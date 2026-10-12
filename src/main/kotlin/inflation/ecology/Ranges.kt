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

    /**
     * Layers read together, whose summed-area tables are interleaved: the totals of all of them at
     * one corner sit side by side in memory, so looking up a box in every layer touches a few cache
     * lines instead of one per layer.
     */
    inner class Stack(val depth: Int) {
        internal val sat = DoubleArray((w + 1) * (h + 1) * depth)
        internal var used = 0
        private val layers = ArrayList<Layer>(depth)
        fun layer() = Layer(this).also { layers += it }

        /** Rebuilds every layer's summed-area table in one pass over memory, as [Layer.build] would one by one. */
        fun build() {
            val k = depth
            val cells = Array(layers.size) { layers[it].cell }
            val row = DoubleArray(k)
            for (y in 0 until h) {
                row.fill(0.0)
                var above = (y * (w + 1) + 1) * k
                var here = ((y + 1) * (w + 1) + 1) * k
                for (x in 0 until w) {
                    val c = y * w + x
                    for (l in cells.indices) {
                        row[l] += cells[l][c]
                        sat[here + l] = sat[above + l] + row[l]
                    }
                    above += k; here += k
                }
            }
        }
    }

    /** One summary layer: totals per coarse cell and their summed-area table. */
    inner class Layer internal constructor(stack: Stack?) {
        val cell = FloatArray(size)
        private val stride: Int
        private val slot: Int
        private val sat: DoubleArray

        init {
            if (stack == null) { stride = 1; slot = 0; sat = DoubleArray((w + 1) * (h + 1)) }
            else { require(stack.used < stack.depth) { "stack full" }; stride = stack.depth; slot = stack.used++; sat = stack.sat }
        }

        private fun at(x: Int, y: Int) = (y * (w + 1) + x) * stride + slot

        fun clear() = cell.fill(0f)

        /** Rebuilds the summed-area table from [cell]. */
        fun build() {
            for (y in 0 until h) {
                var row = 0.0
                for (x in 0 until w) {
                    row += cell[y * w + x]
                    sat[at(x + 1, y + 1)] = sat[at(x + 1, y)] + row
                }
            }
        }

        /** Total over coarse cells [x0, x1) x [y0, y1). */
        fun box(x0: Int, y0: Int, x1: Int, y1: Int): Double =
            sat[at(x1, y1)] - sat[at(x1, y0)] - sat[at(x0, y1)] + sat[at(x0, y0)]

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

    fun layer() = Layer(null)
    fun stack(depth: Int) = Stack(depth)

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
