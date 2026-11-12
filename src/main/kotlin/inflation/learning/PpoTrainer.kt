package inflation.learning

import inflation.Config
import inflation.ecology.Catalogue
import inflation.ecology.HexFeatures
import inflation.ecology.Species
import inflation.ecology.WAYS_IN
import inflation.learning.HexPpo.CLIP
import inflation.learning.HexPpo.ENTROPY
import inflation.stream
import inflation.Stream
import java.io.File
import java.util.concurrent.CompletableFuture
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A training run of [HexPpo]: the networks and their optimisers, critics and the bands' ways, kept in
 * [PpoOptions.out] (and carried on from there if a run left them); the training worlds; and the rounds,
 * each of them play, judge, learn and, every few rounds, the ecosystem check ([EcoCheck]) and a reference check against the hand-written behaviour.
 */
class PpoTrainer(private val o: PpoOptions) {
    private val out = o.out.also { it.mkdirs() }
    private val kinds = Catalogue.learned
    private val files = Role.entries.map { it.file(out) }
    private val models = files.mapIndexed { i, f -> if (f.exists()) SharedMover.load(f) else SharedMover.random(o.hidden, Random(FIRST_SEED + Role.entries[i].ordinal), if (Role.entries[i] == Role.PEOPLE) WAYS_IN else 0) }
        .also { ms -> for ((i, r) in Role.entries.withIndex()) o.residual[r]?.let { ms[i].residual = it } }
    /** A model's two optimisers: for its network's weights and for its kinds' identity vectors. */
    private class Adams(val net: Adam, val identity: Adam)
    private val adams = models.map { Adams(Adam(it.net.w.size, o.rate), Adam(it.identity.size, o.rate)) }
    /** One critic for each role: what a group's return can be expected to be where it stands. */
    private val critics = Role.entries.mapIndexed { i, r -> Critic.loadOrNew(r.criticFile(out), models[i]) }
    /** Bands' ways, learned from their draws, reading their camp's terms (see [WaysPpo], [CampTerms]). */
    private val waysState = if (o.learnWays) WaysPpo.State(out, o.hidden, o.rate) else null
    /** The check of each round's networks against the kept ones (see [EcoCheck]), running beside the training. */
    private val eco = EcoCheck(out)
    private var checking: Pair<CompletableFuture<EcoCheck.Verdict>, File>? = null
    /** The training worlds, living on from round to round (for [PpoOptions.worldRounds] rounds, if set, then a fresh one takes its place). */
    private val players = arrayOfNulls<HexPpo.Player>(o.worlds)
    /** Days each round plays in each world (its steps' lengths as `t.stepDays` sets them, or as `ppo.stepChoices` draws them: [HexPpo.Player]). */
    private val days = o.years * inflation.DAYS_PER_YEAR
    private val log = RunLog(out, "round")
    /** Advantages (in spreads of the kind's) are held within this much of none: a few collapses would otherwise decide a round, the spread being set by them. */
    /** How many times the step an input that has hardly been trained gets (see [Mlp.smallInputRates]); 1: none. */
    private val smallInputBoost = Config.float(Config.Key.PPO_SMALL_INPUT_BOOST, 1f)
    private val advantageClip = Config.float(Config.Key.PPO_ADVANTAGE_CLIP, 3f).let { if (it <= 0f) Float.MAX_VALUE else it }

