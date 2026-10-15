package inflation.world

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How much sun the slopes of each place get against level ground, by month: the shaded share of a place
 * (its slopes that face the pole) and the sunny rest, each with its own ratio. Pure geometry, worked out
 * once from the 1 km terrain: the sun's path for the latitude and the day (Duffie and Beckman's angles),
 * the direct beam on each slope over the day against the beam on level ground, plus the sky's diffuse
 * light, which a slope sees less of the steeper it is.
 */
class Sunlight(
    /** Share of each place on slopes facing the pole, 0..1. */
    val shade: FloatArray,
    /** [0 sunny, 1 shaded][month][place]: the day's sunlight against level ground. */
    val ratio: Array<Array<FloatArray>>,
) {
    companion object {
        /** Share of the sunlight that comes as diffuse light from the sky (about a quarter to two fifths, from memory). */
        const val DIFFUSE = 0.3
        /**
         * How much steeper the hillsides are than the 1 km grid's slopes: slopes reckoned on a coarse grid
         * average across ridges and valleys, and at 1 km come out roughly a third of those on a 30 m grid in
         * hill country (Zhang and Montgomery 1994; Chow and Hodgson 2009), from memory. It varies with the
         * terrain; a single factor is the simplification.
         */
        const val STEEPEN = 3.0

        fun of(world: World, placeCells: Array<IntArray>): Sunlight {
            val grid = world.grid
            val z = world.elevation
            val m = grid.cellKm * 1000
            val n = placeCells.size
            val shade = FloatArray(n)
            val ratio = Array(2) { Array(12) { FloatArray(n) } }
            val declination = DoubleArray(12) { Date(it * 30L + 15).solarDeclination }
            java.util.stream.IntStream.range(0, n).parallel().forEach { p ->
                val sum = Array(2) { DoubleArray(12) }; val count = IntArray(2)
                for (i in placeCells[p]) {
                    val x = grid.x(i); val y = grid.y(i)
                    fun at(dx: Int, dy: Int): Double { val xx = (x + dx).coerceIn(0, grid.width - 1); val yy = (y + dy).coerceIn(0, grid.height - 1); return max(0f, z[grid.index(xx, yy)]).toDouble() }
                    // Rise towards the east and towards the north (rows run south), m per m.
                    val east = (at(1, 0) - at(-1, 0)) / (2 * m) * STEEPEN
                    val north = (at(0, -1) - at(0, 1)) / (2 * m) * STEEPEN
                    val norm = sqrt(1 + east * east + north * north)
                    val ne = -east / norm; val nn = -north / norm; val nu = 1 / norm
                    val k = if (nn > 0) 1 else 0
                    count[k]++
                    val phi = world.climate.latitude[y] * PI / 180
                    for (mo in 0 until 12) {
                        val d = declination[mo]
                        var beam = 0.0; var level = 0.0
                        for (h in 0 until 48) {
                            val w = -PI + (h + 0.5) * PI / 24
                            val su = sin(phi) * sin(d) + cos(phi) * cos(d) * cos(w)
                            if (su <= 0) continue
                            val se = -cos(d) * sin(w)
                            val sn = cos(phi) * sin(d) - sin(phi) * cos(d) * cos(w)
                            level += su
                            beam += max(0.0, ne * se + nn * sn + nu * su)
                        }
                        sum[k][mo] += if (level > 0) (1 - DIFFUSE) * beam / level + DIFFUSE * (1 + nu) / 2 else 1.0
                    }
                }
                shade[p] = count[1].toFloat() / placeCells[p].size.coerceAtLeast(1)
                for (k in 0 until 2) for (mo in 0 until 12) ratio[k][mo][p] = if (count[k] > 0) (sum[k][mo] / count[k]).toFloat() else 1f
            }
            return Sunlight(shade, ratio)
        }
    }
}
