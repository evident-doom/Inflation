package inflation.learning

import inflation.world.Place
import inflation.world.get
import inflation.world.set

import inflation.ecology.BandWay
import inflation.ecology.Binding
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
            val at = Probe(FloatArray(HexFeatures.size), x, null, FloatArray(Critic.GROUP))
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
    fun `an output that did not act in its season is neither learnt from nor pulled toward a wide spread`() {
        val dir = Files.createTempDirectory("ways").toFile()
        val st = WaysPpo.State(dir, 16, 1e-2f)
        val rnd = Random(4)
        val x = FloatArray(WAYS_IN) { rnd.nextFloat() }
        val at = Probe(FloatArray(HexFeatures.size), x, null, FloatArray(Critic.GROUP))
        val idle = BandWay.TIME_TOOLS.slot
        val spreadBefore = st.ways.logStd[idle]
        val cs = (0 until 400).map { k ->
            val up = k % 2 == 0
            val a = FloatArray(WAYS_OUT); a[0] = if (up) 0.3f else -0.3f; a[idle] = a[0]
            val terms = FloatArray(WAYS_OUT) { i -> val z = a[i] / kotlin.math.exp(st.ways.logStd[i]); -0.5f * z * z - st.ways.logStd[i] }
            // (No week of the season asked for more than the week had, and none moved camp: the time weights and the loads did not act.)
            WaysChoice(Catalogue.HUMAN, k, 20, 0, at, x, a, terms.sum()).also { it.terms = terms; it.binding = FloatArray(Binding.SIZE); it.advantage = if (up) 1f else -1f }
        }
        WaysPpo.learn(st, cs, 0, 0.0)
        assertTrue(middle(st.ways, x)[0] > 0f, "the output that acted learnt")
        assertEquals(0f, middle(st.ways, x)[idle], 0f)
        assertEquals(spreadBefore, st.ways.logStd[idle], 0f)
        dir.deleteRecursively()
    }

    @Test
    fun `ways saved before they read the camp's terms choose as they did, whatever the camp holds`() {
        val rnd = Random(1)
        val old = Mlp.random(intArrayOf(WAYS_IN, 16, WAYS_OUT), rnd)
        val dir = Files.createTempDirectory("ways").toFile()
        val f = java.io.File(dir, "ways.mlp"); Schema.save(old, f, Schema.WAYS_IN, Schema.WAYS_OUT, Role.PEOPLE.net)
        val merged = LearnedWays.loadOrNew(f, 16)
        assertEquals(WAYS_IN + CampTerms.size, merged.net.sizes[0])
        val x = FloatArray(WAYS_IN) { Random(3).nextFloat() }
        val into = FloatArray(WAYS_IN + CampTerms.size)
        waysInput(x, FloatArray(CampTerms.size) { 0.9f - 0.01f * it }, into)
        val b = merged.net.buffers(); into.copyInto(b.a[0])
        val ob = old.buffers(); x.copyInto(ob.a[0])
        assertEquals(old.forwardAll(ob).toList(), merged.net.forwardAll(b).toList())
        // Saved again, it keeps the camp's terms by name.
        merged.save(f); assertEquals(WAYS_IN + CampTerms.size, WaysKind.BANDS.load(f).sizes[0])
        dir.deleteRecursively()
    }

    @Test
    fun `the camp's terms leave out what says only how a place stands against the band's own`() {
        val f = FloatArray(HexFeatures.size) { it.toFloat() }
        val camp = CampTerms.of(f)
        assertEquals(CampTerms.size, camp.size)
        assertTrue(HexFeatures.FOOD.toFloat() in camp.toList() && HexFeatures.DEATHS_FORESEEN.toFloat() in camp.toList())
        for (apart in listOf(HexFeatures.STAYS, HexFeatures.EFFORT, HexFeatures.STEP_LENGTH)) assertTrue(apart.toFloat() !in camp.toList())
    }

    @Test
    fun `a band keeps its draw of ways for a season, and it is recorded once`() {
        val w = LearnedWays.fresh(16, Random(1))
        val record = java.util.concurrent.ConcurrentLinkedQueue<Choice>()
        var week = 0
        val standing = java.util.concurrent.ConcurrentHashMap<Int, Standing>()
        val sampler = WaysSampler(w, record, { 1f }, { week }, 5L, standing)
        val g = inflation.ecology.HexGroup(3, Catalogue.HUMAN, inflation.world.Place(0), 10, 4)
        val x = FloatArray(WAYS_IN)
        // (Where the band stood before it moved, as its mover would leave it each week.)
        fun stood(w: Int) { standing[g.id] = Standing(w, Probe(FloatArray(HexFeatures.size), x, null, FloatArray(Critic.GROUP))) }
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
