package inflation.ecology

import inflation.Tuning
import inflation.world.World
import inflation.world.WorldConfig
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HexHurricaneTest {
    private val cfg = WorldConfig(seed = 3, widthKm = 300, heightKm = 300, erosionSteps = 10, plates = 6, oceanMarginKm = 40.0)
    private val world by lazy { World.generate(cfg) }

    private fun sim(settings: Map<String, String>) = HexSim(world, tuning = Tuning(read = { settings[it] }))

    private fun saved(sim: HexSim): ByteArray {
        val f = File.createTempFile("hexsim", ".hexs")
        try { HexSnapshot.save(sim, f); return GZIPInputStream(f.inputStream()).use { it.readBytes() } } finally { f.delete() }
    }

    @Test
    fun `storms that tell no one and kill no one change nothing`() {
        val off = sim(emptyMap()); val ghost = sim(mapOf("t.hurricane" to "2", "t.hurricaneKills" to "0", "t.hurricaneSeen" to "0"))
        repeat(20) { off.step(); ghost.step() }
        assertTrue(ghost.groups.hurricanes != null, "the storms were set down")
        assertTrue(saved(off).contentEquals(saved(ghost)), "a blind, harmless storm moved the world")
    }

    @Test
    fun `a storm keeps to land, moves no faster than its speed and its queue is never revised`() {
        val s = sim(mapOf("t.hurricane" to "1", "t.hurricaneKills" to "0", "t.hurricaneWarn" to "4"))
        val seen = ArrayList<Int>()
        repeat(12) {
            s.step()
            val storms = s.groups.hurricanes!!
            val at = storms.now().single()
            assertTrue(s.hex.heart[at.i] >= 0, "the storm is over the sea")
            seen += at.i
        }
        for (k in 1 until seen.size) assertTrue(s.hex.distanceKm(inflation.world.Hex(seen[k - 1]), inflation.world.Hex(seen[k])) <= s.hex.hexKm * 1.01, "the storm went more than a hex in a week")
        val again = sim(mapOf("t.hurricane" to "1", "t.hurricaneKills" to "0", "t.hurricaneWarn" to "4"))
        repeat(12) { again.step() }
        assertEquals(s.groups.hurricanes!!.now(), again.groups.hurricanes!!.now(), "the same seed gave another path")
    }

    @Test
    fun `recruiters set down stay where they are at speed 0, and the nearest of them is one of them`() {
        val s = sim(mapOf("t.recruiters" to "3", "t.recruiterSpeed" to "0"))
        s.step()
        val first = s.groups.recruiters!!.now()
        repeat(10) { s.step() }
        val sites = s.groups.recruiters!!
        assertEquals(first, sites.now(), "a static recruiter moved")
        assertTrue(sites.nearest(first[0]) == first[0], "the nearest recruiter to a recruiter's own hex is that hex")
        assertTrue(s.groups.hurricanes == null, "no storms were asked for")
    }
}
