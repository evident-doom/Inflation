package inflation.world

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
        write(dir, "relief.png", world) { i -> relief(world, i) }
        write(dir, "biomes.png", world) { i -> withRivers(world, i, shade(world, i, world.biome[i].rgb)) }
        write(dir, "geology.png", world) { i -> withRivers(world, i, shade(world, i, rockColour(world.geology.rock[i], world.isSea[i]))) }
        write(dir, "minerals.png", world) { i -> mineralColour(world, i) }
        write(dir, "precipitation.png", world) { i -> ramp(world.climate.annualPrecip[i], 0f, 1500f, DRY_WET) }
        write(dir, "temperature-coldest-month.png", world) { i -> ramp(world.climate.coldestMonthTemp(i), -20f, 30f, COLD_HOT) }
        write(dir, "temperature-warmest-month.png", world) { i -> ramp(world.climate.warmestMonthTemp(i), -20f, 40f, COLD_HOT) }
        write(dir, "soil.png", world) { i -> if (world.isSea[i]) 0x1f3f6f else ramp(world.soilFertility[i], 0f, 1f, DRY_WET) }
    }

    private fun write(dir: File, name: String, world: World, colour: (Int) -> Int) {
        val g = world.grid
        val img = BufferedImage(g.width, g.height, BufferedImage.TYPE_INT_RGB)
        for (i in 0 until g.size) img.setRGB(g.x(i), g.y(i), colour(i))
        ImageIO.write(img, "png", File(dir, name))
    }

    private fun relief(world: World, i: Int): Int {
        val h = world.elevation[i]
        val base = when {
            world.isSea[i] -> lerp(0x2a5f9f, 0x0b1f3f, min(1f, -h / 3000f))
            world.water.isLake(i) -> if (world.water.lakeClosed[i]) 0x7fb5c8 else 0x3f7fbf
            h < 200 -> lerp(0x5f9f4f, 0x9fbf5f, h / 200f)
            h < 1000 -> lerp(0x9fbf5f, 0xbf9f5f, (h - 200) / 800f)
            h < 2500 -> lerp(0xbf9f5f, 0x8f7f6f, (h - 1000) / 1500f)
            else -> lerp(0x8f7f6f, 0xffffff, min(1f, (h - 2500) / 1500f))
        }
        return withRivers(world, i, shade(world, i, base))
    }

    private fun withRivers(world: World, i: Int, rgb: Int): Int {
        if (!world.isRiver(i)) return rgb
        val q = world.water.discharge[i]
        val strength = (ln(q / SurfaceWater.RIVER_THRESHOLD + 1) / ln(500.0)).toFloat().coerceIn(0.25f, 1f)
        return lerp(rgb, 0x1f5fbf, strength)
    }

    /** Hillshade lit from the north-west. */
    private fun shade(world: World, i: Int, rgb: Int): Int {
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

    private fun rockColour(r: Rock, sea: Boolean): Int = if (sea) 0x1f3f6f else when (r) {
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

    private val DRY_WET = intArrayOf(0xeed9a6, 0xbfbf5f, 0x3f9f3f, 0x1f5f9f)
    private val COLD_HOT = intArrayOf(0x3f3fbf, 0x7fbfff, 0xffffbf, 0xff9f3f, 0xbf1f1f)

    private fun ramp(v: Float, lo: Float, hi: Float, stops: IntArray): Int {
        val t = ((v - lo) / (hi - lo)).coerceIn(0f, 1f) * (stops.size - 1)
        val k = min(t.toInt(), stops.size - 2)
        return lerp(stops[k], stops[k + 1], t - k)
    }

    private fun lerp(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        fun ch(s: Int) = (((a shr s) and 255) * (1 - u) + ((b shr s) and 255) * u).toInt()
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun scale(rgb: Int, f: Float): Int {
        fun ch(s: Int) = (((rgb shr s) and 255) * f).toInt().coerceIn(0, 255)
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
