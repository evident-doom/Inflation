package inflation.learning

import inflation.ecology.Catalogue
import inflation.ecology.HexPolicy
import inflation.world.Date
import inflation.world.WorldConfig
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs

/**
 * What a learned network does differently from the hand-written behaviour, for one kind: a world
 * runs with the kind choosing by the network (the rest by hand), and at each of a sample of its
 * choices both are asked. Reports how often they agree; where they differ, the mean of each feature
 * at the place the network chose against the place the hand-written behaviour would have chosen
 * (so "the network goes where there is more X"); and how the kind fared, by cause of death, against
 * a world where it chose by hand.
 *
 * Usage: hex-explain --net FILE --kind NAME [--seed N] [--size KM] [--years N]
 */
object HexExplain {
    val FEATURES = listOf(
        "food", "suits it", "crowding", "hunters", "discomfort now", "discomfort ahead", "deep snow", "snow depth",
        "height change km", "effort", "stays", "same hex", "toward season place", "season promise - fed", "fed", "reserves",
        "season sin", "season cos", "restless", "water distance", "thirst", "bearing pull", "moon", "birth sin", "birth cos",
    )

    @JvmStatic
    fun main(args: Array<String>) {
        var netFile: File? = null; var kindName = "ostrich"; var seed = 91L; var size = 600; var years = 3
        var k = 0
        while (k < args.size) {
            when (args[k]) {
                "--net" -> netFile = File(args[++k])
                "--kind" -> kindName = args[++k]
                "--seed" -> seed = args[++k].toLong()
                "--size" -> size = args[++k].toInt()
                "--years" -> years = args[++k].toInt()
                else -> error("unknown argument ${args[k]}")
            }
            k++
        }
        val net = Mlp.load(netFile!!.readText())
        val kind = Catalogue.all.first { it.name == kindName }
        val kinds = Catalogue.all.filter { it in Catalogue.migratory || it.carnivore }
        val cfg = WorldConfig(seed = seed, widthKm = size, heightKm = size)
        class Pick(val net: FloatArray, val hand: FloatArray)
        fun play(learned: Boolean): Pair<Map<String, Int>, List<Pick>> {
            val run = HexLearning.Run(cfg, kinds)
            val picks = ConcurrentLinkedQueue<Pick>()
            var agree = 0; var asked = 0
            if (learned) {
                val buffers = ThreadLocal.withInitial { net.buffers() }
                run.groups.policies[kind] = HexPolicy { _, f, b ->
                    val bb = buffers.get()
                    val scores = FloatArray(f.size) { i -> f[i].copyInto(bb.a[0], 0, 0, minOf(f[i].size, bb.a[0].size)); net.forward(bb) }
                    var n = 0; var h = 0
                    for (i in f.indices) { if (scores[i] > scores[n]) n = i; if (b[i] > b[h]) h = i }
                    synchronized(picks) { asked++; if (n == h) agree++ else if (picks.size < 200_000) picks += Pick(f[n].copyOf(), f[h].copyOf()) }
                    n
                }
            }
            val deaths = HashMap<String, Int>()
            repeat(years * Date.DAYS_PER_YEAR / cfg.daysPerStep) {
                run.step()
                for ((key, v) in run.groups.deaths) if (key.startsWith("${kind.name}:")) deaths.merge(key.substringAfter(':'), v, Int::plus)
            }
            deaths["(alive at the end)"] = run.groups.count(kind)
            if (learned) println("%s: the network agreed with the hand-written choice in %.0f%% of %d choices".format(kind.name, 100.0 * agree / asked.coerceAtLeast(1), asked))
            return deaths to picks.toList()
        }
        val both = listOf(true, false).parallelStream().map { play(it) }.toList()
        val (learnedDeaths, picks) = both[0]; val handDeaths = both[1].first
        println("where they differ (%d choices), mean at the network's place vs the hand-written one's:".format(picks.size))
        val rows = FEATURES.indices.map { i ->
            val a = picks.map { it.net.getOrElse(i) { 0f }.toDouble() }.average(); val b = picks.map { it.hand.getOrElse(i) { 0f }.toDouble() }.average()
            Triple(FEATURES[i], a, b)
        }.sortedByDescending { abs(it.second - it.third) }
        for ((name, a, b) in rows) println("   %-22s %8.3f vs %8.3f  (%+.3f)".format(name, a, b, a - b))
        println("deaths over the run (network vs hand-written):")
        for (c in (learnedDeaths.keys + handDeaths.keys).sorted()) println("   %-22s %8d vs %8d".format(c, learnedDeaths[c] ?: 0, handDeaths[c] ?: 0))
    }
}
