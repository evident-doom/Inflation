package inflation.tools

import inflation.tools.fauna.FaunaCheckOptions
import inflation.world.Biome
import inflation.world.Place
import inflation.world.get

/**
 * The plant-eaters' food against what they eat, by kind of country and month over a year: the edible herbs standing
 * (kg/m²), the shoots within reach (a week's, kg/m²), fruit, and roots and fallen mast; what the residents grazed,
 * browsed and dug (g/m² a month); the share of the standing herbs grazed in the month; and the large plant-eaters'
 * weight there (kg/km²). For judging whether the land feeds them too well.
 *
 * Usage: forage-supply (--world NAME | --seed N [--width KM] [--height KM]) [--years N]  (the last year is reported)
 */
fun main(args: Array<String>) {
    val years = args.indexOf("--years").takeIf { it >= 0 }?.let { args[it + 1].toInt() } ?: 1
    val rest = args.toMutableList().also { i -> val j = i.indexOf("--years"); if (j >= 0) { i.removeAt(j); i.removeAt(j) } }
    val sim = FaunaCheckOptions.parse((rest + listOf("--years", "1")).toTypedArray()).start()
    val land = sim.land; val fauna = sim.fauna; val hw = sim.hw; val n = land.n
    val biomes = Biome.entries
    val area = DoubleArray(biomes.size)
    for (p in 0 until n) area[hw.placeBiome[p]] += land.areaKm2[p].toDouble()
    val shown = biomes.indices.filter { area[it] > 0.01 * area.sum() }.sortedByDescending { area[it] }
    val eaters = fauna.kinds.indices.filter { !fauna.kinds[it].carnivore }
    // [month][biome][measure]: herb, browse, fruit, under, grazed, browsed, dug, grazed share, weight; summed area-weighted, then averaged by steps.
    val m = 9
    val acc = Array(12) { Array(biomes.size) { DoubleArray(m) } }
    val steps = IntArray(12)
    val perYear = sim.stepsPerYear
    for (s in 0 until years * perYear) {
        sim.step()
        if (s < (years - 1) * perYear) continue
        val mo = sim.weather.date.month; steps[mo]++
        val days = sim.clock.days.toDouble()
        for (p in 0 until n) {
            val pl = Place(p); val b = hw.placeBiome[p]; val a = land.areaKm2[p].toDouble(); val v = acc[mo][b]
            val herb = (land.palatable[p] * land.plants.herb[p]).toDouble()
            v[0] += herb * a; v[1] += land.plants.browse(pl) * a; v[2] += fauna.food.fruit[p] * a; v[3] += fauna.food.underground(pl) * a
            // (kg/km² a day, to g/m² over a 30-day month.)
            val g = land.plants.grazing[p] * 30.0 / 1000.0; val br = land.plants.browsing[p] * 30.0 / 1000.0; val dg = land.plants.rooting[p] * 30.0 / 1000.0
            v[4] += g * a; v[5] += br * a; v[6] += dg * a
            v[7] += (if (herb > 0.0) land.plants.grazing[p] * days / 1e6 / herb else 0.0) * a
            var w = 0.0; for (k in eaters) w += (fauna.adults[k][p] + 0.3f * fauna.young[k][p]) * fauna.kinds[k].massKg
            v[8] += w * a
        }
    }
    val names = listOf("herbs kg/m²", "shoots kg/m²", "fruit kg/m²", "roots+mast kg/m²", "grazed g/m²/mo", "browsed g/m²/mo", "dug g/m²/mo", "herbs grazed/step", "plant-eaters kg/km²")
    for (b in shown) {
        println("${biomes[b].label} (${"%.0f".format(100 * area[b] / area.sum())}% of land)")
        println("  month " + names.joinToString(" | "))
        val year = DoubleArray(m)
        for (mo in 0 until 12) {
            val v = acc[mo][b]; val k = steps[mo].coerceAtLeast(1) * area[b]
            println("  %5d ".format(mo) + (0 until m).joinToString(" | ") { "%.4f".format(v[it] / k) })
            for (i in 0 until m) year[i] += v[i] / k / 12
        }
        println("  year  " + (0 until m).joinToString(" | ") { "%.4f".format(year[it]) } + "  (grazed+browsed+dug over the year %.0f g/m²)".format(12 * (year[4] + year[5] + year[6])))
    }
}
