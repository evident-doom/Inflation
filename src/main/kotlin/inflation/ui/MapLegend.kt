package inflation.ui

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import kotlin.math.abs

/**
 * What a tint's colours mean, drawn in the top right corner of the map: a title, the colour ramp
 * from 0 to 1 as the hexes are shaded (over a plain earth tone, so faint shades show as they do on
 * the map, and where the ramp leaves a hex clear the earth shows through), values marked along it,
 * and a short note. Without a ramp, only the title and the note. [text] is the longer explanation
 * shown under the map. [swatches] are marks of single colours, each with what it stands for.
 */
internal class MapLegend(val title: String, val text: String, val ramp: ((Float) -> Color?)? = null,
                         val ticks: List<Pair<Float, String>> = emptyList(), val note: String = "",
                         val swatches: List<Swatch> = emptyList()) {
    /** A mark on the map, a dot (or a triangle), its colour and what it stands for. */
    class Swatch(val color: Color, val label: String, val triangle: Boolean = false)

    companion object {
        private const val WIDTH = 230
        private const val RAMP_H = 14
        private const val PAD = 8
        private val GROUND = Color(0x8c8466)
        private val BOX = Color(0f, 0f, 0f, 0.68f)
        private val TITLE_FONT = Font(Font.SANS_SERIF, Font.BOLD, 12)
        private val TEXT_FONT = Font(Font.SANS_SERIF, Font.PLAIN, 11)

        /** A number as the legends show it: whole above 100, one decimal above 1, else three figures. */
        fun num(x: Float) = when { abs(x) >= 100 -> "%.0f".format(x); abs(x) >= 1 -> "%.1f".format(x); else -> "%.3g".format(x) }

        /** Draws [legends] one under another down from the top right corner of an area [w] pixels wide. */
        fun draw(g: Graphics2D, legends: List<MapLegend>, w: Int) {
            var y = PAD
            for (l in legends) y += l.draw(g, w - WIDTH - PAD, y) + PAD
        }

        /** [s] broken into lines no wider than [width] pixels in [g]'s font. */
        private fun wrap(g: Graphics2D, s: String, width: Int): List<String> {
            if (s.isEmpty()) return emptyList()
            val fm = g.fontMetrics
            val lines = ArrayList<String>()
            var line = ""
            for (word in s.split(' ')) {
                val next = if (line.isEmpty()) word else "$line $word"
                if (fm.stringWidth(next) <= width || line.isEmpty()) line = next else { lines += line; line = word }
            }
            if (line.isNotEmpty()) lines += line
            return lines
        }
    }

    /** Draws this legend with its top left corner at ([x], [y]); returns its height. */
    private fun draw(g: Graphics2D, x: Int, y: Int): Int {
        val inner = WIDTH - 2 * PAD
        g.font = TITLE_FONT
        val titleLines = wrap(g, title, inner)
        val titleH = g.fontMetrics.height
        g.font = TEXT_FONT
        val noteLines = wrap(g, note, inner)
        val textH = g.fontMetrics.height
        val rampH = if (ramp != null) RAMP_H + 4 + (if (ticks.isEmpty()) 0 else 4 + textH) else 0
        val swatchH = if (swatches.isEmpty()) 0 else 4 + swatches.size * textH
        val h = PAD + titleLines.size * titleH + (if (ramp != null) 4 + rampH else 0) + swatchH + noteLines.size * textH + PAD

        g.color = BOX; g.fillRoundRect(x, y, WIDTH, h, 8, 8)
        var cy = y + PAD
        g.font = TITLE_FONT; g.color = Color.WHITE
        for (s in titleLines) { g.drawString(s, x + PAD, cy + g.fontMetrics.ascent); cy += titleH }
        g.font = TEXT_FONT
        val ramp = ramp
        if (ramp != null) {
            cy += 4
            val rx = x + PAD
            g.color = GROUND; g.fillRect(rx, cy, inner, RAMP_H)
            for (i in 0 until inner) {
                val c = ramp(i / (inner - 1f)) ?: continue
                g.color = c; g.fillRect(rx + i, cy, 1, RAMP_H)
            }
            g.color = Color(0.85f, 0.85f, 0.85f); g.stroke = BasicStroke(1f); g.drawRect(rx, cy, inner - 1, RAMP_H)
            cy += RAMP_H
            val fm = g.fontMetrics
            for ((t, label) in ticks) {
                val tx = rx + (t.coerceIn(0f, 1f) * (inner - 1)).toInt()
                g.color = Color(0.85f, 0.85f, 0.85f); g.drawLine(tx, cy, tx, cy + 3)
                // Labels sit centred under their mark, kept inside the box.
                val lw = fm.stringWidth(label)
                val lx = (tx - lw / 2).coerceIn(rx, rx + inner - lw)
                g.color = Color.WHITE; g.drawString(label, lx, cy + 4 + fm.ascent)
            }
            cy += 4 + (if (ticks.isEmpty()) 0 else 4 + textH)
        }
        if (swatches.isNotEmpty()) {
            cy += 4
            val r = 4
            for (sw in swatches) {
                val mx = x + PAD + r; val my = cy + textH / 2
                g.color = sw.color
                if (sw.triangle) g.fillPolygon(intArrayOf(mx - r, mx, mx + r), intArrayOf(my + r, my - r, my + r), 3)
                else g.fillOval(mx - r, my - r, 2 * r, 2 * r)
                g.color = Color.WHITE; g.drawString(sw.label, x + PAD + 2 * r + 6, cy + g.fontMetrics.ascent)
                cy += textH
            }
        }
        g.color = Color(0.85f, 0.85f, 0.85f)
        for (s in noteLines) { g.drawString(s, x + PAD, cy + g.fontMetrics.ascent); cy += textH }
        return h
    }
}
