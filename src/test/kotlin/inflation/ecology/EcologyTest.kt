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
    fun `gazelles and wolves coexist for a decade`() {
        // A larger island: a handful of packs on a small one dies out by chance, as it would.
        val sim = Simulation(World.generate(WorldConfig(seed = 7, widthKm = 400, heightKm = 400)))
        val e = sim.ecology
        val gazelles0 = e.count(Catalogue.GAZELLE)
        val wolves0 = e.count(Catalogue.WOLF)
        assertTrue(gazelles0 > 500 && wolves0 > 5, "start: $gazelles0 gazelles, $wolves0 wolves")
        var kills = 0
        val firstIds = e.groups.size
        val census = ArrayList<Pair<Int, Int>>()
        repeat(10) {
            repeat(stepsPerYear) { sim.step(); kills += e.kills }
            census += Pair(e.count(Catalogue.GAZELLE), e.count(Catalogue.WOLF))
        }
        val (g, w) = census.last()
        assertTrue(census.all { it.first > 0 && it.second > 0 }, "both persist: $census")
        assertTrue(g in gazelles0 / 5..gazelles0 * 10, "gazelles $gazelles0 -> $g")
        assertTrue(kills > 100, "wolves kill: $kills")
        assertTrue(e.groups.any { it.id >= firstIds }, "groups split as they grow")
        assertTrue(e.groups.all { it.size <= it.species.maxGroupSize }, "no group above its split size")
        assertTrue(w > 0)
    }

    @Test
    fun `grazing herds wear trails`() {
        val sim = Simulation(world)
        repeat(stepsPerYear * 2) { sim.step() }
        assertTrue(sim.vegetation.trails.count { it > 0.1f } > 20, "trails ${sim.vegetation.trails.count { it > 0.1f }}")
    }
}
