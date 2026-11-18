package inflation.tools

import inflation.ecology.HexFauna
import inflation.ecology.cropGrass
import inflation.ecology.gutFill
import inflation.ecology.selected
import inflation.tools.fauna.FaunaCheckOptions
import inflation.world.Biome
import inflation.world.Place

/**
 * What limits the plant-eaters' feeding in open country, by kind of country and quarter of the last year: whether the
 * week's grass pool ([HexFauna.REACH] of what stands) runs out, or the sward and the gut do. For the land: the edible herbs
 * standing (kg/m²), the share of it eaten a week, and the share of the land (area) where the week's pool was eaten to
 * under 5%. For each plant-eater there, by heads: heads a km², fed, the share of its grass want it can crop where it
 * stands ([cropGrass]), its gut's fill on the forage as it picks it ([gutFill], [selected]), and the share of its heads
 * standing where the week's pool ran out.
 *
 * Usage: steppe-feeding (--world NAME | --seed N [--width KM] [--height KM]) [--years N]  (the last year is reported)
 */
fun main(args: Array<String>) {
    val years = args.indexOf("--years").takeIf { it >= 0 }?.let { args[it + 1].toInt() } ?: 3
    val rest = args.toMutableList().also { i -> val j = i.indexOf("--years"); if (j >= 0) { i.removeAt(j); i.removeAt(j) } }
    val sim = FaunaCheckOptions.parse((rest + listOf("--years", "1")).toTypedArray()).start()
    val land = sim.land; val fauna = sim.fauna; val hw = sim.hw; val n = land.n
    val biomes = Biome.entries
    val shown = listOf(Biome.STEPPE, Biome.SEMI_DESERT, Biome.MEDITERRANEAN_WOODLAND, Biome.OPEN_WOODLAND).map { it.ordinal }
    val eaters = fauna.kinds.indices.filter { !fauna.kinds[it].carnivore }
    // Land [biome][quarter]: area, herb*area, eaten share*area, area where the pool ran out.
    val landAcc = Array(biomes.size) { Array(4) { DoubleArray(4) } }
    // Kinds [kind][biome][quarter]: heads, heads*fed, heads*crop, heads*fill, heads where the pool ran out, area-steps.
    val kindAcc = Array(fauna.kinds.size) { Array(biomes.size) { Array(4) { DoubleArray(5) } } }
    val stepsIn = IntArray(4)
    val perYear = sim.stepsPerYear
    for (s in 0 until years * perYear) {
        sim.step()
        if (s < (years - 1) * perYear) continue
        val qtr = sim.weather.date.month / 3; stepsIn[qtr]++
        for (p in 0 until n) {
            val b = hw.placeBiome[p]
            if (b !in shown) continue
            val pl = Place(p); val a = land.areaKm2[p].toDouble()
            val herb = (land.palatable[p] * land.plants.herb[p]).toDouble()
            val pool = HexFauna.REACH * herb
            val out = pool > 0.0 && fauna.grazeLeft[p] < 0.05 * pool
            val eaten = if (herb > 0.0) (pool - fauna.grazeLeft[p]).coerceAtLeast(0.0) / herb else 0.0
            val l = landAcc[b][qtr]; l[0] += a; l[1] += herb * a; l[2] += eaten * a; if (out) l[3] += a
            val q = land.plants.nourishment(pl)
            for (k in eaters) {
                val h = (fauna.adults[k][p] + fauna.young[k][p]).toDouble() * a
                if (h <= 0.0) continue
                val sp = fauna.kinds[k]
                val v = kindAcc[k][b][qtr]
                v[0] += h; v[1] += h * fauna.fed[k][p]; v[2] += h * fauna.cropGrass(sp, pl); v[3] += h * gutFill(sp, sp.selected(q))
                if (out) v[4] += h
            }
        }
    }
    println("steppe-feeding: last of $years years")
    for (b in shown) {
        println()
        println(biomes[b].label + ": by quarter (Jan-Mar, Apr-Jun, Jul-Sep, Oct-Dec)")
        println("  land: edible herbs kg/m² | share of it eaten a step | share of land where the step's pool ran out")
        for (qt in 0 until 4) { val l = landAcc[b][qt]; if (l[0] <= 0.0) continue
            println("    Q${qt + 1}  %.4f | %.4f | %3.0f%%".format(l[1] / l[0], l[2] / l[0], 100 * l[3] / l[0])) }
        println("  kinds: heads/km² | fed | grass crop share | gut fill | heads where the pool ran out (diet grass/browse/fruit/roots)")
        for (k in eaters) {
            val tot = (0 until 4).sumOf { kindAcc[k][b][it][0] }
            if (tot <= 0.0) continue
            val d = fauna.kinds[k].diet
            println("    ${fauna.kinds[k].name} (%.2f/%.2f/%.2f/%.2f):".format(d.grass, d.browse, d.fruit, d.roots))
            for (qt in 0 until 4) { val v = kindAcc[k][b][qt]; val h = v[0]; if (h <= 0.0) continue
                val area = landAcc[b][qt][0] / stepsIn[qt].coerceAtLeast(1)
                println("      Q${qt + 1}  %.3f | %.2f | %.2f | %.2f | %3.0f%%".format(h / stepsIn[qt].coerceAtLeast(1) / area, v[1] / h, v[2] / h, v[3] / h, 100 * v[4] / h)) }
        }
    }
}
