package inflation.world

import inflation.Config

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Water and plants on the places of a [HexWorld] (the height bands of each hex), stepped with the
 * weather on the same places. The same rules as the fine [LiveWater] and [Vegetation], on the
 * averages of each band's cells:
 *
 * - Each place has a soil bucket and a groundwater store; what runs off flows down the hexes'
 *   rivers within the step (each hex drains to the hex its main river enters), and a river above
 *   bankfull floods the low ground of its hex.
 * - The ground water is an aquifer in the rock under each place (see [Aquifer]): it fills with the
 *   share of the soil's overflow the rock takes in, and with what rivers and floods lose through
 *   their beds where it lies below them; it drains into the hex's streams as fast as the rock lets
 *   water through over the distance to them, or, where a hex has no stream, on underground to the
 *   valley of the hex below. A full aquifer turns the rest away; its level sets how deep the water
 *   lies under the valley floor, and where that is within reach of roots the floodplain stays moist.
 * - Herbs, shrubs and trees grow towards ceilings set by recent moisture, warmth and soil; trees
 *   shade the rest; seed comes from the place itself and its neighbours (the bands above and below
 *   in the hex, the same heights in the hexes around); frost, floods, ash and fire set them back.
 * - A fire burns a share of a place and spreads to neighbouring places, more readily downwind.
 * - Grazing, browsing, rooting, trampling and seed carried by animals are set by the ecology.
 */
class HexLand(internal val hw: HexWorld, private val seed: Long) {
    private val world = hw.world
    private val grid = world.grid
    private val hex = hw.hex
    val n = hw.placeCells.size
    private val days = world.config.daysPerStep
    private val stepSeconds = days * 86_400.0

    // ---------------------------------------------------------------- what each place is

    /** Area of each place, km², and m². */
    val areaKm2 = FloatArray(n) { (hw.placeCells[it].size * grid.cellKm * grid.cellKm).toFloat() }
    private val areaM2 = FloatArray(n) { (areaKm2[it] * 1e6).toFloat() }
    private fun mean(p: Int, f: (Int) -> Float): Float { val cs = hw.placeCells[p]; var s = 0.0; for (i in cs) s += f(i); return (s / cs.size).toFloat() }
    private val soilFertility = FloatArray(n) { p -> mean(p) { world.soilFertility[it] } }
    private val slope = FloatArray(n) { p -> mean(p) { world.terrain.slope[it] } }
    /** Share of waterlogged ground (marsh, bog, salt marsh), where woody plants drown (fixed by the world, so not taken from a saved run: runs saved before it was filled hold zeros). */
    @Transient val logged = FloatArray(n) { p -> mean(p) { if (world.biome[it] == Biome.MARSH || world.biome[it] == Biome.BOG || world.biome[it] == Biome.SALT_MARSH) 1f else 0f } }
    /**
     * The share of each place's plants an animal or a forager can eat, 0..1: most of an open
     * grassland, little of aromatic garrigue, thorny scrub, desert bush, bog or conifer forest, where
     * the plants are defended (bitter, aromatic, thorny or poisonous) or tough. (Fixed by the world and
     * the config, so not taken from a saved run.)
     */
    @Transient val palatable = FloatArray(n) { p ->
        val h = hw.placeHex[p]
        var s = 0f; var w = 0f
        for (b in Biome.entries) { val share = hw.biome[b.ordinal][h]; if (share <= 0f) continue; val v = PALATABLE[b] ?: continue; s += share * v; w += share }
        val v = if (w > 0f) s / w else 0.5f
        // (Half of even the poorest country's plants is eaten by something; the rest of it by the
        // share for its kind. A balancing run may change that floor: t.palatableFloor.)
        val floor = Config.float("t.palatableFloor", PALATABLE_FLOOR)
        floor + (1f - floor) * v
    }

    /** The dead buried at each place (graves weather away over centuries), and the ornaments buried with them. */
    val burials = FloatArray(n)
    val graveGoods = FloatArray(n)
    /** Share of the ground where good knapping stone (flint, obsidian) crops out. */
    val stone = FloatArray(n) { p -> mean(p) { if (world.geology.minerals[it] and (Mineral.FLINT or Mineral.OBSIDIAN) != 0) 1f else 0f } }
    /** How far from a salt lake's water (or a desert sea) its salt crusts reach, km; how dry a shore must be (rain against evaporative demand) for salt flats. */
    private val SALT_REACH_KM = 10f
    private val SABKHA_ARIDITY = 0.15f
    /** Share of the ground where salt can be got (crusts on dry lake beds and salt pans), and where red and yellow ochre crops out. */
    val salt = FloatArray(n) { p -> mean(p) { if (world.geology.minerals[it] and Mineral.SALT != 0) 1f else 0f } }
    val ochre = FloatArray(n) { p -> mean(p) { if (world.geology.minerals[it] and Mineral.OCHRE != 0) 1f else 0f } }
    /** Share of floodplain, which a river in spate covers. */
    val floodShare = FloatArray(n) { p -> mean(p) { if (world.floodplain.isFloodplain[it]) 1f else 0f } }
    private val pet = Array(12) { m -> FloatArray(n) { p -> mean(p) { world.climate.monthlyPet[m][it] } } }
    /** Mean ruggedness, cover from broken ground and the best refuge and lookout of each place. */
    val ruggedness = FloatArray(n) { p -> mean(p) { world.terrain.ruggedness[it] } }
    /**
     * How the ruggedness is spread over each place: the ruggedness at [RUGGED_SHARES] even steps through its
     * ground, from its smoothest to its roughest (a rough hex still has its valley floors and meadows).
     */
    @Transient val ruggedSpread: Array<FloatArray> = Array(RUGGED_SHARES) { FloatArray(n) }.also { q ->
        java.util.stream.IntStream.range(0, n).parallel().forEach { p ->
            val r = FloatArray(hw.placeCells[p].size) { world.terrain.ruggedness[hw.placeCells[p][it]] }.also { it.sort() }
            for (k in 0 until RUGGED_SHARES) q[k][p] = if (r.isEmpty()) 0f else r[((k + 0.5f) / RUGGED_SHARES * r.size).toInt().coerceIn(0, r.size - 1)]
        }
    }
    /** Share of a place that is crag, slopes over 45% (see [inflation.ecology.HexFood.access]). */
    val crag = FloatArray(n) { p -> mean(p) { if (world.terrain.slope[it] > 0.45f) 1f else 0f } }
    /** Share of a place with caves and rock shelters: karst limestone in broken ground. */
    val caves = FloatArray(n) { p -> mean(p) { if (world.geology.rock[it] == Rock.LIMESTONE) 1f else 0.2f } * (ruggedness[p] / 0.5f).coerceIn(0f, 1f) }
    val bestDefence = FloatArray(n) { p -> hw.placeCells[p].maxOf { world.terrain.defensibility[it] } }
    val bestViewKm = FloatArray(n) { p -> hw.placeCells[p].maxOf { world.terrain.viewKm[it] } }
    /** Neighbouring places: the bands above and below in the hex and the bands at about the same height around it. */
    val neighbours: Array<IntArray> = Array(n) { p ->
        val h = hw.placeHex[p]; val b = hw.placeBand[p]
        val out = ArrayList<Int>()
        for (db in -1..1) { val bb = b + db; if (db != 0 && bb in 0 until HexWorld.BANDS_M.size) hw.placeOf[bb][h].let { if (it >= 0) out += it } }
        for (d in 0 until 6) {
            val nh = hex.neighbour(h, d)
            if (nh < 0) continue
            for (db in -1..1) { val bb = b + db; if (bb in 0 until HexWorld.BANDS_M.size) hw.placeOf[bb][nh].let { if (it >= 0) out += it } }
        }
        out.toIntArray()
    }

