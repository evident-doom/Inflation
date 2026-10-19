package inflation.learning

import inflation.ecology.Species
import java.io.File

/**
 * The learned networks, by role: one for the herds (kept until retraining, though no herds live as groups
 * now), one for the hunters and one for people. (Prey and hunters learn apart, so that what makes a hunter
 * better and what makes its prey better do not pull at the same weights; within a role, kinds share what
 * they learn.) Lists of networks go in this order.
 */
enum class Role {
    HERDS, HUNTERS, PEOPLE;

    /** Its name in files and on the command line. */
    val label = name.lowercase()

    /** Its network in [dir]. */
    fun file(dir: File) = File(dir, "$label.mlp")

    /** Its critic in [dir] (see [Critic]). */
    fun criticFile(dir: File) = File(dir, "$label.critic.mlp")

    override fun toString() = label

    companion object {
        fun of(s: Species) = if (s.human) PEOPLE else if (s.carnivore) HUNTERS else HERDS

        /** The role called [label] (as on the command line). */
        fun named(label: String) = entries.firstOrNull { it.label == label } ?: error("no role \"$label\": roles are ${entries.joinToString()}")
    }
}
