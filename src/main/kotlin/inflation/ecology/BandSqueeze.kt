package inflation.ecology

import kotlin.math.max
import kotlin.math.roundToInt

/** Weeks the census's running mean of a band's spare days reaches back, about a season ([Band.spareMean]). The report's, not the model's. */
const val SPARE_WEEKS = 13f
/** A chore that got less than this share of its ask in a week that ran out of days was squeezed ([countSqueeze]). The report's line. */
const val SQUEEZED = 0.9f
/** A week whose spare days came to less than this share of its hands' days ran out of days ([countSqueeze]). The report's line. */
const val WEEK_FULL = 0.05f

/**
 * Counts for the census how [b]'s chores fared this week against what each asked of its plan ([time], all its chores done,
 * its leisure not yet taken): the days each asked and did not get, and whether it got less than [SQUEEZED] of its ask in a
 * week that ran out of days, so that the work before it took its days. Also keeps the running mean of its spare days the
 * census groups bands by ([bookSqueeze]), and how its clothes making went ([makeTools]). Counts only: the week is as it was.
 * (Food's ask includes the stores it aims at; drying's and skins' are last week's; herding's is sowing's.)
 */
internal fun HexGroups.countSqueeze(b: Band, time: BandTime) {
    val total = time.total
    val spare = if (total > 0f) max(0f, time.left) / total else 0f
    b.spareMean = if (b.spareSeen) b.spareMean + (spare - b.spareMean) / SPARE_WEEKS else spare
    b.spareSeen = true
    val full = spare < WEEK_FULL
    for (a in Activity.entries) {
        if (a == Activity.LEISURE || a == Activity.MOVING) continue
        val ask = time.asked[a]
        if (ask <= 0f) continue
        val missing = max(0f, ask - time.workedOn(a))
        fun add(k: Tally.Squeeze, v: Int) { stats.hunting.merge(Tally.squeeze(a, k), v, Int::plus) }
        add(Tally.Squeeze.ASKED_WEEKS, 1)
        add(Tally.Squeeze.ASKED_DAYS, (10f * ask).roundToInt())
        add(Tally.Squeeze.MISSING_DAYS, (10f * missing).roundToInt())
        if (full && missing > (1f - SQUEEZED) * ask) add(Tally.Squeeze.SHORT_WEEKS, 1)
    }
    b.clothesMade?.let { stats.hunting.merge(Tally.clothesMade(it), 1, Int::plus) }
}

/**
 * Books [g]'s band [b]'s week, and its deaths ([causes]), by its spare days over the last season ([Band.spareMean]) and by
 * whether it keeps a herd, with its clothes: whether bands whose weeks are full die of what spare days would have kept off.
 */
internal fun HexGroups.bookSqueeze(g: HexGroup, b: Band, causes: List<Died>) {
    fun add(k: TallyKey, v: Int) { stats.hunting.merge(k, v, Int::plus) }
    val clo = (10f * b.clothesClo()).roundToInt()
    val spare = Tally.Spare.of(b.spareMean)
    add(Tally.bandsSpare(spare, Tally.Bands.BAND_WEEKS), 1)
    add(Tally.bandsSpare(spare, Tally.Bands.PEOPLE_WEEKS), g.size)
    add(Tally.bandsSpare(spare, Tally.Bands.CLOTHES), clo)
    for ((cause, k) in causes) if (k > 0) add(Tally.bandsSpareDied(spare, cause), k)
    val h = if (b.herd.any { it > 0f }) Tally.Herding.HERDING else Tally.Herding.NONE
    add(Tally.bandsHerding(h, Tally.Bands.BAND_WEEKS), 1)
    add(Tally.bandsHerding(h, Tally.Bands.PEOPLE_WEEKS), g.size)
    add(Tally.bandsHerding(h, Tally.Bands.CLOTHES), clo)
    add(Tally.bandsHerding(h, Tally.Bands.SPARE), (1000f * b.spareMean).roundToInt())
    for ((cause, k) in causes) if (k > 0) add(Tally.bandsHerdingDied(h, cause), k)
}