    // ---------------------------------------------------------------- drinking water

    /**
     * Springs and oases: groundwater from the hills comes up in dry country at the foot of slopes,
     * along faults and from limestone (as in the 1 km model), about one per [SPRING_KM2] where
     * groundwater is ample. Fine cells.
     */
    val spring = BooleanArray(grid.size)
    val oasis = BooleanArray(grid.size)
    /** Share of each place's ground within an easy walk ([DRINK_KM]) of a river or stream, a fresh lake, or a spring. */
    val nearRiver = FloatArray(n); val nearLake = FloatArray(n); val nearSpring = FloatArray(n)
    /** Mean distance from each place's ground to the nearest lasting water, km. */
    val waterKmMean = FloatArray(n)
    /** Share of each place's ground within an easy walk of a stream or dry bed (a valley draining [WADI_KM2] or more); area of those beds, m². */
    val nearBed = FloatArray(n)
    private val bedM2 = FloatArray(n)
    /**
     * How full the sand and gravel under each place's beds is, 0..1: a flood fills it, and then the
     * water in it sinks away into the rock below at [SAND_DROP_M] a day, so it lies deeper the longer
     * since the beds last ran.
     */
    val sand = FloatArray(n) { 1f }
    /** Mean distance from each place's ground to the nearest stream, km: how far its ground water has to go. */
    private val streamKm = FloatArray(n)
    /** This week: share of each place within an easy walk of drinkable water (0..1), and the mean walk to it, km. */
    val water = FloatArray(n)
    val waterKm = FloatArray(n)
    /**
     * This week: share of each place within an easy walk of a stream bed where people can dig down to
     * water (a soak), 0..1: where the water table under the valley floor lies within [DIG_M] and the
     * ground is not frozen. Animals do not count it; a band pays for it in time (see the ecology).
     */
    val dug = FloatArray(n)

    /** Whether people at [p] with water within [fetchKm] only by digging must dig for it this week. */
    fun mustDig(p: Int, fetchKm: Float) = water[p] < 0.05f && waterKm[p] > fetchKm && dug[p] >= 0.05f
    /** How far people at [p] have to go for water, km: no way at all where they can dig for it. */
    fun peopleWaterKm(p: Int) = if (water[p] < 0.05f && dug[p] >= 0.05f) 0f else waterKm[p]

    init {
        val c = world.climate
        val dr = world.water.drainage
        val cellArea = grid.cellAreaM2.toFloat()
        val rnd = kotlin.random.Random(seed * 131 + 11)
        val recharge = dr.accumulate(FloatArray(grid.size) { if (world.isSea[it]) 0f else Aquifer.of(world.geology.rock[it]).intake * c.runoff[it] / 1000f * cellArea })
        val reach = max(1, (4 / grid.cellKm).toInt())
        val cellKm2 = (grid.cellKm * grid.cellKm).toFloat()
        for (i in 0 until grid.size) {
            if (world.isSea[i] || world.water.isLake(i)) continue
            val aridity = c.annualPrecip[i] / max(1f, c.annualPet[i])
            if (aridity > 0.65f || world.water.discharge[i] >= 0.1f || recharge[i] < MIN_SPRING_FLOW) continue
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
        val far = 1e6f
        fun near(source: (Int) -> Boolean): FloatArray { val d = FloatArray(grid.size); distanceKm(grid, d, far, source); return d }
        val dRiver = near { !world.isSea[it] && !world.water.isLake(it) && world.water.discharge[it] >= 0.1f }
        val dLake = near { world.water.isLake(it) && !world.water.lakeClosed[it] }
        val dSpring = near { spring[it] }
        // Salt: on the dry beds and shores of salt lakes and pans, and on the flats of a desert shore (sabkha).
        val dSaltLake = near { world.water.isLake(it) && world.water.lakeClosed[it] }
        val dSea = near { world.isSea[it] }
        for (p in 0 until n) salt[p] = max(salt[p], mean(p) { if (dSaltLake[it] <= SALT_REACH_KM || (dSea[it] <= SALT_REACH_KM && c.aridity(it) < SABKHA_ARIDITY)) 1f else 0f })
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
            streamKm[p] = mean(p) { min(dRiver[it], MAX_DRAIN_KM) }
        }
    }

    /**
     * This week's drinking water: rivers while they run (they shrink in drought; an intermittent
     * stream dries up), fresh lakes, springs (frozen in hard frost), floodwater, and in wet weeks
     * the pools and seeps that make water everywhere.
     */
    private fun stepDrink(weather: Weather) {
        for (p in 0 until n) {
            val h = hw.placeHex[p]
            // The streams within reach are the hex's main river and its creeks (from 0.1 m³/s in a usual
            // year); they shrink with the main river, and a bed holds drinkable pools while a trickle runs.
            val running = if (meanFlow[h] > 0f) (flow[h] / meanFlow[h] * CREEK / TRICKLE).coerceIn(0f, 1f) else 0f
            val thawed = weather.temperature[p] > -2f
            val wet = soil[p] / soilCap[p]
            // Springs run on the ground water under the place: they weaken when it runs low after years of drought.
            val springs = nearSpring[p] * springFlow(p)
            val pools = if (thawed && (wet >= POOL_WETNESS || weather.precipitation[p] / days >= 3f)) 1f else 0f
            val flooded = if (flood[p] > 0f) floodShare[p] else 0f
            val lasting = nearRiver[p] * running + nearLake[p] + (if (thawed) springs else 0f)
            water[p] = max(pools, min(1f, lasting + flooded))
            waterKm[p] = if (pools > 0f) 0f else waterKmMean[p] / max(0.2f, running.coerceAtLeast(nearLake[p] + springs))
            // In a dry bed the water may lie a little way down, in the sand and gravel under it.
            // (Under the beds of the valleys the water in the sands sinks away after each flood; under the
            // streams it stands as high as the water table.)
            dug[p] = if (thawed) max(nearBed[p] * (1f - SAND_M * (1f - sand[p]) / DIG_M), nearRiver[p] * (1f - waterTableM(p) / DIG_M)).coerceIn(0f, 1f) else 0f
        }
    }

    // ---------------------------------------------------------------- rivers between hexes

    /** The hex each hex's main river flows into, -1 at the sea or a closed basin. */
    val down = IntArray(hex.size) { -1 }
    /**
     * Running mean of each hex's routed flow before correction, m³/s, over about two years. The
     * buckets of whole bands shed less water than their cells would one by one, and the coarse
     * network misses lakes and splits; each hex's flow is scaled so that over the years it matches
     * the long-term flow of its fine river.
     */
    private val rawMean = FloatArray(hex.size)
    /** Long-term mean flow of each hex's main river, m³/s. */
    val meanFlow = FloatArray(hex.size)
    private val order: IntArray

