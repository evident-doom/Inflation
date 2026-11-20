package inflation.ui

import inflation.ecology.HexSim
import inflation.world.Biome
import inflation.world.Noise
import inflation.world.Place
import inflation.world.get
import java.awt.image.BufferedImage
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * The map's ground as the plants stand now, not as world-gen first drew it ([HexPicture.render], fixed by the climate's
 * labels): each 1 km cell of the vegetated country coloured from its place's live plants: bare soil, herbs (green as far as
 * they are in leaf, straw-coloured as they dry), shrubs, and the trees' crowns. A place's glades ([inflation.world.HexPlants.glade])
 * are drawn as open cells among its wooded ones: the cells of the place whose fixed noise is lowest, as many as its glades'
 * share of its ground, so they lie in patches a few km across and stay put while the glades open and close; the rest of the
 * place shows its wood as thick as its crowns are on the ground left to them. Water, ice, salt and wetlands keep world-gen's
 * colours; the relief shading, height tint and hex borders are [HexPicture]'s as before. Worked out on the simulation's thread
 * (call only while the world stands still) into a new picture each time, the whole map at [px] pixels a km, as the viewer's.
 */
internal class LiveGround(private val sim: HexSim, private val px: Int) {
    private val world = sim.world; private val hw = sim.hw; private val land = sim.land
    private val grid = world.grid
    /** Each cell's place, -1 for none (sea, lake). */
    private val placeOf = IntArray(grid.size) { -1 }.also { a -> for (p in hw.placeCells.indices) for (c in hw.placeCells[p]) a[c] = p }
    /** Each cell's fixed noise, 0..1, smooth over a few km: which of a place's cells open first as glades. */
    private val noise = Noise(world.config.seed + 7177L).let { nz -> FloatArray(grid.size) { i -> ((nz.fbm(grid.x(i).toDouble(), (i / grid.width).toDouble(), GLADE_PATCH_KM / grid.cellKm, 3) + 1.0) / 2.0).toFloat() } }
    /** Each place's cells' noise, sorted: a glade share's threshold is its quantile there. */
    private val sortedNoise = Array(hw.placeCells.size) { p -> FloatArray(hw.placeCells[p].size) { noise[hw.placeCells[p][it]] }.also { it.sort() } }

    /** The ground as the plants stand now. */
    fun render(): BufferedImage {
        val plants = land.plants
        val n = hw.placeCells.size
        // Per place: the open ground's colour, its trees' colour weight on the wooded part, and its glades' noise threshold.
        val open = IntArray(n); val wood = FloatArray(n); val threshold = FloatArray(n)
        for (p in 0 until n) {
            val pl = Place(p)
            val herbF = 1f - exp(-plants.herb[pl] / HERB_FULL_KG)
            val shrubF = 1f - exp(-plants.shrub[pl] / SHRUB_FULL_KG)
            val herb = mix(HERB_DRY, HERB_GREEN, plants.green[pl].coerceIn(0f, 1f))
            open[p] = mix(mix(BARE, herb, herbF), SHRUB, 0.7f * shrubF)
            val crowns = plants.treeLight(pl); val glade = plants.glade[pl].coerceIn(0f, 1f)
            wood[p] = if (crowns < 0.02f) 0f else (crowns / max(0.05f, 1f - glade)).coerceIn(0f, 1f)
            val s = sortedNoise[p]
            threshold[p] = if (s.isEmpty() || glade <= 0f) -1f else s[min(s.size - 1, (glade * s.size).toInt())]
        }
        return HexPicture.render(world, sim.hex, 0.0, 0.0, world.config.widthKm.toDouble(), world.config.heightKm.toDouble(), px, rivers = false) { i ->
            val p = placeOf[i]; val b = world.biome[i]
            if (p < 0 || b !in LIVE) b.rgb else {
                val forest = if (b == Biome.CONIFER_FOREST) Biome.CONIFER_FOREST.rgb else Biome.TEMPERATE_FOREST.rgb
                if (noise[i] < threshold[p]) open[p] else mix(open[p], forest, wood[p])
            }
        }
    }

    companion object {
        /** Herbs and shrubs at which they colour half their ground (kg/m², a 1 - e^-x scale). */
        const val HERB_FULL_KG = 0.1f
        const val SHRUB_FULL_KG = 0.6f
        /** About how wide a patch of glades is drawn, km. */
        const val GLADE_PATCH_KM = 3.0
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
