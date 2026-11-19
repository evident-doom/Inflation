package inflation.tools

import inflation.ecology.cropBrowse
import inflation.ecology.cropGrass
import inflation.ecology.grassUnderSnow
import inflation.ecology.gutFill
import inflation.ecology.leanDiet
import inflation.ecology.selected
import inflation.tools.fauna.FaunaCheckOptions
import inflation.world.Place
import inflation.world.get

/**
 * Why the plant-eaters get the grass they do, over the whole map by quarter of the last year, heads-weighted where each
 * kind stands: the edible herbs standing (g/m²), the share of its want of grass and of browse it can crop there
 * ([inflation.ecology.cropGrass], [inflation.ecology.cropBrowse]), the grass it can get at under the snow, the forage's
 * nourishment as it picks it over, how much of it its gut takes ([inflation.ecology.gutFill]), and its diet's grass share
 * against the share it leans to for protein ([inflation.ecology.leanDiet]).
 *
 * Usage: grass-reach (--world NAME | --seed N [--width KM] [--height KM]) [--years N]  (the last year is reported)
 */
fun main(args: Array<String>) {
    val years = args.indexOf("--years").takeIf { it >= 0 }?.let { args[it + 1].toInt() } ?: 1
    val rest = args.toMutableList().also { i -> val j = i.indexOf("--years"); if (j >= 0) { i.removeAt(j); i.removeAt(j) } }
    val sim = FaunaCheckOptions.parse((rest + listOf("--years", "1")).toTypedArray()).start()
    val land = sim.land; val fauna = sim.fauna; val n = land.n
    val eaters = fauna.kinds.indices.filter { !fauna.kinds[it].carnivore && fauna.kinds[it].diet.grass > 0f }
    // [kind][quarter]: heads, edible herbs, crop grass, crop browse, under snow, quality, gut fill, leaned grass share.
    val acc = Array(fauna.kinds.size) { Array(4) { DoubleArray(8) } }
    val perYear = sim.stepsPerYear
    for (s in 0 until years * perYear) {
        sim.step()
        if (s < (years - 1) * perYear) continue
        val qtr = sim.weather.date.month / 3
        for (p in 0 until n) {
            val pl = Place(p); val a = land.areaKm2[p].toDouble()
            val herbs = land.palatable[p] * land.plants.herb[p]; val q = land.plants.nourishment(pl); val snow = sim.weather.snowpack[pl]
            for (k in eaters) {
                val h = (fauna.adults[k][p] + fauna.young[k][p]).toDouble() * a; if (!(h > 0.0)) continue
                val sp = fauna.kinds[k]; val v = acc[k][qtr]; val d = sp.diet; val ld = fauna.leanDiet(sp, pl)
                val gb = (d.grass + d.browse).coerceAtLeast(1e-6f)
                v[0] += h; v[1] += h * herbs * 1000; v[2] += h * fauna.cropGrass(sp, pl); v[3] += h * fauna.cropBrowse(sp, pl)
                v[4] += h * grassUnderSnow(sp, snow); v[5] += h * sp.selected(q); v[6] += h * gutFill(sp, sp.selected(q)); v[7] += h * ld.grass / gb
            }
        }
    }
    println("grass-reach: last of $years years, whole map, by quarter (Jan-Mar, Apr-Jun, Jul-Sep, Oct-Dec), heads-weighted where each kind stands")
    println("  edible herbs g/m² | crop grass | crop browse | grass under snow | quality picked | gut fill | grass share leaned (diet's)")
    for (k in eaters.filter { k -> acc[k].any { it[0] > 0.0 } }) {
        val sp = fauna.kinds[k]
        println(sp.name + " (diet grass share %.2f, crop half %.0f g/m²):".format(sp.diet.grass / (sp.diet.grass + sp.diet.browse), 1000 * sp.cropHalfKgM2))
        for (q in 0 until 4) { val v = acc[k][q]; val h = v[0]; if (h <= 0.0) continue
            println("  Q${q + 1}  %.1f | %.2f | %.2f | %.2f | %.2f | %.2f | %.2f".format(v[1] / h, v[2] / h, v[3] / h, v[4] / h, v[5] / h, v[6] / h, v[7] / h)) }
    }
}
