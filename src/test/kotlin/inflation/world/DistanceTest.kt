package inflation.world

import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class DistanceTest {
    @Test
    fun `distance to the nearest source matches brute force`() {
        val grid = Grid(37, 23, 1.0)
        val rnd = Random(5)
        val sources = BooleanArray(grid.size) { rnd.nextFloat() < 0.02f }
        val out = FloatArray(grid.size)
        distanceKm(grid, out, 1e6f) { sources[it] }
        for (i in 0 until grid.size) {
            val expected = (0 until grid.size).filter { sources[it] }
                .minOf { hypot((grid.x(it) - grid.x(i)).toDouble(), (grid.y(it) - grid.y(i)).toDouble()) }
            assertEquals(expected, out[i].toDouble(), 1e-4, "cell $i")
        }
    }

    @Test
    fun `no source leaves everything far`() {
        val grid = Grid(10, 8, 1.0)
        val out = FloatArray(grid.size)
        distanceKm(grid, out, 1e6f) { false }
        assertEquals(1e6f, out.max())
        assertEquals(1e6f, out.min())
    }
}
