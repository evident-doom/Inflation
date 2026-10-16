package inflation.tools

import inflation.ecology.Catalogue
import inflation.learning.HexLearning
import inflation.world.Date
import inflation.world.WorldConfig

/**
 * Probe: reptiles and amphibians by climate class: the yearly mean each year, and over the last [show]
 * years where they come from and go (born, dying out, of hunger, lying up, taken by hunters, and the
 * rest: spreading, fire, the cap), as shares of their mean a year; how well fed they are while out; and
 * the insects they and the birds eat. Also, over the whole map, the yearly mean of the other small game
 * and its hunters (and wild boar), and the rodents in March and September.
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
    val yearly = Array(classes.size) { DoubleArray(years) }; val yearlyF = Array(2) { DoubleArray(years) }; val classArea = DoubleArray(classes.size); val fArea = DoubleArray(2)
    for (p in 0 until n) if (c[p] >= 0) { classArea[c[p]] += land.areaKm2[p].toDouble(); fArea[if (forest[p]) 1 else 0] += land.areaKm2[p].toDouble() }
    val steps = years * Date.DAYS_PER_YEAR / cfg.daysPerStep; val perYear = Date.DAYS_PER_YEAR / cfg.daysPerStep
    val start = steps - show * perYear
    // Over the shown years: herps (mean, kg), and the ledger's sums (kg), per class.
    // Over the whole map, each year: rodents, hares, hyrax, birds, tortoises, foxes, owls (kg/km2), wild boar (head/km2); rodents in March and September.
    val smallNames = listOf("rodents", "hares", "hyrax", "birds", "tortoises", "foxes", "owls", "boar/km2", "rod. Mar", "rod. Sep")
    val smallYearly = Array(smallNames.size) { DoubleArray(years) }; val monthSteps = Array(2) { IntArray(years) }
    val boar = run.fauna.kinds.indexOfFirst { it.name == "wild boar" }; val allArea = classArea.sum()
    val herpMean = DoubleArray(classes.size); val ledger = Array(classes.size) { DoubleArray(8) }; val first = DoubleArray(classes.size); val last = DoubleArray(classes.size)
    repeat(steps) { w ->
        if (w == start) { for (l in sl.herpLedger) l.fill(0f); for (l in sl.herpTakenBy) l.fill(0f); for (p in 0 until n) if (c[p] >= 0) first[c[p]] += (sl.herps[p] * land.areaKm2[p]).toDouble() }
        run.step()
        val y = w / perYear
        for (p in 0 until n) { val cc = c[p]; if (cc < 0) continue; val a = land.areaKm2[p].toDouble(); yearly[cc][y] += sl.herps[p] * a / perYear; yearlyF[if (forest[p]) 1 else 0][y] += sl.herps[p] * a / perYear
            if (w >= start) herpMean[cc] += sl.herps[p] * a / (show * perYear) }
        val month = run.weather.date.month; val mi = when (month) { 2 -> 0; 8 -> 1; else -> -1 }; if (mi >= 0) monthSteps[mi][y]++
        for (p in 0 until n) { if (c[p] < 0) continue; val a = land.areaKm2[p].toDouble() / perYear
            val v = doubleArrayOf(sl.rodents[p].toDouble(), sl.hares[p].toDouble(), sl.hyrax[p].toDouble(), sl.birds[p].toDouble(), sl.tortoises[p].toDouble(), sl.foxes[p].toDouble(), sl.owls[p].toDouble(),
                if (boar >= 0) (run.fauna.adults[boar][p] + run.fauna.young[boar][p]).toDouble() else 0.0)
            for (i in v.indices) smallYearly[i][y] += v[i] * a
            if (mi >= 0) smallYearly[8 + mi][y] += sl.rodents[p] * land.areaKm2[p].toDouble() }
    }
    val takers = Array(classes.size) { DoubleArray(sl.herpTakenBy.size) }
    for (p in 0 until n) { val cc = c[p]; if (cc < 0) continue; val a = land.areaKm2[p].toDouble(); last[cc] += sl.herps[p] * a; for (i in 0 until 8) ledger[cc][i] += sl.herpLedger[i][p] * a
        for (i in sl.herpTakenBy.indices) takers[cc][i] += sl.herpTakenBy[i][p] * a }
    println("yearly mean herps kg/km2, year 1..$years")
    for ((ci, name) in classes.withIndex()) if (classArea[ci] > 0) println("%-16s".format(name) + yearly[ci].joinToString("") { "%8.2f".format(it / classArea[ci]) })
    println("%-16s".format("open (canopy<.5)") + yearlyF[0].joinToString("") { "%8.2f".format(it / fArea[0]) })
    println("%-16s".format("forest") + yearlyF[1].joinToString("") { "%8.2f".format(it / fArea[1]) })
    println()
    println("whole map, yearly mean kg/km2 (wild boar head/km2; rodents in March and September), year 1..$years")
    for ((i, name) in smallNames.withIndex()) println("%-16s".format(name) + smallYearly[i].withIndex().joinToString("") { (y, v) ->
        val per = if (i >= 8) monthSteps[i - 8][y].toDouble() else 1.0
        if (per > 0) "%8.2f".format(v / per / allArea) else "%8s".format("-") })
    println()
    println("last $show years, as % of the mean herps a year: born, died out, hunger, lying up, hunters, rest (spread, fire, cap), net change; fed while out; insects eaten kg/km2 a year by herps and by birds")
    println("%-16s%8s%8s%8s%8s%8s%8s%8s%8s%8s%8s%10s%10s".format("class", "herps", "born", "old", "hunger", "lying", "hunters", "rest", "net", "fed", "", "ins:herps", "ins:birds"))
    for ((ci, name) in classes.withIndex()) {
        if (classArea[ci] <= 0) continue
        val m = herpMean[ci]; val l = ledger[ci]
        fun pct(x: Double) = 100.0 * x / show / m
        val net = last[ci] - first[ci]
        val rest = net - (l[0] - l[1] - l[2] - l[3] - l[4])
        println("%-16s%8.1f%8.0f%8.0f%8.0f%8.0f%8.0f%8.0f%8.0f%8.2f%8s%10.0f%10.0f".format(name, m / classArea[ci], pct(l[0]), pct(l[1]), pct(l[2]), pct(l[3]), pct(l[4]), pct(rest), pct(net),
            if (l[6] > 0) l[5] / l[6] else Double.NaN, "", l[5] / show / classArea[ci], l[7] / show / classArea[ci]))
    }
    println()
    println("taken by hunters, last $show years, % of the mean herps a year, by who took them (largest first)")
    val names = run.fauna.kinds.map { it.name }
    for ((ci, name) in classes.withIndex()) {
        if (classArea[ci] <= 0) continue
        val m = herpMean[ci]
        val list = takers[ci].indices.filter { takers[ci][it] > 0.0 }.sortedByDescending { takers[ci][it] }.take(6)
            .joinToString(", ") { i -> "%s %.0f".format(if (i == inflation.ecology.HexSmallLife.TAKER_GROUPS) "groups and people" else names.getOrElse(i) { "kind $i" }, 100.0 * takers[ci][i] / show / m) }
        println("%-16s%s".format(name, list))
    }
}
