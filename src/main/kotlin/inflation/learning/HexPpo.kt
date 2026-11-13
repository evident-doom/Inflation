package inflation.learning

import inflation.inflationHome
import inflation.DAYS_PER_YEAR
import inflation.Tuning
import inflation.Part
import inflation.run.Nominal
import inflation.run.freshSim
import inflation.ecology.MoveGate
import inflation.ecology.HexSim
import inflation.ecology.HexSnapshot
import inflation.ecology.foundMinds
import inflation.ecology.Catalogue
import inflation.Config
import inflation.ecology.HexFeatures
import inflation.ecology.Temperament
import inflation.ecology.HexGroup
import inflation.ecology.HexPolicy
import inflation.ecology.Species
import inflation.stream
import inflation.Stream
import inflation.world.WorldCache
import inflation.world.WorldConfig
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random
import inflation.ecology.Band
import inflation.ecology.DeathClass
import inflation.ecology.LOST_ALL
import inflation.ecology.BandInputs
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
        if (temper == null) 0f else Temperament.pull(temper, features[HexFeatures.HUNTERS], features[HexFeatures.REMEMBERED], features[HexFeatures.STAYS], features[HexFeatures.CROWDING])

    /** How many numbers of the band's own view ([Band.state]) it reads after the kind's part: [WAYS_IN] for people, 0 for animals. */
    val band get() = inputs - INPUTS

    /** Writes a candidate's whole input for kind [s]: its place features, the kind's part, the group's temperament [temper], and (people's) the band's view of itself, [state]. */
    fun fill(s: Species, features: FloatArray, state: FloatArray?, into: FloatArray, temper: FloatArray? = null) {
        features.copyInto(into, 0, 0, min(features.size, HexFeatures.size))
        kindInputs(s, into, HexFeatures.size)
        for (k in 0 until Temperament.AXES) into[HexFeatures.size + KIND + IDENTITY + k] = temper?.getOrElse(k) { 0f } ?: 0f
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
        val n = features.size; val v = HexFeatures.size
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

    fun copy() = SharedMover(net.copy(), identity.copyOf()).also { it.residual = residual }

    fun save(file: File) {
        Schema.save(net, file, if (band > 0) Schema.PEOPLE_IN else Schema.MOVER_IN, Schema.MOVER_OUT, (if (band > 0) Role.PEOPLE else Role.HUNTERS).net)
        File(file.path + ".residual").let { if (residual != 0f) it.writeText(residual.toString()) else it.delete() }
        File(file.path + ".identity").writeText(identity.joinToString(" ") + "\n")
    }

    companion object {
        const val KIND = 14
        const val IDENTITY = 6
        val INPUTS = HexFeatures.size + KIND + IDENTITY + Temperament.AXES

        fun random(hidden: Int, rnd: Random, band: Int = 0) = SharedMover(Mlp.random(intArrayOf(INPUTS + band, hidden, hidden, 1), rnd), FloatArray(Catalogue.all.size * IDENTITY) { 0.1f * (rnd.nextFloat() - 0.5f) })

        /** Whether a mover saved in [file] is people's (it reads the band's view of itself too). */
        fun isPeople(file: File) = file.name.startsWith(Role.PEOPLE.label)

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
    /**
     * Added to what it is judged against, for the move or the ways draw alone, never to what the critic learns: the deaths, by the cause
     * classes, that this choice is not to answer for in full (see [HexPpo.Player]'s `moverKitWeight`, `waysPlaceWeight`).
     */
    var credit = 0f
    /** The deaths the odds made likely among the members over the horizon, as the share of the return they cost: of cold and camp, of going without, of all causes. */
    var lostKit = 0f; var lostFood = 0f; var lostAll = 0f
    /** Recorded for the critic alone (taken as the best was, not drawn): it judges no move, only teaches what a group in its place can expect. */
    var valueOnly = false
}

/**
 * Where a band stood in week [week], as the critic reads it ([at]): the state its ways draw is judged from (see [WaysChoice]).
 */
class Standing(val week: Int, val at: Probe)

/** Whether recorded choice [c] is due to see its group again in step [w]: the first step at least [Critic.bootDays] after it chose ([steps]; null: [Critic.boot] steps on). */
internal fun dueAgain(c: Choice, w: Int, steps: StepLog?) = if (steps == null) c.week + Critic.boot(c.kind) == w else steps.reach(c.week, Critic.bootDays(c.kind)) == w - c.week

/** Gives the choices of group [id] waiting in [probes] that are due to see it again in step [w] ([dueAgain]) where it stands now, [p]. */
internal fun seeAgain(probes: ConcurrentHashMap<Int, MutableList<Choice>>, id: Int, w: Int, p: Probe, steps: StepLog?) {
    val waiting = probes[id] ?: return
    synchronized(waiting) {
        val due = waiting.filter { dueAgain(it, w, steps) }
        if (due.isEmpty()) return
        // (Where it then stood is unknown to the critic if any of it is not a number: its whole horizon counts as seen.)
        val known = p.features.allFinite() && p.band.allFinite() && p.temper.allFinite() && p.group.allFinite()
        if (known) for (c in due) c.probe = p
        waiting.removeAll(due)
    }
}

/**
 * People's choice of camp once no network makes it (people's net major 11; docs/design/band-movement-removal.md, Phase 6): a band
 * that holds its hex camps on its low ground, as one with no network does ([inflation.ecology.GroupPlanner.stayPlan]; the planner
 * sets it). It goes through the planner, held to its hex, only for its camp's terms: this stores where it stood ([Standing]) for its
 * ways' draws, sees its recorded weeks and draws again ([probes]), records a share [rate] of its weeks for the people's critic alone
 * ([Choice.valueOnly]: what a band where it stands can expect, against which its ways' draws are judged), and gives the ways its
 * camp's terms ([planned], [CampTerms]). With no [record], it records nothing.
 */
class CampPolicy(private val record: ConcurrentLinkedQueue<Choice>? = null, private val rate: (Species) -> Float = { 0f }, private val week: () -> Int = { 0 },
                 private val probes: ConcurrentHashMap<Int, MutableList<Choice>>? = null, private val season: () -> Float = { 0f },
                 private val standing: ConcurrentHashMap<Int, Standing>? = null, private val steps: StepLog? = null) : HexPolicy {
    override fun choose(g: HexGroup, features: Array<FloatArray>, baseline: FloatArray, rnd: kotlin.random.Random): Int {
        val here = Critic.here(features)
        if (g.band == null || features.isEmpty() || (record == null && standing == null && probes == null)) return here
        val w = week()
        val at = Probe(features[here].copyOf(HexFeatures.size), g.band?.state?.copyOf(), g.temper.copyOf(), Critic.groupState(g, season()))
        standing?.set(g.id, Standing(w, at))
        if (probes != null) seeAgain(probes, g.id, w, at, steps)
        // (A band whose mind reckons by itself whether it leaves is not learnt from: see [MoverPolicy].)
        if (record == null || g.band?.mind?.gate == MoveGate.RECKONED || rnd.nextFloat() >= rate(g.species)) return here
        if (!at.features.allFinite() || !at.band.allFinite() || !at.temper.allFinite() || !at.group.allFinite()) { NonFinite.note(g.species, "group"); return here }
        val c = Choice(g.species, g.id, g.size, w, arrayOf(at.features), 0, 0f).also { it.temper = at.temper; it.band = at.band; it.group = at.group; it.valueOnly = true }
        record += c
        probes?.computeIfAbsent(g.id) { ArrayList() }?.let { waiting -> synchronized(waiting) { waiting += c } }
        return here
    }
    /** (The planner sets the camp by the hand-written rule, not by the score.) */
    override fun readsBaseline(g: HexGroup) = false
    override fun planned(g: HexGroup, chosen: FloatArray): FloatArray? = if (g.band == null) null else CampTerms.of(chosen)
}

/** Chooses with a [SharedMover], recording a share of its choices in [record] (null: the best, not sampled). */
class MoverPolicy(private val mover: SharedMover, private val record: ConcurrentLinkedQueue<Choice>?, private val rate: (Species) -> Float, private val week: () -> Int,
                  /** Recorded choices waiting to see their group again some weeks on, by group (see [Choice.probe]); null: none wanted. */
                  private val probes: ConcurrentHashMap<Int, MutableList<Choice>>? = null, private val season: () -> Float = { 0f },
                  /** Where each band stood when it chose this week, before it moved (see [Standing]); null: not wanted. */
                  private val standing: ConcurrentHashMap<Int, Standing>? = null,
                  /** The share of the choices not drawn that are still recorded for the critic to learn from ([Choice.valueOnly]). */
                  private val valueRate: (Species) -> Float = { 0f },
                  /** How many times more a band at its hardest (cold exposure or hunger) is recorded than one at ease: the nets learn from the weeks that decide (see [Sampling.stressBoost]). */
                  private val stressBoost: Float = 0f,
                  /** The days the world's steps covered, to see a group again [Critic.bootDays] on whatever the steps' lengths; null: [Critic.boot] steps on. */
                  private val steps: StepLog? = null) : HexPolicy {
    private val scratch = ThreadLocal.withInitial { mover.Scratch() }
    /** The scores this thread's last [choose] worked out for a band, and which band: one that then explores asks for them again ([scores]). */
    private class Last { var id = -1; var scores: FloatArray? = null }
    private val last = ThreadLocal.withInitial { Last() }

    /** The chance [g]'s choice is drawn and recorded: its kind's, raised for a band under stress by [stressBoost] (never past [Sampling.MAX_STRESSED_SHARE]). */
    private fun recordRate(g: HexGroup, state: FloatArray?): Float {
        val r = rate(g.species)
        if (stressBoost <= 0f || state == null || state.size <= BandInputs.COLD_ODDS) return r
        val stress = max(state[BandInputs.COLD_ODDS], 1f - state[BandInputs.FED]).coerceIn(0f, 1f)
        return min(Sampling.MAX_STRESSED_SHARE, r * (1f + stressBoost * stress))
    }

    @Part("choosing: network")
    override fun choose(g: HexGroup, features: Array<FloatArray>, baseline: FloatArray, rnd: kotlin.random.Random): Int {
        val n = features.size
        val state = g.band?.state; val temper = g.temper
        if (standing != null && g.band != null && n > 0)
            standing[g.id] = Standing(week(), Probe(features[Critic.here(features)].copyOf(HexFeatures.size), state?.copyOf(), temper.copyOf(), Critic.groupState(g, season())))
        if (probes != null && probes[g.id] != null)
            seeAgain(probes, g.id, week(), Probe(features[Critic.here(features)].copyOf(HexFeatures.size), state?.copyOf(), temper.copyOf(), Critic.groupState(g, season())), steps)
        g.weighed = true
        val net = mover.netScores(g.species, features, state, temper, scratch.get())
        val scores = FloatArray(n) { k -> net[k] + mover.residual * baseline[k] + mover.pull(temper, features[k]) }
        if (g.band != null) last.get().let { it.id = g.id; it.scores = scores.copyOf() }
        // A place whose score or features are not numbers is never taken, as with the hand-written score; and if no place
        // can be weighed the group stays where it is. One such number learnt from makes the whole network not a number.
        var weighed = 0
        for (k in 0 until n) if (scores[k].isFinite() && features[k].allFinite(HexFeatures.size)) weighed++ else {
            NonFinite.note(g.species, features[k], baseline[k], net[k], mover.pull(temper, features[k])); scores[k] = Float.NEGATIVE_INFINITY }
        if (weighed == 0) return Critic.here(features)
        // Only the choices recorded to learn from are drawn by chance here; every other group takes its best (a band then draws
        // by its will to explore, see [HexGroup.drawn]), so the training worlds live as they would with the networks choosing.
        // (A band whose mind decides by itself whether it leaves ([MoveGate.RECKONED], the planning kind) asks this network only
        // where to go once it leaves: its weeks are not the network's choices, so none is recorded or learnt from, not even for the
        // critic, whose returns would mix two ways of choosing it cannot tell apart. Drawn, it went where the draw sent it and
        // not where its own reckoning would; review/2026-11-05-people-models-harmonize.md, step 3.)
        val learns = g.band?.mind?.gate != MoveGate.RECKONED
        if (record == null || !learns || rnd.nextFloat() >= recordRate(g, state)) {
            var best = 0; for (k in 1 until n) if (scores[k] > scores[best]) best = k
            // (The groups that take their best still show what a group in their place can expect: the critic learns from a share of them too.)
            if (record != null && learns && valueRate(g.species) > 0f && rnd.nextFloat() < valueRate(g.species)) {
                val group = Critic.groupState(g, season())
                if (temper.allFinite() && state.allFinite() && group.allFinite()) {
                    val c = Choice(g.species, g.id, g.size, week(), arrayOf(features[Critic.here(features)].copyOf(HexFeatures.size)), 0, 0f)
                        .also { it.temper = temper.copyOf(); if (mover.band > 0) it.band = state?.copyOf(); it.group = group; it.valueOnly = true }
                    record += c
                    probes?.computeIfAbsent(g.id) { ArrayList() }?.let { waiting -> synchronized(waiting) { waiting += c } }
                }
            }
            return best
        }
        g.drawn = true
        val p = softmax(scores)
        var u = rnd.nextFloat(); var taken = -1
        for (k in 0 until n) if (p[k] > 0f) { taken = k; u -= p[k]; if (u <= 0f) break }
        val group = Critic.groupState(g, season())
        // (Nor is a choice learnt from whose group's own numbers are not all numbers.)
        if (!temper.allFinite() || !state.allFinite() || !group.allFinite()) { NonFinite.note(g.species, "group"); return taken }
        // (The places not weighed are left out of what is learnt.)
        val kept = (0 until n).filter { scores[it] > Float.NEGATIVE_INFINITY }
        val c = Choice(g.species, g.id, g.size, week(), Array(kept.size) { features[kept[it]].copyOf(HexFeatures.size) }, kept.indexOf(taken), ln(max(p[taken], 1e-8f)))
            .also { it.baseline = FloatArray(kept.size) { baseline[kept[it]] }; it.temper = temper.copyOf(); if (mover.band > 0) it.band = state?.copyOf(); it.group = group }
        record += c
        probes?.computeIfAbsent(g.id) { ArrayList() }?.let { waiting -> synchronized(waiting) { waiting += c } }
        return taken
    }
    /** (The hand-written score counts only through [SharedMover.residual], and in the choices recorded to learn from.) */
    override fun readsBaseline(g: HexGroup) = mover.residual != 0f || record != null
    override fun scores(g: HexGroup, features: Array<FloatArray>, baseline: FloatArray): FloatArray {
        // (Asked straight after [choose] for the same places, as a band that explores does: the same scores, not worked out twice.)
        val l = last.get(); val was = l.id; val kept = l.scores
        l.id = -1; l.scores = null
        if (was == g.id && kept != null && kept.size == features.size) return kept
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
        for (i in 0 until min(features.size, HexFeatures.size)) if (!features[i].isFinite()) note(s, "feature ${HexFeatures.names[i]}")
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
     * Weeks ([StepLog.WEEK], 6 days) over which a band's choice is judged: a third of a year. Since bands hold their home hex
     * (people net major 11) their choices are their ways ([WaysPpo]), drawn for a quarter, whose levers (stores, size, splits,
     * disputes) act over the year and beyond. 13 (78 days) until 2026-11-13: what a move did to its band showed within about
     * 8 to 13 weeks (see tools/HexChoiceEffect).
     */
    const val PEOPLE_HORIZON = 20
    const val PEOPLE_DISCOUNT = 0.98f
    /** The fewest animals of a kind its tenfold growth is measured from: a kind may grow from a few groups to this tenfold before the world counts as off the rails. */
    const val GROWTH_FLOOR = 10_000
    /** How far a learning step may move a choice's odds from those it was drawn by (PPO's clip), for the movers and the ways networks alike. */
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
     * (the softmax of their scores matching that of the hand-written scores, sharpened). Only the networks of [roles] learn;
     * the others are left as they are.
     */
    fun imitate(kinds: List<Species>, models: List<SharedMover>, worlds: Int, size: Int, sampling: Sampling, maps: Maps, roles: Set<Role> = Role.entries.toSet()) {
        val shown = (0 until worlds).toList().parallelStream().map { w ->
            val run = freshSim(maps.world(40_000L + w, size, w), kinds).also { it.stats.census = Config.flag(Config.Key.CENSUS, false) }
            val kept = ConcurrentLinkedQueue<Shown>()
            val stepsPerYear = run.stepsPerYear
            val rates = kinds.associateWith { s -> val n = run.groups.groups.count { it.alive && it.species == s }; if (n == 0) 0f else min(1f, sampling.perKindYear.toFloat() / (n * stepsPerYear)) }
            for (s in kinds) run.groups.policies[s] = HexPolicy { g, f, b, rnd ->
                var best = 0; for (k in b.indices) if (b[k] > b[best]) best = k
                if (rnd.nextFloat() < (rates[g.species] ?: 0f) && Role.of(g.species) in roles) {
                    kept += Shown(g.species, Array(f.size) { k -> f[k].copyOf(HexFeatures.size) }, b.copyOf(), run.weather.date.absoluteDay, g.id, g.band?.state?.copyOf(), g.temper.copyOf())
                }
                best
            }
            repeat(stepsPerYear) { run.step() }
            // (In the order of the days and the groups, not of the cores that recorded them.)
            kept.sortedWith(compareBy<Shown>({ it.day }, { it.groupId }))
        }.toList().flatten()
        imitateShown(shown, models, roles)
    }

    /** Fits each role's network in [roles] to the hand-written choices in [shown] (see [imitate]). */
    fun imitateShown(shown: List<Shown>, models: List<SharedMover>, roles: Set<Role> = Role.entries.toSet()) {
        for ((mi, model) in models.withIndex()) {
            if (Role.entries[mi] !in roles) continue
            val mine = shown.filter { role(it.kind) == mi }
            if (mine.isEmpty()) continue
            val kindsHere = mine.map { it.kind }.distinct()
            val counts = mine.groupingBy { it.kind }.eachCount()
            val adam = Adam(model.net.w.size, 1e-3f); val adamId = Adam(model.identity.size, 1e-3f)
            repeat(15) { epoch ->
                var loss = 0.0
                for (batch in mine.shuffled(stream(3L, Stream.BATCHES, 0L, epoch.toLong())).chunked(256)) {
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
                                for (e in 0 until SharedMover.IDENTITY) gradId[c.kind.index * SharedMover.IDENTITY + e] += dIn[HexFeatures.size + SharedMover.KIND + e]
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
        val days = years * DAYS_PER_YEAR
        return Player(cfg, kinds, days).play(days, models, null)
    }

    /**
     * A training world that lives on from round to round (for up to [lifeDays] days), so that no
     * round's play is spent setting up a world, and the choices whose outcome runs past a round's end
     * are judged in the next round instead of being dropped. Choices keep the probability they were
     * made with, so being a round old does not mislead the clipped update.
     *
     * Its steps are `t.stepDays` long, or, with the config key `ppo.stepChoices` (days, comma-separated, say `6,12,30,60`; each
     * a whole number of ticks, all dividing a block that divides the year), drawn: each block of the year (the least
     * length all the choices divide, 60 days for those four) is stepped through in steps of one length drawn for it, so
     * that the networks learn from worlds stepped at every length, and every step stands where a step of its length would
     * in a world stepped at that length alone. The horizons, boots, discounts and seasons are set in days, so that a choice
     * is judged over the same days whatever its steps' lengths ([StepLog]); at one tick a step, as over weeks before.
     */
    class Player(val cfg: WorldConfig, private val kinds: List<Species>, private val lifeDays: Int, private val burnDays: Int = 0, private val sampling: Sampling = Sampling(),
                 /** Where each year's census of the world goes (its kinds' numbers), settling years included. */
                 private val census: (String) -> Unit = {}) {
        private val run = freshSim(cfg, kinds).also {
            // (Kinds that dwindle get newcomers, so every kind stays in the data; checks run without them.)
            it.groups.newcomersOn = true; it.fauna.newcomersOn = true
            // (Trainings keep no census, the report-only tallies: set `census=true` to have it.)
            it.stats.census = Config.flag(Config.Key.CENSUS, false)
        }
        private var week = 0
        /** Days played since the world's start (the settling days included). */
        private var day = 0
        /** The day each step began on ([StepLog]). */
        private val steps = StepLog()
        /** The step lengths drawn from (`ppo.stepChoices`), and the block of days each draw holds for; none: `t.stepDays`. */
        private val choices = (Config.string(Config.Key.PPO_STEP_CHOICES) ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { it.toInt() }
        private val block = choices.fold(1) { a, b -> a / gcd(a, b) * b }.also { b ->
            require(choices.isEmpty() || (choices.all { it >= cfg.daysPerStep && it % cfg.daysPerStep == 0 } && DAYS_PER_YEAR % b == 0)) { "ppo.stepChoices $choices: whole ticks, all dividing a block that divides the year" } }
        /** The length drawn for the block under way (a tick until the first block starts). */
        private var drawn = cfg.daysPerStep
        /** Sets the coming step's length: drawn at each block's start. */
        private fun setStep() {
            if (choices.isEmpty()) return
            val date = run.weather.date
            // (Blocks count from the start day, as a world stepped at one length alone steps from it.)
            val at = Math.floorMod(date.dayOfYear - Tuning.config.startDay, block)
            if (at == 0) drawn = choices[stream(cfg.seed, Stream.STEP_LENGTH, 0L, date.absoluteDay).nextInt(choices.size)]
            // (A step that would not stand where a step of its length would is cut to ticks: only before the first block starts.)
            run.stepDays = if (at % drawn == 0 && at + drawn <= block) drawn else cfg.daysPerStep
        }
        /** Share of the choices of the kinds' rates (set a week, [StepLog.WEEK] days) a step of the coming length takes. */
        private fun perStep() = run.stepDays.toFloat() / StepLog.WEEK
        private val record = ConcurrentLinkedQueue<Choice>()
        /** Choices whose outcome is not yet all seen. */
        private val pending = ArrayList<Choice>()
        /** Weeks ([StepLog.WEEK] days) in a year: what the rates are set by. */
        private val stepsPerYear = DAYS_PER_YEAR / StepLog.WEEK
        // The same number of choices a year of each kind, however many groups it has.
        private val rates = HashMap<Species, Float>()
        private val valueRates = HashMap<Species, Float>()
        private var waysRate = 0f
        /** What became of each group's members, to judge each choice by. */
        private val outcomes = Outcomes()
        /**
         * Config key `ppo.foreseenShape` (default 1; 0: none): a band's choice is judged also by the change it brought in what its
         * members could foresee: the share of them foreseen alive a year ahead ([HexGroup.foreseen] of the groups they are in, times
         * the share of them still alive) at the end of the horizon, less the share foreseen when it chose, times this weight. Added to
         * the return over the whole horizon, and to its first [Critic.boot] weeks with the share foreseen then (the critic's estimate
         * from there carries the rest). Shaping by a potential (Ng, Harada and Russell 1999, ICML): the change in a function of the
         * state, so what is best stays best, while a choice whose harm or good shows only after the horizon (stores, fat, kit for
         * the winter) is seen sooner. Undiscounted, as the return is a weighted mean, not a discounted sum; so the invariance holds
         * only roughly here. Deaths within the horizon count both in the return and in the share still alive.
         */
        private val shape = Config.float(Config.Key.PPO_FORESEEN_SHAPE, 1f)
        /**
         * Config keys `ppo.moverKitWeight` and `ppo.waysPlaceWeight` (default 1: none): the deaths a band's choices are judged by are told
         * apart by cause. A move is judged by deaths of cold and camp ([DeathClass.KIT]), which the ways (what it wears, its fire, shelter
         * and camp) act on more, at this weight; a ways draw by deaths of causes neither food nor kit (beasts, snakes, sickness, raids...),
         * which the move acts on more, at its weight. The share of the return those deaths cost, less than whole, is given back to the choice
         * as [Choice.credit]. Deaths of going without count whole in both: the move and the food efforts both answer for them. The critic
         * is not told: it learns the whole return, so that one baseline serves both.
         */
        private val moverKitWeight = Config.float(Config.Key.PPO_MOVER_KIT_WEIGHT, 1f)
        private val waysPlaceWeight = Config.float(Config.Key.PPO_WAYS_PLACE_WEIGHT, 1f)
        /** Recorded choices waiting to see their group again [Critic.boot] weeks on. */
        private val probes = ConcurrentHashMap<Int, MutableList<Choice>>()
        /** Where each band stood this week before it moved: what its ways draw is judged from. */
        private val standing = ConcurrentHashMap<Int, Standing>()
        /** Whether it has lived its time (if it has one), or gone badly off the rails: then a fresh world takes its place. */
        val done get() = (lifeDays > 0 && day >= lifeDays) || derailed != null
        /**
         * Why it has gone off the rails, if it has (else null): no bands left, a kind grown tenfold since the world settled
         * (from at least [GROWTH_FLOOR] animals, so that a kind starting from a few groups may grow into its range), or half the kinds gone. Short of that it lives on, whatever befalls it, swings
         * included: the networks are to learn in worlds that have run long.
         */
        var derailed: String? = null; private set
        /** Whether its world stands within nominal limits now (the state only: a training world keeps no history). */
        fun nominal() = Nominal.state(run)
        private val startNumbers = HashMap<Species, Int>()
        /**
         * Heads of kind [s]: in its groups, and for a kind that lives settled with roaming packs ([Catalogue.roamingResidents]:
         * wolves, lions) its settled too, where nearly all of them live (the packs are the few a world starts with, and the
         * dogs of bands that died out).
         */
        private fun heads(s: Species): Int = run.groups.count(s) +
            (if (s in Catalogue.roamingResidents) run.groups.fauna.slot(s).let { k -> if (k.none) 0 else run.groups.fauna.count(k).toInt() } else 0)
        private fun numbersByKind(): Map<Species, Int> = kinds.associateWith { heads(it) }.filterValues { it > 0 }
        private fun checkRails() {
            val now = numbersByKind()
            if (startNumbers.isEmpty()) { startNumbers.putAll(now); return }
            val gone = kinds.count { s -> (now[s] ?: 0) == 0 }
            val people = { m: Map<Species, Int> -> m.entries.sumOf { (s, n) -> if (s.human) n else 0 } }
            val grown = kinds.firstOrNull { s -> (now[s] ?: 0) > 10.0 * max(startNumbers[s] ?: 0, GROWTH_FLOOR) }
            derailed = when {
                people(startNumbers) > 0 && people(now) == 0 -> "no bands left"
                grown != null -> "${grown.name} grown tenfold"
                gone * 2 > kinds.size -> "$gone of ${kinds.size} kinds gone"
                else -> null
            }
        }

        /**
         * The world that has settled for [burnDays] days is kept (in `~/.inflation/settled-worlds`, or the directory the config key
         * `ppo.settledWorlds` names; empty or `off`: not kept), and a later start of the same world (its settings and world-making
         * code, kinds, settling and model version) takes it up instead of settling again. It is the world as the networks of the start
         * that made it settled it. A file that today's code will not load is settled again.
         */
        private val settledFile = (Config.string(Config.Key.PPO_SETTLED_WORLDS) ?: inflationHome("settled-worlds").path)
            .takeIf { it.isNotBlank() && it != "off" }?.let { dir ->
                File(dir, "settled-%016x-burn%d-k%08x-m%d%s.bin".format(WorldCache.key(cfg), burnDays / StepLog.WEEK, kinds.map { it.name }.hashCode(), HexSnapshot.MODEL_MAJOR,
                    if (choices.isNotEmpty()) "-s" + choices.joinToString("_") else if (run.stepDays != cfg.daysPerStep) "-s${run.stepDays}" else ""))
            }

        private fun loadSettled() {
            val f = settledFile?.takeIf { it.isFile } ?: return
            try {
                HexSnapshot.load(run, f)
            } catch (e: Exception) {
                census("settled world $f not taken up (${e.message?.lines()?.firstOrNull()}): settling afresh")
                return
            }
            // (Its bands take up the planning kind's mind as founders do, t.planners.)
            run.groups.foundMinds()
            week = burnDays / StepLog.WEEK; day = burnDays; steps.reset(week, day)
            startNumbers.clear(); startNumbers.putAll(numbersByKind())
            outcomes.record(week, run.groups.groups, run.groups.merged)
            census("settled world taken up from $f")
        }

        private fun saveSettled() {
            val f = settledFile ?: return
            if (f.exists()) return
            // (Written under a name of its own and moved into place whole: starts side by side may settle the same world at once.)
            val mine = File(f.path + ".${ProcessHandle.current().pid()}-${System.nanoTime()}.part")
            try {
                HexSnapshot.save(run, mine)
                java.nio.file.Files.move(mine.toPath(), f.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                census("settled world kept in $f")
            } catch (e: Exception) { mine.delete(); census("settled world not kept: ${e.message}") }
        }

        // (People, the one kind of their role, get [Sampling.peopleChoices] times as many as each animal kind.)
        private fun setRates() {
            // (Nothing is recorded while a fresh world settles.)
            if (day < burnDays) { for (s in kinds) { rates[s] = 0f; valueRates[s] = 0f }; waysRate = 0f; return }
            for (s in kinds) { val n = run.groups.groups.count { it.alive && it.species == s }; rates[s] = if (n == 0) 0f else min(if (s.human) sampling.maxShare else Sampling.MAX_SHARE, sampling.perKindYear * (if (s.human) sampling.peopleChoices else 1f) / (n * stepsPerYear))
                valueRates[s] = if (s.human) min(Sampling.MAX_VALUE_SHARE, rates.getValue(s) * (sampling.criticTimes - 1f)) else 0f }
            waysRate = WaysPpo.rate(run.groups.groups.count { it.alive && it.band != null }, stepsPerYear)
        }

        /** [days] more days with [models] choosing (and the bands' ways drawn from [ways], else the scaffold's); the choices whose outcome is now all seen, with their returns. */
        fun play(days: Int, models: List<SharedMover>, ways: LearnedWays?): List<Choice> {
            if (week == 0 && burnDays > 0) loadSettled()
            if (week == 0) { setRates(); checkRails() }
            // A fresh world first settles for [burnDays] days, its choices unrecorded (they come on top of this round's).
            val total = days + if (week == 0) burnDays else 0
            val end = if (lifeDays > 0) min(day + total, lifeDays) else day + total
            val season = { (day % DAYS_PER_YEAR).toFloat() / DAYS_PER_YEAR }
            // (People's camps are hand-written, their low ground (people's net major 11): no network chooses them, so their weeks are
            // recorded for the critic alone, as many as the moves and the critic's extra share were, and their ways read the camp's terms.)
            val policies = models.mapIndexed { i, m -> if (i == Role.PEOPLE.ordinal)
                CampPolicy(record, { s -> min(1f, (rates[s] ?: 0f) * perStep() + min(Sampling.MAX_VALUE_SHARE, (valueRates[s] ?: 0f) * perStep())) }, { week }, probes, season,
                    if (ways != null) standing else null, steps)
                else MoverPolicy(m, record, { s -> min(1f, (rates[s] ?: 0f) * perStep()) }, { week }, probes, season, null,
                { s -> min(Sampling.MAX_VALUE_SHARE, (valueRates[s] ?: 0f) * perStep()) }, sampling.stressBoost, steps) }
            for (s in kinds) run.groups.policies[s] = policies[role(s)]
            ways?.let { w -> run.groups.ways = WaysSampler(w, record, { waysRate }, { week }, cfg.seed, standing, probes, { day }, { run.stepDays }) }
            while (day < end) {
                setStep()
                run.step()
                val before = day
                week++; day += run.stepDays; steps.stepped(run.stepDays)
                // (Growth is judged from the settled world, not from the seeded one: the settling years bring the kinds to their own level.)
                if (before < burnDays && day >= burnDays) { startNumbers.clear(); startNumbers.putAll(numbersByKind()); saveSettled() }
                if (before / DAYS_PER_YEAR != day / DAYS_PER_YEAR) {
                    setRates()
                    census("year %d%s: %s".format(day / DAYS_PER_YEAR, if (day <= burnDays) " (settling)" else "", kinds.joinToString(", ") { s -> if (s in Catalogue.roamingResidents) "${s.name} ${heads(s)} (${run.groups.count(s)} roaming)" else "${s.name} ${run.groups.count(s)}" }))
                }
                outcomes.record(week, run.groups.groups, run.groups.merged)
            }
            val out = ArrayList<Choice>()
            val waiting = ArrayList<Choice>()
            // (In the order of the weeks and the groups, not of the cores that recorded them.)
            val now = record.sortedWith(compareBy<Choice>({ it.week }, { it.groupId })); record.clear()
            for (c in pending + now) {
                // (People change in numbers slowly: their moves are judged over a year, the animals' over half.)
                // (Over at least the horizon's days, in the steps that cover them: at one tick a step, the weeks of the horizon.)
                val horizon = steps.reach(c.week, (if (c.kind.human) PEOPLE_HORIZON else HORIZON) * StepLog.WEEK)
                if (horizon < 0) { if (!done) waiting += c; continue }
                var sum = 0f; var norm = 0f; var d = 1f
                val discount = if (c.kind.human) PEOPLE_DISCOUNT else DISCOUNT
                // (Each step weighs as the weeks it covers, discounted from its first day: [discount] a week.)
                val length = { k: Int -> (steps.dayOf(c.week + k) - steps.dayOf(c.week + k - 1)).toFloat() / StepLog.WEEK }
                val fall = { k: Int -> length(k).let { if (it == 1f) discount else discount.pow(it) } }
                // (Judged by the fate of the group's own members, so that taking others in is no gain.)
                val fore = if (c.kind.human && shape != 0f) FloatArray(horizon) else null
                val lost = if (c.kind.human) FloatArray(3 * horizon) else null
                val own = outcomes.own(c.groupId, c.week, horizon, fore, lost)
                // (Its members then, as [own] counts them: for a band, in years ahead, see [inflation.ecology.FutureYears].)
                val start = outcomes.members(c.groupId, c.week).takeIf { it > 0f } ?: c.size.coerceAtLeast(1).toFloat()
                val boot = steps.reach(c.week, Critic.bootDays(c.kind))
                var part = 0f; var tail = 0f
                for (k in 1..horizon) {
                    val m = own[k - 1] / start
                    val wk = d * length(k)
                    sum += wk * m; norm += wk
                    if (k <= boot) part += wk * m else tail += wk
                    d *= fall(k)
                }
                if (lost != null) {
                    // (A death in week j costs the weeks from j on: its weight in the return is theirs, the discounted weeks from there on over all.)
                    val n = c.size.coerceAtLeast(1).toFloat()
                    var before = 0f; var dk = 1f
                    for (k in 1..horizon) {
                        val worth = (norm - before) / norm
                        c.lostKit += lost[3 * (k - 1) + DeathClass.KIT.ordinal] / n * worth
                        c.lostFood += lost[3 * (k - 1) + DeathClass.FOOD.ordinal] / n * worth
                        c.lostAll += lost[3 * (k - 1) + LOST_ALL] / n * worth
                        before += dk * length(k); dk *= fall(k)
                    }
                    val placeLost = max(0f, c.lostAll - c.lostKit - c.lostFood)
                    c.credit = if (c is WaysChoice) (1f - waysPlaceWeight) * placeLost else (1f - moverKitWeight) * c.lostKit
                }
                c.ret = sum / norm
                c.part = part / norm
                c.boot = own[boot - 1] / start * tail / norm
                if (fore != null) {
                    // (And by what its members were then foreseen to have a year ahead against what they were when it chose: see [shape].)
                    val from = outcomes.foreseen(c.groupId, c.week)
                    c.ret += shape * (fore[horizon - 1] / start - from)
                    c.part += shape * (fore[boot - 1] / start - from)
                }
                if (!c.ret.isFinite() || !c.part.isFinite() || !c.boot.isFinite()) { NonFinite.note(c.kind, "return"); continue }
                out += c
            }
            pending.clear(); pending += waiting
            checkRails()
            // Let go of what no waiting choice needs: the weeks before the oldest of them, and the groups gone before it.
            val oldest = pending.minOfOrNull { it.week } ?: week
            outcomes.cut(oldest, run.groups.groups.mapTo(HashSet()) { it.id })
            // (A choice whose group was not seen choosing when it was due keeps the whole horizon as seen.)
            for (waiting in probes.values) synchronized(waiting) { waiting.removeIf { val b = steps.reach(it.week, Critic.bootDays(it.kind)); b >= 0 && it.week + b < week } }
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
        // (People's camps are hand-written (people's net major 11): only the roles whose network chooses are checked; people's kinds
        // are left out of what is learned.)
        val roles = kinds.map { role(it) }.distinct().filter { it != Role.PEOPLE.ordinal }
        // (The runs go side by side: the hand-written one, and one for each role learned.)
        val runs = (listOf(-1) + roles).parallelStream().map { r ->
            r to if (r < 0) handScore(cfg, years, kinds) else score(cfg, years, kinds) { run ->
                val p = MoverPolicy(models[r], null, { 0f }, { 0 }); for (s in kinds) if (role(s) == r) run.groups.policies[s] = p
            }
        }.toList().toMap()
        val hand = runs.getValue(-1)
        // (Nor the kinds that live settled with roaming packs: the packs are a few of them, scored by the groups alone, [score].)
        return Versus(kinds.filter { role(it) in roles && it !in Catalogue.roamingResidents }.associateWith { s -> runs.getValue(role(s))[s] ?: 0.0 }, hand)
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
        repeat(years * run.stepsPerYear) { run.step(); score.afterStep(run) }
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

/** The greatest common divisor of [a] and [b]. */
private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
