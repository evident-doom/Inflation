package inflation.learning

import inflation.Args
import inflation.ecology.Catalogue
import inflation.ecology.HexPolicy
import inflation.world.Date
import inflation.world.WorldConfig
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
    /** Each place feature's name, as saved networks name their inputs. */
    val FEATURES get() = Schema.PLACE

    @JvmStatic
    fun main(args: Array<String>) {
        val a = Args(args, "hex-explain --net FILE [--kind NAME] [--seed N] [--size KM] [--years N]")
        val netFile = a.file("--net"); val kindName = a.string("--kind") ?: "ostrich"
        val seed = a.long("--seed", 91L); val size = a.int("--size", 600); val years = a.int("--years", 3)
        a.done()
        requireNotNull(netFile) { "hex-explain needs --net FILE" }
        val net = Mlp.load(netFile.readText())
        val kind = Catalogue.all.first { it.name == kindName }
        val kinds = Catalogue.learned
        val cfg = WorldConfig(seed = seed, widthKm = size, heightKm = size)
        class Pick(val net: FloatArray, val hand: FloatArray)
        fun play(learned: Boolean): Pair<Map<String, Int>, List<Pick>> {
            val run = HexLearning.Run(cfg, kinds)
            val picks = ConcurrentLinkedQueue<Pick>()
            var agree = 0; var asked = 0
            if (learned) {
                val buffers = ThreadLocal.withInitial { net.buffers() }
                run.groups.policies[kind] = HexPolicy { _, f, b, _ ->
                    val buf = buffers.get()
                    val scores = FloatArray(f.size) { i -> f[i].copyInto(buf.a[0], 0, 0, minOf(f[i].size, buf.a[0].size)); net.forward(buf) }
                    var n = 0; var h = 0
                    for (i in f.indices) { if (scores[i] > scores[n]) n = i; if (b[i] > b[h]) h = i }
                    synchronized(picks) { asked++; if (n == h) agree++ else if (picks.size < 200_000) picks += Pick(f[n].copyOf(), f[h].copyOf()) }
                    n
                }
            }
            val deaths = HashMap<String, Int>()
            var fedSum = 0.0; var kmSum = 0.0; var weeks = 0; var hungry = 0
            repeat(years * Date.DAYS_PER_YEAR / cfg.daysPerStep) {
                run.step()
                for (g in run.groups.groups) if (g.alive && g.species == kind) { fedSum += g.fed; kmSum += (g.path.size - 1) * run.hex.hexKm; weeks++; if (g.fed < 0.5f) hungry++ }
                for ((key, v) in run.groups.stats.deaths) if (key.startsWith("${kind.name}:")) deaths.merge(key.substringAfter(':'), v, Int::plus)
            }
            deaths["(alive at the end)"] = run.groups.count(kind)
            println("%s, %s: fed %.2f on average, hungry (fed under half) %.1f%% of group-weeks, %.1f km shifted a week".format(kind.name, if (learned) "network" else "hand-written", fedSum / weeks.coerceAtLeast(1), 100.0 * hungry / weeks.coerceAtLeast(1), kmSum / weeks.coerceAtLeast(1)))
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
