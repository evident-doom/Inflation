package inflation

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
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

    /**
     * Each count comes up as often as the Poisson odds say, within five standard errors, at means on both sides of the switch
     * from inversion to transformed rejection and far past where a normal draw stood in: so the draw is exact, skew and all.
     */
    @Test
    fun poissonCountsFollowTheOdds() {
        val r = Random(3)
        for (m in listOf(0.05f, 0.7f, 3f, 9.9f, 10f, 12f, 31f, 80f, 500f)) {
            val draws = 200_000
            val seen = HashMap<Int, Int>()
            repeat(draws) { val k = poisson(m, r); assertTrue(k >= 0); seen.merge(k, 1, Int::plus) }
            val lam = m.toDouble(); val sd = sqrt(lam)
            for (k in maxOf(0, (lam - 4 * sd).toInt())..(lam + 4 * sd + 2).toInt()) {
                var lnFact = 0.0; for (i in 2..k) lnFact += kotlin.math.ln(i.toDouble())
                val p = kotlin.math.exp(k * kotlin.math.ln(lam) - lam - lnFact)
                val f = (seen[k] ?: 0).toDouble() / draws
                assertTrue(abs(f - p) < 5 * sqrt(p * (1 - p) / draws) + 1e-4, "poisson($m): count $k came up ${"%.5f".format(f)}, expected ${"%.5f".format(p)}")
            }
        }
    }

    /** A stream is fixed by its key, differs with the world's seed, and no group replays another's at any lag (as id * 7919 + day once did). */
    @Test
    fun streamsAreKeptApart() {
        assertEquals(stream(3, Stream.FORAGE, 5, 600).nextLong(), stream(3, Stream.FORAGE, 5, 600).nextLong())
        val keys = HashSet<Long>()
        for (seed in 1L..3L) for (s in Stream.entries) for (id in 0L until 40L) for (day in 0L until 60_000L step 600L)
            assertTrue(keys.add(drawKey(seed, s, id, day)), "key repeated: seed $seed, $s, id $id, day $day")
        assertEquals(Stream.entries.size, Stream.entries.map { it.salt }.toSet().size, "two streams share a salt")
    }
}
