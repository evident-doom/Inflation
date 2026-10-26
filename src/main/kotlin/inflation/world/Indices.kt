package inflation.world

/**
 * A place: an index into the arrays kept per place (a hex, or its low or high ground, see [HexWorld.placeHex]).
 * A type of its own so that a place and a [Hex] cannot be swapped; where it is a parameter, local or field it compiles
 * to a plain int, so it costs nothing and a saved field keeps its layout. [NONE] (-1) is "no place".
 */
@JvmInline value class Place(val i: Int) {
    val none get() = i < 0
    override fun toString() = "place $i"
    companion object { val NONE = Place(-1) }
}

/**
 * A hex: an index into the arrays kept per hex ([HexMap]). See [Place]; [NONE] (-1) is "no hex". Hexes are never
 * held as bare ints: a list of them is a [HexArray] (or [HexList] while it grows), and a hex's border in direction
 * d is [HexMap.edge].
 */
@JvmInline value class Hex(val i: Int) : Comparable<Hex> {
    val none get() = i < 0
    override fun toString() = "hex $i"
    /** Hexes in map order, row by row (for ties broken the same way every run). */
    override fun compareTo(other: Hex) = i.compareTo(other.i)
    companion object { val NONE = Hex(-1) }
}

/** All [n] places in order, for `for (p in places)`: [PlaceIter.next] is not generic, so no [Place] is boxed. */
class PlaceRange(private val n: Int) { operator fun iterator() = PlaceIter(n) }
class PlaceIter(private val n: Int) {
    private var i = 0
    operator fun hasNext() = i < n
    operator fun next() = Place(i++)
}
/** A float per place, from [init]: `perPlace(n) { p -> ... }`. */
inline fun perPlace(n: Int, init: (Place) -> Float) = FloatArray(n) { init(Place(it)) }
/** [body] for each place index of the stream, as a [Place]. */
inline fun java.util.stream.IntStream.forEachPlace(crossinline body: (Place) -> Unit) = forEach { body(Place(it)) }

/** All [n] hexes in order, for `for (h in hex.all)`; see [PlaceRange]. */
class HexRange(private val n: Int) {
    operator fun iterator() = HexIter(n)
    inline fun filter(p: (Hex) -> Boolean): List<Hex> { val out = ArrayList<Hex>(); for (h in this) if (p(h)) out += h; return out }
    inline fun count(p: (Hex) -> Boolean): Int { var k = 0; for (h in this) if (p(h)) k++; return k }
}
class HexIter(private val n: Int) {
    private var i = 0
    operator fun hasNext() = i < n
    operator fun next() = Hex(i++)
}
/** [body] for each hex index of the stream, as a [Hex]. */
inline fun java.util.stream.IntStream.forEachHex(crossinline body: (Hex) -> Unit) = forEach { body(Hex(it)) }

/**
 * Hexes in a row, over a plain int array ([a]): a group's path, a band's remembered hexes, the hex of each place. As a
 * field it compiles to the array itself, so a saved field keeps its layout.
 */
@JvmInline value class HexArray(val a: IntArray) {
    constructor(size: Int) : this(IntArray(size) { -1 })
    @Suppress("NOTHING_TO_INLINE") inline operator fun get(k: Int) = Hex(a[k])
    @Suppress("NOTHING_TO_INLINE") inline operator fun set(k: Int, h: Hex) { a[k] = h.i }
    /** The hex of place [p] (where the array is kept per place). */
    @Suppress("NOTHING_TO_INLINE") inline operator fun get(p: Place) = Hex(a[p.i])
    /** The hex kept for hex [h] (where the array is kept per hex, as where each hex's river goes). */
    @Suppress("NOTHING_TO_INLINE") inline operator fun get(h: Hex) = Hex(a[h.i])
    @Suppress("NOTHING_TO_INLINE") inline operator fun set(h: Hex, v: Hex) { a[h.i] = v.i }
    val size get() = a.size
    val indices get() = a.indices
    val lastIndex get() = a.lastIndex
    fun isEmpty() = a.isEmpty()
    fun isNotEmpty() = a.isNotEmpty()
    fun last() = Hex(a.last())
    fun firstOrNull() = if (a.isEmpty()) null else Hex(a[0])
    fun lastOrNull() = if (a.isEmpty()) null else Hex(a.last())
    fun joinToString(separator: String) = a.joinToString(separator)
    operator fun contains(h: Hex) = h.i in a
    fun indexOf(h: Hex) = a.indexOf(h.i)
    /** Where [h] is, or would go, in these hexes sorted in map order ([java.util.Arrays.binarySearch]). */
    fun binarySearch(h: Hex) = java.util.Arrays.binarySearch(a, h.i)
    /** Sorts these hexes into map order, in place. */
    fun sort() = a.sort()
    fun fill(h: Hex) = a.fill(h.i)
    fun copyOf() = HexArray(a.copyOf())
    fun copyOf(n: Int) = HexArray(a.copyOf(n))
    fun copyOfRange(from: Int, to: Int) = HexArray(a.copyOfRange(from, to))
    fun copyInto(o: HexArray, at: Int = 0) { a.copyInto(o.a, at) }
    fun contentEquals(o: HexArray) = a.contentEquals(o.a)
    operator fun iterator() = HexArrayIter(a)
    fun toList(): List<Hex> = a.map { Hex(it) }
    inline fun all(p: (Hex) -> Boolean) = a.all { p(Hex(it)) }
    inline fun any(p: (Hex) -> Boolean) = a.any { p(Hex(it)) }
    inline fun count(p: (Hex) -> Boolean) = a.count { p(Hex(it)) }
    inline fun filter(p: (Hex) -> Boolean): List<Hex> = a.filter { p(Hex(it)) }.map { Hex(it) }
    inline fun <R> map(f: (Hex) -> R): List<R> = a.map { f(Hex(it)) }
    inline fun maxOfOrNull(f: (Hex) -> Float): Float? = if (a.isEmpty()) null else a.maxOf { f(Hex(it)) }
    companion object { val EMPTY = HexArray(IntArray(0)) }
}
class HexArrayIter(private val a: IntArray) {
    private var j = 0
    operator fun hasNext() = j < a.size
    operator fun next() = Hex(a[j++])
}
/** [n] hexes from [init]. */
inline fun hexArray(n: Int, init: (Int) -> Hex) = HexArray(IntArray(n) { init(it).i })
/** These hexes as a [HexArray]. */
fun Collection<Hex>.toHexArray() = HexArray(IntArray(size).also { a -> for ((k, h) in withIndex()) a[k] = h.i })

