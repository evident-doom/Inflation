package inflation.world

/**
 * The one least-effort search over a hex map's borders (Dijkstra's), for every search of the going: the groups' reach, the way
 * out of a peril, the residents' dispersal ground, the map's own efforts. Least effort first, of equals the lower hex, so it
 * is the same on every run and every core, whoever searches.
 *
 * A caller drives it: [start] from one hex or several, then take each hex in turn by [next] and [across] its borders (and
 * [offer] any other way out of it, a strait), then read [reached], [dist] and [via] and [clear] it for the next search. What
 * a caller adds of its own (where it may not go, where it stops) it says in [across]'s test and by not going on from a hex.
 * One searcher a thread ([HexMap.searcher]); its arrays are kept from search to search, left clean by [clear].
 */
class LeastEffort(size: Int) {
    private val d = FloatArray(size) { Float.MAX_VALUE }
    private val from = HexArray(size)
    /** The hexes reached so far (given an effort), in the order first reached. */
    val reached = HexList()
    private val heap = LongHeap()

    /** Starts the search at [h], at no effort. */
    fun start(h: Hex) { if (d[h] == 0f) return; if (d[h] == Float.MAX_VALUE) reached += h; d[h] = 0f; from[h] = Hex.NONE; heap += packed(0f, h) }

    /** The next hex whose least effort is settled, the least first, of equals the lower hex; [Hex.NONE] when none is left. */
    fun next(): Hex {
        while (heap.isNotEmpty()) {
            val e = heap.poll()
            val h = Hex((e and 0xffffffffL).toInt())
            if (Float.fromBits((e ushr 32).toInt()) <= d[h]) return h
        }
        return Hex.NONE
    }

    /** The least effort to [h] found so far; [Float.MAX_VALUE] where not reached. */
    fun dist(h: Hex) = d[h]
    /** The hex [h] was reached from on its least effort; [Hex.NONE] at a start or where not reached. */
    fun via(h: Hex) = from[h]

    /**
     * [to] reached from [h] at effort [nd]: kept if it is less than any found so far (and finite, and at most [most]); true
     * if it was kept.
     */
    @IgnorableReturnValue fun offer(h: Hex, to: Hex, nd: Float, most: Float = Float.MAX_VALUE): Boolean {
        if (!nd.isFinite() || nd > most || nd >= d[to]) return false
        if (d[to] == Float.MAX_VALUE) reached += to
        d[to] = nd; from[to] = h; heap += packed(nd, to)
        return true
    }

    /**
     * Over each border of [h] on [map], at the effort [cost] gives it ([HexMap.edge] order; +∞ closed), out to [most]: each
     * neighbour [offer]ed where [ok] (the neighbour, the effort it would be reached at) lets it be.
     */
    inline fun across(map: HexMap, h: Hex, cost: FloatArray, most: Float = Float.MAX_VALUE, ok: (Hex, Float) -> Boolean = { _, _ -> true }) {
        val at = dist(h)
        for (k in 0 until 6) {
            val nh = map.neighbour(h, k); if (nh.none) continue
            val nd = at + cost[map.edge(h, k)]
            if (!nd.isFinite() || nd > most || !ok(nh, nd)) continue
            offer(h, nh, nd, most)
        }
    }

    /** Leaves it clean for the next search. */
    fun clear() {
        for (i in 0 until reached.size) { val h = reached[i]; d[h] = Float.MAX_VALUE; from[h] = Hex.NONE }
        reached.clear(); heap.clear()
    }

    /** A heap entry: its effort's bits above its hex, so the heap pops the least effort first, of equals the lower hex (efforts are never negative). */
    private fun packed(d: Float, h: Hex): Long = (d.toRawBits().toLong() shl 32) or h.i.toLong()
}

/** A binary min-heap of longs (no boxing). */
class LongHeap {
    private var a = LongArray(256); private var n = 0
    fun clear() { n = 0 }
    fun isNotEmpty() = n > 0
    operator fun plusAssign(v: Long) {
        if (n == a.size) a = a.copyOf(2 * n)
        var i = n++
        while (i > 0) { val up = (i - 1) / 2; if (a[up] <= v) break; a[i] = a[up]; i = up }
        a[i] = v
    }
    fun poll(): Long {
        val top = a[0]; val last = a[--n]
        var i = 0
        while (true) {
            var c = 2 * i + 1; if (c >= n) break
            if (c + 1 < n && a[c + 1] < a[c]) c++
            if (a[c] >= last) break
            a[i] = a[c]; i = c
        }
        if (n > 0) a[i] = last
        return top
    }
}
