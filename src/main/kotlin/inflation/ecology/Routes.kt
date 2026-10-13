package inflation.ecology

import inflation.world.World
import java.util.PriorityQueue
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The ways through the country: a network of waypoints every [NODE_KM] km, each on the easiest
 * ground of its block (a valley floor, a saddle), linked to its eight neighbours by the easiest
 * route between them over the 1 km terrain. A ridge with no pass gives no link, a gorge or a
 * col gives a dear one, an open valley a cheap one. Animals and people that shift their range by
 * tens of km in a week go along these links, so mountains turn them up valleys and over passes.
 *
 * There are two ways of moving: most animals and people, for whom ground steeper than about 25°
 * over a km is nearly closed, and climbers (ibex, mouflon, leopards), who go where others cannot.
 */
class Routes(private val world: World) {
    private val grid = world.grid
    private val terrain = world.terrain
    private val cellKm = grid.cellKm.toFloat()
    private val per = max(1, (NODE_KM / cellKm).toInt())
    val nw = (grid.width + per - 1) / per
    val nh = (grid.height + per - 1) / per
    /** The cell each waypoint stands on, -1 where its block is all water. */
    private val nodeCell = IntArray(nw * nh) { -1 }
    /** Per way of moving: effort of each link, flat-ground km; [Float.POSITIVE_INFINITY] where there is none. */
    private val links = Array(2) { FloatArray(nw * nh * 8) { Float.POSITIVE_INFINITY } }
    /** Per way of moving: effort to walk one cell, as a multiple of flat open ground. */
    private val cellCost = Array(2) { FloatArray(grid.size) }

    init {
        for (c in 0..1) {
            val cost = cellCost[c]
            for (i in 0 until grid.size) {
                val base = terrain.baseCost[i]
                cost[i] = if (!base.isFinite() || world.isSea[i]) Float.POSITIVE_INFINITY
                else base / exp(3.5f * terrain.slope[i]) * slopeCost(terrain.slope[i], c == CLIMBER)
            }
        }
        for (n in 0 until nw * nh) {
            val bx = n % nw; val by = n / nw
            var best = -1; var bestCost = Float.POSITIVE_INFINITY
            for (y in by * per until min(grid.height, (by + 1) * per)) for (x in bx * per until min(grid.width, (bx + 1) * per)) {
                val i = grid.index(x, y)
                if (cellCost[0][i] < bestCost) { bestCost = cellCost[0][i]; best = i }
            }
            nodeCell[n] = best
        }
        // Links, each found once and shared both ways.
        java.util.stream.IntStream.range(0, nw * nh).parallel().forEach { a ->
            if (nodeCell[a] < 0) return@forEach
            val ax = a % nw; val ay = a / nw
            for (dir in 0 until 4) {
                val bx = ax + DX[dir]; val by = ay + DY[dir]
                if (bx < 0 || by < 0 || bx >= nw || by >= nh) continue
                val b = by * nw + bx
                if (nodeCell[b] < 0) continue
                for (c in 0..1) {
                    val e = easiest(nodeCell[a], nodeCell[b], c)
                    links[c][a * 8 + dir] = e
                    links[c][b * 8 + OPPOSITE[dir]] = e
                }
            }
        }
    }

    /** Walking effort over a km of ground of slope [s] (rise over run), as a multiple of flat ground. */
    private fun slopeCost(s: Float, climber: Boolean): Float {
        val limit = if (climber) CLIMBER_LIMIT else STEEP_LIMIT
        val ease = if (climber) 2f else 3.5f
        return exp(ease * s) * if (s > limit) exp(BARRIER * (s - limit)) else 1f
    }

