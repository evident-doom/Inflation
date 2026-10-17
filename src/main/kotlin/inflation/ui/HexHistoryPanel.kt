package inflation.ui

import inflation.Args
import inflation.ecology.HexHistory
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.io.File
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextField
import javax.swing.JTree
import javax.swing.Timer
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Graphs of a world's history for finding imbalances: pick any series in the tree on the left (hold
 * Ctrl for several, or pick a group to show all of it), by week, month or year, on a straight or
 * logarithmic scale, as lines or stacked (death causes, say). Hover over the graph to read the
 * values. The filter box narrows the tree, e.g. to "wolf" or "steppe". It follows a live world, or
 * shows a history saved by the check.
 */
class HexHistoryPanel(private var history: HexHistory, live: Boolean) : JPanel(BorderLayout()) {
    private val root = DefaultMutableTreeNode("history")
    private val model = DefaultTreeModel(root)
    private val tree = JTree(model).apply {
        isRootVisible = false; showsRootHandles = true
        selectionModel.selectionMode = TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
    }
    private val period = JComboBox(HexHistory.Period.entries.map { it.label }.toTypedArray()).apply { selectedIndex = 2 }
    private val logScale = JCheckBox("log scale")
    private val stacked = JCheckBox("stacked")
    private val filter = JTextField(12)
    private val readout = JLabel(" ")
    private var known = 0
    private var hoverX = -1

    private val chart = object : JPanel() {
        init {
            preferredSize = Dimension(900, 560); background = Color.WHITE
            addMouseMotionListener(object : MouseMotionAdapter() { override fun mouseMoved(e: MouseEvent) { hoverX = e.x; repaint() } })
        }
        override fun paintComponent(g0: Graphics) { super.paintComponent(g0); draw(g0 as Graphics2D, width, height) }
    }

