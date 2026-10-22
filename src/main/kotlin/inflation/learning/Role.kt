package inflation.learning

import inflation.ecology.Species
import java.io.File

/**
 * The learned networks, by role: one for the hunters and one for people. (They learn apart, so that what makes a
 * hunter better and what makes a band better do not pull at the same weights; within a role, kinds share what they
 * learn.) Lists of networks go in this order.
 */
enum class Role {
    HUNTERS, PEOPLE;

    /** Its name in files and on the command line. */
    val label = name.lowercase()

    /**
     * The version of its networks (the mover and the ways network that goes with it): raise the major by hand when
     * either changes structure (see [NetVersion]). History: 1 = hunters' and people's networks as of model 4.17; people 2 = the bands' ways network with the carry and time outputs (model 4.23); people 3 = one network for the
     * week's plan: the ways read the mover's last hidden layer at the place chosen, and train it (model 4.26); people 4 = the ways' keenness on each way of building (model 4.29).
     */
    val net get() = if (this == PEOPLE) NetVersion(PEOPLE_MAJOR, 0) else NetVersion(HUNTERS_MAJOR, 0)

    /** Whether the networks of this role in [dir] were saved under another net major version than today's (or none). */
    fun netMajorChanged(dir: File) = listOf(file(dir), File(dir, ways.file)).filter { it.exists() }.any { Schema.versionOf(it).major != net.major }

    /** Its network in [dir]. */
    fun file(dir: File) = File(dir, "$label.mlp")

    /** The ways network that goes with it: the bands' for people, the packs' for hunters. */
    val ways get() = if (this == PEOPLE) WaysKind.BANDS else WaysKind.HUNTERS

    /** Its critic in [dir] (see [Critic]). */
    fun criticFile(dir: File) = File(dir, "$label.critic.mlp")

    override fun toString() = label

    companion object {
        private const val HUNTERS_MAJOR = 1
        private const val PEOPLE_MAJOR = 4

        /** The role of group kind [s] (every group is a hunter or people). */
        fun of(s: Species) = if (s.human) PEOPLE else HUNTERS

        /** The role called [label] (as on the command line). */
        fun named(label: String) = entries.firstOrNull { it.label == label } ?: error("no role \"$label\": roles are ${entries.joinToString()}")
    }
}
