package inflation.ecology

import inflation.Part
import inflation.world.Hex
import inflation.world.HexArray
import inflation.world.HexList
import inflation.ecology.GroupPlanner.LongHeap
import inflation.world.Weather
import inflation.world.get
import inflation.world.hexArray
import inflation.world.set
import inflation.world.toHexArray

// The ground's reach (docs/design/coherence-review.md, B): one least-effort search of the ground about a hex, shared by
// everything a group does in person: the planner's choice of a place ([searchReach]), every trip and move ([findWay],
// [Movement]), the neighbours' meetings ([neighboursMeet]). It answers "how far is it, and which way", in km of easy going
// over the hexes' borders (slope, cliffs, rivers and fords, snow, worn paths: [GroupPlanner.stepEffort]) and over the
// straits a kind that swims can cover ([Catalogue.swimKm]).
//
// It sets no limit on going of its own, and nothing in it depends on the step's length: how far to look is each caller's
// ([most]: the neighbours' 50 km, a rite's search, the planner's reach for the group's step). The step only says which
// state of the ground is read (this step's snow and worn paths, as the going's effort is reckoned once a step), and how
// long a search is kept: the searches are shared until the groups next choose. Sharing changes no answer: least-effort
// distances within a reach do not depend on how far the search went beyond it, so a search to 50 km answers every
// question within 50 km exactly as its own would.

/**
 * The hexes [hexes] (in index order) within [reach] km of easy going of [from], each with its least effort [dist] and the
 * hex it is reached from on the way [via] ([Hex.NONE] for [from]): one search of [reachOf].
 */
internal class Reach(val from: Hex, val reach: Float, val hexes: HexArray, val dist: FloatArray, val via: HexArray) {
    /** The index of hex [h] in [hexes], or below 0 where it is not within the reach. */
    fun indexOf(h: Hex): Int = java.util.Arrays.binarySearch(hexes.a, h.i)

    /** The km of easy going to hex [h], null where it is beyond [most] (or this search). */
    fun km(h: Hex, most: Float = reach): Float? { val i = indexOf(h); return if (i >= 0 && dist[i] <= most) dist[i] else null }

    /** The way to hex [h], first to last, [from] first; null where it is beyond [most] (or this search). */
    fun wayTo(h: Hex, most: Float = reach): Way? {
        val i = indexOf(h); if (i < 0 || dist[i] > most) return null
        val out = ArrayList<Hex>(); var at = h
        while (true) { out += at; if (at == from) break; val k = indexOf(at); if (k < 0) break; at = via[k].takeIf { !it.none } ?: break }
        out.reverse()
        return Way(dist[i], out)
    }
}

/**
 * The ground about hex [from] for a group of kind [s] on height band [band], out to at least [most] km of easy going (as
 * far as the caller asks; no limit of its own): a search already made over the ground as it lies now, if one went as far,
 * else a new one (then kept, [GroupPlanner.reachCache], until the groups next choose and the ground's going is reckoned
 * afresh). Callers take only the hexes within their own [most] ([Reach.km], [Reach.wayTo]). Safe on all
 * cores: two that search at once keep the one that went further, and their answers agree within the shorter.
 */
@Part("reach: least-effort search")
internal fun HexGroups.reachOf(s: Species, band: Int, from: Hex, most: Float, weather: Weather): Reach {
    val key = (s.index.toLong() * 8 + band) * hex.size + from.i
    planner.reachCache[key]?.let { if (it.reach >= most) return it }
    val mine = searchFrom(s, band, from, most, weather)
    return planner.reachCache.merge(key, mine) { a, b -> if (a.reach >= b.reach) a else b } ?: mine
}

/** [reachOf]'s reach for group [g] from hex [from]: its kind, the height band of its place. */
internal fun HexGroups.reachOf(g: HexGroup, from: Hex, most: Float, weather: Weather): Reach =
    reachOf(g.species, hw.placeBand[g.place], from, most, weather)

/** The search itself ([reachOf]): least effort first, of equals the lower hex, so it is the same on every run and every core. */
private fun HexGroups.searchFrom(s: Species, band: Int, from: Hex, most: Float, weather: Weather): Reach {
    val buf = reachScratch.get()
    val dist = buf.dist; val via = buf.via; val reached = buf.reached; val q = buf.queue
    q.clear()
    dist[from] = 0f; reached += from; q += packed(0f, from)
    val effort = planner.stepEffort(Catalogue.travel(s).ordinal, band, weather)
    val swim = Catalogue.swimKm(s)
    while (q.isNotEmpty()) {
        val e = q.poll()
        val d = Float.fromBits((e ushr 32).toInt()); val h = Hex((e and 0xffffffffL).toInt())
        if (d > dist[h]) continue
        for (k in 0 until 6) {
            val nh = hex.neighbour(h, k)
            if (nh.none) continue
            val nd = d + effort[hex.edge(h, k)]
            if (!nd.isFinite() || nd > most) continue
            if (nd < dist[nh]) { if (dist[nh] == Float.MAX_VALUE) reached += nh; dist[nh] = nd; via[nh] = h; q += packed(nd, nh) }
        }
        // Swimmers may cross a strait they can cover, at a great effort.
        if (swim > 0f) for (i in seaLinks[h].indices) { val nh = seaLinks[h][i]
            val gapKm = seaGaps[h][i]
            if (gapKm > swim) continue
            val nd = d + gapKm * SWIM_EFFORT
            if (nd > most) continue
            if (nd < dist[nh]) { if (dist[nh] == Float.MAX_VALUE) reached += nh; dist[nh] = nd; via[nh] = h; q += packed(nd, nh) }
        }
    }
    val hs = reached.toHexArray().also { it.sort() }
    val out = Reach(from, most, hs, FloatArray(hs.size) { dist[hs[it]] }, hexArray(hs.size) { via[hs[it]] })
    for (i in 0 until reached.size) { val h = reached[i]; dist[h] = Float.MAX_VALUE; via[h] = Hex.NONE }
    reached.clear()
    return out
}

/** A search's heap entry: its effort's bits above its hex, so the heap pops the least effort first, of equals the lower hex. */
private fun packed(d: Float, h: Hex): Long = (d.toRawBits().toLong() shl 32) or h.i.toLong()

/** Each core's working arrays for [searchFrom], left clean after each search. */
internal class ReachScratch(size: Int) {
    val dist = FloatArray(size) { Float.MAX_VALUE }; val via = HexArray(size)
    val reached = HexList(); val queue = LongHeap()
}
