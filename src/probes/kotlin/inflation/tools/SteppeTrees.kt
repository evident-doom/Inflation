package inflation.tools

import inflation.tools.fauna.FaunaCheckOptions
import inflation.world.Biome
import inflation.world.Place

/**
 * How wooded the places called steppe are: by the label of the place now ([inflation.world.HexCountry.kind]) and by
 * world-gen's ([inflation.world.HexWorld.placeBiome]), the land's share by tree stand, with each band's crown share as the
 * light reads it ([inflation.world.HexPlants.treeLight]), canopy as the labels and cover read it ([inflation.world.HexPlants.canopy]),
 * glades, shrubs, herbs, rain and aridity. After one step of the run.
 *
 * Usage: steppe-trees (--world NAME | --seed N [--width KM] [--height KM])
 */
fun main(args: Array<String>) {
    val sim = FaunaCheckOptions.parse((args.toList() + listOf("--years", "1")).toTypedArray()).start()
    sim.step()
    val land = sim.land; val pl = land.plants; val n = land.n; val c = land.hw.world.climate
    val bands = listOf(0.1f, 0.3f, 0.55f, 1f, 2f, 4f, Float.MAX_VALUE)
    val names = listOf("<0.1", "0.1-0.3", "0.3-0.55", "0.55-1", "1-2", "2-4", ">4")
    fun report(title: String, inSet: (Int) -> Boolean) {
        val idx = (0 until n).filter(inSet); if (idx.isEmpty()) return
        val all = idx.sumOf { land.areaKm2[it].toDouble() }
        println("$title: %.0f km² (%.1f%% of land)".format(all, 100 * all / (0 until n).sumOf { land.areaKm2[it].toDouble() }))
        println("  tree kg/m²  area%%  crowns(light) canopy(label)  glade  shrub  herb   rain mm  aridity")
        for ((b, hi) in bands.withIndex()) {
            val lo = if (b == 0) -1f else bands[b - 1]
            val ps = idx.filter { pl.tree[it] > lo && pl.tree[it] <= hi }; if (ps.isEmpty()) continue
            val a = ps.sumOf { land.areaKm2[it].toDouble() }
            fun m(f: (Int) -> Float) = ps.sumOf { f(it).toDouble() * land.areaKm2[it] } / a
            println("  %-9s %6.1f  %12.2f %13.2f %6.2f %6.2f %5.3f %8.0f %8.2f".format(names[b], 100 * a / all,
                m { pl.treeLight(Place(it)) }, m { pl.canopy(Place(it)) }, m { pl.gladeArea(Place(it)) }, m { pl.shrub[it] }, m { pl.herb[it] },
                m { land.mean(it) { i -> c.annualPrecip[i] } }, m { land.mean(it) { i -> c.aridity(i) } }))
        }
        val lit = idx.filter { pl.treeLight(Place(it)) >= 0.15f }.sumOf { land.areaKm2[it].toDouble() }
        println("  crowns >= 0.15 (drawn with glades): %.1f%% of it".format(100 * lit / all))
    }
    report("steppe now") { land.country.kind[it] == Biome.STEPPE.ordinal }
    report("steppe at world-gen") { land.hw.placeBiome[it] == Biome.STEPPE.ordinal }
    report("open woodland now") { land.country.kind[it] == Biome.OPEN_WOODLAND.ordinal }
}
