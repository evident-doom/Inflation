package inflation.ui

import inflation.ecology.HexSim
import inflation.world.Biome
import inflation.world.Noise
import inflation.world.Place
import inflation.world.RiverClass
import inflation.world.get
import java.awt.image.BufferedImage
import kotlin.math.exp
import kotlin.math.min

/**
 * The map's ground as the plants stand now, not as world-gen first drew it ([HexPicture.render], fixed by the climate's
 * labels): each 1 km cell of the vegetated country coloured from its place's live plants: bare soil, herbs (green as far as
 * they are in leaf, straw-coloured as they dry), shrubs, and the trees' crowns. The model keeps one plant
 * state for a place, so where its trees stand inside it is drawn, not modelled: they are put on the place's cells that would
 * hold trees first ([treeFirst]: floodplain and stream sides and wetter ground, broken into patches by fixed noise), as
 * closed wood on as many cells as the crowns fill, never on more than its glades ([inflation.world.HexPlants.glade]) leave
 * open; the rest shows its open ground. So a river place's trees line its streams instead of tinting the whole place. Water, ice, salt and wetlands keep world-gen's
 * colours; the relief shading, height tint and hex borders are [HexPicture]'s as before. Worked out on the simulation's thread
 * (call only while the world stands still) into a new picture each time, the whole map at [px] pixels a km, as the viewer's.
 */
internal class LiveGround(private val sim: HexSim, private val px: Int) {
    private val world = sim.world; private val hw = sim.hw; private val land = sim.land
    private val grid = world.grid
    /** Each cell's place, -1 for none (sea, lake). */
    private val placeOf = IntArray(grid.size) { -1 }.also { a -> for (p in hw.placeCells.indices) for (c in hw.placeCells[p]) a[c] = p }
    /** Each cell's fixed noise, 0..1, smooth over a few km. */
    private val noise = Noise(world.config.seed + 7177L).let { nz -> FloatArray(grid.size) { i -> ((nz.fbm(grid.x(i).toDouble(), grid.y(i).toDouble(), PATCH_KM / grid.cellKm, 3) + 1.0) / 2.0).toFloat() } }
    /**
     * Each cell's fixed standing for trees within its place, higher where trees would stand first: on the floodplain, by streams
     * and rivers and close to them, and where the climate is wetter; with [noise] so the wood
     * breaks into patches. Only its order within the place matters.
     */
    private val treeFirst: FloatArray = run {
        val near = riverDistanceKm()
        FloatArray(grid.size) { i ->
            val wet = (world.climate.aridity(i) / 1.5f).coerceIn(0f, 1f)
            val river = if (world.floodplain.isFloodplain[i]) 1f else exp(-near[i] / RIVER_REACH_KM)
            1.5f * river + 0.5f * wet + NOISE_WEIGHT * noise[i]
        }
    }
    /** Each place's cells' [treeFirst], sorted: the share of a place drawn wooded is a quantile of it. */
    private val sortedFirst = Array(hw.placeCells.size) { p -> FloatArray(hw.placeCells[p].size) { treeFirst[hw.placeCells[p][it]] }.also { it.sort() } }

