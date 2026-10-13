package inflation.ecology

import inflation.world.Date
import inflation.world.HexLand
import inflation.world.HexMap
import inflation.world.HexWorld
import inflation.world.Weather
import inflation.world.World
import inflation.world.WorldConfig

/**
 * Runs the resident plant-eaters on the hexes for some years over weather, water and plants, and
 * prints each kind's numbers year by year with the main causes of death, and the cost of a step.
 *
 * Usage: hex-fauna-check [--seed N] [--width KM] [--height KM] [--years N]
 */
fun main(args: Array<String>) {
    var cfg = WorldConfig(seed = 1)
    var years = 10
    var k = 0
    while (k < args.size) {
        when (args[k]) {
            "--seed" -> cfg = cfg.copy(seed = args[++k].toLong())
            "--width" -> cfg = cfg.copy(widthKm = args[++k].toInt())
            "--height" -> cfg = cfg.copy(heightKm = args[++k].toInt())
            "--years" -> years = args[++k].toInt()
            else -> error("unknown argument ${args[k]}")
        }
        k++
    }
    val world = World.generate(cfg)
    val hw = HexWorld(world, HexMap(world))
    val weather = Weather(hw.weatherPlaces(cfg), cfg)
    val land = HexLand(hw, cfg.seed)
    val fauna = HexFauna(hw, land, cfg.daysPerStep)
    val herds = HexGroups(hw, land, fauna, cfg.daysPerStep, cfg.seed)
    val stepsPerYear = Date.DAYS_PER_YEAR / cfg.daysPerStep
    println("year " + fauna.kinds.joinToString("") { "%11s".format(it.name.take(10)) })
    println("   0 " + fauna.kinds.indices.joinToString("") { "%11.0f".format(fauna.count(it)) })
    var ns = 0L
    val causes = java.util.TreeMap<String, Double>()
    for (y in 1..years) {
        repeat(stepsPerYear) {
            val t0 = System.nanoTime()
            weather.step(); land.step(weather); fauna.step(weather, weather.date); herds.step(weather, weather.date)
            ns += System.nanoTime() - t0
            for ((key, v) in fauna.deaths) causes.merge(key, v, Double::plus)
            for ((key, v) in herds.deaths) causes.merge(key, v.toDouble(), Double::plus)
            for ((key, v) in herds.kills) causes.merge("$key:hunted", v.toDouble(), Double::plus)
        }
        println("%4d ".format(y) + fauna.kinds.indices.joinToString("") { "%11.0f".format(fauna.count(it)) })
        println("     groups: " + herds.kinds.joinToString(", ") { "${it.name} ${herds.count(it)}" })
        println("     this week: kills " + herds.kills.entries.joinToString(", ") { "${it.key} ${it.value}" } + " | meetings " + herds.meetings.entries.joinToString(", ") { "${it.key} ${it.value}" })
    }
    println("deaths over the run, by cause:")
    for (s in fauna.kinds + herds.kinds) {
        val c = listOf("age", "hunger", "cold", "disease", "hunted", "other", "rivals").map { it to (causes["${s.name}:$it"] ?: 0.0) }
        val all = c.sumOf { it.second }.coerceAtLeast(1.0)
        println("  %-12s ".format(s.name) + c.filter { it.second > 0.0 }.joinToString(", ") { (k, v) -> "$k %.0f%%".format(100 * v / all) })
    }
    // Where each kind lives at the end: share in each height band.
    println("share of each kind by height band (lowest first):")
    for ((i, s) in fauna.kinds.withIndex()) {
        val byBand = DoubleArray(HexWorld.BANDS_M.size)
        for (p in 0 until fauna.n) byBand[hw.placeBand[p]] += ((fauna.adults[i][p] + fauna.young[i][p]) * land.areaKm2[p]).toDouble()
        val tot = byBand.sum().coerceAtLeast(1.0)
        println("  %-12s ".format(s.name) + byBand.joinToString(" ") { "%3.0f%%".format(100 * it / tot) })
    }
    println("a step of weather, water, plants and resident herds: %.1f ms".format(ns / 1e6 / (years * stepsPerYear)))
}
