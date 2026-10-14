package inflation.learning

import inflation.ecology.Catalogue
import inflation.ecology.HexFeatures
import inflation.ecology.HexGroup
import inflation.ecology.HexPolicy
import inflation.ecology.Species
import inflation.world.Date
import inflation.world.WorldConfig
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ThreadLocalRandom
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

    /** Writes a candidate's whole input for kind [s]: its place features, then the kind's part. */
    fun fill(s: Species, features: FloatArray, into: FloatArray) {
        features.copyInto(into, 0, 0, min(features.size, HexFeatures.SIZE))
        kindInputs(s, into, HexFeatures.SIZE)
    }

    fun copy() = SharedMover(net.copy(), identity.copyOf())

    fun save(file: File) {
        net.save(file)
        File(file.path + ".identity").writeText(identity.joinToString(" ") + "\n")
    }

    companion object {
        const val KIND = 14
        const val IDENTITY = 6
        val INPUTS = HexFeatures.SIZE + KIND + IDENTITY

        /**
         * A network trained before place features were added (they come last among the place's, before
         * the kind's part) gets zero weights for the new ones, so it chooses exactly as before.
         */
        fun widen(net: Mlp): Mlp {
            val old = net.sizes[0]
            if (old >= INPUTS) return net
            val add = INPUTS - old; val at = old - KIND - IDENTITY
            val sizes = net.sizes.copyOf().also { it[0] = INPUTS }
            val m = Mlp(sizes)
            val nOut = sizes[1]
            for (j in 0 until nOut) for (i in 0 until old) m.w[j * INPUTS + (if (i < at) i else i + add)] = net.w[j * old + i]
            // The biases and the deeper layers are unchanged.
            net.w.copyInto(m.w, INPUTS * nOut, old * nOut, net.w.size)
            return m
        }

        fun random(hidden: Int, rnd: Random) = SharedMover(Mlp.random(intArrayOf(INPUTS, hidden, hidden, 1), rnd), FloatArray(Catalogue.all.size * IDENTITY) { 0.1f * (rnd.nextFloat() - 0.5f) })

        fun load(file: File): SharedMover {
            val net = widen(Mlp.load(file.readText()))
            val idFile = File(file.path + ".identity")
            val id = if (idFile.exists()) idFile.readText().trim().split(" ").map { it.toFloat() }.toFloatArray() else FloatArray(Catalogue.all.size * IDENTITY)
            return SharedMover(net, id)
        }
    }
}

/** One recorded choice: the candidates' place features (the kind's part is added when learning), the one taken and its log-probability, who chose, and when. */
class Choice(val kind: Species, val groupId: Int, val size: Int, val week: Int, val inputs: Array<FloatArray>, val taken: Int, val logp: Float) {
    /** What followed: the group's (and its offshoots') numbers over the next weeks against its numbers now, discounted. */
    var ret = 0f
    var advantage = 0f
}

