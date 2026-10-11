package inflation.world

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class Plate(val x: Double, val y: Double, val vx: Double, val vy: Double, val continental: Boolean)

/**
 * Initial relief from a simplified plate model. Cells belong to the nearest plate seed
 * (after domain warping, so boundaries are irregular). Where plates converge the crust is
 * thickened into mountain belts; where they diverge a rift valley opens.
 */
class Tectonics(
    val plates: List<Plate>,
    val plateId: IntArray,
    /** The plate on the other side of the nearest boundary. */
    val neighbourPlateId: IntArray,
    /** Signed convergence at the nearest boundary, m/yr; positive = collision, negative = rifting. */
    val convergence: FloatArray,
    /** Distance to the nearest plate boundary, km. */
    val boundaryDistKm: FloatArray,
    /** Rock uplift rate used by erosion, m/yr. */
    val uplift: FloatArray,
    /** Initial elevation before erosion, m. */
    val elevation: FloatArray,
) {
    companion object {
        fun generate(grid: Grid, cfg: WorldConfig, noise: Noise): Tectonics {
            val rnd = Random(cfg.seed * 7919 + 13)
            val w = grid.width.toDouble(); val h = grid.height.toDouble()
            val plates = List(cfg.plates) {
                val px = rnd.nextDouble() * w
                val py = rnd.nextDouble() * h
                val angle = rnd.nextDouble() * 2 * Math.PI
                val speed = 0.01 + rnd.nextDouble() * 0.04 // m/yr, 1–5 cm/yr
                val oceanic = px < cfg.westOceanFraction * w * 1.4
                Plate(px, py, cos(angle) * speed, sin(angle) * speed, continental = !oceanic)
            }

            val n = grid.size
            val plateId = IntArray(n)
            val neighbourId = IntArray(n)
            val convergence = FloatArray(n)
            val boundaryDist = FloatArray(n)
            val uplift = FloatArray(n)
            val elevation = FloatArray(n)
            val reliefField = FloatArray(n)
            val warp = 90.0 / grid.cellKm

            for (i in 0 until n) {
                val x = grid.x(i).toDouble(); val y = grid.y(i).toDouble()
                val wx = x + warp * noise.fbm(x, y, 400.0 / grid.cellKm, 4)
                val wy = y + warp * noise.fbm(x + 913.0, y - 377.0, 400.0 / grid.cellKm, 4)
                var best = 0; var second = 0
                var d1 = Double.MAX_VALUE; var d2 = Double.MAX_VALUE
                for ((k, p) in plates.withIndex()) {
                    val dx = wx - p.x; val dy = wy - p.y
                    val d = dx * dx + dy * dy
                    if (d < d1) { d2 = d1; second = best; d1 = d; best = k } else if (d < d2) { d2 = d; second = k }
                }
                plateId[i] = best
                neighbourId[i] = second
                val a = plates[best]; val b = plates[second]
                // Distance to the Voronoi edge between the two nearest seeds.
                val sx = b.x - a.x; val sy = b.y - a.y
                val sLen = sqrt(sx * sx + sy * sy).coerceAtLeast(1e-9)
                val distCells = ((sqrt(d2) - sqrt(d1)) / 2).coerceAtLeast(0.0)
                val km = distCells * grid.cellKm
                boundaryDist[i] = km.toFloat()
                // Positive when a moves towards b, i.e. the plates collide.
                val conv = ((a.vx - b.vx) * sx + (a.vy - b.vy) * sy) / sLen
                convergence[i] = conv.toFloat()

                val base = if (a.continental) 250.0 else -2800.0
                val texture = 450.0 * noise.fbm(x, y, 250.0 / grid.cellKm, 6)
                val beltWidth = if (a.continental && b.continental) 120.0 else 70.0
                val belt = exp(-km / beltWidth)
                var u = 0.0; var relief = 0.0
                if (conv > 0) {
                    // Collision: uplift scales with closing speed. 4 cm/yr closure gives ~1 mm/yr uplift.
                    u = conv * 0.025 * belt
                    relief = conv * 25_000.0 * belt * (0.5 + noise.ridged(x, y, 120.0 / grid.cellKm, 5))
                } else {
                    // Rifting: a narrow subsiding valley along the boundary.
                    val rift = exp(-km / 25.0)
                    relief = conv * 40_000.0 * rift
                }
                uplift[i] = if (a.continental) u.toFloat() else 0f
                reliefField[i] = (if (a.continental) relief else relief * 0.3).toFloat()
                elevation[i] = (base + texture).toFloat()
            }
            // The nearest-boundary rule switches abruptly near plate corners; real belts are smooth.
            val r = (30 / grid.cellKm).toInt().coerceAtLeast(1)
            val smoothRelief = blur(grid, blur(grid, reliefField, r), r)
            val smoothUplift = blur(grid, blur(grid, uplift, r), r)
            for (i in 0 until n) {
                elevation[i] += smoothRelief[i]
                uplift[i] = if (plates[plateId[i]].continental) smoothUplift[i] else 0f
            }
            // Soften the step between oceanic and continental crust into a shelf and slope.
            val smooth = blur(grid, elevation, (25 / grid.cellKm).toInt().coerceAtLeast(1))
            for (i in 0 until n) {
                val keep = (boundaryDist[i] / 60f).coerceIn(0f, 1f)
                elevation[i] = smooth[i] * (1 - keep) + elevation[i] * keep
            }
            return Tectonics(plates, plateId, neighbourId, convergence, boundaryDist, uplift, elevation)
        }
    }
}
