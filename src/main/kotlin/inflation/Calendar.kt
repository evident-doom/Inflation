package inflation

/** The model's calendar: 30 days to a month, 12 months to a year ([inflation.world.Date] reckons days by it). */
const val DAYS_PER_MONTH = 30
const val MONTHS_PER_YEAR = 12
const val DAYS_PER_YEAR = DAYS_PER_MONTH * MONTHS_PER_YEAR
/** The spring equinox, the day of the year a run starts on. */
const val START_DAY = 90

/** Months from month [from] on to month [to] (both 0..11), 0..11: how far [to] lies ahead of [from] in the year. */
fun monthsFrom(from: Int, to: Int) = (to - from + 12) % 12

/** Month [m] wrapped into the year, 0..11: a month before January is December, one after December is January. */
fun wrapMonth(m: Int) = Math.floorMod(m, 12)
