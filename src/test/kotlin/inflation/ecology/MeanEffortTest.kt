package inflation.ecology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The mean effort that splits a band's gatherers' days ([meanEffort]) stays a number whatever the foods ask. */
class MeanEffortTest {
    private fun demand(vararg v: Pair<Food, Float>) = FloatArray(FOOD_TYPES).also { d -> for ((f, x) in v) d[f.ordinal] = x }
    private fun efforts(e: Float = 1f) = FloatArray(FOOD_TYPES) { e }

    @Test
    fun `at every effort 1 the mean is 1`() {
        assertEquals(1f, meanEffort(demand(Food.FRUIT to 2f, Food.ROOTS to 3f), efforts()), 1e-6f)
    }

    @Test
    fun `foods are weighed by their days`() {
        val e = efforts(); e[Food.FRUIT.ordinal] = 2f; e[Food.ROOTS.ordinal] = 0f
        assertEquals(0.5f, meanEffort(demand(Food.FRUIT to 1f, Food.ROOTS to 3f), e), 1e-6f)
    }

    @Test
    fun `a food no gatherer hand can reach takes none of the days`() {
        // (All the hands hunting: handling a kg of fish takes 1/0 days, and effort 0 on it must not make 0 * infinity.)
        val e = efforts(); e[Food.FISH.ordinal] = 0f
        val m = meanEffort(demand(Food.FRUIT to 2f, Food.FISH to Float.POSITIVE_INFINITY), e)
        assertEquals(1f, m, 1e-6f)
        assertEquals(0f, meanEffort(demand(Food.FISH to Float.POSITIVE_INFINITY), efforts()))
    }

    @Test
    fun `no demand or no effort gives no split`() {
        assertEquals(0f, meanEffort(demand(), efforts()))
        assertEquals(0f, meanEffort(demand(Food.FRUIT to 1f), efforts(0f)))
        assertTrue(meanEffort(demand(Food.FRUIT to 1f, Food.ROOTS to Float.NaN), efforts()).isFinite())
    }
}
