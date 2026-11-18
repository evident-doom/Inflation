package inflation.learning

import inflation.Stream
import inflation.stream
import inflation.ecology.Catalogue
import inflation.ecology.HexFeatures
import inflation.ecology.HexGroup
import inflation.ecology.Species
import inflation.ecology.Temperament
import inflation.ecology.WAYS_IN
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What a group's return can be expected to be where it stands, for one role: from what the role's
 * network reads of the place it stands on (and, for people, the band's view of itself), and the group's
 * own state ([GROUP_IN]: its size, its young, how fed, fat and protein-fed it is, the time of year).
 *
 * It learns toward each choice's judged return: what became of the group's members over the first
 * [boot] weeks, and from there the critic's own estimate for the group as it then stood (when it was
 * seen choosing that week; else the whole horizon as seen), so that a choice's judgement carries the
 * chance of a few weeks rather than of half a year. Each kind's returns are fitted on their own scale.
 * It learns from the last [ROUNDS] rounds' choices.
 */
class Critic(val net: Mlp, val inputs: Inputs, private val scale: HashMap<String, Scale> = HashMap(), rate: Float = 1e-3f) {
    /**
     * What a critic reads of where a group stands, besides the group's own state ([GROUP_IN]): its [names], and how a group of a
     * kind, at a place with features, with its band's view of itself and its temperament, is written into them.
     */
    class Inputs(val names: List<String>, private val write: (Species, FloatArray, FloatArray?, FloatArray?, FloatArray) -> Unit) {
        val size get() = names.size
        fun fill(kind: Species, features: FloatArray, band: FloatArray?, temper: FloatArray?, into: FloatArray) = write(kind, features, band, temper, into)

        companion object {
            /** What mover [model] reads: the hunters' critic, which reads the hunters' learned kinds' identities as they learn. */
            fun of(model: SharedMover) = Inputs(if (model.band > 0) Schema.PEOPLE_IN else Schema.MOVER_IN) { s, f, b, t, into -> model.fill(s, f, b, into, t) }

            /**
             * People's ([Schema.PEOPLE_CRITIC_IN]): the place, the band's temperament and its view of itself, without people.mlp,
             * which chooses nothing since people's net major 11 ([CampPolicy]).
             */
            val PEOPLE = Inputs(Schema.PEOPLE_CRITIC_IN) { _, f, b, t, into ->
                val n = HexFeatures.size
                f.copyInto(into, 0, 0, min(f.size, n))
                for (k in 0 until Temperament.AXES) into[n + k] = t?.getOrElse(k) { 0f } ?: 0f
                val at = n + Temperament.AXES
                into.fill(0f, at, at + WAYS_IN)
                b?.copyInto(into, at, 0, min(b.size, WAYS_IN))
            }

            /**
             * What people's critic once read that was the same for every band, by name ([Schema.KIND_PART]): the human kind's traits
             * and its identity in people's mover saved in [moverFile] (zero if there is none), for folding into a critic saved
             * reading them ([Schema.fit]).
             */
            fun peopleConstants(moverFile: File): List<Pair<String, Float>> {
                val mover = if (moverFile.exists()) SharedMover.load(moverFile) else SharedMover(Mlp(intArrayOf(1, 1, 1)), FloatArray(Catalogue.all.size * SharedMover.IDENTITY))
                val part = FloatArray(SharedMover.KIND + SharedMover.IDENTITY).also { mover.kindInputs(Catalogue.HUMAN, it, 0) }
                return Schema.KIND_PART.zip(part.toList())
            }
        }
    }

    /** A kind's returns' [mean] and [spread]. */
    data class Scale(val mean: Float, val spread: Float)
    private val adam = Adam(net.w.size, rate)
    private val buffers = ThreadLocal.withInitial { net.buffers() }
    private class Sample(val kind: Species, val x: FloatArray, val probe: FloatArray?, val part: Float, val boot: Float, val whole: Float)
    private val replay = ArrayDeque<List<Sample>>()

    private fun scaleOf(s: Species) = scale[s.name] ?: Scale(1f, 0.1f)
    private fun input(kind: Species, features: FloatArray, band: FloatArray?, temper: FloatArray?, group: FloatArray?, into: FloatArray) {
        inputs.fill(kind, features, band, temper, into)
        val at = inputs.size
        for (k in 0 until GROUP) into[at + k] = group?.getOrElse(k) { 0f } ?: 0f
    }
    private fun x(c: Choice) = FloatArray(net.sizes[0]).also { input(c.kind, c.inputs[here(c)], c.band, c.temper, c.group, it) }
    private fun xProbe(c: Choice) = c.probe?.let { p -> FloatArray(net.sizes[0]).also { input(c.kind, p.features, p.band, p.temper, p.group, it) } }
    private fun raw(x: FloatArray): Float { val b = buffers.get(); x.copyInto(b.a[0]); return net.forward(b) }
    private fun value(kind: Species, x: FloatArray): Float { val (m, s) = scaleOf(kind); return m + s * raw(x) }

    /** The return [c]'s group could expect where it stood. */
    fun value(c: Choice): Float = value(c.kind, x(c))

    /** [c]'s judged return: its first weeks as seen, then the critic's estimate where its group then stood (or the whole horizon as seen). */
    fun target(c: Choice): Float = xProbe(c)?.let { c.part + c.boot * value(c.kind, it) } ?: c.ret

    private fun target(s: Sample) = if (s.probe != null) s.part + s.boot * value(s.kind, s.probe) else s.whole