    init {
        val bar = JPanel(FlowLayout(FlowLayout.LEFT))
        bar.add(JLabel("by")); bar.add(period); bar.add(logScale); bar.add(stacked)
        bar.add(JLabel("  find")); bar.add(filter)
        val open = JButton("Open…"); open.addActionListener { openFile() }
        val save = JButton("Save…"); save.addActionListener { saveFile() }
        bar.add(open); bar.add(save)
        for (c in listOf(period, logScale, stacked)) (c as? JComboBox<*>)?.addActionListener { chart.repaint() }
            ?: (c as JCheckBox).addActionListener { chart.repaint() }
        filter.addActionListener { rebuildTree() }
        tree.addTreeSelectionListener { chart.repaint() }
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, JScrollPane(tree).apply { preferredSize = Dimension(280, 560) }, chart)
        add(bar, BorderLayout.NORTH); add(split, BorderLayout.CENTER); add(readout, BorderLayout.SOUTH)
        rebuildTree()
        if (live) Timer(1000) { if (history.keys().size != known) rebuildTree(); chart.repaint() }.start()
    }

    /** The tree of series names, grouped by their path, keeping what was selected. */
    private fun rebuildTree() {
        val selected = selectedKeys()
        root.removeAllChildren()
        val f = filter.text.trim().lowercase()
        val keys = history.keys()
        for (key in keys) {
            if (f.isNotEmpty() && !key.lowercase().contains(f)) continue
            var node = root
            for (part in key.split('/')) {
                node = node.children().toList().map { it as DefaultMutableTreeNode }.firstOrNull { it.userObject == part }
                    ?: DefaultMutableTreeNode(part).also { node.add(it) }
            }
        }
        known = keys.size
        model.reload()
        for (k in selected) pathTo(k)?.let { tree.addSelectionPath(it) }
    }

    private fun pathTo(key: String): TreePath? {
        var node = root
        val nodes = arrayListOf<Any>(root)
        for (part in key.split('/')) {
            node = node.children().toList().map { it as DefaultMutableTreeNode }.firstOrNull { it.userObject == part } ?: return null
            nodes += node
        }
        return TreePath(nodes.toTypedArray())
    }

    /** Series picked: leaves, and every leaf under a picked group. */
    private fun selectedKeys(): List<String> {
        val out = LinkedHashSet<String>()
        for (path in tree.selectionPaths ?: emptyArray()) {
            val node = path.lastPathComponent as DefaultMutableTreeNode
            for (leaf in node.depthFirstEnumeration().toList().map { it as DefaultMutableTreeNode }.filter { it.isLeaf }) {
                out += leaf.path.drop(1).joinToString("/") { (it as DefaultMutableTreeNode).userObject.toString() }
            }
        }
        val all = history.keys().toSet()
        return out.filter { it in all }
    }

    private var pictureKeys: List<String>? = null

    /** For pictures: draw these series (a key ending in / takes all under it), stacked or not. */
    fun selectForPicture(keys: List<String>, stackedBands: Boolean) {
        val all = history.keys()
        pictureKeys = keys.flatMap { k -> if (k.endsWith("/")) all.filter { it.startsWith(k) } else listOf(k) }
        stacked.isSelected = stackedBands
    }

    /** Draws the graph into [g], [w] by [h]. */
    fun paintChart(g: Graphics2D, w: Int, h: Int) = draw(g, w, h)

    private fun draw(g: Graphics2D, w: Int, h: Int) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val keys = (pictureKeys ?: selectedKeys()).take(40)
        if (keys.isEmpty()) { g.color = Color.GRAY; g.drawString("Pick series on the left (Ctrl for several, or a whole group).", 40, 40); return }
        val per = HexHistory.Period.entries[period.selectedIndex]
        val data = keys.map { history.values(it, per) }
        val n = data.maxOf { it.first.size }
        if (n == 0) return
        val xs = data.first { it.first.size == n }.first
        val stack = stacked.isSelected
        // Values to draw, stacked if asked.
        val ys = Array(keys.size) { FloatArray(n) }
        for ((i, d) in data.withIndex()) for (j in 0 until n) {
            val v = d.second.getOrElse(j) { Float.NaN }.let { if (it.isNaN()) 0f else it }
            ys[i][j] = if (stack && i > 0) ys[i - 1][j] + v else v
        }
        val log = logScale.isSelected
        fun tr(v: Float) = if (log) log10(max(v, 1e-3f)) else v
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (y in ys) for (v in y) { lo = min(lo, tr(v)); hi = max(hi, tr(v)) }
        if (!log) lo = min(0f, lo)
        if (hi - lo < 1e-6f) hi = lo + 1f
        val left = 70; val right = w - 200; val top = 20; val bottom = h - 40
        val x0 = xs.first(); val x1 = max(xs.last(), x0 + 1e-3f)
        fun sx(x: Float) = left + ((x - x0) / (x1 - x0) * (right - left)).toInt()
        fun sy(v: Float) = bottom - ((tr(v) - lo) / (hi - lo) * (bottom - top)).toInt()
        // Axes and ticks.
        g.color = Color(0xdddddd)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
        for (t in ticks(lo, hi)) {
            val y = bottom - ((t - lo) / (hi - lo) * (bottom - top)).toInt()
            g.color = Color(0xeeeeee); g.drawLine(left, y, right, y)
            g.color = Color.DARK_GRAY; g.drawString(label(if (log) 10f.pow(t) else t), 4, y + 4)
        }
        for (t in ticks(x0, x1)) {
            val x = sx(t)
            g.color = Color(0xeeeeee); g.drawLine(x, top, x, bottom)
            g.color = Color.DARK_GRAY; g.drawString("year " + label(t + 1), x - 15, bottom + 15)
        }
        g.color = Color.GRAY; g.drawRect(left, top, right - left, bottom - top)
        // The lines (or stacked bands), with a legend.
        g.stroke = BasicStroke(1.6f)
        for (i in keys.indices.reversed()) {
            val c = colour(i)
            if (stack) {
                val px = IntArray(2 * n); val py = IntArray(2 * n)
                for (j in 0 until n) { px[j] = sx(xs[j]); py[j] = sy(ys[i][j]) }
                for (j in 0 until n) { px[n + j] = sx(xs[n - 1 - j]); py[n + j] = if (i > 0) sy(ys[i - 1][n - 1 - j]) else sy(if (log) 1e-3f else 0f) }
                g.color = Color(c.red, c.green, c.blue, 170); g.fillPolygon(px, py, 2 * n)
            } else {
                g.color = c
                var px = -1; var py = -1
                for (j in 0 until n) {
                    val v = data[i].second.getOrElse(j) { Float.NaN }
                    if (v.isNaN()) { px = -1; continue }
                    val x = sx(xs[j]); val y = sy(v)
                    if (px >= 0) g.drawLine(px, py, x, y)
                    px = x; py = y
                }
            }
        }
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
        for ((i, k) in keys.withIndex()) {
            g.color = colour(i); g.fillRect(right + 10, top + 4 + i * 14, 10, 10)
            g.color = Color.BLACK; g.drawString(k.takeLast(30), right + 24, top + 13 + i * 14)
        }
        // The values where the mouse is.
        if (hoverX in left..right) {
            val xv = x0 + (hoverX - left).toFloat() / (right - left) * (x1 - x0)
            val j = xs.indices.minBy { abs(xs[it] - xv) }
            g.color = Color(0, 0, 0, 80); g.drawLine(sx(xs[j]), top, sx(xs[j]), bottom)
            readout.text = "year %.2f: ".format(xs[j] + 1) +
                keys.indices.joinToString("   ") { i -> "${keys[i].substringAfterLast('/')} ${label(data[i].second.getOrElse(j) { Float.NaN })}" }
        }
    }

    private fun ticks(lo: Float, hi: Float): List<Float> {
        val span = hi - lo
        val step = 10f.pow(floor(log10(span / 5f)))
        val s = listOf(1f, 2f, 5f, 10f).map { it * step }.first { span / it <= 8f }
        val out = ArrayList<Float>()
        var t = (floor(lo / s) * s)
        while (t <= hi + 1e-6f) { if (t >= lo - 1e-6f) out += t; t += s }
        return out
    }

    private fun label(v: Float) = when {
        v.isNaN() -> "–"
        abs(v) >= 1e6f -> "%.2fM".format(v / 1e6f)
        abs(v) >= 1e4f -> "%.0fk".format(v / 1e3f)
        abs(v) >= 100f -> "%.0f".format(v)
        abs(v) >= 1f -> "%.1f".format(v)
        else -> "%.3f".format(v)
    }

    private fun colour(i: Int): Color = PALETTE[i % PALETTE.size]

    private fun openFile() {
        val ch = JFileChooser()
        if (ch.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) { history = HexHistory.readCsv(ch.selectedFile); rebuildTree(); chart.repaint() }
    }

    private fun saveFile() {
        val ch = JFileChooser().apply { selectedFile = File("history.csv") }
        if (ch.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) history.writeCsv(ch.selectedFile)
    }

    companion object {
        private val PALETTE = listOf(0x1f77b4, 0xd62728, 0x2ca02c, 0xff7f0e, 0x9467bd, 0x8c564b, 0xe377c2, 0x7f7f7f, 0xbcbd22, 0x17becf,
            0x393b79, 0xad494a, 0x637939, 0xe7ba52, 0x7b4173, 0x3182bd, 0x31a354, 0xfd8d3c, 0x756bb1, 0x636363).map { Color(it) }

        /** Opens a window with the graphs of [history]. */
        fun show(history: HexHistory, live: Boolean, title: String = "History") {
            val f = JFrame(title)
            f.contentPane.add(HexHistoryPanel(history, live))
            f.setSize(1250, 700)
            f.isVisible = true
        }
    }
}

