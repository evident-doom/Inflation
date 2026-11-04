package inflation.ecology

import inflation.drawUnit
import inflation.Stream
import kotlin.math.exp

/**
 * How a band's will to explore ([BandWay.EXPLORING], 0..2, 1 the scaffold's) loosens its choice of place: it goes where a draw
 * over its places sends it, each weighted `exp((score - best) / (will × unit))`. The unit is that of the scores: for a
 * network's, 1, the temperature it learns at (its recorded choices are drawn by the softmax of its scores), so at the scaffold's
 * 1 a band chooses as its network does while learning; for the hand-written score, this, the temperature the networks imitate
 * it at (`HexPpo.IMITATE_SHARPNESS`, 10). At 0 it takes the best.
 */
internal const val HAND_SCORE_UNIT = 0.1f
/**
 * How strongly a band's will to explore draws it away from where it is: the place it is at scores this many units lower for
 * each step of the will above the scaffold's 1 (below 1, higher by as much). (A choice of the band's: no source sets it.)
 */
internal const val EXPLORE_STAY = 1f

/**
 * The candidate a band of group [g] goes to, of the [n] places weighed this week, by its will to explore [will] (see
 * [HAND_SCORE_UNIT]): [scores] how it scores each (the network's or the hand-written), in [unit]s, [deaths] the survival steps
 * each would cost it ([survivalSteps]), [stays] the candidate where it is now (-1 if none). Above all it keeps alive: a place
 * more than one survival step deadlier than the safest is never drawn, however keen it is. Returns -1 if no place can be weighed.
 */
internal fun HexGroups.exploreChoice(g: HexGroup, will: Float, n: Int, scores: FloatArray, deaths: FloatArray, stays: Int, unit: Float, day: Long): Int {
    val u = drawUnit(seedBase, Stream.EXPLORE, g.id.toLong(), day)
    val taken = exploreDraw(will, n, scores, deaths, stays, unit, u)
    dialUse?.choice(DialSite.EXPLORE_MOVE, will, taken != exploreDraw(1f, n, scores, deaths, stays, unit, u), false)
    return taken
}

/**
 * Where a [Mind.PLANS] band goes this week, of the [n] places weighed (as [exploreChoice]: [deaths] in survival steps,
 * [stays] the one where it is; [weighable] the hand-written scores, a place weighable where its is a number): it draws
 * nothing, and its temperament draws it nowhere. Moving camp is a choice (doc: Moving and settling under the planner):
 * - where its customs send it ([owed]: an appointment due, a raid set, a camp left after a death), or where staying is at
 *   least a survival step deadlier than the safest place, the emergency a band flees by reflex (a cold snap without
 *   shelter, water dried up, beasts or raiders, fire, flood), it goes to the best by [scores] of those not deadlier than
 *   the safest by a step (step 1);
 * - else it reckons the move (step 2): it goes to the best of the places no deadlier than staying, but where it is, if the
 *   case for leaving [gain] (in shares of a week's need, NaN for none) is more than the walk there costs ([walk] of the
 *   place's index). A move by choice never risks more lives than staying: survival keeps first claim.
 * Else it stays. [scores] are asked only once it may leave (null: it stays). Returns -1 if no place can be weighed. With [why],
 * writes there why it went or stayed ([Reckoned], for planTrace).
 */
internal fun plannedMove(n: Int, weighable: FloatArray, deaths: FloatArray, stays: Int, owed: Boolean, gain: Float, walk: (Int) -> Float,
    scores: () -> FloatArray?, why: Array<Reckoned?>? = null): Int {
    var safest = Float.POSITIVE_INFINITY
    for (k in 0 until n) if (weighable[k].isFinite() && deaths[k].isFinite() && deaths[k] < safest) safest = deaths[k]
    if (!safest.isFinite()) return -1
    fun open(k: Int) = weighable[k].isFinite() && deaths[k].isFinite() && deaths[k] - safest <= 1f
    val settled = !owed && stays >= 0 && deaths[stays].isFinite() && deaths[stays] - safest < 1f
    if (settled && !(gain > 0f)) { why?.set(0, if (gain.isNaN()) Reckoned.NO_CASE else Reckoned.COSTS_MORE); return stays }
    val sc = scores() ?: run { why?.set(0, Reckoned.UNSCORED); return stays }
    var best = -1
    for (k in 0 until n) if (open(k) && sc[k].isFinite() && (!settled || (k != stays && deaths[k] <= deaths[stays])) && (best < 0 || sc[k] > sc[best])) best = k
    if (settled) {
        val goes = best >= 0 && gain > walk(best)
        if (why != null) why[0] = when {
            best >= 0 -> if (goes) Reckoned.MOVED else Reckoned.WALK_COSTS_MORE
            (0 until n).any { open(it) && sc[it].isFinite() && it != stays } -> Reckoned.ONLY_DEADLIER
            else -> Reckoned.NOWHERE
        }
        return if (goes) best else stays
    }
    why?.set(0, if (owed) Reckoned.OWED else Reckoned.EMERGENCY)
    return best
}

/**
 * Why a planning band went or stayed this week ([plannedMove]), as planTrace counts it: its customs sent it, or staying
 * was an emergency; else, settled where it is, it had no case to judge (a camp too new, no record), the case was less than
 * what leaving gives up, it could weigh no place, every other place was deadlier than staying, there was no other place,
 * the walk cost more than the case, or it moved.
 */
enum class Reckoned(val label: String) {
    OWED("owed"), EMERGENCY("emergency"), NO_CASE("no case to judge"), COSTS_MORE("leaving gives up more"), UNSCORED("no scores"),
    ONLY_DEADLIER("only deadlier places"), NOWHERE("no other place"), WALK_COSTS_MORE("walk costs more"), MOVED("moved")
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
