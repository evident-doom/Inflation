package inflation.world

import inflation.Tuning
import inflation.HASH_A
import inflation.HASH_B
import inflation.HASH_C
import inflation.mix64
import inflation.unitOf
import inflation.Config
import inflation.world.HexFires.Companion.GRAZE_SCALE
import inflation.world.HexFires.Companion.SPREAD_KM_PER_DAY
import inflation.world.HexPlants.Companion.EROSION
import inflation.world.HexPlants.Companion.LAWN_SCALE
import inflation.world.HexPlants.Companion.ROOT_SCALE
import inflation.world.HexPlants.Companion.SOIL_RECOVERY_YEARS
import inflation.world.HexPlants.Companion.TRAIL_FADE
import inflation.world.HexPlants.Companion.TRAIL_WEAR

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Plant life forms tracked per cell. Food, fodder, fuel and materials are drawn from these.
 * (Herbs' fastest growth: a short sward in full warmth and water doubles in about three weeks, as spring
 * pasture and semi-arid grassland put on 1-3 g/m² a day from a low stand, from memory; slower growth would
 * leave a dry country's spring over before its grass had grown.)
 */
enum class LifeForm(
    /** Largest standing biomass in the best conditions, kg dry matter per m². */
    val maxBiomass: Float,
    /** Fastest relative growth, per year. */
    val growthPerYear: Float,
    /** Long-term soil moisture (0..1) below which the form cannot persist, and where it thrives. */
    val moistureMin: Float,
    val moistureFull: Float,
    /** Growing-season temperature (warmest month, °C) below which it cannot persist, and where it thrives. */
    val warmthMin: Float,
    val warmthFull: Float,
    /** Air temperature that kills it outright, °C. */
    val frostKill: Float,
) {
    HERB(0.6f, 12f, 0.05f, 0.35f, 4f, 12f, -45f),
    SHRUB(3f, 0.8f, 0.12f, 0.45f, 6f, 14f, -30f),
    TREE(25f, 0.25f, 0.3f, 0.7f, 10f, 16f, -25f),
}

/**
 * Living vegetation that responds to the weather, the soil and disturbance instead of being fixed
 * by climate. Each cell holds standing biomass of herbs (grasses and forbs), shrubs and trees.
 *
 * - Growth is logistic towards a ceiling set by the cell's recent moisture and warmth (running
 *   averages over about a year) and its soil fertility, which comes from the bedrock, slope and
 *   volcanic ash. Above the ceiling, plants die back.
 * - Trees shade herbs and shrubs. Trees and shrubs only establish from seed sources nearby, so
 *   forest takes decades to recolonise a cleared area; herbs come back within a season.
 * - Disturbances: drought and heat stress, killing frosts, deep floods, ash burial, and fire,
 *   which is likelier in hot, dry weather with plenty of dry fuel.
 * - Animals and people act through [consume] and [trample]: grazing and browsing remove biomass,
 *   and repeated passage beats down vegetation and wears [trails].
 */
class Vegetation(private val world: World, private val seed: Long, private val tuning: Tuning = Tuning.config) {
    private val grid = world.grid
    private val n = grid.size
    private val rnd = Random(seed * 13 + 1)

    /** Standing biomass per life form, kg/m². */
    val herb = FloatArray(n)
    val shrub = FloatArray(n)
    val tree = FloatArray(n)
    /** Running averages of soil moisture (0..1) and temperature (°C) over about a year. */
    val moisture = FloatArray(n)
    private val warmth = FloatArray(n)
    /** Recently burnt, 0..1, fading over a few years. */
    val burnt = FloatArray(n)
    /** Cells burning this step. */
    val burning = BooleanArray(n)
    /** Fires this step: the cell each started in and the km² it burned. */
    val fires = ArrayList<Fire>()
    /** Worn paths, 0..1: rises with trampling, fades when unused. */
    val trails = FloatArray(n)
    /**
     * Browsing and grazing by animals lately, kg of food per km² per day (set by the ecology).
     * Browsing holds back seedlings, saplings and shrubs; grazing keeps grass short and removes the
     * dry fuel fires need.
     */
    val browsing = FloatArray(n)
    val grazing = FloatArray(n)
    /** Rooting by boar lately, kg of roots dug per km² per day: churned soil, seedbeds, thinned turf. */
    val rooting = FloatArray(n)
    /** Seed of shrubs and trees carried here by fruit-eating animals lately, 0..1. */
    val animalSeed = FloatArray(n)
    private val trampling = FloatArray(n)
    /** Soil fertility including ash enrichment, 0..1. */
    val fertility = world.soilFertility.copyOf()
    /** Extra fertility from decomposed organic matter, set by the soil life (0..0.3). */
    val organicFertility = FloatArray(n)
    /** Dead plant matter shed during the last step, kg/m²: turnover, die-back, frost, flood and ash kill. */
    val litterfall = FloatArray(n)
    private var treeSeed = FloatArray(n)
    private var shrubSeed = FloatArray(n)
    private var stepCount = 0

    val forms: Map<LifeForm, FloatArray> = mapOf(LifeForm.HERB to herb, LifeForm.SHRUB to shrub, LifeForm.TREE to tree)

    init {
        // Start from the long-term climate: moisture from the aridity index, warmth from the warmest month.
        for (i in 0 until n) {
            if (!isVegetated(i)) continue
            moisture[i] = (world.climate.aridity(i) / 1.5f).coerceIn(0f, 1f)
            warmth[i] = world.climate.warmestMonthTemp(i)
            for ((form, b) in forms) b[i] = ceiling(form, i) * 0.8f
        }
        seedSources()
    }

    fun isVegetated(i: Int) = !world.isSea[i] && !world.water.isLake(i)

    /** Total standing biomass, kg/m². */
    fun biomass(i: Int) = herb[i] + shrub[i] + tree[i]

    /** Tree canopy cover, 0..1. */
    fun canopy(i: Int) = min(1f, tree[i] / (0.4f * LifeForm.TREE.maxBiomass))

    /** The biomass a form can sustain here, kg/m². */
    fun ceiling(form: LifeForm, i: Int): Float {
        val m = ((moisture[i] - form.moistureMin) / (form.moistureFull - form.moistureMin)).coerceIn(0f, 1f)
        val t = ((warmth[i] - form.warmthMin) / (form.warmthFull - form.warmthMin)).coerceIn(0f, 1f)
        val soil = 0.3f + 0.7f * min(1f, fertility[i] + organicFertility[i])
        // Waterlogged ground: woody plants drown where the soil is saturated much of the year.
        val logged = when (world.biome[i]) {
            Biome.MARSH, Biome.BOG, Biome.SALT_MARSH -> if (form == LifeForm.HERB) 1f else if (form == LifeForm.SHRUB) 0.4f else 0.05f
            else -> 1f
        }
        return form.maxBiomass * m * t * soil * logged
    }

    /**
     * Removes up to [kgPerM2] of [form] from cell [i] (grazing, browsing, gathering, felling).
     * Returns what was actually taken.
     */
    fun consume(i: Int, form: LifeForm, kgPerM2: Float): Float {
        val b = when (form) { LifeForm.HERB -> herb; LifeForm.SHRUB -> shrub; LifeForm.TREE -> tree }
        val taken = min(b[i], max(0f, kgPerM2))
        b[i] -= taken
        return taken
    }

    /**
     * Records passage through cell [i]. [intensity] 1 is about 150 tonnes of animals (or people)
     * passing, a large herd of big game; a dozen gazelles crossing is about 0.002.
     */
    fun trample(i: Int, intensity: Float) { trampling[i] += intensity }

    /** Advances one step using the weather, water and eruptions of that step. */
    fun step(weather: Weather, water: LiveWater, ash: FloatArray) {
        val days = world.config.daysPerStep
        val years = Date.years(days)
        val memory = 1 - exp(-days / 360f)
        if (stepCount++ % 5 == 0) seedSources()
        spreadFires(weather, water, years)
        val slope = world.terrain.slope
        // Cells are independent of each other, so they are stepped in parallel.
        java.util.stream.IntStream.range(0, n).parallel().forEach { i ->
            if (!isVegetated(i) || water.submerged[i]) {
                litterfall[i] = biomass(i)
                herb[i] = 0f; shrub[i] = 0f; tree[i] = 0f
                return@forEach
            }
            val before = biomass(i)
            val t = weather.temperature[i]
            val wet = water.soil[i] / LiveWater.SOIL_CAPACITY
            moisture[i] += (wet - moisture[i]) * memory
            // Warmth tracks the warmest conditions; a cold year lowers it slowly.
            warmth[i] = if (t > warmth[i]) warmth[i] + (t - warmth[i]) * 0.5f else warmth[i] + (t - warmth[i]) * memory * 0.1f

            // Growth needs warmth now and water now.
            val season = ((t - 2f) / 13f).coerceIn(0f, 1f) * if (t > 38f) 0.3f else 1f
            val water0 = (wet / 0.5f).coerceIn(0f, 1f)
            val shade = canopy(i)
            // Browsers eat seedlings and saplings first: young stands and seed establishment suffer
            // most, so heavily browsed land stays open and lightly browsed land grows over.
            val browsed = 1f - exp(-browsing[i] / tuning.browseScale)
            val young = 1f - min(1f, tree[i] / (0.3f * ceiling(LifeForm.TREE, i).coerceAtLeast(1e-3f)))
            // Rooted, churned soil makes a seedbed; fruit-eaters drop seed far from any parent tree.
            val rooted = 1f - exp(-rooting[i] / ROOT_SCALE)
            val treeSeedHere = max(treeSeed[i], 0.5f * animalSeed[i]) * (1f + 1.5f * rooted)
            val shrubSeedHere = max(shrubSeed[i], animalSeed[i]) * (1f + 1.5f * rooted)
            grow(LifeForm.TREE, tree, i, season * water0 * years * (1 - 0.8f * browsed * young), treeSeedHere * (1 - 0.9f * browsed))
            grow(LifeForm.SHRUB, shrub, i, season * water0 * years * (1 - 0.6f * shade) * (1 - 0.5f * browsed), shrubSeedHere * (1 - 0.8f * browsed))
            // Years of hard grazing make a lawn: short turf, less standing grass. Rooting thins it too.
            val lawn = 1f - exp(-grazing[i] / LAWN_SCALE)
            grow(LifeForm.HERB, herb, i, season * water0 * years * (1 - 0.8f * shade), 1f, 1f - 0.5f * lawn - 0.2f * rooted) // seed bank and roots everywhere

            // Killing frost: well below what the plants here are used to.
            for ((form, b) in forms) if (t < form.frostKill) b[i] *= 0.3f
            // Heat and drought stress beyond the ceiling are handled by die-back in grow(); deep floods drown.
            val flood = water.flood[i]
            if (flood > 1f) { herb[i] *= 0.5f; shrub[i] *= 0.9f }
            if (flood > 3f) tree[i] *= 0.97f
            // Ash: a few cm smothers herbs; tens of cm kill shrubs; half a metre kills forest.
            val a = ash[i]
            if (a > 20f) herb[i] *= exp(-a / 100f)
            if (a > 100f) shrub[i] *= exp(-a / 400f)
            if (a > 300f) tree[i] *= exp(-a / 800f)
            if (a > 1f) fertility[i] = min(1f, fertility[i] + a * 1e-6f) // weathered ash makes rich soil
            // Soil drifts back towards what its rock and climate give it: worn-out soil recovers over
            // decades as rock weathers and plants fix nitrogen; the flush from ash and fire fades.
            fertility[i] += (world.soilFertility[i] - fertility[i]) * years / SOIL_RECOVERY_YEARS
            // Rain on bare slopes washes the topsoil away: cleared, burned or grazed-bare hillsides
            // lose their fertility within years; grass, scrub and forest hold it.
            val cover = min(1f, herb[i] / 0.3f + shrub[i] + canopy(i))
            val bare = (1f - cover) * (1f - cover)
            if (bare > 0.01f) fertility[i] *= 1f - min(0.5f, EROSION * bare * weather.precipitation[i] / 50f * slope[i] / 0.2f * years)

            val burned = if (burning[i]) burn(i) else 0f
            // Living plants shed leaves, stems and roots all the time; deaths add the rest. Burned matter is gone.
            litterfall[i] = max(0f, before - biomass(i) - burned) + years * (herb[i] + 0.2f * shrub[i] + 0.04f * tree[i])

            // Trampling beats down herbs and shrubs and wears a path; paths fade in a few years.
            val tr = trampling[i]
            if (tr > 0f) {
                herb[i] *= exp(-0.05f * tr); shrub[i] *= exp(-0.1f * tr)
                trails[i] = min(1f, trails[i] + TRAIL_WEAR * tr * (1f - trails[i]))
                trampling[i] = 0f
            } else if (trails[i] > 0f) trails[i] = max(0f, trails[i] - TRAIL_FADE * years)
            if (burnt[i] > 0f) burnt[i] = max(0f, burnt[i] - 0.4f * years)
        }
    }

    private fun grow(form: LifeForm, b: FloatArray, i: Int, time: Float, seedRain: Float, capFactor: Float = 1f) {
        val cap = ceiling(form, i) * capFactor
        val v = b[i]
        if (v < cap) {
            val r = form.growthPerYear * time
            // Logistic growth plus establishment from seed; a little remains as seed bank.
            b[i] = v + r * (v + seedRain * cap * 0.05f) * (1 - v / cap.coerceAtLeast(1e-6f))
        } else {
            // Die-back towards what the conditions can carry: faster for herbs, slow for trees.
            b[i] = v - (v - cap) * min(1f, 2f * time + 0.002f)
        }
        if (b[i] < 0f) b[i] = 0f
    }

    /** A uniform 0..1 draw for cell [i] this step: a hash, so that cells can be stepped in any order. */
    private fun cellRandom(i: Int): Float {
        return unitOf(mix64(seed * HASH_A + stepCount * HASH_A + i * HASH_B))
    }

    /** Fuel in cell [i] that can carry a fire, kg/m²: grass and shrubs, less what grazing has removed. */
    private fun fuel(i: Int) = (herb[i] + 0.5f * shrub[i]) * (1f - 0.8f * (1f - exp(-grazing[i] / GRAZE_SCALE)))

    /** Whether cell [i] is dry and hot enough to burn this step. */
    private fun canBurn(i: Int, weather: Weather, water: LiveWater): Boolean {
        if (!isVegetated(i) || water.submerged[i] || water.flood[i] > 0f || water.discharge[i] >= 1f) return false
        val wet = water.soil[i] / LiveWater.SOIL_CAPACITY
        return fuel(i) >= 0.2f && wet < 0.3f && weather.temperature[i] > 15f && weather.precipitation[i] < 5f
    }

    /** Fires people have set, to be lit at the start of the next step (where the ground will burn). */
    private val lit = java.util.concurrent.ConcurrentLinkedQueue<Int>()

    /** Sets a fire at [i] next step: people burning off old grass to bring on new growth and draw game. */
    fun ignite(i: Int) { lit += i }

    /**
     * Fires: lightning and dry-season sparks start them in hot, dry, fuel-rich ground, and each
     * spreads through the step from cell to cell, fastest downwind and the faster the stronger the wind,
     * until it runs out of fuel, reaches wet or grazed-down ground, water or bare rock, or has
     * burned for the step. Many fires stay small; a few run for tens of km.
     */
    private fun spreadFires(weather: Weather, water: LiveWater, years: Float) {
        burning.fill(false)
        fires.clear()
        val days = world.config.daysPerStep
        val maxSteps = (days * SPREAD_KM_PER_DAY / grid.cellKm).toInt()
        val queue = ArrayDeque<Int>()
        val set = HashSet<Int>()
        while (true) set += lit.poll() ?: break
        for (i in 0 until n) {
            if (burning[i] || !canBurn(i, weather, water)) continue
            val heat = ((weather.temperature[i] - 15f) / 20f).coerceIn(0f, 1f)
            val p = tuning.ignitionsPerKm2Year * (grid.cellKm * grid.cellKm).toFloat() * years * min(1f, fuel(i) / 1.5f) * heat
            if (i !in set && cellRandom(i) >= p) continue
            // A fire: spread breadth-first, each front cell lighting its neighbours by chance.
            var cells = 0
            queue.clear(); queue += i; burning[i] = true
            val depth = HashMap<Int, Int>(); depth[i] = 0
            while (queue.isNotEmpty()) {
                val c = queue.removeFirst(); cells++
                val d = depth.getValue(c)
                if (d >= maxSteps) continue
                val cx = grid.x(c); val cy = grid.y(c)
                val u = weather.windEast(c); val v = weather.windSouth(c)
                val speed = kotlin.math.hypot(u, v)
                for (k in 0 until 8) {
                    val dx = DX[k]; val dy = DY[k]
                    if (!grid.inBounds(cx + dx, cy + dy)) continue
                    val nb = grid.index(cx + dx, cy + dy)
                    if (burning[nb] || !canBurn(nb, weather, water)) continue
                    // Downwind burns readily, upwind barely, more so in a strong wind; dense fuel carries it on.
                    val along = if (speed > 0.1f) (dx * u + dy * v) / (speed * kotlin.math.hypot(dx.toFloat(), dy.toFloat())) else 0f
                    val wind = 0.35f + 0.3f * min(1f, speed / 8f) * along
                    val chance = SPREAD * wind * min(1f, fuel(nb) / 1.0f)
                    if (hash(nb, k) >= chance) continue
                    burning[nb] = true; depth[nb] = d + 1; queue += nb
                }
            }
            fires += Fire(i, cells * (grid.cellKm * grid.cellKm).toFloat())
        }
    }

    /** Burns cell [i]: grass and shrubs mostly gone, trees scorched; returns the biomass burned, kg/m². */
    private fun burn(i: Int): Float {
        val before = biomass(i)
        herb[i] *= 0.1f; shrub[i] *= 0.2f; tree[i] *= 0.7f
        burnt[i] = 1f
        // The ash briefly enriches the soil.
        fertility[i] = min(1f, fertility[i] + 0.01f)
        return before - biomass(i)
    }

    private fun hash(i: Int, salt: Int): Float {
        return unitOf(mix64(seed * HASH_A + stepCount * HASH_A + i * HASH_B + salt * HASH_C))
    }


    /** Seed availability for trees and shrubs: biomass within ~5 km, normalised. */
    private fun seedSources() {
        val r = (5 / grid.cellKm).toInt().coerceAtLeast(1)
        treeSeed = blur(grid, FloatArray(n) { min(1f, tree[it] / 5f) }, r)
        shrubSeed = blur(grid, FloatArray(n) { min(1f, shrub[it] / 1f) }, r)
    }

    companion object {
        /** Chance a burning cell lights a downwind neighbour with full fuel. */
        const val SPREAD = 0.9f
        private val DX = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
        private val DY = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)
    }
}
