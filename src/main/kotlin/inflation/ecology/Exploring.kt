package inflation.ecology

import inflation.HASH_A
import inflation.HASH_B
import inflation.HASH_C
import inflation.mix64
import inflation.unitOf
import kotlin.math.exp

/**
 * How a band's will to explore ([BandWay.EXPLORING], 0..2, 1 the scaffold's) loosens its choice of place: it goes where a draw
 * over its places sends it, each weighted `exp((score - best) / (will × unit))`. The unit is that of the scores: for a
 * network's, 1, the temperature it learns at (its recorded choices are drawn by the softmax of its scores), so at the scaffold's
 * 1 a band chooses as its network does while learning; for the hand-written score, this, the temperature the networks imitate
 * it at (`HexPpo.IMITATE_SHARPNESS`, 10). At 0 it takes the best.
 */
const val HAND_SCORE_UNIT = 0.1f
/**
 * How strongly a band's will to explore draws it away from where it is: the place it is at scores this many units lower for
 * each step of the will above the scaffold's 1 (below 1, higher by as much). (A choice of the band's: no source sets it.)
 */
const val EXPLORE_STAY = 1f

/** The salt of the draw a band explores by (see [exploreChoice]), apart from the other draws on the group and day. */
private const val EXPLORE_SALT = 0x5EED_E4B1L

/**
 * The candidate a band of group [g] goes to, of the [n] places weighed this week, by its will to explore [will] (see
 * [HAND_SCORE_UNIT]): [scores] how it scores each (the network's or the hand-written), in [unit]s, [deaths] the survival steps
 * each would cost it ([survivalSteps]), [stays] the candidate where it is now (-1 if none). Above all it keeps alive: a place
 * more than one survival step deadlier than the safest is never drawn, however keen it is. Returns -1 if no place can be weighed.
 */
internal fun HexGroups.exploreChoice(g: HexGroup, will: Float, n: Int, scores: FloatArray, deaths: FloatArray, stays: Int, unit: Float, day: Long): Int {
    val u = unitOf(mix64(g.id * HASH_A + day * HASH_B + EXPLORE_SALT * HASH_C))
    val taken = exploreDraw(will, n, scores, deaths, stays, unit, u)
    dialUse?.choice(DialSite.EXPLORE_MOVE, will, taken != exploreDraw(1f, n, scores, deaths, stays, unit, u), false)
    return taken
}

/** [exploreChoice]'s draw at [will] for the draw [u]. */
private fun exploreDraw(will: Float, n: Int, scores: FloatArray, deaths: FloatArray, stays: Int, unit: Float, u: Float): Int {
    var safest = Float.POSITIVE_INFINITY
    for (k in 0 until n) if (scores[k].isFinite() && deaths[k].isFinite() && deaths[k] < safest) safest = deaths[k]
    if (!safest.isFinite()) return -1
    fun open(k: Int) = scores[k].isFinite() && deaths[k].isFinite() && deaths[k] - safest <= 1f
    fun score(k: Int) = scores[k] - (if (k == stays) EXPLORE_STAY * (will - 1f) * unit else 0f)
    var best = -1
    for (k in 0 until n) if (open(k) && (best < 0 || score(k) > score(best))) best = k
    val t = will * unit
    if (t <= 1e-9f) return best
    var sum = 0.0
    for (k in 0 until n) if (open(k)) sum += exp(((score(k) - score(best)) / t).toDouble())
    var left = u * sum
    for (k in 0 until n) if (open(k)) { left -= exp(((score(k) - score(best)) / t).toDouble()); if (left <= 0.0) return k }
    return best
}
