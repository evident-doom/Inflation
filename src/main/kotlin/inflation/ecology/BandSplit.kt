package inflation.ecology

/**
 * One way the week's census splits bands (by country, clothing, way of living, mind, herd, food, spare days, warmth, hut):
 * the key of each [Tally.Bands] count of a split's value and the key of its deaths by cause. Counts only: nothing chooses by them.
 */
internal class BandSplit(private val counts: (Tally.Bands) -> TallyKey, private val deaths: ((DeathReason) -> TallyKey)? = null) {
    fun key(what: Tally.Bands) = counts(what)
    /** The key of deaths of [cause]; a split without one (herding, whose deaths go by age: [Tally.bandsHerdingDied]) has none. */
    fun diedOf(cause: DeathReason) = (deaths ?: error("no deaths by cause in this split"))(cause)

    companion object {
        fun country(c: inflation.world.Biome) = BandSplit({ Tally.bandsIn(c, it) }, { Tally.bandsDied(c, it) })
        fun clad(c: Tally.Clad) = BandSplit({ Tally.bandsClad(c, it) }, { Tally.bandsCladDied(c, it) })
        fun living(w: Tally.Living) = BandSplit({ Tally.bandsLiving(w, it) }, { Tally.bandsLivingDied(w, it) })
        fun mind(m: Mind) = BandSplit({ Tally.bandsMind(m, it) }, { Tally.bandsMindDied(m, it) })
        fun herding(h: Tally.Herding) = BandSplit({ Tally.bandsHerding(h, it) })
        fun fed(f: Tally.Fed) = BandSplit({ Tally.bandsFed(f, it) }, { Tally.bandsFedDied(f, it) })
        fun spare(s: Tally.Spare) = BandSplit({ Tally.bandsSpare(s, it) }, { Tally.bandsSpareDied(s, it) })
        fun warmed(s: Tally.Spare, w: Tally.Warmed) = BandSplit({ Tally.bandsWarmed(s, w, it) }, { Tally.bandsWarmedDied(s, w, it) })
        fun hut(h: Tally.Hut) = BandSplit({ Tally.bandsHut(h, it) }, { Tally.bandsHutDied(h, it) })
        fun herdKept(h: Tally.HerdKept) = BandSplit({ Tally.bandsHerdKept(h, it) }, { Tally.bandsHerdKeptDied(h, it) })
    }
}

/** Counts [n] more of [what] for bands in [split]. */
internal fun WeekStats.book(split: BandSplit, what: Tally.Bands, n: Int) { tally(split.key(what), n) }

/** Counts one band-week of [people] people for bands in [split]. */
internal fun WeekStats.bookWeek(split: BandSplit, people: Int) { book(split, Tally.Bands.BAND_WEEKS, 1); book(split, Tally.Bands.PEOPLE_WEEKS, people) }

/** Counts the week's deaths [causes] for bands in [split]. */
internal fun WeekStats.bookDeaths(split: BandSplit, causes: List<Died>) {
    for ((cause, k) in causes) if (k > 0) tally(split.diedOf(cause), k)
}
