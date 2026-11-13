package inflation.ui

import inflation.ecology.Catalogue
import inflation.ecology.HexGroup
import inflation.ecology.HexSim
import inflation.ecology.Illness
import inflation.ecology.Material
import inflation.ecology.Skill
import inflation.ecology.Store
import inflation.ecology.Tool
import inflation.ecology.Tradition
import inflation.ecology.livelihood
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.JPanel
import javax.swing.Scrollable
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * A band's state drawn as bars, for the side panel: its people, how it fares, its larder by kind of store against what it aims
 * to keep, its cache, kept animals, tools, goods, skills, home and customs. [show] takes a snapshot of the group as it stands
 * (call only while the world stands still, see [WorldLock]); painting reads only the snapshot.
 */
internal class BandCard(private val sim: HexSim) : JPanel(), Scrollable {
    /** A bar: its label, its parts (value, colour) stacked from the left over [full], a mark at [mark] (NaN none), and what it says. */
    private class Row(val label: String, val parts: List<Pair<Float, Color>>, val full: Float, val text: String, val mark: Float = Float.NaN)
    private class Section(val title: String, val rows: List<Row>, val chips: List<String> = emptyList())

    @Volatile private var sections: List<Section> = emptyList()
    @Volatile private var title = ""

    init { background = Color(0x24282c); isOpaque = true }

    /** Shows group [g] (null: none). */
    fun show(g: HexGroup?) {
        if (g == null) { sections = emptyList(); title = ""; revalidate(); repaint(); return }
        title = "%s #%d".format(g.species.name, g.id)
        sections = build(g)
        revalidate(); repaint()
    }

