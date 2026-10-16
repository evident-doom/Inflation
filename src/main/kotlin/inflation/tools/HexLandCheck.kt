package inflation.tools

import inflation.Args
import inflation.world.Date
import inflation.world.HexLand
import inflation.world.HexMap
import inflation.world.HexWorld
import inflation.world.LiveWater
import inflation.world.RiverClass
import inflation.world.Vegetation
import inflation.world.Weather
import inflation.world.World
import inflation.world.WorldConfig


import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Runs weather, water and plants on every fine cell and on the hexes' height bands side by side
 * from the same seed (no animals), and compares them at the end, place by place: herbs, shrubs and
 * trees, soil moisture, the flow of each hex's main river, and the area burned. Also times a step
 * of each.
 *
 * Usage: hex-land-check [--seed N] [--width KM] [--height KM] [--years N]
 */
fun main(args: Array<String>) {
    val a = Args(args, "hex-land-check [--seed N] [--width KM] [--height KM] [--years N]")
    val base = WorldConfig(seed = 1)
    val cfg = base.copy(seed = a.long("--seed", base.seed), widthKm = a.int("--width", base.widthKm), heightKm = a.int("--height", base.heightKm))
    val years = a.int("--years", 5)
    a.done()
    val world = World.generate(cfg)
    val hw = HexWorld(world, HexMap(world))
    val n = hw.placeCells.size
    val fineWeather = Weather(world.grid, world.climate, cfg, world.elevation)
    val fineWater = LiveWater(world)
    val fineVeg = Vegetation(world, cfg.seed)
    val ash = FloatArray(world.grid.size)
    val hexWeather = Weather(hw.weatherPlaces(cfg), cfg)
    val land = HexLand(hw, cfg.seed)
    val steps = years * Date.DAYS_PER_YEAR / cfg.daysPerStep
    var nsF = 0L; var nsC = 0L
    var burnedF = 0.0; var burnedC = 0.0
    val main = IntArray(hw.hex.size) { -1 }
    for (i in 0 until world.grid.size) { if (world.isSea[i]) continue; val h = hw.hex.owner[i]; if (main[h] < 0 || world.water.discharge[i] > world.water.discharge[main[h]]) main[h] = i }
    val hexes = (0 until hw.hex.size).filter { land.meanFlow[it] >= RiverClass.STREAM.minDischarge }
    val flowF = DoubleArray(hexes.size); val flowC = DoubleArray(hexes.size)
    repeat(steps) {
        var t0 = System.nanoTime()
        fineWeather.step(); fineWater.step(fineWeather); fineVeg.step(fineWeather, fineWater, ash)
        nsF += System.nanoTime() - t0
        t0 = System.nanoTime()
        hexWeather.step(); land.step(hexWeather)
        nsC += System.nanoTime() - t0
        burnedF += fineVeg.fires.sumOf { it.second.toDouble() }; burnedC += land.fires.sumOf { it.second.toDouble() }
        for ((j, h) in hexes.withIndex()) { flowF[j] += fineWater.discharge[main[h]] / steps.toDouble(); flowC[j] += land.flow[h] / steps.toDouble() }
    }
    fun agg(f: (Int) -> Float) = DoubleArray(n) { p -> val cs = hw.placeCells[p]; cs.sumOf { f(it).toDouble() } / cs.size }
    fun corr(a: DoubleArray, b: DoubleArray): Double {
        val ma = a.average(); val mb = b.average()
        var sab = 0.0; var saa = 0.0; var sbb = 0.0
        for (i in a.indices) { sab += (a[i] - ma) * (b[i] - mb); saa += (a[i] - ma) * (a[i] - ma); sbb += (b[i] - mb) * (b[i] - mb) }
        return sab / sqrt(saa * sbb)
    }
    fun compare(name: String, a: DoubleArray, b: DoubleArray) =
        println("%-16s correlation %.3f, mean fine %.3f, bands %.3f, mean difference %.3f".format(name, corr(a, b), a.average(), b.average(), (a.indices).map { abs(a[it] - b[it]) }.average()))
    compare("herbs kg/m²", agg { fineVeg.herb[it] }, DoubleArray(n) { land.herb[it].toDouble() })
    compare("shrubs kg/m²", agg { fineVeg.shrub[it] }, DoubleArray(n) { land.shrub[it].toDouble() })
    compare("trees kg/m²", agg { fineVeg.tree[it] }, DoubleArray(n) { land.tree[it].toDouble() })
    compare("soil water mm", agg { fineWater.soil[it] }, DoubleArray(n) { land.soil[it].toDouble() })
    // Rivers: each hex's main river, where it is a stream or more, averaged over the run.
    compare("river flow m³/s", flowF, flowC)
    println("long-term flow of the same rivers from the climate (what fords and river classes are set by): mean %.3f m³/s".format(hexes.map { land.meanFlow[it].toDouble() }.average()))
    println("burned per year: fine %.0f km², bands %.0f km²".format(burnedF / years, burnedC / years))
    println("a step of weather, water and plants: %.0f ms on %d fine cells, %.1f ms on %d places".format(nsF / 1e6 / steps, world.grid.size, nsC / 1e6 / steps, n))
}
