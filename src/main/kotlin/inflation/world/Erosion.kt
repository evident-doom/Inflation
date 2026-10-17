package inflation.world

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Fluvial erosion with the stream-power law, dh/dt = U - K A^m S, solved with the implicit,
 * O(n) scheme of Braun & Willett (2013) with n = 1, plus linear hillslope diffusion.
 *
 * Eroded material is carried downstream and conserved. Rivers drop what they cannot carry where
 * their gradient flattens (floodplains, fans), closed basins fill with sediment, and what reaches
 * the coast builds deltas and the continental shelf. Sediment is softer than bedrock, so rivers
 * cut through it easily. Present-day lakes come from young deformation applied afterwards.
 */
object Erosion {
    const val M = 0.5
    /** Base erodibility, 1/yr for m = 0.5 with area in m^2. Scaled per rock type. */
    const val K_BASE = 1.2e-6
    /** Hillslope diffusivity, m^2/yr. */
    const val DIFFUSIVITY = 0.5
    /**
     * Transport capacity per step is CAPACITY · dt · A · S (m^3). With typical erosion rates
     * rivers start depositing where the gradient falls below about 0.5%.
     */
    const val CAPACITY = 0.02
    /** Large rivers build delta plains this high, out to [DELTA_KM] from the mouth... */
    const val DELTA_TOP = 1f
    const val DELTA_KM = 10f
    /** Sediment delivered at one mouth in one step needed to build a delta plain above the sea, m^3. */
    const val DELTA_VOLUME = 3e9f
    /** ...then marine sediment builds the shelf up to this depth... */
    const val SHELF_TOP = -2f
    /** ...and deepens by this much per km away from it, giving a gently sloping shelf... */
    const val SHELF_SLOPE = 0.6f
    /** ...out to the shelf break, beyond which the continental slope drops steeply. */
    const val SHELF_BREAK_KM = 120f
    const val MAX_SPREAD_KM = 150f
    /** Thickness above which sediment, not bedrock, sets the erodibility, m. */
    const val COVER = 2f

    /** Returns the thickness of sediment left on each cell, m. */
    fun run(
        grid: Grid,
        elevation: FloatArray,
        uplift: FloatArray,
        erodibility: FloatArray,
        steps: Int,
        dtYears: Double,
        seed: Long,
        diffusivity: Double = DIFFUSIVITY,
        criticalSlope: Double = 0.0,
        /** Told after each step: how many are done of how many. */
        progress: (Int, Int) -> Unit = { _, _ -> },
        isOutlet: (Int) -> Boolean,
    ): FloatArray {
        val n = grid.size
        val cellArea = grid.cellAreaM2.toFloat()
        val area = FloatArray(n) { cellArea }
        val lap = FloatArray(n)
        val before = FloatArray(n)
        val sediment = FloatArray(n)
        val qs = FloatArray(n)
        val toSea = FloatArray(n)
        // (Whether each cell is an outlet, worked out once; the sea floor round a mouth in order of
        // distance, worked out once; and for each mouth how far out its sea floor is already full.)
        val outlet = BooleanArray(n) { isOutlet(it) }
        val ring = Ring(grid)
        val fullTo = IntArray(n); val fullDelta = BooleanArray(n)
        val rnd = Random(seed)
        val sedimentErodibility = Rock.ALLUVIUM.erodibility
        repeat(steps) { step ->
            // Closed basins fill with sediment.
            val filled = priorityFlood(grid, elevation, isOutlet, routable = true)
            for (i in 0 until n) {
                val d = filled[i] - elevation[i]
                if (d > 0.01f && !isOutlet(i)) sediment[i] += d
            }
            System.arraycopy(filled, 0, elevation, 0, n)

            val drainage = Drainage.build(grid, elevation, isOutlet, rnd)
            val acc = drainage.accumulate(area)
            System.arraycopy(elevation, 0, before, 0, n)
            for (i in drainage.stack) {
                val r = drainage.receiver[i]
                if (r == i) continue
                val k = if (sediment[i] > COVER) sedimentErodibility else erodibility[i]
                val f = k * K_BASE * dtYears * acc[i].toDouble().pow(M) / drainage.receiverDistM[i]
                val h = (elevation[i] + dtYears * uplift[i] + f * elevation[r]) / (1.0 + f)
                elevation[i] = h.toFloat()
            }

            // Carry eroded material downstream; drop the excess over capacity.
            qs.fill(0f); toSea.fill(0f)
            for (k in n - 1 downTo 0) {
                val i = drainage.stack[k]
                val r = drainage.receiver[i]
                if (r == i) { if (isOutlet(i)) toSea[i] += qs[i]; continue }
                val eroded = before[i] + (dtYears * uplift[i]).toFloat() - elevation[i]
                if (eroded > 0f) {
                    qs[i] += eroded * cellArea
                    sediment[i] = max(0f, sediment[i] - eroded)
                }
                val slope = max(0f, (elevation[i] - elevation[r]) / drainage.receiverDistM[i])
                val capacity = (CAPACITY * dtYears * acc[i] * slope).toFloat()
                if (qs[i] > capacity) {
                    val dep = min((qs[i] - capacity) / cellArea, 20f)
                    elevation[i] += dep
                    sediment[i] += dep
                    qs[i] -= dep * cellArea
                }
                qs[r] += qs[i]
            }
            depositOffshore(grid, elevation, sediment, toSea, outlet, ring, fullTo, fullDelta)
            diffuse(grid, elevation, lap, dtYears, diffusivity, isOutlet)
            if (criticalSlope > 0) slide(grid, elevation, criticalSlope.toFloat(), isOutlet)
            progress(step + 1, steps)
        }
        return sediment
    }

