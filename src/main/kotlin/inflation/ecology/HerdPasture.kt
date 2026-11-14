package inflation.ecology

import inflation.world.Date
import inflation.world.Hex
import inflation.world.Place
import inflation.world.Weather
import inflation.world.get

// Where a band's herd is ([placeHerd]): its herders take it out by day to the best grazing within a day's going of where it
// is kept, and bring it back at night; in summer a band whose camp lies low takes it up to an upland pasture for the season,
// its herders with it, the rest of the band staying at camp (transhumance: the Zagros herders' summer moves to the
// mountains, and the vertical moves of Mediterranean pastoralists; the oldest such herding by the 7th millennium BCE, from
// memory). Before, a kept herd grazed only the camp's own place, and beasts of prey met it only there.

/**
 * Sets where band [g]'s herd ([b]) is kept this week ([Herd.at]) and where it grazes by day ([Herd.grazeAt]): kept at its
 * camp, or in summer ([SUMMER_MONTHS]) for a band whose camp lies low at an upland pasture ([summerPasture]), staying there
 * through the season once there; grazing by day the place with the most forage within [HERD_DAY_KM] of going of where it is
 * kept ([bestGrazing]), where it is kept if none has more. When it is kept in another hex than last week, its herders drive
 * it there by the ground ([findWay]): the way wears for the herd and its herders, and the herders give the walk's days from
 * the band's next step ([Band.raidWalkOwed]). Worked out afresh each week from where it was, so nothing of it is saved: a
 * loaded world's herds start the week at camp.
 */
internal fun HexGroups.placeHerd(g: HexGroup, b: Band, date: Date, weather: Weather) {
    val camp = g.place
    val was = b.herds.at
    val keep = summerPasture(g, b, date, weather, was) ?: camp
    if (!was.none && hw.placeHex[was] != hw.placeHex[keep]) {
        findWay(g, hw.placeHex[was], hw.placeHex[keep], weather)?.let { way ->
            val herders = herdDays(b) / days.coerceAtLeast(1)
            wearWay(way, herders * g.species.massKg + herdKg(b), 1, back = false)
            b.raidWalkOwed += herders * way.km / DAY_WALK_KM
            stats.meeting(Met.HERDS_DRIVEN_TO_PASTURE)
        }
    }
    b.herds.at = keep
    b.herds.grazeAt = bestGrazing(g, keep, weather)
}

/**
 * The upland pasture band [g]'s herd is kept at this week, if it is summer ([SUMMER_MONTHS]) and there is one: of the places
 * within [SUMMER_PASTURE_KM] of going of its camp ([reachOf]) and [UPLAND_M] or more above it, the one with the most forage
 * ([pastureForage]; of equals, the first by hex and place); the one it is at already ([was]) while that still is one. Null out of
 * summer, or with none in reach.
 */
internal fun HexGroups.summerPasture(g: HexGroup, b: Band, date: Date, weather: Weather, was: Place): Place? {
    // (By the camp's own seasons, [inflation.world.HexLand.localMonth]: up as its grass dries.)
    if (land.localMonth(g.place, date.month) !in SUMMER_MONTHS || b.herds.herd.all { it <= 0f }) return null
    val camp = g.place; val home = hw.placeHex[camp]
    val low = hw.placeElevationM[camp.i]
    fun upland(q: Place) = hw.placeElevationM[q.i] >= low + UPLAND_M
    val reach = searchedAtLeast(g.species, hw.placeBand[camp], home, SUMMER_PASTURE_KM, weather)
    if (!was.none && hw.placeHex[was] != home && upland(was) && reach.km(hw.placeHex[was], SUMMER_PASTURE_KM) != null) return was
    var best = Place.NONE; var most = 0f
    reach.forEachWithin(SUMMER_PASTURE_KM) { h, _ ->
        for (qi in hw.placesOf(h)) { val q = Place(qi); if (!upland(q)) continue; val f = pastureForage(q); if (f > most) { most = f; best = q } }
    }
    return best.takeIf { !it.none }
}

/**
 * The place a herd kept at [kept] grazes by day: the one with the most forage ([pastureForage]) within [HERD_DAY_KM] of going
 * of it, by the herders' going (band [g]'s kind), [kept] itself if none has more (of other equals, the first by hex and place).
 */
internal fun HexGroups.bestGrazing(g: HexGroup, kept: Place, weather: Weather): Place {
    var best = kept; var most = pastureForage(kept)
    searchedAtLeast(g.species, hw.placeBand[kept], hw.placeHex[kept], HERD_DAY_KM, weather).forEachWithin(HERD_DAY_KM) { h, _ ->
        for (qi in hw.placesOf(h)) { val q = Place(qi); val f = pastureForage(q); if (f > most) { most = f; best = q } }
    }
    return best
}

/** The forage a herd finds at place [p], kg/m²: the palatable grass and the browse ([herdForage] reckons a herd's share of them). */
internal fun GroupWorld.pastureForage(p: Place): Float = land.palatable[p] * land.plants.herb[p] + land.plants.browse(p)

/** The live weight of band [b]'s herd, grown and young, kg. */
internal fun GroupWorld.herdKg(b: Band): Float {
    val young = b.herds.youngHerd(); var kg = 0f
    for (k in b.herds.herd.indices) kg += (b.herds.herd[k] + HexFauna.YOUNG_WEIGHT * young[k]) * fauna.kinds[k].massKg
    return kg
}

/** The months a herd is taken up to summer pasture ([summerPasture]): June to September (0-based), up after the lowland grass dries, down before the snow. */
internal val SUMMER_MONTHS = 5..8
/** How far a band takes its herd to summer pasture, km of easy going ([summerPasture]): two and a half days' walk ([DAY_WALK_KM]), a few days' drive. An estimate. */
internal const val SUMMER_PASTURE_KM = 2.5f * DAY_WALK_KM
/** How much higher than its camp a summer pasture lies, m ([summerPasture]): enough for the grass to stay green. An estimate. */
internal const val UPLAND_M = 600f
/** How far herders take a herd out by day and back, km of easy going ([bestGrazing]): a day's grazing round of some 10 km. An estimate. */
internal const val HERD_DAY_KM = 5f
