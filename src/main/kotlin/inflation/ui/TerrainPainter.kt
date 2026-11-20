package inflation.ui

import inflation.ecology.HexSim
import inflation.ecology.Material
import inflation.world.Hex
import inflation.world.Place
import inflation.world.get
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The ground's own make-up drawn into each hex, over the map's background: the glades in its woods ([inflation.world.HexPlants.glade])
 * as sunlit flecks, more of them the more of the ground lies open in glades; how broken it is ([inflation.world.HexLand.ruggedness])
 * as small dark chevrons, more for rougher ground; and where the materials bands dig or gather crop out ([Material.deposit]) as
 * small marks along the hex's foot, one for each. The flecks and chevrons fall at the same spots each time (a hex's own draws),
 * so the picture does not flicker; how many there are is the grade. Read from the world as it stands; call only while it
 * stands still (see [WorldLock]).
 */
internal class TerrainPainter(private val sim: HexSim) {
    private val hex = sim.hex; private val hw = sim.hw; private val land = sim.land
    /** The materials that crop out somewhere, with their marks. */
    private val materials = Material.entries.filter { it.deposit != null }
    /** Ruggedness at which a hex gets the most chevrons: the 95th percentile of the land's places (worked out once). */
    private val roughest: Float by lazy {
        val r = ArrayList<Float>(); for (p in land.places) if (land.ruggedness[p] > 0f) r += land.ruggedness[p]
        r.sort()
        if (r.isEmpty()) 1f else r[(0.95 * (r.size - 1)).toInt()].coerceAtLeast(1e-6f)
    }

    /** Each hex's area-weighted mean of [f] over its places; NaN for a hex of water only. */
    private fun perHex(f: (Place) -> Float): FloatArray {
        val s = DoubleArray(hex.size); val a = DoubleArray(hex.size)
        for (p in land.places) { val h = hw.placeHex[p].i; val km2 = land.areaKm2[p].toDouble(); s[h] += f(p) * km2; a[h] += km2 }
        return FloatArray(hex.size) { if (a[it] > 0) (s[it] / a[it]).toFloat() else Float.NaN }
    }

    /**
     * Draws what is asked ([glades], [rugged], [resources]) in each hex on screen ([onScreen]), [sx] and [sy] taking km to the
     * screen at [kmPx] pixels a km. Nothing when the hexes are too small on screen to hold marks.
     */
    fun paint(g: Graphics2D, kmPx: Double, sx: (Float) -> Int, sy: (Float) -> Int, onScreen: (Hex, Int) -> Boolean,
              glades: Boolean, rugged: Boolean, resources: Boolean) {
        // The hex's corner radius on screen; the marks are laid out in fractions of it.
        val u = (hex.hexKm / sqrt(3.0) * kmPx).toFloat()
        if (u < 4f) return
        val stroke = g.stroke
        val plants = land.plants
        val glade = if (glades) perHex { plants.glade[it] } else null
        val crowns = if (glades) perHex { plants.treeLight(it) } else null
        val rough = if (rugged) perHex { land.ruggedness[it] } else null
        val deposits = if (resources) materials.map { m -> m to (m.deposit?.invoke(land)?.let { d -> perHex { d[it] } } ?: FloatArray(hex.size)) } else emptyList()
        // (As many marks as fit the hex's size on screen, at most [MOST_MARKS].)
        val most = (u * u / 10f).coerceIn(6f, MOST_MARKS.toFloat())
        val dot = max(1, (0.07f * u).roundToInt())
        for (h in hex.all) {
            if (!onScreen(h, 1)) continue
            val (cx, cy) = hex.centreKm(h)
            val x = sx(cx.toFloat()); val y = sy(cy.toFloat())
            if (glade != null && crowns != null) {
                val gl = glade[h.i]; val cr = crowns[h.i]
                if (!gl.isNaN() && cr >= WOODED_FROM && gl > 0.01f) {
                    g.color = GLADE
                    spots(h, 1, (gl * most).roundToInt(), u) { px, py -> g.fillOval(x + px - dot / 2, y + py - dot / 2, dot, dot) }
                }
            }
            if (rough != null) {
                val r = rough[h.i]
                if (!r.isNaN() && r > 0f) {
                    val k = ((r / roughest).coerceAtMost(1f) * most / 3f).roundToInt()
                    val s = max(2, (0.08f * u).roundToInt())
                    g.color = RUGGED; g.stroke = BasicStroke(max(0.8f, 0.03f * u))
                    spots(h, 2, k, u) { px, py -> g.drawPolyline(intArrayOf(x + px - s, x + px, x + px + s), intArrayOf(y + py + s / 2, y + py - s / 2, y + py + s / 2), 3) }
                }
            }
            if (deposits.isNotEmpty()) {
                val here = deposits.filter { (_, d) -> d[h.i] >= RESOURCE_SHOWN }
                if (here.isNotEmpty()) {
                    val r = max(2, (0.08f * u).roundToInt()); val gap = 2 * r + max(1, r / 2)
                    var mx = x - (here.size - 1) * gap / 2; val my = y + (0.62f * u).toInt()
                    g.stroke = BasicStroke(1f)
                    for ((m, _) in here) { mark(g, m, mx, my, r); mx += gap }
                }
            }
        }
        g.stroke = stroke
    }

