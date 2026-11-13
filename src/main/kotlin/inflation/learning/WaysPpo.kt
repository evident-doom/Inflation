package inflation.learning

import inflation.gaussianF
import inflation.learning.HexPpo.CLIP
import inflation.ecology.BandWays
import inflation.ecology.Binding
import inflation.ecology.Catalogue
import inflation.ecology.HexGroup
import inflation.ecology.Species
import inflation.ecology.WAYS_IN
import inflation.ecology.WAYS_OUT
import inflation.Stream
import inflation.stream
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
 * of its own ([logStd], learned too); otherwise the band keeps to the middle. It reads too its camp's own
 * terms this week ([CampTerms], [HexGroup.plan]). Saved as `ways.mlp` (readable wherever the evolved ways were,
 * e.g. `ways`) with its spreads beside it (`.std`).
 */
class LearnedWays(val net: Mlp, val logStd: FloatArray) {
    fun copy() = LearnedWays(net.copy(), logStd.copyOf())

    fun save(file: File) {
        Schema.save(net, file, Schema.WAYS_CAMP_IN, Schema.WAYS_OUT, Role.PEOPLE.net)
        File(file.path + ".std").writeText(logStd.joinToString(" ") { exp(it).toString() } + "\n")
    }

    companion object {
        /** How widely each way is drawn about its middle at first (in the network's output, where 1 doubles or halves the way at most). */
        const val START_STD = 0.3f
        const val MIN_STD = 0.03f
        const val MAX_STD = 1f

        /** A network that keeps to the scaffold's ways (its last layer at zero), reading its camp's terms too (with no weight on them yet). */
        fun fresh(hidden: Int, rnd: Random): LearnedWays {
            val m = Schema.fit(Mlp.random(intArrayOf(WAYS_IN, hidden, WAYS_OUT), rnd).zeroOutputLayer(), Schema.WAYS_IN, Schema.WAYS_OUT, Schema.WAYS_CAMP_IN, Schema.WAYS_OUT)
            return LearnedWays(m, FloatArray(WAYS_OUT) { ln(START_STD) })
        }

        /**
         * The ways in [file] (any earlier ways network, the evolved ones too, fitted by name), or fresh ones: one saved without the
         * camp's terms gives them no weight, so it chooses as it did with none; one saved reading the people's mover's units drops them.
         */
        fun loadOrNew(file: File, hidden: Int): LearnedWays {
            if (!file.exists()) return fresh(hidden, Random(11))
            val net = Schema.load(file, Schema.WAYS_CAMP_IN, Schema.WAYS_OUT)
            val std = File(file.path + ".std").takeIf { it.exists() }?.readText()?.trim()?.split(" ")?.map { ln(it.toFloat()) }
            return LearnedWays(net, FloatArray(WAYS_OUT) { std?.getOrNull(it) ?: ln(START_STD) })
        }
    }
}

/**
 * One band's ways for a season, as drawn: what it knew of itself ([x], the network's whole input, its camp's terms included),
 * the ways it took ([a]) and their log-probability; and where it stood that week ([at]), from which the people's critic judges it
 * (see [WaysPpo]).
 */
class WaysChoice(kind: Species, groupId: Int, size: Int, week: Int, at: Probe, val x: FloatArray, val a: FloatArray, logp: Float) :
    Choice(kind, groupId, size, week, arrayOf(at.features), 0, logp) {
    init { band = at.band; temper = at.temper; group = at.group }
    /** The part of [logp] each output gave (their sum); null: not kept, every output counts as a whole. */
    var terms: FloatArray? = null
    /** What bound in the season this draw covers, filled in by the band's weeks as they pass (see [Binding]); null: not counted, every output counts. */
    var binding: FloatArray? = null
}

/**
 * Sets bands' ways with [ways]: drawn about the network's middle while learning (recording a share,
 * [rate], of the draws in [record]), the middle itself otherwise ([record] null). Each band's draw
 * comes from its own stream for the week, so a seed gives the same run.
 */
