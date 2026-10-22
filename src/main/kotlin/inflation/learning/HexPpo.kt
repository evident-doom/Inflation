package inflation.learning

import inflation.run.Nominal
import inflation.run.freshSim
import inflation.ecology.HexSim
import inflation.ecology.Catalogue
import inflation.Config
import inflation.ecology.HexFeatures
import inflation.ecology.Temperament
import inflation.ecology.HexGroup
import inflation.ecology.HexPolicy
import inflation.ecology.Species
import inflation.world.WorldConfig
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random
import inflation.ecology.Band
import java.util.concurrent.ConcurrentHashMap

/**
 * Where to go, for every animal kind with one shared network, and for people with their own: each
 * candidate place's features ([HexFeatures]) and what the kind is ([kindInputs]: its build, its
 * food, how it lives, and a small learned identity), scored by the network; the group goes where
 * the softmax of the scores sends it (while learning) or to the best (otherwise).
 */
class SharedMover(val net: Mlp, val identity: FloatArray) {
    val inputs get() = net.sizes[0]
    /**
     * How much of the hand-written behaviour's score for each place it starts from (0: none): with
     * this, a network chooses as the hand-written behaviour does at first and learns what to add to
     * it. Saved beside the network (`.residual`).
     */
    var residual = 0f

    /** The kind's part of the input: its traits on log or 0..1 scales, then its learned identity. */
    fun kindInputs(s: Species, into: FloatArray, at: Int) {
        var i = at
        into[i++] = ln(s.massKg) / 5f - 1f
        into[i++] = ln(s.speedKmh) / 4f - 1f
        into[i++] = ln(s.dailyTravelKm) / 3f - 1f
        into[i++] = ln(s.groupSize.toFloat()) / 3f
        val d = s.diet
        into[i++] = d.grass; into[i++] = d.browse; into[i++] = d.fruit + d.roots
        into[i++] = d.meat; into[i++] = d.smallPrey; into[i++] = d.carrion
        into[i++] = if (s in Catalogue.migratory) 1f else 0f
        into[i++] = if (s.carnivore) 1f else 0f
        into[i++] = s.stalking; into[i++] = s.foresight
        for (e in 0 until IDENTITY) into[i++] = identity[s.index * IDENTITY + e]
    }

    /** What the group's temperament [temper] pulls it toward a candidate place with [features] (see [Temperament.pull]): added to the network's score, as to the hand-written one. */
    fun pull(temper: FloatArray?, features: FloatArray): Float =
        (if (temper == null) 0f else Temperament.pull(temper, features[HexFeatures.HUNTERS], features[HexFeatures.REMEMBERED], features[HexFeatures.STAYS], features[HexFeatures.CROWDING])) +
            // A band's yearly round draws it on outside the network, as tradition does, so that learning does not unlearn it.
            inflation.Tuning.config.roundNetPull * features.getOrElse(HexFeatures.ON_ROUND) { 0f }

    /** How many numbers of the band's own view ([Band.state]) it reads after the kind's part: [WAYS_IN] for people, 0 for animals. */
    val band get() = inputs - INPUTS

    /** Writes a candidate's whole input for kind [s]: its place features, the kind's part, the group's temperament [temper], and (people's) the band's view of itself, [state]. */
    fun fill(s: Species, features: FloatArray, state: FloatArray?, into: FloatArray, temper: FloatArray? = null) {
        features.copyInto(into, 0, 0, min(features.size, HexFeatures.SIZE))
        kindInputs(s, into, HexFeatures.SIZE)
        for (k in 0 until Temperament.AXES) into[HexFeatures.SIZE + KIND + IDENTITY + k] = temper?.getOrElse(k) { 0f } ?: 0f
        if (band > 0) { into.fill(0f, INPUTS, inputs); state?.copyInto(into, INPUTS, 0, min(state.size, band)) }
    }

    /** The size of its last hidden layer: what a band's ways read of the place it chose ([plan]). */
    val trunk get() = net.sizes[net.sizes.size - 2]

    /**
     * How it sees candidate place [features] for a group of kind [s] with temperament [temper] and view of itself [state]: its last
     * hidden layer on that input, written whole into [input] (as [fill] does), run in [b].
     */
    fun plan(s: Species, features: FloatArray, state: FloatArray?, temper: FloatArray?, input: FloatArray, b: Mlp.Buffers): FloatArray {
        fill(s, features, state, input, temper)
        input.copyInto(b.a[0]); net.forward(b)
        return b.a[net.sizes.size - 2].copyOf()
    }

