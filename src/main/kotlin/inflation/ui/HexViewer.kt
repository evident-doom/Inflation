package inflation.ui

import inflation.Args
import inflation.Config

import inflation.ecology.HexRoutes
import inflation.ecology.Catalogue
import inflation.ecology.HexSim
import inflation.ecology.PlaceRecord
import inflation.ui.ViewLayers.Measure
import inflation.world.Date
import inflation.world.HexMap
import inflation.world.HexWorld
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
import inflation.ecology.HexHistory
import inflation.ecology.HexSnapshot
import inflation.ecology.HexWater
import inflation.ecology.Material
import inflation.ecology.Skill
import inflation.ecology.Species
import inflation.ecology.Tool
import inflation.ecology.Tradition
import inflation.learning.MoverPolicy
import inflation.learning.NetAnimalWays
import inflation.learning.NetWays
import inflation.learning.Role
import inflation.learning.Schema
import inflation.learning.SharedMover
import inflation.world.Biome
import inflation.world.WorldCache
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Watches the hex world: the fine terrain with its hex borders (see [HexPicture]) as the ground,
 * the herds and hunters as dots walking hex to hex along the ways they took each week, and one
 * resident kind's density as a tint over the hexes.
 *
 * Usage: hex-viewer [--seed N] [--width KM] [--height KM] [--load FILE]
 * (--load carries on a saved world: the seed and size must be the ones it was saved with.) The
 * config keys choose the learned networks, as for hex-fauna: nets=DIR moves every kind by the
 * networks in DIR (herds.mlp, hunters.mlp, people.mlp, as hex-ppo writes them, and herd-ways.mlp,
 * hunter-ways.mlp where present), ways=FILE sets the bands' ways by a learned network, and herdWays
 * and hunterWays the herds' and packs'. Without them the hand-written choices are the model.
 */
fun main(args: Array<String>) {
    val a = Args(args, "hex-viewer [--seed N] [--width KM] [--height KM] [--load FILE]")
    val base = WorldConfig(seed = 1)
    val cfg = base.copy(seed = a.long("--seed", base.seed), widthKm = a.int("--width", base.widthKm), heightKm = a.int("--height", base.heightKm))
    val load = a.file("--load")
    a.done()
    val nets = Config.string("nets"); val ways = Config.string("ways")
    val frame = JFrame("Hex world, seed ${cfg.seed}")
    val status = JLabel("Generating the world…")
    frame.contentPane.add(status)
    frame.setSize(1400, 1000)
    frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
    frame.isVisible = true
    Thread {
        val world = WorldCache.load(cfg) { step -> SwingUtilities.invokeLater { status.text = "Generating: $step…" } }
        SwingUtilities.invokeLater { status.text = "Summarising into hexes and drawing the ground…" }
        val view = HexViewer(world, load, nets, ways)
        SwingUtilities.invokeLater {
            frame.contentPane.removeAll(); frame.contentPane.add(view); frame.revalidate()
        }
    }.start()
}

class HexViewer(world: World, load: java.io.File? = null, private val nets: String? = null, private val waysFile: String? = null) : JPanel(BorderLayout()) {
    private val sim = HexSim(world)
    private val cfg = sim.cfg
    private val hex = sim.hex
    private val hw = sim.hw
    private val weather = sim.weather
    private val land = sim.land
    private val fauna = sim.fauna
    private val groups = sim.groups
    private val px = 2
    private val ground: BufferedImage = HexPicture.render(world, hex, 0.0, 0.0, cfg.widthKm.toDouble(), cfg.heightKm.toDouble(), px, rivers = false)
    /** The rivers as they run this week, drawn over the ground (see [RiverOverlay]). */
    private val rivers = RiverOverlay(world, hex, land)
    private val commands = ConcurrentLinkedQueue<() -> Unit>()
    /** Held by the simulation's thread while it changes the world; the windows read it only when it stands still. */
    private val worldLock = WorldLock()
    @Volatile private var running = false
    @Volatile private var stepsPerSecond = 2

    /** What is drawn: per group its id, kind, the way it walked (km, x0 y0 x1 y1 ...; from where it was drawn last), its size. */
    private class Shown(val id: Int, val kind: Int, val carnivore: Boolean, val way: FloatArray, val size: Int, val health: Float)

    /** What the hexes are tinted by, worked out each week and kept month by month (see [ViewLayers]). */
    private val layers = ViewLayers(sim)
    private val tintChoice = JComboBox(Measure.entries.map { it.label }.toTypedArray())
    /** How the tint is shown: this week (as drawn so far), scaled to the map, or over the last year. */
    private val modeChoice = JComboBox(ViewLayers.Mode.entries.map { it.label }.toTypedArray())
    /** For the deaths: which cause, or all. */
    private val causeChoice = JComboBox((PlaceRecord.Cause.entries.map { "died of " + it.label } + "all causes").toTypedArray()).apply { selectedIndex = PlaceRecord.Cause.entries.size; isEnabled = false }
    /** What the tint's colours mean. */
    private val legendText = JLabel("")
    private val windBox = javax.swing.JCheckBox("Wind", false)
    /** Rivers by this week's flow (see [RiverOverlay]); unticked, no rivers are drawn. */
    private val riversBox = javax.swing.JCheckBox("River flows", true)
    private val herdsBox = javax.swing.JCheckBox("Herds", true)
    private val huntersBox = javax.swing.JCheckBox("Hunters", true)
    private val peopleBox = javax.swing.JCheckBox("People", true)
    /** Paths worn by herds, packs and bands, drawn hex to hex, darker and wider the more worn. */
    private val pathsBox = javax.swing.JCheckBox("Paths", true)
    /** Follow mode: the group followed (-1 none), kept in view week by week, with what happened to it. */
    @Volatile private var followLog = ""

