package inflation.learning

import inflation.ecology.Catalogue
import inflation.ecology.HexFauna
import inflation.ecology.HexFeatures
import inflation.ecology.HexGroup
import inflation.ecology.HexGroups
import inflation.ecology.HexPolicy
import inflation.ecology.HexSim
import inflation.ecology.Species
import inflation.world.Date
import inflation.world.HexLand
import inflation.world.HexMap
import inflation.world.HexWorld
import inflation.world.Weather
import inflation.world.World
import inflation.world.WorldConfig
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Learning the migratory herds' moves on the hexes. A lean world: weather, water and plants, the
 * resident herds as densities, and the migratory herds as groups; no hunters and no people, so
 * what a herd learns is the land and the seasons: when to go up to the summer pastures, when to
 * come down before the snow, where the water and the grass are.
 */
object HexLearning {
    val SIZES = intArrayOf(HexFeatures.SIZE, 32, 32, 1)
    /** Candidates kept per recorded decision. */
    const val SLOTS = 32

    /**
     * A world for learning the seasons: cool enough for snow to lie on the high ground for months,
     * and with mountains: the first of [cfg]'s seed and the next ones whose land rises above
     * [MOUNTAIN_M]. Centred at [LATITUDE].
     */
    fun mountainWorld(cfg: WorldConfig): World {
        var seed = cfg.seed
        while (true) {
            val w = World.generate(cfg.copy(seed = seed, centreLatitude = LATITUDE))
            if ((0 until w.grid.size).any { !w.isSea[it] && w.elevation[it] > MOUNTAIN_M }) return w
            seed += 100_000
        }
    }

    /** Learning worlds are centred this far north, °; and must have land this high, m. */
    const val LATITUDE = 40.0
    const val MOUNTAIN_M = 2000f

    /** A lean hex world on [cfg]: weather, land, resident herds, and groups of [kinds] only. */
    class Run(cfg: WorldConfig, kinds: List<Species>, mountains: Boolean = false) {
        val sim = HexSim(if (mountains) mountainWorld(cfg) else World.generate(cfg), kinds)
        val world get() = sim.world
        val hex get() = sim.hex
        val hw get() = sim.hw
        val weather get() = sim.weather
        val land get() = sim.land
        val fauna get() = sim.fauna
        val groups get() = sim.groups
        fun step() = sim.step()
    }

    /** Chooses with one network per kind: the best-scoring candidate. */
    class NetPolicy(private val net: Mlp) : HexPolicy {
        private val buffers = ThreadLocal.withInitial { net.buffers() }
        override fun choose(g: HexGroup, features: Array<FloatArray>, baseline: FloatArray, rnd: kotlin.random.Random): Int {
            val b = buffers.get()
            var best = 0; var bestV = Float.NEGATIVE_INFINITY
            for ((k, f) in features.withIndex()) {
                f.copyInto(b.a[0], 0, 0, minOf(f.size, b.a[0].size))
                val v = net.forward(b) + 0.01f * (rnd.nextFloat() - 0.5f)
                if (v > bestV) { bestV = v; best = k }
            }
            return best
        }
    }

    /** Chooses by the hand-written score, keeping a sample of decisions to copy. */
    class Recorder : HexPolicy {
        val data = ConcurrentLinkedQueue<Pair<Species, FloatArray>>()
        override fun choose(g: HexGroup, features: Array<FloatArray>, baseline: FloatArray, rnd: kotlin.random.Random): Int {
            var best = 0
            for (k in baseline.indices) if (baseline[k] > baseline[best]) best = k
            if (rnd.nextFloat() < 0.3f && data.size < 400_000) {
                // The chosen one, staying put, and a random sample of the rest.
                val pick = LinkedHashSet<Int>(); pick += best
                features.indices.firstOrNull { features[it][10] > 0.5f }?.let { pick += it }
                val rest = features.indices.shuffled(rnd).iterator()
                while (pick.size < minOf(SLOTS, features.size) && rest.hasNext()) pick += rest.next()
                val rec = FloatArray(SLOTS * STRIDE)
                for ((j, k) in pick.withIndex()) {
                    val at = j * STRIDE
                    rec[at] = 1f
                    features[k].copyInto(rec, at + 1)
                    rec[at + 1 + HexFeatures.SIZE] = baseline[k]
                }
                data += g.species to rec
            }
            return best
        }
    }

