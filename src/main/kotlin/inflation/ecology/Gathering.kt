package inflation.ecology

import inflation.world.HexWorld
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * Kinds that live both ways ([Catalogue.gathering]): one population, spread over the land as residents, out of
 * which herds condense where there are more animals than the land feeds. Each week, the residents a place did not
 * feed (its heads times what fell short of their need) are its excess; where the excess within [gatherKm] of a
 * place comes to a full herd of the kind, it gathers into herds that go looking for other country, and those the
 * land fed stay. A herd that has come away from where it formed, and is fed where it stands among residents of its
 * kind that are fed too, scatters back into the land. Heads, young, fat, feeding and protein go across as they are:
 * being moved from one form to the other changes nothing by itself. The limit is the land's own: what it fed them
 * this week, after the other kinds and the hunters.
 */

/** The ground a herd condenses from, and keeps away from until it scatters: half a range across (see [RANGE_KM]). */
const val GATHER_KM = RANGE_KM / 2

/**
 * The ground a herd of [s] condenses from, and keeps away from until it scatters: as far as the kind walks in a week
 * (a gazelle some 40 km, an onager 100), so a wide-ranging, sparse kind draws on as wide a country as it roams.
 */
internal fun gatherKm(s: Species) = 7.0 * s.dailyTravelKm

/** The heads a gathered herd of [s] holds: as many as its kind's herds gather (see [toHerds]). */
internal fun herdOf(s: Species) = s.maxGroupSize * HERD_UNIT

/**
 * Each week: in each gathering kind, the excess the land did not feed, gathered from about each place where it is
 * most (within [gatherKm], each hex once a week), into as many full herds as it makes; less than a herd stays spread.
 * Those that cannot drink where they are count wholly to the excess.
 */
