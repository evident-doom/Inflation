package inflation.world

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/** A regional climate shock. Strength is in °C for temperature events and a rain factor for droughts. */
class ClimateEvent(
    val kind: Kind,
    val startDay: Long,
    val endDay: Long,
    /** Centre, in cells. */
    val x: Double,
    val y: Double,
    val radiusKm: Double,
    val strength: Double,
) {
    enum class Kind { DROUGHT, COLD_WINTER, HEAT_WAVE, VOLCANIC_WINTER }

    fun activeOn(day: Long) = day in startDay until endDay
}

/**
 * Stochastic weather around the climate normals. Anomalies live on a coarse grid (weather
 * systems are hundreds of km wide) and are interpolated to cells.
 *
 * Variability at several time scales:
 * - weather: temperature and rain anomalies persisting about five days;
 * - year to year: some years wetter or drier everywhere;
 * - a quasi-periodic oscillation of roughly five years and a slower decadal swing, each with
 *   its own spatial pattern, so regions take turns being favoured;
 * - shocks: multi-year droughts, severe winters, summer heat waves, and volcanic winters after
 *   large eruptions (VEI 5 and up cool the region by up to about 1 °C for a couple of years).
 *
 * Snow accumulates below 0 °C and melts by a degree-day rule.
 */
class Weather(private val grid: Grid, private val climate: Climate, private val cfg: WorldConfig) {
    private val coarseKm = 50.0
    private val cw = (grid.width * grid.cellKm / coarseKm).toInt() + 2
    private val ch = (grid.height * grid.cellKm / coarseKm).toInt() + 2
    private val rnd = Random(cfg.seed * 31 + 7)

    private val tempAnomaly = FloatArray(cw * ch)
    private val rainAnomaly = FloatArray(cw * ch)
    private val yearAnomaly = FloatArray(cw * ch)
    private val tempShock = FloatArray(cw * ch)
    private val rainShock = FloatArray(cw * ch)
    private val oscPatternT = smoothPattern()
    private val oscPatternP = smoothPattern()
    private val decPattern = smoothPattern()
    private var anomalyYear = -1L

    /** Interannual oscillation index (quasi-periodic, about 5 years) and its previous value. */
    var oscillation = 0.0; private set
    private var oscillationPrev = 0.0
    /** Decadal index, persistent from year to year. */
    var decadal = 0.0; private set
    /** Regional cooling from volcanic aerosols, °C. */
    var volcanicCooling = 0.0; private set

    val events = ArrayList<ClimateEvent>()

    var date = Date(0); private set
    /** Month (0..11) of the step most recently simulated. */
    var lastMonth = 0; private set
    /** Mean air temperature over the last step, °C. */
    val temperature = FloatArray(grid.size)
    /** Rain and snow that fell during the last step, mm water. */
    val precipitation = FloatArray(grid.size)
    /** Snow on the ground, mm water equivalent. */
    val snowpack = FloatArray(grid.size)
    /** Snowmelt released during the last step, mm. */
    val meltwater = FloatArray(grid.size)

    fun activeEvents(): List<ClimateEvent> = events.filter { it.activeOn(date.absoluteDay) }

    /** A large eruption's aerosols cool the region for about two years. */
    fun volcanicForcing(vei: Int, x: Int, y: Int) {
        if (vei < 5) return
        val cooling = if (vei == 5) 0.4 else 1.0
        volcanicCooling += cooling
        events += ClimateEvent(ClimateEvent.Kind.VOLCANIC_WINTER, date.absoluteDay, date.absoluteDay + 720, x.toDouble(), y.toDouble(), 5000.0, -cooling)
    }

