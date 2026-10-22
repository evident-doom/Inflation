package inflation.ecology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What bands build is done only once it first stands whole, and stays done as it weathers. */
class StructuresTest {
    @Test
    fun `a structure is done once it first stands whole, and stays done as it weathers and is mended`() {
        val s = Structure(Built.HOUSES, 0, -1, 1, 10f)
        buildOn(s, 0.6f)
        assertFalse(s.done, "half built")
        buildOn(s, 0.5f)
        assertTrue(s.done); assertEquals(1f, s.built)
        s.built = 0.7f
        assertTrue(s.done, "weathered")
        buildOn(s, 0.1f)
        assertTrue(s.done); assertEquals(0.8f, s.built, 1e-6f)
    }
}
