package inflation.ecology

import inflation.HASH_A
import inflation.HASH_B
import inflation.mix64
import inflation.world.Date
import inflation.world.Weather
import kotlin.math.max
import kotlin.math.min

/** Years of weeks a band keeps for reckoning its stores ([Band.weekSupply], [storesAlive]). */
const val SUPPLY_YEARS = 3
/** The most weeks of a year a band's record holds ([Band.weekSupply]): steps of a week and longer. */
const val WEEKS_KEPT = 52
/** The years [storesAlive] draws, each a year ahead made month by month of the band's own past years' weeks. */
private const val DRAWN_YEARS = 8
private const val DRAW_SALT = 0x53544f52L

/**
 * Its week's food into band's record of weeks ([Band.weekSupply]) for the week of the year [date] is in, of [days] days:
 * [got], what it got as a share of its need before keeping warm, net of its larder, as [foresee] counts it (above
 * [warmth] laid by, below it lived off its larder or lost from it); [warmth], what keeping warm took, times that need;
 * [moved], whether it moved camp. A year's weeks go over those of [SUPPLY_YEARS] years before.
 */
internal fun Band.recordWeek(date: Date, days: Int, got: Float, warmth: Float, moved: Boolean) {
    val weeks = Date.stepsPerYear(days)
    if (weeks > WEEKS_KEPT) return
    val i = (date.year % SUPPLY_YEARS).toInt() * weeks + min(weeks - 1, date.dayOfYear / days)
    weekSupply[i] = got; weekWarmth[i] = warmth; weekMoved[i] = moved
}

/**
 * The share of band [b]'s (group [g]'s) people alive of hunger through the year ahead with [larderKg] in its larder, over
 * [DRAWN_YEARS] years each made month by month of the same month's weeks in one of the years it keeps ([Band.weekSupply],
 * drawn), week by week as the sim feeds it: a week above its need with keeping warm lays by, one below draws on the larder,
 * then fat, then the body ([Hazards.hunger]); a week it moved camp in, it keeps only what it carries ([foodCarried]).
 * So runs of lean weeks, the cold, its moves and what spoilt or was given away come as they came, not as one mean year
 * ([yearAhead]), whose deaths showed little of who died (calib461 to calib461i, 2026-10-30). The draws are the band's
 * week's, so two larders are reckoned over the same years. Null where it has not a whole year of weeks yet. Only hunger:
 * the stores change nothing else in it. [weather]: the week it reckons from.
 */
internal fun HexGroups.storesAlive(g: HexGroup, b: Band, weather: Weather, larderKg: Float): Float? {
    val weeks = Date.stepsPerYear(days)
    val date = weather.date
    // (In days of its people's need before keeping warm, as the record counts it.)
    val need = (g.dailyNeedKg / g.warmth.coerceAtLeast(1e-3f)).coerceAtLeast(1e-3f)
    return b.weeksAlive(g.species, days, min(weeks - 1, date.dayOfYear / days), larderKg / need, g.fat, g.spent,
        foodCarried(g, b) / need, g.id * HASH_A + date.absoluteDay * HASH_B + DRAW_SALT)
}

/**
 * [storesAlive] for people of kind [s] from week [now] of the year, steps of [days], with [larderDays] of the need before
 * keeping warm in the larder, [fat] days of fat, [spent] days of body protein burnt and [carryDays] carried on a move; the
 * years drawn by [seed].
 */
internal fun Band.weeksAlive(s: Species, days: Int, now: Int, larderDays: Float, fat: Float, spent: Float, carryDays: Float, seed: Long): Float? {
    val weeks = Date.stepsPerYear(days)
    if (weeks > WEEKS_KEPT) return null
    var sum = 0f
    for (n in 0 until DRAWN_YEARS) {
        var larder = larderDays; var fatLeft = fat; var burnt = spent; var alive = 1f
        var drawMonth = -1; var year = -1
        for (k in 1..weeks) {
            val w = (now + k) % weeks
            val month = w * Date.MONTHS_PER_YEAR / weeks
            if (month != drawMonth) { drawMonth = month; year = drawYear(this, weeks, w, mix64(seed + n * 31L + month) ushr 1) }
            // (A week the drawn year has not, another year's; a week no year has, no reckoning.)
            val y = if (year >= 0 && !weekSupply[year * weeks + w].isNaN()) year else drawYear(this, weeks, w, 0L)
            if (y < 0) return null
            val i = y * weeks + w
            if (weekMoved[i]) larder = min(larder, carryDays)
            val got = weekSupply[i]; val warm = weekWarmth[i].coerceAtLeast(1e-3f)
            val paid = if (got > warm) -(got - warm) * days else min((warm - got) * days, larder)
            larder -= paid
            val ate = min(1f, (max(0f, got) + max(0f, paid) / days) / warm)
            val onFat = Hazards.onFat(ate, fatLeft, days); fatLeft = max(0f, fatLeft - (onFat - ate) * days)
            alive *= 1f - Hazards.hunger(s, burnt, onFat, days); burnt = Hazards.spentAfter(s, burnt, onFat, days)
        }
        sum += alive
    }
    return sum / DRAWN_YEARS
}

/** One of the years band [b] has week [w] of, drawn by [h] (-1 for none). */
private fun drawYear(b: Band, weeks: Int, w: Int, h: Long): Int {
    var have = 0
    for (y in 0 until SUPPLY_YEARS) if (!b.weekSupply[y * weeks + w].isNaN()) have++
    if (have == 0) return -1
    var pick = (h % have).toInt()
    for (y in 0 until SUPPLY_YEARS) if (!b.weekSupply[y * weeks + w].isNaN()) { if (pick == 0) return y; pick-- }
    return -1
}
