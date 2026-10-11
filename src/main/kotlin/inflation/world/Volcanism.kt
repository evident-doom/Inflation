package inflation.world

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

enum class VolcanoKind {
    /** Above a subduction zone on a continent: steep stratovolcanoes, explosive, obsidian. */
    CONTINENTAL_ARC,
    /** Above a subduction zone between two oceanic plates: a chain of volcanic islands. */
    ISLAND_ARC,
    /** Over a mantle plume: broad basalt shields, oldest at the far end of the chain. */
    HOTSPOT,
    /** In a continental rift: scattered cones and lava fields. */
    RIFT,
}

/**
 * One volcanic edifice. [heightM] is its relief above the surrounding floor, [radiusKm] its base.
 * [eruptionsPerCentury] is zero for extinct volcanoes.
 */
class Volcano(
    val x: Int,
    val y: Int,
    val kind: VolcanoKind,
    val heightM: Float,
    val radiusKm: Float,
    val ageMyr: Float,
    val eruptionsPerCentury: Float,
) {
    val active: Boolean get() = eruptionsPerCentury > 0f
    /** Young edifices are built after erosion; older ones are worn down with the land around them. */
    val young: Boolean get() = ageMyr < 0.8f
}

/** An eruption during the simulation. VEI is the volcanic explosivity index, 0..6. */
class Eruption(val date: Date, val volcano: Volcano, val vei: Int)

object Volcanism {
    fun generate(grid: Grid, cfg: WorldConfig, tect: Tectonics): List<Volcano> {
        val rnd = Random(cfg.seed * 104_729 + 3)
        val out = ArrayList<Volcano>()
        fun farFromOthers(x: Int, y: Int, km: Double) = out.none { hypot((it.x - x).toDouble(), (it.y - y).toDouble()) * grid.cellKm < km }

        // Arcs: 90–160 km behind the trench on the overriding plate, spaced 40–60 km apart.
        repeat(grid.size / 8) {
            val i = rnd.nextInt(grid.size)
            val d = tect.boundaryDistKm[i]
            if (!tect.isOverriding(i) || d !in 90f..160f) return@repeat
            val x = grid.x(i); val y = grid.y(i)
            if (!farFromOthers(x, y, 40.0 + 20 * rnd.nextDouble())) return@repeat
            val continental = tect.isContinental(i)
            val age = (rnd.nextDouble().pow(2) * 4).toFloat()
            out += Volcano(
                x, y,
                if (continental) VolcanoKind.CONTINENTAL_ARC else VolcanoKind.ISLAND_ARC,
                heightM = (if (continental) 1500f else 4300f) + 1700f * rnd.nextFloat(),
                radiusKm = (if (continental) 12f else 18f) + 8f * rnd.nextFloat(),
                ageMyr = age,
                eruptionsPerCentury = activity(age, rnd, 0.5f),
            )
        }

        // Hotspot chains: the plate drifts over a fixed plume, so older volcanoes trail behind.
        repeat(cfg.hotspots) {
            val hx = (0.15 + 0.7 * rnd.nextDouble()) * grid.width
            val hy = (0.15 + 0.7 * rnd.nextDouble()) * grid.height
            val plate = tect.plates[tect.plateId[grid.index(hx.toInt(), hy.toInt())]]
            val speedKmPerMyr = hypot(plate.vx, plate.vy) * 1000.0
            val spacingMyr = (45.0 / speedKmPerMyr).coerceIn(0.5, 3.0)
            var age = 0.0
            while (age < 12) {
                val x = (hx - plate.vx * 1000.0 * age / grid.cellKm).toInt()
                val y = (hy - plate.vy * 1000.0 * age / grid.cellKm).toInt()
                if (!grid.inBounds(x, y)) break
                if (farFromOthers(x, y, 25.0)) {
                    // Shields reach ~5 km above the floor when active, then subside and erode.
                    val height = 6200f * exp(-age / 5.0).toFloat() - 300f * age.toFloat()
                    if (height > 500f) out += Volcano(
                        x, y, VolcanoKind.HOTSPOT, height, 35f - 10f * exp(-age / 3.0).toFloat(), age.toFloat(),
                        if (age < 0.3) 3f + 6f * rnd.nextFloat() else 0f,
                    )
                }
                age += spacingMyr * (0.7 + 0.6 * rnd.nextDouble())
            }
        }

        // Rifts: a few cones along diverging continental boundaries.
        var rifts = 0
        repeat(grid.size / 20) {
            if (rifts >= 8) return@repeat
            val i = rnd.nextInt(grid.size)
            if (!tect.isContinental(i) || tect.convergence[i] > -0.008f || tect.boundaryDistKm[i] > 15f) return@repeat
            val x = grid.x(i); val y = grid.y(i)
            if (!farFromOthers(x, y, 60.0)) return@repeat
            val age = (rnd.nextDouble() * 2).toFloat()
            out += Volcano(x, y, VolcanoKind.RIFT, 600f + 900f * rnd.nextFloat(), 8f + 6f * rnd.nextFloat(), age, activity(age, rnd, 0.3f))
            rifts++
        }
        return out
    }

