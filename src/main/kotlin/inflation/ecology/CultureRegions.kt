package inflation.ecology

import inflation.world.Hex

/**
 * Cultural regions, as an observer would draw them: bands living within [GATHER_KM] of each other that understand each other
 * at [REGION_UNDERSTOOD] or better and hold at most [REGION_CUSTOMS_APART] of their customs differently ([customsOff]) are of
 * one region, and so are the neighbours of those (single linkage). Nothing in the model reads them: the census prints them and
 * the viewer draws them. [bands] are the living people's bands, [region] each one's region (the index of one of its bands),
 * [name] each region's name by that index.
 */
class CultureRegions(val bands: List<HexGroup>, val region: IntArray, val name: Map<Int, String>) {
    /** The regions' indices, largest by people first, with their people. */
    val byPeople: List<Pair<Int, Double>> = bands.indices.groupBy { region[it] }
        .map { (r, l) -> r to l.sumOf { bands[it].size.toDouble() } }.sortedByDescending { it.second }
}

/** The bands' cultural regions as they stand ([CultureRegions]). */
fun GroupWorld.cultureRegions(all: List<HexGroup>): CultureRegions {
    val bands = all.filter { it.alive && it.band != null }
    val n = bands.size
    val at = Array(n) { hw.placeHex[bands[it].place] }
    val parent = IntArray(n) { it }
    fun root(i: Int): Int {
        var r = i
        while (parent[r] != r) r = parent[r]
        var j = i
        while (parent[j] != r) { val next = parent[j]; parent[j] = r; j = next }
        return r
    }
    for (i in 0 until n) {
        val bi = bands[i].band ?: continue
        for (j in i + 1 until n) {
            val bj = bands[j].band ?: continue
            if (hex.distanceKm(at[i], at[j]) > GATHER_KM) continue
            if (Language.understanding(bi.words, bj.words) >= REGION_UNDERSTOOD && customsOff(bi, bj, hex) <= REGION_CUSTOMS_APART) {
                val ri = root(i); val rj = root(j); if (ri != rj) parent[ri] = rj
            }
        }
    }
    val region = IntArray(n) { root(it) }
    val name = region.distinct().associateWith { r -> regionName((0 until n).filter { region[it] == r }.mapNotNull { bands[it].band }) }
    return CultureRegions(bands, region, name)
}

/**
 * A region's name: its bands' own word for the first of the shared concepts (taken as "people", "we"), the form most of them use,
 * spoken as syllables. So a region is called what its people would call themselves, and two regions of one tongue share a name.
 */
fun regionName(bands: List<Band>): String {
    val form = bands.groupingBy { it.words[0] }.eachCount().maxByOrNull { it.value }?.key ?: return "-"
    return spoken(form)
}

/** A word's form ([Language.FORMS] of them) spoken as consonant-vowel syllables, capitalised. */
fun spoken(form: Int): String {
    val c = "ptkmnslrbdgh"; val v = "aeiou"
    val sb = StringBuilder()
    var f = form
    do { val x = f % (c.length * v.length); sb.append(c[x / v.length]).append(v[x % v.length]); f /= c.length * v.length } while (f > 0)
    return sb.toString().replaceFirstChar { it.uppercase() }
}

/** The hex each region is most at: for each hex, the region with the most people there (none: no band there). */
fun CultureRegions.hexRegion(hexes: Int, hexOf: (HexGroup) -> Hex): IntArray {
    val best = IntArray(hexes) { -1 }; val most = DoubleArray(hexes)
    val byHex = HashMap<Long, Double>()
    for (i in bands.indices) {
        val h = hexOf(bands[i]).i
        val k = h.toLong() * (bands.size + 1) + region[i]
        val p = (byHex[k] ?: 0.0) + bands[i].size
        byHex[k] = p
        if (p > most[h]) { most[h] = p; best[h] = region[i] }
    }
    return best
}

/**
 * Neighbours that understand each other at this or better and hold at most this many customs differently are of one region
 * ([cultureRegions]). The report's, not measured.
 */
internal const val REGION_UNDERSTOOD = 0.75f
internal const val REGION_CUSTOMS_APART = 1
