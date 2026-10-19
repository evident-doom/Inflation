package inflation

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class DrawsTest {
    /** The mean of many draws lies within four standard errors of what is expected, at the sizes groups draw at. */
    @Test
    fun binomialMeansAreUnbiased() {
        val r = Random(1)
        for ((n, p) in listOf(120 to 0.002f, 120 to 0.005f, 160 to 0.01f, 60 to 0.1f, 500 to 0.02f, 200 to 0.5f, 300 to 0.98f, 1000 to 0.999f)) {
            val draws = 100_000
            var sum = 0.0
            repeat(draws) { val k = binomial(n, p, r); assertTrue(k in 0..n); sum += k }
            val want = n * p.toDouble(); val se = sqrt(n * p * (1.0 - p) / draws)
            assertTrue(abs(sum / draws - want) < 4 * se + 0.01 * want + 1e-3, "binomial($n, $p): mean ${sum / draws}, expected $want")
        }
    }

    @Test
    fun poissonMeansAreUnbiased() {
        val r = Random(2)
        for (m in listOf(0.3f, 5f, 31f, 80f)) {
            val draws = 100_000
            var sum = 0.0
            repeat(draws) { sum += poisson(m, r) }
            assertTrue(abs(sum / draws - m) < 4 * sqrt(m / draws.toDouble()) + 1e-3, "poisson($m): mean ${sum / draws}")
        }
    }
}
