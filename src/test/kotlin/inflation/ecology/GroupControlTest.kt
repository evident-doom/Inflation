package inflation.ecology

import inflation.world.World
import inflation.world.WorldConfig
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertTrue

class GroupControlTest {
    // (Without plateaus: on this small map one turns the first band's ground into a spot with no choice of moves.)
    private val cfg = WorldConfig(seed = 3, widthKm = 300, heightKm = 300, erosionSteps = 10, plates = 6, oceanMarginKm = 40.0, plateauKm = 0.0)
    private val world by lazy { World.generate(cfg) }

    private fun state(sim: HexSim): ByteArray {
        val f = File.createTempFile("hexsim", ".hexs")
        try { HexSnapshot.save(sim, f); return GZIPInputStream(f.inputStream()).use { it.readBytes() } } finally { f.delete() }
    }

    /** A control that always takes the model's choices, and counts what it was asked. */
    private class ModelsWay : GroupControl {
        var moves = 0; var ways = 0
        override fun move(d: MoveDecision): Int { moves++; return d.model }
        override fun ways(g: HexGroup, out: FloatArray) { ways++ }
    }

    @Test
    fun `following under a control that takes the model's choices changes nothing`() {
        val steps = 30
        fun run(control: GroupControl?): ByteArray {
            val sim = HexSim(world)
            val band = sim.groups.groups.first { it.band != null }
            sim.groups.watched = band.id; sim.groups.control = control
            repeat(steps) { sim.step() }
            return state(sim)
        }
        val ctl = ModelsWay()
        val a = run(null); val b = run(ctl)
        assertTrue(ctl.moves > 0 && ctl.ways > 0, "the control was asked (${ctl.moves} moves, ${ctl.ways} ways)")
        val diff = (0 until minOf(a.size, b.size)).firstOrNull { a[it] != b[it] } ?: if (a.size == b.size) -1 else minOf(a.size, b.size)
        assertTrue(diff < 0, "the run differs from byte $diff of ${a.size}")
    }

    @Test
    fun `the player's pick and pinned ways are what the band does`() {
        val sim = HexSim(world)
        val band = sim.groups.groups.first { it.band != null }
        sim.groups.watched = band.id
        var asked: MoveDecision? = null; var chosen = -1
        sim.groups.control = object : GroupControl {
            override fun move(d: MoveDecision): Int {
                asked = d
                // The candidate farthest from the model's that is not its own place.
                chosen = d.places.indices.filter { it != d.model }.maxByOrNull { sim.hex.distanceKm(sim.hw.placeHex[d.places[it]], sim.hw.placeHex[d.places[d.model]]) } ?: d.model
                return chosen
            }
            // Twice the hand-written store aim.
            override fun ways(g: HexGroup, out: FloatArray) { out[FOOD_TYPES] = 10f }
        }
        sim.step()
        val d = asked!!
        assertTrue(d.places.size > 1, "it had a choice")
        // (It sets off on the way chosen; a meeting on the way may stop it short.)
        val way = d.paths[chosen]
        assertTrue(band.path.isNotEmpty() && way.copyOf(band.path.size).contentEquals(band.path), "path ${band.path.toList()} along ${way.toList()}")
        assertTrue(band.band!!.storeAim > 1.9f * STORE_AIM_DAYS, "store aim ${band.band!!.storeAim}")
    }

    @Test
    fun `a herd can be steered too`() {
        val sim = HexSim(world)
        val herd = sim.groups.groups.first { !it.species.carnivore }
        sim.groups.watched = herd.id
        var places = IntArray(0); var chosen = -1; var way = IntArray(0)
        sim.groups.control = object : GroupControl {
            override fun move(d: MoveDecision): Int { places = d.places; chosen = d.places.indices.firstOrNull { it != d.model } ?: d.model; way = d.paths[chosen]; return chosen }
            override fun ways(g: HexGroup, out: FloatArray) { out[AnimalWay.FATTEN] = -10f }
        }
        sim.step()
        assertTrue(places.size > 1, "it had a choice")
        // (It sets off on the way chosen; a meeting on the way may stop it short.)
        assertTrue(herd.path.isNotEmpty() && way.copyOf(herd.path.size).contentEquals(herd.path), "path ${herd.path.toList()} along ${way.toList()}")
        assertTrue(herd.will[AnimalWay.FATTEN] < 0.01f)
    }
}
