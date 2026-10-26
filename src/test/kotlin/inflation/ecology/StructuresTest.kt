package inflation.ecology

import inflation.world.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What bands build is done only once it first stands whole, and stays done as it weathers. */
class StructuresTest {
    @Test
    fun `a structure is done once it first stands whole, and stays done as it weathers and is mended`() {
        val s = Structure(Built.HOUSES, Hex(0), -1, 1, 10f)
        buildOn(s, 0.6f)
        assertFalse(s.done, "half built")
        buildOn(s, 0.5f)
        assertTrue(s.done); assertEquals(1f, s.built)
        s.built = 0.7f
        assertTrue(s.done, "weathered")
        buildOn(s, 0.1f)
        assertTrue(s.done); assertEquals(0.8f, s.built, 1e-6f)
    }

    @Test
    fun `the deeper a house is sunk the more of the ground's warmth it brings in, in the cold`() {
        assertEquals(0f, earthGain(Skill.MUDBRICK))
        assertTrue(earthGain(Skill.PIT_HOUSE) > earthGain(Skill.STONE_FOOTING) && earthGain(Skill.STONE_FOOTING) > 0f)
        val b = Band(1)
        b.homeGroundC = 10f
        val flat = b.houseWarmC(-10f)
        b.homeEarth = earthGain(Skill.PIT_HOUSE)
        assertEquals(flat + 20f * earthGain(Skill.PIT_HOUSE), b.houseWarmC(-10f), 1e-4f)
        // (Warmer than the ground, the earth brings nothing.)
        assertEquals(b.houseWarmC(12f), Band(1).also { it.homeGroundC = 10f }.houseWarmC(12f))
    }
}
