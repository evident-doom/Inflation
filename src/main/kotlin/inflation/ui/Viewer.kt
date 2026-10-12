package inflation.ui

import inflation.learning.BandPolicy
import inflation.learning.Mlp

import inflation.ecology.Catalogue
import inflation.ecology.SeaCatalogue
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
 * Usage: viewer [--seed N] [--size KM] [--width KM] [--height KM] [--layer NAME] [--people FILE] [--fauna DIR]
 *               [--screenshot FILE --steps N]
 *
 * With --people the bands choose their camps with that network, and the viewer reloads it whenever
 * the file changes: point it at a training run's people.mlp to watch the current network at work.
 *
 * With --screenshot the viewer runs N steps, saves an image of its window and exits.
 */
fun main(args: Array<String>) {
    var cfg = WorldConfig()
    var screenshot: File? = null
    var shotSteps = 0
    var startLayer = Layer.TERRAIN
    var people: File? = null
    var fauna: File? = null
    var k = 0
    while (k < args.size) {
        when (args[k]) {
            "--seed" -> cfg = cfg.copy(seed = args[++k].toLong())
            "--size" -> { val s = args[++k].toInt(); cfg = cfg.copy(widthKm = s, heightKm = s) }
            "--height" -> cfg = cfg.copy(heightKm = args[++k].toInt())
            "--width" -> cfg = cfg.copy(widthKm = args[++k].toInt())
            "--people" -> people = File(args[++k])
            "--fauna" -> fauna = File(args[++k])
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
            // Animals with a learned network (from fauna-evolve) behave by it.
            fauna?.let { inflation.learning.useFauna(sim.ecology, it) }
            val viewer = Viewer(sim, startLayer, people)
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

/** Dot colours for herds on the map. */
/** Floats per animal group in the viewer's snapshot: x, y, size, kind, and the previous x, y. */
private const val STRIDE = 6

/** Animation: time per frame, ms, and the fewest frames a move between steps is drawn in. */
private const val FRAME_MS = 33
private const val MIN_FRAMES = 8

private val SPECIES_COLOUR = mapOf(
    Catalogue.GAZELLE to 0xf0e0b0, Catalogue.ROE_DEER to 0xc08040, Catalogue.FALLOW_DEER to 0xe0a060,
    Catalogue.RED_DEER to 0xa04020, Catalogue.IBEX to 0xb0b0c0, Catalogue.BOAR to 0x404040,
    Catalogue.AUROCHS to 0x2040a0, Catalogue.ONAGER to 0xe0e0e0,
    Catalogue.ELK to 0x503020, Catalogue.REINDEER to 0xd0d0f0, Catalogue.HORSE to 0x806040,
    Catalogue.MOUFLON to 0xa06030, Catalogue.ORYX to 0xfff8e0, Catalogue.OSTRICH to 0x202020,
    Catalogue.HARTEBEEST to 0xc06030, Catalogue.DROMEDARY to 0xd0b080,
)

/** Dot colours for sea animals. */
private val SEA_COLOUR = mapOf(
    SeaCatalogue.FIN_WHALE to 0x203050, SeaCatalogue.DOLPHIN to 0xd0e0f0, SeaCatalogue.PORPOISE to 0x8090a0,
    SeaCatalogue.SEAL to 0x806040, SeaCatalogue.SHARK to 0xd01010,
)

/** Names of land and sea species, in the order the viewer indexes them. */
private val ALL_NAMES = Catalogue.all.map { it.name } + SeaCatalogue.all.map { it.name }

class Viewer(private val sim: Simulation, startLayer: Layer = Layer.TERRAIN, private val peopleNet: File? = null) : JPanel(BorderLayout()) {
    private val world = sim.world
    private val grid = world.grid
    private val painter = LayerPainter(sim)
    private val pixels = IntArray(grid.size)
    private val image = BufferedImage(grid.width, grid.height, BufferedImage.TYPE_INT_RGB)
    @Volatile private var layer = startLayer
    @Volatile private var running = false
    @Volatile private var showAnimals = true
    /** Which kinds are drawn, by the viewer's index (land species, then sea species). */
    private val shown = BooleanArray(ALL_NAMES.size) { true }
    @Volatile private var stepsPerSecond = 5
    @Volatile private var dirty = true
    private val commands = ConcurrentLinkedQueue<() -> Unit>()
    private val map = MapView()
    private val dateLabel = JLabel()
    private val climateLabel = JLabel()
    private val peopleLabel = JLabel()
    private val info = JTextArea(14, 34)
    private val log = JTextArea(12, 34)
    private var tool = Tool.INSPECT
    private var canalStart = -1
    private var hover = -1
    /** The people network in use: when its file last changed, and what to show about it. */
    private var netStamp = 0L
    @Volatile private var netLabel = "bands: hand-written behaviour"

    /** Loads [peopleNet] into the bands' behaviour if it has changed since last time (on the simulation thread). */
    private fun reloadPeople() {
        val f = peopleNet ?: return
        if (!f.exists() || f.lastModified() == netStamp) return
        val net = runCatching { Mlp.load(f.readText()) }.getOrNull() ?: return // half-written: try again next time
        netStamp = f.lastModified()
        sim.ecology.policies[Catalogue.HUMAN] = BandPolicy(net, sim.ecology::noise) { sim.weather.date }
        val gen = f.parentFile?.let { File(it, "log.txt") }?.takeIf { it.exists() }?.readLines()?.count { it.startsWith("gen ") }
        netLabel = "bands: network ${f.name}" + (gen?.let { " after $it generations" } ?: "") + ", loaded " + java.time.LocalTime.now().withNano(0)
    }

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
        // Animals: a menu of groups (people, plant-eaters, hunters, sea life) and of each kind.
        val animalsBox = JButton("Animals ▾")
        val menu = javax.swing.JPopupMenu()
        val items = HashMap<Int, javax.swing.JCheckBoxMenuItem>()
        fun group(label: String, members: List<Int>) {
            val item = javax.swing.JCheckBoxMenuItem(label, true)
            item.addActionListener { for (i in members) { shown[i] = item.isSelected; items[i]?.isSelected = item.isSelected }; map.repaint() }
            menu.add(item)
        }
        val all = javax.swing.JCheckBoxMenuItem("Show animals", true)
        all.addActionListener { showAnimals = all.isSelected; map.repaint() }
        menu.add(all)
        menu.addSeparator()
        val land = Catalogue.all.indices
        group("People", land.filter { Catalogue.all[it].human })
        group("Plant-eaters", land.filter { !Catalogue.all[it].carnivore })
        group("Hunters", land.filter { Catalogue.all[it].carnivore && !Catalogue.all[it].human })
        group("Sea life", SeaCatalogue.all.indices.map { Catalogue.all.size + it })
        menu.addSeparator()
        for (i in ALL_NAMES.indices) {
            val item = javax.swing.JCheckBoxMenuItem(ALL_NAMES[i], true)
            item.addActionListener { shown[i] = item.isSelected; map.repaint() }
            items[i] = item
            menu.add(item)
        }
        animalsBox.addActionListener { menu.show(animalsBox, 0, animalsBox.height) }
        val tools = JComboBox(Tool.entries.map { it.label }.toTypedArray())
        tools.addActionListener { tool = Tool.entries[tools.selectedIndex]; canalStart = -1 }

        val bar = JPanel()
        bar.add(play); bar.add(step); bar.add(year)
        bar.add(JLabel("Speed (steps/s)")); bar.add(speed)
        bar.add(JLabel("Layer")); bar.add(layers); bar.add(animalsBox)
        bar.add(JLabel("Tool")); bar.add(tools)

        dateLabel.font = dateLabel.font.deriveFont(Font.BOLD, 15f)
        val top = JPanel()
        top.layout = BoxLayout(top, BoxLayout.Y_AXIS)
        top.add(bar)
        val dateRow = JPanel(); dateRow.add(dateLabel); dateRow.add(climateLabel)
        top.add(dateRow)
        val peopleRow = JPanel(); peopleRow.add(peopleLabel)
        top.add(peopleRow)

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
        // Animate: redraw the animals some 30 times a second while they are moving between steps.
        javax.swing.Timer(FRAME_MS) { if (showAnimals && animFrame < animFrames) { animFrame++; map.repaint() } }.start()
    }

    private fun titled(c: javax.swing.JComponent, title: String) = c.also { it.border = BorderFactory.createTitledBorder(title) }

    /**
     * Animal groups at the last repaint: x, y, size, kind index, and where the group was the step
     * before (x, y), per group. Between steps they are drawn part way, so movement is animated.
     */
    private var animals = FloatArray(0)
    /** Frames of the move between the last step and the next: drawn so far, and in all. */
    @Volatile private var animFrame = 0
    @Volatile private var animFrames = 0
    /** Where each group was drawn after the last step (simulation thread only). */
    private val lastPos = java.util.IdentityHashMap<Any, FloatArray>()

    /** Simulation thread: runs commands and steps, repaints the image after each change. */
    private fun loop() {
        var last = System.nanoTime()
        var checked = 0L
        while (true) {
            var changed = false
            if (peopleNet != null && System.nanoTime() - checked > 30_000_000_000L) { checked = System.nanoTime(); reloadPeople() }
            val day = sim.date.absoluteDay
            while (true) { val c = commands.poll() ?: break; c(); changed = true }
            var stepped = false
            // The next step waits until the groups have finished moving on screen.
            if (running && System.nanoTime() - last >= 1_000_000_000L / stepsPerSecond && (!showAnimals || animFrame >= animFrames)) {
                last = System.nanoTime()
                sim.step(); changed = true
            }
            // Stepped by the clock or by the Step and Year buttons: the groups have moved.
            if (sim.date.absoluteDay != day) stepped = true
            if (changed || dirty) {
                dirty = false
                painter.paint(layer, pixels)
                val herds = sim.ecology.groups.filter { it.alive }
                val pods = sim.ecology.marine.groups.filter { it.alive }
                val snapshot = FloatArray((herds.size + pods.size) * STRIDE)
                val seen = java.util.IdentityHashMap<Any, FloatArray>()
                fun put(k: Int, g: Any, x: Float, y: Float, size: Float, kind: Float) {
                    val o = k * STRIDE
                    snapshot[o] = x; snapshot[o + 1] = y; snapshot[o + 2] = size; snapshot[o + 3] = kind
                    // Whence it moves: where it stood after the step before (a new group starts where it is).
                    val from = (if (stepped) lastPos[g] else null) ?: floatArrayOf(x, y)
                    snapshot[o + 4] = from[0]; snapshot[o + 5] = from[1]
                    seen[g] = floatArrayOf(x, y)
                }
                for ((k, g) in herds.withIndex()) put(k, g, g.x.toFloat(), g.y.toFloat(), g.size.toFloat(), Catalogue.all.indexOf(g.species).toFloat())
                for ((j, g) in pods.withIndex()) put(herds.size + j, g, g.x.toFloat(), g.y.toFloat(), g.size.toFloat(), (Catalogue.all.size + g.species.index).toFloat())
                if (stepped) { lastPos.clear(); lastPos.putAll(seen) }
                synchronized(image) {
                    image.setRGB(0, 0, grid.width, grid.height, pixels, 0, grid.width); animals = snapshot
                    if (stepped) { animFrame = 0; animFrames = max(MIN_FRAMES, (1000 / FRAME_MS / stepsPerSecond)) }
                }
                SwingUtilities.invokeLater { map.repaint(); updateLabels() }
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
        val bands = sim.ecology.groups.filter { it.species.human && it.alive }
        peopleLabel.text = "   $netLabel · ${bands.size} bands, ${bands.sumOf { it.size }} people"
        if (log.lineCount - 1 != sim.log.size) log.text = sim.log.takeLast(200).joinToString("\n")
        if (hover >= 0) info.text = describe(hover)
    }

    private fun describe(i: Int): String = buildString {
        val x = grid.x(i); val y = grid.y(i)
        appendLine("Cell ($x, $y)  %.2f°N".format(world.latitude(i)))
        appendLine("Elevation %.0f m".format(world.elevation[i]))
        val f = sim.ecology.fish
        if (f.fish[i] > 0f) appendLine("Fish %.0f kg/km²".format(f.fish[i]))
        if (f.shellfish[i] > 0f) appendLine("Shellfish %.0f kg/km²".format(f.shellfish[i]))
        if (f.run[i] > 0f) appendLine("SPAWNING RUN %.0f kg/km²".format(f.run[i]))
        if (world.isSea[i]) { appendLine("Sea"); nearby(x, y); return@buildString }
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
        val t = world.terrain
        if (v.isVegetated(i)) {
            appendLine("Ruggedness %.2f (slope %.0f%%, relief %.0f m, rock roughness %.2f)".format(t.ruggedness[i], t.slope[i] * 100, t.relief[i], t.roughness[i]))
            appendLine("Travel effort %.1f× flat ground · defensibility %.2f".format(t.travelCost(i, v, sim.weather.snowpack[i], dischargeNow = sim.water.discharge[i]), t.defensibility[i]))
            appendLine("Sight on the ground %.2f km · view over terrain %.1f km, %.0f km²".format(t.sightKm(i, v), t.viewKm[i], t.viewAreaKm2[i]))
        }
        val e = sim.ecology
        if (v.isVegetated(i)) {
            appendLine("Graze %.0f, browse %.0f kg/km² · insects %.0f · rodents %.0f kg/km²".format(e.graze[i], e.browse[i], e.small.insects[i], e.small.rodents[i]))
            appendLine("Litter %.2f kg/m², organic matter %.1f kg/m²".format(e.soil.litter[i], e.soil.organicMatter[i]))
            if (e.small.biting[i] > 50f) appendLine("Mosquitoes and midges")
            if (e.soil.carrion[i] > 0f) appendLine("Carcasses %.0f kg".format(e.soil.carrion[i] * grid.cellAreaM2))
            appendLine("Water %.0f km away".format(e.waterKm[i]))
        }
        nearby(x, y)
        world.volcanoes.firstOrNull { abs(it.x - x) <= 3 && abs(it.y - y) <= 3 }?.let {
            appendLine("Volcano: ${it.kind.name.lowercase().replace('_', ' ')}, ${if (it.active) "active" else "extinct"}, %.1f Myr old".format(it.ageMyr))
        }
    }

    /** The animal groups within 5 km of cell (x, y), by kind. */
    private fun StringBuilder.nearby(x: Int, y: Int) {
        val a = synchronized(image) { animals }
        val near = IntArray(ALL_NAMES.size); val nearGroups = IntArray(ALL_NAMES.size)
        for (k in 0 until a.size / STRIDE) {
            val o = k * STRIDE
            if (abs(a[o] - x - 0.5f) > 5 || abs(a[o + 1] - y - 0.5f) > 5) continue
            val sp = a[o + 3].toInt()
            near[sp] += a[o + 2].toInt(); nearGroups[sp]++
        }
        if (nearGroups.any { it > 0 }) appendLine("Within 5 km: " + ALL_NAMES.indices.filter { nearGroups[it] > 0 }
            .joinToString(", ") { "${near[it]} ${ALL_NAMES[it]} (${nearGroups[it]} groups)" })
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
            if (showAnimals) {
                val a = synchronized(image) { animals }
                // How far through the move between the last step and the next: 0 = where it was, 1 = where it is.
                val t = if (animFrames == 0) 1f else (animFrame.toFloat() / animFrames).coerceIn(0f, 1f)
                for (k in 0 until a.size / STRIDE) {
                    val o = k * STRIDE
                    val ax = a[o + 4] + (a[o] - a[o + 4]) * t; val ay = a[o + 5] + (a[o + 1] - a[o + 5]) * t
                    val x = (ox + ax * scale).toInt(); val y = (oy + ay * scale).toInt()
                    val size = a[o + 2]
                    val idx = a[o + 3].toInt()
                    if (!shown[idx]) continue
                    if (idx >= Catalogue.all.size) {
                        // Sea animals: diamonds, whales larger.
                        val s = SeaCatalogue.all[idx - Catalogue.all.size]
                        val r = if (s == SeaCatalogue.FIN_WHALE) 4 else 2
                        val xs = intArrayOf(x, x + r, x, x - r); val ys = intArrayOf(y - r, y, y + r, y)
                        g2.color = Color(0x101010); g2.drawPolygon(xs, ys, 4)
                        g2.color = Color(SEA_COLOUR[s] ?: 0xffffff); g2.fillPolygon(xs, ys, 4)
                        continue
                    }
                    val species = Catalogue.all[idx]
                    if (species.human) {
                        // A band: a hut, bigger for bigger bands.
                        val r = if (size > 35) 6 else 4
                        val xs = intArrayOf(x - r, x + r, x + r, x, x - r); val ys = intArrayOf(y + r, y + r, y, y - r, y)
                        g2.color = Color(0x101010); g2.drawPolygon(xs, ys, 5)
                        g2.color = Color(0xffb020); g2.fillPolygon(xs, ys, 5)
                        continue
                    }
                    if (species.carnivore) {
                        g2.color = Color.WHITE; g2.fillRect(x - 3, y - 3, 7, 7)
                        g2.color = Color(0xd01010); g2.fillRect(x - 2, y - 2, 5, 5)
                    } else {
                        val r = if (size > 25) 2 else 1
                        g2.color = Color(0x101010); g2.fillOval(x - r - 1, y - r - 1, 2 * r + 3, 2 * r + 3)
                        g2.color = Color(SPECIES_COLOUR[species] ?: 0xffffff); g2.fillOval(x - r, y - r, 2 * r + 1, 2 * r + 1)
                    }
                }
            }
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
                Layer.RUGGEDNESS -> "Ruggedness from slope, relief and rock: pale smooth to dark broken (lava, karst, gorges)"
                Layer.TRAVEL_COST -> "Walking effort vs flat open ground, 1× (pale) to 20× (dark): slope, rock, wetland, rivers, thickets, snow"
                Layer.VISIBILITY -> "How far one sees on the ground through broken ground and plants: 50 m (dark) to 10 km (white)"
                Layer.VIEWSHED -> "Area in view over the bare terrain within 30 km: valley floors dark, summits and open plains light"
                Layer.DEFENSIBILITY -> "Defensibility: height over surroundings, broken ground, approaches cut by water or cliffs"
                Layer.UPWELLING -> "Upwelling feeding the plankton this month, none to full (dark to bright) · diamonds: fin whales (large, dark), dolphins (pale), porpoises (grey), monk seals (brown), sharks (red)"
                Layer.GRAZING -> "Graze plus browse, 0 to 250 kg/km² (dark to yellow) · dots: herds (gazelle cream, onager white, ibex grey, roe/fallow/red deer browns, boar black, aurochs blue), red squares: wolves"
                Layer.FISH -> "Fish (blue to green, up to 6 t/km²) · shellfish beds (pink) · spring spawning run in rivers (orange to yellow)"
                Layer.PLANKTON -> "Phytoplankton: dark blue sparse to green bloom"
                Layer.INSECTS -> "Plant-eating insects, 0 to 3000 kg/km² (log scale)"
                Layer.RODENTS -> "Rodents, 0 to 500 kg/km²"
                Layer.SOIL_LIFE -> "Litter plus soil organic matter, 0 to 10 kg/m²"
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
