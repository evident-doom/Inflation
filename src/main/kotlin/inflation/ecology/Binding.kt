package inflation.ecology

/**
 * What bound in a band's season, for the training of its ways: an output that changed nothing in a season (a weight on an
 * activity's days in weeks when the asks fit the week, the loads carried in a season with no camp move) has nothing to be
 * judged by, and its draw is left out of the likelihood of that season's draw. Counted by the week ([BandTime.short] and
 * the path of the move), from facts that do not depend on the draw, so that leaving them out does not tilt what is learnt.
 */
object Binding {
    /** The slot that counts the weeks a band moved camp; the slots before it are the [Activity]s', in weeks it asked for days while the asks came to more than the week. */
    val MOVES = Activity.entries.size
    val SIZE = MOVES + 1

    /** The slot each of the ways' outputs depends on, -1 for an output that always acts. */
    val slotOfOutput: IntArray = IntArray(WAYS_OUT) { -1 }.also {
        for (a in Activity.entries) timeWay(a)?.let { way -> it[way.slot] = a.ordinal }
        it[BandWay.CARRY_LOADS.slot] = MOVES
    }

    /** Whether way output [i] acted in a season counted in [bound] (null: not counted, so every output counts). */
    fun acted(bound: FloatArray?, i: Int): Boolean = bound == null || slotOfOutput[i].let { it < 0 || bound[it] > 0f }
}
