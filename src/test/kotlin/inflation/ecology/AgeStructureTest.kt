package inflation.ecology

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgeStructureTest {
    private fun band(adults: Int, elders: Int, second: Int, third: Int) =
        HexGroup(1, Catalogue.HUMAN, Place(0), adults, 5).also { g -> g.band = Band(0).also { it.elders = elders; it.ageStage[0] = second; it.ageStage[1] = third } }

    @Test
    fun `adults dying keep the age structure within the living`() {
        val r = Random(1)
        repeat(2000) {
            val a = 1 + r.nextInt(30); val e = r.nextInt(a + 1); val s0 = r.nextInt(a - e + 1); val s1 = r.nextInt(a - e - s0 + 1)
            val g = band(a, e, s0, s1)
            adultsDied(g, r.nextInt(a + 3), r)
            val b = g.band!!
            assertTrue(g.adults >= 0)
            assertTrue(b.elders >= 0 && b.ageStage.all { it >= 0 })
            assertTrue(b.elders + b.ageStage.sum() <= g.adults, "elders ${b.elders} stages ${b.ageStage.toList()} of ${g.adults}")
        }
    }

    @Test
    fun `single deaths fall on the elders by their share`() {
        val r = Random(2)
        var eldersLost = 0
        val n = 20_000
        repeat(n) { val g = band(10, 2, 3, 3); adultsDied(g, 1, r); eldersLost += 2 - g.band!!.elders }
        // A fifth of the adults are elders: about a fifth of single deaths are theirs.
        assertEquals(0.2, eldersLost.toDouble() / n, 0.02)
    }
}