class WaysSampler(private val ways: LearnedWays, private val record: ConcurrentLinkedQueue<Choice>?, private val rate: () -> Float, private val week: () -> Int, private val seed: Long,
                  /** Where each band stood this week ([CampPolicy]): a draw is recorded only with it. */
                  private val standing: ConcurrentHashMap<Int, Standing>? = null,
                  /** Recorded choices waiting to see their group again ([Choice.probe]), by group. */
                  private val probes: ConcurrentHashMap<Int, MutableList<Choice>>? = null,
                  /** The day the step under way began on, counted from the world's start, and the days it covers ([StepLog]): a season is [WaysPpo.SEASON_DAYS] long whatever the steps' lengths. */
                  private val day: () -> Int = { week() * StepLog.WEEK }, private val stepDays: () -> Int = { StepLog.WEEK }) : BandWays {
    private val buffers = ThreadLocal.withInitial { ways.net.buffers() }
    override fun choose(g: HexGroup, inputs: FloatArray, out: FloatArray) {
        val b = buffers.get()
        waysInput(inputs, g.plan, b.a[0])
        val mean = ways.net.forwardAll(b)
        if (record == null) { mean.copyInto(out); return }
        // A band keeps its draw about the middle for a season ([WaysPpo.SEASON] weeks, its own seasons
        // staggered by its id), and the draw is judged once, by that season: a week's ways alone make no
        // difference a return can show (see tools/HexChoiceEffect).
        val w = week()
        val t = day() + g.id % WaysPpo.SEASON * StepLog.WEEK
        val season = t / WaysPpo.SEASON_DAYS
        // (The step that holds the season's first day starts it.)
        val starts = t % WaysPpo.SEASON_DAYS < stepDays()
        val rnd = stream(seed, Stream.WAYS_SEASON, g.id.toLong(), season.toLong())
        // (What binds is counted from the season's first week, for the recorded seasons only.)
        if (starts) g.band?.binding = null
        // Only the bands whose season is recorded to learn from draw their ways by chance; the rest keep to the middle.
        if (rnd.nextFloat() >= rate()) { mean.copyInto(out); return }
        var logp = 0f
        val terms = FloatArray(WAYS_OUT)
        for (i in 0 until WAYS_OUT) {
            val z = gaussianF(rnd)
            out[i] = mean[i] + exp(ways.logStd[i]) * z
            terms[i] = -0.5f * z * z - ways.logStd[i]
            logp += terms[i]
        }
        if (!starts) return
        // (A draw is learnt from only where the band was seen choosing its place this week.)
        val st = standing?.get(g.id)?.takeIf { it.week == w } ?: return
        val c = WaysChoice(g.species, g.id, g.size, w, st.at, b.a[0].copyOf(), out.copyOf(WAYS_OUT), logp)
        c.terms = terms
        c.binding = FloatArray(Binding.SIZE).also { g.band?.binding = it }
        record += c
        probes?.computeIfAbsent(g.id) { ArrayList() }?.let { waiting -> synchronized(waiting) { waiting += c } }
    }
}

/**
 * Learning the bands' ways by proximal policy optimisation, from the draws [WaysSampler] recorded
 * and the returns [HexPpo.play] gave them: each draw is judged by the people's critic ([PpoTrainer]),
 * which learns from bands' weeks recorded for it alone ([CampPolicy]), against what the band could
 * expect where it stood that week; then a few passes of clipped policy gradient move each way's
 * middle toward the draws that did better, and its spread narrows or widens as they show. (A draw
 * is held for a season.)
 */
object WaysPpo {
    /** Ways draws recorded a year in each world (bands are many; a share is enough). */
    const val PER_YEAR = 6000
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

    /** Weeks a band keeps one draw of its ways: a quarter of the 360-day year (judged over [HexPpo.PEOPLE_HORIZON]). */
    const val SEASON = 15
    /** [SEASON] in days ([StepLog.WEEK]-day weeks). */
    val SEASON_DAYS = SEASON * StepLog.WEEK

    /** The share of bands' seasonal draws of their ways to record in a world with [bands] bands. */
    fun rate(bands: Int, stepsPerYear: Int) = if (bands == 0) 0f else min(1f, PER_YEAR.toFloat() * SEASON / (bands * stepsPerYear))