internal fun HexGroups.gather() {
    for (s in Catalogue.gathering) {
        val k = fauna.slot(s); if (k < 0 || s !in kinds) continue
        val a = fauna.adults[k]; val y = fauna.young[k]; val f = fauna.fed[k]
        // (The excess at each place, heads; and by hex, to start from where it is most.)
        val excess = DoubleArray(n)
        val byHex = DoubleArray(hex.size)
        for (p in 0 until n) {
            val heads = (a[p] + y[p]) * land.areaKm2[p].toDouble()
            if (heads <= 0.0) continue
            excess[p] = heads * short(s, k, p)
            byHex[hw.placeHex[p]] += excess[p]
        }
        // (Not one herd's worth in all: nothing to gather.)
        if (byHex.sum() < herdOf(s)) continue
        val used = BooleanArray(hex.size)
        var made = 0; var heads = 0
        for (h0 in (0 until hex.size).filter { byHex[it] > 0.0 }.sortedByDescending { byHex[it] }) {
            if (used[h0]) continue
            var all = 0.0
            for (h in ground(h0, s)) if (!used[h]) all += byHex[h]
            if (all < herdOf(s)) continue
            val about = ground(h0, s).filter { !used[it] }
            val count = (all / herdOf(s)).toInt()
            if (count == 0) continue
            for (h in about) used[h] = true
            // Whole animals go: each place gives its share of the excess, adults and young as they are there.
            val t = count * herdOf(s) / all
            var adults = 0.0; var young = 0.0; var fat = 0.0; var fed = 0.0; var protein = 0.0; var most = -1; var mostHeads = 0.0
            for (h in about) for (b in HexWorld.BANDS_M.indices) {
                val p = hw.placeOf[b][h]; if (p < 0 || excess[p] <= 0.0) continue
                val here = (a[p] + y[p]).toDouble()
                val go = (t * excess[p] / land.areaKm2[p] / here).toFloat().coerceIn(0f, 1f)
                val ga = a[p] * go * land.areaKm2[p].toDouble(); val gy = y[p] * go * land.areaKm2[p].toDouble()
                a[p] -= a[p] * go; y[p] -= y[p] * go
                adults += ga; young += gy
                fat += (ga + gy) * fauna.reserve[k][p]; fed += (ga + gy) * f[p]; protein += (ga + gy) * fauna.protein[k][p]
                if (ga + gy > mostHeads) { mostHeads = ga + gy; most = p }
            }
            val took = adults + young
            val takeA = adults.roundToInt(); val takeY = young.roundToInt()
            if (most < 0 || takeA < count) {
                // (Too few grown animals for the herds: they stay spread, put back where the most came from.)
                if (most >= 0) { a[most] += (adults / land.areaKm2[most]).toFloat(); y[most] += (young / land.areaKm2[most]).toFloat() }
                continue
            }
            // (What rounding leaves over goes back to where the most came from.)
            a[most] += ((adults - takeA) / land.areaKm2[most]).toFloat(); y[most] += ((young - takeY) / land.areaKm2[most]).toFloat()
            for (i in 0 until count) {
                val g = spawn(s, most, takeA / count + if (i < takeA % count) 1 else 0)
                g.young = takeY / count + if (i < takeY % count) 1 else 0
                g.fat = (fat / took).toFloat(); g.fed = (fed / took).toFloat(); g.protein = (protein / took).toFloat()
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
 * Each week: a herd of a gathering kind that has come further than [gatherKm] from where it formed, is fed where it
 * stands ([FED_TO_SCATTER]), and finds the residents of its kind about it fed as well (an excess under that share of
 * their heads), scatters into the land about it as residents: it has found country with room for it.
 */
internal fun HexGroups.scatter() {
    var any = false
    for (g in groups) {
        val s = g.species
        if (!g.alive || s !in Catalogue.gathering) continue
        val k = fauna.slot(s); if (k < 0) continue
        if (g.fed < FED_TO_SCATTER || fauna.thirstAt(s, g.place.i, g.fed) > 0f) continue
        if (g.formedAt >= 0 && hex.distanceKm(hw.placeHex[g.place.i], hw.placeHex[g.formedAt]) <= gatherKm(s)) continue
        var heads = 0.0; var wanting = 0.0
        for (p in placesWithin(g.place.i, s)) {
            val h = (fauna.adults[k][p] + fauna.young[k][p]) * land.areaKm2[p].toDouble()
            heads += h; wanting += h * short(s, k, p)
        }
        if (heads > 0.0 && wanting > (1f - FED_TO_SCATTER) * heads) continue
        spread(g, k)
        stats.hunting.merge(Tally.of(s, Tally.SCATTERED), 1, Int::plus); stats.hunting.merge(Tally.of(s, Tally.HEADS_SCATTERED), g.size, Int::plus)
        // (Its members live on spread out, not dead: so the learning counts them, see [SCATTERED].)
        g.mergedSize = g.size; g.mergedInto = SCATTERED; merged += g
        g.adults = 0; g.young = 0; any = true
    }
    if (any) groups.removeIf { !it.alive }
}

/**
 * What the land falls short of for kind [s]'s residents (slot [k]) at place [p], as a share of them: what their food
 * did not meet, or all of them where they cannot drink.
 */
private fun HexGroups.short(s: Species, k: Int, p: Int): Float {
    val f = fauna.fed[k][p]
    return if (fauna.thirstAt(s, p, f) > 0f) 1f else (1f - f).coerceIn(0f, 1f)
}

/** [HexGroup.mergedInto] of a herd that scattered into the land: its members live on as residents. */
const val SCATTERED = -2

/**
 * How well fed a herd and the residents about it must be for it to scatter: all but a few percent of their need,
 * as fed is never quite whole where animals are many.
 */
const val FED_TO_SCATTER = 0.95f

/**
 * Herd [g]'s animals spread as residents (slot [k]) over the ground about it ([gatherKm]), by how much of it suits
 * them and feeds them ([HexGroups.startWeight]); their condition mixed with those there.
 */
private fun HexGroups.spread(g: HexGroup, k: Int) {
    val about = placesWithin(g.place.i, g.species)
    val weights = FloatArray(about.size) { startWeight(g.species, about[it]) }
    val total = weights.sum()
    for ((i, p) in (if (total > 0f) about else listOf(g.place.i)).withIndex()) {
        val share = if (total > 0f) weights[i] / total else 1f
        if (share <= 0f) continue
        val area = land.areaKm2[p]
        val there = (fauna.adults[k][p] + fauna.young[k][p]) * area
        val come = g.size * share
        val w = come / max(1e-6f, there + come)
        fauna.reserve[k][p] += w * (g.fat - fauna.reserve[k][p])
        fauna.fed[k][p] += w * (g.fed - fauna.fed[k][p])
        fauna.protein[k][p] += w * (g.protein - fauna.protein[k][p])
        if (there <= 0f && g.birthMonth >= 0) fauna.birthStart[k][p] = g.birthMonth.toByte()
        fauna.adults[k][p] += g.adults * share / area; fauna.young[k][p] += g.young * share / area
    }
}

/** The hexes within [gatherKm] of [s] of hex [h0] (itself included), kept once worked out. */
private fun HexGroups.ground(h0: Int, s: Species): IntArray {
    val steps = (gatherKm(s) / hex.hexKm).roundToInt()
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

/** The places in the hexes within [gatherKm] of [s] of place [p]'s hex. */
private fun HexGroups.placesWithin(p: Int, s: Species): List<Int> {
    val out = ArrayList<Int>()
    for (h in ground(hw.placeHex[p], s)) for (b in HexWorld.BANDS_M.indices) { val q = hw.placeOf[b][h]; if (q >= 0) out += q }
    return out
}
