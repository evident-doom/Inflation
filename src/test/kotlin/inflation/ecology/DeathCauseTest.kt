package inflation.ecology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class DeathCauseTest {
    @Test
    fun `labels are distinct and print as themselves`() {
        val all = DeathCause.entries.map { it.label } + Illness.entries.map { it.label }
        assertEquals(all.size, all.toSet().size)
        for (c in DeathCause.entries) assertEquals(c.label, "$c")
    }

    @Test
    fun `deaths to beasts print as they did, one of each`() {
        assertEquals("taken by lion", ByBeast.of(ByBeast.How.TAKEN_BY, Catalogue.LION).label)
        assertEquals("killed by wolf", ByBeast.of(ByBeast.How.KILLED_BY, Catalogue.WOLF).label)
        assertEquals("young killed by wolf", ByBeast.of(ByBeast.How.YOUNG_KILLED_BY, Catalogue.WOLF).label)
        assertSame(ByBeast.of(ByBeast.How.KILLED_BY, Catalogue.WOLF), ByBeast.of(ByBeast.How.KILLED_BY, Catalogue.WOLF))
    }

    @Test
    fun `the viewer files each cause under a fitting heading`() {
        assertEquals(PlaceRecord.Cause.HUNGER, DeathCause.HUNGER.shown)
        assertEquals(PlaceRecord.Cause.KILLED, ByBeast.of(ByBeast.How.TAKEN_BY, Catalogue.LION).shown)
        assertEquals(PlaceRecord.Cause.KILLED, DeathCause.RAIDS.shown)
        assertEquals(PlaceRecord.Cause.COLD, DeathCause.EXPOSURE.shown)
        for (i in Illness.entries) assertEquals(PlaceRecord.Cause.ILLNESS, i.shown)
    }
}
