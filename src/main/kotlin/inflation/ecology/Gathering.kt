package inflation.ecology

import inflation.world.HexWorld
import inflation.world.Weather
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * Kinds that live both ways ([Catalogue.gathering]): one population, spread over the land as residents, out of which
 * groups set off where the place they live in fails them. Residents drift to better ground next door on their own (the
 * spill, see [HexFauna]); a group is the long walk they cannot make that way. Each week, the residents of a place weigh
 * the share of them that would die there next week ([HexFauna.stayOdds]: hunger, cold, thirst, disease, fire, flood,
 * snow, by the rules they die by) against the share that would die at the safest place a group of them could reach in
 * a month, beyond their own ground ([HexFauna.arriveOdds]); where staying is clearly the deadlier ([clearlyWorse]), they
 * leave together, as many groups as they make, however few. A group that stands where it is no longer clearly worse off
 * than anywhere within a month's walk settles back into the land a band a week; elsewhere it takes in the residents
 * about it that are leaving too. Nothing holds a group together but the need: heads, young, fat, feeding, protein and
 * thirst go across as they are, and being moved from one form to the other changes nothing by itself.
 */

/** The ground a herd condenses from, and keeps away from until it scatters: half a range across (see [RANGE_KM]). */
const val GATHER_KM = RANGE_KM / 2

/**
 * The ground residents of [s] leave from together, and their own ground, beyond which a group goes: as far as the kind
 * walks in a week (a gazelle some 40 km, an onager 100).
 */
internal fun gatherKm(s: Species) = 7.0 * s.dailyTravelKm

/** How far a group of [s] looks for a safer place: a month's walk. */
internal fun farKm(s: Species) = 30.0 * s.dailyTravelKm

/** The most heads one herd of [s] holds: ten bands (see [HERD_UNIT]); more leaving together make more herds. */
internal fun herdOf(s: Species) = s.maxGroupSize * HERD_UNIT

/**
 * Whether a band of [band] animals dying at a share [here] in a week is clearly worse off than at a share [there]: by
 * more than a week's chance spread of its deaths (the binomial deviation at [here]), as groups weigh a place's deaths
 * (see [HexGroups.survivalSteps]).
 */
internal fun clearlyWorse(here: Float, there: Float, band: Float): Boolean {
    if (!(there < here)) return false
    val sd = sqrt(band * here * (1f - here))
    return (here - there) * band > sd
}

/**
 * Each week, before herds scatter and gather: for each gathering kind, the share of a band that would die at each place
 * on coming there ([HexFauna.arriveOdds], NaN where it cannot stay), kept in [arriveBy]; and which residents leave
 * ([leaving]): about each place where the most would die staying, the residents of the ground within [gatherKm] whose
 * place is clearly deadlier ([clearlyWorse]) than the safest place within [farKm] beyond that ground, which they head
 * would head for.
 */
internal fun HexGroups.leaveAhead(weather: Weather) {
    val out = FloatArray(HexFauna.HZ_COUNT)
    for (s in Catalogue.gathering) {
        val k = fauna.slot(s); if (k < 0 || s !in kinds) continue
        val a = fauna.adults[k]; val y = fauna.young[k]
        val band = s.maxGroupSize.toFloat()
        val arrive = FloatArray(n) { fauna.arriveOdds(k, it, band, weather, out) }
        arriveBy[s] = arrive
        // (Staying, by place; and by hex, the heads that might leave: those whose place could be beaten at all.)
        val stay = FloatArray(n)
        val byHex = DoubleArray(hex.size)
        for (p in 0 until n) {
            val heads = (a[p] + y[p]) * land.areaKm2[p].toDouble()
            if (heads <= 0.0) continue
            stay[p] = fauna.stayOdds(k, p, weather, out)
            if (clearlyWorse(stay[p], 0f, band)) byHex[hw.placeHex[p]] += heads * stay[p]
        }
        val leave = BooleanArray(n)
        val used = BooleanArray(hex.size)
        val leavingByHex = DoubleArray(hex.size)
        for (h0 in (0 until hex.size).filter { byHex[it] > 0.0 }.sortedByDescending { byHex[it] }) {
            if (used[h0]) continue
            // The safest place beyond their own ground, within a month's walk.
            var bestOdds = Float.MAX_VALUE
            for (h in ground(h0, farKm(s) / RING_KM + hex.hexKm)) {
                val d = hex.distanceKm(h0, h); if (d <= gatherKm(s) || d > farKm(s)) continue
                for (b in HexWorld.BANDS_M.indices) {
                    val q = hw.placeOf[b][h]; if (q < 0) continue
                    if (arrive[q] < bestOdds) bestOdds = arrive[q]
                }
            }
            if (bestOdds == Float.MAX_VALUE) continue
            val about = ground(h0, gatherKm(s)).filter { !used[it] }
            for (h in about) {
                used[h] = true
                for (b in HexWorld.BANDS_M.indices) {
                    val p = hw.placeOf[b][h]; if (p < 0 || a[p] + y[p] <= 0f) continue
                    if (clearlyWorse(stay[p], bestOdds, band)) { leave[p] = true; leavingByHex[h] += (a[p] + y[p]) * land.areaKm2[p].toDouble() }
                }
            }
        }
        leaving[s] = leave
        unfed[s] = leavingByHex
    }
}