    /** Each cell's distance to the nearest stream or river cell, km (4-neighbour steps, capped). */
    private fun riverDistanceKm(): FloatArray {
        val far = 1e9f
        val d = FloatArray(grid.size) { if (world.riverClass(it) >= RiverClass.STREAM) 0f else far }
        val q = java.util.ArrayDeque<Int>(); for (i in 0 until grid.size) if (d[i] == 0f) q += i
        val step = grid.cellKm.toFloat()
        while (q.isNotEmpty()) {
            val i = q.poll(); val x = grid.x(i); val y = grid.y(i); val nd = d[i] + step
            if (nd > MAX_RIVER_KM) continue
            for ((nx, ny) in arrayOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1)) {
                if (nx < 0 || ny < 0 || nx >= grid.width || ny >= grid.height) continue
                val j = grid.index(nx, ny)
                if (nd < d[j]) { d[j] = nd; q += j }
            }
        }
        return d
    }

    /** The ground as the plants stand now. */
    fun render(): BufferedImage {
        val plants = land.plants
        val n = hw.placeCells.size
        // Per place: the open ground's colour, how thick its wood is drawn on the cells it covers, and the [treeFirst] threshold
        // above which a cell is wooded.
        val open = IntArray(n); val wood = FloatArray(n); val threshold = FloatArray(n)
        for (p in 0 until n) {
            val pl = Place(p)
            val herbF = 1f - exp(-plants.herb[pl] / HERB_FULL_KG)
            val shrubF = 1f - exp(-plants.shrub[pl] / SHRUB_FULL_KG)
            val herb = mix(HERB_DRY, HERB_GREEN, plants.green[pl].coerceIn(0f, 1f))
            open[p] = mix(mix(BARE, herb, herbF), SHRUB, 0.7f * shrubF)
            // (Trees stand on at most the ground the glades leave, and gather there on the best of it: as thick as a closed wood
            // until they would cover all of it.)
            val crowns = plants.treeLight(pl); val glade = plants.glade[pl].coerceIn(0f, 1f)
            val wooded = if (crowns < 0.02f) 0f else min(1f - glade, crowns / CLOSED_CROWNS).coerceIn(0f, 1f)
            wood[p] = if (wooded <= 0f) 0f else (crowns / wooded).coerceIn(0f, 1f)
            val s = sortedFirst[p]
            threshold[p] = if (s.isEmpty() || wooded <= 0f) Float.POSITIVE_INFINITY else s[(((1f - wooded) * s.size).toInt()).coerceIn(0, s.size - 1)]
        }
        return HexPicture.render(world, sim.hex, 0.0, 0.0, world.config.widthKm.toDouble(), world.config.heightKm.toDouble(), px, rivers = false) { i ->
            val p = placeOf[i]; val b = world.biome[i]
            if (p < 0 || b !in LIVE) b.rgb else {
                val forest = if (b == Biome.CONIFER_FOREST) Biome.CONIFER_FOREST.rgb else Biome.TEMPERATE_FOREST.rgb
                if (treeFirst[i] < threshold[p]) open[p] else mix(open[p], forest, wood[p])
            }
        }
    }

    companion object {
        /** Herbs and shrubs at which they colour half their ground (kg/m², a 1 - e^-x scale). */
        const val HERB_FULL_KG = 0.1f
        const val SHRUB_FULL_KG = 0.6f
        /** About how wide a patch of wood or open ground is drawn, km. */
        const val PATCH_KM = 6.0
        /** How much the patch noise counts against the place's lie of the land in where trees stand first. */
        const val NOISE_WEIGHT = 0.8f
        /** How far from a stream the pull of its water on trees falls to 1/e, km; beyond [MAX_RIVER_KM] none. */
        const val RIVER_REACH_KM = 1.5f
        const val MAX_RIVER_KM = 8f
        /** Crown share of a closed wood: a place's trees are drawn this thick on as few cells as hold them. */
        const val CLOSED_CROWNS = 0.7f
        const val BARE = 0xcdb98a
        const val HERB_GREEN = 0x93ad55
        const val HERB_DRY = 0xc4b273
        const val SHRUB = 0x7b8442
        /** The kinds of country drawn from the live plants; the rest (water, ice, salt, wetlands) keep world-gen's colours. */
        val LIVE = setOf(Biome.ALPINE_MEADOW, Biome.CONIFER_FOREST, Biome.TEMPERATE_FOREST, Biome.OPEN_WOODLAND, Biome.MEDITERRANEAN_WOODLAND,
            Biome.STEPPE, Biome.SHRUBLAND, Biome.SEMI_DESERT, Biome.DESERT, Biome.FLOODPLAIN_FOREST)

        /** Colour [a] blended toward [b] by [t] (0..1). */
        fun mix(a: Int, b: Int, t: Float): Int {
            val u = t.coerceIn(0f, 1f)
            fun ch(s: Int) = (((a shr s) and 255) * (1 - u) + ((b shr s) and 255) * u).toInt().coerceIn(0, 255)
            return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}
