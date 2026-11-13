package inflation.ui

import inflation.ecology.Built
import inflation.ecology.HexFood
import inflation.ecology.HexSim
import inflation.ecology.Structure
import inflation.ecology.villages
import inflation.world.Hex
import inflation.world.get
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Polygon
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * People's works as they stand in each hex, one mark for each thing there rather than one for all of a kind: every house
 * (filled as far as it stands, dark where a band uses it this week, grey where it stands empty, dashed while it is only
 * begun), the palisade round them, halls, pens, weirs and wells, this year's field (larger for more ground sown), ground worn
 * or cleared, the hex's midden, and a village's ring of bands. Read from the world as it stands; call only while it stands
 * still (see [WorldLock]).
 */
internal class WorksPainter(private val sim: HexSim) {
    private val hex = sim.hex; private val hw = sim.hw; private val land = sim.land; private val groups = sim.groups

    /** One hex's works, gathered from the structures and the fields. */
    private class Works {
        val houses = ArrayList<Structure>()
        var fence = 0f
        val halls = ArrayList<Structure>()
        var penHead = 0f
        var weirs = 0
        var wells = 0
        var fieldHa = 0f; var sownKg = 0f; var ripeKg = 0f; var tame = 0f; var pulse = 0f
        var wornKm2 = 0f; var clearedKm2 = 0f
        var village = 0
        var midden = 0f
        val any get() = houses.isNotEmpty() || fence > 0f || halls.isNotEmpty() || penHead > 0f || weirs > 0 || wells > 0 ||
            fieldHa > 0f || ripeKg > 0f || wornKm2 > 0f || clearedKm2 > 0f || village > 0 || midden >= MIDDEN_SHOWN
    }

    /** Every hex's works (null where there are none), as the world stands. */
    private fun gather(): Array<Works?> {
        val out = arrayOfNulls<Works>(hex.size)
        fun at(h: Hex) = out[h.i] ?: Works().also { out[h.i] = it }
        for (st in groups.bands.built) {
            if (st.hex.none) continue
            val w = at(st.hex)
            when (st.kind) {
                Built.HOUSES -> w.houses += st
                Built.PALISADE -> w.fence = max(w.fence, st.built)
                Built.HALL -> w.halls += st
                Built.PEN -> w.penHead += st.built * st.size
                Built.WEIR -> w.weirs++
                Built.WELL -> w.wells++
            }
        }
        val food = sim.fauna.food
        for (p in 0 until land.n) {
            val cover = food.fieldCover[p]; val m2 = land.areaKm2[p] * 1e6f
            val sown = food.field[p] * m2; val ripe = food.fieldGrain[p] * m2
            val worn = food.fieldWorn[p] * land.areaKm2[p]; val cleared = food.cleared[p] * land.areaKm2[p]
            if (sown <= 0f && ripe <= 0f && worn <= 0f && cleared <= 0f) continue
            val w = at(hw.placeHex[p])
            // (The ground under this year's field: as last sown, or as the seed would cover at the usual rate.)
            w.fieldHa += (if (cover > 0f) cover * land.areaKm2[p] * 100f else sown / HexFood.SEED_RATE / 1e4f).takeIf { sown > 0f || ripe > 0f } ?: 0f
            w.sownKg += sown; w.ripeKg += ripe; w.tame += food.fieldDomestic[p] * sown; w.pulse += food.fieldPulse[p] * sown
            w.wornKm2 += worn; w.clearedKm2 += cleared
        }
        for ((h, bs) in groups.villages()) at(h).village = bs.size
        val midden = groups.bands.midden
        for (h in hex.all) if (midden[h] >= MIDDEN_SHOWN) at(h).midden = midden[h]
        for (h in hex.all) if (out[h.i]?.any == false) out[h.i] = null
        return out
    }

