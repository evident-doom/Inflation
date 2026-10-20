package inflation.ecology

/**
 * A place: an index into the arrays kept per place (a hex, or its low or high ground, see [inflation.world.HexWorld.placeHex]).
 * A type of its own so that a place and a [Hex] cannot be swapped; where it is a parameter, local or field it compiles
 * to a plain int, so it costs nothing and a saved field keeps its layout. [NONE] (-1) is "no place".
 */
@JvmInline value class Place(val i: Int) {
    val none get() = i < 0
    override fun toString() = "place $i"
    companion object { val NONE = Place(-1) }
}

/** A hex: an index into the arrays kept per hex ([inflation.world.HexMap]). See [Place]; [NONE] (-1) is "no hex". */
@JvmInline value class Hex(val i: Int) {
    val none get() = i < 0
    override fun toString() = "hex $i"
    companion object { val NONE = Hex(-1) }
}

/** All [n] places in order, for `for (p in places)`: [PlaceIter.next] is not generic, so no [Place] is boxed. */
class PlaceRange(private val n: Int) { operator fun iterator() = PlaceIter(n) }
class PlaceIter(private val n: Int) {
    private var i = 0
    operator fun hasNext() = i < n
    operator fun next() = Place(i++)
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
@Suppress("NOTHING_TO_INLINE") inline fun inflation.world.HexWorld.placeList(h: Int) = PlaceList(placesOf(h))
@Suppress("NOTHING_TO_INLINE") inline operator fun DoubleArray.get(p: Place) = this[p.i]
@Suppress("NOTHING_TO_INLINE") inline operator fun DoubleArray.set(p: Place, v: Double) { this[p.i] = v }

@Suppress("NOTHING_TO_INLINE") inline operator fun FloatArray.get(p: Place) = this[p.i]
@Suppress("NOTHING_TO_INLINE") inline operator fun FloatArray.set(p: Place, v: Float) { this[p.i] = v }
@Suppress("NOTHING_TO_INLINE") inline operator fun IntArray.get(p: Place) = this[p.i]
@Suppress("NOTHING_TO_INLINE") inline operator fun IntArray.set(p: Place, v: Int) { this[p.i] = v }
@Suppress("NOTHING_TO_INLINE") inline operator fun BooleanArray.get(p: Place) = this[p.i]
@Suppress("NOTHING_TO_INLINE") inline operator fun BooleanArray.set(p: Place, v: Boolean) { this[p.i] = v }
@Suppress("NOTHING_TO_INLINE") inline operator fun ShortArray.get(p: Place) = this[p.i]
@Suppress("NOTHING_TO_INLINE") inline operator fun FloatArray.get(h: Hex) = this[h.i]
@Suppress("NOTHING_TO_INLINE") inline operator fun FloatArray.set(h: Hex, v: Float) { this[h.i] = v }
@Suppress("NOTHING_TO_INLINE") inline operator fun IntArray.get(h: Hex) = this[h.i]
@Suppress("NOTHING_TO_INLINE") inline operator fun IntArray.set(h: Hex, v: Int) { this[h.i] = v }
@Suppress("NOTHING_TO_INLINE") inline operator fun BooleanArray.get(h: Hex) = this[h.i]
@Suppress("NOTHING_TO_INLINE") inline operator fun ShortArray.get(h: Hex) = this[h.i]
/** Whether hex [h] is among [hexes] (hex indices). */
@Suppress("NOTHING_TO_INLINE") inline operator fun IntArray.contains(h: Hex) = contains(h.i)

/** The hex place [p] lies in. */
@Suppress("NOTHING_TO_INLINE") inline fun inflation.world.HexWorld.hexOf(p: Place) = Hex(placeHex[p.i])
/** Hex [h]'s low or high ground, its only place where it is not split ([Place.NONE] if it has no land). */
@Suppress("NOTHING_TO_INLINE") inline fun inflation.world.HexWorld.placeIn(band: Int, h: Hex) = Place(placeIn(band, h.i))
/** The place in hex [h] nearest in height to place [p] ([Place.NONE] if the hex has no land). */
@Suppress("NOTHING_TO_INLINE") inline fun inflation.world.HexWorld.placeNear(p: Place, h: Hex) = Place(placeNear(p.i, h.i))
/** As above, for a hex index kept as a plain int. */
@Suppress("NOTHING_TO_INLINE") inline fun inflation.world.HexWorld.placeNear(p: Place, h: Int) = Place(placeNear(p.i, h))
