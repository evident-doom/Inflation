package inflation.learning

import inflation.world.Place
import inflation.world.get
import inflation.world.set

import inflation.ecology.Catalogue
import inflation.ecology.DeathClass
import inflation.ecology.HexGroup
import inflation.ecology.LOST_ALL
import kotlin.test.Test
import kotlin.test.assertEquals

class OutcomesTest {
    private val wolf = Catalogue.all.first { it.name == "wolf" }
    private fun pack(id: Int, adults: Int) = HexGroup(id, wolf, inflation.world.Place(0), adults, 0)

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
        val band = HexGroup(1, Catalogue.HUMAN, inflation.world.Place(0), 20, 0)
        o.record(0, listOf(band), emptyList())
        // Week 1: two more by luck alone.
        band.adults += 2; band.luck += 2f
        o.record(1, listOf(band), emptyList())
        assertEquals(20f, o.own(1, 0, 1)[0], 1e-4f)
    }

    @Test
    fun `members are foreseen as the groups they are in foresee`() {
        val o = Outcomes()
        val band = HexGroup(1, Catalogue.HUMAN, inflation.world.Place(0), 20, 0).also { it.foreseen = 0.9f }
        o.record(0, listOf(band), emptyList())
        // Week 1: 10 split off into a band foreseeing worse.
        band.adults -= 10; band.foreseen = 0.8f
        val off = HexGroup(2, Catalogue.HUMAN, inflation.world.Place(0), 10, 0).also { it.parent = band.id; it.foreseen = 0.5f }
        o.record(1, listOf(band, off), emptyList())
        val fore = FloatArray(1)
        assertEquals(20f, o.own(1, 0, 1, fore)[0], 1e-4f)
        assertEquals(10f * 0.8f + 10f * 0.5f, fore[0], 1e-4f)
        assertEquals(0.9f, o.foreseen(1, 0), 1e-6f)
    }

    @Test
    fun `the deaths the odds made likely are kept by class for the members, a week's whole to the band that held them all`() {
        val o = Outcomes()
        val band = HexGroup(1, Catalogue.HUMAN, inflation.world.Place(0), 20, 0)
        o.record(0, listOf(band), emptyList())
        // Week 1: 2 expected to die of the cold, 1 of hunger, 4 in all.
        band.addLost(DeathClass.KIT.ordinal, 2f); band.addLost(DeathClass.FOOD.ordinal, 1f); band.addLost(LOST_ALL, 4f)
        o.record(1, listOf(band), emptyList())
        // Week 2: 5 more of the cold; 10 members leave as a new band that week.
        band.addLost(DeathClass.KIT.ordinal, 5f); band.addLost(LOST_ALL, 5f)
        band.adults -= 10
        val off = HexGroup(2, Catalogue.HUMAN, inflation.world.Place(0), 10, 0).also { it.parent = band.id }
        o.record(2, listOf(band, off), emptyList())
        val lost = FloatArray(6)
        val own = o.own(1, 0, 2, null, lost)
        assertEquals(20f, own[0], 1e-4f)
        assertEquals(2f, lost[DeathClass.KIT.ordinal], 1e-4f)
        assertEquals(1f, lost[DeathClass.FOOD.ordinal], 1e-4f)
        assertEquals(4f, lost[LOST_ALL], 1e-4f)
        // (In week 2 the band's 20 members, all there at week 1, own the whole band's expected deaths of the week.)
        assertEquals(5f, lost[3 + DeathClass.KIT.ordinal], 1e-4f)
        assertEquals(0f, lost[3 + DeathClass.FOOD.ordinal], 1e-4f)
    }
}
