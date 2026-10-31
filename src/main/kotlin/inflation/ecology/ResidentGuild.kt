package inflation.ecology

import inflation.Books
import inflation.world.Place
import inflation.world.hexArray
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The resident hunters at the week's meetings ([meet]), by the rules packs meet by (Olli 2026-10-31: packs and residents
 * by the same rules). For each resident hunter kind at each place, one band of it stands in for all its bands there
 * ([bands] of them): it stays the week at its place, at home there, and the packs, bands and other resident hunters
 * whose timetables bring them by meet it as they would a pack, as often as they would meet that many bands
 * ([encounters]), each meeting settled alone. What the meetings kill of the stand-ins comes off the kind's numbers there
 * ([done]); what they rob or kill the residents eat.
 */
internal class ResidentParties(private val gs: HexGroups) {
    /** One kind's stand-in at a place: a band of [adults] grown and [young] young, standing in for [bands] bands. */
    inner class StandIn(val g: HexGroup, val k: Int, val adults: Int, val young: Int, val bands: Float) {
        var deadAdults = 0; var deadYoung = 0
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

    init {
        val f = gs.fauna
        for (k in f.kinds.indices) {
            val s = f.kinds[k]
            if (!s.carnivore) continue
            for (p in 0 until gs.n) {
                val a = f.adults[k][p]; val y = f.young[k][p]
                if (a + y <= 0f) continue
                val heads = (a + y) * gs.land.areaKm2[p]
                val bands = heads / s.groupSize
                if (bands < MIN_BANDS) continue
                val grown = max(1, (s.groupSize * a / (a + y)).roundToInt()); val little = (s.groupSize * y / (a + y)).roundToInt()
                // (Its id is out of the groups' range, fixed by kind and place: the meetings' order draws repeat run to run.)
                val g = HexGroup(-1 - (k * gs.n + p), s, Place(p), grown, little)
                val h = gs.hw.placeHex[Place(p)]
                g.home = h; g.path = hexArray(1) { h }; g.days = FloatArray(1)
                standIns += StandIn(g, k, grown, little, bands); parties += g
            }
        }
    }

    /** The stand-in at index [i] of the meetings' parties (the groups first), or null for a group. */
    fun of(i: Int): StandIn? = (i - gs.groups.size).let { if (it >= 0) standIns[it] else null }

    /** After the meetings: the residents the stand-ins lost come off their numbers. */
    fun done() {
        val f = gs.fauna
        for (si in standIns) {
            if (si.deadAdults == 0 && si.deadYoung == 0) continue
            val p = si.g.place.i; val area = gs.land.areaKm2[p]
            f.adults[si.k][p] = max(0f, f.adults[si.k][p] - si.deadAdults / area)
            f.young[si.k][p] = max(0f, f.young[si.k][p] - si.deadYoung / area)
        }
    }

    companion object {
        /** Fewer bands of a kind at a place than this stand for none: too thin to meet. */
        const val MIN_BANDS = 0.05f
    }
}
