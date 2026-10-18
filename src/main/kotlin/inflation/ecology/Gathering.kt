package inflation.ecology

import inflation.world.HexWorld
import inflation.wrapMonth
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Kinds that live both ways ([Catalogue.gathering]): one population, spread over the land as residents, out of
 * which herds condense where the land about them leaves animals hungry while country beyond their reach has food to
 * spare. Residents move to better ground next door on their own (the spill, see [HexFauna]); a herd is the long walk
 * they cannot make that way. Each week, the hungry within [gatherKm] of a place that come to a full herd of the kind
 * gather into herds, as far as the room the land has beyond that ground, within a month's walk ([farKm]), will feed
 * them, less what the herds already about will take. A kind held short by its food everywhere at once finds no room
 * and stays spread. A herd that has come away from where it formed to ground with room to spare scatters back into
 * the land a band a week; where it finds none, it takes in hungry residents and young males about it, as far as room
 * is left. Heads, young, fat, feeding and protein go across as they are: being moved from one form to the other changes
 * nothing by itself. The room is the land's own: the food left uneaten this week after every kind ate ([HexFauna.room]).
 */

/** The ground a herd condenses from, and keeps away from until it scatters: half a range across (see [RANGE_KM]). */
const val GATHER_KM = RANGE_KM / 2

/**
 * The ground a herd of [s] condenses from, and keeps away from until it scatters: as far as the kind walks in a week
 * (a gazelle some 40 km, an onager 100), so a wide-ranging, sparse kind draws on as wide a country as it roams.
 */
internal fun gatherKm(s: Species) = 7.0 * s.dailyTravelKm

/** How far a herd of [s] looks for room: a month's walk. */
internal fun farKm(s: Species) = 30.0 * s.dailyTravelKm

/**
 * Each week, before herds scatter and gather: for each gathering kind, the room the land has for it by hex, heads
 * ([HexFauna.room]), and the room left in all once the kind's herds are counted against it ([roomLeft]).
 */
internal fun HexGroups.roomAhead() {
    for (s in Catalogue.gathering) {
        val k = fauna.slot(s); if (k < 0 || s !in kinds) continue
        val r = fauna.room[k] ?: continue
        val byHex = DoubleArray(hex.size)
        for (p in 0 until n) if (r[p] > 0f) byHex[hw.placeHex[p]] += r[p] * land.areaKm2[p].toDouble()
        roomBy[s] = byHex
        var herds = 0.0
        for (g in groups) if (g.alive && g.species == s) herds += g.size
        roomLeft[s] = byHex.sum() - herds
    }
}

/** The heads a gathered herd of [s] holds: as many as its kind's herds gather. */
internal fun herdOf(s: Species) = s.maxGroupSize * HERD_UNIT

/**
 * Each week: in each gathering kind, the hungry the land about them does not feed, gathered from about each place where
 * they are most (within [gatherKm], each hex once a week), into as many full herds as they make and the room beyond
 * that ground within [farKm] will feed ([roomAhead]); the rest stays spread.
 */
