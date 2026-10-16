package inflation.ecology

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
    fun `lineages tile the map and kinship is the chance of a shared founder`() {
        val seen = HashSet<Int>()
        for (x in 0 until 8) for (y in 0 until 8) seen += lineageAt(x * LINEAGE_KM + 1, y * LINEAGE_KM + 1)
        assertEquals((0 until LINEAGES).toSet(), seen)
        assertEquals(lineageAt(1.0, 1.0), lineageAt(4 * LINEAGE_KM + 1, 1.0), "four blocks on, the lineages repeat")
        val a = FloatArray(LINEAGES).also { it[3] = 1f }; val b = FloatArray(LINEAGES).also { it[4] = 1f }
        val half = FloatArray(LINEAGES).also { it[3] = 0.5f; it[4] = 0.5f }
        assertEquals(1f, kinship(a, a)); assertEquals(0f, kinship(a, b)); assertEquals(0.5f, kinship(a, half))
    }

    @Test
    fun `every lineage holds the universal traditions, and leaves its dead exactly when it does not bury them`() {
        for (l in 0 until LINEAGES) {
            val t = traditionsAt(l)
            for (u in listOf(Tradition.EXOGAMY, Tradition.SHARING, Tradition.MEDICINE, Tradition.ORAL_TRADITION)) assertTrue(t[u.ordinal], "lineage $l lacks $u")
            assertEquals(!t[Tradition.BURIAL.ordinal], t[Tradition.LEAVE_DEAD.ordinal], "lineage $l")
            val s = riteStrengthAt(l)
            assertTrue(s >= 0f && s < RITE_FOUNDER_MAX, "lineage $l rite strength $s")
        }
        assertTrue((0 until LINEAGES).map { traditionsAt(it).toList() }.toSet().size > 1, "all lineages start alike")
    }

    @Test
    fun `tie-breaks repeat for the same day and favour neither group week after week`() {
        assertEquals(tieBreak(1, 2, 40), tieBreak(1, 2, 40))
        assertNotEquals(tieBreak(1, 2, 40), tieBreak(1, 2, 47))
        val firsts = (0L until 520L step 7).count { tieBreak(1, 2, it) < tieBreak(2, 1, it) }
        assertTrue(firsts in 20..55, "group 1 came first $firsts weeks of 75")
    }

    @Test
    fun `food values without a band are the plain ones, and big prey fights back harder against a small party`() {
        assertEquals(MEAT_SHARE, meatShare(null)); assertEquals(RAW_SEED, seedValue(null))
        val prey = Catalogue.all.filter { it.defence > 0f }.maxBy { it.massKg }
        assertTrue(fightBack(prey, 50f) > fightBack(prey, 5000f))
        assertTrue(fightBack(prey, 1f) <= 2 * FIGHT_BACK * prey.defence + 1e-6f)
    }
}