/** Chooses with a [SharedMover], recording a share of its choices in [record] (null: the best, not sampled). */
class MoverPolicy(private val mover: SharedMover, private val record: ConcurrentLinkedQueue<Choice>?, private val rate: (Species) -> Float, private val week: () -> Int) : HexPolicy {
    private val buffers = ThreadLocal.withInitial { mover.net.buffers() }
    override fun choose(g: HexGroup, features: Array<FloatArray>, baseline: FloatArray): Int {
        val b = buffers.get()
        val n = features.size
        val scores = FloatArray(n) { k -> mover.fill(g.species, features[k], b.a[0]); mover.net.forward(b) }
        if (record == null) { var best = 0; for (k in 1 until n) if (scores[k] > scores[best]) best = k; return best }
        val p = softmax(scores)
        val rnd = ThreadLocalRandom.current()
        var u = rnd.nextFloat(); var taken = n - 1
        for (k in 0 until n) { u -= p[k]; if (u <= 0f) { taken = k; break } }
        if (rnd.nextFloat() < rate(g.species)) record += Choice(g.species, g.id, g.size, week(), Array(n) { k -> features[k].copyOf(HexFeatures.SIZE) }, taken, ln(max(p[taken], 1e-8f)))
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
 * few rounds the networks play the hand-written behaviour on fixed worlds, kind by kind; a kind that
 * does worse than with the last version it did well with keeps that version (in `deployed/`).
 *
 * Usage: hex-ppo [--out DIR] [--worlds N] [--size KM] [--years N] [--rounds N] [--hidden N] [--rate R] [--check N] [--check-seeds N] [--per-kind N] [--teacher DIR] [--teacher-check] [--maps SEED:LAT,...]
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
    const val CLIP = 0.2f
    const val ENTROPY = 0.01f
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
        var checkSeeds = 4
        var teacherCheck = false
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
                "--check" -> checkEvery = args[++k].toInt()
                "--check-seeds" -> checkSeeds = args[++k].toInt()
                "--per-kind" -> PER_KIND_YEAR = args[++k].toInt()
                "--teacher" -> { val dir = File(args[++k]); TEACHERS = Catalogue.all.mapNotNull { s -> HexLearning.file(dir, s).takeIf { it.exists() }?.let { s to Mlp.load(it.readText()) } }.toMap() }
                "--teacher-check" -> teacherCheck = true
                "--maps" -> MAPS = args[++k].split(",").map { m -> val (s, l) = m.split(":"); s.toLong() to l.toDouble() }
                else -> error("unknown argument ${args[k]}")
            }
            k++
        }
        out.mkdirs()
        val kinds = Catalogue.all.filter { it in Catalogue.migratory || it.carnivore }
        val kindsAll = Catalogue.all.filter { it in Catalogue.migratory || it.carnivore }
        // Only how the teachers do against the hand-written behaviour, in today's world.
        if (teacherCheck) {
            val seeds = (91L until 91L + checkSeeds).toList()
            val scores = seeds.parallelStream().map { seed -> check(world(seed, size), years + 1, kindsAll, emptyList(), teachers = true) }.toList()
            println("teachers vs hand-written: " + kindsAll.filter { it in TEACHERS }.joinToString(", ") { s -> "%s %.3f vs %.3f".format(s.name, scores.map { it.first[s] ?: 0.0 }.average(), scores.map { it.second[s] ?: 0.0 }.average()) })
            return
        }
        val files = ROLES.map { File(out, "$it.mlp") }
        val models = files.mapIndexed { i, f -> if (f.exists()) SharedMover.load(f) else SharedMover.random(hidden, Random(7L + i)) }
        fun modelOf(s: Species) = models[role(s)]
        val adams = models.map { Adam(it.net.w.size, rate) to Adam(it.identity.size, rate) }
        // A fresh network first learns to choose as the hand-written behaviour does, so learning starts from it.
        if (!files[0].exists()) {
            imitate(kinds, models, worlds, size)
            for (i in models.indices) models[i].save(files[i])
        }
        // The versions each kind plays with outside training: its last good one.
        val deployedDir = File(out, "deployed").also { it.mkdirs() }
        val best = HashMap<Species, Double>()
        File(deployedDir, "scores.txt").takeIf { it.exists() }?.readLines()?.forEach { l -> val (n, v) = l.split("\t"); kinds.firstOrNull { it.name == n }?.let { best[it] = v.toDouble() } }
        val log = File(out, "log.txt")
        val firstRound = if (log.exists()) log.readLines().count { it.startsWith("round ") } else 0
        for (round in firstRound until firstRound + rounds) {
            val t0 = System.nanoTime()
            // ---- play
            val choices = (0 until worlds).toList().parallelStream().map { w ->
                play(world(50_000L + round * 100L + w, size, w), years, kinds, models)
            }.toList()
            val tPlay = System.nanoTime()
            val all = choices.flatMap { it.first }
            // ---- judge each choice against its kind's own average
            val byKind = all.groupBy { it.kind }
            for ((_, cs) in byKind) {
                val mean = cs.map { it.ret.toDouble() }.average().toFloat()
                val sd = sqrt(cs.map { (it.ret - mean).toDouble().let { d -> d * d } }.average()).toFloat().coerceAtLeast(1e-3f)
                for (c in cs) c.advantage = (c.ret - mean) / sd
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
                        val parts = batch.chunked((batch.size + 15) / 16).parallelStream().map { part -> gradient(model, part, weight) }.toList()
                        val gw = FloatArray(model.net.w.size); val gi = FloatArray(model.identity.size)
                        for ((a, bb) in parts) { for (i in gw.indices) gw[i] += a[i]; for (i in gi.indices) gi[i] += bb[i] }
                        // (Each batch is a share of the whole, so its weights are scaled up to stand for it.)
                        val scale = mine.size.toFloat() / batch.size
                        for (i in gw.indices) gw[i] *= scale; for (i in gi.indices) gi[i] *= scale
                        adams[mi].first.step(model.net.w, gw); adams[mi].second.step(model.identity, gi)
                    }
                }
                model.save(files[mi])
                stats.append(ROLES[mi]).append(' ').append(mine.size).append(" choices; ")
            }
            val returns = byKind.entries.sortedBy { it.key.name }.joinToString(", ") { (s, cs) -> "%s %.3f".format(s.name, cs.map { it.ret.toDouble() }.average()) }
            val line = "round %d  %.0f s (playing %.0f s)  %s mean return: %s".format(round, (System.nanoTime() - t0) / 1e9, (tPlay - t0) / 1e9, stats, returns)
            println(line); log.appendText(line + "\n")
            // ---- check against the hand-written behaviour, and keep each kind's last good version
            if (round % checkEvery == checkEvery - 1) {
                val seeds = (91L until 91L + checkSeeds).toList()
                val scores = seeds.parallelStream().map { seed -> check(world(seed, size, (seed - 91L).toInt()), years + 1, kinds, models) }.toList()
                val parts = ArrayList<String>()
                for (s in kinds) {
                    val learned = scores.map { it.first[s] ?: 0.0 }.average()
                    val hand = scores.map { it.second[s] ?: 0.0 }.average()
                    val keep = learned >= (best[s] ?: Double.NEGATIVE_INFINITY)
                    if (keep) { best[s] = learned; modelOf(s).save(File(deployedDir, s.name.replace(' ', '-') + ".mlp")) }
                    parts += "%s %.3f vs %.3f%s".format(s.name, learned, hand, if (keep) "" else " (kept older)")
                }
                File(deployedDir, "scores.txt").writeText(best.entries.joinToString("\n") { "${it.key.name}\t${it.value}" } + "\n")
                val c = "check round %d (learned vs hand-written, animal-weeks per animal): %s".format(round + 1, parts.joinToString(", "))
                println(c); File(out, "check.txt").appendText(c + "\n")
            }
        }
    }

    /** Choices of the hand-written behaviour: the candidates' inputs and their hand-written scores. */
    class Shown(val kind: Species, val inputs: Array<FloatArray>, val scores: FloatArray)

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
            for (s in kinds) run.groups.policies[s] = HexPolicy { g, f, b0 ->
                // A teacher network, where there is one for the kind, in place of the hand-written scores.
                val b = teachers[g.species]?.let { (bufs, net) -> val bb = bufs.get(); FloatArray(f.size) { k -> f[k].copyInto(bb.a[0], 0, 0, min(f[k].size, bb.a[0].size)); net.forward(bb) } } ?: b0
                var best = 0; for (k in b.indices) if (b[k] > b[best]) best = k
                if (ThreadLocalRandom.current().nextFloat() < (rates[g.species] ?: 0f)) {
                    kept += Shown(g.species, Array(f.size) { k -> f[k].copyOf(HexFeatures.SIZE) }, b.copyOf())
                }
                best
            }
            repeat(stepsPerYear) { run.step() }
            kept.toList()
        }.toList().flatten()
        for ((mi, model) in models.withIndex()) {
            val mine = shown.filter { role(it.kind) == mi }
            if (mine.isEmpty()) continue
            val kindsHere = mine.map { it.kind }.distinct()
            val counts = mine.groupingBy { it.kind }.eachCount()
            val adam = Adam(model.net.w.size, 1e-3f); val adamId = Adam(model.identity.size, 1e-3f)
            repeat(15) { epoch ->
                var loss = 0.0
                for (batch in mine.shuffled(Random(epoch.toLong())).chunked(256)) {
                    val parts = batch.chunked((batch.size + 15) / 16).parallelStream().map { part ->
                        val gw = FloatArray(model.net.w.size); val gi = FloatArray(model.identity.size); val dIn = FloatArray(model.inputs)
                        val bufs = ArrayList<Mlp.Buffers>(); var l = 0.0
                        for (c in part) {
                            val n = c.inputs.size
                            while (bufs.size < n) bufs += model.net.buffers()
                            val p = softmax(FloatArray(n) { k -> model.fill(c.kind, c.inputs[k], bufs[k].a[0]); model.net.forward(bufs[k]) })
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

    /** How much sharper the copied preference is than the hand-written scores themselves. */
    const val IMITATE_SHARPNESS = 10f

    /** One world for [years] with the networks choosing by chance; the recorded choices with their returns. */
    fun play(cfg: WorldConfig, years: Int, kinds: List<Species>, models: List<SharedMover>): Pair<List<Choice>, String> {
        val run = HexLearning.Run(cfg, kinds)
        var week = 0
        val record = ConcurrentLinkedQueue<Choice>()
        val stepsPerYear = Date.DAYS_PER_YEAR / cfg.daysPerStep
        // The same number of choices a year of each kind, however many groups it has.
        val rates = HashMap<Species, Float>()
        fun setRates() { for (s in kinds) { val n = run.groups.groups.count { it.alive && it.species == s }; rates[s] = if (n == 0) 0f else min(1f, PER_KIND_YEAR.toFloat() / (n * stepsPerYear)) } }
        setRates()
        val policies = models.map { MoverPolicy(it, record, { s -> rates[s] ?: 0f }, { week }) }
        for (s in kinds) run.groups.policies[s] = policies[role(s)]
        val steps = years * stepsPerYear
        // Each group's numbers week by week, and where its offshoots came from, to follow each choice's outcome.
        val sizes = HashMap<Int, IntArray>()
        val parent = HashMap<Int, Int>()
        val merged = HashMap<Int, Int>()
        val born = HashMap<Int, Int>()
        repeat(steps) {
            run.step()
            week++
            if (week % stepsPerYear == 0) setRates()
            for (g in run.groups.groups) {
                if (g.parent >= 0 && g.id !in parent) { parent[g.id] = g.parent; born[g.id] = week }
                if (g.mergedSize >= 0) merged.putIfAbsent(g.id, g.mergedSize)
                sizes.getOrPut(g.id) { IntArray(steps + 1) }[week] = if (g.alive) g.size else 0
            }
        }
        // Offshoots of each group, for adding their numbers to its own.
        val children = HashMap<Int, MutableList<Int>>()
        for ((c, p) in parent) children.getOrPut(p) { ArrayList() } += c
        fun numbers(id: Int, w: Int, depth: Int = 0): Int {
            val own = sizes[id]?.get(w) ?: 0
            val m = merged[id]
            var n = if (own == 0 && m != null) m else own
            if (depth < 3) for (c in children[id].orEmpty()) if ((born[c] ?: 0) <= w) n += numbers(c, w, depth + 1)
            return n
        }
        val out = ArrayList<Choice>()
        for (c in record) {
            if (c.week + HORIZON > steps) continue
            var sum = 0f; var norm = 0f; var d = 1f
            for (k in 1..HORIZON) { sum += d * numbers(c.groupId, c.week + k) / c.size.coerceAtLeast(1).toFloat(); norm += d; d *= DISCOUNT }
            c.ret = sum / norm
            out += c
        }
        return out to "seed ${cfg.seed}"
    }

    /** The clipped surrogate's gradient over [part] (to be minimised), for the network's weights and the identities. */
    private fun gradient(model: SharedMover, part: List<Choice>, weight: Map<Choice, Float>): Pair<FloatArray, FloatArray> {
        val gw = FloatArray(model.net.w.size); val gi = FloatArray(model.identity.size)
        val dIn = FloatArray(model.inputs)
        val bufs = ArrayList<Mlp.Buffers>()
        for (c in part) {
            val n = c.inputs.size
            while (bufs.size < n) bufs += model.net.buffers()
            val scores = FloatArray(n) { k -> model.fill(c.kind, c.inputs[k], bufs[k].a[0]); model.net.forward(bufs[k]) }
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

    /** Fixed world [cfg] run twice for [years]: with the networks choosing their best, then with the hand-written behaviour; animal-weeks per animal at the start, kind by kind. */
    fun check(cfg: WorldConfig, years: Int, kinds: List<Species>, models: List<SharedMover>, teachers: Boolean = false): Pair<Map<Species, Double>, Map<Species, Double>> {
        fun score(learned: Boolean): Map<Species, Double> {
            val run = HexLearning.Run(cfg, kinds)
            if (learned && teachers) { for ((s, net) in TEACHERS) if (s in kinds) run.groups.policies[s] = HexLearning.NetPolicy(net) }
            else if (learned) {
                val ps = models.map { MoverPolicy(it, null, { 0f }, { 0 }) }
                for (s in kinds) run.groups.policies[s] = ps[role(s)]
            }
            val start = kinds.associateWith { run.groups.count(it).toDouble() }
            val weeks = HashMap<Species, Double>()
            val steps = years * Date.DAYS_PER_YEAR / cfg.daysPerStep
            repeat(steps) {
                run.step()
                for (g in run.groups.groups) if (g.alive) weeks.merge(g.species, g.size.toDouble(), Double::plus)
            }
            return kinds.associateWith { s -> val st = start.getValue(s); if (st <= 0.0) 0.0 else (weeks[s] ?: 0.0) / st / steps }
        }
        // (The two runs go side by side.)
        val both = listOf(true, false).parallelStream().map { score(it) }.toList()
        return both[0] to both[1]
    }
}
