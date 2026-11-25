package inflation.tools

import inflation.Args
import inflation.world.Date
import inflation.world.HexMap
import inflation.world.HexWorld
import inflation.world.Weather
import inflation.world.World
import inflation.world.WorldConfig
import kotlin.math.sqrt

/**
 * Whether the weather's rain keeps to the climate's normals: runs the places' weather alone for some years and sets the
 * rain it drew beside the normal it draws around ([Weather.Places.monthlyPrecip]), place by place, grouped by the normal's
 * size; also the spread of the yearly totals of all the places in a group (the group's own year-to-year swing) and the
 * drawn against the normal by month (summed over the land). 2026-11-25: steppe drew 255 mm a year over 3 years against a
 * normal of 316.
 *
 * Usage: rain-normals [--seed N] [--width KM] [--height KM] [--years N]
 */
fun main(args: Array<String>) {
    val a = Args(args, "rain-normals [--seed N] [--width KM] [--height KM] [--years N]")
    var cfg = WorldConfig(seed = 1)
    cfg = cfg.copy(seed = a.long("--seed", cfg.seed), widthKm = a.int("--width", cfg.widthKm), heightKm = a.int("--height", cfg.heightKm))
    val years = a.int("--years", 30)
    a.done()
    val world = World.generate(cfg)
    val hw = HexWorld(world, HexMap(world))
    val places = hw.weatherPlaces(cfg)
    val n = places.size
    val weather = Weather(places, cfg)
    val normal = DoubleArray(n) { p -> (0 until Date.MONTHS_PER_YEAR).sumOf { places.monthlyPrecip[it][p].toDouble() } }
    val size = DoubleArray(n) { p -> hw.placeCells[p].size.toDouble() }
    val bands = listOf(0.0, 100.0, 150.0, 200.0, 250.0, 300.0, 400.0, 600.0, 900.0, 1e9)
    fun band(p: Int) = bands.indexOfLast { normal[p] >= it }.coerceAtMost(bands.size - 2)
    val groups = bands.size - 1
    val yearSum = Array(years) { DoubleArray(groups) }
    val drawnMonth = DoubleArray(12); val normalMonth = DoubleArray(12)
    val stepsPerYear = Date.DAYS_PER_YEAR / cfg.daysPerStep
    for (y in 0 until years) for (s in 0 until stepsPerYear) {
        weather.step()
        val m = weather.lastMonth
        for (p in 0 until n) {
            val r = weather.precipitation[p] * size[p]
            yearSum[y][band(p)] += r; drawnMonth[m] += r
            normalMonth[m] += places.monthlyPrecip[m][p] * cfg.daysPerStep / Date.DAYS_PER_MONTH.toDouble() * size[p]
        }
    }
    val groupSize = DoubleArray(groups); val groupNormal = DoubleArray(groups)
    for (p in 0 until n) { groupSize[band(p)] += size[p]; groupNormal[band(p)] += normal[p] * size[p] }
    println("%d places, %d years; rain drawn against the normal, by the normal's size (mm a year, land-weighted):".format(n, years))
    for (g in 0 until groups) {
        if (groupSize[g] == 0.0) continue
        val totals = DoubleArray(years) { yearSum[it][g] / groupSize[g] }
        val mean = totals.average(); val sd = sqrt(totals.sumOf { (it - mean) * (it - mean) } / (years - 1).coerceAtLeast(1))
        val nm = groupNormal[g] / groupSize[g]
        println("  normal %4.0f-%-4s %5.1f%% of land: normal %5.0f drawn %5.0f ratio %.2f (years: sd %.2f of the mean, lowest %.2f, highest %.2f)".format(
            bands[g], if (bands[g + 1] > 1e8) "" else "%.0f".format(bands[g + 1]), 100 * groupSize[g] / size.sum(), nm, mean, mean / nm,
            sd / mean, totals.min() / nm, totals.max() / nm))
    }
    println("by month, all land: drawn / normal")
    println("  " + (0 until 12).joinToString(" ") { "%.2f".format(drawnMonth[it] / normalMonth[it].coerceAtLeast(1e-9)) })
}
