package inflation.world

import kotlin.math.exp
import kotlin.math.min

/**
 * What kind of country each place of [HexLand] is now, read off its plants and water as they stand (Olli 2026-11-18: a
 * label describes the conditions, it does not make them). World-gen's [Biome] of each fine cell ([World.biome]) is fixed
 * by the climate and terrain; this one follows the place as it changes: woods cleared or burnt open, grass grazed off and
 * scrub coming in, ground drying out or getting wet. It is read by the reports, by bands' knowledge of plants and by
 * scouting, never by the plants and animals themselves.
 *
 * It also keeps the live properties that once came from world-gen's labels: the waterlogged share ([HexLand.waterlogged]),
 * the edible share of the plants ([HexLand.palatable]), each recomputed every step from the place's water and plants.
 */
class HexCountry(land: HexLand) : HexLandPart(land) {
    /** Climate of each place, the means of its cells: aridity (rain over evaporation), warmest and coldest month, °C. */
    @Transient private val aridity = FloatArray(n) { p -> mean(p) { world.climate.aridity(it) } }
    @Transient private val warm = FloatArray(n) { p -> mean(p) { world.climate.warmestMonthTemp(it) } }
    @Transient private val cold = FloatArray(n) { p -> mean(p) { world.climate.coldestMonthTemp(it) } }

    /**
     * Share of each place's ground that is low and flat enough to hold water at the surface when its soil is full: slope
     * under 0.3% and under 3 m above the nearest stream, or flat on an open lake's shore (world-gen's test for marsh and bog,
     * without its climate part). Off the floodplain: the floodplain's own wet floor is the hydrology's ([HexHydrology.saturatedFloor]).
     * Split by whether trees stand in the water there ([swampCell]: world-gen's swamp forest, warm and wet) or drown.
     */
    @Transient private val lowFlat = FloatArray(n) { p -> mean(p) { i -> if (lowFlatCell(i) && !swampCell(i)) 1f else 0f } }
    @Transient private val lowFlatSwamp = FloatArray(n) { p -> mean(p) { i -> if (lowFlatCell(i) && swampCell(i)) 1f else 0f } }
    /**
     * Share of each place under the tides: flat delta ground at sea level, waterlogged whatever the weather; salt marsh, or
     * mangrove where the coldest month is over 16 °C (world-gen's lines), where the trees stand in the water.
     */
    @Transient private val tidalMarsh = FloatArray(n) { p -> mean(p) { i -> if (tidalCell(i) && world.climate.coldestMonthTemp(i) <= 16f) 1f else 0f } }
    @Transient private val tidalMangrove = FloatArray(n) { p -> mean(p) { i -> if (tidalCell(i) && world.climate.coldestMonthTemp(i) > 16f) 1f else 0f } }
    /** Share of each place that is sodden ground with trees standing in the water (swamp forest, mangrove), set each step. */
    @Transient val swamp = FloatArray(n)

    /**
     * How sodden each place's low flat ground has been over about the last year, 0..1: the year's mean of how near full its soil
     * has been ([SODDEN_FROM] of its capacity and over; floods are the floodplain's, not this ground's). Starts at world-gen's rule (wet where the climate's
     * rain is over 0.8 of evaporation) and follows the water from then on.
     */
    val sodden = FloatArray(n) { p -> mean(p) { i -> if (world.climate.aridity(i) > 0.8f) 1f else 0f } }
    /** Herbs standing over about the last year, kg/m² (the year's mean, as a sward's cover is judged, not its season). */
    val herbYear = FloatArray(n) { p -> plants.herb[p] }
    /** What kind of country each place is now, as a [Biome] ordinal ([classify]). */
    @Transient val kind = IntArray(n)

    init { refresh() }

    /** A step: the year's means move on by this step's water and herbs, and the derived properties and kinds are set afresh. */
    internal fun step() {
        val memory = 1f - exp(-days / 360f)
        for (p in land.places) {
            val full = ((hydro.soil[p] / hydro.soilCap[p] - SODDEN_FROM) / (1f - SODDEN_FROM)).coerceIn(0f, 1f)
            sodden[p] += (full - sodden[p]) * memory
            herbYear[p] += (plants.herb[p] - herbYear[p]) * memory
        }
        refresh()
    }

