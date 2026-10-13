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
 * - wind: each week a weather [Regime] (westerly fronts, a cold northerly, a warm southerly, a
 *   blocking high, the summer high) with its wind; the wind sets where rain falls (windward slopes
 *   wet, lee sides dry) and the regime how warm and how stormy the week is (see [Winds]).
 *
 * Snow accumulates below 0 °C and melts by a degree-day rule.
 *
 * [elevation] gives the terrain the wind meets; without it, rain does not depend on the wind.
 */
class Weather(private val places: Places, private val cfg: WorldConfig) {
    /** Weather on every cell of [grid]; with [elevation] the wind's rain follows the terrain. */
    constructor(grid: Grid, climate: Climate, cfg: WorldConfig, elevation: FloatArray? = null) :
        this(Places.of(grid, climate, cfg, elevation), cfg)

    /**
     * The places the weather is kept for: anything from every fine cell to a few height bands of
     * each hex. Each has a position, a latitude, its monthly normals and how much rain each wind
     * direction brings it against calm air.
     */
    class Places(
        val size: Int,
        /** Position of each place, km from the north-west corner. */
        val xKm: FloatArray,
        val yKm: FloatArray,
        val latitude: DoubleArray,
        /** [month][place]: temperature °C, rain and snow mm. */
        val monthlyTemp: Array<FloatArray>,
        val monthlyPrecip: Array<FloatArray>,
        /** [wind sector][place]: orographic rain factor (see [Winds.rainFactor]); null for none. */
        val sector: Array<FloatArray>?,
        /** Size of the map, km, and of one fine cell, km (events are placed in fine cells). */
        val widthKm: Double,
        val heightKm: Double,
        val cellKm: Double,
    ) {
        companion object {
            fun of(grid: Grid, climate: Climate, cfg: WorldConfig, elevation: FloatArray?): Places = Places(
                grid.size,
                FloatArray(grid.size) { (grid.x(it) * grid.cellKm).toFloat() },
                FloatArray(grid.size) { (grid.y(it) * grid.cellKm).toFloat() },
                DoubleArray(grid.size) { climate.latitude[grid.y(it)] },
                climate.monthlyTemp, climate.monthlyPrecip,
                elevation?.let { Winds.sectors(grid, it) },
                grid.width * grid.cellKm, grid.height * grid.cellKm, grid.cellKm,
            )
        }
    }

    private val n = places.size
    private val climate = places
    private val coarseKm = 50.0
    private val cw = (places.widthKm / coarseKm).toInt() + 2
    private val ch = (places.heightKm / coarseKm).toInt() + 2
    private val rnd = Random(cfg.seed * 31 + 7)

    private val tempAnomaly = FloatArray(cw * ch)
    private val rainAnomaly = FloatArray(cw * ch)
    private val yearAnomaly = FloatArray(cw * ch)
    private val tempShock = FloatArray(cw * ch)
    private val rainShock = FloatArray(cw * ch)
    /** Eddies in the wind, m/s east and south, on the coarse grid. */
    private val eddyU = FloatArray(cw * ch)
    private val eddyV = FloatArray(cw * ch)
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

    var date = Date(Date.START_DAY.toLong()); private set
    /** Month (0..11) of the step most recently simulated. */
    var lastMonth = Date.START_DAY / Date.DAYS_PER_MONTH; private set
    /** Mean air temperature over the last step, °C. */
    val temperature = FloatArray(n)
    /** Rain and snow that fell during the last step, mm water. */
    val precipitation = FloatArray(n)
    /** Snow on the ground, mm water equivalent. */
    val snowpack = FloatArray(n)
    /** Snowmelt released during the last step, mm. */
    val meltwater = FloatArray(n)

    val winds = Winds(places.sector)
    /** The weather pattern of the last step, and of the coming one (already drawn, see [prepare]). */
    var regime = Regime.WESTERLY; private set
    var upcomingRegime = Regime.WESTERLY; private set
    /** How far the jet stream lies north (+) or south (-) of its seasonal mean, degrees. */
    private var meander = 0.0
    /** Wind over the last step and the coming one, m/s towards the east and the south, on the coarse grid. */
    private val windU = FloatArray(cw * ch)
    private val windV = FloatArray(cw * ch)
    private val nextU = FloatArray(cw * ch)
    private val nextV = FloatArray(cw * ch)
    /** The coming step's temperature and rain, worked out a step ahead. */
    private val nextTemp = FloatArray(n)
    private val nextPrecip = FloatArray(n)
    /** Seasonal mean of the wind's rain factor per place, for the month in [normMonth]. */
    private val norm = FloatArray(n)
    private var normMonth = -1

