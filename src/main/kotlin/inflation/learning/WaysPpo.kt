package inflation.learning

import inflation.ecology.BandWays
import inflation.ecology.Catalogue
import inflation.ecology.HexGroup
import inflation.ecology.Species
import inflation.ecology.WAYS_IN
import inflation.ecology.WAYS_OUT
import inflation.world.WorldConfig
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
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
        Schema.save(net, file, Schema.WAYS_IN, Schema.WAYS_OUT, Role.PEOPLE.net)
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

/**
 * One band's ways for a season, as drawn: what it knew of itself ([x]), the ways it took ([a]) and their log-probability; and
 * where it stood that week before it moved ([at]), from which the people's critic judges it, as it judges the move (see [WaysPpo]).
 */
class WaysChoice(kind: Species, groupId: Int, size: Int, week: Int, at: Probe, val x: FloatArray, val a: FloatArray, logp: Float) :
    Choice(kind, groupId, size, week, arrayOf(at.features), 0, logp) {
    init { band = at.band; temper = at.temper; group = at.group }
}

/**
 * Sets bands' ways with [ways]: drawn about the network's middle while learning (recording a share,
 * [rate], of the draws in [record]), the middle itself otherwise ([record] null). Each band's draw
 * comes from its own stream for the week, so a seed gives the same run.
 */
class WaysSampler(private val ways: LearnedWays, private val record: ConcurrentLinkedQueue<Choice>?, private val rate: () -> Float, private val week: () -> Int, private val seed: Long,
                  /** Where each band stood this week before it moved ([MoverPolicy]): a draw is recorded only with it. */
                  private val standing: ConcurrentHashMap<Int, Standing>? = null,
                  /** Recorded choices waiting to see their group again ([Choice.probe]), by group. */
                  private val probes: ConcurrentHashMap<Int, MutableList<Choice>>? = null) : BandWays {
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
        if (t % WaysPpo.SEASON != 0) return
        // (A draw is learnt from only where the band was seen choosing its place this week.)
        val at = standing?.get(g.id)?.takeIf { it.week == w }?.at ?: return
        val c = WaysChoice(g.species, g.id, g.size, w, at, inputs.copyOf(), out.copyOf(WAYS_OUT), logp)
        record += c
        probes?.computeIfAbsent(g.id) { ArrayList() }?.let { waiting -> synchronized(waiting) { waiting += c } }
    }
}

/**
 * Learning the bands' ways by proximal policy optimisation, from the draws [WaysSampler] recorded
 * and the returns [HexPpo.play] gave them (the same as their moves get): each draw is judged by
 * the people's critic, the one that judges their moves ([PpoTrainer]), against what the band could
 * expect where it stood that week before it moved, since the move and the ways are one week's plan;
 * then a few passes of clipped policy gradient move each way's middle toward the draws that did
 * better, and its spread narrows or widens as they show. (The moves and the draws keep their own
 * records and ratios: a draw is held for a season, a move made weekly.)
 */
object WaysPpo {
    /** Ways draws recorded a year in each world (bands are many; a share is enough). */
    const val PER_YEAR = 6000
    const val CLIP = 0.2f
    /** Pull toward keeping the spreads wide (per way, on its log). */
    const val ENTROPY = 0.002f
    /** The ways, as kept in a training directory. */
    class State(val dir: File, hidden: Int, rate: Float) {
        val file = File(dir, WaysKind.BANDS.file)
        val ways = LearnedWays.loadOrNew(file, hidden)
        val adam = Adam(ways.net.w.size, rate)
        val adamStd = Adam(WAYS_OUT, rate)

        fun save() = ways.save(file)
    }

    /** Weeks a band keeps one draw of its ways, and over which the draw is judged (as people's moves are, [HexPpo.PEOPLE_HORIZON]). */
    const val SEASON = HexPpo.PEOPLE_HORIZON

