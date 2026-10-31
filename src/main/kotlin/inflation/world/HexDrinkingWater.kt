package inflation.world

import kotlin.math.max
import kotlin.math.min

/** Drinking water on the places of [HexLand]: springs, rivers, lakes and pools, and soaks dug in dry beds. */
class HexDrinkingWater(land: HexLand) : HexLandPart(land) {
    /**
     * Springs and oases: groundwater from the hills comes up in dry country at the foot of slopes,
     * along faults and from limestone (as in the 1 km model), about one per [SPRING_KM2] where
     * groundwater is ample; in humid country only beyond an easy walk of a stream (seeps). Fine cells.
     */
    @Transient val spring = BooleanArray(grid.size)
    @Transient val oasis = BooleanArray(grid.size)
    /** Share of each place's ground within an easy walk ([DRINK_KM]) of a river or stream, a fresh lake, or a spring. */
    @Transient val nearRiver = FloatArray(n); @Transient val nearLake = FloatArray(n); @Transient val nearSpring = FloatArray(n)
    /** Mean distance from each place's ground to the nearest lasting water, km. */
    @Transient val waterKmMean = FloatArray(n)
    /**
     * Least distance from any of each place's ground to a river or stream, a fresh lake, a spring, km: from a camp pitched at
     * the water, as people pitch them where they can ([campKm]).
     */
    @Transient private val riverKmLeast = FloatArray(n); @Transient private val lakeKmLeast = FloatArray(n)
    @Transient private val springKmLeast = FloatArray(n)
    /** Share of each place's ground within an easy walk of a stream or dry bed (a valley draining [WADI_KM2] or more); area of those beds, m². */
    @Transient val nearBed = FloatArray(n)
    @Transient internal val bedM2 = FloatArray(n)
    /**
     * How full the sand and gravel under each place's beds is, 0..1: a flood fills it, and then the
     * water in it sinks away into the rock below at [SAND_DROP_M] a day, so it lies deeper the longer
     * since the beds last ran.
     */
    val sand = FloatArray(n) { 1f }
    /** Mean distance from each place's ground to the nearest stream, km: how far its ground water has to go. */
    @Transient internal val streamKm = FloatArray(n)
    /** This week: share of each place within an easy walk of drinkable water (0..1), and the mean walk to it, km. */
    val water = FloatArray(n)
    val waterKm = FloatArray(n)
    /**
     * This week: the walk to water from a camp pitched as near it as the place allows, km: none where pools stand; else to the
     * nearest of the streams still running, the fresh lakes and the springs still flowing; where none of those is in the place,
     * the place's walk to water ([waterKm]). What people carry home their water over (the ecology's fetching).
     */
    @Transient val campKm = FloatArray(n)
    /**
     * This week: share of each place within an easy walk of a stream bed where people can dig down to
     * water (a soak), 0..1: where the water table under the valley floor lies within [DIG_M] and the
     * ground is not frozen. Animals do not count it; a band pays for it in time (see the ecology).
     */
    val dug = FloatArray(n)
    /**
     * This week: 1 where snow lies soft enough for animals to eat it for their water (sheep and cattle wintered on snow
     * alone eat as much and keep their weight as those given water: Degen & Young 1981, 1990; Alberta and Ontario
     * extension trials), 0 where there is too little of it ([SNOW_EAT_MM]) or it is iced over: rain on snow freezes into a
     * crust they cannot get enough from, until fresh snow covers it ([iced]). People melt it at the fire instead: not counted.
     */
    val snowWater = FloatArray(n)
    /** 1 where the lying snow has been iced over by rain on it (see [snowWater]), until fresh snow falls on it or it is gone. */
    val iced = FloatArray(n)

    /**
     * This week: 1 where a well people have dug at the place's hex reaches below the water table ([inflation.ecology.Built.WELL]),
     * 0 elsewhere. Worked out from the bands' structures each week, before their weeks; animals do not draw from it.
     */
    @Transient val well = FloatArray(n)

    /** Share of place [p] within an easy walk of water an animal can drink this week: running or standing, or soft snow. */
    fun animalWater(p: Place) = max(water[p], snowWater[p])
    /** How far an animal at [p] goes for water this week, km: none where it eats the snow. */
    fun animalWaterKm(p: Place) = if (snowWater[p] > 0f) 0f else waterKm[p]

    /** Whether people at [p] have no water this week within [fetchKm] but what lies under the ground: in a soak or a well. */
    fun underground(p: Place, fetchKm: Float) = water[p] < 0.05f && waterKm[p] > fetchKm
    /** Whether people at [p] draw their water from a well this week, having none within [fetchKm] above the ground. */
    fun drawsFromWell(p: Place, fetchKm: Float) = underground(p, fetchKm) && well[p] >= 0.5f
    /** Whether people at [p] with water within [fetchKm] only by digging must dig for it this week (where no well reaches it). */
    fun mustDig(p: Place, fetchKm: Float) = underground(p, fetchKm) && well[p] < 0.5f && dug[p] >= 0.05f
    /** How far people at [p] have to go for water, km: no way at all where they can dig for it or draw it from a well. */
    fun peopleWaterKm(p: Place) = if (water[p] < 0.05f && (dug[p] >= 0.05f || well[p] >= 0.5f)) 0f else waterKm[p]

