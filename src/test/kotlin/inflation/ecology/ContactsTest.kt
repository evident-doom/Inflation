package inflation.ecology

import inflation.world.Hex
import inflation.world.HexArray
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The contacts between groups whose grounds overlap ([contactsOf]): found once a pair, the same whichever way round and in
 * whatever order the groups come, the same run after run on all cores, and with the encounters the same however a hex is
 * cut. And the strategies they are settled by ([Strategy]).
 */
class ContactsTest {
    private fun sphere(vararg hs: Pair<Int, Float>) = Sphere(HexArray(IntArray(hs.size) { hs[it].first }), FloatArray(hs.size) { hs[it].second })

    /** Contacts as (lower id, higher id) to (overlap, mean, hex, contested), for comparing. */
    private fun run(spheres: List<Sphere?>, ids: List<Int>, held: List<Boolean>, area: (Int) -> Float = { 100f }, contend: (Int, Int) -> Boolean = { _, _ -> true }) =
        contactsOf(spheres.toTypedArray(), ids.toIntArray(), held.toBooleanArray(), 16, 6f, contend) { _, _, h -> 1f / area(h.i) }
            .associate { c -> (minOf(ids[c.a], ids[c.b]) to maxOf(ids[c.a], ids[c.b])) to listOf(c.overlap, c.mean, c.hex.i, c.contested.toList().map { it.i }) }

    @Test
    fun `a pair is one contact over all the hexes it shares, the same whichever way round`() {
        val a = sphere(1 to 0.5f, 2 to 0.3f, 3 to 0.2f); val b = sphere(2 to 0.6f, 3 to 0.4f)
        val one = run(listOf(a, b), listOf(10, 20), listOf(true, true))
        val other = run(listOf(b, a), listOf(20, 10), listOf(true, true))
        assertEquals(1, one.size)
        assertEquals(one, other)
        val (overlap, mean, hex, contested) = one.getValue(10 to 20)
        assertTrue(abs(overlap as Float - (0.3f * 0.6f + 0.2f * 0.4f)) < 1e-6f)
        assertTrue(abs(mean as Float - 6f * (0.3f * 0.6f + 0.2f * 0.4f) / 100f) < 1e-6f)
        assertEquals(2, hex)
        assertEquals(listOf(2, 3), contested)
    }

    @Test
    fun `the groups' order does not change the contacts`() {
        val s = listOf(sphere(1 to 0.5f, 2 to 0.5f), sphere(2 to 0.5f, 3 to 0.5f), sphere(3 to 1f), sphere(1 to 0.3f, 2 to 0.3f, 3 to 0.4f))
        val ids = listOf(7, 3, 9, 5); val held = listOf(true, true, false, false)
        val base = run(s, ids, held)
        val order = listOf(2, 0, 3, 1)
        assertEquals(base, run(order.map { s[it] }, order.map { ids[it] }, order.map { held[it] }))
    }

    @Test
    fun `two groups on their way have no contact, and one on its way finds the holder`() {
        val c = run(listOf(sphere(4 to 1f), sphere(4 to 0.5f, 5 to 0.5f), sphere(4 to 0.2f, 6 to 0.8f)), listOf(1, 2, 3), listOf(false, false, true))
        assertEquals(setOf(1 to 3, 2 to 3), c.keys)
    }

    @Test
    fun `cutting a hex in two leaves the encounters as they were`() {
        // (Hex 1 of 100 km² cut into hexes 1 and 2 of 50 each, each group's share halved between them.)
        val whole = run(listOf(sphere(1 to 0.6f, 3 to 0.4f), sphere(1 to 0.5f, 4 to 0.5f)), listOf(1, 2), listOf(true, true))
        val cut = run(listOf(sphere(1 to 0.3f, 2 to 0.3f, 3 to 0.4f), sphere(1 to 0.25f, 2 to 0.25f, 4 to 0.5f)), listOf(1, 2), listOf(true, true),
            area = { if (it == 1 || it == 2) 50f else 100f })
        assertTrue(abs(whole.getValue(1 to 2)[1] as Float - cut.getValue(1 to 2)[1] as Float) < 1e-6f)
    }

    @Test
    fun `the same contacts every run`() {
        val r = java.util.Random(5)
        val spheres = List(300) { sphere(*Array(5) { r.nextInt(16) to r.nextFloat() / 5f }) }
        val ids = List(300) { it * 7 % 300 }; val held = List(300) { it % 3 != 0 }
        val first = run(spheres, ids, held)
        repeat(3) { assertEquals(first, run(spheres, ids, held)) }
    }

    @Test
    fun `pairs that do not contend have no contact`() {
        assertTrue(run(listOf(sphere(1 to 1f), sphere(1 to 1f)), listOf(1, 2), listOf(true, true), contend = { _, _ -> false }).isEmpty())
    }

    @Test
    fun `a strategy is read part by part, the animals' rule where it says nothing`() {
        val s = Strategy.parse("lair:DEFEND, fringe:yield, outside:AMBUSH")!!
        assertEquals(Stance.DEFEND, s.at(Ground.LAIR)); assertEquals(Stance.GIVE_WAY_TO_STRONGER, s.at(Ground.CORE))
        assertEquals(Stance.YIELD, s.at(Ground.FRINGE)); assertEquals(Stance.AMBUSH, s.at(Ground.OUTSIDE))
        assertNull(Strategy.parse(null)); assertTrue(Strategy.ANIMALS.plain); assertTrue(!s.plain)
    }

    @Test
    fun `a pack's ground is its lair, core and fringe by its footprint's weights`() {
        val g = HexGroup(1, Catalogue.all.first { !it.human && it.lifeway == Lifeway.PACK }, inflation.world.Place(0), 2, 0)
        g.home = Hex(5); g.footprint = HexArray(intArrayOf(5, 6, 7)); g.footWeights = floatArrayOf(1f, 0.6f, 0.3f)
        assertEquals(Ground.LAIR, groundOf(g, Hex(5))); assertEquals(Ground.CORE, groundOf(g, Hex(6)))
        assertEquals(Ground.FRINGE, groundOf(g, Hex(7))); assertEquals(Ground.OUTSIDE, groundOf(g, Hex(8)))
    }
}
