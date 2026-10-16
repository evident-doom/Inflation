package inflation.learning

import inflation.Args
import inflation.ecology.AnimalWays
import inflation.ecology.BandWays
import inflation.ecology.Catalogue
import inflation.ecology.HexGroup
import inflation.ecology.HexPolicy
import inflation.ecology.Species
import inflation.world.Date
import inflation.world.WorldConfig
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

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
        val a = Args(args, "hex-evolve [--start DIR] [--out DIR] [--seeds 1,2,..] [--size KM] [--years N] [--gens N] [--pairs N] [--sigma S] [--rate R] [--hunters]")
        val start = a.file("--start") ?: File("hex-policies")
        val out = a.file("--out") ?: File("hex-evolve")
        val seeds = a.string("--seeds")?.split(",")?.map { it.toLong() } ?: listOf(71L, 72L, 73L, 74L, 75L, 76L, 77L, 78L)
        val size = a.int("--size", 600)
        val years = a.int("--years", 3)
        val gens = a.int("--gens", 1000)
        val es = EvolutionStrategy(a.int("--pairs", 8), a.float("--sigma", 0.02f))
        val rate = a.float("--rate", 0.01f)
        val hunters = if (a.flag("--hunters")) Catalogue.predators.filter { !it.human && it !in Catalogue.residentHunters } else emptyList()
        a.done()
        out.mkdirs()
        // Every kind with a network in the start directory evolves: the migratory herds, and people.
        val kinds = (Catalogue.herbivores.filter { it in Catalogue.migratory } + Catalogue.HUMAN).filter { HexLearning.file(start, it).exists() }
        val centres = kinds.associateWith { s -> Mlp.load((HexLearning.file(out, s).takeIf { it.exists() } ?: HexLearning.file(start, s)).readText()) }
        val adams = kinds.associateWith { Adam(centres.getValue(it).w.size, rate) }
        val log = RunLog(out, "gen")
        val firstGen = log.done()
        for (gen in firstGen until firstGen + gens) {
            val t0 = System.nanoTime()
            val rnd = Random(gen * 7919L + 9)
            val eps = kinds.associateWith { s -> es.noise(rnd, centres.getValue(s).w.size) }
            val variants = kinds.associateWith { s -> es.variants(centres.getValue(s), eps.getValue(s)) }
            val results = seeds.parallelStream().map { seed -> evaluate(WorldConfig(seed = seed + 1000L * gen, widthKm = size, heightKm = size), years, variants, hunters) }.toList()
            val parts = ArrayList<String>()
            for (s in kinds) {
                val fitness = DoubleArray(es.count)
                for (r in results) { val f = r.first.getValue(s); for (v in 0 until es.count) fitness[v] += f[v] / results.size }
                val centre = centres.getValue(s)
                es.step(centre, eps.getValue(s), fitness, adams.getValue(s))
                Schema.save(centre, HexLearning.file(out, s), Schema.TEACHER_IN, Schema.MOVER_OUT)
                if (gen % 10 == 9) Schema.save(centre, HexLearning.file(File(out, "gen${gen + 1}").also { it.mkdirs() }, s), Schema.TEACHER_IN, Schema.MOVER_OUT)
                parts += "%s %.2f/%.2f".format(s.name, fitness.take(2 * es.pairs).average(), fitness.last())
            }
            val line = "gen %d  %.0f s  fitness mean/centre: %s  | %s".format(gen, (System.nanoTime() - t0) / 1e9, parts.joinToString(", "), results.joinToString("  ") { it.second })
            log.write(line)
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
}

/**
 * Evolves the bands' ways (their effort on each food, the stores they aim for, what they make
 * first) on small worlds with all the animals: each generation, the bands are dealt variants of
 * the network round-robin, and each variant is scored by the person-weeks its bands live, per
 * person at the start. It starts from the scaffold (a network of zeros chooses exactly as it does).
 *
 * With --role herds or --role hunters it evolves the herds' or the packs' ways instead ([inflation.ecology.AnimalWay],
 * in herd-ways.mlp or hunter-ways.mlp), each variant scored by the head-weeks its groups live per head
 * at the start, averaged over the kinds of the role (so the many jackals do not outweigh the few lions).
 *
 * Usage: hex-evolve-ways [--role bands|herds|hunters] [--out DIR] [--seeds 1,2,..] [--size KM] [--years N] [--gens N] [--pairs N] [--sigma S] [--rate R] [--hidden N] [--nets DIR]
 *
 * With --nets, every kind moves by the learned role networks in DIR (herds.mlp, hunters.mlp,
 * people.mlp, each with its .identity, as [HexPpo] writes them) instead of the hand-written choices.
 */
object HexEvolveWays {
    @JvmStatic
    fun main(args: Array<String>) {
        val a = Args(args, "hex-evolve-ways [--role bands|herds|hunters] [--out DIR] [--seeds 1,2,..] [--size KM] [--years N] [--gens N] [--pairs N] [--sigma S] [--rate R] [--hidden N] [--nets DIR]")
        val out = a.file("--out") ?: File("hex-ways")
        val role = a.string("--role") ?: "bands"
        require(role in listOf("bands", "herds", "hunters")) { "--role is bands, herds or hunters" }
        val seeds = a.string("--seeds")?.split(",")?.map { it.toLong() } ?: listOf(81L, 82L, 83L, 84L, 85L, 86L, 87L, 88L)
        val size = a.int("--size", 600)
        val years = a.int("--years", 5)
        val gens = a.int("--gens", 1000)
        val es = EvolutionStrategy(a.int("--pairs", 8), a.float("--sigma", 0.05f))
        val rate = a.float("--rate", 0.02f)
        val hidden = a.int("--hidden", 16)
        val movers = a.file("--nets")?.let { dir -> Role.entries.map { SharedMover.load(it.file(dir).also { f -> require(f.exists()) { "no $f" } }) } }
        a.done()
        WaysEvolution(role, movers).run(out, seeds, size, years, gens, es, rate, hidden)
    }
}

