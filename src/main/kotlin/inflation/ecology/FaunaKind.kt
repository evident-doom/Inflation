package inflation.ecology

/**
 * A kind of the resident fauna ([HexFauna.kinds]), by its position there: the index of every per-kind array of the
 * residents. A type of its own so that it cannot be swapped for a kind's catalogue number ([Species.index]) or its
 * position among the hunting groups' kinds, which are other numberings. Compiles to a plain int. [NONE] (-1) is "no kind".
 */
@JvmInline value class FaunaKind(val i: Int) {
    val none get() = i < 0
    override fun toString() = "kind $i"
    companion object { val NONE = FaunaKind(-1) }
}

/** All [n] kinds in order, for `for (k in kindIds)`; the iterator is not generic, so no [FaunaKind] is boxed. */
class KindRange(private val n: Int) {
    operator fun iterator() = KindIter(n)
    fun toList() = List(n) { FaunaKind(it) }
}
class KindIter(private val n: Int) {
    private var i = 0
    operator fun hasNext() = i < n
    operator fun next() = FaunaKind(i++)
}

/** A kind and its species: `for ((k, s) in kinds.withKinds())`. */
class KindAndSpecies(val k: FaunaKind, val s: Species) {
    operator fun component1() = k
    operator fun component2() = s
}
fun List<Species>.withKinds(): List<KindAndSpecies> = List(size) { KindAndSpecies(FaunaKind(it), this[it]) }

@Suppress("NOTHING_TO_INLINE") inline operator fun <T> Array<T>.get(k: FaunaKind) = this[k.i]
@Suppress("NOTHING_TO_INLINE") inline operator fun <T> Array<T>.set(k: FaunaKind, v: T) { this[k.i] = v }
@Suppress("NOTHING_TO_INLINE") inline operator fun <T> List<T>.get(k: FaunaKind) = this[k.i]
@Suppress("NOTHING_TO_INLINE") inline operator fun FloatArray.get(k: FaunaKind) = this[k.i]
@Suppress("NOTHING_TO_INLINE") inline operator fun FloatArray.set(k: FaunaKind, v: Float) { this[k.i] = v }
@Suppress("NOTHING_TO_INLINE") inline operator fun IntArray.get(k: FaunaKind) = this[k.i]
@Suppress("NOTHING_TO_INLINE") inline operator fun IntArray.set(k: FaunaKind, v: Int) { this[k.i] = v }
@Suppress("NOTHING_TO_INLINE") inline operator fun BooleanArray.get(k: FaunaKind) = this[k.i]
@Suppress("NOTHING_TO_INLINE") inline operator fun BooleanArray.set(k: FaunaKind, v: Boolean) { this[k.i] = v }
@Suppress("NOTHING_TO_INLINE") inline operator fun DoubleArray.get(k: FaunaKind) = this[k.i]
@Suppress("NOTHING_TO_INLINE") inline operator fun DoubleArray.set(k: FaunaKind, v: Double) { this[k.i] = v }
