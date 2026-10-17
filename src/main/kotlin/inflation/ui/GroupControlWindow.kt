package inflation.ui

import inflation.ecology.ANIMAL_WAYS_OUT
import inflation.ecology.FOOD_TYPES
import inflation.ecology.Food
import inflation.ecology.GroupControl
import inflation.ecology.HexFeatures
import inflation.ecology.HexGroup
import inflation.ecology.HexSim
import inflation.ecology.MoveDecision
import inflation.ecology.Store
import inflation.ecology.Tool
import inflation.ecology.WAYS_OUT
import inflation.learning.Schema
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.util.concurrent.CompletableFuture
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSlider
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import inflation.ecology.Illness
import inflation.ecology.Tradition
import java.util.ArrayDeque
import kotlin.math.atan2
import kotlin.math.hypot
import inflation.ecology.get
import inflation.ecology.set
import inflation.ecology.contains
import inflation.ecology.Place
import inflation.ecology.hexOf
import inflation.ecology.m2

/**
 * The group controls of follow mode: a window beside the overview map that shows the followed group
 * as it decides its week. The local map around it (the country it knows, the places it can reach
 * coloured by how the model scores them, the model's pick and the player's), how the group stands,
 * what it knows of itself and of each place, and its ways for the week; the player may pick another
 * place and pin any of its ways, and "Next step" lets the week run.
 *
 * With "Model decides" ticked the week runs on the model's picks and the window only shows them;
 * otherwise each week waits, at the followed group's choice, for the player. While it waits the rest
 * of the world waits too (saving and loading included). Closed, it takes no part in the run.
 */
