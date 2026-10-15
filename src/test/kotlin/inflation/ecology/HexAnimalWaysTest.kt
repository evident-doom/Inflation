package inflation.ecology

import inflation.world.World
import inflation.world.WorldConfig
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertTrue

class HexAnimalWaysTest {
    private val cfg = WorldConfig(seed = 3, widthKm = 300, heightKm = 300, erosionSteps = 10, plates = 6, oceanMarginKm = 40.0)

    private fun stateAfter(world: World, steps: Int, ways: AnimalWays?): ByteArray {
        val sim = HexSim(world)
        sim.groups.herdWays = ways; sim.groups.hunterWays = ways
        repeat(steps) { sim.step() }
        val f = File.createTempFile("hexsim", ".hexs")
        try {
            HexSnapshot.save(sim, f)
            return GZIPInputStream(f.inputStream()).use { it.readBytes() }
        } finally { f.delete() }
    }

    @Test
    fun `herds and packs whose ways are all 0 do as the hand-written ways do`() {
        val world = World.generate(cfg)
        val a = stateAfter(world, 30, null)
        val b = stateAfter(world, 30, AnimalWays { _, _, out -> out.fill(0f) })
        assertTrue(a.contentEquals(b), "a run with ways of 0 differs from one without")
    }
}
