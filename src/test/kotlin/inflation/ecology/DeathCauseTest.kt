package inflation.ecology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class DeathCauseTest {
    @Test
    fun `labels are distinct and print as themselves`() {
        val all = DeathCause.entries.map { it.label } + Illness.entries.map { it.label }
        assertEquals(all.size, all.toSet().size)
        for (c in DeathCause.entries) assertEquals(c.label, "$c")
    }

    @Test
    fun `deaths to beasts print as they did, one of each`() {
        assertEquals("taken by lion", ByBeast.of(ByBeast.How.TAKEN_BY, Catalogue.LION).label)
        assertEquals("killed by wolf", ByBeast.of(ByBeast.How.KILLED_BY, Catalogue.WOLF).label)
        assertEquals("young killed by wolf", ByBeast.of(ByBeast.How.YOUNG_KILLED_BY, Catalogue.WOLF).label)
        assertSame(ByBeast.of(ByBeast.How.KILLED_BY, Catalogue.WOLF), ByBeast.of(ByBeast.How.KILLED_BY, Catalogue.WOLF))
    }

    @Test
    fun `the viewer files each cause under a fitting heading`() {
        assertEquals(PlaceRecord.Cause.HUNGER, DeathCause.HUNGER.shown)
        assertEquals(PlaceRecord.Cause.KILLED, ByBeast.of(ByBeast.How.TAKEN_BY, Catalogue.LION).shown)
        assertEquals(PlaceRecord.Cause.KILLED, DeathCause.RAIDS.shown)
        assertEquals(PlaceRecord.Cause.COLD, DeathCause.EXPOSURE.shown)
        for (i in Illness.entries) assertEquals(PlaceRecord.Cause.ILLNESS, i.shown)
    }

    @Test
    fun `game fights back by its defence and its weight, the same for every hunter`() {
        // (Gazelles do not fight back; a boar more than a red deer; an aurochs more than a boar; and the lighter the hunter the more.)
        assertEquals(0f, foughtBack(Catalogue.HUMAN, Catalogue.GAZELLE, Catalogue.GAZELLE.massKg))
        val deer = foughtBack(Catalogue.HUMAN, Catalogue.RED_DEER, Catalogue.RED_DEER.massKg)
        val boar = foughtBack(Catalogue.HUMAN, Catalogue.BOAR, Catalogue.BOAR.massKg)
        val aurochs = foughtBack(Catalogue.HUMAN, Catalogue.AUROCHS, Catalogue.AUROCHS.massKg)
        kotlin.test.assertTrue(deer > 0f && boar > deer && aurochs > boar, "deer $deer boar $boar aurochs $aurochs")
        kotlin.test.assertTrue(foughtBack(Catalogue.WOLF, Catalogue.ELK, Catalogue.ELK.massKg) > foughtBack(Catalogue.HUMAN, Catalogue.ELK, Catalogue.ELK.massKg))
        // (Beasts of prey defend themselves too: a lion is no easy kill.)
        kotlin.test.assertTrue(foughtBack(Catalogue.HUMAN, Catalogue.LION, Catalogue.LION.massKg) > boar)
        // (Its danger weighs on a party the less, the more of them share it.)
        kotlin.test.assertTrue(dangerDays(Catalogue.HUMAN, Catalogue.BOAR, Catalogue.BOAR.massKg, 2f) > dangerDays(Catalogue.HUMAN, Catalogue.BOAR, Catalogue.BOAR.massKg, 6f))
    }
}
