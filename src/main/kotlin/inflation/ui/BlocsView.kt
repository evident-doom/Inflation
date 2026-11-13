package inflation.ui

import inflation.ecology.FRIEND_TIE
import inflation.ecology.HexGroup
import inflation.ecology.HexSim
import inflation.world.Hex
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Blocs, as an observer would draw them: bands bound to each other both ways, each fond of the other past [FRIEND_TIE], are
 * of one bloc, and so are the bands bound to those (single linkage). Nothing in the model reads them; it has no structure above
 * the band but villages, so a bloc is only a name for a web of mutual ties, the ground on which larger units would stand.
 * Worked out once a week ([week], on the simulation's thread); drawn from that.
 */
internal class BlocsView(private val sim: HexSim) {
    private val groups = sim.groups; private val hex = sim.hex; private val hw = sim.hw

    /** A bloc: its bands (group ids), the hexes they stand in, its people, and the mutual ties within it (pairs of ids). */
    class Bloc(val ids: IntArray, val hexes: List<Hex>, val people: Int, val links: List<Pair<Int, Int>>)

    @Volatile var blocs: List<Bloc> = emptyList(); private set

    /** After each week (on the simulation's thread): the blocs as the ties stand. */
    fun week() {
        val bands = groups.groups.filter { it.alive && it.band != null }
        val index = HashMap<Int, Int>(); for ((i, g) in bands.withIndex()) index[g.id] = i
        val parent = IntArray(bands.size) { it }
        fun root(i: Int): Int { var r = i; while (parent[r] != r) r = parent[r]; var j = i; while (parent[j] != r) { val n = parent[j]; parent[j] = r; j = n }; return r }
        val links = ArrayList<Pair<Int, Int>>()
        for ((i, g) in bands.withIndex()) {
            val b = g.band ?: continue
            for ((id, tie) in b.ties) {
                if (tie <= FRIEND_TIE || id <= g.id) continue
                val j = index[id] ?: continue
                val back = bands[j].band?.tie(g.id) ?: 0f
                if (back <= FRIEND_TIE) continue
                links += g.id to id
                val ri = root(i); val rj = root(j); if (ri != rj) parent[ri] = rj
            }
        }
        val byRoot = HashMap<Int, ArrayList<HexGroup>>()
        for ((i, g) in bands.withIndex()) byRoot.getOrPut(root(i)) { ArrayList() } += g
        val linksOf = links.groupBy { root(index.getValue(it.first)) }
        blocs = byRoot.filter { it.value.size >= 2 }.map { (r, gs) ->
            Bloc(gs.map { it.id }.toIntArray(), gs.map { hw.placeHex[it.place] }.distinct(), gs.sumOf { it.size }, linksOf[r] ?: emptyList())
        }.sortedByDescending { it.people }
    }

    fun reset() { blocs = emptyList() }

    /**
     * Draws each bloc: its hexes washed and outlined in its colour, its mutual ties as thin lines between the bands where they
     * stand ([at], km, by group id), its bands and people by its largest hex. [sx] and [sy] take km to the screen.
     */
    fun paint(g: Graphics2D, at: Map<Int, Pair<Float, Float>>, sx: (Float) -> Int, sy: (Float) -> Int, onScreen: (Hex, Int) -> Boolean) {
        val stroke = g.stroke; val font = g.font
        val rad = hex.hexKm / sqrt(3.0)
        g.font = font.deriveFont(Font.BOLD, 11f)
        for ((k, bl) in blocs.withIndex()) {
            val c = colour(k)
            for (h in bl.hexes) {
                if (!onScreen(h, 1)) continue
                val (cx, cy) = hex.centreKm(h)
                val xs = IntArray(6) { sx((cx + rad * cos(Math.PI / 6 + it * Math.PI / 3)).toFloat()) }
                val ys = IntArray(6) { sy((cy + rad * sin(Math.PI / 6 + it * Math.PI / 3)).toFloat()) }
                g.color = Color(c.red, c.green, c.blue, 70); g.fillPolygon(xs, ys, 6)
                g.stroke = BasicStroke(2f); g.color = Color(c.red, c.green, c.blue, 220); g.drawPolygon(xs, ys, 6)
            }
            g.stroke = BasicStroke(1.2f); g.color = Color(c.red, c.green, c.blue, 200)
            for ((a, b) in bl.links) {
                val pa = at[a] ?: continue; val pb = at[b] ?: continue
                g.drawLine(sx(pa.first), sy(pa.second), sx(pb.first), sy(pb.second))
            }
            val h = bl.hexes.first()
            if (onScreen(h, 1)) {
                val (cx, cy) = hex.centreKm(h)
                val text = "${bl.ids.size} bands, ${bl.people}"
                val x = sx(cx.toFloat()) + 8; val y = sy(cy.toFloat()) - 10
                g.color = Color(0f, 0f, 0f, 0.6f); g.drawString(text, x + 1, y + 1)
                g.color = c; g.drawString(text, x, y)
            }
        }
        g.stroke = stroke; g.font = font
    }

    fun legend(): MapLegend {
        val top = blocs.take(8)
        val bound = blocs.sumOf { it.ids.size }
        return MapLegend("Blocs", "Blocs, an observer's grouping that nothing in the model reads: bands each fond of the other past a tie of " +
            "%.1f (friends) are of one bloc, and so are the bands bound to those. Each bloc's hexes washed in its colour, its mutual ties as thin lines.".format(FRIEND_TIE),
            note = "${blocs.size} blocs of 2+ bands, $bound bands in them",
            swatches = top.withIndex().map { (k, bl) -> MapLegend.Swatch(colour(k), "${bl.ids.size} bands, ${bl.people} people, ${bl.hexes.size} hexes") })
    }

    private fun colour(k: Int): Color = Color.getHSBColor(((k * 0.618034f + 0.11f) % 1f), 0.65f, 1f)
}
