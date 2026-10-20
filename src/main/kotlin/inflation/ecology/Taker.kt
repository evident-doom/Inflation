package inflation.ecology

/**
 * Who took small prey, as the slot of the "taken by" ledgers of [HexSmallLife]: a resident kind (by its position in the
 * fauna's kinds, below [GROUPS]), the hunting groups and people ([GROUPS]), or one of the small hunters that have no kind
 * of their own ([FOXES], [EAGLES], [OWLS]). A type of its own so that a kind position is not read as a slot by chance.
 */
@JvmInline value class Taker(val i: Int) {
    /** Hunting groups stand for every kind from [GROUPS] up. */
    val atMostGroups get() = Taker(minOf(i, GROUPS.i))
    companion object {
        val GROUPS = Taker(40)
        val FOXES = Taker(41)
        val EAGLES = Taker(42)
        val OWLS = Taker(43)
        /** The slots the ledgers hold for herp and tortoise takers (kinds and groups), and for all takers. */
        const val KINDS_AND_GROUPS = 41
        const val ALL = 44
        /** The resident kind at position [k] in the fauna's kinds. */
        fun kind(k: Int) = Taker(k)
    }
}
@Suppress("NOTHING_TO_INLINE") inline operator fun Array<FloatArray>.get(t: Taker) = this[t.i]
