package inflation

import kotlin.math.min

// A binary min-heap over the first m entries of a LongArray, and the keys that rank floats by it.

/**
 * Keys for ranking [v]'s first [m] values largest first, equal values in index order (as a stable sort by
 * [java.lang.Float.compare] would put them), into [keys]: each value's bits made to sort as signed ints, negated and put
 * above its index, so that the smallest key is the first in rank and its low half the index.
 */
internal fun rankKeys(v: FloatArray, m: Int, keys: LongArray) {
    for (i in 0 until m) {
        val bits = java.lang.Float.floatToIntBits(v[i])
        val sortable = bits xor ((bits shr 31) and 0x7fffffff)
        keys[i] = (-sortable.toLong() shl 32) or i.toLong()
    }
}

/** Makes the first [m] of [a] a binary min-heap. */
internal fun heapify(a: LongArray, m: Int) { for (i in m / 2 - 1 downTo 0) siftDown(a, i, m) }

/** Takes the smallest of min-heap [a]'s first [m] off it (its last moves up into its place) and returns it. */
internal fun popMin(a: LongArray, m: Int): Long {
    val top = a[0]; a[0] = a[m - 1]; siftDown(a, 0, m - 1)
    return top
}

private fun siftDown(a: LongArray, from: Int, m: Int) {
    var i = from; val x = a[i]
    while (true) {
        var c = 2 * i + 1; if (c >= m) break
        if (c + 1 < m && a[c + 1] < a[c]) c++
        if (a[c] >= x) break
        a[i] = a[c]; i = c
    }
    a[i] = x
}
