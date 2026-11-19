package inflation.tools

import inflation.ecology.Danger
import inflation.ecology.FaunaKind
import inflation.ecology.feltC
import inflation.ecology.hazards
import inflation.ecology.sunLoadC
import inflation.enumFloats
import inflation.tools.fauna.FaunaCheckOptions
import inflation.world.Place
import inflation.world.get

/**
 * What the open country costs the kinds that keep to cover and water, against those of the open: for wild boar, aurochs,
 * deer and gazelle, by kind of country and quarter of the last year, weighted by heads: the cover where they stand, the
 * sun's load on a kind that cannot sweat ([sunLoadC], °C) and the heat felt over its comfort, how fed, and the shares that
 * die a year (per 1000) of hunger, heat, thirst, the hunters (residents' and parties'), and crowding sickness, as
 * [hazards] has them where they stand.
 *
 * Usage: open-ground-costs (--world NAME | --seed N [--width KM] [--height KM]) [--years N]  (the last year is reported)
 */
fun main(args: Array<String>) {
    val years = args.indexOf("--years").takeIf { it >= 0 }?.let { args[it + 1].toInt() } ?: 1
    val rest = args.toMutableList().also { i -> val j = i.indexOf("--years"); if (j >= 0) { i.removeAt(j); i.removeAt(j) } }
    val sim = FaunaCheckOptions.parse((rest + listOf("--years", "1")).toTypedArray()).start()
    val land = sim.land; val fauna = sim.fauna; val n = land.n
    // (Places by their plants as they stand at the start of the reported year, [VegClass].)
    val names0 = VegClass.names
    val cls = IntArray(n)
    val area = DoubleArray(names0.size)
    val names = listOf("wild boar", "aurochs", "deer", "gazelle")
    val ks = names.mapNotNull { nm -> fauna.kinds.indexOfFirst { it.name == nm }.takeIf { it >= 0 } }
    val dangers = listOf(Danger.HUNGER, Danger.HEAT, Danger.THIRST, Danger.HUNTED, Danger.SICK)
    // [kind][biome][quarter]: heads, cover, sun load, heat over comfort, fed, then each danger's share over the step.
    val m = 5 + dangers.size + 5
    // What makes the cover where they stand (and on the land at large, by area, [landCov]): shrubs kg/m², the canopy,
    // the wet valley floor's share of the place, and the air, °C (heads-weighted mean, and the hottest week's).
    val landCov = Array(names0.size) { Array(4) { DoubleArray(6) } }
    val acc = Array(fauna.kinds.size) { Array(names0.size) { Array(4) { DoubleArray(m) } } }
    val out = enumFloats<Danger>()
    val perYear = sim.stepsPerYear
    for (s in 0 until years * perYear) {
        if (s == (years - 1) * perYear) for (p in 0 until n) { cls[p] = VegClass.of(land, p); area[cls[p]] += land.areaKm2[p].toDouble() }
        sim.step()
        if (s < (years - 1) * perYear) continue
        val mo = sim.weather.date.month; val qtr = mo / 3
        val stepsAYear = 360.0 / sim.clock.days
        for (p in 0 until n) {
            val pl = Place(p); val b = cls[p]; val a = land.areaKm2[p].toDouble()
            val t = sim.weather.temperature[pl]
            val cover = land.plants.cover(pl).toDouble()
            val shrub = land.plants.shrub[p].toDouble(); val canopy = land.plants.canopy(pl).toDouble(); val wetFloor = land.hydro.wetFloor(pl).toDouble()
            val lc = landCov[b][qtr]; lc[0] += a; lc[1] += a * cover; lc[2] += a * shrub; lc[3] += a * canopy; lc[4] += a * wetFloor
            lc[5] += a * (if (cover < 0.2) 1.0 else 0.0)
            for (k in ks) {
                val heads = fauna.adults[k][p] + fauna.young[k][p]
                if (!(heads > 0f)) continue
                val h = heads.toDouble() * a
                val sp = fauna.kinds[k]
                val v = acc[k][b][qtr]
                val felt = fauna.feltC(sp, pl, t)
                v[0] += h; v[1] += h * cover; v[2] += h * fauna.sunLoadC(sp, pl, t); v[3] += h * sp.hotC(felt); v[4] += h * fauna.fed[k][p]
                fauna.hazards(FaunaKind(k), pl, sim.weather, mo, fauna.fed[k][p], fauna.dry[k][p], heads, out)
                for ((i, d) in dangers.withIndex()) v[5 + i] += h * out[d] * stepsAYear * 1000.0
                val j = 5 + dangers.size
                v[j] += h * shrub; v[j + 1] += h * canopy; v[j + 2] += h * wetFloor; v[j + 3] += h * t; v[j + 4] = maxOf(v[j + 4], t.toDouble())
            }
        }
    }
    val shown = names0.indices.filter { area[it] > 0.02 * area.sum() }.sortedByDescending { area[it] }
    println("open-ground-costs: last of $years years, by kind of country as its plants stood at the year's start (tree canopy, shrubs)")
    println("LAND by kind of country and quarter (area-weighted): cover | shrubs kg/m² | canopy | wet valley floor share | share of land with cover under 0.2")
    for (b in shown) { println("  " + names0[b])
        for (qt in 0 until 4) { val l = landCov[b][qt]; if (l[0] <= 0.0) continue
            println("    Q${qt + 1}  %.2f | %.2f | %.2f | %.2f | %3.0f%%".format(l[1] / l[0], l[2] / l[0], l[3] / l[0], l[4] / l[0], 100 * l[5] / l[0])) } }
    println("by kind, kind of country and quarter (Jan-Mar, Apr-Jun, Jul-Sep, Oct-Dec), heads-weighted:")
    println("  share of heads | cover | sun load °C | heat over comfort °C | fed | per 1000 a year: hunger / heat / thirst / hunted / crowd sick || shrubs kg/m² | canopy | wet floor | air °C mean / hottest")
    for (k in ks) {
        val all = acc[k].sumOf { q -> q.sumOf { it[0] } }
        if (all <= 0.0) continue
        println(fauna.kinds[k].name + ":")
        for (b in shown) {
            val tot = acc[k][b].sumOf { it[0] }
            if (tot / all < 0.01) continue
            println("  " + names0[b])
            for (qt in 0 until 4) { val v = acc[k][b][qt]; val h = v[0]; if (h <= 0.0) continue
                println("    Q${qt + 1}  %3.0f%% | %.2f | %.1f | %.1f | %.2f | ".format(100 * h / all, v[1] / h, v[2] / h, v[3] / h, v[4] / h) +
                    dangers.indices.joinToString(" / ") { "%.0f".format(v[5 + it] / h) } +
                    (5 + dangers.size).let { j -> " || %.2f | %.2f | %.2f | %.1f / %.1f".format(v[j] / h, v[j + 1] / h, v[j + 2] / h, v[j + 3] / h, v[j + 4]) }) }
        }
    }
}
