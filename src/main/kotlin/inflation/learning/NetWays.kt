package inflation.learning

import inflation.ecology.AnimalWays
import inflation.ecology.BandWays
import inflation.ecology.HexGroup

/** Sets bands' ways with a network: inputs as [bandInputs], outputs as [setWays]. */
class NetWays(private val net: Mlp) : BandWays {
    // Not saved (a world saved with these ways comes back with none), so made again on first use.
    @Transient private var buffers: ThreadLocal<Mlp.Buffers>? = null
    override fun choose(g: HexGroup, inputs: FloatArray, out: FloatArray) {
        val b = (buffers ?: ThreadLocal.withInitial { net.buffers() }.also { buffers = it }).get()
        inputs.copyInto(b.a[0])
        net.forwardAll(b).copyInto(out)
    }
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