    /** Per-thread room for [netScores]. */
    inner class Scratch {
        val b = net.buffers()
        val shared = FloatArray(inputs)
        var rows = FloatArray(0)
        var out = FloatArray(0)
    }

    /**
     * The network's score of each candidate place [features] for a group of kind [s] with temperament [temper] and (people's)
     * view of itself [state], in the first `features.size` of [Scratch.out]: what [fill] and [Mlp.forward] give for each, to
     * the bit, with the part all candidates share (the kind, the temperament, the band) read once.
     */
    fun netScores(s: Species, features: Array<FloatArray>, state: FloatArray?, temper: FloatArray?, sc: Scratch): FloatArray {
        val n = features.size; val v = HexFeatures.SIZE
        if (sc.out.size < n) sc.out = FloatArray(2 * n)
        if (n == 0) return sc.out
        if (sc.rows.size < n * v) sc.rows = FloatArray(2 * n * v)
        fill(s, features[0], state, sc.shared, temper)
        for (k in 0 until n) {
            val f = features[k]; val m = min(f.size, v)
            f.copyInto(sc.rows, k * v, 0, m)
            if (m < v) sc.rows.fill(0f, k * v + m, (k + 1) * v)
        }
        net.forwardShared(sc.rows, n, v, sc.shared, sc.out, sc.b)
        return sc.out
    }

    fun copy() = SharedMover(net.copy(), identity.copyOf())

    fun save(file: File) {
        Schema.save(net, file, if (band > 0) Schema.PEOPLE_IN else Schema.MOVER_IN, Schema.MOVER_OUT, (if (band > 0) Role.PEOPLE else Role.HUNTERS).net)
        File(file.path + ".residual").let { if (residual != 0f) it.writeText(residual.toString()) else it.delete() }
        File(file.path + ".identity").writeText(identity.joinToString(" ") + "\n")
    }

    companion object {
        const val KIND = 14
        const val IDENTITY = 6
        val INPUTS = HexFeatures.SIZE + KIND + IDENTITY + Temperament.AXES

        fun random(hidden: Int, rnd: Random, band: Int = 0) = SharedMover(Mlp.random(intArrayOf(INPUTS + band, hidden, hidden, 1), rnd), FloatArray(Catalogue.all.size * IDENTITY) { 0.1f * (rnd.nextFloat() - 0.5f) })

        /** Whether a mover saved in [file] is people's (it reads the band's view of itself too). */
        fun isPeople(file: File) = file.name.startsWith("people")

        fun load(file: File): SharedMover {
            // (Fitted by name to today's inputs: see [Schema]. A people's network saved before it read
            // the band's view is fitted to it with zero weights there, so it chooses as it did.)
            val net = if (!isPeople(file)) Schema.load(file, Schema.MOVER_IN, Schema.MOVER_OUT, legacyMover = true)
                else if (Schema.namesFile(file).exists()) Schema.load(file, Schema.PEOPLE_IN, Schema.MOVER_OUT)
                else Schema.fit(Schema.load(file, Schema.MOVER_IN, Schema.MOVER_OUT, legacyMover = true), Schema.MOVER_IN, Schema.MOVER_OUT, Schema.PEOPLE_IN, Schema.MOVER_OUT)
            val idFile = File(file.path + ".identity")
            // (Kinds added since it was saved start with no identity of their own: zeros.)
            val id = (if (idFile.exists()) idFile.readText().trim().split(" ").map { it.toFloat() }.toFloatArray() else FloatArray(0)).copyOf(Catalogue.all.size * IDENTITY)
            return SharedMover(net, id).also { m -> File(file.path + ".residual").takeIf { it.exists() }?.let { m.residual = it.readText().trim().toFloat() } }
        }
    }
}

/** One recorded choice: the candidates' place features (the kind's part is added when learning), the one taken and its log-probability, who chose, and when. */
open class Choice(val kind: Species, val groupId: Int, val size: Int, val week: Int, val inputs: Array<FloatArray>, val taken: Int, val logp: Float) {
    /** The hand-written scores of the candidates (for networks that start from them). */
    var baseline = FloatArray(0)
    /** A band's view of itself when it chose ([Band.state]); null for animals. */
    var band: FloatArray? = null
    /** The group's temperament when it chose ([HexGroup.temper]). */
    var temper: FloatArray? = null
    /** The group's own state when it chose ([Critic.groupState]). */
    var group: FloatArray? = null
    /** What followed: what became of the group's members over the next weeks against their number now, discounted. */
    var ret = 0f
    /** The same over the first [Critic.boot] weeks alone, and the weight of the critic's estimate from there ([Critic.target]). */
    var part = 0f
    var boot = 0f
    /** Where the group stood, and how it was, [Critic.boot] weeks on (null if it was not seen choosing then). */
    var probe: Probe? = null
    var advantage = 0f
}

