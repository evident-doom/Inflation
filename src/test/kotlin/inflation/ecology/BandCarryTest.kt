package inflation.ecology

import java.lang.reflect.Modifier
import kotlin.random.Random
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

    @Test
    fun `a joining band brings its herd, goods and sickness`() {
        val a = Band(); val b = Band()
        a.herd = floatArrayOf(2f); a.herdTame = floatArrayOf(0.5f)
        b.herd = floatArrayOf(2f); b.herdTame = floatArrayOf(0.1f)
        b.ornaments = 3f; b.exotic = 1f; b.sick[Illness.entries[0]] = 0.4f; b.hurt = 0.2f
        a.absorb(b, 5, 0.5f, 0f, 0f, 0f, 0f, 1, 2)
        assertEquals(4f, a.herd[0]); assertEquals(0.3f, a.herdTame[0], 1e-6f)
        assertEquals(3f, a.ornaments); assertEquals(1f, a.exotic)
        assertEquals(0.2f, a.sick[Illness.entries[0]], 1e-6f); assertEquals(0.1f, a.hurt, 1e-6f)
    }

    @Test
    fun `an offshoot takes its share of the herd and keeps the grain and raid memory`() {
        val a = Band(); a.herd = floatArrayOf(10f); a.seedDomestic = 0.7f; a.sinceRaided = 3
        val o = Band()
        a.splitInto(o, 2, 8, 0.2f, Random(1)) {}
        assertEquals(2f, o.herd[0], 1e-6f); assertEquals(8f, a.herd[0], 1e-6f)
        assertEquals(0.7f, o.seedDomestic); assertEquals(3, o.sinceRaided)
    }
}
