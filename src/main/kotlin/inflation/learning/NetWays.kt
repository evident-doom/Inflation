package inflation.learning

import inflation.ecology.AnimalWays
import inflation.ecology.BandWays
import inflation.ecology.HexFeatures
import inflation.ecology.HexGroup
import inflation.ecology.WAYS_IN

/** Sets bands' ways with a network: inputs as [bandInputs] then its camp's own terms ([waysInput]), outputs as [setWays]. */
class NetWays(private val net: Mlp) : BandWays {
    // Not saved (a world saved with these ways comes back with none), so made again on first use.
    @Transient private var buffers: ThreadLocal<Mlp.Buffers>? = null
    override val parallel get() = true
    override fun choose(g: HexGroup, inputs: FloatArray, out: FloatArray) {
        val b = (buffers ?: ThreadLocal.withInitial { net.buffers() }.also { buffers = it }).get()
        waysInput(inputs, g.plan, b.a[0])
        net.forwardAll(b).copyInto(out)
    }
}

/**
 * A bands' ways network's input, into [into] (its whole first layer): the band's view of itself [inputs] ([WAYS_IN]), then its
 * camp's own terms this week ([HexGroup.plan], [CampTerms]), 0 where there are none (no planner saw the camp) or they do not fit.
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

/**
 * The camp's own terms, which the bands' ways network reads after the band's view of itself: the place features ([HexFeatures])
 * of the place the band camps on this week, as the planner saw it, but those that say only how a place stands against the one
 * the band is on (the climb and effort of the walk, staying, the same hex, the bearings it keeps or heads toward, the house it
 * would leave) and the step's length. (Until people's net major 11 the ways read the people's mover's last hidden layer at the
 * place chosen instead, and trained the mover through it; with bands holding their hex the mover chose only between a hex's low
 * and high ground, so it is retired: docs/design/band-movement-removal.md, Phase 6.)
 */
object CampTerms {
    private val apart = setOf(HexFeatures.CLIMB, HexFeatures.EFFORT, HexFeatures.STAYS, HexFeatures.SAME_HEX, HexFeatures.TOWARD_BEARING,
        HexFeatures.KEEPS_HEADING, HexFeatures.TOWARD_LACKED, HexFeatures.TOWARD_RAID, HexFeatures.TOWARD_SALT, HexFeatures.HOUSE_LEFT, HexFeatures.STEP_LENGTH)
    /** The place features read, in order. */
    val slots: IntArray = (0 until HexFeatures.size).filter { it !in apart }.toIntArray()
    /** Their names as the ways network's inputs. */
    val names: List<String> get() = slots.map { "camp:" + HexFeatures.names[it] }
    val size get() = slots.size

    /** The camp's terms from the place features [features] of the place it camps on. */
    fun of(features: FloatArray) = FloatArray(slots.size) { features.getOrElse(slots[it]) { 0f } }
}
