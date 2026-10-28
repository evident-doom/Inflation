package inflation.ecology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** People's body heat in the cold: every means of keeping warm helps a little, and the unprotected die in a cold night. */
class BodyHeatTest {
    private fun air(meanC: Float, windMs: Float = 2f, wet: Float = 0f, snow: Boolean = false) = Air(meanC, 8f, windMs, wet, snow)
    private fun cover(clo: Float = 1f, fire: Float = 0f, shelter: Float = 0f, home: Float = 0f) =
        Cover(clo, fire, shelter, home, fire * HEARTH_WOOD / FIRE_HOUSE_KG_C)
    /** A fire that keeps those about it 10 °C warmer in a light hut, and in a hut thatched whole; one keeping a house's air 20 °C warmer. */
    private val open10 = 10f / BodyHeat.fireC(1f, 1f)
    private val thatched10 = 10f / BodyHeat.fireC(1f, 2f)
    private val house20 = 20f * FIRE_HOUSE_KG_C / HEARTH_WOOD

    @Test
    fun `in warm weather the lightly clad keep warm at no cost and none die`() {
        val w = BodyHeat.week(air(25f), cover(1f), 7)
        assertEquals(0f, w.extra); assertEquals(0f, w.odds)
    }

    @Test
    fun `the naked die of exposure in a windy night near freezing, the clad with fire and shelter do not`() {
        assertTrue(BodyHeat.week(air(4f, windMs = 4f, wet = 0.5f), cover(0f), 7).odds > 0.1f)
        assertEquals(0f, BodyHeat.week(air(4f, windMs = 4f, wet = 0.5f), cover(1f, fire = open10, shelter = 1f), 7).odds)
    }

    @Test
    fun `the same wood warms more in a thatched hut than in a light one, and most in a house`() {
        assertEquals(1f / BodyHeat.FIRE_OPEN_KG_C, BodyHeat.fireC(1f, 0f), 1e-6f)
        assertEquals(1f / BodyHeat.FIRE_HUT_KG_C, BodyHeat.fireC(1f, 2f), 1e-6f)
        assertTrue(BodyHeat.fireC(1f, 1f) > BodyHeat.fireC(1f, 0f) && BodyHeat.fireC(1f, 1f) < BodyHeat.fireC(1f, 2f), "a light hut's walls between")
        assertTrue(BodyHeat.fireC(2f, 1f) == 2f * BodyHeat.fireC(1f, 1f), "twice the wood, twice the warmth")
        assertTrue(HEARTH_WOOD / FIRE_HOUSE_KG_C > BodyHeat.fireC(1f, 2f))
        val cold = air(-5f, windMs = 3f)
        assertTrue(BodyHeat.week(cold, cover(1.5f, fire = 3f, shelter = 2f), 7).extra < BodyHeat.week(cold, cover(1.5f, fire = 3f, shelter = 1f), 7).extra)
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

    @Test
    fun `those who do not persevere break off work to warm up and carry less heat debt, those who do work on`() {
        // (Cold and wet enough that the work chills them beyond what shivering makes up, while the camp's fire and thatched huts warm them again.)
        val raw = air(-5f, windMs = 6f, wet = 0.5f)
        val c = cover(1f, fire = thatched10, shelter = 2f)
        val on = BodyHeat.week(raw, c, 7, sisu = 1f)
        val off = BodyHeat.week(raw, c, 7, sisu = 0f)
        assertEquals(1f, on.pace)
        assertEquals(BodyHeat.week(raw, c, 7).debt, on.debt)
        assertTrue(off.pace < 1f && off.pace > 0f, "breaks ${off.pace}")
        assertTrue(off.debt <= on.debt)
        assertTrue(BodyHeat.week(raw, c, 7, sisu = 0.5f).pace in off.pace..1f)
        assertEquals(1f, BodyHeat.week(air(20f), c, 7, sisu = 0f).pace, "no breaks in the warm")
    }

    @Test
    fun `hunger saps work only once the fat is spent, the less the more people persevere`() {
        val people = Catalogue.HUMAN
        assertEquals(1f, Sisu.hungerPace(people, 0f, 0f))
        val starving = Hazards.starveDays(people)
        assertEquals(1f - Sisu.LETHARGY, Sisu.hungerPace(people, starving, 0f), 1e-6f)
        assertEquals(1f, Sisu.hungerPace(people, starving, 1f))
        assertTrue(Sisu.hungerPace(people, starving / 2f, 0.5f) in (1f - Sisu.LETHARGY)..1f)
    }

    @Test
    fun `perseverance runs in lineages`() {
        val f = Sisu.founder(7, 1L)
        assertTrue(f in 0f..1f)
        assertEquals(f, Sisu.founder(7, 1L))
        val kids = (100 until 400).map { Sisu.inherit(0.5f, it, 1L) }
        assertTrue(kids.all { it in 0f..1f })
        assertTrue(kotlin.math.abs(kids.average() - 0.5) < 0.05, "offshoots keep their parent's, a little changed")
    }

    @Test
    fun `in deep cold crafts stop at an open camp but go on in a house by its hearth`() {
        val deep = air(-15f, windMs = 6f, wet = 0.5f)
        assertEquals(0f, BodyHeat.week(deep, cover(1f, fire = open10, shelter = 1f), 7, sisu = 1f).campPace, "numb hands, however they persevere")
        assertEquals(1f, BodyHeat.week(deep, cover(1f, fire = house20, shelter = 1f, home = 1f), 7, sisu = 0f).campPace)
        assertEquals(1f, BodyHeat.week(air(10f), cover(1f, fire = open10, shelter = 1f), 7, sisu = 0f).campPace)
    }
}
