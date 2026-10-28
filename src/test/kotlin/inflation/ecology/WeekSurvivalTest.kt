package inflation.ecology

import inflation.world.World
import inflation.world.WorldConfig
import kotlin.test.Test
import kotlin.test.assertTrue

/** The days a band holds first to keep its people alive ([survivalDays]). */
class WeekSurvivalTest {
    private val cfg = WorldConfig(seed = 5, widthKm = 300, heightKm = 300, erosionSteps = 10, plates = 6, oceanMarginKm = 40.0)

    @Test
    fun `survival days hold warmth within its asks and the week, and no food`() {
        val sim = HexSim(World.generate(cfg))
        repeat(8) { sim.step() }
        val bands = sim.groups.groups.filter { it.alive && it.band != null && it.adults > 0 }
        assertTrue(bands.isNotEmpty(), "no bands lived, so nothing was checked")
        val days = sim.groups.days.toFloat()
        with(sim.groups) {
            for (g in bands) {
                val b = g.band!!
                val hands = forageHands(g)
                val time = BandTime(days * hands)
                val food = days * hands * 0.6f; val fuel = fuelMost(g, hands)
                val first = survivalDays(g, b, sim.weather, time, hands, food, 0f, movedCamp(g, b))
                assertTrue(first[Activity.FOOD] == 0f, "food ${first[Activity.FOOD]}")
                assertTrue(first[Activity.FIREWOOD] in 0f..fuel * 1.0001f + 1e-4f, "firewood ${first[Activity.FIREWOOD]} of $fuel")
                val warmth = first.sum()
                assertTrue(warmth <= time.left * 1.0001f, "$warmth person-days of ${time.left}")
            }
        }
    }
}
