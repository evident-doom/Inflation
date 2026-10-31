package inflation.ecology

import inflation.world.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A band's stores reckoned over years made of its own past weeks ([weeksAlive]). */
class StoresAheadTest {
    private val s = Catalogue.HUMAN
    private val days = 6
    // (Not "weeks": inside [Band.apply] that is the band's own [Band.weeks].)
    private val perYear = Date.stepsPerYear(days)

    /** A band whose [years] years all had the weeks [got] gives, moving camp in the weeks [moved] says. */
    private fun band(years: Int, got: (Int) -> Float, moved: (Int) -> Boolean = { false }) = Band(1).apply {
        for (y in 0 until years) for (w in 0 until perYear) { weekSupply[y * perYear + w] = got(w); weekMoved[y * perYear + w] = moved(w) }
    }

    @Test
    fun `no reckoning till it has a whole year of weeks`() {
        val b = band(1, { 1f })
        assertTrue(b.weeksAlive(s, days, 0, 0f, 0f, 0f, 100f, 1L) != null)
        b.weekSupply[5] = Float.NaN
        assertNull(b.weeksAlive(s, days, 0, 0f, 0f, 0f, 100f, 1L))
    }

    @Test
    fun `a larder carries it through a lean spell`() {
        // (Eight weeks at a third of the need, the rest at a fifth over it.)
        val b = band(3, { if (it in 20 until 28) 0.33f else 1.2f })
        val empty = b.weeksAlive(s, days, 0, 0f, 0f, 0f, 1000f, 1L)!!
        val full = b.weeksAlive(s, days, 0, 60f, 0f, 0f, 1000f, 1L)!!
        assertEquals(1f, full, 1e-4f)
        assertTrue(empty < full - 0.01f, "a lean spell with nothing laid by kills: $empty against $full")
    }

    @Test
    fun `a camp move before the lean spell keeps only what it carries`() {
        val lean = { w: Int -> if (w in 20 until 28) 0.33f else 1f }
        val stays = band(3, lean).weeksAlive(s, days, 0, 60f, 0f, 0f, 2f, 1L)!!
        val moves = band(3, lean, { it == 18 }).weeksAlive(s, days, 0, 60f, 0f, 0f, 2f, 1L)!!
        assertTrue(moves < stays - 0.01f, "the larder left at the old camp is missed: $moves against $stays")
    }

    @Test
    fun `the longer a place is kept, the longer it is reckoned kept, up to a generation`() {
        fun kept(years: Float) = keepYears(Band(1).also { it.campWeeks = (years * 60).toInt() - 1 }, 60f)
        assertEquals(1.97f, kept(0.5f), 0.05f)
        assertEquals(5.2f, kept(2f), 0.05f)
        assertEquals(12.5f, kept(10f), 0.1f)
        assertTrue(kept(100f) < 100f && kept(100f) > GENERATION_YEARS * 0.8f, "a long-kept place is reckoned about a generation: ${kept(100f)}")
    }
}
