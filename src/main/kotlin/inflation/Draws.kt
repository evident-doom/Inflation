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
