package inflation.learning

import inflation.ecology.Catalogue
import inflation.ecology.HexFeatures
import inflation.ecology.HexSim
import inflation.ecology.WAYS_IN
import inflation.world.World
import inflation.world.WorldConfig
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PeopleMoverTest {
    @Test
    fun `every input of a band's view has a name`() {
        assertEquals(WAYS_IN, Schema.WAYS_IN.size)
        assertEquals(SharedMover.INPUTS + WAYS_IN, Schema.PEOPLE_IN.size)
    }

    @Test
    fun `a people's network saved before it read the band's view chooses as it did, whatever the band's view`() {
        val rnd = Random(5)
        val old = SharedMover(Mlp.random(intArrayOf(SharedMover.INPUTS, 8, 8, 1), rnd), FloatArray(Catalogue.all.size * SharedMover.IDENTITY) { rnd.nextFloat() })
        val dir = Files.createTempDirectory("mover").toFile()
        val f = java.io.File(dir, "people.mlp")
        Schema.save(old.net, f, Schema.MOVER_IN, Schema.MOVER_OUT)
        java.io.File(f.path + ".identity").writeText(old.identity.joinToString(" ") + "\n")
        val now = SharedMover.load(f)
        assertEquals(WAYS_IN, now.band)
        val ob = old.net.buffers(); val nb = now.net.buffers()
        repeat(20) {
            val feat = FloatArray(HexFeatures.SIZE) { rnd.nextFloat() }
            val state = FloatArray(WAYS_IN) { rnd.nextFloat() }
            old.fill(Catalogue.HUMAN, feat, null, ob.a[0]); now.fill(Catalogue.HUMAN, feat, state, nb.a[0])
            assertEquals(old.net.forward(ob), now.net.forward(nb), 1e-5f)
        }
        dir.deleteRecursively()
    }

    @Test
    fun `bands take stock of themselves each week`() {
        val sim = HexSim(World.generate(WorldConfig(seed = 3, widthKm = 300, heightKm = 300, erosionSteps = 10, plates = 6, oceanMarginKm = 40.0)))
        repeat(3) { sim.step() }
        val bands = sim.groups.groups.filter { it.alive && it.band != null }
        assertTrue(bands.isNotEmpty(), "no bands on the test world")
        for (g in bands) {
            val s = g.band!!.state
            assertEquals(WAYS_IN, s.size, "band #${g.id} has no view of itself")
            assertTrue(s.all { it.isFinite() }, "band #${g.id}: ${s.toList()}")
        }
    }
}
