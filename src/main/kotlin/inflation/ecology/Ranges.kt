package inflation.ecology

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The world summarised on a coarse grid of [km]-sized cells, once per step, for groups that roam
 * ranges many km across. Each layer is a total per coarse cell, kept with a summed-area table so
 * that the total over any box of cells costs four lookups however large the box.
 *
 * A range is a disc around a group's centre; it is looked up as the box of equal area.
 */
class CoarseMaps(fineWidth: Int, fineHeight: Int, val fineKm: Float, val km: Float) {
    /** Fine cells per coarse cell along each side. */
    val ratio = max(1, (km / fineKm).toInt())
    val w = ceil(fineWidth / ratio.toDouble()).toInt()
    val h = ceil(fineHeight / ratio.toDouble()).toInt()
    val size = w * h

    /** One summary layer: totals per coarse cell and their summed-area table. */
    inner class Layer {
        val cell = FloatArray(size)
        private val sat = DoubleArray((w + 1) * (h + 1))

        fun clear() = cell.fill(0f)

        /** Rebuilds the summed-area table from [cell]. */
        fun build() {
            for (y in 0 until h) {
                var row = 0.0
                for (x in 0 until w) {
                    row += cell[y * w + x]
                    sat[(y + 1) * (w + 1) + x + 1] = sat[y * (w + 1) + x + 1] + row
                }
            }
        }

        /** Total over coarse cells [x0, x1) x [y0, y1). */
        fun box(x0: Int, y0: Int, x1: Int, y1: Int): Double =
            sat[y1 * (w + 1) + x1] - sat[y0 * (w + 1) + x1] - sat[y1 * (w + 1) + x0] + sat[y0 * (w + 1) + x0]

        /** Total over the range of radius [rKm] around (x, y) in fine cells. */
        fun range(x: Double, y: Double, rKm: Float, b: Box = Box()): Double {
            b.set(this@CoarseMaps, x, y, rKm)
            return box(b.x0, b.y0, b.x1, b.y1)
        }
    }

    /** The coarse cells covering a range: the box of the disc's area, clipped to the map. */
    class Box {
        var x0 = 0; var y0 = 0; var x1 = 0; var y1 = 0

        fun set(m: CoarseMaps, x: Double, y: Double, rKm: Float) {
            val cx = x * m.fineKm / m.km; val cy = y * m.fineKm / m.km
            val half = 0.886 * rKm / m.km // a square of side 1.77 r has the disc's area
            x0 = floor(cx - half).toInt().coerceIn(0, m.w - 1)
            y0 = floor(cy - half).toInt().coerceIn(0, m.h - 1)
            x1 = ceil(cx + half).toInt().coerceIn(x0 + 1, m.w)
            y1 = ceil(cy + half).toInt().coerceIn(y0 + 1, m.h)
        }

        val cells: Int get() = (x1 - x0) * (y1 - y0)
    }

    fun layer() = Layer()

    /** Coarse cell of fine cell (fx, fy). */
    fun of(fx: Int, fy: Int) = min(h - 1, fy / ratio) * w + min(w - 1, fx / ratio)

    /** Coarse cell of a position in fine cells. */
    fun of(x: Double, y: Double) = of(x.toInt().coerceAtLeast(0), y.toInt().coerceAtLeast(0))
}

/**
 * How much two ranges overlap, 0 (apart) to 1 (one lies within the other): a smooth stand-in for
 * the shared area of two discs, from the distance [d] between centres and the radii.
 */
fun overlap(d: Float, r1: Float, r2: Float): Float {
    val reach = r1 + r2
    if (d >= reach) return 0f
    val inner = kotlin.math.abs(r1 - r2)
    if (d <= inner) return 1f
    return 1f - (d - inner) / (reach - inner)
}
