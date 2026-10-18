package inflation.tools

import inflation.Args
import inflation.ecology.HexGeography
import inflation.ui.HexPicture
import inflation.world.HexMap
import inflation.world.HexWorld
import inflation.world.World
import inflation.world.WorldConfig
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import kotlin.math.ln

/**
 * The named features of a fresh world over its terrain: each region's hexes tinted by kind, the named rivers
 * traced, every name written at the feature's heart; prints the features and how a few hexes are told.
 * Usage: runTool -Pmain=inflation.tools.HexGeographyPictureKt -PtoolArgs="--seed N --width KM --height KM --out FILE --px N"
 */
fun main(args: Array<String>) {
    val a = Args(args, "hex-features-picture [--seed N] [--width KM] [--height KM] [--out FILE] [--px N]")
    val base = WorldConfig(seed = 1)
    val cfg = base.copy(seed = a.long("--seed", base.seed), widthKm = a.int("--width", base.widthKm), heightKm = a.int("--height", base.heightKm))
    val out = a.file("--out") ?: java.io.File("hex-features.png"); val px = a.int("--px", 2)
    a.done()
    val world = World.generate(cfg)
    val hex = HexMap(world)
    val hw = HexWorld(world, hex)
    val t0 = System.nanoTime()
    val f = HexGeography(hw)
    println("features in %.1f s: %s".format((System.nanoTime() - t0) / 1e9, f.summary()))
    for (x in f.all.sortedByDescending { it.hexes.size }.take(40))
        println("  %-12s %-28s %5d hexes  in %s".format(x.kind.label, x.name, x.hexes.size, if (x.parent >= 0) f.all[x.parent].name else "-"))
    val land = (0 until hex.size).filter { hw.land[it] > 0.5f }
    for (h in land.shuffled(kotlin.random.Random(1)).take(8)) println("  hex $h: ${f.where(h)}")
    val img = HexPicture.render(world, hex, 0.0, 0.0, cfg.widthKm.toDouble(), cfg.heightKm.toDouble(), px)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    fun x(h: Int) = (hex.centreKm(h).first * px).toInt()
    fun y(h: Int) = (hex.centreKm(h).second * px).toInt()
    val tint = mapOf(
        HexGeography.Kind.MOUNTAINS to Color(120, 80, 60, 90), HexGeography.Kind.HILLS to Color(170, 130, 90, 50), HexGeography.Kind.FOREST to Color(0, 90, 0, 60),
        HexGeography.Kind.DEEP_FOREST to Color(0, 60, 0, 90), HexGeography.Kind.WOODLAND to Color(90, 140, 40, 50),
        HexGeography.Kind.STEPPE to Color(220, 200, 80, 60), HexGeography.Kind.DESERT to Color(240, 180, 90, 70),
        HexGeography.Kind.MARSH to Color(40, 140, 120, 70), HexGeography.Kind.FLOODPLAIN to Color(0, 160, 200, 70),
        HexGeography.Kind.BAY to Color(0, 40, 160, 80), HexGeography.Kind.PENINSULA to Color(200, 0, 160, 60), HexGeography.Kind.ISTHMUS to Color(255, 0, 0, 80), HexGeography.Kind.STRAIT to Color(0, 120, 255, 90),
        HexGeography.Kind.ISLAND to Color(255, 120, 0, 60))
    val r = hex.hexKm / kotlin.math.sqrt(3.0) * px
    for (feat in f.all) {
        val c = tint[feat.kind] ?: continue
        g.color = c
        for (h in feat.hexes) {
            val xs = IntArray(6) { (x(h) + r * kotlin.math.cos(Math.PI / 6 + it * Math.PI / 3)).toInt() }
            val ys = IntArray(6) { (y(h) + r * kotlin.math.sin(Math.PI / 6 + it * Math.PI / 3)).toInt() }
            g.fillPolygon(xs, ys, 6)
        }
    }
    g.color = Color(0, 40, 200); g.stroke = BasicStroke(1.5f)
    for (feat in f.all) if (feat.kind == HexGeography.Kind.RIVER) g.drawPolyline(IntArray(feat.hexes.size) { x(feat.hexes[it]) }, IntArray(feat.hexes.size) { y(feat.hexes[it]) }, feat.hexes.size)
    for (feat in f.all.filter { it.kind != HexGeography.Kind.VALLEY }.sortedByDescending { it.hexes.size }) {
        g.font = Font(Font.SANS_SERIF, if (feat.kind == HexGeography.Kind.RIVER) Font.ITALIC else Font.BOLD, (7 + 1.6 * ln(feat.hexes.size.toDouble())).toInt() * px / 2 + 4)
        val w = g.fontMetrics.stringWidth(feat.name); val lx = x(feat.label) - w / 2; val ly = y(feat.label)
        g.color = Color(255, 255, 255, 190)
        for ((dx, dy) in arrayOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) g.drawString(feat.name, lx + dx, ly + dy)
        g.color = if (feat.kind == HexGeography.Kind.RIVER || feat.kind == HexGeography.Kind.LAKE || feat.kind == HexGeography.Kind.BAY) Color(0, 30, 150) else Color(40, 30, 20)
        g.drawString(feat.name, lx, ly)
    }
    javax.imageio.ImageIO.write(img, "png", out)
    println("wrote $out")
}
