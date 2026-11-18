package inflation.ecology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Famine by age (design "Famine deaths by age", 2026-11-13): each age reads the band's protein burnt times its tilt, and
 * dies by the rise along the starve ramp between the step's two ends ([Hazards.starvedRise]). The odds must not depend on
 * the step's length: ten 6-day steps at the same food kill as many as one of 60 days.
 */
class FamineByAgeTest {
    private val people = Catalogue.HUMAN

    @Test
    fun `ten six-day steps kill as one sixty-day step at every age's tilt`() {
        for (k in floatArrayOf(0.7f, 1f, 1.3f, 1.9f)) for (fed in floatArrayOf(0f, 0.2f, 0.4f, 0.6f)) for (start in floatArrayOf(0f, 5f, 15f)) {
            var spent = start; var alive = 1.0
            repeat(10) {
                val end = Hazards.spentAfter(people, spent, fed, 6)
                alive *= 1.0 - Hazards.starvedRise(people, k * spent, k * end)
                spent = end
            }
            val one = 1.0 - Hazards.starvedRise(people, k * start, k * Hazards.spentAfter(people, start, fed, 60))
            assertEquals(one, alive, 1e-4, "tilt $k, fed $fed, from $start days burnt")
        }
    }

    @Test
    fun `a heavier tilt dies sooner`() {
        val end = Hazards.spentAfter(people, 0f, 0.2f, 30)
        assertTrue(Hazards.starvedRise(people, 0f, 1.9f * end) > Hazards.starvedRise(people, 0f, end))
        assertEquals(0f, Hazards.starvedRise(people, 0f, 0f))
    }
}
