package inflation.world

import inflation.Books
import inflation.Tuning
import inflation.Config
import inflation.Kg
import inflation.KgM2
import inflation.store.MovedInto

import kotlin.math.exp

/**
 * Water and plants on the places of a [HexWorld] (the height bands of each hex), stepped with the
 * weather on the same places: what each place is, and its parts, the water ([HexHydrology], [HexDrinkingWater]),
 * the plants ([HexPlants]) and fires ([HexFires]). The same rules as the fine [LiveWater] and [Vegetation], on
 * the averages of each band's cells.
 */
class HexLand(internal val hw: HexWorld, internal val seed: Long, /** The run's settings ([Tuning]). */ @Transient val tuning: Tuning = Tuning.config) {
    private val world = hw.world
    private val grid = world.grid
    private val hex = hw.hex
    val n = hw.placeCells.size
    internal val days = world.config.daysPerStep
    internal val stepSeconds = days * 86_400.0

    // ---------------------------------------------------------------- what each place is

    /** Area of each place, km², and m². */
    @Transient val areaKm2 = FloatArray(n) { (hw.placeCells[it].size * grid.cellKm * grid.cellKm).toFloat() }
    /** Place [p]'s area, m². */
    fun m2(p: Int) = areaKm2[p] * 1e6f
    /** [kg] spread over place [p]. */
    fun spread(p: Int, kg: Kg) = KgM2(kg.v / m2(p))
    /** All of [x] over place [p]. */
    fun total(p: Int, x: KgM2) = Kg(x.v * m2(p))
    @Transient internal val areaM2 = FloatArray(n) { (areaKm2[it] * 1e6).toFloat() }
    internal fun mean(p: Int, f: (Int) -> Float): Float { val cs = hw.placeCells[p]; var s = 0.0; for (i in cs) s += f(i); return (s / cs.size).toFloat() }
    @Transient internal val soilFertility = FloatArray(n) { p -> mean(p) { world.soilFertility[it] } }
    @Transient internal val slope = FloatArray(n) { p -> mean(p) { world.terrain.slope[it] } }
    /**
     * Soil fertility of each place's floodplain, 0..1: what its rock (alluvium) gives on its slope, as [Biomes.soilFertility]
     * but without the cut for a dry climate, since its water comes from the river. (Fixed by the world, so not taken from a
     * saved run.)
     */
    @Transient internal val floorFertility = FloatArray(n) { p ->
        var s = 0.0; var k = 0
        for (i in hw.placeCells[p]) if (world.floodplain.isFloodplain[i]) { s += world.geology.rock[i].fertility * exp(-Biomes.slope(grid, world.elevation, i) / 0.15f); k++ }
        if (k == 0) 0f else (s / k).toFloat()
    }
    /** Share of waterlogged ground (marsh, bog, salt marsh), where woody plants drown (fixed by the world, so not taken from a saved run: runs saved before it was filled hold zeros). */
    @Transient val waterlogged = FloatArray(n) { p -> mean(p) { if (world.biome[it] == Biome.MARSH || world.biome[it] == Biome.BOG || world.biome[it] == Biome.SALT_MARSH) 1f else 0f } }
    /**
     * The share of each place's plants an animal or a forager can eat, 0..1: most of an open
     * grassland, little of aromatic garrigue, thorny scrub, desert bush, bog or conifer forest, where
     * the plants are defended (bitter, aromatic, thorny or poisonous) or tough. (Fixed by the world and
     * the config, so not taken from a saved run.)
     */
    @Transient val palatable = FloatArray(n) { p ->
        val h = hw.placeHex[p]
        var s = 0f; var w = 0f
        for (b in Biome.entries) { val share = hw.biome[b.ordinal][h]; if (share <= 0f) continue; val v = PALATABLE[b] ?: continue; s += share * v; w += share }
        val v = if (w > 0f) s / w else 0.5f
        // (Half of even the poorest country's plants is eaten by something; the rest of it by the
        // share for its kind. A balancing run may change that floor: t.palatableFloor.)
        val floor = tuning.palatableFloor
        floor + (1f - floor) * v
    }