    init {
        val main = IntArray(hex.size) { -1 }
        for (i in 0 until grid.size) {
            if (world.isSea[i]) continue
            val h = hex.owner[i]
            if (main[h] < 0 || world.water.discharge[i] > world.water.discharge[main[h]]) main[h] = i
        }
        val receiver = world.water.drainage.receiver
        // Each hex drains to the next hex whose main river its own river reaches: a river that only cuts
        // the corner of a hex where a bigger river runs passes it by (sending it down that hex's river
        // instead left the hex it enters next dry, though its river is the biggest there).
        val isMain = BooleanArray(grid.size)
        for (h in 0 until hex.size) if (main[h] >= 0) isMain[main[h]] = true
        for (h in 0 until hex.size) {
            val m = main[h]
            if (m < 0) continue
            meanFlow[h] = world.water.discharge[m]
            var i = m
            var steps = 0
            while (steps++ < 100_000) {
                val r = receiver[i]
                if (r == i || world.isSea[r]) break
                if (isMain[r] && hex.owner[r] != h) { down[h] = hex.owner[r]; break }
                i = r
            }
        }
        // Upstream first.
        val inDeg = IntArray(hex.size)
        for (h in 0 until hex.size) if (down[h] >= 0) inDeg[down[h]]++
        val q = ArrayDeque<Int>()
        for (h in 0 until hex.size) if (inDeg[h] == 0) q += h
        val o = ArrayList<Int>(hex.size)
        while (q.isNotEmpty()) { val h = q.removeFirst(); o += h; val d = down[h]; if (d >= 0 && --inDeg[d] == 0) q += d }
        order = o.toIntArray()
        // (Each hex drains to the hex whose main river its own reaches downstream on the fine grid, which
        // has no loops, so neither has this; a hex left out would never route its water, so make sure.)
        check(order.size == hex.size) { "hex rivers form a loop: ${hex.size - order.size} hexes never drain" }
        // What the hex network would carry on average from the long-term runoff of the places,
        // against the long-term flow of the fine rivers: lakes, splits and losses the coarse
        // network misses are put back as a fixed factor per hex.
        val mean = DoubleArray(hex.size)
        for (p in 0 until n) mean[hw.placeHex[p]] += mean(p) { world.climate.runoff[it] } / 1000.0 * areaM2[p] / (Date.DAYS_PER_YEAR * 86_400.0)
        for (h in order) { val d = down[h]; if (d >= 0) mean[d] += mean[h] }
        for (h in 0 until hex.size) rawMean[h] = mean[h].toFloat()
    }

    // ---------------------------------------------------------------- state

    /** Plant-available water in the soil, mm; groundwater, mm. */
    /**
     * Water the soil holds, mm, by the rock under it: thin and quick to drain on limestone, granite
     * and evaporites, deep on alluvium and shale (rough values, from memory).
     */
    val soilCap = FloatArray(n) { p -> mean(p) { when (world.geology.rock[it]) {
        Rock.ALLUVIUM -> 220f; Rock.SHALE -> 180f; Rock.BASALT, Rock.VOLCANIC -> 150f; Rock.SANDSTONE -> 120f
        Rock.GRANITE, Rock.METAMORPHIC -> 100f; Rock.LIMESTONE -> 80f; Rock.EVAPORITE -> 60f } } }
    /** Share of the water the soil cannot hold that the rock takes in (see [Aquifer.intake]). */
    private val intake = FloatArray(n) { p -> mean(p) { Aquifer.of(world.geology.rock[it]).intake } }
    /** Water the aquifer under each place holds when full, mm; its thickness, m. */
    val aquiferCap = FloatArray(n) { p -> mean(p) { Aquifer.of(world.geology.rock[it]).let { it.yield * it.thicknessM * 1000f } } }
    private val aquiferM = FloatArray(n) { p -> mean(p) { Aquifer.of(world.geology.rock[it]).thicknessM } }
    /**
     * Share of each place's ground water that drains out each step. An aquifer between streams
     * [L] apart on each side drains as a linear store with rate π²T / (4 Sy L²) (the slowest term of
     * the linearised Boussinesq solution: Brutsaert 2005), T the transmissivity, Sy the specific
     * yield; L is twice the mean distance to a stream.
     */
    private val drain = FloatArray(n) { p ->
        var t = 0.0; var sy = 0.0
        for (i in hw.placeCells[p]) { val a = Aquifer.of(world.geology.rock[i]); t += a.conductivity * a.thicknessM; sy += a.yield }
        val cells = hw.placeCells[p].size
        val l = 2.0 * max(MIN_DRAIN_KM, streamKm[p].toDouble()) * 1000.0
        val rate = Math.PI * Math.PI * (t / cells) / (4.0 * (sy / cells) * l * l)
        (1.0 - exp(-rate * days)).toFloat()
    }
    val soil = FloatArray(n) { soilCap[it] * 0.6f }
    /**
     * The soil water of the shaded slopes (those facing the pole, [Sunlight.shade] of each place), mm; [soil] is
     * the sunny rest. Less sun dries them more slowly and melts their snow later.
     */
    val soilShade = FloatArray(n) { soilCap[it] * 0.6f }
    private val slopeShade = hw.sunlight.shade
    /** Degree-days above 5 °C a place has in a normal year, and so far this year (from the winter solstice). */
    val annualGdd = FloatArray(n) { p -> (0 until 12).sumOf { m -> (max(0f, hw.placeTemp(p, m) - 5f) * Date.DAYS_PER_MONTH).toDouble() }.toFloat() }
    val gdd = FloatArray(n) { p -> (0 until Date.START_DAY / Date.DAYS_PER_MONTH).sumOf { m -> (max(0f, hw.placeTemp(p, m) - 5f) * Date.DAYS_PER_MONTH).toDouble() }.toFloat() }
    /** Ground water under each place, mm over the place (starts settled: see the init below). */
    val groundwater = FloatArray(n)
    /** The lowest place of each hex, where its stream runs and ground water from above comes out; -1 for sea. */
    private val valley = IntArray(hex.size) { h -> (0 until HexWorld.BANDS_M.size).map { hw.placeOf[it][h] }.firstOrNull { it >= 0 } ?: -1 }
    /** Whether a hex has a stream its ground water seeps into; if not, the ground water goes on underground to the hex below. */
    private val stream = BooleanArray(hex.size) { meanFlow[it] >= CREEK }

    init {
        // Start each aquifer at the level where what comes in over a usual year (its share of the long-term
        // runoff, and what comes underground from the hexes above) matches what drains out.
        val stepsPerYear = Date.DAYS_PER_YEAR / days
        val inflow = DoubleArray(hex.size)
        for (h in order) {
            for (band in 0 until HexWorld.BANDS_M.size) {
                val p = hw.placeOf[band][h]; if (p < 0) continue
                var input = intake[p] * mean(p) { world.climate.runoff[it] } / stepsPerYear
                if (p == valley[h]) input += (inflow[h] / areaM2[p] * 1000.0).toFloat()
                groundwater[p] = if (drain[p] > 0f) min(aquiferCap[p], input / drain[p]) else aquiferCap[p]
                val d = down[h]
                if (!stream[h] && d >= 0) inflow[d] += groundwater[p] * drain[p] / 1000.0 * areaM2[p]
            }
        }
    }
    /** Each aquifer's settled level, mm, against which springs weaken (at least a millimetre). */
    private val usualGround = FloatArray(n) { max(1f, groundwater[it]) }
    /** Flow of each hex's river over the last step, m³/s. */
    val flow = meanFlow.copyOf()
    /** Flood depth over each place's floodplain during the last step, m. */
    val flood = FloatArray(n)

