package inflation.learning

import inflation.ecology.Catalogue
import inflation.ecology.HexFeatures
import inflation.ecology.Temperament
import inflation.ecology.HexGroup
import inflation.ecology.HexPolicy
import inflation.ecology.Species
import inflation.world.Date
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
        (if (temper == null) 0f else Temperament.pull(temper, features[3], features[25], features[10], features[2])) +
            // A herd's yearly round draws it on outside the network, as tradition does, so that learning does not unlearn it.
            inflation.ecology.ROUND_NET_PULL * features.getOrElse(51) { 0f }

    /** How many numbers of the band's own view ([Band.state]) it reads after the kind's part: [WAYS_IN] for people, 0 for animals. */
    val band get() = inputs - INPUTS

    /** Writes a candidate's whole input for kind [s]: its place features, the kind's part, the group's temperament [temper], and (people's) the band's view of itself, [state]. */
    fun fill(s: Species, features: FloatArray, state: FloatArray?, into: FloatArray, temper: FloatArray? = null) {
        features.copyInto(into, 0, 0, min(features.size, HexFeatures.SIZE))
        kindInputs(s, into, HexFeatures.SIZE)
        for (k in 0 until Temperament.AXES) into[HexFeatures.SIZE + KIND + IDENTITY + k] = temper?.getOrElse(k) { 0f } ?: 0f
        if (band > 0) { into.fill(0f, INPUTS, inputs); state?.copyInto(into, INPUTS, 0, min(state.size, band)) }
    }

    fun copy() = SharedMover(net.copy(), identity.copyOf())

    fun save(file: File) {
        Schema.save(net, file, if (band > 0) Schema.PEOPLE_IN else Schema.MOVER_IN, Schema.MOVER_OUT)
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
    /** What followed: the group's (and its offshoots') numbers over the next weeks against its numbers now, discounted. */
    var ret = 0f
    var advantage = 0f
}

/** Chooses with a [SharedMover], recording a share of its choices in [record] (null: the best, not sampled). */
class MoverPolicy(private val mover: SharedMover, private val record: ConcurrentLinkedQueue<Choice>?, private val rate: (Species) -> Float, private val week: () -> Int) : HexPolicy {
    private val buffers = ThreadLocal.withInitial { mover.net.buffers() }
    override fun choose(g: HexGroup, features: Array<FloatArray>, baseline: FloatArray, rnd: kotlin.random.Random): Int {
        val b = buffers.get()
        val n = features.size
        val state = g.band?.state; val temper = g.temper
        val scores = FloatArray(n) { k -> mover.fill(g.species, features[k], state, b.a[0], temper); mover.net.forward(b) + mover.residual * baseline[k] + mover.pull(temper, features[k]) }
        // Only the choices recorded to learn from are drawn by chance; every other group takes its best, so the
        // training worlds live as they would with the networks choosing (a world of chance moves goes under).
        if (record == null || rnd.nextFloat() >= rate(g.species)) { var best = 0; for (k in 1 until n) if (scores[k] > scores[best]) best = k; return best }
        val p = softmax(scores)
        var u = rnd.nextFloat(); var taken = n - 1
        for (k in 0 until n) { u -= p[k]; if (u <= 0f) { taken = k; break } }
        record += Choice(g.species, g.id, g.size, week(), Array(n) { k -> features[k].copyOf(HexFeatures.SIZE) }, taken, ln(max(p[taken], 1e-8f))).also { it.baseline = baseline.copyOf(); it.temper = temper.copyOf(); if (mover.band > 0) it.band = state?.copyOf() }
        return taken
    }
    override fun scores(g: HexGroup, features: Array<FloatArray>, baseline: FloatArray): FloatArray {
        val b = buffers.get()
        val state = g.band?.state; val temper = g.temper
        return FloatArray(features.size) { k -> mover.fill(g.species, features[k], state, b.a[0], temper); mover.net.forward(b) + mover.residual * baseline[k] + mover.pull(temper, features[k]) }
    }
}

internal fun softmax(x: FloatArray): FloatArray {
    var m = Float.NEGATIVE_INFINITY; for (v in x) m = max(m, v)
    val e = FloatArray(x.size) { exp(x[it] - m) }
    val s = e.sum()
    for (i in e.indices) e[i] /= s
    return e
}

