package inflation
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * Hashes for draws that must not depend on the order things are stepped in: a place's draw this week
 * is a function of the place, the week and a salt, not of what was drawn before it.
 */

/** Odd constants to spread the parts of a key over the bits before mixing (the golden ratio's and others). */
const val HASH_A = -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
const val HASH_B = 0x632BE59BD9B4E019L
const val HASH_C = 0x165667B19E3779F9L

/** MurmurHash3's 64-bit finaliser: every bit of [h] moves every bit of the result. */
fun mix64(h0: Long): Long {
    var h = (h0 xor (h0 ushr 33)) * -0xae502812aa7333L
    h = (h xor (h ushr 33)) * -0x3b314601e57a13adL
    return h xor (h ushr 33)
}

/** The first round of [mix64] only: cheaper, and enough where the key is spread already. */
fun mix64Once(h0: Long): Long {
    val h = (h0 xor (h0 ushr 33)) * -0xae502812aa7333L
    return h xor (h ushr 33)
}

/**
 * The model's own streams of chance, each kept apart from the others by its [salt] (fixed, so that adding one moves
 * no other). A stream is drawn by [stream] from the world's seed, the stream, who draws ([id]) and when ([at]).
 */
enum class Stream(val salt: Long) {
    // Making the world.
    /** The terrain's noise field ([inflation.world.Noise]). */
    NOISE(0x4e4f4953L),
    TECTONICS(0x54454354L),
    EROSION(0x45524f44L),
    VOLCANOES(0x564f4c43L),
    /** Names of rivers, lakes and the rest. */
    FEATURE_NAMES(0x4e414d45L),
    /** Where springs rise ([inflation.world.HexDrinkingWater]). */
    SPRINGS(0x53505249L),

    // The world over time.
    /** The weather's turns (regimes, droughts), for each weather replicate. */
    WEATHER(0x57454154L),
    /** The mast crop's years, for each weather replicate. */
    MAST(0x4d415354L),
    ERUPTIONS(0x45525550L),
    /** The fine grid's plants ([inflation.world.Vegetation]): their start, and each cell's draws a step. */
    FINE_PLANTS(0x56454745L),
    FIRES(0x46495245L),
    OUTBREAKS(0x4f555442L),
    LOCUSTS(0x4c4f4355L),

    // Groups.
    /** The groups' one shared stream ([inflation.ecology.GroupWorld.rnd]). */
    GROUPS(0x47525053L),
    /** A group's week of life and death ([inflation.ecology.GroupWorld.groupRandom]). */
    GROUP_WEEK(0x47524f55L),
    /** A group's choice of ground for the week ([inflation.ecology.GroupWorld.policyRandom]). */
    POLICY(0x504f4c49L),
    /** The small jitter that keeps a group's places from tying ([inflation.ecology.GroupWorld.hashNoise]). */
    PLACE_JITTER(0x4a495454L),
    /** A party's week of foraging. */
    FORAGE(0x464f5241L),
    /** Whether a band goes somewhere new this week. */
    EXPLORE(0x4558504cL),
    /** A band's customs' year, drawn each week. */
    CUSTOMS(0x43555354L),
    /** Who comes first when two things happen at once ([inflation.ecology.tieBreak]). */
    TIE(0x54494542L),
    /** Resident hunters of different kinds meeting in a hex. */
    RESIDENT_MEETINGS(0x5245534dL),
    /** Newcomer animals in training's play worlds. */
    NEWCOMERS(0x4e455743L),
    /** The sizes groups of a kind start at. */
    START_SIZES(0x53495a45L),
    /** Which founding bands have the planning kind's mind. */
    PLANNERS(0x504c414eL),
    TEMPERAMENT(0x54454d50L),
    SISU(0x53495355L),
    /** The years a band reckons its stores against. */
    STORES_AHEAD(0x53544f52L),
    /** The lineage of each block of country, and the words spoken there. */
    LINEAGE(0x4c494e45L),
    WORDS(0x574f5244L),

