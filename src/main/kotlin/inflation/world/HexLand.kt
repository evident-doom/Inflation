package inflation.world

import inflation.Books
import inflation.Par
import inflation.Tuning
import inflation.Config
import inflation.Kg
import inflation.KgM2
import inflation.store.MovedInto

import kotlin.math.exp

/**
 * Water and plants on the places of a [HexWorld] (each hex, or its low and high ground), stepped with the
 * weather on the same places: what each place is, and its parts, the water ([HexHydrology], [HexDrinkingWater]),
 * the plants ([HexPlants]) and fires ([HexFires]), on the averages of each place's cells. (They began as the rules of a
 * fine-grid water and plant model, since gone further and removed, 2026-11-15.)
 */
class HexLand(internal val hw: HexWorld, internal val seed: Long, /** The run's settings ([Tuning]). */ @Transient val tuning: Tuning = Tuning.config) {
    private val world = hw.world
    private val grid = world.grid
    private val hex = hw.hex
    val n = hw.placeCells.size
    /** Every place, in order. */
    val places get() = inflation.world.PlaceRange(n)
    internal val days = world.config.daysPerStep
    internal val stepSeconds = days * 86_400.0

    // ---------------------------------------------------------------- what each place is

    /** Area of each place, km², and m². */
    /**
     * Whether this tick is the first of the step: what the ticks hand the slow parts as a flow (the plants' litterfall, the
     * runoff) is set afresh in it and added to in the others, so the slow parts take in the whole step's. Always true at one
     * tick a step.
     */
    @Transient var firstTick = true
    /**
     * Whether this tick is the last of the step: what the slow parts hand the ticks once a step (the residents' trampling)
     * holds for each of them and is cleared in this one. Always true at one tick a step.
     */
    @Transient var lastTick = true
    @Transient val areaKm2 = FloatArray(n) { (hw.placeCells[it].size * grid.cellKm * grid.cellKm).toFloat() }
    /** Place [p]'s area, m². */
    fun m2(p: Place) = areaKm2[p] * 1e6f
    /** [kg] spread over place [p]. */
    fun spread(p: Place, kg: Kg) = KgM2(kg.v / m2(p))
    /** All of [x] over place [p]. */
    fun total(p: Place, x: KgM2) = Kg(x.v * m2(p))
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
    /**
     * Share of waterlogged ground, where woody plants drown: under the tides, or low flat ground whose soil has stayed near full
     * over the last year ([HexCountry.sodden]). Set each step by [country] from the water as it is (Olli 2026-11-18: no longer
     * from world-gen's marsh, bog and salt marsh labels).
     */
    val waterlogged = FloatArray(n)
    /**
     * The share of each place's plants an animal or a forager can eat, 0..1: most of an open grassland, little of aromatic
     * garrigue, thorny scrub, desert bush, bog or conifer forest, where the plants are defended (bitter, aromatic, thorny or
     * poisonous) or tough. Set each step by [country] from the herbs, shrubs and trees standing there now and the climate they
     * grow in ([HexCountry.palatableNow]), no longer from world-gen's label. (Half of even the poorest country's plants is
     * eaten by something; the rest of it by that share. A balancing run may change that floor: t.palatableFloor.)
     */
    val palatable = FloatArray(n)

    /** The dead buried at each place (graves weather away over centuries), and the ornaments buried with them. */
    val burials = FloatArray(n)
    /**
     * How holy each place is: the people who held rites there and the dead laid there, fading over the years no one comes
     * ([inflation.ecology.SANCTITY_YEARS]). Bands taking up a rite are drawn to a holier place ([inflation.ecology.riteWeek]).
     */
    val sanctity = FloatArray(n)
    val graveGoods = FloatArray(n)
    /** Share of the ground where good knapping stone (flint, obsidian) crops out. */
    @Transient val stone = FloatArray(n) { p -> mean(p) { if (world.geology.minerals[it] and (Mineral.FLINT or Mineral.OBSIDIAN) != 0) 1f else 0f } }
    /** Share of the ground where obsidian crops out, near explosive volcanic vents: glass that knaps to the sharpest edge of any stone. */
    @Transient val obsidian = FloatArray(n) { p -> mean(p) { if (world.geology.minerals[it] and Mineral.OBSIDIAN != 0) 1f else 0f } }
    /** Share of the ground where salt can be got (crusts on dry lake beds and salt pans), and where red and yellow ochre crops out. */
    @Transient val salt = FloatArray(n) { p -> mean(p) { if (world.geology.minerals[it] and Mineral.SALT != 0) 1f else 0f } }
    @Transient val ochre = FloatArray(n) { p -> mean(p) { if (world.geology.minerals[it] and Mineral.OCHRE != 0) 1f else 0f } }

