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
 * - A border knows what it takes to cross it, as two separate things. The ground: the effort of the
 *   easiest way from the heart of one cell to the heart of the other over the fine terrain, rivers
 *   left aside, and the highest point on that way (a pass). The water: a river lies on the border
 *   when its course passes between the two hexes' centres, so a river that crosses the map makes a
 *   continuous line of river borders; it is graded by its depth at the best crossing along that
 *   stretch (knee deep and less is no obstacle).
 * - A hex that is mostly lake or sea is water, not land.
 *
 * Hexes are "pointy-top" in odd-row offset layout: row r is shifted half a cell right when r is odd.
 */
class HexMap(private val world: World, val hexKm: Double = 10.0) {
    private val grid = world.grid
    private val terrain = world.terrain
    /** Distance between the centres of two rows of hexes, km. */
    val rowKm = hexKm * sqrt(3.0) / 2
    /** Area of one hex, km² (√3/2 of [hexKm] squared, [hexKm] being the distance between centres). */
    val areaKm2 = hexKm * hexKm * sqrt(3.0) / 2
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
    /** Per way of travelling ([Travel]) and hex border (hex × 6 directions): effort over the ground, flat-ground km, rivers left aside (infinite: no way). */
    val ground = Array(Travel.entries.size) { FloatArray(size * 6) { Float.POSITIVE_INFINITY } }
    /** Per way of travelling and border: the whole effort to cross, ground and water (infinite: no way). */
    val crossings = Array(Travel.entries.size) { FloatArray(size * 6) { Float.POSITIVE_INFINITY } }
    val crossing get() = crossings[Travel.FOOT.ordinal]
    /** Per border: highest point on the way over the ground, m. */
    val passM = FloatArray(size * 6)
    /** Per border: the river on it, its flow, m³/s, and its depth at the best crossing along the border, m (0: none). */
    val riverM3s = FloatArray(size * 6)
    val riverDepthM = FloatArray(size * 6)
    /** Whether each hex is water (mostly lake or sea). */
    val water = BooleanArray(size)
    /** Per way of travelling: effort per fine cell over the ground, rivers left aside, as a multiple of flat open ground. */
    val costs = Array(Travel.entries.size) { FloatArray(grid.size) }
    val walk get() = costs[Travel.FOOT.ordinal]

