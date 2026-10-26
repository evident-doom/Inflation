package inflation.world

import inflation.Config

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
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
/**
 * Groups of food-bearing trees whose mast crops boom and bust together ([Weather.mastCrop]), each apart from the others:
 * the deciduous oaks, beech and hazel of the temperate woods; the evergreen oaks, pistachio and wild almond of the
 * Mediterranean, the steppe and the dry scrub; the stone pines and hazel among the conifers.
 */
enum class MastTrees { TEMPERATE, DRY, CONIFER }

class Weather(@Transient private val places: Places, private val cfg: WorldConfig, tuning: inflation.Tuning = inflation.Tuning.config) {
    /** The usual mean temperature at place [p] in [month] (0-based), °C: the climate's, not this year's weather. */
    fun usualTemperature(month: Int, p: Place) = places.monthlyTemp[month][p.i]
    /** Weather on every cell of [grid]; with [elevation] the wind's rain follows the terrain. */
    constructor(grid: Grid, climate: Climate, cfg: WorldConfig, elevation: FloatArray? = null) :
        this(Places.of(grid, climate, cfg, elevation), cfg)

    /**
     * The places the weather is kept for: anything from every fine cell to the low and high ground
     * of each hex. Each has a position, a latitude, its monthly normals and how much rain each wind
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
        /** The sun on each place's shaded and sunny slopes ([Sunlight]); null for level ground everywhere. */
        val sunlight: Sunlight? = null,
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
    @Transient private val climate = places
    private val coarseKm = 50.0
    private val cw = (places.widthKm / coarseKm).toInt() + 2
    private val ch = (places.heightKm / coarseKm).toInt() + 2
    private var rnd = Random(cfg.seed * 31 + 7)

    /** Draws the weather's turns (regimes, droughts) afresh from [replicate]: a run carried on from a saved world then meets other years' weather. */
    fun reseed(replicate: Long) {
        rnd = Random(cfg.seed * 31 + 7 + replicate * 0x9E3779B97F4A7C15uL.toLong())
        mastRnd = Random(cfg.seed * 37 + 11 + replicate * 0x9E3779B97F4A7C15uL.toLong())
    }

    /**
     * This year's mast crop against an average year's, for each group of food-bearing trees ([MastTrees]), over all the
     * land at once ([inflation.ecology.HexFood.mast]): oaks bear in boom and bust years, a median population CV of [MAST_CV]
     * (Schermer et al. 2019, Ecol Lett 22:98: 30 French oak stands over 14 years), in step within a group over areas the
     * size of this land (seed crops went out of step only beyond some 2,000 km, USGS, a transcontinental North American
     * tree), but not between groups: crops of different genera keep little in step (Koenig & Knops 1998 and later,
     * from memory), so one group's bust year can be another's boom. Each group draws a lognormal of mean 1 each year,
     * from its own stream so that the weather draws as before.
     */
    val mastCrop = FloatArray(MastTrees.entries.size) { 1f }
    /** This year's mast crop of the trees of group [t] ([mastCrop]). */
    fun mastCrop(t: MastTrees) = mastCrop[t.ordinal]
    private var mastRnd = Random(cfg.seed * 37 + 11)

