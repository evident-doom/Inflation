package inflation.world

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
 * - Herbs, shrubs and trees grow towards ceilings set by recent moisture, warmth and soil; trees
 *   shade the rest; seed comes from the place itself and its neighbours (the bands above and below
 *   in the hex, the same heights in the hexes around); frost, floods, ash and fire set them back.
 * - A fire burns a share of a place and spreads to neighbouring places, more readily downwind.
 * - Grazing, browsing, rooting, trampling and seed carried by animals are set by the ecology.
 */
class HexLand(private val hw: HexWorld, private val seed: Long) {
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
    /** Share of waterlogged ground (marsh, bog, salt marsh), where woody plants drown. */
    private val logged = FloatArray(n) { p -> mean(p) { if (world.biome[it] == Biome.MARSH || world.biome[it] == Biome.BOG || world.biome[it] == Biome.SALT_MARSH) 1f else 0f } }
    /** Share of floodplain, which a river in spate covers. */
    val floodShare = FloatArray(n) { p -> mean(p) { if (world.floodplain.isFloodplain[it]) 1f else 0f } }
    private val pet = Array(12) { m -> FloatArray(n) { p -> mean(p) { world.climate.monthlyPet[m][it] } } }
    /** Mean ruggedness, cover from broken ground and the best refuge and lookout of each place. */
    val ruggedness = FloatArray(n) { p -> mean(p) { world.terrain.ruggedness[it] } }
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
        for (h in 0 until hex.size) {
            val m = main[h]
            if (m < 0) continue
            meanFlow[h] = world.water.discharge[m]
            var i = m
            var steps = 0
            while (steps++ < 10_000) {
                val r = receiver[i]
                if (r == i || world.isSea[r]) break
                if (hex.owner[r] != h) { down[h] = hex.owner[r]; break }
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
    val soil = FloatArray(n) { LiveWater.SOIL_CAPACITY * 0.6f }
    val groundwater = FloatArray(n) { p -> LiveWater.RECHARGE * mean(p) { world.climate.runoff[it] } / (Date.DAYS_PER_YEAR / days) / LiveWater.BASEFLOW }
    /** Flow of each hex's river over the last step, m³/s. */
    val flow = meanFlow.copyOf()
    /** Flood depth over each place's floodplain during the last step, m. */
    val flood = FloatArray(n)

    val herb = FloatArray(n); val shrub = FloatArray(n); val tree = FloatArray(n)
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
    /** Set by the ecology: browsing, grazing and rooting lately, kg/km²/day; seed carried by animals, 0..1; trampling. */
    val browsing = FloatArray(n); val grazing = FloatArray(n); val rooting = FloatArray(n); val animalSeed = FloatArray(n)
    private val trampling = FloatArray(n)
    /**
     * How green each place's forage is this week, 0..1: young growth in warm, moist weeks; dry or
     * dormant stuff in drought, heat and cold. Grazers get full nourishment from green forage and a
     * fraction from the rest (see [nourishment]).
     */
    val green = FloatArray(n)

    /** Nourishment per kg of forage in place [p] this week, against fresh growth: about a third when dry or dormant. */
    fun nourishment(p: Int) = DRY_VALUE + (1f - DRY_VALUE) * green[p]

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

    /** Advances one step with the weather of that step on the same places. */
    fun step(weather: Weather) {
        stepWater(weather)
        stepPlants(weather)
    }

    private fun stepWater(weather: Weather) {
        val m = weather.lastMonth
        val share = days.toFloat() / Date.DAYS_PER_MONTH
        val volume = DoubleArray(hex.size)
        for (p in 0 until n) {
            val rain = if (weather.temperature[p] >= 0f) weather.precipitation[p] else 0f
            var s = soil[p] + rain + weather.meltwater[p]
            val et = min(s, pet[m][p] * share * min(1f, s / LiveWater.SOIL_CAPACITY))
            s -= et
            val excess = max(0f, s - LiveWater.SOIL_CAPACITY)
            soil[p] = s - excess
            val base = groundwater[p] * LiveWater.BASEFLOW
            groundwater[p] += excess * LiveWater.RECHARGE - base
            volume[hw.placeHex[p]] += (excess * (1 - LiveWater.RECHARGE) + base) / 1000.0 * areaM2[p]
        }
        for (h in order) {
            val raw = (volume[h] / stepSeconds).toFloat()
            rawMean[h] += (raw - rawMean[h]) * FLOW_MEMORY
            flow[h] = if (rawMean[h] > 0.01f && meanFlow[h] > 0f) raw * (meanFlow[h] / rawMean[h]).coerceIn(0.3f, 3f) else raw
            val d = down[h]
            if (d >= 0) volume[d] += volume[h]
        }
        for (p in 0 until n) {
            val h = hw.placeHex[p]
            flood[p] = if (floodShare[p] <= 0f || meanFlow[h] < RiverClass.STREAM.minDischarge) 0f else Channel.floodStageM(flow[h], meanFlow[h])
        }
    }

    private fun stepPlants(weather: Weather) {
        val years = days / Date.DAYS_PER_YEAR.toFloat()
        val memory = 1 - exp(-days / 360f)
        if (stepCount++ % 5 == 0) seedSources()
        spreadFires(weather, years)
        java.util.stream.IntStream.range(0, n).parallel().forEach { p ->
            val before = biomass(p)
            val t = weather.temperature[p]
            val wet = soil[p] / LiveWater.SOIL_CAPACITY
            moisture[p] += (wet - moisture[p]) * memory
            warmth[p] = if (t > warmth[p]) warmth[p] + (t - warmth[p]) * 0.5f else warmth[p] + (t - warmth[p]) * memory * 0.1f
            val season = ((t - 2f) / 13f).coerceIn(0f, 1f) * if (t > 38f) 0.3f else 1f
            val water0 = (wet / 0.5f).coerceIn(0f, 1f)
            // Greenness follows growth, with a lag: a flush takes some weeks to come and to cure.
            green[p] += (season * water0 - green[p]) * GREEN_RATE
            val shade = canopy(p)
            val browsed = 1f - exp(-browsing[p] / Vegetation.BROWSE_SCALE)
            val young = 1f - min(1f, tree[p] / (0.3f * ceiling(LifeForm.TREE, p).coerceAtLeast(1e-3f)))
            val rooted = 1f - exp(-rooting[p] / Vegetation.ROOT_SCALE)
            val treeSeedHere = max(treeSeed[p], 0.5f * animalSeed[p]) * (1f + 1.5f * rooted)
            val shrubSeedHere = max(shrubSeed[p], animalSeed[p]) * (1f + 1.5f * rooted)
            grow(LifeForm.TREE, tree, p, season * water0 * years * (1 - 0.8f * browsed * young), treeSeedHere * (1 - 0.9f * browsed))
            grow(LifeForm.SHRUB, shrub, p, season * water0 * years * (1 - 0.6f * shade) * (1 - 0.5f * browsed), shrubSeedHere * (1 - 0.8f * browsed))
            val lawn = 1f - exp(-grazing[p] / Vegetation.LAWN_SCALE)
            grow(LifeForm.HERB, herb, p, season * water0 * years * (1 - 0.8f * shade), 1f, 1f - 0.5f * lawn - 0.2f * rooted)
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
            litterfall[p] = max(0f, before - biomass(p) - burned) + years * (herb[p] + 0.2f * shrub[p] + 0.04f * tree[p])
            // Trampling, spread over the place: a herd beats down a share of it.
            val tr = trampling[p] / areaKm2[p]
            if (tr > 0f) {
                herb[p] *= exp(-0.05f * tr); shrub[p] *= exp(-0.1f * tr)
                trails[p] = min(1f, trails[p] + Vegetation.TRAIL_WEAR * tr * (1f - trails[p]))
                trampling[p] = 0f
            } else if (trails[p] > 0f) trails[p] = max(0f, trails[p] - Vegetation.TRAIL_FADE * years)
            if (burnt[p] > 0f) burnt[p] = max(0f, burnt[p] - 0.4f * years)
        }
    }

    private fun grow(form: LifeForm, b: FloatArray, p: Int, time: Float, seedRain: Float, capFactor: Float = 1f) {
        val cap = ceiling(form, p) * capFactor
        val v = b[p]
        if (v < cap) b[p] = v + form.growthPerYear * time * (v + seedRain * cap * 0.05f) * (1 - v / cap.coerceAtLeast(1e-6f))
        else b[p] = v - (v - cap) * min(1f, 2f * time + 0.002f)
        if (b[p] < 0f) b[p] = 0f
    }

    private fun fuel(p: Int) = (herb[p] + 0.5f * shrub[p]) * (1f - 0.8f * (1f - exp(-grazing[p] / Vegetation.GRAZE_SCALE)))

    private fun canBurn(p: Int, weather: Weather) =
        flood[p] <= 0f && fuel(p) >= 0.2f && soil[p] / LiveWater.SOIL_CAPACITY < 0.3f && weather.temperature[p] > 15f && weather.precipitation[p] < 5f

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
            var reach = 0
            while (queue.isNotEmpty() && reach++ < MAX_SPREAD) {
                val c = queue.removeFirst()
                val (cx, cy) = hex.centreKm(hw.placeHex[c])
                val u = weather.windEast(c); val v = weather.windSouth(c)
                val speed = kotlin.math.hypot(u, v)
                for (nb in neighbours[c]) {
                    if (burning[nb] > 0f || !canBurn(nb, weather)) continue
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
        /** Most places a fire runs through in a step. */
        const val MAX_SPREAD = 60
        /** Nourishment of dry or dormant forage against fresh growth; weekly share by which greenness follows growth. */
        const val DRY_VALUE = 0.35f
        const val GREEN_RATE = 0.3f
        /** Weekly weight of the newest flow in the running mean used to correct river flows (about two years). */
        const val FLOW_MEMORY = 0.01f
    }
}
