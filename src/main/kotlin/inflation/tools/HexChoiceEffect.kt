package inflation.tools

import inflation.run.freshSim
import inflation.Args
import inflation.Config
import inflation.ecology.BandWays
import inflation.ecology.Catalogue
import inflation.ecology.HexPolicy
import inflation.ecology.WAYS_OUT
import inflation.learning.HexPpo
import inflation.world.WorldConfig
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random
import inflation.learning.Schema
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * How much one band's one choice changes the return people are trained on, against how much that
 * return varies anyway. The same world runs several times from the same seed: hand-written moves and
 * the scaffold's ways, except that at week [warm] half the bands (the same half every run) are made
 * to choose otherwise: a random place other than the hand-written best (one week), ways drawn as in
 * training (one week), or ways drawn once and kept for the year. Each band's return is worked out as
 * training does (its and its offshoots' numbers over the next 52 weeks against its numbers then,
 * discounted 0.98, less its luck in births and deaths). The other half, not made to choose otherwise,
 * shows how far the runs drift apart by the world's own knock-on effects.
 *
 * Usage: hex-choice-effect [--seed N] [--size KM] [--variants A,B]
 * Config keys: choiceWarmWeeks (default 60), the week the choice is forced; choiceNoise (default 0): from the
 * week after the forced one, every band takes a random place other than the best with that chance, and draws
 * its ways as in training, each week (the noise training plays under). choiceWay (default none; the number of a way, from 0, in the
 * order of the network's outputs) with choiceWayValue (default 1, a multiple of the scaffold's, between 0 and 2): the variants
 * force-1wk and force-52wk hold that one way at that value, the other ways at the scaffold's, for the forced half of the
 * bands, for the one week or for the year; they are the variants run by default then, beside the base's two.
 */
