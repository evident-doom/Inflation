package inflation.ecology

import kotlin.test.Test
import kotlin.test.assertEquals

class BandTimeTest {
    @Test
    fun `what each chore took and what is left add up to the time there was`() {
        val t = BandTime(10f)
        t.took(Activity.FOOD, 30f)
        t.spend(Activity.TOOLS, 4f); t.spend(Activity.TOOLS, 1f)
        t.spendAtMost(Activity.DRYING, 8f)
        assertEquals(0f, t.left)
        val census = HashMap<String, Int>()
        t.count(census, Activity.FOOD, Activity.TOOLS, Activity.DRYING, Activity.SKINS)
        t.count(census, "left", t.left)
        assertEquals(mapOf("band time:food" to 300, "band time:tools" to 50, "band time:drying" to 50, "band time:skins" to 0, "band time:left" to 0), census)
    }
}
