package inflation.ecology

import inflation.world.World
import inflation.world.WorldConfig
import java.io.File
import java.util.concurrent.ForkJoinPool
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertTrue

class HexSimDeterminismTest {
    private val cfg = WorldConfig(seed = 3, widthKm = 300, heightKm = 300, erosionSteps = 10, plates = 6, oceanMarginKm = 40.0)

    /** The state after [steps] weeks, as saved, run on [cores] cores (0: all). */
    private fun stateAfter(world: World, steps: Int, cores: Int): ByteArray {
        val run = {
            val sim = HexSim(world)
            repeat(steps) { sim.step() }
            val f = File.createTempFile("hexsim", ".hexs")
            try {
                HexSnapshot.save(sim, f)
                GZIPInputStream(f.inputStream()).use { it.readBytes() }
            } finally { f.delete() }
        }
        return if (cores == 0) run() else ForkJoinPool(cores).submit(run).get()
    }

    @Test
    fun `a seed gives the same run on any number of cores`() {
        val world = World.generate(cfg)
        val steps = 60
        val a = stateAfter(world, steps, 0)
        val b = stateAfter(world, steps, 0)
        val c = stateAfter(world, steps, 1)
        fun firstDiff(x: ByteArray, y: ByteArray) = (0 until minOf(x.size, y.size)).firstOrNull { x[it] != y[it] }
            ?: if (x.size == y.size) -1 else minOf(x.size, y.size)
        assertTrue(firstDiff(a, b) < 0, "two runs on all cores differ from byte ${firstDiff(a, b)} of ${a.size}")
        assertTrue(firstDiff(a, c) < 0, "a run on one core differs from one on all cores from byte ${firstDiff(a, c)} of ${a.size}")
    }
}
