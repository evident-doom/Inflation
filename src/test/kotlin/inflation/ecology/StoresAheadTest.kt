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
    fun `a camp that gives less than the need starves, one that gives it does not`() {
        val fed = campAlive(s, days, 13, 1f, 1f, 0f, 0f, 0f, 1f)
        val short = campAlive(s, days, 13, 0.4f, 1f, 0f, 0f, 0f, 1f)
        val stocked = campAlive(s, days, 13, 0.4f, 1f, 60f, 0f, 0f, 1f)
        assertEquals(1f, fed, 1e-4f)
        assertTrue(short < fed - 0.01f, "13 weeks at 0.4 of the need with nothing laid by: $short")
        assertTrue(stocked > short, "a larder carries it: $stocked against $short")
    }

    @Test
    fun `the camp's own record reads back to the move`() {
        val b = Band(1)
        val start = Date.DAYS_PER_YEAR.toLong() * 3
        // (Five steps: two at the old camp at 0.5, a move, then two at 1.5.)
        val got = floatArrayOf(0.5f, 0.5f, 1.5f, 1.5f, 1.5f)
        for (k in got.indices) b.recordWeek(Date(start + k * days), days, got[k], 1f, k == 2)
        assertEquals(1.5f, b.gotSinceMove(Date(start + got.size * days), days), 1e-5f)
        assertTrue(Band(1).gotSinceMove(Date(start), days).isNaN())
    }
}
