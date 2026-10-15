package inflation.ecology

import kotlin.test.Test
import kotlin.test.assertTrue

class CatalogueTest {
    @Test
    fun `people and every beast of prey move by a learned mover whatever they eat`() {
        assertTrue(Catalogue.all.filter { it.human }.all { it in Catalogue.learned })
        assertTrue(Catalogue.predators.all { it in Catalogue.learned })
        assertTrue(Catalogue.migratory.all { it in Catalogue.learned })
    }
}
