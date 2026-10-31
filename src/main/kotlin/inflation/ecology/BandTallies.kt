package inflation.ecology

/**
 * The tallies every band books every week in the week's ledger ([WeekStats.hunting]: its days by chore, by whether it keeps
 * a herd, and its chores' squeeze), summed here through the bands' weeks, which go one after another, and put in the ledger
 * once ([flush]): a band's week made some eighty merges into a sorted map by text keys. A tally written to in the week has
 * its key in the ledger after the flush, with a sum of nought if need be, as if each had been merged on its own.
 */
internal class BandTallies {
    private val acts = Activity.entries.size
    private val herds = Tally.Herding.entries.size
    private val totals = Tally.TimeTotal.entries.size
    private val squeezes = Tally.Squeeze.entries.size
    private val totalAt = acts
    private val herdTimeAt = totalAt + totals
    private val herdTotalAt = herdTimeAt + herds * acts
    private val squeezeAt = herdTotalAt + herds * totals
    private val n = squeezeAt + acts * squeezes
    private val sum = IntArray(n)
    private val seen = BooleanArray(n)
    private val keys: Array<TallyKey> = buildList {
        for (a in Activity.entries) add(Tally.bandTime(a))
        for (t in Tally.TimeTotal.entries) add(Tally.bandTime(t))
        for (h in Tally.Herding.entries) for (a in Activity.entries) add(Tally.bandsHerdingTime(h, a))
        for (h in Tally.Herding.entries) for (t in Tally.TimeTotal.entries) add(Tally.bandsHerdingTime(h, t))
        for (a in Activity.entries) for (k in Tally.Squeeze.entries) add(Tally.squeeze(a, k))
    }.toTypedArray()

    private fun add(slot: Int, v: Int) { sum[slot] += v; seen[slot] = true }
    /** Tenths of a day spent on [a] (see [Tally.bandTime]). */
    fun time(a: Activity, v: Int) = add(a.ordinal, v)
    fun total(t: Tally.TimeTotal, v: Int) = add(totalAt + t.ordinal, v)
    fun herdTime(h: Tally.Herding, a: Activity, v: Int) = add(herdTimeAt + h.ordinal * acts + a.ordinal, v)
    fun herdTotal(h: Tally.Herding, t: Tally.TimeTotal, v: Int) = add(herdTotalAt + h.ordinal * totals + t.ordinal, v)
    fun squeeze(a: Activity, k: Tally.Squeeze, v: Int) = add(squeezeAt + a.ordinal * squeezes + k.ordinal, v)

    /** Puts the sums in [ledger] and starts again. */
    fun flush(ledger: MutableMap<TallyKey, Int>) {
        for (i in 0 until n) if (seen[i]) { ledger.merge(keys[i], sum[i], Int::plus); sum[i] = 0; seen[i] = false }
    }
}
