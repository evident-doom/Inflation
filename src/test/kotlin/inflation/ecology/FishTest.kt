package inflation.ecology

import inflation.world.Date
import inflation.world.RiverClass
import inflation.world.Simulation
import inflation.world.World
import inflation.world.WorldConfig
import kotlin.test.Test
import kotlin.test.assertTrue

class FishTest {
    private val cfg = WorldConfig(seed = 42, widthKm = 300, heightKm = 300, erosionSteps = 10, plates = 6, oceanMarginKm = 40.0)
    private val world by lazy { World.generate(cfg) }
    private val stepsPerYear = Date.DAYS_PER_YEAR / cfg.daysPerStep
    private val rivers by lazy { (0 until world.grid.size).filter { world.isRiver(it) && world.water.discharge[it] >= RiverClass.STREAM.minDischarge } }

    private fun toRunPeak(sim: Simulation) { while (sim.date.dayOfYear != Fish.RUN_START_DAY + Fish.RUN_DAYS - 6) sim.step() }

    @Test
    fun `seas, rivers and coasts hold fish and shellfish, coasts richer than open sea`() {
        val sim = Simulation(world)
        repeat(stepsPerYear * 2) { sim.step() }
        val f = sim.ecology.fish
        val p = sim.ecology.plankton
        val shelf = (0 until world.grid.size).filter { world.isSea[it] && p.supply[it] > 0.5f }
        val open = (0 until world.grid.size).filter { world.isSea[it] && p.supply[it] < 0.2f }
        val shelfMean = shelf.map { f.fish[it] }.average(); val openMean = open.map { f.fish[it] }.average()
        println("FISH shelf $shelfMean open $openMean rivers ${rivers.map { f.fish[it] }.average()} shellfish ${(0 until world.grid.size).map { f.shellfish[it] }.filter { it > 0 }.average()}")
        assertTrue(shelfMean > 1.5 * openMean, "shelf $shelfMean vs open $openMean")
        assertTrue(rivers.count { f.fish[it] > 1f } > rivers.size / 2, "most rivers have fish")
        assertTrue(f.shellfish.any { it > 1000f }, "shellfish beds on shallow coasts")
        assertTrue(f.fish.all { it.isFinite() && it >= 0f })
    }

    @Test
    fun `a spring run goes up rivers, and a dam stops it`() {
        val sim = Simulation(world)
        toRunPeak(sim)
        val f = sim.ecology.fish
        val reached = rivers.filter { f.run[it] > 0f }
        println("FISH run reaches ${reached.size} of ${rivers.size} river cells, total ${rivers.sumOf { f.run[it].toDouble() }} kg/km²")
        // Rivers that reach the sea at all (not into closed basins).
        val seaward = rivers.filter { c -> var i = c; var sea = false; while (true) { val r = world.water.drainage.receiver[i]; if (r == i) break; if (world.isSea[r]) { sea = true; break }; i = r }; sea }
        assertTrue(reached.size > seaward.size / 4, "run reaches ${reached.size} of ${seaward.size} seaward river cells")
        // Dam the reach with the most run fish that has run fish upstream of it.
        val g = world.grid
        val site = reached.filter { c -> reached.any { world.water.drainage.receiver[it] == c } && world.isRiver(world.water.drainage.receiver[c]) }
            .maxBy { world.water.discharge[it] }
        val dammed = Simulation(world)
        dammed.water.addDam(site, 20f)
        toRunPeak(dammed)
        val above = reached.filter { c -> var i = c; var hit = false; while (true) { if (i == site) { hit = true; break }; val r = world.water.drainage.receiver[i]; if (r == i) break; i = r }; hit }
        assertTrue(above.isNotEmpty())
        assertTrue(above.all { dammed.ecology.fish.run[it] == 0f }, "no run above the dam at (${g.x(site)}, ${g.y(site)})")
        // Outside the season the run is gone.
        repeat(stepsPerYear / 2) { sim.step() }
        assertTrue(rivers.all { f.run[it] < 1f })
    }
}