    const val STRIDE = 1 + HexFeatures.SIZE + 1

    /** Fits a network to reproduce, within each decision, the hand-written scores' differences. */
    fun train(records: List<FloatArray>, rnd: Random, epochs: Int = 20): Pair<Mlp, String>? {
        if (records.size < 200) return null
        val shuffled = records.shuffled(rnd)
        val test = shuffled.take(shuffled.size / 10); val trainSet = shuffled.drop(test.size)
        val net = Mlp.random(SIZES, rnd)
        val b = net.buffers()
        val adam = Adam(net.w.size, 1e-3f)
        val grad = FloatArray(net.w.size)
        val y = FloatArray(SLOTS)
        val batch = 64
        val order = trainSet.indices.toMutableList()
        repeat(epochs) {
            order.shuffle(rnd)
            for (start in order.indices step batch) {
                grad.fill(0f)
                for (j in start until minOf(start + batch, order.size)) {
                    val rec = trainSet[order[j]]
                    var n = 0; var my = 0f; var mt = 0f
                    for (k in 0 until SLOTS) {
                        val at = k * STRIDE
                        if (rec[at] == 0f) continue
                        rec.copyInto(b.a[0], 0, at + 1, at + 1 + HexFeatures.SIZE)
                        y[k] = net.forward(b); my += y[k]; mt += rec[at + STRIDE - 1]; n++
                    }
                    if (n < 2) continue
                    my /= n; mt /= n
                    for (k in 0 until SLOTS) {
                        val at = k * STRIDE
                        if (rec[at] == 0f) continue
                        rec.copyInto(b.a[0], 0, at + 1, at + 1 + HexFeatures.SIZE)
                        net.forward(b)
                        net.backward(b, 2f * ((y[k] - my) - (rec[at + STRIDE - 1] - mt)) / n / batch, grad)
                    }
                }
                adam.step(net.w, grad)
            }
        }
        // Held out: how often the network's best is the hand-written best.
        var same = 0; var decisions = 0
        for (rec in test) {
            var bestY = -1; var bestT = -1; var by = Float.NEGATIVE_INFINITY; var bt = Float.NEGATIVE_INFINITY
            for (k in 0 until SLOTS) {
                val at = k * STRIDE
                if (rec[at] == 0f) continue
                rec.copyInto(b.a[0], 0, at + 1, at + 1 + HexFeatures.SIZE)
                val v = net.forward(b); val t = rec[at + STRIDE - 1]
                if (v > by) { by = v; bestY = k }
                if (t > bt) { bt = t; bestT = k }
            }
            if (bestT >= 0) { decisions++; if (bestY == bestT) same++ }
        }
        return net to "held out %d decisions: same best choice %.1f%%".format(decisions, 100.0 * same / decisions.coerceAtLeast(1))
    }

    fun file(dir: File, s: Species) = File(dir, s.name.replace(' ', '-') + ".mlp")
}

/**
 * Copies the hand-written moves of the migratory herds into networks, on a lean hex world.
 *
 * Usage: hex-imitate [--seed N] [--size KM] [--years N] [--out DIR] [--hunters]
 *
 * Worlds are cool and mountainous (see [HexLearning.mountainWorld]); with --hunters the hunters live
 * there too, by their hand-written behaviour.
 */
