package inflation.ecology

import java.util.concurrent.ConcurrentSkipListMap

/**
 * The census lines every band books each week (where its days went, by whether it herds, and how its chores were squeezed),
 * kept in plain arrays and merged into [WeekStats.hunting] once when the bands' weeks are done ([flush]): the same totals under
 * the same keys, without a string key built and a sorted-map merge for each of some forty lines a band-week.
 * (A line counted as 0 is still a line in the ledger, as with a merge of 0: [seen].) Only the bands' serial loop writes here.
 */
internal class BandBooks {
    private val activities = Activity.entries
    private val nA = activities.size
    private val herdings = Tally.Herding.entries
    private val squeezes = Tally.Squeeze.entries
    private val totals = Tally.TimeTotal.entries

    // Slots: [0, nA) time on each activity; then the three totals; then herding x (activities + lost); then squeeze x activities.
    private val timeAt = 0
    private val totalAt = timeAt + nA
    private val herdAt = totalAt + totals.size
    private val herdStride = nA + 1
    private val squeezeAt = herdAt + herdings.size * herdStride
    private val keys: Array<TallyKey> = Array(squeezeAt + nA * squeezes.size) { slot ->
        when {
            slot < totalAt -> Tally.bandTime(activities[slot - timeAt])
            slot < herdAt -> Tally.bandTime(totals[slot - totalAt])
            slot < squeezeAt -> {
                val h = herdings[(slot - herdAt) / herdStride]; val i = (slot - herdAt) % herdStride
                if (i < nA) Tally.bandsHerdingTime(h, activities[i]) else Tally.bandsHerdingTime(h, Tally.TimeTotal.LOST)
            }
            else -> { val i = slot - squeezeAt; Tally.squeeze(activities[i / squeezes.size], squeezes[i % squeezes.size]) }
        }
    }
    private val sums = IntArray(keys.size)
    private val seen = BooleanArray(keys.size)

    private fun add(slot: Int, v: Int) { sums[slot] += v; seen[slot] = true }

    fun time(a: Activity, tenths: Int) = add(timeAt + a.ordinal, tenths)
    fun total(t: Tally.TimeTotal, tenths: Int) = add(totalAt + t.ordinal, tenths)
    fun herdingTime(h: Tally.Herding, a: Activity, tenths: Int) = add(herdAt + h.ordinal * herdStride + a.ordinal, tenths)
    fun herdingLost(h: Tally.Herding, tenths: Int) = add(herdAt + h.ordinal * herdStride + nA, tenths)
    fun squeeze(a: Activity, k: Tally.Squeeze, v: Int) = add(squeezeAt + a.ordinal * squeezes.size + k.ordinal, v)

    /** Merges what was booked into [into] and starts afresh. */
    fun flush(into: ConcurrentSkipListMap<TallyKey, Int>) {
        for (slot in keys.indices) if (seen[slot]) { into.merge(keys[slot], sums[slot], Int::plus); sums[slot] = 0; seen[slot] = false }
    }
}