/**
 * Opens saved histories: hex-history [file.csv]. With "--png OUT KEY..." it draws the graph of those
 * series by year into a picture instead, without a window (a key ending in / takes the whole group).
 */
fun main(args: Array<String>) {
    val a = Args(args, "hex-history [file.csv] | hex-history FILE.csv --png OUT KEY...")
    val png = a.file("--png")
    val words = a.rest()
    a.done()
    if (png != null && words.isNotEmpty()) {
        val h = HexHistory.readCsv(File(words[0]))
        val p = HexHistoryPanel(h, live = false)
        val keys = words.drop(1)
        p.selectForPicture(keys, stackedBands = keys.any { it.startsWith("deaths/") })
        p.setSize(1250, 650); p.doLayout()
        val img = java.awt.image.BufferedImage(1250, 650, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics(); g.color = Color.WHITE; g.fillRect(0, 0, 1250, 650)
        p.paintChart(g, 1250, 650); g.dispose()
        javax.imageio.ImageIO.write(img, "png", png)
        return
    }
    val file = words.firstOrNull()?.let { File(it) }
    val h = if (file != null && file.exists()) HexHistory.readCsv(file) else HexHistory()
    javax.swing.SwingUtilities.invokeLater {
        val f = JFrame("History" + (file?.let { " — ${it.name}" } ?: ""))
        f.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        f.contentPane.add(HexHistoryPanel(h, live = false))
        f.setSize(1250, 700); f.isVisible = true
    }
}