object HexImitate {
    @JvmStatic
    fun main(args: Array<String>) {
        var cfg = WorldConfig(seed = 7, widthKm = 600, heightKm = 600)
        var years = 3
        var out = File("hex-policies")
        var hunters = emptyList<Species>()
        var people = false
        var k = 0
        while (k < args.size) {
            when (args[k]) {
                "--seed" -> cfg = cfg.copy(seed = args[++k].toLong())
                "--size" -> { val s = args[++k].toInt(); cfg = cfg.copy(widthKm = s, heightKm = s) }
                "--years" -> years = args[++k].toInt()
                "--out" -> out = File(args[++k])
                "--hunters" -> hunters = Catalogue.predators.filter { !it.human }
                "--people" -> people = true
                else -> error("unknown argument ${args[k]}")
            }
            k++
        }
        // With --people, the bands' moves are copied too (and only theirs).
        val kinds = if (people) listOf(Catalogue.HUMAN) else Catalogue.herbivores.filter { it in Catalogue.migratory }
        val run = HexLearning.Run(cfg, (Catalogue.herbivores.filter { it in Catalogue.migratory } + hunters + kinds).distinct(), mountains = true)
        val recorder = HexLearning.Recorder()
        for (s in kinds) run.groups.policies[s] = recorder
        repeat(years * Date.DAYS_PER_YEAR / cfg.daysPerStep) { run.step() }
        out.mkdirs()
        val bySpecies = recorder.data.groupBy({ it.first }, { it.second })
        println("recorded " + kinds.joinToString { "${it.name} ${bySpecies[it]?.size ?: 0}" })
        for (s in kinds) {
            val (net, report) = HexLearning.train(bySpecies[s] ?: emptyList(), Random(cfg.seed + s.index)) ?: continue
            net.save(HexLearning.file(out, s))
            println("%-12s %s".format(s.name, report))
        }
    }
}

/**
 * Evolves the migratory herds' networks on lean hex worlds (no hunters, no people): each
 * generation, every kind's groups in fresh small worlds are dealt variants of its network
 * round-robin, and each variant is scored by the animal-weeks its lineages live, per animal at the
 * start. Herds caught on the high ground when the snow comes die; those that learn the seasons
 * live.
 *
 * Usage: hex-evolve [--start DIR] [--out DIR] [--seeds 1,2,..] [--size KM] [--years N] [--gens N] [--pairs N] [--sigma S] [--rate R] [--hunters]
 *
 * Worlds are cool and mountainous; with --hunters the hunters live there too (hand-written), so the
 * herds also learn where they are safe.
 */
