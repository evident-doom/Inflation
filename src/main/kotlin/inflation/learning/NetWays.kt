package inflation.learning

import inflation.ecology.AnimalWays
import inflation.ecology.BandWays
import inflation.ecology.HexGroup
import inflation.ecology.WAYS_IN

/** Sets bands' ways with a network: inputs as [bandInputs] then the band's plan ([waysInput]), outputs as [setWays]. */
class NetWays(private val net: Mlp) : BandWays {
    // Not saved (a world saved with these ways comes back with none), so made again on first use.
    @Transient private var buffers: ThreadLocal<Mlp.Buffers>? = null
    override fun choose(g: HexGroup, inputs: FloatArray, out: FloatArray) {
        val b = (buffers ?: ThreadLocal.withInitial { net.buffers() }.also { buffers = it }).get()
        waysInput(inputs, g.plan, b.a[0])
        net.forwardAll(b).copyInto(out)
    }
}

/**
 * A bands' ways network's input, into [into] (its whole first layer): the band's view of itself [inputs] ([WAYS_IN]), then how
 * its mover saw the place it chose this week ([HexGroup.plan]) for as many units as the network reads, 0 where there is none
 * (moves hand-written) or it does not fit.
 */
fun waysInput(inputs: FloatArray, plan: FloatArray?, into: FloatArray) {
    inputs.copyInto(into, 0, 0, WAYS_IN)
    val n = into.size - WAYS_IN
    if (plan != null && plan.size == n) plan.copyInto(into, WAYS_IN) else into.fill(0f, WAYS_IN, into.size)
}

/** Sets packs' ways with a network: inputs as [inflation.ecology.animalInputs], outputs as [inflation.ecology.AnimalWay]. */
class NetAnimalWays(private val net: Mlp) : AnimalWays {
    private val buffers = ThreadLocal.withInitial { net.buffers() }
    override fun choose(g: HexGroup, inputs: FloatArray, out: FloatArray) {
        val b = buffers.get()
        inputs.copyInto(b.a[0])
        net.forwardAll(b).copyInto(out)
    }
}
