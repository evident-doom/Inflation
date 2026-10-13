package inflation.world

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Runs the weather on every fine cell and on the height bands of the hexes side by side, from the
 * same seed, and compares them place by place: mean temperature, rain and snow over the years,
 * and how many weeks snow lies. The fine weather is averaged over each band's cells. Also times a
 * step of each.
 *
 * Usage: hex-weather-check [--seed N] [--width KM] [--height KM] [--years N]
 */
fun main(args: Array<String>) {
    var cfg = WorldConfig(seed = 1)
    var years = 2
    var k = 0
    while (k < args.size) {
        when (args[k]) {
            "--seed" -> cfg = cfg.copy(seed = args[++k].toLong())
            "--width" -> cfg = cfg.copy(widthKm = args[++k].toInt())
            "--height" -> cfg = cfg.copy(heightKm = args[++k].toInt())
            "--years" -> years = args[++k].toInt()
            else -> error("unknown argument ${args[k]}")
        }
        k++
    }
    val world = World.generate(cfg)
    val hw = HexWorld(world, HexMap(world))
    val n = hw.placeCells.size
    println("%d places (height bands of %d hexes)".format(n, hw.placeHex.distinct().size))
    val fine = Weather(world.grid, world.climate, cfg, world.elevation)
    val coarse = Weather(hw.weatherPlaces(cfg), cfg)
    val steps = years * Date.DAYS_PER_YEAR / cfg.daysPerStep
    val tF = DoubleArray(n); val tC = DoubleArray(n)
    val pF = DoubleArray(n); val pC = DoubleArray(n)
    val sF = DoubleArray(n); val sC = DoubleArray(n)
    var nsF = 0L; var nsC = 0L
    repeat(steps) {
        var t0 = System.nanoTime(); fine.step(); nsF += System.nanoTime() - t0
        t0 = System.nanoTime(); coarse.step(); nsC += System.nanoTime() - t0
        for (p in 0 until n) {
            val cs = hw.placeCells[p]
            var t = 0.0; var r = 0.0; var snowy = 0.0
            for (i in cs) { t += fine.temperature[i]; r += fine.precipitation[i]; if (fine.snowpack[i] > 10f) snowy += 1.0 }
            tF[p] += t / cs.size; pF[p] += r / cs.size; sF[p] += snowy / cs.size
            tC[p] += coarse.temperature[p]; pC[p] += coarse.precipitation[p]; if (coarse.snowpack[p] > 10f) sC[p] += 1.0
        }
    }
    fun corr(a: DoubleArray, b: DoubleArray): Double {
        val ma = a.average(); val mb = b.average()
        var sab = 0.0; var saa = 0.0; var sbb = 0.0
        for (i in a.indices) { sab += (a[i] - ma) * (b[i] - mb); saa += (a[i] - ma) * (a[i] - ma); sbb += (b[i] - mb) * (b[i] - mb) }
        return sab / sqrt(saa * sbb)
    }
    val yrs = years.toDouble()
    println("mean temperature: correlation %.4f, mean difference %.2f °C".format(corr(tF, tC), (0 until n).map { abs(tF[it] - tC[it]) / steps }.average()))
    println("rain and snow per year: correlation %.4f, mean difference %.0f mm (of %.0f mm)".format(corr(pF, pC), (0 until n).map { abs(pF[it] - pC[it]) / yrs }.average(), pF.average() / yrs))
    println("weeks with snow lying per year: correlation %.4f, mean difference %.1f weeks (fine %.1f, bands %.1f)".format(
        corr(sF, sC), (0 until n).map { abs(sF[it] - sC[it]) / yrs }.average(), sF.average() / yrs, sC.average() / yrs))
    println("a weather step: %.1f ms on %d fine cells, %.2f ms on %d places".format(nsF / 1e6 / steps, world.grid.size, nsC / 1e6 / steps, n))
}