    /** The share of bands' seasonal draws of their ways to record in a world with [bands] bands. */
    fun rate(bands: Int, stepsPerYear: Int) = if (bands == 0) 0f else min(1f, PER_YEAR.toFloat() * SEASON / (bands * stepsPerYear))

    /** One round of learning from [cs] (with their returns and advantages, judged by the people's critic, which explains [explained] of their returns' spread); a line saying how it went. */
    @IgnorableReturnValue fun learn(st: State, cs: List<WaysChoice>, round: Int, explained: Double): String {
        if (cs.isEmpty()) return "ways: no draws"
        // ---- clipped policy gradient on the middles and the spreads
        val ways = st.ways
        var clippedShare = 0.0; var seen = 0
        repeat(4) { epoch ->
            for (batch in cs.shuffled(Random(round * 31L + epoch + 7)).chunked(512)) {
                val parts = inParts(batch) { part -> gradient(ways, part, 1f / batch.size) }
                val gradW = FloatArray(ways.net.w.size); val gradStd = FloatArray(WAYS_OUT); var clipped = 0
                for ((a, s, c) in parts) { gradW.add(a); gradStd.add(s); clipped += c }
                for (i in gradStd.indices) gradStd[i] -= ENTROPY
                st.adam.step(ways.net.w, gradW); st.adamStd.step(ways.logStd, gradStd)
                for (i in ways.logStd.indices) ways.logStd[i] = ways.logStd[i].coerceIn(ln(LearnedWays.MIN_STD), ln(LearnedWays.MAX_STD))
                if (epoch == 3) { clippedShare += clipped; seen += batch.size }
            }
        }
        val stds = ways.logStd.map { exp(it) }
        // (An output nothing is learnt about drifts to the widest spread: those at the widest, and how many at the narrowest.)
        val wide = stds.indices.filter { stds[it] >= 0.95f * LearnedWays.MAX_STD }
        val narrow = stds.count { it <= 1.05f * LearnedWays.MIN_STD }
        val wideNames = wide.take(5).joinToString(", ") { Schema.WAYS_OUT[it] } + if (wide.size > 5) ", ..." else ""
        return "ways %d draws, people's critic explains %.2f, clipped %.2f, spread %.3f (%.3f..%.3f), %d at the widest (%s), %d at the narrowest".format(cs.size, explained, clippedShare / seen.coerceAtLeast(1), stds.average(), stds.min(), stds.max(), wide.size, wideNames, narrow)
    }

    /** The clipped surrogate's gradient over [part], to be minimised: for the network's weights, the log-spreads, and how many draws were clipped. */
    private fun gradient(ways: LearnedWays, part: List<WaysChoice>, w: Float): Triple<FloatArray, FloatArray, Int> {
        val gradW = FloatArray(ways.net.w.size); val gradStd = FloatArray(WAYS_OUT); val b = ways.net.buffers()
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
                gradStd[i] += -coef * (z * z - 1f)
            }
            ways.net.backwardAll(b, dOut, gradW)
        }
        return Triple(gradW, gradStd, clippedN)
    }

    /**
     * People's score over [years] in [cfg] ([HexPpo.score]: people-weeks per person at the start,
     * less their luck in births and deaths), with the bands keeping to the scaffold's ways and to the
     * middle of the learned ones, everything else hand-written: learned against scaffold ([Versus.hand]).
     */
    fun check(cfg: WorldConfig, years: Int, kinds: List<Species>, ways: LearnedWays): Versus<Double> {
        val both = listOf(false, true).parallelStream().map { learned ->
            (if (learned) HexPpo.score(cfg, years, kinds) { run -> run.groups.ways = WaysSampler(ways, null, { 0f }, { 0 }, cfg.seed) } else HexPpo.handScore(cfg, years, kinds))[Catalogue.HUMAN] ?: 0.0
        }.toList()
        return Versus(both[1], both[0])
    }
}