fun main(args: Array<String>) {
    val a = Args(args, "hex-choice-effect [--seed N] [--size KM] [--variants A,B]")
    val seed = a.long("--seed", 91L); val size = a.int("--size", 600)
    val warm = Config.int(Config.Key.CHOICE_WARM_WEEKS, 60); val noise = Config.float(Config.Key.CHOICE_NOISE, 0f)
    val forceWay = Config.int(Config.Key.CHOICE_WAY, -1)
    require(forceWay < WAYS_OUT) { "choiceWay=$forceWay: the ways are numbered 0..${WAYS_OUT - 1}" }
    val forceOutput = if (forceWay >= 0) forcedWayOutput(Config.float(Config.Key.CHOICE_WAY_VALUE, 1f)) else 0f
    val variants = a.string("--variants")?.let { listOf("base") + it.split(",") }
        ?: if (forceWay >= 0) listOf("base", "base-again", "force-1wk", "force-52wk")
        else listOf("base", "base-again", "move-1wk", "move-52wk", "ways-1wk", "ways-52wk")
    a.done()
    val horizon = 52; val discount = HexPpo.PEOPLE_DISCOUNT
    val kinds = Catalogue.learned
    fun perturbed(id: Int) = Random(id * 31L + 7).nextFloat() < 0.5f
    fun waysDraw(id: Int, salt: Long) = Random(id * 1_000_003L + salt).let { r -> FloatArray(WAYS_OUT) { gaussian(r) * 0.3f } }

    class Out(val ret: Map<Int, Double>, val retH: Map<Int, DoubleArray>, val draws: Map<Int, FloatArray>,
        val moveAlt: Map<Int, Pair<Float, Float>>, val stepMs: Double)

    fun play(variant: String): Out {
        val run = freshSim(WorldConfig(seed = seed, widthKm = size, heightKm = size), kinds)
        var week = 0
        val alt = ConcurrentHashMap<Int, Pair<Float, Float>>()
        for (s in kinds) if (s.human) run.groups.policies[s] = HexPolicy { g, f, b, _ ->
            val best = b.indices.maxBy { b[it] }
            val r = Random(seed * 7_919L + g.id * 104_729L + week)
            if (week == warm && variant == "move-1wk" && perturbed(g.id) && f.size > 1) {
                var a = r.nextInt(f.size - 1); if (a >= best) a++
                alt[g.id] = b[best] to b[a]
                a
            } else if (week in warm until warm + horizon && variant == "move-52wk" && perturbed(g.id) && f.size > 1) {
                var a = r.nextInt(f.size - 1); if (a >= best) a++; a
            } else if (week > warm && noise > 0f && f.size > 1 && r.nextFloat() < noise) {
                var a = r.nextInt(f.size - 1); if (a >= best) a++; a
            } else best
        }
        val draws = ConcurrentHashMap<Int, FloatArray>()
        run.groups.ways = BandWays { g, _, out ->
            val p = perturbed(g.id)
            if (p && week == warm && variant == "ways-1wk") waysDraw(g.id, 1).also { draws[g.id] = it }.copyInto(out)
            else if (p && week in warm until warm + horizon && variant == "ways-52wk") waysDraw(g.id, 2).also { draws[g.id] = it }.copyInto(out)
            else if (p && forceWay >= 0 && (week == warm && variant == "force-1wk" || week in warm until warm + horizon && variant == "force-52wk")) out[forceWay] = forceOutput
            else if (week > warm && noise > 0f) waysDraw(g.id, 1000L + week).copyInto(out)
        }
        val size0 = HashMap<Int, Int>(); val luck0 = HashMap<Int, Float>()
        val sizeAt = HashMap<Int, IntArray>(); val luckAt = HashMap<Int, FloatArray>()
        val parent = HashMap<Int, Int>(); val born = HashMap<Int, Int>(); val merged = HashMap<Int, Int>()
        var stepNs = 0L
        repeat(warm + horizon + 1) {
            if (week == warm) for (g in run.groups.groups) if (g.alive && g.species.human) { size0[g.id] = g.size; luck0[g.id] = g.luck }
            val t = System.nanoTime(); run.step(); stepNs += System.nanoTime() - t
            week++
            if (week > warm) {
                val i = week - warm - 1
                for (g in run.groups.groups) if (g.species.human) {
                    if (g.parent >= 0 && g.id !in parent) { parent[g.id] = g.parent; born[g.id] = i }
                    if (g.mergedSize >= 0) merged.putIfAbsent(g.id, g.mergedSize)
                    sizeAt.getOrPut(g.id) { IntArray(horizon + 1) }[i] = if (g.alive) g.size else 0
                    luckAt.getOrPut(g.id) { FloatArray(horizon + 1) { Float.NaN } }[i] = g.luck
                }
                for (g in run.groups.merged) if (g.species.human) merged.putIfAbsent(g.id, g.mergedSize)
            }
        }
        val children = HashMap<Int, MutableList<Int>>(); for ((c, p) in parent) children.getOrPut(p) { ArrayList() } += c
        fun luckAt(id: Int, i: Int): Float {
            val l = luckAt[id] ?: return 0f
            var j = i
            while (j > 0 && l[j].isNaN()) j--
            return if (l[j].isNaN()) 0f else l[j]
        }
        fun numbers(id: Int, i: Int, depth: Int = 0): Float {
            val own = sizeAt[id]?.get(i) ?: 0
            var n = (if (own == 0 && merged[id] != null) merged[id]!! else own).toFloat()
            val l0 = luck0[id] ?: (born[id]?.let { luckAt(id, it) } ?: 0f)
            n = max(0f, n - (luckAt(id, i) - l0))
            if (depth < 3) for (c in children[id].orEmpty()) if ((born[c] ?: 0) <= i) n += numbers(c, i, depth + 1)
            return n
        }
        val ret = size0.mapValues { (id, s0) ->
            var sum = 0.0; var norm = 0.0; var d = 1.0
            for (i in 0 until horizon) { sum += d * numbers(id, i) / s0.coerceAtLeast(1); norm += d; d *= discount }
            sum / norm
        }
        val retH = size0.mapValues { (id, s0) -> DoubleArray(HS.size) { h ->
            var sum = 0.0; var norm = 0.0; var d = 1.0
            for (i in 0 until HS[h]) { sum += d * numbers(id, i) / s0.coerceAtLeast(1); norm += d; d *= discount }
            sum / norm } }
        return Out(ret, retH, draws, alt, stepNs / 1e6 / (warm + horizon + 1))
    }

    val outs = variants.parallelStream().map { it to play(it) }.toList().toMap()
    val base = outs.getValue("base").ret
    val ids = base.keys.sorted()
    fun sd(x: List<Double>): Double { val m = x.average(); return sqrt(x.sumOf { (it - m) * (it - m) } / max(1, x.size - 1)) }
    println("seed $seed, size $size km, warm-up $warm weeks, noise $noise: ${ids.size} bands at the forced week; step %.0f ms"
        .format(outs.getValue("base").stepMs))
    println("base return: mean %.4f, SD across bands %.4f".format(ids.map { base.getValue(it) }.average(), sd(ids.map { base.getValue(it) })))
    for (v in variants.drop(1)) {
        val r = outs.getValue(v).ret
        val dp = ids.filter { perturbed(it) && it in r }.map { r.getValue(it) - base.getValue(it) }
        val dc = ids.filter { !perturbed(it) && it in r }.map { r.getValue(it) - base.getValue(it) }
        println("%-10s forced half (n %d): change mean %+.5f +- %.5f, SD %.5f, |change|>0 in %d%% | other half (n %d): mean %+.5f, SD %.5f, |change|>0 in %d%%"
            .format(v,
            dp.size, dp.average(), sd(dp) / sqrt(dp.size.toDouble()), sd(dp), 100 * dp.count { it != 0.0 } / max(1, dp.size),
            dc.size, dc.average(), sd(dc), 100 * dc.count { it != 0.0 } / max(1, dc.size)))
    }
    // By horizon: how the forced bands' change and the other bands' drift grow with the weeks the return covers.
    val bh = outs.getValue("base").retH
    for (v in variants.drop(1)) {
        val r = outs.getValue(v).retH
        println("  %-10s by horizon (weeks: forced mean +- SE / other half SD): ".format(v) + HS.indices.joinToString(", ") { h ->
            val dp = ids.filter { perturbed(it) && it in r }.map { r.getValue(it)[h] - bh.getValue(it)[h] }
            val dc = ids.filter { !perturbed(it) && it in r }.map { r.getValue(it)[h] - bh.getValue(it)[h] }
            "%d: %+.5f +- %.5f / %.5f".format(HS[h], dp.average(), sd(dp) / sqrt(dp.size.toDouble()), sd(dc)) })
    }
    // The moved bands: the change against how far below the best the forced place scored by the hand-written score.
    val mv = outs["move-1wk"]
    val gaps = if (mv == null) emptyList() else mv.moveAlt.entries.filter { it.key in base && it.key in mv.ret }
        .map { (id, p) -> (p.first - p.second).toDouble() to mv.ret.getValue(id) - base.getValue(id) }
    if (gaps.isNotEmpty()) {
        val sorted = gaps.sortedBy { it.first }
        for (q in sorted.chunked((sorted.size + 2) / 3)) println("  move-1wk, hand-written score gap %.3f..%.3f (n %d): change mean %+.5f, SD %.5f".format(
            q.first().first, q.last().first, q.size, q.map { it.second }.average(), sd(q.map { it.second })))
    }
    // The ways: each way's draw against the change it went with (slope per unit of draw, over the forced bands).
    for (v in listOf("ways-1wk", "ways-52wk")) {
        val o = outs[v] ?: continue
        val rows = o.draws.entries.filter { it.key in base && it.key in o.ret }.map { (id, z) -> z to o.ret.getValue(id) - base.getValue(id) }
        if (rows.size < 10) continue
        val slopes = (0 until WAYS_OUT).map { j ->
            val x = rows.map { it.first[j].toDouble() }; val y = rows.map { it.second }
            val mx = x.average(); val my = y.average()
            val sxx = x.sumOf { (it - mx) * (it - mx) }; val sxy = x.indices.sumOf { (x[it] - mx) * (y[it] - my) }
            val b = sxy / sxx; val resid = x.indices.map { y[it] - my - b * (x[it] - mx) }
            val se = sqrt(resid.sumOf { it * it } / (x.size - 2) / sxx)
            b to se
        }
        val names = Schema.WAYS_OUT
        println("$v: slope of the change per 0.3 of draw (one SD), by way, largest |t| first:")
        println("  " + slopes.indices.sortedByDescending { abs(slopes[it].first / slopes[it].second) }.take(10).joinToString(", ") { j ->
            "%s %+.5f (t %.1f)".format(names[j], 0.3 * slopes[j].first, slopes[j].first / slopes[j].second)
        })
    }
}

private val HS = intArrayOf(1, 2, 4, 8, 13, 26, 52)

private fun gaussian(r: Random): Float = inflation.learning.gaussian(r)
