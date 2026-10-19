package inflation.ecology

/*
 * Plant-eaters no longer live as herds (model 2.16): a world saved with herds has them spread out as residents when it is
 * loaded, all of each kind's herds over the ground each ranges over this week ([rangeShares]), beside the residents it
 * has. Fat, feeding, protein and the birth month go with them.
 */

/** Spreads every plant-eater's herds as residents (after a saved world is loaded); [log] says what it did. */
fun HexSim.swapForms(log: (String) -> Unit) {
    for (s in Catalogue.herbivores) if (groups.groups.any { it.alive && it.species == s }) groups.toResidents(s, log)
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
