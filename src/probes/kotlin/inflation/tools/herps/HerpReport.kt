package inflation.tools.herps

import inflation.ecology.Taker
import inflation.ecology.HexSim
import inflation.ecology.BirdLedger
import inflation.ecology.HerpLedger
import inflation.ecology.HexSmallLife
import inflation.ecology.TortLedger

/** A herp check's report at the end of the run, section by section, from [HerpTally] and the model's small-life ledgers. */
class HerpReport(run: HexSim, private val regions: HerpRegions, private val t: HerpTally) {
    private val sl = run.fauna.small
    private val years = t.years
    private val show = t.show
    private val classArea = regions.classArea
    private val allArea = regions.allArea
    private val names = run.fauna.kinds.map { it.name }

    fun print() {
        herpsByYear(); println()
        smallGameByYear(); println()
        herpLedger(); println()
        herpTakers(); println()
        tortoises(); println()
        preyTakers()
        grubs(); println()
        birds()
        if (t.song >= 0) { println(); monthByMonth() }
    }

    /** Who [i] is, of the takers in a takenBy ledger. */
    private fun takerName(i: Int) = when (i) {
        Taker.GROUPS.i -> "groups and people"
        Taker.FOXES.i -> "foxes"
        Taker.EAGLES.i -> "eagles"
        Taker.OWLS.i -> "owls"
        else -> names.getOrElse(i) { "kind $i" }
    }

    /** The largest [top] of [sums] by who took them, each as [pct]. */
    private fun largestTakers(sums: DoubleArray, top: Int, pct: (Double) -> Double) =
        sums.indices.filter { sums[it] > 0.0 }.sortedByDescending { sums[it] }.take(top)
        .joinToString(", ") { i -> "%s %.0f".format(takerName(i), pct(sums[i])) }

    private fun herpsByYear() {
        println("yearly mean herps kg/km2, year 1..$years")
        for (c in regions.present) println("%-16s".format(c.label) + t.yearly[c.ordinal].joinToString("") { "%8.2f".format(it / classArea[c.ordinal]) })
        println("%-16s".format("open (canopy<.5)") + t.yearlyForest[0].joinToString("") { "%8.2f".format(it / regions.forestArea[0]) })
        println("%-16s".format("forest") + t.yearlyForest[1].joinToString("") { "%8.2f".format(it / regions.forestArea[1]) })
    }

    private fun smallGameByYear() {
        println("whole map, yearly mean kg/km2 (wild boar head/km2; rodents in March and September), year 1..$years")
        for ((i, name) in HerpTally.SMALL.withIndex()) println("%-16s".format(name) + t.smallYearly[i].withIndex().joinToString("") { (y, v) ->
            val per = if (i in 8..9) t.monthWeeks[i - 8][y].toDouble() else 1.0
            if (per > 0) "%8.2f".format(v / per / allArea) else "%8s".format("-") })
    }

    private fun herpLedger() {
        val ledger = Array(HerpLedger.SIZE) { i -> regions.byClass { sl.herpLedger[i][it].toDouble() } }
        val last = regions.byClass { sl.herps[it].toDouble() }
        println("last $show years, as % of the mean herps a year: born, died out, hunger, lying up, hunters, rest (spread, fire, cap), net change; fed while out; insects eaten kg/km2 a year by herps and by birds")
        println("%-16s%8s%8s%8s%8s%8s%8s%8s%8s%8s%8s%10s%10s".format("class", "herps", "born", "old", "hunger", "lying", "hunters", "rest", "net", "fed",
            "", "ins:herps", "ins:birds"))
        for (c in regions.present) {
            val ci = c.ordinal
            val m = t.herpMean[ci]
            fun l(i: Int) = ledger[i][ci]
            fun pct(x: Double) = 100.0 * x / show / m
            val net = last[ci] - t.herpsAtStart[ci]
            val rest = net - (l(HerpLedger.BORN) - l(HerpLedger.DIED_OUT) - l(HerpLedger.HUNGER) - l(HerpLedger.LYING_UP) - l(HerpLedger.HUNTED))
            println("%-16s%8.1f%8.0f%8.0f%8.0f%8.0f%8.0f%8.0f%8.0f%8.2f%8s%10.0f%10.0f".format(c.label, m / classArea[ci], pct(l(HerpLedger.BORN)),
                pct(l(HerpLedger.DIED_OUT)), pct(l(HerpLedger.HUNGER)), pct(l(HerpLedger.LYING_UP)), pct(l(HerpLedger.HUNTED)), pct(rest), pct(net),
                if (l(HerpLedger.NEEDED) > 0) l(HerpLedger.ATE) / l(HerpLedger.NEEDED) else Double.NaN, "", l(HerpLedger.ATE) / show / classArea[ci],
                l(HerpLedger.EATEN_BY_BIRDS) / show / classArea[ci]))
        }
    }

