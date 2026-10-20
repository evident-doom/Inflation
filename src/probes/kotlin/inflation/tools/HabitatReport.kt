package inflation.tools

import inflation.ecology.Place
import inflation.Args
import inflation.ecology.HexSim
import inflation.world.Biome
import inflation.world.World
import inflation.world.WorldConfig

/**
 * How much of the map each kind of country covers, and for each kind of animal how much land suits
 * it: the climate the year round (no month beyond what its coat bears by more than a few degrees),
 * its preferred ground, water within reach, and its food; and in which kinds of country that land lies.
 *
 * Usage: habitat-report [--seed N]
 */
fun main(args: Array<String>) {
    val a = Args(args, "habitat-report [--seed N]")
    val cfg = WorldConfig(seed = a.long("--seed", 1))
    a.done()
    val sim = HexSim(World.generate(cfg))
    val land = sim.land; val hw = sim.hw; val fauna = sim.fauna
    val n = land.n
    val biomeOf = hw.placeBiome
    val total = land.areaKm2.sum()
    println("land %.0f km², by kind of country:".format(total))
    val byBiome = DoubleArray(Biome.entries.size)
    for (p in 0 until n) byBiome[biomeOf[p]] += land.areaKm2[p].toDouble()
    for (b in Biome.entries.sortedByDescending { byBiome[it.ordinal] }) if (byBiome[b.ordinal] > 0) println("  %-24s %6.1f%%".format(b.name.lowercase(),
        100 * byBiome[b.ordinal] / total))
    println("land that suits each kind (climate fit over 0.8, ground it likes, water in reach, food), and where it lies:")
    for (s in fauna.kinds + sim.groups.kinds) {
        val good = DoubleArray(Biome.entries.size)
        for (p in 0 until n) {
            val climate = fauna.climateSuits(s, Place(p)) > 0.8f
            val ground = fauna.suits(s, Place(p)) >= 0.8f
            val water = fauna.usualTether(s, Place(p)) > 0.5f
            val food = s.carnivore || fauna.foodFor(s, Place(p)) > 0.1f
            if (climate && ground && water && food) good[biomeOf[p]] += land.areaKm2[p].toDouble()
        }
        val sum = good.sum()
        val where = Biome.entries.filter { good[it.ordinal] > 0.05 * sum }.sortedByDescending { good[it.ordinal] }
            .joinToString(", ") { "%s %.0f%%".format(it.name.lowercase().replace('_', ' '), 100 * good[it.ordinal] / sum) }
        println("  %-14s %5.1f%% of the land (%6.0f km²)  %s".format(s.name, 100 * sum / total, sum, where))
    }
}