    /** Least effort between cells [a] and [b] (in neighbouring blocks), keeping to the two blocks and their surroundings. */
    private fun easiest(a: Int, b: Int, c: Int): Float {
        val cost = cellCost[c]
        val x0 = max(0, min(grid.x(a), grid.x(b)) - per / 2); val x1 = min(grid.width - 1, max(grid.x(a), grid.x(b)) + per / 2)
        val y0 = max(0, min(grid.y(a), grid.y(b)) - per / 2); val y1 = min(grid.height - 1, max(grid.y(a), grid.y(b)) + per / 2)
        val w = x1 - x0 + 1
        val dist = FloatArray(w * (y1 - y0 + 1)) { Float.POSITIVE_INFINITY }
        val q = PriorityQueue<Long>()
        fun local(i: Int) = (grid.y(i) - y0) * w + grid.x(i) - x0
        dist[local(a)] = 0f; q += pack(0f, a)
        while (q.isNotEmpty()) {
            val top = q.poll(); val d = Float.fromBits((top ushr 32).toInt()); val i = top.toInt()
            if (i == b) return d
            if (d > dist[local(i)]) continue
            val xi = grid.x(i); val yi = grid.y(i)
            for (k in 0 until 8) {
                val nx = xi + DX8[k]; val ny = yi + DY8[k]
                if (nx < x0 || nx > x1 || ny < y0 || ny > y1) continue
                val j = grid.index(nx, ny)
                if (!cost[j].isFinite()) continue
                if (k >= 4 && (!cost[grid.index(xi + DX8[k], yi)].isFinite() || !cost[grid.index(xi, yi + DY8[k])].isFinite())) continue
                val nd = d + (if (k < 4) 1f else 1.4142f) * cellKm * 0.5f * (cost[i] + cost[j])
                val lj = local(j)
                if (nd < dist[lj]) { dist[lj] = nd; q += pack(nd, j) }
            }
        }
        return Float.POSITIVE_INFINITY
    }

    private fun pack(d: Float, i: Int) = (d.toRawBits().toLong() shl 32) or (i.toLong() and 0xffffffffL)

    /** The waypoint nearest the point (x, y) in cells. */
    fun nodeAt(x: Double, y: Double): Int =
        (y / per).toInt().coerceIn(0, nh - 1) * nw + (x / per).toInt().coerceIn(0, nw - 1)

    /** Which way of moving [s] has. */
    fun way(s: Species) = if (s in CLIMBERS) CLIMBER else WALKER

    /**
     * Efforts to every waypoint within [budget] km of effort from (x, y), by the network: a
     * search kept in [r] for looking up candidate places afterwards with [effortTo].
     */
    fun search(x: Double, y: Double, way: Int, budget: Float, r: Search) {
        val start = nodeAt(x, y)
        val sx = start % nw; val sy = start / nw
        val radius = min(Search.MAX_R, (budget / (per * cellKm)).toInt() + 1)
        r.reset(sx, sy, radius)
        r.startNode = start
        if (nodeCell[start] < 0) return
        val l = links[way]
        r.set(start % nw, start / nw, 0f); r.setPrev(start % nw, start / nw, -1); r.heap.clear(); r.heap += pack(0f, start)
        while (r.heap.isNotEmpty()) {
            val top = r.heap.poll(); val d = Float.fromBits((top ushr 32).toInt()); val n = top.toInt()
            if (d > r.get(n % nw, n / nw)) continue
            val nx0 = n % nw; val ny0 = n / nw
            for (k in 0 until 8) {
                val e = l[n * 8 + k]
                if (!e.isFinite()) continue
                val nd = d + e
                if (nd > budget) continue
                val mx = nx0 + DX[k]; val my = ny0 + DY[k]
                if (!r.inside(mx, my)) continue
                if (nd < r.get(mx, my)) { r.set(mx, my, nd); r.setPrev(mx, my, n); r.heap += pack(nd, my * nw + mx) }
            }
        }
    }

    /** Effort by the network from the last [search]'s start to the place (x, y), flat-ground km; infinite if out of reach. */
    fun effortTo(r: Search, x: Double, y: Double): Float {
        val n = nodeAt(x, y)
        val mx = n % nw; val my = n / nw
        return if (r.inside(mx, my)) r.get(mx, my) else Float.POSITIVE_INFINITY
    }

    /**
     * Of the waypoints the last [search] reached, the one nearest the place (tx, ty) as the crow
     * flies (cells), cheaper reached on a tie: where a group heading there can get this week by the
     * ways it can take. Null if none is nearer than where it starts.
     */
    fun toward(r: Search, tx: Double, ty: Double): Pair<Double, Double>? {
        var best = -1; var bestD = Double.MAX_VALUE; var bestE = Float.MAX_VALUE
        r.forEachReached { x, y, e ->
            val n = y * nw + x
            val c = nodeCell[n]
            if (c < 0) return@forEachReached
            val d = hypot(grid.x(c) + 0.5 - tx, grid.y(c) + 0.5 - ty)
            if (d < bestD - 1e-6 || (d < bestD + 1e-6 && e < bestE)) { best = c; bestD = d; bestE = e }
        }
        if (best < 0 || r.startNode < 0) return null
        val s = nodeCell[r.startNode]
        if (s >= 0 && hypot(grid.x(s) + 0.5 - tx, grid.y(s) + 0.5 - ty) <= bestD) return null
        return grid.x(best) + 0.5 to grid.y(best) + 0.5
    }

