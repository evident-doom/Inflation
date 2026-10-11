package inflation.ui

import inflation.world.Date
import inflation.world.LifeForm
import inflation.world.Simulation
import inflation.world.World
import inflation.world.WorldConfig
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSlider
import javax.swing.JTextArea
import javax.swing.JToggleButton
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * Desktop viewer: the world map with layers, time controls, a cell inspector, an event log and
 * tools to build dams, dig canals and irrigate.
 *
 * Usage: viewer [--seed N] [--size KM] [--layer NAME] [--screenshot FILE --steps N]
 *
 * With --screenshot the viewer runs N steps, saves an image of its window and exits.
 */
fun main(args: Array<String>) {
    var cfg = WorldConfig()
    var screenshot: File? = null
    var shotSteps = 0
    var startLayer = Layer.TERRAIN
    var k = 0
    while (k < args.size) {
        when (args[k]) {
            "--seed" -> cfg = cfg.copy(seed = args[++k].toLong())
            "--size" -> { val s = args[++k].toInt(); cfg = cfg.copy(widthKm = s, heightKm = s) }
            "--screenshot" -> screenshot = File(args[++k])
            "--steps" -> shotSteps = args[++k].toInt()
            "--layer" -> startLayer = Layer.valueOf(args[++k].uppercase())
            else -> error("unknown argument ${args[k]}")
        }
        k++
    }
    if (GraphicsEnvironment.isHeadless()) error("the viewer needs a display")
    val frame = JFrame("Inflation: world viewer (seed ${cfg.seed})")
    val status = JLabel("Generating a ${cfg.widthKm} x ${cfg.heightKm} km world…", JLabel.CENTER)
    status.font = status.font.deriveFont(16f)
    frame.contentPane.add(status)
    frame.defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
    frame.size = Dimension(1400, 950)
    frame.isVisible = true
    Thread {
        val world = World.generate(cfg) { step -> SwingUtilities.invokeLater { status.text = "Generating: $step…" } }
        SwingUtilities.invokeLater { status.text = "Starting the simulation…" }
        val sim = Simulation(world)
        SwingUtilities.invokeLater {
            val viewer = Viewer(sim, startLayer)
            frame.contentPane.removeAll()
            frame.contentPane.add(viewer)
            frame.revalidate()
            viewer.fitMap()
            screenshot?.let { viewer.screenshotAfter(shotSteps, it, frame) }
        }
    }.start()
}

enum class Tool(val label: String) {
    INSPECT("Inspect"),
    DAM("Build dam (30 m)"),
    CANAL("Dig canal: click start, then end"),
    IRRIGATE("Irrigate fields around a river cell"),
    CLEAR("Clear vegetation (5 km)"),
}

class Viewer(private val sim: Simulation, startLayer: Layer = Layer.TERRAIN) : JPanel(BorderLayout()) {
    private val world = sim.world
    private val grid = world.grid
    private val painter = LayerPainter(sim)
    private val pixels = IntArray(grid.size)
    private val image = BufferedImage(grid.width, grid.height, BufferedImage.TYPE_INT_RGB)
    @Volatile private var layer = startLayer
    @Volatile private var running = false
    @Volatile private var stepsPerSecond = 5
    @Volatile private var dirty = true
    private val commands = ConcurrentLinkedQueue<() -> Unit>()
    private val map = MapView()
    private val dateLabel = JLabel()
    private val climateLabel = JLabel()
    private val info = JTextArea(14, 34)
    private val log = JTextArea(12, 34)
    private var tool = Tool.INSPECT
    private var canalStart = -1
    private var hover = -1

