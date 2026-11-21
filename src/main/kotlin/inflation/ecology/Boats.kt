package inflation.ecology

import inflation.world.Hex
import inflation.world.HexArray
import inflation.world.HexMap
import inflation.world.LeastEffort
import inflation.world.Weather
import inflation.world.get
import inflation.world.toHexArray
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Boats (Olli 2026-11-21). People cross water as the animals do ([Catalogue.swimKm], the straits at [SWIM_EFFORT]); bands
 * settled by water a boat could use know how to make and handle a simple boat ([boatWater], [learnBoats]), and practice makes
 * both better. Making ([Skill.BOATBUILDING], how well the boats are made) and handling ([Skill.PADDLING]) are dials, the
 * designs not named (dugout, bark or skin boat), so the band's going by water is one number, [Band.boating], 0..1.
 *
 * What it opens is more ways in the one search of the ground ([reachOf], [boatEdges]), so every trip, camp move, meeting,
 * goal and daughter band takes them as it takes the land's, charged in km of easy going and worn as paths are:
 *  - rivers too deep to wade are ferried over;
 *  - open water between two coasts within sight of each other is crossed in calm weather, no wider than one hex of water at
 *    the most skilled ([BOAT_GAP_KM]): coastal going only, no seafaring (Olli 2026-11-21: seafaring needs other skills);
 *  - with more skill, a river big enough to float a boat is travelled down and up, and the coast along, as the water and
 *    the weather allow (not in flood, not in a dry bed, not in a storm);
 *  - boats take fishers out past the shore ([Band.fishing]).
 * Every number here is a first estimate, to be tuned against the record and the runs.
 */

/** Steps of [Band.boating] the searches of the ground tell apart ([Band.craft]): each step that bands hold is a search of its own. */
internal const val CRAFT_LEVELS = 4

/** Open water a fully able crew in a good boat crosses in calm weather, km: one hex of water, within sight of both shores. */
internal const val BOAT_GAP_KM = 10f

/** Effort of a km paddled over calm open water against a km walked on easy ground (a laden boat in practised hands goes about as fast as a walker). */
internal const val PADDLE_EFFORT = 1.5f

/** Effort of getting a band and its loads over a river by boat, in km of easy going, by an unpractised crew; a practised one in half that. */
internal const val FERRY_KM = 2f

/** How much of [Band.boating] it takes to travel rivers and along the coast rather than only to cross. */
internal const val BOAT_TRAVEL = 0.5f

/** The least flow a river carries a boat on, m³/s; a river this many times its mean flow is in flood, too strong to travel. */
internal const val NAVIGABLE_M3S = 5f
internal const val FLOOD_FLOW = 3f

/** Weather calm enough for open water and the coast: a regime less stormy than this ([inflation.world.Regime.storminess]). */
internal const val CALM_STORMINESS = 1f

/** How much more fish a boat in practised hands brings in than fishing from the shore alone, at its best. */
internal const val BOAT_FISH_GAIN = 0.5f

/** The level of both boat skills a band settled by boat water knows them at, at the least ([learnBoats]). */
internal const val BOAT_BASE = 0.2f

/** [b]'s going by water in [CRAFT_LEVELS] steps ([Band.craft]). */
internal fun craftLevel(b: Band): Int = (b.boating() * CRAFT_LEVELS).roundToInt().coerceIn(0, CRAFT_LEVELS)

/** Group [g]'s step of going by water: its band's ([Band.craft]), 0 for all that are not people. */
internal fun craftOf(g: HexGroup): Int = g.band?.craft ?: 0

/**
 * Whether hex [h] lies by water a boat could use: a lake or the sea beside it, or a river through it that carries a boat at
 * its usual flow ([NAVIGABLE_M3S]).
 */
internal fun GroupWorld.boatWater(h: Hex): Boolean {
    if (hex.heart[h] < 0) return false
    if (land.hydro.meanFlow[h] >= NAVIGABLE_M3S) return true
    for (d in 0 until 6) { val q = hex.neighbour(h, d); if (!q.none && hex.heart[q] < 0) return true }
    return false
}

/**
 * A band [b] settled at hex [h] (camped long enough to build: [houseAfterWeeks]) by water a boat could use ([boatWater]) knows
 * how to make and handle a simple boat, at [BOAT_BASE] at the least (Olli 2026-11-21: people settled near water have a base in
 * boatmaking). Elsewhere the ways spread only by teaching, and fade unused.
 */
