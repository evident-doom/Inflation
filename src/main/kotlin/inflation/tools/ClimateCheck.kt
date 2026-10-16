package inflation.tools

import inflation.world.Biome
import inflation.world.World
import inflation.world.WorldConfig

/**
 * Generates a world and summarises its climate: yearly rain on land by latitude and by position
 * west to east, the share of it falling in summer, land by aridity, and the kinds of country.
 *
 * Usage: climate-check [--seed N] [--width KM] [--height KM]
 */
fun main(args: Array<String>) {
    val a = inflation.Args(args, "climate-check [--seed N] [--width KM] [--height KM]")
    val cfg = a.world(WorldConfig(seed = 1)); a.done()
    // (The land and its rocks from the kept landform where there is one: only the climate is made again.)
    val world = World.generate(cfg, inflation.world.LandformCache.load(cfg) {})
    val g = world.grid; val c = world.climate
    val land = (0 until g.size).filter { !world.isSea[it] }
    fun table(name: String, bins: Int, bin: (Int) -> Int) {
        println(name)
        for (b in 0 until bins) {
            val cs = land.filter { bin(it) == b }; if (cs.isEmpty()) continue
            val p = cs.map { c.annualPrecip[it].toDouble() }.average()
            val summer = cs.sumOf { (c.monthlyPrecip[5][it] + c.monthlyPrecip[6][it] + c.monthlyPrecip[7][it]).toDouble() } / cs.sumOf { c.annualPrecip[it].toDouble() }.coerceAtLeast(1.0)
            val pet = cs.map { c.annualPet[it].toDouble() }.average()
            println("  %d: land %6d km², rain %5.0f mm (summer %2.0f%%), evaporative demand %5.0f mm, runoff %4.0f mm, far from sea %4.0f km".format(
                b, cs.size, p, 100 * summer, pet, cs.map { c.runoff[it].toDouble() }.average(), cs.map { c.distanceToSeaKm[it].toDouble() }.average()))
        }
    }
    table("by latitude, north to south (fifths)", 5) { (g.y(it) * 5) / g.height }
    table("by position, west to east (fifths)", 5) { (g.x(it) * 5) / g.width }
    table("by distance from the sea (0-50, 50-150, 150-300, 300-600, 600+ km)", 5) { val d = c.distanceToSeaKm[it]; when { d < 50 -> 0; d < 150 -> 1; d < 300 -> 2; d < 600 -> 3; else -> 4 } }
    // To set beside real places at the same latitudes: rain and its summer share on low coasts and low inland.
    println("by latitude (3° bands): land under 300 m on the coast 0-50 km | inland 100+ km | high ground over 1000 m")
    var band = Math.floor(c.latitude.max() / 3) * 3
    while (band >= Math.floor(c.latitude.min() / 3) * 3) {
        fun line(cs: List<Int>): String {
            if (cs.size < 100) return "        -          "
            val p = cs.map { c.annualPrecip[it].toDouble() }.average()
            val summer = cs.sumOf { (c.monthlyPrecip[5][it] + c.monthlyPrecip[6][it] + c.monthlyPrecip[7][it]).toDouble() } / cs.sumOf { c.annualPrecip[it].toDouble() }.coerceAtLeast(1.0)
            return "%5.0f mm (summer %2.0f%%)".format(p, 100 * summer)
        }
        val inBand = land.filter { val l = c.latitude[g.y(it)]; l >= band && l < band + 3 }
        val low = inBand.filter { world.elevation[it] < 300 }
        println("  %2.0f-%2.0f°N: %s | %s | %s".format(band, band + 3, line(low.filter { c.distanceToSeaKm[it] < 50 }), line(low.filter { c.distanceToSeaKm[it] >= 100 }),
            line(inBand.filter { world.elevation[it] > 1000 })))
        band -= 3
    }
    val ar = listOf(0.05f, 0.2f, 0.5f, 0.65f, 1e9f)
    println("land by aridity: " + listOf("hyperarid", "arid", "semiarid", "dry-subhumid", "humid").mapIndexed { i, nm ->
        "%s %.1f%%".format(nm, 100.0 * land.count { c.aridity(it) >= (if (i == 0) 0f else ar[i - 1]) && c.aridity(it) < ar[i] } / land.size) }.joinToString(", "))
    println("kinds of country: " + Biome.entries.map { b -> b to land.count { world.biome[it] == b } }.filter { it.second > 0 }.sortedByDescending { it.second }
        .joinToString(", ") { "%s %.1f%%".format(it.first.name.lowercase(), 100.0 * it.second / land.size) })
}
