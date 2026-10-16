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
    val smallNames = listOf("rodents", "hares", "hyrax", "birds", "tortoises", "foxes", "owls", "boar/km2", "rod. Mar", "rod. Sep", "insects", "dormant ins.", "pollinators")
    val smallYearly = Array(smallNames.size) { DoubleArray(years) }; val monthSteps = Array(2) { IntArray(years) }
    val boar = run.fauna.kinds.indexOfFirst { it.name == "wild boar" }; val allArea = classArea.sum()
    // Tortoises per class: yearly mean, and over the shown years their mean and the ledger's sums.
    val tYearly = Array(classes.size) { DoubleArray(years) }; val tMean = DoubleArray(classes.size); val tLedger = Array(classes.size) { DoubleArray(7) }
    val tCap = DoubleArray(classes.size).also { for (p in 0 until n) if (c[p] >= 0) it[c[p]] += (sl.tortoiseCapacity(p) * land.areaKm2[p]).toDouble() }
    // Rodents, hares, hyrax, birds per class: mean over the shown years (kg).
    val preyMean = Array(4) { DoubleArray(classes.size) }
    // Each kind of bird over the whole map: yearly mean (kg), mean over the shown years, capacity (kg, as at the start).
    val nb = inflation.ecology.HexSmallLife.BIRDS.size
    val bYearly = Array(nb) { DoubleArray(years) }; val bMean = DoubleArray(nb)
    val bCap = DoubleArray(nb).also { for (k in 0 until nb) for (p in 0 until n) if (c[p] >= 0) it[k] += (sl.birdCapacityAt(k, p) * land.areaKm2[p]).toDouble() }
    // Month by month (see below): stocks (songbirds, insects, pollinators, herps) and flows since the last step.
    val song = inflation.ecology.HexSmallLife.BIRDS.indexOfFirst { it.name == "songbirds" }
    val mSteps = IntArray(12); val mStock = Array(12) { DoubleArray(4) }; val mFlow = Array(12) { DoubleArray(8) }; val mLast = DoubleArray(8)
    val herpMean = DoubleArray(classes.size); val ledger = Array(classes.size) { DoubleArray(8) }; val first = DoubleArray(classes.size); val last = DoubleArray(classes.size)
    repeat(steps) { w ->
        if (w == start) { for (l in sl.herpLedger) l.fill(0f); for (l in sl.herpTakenBy) l.fill(0f); for (l in sl.tortLedger) l.fill(0f); for (l in sl.tortTakenBy) l.fill(0f); for (k in sl.preyTakenBy) for (l in k) l.fill(0f); sl.grubsDug.fill(0f); for (k in sl.birdLedger) for (l in k) l.fill(0f); for (p in 0 until n) if (c[p] >= 0) first[c[p]] += (sl.herps[p] * land.areaKm2[p]).toDouble() }
        run.step()
        val y = w / perYear
        for (p in 0 until n) { val cc = c[p]; if (cc < 0) continue; val a = land.areaKm2[p].toDouble(); yearly[cc][y] += sl.herps[p] * a / perYear; yearlyF[if (forest[p]) 1 else 0][y] += sl.herps[p] * a / perYear
            if (w >= start) herpMean[cc] += sl.herps[p] * a / (show * perYear)
            tYearly[cc][y] += sl.tortoises[p] * a / perYear; if (w >= start) tMean[cc] += sl.tortoises[p] * a / (show * perYear)
            for (k in 0 until nb) { bYearly[k][y] += sl.bird[k][p] * a / perYear; if (w >= start) bMean[k] += sl.bird[k][p] * a / (show * perYear) }
            if (w >= start) { val f = a / (show * perYear); preyMean[0][cc] += sl.rodents[p] * f; preyMean[1][cc] += sl.hares[p] * f; preyMean[2][cc] += sl.hyrax[p] * f; preyMean[3][cc] += sl.birds[p] * f } }
        val month = run.weather.date.month; val mi = when (month) { 2 -> 0; 8 -> 1; else -> -1 }; if (mi >= 0) monthSteps[mi][y]++
        // Month by month over the shown years, whole map: songbirds and their ledger, insects, herps.
        if (w >= start) { val mo = month; mSteps[mo]++
            var sb = 0.0; var ins = 0.0; var pol = 0.0; var hp = 0.0; val now = DoubleArray(8)
            for (p in 0 until n) { if (c[p] < 0) continue; val a = land.areaKm2[p].toDouble()
                if (song >= 0) { sb += sl.bird[song][p] * a; now[0] += sl.birdLedger[song][0][p] * a; now[1] += sl.birdLedger[song][1][p] * a; now[2] += sl.birdLedger[song][6][p] * a; now[3] += sl.birdLedger[song][7][p] * a }
                ins += sl.insects[p] * a; pol += sl.pollinators[p] * a; hp += sl.herps[p] * a
                now[4] += sl.herpLedger[7][p] * a; now[5] += sl.herpLedger[5][p] * a; now[6] += sl.herpLedger[6][p] * a; now[7] += sl.herpLedger[2][p] * a }
            mStock[mo][0] += sb; mStock[mo][1] += ins; mStock[mo][2] += pol; mStock[mo][3] += hp
            for (i in 0 until 8) { mFlow[mo][i] += now[i] - mLast[i]; mLast[i] = now[i] } }
        for (p in 0 until n) { if (c[p] < 0) continue; val a = land.areaKm2[p].toDouble() / perYear
            val v = doubleArrayOf(sl.rodents[p].toDouble(), sl.hares[p].toDouble(), sl.hyrax[p].toDouble(), sl.birds[p].toDouble(), sl.tortoises[p].toDouble(), sl.foxes[p].toDouble(), sl.owls[p].toDouble(),
                if (boar >= 0) (run.fauna.adults[boar][p] + run.fauna.young[boar][p]).toDouble() else 0.0)
            for (i in v.indices) smallYearly[i][y] += v[i] * a
            smallYearly[10][y] += sl.insects[p] * a; smallYearly[11][y] += (sl.dormantInsects[p] + sl.dormantPollinators[p]) * a; smallYearly[12][y] += sl.pollinators[p] * a
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
        val per = if (i in 8..9) monthSteps[i - 8][y].toDouble() else 1.0
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
    println()
    val tTakers = Array(classes.size) { DoubleArray(sl.tortTakenBy.size) }
    for (p in 0 until n) { val cc = c[p]; if (cc < 0) continue; val a = land.areaKm2[p].toDouble(); for (i in 0 until 7) tLedger[cc][i] += sl.tortLedger[i][p] * a
        for (i in sl.tortTakenBy.indices) tTakers[cc][i] += sl.tortTakenBy[i][p] * a }
    println("tortoises: yearly mean kg/km2, year 1..$years (cap: the capacity they grow toward, as at the start)")
    for ((ci, name) in classes.withIndex()) if (classArea[ci] > 0) println("%-16s%8.2f |".format(name, tCap[ci] / classArea[ci]) + tYearly[ci].joinToString("") { "%7.2f".format(it / classArea[ci]) })
    println("tortoises, last $show years, as % of their mean a year: grown, died, crowded, hunters, burnt, rest (spread), net; days a year out, of them in the breeding months")
    println("%-16s%8s%8s%8s%8s%8s%8s%8s%8s%8s%8s".format("class", "kg/km2", "grown", "died", "crowd", "hunters", "burnt", "rest", "net", "out", "breed"))
    for ((ci, name) in classes.withIndex()) {
        if (classArea[ci] <= 0) continue
        val m = tMean[ci]; val l = tLedger[ci]
        fun pct(x: Double) = if (m > 0) 100.0 * x / show / m else Double.NaN
        val net = (tYearly[ci][years - 1] - tYearly[ci][years - show - 1].takeIf { years > show }.let { it ?: tYearly[ci][0] }) / show
        val rest = net - (l[0] - l[1] - l[2] - l[3] - l[4]) / show
        println("%-16s%8.2f%8.0f%8.0f%8.0f%8.0f%8.0f%8.0f%8.0f%8.0f%8.0f".format(name, m / classArea[ci], pct(l[0]), pct(l[1]), pct(l[2]), pct(l[3]), pct(l[4]), 100.0 * rest / m, 100.0 * net / m,
            l[6] / show / classArea[ci], l[5] / show / classArea[ci]))
        val list = tTakers[ci].indices.filter { tTakers[ci][it] > 0.0 }.sortedByDescending { tTakers[ci][it] }.take(5)
            .joinToString(", ") { i -> "%s %.0f".format(if (i == inflation.ecology.HexSmallLife.TAKER_GROUPS) "groups and people" else names.getOrElse(i) { "kind $i" }, pct(tTakers[ci][i])) }
        println("%-16s  taken by: %s".format("", list))
    }
    println()
    val HS = inflation.ecology.HexSmallLife
    fun takerName(i: Int) = when (i) { HS.TAKER_GROUPS -> "groups and people"; HS.TAKER_FOXES -> "foxes"; HS.TAKER_EAGLES -> "eagles"; HS.TAKER_OWLS -> "owls"; else -> names.getOrElse(i) { "kind $i" } }
    for ((ki, kind) in listOf("rodents", "hares", "hyrax", "birds").withIndex()) {
        println("$kind taken, last $show years, % of their mean a year, by who took them (largest first; kg/km2 the mean)")
        val sums = Array(classes.size) { DoubleArray(sl.preyTakenBy[ki].size) }
        for (p in 0 until n) { val cc = c[p]; if (cc < 0) continue; val a = land.areaKm2[p].toDouble(); for (i in sums[cc].indices) sums[cc][i] += sl.preyTakenBy[ki][i][p] * a }
        for ((ci, name) in classes.withIndex()) {
            if (classArea[ci] <= 0) continue
            val m = preyMean[ki][ci]
            val all = sums[ci].sum()
            val list = sums[ci].indices.filter { sums[ci][it] > 0.0 }.sortedByDescending { sums[ci][it] }.take(6)
                .joinToString(", ") { i -> "%s %.0f".format(takerName(i), if (m > 0) 100.0 * sums[ci][i] / show / m else Double.NaN) }
            println("%-16s%8.2f  all %.0f: %s".format(name, m / classArea[ci], if (m > 0) 100.0 * all / show / m else Double.NaN, list))
        }
    }
    println("grubs dug by rooting beasts, last $show years, kg/km2 a year")
    val grubs = DoubleArray(classes.size); for (p in 0 until n) if (c[p] >= 0) grubs[c[p]] += sl.grubsDug[p] * land.areaKm2[p].toDouble()
    for ((ci, name) in classes.withIndex()) if (classArea[ci] > 0) println("%-16s%8.1f".format(name, grubs[ci] / show / classArea[ci]))
    println()
    println("birds, whole map: capacity kg/km2 (as at the start) | yearly mean kg/km2, year 1..$years")
    val BIRDS = inflation.ecology.HexSmallLife.BIRDS
    for (k in 0 until nb) println("%-16s%8.2f |".format(BIRDS[k].name, bCap[k] / allArea) + bYearly[k].joinToString("") { "%7.2f".format(it / allArea) })
    println("birds, last $show years, as % of their mean a year: grown, died, crowded, left, came back, burnt, rest (hunters, spread), net; share fed on insects while eating them (- for kinds that eat none)")
    println("%-16s%8s%8s%8s%8s%8s%8s%8s%8s%8s%8s".format("kind", "kg/km2", "grown", "died", "crowd", "left", "back", "burnt", "rest", "net", "fed"))
    for (k in 0 until nb) {
        val l = DoubleArray(8); for (p in 0 until n) if (c[p] >= 0) { val a = land.areaKm2[p].toDouble(); for (i in 0 until 8) l[i] += sl.birdLedger[k][i][p] * a }
        val m = bMean[k]
        fun pct(x: Double) = if (m > 0) 100.0 * x / show / m else Double.NaN
        val net = (bYearly[k][years - 1] - bYearly[k][maxOf(0, years - show - 1)]) / show
        val rest = net - (l[0] - l[1] - l[2] - l[3] + l[4] - l[5]) / show
        println("%-16s%8.2f%8.0f%8.0f%8.0f%8.0f%8.0f%8.0f%8.0f%8.0f%8s".format(BIRDS[k].name, m / allArea, pct(l[0]), pct(l[1]), pct(l[2]), pct(l[3]), pct(l[4]), pct(l[5]),
            if (m > 0) 100.0 * rest / m else Double.NaN, if (m > 0) 100.0 * net / m else Double.NaN, if (l[7] > 0) "%.2f".format(l[6] / l[7]) else "-"))  // "-": a kind that eats no insects
    }
    if (song >= 0) {
        println()
        println("month by month, last $show years, whole map kg/km2: songbirds; their growth and deaths a month; share fed while eating insects; insects out, pollinators, herps; insects eaten a month by birds, by herps; herps' share fed; herps dying of hunger a month")
        println("%-5s%9s%8s%8s%6s%9s%8s%8s%9s%9s%6s%8s".format("month", "song", "grown", "died", "fed", "insects", "pollin", "herps", "ins:brd", "ins:hrp", "hfed", "hhung"))
        for (mo in 0 until 12) { val st = mSteps[mo].coerceAtLeast(1); val f = mFlow[mo]; val perMonth = 1.0 / show / allArea
            println("%-5d%9.2f%8.2f%8.2f%6.2f%9.1f%8.2f%8.1f%9.1f%9.1f%6.2f%8.2f".format(mo, mStock[mo][0] / st / allArea, f[0] * perMonth, f[1] * perMonth, if (f[3] > 0) f[2] / f[3] else Double.NaN,
                mStock[mo][1] / st / allArea, mStock[mo][2] / st / allArea, mStock[mo][3] / st / allArea, f[4] * perMonth, f[5] * perMonth, if (f[6] > 0) f[5] / f[6] else Double.NaN, f[7] * perMonth)) }
    }
}