    fun run() {
        // A fresh network first learns to choose as the hand-written behaviour does, so learning starts from it: each role
        // whose network is not in the out directory, whatever the others' (one role may be warm-started from a kept net).
        // (People's network chooses nothing since people's net major 11 ([CampPolicy]): it is kept, untrained, only as what its critic
        // reads ([Critic], [SharedMover.fill]), so a fresh one is saved as it is, without imitating; one carried on is saved again
        // under today's version, with its ways.)
        models[Role.PEOPLE.ordinal].save(files[Role.PEOPLE.ordinal])
        val fresh = Role.entries.filter { !files[it.ordinal].exists() }.toSet()
        if (fresh.isNotEmpty()) {
            HexPpo.imitate(kinds, models, o.worlds, o.size, o.sampling, o.maps, fresh)
            for (r in fresh) models[r.ordinal].save(files[r.ordinal])
        }
        eco.init()
        var ahead: CompletableFuture<List<List<Choice>>>? = null
        val firstRound = log.done()
        for (round in firstRound until firstRound + o.rounds) {
            val t0 = System.nanoTime()
            takeCheck(false)
            val choices = ahead?.join() ?: playRound(round, models, waysState?.ways)
            val tPlay = System.nanoTime()
            // The next round's play goes on beside this round's learning and check, with the networks as
            // they are now (a round behind when it is learnt from: each choice keeps the probability it
            // was made with, and the clipped update allows for it).
            ahead = if (o.overlap && round + 1 < firstRound + o.rounds) {
                val ms = models.map { it.copy() }
                val waysNow = waysState?.ways?.copy()
                CompletableFuture.supplyAsync { playRound(round + 1, ms, waysNow) }
            } else null
            val everything = choices.flatten()
            val waysChoices = everything.filterIsInstance<WaysChoice>()
            val valueOnly = everything.filter { it.valueOnly }
            val all = everything.filter { it !is WaysChoice && !it.valueOnly }
            val stats = StringBuilder()
            if (valueOnly.isNotEmpty()) stats.append("%d more choices for the critics alone; ".format(valueOnly.size))
            val waysExplained = judgeWays(waysChoices)
            val byKind = judge(all, valueOnly, round, stats)
            for (mi in models.indices) learn(mi, all, byKind, round - firstRound, round, stats)
            waysState?.let { st ->
                // (Held with people's role, the ways play as they are and do not learn.)
                if (Role.PEOPLE in o.fixed && round - firstRound < o.fixedRounds) stats.append("ways held; ")
                else { stats.append(WaysPpo.learn(st, waysChoices, round, waysExplained)).append("; "); st.save() }
            }
            val returns = byKind.entries.sortedBy { it.key.name }.joinToString(", ") { (s, cs) -> "%s %.3f".format(s.name, cs.map { it.ret.toDouble() }.average()) }
            // (What was not a number when the networks chose, if anything: see [NonFinite].)
            val nonFinite = NonFinite.take().takeIf { it.isNotEmpty() }?.let { "; not numbers when choosing: $it" } ?: ""
            val line = "round %d  %.0f s (playing %.0f s)  %s mean return: %s%s".format(round, (System.nanoTime() - t0) / 1e9, (tPlay - t0) / 1e9, stats, returns, nonFinite)
            log.write(line)
            if (o.checkEvery > 0 && round % o.checkEvery == o.checkEvery - 1) {
                // The ecosystem check runs beside the training (one at a time: a round whose check would start while the
                // last is still running goes unchecked); the reference check against the hand-written behaviour follows.
                if (checking == null) {
                    val candidate = File(out, "checks/round-${round + 1}/candidate").also { EcoCheck.copyRoles(out, it, Role.entries) }
                    checking = eco.start(round, candidate) to candidate
                } else log.write("round ${round + 1} not checked: the last check is still running")
                // (The next round's play finishes first: its worlds and the check's would not all fit in memory at once.)
                ahead?.join()
                check(round)
            }
        }
        takeCheck(true)
    }

    /** The last ecosystem check's verdict, if it is in (or, [wait]ing for it, when it is): logged, and each role that passed kept. */
    private fun takeCheck(wait: Boolean) {
        val (f, candidate) = checking ?: return
        if (!wait && !f.isDone) return
        checking = null
        val v = try { f.join() } catch (e: Exception) { log.check("check failed: ${e.cause?.message ?: e.message}"); return }
        for (l in v.lines) log.check(l)
        eco.apply(v, candidate)
    }

