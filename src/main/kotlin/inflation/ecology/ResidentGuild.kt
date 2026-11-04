package inflation.ecology

import inflation.Books
import inflation.Par
import inflation.world.Place
import inflation.world.hexArray
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The resident hunters at the week's meetings ([meet]), by the rules packs meet by (Olli 2026-10-31: packs and residents
 * by the same rules). For each resident hunter kind in each hex, one band of it stands in for all its bands there
 * ([bands] of them): it stays the week at its place, at home there, and the packs, bands and other resident hunters
 * whose timetables bring them by meet it as they would a pack, as often as they would meet that many bands
 * ([encounters]), each meeting settled alone. It stands at the hex's place that holds the most of them. What the meetings
 * kill of the stand-ins comes off the kind's numbers there, over the hex's places by their heads ([done]); what they rob
 * or kill the residents eat.
 */
internal class ResidentParties(private val gs: HexGroups) {
    /** One kind's stand-in at a place: a band of [adults] grown and [young] young, standing in for [bands] bands. */
    inner class StandIn(val g: HexGroup, val k: Int, val adults: Int, val young: Int, val bands: Float, grownHeads: Float, youngHeads: Float) {
        var deadAdults = 0; var deadYoung = 0
        /** The kind's grown and young heads in the hex not yet killed at the meetings: no more can be ([take]). */
        private var leftAdults = grownHeads; private var leftYoung = youngHeads
        /** Of [n] grown (young if [young]) the meetings would kill, those there are left to kill; taken off what is left. */
        fun take(n: Int, young: Boolean): Int {
            val left = if (young) leftYoung else leftAdults
            val k = minOf(n, kotlin.math.floor(left).toInt())
            if (young) { leftYoung -= k; deadYoung += k } else { leftAdults -= k; deadAdults += k }
            return k
        }
        /** Before a meeting: the band whole again (each meeting is with a band of its own). */
        fun fresh() { g.adults = adults; g.young = young; g.meatMet = 0f }
        /** After it: what it lost, and what it took (eaten). */
        fun settle() {
            deadAdults += adults - g.adults; deadYoung += young - g.young
            if (g.meatMet > 0f) gs.land.books?.flow(Books.Stuff.MATTER, "eaten by resident hunters at meetings", -g.meatMet.toDouble())
            g.meatMet = 0f; g.adults = adults; g.young = young
        }
    }

    private val standIns = ArrayList<StandIn>()
    /** The stand-ins as groups, in the order they follow the groups at the meetings. */
    val parties = ArrayList<HexGroup>()
    private val nHexes = gs.hex.size
    /** The stand-ins of the hex of index h are [inHex] from [hexStart] of h until that of h + 1 (indices into [standIns], in the order made). */
    @PublishedApi internal val hexStart: IntArray
    @PublishedApi internal val inHex: IntArray

    init {
        val f = gs.fauna
        // (Each stand-in's hex, as [standIns]: filed by hex below.)
        var hexOf = IntArray(256); var n = 0
        for (k in f.kinds.indices) {
            val s = f.kinds[k]
            if (!s.carnivore) continue
            // (Each hex's stand-in worked out on all cores, then taken in the hexes' order.)
            val made = arrayOfNulls<StandIn>(nHexes)
            Par.forEachHex("residents: settle", nHexes) { h ->
                var a = 0f; var y = 0f; var p = -1; var most = 0f
                for (q in gs.hw.placesOf(h)) {
                    val area = gs.land.areaKm2[q]; val hq = (f.adults[k][q] + f.young[k][q]) * area
                    a += f.adults[k][q] * area; y += f.young[k][q] * area
                    if (hq > most) { most = hq; p = q }
                }
                if (p < 0) return@forEachHex
                val bands = (a + y) / s.groupSize
                if (bands < MIN_BANDS) return@forEachHex
                val grown = max(1, (s.groupSize * a / (a + y)).roundToInt()); val little = (s.groupSize * y / (a + y)).roundToInt()
                // (Its id is out of the groups' range, fixed by kind and hex: the meetings' order draws repeat run to run.)
                val g = HexGroup(-1 - (k * gs.hex.size + h.i), s, Place(p), grown, little)
                g.home = h; g.path = hexArray(1) { h }; g.days = FloatArray(1)
                made[h.i] = StandIn(g, k, grown, little, bands, a, y)
            }
            val more = made.count { it != null }
            standIns.ensureCapacity(standIns.size + more); parties.ensureCapacity(parties.size + more)
            for (h in made.indices) { val si = made[h] ?: continue
                if (n == hexOf.size) hexOf = hexOf.copyOf(2 * n)
                hexOf[n++] = h; standIns += si; parties += si.g }
        }
        // (A count per hex, then each stand-in into its hex's run, in the order made: a stable sort by hex.)
        hexStart = IntArray(nHexes + 1)
        for (x in 0 until n) hexStart[hexOf[x] + 1]++
        for (h in 0 until nHexes) hexStart[h + 1] += hexStart[h]
        inHex = IntArray(n); val fill = hexStart.copyOf(nHexes)
        for (x in 0 until n) inHex[fill[hexOf[x]]++] = x
    }

    /** Calls [action] with the index among the stand-ins ([of] counts them after the groups) of each in the hex of index [h], in the order made. */
    inline fun forEachIn(h: Int, action: (Int) -> Unit) { for (c in hexStart[h] until hexStart[h + 1]) action(inHex[c]) }

    /** The stand-ins of each hex that has two or more (only those can meet each other), hexes in order, each hex's in the order made. */
    fun standInsByHex(): List<List<StandIn>> {
        val out = ArrayList<List<StandIn>>()
        for (h in 0 until nHexes) {
            val a = hexStart[h]; val b = hexStart[h + 1]
            if (b - a >= 2) out += List(b - a) { standIns[inHex[a + it]] }
        }
        return out
    }

    /** The stand-in at index [i] of the meetings' parties (the groups first), or null for a group. */
    fun of(i: Int): StandIn? = (i - gs.groups.size).let { if (it >= 0) standIns[it] else null }

    /** After the meetings: the residents the stand-ins lost come off their numbers. */
    fun done() {
        val f = gs.fauna
        for (si in standIns) {
            if (si.deadAdults == 0 && si.deadYoung == 0) continue
            val places = gs.hw.placesOf(si.g.home); val a = f.adults[si.k]; val y = f.young[si.k]
            var grown = 0f; var little = 0f
            for (q in places) { grown += a[q] * gs.land.areaKm2[q]; little += y[q] * gs.land.areaKm2[q] }
            // (Each place loses its share by its heads: a share of its density.)
            val lostA = if (grown > 0f) min(1f, si.deadAdults / grown) else 0f; val lostY = if (little > 0f) min(1f, si.deadYoung / little) else 0f
            for (q in places) { a[q] *= 1f - lostA; y[q] *= 1f - lostY }
        }
    }

    companion object {
        /** Fewer bands of a kind at a place than this stand for none: too thin to meet. */
        const val MIN_BANDS = 0.05f
    }
}

