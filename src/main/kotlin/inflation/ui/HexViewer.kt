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
import javax.swing.JButton
import javax.swing.JComboBox
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

    /** What is drawn: per group its kind, the way it walked (km, x0 y0 x1 y1 ...), its size. */
    private class Shown(val kind: Int, val carnivore: Boolean, val way: FloatArray, val size: Int)
    @Volatile private var shown = emptyList<Shown>()
    @Volatile private var frame = 0
    private var frames = 1
    private val date = JLabel()
    private val counts = JLabel()
    private val residentChoice = JComboBox(arrayOf("(no resident tint)") + fauna.kinds.map { it.name }.toTypedArray())
    private val map = MapPanel()

    init {
        val bar = JPanel()
        val run = JButton("Run"); run.addActionListener { running = !running; run.text = if (running) "Pause" else "Run" }
        val step = JButton("Step"); step.addActionListener { commands += { advance() } }
        val speed = JSlider(1, 10, stepsPerSecond); speed.addChangeListener { stepsPerSecond = speed.value }
        residentChoice.addActionListener { map.repaint() }
        bar.add(run); bar.add(step); bar.add(JLabel("weeks/s")); bar.add(speed); bar.add(residentChoice); bar.add(date)
        val bottom = JPanel(); bottom.add(counts)
        add(bar, BorderLayout.NORTH); add(map, BorderLayout.CENTER); add(bottom, BorderLayout.SOUTH)
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
        for (g in groups.groups) {
            if (!g.alive) continue
            val way = FloatArray(g.path.size * 2)
            for ((i, h) in g.path.withIndex()) { val (x, y) = hex.centreKm(h); way[2 * i] = x.toFloat(); way[2 * i + 1] = y.toFloat() }
            if (way.isEmpty()) { val (x, y) = hex.centreKm(hw.placeHex[g.place]); list += Shown(g.species.index, g.species.carnivore, floatArrayOf(x.toFloat(), y.toFloat()), g.size); continue }
            list += Shown(g.species.index, g.species.carnivore, way, g.size)
        }
        shown = list
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
            // The groups, each part of the way along the hexes it walked this week.
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

    companion object {
        private val KIND_COLOURS = intArrayOf(0xf0e060, 0xa0e070, 0x70d0f0, 0xf0a050, 0xe070e0, 0x90f0c0, 0xffffff, 0xc0a0ff, 0xffc0c0, 0x80ffff, 0xffd080, 0xb0ff80, 0xff8080, 0x80b0ff, 0xd0d0d0, 0xa0ffa0, 0xffa0ff, 0xc0c060)
    }
}
