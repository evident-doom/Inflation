package inflation.tools

import inflation.ecology.Catalogue
import inflation.learning.HexLearning
import inflation.world.Date
import inflation.world.HexLand
import inflation.world.WorldConfig

/**
 * The seasons by height: for land in bands of height, month by month over the last year of a run,
 * the means of what makes plants grow and green there: temperature, rain, snow lying and melting,
 * the soil's water against what it holds, the warmth and water parts of growth, the share of grass
 * cured to straw, how green, and how much grass stands.
 *
 * Usage: hex-seasons-by-height [--seed N] [--width KM] [--height KM] [--years N]
 */
fun main(args: Array<String>) {
    var cfg = WorldConfig(); var years = 3
    var k = 0
    while (k < args.size) {
        when (args[k]) {
            "--seed" -> cfg = cfg.copy(seed = args[++k].toLong())
            "--width" -> cfg = cfg.copy(widthKm = args[++k].toInt())
            "--height" -> cfg = cfg.copy(heightKm = args[++k].toInt())
            "--years" -> years = args[++k].toInt()
        }
        k++
    }
    val run = HexLearning.Run(cfg, Catalogue.learned)
    val land = run.land; val hw = run.hw; val n = land.n
    val bands = listOf(0.0 to 300.0, 300.0 to 700.0, 700.0 to 1200.0, 1200.0 to 1800.0, 1800.0 to 2500.0, 2500.0 to 9000.0)
    val bandOf = IntArray(n) { p -> bands.indexOfFirst { (lo, hi) -> hw.placeElevationM[p] >= lo && hw.placeElevationM[p] < hi } }
    @Suppress("UNCHECKED_CAST") val pet = HexLand::class.java.getDeclaredField("pet").also { it.isAccessible = true }.get(land) as Array<FloatArray>
    val names = listOf("evaporative demand mm/week", "soil holds mm", "temperature °C", "rain mm/week", "snow lying mm", "melt mm/week", "soil water / held", "warmth part", "water part", "share cured", "green", "grass kg/m²", "growth (warmth × water)")
    // [band][quantity][month]: sums weighted by area, and the area.
    val sum = Array(bands.size) { Array(names.size) { DoubleArray(12) } }
    val area = Array(bands.size) { DoubleArray(12) }
    val steps = years * Date.DAYS_PER_YEAR / cfg.daysPerStep; val perYear = Date.DAYS_PER_YEAR / cfg.daysPerStep
    val curedField = HexLand::class.java.getDeclaredField("cured").also { it.isAccessible = true }
    repeat(steps) { w ->
        run.step()
        if (w < steps - perYear) return@repeat
        val m = run.weather.date.month
        val cured = curedField.get(land) as BooleanArray
        for (p in 0 until n) {
            val b = bandOf[p]; if (b < 0) continue
            val a = land.areaKm2[p].toDouble()
            val t = run.weather.temperature[p]
            val wet = land.soil[p] / land.soilCap[p]
            val season = ((t - HexLand.GROW_FROM_C) / HexLand.GROW_SPAN_C).coerceIn(0f, 1f) * if (t > 38f) 0.3f else 1f
            val water0 = (wet / HexLand.UNSTRESSED).coerceIn(0f, 1f)
            val v = doubleArrayOf((pet[m][p] * cfg.daysPerStep / Date.DAYS_PER_MONTH).toDouble(), land.soilCap[p].toDouble(), t.toDouble(), run.weather.precipitation[p].toDouble(), run.weather.snowpack[p].toDouble(), run.weather.meltwater[p].toDouble(),
                wet.toDouble(), season.toDouble(), water0.toDouble(), if (cured[p]) 1.0 else 0.0, land.green[p].toDouble(), land.herb[p].toDouble(), (season * water0).toDouble())
            for (q in names.indices) sum[b][q][m] += v[q] * a
            area[b][m] += a
        }
    }
    for ((b, band) in bands.withIndex()) {
        if (area[b].all { it <= 0.0 }) continue
        println("height %.0f-%.0f m (%.0f km²)".format(band.first, band.second, (0 until n).filter { bandOf[it] == b }.sumOf { land.areaKm2[it].toDouble() }))
        for ((q, name) in names.withIndex()) println("  %-24s %s".format(name, (0 until 12).joinToString(" ") { m -> if (area[b][m] > 0) "%6.2f".format(sum[b][q][m] / area[b][m]) else "     -" }))
    }
}

