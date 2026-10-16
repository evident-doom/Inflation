package inflation.ui

import inflation.world.Biome
import inflation.world.HexWorld
import kotlin.math.min

/** A picture of the hex world as the model sees it. */
object HexWorldPicture {
    /** Draws the hexes: each filled with the colour of its main kind of country, darker the higher it lies, with the borders that have no way across on foot in black. */
    fun draw(hw: HexWorld, file: java.io.File, pxPerKm: Double = 1.0) { with(hw) {
        val km = world.grid.cellKm
        val img = java.awt.image.BufferedImage((world.grid.width * km * pxPerKm).toInt(), (world.grid.height * km * pxPerKm).toInt(), java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        val r = hex.hexKm / kotlin.math.sqrt(3.0)
        for (h in 0 until size) {
            val (cx, cy) = hex.centreKm(h)
            val xs = IntArray(6) { ((cx + r * kotlin.math.cos(Math.PI / 6 + it * Math.PI / 3)) * pxPerKm).toInt() }
            val ys = IntArray(6) { ((cy + r * kotlin.math.sin(Math.PI / 6 + it * Math.PI / 3)) * pxPerKm).toInt() }
            val b = mainBiome(h)
            val colour = when {
                b == null || land[h] < 0.5f -> if (sea[h] >= lake[h]) Biome.OCEAN.rgb else Biome.LAKE.rgb
                else -> {
                    val dark = 1f - 0.5f * min(1f, elevationM[h] / 3000f)
                    val c = b.rgb
                    ((((c shr 16) and 255) * dark).toInt() shl 16) or ((((c shr 8) and 255) * dark).toInt() shl 8) or ((c and 255) * dark).toInt()
                }
            }
            g.color = java.awt.Color(colour)
            g.fillPolygon(xs, ys, 6)
            if (riverM3s[h] > 50f && land[h] >= 0.5f) { g.color = java.awt.Color(0x3060d0); g.fillOval(((cx - 1.5) * pxPerKm).toInt(), ((cy - 1.5) * pxPerKm).toInt(), (3 * pxPerKm).toInt(), (3 * pxPerKm).toInt()) }
        }
        g.stroke = java.awt.BasicStroke((0.8 * pxPerKm).toFloat())
        g.color = java.awt.Color.BLACK
        for (h in 0 until size) {
            if (land[h] < 0.5f) continue
            val (cx, cy) = hex.centreKm(h)
            for (d in 0 until 6) {
                val n = hex.neighbour(h, d)
                if (n < 0 || land[n] < 0.5f || hex.crossing[h * 6 + d].isFinite()) continue
                // The shared edge: the corners at angles -30° + 60°·d and +30° + 60°·d from the centre.
                val a0 = Math.toRadians(-30.0 + 60.0 * d); val a1 = Math.toRadians(30.0 + 60.0 * d)
                g.drawLine(((cx + r * kotlin.math.cos(a0)) * pxPerKm).toInt(), ((cy + r * kotlin.math.sin(a0)) * pxPerKm).toInt(),
                    ((cx + r * kotlin.math.cos(a1)) * pxPerKm).toInt(), ((cy + r * kotlin.math.sin(a1)) * pxPerKm).toInt())
            }
        }
        javax.imageio.ImageIO.write(img, "png", file)
    } }
}
