package inflation.ecology

import inflation.world.HexLand
import inflation.world.HexMap
import inflation.world.HexWorld
import kotlin.math.max
import kotlin.math.min

/**
 * Paths: how worn the way is from each hex to each of its six neighbours (index h * 6 + side),
 * 0..1. Every group that goes that way wears it, the heavier and more numerous the more; plants
 * grow back over it, the faster the greener the ground. Worn paths make the going easier, the
 * more so in broken country, so herds and people keep to them: they gather at the easy
 * crossings, passes and fords, and there hunters wait.
 */
class PathWear(private val hex: HexMap) {
    val paths = FloatArray(hex.size * 6)

    /** How worn the busiest path through hex [h] is. */
    fun at(h: Int): Float { var m = 0f; for (d in 0 until 6) m = max(m, paths[h * 6 + d]); return m }

    /** This week's ([days]) going wears the ways [groups] went; the rest grow back a little, the faster the greener the ground. */
    fun week(groups: List<HexGroup>, hw: HexWorld, land: HexLand, days: Int) {
        for (h in 0 until hex.size) {
            val p = (0 until HexWorld.BANDS_M.size).map { hw.placeOf[it][h] }.firstOrNull { it >= 0 } ?: -1
            val regrow = PATH_FADE * (0.3f + (if (p >= 0) land.plants.green[p] else 0f)) * days / 7f
            for (d in 0 until 6) { val i = h * 6 + d; if (paths[i] > 0f) paths[i] = max(0f, paths[i] - regrow * paths[i] - 1e-4f) }
        }
        for (g in groups) {
            if (!g.alive || g.path.size < 2) continue
            val w = min(1f, g.size * g.species.massKg / PATH_MASS_KG)
            for (i in 0 until g.path.size - 1) {
                val a = g.path[i]; val b = g.path[i + 1]
                val d = (0 until 6).firstOrNull { hex.neighbour(a, it) == b } ?: continue
                val back = (0 until 6).firstOrNull { hex.neighbour(b, it) == a } ?: continue
                paths[a * 6 + d] += PATH_WEAR * w * (1f - paths[a * 6 + d])
                paths[b * 6 + back] += PATH_WEAR * w * (1f - paths[b * 6 + back])
            }
        }
    }
}
