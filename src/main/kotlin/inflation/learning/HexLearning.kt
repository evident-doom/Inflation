package inflation.learning

import inflation.Args
import inflation.ecology.Catalogue
import inflation.ecology.HexFeatures
import inflation.ecology.HexGroup
import inflation.ecology.HexPolicy
import inflation.ecology.HexSim
import inflation.ecology.Species
import inflation.world.Date
import inflation.world.World
import inflation.world.WorldConfig
import java.io.File
import java.util.Arrays
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random
import inflation.ecology.AnimalWay
import inflation.ecology.AnimalWays
import inflation.ecology.BandWays

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

    /** One kept decision: the group's kind and id, and the candidates' inputs and hand-written scores. */
    class Recorded(val species: Species, val groupId: Int, val rec: FloatArray)

    /** Chooses by the hand-written score, keeping a sample of decisions to copy. */
    class Recorder : HexPolicy {
        val data = ConcurrentLinkedQueue<Recorded>()

        /**
         * The kept decisions in an order that does not depend on which core recorded them first: by group,
         * then by content (so that the same run always trains the same network).
         */
        fun inOrder(): List<Recorded> = data.sortedWith { a, b -> if (a.groupId != b.groupId) a.groupId.compareTo(b.groupId) else Arrays.compare(a.rec, b.rec) }

        override fun choose(g: HexGroup, features: Array<FloatArray>, baseline: FloatArray, rnd: kotlin.random.Random): Int {
            var best = 0
            for (k in baseline.indices) if (baseline[k] > baseline[best]) best = k
            if (rnd.nextFloat() < 0.3f && data.size < 400_000) {
                // The chosen one, staying put, and a random sample of the rest.
                val pick = LinkedHashSet<Int>(); pick += best
                features.indices.firstOrNull { features[it][HexFeatures.STAYS] > 0.5f }?.let { pick += it }
                val rest = features.indices.shuffled(rnd).iterator()
                while (pick.size < minOf(SLOTS, features.size) && rest.hasNext()) pick += rest.next()
                val rec = FloatArray(SLOTS * STRIDE)
                for ((j, k) in pick.withIndex()) {
                    val at = j * STRIDE
                    rec[at] = 1f
                    features[k].copyInto(rec, at + 1)
                    rec[at + 1 + HexFeatures.SIZE] = baseline[k]
                }
                data += Recorded(g.species, g.id, rec)
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
 * Usage: hex-imitate [--seed N] [--size KM] [--years N] [--out DIR] [--hunters] [--people]
 *
 * Worlds are cool and mountainous (see [HexLearning.mountainWorld]); with --hunters the hunters live
 * there too, by their hand-written behaviour.
 */
object HexImitate {
    @JvmStatic
    fun main(args: Array<String>) {
        val a = Args(args, "hex-imitate [--seed N] [--size KM] [--years N] [--out DIR] [--hunters] [--people]")
        val base = WorldConfig(seed = 7, widthKm = 600, heightKm = 600)
        val side = a.int("--size", base.widthKm)
        val cfg = base.copy(seed = a.long("--seed", base.seed), widthKm = side, heightKm = side)
        val years = a.int("--years", 3)
        val out = a.file("--out") ?: File("hex-policies")
        val hunters = if (a.flag("--hunters")) Catalogue.predators.filter { !it.human && it !in Catalogue.residentHunters } else emptyList()
        val people = a.flag("--people")
        a.done()
        // With --people, the bands' moves are copied too (and only theirs).
        val kinds = if (people) listOf(Catalogue.HUMAN) else Catalogue.herbivores.filter { it in Catalogue.migratory }
        val run = HexLearning.Run(cfg, (Catalogue.herbivores.filter { it in Catalogue.migratory } + hunters + kinds).distinct(), mountains = true)
        val recorder = HexLearning.Recorder()
        for (s in kinds) run.groups.policies[s] = recorder
        repeat(years * Date.DAYS_PER_YEAR / cfg.daysPerStep) { run.step() }
        out.mkdirs()
        val bySpecies = recorder.inOrder().groupBy({ it.species }, { it.rec })
        println("recorded " + kinds.joinToString { "${it.name} ${bySpecies[it]?.size ?: 0}" })
        for (s in kinds) {
            val (net, report) = HexLearning.train(bySpecies[s] ?: emptyList(), Random(cfg.seed + s.index)) ?: continue
            net.save(HexLearning.file(out, s))
            println("%-12s %s".format(s.name, report))
        }
    }
}

/**
 * How the migratory herds use the heights through the year, with the networks in a directory (or
 * the hand-written behaviour without one): each month, the mean elevation of each kind's herds
 * and the share of them standing in deep snow, on a fresh lean world.
 *
 * Usage: hex-seasons [--nets DIR] [--seed N] [--size KM] [--years N] [--hunters]
 */
object HexSeasons {
    @JvmStatic
    fun main(args: Array<String>) {
        val a = Args(args, "hex-seasons [--nets DIR] [--seed N] [--size KM] [--years N] [--hunters]")
        val base = WorldConfig(seed = 991, widthKm = 600, heightKm = 600)
        val nets = a.file("--nets")
        val side = a.int("--size", base.widthKm)
        val cfg = base.copy(seed = a.long("--seed", base.seed), widthKm = side, heightKm = side)
        val years = a.int("--years", 2)
        val hunters = if (a.flag("--hunters")) Catalogue.predators.filter { !it.human && it !in Catalogue.residentHunters } else emptyList()
        a.done()
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
class NetWays(private val net: Mlp) : BandWays {
    // Not saved (a world saved with these ways comes back with none), so made again on first use.
    @Transient private var buffers: ThreadLocal<Mlp.Buffers>? = null
    override fun choose(g: HexGroup, inputs: FloatArray, out: FloatArray) {
        val b = (buffers ?: ThreadLocal.withInitial { net.buffers() }.also { buffers = it }).get()
        inputs.copyInto(b.a[0])
        net.forwardAll(b).copyInto(out)
    }
}

/** Sets herds' or packs' ways with a network: inputs as [inflation.ecology.animalInputs], outputs as [AnimalWay]. */
class NetAnimalWays(private val net: Mlp) : AnimalWays {
    private val buffers = ThreadLocal.withInitial { net.buffers() }
    override fun choose(g: HexGroup, inputs: FloatArray, out: FloatArray) {
        val b = buffers.get()
        inputs.copyInto(b.a[0])
        net.forwardAll(b).copyInto(out)
    }
}
