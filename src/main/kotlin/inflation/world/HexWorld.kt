package inflation.world

import kotlin.math.abs
import kotlin.math.max

/**
 * What the simulation knows of each hex of a [HexMap], summarised from the fine world once:
 * the ground (land, sea and lake shares, how the land lies by height), the climate of each month
 * (on the low and the high ground of the hex as well as on average), the soils and the kinds of
 * country (biome shares), and the water (rivers, springs within reach, a coast).
 *
 * A hex whose land rises far enough holds two places, its low and its high ground, so it can hold both
 * a valley floor and a summer pasture: what grows, how cold it is and where the snow lies are kept for
 * each, and animals move up and down within a hex as well as between hexes.
 */
class HexWorld(val world: World, val hex: HexMap) {
    private val grid = world.grid
    val size = hex.size

    /** Shares of each hex's fine cells that are sea, lake, and land. */
    val sea = FloatArray(size); val lake = FloatArray(size); val land = FloatArray(size)
    /** Mean land elevation of each hex, m. */
    val elevationM = FloatArray(size)
    /** [month][hex]: mean temperature of the land, °C; rain and snow, mm; potential evaporation, mm. */
    val temp = Array(Date.MONTHS_PER_YEAR) { FloatArray(size) }
    val precip = Array(Date.MONTHS_PER_YEAR) { FloatArray(size) }
    val pet = Array(Date.MONTHS_PER_YEAR) { FloatArray(size) }
    /** [biome][hex]: share of the land in each kind of country. */
    val biome = Array(Biome.entries.size) { FloatArray(size) }
    /** Biggest river through the hex, m³/s; length of streams and rivers in it, km. */
    val riverM3s = FloatArray(size)
    val riverKm = FloatArray(size)
    /** Share of the land within 2 km of drinking water (river, lake, spring). */
    val watered = FloatArray(size)
    /** Whether the hex touches the open sea. */
    val coast = BooleanArray(size)

    init {
        val cells = IntArray(size)
        val landN = IntArray(size)
        val elev = DoubleArray(size)
        val water = BooleanArray(grid.size) { world.isSea[it] || world.water.isLake(it) || world.riverClass(it) >= RiverClass.STREAM }
        val nearWater = Climate.distanceTransformKm(grid, water)
        for (i in 0 until grid.size) {
            val h = hex.owner[i]
            cells[h]++
            when {
                world.isSea[i] -> sea[h] += 1f
                world.water.isLake(i) -> lake[h] += 1f
                else -> {
                    landN[h]++
                    elev[h] += world.elevation[i]
                    biome[world.biome[i].ordinal][h] += 1f
                    if (nearWater[i] <= 2f) watered[h] += 1f
                    for (m in 0 until Date.MONTHS_PER_YEAR) {
                        temp[m][h] += world.climate.monthlyTemp[m][i]
                        precip[m][h] += world.climate.monthlyPrecip[m][i]
                        pet[m][h] += world.climate.monthlyPet[m][i]
                    }
                }
            }
            if (world.riverClass(i) >= RiverClass.STREAM) { riverKm[h] += grid.cellKm.toFloat(); riverM3s[h] = max(riverM3s[h], world.water.discharge[i]) }
            if (world.isSea[i]) {
                grid.forNeighbours(i) { j, _ -> if (!world.isSea[j]) coast[hex.owner[j]] = true }
            }
        }
        for (h in 0 until size) {
            val n = cells[h].toFloat().coerceAtLeast(1f)
            sea[h] /= n; lake[h] /= n
            val l = landN[h]
            land[h] = l / n
            if (l == 0) continue
            elevationM[h] = (elev[h] / l).toFloat()
            watered[h] /= l
            for (bi in biome) bi[h] /= l
            for (m in 0 until Date.MONTHS_PER_YEAR) { temp[m][h] /= l; precip[m][h] /= l; pet[m][h] /= l }
        }
    }

    // ---------------------------------------------------------------- places: the low and high ground of each hex

    /**
     * The places the simulation keeps weather, plants and animals for: each hex that has land is one place, or,
     * where its land rises far enough ([SPLIT_RELIEF_M]), two: its low ground and its high ground, split at the
     * height half its land lies below.
     */
    val placeHex: IntArray
    /** Whether each place is its hex's low ground ([LOW], also a hex's only place) or its high ground ([HIGH]). */
    val placeBand: IntArray
    /** The fine cells of each place. */
    val placeCells: Array<IntArray>
    /** Each hex's low and high place (the same place where the hex is not split; -1 where it has no land). */
    private val low = IntArray(size) { -1 }
    private val high = IntArray(size) { -1 }
    /** Each hex's places, the low first. */
    private val placesIn: Array<IntArray>
    /** The height at which each split hex's land divides into low and high ground, m (+∞ where it is not split). */
    private val splitM = FloatArray(size) { Float.POSITIVE_INFINITY }
    /** Mean elevation of each place, m. */
    val placeElevationM: FloatArray
    /** How much sun the shaded and sunny slopes of each place get, by month (worked out once, from the terrain). */
    val sunlight: Sunlight by lazy { Sunlight.of(world, placeCells) }
    /** The main kind of country of each place, as a [Biome] ordinal: the kind most of its cells are (a tie goes to the earlier kind). */
    val placeBiome: IntArray by lazy {
        IntArray(placeCells.size) { p ->
            val counts = IntArray(Biome.entries.size)
            for (c in placeCells[p]) counts[world.biome[c].ordinal]++
            counts.indices.maxBy { counts[it] }
        }
    }

