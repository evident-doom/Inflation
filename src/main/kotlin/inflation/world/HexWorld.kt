package inflation.world

import kotlin.math.max

/**
 * What the simulation knows of each hex of a [HexMap], summarised from the fine world once:
 * the ground (land, sea and lake shares, how the land lies by height), the climate of each month
 * (on the low and the high ground of the hex as well as on average), the soils and the kinds of
 * country (biome shares), and the water (rivers, springs within reach, a coast).
 *
 * Height bands let a hex hold both a valley floor and a summer pasture: what grows, how cold it is
 * and where the snow lies are kept per band, so herds can move up and down within a hex as well as
 * between hexes.
 */
class HexWorld(val world: World, val hex: HexMap) {
    private val grid = world.grid
    val size = hex.size

    /** Shares of each hex's fine cells that are sea, lake, and land. */
    val sea = FloatArray(size); val lake = FloatArray(size); val land = FloatArray(size)
    /** Share of each hex's land in each height band ([BANDS_M] lower bounds). */
    val band = Array(BANDS_M.size) { FloatArray(size) }
    /** Mean land elevation of each hex, m. */
    val elevationM = FloatArray(size)
    /** [month][hex]: mean temperature of the land, °C, and of its lowest and highest tenth of ground (valley floor, high pasture); rain and snow, mm; potential evaporation, mm. */
    val temp = Array(Date.MONTHS_PER_YEAR) { FloatArray(size) }
    val tempLow = Array(Date.MONTHS_PER_YEAR) { FloatArray(size) }
    val tempHigh = Array(Date.MONTHS_PER_YEAR) { FloatArray(size) }
    val precip = Array(Date.MONTHS_PER_YEAR) { FloatArray(size) }
    val pet = Array(Date.MONTHS_PER_YEAR) { FloatArray(size) }
    /** Mean soil fertility of the land, 0..1. */
    val fertility = FloatArray(size)
    /** [biome][hex]: share of the land in each kind of country. */
    val biome = Array(Biome.entries.size) { FloatArray(size) }
    /** Biggest river through the hex, m³/s; length of streams and rivers in it, km; whether any reach is navigable. */
    val riverM3s = FloatArray(size)
    val riverKm = FloatArray(size)
    val navigable = BooleanArray(size)
    /** Share of the land within 2 km of drinking water (river, lake, spring). */
    val watered = FloatArray(size)
    /** Whether the hex touches the open sea. */
    val coast = BooleanArray(size)

    init {
        val cells = IntArray(size)
        val landN = IntArray(size)
        val elev = DoubleArray(size)
        val byHex = Array(size) { ArrayList<Int>() }
        val water = BooleanArray(grid.size) { world.isSea[it] || world.water.isLake(it) || world.riverClass(it) >= RiverClass.STREAM }
        val nearWater = Climate.distanceTransformKm(grid, water)
        for (i in 0 until grid.size) {
            val h = hex.owner[i]
            cells[h]++
            when {
                world.isSea[i] -> sea[h] += 1f
                world.water.isLake(i) -> lake[h] += 1f
                else -> {
                    landN[h]++; byHex[h] += i
                    elev[h] += world.elevation[i]
                    fertility[h] += world.soilFertility[i]
                    biome[world.biome[i].ordinal][h] += 1f
                    val b = BANDS_M.indexOfLast { world.elevation[i] >= it }.coerceAtLeast(0)
                    band[b][h] += 1f
                    if (nearWater[i] <= 2f) watered[h] += 1f
                    for (m in 0 until Date.MONTHS_PER_YEAR) {
                        temp[m][h] += world.climate.monthlyTemp[m][i]
                        precip[m][h] += world.climate.monthlyPrecip[m][i]
                        pet[m][h] += world.climate.monthlyPet[m][i]
                    }
                }
            }
            if (world.riverClass(i) >= RiverClass.STREAM) { riverKm[h] += grid.cellKm.toFloat(); riverM3s[h] = max(riverM3s[h], world.water.discharge[i]) }
            if (world.isNavigable(i)) navigable[h] = true
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
            fertility[h] /= l
            watered[h] /= l
            for (b in band) b[h] /= l
            for (bi in biome) bi[h] /= l
            for (m in 0 until Date.MONTHS_PER_YEAR) { temp[m][h] /= l; precip[m][h] /= l; pet[m][h] /= l }
            // The coldest and warmest tenth of the land: the high pastures and the valley floor.
            val cs = byHex[h]
            val tenth = max(1, cs.size / 10)
            val byHeight = cs.sortedBy { world.elevation[it] }
            for (m in 0 until Date.MONTHS_PER_YEAR) {
                tempLow[m][h] = byHeight.take(tenth).map { world.climate.monthlyTemp[m][it] }.average().toFloat()
                tempHigh[m][h] = byHeight.takeLast(tenth).map { world.climate.monthlyTemp[m][it] }.average().toFloat()
            }
        }
    }

