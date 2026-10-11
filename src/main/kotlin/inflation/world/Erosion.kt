package inflation.world

import kotlin.math.pow
import kotlin.random.Random

/**
 * Fluvial erosion with the stream-power law, dh/dt = U - K A^m S, solved with the implicit,
 * O(n) scheme of Braun & Willett (2013) with n = 1, plus linear hillslope diffusion.
 * Closed basins are treated as sediment-filled during long-term evolution; present-day lakes
 * come from young deformation applied afterwards.
 */
object Erosion {
    const val M = 0.5
    /** Base erodibility, 1/yr for m = 0.5 with area in m^2. Scaled per rock type. */
    const val K_BASE = 1.2e-6
    /** Hillslope diffusivity, m^2/yr. */
    const val DIFFUSIVITY = 0.5

    fun run(
        grid: Grid,
        elevation: FloatArray,
        uplift: FloatArray,
        erodibility: FloatArray,
        steps: Int,
        dtYears: Double,
        seed: Long,
        isOutlet: (Int) -> Boolean,
    ) {
        val n = grid.size
        val area = FloatArray(n) { grid.cellAreaM2.toFloat() }
        val lap = FloatArray(n)
        val rnd = Random(seed)
        repeat(steps) {
            val filled = priorityFlood(grid, elevation, isOutlet, routable = true)
            System.arraycopy(filled, 0, elevation, 0, n)
            val drainage = Drainage.build(grid, elevation, isOutlet, rnd)
            val acc = drainage.accumulate(area)
            for (i in drainage.stack) {
                val r = drainage.receiver[i]
                if (r == i) continue
                val f = erodibility[i] * K_BASE * dtYears * acc[i].toDouble().pow(M) / drainage.receiverDistM[i]
                val h = (elevation[i] + dtYears * uplift[i] + f * elevation[r]) / (1.0 + f)
                elevation[i] = h.toFloat()
            }
            diffuse(grid, elevation, lap, dtYears, isOutlet)
        }
    }

    private fun diffuse(grid: Grid, h: FloatArray, lap: FloatArray, dt: Double, isOutlet: (Int) -> Boolean) {
        val w = grid.width
        val c = (DIFFUSIVITY * dt / (grid.cellM * grid.cellM)).coerceAtMost(0.2).toFloat()
        for (y in 1 until grid.height - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            lap[i] = h[i - 1] + h[i + 1] + h[i - w] + h[i + w] - 4 * h[i]
        }
        for (i in 0 until grid.size) if (!isOutlet(i) && !grid.isEdge(i)) h[i] += c * lap[i]
    }
}