    /** How much a place's rain swings from year to year against the average: most in the desert (bust years), least where it is wet. */
    @Transient private val yearSwing = FloatArray(n) { i ->
        val annual = (0 until Date.MONTHS_PER_YEAR).sumOf { places.monthlyPrecip[it][i].toDouble() }.toFloat()
        0.7f + 1.3f * (1f - annual / 600f).coerceIn(0f, 1f)
    }
    private val tempAnomaly = FloatArray(cw * ch)
    private val rainAnomaly = FloatArray(cw * ch)
    private val yearAnomaly = FloatArray(cw * ch)
    private val tempShock = FloatArray(cw * ch)
    private val rainShock = FloatArray(cw * ch)
    /** Eddies in the wind, m/s east and south, on the coarse grid. */
    private val eddyU = FloatArray(cw * ch)
    private val eddyV = FloatArray(cw * ch)
    @Transient private val oscPatternT = smoothPattern()
    @Transient private val oscPatternP = smoothPattern()
    @Transient private val decPattern = smoothPattern()
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
    /** Begins a world started afresh on [day] of the year ([inflation.Tuning.startDay]); a save loaded after carries its own date. */
    fun begin(day: Int) { date = Date(day.toLong()); lastMonth = day / Date.DAYS_PER_MONTH }
    /** Month (0..11) of the step most recently simulated. */
    var lastMonth = Date.START_DAY / Date.DAYS_PER_MONTH; private set
    /** Mean air temperature over the last step, °C. */
    val temperature = FloatArray(n)
    /** Rain and snow that fell during the last step, mm water. */
    val precipitation = FloatArray(n)
    /** What fell at [p] in the last step as rain, mm water: all of it unless it [snows] there. */
    fun rain(p: Int) = if (snows(temperature[p])) 0f else precipitation[p]
    /** What fell at [p] in the last step as snow, mm water. */
    fun snowfall(p: Int) = if (snows(temperature[p])) precipitation[p] else 0f
    /** Snow on the ground, mm water equivalent. */
    val snowpack = FloatArray(n)
    /** Snowmelt released during the last step, mm. */
    val meltwater = FloatArray(n)
    /**
     * The snow and its melt on the sunny slopes and on the shaded ones (facing the pole: [Sunlight.shade] of
     * the place), mm. Snow melts by the warmth of the air and by the sun on it (Hock 1999), so it lies longer
     * on the shaded slopes; [snowpack] and [meltwater] are the place's mean.
     */
    val snowSun = FloatArray(n); val snowShade = FloatArray(n)
    val meltSun = FloatArray(n); val meltShade = FloatArray(n)

    @Transient val winds = Winds(places.sector)
    /**
     * The weather pattern of the last step beyond the summer high's reach, and of the coming one (already drawn, see
     * [prepare]); [regimeAt] gives the pattern at a latitude.
     */
    var regime = Regime.WESTERLY; private set
    var upcomingRegime = Regime.WESTERLY; private set
    /** Latitude, °N, south of which the summer high held over the last step, and will over the coming one. */
    private var highEdge = -90.0
    private var upcomingHighEdge = -90.0
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

