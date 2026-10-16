package inflation.learning

import inflation.Args
import inflation.ecology.Catalogue
import inflation.ecology.Species
import java.io.File
import inflation.world.WorldConfig

/**
 * How many choices are recorded: [perKindYear] a year of each kind in each world, and [peopleChoices]
 * times as many of people (one kind against fourteen).
 */
class Sampling(val perKindYear: Int = 1500, val peopleChoices: Float = 8f)

/**
 * Hand-picked maps (seed and centre latitude) to play and check on, in turn (e.g. those hex-pick-world
 * finds push herds hardest to migrate); empty for fresh worlds every round.
 */
class Maps(val maps: List<Pair<Long, Double>> = emptyList()) {
    fun isEmpty() = maps.isEmpty()
    fun world(seed: Long, size: Int, i: Int = 0): WorldConfig =
        if (maps.isEmpty()) WorldConfig(seed = seed, widthKm = size, heightKm = size)
        else maps[((i % maps.size) + maps.size) % maps.size].let { (s, lat) -> WorldConfig(seed = s, widthKm = size, heightKm = size, centreLatitude = lat) }
}

/** HexPpo's command line (see [HexPpo] for the usage). */
class PpoOptions(
    val out: File,
    val worlds: Int,
    val size: Int,
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
        const val USAGE = "hex-ppo [--out DIR] [--worlds N] [--size KM] [--years N] [--rounds N] [--hidden N] [--rate R] [--residual ROLE:X,...] [--check N] [--check-seeds N] [--per-kind N] [--people-choices X] [--world-rounds N] [--no-overlap] [--burn-in YEARS] [--hold ROLE[,ROLE]:ROUNDS] [--teacher DIR] [--teacher-check] [--no-ways] [--maps SEED:LAT,...] [--check-on-maps]"

        fun parse(args: Array<String>): PpoOptions {
            val a = Args(args, USAGE)
            val default = Sampling()
            val hold = a.string("--hold")?.split(":")?.let { it[0].split(",").map(Role::named).toSet() to (it.getOrNull(1)?.toInt() ?: Int.MAX_VALUE) }
            val o = PpoOptions(
                out = a.file("--out") ?: File("hex-ppo"),
                worlds = a.int("--worlds", 8),
                size = a.int("--size", 600),
                years = a.int("--years", 2),
                rounds = a.int("--rounds", 1000),
                hidden = a.int("--hidden", 48),
                rate = a.float("--rate", 3e-4f),
                residual = a.string("--residual")?.split(",")?.associate { r -> val (n, v) = r.split(":"); Role.named(n) to v.toFloat() } ?: emptyMap(),
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
                sampling = Sampling(a.int("--per-kind", default.perKindYear), a.float("--people-choices", default.peopleChoices)),
                teachers = a.file("--teacher")?.let { dir -> Catalogue.all.mapNotNull { s -> HexLearning.file(dir, s).takeIf { it.exists() }?.let { s to Schema.load(it, Schema.TEACHER_IN, Schema.MOVER_OUT) } }.toMap() } ?: emptyMap(),
                maps = Maps(a.string("--maps")?.split(",")?.map { m -> val (s, l) = m.split(":"); s.toLong() to l.toDouble() } ?: emptyList()),
            )
            a.done()
            return o
        }
    }
}