    /**
     * Draws the works of each hex on screen ([onScreen]) inside the hex itself, [sx] and [sy] taking km to the screen at
     * [kmPx] pixels a km: every mark is sized against the hex as drawn, so they shrink and grow with it and stay within it.
     * When a hex is too small on screen for the marks to be told apart, one small square for its houses, one for its field
     * and a dot for a village.
     */
    fun paint(g: Graphics2D, kmPx: Double, sx: (Float) -> Int, sy: (Float) -> Int, onScreen: (Hex, Int) -> Boolean) {
        val works = gather()
        // The hex's corner radius on screen; the marks are laid out in fractions of it.
        val u = (hex.hexKm / sqrt(3.0) * kmPx).toFloat()
        if (u < 1.5f) return
        val stroke = g.stroke; val font = g.font
        g.font = font.deriveFont(Font.BOLD, min(11f, max(7f, 0.18f * u)))
        for (h in hex.all) {
            val w = works[h.i] ?: continue
            if (!onScreen(h, 1)) continue
            val (cx, cy) = hex.centreKm(h)
            val x = sx(cx.toFloat()); val y = sy(cy.toFloat())
            if (u < SMALL_HEX_PX) { compact(g, x, y, u, w); continue }
            fun at(fx: Float, fy: Float) = (x + fx * u).toInt() to (y + fy * u).toInt()
            val line = max(0.6f, 0.05f * u)
            if (w.village > 0) at(0f, 0.6f).let { (px, py) -> village(g, px, py, u, w.village) }
            if (w.houses.isNotEmpty()) at(-0.2f, -0.12f).let { (px, py) -> houses(g, px, py, u, line, w) }
            if (w.halls.isNotEmpty()) at(0f, -0.62f).let { (px, py) -> hall(g, px, py, u, line, w.halls) }
            if (w.penHead > 0f) at(-0.5f, -0.3f).let { (px, py) -> pen(g, px, py, u, line, w.penHead) }
            if (w.wells > 0) at(-0.62f, 0.1f).let { (px, py) -> well(g, px, py, u, line, w.wells) }
            if (w.weirs > 0) at(0.4f, 0.3f).let { (px, py) -> weir(g, px, py, u, line, w.weirs) }
            if (w.midden >= MIDDEN_SHOWN) at(0.15f, 0.12f).let { (px, py) -> midden(g, px, py, u, w.midden) }
            at(-0.15f, 0.15f).let { (px, py) -> field(g, px, py, u, line, w) }
        }
        g.stroke = stroke; g.font = font
    }

    /** A hex too small on screen for the marks: a square for its houses (dark if any in use), one for its field, a dot for a village. */
    private fun compact(g: Graphics2D, x: Int, y: Int, u: Float, w: Works) {
        val s = max(2, (0.4f * u).toInt())
        if (w.houses.isNotEmpty()) { g.color = if (w.houses.any { it.user >= 0 }) HOUSE_USED else HOUSE_EMPTY; g.fillRect(x, y - s, s, s) }
        if (w.fieldHa > 0f || w.ripeKg > 0f) { g.color = if (w.ripeKg > 0f) FIELD_RIPE else FIELD_GROWING; g.fillRect(x - s, y, s, s) }
        if (w.village > 0) { g.color = VILLAGE; g.fillOval(x - s / 2, y + s / 2, s, s) }
    }

    /** A village: a ring of a dot for each band. */
    private fun village(g: Graphics2D, x: Int, y: Int, u: Float, bands: Int) {
        val d = (0.08f * u).toInt().coerceAtLeast(2); val n = min(bands, 8); val ring = 0.13f * u
        for (k in 0 until n) {
            val a = 2 * Math.PI * k / n
            val px = x + (ring * cos(a)).toInt(); val py = y + (ring * sin(a)).toInt()
            g.color = VILLAGE; g.fillOval(px - d / 2, py - d / 2, d, d)
        }
    }

