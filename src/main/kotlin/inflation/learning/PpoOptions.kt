package inflation.learning

import inflation.Args
import inflation.Config
import java.io.File
import inflation.world.WorldConfig

/**
 * How many choices are recorded: [perKindYear] a year of each kind in each world, and [peopleChoices]
 * times as many of people (one kind against fourteen), but never more than [MAX_SHARE] of an animal kind's and [maxShare] of people's.
 */
class Sampling(val perKindYear: Int = 1500, val peopleChoices: Float = 8f,
               /** How many times as many of people's choices the critic learns from as the moves do (1: only those drawn); the rest are taken as the best, not learnt from by the moves. */
               val criticTimes: Float = 4f,
               /** How many times more a band under the hardest cold or hunger is recorded than one at ease (0: all alike). */
               val stressBoost: Float = 0f,
               /** The most of people's choices that are recorded (the animal kinds' is [MAX_SHARE]). */
               val maxShare: Float = MAX_SHARE) {
    companion object {
        /**
         * The most of a kind's choices that are recorded, and so drawn by chance instead of the best: a kind
         * in a few hundred groups, with every move drawn, wanders off its country and dwindles.
         */
        const val MAX_SHARE = 0.1f

        /** The most of a kind's other choices that are recorded for the critic alone. */
        const val MAX_VALUE_SHARE = 0.5f

        /** The most of a band's choices that are drawn when it is under stress. */
        const val MAX_STRESSED_SHARE = 0.3f
    }
}

/**
 * Hand-picked maps (seed and centre latitude) to play and check on, in turn (e.g. those hex-pick-world
 * finds push plant-eaters hardest to move with the seasons); empty for fresh worlds every round.
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
 * `ppo.hidden` (default 48), `ppo.rate` (3e-4), `ppo.residual` (ROLE:X,...; none), `ppo.perKind` (1500),
 * `ppo.peopleChoices` (8), `ppo.criticTimes` (4), `ppo.stressBoost` (0), `ppo.maxShare` (0.1), `ppo.waysTrainMover` (true) and `ppo.foreseenShape` (1, read by [HexPpo.Player]).
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
    /** A check of the networks every this many rounds (0: none). */
    val checkEvery: Int,
    val checkSeeds: Int,
    /** Check on the training maps instead of fresh worlds: how well they are fitted, not how well the networks do elsewhere. */
    val checkOnMaps: Boolean,
    /**
     * How many rounds a training world lives before a fresh one takes its place (0: until it goes badly
     * off the rails, so that the networks learn in worlds that have run long, where what bands and packs
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
    /** Whether learning the ways also trains the people's mover beneath them (else only their own layers). */
    val waysTrainMover: Boolean,
    val sampling: Sampling,
    val maps: Maps,
) {
    companion object {
        const val USAGE = "hex-ppo [--out DIR] [--worlds N] [--size KM|WxH] [--years N] [--rounds N] [--check N] [--check-seeds N] [--world-rounds N] [--no-overlap] [--burn-in YEARS] [--hold ROLE[,ROLE]:ROUNDS] [--no-ways] [--maps SEED:LAT,...] [--check-on-maps]"

        fun parse(args: Array<String>): PpoOptions {
            val a = Args(args, USAGE)
            val default = Sampling()
            // (One large world is better than many small ones: a training world can be wider than it is tall, as long as every place stays at a latitude the model holds for.)
            val (width, height) = (a.string("--size") ?: "600").lowercase().split("x").map { it.toInt() }.let { l -> l[0] to l.getOrElse(1) { l[0] } }
            val hold = a.string("--hold")?.split(":")
            val fixed = hold?.let { it[0].split(",").map(Role::named).toSet() } ?: emptySet()
            val fixedRounds = hold?.let { it.getOrNull(1)?.toInt() ?: Int.MAX_VALUE } ?: 0
            val o = PpoOptions(
                out = a.file("--out") ?: File("hex-ppo"),
                worlds = a.int("--worlds", 8),
                size = height,
                width = width,
                height = height,
                years = a.int("--years", 2),
                rounds = a.int("--rounds", 1000),
                hidden = Config.int(Config.Key.PPO_HIDDEN, 48),
                rate = Config.float(Config.Key.PPO_RATE, 3e-4f),
                residual = Config.string(Config.Key.PPO_RESIDUAL)?.split(",")?.associate { r -> val (n, v) = r.split(":"); Role.named(n) to v.toFloat() } ?: emptyMap(),
                checkEvery = a.int("--check", 5),
                checkSeeds = a.int("--check-seeds", 12),
                checkOnMaps = a.flag("--check-on-maps"),
                worldRounds = a.int("--world-rounds", 0),
                overlap = !a.flag("--no-overlap"),
                fixed = fixed,
                fixedRounds = fixedRounds,
                burnYears = a.int("--burn-in", 1),
                learnWays = !a.flag("--no-ways"),
                waysTrainMover = Config.flag(Config.Key.PPO_WAYS_TRAIN_MOVER, true),
                sampling = Sampling(Config.int(Config.Key.PPO_PER_KIND, default.perKindYear), Config.float(Config.Key.PPO_PEOPLE_CHOICES, default.peopleChoices), Config.float(Config.Key.PPO_CRITIC_TIMES, default.criticTimes), Config.float(Config.Key.PPO_STRESS_BOOST, default.stressBoost), Config.float(Config.Key.PPO_MAX_SHARE, default.maxShare)),
                maps = Maps(a.string("--maps")?.split(",")?.map { m -> val (s, l) = m.split(":"); s.toLong() to l.toDouble() } ?: emptyList()),
            )
            a.done()
            return o
        }
    }
}
