package inflation.ui

import inflation.Args
import inflation.world.HexMap
import inflation.world.World
import inflation.world.RiverClass
import inflation.world.WorldConfig
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A picture of the hexes over the fine terrain they summarise: shaded relief and plant cover from
 * the 1 km cells, rivers drawn on their own courses (wider the bigger), and every border between
 * two land hexes marked with what it takes to cross on foot, the ground and the water apart:
 *
 * - the ground: no line, open (under 1.5 times the effort of flat ground); yellow, hard (1.5–4
 *   times); orange-red, very hard (over 4 times); black, no way over the ground (cliffs, open water);
 * - the water, a narrower line on top: light blue, a river to be waded (knee to waist deep at the
 *   best crossing); mid blue, chest deep, slow and dangerous; dark blue, deeper, closed on foot.
 *
 * Usage: hex-picture [--seed N] [--width KM] [--height KM] [--out FILE] [--x KM --y KM --size KM] [--px N]
 * where x, y, size pick a square of the map (km from the north-west corner) and px is pixels per km.
 */
fun main(args: Array<String>) {
    val a = Args(args, "hex-picture [--seed N] [--width KM] [--height KM] [--out FILE] [--x KM --y KM --size KM] [--px N]")
    val base = WorldConfig(seed = 1)
    val cfg = base.copy(seed = a.long("--seed", base.seed), widthKm = a.int("--width", base.widthKm), heightKm = a.int("--height", base.heightKm))
    val out = a.file("--out") ?: java.io.File("hex-picture.png")
    var x0 = a.double("--x", -1.0); var y0 = a.double("--y", -1.0); var sizeKm = a.double("--size", -1.0); val px = a.int("--px", 4)
    a.done()
    val world = World.generate(cfg)
    val hex = HexMap(world)
    if (sizeKm < 0) { x0 = 0.0; y0 = 0.0; sizeKm = max(cfg.widthKm, cfg.heightKm).toDouble() }
    HexPicture.draw(world, hex, out, x0, y0, sizeKm, px)
}

object HexPicture {
    fun draw(world: World, hex: HexMap, file: java.io.File, x0: Double, y0: Double, sizeKm: Double, px: Int) {
        javax.imageio.ImageIO.write(render(world, hex, x0, y0, sizeKm, sizeKm, px), "png", file)
    }

