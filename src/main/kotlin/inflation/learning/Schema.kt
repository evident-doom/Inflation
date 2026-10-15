package inflation.learning

import inflation.ecology.FOOD_TYPES
import inflation.ecology.HexFeatures
import inflation.ecology.Skill
import inflation.ecology.Store
import inflation.ecology.Tool
import java.io.File

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
    /** The place features a group sees of a candidate place ([HexFeatures], in order). */
    val PLACE = listOf(
        "food", "climate suits", "crowding", "hunters", "discomfort", "discomfort forecast", "snow over limit", "snow",
        "climb", "effort", "stays", "same hex", "toward bearing", "season's place", "fed", "fat", "season sin", "season cos",
        "restless", "water distance", "days dry", "pull", "moon", "to births sin", "to births cos",
        "remembered", "remembered same season", "remembered fed", "remembered water", "remembered danger", "remembered age",
        "ancestors", "appointment", "houses", "own size", "lasting water", "sickness",
        "trees", "shrubs", "grass", "marsh", "water against usual", "paths", "herds about", "dung", "grazing and kills", "known ground", "beyond own ground", "others' marks",
    ).also { require(it.size == HexFeatures.SIZE) { "Schema.PLACE has ${it.size} names, HexFeatures.SIZE is ${HexFeatures.SIZE}" } }
    /** A kind's traits, then its learned identity (see [SharedMover.kindInputs]). */
    val KIND = listOf("mass", "speed", "daily travel", "group size", "grass", "browse", "fruit and roots", "meat", "small prey", "carrion", "migratory", "carnivore", "stalking", "foresight")
        .also { require(it.size == SharedMover.KIND) }

    val MOVER_IN: List<String> get() = PLACE.map { "place:$it" } + KIND.map { "kind:$it" } + (0 until SharedMover.IDENTITY).map { "identity:$it" } + TEMPER.map { "temper:$it" }
    /** A group's inherited temperament ([inflation.ecology.Temperament]). */
    val TEMPER: List<String> get() = inflation.ecology.Temperament.NAMES
    val MOVER_OUT = listOf("score")
    /** People's mover: the mover's inputs, then the band's view of itself and of the bands about it ([WAYS_IN], from [inflation.ecology.Band.state]). */
    val PEOPLE_IN: List<String> get() = MOVER_IN + WAYS_IN.map { "band:$it" }
    /** Per-kind networks (teachers): the place features only. */
    val TEACHER_IN: List<String> get() = PLACE.map { "place:$it" }

    /** A band's view of itself ([inflation.ecology.bandInputs], in order). */
    val WAYS_IN: List<String> get() = listOf("year sin", "year cos", "fed", "larder") + Store.entries.map { "store:${it.name.lowercase()}" } +
        listOf("adults", "young per adult") + Skill.FORAGING.map { "skill:${it.name.lowercase()}" } + Tool.entries.map { "tool:${it.name.lowercase()}" } +
        listOf("temperature", "camp weeks", "spare days") + inflation.ecology.Illness.entries.map { "sick:${it.name.lowercase()}" } +
        listOf("wounded", "lamed", "elders", "taken by beasts", "beasts about", "friendly bands about", "strange bands about", "hostile bands about",
            "sickness of bands about", "raided lately", "set on a raid", "inbreeding") +
        inflation.ecology.Tradition.entries.map { "custom:${it.name.lowercase()}" } + TEMPER.map { "temper:$it" }
    /** A band's ways ([inflation.ecology.setWays], in order). */
    val WAYS_OUT: List<String> get() = (0 until FOOD_TYPES).map { "effort:$it" } + listOf("store aim") + Tool.entries.map { "make first:${it.name.lowercase()}" } +
        listOf("scouts", "houses", "raids", "palisades", "sowing", "gathering range", "watching children", "night fire", "shunning the sick", "exploring",
            "giving", "taking from caches", "caching", "drying", "keeping young animals", "shelters", "feasting", "appointments", "burning off")

    /** A herd's or pack's view of itself ([inflation.ecology.animalInputs], in order). */
    val ANIMAL_WAYS_IN: List<String> get() = (listOf("year sin", "year cos", "fed", "fat", "days dry", "own size", "young per adult", "wary", "holds ground", "weeks hungry", "temperature",
        "mass", "diet:grass", "diet:browse", "diet:fruit and roots", "diet:meat", "diet:small prey", "diet:carrion") + TEMPER.map { "temper:$it" })
        .also { require(it.size == inflation.ecology.ANIMAL_WAYS_IN) }
    /** A herd's ways and a pack's ([inflation.ecology.AnimalWay], in order). */
    val HERD_WAYS_OUT = listOf("herd size", "grass", "browse", "fruit and roots", "fattening", "births earlier or later")
        .also { require(it.size == inflation.ecology.ANIMAL_WAYS_OUT) }
    val HUNTER_WAYS_OUT = listOf("chasing", "stealing kills", "killing other hunters", "fighting rivals", "seeking a mate", "settling")
        .also { require(it.size == inflation.ecology.ANIMAL_WAYS_OUT) }

    fun namesFile(f: File) = File(f.path + ".names")

    /** Saves [net] with the names of its inputs and outputs. */
    fun save(net: Mlp, file: File, inputs: List<String>, outputs: List<String>) {
        require(inputs.size == net.sizes.first() && outputs.size == net.sizes.last()) { "names do not fit the network ${net.sizes.toList()}" }
        net.save(file)
        namesFile(file).writeText(inputs.joinToString("|") + "\n" + outputs.joinToString("|") + "\n")
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
        val oldIn = net.sizes[0]; val h = net.sizes[1]
        // First layer: each hidden unit's weight on each input, by the input's name; then its bias.
        val inAt = inputs.map { savedIn.indexOf(it) }
        for (j in 0 until h) for ((i, k) in inAt.withIndex()) if (k >= 0) m.w[j * inputs.size + i] = net.w[j * oldIn + k]
        for (j in 0 until h) m.w[inputs.size * h + j] = net.w[oldIn * h + j]
        // The hidden layers between are as they were.
        var offOld = oldIn * h + h; var offNew = inputs.size * h + h
        for (l in 2 until net.sizes.size - 1) {
            val n = net.sizes[l - 1] * net.sizes[l] + net.sizes[l]
            net.w.copyInto(m.w, offNew, offOld, offOld + n); offOld += n; offNew += n
        }
        // Last layer: each output's row and bias, by the output's name (a new one stays zero).
        val last = net.sizes[net.sizes.size - 2]; val oldOut = net.sizes.last()
        for ((o, k) in outputs.map { savedOut.indexOf(it) }.withIndex()) if (k >= 0) {
            for (j in 0 until last) m.w[offNew + o * last + j] = net.w[offOld + k * last + j]
            m.w[offNew + outputs.size * last + o] = net.w[offOld + oldOut * last + k]
        }
        return m
    }
}