    /**
     * How far from a salt lake's water (or a desert sea) its salt crusts reach, km; how dry a shore must be (rain against
     * evaporative demand) for salt flats.
     */
    private val SALT_REACH_KM = 10f
    private val SABKHA_ARIDITY = 0.15f

    init {
        val c = world.climate
        val dr = world.water.drainage
        val cellArea = grid.cellAreaM2.toFloat()
        val rnd = kotlin.random.Random(land.seed * 131 + 11)
        val recharge = dr.accumulate(FloatArray(grid.size) {
            if (world.isSea[it]) 0f else Aquifer.of(world.geology.rock[it]).intake * c.runoff[it] / 1000f * cellArea
        })
        val reach = max(1, (4 / grid.cellKm).toInt())
        val cellKm2 = (grid.cellKm * grid.cellKm).toFloat()
        val far = 1e6f
        fun near(source: (Int) -> Boolean): FloatArray { val d = FloatArray(grid.size); distanceKm(grid, d, far, source); return d }
        val dRiver = near { !world.isSea[it] && !world.water.isLake(it) && world.water.discharge[it] >= 0.1f }
        for (i in 0 until grid.size) {
            if (world.isSea[i] || world.water.isLake(i)) continue
            val aridity = c.annualPrecip[i] / max(1f, c.annualPet[i])
            // (In humid country the streams carry the ground water off, and springs matter only where no stream
            // is within an easy walk: on ridges, hilltops and islets too small to gather a stream, the seeps.)
            if ((aridity > 0.65f && dRiver[i] <= DRINK_KM) || world.water.discharge[i] >= 0.1f || recharge[i] < MIN_SPRING_FLOW) continue
            var high = world.elevation[i]
            val x = grid.x(i); val y = grid.y(i)
            for (dy in -reach..reach) for (dx in -reach..reach) if (grid.inBounds(x + dx, y + dy)) high = max(high, world.elevation[grid.index(x + dx, y + dy)])
            val foothill = ((high - world.elevation[i]) / 200f).coerceIn(0f, 1f)
            val fault = if (world.tectonics.boundaryDistKm[i] < 10f) 0.5f else 0f
            val karst = if (world.geology.rock[i] == Rock.LIMESTONE) 0.5f else 0f
            val flow = min(1f, recharge[i] / (10f * MIN_SPRING_FLOW))
            if (rnd.nextFloat() < cellKm2 / SPRING_KM2 * 3f * flow * (0.2f + foothill + fault + karst)) {
                spring[i] = true
                oasis[i] = aridity < 0.3f && recharge[i] > 10f * MIN_SPRING_FLOW
            }
        }
        val dLake = near { world.water.isLake(it) && !world.water.lakeClosed[it] }
        val dSpring = near { spring[it] }
        // Salt: on the dry beds and shores of salt lakes and pans, and on the flats of a desert shore (sabkha).
        val dSaltLake = near { world.water.isLake(it) && world.water.lakeClosed[it] }
        val dSea = near { world.isSea[it] }
        for (p in 0 until n) land.salt[p] = max(land.salt[p], mean(p) {
            if (dSaltLake[it] <= SALT_REACH_KM || (dSea[it] <= SALT_REACH_KM && c.aridity(it) < SABKHA_ARIDITY)) 1f else 0f
        })
        // Dry beds: every valley that drains enough ground to carry flash floods, which lay sand and gravel in it.
        val drained = dr.accumulate(FloatArray(grid.size) { if (world.isSea[it]) 0f else cellArea })
        val bed = { i: Int -> !world.isSea[i] && !world.water.isLake(i) && drained[i] >= WADI_KM2 * 1e6f }
        val dBed = near(bed)
        for (p in 0 until n) {
            nearBed[p] = mean(p) { if (dBed[it] <= DRINK_KM) 1f else 0f }
            bedM2[p] = (hw.placeCells[p].count(bed) * grid.cellKm * 1000.0 * WADI_WIDTH_M).toFloat()
        }
        for (p in 0 until n) {
            nearRiver[p] = mean(p) { if (dRiver[it] <= DRINK_KM) 1f else 0f }
            nearLake[p] = mean(p) { if (dLake[it] <= DRINK_KM) 1f else 0f }
            nearSpring[p] = mean(p) { if (dSpring[it] <= DRINK_KM) 1f else 0f }
            waterKmMean[p] = mean(p) { min(min(dRiver[it], dLake[it]), min(dSpring[it], MAX_WATER_KM)) }
            riverKmLeast[p] = hw.placeCells[p].minOf { dRiver[it] }; lakeKmLeast[p] = hw.placeCells[p].minOf { dLake[it] }
            springKmLeast[p] = hw.placeCells[p].minOf { dSpring[it] }
            streamKm[p] = mean(p) { min(dRiver[it], HexHydrology.MAX_DRAIN_KM) }
        }
    }

