package inflation.tools

import inflation.Args
import inflation.Config
import inflation.ecology.BandWays
import inflation.ecology.Catalogue
import inflation.ecology.HexPolicy
import inflation.ecology.WAYS_OUT
import inflation.learning.HexPpo
import inflation.learning.Schema
import inflation.world.WorldConfig
import inflation.ecology.HexFeatures
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * How far people's score (as the training checks it: people-weeks per person at the start, less
 * their luck in births and deaths) can be moved at all by what bands choose: every band moving at
 * random, to the worst place, or staying; and every band holding one of its ways far below or above
 * the scaffold's (network output -2 or +2: about 0.24 or 1.76 times the scaffold) the whole time, the
 * others at the scaffold. "nudge" (every way at +0.01, a change too small to matter) shows how far the
 * score moves by the world's own knock-on effects alone. If no choice moves the score much beyond
 * that, no learner can gain much by it.
 *
 * Usage: hex-people-leverage [--seeds A,B] [--size KM] [--years N]
 * Config keys: leverageTests: all (default), moves (only the moves) or ways (only the ways). leverageWayValues (default none): values,
 * as multiples of the scaffold's (0.5,1,1.5), to hold each way at for every band in place of the network outputs -2 and +2; leverageWays
 * (default all): the numbers of the ways to test (numbered from 0 in the order of the network's outputs, comma-separated).
 */
fun main(args: Array<String>) {
    val a = Args(args, "hex-people-leverage [--seeds A,B] [--size KM] [--years N]")
    val seeds = a.string("--seeds")?.split(",")?.map { it.toLong() } ?: listOf(91L); val size = a.int("--size", 600); val years = a.int("--years", 3)
    val tests = Config.string(Config.Key.LEVERAGE_TESTS) ?: "all"
    require(tests in setOf("all", "moves", "ways")) { "leverageTests=$tests: all, moves or ways" }
    val moves = tests != "ways"; val ways = tests != "moves"
    val values = Config.string(Config.Key.LEVERAGE_WAY_VALUES)?.split(',')?.map { it.trim().toFloat() }
    val tested = Config.string(Config.Key.LEVERAGE_WAYS)?.split(',')?.map { it.trim().toInt() } ?: (0 until WAYS_OUT).toList()
    require(tested.all { it in 0 until WAYS_OUT }) { "leverageWays=$tested: the ways are numbered 0..${WAYS_OUT - 1}" }
    a.done()
    val kinds = Catalogue.learned
    val names = Schema.WAYS_OUT
    /** One trial: a name, and the way held (-1: none) at the network output [held] for every band. */
    class Trial(val name: String, val way: Int = -1, val held: Float = 0f, val label: String = name)
    val configs = buildList {
        add(Trial("hand")); add(Trial("nudge"))
        if (moves) { add(Trial("random")); add(Trial("worst")); add(Trial("stay")) }
        if (ways) for (j in tested) {
            if (values == null) { add(Trial("way-$j", j, -2f, "${names[j]} low")); add(Trial("way+$j", j, 2f, "${names[j]} high")) }
            else for (v in values) add(Trial("way$j@$v", j, forcedWayOutput(v), "${names[j]} x$v"))
        }
    }
    val jobs = seeds.flatMap { s -> configs.map { s to it } }
    val done = AtomicInteger()
    val r = jobs.parallelStream().map { (seed, c) ->
        val cfg = WorldConfig(seed = seed, widthKm = size, heightKm = size)
        val score = HexPpo.score(cfg, years, kinds) { run ->
            for (s in kinds) if (s.human) run.groups.policies[s] = HexPolicy { _, f, b, rnd ->
                when (c.name) {
                    "stay" -> f.indices.firstOrNull { f[it].getOrElse(HexFeatures.STAYS) { 0f } > 0.5f } ?: b.indices.maxBy { b[it] }
                    "random" -> rnd.nextInt(f.size)
                    "worst" -> b.indices.minBy { b[it] }
                    else -> b.indices.maxBy { b[it] }
                }
            }
            run.groups.ways = BandWays { _, _, out ->
                if (c.name == "nudge") for (i in 0 until WAYS_OUT) out[i] = 0.01f
                else if (c.way >= 0) out[c.way] = c.held
            }
        }[Catalogue.HUMAN] ?: 0.0
        System.err.println("  ${done.incrementAndGet()}/${jobs.size}")
        Triple(seed, c, score)
    }.toList()
    fun of(seed: Long, c: Trial) = r.first { it.first == seed && it.second === c }.third
    println("people's score against the hand-written moves and scaffold ways, $size km, $years years, seeds $seeds (per seed, then mean):")
    val rows = configs.drop(1).map { c ->
        val rel = seeds.map { s -> 100 * (of(s, c) / of(s, configs[0]) - 1) }
        Triple(c, rel, rel.average())
    }
    for ((c, rel, m) in rows.sortedByDescending { abs(it.third) }) println("%-28s %+6.2f%%   (%s)".format(c.label, m,
        rel.joinToString(" ") { "%+.2f".format(it) }))
}
