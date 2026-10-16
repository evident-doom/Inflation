package inflation.tools

import inflation.ecology.Catalogue
import inflation.learning.HexLearning
import inflation.world.Date
import inflation.world.WorldConfig

/**
 * Probe: reptiles and amphibians by climate class: the yearly mean each year, and month by month over
 * the last [show] years the terms of their rule (out or not, soil moisture, growth on insects, what the
 * plants carry) against how they actually changed, the rest being what hunters took, fire and spreading.
 */
fun main(args: Array<String>) {
    var cfg = WorldConfig(seed = 1); var years = 5; var show = 2
    var k = 0
    while (k < args.size) { when (args[k]) { "--seed" -> cfg = cfg.copy(seed = args[++k].toLong()); "--width" -> cfg = cfg.copy(widthKm = args[++k].toInt()); "--height" -> cfg = cfg.copy(heightKm = args[++k].toInt()); "--years" -> years = args[++k].toInt(); "--show" -> show = args[++k].toInt() }; k++ }
    val run = HexLearning.Run(cfg, Catalogue.learned)
    val land = run.land; val hw = run.hw; val n = land.n; val sl = run.fauna.small
    val meanT = FloatArray(n) { p -> (0 until 12).map { hw.placeTemp(p, it) }.average().toFloat() }
    val rain = FloatArray(n) { p -> (0 until 12).sumOf { hw.precip[it][hw.placeHex[p]].toDouble() }.toFloat() }
    val classes = listOf("boreal/cold", "cool temperate", "warm temperate", "mediterranean", "hot desert", "hot wet")
    val c = IntArray(n) { p -> when {
        land.biomass(p) <= 0f -> -1
        rain[p] < 250f && meanT[p] >= 11f -> 4
        meanT[p] < 6f -> 0
        meanT[p] < 11f -> 1
        rain[p] < 500f -> 3
        meanT[p] < 16f -> 2
        else -> 5 } }
    val forest = BooleanArray(n) { p -> land.canopy(p) > 0.5f }
    val years1 = Date.DAYS_PER_YEAR.toFloat() / cfg.daysPerStep
    val q = listOf("herps kg/km2", "share out", "moist (out)", "insects (out)", "growth/yr (out)", "carrying kg/km2", "herps/carrying", "rule change %/yr", "actual change %/yr", "other %/yr")
    val sum = Array(classes.size) { Array(q.size) { DoubleArray(12) } }; val area = Array(classes.size) { DoubleArray(12) }; val areaOut = Array(classes.size) { DoubleArray(12) }
    val yearly = Array(classes.size) { DoubleArray(years) }; val yearlyF = Array(2) { DoubleArray(years) }; val classArea = DoubleArray(classes.size); val fArea = DoubleArray(2)
    for (p in 0 until n) if (c[p] >= 0) { classArea[c[p]] += land.areaKm2[p].toDouble(); fArea[if (forest[p]) 1 else 0] += land.areaKm2[p].toDouble() }
    val steps = years * Date.DAYS_PER_YEAR / cfg.daysPerStep; val perYear = Date.DAYS_PER_YEAR / cfg.daysPerStep
    val before = FloatArray(n)
    repeat(steps) { w ->
        sl.herps.copyInto(before)
        run.step()
        val y = w / perYear
        for (p in 0 until n) { val cc = c[p]; if (cc < 0) continue; val a = land.areaKm2[p].toDouble(); yearly[cc][y] += sl.herps[p] * a / perYear; yearlyF[if (forest[p]) 1 else 0][y] += sl.herps[p] * a / perYear }
        if (w < steps - show * perYear) return@repeat
        val m = run.weather.date.month
        for (p in 0 until n) {
            val cc = c[p]; if (cc < 0) continue; val a = land.areaKm2[p].toDouble()
            val t = run.weather.temperature[p]; val soil = land.soil[p]
            val out = sl.herpsActive(t, soil)
            val food = land.herb[p] + 0.3f * land.shrub[p]
            val hc = 80f * food / (food + 0.3f)
            val moist = (soil / 40f).coerceIn(0.2f, 1f)
            val ins = sl.insects[p]
            val grow = 4f * moist * ins / (ins + 400f)
            val h0 = before[p]
            val rule = if (h0 <= 0f) 0f else if (out) grow * (1 - h0 / hc.coerceAtLeast(1f)) - 1f else -0.3f
            val actual = if (h0 <= 0f) 0f else (sl.herps[p] - h0) / h0 * years1
            val v = doubleArrayOf(sl.herps[p].toDouble(), if (out) 1.0 else 0.0, 0.0, 0.0, 0.0, hc.toDouble(), if (hc > 0f) (sl.herps[p] / hc).toDouble() else 0.0, 100.0 * rule, 100.0 * actual, 100.0 * (actual - rule))
            // Rates weighted by the herps there, so they say what happens to the animals.
            val hw0 = h0.toDouble() * a
            for (i in v.indices) when (i) { 2, 3, 4 -> {}; 7, 8, 9 -> sum[cc][i][m] += v[i] * hw0; else -> sum[cc][i][m] += v[i] * a }
            area[cc][m] += a
            if (out) { sum[cc][2][m] += moist * a; sum[cc][3][m] += ins * a; sum[cc][4][m] += grow * a; areaOut[cc][m] += a }
            herpWeight[cc][m] += hw0
        }
    }
    println("yearly mean herps kg/km2, year 1..$years")
    for ((ci, name) in classes.withIndex()) if (classArea[ci] > 0) println("%-16s".format(name) + yearly[ci].joinToString("") { "%8.2f".format(it / classArea[ci]) })
    println("%-16s".format("open (canopy<.5)") + yearlyF[0].joinToString("") { "%8.2f".format(it / fArea[0]) })
    println("%-16s".format("forest") + yearlyF[1].joinToString("") { "%8.2f".format(it / fArea[1]) })
    for ((ci, name) in classes.withIndex()) {
        if (area[ci].sum() == 0.0) continue
        println("== $name")
        println("%-20s".format("month") + (0 until 12).joinToString("") { "%8d".format(it + 1) })
        for ((i, nm) in q.withIndex()) println("%-20s".format(nm) + (0 until 12).joinToString("") { m ->
            val d = when (i) { 2, 3, 4 -> areaOut[ci][m]; 7, 8, 9 -> herpWeight[ci][m]; else -> area[ci][m] }
            "%8.2f".format(if (d > 0) sum[ci][i][m] / d else Double.NaN) })
    }
}

private val herpWeight = Array(6) { DoubleArray(12) }