/** A growing list of hexes, without boxing them (see [HexArray]). */
class HexList(capacity: Int = 16) {
    private var a = IntArray(capacity)
    var size = 0
        private set
    operator fun get(k: Int) = Hex(a[k])
    fun add(h: Hex) { if (size == a.size) a = a.copyOf(maxOf(4, 2 * size)); a[size++] = h.i }
    operator fun plusAssign(h: Hex) = add(h)
    fun clear() { size = 0 }
    fun isEmpty() = size == 0
    fun toHexArray() = HexArray(a.copyOf(size))
    operator fun iterator() = HexArrayIter(a.copyOf(size))
}

/** The places of one hex, for `for (p in hw.placeList(h))`. */
@JvmInline value class PlaceList(private val a: IntArray) {
    operator fun iterator() = PlaceListIter(a)
    val size get() = a.size
}
class PlaceListIter(private val a: IntArray) {
    private var j = 0
    operator fun hasNext() = j < a.size
    operator fun next() = Place(a[j++])
}
@Suppress("NOTHING_TO_INLINE") inline fun HexWorld.placeList(h: Hex) = PlaceList(placesOf(h))
@Suppress("NOTHING_TO_INLINE") inline operator fun DoubleArray.get(p: Place) = this[p.i]
@Suppress("NOTHING_TO_INLINE") inline operator fun DoubleArray.set(p: Place, v: Double) { this[p.i] = v }

@Suppress("NOTHING_TO_INLINE") inline operator fun FloatArray.get(p: Place) = this[p.i]
@Suppress("NOTHING_TO_INLINE") inline operator fun FloatArray.set(p: Place, v: Float) { this[p.i] = v }
@Suppress("NOTHING_TO_INLINE") inline operator fun IntArray.get(p: Place) = this[p.i]
@Suppress("NOTHING_TO_INLINE") inline operator fun IntArray.set(p: Place, v: Int) { this[p.i] = v }
@Suppress("NOTHING_TO_INLINE") inline operator fun BooleanArray.get(p: Place) = this[p.i]
@Suppress("NOTHING_TO_INLINE") inline operator fun BooleanArray.set(p: Place, v: Boolean) { this[p.i] = v }
@Suppress("NOTHING_TO_INLINE") inline operator fun ShortArray.get(p: Place) = this[p.i]
@Suppress("NOTHING_TO_INLINE") inline operator fun DoubleArray.get(h: Hex) = this[h.i]
@Suppress("NOTHING_TO_INLINE") inline operator fun DoubleArray.set(h: Hex, v: Double) { this[h.i] = v }
@Suppress("NOTHING_TO_INLINE") inline operator fun FloatArray.get(h: Hex) = this[h.i]
@Suppress("NOTHING_TO_INLINE") inline operator fun FloatArray.set(h: Hex, v: Float) { this[h.i] = v }
@Suppress("NOTHING_TO_INLINE") inline operator fun IntArray.get(h: Hex) = this[h.i]
@Suppress("NOTHING_TO_INLINE") inline operator fun IntArray.set(h: Hex, v: Int) { this[h.i] = v }
@Suppress("NOTHING_TO_INLINE") inline operator fun BooleanArray.get(h: Hex) = this[h.i]
@Suppress("NOTHING_TO_INLINE") inline operator fun BooleanArray.set(h: Hex, v: Boolean) { this[h.i] = v }
@Suppress("NOTHING_TO_INLINE") inline operator fun ShortArray.get(h: Hex) = this[h.i]
@Suppress("NOTHING_TO_INLINE") inline operator fun ShortArray.set(h: Hex, v: Short) { this[h.i] = v }
@Suppress("NOTHING_TO_INLINE") inline operator fun ByteArray.get(h: Hex) = this[h.i]
@Suppress("NOTHING_TO_INLINE") inline operator fun ByteArray.set(h: Hex, v: Byte) { this[h.i] = v }
@Suppress("NOTHING_TO_INLINE") inline operator fun <T> Array<T>.get(h: Hex) = this[h.i]
@Suppress("NOTHING_TO_INLINE") inline operator fun <T> Array<T>.set(h: Hex, v: T) { this[h.i] = v }
@Suppress("NOTHING_TO_INLINE") inline operator fun <T> List<T>.get(h: Hex) = this[h.i]
