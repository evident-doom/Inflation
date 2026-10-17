package inflation.tools

import inflation.tools.fauna.FaunaCheckOptions
import inflation.world.Date
import kotlin.math.hypot
import inflation.ecology.get
import inflation.ecology.set
import inflation.ecology.contains
import inflation.ecology.Place
import inflation.ecology.Hex
import inflation.ecology.hexOf
import inflation.ecology.m2

/**
 * Whether the groups follow their kind's sense of better country (the pull the viewer draws as yellow
 * arrows, see [inflation.ecology.HexBearings]): for each group kind, month by month over the last year
 * of a run, how often its groups change hex in a week, how far they go, how well a week's move and a
 * month's net move line up with the pull where they set out (cosine: 1 straight along it, 0 across or at
 * random, -1 against it), how far along it they get, how strong the pull is where they stand (lower when
 * they are in the country it points to), and how high they stand. Run once with nets= in the config
 * and once without to set the learned networks beside the hand-written choices.
 *
 * Usage: hex-pull-check (as hex-fauna-check: --world NAME [--years N] [--replicate R], ...; default 2 years, the last one measured)
 */
fun main(args: Array<String>) {
    val options = FaunaCheckOptions.parse(if ("--years" in args) args else args + arrayOf("--years", "2"))
    val sim = options.start()
    val groups = sim.groups; val hex = sim.hw.hex; val placeHex = sim.hw.placeHex; val elev = sim.hw.placeElevationM
    val b = groups.bearings
    val kinds = groups.kinds
    val perYear = Date.DAYS_PER_YEAR / options.cfg.daysPerStep
    val steps = options.years * perYear
    // Pulls weaker than this have no way worth reading.
    val minPull = 0.05f
    // [kind][month] sums.
    class Acc {
        val weeks = DoubleArray(12); val moved = DoubleArray(12); val km = DoubleArray(12); val cosN = DoubleArray(12); val cos = DoubleArray(12)
        val pull = DoubleArray(12); val elev = DoubleArray(12); val stepsSeen = IntArray(12)
        val mN = DoubleArray(12); val mKm = DoubleArray(12); val mAlong = DoubleArray(12); val mCosN = DoubleArray(12); val mCos = DoubleArray(12)
    }
    val acc = Array(kinds.size) { Acc() }
    val kindOf = kinds.withIndex().associate { it.value to it.index }
    // Where each group stood at the start of the month, and the pull there then (east, south).
    class Start(val hex: Int, val e: Float, val s: Float)
    var month = -1
    var starts = HashMap<Int, Start>()
    val t0 = System.nanoTime()
    repeat(steps) { w ->
        val before = HashMap<Int, Int>(groups.groups.size * 2)
        for (g in groups.groups) if (g.alive) before[g.id] = placeHex[g.place]
        sim.step()
        val m = b.month
        val measuring = w >= steps - perYear
        if (m != month) {
            // The month just ended: each group's net move from where it stood at its start to where it stood before this step.
            if (measuring && month >= 0) for (g in groups.groups) {
                val st = starts[g.id] ?: continue
                val end = before[g.id] ?: continue
                val k = kindOf[g.species] ?: continue
                val (x0, y0) = hex.centreKm(st.hex); val (x1, y1) = hex.centreKm(end)
                val dx = x1 - x0; val dy = y1 - y0; val d = hypot(dx, dy)
                val a = acc[k]
                a.mN[month]++; a.mKm[month] += d
                val ps = hypot(st.e, st.s)
                if (ps >= minPull) {
                    a.mAlong[month] += (dx * st.e + dy * st.s) / ps
                    if (d > 0.5 * hex.hexKm) { a.mCosN[month]++; a.mCos[month] += (dx * st.e + dy * st.s) / (ps * d) }
                }
            }
            month = m
            starts = HashMap()
            for (g in groups.groups) {
                val h = before[g.id] ?: continue
                val k = kindOf[g.species] ?: continue
                starts[g.id] = Start(h, b.east[k][h], b.south[k][h])
            }
        }
        if (!measuring) return@repeat
        for (a in acc) a.stepsSeen[m]++
        for (g in groups.groups) {
            if (!g.alive) continue
            val h0 = before[g.id] ?: continue
            val k = kindOf[g.species] ?: continue
            val a = acc[k]
            val h1 = placeHex[g.place]
            val e = b.east[k][h0]; val s = b.south[k][h0]; val ps = hypot(e, s)
            a.weeks[m]++; a.pull[m] += ps; a.elev[m] += elev[g.place]
            if (h1 == h0) continue
            val (x0, y0) = hex.centreKm(h0); val (x1, y1) = hex.centreKm(h1)
            val dx = x1 - x0; val dy = y1 - y0; val d = hypot(dx, dy)
            a.moved[m]++; a.km[m] += d
            if (ps >= minPull) { a.cosN[m]++; a.cos[m] += (dx * e + dy * s) / (ps * d) }
        }
    }
    println("pull check: %d years, the last measured, in %.0f s; %s".format(options.years, (System.nanoTime() - t0) / 1e9,
        if (inflation.Config.string("nets") != null) "learned networks (nets=${inflation.Config.string("nets")})" else "hand-written choices"))
    println("cosine: 1 along the pull, 0 across it or at random, -1 against; pulls under $minPull left out of cosines and km along")
    fun ratio(x: DoubleArray, n: DoubleArray, m: Int, fmt: String) = if (n[m] > 0) fmt.format(x[m] / n[m]) else "     -"
    fun yr(x: DoubleArray, n: DoubleArray, fmt: String) = if (n.sum() > 0) fmt.format(x.sum() / n.sum()) else "     -"
    fun row(label: String, f: (Int) -> String, year: String) = println("  %-26s".format(label) + (0 until 12).joinToString(" ") { f(it) } + "  | " + year)
    println("%-28s".format("month (0 = mid Dec-mid Jan)") + (0 until 12).joinToString(" ") { "%6d".format(it) } + "  | year")
    for ((k, s) in kinds.withIndex()) {
        val a = acc[k]
        if (a.weeks.sum() <= 0) continue
        val groupsN = DoubleArray(12) { m -> if (a.stepsSeen[m] > 0) a.weeks[m] / a.stepsSeen[m] else 0.0 }
        println("== ${s.name} (groups ${"%.0f".format(groupsN.average())})")
        row("weeks changing hex", { m -> ratio(a.moved, a.weeks, m, "%6.2f") }, yr(a.moved, a.weeks, "%6.2f"))
        row("km a week", { m -> ratio(a.km, a.weeks, m, "%6.1f") }, yr(a.km, a.weeks, "%6.1f"))
        row("week's move: cosine", { m -> ratio(a.cos, a.cosN, m, "%6.2f") }, yr(a.cos, a.cosN, "%6.2f"))
        row("month's net move km", { m -> ratio(a.mKm, a.mN, m, "%6.1f") }, yr(a.mKm, a.mN, "%6.1f"))
        row("  of it along the pull km", { m -> ratio(a.mAlong, a.mN, m, "%6.1f") }, yr(a.mAlong, a.mN, "%6.1f"))
        row("  cosine", { m -> ratio(a.mCos, a.mCosN, m, "%6.2f") }, yr(a.mCos, a.mCosN, "%6.2f"))
        row("pull where they stand", { m -> ratio(a.pull, a.weeks, m, "%6.2f") }, yr(a.pull, a.weeks, "%6.2f"))
        row("height m", { m -> ratio(a.elev, a.weeks, m, "%6.0f") }, yr(a.elev, a.weeks, "%6.0f"))
    }
}
