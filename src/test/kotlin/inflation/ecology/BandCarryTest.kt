package inflation.ecology

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every field a band keeps has a rule for when the band splits and when another joins it ([Band.CARRY]). */
class BandCarryTest {
    @Test
    fun `every band field has a split and join rule`() {
        val fields = Band::class.java.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && !Modifier.isTransient(it.modifiers) && !it.isSynthetic }
            .map { it.name }.toSortedSet()
        assertEquals(fields, Band.CARRY.keys.toSortedSet(), "fields without a rule in Band.CARRY, or rules for fields that are gone")
    }
}