/**
 * Each week: in each gathering kind, the residents leaving ([leaveAhead]) and not taken in by a herd about them
 * ([scatter]), together from about each place where they are most (within [gatherKm], each hex once a week), as many
 * herds as they make ([herdOf] at most in each); no fewer than its kind goes about in ([Species.groupSize]), else they stay.
 */
internal fun HexGroups.gather() {
    for (s in Catalogue.gathering) {
        val k = fauna.slot(s); if (k < 0 || s !in kinds) continue
        val leave = leaving[s] ?: continue
        val a = fauna.adults[k]; val y = fauna.young[k]; val f = fauna.fed[k]
        val byHex = DoubleArray(hex.size)
        for (p in 0 until n) if (leave[p]) byHex[hw.placeHex[p]] += (a[p] + y[p]) * land.areaKm2[p].toDouble()
        val used = BooleanArray(hex.size)
        var made = 0; var heads = 0
        for (h0 in (0 until hex.size).filter { byHex[it] > 0.0 }.sortedByDescending { byHex[it] }) {
            if (used[h0]) continue
            val about = ground(h0, gatherKm(s)).filter { !used[it] }
            var all = 0.0; for (h in about) all += byHex[h]
            if (all < s.groupSize) continue
            for (h in about) used[h] = true
            // Whole animals go: all of each leaving place, adults and young as they are there.
            var adults = 0.0; var young = 0.0; var fat = 0.0; var fed = 0.0; var protein = 0.0; var dry = 0.0; var most = -1; var mostHeads = 0.0
            for (h in about) for (b in HexWorld.BANDS_M.indices) {
                val p = hw.placeOf[b][h]; if (p < 0 || !leave[p]) continue
                val ga = a[p] * land.areaKm2[p].toDouble(); val gy = y[p] * land.areaKm2[p].toDouble()
                if (ga + gy <= 0.0) continue
                a[p] = 0f; y[p] = 0f
                adults += ga; young += gy
                fat += (ga + gy) * fauna.reserve[k][p]; fed += (ga + gy) * f[p]; protein += (ga + gy) * fauna.protein[k][p]; dry += (ga + gy) * fauna.dry[k][p]
                if (ga + gy > mostHeads) { mostHeads = ga + gy; most = p }
            }
            if (most < 0) continue
            val took = adults + young
            val takeA = adults.roundToInt(); val takeY = young.roundToInt()
            val count = (takeA + takeY + herdOf(s) - 1) / herdOf(s)
            if (takeA < max(1, count)) {
                // (Too few grown animals to go: they stay, put back where the most came from.)
                a[most] += (adults / land.areaKm2[most]).toFloat(); y[most] += (young / land.areaKm2[most]).toFloat()
                continue
            }
            // (What rounding leaves over goes back to where the most came from.)
            a[most] = max(0f, a[most] + ((adults - takeA) / land.areaKm2[most]).toFloat()); y[most] = max(0f, y[most] + ((young - takeY) / land.areaKm2[most]).toFloat())
            for (i in 0 until count) {
                val g = spawn(s, most, takeA / count + if (i < takeA % count) 1 else 0)
                g.young = takeY / count + if (i < takeY % count) 1 else 0
                g.fat = (fat / took).toFloat(); g.fed = (fed / took).toFloat(); g.protein = (protein / took).toFloat(); g.dry = (dry / took).toFloat()
                g.birthMonth = fauna.birthStart[k][most].toInt()
                g.formedAt = most
                inheritSeasons(g)
                groups += g
            }
            made += count; heads += takeA + takeY
        }
        if (made > 0) { stats.hunting.merge(Tally.of(s, Tally.GATHERED), made, Int::plus); stats.hunting.merge(Tally.of(s, Tally.HEADS_GATHERED), heads, Int::plus) }
    }
}

/**
 * For the viewer: by hex, how many herds of kind [s] the residents leaving within [gatherKm] of it came to this week,
 * before any went; null before a week.
 */