    /**
     * One round of learning from [cs] (with their returns and advantages, judged by the people's critic, which explains [explained]
     * of their returns' spread); a line saying how it went.
     */
    @IgnorableReturnValue fun learn(st: State, cs: List<WaysChoice>, round: Int, explained: Double): String {
        if (cs.isEmpty()) return "ways: no draws"
        // ---- clipped policy gradient on the middles and the spreads
        val ways = st.ways
        var clippedShare = 0.0; var seen = 0
        repeat(4) { epoch ->
            for (batch in cs.shuffled(stream(2L, Stream.BATCHES, round.toLong(), epoch.toLong())).chunked(512)) {
                val parts = inParts(batch) { part -> gradient(ways, part, 1f / batch.size) }
                val gradW = FloatArray(ways.net.w.size); val gradStd = FloatArray(WAYS_OUT); var clipped = 0
                val live = IntArray(WAYS_OUT)
                for (g in parts) { gradW.add(g.ways); gradStd.add(g.std); clipped += g.clipped; for (i in live.indices) live[i] += g.live[i] }
                // (Only an output that acted in some draw of the batch is pulled toward a wide spread: one nothing is learnt about would drift there.)
                for (i in gradStd.indices) if (live[i] > 0) gradStd[i] -= ENTROPY
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
        // (How often the week plan and the camp move acted in a recorded season: an output that never acted learnt nothing.)
        val counted = cs.mapNotNull { it.binding }
        val timeActed = counted.count { b -> (0 until Binding.MOVES).any { b[it] > 0f } }
        val moveActed = counted.count { it[Binding.MOVES] > 0f }
        val acted = "time weights acted in %.0f%% of draws, carry loads in %.0f%%".format(100.0 * timeActed / counted.size.coerceAtLeast(1), 100.0 * moveActed / counted.size.coerceAtLeast(1))
        return "ways %d draws, people's critic explains %.2f, clipped %.2f, spread %.3f (%.3f..%.3f), %d at the widest (%s), %d at the narrowest; %s".format(cs.size, explained, clippedShare / seen.coerceAtLeast(1), stds.average(), stds.min(), stds.max(), wide.size, wideNames, narrow, acted)
    }

    /** A part's gradient: for the ways' weights and their log-spreads, and how many draws were clipped. */
    private class Grad(val ways: FloatArray, val std: FloatArray, val clipped: Int,
                       /** For each output, how many of the draws that were not clipped acted on it (see [Binding.acted]). */
                       val live: IntArray)

    /** The clipped surrogate's gradient over [part], to be minimised. */
    private fun gradient(ways: LearnedWays, part: List<WaysChoice>, w: Float): Grad {
        val gradW = FloatArray(ways.net.w.size); val gradStd = FloatArray(WAYS_OUT); val b = ways.net.buffers()
        val dOut = FloatArray(WAYS_OUT); var clippedN = 0
        val live = IntArray(WAYS_OUT); val acts = BooleanArray(WAYS_OUT)
        for (c in part) {
            c.x.copyInto(b.a[0])
            val mu = ways.net.forwardAll(b)
            // (The outputs that acted in its season are the draw's likelihood; the rest changed nothing the return could show, and are left out of it, as recorded and as now.)
            var logp = 0f; var was = 0f
            val terms = c.terms
            for (i in 0 until WAYS_OUT) {
                acts[i] = terms == null || Binding.acted(c.binding, i)
                if (!acts[i]) continue
                val z = (c.a[i] - mu[i]) / exp(ways.logStd[i]); logp += -0.5f * z * z - ways.logStd[i]
                if (terms != null) was += terms[i]
            }
            val ratio = exp(logp - (if (terms != null) was else c.logp))
            val a = c.advantage
            val clipped = (a > 0f && ratio > 1f + CLIP) || (a < 0f && ratio < 1f - CLIP)
            if (clipped) { clippedN++; continue }
            val coef = a * ratio * w
            for (i in 0 until WAYS_OUT) {
                if (!acts[i]) { dOut[i] = 0f; continue }
                live[i]++
                val s = exp(ways.logStd[i]); val z = (c.a[i] - mu[i]) / s
                // d log p / d middle = z / s; d log p / d log-spread = z^2 - 1 (minimising, so negated).
                dOut[i] = -coef * z / s
                gradStd[i] += -coef * (z * z - 1f)
            }
            ways.net.backwardAll(b, dOut, gradW)
        }
        return Grad(gradW, gradStd, clippedN, live)
    }

    /**
     * People's score over [years] in [cfg] ([HexPpo.score]: people-weeks per person at the start,
     * less their luck in births and deaths), with the bands keeping to the scaffold's ways and to the
     * middle of the learned ones, everything else hand-written (bands camp on their low ground, [CampPolicy],
     * so that the ways see their camp's terms): learned against scaffold ([Versus.hand]).
     */
    fun check(cfg: WorldConfig, years: Int, kinds: List<Species>, ways: LearnedWays): Versus<Double> {
        val both = listOf(false, true).parallelStream().map { learned ->
            HexPpo.score(cfg, years, kinds) { run ->
                val p = CampPolicy()
                for (s in kinds) if (s.human) run.groups.policies[s] = p
                if (learned) run.groups.ways = WaysSampler(ways, null, { 0f }, { 0 }, cfg.seed)
            }[Catalogue.HUMAN] ?: 0.0
        }.toList()
        return Versus(both[1], both[0])
    }
}
