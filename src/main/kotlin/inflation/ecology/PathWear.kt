package inflation.ecology

import inflation.world.Hex
import inflation.world.get
import inflation.world.set

import inflation.world.HexLand
import inflation.world.HexMap
import inflation.world.HexWorld
import kotlin.math.max
import kotlin.math.min

/**
 * Paths: how worn the way is from each hex to each of its six neighbours (by border, [HexMap.edge]),
 * 0..1. Every group that goes that way through the country (not just shifting to the next hex)
 * wears it, the heavier and more numerous the more; plants grow back over it, the faster the
 * greener the ground. Worn paths make the going easier, the more so in broken country, so packs
 * and people keep to them: they gather at the easy crossings, passes and fords, and there hunters
 * wait. A band wears the ways out of the hex it lives in too ([CAMP_WEAR]). Ways wear between land hexes only.
 */
class PathWear(private val hex: HexMap) {
    val paths = FloatArray(hex.size * 6)

    /** How worn the busiest path through hex [h] is. */
    fun at(h: Hex): Float { var m = 0f; for (d in 0 until 6) m = max(m, paths[hex.edge(h, d)]); return m }

    /**
     * This week's ([days]) going wears the ways [groups] went; the rest grow back a little, the faster the greener the ground.
     * [toward], where given, shares a band's wear about its hex out over its six borders (six in all, 1 each when even).
     */
    fun week(groups: List<HexGroup>, hw: HexWorld, land: HexLand, days: Int, toward: ((HexGroup) -> FloatArray?)? = null) {
        for (h in hex.all) {
            val p = hw.firstPlaceIn(h)
            val regrow = PATH_FADE * (0.3f + (if (!p.none) land.plants.green[p] else 0f)) * days / 7f
            for (d in 0 until 6) { val i = hex.edge(h, d); if (paths[i] > 0f) paths[i] = max(0f, paths[i] - regrow * paths[i] - 1e-4f) }
        }
        // Only going through country wears a way: a week's shift to the next hex is ranging over ground a
        // group grazes or forages anyway (a hex is wider than a day's going about), spread over it, not one line.
        // (And a band living in a hex wears ways out of it on every side: its foragers, hunters and visitors go out and
        // back the year round, so ground it lives on opens up around it (Olli 2026-11-07). A week's worth, as a through
        // going's wear, spread over its six borders.)
        // (Over land only: its people go out along the shore, not into the sea, so no way wears toward a sea or lake hex
        // ([HexMap.heart] below 0). Before, every coastal band wore ways into the water on its sea sides, and the viewer
        // drew paths into the sea: Olli 2026-11-13.)
        for (g in groups) {
            if (!g.alive || g.band == null) continue
            val h = hw.placeHex[g.place]
            if (hex.heart[h] < 0) continue
            val w0 = min(1f, g.size * g.species.massKg / PATH_MASS_KG) * CAMP_WEAR * days / 7f
            val share = toward?.invoke(g)
            for (d in 0 until 6) {
                val nb = hex.neighbour(h, d); if (nb.none || hex.heart[nb] < 0) continue
                val back = (d + 3) % 6
                val w = min(1f, w0 * (share?.get(d) ?: 1f))
                paths[hex.edge(h, d)] += w * (1f - paths[hex.edge(h, d)])
                paths[hex.edge(nb, back)] += w * (1f - paths[hex.edge(nb, back)])
            }
        }
        for (g in groups) {
            // (Bands that hold their home hex wear ways by going between hexes to their neighbours instead: their trips, [trip], [walkOver].)
            if (!g.alive || g.path.size < 3) continue
            val w = min(1f, g.size * g.species.massKg / PATH_MASS_KG)
            for (i in 0 until g.path.size - 1) {
                val a = g.path[i]; val b = g.path[i + 1]
                if (hex.heart[a] < 0 || hex.heart[b] < 0) continue
                val d = (0 until 6).firstOrNull { hex.neighbour(a, it) == b } ?: continue
                val back = (0 until 6).firstOrNull { hex.neighbour(b, it) == a } ?: continue
                paths[hex.edge(a, d)] += PATH_WEAR * w * (1f - paths[hex.edge(a, d)])
                paths[hex.edge(b, back)] += PATH_WEAR * w * (1f - paths[hex.edge(b, back)])
            }
        }
    }

    /** People went along [way] (hexes side by side, first to last), and back if [back], wearing each border on it by [w]. */
    fun along(way: List<Hex>, w: Float, back: Boolean = true) {
        for (i in 0 until way.size - 1) {
            val a = way[i]; val b = way[i + 1]
            if (hex.heart[a] < 0 || hex.heart[b] < 0) continue
            val d = (0 until 6).firstOrNull { hex.neighbour(a, it) == b } ?: continue
            paths[hex.edge(a, d)] += w * (1f - paths[hex.edge(a, d)])
            if (back) { val e = hex.edge(b, (d + 3) % 6); paths[e] += w * (1f - paths[e]) }
        }
    }
}

/**
 * How band group [g]'s wear about its hex is shared over its six borders when bands hold their home hex
 * ([inflation.Tuning.bandsStay]): half of it evenly (its foragers and hunters going out and back all round), half toward
 * the bands it is tied to within [NEIGHBOUR_RINGS] hexes, each by its tie, along the first step of the way to it ([firstStep]):
 * so the ways to the neighbours it deals with most wear into tracks. Six in all; null (even) for a band with no such ties.
 * [byId]: the living groups by id.
 */
internal fun HexGroups.wearToward(g: HexGroup, byId: Map<Int, HexGroup>): FloatArray? {
    val b = g.band ?: return null
    val h = hw.placeHex[g.place]
    val dir = FloatArray(6); var sum = 0f
    for ((id, tie) in b.ties) {
        if (tie <= 0f) continue
        val o = byId[id] ?: continue
        val oh = hw.placeHex[o.place]
        if (oh == h || hex.distanceKm(h, oh) > (NEIGHBOUR_RINGS + 0.5) * hex.hexKm) continue
        val d = firstStep(h, oh); if (d < 0) continue
        dir[d] += tie; sum += tie
    }
    if (sum <= 0f) return null
    return FloatArray(6) { d -> 0.5f + 3f * dir[d] / sum }
}

/** The border of hex [from] (0..5) whose land neighbour lies nearest [to]: the first step of the way there; -1 for none. */
internal fun HexGroups.firstStep(from: Hex, to: Hex): Int {
    var best = -1; var bestKm = hex.distanceKm(from, to)
    for (d in 0 until 6) {
        val nb = hex.neighbour(from, d)
        if (nb.none || hex.heart[nb] < 0) continue
        val km = hex.distanceKm(nb, to)
        if (km < bestKm - 1e-6) { bestKm = km; best = d }
    }
    return best
}
