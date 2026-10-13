package inflation.world

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class Plate(val x: Double, val y: Double, val vx: Double, val vy: Double, val continental: Boolean)

/**
 * Initial relief from a simplified plate model. Cells belong to the nearest plate seed
 * (after domain warping, so boundaries are irregular).
 *
 * - Continental crust stands near sea level. Its thickness varies, so parts of every continental
 *   plate are drowned into shallow shelf seas, which breaks land into islands and straits.
 * - Oceanic crust deepens with age, from about 2.5 km at a spreading ridge to 4.5 km far from it.
 * - Where plates converge, continents collide into mountain belts. Where an oceanic plate meets
 *   another plate it sinks beneath it, leaving a deep trench (volcanic arcs are added by [Volcanism]).
 * - Where plates diverge, continents rift and oceans spread.
 * - A band along every map edge is ocean floor, so the region is surrounded by sea.
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
    fun isContinental(i: Int): Boolean = plates[plateId[i]].continental

    /** True if the plate at [i] is sinking beneath its neighbour at the nearest boundary. */
    fun isSubducting(i: Int): Boolean = convergence[i] > 0.005f && subducts(plateId[i], neighbourPlateId[i])

    /** True if the plate at [i] overrides a sinking neighbour, which feeds a volcanic arc. */
    fun isOverriding(i: Int): Boolean = convergence[i] > 0.005f && subducts(neighbourPlateId[i], plateId[i])

    /** Oceanic crust sinks under continental crust; of two oceanic plates the higher-numbered one sinks. */
    private fun subducts(a: Int, b: Int): Boolean {
        if (a == b || plates[a].continental) return false
        return plates[b].continental || a > b
    }

    companion object {
        /** Randomness in which plates are continental: low keeps the continents together in one mass. */
        const val CONTINENT_SCATTER = 0.1
        /** Deepest subsidence of a continental rift valley before smoothing, m. */
        const val RIFT_DEPTH_M = 900.0
        /** Extra crustal freeboard at the middle of the map, falling to none at its edges, m. */
        const val CORE_FREEBOARD_M = 450.0
        fun generate(grid: Grid, cfg: WorldConfig, noise: Noise): Tectonics {
            val rnd = Random(cfg.seed * 7919 + 13)
            val w = grid.width.toDouble(); val h = grid.height.toDouble()
            val seeds = List(cfg.plateCount) {
                val px = rnd.nextDouble() * w
                val py = rnd.nextDouble() * h
                val angle = rnd.nextDouble() * 2 * Math.PI
                val speed = 0.01 + rnd.nextDouble() * 0.04 // m/yr, 1–5 cm/yr
                Triple(px, py, angle to speed)
            }
            // Plates seeded nearer the centre are likelier to be continental; the edges are ocean.
            // Distance is measured against the map's own proportions, so a tall map has land along its length.
            val continentalCount = (cfg.plateCount * cfg.continentalShare).toInt().coerceAtLeast(2)
            val continental = seeds.indices.sortedBy { k ->
                hypot((seeds[k].first - w / 2) / (0.5 * w), (seeds[k].second - h / 2) / (0.5 * h)) + CONTINENT_SCATTER * rnd.nextDouble()
            }.take(continentalCount).toSet()
            val plates = seeds.mapIndexed { k, (px, py, motion) ->
                val (angle, speed) = motion
                Plate(px, py, cos(angle) * speed, sin(angle) * speed, k in continental)
            }

            val n = grid.size
            val plateId = IntArray(n)
            val neighbourId = IntArray(n)
            val convergence = FloatArray(n)
            val boundaryDist = FloatArray(n)
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
                val sx = b.x - a.x; val sy = b.y - a.y
                val sLen = sqrt(sx * sx + sy * sy).coerceAtLeast(1e-9)
                // Distance to the Voronoi edge between the two nearest seeds.
                boundaryDist[i] = (((sqrt(d2) - sqrt(d1)) / 2).coerceAtLeast(0.0) * grid.cellKm).toFloat()
                // Positive when a moves towards b, i.e. the plates collide.
                convergence[i] = (((a.vx - b.vx) * sx + (a.vy - b.vy) * sy) / sLen).toFloat()
            }

            // Mid-ocean ridges: diverging boundaries between two oceanic plates.
            val ridge = BooleanArray(n) {
                convergence[it] < -0.003f && boundaryDist[it] < 2f * grid.cellKm &&
                    !plates[plateId[it]].continental && !plates[neighbourId[it]].continental
            }
            val ridgeDist = if (ridge.any { it }) Climate.distanceTransformKm(grid, ridge) else FloatArray(n) { 1e6f }

            val uplift = FloatArray(n)
            val elevation = FloatArray(n)
            val reliefField = FloatArray(n)
            for (i in 0 until n) {
                val x = grid.x(i).toDouble(); val y = grid.y(i).toDouble()
                val a = plates[plateId[i]]; val b = plates[neighbourId[i]]
                val km = boundaryDist[i].toDouble()
                val conv = convergence[i].toDouble()
                val texture = noise.fbm(x, y, 250.0 / grid.cellKm, 6)
                val base = if (a.continental) {
                    // Crustal thickness varies; thin parts are drowned shelves around -50 to -200 m. The
                    // continent's old core is thickest, so inland seas there are rarer than on its margins.
                    val core = 1 - hypot((x - w / 2) / (0.5 * w), (y - h / 2) / (0.5 * h))
                    val freeboard = 60.0 + CORE_FREEBOARD_M * core + 800.0 * noise.fbm(x + 5003, y - 7001, 220.0 / grid.cellKm, 4) + 300.0 * texture
                    if (freeboard > 0) freeboard else freeboard * 0.25 - 40.0
                } else {
                    // Half-space cooling: ridge crests at ~2.5 km depth, old floor near 4.5 km.
                    -(2500.0 + 2000.0 * (1 - exp(-ridgeDist[i] / 300.0))) + 150.0 * texture
                }
                val beltWidth = if (a.continental && b.continental) cfg.beltKm else 70.0
                val belt = exp(-km / beltWidth)
                var u = 0.0; var relief = 0.0
                if (conv > 0 && a.continental) {
                    // Collision or the overriding side of a subduction zone: uplift scales with closing speed.
                    u = conv * 0.025 * belt * cfg.upliftScale
                    relief = conv * 25_000.0 * belt * (0.5 + noise.ridged(x, y, 120.0 / grid.cellKm, 5)) * cfg.upliftScale
                } else if (conv < 0 && a.continental) {
                    // Rifting: a narrow subsiding valley along the boundary, a chain of lakes or at most a
                    // narrow gulf; a young rift does not split a continent with open ocean.
                    relief = max(conv * 40_000.0, -RIFT_DEPTH_M) * exp(-km / 25.0)
                }
                uplift[i] = u.toFloat()
                reliefField[i] = relief.toFloat()
                elevation[i] = base.toFloat()
            }
            // The nearest-boundary rule switches abruptly near plate corners; real belts are smooth.
            val r = (30 / grid.cellKm).toInt().coerceAtLeast(1)
            val smoothRelief = blur(grid, blur(grid, reliefField, r), r)
            val smoothUplift = blur(grid, blur(grid, uplift, r), r)
            for (i in 0 until n) {
                // Ridges: belts are not smooth domes but folds and thrust sheets, crest after crest.
                val ridges = if (cfg.ridgeKm > 0) {
                    val x = grid.x(i).toDouble(); val y = grid.y(i).toDouble()
                    (1 - cfg.ridgeShare + cfg.ridgeShare * 2 * noise.ridged(x + 911, y - 377, cfg.ridgeKm / grid.cellKm, 4)).toFloat()
                } else 1f
                elevation[i] += smoothRelief[i] * (if (smoothRelief[i] > 0f) ridges else 1f)
                uplift[i] = if (plates[plateId[i]].continental) smoothUplift[i] * ridges else 0f
            }
            // Soften the step between oceanic and continental crust into a continental slope.
            val smooth = blur(grid, elevation, (25 / grid.cellKm).toInt().coerceAtLeast(1))
            for (i in 0 until n) {
                val keep = (boundaryDist[i] / 60f).coerceIn(0f, 1f)
                elevation[i] = smooth[i] * (1 - keep) + elevation[i] * keep
            }

            val tect = Tectonics(plates, plateId, neighbourId, convergence, boundaryDist, uplift, elevation)
            for (i in 0 until n) {
                // Trenches: a narrow deep on the sinking plate along the boundary.
                if (tect.isSubducting(i)) elevation[i] -= (3000.0 * exp(-boundaryDist[i] / 20.0)).toFloat()
                // Every map edge drops to ocean floor.
                val x = grid.x(i); val y = grid.y(i)
                val edgeKm = min(min(x, grid.width - 1 - x), min(y, grid.height - 1 - y)) * grid.cellKm
                val t = (edgeKm / cfg.oceanMarginKm).coerceIn(0.0, 1.0)
                val keep = (t * t * (3 - 2 * t)).toFloat()
                elevation[i] = elevation[i] * keep + (-4200f) * (1 - keep)
                uplift[i] *= keep
            }
            return tect
        }
    }
}
