package inflation.ecology

import kotlin.test.Test
import kotlin.test.assertTrue

class CatalogueTest {
    @Test
    fun `people and every beast of prey that moves as a pack move by a learned mover whatever they eat`() {
        assertTrue(Catalogue.all.filter { it.human }.all { it in Catalogue.learned })
        assertTrue(Catalogue.predators.filter { it !in Catalogue.residentHunters }.all { it in Catalogue.learned })
        assertTrue(Catalogue.residentHunters.none { it in Catalogue.learned })
        assertTrue(Catalogue.migratory.all { it in Catalogue.learned })
    }

    @Test
    fun `every kind sits at its fixed id`() {
        for ((i, s) in Catalogue.all.withIndex()) assertTrue(s.id == i && s.index == i, "${s.name}: id ${s.id}, index ${s.index}, at $i")
        // (The ids the saved worlds and networks were made with.)
        assertTrue(Catalogue.GAZELLE.id == 0 && Catalogue.WOLF.id == 18 && Catalogue.HUMAN.id == 24 && Catalogue.ARGALI.id == 26)
    }

    @Test
    fun `every kind's record adds up`() {
        for (s in Catalogue.all) {
            val d = s.diet
            val diet = d.grass + d.browse + d.roots + d.fruit + d.meat + d.smallPrey + d.carrion
            assertTrue(kotlin.math.abs(diet - 1f) < 1e-3f, "${s.name}: diet shares sum to $diet")
            s.activity?.let { assertTrue(kotlin.math.abs(it.sum() - 1f) < 1e-3f, "${s.name}: activity sums to ${it.sum()}") }
            // (A carcass is less than the animal: guts, blood and the rest are not in it.)
            val p = s.products
            val carcass = p.meat + p.fat + p.hide + p.bone + p.sinew + p.hornOrAntler
            assertTrue(carcass < 0.9f * s.massKg, "${s.name}: products of $carcass kg from a ${s.massKg} kg animal")
        }
    }
}
