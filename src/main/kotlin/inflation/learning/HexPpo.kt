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
        if (temper == null) 0f else Temperament.pull(temper, features[3], features[25], features[10], features[2])

    /** How many numbers of the band's own view ([inflation.ecology.Band.state]) it reads after the kind's part: [WAYS_IN] for people, 0 for animals. */
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
    /** A band's view of itself when it chose ([inflation.ecology.Band.state]); null for animals. */
    var band: FloatArray? = null
    /** The group's temperament when it chose ([inflation.ecology.HexGroup.temper]). */
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
        if (record == null) { var best = 0; for (k in 1 until n) if (scores[k] > scores[best]) best = k; return best }
        val p = softmax(scores)
        var u = rnd.nextFloat(); var taken = n - 1
        for (k in 0 until n) { u -= p[k]; if (u <= 0f) { taken = k; break } }
        if (rnd.nextFloat() < rate(g.species)) record += Choice(g.species, g.id, g.size, week(), Array(n) { k -> features[k].copyOf(HexFeatures.SIZE) }, taken, ln(max(p[taken], 1e-8f))).also { it.baseline = baseline.copyOf(); it.temper = temper.copyOf(); if (mover.band > 0) it.band = state?.copyOf() }
        return taken
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
 * Usage: hex-ppo [--out DIR] [--worlds N] [--size KM] [--years N] [--rounds N] [--hidden N] [--rate R] [--check N] [--check-seeds N] [--per-kind N] [--people-choices X] [--world-rounds N] [--no-overlap] [--burn-in YEARS] [--teacher DIR] [--teacher-check] [--maps SEED:LAT,...] [--check-on-maps]
 */
object HexPpo {
    /**
     * The networks, by role: one for the herds, one for the hunters and one for people. (Prey and
     * hunters learn apart, so that what makes a hunter better and what makes its prey better do
     * not pull at the same weights; within a role, kinds share what they learn.)
     */
    val ROLES = listOf("herds", "hunters", "people")
    fun role(s: Species) = if (s.human) 2 else if (s.carnivore) 1 else 0

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
    /** How many times as many choices a year are recorded of people as of each animal kind (one kind against fourteen). */
    var PEOPLE_CHOICES = 8f
    /** Choices recorded a year for each kind in each world. */
    var PER_KIND_YEAR = 1500

