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
    fun `regard - strangers of other customs are warier than kin and friends, and the raid chance is the one it always was`() {
        val b = Band(0); val o = Band(0)
        b.lineage[3] = 1f; o.lineage[4] = 1f
        o.traditions[Tradition.BURIAL] = !b.holds(Tradition.BURIAL); o.traditions[Tradition.FEASTS] = !b.holds(Tradition.FEASTS)
        val r = Regard(b, 7, o, 0.4f)
        assertTrue(r.stranger); assertEquals(2, r.customsApart); assertEquals(0f, r.kin)
        // (The raid chance as written out in the raid itself before the regard took it over.)
        fun before(drive: Float, u: Float, tie: Float, stranger: Boolean) =
            drive * (0.5f + 0.5f * (1f - u)) * (1f - 0.5f * kinship(b.lineage, o.lineage)) *
                (1f - TIE_PEACE * maxOf(0f, tie)) * (1f + (if (stranger) STRANGER_HOSTILITY else 0f) +
                CUSTOMS_HOSTILITY * (2f / Tradition.entries.size))
        val drive = RAID * 1.3f * 0.7f
        assertEquals(before(drive, 0.4f, 0f, true), r.fightChance(drive))
        val wary = r.wariness
        b.befriend(7, 0.5f)
        assertEquals(before(drive, 0.4f, b.tie(7), false), r.fightChance(drive), "read as it stands now")
        assertTrue(r.wariness < wary && r.trust > 0.5f * 0.4f && r.takesUpPlace > 0.3f * 0.4f)
        assertEquals(0.5f * (b.tie(7) + 0f), Regard.close(r, Regard(o, 1, b, 0.4f)))
        assertTrue(r.friend && !r.feud)
        // (Unheard, it reads their words as they stand; and no band is ever warier than the most there is.)
        assertEquals(Language.understanding(b.words, o.words), Regard(b, 7, o).understood)
        val far = Band(0).also { f -> f.lineage[5] = 1f; for (t in Tradition.entries) f.traditions[t] = !b.holds(t) }
        assertTrue(Regard(b, 9, far, 0f).wariness <= Regard.MOST_WARY)
        assertEquals(Regard.MOST_WARY, Regard(b, 9, far, 0f).wariness, 1e-6f)
        b.grudge[5] = FEUD + 0.1f
        assertTrue(Regard(b, 9, far).feud)
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
