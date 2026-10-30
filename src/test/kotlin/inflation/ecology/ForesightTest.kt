package inflation.ecology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The year ahead as a band foresees it ([YearAhead]). */
class ForesightTest {
    private val s = Catalogue.HUMAN
    private val background = run { var a = 1f; repeat(12) { a *= 1f - s.backgroundMortality / 12f }; a }
    private fun months(vararg first: Float) = FloatArray(12) { if (it < first.size) first[it] else 1f }

    @Test
    fun `what a month lays by pays a later month's shortfall`() {
        val none = FloatArray(12)
        val laid = YearAhead(none, months(1.5f, 0.5f)).alive(s, 0f, 0f, 0f)
        val short = YearAhead(none, months(1f, 0.5f)).alive(s, 0f, 0f, 0f)
        assertEquals(background, laid, 1e-5f)
        assertTrue(short < laid - 0.01f, "a lean month with nothing laid by kills: $short against $laid")
    }

    @Test
    fun `years that differ are reckoned bad, usual and good`() {
        val none = FloatArray(12)
        val fed = months(1f, 0.7f)
        val alike = YearAhead(none, fed).alive(s, 0f, 0f, 0f)
        assertEquals(alike, YearAhead(none, fed, FloatArray(12)).alive(s, 0f, 0f, 0f))
        val apart = YearAhead(none, fed, FloatArray(12) { 0.2f }).alive(s, 0f, 0f, 0f)
        assertTrue(apart != alike, "a spread of years changes what it foresees")
        assertTrue(apart < background, "a bad year still starves: $apart")
    }

    @Test
    fun `a camp move keeps only what it carries`() {
        val none = FloatArray(12)
        val fed = months(0.5f)
        val stays = YearAhead(none, fed).alive(s, 15f, 0f, 0f)
        val moves = YearAhead(none, fed, FloatArray(12), 2f, BooleanArray(12) { it == 0 }).alive(s, 15f, 0f, 0f)
        assertEquals(background, stays, 1e-5f)
        assertTrue(moves < stays - 0.01f, "the larder left at the old camp is missed: $moves against $stays")
    }
}
