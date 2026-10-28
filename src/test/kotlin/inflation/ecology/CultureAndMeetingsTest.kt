package inflation.ecology

import inflation.Tuning
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** What bands start with and carry from meeting to meeting: words, lineages, traditions, rites; and the meeting rules that need no world. */
class CultureAndMeetingsTest {
    @Test
    fun `neighbours in one region share half their words, bands of one country all of them`() {
        val here = wordsAt(10.0, 10.0)
        assertEquals(1f, Language.understanding(here, wordsAt(140.0, 140.0)), "same 150 km country")
        assertEquals(0.5f, Language.understanding(here, wordsAt(160.0, 10.0)), 0.04f, "next country, same region")
        assertTrue(Language.understanding(here, wordsAt(1000.0, 1000.0)) < 0.1f, "a far region")
        assertTrue(here.all { it in 0 until Language.FORMS })
    }

    @Test
    fun `lineages are drawn per block without a repeating pattern, and kinship is the chance of a shared founder`() {
        val seen = HashSet<Int>()
        for (x in 0 until 8) for (y in 0 until 8) seen += lineageAt(x * LINEAGE_KM + 1, y * LINEAGE_KM + 1)
        assertEquals((0 until LINEAGES).toSet(), seen)
        assertEquals(lineageAt(1.0, 1.0), lineageAt(LINEAGE_KM - 1, LINEAGE_KM - 1), "one block, one lineage")
        assertNotEquals(lineageAt(1.0, 1.0), lineageAt(4 * LINEAGE_KM + 1, 1.0), "four blocks on, no repeat")
        val a = FloatArray(LINEAGES).also { it[3] = 1f }; val b = FloatArray(LINEAGES).also { it[4] = 1f }
        val half = FloatArray(LINEAGES).also { it[3] = 0.5f; it[4] = 0.5f }
        assertEquals(1f, kinship(a, a)); assertEquals(0f, kinship(a, b)); assertEquals(0.5f, kinship(a, half))
    }

    @Test
    fun `every lineage holds the universal traditions, and leaves its dead exactly when it does not bury them`() {
        for (l in 0 until LINEAGES) {
            val t = traditionsAt(l)
            for (u in listOf(Tradition.EXOGAMY, Tradition.SHARING, Tradition.MEDICINE, Tradition.ORAL_TRADITION)) assertTrue(t[u],
                "lineage $l lacks $u")
            assertEquals(!t[Tradition.BURIAL], t[Tradition.LEAVE_DEAD], "lineage $l")
            val s = riteStrengthAt(l, inflation.Tuning.config)
            assertTrue(s >= 0f && s < inflation.Tuning.config.riteFounderMax, "lineage $l rite strength $s")
        }
        assertTrue((0 until LINEAGES).map { traditionsAt(it).a.toList() }.toSet().size > 1, "all lineages start alike")
    }

    @Test
    fun `marks follow the many, and what a band has seen counts itself as one`() {
        assertEquals(0.5f, conformist(0.5f), 1e-6f)
        assertTrue(conformist(0.8f) > 0.8f && conformist(0.2f) < 0.2f, "the many count for more than their share")
        assertEquals(0f, conformist(0f)); assertEquals(1f, conformist(1f))
        val b = Band(0); val o = Band(0)
        o.traditions[Tradition.BURIAL] = true
        assertEquals(0f, b.heardShare(Tradition.BURIAL, true), "seen no one: only itself, which does not bury")
        b.hear(o, 1f); b.hear(o, 1f); b.hear(o, 1f)
        assertEquals(0.75f, b.heardShare(Tradition.BURIAL, true), 1e-6f)
        assertEquals(0.25f, b.heardShare(Tradition.BURIAL, false), 1e-6f)
        assertTrue(Tradition.entries.any { it.mark } && Tradition.entries.any { !it.mark })
    }

    @Test
    fun `tie-breaks repeat for the same day and favour neither group week after week`() {
        assertEquals(tieBreak(1, 2, 40), tieBreak(1, 2, 40))
        assertNotEquals(tieBreak(1, 2, 40), tieBreak(1, 2, 47))
        val firsts = (0L until 520L step 7).count { tieBreak(1, 2, it) < tieBreak(2, 1, it) }
        assertTrue(firsts in 20..55, "group 1 came first $firsts weeks of 75")
    }

    @Test
    fun `food values without a band are the plain ones`() {
        assertEquals(MEAT_SHARE, meatShare(null)); assertEquals(RAW_SEED, seedValue(null))
    }
}