    private fun herpTakers() {
        val takers = Array(sl.herpTakenBy.size) { i -> regions.byClass { sl.herpTakenBy[i][it].toDouble() } }
        println("taken by hunters, last $show years, % of the mean herps a year, by who took them (largest first)")
        for (c in regions.present) {
            val m = t.herpMean[c.ordinal]
            val sums = DoubleArray(takers.size) { takers[it][c.ordinal] }
            println("%-16s%s".format(c.label, largestTakers(sums, 6) { 100.0 * it / show / m }))
        }
    }

    private fun tortoises() {
        val ledger = Array(TortLedger.SIZE) { i -> regions.byClass { sl.tortLedger[i][it].toDouble() } }
        val takers = Array(sl.tortTakenBy.size) { i -> regions.byClass { sl.tortTakenBy[i][it].toDouble() } }
        println("tortoises: yearly mean kg/km2, year 1..$years (cap: the capacity they grow toward, as at the start)")
        for (c in regions.present) println("%-16s%8.2f |".format(c.label, t.tortCapacity[c.ordinal] / classArea[c.ordinal]) +
            t.tortYearly[c.ordinal].joinToString("") { "%7.2f".format(it / classArea[c.ordinal]) })
        println("tortoises, last $show years, as % of their mean a year: grown, died, crowded, hunters, burnt, rest (spread), net; days a year out, of them in the breeding months")
        println("%-16s%8s%8s%8s%8s%8s%8s%8s%8s%8s%8s".format("class", "kg/km2", "grown", "died", "crowd", "hunters", "burnt", "rest", "net", "out",
            "breed"))
        for (c in regions.present) {
            val ci = c.ordinal
            val m = t.tortMean[ci]
            fun l(i: Int) = ledger[i][ci]
            fun pct(x: Double) = if (m > 0) 100.0 * x / show / m else Double.NaN
            val ty = t.tortYearly[ci]
            val net = (ty[years - 1] - (if (years > show) ty[years - show - 1] else ty[0])) / show
            val rest = net - (l(TortLedger.GROWN) - l(TortLedger.DIED) - l(TortLedger.CROWDED) - l(TortLedger.HUNTED) - l(TortLedger.BURNT)) / show
            println("%-16s%8.2f%8.0f%8.0f%8.0f%8.0f%8.0f%8.0f%8.0f%8.0f%8.0f".format(c.label, m / classArea[ci], pct(l(TortLedger.GROWN)),
                pct(l(TortLedger.DIED)), pct(l(TortLedger.CROWDED)), pct(l(TortLedger.HUNTED)), pct(l(TortLedger.BURNT)), 100.0 * rest / m, 100.0 * net / m,
                l(TortLedger.DAYS_OUT) / show / classArea[ci], l(TortLedger.BREEDING_DAYS) / show / classArea[ci]))
            val sums = DoubleArray(takers.size) { takers[it][ci] }
            val list = largestTakers(sums, 5, ::pct)
            println("%-16s  taken by: %s".format("", list))
        }
    }

    private fun preyTakers() {
        for ((ki, kind) in listOf("rodents", "hares", "hyrax", "birds").withIndex()) {
            println("$kind taken, last $show years, % of their mean a year, by who took them (largest first; kg/km2 the mean)")
            val takers = Array(sl.preyTakenBy[ki].size) { i -> regions.byClass { sl.preyTakenBy[ki][i][it].toDouble() } }
            for (c in regions.present) {
                val m = t.preyMean[ki][c.ordinal]
                val sums = DoubleArray(takers.size) { takers[it][c.ordinal] }
                fun pct(x: Double) = if (m > 0) 100.0 * x / show / m else Double.NaN
                println("%-16s%8.2f  all %.0f: %s".format(c.label, m / classArea[c.ordinal], pct(sums.sum()), largestTakers(sums, 6, ::pct)))
            }
        }
    }