/**
 * Where a band stood in week [week] before it moved, as the critic reads it ([at]): the state its week's plan, the move and the
 * ways set after it, is judged from (see [WaysChoice]).
 */
class Standing(val week: Int, val at: Probe) {
    /** The mover's whole input for the place it then chose ([SharedMover.plan]), for its ways' learning to train the mover through. */
    @Volatile var plan: FloatArray? = null
}

/** Chooses with a [SharedMover], recording a share of its choices in [record] (null: the best, not sampled). */
class MoverPolicy(private val mover: SharedMover, private val record: ConcurrentLinkedQueue<Choice>?, private val rate: (Species) -> Float, private val week: () -> Int,
                  /** Recorded choices waiting to see their group again some weeks on, by group (see [Choice.probe]); null: none wanted. */
                  private val probes: ConcurrentHashMap<Int, MutableList<Choice>>? = null, private val season: () -> Float = { 0f },
                  /** Where each band stood when it chose this week, before it moved (see [Standing]); null: not wanted. */
                  private val standing: ConcurrentHashMap<Int, Standing>? = null) : HexPolicy {
    private val scratch = ThreadLocal.withInitial { mover.Scratch() }
    override fun choose(g: HexGroup, features: Array<FloatArray>, baseline: FloatArray, rnd: kotlin.random.Random): Int {
        val n = features.size
        val state = g.band?.state; val temper = g.temper
        if (standing != null && g.band != null && n > 0)
            standing[g.id] = Standing(week(), Probe(features[Critic.here(features)].copyOf(HexFeatures.SIZE), state?.copyOf(), temper.copyOf(), Critic.groupState(g, season())))
        if (probes != null) probes[g.id]?.let { waiting -> synchronized(waiting) {
            val w = week()
            val due = waiting.filter { it.week + Critic.boot(it.kind) == w }
            if (due.isNotEmpty()) {
                val p = Probe(features[Critic.here(features)].copyOf(HexFeatures.SIZE), state?.copyOf(), temper.copyOf(), Critic.groupState(g, season()))
                // (Where it then stood is unknown to the critic if any of it is not a number: its whole horizon counts as seen.)
                val known = p.features.allFinite() && p.band.allFinite() && p.temper.allFinite() && p.group.allFinite()
                if (known) for (c in due) c.probe = p
                waiting.removeAll(due)
            }
        } }
        val net = mover.netScores(g.species, features, state, temper, scratch.get())
        val scores = FloatArray(n) { k -> net[k] + mover.residual * baseline[k] + mover.pull(temper, features[k]) }
        // A place whose score or features are not numbers is never taken, as with the hand-written score; and if no place
        // can be weighed the group stays where it is. One such number learnt from makes the whole network not a number.
        var weighed = 0
        for (k in 0 until n) if (scores[k].isFinite() && features[k].allFinite(HexFeatures.SIZE)) weighed++ else {
            NonFinite.note(g.species, features[k], baseline[k], net[k], mover.pull(temper, features[k])); scores[k] = Float.NEGATIVE_INFINITY }
        if (weighed == 0) return Critic.here(features)
        // Only the choices recorded to learn from are drawn by chance here; every other group takes its best (a band then draws
        // by its will to explore, see [HexGroup.drawn]), so the training worlds live as they would with the networks choosing.
        if (record == null || rnd.nextFloat() >= rate(g.species)) { var best = 0; for (k in 1 until n) if (scores[k] > scores[best]) best = k; return best }
        g.drawn = true
        val p = softmax(scores)
        var u = rnd.nextFloat(); var taken = -1
        for (k in 0 until n) if (p[k] > 0f) { taken = k; u -= p[k]; if (u <= 0f) break }
        val group = Critic.groupState(g, season())
        // (Nor is a choice learnt from whose group's own numbers are not all numbers.)
        if (!temper.allFinite() || !state.allFinite() || !group.allFinite()) { NonFinite.note(g.species, "group"); return taken }
        // (The places not weighed are left out of what is learnt.)
        val kept = (0 until n).filter { scores[it] > Float.NEGATIVE_INFINITY }
        val c = Choice(g.species, g.id, g.size, week(), Array(kept.size) { features[kept[it]].copyOf(HexFeatures.SIZE) }, kept.indexOf(taken), ln(max(p[taken], 1e-8f)))
            .also { it.baseline = FloatArray(kept.size) { baseline[kept[it]] }; it.temper = temper.copyOf(); if (mover.band > 0) it.band = state?.copyOf(); it.group = group }
        record += c
        probes?.computeIfAbsent(g.id) { ArrayList() }?.let { waiting -> synchronized(waiting) { waiting += c } }
        return taken
    }
    override fun planned(g: HexGroup, chosen: FloatArray): FloatArray? {
        if (g.band == null || mover.band == 0) return null
        val input = FloatArray(mover.inputs)
        val trunk = mover.plan(g.species, chosen, g.band?.state, g.temper, input, scratch.get().b)
        standing?.get(g.id)?.let { if (it.week == week()) it.plan = input }
        return trunk
    }
    override fun scores(g: HexGroup, features: Array<FloatArray>, baseline: FloatArray): FloatArray {
        val state = g.band?.state; val temper = g.temper
        val net = mover.netScores(g.species, features, state, temper, scratch.get())
        return FloatArray(features.size) { k -> net[k] + mover.residual * baseline[k] + mover.pull(temper, features[k]) }
    }
}

