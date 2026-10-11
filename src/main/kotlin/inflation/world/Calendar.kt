package inflation.world

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

enum class Season { SPRING, SUMMER, AUTUMN, WINTER }

enum class MoonPhase { NEW, WAXING_CRESCENT, FIRST_QUARTER, WAXING_GIBBOUS, FULL, WANING_GIBBOUS, LAST_QUARTER, WANING_CRESCENT }

/**
 * World time. A year is 12 months of 30 days. The moon's cycle is fixed at exactly one month,
 * new moon on day 1 (the real synodic month is 29.53 days; this world rounds it so months and
 * moons stay aligned). Day 0 of the year is the spring equinox.
 */
data class Date(val absoluteDay: Long) {
    val year: Long get() = absoluteDay / DAYS_PER_YEAR
    val dayOfYear: Int get() = (absoluteDay % DAYS_PER_YEAR).toInt()
    /** 0-based month, 0..11. */
    val month: Int get() = dayOfYear / DAYS_PER_MONTH
    /** 1-based day of month, 1..30. */
    val dayOfMonth: Int get() = dayOfYear % DAYS_PER_MONTH + 1

    val season: Season get() = Season.entries[dayOfYear / 90]

    /** Fraction of the lunar cycle, 0 = new moon, 0.5 = full moon. */
    val moonAge: Double get() = (dayOfMonth - 1).toDouble() / DAYS_PER_MONTH
    val moonPhase: MoonPhase get() = MoonPhase.entries[((moonAge * 8 + 0.5).toInt()) % 8]
    /** Illuminated fraction of the disc, 0..1. Matters for night hunting and predators. */
    val moonlight: Double get() = (1 - cos(2 * PI * moonAge)) / 2

    /** Solar declination in radians: 0 at the equinoxes, +23.44° at the summer solstice (day 90). */
    val solarDeclination: Double get() = 23.44 * PI / 180 * sin(2 * PI * dayOfYear / DAYS_PER_YEAR)

    /** Hours of daylight at [latitudeDeg]. */
    fun dayLengthHours(latitudeDeg: Double): Double {
        val phi = latitudeDeg * PI / 180
        val x = (-tan(phi) * tan(solarDeclination)).coerceIn(-1.0, 1.0)
        return 24 / PI * acos(x)
    }

    fun plusDays(days: Int) = Date(absoluteDay + days)

    override fun toString() = "Year $year, month ${month + 1}, day $dayOfMonth"

    companion object {
        const val DAYS_PER_MONTH = 30
        const val MONTHS_PER_YEAR = 12
        const val DAYS_PER_YEAR = DAYS_PER_MONTH * MONTHS_PER_YEAR
    }
}
