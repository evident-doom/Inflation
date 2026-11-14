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
import kotlin.math.max
import kotlin.math.min

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

    /** Each hex within [most] and its km of easy going, in index order. */
    inline fun forEachWithin(most: Float, f: (Hex, Float) -> Unit) { for (i in 0 until hexes.size) if (dist[i] <= most) f(hexes[i], dist[i]) }

    /**
     * This reach as far as [most] only: the same answers within it, and its [hexes] only those within it, whatever search
     * it came from ([reachOf]). The ways to them stay within it: each step of a way is no further than the hex it leads to.
     */
    fun within(most: Float): Reach {
        if (most >= reach) return this
        var n = 0; for (d in dist) if (d <= most) n++
        val hs = HexArray(n); val ds = FloatArray(n); val vs = HexArray(n)
        var k = 0
        for (i in 0 until hexes.size) if (dist[i] <= most) { hs[k] = hexes[i]; ds[k] = dist[i]; vs[k] = via[i]; k++ }
        return Reach(from, most, hs, ds, vs)
    }
}

/**
 * The ground about hex [from] for a group of kind [s] on height band [band], out to at least [most] km of easy going (as
 * far as the caller asks; no limit of its own): a search already made over the ground as it lies now, if one went as far,
 * else a new one (then kept, [GroupPlanner.reachCache], until the groups next choose and the ground's going is reckoned
 * afresh), taken as far as [most] only ([Reach.within]): so what a caller gets is the same whichever searches were made
 * before it, on whichever core, and a caller that goes over all its [Reach.hexes] sees the same hexes on every run.
 * Safe on all cores: two that search at once keep the one that went further, and their answers agree within the shorter.
 */
@Part("reach: least-effort search")
internal fun HexGroups.reachOf(s: Species, band: Int, from: Hex, most: Float, weather: Weather): Reach =
    searchedAtLeast(s, band, from, most, weather).within(most)

/**
 * A search of the ground about [from] that went at least [most] km ([reachOf]), perhaps further, as it is kept: for a caller
 * that reads it only within [most] itself ([Reach.km] and [Reach.wayTo] with [most], [Reach.forEachWithin]), sparing the
 * copy [Reach.within] makes. What it reads so is the same on every run.
 */
internal fun HexGroups.searchedAtLeast(s: Species, band: Int, from: Hex, most: Float, weather: Weather): Reach {
    val key = (s.index.toLong() * 8 + band) * hex.size + from.i
    planner.reachCache[key]?.let { if (it.reach >= most) return it }
    val mine = searchFrom(s, band, from, most, weather)
    return planner.reachCache.merge(key, mine) { a, b -> if (a.reach >= b.reach) a else b } ?: mine
}

/** The km of easy going from [from] to [to] for group [g], if within [most] ([reachOf]); else null. A point query: no copy is made. */
internal fun HexGroups.goingKm(g: HexGroup, from: Hex, to: Hex, most: Float, weather: Weather): Float? =
    searchedAtLeast(g.species, hw.placeBand[g.place], from, most, weather).km(to, most)

/**
 * How far hex [h] is from the [goal] group [g] is drawn to, for the pulls toward goals (its season's place, its appointment,
 * a raid's target, salt, what it lacks: [toward]): by the going within [GOAL_GOING_KM] of the goal, searched from the goal
 * ([goingKm]); beyond that as the crow flies, but never nearer than [GOAL_GOING_KM]. So a pull toward a place beyond a strait
 * or a ridge points along the way there, not across it; a goal much further off points the way it lies.
 */
internal fun HexGroups.goalKm(g: HexGroup, goal: Hex, h: Hex, weather: Weather): Double =
    goingKm(g, goal, h, GOAL_GOING_KM, weather)?.toDouble() ?: max(GOAL_GOING_KM.toDouble(), hexKm(h, goal))

/**
 * How far going from [here] to [h] brings group [g] toward [goal], -1 to 1, against [scale] km (by default the whole way):
 * by [goalKm]. The one measure of every pull toward a goal; 0 where [here] is the goal.
 */
internal fun HexGroups.toward(g: HexGroup, goal: Hex, here: Hex, h: Hex, weather: Weather, scale: Double? = null): Float {
    val from = goalKm(g, goal, here, weather)
    if (!(from > 0.0)) return 0f
    return ((from - goalKm(g, goal, h, weather)) / (scale?.let { min(from, it) } ?: from)).toFloat().coerceIn(-1f, 1f)
}

/**
 * The nearest salt band [g] ([b]) knows ([Bands.saltKnown]), from hex [here]: by the going within [GOAL_GOING_KM] ([reachOf]
 * from [here]), beyond as the crow flies but never nearer than that, as a goal's distance is ([goalKm]): so not the one
 * across a strait when another lies along the shore.
 */
internal fun HexGroups.saltKnown(g: HexGroup, b: Band, here: Hex, weather: Weather): Hex {
    if (b.saltFrom == here) return b.saltAt
    val reach = searchedAtLeast(g.species, hw.placeBand[g.place], here, GOAL_GOING_KM, weather)
    return bands.saltKnown(b, here) { h -> reach.km(h, GOAL_GOING_KM)?.toDouble() ?: max(GOAL_GOING_KM.toDouble(), hexKm(here, h)) }
}

/** How far off a goal its pull goes by the ground ([goalKm]): six days' walk ([DAY_WALK_KM]); further, as the crow flies. An estimate. */
internal const val GOAL_GOING_KM = 6f * DAY_WALK_KM

/**
 * How far a band looks for kin to join or to take refuge with ([mergeAndPair], [scatterBeaten]): a day's walk ([DAY_WALK_KM]),
 * km of easy going.
 */
internal fun GroupWorld.kinReachKm(): Float = DAY_WALK_KM

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