/** Whether the first [size] numbers (all, by default) are numbers, neither infinite nor NaN; true for none. */
internal fun FloatArray?.allFinite(size: Int = Int.MAX_VALUE): Boolean {
    if (this == null) return true
    for (i in 0 until min(size, this.size)) if (!this[i].isFinite()) return false
    return true
}

/**
 * The scores and inputs that were not numbers (NaN or infinite) when a network chose, by kind and by what was not: the
 * hand-written score of a place, a place feature (by name), the network's score, the temperament's pull, or the group's
 * own numbers. Such places are never taken and never learnt from (see [MoverPolicy]); the training's round log reports
 * them, so that what makes them can be found.
 */
object NonFinite {
    private val counts = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()

    fun note(s: Species, what: String) { counts.computeIfAbsent("${s.name}: $what") { java.util.concurrent.atomic.AtomicLong() }.incrementAndGet() }

    /** A place [features] whose score was not a number: the hand-written score [baseline], the network's [net], the temperament's [pull]. */
    fun note(s: Species, features: FloatArray, baseline: Float, net: Float, pull: Float) {
        for (i in 0 until min(features.size, HexFeatures.SIZE)) if (!features[i].isFinite()) note(s, "feature ${HexFeatures.NAMES[i]}")
        if (!baseline.isFinite()) note(s, "hand-written score")
        if (!net.isFinite()) note(s, "network")
        if (!pull.isFinite()) note(s, "pull")
    }

    /** What has been counted since the last call, most first ("" if nothing), and the counts cleared. */
    @IgnorableReturnValue fun take(): String {
        val now = counts.keys.toList().map { it to (counts.remove(it)?.get() ?: 0L) }.filter { it.second > 0 }
        return now.sortedByDescending { it.second }.joinToString(", ") { "${it.first} ${it.second}" }
    }
}

internal fun softmax(x: FloatArray): FloatArray {
    var m = Float.NEGATIVE_INFINITY; for (v in x) m = max(m, v)
    val e = FloatArray(x.size) { exp(x[it] - m) }
    val s = e.sum()
    for (i in e.indices) e[i] /= s
    return e
}

/** A score with the learned networks against the same with the hand-written behaviour (or the scaffold's ways), in one world. */
class Versus<T>(val learned: T, val hand: T)

/**
 * Learns where to go by proximal policy optimisation, from every recorded choice: one network for
 * the hunters (kinds told apart by their traits and a learned identity), one for people. Each
 * round, fresh small worlds with all the animals and people run for a while with the current
 * networks choosing by chance as they score; a share of the choices of every kind is recorded (the
 * same number a year of each kind, however common it is), and what became of the group over the
 * following weeks is its return. Each kind is judged against its own average, and counts the same
 * in the update as every other kind, so the common hunters do not drown out the rare ones. Every
 * few rounds each role's network plays against the hand-written behaviour on fixed fresh worlds
 * (the other roles hand-written), reported per kind with its spread over the worlds; and the ecosystem
 * check ([EcoCheck]) keeps each role's new network only when the whole holds up with it (in `kept/`).
 *
 * Usage: [PpoOptions.USAGE]; the learning settings are config keys (see [PpoOptions]).
 */
object HexPpo {
    /** The index of [s]'s role's network in a list of them ([Role]). */
    fun role(s: Species) = Role.of(s).ordinal

