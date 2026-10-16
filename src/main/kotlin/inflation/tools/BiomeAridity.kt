package inflation.tools

import inflation.world.Biome
import inflation.world.World
import inflation.world.WorldConfig
import inflation.world.LandformCache

/**
 * Generates a world and tells, for each class of aridity (rain over evaporative demand), how much land
 * it covers, its height, rain, demand and winter, and the kinds of country it became; then how much land
 * lies far from the sea, and the shares of the dry kinds of country.
 *
 * Usage: biome-aridity [--seed N] [--width KM] [--height KM]
 */
fun main(args: Array<String>) {
    val a = inflation.Args(args, "biome-aridity [--seed N] [--width KM] [--height KM]")
    val cfg = a.world(WorldConfig(seed = 1)); a.done()
    val w = World.generate(cfg, LandformCache.load(cfg) {}); val c = w.climate
    val land = (0 until w.grid.size).filter { !w.isSea[it] }
    val classes = listOf("<0.05" to 0.05f, "0.05-0.2" to 0.2f, "0.2-0.45" to 0.45f, "0.45-0.75" to 0.75f, ">0.75" to 1e9f)
    fun cls(i: Int): Int { val a = c.aridity(i); return classes.indexOfFirst { a < it.second } }
    println("seed ${cfg.seed} ${cfg.widthKm}x${cfg.heightKm}: land ${land.size} cells")
    for ((ci, cp) in classes.withIndex()) {
        val cs = land.filter { cls(it) == ci }; if (cs.isEmpty()) continue
        val by = cs.groupingBy { w.biome[it] }.eachCount().entries.sortedByDescending { it.value }.take(5)
        val elev = cs.map { w.elevation[it].toDouble() }.average()
        val warm = cs.map { c.warmestMonthTemp(it).toDouble() }.average(); val cold = cs.map { c.coldestMonthTemp(it).toDouble() }.average()
        val rain = cs.map { c.annualPrecip[it].toDouble() }.average(); val pet = cs.map { c.annualPet[it].toDouble() }.average()
        println("  aridity %-9s %5.1f%% of land, mean %4.0f m, rain %4.0f mm, PET %4.0f mm, warmest %4.1f, coldest %5.1f C: %s".format(cp.first, 100.0 * cs.size / land.size, elev, rain, pet, warm, cold,
            by.joinToString(", ") { "%s %.0f%%".format(it.key.name.lowercase(), 100.0 * it.value / cs.size) }))
    }
    val far = land.count { c.distanceToSeaKm[it] >= 300f }; val far150 = land.count { c.distanceToSeaKm[it] >= 150f }
    println("  land 150+ km from the sea %.1f%%, 300+ km %.1f%%; latitude %.1f-%.1f N".format(100.0 * far150 / land.size, 100.0 * far / land.size, c.latitude.min(), c.latitude.max()))
    println("  kinds of country: " + Biome.entries.map { b -> b to land.count { w.biome[it] == b } }.filter { 100.0 * it.second / land.size >= 0.5 }.sortedByDescending { it.second }.joinToString(", ") { "%s %.1f%%".format(it.first.name.lowercase(), 100.0 * it.second / land.size) })
    val dry = listOf(Biome.STEPPE, Biome.SEMI_DESERT, Biome.DESERT, Biome.SHRUBLAND).associateWith { b -> 100.0 * land.count { w.biome[it] == b } / land.size }
    println("  dry country: " + dry.entries.joinToString(", ") { "%s %.1f%%".format(it.key.name.lowercase(), it.value) })
}
