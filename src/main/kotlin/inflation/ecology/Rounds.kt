package inflation.ecology

/**
 * The round group [g] keeps to in [month], if any: the place it makes for and how strongly (0..). A band's is its rite,
 * a round its culture holds to (see [Band.riteFor]); packs keep none. The pull is added to the hand-written score and,
 * outside the network, to a learned mover's (see the "on round" feature and SharedMover.pull).
 */
internal fun roundOf(g: HexGroup, month: Int): RoundPull? = if (ROUNDS) g.band?.riteFor(month) else null

/** The place a round draws a group to this month, and how strongly (see [roundOf]). */
data class RoundPull(val place: Int, val strength: Float)

/** The size of a range, km: the country a place is judged by at its widest ([HexGeography]). */
const val RANGE_KM = 100.0
/** The ground one place draws on: half a range across. */
const val GATHER_KM = RANGE_KM / 2