    /** The followed group's controls (see [GroupControlWindow]): part of the run only while open. */
    private val control by lazy { GroupControlWindow(sim, ground, px, rivers, worldLock, requestStep = { commands += { advance() } }, running = { running }) }

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
    @Volatile private var frames = 1
    private val date = JLabel()
    /**
     * The kinds and their numbers along the bottom, as buttons: pressing one shows where that kind is
     * (its density over the hexes, and only its herds or packs drawn); pressing it again shows all.
     */
    private val kindButtons = ArrayList<javax.swing.JToggleButton>()
    /** Which kind's numbers to tint the hexes by: any resident kind, or any kind in herds and packs. */
    @Volatile private var onlyKind: Species? = null
    /** Life in the water to show as a tint: fish of the sea and lakes, river fish, shellfish, and each kind of sea animal (densities per hex). */
    private val waterLayers by lazy { listOf<Pair<String, FloatArray>>("sea and lake fish" to fauna.water.fish, "river fish" to fauna.water.riverFish, "shellfish" to fauna.water.shellfish) +
        HexWater.SEA.mapIndexed { i, k -> k.name to fauna.water.sea[i] } }
    private val residentChoice by lazy { JComboBox(arrayOf("(no kind shown)") + (fauna.kinds + groups.kinds).map { "where: " + it.name }.toTypedArray() + waterLayers.map { "water: " + it.first }.toTypedArray()) }
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
        // (The time controls here take over from the follow window: a week waiting there for the player goes on with the picks shown.)
        val run = JButton("Run"); run.addActionListener { running = !running; run.text = if (running) "Pause" else "Run"; control.overviewTakesOver() }
        val step = JButton("Step"); step.addActionListener { control.overviewTakesOver(); commands += { advance() } }
        val speed = JSlider(1, 10, stepsPerSecond); speed.addChangeListener { stepsPerSecond = speed.value }
        residentChoice.addActionListener { map.repaint() }
        tintChoice.addActionListener { causeChoice.isEnabled = Measure.entries[tintChoice.selectedIndex].byCause; map.repaint(); if (picked >= 0) inspect(picked) }
        modeChoice.addActionListener { map.repaint() }; causeChoice.addActionListener { map.repaint(); if (picked >= 0) inspect(picked) }
        windBox.addActionListener { map.repaint() }
        riversBox.addActionListener { rivers.visible = riversBox.isSelected; map.repaint() }
        herdsBox.addActionListener { map.repaint() }; huntersBox.addActionListener { map.repaint() }; peopleBox.addActionListener { map.repaint() }; pathsBox.addActionListener { map.repaint() }
        pullChoice.addActionListener { map.repaint() }
        bar.add(run); bar.add(step); bar.add(JLabel("weeks/s")); bar.add(speed); bar.add(tintChoice); bar.add(modeChoice); bar.add(causeChoice); bar.add(residentChoice); bar.add(herdsBox); bar.add(huntersBox); bar.add(peopleBox); bar.add(pathsBox); bar.add(windBox); bar.add(riversBox); bar.add(pullChoice)
        val graphs = JButton("History…"); graphs.addActionListener { HexHistoryPanel.show(history, live = true, title = "History, seed ${cfg.seed}") }
        // Save the world as it stands, or carry on one saved before.
        val save = JButton("Save…"); save.addActionListener { chooseFile(true)?.let { f -> commands += { HexSnapshot.save(sim, f); SwingUtilities.invokeLater { date.text = "   saved to ${f.name}" } } } }
        val open = JButton("Load…"); open.addActionListener { chooseFile(false)?.let { f -> commands += { loadWorld(f) } } }
        // Following a group is the follow window: open it on the group picked in the list.
        val follow = JButton("Follow…")
        follow.toolTipText = "Follow the group picked in the list in its own window: its local map and state, what it knows, its choices each week (the model's highlighted), yours, and Next step"
        follow.addActionListener {
            val i = groupView.selectedIndex
            if (i in groupIds.indices) control.follow(groupIds[i]) else javax.swing.JOptionPane.showMessageDialog(this, "Click a hex on the map and pick a group in the list first.")
        }
        bar.add(follow)
        bar.add(graphs); bar.add(save); bar.add(open); bar.add(date)
        useNetworks()
        load?.let { f -> commands += { loadWorld(f) } }
        val bottom = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 2))
        val all = fauna.kinds + groups.kinds
        for ((i, s) in all.withIndex()) {
            val b = javax.swing.JToggleButton(s.name); b.margin = java.awt.Insets(1, 4, 1, 4)
            b.toolTipText = if (i < fauna.kinds.size) "${s.name}: lives spread over the land (shown as its density)" else "${s.name}: lives in herds or packs (shown as its density, and only its groups drawn)"
            b.addActionListener {
                for (o in kindButtons) if (o !== b) o.isSelected = false
                onlyKind = if (b.isSelected && i >= fauna.kinds.size) s else null
                residentChoice.selectedIndex = if (b.isSelected) i + 1 else 0
                map.repaint()
            }
            kindButtons += b; bottom.add(b)
        }
        val side = JPanel(BorderLayout())
        side.add(JScrollPane(info), BorderLayout.NORTH)
        side.add(JScrollPane(groupView), BorderLayout.CENTER)
        side.add(JScrollPane(groupInfo), BorderLayout.SOUTH)
        groupView.addListSelectionListener { e -> if (!e.valueIsAdjusting) showGroup() }
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, map, side)
        split.resizeWeight = 0.75
        val south = JPanel(BorderLayout()); south.add(legendText.apply { border = javax.swing.BorderFactory.createEmptyBorder(2, 6, 2, 6) }, BorderLayout.NORTH); south.add(bottom, BorderLayout.CENTER)
        add(bar, BorderLayout.NORTH); add(split, BorderLayout.CENTER); add(south, BorderLayout.SOUTH)
        snapshot()
        Thread(::loop, "hex simulation").apply { isDaemon = true }.start()
        Timer(33) { if (frame < frames) { frame++; map.repaint() } }.start()
    }

    private fun chooseFile(save: Boolean): java.io.File? {
        val fc = javax.swing.JFileChooser(java.io.File(".")).apply { fileFilter = javax.swing.filechooser.FileNameExtensionFilter("saved hex worlds", "hexw") }
        val ok = if (save) fc.showSaveDialog(this) else fc.showOpenDialog(this)
        if (ok != javax.swing.JFileChooser.APPROVE_OPTION) return null
        val f = fc.selectedFile
        return if (save && !f.name.endsWith(".hexw")) java.io.File(f.path + ".hexw") else f
    }

    /** Carries on the world saved in [f] (on the simulation's thread). */
    private fun loadWorld(f: java.io.File) {
        try {
            HexSnapshot.load(sim, f)
            layers.reset()
            useNetworks()
            lastDrawn.clear()
            snapshot()
        } catch (e: Exception) {
            SwingUtilities.invokeLater { javax.swing.JOptionPane.showMessageDialog(this, "Could not load ${f.name}: ${e.message}") }
        }
    }

    /** Week-by-week history of everything worth watching, for the graphs. */
    private val history = HexHistory()

    private fun advance() {
        sim.step()
        history.record(sim)
        layers.week()
        snapshot()
        control.weekDone()
    }

    /** The model: learned networks where given ([nets], [waysFile]), else the hand-written choices. */
    private fun useNetworks() {
        nets?.let { name ->
            val dir = inflation.tools.baselineDir(name)
            for (role in Role.entries) {
                val f = role.file(dir).takeIf { it.exists() } ?: continue
                val p = MoverPolicy(SharedMover.load(f), null, { 0f }, { 0 })
                for (s in groups.kinds) if (Role.of(s) == role) groups.policies[s] = p
            }
            java.io.File(dir, "herd-ways.mlp").takeIf { it.exists() }?.let { groups.herdWays = NetAnimalWays(Schema.load(it, Schema.ANIMAL_WAYS_IN, Schema.HERD_WAYS_OUT)) }
            java.io.File(dir, "hunter-ways.mlp").takeIf { it.exists() }?.let { groups.hunterWays = NetAnimalWays(Schema.load(it, Schema.ANIMAL_WAYS_IN, Schema.HUNTER_WAYS_OUT)) }
        }
        (waysFile ?: nets?.let { java.io.File(inflation.tools.baselineDir(it), "ways.mlp").takeIf { f -> f.exists() }?.path })?.let {
            groups.ways = NetWays(Schema.load(java.io.File(it), Schema.WAYS_IN, Schema.WAYS_OUT))
        }
        Config.string("herdWays")?.let { groups.herdWays = NetAnimalWays(Schema.load(java.io.File(it), Schema.ANIMAL_WAYS_IN, Schema.HERD_WAYS_OUT)) }
        Config.string("hunterWays")?.let { groups.hunterWays = NetAnimalWays(Schema.load(java.io.File(it), Schema.ANIMAL_WAYS_IN, Schema.HUNTER_WAYS_OUT)) }
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
            val end = (hx + r * cos(a)).toFloat() to (hy + r * sin(a)).toFloat()
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
        // Following a group (in the follow window): the week's log, for the panel when that group is picked.
        followLog = if (groups.watched >= 0) groups.watchLog.joinToString("\n") else ""
        SwingUtilities.invokeLater { if (picked >= 0) inspect(picked) }
        frames = max(8, 30 / stepsPerSecond); frame = 0
        // (The texts are made here, on the simulation's thread, from the world as the week left it.)
        val dateText = "   year ${weather.date.year}, month ${weather.date.month + 1}, day ${weather.date.dayOfMonth}"
        val all = fauna.kinds + groups.kinds
        val kindTexts = all.mapIndexed { i, s -> "${s.name} " + (if (i < fauna.kinds.size) "%.0f".format(fauna.count(i)) else "${groups.count(s)} in ${groups.groups.count { it.alive && it.species == s }}") }
        SwingUtilities.invokeLater {
            date.text = dateText
            for ((i, b) in kindButtons.withIndex()) b.text = kindTexts[i]
        }
    }

    private fun loop() {
        var last = 0L
        while (true) {
            while (true) { val c = commands.poll() ?: break; worldLock.changing(c) }
            if (running && frame >= frames && System.nanoTime() - last > 1_000_000_000L / stepsPerSecond) { last = System.nanoTime(); worldLock.changing { advance() } }
            else Thread.sleep(5)
        }
    }

    /** A colour from components that may stray outside 0..1 (a measure beyond its scale): clamped, as Color would otherwise throw and leave the map unpainted. */
    private fun rgba(r: Float, g: Float, b: Float, a: Float) = Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f), a.coerceIn(0f, 1f))

    private inner class MapPanel : JPanel() {
        private var scale = 0.5
        private var ox = 0.0; private var oy = 0.0
        private var drag: java.awt.Point? = null

        /** Puts the point ([x], [y]) km in the middle of the view. */
        fun centreOn(x: Double, y: Double) { val kmPx = px * scale; ox = width / 2.0 - x * kmPx; oy = height / 2.0 - y * kmPx; repaint() }
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

        private val painter = worldLock.Painter(this)

        override fun paintComponent(g0: Graphics) {
            super.paintComponent(g0)
            painter.paint(g0) { paintMap(it) }
        }

        private fun paintMap(g: Graphics2D) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.drawImage(ground, ox.toInt(), oy.toInt(), (ground.width * scale).toInt(), (ground.height * scale).toInt(), null)
            val kmPx = px * scale
            rivers.draw(g, ox, oy, kmPx, width, height)
            fun sx(km: Float) = (ox + km * kmPx).toInt()
            fun sy(km: Float) = (oy + km * kmPx).toInt()
            // The chosen measure as a tint over the hexes (see [ViewLayers]), shown as the mode says.
            val tint = Measure.entries[tintChoice.selectedIndex]
            if (tint != Measure.NONE) {
                val rad = hex.hexKm / sqrt(3.0)
                val mode = ViewLayers.Mode.entries[modeChoice.selectedIndex]
                val key = layers.key(tint, causeChoice.selectedIndex)
                val colours = arrayOfNulls<Color>(hex.size)
                val legend = paintTint(tint, mode, key, colours)
                if (legendText.text != legend) SwingUtilities.invokeLater { legendText.text = legend }
                for (h in 0 until hex.size) {
                    val colour = colours[h] ?: continue
                    val (cx, cy) = hex.centreKm(h)
                    val xs = IntArray(6) { sx((cx + rad * cos(Math.PI / 6 + it * Math.PI / 3)).toFloat()) }
                    val ys = IntArray(6) { sy((cy + rad * sin(Math.PI / 6 + it * Math.PI / 3)).toFloat()) }
                    g.color = colour; g.fillPolygon(xs, ys, 6)
                }
            } else if (legendText.text.isNotEmpty()) SwingUtilities.invokeLater { legendText.text = "" }
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
                    val ang = atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())
                    for (sgn in intArrayOf(-1, 1)) g.drawLine(x1, y1, (x1 - 5 * cos(ang + sgn * 0.5)).toInt(), (y1 - 5 * sin(ang + sgn * 0.5)).toInt())
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
                    val ang = atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())
                    for (sgn in intArrayOf(-1, 1)) g.drawLine(x1, y1, (x1 - 5 * cos(ang + sgn * 0.5)).toInt(), (y1 - 5 * sin(ang + sgn * 0.5)).toInt())
                }
            }
            // A resident kind's density as a tint over the hexes.
            val r = residentChoice.selectedIndex - 1
            if (r >= 0) {
                val rad = hex.hexKm / sqrt(3.0)
                val dens = DoubleArray(hex.size); val area = DoubleArray(hex.size)
                for (p in 0 until fauna.n) { val h = hw.placeHex[p]; area[h] += land.areaKm2[p].toDouble() }
                if (r < fauna.kinds.size) for (p in 0 until fauna.n) dens[hw.placeHex[p]] += ((fauna.adults[r][p] + fauna.young[r][p]) * land.areaKm2[p]).toDouble()
                else if (r < fauna.kinds.size + groups.kinds.size) { val s = groups.kinds[r - fauna.kinds.size]; for (gr in groups.groups) if (gr.alive && gr.species == s) dens[hw.placeHex[gr.place]] += gr.size.toDouble() }
                else {
                    // Life in the water: its density per hex as it is kept (sea hexes too).
                    val stock = waterLayers[r - fauna.kinds.size - groups.kinds.size].second
                    for (h in 0 until hex.size) { area[h] = 1.0; dens[h] = stock[h].toDouble() }
                }
                // Shaded against the densest twentieth of hexes, so that thin spreads show too.
                val values = (0 until hex.size).filter { area[it] > 0 && dens[it] > 0 }.map { dens[it] / area[it] }.sorted()
                val top = (if (values.isEmpty()) 0.0 else values[(values.size * 0.95).toInt().coerceAtMost(values.size - 1)]).coerceAtLeast(1e-6)
                for (h in 0 until hex.size) {
                    if (area[h] <= 0) continue
                    if (dens[h] <= 0) continue
                    val v = sqrt(dens[h] / area[h] / top).toFloat().coerceIn(0.15f, 1f)
                    val (cx, cy) = hex.centreKm(h)
                    val xs = IntArray(6) { sx((cx + rad * cos(Math.PI / 6 + it * Math.PI / 3)).toFloat()) }
                    val ys = IntArray(6) { sy((cy + rad * sin(Math.PI / 6 + it * Math.PI / 3)).toFloat()) }
                    g.color = Color(1f, 0.1f, 0.6f, 0.6f * v); g.fillPolygon(xs, ys, 6)
                }
            }
            // Paths: hex centre to hex centre, each pair once, darker and wider the more worn.
            if (pathsBox.isSelected) {
                val stroke = g.stroke
                for (h in 0 until hex.size) for (d in 0 until 3) {
                    val nh = hex.neighbour(h, d); if (nh < 0) continue
                    val back = (0 until 6).firstOrNull { hex.neighbour(nh, it) == h } ?: continue
                    val s = max(groups.paths[h * 6 + d], groups.paths[nh * 6 + back])
                    if (s < 0.05f) continue
                    val (x0, y0) = hex.centreKm(h); val (x1, y1) = hex.centreKm(nh)
                    g.color = Color(0.45f, 0.3f, 0.12f, (0.25f + 0.65f * s).coerceAtMost(1f))
                    g.stroke = java.awt.BasicStroke((0.5f + 3f * s) * max(0.5f, scale.toFloat()))
                    g.drawLine(sx(x0.toFloat()), sy(y0.toFloat()), sx(x1.toFloat()), sy(y1.toFloat()))
                }
                g.stroke = stroke
                // Landmarks on the routes: junctions, fords, passes, water and ends.
                val r = max(2, (2.5 * max(0.5, scale)).toInt())
                for (h in groups.routes.landmarks) {
                    val (cx, cy) = hex.centreKm(h); val x = sx(cx.toFloat()); val y = sy(cy.toFloat())
                    g.color = when (groups.routes.kind(h)) {
                        HexRoutes.Kind.JUNCTION -> Color(0.95f, 0.85f, 0.4f); HexRoutes.Kind.FORD -> Color(0.3f, 0.6f, 1f)
                        HexRoutes.Kind.PASS -> Color(0.85f, 0.85f, 0.85f); HexRoutes.Kind.WATER -> Color(0.2f, 0.9f, 0.9f)
                        else -> Color(0.5f, 0.35f, 0.2f)
                    }
                    if (groups.routes.kind(h) == HexRoutes.Kind.PASS) g.fillPolygon(intArrayOf(x - r, x, x + r), intArrayOf(y + r, y - r, y + r), 3)
                    else g.fillOval(x - r, y - r, 2 * r, 2 * r)
                }
            }
            // The hex being inspected.
            if (picked >= 0) {
                val (cx, cy) = hex.centreKm(picked)
                val rad = hex.hexKm / sqrt(3.0)
                val xs = IntArray(6) { sx((cx + rad * cos(Math.PI / 6 + it * Math.PI / 3)).toFloat()) }
                val ys = IntArray(6) { sy((cy + rad * sin(Math.PI / 6 + it * Math.PI / 3)).toFloat()) }
                g.stroke = java.awt.BasicStroke(2.5f); g.color = Color.WHITE; g.drawPolygon(xs, ys, 6); g.stroke = java.awt.BasicStroke(1f)
            }
            // The groups, each part of the way along the hexes it walked this week; at the end of the
            // week those sharing a hex stand side by side in a small ring around its centre.
            val t = min(1f, frame.toFloat() / frames)
            for (s in shown) {
                val sp = Catalogue.all[s.kind]
                // The group followed in the follow window: a yellow ring.
                if (s.id == groups.watched) { val (fx, fy) = along(s.way, t); g.color = Color(1f, 0.85f, 0.1f); g.stroke = java.awt.BasicStroke(2.5f); g.drawOval(sx(fx) - 9, sy(fy) - 9, 18, 18); g.stroke = java.awt.BasicStroke(1f) }
                if (if (sp.human) !peopleBox.isSelected else if (s.carnivore) !huntersBox.isSelected else !herdsBox.isSelected) continue
                if (onlyKind != null && sp != onlyKind) continue
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

    /**
     * Colours each hex by [tint] as [mode] says (into [out], null for none) and returns what the
     * colours mean. "This week" keeps the measures' own fixed scales; "scaled" stretches the map's
     * 5th to 95th percentile of the hexes with any over the full range; the seasonal modes read the
     * months kept by [layers].
     */
    private fun paintTint(tint: Measure, mode: ViewLayers.Mode, key: String, out: Array<Color?>): String {
        val v = layers.now[key] ?: return "${tint.label}: waiting for the first week"
        val unit = tint.unit
        fun pct(vals: FloatArray, keep: (Float) -> Boolean): Pair<Float, Float> {
            val l = vals.filter { !it.isNaN() && keep(it) }.sorted()
            if (l.isEmpty()) return 0f to 1f
            return l[(l.size * 0.05).toInt()] to l[(l.size * 0.95).toInt().coerceAtMost(l.size - 1)]
        }
        fun f(x: Float) = when { abs(x) >= 100 -> "%.0f".format(x); abs(x) >= 1 -> "%.1f".format(x); else -> "%.3g".format(x) }
        fun shade(h: Int, a: Float) { out[h] = rgba(tint.r, tint.g, tint.b, a) }
        val kept = layers.monthsKept(key)
        when (mode) {
            ViewLayers.Mode.WEEK -> {
                if (tint.weeks) {
                    for (h in v.indices) if (!v[h].isNaN() && v[h] > 0f) shade(h, 0.05f + 0.75f * min(1f, ln(1f + v[h]) / ln(1f + 104f)))
                    return "${tint.label}: what lies there over what went in a week. Pale: gone within days; full: two years or more (accumulating)."
                }
                if (tint == Measure.HUNTERS_PER_PREY) {
                    for (h in v.indices) if (!v[h].isNaN() && v[h] > 0f) { val l = (log10(v[h]) / 1.5f).coerceIn(-1f, 1f); out[h] = if (l >= 0) rgba(0.9f, 0.15f, 0.1f, 0.15f + 0.6f * l) else rgba(0.1f, 0.4f, 0.9f, 0.15f - 0.6f * l) }
                    return "Hunters per prey, kg per 100 kg over a hex and its ring (people not counted). Clear at 1, about what prey carries of one carnivore kind in nature (Carbone & Gittleman 2002); red above (full at 30), blue below (full at 0.03)."
                }
                fixedColour(tint, v, out)?.let { return it }
                return paintTint(tint, ViewLayers.Mode.SCALED, key, out)
            }
            ViewLayers.Mode.SCALED -> {
                val (lo, hi) = pct(v) { tint.signed || it > 0f }
                val span = (hi - lo).coerceAtLeast(1e-9f)
                for (h in v.indices) {
                    if (v[h].isNaN() || (!tint.signed && v[h] <= 0f)) continue
                    val x = ((v[h] - lo) / span).coerceIn(0f, 1f)
                    out[h] = if (tint.signed) heat(x, 0.55f) else rgba(tint.r, tint.g, tint.b, 0.08f + 0.72f * x)
                }
                val zero = if (tint.signed) "" else "; hexes with none left clear (${v.count { !it.isNaN() && it <= 0f }} of ${v.count { !it.isNaN() }})"
                return "${tint.label}${causeNote(tint)}: ${f(lo)} to ${f(hi)} $unit (the map's 5th to 95th percentile)$zero"
            }
            ViewLayers.Mode.SWING -> {
                val sw = layers.swing(key, tint.signed) ?: return "${tint.label}: the swing needs two months kept ($kept so far)"
                val (_, hi) = pct(sw) { true }
                val top = if (tint.signed) hi.coerceAtLeast(1e-6f) else 2f
                for (h in sw.indices) if (!sw[h].isNaN()) out[h] = heat((sw[h] / top).coerceIn(0f, 1f), 0.6f)
                return if (tint.signed) "${tint.label}${causeNote(tint)}: highest month less lowest over the $kept months kept; blue none, red ${f(top)} $unit or more"
                else "${tint.label}${causeNote(tint)}: (highest month - lowest) / mean over the $kept months kept; blue flat all year, green 1, red 2 or more"
            }
            ViewLayers.Mode.PEAK -> {
                val pk = layers.peak(key) ?: return "${tint.label}: the peak month needs two months kept ($kept so far)"
                val sw = layers.swing(key, tint.signed)
                val (_, top) = if (sw != null) pct(sw) { true } else 0f to 1f
                for (h in pk.indices) {
                    if (pk[h] < 0) continue
                    val strength = sw?.get(h)?.takeIf { !it.isNaN() }?.let { if (tint.signed) it / top.coerceAtLeast(1e-6f) else it } ?: 1f
                    val c = Color.getHSBColor(pk[h] / 12f, 0.85f, 0.95f)
                    out[h] = Color(c.red, c.green, c.blue, (255 * (0.1f + 0.6f * strength.coerceIn(0f, 1f))).toInt())
                }
                return "${tint.label}${causeNote(tint)}: month of the highest monthly mean, Jan red, Mar yellow, May green, Jul cyan, Sep blue, Nov purple; faint where it hardly swings ($kept months kept)"
            }
            ViewLayers.Mode.YEAR_AGO -> {
                val r = layers.yearAgo(key, tint.signed) ?: return "${tint.label}: needs the same month twice (a year and a month of record)"
                for (h in r.indices) {
                    if (r[h].isNaN()) continue
                    val x = if (tint.signed) (r[h] / 5f).coerceIn(-1f, 1f) else (ln(r[h]) / ln(4f)).coerceIn(-1f, 1f)
                    if (abs(x) < 0.05f) continue
                    out[h] = if (x > 0) rgba(0.9f, 0.15f, 0.1f, 0.1f + 0.65f * x) else rgba(0.1f, 0.35f, 0.95f, 0.1f - 0.65f * x)
                }
                val month = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")[layers.lastMonth]
                return if (tint.signed) "${tint.label}: $month against $month a year before; red warmer, blue colder, full at 5 $unit"
                else "${tint.label}${causeNote(tint)}: $month against $month a year before; red more, blue less, full at 4 times or a quarter"
            }
        }
    }

    private fun causeNote(tint: Measure) = if (tint.byCause) " (" + causeChoice.selectedItem + ")" else ""

    /** The measures' own fixed colour scales, as the viewer has always drawn them; null for a measure without one. */
    private fun fixedColour(tint: Measure, values: FloatArray, out: Array<Color?>): String? {
        val legend = when (tint) {
            Measure.TEMPERATURE -> "blue -15 °C to red 35 °C"; Measure.SNOW -> "white from 5 mm, full at 300 mm"; Measure.RAIN -> "blue from 2 mm, full at 80 mm"
            Measure.GREEN -> "yellow dry to green"; Measure.FORAGE -> "full at 2 kg/m²"; Measure.SOIL -> "brown dry to blue 150 mm"; Measure.WATER -> "full where all the ground is within reach of water"
            Measure.INSECTS -> "full at 1500 kg/km²"; Measure.BITING -> "full at 400 kg/km²"; Measure.POLLINATORS -> "full at 50 kg/km²"; Measure.ORGANIC -> "full at 8 kg/m²"
            Measure.SMALL_GAME -> "log scale, 1 to 600 kg/km²"; Measure.FOXES -> "full at 2 kg/km²"; Measure.OWLS -> "full at 1 kg/km²"; Measure.EAGLES -> "full where every range is held"
            Measure.CARRION -> "full at 20 kg/km²"; Measure.RESIDENTS -> "full at 20 head/km²"
            else -> return null
        }
        for (h in values.indices) {
            val v = values[h]; if (v.isNaN()) continue
            out[h] = when (tint) {
                Measure.TEMPERATURE -> heat(((v + 15f) / 50f).coerceIn(0f, 1f), 0.55f)
                Measure.SNOW -> if (v < 5f) null else rgba(1f, 1f, 1f, (0.25f + 0.6f * min(1f, v / 300f)))
                Measure.RAIN -> if (v < 2f) null else rgba(0.1f, 0.3f, 1f, (0.2f + 0.6f * min(1f, v / 80f)))
                Measure.GREEN -> rgba(1f - v, 0.9f, 0.1f, 0.5f)
                Measure.FORAGE -> rgba(0.1f, 0.7f, 0.1f, (0.1f + 0.7f * min(1f, v / 2f)))
                Measure.SOIL -> rgba(0.5f * (1f - v / 150f), 0.3f, min(1f, v / 150f), 0.55f)
                Measure.WATER -> rgba(0.1f, 0.4f, 1f, 0.1f + 0.6f * v)
                Measure.INSECTS -> rgba(0.6f, 0.8f, 0.1f, (0.1f + 0.7f * min(1f, v / 1500f)))
                Measure.BITING -> rgba(0.8f, 0.1f, 0.1f, (0.05f + 0.7f * min(1f, v / 400f)))
                Measure.POLLINATORS -> rgba(1f, 0.85f, 0.1f, (0.05f + 0.7f * min(1f, v / 50f)))
                Measure.ORGANIC -> rgba(0.35f, 0.2f, 0.05f, (0.05f + 0.7f * min(1f, v / 8f)))
                Measure.SMALL_GAME -> rgba(0.9f, 0.5f, 0.1f, (0.05f + 0.75f * min(1f, ln(1f + v) / ln(1f + 600f)))) // log scale: 1 to 600 kg/km²
                Measure.FOXES -> rgba(0.9f, 0.35f, 0.05f, (0.05f + 0.7f * min(1f, v / 2f)))
                Measure.OWLS -> rgba(0.5f, 0.4f, 0.3f, (0.05f + 0.7f * min(1f, v / 1f)))
                Measure.EAGLES -> rgba(0.3f, 0.2f, 0.1f, (0.05f + 0.6f * v))
                Measure.CARRION -> rgba(0.4f, 0.1f, 0.4f, (0.1f + 0.7f * min(1f, v / 20f)))
                Measure.RESIDENTS -> rgba(1f, 0.1f, 0.6f, (0.05f + 0.7f * min(1f, v / 20f)))
            }
        }
        return "${tint.label}: $legend (the viewer's fixed scale; \"scaled to the map\" stretches it)"
    }

    /** Fills the side panel with what is in hex [h]: its ground, weather, plants, resident herds and borders, and the groups standing in it. */
    private fun inspect(h: Int) = worldLock.whileStill { inspectNow(h) }

    private fun inspectNow(h: Int) {
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
        // The tinted measure here: this week, and month by month over the last year.
        val tint = Measure.entries[tintChoice.selectedIndex]
        if (tint != Measure.NONE) {
            val key = layers.key(tint, causeChoice.selectedIndex)
            layers.now[key]?.get(h)?.takeIf { !it.isNaN() }?.let { v ->
                sb.appendLine("%s%s: %.3g %s".format(tint.label, causeNote(tint), v, tint.unit))
                val m = layers.byMonth(key, h)
                if (m.any { !it.isNaN() }) sb.appendLine("   by month: " + m.withIndex().filter { !it.value.isNaN() }.joinToString(" ") { "%d:%.3g".format(it.index + 1, it.value) })
            }
            if (tint == Measure.HUNGRY_BY_PLENTY && h < layers.hunger.size) sb.appendLine("   plant-eaters short of their wants %.0f%%; most forage left uneaten within 2 hexes %.2f kg/m²".format(100 * layers.hunger[h], layers.plenty[h]))
            if (tint.byCause) sb.appendLine("   by cause: " + PlaceRecord.Cause.entries.joinToString(", ") { c -> "%s %.3g".format(c.label, layers.now[layers.key(tint, c.ordinal)]?.get(h) ?: 0f) })
        }
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
        if (again >= 0) { groupView.selectedIndex = again; showGroupNow() } else { groupView.clearSelection(); groupInfo.text = "" }
    }

    /** Shows the group picked in the list. */
    private fun showGroup() = worldLock.whileStill { showGroupNow() }

    private fun showGroupNow() {
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
        g.band?.let { b ->
            sb.appendLine("larder %.0f kg (%.0f days); camp kept %d weeks".format(g.larder, g.larder / g.dailyNeedKg.coerceAtLeast(1e-3f), b.campWeeks))
            sb.appendLine("skills: " + Skill.entries.filter { b.knowsSkill[it.ordinal] }.joinToString(", ") { "%s %.0f%%".format(it.name.lowercase().replace('_', ' '), 100 * b.skills[it.ordinal]) })
            sb.appendLine("tools: " + Tool.entries.filter { b.knows[it.ordinal] }.joinToString(", ") { "%s %.0f%%".format(it.name.lowercase(), 100 * b.kit[it.ordinal]) })
            sb.appendLine("elders %d; children inbred %.3f; descent mostly lineage %d".format(b.elders, b.inbred, b.mainLineage()))
            if (b.kept.sum() >= 0.5f) sb.appendLine("keeps: " + b.kept.withIndex().filter { it.value >= 0.5f }.joinToString(", ") { "%.0f %s".format(it.value, if (Catalogue.all[it.index].name == "wolf") "dogs" else "tame " + Catalogue.all[it.index].name) })
            sb.appendLine("customs: " + Tradition.entries.filter { b.holds(it) }.joinToString(", ") { it.name.lowercase().replace('_', ' ') }.ifEmpty { "none" })
            sb.appendLine("goods: " + Material.entries.joinToString(", ") { "%s %.0f kg".format(it.name.lowercase(), b.goods[it.ordinal]) })
        }
        if (s.carnivore && g.band == null) sb.appendLine("holds ground at hex %s; meat from herds met this week %.0f kg".format(if (g.home >= 0) g.home.toString() else "none", g.meatMet))
        else {
            val months = (0 until Date.MONTHS_PER_YEAR).filter { g.seasonPlace[it] >= 0 }
            sb.appendLine("remembered places by month: " + months.joinToString(" ") { m -> "%d:%.0fm".format(m + 1, hw.placeElevationM[g.seasonPlace[m]]) })
        }
        if (g.id == groups.watched && followLog.isNotEmpty()) { sb.appendLine(); sb.appendLine("this week:"); sb.appendLine(followLog) }
        groupInfo.text = sb.toString()
    }

    /**
     * The country of hex [h] in words: its kinds of country, how thick the grass, scrub and trees
     * are, how green, and the fruit and dry fodder this season.
     */
    private fun describe(h: Int): String {
        val names = Biome.entries.map { it to hw.biome[it.ordinal][h] }.filter { it.second >= 0.1f }.sortedByDescending { it.second }
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
        val r = (1.5f * v - 0.25f).coerceIn(0f, 1f); val g = (1f - abs(v - 0.5f) * 2f).coerceIn(0f, 1f); val b = (1f - 1.5f * v).coerceIn(0f, 1f)
        return Color(r, g, b, a)
    }

    companion object {
        private val KIND_COLOURS = intArrayOf(0xf0e060, 0xa0e070, 0x70d0f0, 0xf0a050, 0xe070e0, 0x90f0c0, 0xffffff, 0xc0a0ff, 0xffc0c0, 0x80ffff, 0xffd080, 0xb0ff80, 0xff8080, 0x80b0ff, 0xd0d0d0, 0xa0ffa0, 0xffa0ff, 0xc0c060)
    }
}
