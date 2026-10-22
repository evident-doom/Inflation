package inflation.learning

import inflation.Config

import inflation.ecology.HexGroups
import java.io.File

/**
 * The ways a learned network can set for the week, one entry each: bands' ways ([inflation.ecology.BandWays]) and
 * packs' ([inflation.ecology.AnimalWays]). Each names its file in a networks directory, its config key, the
 * inputs and outputs its network is saved with, and where a run takes it; the programs that load, train, check or
 * keep ways go through this list instead of spelling them out.
 */
enum class WaysKind(
    /** As `--role` names it ([HexEvolveWays]). */
    val label: String,
    /** Its network's file in a networks directory. */
    val file: String,
    /** The config key that gives its network's file instead ([LearnedModel]). */
    val configKey: Config.Key,
    /** Whose ways, as the log says it. */
    val whose: String,
) {
    BANDS("bands", "ways.mlp", Config.Key.WAYS, "bands'"),
    HUNTERS("hunters", "hunter-ways.mlp", Config.Key.HUNTER_WAYS, "packs'");

    /** The role whose networks it goes with. */
    val role get() = if (this == BANDS) Role.PEOPLE else Role.HUNTERS

    /** The names of its network's inputs. */
    val inNames: List<String> get() = if (this == BANDS) Schema.WAYS_IN else Schema.ANIMAL_WAYS_IN
    /** The names of its network's outputs. */
    val outNames: List<String> get() = if (this == BANDS) Schema.WAYS_OUT else Schema.HUNTER_WAYS_OUT

    /** Its network in [f], fitted to today's inputs and outputs by name ([Schema.load]). */
    fun load(f: File): Mlp = Schema.load(f, inNames, outNames)

    /** Saves [net] to [f] with its inputs' and outputs' names. */
    fun save(net: Mlp, f: File) = Schema.save(net, f, inNames, outNames, role.net)

    /** Sets [groups]' ways of this kind by [net]. */
    fun set(groups: HexGroups, net: Mlp) {
        when (this) {
            BANDS -> groups.ways = NetWays(net)
            HUNTERS -> groups.hunterWays = NetAnimalWays(net)
        }
    }

    companion object {
        /** The kind `--role` names, or null. */
        fun named(label: String): WaysKind? = entries.firstOrNull { it.label == label }
    }
}