    const val HORIZON = 26
    const val DISCOUNT = 0.95f
    /**
     * Weeks over which a band's choice is judged: a season. What a move does to its band shows within
     * about 8 to 13 weeks, while the drift every other band's choices cause grows on through the year
     * (see tools/HexChoiceEffect).
     */
    const val PEOPLE_HORIZON = 13
    const val PEOPLE_DISCOUNT = 0.98f
    /** The fewest animals of a kind its tenfold growth is measured from: a kind may grow from a few groups to this tenfold before the world counts as off the rails. */
    const val GROWTH_FLOOR = 10_000
    const val CLIP = 0.2f
    /**
     * Parts each batch of learning is split into, to be worked on all cores; the same on every machine,
     * so that a run's sums come out the same.
     */
    const val PARTS = 128
    const val ENTROPY = 0.01f

    @JvmStatic
    fun main(args: Array<String>) {
        val o = PpoOptions.parse(args)
        o.out.mkdirs()
        PpoTrainer(o).run()
    }

    /** A world to check on: a fresh one for [seed] (never a training map unless [onMaps]). */
    fun checkWorld(seed: Long, size: Int, onMaps: Boolean, maps: Maps): WorldConfig =
        if (onMaps && !maps.isEmpty()) maps.world(seed, size, (seed - 91L).toInt()) else WorldConfig(seed = seed, widthKm = size, heightKm = size)

    /**
     * How [learned] compares with [hand], world by world (paired: the same worlds): the mean relative
     * change, its standard error over the worlds, and in how many worlds the learned did better.
     */
    fun compare(learned: List<Double>, hand: List<Double>): String {
        val rel = learned.indices.filter { hand[it] > 0.0 }.map { learned[it] / hand[it] - 1.0 }
        if (rel.isEmpty()) return "n/a"
        val mean = rel.average()
        val se = if (rel.size > 1) sqrt(rel.sumOf { (it - mean) * (it - mean) } / (rel.size - 1) / rel.size) else Double.NaN
        return "%+.1f%% +- %.1f%% (%d/%d)".format(100 * mean, 100 * se, rel.count { it > 0.0 }, rel.size)
    }

    /** Choices of the hand-written behaviour: the candidates' inputs and their hand-written scores. */
    class Shown(val kind: Species, val inputs: Array<FloatArray>, val scores: FloatArray, val day: Long = 0L, val groupId: Int = 0, val band: FloatArray? = null, val temper: FloatArray? = null)

    /**
     * Fits the networks to the hand-written behaviour: worlds run on it, a share of its choices of
     * every kind is kept, and the networks learn to give the same candidates the same preference
     * (the softmax of their scores matching that of the hand-written scores, sharpened).
     */
    fun imitate(kinds: List<Species>, models: List<SharedMover>, worlds: Int, size: Int, sampling: Sampling, maps: Maps) {
        val shown = (0 until worlds).toList().parallelStream().map { w ->
            val run = freshSim(maps.world(40_000L + w, size, w), kinds)
            val kept = ConcurrentLinkedQueue<Shown>()
            val stepsPerYear = WorldConfig().stepsPerYear
            val rates = kinds.associateWith { s -> val n = run.groups.groups.count { it.alive && it.species == s }; if (n == 0) 0f else min(1f, sampling.perKindYear.toFloat() / (n * stepsPerYear)) }
            for (s in kinds) run.groups.policies[s] = HexPolicy { g, f, b, rnd ->
                var best = 0; for (k in b.indices) if (b[k] > b[best]) best = k
                if (rnd.nextFloat() < (rates[g.species] ?: 0f)) {
                    kept += Shown(g.species, Array(f.size) { k -> f[k].copyOf(HexFeatures.SIZE) }, b.copyOf(), run.weather.date.absoluteDay, g.id, g.band?.state?.copyOf(), g.temper.copyOf())
                }
                best
            }
            repeat(stepsPerYear) { run.step() }
            // (In the order of the days and the groups, not of the cores that recorded them.)
            kept.sortedWith(compareBy<Shown>({ it.day }, { it.groupId }))
        }.toList().flatten()
        imitateShown(shown, models)
    }

