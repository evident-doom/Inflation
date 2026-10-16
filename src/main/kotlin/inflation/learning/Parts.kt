package inflation.learning

/**
 * [f] on each of [HexPpo.PARTS] shares of [batch] at once (one per share, on all cores); the results in
 * the shares' order, so that summing them in turn comes out the same on every machine.
 */
internal fun <C, R> inParts(batch: List<C>, f: (List<C>) -> R): List<R> =
    batch.chunked((batch.size + HexPpo.PARTS - 1) / HexPpo.PARTS).parallelStream().map { f(it) }.toList()

/** Adds [other] into this, slot by slot (a gradient summed from its parts). */
internal fun FloatArray.add(other: FloatArray) { for (i in indices) this[i] += other[i] }
