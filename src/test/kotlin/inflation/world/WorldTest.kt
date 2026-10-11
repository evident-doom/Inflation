package inflation.world

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class WorldTest {
    private val cfg = WorldConfig(seed = 42, widthKm = 300, heightKm = 300, erosionSteps = 10)
    private val world by lazy { World.generate(cfg) }

    @Test
    fun `generation is deterministic for a seed`() {
        val again = World.generate(cfg)
        assertContentEquals(world.elevation, again.elevation)
        assertContentEquals(world.water.discharge, again.water.discharge)
    }

    @Test
    fun `world has sea, land and a plausible relief`() {
        val land = world.isSea.count { !it }
        assertTrue(land in world.grid.size / 5..world.grid.size * 19 / 20, "land fraction")
        assertTrue(world.elevation.max() in 300f..7000f, "highest point ${world.elevation.max()}")
    }

    @Test
    fun `rivers end in the sea, at the map edge or in a lake`() {
        val g = world.grid
        val d = world.water.drainage
        for (i in 0 until g.size) if (d.receiver[i] == i) assertTrue(world.isSea[i] || g.isEdge(i))
        // A river cell's flow never vanishes before reaching a lake, the sea or the edge.
        for (i in 0 until g.size) {
            if (!world.isRiver(i)) continue
            val r = d.receiver[i]
            if (r == i || world.isSea[r] || world.water.isLake(r)) continue
            assertTrue(world.water.discharge[r] >= world.water.discharge[i] * 0.999f, "discharge grows downstream")
        }
    }

    @Test
    fun `north is colder than south and highlands colder than lowlands`() {
        val g = world.grid
        fun lowlandColdest(row: Int) = (0 until g.width).map { g.index(it, row) }
            .filter { !world.isSea[it] && world.elevation[it] < 300 }
            .map { world.climate.coldestMonthTemp(it) }.average()
        val north = lowlandColdest(0)
        val south = lowlandColdest(g.height - 1)
        if (!north.isNaN() && !south.isNaN()) assertTrue(north < south)
        val land = (0 until g.size).filter { !world.isSea[it] }
        val high = land.maxBy { world.elevation[it] }
        val low = land.filter { g.y(it) == g.y(high) }.minBy { world.elevation[it] }
        assertTrue(world.climate.meanTemp(high) < world.climate.meanTemp(low))
    }

    @Test
    fun `weather averages to the climate over a year`() {
        val weather = Weather(world.grid, world.climate, cfg)
        val total = FloatArray(world.grid.size)
        repeat(Date.DAYS_PER_YEAR / cfg.daysPerStep * 3) {
            weather.step()
            for (i in total.indices) total[i] += weather.precipitation[i]
        }
        val land = (0 until world.grid.size).filter { !world.isSea[it] }
        val simulated = land.sumOf { total[it].toDouble() } / 3
        val normal = land.sumOf { world.climate.annualPrecip[it].toDouble() }
        assertTrue(simulated / normal in 0.7..1.3, "3-year mean is ${simulated / normal} of normal")
        assertTrue(weather.snowpack.indices.all { weather.snowpack[it] == 0f || world.climate.coldestMonthTemp(it) < 8f })
    }
}