    val herb = FloatArray(n); val shrub = FloatArray(n); val tree = FloatArray(n)
    /** Standing dead grass and stems, kg/m² (built up in HexFood), eaten in the lean months, and fuel for fires. */
    val straw = FloatArray(n)
    val moisture = FloatArray(n) { p -> (mean(p) { world.climate.aridity(it) } / 1.5f).coerceIn(0f, 1f) }
    private val warmth = FloatArray(n) { p -> mean(p) { world.climate.warmestMonthTemp(it) } }
    val fertility = soilFertility.copyOf()
    val organicFertility = FloatArray(n)
    /** Share of each place burnt lately, fading over a few years; share burning this step. */
    val burnt = FloatArray(n)
    val burning = FloatArray(n)
    /** Fires this step: the place each started in and the km² it burned. */
    val fires = ArrayList<Pair<Int, Float>>()
    val litterfall = FloatArray(n)
    /**
     * Browse within an animal's reach at each place, kg/m² of the edible part: the leaves and shoots
     * the shrubs and trees put out (their growth and the leaves and twigs they renew each year), not
     * yet eaten or shed. Shoots last about a year, as the yearly renewal they come from.
     */
    val shrubShoots = FloatArray(n); val treeShoots = FloatArray(n)
    /** Whether the shoots have been set; a world saved before they were kept gets a year's worth from its plants. */
    private var shootsSet = false
    /** Set by the ecology: browsing, grazing and rooting lately, kg/km²/day; seed carried by animals, 0..1; trampling. */
    val browsing = FloatArray(n); val grazing = FloatArray(n); val rooting = FloatArray(n); val animalSeed = FloatArray(n)
    private val trampling = FloatArray(n)
    /**
     * How green each place's forage is this week, 0..1: young growth in warm, moist weeks; dry or
     * dormant stuff in drought, heat and cold. Grazers get full nourishment from green forage and a
     * fraction from the rest (see [nourishment]).
     */
    val green = FloatArray(n)
    /**
     * The cured grass weathering where it stands: how much of the stand has fallen into the litter since
     * it last grew, kg/m² (the crowns that grew it are still there, so it grows back as fast as it would
     * have), and how fresh the cured part still is, 0..1: 1 just cured, 0 leached to straw (see [HAY_WEATHERING]).
     */
    val hayFallen = FloatArray(n); val hayFresh = FloatArray(n) { 1f }
    /** Each place's warmest month, °C (normal); and from it how plants there are adapted: the warmth at which they start to grow and over which they reach full growth, °C, and the share of the sward that is perennial. */
    private val warmest = FloatArray(n) { p -> (0 until 12).maxOf { hw.placeTemp(p, it) } }
    private val growFrom = FloatArray(n) { p -> (warmest[p] - 20f).coerceIn(-2f, GROW_FROM_C) }
    private val growSpan = FloatArray(n) { p -> (warmest[p] - 6f).coerceIn(6f, GROW_SPAN_C) }
    val perennialShare = FloatArray(n) { p -> ((PERENNIAL_FULL_C + 4f - warmest[p]) / 4f).coerceIn(0f, 1f) }
    /** How far the warmth [t], °C, lets the plants of place [p] grow, 0..1, as they are adapted to it. */
    fun growthWarmth(p: Int, t: Float) = ((t - growFrom[p]) / growSpan[p]).coerceIn(0f, 1f) * if (t > 38f) 0.3f else 1f
    /**
     * How fast the grass of place [p] grows, 0..1, at warmth [season] (see [growthWarmth]) with the soil
     * [wet] (its water against what it holds), the annuals of it [cured] or not: the annuals grow with the
     * soil's water to where plants take it unstressed and not at all once cured; the perennials, by their
     * deep roots, until the soil is a quarter full; mixed as the place's sward is.
     */
    fun herbGrowth(p: Int, season: Float, wet: Float, cured: Boolean): Float {
        val annual = if (cured) 0f else season * (wet / UNSTRESSED).coerceIn(0f, 1f)
        val perennial = season * (wet / PERENNIAL_UNSTRESSED).coerceIn(0f, 1f)
        return (1f - perennialShare[p]) * annual + perennialShare[p] * perennial
    }
    /** The grass has dried out and cured this season (it waits for rain to sprout again). */
    val cured = BooleanArray(n)
    /**
     * How green the open ground is, and the seeps: the hollows and stream sides ([SEEP_SHARE] of each place)
     * where the ground water comes out and keeps the turf wet while it flows, through a dry summer.
     * [green] is the two together.
     */
    private val greenOpen = FloatArray(n); val greenSeep = FloatArray(n)
    /** The same for the grass of the shaded slopes: how green it is, and whether it has cured. */
    val greenShade = FloatArray(n); private val curedShade = BooleanArray(n)
    /** How the seeps of place [p] run, 0..1: with the ground water under it, against a usual year (as its springs). */
    fun seeping(p: Int) = springFlow(p)

    /** Nourishment per kg of forage in place [p] this week, against fresh growth: about two thirds when dry or dormant. */
    fun nourishment(p: Int) = DRY_VALUE + (1f - DRY_VALUE) * green[p]

    /** Volcanic ash lying on each place, mm (set from the eruptions each week). */
    val ash = FloatArray(n)

    /** Share of each place worn by paths, 0..1. */
    val trails = FloatArray(n)
    private var treeSeed = FloatArray(n); private var shrubSeed = FloatArray(n)
    private var stepCount = 0

    init {
        for (p in 0 until n) { herb[p] = ceiling(LifeForm.HERB, p) * 0.8f; shrub[p] = ceiling(LifeForm.SHRUB, p) * 0.8f; tree[p] = ceiling(LifeForm.TREE, p) * 0.8f }
        seedSources()
    }

    fun biomass(p: Int) = herb[p] + shrub[p] + tree[p]
    fun canopy(p: Int) = min(1f, tree[p] / (0.4f * LifeForm.TREE.maxBiomass))
    /** Cover to hide in at place [p], 0..1: scrub and forest. */
    fun cover(p: Int) = min(1f, shrub[p] / 2f + canopy(p))

    fun ceiling(form: LifeForm, p: Int): Float {
        val m = ((moisture[p] - form.moistureMin) / (form.moistureFull - form.moistureMin)).coerceIn(0f, 1f)
        val t = ((warmth[p] - form.warmthMin) / (form.warmthFull - form.warmthMin)).coerceIn(0f, 1f)
        val soilF = 0.3f + 0.7f * min(1f, fertility[p] + organicFertility[p])
        val wetFactor = when (form) { LifeForm.HERB -> 1f; LifeForm.SHRUB -> 1f - 0.6f * logged[p]; LifeForm.TREE -> 1f - 0.95f * logged[p] }
        return form.maxBiomass * m * t * soilF * wetFactor
    }

    fun consume(p: Int, form: LifeForm, kgPerM2: Float): Float {
        val b = when (form) { LifeForm.HERB -> herb; LifeForm.SHRUB -> shrub; LifeForm.TREE -> tree }
        val taken = min(b[p], max(0f, kgPerM2)); b[p] -= taken; return taken
    }

    fun trample(p: Int, intensity: Float) { trampling[p] += intensity }

    /** Browse at [p], kg/m². */
    fun browse(p: Int) = shrubShoots[p] + treeShoots[p]

    /** Eats up to [kgPerM2] of the browse at [p], shrubs and trees alike; what was eaten comes off the plants too. */
    fun eatBrowse(p: Int, kgPerM2: Float): Float {
        val all = browse(p)
        if (all <= 0f || kgPerM2 <= 0f) return 0f
        val x = min(kgPerM2, all)
        val s = x * shrubShoots[p] / all; val t = x - s
        shrubShoots[p] -= s; treeShoots[p] -= t
        return consume(p, LifeForm.SHRUB, s) + consume(p, LifeForm.TREE, t)
    }

