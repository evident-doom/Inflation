package inflation.ecology

import inflation.Config
import inflation.ecology.GroupPlanner.PlanContext
import inflation.world.Hex
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
 *   allow, [exploreDraw], [plannedMove]): its steps and score; how many free places were not open, or had no score;
 * - way out: the effort from its camp to the nearest free ground within [WAY_OUT_WEEKS] weeks' reach ([searchWayOut]).
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
        "chose peril %.2f%s, %s; weighed %d, safest steps %.1f; free weighed %d (not open %d, no score %d), best open free: %s; way out %s").format(
        g.id, c.date, g.size, b.mind.label, if (byNet) "net" else "hand", if (c.restless) ", restless" else "", if (gs.parties[g.id] != null) ", party out" else "",
        camp, place(kStay), c.reach, reached.size, freeHexes, if (nearestFree.isNaN()) "none" else "%.0f".format(nearestFree),
        gs.perilAhead(bestHex), if (best == g.place) " (stays)" else " at %.0f".format(dist[bestHex]), place(kChose),
        n, safest, freeWeighed, freeShut, freeUnscored, if (kFree < 0) "none" else place(kFree),
        wayOutLook(c)))
    perilParts(c, kStay, kChose)
    return true
}

/**
 * perilTrace: what the deaths a band weighed are made of (`perilParts #id` line), as shares of it this week, at its camp, at the
 * place it chose, at the weighed place nearest the way out ([searchWayOut]) and at the safest weighed place free of the peril:
 * the peril ([perilDeaths]), the crossings ([crossingDeaths]), the place's own ([deathsAt]: hunger, cold, sickness, beasts...),
 * and what leaving its camp costs ([Band.leavingDying]); with the survival steps and score of each.
 */
private fun GroupPlanner.perilParts(c: PlanContext, kStay: Int, kChose: Int) {
    val g = c.g; val b = g.band ?: return; val buf = c.buf; val n = buf.explPlaces.size
    val campHex = b.campPlace.takeIf { !it.none }?.let { gs.hw.placeHex[it] } ?: Hex.NONE
    val leaving = if (campHex.none || b.mind.gate == MoveGate.RECKONED) 0f else b.leavingDying
    val out = buf.wayOut
    fun rOf(k: Int) = out?.get(gs.hw.placeHex[buf.explPlaces[k]].i) ?: Float.POSITIVE_INFINITY
    var kOut = -1; var kFree = -1
    for (k in 0 until n) { val h = gs.hw.placeHex[buf.explPlaces[k]]
        if (!buf.explDeaths[k].isFinite()) continue
        val r = rOf(k)
        if (r.isFinite() && (kOut < 0 || r < rOf(kOut) || (r == rOf(kOut) && buf.explDeaths[k] < buf.explDeaths[kOut]))) kOut = k
        if (gs.perilAhead(h) == 0f && (kFree < 0 || buf.explDeaths[k] < buf.explDeaths[kFree])) kFree = k }
    fun parts(k: Int): String {
        if (k < 0) return "none"
        val h = gs.hw.placeHex[buf.explPlaces[k]]
        val peril = perilDeaths(h, c.here, buf.via, buf); val cross = crossingDeaths(g.species, h, c.here, buf.via)
        val place = buf.explDying[k] - peril - cross; val leave = if (leaving > 0f && h != campHex) leaving else 0f
        return "at %.0f km: peril %.3f, crossing %.3f, place %.3f, leaving %.3f; steps %.1f, score %.2f".format(
            buf.dist[h], peril, cross, place, leave, buf.explDeaths[k], buf.explScores[k])
    }
    System.err.println("perilParts #%d %s: camp %s | chose %s | nearest the way out %s | safest free %s".format(
        g.id, c.date, parts(kStay), parts(kChose), parts(kOut), parts(kFree)))
}

/**
 * perilTrace: the way out as band [c].g sees it this week ([searchWayOut]): the effort from its camp to the nearest free ground;
 * the least of it from any hex it can reach this week, and how far that hex is (no progress if it is the camp's); and the first
 * step on the way out from camp, its effort against the week's reach. "none" if no way out was found.
 */
private fun GroupPlanner.wayOutLook(c: PlanContext): String {
    val out = c.buf.wayOut ?: return "none"
    val fromCamp = out[c.here.i] ?: return "none"
    val reached = c.buf.reached; val dist = c.buf.dist
    var least = fromCamp; var leastKm = 0f
    for (i in 0 until reached.size) { val h = reached[i]; val r = out[h.i] ?: continue
        if (r < least) { least = r; leastKm = dist[h] } }
    val effort = stepEffort(Catalogue.travel(c.g.species).ordinal, gs.hw.placeBand[c.g.place], c.weather)
    var step = Float.NaN; var best = Float.POSITIVE_INFINITY
    for (k in 0 until 6) { val nh = gs.hex.neighbour(c.here, k); if (nh.none) continue
        val r = out[nh.i] ?: continue; val e = effort[gs.hex.edge(c.here, k)]
        if (e + r < best) { best = e + r; step = e } }
    return "%.0f km; least in reach %.0f km%s; first step %s km".format(fromCamp, least,
        if (least < fromCamp) " at %.0f".format(leastKm) else " (no progress)", if (step.isNaN()) "none" else "%.0f".format(step))
}
