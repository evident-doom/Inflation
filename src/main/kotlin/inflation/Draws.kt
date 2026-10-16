package inflation
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
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