    /** Advances one step with the weather of that step on the same places. */
    fun step(weather: Weather) {
        stepWater(weather)
        stepDrink(weather)
        stepPlants(weather)
    }

    private fun stepWater(weather: Weather) {
        val m = weather.lastMonth
        val share = days.toFloat() / Date.DAYS_PER_MONTH
        val volume = DoubleArray(hex.size)
        val underground = DoubleArray(hex.size)
        for (p in 0 until n) {
            val t = weather.temperature[p]
            if (weather.date.month == 0 && weather.date.dayOfMonth <= days) gdd[p] = 0f
            gdd[p] += max(0f, t - 5f) * days
            val rain = if (t >= 0f) weather.precipitation[p] else 0f
            // Evaporation follows the week's heat against the month's normal (about 6% a degree) and the sun on the
            // slopes; plants draw at full rate until the soil is half dry, then less as it dries (the same stress their
            // growth feels). The sunny and the shaded slopes each keep their own soil water.
            val heat = exp(0.06f * (t - hw.placeTemp(p, m))).coerceIn(0.3f, 3f)
            fun bucket(water: Float, sun: Float): Float {
                val et = min(water, pet[m][p] * sun * share * heat * min(1f, water / (UNSTRESSED * soilCap[p])))
                return water - et
            }
            val sunny = bucket(soil[p] + rain + weather.meltSun[p], hw.sunlight.ratio[0][m][p])
            val shady = bucket(soilShade[p] + rain + weather.meltShade[p], hw.sunlight.ratio[1][m][p])
            soil[p] = min(sunny, soilCap[p]); soilShade[p] = min(shady, soilCap[p])
            val excess = (1 - slopeShade[p]) * max(0f, sunny - soilCap[p]) + slopeShade[p] * max(0f, shady - soilCap[p])
            // The turf of the seeps draws what it evaporates from the ground water seeping out under it.
            if (t > 0f) groundwater[p] = max(0f, groundwater[p] - SEEP_SHARE * pet[m][p] * share * heat * seeping(p))
            // The rock takes in its share of what the soil cannot hold, as far as the aquifer has room; the rest runs off.
            val taken = min(excess * intake[p], aquiferCap[p] - groundwater[p]).coerceAtLeast(0f)
            groundwater[p] += taken
            volume[hw.placeHex[p]] += (excess - taken) / 1000.0 * areaM2[p]
            ranOff[p] = excess - taken > 0f
            // The water in the bed sands sinks into the rock below.
            if (bedM2[p] > 0f && sand[p] > 0f) {
                val sink = min(sand[p], SAND_DROP_M * days / SAND_M)
                sand[p] -= sink
                val mm = (sink * SAND_M * SAND_YIELD * bedM2[p] / areaM2[p] * 1000f)
                // (What the rock has no room for comes out in the stream.)
                val into = min(mm, aquiferCap[p] - groundwater[p]).coerceAtLeast(0f)
                groundwater[p] += into
                volume[hw.placeHex[p]] += (mm - into) / 1000.0 * areaM2[p]
            }
        }
        for (h in order) {
            // Ground water drains into the hex's stream, or on underground to the valley of the hex below.
            val v = valley[h]
            if (v >= 0 && underground[h] > 0.0) {
                val mm = (underground[h] / areaM2[v] * 1000.0).toFloat()
                val room = max(0f, aquiferCap[v] - groundwater[v])
                // What the valley's aquifer has no room for comes up as seeps and springs.
                groundwater[v] += min(mm, room)
                val over = max(0f, mm - room) / 1000.0 * areaM2[v]
                volume[h] += over
            }
            val d = down[h]
            for (band in 0 until HexWorld.BANDS_M.size) {
                val p = hw.placeOf[band][h]; if (p < 0) continue
                val out = groundwater[p] * drain[p]
                groundwater[p] -= out
                val m3 = out / 1000.0 * areaM2[p]
                if (!stream[h] && d >= 0) underground[d] += m3 else volume[h] += m3
            }
            val raw = (volume[h] / stepSeconds).toFloat()
            rawMean[h] += (raw - rawMean[h]) * FLOW_MEMORY
            flow[h] = if (rawMean[h] > 0.01f && meanFlow[h] > 0f) raw * (meanFlow[h] / rawMean[h]).coerceIn(0.3f, 3f) else raw
            for (band in 0 until HexWorld.BANDS_M.size) {
                val p = hw.placeOf[band][h]; if (p < 0) continue
                flood[p] = if (floodShare[p] <= 0f || meanFlow[h] < RiverClass.STREAM.minDischarge) 0f else Channel.floodStageM(flow[h], meanFlow[h])
            }
            // Where the aquifer lies below the river, the river loses water through its bed, and a flood
            // through the ground it covers, as fast as the bed lets it through and the aquifer has room.
            if (v >= 0 && volume[h] > 0.0) {
                val wetM2 = Channel.widthM(raw).toDouble() * hw.hex.hexKm * 1000.0
                var lose = 0.0
                for (band in 0 until HexWorld.BANDS_M.size) {
                    val p = hw.placeOf[band][h]; if (p < 0) continue
                    val bed = (if (p == v) wetM2 else 0.0) + (if (flood[p] > 0f) floodShare[p] * areaM2[p].toDouble() else 0.0)
                    if (bed <= 0.0) continue
                    soak[p] = (BED_CONDUCTIVITY * days * bed * (1f - groundwater[p] / aquiferCap[p]).coerceIn(0f, 1f)).toFloat()
                    lose += soak[p]
                }
                val share = if (lose > 0.0) min(1.0, volume[h] / lose) else 0.0
                if (lose > 0.0) for (band in 0 until HexWorld.BANDS_M.size) {
                    val p = hw.placeOf[band][h]; if (p < 0) continue
                    val bed = (if (p == v) wetM2 else 0.0) + (if (flood[p] > 0f) floodShare[p] * areaM2[p].toDouble() else 0.0)
                    if (bed <= 0.0) continue
                    val m3 = soak[p] * share
                    groundwater[p] += (m3 / areaM2[p] * 1000.0).toFloat(); volume[h] -= m3
                }
            }
            // Floods soak the floodplains, and where the water table is within reach of roots (and round
            // lakes and springs) the ground stays moist: the water is drawn from the ground water under
            // the place, then from what flows through the hex.
            var want = 0.0
            for (band in 0 until HexWorld.BANDS_M.size) {
                val p = hw.placeOf[band][h]; if (p < 0) continue
                var need = soakTo(p, h) - soil[p]
                soak[p] = need.coerceAtLeast(0f)
                if (need <= 0f) continue
                val fromGround = min(need, groundwater[p])
                groundwater[p] -= fromGround; soil[p] += fromGround; need -= fromGround
                soak[p] = need
                if (need > 0f) want += need / 1000.0 * areaM2[p]
            }
            if (want > 0.0) {
                val share = min(1.0, volume[h] / want).toFloat()
                for (band in 0 until HexWorld.BANDS_M.size) {
                    val p = hw.placeOf[band][h]; if (p < 0 || soak[p] <= 0f) continue
                    val mm = soak[p] * share
                    soil[p] += mm; volume[h] -= mm / 1000.0 * areaM2[p]
                }
            }
            // Where the beds ran this week (the place shed water, or a flood came down), the water fills the sands under them.
            for (band in 0 until HexWorld.BANDS_M.size) {
                val p = hw.placeOf[band][h]; if (p < 0 || bedM2[p] <= 0f || sand[p] >= 1f) continue
                if (!ranOff[p] && flood[p] <= 0f) continue
                val m3 = min(volume[h], ((1f - sand[p]) * SAND_M * SAND_YIELD * bedM2[p]).toDouble())
                if (m3 <= 0.0) continue
                sand[p] += (m3 / (SAND_M * SAND_YIELD * bedM2[p])).toFloat(); volume[h] -= m3
            }
            if (d >= 0) volume[d] += volume[h]
        }
    }

