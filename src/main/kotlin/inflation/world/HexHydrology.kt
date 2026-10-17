package inflation.world

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * The water of the places of [HexLand], as the fine [LiveWater] keeps it on the averages of each band's cells:
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
 *
 * Rivers, soil water and ground water are one part: the week's water is routed hex by hex from upstream
 * down in a single pass, each hex's ground water, river, floods and soils settled before the next.
 */
class HexHydrology(land: HexLand) : HexLandPart(land) {
    /** The hex each hex's main river flows into, -1 at the sea or a closed basin. */
    @Transient val down = IntArray(hex.size) { -1 }
    /**
     * Running mean of each hex's routed flow before correction, m³/s, over about two years. The
     * buckets of whole bands shed less water than their cells would one by one, and the coarse
     * network misses lakes and splits; each hex's flow is scaled so that over the years it matches
     * the long-term flow of its fine river.
     */
    private val rawMean = FloatArray(hex.size)
    /** Long-term mean flow of each hex's main river, m³/s. */
    @Transient val meanFlow = FloatArray(hex.size)
    @Transient private val order: IntArray

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
        for (p in 0 until n) mean[hw.placeHex[p]] += mean(p) { world.climate.runoff[it] } / 1000.0 * land.areaM2[p] / (Date.DAYS_PER_YEAR * 86_400.0)
        for (h in order) { val d = down[h]; if (d >= 0) mean[d] += mean[h] }
        for (h in 0 until hex.size) rawMean[h] = mean[h].toFloat()
    }

    /**
     * Water the soil holds, mm, by the rock under it: thin and quick to drain on limestone, granite
     * and evaporites, deep on alluvium and shale (rough values, from memory).
     */
    @Transient val soilCap = FloatArray(n) { p -> mean(p) { when (world.geology.rock[it]) {
        Rock.ALLUVIUM -> 220f; Rock.SHALE -> 180f; Rock.BASALT, Rock.VOLCANIC -> 150f; Rock.SANDSTONE -> 120f
        Rock.GRANITE, Rock.METAMORPHIC -> 100f; Rock.LIMESTONE -> 80f; Rock.EVAPORITE -> 60f } } }
    /** Share of the water the soil cannot hold that the rock takes in (see [Aquifer.intake]). */
    @Transient private val intake = FloatArray(n) { p -> mean(p) { Aquifer.of(world.geology.rock[it]).intake } }
    /** Water the aquifer under each place holds when full, mm; its thickness, m. */
    @Transient val aquiferCap = FloatArray(n) { p -> mean(p) { Aquifer.of(world.geology.rock[it]).let { it.yield * it.thicknessM * 1000f } } }
    @Transient private val aquiferM = FloatArray(n) { p -> mean(p) { Aquifer.of(world.geology.rock[it]).thicknessM } }
    /**
     * Share of each place's ground water that drains out each step. An aquifer between streams
     * [L] apart on each side drains as a linear store with rate π²T / (4 Sy L²) (the slowest term of
     * the linearised Boussinesq solution: Brutsaert 2005), T the transmissivity, Sy the specific
     * yield; L is twice the mean distance to a stream.
     */
    @Transient private val drain = FloatArray(n) { p ->
        var t = 0.0; var sy = 0.0
        for (i in hw.placeCells[p]) { val a = Aquifer.of(world.geology.rock[i]); t += a.conductivity * a.thicknessM; sy += a.yield }
        val cells = hw.placeCells[p].size
        val l = 2.0 * max(MIN_DRAIN_KM, drink.streamKm[p].toDouble()) * 1000.0
        val rate = Math.PI * Math.PI * (t / cells) / (4.0 * (sy / cells) * l * l)
        (1.0 - exp(-rate * days)).toFloat()
    }
    /** Plant-available water in the soil, mm. */
    val soil = FloatArray(n) { soilCap[it] * 0.6f }
    /**
     * The soil water of the shaded slopes (those facing the pole, [Sunlight.shade] of each place), mm; [soil] is
     * the sunny rest. Less sun dries them more slowly and melts their snow later.
     */
    val soilShade = FloatArray(n) { soilCap[it] * 0.6f }
    /** Ground water under each place, mm over the place (starts settled: see the init below). */
    val groundwater = FloatArray(n)
    /** The lowest place of each hex, where its stream runs and ground water from above comes out; -1 for sea. */
    @Transient private val valley = IntArray(hex.size) { hw.firstPlaceIn(it) }
    /** Whether a hex has a stream its ground water seeps into; if not, the ground water goes on underground to the hex below. */
    @Transient private val stream = BooleanArray(hex.size) { meanFlow[it] >= CREEK }

    init {
        // Start each aquifer at the level where what comes in over a usual year (its share of the long-term
        // runoff, and what comes underground from the hexes above) matches what drains out.
        val stepsPerYear = Date.DAYS_PER_YEAR / days
        val inflow = DoubleArray(hex.size)
        for (h in order) {
            for (p in hw.placesOf(h)) {
                var input = intake[p] * mean(p) { world.climate.runoff[it] } / stepsPerYear
                if (p == valley[h]) input += (inflow[h] / land.areaM2[p] * 1000.0).toFloat()
                groundwater[p] = if (drain[p] > 0f) min(aquiferCap[p], input / drain[p]) else aquiferCap[p]
                val d = down[h]
                if (!stream[h] && d >= 0) inflow[d] += groundwater[p] * drain[p] / 1000.0 * land.areaM2[p]
            }
        }
    }
    /** Each aquifer's settled level, mm, against which springs weaken (at least a millimetre). */
    @Transient private val usualGround = FloatArray(n) { max(1f, groundwater[it]) }
    /** Flow of each hex's river over the last step, m³/s. */
    val flow = meanFlow.copyOf()
    /** Flood depth over each place's floodplain during the last step, m. */
    val flood = FloatArray(n)

    /**
     * Share of place [p] that is wet valley floor: the floodplain where the water table lies within reach
     * of roots, so that its turf and reeds grow on through a dry summer as long as it is growing weather
     * (the river meadows and marshes that stay green when the hills have cured).
     */
    fun wetFloor(p: Int) = min(1f - SEEP_SHARE, land.floodShare[p] * (1f - floorTableM(p) / ROOT_REACH_M).coerceIn(0f, 1f))
    /** Share of place [p] that is valley floor with the water table so near the surface ([SATURATED_M]) that its soil is sodden. */
    fun saturatedFloor(p: Int) = min(wetFloor(p), land.floodShare[p] * (1f - floorTableM(p) / SATURATED_M).coerceIn(0f, 1f))
    /** Share of place [p] whose soil is sodden this week, without air in it: marsh and bog, a flooded floodplain, or a valley floor over a high water table. */
    fun saturated(p: Int) = min(1f, land.waterlogged[p] + if (flood[p] > 0f) land.floodShare[p] else saturatedFloor(p))
    /**
     * Depth of the water table under the valley floor of place [p], m: where the hex's river runs, the
     * alluvium under its floodplain stands at the river's level, as far below the floodplain as the river
     * falls short of its bankfull depth (see [Channel]); where it has run dry, the ground water under the place.
     */
    fun floorTableM(p: Int): Float {
        val h = hw.placeHex[p]
        val regional = waterTableM(p)
        if (meanFlow[h] <= 0f || flow[h] <= 0f) return regional
        return min(regional, max(0f, Channel.depthM(Channel.bankfull(meanFlow[h])) - Channel.depthM(flow[h])))
    }
    /** How the seeps of place [p] run, 0..1: with the ground water under it, against a usual year (as its springs). */
    fun seeping(p: Int) = springFlow(p)

    internal fun step(weather: Weather) {
        val m = weather.lastMonth
        val share = days.toFloat() / Date.DAYS_PER_MONTH
        val volume = DoubleArray(hex.size)
        val underground = DoubleArray(hex.size)
        for (p in 0 until n) {
            val t = weather.temperature[p]
            val rain = if (t >= 0f) weather.precipitation[p] else 0f
            // Evaporation follows the week's heat against the month's normal (about 6% a degree) and the sun on the
            // slopes; plants draw at full rate until the soil is half dry, then less as it dries (the same stress their
            // growth feels). The sunny and the shaded slopes each keep their own soil water.
            val heat = exp(0.06f * (t - hw.placeTemp(p, m))).coerceIn(0.3f, 3f)
            fun bucket(water: Float, sun: Float): Float {
                val et = min(water, land.pet[m][p] * sun * share * heat * min(1f, water / (UNSTRESSED * soilCap[p])))
                return water - et
            }
            val sunny = bucket(soil[p] + rain + weather.meltSun[p], hw.sunlight.ratio[0][m][p])
            val shady = bucket(soilShade[p] + rain + weather.meltShade[p], hw.sunlight.ratio[1][m][p])
            soil[p] = min(sunny, soilCap[p]); soilShade[p] = min(shady, soilCap[p])
            val excess = (1 - land.slopeShade[p]) * max(0f, sunny - soilCap[p]) + land.slopeShade[p] * max(0f, shady - soilCap[p])
            // The turf of the seeps draws what it evaporates from the ground water seeping out under it.
            if (t > 0f) groundwater[p] = max(0f, groundwater[p] - SEEP_SHARE * land.pet[m][p] * share * heat * seeping(p))
            // The rock takes in its share of what the soil cannot hold, as far as the aquifer has room; the rest runs off.
            val taken = min(excess * intake[p], aquiferCap[p] - groundwater[p]).coerceAtLeast(0f)
            groundwater[p] += taken
            volume[hw.placeHex[p]] += (excess - taken) / 1000.0 * land.areaM2[p]
            ranOff[p] = excess - taken > 0f
            runoffMm[p] = excess - taken
            // The water in the bed sands sinks into the rock below.
            if (drink.bedM2[p] > 0f && drink.sand[p] > 0f) {
                val sink = min(drink.sand[p], HexDrinkingWater.SAND_DROP_M * days / HexDrinkingWater.SAND_M)
                drink.sand[p] -= sink
                val mm = (sink * HexDrinkingWater.SAND_M * HexDrinkingWater.SAND_YIELD * drink.bedM2[p] / land.areaM2[p] * 1000f)
                // (What the rock has no room for comes out in the stream.)
                val into = min(mm, aquiferCap[p] - groundwater[p]).coerceAtLeast(0f)
                groundwater[p] += into
                volume[hw.placeHex[p]] += (mm - into) / 1000.0 * land.areaM2[p]
            }
        }
        for (h in order) {
            // Ground water drains into the hex's stream, or on underground to the valley of the hex below.
            val v = valley[h]
            if (v >= 0 && underground[h] > 0.0) {
                val mm = (underground[h] / land.areaM2[v] * 1000.0).toFloat()
                val room = max(0f, aquiferCap[v] - groundwater[v])
                // What the valley's aquifer has no room for comes up as seeps and springs.
                groundwater[v] += min(mm, room)
                val over = max(0f, mm - room) / 1000.0 * land.areaM2[v]
                volume[h] += over
            }
            val d = down[h]
            for (p in hw.placesOf(h)) {
                val out = groundwater[p] * drain[p]
                groundwater[p] -= out
                val m3 = out / 1000.0 * land.areaM2[p]
                if (!stream[h] && d >= 0) underground[d] += m3 else volume[h] += m3
            }
            val raw = (volume[h] / land.stepSeconds).toFloat()
            rawMean[h] += (raw - rawMean[h]) * FLOW_MEMORY
            flow[h] = if (rawMean[h] > 0.01f && meanFlow[h] > 0f) raw * (meanFlow[h] / rawMean[h]).coerceIn(0.3f, 3f) else raw
            for (p in hw.placesOf(h)) {
                flood[p] = if (land.floodShare[p] <= 0f || meanFlow[h] < RiverClass.STREAM.minDischarge) 0f else Channel.floodStageM(flow[h], meanFlow[h])
            }
            // Where the aquifer lies below the river, the river loses water through its bed, and a flood
            // through the ground it covers, as fast as the bed lets it through and the aquifer has room.
            if (v >= 0 && volume[h] > 0.0) {
                val wetM2 = Channel.widthM(raw).toDouble() * hw.hex.hexKm * 1000.0
                var lose = 0.0
                for (p in hw.placesOf(h)) {
                    val bed = (if (p == v) wetM2 else 0.0) + (if (flood[p] > 0f) land.floodShare[p] * land.areaM2[p].toDouble() else 0.0)
                    if (bed <= 0.0) continue
                    soak[p] = (BED_CONDUCTIVITY * days * bed * (1f - groundwater[p] / aquiferCap[p]).coerceIn(0f, 1f)).toFloat()
                    lose += soak[p]
                }
                val share = if (lose > 0.0) min(1.0, volume[h] / lose) else 0.0
                if (lose > 0.0) for (p in hw.placesOf(h)) {
                    val bed = (if (p == v) wetM2 else 0.0) + (if (flood[p] > 0f) land.floodShare[p] * land.areaM2[p].toDouble() else 0.0)
                    if (bed <= 0.0) continue
                    val m3 = soak[p] * share
                    groundwater[p] += (m3 / land.areaM2[p] * 1000.0).toFloat(); volume[h] -= m3
                }
            }
            // Floods soak the floodplains, and where the water table is within reach of roots (and round
            // lakes and springs) the ground stays moist: the water is drawn from the ground water under
            // the place, then from what flows through the hex.
            var want = 0.0
            for (p in hw.placesOf(h)) {
                var need = soakTo(p, h) - soil[p]
                soak[p] = need.coerceAtLeast(0f)
                if (need <= 0f) continue
                val fromGround = min(need, groundwater[p])
                groundwater[p] -= fromGround; soil[p] += fromGround; need -= fromGround
                soak[p] = need
                if (need > 0f) want += need / 1000.0 * land.areaM2[p]
            }
            if (want > 0.0) {
                val share = min(1.0, volume[h] / want).toFloat()
                for (p in hw.placesOf(h)) {
                    if (soak[p] <= 0f) continue
                    val mm = soak[p] * share
                    soil[p] += mm; volume[h] -= mm / 1000.0 * land.areaM2[p]
                }
            }
            // Where the beds ran this week (the place shed water, or a flood came down), the water fills the sands under them.
            for (p in hw.placesOf(h)) {
                if (drink.bedM2[p] <= 0f || drink.sand[p] >= 1f) continue
                if (!ranOff[p] && flood[p] <= 0f) continue
                val m3 = min(volume[h], ((1f - drink.sand[p]) * HexDrinkingWater.SAND_M * HexDrinkingWater.SAND_YIELD * drink.bedM2[p]).toDouble())
                if (m3 <= 0.0) continue
                drink.sand[p] += (m3 / (HexDrinkingWater.SAND_M * HexDrinkingWater.SAND_YIELD * drink.bedM2[p])).toFloat(); volume[h] -= m3
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
        if (flood[p] > 0f) to = soilCap[p] * land.floodShare[p]
        // On the valley floor (the floodplain) deep roots (tamarisk, poplar, palms, reeds) reach the water
        // table where it lies within their reach; round fresh lakes and springs it is near the surface.
        // Their share of the place stays moist through the driest weeks.
        val valleyFloor = wetFloor(p)
        val edge = min(1f, valleyFloor + WATER_TABLE * (drink.nearLake[p] + drink.nearSpring[p] * springFlow(p)))
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
    /** Water each place shed over the ground this step, mm (it washes litter off, see [carrySilt]). */
    @Transient val runoffMm = FloatArray(n)

    /**
     * Carries what the runoff washed off each place ([washedKg], kg of organic matter) down the rivers with the
     * water, and lays the share [FLOOD_TRAP] of what passes a hex in spate on its flooded floodplains, with the
     * silt it travels with. Fills [laidKgM2] with what was laid on each place, kg/m²; the rest reaches the sea
     * or a closed lake, where it settles out of the model.
     */
    fun carrySilt(washedKg: FloatArray, laidKgM2: FloatArray) {
        laidKgM2.fill(0f)
        val load = DoubleArray(hex.size)
        for (p in 0 until n) load[hw.placeHex[p]] += washedKg[p].toDouble()
        for (h in order) {
            if (load[h] <= 0.0) continue
            var flooded = 0.0
            for (p in hw.placesOf(h)) if (flood[p] > 0f) flooded += land.floodShare[p] * land.areaM2[p]
            if (flooded > 0.0) {
                val laid = load[h] * FLOOD_TRAP
                for (p in hw.placesOf(h)) {
                    if (flood[p] <= 0f) continue
                    laidKgM2[p] += (laid * land.floodShare[p] * land.areaM2[p] / flooded / land.areaM2[p]).toFloat()
                }
                load[h] -= laid
            }
            val d = down[h]; if (d >= 0) load[d] += load[h]
        }
    }

    companion object {
        /** Share of the soil's water plants take up without stress; drier than that, growth and evaporation fall in step (FAO-56, p about 0.5). */
        const val UNSTRESSED = 0.5f
        /** How moist the water table keeps the ground round fresh lakes and springs, as a share of a full soil. */
        const val WATER_TABLE = 0.6f
        /**
         * Share of the land in seeps: where the ground water comes out in hollows and along the streams. The
         * streams run some 2.5 km to each km² of hill country (drainage density, about 2–4 in humid uplands)
         * with a wet strip some 40 m wide along them (both banks), from memory.
         */
        const val SEEP_SHARE = 2.5f * 0.04f
        /**
         * Streams: the least usual flow of a creek counted as drinking water (as [HexDrinkingWater.nearRiver] is set), m³/s;
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
        /** Depth to which phreatophytes (tamarisk, poplar, acacia, date palm) reach the water table, m (often 5-10 m, from memory). */
        const val ROOT_REACH_M = 5f
        /** Depth of water table the turf of a valley floor still draws on, m: grass roots and the capillary rise above the table (about 1-1.5 m, from memory). */
        const val TURF_REACH_M = 1.5f
        /**
         * Depth of the water table within which the soil above it is sodden: the capillary fringe of silty
         * alluvium rises some 0.5-1 m (from memory), so woody plants drown and dead matter rots without air.
         */
        const val SATURATED_M = 0.5f
        /**
         * Share of the organic matter a river carries that a hex in spate lays on its floodplain. A river's
         * floodplains keep some 20-50% of the load that passes in floods (from memory), over a flooding stretch
         * of some 10-30 hexes, so about 3% in each.
         */
        const val FLOOD_TRAP = 0.03
        /** Weekly weight of the newest flow in the running mean used to correct river flows (about two years). */
        const val FLOW_MEMORY = 0.01f
    }
}
