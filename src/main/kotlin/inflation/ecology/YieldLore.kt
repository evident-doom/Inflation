package inflation.ecology

import inflation.ecology.HexFeatures.LORE_MONTHS
import inflation.world.Date
import inflation.world.Place
import kotlin.math.min

// The lore of the land's months ([HexGroups.yieldLore]): what bands found a place to yield in each month of the year, kept by
// the place, so that the people of a country know its lean months, and a band new to it learns them as hearsay. For the
// networks to see where and when the food runs short before it does (doom trace, 2026-11-06: bands lay by as much in spring
// as in autumn, and those that starve see nothing of the lean months coming).

/** The share of the gap one band-week's yield closes in [HexGroups.yieldLore]: about the last ten band-weeks of a month. */
internal const val YIELD_LORE_RATE = 0.1f

/** The yield bands usually find at [p] in [month] ([HexGroups.yieldLore]), NaN where none remembers. */
internal fun HexGroups.usualYield(p: Place, month: Int): Float = yieldLore[p.i * Date.MONTHS_PER_YEAR + month]

/** Yield [y] a band found at [p] in [month], into the lore. */
internal fun HexGroups.learnYield(p: Place, month: Int, y: Float) {
    if (y.isNaN()) return
    val i = p.i * Date.MONTHS_PER_YEAR + month
    val was = yieldLore[i]
    yieldLore[i] = if (was.isNaN()) y else was + YIELD_LORE_RATE * (y - was)
}

/**
 * The lore of [p] for the [LORE_MONTHS] months after [month] into [into] from [at] (each x / (1 + x), 0 where unknown), and
 * at [knownAt] 1 if any is known.
 */
internal fun HexGroups.yieldLoreInto(p: Place, month: Int, into: FloatArray, at: Int, knownAt: Int) {
    var known = false
    for (k in 0 until LORE_MONTHS) {
        val v = usualYield(p, (month + k + 1) % Date.MONTHS_PER_YEAR)
        if (v.isNaN()) into[at + k] = 0f else { into[at + k] = v / (1f + v); known = true }
    }
    into[knownAt] = if (known) 1f else 0f
}

/** Band's yield in [date]'s week as [recordWeek] put it in its record ([Band.weekYield]), NaN if none. */
internal fun Band.yieldOfWeek(date: Date, days: Int): Float {
    val weeks = Date.stepsPerYear(days)
    if (weeks > WEEKS_KEPT) return Float.NaN
    return weekYield[(date.year % SUPPLY_YEARS).toInt() * weeks + min(weeks - 1, date.dayOfYear / days)]
}
