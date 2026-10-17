package inflation.ecology

import inflation.learning.Schema
import inflation.learning.SharedMover
import inflation.learning.Mlp
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TemperamentTest {
    @Test
    fun `founders differ by chance but the same id gives the same temperament, and offshoots stay near their parent`() {
        val a = Temperament.founder(7, 1L); val b = Temperament.founder(8, 1L)
        assertEquals(a.toList(), Temperament.founder(7, 1L).toList())
        assertNotEquals(a.toList(), b.toList())
        val all = (0 until 4000).map { Temperament.founder(it, 1L)[0].toDouble() }
        val sd = kotlin.math.sqrt(all.sumOf { it * it } / all.size)
        assertEquals(Temperament.FOUNDER_SD.toDouble(), sd, 0.01)
        assertNotEquals(a.toList(), Temperament.founder(7, 2L).toList(), "another world's seed gives other founders")
        val child = Temperament.inherit(a, 9001, 1L)
        for (k in 0 until Temperament.AXES) assertTrue(kotlin.math.abs(child[k] - a[k]) < 6 * Temperament.DRIFT_SD)
    }

    @Test
    fun `a mover saved before temperament reads it with zero weight, and a bold group leans toward hunters`() {
        val rnd = Random(3)
        val old = SharedMover(Mlp.random(intArrayOf(SharedMover.INPUTS - Temperament.AXES, 8, 8, 1), rnd),
            FloatArray(Catalogue.all.size * SharedMover.IDENTITY))
        val oldNames = Schema.MOVER_IN.dropLast(Temperament.AXES)
        val now = SharedMover(Schema.fit(old.net, oldNames, Schema.MOVER_OUT, Schema.MOVER_IN, Schema.MOVER_OUT), old.identity)
        val f = FloatArray(HexFeatures.SIZE) { rnd.nextFloat() }
        val ob = old.net.buffers(); val nb = now.net.buffers()
        now.fill(Catalogue.HUMAN, f, null, nb.a[0], floatArrayOf(1f, -1f, 0.5f, 0.3f))
        nb.a[0].copyInto(ob.a[0], 0, 0, ob.a[0].size)
        assertEquals(old.net.forward(ob), now.net.forward(nb), 1e-5f)
        val risky = f.copyOf().also { it[3] = 0.9f }; val safe = f.copyOf().also { it[3] = 0.1f }
        val bold = floatArrayOf(0.2f, 0f, 0f, 0f)
        assertTrue(now.pull(bold, risky) > now.pull(bold, safe))
        assertTrue(now.pull(floatArrayOf(-0.2f, 0f, 0f, 0f), risky) < now.pull(floatArrayOf(-0.2f, 0f, 0f, 0f), safe))
    }
}
