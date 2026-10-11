package inflation.ecology

import inflation.world.Biome
import inflation.world.Date
import inflation.world.LifeForm
import inflation.world.Simulation
import inflation.world.blur
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Plankton in the sea and in lakes. Phytoplankton grow with light, warmth and nutrients;
 * zooplankton graze them. Nutrients are high on shallow shelves and where rivers enter the sea,
 * low over deep open water. Winter storms and cooling mix nutrients up from below; summer
 * warmth layers the water and starves the surface. Together these give a spring bloom and a
 * thin summer, and much richer coasts than open ocean.
 *
 * Units: g dry matter per m².
 */
class Plankton(private val sim: Simulation) {
    private val world = sim.world
    private val grid = world.grid
    private val n = grid.size
    /** Where the water is: sea, or freshwater lakes. */
    val isWater = BooleanArray(n) { world.isSea[it] || world.water.isLake(it) }
    /** Nutrient supply the water column is mixed towards, 0..1. */
    val supply = FloatArray(n)
    /** Dissolved nutrients available to phytoplankton now, 0..1. */
    val nutrients = FloatArray(n)
    val phyto = FloatArray(n)
    val zoo = FloatArray(n)

    init {
        // River mouths: discharge entering the sea, spread over ~20 km of coastal water.
        val mouth = FloatArray(n)
        val d = world.water.drainage
        for (i in 0 until n) {
            if (world.isSea[i]) continue
            val r = d.receiver[i]
            if (r != i && world.isSea[r]) mouth[r] += world.water.discharge[i]
        }
        val spread = blur(grid, blur(grid, mouth, (10 / grid.cellKm).toInt().coerceAtLeast(1)), (10 / grid.cellKm).toInt().coerceAtLeast(1))
        for (i in 0 until n) {
            if (!isWater[i]) continue
            supply[i] = if (world.water.isLake(i)) {
                if (world.water.lakeClosed[i]) 0.2f else 0.6f
            } else {
                val depth = -world.elevation[i]
                val shelf = 0.15f + 0.45f * exp(-max(0f, depth) / 150f)
                min(1f, shelf + 0.4f * min(1f, spread[i] * 20f))
            }
            nutrients[i] = supply[i]
            phyto[i] = 2f * supply[i]
            zoo[i] = 0.5f * supply[i]
        }
    }

    fun step() {
        val days = world.config.daysPerStep
        val date = sim.weather.date
        val light = (Date(date.absoluteDay).dayLengthHours(35.0) / 12.0).toFloat()
        for (i in 0 until n) {
            if (!isWater[i]) continue
            val t = sim.weather.temperature[i]
            // Cold water mixes deep; warm water stratifies.
            val mixing = if (t < 14f) 0.25f else if (t < 20f) 0.08f else 0.02f
            // Nutrients are counted as the phytoplankton they can build, g/m²; a full supply builds [NUTRIENT_SCALE].
            val full = NUTRIENT_SCALE * supply[i]
            var nu = nutrients[i] * NUTRIENT_SCALE; var p = phyto[i]; var z = zoo[i]
            val warm = ((t + 2f) / 22f).coerceIn(0.1f, 1.2f)
            repeat(days) {
                val growth = min(nu, 0.9f * light * warm * p * nu / (nu + 0.15f * NUTRIENT_SCALE))
                val grazing = min(p, 0.6f * z * p / (p + 3f))
                // Zooplankton are themselves eaten by fish and jellies, more so where they are dense.
                val pDeath = 0.05f * p; val zDeath = 0.05f * z + 0.15f * z * z
                // Dead plankton and waste sink; about half is recycled near the surface.
                nu = max(0f, nu - growth + mixing * (full - nu) + 0.5f * (pDeath + zDeath + 0.7f * grazing))
                p = max(0.01f, p + growth - grazing - pDeath)
                z = max(0.005f, z + 0.3f * grazing - zDeath)
            }
            nutrients[i] = nu / NUTRIENT_SCALE; phyto[i] = p; zoo[i] = z
        }
    }

    companion object {
        /** Phytoplankton, g/m², that a full nutrient supply can sustain. */
        const val NUTRIENT_SCALE = 10f
    }
}

/**
 * Life in and on the soil: plant litter decomposes at a rate set by warmth and moisture, part of
 * it becoming soil organic matter, which raises fertility. Carcasses rot quickly in heat and are
 * stripped by insects; scavengers take a share first. Soil fauna (earthworms, mites, springtails,
 * fungi) are not tracked as species; their biomass follows the decomposition they do.
 *
 * Units: kg/m² for litter, organic matter and carrion; g/m² for soil fauna.
 */
class SoilLife(private val sim: Simulation) {
    private val world = sim.world
    private val n = world.grid.size
    val litter = FloatArray(n)
    val organicMatter = FloatArray(n)
    val carrion = FloatArray(n)
    val soilFauna = FloatArray(n)

    init {
        // Start near the steady state for each cell's current vegetation.
        val v = sim.vegetation
        for (i in 0 until n) {
            if (!v.isVegetated(i)) continue
            litter[i] = 0.5f * (v.herb[i] + 0.2f * v.shrub[i] + 0.04f * v.tree[i]) + 0.3f
            organicMatter[i] = 4f * min(1f, v.biomass(i) / 5f)
        }
    }

