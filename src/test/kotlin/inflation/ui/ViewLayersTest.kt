package inflation.ui

import inflation.ecology.HexSim
import inflation.ecology.HexSnapshot
import inflation.ecology.PlaceRecord
import inflation.world.World
import inflation.world.WorldConfig
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ViewLayersTest {
    private val cfg = WorldConfig(seed = 3, widthKm = 300, heightKm = 300, erosionSteps = 10, plates = 6, oceanMarginKm = 40.0)
    private val world by lazy { World.generate(cfg) }

    private fun state(sim: HexSim): ByteArray {
        val f = File.createTempFile("hexsim", ".hexs")
        try { HexSnapshot.save(sim, f); return GZIPInputStream(f.inputStream()).use { it.readBytes() } } finally { f.delete() }
    }

    @Test
    fun `watching the layers changes nothing in the run`() {
        val steps = 20
        val a = HexSim(world).also { s -> repeat(steps) { s.step() } }
        val b = HexSim(world).also { s -> val l = ViewLayers(s); repeat(steps) { s.step(); l.week() } }
        val x = state(a); val y = state(b)
        assertEquals(x.size, y.size, "saved states differ in size")
        assertTrue(x.contentEquals(y), "the run differs when watched")
    }

    @Test
    fun `a year and more gives swings, peaks and the year before`() {
        val sim = HexSim(world)
        val l = ViewLayers(sim)
        val weeks = 70 // (six-day weeks, sixty a year: the same month twice)
        repeat(weeks) { sim.step(); l.week() }
        val key = l.key(ViewLayers.Measure.TEMPERATURE, 0)
        assertEquals(12, l.monthsKept(key))
        val sw = assertNotNull(l.swing(key, true))
        assertTrue(sw.filter { !it.isNaN() }.average() > 1.0, "temperature swings over the year")
        assertNotNull(l.peak(ViewLayers.Measure.FORAGE.name))
        assertNotNull(l.yearAgo(ViewLayers.Measure.FORAGE.name, false))
        // Things died somewhere, and carrion went.
        val all = l.key(ViewLayers.Measure.DEATHS, PlaceRecord.Cause.entries.size)
        assertTrue(l.now.getValue(all).any { !it.isNaN() && it > 0f }, "some animals died")
        assertTrue(l.now.getValue(ViewLayers.Measure.CARRION_WEEKS.name).any { !it.isNaN() && it > 0f && it < ViewLayers.MAX_WEEKS },
            "carrion clears somewhere")
        for (m in ViewLayers.Measure.entries) if (m.tints) assertNotNull(l.now[l.key(m, 0)], "${m.name} worked out")
        println("weeks to clear carrion (median of hexes with any): " +
            l.now.getValue(ViewLayers.Measure.CARRION_WEEKS.name).filter { !it.isNaN() && it > 0f }.sorted().let { it[it.size / 2] })
        println("hunters per 100 kg prey (median): " +
            l.now.getValue(ViewLayers.Measure.HUNTERS_PER_PREY.name).filter { !it.isNaN() && it > 0f }.sorted()
                .let { if (it.isEmpty()) 0f else it[it.size / 2] })
        println("kills kg/km2/wk (max): " + l.now.getValue(ViewLayers.Measure.KILLS.name).filter { !it.isNaN() }.maxOrNull())
        println("forage swing (median): " + l.swing(ViewLayers.Measure.FORAGE.name, false)!!.filter { !it.isNaN() }.sorted().let { it[it.size / 2] })
        println("herbs swing (median): " + l.swing(ViewLayers.Measure.HERBS.name, false)!!.filter { !it.isNaN() }.sorted().let { it[it.size / 2] })
        println("small game swing (median): " + l.swing(ViewLayers.Measure.SMALL_GAME.name, false)!!.filter { !it.isNaN() }.sorted().let { it[it.size / 2] })
    }
}