    /**
     * The houses, in a row from ([x0], [y0]) rightwards, whole ones first and the largest first: each its own mark, as
     * wide as the people it shelters ask, filled from the ground up as far as it stands, dark where a band uses it this week,
     * grey where none does, its outline dashed while it has never stood whole. More than [HOUSES_DRAWN]: the rest as "+n".
     * The palisade, if any, a ring round the row, as much of it as stands.
     */
    private fun houses(g: Graphics2D, x0: Int, base: Int, u: Float, line: Float, w: Works) {
        val shown = w.houses.sortedWith(compareByDescending<Structure> { it.done }.thenByDescending { it.size }).take(HOUSES_DRAWN)
        var x = x0
        var top = base
        for (st in shown) {
            val r = (u * (0.06f + 0.025f * ln(1f + st.size / 8f))).toInt().coerceAtLeast(1)
            val hx = x + r
            val poly = Polygon(intArrayOf(hx - r, hx, hx + r, hx + r, hx - r), intArrayOf(base - r, base - 2 * r, base - r, base, base), 5)
            top = min(top, base - 2 * r)
            val stands = st.built.coerceIn(0f, 1f)
            if (stands > 0f) {
                // (Filled from the ground up, as far as it stands.)
                val clip = g.clip
                val hgt = (2 * r * stands).toInt().coerceAtLeast(1)
                g.clipRect(hx - r - 1, base - hgt, 2 * r + 2, hgt + 1)
                g.color = if (st.user >= 0) HOUSE_USED else HOUSE_EMPTY; g.fillPolygon(poly)
                g.clip = clip
            }
            if (!st.done || u >= 20f) {
                g.color = EDGE
                g.stroke = if (st.done) BasicStroke(line) else BasicStroke(line, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 4f, floatArrayOf(2f, 2f), 0f)
                g.drawPolygon(poly)
            }
            x += 2 * r + 1
        }
        val more = w.houses.size - shown.size
        if (more > 0 && u >= 25f) { g.color = EDGE; g.drawString("+$more", x + 1, base) }
        if (w.fence > 0f) {
            // The palisade: an ellipse close round the row of houses, as much of it as stands.
            val pad = max(1, (0.04f * u).toInt())
            g.stroke = BasicStroke(line)
            g.color = PALISADE
            g.drawArc(x0 - pad, top - pad, (x - x0) + 2 * pad, (base - top) + 2 * pad, 90, -(360 * w.fence.coerceIn(0f, 1f)).toInt())
        }
    }

    /** A hall: a long roofed building, wider for more people gathered in it, as far as the largest stands; outlined while never whole. */
    private fun hall(g: Graphics2D, x: Int, y: Int, u: Float, line: Float, halls: List<Structure>) {
        val st = halls.maxBy { it.size * it.built }
        val w = (u * (0.1f + 0.04f * ln(1f + st.size / 30f))).toInt().coerceAtLeast(2); val r = (0.06f * u).toInt().coerceAtLeast(1)
        g.stroke = BasicStroke(line)
        if (st.done && st.built > 0f) {
            g.color = HALL_WALL; g.fillRect(x - w, y, 2 * w, 2 * r)
            g.color = HALL_ROOF; g.fillPolygon(intArrayOf(x - w - 1, x, x + w + 1), intArrayOf(y, y - 2 * r, y), 3)
        } else {
            g.color = HALL_ROOF; g.drawRect(x - w, y, 2 * w, 2 * r); g.drawPolygon(intArrayOf(x - w - 1, x, x + w + 1), intArrayOf(y, y - 2 * r, y), 3)
        }
    }

    /** A pen: a fenced square of stakes, larger for more head. */
    private fun pen(g: Graphics2D, x: Int, y: Int, u: Float, line: Float, head: Float) {
        val r = (u * (0.05f + 0.025f * ln(1f + head / 20f))).toInt().coerceAtLeast(1)
        g.stroke = BasicStroke(line)
        g.color = PEN; g.drawRect(x - r, y - r, 2 * r, 2 * r)
        g.drawLine(x - r, y, x + r, y)
    }