    private fun build(g: HexGroup): List<Section> {
        val s = g.species
        val out = ArrayList<Section>()
        val b = g.band
        val elders = b?.elders ?: 0
        out += Section("People", listOf(
            Row("people", listOf((g.adults - elders).toFloat() to ADULTS, elders.toFloat() to ELDERS, g.young.toFloat() to YOUNG),
                max(g.size, s.maxGroupSize).toFloat(), "%d grown%s, %d young".format(g.adults, if (b != null) " (%d elders)".format(elders) else "", g.young),
                mark = s.maxGroupSize.toFloat())))
        val cond = ArrayList<Row>()
        cond += Row("fed", listOf(g.fed to fedColour(g.fed)), 1f, "%.0f%% last week".format(100 * g.fed))
        cond += Row("reserves", listOf(g.fat to fedColour(g.fat / s.maxFatDays.coerceAtLeast(1f))), s.maxFatDays.coerceAtLeast(1f),
            "%.0f of %.0f days".format(g.fat, s.maxFatDays))
        cond += Row("thirst", listOf(g.dry to WATER), s.daysWithoutWater.toFloat().coerceAtLeast(1f), "drank %.1f d ago".format(g.dry))
        if (b != null) {
            cond += Row("able hands", listOf(b.ableShare to ADULTS), 1f, "%.0f%% (wounded %.0f%%, lamed %.0f%%)".format(100 * b.ableShare, 100 * b.hurt, 100 * b.lame))
            for (i in Illness.entries) if (b.sick[i] > 0.005f) cond += Row(i.label, listOf(b.sick[i] to SICK), 1f, "%.0f%% sick".format(100 * b.sick[i]))
        }
        out += Section("How it fares", cond)
        if (b == null) return out

        // Food: the larder by store against the days it aims to keep, the cache, seed corn, kept animals.
        val need = g.dailyNeedKg.coerceAtLeast(1e-3f)
        val aimKg = b.storeAim * need
        val larder = Store.entries.map { g.stores[it] to STORE_COLOURS[it.ordinal] }
        val food = ArrayList<Row>()
        food += Row("larder", larder, max(g.larder, aimKg).coerceAtLeast(1f),
            "%.0f kg, %.0f d (aim %.0f d)".format(g.larder, g.larder / need, b.storeAim), mark = aimKg)
        if (b.cacheKg >= 1f) food += Row("cache", Store.entries.map { b.cache[it] to STORE_COLOURS[it.ordinal] }, maxOf(g.larder, aimKg, b.cacheKg),
            "%.0f kg at %s".format(b.cacheKg, if (b.cachePlace.none) "?" else "hex %d".format(sim.hw.placeHex[b.cachePlace.i].i)))
        if (b.seedCorn >= 1f) food += Row("seed corn", listOf(b.seedCorn to STORE_COLOURS[Store.SEED.ordinal]), max(b.seedCorn, b.seedTarget).coerceAtLeast(1f),
            "%.0f kg (aims for %.0f)".format(b.seedCorn, b.seedTarget), mark = b.seedTarget)
        val kinds = sim.fauna.kinds
        val herdMost = (b.herds.herd.indices.maxOfOrNull { b.herds.herd[it] + b.herds.herdYoung.getOrElse(it) { 0f } } ?: 0f).coerceAtLeast(1f)
        for (k in b.herds.herd.indices) {
            val young = b.herds.herdYoung.getOrElse(k) { 0f }
            if (b.herds.herd[k] + young < 0.5f) continue
            val pet = b.herds.herdPet.getOrElse(k) { 0f } > 0f
            food += Row(if (k < kinds.size) kinds[k].name else "kind $k", listOf(b.herds.herd[k] to HERD, young to YOUNG), herdMost,
                "%.0f grown, %.0f young, tame %.0f%%%s".format(b.herds.herd[k], young, 100 * b.herds.herdTame.getOrElse(k) { 0f }, if (pet) ", pets" else ""))
        }
        for (k in b.dogs.kept.indices) if (b.dogs.kept[k] >= 0.5f) {
            val name = if (Catalogue.all[k] == Catalogue.WOLF) "dogs" else "tame " + Catalogue.all[k].name
            food += Row(name, listOf(b.dogs.kept[k] to HERD), max(b.dogs.kept[k], 10f), "%.0f".format(b.dogs.kept[k]))
        }
        out += Section("Food", food)

        // Tools: the share of its people equipped with each it knows, how well made, and spares to give.
        out += Section("Tools", Tool.entries.filter { b.knows[it] }.map { t ->
            Row(t.name.lowercase(), listOf(b.kit[t] * b.made[t] to TOOL, b.kit[t] * (1f - b.made[t]) to TOOL_POOR), 1f,
                "%.0f%% kit, made %.0f%%%s".format(100 * b.kit[t], 100 * b.made[t], if (b.spare[t] >= 0.05f) ", %.1f spare".format(b.spare[t]) else ""))
        })
        // Goods: kg of each material, on a log scale against the most of any.
        val most = Material.entries.maxOf { b.goods[it] }.coerceAtLeast(1f)
        val goods = Material.entries.filter { b.goods[it] >= 0.05f }.map { m ->
            Row(m.name.lowercase().replace('_', ' '), listOf(logShare(b.goods[m], most) to GOODS), 1f, "%.1f kg".format(b.goods[m]))
        }.toMutableList()
        if (b.ornaments >= 0.5f) goods += Row("ornaments", listOf(b.ornaments - b.exotic to GOODS, b.exotic to SHELL), b.ornaments.coerceAtLeast(1f),
            "%.0f, %.0f of sea shell".format(b.ornaments, b.exotic))
        out += Section("Goods", goods)
        out += Section("Skills", Skill.entries.filter { b.knowsSkill[it] }.map { k ->
            Row(k.name.lowercase().replace('_', ' '), listOf(b.skills[k] to SKILL), 1f, "%.0f%%".format(100 * b.skills[k]))
        })
        // Home: its houses, palisade, shelter, how long it has kept its camp, how unevenly fortune lies among its people.
        val home = ArrayList<Row>()
        if (!b.houseHex.none) home += Row("houses", listOf(b.house to WorksPainter.HOUSE_USED), 1f,
            "%.0f%% housed at hex %d%s".format(100 * b.house, b.houseHex.i, if (b.atHome) ", at home" else ""))
        if (b.palisade > 0.01f) home += Row("palisade", listOf(b.palisade to WorksPainter.PALISADE), 1f, "%.0f%%".format(100 * b.palisade))
        home += Row("shelter", listOf(b.shelter to WorksPainter.HOUSE_EMPTY), 1f, "%.0f%%".format(100 * b.shelter))
        home += Row("camp kept", listOf(min(b.campWeeks.toFloat(), 60f) to ADULTS), 60f, "%d weeks".format(b.campWeeks))
        home += Row("unevenness", listOf(b.unevenness to SICK), 1f, "%.2f".format(b.unevenness))
        out += Section("Home", home)
        // Customs, as chips.
        val chips = Tradition.entries.filter { b.holds(it) }.map { it.name.lowercase().replace('_', ' ') }.toMutableList()
        if (!b.totem.none) chips += "totem: " + (if (b.totem.i < kinds.size) kinds[b.totem.i].name else "kind ${b.totem.i}") + if (b.totemBroken > 0f) " (broken)" else ""
        if (b.holds(Tradition.BURIAL)) chips += "buries " + b.burialWay.name.lowercase().replace('_', ' ')
        chips += "lives by " + b.livelihood().name.lowercase() + if (b.heldLivelihood != b.livelihood()) " (holds " + b.heldLivelihood.name.lowercase() + ")" else ""
        chips += "disputes: " + b.disputeWay.name.lowercase().replace('_', ' ')
        out += Section("Customs", emptyList(), chips)
        return out
    }

