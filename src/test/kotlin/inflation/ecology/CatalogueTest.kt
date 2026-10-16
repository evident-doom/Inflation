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
}
