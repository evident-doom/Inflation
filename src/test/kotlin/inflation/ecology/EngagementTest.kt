package inflation.ecology

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How an engagement between two groups is settled, case by case ([actOf], [settleEngagement], [sightingsOf]): what each
 * stance does by what it knows, who sees whom, who gives way, who strikes and whether unawares. The rules a group of
 * people's strategies (bandits, war parties, scouts) will be settled by, pinned down so they cannot drift unseen.
 */
class EngagementTest {
    private val will = 1f

    @Test
    fun `one that does not see the other does nothing, whatever its stance`() {
        for (st in Stance.entries) assertEquals(Act.LIE_LOW, actOf(st, sees = false, seen = true, own = 1f, other = 1f, will = will), "$st")
    }

    @Test
    fun `each stance acts by what it sees and how strong the other is`() {
        // (stance, seen by the other, own strength, other's) to what it does.
        val cases = listOf(
            Triple(Stance.DEFEND, true, 1f to 10f) to Act.FIGHT,
            Triple(Stance.ATTACK, true, 1f to 10f) to Act.FIGHT,
            Triple(Stance.YIELD, true, 10f to 1f) to Act.WITHDRAW,
            Triple(Stance.GIVE_WAY_TO_STRONGER, true, 1f to 1f) to Act.FIGHT,
            Triple(Stance.GIVE_WAY_TO_STRONGER, true, 1f to 2f) to Act.WITHDRAW,
            Triple(Stance.GREET, true, 1f to 1f) to Act.FIGHT,
            Triple(Stance.GREET, true, 1f to 2f) to Act.WITHDRAW,
            // (An ambusher unseen strikes only with the upper hand once surprise is reckoned, else lies low; seen, it gives way to the stronger.)
            Triple(Stance.AMBUSH, false, 1f to 1.5f) to Act.FIGHT,
            Triple(Stance.AMBUSH, false, 1f to 3f) to Act.LIE_LOW,
            Triple(Stance.AMBUSH, true, 1f to 2f) to Act.WITHDRAW,
            Triple(Stance.AMBUSH, true, 1f to 1f) to Act.FIGHT,
            // (A scout unseen watches; seen, it falls back.)
            Triple(Stance.HIDE_AND_SCOUT, false, 10f to 1f) to Act.LIE_LOW,
            Triple(Stance.HIDE_AND_SCOUT, true, 10f to 1f) to Act.WITHDRAW,
        )
        for ((c, act) in cases) {
            val (st, seen, s) = c
            assertEquals(act, actOf(st, sees = true, seen = seen, own = s.first, other = s.second, will = will), "$st seen=$seen ${s.first} against ${s.second}")
        }
    }

    @Test
    fun `a greater will to fight gives way later`() {
        assertEquals(Act.WITHDRAW, actOf(Stance.GIVE_WAY_TO_STRONGER, true, true, 1f, 2f, will = 1f))
        assertEquals(Act.FIGHT, actOf(Stance.GIVE_WAY_TO_STRONGER, true, true, 1f, 2f, will = 2f))
    }

    private fun settle(stA: Stance, stB: Stance, aSees: Boolean = true, bSees: Boolean = true, sa: Float = 1f, sb: Float = 1f, raid: Boolean = false) =
        settleEngagement(stA, stB, aSees, bSees, sa, sb, will, will, raid)

    @Test
    fun `two that see each other - the yielder gives way, the stronger is given way to, near equals fight`() {
        assertEquals(Ending.B_GAVE_WAY, settle(Stance.DEFEND, Stance.YIELD).ending)
        assertEquals(Ending.A_GAVE_WAY, settle(Stance.GIVE_WAY_TO_STRONGER, Stance.DEFEND, sa = 1f, sb = 2f).ending)
        assertEquals(Ending.FOUGHT, settle(Stance.GIVE_WAY_TO_STRONGER, Stance.GIVE_WAY_TO_STRONGER).ending)
        // (Two that both withdraw: the weaker gives the ground.)
        assertEquals(Ending.A_GAVE_WAY, settle(Stance.YIELD, Stance.YIELD, sa = 1f, sb = 2f).ending)
        assertEquals(Ending.B_GAVE_WAY, settle(Stance.YIELD, Stance.YIELD, sa = 2f, sb = 1f).ending)
    }

    @Test
    fun `one taken unawares cannot get away, and its attacker strikes with surprise`() {
        val e = settle(Stance.ATTACK, Stance.YIELD, aSees = true, bSees = false)
        assertEquals(Ending.FOUGHT, e.ending); assertTrue(e.surpriseA); assertTrue(!e.surpriseB); assertTrue(e.aStrikes)
        assertEquals(ENGAGE_SURPRISE, e.sa); assertEquals(1f, e.sb)
        // (One that sees the attack coming withdraws in time.)
        assertEquals(Ending.B_GAVE_WAY, settle(Stance.ATTACK, Stance.YIELD).ending)
    }

    @Test
    fun `a scout unseen watches and nothing comes of it, two that hide and miss each other pass unseen`() {
        assertEquals(Ending.SCOUTED, settle(Stance.HIDE_AND_SCOUT, Stance.YIELD, aSees = true, bSees = false).ending)
        assertEquals(Ending.PASSED_UNSEEN, settle(Stance.AMBUSH, Stance.AMBUSH, aSees = false, bSees = false).ending)
    }

    @Test
    fun `in a fight both sides take up, the one that sees first or the raider strikes`() {
        assertTrue(settle(Stance.DEFEND, Stance.DEFEND, sa = 2f, sb = 1f).aStrikes)
        assertTrue(!settle(Stance.DEFEND, Stance.DEFEND, sa = 1f, sb = 2f).aStrikes)
        assertTrue(settle(Stance.ATTACK, Stance.DEFEND, sa = 1f, sb = 2f, raid = true).aStrikes)
        assertTrue(!settle(Stance.YIELD, Stance.ATTACK, aSees = false, bSees = true).aStrikes)
    }

    @Test
    fun `the outcome is the same every time for the same inputs`() {
        for (a in Stance.entries) for (b in Stance.entries) for (sees in listOf(true to true, true to false, false to true, false to false)) {
            val x = settle(a, b, sees.first, sees.second, 1.2f, 0.9f); val y = settle(a, b, sees.first, sees.second, 1.2f, 0.9f)
            assertEquals(x.ending, y.ending); assertEquals(x.aStrikes, y.aStrikes); assertEquals(x.sa, y.sa)
        }
    }

    @Test
    fun `two that do not hide always see each other, and hiding gives the hider the first look`() {
        val r = Random(7)
        repeat(1000) { assertEquals(true to true, sightingsOf(1f, 1f, 1f, 1f, r)) }
        // (A hides, three times harder to see: it sees B first three times as often as B sees it first, and B often never sees it.)
        var aFirstUnseen = 0; var both = 0; val n = 20000
        repeat(n) { val (aSaw, bSaw) = sightingsOf(1f, 4f, 1f, 1f, r); if (aSaw && !bSaw) aFirstUnseen++; if (aSaw && bSaw) both++ }
        assertTrue(aFirstUnseen > n / 2, "the hider took the other unawares $aFirstUnseen of $n")
        // (Two that both hide well may pass unseen.)
        var none = 0
        repeat(n) { if (sightingsOf(1f, 4f, 1f, 4f, r) == (false to false)) none++ }
        assertTrue(none > n / 2, "both hiding passed unseen $none of $n")
    }
}
