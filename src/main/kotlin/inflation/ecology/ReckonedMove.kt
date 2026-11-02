package inflation.ecology

import inflation.world.Date
import kotlin.math.max
import kotlin.math.min

/**
 * The weeks of its camp a [Mind.PLANS] band judges the camp by ([campReturn]): its latest, as a patch's return falls while
 * it is worked. (A choice: no source sets it; about a month.)
 */
internal const val CAMP_RECENT_WEEKS = 4

/**
 * What band [b]'s camp has lately brought it (doc: Moving and settling under the planner, step 2, option A): the mean of
 * its record ([Band.weekSupply]) over the last weeks it has kept the camp, up to [CAMP_RECENT_WEEKS], before [date]'s
 * week, in steps of [days] days, each a share of its need. NaN if it has kept the camp no whole week yet.
 */
internal fun Band.campReturn(date: Date, days: Int): Float {
    val weeks = Date.stepsPerYear(days)
    if (weeks > WEEKS_KEPT) return Float.NaN
    val now = date.year * weeks + min(weeks - 1, date.dayOfYear / days)
    var sum = 0f; var n = 0
    for (j in 1..min(campWeeks, CAMP_RECENT_WEEKS)) {
        val got = weekSupply[ringIndex(now - j, weeks)]
        if (!got.isNaN()) { sum += got; n++ }
    }
    return if (n > 0) sum / n else Float.NaN
}

/**
 * What band [b] expects of a fresh camp at this time of year, by its own record (Charnov's average for the environment):
 * the mean of the same weeks [campReturn] reads, in the years before, wherever it was then. Its own seasons, so a lean
 * season everywhere is not a reason to leave; a mean of what came, not a pick among places, so it does not over-promise
 * as forecasts of the best place did (ab-camp, 2026-10-31). NaN if it has no such weeks yet.
 */
internal fun Band.usualReturn(date: Date, days: Int): Float {
    val weeks = Date.stepsPerYear(days)
    if (weeks > WEEKS_KEPT) return Float.NaN
    val now = date.year * weeks + min(weeks - 1, date.dayOfYear / days)
    var sum = 0f; var n = 0
    for (y in 1 until SUPPLY_YEARS) for (j in 1..min(campWeeks, CAMP_RECENT_WEEKS)) {
        val at = now - j - y * weeks
        if (at < 0) continue
        val got = weekSupply[ringIndex(at, weeks)]
        if (!got.isNaN()) { sum += got; n++ }
    }
    return if (n > 0) sum / n else Float.NaN
}

/** Where week [at] (counted from the first week of year 0) lies in a band's ring of weeks ([Band.weekSupply]). */
private fun ringIndex(at: Long, weeks: Int) = ((at / weeks) % SUPPLY_YEARS).toInt() * weeks + (at % weeks).toInt()

/**
 * What the walk of [km] to a fresh camp costs band [b] (group [g]), in shares of a week's need, as the mover's hand-written
 * score weighs it: the food the going burns, and the days it takes from the week's work beyond those it had to spare, paid
 * back over the weeks it will keep the new camp ([Band.stayWeeks]).
 */
internal fun GroupWorld.reckonedWalk(g: HexGroup, b: Band, km: Float): Float =
    WALK_COST * km / days + max(0f, moveDays(g, km) - b.spareDays / forageHands(g)) / (days * b.stayWeeks.coerceAtLeast(1f))
