package inflation.ecology

import inflation.wrapMonth
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * Kinds that live both ways ([Catalogue.gathering]): one population, spread over the land as residents
 * most of the year, gathered into herds for the months their kind's round takes them far. Residents
 * where a round leaves the ground about its place ([GATHER_KM]) next month gather into herds of the kind's full
 * herd size and go with it; a herd that has come to where its round stays a while scatters back into
 * the land where it stands. Heads, young, fat, feeding and protein go across as they are: being moved
 * from one form to the other changes nothing by itself. Where no round passes, the kind never gathers.
 */

/** The ground a round's place draws its animals from, and holds them on: half a range across (see [RANGE_KM]). */
const val GATHER_KM = RANGE_KM / 2

/** The heads a gathered herd of [s] holds: as many as its kind's herds gather (see [toHerds]). */
internal fun herdOf(s: Species) = s.maxGroupSize * HERD_UNIT

/**
 * At the month's turn: the residents of each gathering kind about a round's place last month, where the
 * round goes on to another range this month, gather into herds that take that round; what is too few for a
 * full herd stays spread.
 */
internal fun HexGroups.gather(month: Int) {
    val prev = wrapMonth(month - 1)
    for (s in Catalogue.gathering) {
        val k = fauna.slot(s); if (k < 0 || s !in kinds) continue
        val rounds = herdRounds[s.index] ?: continue
        // (Each place a round leaves from, once.)
        val leaving = LinkedHashMap<Int, IntArray>()
        for (r in rounds) if (hex.distanceKm(hw.placeHex[r[prev]], hw.placeHex[r[month]]) > GATHER_KM) leaving.putIfAbsent(r[prev], r)
        val a = fauna.adults[k]; val y = fauna.young[k]
        var made = 0; var heads = 0
        for ((from, round) in leaving) {
            val about = placesWithin(from, GATHER_KM)
            var adults = 0.0; var young = 0.0; var fat = 0.0; var fed = 0.0; var protein = 0.0; var most = -1; var mostHeads = 0.0
            for (p in about) {
                val ad = a[p] * land.areaKm2[p].toDouble(); val yo = y[p] * land.areaKm2[p].toDouble(); val h = ad + yo
                adults += ad; young += yo
                fat += h * fauna.reserve[k][p]; fed += h * fauna.fed[k][p]; protein += h * fauna.protein[k][p]
                if (h > mostHeads) { mostHeads = h; most = p }
            }
            val all = adults + young
            val count = (all / herdOf(s)).toInt()
            if (count == 0 || adults < count) continue
            // Whole animals go: the herds' adults and young, taken from each place in proportion.
            val takeA = (count * herdOf(s) * adults / all).roundToInt().coerceIn(count, adults.toInt())
            val takeY = minOf(count * herdOf(s) - takeA, young.toInt()).coerceAtLeast(0)
            val fa = (takeA / adults).toFloat(); val fy = if (young > 0.0) (takeY / young).toFloat() else 0f
            for (p in about) { a[p] *= 1f - fa; y[p] *= 1f - fy }
            for (i in 0 until count) {
                val g = spawn(s, from, takeA / count + if (i < takeA % count) 1 else 0)
                g.young = takeY / count + if (i < takeY % count) 1 else 0
                g.fat = (fat / all).toFloat(); g.fed = (fed / all).toFloat(); g.protein = (protein / all).toFloat()
                g.birthMonth = fauna.birthStart[k][most].toInt()
                inheritSeasons(g)
                round.copyInto(g.route)
                groups += g
            }
            made += count; heads += takeA + takeY
        }
        if (made > 0) { stats.hunting.merge(Tally.of(s, Tally.GATHERED), made, Int::plus); stats.hunting.merge(Tally.of(s, Tally.HEADS_GATHERED), heads, Int::plus) }
    }
}

/**
 * Each week: a herd of a gathering kind whose place this month and next lie within [GATHER_KM] of each other scatters
 * into the land about it as residents once it has come within reach of that place, or has stopped short of it (kept
 * all week to one hex): nothing is left for it to travel for.
 */
internal fun HexGroups.scatter(month: Int) {
    var any = false
    for (g in groups) {
        val s = g.species
        if (!g.alive || s !in Catalogue.gathering) continue
        val k = fauna.slot(s); if (k < 0) continue
        val goal = roundOf(g, month)?.place ?: g.seasonPlace[month]
        val next = roundOf(g, wrapMonth(month + 1))?.place ?: g.seasonPlace[wrapMonth(month + 1)]
        if (goal < 0 || next < 0) continue
        if (hex.distanceKm(hw.placeHex[goal], hw.placeHex[next]) > GATHER_KM) continue
        // (Or that has stopped short of it: stayed where it was all week, as residents do.)
        val still = g.path.size <= 1
        if (!still && hex.distanceKm(hw.placeHex[g.place], hw.placeHex[goal]) > GATHER_KM) continue
        spread(g, k)
        stats.hunting.merge(Tally.of(s, Tally.SCATTERED), 1, Int::plus); stats.hunting.merge(Tally.of(s, Tally.HEADS_SCATTERED), g.size, Int::plus)
        // (Its members live on spread out, not dead: so the learning counts them, see [SCATTERED].)
        g.mergedSize = g.size; g.mergedInto = SCATTERED; merged += g
        g.adults = 0; g.young = 0; any = true
    }
    if (any) groups.removeIf { !it.alive }
}

/** [HexGroup.mergedInto] of a herd that scattered into the land: its members live on as residents. */
const val SCATTERED = -2

/**
 * Herd [g]'s animals spread as residents (slot [k]) over the ground about it ([GATHER_KM], as they gathered from), by how
 * much of it suits them and feeds them ([HexGroups.startWeight]); their condition mixed with those there.
 */
private fun HexGroups.spread(g: HexGroup, k: Int) {
    val about = placesWithin(g.place.i, GATHER_KM)
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

/** The places whose hexes' centres lie within [km] of place [p]'s. */
private fun HexGroups.placesWithin(p: Int, km: Double): List<Int> {
    val (x0, y0) = hex.centreKm(hw.placeHex[p])
    val out = ArrayList<Int>()
    for (q in 0 until n) {
        val (x, y) = hex.centreKm(hw.placeHex[q])
        if (kotlin.math.abs(x - x0) <= km && kotlin.math.abs(y - y0) <= km && kotlin.math.hypot(x - x0, y - y0) <= km) out += q
    }
    return out
}
