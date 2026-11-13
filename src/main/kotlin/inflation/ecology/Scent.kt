package inflation.ecology

import inflation.world.Hex
import inflation.world.get
import inflation.world.set

/**
 * Scent marks of each hunting kind on each hex: how fresh (0..1, fading week by week unless
 * renewed) and whose. A pack marks the ground it works ([mark]); others of its kind keep off fresh marks.
 */
class Scent {
    /** Freshness of the marks by kind (index) and hex. */
    internal val marks = HashMap<Int, FloatArray>()
    /** Whose mark it is (group id, -1 none) by kind and hex. */
    internal val markers = HashMap<Int, IntArray>()
    /**
     * The second claimant of each hex by kind, its freshness and whose ([claim]): two packs whose grounds overlap both keep
     * their claim on the hexes they share, the fresher first. Null before.
     */
    internal var marks2: HashMap<Int, FloatArray>? = null
    internal var markers2: HashMap<Int, IntArray>? = null

    /** Freshness of marks other than [id]'s of kind [s] on hex [h]: the freshest claim that is not its own. */
    fun foreign(s: Species, h: Hex, id: Int): Float {
        val m = marks[s.index] ?: return 0f
        if (markers.getValue(s.index)[h] != id) return m[h]
        return marks2?.get(s.index)?.get(h.i) ?: 0f
    }

    /**
     * Pack [id] of kind [k] claims hex [h] at freshness [w] ([mark], a pack holding its ground): its own claim renewed if it
     * has one, else a new one where no other claim is fresh ([MARK_FRESH]), taking the first place if fresher than the
     * first claim (which keeps the second), else the second if fresher than that. So a claim is never lost by another's
     * coming but as it fades or a third, fresher one comes; and which pack marks first does not decide who holds.
     */
    fun claim(k: Int, h: Hex, id: Int, w: Float, n: Int) {
        val m = marks.getValue(k); val who = markers.getValue(k)
        val m2 = (marks2 ?: HashMap<Int, FloatArray>().also { marks2 = it }).getOrPut(k) { FloatArray(n) }
        val who2 = (markers2 ?: HashMap<Int, IntArray>().also { markers2 = it }).getOrPut(k) { IntArray(n) { -1 } }
        val i = h.i
        when {
            who[i] == id -> m[i] = maxOf(m[i], w)
            who2[i] == id -> { m2[i] = maxOf(m2[i], w)
                if (m2[i] > m[i]) { val t = m[i]; m[i] = m2[i]; m2[i] = t; who2[i] = who[i]; who[i] = id } }
            m[i] >= MARK_FRESH -> {}
            w > m[i] -> { m2[i] = m[i]; who2[i] = who[i]; m[i] = w; who[i] = id }
            w > m2[i] -> { m2[i] = w; who2[i] = id }
        }
    }
}
