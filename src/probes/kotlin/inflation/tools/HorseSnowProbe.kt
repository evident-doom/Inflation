package inflation.tools

import inflation.ecology.Hex
import inflation.ecology.FaunaKind
import inflation.ecology.Place
import inflation.tools.fauna.FaunaCheckOptions
import inflation.world.HexWorld

/**
 * Probe: where a resident kind (default wild horse) stands by height through the year, against the snow on its
 * places, how well it fed there, and what the bottom band of the same hex would be worth to it.
 * Usage: horseSnowProbe --world storm-1 --years 1 [--kind wild_horse] (a kind's name with _ for its spaces, as runTool splits at them)
 */
fun main(args: Array<String>) {
    val at = args.indexOf("--kind")
    val name = if (at >= 0) args.getOrNull(at + 1)?.replace('_', ' ') ?: error("--kind needs a name") else "wild horse"
    val options = FaunaCheckOptions.parse(if (at >= 0) (args.take(at) + args.drop(at + 2)).toTypedArray() else args)
    val sim = options.start()
    val fauna = sim.fauna; val hw = sim.hw; val land = sim.land; val weather = sim.weather
    val k = fauna.kinds.indexOfFirst { it.name == name }
    require(k >= 0) { "no resident kind $name" }
    val s = fauna.kinds[k]
    val n = fauna.n
    val bins = floatArrayOf(500f, 1000f, 1500f, 2000f, Float.POSITIVE_INFINITY)
    println("kind ${s.name}, snow limit ${s.snowLimitMm} mm water, comfort ${s.comfortC}")
    println("week date        heads | share by height <500 500-1000 1000-1500 1500-2000 >2000 | " +
        "share on snow >10 >100 >limit >2xlimit | >1500m: snow mm, fed, felt C, appeal here, appeal bottom band, odds here, odds bottom | <500m fed")
    val weeks = options.years * 52
    for (w in 0 until weeks) {
        sim.step()
        val a = fauna.adults[k]; val y = fauna.young[k]; val f = fauna.fed[k]
        var tot = 0.0; val byH = DoubleArray(bins.size); val snow = DoubleArray(4)
        var hiHeads = 0.0; var hiSnow = 0.0; var hiFed = 0.0; var hiT = 0.0; var hiAp = 0.0; var hiApLow = 0.0; var hiOdds = 0.0; var hiOddsLow = 0.0; var hiLowN = 0.0
        var loHeads = 0.0; var loFed = 0.0
        // Appeal's factors, high place vs bottom band of the same hex: fare, fresh, suits, tether, the rest (fear, fire, plague, crowding, discomfort, snow); and heads on high places they cannot stay at (odds NaN).
        val fH = DoubleArray(5); val fL = DoubleArray(5); var nanHeads = 0.0
        fun factors(q: Int): FloatArray {
            val fare = fauna.fareFor(s, Place(q)); val fresh = land.plants.nourishment(inflation.ecology.Place(q)) * fauna.forageProtein(s, Place(q))
            val suits = fauna.suits(s, Place(q)); val teth = fauna.tether(s, Place(q))
            return floatArrayOf(fare, fresh, suits, teth, fauna.appeal(FaunaKind(k), Place(q), weather) / (fare * fresh * suits * teth))
        }
        for (p in 0 until n) {
            val heads = ((a[p] + y[p]) * land.areaKm2[p]).toDouble()
            if (heads <= 0.0) continue
            tot += heads
            val m = hw.placeElevationM[p]
            byH[bins.indexOfFirst { m < it }] += heads
            val sp = weather.snowpack[p]
            if (sp > 10f) snow[0] += heads; if (sp > 100f) snow[1] += heads
            if (sp > s.snowLimitMm) snow[2] += heads; if (sp > 2 * s.snowLimitMm) snow[3] += heads
            if (m < 500f) { loHeads += heads; loFed += heads * f[p] }
            if (m >= 1500f) {
                hiHeads += heads; hiSnow += heads * sp; hiFed += heads * f[p]
                hiT += heads * fauna.feltC(s, Place(p), weather.temperature[p])
                hiAp += heads * fauna.appeal(FaunaKind(k), Place(p), weather); hiOdds += heads * fauna.arrive[k][p]
                val low = hw.firstPlaceIn(Hex(hw.placeHex[p]))
                if (fauna.arrive[k][p].isNaN()) nanHeads += heads
                if (!low.none && low.i != p) {
                    hiLowN += heads
                    val fh = factors(p); val fl = factors(low.i)
                    for (i in 0 until 5) { fH[i] += heads * fh[i]; fL[i] += heads * fl[i] }
                    hiApLow += heads * fauna.appeal(FaunaKind(k), Place(low.i), weather)
                    hiOddsLow += heads * fauna.arrive[k][low.i].let { if (it.isNaN()) 1f else it }
                }
            }
        }
        if (w % 4 == 3) println("      factors high/bottom: fare %.3g/%.3g fresh %.3f/%.3f suits %.3f/%.3f tether %.3f/%.3f rest %.3f/%.3f; heads at >1500 m on places they cannot stay %.1f%%".format(
            fH[0] / hiLowN, fL[0] / hiLowN, fH[1] / hiLowN, fL[1] / hiLowN, fH[2] / hiLowN, fL[2] / hiLowN, fH[3] / hiLowN, fL[3] / hiLowN, fH[4] / hiLowN, fL[4] / hiLowN, 100 * nanHeads / hiHeads))
        if (w % 2 == 1) {
            val sh = { x: Double -> "%5.1f".format(100 * x / tot) }
            val hi = { x: Double -> if (hiHeads > 0) "%7.3f".format(x / hiHeads) else "    -" }
            val hl = { x: Double -> if (hiLowN > 0) "%7.3f".format(x / hiLowN) else "    -" }
            println("%4d %s %8.0f | %s | %s | %6.0f %s %6.1f %s %s %s %s | %s".format(w + 1, weather.date, tot,
                byH.joinToString(" ") { sh(it) }, snow.joinToString(" ") { sh(it) },
                if (hiHeads > 0) hiSnow / hiHeads else 0.0, hi(hiFed), if (hiHeads > 0) hiT / hiHeads else 0.0,
                hi(hiAp), hl(hiApLow), hi(hiOdds), hl(hiOddsLow), if (loHeads > 0) "%.3f".format(loFed / loHeads) else "-"))
        }
    }
    println("hexes split into low and high ground above ${HexWorld.SPLIT_RELIEF_M} m of relief")
}
