package inflation.ecology

import inflation.Config
import inflation.Trace
import inflation.world.Date
import inflation.world.Weather
import kotlin.math.roundToInt

/**
 * forecastTrace=true: a sample of bands (1 in 20, the first [FORECAST_TRACE_MOST]) followed for a year from the first week they
 * have a year of months behind them ([Band.monthSupply]): what their forecast foresaw month by month ([yearAhead]), and what came, the food they got as [Band.monthSupply]
 * counts it, their larder, fat and body protein burnt, their people, deaths and moves; on stderr, once the year is over or
 * the band is gone. Whether the forecast's months are what came, where [bookCalibration] shows only that its deaths are not.
 */
internal val FORECAST_TRACE = Trace(Config.Key.FORECAST_TRACE, "forecast", share = 0.05f, most = FORECAST_TRACE_MOST)
private const val FORECAST_TRACE_MOST = 600

/** Starts following band [b] (group [g]) for [ForecastTrace] where it is drawn for it, once a band. */
internal fun HexGroups.traceForecast(g: HexGroup, b: Band, weather: Weather) {
    // (Once it has a year of months as [Band.monthSupply] now counts them: a band from an older save has none yet.)
    if (b.forecastTraced || b.monthSupplySq.any { it.isNaN() }) return
    b.forecastTraced = true
    if (!FORECAST_TRACE.wants(g.id.toLong())) return
    val year = yearAhead(g, b, weather)
    val need = g.dailyNeedKg.coerceAtLeast(1e-3f)
    val m0 = weather.date.month
    val head = "forecast trace: band %d, %s, %s, %d people, larder %.1f days, fat %.1f, burnt %.1f, foreseen alive %.4f\n".format(
        g.id, weather.date, b.mind, g.size, g.larder / need, g.fat, g.spent, g.foreseen) +
        " foreseen, a usual year:\n" + year.trace(g.species, g.larder / need, g.fat, g.spent)
    val supply = FloatArray(Date.MONTHS_PER_YEAR) { b.monthSupply[(m0 + 1 + it) % Date.MONTHS_PER_YEAR] }
    val spread = FloatArray(Date.MONTHS_PER_YEAR) { b.supplySpread((m0 + 1 + it) % Date.MONTHS_PER_YEAR) }
    b.forecastTrace = ForecastTrace(head, m0, supply, spread, g.size, b.larderSeen)
}

/**
 * One band's year as [traceForecast] follows it: from the month after [startMonth], each month's food got against what it
 * foresaw ([supply] and its [spread], as [Band.monthSupply] counts it), and how it fared. [head]: what it foresaw.
 */
