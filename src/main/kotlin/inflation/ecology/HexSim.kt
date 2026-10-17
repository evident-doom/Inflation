package inflation.ecology

import inflation.Books
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
        set(b) { field = b; land.books = b; if (b != null) keepBooks(b) }

    init { if (Books.ON) books = Books() }

    /** Names every stock of water and matter in [b]; they start from the world as the first step finds it (after a save is loaded). */
    private fun keepBooks(b: Books) {
        val n = land.n
        b.stock(Books.Stuff.WATER, "snow") { var s = 0.0; for (p in 0 until n) s += weather.snowpack[p] / 1000.0 * land.areaM2[p]; s }
        land.hydro.keepBooks(b)
        land.plants.keepBooks(b)
        fauna.keepBooks(b)
        groups.keepBooks(b)
    }

    /** One week: the weather, then water and plants, then the residents and small life, then the groups. */
    fun step() {
        // (What changed between steps, as a save loaded into the world or the viewer's hand, is charged to that.)
        books?.check("between steps")
        Profile.time("weather") { weather.step() }
        books?.let { b ->
            // (Snow falls in the cold; the melt goes to the soil, booked again there as it comes in.)
            var fell = 0.0; var melt = 0.0
            for (p in 0 until land.n) {
                if (weather.temperature[p] < 0f) fell += weather.precipitation[p] / 1000.0 * land.areaM2[p]
                melt += weather.meltwater[p] / 1000.0 * land.areaM2[p]
            }
            b.flow(Books.Stuff.WATER, "snowfall", fell); b.flow(Books.Stuff.WATER, "snowmelt (to the soil)", -melt)
            b.check("weather")
        }
        // Ash on the ground weathers into the soil over about two years.
        for (p in 0 until land.n) if (land.plants.ash[p] > 0f) land.plants.ash[p] = if (land.plants.ash[p] < 0.05f) 0f else land.plants.ash[p] * ashKeep
        val now = eruptions.step(weather.date, cfg.daysPerStep, wind = { c ->
            val p = hw.firstPlaceIn(hex.owner[c])
            if (p < 0) 1f to 0f else weather.windEast(p) to weather.windSouth(p)
        }) { c, mm -> val p = cellPlace[c]; if (p >= 0) land.plants.ash[p] += mm / hw.placeCells[p].size }
        // A great eruption's veil cools the whole region for a couple of years.
        for (e in now) weather.volcanicForcing(e.vei, e.volcano.x, e.volcano.y)
        Profile.time("water and plants") { land.step(weather) }
        // The residents' hunters find the herds about them as they find the resident game (see [HerdAt]).
        if (SHARED_HUNT) fauna.herdsAt = groups.herdsByPlace()
        fauna.step(weather, weather.date)
        groups.takeResidentsKills(fauna.herdsAt)
        books?.check("herds' losses to resident hunters")
        fauna.herdsAt = emptyMap()
        groups.step(weather, weather.date)
        books?.check("groups: the rest of the week")
    }
}