    /** Volcanoes younger than ~0.3 Myr may still erupt, a few times per century at most. */
    private fun activity(ageMyr: Float, rnd: Random, scale: Float): Float =
        if (ageMyr < 0.3f && rnd.nextFloat() < 0.8f) scale * (0.5f + 4f * rnd.nextFloat()) else 0f

    /** Adds edifices to the terrain: cones with a summit crater where the volcano is active. */
    fun build(grid: Grid, elevation: FloatArray, volcanoes: List<Volcano>, young: Boolean, noise: Noise) {
        for (v in volcanoes) {
            if (v.young != young) continue
            val rCells = (v.radiusKm * 1.4 / grid.cellKm).toInt()
            for (dy in -rCells..rCells) for (dx in -rCells..rCells) {
                val x = v.x + dx; val y = v.y + dy
                if (!grid.inBounds(x, y)) continue
                // Lava flows and gullies make the outline and flanks irregular.
                val lobes = 1 + 0.3 * noise.fbm(x + 71.0 * v.x, y - 53.0 * v.y, v.radiusKm / grid.cellKm / 2, 3)
                val r = hypot(dx.toDouble(), dy.toDouble()) * grid.cellKm / v.radiusKm / lobes
                if (r >= 1) continue
                val gullies = 1 - 0.15 * r * noise.ridged(x.toDouble(), y.toDouble(), 3.0 / grid.cellKm, 3)
                val shape = gullies * if (v.kind == VolcanoKind.HOTSPOT) (1 - r).pow(1.2) else (1 - r).pow(2.2) // shields are broad
                val crater = if (v.active && r < 0.06) 0.04 * (1 - r / 0.06) else 0.0
                elevation[grid.index(x, y)] += (v.heightM * (shape - crater)).toFloat()
            }
        }
    }

    /** Volcanic rock under each edifice; obsidian near explosive vents. */
    fun applyRock(grid: Grid, geology: Geology, volcanoes: List<Volcano>, noise: Noise) {
        for (v in volcanoes) {
            val rCells = (v.radiusKm / grid.cellKm).toInt()
            for (dy in -rCells..rCells) for (dx in -rCells..rCells) {
                val x = v.x + dx; val y = v.y + dy
                if (!grid.inBounds(x, y)) continue
                val r = hypot(dx.toDouble(), dy.toDouble()) * grid.cellKm / v.radiusKm
                if (r >= 1) continue
                val i = grid.index(x, y)
                geology.rock[i] = if (v.kind == VolcanoKind.HOTSPOT) Rock.BASALT else Rock.VOLCANIC
                val explosive = v.kind == VolcanoKind.CONTINENTAL_ARC || v.kind == VolcanoKind.RIFT
                if (explosive && r < 0.7 && noise.fbm(x + 333.0, y - 999.0, 6.0 / grid.cellKm, 2) > 0.2) {
                    geology.minerals[i] = geology.minerals[i] or Mineral.OBSIDIAN
                }
            }
        }
    }
}

/**
 * Eruptions over simulated time. Each active volcano erupts at random at its long-term rate;
 * the explosivity follows the observed frequency of VEI classes, each step up being roughly
 * five times rarer. Ash falls mostly downwind (east) and thins with distance; it lies on the
 * ground for a few years before it is washed into the soil.
 */
class Eruptions(private val grid: Grid, private val volcanoes: List<Volcano>, seed: Long) {
    private val rnd = Random(seed * 7 + 5)
    /** Ash on the ground, mm. */
    val ash = FloatArray(grid.size)
    val history = ArrayList<Eruption>()

    fun step(date: Date, days: Int): List<Eruption> {
        val decay = exp(-days / 720.0).toFloat() // about two years' residence
        for (i in ash.indices) if (ash[i] > 0f) ash[i] = if (ash[i] < 0.05f) 0f else ash[i] * decay
        val now = ArrayList<Eruption>()
        for (v in volcanoes) {
            if (!v.active) continue
            val p = v.eruptionsPerCentury / 100.0 * days / Date.DAYS_PER_YEAR
            if (rnd.nextDouble() >= p) continue
            val vei = min(6, (-ln(max(rnd.nextDouble(), 1e-9)) / ln(5.0)).toInt() + if (v.kind == VolcanoKind.HOTSPOT) 0 else 1)
            val e = Eruption(date, v, vei)
            now += e; history += e
            fallAsh(v, vei)
        }
        return now
    }

    private fun fallAsh(v: Volcano, vei: Int) {
        if (vei < 2) return
        // Thickness 1 km downwind of the vent, and the e-folding distance, both grow with VEI.
        val nearMm = 50.0 * 2.5.pow(vei - 2)
        val lengthKm = 4.0 * 2.6.pow(vei - 2)
        val reach = (lengthKm * 5 / grid.cellKm).toInt()
        for (dy in -reach..reach) for (dx in -reach / 3..reach) {
            val x = v.x + dx; val y = v.y + dy
            if (!grid.inBounds(x, y)) continue
            val along = dx * grid.cellKm; val across = abs(dy) * grid.cellKm
            // A plume drifting east: thinner upwind and to the sides.
            val dist = sqrt((if (along < 0) along * 3 else along).pow(2) + (across * 2).pow(2))
            val mm = nearMm * exp(-dist / lengthKm)
            if (mm > 0.1) ash[grid.index(x, y)] += mm.toFloat()
        }
    }
}
