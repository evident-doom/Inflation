package inflation.tools.herps

import inflation.learning.HexLearning

/** The kinds of climate a herp check sorts places into, by yearly mean temperature (°C) and rain (mm). */
enum class ClimateClass(val label: String, val bounds: String) {
    COLD("boreal/cold", "<6C"),
    COOL("cool temperate", "6-11C"),
    WARM("warm temperate", "11-16C, rain>500"),
    MEDITERRANEAN("mediterranean", ">=11C, 250-500mm"),
    HOT_DESERT("hot desert", "<250mm"),
    HOT_WET("hot wet", ">16C, >500mm");

    companion object {
        fun of(meanT: Float, rain: Float): ClimateClass = when {
            rain < 250f && meanT >= 11f -> HOT_DESERT
            meanT < 6f -> COLD
            meanT < 11f -> COOL
            rain < 500f -> MEDITERRANEAN
            meanT < 16f -> WARM
            else -> HOT_WET
        }
    }
}

/** Each place's climate class (-1 where nothing grows) and whether it is under forest, with the land in each, km². */
class HerpRegions(run: HexLearning.Run) {
    private val land = run.land
    val n = land.n
    /** Each place's [ClimateClass] ordinal, or -1 where nothing grows. */
    val classOf = IntArray(n) { p ->
        val hw = run.hw
        if (land.plants.biomass(p) <= 0f) -1
        else ClimateClass.of((0 until 12).map { hw.placeTemp(p, it) }.average().toFloat(),
            (0 until 12).sumOf { hw.precip[it][hw.placeHex[p]].toDouble() }.toFloat()).ordinal
    }
    val forest = BooleanArray(n) { p -> land.plants.canopy(p) > 0.5f }
    val classArea = DoubleArray(ClimateClass.entries.size)
    /** Land in the open [0] and under forest [1]. */
    val forestArea = DoubleArray(2)
    init {
        for (p in 0 until n) if (classOf[p] >= 0) {
            classArea[classOf[p]] += land.areaKm2[p].toDouble()
            forestArea[if (forest[p]) 1 else 0] += land.areaKm2[p].toDouble()
        }
    }
    val allArea = classArea.sum()

    /** The classes with land, and their labels. */
    val present get() = ClimateClass.entries.filter { classArea[it.ordinal] > 0 }

    /** Sums [f] (a stock or ledger, kg/km², at place p) times each place's area by class. */
    fun byClass(f: (Int) -> Double): DoubleArray {
        val out = DoubleArray(ClimateClass.entries.size)
        for (p in 0 until n) if (classOf[p] >= 0) out[classOf[p]] += f(p) * land.areaKm2[p]
        return out
    }

    /** Sums [f] times each place's area over the map's growing land. */
    fun overMap(f: (Int) -> Double): Double { var s = 0.0; for (p in 0 until n) if (classOf[p] >= 0) s += f(p) * land.areaKm2[p]; return s }
}