    private fun cx(i: Int) = places.xKm[i] / coarseKm
    private fun cy(i: Int) = places.yKm[i] / coarseKm
    /** Wind over the last step at cell [i], m/s towards the east. */
    fun windEast(i: Int): Float = sample(windU, cx(i), cy(i))
    /** Wind over the last step at cell [i], m/s towards the south. */
    fun windSouth(i: Int): Float = sample(windV, cx(i), cy(i))

    /** How many droughts are under way. */
    fun droughtCount() = events.count { it.kind == ClimateEvent.Kind.DROUGHT && it.activeOn(date.absoluteDay) }

    /** Droughts under way: how many, and the share of their rain they bring. */
    fun droughts(): String {
        val now = events.filter { it.kind == ClimateEvent.Kind.DROUGHT && it.activeOn(date.absoluteDay) }
        return if (now.isEmpty()) "none" else "${now.size} (" + now.joinToString(", ") { "%.0f%% rain over %.0f km".format(100 * it.strength, it.radiusKm) } + ")"
    }

    /** A large eruption's aerosols cool the region for about two years. */
    fun volcanicForcing(vei: Int, x: Int, y: Int) {
        if (vei < 5) return
        val cooling = if (vei == 5) 0.4 else 1.0
        volcanicCooling += cooling
        events += ClimateEvent(ClimateEvent.Kind.VOLCANIC_WINTER, date.absoluteDay, date.absoluteDay + 720, x.toDouble(), y.toDouble(), 5000.0, -cooling)
    }

    private var prepared = false

    /** Where the year stands, -1 at midwinter to 1 at midsummer, as in the climate normals. */
    private fun season(d: Date) = cos(2 * PI * (d.dayOfYear - Climate.WARMEST_DAY) / Date.DAYS_PER_YEAR)

    /**
     * Draws the weather for the step starting on [date]: done one step ahead, so that the coming
     * week's weather is known before it arrives (see [forecastTemp], [forecastRain]).
     */
    private fun prepare() {
        val days = cfg.daysPerStep
        if (date.year != anomalyYear) {
            anomalyYear = date.year
            newYear()
        }
        val phi = exp(-days / 5.0) // weather systems persist about five days
        ar1(tempAnomaly, phi, sigma = TEMP_SIGMA)
        ar1(rainAnomaly, phi, sigma = RAIN_SIGMA)
        volcanicCooling *= exp(-days / 360.0)
        shocks()
        drawWind()
        if (date.month != normMonth) normalise()
        val m = date.month; val next = (m + 1) % 12
        val f = (date.dayOfMonth - 1).toFloat() / Date.DAYS_PER_MONTH
        val tNow = climate.monthlyTemp[m]; val tNext = climate.monthlyTemp[next]
        val pNow = climate.monthlyPrecip[m]
        val stepShare = days.toFloat() / Date.DAYS_PER_MONTH
        val s = season(date)
        val warmth = (winds.warmth(upcomingRegime, s) - winds.meanWarmth(s) - volcanicCooling).toFloat()
        val storm = upcomingRegime.storminess
        java.util.stream.IntStream.range(0, n).parallel().forEach { i ->
            val x = cx(i); val y = cy(i)
            nextTemp[i] = tNow[i] * (1 - f) + tNext[i] * f + sample(tempAnomaly, x, y) + sample(tempShock, x, y) + warmth
            val wind = storm * winds.rainFactor(i, sample(nextU, x, y), sample(nextV, x, y)) / norm[i]
            nextPrecip[i] = pNow[i] * stepShare * wind * exp(sample(rainAnomaly, x, y) - RAIN_BIAS + sample(yearAnomaly, x, y) + sample(rainShock, x, y))
        }
        prepared = true
    }

