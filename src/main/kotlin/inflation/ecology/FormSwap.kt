package inflation.ecology

import kotlin.math.roundToInt

/*
 * EXPERIMENT (branch only, not for main): the same kind run in the other form than its own, from a
 * settled state, to see what being a herd or a resident costs a kind (t.formSwap=NAME,NAME). A resident
 * kind's animals are gathered into herds of its kind's full herd size (as big as herds gather,
 * [Species.maxGroupSize] × [HERD_UNIT]) at places drawn by its numbers there; a herd kind's animals are
 * spread as residents over the ground each herd ranges over this week ([rangeShares]). Fat, feeding,
 * protein and the birth month go with them.
 */

/** EXPERIMENT switch: herds and residents hunted by one rule (see [seenFirst], [killChance], [worthChasing], [HerdAt]); off: as on main. */
internal val SHARED_HUNT = inflation.Config.flag("t.sharedHunt", true)

/** EXPERIMENT switch: herds reach their food as residents do (a share of the place's food out of reach, not of their need), and residents pay for the km they move as herds do; off: as on main. */
internal val SHARED_FEED = inflation.Config.flag("t.sharedFeed", true)

/** EXPERIMENT switch: counts each step of the hunt, herds and residents alike, by prey kind, into the history ("hunt diag/KIND/STEP"). */
internal val HUNT_DIAG = inflation.Config.flag("t.huntDiag", false)

/** Adds [n] to the hunt-step count [what] for prey [s] (kept in hundredths, see [HexHistory]). */
internal fun HexGroups.diag(s: Species, what: String, n: Float) {
    if (HUNT_DIAG && n > 0f) stats.hunting.merge("diag:${s.name}:$what", (n * 100f).roundToInt(), Int::plus)
}

/** Swaps the form of each kind in [Catalogue.formSwap] (after a settled state is loaded); [log] says what it did. */
fun HexSim.swapForms(log: (String) -> Unit) {
    for (s in Catalogue.formSwap) {
        if (Catalogue.resident(s)) groups.toHerds(s, weather.date.month, log) else groups.toResidents(s, log)
    }
}

/** Resident kind [s] gathered into herds. */
internal fun HexGroups.toHerds(s: Species, month: Int, log: (String) -> Unit) {
    val k = fauna.kinds.indexOf(s)
    val a = fauna.adults[k]; val y = fauna.young[k]
    val heads = FloatArray(n) { p -> (a[p] + y[p]) * land.areaKm2[p] }
    var adults = 0.0; var total = 0.0
    for (p in 0 until n) { adults += a[p] * land.areaKm2[p]; total += heads[p] }
    val herd = s.maxGroupSize * HERD_UNIT
    val count = (total / herd).roundToInt()
    val weightSum = heads.sum()
    val made = ArrayList<HexGroup>()
    repeat(count) {
        val p = drawPlace(heads, weightSum)
        val ad = (herd * adults / total).roundToInt().coerceAtLeast(2)
        val g = spawn(s, p, ad)
        g.young = herd - ad
        g.fat = fauna.reserve[k][p]; g.fed = fauna.fed[k][p]; g.protein = fauna.protein[k][p]
        g.birthMonth = fauna.birthStart[k][p].toInt()
        inheritSeasons(g)
        made += g
    }
    groups += made
    a.fill(0f); y.fill(0f)
    // Its rounds, as the other herds' were first grown: from how good each place is for it each month, here
    // reckoned once from the land as it is now (the others' from a year of notes).
    if (ROUNDS) {
        val notes = seasonNotes.getOrPut(s.index) { arrayOfNulls(12) }
        for (m in 0 until 12) notes[m] = FloatArray(n) { p -> herdQuality(s, p, m) }
        growRoundsFor(s, month)
    }
    log("form swap: ${s.name}, %.0f residents gathered into %d herds of %d".format(total, count, herd) +
        if (ROUNDS) ", %d of them on a round".format(made.count { it.route[0] >= 0 }) else "")
}

/** Herd kind [s] spread as residents over the ground its herds range over. */
internal fun HexGroups.toResidents(s: Species, log: (String) -> Unit) {
    val k = fauna.kinds.indexOf(s)
    val a = fauna.adults[k]; val y = fauna.young[k]
    a.fill(0f); y.fill(0f)
    val w = DoubleArray(n); val fat = DoubleArray(n); val fed = DoubleArray(n); val protein = DoubleArray(n)
    val birth = IntArray(n) { -1 }; val birthW = DoubleArray(n)
    var heads = 0; var herds = 0
    for (g in groups) {
        if (!g.alive || g.species != s) continue
        heads += g.size; herds++
        rangeShares(g, hw.placeHex[g.place]) { h, share ->
            val p = (hw.sameBandPlace(g.place, h).takeIf { !it.none } ?: g.place).i
            a[p] += g.adults * share / land.areaKm2[p]; y[p] += g.young * share / land.areaKm2[p]
            val m = (g.size * share).toDouble()
            w[p] += m; fat[p] += m * g.fat; fed[p] += m * g.fed; protein[p] += m * g.protein
            if (m > birthW[p]) { birthW[p] = m; birth[p] = g.birthMonth }
        }
        g.adults = 0; g.young = 0
    }
    for (p in 0 until n) if (w[p] > 0.0) {
        fauna.reserve[k][p] = (fat[p] / w[p]).toFloat(); fauna.fed[k][p] = (fed[p] / w[p]).toFloat(); fauna.protein[k][p] = (protein[p] / w[p]).toFloat()
        if (birth[p] >= 0) fauna.birthStart[k][p] = birth[p].toByte()
    }
    groups.removeIf { !it.alive }
    log("form swap: ${s.name}, $heads animals in $herds herds spread as residents over ${w.count { it > 0.0 }} places")
}
