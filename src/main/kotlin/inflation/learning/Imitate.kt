package inflation.learning

import inflation.ecology.AnimalGroup
import inflation.ecology.BaselinePolicy
import inflation.ecology.Catalogue
import inflation.ecology.Option
import inflation.ecology.Policy
import inflation.ecology.Species
import inflation.world.Date
import inflation.world.Simulation
import inflation.world.World
import inflation.world.WorldConfig
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Trains one network per species to copy [BaselinePolicy]: the starting point for learned
 * behaviour, since a random network would starve every group and give learning nothing to work
 * with. Runs the world with the baseline, records a sample of decisions (every option's features
 * and the baseline's score for it), and fits each network to reproduce the scores, which carry
 * far more information than the choices alone. Only differences between a decision's options
 * matter, so scores are compared after subtracting each decision's mean.
 *
 * Usage: imitate [--seed N] [--size KM] [--years N] [--out DIR]
 */
fun main(args: Array<String>) {
    var cfg = WorldConfig(seed = 7, widthKm = 400, heightKm = 400)
    var years = 3
    var out = NetworkPolicy.DIR
    var k = 0
    while (k < args.size) {
        when (args[k]) {
            "--seed" -> cfg = cfg.copy(seed = args[++k].toLong())
            "--size" -> { val s = args[++k].toInt(); cfg = cfg.copy(widthKm = s, heightKm = s) }
            "--years" -> years = args[++k].toInt()
            "--out" -> out = File(args[++k])
            else -> error("unknown argument ${args[k]}")
        }
        k++
    }
    val sim = Simulation(World.generate(cfg))
    val e = sim.ecology
    val recorder = Recorder(e.policies.getValue(Catalogue.GAZELLE) as BaselinePolicy)
    for (s in Catalogue.all) e.policies[s] = recorder
    val steps = years * Date.DAYS_PER_YEAR / cfg.daysPerStep
    val t0 = System.nanoTime()
    repeat(steps) { sim.step() }
    println("recorded %d years in %.0f s: %s".format(years, (System.nanoTime() - t0) / 1e9,
        Catalogue.all.joinToString { "${it.name} ${recorder.data.getValue(it).size}" }))
    val results = Catalogue.all.parallelStream().map { s -> s to Imitation.train(recorder.data.getValue(s).toList(), Random(cfg.seed + s.index)) }.toList()
    for ((s, r) in results) {
        val (net, report) = r ?: continue
        net.save(NetworkPolicy.file(out, s))
        println("%-12s %s".format(s.name, report))
    }
}

/** Passes decisions to the baseline and keeps a sample of them, at most [MAX] per species. */
class Recorder(private val baseline: BaselinePolicy) : Policy {
    val data = Catalogue.all.associateWith { ConcurrentLinkedQueue<FloatArray>() }
    private val counts = Catalogue.all.associateWith { AtomicInteger() }

    override fun choose(group: AnimalGroup, options: List<Option>): Int {
        val s = group.species
        // Predators decide far less often than all the herds together, so keep all of theirs.
        val rate = if (s.carnivore) 1f else 0.25f
        if (ThreadLocalRandom.current().nextFloat() < rate && counts.getValue(s).incrementAndGet() <= MAX) {
            val rec = FloatArray(Imitation.SLOTS * Imitation.STRIDE)
            val f = FloatArray(Features.SIZE)
            for ((k, o) in options.withIndex()) {
                if (k >= Imitation.SLOTS || o.cell < 0) continue
                val at = k * Imitation.STRIDE
                Features.encode(group, o, k, f)
                rec[at] = 1f
                f.copyInto(rec, at + 1)
                rec[at + 1 + Features.SIZE] = baseline.score(group, o)
            }
            data.getValue(s) += rec
        }
        return baseline.choose(group, options)
    }

    companion object {
        const val MAX = 120_000
    }
}

object Imitation {
    /** Options per decision: stay and eight directions. */
    const val SLOTS = 9
    /** Per option: valid flag, features, target score. */
    const val STRIDE = 1 + Features.SIZE + 1
    val SIZES = intArrayOf(Features.SIZE, 32, 32, 1)

    /** Fits a network to [records]; null if there are too few. Returns it with a held-out report. */
    fun train(records: List<FloatArray>, rnd: Random, epochs: Int = 30): Pair<Mlp, String>? {
        if (records.size < 200) return null
        val shuffled = records.shuffled(rnd)
        val test = shuffled.take(shuffled.size / 10)
        val trainSet = shuffled.drop(test.size)
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
                    // Centred error per option: prediction and target each minus their decision's mean.
                    var n = 0; var my = 0f; var mt = 0f
                    for (k in 0 until SLOTS) {
                        val at = k * STRIDE
                        if (rec[at] == 0f) continue
                        rec.copyInto(b.a[0], 0, at + 1, at + 1 + Features.SIZE)
                        y[k] = net.forward(b)
                        my += y[k]; mt += rec[at + STRIDE - 1]; n++
                    }
                    if (n < 2) continue
                    my /= n; mt /= n
                    for (k in 0 until SLOTS) {
                        val at = k * STRIDE
                        if (rec[at] == 0f) continue
                        rec.copyInto(b.a[0], 0, at + 1, at + 1 + Features.SIZE)
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

    /**
     * How often the network's best option is the baseline's best (before noise), and the share
     * of the spread in scores within decisions that it reproduces.
     */
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
                rec.copyInto(b.a[0], 0, at + 1, at + 1 + Features.SIZE)
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
