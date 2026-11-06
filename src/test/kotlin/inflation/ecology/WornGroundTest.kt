package inflation.ecology

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WornGroundTest {
    @Test fun normalQuantileMatchesKnownPoints() {
        assertEquals(0f, normalQuantile(0.5f), 1e-5f)
        assertEquals(1.2816f, normalQuantile(0.9f), 1e-3f)
        assertEquals(-1.6449f, normalQuantile(0.05f), 1e-3f)
    }

    @Test fun aLightBandsPaceHoldsOnFreshGroundThenFallsWithALongTail() {
        assertEquals(1f, wornPace(0f, 100f))
        assertEquals(1f, wornPace(5f, 95f))
        val third = wornPace(30f, 70f); val half = wornPace(50f, 50f); val most = wornPace(90f, 10f)
        assertTrue(third < 1f && half < third && most < half, "pace $third, $half, $most")
        // (σ 1.1: at half taken the best left yields exp(1.1 × (0 − 1.2816)) ≈ 0.24 of the fresh pace.)
        assertTrue(abs(half - 0.244f) < 0.01f, "half $half")
    }

    @Test fun aSettledBandWalksFartherAsTheNearGroundIsGoneOver() {
        assertEquals(1f, wornWalk(0f, 100f))
        // (Half the ground's food taken: twice the ground, so √2 the walk.)
        assertEquals(1.4142f, wornWalk(100f, 100f), 1e-3f)
        assertTrue(wornWalk(1000f, 1f) <= kotlin.math.sqrt(1f / (1f - WORN_MOST)) + 1e-4f)
    }
}
