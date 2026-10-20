package inflation.tools

import inflation.world.HexHydrology
import inflation.world.HexLand
import inflation.world.HexMap
import inflation.world.HexWorld
import inflation.world.RiverClass
import inflation.world.Weather
import inflation.world.World
import inflation.world.WorldConfig
import inflation.world.LandformCache

/**
 * Runs weather, water and plants on the hexes' places (no animals) and reports, year by year,
 * how much of dry country has water within reach in its driest week, how the springs hold up, how
 * the ground water stands, and whether rivers run dry.
 *
 * Usage: hex-water-check [--seed N] [--width KM] [--height KM] [--years N]
 */
fun main(args: Array<String>) {
    val a = inflation.Args(args, "hex-water-check [--seed N] [--width KM] [--height KM] [--years N]")
    val cfg = a.world(WorldConfig(seed = 1))
    val years = a.int("--years", 8)
    a.done()
    val world = World.generate(cfg, LandformCache.load(cfg) {})
    val hw = HexWorld(world, HexMap(world))
    val n = hw.placeCells.size
    val land = HexLand(hw, cfg.seed)
    val weather = Weather(hw.weatherPlaces(cfg), cfg)
    fun mean(p: Int, f: (Int) -> Float): Float { val cs = hw.placeCells[p]; var s = 0.0; for (i in cs) s += f(i); return (s / cs.size).toFloat() }
    val arid = FloatArray(n) { p -> mean(p) { world.climate.aridity(it) } }
    val classes = listOf("hyperarid <0.2" to 0f..0.2f, "arid 0.2-0.5" to 0.2f..0.5f, "semiarid 0.5-0.65" to 0.5f..0.65f,
        "dry-subhumid 0.65-1" to 0.65f..1f, "humid >1" to 1f..1e9f)
    val cls = IntArray(n) { p -> classes.indexOfFirst { arid[p] in it.second } }
    val rivers = listOf("streams 3-30 m³/s" to RiverClass.STREAM, "rivers 30-300 m³/s" to RiverClass.RIVER,
        "great rivers 300+ m³/s" to RiverClass.GREAT_RIVER)
    val rcls = IntArray(hw.hex.size) { h -> if (hw.land[h] <= 0f) -1 else rivers.indexOfLast { land.hydro.meanFlow[h] >= it.second.minDischarge } }
    for ((c, cl) in classes.withIndex()) println("%-20s area %.0f km², near a spring %.1f%%".format(cl.first,
        (0 until n).filter { cls[it] == c }.sumOf { land.areaKm2[it].toDouble() },
        100 * (0 until n).filter { cls[it] == c }.sumOf { (land.drink.nearSpring[it] * land.areaKm2[it]).toDouble() } /
            (0 until n).filter { cls[it] == c }.sumOf { land.areaKm2[it].toDouble() }.coerceAtLeast(1.0)))
    for ((c, r) in rivers.withIndex()) println("%-22s hexes %d".format(r.first, rcls.count { it == c }))
    val stepsY = cfg.stepsPerYear
    fun stock() = (0 until n).sumOf { (land.hydro.groundwater[it] * land.areaKm2[it]).toDouble() }
    val stock0 = stock()
    var landNs = 0L
    for (y in 0 until years) {
        val minWater = FloatArray(n) { 9f }; val maxKm = FloatArray(n); val minPeople = FloatArray(n) { 9f }
        val sMin = FloatArray(hw.hex.size) { 9f }
        val rMin = FloatArray(hw.hex.size) { 9f }; val dryWeeks = IntArray(hw.hex.size)
        val rain = DoubleArray(classes.size)
        var lo = Double.MAX_VALUE; var hi = 0.0
        repeat(stepsY) {
            weather.step(); val t0 = System.nanoTime(); land.step(weather); landNs += System.nanoTime() - t0
            for (p in 0 until n) {
                if (land.drink.water[p] < minWater[p]) minWater[p] = land.drink.water[p]
                val forPeople = maxOf(land.drink.water[p], land.drink.dug[p])
                if (forPeople < minPeople[p]) minPeople[p] = forPeople
                if (land.drink.waterKm[p] > maxKm[p]) maxKm[p] = land.drink.waterKm[p]
                if (cls[p] >= 0) rain[cls[p]] += weather.precipitation[p] * land.areaKm2[p]
                if (land.drink.nearSpring[p] > 0f) { val h = hw.placeHex[p]; sMin[h] = minOf(sMin[h], land.hydro.springFlow(inflation.ecology.Place(p))) }
            }
            for (h in 0 until hw.hex.size) if (rcls[h] >= 0) {
                val r = land.hydro.flow[h] / land.hydro.meanFlow[h]
                if (r < rMin[h]) rMin[h] = r
                if (land.hydro.flow[h] < HexHydrology.TRICKLE) dryWeeks[h]++
            }
            val s = stock(); lo = minOf(lo, s); hi = maxOf(hi, s)
        }
        println("== year %d  ground water: dry-season low / wet-season high %.2f, mean against the start %.2f; water and plants %.1f ms a step"
            .format(y, lo / hi, (lo + hi) / 2 / stock0, landNs / 1e6 / stepsY))
        landNs = 0L
        if (y == years - 1) for (h in 0 until hw.hex.size) if (rcls[h] >= 1 && dryWeeks[h] > 0)
            println("    below a trickle: hex %d, usual %.0f m³/s, %d weeks, lowest %.4f of usual, %d places, land share %.2f, drains to %d".format(
                h, land.hydro.meanFlow[h], dryWeeks[h], rMin[h],
                hw.placesOf(h).size, hw.land[h], land.hydro.down[h]))
        for ((c, cl) in classes.withIndex()) {
            val ps = (0 until n).filter { cls[it] == c }; if (ps.isEmpty()) continue
            val a = ps.sumOf { land.areaKm2[it].toDouble() }
            val spP = ps.filter { land.drink.nearSpring[it] > 0f }
            println("  %-20s rain %4.0f mm; no water in driest week %.2f (for people, who dig: %.2f); worst walk %4.1f km; springs: driest week %.2f of usual, under 0.1: %.2f".format(
                cl.first, rain[c] / a,
                ps.filter { minWater[it] < 0.05f }.sumOf { land.areaKm2[it].toDouble() } / a,
                ps.filter { minPeople[it] < 0.05f }.sumOf { land.areaKm2[it].toDouble() } / a,
                ps.sumOf { (maxKm[it] * land.areaKm2[it]).toDouble() } / a,
                if (spP.isEmpty()) Double.NaN else spP.map { sMin[hw.placeHex[it]].toDouble() }.average(),
                if (spP.isEmpty()) Double.NaN else spP.count { sMin[hw.placeHex[it]] < 0.1f }.toDouble() / spP.size))
            val dry = ps.filter { minPeople[it] < 0.05f }
            if (dry.isNotEmpty()) println(
                "  %-20s   where people find none: by a stream bed %.0f%% of places (mean share %.2f); water table %.0f m down (median)".format(
                "", 100.0 * dry.count { land.drink.nearRiver[it] > 0f } / dry.size, dry.map { land.drink.nearRiver[it].toDouble() }.average(),
                dry.map { land.hydro.waterTableM(inflation.ecology.Place(it)) }.sorted()[dry.size / 2]))
        }
        for ((c, r) in rivers.withIndex()) {
            val hs = (0 until hw.hex.size).filter { rcls[it] == c }; if (hs.isEmpty()) continue
            println("  %-22s lowest week %.3f of mean flow (median hex); hexes ever under 2%%: %.3f; ever below a trickle: %.3f; weeks below a trickle %.2f%%"
                .format(
                r.first, hs.map { rMin[it] }.sorted()[hs.size / 2], hs.count { rMin[it] < 0.02f }.toDouble() / hs.size,
                hs.count { dryWeeks[it] > 0 }.toDouble() / hs.size, 100.0 * hs.sumOf { dryWeeks[it] } / (hs.size * stepsY)))
        }
    }
}
