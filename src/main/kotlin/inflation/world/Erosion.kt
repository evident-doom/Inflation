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
        val stamp = IntArray(n)
        val rnd = Random(seed)
        val sedimentErodibility = Rock.ALLUVIUM.erodibility
        var tag = 0
        repeat(steps) {
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
            tag = depositOffshore(grid, elevation, sediment, toSea, isOutlet, stamp, tag)
            diffuse(grid, elevation, lap, dtYears, diffusivity, isOutlet)
            if (criticalSlope > 0) slide(grid, elevation, criticalSlope.toFloat(), isOutlet)
        }
        return sediment
    }

    /**
     * Sediment reaching the coast spreads outward from the river mouth, nearest sea floor first,
     * filling it up to a profile: a delta plain just above sea level near the mouth, then a shelf
     * deepening gently to the shelf break, then the steep continental slope. What remains spills
     * into the deep sea.
     */
    private fun depositOffshore(
        grid: Grid, elevation: FloatArray, sediment: FloatArray, toSea: FloatArray,
        isOutlet: (Int) -> Boolean, stamp: IntArray, lastTag: Int,
    ): Int {
        val cellArea = grid.cellAreaM2.toFloat()
        // The coast is cut into tiles twice as wide as sediment ever spreads, and tiles of one colour
        // of a four-colour chessboard are filled at the same time on separate cores: their spreads
        // cannot reach each other's sea floor.
        val tileCells = max(1, (2 * MAX_SPREAD_KM / grid.cellKm).toInt() + 1)
        val tilesX = (grid.width + tileCells - 1) / tileCells; val tilesY = (grid.height + tileCells - 1) / tileCells
        val byTile = Array(tilesX * tilesY) { ArrayList<Int>() }
        for (s in 0 until grid.size) if (toSea[s] > 0f) byTile[(grid.y(s) / tileCells) * tilesX + grid.x(s) / tileCells] += s
        val heaps = ThreadLocal.withInitial { FloatHeap(1024) }
        val marks = ThreadLocal.withInitial { IntArray(grid.size) }
        val tags = ThreadLocal.withInitial { intArrayOf(0) }
        for (colour in 0 until 4) {
            val tiles = (0 until tilesX * tilesY).filter { ((it / tilesX) % 2) * 2 + (it % tilesX) % 2 == colour && byTile[it].isNotEmpty() }
            tiles.parallelStream().forEach { tile ->
                val heap = heaps.get(); val mine = marks.get(); val tag = tags.get()
                for (s in byTile[tile]) {
                    var volume = toSea[s]
                    val deltaKm = if (volume > DELTA_VOLUME) DELTA_KM else 0f
                    tag[0]++
                    val sx = grid.x(s); val sy = grid.y(s)
                    heap.push(0f, s); mine[s] = tag[0]
                    while (heap.size > 0 && volume > 0f) {
                        val km = heap.peekKey()
                        val i = heap.pop()
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
                        }
                        grid.forNeighbours(i) { j, _ ->
                            if (mine[j] != tag[0] && isOutlet(j)) {
                                mine[j] = tag[0]
                                val dx = (grid.x(j) - sx).toDouble(); val dy = (grid.y(j) - sy).toDouble()
                                val d = (kotlin.math.sqrt(dx * dx + dy * dy) * grid.cellKm).toFloat()
                                if (d < MAX_SPREAD_KM) heap.push(d, j)
                            }
                        }
                    }
                    while (heap.size > 0) heap.pop()
                }
            }
        }
        return lastTag
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
