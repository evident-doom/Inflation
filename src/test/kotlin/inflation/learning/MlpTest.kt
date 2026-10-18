package inflation.learning

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.File

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

    @Test
    fun aSavedFileKeepsEachUnitsWeightsTogether() {
        // sizes 2 2 1; unit 0 of the hidden layer reads (0.5, -1), unit 1 (0.3, 0.7); biases 0.25, -0.1; the output reads (2, -1), bias 0.1.
        val m = Mlp.load("2 2 1\n0.5 -1 0.3 0.7 0.25 -0.1 2 -1 0.1\n")
        val b = m.buffers(); floatArrayOf(1f, 2f).copyInto(b.a[0])
        val want = 0.1 + 2 * kotlin.math.tanh(0.25 + 0.5 - 2.0) - kotlin.math.tanh(-0.1 + 0.3 + 1.4)
        assertEquals(want.toFloat(), m.forward(b), 1e-5f)
        val f = File.createTempFile("mlp", ".mlp").also { it.deleteOnExit() }
        m.save(f)
        assertEquals("2 2 1\n0.5 -1.0 0.3 0.7 0.25 -0.1 2.0 -1.0 0.1\n", f.readText())
        assertContentEquals(m.w, Mlp.load(f.readText()).w)
    }

    @Test
    fun sharedInputsScoreAsTheWholeInputToTheBit() {
        val rnd = Random(3)
        for (sizes in listOf(intArrayOf(9, 6, 6, 1), intArrayOf(9, 5, 1))) {
            val m = Mlp.random(sizes, rnd); for (i in m.w.indices) m.w[i] += 0.1f * (rnd.nextFloat() - 0.5f)
            val varying = 4; val n = 7
            val rows = FloatArray(n * varying) { if (it % 5 == 0) 0f else 2f * rnd.nextFloat() - 1f }
            val shared = FloatArray(sizes[0]) { if (it == 6) 0f else 2f * rnd.nextFloat() - 1f }
            val out = FloatArray(n); m.forwardShared(rows, n, varying, shared, out, m.buffers())
            val b = m.buffers()
            for (k in 0 until n) {
                rows.copyInto(b.a[0], 0, k * varying, (k + 1) * varying); shared.copyInto(b.a[0], varying, varying, sizes[0])
                assertEquals(m.forward(b).toRawBits(), out[k].toRawBits())
            }
        }
    }

    @Test
    fun gradientsMatchFiniteDifferences() {
        val sizes = intArrayOf(5, 4, 3, 2); val m = Mlp.random(sizes, Random(11))
        for (i in m.w.indices) m.w[i] += 0.2f * (Random(i).nextFloat() - 0.5f)
        val x = floatArrayOf(0.3f, -0.8f, 0.5f, 0f, 1.1f); val dOut = floatArrayOf(1f, -0.5f)
        fun loss(): Double { val b = m.buffers(); x.copyInto(b.a[0]); val y = m.forwardAll(b); return (dOut[0] * y[0] + dOut[1] * y[1]).toDouble() }
        val b = m.buffers(); x.copyInto(b.a[0]); m.forwardAll(b)
        val grad = FloatArray(m.w.size); val dIn = FloatArray(5); m.backwardAll(b, dOut, grad, dIn)
        val h = 1e-2f
        for (i in m.w.indices) {
            val w0 = m.w[i]
            m.w[i] = w0 + h; val up = loss(); m.w[i] = w0 - h; val down = loss(); m.w[i] = w0
            assertEquals((up - down) / (2 * h), grad[i].toDouble(), 2e-3, "weight $i")
        }
        for (i in x.indices) {
            val x0 = x[i]
            x[i] = x0 + h; val up = loss(); x[i] = x0 - h; val down = loss(); x[i] = x0
            assertEquals((up - down) / (2 * h), dIn[i].toDouble(), 2e-3, "input $i")
        }
    }

    @Test
    fun tanhIsCloseEverywhereAndNeverPastOne() {
        var x = -20f
        while (x <= 20f) {
            val t = Mlp.tanh(x)
            assertEquals(kotlin.math.tanh(x.toDouble()), t.toDouble(), 5e-7, "at $x")
            assertTrue(kotlin.math.abs(t) <= 1f)
            x += 0.000731f
        }
        assertEquals(0f, Mlp.tanh(0f))
    }
}
