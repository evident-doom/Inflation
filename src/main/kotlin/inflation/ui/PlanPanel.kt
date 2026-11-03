package inflation.ui

import inflation.ecology.Activity
import inflation.ecology.DaysLook
import inflation.ecology.ItemLook
import inflation.ecology.PlanItem
import inflation.ecology.PlanPick
import inflation.ecology.MoveLook
import inflation.ecology.ReckonTerm
import inflation.ecology.Reckoned
import inflation.world.Date
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.event.HyperlinkEvent
import javax.swing.table.AbstractTableModel
import kotlin.math.abs
import kotlin.math.max

/**
 * The Plan tab of the group controls ([GroupControlWindow]), for a planning band ([inflation.ecology.Mind.PLANS]): its week as
 * a turn of a game. On top, the weeks it has planned (newest first), each with whether it stayed or moved and why, and what
 * its spare days went to; below, the week picked, as cards:
 * - Stay or move: its case for leaving, part by part, against the walk to the best place it may go, and every place it
 *   weighed with the rule that let it in or kept it out;
 * - Its days: the week's days as one bar, essentials first, then each tier's items, each with what it asked, what a day of it
 *   was reckoned to save, the bar it had to clear, what it got, and (open it) the numbers behind the reckoning.
 * Everything shown is what the sim reckoned that week ([MoveLook], [DaysLook]); the window only lays it out. A place's
 * "go here" sets the player's pick in the window, as clicking it on the map does.
 */
