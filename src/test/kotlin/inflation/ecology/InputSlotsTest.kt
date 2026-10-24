package inflation.ecology

import inflation.world.World
import inflation.world.WorldConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import inflation.learning.Mlp
import inflation.learning.Schema
import kotlin.random.Random

/** Each group's view of itself fills every slot it names, and a band's ways at zero are the scaffold's. */
class InputSlotsTest {
    private val cfg = WorldConfig(seed = 5, widthKm = 200, heightKm = 200, erosionSteps = 10, plates = 6, oceanMarginKm = 40.0)

    @Test
    fun `every named slot of a band's view and a pack's is written`() {
        val sim = HexSim(World.generate(cfg))
        repeat(2) { sim.step() }
        val live = sim.groups.groups.filter { it.alive }
        val band = live.first { it.band != null }
        val into = FloatArray(WAYS_IN) { Float.NaN }
        sim.groups.bandInputs(band, sim.weather, into)
        assertEquals(emptyList(), into.indices.filter { into[it].isNaN() }.map { BAND_INPUT_NAMES[it] }, "band slots left unwritten")
        val pack = live.first { it.band == null }
        val a = FloatArray(ANIMAL_WAYS_IN) { Float.NaN }
        sim.groups.animalInputs(pack, sim.weather, a)
        assertEquals(emptyList(), a.indices.filter { a[it].isNaN() }.map { ANIMAL_INPUT_NAMES[it] }, "${pack.species.name} slots left unwritten")
    }

    @Test
    fun `a band's ways at zero are the scaffold's`() {
        val sim = HexSim(World.generate(cfg))
        sim.step()
        val g = sim.groups.groups.first { it.alive && it.band != null }
        val fresh = Band(1)
        sim.groups.setWays(g, sim.weather, { _, _, out -> out.fill(0f) }, FloatArray(WAYS_IN), FloatArray(WAYS_OUT))
        val b = g.band!!
        assertTrue(b.will.a.contentEquals(fresh.will.a), "ways")
        assertTrue(b.effort.contentEquals(fresh.effort), "effort")
        assertEquals(fresh.storeShare, b.storeShare)
        assertTrue(b.makeFirst.a.contentEquals(fresh.makeFirst.a), "make first")
        assertEquals(fresh.scoutShare, b.scoutShare); assertEquals(fresh.shunSick, b.shunSick)
    }

    @Test
    fun `the ecosystem slots are in range and a net trained without them ignores them`() {
        val sim = HexSim(World.generate(cfg))
        repeat(6) { sim.step() }
        val band = sim.groups.groups.first { it.alive && it.band != null }
        val x = BandInputs
        val into = FloatArray(WAYS_IN)
        sim.groups.bandInputs(band, sim.weather, into)
        val eco = (x.NEAR_RIVER until WAYS_IN)
        for (i in eco) assertTrue(into[i] in -1f..1f, "${BAND_INPUT_NAMES[i]} = ${into[i]}")
        assertEquals(BAND_INPUT_NAMES.size, BAND_INPUT_NAMES.toSet().size, "names are unique")
        // A net saved with the names before the ecosystem slots chooses the same whatever they hold.
        val oldNames = BAND_INPUT_NAMES.subList(0, x.NEAR_RIVER)
        val old = Mlp.random(intArrayOf(oldNames.size, 6, 3), Random(4))
        val outs = listOf("a", "b", "c")
        val fitted = Schema.fit(old, oldNames, outs, BAND_INPUT_NAMES, outs)
        fun run(net: Mlp, v: FloatArray): FloatArray { val b = net.buffers(); v.copyInto(b.a[0]); return net.forwardAll(b).copyOf() }
        val before = run(old, into.copyOf(oldNames.size))
        assertTrue(before.contentEquals(run(fitted, into)), "old net unchanged")
        val shouted = into.copyOf().also { for (i in eco) it[i] = 1f }
        assertTrue(before.contentEquals(run(fitted, shouted)), "old net blind to the ecosystem")
    }
}