/**
 * Learns where to go by proximal policy optimisation, from every recorded choice: one network for
 * the herds, one for the hunters (kinds within each told apart by their traits and a learned
 * identity), one for people. Each
 * round, fresh small worlds with all the animals and people run for a while with the current
 * networks choosing by chance as they score; a share of the choices of every kind is recorded (the
 * same number a year of each kind, however common it is), and what became of the group over the
 * following weeks is its return. Each kind is judged against its own average, and counts the same
 * in the update as every other kind, so the common herds do not drown out the rare hunters. Every
 * few rounds each role's network plays against the hand-written behaviour on fixed fresh worlds
 * (the other roles hand-written), reported per kind with its spread over the worlds; a kind that
 * does worse than with the last version it did well with keeps that version (in `deployed/`).
 *
 * Usage: hex-ppo [--out DIR] [--worlds N] [--size KM] [--years N] [--rounds N] [--hidden N] [--rate R] [--check N] [--check-seeds N] [--per-kind N] [--people-choices X] [--world-rounds N] [--no-overlap] [--burn-in YEARS] [--hold ROLE[,ROLE]:ROUNDS] [--teacher DIR] [--teacher-check] [--maps SEED:LAT,...] [--check-on-maps]
 */
object HexPpo {
    /** The index of [s]'s role's network in a list of them ([Role]). */
    fun role(s: Species) = Role.of(s).ordinal

    const val HORIZON = 26
    const val DISCOUNT = 0.95f
    /**
     * Weeks over which a band's choice is judged: a season. What a move does to its band shows within
     * about 8 to 13 weeks, while the drift every other band's choices cause grows on through the year
     * (at 52 weeks it was as large as the spread between bands; see tools/HexChoiceEffect).
     */
    const val PEOPLE_HORIZON = 13
    const val PEOPLE_DISCOUNT = 0.98f
    const val CLIP = 0.2f
    /**
     * Parts each batch of learning is split into, to be worked on all cores (with 16 parts, as
     * before, most of a big machine stood idle while learning); the same on every machine, so that
     * a run's sums come out the same.
     */
    const val PARTS = 128
    const val ENTROPY = 0.01f