internal class PlanPanel(
    /** A place index in words (hex, height). */
    private val where: (Int) -> String,
    /** The player picks a place (by its place index), as a click on the map. */
    private val pickPlace: (Int) -> Unit,
    /** The player's picks for the band's next weeks, by [PlanItem] ([GroupControlWindow.planPick]). */
    private val picks: () -> Array<PlanPick>,
    /** The player picks [PlanItem] to be held, dropped or left to the band, from the next week it plans on. */
    private val setPick: (PlanItem, PlanPick) -> Unit,
) {
    /** One planned week: its move (null if it weighed none) and its days (null until the week has run). */
    private class Week(val date: Date, var move: MoveLook?, var days: DaysLook?)

    private val weeks = ArrayList<Week>()
    private var group = -1
    private var shown = 0
    /** The cards opened to their inputs, by name. */
    private val open = HashSet<String>()
    private val view = JEditorPane("text/html", "").apply { isEditable = false }
    private val table = JTable()
    private val model = Weeks()
    private var updating = false
    val component: JPanel = JPanel(BorderLayout())

    init {
        table.model = model
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        table.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        for ((c, w) in intArrayOf(150, 60, 190, 400).withIndex()) table.columnModel.getColumn(c).preferredWidth = w
        table.selectionModel.addListSelectionListener { e ->
            if (e.valueIsAdjusting || updating) return@addListSelectionListener
            if (table.selectedRow >= 0) { shown = table.selectedRow; render() }
        }
        view.addHyperlinkListener { e ->
            if (e.eventType != HyperlinkEvent.EventType.ACTIVATED) return@addHyperlinkListener
            val d = e.description ?: return@addHyperlinkListener
            when {
                d.startsWith("open:") -> { val k = d.removePrefix("open:"); if (!open.remove(k)) open += k; render() }
                d.startsWith("go:") -> d.removePrefix("go:").toIntOrNull()?.let(pickPlace)
                d.startsWith("pick:") -> {
                    val parts = d.removePrefix("pick:").split(":")
                    val item = PlanItem.entries.firstOrNull { it.name == parts.getOrNull(0) }
                    val p = PlanPick.entries.firstOrNull { it.name == parts.getOrNull(1) }
                    if (item != null && p != null) { setPick(item, p); render() }
                }
            }
        }
        val top = JPanel(BorderLayout())
        top.add(JLabel("  Weeks it planned, newest first (pick one to see its reckoning)"), BorderLayout.NORTH)
        top.add(JScrollPane(table), BorderLayout.CENTER)
        component.add(JSplitPane(JSplitPane.VERTICAL_SPLIT, top, JScrollPane(view)).apply { resizeWeight = 0.25 }, BorderLayout.CENTER)
    }

    /** The followed group changed: its record starts afresh. */
    fun clear() { weeks.clear(); group = -1; shown = 0; refresh() }

    /**
     * What the sim kept of group [id]'s latest reckonings ([move], [days]; null for none): a week's move comes as it chooses,
     * its days once the week has run. [planner]: whether it is a planning band at all.
     */
    fun update(id: Int, planner: Boolean, move: MoveLook?, days: DaysLook?) {
        if (id != group) { weeks.clear(); group = id; shown = 0 }
        if (!planner) { weeks.clear(); refresh(notPlanner = true); return }
        fun weekOf(d: Date): Week {
            weeks.firstOrNull { it.date == d }?.let { return it }
            return Week(d, null, null).also { weeks.add(0, it); while (weeks.size > KEPT_WEEKS) weeks.removeAt(weeks.size - 1) }
        }
        if (move != null && move.group == id) weekOf(move.date).move = move
        if (days != null && days.group == id) weekOf(days.date).days = days
        refresh()
    }

    private fun refresh(notPlanner: Boolean = false) {
        updating = true
        model.fireTableDataChanged()
        if (shown >= weeks.size) shown = 0
        if (weeks.isNotEmpty()) table.setRowSelectionInterval(shown, shown)
        updating = false
        if (notPlanner) view.text = html("<p>This group does not plan: it is a band run by the networks, or not a band. Planning bands are " +
            "those set by <b>t.planners</b>.</p>") else render()
    }

    private fun render() {
        val w = weeks.getOrNull(shown)
        if (w == null) { view.text = html("<p>No planned week yet. Step a week: its choice of camp shows as it is made, its days once the week has run.</p>"); return }
        val sb = StringBuilder()
        sb.append("<h2>").append(w.date).append("</h2>")
        sb.append(moveCard(w.move))
        sb.append(daysCard(w.days))
        view.text = html(sb.toString())
        view.caretPosition = 0
    }

    // ---- Stay or move ----

    private fun verdict(m: MoveLook): Pair<String, String> {
        val moved = m.chosen >= 0 && m.chosen != m.stays
        val title = if (moved) "MOVES to " + where(m.places[m.chosen]) else if (m.chosen < 0) "NO PLACE COULD BE WEIGHED" else "STAYS"
        val why = when (m.why) {
            Reckoned.OWED -> "its customs send it (an appointment due, a raid set, or a camp left after a death): it goes to the best place no more than a survival step deadlier than the safest"
            Reckoned.EMERGENCY -> "staying is at least a survival step deadlier than the safest place: it flees to the best place no more than a step deadlier than the safest"
            Reckoned.NO_CASE -> "it has no case to judge: no whole week at this camp yet, or no record of such weeks"
            Reckoned.COSTS_MORE -> "its case for leaving is not above zero once what leaving gives up is taken off"
            Reckoned.UNSCORED -> "no scores came back for the places"
            Reckoned.ONLY_DEADLIER -> "every other place it may go is deadlier than staying"
            Reckoned.NOWHERE -> "there is no other place to go"
            Reckoned.WALK_COSTS_MORE -> "the walk to the best place costs more than its case for leaving"
            Reckoned.MOVED -> "its case for leaving is more than the walk to the best place"
            null -> "(no reason kept)"
        }
        return title to why
    }

    private fun moveCard(m: MoveLook?): String {
        val sb = StringBuilder()
        sb.append(card("Stay or move"))
        if (m == null) return sb.append("<p>It weighed no move this week (it did not choose its place this week, or the week had not yet come to it).</p>").append(END).toString()
        val (title, why) = verdict(m)
        val moved = m.chosen >= 0 && m.chosen != m.stays
        sb.append(banner(title, why, if (moved) GOLD else GREEN))
        // The case, part by part.
        val t = m.terms
        fun term(r: ReckonTerm) = t.getOrElse(r.ordinal) { Float.NaN }
        if (!m.owed && !term(ReckonTerm.CASE).isNaN()) {
            sb.append("<p><b>Its case for leaving</b>, in shares of a week of its hands' work (0.10 = a tenth of a week), each loss paid over the %.1f weeks it expects to keep a camp:</p>".format(m.stayWeeks))
            sb.append("<table cellpadding=2 cellspacing=0 border=0>")
            fun row(label: String, v: Float, sign: String, note: String, bold: Boolean = false) {
                val b0 = if (bold) "<b>" else ""; val b1 = if (bold) "</b>" else ""
                sb.append("<tr><td>$b0$label$b1</td><td align=right>$sign</td><td align=right>$b0${num(v)}$b1</td><td><font color=gray>$note</font></td></tr>")
            }
            row("this camp's work", term(ReckonTerm.CAMP), "", "share of the week its foraging took to feed it lately (last weeks here)")
            row("a fresh camp's usual work", term(ReckonTerm.USUAL), "&minus;", "from news of other bands' yields this month, else its own same weeks in past years")
            row("case", term(ReckonTerm.CASE), "=", "", bold = true)
            row("its houses here", term(ReckonTerm.HOUSE), "&minus;", "left to weather and to others")
            row("its crop not yet reaped", term(ReckonTerm.CROP), "&minus;", "lost harvest")
            row("its heavy tools", term(ReckonTerm.TOOLS), "&minus;", "days of making them again")
            row("its weir", term(ReckonTerm.WEIR), "&minus;", "food the weir takes each week away")
            row("gain from leaving", m.gain, "=", "moves only if above the walk to the best place", bold = true)
            sb.append("</table>")
        } else if (m.owed) sb.append("<p>Owed by custom: it does not weigh a case, it goes.</p>")
        else sb.append("<p>No case judged: too new to this camp, or no record of such weeks to compare with.</p>")
        // The places.
        sb.append(placesTable(m))
        return sb.append(END).toString()
    }

    /** The places it weighed, each with the rule that let it in or kept it out ([inflation.ecology.plannedMove], as it reads). */
    private fun placesTable(m: MoveLook): String {
        val n = m.places.size
        var safest = Float.POSITIVE_INFINITY
        for (k in 0 until n) if (m.handScores[k].isFinite() && m.deaths[k].isFinite() && m.deaths[k] < safest) safest = m.deaths[k]
        fun admitted(k: Int) = m.handScores[k].isFinite() && m.deaths[k].isFinite() && m.deaths[k] - safest <= 1f
        val settled = !m.owed && m.stays >= 0 && m.deaths[m.stays].isFinite() && m.deaths[m.stays] - safest < 1f
        val sc = m.scores
        fun status(k: Int): Pair<String, String> = when {
            k == m.chosen && k == m.stays -> "stays here" to GREEN
            k == m.chosen -> "goes here" to GOLD
            k == m.stays -> "where it stands" to LIGHT
            !admitted(k) -> "out: over a survival step deadlier than the safest" to GREY
            sc == null -> "not scored: it never weighed leaving" to LIGHT
            !(sc.getOrNull(k) ?: Float.NaN).isFinite() -> "out: no score" to GREY
            settled && m.deaths[k] > m.deaths[m.stays] -> "out: deadlier than staying" to GREY
            settled && m.chosen == m.stays && m.why == Reckoned.WALK_COSTS_MORE && isBest(m, k, ::admitted, settled) -> "best, but the walk costs more than the case" to ORANGE
            else -> "lower score" to LIGHT
        }
        val order = (0 until n).sortedWith(compareBy<Int>({ it != m.chosen }, { it != m.stays }, { -(sc?.getOrNull(it) ?: m.handScores[it]) }))
        val sb = StringBuilder()
        sb.append("<p><b>Places weighed</b> (%d). Survival steps: the lives a place would cost it, in the steps it reckons survival by; ".format(n) +
            "safest %s%s.</p>".format(num(safest), if (m.stays >= 0) ", here " + num(m.deaths[m.stays]) else ""))
        sb.append("<table cellpadding=2 cellspacing=0 border=1><tr bgcolor=$HEAD><th>place</th><th>km</th><th>survival steps</th><th>hand-written</th>" +
            "<th>score it went by</th><th>walk (week shares)</th><th>gain &minus; walk</th><th>verdict</th><th></th></tr>")
        for (k in order.take(MAX_PLACES)) {
            val (s, colour) = status(k)
            val net = if (m.gain.isNaN() || m.walks[k].isNaN() || k == m.stays) "" else num(m.gain - m.walks[k])
            sb.append("<tr bgcolor=$colour><td>${where(m.places[k])}</td><td align=right>%.1f</td><td align=right>${num(m.deaths[k])}</td>".format(m.km[k]) +
                "<td align=right>${num(m.handScores[k])}</td><td align=right>${sc?.let { num(it[k]) } ?: "-"}</td>" +
                "<td align=right>${if (k == m.stays) "" else num(m.walks[k])}</td><td align=right>$net</td><td>$s</td>" +
                "<td>${if (k == m.stays) "" else "<a href=\"go:${m.places[k]}\">go here</a>"}</td></tr>")
        }
        sb.append("</table>")
        if (n > MAX_PLACES) sb.append("<p><font color=gray>(%d more not shown)</font></p>".format(n - MAX_PLACES))
        return sb.toString()
    }

    /** Whether place [k] is the best of those it might leave for ([plannedMove]'s choice among the open places no deadlier than staying). */
    private fun isBest(m: MoveLook, k: Int, open: (Int) -> Boolean, settled: Boolean): Boolean {
        val sc = m.scores ?: return false
        var best = -1
        for (j in m.places.indices) if (open(j) && sc[j].isFinite() && (!settled || (j != m.stays && m.deaths[j] <= m.deaths[m.stays])) && (best < 0 || sc[j] > sc[best])) best = j
        return best == k
    }

    // ---- Its days ----

    private fun daysCard(d: DaysLook?): String {
        val sb = StringBuilder()
        sb.append(card("Its days this week"))
        if (d == null) return sb.append("<p>Shown once the week has run (a band sets its days as it forages, after it moves).</p>").append(END).toString()
        val ess = if (d.planned) d.essentialsHeld.sum() else d.essentials.sum()
        val owed = d.customsPaid + d.ornamentsPaid
        val lives = d.items.filter { it.unit == "lives" }.sumOf { it.heldDays.toDouble() }.toFloat()
        val gens = d.items.filter { it.unit == "days" }.sumOf { it.heldDays.toDouble() }.toFloat()
        val unr = d.unreckoned.sumOf { it.given.toDouble() }.toFloat()
        sb.append("<p><b>Playing it:</b> each item beyond the essentials has <i>model &middot; hold &middot; drop</i>. Hold gives it days as far as it asks " +
            "(first of its tier) whatever its reckoning; drop gives it none. Your picks take hold from the next week it plans and stay until you change them " +
            "or press <i>Undo my changes</i>. Where it goes is picked on the map, the place list, or <i>go here</i> above.</p>")
        sb.append("<p>%d people; %.0f person-days of work this week. One bar, in the order they were held:</p>".format(d.people, d.total))
        sb.append(bar(d.total, listOf(
            Triple("walk and water", d.walkAndWater, "#9e9e9e"), Triple("essentials", ess, "#e57373"), Triple("owed by custom", owed, "#ba68c8"),
            Triple("lives this year", lives, "#4fc3f7"), Triple("days over generations", gens, "#81c784"), Triple("no reckoning yet", unr, "#ffd54f"),
            Triple("leisure", d.leisure, "#e0e0e0"))))
        // Tier 0: the essentials.
        sb.append(tier("Essentials, held first", "the week's food, firewood, shelter and clothes as the survival pass gave them, and its herders: by deaths averted, never weighed against the rest"))
        sb.append("<p>")
        sb.append(Activity.entries.filter { d.essentials[it.ordinal] > 0.05f || d.essentialsHeld[it.ordinal] > 0.05f }.joinToString(" &middot; ") { a ->
            val asked = d.essentials[a.ordinal]; val held = d.essentialsHeld[a.ordinal]
            if (!d.planned) "%s <b>%.1f</b> asked".format(a.label, asked)
            else if (held + 0.05f < asked) "%s <b>%.1f</b> of %.1f asked <font color=#b00000>(short)</font>".format(a.label, held, asked)
            else "%s <b>%.1f</b>".format(a.label, held)
        }.ifEmpty { "none" })
        sb.append(" days held; %.1f days left for the rest.</p>".format(d.afterEssentials))
        if (d.planned && d.essentials.sum() > d.essentialsHeld.sum() + 0.05f)
            sb.append("<p><font color=#b00000>The essentials asked %.1f days, more than the %.1f the week had after the walk and water: warmth was held first, then the week's needs, then herders, and nothing was left for the tiers below.</font></p>".format(
                d.essentials.sum(), d.total - d.walkAndWater))
        // Tier 1: owed.
        sb.append(tier("Owed by custom", "rites, burials, keeping clean and nursing where it holds to them, its ornaments: belief decides them, not lives"))
        sb.append("<p>customs %s of %s asked%s &middot; %s<br>ornaments %s of %s asked%s &middot; %s</p>".format(num(d.customsPaid), num(d.customsAsked),
            heldTo(d, PlanItem.CUSTOMS), pickLinks(PlanItem.CUSTOMS), num(d.ornamentsPaid), num(d.ornamentsAsked), heldTo(d, PlanItem.ORNAMENTS), pickLinks(PlanItem.ORNAMENTS)))
        // Tiers 2 and 3: the scored items.
        sb.append(tier("Held by lives saved this year", "a day of work goes where it saves the most lives, while it saves any"))
        for (it in d.items.filter { it.unit == "lives" }) sb.append(itemCard(d, it))
        sb.append(tier("Held by days saved over generations", "houses, weirs and tools, the one that saves most days for its days first, while it saves more days than it takes (bar 1)"))
        for (it in d.items.filter { it.unit == "days" }.sortedBy { it.rank }) sb.append(itemCard(d, it))
        if (d.tools.isNotEmpty()) {
            sb.append("<p><b>Each tool</b> it knows and lacks of a whole kit:</p><table cellpadding=2 cellspacing=0 border=1><tr bgcolor=$HEAD><th>tool</th><th>kit</th>" +
                "<th>days saved a year (whole kit)</th><th>lasts, y</th><th>days to make</th><th>saved / made</th><th>verdict</th></tr>")
            for (t in d.tools) sb.append("<tr bgcolor=${if (t.pays) GREEN else GREY}><td>${t.tool.name.lowercase()}</td><td align=right>%.0f%%</td>".format(100 * t.kit) +
                "<td align=right>${num(t.savedAYear)}</td><td align=right>${num(t.lastsYears)}</td><td align=right>${num(t.makeDays)}</td>" +
                "<td align=right><b>${num(t.ratio)}</b></td><td>${if (t.pays) "pays: made" else "below 1: not made"}</td></tr>")
            sb.append("</table>")
        }
        // Tier 4: unreckoned, then leisure.
        sb.append(tier("No reckoning yet", "shared out at equal weights of what is left"))
        sb.append("<table cellpadding=2 cellspacing=0 border=1><tr bgcolor=$HEAD><th>chore</th><th>asked</th><th>given</th><th>your pick</th></tr>")
        for (c in d.unreckoned) if (c.asked > 0.05f || c.given > 0.05f || picks()[c.item.ordinal] != PlanPick.MODEL || d.picks[c.item.ordinal] != PlanPick.MODEL)
            sb.append("<tr><td>${c.name}${heldTo(d, c.item)}</td><td align=right>${num(c.asked)}</td><td align=right>${num(c.given)}</td><td>${pickLinks(c.item)}</td></tr>")
        sb.append("</table>")
        sb.append(tier("Leisure", "what is left: no chore takes it"))
        sb.append("<p><b>%.1f</b> days</p>".format(d.leisure))
        return sb.append(END).toString()
    }

    /** This week's own pick for [item], where the player's held it ([DaysLook.picks]). */
    private fun heldTo(d: DaysLook, item: PlanItem) = d.picks[item.ordinal].let { if (it == PlanPick.MODEL) "" else " <font color=#b35c00><b>(your %s)</b></font>".format(it.label) }

    /** The player's pick for [item] from the next week: model, hold, drop, the current one in bold. */
    private fun pickLinks(item: PlanItem): String {
        val now = picks()[item.ordinal]
        return "next weeks: " + PlanPick.entries.joinToString(" &middot; ") { p ->
            if (p == now) "<b>[${p.label}]</b>" else "<a href=\"pick:${item.name}:${p.name}\">${p.label}</a>" }
    }

    private fun itemCard(d: DaysLook, it: ItemLook): String {
        val (verdict, colour) = when {
            d.picks[it.item.ordinal] == PlanPick.DROP -> "DROPPED by you" to ORANGE
            it.heldDays > 0.005f -> "HELD %.1f of %.1f days asked".format(it.heldDays, it.askDays) to GREEN
            it.askDays <= 0.005f -> "nothing asked" to LIGHT
            it.rate <= it.bar -> "NOT HELD: saves %s, not above the bar %s".format(num(it.rate), num(it.bar)) to GREY
            else -> "NOT HELD: worth it, but no days were left" to ORANGE
        }
        val unit = if (it.unit == "lives") "lives a day of work saves" else "days saved a day of work"
        val key = "item:" + it.name.replace(' ', '_')
        val sb = StringBuilder()
        sb.append("<table width=100% cellpadding=4 cellspacing=0 border=1><tr bgcolor=$colour><td><b>${it.name}</b>${heldTo(d, it.item)} &middot; $verdict &middot; ${pickLinks(it.item)}<br>")
        sb.append("asked <b>%s</b> days &middot; %s: <b>%s</b> (bar %s) &middot; weighed %s &middot; <a href=\"open:%s\">%s</a>".format(
            num(it.askDays), unit, num(it.rate), num(it.bar), ordinal(it.rank), key, if (key in open) "hide the reckoning" else "show the reckoning"))
        if (key in open) {
            sb.append("<table cellpadding=1 cellspacing=0 border=0>")
            if (it.inputs.isEmpty()) sb.append("<tr><td><font color=gray>not reckoned this week (nothing asked, or nothing it could gain)</font></td></tr>")
            for ((l, v) in it.inputs) sb.append("<tr><td>$l</td><td><b>$v</b></td></tr>")
            sb.append("</table>")
        }
        return sb.append("</td></tr></table><br>").toString()
    }

    // ---- The history table ----

    private inner class Weeks : AbstractTableModel() {
        private val cols = arrayOf("week", "camp", "why", "its spare days went to")
        override fun getRowCount() = weeks.size
        override fun getColumnCount() = cols.size
        override fun getColumnName(c: Int) = cols[c]
        override fun getValueAt(r: Int, c: Int): Any {
            val w = weeks[r]
            val m = w.move
            return when (c) {
                0 -> "y%d m%d d%d".format(w.date.year, w.date.month + 1, w.date.dayOfMonth)
                1 -> if (m == null) "-" else if (m.chosen >= 0 && m.chosen != m.stays) "MOVE" else "stay"
                2 -> m?.why?.label ?: ""
                else -> w.days?.let { d ->
                    (d.items.filter { it.heldDays > 0.05f }.map { "%s %.0f".format(it.name, it.heldDays) } +
                        listOf("leisure %.0f".format(d.leisure))).joinToString(", ")
                } ?: "(week not run yet)"
            }
        }
    }

    // ---- Pieces of HTML ----

    private fun html(body: String) = "<html><body style=\"font-family: sans-serif; font-size: 11px; margin: 6px\">$body</body></html>"
    private fun card(title: String) = "<table width=100% cellpadding=6 cellspacing=0 border=1><tr bgcolor=$HEAD><td><font size=+1><b>$title</b></font></td></tr><tr><td>"
    private fun banner(title: String, why: String, colour: String) =
        "<table width=100% cellpadding=6 cellspacing=0 border=0><tr bgcolor=$colour><td><font size=+1><b>$title</b></font><br>$why</td></tr></table>"
    private fun tier(title: String, note: String) = "<p><b>$title</b> <font color=gray>&mdash; $note</font></p>"
    private fun bar(total: Float, parts: List<Triple<String, Float, String>>): String {
        val sb = StringBuilder("<table width=100% cellpadding=3 cellspacing=0 border=0><tr>")
        val t = max(total, 1e-3f)
        for ((_, v, colour) in parts) if (v > 0.005f * t) sb.append("<td bgcolor=$colour width=%d%%>&nbsp;</td>".format(max(1, (100 * v / t).toInt())))
        sb.append("</tr></table><p>")
        sb.append(parts.joinToString(" &middot; ") { (label, v, colour) -> "<font color=$colour>&#9632;</font> $label <b>%.1f</b>".format(v) })
        return sb.append("</p>").toString()
    }
    private fun num(v: Float): String = when {
        v.isNaN() -> "-"
        v.isInfinite() -> if (v > 0) "&infin;" else "&minus;&infin;"
        v == 0f -> "0"
        abs(v) >= 100f -> "%.0f".format(v)
        abs(v) >= 1f -> "%.2f".format(v)
        abs(v) >= 0.001f -> "%.3f".format(v)
        else -> "%.2e".format(v)
    }
    private fun ordinal(k: Int) = when (k) { 0 -> "first"; 1 -> "second"; 2 -> "third"; 3 -> "fourth"; 4 -> "fifth"; else -> "${k + 1}th" }

    private companion object {
        const val KEPT_WEEKS = 104
        const val MAX_PLACES = 30
        const val END = "</td></tr></table><br>"
        const val HEAD = "#d7dee8"
        const val GREEN = "#c8e6c9"
        const val GOLD = "#ffe082"
        const val ORANGE = "#ffcc80"
        const val GREY = "#eeeeee"
        const val LIGHT = "#ffffff"
    }
}
