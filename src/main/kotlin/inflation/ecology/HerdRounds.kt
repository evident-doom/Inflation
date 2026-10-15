package inflation.ecology

import inflation.world.Date

/**
 * Herds' yearly rounds: the routes a herd learns young from its elders, kept for longer than any
 * herd lives, not found again each week from what lies about. Every [ROUND_YEARS] years (first once
 * a year's seasons have been seen) each herd kind's rounds are grown from the land's seasons as its
 * kind found them: its good country each month is gathered into ranges [RANGE_KM] across; then, from
 * each month-one range in turn, the yearly circuit through one range a month that gives the most
 * good grazing for the least going (a leg may cross poor country, climb a pass or take the long way
 * round, if the circuit as a whole pays); legs longer than a month's going are not taken. Each herd
 * then takes as its round the circuit that passes nearest to where it is this month, unless it has
 * one already; offshoots take their parent's. A round draws a herd on as its season's place does,
 * but is not given up for a place it happens to fare better at.
 */
internal fun HexGroups.growRounds(month: Int) {
    val firstGrowth = herdRounds.isEmpty()
    for (s in kinds) {
        if (s.carnivore) continue
        val q = seasonQuality(s) ?: continue
        val rounds = roundsFor(s, q)
        if (rounds.isEmpty()) continue
        herdRounds[s.index] = rounds
        for (g in groups) if (g.alive && g.species == s && g.route[0] == -1) {
            if (firstGrowth && hashNoise(g.id, 77) >= ROUND_START_SHARE) java.util.Arrays.fill(g.route, NO_ROUND) else takeRound(g, rounds, month)
        }
        meetings.merge("${s.name}: yearly rounds grown", rounds.size, Int::plus)
        // (And how far apart their January and July places lie, km, on average: for the check.)
        meetings.merge("${s.name}: rounds' January-July km", rounds.map { hex.distanceKm(hw.placeHex[it[0]], hw.placeHex[it[6]]) }.average().toInt(), Int::plus)
    }
}

/**
 * The round group [g] keeps to in [month], if any: the place it makes for and how strongly (0..). A herd's
 * is its yearly round as its elders taught it ([HexGroup.route], at [ROUND_STRENGTH]); a band's is its
 * rite, a round its culture holds to (see [Band.riteFor]). The pull is added to the hand-written score
 * and, outside the network, to a learned mover's (see the "on round" feature and SharedMover.pull).
 */
internal fun HexGroups.roundOf(g: HexGroup, month: Int): Pair<Int, Float>? {
    if (!ROUNDS) return null
    g.band?.let { return it.riteFor(month) }
    return if (g.route[month] >= 0) g.route[month] to ROUND_STRENGTH else null
}

/**
 * An offshoot keeps its parent's round, but not always exactly: now and then it changes one month's
 * place for one nearby ([ROUND_DRIFT]); a herd with a round now and then gives it up and keeps to
 * its own country, and one without now and then takes up the nearest round ([ROUND_SWITCH]).
 * Herds whose rounds feed them better grow and split more, so better rounds spread and poorer ones
 * die out: rounds are kept where they pay, as tradition is.
 */
internal fun HexGroups.driftRound(g: HexGroup) {
    val r = rnd.nextFloat()
    if (g.route[0] >= 0) {
        if (r < ROUND_SWITCH) { java.util.Arrays.fill(g.route, NO_ROUND); return }
        if (r < ROUND_SWITCH + ROUND_DRIFT) {
            val m = rnd.nextInt(12); val h0 = hw.placeHex[g.route[m]]
            val (x, y) = hex.centreKm(h0); val a = rnd.nextDouble() * 2 * Math.PI; val d = rnd.nextDouble() * RANGE_KM
            val h = hex.hexAt(x + d * kotlin.math.cos(a), y + d * kotlin.math.sin(a))
            val p = if (h in 0 until hex.size) hw.placeOf[hw.placeBand[g.route[m]]][h].takeIf { it >= 0 } ?: hw.placeOf[0][h] else -1
            if (p >= 0) g.route[m] = p
        }
    } else if (g.route[0] == NO_ROUND && r < ROUND_SWITCH) java.util.Arrays.fill(g.route, -1) // (to take the nearest round at the month's turn)
}

/** A herd takes the round that passes nearest to where it stands this month. */
internal fun HexGroups.takeRound(g: HexGroup, rounds: List<IntArray>, month: Int) {
    val here = hw.placeHex[g.place]
    var best: IntArray? = null; var bestKm = Double.MAX_VALUE
    for (r in rounds) { val km = hex.distanceKm(here, hw.placeHex[r[month]]); if (km < bestKm) { bestKm = km; best = r } }
    best?.copyInto(g.route)
}

