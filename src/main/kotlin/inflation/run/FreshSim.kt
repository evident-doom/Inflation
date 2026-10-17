package inflation.run

import inflation.ecology.HexSim
import inflation.ecology.Species
import inflation.world.World
import inflation.world.WorldConfig

/**
 * A model on a world made afresh for [cfg] (not from the world cache: training and the checks draw
 * many one-off worlds), with groups of [kinds] only.
 */
fun freshSim(cfg: WorldConfig, kinds: List<Species>) = HexSim(World.generate(cfg), kinds)