    /** A well: a ring with water in it; a dot beside it for each more. */
    private fun well(g: Graphics2D, x: Int, y: Int, u: Float, line: Float, n: Int) {
        val r = (0.05f * u).toInt().coerceAtLeast(1)
        g.color = WELL_WATER; g.fillOval(x - r, y - r, 2 * r, 2 * r)
        g.stroke = BasicStroke(line); g.color = WELL_STONE; g.drawOval(x - r, y - r, 2 * r, 2 * r)
        val d = max(1, r / 2)
        for (k in 1 until min(n, 4)) g.fillOval(x + r + k * 2 * d, y - d / 2, d, d)
    }

    /** A weir: a fence of stakes across the water, a zigzag; one more line under it for each more. */
    private fun weir(g: Graphics2D, x: Int, y: Int, u: Float, line: Float, n: Int) {
        val s = max(1f, 0.04f * u)
        g.stroke = BasicStroke(line)
        g.color = WEIR
        for (k in 0 until min(n, 3)) {
            val yy = y + (k * 1.6f * s).toInt()
            val xs = IntArray(5) { x + (it * s).toInt() }; val ys = IntArray(5) { yy + if (it % 2 == 0) 0 else -s.toInt() }
            g.drawPolyline(xs, ys, 5)
        }
    }

    /** The midden: a grey heap, larger the more there is. */
    private fun midden(g: Graphics2D, x: Int, y: Int, u: Float, m: Float) {
        val r = (u * (0.03f + 0.05f * m.coerceIn(0f, 1f))).toInt().coerceAtLeast(1)
        g.color = MIDDEN; g.fillArc(x - r, y - r, 2 * r, 2 * r, 0, 180)
    }

    /**
     * This year's field, its top right corner at ([x], [y]): a square larger for more ground under it (log scale,
     * [FIELD_FULL_HA] full), green while it grows, gold as far as ripe grain stands against what the sown ground would bear,
     * ringed white where most of the seed is of ears that hold their grain, a purple corner for pulses. Without one: an
     * outlined, crossed square for ground worn by cropping and left to rest, a pale outlined square for ground cleared and
     * growing back.
     */
    private fun field(g: Graphics2D, x: Int, y: Int, u: Float, line: Float, w: Works) {
        g.stroke = BasicStroke(line)
        if (w.fieldHa > 0f || w.ripeKg > 0f) {
            val s = (u * (0.12f + 0.2f * min(1f, ln(1f + w.fieldHa) / ln(1f + FIELD_FULL_HA)))).toInt().coerceAtLeast(2)
            val fx = x - s; val fy = y
            g.color = FIELD_GROWING; g.fillRect(fx, fy, s, s)
            if (w.ripeKg > 0f) {
                // (Gold from the top down, as far as the ripe crop stands against a full one on that ground.)
                val full = (w.fieldHa * 1e4f * HexFood.SEED_RATE * RIPE_PER_SEED).coerceAtLeast(1f)
                val share = min(1f, w.ripeKg / full)
                g.color = FIELD_RIPE; g.fillRect(fx, fy, s, (s * share).toInt().coerceAtLeast(1))
            }
            if (s >= 6) { g.color = FURROW; g.drawLine(fx, fy + s / 3, fx + s, fy + s / 3); g.drawLine(fx, fy + 2 * s / 3, fx + s, fy + 2 * s / 3) }
            if (w.sownKg > 0f && w.tame / w.sownKg > 0.5f) { g.color = Color.WHITE; g.drawRect(fx - 1, fy - 1, s + 1, s + 1) }
            if (w.sownKg > 0f && w.pulse > 0.05f * w.sownKg) {
                val d = (s * min(1f, 0.3f + w.pulse / w.sownKg)).toInt().coerceAtLeast(2)
                g.color = PULSE; g.fillOval(fx + s - d / 2, fy - d / 2, d, d)
            }
        } else if (w.wornKm2 > 0f) {
            val s = (0.15f * u).toInt().coerceAtLeast(2)
            g.color = WORN; g.drawRect(x - s, y, s, s); g.drawLine(x - s, y + s, x, y)
        } else if (w.clearedKm2 > 0f) {
            val s = (0.15f * u).toInt().coerceAtLeast(2)
            g.color = CLEARED; g.drawRect(x - s, y, s, s)
        }
    }

