package inflation.ecology

import inflation.world.Hex
import inflation.world.get
import inflation.world.Weather
import kotlin.math.min
import kotlin.math.pow

/*
 * Movement: every going of people between hexes goes by the ground and leaves its mark (Olli, 14 Nov 2026: all movement is
 * handled alike, by the general movement logic that always weighs the terrain and leaves paths).
 *
 *  - [findWay]: the least-effort way between two hexes this step (slope, broken ground, rivers and fords, cliffs, snow and
 *    worn paths, as the groups' going weighs every border: [GroupPlanner.stepEffort]), in km of easy going.
 *  - [trip]: some of a band's people go there and back (a visit, a meeting, a raid, a feast, a rite): the way wears by how
 *    many went, how often, and the walk is charged to the band ([WalkPaid]).
 *  - [walkOver] / [moveCamp]: a whole band goes, one way (a camp moved, a daughter band's new home, households to kin, a
 *    band joining another).
 *
 * A new kind of going calls one of these: none is costed as the crow flies, none is free, and none leaves no path.
 */

/** A way found by the ground ([findWay]): its km of easy going and its hexes, first to last. */
internal class Way(val km: Float, val hexes: List<Hex>)

/** Who gives the days of a trip's walk ([trip]). */
internal enum class WalkPaid {
    /** Taken from the band's next step's days before other work ([Band.raidWalkOwed]). */
    NEXT_STEP,
    /** From the band's rest left over after its work, as its customs are ([Band.visitOwed]). */
    SPARE_TIME,
    /** The caller charges it itself (a rite's days hold its walk: [riteDays]). */
    BY_CALLER,
}

/** The default reach of a [findWay] search: three times the straight distance and two hexes more, km of easy going. */
internal fun HexGroups.wayReach(from: Hex, to: Hex): Float = (3.0 * hex.distanceKm(from, to) + 2.0 * hex.hexKm).toFloat()

/**
 * Band [g]'s least-effort way from hex [from] to hex [to] this step, searched no further than [most] km of easy going; null
 * if there is no way there by land. (The nearest first; of equals, the lower hex, so the search is the same on every run.)
 */
internal fun HexGroups.findWay(g: HexGroup, from: Hex, to: Hex, weather: Weather, most: Float = wayReach(from, to)): Way? {
    if (from == to) return Way(0f, listOf(from))
    val effort = planner.stepEffort(Catalogue.travel(g.species).ordinal, hw.placeBand[g.place], weather)
    val best = HashMap<Hex, Pair<Float, Hex>>(); best[from] = 0f to Hex.NONE
    val q = java.util.PriorityQueue<Pair<Float, Hex>>(compareBy<Pair<Float, Hex>> { it.first }.thenBy { it.second.i }); q += 0f to from
    while (q.isNotEmpty()) {
        val (d, h) = q.poll()
        if (h == to) return Way(d, wayTo(best, from, to))
        if (d > best.getValue(h).first) continue
        for (k in 0 until 6) {
            val nh = hex.neighbour(h, k); if (nh.none) continue
            val nd = d + effort[hex.edge(h, k)]
            if (!nd.isFinite() || nd > most) continue
            val was = best[nh]
            if (was == null || nd < was.first) { best[nh] = nd to h; q += nd to nh }
        }
    }
    return null
}

/** The km of easy going of [findWay]'s way, null if none: for a reckoning that walks nowhere (a report, a choice weighed). */
internal fun HexGroups.wayKm(g: HexGroup, from: Hex, to: Hex, weather: Weather, most: Float = wayReach(from, to)): Float? =
    findWay(g, from, to, weather, most)?.km

/**
 * [people] of band [g] go from its hex to hex [to] and back, [times] over, by the ground ([findWay], within [most]): the way
 * wears ([wearWay]) and the walk is charged as [paid] says ([visitDays]). Returns the way's km of easy going one way, null if
 * there is no way there (and nothing is worn or charged).
 */
@IgnorableReturnValue internal fun HexGroups.trip(g: HexGroup, to: Hex, weather: Weather, people: Float, paid: WalkPaid, times: Int = 1,
                            most: Float = wayReach(hw.placeHex[g.place], to)): Float? {
    val way = findWay(g, hw.placeHex[g.place], to, weather, most) ?: return null
    tripAlong(g, way, people, paid, times)
    return way.km
}

/** As [trip], along a way already found (a search that found many at once, [effortsFrom]). */
internal fun HexGroups.tripAlong(g: HexGroup, way: Way, people: Float, paid: WalkPaid, times: Int = 1) {
    if (way.hexes.size < 2 || times <= 0) return
    wearWay(way, people * g.species.massKg, times, back = true)
    val b = g.band ?: return
    val owed = visitDays(people * times, way.km.toDouble())
    when (paid) {
        WalkPaid.NEXT_STEP -> b.raidWalkOwed += owed
        WalkPaid.SPARE_TIME -> b.visitOwed += owed
        WalkPaid.BY_CALLER -> {}
    }
}

/**
 * Band [g] goes whole from its hex to hex [to], one way, by the ground: the way wears for all its people and its grown give
 * the days of the walk, from its next step's ([Band.raidWalkOwed]). Its place is left as it was (a band that joins another
 * brings what it carries, [joinBand]). Returns the way, null if there is no way there (and it does not go).
 */
@IgnorableReturnValue internal fun HexGroups.walkOver(g: HexGroup, to: Hex, weather: Weather): Way? {
    val way = findWay(g, hw.placeHex[g.place], to, weather) ?: return null
    if (way.hexes.size < 2) return way
    wearWay(way, g.size * g.species.massKg, 1, back = false)
    g.band?.let { b -> b.raidWalkOwed += g.adults * way.km / (WALK_KM_PER_HOUR * Activity.WORK_HOURS) }
    return way
}

/** As [walkOver], and band [g] makes its camp in hex [to]. Returns whether it went (false: no way there). */
@IgnorableReturnValue internal fun HexGroups.moveCamp(g: HexGroup, to: Hex, weather: Weather): Boolean {
    if (walkOver(g, to, weather) == null) return false
    g.place = hw.firstPlaceIn(to)
    return true
}

/**
 * [kg] of people went along [way], [times] over (and back, if [back]): each border wears as a group of that weight going
 * through wears it ([PATH_WEAR], [PATH_MASS_KG]), the goings compounded as one after another would. Only with bands that
 * hold their home hex ([inflation.Tuning.bandsStay]): roaming groups wear the ways they walk as they walk them ([PathWear.week]).
 */
internal fun HexGroups.wearWay(way: Way, kg: Float, times: Int, back: Boolean) {
    if (!tuning.bandsStay || way.hexes.size < 2 || times <= 0) return
    val w = PATH_WEAR * min(1f, kg / PATH_MASS_KG)
    wear.along(way.hexes, 1f - (1f - w).pow(times), back)
}

/** The person-days of [people] walking [km] of easy going and back. */
internal fun visitDays(people: Float, km: Double) = people * (2.0 * km / (WALK_KM_PER_HOUR * Activity.WORK_HOURS)).toFloat()

/**
 * Band [from] joins band [into] ([joinBand]); from another hex it first walks over to it by the ground ([walkOver]), and with
 * no way there it does not join. Returns whether it joined.
 */
internal fun HexGroups.joinOver(into: HexGroup, from: HexGroup, weather: Weather, week: Int): Boolean {
    val to = hw.placeHex[into.place]
    if (hw.placeHex[from.place] != to && walkOver(from, to, weather) == null) return false
    joinBand(into, from, week)
    return true
}
