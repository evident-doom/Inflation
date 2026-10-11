package inflation.ecology

import inflation.world.Date
import inflation.world.Simulation
import inflation.world.World
import inflation.world.WorldConfig
import kotlin.test.Test
import kotlin.test.assertTrue

class EcologyTest {
    private val cfg = WorldConfig(seed = 42, widthKm = 300, heightKm = 300, erosionSteps = 10, plates = 6, oceanMarginKm = 40.0)
    private val world by lazy { World.generate(cfg) }
    private val stepsPerYear = Date.DAYS_PER_YEAR / cfg.daysPerStep

    @Test
    fun `plankton blooms in spring and is richer on coasts than in open sea`() {
        val sim = Simulation(world)
        val p = sim.ecology.plankton
        val coast = (0 until world.grid.size).filter { world.isSea[it] && p.supply[it] > 0.5f }
        val open = (0 until world.grid.size).filter { world.isSea[it] && p.supply[it] < 0.2f }
        assertTrue(coast.isNotEmpty() && open.isNotEmpty())
        repeat(stepsPerYear) { sim.step() } // settle
        val coastMean = DoubleArray(4); val openMean = DoubleArray(4)
        repeat(stepsPerYear) {
            sim.step()
            val season = sim.date.season.ordinal
            coastMean[season] += coast.sumOf { p.phyto[it].toDouble() } / coast.size
            openMean[season] += open.sumOf { p.phyto[it].toDouble() } / open.size
        }
        assertTrue(coastMean.sum() > 2 * openMean.sum(), "coast ${coastMean.sum()} vs open ${openMean.sum()}")
        assertTrue(p.phyto.all { it.isFinite() && it < 100f }, "phytoplankton stays bounded")
        val (spring, summer) = Pair(coastMean[0], coastMean[1])
        assertTrue(spring > summer, "spring $spring vs summer $summer")
    }

    @Test
    fun `soil life keeps litter bounded and feeds fertility back to plants`() {
        val sim = Simulation(world)
        repeat(stepsPerYear * 3) { sim.step() }
        val s = sim.ecology.soil
        val land = (0 until world.grid.size).filter { sim.vegetation.isVegetated(it) }
        assertTrue(land.all { s.litter[it] in 0f..20f }, "litter max ${land.maxOf { s.litter[it] }}")
        assertTrue(land.all { sim.vegetation.organicFertility[it] in 0f..0.3f })
        assertTrue(land.count { sim.vegetation.organicFertility[it] > 0.05f } > land.size / 4, "organic matter enriches much of the land")
    }

    @Test
    fun `large herbivores and wolves coexist for a decade`() {
        // A larger island: a handful of packs on a small one dies out by chance, as it would.
        val sim = Simulation(World.generate(WorldConfig(seed = 7, widthKm = 400, heightKm = 400)))
        val e = sim.ecology
        val start = Catalogue.all.associateWith { e.count(it) }
        assertTrue(start.getValue(Catalogue.GAZELLE) > 500 && start.getValue(Catalogue.WOLF) > 5, "start: $start")
        var kills = 0
        val firstIds = e.groups.size
        val census = ArrayList<Map<Species, Int>>()
        repeat(10) {
            repeat(stepsPerYear) { sim.step(); kills += e.kills }
            census += Catalogue.all.associateWith { e.count(it) }
        }
        for (s in Catalogue.all) assertTrue(census.all { it.getValue(s) > 0 }, "${s.name} persists: ${census.map { it.getValue(s) }}")
        assertTrue(kills > 100, "wolves kill: $kills")
        assertTrue(e.groups.any { it.id >= firstIds }, "groups split as they grow")
        assertTrue(e.groups.all { it.size <= it.species.maxGroupSize }, "no group above its split size")
    }

    @Test
    fun `grazing herds wear trails`() {
        val sim = Simulation(world)
        repeat(stepsPerYear * 2) { sim.step() }
        assertTrue(sim.vegetation.trails.count { it > 0.1f } > 20, "trails ${sim.vegetation.trails.count { it > 0.1f }}")
    }
}
