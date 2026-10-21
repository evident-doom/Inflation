package inflation.tools.fauna

import inflation.ecology.BandWays
import inflation.ecology.HexSim
import inflation.ecology.WAYS_OUT
import inflation.tools.forcedWayOutput

/**
 * waysForce=WAY:VALUE,...: holds those of the bands' ways at those values for every band every week, whatever the network sets: WAY
 * is a way's number from 0, in the order of the network's outputs ([inflation.learning.Schema.WAYS_OUT]), and VALUE its multiple of
 * the scaffold's choice, between 0 and 2 (1 the scaffold's; 0.5 and 1.5 are the network outputs -1.10 and +1.10). The other ways are
 * the network's. Without a ways network the others are the scaffold's. Not set, the bands' ways are not touched.
 */
class WaysForce(sim: HexSim, spec: String) {
    init {
        val held = spec.split(',').map { part ->
            val (way, value) = part.split(':').map { it.trim() }.also { require(it.size == 2) { "waysForce=$spec: each is WAY:VALUE, e.g. 7:1.5" } }
            val j = way.toInt()
            require(j in 0 until WAYS_OUT) { "waysForce=$spec: the ways are numbered 0..${WAYS_OUT - 1}" }
            j to forcedWayOutput(value.toFloat())
        }
        val network = sim.groups.ways
        sim.groups.ways = BandWays { g, inputs, out ->
            network?.choose(g, inputs, out)
            for ((j, v) in held) out[j] = v
        }
    }
}
