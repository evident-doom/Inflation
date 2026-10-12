package inflation.learning

import inflation.world.WorldConfig
import java.io.File

/**
 * Head-to-head test of two people networks: in each world, half the bands get one and half the
 * other, each scored as in [BandEvolve.evaluateBands] (person-weeks per person at the start).
 * Running both in the same worlds cancels most of the luck of the world itself.
 *
 * Usage: band-compare A.mlp B.mlp [--seeds 201,202,...] [--width KM] [--height KM] [--years N]
 */
object BandCompare {
    @JvmStatic
    fun main(args: Array<String>) {
        val a = Mlp.load(File(args[0]).readText())
        val b = Mlp.load(File(args[1]).readText())
        var seeds = (201L..206L).toList()
        var width = 500; var height = 1000
        var years = 5
        var k = 2
        while (k < args.size) {
            when (args[k]) {
                "--seeds" -> seeds = args[++k].split(",").map { it.toLong() }
                "--width" -> width = args[++k].toInt()
                "--height" -> height = args[++k].toInt()
                "--years" -> years = args[++k].toInt()
                else -> error("unknown argument ${args[k]}")
            }
            k++
        }
        val results = seeds.map { seed -> seed to BandEvolve.evaluateBands(WorldConfig(seed = seed, widthKm = width, heightKm = height), years, arrayOf(a, b)) }
        var wins = 0
        for ((seed, r) in results) {
            if (r.fitness[0] > r.fitness[1]) wins++
            println("seed %d: A %.3f  B %.3f  | %s".format(seed, r.fitness[0], r.fitness[1], r.summary))
        }
        println("A ahead in %d of %d worlds; mean A %.3f, B %.3f".format(wins, results.size,
            results.map { it.second.fitness[0] }.average(), results.map { it.second.fitness[1] }.average()))
    }
}