    private fun logShare(v: Float, most: Float) = if (v <= 0f) 0f else (ln(1f + v) / ln(1f + most)).coerceIn(0f, 1f)

    // Layout.
    private val labelW = 86; private val textW = 170; private val rowH = 15; private val pad = 6
    private val titleFont = Font(Font.SANS_SERIF, Font.BOLD, 13)
    private val headFont = Font(Font.SANS_SERIF, Font.BOLD, 11)
    private val textFont = Font(Font.SANS_SERIF, Font.PLAIN, 11)

    private fun contentHeight(width: Int): Int {
        var h = pad + 18
        for (sec in sections) {
            if (sec.rows.isEmpty() && sec.chips.isEmpty()) continue
            h += 16 + sec.rows.size * rowH
            if (sec.chips.isNotEmpty()) h += chipLines(sec.chips, width) * (rowH + 2)
            h += 4
        }
        return h + pad
    }

    private fun chipLines(chips: List<String>, width: Int): Int {
        val fm = getFontMetrics(textFont)
        var lines = 1; var x = pad
        for (c in chips) { val w = fm.stringWidth(c) + 10; if (x + w > width - pad && x > pad) { lines++; x = pad }; x += w + 4 }
        return lines
    }

    override fun getPreferredSize() = Dimension(380, contentHeight(max(width, 380)))
    override fun getPreferredScrollableViewportSize(): Dimension = Dimension(380, 300)
    override fun getScrollableUnitIncrement(r: Rectangle, o: Int, d: Int) = rowH
    override fun getScrollableBlockIncrement(r: Rectangle, o: Int, d: Int) = r.height - rowH
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = false