    /** Learns from this round's choices [cs] and the last rounds' ([ROUNDS] in all): [EPOCHS] passes of least squares toward their judged returns. */
    fun learn(cs: List<Choice>, round: Int) {
        replay.addLast(cs.map { c -> Sample(c.kind, x(c), xProbe(c), c.part, c.boot, c.ret) })
        while (replay.size > ROUNDS) replay.removeFirst()
        val all = replay.flatten()
        // Each kind's returns on their own scale (its mean and spread over the samples kept).
        for ((k, ss) in all.groupBy { it.kind }) {
            val ts = ss.map { target(it) }
            val m = ts.average().toFloat()
            val sd = sqrt(ts.map { ((it - m) * (it - m)).toDouble() }.average()).toFloat().coerceAtLeast(1e-3f)
            scale[k.name] = Scale(m, sd)
        }
        repeat(EPOCHS) { epoch ->
            for (batch in all.shuffled(stream(0L, Stream.BATCHES, round.toLong(), epoch.toLong())).chunked(1024)) {
                val parts = inParts(batch) { part ->
                    val g = FloatArray(net.w.size); val b = net.buffers()
                    for (s in part) {
                        val (m, sd) = scaleOf(s.kind)
                        val t = (target(s) - m) / sd
                        s.x.copyInto(b.a[0])
                        val v = net.forward(b)
                        net.backward(b, 2f * (v - t) / batch.size, g, null)
                    }
                    g
                }
                val g = FloatArray(net.w.size); for (p in parts) g.add(p)
                // (A step that is not all numbers would make the critic not a number for good: it is left out.)
                if (g.allFinite()) adam.step(net.w, g)
            }
        }
    }

    /** The share of the spread in this round's judged returns, within each kind, that the critic accounts for: by kind. */
    fun explained(cs: List<Choice>): Map<Species, Double> = cs.groupBy { it.kind }.mapValues { (_, ks) ->
        val ts = ks.map { target(it).toDouble() }
        val vs = ks.map { value(it).toDouble() }
        val m = ts.average()
        val total = ts.sumOf { (it - m) * (it - m) }
        if (total <= 0.0) 0.0 else 1.0 - ts.indices.sumOf { (ts[it] - vs[it]) * (ts[it] - vs[it]) } / total
    }

    fun save(f: File) {
        Schema.save(net, f, inputs.names + GROUP_IN, listOf("value"))
        File(f.path + ".scale").writeText(scale.entries.sortedBy { it.key }.joinToString("") { "${it.key}\t${it.value.mean}\t${it.value.spread}\n" })
    }

    companion object {
        /**
         * Weeks over which a choice is judged as seen before the critic's estimate takes over: animals, people. People's
         * is a third of [HexPpo.PEOPLE_HORIZON] (60 weeks), so a year's judgement is not mostly the critic's guess.
         */
        const val BOOT = 8
        const val PEOPLE_BOOT = 20
        const val ROUNDS = 3
        const val EPOCHS = 8
        const val HIDDEN = 64
        val GROUP_IN = listOf("group:size", "group:young", "group:fed", "group:fat", "group:protein", "group:season-sin", "group:season-cos")
        val GROUP = GROUP_IN.size

        fun boot(s: Species) = if (s.human) PEOPLE_BOOT else BOOT
        /** [boot] in days: the steps that cover at least as many days are judged as seen, whatever their length ([StepLog]). */
        fun bootDays(s: Species) = boot(s) * StepLog.WEEK

        /** Where [c]'s group stood: the candidate that is staying put (else the first). */
        fun here(c: Choice) = here(c.inputs)
        fun here(features: Array<FloatArray>) = features.indices.firstOrNull { features[it].getOrElse(HexFeatures.STAYS) { 0f } > 0.5f } ?: 0

        /**
         * [g]'s own state for the critic, at [season] (0..1 through the year). Its protein slot is always 1: every group
         * eats meat, rich in it (the slot keeps the critic's layout until the next retraining).
         */
        fun groupState(g: HexGroup, season: Float) = floatArrayOf(
            ln(max(1, g.size).toFloat()) / 5f, g.young.toFloat() / max(1, g.size), g.fed, g.fat, 1f,
            sin(2f * PI.toFloat() * season), cos(2f * PI.toFloat() * season))

        /**
         * The critic in [f], or a new one ([HIDDEN] by [HIDDEN]), reading [inputs] and the group's own state. Inputs it was saved
         * reading that were the same for every group of its kinds, [constants], go into its biases ([Schema.fit]).
         */
        fun loadOrNew(f: File, inputs: Inputs, constants: List<Pair<String, Float>> = emptyList()): Critic {
            if (f.exists()) {
                val net = Schema.load(f, inputs.names + GROUP_IN, listOf("value"), constants = constants)
                val scale = HashMap<String, Scale>()
                File(f.path + ".scale").takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }?.forEach { l -> val (k, m, s) = l.split("\t"); scale[k] = Scale(m.toFloat(), s.toFloat()) }
                return Critic(net, inputs, scale)
            }
            return Critic(Mlp.random(intArrayOf(inputs.size + GROUP, HIDDEN, HIDDEN, 1), kotlin.random.Random(91)), inputs)
        }
    }
}

/** Where a group stood, and how it was, some weeks after a recorded choice: for the critic's estimate from there. */
class Probe(val features: FloatArray, val band: FloatArray?, val temper: FloatArray?, val group: FloatArray)