    /** Fits each role's network to the hand-written choices in [shown] (see [imitate]). */
    fun imitateShown(shown: List<Shown>, models: List<SharedMover>) {
        for ((mi, model) in models.withIndex()) {
            val mine = shown.filter { role(it.kind) == mi }
            if (mine.isEmpty()) continue
            val kindsHere = mine.map { it.kind }.distinct()
            val counts = mine.groupingBy { it.kind }.eachCount()
            val adam = Adam(model.net.w.size, 1e-3f); val adamId = Adam(model.identity.size, 1e-3f)
            repeat(15) { epoch ->
                var loss = 0.0
                for (batch in mine.shuffled(Random(epoch.toLong())).chunked(256)) {
                    val parts = inParts(batch) { part ->
                        val gradW = FloatArray(model.net.w.size); val gradId = FloatArray(model.identity.size); val dIn = FloatArray(model.inputs)
                        val bufs = ArrayList<Mlp.Buffers>(); var l = 0.0
                        for (c in part) {
                            val n = c.inputs.size
                            while (bufs.size < n) bufs += model.net.buffers()
                            val p = softmax(FloatArray(n) { k -> model.fill(c.kind, c.inputs[k], c.band, bufs[k].a[0], c.temper); model.net.forward(bufs[k]) })
                            val q = softmax(FloatArray(n) { IMITATE_SHARPNESS * c.scores[it] })
                            val w = 1f / (kindsHere.size * counts.getValue(c.kind))
                            for (k in 0 until n) {
                                l -= w * q[k] * ln(max(p[k], 1e-8f))
                                val dOut = w * (p[k] - q[k])
                                if (dOut == 0f) continue
                                model.net.backward(bufs[k], dOut, gradW, dIn)
                                for (e in 0 until SharedMover.IDENTITY) gradId[c.kind.index * SharedMover.IDENTITY + e] += dIn[HexFeatures.SIZE + SharedMover.KIND + e]
                            }
                        }
                        Triple(gradW, gradId, l)
                    }
                    val gradW = FloatArray(model.net.w.size); val gradId = FloatArray(model.identity.size)
                    for ((a, b, l) in parts) { gradW.add(a); gradId.add(b); loss += l }
                    val scale = mine.size.toFloat() / batch.size
                    for (i in gradW.indices) gradW[i] *= scale; for (i in gradId.indices) gradId[i] *= scale
                    adam.step(model.net.w, gradW); adamId.step(model.identity, gradId)
                }
                println("imitating, %s, epoch %d: loss %.4f on %d choices".format(Role.entries[mi], epoch, loss, mine.size))
            }
        }
    }

    /** How much sharper the copied preference is than the hand-written scores themselves. */
    const val IMITATE_SHARPNESS = 10f

    /** One world for [years] with the networks choosing by chance; the recorded choices with their returns. */
    fun play(cfg: WorldConfig, years: Int, kinds: List<Species>, models: List<SharedMover>): List<Choice> {
        val steps = years * cfg.stepsPerYear
        return Player(cfg, kinds, steps).play(steps, models, null)
    }

