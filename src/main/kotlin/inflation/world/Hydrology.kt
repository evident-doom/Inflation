package inflation.world

import kotlin.random.Random

/** Min-heap of (float key, int value) pairs for priority-flood. */
private class FloatHeap(capacity: Int) {
    private var keys = FloatArray(capacity)
    private var vals = IntArray(capacity)
    var size = 0; private set

    fun push(k: Float, v: Int) {
        if (size == keys.size) { keys = keys.copyOf(size * 2); vals = vals.copyOf(size * 2) }
        var i = size++
        while (i > 0) {
            val p = (i - 1) / 2
            if (keys[p] <= k) break
            keys[i] = keys[p]; vals[i] = vals[p]; i = p
        }
        keys[i] = k; vals[i] = v
    }

    fun peekKey(): Float = keys[0]

    fun pop(): Int {
        val top = vals[0]
        val k = keys[--size]; val v = vals[size]
        var i = 0
        while (true) {
            var c = 2 * i + 1
            if (c >= size) break
            if (c + 1 < size && keys[c + 1] < keys[c]) c++
            if (keys[c] >= k) break
            keys[i] = keys[c]; vals[i] = vals[c]; i = c
        }
        keys[i] = k; vals[i] = v
        return top
    }
}

/**
 * Drainage network on a raster: steepest-descent (D8) receivers, a topological order
 * from outlets upstream, and flow accumulation.
 */
class Drainage(
    val receiver: IntArray,
    val receiverDistM: FloatArray,
    /** Cells ordered so that every cell appears after its receiver. */
    val stack: IntArray,
) {
    /** Sums [weights] downstream; result[i] = weight of i plus everything draining through it. */
    fun accumulate(weights: FloatArray): FloatArray {
        val acc = weights.copyOf()
        for (k in stack.size - 1 downTo 0) {
            val i = stack[k]; val r = receiver[i]
            if (r != i) acc[r] += acc[i]
        }
        return acc
    }

    companion object {
        /**
         * Builds receivers on [surface]. Cells where [isOutlet] is true drain off the map or into the sea.
         * The surface must have no closed depressions (see [priorityFlood]).
         */
        fun build(grid: Grid, surface: FloatArray, isOutlet: (Int) -> Boolean, rnd: Random? = null): Drainage {
            val n = grid.size
            val receiver = IntArray(n)
            val dist = FloatArray(n)
            for (i in 0 until n) {
                receiver[i] = i
                if (isOutlet(i)) continue
                var best = i; var bestScore = 0.0; var bestD = 0.0
                grid.forNeighbours(i) { j, d ->
                    val s = (surface[i] - surface[j]) / d
                    // Steepest descent, or with [rnd] a downhill neighbour chosen with odds rising
                    // steeply with slope, which breaks up the parallel channels plain D8 carves.
                    val score = if (s <= 0) 0.0 else if (rnd == null) s else s * s * rnd.nextDouble()
                    if (score > bestScore) { bestScore = score; best = j; bestD = d }
                }
                receiver[i] = best; dist[i] = bestD.toFloat()
            }
            // Donor lists via counting sort, then a breadth-first walk upstream from every root.
            val count = IntArray(n + 1)
            for (i in 0 until n) if (receiver[i] != i) count[receiver[i] + 1]++
            for (i in 0 until n) count[i + 1] += count[i]
            val donors = IntArray(n)
            val fill = count.copyOf()
            for (i in 0 until n) if (receiver[i] != i) donors[fill[receiver[i]]++] = i
            val stack = IntArray(n)
            var head = 0; var tail = 0
            for (i in 0 until n) if (receiver[i] == i) stack[tail++] = i
            while (head < tail) {
                val i = stack[head++]
                for (k in count[i] until count[i + 1]) stack[tail++] = donors[k]
            }
            check(tail == n) { "drainage graph has a cycle" }
            return Drainage(receiver, dist, stack)
        }
    }
}

/**
 * Priority-flood depression filling (Barnes et al. 2014). Returns the elevation water would
 * stand at if every closed basin filled to its spill point. With [routable] the filled surface
 * rises by the smallest float step from cell to cell, so every cell has a lower neighbour and
 * flow can be routed across lakes and flats.
 */
fun priorityFlood(grid: Grid, elevation: FloatArray, isOutlet: (Int) -> Boolean, routable: Boolean = false): FloatArray {
    val n = grid.size
    val filled = elevation.copyOf()
    val closed = BooleanArray(n)
    val heap = FloatHeap(grid.width * 4)
    for (i in 0 until n) if (isOutlet(i)) { closed[i] = true; heap.push(filled[i], i) }
    while (heap.size > 0) {
        val i = heap.pop()
        val hi = filled[i]
        grid.forNeighbours(i) { j, _ ->
            if (!closed[j]) {
                closed[j] = true
                if (routable) {
                    if (filled[j] <= hi) filled[j] = Math.nextUp(hi)
                } else if (filled[j] < hi) {
                    filled[j] = hi
                }
                heap.push(filled[j], j)
            }
        }
    }
    return filled
}