    // Training and its tools.
    /** A band's learned ways drawn for a season, in training. */
    WAYS_SEASON(0x57415953L),
    /** The order a round's choices are learned from in, by round and pass. */
    BATCHES(0x42415443L),
    /** The noise of each generation of evolved ways. */
    EVOLUTION(0x45564f4cL),
    /** Which cases a diagnostic prints ([Trace]): apart from the model's own draws, so that tracing changes nothing. */
    TRACE(0x54524143L),
    /** Which bands an experiment perturbs, and what it draws for them. */
    EXPERIMENT(0x45585045L),
}

/**
 * The key of [stream]'s draw for [id] at [at] in the world of [seed]: each part mixed in turn ([mix64] is one to one),
 * so no two of seed, stream, id and time give the same key but by chance, however far apart the ids or times lie.
 */
fun drawKey(seed: Long, stream: Stream, id: Long, at: Long): Long = mix64(mix64(mix64(seed + stream.salt * HASH_A) + id) + at)

/** [drawKey] with one more [part] (a salt of the caller's, say). */
fun drawKey(seed: Long, stream: Stream, id: Long, at: Long, part: Long): Long = mix64(drawKey(seed, stream, id, at) + part)

/** An even draw in 0..1 from [stream] for [id] at [at] (and [part]) in the world of [seed] (see [drawKey]). */
fun drawUnit(seed: Long, stream: Stream, id: Long, at: Long, part: Long = 0L): Float = unitOf(drawKey(seed, stream, id, at, part))

/** [stream]'s own stream of chance for [id] at [at] in the world of [seed] (see [drawKey]). */
fun stream(seed: Long, stream: Stream, id: Long = 0L, at: Long = 0L) = kotlin.random.Random(drawKey(seed, stream, id, at))

/** The top 24 bits of [h] as a number in 0..1. */
fun unitOf(h: Long): Float = (h ushr 40).toFloat() / (1L shl 24).toFloat()

/** A standard normal draw from [r] (Box–Muller, with the first draw kept off zero). */
fun gaussian(r: kotlin.random.Random): Double {
    val u = max(r.nextDouble(), 1e-12)
    return sqrt(-2 * ln(u)) * cos(2 * Math.PI * r.nextDouble())
}

/** A standard normal draw from [r], as a float ([gaussian]). */
fun gaussianF(r: kotlin.random.Random): Float = gaussian(r).toFloat()

/** An index drawn at random by [weights] (summing to [total]), from [r]. */
fun drawWeighted(weights: FloatArray, total: Float, r: kotlin.random.Random): Int {
    var u = r.nextFloat() * total; var i = 0
    while (i < weights.size - 1 && u > weights[i]) { u -= weights[i]; i++ }
    return i
}

/** A Poisson draw of [mean] from [r] (normal, rounded to the nearest count, above a mean of 30). */
fun poisson(mean: Float, r: kotlin.random.Random): Int {
    if (mean <= 0f) return 0
    if (mean > 30f) return (mean + sqrt(mean) * gaussianF(r)).roundToInt().coerceAtLeast(0)
    val l = exp(-mean.toDouble()); var k = 0; var pr = 1.0
    do { k++; pr *= r.nextDouble() } while (pr > l)
    return k - 1
}

/**
 * A binomial draw of [n] tries at [p] from [r]. Exact, try by try, up to 50 tries; beyond that, normal (rounded to
 * the nearest count) where both outcomes are expected at least 10 times, and otherwise exact by the gaps between
 * successes, which costs one draw per success.
 */
fun binomial(n: Int, p: Float, r: kotlin.random.Random): Int {
    if (n <= 0 || p <= 0f) return 0
    if (p >= 1f) return n
    if (n <= 50) { var k = 0; repeat(n) { if (r.nextFloat() < p) k++ }; return k }
    if (n * p >= 10f && n * (1 - p) >= 10f) return (n * p + sqrt(n * p * (1 - p)) * gaussianF(r)).roundToInt().coerceIn(0, n)
    if (p > 0.5f) return n - binomial(n, 1f - p, r)
    // (Rare successes: the tries up to each next one are geometric.)
    val lq = ln(1.0 - p); var k = 0; var at = 0L
    while (true) {
        at += (ln(max(r.nextDouble(), 1e-300)) / lq).toLong() + 1
        if (at > n) return k
        k++
    }
}