internal fun GroupWorld.learnBoats(b: Band, h: Hex) {
    b.byBoatWater = boatWater(h)
    if (b.campWeeks < houseAfterWeeks(b) || !b.byBoatWater) return
    for (s in BOAT_SKILLS) if (!b.knowsSkill[s]) {
        b.knowsSkill[s] = true; b.skills[s] = maxOf(b.skills[s], BOAT_BASE); b.lastUsed[s] = b.weeks
        stats.meeting(Met.Of.HIT_ON, s.name.lowercase())
    }
    if (!b.knows[Tool.BOAT]) { b.knows[Tool.BOAT] = true; stats.meeting(Met.Of.HIT_ON, Tool.BOAT.name.lowercase()) }
}

internal val BOAT_SKILLS = listOf(Skill.BOATBUILDING, Skill.PADDLING)

/**
 * Whether [way] goes over water: a strait (a step between hexes that are not neighbours: across open water, or down or up a
 * river), or a river over the knee on a border it crosses.
 */
internal fun HexGroups.overWater(way: Way): Boolean {
    for (i in 0 until way.hexes.size - 1) {
        val a = way.hexes[i]; val b = way.hexes[i + 1]
        val d = (0 until 6).firstOrNull { hex.neighbour(a, it) == b } ?: return true
        if (hex.riverDepthM[hex.edge(a, d)] > HexMap.KNEE) return true
    }
    return false
}

/**
 * The ways by water from hex [h], reached at [at], that a search ([reachOf]) for people going by water at step [craft] of
 * [CRAFT_LEVELS] offers [le] besides the land's ([effort]: this week's going over each border, [travel] the way of going whose
 * ground it reads), out to [most]. Animals and people without boats have none of these; the straits they swim are the search's own.
 */
internal fun HexGroups.boatEdges(le: LeastEffort, h: Hex, at: Float, craft: Int, travel: Int, effort: FloatArray, weather: Weather, most: Float) {
    val c = craft.toFloat() / CRAFT_LEVELS
    val calm = weather.regime.storminess < CALM_STORMINESS
    // Rivers: ferried over, the ground's going on either bank and the crossing (the week's snow and paths aside).
    val ground = hex.ground[travel]
    for (k in 0 until 6) {
        val q = hex.neighbour(h, k); if (q.none || hex.heart[q] < 0) continue
        val e = hex.edge(h, k)
        if (hex.riverDepthM[e] <= HexMap.KNEE || !ground[e].isFinite()) continue
        val over = ground[e] + FERRY_KM * (1f - 0.5f * c)
        if (over < effort[e]) le.offer(h, q, at + over, most)
    }
    // Open water within sight of both shores, in calm weather.
    if (calm) {
        val reach = BOAT_GAP_KM * c
        val links = seaLinks[h]; val gaps = seaGaps[h]
        for (i in links.indices) if (gaps[i] <= reach) le.offer(h, links[i], at + gaps[i] * PADDLE_EFFORT, most)
    }
    if (c < BOAT_TRAVEL) return
    // Down and up a river that carries a boat this week (not in flood): faster down, slower up, both easing with skill.
    val hydro = land.hydro
    fun floats(r: Hex) = hydro.flow[r] >= NAVIGABLE_M3S && hydro.flow[r] <= FLOOD_FLOW * hydro.meanFlow[r]
    val down = hydro.down[h]
    if (!down.none && hex.heart[down] >= 0 && floats(h))
        le.offer(h, down, at + hex.distanceKm(h, down).toFloat() * (1f - 0.6f * c), most)
    for (u in upstream[h]) if (hex.heart[u] >= 0 && floats(u))
        le.offer(h, u, at + hex.distanceKm(h, u).toFloat() * (2f - 0.8f * c), most)
    // Along the coast, from shore to shore, in calm weather.
    if (calm && hw.coast[h]) for (k in 0 until 6) {
        val q = hex.neighbour(h, k); if (q.none || hex.heart[q] < 0 || !hw.coast[q]) continue
        le.offer(h, q, at + hex.hexKm.toFloat() * (2f - c), most)
    }
}

/** For each hex, the hexes whose main river flows into it ([inflation.world.HexHydrology.down]). */
internal fun upstreamOf(down: HexArray, hex: HexMap): Array<HexArray> {
    val ups = Array(hex.size) { ArrayList<Hex>(2) }
    for (h in hex.all) { val d = down[h]; if (!d.none) ups[d.i] += h }
    return Array(hex.size) { ups[it].toHexArray() }
}

/** What fish boats would bring in more, as the planners weigh a whole kit against what it has ([PlanDays] toolRates): [fishKg] a year with [k] of a kit. */
internal fun boatFishMore(fishKg: Float, k: Float, paddling: Float, perDay: Float): Float {
    if (perDay <= 0f || fishKg <= 0f) return 0f
    val now = 1f + BOAT_FISH_GAIN * min(1f, k) * paddling; val whole = 1f + BOAT_FISH_GAIN * paddling
    return fishKg * (whole / now - 1f) / perDay
}
