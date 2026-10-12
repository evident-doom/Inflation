package inflation.learning

import inflation.ecology.AnimalGroup
import inflation.ecology.BaselinePolicy
import inflation.ecology.Catalogue
import inflation.ecology.Option
import inflation.ecology.Policy
import inflation.ecology.Tool
import inflation.ecology.Tradition
import inflation.ecology.pickBest
import inflation.world.Date
import inflation.world.Simulation
import inflation.world.World
import inflation.world.WorldConfig
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The first learned behaviour for people: one shared network for every band that scores each
 * candidate place for the week's camp and range. It sees the place (the same features animals
 * see) and the band itself: its stores, how long it has kept camp and how much firewood is left,
 * its people, its tools and some of its ways, and the season. Bands differ in what they see, not in
 * the network. This is the first stage of docs/design/learned-bands.md: a fixed feature vector,
 * before tokens and attention.
 */
object BandFeatures {
    const val SIZE = Features.SIZE + 14

    fun encode(g: AnimalGroup, o: Option, k: Int, date: Date, out: FloatArray) {
        Features.encode(g, o, k, out)
        val n = Features.SIZE
        val need = g.dailyNeedKg.coerceAtLeast(1e-3f)
        out[n] = min(1f, g.larder / need / 180f)
        out[n + 1] = min(1f, g.campWeeks / 52f)
        out[n + 2] = ((g.campWeeks - g.fuelWeeks) / 26f).coerceIn(-1f, 1f)
        out[n + 3] = g.fare
        out[n + 4] = g.elders.toFloat() / g.size.coerceAtLeast(1)
        out[n + 5] = g.young.toFloat() / g.size.coerceAtLeast(1)
        out[n + 6] = min(1f, g.size / 50f)
        out[n + 7] = g.tools?.let { (it[Tool.SPEAR.ordinal] + it[Tool.BOW.ordinal]) / 2f } ?: 0f
        out[n + 8] = if (g.holds(Tradition.FEASTS)) 1f else 0f
        out[n + 9] = if (g.holds(Tradition.BURIAL)) 1f else 0f
        out[n + 10] = if (g.holds(Tradition.SKY_LORE)) 1f else 0f
        val a = 2 * PI * date.dayOfYear / Date.DAYS_PER_YEAR
        out[n + 11] = sin(a).toFloat()
        out[n + 12] = cos(a).toFloat()
        out[n + 13] = if (k == 0) 1f else 0f // staying put
    }

    val SIZES = intArrayOf(SIZE, 48, 32, 1)
    val FILE = File("policies/people.mlp")
}

/** Bands choose with [net]; everyone else (and bands if [net] is absent) with [fallback]. */
class BandPolicy(private val net: Mlp, private val noise: (AnimalGroup, Int) -> Float, private val date: () -> Date) : Policy {
    private val buffers = ThreadLocal.withInitial { net.buffers() }

    override fun choose(group: AnimalGroup, options: List<Option>): Int {
        val b = buffers.get()
        val d = date()
        return pickBest(group, options, noise) { o, k -> BandFeatures.encode(group, o, k, d, b.a[0]); net.forward(b) }
    }
}

/**
 * Copies the hand-written band behaviour into the shared network: records bands' decisions (each
 * option's features and the hand-written score) on a world, then fits the network to reproduce the
 * scores within each decision.
 *
 * Usage: band-imitate [--seed N] [--width KM] [--height KM] [--years N] [--out FILE]
 */
fun main(args: Array<String>) {
    var cfg = WorldConfig(seed = 7, widthKm = 500, heightKm = 1000)
    var years = 4
    var out = BandFeatures.FILE
    var k = 0
    while (k < args.size) {
        when (args[k]) {
            "--seed" -> cfg = cfg.copy(seed = args[++k].toLong())
            "--width" -> cfg = cfg.copy(widthKm = args[++k].toInt())
            "--height" -> cfg = cfg.copy(heightKm = args[++k].toInt())
            "--years" -> years = args[++k].toInt()
            "--out" -> out = File(args[++k])
            else -> error("unknown argument ${args[k]}")
        }
        k++
    }
    val sim = Simulation(World.generate(cfg))
    val e = sim.ecology
    val baseline = e.policies.getValue(Catalogue.HUMAN) as BaselinePolicy
    val recorder = BandRecorder(baseline) { sim.weather.date }
    e.policies[Catalogue.HUMAN] = recorder
    val t0 = System.nanoTime()
    repeat(years * Date.DAYS_PER_YEAR / cfg.daysPerStep) { sim.step() }
    println("recorded %d band decisions in %.0f s".format(recorder.data.size, (System.nanoTime() - t0) / 1e9))
    val (net, report) = BandImitation.train(recorder.data.toList(), Random(cfg.seed)) ?: error("too few decisions")
    net.save(out)
    println("people: $report")
}

