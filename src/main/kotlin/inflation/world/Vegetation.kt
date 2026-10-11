package inflation.world

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Plant life forms tracked per cell. Food, fodder, fuel and materials are drawn from these. */
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
    HERB(0.6f, 6f, 0.05f, 0.35f, 4f, 12f, -45f),
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
class Vegetation(private val world: World, seed: Long) {
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
    /** Worn paths, 0..1: rises with trampling, fades when unused. */
    val trails = FloatArray(n)
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
        val b = forms.getValue(form)
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
        val years = days / Date.DAYS_PER_YEAR.toFloat()
        val memory = 1 - exp(-days / 360f)
        if (stepCount++ % 5 == 0) seedSources()
        for (i in 0 until n) {
            if (!isVegetated(i) || water.submerged[i]) {
                litterfall[i] = biomass(i)
                herb[i] = 0f; shrub[i] = 0f; tree[i] = 0f
                continue
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
            grow(LifeForm.TREE, tree, i, season * water0 * years, treeSeed[i])
            grow(LifeForm.SHRUB, shrub, i, season * water0 * years * (1 - 0.6f * shade), shrubSeed[i])
            grow(LifeForm.HERB, herb, i, season * water0 * years * (1 - 0.8f * shade), 1f) // seed bank and roots everywhere

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

            val burned = fire(i, t, wet, years)
            // Living plants shed leaves, stems and roots all the time; deaths add the rest. Burned matter is gone.
            litterfall[i] = max(0f, before - biomass(i) - burned) + years * (herb[i] + 0.2f * shrub[i] + 0.04f * tree[i])

            // Trampling beats down herbs and shrubs and wears a path; paths fade in a few years.
            val tr = trampling[i]
            if (tr > 0f) {
                herb[i] *= exp(-0.05f * tr); shrub[i] *= exp(-0.1f * tr)
                trails[i] = min(1f, trails[i] + 0.5f * tr)
                trampling[i] = 0f
            } else if (trails[i] > 0f) trails[i] = max(0f, trails[i] - 0.3f * years)
            if (burnt[i] > 0f) burnt[i] = max(0f, burnt[i] - 0.4f * years)
        }
    }

    private fun grow(form: LifeForm, b: FloatArray, i: Int, time: Float, seedRain: Float) {
        val cap = ceiling(form, i)
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

    /** Returns the biomass burned, kg/m². */
    private fun fire(i: Int, t: Float, wet: Float, years: Float): Float {
        val fuel = herb[i] + 0.5f * shrub[i]
        if (fuel < 0.2f || wet > 0.3f || t < 18f) return 0f
        // A few % per year in dry, hot, fuel-rich Mediterranean scrub; far less elsewhere.
        val p = 0.04f * years * min(1f, fuel / 1.5f) * ((t - 18f) / 15f).coerceIn(0f, 1f) * (1 - wet / 0.3f)
        if (rnd.nextFloat() >= p) return 0f
        val before = biomass(i)
        herb[i] *= 0.1f; shrub[i] *= 0.2f; tree[i] *= 0.7f
        burnt[i] = 1f
        return before - biomass(i)
    }

    /** Seed availability for trees and shrubs: biomass within ~5 km, normalised. */
    private fun seedSources() {
        val r = (5 / grid.cellKm).toInt().coerceAtLeast(1)
        treeSeed = blur(grid, FloatArray(n) { min(1f, tree[it] / 5f) }, r)
        shrubSeed = blur(grid, FloatArray(n) { min(1f, shrub[it] / 1f) }, r)
    }
}
