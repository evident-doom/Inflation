package inflation.world

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The world summarised onto hexagonal cells [hexKm] across (centre to centre), for a simulation
 * that moves a few cells a week. The fine terrain is kept only for generation; what the
 * simulation needs from it is carried by the cells and by the borders between them:
 *
 * - A cell knows its mix of ground: land share, its elevations from valley floor to peak, how
 *   much of it is flat, slope or crag, and its rivers.
 * - A border knows what it takes to cross it: the effort of the easiest way from the heart of one
 *   cell to the heart of the other over the fine terrain, the highest point on that way (a pass),
 *   the biggest river it crosses (a ford or a barrier), or that there is no way at all.
 *
 * Hexes are "pointy-top" in odd-row offset layout: row r is shifted half a cell right when r is odd.
 */
class HexMap(private val world: World, val hexKm: Double = 10.0) {
    private val grid = world.grid
    private val terrain = world.terrain
    private val rowKm = hexKm * sqrt(3.0) / 2
    val cols = (grid.width * grid.cellKm / hexKm).toInt() + 1
    val rows = (grid.height * grid.cellKm / rowKm).toInt() + 1
    val size = cols * rows
    /** The hex each fine cell belongs to. */
    val owner = IntArray(grid.size)
    /** Per hex: fine cells, of which land; the fine cell at its heart (easiest ground near its centre), -1 if all sea. */
    val cells = IntArray(size)
    val landCells = IntArray(size)
    val heart = IntArray(size) { -1 }
    /** Per hex: lowest, median and highest land elevation, m; shares of land that is flat (< 5%), slope, and crag (> 45%). */
    val lowM = FloatArray(size); val midM = FloatArray(size); val highM = FloatArray(size)
    val flat = FloatArray(size); val crag = FloatArray(size)
    /**
     * Per way of travelling ([Travel]) and hex border (hex × 6 directions): effort to cross, flat-ground
     * km (infinite: no way). For the light walker also the highest point on the way, m, and the biggest
     * river crossed, m³/s.
     */
    val crossings = Array(Travel.entries.size) { FloatArray(size * 6) { Float.POSITIVE_INFINITY } }
    val crossing get() = crossings[Travel.FOOT.ordinal]
    val passM = FloatArray(size * 6)
    val riverM3s = FloatArray(size * 6)
    /** Per way of travelling: effort per fine cell, as a multiple of flat open ground. */
    val costs = Array(Travel.entries.size) { FloatArray(grid.size) }
    val walk get() = costs[Travel.FOOT.ordinal]

    init {
        for (i in 0 until grid.size) {
            val base = terrain.baseCost[i]
            if (!base.isFinite() || world.isSea[i]) { for (c in costs) c[i] = Float.POSITIVE_INFINITY; continue }
            val s = terrain.slope[i]
            val land = terrain.landCost[i]          // without the river
            val river = base / land                 // what the river adds, 1 where there is none
            val climb = exp(3.5f * s)               // already in both
            for (tr in Travel.entries) {
                val slope = exp(tr.ease * s) * (if (s > tr.steep) exp(BARRIER * (s - tr.steep)) else 1f)
                val water = when (tr) { Travel.SWIM -> 1f + 0.2f * (river - 1f); Travel.LADEN -> river * river; else -> river }
                costs[tr.ordinal][i] = land / climb * slope * water
            }
        }
        for (i in 0 until grid.size) {
            val h = hexAt(grid.x(i) * grid.cellKm + 0.5 * grid.cellKm, grid.y(i) * grid.cellKm + 0.5 * grid.cellKm)
            owner[i] = h
            cells[h]++
            if (!world.isSea[i]) landCells[h]++
        }
        summarise()
        java.util.stream.IntStream.range(0, size).parallel().forEach { a ->
            if (heart[a] < 0) return@forEach
            for (d in 0 until 3) {
                val b = neighbour(a, d)
                if (b < 0 || heart[b] < 0) continue
                for (tr in Travel.entries) cross(a, b, d, tr)
            }
        }
    }

    /** The hex containing the point (x, y) km. */
    fun hexAt(xKm: Double, yKm: Double): Int {
        // To axial coordinates (pointy-top), round in cube coordinates, back to odd-row offset.
        val q = (sqrt(3.0) / 3 * xKm - 1.0 / 3 * yKm) / (hexKm / sqrt(3.0))
        val r = (2.0 / 3 * yKm) / (hexKm / sqrt(3.0))
        var cx = q; var cz = r; var cy = -cx - cz
        var rx = cx.roundToInt().toDouble(); var ry = cy.roundToInt().toDouble(); var rz = cz.roundToInt().toDouble()
        val dx = abs(rx - cx); val dy = abs(ry - cy); val dz = abs(rz - cz)
        if (dx > dy && dx > dz) rx = -ry - rz else if (dy > dz) ry = -rx - rz else rz = -rx - ry
        val row = rz.toInt()
        val col = rx.toInt() + (row - (row and 1)) / 2
        return row.coerceIn(0, rows - 1) * cols + col.coerceIn(0, cols - 1)
    }

    /** Centre of hex [h], km. */
    fun centreKm(h: Int): Pair<Double, Double> {
        val col = h % cols; val row = h / cols
        return hexKm * (col + 0.5 * (row and 1)) to rowKm * row
    }

    /** The neighbour of [h] in direction [d] (0 E, 1 SE, 2 SW, 3 W, 4 NW, 5 NE), or -1 off the map. */
    fun neighbour(h: Int, d: Int): Int {
        val col = h % cols; val row = h / cols
        val odd = row and 1
        val (dc, dr) = if (odd == 1) ODD[d] else EVEN[d]
        val c = col + dc; val r = row + dr
        return if (c < 0 || r < 0 || c >= cols || r >= rows) -1 else r * cols + c
    }