internal class ForecastTrace(private val head: String, private val startMonth: Int, private val supply: FloatArray,
    private val spread: FloatArray, private val startPeople: Int, startLarder: Float) {
    private val n = Date.MONTHS_PER_YEAR
    private val got = FloatArray(n); private val weeks = IntArray(n); private val moves = IntArray(n)
    private val larder = FloatArray(n); private val fat = FloatArray(n); private val burnt = FloatArray(n)
    private val people = IntArray(n); private val died = IntArray(n); private val starved = IntArray(n)
    private val movedIn = Array(LarderMove.entries.size) { FloatArray(n) }
    /** Its larder at each month's start (days of its people's need before keeping warm), and as last seen. */
    private val larderStart = FloatArray(n); private var larderNow = startLarder
    private var month = startMonth
    /** The month of the year ahead it is in (0 for the first; -1 still in the month it started). */
    private var slot = -1

    /** A week in month [m]: the food it got ([got], as [Band.addSupply] has it), its larder (days), fat, burnt, people and whether it moved. */
    fun week(m: Int, got: Float, larder: Float, fat: Float, burnt: Float, people: Int, moved: Boolean) {
        if (m != month) { month = m; slot++ }
        if (slot < 0 || slot >= n) return
        if (weeks[slot] == 0) { this.people[slot] = people; larderStart[slot] = larderNow }
        larderNow = larder
        this.got[slot] += got; weeks[slot]++; if (moved) moves[slot]++
        this.larder[slot] = larder; this.fat[slot] = fat; this.burnt[slot] = burnt
    }

    /** [k] of its people died this week, [hunger] whether of hunger. */
    fun died(k: Int, hunger: Boolean) {
        if (slot < 0 || slot >= n) return
        died[slot] += k; if (hunger) starved[slot] += k
    }

    /** [value] of group [g]'s food moved in (below 0, out) for [why] this week: days of its people's need before keeping warm. */
    fun moved(g: HexGroup, why: LarderMove, value: Float) {
        if (slot < 0 || slot >= n) return
        movedIn[why.ordinal][slot] += value / (g.dailyNeedKg / g.warmth.coerceAtLeast(1e-3f)).coerceAtLeast(1e-3f)
    }

    /** Whether its year is over. */
    val over get() = slot >= n

    /** What it foresaw and what came, [end] how the following ended. */
    fun report(end: String): String {
        val sb = StringBuilder(head)
        sb.append(" what came (%s; got against foreseen, of the need before keeping warm; larder, fat and burnt at the month's end):\n".format(end))
        for (k in 0 until n) {
            if (weeks[k] == 0) break
            sb.append("  +%d: got %.2f foreseen %.2f±%.2f larder %.1f fat %.1f burnt %.1f people %d died %d hunger %d moved %d of %d weeks; larder days moved: %s other %.1f\n".format(
                k + 1, got[k] / weeks[k], supply[k], spread[k], larder[k], fat[k], burnt[k], people[k], died[k], starved[k], moves[k], weeks[k],
                LarderMove.entries.joinToString(" ") { "%s %.1f".format(it.label, movedIn[it.ordinal][k]) },
                larder[k] - larderStart[k] - LarderMove.entries.sumOf { movedIn[it.ordinal][k].toDouble() }.toFloat()))
        }
        sb.append("  people at the start %d; month %d\n".format(startPeople, startMonth))
        return sb.toString()
    }
}

/** Weeks before and after a camp move the census follows a band ([moveCensus]). */
const val MOVE_WEEKS = 4
/** The weeks over which the census counts a band's camp moves as lately ([Band.movesLately]): about a season. */
const val MOVES_LATELY_WEEKS = 13f
/** Fed below this share of the need on the mean over the weeks before a move, a band was already short of food when it moved. */
const val HUNGRY_BEFORE = 0.9f

/**
 * For the census: band [b]'s (group [g]'s) week around its camp moves, the share of the need it ate and its [larderDays], in
 * the [MOVE_WEEKS] weeks before a move ([moved] this week) and after it: whether bands starve for moving, or move for
 * starving. Counts only.
 */