    @JvmStatic
    fun main(args: Array<String>) {
        val o = PpoOptions.parse(args)
        o.out.mkdirs()
        // Only how the teachers do against the hand-written behaviour, in today's world.
        if (o.teacherCheck) {
            val kinds = Catalogue.learned
            val seeds = (91L until 91L + o.checkSeeds).toList()
            val scores = seeds.parallelStream().map { seed -> teacherCheck(checkWorld(seed, o.size, o.checkOnMaps, o.maps), o.years + 1, kinds, o.teachers) }.toList()
            println("teachers vs hand-written over ${seeds.size} worlds: " + kinds.filter { it in o.teachers }.joinToString(", ") { s -> s.name + " " + compare(scores.map { it.first[s] ?: 0.0 }, scores.map { it.second[s] ?: 0.0 }) })
            return
        }
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
     * (the softmax of their scores matching that of the hand-written scores, sharpened). Where a kind
     * has one of [teachers], it is copied instead of the hand-written behaviour.
     */
    fun imitate(kinds: List<Species>, models: List<SharedMover>, worlds: Int, size: Int, sampling: Sampling, teachers: Map<Species, Mlp>, maps: Maps) {
        val shown = (0 until worlds).toList().parallelStream().map { w ->
            val run = HexLearning.Run(maps.world(40_000L + w, size, w), kinds)
            val kept = ConcurrentLinkedQueue<Shown>()
            val stepsPerYear = Date.DAYS_PER_YEAR / (WorldConfig().daysPerStep)
            val rates = kinds.associateWith { s -> val n = run.groups.groups.count { it.alive && it.species == s }; if (n == 0) 0f else min(1f, sampling.perKindYear.toFloat() / (n * stepsPerYear)) }
            val teachersHere = teachers.mapValues { (_, net) -> ThreadLocal.withInitial { net.buffers() } to net }
            for (s in kinds) run.groups.policies[s] = HexPolicy { g, f, b0, rnd ->
                // A teacher network, where there is one for the kind, in place of the hand-written scores.
                val b = teachersHere[g.species]?.let { (bufs, net) -> val bb = bufs.get(); FloatArray(f.size) { k -> f[k].copyInto(bb.a[0], 0, 0, min(f[k].size, bb.a[0].size)); net.forward(bb) } } ?: b0
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
                        val gw = FloatArray(model.net.w.size); val gi = FloatArray(model.identity.size); val dIn = FloatArray(model.inputs)
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
                                model.net.backward(bufs[k], dOut, gw, dIn)
                                for (e in 0 until SharedMover.IDENTITY) gi[c.kind.index * SharedMover.IDENTITY + e] += dIn[HexFeatures.SIZE + SharedMover.KIND + e]
                            }
                        }
                        Triple(gw, gi, l)
                    }
                    val gw = FloatArray(model.net.w.size); val gi = FloatArray(model.identity.size)
                    for ((a, b, l) in parts) { gw.add(a); gi.add(b); loss += l }
                    val scale = mine.size.toFloat() / batch.size
                    for (i in gw.indices) gw[i] *= scale; for (i in gi.indices) gi[i] *= scale
                    adam.step(model.net.w, gw); adamId.step(model.identity, gi)
                }
                println("imitating, %s, epoch %d: loss %.4f on %d choices".format(Role.entries[mi], epoch, loss, mine.size))
            }
        }
    }

    /** How much sharper the copied preference is than the hand-written scores themselves. */
    const val IMITATE_SHARPNESS = 10f

    /** One world for [years] with the networks choosing by chance; the recorded choices with their returns. */
    fun play(cfg: WorldConfig, years: Int, kinds: List<Species>, models: List<SharedMover>): Pair<List<Choice>, String> {
        val steps = years * Date.DAYS_PER_YEAR / cfg.daysPerStep
        return Player(cfg, kinds, steps).play(steps, models, null) to "seed ${cfg.seed}"
    }

    /**
     * A training world that lives on from round to round (for up to [lifeSteps] weeks), so that no
     * round's play is spent setting up a world, and the choices whose outcome runs past a round's end
     * are judged in the next round instead of being dropped. Choices keep the probability they were
     * made with, so being a round old does not mislead the clipped update.
     */
    class Player(val cfg: WorldConfig, private val kinds: List<Species>, private val lifeSteps: Int, private val burnSteps: Int = 0, private val sampling: Sampling = Sampling()) {
        private val run = HexLearning.Run(cfg, kinds).also {
            // (Kinds that dwindle get newcomers, so every kind stays in the data; checks run without them.)
            it.groups.newcomersOn = true; it.fauna.newcomersOn = true
        }
        private var week = 0
        private val record = ConcurrentLinkedQueue<Choice>()
        /** Choices whose outcome is not yet all seen. */
        private val pending = ArrayList<Choice>()
        private val stepsPerYear = Date.DAYS_PER_YEAR / cfg.daysPerStep
        // The same number of choices a year of each kind, however many groups it has.
        private val rates = HashMap<Species, Float>()
        private var waysRate = 0f
        /** What became of each group's members, to judge each choice by. */
        private val outcomes = Outcomes()
        /** Whether it has lived its time (if it has one), or gone badly off the rails: then a fresh world takes its place. */
        val done get() = (lifeSteps > 0 && week >= lifeSteps) || derailed != null
        /**
         * Why it has gone off the rails, if it has (else null): no bands left, herds or hunters down
         * to a tenth of their numbers at the start, herds, hunters or people grown tenfold, or half the
         * kinds gone. Short of that it lives
         * on, whatever befalls it: the networks are to learn in worlds that have run long.
         */
        var derailed: String? = null; private set
        private val startNumbers = HashMap<Int, Int>()
        private fun numbersByRole(): Map<Int, Int> = run.groups.groups.filter { it.alive }.groupingBy { role(it.species) }.fold(0) { a, g -> a + g.size }
        private fun checkRails() {
            val now = numbersByRole()
            if (startNumbers.isEmpty()) { startNumbers.putAll(now); return }
            val gone = kinds.count { s -> run.groups.groups.none { it.alive && it.species == s } }
            derailed = when {
                (startNumbers[2] ?: 0) > 0 && (now[2] ?: 0) == 0 -> "no bands left"
                (0..1).any { r -> (now[r] ?: 0) < 0.1 * (startNumbers[r] ?: 0) } -> "herds or hunters down to a tenth"
                (0..2).any { r -> (now[r] ?: 0) > 10.0 * (startNumbers[r] ?: Int.MAX_VALUE / 20) } -> "a role grown tenfold"
                gone * 2 > kinds.size -> "$gone of ${kinds.size} kinds gone"
                else -> null
            }
        }

        // (People, the one kind of their role, get [Sampling.peopleChoices] times as many as each animal kind.)
        private fun setRates() {
            // (Nothing is recorded while a fresh world settles.)
            if (week < burnSteps) { for (s in kinds) rates[s] = 0f; waysRate = 0f; return }
            for (s in kinds) { val n = run.groups.groups.count { it.alive && it.species == s }; rates[s] = if (n == 0) 0f else min(1f, sampling.perKindYear * (if (s.human) sampling.peopleChoices else 1f) / (n * stepsPerYear)) }
            waysRate = WaysPpo.rate(run.groups.groups.count { it.alive && it.band != null }, stepsPerYear)
        }

        /** [steps] more weeks with [models] choosing (and the bands' ways drawn from [ways], else the scaffold's); the choices whose outcome is now all seen, with their returns. */
        fun play(steps: Int, models: List<SharedMover>, ways: LearnedWays?): List<Choice> {
            if (week == 0) { setRates(); checkRails() }
            // A fresh world first settles for [burnSteps] weeks, its choices unrecorded (they come on top of this round's).
            val total = steps + if (week == 0) burnSteps else 0
            val policies = models.map { MoverPolicy(it, record, { s -> rates[s] ?: 0f }, { week }) }
            for (s in kinds) run.groups.policies[s] = policies[role(s)]
            ways?.let { w -> run.groups.ways = WaysSampler(w, record, { waysRate }, { week }, cfg.seed) }
            repeat(if (lifeSteps > 0) min(total, lifeSteps - week) else total) {
                run.step()
                week++
                if (week % stepsPerYear == 0) setRates()
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
                for (k in 1..horizon) { sum += d * own[k - 1] / c.size.coerceAtLeast(1).toFloat(); norm += d; d *= discount }
                c.ret = sum / norm
                out += c
            }
            pending.clear(); pending += waiting
            checkRails()
            // Let go of what no waiting choice needs: the weeks before the oldest of them, and the groups gone before it.
            val oldest = pending.minOfOrNull { it.week } ?: week
            outcomes.cut(oldest, run.groups.groups.mapTo(HashSet()) { it.id })
            return out
        }
    }

    /**
     * How the kinds do in [cfg] over [years] (animal-weeks per animal at the start) with each role's
     * network choosing alone, the other roles hand-written, against all hand-written: for each kind,
     * its score in the run where its own role was learned, and in the hand-written run.
     */
    fun check(cfg: WorldConfig, years: Int, kinds: List<Species>, models: List<SharedMover>): Pair<Map<Species, Double>, Map<Species, Double>> {
        val roles = kinds.map { role(it) }.distinct()
        // (The runs go side by side: the hand-written one, and one for each role learned.)
        val runs = (listOf(-1) + roles).parallelStream().map { r ->
            r to if (r < 0) handScore(cfg, years, kinds) else score(cfg, years, kinds) { run ->
                val p = MoverPolicy(models[r], null, { 0f }, { 0 }); for (s in kinds) if (role(s) == r) run.groups.policies[s] = p
            }
        }.toList().toMap()
        val hand = runs.getValue(-1)
        return kinds.associateWith { s -> runs.getValue(role(s))[s] ?: 0.0 } to hand
    }

    /** The [teachers] (every kind that has one) against the hand-written behaviour in [cfg]. */
    fun teacherCheck(cfg: WorldConfig, years: Int, kinds: List<Species>, teachers: Map<Species, Mlp>): Pair<Map<Species, Double>, Map<Species, Double>> {
        val both = listOf(true, false).parallelStream().map { t ->
            score(cfg, years, kinds) { run -> if (t) for ((s, net) in teachers) if (s in kinds) run.groups.policies[s] = HexLearning.NetPolicy(net) }
        }.toList()
        return both[0] to both[1]
    }

    /**
     * [score] with everything hand-written, worked out once for each world and kept: it is the same
     * every check (the runs are reproducible), and it was a quarter of each check's work.
     */
    internal fun handScore(cfg: WorldConfig, years: Int, kinds: List<Species>): Map<Species, Double> =
        handScores.computeIfAbsent(Triple(cfg, years, kinds.map { it.name })) { score(cfg, years, kinds) { } }
    private val handScores = ConcurrentHashMap<Triple<WorldConfig, Int, List<String>>, Map<Species, Double>>()

    /**
     * Animal-weeks per animal at the start, by kind, over [years] in [cfg] with the policies [setUp]
     * installs; for people, less their luck in their own births and deaths (see [HexGroup.luck]), so
     * that a small gain shows through the chance of who was born and who died.
     */
    internal fun score(cfg: WorldConfig, years: Int, kinds: List<Species>, setUp: (HexLearning.Run) -> Unit): Map<Species, Double> {
        val run = HexLearning.Run(cfg, kinds)
        setUp(run)
        val start = kinds.associateWith { run.groups.count(it).toDouble() }
        val weeks = HashMap<Species, Double>()
        val steps = years * Date.DAYS_PER_YEAR / cfg.daysPerStep
        val lastLuck = HashMap<Int, Float>(); val luck = HashMap<Species, Double>()
        repeat(steps) {
            run.step()
            for (g in run.groups.groups) {
                if (g.species.human) { luck.merge(g.species, (g.luck - (lastLuck[g.id] ?: 0f)).toDouble(), Double::plus); lastLuck[g.id] = g.luck }
                if (g.alive) weeks.merge(g.species, g.size.toDouble(), Double::plus)
            }
            for ((s, l) in luck) weeks.merge(s, -l, Double::plus)
        }
        return kinds.associateWith { s -> val st = start.getValue(s); if (st <= 0.0) 0.0 else (weeks[s] ?: 0.0) / st / steps }
    }
}

