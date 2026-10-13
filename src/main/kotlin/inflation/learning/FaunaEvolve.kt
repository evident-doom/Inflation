package inflation.learning

import inflation.ecology.AnimalGroup
import inflation.ecology.Catalogue
import inflation.ecology.Option
import inflation.ecology.Policy
import inflation.ecology.Species
import inflation.world.Date
import inflation.world.Simulation
import inflation.world.World
import inflation.world.WorldConfig
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Evolves the networks of several species together: the migratory herds and the hunters, so that
 * each adapts to the others as they change. Each generation draws, for every species, [pairs]
 * random directions in weight space and makes a plus and a minus variant of each, plus the
 * unchanged centre. In every world the groups of each evolving species are dealt that species'
 * variants round-robin, and a group's descendants keep its variant, so one world run scores the
 * variants of all species at once. A variant's fitness is the animal-weeks its lineages live, per
 * animal it started with: survival and young over the years, with no reward for any particular
 * way of living. Each species' weights then move towards its better variants, by rank, with Adam.
 * Species not evolving keep the hand-written behaviour (resident plant-eaters keep to their ranges
 * and need no guide).
 *
 * Usage: fauna-evolve [--start DIR] [--out DIR] [--species a,b,c] [--seeds 1,2,3,4] [--size KM]
 *                     [--years N] [--gens N] [--pairs N] [--sigma S] [--rate R] [--without a,b|hunters]
 *
 * --without leaves kinds out of the worlds ("hunters" for every meat-eater, people included): a
 * lean world in which herds can learn the seasons fast.
 */
object FaunaEvolve {
    /** The species evolved by default: the migratory herds and the hunters other than people. */
    val DEFAULT = Catalogue.migratory.toList() + Catalogue.predators.filter { !it.human }

    @JvmStatic
    fun main(args: Array<String>) {
        var start = NetworkPolicy.DIR
        var out = File("fauna-evolve")
        var species = DEFAULT
        var seeds = listOf(41L, 42L, 43L, 44L)
        var size = 600
        var years = 3
        var gens = 1000
        var pairs = 8
        var sigma = 0.02f
        var rate = 0.01f
        var without = emptySet<Species>()
        var k = 0
        while (k < args.size) {
            when (args[k]) {
                "--start" -> start = File(args[++k])
                "--out" -> out = File(args[++k])
                "--species" -> species = args[++k].split(",").map { n -> Catalogue.all.first { it.name == n.replace('-', ' ') } }
                "--seeds" -> seeds = args[++k].split(",").map { it.toLong() }
                "--size" -> size = args[++k].toInt()
                "--years" -> years = args[++k].toInt()
                "--gens" -> gens = args[++k].toInt()
                "--pairs" -> pairs = args[++k].toInt()
                "--sigma" -> sigma = args[++k].toFloat()
                "--rate" -> rate = args[++k].toFloat()
                "--without" -> without = kinds(args[++k])
                else -> error("unknown argument ${args[k]}")
            }
            k++
        }
        out.mkdirs()
        // Resume from the last saved networks where there are any.
        val centres = species.associateWith { s ->
            val resume = NetworkPolicy.file(out, s)
            Mlp.load((if (resume.exists()) resume else NetworkPolicy.file(start, s)).readText())
        }
        val adams = species.associateWith { Adam(centres.getValue(it).w.size, rate) }
        val log = File(out, "log.txt")
        val firstGen = if (log.exists()) log.readLines().count { it.startsWith("gen ") } else 0
        val nv = 2 * pairs + 1
        for (gen in firstGen until firstGen + gens) {
            val t0 = System.nanoTime()
            val rnd = Random(gen * 7919L + 5)
            val eps = species.associateWith { s -> Array(pairs) { FloatArray(centres.getValue(s).w.size) { gaussian(rnd) } } }
            val variants = species.associateWith { s ->
                Array(nv) { v ->
                    val m = centres.getValue(s).copy()
                    if (v < 2 * pairs) {
                        val sign = if (v % 2 == 0) 1f else -1f
                        val e = eps.getValue(s)[v / 2]
                        for (i in m.w.indices) m.w[i] += sign * sigma * e[i]
                    }
                    m
                }
            }
            // Fresh worlds each generation, so the networks are not fitted to one map.
            val results = seeds.parallelStream().map { seed ->
                evaluate(WorldConfig(seed = seed + 1000L * gen, widthKm = size, heightKm = size), years, variants, without)
            }.toList()
            val parts = ArrayList<String>()
            for (s in species) {
                val fitness = DoubleArray(nv)
                for (r in results) { val f = r.fitness.getValue(s); for (v in 0 until nv) fitness[v] += f[v] / results.size }
                val ranks = centredRanks(fitness.copyOf(2 * pairs))
                val centre = centres.getValue(s)
                val grad = FloatArray(centre.w.size)
                for (i in 0 until pairs) {
                    val d = ranks[2 * i] - ranks[2 * i + 1]
                    val e = eps.getValue(s)[i]
                    for (j in grad.indices) grad[j] -= (d * e[j] / (2 * pairs * sigma)).toFloat()
                }
                adams.getValue(s).step(centre.w, grad)
                centre.save(NetworkPolicy.file(out, s))
                if (gen % 10 == 9) centre.save(NetworkPolicy.file(File(out, "gen${gen + 1}").also { it.mkdirs() }, s))
                parts += "%s %.2f/%.2f".format(s.name, fitness.take(2 * pairs).average(), fitness.last())
            }
            val line = "gen %d  %.0f s  fitness mean/centre: %s  | %s".format(
                gen, (System.nanoTime() - t0) / 1e9, parts.joinToString(", "), results.joinToString("  ") { it.summary })
            println(line)
            log.appendText(line + "\n")
        }
    }

