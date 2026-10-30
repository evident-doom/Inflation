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
                CUSTOMS_HOSTILITY * (2f / CUSTOMS))
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
        val far = Band(0).also { f -> f.lineage[5] = 1f; f.totem = FaunaKind(2); for (t in Tradition.entries) f.traditions[t] = !b.holds(t) }
        assertTrue(Regard(b, 9, far, 0f).wariness <= Regard.MOST_WARY)
        // (No pair differs in every custom: where they lay their dead counts only where both bury them.)
        assertEquals(1f + STRANGER_HOSTILITY + CUSTOMS_HOSTILITY * (CUSTOMS - 1f) / CUSTOMS, Regard(b, 9, far, 0f).wariness, 1e-6f)
        b.grudge[5] = FEUD + 0.1f
        assertTrue(Regard(b, 9, far).feud)
    }

    @Test
    fun `a lineage's bands share a totem, one that eats of it is not seen to keep it, and the many sway which is kept`() {
        assertEquals(totemAt(3, 20), totemAt(3, 20))
        assertTrue((0 until LINEAGES).all { totemAt(it, 20).i in 0 until 20 })
        assertTrue((0 until LINEAGES).map { totemAt(it, 20) }.toSet().size > 4, "lineages keep few totems among them")
        assertTrue(totemAt(3, 0).none)
        val b = Band(20); val o = Band(20)
        b.lineage[3] = 1f; o.lineage[3] = 1f
        b.ensureMarks(20); o.ensureMarks(20)
        assertEquals(totemAt(3, 20), b.totem); assertEquals(b.totem, o.totem)
        assertEquals(0, Regard(b, 1, o, 1f).customsApart, "one lineage, one totem")
        o.totemBroken = TOTEM_SHAME_YEARS
        assertTrue(o.keptTotem.none)
        assertEquals(1, Regard(b, 1, o, 1f).customsApart, "a band known to have eaten of its totem is one custom apart")
        // (Seen: three bands keeping another kind; itself counts for its own.)
        val other = FaunaKind((b.totem.i + 1) % 20)
        val c = Band(20).also { it.totem = other; it.marksChosen = true }
        repeat(3) { b.hear(c, 1f) }
        assertEquals(0.75f, b.heardTotemShare(other), 1e-6f)
        assertEquals(0.25f, b.heardTotemShare(b.totem), 1e-6f)
        // (A band from a save made before totems hears of none until its first week.)
        val old = Band(0); old.hear(c, 1f)
        old.ensureMarks(20)
        assertEquals(21, old.heardTotem.size); assertEquals(totemAt(old.mainLineage(), 20), old.totem)
        assertEquals(burialWayAt(old.mainLineage()), old.burialWay)
    }

    @Test
    fun `where bands lay their dead counts apart only between bands that bury them, and follows the many`() {
        assertEquals(BurialWay.entries.toSet(), (0 until LINEAGES).map { burialWayAt(it) }.toSet(), "every way some lineage's")
        val b = Band(0); val o = Band(0)
        b.burialWay = BurialWay.AT_CAMP; o.burialWay = BurialWay.AT_ANCESTORS
        assertEquals(0, Regard(b, 1, o, 1f).customsApart, "neither buries")
        b.traditions[Tradition.BURIAL] = true; o.traditions[Tradition.BURIAL] = true
        assertEquals(1, Regard(b, 1, o, 1f).customsApart)
        repeat(3) { b.hear(o, 1f) }
        assertEquals(0.75f, b.heardBurialShare(BurialWay.AT_ANCESTORS), 1e-6f)
        assertEquals(0.25f, b.heardBurialShare(BurialWay.AT_CAMP), 1e-6f)
        val c = Band(0)
        b.hear(c, 1f)
        assertEquals(0.75f, b.heardBurialShare(BurialWay.AT_ANCESTORS), 1e-6f, "bands that leave their dead do not count")
    }

    @Test
    fun `an older belief bears more strain, and the part that breaks from it keeps none of it`() {
        val b = Band(0)
        assertEquals(STRAIN_SPLIT, b.strainBorne(), 1e-6f)
        b.beliefYears = BELIEF_AGE_YEARS
        assertEquals(2f * STRAIN_SPLIT, b.strainBorne(), 1e-6f, "a belief a generation old bears twice the strain")
        b.rite[3] = 7; b.riteStrength = 2f; b.strain = 5f; b.riteOwed = 4f; b.riteAsked = 6f; b.riteSpare = 3f; b.riteDue = 7
        b.dropBelief()
        assertTrue(b.rite.all { it < 0 })
        assertEquals(0f, b.riteStrength); assertEquals(0f, b.beliefYears); assertEquals(0f, b.strain); assertEquals(0f, b.riteOwed)
        assertEquals(0f, b.riteAsked); assertEquals(0f, b.riteSpare)
        assertEquals(-1, b.riteDue)
    }

    @Test
    fun `the year turns at the equinoxes and solstices, every third month`() {
        assertEquals(listOf(0, 3, 6, 9), (0 until 12).filter { turnOfYear(it) })
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
