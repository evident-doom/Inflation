package inflation.tools

import inflation.run.TestWorlds
import inflation.Args
import inflation.ecology.HexSim
import inflation.world.Biome
import inflation.world.RiverClass
import inflation.world.WorldConfig
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * How much hexes differ from their neighbours: whether the map's fields (warmth, rain, soil,
 * plants, residents) change only in slow gradients or have hexes that stand out from those
 * around them. For each field, over inland hexes (all land, and so are their six neighbours):
 *
 * - its spread over the map (sd);
 * - how alike hexes k apart are: the semivariance at k hexes over the map's variance (0: the same
 *   as their neighbours, 1: no more alike than any two hexes on the map);
 * - the local anomaly: a hex's value less the mean of its six neighbours, its sd against the map's;
 * - local peaks and pits: the share of hexes above (below) all six neighbours (1/7 = 14% if hexes
 *   were drawn at random; a smooth gradient has almost none), and how far a peak stands above its
 *   neighbours' mean, in map sd;
 * - for fields made from the 1 km cells: the spread of the cells within a hex against the spread
 *   between hexes (how much the hex mean hides);
 * - the grid: neighbour semivariance along each of the three hex axes (E-W, SE-NW, SW-NE), which
 *   would differ if the square 1 km grid showed through, and how many fine cells make up a hex.
 *
 * Usage: hex-variance (--world NAME | --seed N [--width KM] [--height KM])
 * With --world the settled state is loaded, and plants and residents are measured as they stand.
 */
