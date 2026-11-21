package inflation.ecology

import inflation.Part
import inflation.drawKey
import inflation.Stream
import inflation.world.Hex
import inflation.world.Place
import inflation.world.get
import inflation.world.set

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
     * Spread of founders' temperaments, in units of the temperature a band chooses at: a network's score, whose unit is
     * that temperature (1); the hand-written score takes it at its own unit, [HAND_SCORE_UNIT] ([temperPull]). So a
     * temperament leans as far on either. Not measured; kept wide enough that runs try out different ways of choosing
     * and selection and learning sort them. (Until 2026-11-05 the hand-written score took it at the network's scale, ten
     * times its own: the restless pull was then the largest part of half the moves it chose, and its bands, moving a
     * third of their weeks, starved: handmove-trace, handroles screens.)
     */
    const val FOUNDER_SD = 0.15f
    /** The change at each split: a fifth of the founders' spread, so lineages stay recognisable over a few splits. Not measured. */
    const val DRIFT_SD = FOUNDER_SD / 5f

    /** A founder's temperament, from its id and the world's [seed] (so a seed gives the same run, and another seed other founders). */
    fun founder(id: Int, seed: Long) = FloatArray(AXES) { FOUNDER_SD * normal(id, it, 1, seed) }

    /** An offshoot's: its parent's, a little changed. */
    fun inherit(parent: FloatArray, id: Int, seed: Long) = FloatArray(AXES) { (parent.getOrElse(it) { 0f }) + DRIFT_SD * normal(id, it, 2, seed) }

    /**
     * The pull of temperament [t] toward a place with this much of hunters about ([danger], 0..1),
     * known to it or not ([remembered], 0 or 1), where it stands now or not ([stays]), and so
     * crowded with its own kind ([crowd], 0..1).
     */
    fun pull(t: FloatArray, danger: Float, remembered: Float, stays: Float, crowd: Float): Float =
        t[0] * danger + t[1] * (1f - remembered) + t[2] * (1f - stays) + t[3] * crowd

    /** A standard normal number from [id], [axis], [salt] and the world's [seed]. */
    private fun normal(id: Int, axis: Int, salt: Int, seed: Long): Float {
        fun u(k: Int): Double {
            val h = drawKey(seed, Stream.TEMPERAMENT, id.toLong(), axis.toLong(), salt * 1_000_003L + k)
            return ((h ushr 11).toDouble() + 0.5) / (1L shl 53).toDouble()
        }
        return (sqrt(-2.0 * ln(u(0))) * cos(2.0 * Math.PI * u(1))).toFloat()
    }
}

/** What temperament pulls group [g] toward place [p] (in hex [h]) with: the hand-written path's share, in its units ([HAND_SCORE_UNIT], see [Temperament.FOUNDER_SD]). */
@Part("choosing: temper pull")
internal fun GroupWorld.temperPull(g: HexGroup, p: Place, h: Hex, hunters: Float, crowded: Float): Float {
    var remembered = 0f
    for (i in g.memory.hex.indices) if (g.memory.hex[i] == h) { remembered = 1f; break }
    return HAND_SCORE_UNIT * Temperament.pull(g.temper, hunters / (hunters + 3f), remembered, if (p == g.place) 1f else 0f, crowded)
}
