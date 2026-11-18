package inflation.ecology

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
 * Least effort to each hex within [reach] of [here] for [g] this week, into [buf] (its dist, via and reached), from the
 * step's shared search of the ground ([reachOf]): the hexes within its own reach, in index order, so the choice does not
 * depend on which group's search went furthest. ([restless] no longer parts the searches: within a reach they agree.)
 */
@Part("choosing: reach (least-effort search)")
@Suppress("UNUSED_PARAMETER")
internal fun GroupPlanner.searchReach(g: HexGroup, here: Hex, reach: Float, restless: Boolean, weather: Weather, buf: Scratch) {
    val dist = buf.dist; val via = buf.via; val reached = buf.reached
    for (i in 0 until reached.size) { val h = reached[i]; dist[h] = Float.MAX_VALUE; via[h] = Hex.NONE }
    reached.clear()
    val shared = gs.reachOf(g.species, gs.hw.placeBand[g.place], here, reach, weather)
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
