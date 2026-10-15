package inflation.tools

import inflation.ecology.Catalogue
import inflation.ecology.HexPolicy
import inflation.learning.HexLearning
import inflation.world.Date
import inflation.world.WorldConfig

/**
 * How much where people move matters to how they do: the same worlds run with people choosing by the
 * hand-written behaviour, staying put whenever they may, going to a place at random, and to the
 * hand-written worst; the people-weeks per person at the start over the years (as the training
 * checks score it), relative to the hand-written. If even moving at random costs little, a learner
 * that moves better can gain little by that score.
 *
 * Usage: hex-move-headroom [--worlds N] [--size KM] [--years N]
 */
fun main(args: Array<String>) {
    var worlds = 4; var size = 1000; var years = 3
    var k = 0
    while (k < args.size) { when (args[k]) { "--worlds" -> worlds = args[++k].toInt(); "--size" -> size = args[++k].toInt(); "--years" -> years = args[++k].toInt() }; k++ }
    val kinds = Catalogue.all.filter { it in Catalogue.migratory || it.carnivore }
    val ways = listOf("hand-written", "stay", "random", "worst")
    fun score(seed: Long, way: String): Double {
        val run = HexLearning.Run(WorldConfig(seed = seed, widthKm = size, heightKm = size), kinds)
        for (s in kinds) if (s.human) run.groups.policies[s] = HexPolicy { _, f, b, rnd ->
            when (way) {
                "stay" -> f.indices.firstOrNull { f[it].getOrElse(10) { 0f } > 0.5f } ?: b.indices.maxBy { b[it] }
                "random" -> rnd.nextInt(f.size)
                "worst" -> b.indices.minBy { b[it] }
                else -> b.indices.maxBy { b[it] }
            }
        }
        val start = run.groups.groups.filter { it.alive && it.species.human }.sumOf { it.size }.toDouble()
        val steps = years * Date.DAYS_PER_YEAR / WorldConfig().daysPerStep
        var weeks = 0.0
        repeat(steps) { run.step(); weeks += run.groups.groups.filter { it.alive && it.species.human }.sumOf { it.size } }
        return if (start <= 0.0) 0.0 else weeks / start / steps
    }
    val jobs = (0 until worlds).flatMap { w -> ways.map { w to it } }
    val r = jobs.parallelStream().map { (w, way) -> Triple(w, way, score(91L + w, way)) }.toList()
    for (way in ways) {
        val rel = (0 until worlds).map { w -> r.first { it.first == w && it.second == way }.third / r.first { it.first == w && it.second == "hand-written" }.third - 1.0 }
        println("%-12s people-weeks per person %.3f, against hand-written %+.1f%% (worlds: %s)".format(way,
            r.filter { it.second == way }.map { it.third }.average(), 100 * rel.average(), rel.joinToString(" ") { "%+.1f".format(100 * it) }))
    }
}
