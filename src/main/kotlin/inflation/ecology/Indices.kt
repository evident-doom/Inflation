package inflation.ecology

/**
 * A place: an index into the arrays kept per place (a hex at one height band, see [inflation.world.HexWorld.placeHex]).
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

@Suppress("NOTHING_TO_INLINE") inline operator fun FloatArray.get(p: Place) = this[p.i]
@Suppress("NOTHING_TO_INLINE") inline operator fun FloatArray.set(p: Place, v: Float) { this[p.i] = v }
@Suppress("NOTHING_TO_INLINE") inline operator fun IntArray.get(p: Place) = this[p.i]
@Suppress("NOTHING_TO_INLINE") inline operator fun IntArray.set(p: Place, v: Int) { this[p.i] = v }
@Suppress("NOTHING_TO_INLINE") inline operator fun BooleanArray.get(p: Place) = this[p.i]
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
/** The place in hex [h] at height band [band] ([Place.NONE] if the hex has no land there). */
@Suppress("NOTHING_TO_INLINE") inline fun inflation.world.HexWorld.placeIn(band: Int, h: Hex) = Place(placeOf[band][h.i])
/** Area of place [p], m². */
@Suppress("NOTHING_TO_INLINE") inline fun inflation.world.HexLand.m2(p: Place) = m2(p.i)
/** Shrub and tree cover of place [p], 0..1. */
@Suppress("NOTHING_TO_INLINE") inline fun inflation.world.HexPlants.cover(p: Place) = cover(p.i)
/** Tree canopy of place [p], 0..1. */
@Suppress("NOTHING_TO_INLINE") inline fun inflation.world.HexPlants.canopy(p: Place) = canopy(p.i)
/** Whether water at place [p] must be dug for, fetching up to [fetchKm]. */
@Suppress("NOTHING_TO_INLINE") inline fun inflation.world.HexDrinkingWater.mustDig(p: Place, fetchKm: Float) = mustDig(p.i, fetchKm)
/** The place in hex [h] at the height band of place [p] ([Place.NONE] if the hex has no land there). */
@Suppress("NOTHING_TO_INLINE") inline fun inflation.world.HexWorld.sameBandPlace(p: Place, h: Hex) = Place(sameBandPlace(p.i, h.i))
/** As above, for a hex index kept as a plain int. */
@Suppress("NOTHING_TO_INLINE") inline fun inflation.world.HexWorld.sameBandPlace(p: Place, h: Int) = Place(sameBandPlace(p.i, h))