    /**
     * The works of hex [h] in words, for the inspector: each house (people it shelters, how far it stands, whole or begun, who
     * uses it this week and who began it), the palisade, halls, pens, weirs and wells, the field of each place, worn and cleared
     * ground, the midden and the fouling of camps. Empty where there are none.
     */
    fun describe(h: Hex): String {
        val sb = StringBuilder()
        val here = groups.bands.built.filter { it.hex == h }
        for (st in here.sortedWith(compareBy<Structure> { it.kind }.thenByDescending { it.done }.thenByDescending { it.size })) {
            val stands = "%.0f%% standing%s".format(100 * st.built, if (st.done) "" else ", never yet whole")
            val who = (if (st.user >= 0) "used by #${st.user}" else "unused") + if (st.builder >= 0) ", begun by #${st.builder}" else ""
            sb.appendLine("   " + when (st.kind) {
                Built.HOUSES -> "houses for %.0f: %s; %s".format(st.size, stands, who)
                Built.PALISADE -> "palisade: %.0f%% of the ring stands; %s".format(100 * st.built, who)
                Built.HALL -> "hall for %.0f: %s; %s".format(st.size, stands, who)
                Built.PEN -> "pen for %.0f head: %s; %s".format(st.size, stands, who)
                Built.WEIR -> "weir %.0f m across the river: %s; %s".format(st.size, stands, who)
                Built.WELL -> "well %.0f m deep: %s; %s".format(st.size, stands, who)
            })
        }
        val food = sim.fauna.food
        for (p in hw.placesOf(h)) {
            val a = land.areaKm2[p]; val m2 = a * 1e6f
            val sown = food.field[p] * m2; val ripe = food.fieldGrain[p] * m2
            if (sown > 0f || ripe > 0f) {
                val ha = if (food.fieldCover[p] > 0f) food.fieldCover[p] * a * 100f else sown / HexFood.SEED_RATE / 1e4f
                sb.appendLine("   field at %.0f m: %.1f ha, %.0f kg sown%s%s, %.0f kg ripe standing; held by %s".format(hw.placeElevationM[p], ha, sown,
                    if (sown > 0f) ", %.0f%% holding their grain".format(100 * food.fieldDomestic[p]) else "",
                    if (food.fieldPulse[p] > 0.005f) ", %.0f%% pulses".format(100 * food.fieldPulse[p]) else "", ripe,
                    if (food.fieldHolder[p] >= 0) "#${food.fieldHolder[p]}" else "nobody"))
            }
            if (food.fieldWorn[p] > 0.001f || food.cleared[p] > 0.001f)
                sb.appendLine("   ground at %.0f m: %.1f%% worn by cropping (bearing %.0f%%), %.1f%% cleared".format(hw.placeElevationM[p],
                    100 * food.fieldWorn[p], 100 * food.fieldSoil[p], 100 * food.cleared[p]))
        }
        val bands = groups.bands
        if (bands.midden[h] > 0.005f) sb.appendLine("   midden %.0f%%".format(100 * bands.midden[h]))
        if (bands.fouledWeeks[h] > 0.5f) sb.appendLine("   ground fouled by %.0f weeks of camping".format(bands.fouledWeeks[h]))
        return sb.toString()
    }