    fun step() {
        val years = world.config.daysPerStep / Date.DAYS_PER_YEAR.toFloat()
        val v = sim.vegetation
        for (i in 0 until n) {
            if (!v.isVegetated(i)) { litter[i] = 0f; carrion[i] = 0f; continue }
            val t = sim.weather.temperature[i]
            val wet = (sim.water.soil[i] / 75f).coerceIn(0f, 1f)
            // Decomposition doubles every 10 °C up to about 30 °C, and stops in dry or frozen soil.
            val warm = if (t <= 0f) 0f else min(2.8f, exp(0.069f * (t - 15f)))
            litter[i] += v.litterfall[i]
            val decomposed = litter[i] * min(1f, 1.2f * warm * wet * years)
            litter[i] -= decomposed
            organicMatter[i] += 0.3f * decomposed - 0.05f * years * organicMatter[i]
            v.organicFertility[i] = 0.3f * organicMatter[i] / (organicMatter[i] + 4f)
            soilFauna[i] = 20f * decomposed / years // g/m² of soil animals per kg/m²/yr decomposed
            // Carcasses: half gone in about 10 days at 25 °C, much slower in the cold.
            if (carrion[i] > 0f) {
                val rot = min(1f, 0.07f * max(0.1f, warm) * world.config.daysPerStep)
                val gone = carrion[i] * rot
                carrion[i] -= gone
                litter[i] += 0.3f * gone
                if (carrion[i] < 1e-7f) carrion[i] = 0f
            }
        }
    }

    /** Removes up to [kgPerM2] of carrion at [i]; returns what was taken. */
    fun scavenge(i: Int, kgPerM2: Float): Float {
        val taken = min(carrion[i], kgPerM2)
        carrion[i] -= taken
        return taken
    }
}

/**
 * Small animals as density fields, kg per km²:
 * - plant-eating insects (grasshoppers, caterpillars, beetles), active above about 10 °C, eating
 *   herbs and shrub leaves; a few survive the winter as eggs;
 * - biting insects (mosquitoes, midges), breeding in standing water when it is warm;
 * - rodents and other small mammals (voles, gerbils, jirds), eating seeds and herbs, breeding
 *   fast in good years and crashing in drought and cold. Wolves, foxes and raptors eat them.
 * Each spreads to neighbouring land every month.
 */
class SmallFauna(private val sim: Simulation) {
    private val world = sim.world
    private val grid = world.grid
    private val n = grid.size
    val insects = FloatArray(n)
    val biting = FloatArray(n)
    val rodents = FloatArray(n)
    private var steps = 0
    private val wetland = BooleanArray(n) {
        val b = world.biome[it]
        b == Biome.MARSH || b == Biome.SWAMP_FOREST || b == Biome.BOG || b == Biome.SALT_MARSH || b == Biome.MANGROVE
    }

    init {
        val v = sim.vegetation
        for (i in 0 until n) {
            if (!v.isVegetated(i)) continue
            insects[i] = 300f * min(1f, v.herb[i] / 0.4f)
            rodents[i] = 150f * min(1f, v.herb[i] / 0.4f)
        }
    }

    fun step() {
        val days = world.config.daysPerStep.toFloat()
        val years = days / Date.DAYS_PER_YEAR
        val v = sim.vegetation
        for (i in 0 until n) {
            if (!v.isVegetated(i)) { insects[i] = 0f; rodents[i] = 0f; biting[i] = 0f; continue }
            val t = sim.weather.temperature[i]
            val food = v.herb[i] + 0.3f * v.shrub[i] // kg/m²
            val snow = sim.weather.snowpack[i]

            // Insects: grow fast in warmth on green food towards what the plants can carry, die back
            // in the cold to an egg bank. They eat about a quarter of their own mass a day (dry food
            // per fresh body mass), some 5-15% of what grassland grows in a year.
            var ins = insects[i]
            if (t > 10f) {
                val ceiling = 2000f * food / (food + 0.15f)
                val g = 15f * years * ((t - 10f) / 15f).coerceIn(0f, 1f)
                ins += ins * g * (1 - ins / ceiling.coerceAtLeast(10f)) - ins * 2f * years
                val eaten = ins * 0.25f * days / 1e6f
                v.consume(i, LifeForm.HERB, eaten * 0.8f); v.consume(i, LifeForm.SHRUB, eaten * 0.2f)
            } else {
                ins -= ins * min(0.9f, 6f * years)
            }
            insects[i] = ins.coerceIn(5f, 3000f)

            // Biting insects need standing water and warmth.
            val water = sim.water.flood[i] > 0f || wetland[i]
            biting[i] = if (t > 15f && water) min(500f, biting[i] + 60f * days) else biting[i] * 0.7f

            // Rodents: logistic on food, losses to cold and deep snow; they eat ~10% of their mass a day.
            var r = rodents[i]
            val birth = if (t > 5f) 5f * years * food / (food + 0.2f) else 0f
            val death = 1.2f * years + if (snow > 30f || t < -5f) 3f * years else 0f
            val ceiling = 600f * food / (food + 0.3f)
            r += r * birth * (1 - r / ceiling.coerceAtLeast(1f)) - r * death
            rodents[i] = r.coerceIn(1f, 3000f)
            v.consume(i, LifeForm.HERB, rodents[i] * 0.1f * days / 1e6f)
        }
        if (++steps % 5 == 0) {
            spread(insects); spread(rodents)
        }
    }

    /** Takes up to [kgPerKm2] of rodents at [i] (predation); returns what was caught. */
    fun catchRodents(i: Int, kgPerKm2: Float): Float {
        val taken = min(rodents[i] * 0.5f, kgPerKm2)
        rodents[i] -= taken
        return taken
    }

    private fun spread(f: FloatArray) {
        val mixed = blur(grid, f, 2)
        val v = sim.vegetation
        for (i in 0 until n) if (v.isVegetated(i)) f[i] = 0.8f * f[i] + 0.2f * mixed[i]
    }
}
