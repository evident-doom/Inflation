package inflation.ecology

/**
 * The years a band's people have ahead, against a grown one in the childbearing years, by which training weighs a band's
 * members (Olli 2026-11-05: people weigh the years to come; in want the old suffer and the young are kept alive). A child
 * (0 to 15, about 7 on average) has some 43 years ahead and a grown one (about 30) some 30, so a child counts [YOUNG];
 * an elder (past bearing, about 55) has some 15, so counts [ELDER]. Rounded from the life tables of living foragers
 * (Gurven & Kaplan 2007, Population and Development Review 33: life expectancy at 15 about 39 more years, at 45 about 20):
 * estimates, not fitted. Animals count every head alike.
 */
object FutureYears {
    const val YOUNG = 1.4f
    const val ELDER = 0.5f

    /** The years ahead of [adults] grown ones, [elders] of them elders, and [young] young, in grown ones' years. */
    fun of(adults: Int, elders: Int, young: Int): Float = (adults - elders) + ELDER * elders + YOUNG * young

    /** Group [g]'s years ahead: a band's by its ages, an animal group's its heads. */
    fun of(g: HexGroup): Float = if (g.species.human) of(g.adults, g.band?.elders ?: 0, g.young) else g.size.toFloat()
}
