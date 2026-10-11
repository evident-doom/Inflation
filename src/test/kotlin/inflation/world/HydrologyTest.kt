package inflation.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HydrologyTest {
    /** A bowl sloping down to the west edge, with a closed pit in the middle. */
    private fun bowl(grid: Grid): FloatArray = FloatArray(grid.size) { i ->
        val x = grid.x(i); val y = grid.y(i)
        val base = 10f * x
        val pit = if (x in 8..11 && y in 8..11) -60f else 0f
        base + pit + if (x == 7 && y in 7..12) 5f else 0f
    }

    @Test
    fun `priority flood fills a pit to its spill level`() {
        val grid = Grid(20, 20, 1.0)
        val h = bowl(grid)
        val westEdge = { i: Int -> grid.x(i) == 0 }
        val filled = priorityFlood(grid, h, westEdge)
        for (i in 0 until grid.size) assertTrue(filled[i] >= h[i])
        val pit = grid.index(9, 9)
        assertEquals(75f, filled[pit], "pit fills to the lowest point on its rim")
        // Outside the pit, nothing changes.
        assertEquals(h[grid.index(15, 3)], filled[grid.index(15, 3)])
    }

    @Test
    fun `every cell drains downhill to an outlet`() {
        val grid = Grid(20, 20, 1.0)
        val westEdge = { i: Int -> grid.x(i) == 0 }
        val routing = priorityFlood(grid, bowl(grid), westEdge, routable = true)
        val d = Drainage.build(grid, routing, westEdge)
        assertEquals(grid.size, d.stack.size)
        for (i in 0 until grid.size) {
            val r = d.receiver[i]
            if (r == i) assertTrue(westEdge(i), "only outlets are roots") else assertTrue(routing[r] < routing[i])
        }
        val acc = d.accumulate(FloatArray(grid.size) { 1f })
        val atOutlets = (0 until grid.size).filter { d.receiver[it] == it }.sumOf { acc[it].toDouble() }
        assertEquals(grid.size.toDouble(), atOutlets, "all water reaches an outlet")
    }
}