    /**
     * A training world that lives on from round to round (for up to [lifeSteps] weeks), so that no
     * round's play is spent setting up a world, and the choices whose outcome runs past a round's end
     * are judged in the next round instead of being dropped. Choices keep the probability they were
     * made with, so being a round old does not mislead the clipped update.
     */
    class Player(val cfg: WorldConfig, private val kinds: List<Species>, private val lifeSteps: Int, private val burnSteps: Int = 0, private val sampling: Sampling = Sampling(),
                 /** Where each year's census of the world goes (its kinds' numbers), settling years included. */
                 private val census: (String) -> Unit = {}) {
        private val run = freshSim(cfg, kinds).also {
            // (Kinds that dwindle get newcomers, so every kind stays in the data; checks run without them.)
            it.groups.newcomersOn = true; it.fauna.newcomersOn = true
        }
        private var week = 0
        private val record = ConcurrentLinkedQueue<Choice>()
        /** Choices whose outcome is not yet all seen. */
        private val pending = ArrayList<Choice>()
        private val stepsPerYear = cfg.stepsPerYear
        // The same number of choices a year of each kind, however many groups it has.
        private val rates = HashMap<Species, Float>()
        private var waysRate = 0f
        /** What became of each group's members, to judge each choice by. */
        private val outcomes = Outcomes()
        /** Recorded choices waiting to see their group again [Critic.boot] weeks on. */
        private val probes = ConcurrentHashMap<Int, MutableList<Choice>>()
        /** Where each band stood this week before it moved: what its ways draw is judged from. */
        private val standing = ConcurrentHashMap<Int, Standing>()
        /** Whether it has lived its time (if it has one), or gone badly off the rails: then a fresh world takes its place. */
        val done get() = (lifeSteps > 0 && week >= lifeSteps) || derailed != null
        /**
         * Why it has gone off the rails, if it has (else null): no bands left, a kind grown tenfold since the world settled
         * (from at least [GROWTH_FLOOR] animals, so that a kind starting from a few groups may grow into its range), or half the kinds gone. Short of that it lives on, whatever befalls it, swings
         * included: the networks are to learn in worlds that have run long.
         */
        var derailed: String? = null; private set
        /** Whether its world stands within nominal limits now (the state only: a training world keeps no history). */
        fun nominal() = Nominal.state(run)
        private val startNumbers = HashMap<Species, Int>()
        private fun numbersByKind(): Map<Species, Int> = run.groups.groups.filter { it.alive }.groupingBy { it.species }.fold(0) { a, g -> a + g.size }
        private fun checkRails() {
            val now = numbersByKind()
            if (startNumbers.isEmpty()) { startNumbers.putAll(now); return }
            val gone = kinds.count { s -> run.groups.groups.none { it.alive && it.species == s } }
            val people = { m: Map<Species, Int> -> m.entries.sumOf { (s, n) -> if (s.human) n else 0 } }
            val grown = kinds.firstOrNull { s -> (now[s] ?: 0) > 10.0 * max(startNumbers[s] ?: 0, GROWTH_FLOOR) }
            derailed = when {
                people(startNumbers) > 0 && people(now) == 0 -> "no bands left"
                grown != null -> "${grown.name} grown tenfold"
                gone * 2 > kinds.size -> "$gone of ${kinds.size} kinds gone"
                else -> null
            }
        }

        // (People, the one kind of their role, get [Sampling.peopleChoices] times as many as each animal kind.)
        private fun setRates() {
            // (Nothing is recorded while a fresh world settles.)
            if (week < burnSteps) { for (s in kinds) rates[s] = 0f; waysRate = 0f; return }
            for (s in kinds) { val n = run.groups.groups.count { it.alive && it.species == s }; rates[s] = if (n == 0) 0f else min(Sampling.MAX_SHARE, sampling.perKindYear * (if (s.human) sampling.peopleChoices else 1f) / (n * stepsPerYear)) }
            waysRate = WaysPpo.rate(run.groups.groups.count { it.alive && it.band != null }, stepsPerYear)
        }

        /** [steps] more weeks with [models] choosing (and the bands' ways drawn from [ways], else the scaffold's); the choices whose outcome is now all seen, with their returns. */
        fun play(steps: Int, models: List<SharedMover>, ways: LearnedWays?): List<Choice> {
            if (week == 0) { setRates(); checkRails() }
            // A fresh world first settles for [burnSteps] weeks, its choices unrecorded (they come on top of this round's).
            val total = steps + if (week == 0) burnSteps else 0
            val season = { (week % stepsPerYear).toFloat() / stepsPerYear }
            val policies = models.mapIndexed { i, m -> MoverPolicy(m, record, { s -> rates[s] ?: 0f }, { week }, probes, season, if (ways != null && i == Role.PEOPLE.ordinal) standing else null) }
            for (s in kinds) run.groups.policies[s] = policies[role(s)]
            ways?.let { w -> run.groups.ways = WaysSampler(w, record, { waysRate }, { week }, cfg.seed, standing, probes) }
            repeat(if (lifeSteps > 0) min(total, lifeSteps - week) else total) {
                run.step()
                week++
                // (Growth is judged from the settled world, not from the seeded one: the settling years bring the kinds to their own level.)
                if (week == burnSteps) { startNumbers.clear(); startNumbers.putAll(numbersByKind()) }
                if (week % stepsPerYear == 0) {
                    setRates()
                    census("year %d%s: %s".format(week / stepsPerYear, if (week <= burnSteps) " (settling)" else "", kinds.joinToString(", ") { s -> "${s.name} ${run.groups.count(s)}" }))
                }
                outcomes.record(week, run.groups.groups, run.groups.merged)
            }
            val out = ArrayList<Choice>()
            val waiting = ArrayList<Choice>()
            // (In the order of the weeks and the groups, not of the cores that recorded them.)
            val now = record.sortedWith(compareBy<Choice>({ it.week }, { it.groupId })); record.clear()
            for (c in pending + now) {
                // (People change in numbers slowly: their moves are judged over a year, the animals' over half.)
                val horizon = if (c.kind.human) PEOPLE_HORIZON else HORIZON
                if (c.week + horizon > week) { if (!done) waiting += c; continue }
                var sum = 0f; var norm = 0f; var d = 1f
                val discount = if (c.kind.human) PEOPLE_DISCOUNT else DISCOUNT
                // (Judged by the fate of the group's own members, so that taking others in is no gain.)
                val own = outcomes.own(c.groupId, c.week, horizon)
                val boot = Critic.boot(c.kind)
                var part = 0f; var tail = 0f
                for (k in 1..horizon) {
                    val m = own[k - 1] / c.size.coerceAtLeast(1).toFloat()
                    sum += d * m; norm += d
                    if (k <= boot) part += d * m else tail += d
                    d *= discount
                }
                c.ret = sum / norm
                c.part = part / norm
                c.boot = own[boot - 1] / c.size.coerceAtLeast(1).toFloat() * tail / norm
                if (!c.ret.isFinite() || !c.part.isFinite() || !c.boot.isFinite()) { NonFinite.note(c.kind, "return"); continue }
                out += c
            }
            pending.clear(); pending += waiting
            checkRails()
            // Let go of what no waiting choice needs: the weeks before the oldest of them, and the groups gone before it.
            val oldest = pending.minOfOrNull { it.week } ?: week
            outcomes.cut(oldest, run.groups.groups.mapTo(HashSet()) { it.id })
            // (A choice whose group was not seen choosing when it was due keeps the whole horizon as seen.)
            for (waiting in probes.values) synchronized(waiting) { waiting.removeIf { it.week + Critic.boot(it.kind) < week } }
            probes.values.removeIf { it.isEmpty() }
            return out
        }
    }

