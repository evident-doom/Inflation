package inflation.tools

import inflation.ecology.BandWays
import inflation.ecology.Catalogue
import inflation.ecology.HexPolicy
import inflation.ecology.WAYS_OUT
import inflation.learning.HexPpo
import inflation.learning.Schema
import inflation.world.WorldConfig

/**
 * How far people's score (as the training checks it: people-weeks per person at the start, less
 * their luck in births and deaths) can be moved at all by what bands choose: every band moving at
 * random, to the worst place, or staying; and every band holding one of its ways far below or above
 * the scaffold's (network output -2 or +2: about 0.24 or 1.76 times the scaffold) the whole time, the
 * others at the scaffold. "nudge" (every way at +0.01, a change too small to matter) shows how far the
 * score moves by the world's own knock-on effects alone. If no choice moves the score much beyond
 * that, no learner can gain much by it.
 *
 * Usage: hex-people-leverage [--seeds A,B] [--size KM] [--years N] [--ways-only] [--moves-only]
 */
fun main(args: Array<String>) {
    var seeds = listOf(91L); var size = 600; var years = 3; var ways = true; var moves = true
    var k = 0
    while (k < args.size) { when (args[k]) {
        "--seeds" -> seeds = args[++k].split(",").map { it.toLong() }; "--size" -> size = args[++k].toInt(); "--years" -> years = args[++k].toInt()
        "--ways-only" -> moves = false; "--moves-only" -> ways = false }; k++ }
    val kinds = Catalogue.learned
    val names = Schema.WAYS_OUT
    val configs = buildList {
        add("hand"); add("nudge")
        if (moves) { add("random"); add("worst"); add("stay") }
        if (ways) for (j in 0 until WAYS_OUT) { add("way-$j"); add("way+$j") }
    }
    fun label(c: String) = if (c.startsWith("way")) "${names[c.substring(4).toInt()]} ${if (c[3] == '-') "low" else "high"}" else c
    val jobs = seeds.flatMap { s -> configs.map { s to it } }
    val done = java.util.concurrent.atomic.AtomicInteger()
    val r = jobs.parallelStream().map { (seed, c) ->
        val cfg = WorldConfig(seed = seed, widthKm = size, heightKm = size)
        val score = HexPpo.score(cfg, years, kinds) { run ->
            for (s in kinds) if (s.human) run.groups.policies[s] = HexPolicy { _, f, b, rnd ->
                when (c) {
                    "stay" -> f.indices.firstOrNull { f[it].getOrElse(10) { 0f } > 0.5f } ?: b.indices.maxBy { b[it] }
                    "random" -> rnd.nextInt(f.size)
                    "worst" -> b.indices.minBy { b[it] }
                    else -> b.indices.maxBy { b[it] }
                }
            }
            run.groups.ways = BandWays { _, _, out ->
                when {
                    c == "nudge" -> for (i in 0 until WAYS_OUT) out[i] = 0.01f
                    c.startsWith("way") -> out[c.substring(4).toInt()] = if (c[3] == '-') -2f else 2f
                }
            }
        }[Catalogue.HUMAN] ?: 0.0
        System.err.println("  ${done.incrementAndGet()}/${jobs.size}")
        Triple(seed, c, score)
    }.toList()
    fun of(seed: Long, c: String) = r.first { it.first == seed && it.second == c }.third
    println("people's score against the hand-written moves and scaffold ways, $size km, $years years, seeds $seeds (per seed, then mean):")
    val rows = configs.drop(1).map { c ->
        val rel = seeds.map { s -> 100 * (of(s, c) / of(s, "hand") - 1) }
        Triple(c, rel, rel.average())
    }
    for ((c, rel, m) in rows.sortedByDescending { kotlin.math.abs(it.third) }) println("%-28s %+6.2f%%   (%s)".format(label(c), m, rel.joinToString(" ") { "%+.2f".format(it) }))
}