    /** The weather pattern of the last step at latitude [lat], °N. */
    fun regimeAt(lat: Double) = if (lat < highEdge) Regime.SUMMER_HIGH else regime

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
    private fun season(d: Date) = Climate.seasonCos(d.dayOfYear.toDouble())

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
        // The monthly normals stand for mid-month: before the 16th the step lies between last month and this.
        val m = date.month
        val pos = (date.dayOfMonth - 15.5f) / Date.DAYS_PER_MONTH
        val (from, to) = if (pos >= 0f) m to (m + 1) % Date.MONTHS_PER_YEAR else (m + Date.MONTHS_PER_YEAR - 1) % Date.MONTHS_PER_YEAR to m
        val f = if (pos >= 0f) pos else 1f + pos
        val tNow = climate.monthlyTemp[from]; val tNext = climate.monthlyTemp[to]
        val pNow = climate.monthlyPrecip[m]
        val stepShare = days.toFloat() / Date.DAYS_PER_MONTH
        val s = season(date)
        val regimes = winds.at(s)
        java.util.stream.IntStream.range(0, n).parallel().forEach { i ->
            val x = cx(i); val y = cy(i)
            val lat = places.latitude[i]
            val here = if (lat < upcomingHighEdge) Regime.SUMMER_HIGH else upcomingRegime
            val warmth = (winds.warmth(here, s) - regimes.meanWarmth(lat) - volcanicCooling).toFloat()
            nextTemp[i] = tNow[i] * (1 - f) + tNext[i] * f + sample(tempAnomaly, x, y) + sample(tempShock, x, y) + warmth
            val wind = here.storminess * winds.rainFactor(i, sample(nextU, x, y), sample(nextV, x, y)) / norm[i]
            // Rain comes in storms: a week is wet or dry, the wetter the month the likelier wet, and wet
            // spells hang together in space and time (the rain anomaly decides where); a wet week brings
            // the dry weeks' share too. Dry country swings more from year to year, with bust years.
            val pMonth = pNow[i] * wind
            val wetChance = 1f - exp(-pMonth / WET_MONTH_MM)
            // (Between the nodes of the coarse field the blend of their anomalies spreads less than at a node:
            // the samples are scaled back to a node's spread, so wet weeks are as likely midway as at a node.)
            val spread = spreadAt(x, y)
            // In dry country rain comes from scattered storms, a few tens of km across, that green one
            // stretch of desert and steppe and miss the next (as many as the month's rain asks for, placed
            // afresh each week): the patchy rain nomadic herds follow. Where the month is wet the rain still
            // comes with the wide weather systems.
            val arid = (1f - pMonth / ARID_MONTH_MM).coerceIn(0f, 1f)
            val zWide = sample(rainAnomaly, x, y) / spread / RAIN_SIGMA
            val z = if (arid <= 0f) zWide else (1f - arid) * zWide + arid * stormField(places.xKm[i], places.yKm[i])
            val wet = 1f / (1f + exp(-1.7f * z)) > 1f - wetChance
            val swing = yearSwing[i]
            nextPrecip[i] = if (!wet || wetChance <= 0f) 0f
                else pMonth * stepShare / wetChance * exp(swing * sample(yearAnomaly, x, y) / spread - (YEAR_SIGMA * swing).let { it * it } / 2 + sample(rainShock, x, y))
        }
        prepared = true
    }

    /**
     * The coming step's regimes and wind: a pattern often persists, else one is drawn for the season. How far north the
     * summer high reaches is drawn so that it holds [Winds.share] of the weeks at each latitude; beyond it one of the
     * other regimes blows, by their seasonal weights.
     */
    private fun drawWind() {
        val days = cfg.daysPerStep
        val s = season(date)
        if (rnd.nextDouble() >= REGIME_PERSISTENCE) {
            val others = Regime.entries.filter { it != Regime.SUMMER_HIGH }
            val weights = others.map { winds.weight(it, s) }
            var u = rnd.nextDouble() * weights.sum()
            upcomingRegime = others.indices.first { k -> u -= weights[k]; u <= 0 }.let { others[it] }
            // (The high holds where highShare × highReach(lat) exceeds one uniform draw: south of a line.)
            val reach = rnd.nextDouble() / winds.highShare(s)
            upcomingHighEdge = if (reach >= 1) -90.0 else Winds.HIGH_EDGE_LAT - reach * (Winds.HIGH_EDGE_LAT - Winds.HIGH_FULL_LAT)
        }
        val keep = exp(-days / 10.0)
        meander = keep * meander + sqrt(1 - keep * keep) * 2.0 * gaussian()
        val jet = winds.jetLatitude(s) + meander
        ar1(eddyU, exp(-days / 7.0), EDDY_SIGMA)
        ar1(eddyV, exp(-days / 7.0), EDDY_SIGMA)
        val base = FloatArray(2)
        for (y in 0 until ch) {
            val lat = cfg.centreLatitude - (y * coarseKm - places.heightKm / 2) / WorldConfig.KM_PER_DEGREE
            winds.baseWind(if (lat < upcomingHighEdge) Regime.SUMMER_HIGH else upcomingRegime, lat, jet, base)
            for (x in 0 until cw) {
                val c = y * cw + x
                nextU[c] = base[0] + eddyU[c]; nextV[c] = base[1] + eddyV[c]
            }
        }
    }

    /**
     * Where this step's scattered storms fall, as a value whose logistic gives the same chance of rain
     * as the wide field's: random afresh each step on cells of [STORM_KM], blended between cells.
     */
    private fun stormField(xKm: Float, yKm: Float): Float {
        val gx = xKm / STORM_KM; val gy = yKm / STORM_KM
        val x0 = kotlin.math.floor(gx).toInt(); val y0 = kotlin.math.floor(gy).toInt()
        val fx = gx - x0; val fy = gy - y0
        val step = date.absoluteDay / cfg.daysPerStep
        fun at(cx: Int, cy: Int): Float {
            var h = cfg.seed * -0x61c8864680b583ebL + step * 0x9E3779B97F4A7C15uL.toLong() + cx * 0x632BE59BD9B4E019L + cy * 0x165667B19E3779F9L
            h = (h xor (h ushr 31)) * -0x4b47d5b1L; h = h xor (h ushr 29)
            val u = ((h ushr 11).toDouble() / (1L shl 53).toDouble()).coerceIn(1e-6, 1 - 1e-6)
            return (kotlin.math.ln(u / (1 - u)) / 1.7).toFloat()
        }
        val v = (at(x0, y0) * (1 - fx) + at(x0 + 1, y0) * fx) * (1 - fy) + (at(x0, y0 + 1) * (1 - fx) + at(x0 + 1, y0 + 1) * fx) * fy
        // (Midway between cells the blend spreads less; scaled back so a storm is as likely there.)
        val spread = kotlin.math.sqrt(((1 - fx) * (1 - fx) + fx * fx) * ((1 - fy) * (1 - fy) + fy * fy))
        return v / spread.coerceAtLeast(0.3f)
    }

    /** The seasonal mean of the wind's rain factor this month, so that rain keeps to the normals on average. */
    private fun normalise() {
        normMonth = date.month
        val s = Climate.seasonCos(date.month * Date.DAYS_PER_MONTH + 15.0)
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
        highEdge = upcomingHighEdge
        nextU.copyInto(windU); nextV.copyInto(windV)
        val (shockT, shockRain) = shock()
        java.util.stream.IntStream.range(0, n).parallel().forEach { i ->
            val t = nextTemp[i] + shockT
            val p = nextPrecip[i] * shockRain
            temperature[i] = t
            precipitation[i] = p
            // (A world saved before the slopes were kept apart has its snow only as the place's mean.)
            if (snowpack[i] > 0f && snowSun[i] == 0f && snowShade[i] == 0f) { snowSun[i] = snowpack[i]; snowShade[i] = snowpack[i] }
            if (snows(t)) {
                snowSun[i] += p; snowShade[i] += p
                meltSun[i] = 0f; meltShade[i] = 0f
            } else {
                // 4 mm a degree-day on level ground, of which the sun's share follows the sun on the slope.
                val sun = places.sunlight
                val m = date.month
                meltSun[i] = min(snowSun[i], (MELT_AIR + (MELT_DEGREE_DAY - MELT_AIR) * (sun?.ratio?.get(0)?.get(m)?.get(i) ?: 1f)) * t * days)
                meltShade[i] = min(snowShade[i], (MELT_AIR + (MELT_DEGREE_DAY - MELT_AIR) * (sun?.ratio?.get(1)?.get(m)?.get(i) ?: 1f)) * t * days)
                snowSun[i] -= meltSun[i]; snowShade[i] -= meltShade[i]
            }
            val f = places.sunlight?.shade?.get(i) ?: 0f
            snowpack[i] = (1 - f) * snowSun[i] + f * snowShade[i]
            meltwater[i] = (1 - f) * meltSun[i] + f * meltShade[i]
        }
        date = date.plusDays(days)
        prepare()
    }

    /**
     * A long climate shock, like the Younger Dryas (about 12,900 years ago: some 1,200 years colder
     * and drier, setting in over decades and lifting quickly), set with
     * climateShock=startYear,years,degrees,rainShare ([inflation.Tuning.climateShock]; for example 20,1200,-4,0.7): the
     * temperature offset and the share of the rain, now; nothing when unset.
     */
    @Transient private val shockSpec = tuning.climateShock
    fun shock(): Pair<Float, Float> {
        val s = shockSpec ?: return 0f to 1f
        val (start, years, dT, rain) = s
        val y = date.absoluteDay / Date.DAYS_PER_YEAR.toDouble() - start
        if (y < 0 || y > years) return 0f to 1f
        // Setting in over a few decades, lifting within one.
        val k = minOf(1.0, y / 30.0, (years - y) / 10.0).coerceIn(0.0, 1.0)
        return (dT * k).toFloat() to (1.0 - (1.0 - rain) * k).toFloat()
    }

    /**
     * Extra years of stress, set with weather.stressYears=chance: each year, with this chance, a severe winter or a drought year
     * of the kinds below comes on top of the usual ones, so that training worlds meet the hard years often (0 when unset).
     */
    @Transient private val stressYears = tuning.weatherStressYears

    private fun newYear() {
        // Shocks that are over are let go.
        events.removeAll { it.endDay <= date.absoluteDay }
        ar1(yearAnomaly, phi = 0.4, sigma = YEAR_SIGMA)
        // AR(2) with complex roots: a damped oscillation of about five years.
        val r = 0.8; val period = 5.0
        val next = 2 * r * cos(2 * PI / period) * oscillation - r * r * oscillationPrev + 0.75 * gaussian()
        oscillationPrev = oscillation; oscillation = next
        decadal = 0.9 * decadal + sqrt(1 - 0.81) * gaussian()
        for (t in MastTrees.entries) mastCrop[t.ordinal] = mastFrom(inflation.gaussian(mastRnd))

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
            // Midwinter, from the solstice (the year's first day) into February.
            val start = day + rnd.nextInt(60)
            events += ClimateEvent(ClimateEvent.Kind.COLD_WINTER, start, start + 15 + rnd.nextInt(30), x, y, 300.0 + 300 * rnd.nextDouble(), -(5.0 + 4 * rnd.nextDouble()))
        }
        if (rnd.nextDouble() < 0.08) { // a summer heat wave
            val (x, y) = place()
            // High summer: July and August.
            val start = day + 180 + rnd.nextInt(60)
            events += ClimateEvent(ClimateEvent.Kind.HEAT_WAVE, start, start + 12 + rnd.nextInt(20), x, y, 250.0 + 300 * rnd.nextDouble(), 4.0 + 4 * rnd.nextDouble())
        }
        // (Drawn only when asked for, so that the usual weather is the same.)
        if (stressYears > 0.0 && rnd.nextDouble() < stressYears) {
            val (x, y) = place()
            if (rnd.nextBoolean()) {
                val start = day + rnd.nextInt(60)
                events += ClimateEvent(ClimateEvent.Kind.COLD_WINTER, start, start + 15 + rnd.nextInt(30), x, y, 300.0 + 300 * rnd.nextDouble(), -(5.0 + 4 * rnd.nextDouble()))
            } else events += ClimateEvent(ClimateEvent.Kind.DROUGHT, day, day + Date.DAYS_PER_YEAR, x, y, 300.0 + 400 * rnd.nextDouble(), 0.5 + 0.2 * rnd.nextDouble())
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

    /**
     * The spread of a [sample] at ([x], [y]) against a node's, 0..1: the four nodes it blends are
     * correlated as [ar1]'s smoothing over 3×3 cells makes them (6/9 side by side, 4/9 across the
     * corner), so midway between them the blend spreads about 0.8 as much as at a node.
     */
    private fun spreadAt(x: Double, y: Double): Float {
        val x0 = min(x.toInt(), cw - 2); val y0 = min(y.toInt(), ch - 2)
        val fx = (x - x0).toFloat(); val fy = (y - y0).toFloat()
        val a = (1 - fx) * (1 - fy); val b = fx * (1 - fy); val c = (1 - fx) * fy; val d = fx * fy
        val v = a * a + b * b + c * c + d * d + 2f * (6f / 9f) * (a * b + c * d + a * c + b * d) + 2f * (4f / 9f) * (a * d + b * c)
        return sqrt(v)
    }

    private fun gaussian(): Double = inflation.gaussian(rnd)

    companion object {
        /** The year-to-year swing of each group's mast crop ([mastCrop]): its coefficient of variation. */
        const val MAST_CV = 1.3
        /** The year's mast crop ([mastCrop]) for a standard normal draw [z]: a lognormal of mean 1 and coefficient of variation [MAST_CV]. */
        internal fun mastFrom(z: Double): Float {
            val sigma2 = ln(1.0 + MAST_CV * MAST_CV)
            return exp(sqrt(sigma2) * z - sigma2 / 2).toFloat()
        }
        /** Whether what falls at a week's mean air temperature [t], °C, falls as snow: the one rule for rain and snow. */
        fun snows(t: Float) = t < 0f
        /** Snow melted per degree-day above freezing on level ground, mm; the part of it that the air's warmth melts without the sun (Hock 1999: about 1.8). */
        const val MELT_DEGREE_DAY = 4f
        const val MELT_AIR = 1.8f
        /** Spread of the week-to-week temperature (°C) and log-rain anomalies, on top of the regimes. */
        const val TEMP_SIGMA = 1.5f
        const val RAIN_SIGMA = 0.35f
        /** Spread of the yearly log-rain anomaly at a node of the coarse field. */
        const val YEAR_SIGMA = 0.25f
        /** Rain in a month (mm) at which about two weeks in three are wet. */
        const val WET_MONTH_MM = 25f
        /** Scattered storms: below this month's rain, mm, rain comes as storms rather than with wide systems (wholly at none); their size, km. */
        const val ARID_MONTH_MM = 40f
        const val STORM_KM = 30f
        /** Chance a regime holds into the next week. */
        const val REGIME_PERSISTENCE = 0.5
        /** Spread of the wind's eddies, m/s. */
        const val EDDY_SIGMA = 2.5f
    }
}