    /** Depth of the water table under the valley floor of place [p], m: at the surface when its aquifer is full. */
    fun waterTableM(p: Int) = aquiferM[p] * (1f - groundwater[p] / aquiferCap[p]).coerceIn(0f, 1f)

    /** How wet the soil of [p] in hex [h] is kept this week by a flood over its floodplain and by the water table, mm. */
    private fun soakTo(p: Int, h: Int): Float {
        var to = 0f
        // A flood soaks the floodplain's share of the place.
        if (flood[p] > 0f) to = soilCap[p] * floodShare[p]
        // On the valley floor (the floodplain) deep roots (tamarisk, poplar, palms, reeds) reach the water
        // table where it lies within their reach; round fresh lakes and springs it is near the surface.
        // Their share of the place stays moist through the driest weeks.
        val valleyFloor = floodShare[p] * (1f - waterTableM(p) / ROOT_REACH_M).coerceIn(0f, 1f)
        val edge = min(1f, valleyFloor + WATER_TABLE * (nearLake[p] + nearSpring[p] * springFlow(p)))
        if (edge > 0f) to = max(to, soilCap[p] * edge)
        return to
    }
    /**
     * How the springs of place [p] run against a usual year, 0..1: as the aquifer under it stands against
     * its settled level. Its water comes from the rain on the place and, in a valley, from the ground water
     * of the hexes above, so springs hold through a dry summer and fail only when years of drought drain it.
     */
    fun springFlow(p: Int) = min(1f, groundwater[p] / usualGround[p])
    /** Scratch per place: water lost into it through a river bed or a flood, m³; then what its soil still needs, mm. */
    private val soak = FloatArray(n)
    /** Scratch per place: whether it shed water this step (so its beds ran). */
    private val ranOff = BooleanArray(n)

    private fun stepPlants(weather: Weather) {
        val years = days / Date.DAYS_PER_YEAR.toFloat()
        val memory = 1 - exp(-days / 360f)
        if (stepCount++ % 5 == 0) seedSources()
        if (!shootsSet) { for (p in 0 until n) { shrubShoots[p] = palatable[p] * SHRUB_RENEWAL * shrub[p]; treeShoots[p] = palatable[p] * TREE_IN_REACH * TREE_RENEWAL * tree[p] }; shootsSet = true }
        val shootsKept = exp(-years)
        spreadFires(weather, years)
        java.util.stream.IntStream.range(0, n).parallel().forEach { p ->
            val before = biomass(p)
            val t = weather.temperature[p]
            val wet = soil[p] / soilCap[p]
            moisture[p] += (((1 - slopeShade[p]) * soil[p] + slopeShade[p] * soilShade[p]) / soilCap[p] - moisture[p]) * memory
            warmth[p] = if (t > warmth[p]) warmth[p] + (t - warmth[p]) * 0.5f else warmth[p] + (t - warmth[p]) * memory * 0.1f
            // Plants are adapted to their place: where summers are cool they grow fully at less warmth (alpine
            // grasses at 5-10 °C), where they are hot as before.
            val season = growthWarmth(p, t)
            val water0 = (wet / UNSTRESSED).coerceIn(0f, 1f)
            val waterShade = (soilShade[p] / soilCap[p] / UNSTRESSED).coerceIn(0f, 1f)
            // Grass that dries out in growing weather sets seed and cures to straw where it stands; it greens
            // again only when rain wets the soil enough to sprout it (to where plants take water unstressed).
            // So do the annual grasses of hot, dry summers. Where summers are cool the sward is perennial: deep
            // roots keep it growing until the soil is a quarter full, it browns as the soil dries further and
            // greens again with the next rain, without curing for the year.
            if (season > 0f && water0 < 0.5f) cured[p] = true else if (water0 >= 1f) cured[p] = false
            if (season > 0f && waterShade < 0.5f) curedShade[p] = true else if (waterShade >= 1f) curedShade[p] = false
            val shaded = slopeShade[p]
            val sunnyGrowth = herbGrowth(p, season, wet, cured[p])
            val shadyGrowth = herbGrowth(p, season, soilShade[p] / soilCap[p], curedShade[p])
            val openGrowth = (1 - shaded) * sunnyGrowth + shaded * shadyGrowth
            // The seeps grow as long as the ground water flows, whatever the weather brings.
            val seepGrowth = season * seeping(p)
            val grassGrowth = (1 - SEEP_SHARE) * openGrowth + SEEP_SHARE * seepGrowth
            // Greenness follows growth, with a lag: a flush takes some weeks to come and to cure.
            // After a fire the burn greens up with young growth, rich forage, as long as it is growing weather.
            greenOpen[p] += (min(1f, sunnyGrowth + FLUSH * burnt[p] * season) - greenOpen[p]) * GREEN_RATE
            greenShade[p] += (min(1f, shadyGrowth + FLUSH * burnt[p] * season) - greenShade[p]) * GREEN_RATE
            greenSeep[p] += (seepGrowth - greenSeep[p]) * GREEN_RATE
            green[p] = (1 - SEEP_SHARE) * ((1 - shaded) * greenOpen[p] + shaded * greenShade[p]) + SEEP_SHARE * greenSeep[p]
            val shade = canopy(p)
            val browsed = 1f - exp(-browsing[p] / Vegetation.BROWSE_SCALE)
            val young = 1f - min(1f, tree[p] / (0.3f * ceiling(LifeForm.TREE, p).coerceAtLeast(1e-3f)))
            val rooted = 1f - exp(-rooting[p] / Vegetation.ROOT_SCALE)
            val treeSeedHere = max(treeSeed[p], 0.5f * animalSeed[p]) * (1f + 1.5f * rooted)
            val shrubSeedHere = max(shrubSeed[p], animalSeed[p]) * (1f + 1.5f * rooted)
            val tree0 = tree[p]; val shrub0 = shrub[p]
            grow(LifeForm.TREE, tree, p, season * water0 * years * (1 - 0.8f * browsed * young), treeSeedHere * (1 - 0.9f * browsed))
            grow(LifeForm.SHRUB, shrub, p, season * water0 * years * (1 - 0.6f * shade) * (1 - 0.5f * browsed), shrubSeedHere * (1 - 0.8f * browsed))
            // New shoots: what the woody plants grew, and the leaves and twigs they renew, in growing weather.
            val renew = season * water0 * years
            shrubShoots[p] = shrubShoots[p] * shootsKept + palatable[p] * (max(0f, shrub[p] - shrub0) + SHRUB_RENEWAL * renew * shrub[p])
            treeShoots[p] = treeShoots[p] * shootsKept + palatable[p] * TREE_IN_REACH * (max(0f, tree[p] - tree0) + TREE_RENEWAL * renew * tree[p])
            // In the cold the deciduous leaves fall: through the winter only twigs and buds are left to browse.
            // (Where winters are mild enough to grow on, the leaves stay, as on the evergreen scrub of the south.)
            if (LEAF_FALL > 0f && season <= 0f && t < growFrom[p]) { val k = exp(-LEAF_FALL * years); shrubShoots[p] *= k; treeShoots[p] *= k }
            val lawn = 1f - exp(-grazing[p] / Vegetation.LAWN_SCALE)
            val herb0 = herb[p]
            grow(LifeForm.HERB, herb, p, grassGrowth * years * (1 - 0.8f * shade), 1f, 1f - 0.5f * lawn - 0.2f * rooted, hayFallen[p])
            // Cured grass weathers where it stands: the dead stems break and fall into the litter, and what
            // stands is leached of its protein, week by week, until rain sprouts it again. New growth makes
            // good what fell, and freshens the stand by its share of it.
            val grown = max(0f, herb[p] - herb0)
            hayFallen[p] = max(0f, hayFallen[p] - grown)
            if (HAY_WEATHERING > 0f) {
                val kept = exp(-HAY_WEATHERING * years)
                val fell = herb[p] * (1f - green[p]) * (1f - kept)
                herb[p] -= fell; hayFallen[p] += fell
                hayFresh[p] = if (herb[p] > 1e-6f) ((herb[p] - grown).coerceAtLeast(0f) * hayFresh[p] * kept + grown) / herb[p] else 1f
            }
            // Ash smothers the low plants and scorches leaves; as it weathers it enriches the soil.
            if (ash[p] > 1f) {
                herb[p] *= exp(-ash[p] / ASH_SMOTHER_MM * years * 52f / 10f); shrub[p] *= exp(-ash[p] / (4f * ASH_SMOTHER_MM) * years * 52f / 10f)
                fertility[p] = min(1f, fertility[p] + ASH_FERTILITY * ash[p] * years)
            }
            if (t < LifeForm.HERB.frostKill) herb[p] *= 0.3f
            if (t < LifeForm.SHRUB.frostKill) shrub[p] *= 0.3f
            if (t < LifeForm.TREE.frostKill) tree[p] *= 0.3f
            // Floods drown what grows on the floodplain share of the place.
            val f = flood[p]; val fs = floodShare[p]
            if (f > 1f) { herb[p] *= 1f - 0.5f * fs; shrub[p] *= 1f - 0.1f * fs }
            if (f > 3f) tree[p] *= 1f - 0.03f * fs
            fertility[p] += (soilFertility[p] - fertility[p]) * years / Vegetation.SOIL_RECOVERY_YEARS
            val cover = min(1f, herb[p] / 0.3f + shrub[p] + canopy(p))
            val bare = (1f - cover) * (1f - cover)
            if (bare > 0.01f) fertility[p] *= 1f - min(0.5f, Vegetation.EROSION * bare * weather.precipitation[p] / 50f * slope[p] / 0.2f * years)
            val burned = if (burning[p] > 0f) burn(p, burning[p]) else 0f
            litterfall[p] = max(0f, before - biomass(p) - burned) + years * (herb[p] + SHRUB_RENEWAL * shrub[p] + TREE_RENEWAL * tree[p])
            // Trampling, spread over the place: a herd beats down a share of it.
            val tr = trampling[p] / areaKm2[p]
            if (tr > 0f) {
                herb[p] *= exp(-TRAMPLE_HERB * tr); shrub[p] *= exp(-TRAMPLE_SHRUB * tr)
                trails[p] = min(1f, trails[p] + Vegetation.TRAIL_WEAR * tr * (1f - trails[p]))
                trampling[p] = 0f
            } else if (trails[p] > 0f) trails[p] = max(0f, trails[p] - Vegetation.TRAIL_FADE * years)
            if (burnt[p] > 0f) burnt[p] = max(0f, burnt[p] - 0.4f * years)
        }
    }

