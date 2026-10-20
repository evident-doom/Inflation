package inflation.ui

import inflation.learning.LearnedModel
import inflation.Args
import inflation.Config

import inflation.ecology.HexGeography
import inflation.ecology.HexRoutes
import inflation.ecology.WATER_LANDMARK_KM
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
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
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
import inflation.world.Biome
import inflation.world.WorldCache
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import java.io.File
import javax.swing.JCheckBox
import javax.swing.JToggleButton
import javax.swing.text.DefaultCaret
import java.awt.Font
import javax.swing.JOptionPane
import java.awt.FlowLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import java.awt.Point
import java.awt.BasicStroke
import inflation.ecology.get
import inflation.ecology.set
import inflation.ecology.contains
import inflation.ecology.hexOf
import inflation.ecology.m2

/**
 * Watches the hex world: the fine terrain with its hex borders (see [HexPicture]) as the ground,
 * the packs and bands as marks walking hex to hex along the ways they took each week, and one
 * resident kind's density as a tint over the hexes.
 *
 * Usage: hex-viewer [--seed N] [--width KM] [--height KM] [--load FILE]
 * (--load carries on a saved world: the seed and size must be the ones it was saved with.) The
 * config keys choose the learned networks as for hex-fauna (see [LearnedModel]). Without them the
 * hand-written choices are the model.
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

class HexViewer(world: World, load: File? = null, private val nets: String? = null, private val waysFile: String? = null) : JPanel(BorderLayout()) {
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
    private class Shown(val id: Int, val kind: Int, val way: FloatArray, val size: Int, val health: Float, val atHome: Boolean)
    /** A resident kind's spill last week, by hex (see [inflation.ecology.HexFauna.flowOf]): its slot, the net way east and south, head/km². */
    private class Flow(val kind: Int, val east: FloatArray, val south: FloatArray)
    @Volatile private var flowShown: Flow? = null
    /** Where the chosen plant-eater's seasonal memory pulls it from each hex for the coming month ([inflation.ecology.SeasonalMemory]), as a [Flow] of log worth per hex. */
    @Volatile private var pullShown: Flow? = null

    /** What the hexes are tinted by, worked out each week and kept month by month (see [ViewLayers]). */
    private val layers = ViewLayers(sim)
    private val tintChoice = JComboBox(Measure.entries.map { it.label }.toTypedArray())
    /** How the tint is shown: this week (as drawn so far), scaled to the map, or over the last year. */
    private val modeChoice = JComboBox(ViewLayers.Mode.entries.map { it.label }.toTypedArray())
    /** For the deaths: which cause, or all. */
    private val causeChoice = JComboBox((PlaceRecord.Cause.entries.map { "died of " + it.label } + "all causes").toTypedArray()).apply {
        selectedIndex = PlaceRecord.Cause.entries.size
        isEnabled = false
    }
    /** What the tint's colours mean. */
    private val legendText = JLabel("")
    private val windBox = JCheckBox("Wind", false)
    /** The chosen resident kind's movement last week, as arrows (see [Flow]). */
    private val flowBox = JCheckBox("Resident flow", true)
    /** Tints the chosen resident kind by how high up its hexes it stands, not by its numbers. */
    private val heightBox = JCheckBox("Up/down", false)
    /** The chosen plant-eater's pull by its seasonal memory, as arrows (see [pullShown]). */
    private val pullBox = JCheckBox("Memory pull", true)
    /** Rivers by this week's flow (see [RiverOverlay]); unticked, no rivers are drawn. */
    private val riversBox = JCheckBox("River flows", true)
    private val huntersBox = JCheckBox("Hunters", true)
    private val peopleBox = JCheckBox("People", true)
    /** Paths worn by packs and bands, drawn hex to hex, darker and wider the more worn. */
    private val pathsBox = JCheckBox("Paths", true)
    /** The names of the great features of the country (see [HexGeography]), written over the map. */
    private val namesBox = JCheckBox("Names", false)
    private val features by lazy { HexGeography(hw) }
    /** Follow mode: the group followed (-1 none), kept in view week by week, with what happened to it. */
    @Volatile private var followLog = ""

    /** The followed group's controls (see [GroupControlWindow]): part of the run only while open. */
    private val control by lazy { GroupControlWindow(sim, ground, px, rivers, worldLock, requestStep = { commands += { advance() } }, running = { running }) }

    /**
     * The place whose weather stands for hex [h]: its own low ground, or for a sea or lake hex the
     * nearest land within a few rings (the weather is worked out on land only); -1 if none.
     */
    private fun windPlace(h: Int): Int {
        hw.firstPlaceIn(h).takeIf { it >= 0 }?.let { return it }
        var ring = listOf(h); val seen = hashSetOf(h)
        repeat(4) {
            val next = ArrayList<Int>()
            for (r in ring) for (d in 0 until 6) { val q = hex.neighbour(r, d); if (q >= 0 && seen.add(q)) next += q }
            for (q in next) hw.firstPlaceIn(q).takeIf { it >= 0 }?.let { return it }
            ring = next
        }
        return -1
    }
    /** Whose sense of direction to draw as arrows: a resident kind or a kind living in groups. */
    private val pullChoice by lazy { JComboBox(arrayOf("(no sense of direction)") + kindNames().map { "pull: $it" }.toTypedArray()) }
    /** The resident kinds and the kinds in groups, by name. */
    private fun kindNames() = fauna.kinds.map { it.name } + groups.kinds.map { it.name }
    /** Where each group was drawn at the end of the last move, km. */
    private val lastDrawn = HashMap<Int, Pair<Float, Float>>()
    @Volatile private var shown = emptyList<Shown>()
    @Volatile private var frame = 0
    @Volatile private var frames = 1
    private val date = JLabel()
    /**
     * The kinds and their numbers along the bottom, as buttons: pressing one shows where that kind is
     * (its density over the hexes, and only its packs or bands drawn); pressing it again shows all.
     */
    private val kindButtons = ArrayList<JToggleButton>()
    /** Which kind's numbers to tint the hexes by: any resident kind, or any kind in packs and bands. */
    @Volatile private var onlyKind: Species? = null
    /**
     * Life in the water to show as a tint: fish of the sea and lakes, river fish, shellfish, and each kind of sea
     * animal (densities per hex).
     */
    private val waterLayers by lazy { listOf<Pair<String, FloatArray>>("sea and lake fish" to fauna.water.fish, "river fish" to fauna.water.riverFish,
        "shellfish" to fauna.water.shellfish) +
        HexWater.SEA.mapIndexed { i, k -> k.name to fauna.water.sea[i] } }
    private val residentChoice by lazy { JComboBox(arrayOf("(no kind shown)") + kindNames().map { "where: $it" }.toTypedArray() +
        waterLayers.map { "water: " + it.first }.toTypedArray()) }
    private val map = MapPanel()
    /** The hex being inspected, -1 for none, and its groups. */
    @Volatile private var picked = -1
    private val info = JTextArea(14, 40).apply {
        isEditable = false; (caret as DefaultCaret).updatePolicy = DefaultCaret.NEVER_UPDATE
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
    }
    private val groupList = DefaultListModel<String>()
    private val groupView = JList(groupList)
    private val groupInfo = JTextArea(12, 40).apply {
        isEditable = false; (caret as DefaultCaret).updatePolicy = DefaultCaret.NEVER_UPDATE
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
    }
    private var groupIds = IntArray(0)

    init {
        val bar = JPanel()
        // (The time controls here take over from the follow window: a week waiting there for the player goes on with the picks shown.)
        val run = JButton("Run")
        run.addActionListener { running = !running; run.text = if (running) "Pause" else "Run"; control.overviewTakesOver() }
        val step = JButton("Step"); step.addActionListener { control.overviewTakesOver(); commands += { advance() } }
        val speed = JSlider(1, 10, stepsPerSecond); speed.addChangeListener { stepsPerSecond = speed.value }
        residentChoice.addActionListener {
            // (Its spill is recorded from the next week on.)
            val r = residentChoice.selectedIndex - 1
            fauna.flowOf = if (r in fauna.kinds.indices) r else -1
            map.repaint()
        }
        flowBox.addActionListener { map.repaint() }
        heightBox.addActionListener { map.repaint() }
        pullBox.addActionListener { map.repaint() }
        tintChoice.addActionListener {
            causeChoice.isEnabled = Measure.entries[tintChoice.selectedIndex].byCause
            map.repaint()
            if (picked >= 0) inspect(picked)
        }
        modeChoice.addActionListener { map.repaint() }; causeChoice.addActionListener { map.repaint(); if (picked >= 0) inspect(picked) }
        windBox.addActionListener { map.repaint() }
        riversBox.addActionListener { rivers.visible = riversBox.isSelected; map.repaint() }
        huntersBox.addActionListener { map.repaint() }
        peopleBox.addActionListener { map.repaint() }
        pathsBox.addActionListener { map.repaint() }
        namesBox.addActionListener { map.repaint() }
        pullChoice.addActionListener { map.repaint() }
        bar.add(run)
        bar.add(step)
        bar.add(JLabel("weeks/s"))
        bar.add(speed)
        bar.add(tintChoice)
        bar.add(modeChoice)
        bar.add(causeChoice)
        bar.add(residentChoice)
        bar.add(flowBox)
        bar.add(heightBox)
        bar.add(pullBox)
        bar.add(huntersBox)
        bar.add(peopleBox)
        bar.add(pathsBox)
        bar.add(namesBox)
        bar.add(windBox)
        bar.add(riversBox)
        bar.add(pullChoice)
        val graphs = JButton("History…"); graphs.addActionListener { HexHistoryPanel.show(history, live = true, title = "History, seed ${cfg.seed}") }
        // Save the world as it stands, or carry on one saved before.
        val save = JButton("Save…")
        save.addActionListener {
            chooseFile(true)?.let { f -> commands += { HexSnapshot.save(sim, f); SwingUtilities.invokeLater { date.text = "   saved to ${f.name}" } } }
        }
        val open = JButton("Load…"); open.addActionListener { chooseFile(false)?.let { f -> commands += { loadWorld(f) } } }
        // Following a group is the follow window: open it on the group picked in the list.
        val follow = JButton("Follow…")
        follow.toolTipText = "Follow the group picked in the list in its own window: its local map and state, what it knows, its choices each week (the model's highlighted), yours, and Next step"
        follow.addActionListener {
            val i = groupView.selectedIndex
            if (i in groupIds.indices) control.follow(groupIds[i]) else JOptionPane.showMessageDialog(this,
                "Click a hex on the map and pick a group in the list first.")
        }
        bar.add(follow)
        bar.add(graphs); bar.add(save); bar.add(open); bar.add(date)
        useNetworks()
        load?.let { f -> commands += { loadWorld(f) } }
        // Every kind's button in view: as many rows as the window's width needs.
        val bottom = JPanel(WrapLayout(FlowLayout.LEFT, 4, 2))
        // (One button a kind.)
        val all = fauna.kinds + groups.kinds
        for ((i, s) in all.withIndex()) {
            val b = JToggleButton(s.name); b.margin = Insets(1, 4, 1, 4)
            b.toolTipText = if (i < fauna.kinds.size) "${s.name}: lives spread over the land (shown as its density)"
                else "${s.name}: lives in packs or bands (shown as its density, and only its groups drawn)"
            b.addActionListener {
                for (o in kindButtons) if (o !== b) o.isSelected = false
                onlyKind = if (b.isSelected && s in groups.kinds) s else null
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
        val south = JPanel(BorderLayout())
        south.add(legendText.apply { border = BorderFactory.createEmptyBorder(2, 6, 2, 6) }, BorderLayout.NORTH)
        south.add(bottom, BorderLayout.CENTER)
        south.addComponentListener(object : ComponentAdapter() { override fun componentResized(e: ComponentEvent) = bottom.revalidate() })
        add(bar, BorderLayout.NORTH); add(split, BorderLayout.CENTER); add(south, BorderLayout.SOUTH)
        snapshot()
        Thread(::loop, "hex simulation").apply { isDaemon = true }.start()
        Timer(33) { if (frame < frames) { frame++; map.repaint() } }.start()
    }

    private fun chooseFile(save: Boolean): File? {
        val fc = JFileChooser(File(".")).apply { fileFilter = FileNameExtensionFilter("saved hex worlds", "hexw") }
        val ok = if (save) fc.showSaveDialog(this) else fc.showOpenDialog(this)
        if (ok != JFileChooser.APPROVE_OPTION) return null
        val f = fc.selectedFile
        return if (save && !f.name.endsWith(".hexw")) File(f.path + ".hexw") else f
    }

    /** Carries on the world saved in [f] (on the simulation's thread). */
    private fun loadWorld(f: File) {
        try {
            HexSnapshot.load(sim, f)
            layers.reset()
            useNetworks()
            lastDrawn.clear()
            snapshot()
        } catch (e: Exception) {
            SwingUtilities.invokeLater { JOptionPane.showMessageDialog(this, "Could not load ${f.name}: ${e.message}") }
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

    /** The model: learned networks where given ([nets], [waysFile], see [LearnedModel]), else the hand-written choices. */
    private fun useNetworks() {
        LearnedModel.apply(groups, nets, waysFile, Config.string("hunterWays")) { println(it) }
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
            list += Shown(g.id, g.species.index, pts.toFloatArray(), g.size, health, g.band?.atHome == true)
            lastDrawn[g.id] = end
        }
        shown = list
        // The chosen resident kind's spill this week, net, per km² of each hex.
        val fk = fauna.flowOf
        flowShown = if (fk < 0) null else {
            val area = DoubleArray(hex.size)
            for (p in 0 until fauna.n) area[hw.placeHex[p]] += land.areaKm2[p].toDouble()
            Flow(fk, FloatArray(hex.size) { if (area[it] > 0) (fauna.flowEast[it] / hex.hexKm / area[it]).toFloat() else 0f },
                FloatArray(hex.size) { if (area[it] > 0) (fauna.flowSouth[it] / hex.hexKm / area[it]).toFloat() else 0f })
        }
        // Its seasonal memory's pull, as the residents follow it this week: from each hex, the first step of the route
        // from its place that gains most ([HexFauna.routeTo]), as long as all that route gains.
        pullShown = if (fk < 0 || fauna.room[fk] == null) null else {
            val east = FloatArray(hex.size); val south = FloatArray(hex.size); val most = FloatArray(hex.size)
            for (p in 0 until fauna.n) {
                val h = hw.placeHex[p]; val g = fauna.routeGainAt(fk, p); if (!(g > most[h])) continue
                val q = fauna.routeTo(fk, p); if (q < 0) continue
                val to = hw.placeHex[q]; most[h] = g
                if (to == h) { east[h] = 0f; south[h] = 0f; continue }
                val (x0, y0) = hex.centreKm(h); val (x1, y1) = hex.centreKm(to)
                east[h] = (g * (x1 - x0) / hex.hexKm).toFloat(); south[h] = (g * (y1 - y0) / hex.hexKm).toFloat()
            }
            Flow(fk, east, south)
        }
        // Following a group (in the follow window): the week's log, for the panel when that group is picked.
        followLog = if (groups.watched >= 0) groups.watchLog.joinToString("\n") else ""
        SwingUtilities.invokeLater { if (picked >= 0) inspect(picked) }
        frames = max(8, 30 / stepsPerSecond); frame = 0
        // (The texts are made here, on the simulation's thread, from the world as the week left it.)
        val dateText = "   year ${weather.date.year}, month ${weather.date.month + 1}, day ${weather.date.dayOfMonth}"
        val all = fauna.kinds + groups.kinds
        val kindTexts = all.withIndex().map { (i, s) ->
            "${s.name} " + if (i >= fauna.kinds.size) "${groups.count(s)} in ${groups.groups.count { it.alive && it.species == s }}"
                else "%.0f".format(fauna.count(i))
        }
        SwingUtilities.invokeLater {
            date.text = dateText
            for ((i, b) in kindButtons.withIndex()) b.text = kindTexts[i]
        }
    }

    private fun loop() {
        var last = 0L
        while (true) {
            while (true) { val c = commands.poll() ?: break; worldLock.changing(c) }
            if (running && frame >= frames && System.nanoTime() - last > 1_000_000_000L / stepsPerSecond) {
                last = System.nanoTime()
                worldLock.changing { advance() }
            }
            else Thread.sleep(5)
        }
    }

    /**
     * A colour from components that may stray outside 0..1 (a measure beyond its scale): clamped, as Color would
     * otherwise throw and leave the map unpainted.
     */
    private fun rgba(r: Float, g: Float, b: Float, a: Float) = Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f), a.coerceIn(0f, 1f))

    private inner class MapPanel : JPanel() {
        private var scale = 0.5
        private var ox = 0.0; private var oy = 0.0
        private var drag: Point? = null

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

        /**
         * Up/down: where in each hex's span of heights resident kind [r]'s animals stand, by heads (0 the lowest band's, 1
         * the highest's; [dens] its heads in each hex), so that moving up and down the slopes within a hex shows; NaN for
         * hexes with one band, left clear.
         */
        private fun upDown(r: Int, dens: DoubleArray): FloatArray {
            val lo = FloatArray(hex.size) { Float.MAX_VALUE }; val hi = FloatArray(hex.size) { -Float.MAX_VALUE }
            val sum = DoubleArray(hex.size)
            for (p in 0 until fauna.n) { val h = hw.placeHex[p]; val e = hw.placeElevationM[p]
                lo[h] = min(lo[h], e); hi[h] = max(hi[h], e)
                sum[h] += (fauna.adults[r][p] + fauna.young[r][p]) * land.areaKm2[p] * e.toDouble() }
            return FloatArray(hex.size) { h -> if (dens[h] <= 0 || hi[h] - lo[h] < 1f) Float.NaN else ((sum[h] / dens[h] - lo[h]) / (hi[h] - lo[h])).toFloat().coerceIn(0f, 1f) }
        }
        private val upDownLegend = MapLegend("Up/down", "Up/down: where the chosen kind stands in each hex's span of heights, by heads",
            { x -> Color(x, 0.25f, 1f - x, 0.75f) }, listOf(0f to "lowest band", 0.5f to "between", 1f to "highest band"),
            "hexes of one band left clear")

        private fun paintMap(g: Graphics2D) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.drawImage(ground, ox.toInt(), oy.toInt(), (ground.width * scale).toInt(), (ground.height * scale).toInt(), null)
            val kmPx = px * scale
            rivers.draw(g, ox, oy, kmPx, width, height)
            fun sx(km: Float) = (ox + km * kmPx).toInt()
            fun sy(km: Float) = (oy + km * kmPx).toInt()
            // Hexes whose centre lies more than [hexes] hexes off the screen are skipped: at a close zoom most of the map is.
            fun onScreen(h: Int, hexes: Int = 1): Boolean {
                val (cx, cy) = hex.centreKm(h)
                val margin = hexes * hex.hexKm * kmPx
                val x = ox + cx * kmPx; val y = oy + cy * kmPx
                return x > -margin && y > -margin && x < width + margin && y < height + margin
            }
            // Hex [h] filled with [colour], or with [fill] false outlined.
            fun hexagon(h: Int, colour: Color, fill: Boolean = true) {
                val (cx, cy) = hex.centreKm(h); val rad = hex.hexKm / sqrt(3.0)
                val xs = IntArray(6) { sx((cx + rad * cos(Math.PI / 6 + it * Math.PI / 3)).toFloat()) }
                val ys = IntArray(6) { sy((cy + rad * sin(Math.PI / 6 + it * Math.PI / 3)).toFloat()) }
                g.color = colour; if (fill) g.fillPolygon(xs, ys, 6) else g.drawPolygon(xs, ys, 6)
            }
            // The chosen measure as a tint over the hexes (see [ViewLayers]), shown as the mode says.
            val tint = Measure.entries[tintChoice.selectedIndex]
            val legends = ArrayList<MapLegend>()
            if (tint != Measure.NONE) {
                val rad = hex.hexKm / sqrt(3.0)
                val mode = ViewLayers.Mode.entries[modeChoice.selectedIndex]
                val key = layers.key(tint, causeChoice.selectedIndex)
                val colours = arrayOfNulls<Color>(hex.size)
                val legend = paintTint(tint, mode, key, colours)
                legends += legend
                if (legendText.text != legend.text) SwingUtilities.invokeLater { legendText.text = legend.text }
                for (h in 0 until hex.size) {
                    val colour = colours[h] ?: continue
                    if (!onScreen(h)) continue
                    hexagon(h, colour)
                }
            } else if (legendText.text.isNotEmpty()) SwingUtilities.invokeLater { legendText.text = "" }
            // Wind as small arrows, every few hexes, longer and brighter the stronger.
            if (windBox.isSelected) {
                val every = max(1, (2.0 / scale).toInt())
                for (h in 0 until hex.size) {
                    val col = h % hex.cols; val row = h / hex.cols
                    if (col % every != 0 || row % every != 0 || !onScreen(h, every + 1)) continue
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
                    if (col % every != 0 || row % every != 0 || hex.heart[h] < 0 || !onScreen(h, every + 1)) continue
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
                if (r < fauna.kinds.size) for (p in 0 until fauna.n) dens[hw.placeHex[p]] += ((fauna.adults[r][p] + fauna.young[r][p]) *
                    land.areaKm2[p]).toDouble()
                else if (r < fauna.kinds.size + groups.kinds.size) {
                    val s = groups.kinds[r - fauna.kinds.size]
                    for (gr in groups.groups) if (gr.alive && gr.species == s) dens[hw.placeHex[gr.place]] += gr.size.toDouble()
                }
                else {
                    // Life in the water: its density per hex as it is kept (sea hexes too).
                    val stock = waterLayers[r - fauna.kinds.size - groups.kinds.size].second
                    for (h in 0 until hex.size) { area[h] = 1.0; dens[h] = stock[h].toDouble() }
                }
                val up = if (heightBox.isSelected && r < fauna.kinds.size) upDown(r, dens) else null
                if (up != null) {
                    legends += upDownLegend
                    for (h in 0 until hex.size) {
                        val x = up[h]; if (x.isNaN() || !onScreen(h)) continue
                        hexagon(h, Color(x, 0.25f, 1f - x, 0.75f))
                    }
                } else {
                // Shaded against the densest twentieth of hexes, so that thin spreads show too.
                val values = (0 until hex.size).filter { area[it] > 0 && dens[it] > 0 }.map { dens[it] / area[it] }.sorted()
                val top = (if (values.isEmpty()) 0.0 else values[(values.size * 0.95).toInt().coerceAtMost(values.size - 1)]).coerceAtLeast(1e-6)
                val water = r >= fauna.kinds.size + groups.kinds.size
                val name = residentChoice.selectedItem.toString()
                legends += MapLegend(name, name, { x -> Color(1f, 0.1f, 0.6f, 0.6f * sqrt(x).coerceIn(0.15f, 1f)) },
                    listOf(0f to "few", 0.25f to MapLegend.num((top / 4).toFloat()), 1f to MapLegend.num(top.toFloat()) + "+"),
                    (if (water) "as kept per hex" else "head per km²") + "; full at the densest twentieth of hexes; clear where none")
                for (h in 0 until hex.size) {
                    if (area[h] <= 0) continue
                    if (dens[h] <= 0 || !onScreen(h)) continue
                    val v = sqrt(dens[h] / area[h] / top).toFloat().coerceIn(0.15f, 1f)
                    hexagon(h, Color(1f, 0.1f, 0.6f, 0.6f * v))
                }
                }
            }
            // The chosen resident kind's movement last week: an arrow from each hex the way its animals went on balance,
            // longer and brighter the more went (against the busiest twentieth of hexes).
            val flow = flowShown
            if (flowBox.isSelected && flow != null && flow.kind == r) {
                val every = max(1, (1.5 / scale).toInt())
                val mags = (0 until hex.size).map { hypot(flow.east[it], flow.south[it]) }.filter { it > 0f }.sorted()
                val top = (if (mags.isEmpty()) 0f else mags[(mags.size * 0.95).toInt().coerceAtMost(mags.size - 1)]).coerceAtLeast(1e-9f)
                g.color = Color.WHITE
                for (h in 0 until hex.size) {
                    val col = h % hex.cols; val row = h / hex.cols
                    if (col % every != 0 || row % every != 0 || !onScreen(h, every + 1)) continue
                    val u = flow.east[h]; val v = flow.south[h]
                    val m = hypot(u, v); if (m < 0.05f * top) continue
                    val x = min(1f, m / top)
                    val (cx, cy) = hex.centreKm(h)
                    val len = (x * hex.hexKm * every * 0.8).toFloat()
                    val x0 = sx(cx.toFloat()); val y0 = sy(cy.toFloat())
                    val x1 = sx(cx.toFloat() + u / m * len); val y1 = sy(cy.toFloat() + v / m * len)
                    g.color = Color(0.6f, 1f, 1f, 0.35f + 0.65f * x)
                    g.drawLine(x0, y0, x1, y1)
                    val ang = atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())
                    for (sgn in intArrayOf(-1, 1)) g.drawLine(x1, y1, (x1 - 5 * cos(ang + sgn * 0.5)).toInt(), (y1 - 5 * sin(ang + sgn * 0.5)).toInt())
                }
                legends += MapLegend("Resident flow", "Resident flow: where the chosen resident kind's animals went last week on balance, an arrow from each hex the " +
                    "net way they spilled to the hexes about it, longer and brighter the more went (full at the busiest twentieth of hexes).",
                    note = "net head/km² a week; full at " + MapLegend.num(top))
            }
            // The chosen plant-eater's seasonal memory: an arrow from each hex the way it pulls them for the coming month.
            val pull = pullShown
            if (pullBox.isSelected && pull != null && pull.kind == r) {
                val every = max(1, (1.5 / scale).toInt())
                val mags = (0 until hex.size).map { hypot(pull.east[it], pull.south[it]) }.filter { it > 0f }.sorted()
                val top = (if (mags.isEmpty()) 0f else mags[(mags.size * 0.95).toInt().coerceAtMost(mags.size - 1)]).coerceAtLeast(1e-9f)
                for (h in 0 until hex.size) {
                    val col = h % hex.cols; val row = h / hex.cols
                    if (col % every != 0 || row % every != 0 || !onScreen(h, every + 1)) continue
                    val u = pull.east[h]; val v = pull.south[h]
                    val m = hypot(u, v); if (m < 0.05f * top) continue
                    val x = min(1f, m / top)
                    val (cx, cy) = hex.centreKm(h)
                    val len = (x * hex.hexKm * every * 0.8).toFloat()
                    val x0 = sx(cx.toFloat()); val y0 = sy(cy.toFloat())
                    val x1 = sx(cx.toFloat() + u / m * len); val y1 = sy(cy.toFloat() + v / m * len)
                    g.color = Color(1f, 0.75f, 0.2f, 0.35f + 0.65f * x)
                    g.drawLine(x0, y0, x1, y1)
                    val ang = atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())
                    for (sgn in intArrayOf(-1, 1)) g.drawLine(x1, y1, (x1 - 5 * cos(ang + sgn * 0.5)).toInt(), (y1 - 5 * sin(ang + sgn * 0.5)).toInt())
                }
                legends += MapLegend("Memory pull", "Memory pull: the route the chosen plant-eater's seasonal memory leads its residents along this week, " +
                    "from each hex the first step towards the best ground a month's going reaches, longer and brighter the more that ground gains over " +
                    "staying (full at the strongest twentieth of hexes). Empty until the kind has lived a year with the memory.",
                    note = "gain over staying (log), full at " + MapLegend.num(top * 100) + "%")
            }
            // Paths: hex centre to hex centre, each pair once, darker and wider the more worn.
            if (pathsBox.isSelected) {
                val stroke = g.stroke
                for (h in 0 until hex.size) for (d in 0 until 3) {
                    if (!onScreen(h, 2)) break
                    val nh = hex.neighbour(h, d); if (nh < 0) continue
                    val back = (0 until 6).firstOrNull { hex.neighbour(nh, it) == h } ?: continue
                    val s = max(groups.wear.paths[h * 6 + d], groups.wear.paths[nh * 6 + back])
                    if (s < 0.05f) continue
                    val (x0, y0) = hex.centreKm(h); val (x1, y1) = hex.centreKm(nh)
                    g.color = Color(0.45f, 0.3f, 0.12f, (0.25f + 0.65f * s).coerceAtMost(1f))
                    g.stroke = BasicStroke((0.5f + 3f * s) * max(0.5f, scale.toFloat()))
                    g.drawLine(sx(x0.toFloat()), sy(y0.toFloat()), sx(x1.toFloat()), sy(y1.toFloat()))
                }
                g.stroke = stroke
                // The routes: the legs between places, dark and wider the more used (by their least worn stretch).
                val routes = groups.routes
                g.color = Color(0.2f, 0.1f, 0.05f, 0.9f)
                for ((i, leg) in routes.legs.withIndex()) {
                    if (!onScreen(leg[0], 2) && !onScreen(leg[leg.size - 1], 2)) continue
                    g.stroke = BasicStroke((0.5f + 4f * routes.legWear[i]) * max(0.5f, scale.toFloat()), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    val xs = IntArray(leg.size) { sx(hex.centreKm(leg[it]).first.toFloat()) }
                    val ys = IntArray(leg.size) { sy(hex.centreKm(leg[it]).second.toFloat()) }
                    g.drawPolyline(xs, ys, leg.size)
                }
                g.stroke = stroke
                // Landmarks: each place by its most telling landmark, a full mark; the others in it small.
                val r = max(2, (2.5 * max(0.5, scale)).toInt())
                for (h in routes.landmarks) {
                    val (cx, cy) = hex.centreKm(h); val x = sx(cx.toFloat()); val y = sy(cy.toFloat())
                    val k = routes.kind(h)
                    val rr = if (routes.places.getOrNull(routes.placeOf[h]) == h) r else max(1, r / 2)
                    g.color = landmarkColour(k)
                    if (k == HexRoutes.Kind.PASS || k == HexRoutes.Kind.PEAK) g.fillPolygon(intArrayOf(x - rr, x, x + rr), intArrayOf(y + rr, y - rr, y + rr), 3)
                    else g.fillOval(x - rr, y - rr, 2 * rr, 2 * rr)
                }
                legends += MapLegend("Paths and landmarks", "Paths: brown lines between hex centres, darker and wider the more worn. Routes: dark lines from place to place along the most trodden strands, wider the more used. " +
                    "A place is its most telling landmark (full mark) with the landmarks within a ring of it (small marks). Landmarks on the routes: " +
                    "junction (three or more worn ways meet), ford (a worn way crosses a river deeper than the knee), pass (a worn way through rugged country, " +
                    "its ring at least as rough), water (within ${WATER_LANDMARK_KM.toInt()} km of drinking water), end (a single worn way). " +
                    "Natural landmarks, on a route or not: hilltop (higher than all within two rings), lake, spring, where two rivers meet, river mouth.",
                    note = groups.routes.summary(),
                    swatches = HexRoutes.Kind.entries.drop(1).map { MapLegend.Swatch(landmarkColour(it), it.label, it == HexRoutes.Kind.PASS || it == HexRoutes.Kind.PEAK) })
            }
            // The names of the country: the larger the feature, the larger its name; rivers in blue along their middle.
            if (namesBox.isSelected) {
                val font = g.font
                for (f in features.all.sortedByDescending { it.hexes.size }) {
                    if (!onScreen(f.label)) continue
                    val px = (9 + 2.2 * ln(f.hexes.size.toDouble())) * max(0.5, scale)
                    g.font = font.deriveFont(if (f.kind == HexGeography.Kind.RIVER) Font.ITALIC else Font.BOLD, px.toFloat())
                    val (cx, cy) = hex.centreKm(f.label)
                    val w = g.fontMetrics.stringWidth(f.name); val x = sx(cx.toFloat()) - w / 2; val y = sy(cy.toFloat())
                    // (Only where the name fits along the river, or across the stretch of country, at this zoom.)
                    val across = if (f.kind == HexGeography.Kind.RIVER) f.hexes.size * hex.hexKm else sqrt(f.hexes.size * hex.areaKm2)
                    if (w > across * kmPx) continue
                    g.color = Color(1f, 1f, 1f, 0.7f)
                    for ((dx, dy) in arrayOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) g.drawString(f.name, x + dx, y + dy)
                    g.color = featureColour(f.kind)
                    g.drawString(f.name, x, y)
                }
                g.font = font
                legends += MapLegend("Names", "The great features of the country, each named once: rivers (main stems, the greater flow keeping the name), " +
                    "their valleys and floodplains, lakes, and stretches of mountains, hills, forest, woods, steppe, desert and marsh larger than the ground one place draws on; " +
                    "bays and straits, peninsulas, isthmuses and islands (narrower than the ground one place draws on, or than a range). The larger the feature, the larger its name; a name is written only where it fits along its river or across its country.", note = features.summary(),
                    swatches = HexGeography.Kind.entries.map { MapLegend.Swatch(featureColour(it), it.label) })
            }
            // The hex being inspected.
            if (picked >= 0) {
                g.stroke = BasicStroke(2.5f); hexagon(picked, Color.WHITE, false); g.stroke = BasicStroke(1f)
            }
            // The groups, each part of the way along the hexes it walked this week; at the end of the
            // week those sharing a hex stand side by side in a small ring around its centre.
            val t = min(1f, frame.toFloat() / frames)
            for (s in shown) {
                val sp = Catalogue.all[s.kind]
                // The group followed in the follow window: a yellow ring.
                if (s.id == groups.watched) {
                    val (fx, fy) = along(s.way, t)
                    g.color = Color(1f, 0.85f, 0.1f)
                    g.stroke = BasicStroke(2.5f)
                    g.drawOval(sx(fx) - 9, sy(fy) - 9, 18, 18)
                    g.stroke = BasicStroke(1f)
                }
                if (if (sp.human) !peopleBox.isSelected else !huntersBox.isSelected) continue
                if (onlyKind != null && sp != onlyKind) continue
                val w = s.way
                val (x, y) = along(w, t)
                if (sp.human) {
                    // A band: a white-ringed blue diamond, or a hut while it is at home in its houses,
                    // fading as it goes hungry.
                    val a = 0.35f + 0.65f * s.health
                    val px = sx(x); val py = sy(y)
                    if (s.atHome) {
                        // A pitched roof over a walled square.
                        g.color = Color(1f, 1f, 1f, a)
                        g.fillPolygon(intArrayOf(px, px + 7, px + 5, px + 5, px - 5, px - 5, px - 7), intArrayOf(py - 7, py, py, py + 5, py + 5, py, py), 7)
                        g.color = Color(0.1f, 0.3f, 0.95f, a)
                        g.fillPolygon(intArrayOf(px, px + 5, px - 5), intArrayOf(py - 5, py, py), 3)
                        g.fillRect(px - 3, py, 7, 4)
                    } else {
                        g.color = Color(1f, 1f, 1f, a); g.fillPolygon(intArrayOf(px, px + 5, px, px - 5), intArrayOf(py - 5, py, py + 5, py), 4)
                        g.color = Color(0.1f, 0.3f, 0.95f, a); g.fillPolygon(intArrayOf(px, px + 3, px, px - 3), intArrayOf(py - 3, py, py + 3, py), 4)
                    }
                } else {
                    // Hunters fade as they starve.
                    val a = 0.25f + 0.75f * s.health
                    g.color = Color(1f, 1f, 1f, a); g.fillRect(sx(x) - 3, sy(y) - 3, 7, 7)
                    g.color = Color(0.82f, 0.06f, 0.06f, a); g.fillRect(sx(x) - 2, sy(y) - 2, 5, 5)
                }
            }
            // What the tints' colours mean, in the top right corner.
            MapLegend.draw(g, legends, width)
        }

        private fun along(w: FloatArray, t: Float): Pair<Float, Float> {
            if (w.size <= 2) return w[0] to w[1]
            var total = 0f
            for (j in 0 until w.size / 2 - 1) total += hypot(w[2 * j + 2] - w[2 * j], w[2 * j + 3] - w[2 * j + 1])
            var left = t * total
            for (j in 0 until w.size / 2 - 1) {
                val len = hypot(w[2 * j + 2] - w[2 * j], w[2 * j + 3] - w[2 * j + 1])
                if (left <= len && len > 0f) {
                    val f = left / len
                    return w[2 * j] + (w[2 * j + 2] - w[2 * j]) * f to w[2 * j + 1] + (w[2 * j + 3] - w[2 * j + 1]) * f
                }
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
    private fun paintTint(tint: Measure, mode: ViewLayers.Mode, key: String, out: Array<Color?>): MapLegend {
        val title = "${tint.label}${causeNote(tint)}"
        fun waiting(why: String) = MapLegend(title, "${tint.label}: $why", note = why)
        val v = layers.now[key] ?: return waiting("waiting for the first week")
        val unit = tint.unit
        fun pct(vals: FloatArray, keep: (Float) -> Boolean): Pair<Float, Float> {
            val l = vals.filter { !it.isNaN() && keep(it) }.sorted()
            if (l.isEmpty()) return 0f to 1f
            return l[(l.size * 0.05).toInt()] to l[(l.size * 0.95).toInt().coerceAtMost(l.size - 1)]
        }
        fun f(x: Float) = MapLegend.num(x)
        fun shade(h: Int, a: Float) { out[h] = rgba(tint.r, tint.g, tint.b, a) }
        val kept = layers.monthsKept(key)
        when (mode) {
            ViewLayers.Mode.WEEK -> {
                if (tint.weeks) {
                    val full = ln(1f + 104f)
                    for (h in v.indices) if (!v[h].isNaN() && v[h] > 0f) shade(h, 0.05f + 0.75f * min(1f, ln(1f + v[h]) / full))
                    return MapLegend(title, "${tint.label}: what lies there over what went in a week. Pale: gone within days; full: two years or more (accumulating).",
                        { x -> if (x <= 0f) null else rgba(tint.r, tint.g, tint.b, 0.05f + 0.75f * x) },
                        listOf(0f to "0", ln(1f + 4f) / full to "4", ln(1f + 20f) / full to "20", 1f to "104+"),
                        "weeks to clear (log scale); full: accumulating")
                }
                if (tint == Measure.HUNTERS_PER_PREY) {
                    fun colour(l: Float) = if (l >= 0) rgba(0.9f, 0.15f, 0.1f, 0.15f + 0.6f * l) else rgba(0.1f, 0.4f, 0.9f, 0.15f - 0.6f * l)
                    for (h in v.indices) if (!v[h].isNaN() && v[h] > 0f) out[h] = colour((log10(v[h]) / 1.5f).coerceIn(-1f, 1f))
                    return MapLegend(title, "Hunters per prey, kg per 100 kg over a hex and its ring (people not counted). Clear at 1, about what prey carries of one carnivore kind in nature (Carbone & Gittleman 2002); red above (full at 30), blue below (full at 0.03).",
                        { x -> colour(2f * x - 1f) }, listOf(0f to "0.03", 0.5f to "1", 1f to "30+"),
                        "kg hunters per 100 kg prey, log scale; people not counted")
                }
                fixedColour(tint, v, out)?.let { return it }
                return paintTint(tint, ViewLayers.Mode.SCALED, key, out)
            }
            ViewLayers.Mode.SCALED -> {
                val (lo, hi) = pct(v) { tint.signed || it > 0f }
                val span = (hi - lo).coerceAtLeast(1e-9f)
                fun colour(x: Float) = if (tint.signed) heat(x, 0.55f) else rgba(tint.r, tint.g, tint.b, 0.08f + 0.72f * x)
                for (h in v.indices) {
                    if (v[h].isNaN() || (!tint.signed && v[h] <= 0f)) continue
                    out[h] = colour(((v[h] - lo) / span).coerceIn(0f, 1f))
                }
                val zero = if (tint.signed) "" else "; hexes with none left clear (${v.count { !it.isNaN() && it <= 0f }} of ${v.count { !it.isNaN() }})"
                return MapLegend(title, "$title: ${f(lo)} to ${f(hi)} $unit (the map's 5th to 95th percentile)$zero",
                    ::colour, listOf(0f to f(lo), 1f to f(hi)),
                    "$unit; the map's 5th to 95th percentile" + if (tint.signed) "" else "; none: clear")
            }
            ViewLayers.Mode.SWING -> {
                val sw = layers.swing(key, tint.signed) ?: return waiting("the swing needs two months kept ($kept so far)")
                val (_, hi) = pct(sw) { true }
                val top = if (tint.signed) hi.coerceAtLeast(1e-6f) else 2f
                for (h in sw.indices) if (!sw[h].isNaN()) out[h] = heat((sw[h] / top).coerceIn(0f, 1f), 0.6f)
                val ramp = { x: Float -> heat(x, 0.6f) }
                return if (tint.signed) MapLegend(title, "$title: highest month less lowest over the $kept months kept; blue none, red ${f(top)} $unit or more",
                    ramp, listOf(0f to "0", 1f to "${f(top)}+"), "$unit, highest month less lowest ($kept months kept)")
                else MapLegend(title, "$title: (highest month - lowest) / mean over the $kept months kept; blue flat all year, green 1, red 2 or more",
                    ramp, listOf(0f to "flat", 0.5f to "1", 1f to "2+"), "(highest month - lowest) / mean ($kept months kept)")
            }
            ViewLayers.Mode.PEAK -> {
                val pk = layers.peak(key) ?: return waiting("the peak month needs two months kept ($kept so far)")
                val sw = layers.swing(key, tint.signed)
                val (_, top) = if (sw != null) pct(sw) { true } else 0f to 1f
                fun colour(m: Int, strength: Float): Color {
                    val c = Color.getHSBColor(m / 12f, 0.85f, 0.95f)
                    return Color(c.red, c.green, c.blue, (255 * (0.1f + 0.6f * strength.coerceIn(0f, 1f))).toInt())
                }
                for (h in pk.indices) {
                    if (pk[h] < 0) continue
                    val strength = sw?.get(h)?.takeIf { !it.isNaN() }?.let { if (tint.signed) it / top.coerceAtLeast(1e-6f) else it } ?: 1f
                    out[h] = colour(pk[h], strength)
                }
                val months = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
                return MapLegend(title, "$title: month of the highest monthly mean, Jan red, Mar yellow, May green, Jul cyan, Sep blue, Nov purple; faint where it hardly swings ($kept months kept)",
                    { x -> colour(min(11, (x * 12).toInt()), 1f) }, (0 until 12 step 2).map { (it + 0.5f) / 12f to months[it] },
                    "month of the highest monthly mean; faint where it hardly swings ($kept months kept)")
            }
            ViewLayers.Mode.YEAR_AGO -> {
                val r = layers.yearAgo(key, tint.signed) ?: return waiting("needs the same month twice (a year and a month of record)")
                fun colour(x: Float) = if (abs(x) < 0.05f) null
                    else if (x > 0) rgba(0.9f, 0.15f, 0.1f, 0.1f + 0.65f * x) else rgba(0.1f, 0.35f, 0.95f, 0.1f - 0.65f * x)
                for (h in r.indices) {
                    if (r[h].isNaN()) continue
                    out[h] = colour(if (tint.signed) (r[h] / 5f).coerceIn(-1f, 1f) else (ln(r[h]) / ln(4f)).coerceIn(-1f, 1f))
                }
                val month = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")[layers.lastMonth]
                val ramp = { x: Float -> colour(2f * x - 1f) }
                return if (tint.signed) MapLegend(title, "${tint.label}: $month against $month a year before; red warmer, blue colder, full at 5 $unit",
                    ramp, listOf(0f to "-5", 0.5f to "same", 1f to "+5"), "$unit, $month against $month a year before")
                else MapLegend(title, "$title: $month against $month a year before; red more, blue less, full at 4 times or a quarter",
                    ramp, listOf(0f to "¼×", 0.5f to "same", 1f to "4×"), "$month against $month a year before (log scale)")
            }
        }
    }

    private fun causeNote(tint: Measure) = if (tint.byCause) " (" + causeChoice.selectedItem + ")" else ""

    /** The measures' own fixed colour scales; null for a measure without one. */
    private fun fixedColour(tint: Measure, values: FloatArray, out: Array<Color?>): MapLegend? {
        // What the scale says, and the values it runs over: its ends, with any marked between them.
        val (legend, marks) = when (tint) {
            Measure.TEMPERATURE -> "blue -15 °C to red 35 °C" to floatArrayOf(-15f, 0f, 15f, 35f)
            Measure.SNOW -> "white from 5 mm, full at 300 mm" to floatArrayOf(0f, 150f, 300f)
            Measure.RAIN -> "blue from 2 mm, full at 80 mm" to floatArrayOf(0f, 40f, 80f)
            Measure.GREEN -> "yellow dry to green" to floatArrayOf(0f, 0.5f, 1f)
            Measure.FORAGE -> "full at 2 kg/m²" to floatArrayOf(0f, 1f, 2f)
            Measure.SOIL -> "brown dry to blue 150 mm" to floatArrayOf(0f, 75f, 150f)
            Measure.WATER -> "full where all the ground is within reach of water" to floatArrayOf(0f, 0.5f, 1f)
            Measure.INSECTS -> "full at 1500 kg/km²" to floatArrayOf(0f, 750f, 1500f)
            Measure.BITING -> "full at 400 kg/km²" to floatArrayOf(0f, 200f, 400f)
            Measure.POLLINATORS -> "full at 50 kg/km²" to floatArrayOf(0f, 25f, 50f)
            Measure.ORGANIC -> "full at 8 kg/m²" to floatArrayOf(0f, 4f, 8f)
            Measure.SMALL_GAME -> "log scale, 1 to 600 kg/km²" to floatArrayOf(0f, 1f, 10f, 100f, 600f)
            Measure.FOXES -> "full at 2 kg/km²" to floatArrayOf(0f, 1f, 2f)
            Measure.OWLS -> "full at 1 kg/km²" to floatArrayOf(0f, 0.5f, 1f)
            Measure.EAGLES -> "full where every range is held" to floatArrayOf(0f, 0.5f, 1f)
            Measure.CARRION -> "full at 20 kg/km²" to floatArrayOf(0f, 10f, 20f)
            Measure.RESIDENTS -> "full at 20 head/km²" to floatArrayOf(0f, 10f, 20f)
            else -> return null
        }
        for (h in values.indices) { val v = values[h]; if (!v.isNaN()) out[h] = fixedColourOf(tint, v) }
        // Along the legend's ramp: the value at x (0..1), and where a value falls.
        val lo = marks.first(); val hi = marks.last(); val log = tint == Measure.SMALL_GAME
        fun at(x: Float) = if (log) (1f + hi).pow(x) - 1f else lo + x * (hi - lo)
        fun where(v: Float) = if (log) ln(1f + v) / ln(1f + hi) else (v - lo) / (hi - lo)
        return MapLegend(tint.label, "${tint.label}: $legend (the viewer's fixed scale; \"scaled to the map\" stretches it)",
            { x -> fixedColourOf(tint, at(x)) }, marks.map { where(it) to MapLegend.num(it) + if (it == hi && tint != Measure.TEMPERATURE) "+" else "" },
            "${tint.unit}; $legend")
    }

    /** A value's colour on [tint]'s fixed scale (see [fixedColour]); null for clear. */
    private fun fixedColourOf(tint: Measure, v: Float): Color? = when (tint) {
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
        else -> null
    }

    /** Fills the side panel with what is in hex [h]: its ground, weather, plants, residents and borders, and the groups standing in it. */
    private fun inspect(h: Int) = worldLock.whileStill { inspectNow(h) }

    private fun inspectNow(h: Int) {
        val sb = StringBuilder()
        val (cx, cy) = hex.centreKm(h)
        sb.appendLine("Hex %d at (%.0f, %.0f) km%s".format(h, cx, cy, if (hex.water[h]) " - water" else ""))
        for (p in hw.placesOf(h)) {
            sb.appendLine("%s (%.0f m, %.0f km²): %.1f °C, snow %.0f mm, soil water %.0f mm".format(
                if (hw.placeBand[p] == HexWorld.HIGH) "high ground" else if (hw.placesOf(h).size > 1) "low ground" else "the hex", hw.placeElevationM[p], land.areaKm2[p], weather.temperature[p], weather.snowpack[p], land.hydro.soil[p]))
            sb.appendLine("   herbs %.2f shrubs %.2f trees %.1f kg/m², green %.0f%%, rugged %.2f, refuge %.2f, lookout %.0f km".format(
                land.plants.herb[p], land.plants.shrub[p], land.plants.tree[p], 100 * land.plants.green[p],
                land.ruggedness[p], land.bestDefence[p], land.bestViewKm[p]))
            sb.appendLine("   rain %.0f mm this week, wind %.1f m/s; drinking water within reach of %.0f%% (walk %.1f km)".format(
                weather.precipitation[p], hypot(weather.windEast(p), weather.windSouth(p)), 100 * land.drink.water[p], land.drink.waterKm[p]))
            val sl = fauna.small
            sb.appendLine("   insects %.0f, biting %.0f, rodents %.0f, reptiles %.0f, pollinators %.0f kg/km²; litter %.2f, organic %.1f kg/m²".format(
                sl.insects[p], sl.biting[p], sl.rodents[p], sl.herps[p], sl.pollinators[p], sl.litter[p], sl.organicMatter[p]))
            val res = fauna.kinds.indices.filter { fauna.adults[it][p] + fauna.young[it][p] > 0.01f }
                .joinToString(", ") { "%s %.1f".format(fauna.kinds[it].name, fauna.adults[it][p] + fauna.young[it][p]) }
            if (res.isNotEmpty()) sb.appendLine("   residents per km²: $res")
        }
        // Everything living here, in head counts for the whole hex.
        var totalArea = 0f
        val residents = DoubleArray(fauna.kinds.size)
        var food = 0.0; var small = 0.0; var carrion = 0.0
        for (p in hw.placesOf(h)) {
            val a = land.areaKm2[p]; totalArea += a
            for (k in fauna.kinds.indices) residents[k] += ((fauna.adults[k][p] + fauna.young[k][p]) * a).toDouble()
            food += ((land.plants.herb[p] + land.plants.shrub[p]) * a).toDouble() // kg/m² × km² = 1e6 kg
            small += ((fauna.small.rodents[p] + fauna.small.herps[p]) * a).toDouble(); carrion += (fauna.small.carrion[p] * 1e6f * a).toDouble()
        }
        val gs = groups.groups.filter { it.alive && hw.placeHex[it.place] == h }
        sb.appendLine("country: " + describe(h))
        sb.appendLine("in the hex (%.0f km²):".format(totalArea))
        sb.appendLine("   residents: " + fauna.kinds.indices.filter { residents[it] >= 0.5 }
            .joinToString(", ") { "%s %.0f".format(fauna.kinds[it].name, residents[it]) }.ifEmpty { "none" })
        sb.appendLine("   packs and bands: " + gs.groupBy { it.species }.entries
            .joinToString(", ") { (s, l) -> "%s %d in %d".format(s.name, l.sumOf { it.size }, l.size) }.ifEmpty { "none" })
        sb.appendLine("   forage (grass and browse) %.0f t, rodents and reptiles %.1f t, carrion %.1f t".format(food * 1000, small / 1000, carrion / 1000))
        sb.appendLine("river: biggest %.0f m³/s, flow now %.0f m³/s".format(land.hydro.meanFlow[h], land.hydro.flow[h]))
        // The tinted measure here: this week, and month by month over the last year.
        val tint = Measure.entries[tintChoice.selectedIndex]
        if (tint != Measure.NONE) {
            val key = layers.key(tint, causeChoice.selectedIndex)
            layers.now[key]?.get(h)?.takeIf { !it.isNaN() }?.let { v ->
                sb.appendLine("%s%s: %.3g %s".format(tint.label, causeNote(tint), v, tint.unit))
                val m = layers.byMonth(key, h)
                if (m.any { !it.isNaN() }) sb.appendLine("   by month: " + m.withIndex().filter { !it.value.isNaN() }
                    .joinToString(" ") { "%d:%.3g".format(it.index + 1, it.value) })
            }
            if (tint == Measure.HUNGRY_BY_PLENTY && h < layers.hunger.size) sb.appendLine(
                "   grazers and browsers going short %.2f a km² (head-equivalents); most they could eat within 2 hexes %.3f kg/m²".format(layers.hunger[h],
                    layers.plenty[h]))
            if (tint.byCause) sb.appendLine("   by cause: " + PlaceRecord.Cause.entries.joinToString(", ") { c ->
                "%s %.3g".format(c.label, layers.now[layers.key(tint, c.ordinal)]?.get(h) ?: 0f) })
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
        val rows = here.map { g ->
            "%s #%d: %d adults, %d young, fed %.0f%%, reserves %.0f d".format(g.species.name, g.id, g.adults, g.young, 100 * g.fed, g.fat) }
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
        sb.appendLine("stands on %s at %.0f m".format(if (hw.placeBand[g.place] == HexWorld.HIGH) "high ground" else "low ground", hw.placeElevationM[g.place]))
        sb.appendLine("this week it walked %d hexes: %s".format(max(0, g.path.size - 1), g.path.joinToString(" > ")))
        if (g.days.isNotEmpty()) sb.appendLine("entering them on days: " + g.days.joinToString(" ") { "%.1f".format(it) })
        g.band?.let { b ->
            sb.appendLine("larder %.0f kg (%.0f days); camp kept %d weeks".format(g.larder, g.larder / g.dailyNeedKg.coerceAtLeast(1e-3f), b.campWeeks))
            sb.appendLine("skills: " + Skill.entries.filter { b.knowsSkill[it] }
                .joinToString(", ") { "%s %.0f%%".format(it.name.lowercase().replace('_', ' '), 100 * b.skills[it]) })
            sb.appendLine("tools: " + Tool.entries.filter { b.knows[it] }
                .joinToString(", ") { "%s %.0f%%".format(it.name.lowercase(), 100 * b.kit[it]) })
            sb.appendLine("elders %d; children inbred %.3f; descent mostly lineage %d".format(b.elders, b.inbred, b.mainLineage()))
            if (b.kept.sum() >= 0.5f) sb.appendLine("keeps: " + b.kept.withIndex().filter { it.value >= 0.5f }.joinToString(", ") {
                "%.0f %s".format(it.value, if (Catalogue.all[it.index].name == "wolf") "dogs" else "tame " + Catalogue.all[it.index].name) })
            sb.appendLine("customs: " + Tradition.entries.filter { b.holds(it) }.joinToString(", ") { it.name.lowercase().replace('_', ' ') }
                .ifEmpty { "none" })
            sb.appendLine("goods: " + Material.entries.joinToString(", ") { "%s %.0f kg".format(it.name.lowercase(), b.goods[it]) })
        }
        if (g.band == null) sb.appendLine("holds ground at hex %s; meat taken from other hunters this week %.0f kg".format(
            if (!g.home.none) g.home.toString() else "none", g.meatMet))
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
        for ((b, s) in names.take(3)) parts += "%s %s".format(share(s), b.label)
        // Plants over the land, area-weighted over its low and high ground.
        var a = 0.0; var herb = 0.0; var shrub = 0.0; var tree = 0.0; var green = 0.0; var fruit = 0.0; var snow = 0.0; var burnt = 0.0
        for (p in hw.placesOf(h)) {
            val w = land.areaKm2[p].toDouble(); a += w
            herb += land.plants.herb[p] * w; shrub += land.plants.shrub[p] * w; tree += land.plants.tree[p] * w; green += land.plants.green[p] * w
            fruit += fauna.food.fruit[p] * w; snow += weather.snowpack[p] * w; burnt += land.fire.burnt[p] * w
        }
        if (a <= 0.0) return parts.joinToString("; ").ifEmpty { "water" }
        herb /= a; shrub /= a; tree /= a; green /= a; fruit /= a; snow /= a; burnt /= a
        val grass = when { herb < 0.05 -> "bare or nearly bare ground"; herb < 0.2 -> "thin grass"; herb < 0.5 -> "fair grass"; else -> "thick grass" }
        val scrub = when { shrub < 0.2 -> null; shrub < 1.0 -> "scattered scrub"; else -> "dense scrub" }
        val wood = when { tree < 1.0 -> null; tree < 8.0 -> "open woodland"; else -> "closed forest" }
        val state = when { snow > 50 -> "under snow"; green > 0.6 -> "green and growing"; green > 0.3 -> "partly green"; else -> "dry or dormant" }
        val plants = listOfNotNull(grass, scrub, wood).joinToString(", ") + " ($state)"
        val extras = listOfNotNull(
            if (fruit > 0.0005) "fruit ripe" else null,
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
        /** The colour of a landmark on the map (passes are drawn as triangles). */
        private fun featureColour(k: HexGeography.Kind) = when (k) {
            HexGeography.Kind.RIVER, HexGeography.Kind.LAKE, HexGeography.Kind.BAY, HexGeography.Kind.STRAIT -> Color(0.1f, 0.25f, 0.7f)
            HexGeography.Kind.VALLEY, HexGeography.Kind.FLOODPLAIN -> Color(0.15f, 0.4f, 0.4f)
            HexGeography.Kind.FOREST, HexGeography.Kind.DEEP_FOREST, HexGeography.Kind.WOODLAND -> Color(0.1f, 0.35f, 0.1f)
            HexGeography.Kind.MOUNTAINS, HexGeography.Kind.HILLS -> Color(0.35f, 0.25f, 0.2f)
            HexGeography.Kind.STEPPE, HexGeography.Kind.DESERT -> Color(0.45f, 0.35f, 0.05f)
            HexGeography.Kind.MARSH -> Color(0.2f, 0.4f, 0.3f)
            HexGeography.Kind.PENINSULA, HexGeography.Kind.ISTHMUS, HexGeography.Kind.ISLAND -> Color(0.3f, 0.3f, 0.3f)
        }
        private fun landmarkColour(k: HexRoutes.Kind) = when (k) {
            HexRoutes.Kind.JUNCTION -> Color(0.95f, 0.85f, 0.4f); HexRoutes.Kind.FORD -> Color(0.3f, 0.6f, 1f)
            HexRoutes.Kind.PASS -> Color(0.85f, 0.85f, 0.85f); HexRoutes.Kind.WATER -> Color(0.2f, 0.9f, 0.9f)
            HexRoutes.Kind.PEAK -> Color(0.55f, 0.3f, 0.75f); HexRoutes.Kind.LAKE -> Color(0.1f, 0.25f, 0.8f)
            HexRoutes.Kind.SPRING -> Color(0.4f, 1f, 0.5f); HexRoutes.Kind.CONFLUENCE -> Color(1f, 0.5f, 0.8f)
            HexRoutes.Kind.MOUTH -> Color(1f, 0.55f, 0.1f)
            else -> Color(0.5f, 0.35f, 0.2f)
        }
    }
}