object HexEvolve {
    @JvmStatic
    fun main(args: Array<String>) {
        var start = File("hex-policies")
        var out = File("hex-evolve")
        var seeds = listOf(71L, 72L, 73L, 74L, 75L, 76L, 77L, 78L)
        var size = 600
        var years = 3
        var gens = 1000
        var pairs = 8
        var sigma = 0.02f
        var rate = 0.01f
        var hunters = emptyList<Species>()
        var k = 0
        while (k < args.size) {
            when (args[k]) {
                "--start" -> start = File(args[++k])
                "--out" -> out = File(args[++k])
                "--seeds" -> seeds = args[++k].split(",").map { it.toLong() }
                "--size" -> size = args[++k].toInt()
                "--years" -> years = args[++k].toInt()
                "--gens" -> gens = args[++k].toInt()
                "--pairs" -> pairs = args[++k].toInt()
                "--sigma" -> sigma = args[++k].toFloat()
                "--rate" -> rate = args[++k].toFloat()
                "--hunters" -> hunters = Catalogue.predators.filter { !it.human }
                else -> error("unknown argument ${args[k]}")
            }
            k++
        }
        out.mkdirs()
        // Every kind with a network in the start directory evolves: the migratory herds, and people.
        val kinds = (Catalogue.herbivores.filter { it in Catalogue.migratory } + Catalogue.HUMAN).filter { HexLearning.file(start, it).exists() }
        val centres = kinds.associateWith { s -> Mlp.load((HexLearning.file(out, s).takeIf { it.exists() } ?: HexLearning.file(start, s)).readText()) }
        val adams = kinds.associateWith { Adam(centres.getValue(it).w.size, rate) }
        val log = File(out, "log.txt")
        val firstGen = if (log.exists()) log.readLines().count { it.startsWith("gen ") } else 0
        val nv = 2 * pairs + 1
        for (gen in firstGen until firstGen + gens) {
            val t0 = System.nanoTime()
            val rnd = Random(gen * 7919L + 9)
            val eps = kinds.associateWith { s -> Array(pairs) { FloatArray(centres.getValue(s).w.size) { gaussian(rnd) } } }
            val variants = kinds.associateWith { s ->
                Array(nv) { v ->
                    val m = centres.getValue(s).copy()
                    if (v < 2 * pairs) { val sign = if (v % 2 == 0) 1f else -1f; val e = eps.getValue(s)[v / 2]; for (i in m.w.indices) m.w[i] += sign * sigma * e[i] }
                    m
                }
            }
            val results = seeds.parallelStream().map { seed -> evaluate(WorldConfig(seed = seed + 1000L * gen, widthKm = size, heightKm = size), years, variants, hunters) }.toList()
            val parts = ArrayList<String>()
            for (s in kinds) {
                val fitness = DoubleArray(nv)
                for (r in results) { val f = r.first.getValue(s); for (v in 0 until nv) fitness[v] += f[v] / results.size }
                val order = (0 until 2 * pairs).sortedBy { fitness[it] }
                val ranks = DoubleArray(2 * pairs); order.forEachIndexed { rank, i -> ranks[i] = rank.toDouble() / (2 * pairs - 1) - 0.5 }
                val centre = centres.getValue(s)
                val grad = FloatArray(centre.w.size)
                for (i in 0 until pairs) { val d = ranks[2 * i] - ranks[2 * i + 1]; val e = eps.getValue(s)[i]; for (j in grad.indices) grad[j] -= (d * e[j] / (2 * pairs * sigma)).toFloat() }
                adams.getValue(s).step(centre.w, grad)
                Schema.save(centre, HexLearning.file(out, s), Schema.TEACHER_IN, Schema.MOVER_OUT)
                if (gen % 10 == 9) Schema.save(centre, HexLearning.file(File(out, "gen${gen + 1}").also { it.mkdirs() }, s), Schema.TEACHER_IN, Schema.MOVER_OUT)
                parts += "%s %.2f/%.2f".format(s.name, fitness.take(2 * pairs).average(), fitness.last())
            }
            val line = "gen %d  %.0f s  fitness mean/centre: %s  | %s".format(gen, (System.nanoTime() - t0) / 1e9, parts.joinToString(", "), results.joinToString("  ") { it.second })
            println(line); log.appendText(line + "\n")
        }
    }

    /** One lean world for [years], each group of an evolving kind on one of its [variants]; animal-weeks per animal at the start. */
    fun evaluate(cfg: WorldConfig, years: Int, variants: Map<Species, Array<Mlp>>, hunters: List<Species> = emptyList()): Pair<Map<Species, DoubleArray>, String> {
        val run = HexLearning.Run(cfg, (Catalogue.herbivores.filter { it in Catalogue.migratory } + variants.keys + hunters).distinct(), mountains = true)
        val rnd = Random(cfg.seed)
        val started = variants.mapValues { DoubleArray(it.value.size) }
        for ((s, vs) in variants) run.groups.groups.filter { it.species == s }.shuffled(rnd).forEachIndexed { i, g -> g.tag = i % vs.size; started.getValue(s)[g.tag] += g.size.toDouble() }
        val nets = variants.mapValues { (_, vs) -> vs.map { HexLearning.NetPolicy(it) } }
        for (s in variants.keys) run.groups.policies[s] = HexPolicy { g, f, b, rnd -> nets.getValue(g.species)[g.tag.coerceIn(0, variants.getValue(g.species).size - 1)].choose(g, f, b, rnd) }
        val weeks = variants.mapValues { DoubleArray(it.value.size) }
        val start = variants.keys.associateWith { run.groups.count(it) }
        val steps = years * Date.DAYS_PER_YEAR / cfg.daysPerStep
        repeat(steps) {
            run.step()
            for (g in run.groups.groups) if (g.alive && g.tag >= 0) weeks[g.species]?.let { w -> if (g.tag < w.size) w[g.tag] += g.size.toDouble() }
        }
        val fitness = variants.mapValues { (s, vs) -> DoubleArray(vs.size) { v -> val st = started.getValue(s)[v]; if (st == 0.0) 0.0 else weeks.getValue(s)[v] / st / steps } }
        return fitness to "seed ${cfg.seed}: " + variants.keys.joinToString(" ") { "${it.name.split(' ').last()} ${start.getValue(it)}->${run.groups.count(it)}" }
    }