    /** (With [crowns], kg/m², of the stand lost lately that its roots will grow back: it grows as from the whole stand.) */
    private fun grow(form: LifeForm, b: FloatArray, p: Int, time: Float, seedRain: Float, capFactor: Float = 1f, crowns: Float = 0f) {
        val cap = ceiling(form, p) * capFactor
        val v = b[p]; val base = v + crowns
        if (v < cap) b[p] = v + form.growthPerYear * time * (base + seedRain * cap * 0.05f) * (1 - v / cap.coerceAtLeast(1e-6f))
        else b[p] = v - (v - cap) * min(1f, 2f * time + 0.002f)
        if (b[p] < 0f) b[p] = 0f
    }

    private fun fuel(p: Int) = straw[p] + (herb[p] + 0.5f * shrub[p]) * (1f - 0.8f * (1f - exp(-grazing[p] / Vegetation.GRAZE_SCALE)))

    private fun canBurn(p: Int, weather: Weather) =
        flood[p] <= 0f && fuel(p) >= 0.2f && soil[p] / soilCap[p] < 0.3f && weather.temperature[p] > 15f && weather.precipitation[p] < 5f

    /** Fires people have set, to be lit next step. */
    private val lit = java.util.concurrent.ConcurrentLinkedQueue<Int>()
    fun ignite(p: Int) { lit += p }

    /**
     * Fires: started by lightning and sparks in hot, dry, fuel-rich places (more often the bigger the
     * place), each burns a share of its place set by the fuel and spreads to neighbouring places,
     * readily downwind, barely upwind, within the step.
     */
    private fun spreadFires(weather: Weather, years: Float) {
        burning.fill(0f)
        fires.clear()
        val set = HashSet<Int>()
        while (true) set += lit.poll() ?: break
        for (p in 0 until n) {
            if (burning[p] > 0f || !canBurn(p, weather)) continue
            val heat = ((weather.temperature[p] - 15f) / 20f).coerceIn(0f, 1f)
            val chance = Vegetation.IGNITIONS_PER_KM2_YEAR * areaKm2[p] * years * min(1f, fuel(p) / 1.5f) * heat
            if (p !in set && hash(p, 0) >= chance) continue
            var km2 = 0f
            val queue = ArrayDeque<Int>(); queue += p
            burning[p] = burnShare(p); km2 += burning[p] * areaKm2[p]
            val farKm = Vegetation.SPREAD_KM_PER_DAY * days
            while (queue.isNotEmpty()) {
                val c = queue.removeFirst()
                val (cx, cy) = hex.centreKm(hw.placeHex[c])
                val u = weather.windEast(c); val v = weather.windSouth(c)
                val speed = kotlin.math.hypot(u, v)
                for (nb in neighbours[c]) {
                    if (burning[nb] > 0f || !canBurn(nb, weather)) continue
                    if (hex.distanceKm(hw.placeHex[p], hw.placeHex[nb]) > farKm) continue
                    val (nx, ny) = hex.centreKm(hw.placeHex[nb])
                    val dx = (nx - cx).toFloat(); val dy = (ny - cy).toFloat()
                    val len = kotlin.math.hypot(dx, dy)
                    val along = if (speed > 0.1f && len > 0f) (dx * u + dy * v) / (speed * len) else 0f
                    val wind = 0.2f + 0.4f * min(1f, speed / 8f) * along
                    if (hash(nb, c) >= wind * burning[c] * min(1f, fuel(nb))) continue
                    burning[nb] = burnShare(nb); km2 += burning[nb] * areaKm2[nb]; queue += nb
                }
            }
            fires += Pair(p, km2)
        }
    }

    /** Share of place [p] a fire burns: most of a place thick with dry grass, a little of a sparse one. */
    private fun burnShare(p: Int) = (0.1f + 0.6f * min(1f, fuel(p) / 1.5f)).coerceAtMost(0.7f)

    private fun burn(p: Int, share: Float): Float {
        val before = biomass(p)
        herb[p] *= 1f - 0.9f * share; shrub[p] *= 1f - 0.8f * share; tree[p] *= 1f - 0.3f * share
        straw[p] *= 1f - share
        burnt[p] = max(burnt[p], share)
        fertility[p] = min(1f, fertility[p] + 0.01f * share)
        return before - biomass(p)
    }

