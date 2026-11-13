package inflation.ui

import inflation.ecology.Dealing
import inflation.ecology.HexGroup
import inflation.ecology.HexSim
import inflation.ecology.Regard
import inflation.ecology.FEUD
import inflation.ecology.FRIEND_TIE
import inflation.ecology.kinship
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Bands' dealings with each other: over the map, what passed between bands in the last weeks (lines from the band that acted
 * to the other, by kind, fading with the weeks) and the ties of the band picked (dotted: pale green as far as it is fond of a band, pink
 * as far as it is not, dashed red where it holds a feud against that band's people); and for the side panel, how the band
 * picked stands toward each band it has ties with, knows the home of, or dealt with lately ([Regard]). The dealings come
 * from the model's [inflation.ecology.HexGroups.dealings], taken once a week by [week].
 */
internal class RelationsView(private val sim: HexSim) {
    private val groups = sim.groups; private val hex = sim.hex; private val hw = sim.hw

    /** The dealings of the last [WEEKS_KEPT] weeks, newest first. */
    @Volatile private var recent: List<List<Dealing>> = emptyList()

    init { groups.keepDealings = true }

    /** After each week (on the simulation's thread): takes the week's dealings from the model. */
    fun week() {
        val now = ArrayList<Dealing>()
        while (true) now += groups.dealings.poll() ?: break
        recent = (listOf<List<Dealing>>(now) + recent).take(WEEKS_KEPT)
    }

    /** The dealings of the last [WEEKS_KEPT] weeks that group [id] had a part in, each with its age in weeks (0 this week). */
    fun of(id: Int): List<Pair<Int, Dealing>> = recent.withIndex().flatMap { (age, w) -> w.filter { it.from == id || it.to == id }.map { age to it } }

    /** Forgets the dealings (after a load). */
    fun reset() { groups.dealings.clear(); recent = emptyList() }

    /**
     * Draws the dealings between bands at their spots ([at], km, by group id) and, for band [picked] (null none), its ties.
     * [sx] and [sy] take km to the screen. Reads the world: call only while it stands still.
     */
    fun paint(g: Graphics2D, at: Map<Int, Pair<Float, Float>>, picked: HexGroup?, sx: (Float) -> Int, sy: (Float) -> Int) {
        val stroke = g.stroke
        // The picked band's ties: to every band it is tied to that is about.
        val pb = picked?.band
        if (picked != null && pb != null) {
            val from = at[picked.id]
            if (from != null) for ((id, tie) in pb.ties) {
                val to = at[id] ?: continue
                val o = groups.groups.firstOrNull { it.id == id && it.alive } ?: continue
                val ob = o.band ?: continue
                val feud = Regard(pb, id, ob, map = hex).feud
                val w = 1f + 3f * abs(tie)
                // (Dotted, in colours no dealing uses, so a tie is never taken for something done this month.)
                g.stroke = if (feud) BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 4f, floatArrayOf(7f, 5f), 0f)
                    else BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 4f, floatArrayOf(1f, 2f * w + 2f), 0f)
                g.color = if (tie >= 0f) Color(TIE_FOND.red, TIE_FOND.green, TIE_FOND.blue, (255 * (0.35f + 0.6f * min(1f, tie))).toInt())
                    else Color(TIE_COLD.red, TIE_COLD.green, TIE_COLD.blue, (255 * (0.35f + 0.6f * min(1f, -tie))).toInt())
                if (feud) g.color = FEUD_COLOUR
                g.drawLine(sx(from.first), sy(from.second), sx(to.first), sy(to.second))
            }
        }
        // The dealings, oldest first so the newest lie on top; between two bands the weightiest kind of a week only.
        for ((age, week) in recent.withIndex().reversed()) {
            val fade = 1f - age.toFloat() / WEEKS_KEPT
            val best = HashMap<Long, Dealing>()
            for (d in week) {
                val key = min(d.from, d.to).toLong() shl 32 or max(d.from, d.to).toLong()
                val had = best[key]
                if (had == null || WEIGHT[d.kind.ordinal] > WEIGHT[had.kind.ordinal]) best[key] = d
            }
            for (d in best.values) {
                val a = at[d.from] ?: continue; val b = at[d.to] ?: continue
                val x0 = sx(a.first); val y0 = sy(a.second); val x1 = sx(b.first); val y1 = sy(b.second)
                if (x0 == x1 && y0 == y1) continue
                val c = COLOURS[d.kind.ordinal]
                g.color = Color(c.red, c.green, c.blue, (255 * (if (d.kind == Dealing.Kind.MET || d.kind == Dealing.Kind.TOLD) 0.35f else 0.9f) * fade).toInt())
                g.stroke = BasicStroke(if (d.kind == Dealing.Kind.RAID) 2.5f else 1.6f)
                g.drawLine(x0, y0, x1, y1)
                // (An arrowhead where one acted on the other.)
                if (d.kind != Dealing.Kind.MET && d.kind != Dealing.Kind.TOLD && d.kind != Dealing.Kind.PARTNERS && d.kind != Dealing.Kind.RITE) {
                    val len = hypot((x1 - x0).toDouble(), (y1 - y0).toDouble())
                    if (len > 12) {
                        val ang = atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())
                        val mx = x0 + ((x1 - x0) * 0.6).toInt(); val my = y0 + ((y1 - y0) * 0.6).toInt()
                        for (sgn in intArrayOf(-1, 1)) g.drawLine(mx, my, (mx - 7 * cos(ang + sgn * 0.5)).toInt(), (my - 7 * sin(ang + sgn * 0.5)).toInt())
                    }
                }
            }
        }
        g.stroke = stroke
    }

    /**
     * How band [g] stands toward the bands it has ties with, knows the home of, or dealt with in the last weeks, as an HTML
     * table, the closest first; and its dealings of the last weeks. Reads the world: call only while it stands still.
     */
    fun html(g: HexGroup?): String {
        if (g == null) return page("<p>Pick a band in the list.</p>")
        val b = g.band ?: return page("<p>Not a band.</p>")
        val ids = LinkedHashSet<Int>()
        ids += b.ties.keys; ids += b.knownHomes.keys
        for (w in recent) for (d in w) { if (d.from == g.id) ids += d.to; if (d.to == g.id) ids += d.from }
        ids -= g.id
        val here = hw.placeHex[g.place]
        val sb = StringBuilder()
        sb.append("<p><b>Band #${g.id}</b>: ${b.ties.size} ties, knows ${b.knownHomes.size} homes; renown %.1f.</p>".format(b.renown))
        sb.append("<table cellspacing=0 cellpadding=2 border=1><tr bgcolor=#dde4ee><th>band</th><th>km</th><th>people</th><th>tie</th>" +
            "<th>understood</th><th>kin</th><th>customs apart</th><th>grudge</th><th>wary</th><th>trust</th><th>favours</th><th>lately</th></tr>")
        val rows = ids.map { id -> id to (groups.groups.firstOrNull { it.id == id && it.alive }) }
            .sortedByDescending { b.tie(it.first) }
        for ((id, o) in rows) {
            val ob = o?.band
            val tie = b.tie(id)
            val colour = when { ob != null && Regard(b, id, ob, map = hex).feud -> "#f6c9c4"; tie >= FRIEND_TIE -> "#d3f2d0"; tie < 0f -> "#f8e0d8"; else -> "#ffffff" }
            val dealt = recent.flatten().filter { (it.from == g.id && it.to == id) || (it.to == g.id && it.from == id) }
                .groupingBy { (if (it.from == g.id) "" else "was ") + it.kind.label }.eachCount()
                .entries.joinToString(", ") { (k, n) -> if (n > 1) "$k ×$n" else k }
            sb.append("<tr bgcolor=$colour><td>#$id${if (o == null) " (gone)" else ""}</td>")
            if (o == null || ob == null) {
                val h = b.knownHomes[id]
                sb.append("<td>${if (h != null) "%.0f".format(hex.distanceKm(here, h)) else ""}</td><td></td><td>%.2f</td><td colspan=8>${if (h != null) "home known" else ""} $dealt</td></tr>".format(tie))
                continue
            }
            val r = Regard(b, id, ob, map = hex)
            sb.append("<td>%.0f</td><td>%d</td><td>%.2f</td><td>%.0f%%</td><td>%.0f%%</td><td>%d</td><td>%.1f%s</td><td>%.2f</td><td>%.2f</td><td>%s</td><td>%s</td></tr>".format(
                hex.distanceKm(here, hw.placeHex[o.place]), o.size, tie, 100 * r.understood, 100 * kinship(b.lineage, ob.lineage), r.customsApart,
                r.grudge, if (r.feud) " feud" else "", r.wariness, r.trust,
                b.favours[id]?.let { "%+.1f".format(it) } ?: "", dealt))
        }
        sb.append("</table>")
        sb.append("<p><font color=gray>tie: -1..1, friends above %.1f. understood: their words; kin: chance two people share a founding line; ".format(FRIEND_TIE) +
            "customs apart: of its traditions, totem, burial, rites, way of life and home ground; grudge against their people, a feud above %.0f; ".format(FEUD) +
            "wary: how many times readier than at its least it is to fight them; trust: how far it believes what they tell; favours: + owed to it, - it owes; " +
            "lately: the last $WEEKS_KEPT weeks' dealings.</font></p>")
        return page(sb.toString())
    }

    private fun page(body: String) = "<html><body style=\"font-family: sans-serif; font-size: 10px; margin: 4px\">$body</body></html>"

    companion object {
        /** Weeks of dealings kept and drawn, fading. */
        const val WEEKS_KEPT = 4
        private val FEUD_COLOUR = Color(1f, 0.15f, 0.1f, 0.95f)
        /** The picked band's ties: fond (dotted), and cold (dotted). */
        private val TIE_FOND = Color(0.85f, 1f, 0.75f)
        private val TIE_COLD = Color(0.95f, 0.55f, 0.75f)
        /** Each kind's colour, in [Dealing.Kind]'s order. */
        private val COLOURS = arrayOf(
            Color(1f, 1f, 1f), Color(0.35f, 0.95f, 0.35f), Color(0.95f, 0.9f, 0.7f), Color(0.6f, 0.9f, 0.6f),
            Color(1f, 0.8f, 0.15f), Color(0.3f, 0.8f, 0.9f), Color(1f, 0.5f, 0.8f), Color(0.75f, 0.75f, 0.75f),
            Color(0.75f, 0.45f, 1f), Color(1f, 0.15f, 0.1f), Color(1f, 0.55f, 0.1f))
        /** The colour [k] is drawn in. */
        fun colourOf(k: Dealing.Kind): Color = COLOURS[k.ordinal]
        /** Which kind of a week's dealings between two bands is drawn: the weightiest. */
        private val WEIGHT = intArrayOf(0, 5, 3, 4, 6, 5, 7, 1, 2, 9, 8)

        val legend = MapLegend("Dealings", "Dealings between bands over the last $WEEKS_KEPT weeks, fading with age, from the band that acted to the " +
            "other (arrowheads), between their camps; between two bands in a week, only the weightiest. For the band picked in the list, its ties, dotted " +
            "(standing feelings, however far, not dealings): pale green as far as it is fond of a band, pink as far as it is not, dashed red where it holds a feud against that band's people. " +
            "Far ties are mostly inherited: a daughter band takes its mother's ties when it splits off.",
            swatches = Dealing.Kind.entries.map { MapLegend.Swatch(COLOURS[it.ordinal], it.label) } + listOf(MapLegend.Swatch(TIE_FOND, "tie, fond (picked band, dotted)"),
                MapLegend.Swatch(TIE_COLD, "tie, cold (picked band, dotted)"), MapLegend.Swatch(FEUD_COLOUR, "feud (picked band, dashed)")))
    }
}