    private fun gaussian(rnd: Random): Float {
        var u: Double
        do u = rnd.nextDouble() while (u <= 1e-12)
        return (sqrt(-2.0 * ln(u)) * cos(2.0 * PI * rnd.nextDouble())).toFloat()
    }
}

/**
 * How the migratory herds use the heights through the year, with the networks in a directory (or
 * the hand-written behaviour without one): each month, the mean elevation of each kind's herds
 * and the share of them standing in deep snow, on a fresh lean world.
 *
 * Usage: hex-seasons [--nets DIR] [--seed N] [--size KM] [--years N]
 */
object HexSeasons {
    @JvmStatic
    fun main(args: Array<String>) {
        var cfg = WorldConfig(seed = 991, widthKm = 600, heightKm = 600)
        var nets: File? = null
        var hunters = emptyList<Species>()
        var years = 2
        var k = 0
        while (k < args.size) {
            when (args[k]) {
                "--nets" -> nets = File(args[++k])
                "--seed" -> cfg = cfg.copy(seed = args[++k].toLong())
                "--size" -> { val s = args[++k].toInt(); cfg = cfg.copy(widthKm = s, heightKm = s) }
                "--years" -> years = args[++k].toInt()
                "--hunters" -> hunters = Catalogue.predators.filter { !it.human }
                else -> error("unknown argument ${args[k]}")
            }
            k++
        }
        val kinds = Catalogue.herbivores.filter { it in Catalogue.migratory }
        val run = HexLearning.Run(cfg, kinds + hunters, mountains = true)
        nets?.let { dir -> for (s in kinds) HexLearning.file(dir, s).takeIf { it.exists() }?.let { run.groups.policies[s] = HexLearning.NetPolicy(Schema.load(it, Schema.TEACHER_IN, Schema.MOVER_OUT)) } }
        val stepsPerMonth = Date.DAYS_PER_MONTH / cfg.daysPerStep
        println("month " + kinds.joinToString("") { "%18s".format(it.name.take(16)) })
        repeat(years * 12) { m ->
            val elev = DoubleArray(kinds.size); val snow = DoubleArray(kinds.size); val n = DoubleArray(kinds.size); val fed = DoubleArray(kinds.size)
            repeat(stepsPerMonth) {
                run.step()
                for (g in run.groups.groups) {
                    if (!g.alive || g.species !in kinds) continue
                    val i = kinds.indexOf(g.species)
                    elev[i] += run.hw.placeElevationM[g.place] * g.size.toDouble(); n[i] += g.size.toDouble(); fed[i] += g.fed * g.size.toDouble()
                    if (run.weather.snowpack[g.place] > g.species.snowLimitMm) snow[i] += g.size.toDouble()
                }
            }
            println("%5d ".format(m % 12 + 1) + kinds.indices.joinToString("") { i -> if (n[i] > 0) "%7.0fm %4.0f%% f%3.0f".format(elev[i] / n[i], 100 * snow[i] / n[i], 100 * fed[i] / n[i]) else "%18s".format("-") } +
                "   count " + kinds.joinToString(" ") { "${run.groups.count(it)}" })
        }
        println("at the end: " + kinds.joinToString(", ") { "${it.name} ${run.groups.count(it)}" })
    }
}

/** Sets bands' ways with a network: inputs as [bandInputs], outputs as [setWays]. */
class NetWays(private val net: Mlp) : inflation.ecology.BandWays {
    private val buffers = ThreadLocal.withInitial { net.buffers() }
    override fun choose(g: HexGroup, inputs: FloatArray, out: FloatArray) {
        val b = buffers.get()
        inputs.copyInto(b.a[0])
        net.forwardAll(b).copyInto(out)
    }
}

