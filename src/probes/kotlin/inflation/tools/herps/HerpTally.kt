package inflation.tools.herps

import inflation.ecology.HexSim
import inflation.ecology.BirdLedger
import inflation.ecology.HerpLedger
import inflation.ecology.HexSmallLife

/**
 * What a herp check counts week by week: yearly means of the herps, tortoises, birds and other small game
 * (by climate class or over the map), and over the last [show] years their means and month-by-month
 * stocks and flows. At the start of those years it clears the model's small-life ledgers, so that what
 * they hold at the end is those years' sums.
 */
class HerpTally(private val run: HexSim, private val regions: HerpRegions, val years: Int, val show: Int, val perYear: Int) {
    private val land = run.land
    private val sl = run.fauna.small
    private val n = land.n
    private val classes = ClimateClass.entries.size
    private val nb = HexSmallLife.BIRDS.size
    /** The first week of the shown years. */
    val start = years * perYear - show * perYear

    /** Herps by class each year, and in the open and under forest (yearly means, kg). */
    val yearly = Array(classes) { DoubleArray(years) }
    val yearlyForest = Array(2) { DoubleArray(years) }
    /** Herps and tortoises by class over the shown years (mean, kg); herps at their start. */
    val herpMean = DoubleArray(classes)
    val tortMean = DoubleArray(classes)
    val herpsAtStart = DoubleArray(classes)
    /** Tortoises by class each year (yearly means, kg). */
    val tortYearly = Array(classes) { DoubleArray(years) }
    /** Rodents, hares, hyrax and birds by class over the shown years (mean, kg). */
    val preyMean = Array(4) { DoubleArray(classes) }
    /** Each kind of bird over the map: yearly mean, and mean over the shown years (kg). */
    val birdYearly = Array(nb) { DoubleArray(years) }
    val birdMean = DoubleArray(nb)
    /** Over the map each year, [SMALL] (kg; wild boar head; rodents in March and September summed over those months' weeks, see [monthWeeks]). */
    val smallYearly = Array(SMALL.size) { DoubleArray(years) }
    /** Weeks of March [0] and September [1] each year. */
    val monthWeeks = Array(2) { IntArray(years) }
    /** Month by month over the shown years, the map's songbirds, insects, pollinators and herps (summed over the month's weeks), the
     * weeks, and [MonthFlow]s summed. */
    val monthWeeksShown = IntArray(12)
    val monthStock = Array(12) { DoubleArray(4) }
    val monthFlow = Array(12) { DoubleArray(MonthFlow.SIZE) }
    private val flowsBefore = DoubleArray(MonthFlow.SIZE)
    /** Capacities as at the start: tortoises by class, each kind of bird over the map (kg). */
    val tortCapacity = DoubleArray(classes).also {
        for (p in 0 until n) if (regions.classOf[p] >= 0) it[regions.classOf[p]] += (sl.tortoiseCapacity(inflation.ecology.Place(p)) * land.areaKm2[p]).toDouble()
    }
    val birdCapacity = DoubleArray(nb).also {
        for (k in 0 until nb) for (p in 0 until n) if (regions.classOf[p] >= 0) it[k] += (sl.birdCapacityAt(k, inflation.ecology.Place(p)) * land.areaKm2[p]).toDouble()
    }
    private val boar = run.fauna.kinds.indexOfFirst { it.name == "wild boar" }
    val song = HexSmallLife.BIRDS.indexOf(HexSmallLife.SONGBIRDS)

    /** Before step [w]: at the start of the shown years, clears the model's ledgers and notes the herps. */
    fun beforeStep(w: Int) {
        if (w != start) return
        for (l in sl.herpLedger) l.fill(0f); for (l in sl.herpTakenBy) l.fill(0f); for (l in sl.tortLedger) l.fill(0f); for (l in sl.tortTakenBy) l.fill(0f)
        for (k in sl.preyTakenBy) for (l in k) l.fill(0f); sl.grubsDug.fill(0f); for (k in sl.birdLedger) for (l in k) l.fill(0f)
        for (p in 0 until n) if (regions.classOf[p] >= 0) herpsAtStart[regions.classOf[p]] += (sl.herps[p] * land.areaKm2[p]).toDouble()
    }

    /** After step [w]. */
    fun afterStep(w: Int) {
        val y = w / perYear
        val shown = w >= start
        stocks(y, shown)
        val month = run.weather.date.month; val mi = when (month) { 2 -> 0; 8 -> 1; else -> -1 }; if (mi >= 0) monthWeeks[mi][y]++
        if (shown) monthByMonth(month)
        smallGame(y, mi)
    }