    /**
     * [count] spots (at most [MOST_MARKS]) inside hex [h], each the same every time for that hex and [salt]: offsets from its
     * centre in pixels for a corner radius of [u], within the circle the hex's sides touch, kept off its rim.
     */
    private fun spots(h: Hex, salt: Int, count: Int, u: Float, at: (Int, Int) -> Unit) {
        var s = (h.i.toLong() * 6364136223846793005L + salt * 1442695040888963407L) or 1L
        fun next(): Float { s = s * 6364136223846793005L + 1442695040888963407L; return ((s ushr 40).toInt() and 0xFFFFFF) / 16777216f }
        val r = 0.78f * u
        for (k in 0 until min(count, MOST_MARKS)) {
            // (Even over the disc: the square root of a uniform draw for the radius.)
            val d = r * sqrt(next()); val a = 2 * Math.PI * next()
            at((d * kotlin.math.cos(a)).toInt(), (d * kotlin.math.sin(a)).toInt())
        }
    }

    /** Material [m]'s mark at ([x], [y]), [r] pixels across half: a shape and colour of its own. */
    private fun mark(g: Graphics2D, m: Material, x: Int, y: Int, r: Int) {
        val c = colourOf(m)
        when (m) {
            Material.STONE, Material.OBSIDIAN -> {
                // (Knapping stone: a diamond, the shape of a flake.)
                val xs = intArrayOf(x, x + r, x, x - r); val ys = intArrayOf(y - r, y, y + r, y)
                g.color = c; g.fillPolygon(xs, ys, 4); g.color = EDGE; g.drawPolygon(xs, ys, 4)
            }
            Material.SALT -> { g.color = c; g.fillRect(x - r, y - r, 2 * r, 2 * r); g.color = EDGE_DARK; g.drawRect(x - r, y - r, 2 * r, 2 * r) }
            Material.GROUND_STONE -> { g.color = c; g.fillOval(x - r, y - r, 2 * r, 2 * r); g.color = EDGE_DARK; g.drawOval(x - r, y - r, 2 * r, 2 * r) }
            else -> { g.color = c; g.fillOval(x - r, y - r, 2 * r, 2 * r); g.color = EDGE; g.drawOval(x - r, y - r, 2 * r, 2 * r) }
        }
    }

    fun legend(glades: Boolean, rugged: Boolean, resources: Boolean): MapLegend {
        val parts = ArrayList<String>(); val swatches = ArrayList<MapLegend.Swatch>()
        if (glades) {
            parts += "Glades: sunlit flecks in the woods (where trees' crowns cover at least ${(100 * WOODED_FROM).toInt()}% of the ground), the more " +
                "flecks the more of the ground lies open in glades, opened by fire and by herds that keep saplings down, closed by new crowns"
            swatches += MapLegend.Swatch(GLADE, "glades")
        }
        if (rugged) {
            parts += "Rugged ground: dark chevrons, more for rougher ground, full at the roughest 5% of the land"
            swatches += MapLegend.Swatch(RUGGED, "rugged")
        }
        if (resources) {
            parts += "Resources: where materials crop out on at least ${"%.1f".format(100 * RESOURCE_SHOWN)}% of a hex's ground, a mark each along its foot"
            for (m in materials) swatches += MapLegend.Swatch(colourOf(m), m.name.lowercase().replace('_', ' '))
        }
        return MapLegend("Terrain", parts.joinToString(". ") + ".", swatches = swatches)
    }

    companion object {
        /** Trees' crowns over at least this share of the ground make a wood whose glades are drawn. */
        const val WOODED_FROM = 0.15f
        /** Most flecks or chevrons in one hex. */
        const val MOST_MARKS = 90
        /** A material is marked in a hex where it crops out on at least this share of its ground. */
        const val RESOURCE_SHOWN = 0.005f
        val GLADE = Color(1f, 0.95f, 0.55f, 0.8f)
        val RUGGED = Color(0.22f, 0.15f, 0.1f, 0.75f)
        private val EDGE = Color(1f, 1f, 1f, 0.85f)
        private val EDGE_DARK = Color(0f, 0f, 0f, 0.6f)
        fun colourOf(m: Material): Color = when (m) {
            Material.STONE -> Color(0.7f, 0.7f, 0.75f)
            Material.OBSIDIAN -> Color(0.08f, 0.08f, 0.1f)
            Material.OCHRE -> Color(0.75f, 0.25f, 0.1f)
            Material.SALT -> Color(0.97f, 0.97f, 0.97f)
            Material.GROUND_STONE -> Color(0.5f, 0.45f, 0.4f)
            else -> Color(0.6f, 0.8f, 0.9f)
        }
    }
}
