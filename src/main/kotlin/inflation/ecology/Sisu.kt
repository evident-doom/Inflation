package inflation.ecology

import inflation.HASH_A
import inflation.HASH_B
import inflation.HASH_C
import inflation.mix64Once
import kotlin.math.max
import kotlin.math.min

/**
 * Misery costs work: cold, hungry people do not work as people do on a good day. A band's [Band.pace] is the share of its
 * people's working time that is work: the cold takes breaks to warm up by the fire ([BodyHeat], [Band.coldPace]), and
 * hunger past the body's fat saps strength and will ([hungerPace]). What is lost is spent idle at camp, and eats as rest
 * does. Perseverance ([Band.sisu]) keeps people working through it, at a price: carried as heat debt in the cold, and as
 * the food the work burns while hungry.
 *
 * Darkness is not in it: at the latitudes of the worlds run (about 30-40°) a winter's day still has 9-10 hours of light,
 * more than the [BodyHeat.WORK_H] hours of work.
 */
object Sisu {
    /**
     * The share of their working pace people starved to the end of their body's reserves lose, if they do not persevere: an
     * estimate. In the Minnesota semi-starvation study men at a body mass index of about 16.5 after 24 weeks fell into
     * marked inactivity and lost the will to work (Keys et al. 1950, The Biology of Human Starvation); Guatemalan farmers
     * with less lean mass worked at 10% lower intensity and took 69% longer over the same tasks (Town et al. 1989, as
     * summarised in FAO 1994, Body mass index: a measure of chronic energy deficiency in adults, ch. 5).
     */
    const val LETHARGY = 0.5f

    /** The change at a split, standard deviation: a tenth of the range, so lineages stay recognisable (not measured, as [Temperament.DRIFT_SD]). */
    const val DRIFT_SD = 0.1f

    /**
     * The pace hunger leaves people of [s] who have burnt [spent] days of their body's protein (their fat spent: see
     * [Hazards.spentAfter]) with perseverance [sisu]: none lost while fat carries them, the more the nearer the end of their
     * protein ([Hazards.starveDays]).
     */
    fun hungerPace(s: Species, spent: Float, sisu: Float) = 1f - (1f - sisu) * LETHARGY * min(1f, max(0f, spent) / Hazards.starveDays(s))

    /**
     * A founding band's perseverance, from its group's [id] and the world's [seed]: drawn evenly from 0 to 1, so that
     * lineages try out every degree of it and selection sorts them (not measured).
     */
    fun founder(id: Int, seed: Long) = uniform(id, 1, seed)

    /** An offshoot's: its parent's [parent], a little changed. */
    fun inherit(parent: Float, id: Int, seed: Long) = (parent + DRIFT_SD * (uniform(id, 2, seed) + uniform(id, 3, seed) + uniform(id, 4, seed) - 1.5f) * 2f).coerceIn(0f, 1f)

    /** An even draw from 0 to 1 from [id], [salt] and the world's [seed]. */
    private fun uniform(id: Int, salt: Int, seed: Long): Float {
        val h = mix64Once(id.toLong() * HASH_A + (977L + salt) * HASH_B + seed * HASH_C)
        return ((h ushr 40).toFloat() + 0.5f) / (1L shl 24).toFloat()
    }
}
