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

/**
 * Exact Euclidean distance, km, from every cell to the nearest cell where [source] is true, written
 * to [out]; [far] where there is none. Separable (Felzenszwalb and Huttenlocher): squared distances
 * along each row, then down each column, rows and columns in parallel.
 */
fun distanceKm(grid: Grid, out: FloatArray, far: Float, source: (Int) -> Boolean) {
    val w = grid.width; val h = grid.height
    val inf = 1e18
    val rows = DoubleArray(grid.size)
    java.util.stream.IntStream.range(0, h).parallel().forEach { y ->
        val f = DoubleArray(w) { if (source(y * w + it)) 0.0 else inf }
        val d = DoubleArray(w)
        squaredDistance1d(f, w, d, IntArray(w), DoubleArray(w + 1))
        System.arraycopy(d, 0, rows, y * w, w)
    }
    java.util.stream.IntStream.range(0, w).parallel().forEach { x ->
        val f = DoubleArray(h) { rows[it * w + x] }
        val d = DoubleArray(h)
        squaredDistance1d(f, h, d, IntArray(h), DoubleArray(h + 1))
        for (y in 0 until h) out[y * w + x] = if (d[y] >= inf / 2) far else (sqrt(d[y]) * grid.cellKm).toFloat()
    }
}

/** Lower envelope of parabolas: d[q] = min over p of (q - p)² + f[p]. */
private fun squaredDistance1d(f: DoubleArray, n: Int, d: DoubleArray, v: IntArray, z: DoubleArray) {
    var k = 0
    v[0] = 0; z[0] = Double.NEGATIVE_INFINITY; z[1] = Double.POSITIVE_INFINITY
    for (q in 1 until n) {
        if (f[q] >= 1e17) continue // no source here: it never lowers the envelope
        if (f[v[k]] >= 1e17) { v[k] = q; continue }
        var s: Double
        while (true) {
            s = ((f[q] + q.toDouble() * q) - (f[v[k]] + v[k].toDouble() * v[k])) / (2.0 * q - 2.0 * v[k])
            if (s <= z[k] && k > 0) k-- else break
        }
        if (s <= z[k]) { v[k] = q } else { k++; v[k] = q; z[k] = s }
        z[k + 1] = Double.POSITIVE_INFINITY
    }
    if (f[v[0]] >= 1e17) { for (q in 0 until n) d[q] = 1e18; return }
    k = 0
    for (q in 0 until n) {
        while (z[k + 1] < q) k++
        val dq = (q - v[k]).toDouble()
        d[q] = dq * dq + f[v[k]]
    }
}