    private fun summarise() {
        val elevs = Array(size) { ArrayList<Float>() }
        val flatN = IntArray(size); val cragN = IntArray(size)
        for (i in 0 until grid.size) {
            if (world.isSea[i]) continue
            val h = owner[i]
            elevs[h] += world.elevation[i]
            val s = terrain.slope[i]
            if (s < 0.05f) flatN[h]++
            if (s > 0.45f) cragN[h]++
        }
        for (h in 0 until size) {
            val e = elevs[h]
            if (e.isEmpty()) continue
            e.sort()
            lowM[h] = e.first(); midM[h] = e[e.size / 2]; highM[h] = e.last()
            flat[h] = flatN[h].toFloat() / e.size; crag[h] = cragN[h].toFloat() / e.size
        }
        // The heart of a hex: its easiest ground, preferring the middle, so a hex of valley and ridge
        // is entered at the valley.
        val best = FloatArray(size) { Float.POSITIVE_INFINITY }
        for (i in 0 until grid.size) {
            if (!walk[i].isFinite()) continue
            val h = owner[i]
            val (cx, cy) = centreKm(h)
            val off = kotlin.math.hypot(grid.x(i) * grid.cellKm - cx, grid.y(i) * grid.cellKm - cy) / hexKm
            val score = (walk[i] * (1 + 2 * off)).toFloat()
            if (score < best[h]) { best[h] = score; heart[h] = i }
        }
    }

    /** Works out the border between [a] and its neighbour [b] in direction [d], keeping to the two hexes. */
    private fun cross(a: Int, b: Int, d: Int, tr: Travel) {
        val walk = costs[tr.ordinal]
        val from = heart[a]; val to = heart[b]
        val dist = HashMap<Int, Float>()
        val via = HashMap<Int, Int>()
        val q = PriorityQueue<Pair<Float, Int>>(compareBy { it.first })
        dist[from] = 0f; q += 0f to from
        val ck = grid.cellKm.toFloat()
        var found = false
        while (q.isNotEmpty()) {
            val (dd, i) = q.poll()
            if (i == to) { found = true; break }
            if (dd > (dist[i] ?: Float.POSITIVE_INFINITY)) continue
            val xi = grid.x(i); val yi = grid.y(i)
            for (k in 0 until 8) {
                val nx = xi + DX8[k]; val ny = yi + DY8[k]
                if (!grid.inBounds(nx, ny)) continue
                val j = grid.index(nx, ny)
                if (owner[j] != a && owner[j] != b) continue
                if (!walk[j].isFinite()) continue
                val nd = dd + (if (k < 4) 1f else 1.4142f) * ck * 0.5f * (walk[i] + walk[j])
                if (nd < (dist[j] ?: Float.POSITIVE_INFINITY)) { dist[j] = nd; via[j] = i; q += nd to j }
            }
        }
        if (!found) return
        // Along the way: its highest point and the biggest river crossed.
        var top = world.elevation[to]; var river = 0f
        var i = to
        while (i != from) {
            top = max(top, world.elevation[i])
            river = max(river, world.water.discharge[i])
            i = via.getValue(i)
        }
        val e = dist.getValue(to)
        val back = (d + 3) % 6
        crossings[tr.ordinal][a * 6 + d] = e; crossings[tr.ordinal][b * 6 + back] = e
        if (tr != Travel.FOOT) return
        passM[a * 6 + d] = top; passM[b * 6 + back] = top
        riverM3s[a * 6 + d] = river; riverM3s[b * 6 + back] = river
    }

    /** Least effort from hex [a] to every hex, over the borders, travelling as [tr]; infinite where there is no way. */
    fun efforts(a: Int, tr: Travel = Travel.FOOT): FloatArray {
        val crossing = crossings[tr.ordinal]
        val dist = FloatArray(size) { Float.POSITIVE_INFINITY }
        val q = PriorityQueue<Pair<Float, Int>>(compareBy { it.first })
        dist[a] = 0f; q += 0f to a
        while (q.isNotEmpty()) {
            val (d, h) = q.poll()
            if (d > dist[h]) continue
            for (k in 0 until 6) {
                val n = neighbour(h, k)
                if (n < 0) continue
                val nd = d + crossing[h * 6 + k]
                if (nd < dist[n]) { dist[n] = nd; q += nd to n }
            }
        }
        return dist
    }

    /**
     * Ways of travelling, each with how hard slopes are (effort grows as e^(ease × slope)) and the slope
     * (rise over run over a km) above which ground closes fast.
     */
    enum class Travel(val ease: Float, val steep: Float) {
        /** On foot and lightly loaded: most animals, hunting and raiding parties. */
        FOOT(3.5f, 0.35f),
        /** Sure-footed on crags: ibex, mouflon, leopards. */
        CLIMB(2f, 0.8f),
        /** A band moving camp with its stores, children and old: slopes and deep water cost far more. */
        LADEN(5f, 0.25f),
        /** Swimmers and waders (deer, horses, hippos): rivers stop them far less. */
        SWIM(3.5f, 0.35f),
    }

    companion object {
        /** How fast ground closes above a way of travelling's steepest. */
        const val BARRIER = 25f
        // Neighbour offsets (col, row) for even and odd rows: E, SE, SW, W, NW, NE.
        private val EVEN = arrayOf(1 to 0, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1)
        private val ODD = arrayOf(1 to 0, 1 to 1, 0 to 1, -1 to 0, 0 to -1, 1 to -1)
        private val DX8 = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1)
        private val DY8 = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)
    }
}
