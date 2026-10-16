package inflation.world

import kotlin.math.exp

/** The physical world: terrain, rock, water and climate. Living things are added in later steps. */
class World(
    val config: WorldConfig,
    val grid: Grid,
    val tectonics: Tectonics,
    val volcanoes: List<Volcano>,
    /** Metres above sea level. */
    val elevation: FloatArray,
    /** Connected open sea. Land below sea level that is cut off from it (rift basins) is not sea. */
    val isSea: BooleanArray,
    val geology: Geology,
    val climate: Climate,
    val water: SurfaceWater,
    val floodplain: Floodplain,
    val biome: Array<Biome>,
    val soilFertility: FloatArray,
) {
    /** Ruggedness, travel cost, visibility and defensibility; computed on first use. */
    val terrain: Terrain by lazy { Terrain(this) }

    fun latitude(i: Int): Double = climate.latitude[grid.y(i)]
    fun isRiver(i: Int): Boolean = !isSea[i] && !water.isLake(i) && water.discharge[i] >= SurfaceWater.RIVER_THRESHOLD
    fun riverClass(i: Int): RiverClass = if (isSea[i] || water.isLake(i)) RiverClass.NONE else RiverClass.of(water.discharge[i])

    /** Passable by dugout or raft at mean flow: deep enough, and not a rapid. */
    fun isNavigable(i: Int): Boolean {
        if (!isRiver(i)) return false
        val r = water.drainage.receiver[i]
        val gradient = if (r == i) 0f else (elevation[i] - elevation[r]) / water.drainage.receiverDistM[i]
        return Channel.depthM(water.discharge[i]) >= Channel.NAVIGABLE_DEPTH_M && gradient < Channel.RAPIDS_GRADIENT
    }

    companion object {
        /** Escarpments: size of the faulted provinces and spacing of the scarps in them, km; each block's band of the field; most a scarp rises, m. */
        const val SCARP_PROVINCE_KM = 300.0
        const val SCARP_SPACING_KM = 120.0
        const val SCARP_BAND = 0.08
        const val SCARP_M = 220.0
        /** Smallest lake kept, km²: shallower hollows are filled. */
        const val MIN_LAKE_KM2 = 6.0

        fun generate(cfg: WorldConfig, made: Landform? = null, log: (String) -> Unit = {}): World {
            // The lie of the land and its rocks (the slow part): as given, else as kept on disk from an
            // earlier run with the same settings and landform code, else made now (and kept).
            val land = made ?: inflation.ecology.HexSnapshot.LandformCache.load(cfg, log)
            val grid = land.grid; val noise = Noise(cfg.seed)
            val tect = land.tectonics; val volcanoes = land.volcanoes; val elevation = land.elevation; val geology = land.geology
            val sea = seaMask(grid, elevation)

            log("climate")
            val climate = Climate.generate(grid, cfg, elevation, sea, noise)
            log("rivers, lakes and floodplains")
            var water = SurfaceWater.generate(grid, elevation, sea, climate)
            if (Floodplain.planeValleyFloors(grid, elevation, sea, water, geology.sediment)) {
                water = SurfaceWater.generate(grid, elevation, sea, climate)
            }
            val floodplain = Floodplain.generate(grid, elevation, sea, water)
            depositSediments(grid, sea, geology, water, floodplain)

            log("biomes and soils")
            val biome = Biomes.classify(grid, elevation, sea, climate, water, floodplain)
            val soil = Biomes.soilFertility(grid, elevation, sea, geology, climate)
            return World(cfg, grid, tect, volcanoes, elevation, sea, geology, climate, water, floodplain, biome, soil)
        }


        /**
         * The lie of the land and its rocks: tectonics, volcanoes, bedrock, erosion and sediment, the
         * recent warping and the young volcanoes. It does not depend on the climate, so it can be kept
         * and reused while the weather is worked on (see [inflation.ecology.HexSnapshot.LandformCache]).
         */
        fun landform(cfg: WorldConfig, log: (String) -> Unit = {}): Landform {
            val grid = Grid(cfg.width, cfg.height, cfg.cellKm)
            val noise = Noise(cfg.seed)

            log("tectonics and volcanoes")
            val tect = Tectonics.generate(grid, cfg, noise)
            val elevation = tect.elevation.copyOf()
            val volcanoes = Volcanism.generate(grid, cfg, tect)
            Volcanism.build(grid, elevation, volcanoes, young = false, noise)
            val geology = Geology.bedrock(grid, tect, noise)
            Volcanism.applyRock(grid, geology, volcanoes, noise)

            escarpments(grid, noise, elevation)
            log("erosion and sediment (${cfg.erosionSteps} steps of ${cfg.erosionStepYears.toInt()} years)")
            val seaAtStart = seaMask(grid, elevation)
            val sediment = Erosion.run(grid, elevation, tect.uplift, geology.erodibility(), cfg.erosionSteps, cfg.erosionStepYears, cfg.seed,
                cfg.hillDiffusivity, cfg.criticalSlope, { done, all -> log("erosion and sediment: step $done of $all") }) {
                seaAtStart[it] || grid.isEdge(it)
            }
            System.arraycopy(sediment, 0, geology.sediment, 0, grid.size)

            log("recent deformation and young volcanoes")
            youngDeformation(grid, tect, noise, elevation)
            Volcanism.build(grid, elevation, volcanoes, young = true, noise)
            fillPuddles(grid, elevation)
            return Landform(grid, tect, volcanoes, elevation, geology)
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
                val rift = if (conv < 0 && tect.isContinental(i)) conv * 12_000.0 * exp(-tect.boundaryDistKm[i] / 12.0) else 0.0
                // Coastal plains and deltas stay flat; warping grows with height.
                val warp = 35.0 * noise.fbm(x - 3001, y + 1999, 60.0 / grid.cellKm, 3) * (elevation[i] / 40.0).coerceIn(0.0, 1.0)
                if (elevation[i] > 0f) elevation[i] = (elevation[i] + rift + warp).toFloat()
            }
        }

        /**
         * The slight warping after erosion leaves the plains dotted with shallow hollows a cell or two
         * across; real ones fill with silt or drain. Hollows smaller than [MIN_LAKE_KM2] are filled to
         * their spill level, leaving the lakes of real basins.
         */
        private fun fillPuddles(grid: Grid, elevation: FloatArray) {
            val sea0 = seaMask(grid, elevation)
            val filled = priorityFlood(grid, elevation, { sea0[it] || grid.isEdge(it) })
            val seen = BooleanArray(grid.size)
            val queue = IntArray(grid.size)
            val minCells = (MIN_LAKE_KM2 / (grid.cellKm * grid.cellKm)).toInt().coerceAtLeast(1)
            for (s in 0 until grid.size) {
                if (seen[s] || sea0[s] || filled[s] - elevation[s] < 0.01f) continue
                var head = 0; var tail = 0
                queue[tail++] = s; seen[s] = true
                while (head < tail) {
                    val i = queue[head++]
                    grid.forNeighbours(i) { j, _ -> if (!seen[j] && !sea0[j] && filled[j] - elevation[j] >= 0.01f) { seen[j] = true; queue[tail++] = j } }
                }
                if (tail < minCells) for (k in 0 until tail) { val i = queue[k]; elevation[i] = filled[i] + 0.01f }
            }
        }

        /**
         * Where the escarpments stand: how strongly the place is faulted (0..1) and how far it lies
         * through its block, 0 at the foot of the scarp below it rising to 1 at the top of the next.
         */
        fun scarpField(noise: Noise, cellKm: Double, x: Double, y: Double): Pair<Double, Double> {
            val faulted = ((noise.fbm(x + 777, y - 555, SCARP_PROVINCE_KM / cellKm, 2) - 0.1) / 0.3).coerceIn(0.0, 1.0)
            val blocks = noise.fbm(x - 123, y + 321, SCARP_SPACING_KM / cellKm, 2) / SCARP_BAND
            return faulted to blocks - kotlin.math.floor(blocks)
        }

        /**
         * Escarpments: in faulted provinces the land is tilted into blocks, each rising gently to a
         * scarp and dropping sharply to the next (cuestas, fault scarps), some tens to a few hundred
         * metres high. They are made before erosion, so rivers cut gorges through them and the
         * scarps stand between.
         */
        private fun escarpments(grid: Grid, noise: Noise, elevation: FloatArray) {
            for (i in 0 until grid.size) {
                if (elevation[i] !in 30f..1200f) continue
                val (faulted, through) = scarpField(noise, grid.cellKm, grid.x(i).toDouble(), grid.y(i).toDouble())
                elevation[i] += (SCARP_M * faulted * through).toFloat()
            }
        }

        /** Alluvium where sediment is thick or floods reach; salt and evaporites in closed lake beds. */
        private fun depositSediments(grid: Grid, sea: BooleanArray, geology: Geology, water: SurfaceWater, floodplain: Floodplain) {
            for (i in 0 until grid.size) {
                if (sea[i]) continue
                if (water.isLake(i) && water.lakeClosed[i]) {
                    geology.rock[i] = Rock.EVAPORITE
                    geology.minerals[i] = geology.minerals[i] or Mineral.SALT
                } else if (geology.sediment[i] > Erosion.COVER || floodplain.isFloodplain[i]) {
                    geology.rock[i] = Rock.ALLUVIUM
                    if (floodplain.isFloodplain[i]) geology.minerals[i] = geology.minerals[i] or Mineral.CLAY
                }
            }
        }
    }
}

/** The land before the climate: its grid, plates, volcanoes, heights and rocks (see [World.landform]). */
class Landform(val grid: Grid, val tectonics: Tectonics, val volcanoes: List<Volcano>, val elevation: FloatArray, val geology: Geology)
