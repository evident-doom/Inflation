package inflation.learning

import inflation.ecology.Catalogue
import inflation.ecology.HexFeatures
import inflation.ecology.WAYS_IN
import inflation.ecology.WAYS_OUT
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WaysPpoTest {
    private fun middle(w: LearnedWays, x: FloatArray): FloatArray { val b = w.net.buffers(); x.copyInto(b.a[0]); return w.net.forwardAll(b).copyOf() }

    @Test
    fun `fresh learned ways keep to the scaffold's, and come back as saved`() {
        val w = LearnedWays.fresh(16, Random(1))
        val x = FloatArray(WAYS_IN) { Random(2).nextFloat() }
        assertTrue(middle(w, x).all { it == 0f })
        val dir = Files.createTempDirectory("ways").toFile()
        val f = java.io.File(dir, "ways.mlp")
        w.logStd[3] = -1f
        w.save(f)
        val back = LearnedWays.loadOrNew(f, 16)
        assertEquals(-1f, back.logStd[3], 1e-5f)
        assertEquals(w.net.w.toList(), back.net.w.toList())
        dir.deleteRecursively()
    }

    @Test
    fun `ways that turned out better draw the middle toward them, worse ones push it away`() {
        for (sign in listOf(1f, -1f)) {
            val dir = Files.createTempDirectory("ways").toFile()
            val st = WaysPpo.State(dir, 16, 1e-2f)
            val rnd = Random(4)
            val x = FloatArray(WAYS_IN) { rnd.nextFloat() }
            // Every draw took way 0 higher than the middle; half did well (or badly, by [sign]), half the opposite way round, as
            // the people's critic judged them.
            val at = Probe(FloatArray(HexFeatures.SIZE), x, null, FloatArray(Critic.GROUP))
            val cs = (0 until 400).map { k ->
                val a = FloatArray(WAYS_OUT); a[0] = 0.3f
                val logp = (0 until WAYS_OUT).sumOf { i -> val z = a[i] / kotlin.math.exp(st.ways.logStd[i]); (-0.5f * z * z - st.ways.logStd[i]).toDouble() }.toFloat()
                WaysChoice(Catalogue.HUMAN, k, 20, 0, at, x, a, logp).also { it.ret = 1f + sign * 0.01f; it.advantage = sign }
            } + (0 until 400).map { k ->
                val a = FloatArray(WAYS_OUT); a[0] = -0.3f
                val logp = (0 until WAYS_OUT).sumOf { i -> val z = a[i] / kotlin.math.exp(st.ways.logStd[i]); (-0.5f * z * z - st.ways.logStd[i]).toDouble() }.toFloat()
                WaysChoice(Catalogue.HUMAN, 1000 + k, 20, 0, at, x, a, logp).also { it.ret = 1f - sign * 0.01f; it.advantage = -sign }
            }
            WaysPpo.learn(st, cs, 0, 0.0)
            val m = middle(st.ways, x)[0]
            assertTrue(sign * m > 0f, "sign $sign: middle of way 0 went to $m")
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a band keeps its draw of ways for a season, and it is recorded once`() {
        val w = LearnedWays.fresh(16, Random(1))
        val record = java.util.concurrent.ConcurrentLinkedQueue<Choice>()
        var week = 0
        val standing = java.util.concurrent.ConcurrentHashMap<Int, Standing>()
        val sampler = WaysSampler(w, record, { 1f }, { week }, 5L, standing)
        val g = inflation.ecology.HexGroup(3, Catalogue.HUMAN, inflation.ecology.Place(0), 10, 4)
        val x = FloatArray(WAYS_IN)
        // (Where the band stood before it moved, as its mover would leave it each week.)
        fun stood(w: Int) { standing[g.id] = Standing(w, Probe(FloatArray(HexFeatures.SIZE), x, null, FloatArray(Critic.GROUP))) }
        val draws = (0 until 2 * WaysPpo.SEASON).map { week = it; stood(it); FloatArray(WAYS_OUT).also { o -> sampler.choose(g, x, o) }.toList() }
        val start = (WaysPpo.SEASON - g.id % WaysPpo.SEASON) % WaysPpo.SEASON
        // The same draw all season, a new one when the next begins.
        for (k in 1 until WaysPpo.SEASON) assertEquals(draws[start], draws[start + k])
        assertTrue(draws[start] != draws[start - 1])
        // One record a season, at its first week, holding the draw kept.
        assertEquals(2, record.size)
        assertEquals(listOf(start, start + WaysPpo.SEASON), record.map { it.week })
        assertEquals(draws[start], (record.first() as WaysChoice).a.toList())
        // Not seen choosing its place that week, the draw is not recorded.
        record.clear(); standing.clear()
        week = start + 2 * WaysPpo.SEASON; sampler.choose(g, x, FloatArray(WAYS_OUT))
        assertEquals(0, record.size)
    }
}