    @JvmStatic
    fun main(args: Array<String>) {
        var out = File("hex-ppo")
        var worlds = 8
        var size = 600
        var years = 2
        var rounds = 1000
        var hidden = 48
        var rate = 3e-4f
        var checkEvery = 5
        var checkSeeds = 12
        var checkOnMaps = false
        var teacherCheck = false
        var worldRounds = 0
        var overlap = true
        var burnYears = 1
        var learnWays = true
        var k = 0
        while (k < args.size) {
            when (args[k]) {
                "--out" -> out = File(args[++k])
                "--worlds" -> worlds = args[++k].toInt()
                "--size" -> size = args[++k].toInt()
                "--years" -> years = args[++k].toInt()
                "--rounds" -> rounds = args[++k].toInt()
                "--hidden" -> hidden = args[++k].toInt()
                "--rate" -> rate = args[++k].toFloat()
                // --residual people:3,herds:0 sets how much of the hand-written score each role's network starts from.
                "--residual" -> RESIDUAL = args[++k].split(",").associate { r -> val (n, v) = r.split(":"); n to v.toFloat() }
                "--check" -> checkEvery = args[++k].toInt()
                "--check-seeds" -> checkSeeds = args[++k].toInt()
                // Check on the training maps (--maps) instead of fresh worlds: how well they are fitted, not how well the networks do elsewhere.
                "--check-on-maps" -> checkOnMaps = true
                "--per-kind" -> PER_KIND_YEAR = args[++k].toInt()
                // How many rounds a training world lives before a fresh one takes its place (0, the default: until it goes
                // badly off the rails, so that the networks learn in worlds that have run long, where what bands and herds
                // did compounds; 1: fresh every round, as before).
                "--world-rounds" -> worldRounds = args[++k].toInt()
                // Play each round only after the last one has learnt (slower; each round's choices made by the networks just learnt).
                "--no-overlap" -> overlap = false
                // Years a fresh training world runs, with the networks choosing, before any choice is recorded (its first state is not like a lived-in world's).
                "--burn-in" -> burnYears = args[++k].toInt()
                "--people-choices" -> PEOPLE_CHOICES = args[++k].toFloat()
                "--teacher" -> { val dir = File(args[++k]); TEACHERS = Catalogue.all.mapNotNull { s -> HexLearning.file(dir, s).takeIf { it.exists() }?.let { s to Schema.load(it, Schema.TEACHER_IN, Schema.MOVER_OUT) } }.toMap() }
                "--teacher-check" -> teacherCheck = true
                // Bands' ways stay the scaffold's (or as evolved) instead of being learned beside their moves.
                "--no-ways" -> learnWays = false
                "--maps" -> MAPS = args[++k].split(",").map { m -> val (s, l) = m.split(":"); s.toLong() to l.toDouble() }
                else -> error("unknown argument ${args[k]}")
            }
            k++
        }
        out.mkdirs()
        val kinds = Catalogue.learned
        val kindsAll = Catalogue.learned
        // Only how the teachers do against the hand-written behaviour, in today's world.
        if (teacherCheck) {
            val seeds = (91L until 91L + checkSeeds).toList()
            val scores = seeds.parallelStream().map { seed -> teacherCheck(checkWorld(seed, size, checkOnMaps), years + 1, kindsAll) }.toList()
            println("teachers vs hand-written over ${seeds.size} worlds: " + kindsAll.filter { it in TEACHERS }.joinToString(", ") { s -> s.name + " " + compare(scores.map { it.first[s] ?: 0.0 }, scores.map { it.second[s] ?: 0.0 }) })
            return
        }
        val files = ROLES.map { File(out, "$it.mlp") }
        val models = files.mapIndexed { i, f -> if (f.exists()) SharedMover.load(f) else SharedMover.random(hidden, Random(7L + i), if (ROLES[i] == "people") inflation.ecology.WAYS_IN else 0) }
        for ((i, r) in ROLES.withIndex()) RESIDUAL[r]?.let { models[i].residual = it }
        fun modelOf(s: Species) = models[role(s)]
        val adams = models.map { Adam(it.net.w.size, rate) to Adam(it.identity.size, rate) }
        // One critic for each role: what a group's return can be expected to be where it stands.
        val critics = ROLES.mapIndexed { i, r -> Critic.loadOrNew(File(out, "$r.value.mlp"), hidden, models[i]) }
        // Bands' ways, learned beside their moves (see [WaysPpo]).
        val waysState = if (learnWays) WaysPpo.State(out, hidden, rate) else null
        WAYS = waysState?.ways
        // A fresh network first learns to choose as the hand-written behaviour does, so learning starts from it.
        if (!files[0].exists()) {
            imitate(kinds, models, worlds, size)
            for (i in models.indices) models[i].save(files[i])
        }
        // The versions each kind plays with outside training: its last good one.
        val deployedDir = File(out, "deployed").also { it.mkdirs() }
        val best = HashMap<Species, Double>()
        File(deployedDir, "scores.txt").takeIf { it.exists() }?.readLines()?.forEach { l -> val (n, v) = l.split("\t"); kinds.firstOrNull { it.name == n }?.let { best[it] = v.toDouble() } }
        // The training worlds, living on from round to round (for [worldRounds] rounds, if set, then a fresh one takes its place).
        val players = arrayOfNulls<Player>(worlds)
        val steps = years * Date.DAYS_PER_YEAR / WorldConfig().daysPerStep
        fun playRound(r: Int, ms: List<SharedMover>, ways: LearnedWays?): List<List<Choice>> {
            val fresh = (0 until worlds).filter { w -> players[w]?.done != false }
            for (w in fresh) players[w]?.derailed?.let { why -> println("world $w replaced after round ${r - 1}: $why"); File(out, "log.txt").appendText("world $w replaced after round ${r - 1}: $why\n") }
            // (New worlds are made side by side, as each round's fresh worlds were before they lived on.)
            fresh.parallelStream().map { w -> w to Player(world(50_000L + r * 100L + w, size, w), kinds, steps * worldRounds, burnYears * Date.DAYS_PER_YEAR / WorldConfig().daysPerStep) }
                .toList().forEach { (w, p) -> players[w] = p }
            return (0 until worlds).toList().parallelStream().map { w -> players[w]!!.play(steps, ms, ways) }.toList()
        }
        var ahead: java.util.concurrent.CompletableFuture<List<List<Choice>>>? = null
        val log = File(out, "log.txt")
        val firstRound = if (log.exists()) log.readLines().count { it.startsWith("round ") } else 0
        for (round in firstRound until firstRound + rounds) {
            val t0 = System.nanoTime()
            // ---- play
            val choices = ahead?.join() ?: playRound(round, models, waysState?.ways)
            val tPlay = System.nanoTime()
            // The next round's play goes on beside this round's learning and check, with the networks as
            // they are now (a round behind when it is learnt from: each choice keeps the probability it
            // was made with, and the clipped update allows for it).
            ahead = if (overlap && round + 1 < firstRound + rounds) {
                val ms = models.map { m -> SharedMover(m.net.copy(), m.identity.copyOf()).also { it.residual = m.residual } }
                val wy = waysState?.ways?.copy()
                java.util.concurrent.CompletableFuture.supplyAsync { playRound(round + 1, ms, wy) }
            } else null
            val everything = choices.flatten()
            val waysChoices = everything.filterIsInstance<WaysChoice>()
            val all = everything.filter { it !is WaysChoice }
            // ---- judge each choice against what its group could expect where it stood (the critic's
            // estimate), not only against its kind's average: much of what befalls a group is its
            // situation and its luck, not the move; then scaled within its kind.
            val byKind = all.groupBy { it.kind }
            val expected = HashMap<Choice, Float>()
            for ((mi, model) in models.withIndex()) {
                val mine = all.filter { role(it.kind) == mi }
                if (mine.isEmpty()) continue
                val critic = critics[mi]
                for (c in mine) expected[c] = critic.value(model, c)
                critic.learn(model, mine, round)
            }
            for ((_, cs) in byKind) {
                val mean = cs.map { it.ret.toDouble() }.average().toFloat()
                val gap = cs.associateWith { c -> c.ret - (expected[c] ?: mean) }
                val sd = sqrt(gap.values.map { (it * it).toDouble() }.average()).toFloat().coerceAtLeast(1e-3f)
                for (c in cs) c.advantage = gap.getValue(c) / sd
            }
            // ---- learn: a few passes of clipped policy gradient, every kind weighing the same
            val stats = StringBuilder()
            for ((mi, model) in models.withIndex()) {
                val mine = all.filter { role(it.kind) == mi }
                if (mine.isEmpty()) continue
                val kindsHere = mine.map { it.kind }.distinct()
                val weight = mine.associateWith { c -> 1f / (kindsHere.size * byKind.getValue(c.kind).size) }
                repeat(4) { epoch ->
                    for (batch in mine.shuffled(Random(round * 31L + epoch)).chunked(512)) {
                        val parts = batch.chunked((batch.size + PARTS - 1) / PARTS).parallelStream().map { part -> gradient(model, part, weight) }.toList()
                        val gw = FloatArray(model.net.w.size); val gi = FloatArray(model.identity.size)
                        for ((a, bb) in parts) { for (i in gw.indices) gw[i] += a[i]; for (i in gi.indices) gi[i] += bb[i] }
                        // (Each batch is a share of the whole, so its weights are scaled up to stand for it.)
                        val scale = mine.size.toFloat() / batch.size
                        for (i in gw.indices) gw[i] *= scale; for (i in gi.indices) gi[i] *= scale
                        adams[mi].first.step(model.net.w, gw); adams[mi].second.step(model.identity, gi)
                    }
                }
                model.save(files[mi]); critics[mi].save(File(out, "${ROLES[mi]}.value.mlp"))
                stats.append(ROLES[mi]).append(' ').append(mine.size).append(" choices; ")
            }
            waysState?.let { stats.append(WaysPpo.learn(it, waysChoices, round)).append("; "); it.save() }
            val returns = byKind.entries.sortedBy { it.key.name }.joinToString(", ") { (s, cs) -> "%s %.3f".format(s.name, cs.map { it.ret.toDouble() }.average()) }
            val line = "round %d  %.0f s (playing %.0f s)  %s mean return: %s".format(round, (System.nanoTime() - t0) / 1e9, (tPlay - t0) / 1e9, stats, returns)
            println(line); log.appendText(line + "\n")
            // ---- check against the hand-written behaviour, one role at a time (the others hand-written,
            // so a role's gain is its own and not the others'), on worlds it has not trained on; and keep
            // each kind's last good version
            if (round % checkEvery == checkEvery - 1) {
                // (The next round's play finishes first: its worlds and the check's would not all fit in memory at once.)
                ahead?.join()
                val seeds = (91L until 91L + checkSeeds).toList()
                val scores = seeds.parallelStream().map { seed -> check(checkWorld(seed, size, checkOnMaps), years + 1, kinds, models) }.toList()
                val parts = ArrayList<String>()
                for (s in kinds) {
                    val learnedEach = scores.map { it.first[s] ?: 0.0 }
                    val handEach = scores.map { it.second[s] ?: 0.0 }
                    val learned = learnedEach.average()
                    val keep = learned >= (best[s] ?: Double.NEGATIVE_INFINITY)
                    if (keep) { best[s] = learned; modelOf(s).save(File(deployedDir, s.name.replace(' ', '-') + ".mlp")) }
                    parts += s.name + " " + compare(learnedEach, handEach) + if (keep) "" else " (kept older)"
                }
                File(deployedDir, "scores.txt").writeText(best.entries.joinToString("\n") { "${it.key.name}\t${it.value}" } + "\n")
                val c = "check round %d (each role learned alone vs all hand-written, animal-weeks per animal, %d %s worlds: mean change +- its standard error, worlds better): %s".format(round + 1, seeds.size, if (checkOnMaps && MAPS.isNotEmpty()) "training" else "fresh", parts.joinToString(", "))
                println(c); File(out, "check.txt").appendText(c + "\n")
                // Bands' learned ways (their middles) against the scaffold's, everything else hand-written.
                waysState?.let { st ->
                    val w = seeds.parallelStream().map { seed -> WaysPpo.check(checkWorld(seed, size, checkOnMaps), years + 1, kinds, st.ways) }.toList()
                    val cw = "check round %d ways (learned vs scaffold, people-weeks per person): %s".format(round + 1, compare(w.map { it.first }, w.map { it.second }))
                    println(cw); File(out, "check.txt").appendText(cw + "\n")
                }
            }
        }
    }