/** Passes bands' decisions to the hand-written behaviour and keeps a sample of them. */
class BandRecorder(private val baseline: BaselinePolicy, private val date: () -> Date) : Policy {
    val data = ConcurrentLinkedQueue<FloatArray>()
    private val count = AtomicInteger()

    override fun choose(group: AnimalGroup, options: List<Option>): Int {
        if (ThreadLocalRandom.current().nextFloat() < 0.5f && count.incrementAndGet() <= MAX) {
            val rec = FloatArray(BandImitation.SLOTS * BandImitation.STRIDE)
            val f = FloatArray(BandFeatures.SIZE)
            val d = date()
            for ((k, o) in options.withIndex()) {
                if (k >= BandImitation.SLOTS || o.cell < 0) continue
                val at = k * BandImitation.STRIDE
                BandFeatures.encode(group, o, k, d, f)
                rec[at] = 1f
                f.copyInto(rec, at + 1)
                rec[at + 1 + BandFeatures.SIZE] = baseline.score(group, o)
            }
            data += rec
        }
        return baseline.choose(group, options)
    }

    companion object {
        const val MAX = 150_000
    }
}

object BandImitation {
    /** Options per decision: all the candidates a band weighs. */
    const val SLOTS = 28
    const val STRIDE = 1 + BandFeatures.SIZE + 1

    fun train(records: List<FloatArray>, rnd: Random, epochs: Int = 25): Pair<Mlp, String>? {
        if (records.size < 200) return null
        val shuffled = records.shuffled(rnd)
        val test = shuffled.take(shuffled.size / 10)
        val trainSet = shuffled.drop(test.size)
        val net = Mlp.random(BandFeatures.SIZES, rnd)
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
                        rec.copyInto(b.a[0], 0, at + 1, at + 1 + BandFeatures.SIZE)
                        y[k] = net.forward(b)
                        my += y[k]; mt += rec[at + STRIDE - 1]; n++
                    }
                    if (n < 2) continue
                    my /= n; mt /= n
                    for (k in 0 until SLOTS) {
                        val at = k * STRIDE
                        if (rec[at] == 0f) continue
                        rec.copyInto(b.a[0], 0, at + 1, at + 1 + BandFeatures.SIZE)
                        net.forward(b)
                        val err = (y[k] - my) - (rec[at + STRIDE - 1] - mt)
                        net.backward(b, 2f * err / n / batch, grad)
                    }
                }
                adam.step(net.w, grad)
            }
        }
        return net to report(net, test)
    }

    fun report(net: Mlp, test: List<FloatArray>): String {
        val b = net.buffers()
        var same = 0; var decisions = 0
        var errSq = 0.0; var varSq = 0.0
        val y = FloatArray(SLOTS)
        for (rec in test) {
            var n = 0; var my = 0f; var mt = 0f
            var bestY = -1; var bestT = -1
            for (k in 0 until SLOTS) {
                val at = k * STRIDE
                if (rec[at] == 0f) continue
                rec.copyInto(b.a[0], 0, at + 1, at + 1 + BandFeatures.SIZE)
                y[k] = net.forward(b)
                val t = rec[at + STRIDE - 1]
                if (bestY < 0 || y[k] > y[bestY]) bestY = k
                if (bestT < 0 || t > rec[bestT * STRIDE + STRIDE - 1]) bestT = k
                my += y[k]; mt += t; n++
            }
            if (n < 2) continue
            my /= n; mt /= n
            for (k in 0 until SLOTS) {
                val at = k * STRIDE
                if (rec[at] == 0f) continue
                val t = rec[at + STRIDE - 1] - mt
                val d = (y[k] - my) - t
                errSq += d * d; varSq += t * t
            }
            decisions++
            if (bestY == bestT) same++
        }
        return "held out %d decisions: same best option %.1f%%, score spread explained %.1f%%".format(
            decisions, 100.0 * same / decisions, 100.0 * (1 - errSq / varSq))
    }
}

/**
 * Evolves the shared band network by evolution strategies. Each generation draws [pairs] random
 * directions and makes a plus and a minus variant of each, plus the unchanged centre. Bands in
 * every world are dealt variants round-robin; a band's descendants keep its variant. A variant's
 * fitness is the person-weeks its lineages live, per person it started with: survival and children
 * over the years, with no reward for any particular way of living. The weights move towards the
 * better variants, by rank, with Adam. Animals keep their own behaviour.
 *
 * Usage: band-evolve [--start FILE] [--out DIR] [--seeds 1,2,3,4] [--width KM] [--height KM]
 *                    [--years N] [--gens N] [--pairs N] [--sigma S] [--rate R]
 */