    init {
        val play = JToggleButton("▶ Play")
        play.addActionListener { running = play.isSelected; play.text = if (running) "⏸ Pause" else "▶ Play" }
        val step = JButton("Step")
        step.addActionListener { commands += { sim.step() } }
        val year = JButton("+1 year")
        year.addActionListener { commands += { repeat(Date.DAYS_PER_YEAR / world.config.daysPerStep) { sim.step() } } }
        val speed = JSlider(1, 30, stepsPerSecond)
        speed.addChangeListener { stepsPerSecond = speed.value }
        val layers = JComboBox(Layer.entries.map { it.label }.toTypedArray())
        layers.selectedIndex = startLayer.ordinal
        layers.addActionListener { layer = Layer.entries[layers.selectedIndex]; dirty = true }
        val tools = JComboBox(Tool.entries.map { it.label }.toTypedArray())
        tools.addActionListener { tool = Tool.entries[tools.selectedIndex]; canalStart = -1 }

        val bar = JPanel()
        bar.add(play); bar.add(step); bar.add(year)
        bar.add(JLabel("Speed (steps/s)")); bar.add(speed)
        bar.add(JLabel("Layer")); bar.add(layers)
        bar.add(JLabel("Tool")); bar.add(tools)

        dateLabel.font = dateLabel.font.deriveFont(Font.BOLD, 15f)
        val top = JPanel()
        top.layout = BoxLayout(top, BoxLayout.Y_AXIS)
        top.add(bar)
        val dateRow = JPanel(); dateRow.add(dateLabel); dateRow.add(climateLabel)
        top.add(dateRow)

        for (t in listOf(info, log)) { t.isEditable = false; t.font = Font(Font.MONOSPACED, Font.PLAIN, 12); t.lineWrap = true; t.wrapStyleWord = true }
        val side = JPanel()
        side.layout = BoxLayout(side, BoxLayout.Y_AXIS)
        side.add(titled(JScrollPane(info), "Cell"))
        side.add(titled(JScrollPane(log), "Events"))
        side.preferredSize = Dimension(380, 800)

        add(top, BorderLayout.NORTH)
        add(map, BorderLayout.CENTER)
        add(side, BorderLayout.EAST)
        updateLabels()
        Thread(::loop, "simulation").apply { isDaemon = true }.start()
    }

    private fun titled(c: javax.swing.JComponent, title: String) = c.also { it.border = BorderFactory.createTitledBorder(title) }

    /** Simulation thread: runs commands and steps, repaints the image after each change. */
    private fun loop() {
        var last = System.nanoTime()
        while (true) {
            var changed = false
            while (true) { val c = commands.poll() ?: break; c(); changed = true }
            if (running && System.nanoTime() - last >= 1_000_000_000L / stepsPerSecond) {
                last = System.nanoTime()
                sim.step(); changed = true
            }
            if (changed || dirty) {
                dirty = false
                painter.paint(layer, pixels)
                synchronized(image) { image.setRGB(0, 0, grid.width, grid.height, pixels, 0, grid.width) }
                SwingUtilities.invokeLater { updateLabels(); map.repaint() }
            } else Thread.sleep(5)
        }
    }

    private fun updateLabels() {
        val d = sim.date
        dateLabel.text = "Year ${d.year + 1} · month ${d.month + 1}, day ${d.dayOfMonth} · ${d.season.name.lowercase()} · moon: ${d.moonPhase.name.lowercase().replace('_', ' ')}"
        val w = sim.weather
        val events = w.activeEvents().joinToString { it.kind.name.lowercase().replace('_', ' ') }
        climateLabel.text = "   oscillation %+.1f · decadal %+.1f%s%s".format(
            w.oscillation, w.decadal,
            if (w.volcanicCooling > 0.05) " · volcanic cooling %.1f °C".format(w.volcanicCooling) else "",
            if (events.isNotEmpty()) " · now: $events" else "",
        )
        if (log.lineCount - 1 != sim.log.size) log.text = sim.log.takeLast(200).joinToString("\n")
        if (hover >= 0) info.text = describe(hover)
    }

    private fun describe(i: Int): String = buildString {
        val x = grid.x(i); val y = grid.y(i)
        appendLine("Cell ($x, $y)  %.2f°N".format(world.latitude(i)))
        appendLine("Elevation %.0f m".format(world.elevation[i]))
        if (world.isSea[i]) { appendLine("Sea"); return@buildString }
        appendLine("Rock ${world.geology.rock[i].name.lowercase()}, sediment %.0f m".format(world.geology.sediment[i]))
        appendLine("Potential biome ${world.biome[i].name.lowercase().replace('_', ' ')}")
        appendLine("Soil fertility %.2f".format(sim.vegetation.fertility[i]))
        appendLine("Now %.1f °C, rain %.0f mm this step, snow %.0f mm".format(sim.weather.temperature[i], sim.weather.precipitation[i], sim.weather.snowpack[i]))
        appendLine("Soil water %.0f of %.0f mm".format(sim.water.soil[i], 150f))
        val v = sim.vegetation
        appendLine("Vegetation kg/m²: herbs %.2f, shrubs %.2f, trees %.1f".format(v.herb[i], v.shrub[i], v.tree[i]))
        if (v.burnt[i] > 0f) appendLine("Burnt recently")
        if (sim.eruptions.ash[i] > 0.5f) appendLine("Ash %.0f mm".format(sim.eruptions.ash[i]))
        val mean = world.water.discharge[i]
        if (mean >= 0.3f || sim.water.discharge[i] >= 0.3f) {
            appendLine("River: %.1f m³/s now, %.1f mean (%s)".format(sim.water.discharge[i], mean, world.riverClass(i).name.lowercase().replace('_', ' ')))
            if (world.isNavigable(i)) appendLine("Navigable by canoe or raft")
        }
        if (world.floodplain.isFloodplain[i]) appendLine("Floodplain, %.1f m above the river".format(world.floodplain.hand[i]))
        if (sim.water.flood[i] > 0f) appendLine("FLOODED %.1f m".format(sim.water.flood[i]))
        if (sim.water.submerged[i]) appendLine("Under a reservoir")
        if (world.water.isLake(i)) appendLine(if (world.water.lakeClosed[i]) "Salt lake" else "Lake")
        world.volcanoes.firstOrNull { abs(it.x - x) <= 3 && abs(it.y - y) <= 3 }?.let {
            appendLine("Volcano: ${it.kind.name.lowercase().replace('_', ' ')}, ${if (it.active) "active" else "extinct"}, %.1f Myr old".format(it.ageMyr))
        }
    }

