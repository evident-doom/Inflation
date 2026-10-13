package inflation.world

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

enum class Season { SPRING, SUMMER, AUTUMN, WINTER }

enum class MoonPhase { NEW, WAXING_CRESCENT, FIRST_QUARTER, WAXING_GIBBOUS, FULL, WANING_GIBBOUS, LAST_QUARTER, WANING_CRESCENT }

/**
 * World time. A year is 12 months of 30 days, day 0 of the year being the winter solstice, so the
 * months run roughly as ours do (month 1 is about January, month 7 about July); the months are the
 * simulation's bookkeeping, not anything its people can see. A run starts at the spring equinox of
 * year 0 ([START_DAY]), at the end of winter, so nothing starts out in the cold. The moon keeps its own
 * time, a synodic month of 29.53 days from a new moon on day 0, so its phases slide against the
 * seasons by about six days a year: a calendar of moons drifts, as real lunar calendars do.
 */
data class Date(val absoluteDay: Long) {
    val year: Long get() = absoluteDay / DAYS_PER_YEAR
    val dayOfYear: Int get() = (absoluteDay % DAYS_PER_YEAR).toInt()
    /** 0-based month, 0..11. */
    val month: Int get() = dayOfYear / DAYS_PER_MONTH
    /** 1-based day of month, 1..30. */
    val dayOfMonth: Int get() = dayOfYear % DAYS_PER_MONTH + 1

    val season: Season get() = Season.entries[(dayOfYear / 90 + 3) % 4]

    /** Fraction of the lunar cycle, 0 = new moon, 0.5 = full moon. */
    val moonAge: Double get() = (absoluteDay / SYNODIC_MONTH).let { it - kotlin.math.floor(it) }
    /** Lunations since day 0. */
    val lunation: Long get() = (absoluteDay / SYNODIC_MONTH).toLong()
    val moonPhase: MoonPhase get() = MoonPhase.entries[((moonAge * 8 + 0.5).toInt()) % 8]
    /** Illuminated fraction of the disc, 0..1. Matters for night hunting and predators. */
    val moonlight: Double get() = (1 - cos(2 * PI * moonAge)) / 2

    /** Mean [moonlight] over the [days] days from this date. */
    fun meanMoonlight(days: Int): Double = (0 until days).sumOf { plusDays(it).moonlight } / days

    /**
     * Tidal range against the mean, about 0.7 at neap tides (quarter moons) to 1.3 at spring tides
     * (new and full moon), when the sun and moon pull together.
     */
    val tideRange: Double get() = 1 + 0.3 * cos(4 * PI * moonAge)

    /** Solar declination in radians: 0 at the equinoxes (days 90 and 270), +23.44° at the summer solstice (day 180). */
    val solarDeclination: Double get() = 23.44 * PI / 180 * sin(2 * PI * (dayOfYear - START_DAY) / DAYS_PER_YEAR)

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
        /** The spring equinox, day of the year a run starts on. */
        const val START_DAY = 90
        /** Days from one new moon to the next. */
        const val SYNODIC_MONTH = 29.530589
    }
}