    private fun stocks(y: Int, shown: Boolean) {
        val forest = regions.forest
        for (p in 0 until n) {
            val cc = regions.classOf[p]; if (cc < 0) continue; val a = land.areaKm2[p].toDouble()
            yearly[cc][y] += sl.herps[p] * a / perYear; yearlyForest[if (forest[p]) 1 else 0][y] += sl.herps[p] * a / perYear
            if (shown) herpMean[cc] += sl.herps[p] * a / (show * perYear)
            tortYearly[cc][y] += sl.tortoises[p] * a / perYear; if (shown) tortMean[cc] += sl.tortoises[p] * a / (show * perYear)
            for (k in 0 until nb) { birdYearly[k][y] += sl.bird[k][p] * a / perYear; if (shown) birdMean[k] += sl.bird[k][p] * a / (show * perYear) }
            if (shown) {
                val f = a / (show * perYear)
                preyMean[0][cc] += sl.rodents[p] * f
                preyMean[1][cc] += sl.hares[p] * f
                preyMean[2][cc] += sl.hyrax[p] * f
                preyMean[3][cc] += sl.birds[p] * f
            }
        }
    }

    /** The map's songbirds and their ledger, insects, herps, this week. */
    private fun monthByMonth(mo: Int) {
        monthWeeksShown[mo]++
        var sb = 0.0; var ins = 0.0; var pol = 0.0; var hp = 0.0; val now = DoubleArray(MonthFlow.SIZE)
        for (p in 0 until n) {
            if (regions.classOf[p] < 0) continue; val a = land.areaKm2[p].toDouble()
            if (song >= 0) {
                val b = sl.birdLedger[song]
                sb += sl.bird[song][p] * a; now[MonthFlow.SONG_GROWN] += b[BirdLedger.GROWN][p] * a; now[MonthFlow.SONG_DIED] += b[BirdLedger.DIED][p] * a
                now[MonthFlow.SONG_FED_DAYS] += b[BirdLedger.FED_DAYS][p] * a; now[MonthFlow.SONG_INSECT_DAYS] += b[BirdLedger.INSECT_DAYS][p] * a
            }
            ins += sl.insects[p] * a; pol += sl.pollinators[p] * a; hp += sl.herps[p] * a
            val h = sl.herpLedger
            now[MonthFlow.INSECTS_TO_BIRDS] += h[HerpLedger.EATEN_BY_BIRDS][p] * a; now[MonthFlow.INSECTS_TO_HERPS] += h[HerpLedger.ATE][p] * a
            now[MonthFlow.HERPS_NEEDED] += h[HerpLedger.NEEDED][p] * a; now[MonthFlow.HERPS_HUNGER] += h[HerpLedger.HUNGER][p] * a
        }
        monthStock[mo][0] += sb; monthStock[mo][1] += ins; monthStock[mo][2] += pol; monthStock[mo][3] += hp
        for (i in 0 until MonthFlow.SIZE) { monthFlow[mo][i] += now[i] - flowsBefore[i]; flowsBefore[i] = now[i] }
    }

    private fun smallGame(y: Int, mi: Int) {
        for (p in 0 until n) {
            if (regions.classOf[p] < 0) continue; val a = land.areaKm2[p].toDouble() / perYear
            val v = doubleArrayOf(sl.rodents[p].toDouble(), sl.hares[p].toDouble(), sl.hyrax[p].toDouble(), sl.birds[p].toDouble(),
                sl.tortoises[p].toDouble(), sl.foxes[p].toDouble(), sl.owls[p].toDouble(),
                if (boar >= 0) (run.fauna.adults[boar][p] + run.fauna.young[boar][p]).toDouble() else 0.0)
            for (i in v.indices) smallYearly[i][y] += v[i] * a
            smallYearly[10][y] += sl.insects[p] * a
            smallYearly[11][y] += (sl.dormantInsects[p] + sl.dormantPollinators[p]) * a
            smallYearly[12][y] += sl.pollinators[p] * a
            if (mi >= 0) smallYearly[8 + mi][y] += sl.rodents[p] * land.areaKm2[p].toDouble()
        }
    }

    companion object {
        /** The rows of [smallYearly]. */
        val SMALL = listOf("rodents", "hares", "hyrax", "birds", "tortoises", "foxes", "owls", "boar/km2", "rod. Mar", "rod. Sep",
            "insects", "dormant ins.", "pollinators")
    }
}

/** The flows [HerpTally.monthFlow] keeps month by month: the songbirds' ledger, and insects eaten and wanted. */
object MonthFlow {
    const val SONG_GROWN = 0
    const val SONG_DIED = 1
    const val SONG_FED_DAYS = 2
    const val SONG_INSECT_DAYS = 3
    const val INSECTS_TO_BIRDS = 4
    const val INSECTS_TO_HERPS = 5
    const val HERPS_NEEDED = 6
    const val HERPS_HUNGER = 7
    const val SIZE = 8
}
