package inflation.ecology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MoveGateTest {
    private val asBuilt = Slots(MoveGate.RECKONED, DaySplit.TIERED, DialSource.SCAFFOLD)

    @Test fun parsesTheBestGate() {
        assertEquals(Slots(MoveGate.BEST, DaySplit.WEIGHTED, DialSource.WAYS), Slots.parse("gate:best,days:weighted,dials:ways", asBuilt))
        assertEquals(MoveGate.SURPLUS, Slots.parse("gate:surplus", asBuilt).gate)
        assertEquals(asBuilt, Slots.parse(null, asBuilt))
        assertFailsWith<IllegalStateException> { Slots.parse("gate:greedy", asBuilt) }
    }

    @Test fun theNetsBandsTakeTheBestPlaceAndCanHaveTheDrawBack() {
        assertEquals(MoveGate.BEST, Mind.NETS.gate)
        assertEquals(MoveGate.EXPLORE_DRAW, Slots.parse("gate:draw", Slots(MoveGate.BEST, DaySplit.WEIGHTED, DialSource.WAYS), FORAGER_SLOTS).gate)
    }

    @Test fun bestTakesTheTopScoreWithinOneSurvivalStepOfTheSafest() {
        val nan = Float.NaN
        // The top score (place 2) is 1.5 steps deadlier than the safest: out; of the rest place 1 scores best.
        assertEquals(1, exploreBest(4, floatArrayOf(0f, 2f, 9f, 1f), floatArrayOf(0f, 1f, 1.5f, 0f)))
        // An unweighable place is never taken, nor an unscored one.
        assertEquals(0, exploreBest(3, floatArrayOf(1f, nan, 5f), floatArrayOf(0f, 0f, Float.POSITIVE_INFINITY)))
        assertEquals(-1, exploreBest(2, floatArrayOf(nan, nan), floatArrayOf(0f, 0f)))
    }
}