    private fun refresh() {
        for (p in land.places) {
            land.waterlogged[p] = min(1f, tidalMarsh[p] + lowFlat[p] * sodden[p])
            swamp[p] = min(1f, tidalMangrove[p] + lowFlatSwamp[p] * sodden[p])
            land.palatable[p] = palatableNow(p)
            kind[p] = classify(p).ordinal
        }
    }

    /**
     * The share of place [p]'s plants that something can eat, 0..1, by what stands there now: its herbs, the shrubs and the
     * trees' shoots in reach, each by how well defended it is where it grows ([HERB_EDIBLE], [woodyEdible]); the floor of
     * [inflation.Tuning.palatableFloor] as before. (Each form weighed by what of it is in reach: herbs whole, shrubs by
     * [HexPlants.SHRUB_IN_REACH], trees by [HexPlants.TREE_IN_REACH].)
     */
    private fun palatableNow(p: Place): Float {
        val herbs = plants.herb[p]; val shrubs = HexPlants.SHRUB_IN_REACH * plants.shrub[p]; val trees = HexPlants.TREE_IN_REACH * plants.tree[p]
        val all = herbs + shrubs + trees
        // (Sedge, rush and bog moss on sodden ground: tough or poor.)
        val herbEdible = HERB_EDIBLE * (1f - WET_HERB_CUT * land.waterlogged[p])
        val v = if (all <= 1e-6f) 0.5f else (herbs * herbEdible + (shrubs + trees) * woodyEdible(p)) / all
        val floor = tuning.palatableFloor
        return floor + (1f - floor) * v
    }

    /**
     * The edible share of the shrubs and trees at [p], by the climate they grow in: the evergreen, aromatic or thorny scrub
     * and woods of dry country and of dry summers, and the needle-leaved woods of cold winters, defend themselves; the
     * deciduous broadleaves of moist temperate country least. (The values world-gen's labels had for those countries.)
     */
    private fun woodyEdible(p: Place): Float = when {
        aridity[p] < ARID -> 0.3f
        aridity[p] < SEMI_ARID -> 0.35f
        cold[p] < CONIFER_COLD_C -> 0.3f
        aridity[p] < SUBHUMID && cold[p] >= MILD_WINTER_C -> 0.45f
        else -> 0.55f
    }

    /**
     * What kind of country place [p] is now, by the same climate lines world-gen draws ([Biomes.classify]) and its plants
     * and water as they stand: a canopy over [FOREST_CANOPY] is forest, over [WOODLAND_CANOPY] woodland; open ground where
     * the shrubs cover more than the grass (and at least [SCRUB_COVER]) is shrubland (in arid country semi-desert); open
     * ground with little standing (under [BARE_KG_M2] of herbs and shrubs) is semi-desert or, in hyper-arid country, desert;
     * other open ground is steppe. Waterlogged over half is marsh, bog or swamp forest. The kinds of a place are its main one:
     * a place is one kind or another as most of it is.
     */
    fun classify(p: Place): Biome {
        val canopy = plants.canopy(p)
        val shrubCover = plants.shrub[p] / 2f; val grassCover = herbYear[p] / SWARD_KG_M2
        return when {
            warm[p] < 0f -> Biome.ICE_AND_ROCK
            tidalMarsh[p] >= 0.5f -> Biome.SALT_MARSH
            tidalMangrove[p] >= 0.5f -> Biome.MANGROVE
            swamp[p] >= 0.5f -> Biome.SWAMP_FOREST
            land.waterlogged[p] >= 0.5f -> when {
                canopy >= WOODLAND_CANOPY -> Biome.SWAMP_FOREST
                cold[p] < -3f && warm[p] < 20f -> Biome.BOG
                else -> Biome.MARSH
            }
            warm[p] < 10f -> Biome.ALPINE_MEADOW
            hydro.wetFloor(p) >= 0.5f && canopy >= WOODLAND_CANOPY -> Biome.FLOODPLAIN_FOREST
            canopy >= FOREST_CANOPY -> if (cold[p] < CONIFER_COLD_C) Biome.CONIFER_FOREST else Biome.TEMPERATE_FOREST
            canopy >= WOODLAND_CANOPY -> if (cold[p] < MILD_WINTER_C) Biome.OPEN_WOODLAND else Biome.MEDITERRANEAN_WOODLAND
            herbYear[p] + plants.shrub[p] < BARE_KG_M2 -> if (aridity[p] < HYPER_ARID) Biome.DESERT else Biome.SEMI_DESERT
            shrubCover > grassCover && shrubCover >= SCRUB_COVER -> if (aridity[p] < ARID) Biome.SEMI_DESERT else Biome.SHRUBLAND
            aridity[p] < HYPER_ARID -> Biome.DESERT
            else -> Biome.STEPPE
        }
    }