internal fun HexGroups.gather() {
    for (s in Catalogue.gathering) {
        val k = fauna.slot(s); if (k < 0 || s !in kinds) continue
        val a = fauna.adults[k]; val y = fauna.young[k]; val f = fauna.fed[k]
        val room = roomBy[s] ?: continue; var left = roomLeft[s] ?: 0.0
        // (The excess at each place, heads; and by hex, to start from where it is most.)
        val excess = DoubleArray(n)
        val byHex = DoubleArray(hex.size)
        for (p in 0 until n) {
            val heads = (a[p] + y[p]) * land.areaKm2[p].toDouble()
            if (heads <= 0.0) continue
            excess[p] = heads * short(s, k, p)
            byHex[hw.placeHex[p]] += excess[p]
        }
        unfed[s] = byHex
        // (Not one herd's worth in all, of the hungry or of room: nothing to gather.)
        if (byHex.sum() < herdOf(s) || left < herdOf(s)) continue
        val roomy = (0 until hex.size).filter { room[it] > 0.0 }
        val used = BooleanArray(hex.size)
        var made = 0; var heads = 0
        for (h0 in (0 until hex.size).filter { byHex[it] > 0.0 }.sortedByDescending { byHex[it] }) {
            if (used[h0]) continue
            var all = 0.0
            for (h in ground(h0, gatherKm(s))) if (!used[h]) all += byHex[h]
            if (all < herdOf(s)) continue
            val about = ground(h0, gatherKm(s)).filter { !used[it] }
            // The room beyond their own ground, within a month's walk.
            val far = roomy.filter { val d = hex.distanceKm(h0, it); d > gatherKm(s) && d <= farKm(s) }
            var spare = 0.0; for (h in far) spare += room[h]
            val count = (minOf(all, spare, left) / herdOf(s)).toInt()
            if (count == 0) continue
            // (What they will take of it is no longer room for others.)
            val takes = count * herdOf(s).toDouble()
            for (h in far) room[h] -= room[h] * takes / spare
            left -= takes
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
        roomLeft[s] = left
        if (made > 0) { stats.hunting.merge(Tally.of(s, Tally.GATHERED), made, Int::plus); stats.hunting.merge(Tally.of(s, Tally.HEADS_GATHERED), heads, Int::plus) }
    }
}

/**
 * Diagnostic (unfedDiag): for any resident kind [s], as if it lived both ways, what [gather] would condense out of
 * its residents this week, by the same grouping, counted two ways: of the unfed (heads times what fell short of their
 * need), and of the floaters (heads beyond the breeding ranges a place has, [inflation.Tuning.breedSpacing] times
 * the kind's usual density); and its unfed, floating and all heads. Nothing moves. (Herds unfed, heads unfed, all
 * heads, herds of floaters, floating heads.)
 */
internal fun HexGroups.wouldGather(s: Species): DoubleArray {
    val k = fauna.slot(s); if (k < 0) return DoubleArray(5)
    val a = fauna.adults[k]; val y = fauna.young[k]
    val ranges = tuning.breedSpacing * HexFauna.usualDensity(s)
    val unfedByHex = DoubleArray(hex.size); val floatByHex = DoubleArray(hex.size)
    var unfedHeads = 0.0; var allHeads = 0.0; var floating = 0.0
    for (p in 0 until n) {
        val heads = (a[p] + y[p]) * land.areaKm2[p].toDouble()
        if (heads <= 0.0) continue
        val e = heads * short(s, k, p)
        val fl = max(0.0, heads - ranges * land.areaKm2[p])
        unfedByHex[hw.placeHex[p]] += e; floatByHex[hw.placeHex[p]] += fl
        unfedHeads += e; floating += fl; allHeads += heads
    }
    return doubleArrayOf(herdsIn(s, unfedByHex).toDouble(), unfedHeads, allHeads, herdsIn(s, floatByHex).toDouble(), floating)
}

/** The full herds of [s] the heads by hex [byHex] would gather into, from where they are most (as [gather] groups them). */
private fun HexGroups.herdsIn(s: Species, byHex: DoubleArray): Int {
    if (byHex.sum() < herdOf(s)) return 0
    var herds = 0
    val used = BooleanArray(hex.size)
    for (h0 in (0 until hex.size).filter { byHex[it] > 0.0 }.sortedByDescending { byHex[it] }) {
        if (used[h0]) continue
        var all = 0.0
        for (h in ground(h0, gatherKm(s))) if (!used[h]) all += byHex[h]
        val count = (all / herdOf(s)).toInt()
        if (count == 0) continue
        for (h in ground(h0, gatherKm(s))) used[h] = true
        herds += count
    }
    return herds
}

/**
 * For the viewer: by hex, how many herds of kind [s] the unfed excess within [gatherKm] of it came to this week, before
 * any gathered (at 1 or more, a herd forms there unless a place nearer more excess took it first); null before a week.
 */
internal fun HexGroups.herdsWorth(s: Species): FloatArray? {
    val byHex = unfed[s] ?: return null
    val out = FloatArray(hex.size)
    // (The ground is the same both ways: each hex's excess counts towards every hex it lies within reach of.)
    for (h in byHex.indices) if (byHex[h] > 0.0) { val v = (byHex[h] / herdOf(s)).toFloat(); for (q in ground(h, gatherKm(s))) out[q] += v }
    return out
}

/**
 * Each week, a herd of a gathering kind keeps the balance with the residents of its kind about it. Where it has come
 * further than [gatherKm] from where it formed, is fed where it stands ([FED_TO_SCATTER]), and finds the residents
 * about it expecting to be fed through the coming season as well (an excess under that share of their heads, see
 * [foresee]), it has found country with room for it: one usual
 * band of its kind ([Species.maxGroupSize]) settles there as residents, about where it stands, and the last band
 * scatters whole. Elsewhere it takes in, within a day's walk of it, the residents that expect to go short (as
 * [gather] counts them) and the young males that grew up there this week and leave to find their own ground (half those grown,
 * see [HexFauna.grownUp]); each place gives to one herd a week. So a herd grows where the country is crowded and
 * thins where it has room, a band a week, and those it leaves are counted with the residents from then on.
 */
internal fun HexGroups.scatter() {
    var any = false
    for (g in groups) { g.settled = 0; g.joined = 0 }
    val taken = HashMap<Species, BooleanArray>()
    for (g in groups) {
        val s = g.species
        if (!g.alive || s !in Catalogue.gathering) continue
        val k = fauna.slot(s); if (k < 0) continue
        if (!roomFor(g, k)) { join(g, k, taken.getOrPut(s) { BooleanArray(n) }); continue }
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
 * Whether herd [g] (its kind's residents in slot [k]) has found country with room for a band of it (see [scatter]): fed,
 * away from where it formed, and the land within [gatherKm] would feed a band more; the band's room is then taken.
 */
private fun HexGroups.roomFor(g: HexGroup, k: Int): Boolean {
    val s = g.species
    if (g.fed < FED_TO_SCATTER || fauna.thirstAt(s, g.place.i, g.fed) > 0f) return false
    if (g.formedAt >= 0 && hex.distanceKm(hw.placeHex[g.place.i], hw.placeHex[g.formedAt]) <= gatherKm(s)) return false
    val room = roomBy[s] ?: return false
    val near = ground(hw.placeHex[g.place.i], gatherKm(s))
    var spare = 0.0; for (h in near) spare += room[h]
    val band = min(g.size, s.maxGroupSize).toDouble()
    if (spare < band) return false
    for (h in near) room[h] -= room[h] * band / spare
    return true
}

/**
 * Herd [g] takes in, within a day's walk of it, the residents (slot [k]) the land did not feed and the young males
 * leaving home, from the places no other herd has taken from this week ([taken]); their condition mixed with its own.
 */
private fun HexGroups.join(g: HexGroup, k: Int, taken: BooleanArray) {
    val s = g.species
    val a = fauna.adults[k]; val y = fauna.young[k]; val grown = fauna.grownUp[k] ?: return
    // (Only as many as there is room for, beyond what the herds about already take.)
    var left = roomLeft[s] ?: return
    if (left <= 0.0) return
    var adults = 0.0; var young = 0.0; var fat = 0.0; var fed = 0.0; var protein = 0.0; var most = -1; var mostHeads = 0.0
    for (p in placesWithin(g.place.i, s.dailyTravelKm.toDouble())) {
        if (taken[p]) continue
        val here = a[p] + y[p]; if (here <= 0f) continue
        taken[p] = true
        val short = short(s, k, p)
        // (Per km²: the hungry, adults and young as they are there, and the young males that grew up, from the adults
        // left; no more than the room left.)
        val area = land.areaKm2[p]
        val cap = min(1.0, left / ((a[p] + y[p]) * area)).toFloat()
        val ga = min(a[p], a[p] * short + 0.5f * grown[p] * (1f - short)) * cap; val gy = y[p] * short * cap
        if (ga + gy <= 0f) continue
        left -= (ga + gy) * area
        a[p] -= ga; y[p] -= gy
        val heads = (ga + gy) * area.toDouble()
        adults += ga * area; young += gy * area
        fat += heads * fauna.reserve[k][p]; fed += heads * fauna.fed[k][p]; protein += heads * fauna.protein[k][p]
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
    g.adults += takeA; g.young += takeY; g.joined = takeA + takeY
    roomLeft[s] = left
    stats.hunting.merge(Tally.of(s, Tally.HEADS_JOINED), takeA + takeY, Int::plus)
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
 * [adults] and [young] of herd [g] settle as residents (slot [k]) about where it stands, within a day's walk of its
 * kind ([Species.dailyTravelKm]), by how much of it suits them and feeds them ([HexGroups.startWeight]); their
 * condition mixed with those there.
 */
private fun HexGroups.spread(g: HexGroup, k: Int, adults: Int, young: Int) {
    val about = placesWithin(g.place.i, g.species.dailyTravelKm.toDouble())
    val weights = FloatArray(about.size) { startWeight(g.species, about[it]) }
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
        if (there <= 0f && g.birthMonth >= 0) fauna.birthStart[k][p] = g.birthMonth.toByte()
        fauna.adults[k][p] += adults * share / area; fauna.young[k][p] += young * share / area
    }
}

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
