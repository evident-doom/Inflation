package inflation.tools

import inflation.ecology.HexSim


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
    var uniform = false
    var k = 0
    while (k < args.size) {
        when (args[k]) {
            "--seed" -> cfg = cfg.copy(seed = args[++k].toLong())
            "--width" -> cfg = cfg.copy(widthKm = args[++k].toInt())
            "--height" -> cfg = cfg.copy(heightKm = args[++k].toInt())
            "--years" -> years = args[++k].toInt()
            "--uniform" -> uniform = true
            else -> error("unknown argument ${args[k]}")
        }
        k++
    }
    val sim = HexSim(World.generate(cfg), uniformStart = uniform)
    val hw = sim.hw; val weather = sim.weather; val land = sim.land; val fauna = sim.fauna; val herds = sim.groups
    val stepsPerYear = Date.DAYS_PER_YEAR / cfg.daysPerStep
    println("year " + fauna.kinds.joinToString("") { "%11s".format(it.name.take(10)) })
    println("   0 " + fauna.kinds.indices.joinToString("") { "%11.0f".format(fauna.count(it)) })
    // How well this month's climate suits each kind where its groups are, by head count.
    fun fit() = herds.kinds.joinToString(", ") { s ->
        val gs = herds.groups.filter { it.alive && it.species == s }
        val heads = gs.sumOf { it.size }.coerceAtLeast(1)
        "%s %.2f".format(s.name, gs.sumOf { it.size * fauna.monthFit(s, it.place, weather.date.month).toDouble() } / heads)
    }
    println("     climate fit: " + fit())
    // How much of the land each kind's climate suits at all, and how strongly its groups feel drawn elsewhere.
    fun drawn() = herds.kinds.withIndex().joinToString(", ") { (k, s) ->
        val gs = herds.groups.filter { it.alive && it.species == s }
        val pull = gs.sumOf { herds.bearings.strength(k, hw.placeHex[it.place]).toDouble() } / gs.size.coerceAtLeast(1)
        val good = (0 until fauna.n).sumOf { p -> if (fauna.climateSuits(s, p) > 0.8f) land.areaKm2[p].toDouble() else 0.0 } / land.areaKm2.sum()
        "%s %.0f%% / %.2f".format(s.name, 100 * good, pull)
    }
    run {
        val t0 = System.nanoTime(); herds.bearings.month
        val t1 = System.nanoTime(); herds.bearings.update(0); fauna.bearings.update(0)
        println("sense of direction: setting up %.0f ms, a month's update for all kinds %.0f ms".format((t1 - t0) / 1e6, (System.nanoTime() - t1) / 1e6))
    }
    // Where the matter is: each pool over the whole map, thousand tonnes.
    fun budget(): String {
        val sl = fauna.small
        fun perM2(a: FloatArray) = a.indices.sumOf { (a[it] * land.areaKm2[it]).toDouble() } // kg/m² × km² = 1e6 kg = 1000 t
        fun perKm2(a: FloatArray) = perM2(a) / 1e6
        val plants = (0 until fauna.n).sumOf { (land.biomass(it) * land.areaKm2[it]).toDouble() }
        val smallAnimals = listOf(sl.insects, sl.rodents, sl.herps, sl.pollinators, sl.hares, sl.hyrax, sl.tortoises, sl.foxes, sl.birds).sumOf { perKm2(it) }
        val residents = fauna.kinds.indices.sumOf { fauna.count(it) * fauna.kinds[it].massKg } / 1e6
        val groups = herds.groups.filter { it.alive }.sumOf { (it.adults + 0.3 * it.young) * it.species.massKg } / 1e6
        return "plants %.0f, litter %.0f, soil organic %.0f, carrion %.2f, small animals %.2f, resident herds %.2f, herds and packs %.2f (thousand t)".format(
            plants, perM2(sl.litter), perM2(sl.organicMatter), perM2(sl.carrion), smallAnimals, residents, groups)
    }
    println("     matter: " + budget())
    var ns = 0L
    val causes = java.util.TreeMap<String, Double>()
    for (y in 1..years) {
        repeat(stepsPerYear) { w ->
            if (w == stepsPerYear / 2) println("     midsummer, land that suits / pull felt: " + drawn())
            val t0 = System.nanoTime()
            sim.step()
            ns += System.nanoTime() - t0
            for ((key, v) in fauna.deaths) causes.merge(key, v, Double::plus)
            for ((key, v) in herds.deaths) causes.merge(key, v.toDouble(), Double::plus)
            for ((key, v) in herds.kills) causes.merge("$key:hunted", v.toDouble(), Double::plus)
        }
        println("%4d ".format(y) + fauna.kinds.indices.joinToString("") { "%11.0f".format(fauna.count(it)) })
        println("     small life: " + fauna.small.summary())
        println("     matter: " + budget())
        println("     climate fit: " + fit())
        println("     land that suits / pull felt: " + drawn())
        println("     groups: " + herds.kinds.joinToString(", ") { "${it.name} ${herds.count(it)}" })
        println("     this week: kills " + herds.kills.entries.joinToString(", ") { "${it.key} ${it.value}" } + " | meetings " + herds.meetings.entries.joinToString(", ") { "${it.key} ${it.value}" })
    }
    println("deaths over the run, by cause:")
    for (s in fauna.kinds + herds.kinds) {
        val c = causes.entries.filter { it.key.substringBefore(":") == s.name }.map { it.key.substringAfter(":") to it.value }.sortedByDescending { it.second }
        val all = c.sumOf { it.second }.coerceAtLeast(1.0)
        println("  %-12s ".format(s.name) + c.filter { 100 * it.second / all >= 0.5 }.joinToString(", ") { (k, v) -> "$k %.0f%%".format(100 * v / all) })
    }
    // Where each kind lives at the end: share in each height band.
    println("share of each kind by height band (lowest first):")
    for ((i, s) in fauna.kinds.withIndex()) {
        val byBand = DoubleArray(HexWorld.BANDS_M.size)
        for (p in 0 until fauna.n) byBand[hw.placeBand[p]] += ((fauna.adults[i][p] + fauna.young[i][p]) * land.areaKm2[p]).toDouble()
        val tot = byBand.sum().coerceAtLeast(1.0)
        println("  %-12s ".format(s.name) + byBand.joinToString(" ") { "%3.0f%%".format(100 * it / tot) })
    }
    println("a step of weather, water, plants and all animals: %.1f ms, of it settling meetings %.1f ms, the sense of direction %.1f ms".format(ns / 1e6 / (years * stepsPerYear), herds.meetNs / 1e6 / (years * stepsPerYear), (herds.bearings.updateNs + fauna.bearings.updateNs) / 1e6 / (years * stepsPerYear)))
}