internal fun HexGroups.moveCensus(g: HexGroup, b: Band, moved: Boolean, larderDays: Float, season: inflation.world.Season) {
    // (And by how often it has moved camp lately, season by season: its larder and fat going into the lean months, and the
    // deaths after, [Band.leanKey].)
    b.movesLately += (if (moved) 1f else 0f) - b.movesLately / MOVES_LATELY_WEEKS
    val lately = Tally.MovedLately.of(b.movesLately)
    stats.tally(Tally.movedLately(lately, season, Tally.Lately.PEOPLE_WEEKS), g.size)
    stats.tally(Tally.movedLately(lately, season, Tally.Lately.LARDER), (10f * larderDays * g.size).roundToInt())
    stats.tally(Tally.movedLately(lately, season, Tally.Lately.FAT), (10f * g.fat * g.size).roundToInt())
    stats.tally(Tally.movedLately(lately, season, Tally.Lately.STORE_AIM), (10f * b.storeAim * g.size).roundToInt())
    b.leanKey = lately.ordinal * inflation.world.Season.entries.size + season.ordinal
    fun book(offset: Int, fed: Float, larder: Float) {
        stats.tally(Tally.aroundMove(b.mind, offset, Tally.AroundMove.WEEKS), 1)
        stats.tally(Tally.aroundMove(b.mind, offset, Tally.AroundMove.FED), (1000f * fed).roundToInt())
        stats.tally(Tally.aroundMove(b.mind, offset, Tally.AroundMove.LARDER), (10f * larder).roundToInt())
    }
    // (Hungry bands, moving or not: a band fed below [HUNGRY_BEFORE] on the mean over the weeks before this one enters the
    // cohort of those that moved camp this week or of those that stayed, by its larder then, and is followed for [MOVE_WEEKS]
    // weeks after, whatever it does then: whether a move feeds a hungry band or starves it, against hungry bands that stayed.)
    if (b.hungryCohort < 0) {
        var sum = 0f; var n = 0
        for (fed in b.recentFed) if (!fed.isNaN()) { sum += fed; n++ }
        if (n == MOVE_WEEKS && sum / n < HUNGRY_BEFORE) {
            val c = Tally.HungryCohort.of(moved, b.recentLarder[Math.floorMod(b.recentAt - 1, MOVE_WEEKS)])
            b.hungryCohort = c.ordinal; b.hungryWeek = 0
            stats.tally(Tally.hungryCohort(c, Tally.HungryFollowed.ENTERED), 1)
        }
    }
    if (b.hungryCohort >= 0) {
        val c = Tally.HungryCohort.entries[b.hungryCohort]
        if (b.hungryWeek > 0) {
            stats.tally(Tally.hungryCohort(c, Tally.HungryFollowed.PEOPLE_WEEKS), g.size)
            stats.tally(Tally.hungryCohort(c, Tally.HungryFollowed.FED), (1000f * g.fed * g.size).roundToInt())
        }
        if (++b.hungryWeek > MOVE_WEEKS) b.hungryCohort = -1
    }
    if (moved) {
        var sum = 0f; var n = 0
        for (j in 1..MOVE_WEEKS) {
            val i = Math.floorMod(b.recentAt - j, MOVE_WEEKS)
            val fed = b.recentFed[i]
            if (fed.isNaN()) continue
            book(-j, fed, b.recentLarder[i]); sum += fed; n++
        }
        b.fedBeforeMove = if (n > 0) sum / n else Float.NaN
        b.sinceMove = 0
    } else if (b.sinceMove >= 0) b.sinceMove++
    if (b.sinceMove in 0..MOVE_WEEKS) book(b.sinceMove, g.fed, larderDays)
    b.recentFed[b.recentAt] = g.fed; b.recentLarder[b.recentAt] = larderDays
    b.recentAt = (b.recentAt + 1) % MOVE_WEEKS
}

/** [kg] of group [g]'s food moved into its larder (below 0, out) for [why]: for the census ([Band.larderFlow]) and the trace. */
internal fun Band.larderMoved(g: HexGroup, why: LarderMove, kg: Float) {
    larderFlow[why.ordinal] += kg
    forecastTrace?.moved(g, why, kg)
}

/**
 * What moved food into or out of a band's larder, for [ForecastTrace]: raided, given to visiting kin, given at a meeting, eaten
 * at a feast (between bands); eaten from it, the week's food kept in it, spoilt or got at by mice, cached or left at the old
 * camp on a move (its own).
 */
enum class LarderMove(val label: String) {
    RAIDED("raided"), KIN("kin"), GIFT("gift"), FEAST("feast"), ATE("ate"), KEPT("kept"), SPOILT("spoilt"), LEFT("left")
}

/** What moved a store of each kind ([Band.storeFlow]): the week's food kept, fresh meat and fish dried (out of fresh, into dried), eaten, spoilt. */
enum class StoreFlow(val label: String) { KEPT("kept"), DRIED("dried"), ATE("ate"), SPOILT("spoilt") }

/** Books in [Band.storeFlow] how group [g]'s stores moved for [why] since [before] (a copy of them then). */
internal fun Band.storeMoved(g: HexGroup, why: StoreFlow, before: FloatArray) {
    for (st in Store.entries) storeFlow[st.ordinal * StoreFlow.entries.size + why.ordinal] += g.stores[st] - before[st.ordinal]
}

/** A copy of group [g]'s stores, for [storeMoved]. */
internal fun storesNow(g: HexGroup) = FloatArray(Store.entries.size) { g.stores[Store.entries[it]] }