    /**
     * How many months each place's seasons run behind the land's usual ones (ahead, below 0): the month its mean temperature
     * first reaches [SPRING_C] against the median such month of all places, -2 to 3 (a place that never warms so far, 3).
     * So the seasons the calendar rules name (honey, eggs, the small game's and birds' breeding, the hibernators' autumn, the
     * herds' summer: [localMonth]) come later in the hills and earlier on the warm coast, and stay where they were on
     * average. Worked out once from the climate's months.
     */
    @Transient val seasonLag: ByteArray = run {
        val warm = IntArray(n) { p -> (0 until 12).firstOrNull { m -> mean(p) { world.climate.monthlyTemp[m][it] } >= SPRING_C } ?: 12 }
        val usual = if (n == 0) 0 else warm.sorted()[n / 2]
        ByteArray(n) { p -> (warm[p] - usual).coerceIn(-2, 3).toByte() }
    }

    /** The month at place [p] as its seasons go ([seasonLag]): [month] of the calendar, less the months its seasons run behind. */
    fun localMonth(p: Place, month: Int): Int = Math.floorMod(month - seasonLag[p.i], 12)
    /**
     * Share of the ground where rock fit for grinding stones lies bare (not under more than [BARE_ROCK_M] of sediment), by how
     * well it grinds ([GRINDING_ROCK]): mortars, pestles and querns were pecked from basalt, sandstone and granite, not from
     * flint. Only some beds of a rock serve ([QUERN_BEDS]): the rest is too soft, too fine, too cracked or too weathered, so
     * good grinding rock lies in patches some kilometres across, and between them none is to be had but by giving on.
     */
    @Transient val groundStone = run {
        val beds = Noise(world.config.seed + QUERN_SEED)
        FloatArray(n) { p -> mean(p) { i ->
            val rock = world.geology.rock[i].ordinal
            if (world.geology.sediment[i] > BARE_ROCK_M ||
                beds.fbm(grid.x(i).toDouble(), grid.y(i).toDouble(), QUERN_PATCH_KM / grid.cellKm, 3) <= QUERN_BEDS[rock]) 0f
            else GRINDING_ROCK[rock]
        } }
    }
    /** Share of floodplain, which a river in spate covers. */
    @Transient val floodShare = FloatArray(n) { p -> mean(p) { if (world.floodplain.isFloodplain[it]) 1f else 0f } }
    @Transient internal val pet = Array(12) { m -> FloatArray(n) { p -> mean(p) { world.climate.monthlyPet[m][it] } } }
    /** The mean month's rain of each place, mm, by month: against [pet], whether a month dries more than it rains ([dryMonth]). */
    @Transient internal val rainNormal = Array(12) { m -> FloatArray(n) { p -> mean(p) { world.climate.monthlyPrecip[m][it] } } }
    /** Whether month [m] at place [p] dries more than it rains in the mean year: a salt pan's crust lies bare to scrape. */
    fun dryMonth(p: Place, m: Int) = rainNormal[m][p] < pet[m][p]
    /** Mean ruggedness, cover from broken ground and the best refuge and lookout of each place. */
    @Transient val ruggedness = FloatArray(n) { p -> mean(p) { world.terrain.ruggedness[it] } }
    /**
     * How the ruggedness is spread over each place: the ruggedness at [RUGGED_SHARES] even steps through its
     * ground, from its smoothest to its roughest (a rough hex still has its valley floors and meadows).
     */
    @Transient val ruggedSpread: Array<FloatArray> = Array(RUGGED_SHARES) { FloatArray(n) }.also { q ->
        Par.forEach("land: ruggedness spread", n) { p ->
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
        Par.forEach("land: over ground", n) { p -> out[p] = mean(p) { f(world.terrain.ruggedness[it]) } }
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
    /** Neighbouring places: the other half of its hex, if split, and the places of the hexes around it. */
    @Transient val neighbours: Array<IntArray> = Array(n) { p ->
        val h = hw.placeHex[p]
        val out = ArrayList<Int>()
        for (q in hw.placesOf(h)) if (q != p) out += q
        for (d in 0 until 6) {
            val nh = hex.neighbour(h, d)
            if (nh.none) continue
            for (q in hw.placesOf(nh)) out += q
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
        "gdd", "annualGdd", "herb", "shrub", "tree", "moisture", "warmth", "fertility", "organicFertility", "litterfall", "shrubShoots",
        "treeShoots", "shootsSet", "browsing", "grazing", "rooting", "animalSeed", "trampling", "green", "hayFallen", "hayFresh", "warmest", "growFrom",
        "growSpan", "perennialShare", "cured", "greenOpen", "greenSeep", "greenWet", "greenShade", "curedShade", "ash", "trails", "treeSeed", "shrubSeed",
        "stepCount",
    )
    val plants = HexPlants(this)
    @field:MovedInto("burnt", "burning", "fires", "lit")
    val fire = HexFires(this)
    /** What kind of country each place is now, and the waterlogged and edible shares that follow from its water and plants. */
    val country = HexCountry(this)

    /** The land's part of the week, in order: the water, then the plants that grow by it. */
    @Transient val phases = listOf(
        Phase("water") { hydro.step(it); drink.step(it) },
        Phase("plants") { plants.step(it); country.step() },
    )
    /** Advances one step with the weather of that step on the same places. */
    fun step(weather: Weather) = runPhases(phases, weather, books)

    companion object {
        /** Steps through each place's ground at which its ruggedness is kept (see [ruggedSpread]). */
        const val RUGGED_SHARES = 8
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

/**
 * How well each surface rock ([Rock]) serves for grinding stones, 0..1: vesicular basalt and scoria best (they stay rough as
 * they wear; Natufian and later querns and mortars of basalt were carried far from the lava fields), sandstone and granite
 * well, limestone fairly (the Natufian bedrock and boulder mortars), the soft and loose rocks not at all. Not measured.
 */
internal val GRINDING_ROCK = FloatArray(Rock.entries.size) { when (Rock.entries[it]) {
    Rock.BASALT, Rock.VOLCANIC -> 1f
    Rock.SANDSTONE, Rock.GRANITE -> 0.7f
    Rock.LIMESTONE -> 0.4f
    Rock.METAMORPHIC -> 0.2f
    Rock.SHALE, Rock.ALLUVIUM, Rock.EVAPORITE -> 0f
} }
/**
 * Which beds of each surface rock ([Rock]) give grinding stones: the level a patch noise must pass, so about half of a lava
 * field (the rough, vesicular flows), a fifth of sandstone (the hard, well-cemented, middling to coarse beds), a tenth of
 * granite (coarse and unweathered), a twenty-fifth of the metamorphic rocks (quartzite) and of limestone (the hard beds the
 * Natufians cut mortars in); none of the soft and loose rocks. The shares are estimates; that querns were pecked from a few
 * known outcrops and carried tens of kilometres from them (the basalt of the Golan and Galilee in the Levant; the Eifel lava) is not.
 */
internal val QUERN_BEDS = FloatArray(Rock.entries.size) { when (Rock.entries[it]) {
    Rock.BASALT, Rock.VOLCANIC -> 0f
    Rock.SANDSTONE -> 0.15f
    Rock.GRANITE -> 0.25f
    Rock.METAMORPHIC, Rock.LIMESTONE -> 0.35f
    Rock.SHALE, Rock.ALLUVIUM, Rock.EVAPORITE -> Float.MAX_VALUE
} }
/** The wavelength of the patches of grinding rock ([QUERN_BEDS]), km: beds and flows a few kilometres across. An estimate. */
internal const val QUERN_PATCH_KM = 15.0
/** Offset to the world's seed for the patches of grinding rock ([QUERN_BEDS]), so they fall apart from its other noises. */
private const val QUERN_SEED = 7_919L
/**
 * Sediment over bedrock beyond which no rock lies bare for the taking, m: rock is got where it crops out, in scree and in
 * stream beds, not dug from under soil. (Erosion counts bedrock as bare under up to [Erosion.COVER].) An estimate.
 */
internal const val BARE_ROCK_M = 0.5f

/** The mean temperature, °C, a place reaches as its spring comes ([HexLand.seasonLag]): when the grass and the insects wake. An estimate. */
const val SPRING_C = 10f
