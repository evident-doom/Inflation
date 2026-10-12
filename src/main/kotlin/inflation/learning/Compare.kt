package inflation.learning

import inflation.world.WorldConfig
import java.io.File

/**
 * Head-to-head test of two wolf networks: in each world, half the packs get one and half the
 * other, and each is scored as in [evaluate] (wolf-days per starting pack). Running both in the
 * same worlds cancels most of the luck of the world itself.
 *
 * Usage: compare A.mlp B.mlp [--seeds 101,102,...] [--size KM] [--years N]
 */
fun main(args: Array<String>) {
    val a = Mlp.load(File(args[0]).readText())
    val b = Mlp.load(File(args[1]).readText())
    var seeds = (101L..108L).toList()
    var size = 800
    var years = 3
    var k = 2
    while (k < args.size) {
        when (args[k]) {
            "--seeds" -> seeds = args[++k].split(",").map { it.toLong() }
            "--size" -> size = args[++k].toInt()
            "--years" -> years = args[++k].toInt()
            else -> error("unknown argument ${args[k]}")
        }
        k++
    }
    val results = seeds.parallelStream().map { seed ->
        seed to evaluate(WorldConfig(seed = seed, widthKm = size, heightKm = size), years, arrayOf(a, b))
    }.toList()
    var wins = 0
    for ((seed, r) in results) {
        if (r.fitness[0] > r.fitness[1]) wins++
        println("seed %d: A %.1f  B %.1f  | %s".format(seed, r.fitness[0], r.fitness[1], r.summary))
    }
    println("A ahead in %d of %d worlds; mean A %.1f, B %.1f".format(wins, results.size,
        results.map { it.second.fitness[0] }.average(), results.map { it.second.fitness[1] }.average()))
}