    /** The picture of the [w] × [h] km from (x0, y0), at [px] pixels per km; without [rivers], the river courses are left for the
     * viewer to draw by their flow. */
    fun render(world: World, hex: HexMap, x0: Double, y0: Double, wKm: Double, hKm: Double, px: Int, rivers: Boolean = true): BufferedImage {
        val g = world.grid
        val ck = g.cellKm
        val w = min(wKm, g.width * ck - x0); val h = min(hKm, g.height * ck - y0)
        val img = BufferedImage((w * px).toInt(), (h * px).toInt(), BufferedImage.TYPE_INT_RGB)
        // Fine terrain: shaded relief tinted by height, greener where the country is wooded.
        for (py in 0 until img.height) for (pxi in 0 until img.width) {
            val cx = ((x0 + pxi.toDouble() / px) / ck).toInt().coerceIn(0, g.width - 1)
            val cy = ((y0 + py.toDouble() / px) / ck).toInt().coerceIn(0, g.height - 1)
            img.setRGB(pxi, py, cellColour(world, cx, cy))
        }
        val gr = img.createGraphics()
        gr.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        fun sx(km: Double) = ((km - x0) * px).toInt()
        fun sy(km: Double) = ((km - y0) * px).toInt()
        // Rivers on their own courses: each river cell joined to the next one down, wider the bigger.
        val recv = world.water.drainage.receiver
        if (rivers) for (i in 0 until g.size) {
            val cls = world.riverClass(i)
            if (cls < RiverClass.STREAM) continue
            val r = recv[i]
            if (r == i) continue
            val ax = (g.x(i) + 0.5) * ck; val ay = (g.y(i) + 0.5) * ck
            val bx = (g.x(r) + 0.5) * ck; val by = (g.y(r) + 0.5) * ck
            if (max(ax, bx) < x0 || min(ax, bx) > x0 + w || max(ay, by) < y0 || min(ay, by) > y0 + h) continue
            gr.color = Color(0x2f6fd0)
            gr.stroke = BasicStroke(when (cls) { RiverClass.STREAM -> 0.6f; RiverClass.RIVER -> 1.4f; else -> 2.4f } * px / 2f)
            gr.drawLine(sx(ax), sy(ay), sx(bx), sy(by))
        }
        // Hex outlines, faint, and every border coloured by what it takes to cross on foot.
        val r = hex.hexKm / sqrt(3.0)
        val foot = hex.crossings[HexMap.Travel.FOOT.ordinal]
        for (a in 0 until hex.size) {
            val (cx, cy) = hex.centreKm(a)
            if (cx < x0 - hex.hexKm || cx > x0 + w + hex.hexKm || cy < y0 - hex.hexKm || cy > y0 + h + hex.hexKm) continue
            for (d in 0 until 6) {
                val b = hex.neighbour(a, d)
                val a0 = Math.toRadians(-30.0 + 60.0 * d); val a1 = Math.toRadians(30.0 + 60.0 * d)
                val xa = sx(cx + r * cos(a0)); val ya = sy(cy + r * sin(a0)); val xb = sx(cx + r * cos(a1)); val yb = sy(cy + r * sin(a1))
                gr.stroke = BasicStroke(0.6f); gr.color = Color(255, 255, 255, 45)
                gr.drawLine(xa, ya, xb, yb)
                if (b < 0 || hex.heart[a] < 0 || hex.heart[b] < 0 || b < a) continue
                // The ground: colour by effort over it, rivers left aside.
                val e = hex.ground[HexMap.Travel.FOOT.ordinal][a * 6 + d] / hex.hexKm.toFloat()
                val (colour, width) = when {
                    !e.isFinite() -> Color.BLACK to 3.0f
                    e >= 4f -> Color(0xe0401a) to 2.4f
                    e >= 1.5f -> Color(0xf0c020) to 1.4f
                    else -> null to 0f
                }
                if (colour != null) {
                    gr.stroke = BasicStroke(width * px / 3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    gr.color = colour
                    gr.drawLine(xa, ya, xb, yb)
                }
                // The water, on top: a river on this border, by depth at its best crossing.
                val depth = hex.riverDepthM[a * 6 + d]
                if (depth <= HexMap.KNEE) continue
                gr.color = when { depth > 1.5f -> Color(0x0a1f6e); depth > 1.0f -> Color(0x1f55d0); else -> Color(0x6fb4ff) }
                gr.stroke = BasicStroke(1.1f * px / 2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                gr.drawLine(xa, ya, xb, yb)
            }
        }
        return img
    }

    private fun cellColour(world: World, x: Int, y: Int): Int {
        val g = world.grid
        val i = g.index(x, y)
        if (world.isSea[i]) return 0x30506e
        if (world.water.isLake(i)) return 0x4f86c6
        val e = world.elevation[i]
        val ex = world.elevation[g.index(min(x + 1, g.width - 1), y)] - world.elevation[g.index(max(x - 1, 0), y)]
        val ey = world.elevation[g.index(x, min(y + 1, g.height - 1))] - world.elevation[g.index(x, max(y - 1, 0))]
        val shade = (0.78f - (ex + ey) / (3f * g.cellM.toFloat())).coerceIn(0.25f, 1.1f)
        val base = world.biome[i].rgb
        val hgt = (e / 3500f).coerceIn(0f, 1f)
        fun ch(c: Int) = ((c * (1 - 0.5f * hgt) + 230 * 0.5f * hgt) * shade).toInt().coerceIn(0, 255)
        val cliff = world.terrain.cliff[i]
        return if (cliff) 0x3a2a20 else (ch((base shr 16) and 255) shl 16) or (ch((base shr 8) and 255) shl 8) or ch(base and 255)
    }
}
