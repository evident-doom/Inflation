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
    val landAcc = Array(biomes.size) { Array(4) { DoubleArray(6) } }
    // Kinds [kind][biome][quarter]: heads, heads*fed, heads*crop, heads*fill, heads where the pool ran out, area-steps.
    val kindAcc = Array(fauna.kinds.size) { Array(biomes.size) { Array(4) { DoubleArray(5) } } }
    val stepsIn = IntArray(4)
    // Fed by crowding [kind][biome][bin]: heads and heads*fed where the kind stands at under 0.5, 0.5-1, 1-2 and over 2
    // times its usual numbers ([HexFauna.usualAt]); and by all plant-eaters' weight there, under 250, 250-1000, 1000-3000
    // and over 3000 kg/km². If the kinds feed one another's hunger, fed falls along both.
    val ownBins = Array(fauna.kinds.size) { Array(biomes.size) { DoubleArray(8) } }
    val allBins = Array(fauna.kinds.size) { Array(biomes.size) { DoubleArray(8) } }
    // Grazing against growth, by place over the last year: grass eaten, kg/m² of the place, and the peak of the edible herbs
    // standing (the peak-standing-crop measure of a year's growth, which leaves out what grew and was eaten or fell before it).
    val ateYear = DoubleArray(n); val peakHerb = DoubleArray(n)
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
            ateYear[p] += (pool - fauna.grazeLeft[p]).coerceAtLeast(0.0); if (herb > peakHerb[p]) peakHerb[p] = herb
            val l = landAcc[b][qtr]; l[0] += a; l[1] += herb * a; l[2] += eaten * a; if (out) l[3] += a
            l[4] += herb * land.plants.leafy(pl) * a; l[5] += land.plants.nourishment(pl) * a
            val q = land.plants.nourishment(pl)
            var kgKm2 = 0.0
            for (k in eaters) kgKm2 += (fauna.adults[k][p] + fauna.young[k][p]).toDouble() * fauna.kinds[k].massKg
            val allBin = when { kgKm2 < 250.0 -> 0; kgKm2 < 1000.0 -> 1; kgKm2 < 3000.0 -> 2; else -> 3 }
            for (k in eaters) {
                val h = (fauna.adults[k][p] + fauna.young[k][p]).toDouble() * a
                if (h <= 0.0) continue
                val sp = fauna.kinds[k]
                val v = kindAcc[k][b][qtr]
                v[0] += h; v[1] += h * fauna.fed[k][p]; v[2] += h * fauna.cropGrass(sp, pl); v[3] += h * gutFill(sp, sp.selected(q))
                if (out) v[4] += h
                val crowd = (fauna.adults[k][p] + fauna.young[k][p]) / fauna.usualAt(k, pl).coerceAtLeast(1e-9f)
                val ownBin = when { crowd < 0.5f -> 0; crowd < 1f -> 1; crowd < 2f -> 2; else -> 3 }
                val f = fauna.fed[k][p].toDouble()
                ownBins[k][b][2 * ownBin] += h; ownBins[k][b][2 * ownBin + 1] += h * f
                allBins[k][b][2 * allBin] += h; allBins[k][b][2 * allBin + 1] += h * f
            }
        }
    }
    println("steppe-feeding: last of $years years")
    for (b in shown) {
        println()
        println(biomes[b].label + ": by quarter (Jan-Mar, Apr-Jun, Jul-Sep, Oct-Dec)")
        var ar = 0.0; var ate = 0.0; var peak = 0.0
        for (p in 0 until n) if (hw.placeBiome[p] == b) { val a = land.areaKm2[p].toDouble(); ar += a; ate += ateYear[p] * a; peak += peakHerb[p] * a }
        if (ar > 0.0) println("  year: grass eaten %.1f g/m², peak edible herbs %.1f g/m², eaten/peak %.1f%%".format(1000 * ate / ar, 1000 * peak / ar, 100 * ate / peak.coerceAtLeast(1e-12)))
        println("  land: edible herbs kg/m² | share of it eaten a step | share of land where the step's pool ran out | of the herbs fresh leaf kg/m² (leafy) | nourishment")
        for (qt in 0 until 4) { val l = landAcc[b][qt]; if (l[0] <= 0.0) continue
            println("    Q${qt + 1}  %.4f | %.4f | %3.0f%% | %.5f | %.2f".format(l[1] / l[0], l[2] / l[0], 100 * l[3] / l[0], l[4] / l[0], l[5] / l[0])) }
        println("  kinds: heads/km² | fed | grass crop share | gut fill | heads where the pool ran out (diet grass/browse/fruit/roots)")
        for (k in eaters) {
            val tot = (0 until 4).sumOf { kindAcc[k][b][it][0] }
            if (tot <= 0.0) continue
            val d = fauna.kinds[k].diet
            println("    ${fauna.kinds[k].name} (%.2f/%.2f/%.2f/%.2f):".format(d.grass, d.browse, d.fruit, d.roots))
            for (qt in 0 until 4) { val v = kindAcc[k][b][qt]; val h = v[0]; if (h <= 0.0) continue
                val area = landAcc[b][qt][0] / stepsIn[qt].coerceAtLeast(1)
                println("      Q${qt + 1}  %.3f | %.2f | %.2f | %.2f | %3.0f%%".format(h / stepsIn[qt].coerceAtLeast(1) / area, v[1] / h, v[2] / h, v[3] / h, 100 * v[4] / h)) }
            fun bins(a: DoubleArray) = (0 until 4).joinToString("  ") { i -> val hh = a[2 * i]; if (hh <= 0.0) "-" else "%.2f (%.0f%%)".format(a[2 * i + 1] / hh, 100 * hh / tot) }
            println("      fed by own crowding (<0.5, 0.5-1, 1-2, >2 x usual; share of heads): " + bins(ownBins[k][b]))
            println("      fed by all plant-eaters' weight (<250, 250-1000, 1000-3000, >3000 kg/km²): " + bins(allBins[k][b]))
        }
    }
}
