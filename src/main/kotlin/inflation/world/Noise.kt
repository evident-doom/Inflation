package inflation.world

import kotlin.math.abs
import kotlin.math.floor
import kotlin.random.Random

/** Seeded 2D Perlin gradient noise, roughly in [-1, 1]. */
class Noise(seed: Long) {
    private val perm = IntArray(512)

    init {
        val p = IntArray(256) { it }
        val rnd = Random(seed)
        for (i in 255 downTo 1) {
            val j = rnd.nextInt(i + 1)
            val t = p[i]; p[i] = p[j]; p[j] = t
        }
        for (i in 0 until 512) perm[i] = p[i and 255]
    }

    fun noise(x: Double, y: Double): Double {
        val xi = floor(x).toInt(); val yi = floor(y).toInt()
        val xf = x - xi; val yf = y - yi
        val u = fade(xf); val v = fade(yf)
        val a = perm[(xi and 255)] + (yi and 255)
        val b = perm[((xi + 1) and 255)] + (yi and 255)
        val n00 = grad(perm[a], xf, yf)
        val n01 = grad(perm[a + 1], xf, yf - 1)
        val n10 = grad(perm[b], xf - 1, yf)
        val n11 = grad(perm[b + 1], xf - 1, yf - 1)
        return lerp(v, lerp(u, n00, n10), lerp(u, n01, n11)) * 1.41
    }

    /** Fractal sum of [octaves] octaves; [scale] is the wavelength of the first octave in cells. */
    fun fbm(x: Double, y: Double, scale: Double, octaves: Int, persistence: Double = 0.5): Double {
        var amp = 1.0; var freq = 1.0 / scale; var sum = 0.0; var norm = 0.0
        for (o in 0 until octaves) {
            sum += amp * noise(x * freq + o * 17.3, y * freq - o * 31.7)
            norm += amp
            amp *= persistence; freq *= 2.0
        }
        return sum / norm
    }

    /** Ridged multifractal in [0, 1]: sharp crests, used for mountain texture. */
    fun ridged(x: Double, y: Double, scale: Double, octaves: Int): Double {
        var amp = 1.0; var freq = 1.0 / scale; var sum = 0.0; var norm = 0.0
        for (o in 0 until octaves) {
            val r = 1.0 - abs(noise(x * freq + o * 5.1, y * freq + o * 9.7))
            sum += amp * r * r
            norm += amp
            amp *= 0.5; freq *= 2.0
        }
        return sum / norm
    }

    private fun fade(t: Double) = t * t * t * (t * (t * 6 - 15) + 10)
    private fun lerp(t: Double, a: Double, b: Double) = a + t * (b - a)
    private fun grad(hash: Int, x: Double, y: Double): Double = when (hash and 7) {
        0 -> x + y; 1 -> -x + y; 2 -> x - y; 3 -> -x - y
        4 -> x; 5 -> -x; 6 -> y; else -> -y
    }
}