/** The yearly circuits of kind [s], from how good each place was for it each month ([q]: [month][place]). */
private fun HexGroups.roundsFor(s: Species, q: Array<FloatArray>): List<IntArray> {
    // Ranges: the good country of each month gathered in squares of [RANGE_KM]: their worth (mean
    // quality over the square's land) and their best place.
    class Range(val place: Int, val worth: Float, val x: Double, val y: Double)
    val ranges = Array(12) { m ->
        val sum = HashMap<Long, DoubleArray>(); val bestP = HashMap<Long, Int>()
        for (p in 0 until n) {
            val (x, y) = hex.centreKm(hw.placeHex[p])
            val key = (x / RANGE_KM).toLong() * 100_000 + (y / RANGE_KM).toLong()
            val a = sum.getOrPut(key) { DoubleArray(2) }; a[0] += q[m][p] * land.areaKm2[p]; a[1] += land.areaKm2[p].toDouble()
            val b = bestP[key]; if (b == null || q[m][p] > q[m][b]) bestP[key] = p
        }
        sum.entries.filter { it.value[1] > 0.3 * RANGE_KM * RANGE_KM }.map { (key, a) ->
            val p = bestP.getValue(key); val (x, y) = hex.centreKm(hw.placeHex[p]); Range(p, (a[0] / a[1]).toFloat(), x, y)
        }.let { all -> val top = all.maxOfOrNull { it.worth } ?: 0f; all.filter { it.worth >= RANGE_FLOOR * top } }
            .sortedByDescending { it.worth }.take(RANGES_KEPT).sortedBy { it.place }
    }
    if (ranges.any { it.isEmpty() }) return emptyList()
    val legMax = s.maxDailyTravelKm * Date.DAYS_PER_MONTH * 0.5
    // (A leg over open water is not taken: herds walk their rounds. Sampled every [hex.hexKm] along the way.)
    val wet = HashMap<Long, Boolean>()
    fun overSea(a: Range, b: Range): Boolean = wet.getOrPut(a.place.toLong() * 1_000_003 + b.place) {
        val d = kotlin.math.hypot(a.x - b.x, a.y - b.y); val steps = (d / hex.hexKm).toInt().coerceAtLeast(1)
        (1 until steps).any { i -> val f = i.toDouble() / steps; val h = hex.hexAt(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f); h in 0 until hex.size && hex.heart[h] < 0 }
    }
    fun km(a: Range, b: Range) = if (overSea(a, b)) Double.MAX_VALUE else kotlin.math.hypot(a.x - b.x, a.y - b.y)
    // From each month-one range, the best circuit by dynamic programming over the months, closing back on it.
    val out = ArrayList<IntArray>()
    for (r0 in ranges[0]) {
        var value = DoubleArray(ranges[1].size) { j -> val d = km(r0, ranges[1][j]); if (d > legMax) Double.NEGATIVE_INFINITY else r0.worth + ranges[1][j].worth - ROUND_KM_COST * d }
        val from = Array(12) { IntArray(0) }
        for (m in 2 until 12) {
            val prev = ranges[m - 1]; val cur = ranges[m]
            val next = DoubleArray(cur.size) { Double.NEGATIVE_INFINITY }; val back = IntArray(cur.size) { -1 }
            for (j in cur.indices) for (k in prev.indices) {
                if (value[k] == Double.NEGATIVE_INFINITY) continue
                val d = km(prev[k], cur[j]); if (d > legMax) continue
                val v = value[k] + cur[j].worth - ROUND_KM_COST * d
                if (v > next[j]) { next[j] = v; back[j] = k }
            }
            value = next; from[m] = back
        }
        // Close the circuit: back to the month-one range.
        var bestJ = -1; var bestV = Double.NEGATIVE_INFINITY
        for (j in ranges[11].indices) {
            if (value[j] == Double.NEGATIVE_INFINITY) continue
            val d = km(ranges[11][j], r0); if (d > legMax) continue
            val v = value[j] - ROUND_KM_COST * d
            if (v > bestV) { bestV = v; bestJ = j }
        }
        if (bestJ < 0) continue
        val round = IntArray(12); round[0] = r0.place
        var j = bestJ
        for (m in 11 downTo 1) { round[m] = ranges[m][j].place; if (m >= 2) j = from[m][j] }
        out += round
    }
    return out.distinctBy { it.toList() }
}

/** How good each place was for kind [s] in each month, as noted month by month, or null until a whole year is in. */
private fun HexGroups.seasonQuality(s: Species): Array<FloatArray>? {
    val a = seasonNotes[s.index] ?: return null
    if (a.any { it == null }) return null
    @Suppress("UNCHECKED_CAST") return a as Array<FloatArray>
}

/**
 * Rounds: how often they are grown again, years; the size of a range, km; how many ranges of a month are
 * kept; what a km of the round costs against the quality of a month's grazing (0..1).
 */
const val ROUND_YEARS = 10
/** Rounds kept by inheritance: chance an offshoot changes one month's place of its round; chance it gives its round up, or takes one up; the mark of a herd keeping to its own country. */
val ROUND_DRIFT = System.getProperty("hex.t.roundDrift")?.toFloat() ?: 0.3f
val ROUND_SWITCH = System.getProperty("hex.t.roundSwitch")?.toFloat() ?: 0.05f
const val NO_ROUND = -2
/** Share of herds that start with a round when the rounds are first grown (the rest keep to their country, and the two ways compete). */
val ROUND_START_SHARE = System.getProperty("hex.t.roundStart")?.toFloat() ?: 0.5f
const val RANGE_KM = 100.0
const val RANGES_KEPT = 300
/** A range is kept for a month if it is at least this good against the month's best (so rounds cover the kind's whole country, not only its few best spots, where every herd would crowd). */
const val RANGE_FLOOR = 0.3f
val ROUND_KM_COST = System.getProperty("hex.t.roundKmCost")?.toDouble() ?: 0.002