    private fun hash(p: Int, salt: Int): Float {
        var h = seed * -0x61c8864680b583ebL + stepCount * 0x9E3779B97F4A7C15uL.toLong() + p * 0x632BE59BD9B4E019L + salt * 0x165667B19E3779F9L
        h = (h xor (h ushr 33)) * -0xae502812aa7333L
        h = (h xor (h ushr 33)) * -0x3b314601e57a13adL
        h = h xor (h ushr 33)
        return (h ushr 40).toFloat() / (1L shl 24).toFloat()
    }

    /** Seed for trees and shrubs: from the place itself, and half as much from its neighbours. */
    private fun seedSources() {
        val t = FloatArray(n) { min(1f, tree[it] / 5f) }; val s = FloatArray(n) { min(1f, shrub[it] / 1f) }
        treeSeed = FloatArray(n) { p -> max(t[p], 0.5f * (neighbours[p].maxOfOrNull { t[it] } ?: 0f)) }
        shrubSeed = FloatArray(n) { p -> max(s[p], 0.5f * (neighbours[p].maxOfOrNull { s[it] } ?: 0f)) }
    }

    companion object {
        /** Drinking water: an easy walk to it, km; furthest counted, km; soil wetness at which pools stand everywhere. */
        const val DRINK_KM = 3f
        const val MAX_WATER_KM = 60f
        const val POOL_WETNESS = 0.8f
        /** Springs: least groundwater flow for a spring, m³/yr; dry country per spring, km². */
        const val MIN_SPRING_FLOW = 3e4f
        const val SPRING_KM2 = 150f
        /** Nourishment of dry or dormant forage against fresh growth (dry grass is about 45-55% digestible against 65-75% for young growth, from memory); weekly share by which greenness follows growth. */
        val DRY_VALUE = Config.float("t.dryValue", 0.65f)
        /** Experiment knobs: the warmth at which plants start to grow, °C, and the span over which they reach full growth, °C. */
        val GROW_FROM_C = Config.float("t.growFrom", 2f)
        val GROW_SPAN_C = Config.float("t.growSpan", 13f)
        /**
         * Perennial swards: wholly so where the warmest month is at most this, °C (annuals wholly where it is
         * 4 °C warmer); the share of what the soil holds down to which their deep roots keep them growing.
         */
        const val PERENNIAL_FULL_C = 18f
        const val PERENNIAL_UNSTRESSED = 0.25f
        const val PALATABLE_FLOOR = 0.5f
        /** Share of the soil's water plants take up without stress; drier than that, growth and evaporation fall in step (FAO-56, p about 0.5). */
        const val UNSTRESSED = 0.5f
        /** Share of shrubs' and trees' standing mass renewed as leaves and twigs a year (as shed into the litter). */
        /** Steps through each place's ground at which its ruggedness is kept (see [ruggedSpread]). */
        const val RUGGED_SHARES = 8
        const val SHRUB_RENEWAL = 0.2f
        const val TREE_RENEWAL = 0.04f
        /**
         * How fast cured grass weathers where it stands, a year: the share of the standing dead that falls
         * into the litter (about 0.12-0.2 a month for grasses in the CENTURY model's fall rate, from memory),
         * and the rate at which what stands loses its protein toward straw's (crude protein of cured grass
         * falling from about 6% to 3% over a dry season of four to five months, from memory). 0: no weathering.
         */
        val HAY_WEATHERING = Config.float("t.hayWeathering", 1.8f)
        /** Share of trees' new growth within a browser's reach (saplings and low boughs). */
        const val TREE_IN_REACH = 0.03f
        /** How moist the water table keeps the ground round fresh lakes and springs, as a share of a full soil. */
        const val WATER_TABLE = 0.6f
        /**
         * Share of the land in seeps: where the ground water comes out in hollows and along the streams. The
         * streams run some 2.5 km to each km² of hill country (drainage density, about 2–4 in humid uplands)
         * with a wet strip some 40 m wide along them (both banks), from memory.
         */
        const val SEEP_SHARE = 2.5f * 0.04f
        /**
         * Streams: the least usual flow of a creek counted as drinking water (as [nearRiver] is set), m³/s;
         * the flow that still keeps pools in its bed, m³/s (about 860 m³ a day, far more than any band and herd drink).
         */
        const val CREEK = 0.1f
        const val TRICKLE = 0.01f
        /** Ground water: least and most distance to a stream counted, km (a cell; beyond 100 km it hardly matters). */
        const val MIN_DRAIN_KM = 0.5
        const val MAX_DRAIN_KM = 100f
        /**
         * How fast water seeps through a river's bed into the aquifer below, m/day: beds are clogged with
         * silt, typically 0.01-1 m/day against several m/day for the sand below (from memory).
         */
        const val BED_CONDUCTIVITY = 0.1
        /**
         * Deepest people dig for water in a dry bed with sticks and their hands, m (soaks and sip-wells
         * are rarely deeper than 2-3 m, from memory).
         */
        const val DIG_M = 3f
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
        /** Depth to which phreatophytes (tamarisk, poplar, acacia, date palm) reach the water table, m (often 5-10 m, from memory). */
        const val ROOT_REACH_M = 5f
        /** Share of the plants of each kind of country that are edible (see [palatable]). */
        val PALATABLE = mapOf(
            Biome.ALPINE_MEADOW to 0.7f, Biome.STEPPE to 0.7f, Biome.OPEN_WOODLAND to 0.6f, Biome.FLOODPLAIN_FOREST to 0.5f,
            Biome.MARSH to 0.5f, Biome.MEDITERRANEAN_WOODLAND to 0.45f, Biome.TEMPERATE_FOREST to 0.4f, Biome.SALT_MARSH to 0.4f,
            Biome.SEMI_DESERT to 0.35f, Biome.SHRUBLAND to 0.3f, Biome.CONIFER_FOREST to 0.3f, Biome.SWAMP_FOREST to 0.3f,
            Biome.DESERT to 0.3f, Biome.BOG to 0.2f, Biome.MANGROVE to 0.2f, Biome.ICE_AND_ROCK to 0.2f,
        )
        const val GREEN_RATE = 0.3f
        /** How green a fresh burn turns in growing weather, per share burnt. */
        const val FLUSH = 1.5f
        /** Ash depth that smothers most of the herb layer within weeks, mm; fertility added per mm of ash a year as it weathers. */
        const val ASH_SMOTHER_MM = 50f
        const val ASH_FERTILITY = 0.002f
        /** Weekly weight of the newest flow in the running mean used to correct river flows (about two years). */
        const val FLOW_MEMORY = 0.01f
    }
}

/**
 * Share of the herbs and shrubs a place loses a step for each unit of trampling over it (see
 * [HexLand.trample]; t.trampleHerb, t.trampleShrub): about 5% of the shrubs a year where a
 * tonne of animals a km² goes about (it was ten times that, more than shrubs regrow: the shrub layer
 * fell by half in four years and the browsers starved).
 */
val TRAMPLE_HERB = Config.float("t.trampleHerb", 0.005f)
val TRAMPLE_SHRUB = Config.float("t.trampleShrub", 0.01f)

/** How fast the leaves of the shoots fall in the cold, a year: about two-thirds gone over a four-month winter, the twigs and buds left (t.leafFall; 0: they last as twigs do). */
val LEAF_FALL = Config.float("t.leafFall", 4f)