    /** Advances the weather by one step of [WorldConfig.daysPerStep] days. */
    fun step() {
        val days = cfg.daysPerStep
        if (date.year != anomalyYear) {
            anomalyYear = date.year
            newYear()
        }
        val phi = exp(-days / 5.0) // weather systems persist about five days
        ar1(tempAnomaly, phi, sigma = 2.5f)
        ar1(rainAnomaly, phi, sigma = 0.8f)
        volcanicCooling *= exp(-days / 360.0)
        shocks()

        val m = date.month
        lastMonth = m
        val next = (m + 1) % 12
        val f = (date.dayOfMonth - 1).toFloat() / Date.DAYS_PER_MONTH
        val tNow = climate.monthlyTemp[m]; val tNext = climate.monthlyTemp[next]
        val pNow = climate.monthlyPrecip[m]
        val stepShare = days.toFloat() / Date.DAYS_PER_MONTH
        val rainBias = (0.8f * 0.8f + 0.3f * 0.3f) / 2 // keeps the lognormal mean at the climate normal
        val cooling = volcanicCooling.toFloat()
        for (i in 0 until grid.size) {
            val x = grid.x(i) * grid.cellKm / coarseKm; val y = grid.y(i) * grid.cellKm / coarseKm
            val t = tNow[i] * (1 - f) + tNext[i] * f + sample(tempAnomaly, x, y) + sample(tempShock, x, y) - cooling
            val p = pNow[i] * stepShare * exp(sample(rainAnomaly, x, y) - rainBias + sample(yearAnomaly, x, y) + sample(rainShock, x, y))
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

    private fun newYear() {
        ar1(yearAnomaly, phi = 0.4, sigma = 0.25f)
        // AR(2) with complex roots: a damped oscillation of about five years.
        val r = 0.8; val period = 5.0
        val next = 2 * r * cos(2 * PI / period) * oscillation - r * r * oscillationPrev + 0.75 * gaussian()
        oscillationPrev = oscillation; oscillation = next
        decadal = 0.9 * decadal + sqrt(1 - 0.81) * gaussian()

        val day = date.absoluteDay
        fun place() = Pair(rnd.nextDouble() * grid.width, rnd.nextDouble() * grid.height)
        if (rnd.nextDouble() < 0.015) { // a multi-year drought, a few per century
            val (x, y) = place()
            val years = 2 + rnd.nextInt(7)
            events += ClimateEvent(ClimateEvent.Kind.DROUGHT, day, day + years * Date.DAYS_PER_YEAR, x, y, 200.0 + 300 * rnd.nextDouble(), 0.55 + 0.2 * rnd.nextDouble())
        }
        if (rnd.nextDouble() < 0.06) { // a severe winter: weeks of arctic air
            val (x, y) = place()
            val start = day + 270 + rnd.nextInt(70)
            events += ClimateEvent(ClimateEvent.Kind.COLD_WINTER, start, start + 15 + rnd.nextInt(30), x, y, 300.0 + 300 * rnd.nextDouble(), -(5.0 + 4 * rnd.nextDouble()))
        }
        if (rnd.nextDouble() < 0.08) { // a summer heat wave
            val (x, y) = place()
            val start = day + 100 + rnd.nextInt(60)
            events += ClimateEvent(ClimateEvent.Kind.HEAT_WAVE, start, start + 12 + rnd.nextInt(20), x, y, 250.0 + 300 * rnd.nextDouble(), 4.0 + 4 * rnd.nextDouble())
        }
    }

    /** Coarse temperature and rain anomalies from the slow modes and the active shocks. */
    private fun shocks() {
        val day = date.absoluteDay
        val active = events.filter { it.activeOn(day) && it.kind != ClimateEvent.Kind.VOLCANIC_WINTER }
        for (cy in 0 until ch) for (cx in 0 until cw) {
            val c = cy * cw + cx
            var t = 0.7 * oscillation * oscPatternT[c] + 0.4 * decadal * decPattern[c]
            var p = 0.15 * oscillation * oscPatternP[c] + 0.12 * decadal * decPattern[c]
            for (e in active) {
                val km = hypot(cx * coarseKm - e.x * grid.cellKm, cy * coarseKm - e.y * grid.cellKm)
                val w = exp(-(km / e.radiusKm).let { it * it })
                when (e.kind) {
                    ClimateEvent.Kind.DROUGHT -> { p += ln(e.strength) * w; t += 0.8 * w }
                    ClimateEvent.Kind.HEAT_WAVE -> { t += e.strength * w; p -= 0.7 * w }
                    else -> t += e.strength * w
                }
            }
            tempShock[c] = t.toFloat(); rainShock[c] = p.toFloat()
        }
    }

    /** A smooth random field on the coarse grid scaled to about -1..1. */
    private fun smoothPattern(): FloatArray {
        var f = FloatArray(cw * ch) { rnd.nextFloat() * 2 - 1 }
        repeat(6) {
            val g = FloatArray(f.size)
            for (y in 0 until ch) for (x in 0 until cw) {
                var s = 0f; var k = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx; val yy = y + dy
                    if (xx in 0 until cw && yy in 0 until ch) { s += f[yy * cw + xx]; k++ }
                }
                g[y * cw + x] = s / k
            }
            f = g
        }
        val m = f.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-6f)
        return FloatArray(f.size) { f[it] / m }
    }

    private fun ar1(field: FloatArray, phi: Double, sigma: Float) {
        val noise = FloatArray(field.size) { gaussian().toFloat() }
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

    private fun gaussian(): Double {
        // Box–Muller
        val u = max(rnd.nextDouble(), 1e-12)
        return sqrt(-2 * ln(u)) * cos(2 * PI * rnd.nextDouble())
    }
}
