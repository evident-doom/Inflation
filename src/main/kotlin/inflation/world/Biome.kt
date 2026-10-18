package inflation.world

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/** Broad vegetation classes. Flora and fauna are parameterised per biome. */
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
    /** Reed and sedge marsh: open, waterlogged or seasonally flooded. */
    MARSH(0x5f9f8f),
    /** Forested wetland in warm, wet lowlands. */
    SWAMP_FOREST(0x2f6f5f),
    /** Peat bog or fen where it is cold and wet. */
    BOG(0x7f8f6f),
    /** Tidal flats and salt marsh on low, sheltered coasts. */
    SALT_MARSH(0x9fbfaf),
    /** Mangrove on warm tidal coasts. */
    MANGROVE(0x3f6f3f),
    /** Gallery forest on the floodplains of rivers. */
    FLOODPLAIN_FOREST(0x2f7f5f),
}

object Biomes {
    fun classify(
        grid: Grid,
        elevation: FloatArray,
        isSea: BooleanArray,
        climate: Climate,
        water: SurfaceWater,
        floodplain: Floodplain,
    ): Array<Biome> = Array(grid.size) { i ->
        val ai = climate.aridity(i)
        val warm = climate.warmestMonthTemp(i)
        val cold = climate.coldestMonthTemp(i)
        val s = if (isSea[i]) 0f else slope(grid, elevation, i)
        val r = floodplain.riverOf[i]
        val riverQ = if (r >= 0) water.discharge[r] else 0f
        val flat = s < 0.003f
        // Waterlogged: flat, barely above the river, and either a wet climate or a river's floods.
        val waterlogged = flat && floodplain.hand[i] < 3f && (ai > 0.8f || (floodplain.isFloodplain[i] && riverQ > 10f))
        val lakeShore = flat && !water.isLake(i) && nextTo(grid, i) { water.isLake(it) && !water.lakeClosed[it] }
        // Tidal marsh on the low ground of deltas; elsewhere the coast is beach or cliff.
        val tidal = flat && elevation[i] < 2f && climate.distanceToSeaKm[i] < 3f && riverQ > 3f
        when {
            isSea[i] -> Biome.OCEAN
            water.isLake(i) && water.lakeClosed[i] && ai < 0.15f -> Biome.SALT_FLAT
            water.isLake(i) && water.lakeClosed[i] -> Biome.SALT_LAKE
            water.isLake(i) -> Biome.LAKE
            warm < 0f -> Biome.ICE_AND_ROCK
            warm < 10f -> Biome.ALPINE_MEADOW
            tidal -> if (cold > 16f) Biome.MANGROVE else Biome.SALT_MARSH
            waterlogged || lakeShore -> when {
                cold < -3f && warm < 20f -> Biome.BOG
                warm > 20f && ai > 1f && !lakeShore -> Biome.SWAMP_FOREST
                else -> Biome.MARSH
            }
            floodplain.isFloodplain[i] && riverQ > 3f && ai < 0.75f -> Biome.FLOODPLAIN_FOREST
            ai < 0.05f -> Biome.DESERT
            ai < 0.2f -> Biome.SEMI_DESERT
            // (Dry grass country is steppe whatever the winter: the Syrian steppe has mild winters, the Anatolian cold ones.)
            ai < 0.45f -> Biome.STEPPE
            ai < 0.75f -> if (cold < 2f) Biome.OPEN_WOODLAND else Biome.MEDITERRANEAN_WOODLAND
            cold < -6f -> Biome.CONIFER_FOREST
            else -> Biome.TEMPERATE_FOREST
        }
    }

    private inline fun nextTo(grid: Grid, i: Int, test: (Int) -> Boolean): Boolean {
        var hit = false
        grid.forNeighbours(i) { j, _ -> if (test(j)) hit = true }
        return hit
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
