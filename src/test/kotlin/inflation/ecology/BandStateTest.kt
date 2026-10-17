package inflation.ecology

import inflation.world.World
import inflation.world.WorldConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A year of bands (their week in [bandWeek], foraging in [forage], their rites, ties and descent) leaves each group's
 * state within its bounds ([violations]).
 */
class BandStateTest {
    private val cfg = WorldConfig(seed = 5, widthKm = 300, heightKm = 300, erosionSteps = 10, plates = 6, oceanMarginKm = 40.0)

    @Test
    fun `a year leaves every band's state within its bounds`() {
        val sim = HexSim(World.generate(cfg))
        repeat(52) { sim.step() }
        val bands = sim.groups.groups.filter { it.alive && it.band != null }
        assertTrue(bands.isNotEmpty(), "no bands lived through the year, so nothing was checked")
        assertEquals(emptyList(), sim.groups.groups.filter { it.alive }.flatMap { it.violations() })
    }
}