    /** The coming step's regime and wind: a regime often persists, else one is drawn for the season. */
    private fun drawWind() {
        val days = cfg.daysPerStep
        val s = season(date)
        if (rnd.nextDouble() >= REGIME_PERSISTENCE) {
            val weights = Regime.entries.map { winds.weight(it, s) }
            var u = rnd.nextDouble() * weights.sum()
            upcomingRegime = Regime.entries.first { r -> u -= weights[r.ordinal]; u <= 0 }
        }
        val keep = exp(-days / 10.0)
        meander = keep * meander + sqrt(1 - keep * keep) * 2.0 * gaussian()
        val jet = winds.jetLatitude(s) + meander
        ar1(eddyU, exp(-days / 7.0), EDDY_SIGMA)
        ar1(eddyV, exp(-days / 7.0), EDDY_SIGMA)
        val base = FloatArray(2)
        for (y in 0 until ch) {
            winds.baseWind(upcomingRegime, cfg.centreLatitude - (y * coarseKm - places.heightKm / 2) / WorldConfig.KM_PER_DEGREE, jet, base)
            for (x in 0 until cw) {
                val c = y * cw + x
                nextU[c] = base[0] + eddyU[c]; nextV[c] = base[1] + eddyV[c]
            }
        }
    }

    /** The seasonal mean of the wind's rain factor this month, so that rain keeps to the normals on average. */
    private fun normalise() {
        normMonth = date.month
        val s = cos(2 * PI * (date.month * Date.DAYS_PER_MONTH + 15 - Climate.WARMEST_DAY) / Date.DAYS_PER_YEAR)
        val tmp = ThreadLocal.withInitial { FloatArray(2) }
        java.util.stream.IntStream.range(0, n).parallel().forEach { i ->
            norm[i] = winds.normalFactor(i, places.latitude[i], s, tmp.get())
        }
    }

    /** Mean air temperature the coming step will bring at [i], °C: already settled, as weather is drawn a step ahead. */
    fun forecastTemp(i: Int): Float {
        if (!prepared) prepare()
        return nextTemp[i]
    }

    /** Rain and snow the coming step will bring at [i], mm. */
    fun forecastRain(i: Int): Float {
        if (!prepared) prepare()
        return nextPrecip[i]
    }

    /** Advances the weather by one step of [WorldConfig.daysPerStep] days, then draws the next step's weather. */
    fun step() {
        val days = cfg.daysPerStep
        if (!prepared) prepare()
        lastMonth = date.month
        regime = upcomingRegime
        nextU.copyInto(windU); nextV.copyInto(windV)
        java.util.stream.IntStream.range(0, n).parallel().forEach { i ->
            val t = nextTemp[i]
            val p = nextPrecip[i]
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
        prepare()
    }

    private fun newYear() {
        ar1(yearAnomaly, phi = 0.4, sigma = 0.25f)
        // AR(2) with complex roots: a damped oscillation of about five years.
        val r = 0.8; val period = 5.0
        val next = 2 * r * cos(2 * PI / period) * oscillation - r * r * oscillationPrev + 0.75 * gaussian()
        oscillationPrev = oscillation; oscillation = next
        decadal = 0.9 * decadal + sqrt(1 - 0.81) * gaussian()

        val day = date.absoluteDay
        fun place() = Pair(rnd.nextDouble() * places.widthKm / places.cellKm, rnd.nextDouble() * places.heightKm / places.cellKm)
        if (rnd.nextDouble() < 0.015) { // a multi-year drought, a few per century
            val (x, y) = place()
            val years = 2 + rnd.nextInt(7)
            events += ClimateEvent(ClimateEvent.Kind.DROUGHT, day, day + years * Date.DAYS_PER_YEAR, x, y, 200.0 + 300 * rnd.nextDouble(), 0.55 + 0.2 * rnd.nextDouble())
        }
        if (rnd.nextDouble() < 0.12) { // a drought year: a dry growing season over a region, about one year in eight in any one place
            val (x, y) = place()
            events += ClimateEvent(ClimateEvent.Kind.DROUGHT, day, day + Date.DAYS_PER_YEAR, x, y, 300.0 + 400 * rnd.nextDouble(), 0.5 + 0.2 * rnd.nextDouble())
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
                val km = hypot(cx * coarseKm - e.x * places.cellKm, cy * coarseKm - e.y * places.cellKm)
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

    companion object {
        /** Spread of the week-to-week temperature (°C) and log-rain anomalies, on top of the regimes. */
        const val TEMP_SIGMA = 1.5f
        const val RAIN_SIGMA = 0.35f
        /** Keeps the lognormal rain anomalies' mean at 1 (year-to-year sigma about 0.3 included). */
        const val RAIN_BIAS = (RAIN_SIGMA * RAIN_SIGMA + 0.3f * 0.3f) / 2
        /** Chance a regime holds into the next week. */
        const val REGIME_PERSISTENCE = 0.5
        /** Spread of the wind's eddies, m/s. */
        const val EDDY_SIGMA = 2.5f
    }
}
