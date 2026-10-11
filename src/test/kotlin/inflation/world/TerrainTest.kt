package inflation.world

import kotlin.test.Test
import kotlin.test.assertTrue

class TerrainTest {
    private val world by lazy { World.generate(WorldConfig(seed = 42, widthKm = 300, heightKm = 300, erosionSteps = 10, plates = 6, oceanMarginKm = 40.0)) }
    private val land by lazy { (0 until world.grid.size).filter { !world.isSea[it] && !world.water.isLake(it) } }

    @Test
    fun `mountains and bare rock are rugged, alluvial plains smooth`() {
        val t = world.terrain
        val plains = land.filter { world.geology.rock[it] == Rock.ALLUVIUM && t.slope[it] < 0.02f }
        val steep = land.sortedByDescending { t.slope[it] }.take(land.size / 20)
        assertTrue(plains.size > 20 && steep.isNotEmpty())
        val p = plains.map { t.ruggedness[it] }.average(); val s = steep.map { t.ruggedness[it] }.average()
        assertTrue(s > p + 0.3, "steep $s vs plains $p")
        assertTrue(land.all { t.ruggedness[it] in 0f..1f })
    }

    @Test
    fun `open water cannot be walked and wetlands and thickets are slow`() {
        val t = world.terrain
        val v = Vegetation(world, 42)
        assertTrue((0 until world.grid.size).filter { world.isSea[it] }.all { t.baseCost[it] == Float.POSITIVE_INFINITY })
        assertTrue(land.all { t.baseCost[it] >= 1f })
        val i = land.minBy { t.baseCost[it] }
        for (form in v.forms.values) form[i] = 0f
        val bare = t.travelCost(i, v, 0f)
        assertTrue(t.travelCost(i, v, 300f) > 2.5f * bare, "deep snow slows walking")
        v.shrub[i] = 3f
        assertTrue(t.travelCost(i, v, 0f) > 1.5f * bare, "a thicket slows walking")
    }

    @Test
    fun `plants and broken ground cut visibility, hills see far`() {
        val t = world.terrain
        val v = Vegetation(world, 42)
        val i = land.filter { t.ruggedness[it] < 0.1f }.maxBy { t.viewKm[it] }
        for (form in v.forms.values) form[i] = 0f
        val open = t.sightKm(i, v)
        v.tree[i] = 25f
        assertTrue(t.sightKm(i, v) < 0.1f && open > 2f, "open $open, forest ${t.sightKm(i, v)}")
        val rugged = land.maxBy { t.ruggedness[it] }
        for (form in v.forms.values) form[rugged] = 0f
        assertTrue(t.concealmentKm(rugged, v) < 0.5f * open, "broken ground hides: ${t.concealmentKm(rugged, v)} vs $open")
        // In hilly country, compare ridge tops with valley floors.
        val hilly = land.filter { t.relief[it] > 200f }
        val tops = hilly.sortedByDescending { t.prominence[it] }.take(hilly.size / 20)
        val hollows = hilly.sortedBy { t.prominence[it] }.take(hilly.size / 20)
        // Valleys see their own walls close by; tops see far.
        val top = tops.map { t.viewKm[it] }.average(); val hollow = hollows.map { t.viewKm[it] }.average()
        assertTrue(top > 1.25 * hollow, "summits see $top km on average, hollows $hollow km")
    }

    @Test
    fun `high, broken or water-girt ground is more defensible than open plain`() {
        val t = world.terrain
        val plains = land.filter { t.slope[it] < 0.02f && t.prominence[it] in -10f..10f && t.baseCost[it] < 2f }
        val hills = land.sortedByDescending { t.prominence[it] }.take(land.size / 50)
        assertTrue(hills.map { t.defensibility[it] }.average() > plains.map { t.defensibility[it] }.average() + 0.2)
    }
}