    /** A world to check on: a fresh one for [seed] (never a training map unless [onMaps]). */
    fun checkWorld(seed: Long, size: Int, onMaps: Boolean): WorldConfig =
        if (onMaps && MAPS.isNotEmpty()) world(seed, size, (seed - 91L).toInt()) else WorldConfig(seed = seed, widthKm = size, heightKm = size)

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
    fun imitate(kinds: List<Species>, models: List<SharedMover>, worlds: Int, size: Int) {
        val shown = (0 until worlds).toList().parallelStream().map { w ->
            val run = HexLearning.Run(world(40_000L + w, size, w), kinds)
            val kept = ConcurrentLinkedQueue<Shown>()
            val stepsPerYear = Date.DAYS_PER_YEAR / (WorldConfig().daysPerStep)
            val rates = kinds.associateWith { s -> val n = run.groups.groups.count { it.alive && it.species == s }; if (n == 0) 0f else min(1f, PER_KIND_YEAR.toFloat() / (n * stepsPerYear)) }
            val teachers = TEACHERS.mapValues { (_, net) -> ThreadLocal.withInitial { net.buffers() } to net }
            for (s in kinds) run.groups.policies[s] = HexPolicy { g, f, b0, rnd ->
                // A teacher network, where there is one for the kind, in place of the hand-written scores.
                val b = teachers[g.species]?.let { (bufs, net) -> val bb = bufs.get(); FloatArray(f.size) { k -> f[k].copyInto(bb.a[0], 0, 0, min(f[k].size, bb.a[0].size)); net.forward(bb) } } ?: b0
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
                    val parts = batch.chunked((batch.size + PARTS - 1) / PARTS).parallelStream().map { part ->
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
                    }.toList()
                    val gw = FloatArray(model.net.w.size); val gi = FloatArray(model.identity.size)
                    for ((a, b, l) in parts) { for (i in gw.indices) gw[i] += a[i]; for (i in gi.indices) gi[i] += b[i]; loss += l }
                    val scale = mine.size.toFloat() / batch.size
                    for (i in gw.indices) gw[i] *= scale; for (i in gi.indices) gi[i] *= scale
                    adam.step(model.net.w, gw); adamId.step(model.identity, gi)
                }
                println("imitating, %s, epoch %d: loss %.4f on %d choices".format(ROLES[mi], epoch, loss, mine.size))
            }
        }
    }

    /**
     * Hand-picked maps (seed and centre latitude) to play and check on, in turn (e.g. those
     * hex-pick-world finds push herds hardest to migrate); empty for fresh worlds every round.
     */
    var MAPS: List<Pair<Long, Double>> = emptyList()
    fun world(seed: Long, size: Int, i: Int = 0): WorldConfig =
        if (MAPS.isEmpty()) WorldConfig(seed = seed, widthKm = size, heightKm = size)
        else MAPS[((i % MAPS.size) + MAPS.size) % MAPS.size].let { (s, lat) -> WorldConfig(seed = s, widthKm = size, heightKm = size, centreLatitude = lat) }

    /** Networks to copy instead of the hand-written behaviour, by kind (e.g. the herds' evolved ones). */
    var TEACHERS: Map<Species, Mlp> = emptyMap()
    /** Bands' ways to draw from while playing (null: the scaffold's). */
    var WAYS: LearnedWays? = null
    var RESIDUAL: Map<String, Float> = emptyMap()

    /** How much sharper the copied preference is than the hand-written scores themselves. */
    const val IMITATE_SHARPNESS = 10f

    /** One world for [years] with the networks choosing by chance; the recorded choices with their returns. */
    fun play(cfg: WorldConfig, years: Int, kinds: List<Species>, models: List<SharedMover>): Pair<List<Choice>, String> {
        val steps = years * Date.DAYS_PER_YEAR / cfg.daysPerStep
        return Player(cfg, kinds, steps).play(steps, models) to "seed ${cfg.seed}"
    }

    /**
     * A training world that lives on from round to round (for up to [lifeSteps] weeks), so that no
     * round's play is spent setting up a world, and the choices whose outcome runs past a round's end
     * are judged in the next round instead of being dropped. Choices keep the probability they were
     * made with, so being a round old does not mislead the clipped update.
     */
    class Player(val cfg: WorldConfig, private val kinds: List<Species>, private val lifeSteps: Int, private val burnSteps: Int = 0) {
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
        /**
         * A group's numbers week by week from week [base] (and for bands, their luck in their own
         * births and deaths, see [HexGroup.luck]); the weeks no choice still waiting needs are let go,
         * so a world can live on for ever.
         */
        private class Track(var base: Int) {
            var size = IntArray(64); var luck = FloatArray(64) { Float.NaN }; var n = 0
            fun set(w: Int, s: Int, l: Float) {
                val i = w - base
                if (i >= size.size) { val m = max(2 * size.size, i + 1); size = size.copyOf(m); val old = luck.size; luck = luck.copyOf(m); for (k in old until m) luck[k] = Float.NaN }
                size[i] = s; luck[i] = l; n = max(n, i + 1)
            }
            fun size(w: Int) = (w - base).let { if (it in 0 until n) size[it] else 0 }
            // (As last seen, once it is gone.)
            fun luck(w: Int): Float { var k = min(w - base, n - 1); while (k > 0 && luck[k].isNaN()) k--; return if (k < 0 || luck[k].isNaN()) 0f else luck[k] }
            /** Lets go of the weeks before [w]. */
            fun cut(w: Int) {
                val d = w - base; if (d <= 0) return
                val keep = luck(w - 1)
                if (d >= n) { n = 0 } else { size = size.copyOfRange(d, size.size); luck = luck.copyOfRange(d, luck.size); n -= d; if (luck[0].isNaN()) luck[0] = keep }
                base = w
            }
        }
        // Each group's numbers, and where its offshoots came from, to follow each choice's outcome.
        private val tracks = HashMap<Int, Track>()
        private val parent = HashMap<Int, Int>()
        private val merged = HashMap<Int, Int>()
        private val born = HashMap<Int, Int>()
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

        // (People, the one kind of their role, get [PEOPLE_CHOICES] times as many as each animal kind.)
        private fun setRates() {
            // (Nothing is recorded while a fresh world settles.)
            if (week < burnSteps) { for (s in kinds) rates[s] = 0f; waysRate = 0f; return }
            for (s in kinds) { val n = run.groups.groups.count { it.alive && it.species == s }; rates[s] = if (n == 0) 0f else min(1f, PER_KIND_YEAR * (if (s.human) PEOPLE_CHOICES else 1f) / (n * stepsPerYear)) }
            waysRate = WaysPpo.rate(run.groups.groups.count { it.alive && it.band != null }, stepsPerYear)
        }

        /** [steps] more weeks with [models] choosing (and the bands' ways drawn from [WAYS]); the choices whose outcome is now all seen, with their returns. */
        fun play(steps: Int, models: List<SharedMover>, ways: LearnedWays? = WAYS): List<Choice> {
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
                for (g in run.groups.groups) {
                    if (g.parent >= 0 && g.id !in parent) { parent[g.id] = g.parent; born[g.id] = week }
                    if (g.mergedSize >= 0) merged.putIfAbsent(g.id, g.mergedSize)
                    tracks.getOrPut(g.id) { Track(week) }.set(week, if (g.alive) g.size else 0, if (g.species.human) g.luck else 0f)
                }
                // Groups that merged into another this week are gone from the list by now: their numbers carry on as at the merge.
                for (g in run.groups.merged) merged.putIfAbsent(g.id, g.mergedSize)
            }
            // Offshoots of each group, for adding their numbers to its own.
            val children = HashMap<Int, MutableList<Int>>()
            for ((c, p) in parent) children.getOrPut(p) { ArrayList() } += c
            // A group's numbers (and its offshoots') at week [w]; for bands, less their luck in births and
            // deaths since week [from], so that a choice is judged by what it made likely, not by chance.
            fun numbers(id: Int, w: Int, from: Int, depth: Int = 0): Float {
                val tr = tracks[id]
                val own = tr?.size(w) ?: 0
                val m = merged[id]
                var n = (if (own == 0 && m != null) m else own).toFloat()
                if (tr != null) n = max(0f, n - (tr.luck(w) - tr.luck(max(from, born[id] ?: 0))))
                if (depth < 3) for (c in children[id].orEmpty()) if ((born[c] ?: 0) <= w) n += numbers(c, w, from, depth + 1)
                return n
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
                for (k in 1..horizon) { sum += d * numbers(c.groupId, c.week + k, c.week) / c.size.coerceAtLeast(1).toFloat(); norm += d; d *= discount }
                c.ret = sum / norm
                out += c
            }
            pending.clear(); pending += waiting
            checkRails()
            // Let go of what no waiting choice needs: the weeks before the oldest of them, and the groups gone before it.
            val oldest = pending.minOfOrNull { it.week } ?: week
            val here = run.groups.groups.mapTo(HashSet()) { it.id }
            val it = tracks.entries.iterator()
            while (it.hasNext()) { val (id, tr) = it.next(); if (id !in here && tr.base + tr.n <= oldest) it.remove() else tr.cut(oldest) }
            return out
        }
    }

    /** The clipped surrogate's gradient over [part] (to be minimised), for the network's weights and the identities. */
    private fun gradient(model: SharedMover, part: List<Choice>, weight: Map<Choice, Float>): Pair<FloatArray, FloatArray> {
        val gw = FloatArray(model.net.w.size); val gi = FloatArray(model.identity.size)
        val dIn = FloatArray(model.inputs)
        val bufs = ArrayList<Mlp.Buffers>()
        for (c in part) {
            val n = c.inputs.size
            while (bufs.size < n) bufs += model.net.buffers()
            val scores = FloatArray(n) { k -> model.fill(c.kind, c.inputs[k], c.band, bufs[k].a[0], c.temper); model.net.forward(bufs[k]) + model.residual * (c.baseline.getOrNull(k) ?: 0f) + model.pull(c.temper, c.inputs[k]) }
            val p = softmax(scores)
            val logp = ln(max(p[c.taken], 1e-8f))
            val ratio = exp(logp - c.logp)
            val a = c.advantage
            // The clipped objective passes no gradient once the ratio has moved past the clip in the advantage's direction.
            val clipped = (a > 0f && ratio > 1f + CLIP) || (a < 0f && ratio < 1f - CLIP)
            val coef = if (clipped) 0f else a * ratio
            var entropy = 0f; for (q in p) if (q > 0f) entropy -= q * ln(q)
            val w = weight.getValue(c)
            for (k in 0 until n) {
                // d(objective)/d(score k): the policy term, and the entropy bonus that keeps it exploring.
                val dPolicy = coef * ((if (k == c.taken) 1f else 0f) - p[k])
                val dEntropy = -p[k] * (ln(max(p[k], 1e-8f)) + entropy)
                val dOut = -w * (dPolicy + ENTROPY * dEntropy)
                if (dOut == 0f) continue
                model.net.backward(bufs[k], dOut, gw, dIn)
                val s = c.kind
                for (e in 0 until SharedMover.IDENTITY) gi[s.index * SharedMover.IDENTITY + e] += dIn[HexFeatures.SIZE + SharedMover.KIND + e]
            }
        }
        return gw to gi
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

    /** The teachers (every kind that has one) against the hand-written behaviour in [cfg]. */
    fun teacherCheck(cfg: WorldConfig, years: Int, kinds: List<Species>): Pair<Map<Species, Double>, Map<Species, Double>> {
        val both = listOf(true, false).parallelStream().map { t ->
            score(cfg, years, kinds) { run -> if (t) for ((s, net) in TEACHERS) if (s in kinds) run.groups.policies[s] = HexLearning.NetPolicy(net) }
        }.toList()
        return both[0] to both[1]
    }

    /**
     * Animal-weeks per animal at the start, by kind, over [years] in [cfg] with the policies [setUp]
     * installs; for people, less their luck in their own births and deaths (see [HexGroup.luck]), so
     * that a small gain shows through the chance of who was born and who died.
     */
    /**
     * [score] with everything hand-written, worked out once for each world and kept: it is the same
     * every check (the runs are reproducible), and it was a quarter of each check's work.
     */
    internal fun handScore(cfg: WorldConfig, years: Int, kinds: List<Species>): Map<Species, Double> =
        handScores.computeIfAbsent(Triple(cfg, years, kinds.map { it.name })) { score(cfg, years, kinds) { } }
    private val handScores = java.util.concurrent.ConcurrentHashMap<Triple<WorldConfig, Int, List<String>>, Map<Species, Double>>()

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
    private fun here(c: Choice) = c.inputs.indices.firstOrNull { c.inputs[it].getOrElse(10) { 0f } > 0.5f } ?: 0

    fun value(model: SharedMover, c: Choice): Float {
        val b = buffers.get()
        model.fill(c.kind, c.inputs[here(c)], c.band, b.a[0], c.temper)
        return net.forward(b)
    }

    /** A few passes of least squares toward the returns seen this round. */
    fun learn(model: SharedMover, cs: List<Choice>, round: Int) {
        repeat(3) { epoch ->
            for (batch in cs.shuffled(kotlin.random.Random(round * 17L + epoch)).chunked(1024)) {
                val parts = batch.chunked((batch.size + HexPpo.PARTS - 1) / HexPpo.PARTS).parallelStream().map { part ->
                    val g = FloatArray(net.w.size); val b = net.buffers()
                    for (c in part) {
                        model.fill(c.kind, c.inputs[here(c)], c.band, b.a[0], c.temper)
                        val v = net.forward(b)
                        net.backward(b, 2f * (v - c.ret) / batch.size, g, null)
                    }
                    g
                }.toList()
                val g = FloatArray(net.w.size); for (p in parts) for (i in g.indices) g[i] += p[i]
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
