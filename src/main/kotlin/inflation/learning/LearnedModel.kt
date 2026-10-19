package inflation.learning

import inflation.Config
import inflation.ecology.HexGroups
import inflation.run.baselineDir
import java.io.File

/**
 * The learned networks a run moves and chooses by, the same for every program (hex-fauna, the viewer, the
 * checks): nets=DIR (a directory or a baseline's name) moves each kind by its role's network in DIR
 * (herds.mlp, hunters.mlp, people.mlp, as hex-ppo writes them; a role without one keeps the hand-written
 * choices), and sets the bands', herds' and packs' ways by DIR's ways.mlp, herd-ways.mlp and
 * hunter-ways.mlp where present; ways=FILE, herdWays=FILE and hunterWays=FILE set those ways instead.
 * Without any of them the hand-written choices are the model.
 */
object LearnedModel {
    /** Sets [groups]' choices from the config's keys, saying with [log] what it loaded. */
    fun fromConfig(groups: HexGroups, log: (String) -> Unit) =
        apply(groups, Config.string("nets"), Config.string(WaysKind.BANDS.configKey), Config.string(WaysKind.HERDS.configKey),
            Config.string(WaysKind.HUNTERS.configKey), log)

    fun apply(groups: HexGroups, nets: String?, ways: String?, herdWays: String?, hunterWays: String?, log: (String) -> Unit) {
        val dir = nets?.let { baselineDir(it) }
        if (dir != null) {
            val by = Role.entries.associateWith { r -> r.file(dir).takeIf { it.exists() }?.let { MoverPolicy(SharedMover.load(it), null, { 0f }, { 0 }) } }
            for (s in groups.kinds) by[Role.of(s)]?.let { groups.policies[s] = it }
            val missing = by.filterValues { it == null }.keys
            log("moving by the learned networks in $dir" + if (missing.isEmpty()) "" else " (none there for ${missing.joinToString { it.label }}: hand-written)")
        }
        fun file(given: String?, name: String) = given?.let { File(it) } ?: dir?.let { File(it, name) }?.takeIf { it.exists() }
        for ((k, given) in listOf(WaysKind.BANDS to ways, WaysKind.HERDS to herdWays, WaysKind.HUNTERS to hunterWays))
            file(given, k.file)?.let { k.set(groups, k.load(it)); log("${k.whose} ways by $it") }
    }
}
