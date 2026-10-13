package inflation.ui

import inflation.ecology.Catalogue
import inflation.ecology.HexFauna
import inflation.ecology.HexGroups
import inflation.ecology.HexSim
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
    private val sim = HexSim(world)
    private val cfg = sim.cfg
    private val hex = sim.hex
    private val hw = sim.hw
    private val weather = sim.weather
    private val land = sim.land
    private val fauna = sim.fauna
    private val groups = sim.groups
    private val px = 2
    private val ground: BufferedImage = HexPicture.render(world, hex, 0.0, 0.0, cfg.widthKm.toDouble(), cfg.heightKm.toDouble(), px)
    private val commands = ConcurrentLinkedQueue<() -> Unit>()
    @Volatile private var running = false
    @Volatile private var stepsPerSecond = 2

    /** What is drawn: per group its id, kind, the way it walked (km, x0 y0 x1 y1 ...; from where it was drawn last), its size. */
    private class Shown(val id: Int, val kind: Int, val carnivore: Boolean, val way: FloatArray, val size: Int, val health: Float)

    /** What the hexes are tinted by. */
    private enum class Tint(val label: String) {
        NONE("Ground only"), TEMPERATURE("Temperature"), SNOW("Snow lying"), RAIN("Rain this week"), GREEN("Green forage"),
        FORAGE("Grass and browse"), SOIL("Soil water"), WATER("Drinking water"), SMALL_GAME("Rodents and reptiles"), INSECTS("Insects"), BITING("Biting insects"), POLLINATORS("Pollinators"), ORGANIC("Soil organic matter"), CARRION("Carrion"), RESIDENTS("Resident herds (all)"),
    }
    private val tintChoice = JComboBox(Tint.entries.map { it.label }.toTypedArray())
    private val windBox = javax.swing.JCheckBox("Wind", false)
    private val herdsBox = javax.swing.JCheckBox("Herds", true)
    private val huntersBox = javax.swing.JCheckBox("Hunters", true)
    private val peopleBox = javax.swing.JCheckBox("People", true)

    /**
     * The place whose weather stands for hex [h]: its own lowest band, or for a sea or lake hex the
     * nearest land within a few rings (the weather is worked out on land only); -1 if none.
     */
    private fun windPlace(h: Int): Int {
        (0 until HexWorld.BANDS_M.size).map { hw.placeOf[it][h] }.firstOrNull { it >= 0 }?.let { return it }
        var ring = listOf(h); val seen = hashSetOf(h)
        repeat(4) {
            val next = ArrayList<Int>()
            for (r in ring) for (d in 0 until 6) { val q = hex.neighbour(r, d); if (q >= 0 && seen.add(q)) next += q }
            for (q in next) (0 until HexWorld.BANDS_M.size).map { hw.placeOf[it][q] }.firstOrNull { it >= 0 }?.let { return it }
            ring = next
        }
        return -1
    }
    /** Whose sense of direction to draw as arrows: a resident kind or a kind living in groups. */
    private val pullChoice by lazy { JComboBox(arrayOf("(no sense of direction)") + (fauna.kinds + groups.kinds).map { "pull: " + it.name }.toTypedArray()) }
    /** Where each group was drawn at the end of the last move, km. */
    private val lastDrawn = HashMap<Int, Pair<Float, Float>>()
    @Volatile private var shown = emptyList<Shown>()
    @Volatile private var frame = 0
    private var frames = 1
    private val date = JLabel()
    private val counts = JLabel()
    /** Which kind's numbers to tint the hexes by: any resident kind, or any kind in herds and packs. */
    private val residentChoice by lazy { JComboBox(arrayOf("(no kind shown)") + (fauna.kinds + groups.kinds).map { "where: " + it.name }.toTypedArray()) }
    private val map = MapPanel()
    /** The hex being inspected, -1 for none, and its groups. */
    @Volatile private var picked = -1
    private val info = JTextArea(14, 40).apply { isEditable = false; (caret as javax.swing.text.DefaultCaret).updatePolicy = javax.swing.text.DefaultCaret.NEVER_UPDATE; font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12) }
    private val groupList = DefaultListModel<String>()
    private val groupView = JList(groupList)
    private val groupInfo = JTextArea(12, 40).apply { isEditable = false; (caret as javax.swing.text.DefaultCaret).updatePolicy = javax.swing.text.DefaultCaret.NEVER_UPDATE; font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12) }
    private var groupIds = IntArray(0)

    init {
        val bar = JPanel()
        val run = JButton("Run"); run.addActionListener { running = !running; run.text = if (running) "Pause" else "Run" }
        val step = JButton("Step"); step.addActionListener { commands += { advance() } }
        val speed = JSlider(1, 10, stepsPerSecond); speed.addChangeListener { stepsPerSecond = speed.value }
        residentChoice.addActionListener { map.repaint() }
        tintChoice.addActionListener { map.repaint() }
        windBox.addActionListener { map.repaint() }
        herdsBox.addActionListener { map.repaint() }; huntersBox.addActionListener { map.repaint() }; peopleBox.addActionListener { map.repaint() }
        pullChoice.addActionListener { map.repaint() }
        bar.add(run); bar.add(step); bar.add(JLabel("weeks/s")); bar.add(speed); bar.add(tintChoice); bar.add(residentChoice); bar.add(herdsBox); bar.add(huntersBox); bar.add(peopleBox); bar.add(windBox); bar.add(pullChoice)
        val graphs = JButton("History…"); graphs.addActionListener { HexHistoryPanel.show(history, live = true, title = "History, seed ${cfg.seed}") }
        bar.add(graphs); bar.add(date)
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

    /** Week-by-week history of everything worth watching, for the graphs. */
    private val history = inflation.ecology.HexHistory()

    private fun advance() {
        sim.step()
        history.record(sim)
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
            // How well it is doing: fed last week and its reserves.
            val health = (0.5f * g.fed + 0.5f * g.fat / g.species.maxFatDays.coerceAtLeast(1f)).coerceIn(0f, 1f)
            list += Shown(g.id, g.species.index, g.species.carnivore, pts.toFloatArray(), g.size, health)
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
            // The chosen measure as a tint over the hexes: the area-weighted mean of its bands.
            val tint = Tint.entries[tintChoice.selectedIndex]
            if (tint != Tint.NONE) {
                val rad = hex.hexKm / kotlin.math.sqrt(3.0)
                val sum = DoubleArray(hex.size); val area = DoubleArray(hex.size)
                for (p in 0 until fauna.n) {
                    val v = when (tint) {
                        Tint.TEMPERATURE -> weather.temperature[p]
                        Tint.SNOW -> weather.snowpack[p]
                        Tint.RAIN -> weather.precipitation[p]
                        Tint.GREEN -> land.green[p]
                        Tint.FORAGE -> land.herb[p] + land.shrub[p]
                        Tint.SOIL -> land.soil[p]
                        Tint.WATER -> land.water[p]
                        Tint.INSECTS -> fauna.small.insects[p]
                        Tint.BITING -> fauna.small.biting[p]
                        Tint.POLLINATORS -> fauna.small.pollinators[p]
                        Tint.ORGANIC -> fauna.small.organicMatter[p]
                        Tint.SMALL_GAME -> fauna.small.rodents[p] + fauna.small.herps[p]
                        Tint.CARRION -> fauna.small.carrion[p] * 1e6f
                        Tint.RESIDENTS -> fauna.kinds.indices.sumOf { (fauna.adults[it][p] + fauna.young[it][p]).toDouble() }.toFloat()
                        Tint.NONE -> 0f
                    }
                    val h = hw.placeHex[p]; sum[h] += v * land.areaKm2[p].toDouble(); area[h] += land.areaKm2[p].toDouble()
                }
                for (h in 0 until hex.size) {
                    if (area[h] <= 0) continue
                    val v = (sum[h] / area[h]).toFloat()
                    val colour = when (tint) {
                        Tint.TEMPERATURE -> heat(((v + 15f) / 50f).coerceIn(0f, 1f), 0.55f)
                        Tint.SNOW -> if (v < 5f) null else Color(1f, 1f, 1f, (0.25f + 0.6f * min(1f, v / 300f)))
                        Tint.RAIN -> if (v < 2f) null else Color(0.1f, 0.3f, 1f, (0.2f + 0.6f * min(1f, v / 80f)))
                        Tint.GREEN -> Color(1f - v, 0.9f, 0.1f, 0.5f)
                        Tint.FORAGE -> Color(0.1f, 0.7f, 0.1f, (0.1f + 0.7f * min(1f, v / 2f)))
                        Tint.SOIL -> Color(0.5f * (1f - v / 150f), 0.3f, min(1f, v / 150f), 0.55f)
                        Tint.WATER -> Color(0.1f, 0.4f, 1f, 0.1f + 0.6f * v)
                        Tint.INSECTS -> Color(0.6f, 0.8f, 0.1f, (0.1f + 0.7f * min(1f, v / 1500f)))
                        Tint.BITING -> Color(0.8f, 0.1f, 0.1f, (0.05f + 0.7f * min(1f, v / 400f)))
                        Tint.POLLINATORS -> Color(1f, 0.85f, 0.1f, (0.05f + 0.7f * min(1f, v / 50f)))
                        Tint.ORGANIC -> Color(0.35f, 0.2f, 0.05f, (0.05f + 0.7f * min(1f, v / 8f)))
                        Tint.SMALL_GAME -> Color(0.9f, 0.5f, 0.1f, (0.1f + 0.7f * min(1f, v / 60f)))
                        Tint.CARRION -> Color(0.4f, 0.1f, 0.4f, (0.1f + 0.7f * min(1f, v / 20f)))
                        Tint.RESIDENTS -> Color(1f, 0.1f, 0.6f, (0.05f + 0.7f * min(1f, v / 20f)))
                        Tint.NONE -> null
                    } ?: continue
                    val (cx, cy) = hex.centreKm(h)
                    val xs = IntArray(6) { sx((cx + rad * kotlin.math.cos(Math.PI / 6 + it * Math.PI / 3)).toFloat()) }
                    val ys = IntArray(6) { sy((cy + rad * kotlin.math.sin(Math.PI / 6 + it * Math.PI / 3)).toFloat()) }
                    g.color = colour; g.fillPolygon(xs, ys, 6)
                }
            }
            // Wind as small arrows, every few hexes, longer and brighter the stronger.
            if (windBox.isSelected) {
                val every = max(1, (2.0 / scale).toInt())
                for (h in 0 until hex.size) {
                    val col = h % hex.cols; val row = h / hex.cols
                    if (col % every != 0 || row % every != 0) continue
                    val p = windPlace(h).takeIf { it >= 0 } ?: continue
                    val u = weather.windEast(p); val v = weather.windSouth(p)
                    val speed = hypot(u, v)
                    if (speed < 0.3f) continue
                    val (cx, cy) = hex.centreKm(h)
                    val len = (min(1f, speed / 12f) * hex.hexKm * every * 0.8).toFloat()
                    val x0 = sx(cx.toFloat()); val y0 = sy(cy.toFloat())
                    val x1 = sx(cx.toFloat() + u / speed * len); val y1 = sy(cy.toFloat() + v / speed * len)
                    g.color = Color(1f, 1f, 1f, (0.4f + 0.6f * min(1f, speed / 12f)))
                    g.drawLine(x0, y0, x1, y1)
                    val ang = kotlin.math.atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())
                    for (sgn in intArrayOf(-1, 1)) g.drawLine(x1, y1, (x1 - 5 * kotlin.math.cos(ang + sgn * 0.5)).toInt(), (y1 - 5 * kotlin.math.sin(ang + sgn * 0.5)).toInt())
                }
            }
            // A kind's sense of where better country lies, as yellow arrows, longer the stronger.
            val pk = pullChoice.selectedIndex - 1
            if (pk >= 0) {
                val (b, k) = if (pk < fauna.kinds.size) fauna.bearings to pk else groups.bearings to pk - fauna.kinds.size
                val every = max(1, (2.0 / scale).toInt())
                for (h in 0 until hex.size) {
                    val col = h % hex.cols; val row = h / hex.cols
                    if (col % every != 0 || row % every != 0 || hex.heart[h] < 0) continue
                    val u = b.east[k][h]; val v = b.south[k][h]
                    val st = hypot(u, v)
                    if (st < 0.02f) continue
                    val (cx, cy) = hex.centreKm(h)
                    val len = (min(1f, st * 2f) * hex.hexKm * every * 0.8).toFloat()
                    val x0 = sx(cx.toFloat()); val y0 = sy(cy.toFloat())
                    val x1 = sx(cx.toFloat() + u / st * len); val y1 = sy(cy.toFloat() + v / st * len)
                    g.color = Color(1f, 0.85f, 0.1f, (0.4f + 0.6f * min(1f, st * 2f)))
                    g.drawLine(x0, y0, x1, y1)
                    val ang = kotlin.math.atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())
                    for (sgn in intArrayOf(-1, 1)) g.drawLine(x1, y1, (x1 - 5 * kotlin.math.cos(ang + sgn * 0.5)).toInt(), (y1 - 5 * kotlin.math.sin(ang + sgn * 0.5)).toInt())
                }
            }
            // A resident kind's density as a tint over the hexes.
            val r = residentChoice.selectedIndex - 1
            if (r >= 0) {
                val rad = hex.hexKm / kotlin.math.sqrt(3.0)
                val dens = DoubleArray(hex.size); val area = DoubleArray(hex.size)
                for (p in 0 until fauna.n) { val h = hw.placeHex[p]; area[h] += land.areaKm2[p].toDouble() }
                if (r < fauna.kinds.size) for (p in 0 until fauna.n) dens[hw.placeHex[p]] += ((fauna.adults[r][p] + fauna.young[r][p]) * land.areaKm2[p]).toDouble()
                else { val s = groups.kinds[r - fauna.kinds.size]; for (gr in groups.groups) if (gr.alive && gr.species == s) dens[hw.placeHex[gr.place]] += gr.size.toDouble() }
                // Shaded against the densest twentieth of hexes, so that thin spreads show too.
                val values = (0 until hex.size).filter { area[it] > 0 && dens[it] > 0 }.map { dens[it] / area[it] }.sorted()
                val top = (if (values.isEmpty()) 0.0 else values[(values.size * 0.95).toInt().coerceAtMost(values.size - 1)]).coerceAtLeast(1e-6)
                for (h in 0 until hex.size) {
                    if (area[h] <= 0) continue
                    if (dens[h] <= 0) continue
                    val v = kotlin.math.sqrt(dens[h] / area[h] / top).toFloat().coerceIn(0.15f, 1f)
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
                val sp = Catalogue.all[s.kind]
                if (if (sp.human) !peopleBox.isSelected else if (s.carnivore) !huntersBox.isSelected else !herdsBox.isSelected) continue
                val w = s.way
                val (x, y) = along(w, t)
                if (sp.human) {
                    // A band: a white-ringed blue diamond, fading as it goes hungry.
                    val a = 0.35f + 0.65f * s.health
                    val px = sx(x); val py = sy(y)
                    g.color = Color(1f, 1f, 1f, a); g.fillPolygon(intArrayOf(px, px + 5, px, px - 5), intArrayOf(py - 5, py, py + 5, py), 4)
                    g.color = Color(0.1f, 0.3f, 0.95f, a); g.fillPolygon(intArrayOf(px, px + 3, px, px - 3), intArrayOf(py - 3, py, py + 3, py), 4)
                } else if (s.carnivore) {
                    // Hunters fade as they starve.
                    val a = 0.25f + 0.75f * s.health
                    g.color = Color(1f, 1f, 1f, a); g.fillRect(sx(x) - 3, sy(y) - 3, 7, 7)
                    g.color = Color(0.82f, 0.06f, 0.06f, a); g.fillRect(sx(x) - 2, sy(y) - 2, 5, 5)
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
            sb.appendLine("   herbs %.2f shrubs %.2f trees %.1f kg/m², green %.0f%%, rugged %.2f, refuge %.2f, lookout %.0f km".format(land.herb[p], land.shrub[p], land.tree[p], 100 * land.green[p], land.ruggedness[p], land.bestDefence[p], land.bestViewKm[p]))
            sb.appendLine("   rain %.0f mm this week, wind %.1f m/s; drinking water within reach of %.0f%% (walk %.1f km)".format(weather.precipitation[p], hypot(weather.windEast(p), weather.windSouth(p)), 100 * land.water[p], land.waterKm[p]))
            val sl = fauna.small
            sb.appendLine("   insects %.0f, biting %.0f, rodents %.0f, reptiles %.0f, pollinators %.0f kg/km²; litter %.2f, organic %.1f kg/m²".format(sl.insects[p], sl.biting[p], sl.rodents[p], sl.herps[p], sl.pollinators[p], sl.litter[p], sl.organicMatter[p]))
            val res = fauna.kinds.indices.filter { fauna.adults[it][p] + fauna.young[it][p] > 0.01f }
                .joinToString(", ") { "%s %.1f".format(fauna.kinds[it].name, fauna.adults[it][p] + fauna.young[it][p]) }
            if (res.isNotEmpty()) sb.appendLine("   resident herds per km²: $res")
        }
        // Everything living here, in head counts for the whole hex.
        var totalArea = 0f
        val residents = DoubleArray(fauna.kinds.size)
        var food = 0.0; var small = 0.0; var carrion = 0.0
        for (b in 0 until HexWorld.BANDS_M.size) {
            val p = hw.placeOf[b][h]
            if (p < 0) continue
            val a = land.areaKm2[p]; totalArea += a
            for (k in fauna.kinds.indices) residents[k] += ((fauna.adults[k][p] + fauna.young[k][p]) * a).toDouble()
            food += ((land.herb[p] + land.shrub[p]) * a).toDouble() // kg/m² × km² = 1e6 kg
            small += ((fauna.small.rodents[p] + fauna.small.herps[p]) * a).toDouble(); carrion += (fauna.small.carrion[p] * 1e6f * a).toDouble()
        }
        val gs = groups.groups.filter { it.alive && hw.placeHex[it.place] == h }
        sb.appendLine("country: " + describe(h))
        sb.appendLine("in the hex (%.0f km²):".format(totalArea))
        sb.appendLine("   resident herds: " + fauna.kinds.indices.filter { residents[it] >= 0.5 }.joinToString(", ") { "%s %.0f".format(fauna.kinds[it].name, residents[it]) }.ifEmpty { "none" })
        sb.appendLine("   herds and packs: " + gs.groupBy { it.species }.entries.joinToString(", ") { (s, l) -> "%s %d in %d".format(s.name, l.sumOf { it.size }, l.size) }.ifEmpty { "none" })
        sb.appendLine("   forage (grass and browse) %.0f t, rodents and reptiles %.1f t, carrion %.1f t".format(food * 1000, small / 1000, carrion / 1000))
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
        // Refill the list in place, keeping the picked group and where the list is scrolled to.
        val picked = groupView.selectedIndex.let { if (it in groupIds.indices) groupIds[it] else -1 }
        groupIds = here.map { it.id }.toIntArray()
        val rows = here.map { g -> "%s #%d: %d adults, %d young, fed %.0f%%, reserves %.0f d".format(g.species.name, g.id, g.adults, g.young, 100 * g.fed, g.fat) }
        for ((i, r) in rows.withIndex()) if (i < groupList.size()) { if (groupList[i] != r) groupList[i] = r } else groupList.addElement(r)
        while (groupList.size() > rows.size) groupList.removeElementAt(groupList.size() - 1)
        val again = groupIds.indexOf(picked)
        if (again >= 0) { groupView.selectedIndex = again; showGroup() } else { groupView.clearSelection(); groupInfo.text = "" }
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

    /**
     * The country of hex [h] in words: its kinds of country, how thick the grass, scrub and trees
     * are, how green, and the fruit and dry fodder this season.
     */
    private fun describe(h: Int): String {
        val names = inflation.world.Biome.entries.map { it to hw.biome[it.ordinal][h] }.filter { it.second >= 0.1f }.sortedByDescending { it.second }
        val parts = ArrayList<String>()
        if (hw.sea[h] > 0.3f) parts += "%s sea".format(share(hw.sea[h]))
        if (hw.lake[h] > 0.1f) parts += "%s lake".format(share(hw.lake[h]))
        for ((b, s) in names.take(3)) parts += "%s %s".format(share(s), b.name.lowercase().replace('_', ' '))
        // Plants over the land, area-weighted over the height bands.
        var a = 0.0; var herb = 0.0; var shrub = 0.0; var tree = 0.0; var green = 0.0; var fruit = 0.0; var straw = 0.0; var snow = 0.0; var burnt = 0.0
        for (b in 0 until HexWorld.BANDS_M.size) {
            val p = hw.placeOf[b][h]; if (p < 0) continue
            val w = land.areaKm2[p].toDouble(); a += w
            herb += land.herb[p] * w; shrub += land.shrub[p] * w; tree += land.tree[p] * w; green += land.green[p] * w
            fruit += fauna.food.fruit[p] * w; straw += fauna.food.straw[p] * w; snow += weather.snowpack[p] * w; burnt += land.burnt[p] * w
        }
        if (a <= 0.0) return parts.joinToString("; ").ifEmpty { "water" }
        herb /= a; shrub /= a; tree /= a; green /= a; fruit /= a; straw /= a; snow /= a; burnt /= a
        val grass = when { herb < 0.05 -> "bare or nearly bare ground"; herb < 0.2 -> "thin grass"; herb < 0.5 -> "fair grass"; else -> "thick grass" }
        val scrub = when { shrub < 0.2 -> null; shrub < 1.0 -> "scattered scrub"; else -> "dense scrub" }
        val wood = when { tree < 1.0 -> null; tree < 8.0 -> "open woodland"; else -> "closed forest" }
        val state = when { snow > 50 -> "under snow"; green > 0.6 -> "green and growing"; green > 0.3 -> "partly green"; else -> "dry or dormant" }
        val plants = listOfNotNull(grass, scrub, wood).joinToString(", ") + " ($state)"
        val extras = listOfNotNull(
            if (fruit > 0.0005) "fruit ripe" else null,
            if (straw > 0.0008) "plenty of standing dry fodder" else if (straw < 0.0002) "little dry fodder" else null,
            if (burnt > 0.1) "lately burnt" else null,
        )
        return (parts + plants + extras).joinToString("; ")
    }

    private fun share(s: Float) = when { s > 0.8f -> "almost all"; s > 0.55f -> "mostly"; s > 0.35f -> "much"; s > 0.2f -> "a fifth or more"; else -> "some" }

    /** A colour from blue (0) through green and yellow to red (1), with alpha [a]. */
    private fun heat(v: Float, a: Float): Color {
        val r = (1.5f * v - 0.25f).coerceIn(0f, 1f); val g = (1f - kotlin.math.abs(v - 0.5f) * 2f).coerceIn(0f, 1f); val b = (1f - 1.5f * v).coerceIn(0f, 1f)
        return Color(r, g, b, a)
    }

    companion object {
        private val KIND_COLOURS = intArrayOf(0xf0e060, 0xa0e070, 0x70d0f0, 0xf0a050, 0xe070e0, 0x90f0c0, 0xffffff, 0xc0a0ff, 0xffc0c0, 0x80ffff, 0xffd080, 0xb0ff80, 0xff8080, 0x80b0ff, 0xd0d0d0, 0xa0ffa0, 0xffa0ff, 0xc0c060)
    }
}
