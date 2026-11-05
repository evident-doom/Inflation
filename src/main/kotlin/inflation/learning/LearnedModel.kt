package inflation.learning

import inflation.Config
import inflation.ecology.HexGroups
import inflation.ecology.HexPolicy
import inflation.ecology.Mind
import inflation.ecology.Species
import inflation.run.baselineDir
import inflation.run.baselinesRoot
import java.io.File

/**
 * The learned networks a run moves and chooses by, the same for every program (hex-fauna, the viewer, the
 * checks): nets=DIR (a directory or a baseline's name) moves each kind by its role's network in DIR
 * (hunters.mlp, people.mlp, as hex-ppo writes them; a role without one keeps the hand-written choices),
 * and sets the bands' and packs' ways by DIR's ways.mlp and hunter-ways.mlp where present; ways=FILE and
 * hunterWays=FILE set those ways instead.
 * Without nets= the latest baseline is used ([latestBaseline]): the hand-written choices only seed a new world's
 * start (founding), they are not a way to run one. A role with no network in the baseline keeps the hand-written
 * choices, as says the log line; a run with no baseline at all stops and says so.
 */
object LearnedModel {
    /** Sets [groups]' choices from the config's keys, saying with [log] what it loaded. */
    fun fromConfig(groups: HexGroups, log: (String) -> Unit) =
        apply(groups, configuredNets(), Config.string(WaysKind.BANDS.configKey), Config.string(WaysKind.HUNTERS.configKey), log)

    /** nets= as the config gives it, else the latest baseline; stops if there is neither. */
    fun configuredNets(): String = Config.string(Config.Key.NETS) ?: latestBaseline()?.path
        ?: error("no trained networks: nets= names none and ${baselinesRoot()} holds no baseline. Train one (hex-ppo) and keep it with `baseline save`")

    /** The baseline kept last (by the time its info.txt says, else its folder's), or none. */
    fun latestBaseline(): File? = baselinesRoot().listFiles()?.filter { it.isDirectory && Role.entries.any { r -> r.file(it).exists() } }
        ?.maxByOrNull { d -> File(d, "info.txt").takeIf { it.exists() }?.readLines()?.firstOrNull()?.removePrefix("kept ")
            ?.let { runCatching { java.time.LocalDateTime.parse(it) }.getOrNull() } ?: java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(d.lastModified()), java.time.ZoneId.systemDefault()) }

    /** The people mover of the bands of mind [m] in [dir], where it has one of its own ([HexGroups.mindPolicies]). */
    fun mindFile(dir: File, m: Mind) = File(dir, "${Role.PEOPLE.label}-${m.label}.mlp")

    fun apply(groups: HexGroups, nets: String?, ways: String?, hunterWays: String?, log: (String) -> Unit) {
        val dir = nets?.let { baselineDir(it) }
        if (dir != null) {
            val by = Role.entries.associateWith { r -> r.file(dir).takeIf { it.exists() }?.let { MoverPolicy(SharedMover.load(it), null, { 0f }, { 0 }) } }
            for (s in groups.kinds) by[Role.of(s)]?.let { groups.policies[s] = it }
            val missing = by.filterValues { it == null }.keys
            log("moving by the learned networks in $dir" + if (missing.isEmpty()) "" else " (WARNING: no network there for ${missing.joinToString { it.label }}: those groups keep the hand-written choices; train and keep a baseline with all roles)")
            // (A mind with a mover of its own, people-MIND.mlp beside people.mlp, moves its bands by it; any other mind's bands by
            // people.mlp. None is trained yet: review/2026-11-05-people-models-harmonize.md, step 5.)
            groups.mindPolicies.clear()
            for (m in Mind.entries) if (m != Mind.NETS) mindFile(dir, m).takeIf { it.exists() }?.let { f ->
                val p = MoverPolicy(SharedMover.load(f), null, { 0f }, { 0 })
                groups.mindPolicies[m] = HashMap<Species, HexPolicy>().also { by -> for (s in groups.kinds) if (Role.of(s) == Role.PEOPLE) by[s] = p }
                log("${m.label} bands move by $f")
            }
        }
        fun file(given: String?, name: String) = given?.let { File(it) } ?: dir?.let { File(it, name) }?.takeIf { it.exists() }
        for ((k, given) in listOf(WaysKind.BANDS to ways, WaysKind.HUNTERS to hunterWays))
            file(given, k.file)?.let { k.set(groups, k.load(it)); log("${k.whose} ways by $it") }
    }
}