    private fun apply(i: Int) {
        when (tool) {
            Tool.INSPECT -> {}
            Tool.DAM -> commands += {
                val dam = sim.water.addDam(i, 30f)
                sim.log += if (dam == null) "Cannot build a dam here" else
                    "${sim.date}: dam built at (${grid.x(i)}, ${grid.y(i)}), reservoir up to %.0f million m³ over %d km²".format(dam.capacityM3 / 1e6f, dam.cells.size)
            }
            Tool.CANAL -> if (canalStart < 0) canalStart = i else {
                val from = canalStart; canalStart = -1
                val path = line(from, i)
                commands += {
                    val d = sim.water.addDiversion(from, path, 0.5f)
                    sim.log += if (d == null) "Canal refused: it must run downhill and not loop back into its source" else "${sim.date}: canal dug from (${grid.x(from)}, ${grid.y(from)}) to (${grid.x(i)}, ${grid.y(i)}), taking half the flow"
                }
            }
            Tool.IRRIGATE -> commands += {
                val fields = ArrayList<Int>()
                for (dy in -2..2) for (dx in -2..2) {
                    val x = grid.x(i) + dx; val y = grid.y(i) + dy
                    if (grid.inBounds(x, y) && !world.isSea[grid.index(x, y)]) fields += grid.index(x, y)
                }
                sim.water.addIrrigation(i, fields.toIntArray(), 15f)
                sim.log += "${sim.date}: irrigation from (${grid.x(i)}, ${grid.y(i)}) onto ${fields.size} km²"
            }
            Tool.CLEAR -> commands += {
                for (dy in -5..5) for (dx in -5..5) {
                    val x = grid.x(i) + dx; val y = grid.y(i) + dy
                    if (!grid.inBounds(x, y)) continue
                    val c = grid.index(x, y)
                    for (f in LifeForm.entries) sim.vegetation.consume(c, f, Float.MAX_VALUE)
                }
                sim.log += "${sim.date}: vegetation cleared around (${grid.x(i)}, ${grid.y(i)})"
            }
        }
    }

    /** Cells on a straight line from a to b (exclusive of a), stepping to neighbours. */
    private fun line(a: Int, b: Int): IntArray {
        val out = ArrayList<Int>()
        var x = grid.x(a); var y = grid.y(a)
        val tx = grid.x(b); val ty = grid.y(b)
        while (x != tx || y != ty) {
            x += Integer.signum(tx - x); y += Integer.signum(ty - y)
            out += grid.index(x, y)
        }
        return out.toIntArray()
    }

    fun fitMap() = map.fit()