internal fun HexGroups.herdsWorth(s: Species): FloatArray? {
    val byHex = unfed[s] ?: return null
    val out = FloatArray(hex.size)
    // (The ground is the same both ways: each hex's leavers count towards every hex it lies within reach of.)
    for (h in byHex.indices) if (byHex[h] > 0.0) { val v = (byHex[h] / herdOf(s)).toFloat(); for (q in ground(h, gatherKm(s))) out[q] += v }
    return out
}

/**
 * Each week, a herd of a gathering kind keeps the balance with the residents of its kind about it. Where a band of it
 * would not be clearly worse off staying where it stands than at the safest place within a month's walk beyond it
 * ([settles]), one usual band of its kind ([Species.maxGroupSize]) settles there as residents, about where it stands,
 * and the last band scatters whole. Elsewhere it takes in, within a day's walk of it, the residents leaving too
 * ([leaveAhead]) and the young males that grew up there this week and leave to find their own ground (half those grown,
 * see [HexFauna.grownUp]); each place gives to one herd a week. So herds form where the land fails its residents and
 * melt back into it where it does not, a band a week.
 */
internal fun HexGroups.scatter() {
    var any = false
    for (g in groups) { g.settled = 0; g.joined = 0 }
    val taken = HashMap<Species, BooleanArray>()
    for (g in groups) {
        val s = g.species
        if (!g.alive || s !in Catalogue.gathering) continue
        val k = fauna.slot(s); if (k < 0) continue
        if (!settles(g)) { join(g, k, taken.getOrPut(s) { BooleanArray(n) }); continue }
        if (g.size <= s.maxGroupSize) {
            spread(g, k, g.adults, g.young)
            stats.hunting.merge(Tally.of(s, Tally.SCATTERED), 1, Int::plus); stats.hunting.merge(Tally.of(s, Tally.HEADS_SCATTERED), g.size, Int::plus)
            // (Its members live on spread out, not dead: so the learning counts them, see [SCATTERED].)
            g.mergedSize = g.size; g.mergedInto = SCATTERED; merged += g
            g.adults = 0; g.young = 0; any = true
        } else {
            // A band's worth, adults and young as they are in the herd.
            val young = (s.maxGroupSize.toLong() * g.young / g.size).toInt()
            val adults = s.maxGroupSize - young
            spread(g, k, adults, young)
            g.adults -= adults; g.young -= young; g.settled = adults + young
            stats.hunting.merge(Tally.of(s, Tally.HEADS_SETTLED), adults + young, Int::plus)
        }
    }
    if (any) groups.removeIf { !it.alive }
}

/**
 * Whether a band of herd [g] would stay where it stands: its place, as it would be to a band coming there
 * ([arriveBy]), is not clearly deadlier ([clearlyWorse]) than the safest place within [farKm] beyond [gatherKm] of it.
 */
private fun HexGroups.settles(g: HexGroup): Boolean {
    val s = g.species
    val arrive = arriveBy[s] ?: return false
    val here = arrive[g.place.i]
    if (here.isNaN()) return false
    val h0 = hw.placeHex[g.place.i]
    var best = Float.MAX_VALUE
    for (h in ground(h0, farKm(s) / RING_KM + hex.hexKm)) {
        val d = hex.distanceKm(h0, h); if (d <= gatherKm(s) || d > farKm(s)) continue
        for (b in HexWorld.BANDS_M.indices) { val q = hw.placeOf[b][h]; if (q >= 0 && arrive[q] < best) best = arrive[q] }
    }
    return !clearlyWorse(here, best, s.maxGroupSize.toFloat())
}

/**
 * Herd [g] takes in, within a day's walk of it, the residents (slot [k]) leaving their place this week ([leaveAhead])
 * and the young males leaving home, from the places no other herd has taken from this week ([taken]); their condition
 * mixed with its own.
 */
