package inflation.ecology

import inflation.Books
import inflation.Stocks
import inflation.Tuning
import inflation.world.Eruptions
import inflation.world.HexLand
import inflation.world.HexMap
import inflation.world.HexWorld
import inflation.world.Weather
import inflation.world.World
import kotlin.math.exp

/**
 * The whole hex model over a generated [world]: the hexes and their places, the weather on them,
 * water and plants, the soil and small life with the resident herds, and the herds and packs that
 * live as groups. One [step] is one week, always in the same order.
 */
class HexSim(
    val world: World,
    /** The kinds that live as groups (default: the migratory herds and the hunters other than people). */
    kinds: List<Species>? = null,
    /** Start the groups spread evenly over the land, wherever it suits them or not. */
    uniformStart: Boolean = false,
    /** The run's settings (by default as the config file gives them). */
    tuning: Tuning = Tuning.config,
) {
    val cfg = world.config
    val hex = HexMap(world)
    val hw = HexWorld(world, hex)
    val weather = Weather(hw.weatherPlaces(cfg), cfg)
    val land = HexLand(hw, cfg.seed, tuning)
    val fauna = HexFauna(hw, land, cfg.daysPerStep)
    val groups = HexGroups(hw, land, fauna, cfg.daysPerStep, cfg.seed, kinds, uniformStart)
    /** Volcanoes erupting at their own rates; the ash falls downwind on the places. */
    val eruptions = Eruptions(world.grid, world.volcanoes, cfg.seed)
    /** The place each fine cell lies in (-1 for none), for where ash falls: made at the first eruption. */
    private val cellPlace by lazy { IntArray(world.grid.size) { -1 }.also { cp -> for (p in hw.placeCells.indices) for (c in hw.placeCells[p]) cp[c] = p } }
    private val ashKeep = exp(-cfg.daysPerStep / 720.0).toFloat()
    /** The books of water and matter (see [Books]), when kept: set before the first step to keep them. */
    var books: Books? = null
        set(b) { field = b; land.books = b; b?.keep(stocks()) }

    init { if (Books.ON) books = Books() }

    /** Every stock of water and matter in the model, as each part names them (the books start from them as the first step finds them, after a save is loaded). */
    fun stocks(): Stocks = Stocks().also { s ->
        val n = land.n
        s.add(Books.Stuff.WATER, "snow", weather.snowpack) { var sum = 0.0; for (p in 0 until n) sum += weather.snowpack[p] / 1000.0 * land.areaM2[p]; sum }
        land.hydro.listStocks(s)
        land.plants.listStocks(s)
        fauna.listStocks(s)
        groups.listStocks(s)
    }

    /**
     * The week, in order: the weather and what falls from the sky, the water and plants ([HexLand.phases]), the residents and
     * small life ([HexFauna.phases]), what the residents' hunters took of the herds, and the groups ([HexGroups.phases]).
     */
    @Transient val phases: List<Phase> = listOf(
        Phase("weather") { weather.step(); bookSnow() },
        Phase("ash and eruptions") { fallingAsh() },
    ) + land.phases + listOf(
        // The residents' hunters find the herds about them as they find the resident game (see [HerdAt]).
        Phase("residents: herds about") { fauna.herdsAt = groups.herdsByPlace() },
    ) + fauna.phases + listOf(
        Phase("herds' losses to resident hunters") { groups.takeResidentsKills(fauna.herdsAt); fauna.herdsAt = emptyMap() },
    ) + groups.phases

    /** One week ([phases]). */
    fun step() {
        // (What changed between steps, as a save loaded into the world or the viewer's hand, is charged to that.)
        books?.check("between steps")
        runPhases(phases, weather, books)
    }

    /** Snow falls in the cold; the melt goes to the soil, booked again there as it comes in. */
    private fun bookSnow() {
        val b = books ?: return
        var fell = 0.0; var melt = 0.0
        for (p in 0 until land.n) {
            if (weather.temperature[p] < 0f) fell += weather.precipitation[p] / 1000.0 * land.areaM2[p]
            melt += weather.meltwater[p] / 1000.0 * land.areaM2[p]
        }
        b.flow(Books.Stuff.WATER, "snowfall", fell); b.flow(Books.Stuff.WATER, "snowmelt (to the soil)", -melt)
    }

    /** Ash on the ground weathers into the soil over about two years; eruptions lay fresh ash, and a great one's veil cools the whole region for a couple of years. */
    private fun fallingAsh() {
        for (p in 0 until land.n) if (land.plants.ash[p] > 0f) land.plants.ash[p] = if (land.plants.ash[p] < 0.05f) 0f else land.plants.ash[p] * ashKeep
        val now = eruptions.step(weather.date, cfg.daysPerStep, wind = { c ->
            val p = hw.firstPlaceIn(hex.owner[c])
            if (p < 0) 1f to 0f else weather.windEast(p) to weather.windSouth(p)
        }) { c, mm -> val p = cellPlace[c]; if (p >= 0) land.plants.ash[p] += mm / hw.placeCells[p].size }
        for (e in now) weather.volcanicForcing(e.vei, e.volcano.x, e.volcano.y)
    }
}
