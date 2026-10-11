package inflation.world

import kotlin.math.sqrt

/**
 * Square-cell raster. Row 0 is the northern edge, column 0 the western edge.
 * Fields are flat FloatArrays indexed by [index].
 */
class Grid(val width: Int, val height: Int, val cellKm: Double) {
    val size: Int = width * height
    val cellM: Double = cellKm * 1000.0
    val cellAreaM2: Double = cellM * cellM

    fun index(x: Int, y: Int): Int = y * width + x
    fun x(i: Int): Int = i % width
    fun y(i: Int): Int = i / width
    fun inBounds(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height
    fun isEdge(i: Int): Boolean {
        val x = x(i); val y = y(i)
        return x == 0 || y == 0 || x == width - 1 || y == height - 1
    }

    /** Calls [f] with (neighbour index, distance in metres) for each of the up to 8 neighbours. */
    inline fun forNeighbours(i: Int, f: (Int, Double) -> Unit) {
        val x = x(i); val y = y(i)
        for (k in 0 until 8) {
            val nx = x + DX[k]; val ny = y + DY[k]
            if (nx in 0 until width && ny in 0 until height) f(ny * width + nx, DIST[k] * cellM)
        }
    }

    companion object {
        val DX = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
        val DY = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)
        val DIST = DoubleArray(8) { k -> if (DX[k] != 0 && DY[k] != 0) sqrt(2.0) else 1.0 }
    }
}

/** Box blur with the given radius in cells, applied separably; edges are clamped. */
fun blur(grid: Grid, src: FloatArray, radius: Int): FloatArray {
    if (radius <= 0) return src.copyOf()
    val w = grid.width; val h = grid.height
    val tmp = FloatArray(src.size)
    val out = FloatArray(src.size)
    val norm = 1f / (2 * radius + 1)
    for (y in 0 until h) {
        var acc = 0f
        for (k in -radius..radius) acc += src[y * w + k.coerceIn(0, w - 1)]
        for (x in 0 until w) {
            tmp[y * w + x] = acc * norm
            acc += src[y * w + (x + radius + 1).coerceAtMost(w - 1)] - src[y * w + (x - radius).coerceAtLeast(0)]
        }
    }
    for (x in 0 until w) {
        var acc = 0f
        for (k in -radius..radius) acc += tmp[k.coerceIn(0, h - 1) * w + x]
        for (y in 0 until h) {
            out[y * w + x] = acc * norm
            acc += tmp[(y + radius + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - radius).coerceAtLeast(0) * w + x]
        }
    }
    return out
}
