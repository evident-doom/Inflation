package inflation.world

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimulationTest {
    private val cfg = WorldConfig(seed = 42, widthKm = 300, heightKm = 300, erosionSteps = 10, plates = 6, oceanMarginKm = 40.0)
    private val world by lazy { World.generate(cfg) }
    private val stepsPerYear = Date.DAYS_PER_YEAR / cfg.daysPerStep

    private fun biggestRiver() = (0 until world.grid.size).filter { !world.isSea[it] && !world.water.isLake(it) }
        .maxBy { world.water.discharge[it] }

    @Test
    fun `rivers run at their long-term mean over several years`() {
        val sim = Simulation(world)
        val mouth = biggestRiver()
        var total = 0.0
        repeat(stepsPerYear * 3) { sim.step(); total += sim.water.discharge[mouth] }
        val ratio = total / (stepsPerYear * 3) / world.water.discharge[mouth]
        assertTrue(ratio in 0.6..1.6, "simulated / long-term mean flow is $ratio")
    }

    @Test
    fun `a dam stores water and evens out the flow below it`() {
        val g = world.grid
        // The biggest river site where a 40 m dam backs up a real reservoir (not a steep gorge).
        val candidates = (0 until g.size).filter {
            val r = world.water.drainage.receiver[it]
            world.isRiver(it) && world.water.discharge[it] > 1f && world.isRiver(r)
        }.sortedByDescending { world.water.discharge[it] }
        val probe = Simulation(world)
        val site = candidates.first { probe.water.addDam(it, 40f)?.capacityM3?.let { c -> c > 1e6f } == true }
        val below = world.water.drainage.receiver[site]
        fun flows(sim: Simulation) = List(stepsPerYear * 2) { sim.step(); sim.water.discharge[below].toDouble() }
        val natural = flows(Simulation(world))
        val dammed = Simulation(world)
        val dam = assertNotNull(dammed.water.addDam(site, 40f))
        var stored = 0f
        val regulated = List(stepsPerYear * 2) { dammed.step(); stored = maxOf(stored, dam.storage); dammed.water.discharge[below].toDouble() }
        fun spread(xs: List<Double>) = xs.max() - xs.min()
        // A dry-season reservoir may be drawn down by the end; it must have filled at some point.
        assertTrue(stored > 0f, "reservoir holds water")
        assertTrue(dammed.water.submerged.any { it }, "reservoir floods land")
        assertTrue(spread(regulated) < spread(natural), "flow range ${spread(regulated)} vs natural ${spread(natural)}")
    }

    @Test
    fun `canals run downhill and cannot loop`() {
        val sim = Simulation(world)
        val g = world.grid
        val river = (0 until g.size).filter { world.isRiver(it) && world.water.discharge[it] > 0.5f }.maxBy { world.elevation[it] }
        // Uphill: towards the highest neighbour.
        var up = river; g.forNeighbours(river) { j, _ -> if (world.elevation[j] > world.elevation[up]) up = j }
        assertNull(sim.water.addDiversion(river, intArrayOf(up), 0.5f))
        // Downhill to a neighbour that is not its own receiver.
        var target = -1
        g.forNeighbours(river) { j, _ ->
            if (j != world.water.drainage.receiver[river] && world.elevation[j] < world.elevation[river] && !world.isSea[j]) target = j
        }
        if (target >= 0) {
            assertNotNull(sim.water.addDiversion(river, intArrayOf(target), 0.5f))
            repeat(5) { sim.step() }
            assertTrue(sim.water.discharge[target] > 0f)
        }
    }

    @Test
    fun `cleared vegetation regrows, herbs first and trees slowly`() {
        val sim = Simulation(world)
        val v = sim.vegetation
        val g = world.grid
        val forest = (0 until g.size).filter { v.isVegetated(it) && v.tree[it] > 3f }
        assertTrue(forest.isNotEmpty(), "some forest exists")
        val cell = forest[forest.size / 2]
        val before = v.tree[cell]
        repeat(stepsPerYear / 2) { sim.step() }
        for (f in LifeForm.entries) v.consume(cell, f, Float.MAX_VALUE)
        repeat(stepsPerYear * 2) { sim.step() }
        assertTrue(v.herb[cell] > 0.05f, "herbs back after two years: ${v.herb[cell]}")
        assertTrue(v.tree[cell] < before * 0.5f, "trees still far below their former ${before}: ${v.tree[cell]}")
        assertTrue(v.tree[cell] > 0f, "trees re-establishing from nearby seed")
    }

    @Test
    fun `thick ash kills vegetation`() {
        val sim = Simulation(world)
        val v = sim.vegetation
        val cell = (0 until world.grid.size).first { v.isVegetated(it) && v.herb[it] > 0.1f }
        sim.eruptions.ash[cell] = 600f
        sim.step()
        assertTrue(v.herb[cell] < 0.05f, "herbs buried: ${v.herb[cell]}")
    }

    @Test
    fun `the climate has shocks over a century`() {
        val weather = Weather(world.grid, world.climate, cfg)
        repeat(stepsPerYear * 100) { weather.step() }
        val kinds = weather.events.map { it.kind }.toSet()
        assertTrue(ClimateEvent.Kind.COLD_WINTER in kinds && ClimateEvent.Kind.HEAT_WAVE in kinds, "events: $kinds")
    }
}
