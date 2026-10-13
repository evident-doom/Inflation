package inflation.world

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * The ground as something to cross, hide in, look out from and hold.
 *
 * At 1 km resolution the elevation model smooths away what makes ground hard going: boulder
 * fields, lava flows, karst, gullies. So ruggedness combines the slope and local relief that the
 * grid does resolve with a surface roughness that comes from the rock and how deeply soil and
 * sediment bury it. Young basalt lava fields (the harrat of the Levant and Arabia) and bare karst
 * are the roughest; alluvial plains the smoothest.
 *
 * What a hill or ridge looks out over: [viewKm] is a ray march over the terrain in 16 directions out
 * to [VIEW_MAX_KM], with the earth's curvature, for an observer and target each about 2 m tall.
 *
 * All fields are computed once per world; the hexes summarise them.
 */
class Terrain(private val world: World) {
    private val grid = world.grid
    private val n = grid.size
    private val elev = world.elevation

    /** Steepest downhill gradient, m/m. */
    val slope = FloatArray(n)
    /** Elevation range within about 2 km, m. */
    val relief = FloatArray(n)
    /** Surface roughness the grid does not resolve: boulders, lava, karst, 0..1. */
    val roughness = FloatArray(n)
    /** Overall ruggedness, 0 (flat, smooth) to 1 (cliffs, broken lava, deep gullies). */
    val ruggedness = FloatArray(n)
    /** Walking effort relative to flat, open, dry ground, ignoring plants and snow; infinite on open water. */
    val baseCost = FloatArray(n)
    /** Travel cost without crossing water, and how fordable each river cell is (see [crossingCost]). */
    internal val landCost = FloatArray(n)
    private val ford = FloatArray(n) { 1f }
    /**
     * Cliffs the grid does not resolve as slope: escarpments, and the walls of gorges a river has cut
     * into a plain. A cell is a cliff where the ground drops sharply to a neighbour while the land
     * around is otherwise gentle; streams cut gaps through them.
     */
    val cliff = BooleanArray(n)
    /** Height above the mean ground within about 5 km, m: hills, spurs and knolls are positive. */
    val prominence = FloatArray(n)
    /** Mean distance one can see over the bare terrain, km (out to [VIEW_MAX_KM]). */
    val viewKm = FloatArray(n)
    /** Bare-terrain area in view within [VIEW_MAX_KM], km². */
    val viewAreaKm2 = FloatArray(n)
    /**
     * How defensible a place is, 0..1: height over the surroundings, broken ground, and approaches
     * cut by water, marsh or steep slopes. For settlements and refuges in later stages.
     */
    val defensibility = FloatArray(n)

    private fun isWater(i: Int) = world.isSea[i] || world.water.isLake(i)

    init {
        val cell = grid.cellM.toFloat()
        for (i in 0 until n) {
            if (isWater(i)) { baseCost[i] = Float.POSITIVE_INFINITY; continue }
            slope[i] = Biomes.slope(grid, elev, i)
            var lo = elev[i]; var hi = elev[i]
            val r = max(1, (2 / grid.cellKm).toInt())
            val x0 = grid.x(i); val y0 = grid.y(i)
            for (dy in -r..r) for (dx in -r..r) {
                val x = x0 + dx; val y = y0 + dy
                if (!grid.inBounds(x, y)) continue
                val e = max(0f, elev[grid.index(x, y)])
                lo = min(lo, e); hi = max(hi, e)
            }
            relief[i] = hi - lo
            // Bedrock shows through where sediment is thin; a few metres of soil and alluvium bury it.
            val exposed = exp(-world.geology.sediment[i] / 3f)
            roughness[i] = ROCK_ROUGHNESS.getValue(world.geology.rock[i]) * exposed + 0.2f * min(1f, slope[i] / 0.5f)
            ruggedness[i] = min(1f, 0.5f * min(1f, slope[i] / 0.35f) + 0.25f * min(1f, relief[i] / 600f) + 0.5f * roughness[i])
            baseCost[i] = walkingCost(slope[i]) * (1f + roughness[i]) * wetCost(i) * riverCost(i)
            landCost[i] = baseCost[i] / riverCost(i)
            ford[i] = fordFactor(i)
        }
        findCliffs()
        val mean = blur(grid, FloatArray(n) { max(0f, elev[it]) }, max(1, (5 / grid.cellKm).toInt()))
        for (i in 0 until n) if (!isWater(i)) prominence[i] = elev[i] - mean[i]
        viewshed(cell)
        for (i in 0 until n) if (!isWater(i)) defensibility[i] = defence(i)
    }