    /** The dead buried at each place (graves weather away over centuries), and the ornaments buried with them. */
    val burials = FloatArray(n)
    val graveGoods = FloatArray(n)
    /** Share of the ground where good knapping stone (flint, obsidian) crops out. */
    @Transient val stone = FloatArray(n) { p -> mean(p) { if (world.geology.minerals[it] and (Mineral.FLINT or Mineral.OBSIDIAN) != 0) 1f else 0f } }
    /** Share of the ground where salt can be got (crusts on dry lake beds and salt pans), and where red and yellow ochre crops out. */
    @Transient val salt = FloatArray(n) { p -> mean(p) { if (world.geology.minerals[it] and Mineral.SALT != 0) 1f else 0f } }
    @Transient val ochre = FloatArray(n) { p -> mean(p) { if (world.geology.minerals[it] and Mineral.OCHRE != 0) 1f else 0f } }
    /** Share of floodplain, which a river in spate covers. */
    @Transient val floodShare = FloatArray(n) { p -> mean(p) { if (world.floodplain.isFloodplain[it]) 1f else 0f } }
    @Transient internal val pet = Array(12) { m -> FloatArray(n) { p -> mean(p) { world.climate.monthlyPet[m][it] } } }
    /** Mean ruggedness, cover from broken ground and the best refuge and lookout of each place. */
    @Transient val ruggedness = FloatArray(n) { p -> mean(p) { world.terrain.ruggedness[it] } }
    /**
     * How the ruggedness is spread over each place: the ruggedness at [RUGGED_SHARES] even steps through its
     * ground, from its smoothest to its roughest (a rough hex still has its valley floors and meadows).
     */
    @Transient val ruggedSpread: Array<FloatArray> = Array(RUGGED_SHARES) { FloatArray(n) }.also { q ->
        java.util.stream.IntStream.range(0, n).parallel().forEach { p ->
            val r = FloatArray(hw.placeCells[p].size) { world.terrain.ruggedness[hw.placeCells[p][it]] }.also { it.sort() }
            for (k in 0 until RUGGED_SHARES) q[k][p] = if (r.isEmpty()) 0f else r[((k + 0.5f) / RUGGED_SHARES * r.size).toInt().coerceIn(0, r.size - 1)]
        }
    }
    /**
     * For each place, the mean over its ground of [f] of the ruggedness: how a rule that reads ruggedness
     * through a threshold or a curve sees a place, so a gentle place with a band of cliffs keeps its cliffs
     * (worked out once from the fine cells).
     */
    fun overGround(f: (Float) -> Float): FloatArray = FloatArray(n).also { out ->
        java.util.stream.IntStream.range(0, n).parallel().forEach { p -> out[p] = mean(p) { f(world.terrain.ruggedness[it]) } }
    }
    /** How well each place's crags and its burrows in broken ground shelter a kind, 0..1 (see [inflation.ecology.Niches.shelter]). */
    @Transient val cragShelter = overGround { ((it - 0.3f) / 0.4f).coerceIn(0f, 1f) }
    @Transient val denShelter = overGround { 0.6f * (it / 0.5f).coerceIn(0f, 1f) }
    /** Share of a place that is crag, slopes over 45% (see [inflation.ecology.HexFood.access]). */
    @Transient val crag = FloatArray(n) { p -> mean(p) { if (world.terrain.slope[it] > 0.45f) 1f else 0f } }
    /** Share of a place with caves and rock shelters: karst limestone in broken ground. */
    @Transient val caves = FloatArray(n) { p -> mean(p) { (if (world.geology.rock[it] == Rock.LIMESTONE) 1f else 0.2f) * (world.terrain.ruggedness[it] / 0.5f).coerceIn(0f, 1f) } }
    @Transient val bestDefence = FloatArray(n) { p -> hw.placeCells[p].maxOf { world.terrain.defensibility[it] } }
    @Transient val bestViewKm = FloatArray(n) { p -> hw.placeCells[p].maxOf { world.terrain.viewKm[it] } }
    /** Neighbouring places: the bands above and below in the hex and the bands at about the same height around it. */
    @Transient val neighbours: Array<IntArray> = Array(n) { p ->
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
    @Transient internal val slopeShade = hw.sunlight.shade
    /** The books of the run's water and matter, when kept (see [Books]); the parts and the ecology book their flows in them. */
    @Transient var books: Books? = null

    // ---------------------------------------------------------------- the parts (saved before they were split out as the land's own fields)

    @field:MovedInto(
        "spring", "oasis", "nearRiver", "nearLake", "nearSpring", "waterKmMean", "nearBed", "bedM2", "sand", "streamKm", "water", "waterKm", "dug",
        "SALT_REACH_KM", "SABKHA_ARIDITY",
    )
    val drink = HexDrinkingWater(this)
    @field:MovedInto(
        "down", "meanFlow", "order", "soilCap", "intake", "aquiferCap", "aquiferM", "drain", "soil", "soilShade", "groundwater", "valley",
        "stream", "usualGround", "flow", "flood", "soak", "ranOff",
    )
    val hydro = HexHydrology(this)
    @field:MovedInto(
        "gdd", "annualGdd", "herb", "shrub", "tree", "straw", "moisture", "warmth", "fertility", "organicFertility", "litterfall", "shrubShoots",
        "treeShoots", "shootsSet", "browsing", "grazing", "rooting", "animalSeed", "trampling", "green", "hayFallen", "hayFresh", "warmest", "growFrom",
        "growSpan", "perennialShare", "cured", "greenOpen", "greenSeep", "greenWet", "greenShade", "curedShade", "ash", "trails", "treeSeed", "shrubSeed",
        "stepCount",
    )
    val plants = HexPlants(this)
    @field:MovedInto("burnt", "burning", "fires", "lit")
    val fire = HexFires(this)

    /** Advances one step with the weather of that step on the same places. */
    fun step(weather: Weather) {
        hydro.step(weather)
        drink.step(weather)
        books?.check("water")
        plants.step(weather)
        books?.check("plants")
    }

    companion object {
        /** Steps through each place's ground at which its ruggedness is kept (see [ruggedSpread]). */
        const val RUGGED_SHARES = 8
        /** Share of the plants of each kind of country that are edible (see [palatable]). */
        val PALATABLE = mapOf(
            Biome.ALPINE_MEADOW to 0.7f, Biome.STEPPE to 0.7f, Biome.OPEN_WOODLAND to 0.6f, Biome.FLOODPLAIN_FOREST to 0.5f,
            Biome.MARSH to 0.5f, Biome.MEDITERRANEAN_WOODLAND to 0.45f, Biome.TEMPERATE_FOREST to 0.4f, Biome.SALT_MARSH to 0.4f,
            Biome.SEMI_DESERT to 0.35f, Biome.SHRUBLAND to 0.3f, Biome.CONIFER_FOREST to 0.3f, Biome.SWAMP_FOREST to 0.3f,
            Biome.DESERT to 0.3f, Biome.BOG to 0.2f, Biome.MANGROVE to 0.2f, Biome.ICE_AND_ROCK to 0.2f,
        )
    }
}

/** A part of [HexLand], with the land's places and its other parts at hand. */
abstract class HexLandPart(@Transient protected val land: HexLand) {
    protected val hw get() = land.hw
    protected val world get() = land.hw.world
    protected val grid get() = land.hw.world.grid
    protected val hex get() = land.hw.hex
    protected val n get() = land.n
    protected val tuning get() = land.tuning
    protected val days get() = land.days
    protected val drink get() = land.drink
    protected val hydro get() = land.hydro
    protected val plants get() = land.plants
    protected val fire get() = land.fire
    protected fun mean(p: Int, f: (Int) -> Float) = land.mean(p, f)
}
