package inflation.world

import kotlin.math.max
import kotlin.math.min

/**
 * Rivers and floods as the weather changes. Each land cell has a soil bucket and a groundwater
 * store. Rain and snowmelt wet the soil; plants and the sun take water back at a rate that falls
 * as the soil dries; water beyond the soil's capacity runs off, part of it at once and part
 * through groundwater, which feeds rivers slowly between rains. Runoff is routed down the river
 * network every step.
 *
 * When a river carries more than bankfull flow it overtops its banks and floods the land that
 * lies within the flood stage of it (see [Floodplain]).
 *
 * Water reaches the river mouth within one step; real flood waves take days to travel, so peaks
 * on the largest rivers come a little early and sharp.
 */
class LiveWater(private val world: World) {
    private val grid = world.grid
    private val n = grid.size
    private val days = world.config.daysPerStep
    private val stepSeconds = days * 86_400.0
    private val cellArea = grid.cellAreaM2.toFloat()

    /** Plant-available water in the soil, mm. */
    val soil = FloatArray(n)
    /** Groundwater, mm. */
    val groundwater = FloatArray(n)
    /** River discharge during the last step, m^3/s. */
    val discharge = FloatArray(n)
    /** Flood water depth on land during the last step, m. */
    val flood = FloatArray(n)
    private val receiver = world.water.drainage.receiver.copyOf()
    private val order = world.water.drainage.stack.copyOf()
    private val volume = FloatArray(n)

    init {
        // Start near the long-term state: moist soil, groundwater at its mean level.
        val stepsPerYear = Date.stepsPerYear(days)
        for (i in 0 until n) {
            if (world.isSea[i]) continue
            soil[i] = SOIL_CAPACITY * 0.6f
            groundwater[i] = RECHARGE * world.climate.runoff[i] / stepsPerYear / BASEFLOW
        }
        System.arraycopy(world.water.discharge, 0, discharge, 0, n)
    }

    /** Advances one step using the weather that just happened in [weather]. */
    fun step(weather: Weather) {
        val pet = world.climate.monthlyPet[weather.lastMonth]
        val share = days.toFloat() / Date.DAYS_PER_MONTH
        val water = world.water
        for (i in 0 until n) {
            if (world.isSea[i]) { volume[i] = 0f; continue }
            val rain = weather.rain(i)
            val input = rain + weather.meltwater[i]
            if (water.isLake(i)) {
                volume[i] = (input - 1.1f * pet[i] * share) / 1000f * cellArea
                continue
            }
            var s = soil[i] + input
            val et = min(s, pet[i] * share * min(1f, s / SOIL_CAPACITY))
            s -= et
            val excess = max(0f, s - SOIL_CAPACITY)
            soil[i] = s - excess
            val base = groundwater[i] * BASEFLOW
            groundwater[i] += excess * RECHARGE - base
            volume[i] = (excess * (1 - RECHARGE) + base) / 1000f * cellArea
        }
        route()

        // Floods: river stage above bankfull, spread over land lower than that stage.
        val fp = world.floodplain
        for (i in 0 until n) {
            val r = fp.riverOf[i]
            flood[i] = if (r < 0 || water.discharge[r] < RiverClass.STREAM.minDischarge) 0f
                else max(0f, Channel.floodStageM(discharge[r], water.discharge[r]) - fp.hand[i])
        }
    }

    private fun route() {
        for (k in n - 1 downTo 0) {
            val i = order[k]
            val v = volume[i]
            val r = receiver[i]
            if (r != i) volume[r] += max(v, 0f)
        }
        for (i in 0 until n) {
            discharge[i] = if (world.isSea[i]) 0f else (max(volume[i], 0f) / stepSeconds).toFloat()
        }
    }

    companion object {
        /** Water a loamy soil holds for plants over the root zone, mm. */
        const val SOIL_CAPACITY = 150f
        /** Share of excess water that soaks through to groundwater rather than running off at once. */
        const val RECHARGE = 0.4f
        /** Share of groundwater released to rivers each step. */
        const val BASEFLOW = 0.04f
    }
}
