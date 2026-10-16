package inflation.ui

import inflation.world.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Writes PNG maps of a world for inspection. One pixel per cell. */
object Render {
    fun all(world: World, dir: File) {
        dir.mkdirs()
        write(dir, "relief.png", world, volcanoes = true) { i -> relief(world, i) }
        write(dir, "biomes.png", world) { i -> withRivers(world, i, shade(world, i, world.biome[i].rgb)) }
        write(dir, "geology.png", world) { i -> withRivers(world, i, shade(world, i, rockColour(world.geology.rock[i], world.isSea[i]))) }
        write(dir, "minerals.png", world) { i -> mineralColour(world, i) }
        write(dir, "precipitation.png", world) { i -> ramp(world.climate.annualPrecip[i], 0f, 1500f, DRY_WET) }
        write(dir, "temperature-coldest-month.png", world) { i -> ramp(world.climate.coldestMonthTemp(i), -20f, 30f, COLD_HOT) }
        write(dir, "temperature-warmest-month.png", world) { i -> ramp(world.climate.warmestMonthTemp(i), -20f, 40f, COLD_HOT) }
        write(dir, "soil.png", world) { i -> if (world.isSea[i]) 0x1f3f6f else ramp(world.soilFertility[i], 0f, 1f, DRY_WET) }
        write(dir, "sediment.png", world) { i -> shade(world, i, ramp(ln(1 + world.geology.sediment[i]), 0f, ln(1 + 500f), SEDIMENT)) }
        write(dir, "rivers.png", world) { i -> waterways(world, i) }
    }

    /** Terrain colour for any cell: bathymetry at sea, hypsometric tints on land, then rivers. */
    fun relief(world: World, i: Int): Int {
        val h = world.elevation[i]
        val base = when {
            world.isSea[i] -> when {
                h > -200 -> lerp(0x5f9fcf, 0x3a75b0, -h / 200f) // shelf
                else -> lerp(0x3a75b0, 0x0b1f3f, min(1f, (-h - 200) / 5000f))
            }
            world.water.isLake(i) -> if (world.water.lakeClosed[i]) 0x7fb5c8 else 0x3f7fbf
            h < 200 -> lerp(0x5f9f4f, 0x9fbf5f, h / 200f)
            h < 1000 -> lerp(0x9fbf5f, 0xbf9f5f, (h - 200) / 800f)
            h < 2500 -> lerp(0xbf9f5f, 0x8f7f6f, (h - 1000) / 1500f)
            else -> lerp(0x8f7f6f, 0xffffff, min(1f, (h - 2500) / 1500f))
        }
        return withRivers(world, i, shade(world, i, base))
    }

    /** Rivers by size, navigable reaches highlighted, floodplains tinted. */
    private fun waterways(world: World, i: Int): Int {
        if (world.isSea[i]) return 0x1f3f6f
        if (world.water.isLake(i)) return 0x3f7fbf
        val base = shade(world, i, if (world.floodplain.isFloodplain[i]) 0xcfe0b0 else 0xe8e4d8)
        return when {
            world.isNavigable(i) -> 0x0030c0
            world.isRiver(i) -> lerp(base, 0x5f9fdf, (ln(world.water.discharge[i] / SurfaceWater.RIVER_THRESHOLD + 1) / ln(50.0)).toFloat().coerceIn(0.35f, 1f))
            else -> base
        }
    }

    private fun write(dir: File, name: String, world: World, volcanoes: Boolean = false, colour: (Int) -> Int) {
        val img = image(world, colour)
        if (volcanoes) markVolcanoes(world, img, 1.0)
        ImageIO.write(img, "png", File(dir, name))
    }

    fun image(world: World, colour: (Int) -> Int): BufferedImage {
        val g = world.grid
        val img = BufferedImage(g.width, g.height, BufferedImage.TYPE_INT_RGB)
        val px = IntArray(g.size) { colour(it) }
        img.setRGB(0, 0, g.width, g.height, px, 0, g.width)
        return img
    }

