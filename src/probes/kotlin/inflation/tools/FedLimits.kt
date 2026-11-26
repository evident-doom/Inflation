package inflation.tools

import inflation.ecology.cropBrowse
import inflation.ecology.cropGrass
import inflation.ecology.foragedQuality
import inflation.ecology.grassUnderSnow
import inflation.ecology.gutFill
import inflation.ecology.reachOf
import inflation.ecology.selected
import inflation.tools.fauna.FaunaCheckOptions
import inflation.world.Place

/**
 * What holds each plant-eater's feeding where it stands, over the last year, heads-weighted, by season (local months:
 * spring 3-5, summer 6-8, autumn 9-11, winter 12-2) and by how crowded it stands (heads / [inflation.ecology.HexFauna.usualAt]):
 * how fed by food ([inflation.ecology.HexFauna.fedByFood], before reserves), the forage's worth as it picks it
 * ([foragedQuality], [selected]), its gut fill on that ([gutFill]), the share of its want of grass and of browse it can crop
 * as they stand ([cropGrass], [cropBrowse]), its reach ([reachOf]), the grass under snow it gets at ([grassUnderSnow]),
 * and the share of the place's week's grass the eaters left ([inflation.ecology.HexFauna.spareGrass] against what was in
 * reach): near 0 where they ate it all (food short among them), near 1 where they left nearly all (each held by its own
 * cropping and the forage's worth, whatever their numbers). For telling whether food tightens with numbers.
 *
 * Usage: fed-limits (--world NAME | --load FILE | --seed N ...) [--years N] [--kinds a,b,...]
 */
fun main(args: Array<String>) {
    fun opt(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args[it + 1] }
    val years = opt("--years")?.toInt() ?: 1
    val names = opt("--kinds")?.split(",")?.map { it.trim() } ?: listOf("wild boar", "roe deer", "deer", "gazelle", "aurochs", "ostrich")
    val rest = args.toMutableList().also { i -> for (o in listOf("--years", "--kinds")) { val j = i.indexOf(o); if (j >= 0) { i.removeAt(j); i.removeAt(j) } } }
    val sim = FaunaCheckOptions.parse((rest + listOf("--years", "1")).toTypedArray()).start()
    val land = sim.land; val fauna = sim.fauna; val n = land.n
    val ks = names.mapNotNull { nm -> fauna.kinds.indexOfFirst { it.name == nm }.takeIf { it >= 0 } }
    val bins = floatArrayOf(0.5f, 1.5f, 3f, Float.POSITIVE_INFINITY)
    val binNames = listOf("<0.5x", "0.5-1.5x", "1.5-3x", ">3x")
    val seasons = listOf("spring", "summer", "autumn", "winter")
    // [kind][season][bin][0 heads, 1 fed, 2 q, 3 fill, 4 cropG, 5 cropB, 6 reach, 7 under snow, 8 grass left]
    val acc = Array(ks.size) { Array(4) { Array(bins.size) { DoubleArray(9) } } }
    val perYear = sim.stepsPerYear; val total = years * perYear
    for (step in 0 until total) {
        sim.step()
        if (step < total - perYear) continue
        val month = sim.weather.date.month
        for ((i, k) in ks.withIndex()) {
            val s = fauna.kinds[k]
            for (p in 0 until n) {
                val pl = Place(p)
                val h = (fauna.adults[k][p] + fauna.young[k][p]).toDouble(); if (h <= 0.0) continue
                val heads = h * land.areaKm2[p]
                val crowd = h / fauna.usualAt(k, pl).coerceAtLeast(1e-9f)
                val b = bins.indexOfFirst { crowd < it }
                val season = ((land.localMonth(pl, month) + 10) % 12) / 3
                val v = acc[i][season][b]
                val q = s.selected(fauna.foragedQuality(pl))
                val inReach = land.palatable[p] * land.plants.herb[p]
                v[0] += heads; v[1] += heads * fauna.fedByFood[k][p]; v[2] += heads * q; v[3] += heads * gutFill(s, q)
                v[4] += heads * fauna.cropGrass(s, pl); v[5] += heads * fauna.cropBrowse(s, pl); v[6] += heads * fauna.reachOf(s, pl)
                v[7] += heads * grassUnderSnow(s, sim.weather.snowpack[p])
                v[8] += heads * (if (inReach > 0f) (fauna.spareGrass[p] / (0.01f * inReach)).coerceIn(0f, 1f) else 1f)
            }
        }
    }
    println("fed-limits: last of $years years; per kind, season and crowding (heads / usual): share of heads | fed by food | forage worth picked | gut fill | crop grass | crop browse | reach | grass not under snow | grass left of a week's reach")
    for ((i, k) in ks.withIndex()) {
        val all = acc[i].sumOf { s -> s.sumOf { it[0] } }; if (all <= 0.0) continue
        println(fauna.kinds[k].name + ":")
        for (si in 0 until 4) for (b in bins.indices) { val v = acc[i][si][b]; if (v[0] < 0.005 * all) continue
            println("  %-7s %-9s %4.0f%% | %.2f | %.2f | %.2f | %.2f | %.2f | %.2f | %.2f | %.2f".format(seasons[si], binNames[b], 100 * v[0] / all,
                v[1] / v[0], v[2] / v[0], v[3] / v[0], v[4] / v[0], v[5] / v[0], v[6] / v[0], v[7] / v[0], v[8] / v[0])) }
    }
}