    init {
        val cellsOf = Array(size) { ArrayList<Int>() }
        for (i in 0 until grid.size) if (!world.isSea[i] && !world.water.isLake(i)) cellsOf[hex.owner[i]] += i
        // A hex is split where the land between its lowest and highest tenth rises more than [SPLIT_RELIEF_M], and
        // each half would hold at least [MIN_HALF_CELLS] cells; the split is at the median height of its land.
        for (h in 0 until size) {
            val cs = cellsOf[h]
            if (cs.size < 2 * MIN_HALF_CELLS) continue
            val e = FloatArray(cs.size) { world.elevation[cs[it]] }.also { it.sort() }
            if (e[(e.size * 9) / 10] - e[e.size / 10] > SPLIT_RELIEF_M) splitM[h] = e[e.size / 2]
        }
        val hexes = ArrayList<Int>(); val bands = ArrayList<Int>(); val cells = ArrayList<IntArray>()
        for (h in 0 until size) {
            val cs = cellsOf[h]
            if (cs.isEmpty()) continue
            var (hi, lo) = cs.partition { bandAt(it) == HIGH }
            if (lo.isEmpty()) { lo = hi; hi = emptyList(); splitM[h] = Float.POSITIVE_INFINITY }
            low[h] = hexes.size; hexes += h; bands += LOW; cells += lo.toIntArray()
            if (hi.isNotEmpty()) { high[h] = hexes.size; hexes += h; bands += HIGH; cells += hi.toIntArray() } else high[h] = low[h]
        }
        placeHex = hexes.toIntArray(); placeBand = bands.toIntArray(); placeCells = cells.toTypedArray()
        placesIn = Array(size) { h -> when { low[h] < 0 -> IntArray(0); high[h] == low[h] -> intArrayOf(low[h]); else -> intArrayOf(low[h], high[h]) } }
        placeElevationM = FloatArray(placeCells.size) { p -> (placeCells[p].sumOf { world.elevation[it].toDouble() } / placeCells[p].size).toFloat() }
    }

    /** Whether fine cell [i] (land) lies on its hex's high ground. */
    private fun bandAt(i: Int) = if (world.elevation[i] >= splitM[hex.owner[i]]) HIGH else LOW

    /** The place fine cell [i] lies in, or -1 for sea and lake. */
    fun placeAt(i: Int): Int = if (world.isSea[i] || world.water.isLake(i)) -1 else if (bandAt(i) == HIGH) high[hex.owner[i]] else low[hex.owner[i]]

    /** Normal temperature of place [p] in month [m], °C: its hex's, corrected for the place's height. */
    fun placeTemp(p: Int, m: Int): Float = temp[m][placeHex[p]] - Climate.LAPSE_RATE.toFloat() * (placeElevationM[p] - elevationM[placeHex[p]])

    /** The places as the weather sees them: the mean position, normals and wind exposure of each place's cells. */
    fun weatherPlaces(cfg: WorldConfig): Weather.Places {
        val n = placeCells.size
        val fine = Winds.sectors(grid, world.elevation)
        fun mean(f: (Int) -> Float) = FloatArray(n) { p -> val cs = placeCells[p]; (cs.sumOf { f(it).toDouble() } / cs.size).toFloat() }
        return Weather.Places(
            n,
            mean { (grid.x(it) * grid.cellKm).toFloat() },
            mean { (grid.y(it) * grid.cellKm).toFloat() },
            DoubleArray(n) { p -> placeCells[p].sumOf { world.climate.latitude[grid.y(it)] } / placeCells[p].size },
            Array(Date.MONTHS_PER_YEAR) { m -> mean { world.climate.monthlyTemp[m][it] } },
            Array(Date.MONTHS_PER_YEAR) { m -> mean { world.climate.monthlyPrecip[m][it] } },
            Array(8) { k -> mean { fine[k][it] } },
            grid.width * grid.cellKm, grid.height * grid.cellKm, grid.cellKm,
            sunlight,
        )
    }

    /** The place in hex [h] nearest in height to place [p]: its low or high ground, whichever lies nearer; -1 if the hex has no land. */
    fun placeNear(p: Int, h: Int): Int {
        val lo = low[h]; val hi = high[h]
        if (lo == hi) return lo
        val e = placeElevationM[p]
        return if (abs(e - placeElevationM[lo]) <= abs(e - placeElevationM[hi])) lo else hi
    }

    /** Hex [h]'s low ([LOW]) or high ([HIGH]) ground; its only place where it is not split; -1 if it has no land. */
    fun placeIn(band: Int, h: Int): Int = if (band == HIGH) high[h] else low[h]

    /** The places of hex [h], the low first. */
    fun placesOf(h: Int): IntArray = placesIn[h]

    /** Hex [h]'s low ground (its only place where it is not split), or -1 if it has no land. */
    fun firstPlaceIn(h: Int): Int = low[h]

    /** The kind of country covering most of the land of hex [h], or null if it has no land. */
    fun mainBiome(h: Int): Biome? = if (land[h] == 0f) null else Biome.entries.maxBy { biome[it.ordinal][h] }

    companion object {
        /** A hex's low and high ground (see [placeBand]); how many places a hex can have. */
        const val LOW = 0
        const val HIGH = 1
        const val BANDS = 2
        /**
         * Rise of a hex's land, from its lowest tenth to its highest, beyond which its low and high ground are kept
         * apart, m: about 2.6 °C by the lapse rate (not sourced; about the 90th percentile of the step in mean height
         * between neighbouring hexes, so a hex keeps one climate where its own land differs less than its neighbours often do).
         */
        const val SPLIT_RELIEF_M = 400f
        /** Fewest fine cells each half of a split hex holds (10 km²): a few cells of land on a coast are not split. */
        const val MIN_HALF_CELLS = 10
    }
}
