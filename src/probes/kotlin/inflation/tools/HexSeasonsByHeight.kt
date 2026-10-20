package inflation.tools

import inflation.ecology.Hex
import inflation.run.freshSim
import inflation.Args
import inflation.ecology.Catalogue
import inflation.world.Date
import inflation.world.HexHydrology
import inflation.world.HexPlants
import inflation.world.WorldConfig
import inflation.world.HexWorld
import kotlin.math.exp
import kotlin.math.ln

/**
 * The seasons by height: for land in bands of height, month by month over the last year of a run,
 * the means of what makes plants grow and green there: temperature, rain, snow lying and melting,
 * the soil's water against what it holds, the warmth and water parts of growth, the share of grass
 * cured to straw, how green, and how much grass stands.
 *
 * Usage: hex-seasons-by-height [--seed N] [--width KM] [--height KM] [--years N]
 */
fun main(args: Array<String>) {
    val a = Args(args, "hex-seasons-by-height [--seed N] [--width KM] [--height KM] [--years N]")
    var cfg = WorldConfig()
    cfg = cfg.copy(seed = a.long("--seed", cfg.seed), widthKm = a.int("--width", cfg.widthKm), heightKm = a.int("--height", cfg.heightKm))
    val years = a.int("--years", 3)
    a.done()
    val run = freshSim(cfg, Catalogue.learned)
    val land = run.land; val hw = run.hw; val n = land.n
    val bands = listOf(0.0 to 300.0, 300.0 to 700.0, 700.0 to 1200.0, 1200.0 to 1800.0, 1800.0 to 2500.0, 2500.0 to 9000.0)
    val bandOf = IntArray(n) { p -> bands.indexOfFirst { (lo, hi) -> hw.placeElevationM[p] >= lo && hw.placeElevationM[p] < hi } }
    val pet = land.pet
    val watchKinds = run.fauna.kinds.filter { it.name in setOf("red deer", "ibex", "mouflon", "aurochs", "fallow deer", "wild boar") }
    val names = watchKinds.map { it.name + " per km²" } + listOf("evaporative demand mm/week", "soil holds mm", "temperature °C", "rain mm/week",
        "snow lying mm", "melt mm/week", "soil water / held", "warmth part", "water part", "share cured", "green", "grass kg/m²",
        "growth (warmth × water)")
    // [band][quantity][month]: sums weighted by area, and the area.
    val sum = Array(bands.size) { Array(names.size) { DoubleArray(12) } }
    val area = Array(bands.size) { DoubleArray(12) }
    val ratioSum = Array(run.fauna.kinds.size) { DoubleArray(12) }; val ratioN = Array(run.fauna.kinds.size) { IntArray(12) }
    val steps = years * Date.DAYS_PER_YEAR / cfg.daysPerStep; val perYear = Date.DAYS_PER_YEAR / cfg.daysPerStep
    repeat(steps) { w ->
        run.step()
        if (w < steps - perYear) return@repeat
        val m = run.weather.date.month
        val cured = land.plants.cured
        for (kk in run.fauna.kinds.indices) for (h in 0 until run.hex.size) {
            val lo = hw.placeIn(HexWorld.LOW, Hex(h)); val hi = hw.placeIn(HexWorld.HIGH, Hex(h)); if (lo.none || hi == lo) continue
            val al = run.fauna.worth[kk][lo.i]; val ah = run.fauna.worth[kk][hi.i]
            if (al > 0f && ah > 0f) { ratioSum[kk][m] += ln((ah / al).toDouble()); ratioN[kk][m]++ }
        }
        for (p in 0 until n) {
            val b = bandOf[p]; if (b < 0) continue
            val a = land.areaKm2[p].toDouble()
            val t = run.weather.temperature[p]
            val wet = land.hydro.soil[p] / land.hydro.soilCap[p]
            val season = ((t - land.tuning.growFromC) / land.tuning.growSpanC).coerceIn(0f, 1f) * if (t > 38f) 0.3f else 1f
            val water0 = (wet / HexHydrology.UNSTRESSED).coerceIn(0f, 1f)
            val v = doubleArrayOf(*watchKinds.map { s ->
                val kk = run.fauna.slot(s); (run.fauna.adults[kk.i][p] + run.fauna.young[kk.i][p]).toDouble() }.toDoubleArray(),
                (pet[m][p] * cfg.daysPerStep / Date.DAYS_PER_MONTH).toDouble(), land.hydro.soilCap[p].toDouble(), t.toDouble(),
                run.weather.precipitation[p].toDouble(), run.weather.snowpack[p].toDouble(), run.weather.meltwater[p].toDouble(),
                wet.toDouble(), season.toDouble(), water0.toDouble(), if (cured[p]) 1.0 else 0.0,
                land.plants.green[p].toDouble(), land.plants.herb[p].toDouble(),
                (season * water0).toDouble())
            for (q in names.indices) sum[b][q][m] += v[q] * a
            area[b][m] += a
        }
    }
    // How much better the higher of two neighbouring height bands in a hex looks to red deer and mouflon, month by month (geometric mean of high/low).
    for (name in listOf("red deer", "mouflon", "ibex")) {
        val kk = run.fauna.kinds.indexOfFirst { it.name == name }; if (kk < 0) continue
        println("$name: higher band's appeal against the lower's, by month: " +
            (0 until 12).joinToString(" ") { m -> if (ratioN[kk][m] > 0) "%.2f".format(exp(ratioSum[kk][m] / ratioN[kk][m])) else "-" })
    }
    for ((b, band) in bands.withIndex()) {
        if (area[b].all { it <= 0.0 }) continue
        println("height %.0f-%.0f m (%.0f km²)".format(band.first, band.second, (0 until n).filter { bandOf[it] == b }.sumOf { land.areaKm2[it].toDouble() }))
        for ((q, name) in names.withIndex()) println("  %-24s %s".format(name,
            (0 until 12).joinToString(" ") { m -> if (area[b][m] > 0) "%6.2f".format(sum[b][q][m] / area[b][m]) else "     -" }))
    }
}

