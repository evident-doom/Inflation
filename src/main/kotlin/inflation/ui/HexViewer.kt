package inflation.ui

import inflation.ecology.Catalogue
import inflation.ecology.HexFauna
import inflation.ecology.HexGroups
import inflation.world.HexLand
import inflation.world.HexMap
import inflation.world.HexPicture
import inflation.world.HexWorld
import inflation.world.Weather
import inflation.world.World
import inflation.world.WorldConfig
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentLinkedQueue
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Watches the hex world: the fine terrain with its hex borders (see [HexPicture]) as the ground,
 * the herds and hunters as dots walking hex to hex along the ways they took each week, and one
 * resident kind's density as a tint over the hexes.
 *
 * Usage: hex-viewer [--seed N] [--width KM] [--height KM]
 */
fun main(args: Array<String>) {
    var cfg = WorldConfig(seed = 1)
    var k = 0
    while (k < args.size) {
        when (args[k]) {
            "--seed" -> cfg = cfg.copy(seed = args[++k].toLong())
            "--width" -> cfg = cfg.copy(widthKm = args[++k].toInt())
            "--height" -> cfg = cfg.copy(heightKm = args[++k].toInt())
            else -> error("unknown argument ${args[k]}")
        }
        k++
    }
    val frame = JFrame("Hex world, seed ${cfg.seed}")
    val status = JLabel("Generating the world…")
    frame.contentPane.add(status)
    frame.setSize(1400, 1000)
    frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
    frame.isVisible = true
    Thread {
        val world = World.generate(cfg) { step -> SwingUtilities.invokeLater { status.text = "Generating: $step…" } }
        SwingUtilities.invokeLater { status.text = "Summarising into hexes and drawing the ground…" }
        val view = HexViewer(world)
        SwingUtilities.invokeLater {
            frame.contentPane.removeAll(); frame.contentPane.add(view); frame.revalidate()
        }
    }.start()
}

class HexViewer(world: World) : JPanel(BorderLayout()) {
    private val cfg = world.config
    private val hex = HexMap(world)
    private val hw = HexWorld(world, hex)
    private val weather = Weather(hw.weatherPlaces(cfg), cfg)
    private val land = HexLand(hw, cfg.seed)
    private val fauna = HexFauna(hw, land, cfg.daysPerStep)
    private val groups = HexGroups(hw, land, fauna, cfg.daysPerStep, cfg.seed)
    private val px = 2
    private val ground: BufferedImage = HexPicture.render(world, hex, 0.0, 0.0, cfg.widthKm.toDouble(), cfg.heightKm.toDouble(), px)
    private val commands = ConcurrentLinkedQueue<() -> Unit>()
    @Volatile private var running = false
    @Volatile private var stepsPerSecond = 2

