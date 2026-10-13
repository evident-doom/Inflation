package inflation.ecology

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

    /** One week: the weather, then water and plants, then the residents and small life, then the groups. */
    fun step() {
        weather.step()
        land.step(weather)
        fauna.step(weather, weather.date)
        groups.step(weather, weather.date)
    }
}
