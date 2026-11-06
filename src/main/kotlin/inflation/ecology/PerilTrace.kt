package inflation.ecology

import inflation.Config
import inflation.ecology.GroupPlanner.PlanContext
import inflation.world.Place
import inflation.world.get

// The peril trace, a [GroupWatch] (perilTrace=true): every band that foresees a deadly peril at its camp ([HexGroups.perilAhead]),
// each week as it chooses: how far it could go, how much of that is free of the peril, and of the places it weighed, the one it
// chose against the best one free of the peril, in survival steps and score; and at the week's end, whether it lived. Made to
// see why bands die in the hurricane test with a place free of the storm within reach the week before. See
// docs/tracing-groups.md and `scripts/doomtrace.py peril`.

/** The peril trace as a [GroupWatch]. */
internal object PerilWatch : GroupWatch {
    /** The bands printed this week (by id), to say at the week's end whether each lived. */
    private val followed = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()
    override fun chose(planner: GroupPlanner, c: PlanContext, best: Place, byNet: Boolean) {
        if (c.g.band != null && planner.tracePeril(c, best, byNet)) followed[c.g.id] = true
    }
    override fun weekEnd(gs: HexGroups, date: inflation.world.Date) {
        if (followed.isEmpty()) return
        for (g in gs.groups) if (followed.remove(g.id) != null)
            System.err.println("perilEnd #%d %s: %s".format(g.id, date, if (g.alive) "lives, ${g.size} people" else if (g.mergedSize >= 0) "joined another band" else "died out"))
        followed.clear()
    }
}

/** perilTrace=true: the bands warned of a peril at their camp, followed through the week ([tracePeril]). */
internal val PERIL_TRACE = Config.flag(Config.Key.PERIL_TRACE, false)

/**
 * perilTrace: band [c].g, choosing [best] this week, if it foresees a peril at its camp: one line (`peril #id ...`). Reads only:
 * draws nothing, changes nothing. Returns whether it printed.
 * - camp: the peril there ([HexGroups.perilAhead], 1 = it strikes this week) and the survival steps staying costs;
 * - reach: km, hexes, those free of the peril and the least effort to the nearest of them;
 * - chose: its peril, the effort there (or stays), survival steps and hand-written score;
 * - weighed: the places weighed, the least survival steps among them; the places free of the peril weighed, and of them
 *   the best by hand-written score that is open (no deadlier than the safest by a step, as the draw and the planner
 *   allow, [exploreDraw], [plannedMove]): its steps and score; how many free places were not open, or had no score.
 */
internal fun GroupPlanner.tracePeril(c: PlanContext, best: Place, byNet: Boolean): Boolean {
    val g = c.g; val b = g.band ?: return false
    val camp = gs.perilAhead(c.here)
    if (!(camp > 0f)) return false
    val buf = c.buf; val reached = buf.reached; val dist = buf.dist
    var freeHexes = 0; var nearestFree = Float.NaN
    for (i in 0 until reached.size) { val h = reached[i]
        if (gs.perilAhead(h) == 0f) { freeHexes++; if (nearestFree.isNaN() || dist[h] < nearestFree) nearestFree = dist[h] } }
    val n = buf.explPlaces.size
    var safest = Float.POSITIVE_INFINITY
    for (k in 0 until n) if (buf.explScores[k].isFinite() && buf.explDeaths[k].isFinite() && buf.explDeaths[k] < safest) safest = buf.explDeaths[k]
    var kChose = -1; var kStay = -1; var kFree = -1; var freeWeighed = 0; var freeShut = 0; var freeUnscored = 0
    for (k in 0 until n) {
        val p = buf.explPlaces[k]
        if (p == best.i) kChose = k
        if (p == g.place.i) kStay = k
        if (gs.perilAhead(gs.hw.placeHex[p]) != 0f) continue
        freeWeighed++
        if (!buf.explScores[k].isFinite() || !buf.explDeaths[k].isFinite()) { freeUnscored++; continue }
        if (buf.explDeaths[k] - safest > 1f) { freeShut++; continue }
        if (kFree < 0 || buf.explScores[k] > buf.explScores[kFree]) kFree = k
    }
    fun place(k: Int) = if (k < 0) "not weighed" else "steps %.1f, score %.2f".format(buf.explDeaths[k], buf.explScores[k])
    val bestHex = gs.hw.placeHex[best]
    System.err.println(("peril #%d %s: %d people, mind %s, by %s%s%s; camp peril %.2f (%s); reach %.0f km (%d hexes, %d free, nearest free at %s); " +
        "chose peril %.2f%s, %s; weighed %d, safest steps %.1f; free weighed %d (not open %d, no score %d), best open free: %s").format(
        g.id, c.date, g.size, b.mind.label, if (byNet) "net" else "hand", if (c.restless) ", restless" else "", if (gs.parties[g.id] != null) ", party out" else "",
        camp, place(kStay), c.reach, reached.size, freeHexes, if (nearestFree.isNaN()) "none" else "%.0f".format(nearestFree),
        gs.perilAhead(bestHex), if (best == g.place) " (stays)" else " at %.0f".format(dist[bestHex]), place(kChose),
        n, safest, freeWeighed, freeShut, freeUnscored, if (kFree < 0) "none" else place(kFree)))
    return true
}