    /** What is drawn: per group its id, kind, the way it walked (km, x0 y0 x1 y1 ...; from where it was drawn last), its size. */
    private class Shown(val id: Int, val kind: Int, val carnivore: Boolean, val way: FloatArray, val size: Int)
    /** Where each group was drawn at the end of the last move, km. */
    private val lastDrawn = HashMap<Int, Pair<Float, Float>>()
    @Volatile private var shown = emptyList<Shown>()
    @Volatile private var frame = 0
    private var frames = 1
    private val date = JLabel()
    private val counts = JLabel()
    private val residentChoice = JComboBox(arrayOf("(no resident tint)") + fauna.kinds.map { it.name }.toTypedArray())
    private val map = MapPanel()
    /** The hex being inspected, -1 for none, and its groups. */
    @Volatile private var picked = -1
    private val info = JTextArea(14, 40).apply { isEditable = false; font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12) }
    private val groupList = DefaultListModel<String>()
    private val groupView = JList(groupList)
    private val groupInfo = JTextArea(12, 40).apply { isEditable = false; font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12) }
    private var groupIds = IntArray(0)

    init {
        val bar = JPanel()
        val run = JButton("Run"); run.addActionListener { running = !running; run.text = if (running) "Pause" else "Run" }
        val step = JButton("Step"); step.addActionListener { commands += { advance() } }
        val speed = JSlider(1, 10, stepsPerSecond); speed.addChangeListener { stepsPerSecond = speed.value }
        residentChoice.addActionListener { map.repaint() }
        bar.add(run); bar.add(step); bar.add(JLabel("weeks/s")); bar.add(speed); bar.add(residentChoice); bar.add(date)
        val bottom = JPanel(); bottom.add(counts)
        val side = JPanel(BorderLayout())
        side.add(JScrollPane(info), BorderLayout.NORTH)
        side.add(JScrollPane(groupView), BorderLayout.CENTER)
        side.add(JScrollPane(groupInfo), BorderLayout.SOUTH)
        groupView.addListSelectionListener { showGroup() }
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, map, side)
        split.resizeWeight = 0.75
        add(bar, BorderLayout.NORTH); add(split, BorderLayout.CENTER); add(bottom, BorderLayout.SOUTH)
        snapshot()
        Thread(::loop, "hex simulation").apply { isDaemon = true }.start()
        Timer(33) { if (frame < frames) { frame++; map.repaint() } }.start()
    }

    private fun advance() {
        weather.step(); land.step(weather); fauna.step(weather, weather.date); groups.step(weather, weather.date)
        snapshot()
    }

    private fun snapshot() {
        val list = ArrayList<Shown>(groups.groups.size)
        // Groups ending the week in the same hex stand side by side.
        val inHex = HashMap<Int, Int>(); val slotOf = HashMap<Int, Int>()
        for (g in groups.groups) if (g.alive) { val h = hw.placeHex[g.place]; slotOf[g.id] = inHex.getOrDefault(h, 0); inHex.merge(h, 1, Int::plus) }
        for (g in groups.groups) {
            if (!g.alive) continue
            val h = hw.placeHex[g.place]
            // Its spot at the end: a small ring around the hex's centre when it shares the hex.
            val (hx, hy) = hex.centreKm(h)
            val slots = inHex.getValue(h); val slot = slotOf.getValue(g.id)
            val r = if (slots > 1) min(3.5, 1.2 + 0.35 * slots) else 0.0
            val a = 2 * Math.PI * slot / max(1, slots)
            val end = (hx + r * kotlin.math.cos(a)).toFloat() to (hy + r * kotlin.math.sin(a)).toFloat()
            // From where it was drawn last, through the centres of the hexes passed, to its spot.
            val pts = ArrayList<Float>()
            val start = lastDrawn[g.id] ?: end
            pts += start.first; pts += start.second
            for (ph in g.path.drop(1).dropLast(1)) { val (x, y) = hex.centreKm(ph); pts += x.toFloat(); pts += y.toFloat() }
            pts += end.first; pts += end.second
            list += Shown(g.id, g.species.index, g.species.carnivore, pts.toFloatArray(), g.size)
            lastDrawn[g.id] = end
        }
        shown = list
        SwingUtilities.invokeLater { if (picked >= 0) inspect(picked) }
        frames = max(8, 30 / stepsPerSecond); frame = 0
        SwingUtilities.invokeLater {
            date.text = "   year ${weather.date.year}, month ${weather.date.month + 1}, day ${weather.date.dayOfMonth}"
            counts.text = groups.kinds.joinToString("  ") { "${it.name} ${groups.count(it)}" }
        }
    }

    private fun loop() {
        var last = 0L
        while (true) {
            while (true) { val c = commands.poll() ?: break; c() }
            if (running && frame >= frames && System.nanoTime() - last > 1_000_000_000L / stepsPerSecond) { last = System.nanoTime(); advance() }
            else Thread.sleep(5)
        }
    }

    private inner class MapPanel : JPanel() {
        private var scale = 0.5
        private var ox = 0.0; private var oy = 0.0
        private var drag: java.awt.Point? = null

        init {
            preferredSize = Dimension(1200, 900)
            background = Color(0x30506e)
            val mouse = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) { drag = e.point }
                override fun mouseClicked(e: MouseEvent) {
                    val kmPx = px * scale
                    val h = hex.hexAt((e.x - ox) / kmPx, (e.y - oy) / kmPx)
                    picked = h; inspect(h); repaint()
                }
                override fun mouseDragged(e: MouseEvent) { val d = drag ?: return; ox += e.x - d.x; oy += e.y - d.y; drag = e.point; repaint() }
                override fun mouseWheelMoved(e: MouseWheelEvent) {
                    val f = if (e.wheelRotation < 0) 1.25 else 0.8
                    val ns = (scale * f).coerceIn(0.1, 8.0)
                    ox = e.x - (e.x - ox) * ns / scale; oy = e.y - (e.y - oy) * ns / scale; scale = ns; repaint()
                }
            }
            addMouseListener(mouse); addMouseMotionListener(mouse); addMouseWheelListener(mouse)
        }

        override fun paintComponent(g0: Graphics) {
            super.paintComponent(g0)
            val g = g0 as Graphics2D
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.drawImage(ground, ox.toInt(), oy.toInt(), (ground.width * scale).toInt(), (ground.height * scale).toInt(), null)
            val kmPx = px * scale
            fun sx(km: Float) = (ox + km * kmPx).toInt()
            fun sy(km: Float) = (oy + km * kmPx).toInt()
            // A resident kind's density as a tint over the hexes.
            val r = residentChoice.selectedIndex - 1
            if (r >= 0) {
                val rad = hex.hexKm / kotlin.math.sqrt(3.0)
                val dens = DoubleArray(hex.size); val area = DoubleArray(hex.size)
                for (p in 0 until fauna.n) { val h = hw.placeHex[p]; dens[h] += ((fauna.adults[r][p] + fauna.young[r][p]) * land.areaKm2[p]).toDouble(); area[h] += land.areaKm2[p].toDouble() }
                val top = (0 until hex.size).maxOf { if (area[it] > 0) dens[it] / area[it] else 0.0 }.coerceAtLeast(1e-6)
                for (h in 0 until hex.size) {
                    if (area[h] <= 0) continue
                    val v = (dens[h] / area[h] / top).toFloat().coerceIn(0f, 1f)
                    if (v < 0.02f) continue
                    val (cx, cy) = hex.centreKm(h)
                    val xs = IntArray(6) { sx((cx + rad * kotlin.math.cos(Math.PI / 6 + it * Math.PI / 3)).toFloat()) }
                    val ys = IntArray(6) { sy((cy + rad * kotlin.math.sin(Math.PI / 6 + it * Math.PI / 3)).toFloat()) }
                    g.color = Color(1f, 0.1f, 0.6f, 0.6f * v); g.fillPolygon(xs, ys, 6)
                }
            }
            // The hex being inspected.
            if (picked >= 0) {
                val (cx, cy) = hex.centreKm(picked)
                val rad = hex.hexKm / kotlin.math.sqrt(3.0)
                val xs = IntArray(6) { sx((cx + rad * kotlin.math.cos(Math.PI / 6 + it * Math.PI / 3)).toFloat()) }
                val ys = IntArray(6) { sy((cy + rad * kotlin.math.sin(Math.PI / 6 + it * Math.PI / 3)).toFloat()) }
                g.stroke = java.awt.BasicStroke(2.5f); g.color = Color.WHITE; g.drawPolygon(xs, ys, 6); g.stroke = java.awt.BasicStroke(1f)
            }
            // The groups, each part of the way along the hexes it walked this week; at the end of the
            // week those sharing a hex stand side by side in a small ring around its centre.
            val t = min(1f, frame.toFloat() / frames)
            for (s in shown) {
                val w = s.way
                val (x, y) = along(w, t)
                val sp = Catalogue.all[s.kind]
                if (s.carnivore) {
                    g.color = Color.WHITE; g.fillRect(sx(x) - 3, sy(y) - 3, 7, 7)
                    g.color = Color(0xd01010); g.fillRect(sx(x) - 2, sy(y) - 2, 5, 5)
                } else {
                    g.color = Color(0x101010); g.fillOval(sx(x) - 3, sy(y) - 3, 7, 7)
                    g.color = Color(KIND_COLOURS[sp.index % KIND_COLOURS.size]); g.fillOval(sx(x) - 2, sy(y) - 2, 5, 5)
                }
            }
        }

        private fun along(w: FloatArray, t: Float): Pair<Float, Float> {
            if (w.size <= 2) return w[0] to w[1]
            var total = 0f
            for (j in 0 until w.size / 2 - 1) total += hypot(w[2 * j + 2] - w[2 * j], w[2 * j + 3] - w[2 * j + 1])
            var left = t * total
            for (j in 0 until w.size / 2 - 1) {
                val len = hypot(w[2 * j + 2] - w[2 * j], w[2 * j + 3] - w[2 * j + 1])
                if (left <= len && len > 0f) { val f = left / len; return w[2 * j] + (w[2 * j + 2] - w[2 * j]) * f to w[2 * j + 1] + (w[2 * j + 3] - w[2 * j + 1]) * f }
                left -= len
            }
            return w[w.size - 2] to w[w.size - 1]
        }
    }

    /** Fills the side panel with what is in hex [h]: its ground, weather, plants, resident herds and borders, and the groups standing in it. */
    private fun inspect(h: Int) {
        val sb = StringBuilder()
        val (cx, cy) = hex.centreKm(h)
        sb.appendLine("Hex %d at (%.0f, %.0f) km%s".format(h, cx, cy, if (hex.water[h]) " - water" else ""))
        for (b in 0 until HexWorld.BANDS_M.size) {
            val p = hw.placeOf[b][h]
            if (p < 0) continue
            sb.appendLine("band %d (%.0f m, %.0f km²): %.1f °C, snow %.0f mm, soil water %.0f mm".format(b, hw.placeElevationM[p], land.areaKm2[p], weather.temperature[p], weather.snowpack[p], land.soil[p]))
            sb.appendLine("   herbs %.2f shrubs %.2f trees %.1f kg/m², rugged %.2f, refuge %.2f, lookout %.0f km".format(land.herb[p], land.shrub[p], land.tree[p], land.ruggedness[p], land.bestDefence[p], land.bestViewKm[p]))
            val res = fauna.kinds.indices.filter { fauna.adults[it][p] + fauna.young[it][p] > 0.01f }
                .joinToString(", ") { "%s %.1f".format(fauna.kinds[it].name, fauna.adults[it][p] + fauna.young[it][p]) }
            if (res.isNotEmpty()) sb.appendLine("   resident herds per km²: $res")
        }
        sb.appendLine("river: biggest %.0f m³/s, flow now %.0f m³/s".format(land.meanFlow[h], land.flow[h]))
        sb.append("borders (on foot, ground + water):")
        val dirs = arrayOf("E", "SE", "SW", "W", "NW", "NE")
        for (d in 0 until 6) {
            val e = hex.crossing[h * 6 + d]
            val depth = hex.riverDepthM[h * 6 + d]
            sb.append("  ${dirs[d]} " + (if (e.isFinite()) "%.0f".format(e) else "no way") + (if (depth > HexMap.KNEE) " (river %.1f m)".format(depth) else ""))
        }
        info.text = sb.toString()
        val here = groups.groups.filter { it.alive && hw.placeHex[it.place] == h }
        groupIds = here.map { it.id }.toIntArray()
        groupList.clear()
        for (g in here) groupList.addElement("%s #%d: %d adults, %d young, fed %.0f%%".format(g.species.name, g.id, g.adults, g.young, 100 * g.fed))
        groupInfo.text = ""
    }

    /** Shows the group picked in the list. */
    private fun showGroup() {
        val i = groupView.selectedIndex
        if (i < 0 || i >= groupIds.size) return
        val g = groups.groups.firstOrNull { it.id == groupIds[i] } ?: return
        val s = g.species
        val sb = StringBuilder()
        sb.appendLine("%s #%d".format(s.name, g.id))
        sb.appendLine("%d adults, %d young; fed %.0f%% last week, reserves %.0f of %.0f days".format(g.adults, g.young, 100 * g.fed, g.fat, s.maxFatDays))
        sb.appendLine("stands in band %d at %.0f m".format(hw.placeBand[g.place], hw.placeElevationM[g.place]))
        sb.appendLine("this week it walked %d hexes: %s".format(max(0, g.path.size - 1), g.path.joinToString(" > ")))
        if (g.days.isNotEmpty()) sb.appendLine("entering them on days: " + g.days.joinToString(" ") { "%.1f".format(it) })
        if (s.carnivore) sb.appendLine("holds ground at hex %s; meat from herds met this week %.0f kg".format(if (g.home >= 0) g.home.toString() else "none", g.meatMet))
        else {
            val months = (0 until 12).filter { g.seasonPlace[it] >= 0 }
            sb.appendLine("remembered places by month: " + months.joinToString(" ") { m -> "%d:%.0fm".format(m + 1, hw.placeElevationM[g.seasonPlace[m]]) })
        }
        groupInfo.text = sb.toString()
    }

    companion object {
        private val KIND_COLOURS = intArrayOf(0xf0e060, 0xa0e070, 0x70d0f0, 0xf0a050, 0xe070e0, 0x90f0c0, 0xffffff, 0xc0a0ff, 0xffc0c0, 0x80ffff, 0xffd080, 0xb0ff80, 0xff8080, 0x80b0ff, 0xd0d0d0, 0xa0ffa0, 0xffa0ff, 0xc0c060)
    }
}