    /** One round's play in every training world, with [ms] choosing and the bands' ways drawn from [ways]; fresh worlds where one is done. */
    private fun playRound(r: Int, ms: List<SharedMover>, ways: LearnedWays?): List<List<Choice>> {
        val fresh = (0 until o.worlds).filter { w -> players[w]?.done != false }
        for (w in fresh) players[w]?.let { p ->
            p.derailed?.let { why -> log.write("world $w replaced after round ${r - 1}: $why") }
            // (Reported only: whether the world it leaves stands within nominal limits.)
            log.write("world $w at its end, within nominal limits (state): ${p.nominal().brief()}")
        }
        // (New worlds are made side by side.)
        fresh.parallelStream().map { w -> w to HexPpo.Player(o.maps.world(50_000L + r * 100L + w, o.width, o.height, w), kinds, days * o.worldRounds, o.burnYears * inflation.DAYS_PER_YEAR, o.sampling) { line -> log.write("world $w $line") } }
            .toList().forEach { (w, p) -> players[w] = p }
        return (0 until o.worlds).toList().parallelStream().map { w -> players[w]!!.play(days, ms, ways) }.toList()
    }

    /**
     * Judges each choice against what its group could expect where it stood (the critic's estimate),
     * not only against its kind's average: much of what befalls a group is its situation and its luck,
     * not the move; then scales it within its kind. Teaches the critics this round's returns. The choices by kind.
     */
    private fun judge(all: List<Choice>, valueOnly: List<Choice>, round: Int, stats: StringBuilder): Map<Species, List<Choice>> {
        val byKind = all.groupBy { it.kind }
        val expected = HashMap<Choice, Float>()
        val judged = HashMap<Choice, Float>()
        for ((mi, model) in models.withIndex()) {
            val mine = all.filter { Role.of(it.kind).ordinal == mi }
            val critic = critics[mi]
            // (People's weeks are all for the critic alone since no network chooses their camps ([CampPolicy]): it learns from them,
            // and judges the ways' draws by them, [judgeWays].)
            if (mine.isEmpty()) {
                val alone = valueOnly.filter { Role.of(it.kind).ordinal == mi }
                if (alone.isEmpty()) continue
                val explainedAlone = critic.explained(model, alone)
                critic.learn(model, alone, round); critic.save(Role.entries[mi].criticFile(out), model)
                stats.append("%s critic explains %.2f of %d weeks recorded for it alone, %.0f%% seen again; ".format(Role.entries[mi], explainedAlone.values.average(), alone.size, 100.0 * alone.count { it.probe != null } / alone.size))
                continue
            }
            // (Judged with the critic as it stood before this round, then it learns from the round.)
            val t0 = System.nanoTime()
            val explained = critic.explained(model, mine)
            for (c in mine) { expected[c] = critic.value(model, c); judged[c] = critic.target(model, c) }
            // (The critic learns too from the groups that took their best, [Choice.valueOnly]: judged, not judging.)
            critic.learn(model, mine + valueOnly.filter { Role.of(it.kind).ordinal == mi }, round)
            // (What the deaths of people cost their choices' returns, by cause class: the weights on them, if any, are in [HexPpo.Player].)
            if (mi == Role.PEOPLE.ordinal) mine.filter { !it.valueOnly && it !is WaysChoice }.takeIf { it.isNotEmpty() }?.let { ms ->
                stats.append("people's returns lose %.4f to cold and camp, %.4f to going without, %.4f in all; ".format(ms.map { it.lostKit.toDouble() }.average(), ms.map { it.lostFood.toDouble() }.average(), ms.map { it.lostAll.toDouble() }.average()))
            }
            val worst = explained.entries.sortedBy { it.value }.take(3).joinToString(", ") { "%s %.2f".format(it.key.name, it.value) }
            stats.append("%s critic explains %.2f (least: %s), %.0f%% seen again (%.0f s); ".format(Role.entries[mi], explained.values.average(), worst, 100.0 * mine.count { it.probe != null } / mine.size, (System.nanoTime() - t0) / 1e9))
        }
        for ((_, cs) in byKind) {
            val mean = cs.map { judged.getValue(it).toDouble() }.average().toFloat()
            val gap = cs.associateWith { c -> judged.getValue(c) - (expected[c] ?: mean) + c.credit }
            val sd = sqrt(gap.values.map { (it * it).toDouble() }.average()).toFloat().coerceAtLeast(1e-3f)
            for (c in cs) c.advantage = (gap.getValue(c) / sd).coerceIn(-advantageClip, advantageClip)
        }
        return byKind
    }

