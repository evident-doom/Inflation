package inflation.ecology

import kotlin.enums.enumEntries

/**
 * A float for each entry of enum [E], read and written by the entry, so that an array kept per [Skill] cannot be
 * read with a [Tool]. A value class over the plain array: as a field or parameter it compiles to the array itself,
 * so it costs nothing and a saved field keeps its layout. [a] is the array, for whole-array work.
 */
@JvmInline value class EnumFloats<E : Enum<E>>(val a: FloatArray) {
    @Suppress("NOTHING_TO_INLINE") inline operator fun get(e: E) = a[e.ordinal]
    @Suppress("NOTHING_TO_INLINE") inline operator fun set(e: E, v: Float) { a[e.ordinal] = v }
    fun sum() = a.sum()
    fun copyOf() = EnumFloats<E>(a.copyOf())
    fun fill(v: Float) = a.fill(v)
    fun copyInto(o: EnumFloats<E>) { a.copyInto(o.a) }
}

/** A yes or no for each entry of enum [E]; see [EnumFloats]. */
@JvmInline value class EnumBools<E : Enum<E>>(val a: BooleanArray) {
    @Suppress("NOTHING_TO_INLINE") inline operator fun get(e: E) = a[e.ordinal]
    @Suppress("NOTHING_TO_INLINE") inline operator fun set(e: E, v: Boolean) { a[e.ordinal] = v }
    fun copyOf() = EnumBools<E>(a.copyOf())
    fun copyInto(o: EnumBools<E>) { a.copyInto(o.a) }
    fun fill(v: Boolean) = a.fill(v)
}

/** An int for each entry of enum [E]; see [EnumFloats]. */
@JvmInline value class EnumInts<E : Enum<E>>(val a: IntArray) {
    @Suppress("NOTHING_TO_INLINE") inline operator fun get(e: E) = a[e.ordinal]
    @Suppress("NOTHING_TO_INLINE") inline operator fun set(e: E, v: Int) { a[e.ordinal] = v }
    fun copyOf() = EnumInts<E>(a.copyOf())
    fun copyInto(o: EnumInts<E>) { a.copyInto(o.a) }
}

/** A float for each entry of [E], from [init]. */
inline fun <reified E : Enum<E>> enumFloats(init: (E) -> Float = { 0f }): EnumFloats<E> {
    val es = enumEntries<E>(); return EnumFloats(FloatArray(es.size) { init(es[it]) })
}
/** A yes or no for each entry of [E], from [init]. */
inline fun <reified E : Enum<E>> enumBools(init: (E) -> Boolean = { false }): EnumBools<E> {
    val es = enumEntries<E>(); return EnumBools(BooleanArray(es.size) { init(es[it]) })
}
/** An int for each entry of [E], from [init]. */
inline fun <reified E : Enum<E>> enumInts(init: (E) -> Int = { 0 }): EnumInts<E> {
    val es = enumEntries<E>(); return EnumInts(IntArray(es.size) { init(es[it]) })
}
