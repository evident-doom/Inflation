package inflation.tools

import inflation.tools.fauna.FaunaCheckOptions
import inflation.world.Biome
import inflation.world.Place

/**
 * How wooded the places called steppe are: by the label of the place now ([inflation.world.HexCountry.kind]) and by
 * world-gen's ([inflation.world.HexWorld.placeBiome]), the land's share by tree stand, with each band's crown share as the
 * light reads it ([inflation.world.HexPlants.treeLight]), canopy as the labels and cover read it ([inflation.world.HexPlants.canopy]),
 * glades, shrubs, herbs, rain and aridity. After one step of the run. Then, over a year, what holds the trees down in the
 * places called steppe where the climate is humid (rain over demand at least [HUMID]): their world-gen kinds, and by how
 * their trees changed over the year, the browsing and grazing, fire, drought, people's clearing, moisture and soil.
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

    val humid = (0 until n).filter { land.country.kind[it] == Biome.STEPPE.ordinal && land.mean(it) { i -> c.aridity(i) } >= HUMID }
    // (And the dry steppe of world-gen, rain under [DRY] of the demand: where the trees should stay out.)
    val dry = (0 until n).filter { land.hw.placeBiome[it] == Biome.STEPPE.ordinal && land.mean(it) { i -> c.aridity(i) } < DRY }
    val ha = humid.sumOf { land.areaKm2[it].toDouble() }
    println("\nsteppe now with aridity >= $HUMID: %.1f%% of land; its world-gen kinds: ".format(100 * ha / (0 until n).sumOf { land.areaKm2[it].toDouble() }) +
        humid.groupBy { land.hw.placeBiome[it] }.mapValues { e -> e.value.sumOf { land.areaKm2[it].toDouble() } }.entries.sortedByDescending { it.value }
            .joinToString(", ") { "%s %.0f%%".format(Biome.entries[it.key].name.lowercase(), 100 * it.value / ha) })
    // (Months of the normal year in growing warmth, over 10 °C, with rain under half the demand: the dry season the trees must bear.)
    fun dryMonths(p: Int) = land.mean(p) { i -> (0 until 12).count { m -> c.monthlyTemp[m][i] > 10f && c.monthlyPrecip[m][i] < 0.5f * c.monthlyPet[m][i] }.toFloat() }
    val forest = (0 until n).filter { land.country.kind[it] == Biome.TEMPERATE_FOREST.ordinal || land.country.kind[it] == Biome.CONIFER_FOREST.ordinal }
    val tree0 = FloatArray(n) { pl.tree[it] }
    val deepMean = DoubleArray(n); val deepMin = DoubleArray(n) { 1.0 }; val shrubLight = DoubleArray(n); val treeShade = DoubleArray(n); val herbLight = DoubleArray(n)
    val ls = FloatArray(3)
    fun deepFill(p: Int): Double { val pp = Place(p); val h = land.hydro; return ((h.soil[p] - h.soilTop[p]) / (h.soilCap[p] - h.topCap(pp))).toDouble() }
    fun watch(p: Int) { val d = deepFill(p); deepMean[p] += d; deepMin[p] = minOf(deepMin[p], d); pl.lightShares(Place(p), ls); herbLight[p] += ls[0].toDouble(); shrubLight[p] += ls[1].toDouble(); treeShade[p] += ls[2].toDouble() }
    val browse = DoubleArray(n); val graze = DoubleArray(n); val burnt = DoubleArray(n); val drought = DoubleArray(n); val tTree = DoubleArray(n)
    val steps = sim.stepsPerYear
    // (Dry places, rain under [DRY] of the demand, with a floodplain: where gallery woods along the rivers should stand.)
    val valleys = (0 until n).filter { land.floodShare[it] > 0f && land.mean(it) { i -> c.aridity(i) } < DRY }
    val rootedMean = DoubleArray(n); val wetMean = DoubleArray(n); val tableMean = DoubleArray(n); val floorDrought = DoubleArray(n)
    for (s in 0 until steps) {
        sim.step()
        for (p in valleys) { val pp = Place(p); val h = land.hydro
            wetMean[p] += h.wetFloor(pp).toDouble(); rootedMean[p] += maxOf(0f, h.wetFloor(pp) - h.saturatedFloor(pp)).toDouble(); tableMean[p] += h.floorTableM(pp).toDouble(); floorDrought[p] += pl.droughtTree[p].toDouble() }
        for (p in forest) { drought[p] += pl.droughtTree[p].toDouble(); watch(p) }
        for (p in humid + dry) watch(p)
        for (p in humid + dry) { browse[p] += pl.browsing[p].toDouble(); graze[p] += pl.grazing[p].toDouble(); burnt[p] += land.fire.burning[p].toDouble()
            drought[p] += pl.droughtTree[p].toDouble(); tTree[p] += land.hydro.transpired[2][p].toDouble() }
    }
    fun drivers(title: String, set: List<Int>) {
    if (set.isEmpty()) return
    val sa = set.sumOf { land.areaKm2[it].toDouble() }
    println(title)
    println("  over a year, by the trees at the start and their change: area%, tree kg/m² start->end, browsing and grazing kg/km²/day, share burnt, tree drought (mean), trees transpired mm, opened, moisture, soil, dry months")
    // (By the trees at the start too: none to speak of, thin, a stand.)
    val groups = listOf(
        "none, came up" to { p: Int -> tree0[p] < 0.1f && pl.tree[p] >= 0.1f }, "none, stayed" to { p: Int -> tree0[p] < 0.1f && pl.tree[p] < 0.1f },
        "thin, fell" to { p: Int -> tree0[p] in 0.1f..4f && pl.tree[p] < 0.9f * tree0[p] }, "thin, held" to { p: Int -> tree0[p] in 0.1f..4f && pl.tree[p] in 0.9f * tree0[p]..1.1f * tree0[p] },
        "thin, grew" to { p: Int -> tree0[p] in 0.1f..4f && pl.tree[p] > 1.1f * tree0[p] }, "stand > 4" to { p: Int -> tree0[p] > 4f })
    for ((name, test) in groups) {
        val ps = set.filter(test); if (ps.isEmpty()) continue
        val a = ps.sumOf { land.areaKm2[it].toDouble() }
        fun m(f: (Int) -> Double) = ps.sumOf { f(it) * land.areaKm2[it] } / a
        println("  %-14s %5.1f%%  %.2f->%.2f  %7.0f %7.0f  %.3f  %.2f  %5.0f  %.2f  %.2f  %.2f".format(name, 100 * a / sa, m { tree0[it].toDouble() }, m { pl.tree[it].toDouble() },
            m { browse[it] / steps }, m { graze[it] / steps }, m { burnt[it] }, m { drought[it] / steps }, m { tTree[it] }, m { pl.opened[it].toDouble() },
            m { pl.moisture[it].toDouble() }, m { pl.soil(Place(it)).toDouble() }) + "  %.1f".format(m { dryMonths(it).toDouble() }) +
            "  deep fill mean %.2f min %.2f; light herbs/shrubs/trees %.2f/%.2f/%.2f; shrubs %.2f kg/m²".format(m { deepMean[it] / steps }, m { deepMin[it] },
                m { herbLight[it] / steps }, m { shrubLight[it] / steps }, m { treeShade[it] / steps }, m { pl.shrub[it].toDouble() }) +
            "; aridity %.2f, wet floor %.2f, waterlogged %.2f".format(m { land.mean(it) { i -> c.aridity(i) }.toDouble() }, m { land.hydro.wetFloor(Place(it)).toDouble() }, m { land.waterlogged[it].toDouble() }))
    }
    }
    drivers("humid steppe now (aridity >= $HUMID)", humid)
    drivers("\ndry world-gen steppe (aridity < $DRY)", dry)
    if (forest.isNotEmpty()) {
        val a = forest.sumOf { land.areaKm2[it].toDouble() }
        fun m(f: (Int) -> Double) = forest.sumOf { f(it) * land.areaKm2[it] } / a
        println("  forest now, for comparison: tree %.2f kg/m², tree drought (mean) %.2f, dry months %.1f, aridity %.2f".format(m { pl.tree[it].toDouble() },
            m { drought[it] / steps }, m { dryMonths(it).toDouble() }, m { land.mean(it) { i -> c.aridity(i) }.toDouble() }) +
            "; deep fill mean %.2f min %.2f; light herbs/shrubs/trees %.2f/%.2f/%.2f; shrubs %.2f kg/m²".format(m { deepMean[it] / steps }, m { deepMin[it] },
                m { herbLight[it] / steps }, m { shrubLight[it] / steps }, m { treeShade[it] / steps }, m { pl.shrub[it].toDouble() }))
    }
    // (And dry places along a river or stream with no floodplain mapped in them: the narrow valleys the wet floor misses.)
    val noFloor = (0 until n).filter { land.floodShare[it] <= 0f && land.drink.nearRiver[it] >= 0.3f && land.mean(it) { i -> c.aridity(i) } < DRY }
    if (noFloor.isNotEmpty()) {
        val a = noFloor.sumOf { land.areaKm2[it].toDouble() }
        fun m(f: (Int) -> Double) = noFloor.sumOf { f(it) * land.areaKm2[it] } / a
        println("\ndry places by a stream (near-river share >= 0.3) with no floodplain: %.1f%% of land; tree %.2f kg/m², crowns %.2f, under 0.1 kg/m² %.1f%%, near-river share %.2f".format(
            100 * a / (0 until n).sumOf { land.areaKm2[it].toDouble() }, m { pl.tree[it].toDouble() }, m { pl.treeLight(Place(it)).toDouble() },
            100 * noFloor.filter { pl.tree[it] < 0.1f }.sumOf { land.areaKm2[it].toDouble() } / a, m { land.drink.nearRiver[it].toDouble() }))
    }
    if (valleys.isNotEmpty()) {
        val all = valleys.sumOf { land.areaKm2[it].toDouble() }
        println("\ndry places (aridity < $DRY) with a floodplain: %.1f%% of land; over the year, by floodplain share: area%%, floodplain, wet floor, rooted floor (wet, not sodden), floor table m, tree kg/m², crowns, shrubs kg/m², tree drought".format(
            100 * all / (0 until n).sumOf { land.areaKm2[it].toDouble() }))
        for ((lo, hi) in listOf(0f to 0.05f, 0.05f to 0.15f, 0.15f to 0.3f, 0.3f to 1.01f)) {
            val ps = valleys.filter { land.floodShare[it] > lo && land.floodShare[it] <= hi }; if (ps.isEmpty()) continue
            val a = ps.sumOf { land.areaKm2[it].toDouble() }
            fun m(f: (Int) -> Double) = ps.sumOf { f(it) * land.areaKm2[it] } / a
            println("  %4.2f-%4.2f %6.1f %6.3f %6.3f %6.3f %6.2f %6.2f %6.3f %6.2f %6.2f".format(lo, minOf(hi, 1f), 100 * a / all, m { land.floodShare[it].toDouble() },
                m { wetMean[it] / steps }, m { rootedMean[it] / steps }, m { tableMean[it] / steps }, m { pl.tree[it].toDouble() },
                m { pl.treeLight(Place(it)).toDouble() }, m { pl.shrub[it].toDouble() }, m { floorDrought[it] / steps }))
        }
    }
}

/** Rain over evaporative demand from which a place counts as humid for [main]'s second part. */
private const val HUMID = 0.65f
/** Rain over evaporative demand under which world-gen steppe counts as dry for [main]'s second part (UNEP semi-arid and drier). */
private const val DRY = 0.45f