    /**
     * Judges the bands' ways draws [cs] by the people's critic as it stands before this round's learning, as their moves are
     * judged ([judge]): each against what its band could expect where it stood that week before it moved, centred and scaled
     * among the draws. The share of the spread in their judged returns the critic accounts for.
     */
    private fun judgeWays(cs: List<WaysChoice>): Double {
        if (cs.isEmpty()) return 0.0
        val model = models[Role.PEOPLE.ordinal]; val critic = critics[Role.PEOPLE.ordinal]
        val expected = FloatArray(cs.size) { critic.value(model, cs[it]) }
        val judged = FloatArray(cs.size) { critic.target(model, cs[it]) }
        val gap = FloatArray(cs.size) { judged[it] - expected[it] + cs[it].credit }
        val mean = gap.average().toFloat()
        val sd = sqrt(gap.map { ((it - mean) * (it - mean)).toDouble() }.average()).toFloat().coerceAtLeast(1e-3f)
        for ((k, c) in cs.withIndex()) c.advantage = ((gap[k] - mean) / sd).coerceIn(-advantageClip, advantageClip)
        val jm = judged.average()
        val total = judged.sumOf { (it - jm) * (it - jm) }
        return if (total <= 0.0) 0.0 else 1.0 - gap.sumOf { (it * it).toDouble() } / total
    }

    /** A few passes of clipped policy gradient for role [mi], every kind weighing the same; saves its network and critic. [sinceStart]: rounds into this run. */
    private fun learn(mi: Int, all: List<Choice>, byKind: Map<Species, List<Choice>>, sinceStart: Int, round: Int, stats: StringBuilder) {
        val model = models[mi]
        val both = all.filter { Role.of(it.kind).ordinal == mi }
        if (both.isEmpty()) return
        // (A role held fixed plays with its network as it is and does not learn, while the others catch up.)
        if (Role.entries[mi] in o.fixed && sinceStart < o.fixedRounds) { stats.append(Role.entries[mi]).append(" held; "); return }
        val mine = both
        if (mine.isEmpty()) { model.save(files[mi]); return }
        val t0 = System.nanoTime()
        val kindsHere = mine.map { it.kind }.distinct()
        val perKind = mine.groupingBy { it.kind }.eachCount()
        val weight = mine.associateWith { c -> 1f / (kindsHere.size * perKind.getValue(c.kind)) }
        var skipped = 0
        var clipped = 0L; var seen = 0L
        val inputRates = if (smallInputBoost > 1f) model.net.smallInputRates(smallInputBoost) else null
        repeat(4) { epoch ->
            for (batch in mine.shuffled(stream(0L, Stream.BATCHES, round.toLong(), epoch.toLong())).chunked(512)) {
                val parts = inParts(batch) { part -> gradient(model, part, weight) }
                val gradW = FloatArray(model.net.w.size); val gradId = FloatArray(model.identity.size)
                for ((w, id, c) in parts) { gradW.add(w); gradId.add(id); if (epoch == 3) clipped += c }
                if (epoch == 3) seen += batch.size
                // (A step that is not all numbers would make the network not a number for good: it is left out, and told.)
                if (!gradW.allFinite() || !gradId.allFinite()) { skipped++; continue }
                // (Each batch is a share of the whole, so its weights are scaled up to stand for it.)
                val scale = mine.size.toFloat() / batch.size
                for (i in gradW.indices) gradW[i] *= scale; for (i in gradId.indices) gradId[i] *= scale
                adams[mi].net.step(model.net.w, gradW, inputRates); adams[mi].identity.step(model.identity, gradId)
            }
        }
        model.save(files[mi]); critics[mi].save(Role.entries[mi].criticFile(out), model)
        stats.append(Role.entries[mi]).append(' ').append(mine.size).append(" choices (%.0f s, clipped %.2f%s); ".format((System.nanoTime() - t0) / 1e9, clipped.toDouble() / seen.coerceAtLeast(1),
            if (skipped > 0) ", $skipped steps left out: not numbers" else ""))
    }