    init {
        for (i in 0 until grid.size) {
            val base = terrain.baseCost[i]
            if (!base.isFinite() || world.isSea[i]) { for (c in costs) c[i] = Float.POSITIVE_INFINITY; continue }
            val s = terrain.slope[i]
            val land = terrain.landCost[i]          // without the river
            val climb = exp(3.5f * s)               // already in it
            for (tr in Travel.entries) {
                val slope = exp(tr.ease * s) * (if (s > tr.steep) exp(BARRIER * (s - tr.steep)) else 1f)
                costs[tr.ordinal][i] = land / climb * slope * (if (terrain.cliff[i]) tr.cliff else 1f)
            }
        }
        val wet = IntArray(size)
        for (i in 0 until grid.size) {
            val h = hexAt(grid.x(i) * grid.cellKm + 0.5 * grid.cellKm, grid.y(i) * grid.cellKm + 0.5 * grid.cellKm)
            owner[i] = h
            cells[h]++
            if (!world.isSea[i]) landCells[h]++
            if (world.isSea[i] || world.water.isLake(i)) wet[h]++
        }
        for (h in 0 until size) water[h] = wet[h] * 2 > cells[h]
        summarise()
        for (h in 0 until size) if (water[h]) heart[h] = -1
        riversOnEdges()
        java.util.stream.IntStream.range(0, size).parallel().forEach { a ->
            if (heart[a] < 0) return@forEach
            for (d in 0 until 3) {
                val b = neighbour(a, d)
                if (b < 0 || heart[b] < 0) continue
                for (tr in Travel.entries) {
                    cross(a, b, d, tr)
                    // The whole crossing: the ground, plus the river on the border, if any.
                    val g = ground[tr.ordinal][a * 6 + d]
                    val w = tr.water(riverDepthM[a * 6 + d])
                    val e = if (!g.isFinite() || !w.isFinite()) Float.POSITIVE_INFINITY else g + (w - 1f) * RIVER_KM
                    crossings[tr.ordinal][a * 6 + d] = e; crossings[tr.ordinal][b * 6 + (d + 3) % 6] = e
                }
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

    /** Distance between the centres of hexes [a] and [b], km. */
    fun distanceKm(a: Int, b: Int): Double {
        val ra = a / cols; val rb = b / cols
        val dx = hexKm * ((a - ra * cols) - (b - rb * cols) + 0.5 * ((ra and 1) - (rb and 1)))
        val dy = rowKm * (ra - rb)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /** The hexes next to [ring] not yet in [seen] (which takes them in), in the order they are reached: the next ring outward. */
    fun nextRing(ring: List<Int>, seen: MutableSet<Int>): List<Int> {
        val next = ArrayList<Int>()
        for (h in ring) for (d in 0 until 6) { val q = neighbour(h, d); if (q >= 0 && seen.add(q)) next += q }
        return next
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
        // is entered at the valley; and on the largest stretch of its land that hangs together on
        // foot, so a scrap of land cut off by a river or a ring of cliffs is not taken for the hex.
        val part = IntArray(grid.size) { -1 }
        val partSize = ArrayList<Int>()
        val largest = IntArray(size) { -1 }
        val queue = IntArray(grid.size)
        for (s in 0 until grid.size) {
            if (part[s] >= 0 || !walk[s].isFinite()) continue
            val id = partSize.size
            var head = 0; var tail = 0
            queue[tail++] = s; part[s] = id
            while (head < tail) {
                val i = queue[head++]
                val xi = grid.x(i); val yi = grid.y(i)
                for (k in 0 until 8) {
                    val nx = xi + DX8[k]; val ny = yi + DY8[k]
                    if (!grid.inBounds(nx, ny)) continue
                    val j = grid.index(nx, ny)
                    if (part[j] >= 0 || owner[j] != owner[s] || !walk[j].isFinite()) continue
                    if (k >= 4 && (!walk[grid.index(xi + DX8[k], yi)].isFinite() || !walk[grid.index(xi, yi + DY8[k])].isFinite())) continue
                    part[j] = id; queue[tail++] = j
                }
            }
            partSize += tail
            val h = owner[s]
            if (largest[h] < 0 || tail > partSize[largest[h]]) largest[h] = id
        }
        val best = FloatArray(size) { Float.POSITIVE_INFINITY }
        for (i in 0 until grid.size) {
            if (!walk[i].isFinite()) continue
            val h = owner[i]
            if (part[i] != largest[h]) continue
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
                // No slipping diagonally between two cells of a river or cliff line.
                if (k >= 4 && (!walk[grid.index(xi + DX8[k], yi)].isFinite() || !walk[grid.index(xi, yi + DY8[k])].isFinite())) continue
                val nd = dd + (if (k < 4) 1f else 1.4142f) * ck * 0.5f * (walk[i] + walk[j])
                if (nd < (dist[j] ?: Float.POSITIVE_INFINITY)) { dist[j] = nd; via[j] = i; q += nd to j }
            }
        }
        if (!found) return
        // Along the way: its highest point.
        var top = world.elevation[to]
        var i = to
        while (i != from) { top = max(top, world.elevation[i]); i = via.getValue(i) }
        val e = dist.getValue(to)
        val back = (d + 3) % 6
        ground[tr.ordinal][a * 6 + d] = e; ground[tr.ordinal][b * 6 + back] = e
        if (tr == Travel.FOOT) { passM[a * 6 + d] = top; passM[b * 6 + back] = top }
    }

    /**
     * Puts the rivers on the borders they run between: each reach of a river deep enough to matter
     * (over the knee where it is easiest) that passes between the centres of two neighbouring hexes
     * puts the river on their border, at the best crossing depth of the reaches doing so.
     */
    private fun riversOnEdges() {
        val recv = world.water.drainage.receiver
        val ck = grid.cellKm
        for (i in 0 until grid.size) {
            val r = recv[i]
            if (r == i || world.isSea[i] || world.riverClass(i) < RiverClass.STREAM) continue
            val depth = terrain.crossingDepthM(i)
            if (depth <= KNEE) continue
            val ax = (grid.x(i) + 0.5) * ck; val ay = (grid.y(i) + 0.5) * ck
            val bx = (grid.x(r) + 0.5) * ck; val by = (grid.y(r) + 0.5) * ck
            // The hexes near the reach, and their borders.
            val near = HashSet<Int>()
            for (h in intArrayOf(owner[i], owner[r])) { near += h; for (d in 0 until 6) neighbour(h, d).let { if (it >= 0) near += it } }
            for (h in near) for (d in 0 until 3) {
                val n = neighbour(h, d)
                if (n < 0 || n !in near) continue
                val (hx, hy) = centreKm(h); val (nx, ny) = centreKm(n)
                if (!segmentsCross(ax, ay, bx, by, hx, hy, nx, ny)) continue
                val k = h * 6 + d; val back = n * 6 + (d + 3) % 6
                val best = if (riverDepthM[k] == 0f) depth else min(riverDepthM[k], depth)
                riverDepthM[k] = best; riverDepthM[back] = best
                riverM3s[k] = max(riverM3s[k], world.water.discharge[i]); riverM3s[back] = riverM3s[k]
            }
        }
    }

    private fun segmentsCross(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, dx: Double, dy: Double): Boolean {
        fun side(px: Double, py: Double, qx: Double, qy: Double, rx: Double, ry: Double) = (qx - px) * (ry - py) - (qy - py) * (rx - px)
        val d1 = side(cx, cy, dx, dy, ax, ay); val d2 = side(cx, cy, dx, dy, bx, by)
        val d3 = side(ax, ay, bx, by, cx, cy); val d4 = side(ax, ay, bx, by, dx, dy)
        return (d1 > 0) != (d2 > 0) && (d3 > 0) != (d4 > 0)
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
    enum class Travel(val ease: Float, val steep: Float, private val wade: Float, private val deepest: Float, val cliff: Float) {
        /** On foot and lightly loaded: most animals, hunting and raiding parties. Waist deep is hard, over the chest closed; a cliff is a desperate scramble. */
        FOOT(3.5f, 0.35f, 1f, 1.5f, 50f),
        /** Sure-footed on crags: ibex, mouflon, leopards. Water as for those on foot; cliffs only slow them. */
        CLIMB(2f, 0.8f, 1f, 1.5f, 3f),
        /** A band moving camp with its stores, children and old: slopes cost far more, water over the knee is hard, over the waist closed, and cliffs closed. */
        LADEN(5f, 0.25f, 0.5f, 1f, Float.POSITIVE_INFINITY),
        /** Swimmers and waders (deer, horses, hippos): a deep river slows them, only a great one stops them; cliffs as on foot. */
        SWIM(3.5f, 0.35f, 2.5f, 4f, 50f);

        /** Effort factor for getting across water [depth] m deep at its easiest point; infinite where it cannot be crossed. */
        fun water(depth: Float): Float = when {
            depth < KNEE -> 1f
            depth < wade -> 1f + 4f * (depth - KNEE) / (wade - KNEE)
            depth < deepest -> 5f + 25f * (depth - wade) / (deepest - wade)
            else -> Float.POSITIVE_INFINITY
        }
    }

    companion object {
        /** How fast ground closes above a way of travelling's steepest. */
        const val BARRIER = 25f
        /** Water up to the knee, m, is crossed without trouble. */
        const val KNEE = 0.5f
        /** How much ground the water's effort factor applies to when a river is crossed, km. */
        const val RIVER_KM = 1f
        // Neighbour offsets (col, row) for even and odd rows: E, SE, SW, W, NW, NE.
        private val EVEN = arrayOf(1 to 0, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1)
        private val ODD = arrayOf(1 to 0, 1 to 1, 0 to 1, -1 to 0, 0 to -1, 1 to -1)
        private val DX8 = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1)
        private val DY8 = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)
    }
}