    /** Share of hex [h]'s land of each kind now ([kind]), by area: summed into [into], which is cleared first. */
    fun sharesOf(h: Hex, into: FloatArray) {
        into.fill(0f); var all = 0f
        for (p in hw.placeList(h)) { into[kind[p]] += land.areaKm2[p]; all += land.areaKm2[p] }
        if (all > 0f) for (k in into.indices) into[k] /= all
    }

    private fun lowFlatCell(i: Int): Boolean {
        if (world.isSea[i] || world.water.isLake(i)) return false
        if (Biomes.slope(grid, world.elevation, i) >= 0.003f) return false
        if (world.floodplain.hand[i] < 3f) return true
        var shore = false
        grid.forNeighbours(i) { j, _ -> if (world.water.isLake(j) && !world.water.lakeClosed[j]) shore = true }
        return shore
    }

    /** Share of place [p] that is wetland: its waterlogged and swamp ground and its hydrology's saturated valley floor. */
    fun wetland(p: Place) = min(1f, land.waterlogged[p] + swamp[p] + hydro.saturatedFloor(p))

    /** Where trees stand in water rather than drown: world-gen's swamp forest climate, warmest month over 20 °C and rain over evaporation. */
    private fun swampCell(i: Int) = world.climate.warmestMonthTemp(i) > 20f && world.climate.aridity(i) > 1f

    private fun tidalCell(i: Int): Boolean {
        if (world.isSea[i] || world.water.isLake(i) || Biomes.slope(grid, world.elevation, i) >= 0.003f) return false
        val r = world.floodplain.riverOf[i]
        return world.elevation[i] < 2f && world.climate.distanceToSeaKm[i] < 3f && r >= 0 && world.water.discharge[r] > 3f
    }

    companion object {
        /** Soil this full of its capacity and over counts towards sodden ground ([sodden]); full at full. A guess. */
        const val SODDEN_FROM = 0.8f
        /** Edible share of herbs on dry ground: open grassland's (world-gen's steppe and alpine meadow had 0.7). */
        const val HERB_EDIBLE = 0.7f
        /** Cut in the herbs' edible share on waterlogged ground (bog had 0.2, marsh 0.5: sedge, rush, moss). */
        const val WET_HERB_CUT = 0.5f
        /** Canopy over which a place is forest, and over which woodland (the FAO's forest is canopy over 10%; these are for closed and open woods, a guess). */
        const val FOREST_CANOPY = 0.6f
        const val WOODLAND_CANOPY = 0.2f
        /** Herbs, kg/m², that cover their ground (as the erosion test in [HexPlants] has it). */
        const val SWARD_KG_M2 = 0.3f
        /** Cover of shrubs (half their kg/m², [HexPlants.cover]) from which open ground counts as scrub. */
        const val SCRUB_COVER = 0.2f
        /**
         * Herbs and shrubs standing, kg/m², under which open ground is semi-desert or desert: desert steppe in Inner
         * Mongolia peaks at 28.5 g/m² of herbs, typical steppe at 163 (Kang et al. 2013, PLoS ONE).
         */
        const val BARE_KG_M2 = 0.03f
        /** Aridity (rain over evaporation) lines of world-gen's kinds: the UNEP hyper-arid, arid, semi-arid and dry sub-humid. */
        const val HYPER_ARID = 0.05f
        const val ARID = 0.2f
        const val SEMI_ARID = 0.45f
        const val SUBHUMID = 0.75f
        /** Coldest month under which the woods are needle-leaved, °C, and over which the winters are mild (world-gen's lines). */
        const val CONIFER_COLD_C = -6f
        const val MILD_WINTER_C = 2f
    }
}