/**
 * Evolves the bands' ways (their effort on each food, the stores they aim for, what they make
 * first) on small worlds with all the animals: each generation, the bands are dealt variants of
 * the network round-robin, and each variant is scored by the person-weeks its bands live, per
 * person at the start. It starts from the scaffold (a network of zeros chooses exactly as it does).
 *
 * Usage: hex-evolve-ways [--out DIR] [--seeds 1,2,..] [--size KM] [--years N] [--gens N] [--pairs N] [--sigma S] [--rate R] [--hidden N] [--nets DIR]
 *
 * With --nets, every kind moves by the learned role networks in DIR (herds.mlp, hunters.mlp,
 * people.mlp, each with its .identity, as [HexPpo] writes them) instead of the hand-written choices.
 */
object HexEvolveWays {
    /** The learned movers, by role, when given. */
    private var movers: List<SharedMover>? = null
    @JvmStatic
    fun main(args: Array<String>) {
        var out = File("hex-ways")
        var seeds = listOf(81L, 82L, 83L, 84L, 85L, 86L, 87L, 88L)
        var size = 600
        var years = 5
        var gens = 1000
        var pairs = 8
        var sigma = 0.05f
        var rate = 0.02f
        var hidden = 16
        var checkSeeds = listOf(91L, 92L, 93L, 94L, 95L, 96L, 97L, 98L)
        var k = 0
        while (k < args.size) {
            when (args[k]) {
                "--out" -> out = File(args[++k])
                "--seeds" -> seeds = args[++k].split(",").map { it.toLong() }
                "--size" -> size = args[++k].toInt()
                "--years" -> years = args[++k].toInt()
                "--gens" -> gens = args[++k].toInt()
                "--pairs" -> pairs = args[++k].toInt()
                "--sigma" -> sigma = args[++k].toFloat()
                "--rate" -> rate = args[++k].toFloat()
                "--hidden" -> hidden = args[++k].toInt()
                "--nets" -> { val dir = File(args[++k]); movers = HexPpo.ROLES.map { SharedMover.load(File(dir, "$it.mlp").also { f -> require(f.exists()) { "no $f" } }) } }
                else -> error("unknown argument ${args[k]}")
            }
            k++
        }
        out.mkdirs()
        val file = File(out, "ways.mlp")
        // A network of zeros in its last layer chooses as the scaffold does; the hidden layer starts small and random.
        val centre = if (file.exists()) Schema.load(file, Schema.WAYS_IN, Schema.WAYS_OUT) else Mlp.random(intArrayOf(inflation.ecology.WAYS_IN, hidden, inflation.ecology.WAYS_OUT), Random(1)).also { m ->
            val last = Mlp.count(intArrayOf(inflation.ecology.WAYS_IN, hidden)); for (i in last until m.w.size) m.w[i] = 0f
        }
        val adam = Adam(centre.w.size, rate)
        val log = File(out, "log.txt")
        val firstGen = if (log.exists()) log.readLines().count { it.startsWith("gen ") } else 0
        val nv = 2 * pairs + 1
        for (gen in firstGen until firstGen + gens) {
            val t0 = System.nanoTime()
            val rnd = Random(gen * 7919L + 17)
            val eps = Array(pairs) { FloatArray(centre.w.size) { gaussian(rnd) } }
            val variants = Array(nv) { v ->
                val m = centre.copy()
                if (v < 2 * pairs) { val sign = if (v % 2 == 0) 1f else -1f; val e = eps[v / 2]; for (i in m.w.indices) m.w[i] += sign * sigma * e[i] }
                m
            }
            val results = seeds.parallelStream().map { seed -> evaluate(WorldConfig(seed = seed + 1000L * gen, widthKm = size, heightKm = size), years, variants) }.toList()
            val fitness = DoubleArray(nv)
            for (r in results) for (v in 0 until nv) fitness[v] += r.first[v] / results.size
            val order = (0 until 2 * pairs).sortedBy { fitness[it] }
            val ranks = DoubleArray(2 * pairs); order.forEachIndexed { rank, i -> ranks[i] = rank.toDouble() / (2 * pairs - 1) - 0.5 }
            val grad = FloatArray(centre.w.size)
            for (i in 0 until pairs) { val d = ranks[2 * i] - ranks[2 * i + 1]; val e = eps[i]; for (j in grad.indices) grad[j] -= (d * e[j] / (2 * pairs * sigma)).toFloat() }
            adam.step(centre.w, grad)
            Schema.save(centre, file, Schema.WAYS_IN, Schema.WAYS_OUT)
            if (gen % 10 == 9) {
                Schema.save(centre, File(File(out, "gen${gen + 1}").also { it.mkdirs() }, "ways.mlp"), Schema.WAYS_IN, Schema.WAYS_OUT)
                // Against the scaffold on the same fixed worlds: the centre's bands and scaffold bands side by side.
                val scaffold = centre.copy().also { m -> val last = Mlp.count(intArrayOf(m.sizes[0], m.sizes[1])); for (i in last until m.w.size) m.w[i] = 0f }
                val check = checkSeeds.parallelStream().map { seed -> evaluate(WorldConfig(seed = seed, widthKm = size, heightKm = size), years, arrayOf(scaffold, centre)).first }.toList()
                val vs = "check gen %d: scaffold %.3f, learned %.3f".format(gen + 1, check.map { it[0] }.average(), check.map { it[1] }.average())
                println(vs); File(out, "check.txt").appendText(vs + "\n")
            }
            val line = "gen %d  %.0f s  fitness mean/centre: %.3f/%.3f  | %s".format(gen, (System.nanoTime() - t0) / 1e9, fitness.take(2 * pairs).average(), fitness.last(), results.joinToString("  ") { it.second })
            println(line); log.appendText(line + "\n")
        }
    }

