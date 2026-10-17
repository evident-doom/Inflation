package inflation.learning

import inflation.Args
import inflation.Config
import inflation.ecology.Catalogue
import inflation.ecology.Species
import java.io.File
import inflation.world.WorldConfig

/**
 * How many choices are recorded: [perKindYear] a year of each kind in each world, and [peopleChoices]
 * times as many of people (one kind against fourteen), but never more than [MAX_SHARE] of a kind's.
 */
class Sampling(val perKindYear: Int = 1500, val peopleChoices: Float = 8f) {
    companion object {
        /**
         * The most of a kind's choices that are recorded, and so drawn by chance instead of the best: herds
         * live in a few hundred groups, and with every move drawn they wander off their country and dwindle.
         */
        const val MAX_SHARE = 0.1f
    }
}

/**
 * Hand-picked maps (seed and centre latitude) to play and check on, in turn (e.g. those hex-pick-world
 * finds push herds hardest to migrate); empty for fresh worlds every round.
 */
class Maps(val maps: List<Pair<Long, Double>> = emptyList()) {
    fun isEmpty() = maps.isEmpty()
    fun world(seed: Long, size: Int, i: Int = 0): WorldConfig = world(seed, size, size, i)
    fun world(seed: Long, width: Int, height: Int, i: Int): WorldConfig =
        if (maps.isEmpty()) WorldConfig(seed = seed, widthKm = width, heightKm = height)
        else maps[((i % maps.size) + maps.size) % maps.size].let { (s, lat) -> WorldConfig(seed = s, widthKm = width, heightKm = height, centreLatitude = lat) }
}

/**
 * HexPpo's command line (see [HexPpo] for the usage), and its learning settings from the config file:
 * `ppo.hidden` (default 48), `ppo.rate` (3e-4), `ppo.residual` (ROLE:X,...; none), `ppo.perKind` (1500)
 * and `ppo.peopleChoices` (8).
 */
class PpoOptions(
    val out: File,
    val worlds: Int,
    /** The checks' worlds' side (km); the training worlds are [width] by [height] (`--size W` or `--size WxH`). */
    val size: Int,
    val width: Int,
    val height: Int,
    val years: Int,
    val rounds: Int,
    val hidden: Int,
    val rate: Float,
    /** How much of the hand-written score each role's network starts from, by role. */
    val residual: Map<Role, Float>,
    val checkEvery: Int,
    val checkSeeds: Int,
    /** Check on the training maps instead of fresh worlds: how well they are fitted, not how well the networks do elsewhere. */
    val checkOnMaps: Boolean,
    val teacherCheck: Boolean,
    /**
     * How many rounds a training world lives before a fresh one takes its place (0: until it goes badly
     * off the rails, so that the networks learn in worlds that have run long, where what bands and herds
     * did compounds; 1: fresh every round).
     */
    val worldRounds: Int,
    /** Play the next round beside this round's learning (else each round's choices are made by the networks just learnt; slower). */
    val overlap: Boolean,
    /** Roles whose networks play but do not learn, for the first [fixedRounds] rounds of this run. */
    val fixed: Set<Role>,
    val fixedRounds: Int,
    /** Years a fresh training world runs, with the networks choosing, before any choice is recorded (its first state is not like a lived-in world's). */
    val burnYears: Int,
    /** Bands' ways learned beside their moves (else the scaffold's, or as evolved). */
    val learnWays: Boolean,
    val sampling: Sampling,
    /** Networks to copy instead of the hand-written behaviour, by kind (e.g. the herds' evolved ones). */
    val teachers: Map<Species, Mlp>,
    val maps: Maps,
) {
    companion object {
        const val USAGE = "hex-ppo [--out DIR] [--worlds N] [--size KM|WxH] [--years N] [--rounds N] [--check N] [--check-seeds N] [--world-rounds N] [--no-overlap] [--burn-in YEARS] [--hold ROLE[,ROLE]:ROUNDS] [--teacher DIR] [--teacher-check] [--no-ways] [--maps SEED:LAT,...] [--check-on-maps]"

        fun parse(args: Array<String>): PpoOptions {
            val a = Args(args, USAGE)
            val default = Sampling()
            // (One large world is better than many small ones: a training world can be wider than it is tall, as long as every place stays at a latitude the model holds for.)
            val size = (a.string("--size") ?: "600").lowercase().split("x").map { it.toInt() }.let { l -> l[0] to l.getOrElse(1) { l[0] } }
            val hold = a.string("--hold")?.split(":")?.let { it[0].split(",").map(Role::named).toSet() to (it.getOrNull(1)?.toInt() ?: Int.MAX_VALUE) }
            val o = PpoOptions(
                out = a.file("--out") ?: File("hex-ppo"),
                worlds = a.int("--worlds", 8),
                size = size.second,
                width = size.first,
                height = size.second,
                years = a.int("--years", 2),
                rounds = a.int("--rounds", 1000),
                hidden = Config.int("ppo.hidden", 48),
                rate = Config.float("ppo.rate", 3e-4f),
                residual = Config.string("ppo.residual")?.split(",")?.associate { r -> val (n, v) = r.split(":"); Role.named(n) to v.toFloat() } ?: emptyMap(),
                checkEvery = a.int("--check", 5),
                checkSeeds = a.int("--check-seeds", 12),
                checkOnMaps = a.flag("--check-on-maps"),
                teacherCheck = a.flag("--teacher-check"),
                worldRounds = a.int("--world-rounds", 0),
                overlap = !a.flag("--no-overlap"),
                fixed = hold?.first ?: emptySet(),
                fixedRounds = hold?.second ?: 0,
                burnYears = a.int("--burn-in", 1),
                learnWays = !a.flag("--no-ways"),
                sampling = Sampling(Config.int("ppo.perKind", default.perKindYear), Config.float("ppo.peopleChoices", default.peopleChoices)),
                teachers = a.file("--teacher")?.let { dir -> Catalogue.all.mapNotNull { s -> HexLearning.file(dir, s).takeIf { it.exists() }?.let { s to Schema.load(it, Schema.TEACHER_IN, Schema.MOVER_OUT) } }.toMap() } ?: emptyMap(),
                maps = Maps(a.string("--maps")?.split(",")?.map { m -> val (s, l) = m.split(":"); s.toLong() to l.toDouble() } ?: emptyList()),
            )
            a.done()
            return o
        }
    }
}
