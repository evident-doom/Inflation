package inflation.ecology

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import inflation.enumFloats

class BandTimeTest {
    @Test
    fun `asks that fit the week are given whole`() {
        val asks = floatArrayOf(3f, 2f, 1f)
        assertTrue(BandTime.shareOut(asks, floatArrayOf(1f, 1f, 1f), 7f).contentEquals(asks))
    }

    @Test
    fun `at equal weights every ask gets the same share of itself, and all the days are given`() {
        val got = BandTime.shareOut(floatArrayOf(6f, 2f, 4f), floatArrayOf(1f, 1f, 1f), 6f)
        assertEquals(6f, got.sum(), 1e-3f)
        for (i in got.indices) assertEquals(0.5f, got[i] / floatArrayOf(6f, 2f, 4f)[i], 1e-3f)
    }

    @Test
    fun `the less weighed lose their days first, and none gets more than it asked`() {
        val asks = floatArrayOf(4f, 4f)
        val got = BandTime.shareOut(asks, floatArrayOf(4f, 1f), 4f)
        assertEquals(4f, got.sum(), 1e-3f)
        assertTrue(got[0] > got[1] && got[0] <= asks[0] && got[1] >= 0f, got.toList().toString())
    }

    @Test
    fun `what is held for one is kept from the others till it is released`() {
        val time = BandTime(10f)
        val asks = enumFloats<Activity>()
        asks[Activity.FOOD] = 6f; asks[Activity.LEISURE] = 6f
        time.plan(enumFloats(), asks)
        assertEquals(5f, time.leftFor(Activity.FOOD), 1e-3f)
        assertEquals(0f, time.leftFor(Activity.TOOLS), 1e-3f)
        time.spend(Activity.FOOD, 3f)
        time.release(Activity.FOOD)
        assertEquals(2f, time.leftFor(Activity.TOOLS), 1e-3f)
        assertTrue(abs(time.left - 7f) < 1e-3f)
    }

    @Test
    fun `a day takes food by how hard its work is, against a day foraging`() {
        assertEquals(0f, Activity.FOOD.restSaves, 1e-6f)
        assertEquals(0.34f, Activity.LEISURE.restSaves, 0.01f)
        assertTrue(Activity.TOOLS.restSaves in 0.1f..0.3f)
        assertTrue(Activity.FIREWOOD.restSaves < 0f)
    }

    @Test
    fun `the days no activity asks for are the week's rest`() {
        val time = BandTime(10f)
        val asks = enumFloats<Activity>()
        asks[Activity.FOOD] = 3f; asks[Activity.TOOLS] = 2f
        time.plan(enumFloats(), asks)
        assertEquals(5f, time.unheld, 1e-3f)
        time.spend(Activity.FOOD, 1f)
        assertEquals(5f, time.unheld, 1e-3f)
    }

    @Test
    fun `what a band needs to live through the week comes before the rest`() {
        val time = BandTime(10f)
        val needs = enumFloats<Activity>(); val asks = enumFloats<Activity>()
        needs[Activity.FOOD] = 7f; asks[Activity.LEISURE] = 6f; asks[Activity.TOOLS] = 3f
        time.plan(needs, asks)
        assertEquals(7f, time.leftFor(Activity.FOOD), 1e-3f)
        assertEquals(1f, time.leftFor(Activity.TOOLS), 1e-3f)
    }

    @Test
    fun `the fire comes before the food, the food before the herd, and the herd before the rest`() {
        val time = BandTime(10f)
        val warm = enumFloats<Activity>(); val kept = enumFloats<Activity>(); val needs = enumFloats<Activity>(); val asks = enumFloats<Activity>()
        warm[Activity.FIREWOOD] = 3f; needs[Activity.FOOD] = 5f; kept[Activity.SOWING] = 4f; asks[Activity.TOOLS] = 3f
        time.plan(needs, asks, warm0 = warm, kept = kept)
        assertEquals(3f, time.heldFor(Activity.FIREWOOD), 1e-3f)
        assertEquals(5f, time.heldFor(Activity.FOOD), 1e-3f)
        assertEquals(2f, time.heldFor(Activity.SOWING), 1e-3f)
        assertEquals(0f, time.heldFor(Activity.TOOLS), 1e-3f)
    }

    @Test
    fun `work at half pace takes twice the people's days, the rest lost`() {
        val pace = enumFloats<Activity> { 1f }.also { it[Activity.FOOD] = 0.5f }
        val t = BandTime(10f, pace)
        assertEquals(5f, t.leftFor(Activity.FOOD), 1e-6f)
        assertEquals(10f, t.leftFor(Activity.TOOLS), 1e-6f)
        t.spend(Activity.FOOD, 2f)
        assertEquals(6f, t.left, 1e-6f)
        assertEquals(2f, t.lost, 1e-6f)
        assertEquals(4f, t.spentOn(Activity.FOOD), 1e-6f)
    }

    @Test
    fun `work done is counted apart from the days the cold and hunger took`() {
        val time = BandTime(10f, enumFloats<Activity> { a -> if (a == Activity.TOOLS) 0.5f else 1f })
        time.spend(Activity.TOOLS, 2f)
        time.took(Activity.FOOD, 3f)
        assertEquals(4f, time.spentOn(Activity.TOOLS), 1e-4f)
        assertEquals(2f, time.workedOn(Activity.TOOLS), 1e-4f)
        assertEquals(3f, time.workedOn(Activity.FOOD), 1e-4f)
    }
}
