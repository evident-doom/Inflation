package inflation.tools

import inflation.tools.fauna.FaunaCheckOptions
import inflation.world.Biome
import inflation.world.Place

/**
 * Where the water of steppe country goes and what the herbs make of it, over a year: by kind of country now (the live
 * label) and, for world-gen steppe, by its trees. Per group: area, rain, aridity; trees, shrubs and herbs standing (herbs'
 * year mean and peak, kg/m²); the year's water, mm: rain, bare-ground evaporation, the herbs', shrubs' and trees' transpiration
 * from the soil, runoff; the herbs' light share; grazing over the year, g/m²; the plant-eaters' weight, kg/km². For judging
 * whether thin herbs in steppe come from the water going elsewhere, the light, or the grazing (2026-11-25: world-gen steppe
 * held 22 g/m² of edible herbs against 30-230 g/m² peak standing in real steppe).
 *
 * Usage: herb-budget (--world NAME | --seed N) — runs one year from the start and reports it.
 */
fun main(args: Array<String>) {
    val sim = FaunaCheckOptions.parse((args.toList() + listOf("--years", "1")).toTypedArray()).start()
    val land = sim.land; val pl = land.plants; val hy = land.hydro; val n = land.n; val c = land.hw.world.climate
    val fauna = sim.fauna; val eaters = fauna.kinds.indices.filter { !fauna.kinds[it].carnivore }
    val herbMean = DoubleArray(n); val herbPeak = DoubleArray(n); val rain = DoubleArray(n); val evap = DoubleArray(n)
    val tr = Array(3) { DoubleArray(n) }; val runoff = DoubleArray(n); val herbLight = DoubleArray(n); val grazed = DoubleArray(n); val eatersKg = DoubleArray(n)
    val ls = FloatArray(3)
    val steps = sim.stepsPerYear
    for (s in 0 until steps) {
        sim.step()
        val days = sim.clock.days.toDouble()
        for (p in 0 until n) {
            val h = pl.herb[p].toDouble(); herbMean[p] += h / steps; if (h > herbPeak[p]) herbPeak[p] = h
            rain[p] += sim.weather.precipitation[p].toDouble()
            evap[p] += hy.evaporatedMm[p].toDouble(); for (k in 0..2) tr[k][p] += hy.transpired[k][p].toDouble(); runoff[p] += hy.runoffMm[p].toDouble()
            pl.lightShares(Place(p), ls); herbLight[p] += ls[0].toDouble() / steps
            grazed[p] += pl.grazing[p] * days / 1000.0
            var w = 0.0; for (k in eaters) w += (fauna.adults[k][p] + 0.3f * fauna.young[k][p]) * fauna.kinds[k].massKg
            eatersKg[p] += w / steps
        }
    }
    val all = (0 until n).sumOf { land.areaKm2[it].toDouble() }
    fun report(title: String, ps: List<Int>) {
        if (ps.isEmpty()) return
        val a = ps.sumOf { land.areaKm2[it].toDouble() }
        fun m(f: (Int) -> Double) = ps.sumOf { f(it) * land.areaKm2[it] } / a
        println("%-34s %5.1f%% rain %4.0f arid %.2f | tree %5.2f shrub %.2f herb %.3f peak %.3f | evap %4.0f herbs %4.0f shrubs %4.0f trees %4.0f runoff %4.0f | herb light %.2f | grazed %5.1f g/m² | eaters %5.0f kg/km²".format(
            title, 100 * a / all, m { rain[it] }, m { land.mean(it) { i -> c.aridity(i) }.toDouble() }, m { pl.tree[it].toDouble() }, m { pl.shrub[it].toDouble() },
            m { herbMean[it] }, m { herbPeak[it] }, m { evap[it] }, m { tr[0][it] }, m { tr[1][it] }, m { tr[2][it] }, m { runoff[it] }, m { herbLight[it] }, m { grazed[it] }, m { eatersKg[it] }))
    }
    println("by kind of country now:")
    for (b in listOf(Biome.STEPPE, Biome.SHRUBLAND, Biome.SEMI_DESERT, Biome.OPEN_WOODLAND, Biome.MEDITERRANEAN_WOODLAND, Biome.TEMPERATE_FOREST))
        report(b.name.lowercase(), (0 until n).filter { land.country.kind[it] == b.ordinal })
    println("world-gen steppe, by trees now (kg/m²):")
    val wg = (0 until n).filter { land.hw.placeBiome[it] == Biome.STEPPE.ordinal }
    for ((lo, hi) in listOf(-1f to 0.1f, 0.1f to 1f, 1f to 4f, 4f to Float.MAX_VALUE))
        report("  trees %s-%s".format(if (lo < 0f) "0" else "$lo", if (hi > 1e9f) "" else "$hi"), wg.filter { pl.tree[it] > lo && pl.tree[it] <= hi })
    println("world-gen steppe, treeless (<0.1), by aridity:")
    for ((lo, hi) in listOf(0f to 0.2f, 0.2f to 0.35f, 0.35f to 0.5f, 0.5f to 0.65f, 0.65f to 10f))
        report("  aridity %.2f-%.2f".format(lo, minOf(hi, 9.99f)), wg.filter { pl.tree[it] <= 0.1f && land.mean(it) { i -> c.aridity(i) }.let { a -> a >= lo && a < hi } })
}