    /** One small world with all the animals for [years], each band on one of [variants]; person-weeks per person at the start. */
    fun evaluate(cfg: WorldConfig, years: Int, variants: Array<Mlp>): Pair<DoubleArray, String> {
        val run = HexLearning.Run(cfg, Catalogue.all.filter { it in Catalogue.migratory || it.carnivore }.distinct())
        movers?.let { ms -> val ps = ms.map { MoverPolicy(it, null, { 0f }, { 0 }) }; for (s in Catalogue.all) run.groups.policies[s] = ps[HexPpo.role(s)] }
        val rnd = Random(cfg.seed)
        val people = Catalogue.HUMAN
        val started = DoubleArray(variants.size)
        run.groups.groups.filter { it.species == people }.shuffled(rnd).forEachIndexed { i, g -> g.tag = i % variants.size; started[g.tag] += g.size.toDouble() }
        val nets = variants.map { NetWays(it) }
        run.groups.ways = inflation.ecology.BandWays { g, inp, o -> nets[g.tag.coerceIn(0, nets.size - 1)].choose(g, inp, o) }
        val weeks = DoubleArray(variants.size)
        val start = run.groups.count(people)
        val steps = years * Date.DAYS_PER_YEAR / cfg.daysPerStep
        repeat(steps) {
            run.step()
            for (g in run.groups.groups) if (g.alive && g.tag >= 0 && g.species == people && g.tag < weeks.size) weeks[g.tag] += g.size.toDouble()
        }
        val fitness = DoubleArray(variants.size) { v -> if (started[v] == 0.0) 0.0 else weeks[v] / started[v] / steps }
        return fitness to "seed ${cfg.seed}: people $start->${run.groups.count(people)}"
    }

    private fun gaussian(rnd: Random): Float {
        var u: Double
        do u = rnd.nextDouble() while (u <= 1e-12)
        return (sqrt(-2.0 * ln(u)) * cos(2.0 * PI * rnd.nextDouble())).toFloat()
    }
}