    /** The cells within [MAX_SPREAD_KM] of a cell, as steps across and down, in order of distance, km. */
    private class Ring(grid: Grid) {
        val dx: IntArray; val dy: IntArray; val km: FloatArray
        init {
            val r = (MAX_SPREAD_KM / grid.cellKm).toInt() + 1
            class Cell(val dx: Int, val dy: Int, val km: Float)
            val all = ArrayList<Cell>()
            for (y in -r..r) for (x in -r..r) {
                val d = (kotlin.math.sqrt((x * x + y * y).toDouble()) * grid.cellKm).toFloat()
                if (d < MAX_SPREAD_KM) all += Cell(x, y, d)
            }
            all.sortWith(compareBy<Cell>({ it.km }, { it.dy }, { it.dx }))
            dx = IntArray(all.size) { all[it].dx }; dy = IntArray(all.size) { all[it].dy }; km = FloatArray(all.size) { all[it].km }
        }
    }

    /**
     * Sediment reaching the coast spreads outward from the river mouth, nearest sea floor first,
     * filling it up to a profile: a delta plain just above sea level near the mouth, then a shelf
     * deepening gently to the shelf break, then the steep continental slope. What remains spills
     * into the deep sea. (The sea floor round a mouth is taken in order of distance from a table
     * worked out once; a mouth remembers how far out its floor is already filled to the profile,
     * which stays so, since the sea floor does not wear, and starts from there next time.)
     */
    private fun depositOffshore(
        grid: Grid, elevation: FloatArray, sediment: FloatArray, toSea: FloatArray,
        isOutlet: BooleanArray, ring: Ring, fullTo: IntArray, fullDelta: BooleanArray,
    ) {
        val cellArea = grid.cellAreaM2.toFloat()
        // The coast is cut into tiles twice as wide as sediment ever spreads, and tiles of one colour
        // of a four-colour chessboard are filled at the same time on separate cores: their spreads
        // cannot reach each other's sea floor.
        val tileCells = max(1, (2 * MAX_SPREAD_KM / grid.cellKm).toInt() + 1)
        val tilesX = (grid.width + tileCells - 1) / tileCells; val tilesY = (grid.height + tileCells - 1) / tileCells
        val byTile = Array(tilesX * tilesY) { ArrayList<Int>() }
        for (s in 0 until grid.size) if (toSea[s] > 0f) byTile[(grid.y(s) / tileCells) * tilesX + grid.x(s) / tileCells] += s
        for (colour in 0 until 4) {
            val tiles = (0 until tilesX * tilesY).filter { ((it / tilesX) % 2) * 2 + (it % tilesX) % 2 == colour && byTile[it].isNotEmpty() }
            tiles.parallelStream().forEach { tile ->
                for (s in byTile[tile]) {
                    var volume = toSea[s]
                    val delta = volume > DELTA_VOLUME
                    val deltaKm = if (delta) DELTA_KM else 0f
                    // (A mouth that has just grown a delta has a higher profile near it to fill again.)
                    if (fullDelta[s] != delta) { fullTo[s] = 0; fullDelta[s] = delta }
                    val sx = grid.x(s); val sy = grid.y(s)
                    var k = fullTo[s]; var allFull = true
                    while (k < ring.km.size && volume > 0f) {
                        val x = sx + ring.dx[k]; val y = sy + ring.dy[k]
                        if (x in 0 until grid.width && y in 0 until grid.height) {
                            val i = y * grid.width + x
                            if (isOutlet[i]) {
                                val km = ring.km[k]
                                val target = when {
                                    km < deltaKm -> DELTA_TOP
                                    km < SHELF_BREAK_KM -> SHELF_TOP - SHELF_SLOPE * (km - deltaKm)
                                    else -> SHELF_TOP - SHELF_SLOPE * (SHELF_BREAK_KM - deltaKm) - 25f * (km - SHELF_BREAK_KM)
                                }
                                val room = (target - elevation[i]) * cellArea
                                if (room > 0f) {
                                    val dep = min(room, volume)
                                    elevation[i] += dep / cellArea
                                    sediment[i] += dep / cellArea
                                    volume -= dep
                                    if (dep < room) allFull = false
                                }
                            }
                        }
                        if (allFull) fullTo[s] = k + 1
                        k++
                    }
                }
            }
        }
    }

    /**
     * Landslides: where a hillside is steeper than [critical] (rise over run) towards a neighbour,
     * the top gives way down to that angle. The fallen rock is left for the rivers to carry off.
     */
    private fun slide(grid: Grid, h: FloatArray, critical: Float, isOutlet: (Int) -> Boolean) {
        val w = grid.width
        val drop = critical * grid.cellM.toFloat()
        for (y in 1 until grid.height - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            if (isOutlet(i)) continue
            val lo = minOf(minOf(h[i - 1], h[i + 1]), minOf(h[i - w], h[i + w]))
            if (h[i] - lo > drop) h[i] = lo + drop
        }
    }

    private fun diffuse(grid: Grid, h: FloatArray, lap: FloatArray, dt: Double, diffusivity: Double, isOutlet: (Int) -> Boolean) {
        val w = grid.width
        val c = (diffusivity * dt / (grid.cellM * grid.cellM)).coerceAtMost(0.2).toFloat()
        for (y in 1 until grid.height - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            lap[i] = h[i - 1] + h[i + 1] + h[i - w] + h[i + w] - 4 * h[i]
        }
        for (i in 0 until grid.size) if (!isOutlet(i) && !grid.isEdge(i)) h[i] += c * lap[i]
    }
}
