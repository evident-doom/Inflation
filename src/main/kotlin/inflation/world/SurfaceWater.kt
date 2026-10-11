package inflation.world

import kotlin.math.max

/**
 * Present-day lakes and rivers from a water balance. Each closed depression receives the runoff
 * of its catchment; open water evaporates at about 1.1 times potential evapotranspiration. If
 * inflow exceeds evaporation from the full basin the lake fills to its spill point and overflows;
 * otherwise it stands lower and has no outlet, and its water turns saline.
 */
class SurfaceWater(
    /** Lake surface elevation where a lake stands, NaN elsewhere. */
    val lakeLevel: FloatArray,
    /** True for lakes with no outflow (saline). */
    val lakeClosed: BooleanArray,
    /** Mean annual river discharge through each cell, m^3/s. */
    val discharge: FloatArray,
    val drainage: Drainage,
) {
    fun isLake(i: Int) = !lakeLevel[i].isNaN()

    companion object {
        const val SECONDS_PER_YEAR = 3.1536e7
        /** Discharge above which a cell counts as a river, m^3/s. */
        const val RIVER_THRESHOLD = 0.5f

        fun generate(grid: Grid, elevation: FloatArray, isSea: BooleanArray, climate: Climate): SurfaceWater {
            val n = grid.size
            val outlet = { i: Int -> isSea[i] || grid.isEdge(i) }
            val spill = priorityFlood(grid, elevation, outlet)
            val routing = priorityFlood(grid, elevation, outlet, routable = true)
            val drainage = Drainage.build(grid, routing, outlet)
            val area = grid.cellAreaM2.toFloat()

            val landInflow = FloatArray(n) { if (isSea[it]) 0f else climate.runoff[it] / 1000f * area } // m^3/yr
            val acc = drainage.accumulate(landInflow)

            // Label closed depressions (cells below their spill level).
            val label = IntArray(n) { -1 }
            val members = ArrayList<IntArray>()
            val queue = IntArray(n)
            for (s in 0 until n) {
                if (label[s] >= 0 || isSea[s] || spill[s] - elevation[s] < 0.5f) continue
                var head = 0; var tail = 0
                queue[tail++] = s; label[s] = members.size
                while (head < tail) {
                    val i = queue[head++]
                    grid.forNeighbours(i) { j, _ ->
                        if (label[j] < 0 && !isSea[j] && spill[j] - elevation[j] >= 0.5f) {
                            label[j] = members.size; queue[tail++] = j
                        }
                    }
                }
                members.add(queue.copyOfRange(0, tail))
            }

            val lakeLevel = FloatArray(n) { Float.NaN }
            val closed = BooleanArray(n)
            val netWeight = landInflow.copyOf()
            for (cells in members) {
                // Everything upstream passes through the basin's lowest-on-route cell with the largest flow.
                val inflow = cells.maxOf { acc[it] }.toDouble()
                val sorted = cells.sortedBy { elevation[it] }
                var evap = 0.0
                var count = 0
                for (c in sorted) {
                    evap += lakeEvaporationM(climate, c) * area
                    count++
                    if (evap >= inflow) break
                }
                val full = evap < inflow
                val level = if (full) spill[sorted[0]] else elevation[sorted[count - 1]]
                for (k in 0 until count) {
                    val c = sorted[k]
                    lakeLevel[c] = level
                    closed[c] = !full
                    netWeight[c] = (climate.annualPrecip[c] / 1000f - lakeEvaporationM(climate, c).toFloat()) * area
                }
            }

            // Discharge, with evaporation from lakes taken out and flows floored at zero.
            val q = netWeight.copyOf()
            for (k in n - 1 downTo 0) {
                val i = drainage.stack[k]; val r = drainage.receiver[i]
                if (r != i) q[r] += max(q[i], 0f)
            }
            val discharge = FloatArray(n) { if (isSea[it]) 0f else (max(q[it], 0f) / SECONDS_PER_YEAR).toFloat() }
            return SurfaceWater(lakeLevel, closed, discharge, drainage)
        }

        private fun lakeEvaporationM(climate: Climate, i: Int): Double = 1.1 * climate.annualPet[i] / 1000.0
    }
}