    /**
     * This week's drinking water: rivers while they run (they shrink in drought; an intermittent
     * stream dries up), fresh lakes, springs (frozen in hard frost), floodwater, and in wet weeks
     * the pools and seeps that make water everywhere.
     */
    internal fun step(weather: Weather) {
        for (p in land.places) {
            val h = hw.placeHex[p]
            // The streams within reach are the hex's main river and its creeks (from 0.1 m³/s in a usual
            // year); they shrink with the main river, and a bed holds drinkable pools while a trickle runs.
            val running = if (hydro.meanFlow[h] > 0f) (hydro.flow[h] / hydro.meanFlow[h] * HexHydrology.CREEK / HexHydrology.TRICKLE).coerceIn(0f, 1f) else 0f
            val thawed = weather.temperature[p] > THAW_C
            val wet = hydro.soil[p] / hydro.soilCap[p]
            // Springs run on the ground water under the place: they weaken when it runs low after years of drought.
            val springs = nearSpring[p] * hydro.springFlow(p)
            val pools = if (thawed && (wet >= POOL_WETNESS || weather.precipitation[p] / days >= 3f)) 1f else 0f
            val flooded = if (hydro.flood[p] > 0f) land.floodShare[p] else 0f
            val lasting = nearRiver[p] * running + nearLake[p] + (if (thawed) springs else 0f)
            water[p] = max(pools, min(1f, lasting + flooded))
            waterKm[p] = if (pools > 0f) 0f else waterKmMean[p] / max(0.2f, running.coerceAtLeast(nearLake[p] + springs))
            val near = min(if (running > 0f) riverKmLeast[p] else MAX_WATER_KM, min(lakeKmLeast[p], if (thawed && springs > 0f) springKmLeast[p] else MAX_WATER_KM))
            campKm[p] = if (pools > 0f) 0f else if (near < MAX_WATER_KM) near else waterKm[p]
            // In a dry bed the water may lie a little way down, in the sand and gravel under it.
            // (Under the beds of the valleys the water in the sands sinks away after each flood; under the
            // streams it stands as high as the water table.)
            val snow = weather.snowpack[p] >= SNOW_EAT_MM
            iced[p] = when {
                !snow -> 0f
                weather.snowfall(p.i) >= SNOW_EAT_MM -> 0f
                weather.rain(p.i) >= SNOW_EAT_MM -> 1f
                else -> iced[p]
            }
            snowWater[p] = if (snow && iced[p] < 0.5f) 1f else 0f
            dug[p] = if (thawed) {
                max(nearBed[p] * (1f - SAND_M * (1f - sand[p]) / DIG_M), nearRiver[p] * (1f - hydro.waterTableM(p) / DIG_M)).coerceIn(0f, 1f)
            } else 0f
        }
    }

    companion object {
        /** Drinking water: an easy walk to it, km; furthest counted, km; soil wetness at which pools stand everywhere. */
        const val DRINK_KM = 3f
        const val MAX_WATER_KM = 60f
        const val POOL_WETNESS = 0.8f
        /**
         * Least snow lying, and least fresh snow in a week, that animals can eat for water, mm water equivalent: a few cm of
         * snow, as "snow lying" is counted (see [inflation.tools.GenerateWorld]).
         */
        const val SNOW_EAT_MM = 5f
        /** Springs: least groundwater flow for a spring, m³/yr; dry country per spring, km². */
        const val MIN_SPRING_FLOW = 3e4f
        const val SPRING_KM2 = 150f
        /**
         * Deepest people dig for water in a dry bed with sticks and their hands, m (soaks and sip-wells
         * are rarely deeper than 2-3 m, from memory).
         */
        const val DIG_M = 3f
        /**
         * The week's mean air, °C, below which the ground is frozen too hard to dig with a stick, and springs and pools freeze
         * (an approximation, from memory: frost goes down over weeks of cold, and snow keeps the ground under it warmer; a
         * week's air stands for both). Bulbs and roots go by it too ([inflation.ecology.HexFood.diggable]).
         */
        const val THAW_C = -2f
        /**
         * Dry beds: least ground a valley drains to have one, km²; their width, m; depth of the sand and
         * gravel in them, m, and the share of it water can fill; how fast the water in it sinks away after a
         * flood, m a day (so it lies out of reach of digging some five months after the last flood). Rough
         * values for wadis, from memory.
         */
        const val WADI_KM2 = 25f
        const val WADI_WIDTH_M = 30.0
        const val SAND_M = 5f
        const val SAND_YIELD = 0.25f
        const val SAND_DROP_M = 0.02f
        /**
         * Share of a band's days that digging soaks and drawing water from them takes: a soak is opened
         * again each day and the water seeps in slowly, an hour or two of each person's day (rough, from memory).
         */
        const val DIG_SHARE = 0.1f
    }
}
