package inflation.ecology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeathCauseTest {
    @Test
    fun `labels are distinct and print as themselves`() {
        assertEquals(DeathCause.entries.size, DeathCause.entries.map { it.label }.toSet().size)
        for (c in DeathCause.entries) assertEquals(c.label, "$c")
    }

    @Test
    fun `named deaths carry the prefixes the reports and bands look for`() {
        assertTrue(DeathCause.takenBy("lion").startsWith(DeathCause.TAKEN_BY))
        assertTrue(DeathCause.killedBy("wolf").startsWith(DeathCause.KILLED_BY))
        assertEquals("young killed by wolf", DeathCause.youngKilledBy("wolf"))
    }

    @Test
    fun `the viewer files each cause under a fitting heading`() {
        assertEquals(PlaceRecord.Cause.HUNGER, PlaceRecord.cause(DeathCause.HUNGER.label))
        assertEquals(PlaceRecord.Cause.KILLED, PlaceRecord.cause(DeathCause.takenBy("lion")))
        assertEquals(PlaceRecord.Cause.KILLED, PlaceRecord.cause(DeathCause.RAIDS.label))
        assertEquals(PlaceRecord.Cause.COLD, PlaceRecord.cause(DeathCause.EXPOSURE.label))
    }
}
