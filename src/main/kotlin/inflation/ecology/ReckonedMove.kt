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

/**
 * What the walk of [km] to a fresh camp costs band [b] (group [g]), in shares of a week's need, as the mover's hand-written
 * score weighs it: the food the going burns, and the days it takes from the week's work beyond those it had to spare, paid
 * back over the weeks it will keep the new camp ([Band.stayWeeks]).
 */
internal fun GroupWorld.reckonedWalk(g: HexGroup, b: Band, km: Float): Float =
    WALK_COST * km / days + max(0f, moveDays(g, km) - b.spareDays / forageHands(g)) / (days * b.stayWeeks.coerceAtLeast(1f))
