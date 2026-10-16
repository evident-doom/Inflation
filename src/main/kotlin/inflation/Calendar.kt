package inflation

/** Months from month [from] on to month [to] (both 0..11), 0..11: how far [to] lies ahead of [from] in the year. */
fun monthsFrom(from: Int, to: Int) = (to - from + 12) % 12

/** Month [m] wrapped into the year, 0..11: a month before January is December, one after December is January. */
fun wrapMonth(m: Int) = Math.floorMod(m, 12)
