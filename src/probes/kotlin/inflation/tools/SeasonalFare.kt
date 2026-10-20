package inflation.tools

import inflation.ecology.enumFloats
import inflation.ecology.disease
import inflation.ecology.EnumFloats
import inflation.ecology.Danger
import inflation.ecology.Place
import inflation.Args
import inflation.ecology.HexFauna
import inflation.ecology.HexSim
import inflation.ecology.HexSnapshot
import inflation.learning.LearnedModel
import inflation.run.TestWorlds
import inflation.world.WorldCache
import inflation.world.WorldConfig

/**
 * Where each plant-eating kind fared best at each time of year, by height: for each kind, month and height band, the
 * share of its heads there and the heads-weighted share of its grown that would die in a week where they stood
 * ([inflation.ecology.HexFauna.stayOdds], besides old age), and how well fed they were. What a memory of where the kind
 * fared best would draw it towards, against where it is, and its heads in the first and last counted week. Counted
 * after [--skip] weeks.
 *
 * Usage: seasonal-fare (--world NAME | --seed N [--width KM] [--height KM]) [--weeks N] [--skip N] [--kinds a,b]
 */
fun main(args: Array<String>) {
    val a = Args(args, "seasonal-fare (--world NAME | --seed N [--width KM] [--height KM]) [--weeks N] [--skip N] [--kinds a,b]")
    val named = a.string("--world")?.let { TestWorlds.named(it) }
    var cfg = named?.config ?: WorldConfig(seed = 1)
    cfg = cfg.copy(seed = a.long("--seed", cfg.seed), widthKm = a.int("--width", cfg.widthKm), heightKm = a.int("--height", cfg.heightKm))
    val weeks = a.int("--weeks", 104); val skip = a.int("--skip", 52)
    val names = a.string("--kinds")?.split(",")?.map { it.trim().replace('_', ' ') }
    a.done()
    val sim = HexSim(WorldCache.load(cfg) { })
    named?.let { TestWorlds.check(it, sim); HexSnapshot.load(sim, it.state); println("carrying on ${it.state} at ${sim.weather.date}") }
    LearnedModel.fromConfig(sim.groups) { println(it) }
    val fauna = sim.fauna; val land = sim.land; val hw = sim.hw; val n = land.n
    val kinds = fauna.kinds.indices.filter { k -> fauna.room[k] != null && (names == null || fauna.kinds[k].name in names) }
    val bandsM = floatArrayOf(500f, 1000f, 1500f)
    fun band(p: Int): Int { val e = hw.placeElevationM[p]; var b = 0; while (b < bandsM.size && e >= bandsM[b]) b++; return b }
    val nb = bandsM.size + 1
    // [kind][month][band]
    val heads = Array(kinds.size) { Array(12) { DoubleArray(nb) } }; val die = Array(kinds.size) { Array(12) { DoubleArray(nb) } }
    val fed = Array(kinds.size) { Array(12) { DoubleArray(nb) } }
    // [kind][band][cause]: heads-weighted weekly shares over the counted weeks, by cause.
    val causes = listOf<(EnumFloats<Danger>) -> Float>({ it[Danger.HUNGER] }, { it[Danger.COLD] }, { it[Danger.THIRST] }, { it.disease() }, { it[Danger.SNOW_A] }, { it[Danger.HUNTED] })
    val causeNames = listOf("hunger", "cold", "thirst", "disease", "snow", "hunted")
    val byCause = Array(kinds.size) { Array(nb) { DoubleArray(causes.size) } }; val yearHeads = Array(kinds.size) { DoubleArray(nb) }
    val out = enumFloats<Danger>()
    val first = DoubleArray(kinds.size); val last = DoubleArray(kinds.size)
    for (w in 0 until weeks) {
        sim.step()
        if (w < skip) continue
        val m = sim.weather.date.month
        last.fill(0.0)
        for ((i, k) in kinds.withIndex()) for (p in 0 until n) {
            val h = ((fauna.adults[k][p] + fauna.young[k][p]) * land.areaKm2[p]).toDouble(); if (h <= 0.0) continue
            val b = band(p); last[i] += h
            heads[i][m][b] += h; die[i][m][b] += h * fauna.stayOdds(k, Place(p), sim.weather, out); fed[i][m][b] += h * fauna.fed[k][p]
            yearHeads[i][b] += h; for ((c, z) in causes.withIndex()) byCause[i][b][c] += h * z(out)
        }
        if (w == skip) last.copyInto(first)
    }
    val label = listOf("<500", "500-1000", "1000-1500", ">=1500")
    println("month:                            " + (0 until 12).joinToString(" ") { "%6d".format(it + 1) })
    for ((i, k) in kinds.withIndex()) {
        println("%s  (heads %.0f -> %.0f)".format(fauna.kinds[k].name, first[i], last[i]))
        for (b in 0 until nb) {
            if ((0 until 12).all { heads[i][it][b] <= 0.0 }) continue
            fun row(what: String, f: (Int) -> String) = println("  %-10s %-20s %s".format(label[b], what, (0 until 12).joinToString(" ") { m -> if (heads[i][m][b] > 0) f(m) else "     -" }))
            row("share of heads") { m -> "%6.3f".format(heads[i][m][b] / heads[i][m].sum()) }
            row("dying %/week") { m -> "%6.2f".format(100 * die[i][m][b] / heads[i][m][b]) }
            row("fed") { m -> "%6.2f".format(fed[i][m][b] / heads[i][m][b]) }
            if (yearHeads[i][b] > 0) println("  %-10s %-20s %s".format(label[b], "%/week by cause", causes.indices.joinToString("  ") { c -> "%s %.3f".format(causeNames[c], 100 * byCause[i][b][c] / yearHeads[i][b]) }))
        }
    }
}