    /**
     * The way from the last [search]'s start to the place (x, y): the waypoints passed, as cell
     * coordinates x0, y0, x1, y1, ..., ending at (x, y). Null if it is out of reach.
     */
    fun wayTo(r: Search, x: Double, y: Double): FloatArray? {
        val end = nodeAt(x, y)
        if (!r.inside(end % nw, end / nw) || !r.get(end % nw, end / nw).isFinite()) return null
        val nodes = ArrayList<Int>()
        var n = end
        while (n >= 0 && nodes.size < 4 * Search.MAX_R) { nodes += n; n = r.getPrev(n % nw, n / nw) }
        nodes.reverse()
        val out = FloatArray(2 * nodes.size + 2)
        for ((k, m) in nodes.withIndex()) { val c = nodeCell[m]; out[2 * k] = grid.x(c) + 0.5f; out[2 * k + 1] = grid.y(c) + 0.5f }
        out[out.size - 2] = x.toFloat(); out[out.size - 1] = y.toFloat()
        return out
    }

    /** Working space for [search], one per thread. */
    class Search {
        val heap = PriorityQueue<Long>()
        private val dist = FloatArray((2 * MAX_R + 1) * (2 * MAX_R + 1))
        private val prev = IntArray((2 * MAX_R + 1) * (2 * MAX_R + 1))
        private var cx = 0; private var cy = 0; private var r = 0
        /** The waypoint the search started from, -1 before any. */
        var startNode = -1
        fun forEachReached(f: (x: Int, y: Int, effort: Float) -> Unit) {
            val side = 2 * r + 1
            for (k in 0 until side * side) { val d = dist[k]; if (d.isFinite()) f(cx - r + k % side, cy - r + k / side, d) }
        }
        fun reset(x: Int, y: Int, radius: Int) {
            cx = x; cy = y; r = radius
            val side = 2 * r + 1
            java.util.Arrays.fill(dist, 0, side * side, Float.POSITIVE_INFINITY)
        }
        fun inside(x: Int, y: Int) = x >= cx - r && x <= cx + r && y >= cy - r && y <= cy + r
        fun get(x: Int, y: Int) = dist[(y - cy + r) * (2 * r + 1) + (x - cx + r)]
        fun set(x: Int, y: Int, d: Float) { dist[(y - cy + r) * (2 * r + 1) + (x - cx + r)] = d }
        fun getPrev(x: Int, y: Int) = prev[(y - cy + r) * (2 * r + 1) + (x - cx + r)]
        fun setPrev(x: Int, y: Int, n: Int) { prev[(y - cy + r) * (2 * r + 1) + (x - cx + r)] = n }

        companion object {
            const val MAX_R = 60
        }
    }

    companion object {
        /** Spacing of the waypoints, km. */
        const val NODE_KM = 4f
        /** Slope (rise over run over a km) above which ground closes fast: about 19° for most, 39° for climbers. */
        const val STEEP_LIMIT = 0.35f
        const val CLIMBER_LIMIT = 0.8f
        /** How fast ground closes above the limit: each 0.1 of extra slope multiplies the effort by e^[BARRIER]/10. */
        const val BARRIER = 25f
        const val WALKER = 0
        const val CLIMBER = 1
        val CLIMBERS by lazy { setOf(Catalogue.IBEX, Catalogue.MOUFLON, Catalogue.LEOPARD) }
        // Neighbours: E, S, SE, SW first (each found once), then their opposites.
        private val DX = intArrayOf(1, 0, 1, -1, -1, 0, -1, 1)
        private val DY = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)
        private val OPPOSITE = intArrayOf(4, 5, 6, 7, 0, 1, 2, 3)
        private val DX8 = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1)
        private val DY8 = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)
    }
}