fun main(args: Array<String>) {
    val a = Args(args, "hex-variance (--world NAME | --seed N [--width KM] [--height KM])")
    val named = a.string("--world")?.let { TestWorlds.named(it) }
    var cfg = named?.config ?: WorldConfig(seed = 1)
    cfg = cfg.copy(seed = a.long("--seed", cfg.seed), widthKm = a.int("--width", cfg.widthKm), heightKm = a.int("--height", cfg.heightKm))
    a.done()
    val t0 = System.nanoTime()
    val sim = HexSim(inflation.world.WorldCache.load(cfg) { })
    named?.let { TestWorlds.check(it, sim); inflation.ecology.HexSnapshot.load(sim, it.state); println("loaded ${it.state} at ${sim.weather.date}") }
    println("world ready in %.0f s".format((System.nanoTime() - t0) / 1e9))
    val world = sim.world; val hw = sim.hw; val hex = sim.hex; val land = sim.land; val fauna = sim.fauna
    val grid = world.grid
    val H = hex.size

    // Inland hexes: all land, and so are their neighbours.
    val full = BooleanArray(H) { hw.land[it] >= 0.999f }
    val inland = (0 until H).filter { h -> full[h] && (0 until 6).all { d -> hex.neighbour(h, d).let { it >= 0 && full[it] } } }
    val isIn = BooleanArray(H).also { for (h in inland) it[h] = true }
    println("${inland.size} inland hexes of ${(0 until H).count { hw.land[it] > 0f }} with land; hex ${hex.hexKm} km")

    // Fine cells per hex, and the fine cells of each hex's land.
    val cellsOf = Array(H) { ArrayList<Int>() }
    for (i in 0 until grid.size) if (!world.isSea[i] && !world.water.isLake(i)) cellsOf[hex.owner[i]] += i
    val counts = inland.map { hex.cells[it] }.groupingBy { it }.eachCount().toSortedMap()
    println("fine cells per inland hex: " + counts.entries.joinToString(", ") { "${it.key}: ${it.value}" })

    // Hex distance by breadth over the rings, for the lags measured.
    val lags = intArrayOf(1, 2, 3, 5, 8, 13, 20)
    val maxLag = lags.last()
    val ringsOf: Array<Array<IntArray>> = Array(H) { emptyArray() }
    java.util.stream.IntStream.range(0, inland.size).parallel().forEach { idx ->
        val h = inland[idx]
        val seen = HashSet<Int>().apply { add(h) }
        var ring = listOf(h)
        val out = arrayOfNulls<IntArray>(maxLag + 1)
        for (k in 1..maxLag) { ring = hex.nextRing(ring, seen); if (k in lags) out[k] = ring.filter { isIn[it] && it > h }.toIntArray() }
        ringsOf[h] = Array(maxLag + 1) { out[it] ?: IntArray(0) }
    }

    // The hexes within [REGION] rings of each inland hex (itself included), for the spread of its region.
    val REGION = 5
    val regionOf: Array<IntArray> = Array(H) { IntArray(0) }
    java.util.stream.IntStream.range(0, inland.size).parallel().forEach { idx ->
        val h = inland[idx]
        val seen = HashSet<Int>().apply { add(h) }
        var ring = listOf(h); val all = arrayListOf(h)
        for (k in 1..REGION) { ring = hex.nextRing(ring, seen); all += ring.filter { full[it] } }
        regionOf[h] = all.toIntArray()
    }

    fun line(name: String, v: DoubleArray, fine: ((Int) -> Double)? = null) {
        val xs = inland.map { v[it] }
        val mean = xs.average()
        val variance = xs.sumOf { (it - mean) * (it - mean) } / xs.size
        if (variance <= 0.0) { println("%-22s constant".format(name)); return }
        val n = inland.size.toDouble()
        val sd = sqrt(variance)
        val gam = lags.map { k ->
            var s = 0.0; var c = 0L
            for (h in inland) for (o in ringsOf[h][k]) { val d = v[h] - v[o]; s += d * d; c++ }
            if (c == 0L) Double.NaN else 0.5 * s / c / variance
        }
        // By axis, at lag 1.
        val axis = DoubleArray(3); val axisN = LongArray(3)
        for (h in inland) for (d in 0 until 3) { val o = hex.neighbour(h, d); if (o >= 0 && isIn[o]) { val x = v[h] - v[o]; axis[d] += x * x; axisN[d]++ } }
        for (d in 0 until 3) axis[d] = 0.5 * axis[d] / axisN[d] / variance
        var anom = 0.0; var peaks = 0; var pits = 0; var peakBy = 0.0; var pitBy = 0.0
        var best = 0.0; var typical = 0.0; var worst = 0.0; var regions = 0
        var absDiff = 0.0; var regionVar = 0.0; var anomRel = 0.0; var peakRel = 0.0; var relN = 0
        for (h in inland) {
            val rg = regionOf[h]; var rm = 0.0; for (o in rg) rm += v[o]; rm /= rg.size
            var rv = 0.0; for (o in rg) rv += (v[o] - rm) * (v[o] - rm); rv /= rg.size
            regionVar += rv
            if (h % 7 == 0 && rg.size >= 60) {
                val sorted = rg.map { v[it] }.sorted()
                best += sorted.last()
                typical += sorted[sorted.size / 2]
                worst += sorted.first()
                regions++
            }
            var s = 0.0; var hi = true; var lo = true
            for (d in 0 until 6) { val o = hex.neighbour(h, d); s += v[o]; if (v[o] >= v[h]) hi = false; if (v[o] <= v[h]) lo = false }
            val r = v[h] - s / 6
            anom += r * r
            for (d in 0 until 6) absDiff += abs(v[h] - v[hex.neighbour(h, d)]) / 6
            if (rv > 0) { anomRel += r * r / rv; relN++; if (hi) peakRel += r / sqrt(rv) }
            if (hi) { peaks++; peakBy += r }
            if (lo) { pits++; pitBy -= r }
        }
        val within = fine?.let { f ->
            var w = 0.0; var c = 0L
            for (h in inland) {
                val cs = cellsOf[h]
                if (cs.size < 2) continue
                val m = cs.sumOf { f(it) } / cs.size
                for (i in cs) { val d = f(i) - m; w += d * d }
                c += cs.size
            }
            sqrt(w / c) / sd
        }
        println("%-22s mean %9.3f sd %8.3f | neighbours differ by %8.3f (%3.0f%% of mean) | regional sd %8.3f, anomaly %.2f of it, peaks +%.2f of it | in 50 km: best %.3f, median %.3f, worst %.3f".format(
            name, mean, sd, absDiff / n, 100 * absDiff / n / abs(mean), sqrt(regionVar / n),
            sqrt(anomRel / maxOf(relN, 1)), if (peaks > 0) peakRel / peaks else 0.0,
            best / regions, typical / regions, worst / regions))
        println("%-22s lag %s | E-W %.2f SE %.2f SW %.2f | anomaly %.2f sd | peaks %4.1f%% (+%.2f sd) pits %4.1f%% (-%.2f sd)%s".format(
            "", gam.joinToString(" ") { "%.2f".format(it) }, axis[0], axis[1], axis[2], sqrt(anom / n) / sd,
            100 * peaks / n, if (peaks > 0) peakBy / peaks / sd else 0.0, 100 * pits / n, if (pits > 0) pitBy / pits / sd else 0.0,
            within?.let { " | within-hex sd %.2f of between".format(it) } ?: ""))
    }
    println("best, median and worst: the hexes within $REGION rings, averaged over every 7th inland hex")
    println("first line: mean, map sd, mean difference to each of the 6 neighbours, the sd within $REGION rings (${REGION * hex.hexKm} km) averaged over hexes, the local anomaly and peak margin against that regional sd")
    println("columns: semivariance / map variance at lags ${lags.joinToString(" ")} hexes (${lags.joinToString(" ") { "%.0f".format(it * hex.hexKm) }} km); lag-1 by axis; local anomaly (hex less its 6 neighbours' mean) in map sd; share of hexes above / below all 6 neighbours (random would be 14.3%%) and their mean margin")

    fun hexMean(f: (Int) -> Double) = DoubleArray(H) { h -> val cs = cellsOf[h]; if (cs.isEmpty()) 0.0 else cs.sumOf { f(it) } / cs.size }
    val clim = world.climate
    println("\n-- the land and the climate (from the 1 km cells) --")
    line("elevation m", hexMean { world.elevation[it].toDouble() }) { world.elevation[it].toDouble() }
    line("slope", hexMean { world.terrain.slope[it].toDouble() }) { world.terrain.slope[it].toDouble() }
    line("ruggedness", hexMean { world.terrain.ruggedness[it].toDouble() }) { world.terrain.ruggedness[it].toDouble() }
    line("July temp °C", hexMean { clim.monthlyTemp[6][it].toDouble() }) { clim.monthlyTemp[6][it].toDouble() }
    line("January temp °C", hexMean { clim.monthlyTemp[0][it].toDouble() }) { clim.monthlyTemp[0][it].toDouble() }
    line("rain mm/yr", hexMean { clim.annualPrecip[it].toDouble() }) { clim.annualPrecip[it].toDouble() }
    line("aridity P/PET", hexMean { clim.aridity(it).toDouble() }) { clim.aridity(it).toDouble() }
    line("ln aridity",
        hexMean { kotlin.math.ln(clim.aridity(it).toDouble().coerceAtLeast(1e-3)) }) { kotlin.math.ln(clim.aridity(it).toDouble().coerceAtLeast(1e-3)) }
    line("soil fertility", hexMean { world.soilFertility[it].toDouble() }) { world.soilFertility[it].toDouble() }
    line("rock fertility", hexMean { world.geology.rock[it].fertility.toDouble() }) { world.geology.rock[it].fertility.toDouble() }
    line("floodplain share", hexMean { if (world.floodplain.isFloodplain[it]) 1.0 else 0.0 })
    line("watered (2 km) share", DoubleArray(H) { hw.watered[it].toDouble() })
    line("stream km", DoubleArray(H) { hw.riverKm[it].toDouble() })
    line("forest share", DoubleArray(H) { h -> Biome.entries.filter { it.name.contains("FOREST") }.sumOf { hw.biome[it.ordinal][h].toDouble() } })
    line("stone (flint) share",
        hexMean { if (world.geology.minerals[it] and (inflation.world.Mineral.FLINT or inflation.world.Mineral.OBSIDIAN) != 0) 1.0 else 0.0 })

    // Place values, as area-weighted means over each hex's places.
    fun placeMean(f: (Int) -> Double): DoubleArray {
        val s = DoubleArray(H); val w = DoubleArray(H)
        for (p in 0 until land.n) { val h = hw.placeHex[p]; s[h] += f(p) * land.areaKm2[p]; w[h] += land.areaKm2[p] }
        return DoubleArray(H) { if (w[it] > 0) s[it] / w[it] else 0.0 }
    }
    println("\n-- the places as they stand (${if (named != null) "settled state, ${sim.weather.date}" else "fresh start"}) --")
    line("herbs kg/m²", placeMean { land.plants.herb[it].toDouble() })
    line("shrubs kg/m²", placeMean { land.plants.shrub[it].toDouble() })
    line("trees kg/m²", placeMean { land.plants.tree[it].toDouble() })
    line("soil water mm", placeMean { land.hydro.soil[it].toDouble() })
    line("drink near share", placeMean { land.drink.nearRiver[it].toDouble().coerceAtLeast(land.drink.nearLake[it].toDouble())
        .coerceAtLeast(land.drink.nearSpring[it].toDouble()) })
    line("palatable share", placeMean { land.palatable[it].toDouble() })
    val ks = fauna.kinds.indices
    line("resident kg/km²", placeMean { p -> ks.sumOf { k -> (fauna.adults[k][p] + fauna.young[k][p]).toDouble() * fauna.kinds[k].massKg } })
    val byHeads = ks.sortedByDescending { k -> (0 until land.n).sumOf { p -> fauna.adults[k][p].toDouble() * land.areaKm2[p] } }
    for (k in byHeads.take(8)) line("${fauna.kinds[k].name}/km²", placeMean { p -> fauna.adults[k][p].toDouble() })
    // Groups (herds, packs, bands) standing in each hex, by body mass.
    val groupKg = DoubleArray(H)
    for (g in sim.groups.groups) { val p = g.place; if (p >= 0) groupKg[hw.placeHex[p]] += (g.adults + g.young).toDouble() * g.species.massKg }
    line("group kg in hex", groupKg)
    // A check that streams are not counted by the grid's directions: river cells per hex along straight and diagonal runs.
    var straight = 0L; var diagonal = 0L
    for (i in 0 until grid.size) {
        if (world.riverClass(i) < RiverClass.STREAM) continue
        val j = world.water.drainage.receiver[i]; if (j < 0 || j == i) continue
        if (grid.x(i) == grid.x(j) || grid.y(i) == grid.y(j)) straight++ else diagonal++
    }
    println("\nstream cells flowing straight %d, diagonal %d (stream km counts each cell as %.1f km: a diagonal step is %.2f km)"
        .format(straight, diagonal, grid.cellKm, grid.cellKm * sqrt(2.0)))
}