    override fun paintComponent(g0: Graphics) {
        super.paintComponent(g0)
        val g = g0 as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        var y = pad
        g.font = titleFont; g.color = Color.WHITE
        g.drawString(title.ifEmpty { "Pick a group in the list" }, pad, y + g.fontMetrics.ascent); y += 18
        val barX = pad + labelW; val barW = (width - barX - textW - pad).coerceAtLeast(40)
        for (sec in sections) {
            if (sec.rows.isEmpty() && sec.chips.isEmpty()) continue
            g.font = headFont; g.color = HEAD
            g.drawString(sec.title, pad, y + 12); g.drawLine(pad + g.fontMetrics.stringWidth(sec.title) + 6, y + 8, width - pad, y + 8)
            y += 16
            g.font = textFont
            for (r in sec.rows) {
                g.color = LABEL; g.drawString(clip(g, r.label, labelW - 4), pad, y + 11)
                g.color = TRACK; g.fillRect(barX, y + 2, barW, rowH - 4)
                var x = barX.toFloat()
                val full = r.full.coerceAtLeast(1e-6f)
                for ((v, c) in r.parts) {
                    if (v <= 0f) continue
                    val w = (barW * v / full).coerceAtMost(barX + barW - x)
                    if (w <= 0f) break
                    g.color = c; g.fillRect(x.toInt(), y + 2, max(1, w.toInt()), rowH - 4); x += w
                }
                if (!r.mark.isNaN() && r.mark > 0f) {
                    val mx = barX + (barW * (r.mark / full)).toInt().coerceIn(0, barW - 1)
                    g.color = Color.WHITE; g.drawLine(mx, y, mx, y + rowH - 1)
                }
                g.color = TEXT; g.drawString(clip(g, r.text, textW - 4), barX + barW + 6, y + 11)
                y += rowH
            }
            if (sec.chips.isNotEmpty()) {
                val fm = g.fontMetrics
                var x = pad
                for (c in sec.chips) {
                    val w = fm.stringWidth(c) + 10
                    if (x + w > width - pad && x > pad) { x = pad; y += rowH + 2 }
                    g.color = CHIP; g.fillRoundRect(x, y + 1, w, rowH, 8, 8)
                    g.color = TEXT; g.drawString(c, x + 5, y + 12)
                    x += w + 4
                }
                y += rowH + 2
            }
            y += 4
        }
    }

    private fun clip(g: Graphics2D, s: String, w: Int): String {
        val fm = g.fontMetrics
        if (fm.stringWidth(s) <= w) return s
        var t = s
        while (t.isNotEmpty() && fm.stringWidth("$t…") > w) t = t.dropLast(1)
        return "$t…"
    }

    companion object {
        private val HEAD = Color(0.75f, 0.8f, 0.9f)
        private val LABEL = Color(0.8f, 0.8f, 0.8f)
        private val TEXT = Color(0.92f, 0.92f, 0.92f)
        private val TRACK = Color(0.18f, 0.2f, 0.22f)
        private val CHIP = Color(0.25f, 0.3f, 0.38f)
        private val ADULTS = Color(0.3f, 0.55f, 0.95f)
        private val ELDERS = Color(0.6f, 0.65f, 0.85f)
        private val YOUNG = Color(0.55f, 0.85f, 0.95f)
        private val WATER = Color(0.2f, 0.45f, 0.9f)
        private val SICK = Color(0.85f, 0.35f, 0.3f)
        private val HERD = Color(0.8f, 0.65f, 0.4f)
        private val TOOL = Color(0.7f, 0.7f, 0.65f)
        private val TOOL_POOR = Color(0.45f, 0.45f, 0.42f)
        private val GOODS = Color(0.75f, 0.55f, 0.35f)
        private val SHELL = Color(0.95f, 0.85f, 0.75f)
        private val SKILL = Color(0.45f, 0.75f, 0.5f)
        /** Each [Store]'s colour, in its order: fresh meat, dried meat, fruit, roots, seed, grease. */
        private val STORE_COLOURS = arrayOf(Color(0.85f, 0.25f, 0.25f), Color(0.6f, 0.2f, 0.15f), Color(0.85f, 0.35f, 0.7f),
            Color(0.7f, 0.5f, 0.25f), Color(0.95f, 0.8f, 0.3f), Color(0.95f, 0.95f, 0.75f))
        /** Red going short, yellow middling, green well. */
        private fun fedColour(x: Float): Color {
            val v = x.coerceIn(0f, 1f)
            return Color((1.6f * (1f - v)).coerceIn(0f, 0.9f), (1.4f * v).coerceIn(0.2f, 0.8f), 0.25f)
        }
    }
}