class GroupControlWindow(
    private val sim: HexSim,
    private val ground: BufferedImage,
    /** Km to the ground picture's pixels. */
    private val px: Int,
    /** The rivers as they run this week, drawn over the ground. */
    private val rivers: RiverOverlay,
    /** Read the world only while it stands still (see [WorldLock]). */
    private val world: WorldLock,
    /** Lets one more week run (on the simulation's thread). */
    private val requestStep: () -> Unit,
    /** Whether the overview is running weeks on its own. */
    private val running: () -> Boolean,
) : GroupControl {
    private val groups = sim.groups
    private val hex = sim.hex
    private val hw = sim.hw
    private val frame = JFrame("Group controls")

    /** The decision shown, and the one waiting for the player (null when none waits). */
    @Volatile private var decision: MoveDecision? = null
    @Volatile private var waiting: CompletableFuture<Int>? = null
    /** The player's pick, an index into the decision's places. */
    @Volatile private var pick = -1
    /** Whether the week now running was started here (only those wait for the player; the overview's run on). */
    @Volatile private var playerWeek = false
    private fun stepHere() { playerWeek = true; requestStep() }
    /** After the player's week runs, let the next week run up to the group's choice. */
    @Volatile private var startNext = false
    /** The player's pinned ways, by output (NaN: the model's), for the kind of group followed: a band's or a herd's or pack's. */
    @Volatile private var pins = FloatArray(0)
    private var pinsFor = -1

    private val handsOff = JCheckBox("Model decides", false)
    private val reset = JButton("Undo my changes")
    /** Where the followed group went in the last few weeks, newest last (the hexes of each week's way). */
    private val trail = ArrayDeque<IntArray>()
    /** The groups it saw about it when it last chose (to show where they went). */
    @Volatile private var seenBefore = emptySet<Int>()
    private val next = JButton("Next step")
    private val title = JLabel(" ")
    private val note = JLabel(" ")
    private val status = text()
    private val knows = text()
    private val place = text()
    private val lastWeek = text()
    private val map = LocalMap()
    private val table = JTable()
    private val tableModel = Choices()
    private val waysPanel = JPanel(GridBagLayout())
    private var wayRows = emptyList<WayRow>()
    /** Candidate indices in the table's order (best by the model first). */
    private var order = IntArray(0)
    private var updating = false

    init {
        frame.defaultCloseOperation = JFrame.HIDE_ON_CLOSE
        frame.addWindowListener(object : WindowAdapter() { override fun windowClosing(e: WindowEvent) { close() } })
        title.font = title.font.deriveFont(Font.BOLD, 15f)
        handsOff.toolTipText = "Ticked, the group goes where the model chooses and the week runs without waiting; the window shows its choices"
        next.toolTipText = "Go with the picks shown (yours where you changed them, else the model's) and run the week"
        next.font = next.font.deriveFont(Font.BOLD, 14f)
        next.addActionListener { nextStep() }
        handsOff.addActionListener { if (handsOff.isSelected) release() else startIfIdle(); refresh() }
        reset.toolTipText = "Drop your changes this week: back to the model's place and ways"
        reset.addActionListener { decision?.let { pick = it.model }; pins = FloatArray(pins.size) { Float.NaN }; refresh() }

        table.model = tableModel
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        table.autoResizeMode = JTable.AUTO_RESIZE_OFF
        for ((c, w) in intArrayOf(50, 190, 70, 90, 50, 70, 55, 50).withIndex()) table.columnModel.getColumn(c).preferredWidth = w
        table.selectionModel.addListSelectionListener { e ->
            if (e.valueIsAdjusting || updating) return@addListSelectionListener
            val r = table.selectedRow; if (r in order.indices) { choose(order[r]) }
        }
        val choices = JSplitPane(JSplitPane.VERTICAL_SPLIT, JScrollPane(table), JScrollPane(place)).apply { resizeWeight = 0.6 }
        val tabs = JTabbedPane()
        tabs.addTab("Status", JScrollPane(status))
        tabs.addTab("Where to go", choices)
        tabs.addTab("Ways", JScrollPane(waysPanel))
        tabs.addTab("What it knows", JScrollPane(knows))
        tabs.addTab("Last week", JScrollPane(lastWeek))

        val bar = JPanel(BorderLayout())
        val buttons = JPanel(); buttons.add(handsOff); buttons.add(reset); buttons.add(next)
        bar.add(note, BorderLayout.CENTER); bar.add(buttons, BorderLayout.EAST)
        val left = JPanel(BorderLayout()); left.add(map, BorderLayout.CENTER)
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, tabs).apply { resizeWeight = 0.5 }
        frame.contentPane.add(title, BorderLayout.NORTH)
        frame.contentPane.add(split, BorderLayout.CENTER)
        frame.contentPane.add(bar, BorderLayout.SOUTH)
        frame.setSize(1250, 760)
    }

    private fun text() = JTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12); (caret as javax.swing.text.DefaultCaret).updatePolicy = javax.swing.text.DefaultCaret.NEVER_UPDATE
    }

    /** Follows group [id] in the window (opening it). */
    fun follow(id: Int) = world.whileStill {
        if (id != groups.watched) { groups.watched = id; followChanged() }
        open()
    }

    /** The overview's own time controls were used: a week waiting here goes on with the picks shown, and weeks it runs do not wait. */
    fun overviewTakesOver() {
        startNext = false
        release(pick)
    }

    /** Opens the window and puts the followed group under the player's hand. */
    private fun open() {
        groups.control = this
        frame.isVisible = true; frame.toFront()
        startIfIdle(); refresh()
    }

    /** Closes it: the group goes back to the model, and a week waiting on the player runs on its picks. */
    fun close() = world.whileStill {
        groups.control = null
        groups.watched = -1
        startNext = false
        release()
        frame.isVisible = false
    }

    val isOpen get() = frame.isVisible

    /** The group followed changed (or follow ended): any waiting choice goes to the model, and the pins are dropped. */
    fun followChanged() {
        startNext = false
        release()
        decision = null; pins = FloatArray(0); pinsFor = -1
        synchronized(trail) { trail.clear() }; seenBefore = emptySet()
        SwingUtilities.invokeLater { refresh(); startIfIdle() }
    }

    /** A week has run (on the simulation's thread). */
    fun weekDone() {
        groups.groups.firstOrNull { it.id == groups.watched && it.alive }?.let { g ->
            synchronized(trail) { trail.addLast(g.path.copyOf()); while (trail.size > TRAIL_WEEKS) trail.removeFirst() }
        }
        val log = groups.watchLog.joinToString("\n")
        SwingUtilities.invokeLater { lastWeek.text = log; refresh() }
        playerWeek = false
        if (startNext && isOpen && !running() && groups.watched >= 0 && !handsOff.isSelected) { startNext = false; stepHere() }
        else startNext = false
    }

    /** Lets a week run up to the followed group's choice, when the player decides and nothing runs or waits. */
    private fun startIfIdle() {
        if (isOpen && !handsOff.isSelected && waiting == null && !running() && groups.watched >= 0) stepHere()
    }

    private fun nextStep() {
        if (waiting != null) { startNext = true; release(pick) }
        else if (!running()) { startNext = !handsOff.isSelected; stepHere() }
        refresh()
    }

    /** Lets a waiting week go on, with the player's pick [p] (the model's if none). */
    private fun release(p: Int = -1) {
        val w = waiting ?: return
        waiting = null
        w.complete(if (p >= 0) p else decision?.model ?: 0)
    }

    private fun choose(i: Int) {
        val d = decision ?: return
        if (i !in d.places.indices) return
        pick = i; refresh()
    }

    // ---- The control, called from the simulation for the followed group ----

    override fun move(d: MoveDecision): Int {
        val roleSize = if (d.group.band != null) WAYS_OUT else ANIMAL_WAYS_OUT
        val role = if (d.group.band != null) 2 else if (d.group.species.carnivore) 1 else 0
        if (pins.size != roleSize || pinsFor != d.group.id * 3 + role) { pins = FloatArray(roleSize) { Float.NaN }; pinsFor = d.group.id * 3 + role }
        pick = d.model
        // (The groups it saw last time it chose, so that those that left can be shown going.)
        decision?.let { old -> if (old.group.id == d.group.id) seenBefore = nearIds(hw.placeHex[old.group.place]) }
        decision = d
        val f = if (handsOff.isSelected || !isOpen || !playerWeek || running()) null else CompletableFuture<Int>()
        waiting = f
        SwingUtilities.invokeLater { refresh() }
        // (While it waits on the player the world stands still, and the windows may read it.)
        return f?.let { world.waiting { runCatching { it.get() }.getOrDefault(d.model) } } ?: d.model
    }

    override fun ways(g: HexGroup, out: FloatArray) {
        val p = pins
        for (k in 0 until min(p.size, out.size)) if (!p[k].isNaN()) out[k] = toOutput(p[k])
    }

    /** A way's strength (0..2, 1 the hand-written way) as a network output, and back. */
    private fun toOutput(v: Float): Float { val x = (v / 2f).coerceIn(0.005f, 0.995f); return -ln(1f / x - 1f) }
    private fun toWay(out: Float) = 2f / (1f + exp(-out))

    // ---- What the window shows ----

    private fun refresh() = world.whileStill { refreshNow() }

    private fun refreshNow() {
        val d = decision
        val g = groups.groups.firstOrNull { it.id == groups.watched && it.alive }
        val w = waiting != null
        next.text = if (w) "Next step" else if (handsOff.isSelected) "Next step" else "Start the week"
        reset.isEnabled = d != null && (pick != d.model || pins.any { !it.isNaN() })
        note.text = when {
            groups.watched < 0 -> "  Follow a group in the overview (tick Follow, or pick a band in the list) to take its controls."
            g == null -> "  The group followed is gone (died out or joined another). Pick another in the overview and press Follow…."
            running() -> "  The overview is running: the model decides. Pause it there, then use Next step here to play."
            w -> "  Choose where it goes (click the map or the list) and set its ways; Next step runs the week."
            handsOff.isSelected -> "  The model decides; the choices shown are the ones it made."
            else -> "  Running…"
        }
        if (g == null) { title.text = " "; status.text = ""; map.repaint(); return }
        val s = g.species
        val date = sim.weather.date
        title.text = "  %s #%d — year %d, month %d, day %d%s".format(s.name, g.id, date.year, date.month + 1, date.dayOfMonth, if (w) "   (deciding)" else "")
        status.text = statusText(g, d)
        knows.text = knowsText(g, d)
        // The candidates, best by the model first.
        updating = true
        if (d != null && d.group.id == g.id) {
            val scores = d.netScores ?: d.handScores
            order = d.places.indices.sortedByDescending { scores[it] }.toIntArray()
            tableModel.fireTableDataChanged()
            val r = order.indexOf(pick); if (r >= 0) { table.setRowSelectionInterval(r, r); table.scrollRectToVisible(table.getCellRect(r, 0, true)) }
            place.text = placeText(d, pick)
            buildWays(d)
        } else { order = IntArray(0); tableModel.fireTableDataChanged(); place.text = "" }
        updating = false
        map.repaint()
    }

    /** The groups now in hex [h] and the six about it. */
    private fun nearIds(h: Int): Set<Int> {
        val near = HashSet<Int>().also { it += h; for (k in 0 until 6) hex.neighbour(h, k).takeIf { n -> n >= 0 }?.let { n -> it += n } }
        return groups.groups.filter { it.alive && hw.placeHex[it.place] in near }.map { it.id }.toSet()
    }

    private fun where(p: Int) = "hex %d, %.0f m".format(hw.placeHex[p], hw.placeElevationM[p])

    private fun statusText(g: HexGroup, d: MoveDecision?): String {
        val s = g.species
        val sb = StringBuilder()
        val b = g.band
        sb.appendLine("%d adults, %d young%s".format(g.adults, g.young, b?.let { ", %d of them elders".format(it.elders) } ?: ""))
        sb.appendLine("fed %.0f%% last week; reserves %.0f of %.0f days".format(100 * g.fed, g.fat, s.maxFatDays))
        sb.appendLine("last drank %.1f days ago (it can go %d)".format(g.dry, s.daysWithoutWater))
        sb.appendLine("stands at %s, %.1f °C".format(where(g.place.i), sim.weather.temperature[g.place.i]))
        if (b != null) {
            sb.appendLine("larder %.0f kg (%.0f days): ".format(g.larder, g.larder / g.dailyNeedKg.coerceAtLeast(1e-3f)) +
                Store.entries.filter { g.stores[it] >= 1f }.joinToString(", ") { "%s %.0f".format(it.name.lowercase(), g.stores[it]) })
            val sick = Illness.entries.filter { b.sick[it] > 0.005f }
                .joinToString(", ") { "%s %.0f%%".format(it.name.lowercase().replace('_', ' '), 100 * b.sick[it]) }
            sb.appendLine("health: wounded %.0f%%, lamed %.0f%%%s".format(100 * b.hurt, 100 * b.lame, if (sick.isNotEmpty()) "; sick: $sick" else ""))
            sb.appendLine("camp kept %d weeks%s".format(b.campWeeks,
                if (!b.houseHex.none) "; houses at hex %d (%.0f%%)".format(b.houseHex.i, 100 * b.house) else ""))
            sb.appendLine("tools: " + Tool.entries.filter { b.knows[it] }
                .joinToString(", ") { "%s %.0f%%".format(it.name.lowercase(), 100 * b.kit[it]) })
            sb.appendLine("customs: " + Tradition.entries.filter { b.holds(it) }.joinToString(", ") { it.name.lowercase().replace('_', ' ') }
                .ifEmpty { "none" })
            sb.appendLine("knows %d hexes; knows the homes of %d bands".format((0 until hex.size).count { b.knows(it) }, b.knownHomes.size))
            b.raid?.let { sb.appendLine("set on a raid at hex %d".format(it.hex.i)) }
            b.appointment?.let { sb.appendLine("meets others at hex %d in month %d".format(it.hex.i, it.month + 1)) }
        } else if (s.carnivore) sb.appendLine(if (!g.home.none) "holds ground about hex %d".format(g.home.i) else "holds no ground")
        val here = hw.placeHex[g.place]
        val with = groups.groups.filter { it.alive && it !== g && hw.placeHex[it.place] == here }
        sb.appendLine("in its hex: " + with.joinToString(", ") { "%s #%d (%d)".format(it.species.name, it.id, it.size) }.ifEmpty { "no other group" })
        if (d != null && d.group.id == g.id) {
            sb.appendLine()
            sb.appendLine(if (d.content) "content where it is: the model keeps it here this week"
                else if (d.restless) "restless (hungry, thirsty, astray, or due somewhere): it will go far" else "weighing where to graze or camp")
            sb.appendLine("it can reach %d places, within %.0f km of effort".format(d.places.size, d.reachKm))
            if (d.goal >= 0) sb.appendLine("%s draws it to %s, %.0f km off".format(if (d.onRound) "its round" else "its memory of the season", where(d.goal),
                hex.distanceKm(hw.placeHex[g.place], hw.placeHex[d.goal])))
            sb.appendLine("where to go is chosen by " + if (d.learned) "the learned network" else "the hand-written score")
            sb.appendLine("its ways are set by " + if (d.waysLearned) "the learned network" else "the hand-written ways")
            sb.appendLine()
            sb.appendLine("the model goes to %s%s".format(where(d.places[d.model]), if (d.places[d.model] == g.place.i) " (stays)" else ""))
            if (pick != d.model && pick in d.places.indices) sb.appendLine("you go to %s".format(where(d.places[pick])))
            val pinned = pins.count { !it.isNaN() }
            if (pinned > 0) sb.appendLine("you set %d of its ways".format(pinned))
        }
        return sb.toString()
    }

    private fun knowsText(g: HexGroup, d: MoveDecision?): String {
        if (d == null || d.group.id != g.id) return "(shown once it weighs its week)"
        val names = if (g.band != null) Schema.WAYS_IN else Schema.ANIMAL_WAYS_IN
        val sb = StringBuilder()
        sb.appendLine(if (g.band != null) "What the band knows of itself and the bands about it, as its networks read it:"
            else "What it knows of itself, as its ways network reads it:")
        for ((i, n) in names.withIndex()) if (i < d.knows.size) sb.appendLine("  %-28s %7.2f".format(n, d.knows[i]))
        return sb.toString()
    }

    private fun placeText(d: MoveDecision, i: Int): String {
        if (i !in d.places.indices) return ""
        val sb = StringBuilder()
        sb.appendLine("What it sees of %s%s (as the network reads it):".format(where(d.places[i]), if (i == d.model) " — the model's pick" else ""))
        val f = d.features[i]
        for ((k, n) in Schema.PLACE.withIndex()) if (k < HexFeatures.SIZE && f[k] != 0f) sb.appendLine("  %-26s %7.2f".format(n, f[k]))
        sb.appendLine("  (features at 0 not listed)")
        return sb.toString()
    }

    private inner class Choices : AbstractTableModel() {
        private val cols = arrayOf("", "place", "network", "hand-written", "food", "water km", "effort", "known")
        override fun getRowCount() = order.size
        override fun getColumnCount() = cols.size
        override fun getColumnName(c: Int) = cols[c]
        override fun getValueAt(r: Int, c: Int): Any {
            val d = decision ?: return ""
            val i = order[r]; val f = d.features[i]; val s = d.group.species
            return when (c) {
                0 -> (if (i == d.model) "model" else "") + (if (i == pick && i != d.model) "you" else "")
                1 -> where(d.places[i]) + if (d.places[i] == d.group.place.i) " (stay)" else ""
                2 -> d.netScores?.let { "%.3f".format(it[i]) } ?: "-"
                3 -> "%.3f".format(d.handScores[i])
                4 -> "%.2f".format(f[HexFeatures.FOOD])
                5 -> "%.1f".format(f[HexFeatures.WATER_DISTANCE] * s.dailyTravelKm)
                6 -> "%.0f%%".format(100 * f[HexFeatures.EFFORT])
                else -> if (f[HexFeatures.KNOWN_GROUND] > 0f) "yes" else "no"
            }
        }
    }

    /** One of its ways: the model's strength for it, and the player's slider and pin. */
    private inner class WayRow(val k: Int, name: String) {
        val label = JLabel(name)
        val model = JLabel()
        val slider = JSlider(0, 200, 100).apply { preferredSize = Dimension(220, 22) }
        val value = JLabel()
        val pin = JCheckBox("yours")
        init {
            slider.addChangeListener {
                if (!updating) {
                    pins = pins.copyOf().also { it[k] = slider.value / 100f }
                    pin.isSelected = true
                    value.text = "×%.2f".format(slider.value / 100f)
                }
            }
            pin.addActionListener { if (!updating) { pins = pins.copyOf().also { it[k] = if (pin.isSelected) slider.value / 100f else Float.NaN }; refresh() } }
        }
    }

    private var waysFor = -1
    private fun buildWays(d: MoveDecision) {
        val band = d.group.band != null
        val names = if (band) wayNames() else if (d.group.species.carnivore) Schema.HUNTER_WAYS_OUT else Schema.HERD_WAYS_OUT
        val key = if (band) 2 else if (d.group.species.carnivore) 1 else 0
        if (waysFor != key) {
            waysFor = key
            waysPanel.removeAll()
            val c = GridBagConstraints().apply { insets = Insets(1, 4, 1, 4); anchor = GridBagConstraints.WEST }
            c.gridy = 0
            for ((x, h) in listOf("way", "model", "yours", "", "").withIndex()) {
                c.gridx = x
                waysPanel.add(JLabel(h).apply { font = font.deriveFont(Font.BOLD) }, c)
            }
            wayRows = names.mapIndexed { k, n -> WayRow(k, n) }
            for (r in wayRows) {
                c.gridy++
                c.gridx = 0; waysPanel.add(r.label, c); c.gridx = 1; waysPanel.add(r.model, c); c.gridx = 2; waysPanel.add(r.slider, c)
                c.gridx = 3; waysPanel.add(r.value, c); c.gridx = 4; waysPanel.add(r.pin, c)
            }
            c.gridy++; c.gridx = 0; c.gridwidth = 5
            waysPanel.add(JLabel("<html>Each way as a strength: 1 is the hand-written way, 0 never, 2 twice as readily.<br>" +
                (if (band) "A band sets its ways as it forages, after it moves: the model's shown here are from the start of the week."
                    else "Set before it chose where to go; yours take hold at Next step.") + "</html>"), c)
            waysPanel.revalidate()
        }
        val p = pins
        for (r in wayRows) {
            val m = toWay(d.ways.getOrElse(r.k) { 0f })
            r.model.text = "×%.2f".format(m)
            val mine = p.getOrElse(r.k) { Float.NaN }
            val v = if (mine.isNaN()) m else mine
            r.slider.value = (v * 100).toInt(); r.value.text = "×%.2f".format(v); r.pin.isSelected = !mine.isNaN()
        }
    }

    private fun wayNames(): List<String> {
        return Schema.WAYS_OUT.mapIndexed { k, n ->
            if (k < FOOD_TYPES) "effort: " + Food.entries[k].label else n.replace("make first:", "make first: ")
        }
    }

    /** The country about the group: what it can reach and how the model scores it, the model's pick and the player's. */
    private inner class LocalMap : JPanel() {
        /** The hexes on screen, for clicks: centre km and view scale as last drawn. */
        private var cx = 0.0; private var cy = 0.0; private var kmPx = 1.0

        init {
            preferredSize = Dimension(600, 600)
            background = Color(0x30506e)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val d = decision ?: return
                    val h = hex.hexAt(cx + (e.x - width / 2.0) / kmPx, cy + (e.y - height / 2.0) / kmPx)
                    // A hex may hold places at several heights: clicking again goes to the next.
                    val here = d.places.indices.filter { hw.placeHex[d.places[it]] == h }
                    if (here.isEmpty()) return
                    val scores = d.netScores ?: d.handScores
                    val sorted = here.sortedByDescending { scores[it] }
                    val at = sorted.indexOf(pick)
                    choose(if (at >= 0) sorted[(at + 1) % sorted.size] else sorted[0])
                }
            })
        }

        private val painter = world.Painter(this)

        override fun paintComponent(g0: Graphics) {
            super.paintComponent(g0)
            painter.paint(g0) { paintMap(it) }
        }

        private fun paintMap(g: Graphics2D) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val grp = groups.groups.firstOrNull { it.id == groups.watched && it.alive } ?: run {
                g.color = Color.WHITE; g.drawString("No group followed", 20, 30); return
            }
            val d = decision?.takeIf { it.group.id == grp.id }
            val centre = hw.placeHex[d?.group?.place?.takeIf { d.group === grp }?.let { grp.place } ?: grp.place]
            val (x0, y0) = hex.centreKm(centre); cx = x0; cy = y0
            val viewKm = (max(d?.reachKm?.toDouble() ?: 0.0, 3 * hex.hexKm) + 2 * hex.hexKm).coerceAtMost(25 * hex.hexKm)
            kmPx = min(width, height) / (2 * viewKm)
            fun sx(km: Double) = (width / 2.0 + (km - cx) * kmPx).toInt()
            fun sy(km: Double) = (height / 2.0 + (km - cy) * kmPx).toInt()
            // The ground.
            val halfW = width / 2.0 / kmPx; val halfH = height / 2.0 / kmPx
            g.drawImage(ground, 0, 0, width, height, ((cx - halfW) * px).toInt(), ((cy - halfH) * px).toInt(), ((cx + halfW) * px).toInt(),
                ((cy + halfH) * px).toInt(), null)
            rivers.draw(g, width / 2.0 - cx * kmPx, height / 2.0 - cy * kmPx, kmPx, width, height)
            val rad = hex.hexKm / sqrt(3.0)
            fun outline(h: Int): Pair<IntArray, IntArray> {
                val (hx, hy) = hex.centreKm(h)
                return IntArray(6) { sx(hx + rad * cos(Math.PI / 6 + it * Math.PI / 3)) } to IntArray(6) { sy(hy + rad * sin(Math.PI / 6 + it * Math.PI / 3)) }
            }
            // The hexes in view: a band sees only dimly the country it does not know.
            val b = grp.band
            val inView = ArrayList<Int>()
            run {
                val h0 = hex.hexAt(cx - halfW, cy - halfH); val h1 = hex.hexAt(cx + halfW, cy + halfH)
                val c0 = (if (h0 >= 0) h0 % hex.cols else 0) - 1; val c1 = (if (h1 >= 0) h1 % hex.cols else hex.cols - 1) + 1
                val r0 = (if (h0 >= 0) h0 / hex.cols else 0) - 1; val r1 = (if (h1 >= 0) h1 / hex.cols else hex.rows - 1) + 1
                for (r in max(0, r0)..min(hex.rows - 1, r1)) for (c in max(0, c0)..min(hex.cols - 1, c1)) inView += r * hex.cols + c
            }
            if (b != null) for (h in inView) if (h < hex.size && !b.knows(h)) {
                val (xs, ys) = outline(h)
                g.color = Color(0f, 0f, 0f, 0.45f)
                g.fillPolygon(xs, ys, 6)
            }
            // The places it can reach, coloured by how the model scores them (the best green, the worst red).
            if (d != null) {
                val scores = d.netScores ?: d.handScores
                val best = HashMap<Int, Int>()
                for (i in d.places.indices) { val h = hw.placeHex[d.places[i]]; val o = best[h]; if (o == null || scores[i] > scores[o]) best[h] = i }
                val sorted = scores.sortedArray(); val n = sorted.size
                for ((h, i) in best) {
                    val rank = (sorted.binarySearch(scores[i]).let { if (it < 0) -it - 1 else it }).toFloat() / max(1, n - 1)
                    val (xs, ys) = outline(h)
                    g.color = Color((1f - rank).coerceIn(0f, 1f), rank.coerceIn(0f, 1f), 0.15f, 0.38f); g.fillPolygon(xs, ys, 6)
                    g.color = Color(1f, 1f, 1f, 0.35f); g.drawPolygon(xs, ys, 6)
                }
                // The pull forward: an arrow toward the place its round or its memory of the season draws it to,
                // that hex lightly lit (in view, or the arrow ends at the edge); and the kind's sense of better country.
                if (d.goal >= 0) {
                    val gh = hw.placeHex[d.goal]
                    val (gx, gy) = hex.centreKm(gh)
                    val pink = Color(1f, 0.55f, 1f)
                    val gxp = sx(gx); val gyp = sy(gy)
                    val inside = gxp in 0..width && gyp in 0..height
                    if (inside) {
                        val (xs, ys) = outline(gh)
                        g.color = Color(1f, 0.55f, 1f, 0.3f)
                        g.fillPolygon(xs, ys, 6)
                        g.color = pink
                        g.drawPolygon(xs, ys, 6)
                    }
                    val ang = atan2(gy - cy, gx - cx)
                    val dist = hypot((gxp - width / 2).toDouble(), (gyp - height / 2).toDouble())
                    val len = min(dist - (if (inside) 10.0 else 0.0), min(width, height) / 2.0 - 14)
                    if (len > 15) arrow(g, width / 2 + (12 * cos(ang)).toInt(), height / 2 + (12 * sin(ang)).toInt(), width / 2 + (len * cos(ang)).toInt(),
                        height / 2 + (len * sin(ang)).toInt(), pink, 3f)
                    val lx = (width / 2 + (len * cos(ang)).toInt() + 8).coerceIn(4, width - 130)
                    val ly = (height / 2 + (len * sin(ang)).toInt() - 6).coerceIn(14, height - 4)
                    g.color = pink
                    g.drawString((if (d.onRound) "its round" else "its season's place") + ", %.0f km".format(hex.distanceKm(centre, gh)), lx, ly)
                }
                groups.kindIndex[grp.species]?.let { kind ->
                    val e = groups.bearings.east[kind][centre]; val so = groups.bearings.south[kind][centre]
                    val st = hypot(e, so)
                    if (st > 0.02f) {
                        val len = (st.coerceAtMost(1f) * 2.5 * hex.hexKm * kmPx)
                        arrow(g, width / 2, height / 2, width / 2 + (len * e / st).toInt(), height / 2 + (len * so / st).toInt(),
                            Color(1f, 0.95f, 0.6f, 0.8f), 1.5f)
                    }
                }
                // The model's way and pick (yellow), and the player's (cyan).
                fun way(i: Int, c: Color, w: Float) {
                    val path = d.paths[i]
                    g.color = c; g.stroke = BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    for (k in 0 until path.size - 1) {
                        val (ax, ay) = hex.centreKm(path[k])
                        val (bx, by) = hex.centreKm(path[k + 1])
                        g.drawLine(sx(ax), sy(ay), sx(bx), sy(by))
                    }
                    val (xs, ys) = outline(hw.placeHex[d.places[i]]); g.drawPolygon(xs, ys, 6)
                    g.stroke = BasicStroke(1f)
                }
                way(d.model, Color(1f, 0.85f, 0.1f), 4f)
                if (pick != d.model && pick in d.places.indices) way(pick, Color(0.2f, 0.95f, 1f), 3f)
            }
            // Where it went in the last weeks: older the fainter.
            val past = synchronized(trail) { trail.toList() }
            for ((age, path) in past.reversed().withIndex()) {
                val a = (0.9f * (1f - age.toFloat() / TRAIL_WEEKS)).coerceAtLeast(0.25f)
                g.color = Color(1f, 1f, 1f, a)
                g.stroke = BasicStroke(3f - age * 0.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(6f, 4f), 0f)
                for (k in 0 until path.size - 1) {
                    val (ax, ay) = hex.centreKm(path[k])
                    val (bx, by) = hex.centreKm(path[k + 1])
                    g.drawLine(sx(ax), sy(ay), sx(bx), sy(by))
                }
                path.lastOrNull()?.let { h -> val (hx, hy) = hex.centreKm(h); g.fillOval(sx(hx) - 3, sy(hy) - 3, 6, 6) }
            }
            g.stroke = BasicStroke(1f)
            // Where it stands.
            run { val (xs, ys) = outline(centre); g.color = Color.WHITE; g.stroke = BasicStroke(2f); g.drawPolygon(xs, ys, 6); g.stroke = BasicStroke(1f) }
            // The groups about it that it can see: in its hex and the next, each with the way it came this
            // week; and those it saw last time that have since gone, faint, with the way they went.
            val nearHexes = HashSet<Int>().also { it += centre; for (k in 0 until 6) hex.neighbour(centre, k).takeIf { n -> n >= 0 }?.let { n -> it += n } }
            val gone = seenBefore
            val slot = HashMap<Int, Int>()
            val shownHere = groups.groups.filter { it.alive && (hw.placeHex[it.place] in nearHexes || it.id in gone) }
            g.font = g.font.deriveFont(10f)
            for (o in shownHere.sortedBy { if (it === grp) 0 else 1 }) {
                val h = hw.placeHex[o.place]
                val left = h !in nearHexes
                val (hx, hy) = hex.centreKm(h)
                // The followed group stands in the middle of its hex, the others in a ring about it.
                val (ox, oy) = if (o === grp) sx(hx) to sy(hy) else {
                    val k = slot.merge(h, 1, Int::plus)!! - 1
                    val a = 2 * Math.PI * k / 6 + Math.PI / 6; val r = 0.3 * hex.hexKm
                    sx(hx + r * cos(a)) to sy(hy + r * sin(a))
                }
                val alpha = if (left) 0.45f else 1f
                if (o !== grp && o.path.size > 1) {
                    g.color = Color(1f, 1f, 1f, 0.5f * alpha); g.stroke = BasicStroke(1.2f)
                    for (k in 0 until o.path.size - 1) {
                        val (ax, ay) = hex.centreKm(o.path[k])
                        val (bx, by) = hex.centreKm(o.path[k + 1])
                        g.drawLine(sx(ax), sy(ay), if (k == o.path.size - 2) ox else sx(bx), if (k == o.path.size - 2) oy else sy(by))
                    }
                    g.stroke = BasicStroke(1f)
                }
                val big = if (o === grp) 9 else 6
                val ring = if (o === grp) Color(1f, 0.85f, 0.1f) else Color(1f, 1f, 1f, alpha)
                when {
                    o.species.human -> {
                        g.color = ring
                        g.fillPolygon(intArrayOf(ox, ox + big, ox, ox - big), intArrayOf(oy - big, oy, oy + big, oy), 4)
                        g.color = Color(0.1f, 0.3f, 0.95f, alpha)
                        g.fillPolygon(intArrayOf(ox, ox + big / 2, ox, ox - big / 2), intArrayOf(oy - big / 2, oy, oy + big / 2, oy), 4)
                    }
                    o.species.carnivore -> {
                        g.color = ring
                        g.fillRect(ox - big / 2 - 1, oy - big / 2 - 1, big + 2, big + 2)
                        g.color = Color(0.82f, 0.06f, 0.06f, alpha)
                        g.fillRect(ox - big / 2, oy - big / 2, big, big)
                    }
                    else -> {
                        g.color = if (o === grp) ring else Color(0f, 0f, 0f, alpha)
                        g.fillOval(ox - big / 2 - 1, oy - big / 2 - 1, big + 2, big + 2)
                        g.color = Color(0.9f, 0.9f, 0.6f, alpha)
                        g.fillOval(ox - big / 2, oy - big / 2, big, big)
                    }
                }
                if (o !== grp) {
                    g.color = Color(1f, 1f, 1f, alpha)
                    g.drawString("%s %d%s".format(o.species.name, o.size, if (left) " (gone)" else ""), ox + big, oy - big)
                }
            }
            // The legend.
            g.font = g.font.deriveFont(11f)
            val lines = listOfNotNull("yellow: the model's pick", if (d != null && pick != d.model) "cyan: yours" else null,
                "green to red: best to worst by the model",
                "pink arrow: its round or season's place", "pale arrow: its kind's sense of better country", "dashed: where it went, last $TRAIL_WEEKS weeks",
                if (b != null) "dark: country it does not know" else null, "click a hex to go there")
            g.color = Color(0f, 0f, 0f, 0.55f); g.fillRect(6, 6, 290, 16 * lines.size + 6)
            g.color = Color.WHITE
            for ((i, l) in lines.withIndex()) g.drawString(l, 12, 20 + 16 * i)
        }
    }

    private fun arrow(g: Graphics2D, x0: Int, y0: Int, x1: Int, y1: Int, c: Color, w: Float) {
        g.color = c; g.stroke = BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.drawLine(x0, y0, x1, y1)
        val ang = atan2((y1 - y0).toDouble(), (x1 - x0).toDouble()); val head = 6 + 2 * w
        for (sgn in intArrayOf(-1, 1)) g.drawLine(x1, y1, (x1 - head * cos(ang + sgn * 0.45)).toInt(), (y1 - head * sin(ang + sgn * 0.45)).toInt())
        g.stroke = BasicStroke(1f)
    }

    companion object {
        /** Weeks of the followed group's way shown behind it. */
        const val TRAIL_WEEKS = 4
    }

}
