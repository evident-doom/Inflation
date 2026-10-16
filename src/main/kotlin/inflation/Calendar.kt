package inflation

/** Months from month [from] on to month [to] (both 0..11), 0..11: how far [to] lies ahead of [from] in the year. */
fun monthsFrom(from: Int, to: Int) = (to - from + 12) % 12