    private fun findCliffs() {
        // Escarpments: the top of each fault block, where the ground drops to the foot of the next
        // block, along the whole line of the scarp; streams cut gaps through it.
        val noise = Noise(world.config.seed)
        val field = FloatArray(n); val faulted = FloatArray(n)
        for (i in 0 until n) {
            val (f, through) = World.scarpField(noise, grid.cellKm, grid.x(i).toDouble(), grid.y(i).toDouble())
            faulted[i] = f.toFloat(); field[i] = through.toFloat()
        }
        for (i in 0 until n) {
            if (isWater(i) || world.water.discharge[i] > CLIFF_GAP_M3S || faulted[i] < SCARP_FAULTED || field[i] < 0.7f || elev[i] > 1500f) continue
            val x0 = grid.x(i); val y0 = grid.y(i)
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val x = x0 + dx; val y = y0 + dy
                if (!grid.inBounds(x, y)) continue
                val j = grid.index(x, y)
                // Across the line: the neighbour lies at the foot of the next block, well below.
                if (field[j] < 0.3f && elev[i] - elev[j] > CLIFF_M * grid.cellKm.toFloat()) { cliff[i] = true; break }
            }
        }
        // Gorges: a river cut well below the plain on both banks; its walls are cliffs.
        for (i in 0 until n) {
            if (world.riverClass(i) < RiverClass.STREAM) continue
            val x0 = grid.x(i); val y0 = grid.y(i)
            var rim = Float.MAX_VALUE
            for (dy in -2..2) for (dx in -2..2) {
                if (kotlin.math.abs(dx) < 2 && kotlin.math.abs(dy) < 2) continue
                val x = x0 + dx; val y = y0 + dy
                if (!grid.inBounds(x, y)) continue
                rim = min(rim, elev[grid.index(x, y)])
            }
            if (rim - elev[i] < GORGE_M) continue
            for (dy in -1..1) for (dx in -1..1) {
                val x = x0 + dx; val y = y0 + dy
                if (!grid.inBounds(x, y)) continue
                val j = grid.index(x, y)
                if (j != i && !isWater(j) && world.water.discharge[j] <= CLIFF_GAP_M3S && elev[j] - elev[i] > 0.5f * GORGE_M) cliff[j] = true
            }
        }
    }

    /**
     * Tobler's hiking function, relative to flat ground: walking speed falls off exponentially
     * with gradient. Gradients here are averaged over a kilometre, so they understate the steepest
     * pitches a route must take; the roughness term covers some of that.
     */
    private fun walkingCost(s: Float) = exp(3.5f * s)

    private fun wetCost(i: Int) = when (world.biome[i]) {
        Biome.MARSH, Biome.SALT_MARSH -> 3f
        Biome.SWAMP_FOREST, Biome.MANGROVE -> 5f
        Biome.BOG -> 4f
        else -> if (world.floodplain.isFloodplain[i]) 1.2f else 1f
    }

    /**
     * How the channel at [i] lies for crossing, as a factor on its depth: where a river runs wide,
     * slow and braided over gravel on gentle ground it is shallow (fords, about 0.6); where it is
     * confined between steep banks in a valley it runs deep and fast (about 1.3).
     */
    private fun fordFactor(i: Int): Float {
        if (world.riverClass(i) == RiverClass.NONE) return 1f
        val r = world.water.drainage.receiver[i]
        val gradient = if (r == i) 0f else (world.elevation[i] - world.elevation[r]) / world.water.drainage.receiverDistM[i]
        return when {
            gradient < 0.002f && world.floodplain.isFloodplain[i] -> 0.6f
            gradient < 0.004f -> 0.8f
            gradient > 0.02f || slope[i] > 0.15f -> 1.3f
            else -> 1f
        }
    }

    /**
     * Cost of getting across the river at [i] with [dischargeNow] m³/s in it, as a factor: knee
     * deep is a wade, waist deep slow and risky, beyond chest deep a swim most animals refuse; a
     * stream in spate stops herds for days, and in the dry season the same river has fords.
     */
    /** Depth of the river at [i] where it is easiest to cross, m, with [discharge] m³/s in it (0 where there is no river). */
    fun crossingDepthM(i: Int, discharge: Float = world.water.discharge[i]): Float =
        if (world.riverClass(i) == RiverClass.NONE || discharge < 0.05f) 0f else Channel.depthM(discharge) * ford[i]

    fun crossingCost(i: Int, dischargeNow: Float): Float {
        if (world.riverClass(i) == RiverClass.NONE || dischargeNow < 0.05f) return 1f
        val depth = Channel.depthM(dischargeNow) * ford[i]
        fun ramp(x: Float, a: Float, b: Float) = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return 1f + 4f * ramp(depth, 0.3f, 1f) + 20f * ramp(depth, 1.2f, 2.5f)
    }

    /** Crossing water: creeks are stepped over, streams waded, rivers swum or forded, great rivers need a boat. */
    private fun riverCost(i: Int) = when (world.riverClass(i)) {
        RiverClass.NONE, RiverClass.CREEK -> 1f
        RiverClass.STREAM -> 1.5f
        RiverClass.RIVER -> 4f
        RiverClass.GREAT_RIVER -> 15f
    }

    private fun viewshed(cellM: Float) {
        val steps = (VIEW_MAX_KM / grid.cellKm).toInt()
        val dirs = 16
        val dx = DoubleArray(dirs) { kotlin.math.cos(2 * Math.PI * it / dirs) }
        val dy = DoubleArray(dirs) { kotlin.math.sin(2 * Math.PI * it / dirs) }
        val earthR = 6_371_000.0
        for (i in 0 until n) {
            if (isWater(i)) continue
            val eye = max(0f, elev[i]) + EYE_M
            var reachSum = 0.0; var area = 0.0
            for (d in 0 until dirs) {
                var maxTan = Double.NEGATIVE_INFINITY
                var reach = 0.0
                var x = grid.x(i) + 0.5; var y = grid.y(i) + 0.5
                for (k in 1..steps) {
                    x += dx[d]; y += dy[d]
                    val xi = x.toInt(); val yi = y.toInt()
                    if (x < 0 || y < 0 || !grid.inBounds(xi, yi)) break
                    val j = grid.index(xi, yi)
                    val dist = k * cellM.toDouble()
                    val drop = dist * dist / (2 * earthR) * 0.87 // curvature less refraction
                    val ground = max(0f, elev[j]) - drop
                    val target = (ground + EYE_M - eye) / dist
                    if (target >= maxTan) { reach = k * grid.cellKm; area += 2 * Math.PI * k * grid.cellKm * grid.cellKm / dirs }
                    maxTan = max(maxTan, (ground - eye) / dist)
                }
                reachSum += reach
            }
            viewKm[i] = (reachSum / dirs).toFloat()
            viewAreaKm2[i] = area.toFloat()
        }
    }

    private fun defence(i: Int): Float {
        // Approaches: of 16 directions at about 2 km, how many are cut by water or a steep climb.
        var cut = 0
        val r = max(1.0, 2.0 / grid.cellKm)
        for (d in 0 until 16) {
            val a = 2 * Math.PI * d / 16
            var blocked = false
            for (k in 1..r.toInt()) {
                val x = (grid.x(i) + 0.5 + kotlin.math.cos(a) * k).toInt(); val y = (grid.y(i) + 0.5 + kotlin.math.sin(a) * k).toInt()
                if (!grid.inBounds(x, y)) { blocked = true; break }
                val j = grid.index(x, y)
                if (baseCost[j] >= 4f || (elev[i] - elev[j]) / (k * grid.cellM) > 0.15) { blocked = true; break }
            }
            if (blocked) cut++
        }
        val height = (prominence[i] / 150f).coerceIn(0f, 1f)
        return min(1f, 0.4f * height + 0.3f * ruggedness[i] + 0.5f * cut / 16f)
    }

    companion object {
        /** Cliffs: least drop over a km across an escarpment; streams this big cut a gap. */
        const val CLIFF_M = 50f
        const val CLIFF_GAP_M3S = 0.5f
        /** How strongly faulted a place must be for its scarps to stand as cliffs. */
        const val SCARP_FAULTED = 0.3f
        /** How far below the plain on both banks a river must have cut for a gorge, m. */
        const val GORGE_M = 120f
        const val VIEW_MAX_KM = 30f
        const val EYE_M = 2f

        val ROCK_ROUGHNESS = mapOf(
            Rock.BASALT to 0.8f,      // lava fields
            Rock.VOLCANIC to 0.5f,    // tuff, scoria, younger flows
            Rock.LIMESTONE to 0.55f,  // karst pavement and sinkholes
            Rock.GRANITE to 0.4f,     // tors and boulder fields
            Rock.SANDSTONE to 0.35f,  // ledges and slot canyons
            Rock.METAMORPHIC to 0.3f,
            Rock.SHALE to 0.1f,       // weathers to smooth slopes
            Rock.EVAPORITE to 0.05f,
            Rock.ALLUVIUM to 0f,
        )

    }
}
