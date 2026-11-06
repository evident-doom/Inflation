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

    @Test fun aFlowingCropIsReckonedAgainstWhatThePlaceBore() {
        val w = WornGround(1)
        // (A season's fruit: 1000 kg borne, 100 taken, and little ripe this week: a tenth taken, not nearly all.)
        w.crop[WORN_FRUIT][0] = 1000f; w.taken[WORN_FRUIT][0] = 100f
        assertEquals(0.1f, w.takenShare(WORN_FRUIT, inflation.world.Place(0), 5f), 1e-4f)
        // (Roots stand: what was taken against what was taken and what is left.)
        w.taken[WORN_ROOTS][0] = 100f
        assertEquals(0.5f, w.takenShare(WORN_ROOTS, inflation.world.Place(0), 100f), 1e-4f)
        // (Nothing borne on record: as much as was taken was borne.)
        w.taken[WORN_SEED][0] = 10f
        assertEquals(WORN_MOST, w.takenShare(WORN_SEED, inflation.world.Place(0), 0f), 1e-4f)
    }
}
