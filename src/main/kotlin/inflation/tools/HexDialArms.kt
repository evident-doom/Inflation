package inflation.tools

import inflation.Config
import inflation.ecology.BandInputs
import inflation.ecology.BandWays
import inflation.ecology.WAYS_OUT
import inflation.learning.HexPpo
import inflation.learning.Outcomes
import inflation.learning.Schema
import inflation.tools.fauna.FaunaCheckOptions
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Which of the bands' ways change the return people are trained on at all, read from one world: the bands alive at the start are dealt
 * into arms of [armSize] bands each, a control arm that keeps to the scaffold's ways and an arm for each way at each value tried, whose
 * bands hold that one way at that value (the others at the scaffold's) for the whole run. Each band's return is worked out as training
 * does ([HexPpo.play]: its and its offshoots' numbers over the next weeks against its numbers at the start, discounted
 * [HexPpo.PEOPLE_DISCOUNT], less their luck in births and deaths), at 13 weeks and at the run's length, and each arm's mean is set against the
 * control's. A way no arm's return shows is one no learner can learn from by the return.
 *
 * The world is a test world or a saved one, as for hex-fauna-check (`--world NAME`, `--replicate R`, `--seed`, `--width`, `--height`,
 * `--load FILE`); moves and the bands' ways are as the config sets them (`nets=DIR` for learned networks, else hand-written moves and the scaffold's ways),
 * except that each arm holds its one way at its value.
 * Config keys: dialArmSize (default 100, bands in each arm; fewer if the world has too few bands for them), dialArmWeeks (default 26, the weeks
 * the ways are held and the return covers), dialArmWays (default all; the numbers of the ways, from 0 in the order of the network's outputs,
 * comma-separated), dialArmValues (default 0.5,1.5; multiples of the scaffold's, between 0 and 2), dialArmGroups (default false; true: an arm for each group of ways, each band scrambling the group's ways between the values at random),
 * dialArmStressTop (default 0; a share of the bands, those under the most stress after a first week, from which the arms are dealt, to see whether the ways matter more to them than to all).
 *
 * Usage: hex-dial-arms [--world NAME] [--replicate R] [--seed N] [--width KM] [--height KM] [--load FILE]
 */
