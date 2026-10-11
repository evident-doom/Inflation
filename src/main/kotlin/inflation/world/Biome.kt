package inflation.world

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/** Broad vegetation classes. Flora and fauna in later steps are parameterised per biome. */
enum class Biome(val rgb: Int) {
    OCEAN(0x1f3f6f),
    LAKE(0x3f7fbf),
    SALT_LAKE(0x7fb5c8),
    SALT_FLAT(0xe8e2d0),
    ICE_AND_ROCK(0xf2f2f2),
    ALPINE_MEADOW(0xa9b98a),
    CONIFER_FOREST(0x2f5f3f),
    TEMPERATE_FOREST(0x3f7f3f),
    OPEN_WOODLAND(0x7f9f4f),
    MEDITERRANEAN_WOODLAND(0x8a9a3a),
    STEPPE(0xbfbf7f),
    SHRUBLAND(0xc8b46e),
    SEMI_DESERT(0xd9c48f),
    DESERT(0xeed9a6),
    WETLAND(0x4f8f7f),
    RIPARIAN_FOREST(0x2f7f5f),
}

object Biomes {
    fun classify(
        grid: Grid,
        elevation: FloatArray,
        isSea: BooleanArray,
        climate: Climate,
        water: SurfaceWater,
    ): Array<Biome> = Array(grid.size) { i ->
        val ai = climate.aridity(i)
        val warm = climate.warmestMonthTemp(i)
        val cold = climate.coldestMonthTemp(i)
        val q = water.discharge[i]
        when {
            isSea[i] -> Biome.OCEAN
            water.isLake(i) && water.lakeClosed[i] && ai < 0.15f -> Biome.SALT_FLAT
            water.isLake(i) && water.lakeClosed[i] -> Biome.SALT_LAKE
            water.isLake(i) -> Biome.LAKE
            warm < 0f -> Biome.ICE_AND_ROCK
            warm < 10f -> Biome.ALPINE_MEADOW
            q > 30f && slope(grid, elevation, i) < 0.002f -> Biome.WETLAND
            q > 3f && ai < 0.5f -> Biome.RIPARIAN_FOREST
            ai < 0.05f -> Biome.DESERT
            ai < 0.2f -> Biome.SEMI_DESERT
            ai < 0.45f -> if (cold < 2f) Biome.STEPPE else Biome.SHRUBLAND
            ai < 0.75f -> if (cold < 2f) Biome.OPEN_WOODLAND else Biome.MEDITERRANEAN_WOODLAND
            cold < -6f -> Biome.CONIFER_FOREST
            else -> Biome.TEMPERATE_FOREST
        }
    }

    /** Steepest downhill gradient, m/m. */
    fun slope(grid: Grid, elevation: FloatArray, i: Int): Float {
        var s = 0f
        grid.forNeighbours(i) { j, d -> s = max(s, ((elevation[i] - elevation[j]) / d).toFloat()) }
        return s
    }

    /**
     * Soil fertility 0..1: what the bedrock weathers into, reduced on steep slopes where soil
     * does not stay, and by drought. A starting point for plant productivity, not a crop yield.
     */
    fun soilFertility(grid: Grid, elevation: FloatArray, isSea: BooleanArray, geology: Geology, climate: Climate): FloatArray =
        FloatArray(grid.size) { i ->
            if (isSea[i]) 0f else {
                val s = slope(grid, elevation, i)
                val moisture = sqrt(climate.aridity(i).coerceIn(0f, 1f))
                geology.rock[i].fertility * exp(-s / 0.15f) * moisture
            }
        }
}
