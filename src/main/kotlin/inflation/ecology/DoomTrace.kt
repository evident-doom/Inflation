package inflation.ecology

import inflation.Config
import inflation.ecology.GroupPlanner.PlanContext
import inflation.world.Hex
import inflation.world.Place
import inflation.world.get

// The doom trace: which bands run into hunger (doomList), and, run again from the same save, whether those bands had a
// better place within reach, or beyond it, week by week as they chose (doomTrace).

/** doomList=true: prints the bands that lost people to hunger, each year and as they die out ([listDoomed]). */
internal val DOOM_LIST = Config.flag(Config.Key.DOOM_LIST, false)
/** doomTrace=id,id,...: the bands followed week by week ([traceDoomed]). */
internal val DOOM_TRACE: Set<Int> = Config.string(Config.Key.DOOM_TRACE)?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet() ?: emptySet()
/** doomList: the year whose hunger deaths are being counted. */
internal var doomYear = Long.MIN_VALUE
/** Hunger deaths over a year at which a band is listed. */
internal const val DOOM_LEAST = 2
/** How far the trace looks for better country: this many times the band's reach this week. */
internal const val DOOM_WIDE = 4f
/** A place this many times as rich as the band's own, as its mover sees food ([knownGame]), counts as better. */
internal const val DOOM_BETTER = 1.25f

/**
 * doomTrace: for followed band [g] as it chose [best] this week ([c]): where it is and how it fares; its own place's food,
 * as its mover sees it ([knownGame]); the place it chose; the best place within its reach this week; and, by the least
 * effort out to [DOOM_WIDE] times its reach, the best place there and the nearest one [DOOM_BETTER] times as rich as its own.
 * Reads only: draws nothing, changes nothing, so the run is the run without it.
 */
internal fun GroupPlanner.traceDoomed(c: PlanContext, best: Place) {
    val g = c.g; val b = g.band ?: return; val kind = c.kind; val s = g.species
    fun value(h: Hex): Pair<Float, Place> {
        var v = Float.NEGATIVE_INFINITY; var at = Place.NONE
        for (pi in gs.hw.placesOf(h)) { val p = Place(pi); val x = knownGame(g, kind, p, h); if (x > v) { v = x; at = p } }
        return v to at
    }
    val here = value(c.here).first.coerceAtLeast(0f)
    val reached = c.buf.reached; val dist = c.buf.dist
    var inBest = Float.NEGATIVE_INFINITY; var inBestKm = 0f; var inBetter = 0
    for (i in 0 until reached.size) { val h = reached[i]; val v = value(h).first
        if (v > inBest) { inBest = v; inBestKm = dist[h] }
        if (v >= DOOM_BETTER * here && v > 0f) inBetter++ }
    // (The wider search: its own least-effort walk, with this week's going, apart from the shared one.)
    val effort = stepEffort(Catalogue.travel(s).ordinal, gs.hw.placeBand[g.place], c.weather)
    val wide = DOOM_WIDE * c.reach
    val d = HashMap<Int, Float>(); val q = java.util.PriorityQueue<Pair<Float, Hex>>(compareBy { it.first })
    d[c.here.i] = 0f; q += 0f to c.here
    var wBest = Float.NEGATIVE_INFINITY; var wBestKm = 0f; var wBestHex = Hex.NONE; var firstBetterKm = Float.NaN
    while (q.isNotEmpty()) {
        val (dd, h) = q.poll()
        if (dd > (d[h.i] ?: Float.MAX_VALUE)) continue
        val v = value(h).first
        if (v > wBest) { wBest = v; wBestKm = dd; wBestHex = h }
        if (firstBetterKm.isNaN() && v >= DOOM_BETTER * here && v > 0f) firstBetterKm = dd
        for (k in 0 until 6) {
            val nh = gs.hex.neighbour(h, k); if (nh.none) continue
            val nd = dd + effort[gs.hex.edge(h, k)]
            if (!nd.isFinite() || nd > wide) continue
            if (nd < (d[nh.i] ?: Float.MAX_VALUE)) { d[nh.i] = nd; q += nd to nh }
        }
    }
    val bestHex = gs.hw.placeHex[best]
    val chose = value(bestHex).first
    val crowdBest = if (!wBestHex.none) gs.crowdedBy(s, kinAround(g, kind, wBestHex, c.sensed)) else 0f
    val larder = g.larder / g.dailyNeedKg.coerceAtLeast(1e-3f)
    System.err.println(("doom #%d %s: %d people, fed %.2f, fat %.1f d, larder %.1f d, yield %.2f%s; reach %.0f (%d hexes): " +
        "here %.0f, chose %.0f%s; best in reach %.0f at %.0f (%d places %.2fx); within %.0f: best %.0f at %.0f (crowd %.2f), first %.2fx at %s").format(
        g.id, c.date, g.size, g.fed, g.fat, larder, b.yieldNow, if (c.restless) ", restless" else "", c.reach, reached.size,
        here, chose, if (best == g.place) " (stays)" else " at %.0f".format(dist[bestHex]), inBest, inBestKm, inBetter, DOOM_BETTER,
        wide, wBest, wBestKm, crowdBest, DOOM_BETTER, if (firstBetterKm.isNaN()) "none" else "%.0f".format(firstBetterKm)))
}

/** doomList: band [g]'s hunger deaths this year so far ([Band.hungerDied]), printed when it dies out. */
internal fun listDoomedEnd(g: HexGroup, date: inflation.world.Date) {
    val b = g.band ?: return
    if (b.hungerDied > 0) System.err.println("doomList #%d died out %s: %d hunger deaths this year, last %d people".format(g.id, date, b.hungerDied, g.size))
}

/** doomList: at a year's end, the living bands with [DOOM_LEAST] or more hunger deaths in it; and the count starts again. */
internal fun listDoomedYear(groups: List<HexGroup>, date: inflation.world.Date) {
    for (g in groups) { val b = g.band ?: continue; if (!g.alive) continue
        if (b.hungerDied >= DOOM_LEAST) System.err.println("doomList #%d year to %s: %d hunger deaths, now %d people".format(g.id, date, b.hungerDied, g.size))
        b.hungerDied = 0 }
}