    /**
     * The reference check, reported only: against the hand-written behaviour, one role at a time (the others
     * hand-written, so a role's gain is its own and not the others'), on worlds it has not trained on; then the
     * bands' learned ways (their middles) against the scaffold's, both with the people's learned moves. It shows how far the networks have moved
     * from the hand-written rules, not whether they are better: that is [EcoCheck]'s.
     */
    private fun check(round: Int) {
        val seeds = (91L until 91L + o.checkSeeds).toList()
        val scores = seeds.parallelStream().map { seed -> HexPpo.check(HexPpo.checkWorld(seed, o.size, o.checkOnMaps, o.maps), o.years + 1, kinds, models) }.toList()
        val parts = ArrayList<String>()
        // (People's camps are hand-written: only the kinds whose network chooses, [HexPpo.check].)
        for (s in kinds.filter { k -> scores.all { v -> k in v.learned } }) {
            val learnedEach = scores.map { it.learned[s] ?: 0.0 }
            val handEach = scores.map { it.hand[s] ?: 0.0 }
            parts += s.name + " " + HexPpo.compare(learnedEach, handEach)
        }
        val c = "check round %d (each role learned alone vs all hand-written, animal-weeks per animal, %d %s worlds: mean change +- its standard error, worlds better): %s".format(round + 1, seeds.size, if (o.checkOnMaps && !o.maps.isEmpty()) "training" else "fresh", parts.joinToString(", "))
        log.check(c)
        waysState?.let { st ->
            val w = seeds.parallelStream().map { seed -> WaysPpo.check(HexPpo.checkWorld(seed, o.size, o.checkOnMaps, o.maps), o.years + 1, kinds, st.ways) }.toList()
            val waysLine = "check round %d ways (learned vs scaffold, people-weeks per person): %s".format(round + 1, HexPpo.compare(w.map { it.learned }, w.map { it.hand }))
            log.check(waysLine)
        }
    }

    /** The clipped surrogate's gradient over [part] (to be minimised), for the network's weights and the identities. */
    private fun gradient(model: SharedMover, part: List<Choice>, weight: Map<Choice, Float>): Triple<FloatArray, FloatArray, Int> {
        val gradW = FloatArray(model.net.w.size); val gradId = FloatArray(model.identity.size)
        var clippedN = 0
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
            if (clipped) clippedN++
            val coef = if (clipped) 0f else a * ratio
            var entropy = 0f; for (q in p) if (q > 0f) entropy -= q * ln(q)
            val w = weight.getValue(c)
            for (k in 0 until n) {
                // d(objective)/d(score k): the policy term, and the entropy bonus that keeps it exploring.
                val dPolicy = coef * ((if (k == c.taken) 1f else 0f) - p[k])
                val dEntropy = -p[k] * (ln(max(p[k], 1e-8f)) + entropy)
                val dOut = -w * (dPolicy + ENTROPY * dEntropy)
                if (dOut == 0f) continue
                model.net.backward(bufs[k], dOut, gradW, dIn)
                val s = c.kind
                for (e in 0 until SharedMover.IDENTITY) gradId[s.index * SharedMover.IDENTITY + e] += dIn[HexFeatures.size + SharedMover.KIND + e]
            }
        }
        return Triple(gradW, gradId, clippedN)
    }

    companion object {
        /** Fresh networks' first seed, by role ordinal (as when the herds' role came first). */
        const val FIRST_SEED = 8L
    }
}
