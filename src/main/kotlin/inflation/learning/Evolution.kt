package inflation.learning

import inflation.ecology.Species
import inflation.run.freshSim
import inflation.ecology.HexSim
import inflation.Args
import inflation.Config
import inflation.ecology.AnimalWays
import inflation.ecology.BandWays
import inflation.ecology.Catalogue
import inflation.ecology.HexGroup
import inflation.world.WorldConfig
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random
import inflation.ecology.AnimalWay

/** A standard normal draw (Box-Muller). */
internal fun gaussian(rnd: Random): Float {
    var u: Double
    do u = rnd.nextDouble() while (u <= 1e-12)
    return (sqrt(-2.0 * ln(u)) * cos(2.0 * PI * rnd.nextDouble())).toFloat()
}

/**
 * Evolution strategies with mirrored noise: each generation a network is tried as [pairs] pairs of
 * variants, the centre plus and minus [sigma] times a draw of noise, and the centre itself last; the
 * pairs' fitness, by rank, says which way to move the centre, and an [Adam] step moves it.
 */
class EvolutionStrategy(val pairs: Int, val sigma: Float) {
    /** Variants a generation: the pairs, then the centre. */
    val count = 2 * pairs + 1

    /** This generation's noise for a network of [size] weights. */
    fun noise(rnd: Random, size: Int) = Array(pairs) { FloatArray(size) { gaussian(rnd) } }

    fun variants(centre: Mlp, eps: Array<FloatArray>) = Array(count) { v ->
        val m = centre.copy()
        if (v < 2 * pairs) { val sign = if (v % 2 == 0) 1f else -1f; val e = eps[v / 2]; for (i in m.w.indices) m.w[i] += sign * sigma * e[i] }
        m
    }

    /** Moves [centre] by the pairs' [fitness] (the centre's, last, is not used). */
    fun step(centre: Mlp, eps: Array<FloatArray>, fitness: DoubleArray, adam: Adam) {
        val order = (0 until 2 * pairs).sortedBy { fitness[it] }
        val ranks = DoubleArray(2 * pairs); order.forEachIndexed { rank, i -> ranks[i] = rank.toDouble() / (2 * pairs - 1) - 0.5 }
        val grad = FloatArray(centre.w.size)
        for (i in 0 until pairs) { val d = ranks[2 * i] - ranks[2 * i + 1]; val e = eps[i]; for (j in grad.indices) grad[j] -= (d * e[j] / (2 * pairs * sigma)).toFloat() }
        adam.step(centre.w, grad)
    }
}

/**
 * Evolves the bands' ways (their effort on each food, the stores they aim for, what they make
 * first) on small worlds with all the animals: each generation, the bands are dealt variants of
 * the network round-robin, and each variant is scored by the person-weeks its bands live, per
 * person at the start. It starts from the scaffold (a network of zeros chooses exactly as it does).
 *
 * With --role hunters it evolves the packs' ways instead ([AnimalWay], in hunter-ways.mlp), each variant
 * scored by the head-weeks its groups live per head at the start, averaged over the kinds of packs (so the
 * many jackals do not outweigh the few lions).
 *
 * Usage: hex-evolve-ways [--role bands|hunters] [--out DIR] [--seeds 1,2,..] [--size KM] [--years N] [--gens N] [--pairs N] [--nets DIR]
 *
 * Config keys: `evolveWays.sigma` (default 0.05), `evolveWays.rate` (0.02) and `evolveWays.hidden` (16).
 *
 * With --nets, every kind moves by the learned role networks in DIR (hunters.mlp and
 * people.mlp, each with its .identity, as [HexPpo] writes them) instead of the hand-written choices.
 */
object HexEvolveWays {
    @JvmStatic
    fun main(args: Array<String>) {
        val a = Args(args, "hex-evolve-ways [--role bands|hunters] [--out DIR] [--seeds 1,2,..] [--size KM] [--years N] [--gens N] [--pairs N] [--nets DIR]")
        val out = a.file("--out") ?: File("hex-ways")
        val role = requireNotNull(WaysKind.named(a.string("--role") ?: WaysKind.BANDS.label)) { "--role is bands or hunters" }
        val seeds = a.string("--seeds")?.split(",")?.map { it.toLong() } ?: listOf(81L, 82L, 83L, 84L, 85L, 86L, 87L, 88L)
        val size = a.int("--size", 600)
        val years = a.int("--years", 5)
        val gens = a.int("--gens", 1000)
        val es = EvolutionStrategy(a.int("--pairs", 8), Config.float("evolveWays.sigma", 0.05f))
        val rate = Config.float("evolveWays.rate", 0.02f)
        val hidden = Config.int("evolveWays.hidden", 16)
        val movers = a.file("--nets")?.let { dir -> Role.entries.map { SharedMover.load(it.file(dir).also { f -> require(f.exists()) { "no $f" } }) } }
        a.done()
        WaysEvolution(role, movers).run(out, seeds, size, years, gens, es, rate, hidden)
    }
}

/** [HexEvolveWays] for one [role]'s ways, every kind moving by [movers] (by role) if given. */
class WaysEvolution(private val role: WaysKind, private val movers: List<SharedMover>?) {
    private val inNames = role.inNames
    private val outNames = role.outNames
    private val fileName = role.file
    private val checkSeeds = listOf(91L, 92L, 93L, 94L, 95L, 96L, 97L, 98L)

