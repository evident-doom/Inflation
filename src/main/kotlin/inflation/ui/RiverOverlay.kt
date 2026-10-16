package inflation.ui

import inflation.world.HexLand
import inflation.world.HexMap
import inflation.world.RiverClass
import inflation.world.World
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.geom.Path2D
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The rivers as they run this week, drawn over the ground on their fine courses: each stretch as
 * wide as its flow now (display width growing as flow^0.4, exaggerated as on the ground picture), so a
 * river that dries up in summer thins to nothing and a river in spate swells and turns pale.
 *
 * The weekly flow is kept per hex ([HexLand.flow], the hex's main river); a fine stretch takes its
 * long-term flow scaled by the flow now against the usual flow of the hex its water mostly comes
 * from (for a stretch off the hex's main river, the hex where its biggest headwater is the main
 * river, or else its own hex). Everything here is worked out on drawing; the step does nothing for it.
 */
class RiverOverlay(world: World, hex: HexMap, private val land: HexLand) {
    private val ax: FloatArray; private val ay: FloatArray; private val bx: FloatArray; private val by: FloatArray
    /** Long-term flow of each stretch, m³/s, and the hex whose flow now it follows. */
    private val usual: FloatArray; private val src: IntArray

    init {
        val g = world.grid
        val discharge = world.water.discharge
        val recv = world.water.drainage.receiver
        // Each hex's main river: its cell of biggest flow (as HexLand picks it).
        val main = IntArray(hex.size) { -1 }
        for (i in 0 until g.size) {
            if (world.isSea[i]) continue
            val h = hex.owner[i]
            if (main[h] < 0 || discharge[i] > discharge[main[h]]) main[h] = i
        }
        val isMain = BooleanArray(g.size); for (m in main) if (m >= 0) isMain[m] = true
        // Upstream first: a cell's biggest donor is settled before the cell itself.
        val follows = IntArray(g.size) { hex.owner[it] }
        val bigDonor = IntArray(g.size) { -1 }
        val stack = world.water.drainage.stack
        for (k in stack.size - 1 downTo 0) {
            val i = stack[k]
            if (!isMain[i] && bigDonor[i] >= 0) follows[i] = follows[bigDonor[i]]
            val r = recv[i]
            if (r != i && (bigDonor[r] < 0 || discharge[i] > discharge[bigDonor[r]])) bigDonor[r] = i
        }
        val cells = (0 until g.size).filter { world.riverClass(it) >= RiverClass.STREAM && recv[it] != it }
        val ck = g.cellKm
        ax = FloatArray(cells.size) { ((g.x(cells[it]) + 0.5) * ck).toFloat() }
        ay = FloatArray(cells.size) { ((g.y(cells[it]) + 0.5) * ck).toFloat() }
        bx = FloatArray(cells.size) { ((g.x(recv[cells[it]]) + 0.5) * ck).toFloat() }
        by = FloatArray(cells.size) { ((g.y(recv[cells[it]]) + 0.5) * ck).toFloat() }
        usual = FloatArray(cells.size) { discharge[cells[it]] }
        src = IntArray(cells.size) { follows[cells[it]] }
    }

    /** Whether the rivers are drawn at all (the viewer's "River flows" box). */
    @Volatile var visible = true

    /** Stretches drawn. */
    val size get() = usual.size

    /**
     * Draws the rivers in the view of [wPx] × [hPx] pixels, where a point (x, y) km lands at
     * (ox + x·kmPx, oy + y·kmPx).
     */
    fun draw(g: Graphics2D, ox: Double, oy: Double, kmPx: Double, wPx: Int, hPx: Int) {
        if (!visible) return
        val x0 = (-ox / kmPx).toFloat(); val y0 = (-oy / kmPx).toFloat()
        val x1 = ((wPx - ox) / kmPx).toFloat(); val y1 = ((hPx - oy) / kmPx).toFloat()
        // Stretches of like width and colour go in one path: (width in quarter pixels) × spate level.
        val paths = HashMap<Int, Path2D.Float>()
        for (k in usual.indices) {
            if (max(ax[k], bx[k]) < x0 || min(ax[k], bx[k]) > x1 || max(ay[k], by[k]) < y0 || min(ay[k], by[k]) > y1) continue
            val h = src[k]
            val mean = land.meanFlow[h]
            val ratio = (if (mean > 0f) land.flow[h] / mean else 1f).coerceIn(0f, 10f)
            val q = usual[k] * ratio
            val px = (min(MAX_KM, WIDTH_KM * q.pow(0.4f)) * kmPx).toFloat()
            if (px < MIN_PX) continue
            val quarters = min(400, (px * 4f).toInt().coerceAtLeast(1))
            val spate = when { ratio >= 3f -> 2; ratio >= 1.5f -> 1; else -> 0 }
            val path = paths.getOrPut(quarters * 3 + spate) { Path2D.Float() }
            path.moveTo(ox + ax[k] * kmPx, oy + ay[k] * kmPx); path.lineTo(ox + bx[k] * kmPx, oy + by[k] * kmPx)
        }
        val oldStroke = g.stroke
        for ((key, path) in paths) {
            val px = key / 3 / 4f; val spate = key % 3
            // Under a pixel wide, drawn a pixel wide but fainter, so creeks fade out rather than flicker.
            val alpha = min(1f, px).coerceIn(0f, 1f)
            val c = COLOURS[spate]
            g.color = Color(c.red, c.green, c.blue, (255 * alpha).toInt().coerceIn(0, 255))
            g.stroke = BasicStroke(max(1f, px), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(path)
        }
        g.stroke = oldStroke
    }

    companion object {
        /** Display width, km, at 1 m³/s: a 3 m³/s stream about 0.6 km, as on the ground picture. */
        const val WIDTH_KM = 0.39
        /** Widest drawn, km. */
        const val MAX_KM = 6.0
        /** Narrower than this, pixels, not drawn at all. */
        const val MIN_PX = 0.15f
        /** Usual water; in spate (over 1.5 times the usual flow); in high flood (over 3 times). */
        private val COLOURS = arrayOf(Color(0x2f6fd0), Color(0x5fa8f0), Color(0xb0e4ff))
    }
}