    // ---------------------------------------------------------------- places: the height bands of each hex

    /** The places the simulation keeps weather, plants and herds for: each height band of each hex that has land in it. */
    val placeHex: IntArray
    val placeBand: IntArray
    /** The fine cells of each place. */
    val placeCells: Array<IntArray>
    /** Each hex's places, by band (-1 where the band holds no land). */
    val placeOf = Array(BANDS_M.size) { IntArray(size) { -1 } }
    /** Each hex's places, from the lowest band up. */
    private val placesIn: Array<IntArray>
    /** Mean elevation of each place, m. */
    val placeElevationM: FloatArray
    /** How much sun the shaded and sunny slopes of each place get, by month (worked out once, from the terrain). */
    val sunlight: Sunlight by lazy { Sunlight.of(world, placeCells) }

    init {
        val lists = HashMap<Long, ArrayList<Int>>()
        for (i in 0 until grid.size) {
            if (world.isSea[i] || world.water.isLake(i)) continue
            val b = BANDS_M.indexOfLast { world.elevation[i] >= it }.coerceAtLeast(0)
            lists.getOrPut(hex.owner[i].toLong() * BANDS_M.size + b) { ArrayList() } += i
        }
        val keys = lists.keys.sorted()
        placeHex = IntArray(keys.size) { (keys[it] / BANDS_M.size).toInt() }
        placeBand = IntArray(keys.size) { (keys[it] % BANDS_M.size).toInt() }
        placeCells = Array(keys.size) { lists.getValue(keys[it]).toIntArray() }
        for (p in keys.indices) placeOf[placeBand[p]][placeHex[p]] = p
        placesIn = Array(size) { h -> placeOf.map { it[h] }.filter { it >= 0 }.toIntArray() }
        placeElevationM = FloatArray(keys.size) { p -> (placeCells[p].sumOf { world.elevation[it].toDouble() } / placeCells[p].size).toFloat() }
    }

    /** Normal temperature of place [p] in month [m], °C: its hex's, corrected for the band's height. */
    fun placeTemp(p: Int, m: Int): Float = temp[m][placeHex[p]] - 6.5e-3f * (placeElevationM[p] - elevationM[placeHex[p]])

    /** The places as the weather sees them: the mean position, normals and wind exposure of each band's cells. */
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

    /** The place in hex [h] at the height band of place [p], or -1 if the hex has no ground at that height. */
    fun sameBandPlace(p: Int, h: Int) = placeOf[placeBand[p]][h]

    /** The places of hex [h], from the lowest band up. */
    fun placesOf(h: Int): IntArray = placesIn[h]

    /** The first place in hex [h], from the lowest band up, or -1 if it has none. */
    fun firstPlaceIn(h: Int): Int { for (b in placeOf.indices) { val p = placeOf[b][h]; if (p >= 0) return p }; return -1 }

    /** The kind of country covering most of the land of hex [h], or null if it has no land. */
    fun mainBiome(h: Int): Biome? = if (land[h] == 0f) null else Biome.entries.maxBy { biome[it.ordinal][h] }

    companion object {
        /** Lower bounds of the height bands, m. */
        val BANDS_M = floatArrayOf(-1000f, 500f, 1000f, 1500f, 2500f)
    }
}