    /**
     * How the kinds do in [cfg] over [years] (animal-weeks per animal at the start) with each role's
     * network choosing alone, the other roles hand-written, against all hand-written: for each kind,
     * its score in the run where its own role was learned, and in the hand-written run.
     */
    fun check(cfg: WorldConfig, years: Int, kinds: List<Species>, models: List<SharedMover>): Versus<Map<Species, Double>> {
        val roles = kinds.map { role(it) }.distinct()
        // (The runs go side by side: the hand-written one, and one for each role learned.)
        val runs = (listOf(-1) + roles).parallelStream().map { r ->
            r to if (r < 0) handScore(cfg, years, kinds) else score(cfg, years, kinds) { run ->
                val p = MoverPolicy(models[r], null, { 0f }, { 0 }); for (s in kinds) if (role(s) == r) run.groups.policies[s] = p
            }
        }.toList().toMap()
        val hand = runs.getValue(-1)
        return Versus(kinds.associateWith { s -> runs.getValue(role(s))[s] ?: 0.0 }, hand)
    }

    /**
     * [score] with everything hand-written, worked out once for each world and kept: it is the same
     * every check (the runs are reproducible).
     */
    internal fun handScore(cfg: WorldConfig, years: Int, kinds: List<Species>): Map<Species, Double> =
        handScores.computeIfAbsent(Triple(cfg, years, kinds.map { it.name })) { score(cfg, years, kinds) { } }
    private val handScores = ConcurrentHashMap<Triple<WorldConfig, Int, List<String>>, Map<Species, Double>>()

    /**
     * Animal-weeks per animal at the start, by kind, over [years] in [cfg] with the policies [setUp]
     * installs; for people, less their luck in their own births and deaths (see [HexGroup.luck]), so
     * that a small gain shows through the chance of who was born and who died.
     */
    internal fun score(cfg: WorldConfig, years: Int, kinds: List<Species>, setUp: (HexSim) -> Unit): Map<Species, Double> {
        val run = freshSim(cfg, kinds)
        setUp(run)
        val score = RunScore(run, kinds)
        repeat(years * cfg.stepsPerYear) { run.step(); score.afterStep(run) }
        return score.value()
    }
}

/**
 * The training's score of a run, kept step by step: animal-weeks per animal at the start, by kind; for
 * people, less their luck in their own births and deaths (see [inflation.ecology.HexGroup.luck]), so
 * that a small gain shows through the chance of who was born and who died. Made before the first step;
 * [afterStep] after each.
 */
class RunScore(run: HexSim, private val kinds: List<Species>) {
    private val start = kinds.associateWith { run.groups.count(it).toDouble() }
    private val weeks = HashMap<Species, Double>()
    private val lastLuck = HashMap<Int, Float>()
    private val luck = HashMap<Species, Double>()
    private var steps = 0

    fun afterStep(run: HexSim) {
        for (g in run.groups.groups) {
            if (g.species.human) { luck.merge(g.species, (g.luck - (lastLuck[g.id] ?: 0f)).toDouble(), Double::plus); lastLuck[g.id] = g.luck }
            if (g.alive) weeks.merge(g.species, g.size.toDouble(), Double::plus)
        }
        for ((s, l) in luck) weeks.merge(s, -l, Double::plus)
        steps++
    }

    fun value(): Map<Species, Double> = kinds.associateWith { s -> val st = start.getValue(s); if (st <= 0.0 || steps == 0) 0.0 else (weeks[s] ?: 0.0) / st / steps }
}
