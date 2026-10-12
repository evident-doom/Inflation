package inflation.learning

import inflation.ecology.AnimalGroup
import inflation.ecology.Catalogue
import inflation.ecology.Option
import inflation.ecology.Policy
import inflation.world.Date
import inflation.world.Simulation
import inflation.world.World
import inflation.world.WorldConfig
import java.io.File
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Evolves the wolves' network by evolution strategies. Each generation draws [pairs] random
 * directions in weight space and makes two variants per direction (plus and minus, which cancels
 * much of the luck). Every pack in a world is given one variant, so one world run compares all of
 * them at once; packs that split pass their variant on. A variant's fitness is the wolf-days its
 * lineages live, per pack it started with: survival and offspring over years, with no reward for
 * any particular way of hunting. The weights then move toward the better variants, by rank.
 * Prey keep the baseline behaviour.
 *
 * Usage: evolve [--start DIR] [--out DIR] [--seeds 7,8,9,10] [--size KM] [--years N]
 *               [--gens N] [--pairs N] [--sigma S] [--rate R]
 */
fun main(args: Array<String>) {
    var start = NetworkPolicy.DIR
    var out = File("evolve-out")
    var seeds = listOf(7L, 8L, 9L, 10L)
    var size = 400
    var years = 3
    var gens = 100
    var pairs = 12
    var sigma = 0.02f
    var rate = 0.01f
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
            else -> error("unknown argument ${args[k]}")
        }
        k++
    }
    val wolf = Catalogue.WOLF
    val resume = NetworkPolicy.file(out, wolf)
    val centre = Mlp.load((if (resume.exists()) resume else NetworkPolicy.file(start, wolf)).readText())
    val log = File(out, "log.txt").also { it.parentFile.mkdirs() }
    val firstGen = if (log.exists()) log.readLines().count { it.startsWith("gen ") } else 0
    val adam = Adam(centre.w.size, rate)
    for (gen in firstGen until firstGen + gens) {
        val t0 = System.nanoTime()
        val rnd = Random(gen * 7919L + 1)
        val eps = Array(pairs) { FloatArray(centre.w.size) { gaussian(rnd) } }
        // Variant 2i is centre + sigma eps_i, 2i+1 is centre - sigma eps_i; the last is the centre itself.
        val variants = Array(2 * pairs + 1) { v ->
            val m = centre.copy()
            if (v < 2 * pairs) {
                val sign = if (v % 2 == 0) 1f else -1f
                val e = eps[v / 2]
                for (i in m.w.indices) m.w[i] += sign * sigma * e[i]
            }
            m
        }
        // Each world is generated anew: the simulation changes the world it runs on.
        val results = seeds.parallelStream().map { seed ->
            evaluate(WorldConfig(seed = seed + 1000L * gen, widthKm = size, heightKm = size), years, variants)
        }.toList()
        val fitness = DoubleArray(variants.size)
        for (r in results) for (v in fitness.indices) fitness[v] += r.fitness[v]
        // Centred ranks: robust to the odd lucky lineage.
        val ranks = centredRanks(fitness.copyOf(2 * pairs))
        val grad = FloatArray(centre.w.size)
        for (i in 0 until pairs) {
            val d = ranks[2 * i] - ranks[2 * i + 1]
            val e = eps[i]
            for (j in grad.indices) grad[j] -= (d * e[j] / (2 * pairs * sigma)).toFloat() // Adam descends
        }
        adam.step(centre.w, grad)
        centre.save(resume)
        val mean = fitness.take(2 * pairs).average()
        val line = "gen %d  %.0f s  fitness mean %.1f best %.1f centre %.1f  | %s".format(
            gen, (System.nanoTime() - t0) / 1e9, mean, fitness.take(2 * pairs).max(), fitness.last(),
            results.joinToString("  ") { it.summary })
        println(line)
        log.appendText(line + "\n")
        if (gen % 10 == 9) centre.save(File(out, "wolf-gen${gen + 1}.mlp"))
    }
}

class Evaluation(val fitness: DoubleArray, val summary: String)

/** Runs one world for [years] with each wolf pack on one of [variants]; prey use the baseline. */
fun evaluate(cfg: WorldConfig, years: Int, variants: Array<Mlp>): Evaluation {
    val sim = Simulation(World.generate(cfg))
    val e = sim.ecology
    val wolf = Catalogue.WOLF
    val baseline = e.policies.getValue(wolf)
    val rnd = Random(cfg.seed)
    val packs = e.groups.filter { it.species == wolf }.shuffled(rnd)
    val started = IntArray(variants.size)
    packs.forEachIndexed { i, g -> g.tag = i % variants.size; started[g.tag]++ }
    val policy = VariantPolicy(variants, e::noise, baseline)
    e.policies[wolf] = policy
    val wolfDays = DoubleArray(variants.size)
    val steps = years * Date.DAYS_PER_YEAR / cfg.daysPerStep
    val prey0 = Catalogue.herbivores.sumOf { e.count(it) }
    repeat(steps) {
        sim.step()
        for (g in e.groups) if (g.species == wolf && g.tag >= 0) wolfDays[g.tag] += g.size.toDouble() * cfg.daysPerStep
    }
    val fitness = DoubleArray(variants.size) { if (started[it] == 0) 0.0 else wolfDays[it] / started[it] / (years * Date.DAYS_PER_YEAR) }
    val prey = Catalogue.herbivores.sumOf { e.count(it) }
    return Evaluation(fitness, "seed %d: %d packs, wolves %d, prey %d -> %d".format(cfg.seed, packs.size, e.count(wolf), prey0, prey))
}

/** Wolves pick with the variant their lineage carries; groups without one use [fallback]. */
class VariantPolicy(private val variants: Array<Mlp>, noise: (AnimalGroup, Int) -> Float, private val fallback: Policy) : Policy {
    private val policies = variants.map { NetworkPolicy(mapOf(Catalogue.WOLF to it), noise, fallback) }

    override fun choose(group: AnimalGroup, options: List<Option>): Int =
        if (group.tag in variants.indices) policies[group.tag].choose(group, options) else fallback.choose(group, options)
}

private fun gaussian(rnd: Random): Float {
    var u: Double
    do u = rnd.nextDouble() while (u <= 1e-12)
    return (sqrt(-2.0 * kotlin.math.ln(u)) * kotlin.math.cos(2.0 * Math.PI * rnd.nextDouble())).toFloat()
}

private fun centredRanks(x: DoubleArray): DoubleArray {
    val order = x.indices.sortedBy { x[it] }
    val r = DoubleArray(x.size)
    order.forEachIndexed { rank, i -> r[i] = rank.toDouble() / (x.size - 1) - 0.5 }
    return r
}
