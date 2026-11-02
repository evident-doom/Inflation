package inflation.ecology

import inflation.world.Date
import kotlin.math.max
import kotlin.math.min

/**
 * The weeks of its camp a [Mind.PLANS] band judges the camp by ([campWork]): its latest, as a patch's yield falls while
 * it is worked. (A choice: no source sets it; about a month.)
 */
internal const val CAMP_RECENT_WEEKS = 4

/**
 * The share of its hands' week band [b]'s camp has lately taken to feed it (doc: Moving and settling under the planner,
 * step 2, option A: the food a person-day's foraging brings): over the last weeks it has kept the camp, up to
 * [CAMP_RECENT_WEEKS], before [date]'s week, in steps of [days] days, the mean of each week's yield ([Band.weekYield]),
 * as the share of a week its foraging would take to bring its need, the whole week at most. Judged by the camp's yield,
 * not by what the band took in, which falls when it stops at enough and swings with gifts and its larder (a band with a
 * full larder left a good camp for it, hunger trace 2026-11-02). NaN if it has kept the camp no whole week yet.
 */
internal fun Band.campWork(date: Date, days: Int): Float {
    val weeks = Date.stepsPerYear(days)
    if (weeks > WEEKS_KEPT) return Float.NaN
    val now = date.year * weeks + min(weeks - 1, date.dayOfYear / days)
    var sum = 0f; var n = 0
    for (j in 1..min(campWeeks, CAMP_RECENT_WEEKS)) {
        val y = weekYield[ringIndex(now - j, weeks)]
        if (!y.isNaN()) { sum += y; n++ }
    }
    return if (n > 0) workFor(sum / n) else Float.NaN
}

/**
 * The share of its hands' week band [b] expects a fresh camp to take to feed it at this time of year, by its own record
 * (Charnov's average for the environment): as [campWork], of the mean yield of the same weeks in the years before, wherever
 * it was then. Its own seasons, so a lean season everywhere is not a reason to leave; a mean of what came, not a pick
 * among places, so it does not over-promise as forecasts of the best place did (ab-camp, 2026-10-31). NaN if it has no
 * such weeks yet.
 */
internal fun Band.usualWork(date: Date, days: Int): Float {
    val weeks = Date.stepsPerYear(days)
    if (weeks > WEEKS_KEPT) return Float.NaN
    val now = date.year * weeks + min(weeks - 1, date.dayOfYear / days)
    var sum = 0f; var n = 0
    for (y in 1 until SUPPLY_YEARS) for (j in 1..min(campWeeks, CAMP_RECENT_WEEKS)) {
        val at = now - j - y * weeks
        if (at < 0) continue
        val v = weekYield[ringIndex(at, weeks)]
        if (!v.isNaN()) { sum += v; n++ }
    }
    return if (n > 0) workFor(sum / n) else Float.NaN
}

/** The share of a week its foraging takes to bring its need at a yield of [yield] days of need a day: the whole week at most. */
private fun workFor(yield: Float) = if (yield > 1f) 1f / yield else 1f

/** Where week [at] (counted from the first week of year 0) lies in a band's ring of weeks ([Band.weekSupply]). */
private fun ringIndex(at: Long, weeks: Int) = ((at / weeks) % SUPPLY_YEARS).toInt() * weeks + (at % weeks).toInt()

/** The most loads a [Mind.PLANS] band's adults take on a move ([relayLoads]): as far as any band's ways go ([BandWay.CARRY_LOADS]). */
private const val LOADS_MOST = 2f

/**
 * The days a move of [km] with [loads] loads a walker costs band [b] (group [g]): its stores it leaves (what keeps longest
 * goes first, [storesCarried]), in days of its people's need, and the days its people walk, there and back again for each
 * load past the first ([relayWalks]), not at work.
 */
private fun GroupWorld.moveCostDays(g: HexGroup, b: Band, km: Float, loads: Float): Float {
    var room = max(0f, carryKg(g, b) * loads - mendingStockKg(g, b))
    var left = 0f
    for (st in CARRY_ORDER) {
        val kgPer = g.storeKgPer[st].coerceAtLeast(1e-6f)
        val x = min(g.stores[st], room / kgPer)
        left += g.stores[st] - x; room = max(0f, room - x * kgPer)
    }
    return left / g.dailyNeedKg.coerceAtLeast(1e-3f) + km * (1f + 2f * max(0f, loads - 1f)) / campKmADay(g)
}

/**
 * The loads band [b] (group [g]) has its adults take on a camp move of [km] ([Band.planLoads]): one, or as many as carry all
 * its stores ([LOADS_MOST] at most), whichever costs it fewer days ([moveCostDays]): a relay's days walked against the
 * stores it would leave, a day of work weighed as a day of need. A short move takes its larder along; a long one leaves what
 * it cannot carry. (Bands that leave a camp after a death left their stores with it, m5-1 hunger trace 2026-11-02.)
 */
internal fun GroupWorld.relayLoads(g: HexGroup, b: Band, km: Float): Float {
    val room = carryKg(g, b).coerceAtLeast(1e-3f)
    val weight = Store.entries.sumOf { (g.stores[it] * g.storeKgPer[it]).toDouble() }.toFloat() + mendingStockKg(g, b)
    val all = (weight / room).coerceIn(1f, LOADS_MOST)
    return if (moveCostDays(g, b, km, all) < moveCostDays(g, b, km, 1f)) all else 1f
}

/**
 * What a move of [km] to a fresh camp costs band [b] (group [g]), in shares of a week's need, with the loads it would take
 * ([relayLoads]): the food the going burns, the days its walks take from the week's work beyond those it had to spare, and
 * the stores it would leave, the last two paid back over the weeks it will keep the new camp ([Band.stayWeeks]).
 */
internal fun GroupWorld.reckonedWalk(g: HexGroup, b: Band, km: Float): Float {
    val loads = relayLoads(g, b, km)
    val walks = 1f + 2f * max(0f, loads - 1f)
    val walked = km * walks / campKmADay(g)
    val stay = days * b.stayWeeks.coerceAtLeast(1f)
    val left = moveCostDays(g, b, km, loads) - walked
    return WALK_COST * km * walks / days + max(0f, walked - b.spareDays / forageHands(g)) / stay + left / stay
}