    /** Triangles on volcanoes: red if active, dark grey if extinct. [scale] is pixels per cell. */
    fun markVolcanoes(world: World, img: BufferedImage, scale: Double, offsetX: Double = 0.0, offsetY: Double = 0.0) {
        val gfx = img.createGraphics()
        for (v in world.volcanoes) {
            if (world.elevation[world.grid.index(v.x, v.y)] < -200f) continue // deep seamounts
            val x = (v.x * scale + offsetX).toInt(); val y = (v.y * scale + offsetY).toInt()
            gfx.color = if (v.active) java.awt.Color(0xd02010) else java.awt.Color(0x404040)
            gfx.fillPolygon(intArrayOf(x - 5, x + 5, x), intArrayOf(y + 4, y + 4, y - 6), 3)
        }
        gfx.dispose()
    }

    fun withRivers(world: World, i: Int, rgb: Int): Int {
        if (!world.isRiver(i)) return rgb
        val q = world.water.discharge[i]
        val strength = (ln(q / SurfaceWater.RIVER_THRESHOLD + 1) / ln(500.0)).toFloat().coerceIn(0.25f, 1f)
        return lerp(rgb, 0x1f5fbf, strength)
    }

    /** Hillshade lit from the north-west. */
    fun shade(world: World, i: Int, rgb: Int): Int {
        if (world.isSea[i]) return rgb
        val g = world.grid
        val x = g.x(i); val y = g.y(i)
        val e = world.elevation
        val dzdx = (e[g.index(min(x + 1, g.width - 1), y)] - e[g.index(max(x - 1, 0), y)]) / (2 * g.cellM)
        val dzdy = (e[g.index(x, min(y + 1, g.height - 1))] - e[g.index(x, max(y - 1, 0))]) / (2 * g.cellM)
        val nx = -dzdx * 8; val ny = -dzdy * 8
        val len = sqrt(nx * nx + ny * ny + 1)
        val light = ((-nx - ny) / sqrt(2.0) * 0.6 + 1) / len
        return scale(rgb, light.toFloat().coerceIn(0.4f, 1.3f))
    }

    fun rockColour(r: Rock, sea: Boolean): Int = if (sea) 0x1f3f6f else when (r) {
        Rock.BASALT -> 0x3f3f4f
        Rock.VOLCANIC -> 0x8f3f3f
        Rock.GRANITE -> 0xcf9f9f
        Rock.METAMORPHIC -> 0x8f6f9f
        Rock.LIMESTONE -> 0xdfdfbf
        Rock.SANDSTONE -> 0xdfaf6f
        Rock.SHALE -> 0x7f8f7f
        Rock.ALLUVIUM -> 0x9fcf7f
        Rock.EVAPORITE -> 0xffffff
    }

    private fun mineralColour(world: World, i: Int): Int {
        if (world.isSea[i]) return 0x1f3f6f
        val m = world.geology.minerals[i]
        return when {
            m and Mineral.COPPER != 0 -> 0x2fbf9f
            m and Mineral.TIN != 0 -> 0x9f9fff
            m and Mineral.OBSIDIAN != 0 -> 0x000000
            m and Mineral.SALT != 0 -> 0xffffff
            m and Mineral.BITUMEN != 0 -> 0x5f3f1f
            m and Mineral.FLINT != 0 -> 0x7f7f7f
            m and Mineral.OCHRE != 0 -> 0xcf5f1f
            m and Mineral.CLAY != 0 -> 0xbf8f5f
            else -> 0xe0e0d0
        }
    }

    private val SEDIMENT = intArrayOf(0x606060, 0xc8b88a, 0xe8d8a0, 0xffffe0)
    val DRY_WET = intArrayOf(0xeed9a6, 0xbfbf5f, 0x3f9f3f, 0x1f5f9f)
    val COLD_HOT = intArrayOf(0x3f3fbf, 0x7fbfff, 0xffffbf, 0xff9f3f, 0xbf1f1f)

    fun ramp(v: Float, lo: Float, hi: Float, stops: IntArray): Int {
        val t = ((v - lo) / (hi - lo)).coerceIn(0f, 1f) * (stops.size - 1)
        val k = min(t.toInt(), stops.size - 2)
        return lerp(stops[k], stops[k + 1], t - k)
    }

    fun lerp(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        fun ch(s: Int) = (((a shr s) and 255) * (1 - u) + ((b shr s) and 255) * u).toInt()
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun scale(rgb: Int, f: Float): Int {
        fun ch(s: Int) = (((rgb shr s) and 255) * f).toInt().coerceIn(0, 255)
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