    fun run(out: File, seeds: List<Long>, size: Int, years: Int, gens: Int, es: EvolutionStrategy, rate: Float, hidden: Int) {
        out.mkdirs()
        val file = File(out, fileName)
        // A network of zeros in its last layer chooses as the scaffold does; the hidden layer starts small and random.
        val centre = if (file.exists()) Schema.load(file, inNames, outNames) else Mlp.random(intArrayOf(inNames.size, hidden, outNames.size), Random(1)).zeroOutputLayer()
        val adam = Adam(centre.w.size, rate)
        val log = RunLog(out, "gen")
        val firstGen = log.done()
        for (gen in firstGen until firstGen + gens) {
            val t0 = System.nanoTime()
            val rnd = Random(gen * 7919L + 17)
            val eps = es.noise(rnd, centre.w.size)
            val variants = es.variants(centre, eps)
            val results = seeds.parallelStream().map { seed -> evaluate(WorldConfig(seed = seed + 1000L * gen, widthKm = size, heightKm = size), years, variants) }.toList()
            val fitness = DoubleArray(es.count)
            for (r in results) for (v in 0 until es.count) fitness[v] += r.fitness[v] / results.size
            es.step(centre, eps, fitness, adam)
            Schema.save(centre, file, inNames, outNames)
            if (gen % 10 == 9) {
                Schema.save(centre, File(File(out, "gen${gen + 1}").also { it.mkdirs() }, fileName), inNames, outNames)
                // Against the scaffold on the same fixed worlds: the centre's bands and scaffold bands side by side.
                val scaffold = centre.copy().zeroOutputLayer()
                val check = checkSeeds.parallelStream().map { seed -> evaluate(WorldConfig(seed = seed, widthKm = size, heightKm = size), years, arrayOf(scaffold, centre)).fitness }.toList()
                val vs = "check gen %d: scaffold %.3f, learned %.3f".format(gen + 1, check.map { it[0] }.average(), check.map { it[1] }.average())
                log.check(vs)
            }
            val line = "gen %d  %.0f s  fitness mean/centre: %.3f/%.3f  | %s".format(gen, (System.nanoTime() - t0) / 1e9, fitness.take(2 * es.pairs).average(), fitness.last(), results.joinToString("  ") { it.note })
            log.write(line)
        }
    }

    /** One small world with all the animals for [years], each band (or pack, by the role) on one of [variants]; see [dealt]. */
    /** Each variant's [fitness] on one world, and a [note] on how the world went. */
    class Result(val fitness: DoubleArray, val note: String)

    fun evaluate(cfg: WorldConfig, years: Int, variants: Array<Mlp>): Result {
        val run = freshSim(cfg, Catalogue.learned.distinct())
        movers?.let { ms -> val ps = ms.map { MoverPolicy(it, null, { 0f }, { 0 }) }; for (s in Catalogue.learned) run.groups.policies[s] = ps[Role.of(s).ordinal] }
        val steps = years * cfg.stepsPerYear
        if (role == WaysKind.BANDS) {
            val people = Catalogue.HUMAN
            val nets = variants.map { NetWays(it) }
            run.groups.ways = BandWays { g, inp, o -> nets[g.tag.coerceIn(0, nets.size - 1)].choose(g, inp, o) }
            val start = run.groups.count(people)
            val (fitness, _) = dealt(run, Random(cfg.seed), variants.size, steps) { it.species == people }
            return Result(fitness, "seed ${cfg.seed}: people $start->${run.groups.count(people)}")
        }
        val nets = variants.map { NetAnimalWays(it) }
        run.groups.hunterWays = AnimalWays { g, inp, o -> nets[g.tag.coerceIn(0, nets.size - 1)].choose(g, inp, o) }
        val (fitness, started) = dealt(run, Random(cfg.seed), variants.size, steps) { !it.species.human }
        return Result(fitness, "seed ${cfg.seed}: " + started.entries.joinToString(", ") { (s, st) -> "${s.name} ${st.sum().toInt()}->${run.groups.count(s)}" })
    }
}

/**
 * Deals [variants] round-robin among the groups [mine] picks, within each kind (so each kind is weighed
 * on every variant), shuffled by [rnd], through each group's tag; runs [steps] steps; and scores each
 * variant by the head-weeks its groups live per head at the start, the mean over the kinds. Also the
 * heads each kind started with on each variant.
 */
internal fun dealt(run: HexSim, rnd: Random, variants: Int, steps: Int, mine: (HexGroup) -> Boolean): Pair<DoubleArray, Map<Species, DoubleArray>> {
    val kinds = run.groups.groups.filter { mine(it) }.map { it.species }.distinct().sortedBy { it.index }
    val started = kinds.associateWith { DoubleArray(variants) }
    val weeks = kinds.associateWith { DoubleArray(variants) }
    for (s in kinds) run.groups.groups.filter { it.species == s }.shuffled(rnd).forEachIndexed { i, g -> g.tag = i % variants; started.getValue(s)[g.tag] += g.size.toDouble() }
    repeat(steps) {
        run.step()
        for (g in run.groups.groups) if (g.alive && g.tag >= 0 && g.tag < variants && mine(g)) weeks[g.species]?.let { it[g.tag] += g.size.toDouble() }
    }
    val fitness = DoubleArray(variants) { v ->
        val per = kinds.filter { started.getValue(it)[v] > 0.0 }.map { weeks.getValue(it)[v] / started.getValue(it)[v] / steps }
        if (per.isEmpty()) 0.0 else per.average()
    }
    return fitness to started
}
