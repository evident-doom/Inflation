package inflation.learning

import inflation.ecology.BandWays
import inflation.ecology.Catalogue
import inflation.ecology.HexGroup
import inflation.ecology.Species
import inflation.ecology.WAYS_IN
import inflation.ecology.WAYS_OUT
import inflation.world.WorldConfig
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Bands' ways learned as their moves are ([HexPpo]): a network gives, from what a band knows of
 * itself ([inflation.ecology.bandInputs]), the middle of each of its ways ([inflation.ecology.setWays]:
 * 0 is the scaffold's way), and while learning each way is drawn about that middle with a spread
 * of its own ([logStd], learned too); otherwise the band keeps to the middle. Saved as `ways.mlp`
 * (readable wherever the evolved ways were, e.g. `ways`) with its spreads beside it (`.std`).
 */
class LearnedWays(val net: Mlp, val logStd: FloatArray) {
    fun copy() = LearnedWays(net.copy(), logStd.copyOf())

    fun save(file: File) {
        Schema.save(net, file, Schema.WAYS_IN, Schema.WAYS_OUT)
        File(file.path + ".std").writeText(logStd.joinToString(" ") { exp(it).toString() } + "\n")
    }

    companion object {
        /** How widely each way is drawn about its middle at first (in the network's output, where 1 doubles or halves the way at most). */
        const val START_STD = 0.3f
        const val MIN_STD = 0.03f
        const val MAX_STD = 1f

        /** A network that keeps to the scaffold's ways (its last layer at zero). */
        fun fresh(hidden: Int, rnd: Random): LearnedWays {
            val m = Mlp.random(intArrayOf(WAYS_IN, hidden, WAYS_OUT), rnd).zeroOutputLayer()
            return LearnedWays(m, FloatArray(WAYS_OUT) { ln(START_STD) })
        }

        /** The ways in [file] (any earlier ways network, the evolved ones too, fitted by name), or fresh ones. */
        fun loadOrNew(file: File, hidden: Int): LearnedWays {
            if (!file.exists()) return fresh(hidden, Random(11))
            val net = Schema.load(file, Schema.WAYS_IN, Schema.WAYS_OUT)
            val std = File(file.path + ".std").takeIf { it.exists() }?.readText()?.trim()?.split(" ")?.map { ln(it.toFloat()) }
            return LearnedWays(net, FloatArray(WAYS_OUT) { std?.getOrNull(it) ?: ln(START_STD) })
        }
    }
}

/** One band's ways for a week, as drawn: what it knew of itself, the ways it took and their log-probability. */
class WaysChoice(kind: Species, groupId: Int, size: Int, week: Int, val x: FloatArray, val a: FloatArray, logp: Float) :
    Choice(kind, groupId, size, week, arrayOf(x), 0, logp)

/**
 * Sets bands' ways with [ways]: drawn about the network's middle while learning (recording a share,
 * [rate], of the draws in [record]), the middle itself otherwise ([record] null). Each band's draw
 * comes from its own stream for the week, so a seed gives the same run.
 */
class WaysSampler(private val ways: LearnedWays, private val record: ConcurrentLinkedQueue<Choice>?, private val rate: () -> Float, private val week: () -> Int, private val seed: Long) : BandWays {
    private val buffers = ThreadLocal.withInitial { ways.net.buffers() }
    override fun choose(g: HexGroup, inputs: FloatArray, out: FloatArray) {
        val b = buffers.get()
        inputs.copyInto(b.a[0])
        val mean = ways.net.forwardAll(b)
        if (record == null) { mean.copyInto(out); return }
        // A band keeps its draw about the middle for a season ([WaysPpo.SEASON] weeks, its own seasons
        // staggered by its id), and the draw is judged once, by that season: a week's ways alone make no
        // difference a return can show (see tools/HexChoiceEffect).
        val w = week()
        val t = w + g.id % WaysPpo.SEASON
        val season = t / WaysPpo.SEASON
        val rnd = Random(seed * 1_000_003L + g.id * 7_919L + season * 104_729L)
        // Only the bands whose season is recorded to learn from draw their ways by chance; the rest keep to the middle.
        if (rnd.nextFloat() >= rate()) { mean.copyInto(out); return }
        var logp = 0f
        for (i in 0 until WAYS_OUT) {
            val z = gaussian(rnd)
            out[i] = mean[i] + exp(ways.logStd[i]) * z
            logp += -0.5f * z * z - ways.logStd[i]
        }
        if (t % WaysPpo.SEASON == 0) record += WaysChoice(g.species, g.id, g.size, w, inputs.copyOf(), out.copyOf(WAYS_OUT), logp)
    }
}

/**
 * Learning the bands' ways by proximal policy optimisation, from the draws [WaysSampler] recorded
 * and the returns [HexPpo.play] gave them (the same as their moves get): a critic learns what
 * return a band can expect from what it knows of itself, and each draw is judged by how much
 * better than that it turned out; then a few passes of clipped policy gradient move each way's
 * middle toward the draws that did better, and its spread narrows or widens as they show.
 */
object WaysPpo {
    /** Ways draws recorded a year in each world (bands are many; a share is enough). */
    const val PER_YEAR = 6000
    const val CLIP = 0.2f
    /** Pull toward keeping the spreads wide (per way, on its log). */
    const val ENTROPY = 0.002f
    /** Passes of the critic over each round's draws. */
    const val CRITIC_EPOCHS = 8

    /** The ways and their critic, as kept in a training directory. */
    class State(val dir: File, hidden: Int, rate: Float) {
        val file = File(dir, "ways.mlp")
        val criticFile = File(dir, "ways.value.mlp")
        val ways = LearnedWays.loadOrNew(file, hidden)
        val critic: Mlp = if (criticFile.exists()) Schema.load(criticFile, Schema.WAYS_IN, listOf("value")) else
            // (A new critic expects every band to hold its numbers until it has learned better.)
            Mlp.random(intArrayOf(WAYS_IN, hidden, 1), Random(91)).zeroOutputLayer().also { m -> m.w[m.w.size - 1] = 1f }
        val adam = Adam(ways.net.w.size, rate)
        val adamStd = Adam(WAYS_OUT, rate)
        val adamCritic = Adam(critic.w.size, 1e-3f)

        fun save() {
            ways.save(file)
            Schema.save(critic, criticFile, Schema.WAYS_IN, listOf("value"))
        }
    }

    /** Weeks a band keeps one draw of its ways, and over which the draw is judged (as people's moves are, [HexPpo.PEOPLE_HORIZON]). */
    const val SEASON = HexPpo.PEOPLE_HORIZON

    /** The share of bands' seasonal draws of their ways to record in a world with [bands] bands. */
    fun rate(bands: Int, stepsPerYear: Int) = if (bands == 0) 0f else min(1f, PER_YEAR.toFloat() * SEASON / (bands * stepsPerYear))

    /** One round of learning from [cs] (with their returns); a line saying how it went. */
    fun learn(st: State, cs: List<WaysChoice>, round: Int): String {
        if (cs.isEmpty()) return "ways: no draws"
        val critic = st.critic
        // ---- the critic: least squares toward the returns seen
        repeat(CRITIC_EPOCHS) { epoch ->
            for (batch in cs.shuffled(Random(round * 17L + epoch + 5)).chunked(256)) {
                val parts = inParts(batch) { part ->
                    val g = FloatArray(critic.w.size); val b = critic.buffers()
                    for (c in part) { c.x.copyInto(b.a[0]); val v = critic.forward(b); critic.backward(b, 2f * (v - c.ret) / batch.size, g) }
                    g
                }
                val g = FloatArray(critic.w.size); for (p in parts) g.add(p)
                st.adamCritic.step(critic.w, g)
            }
        }
        // ---- advantages: against the critic (or, while it explains nothing yet, the average), centred and scaled
        val cb = critic.buffers()
        var gap = FloatArray(cs.size) { k -> cs[k].x.copyInto(cb.a[0]); cs[k].ret - critic.forward(cb) }
        val rets = cs.map { it.ret.toDouble() }; val rm = rets.average()
        fun spread(x: FloatArray): Double { val m = x.average(); return x.sumOf { (it - m) * (it - m) } }
        val explained = 1.0 - spread(gap) / rets.sumOf { (it - rm) * (it - rm) }.coerceAtLeast(1e-12)
        if (explained < 0.0) gap = FloatArray(cs.size) { cs[it].ret - rm.toFloat() }
        val mean = gap.average().toFloat()
        val sd = sqrt(gap.map { ((it - mean) * (it - mean)).toDouble() }.average()).toFloat().coerceAtLeast(1e-3f)
        for ((k, c) in cs.withIndex()) c.advantage = (gap[k] - mean) / sd
        // ---- clipped policy gradient on the middles and the spreads
        val ways = st.ways
        var clippedShare = 0.0; var seen = 0
        repeat(4) { epoch ->
            for (batch in cs.shuffled(Random(round * 31L + epoch + 7)).chunked(512)) {
                val parts = inParts(batch) { part -> gradient(ways, part, 1f / batch.size) }
                val gw = FloatArray(ways.net.w.size); val gs = FloatArray(WAYS_OUT); var clipped = 0
                for ((a, s, c) in parts) { gw.add(a); gs.add(s); clipped += c }
                for (i in gs.indices) gs[i] -= ENTROPY
                st.adam.step(ways.net.w, gw); st.adamStd.step(ways.logStd, gs)
                for (i in ways.logStd.indices) ways.logStd[i] = ways.logStd[i].coerceIn(ln(LearnedWays.MIN_STD), ln(LearnedWays.MAX_STD))
                if (epoch == 3) { clippedShare += clipped; seen += batch.size }
            }
        }
        val stds = ways.logStd.map { exp(it) }
        return "ways %d draws, critic explains %.2f%s, clipped %.2f, spread %.3f (%.3f..%.3f)".format(cs.size, explained, if (explained < 0.0) " (judged against the average)" else "", clippedShare / seen.coerceAtLeast(1), stds.average(), stds.min(), stds.max())
    }

    /** The clipped surrogate's gradient over [part], to be minimised: for the network's weights, the log-spreads, and how many draws were clipped. */
    private fun gradient(ways: LearnedWays, part: List<WaysChoice>, w: Float): Triple<FloatArray, FloatArray, Int> {
        val gw = FloatArray(ways.net.w.size); val gs = FloatArray(WAYS_OUT); val b = ways.net.buffers()
        val dOut = FloatArray(WAYS_OUT); var clippedN = 0
        for (c in part) {
            c.x.copyInto(b.a[0])
            val mu = ways.net.forwardAll(b)
            var logp = 0f
            for (i in 0 until WAYS_OUT) { val z = (c.a[i] - mu[i]) / exp(ways.logStd[i]); logp += -0.5f * z * z - ways.logStd[i] }
            val ratio = exp(logp - c.logp)
            val a = c.advantage
            val clipped = (a > 0f && ratio > 1f + CLIP) || (a < 0f && ratio < 1f - CLIP)
            if (clipped) { clippedN++; continue }
            val coef = a * ratio * w
            for (i in 0 until WAYS_OUT) {
                val s = exp(ways.logStd[i]); val z = (c.a[i] - mu[i]) / s
                // d log p / d middle = z / s; d log p / d log-spread = z^2 - 1 (minimising, so negated).
                dOut[i] = -coef * z / s
                gs[i] += -coef * (z * z - 1f)
            }
            ways.net.backwardAll(b, dOut, gw)
        }
        return Triple(gw, gs, clippedN)
    }

    /**
     * People's score over [years] in [cfg] ([HexPpo.score]: people-weeks per person at the start,
     * less their luck in births and deaths), with the bands keeping to the scaffold's ways and to the
     * middle of the learned ones, everything else hand-written: (learned, scaffold).
     */
    fun check(cfg: WorldConfig, years: Int, kinds: List<Species>, ways: LearnedWays): Pair<Double, Double> {
        val both = listOf(false, true).parallelStream().map { learned ->
            (if (learned) HexPpo.score(cfg, years, kinds) { run -> run.groups.ways = WaysSampler(ways, null, { 0f }, { 0 }, cfg.seed) } else HexPpo.handScore(cfg, years, kinds))[Catalogue.HUMAN] ?: 0.0
        }.toList()
        return both[1] to both[0]
    }
}