object BandEvolve {
    @JvmStatic
    fun main(args: Array<String>) {
        var start = BandFeatures.FILE
        var out = File("band-evolve")
        var seeds = listOf(11L, 12L, 13L, 14L)
        var width = 500; var height = 1000
        var years = 3
        var gens = 1000
        var pairs = 12
        var sigma = 0.02f
        var rate = 0.01f
        var k = 0
        while (k < args.size) {
            when (args[k]) {
                "--start" -> start = File(args[++k])
                "--out" -> out = File(args[++k])
                "--seeds" -> seeds = args[++k].split(",").map { it.toLong() }
                "--width" -> width = args[++k].toInt()
                "--height" -> height = args[++k].toInt()
                "--years" -> years = args[++k].toInt()
                "--gens" -> gens = args[++k].toInt()
                "--pairs" -> pairs = args[++k].toInt()
                "--sigma" -> sigma = args[++k].toFloat()
                "--rate" -> rate = args[++k].toFloat()
                else -> error("unknown argument ${args[k]}")
            }
            k++
        }
        val resume = File(out, "people.mlp")
        val centre = Mlp.load((if (resume.exists()) resume else start).readText())
        val log = File(out, "log.txt").also { it.parentFile.mkdirs() }
        val firstGen = if (log.exists()) log.readLines().count { it.startsWith("gen ") } else 0
        val adam = Adam(centre.w.size, rate)
        for (gen in firstGen until firstGen + gens) {
            val t0 = System.nanoTime()
            val rnd = Random(gen * 7919L + 3)
            val eps = Array(pairs) { FloatArray(centre.w.size) { gaussian(rnd) } }
            val variants = Array(2 * pairs + 1) { v ->
                val m = centre.copy()
                if (v < 2 * pairs) {
                    val sign = if (v % 2 == 0) 1f else -1f
                    val e = eps[v / 2]
                    for (i in m.w.indices) m.w[i] += sign * sigma * e[i]
                }
                m
            }
            // Fresh worlds each generation, so the network is not fitted to one map.
            val results = seeds.map { seed ->
                evaluateBands(WorldConfig(seed = seed + 1000L * gen, widthKm = width, heightKm = height), years, variants)
            }
            val fitness = DoubleArray(variants.size)
            for (r in results) for (v in fitness.indices) fitness[v] += r.fitness[v] / results.size
            val ranks = ranks(fitness.copyOf(2 * pairs))
            val grad = FloatArray(centre.w.size)
            for (i in 0 until pairs) {
                val d = ranks[2 * i] - ranks[2 * i + 1]
                val e = eps[i]
                for (j in grad.indices) grad[j] -= (d * e[j] / (2 * pairs * sigma)).toFloat()
            }
            adam.step(centre.w, grad)
            centre.save(resume)
            val line = "gen %d  %.0f s  fitness mean %.3f best %.3f centre %.3f  | %s".format(
                gen, (System.nanoTime() - t0) / 1e9, fitness.take(2 * pairs).average(), fitness.take(2 * pairs).max(), fitness.last(),
                results.joinToString("  ") { it.summary })
            println(line)
            log.appendText(line + "\n")
            if (gen % 10 == 9) centre.save(File(out, "people-gen${gen + 1}.mlp"))
        }
    }

    /** Runs one world for [years] with every band on one of [variants]. */
    fun evaluateBands(cfg: WorldConfig, years: Int, variants: Array<Mlp>): Evaluation {
        val sim = Simulation(World.generate(cfg))
        val e = sim.ecology
        val people = Catalogue.HUMAN
        val rnd = Random(cfg.seed)
        val bands = e.groups.filter { it.species == people }.shuffled(rnd)
        val started = DoubleArray(variants.size)
        bands.forEachIndexed { i, g -> g.tag = i % variants.size; started[g.tag] += g.size.toDouble() }
        val policies = variants.map { BandPolicy(it, e::noise) { sim.weather.date } }
        val fallback = e.policies.getValue(people)
        e.policies[people] = object : Policy {
            override fun choose(group: AnimalGroup, options: List<Option>) =
                if (group.tag in variants.indices) policies[group.tag].choose(group, options) else fallback.choose(group, options)
        }
        val personWeeks = DoubleArray(variants.size)
        val steps = years * Date.DAYS_PER_YEAR / cfg.daysPerStep
        repeat(steps) {
            sim.step()
            for (g in e.groups) if (g.species == people && g.tag >= 0 && g.alive) personWeeks[g.tag] += g.size.toDouble()
        }
        val fitness = DoubleArray(variants.size) { if (started[it] == 0.0) 0.0 else personWeeks[it] / started[it] / steps }
        return Evaluation(fitness, "seed %d: %d bands, people %d -> %d".format(cfg.seed, bands.size, started.sum().toInt(), e.count(people)))
    }

    private fun gaussian(rnd: Random): Float {
        var u: Double
        do u = rnd.nextDouble() while (u <= 1e-12)
        return (sqrt(-2.0 * kotlin.math.ln(u)) * cos(2.0 * PI * rnd.nextDouble())).toFloat()
    }

    private fun ranks(x: DoubleArray): DoubleArray {
        val order = x.indices.sortedBy { x[it] }
        val r = DoubleArray(x.size)
        order.forEachIndexed { rank, i -> r[i] = rank.toDouble() / (x.size - 1) - 0.5 }
        return r
    }
}
