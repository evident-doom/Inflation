package inflation.ecology

import inflation.monthsFrom
import inflation.wrapMonth
import inflation.world.Date
import inflation.world.Hex
import inflation.world.HexArray
import inflation.world.Place
import inflation.world.Weather
import inflation.world.get
import inflation.world.toHexArray
import kotlin.math.sqrt

// Packs that hold their ground (docs/design/pack-performance.md, "Territory wider than a hex"): with
// [inflation.Tuning.packsStay], a content pack on its home hex weighs no place and stays, as bands that hold their home hex
// do ([staysHome]); its territory is a footprint, the hexes within its range of home, which it hunts and marks by nearness,
// the same at any step length. Only restless packs (the homeless, the hungry, the thirsty, the astray, the pressed) and those
// away from home weigh their places as before.

/**
 * Whether pack [g] holds its ground this step ([inflation.Tuning.packsStay]): it is on its home hex, not restless
 * ([restless]: fed, has ground, not thirsty, not astray, not pressed by people) and warned of no peril there, and nobody is
 * steering it. Its choice to stay is then made without weighing any place ([groundPlan]).
 */
internal fun GroupPlanner.holdsGround(g: HexGroup, weather: Weather): Boolean {
    if (!gs.tuning.packsStay || g.band != null || g.species.lifeway != Lifeway.PACK || g.home.none) return false
    val here = gs.hw.placeHex[g.place]
    if (gs.perilAhead(here) > 0f) return false
    // (Off its home hex, a weekly pack weighs its places and is back within a week or so; over a step of several ticks it would
    // be back for most of the step, so it goes home and holds its ground there, if home is within the step's content reach and
    // safe. trace751: 60-day packs planned off home 29% of steps against 2% weekly, and so hunted no footprint for the whole
    // step: 51-67% of the steps of the lean 60-day windows.)
    if (here != g.home) {
        if (gs.ticks <= 1 || gs.perilAhead(g.home) > 0f) return false
        if (gs.hex.distanceKm(here, g.home) > g.species.dailyTravelKm * SHIFT_DAYS * gs.stepShift) return false
    }
    if (gs.control != null && g.id == gs.watched) return false
    val s = g.species
    val thirsty = g.dry > 0.5f * s.daysWithoutWater
    val astray = gs.bearings.strength(gs.kindSlot[s.index], here) > ASTRAY
    return !gs.restless(g, weather, thirsty, astray, false)
}

/**
 * The step of a pack that holds its ground ([holdsGround]): it stays at its place on its home hex, and its territory's
 * footprint is laid out for its hunting and marking ([footprint]). Its choice is not weighed, so nothing of it is learned
 * from: the hunters' network learns from the weeks a pack is restless only.
 */
internal fun GroupPlanner.groundPlan(g: HexGroup, date: Date): GroupPlanner.Plan {
    g.safestPeril = Float.NaN; g.drawn = false; g.weighed = false; g.plan = null
    planCounts.incrementAndGet(g.species.index * 3)
    val here = gs.hw.placeHex[g.place]
    if (here == g.home) { footprint(g, date, g.place); return GroupPlanner.Plan(g.place, HexArray(intArrayOf(g.home.i))) }
    // (Off its home hex at a long step ([holdsGround]): it goes home, to the place there nearest its height.)
    val at = gs.hw.placeNear(g.place, g.home)
    footprint(g, date, at)
    return GroupPlanner.Plan(at, HexArray(intArrayOf(here.i, g.home.i)))
}

/**
 * Lays out pack [g]'s footprint ([HexGroup.footprint]): its home hex and every hex with ground within its range of home,
 * each weighted by nearness, 1 at home falling off to a hex's width beyond the range. Its range is its kind's
 * ([GroupWorld.territoryKm]) as wide as the game on its home ground needs it (wider where game is thin: half to three times,
 * as the planner reckons a pack's ground), or [inflation.Tuning.denKm] while it has young in the den.
 */
internal fun GroupPlanner.footprint(g: HexGroup, date: Date, at: Place = g.place) {
    val s = g.species
    val kind = gs.kindSlot[s.index]
    val month = wrapMonth(date.month + if (date.dayOfMonth > Date.DAYS_PER_MONTH / 2) 1 else 0)
    val denning = g.young > 0 && monthsFrom(g.birthMonth, month) < gs.tuning.denMonths
    val homeValue = placeValue[kind][at]
    val range = if (denning) gs.tuning.denKm.toDouble() else
        (gs.territoryKm(s) * sqrt(PREY_HALF / homeValue.coerceAtLeast(1f)).coerceIn(0.5f, 3f)).toDouble()
    val fall = range + gs.hex.hexKm
    val today = date.absoluteDay
    // (Its yieldings that have run out are forgotten.)
    g.yielded = g.yielded?.filter { (it and 0xFFFFFFFFL) > today }?.toLongArray()?.takeIf { it.isNotEmpty() }
    val hexes = ArrayList<Hex>(); val weights = ArrayList<Float>()
    hexes += g.home; weights += 1f
    val seen = HashSet<Hex>().also { it += g.home }
    var ring: List<Hex> = listOf(g.home)
    while (true) {
        val next = gs.hex.nextRing(ring, seen)
        var any = false
        for (h in next) {
            val d = gs.hex.distanceKm(h, g.home)
            if (d > range) continue
            any = true
            // (Not where it gave way lately, [gaveWay].)
            if (gs.hw.placeNear(at, h).none || yields(g, h, today)) continue
            hexes += h; weights += (1.0 - d / fall).toFloat()
        }
        if (!any) break
        ring = next
    }
    g.footprint = hexes.toHexArray(); g.footWeights = weights.toFloatArray()
}

/**
 * The ground a pack that holds its ground ([holdsGround]) searches for game this step with [km2] of search: the places of its
 * footprint [hexes], each its share of the search by its [weights], as far as the place goes ([ticks] times its area over the
 * step), and what a place cannot take shared again among the others by their weights. What is left after all are full is
 * the same ground searched again, which finds no more game ([huntGround] for a pack on the move).
 */
internal fun GroupWorld.footGround(g: HexGroup, hexes: HexArray, weights: FloatArray, km2: Float): HuntGround {
    val area = land.areaKm2
    val places = IntArray(hexes.size); val w = FloatArray(hexes.size); var m = 0
    for (i in 0 until hexes.size) {
        val p = hw.placeNear(g.place, hexes[i]).takeIf { !it.none } ?: continue
        places[m] = p.i; w[m] = weights[i]; m++
    }
    val swept = FloatArray(m)
    var left = km2
    var open = 0f; for (i in 0 until m) open += w[i]
    while (left > 1e-3f && open > 0f) {
        val each = left / open; var nextOpen = 0f
        for (i in 0 until m) {
            if (w[i] <= 0f) continue
            val room = area[places[i]] * ticks - swept[i]; if (room <= 0f) continue
            val s = kotlin.math.min(room, each * w[i]); swept[i] += s; left -= s
            if (s < room) nextOpen += w[i]
        }
        open = nextOpen
    }
    return HuntGround(places.copyOf(m), swept)
}
