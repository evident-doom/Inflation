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
    fun `every field of a band's kept animals has its contract's split and join rule`() {
        fun fields(c: Class<*>) = c.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && !Modifier.isTransient(it.modifiers) && !it.isSynthetic }
            .map { it.name }.toSortedSet()
        assertEquals(fields(Herd::class.java), Herd.CARRY.keys.toSortedSet(), "Herd fields without a rule in Herd.CARRY, or rules for fields that are gone")
        assertEquals(fields(Dogs::class.java), Dogs.CARRY.keys.toSortedSet(), "Dogs fields without a rule in Dogs.CARRY, or rules for fields that are gone")
    }

    @Test
    fun `an offshoot takes its share of the dogs`() {
        val a = Band(1); a.dogs.kept[0] = 5f
        val o = Band(1)
        a.splitInto(o, 2, 8, 0.2f, Random(1)) {}
        assertEquals(1f, o.dogs.kept[0], 1e-6f); assertEquals(4f, a.dogs.kept[0], 1e-6f)
    }

    @Test
    fun `a joining band brings its herd, goods and sickness`() {
        val a = Band(1); val b = Band(1)
        a.herds.herd[0] = 2f; a.herds.herdTame[0] = 0.5f
        b.herds.herd[0] = 2f; b.herds.herdTame[0] = 0.1f
        a.herds.sows()[0] = 0.8f; b.herds.sows()[0] = 0.2f
        b.ornaments = 3f; b.exotic = 1f; b.sick[Illness.entries[0]] = 0.4f; b.hurt = 0.2f
        a.absorb(b, 5, 0.5f, 0f, 0f, 0f, 0f, 1, 2)
        assertEquals(4f, a.herds.herd[0]); assertEquals(0.3f, a.herds.herdTame[0], 1e-6f)
        assertEquals(0.5f, a.herds.sows()[0], 1e-6f)
        assertEquals(3f, a.ornaments); assertEquals(1f, a.exotic)
        assertEquals(0.2f, a.sick[Illness.entries[0]], 1e-6f); assertEquals(0.1f, a.hurt, 1e-6f)
    }

    @Test
    fun `an offshoot takes its share of the herd and seed corn and keeps the grain and raid memory`() {
        val a = Band(1); a.herds.herd[0] = 10f; a.seedDomestic = 0.7f; a.seedCorn = 10f; a.sinceRaided = 3
        val o = Band(1)
        a.splitInto(o, 2, 8, 0.2f, Random(1)) {}
        assertEquals(2f, o.herds.herd[0], 1e-6f); assertEquals(8f, a.herds.herd[0], 1e-6f)
        assertEquals(0.7f, o.seedDomestic); assertEquals(3, o.sinceRaided)
        assertEquals(2f, o.seedCorn, 1e-6f); assertEquals(8f, a.seedCorn, 1e-6f)
    }

    @Test
    fun `a joining band's people keep what they foresaw`() {
        val bins = ForeseenBin.entries.size
        val a = Band(1); val b = Band(1)
        a.foreseenShares[0] = 1f; a.foreseenAt = 1
        b.foreseenShares[3 * bins + bins - 1] = 1f; b.foreseenAt = 4
        a.absorb(b, 5, 0.25f, 0f, 0f, 0f, 0f, 1, 2)
        assertEquals(0.75f, a.foreseenShares[0], 1e-6f); assertEquals(0.25f, a.foreseenShares[bins - 1], 1e-6f)
    }

    @Test
    fun `an offshoot takes every field its rule says it copies`() {
        // (Each number or array of numbers whose rule is [OnSplit.COPIED] set to a value of its own, then the band split: the
        // new band must hold the same. Before 13 Nov 2026 the rules said so of stayWeeks, weaningFood, waterShort and others
        // that splitInto left at a new band's start.)
        val a = Band(1)
        val checked = ArrayList<java.lang.reflect.Field>()
        for ((k, name) in Band.CARRY.filterValues { it.first == OnSplit.COPIED }.keys.withIndex()) {
            val f = Band::class.java.getDeclaredField(name).also { it.isAccessible = true }
            val x = 3 + k
            when (val v = f.get(a)) {
                is Float -> f.setFloat(a, x + 0.25f)
                is Int -> f.setInt(a, x)
                is Long -> f.setLong(a, x.toLong())
                is FloatArray -> v.fill(x + 0.25f)
                is IntArray -> v.fill(x)
                is LongArray -> v.fill(x.toLong())
                else -> continue
            }
            checked += f
        }
        a.seenStrength[7] = 2f; a.seenDay[7] = 11L
        val o = Band(1)
        a.splitInto(o, 2, 8, 0.2f, Random(1)) {}
        for (f in checked) when (val v = f.get(a)) {
            is FloatArray -> assertEquals(v.toList(), (f.get(o) as FloatArray).toList(), f.name)
            is IntArray -> assertEquals(v.toList(), (f.get(o) as IntArray).toList(), f.name)
            is LongArray -> assertEquals(v.toList(), (f.get(o) as LongArray).toList(), f.name)
            else -> assertEquals(v, f.get(o), f.name)
        }
        assertEquals(2f, o.seenStrength[7]); assertEquals(11L, o.seenDay[7])
    }

    @Test
    fun `a joining band adds what it heard of ways of life and its raid walk, and blends last week's lean food and salt`() {
        val a = Band(1); val b = Band(1)
        val l = Livelihood.entries[0]
        a.heardLivelihood[l] = 1f; b.heardLivelihood[l] = 2f
        a.raidWalkOwed = 1f; b.raidWalkOwed = 3f
        a.leanLost = 0.2f; b.leanLost = 0.6f; a.sodiumShort = 0f; b.sodiumShort = 0.4f
        a.absorb(b, 5, 0.5f, 0f, 0f, 0f, 0f, 1, 2)
        assertEquals(3f, a.heardLivelihood[l], 1e-6f); assertEquals(4f, a.raidWalkOwed, 1e-6f)
        assertEquals(0.4f, a.leanLost, 1e-6f); assertEquals(0.2f, a.sodiumShort, 1e-6f)
    }
}
