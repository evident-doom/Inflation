package inflation.tools

import inflation.ecology.HexFeatures
import inflation.ecology.HexGroup
import inflation.ecology.HexPolicy
import inflation.learning.LearnedModel
import inflation.learning.Role
import inflation.learning.SharedMover
import inflation.run.baselineDir
import inflation.tools.fauna.FaunaCheckOptions
import inflation.world.Date
import java.util.concurrent.atomic.DoubleAdder
import java.util.concurrent.atomic.LongAdder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Whether the people's mover in nets= uses some of its place inputs at all: the band score's pulls ([HexFeatures.KEEPS_OFF]
 * to [HexFeatures.FEAST_DRAW]) and, for scale, a few inputs it surely reads. Over a run (as hex-fauna-check: --world NAME
 * [--years N] [--replicate R]; default 1 year) every band choice among two places or more is weighed again by the same
 * network with one input set to 0 in every candidate, and for each input it says: the share of candidates where it is not 0,
 * how much setting it to 0 moves those candidates' scores (mean |change|) against how far the candidates' scores lie apart
 * within a choice (their spread), and the share of choices whose best place changes; and which way it pulls: its signed
 * contribution (the score with it less the score without it), per unit of the input, averaged over those candidates, and the share
 * of them where it raises the score (for a 0-or-1 input such as [HexFeatures.UNLUCKY_CAMP], > 0 draws bands to the place, < 0 keeps them off). The run itself goes on as it would:
 * the bands still choose by the network, and nothing is drawn from their streams. Report only.
 */
fun main(args: Array<String>) {
    val options = FaunaCheckOptions.parse(if ("--years" in args) args else args + arrayOf("--years", "1"))
    val sim = options.start()
    val groups = sim.groups
    val dir = baselineDir(LearnedModel.configuredNets())
    val file = Role.PEOPLE.file(dir)
    require(file.exists()) { "no people's network in $dir" }
    val mover = SharedMover.load(file)
    val probed = listOf(
        HexFeatures.KEEPS_OFF, HexFeatures.TOWARD_RAID, HexFeatures.TOWARD_SALT, HexFeatures.SOWN_HERE, HexFeatures.HERD_LOSS,
        HexFeatures.UNLUCKY_CAMP, HexFeatures.HOUSE_LEFT, HexFeatures.FEAST_DRAW, HexFeatures.DEATHS_FORESEEN,
        // (For scale: inputs it surely reads.)
        HexFeatures.FOOD, HexFeatures.STAYS, HexFeatures.HOUSES, HexFeatures.APPOINTMENT, HexFeatures.FISH, HexFeatures.WATER_DISTANCE,
    )
    class Acc { val nonzero = LongAdder(); val moved = DoubleAdder(); val signed = DoubleAdder(); val raised = LongAdder(); val choicesWith = LongAdder(); val flipped = LongAdder() }
    val acc = probed.associateWith { Acc() }
    val choices = LongAdder(); val candidates = LongAdder(); val spread = DoubleAdder()
    val scratch = ThreadLocal.withInitial { mover.Scratch() }
    fun best(s: FloatArray, n: Int): Int { var b = 0; for (k in 1 until n) if (s[k] > s[b]) b = k; return b }
    for (s in groups.kinds) if (Role.of(s) == Role.PEOPLE) {
        val p = groups.policies[s] ?: continue
        groups.policies[s] = object : HexPolicy {
            override fun choose(g: HexGroup, features: Array<FloatArray>, baseline: FloatArray, rnd: kotlin.random.Random): Int {
                val chosen = p.choose(g, features, baseline, rnd)
                val n = features.size
                if (n < 2) return chosen
                val sc = scratch.get(); val state = g.band?.state; val temper = g.temper
                val base = mover.netScores(g.species, features, state, temper, sc).copyOf(n)
                val top = best(base, n)
                var mean = 0.0; for (k in 0 until n) mean += base[k]; mean /= n
                var v = 0.0; for (k in 0 until n) v += (base[k] - mean) * (base[k] - mean)
                choices.increment(); candidates.add(n.toLong()); spread.add(sqrt(v / n))
                for (slot in probed) {
                    var any = false
                    for (k in 0 until n) if (features[k][slot] != 0f) { any = true; break }
                    if (!any) continue
                    val a = acc.getValue(slot)
                    val zeroed = Array(n) { k -> features[k].copyOf().also { it[slot] = 0f } }
                    val off = mover.netScores(g.species, zeroed, state, temper, sc)
                    var nz = 0L; var moved = 0.0; var signed = 0.0; var raised = 0L
                    for (k in 0 until n) if (features[k][slot] != 0f) {
                        val d = base[k] - off[k]
                        nz++; moved += abs(d); signed += d / features[k][slot]; if (d > 0f) raised++
                    }
                    a.nonzero.add(nz); a.moved.add(moved); a.signed.add(signed); a.raised.add(raised); a.choicesWith.increment()
                    if (best(off, n) != top) a.flipped.increment()
                }
                return chosen
            }
            override fun scores(g: HexGroup, features: Array<FloatArray>, baseline: FloatArray) = p.scores(g, features, baseline)
            override fun readsBaseline(g: HexGroup) = p.readsBaseline(g)
            override fun planned(g: HexGroup, chosen: FloatArray) = p.planned(g, chosen)
        }
    }
    val steps = options.years * (Date.DAYS_PER_YEAR / options.cfg.daysPerStep)
    repeat(steps) { sim.step() }
    val c = choices.sum().coerceAtLeast(1); val cand = candidates.sum().coerceAtLeast(1)
    println("net-input-use: people's mover $file, ${options.years} y, ${choices.sum()} band choices among 2+ places, ${cand / c} places a choice on average")
    println("mean spread of the candidates' scores within a choice (sd): %.4f".format(spread.sum() / c))
    println("%-26s %12s %12s %14s %10s %12s %14s %10s".format("input", "not 0 (cand)", "choices with", "mean |change|", "/ spread", "best flips", "pull per unit", "raises"))
    for (slot in probed) {
        val a = acc.getValue(slot); val nz = a.nonzero.sum(); val with = a.choicesWith.sum()
        val m = if (nz > 0) a.moved.sum() / nz else 0.0
        println("%-26s %11.3f%% %11.3f%% %14.4f %10.3f %11.3f%% %+14.4f %9.1f%%".format(HexFeatures.names[slot], 100.0 * nz / cand, 100.0 * with / c, m,
            m / (spread.sum() / c).coerceAtLeast(1e-9), if (with > 0) 100.0 * a.flipped.sum() / with else 0.0,
            if (nz > 0) a.signed.sum() / nz else 0.0, if (nz > 0) 100.0 * a.raised.sum() / nz else 0.0))
    }
}
