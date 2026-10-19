package inflation.ecology

import java.util.Arrays
import kotlin.math.roundToInt

/*
 * For tests: what being a herd or a resident costs a plant-eater, from a settled state (t.formSwap). With the key set
 * at all, herds neither gather nor scatter, so each kind keeps the form it has; once a saved world is loaded, each
 * kind named goes wholly into the form named. Gathered: all its residents into herds of its kind's full herd size
 * ([herdOf]) at places drawn by its numbers there, beside the herds it has. Spread: all its herds as residents over the
 * ground each herd ranges over this week ([rangeShares]), beside the residents it has. Fat, feeding, protein and the
 * birth month go with them.
 */

/**
 * Puts each kind in [Catalogue.formSwap] into the form named for it (after a settled state is loaded); without
 * [Catalogue.herds], spreads every plant-eater's herds as residents instead. [log] says what it did.
 */
fun HexSim.swapForms(log: (String) -> Unit) {
    if (!Catalogue.herds) {
        for (s in Catalogue.herbivores) if (groups.groups.any { it.alive && it.species == s }) groups.toResidents(s, log)
        return
    }
    for ((s, herds) in Catalogue.formSwap) if (herds) groups.toHerds(s, weather.date.month, log) else groups.toResidents(s, log)
}

/** Plant-eater [s]'s residents all gathered into herds. */
internal fun HexGroups.toHerds(s: Species, month: Int, log: (String) -> Unit) {
    val k = fauna.slot(s); if (k < 0 || s !in kinds) { log("form swap: ${s.name} cannot live as herds here"); return }
    val a = fauna.adults[k]; val y = fauna.young[k]
    val heads = FloatArray(n) { p -> (a[p] + y[p]) * land.areaKm2[p] }
    var adults = 0.0; var total = 0.0
    for (p in 0 until n) { adults += a[p] * land.areaKm2[p]; total += heads[p] }
    val herd = herdOf(s)
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
        val rounds = seasonQuality(s)?.let { roundsFor(s, it) }.orEmpty()
        if (rounds.isNotEmpty()) {
            herdRounds[s.index] = rounds
            for (g in made) if (g.route[0] == ROUND_OPEN) {
                if (hashNoise(g.id, 77) >= tuning.roundStartShare) Arrays.fill(g.route, NO_ROUND) else takeRound(g, rounds, month)
            }
        }
    }
    log("form swap: ${s.name}, %.0f residents gathered into %d herds of %d".format(total, count, herd) +
        if (ROUNDS) ", %d of them on a round".format(made.count { it.route[0] >= 0 }) else "")
}

/** Plant-eater [s]'s herds all spread as residents over the ground they graze, mixed with the residents there. */
internal fun HexGroups.toResidents(s: Species, log: (String) -> Unit) {
    val k = fauna.slot(s); if (k < 0) { log("form swap: ${s.name} cannot live spread out here"); return }
    val a = fauna.adults[k]; val y = fauna.young[k]
    // (What is there already counts in the mix by its heads.)
    val w = DoubleArray(n) { p -> ((a[p] + y[p]) * land.areaKm2[p]).toDouble() }
    val fat = DoubleArray(n) { w[it] * fauna.reserve[k][it] }; val fed = DoubleArray(n) { w[it] * fauna.fed[k][it] }
    val protein = DoubleArray(n) { w[it] * fauna.protein[k][it] }
    val birth = IntArray(n) { -1 }; val birthW = w.copyOf()
    var heads = 0; var herds = 0
    for (g in groups) {
        if (!g.alive || g.species != s) continue
        heads += g.size; herds++
        rangeShares(g, hw.placeHex[g.place]) { h, share ->
            val p = hw.sameBandPlace(g.place.i, h).takeIf { it >= 0 } ?: g.place.i
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
    log("form swap: ${s.name}, $heads animals in $herds herds spread as residents")
}