/** [HexEvolveWays] for one [role]'s ways ("bands", "herds" or "hunters"), every kind moving by [movers] (by role) if given. */
class WaysEvolution(private val role: String, private val movers: List<SharedMover>?) {
    private val inNames = if (role == "bands") Schema.WAYS_IN else Schema.ANIMAL_WAYS_IN
    private val outNames = when (role) { "bands" -> Schema.WAYS_OUT; "herds" -> Schema.HERD_WAYS_OUT; else -> Schema.HUNTER_WAYS_OUT }
    private val fileName = when (role) { "bands" -> "ways.mlp"; "herds" -> "herd-ways.mlp"; else -> "hunter-ways.mlp" }
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
            for (r in results) for (v in 0 until es.count) fitness[v] += r.first[v] / results.size
            es.step(centre, eps, fitness, adam)
            Schema.save(centre, file, inNames, outNames)
            if (gen % 10 == 9) {
                Schema.save(centre, File(File(out, "gen${gen + 1}").also { it.mkdirs() }, fileName), inNames, outNames)
                // Against the scaffold on the same fixed worlds: the centre's bands and scaffold bands side by side.
                val scaffold = centre.copy().zeroOutputLayer()
                val check = checkSeeds.parallelStream().map { seed -> evaluate(WorldConfig(seed = seed, widthKm = size, heightKm = size), years, arrayOf(scaffold, centre)).first }.toList()
                val vs = "check gen %d: scaffold %.3f, learned %.3f".format(gen + 1, check.map { it[0] }.average(), check.map { it[1] }.average())
                log.check(vs)
            }
            val line = "gen %d  %.0f s  fitness mean/centre: %.3f/%.3f  | %s".format(gen, (System.nanoTime() - t0) / 1e9, fitness.take(2 * es.pairs).average(), fitness.last(), results.joinToString("  ") { it.second })
            log.write(line)
        }
    }

    /** One small world with all the animals for [years], each band on one of [variants]; person-weeks per person at the start. */
    fun evaluate(cfg: WorldConfig, years: Int, variants: Array<Mlp>): Pair<DoubleArray, String> {
        val run = HexLearning.Run(cfg, Catalogue.learned.distinct())
        movers?.let { ms -> val ps = ms.map { MoverPolicy(it, null, { 0f }, { 0 }) }; for (s in Catalogue.all) run.groups.policies[s] = ps[Role.of(s).ordinal] }
        if (role != "bands") return evaluateAnimals(run, cfg, years, variants)
        val rnd = Random(cfg.seed)
        val people = Catalogue.HUMAN
        val started = DoubleArray(variants.size)
        run.groups.groups.filter { it.species == people }.shuffled(rnd).forEachIndexed { i, g -> g.tag = i % variants.size; started[g.tag] += g.size.toDouble() }
        val nets = variants.map { NetWays(it) }
        run.groups.ways = BandWays { g, inp, o -> nets[g.tag.coerceIn(0, nets.size - 1)].choose(g, inp, o) }
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

    /** As [evaluate], for the herds' or packs' ways: each group of the role on one of [variants]; head-weeks per head at the start, the mean over the role's kinds. */
    private fun evaluateAnimals(run: HexLearning.Run, cfg: WorldConfig, years: Int, variants: Array<Mlp>): Pair<DoubleArray, String> {
        val rnd = Random(cfg.seed)
        val hunters = role == "hunters"
        fun mine(g: HexGroup) = !g.species.human && g.species.carnivore == hunters
        val kinds = run.groups.groups.filter { mine(it) }.map { it.species }.distinct().sortedBy { it.index }
        val started = kinds.associateWith { DoubleArray(variants.size) }
        val weeks = kinds.associateWith { DoubleArray(variants.size) }
        // Variants are dealt round-robin within each kind, so each kind is weighed on every variant.
        for (s in kinds) run.groups.groups.filter { it.species == s }.shuffled(rnd).forEachIndexed { i, g -> g.tag = i % variants.size; started.getValue(s)[g.tag] += g.size.toDouble() }
        val nets = variants.map { NetAnimalWays(it) }
        val ways = AnimalWays { g, inp, o -> nets[g.tag.coerceIn(0, nets.size - 1)].choose(g, inp, o) }
        if (hunters) run.groups.hunterWays = ways else run.groups.herdWays = ways
        val steps = years * Date.DAYS_PER_YEAR / cfg.daysPerStep
        repeat(steps) {
            run.step()
            for (g in run.groups.groups) if (g.alive && g.tag >= 0 && g.tag < variants.size && mine(g)) weeks[g.species]?.let { it[g.tag] += g.size.toDouble() }
        }
        val fitness = DoubleArray(variants.size) { v ->
            val per = kinds.filter { started.getValue(it)[v] > 0.0 }.map { weeks.getValue(it)[v] / started.getValue(it)[v] / steps }
            if (per.isEmpty()) 0.0 else per.average()
        }
        return fitness to "seed ${cfg.seed}: " + kinds.joinToString(", ") { s -> "${s.name} ${started.getValue(s).sum().toInt()}->${run.groups.count(s)}" }
    }
}
