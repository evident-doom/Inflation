package inflation.ecology

import inflation.world.World
import inflation.world.WorldConfig
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every table of water or matter in the land's parts, the residents, their food and the small life is a stock the books
 * audit and the reports read ([HexSim.stocks]); every other table is named in [NOT_STOCKS] with why it holds none. A new
 * table of matter fails here until it is listed one way or the other.
 */
class StocksTest {
    private val cfg = WorldConfig(seed = 3, widthKm = 200, heightKm = 200, erosionSteps = 10, plates = 6, oceanMarginKm = 40.0)

    @Test
    fun `every table of the land and the animals is a stock or is named as no stock`() {
        val sim = HexSim(World.generate(cfg))
        val listed = IdentityHashMap<Any, Boolean>()
        for (s in sim.stocks().list) for (a in s.arrays) listed[a] = true
        val parts = listOf(sim.land.hydro, sim.land.drink, sim.land.plants, sim.land.fire, sim.fauna, sim.fauna.food, sim.fauna.small)
        val unlisted = ArrayList<String>()
        for (part in parts) {
            var c: Class<*>? = part.javaClass
            while (c != null && c != Any::class.java) {
                for (f in c.declaredFields) {
                    if (Modifier.isStatic(f.modifiers)) continue
                    f.isAccessible = true
                    val v = f.get(part) ?: continue
                    val tables = when (v) {
                        is FloatArray, is DoubleArray -> listOf(v)
                        is Array<*> -> if (v.isNotEmpty() && v.all { it is FloatArray || it is DoubleArray }) v.toList() else continue
                        else -> continue
                    }
                    val name = "${part.javaClass.simpleName}.${f.name}"
                    if (tables.any { it !in listed } && name !in NOT_STOCKS) unlisted += name
                }
                c = c.superclass
            }
        }
        assertTrue(unlisted.isEmpty(), "tables neither in HexSim.stocks() nor in NOT_STOCKS:\n" + unlisted.joinToString("\n"))
    }

    @Test
    fun `the reports' wider pools add up their stocks`() {
        val sim = HexSim(World.generate(cfg))
        val stocks = sim.stocks()
        val m = stocks.matter()
        for (pool in listOf("plants", "small animals", "resident animals", "herds, packs and bands")) {
            val parts = stocks.list.filter { it.group == pool }
            assertTrue(parts.isNotEmpty(), "no stocks in $pool")
            assertEquals(parts.sumOf { it.sum() } / 1e6, m.getValue(pool), 1e-9 * (1 + m.getValue(pool)))
        }
    }

    companion object {
        /** Tables that hold no water or matter of their own, by why. */
        val NOT_STOCKS = listOf(
            // Fixed facts of the place: capacities, shares, distances and settled levels.
            "HexHydrology.meanFlow", "HexHydrology.soilCap", "HexHydrology.intake", "HexHydrology.aquiferCap", "HexHydrology.aquiferM",
            "HexHydrology.drain", "HexHydrology.lakeM2", "HexHydrology.lakeFull", "HexHydrology.lakeHold", "HexHydrology.usualGround",
            "HexDrinkingWater.nearRiver", "HexDrinkingWater.nearLake", "HexDrinkingWater.nearSpring", "HexDrinkingWater.waterKmMean",
            "HexDrinkingWater.nearBed", "HexDrinkingWater.bedM2", "HexDrinkingWater.streamKm", "HexPlants.annualGdd", "HexPlants.warmth",
            "HexPlants.fertility", "HexPlants.organicFertility", "HexPlants.warmest", "HexPlants.perennialShare", "HexFood.cerealGround",
            "HexFood.cereal", "HexSmallLife.eagleCrags", "HexSmallLife.rockKopje", "HexSmallLife.rockChukar", "HexSmallLife.rockHides", "HexSmallLife.wetland",
            "HexSmallLife.waterfowl", "HexSmallLife.meanTemp", "HexSmallLife.annualRain", "HexSmallLife.soilReach",
            // Rates and the week's passing water: flows over a step, not water held.
            "HexHydrology.lakeRain", "HexHydrology.lakeEvap", "HexHydrology.flow", "HexHydrology.flood", "HexHydrology.soak",
            "HexHydrology.soakTarget", "HexHydrology.runoffMm", "HexPlants.litterfall", "HexPlants.browsing", "HexPlants.grazing",
            "HexPlants.rooting", "HexPlants.trampling", "HexSmallLife.washedKg", "HexSmallLife.laidKgM2", "HexSmallLife.leaving",
            "HexSmallLife.newborn", "HexFauna.bornThisWeek", "HexFauna.groupKilled",
            // Shares, states and memories, 0..1 or days or degrees.
            "HexDrinkingWater.water", "HexDrinkingWater.waterKm", "HexDrinkingWater.dug", "HexDrinkingWater.snowWater", "HexDrinkingWater.iced", "HexPlants.gdd", "HexPlants.moisture",
            "HexPlants.shrubShoots", "HexPlants.treeShoots", "HexPlants.animalSeed", "HexPlants.green", "HexPlants.hayFresh", "HexPlants.grassAge",
            "HexPlants.growFrom", "HexPlants.growSpan", "HexPlants.greenOpen", "HexPlants.greenSeep", "HexPlants.greenWet",
            "HexPlants.greenShade", "HexPlants.trails", "HexPlants.treeSeed", "HexPlants.shrubSeed", "HexFires.burnt", "HexFires.burning",
            "HexFauna.waterC", "HexFauna.fed", "HexFauna.protein", "HexFauna.fear", "HexFauna.usual", "HexFauna.outbreak",
            "HexFauna.fedByMonth", "HexFauna.reserve", "HexFauna.autumnFed", "HexFauna.dry", "HexFauna.arrive", "HexFood.domestic", "HexSmallLife.pollination",
            "HexSmallLife.insectFrom", "HexSmallLife.harass", "HexSmallLife.spreadWeight", "HexSmallLife.returning",
            // For the check and the viewer: heads held hungry with no better place next door, and one kind's spill last week.
            "HexFauna.stuck", "HexFauna.flowEast", "HexFauna.flowSouth", "HexFauna.flowOut",
            // Ash is mm of rock, not living or dead matter; the grass that fell to the litter is a memory of the stand (the
            // litter holds its mass).
            "HexPlants.ash", "HexPlants.hayFallen",
            // Part of a stock already counted, or a sum of stocks: the nests are part of their kinds; birds sum [bird];
            // the week's carcasses pass to the carrion within the step (booked as a flow each way); what is left to
            // graze or browse is part of the plants.
            "HexSmallLife.young", "HexSmallLife.birds", "HexFauna.carcassKg", "HexFauna.grazeLeft", "HexFauna.browseLeft",
            // Counted as heads, not mass (a boundary choice): eagles.
            "HexSmallLife.eagles",
            // Ledgers for checks.
            "HexFauna.huntedByResidents", "HexFood.ripenedNow", "HexSmallLife.grubsDug", "HexSmallLife.herpLedger", "HexSmallLife.herpTakenBy",
            "HexSmallLife.tortLedger", "HexSmallLife.tortTakenBy",
        ).toSet()
    }
}
