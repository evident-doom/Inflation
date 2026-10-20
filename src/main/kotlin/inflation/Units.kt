@file:Suppress("NOTHING_TO_INLINE")

package inflation

/*
 * Quantities with their units, so that kg per m² cannot be passed where kg per km² is meant (they differ by a million).
 * Each is a value class over a Float: as a parameter, local or field it compiles to a plain float and costs nothing.
 * The arithmetic below keeps a unit within itself; moving between units is spelt out ([KgM2.perKm2], [KgKm2.perM2],
 * or `.v` and a constructor where an expression's float rounding must stay exactly as it was).
 */

/** Mass, kg. */
@JvmInline value class Kg(val v: Float) {
    inline operator fun plus(o: Kg) = Kg(v + o.v)
    inline operator fun minus(o: Kg) = Kg(v - o.v)
    inline operator fun times(f: Float) = Kg(v * f)
    inline operator fun div(f: Float) = Kg(v / f)
    inline operator fun div(o: Kg) = v / o.v
    inline operator fun compareTo(o: Kg) = v.compareTo(o.v)
    /** Spread over [m2] square metres. */
    inline fun perM2(m2: Float) = KgM2(v / m2)
    /** Spread over [km2] square kilometres. */
    inline fun perKm2(km2: Float) = KgKm2(v / km2)
    override fun toString() = "$v kg"
    companion object { val ZERO = Kg(0f) }
}

/** Mass per area, kg/m²: the plants, their food (fruit, roots, seed), carrion, bones, litter. */
@JvmInline value class KgM2(val v: Float) {
    inline operator fun plus(o: KgM2) = KgM2(v + o.v)
    inline operator fun minus(o: KgM2) = KgM2(v - o.v)
    inline operator fun times(f: Float) = KgM2(v * f)
    inline operator fun div(f: Float) = KgM2(v / f)
    inline operator fun div(o: KgM2) = v / o.v
    inline operator fun compareTo(o: KgM2) = v.compareTo(o.v)
    /** The same in kg/km². */
    inline val perKm2 get() = KgKm2(v * 1e6f)
    /** Over [m2] square metres. */
    inline fun over(m2: Float) = Kg(v * m2)
    override fun toString() = "$v kg/m²"
    companion object { val ZERO = KgM2(0f) }
}

/** Mass per area, kg/km²: the animals, large and small. */
@JvmInline value class KgKm2(val v: Float) {
    inline operator fun plus(o: KgKm2) = KgKm2(v + o.v)
    inline operator fun minus(o: KgKm2) = KgKm2(v - o.v)
    inline operator fun times(f: Float) = KgKm2(v * f)
    inline operator fun div(f: Float) = KgKm2(v / f)
    inline operator fun div(o: KgKm2) = v / o.v
    inline operator fun compareTo(o: KgKm2) = v.compareTo(o.v)
    /** The same in kg/m². */
    inline val perM2 get() = KgM2(v / 1e6f)
    /** Over [km2] square kilometres. */
    inline fun over(km2: Float) = Kg(v * km2)
    override fun toString() = "$v kg/km²"
    companion object { val ZERO = KgKm2(0f) }
}

inline fun min(a: Kg, b: Kg) = Kg(kotlin.math.min(a.v, b.v))
inline fun max(a: Kg, b: Kg) = Kg(kotlin.math.max(a.v, b.v))
inline fun min(a: KgM2, b: KgM2) = KgM2(kotlin.math.min(a.v, b.v))
inline fun max(a: KgM2, b: KgM2) = KgM2(kotlin.math.max(a.v, b.v))
inline fun min(a: KgKm2, b: KgKm2) = KgKm2(kotlin.math.min(a.v, b.v))
inline fun max(a: KgKm2, b: KgKm2) = KgKm2(kotlin.math.max(a.v, b.v))
inline operator fun Float.times(m: Kg) = Kg(this * m.v)
inline operator fun Float.times(m: KgM2) = KgM2(this * m.v)
inline operator fun Float.times(m: KgKm2) = KgKm2(this * m.v)

