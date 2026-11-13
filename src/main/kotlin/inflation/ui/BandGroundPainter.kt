package inflation.ui

import inflation.ecology.HexGroup
import inflation.ecology.HexSim
import inflation.ecology.ancestorsHex
import inflation.world.Hex
import inflation.world.get
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One band's reach beyond its camp, drawn over the map: the country it knows (faint), the ground its hunters searched and
 * its gatherers worked in its last week, and the places it holds in mind: its houses, cache, the place its dead mostly lie,
 * its meeting place and month, its rite sites by month, the places it keeps to by month, the homes of other bands it knows
 * of, where it was driven from; a raid it set out on and a scouting party it has out. Read from the world as it stands; call
 * only while it stands still (see [WorldLock]).
 */
internal class BandGroundPainter(private val sim: HexSim) {
    private val hex = sim.hex; private val hw = sim.hw; private val land = sim.land; private val groups = sim.groups

    /**
     * Draws band [g]'s ground; [sx], [sy] take km to the screen, [kmPx] is pixels a km, [onScreen] whether a hex is in view.
     * Nothing for a group that is not a band.
     */
    fun paint(g: Graphics2D, grp: HexGroup, kmPx: Double, sx: (Float) -> Int, sy: (Float) -> Int, onScreen: (Hex, Int) -> Boolean) {
        val b = grp.band ?: return
        val stroke = g.stroke; val font = g.font
        val rad = hex.hexKm / sqrt(3.0)
        fun hexagon(h: Hex, c: Color, fill: Boolean = true) {
            val (cx, cy) = hex.centreKm(h)
            val xs = IntArray(6) { sx((cx + rad * cos(Math.PI / 6 + it * Math.PI / 3)).toFloat()) }
            val ys = IntArray(6) { sy((cy + rad * sin(Math.PI / 6 + it * Math.PI / 3)).toFloat()) }
            g.color = c; if (fill) g.fillPolygon(xs, ys, 6) else g.drawPolygon(xs, ys, 6)
        }
        fun centre(h: Hex): Pair<Int, Int> { val (cx, cy) = hex.centreKm(h); return sx(cx.toFloat()) to sy(cy.toFloat()) }

        // The country it knows: a faint wash.
        for (h in hex.all) if (b.knows(h) && onScreen(h, 1)) hexagon(h, KNOWN)
        // Its gatherers' ground: the hexes it walked through, and the ring round its camp at the share they worked.
        val camp = hw.placeHex[grp.place]
        val walked = HashSet<Hex>().also { for (h in grp.path) it += h; if (it.isEmpty()) it += camp }
        for (h in walked) if (onScreen(h, 1)) hexagon(h, GATHER)
        if (b.ringSeen > 0f) for (d in 0 until 6) {
            val q = hex.neighbour(camp, d)
            if (!q.none && q !in walked && onScreen(q, 1)) hexagon(q, Color(GATHER.red, GATHER.green, GATHER.blue, (255 * min(0.45f, 0.1f + 2f * b.ringSeen)).toInt()))
        }
        // Its hunters' ground: each hex as far as they searched it against its land, outlined.
        b.huntSeen?.let { gr ->
            val km2 = HashMap<Hex, Float>()
            for (i in 0 until gr.size) { val h = hw.placeHex[gr.place[i]]; km2.merge(h, gr.km2[i], Float::plus) }
            val area = HashMap<Hex, Float>()
            for (h in km2.keys) area[h] = hw.placesOf(h).sumOf { land.areaKm2[it].toDouble() }.toFloat()
            g.stroke = BasicStroke(2f)
            for ((h, k) in km2) {
                if (k <= 0f || !onScreen(h, 1)) continue
                val share = (k / area.getValue(h).coerceAtLeast(1f)).coerceIn(0f, 1f)
                hexagon(h, Color(HUNT.red, HUNT.green, HUNT.blue, (255 * (0.1f + 0.45f * sqrt(share))).toInt()))
                hexagon(h, HUNT, fill = false)
            }
            g.stroke = BasicStroke(1f)
        }
        val (bx, by) = centre(camp)
        val z = max(0.7, kmPx).toFloat()
        g.font = font.deriveFont(Font.BOLD, 10f)
        fun label(h: Hex, text: String, c: Color, dy: Int = 0) {
            val (x, y) = centre(h)
            g.color = Color(0f, 0f, 0f, 0.6f); g.drawString(text, x + 6 + 1, y + dy + 1)
            g.color = c; g.drawString(text, x + 6, y + dy)
        }
        fun line(h: Hex, c: Color, dashed: Boolean) {
            val (x, y) = centre(h)
            g.stroke = if (dashed) BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 4f, floatArrayOf(6f, 4f), 0f) else BasicStroke(1.8f)
            g.color = c; g.drawLine(bx, by, x, y)
            g.stroke = BasicStroke(1f)
        }
        // The places it keeps to by month: their months, together at each place.
        val months = HashMap<Hex, ArrayList<Int>>()
        for (m in 0 until 12) { val p = b.seasonPlace[m]; if (p >= 0) months.getOrPut(hw.placeHex[p]) { ArrayList() } += m + 1 }
        for ((h, ms) in months) if (onScreen(h, 1)) label(h, "m" + ms.joinToString(","), SEASON, -6)
        // Its rite sites, by month.
        val rites = HashMap<Hex, ArrayList<Int>>()
        for (m in 0 until 12) { val p = b.rite[m]; if (p >= 0) rites.getOrPut(hw.placeHex[p]) { ArrayList() } += m + 1 }
        for ((h, ms) in rites) if (onScreen(h, 1)) {
            val (x, y) = centre(h); val r = (4 * z).toInt().coerceAtLeast(3)
            g.color = RITE; g.fillPolygon(intArrayOf(x - r, x, x + r), intArrayOf(y + r, y - r, y + r), 3)
            label(h, "rite " + ms.joinToString(","), RITE, 12)
        }
        // The homes of the bands it knows of.
        for ((_, h) in b.knownHomes) if (onScreen(h, 1)) {
            val (x, y) = centre(h); val r = (3 * z).toInt().coerceAtLeast(2)
            g.color = HOMES; g.drawOval(x - r, y - r, 2 * r, 2 * r)
        }
        // Its houses, cache, ancestors, where it was driven from.
        if (!b.houseHex.none) { line(b.houseHex, HOUSE, false); label(b.houseHex, "its houses", HOUSE) }
        if (!b.cachePlace.none && b.cacheKg >= 1f) { val h = hw.placeHex[b.cachePlace]; line(h, CACHE, true); label(h, "cache %.0f kg".format(b.cacheKg), CACHE, 12) }
        val dead = ancestorsHex(grp)
        if (!dead.none && onScreen(dead, 1)) {
            val (x, y) = centre(dead); val r = (4 * z).toInt().coerceAtLeast(3)
            g.stroke = BasicStroke(2f); g.color = DEAD; g.drawLine(x, y - r, x, y + r); g.drawLine(x - r * 2 / 3, y - r / 3, x + r * 2 / 3, y - r / 3)
            g.stroke = BasicStroke(1f); label(dead, "its dead", DEAD, 12)
        }
        if (!b.drivenFrom.none && b.keptFrom(b.drivenFrom)) label(b.drivenFrom, "driven out", RAID, 24)
        // Its meeting place, a raid under way, a scouting party out.
        b.appointment?.let { a -> line(a.hex, MEET, true); label(a.hex, "meets month ${a.month + 1}", MEET, 24) }
        b.raid?.let { r -> line(r.hex, RAID, false); label(r.hex, "raid", RAID, -18) }
        groups.partyOut[grp.id]?.let { pid ->
            val p = groups.parties[pid]
            val party = groups.groups.firstOrNull { it.id == pid && it.alive }
            if (party != null) { val h = hw.placeHex[party.place]; line(h, PARTY, true); label(h, "its scouts", PARTY, -18) }
            if (p != null && !p.target.none) label(p.target, "scouts' goal", PARTY, -30)
        }
        g.stroke = stroke; g.font = font
    }

    companion object {
        private val KNOWN = Color(1f, 1f, 1f, 0.07f)
        private val GATHER = Color(0.3f, 0.85f, 0.25f, 0.4f)
        private val HUNT = Color(0.95f, 0.3f, 0.1f, 0.9f)
        private val SEASON = Color(1f, 1f, 0.6f)
        private val RITE = Color(0.75f, 0.45f, 1f)
        private val HOMES = Color(1f, 0.95f, 0.85f, 0.85f)
        private val HOUSE = Color(1f, 0.8f, 0.4f)
        private val CACHE = Color(0.9f, 0.75f, 0.5f)
        private val DEAD = Color(0.85f, 0.85f, 0.85f)
        private val MEET = Color(0.4f, 0.9f, 1f)
        private val RAID = Color(1f, 0.25f, 0.2f)
        private val PARTY = Color(1f, 0.6f, 0.9f)

        val legend = MapLegend("Band's ground", "The ground of the band picked in the list (or the one followed): the country it knows as a faint " +
            "wash; green, the hexes it walked through last week and, fainter, the hexes round its camp its gatherers worked, as far as they " +
            "did; red outlines, the ground its hunters searched, the fuller the more of the hex. Labels: the places it keeps to by month (m1-m12), " +
            "its rite sites (triangles), its houses, cache and the place most of its dead lie (cross), its meeting place and month, a raid it set " +
            "out on, its scouting party and the party's goal, where raiders drove it from while the feud lasts; open rings, the homes of other " +
            "bands it knows of.", swatches = listOf(MapLegend.Swatch(GATHER, "gathered"), MapLegend.Swatch(HUNT, "hunted"),
                MapLegend.Swatch(SEASON, "places by month"), MapLegend.Swatch(RITE, "rite site", triangle = true), MapLegend.Swatch(HOMES, "homes it knows of"),
                MapLegend.Swatch(MEET, "meeting place"), MapLegend.Swatch(RAID, "raid"), MapLegend.Swatch(PARTY, "scouting party")))
    }
}