    companion object {
        /** Houses drawn one by one in a hex; the rest are counted. */
        const val HOUSES_DRAWN = 4
        /** Below this corner radius in pixels a hex gets only [compact]'s marks. */
        const val SMALL_HEX_PX = 14f
        /** A field's mark is full size at this many hectares. */
        const val FIELD_FULL_HA = 20f
        /** For the mark only: about what a kg of seed ripens to on fair ground (2.4 t/ha at 120 kg/ha). */
        const val RIPE_PER_SEED = 20f
        /** Middens are drawn from this size up (0..1). */
        const val MIDDEN_SHOWN = 0.05f

        val HOUSE_USED = Color(0.45f, 0.25f, 0.1f, 0.95f)
        val HOUSE_EMPTY = Color(0.6f, 0.58f, 0.55f, 0.9f)
        val EDGE = Color(1f, 0.95f, 0.85f, 0.95f)
        val PALISADE = Color(0.3f, 0.15f, 0.05f, 0.95f)
        val HALL_WALL = Color(0.75f, 0.55f, 0.85f, 0.95f)
        val HALL_ROOF = Color(0.35f, 0.15f, 0.45f, 0.95f)
        val PEN = Color(0.85f, 0.6f, 0.3f, 0.95f)
        val WELL_WATER = Color(0.2f, 0.45f, 0.9f, 0.95f)
        val WELL_STONE = Color(0.85f, 0.85f, 0.8f, 0.95f)
        val WEIR = Color(0.55f, 0.85f, 1f, 0.95f)
        val MIDDEN = Color(0.45f, 0.45f, 0.4f, 0.9f)
        val FIELD_GROWING = Color(0.35f, 0.7f, 0.2f, 0.95f)
        val FIELD_RIPE = Color(0.95f, 0.75f, 0.15f, 0.95f)
        val FURROW = Color(0.25f, 0.15f, 0.05f, 0.9f)
        val PULSE = Color(0.5f, 0.2f, 0.6f, 0.95f)
        val WORN = Color(0.55f, 0.4f, 0.25f, 0.8f)
        val CLEARED = Color(0.8f, 0.7f, 0.5f, 0.8f)
        val VILLAGE = Color(0.9f, 0.2f, 0.6f, 0.95f)

        val legend = MapLegend("Works", "People's works, drawn small inside each hex, one mark for each thing standing (zoomed out, only a square for houses, one for a field and a dot for a village). Houses in a row just above the hex's centre, " +
            "each as wide as the people it shelters, filled from the ground up as far as it stands: dark where a band uses it this week, grey where " +
            "none does, dashed while it has never stood whole; past $HOUSES_DRAWN the rest counted as +n. A brown ring round them for a palisade, as much " +
            "of it as stands. A hall at the top of the hex, outlined while never whole. A pen (upper left) larger for more head; a well (left) as a ring of " +
            "stone; a weir (right) as a zigzag of stakes, a line for each; the midden as a grey heap. This year's field (below the centre, left), larger for more " +
            "ground sown (full at ${FIELD_FULL_HA.toInt()} ha), green while it grows and gold from the top as far as ripe grain stands, ringed white " +
            "where most of the seed holds its grain, a purple corner for pulses; a crossed square for ground worn by cropping and resting, a pale " +
            "square for ground cleared and growing back. A village (low in the hex): a ring of a dot for each band.",
            swatches = listOf(MapLegend.Swatch(HOUSE_USED, "house in use"), MapLegend.Swatch(HOUSE_EMPTY, "house standing empty"),
                MapLegend.Swatch(PALISADE, "palisade"), MapLegend.Swatch(HALL_WALL, "hall"), MapLegend.Swatch(PEN, "pen"),
                MapLegend.Swatch(WELL_WATER, "well"), MapLegend.Swatch(WEIR, "weir"), MapLegend.Swatch(MIDDEN, "midden"),
                MapLegend.Swatch(FIELD_GROWING, "field growing"), MapLegend.Swatch(FIELD_RIPE, "ripe grain"), MapLegend.Swatch(PULSE, "pulses sown"),
                MapLegend.Swatch(WORN, "worn ground"), MapLegend.Swatch(VILLAGE, "village")))
    }
}
