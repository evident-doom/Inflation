package inflation.world

import kotlin.math.exp

/** The physical world: terrain, rock, water and climate. Living things are added in later steps. */
class World(
    val config: WorldConfig,
    val grid: Grid,
    val tectonics: Tectonics,
    /** Metres above sea level. */
    val elevation: FloatArray,
    /** Connected open sea. Land below sea level that is cut off from it (rift basins) is not sea. */
    val isSea: BooleanArray,
    val geology: Geology,
    val climate: Climate,
    val water: SurfaceWater,
    val biome: Array<Biome>,
    val soilFertility: FloatArray,
) {
    fun latitude(i: Int): Double = climate.latitude[grid.y(i)]
    fun isRiver(i: Int): Boolean = !isSea[i] && !water.isLake(i) && water.discharge[i] >= SurfaceWater.RIVER_THRESHOLD

    companion object {
        fun generate(cfg: WorldConfig, log: (String) -> Unit = {}): World {
            val grid = Grid(cfg.width, cfg.height, cfg.cellKm)
            val noise = Noise(cfg.seed)

            log("tectonics")
            val tect = Tectonics.generate(grid, cfg, noise)
            val elevation = tect.elevation.copyOf()
            val geology = Geology.bedrock(grid, tect, noise)

            log("erosion (${cfg.erosionSteps} steps of ${cfg.erosionStepYears.toInt()} years)")
            var sea = seaMask(grid, elevation)
            val seaAtStart = sea
            Erosion.run(grid, elevation, tect.uplift, geology.erodibility(), cfg.erosionSteps, cfg.erosionStepYears, cfg.seed) {
                seaAtStart[it] || grid.isEdge(it)
            }

            log("recent deformation")
            youngDeformation(grid, tect, noise, elevation)
            sea = seaMask(grid, elevation)

            log("climate")
            val climate = Climate.generate(grid, cfg, elevation, sea, noise)
            log("rivers and lakes")
            val water = SurfaceWater.generate(grid, elevation, sea, climate)
            depositSediments(grid, elevation, sea, geology, water)

            log("biomes and soils")
            val biome = Biomes.classify(grid, elevation, sea, climate, water)
            val soil = Biomes.soilFertility(grid, elevation, sea, geology, climate)
            return World(cfg, grid, tect, elevation, sea, geology, climate, water, biome, soil)
        }

        /** Cells at or below sea level connected to a map edge that is itself below sea level. */
        fun seaMask(grid: Grid, elevation: FloatArray): BooleanArray {
            val sea = BooleanArray(grid.size)
            val queue = IntArray(grid.size)
            var head = 0; var tail = 0
            for (i in 0 until grid.size) if (grid.isEdge(i) && elevation[i] <= 0f) { sea[i] = true; queue[tail++] = i }
            while (head < tail) {
                val i = queue[head++]
                grid.forNeighbours(i) { j, _ -> if (!sea[j] && elevation[j] <= 0f) { sea[j] = true; queue[tail++] = j } }
            }
            return sea
        }

        /**
         * Erosion fills every basin over geological time, but real lakes are young: they sit in
         * basins made by recent faulting and warping. Rifts subside and the land surface is gently
         * warped by tens of metres.
         */
        private fun youngDeformation(grid: Grid, tect: Tectonics, noise: Noise, elevation: FloatArray) {
            for (i in 0 until grid.size) {
                val x = grid.x(i).toDouble(); val y = grid.y(i).toDouble()
                val conv = tect.convergence[i]
                val rift = if (conv < 0 && tect.plates[tect.plateId[i]].continental) conv * 12_000.0 * exp(-tect.boundaryDistKm[i] / 12.0) else 0.0
                val warp = 35.0 * noise.fbm(x - 3001, y + 1999, 60.0 / grid.cellKm, 3)
                if (elevation[i] > 0f) elevation[i] = (elevation[i] + rift + warp).toFloat()
            }
        }

        /** Alluvium on floodplains and deltas; salt and evaporites in closed lake beds. */
        private fun depositSediments(grid: Grid, elevation: FloatArray, sea: BooleanArray, geology: Geology, water: SurfaceWater) {
            for (i in 0 until grid.size) {
                if (sea[i]) continue
                if (water.isLake(i) && water.lakeClosed[i]) {
                    geology.rock[i] = Rock.EVAPORITE
                    geology.minerals[i] = geology.minerals[i] or Mineral.SALT
                } else if (water.discharge[i] > 5f && Biomes.slope(grid, elevation, i) < 0.004f) {
                    geology.rock[i] = Rock.ALLUVIUM
                    geology.minerals[i] = geology.minerals[i] or Mineral.CLAY
                }
            }
        }
    }
}
