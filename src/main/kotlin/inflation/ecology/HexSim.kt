package inflation.ecology

import inflation.world.Eruptions
import inflation.world.HexLand
import inflation.world.HexMap
import inflation.world.HexWorld
import inflation.world.Weather
import inflation.world.World

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
) {
    val cfg = world.config
    val hex = HexMap(world)
    val hw = HexWorld(world, hex)
    val weather = Weather(hw.weatherPlaces(cfg), cfg)
    val land = HexLand(hw, cfg.seed)
    val fauna = HexFauna(hw, land, cfg.daysPerStep)
    val groups = HexGroups(hw, land, fauna, cfg.daysPerStep, cfg.seed, kinds, uniformStart)
    /** Volcanoes erupting at their own rates; the ash falls downwind on the places. */
    val eruptions = Eruptions(world.grid, world.volcanoes, cfg.seed)

    /** One week: the weather, then water and plants, then the residents and small life, then the groups. */
    fun step() {
        Profile.time("weather") { weather.step() }
        val now = eruptions.step(weather.date, cfg.daysPerStep) { c ->
            val h = hex.owner[c]
            val p = (0 until HexWorld.BANDS_M.size).map { hw.placeOf[it][h] }.firstOrNull { it >= 0 }
            if (p == null) 1f to 0f else weather.windEast(p) to weather.windSouth(p)
        }
        // A great eruption's veil cools the whole region for a couple of years.
        for (e in now) weather.volcanicForcing(e.vei, e.volcano.x, e.volcano.y)
        if (eruptions.anyAsh || now.isNotEmpty()) for (p in 0 until land.n) land.ash[p] = hw.placeCells[p].sumOf { eruptions.ash[it].toDouble() }.toFloat() / hw.placeCells[p].size
        else if (land.ash.any { it > 0f }) land.ash.fill(0f)
        Profile.time("water and plants") { land.step(weather) }
        fauna.step(weather, weather.date)
        groups.step(weather, weather.date)
    }
}