    private fun grubs() {
        println("grubs dug by rooting beasts, last $show years, kg/km2 a year")
        val grubs = regions.byClass { sl.grubsDug[it].toDouble() }
        for (c in regions.present) println("%-16s%8.1f".format(c.label, grubs[c.ordinal] / show / classArea[c.ordinal]))
    }

    private fun birds() {
        val birds = HexSmallLife.BIRDS
        println("birds, whole map: capacity kg/km2 (as at the start) | yearly mean kg/km2, year 1..$years")
        for (k in birds.indices) println("%-16s%8.2f |".format(birds[k].name, t.birdCapacity[k] / allArea) +
            t.birdYearly[k].joinToString("") { "%7.2f".format(it / allArea) })
        println("birds, last $show years, as % of their mean a year: grown, died, crowded, left, came back, burnt, rest (hunters, spread), net; share fed on insects while eating them (- for kinds that eat none)")
        println("%-16s%8s%8s%8s%8s%8s%8s%8s%8s%8s%8s".format("kind", "kg/km2", "grown", "died", "crowd", "left", "back", "burnt", "rest", "net", "fed"))
        for (k in birds.indices) {
            val l = DoubleArray(BirdLedger.SIZE) { i -> regions.overMap { sl.birdLedger[k][i][it].toDouble() } }
            val m = t.birdMean[k]
            fun pct(x: Double) = if (m > 0) 100.0 * x / show / m else Double.NaN
            val net = (t.birdYearly[k][years - 1] - t.birdYearly[k][maxOf(0, years - show - 1)]) / show
            val rest = net - (l[BirdLedger.GROWN] - l[BirdLedger.DIED] - l[BirdLedger.CROWDED] - l[BirdLedger.LEFT] + l[BirdLedger.BACK] -
                l[BirdLedger.BURNT]) / show
            println("%-16s%8.2f%8.0f%8.0f%8.0f%8.0f%8.0f%8.0f%8.0f%8.0f%8s".format(birds[k].name, m / allArea, pct(l[BirdLedger.GROWN]),
                pct(l[BirdLedger.DIED]), pct(l[BirdLedger.CROWDED]), pct(l[BirdLedger.LEFT]), pct(l[BirdLedger.BACK]), pct(l[BirdLedger.BURNT]),
                if (m > 0) 100.0 * rest / m else Double.NaN, if (m > 0) 100.0 * net / m else Double.NaN,
                if (l[BirdLedger.INSECT_DAYS] > 0) "%.2f".format(l[BirdLedger.FED_DAYS] / l[BirdLedger.INSECT_DAYS]) else "-")
            )  // "-": a kind that eats no insects
        }
    }

    private fun monthByMonth() {
        println("month by month, last $show years, whole map kg/km2: songbirds; their growth and deaths a month; share fed while eating insects; insects out, pollinators, herps; insects eaten a month by birds, by herps; herps' share fed; herps dying of hunger a month")
        println("%-5s%9s%8s%8s%6s%9s%8s%8s%9s%9s%6s%8s".format("month", "song", "grown", "died", "fed", "insects", "pollin", "herps", "ins:brd",
            "ins:hrp", "hfed", "hhung"))
        for (mo in 0 until 12) {
            val st = t.monthWeeksShown[mo].coerceAtLeast(1)
            val f = t.monthFlow[mo]
            val s = t.monthStock[mo]
            val perMonth = 1.0 / show / allArea
            println("%-5d%9.2f%8.2f%8.2f%6.2f%9.1f%8.2f%8.1f%9.1f%9.1f%6.2f%8.2f".format(mo, s[0] / st / allArea, f[MonthFlow.SONG_GROWN] * perMonth,
                f[MonthFlow.SONG_DIED] * perMonth,
                if (f[MonthFlow.SONG_INSECT_DAYS] > 0) f[MonthFlow.SONG_FED_DAYS] / f[MonthFlow.SONG_INSECT_DAYS] else Double.NaN,
                s[1] / st / allArea, s[2] / st / allArea, s[3] / st / allArea, f[MonthFlow.INSECTS_TO_BIRDS] * perMonth,
                f[MonthFlow.INSECTS_TO_HERPS] * perMonth,
                if (f[MonthFlow.HERPS_NEEDED] > 0) f[MonthFlow.INSECTS_TO_HERPS] / f[MonthFlow.HERPS_NEEDED] else Double.NaN,
                f[MonthFlow.HERPS_HUNGER] * perMonth))
        }
    }
}
