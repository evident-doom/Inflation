package inflation.ecology

import inflation.world.World
import inflation.world.WorldConfig
import kotlin.test.Test
import kotlin.test.assertTrue

/** The days a band gives first to keep its people alive ([survivalDays]). */
class WeekSurvivalTest {
    private val cfg = WorldConfig(seed = 5, widthKm = 300, heightKm = 300, erosionSteps = 10, plates = 6, oceanMarginKm = 40.0)

    @Test
    fun `survival days keep within the asks and the week, and a starving band forages first`() {
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
                val food = days * hands * 0.6f; val fuel = fuelAsk(g, hands)
                val first = survivalDays(g, b, sim.weather, time, hands, food, 0f, fuel, movedCamp(g, b))
                assertTrue(first[Activity.FOOD.ordinal] in 0f..food * 1.0001f, "food ${first[Activity.FOOD.ordinal]} of $food")
                assertTrue(first[Activity.FIREWOOD.ordinal] in 0f..fuel * 1.0001f + 1e-4f, "firewood ${first[Activity.FIREWOOD.ordinal]} of $fuel")
                assertTrue(first.sum() <= time.left * 1.0001f, "${first.sum()} person-days of ${time.left}")
            }
            // A band at the end of its fat and its body's reserve, with nothing in its larder, gives its days to food.
            val g = bands.first(); val b = g.band!!
            g.larder = 0f; g.fat = 0f; g.spent = Hazards.starveDays(g.species)
            val hands = forageHands(g)
            val food = days * hands * 0.6f
            val first = survivalDays(g, b, sim.weather, BandTime(days * hands), hands, food, 0f, fuelAsk(g, hands), movedCamp(g, b))
            assertTrue(first[Activity.FOOD.ordinal] > 0.5f * food, "a starving band gave food ${first[Activity.FOOD.ordinal]} of $food person-days")
        }
    }
}