/**
 * A critic: from where a group stands (its own place's features and its kind), the return it can be
 * expected to make whatever it does, learned by least squares on the returns seen. Choices are then
 * judged by how much better than that they turned out.
 */
class Critic(val net: Mlp, rate: Float = 1e-3f) {
    private val adam = Adam(net.w.size, rate)
    private val buffers = ThreadLocal.withInitial { net.buffers() }

    /** Where [c]'s group stood: the candidate that is staying put (else the first). */
    private fun here(c: Choice) = c.inputs.indices.firstOrNull { c.inputs[it].getOrElse(HexFeatures.STAYS) { 0f } > 0.5f } ?: 0

    fun value(model: SharedMover, c: Choice): Float {
        val b = buffers.get()
        model.fill(c.kind, c.inputs[here(c)], c.band, b.a[0], c.temper)
        return net.forward(b)
    }

    /** A few passes of least squares toward the returns seen this round. */
    fun learn(model: SharedMover, cs: List<Choice>, round: Int) {
        repeat(3) { epoch ->
            for (batch in cs.shuffled(kotlin.random.Random(round * 17L + epoch)).chunked(1024)) {
                val parts = inParts(batch) { part ->
                    val g = FloatArray(net.w.size); val b = net.buffers()
                    for (c in part) {
                        model.fill(c.kind, c.inputs[here(c)], c.band, b.a[0], c.temper)
                        val v = net.forward(b)
                        net.backward(b, 2f * (v - c.ret) / batch.size, g, null)
                    }
                    g
                }
                val g = FloatArray(net.w.size); for (p in parts) g.add(p)
                adam.step(net.w, g)
            }
        }
    }

    fun save(f: File) = Schema.save(net, f, if (net.sizes[0] > SharedMover.INPUTS) Schema.PEOPLE_IN else Schema.MOVER_IN, listOf("value"))

    companion object {
        /** The critic in [f], or a new one, reading what [model] reads (for people, the band's view of itself too). */
        fun loadOrNew(f: File, hidden: Int, model: SharedMover): Critic {
            val names = if (model.band > 0) Schema.PEOPLE_IN else Schema.MOVER_IN
            return Critic(if (f.exists()) Schema.load(f, names, listOf("value")) else
                Mlp.random(intArrayOf(model.inputs, hidden, 1), kotlin.random.Random(91)).also { m -> m.w[m.w.size - 1] = 1f })
        }
    }
}
