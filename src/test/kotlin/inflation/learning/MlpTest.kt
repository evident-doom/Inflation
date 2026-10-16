package inflation.learning

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class MlpTest {
    @Test
    fun aZeroedOutputLayerGivesZeroAndKeepsTheHiddenLayer() {
        for (sizes in listOf(intArrayOf(5, 4, 1), intArrayOf(5, 4, 3), intArrayOf(5, 4, 4, 2))) {
            val m = Mlp.random(sizes, Random(7)); val before = m.w.copyOf()
            m.zeroOutputLayer()
            val hidden = Mlp.count(sizes.copyOf(sizes.size - 1))
            assertContentEquals(before.copyOf(hidden), m.w.copyOf(hidden))
            val b = m.buffers(); FloatArray(5) { it - 2f }.copyInto(b.a[0])
            assertContentEquals(FloatArray(sizes.last()), m.forwardAll(b).copyOf())
        }
    }

    @Test
    fun partsComeBackInOrderAndSumAsInTurn() {
        val batch = (0 until 1000).map { it.toFloat() }
        val parts = inParts(batch) { part -> floatArrayOf(part.sum(), part.size.toFloat()) }
        val shares = batch.chunked((batch.size + HexPpo.PARTS - 1) / HexPpo.PARTS)
        assertEquals(shares.size, parts.size)
        for ((i, p) in parts.withIndex()) assertEquals(shares[i].sum(), p[0])
        val g = FloatArray(2); for (p in parts) g.add(p)
        var inTurn = 0f; for (p in shares) inTurn += p.sum()
        assertEquals(inTurn, g[0]); assertEquals(1000f, g[1])
    }
}