    class Result(val fitness: Map<Species, DoubleArray>, val summary: String)

    /** Runs one world for [years] with each group of an evolving species on one of its [variants]. */
    fun evaluate(cfg: WorldConfig, years: Int, variants: Map<Species, Array<Mlp>>, without: Set<Species> = emptySet()): Result {
        val sim = Simulation(World.generate(cfg))
        val e = sim.ecology
        leaveOut(e, without)
        val rnd = Random(cfg.seed)
        val started = variants.mapValues { DoubleArray(it.value.size) }
        val start = variants.keys.associateWith { e.count(it) }
        for ((s, vs) in variants) {
            e.groups.filter { it.species == s }.shuffled(rnd).forEachIndexed { i, g -> g.tag = i % vs.size; started.getValue(s)[g.tag] += g.size.toDouble() }
        }
        val fallback = e.policies.getValue(Catalogue.GAZELLE)
        val policies = variants.mapValues { (s, vs) -> vs.map { NetworkPolicy(mapOf(s to it), e::noise, fallback) } }
        val chooser = object : Policy {
            override fun choose(group: AnimalGroup, options: List<Option>): Int {
                val ps = policies[group.species]
                return if (ps != null && group.tag in ps.indices) ps[group.tag].choose(group, options) else fallback.choose(group, options)
            }
        }
        for (s in variants.keys) e.policies[s] = chooser
        val weeks = variants.mapValues { DoubleArray(it.value.size) }
        val steps = years * Date.DAYS_PER_YEAR / cfg.daysPerStep
        repeat(steps) {
            sim.step()
            for (g in e.groups) if (g.alive && g.tag >= 0) weeks[g.species]?.let { w -> if (g.tag < w.size) w[g.tag] += g.size.toDouble() }
        }
        val fitness = variants.mapValues { (s, vs) ->
            DoubleArray(vs.size) { v -> val st = started.getValue(s)[v]; if (st == 0.0) 0.0 else weeks.getValue(s)[v] / st / steps }
        }
        val pops = variants.keys.joinToString(" ") { "${it.name.split(' ').last()} ${start.getValue(it)}->${e.count(it)}" }
        return Result(fitness, "seed ${cfg.seed}: $pops")
    }

    /** Species named in [list] (comma-separated, '-' for spaces); "hunters" stands for every meat-eater, people included. */
    fun kinds(list: String): Set<Species> = list.split(",").flatMap { n ->
        if (n == "hunters") Catalogue.predators else listOf(Catalogue.all.first { it.name == n.replace('-', ' ') })
    }.toSet()

    /** Empties the world of [kinds]. */
    fun leaveOut(e: inflation.ecology.Ecology, kinds: Set<Species>) {
        for (g in e.groups) if (g.species in kinds) { g.adults = 0; g.young = 0 }
    }

    private fun gaussian(rnd: Random): Float {
        var u: Double
        do u = rnd.nextDouble() while (u <= 1e-12)
        return (sqrt(-2.0 * ln(u)) * cos(2.0 * PI * rnd.nextDouble())).toFloat()
    }

    private fun centredRanks(x: DoubleArray): DoubleArray {
        val order = x.indices.sortedBy { x[it] }
        val r = DoubleArray(x.size)
        order.forEachIndexed { rank, i -> r[i] = rank.toDouble() / (x.size - 1) - 0.5 }
        return r
    }
}
