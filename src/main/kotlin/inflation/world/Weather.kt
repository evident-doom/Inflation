package inflation.world

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Stochastic weather around the climate normals. Anomalies live on a coarse grid (weather
 * systems are hundreds of km wide) and are interpolated to cells. Temperature and precipitation
 * anomalies are first-order autoregressive in time; a separate yearly anomaly makes some years
 * wet and others droughts. Snow accumulates below 0 °C and melts by a degree-day rule.
 */
class Weather(private val grid: Grid, private val climate: Climate, private val cfg: WorldConfig) {
    private val coarseKm = 50.0
    private val cw = (grid.width * grid.cellKm / coarseKm).toInt() + 2
    private val ch = (grid.height * grid.cellKm / coarseKm).toInt() + 2
    private val rnd = Random(cfg.seed * 31 + 7)

    private val tempAnomaly = FloatArray(cw * ch)
    private val rainAnomaly = FloatArray(cw * ch)
    private val yearAnomaly = FloatArray(cw * ch)
    private var anomalyYear = -1L

    var date = Date(0); private set
    /** Mean air temperature over the last step, °C. */
    val temperature = FloatArray(grid.size)
    /** Rain and snow that fell during the last step, mm water. */
    val precipitation = FloatArray(grid.size)
    /** Snow on the ground, mm water equivalent. */
    val snowpack = FloatArray(grid.size)
    /** Snowmelt released during the last step, mm. */
    val meltwater = FloatArray(grid.size)

    /** Advances the weather by one step of [WorldConfig.daysPerStep] days. */
    fun step() {
        val days = cfg.daysPerStep
        if (date.year != anomalyYear) {
            anomalyYear = date.year
            ar1(yearAnomaly, phi = 0.4, sigma = 0.3f)
        }
        val phi = exp(-days / 5.0) // weather systems persist about five days
        ar1(tempAnomaly, phi, sigma = 2.5f)
        ar1(rainAnomaly, phi, sigma = 0.8f)

        val m = date.month
        val next = (m + 1) % 12
        val f = (date.dayOfMonth - 1).toFloat() / Date.DAYS_PER_MONTH
        val tNow = climate.monthlyTemp[m]; val tNext = climate.monthlyTemp[next]
        val pNow = climate.monthlyPrecip[m]
        val stepShare = days.toFloat() / Date.DAYS_PER_MONTH
        val rainBias = (0.8f * 0.8f + 0.3f * 0.3f) / 2 // keeps the lognormal mean at the climate normal
        for (i in 0 until grid.size) {
            val x = grid.x(i) * grid.cellKm / coarseKm; val y = grid.y(i) * grid.cellKm / coarseKm
            val t = tNow[i] * (1 - f) + tNext[i] * f + sample(tempAnomaly, x, y)
            val p = pNow[i] * stepShare * exp(sample(rainAnomaly, x, y) - rainBias + sample(yearAnomaly, x, y))
            temperature[i] = t
            precipitation[i] = p
            if (t < 0) {
                snowpack[i] += p
                meltwater[i] = 0f
            } else {
                val melt = min(snowpack[i], 4f * t * days) // 4 mm per degree-day
                snowpack[i] -= melt
                meltwater[i] = melt
            }
        }
        date = date.plusDays(days)
    }

    private fun ar1(field: FloatArray, phi: Double, sigma: Float) {
        val noise = FloatArray(field.size) { rnd.nextGaussian().toFloat() }
        // Light smoothing so neighbouring coarse cells are correlated.
        for (y in 0 until ch) for (x in 0 until cw) {
            var s = 0f; var c = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until cw && yy in 0 until ch) { s += noise[yy * cw + xx]; c++ }
            }
            val i = y * cw + x
            field[i] = (phi * field[i] + sqrt(1 - phi * phi) * sigma * s / sqrt(c.toDouble())).toFloat()
        }
    }

    private fun sample(field: FloatArray, x: Double, y: Double): Float {
        val x0 = min(x.toInt(), cw - 2); val y0 = min(y.toInt(), ch - 2)
        val fx = (x - x0).toFloat(); val fy = (y - y0).toFloat()
        val a = field[y0 * cw + x0]; val b = field[y0 * cw + x0 + 1]
        val c = field[(y0 + 1) * cw + x0]; val d = field[(y0 + 1) * cw + x0 + 1]
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
    }

    private fun Random.nextGaussian(): Double {
        // Box–Muller
        val u = max(nextDouble(), 1e-12)
        return sqrt(-2 * kotlin.math.ln(u)) * kotlin.math.cos(2 * Math.PI * nextDouble())
    }
}
