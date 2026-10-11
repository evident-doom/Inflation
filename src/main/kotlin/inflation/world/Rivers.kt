package inflation.world

import kotlin.math.max
import kotlin.math.pow

/** Size classes of running water by mean discharge, m^3/s. */
enum class RiverClass(val minDischarge: Float) {
    NONE(0f),
    CREEK(SurfaceWater.RIVER_THRESHOLD),
    STREAM(3f),
    RIVER(30f),
    GREAT_RIVER(300f);

    companion object {
        fun of(discharge: Float): RiverClass = entries.last { discharge >= it.minDischarge }
    }
}

/**
 * Channel shape from discharge by hydraulic geometry (Leopold & Maddock 1953): width and depth
 * grow as powers of flow. Coefficients are mid-range values for alluvial rivers.
 */
object Channel {
    fun widthM(discharge: Float): Float = 5f * discharge.coerceAtLeast(0f).pow(0.5f)
    fun depthM(discharge: Float): Float = 0.3f * discharge.coerceAtLeast(0f).pow(0.4f)
    /**
     * Flow that just fills the channel. Bankfull flow is typically several times the mean flow
     * (2–10x, higher for flashy rivers); it is exceeded about every year or two.
     */
    fun bankfull(meanDischarge: Float): Float = 4f * meanDischarge

    /** Below bankfull the stage is the channel depth; above it, water spreads out and rises more slowly. */
    fun floodStageM(discharge: Float, meanDischarge: Float): Float {
        val bf = bankfull(meanDischarge)
        if (discharge <= bf || bf <= 0f) return 0f
        return 1.5f * (depthM(discharge) - depthM(bf))
    }

    /**
     * A loaded dugout canoe or raft needs roughly a third of a metre of water. Reaches steeper
     * than 0.4% are rapids or falls that have to be portaged.
     */
    const val NAVIGABLE_DEPTH_M = 0.35f
    const val RAPIDS_GRADIENT = 0.004f
}

/**
 * Where rivers flood. Every land cell drains to some river cell; its height above that cell
 * (HAND, height above nearest drainage) is how high the river must rise to reach it. Low cells
 * near large rivers are the floodplain.
 */
class Floodplain(
    /** The river cell each land cell drains to, or -1 where it reaches the sea or a lake first. */
    val riverOf: IntArray,
    /** Height above that river cell, m. */
    val hand: FloatArray,
    /** Reached by a large flood, about two and a half times bankfull. */
    val isFloodplain: BooleanArray,
) {
    companion object {
        /**
         * Valley floors. Meandering rivers swing across their valleys and erode the sides, leaving
         * a flat floor of their own deposits a little above the channel. Floor width grows with
         * the square root of discharge: about 1 km for 15 m³/s, 5 km for 400 m³/s. Terrain
         * within that distance of a river is lowered to just above the river and covered with
         * alluvium. Returns true if any cell changed.
         */
        fun planeValleyFloors(grid: Grid, elevation: FloatArray, isSea: BooleanArray, water: SurfaceWater, sediment: FloatArray): Boolean {
            val n = grid.size
            val source = IntArray(n) { -1 }
            val queue = IntArray(n)
            var head = 0; var tail = 0
            fun halfWidthKm(q: Float) = 0.25f * kotlin.math.sqrt(q)
            for (i in 0 until n) {
                if (isSea[i] || water.isLake(i) || halfWidthKm(water.discharge[i]) < grid.cellKm) continue
                source[i] = i; queue[tail++] = i
            }
            var changed = false
            while (head < tail) {
                val i = queue[head++]
                val r = source[i]
                val reach = halfWidthKm(water.discharge[r])
                grid.forNeighbours(i) { j, _ ->
                    if (source[j] >= 0 || isSea[j] || water.isLake(j)) return@forNeighbours
                    val km = (kotlin.math.hypot((grid.x(j) - grid.x(r)).toDouble(), (grid.y(j) - grid.y(r)).toDouble()) * grid.cellKm).toFloat()
                    if (km > reach) return@forNeighbours
                    source[j] = r; queue[tail++] = j
                    val floor = elevation[r] + 1f + 0.3f * km
                    if (elevation[j] > floor) {
                        elevation[j] = floor
                        sediment[j] = max(sediment[j], 5f)
                        changed = true
                    }
                }
            }
            return changed
        }

        /** Ratio of a large, roughly once-in-decades flood to bankfull flow. */
        const val LARGE_FLOOD = 2.5f

        fun generate(grid: Grid, elevation: FloatArray, isSea: BooleanArray, water: SurfaceWater): Floodplain {
            val n = grid.size
            val d = water.drainage
            val riverOf = IntArray(n) { -1 }
            val hand = FloatArray(n) { Float.MAX_VALUE }
            val isRiver = { i: Int -> !isSea[i] && !water.isLake(i) && water.discharge[i] >= SurfaceWater.RIVER_THRESHOLD }
            // The stack lists receivers before donors, so each cell inherits from downstream.
            for (i in d.stack) {
                if (isSea[i] || water.isLake(i)) continue
                riverOf[i] = if (isRiver(i)) i else { val r = d.receiver[i]; if (r == i) -1 else riverOf[r] }
                if (riverOf[i] >= 0) hand[i] = max(0f, elevation[i] - elevation[riverOf[i]])
            }
            val plain = BooleanArray(n) { i ->
                val r = riverOf[i]
                r >= 0 && water.discharge[r] >= RiverClass.STREAM.minDischarge &&
                    hand[i] <= Channel.floodStageM(Channel.bankfull(water.discharge[r]) * LARGE_FLOOD, water.discharge[r])
            }
            return Floodplain(riverOf, hand, plain)
        }
    }
}
