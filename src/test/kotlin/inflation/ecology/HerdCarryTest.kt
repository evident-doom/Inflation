package inflation.ecology

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every field a herd keeps has a rule for when animals change form ([herdCarryRules]). */
class HerdCarryTest {
    @Test
    fun `every herd field has a gather, join and spread rule`() {
        val fields = HexGroup::class.java.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && !Modifier.isTransient(it.modifiers) && !it.isSynthetic }
            .map { it.name }.toSortedSet()
        assertEquals(fields, herdCarryRules().keys.toSortedSet(), "fields without a rule in herdCarryRules, or rules for fields that are gone")
    }
}