private fun HexGroups.join(g: HexGroup, k: Int, taken: BooleanArray) {
    val s = g.species
    val a = fauna.adults[k]; val y = fauna.young[k]; val grown = fauna.grownUp[k] ?: return
    val leave = leaving[s] ?: return
    var adults = 0.0; var young = 0.0; var fat = 0.0; var fed = 0.0; var protein = 0.0; var dry = 0.0; var most = -1; var mostHeads = 0.0
    for (p in placesWithin(g.place.i, s.dailyTravelKm.toDouble())) {
        if (taken[p]) continue
        val here = a[p] + y[p]; if (here <= 0f) continue
        taken[p] = true
        // (Per km²: all of a place that is leaving; elsewhere half the young that grew up, the males.)
        val ga = if (leave[p]) a[p] else min(a[p], 0.5f * grown[p]); val gy = if (leave[p]) y[p] else 0f
        if (ga + gy <= 0f) continue
        val area = land.areaKm2[p]
        a[p] -= ga; y[p] -= gy
        val heads = (ga + gy) * area.toDouble()
        adults += ga * area; young += gy * area
        fat += heads * fauna.reserve[k][p]; fed += heads * fauna.fed[k][p]; protein += heads * fauna.protein[k][p]; dry += heads * fauna.dry[k][p]
        if (heads > mostHeads) { mostHeads = heads; most = p }
    }
    if (most < 0) return
    val took = adults + young
    val takeA = adults.roundToInt(); val takeY = young.roundToInt()
    // (What rounding leaves over stays where the most came from.)
    a[most] = max(0f, a[most] + ((adults - takeA) / land.areaKm2[most]).toFloat()); y[most] = max(0f, y[most] + ((young - takeY) / land.areaKm2[most]).toFloat())
    if (takeA + takeY == 0) return
    val w = took / (took + g.size)
    g.fat += (w * (fat / took - g.fat)).toFloat(); g.fed += (w * (fed / took - g.fed)).toFloat(); g.protein += (w * (protein / took - g.protein)).toFloat()
    g.dry += (w * (dry / took - g.dry)).toFloat()
    g.adults += takeA; g.young += takeY; g.joined = takeA + takeY
    stats.hunting.merge(Tally.of(s, Tally.HEADS_JOINED), takeA + takeY, Int::plus)
}

/** [HexGroup.mergedInto] of a herd that scattered into the land: its members live on as residents. */
const val SCATTERED = -2

/**
 * [adults] and [young] of herd [g] settle as residents (slot [k]) about where it stands, within a day's walk of its
 * kind ([Species.dailyTravelKm]), by how much of it suits them, feeds them and waters them this week
 * ([HexGroups.startWeight]); their condition, thirst included, mixed with those there.
 */
private fun HexGroups.spread(g: HexGroup, k: Int, adults: Int, young: Int) {
    val about = placesWithin(g.place.i, g.species.dailyTravelKm.toDouble())
    val weights = FloatArray(about.size) { startWeight(g.species, about[it], now = true) }
    val total = weights.sum()
    val size = adults + young
    for ((i, p) in (if (total > 0f) about else listOf(g.place.i)).withIndex()) {
        val share = if (total > 0f) weights[i] / total else 1f
        if (share <= 0f) continue
        val area = land.areaKm2[p]
        val there = (fauna.adults[k][p] + fauna.young[k][p]) * area
        val come = size * share
        val w = come / max(1e-6f, there + come)
        fauna.reserve[k][p] += w * (g.fat - fauna.reserve[k][p])
        fauna.fed[k][p] += w * (g.fed - fauna.fed[k][p])
        fauna.protein[k][p] += w * (g.protein - fauna.protein[k][p])
        fauna.dry[k][p] += w * (g.dry - fauna.dry[k][p])
        if (there <= 0f && g.birthMonth >= 0) fauna.birthStart[k][p] = g.birthMonth.toByte()
        fauna.adults[k][p] += adults * share / area; fauna.young[k][p] += young * share / area
    }
}

/** How near the hexes of the k-th ring about a hex can lie, in k hex widths (its corners lie k widths out, the middles of its sides less). */
private const val RING_KM = 0.866

/** The hexes within [km] of hex [h0] (itself and its neighbours at least), kept once worked out. */
private fun HexGroups.ground(h0: Int, km: Double): IntArray {
    val steps = max(1, (km / hex.hexKm).roundToInt())
    val all = gatherGround.getOrPut(steps) { arrayOfNulls(hex.size) }
    all[h0]?.let { return it }
    val out = arrayListOf(h0); val seen = hashSetOf(h0)
    var ring = listOf(h0)
    repeat(steps) {
        val next = ArrayList<Int>()
        for (h in ring) for (d in 0 until 6) { val nh = hex.neighbour(h, d); if (nh >= 0 && seen.add(nh)) next += nh }
        out += next; ring = next
    }
    return out.toIntArray().also { all[h0] = it }
}

/** The places in the hexes within [km] of place [p]'s hex. */
private fun HexGroups.placesWithin(p: Int, km: Double): List<Int> {
    val out = ArrayList<Int>()
    for (h in ground(hw.placeHex[p], km)) for (b in HexWorld.BANDS_M.indices) { val q = hw.placeOf[b][h]; if (q >= 0) out += q }
    return out
}