    /** Runs [steps] steps, then saves the window to [file] and exits. */
    fun screenshotAfter(steps: Int, file: File, frame: JFrame) {
        hover = grid.index(grid.width / 2, grid.height / 2)
        commands += {
            repeat(steps) { sim.step() }
            dirty = true
            Thread.sleep(500)
            SwingUtilities.invokeLater {
                updateLabels()
                Thread {
                    Thread.sleep(1500)
                    SwingUtilities.invokeAndWait {
                        val img = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_RGB)
                        frame.paint(img.graphics)
                        ImageIO.write(img, "png", file)
                    }
                    System.exit(0)
                }.start()
            }
        }
    }

    private inner class MapView : JPanel() {
        var scale = 1.0
        var ox = 0.0
        var oy = 0.0
        private var drag: Point? = null

        init {
            background = Color(0x1f3f6f)
            val mouse = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) { drag = e.point }
                override fun mouseReleased(e: MouseEvent) { drag = null }
                override fun mouseDragged(e: MouseEvent) {
                    val d = drag ?: return
                    ox += e.x - d.x; oy += e.y - d.y; drag = e.point; repaint()
                }
                override fun mouseMoved(e: MouseEvent) {
                    hover = cellAt(e.x, e.y)
                    if (hover >= 0) info.text = describe(hover)
                }
                override fun mouseClicked(e: MouseEvent) { val c = cellAt(e.x, e.y); if (c >= 0) apply(c) }
                override fun mouseWheelMoved(e: MouseWheelEvent) {
                    val f = 1.25.pow(-e.preciseWheelRotation)
                    val ns = (scale * f).coerceIn(0.2, 40.0)
                    ox = e.x - (e.x - ox) * ns / scale; oy = e.y - (e.y - oy) * ns / scale
                    scale = ns; repaint()
                }
            }
            addMouseListener(mouse); addMouseMotionListener(mouse); addMouseWheelListener(mouse)
        }

        fun fit() {
            scale = max(0.2, minOf(width.toDouble() / grid.width, height.toDouble() / grid.height))
            ox = (width - grid.width * scale) / 2; oy = (height - grid.height * scale) / 2
            repaint()
        }

        fun cellAt(px: Int, py: Int): Int {
            val x = ((px - ox) / scale).toInt(); val y = ((py - oy) / scale).toInt()
            return if (grid.inBounds(x, y)) grid.index(x, y) else -1
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, if (scale >= 1) RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR else RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            synchronized(image) {
                g2.drawImage(image, ox.toInt(), oy.toInt(), (grid.width * scale).toInt(), (grid.height * scale).toInt(), null)
            }
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            for (v in world.volcanoes) {
                if (world.elevation[grid.index(v.x, v.y)] < -200f) continue
                val x = (ox + (v.x + 0.5) * scale).toInt(); val y = (oy + (v.y + 0.5) * scale).toInt()
                val erupting = sim.eruptions.history.lastOrNull { it.volcano === v }?.let { sim.date.absoluteDay - it.date.absoluteDay < 30 } == true
                g2.color = when { erupting -> Color(0xffa000); v.active -> Color(0xd02010); else -> Color(0x404040) }
                g2.fillPolygon(intArrayOf(x - 6, x + 6, x), intArrayOf(y + 5, y + 5, y - 7), 3)
            }
            g2.color = Color(0xffd000)
            for (d in sim.water.dams) {
                val x = (ox + (grid.x(d.cell) + 0.5) * scale).toInt(); val y = (oy + (grid.y(d.cell) + 0.5) * scale).toInt()
                g2.fillRect(x - 4, y - 4, 8, 8)
            }
            g2.color = Color(0x00e0e0)
            for (c in sim.water.diversions) {
                var px = grid.x(c.from); var py = grid.y(c.from)
                for (p in c.path) {
                    val nx = grid.x(p); val ny = grid.y(p)
                    g2.drawLine((ox + (px + 0.5) * scale).toInt(), (oy + (py + 0.5) * scale).toInt(), (ox + (nx + 0.5) * scale).toInt(), (oy + (ny + 0.5) * scale).toInt())
                    px = nx; py = ny
                }
            }
            if (canalStart >= 0) {
                g2.color = Color.WHITE
                g2.drawOval((ox + grid.x(canalStart) * scale).toInt() - 5, (oy + grid.y(canalStart) * scale).toInt() - 5, 10, 10)
            }
            legend(g2)
        }

        private fun legend(g2: Graphics2D) {
            val text = when (layer) {
                Layer.TERRAIN -> "Blue lines: rivers (width by flow) · light blue: flooded · white: snow · grey: ash · triangles: volcanoes (red active, orange erupting)"
                Layer.VEGETATION -> "Dark green: forest · olive: shrubs · light green: grass · tan: bare · black: burnt · brown: trails"
                Layer.RIVER_FLOW -> "River flow against its mean: red low, white normal, blue high"
                Layer.SOIL_MOISTURE -> "Soil water: tan dry to blue saturated"
                Layer.TEMPERATURE -> "Air temperature this step, -20 °C (blue) to 40 °C (red)"
                Layer.PRECIPITATION -> "Rain and snow this step"
                else -> layer.label
            }
            g2.font = g2.font.deriveFont(12f)
            val w = g2.fontMetrics.stringWidth(text) + 16
            g2.color = Color(0, 0, 0, 150)
            g2.fillRect(8, height - 30, w, 22)
            g2.color = Color.WHITE
            g2.drawString(text, 16, height - 14)
        }
    }
}
