package inflation.ecology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** People's body heat in the cold: every means of keeping warm helps a little, and the unprotected die in a cold night. */
class BodyHeatTest {
    private fun air(meanC: Float, windMs: Float = 2f, wet: Float = 0f, snow: Boolean = false) = Air(meanC, 8f, windMs, wet, snow)
    private fun cover(clo: Float = 1f, fire: Float = 0f, shelter: Float = 0f, home: Float = 0f) = Cover(clo, fire, shelter, home, HOUSE_C)

    @Test
    fun `in warm weather the lightly clad keep warm at no cost and none die`() {
        val w = BodyHeat.week(air(25f), cover(1f), 7)
        assertEquals(0f, w.extra); assertEquals(0f, w.odds)
    }

    @Test
    fun `the naked die of exposure in a windy night near freezing, the clad with fire and shelter do not`() {
        assertTrue(BodyHeat.week(air(4f, windMs = 4f, wet = 0.5f), cover(0f), 7).odds > 0.1f)
        assertEquals(0f, BodyHeat.week(air(4f, windMs = 4f, wet = 0.5f), cover(1f, fire = 1f, shelter = 1f), 7).odds)
    }

    @Test
    fun `each lesser means helps a little, clothes, fire, shelter with its bedding, a house, and dry and still air`() {
        // (Mild enough that shivering makes up for all of it, so that each means shows in the food.)
        val cold = air(5f, windMs = 2f, wet = 0.3f)
        val base = BodyHeat.week(cold, cover(2f), 7).extra
        assertTrue(base > 0f)
        assertTrue(BodyHeat.week(cold, cover(2.5f), 7).extra < base, "clothes")
        assertTrue(BodyHeat.week(cold, cover(2f, fire = 1f), 7).extra < base, "fire")
        assertTrue(BodyHeat.week(cold, cover(2f, shelter = 1f), 7).extra < base, "shelter")
        assertTrue(BodyHeat.week(cold, cover(2f, home = 1f), 7).extra < base, "house")
        assertTrue(BodyHeat.week(air(5f, windMs = 1f, wet = 0.3f), cover(2f), 7).extra < base, "less wind")
        assertTrue(BodyHeat.week(air(5f, windMs = 2f), cover(2f), 7).extra < base, "dry")
    }

    @Test
    fun `deaths rise with the heat debt, half at the lethal debt`() {
        assertEquals(0f, BodyHeat.lethal(0f))
        assertEquals(0.5f, BodyHeat.lethal(BodyHeat.LETHAL_WH_M2), 1e-6f)
        assertTrue(BodyHeat.lethal(100f) < BodyHeat.lethal(200f))
    }
}
