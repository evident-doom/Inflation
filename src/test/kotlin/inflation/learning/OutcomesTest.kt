package inflation.learning

import inflation.ecology.Catalogue
import inflation.ecology.HexGroup
import kotlin.test.Test
import kotlin.test.assertEquals

class OutcomesTest {
    private val wolf = Catalogue.all.first { it.name == "wolf" }
    private fun pack(id: Int, adults: Int) = HexGroup(id, wolf, inflation.ecology.Place(0), adults, 0)

    @Test
    fun `a pack that takes another in is not credited with its animals, and the one that joined fares as the pack it joined`() {
        val o = Outcomes()
        val a = pack(1, 10); val b = pack(2, 5)
        o.record(0, listOf(a, b), emptyList())
        // Week 1: b joins a.
        a.adults += b.adults; b.mergedSize = b.size; b.mergedInto = a.id; b.adults = 0
        o.record(1, listOf(a), listOf(b))
        // Week 2: a loses 3 of its 15.
        a.adults -= 3
        o.record(2, listOf(a), emptyList())
        val ownA = o.own(1, 0, 2)
        assertEquals(10f, ownA[0], 1e-4f)
        assertEquals(10f * 12 / 15, ownA[1], 1e-4f)
        val ownB = o.own(2, 0, 2)
        assertEquals(5f, ownB[0], 1e-4f)
        assertEquals(5f * 12 / 15, ownB[1], 1e-4f)
    }

    @Test
    fun `births count, and an offshoot takes its share of the members with it`() {
        val o = Outcomes()
        val a = pack(1, 10)
        o.record(0, listOf(a), emptyList())
        // Week 1: 2 born.
        a.adults += 2
        o.record(1, listOf(a), emptyList())
        // Week 2: 4 split off as a new pack.
        a.adults -= 4
        val c = pack(3, 4).also { it.parent = a.id }
        o.record(2, listOf(a, c), emptyList())
        val own = o.own(1, 0, 2)
        assertEquals(12f, own[0], 1e-4f)
        assertEquals(12f, own[1], 1e-4f)
    }

    @Test
    fun `a band's luck in its own births and deaths is taken out`() {
        val o = Outcomes()
        val band = HexGroup(1, Catalogue.HUMAN, inflation.ecology.Place(0), 20, 0)
        o.record(0, listOf(band), emptyList())
        // Week 1: two more by luck alone.
        band.adults += 2; band.luck += 2f
        o.record(1, listOf(band), emptyList())
        assertEquals(20f, o.own(1, 0, 1)[0], 1e-4f)
    }
}
