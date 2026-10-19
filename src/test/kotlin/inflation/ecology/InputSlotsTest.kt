package inflation.ecology

import inflation.Config
import inflation.Tuning
import inflation.world.World
import inflation.world.WorldConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Each group's view of itself fills every slot it names, and a band's ways at zero are the scaffold's. */
class InputSlotsTest {
    private val cfg = WorldConfig(seed = 5, widthKm = 200, heightKm = 200, erosionSteps = 10, plates = 6, oceanMarginKm = 40.0)

    @Test
    fun `every named slot of a band's view and a herd's or pack's is written`() {
        // (With plant-eater herds, so that there is one to read.)
        val sim = HexSim(World.generate(cfg), tuning = Tuning { if (it == "t.plantEaterHerds") "true" else Config.string(it) })
        repeat(2) { sim.step() }
        val live = sim.groups.groups.filter { it.alive }
        val band = live.first { it.band != null }
        val into = FloatArray(WAYS_IN) { Float.NaN }
        sim.groups.bandInputs(band, sim.weather, into)
        assertEquals(emptyList(), into.indices.filter { into[it].isNaN() }.map { BAND_INPUT_NAMES[it] }, "band slots left unwritten")
        for (g in listOf(live.first { it.band == null && !it.species.carnivore }, live.first { it.band == null && it.species.carnivore })) {
            val a = FloatArray(ANIMAL_WAYS_IN) { Float.NaN }
            sim.groups.animalInputs(g, sim.weather, a)
            assertEquals(emptyList(), a.indices.filter { a[it].isNaN() }.map { ANIMAL_INPUT_NAMES[it] }, "${g.species.name} slots left unwritten")
        }
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
        assertEquals(fresh.storeAim, b.storeAim)
        assertTrue(b.makeFirst.a.contentEquals(fresh.makeFirst.a), "make first")
        assertEquals(fresh.scoutShare, b.scoutShare); assertEquals(fresh.shunSick, b.shunSick)
    }
}
