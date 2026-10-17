package inflation.ecology

/**
 * Scent marks of each hunting kind on each hex: how fresh (0..1, fading week by week unless
 * renewed) and whose. A pack marks the ground it works ([mark]); others of its kind keep off fresh marks.
 */
class Scent {
    /** Freshness of the marks by kind (index) and hex. */
    internal val marks = HashMap<Int, FloatArray>()
    /** Whose mark it is (group id, -1 none) by kind and hex. */
    internal val markers = HashMap<Int, IntArray>()

    /** Freshness of marks other than [id]'s of kind [s] on hex [h]. */
    fun foreign(s: Species, h: Int, id: Int): Float {
        val m = marks[s.index] ?: return 0f
        return if (markers.getValue(s.index)[h] == id) 0f else m[h]
    }
}
