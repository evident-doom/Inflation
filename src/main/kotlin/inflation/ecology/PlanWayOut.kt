package inflation.ecology

import inflation.ecology.GroupPlanner.Scratch
import inflation.world.Hex
import inflation.world.Weather
import inflation.world.get

// Choosing: the way out of a peril. A group warned of a deadly peril at its camp ([Peril]) flees, at a forced march (see
// [GroupPlanner.plan]); but where every place within a week's reach lies in the peril's way, "how soon it strikes" says
// nothing of which way is out, and the group sat still while the storm came (perilwhy684, 2026-11-06: of 60 bands killed
// in camp, 26 never had ground free of the storm within a week's reach and mostly stayed put for 6 weeks of warning, and 24
// had fled to the edge of its path, which the storm then reached). Facing sure death, anything living grasps the slightest
// chance (Olli 2026-11-05): it heads for the nearest safe ground, however far (Olli chose this, 2026-11-06).

/** How many weeks of its reach a group warned of a peril looks for ground free of it: the hurricane test's warning, 6 weeks. */
internal const val WAY_OUT_WEEKS = 6f

/**
 * For group [g], warned of a peril at its camp in [here]: by least effort out to [WAY_OUT_WEEKS] times its [reach] this week,
 * the hexes free of every peril ([HexGroups.perilAhead] 0); then from them, the least effort back to each hex it can reach
 * this week, into [Scratch.wayOut] (none if no free hex lies that near). The going is this week's ([stepEffort]), taken the
 * same both ways; straits are not swum on the way out.
 *
 * Only ground it can be on when the peril is not counts ([HexGroups.perilWeeks]): at its reach a week, a hex is passed
 * through only if the peril does not strike it the week the group would get there, and is free ground if the peril strikes
 * it no more from that week on, though it struck it before: so the group may slip behind the peril, over ground it has
 * passed. Without the weeks, the way out ran along the storm's own coming path (stuck689 and parts689, 2026-11-07: 21 of
 * the 33 bands killed with free ground in reach the week before had fled toward a way out the storm reached first); with
 * only the week a peril first strikes, ground it had passed stayed shut, and 26 of 35 killed bands found no way out
 * (timed691). The weeks are those of the least effort out from the camp; the way back keeps to the hexes so passed.
 */
internal fun GroupPlanner.searchWayOut(g: HexGroup, here: Hex, reach: Float, weather: Weather, buf: Scratch) {
    val effort = stepEffort(Catalogue.travel(g.species).ordinal, gs.hw.placeBand[g.place], weather)
    val wide = WAY_OUT_WEEKS * reach
    // (Out from the camp: every hex within the wide reach, and which of them are free.)
    val seen = HashMap<Int, Float>(); val q = java.util.PriorityQueue<Pair<Float, Hex>>(compareBy { it.first })
    seen[here.i] = 0f; q += 0f to here
    val free = ArrayList<Hex>()
    while (q.isNotEmpty()) {
        val (d, h) = q.poll()
        if (d > (seen[h.i] ?: Float.MAX_VALUE)) continue
        if (strikesFrom(h, d, reach) == 0) { free += h; continue }
        for (k in 0 until 6) {
            val nh = gs.hex.neighbour(h, k); if (nh.none) continue
            val nd = d + effort[gs.hex.edge(h, k)]
            if (!nd.isFinite() || nd > wide) continue
            // (Not where the peril strikes the week it would get there.)
            if ((strikesFrom(nh, nd, reach) and 1) != 0) continue
            if (nd < (seen[nh.i] ?: Float.MAX_VALUE)) { seen[nh.i] = nd; q += nd to nh }
        }
    }
    if (free.isEmpty()) return
    // (Back from the free ground, over the hexes found: the effort still to go from each.)
    val out = HashMap<Int, Float>()
    for (h in free) { out[h.i] = 0f; q += 0f to h }
    while (q.isNotEmpty()) {
        val (d, h) = q.poll()
        if (d > (out[h.i] ?: Float.MAX_VALUE)) continue
        for (k in 0 until 6) {
            val nh = gs.hex.neighbour(h, k); if (nh.none || nh.i !in seen) continue
            val nd = d + effort[gs.hex.edge(h, k)]
            if (!nd.isFinite()) continue
            if (nd < (out[nh.i] ?: Float.MAX_VALUE)) { out[nh.i] = nd; q += nd to nh }
        }
    }
    buf.wayOut = out; buf.wayOutReach = reach
}

/**
 * The weeks a peril strikes hex [h] from the week a group gets there, at [effort] from its camp and [reach] a week: bit 0 for
 * that week ([HexGroups.perilWeeks]); 0 if it strikes there no more, or if the group gets there beyond the weeks foreseen.
 */
private fun GroupPlanner.strikesFrom(h: Hex, effort: Float, reach: Float): Int {
    val week = (effort / reach.coerceAtLeast(1f)).toInt()
    return if (week >= Peril.MAX_STRIKE_WEEKS) 0 else gs.perilWeeks(h) ushr week
}

/**
 * The peril a group foresees at hex [h] ([HexGroups.perilAhead]), and where its camp is in peril and a way out was found
 * ([searchWayOut]), weighed by the share of the way out still to go from [h]: r / (r + its week's reach), r the effort from
 * [h] to the nearest free ground. A place a week short of safety weighs half its peril, one on the safe ground none. So
 * among places all in the peril's way, those nearer the way out are the safer, as fleeing makes them.
 */
internal fun GroupPlanner.wayOutPeril(h: Hex, buf: Scratch): Float {
    val ahead = gs.perilAhead(h)
    if (ahead == 0f) return 0f
    val out = buf.wayOut ?: return ahead
    val r = out[h.i] ?: return ahead
    return ahead * r / (r + buf.wayOutReach.coerceAtLeast(1f))
}
