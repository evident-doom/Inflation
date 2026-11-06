package inflation.ecology

import inflation.world.Hex

/**
 * A deadly event a group can be warned of before it strikes, and that it can only escape by leaving the ground it will strike:
 * the hurricane test's storms now ([HexHurricane]); later a volcano erupting, a flood rising, a wildfire coming, an enemy army,
 * a plague in the next valley (Olli 2026-11-06: the storms are the first of the deadly events that call for flight). Every
 * peril feeds the one net input [HexFeatures.DEADLY_AHEAD], so that what a mover learns of fleeing one is there for the next.
 */
interface Peril {
    /**
     * How soon and how surely it kills a group at hex [h], as the group is told: the share that would die times 1 for this
     * week, less by a step for each week ahead; 0 where none is foreseen.
     */
    fun ahead(h: Hex): Float
}

/** The deadliest peril foreseen at hex [h] ([Peril.ahead]); 0 where none is. Every peril the world holds is weighed here. */
internal fun HexGroups.perilAhead(h: Hex): Float = hurricanes?.ahead(h) ?: 0f