fun main(args: Array<String>) {
    val opts = FaunaCheckOptions.parse(args)
    val armSize = Config.int(Config.Key.DIAL_ARM_SIZE, 100)
    val weeks = Config.int(Config.Key.DIAL_ARM_WEEKS, 26)
    val tested = Config.string(Config.Key.DIAL_ARM_WAYS)?.split(',')?.map { it.trim().toInt() } ?: (0 until WAYS_OUT).toList()
    val values = Config.string(Config.Key.DIAL_ARM_VALUES)?.split(',')?.map { it.trim().toFloat() } ?: listOf(0.5f, 1.5f)
    require(armSize > 1 && weeks >= 13) { "dialArmSize needs 2 or more bands and dialArmWeeks 13 or more" }
    require(tested.all { it in 0 until WAYS_OUT }) { "dialArmWays=$tested: the ways are numbered 0..${WAYS_OUT - 1}" }
    val names = Schema.WAYS_OUT
    /** An arm's ways, each held at one of [outputs] (the network outputs for the values tried): the same one for all its bands, or, if [scramble], one drawn for each band and way. */
    class Arm(val name: String, val ways: List<Int>, val outputs: List<Float>, val scramble: Boolean = false) {
        val bands = ArrayList<Int>()
        fun output(band: Int, way: Int) = if (scramble) outputs[Random(band * 104_729L + way).nextInt(outputs.size)] else outputs[0]
    }
    val byGroup = Config.flag(Config.Key.DIAL_ARM_GROUPS, false)
    val arms = if (byGroup) {
        // A group is the ways whose names share what comes before the colon (the efforts, the time weights, the tools made first...), the others one group of their own;
        // its arm scrambles all of them: each band holds each at 0.5 or 1.5 (the values tried), drawn for it alone.
        val groups = tested.groupBy { j -> names[j].substringBefore(':', "single ways") }
        listOf(Arm("control", emptyList(), emptyList())) + groups.map { (g, js) -> Arm("$g (${js.size} ways)", js, values.map { forcedWayOutput(it) }, true) } +
            Arm("all tried (${tested.size} ways)", tested, values.map { forcedWayOutput(it) }, true)
    } else listOf(Arm("control", emptyList(), emptyList())) + tested.flatMap { j -> values.map { v -> Arm("${names[j]} x$v", listOf(j), listOf(forcedWayOutput(v))) } }

    val stressTop = Config.float(Config.Key.DIAL_ARM_STRESS_TOP, 0f)
    require(stressTop in 0f..1f) { "dialArmStressTop is a share, 0..1" }
    val sim = opts.start()
    if (stressTop > 0f) sim.step() // (The bands' view of themselves is drawn at the start of a week.)
    fun stress(g: inflation.ecology.HexGroup): Float { val s = g.band?.state ?: return 0f; return if (s.size <= BandInputs.COLD_ODDS) 0f else max(s[BandInputs.COLD_ODDS], 1f - s[BandInputs.FED]).coerceIn(0f, 1f) }
    val everyone = sim.groups.groups.filter { it.alive && it.species.human && it.band != null }
    val humans = if (stressTop > 0f) everyone.sortedByDescending { stress(it) }.take(max(1, (everyone.size * stressTop).toInt())).also { top ->
        println("%d of %d bands under the most stress taken: stress %.3f to %.3f (all bands: mean %.3f, max %.3f)".format(top.size, everyone.size,
            stress(top.last()), stress(top.first()), everyone.map { stress(it) }.average(), everyone.maxOf { stress(it) }))
    } else everyone
    val per = minOf(armSize, humans.size / arms.size)
    require(per >= 2) { "${humans.size} bands for ${arms.size} arms: name fewer ways (dialArmWays) or values (dialArmValues)" }
    if (per < armSize) println("only ${humans.size} bands for ${arms.size} arms: $per in each, not $armSize")
    val order = humans.map { it.id }.shuffled(Random(opts.cfg.seed * 31L + 7L))
    for ((i, id) in order.take(per * arms.size).withIndex()) arms[i % arms.size].bands += id
    val armOf = HashMap<Int, Arm>().also { m -> for (a in arms) for (id in a.bands) m[id] = a }
    // (The ways are the learned networks' if the config names them (nets=, ways=): each arm then holds its one way at its value in place of the network's.)
    val base = sim.groups.ways
    sim.groups.ways = BandWays { g, inputs, out ->
        base?.choose(g, inputs, out)
        armOf[g.id]?.let { a -> for (w in a.ways) out[w] = a.output(g.id, w) }
    }

    val outcomes = Outcomes()
    val start = humans.associate { it.id to it.size }
    var week = 0
    outcomes.record(week, sim.groups.groups, sim.groups.merged)
    repeat(weeks) {
        sim.step(); week++
        outcomes.record(week, sim.groups.groups, sim.groups.merged)
    }
    val horizons = listOf(13, weeks).distinct()
    fun ret(id: Int, h: Int): Double {
        val own = outcomes.own(id, 0, h)
        var sum = 0.0; var norm = 0.0; var d = 1.0
        for (k in 1..h) { sum += d * own[k - 1] / max(1, start.getValue(id)); norm += d; d *= HexPpo.PEOPLE_DISCOUNT }
        return sum / norm
    }
    fun mean(x: List<Double>) = x.average()
    fun se(x: List<Double>): Double { val m = x.average(); return sqrt(x.sumOf { (it - m) * (it - m) } / max(1, x.size - 1) / x.size) }
    val control = arms[0]
    println("${humans.size} bands at the start, $per in each of ${arms.size} arms (control and ${tested.size} ways at ${values.joinToString(", ")}), held $weeks weeks")
    for (h in horizons) {
        val c = control.bands.map { ret(it, h) }
        val sd = se(c) * sqrt(c.size.toDouble())
        println("\nreturn over $h weeks: control mean %.5f, SD across bands %.5f; a change of %.5f would show at about two standard errors (arm against control)".format(mean(c), sd, 2 * sd * sqrt(2.0 / per)))
        val rows = arms.drop(1).map { a ->
            val r = a.bands.map { ret(it, h) }
            val diff = mean(r) - mean(c); val e = sqrt(se(r) * se(r) + se(c) * se(c))
            Triple(a.name, diff, e)
        }
        println("  by |t|, largest first (change against control +- standard error, t):")
        for ((n, d, e) in rows.sortedByDescending { abs(it.second / it.third) }.take(20)) println("  %-34s %+.5f +- %.5f  t %+.1f".format(n, d, e, d / e))
        val shown = rows.count { abs(it.second / it.third) > 2 }
        println("  arms with |t| > 2: $shown of ${rows.size} (about ${(rows.size * 0.05).toInt()} would by chance alone)")
        // The control is one small arm: its own chance in the mean moves every comparison the same way. The other arms against their own
        // mean, and how far their means spread beyond what the bands' spread inside the arms gives (about 1 if no way matters).
        val others = arms.drop(1).map { a -> a.bands.map { ret(it, h) } }
        val means = others.map { mean(it) }; val grand = mean(means)
        val within = others.sumOf { r -> r.sumOf { (it - mean(r)) * (it - mean(r)) } } / max(1, others.sumOf { it.size - 1 })
        val spread = means.sumOf { (it - grand) * (it - grand) } / max(1, means.size - 1)
        val offGrand = arms.drop(1).indices.count { i -> abs(means[i] - grand) / sqrt(within / others[i].size) > 2 }
        println("  against the other arms' mean instead (control %+.5f from it): arms with |t| > 2: %d of %d; the arms' means spread %.2f times what the bands' spread inside the arms gives (1: no way matters)"
            .format(mean(control.bands.map { ret(it, h) }) - grand, offGrand, rows.size, sqrt(spread / (within / per))))
        val quiet = if (byGroup) emptyList() else tested.filter { j -> rows.filter { it.first.startsWith(names[j] + " x") }.all { abs(it.second / it.third) < 1 } }
        println("  ways with every arm under 1 standard error: ${quiet.joinToString(", ") { names[it] }.ifEmpty { "none" }}")
    }
}
