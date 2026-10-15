package inflation.ecology

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * A group's temperament: how it leans, beyond what its kind and its state call for, along the
 * axes on which animals of one kind are found to differ lastingly (Réale and colleagues 2007:
 * boldness, exploration, activity, sociability; recalled, not checked). Each is a small pull on
 * the places it weighs, added to whatever chooses for it, the hand-written score or a network
 * (which also sees it, so it can learn what a temperament is good for):
 * - boldness: drawn to ground thick with hunters (above 0) or shunning it more (below);
 * - exploration: drawn to ground it does not remember, or keeping to what it knows;
 * - activity: moving on, or staying put;
 * - sociability: drawn to its own kind about, or keeping apart.
 * Founding groups get theirs by chance; a group that splits off takes its parent's, a little
 * changed, so temperaments run in lineages and those that do well spread with them.
 */
object Temperament {
    const val AXES = 4
    val NAMES = listOf("boldness", "exploration", "activity", "sociability")
    /**
     * Spread of founders' temperaments, in units of a place's score: one spread from the average
     * weighs as much as the pull of staying put ([STAY]). Not measured; chosen so that temperament
     * tips close choices without overriding food, water, season or danger (at three times this,
     * herds' and packs' share of weeks moved shifted by up to 25 points in a 3-year check).
     */
    const val FOUNDER_SD = STAY
    /** The change at each split: a fifth of the founders' spread, so lineages stay recognisable over a few splits. Not measured. */
    const val DRIFT_SD = FOUNDER_SD / 5f

    /** A founder's temperament, from its id (so a seed gives the same run). */
    fun founder(id: Int) = FloatArray(AXES) { FOUNDER_SD * normal(id, it, 1) }

    /** An offshoot's: its parent's, a little changed. */
    fun inherit(parent: FloatArray, id: Int) = FloatArray(AXES) { (parent.getOrElse(it) { 0f }) + DRIFT_SD * normal(id, it, 2) }

    /**
     * The pull of temperament [t] toward a place with this much of hunters about ([danger], 0..1),
     * known to it or not ([remembered], 0 or 1), where it stands now or not ([stays]), and so
     * crowded with its own kind ([crowd], 0..1).
     */
    fun pull(t: FloatArray, danger: Float, remembered: Float, stays: Float, crowd: Float): Float =
        t[0] * danger + t[1] * (1f - remembered) + t[2] * (1f - stays) + t[3] * crowd

    /** A standard normal number from [id], [axis] and [salt]. */
    private fun normal(id: Int, axis: Int, salt: Int): Float {
        fun u(k: Int): Double {
            var h = id.toLong() * 0x9E3779B97F4A7C15uL.toLong() + (axis * 31 + salt * 1009 + k).toLong() * 0x632BE59BD9B4E019L
            h = (h xor (h ushr 33)) * -0xae502812aa7333L; h = h xor (h ushr 33)
            return ((h ushr 11).toDouble() + 0.5) / (1L shl 53).toDouble()
        }
        return (sqrt(-2.0 * ln(u(0))) * cos(2.0 * Math.PI * u(1))).toFloat()
    }
}

/** What temperament pulls group [g] toward place [p] (in hex [h]) with: the hand-written path's share. */
internal fun HexGroups.temperPull(g: HexGroup, p: Int, h: Int, predatorKg: FloatArray): Float {
    val hunters = predatorKg[p] / land.areaKm2[p]
    var remembered = 0f
    for (i in g.memory.hex.indices) if (g.memory.hex[i] == h) { remembered = 1f; break }
    return Temperament.pull(g.temper, hunters / (hunters + 3f), remembered, if (p == g.place) 1f else 0f, crowd(g.species, p, g))
}
