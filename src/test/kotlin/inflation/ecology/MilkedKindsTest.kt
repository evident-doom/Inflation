package inflation.ecology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Only the kinds whose kept descendants were milked give milk when kept ([Species.milked], [milkKg]). */
class MilkedKindsTest {
    @Test
    fun `only milked kinds give milk`() {
        assertEquals(setOf("aurochs", "wild goat", "wild horse", "mouflon", "dromedary"), Catalogue.all.filter { it.milked }.map { it.name }.toSet())
        for (s in Catalogue.all) if (s.milked) assertTrue(milkKg(s) > 0f, s.name) else assertEquals(0f, milkKg(s), s.name)
    }
}
