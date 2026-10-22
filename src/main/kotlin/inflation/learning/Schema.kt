package inflation.learning

import inflation.ecology.HexFeatures
import java.io.File
import inflation.ecology.AnimalWay
import inflation.ecology.Band
import inflation.ecology.Temperament

/**
 * The version of a role's networks, kept apart from the model's ([inflation.ecology.HexSnapshot.MODEL_MAJOR]).
 * Raise [major] by hand ([Role.net]) when the role's networks change structure (inputs, outputs, what an output means):
 * the training then takes the first network trained under the new major as the role's baseline and keeps it whatever
 * it scores against the earlier version ([EcoCheck]). [minor] is for changes that leave the networks' meaning alone;
 * the training treats them as before.
 */
data class NetVersion(val major: Int, val minor: Int) {
    companion object { val NONE = NetVersion(0, 0) }
}

/**
 * What each input and output of a learned network means, by name, so that networks trained on an
 * older model keep working as features and choices are added (see docs/design/model-compatibility.md).
 *
 * A network is saved with a `.names` file beside it: its input names on the first line, its output
 * names on the second, separated by `|`. Loaded into a model whose inputs and outputs differ, it is
 * fitted by name: an input it never saw gets zero weights (it ignores it until trained), an input
 * that no longer exists is dropped, and an output it never had gets zero weights and bias. Every
 * addition must therefore be neutral at zero: a new feature reads 0 where there is nothing new, and
 * a new choice takes its hand-written default at an output of 0 (as the bands' ways do).
 */
object Schema {
    /** The place features a group sees of a candidate place ([HexFeatures.NAMES]). */
    val PLACE get() = HexFeatures.NAMES
    /** A kind's traits, then its learned identity (see [SharedMover.kindInputs]). */
    val KIND = listOf("mass", "speed", "daily travel", "group size", "grass", "browse", "fruit and roots", "meat", "small prey", "carrion", "migratory", "carnivore", "stalking", "foresight")
        .also { require(it.size == SharedMover.KIND) }

    val MOVER_IN: List<String> get() = PLACE.map { "place:$it" } + KIND.map { "kind:$it" } + (0 until SharedMover.IDENTITY).map { "identity:$it" } + TEMPER.map { "temper:$it" }
    /** A group's inherited temperament ([Temperament]). */
    val TEMPER: List<String> get() = Temperament.NAMES
    val MOVER_OUT = listOf("score")
    /** People's mover: the mover's inputs, then the band's view of itself and of the bands about it ([WAYS_IN], from [Band.state]). */
    val PEOPLE_IN: List<String> get() = MOVER_IN + WAYS_IN.map { "band:$it" }

    /** A band's view of itself ([inflation.ecology.BAND_INPUT_NAMES]) and its ways ([inflation.ecology.WAYS_OUT_NAMES]). */
    val WAYS_IN: List<String> get() = inflation.ecology.BAND_INPUT_NAMES
    val WAYS_OUT: List<String> get() = inflation.ecology.WAYS_OUT_NAMES

    /** A pack's view of itself ([inflation.ecology.ANIMAL_INPUT_NAMES]) and its ways ([AnimalWay]). */
    val ANIMAL_WAYS_IN: List<String> get() = inflation.ecology.ANIMAL_INPUT_NAMES
    val HUNTER_WAYS_OUT get() = AnimalWay.HUNTER_NAMES

    fun namesFile(f: File) = File(f.path + ".names")

    /**
     * Saves [net] with the names of its inputs and outputs and, if given, the net [version] it was trained under
     * (third line; see [NetVersion]).
     */
    fun save(net: Mlp, file: File, inputs: List<String>, outputs: List<String>, version: NetVersion? = null) {
        require(inputs.size == net.sizes.first() && outputs.size == net.sizes.last()) { "names do not fit the network ${net.sizes.toList()}" }
        net.save(file)
        namesFile(file).writeText(inputs.joinToString("|") + "\n" + outputs.joinToString("|") + "\n" + (version?.let { "net ${it.major}.${it.minor}\n" } ?: ""))
    }

    /** The net version [file] was saved with; [NetVersion.NONE] when it carries none (saved before nets had versions, or not a versioned net). */
    fun versionOf(file: File): NetVersion {
        val line = namesFile(file).takeIf { it.exists() }?.readLines()?.getOrNull(2) ?: return NetVersion.NONE
        val parts = line.removePrefix("net ").split(".")
        return NetVersion(parts.getOrNull(0)?.toIntOrNull() ?: return NetVersion.NONE, parts.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    /**
     * Loads the network in [file] fitted to [inputs] and [outputs]. One saved without names is taken
     * to have the first of today's names (features and choices have been added at the end), except
     * that a mover's place features come before its kind's part ([legacyMover]).
     */
    fun load(file: File, inputs: List<String>, outputs: List<String>, legacyMover: Boolean = false): Mlp {
        val net = Mlp.load(file.readText())
        val nf = namesFile(file)
        val (savedIn, savedOut) = if (nf.exists()) nf.readLines().let { it[0].split("|") to it[1].split("|") } else {
            val nIn = net.sizes.first(); val nOut = net.sizes.last()
            val ins = if (legacyMover && nIn < inputs.size) {
                // Legacy movers end with the kind's part; today's inputs carry the temperament after it.
                val tail = SharedMover.KIND + SharedMover.IDENTITY
                val kindAt = PLACE.size
                inputs.take(nIn - tail) + inputs.subList(kindAt, kindAt + tail)
            } else inputs.take(nIn)
            ins to outputs.take(nOut)
        }
        return fit(net, savedIn, savedOut, inputs, outputs)
    }

    /** [net], trained on [savedIn] and [savedOut], refitted to [inputs] and [outputs] by name. */
    fun fit(net: Mlp, savedIn: List<String>, savedOut: List<String>, inputs: List<String>, outputs: List<String>): Mlp {
        if (savedIn == inputs && savedOut == outputs) return net
        require(net.sizes.size >= 3) { "a network needs a hidden layer to be refitted" }
        val sizes = net.sizes.copyOf().also { it[0] = inputs.size; it[it.size - 1] = outputs.size }
        val m = Mlp(sizes)
        val h = net.sizes[1]; val top = net.sizes.size - 1
        // First layer: each hidden unit's weight on each input, by the input's name; then its bias.
        val inAt = inputs.map { savedIn.indexOf(it) }
        for (j in 0 until h) {
            for ((i, k) in inAt.withIndex()) if (k >= 0) m.w[m.at(1, i, j)] = net.w[net.at(1, k, j)]
            m.w[m.biasAt(1, j)] = net.w[net.biasAt(1, j)]
        }
        // The hidden layers between are as they were.
        for (l in 2 until top) net.w.copyInto(m.w, m.at(l, 0, 0), net.at(l, 0, 0), net.biasAt(l, net.sizes[l]))
        // Last layer: each output's weights and bias, by the output's name (a new one stays zero).
        val last = net.sizes[top - 1]
        for ((o, k) in outputs.map { savedOut.indexOf(it) }.withIndex()) if (k >= 0) {
            for (j in 0 until last) m.w[m.at(top, j, o)] = net.w[net.at(top, j, k)]
            m.w[m.biasAt(top, o)] = net.w[net.biasAt(top, k)]
        }
        return m
    }
}
