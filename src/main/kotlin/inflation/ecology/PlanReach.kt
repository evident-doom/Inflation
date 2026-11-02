package inflation.ecology

import inflation.ecology.GroupPlanner.Reach
import inflation.ecology.GroupPlanner.Scratch
import inflation.Par
import inflation.Part
import inflation.world.Hex
import inflation.world.hexArray
import inflation.world.toHexArray
import inflation.world.get
import inflation.world.set
import inflation.world.Weather
import kotlin.math.min

// Choosing: the least-effort search of the hexes a group can reach this week, and the effort of each step.

/**
 * Least effort to each hex within [reach] of [here] for [g] this week, into [buf] (its dist, via and
 * reached): from a search shared with groups of its kind starting from the same place that reach as
 * far or further, else from a new one (then shared).
 */
@Part("choosing: reach (least-effort search)")
internal fun GroupPlanner.searchReach(g: HexGroup, here: Hex, reach: Float, restless: Boolean, weather: Weather, buf: Scratch) {
    val s = g.species
    val dist = buf.dist; val via = buf.via; val reached = buf.reached
    for (i in 0 until reached.size) { val h = reached[i]; dist[h] = Float.MAX_VALUE; via[h] = Hex.NONE }
    reached.clear()
    val q = buf.queue; q.clear()
    // Groups of a kind starting from the same place this week share the search.
    // Each group's reach is its own (hunger, the band's weak), so a search is shared only
    // with groups that reach no further, and each takes the hexes within its own reach: least-effort
    // distances within a reach do not depend on how far the search went beyond it. The hexes are kept
    // in index order, so the choice does not depend on which group's search was kept.
    val reachKey = ((s.index.toLong() * 8 + gs.hw.placeBand[g.place]) * gs.hex.size + here.i) * 2 + (if (restless) 1 else 0)
    var shared = reachCache[reachKey]
    if (shared == null || shared.reach < reach) {
        dist[here] = 0f; reached += here; q += 0L.withPlace(0f, here)
        // (The effort of each crossing this week, through snow and along worn paths: see [stepEffort].)
        val effort = stepEffort(Catalogue.travel(s).ordinal, gs.hw.placeBand[g.place], weather)
        while (q.isNotEmpty()) {
            val e = q.poll()
            val d = Float.fromBits((e ushr 32).toInt()); val h = Hex((e and 0xffffffffL).toInt())
            if (d > dist[h]) continue
            for (k in 0 until 6) {
                val nh = gs.hex.neighbour(h, k)
                if (nh.none) continue
                val nd = d + effort[gs.hex.edge(h, k)]
                if (!nd.isFinite() || nd > reach) continue
                if (nd < dist[nh]) { if (dist[nh] == Float.MAX_VALUE) reached += nh; dist[nh] = nd; via[nh] = h; q += 0L.withPlace(nd, nh) }
            }
            // Swimmers may cross a strait they can cover, at a great effort.
            val swim = Catalogue.swimKm(s)
            if (swim > 0f) for (i in gs.seaLinks[h].indices) { val nh = gs.seaLinks[h][i]
                val gapKm = gs.seaGaps[h][i]
                if (gapKm > swim) continue
                val nd = d + gapKm * SWIM_EFFORT
                if (nd > reach) continue
                if (nd < dist[nh]) { if (dist[nh] == Float.MAX_VALUE) reached += nh; dist[nh] = nd; via[nh] = h; q += 0L.withPlace(nd, nh) }
            }
        }
        val hs = reached.toHexArray().also { it.sort() }
        val mine = Reach(reach, hs, FloatArray(hs.size) { dist[hs[it]] }, hexArray(hs.size) { via[hs[it]] })
        shared = reachCache.merge(reachKey, mine) { a, b -> if (a.reach >= b.reach) a else b }!!
        for (i in 0 until reached.size) { val h = reached[i]; dist[h] = Float.MAX_VALUE; via[h] = Hex.NONE }
        reached.clear()
    }
    for (i in shared.hexes.indices) {
        if (shared.dist[i] > reach) continue
        val h = shared.hexes[i]; reached += h; dist[h] = shared.dist[i]; via[h] = shared.via[i]
    }
}

@IgnorableReturnValue internal fun GroupPlanner.stepEffort(travel: Int, band: Int, weather: Weather): FloatArray = stepEffort.computeIfAbsent(travel * 64 + band) {
    val cross = gs.hex.crossings[travel]
    val out = FloatArray(gs.hex.size * 6) { Float.POSITIVE_INFINITY }
    Par.forEachHex("choosing: step effort", gs.hex.size) { h ->
        for (k in 0 until 6) {
            val nh = gs.hex.neighbour(h, k)
            if (nh.none || gs.hex.heart[nh] < 0) continue
            // Lying snow makes the going harder: about twice the effort in 15 cm, more in deeper. (Read at the height
            // it walks at: in the next hex, the ground nearest the low or high ground of this one it keeps to.)
            val np = gs.hw.placeIn(band, h).let { if (!it.none) gs.hw.placeNear(it, nh) else gs.hw.placeIn(band, nh) }
            val snowMm = if (!np.none) weather.snowpack[np] else 0f
            // A worn path makes the going easier, the more so where the country is broken.
            val worn = gs.wear.paths[gs.hex.edge(h, k)] * PATH_EASE * (0.5f + (if (!np.none) gs.land.ruggedness[np] else 0f))
            out[gs.hex.edge(h, k)] = cross[gs.hex.edge(h, k)] * (1f + snowMm / SNOW_EFFORT_MM) * (1f - min(0.7f, worn))
        }
    }
    out
}
