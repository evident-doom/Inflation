package inflation.learning

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class SchemaTest {
    private fun out(net: Mlp, x: FloatArray): FloatArray {
        val b = net.buffers(); x.copyInto(b.a[0]); return net.forwardAll(b).copyOf()
    }

    @Test
    fun aNetworkFittedToNewInputsAndOutputsChoosesAsBeforeAndIsNeutralOnTheNew() {
        val rnd = Random(3)
        val old = Mlp.random(intArrayOf(3, 5, 2), rnd)
        val oldIn = listOf("a", "b", "c"); val oldOut = listOf("x", "y")
        // "b" is gone, "d" is new (inserted in the middle), "z" is a new output.
        val newIn = listOf("a", "d", "c"); val newOut = listOf("y", "z", "x")
        val fitted = Schema.fit(old, oldIn, oldOut, newIn, newOut)
        repeat(20) {
            val a = Random.nextFloat(); val c = Random.nextFloat(); val d = Random.nextFloat()
            val before = out(old, floatArrayOf(a, 0f, c))
            val after = out(fitted, floatArrayOf(a, d, c))
            assertEquals(before[1], after[0], 1e-5f)
            assertEquals(before[0], after[2], 1e-5f)
            assertEquals(0f, after[1], 1e-6f)
        }
    }
}
