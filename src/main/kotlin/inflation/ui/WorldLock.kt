package inflation.ui

import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.util.concurrent.Semaphore
import javax.swing.JComponent
import javax.swing.Timer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Keeps the windows from reading the world while the simulation changes it. The simulation's thread
 * holds it through each week and each command ([changing]); the Swing thread reads the world only
 * through [whileStill], which, while a week runs, does nothing now and tries again a moment later. A week
 * waiting on the player (see [GroupControlWindow]) lets it go while it waits ([waiting]): the world
 * stands still then, but for the planning that only reads it.
 */
class WorldLock {
    // (A semaphore, not a lock: the week waits on the player on a worker thread, not the simulation's own.)
    private val held = Semaphore(1)

    /** Runs [f], which changes the world, once no window is reading it. */
    fun <T> changing(f: () -> T): T {
        held.acquireUninterruptibly()
        try { return f() } finally { held.release() }
    }

    /** Within [changing]: lets the windows read the world while [f] waits on the player. */
    fun <T> waiting(f: () -> T): T {
        held.release()
        try { return f() } finally { held.acquireUninterruptibly() }
    }

    /** On the Swing thread: runs [f], which reads (or sets a choice in) the world, now if it stands still, else when the week is through. */
    fun whileStill(f: () -> Unit) {
        if (!ifStill(f)) Timer(RETRY_MS) { whileStill(f) }.apply { isRepeats = false }.start()
    }

    /** Runs [f] if the world stands still now; false, and [f] not run, if it is being changed. */
    fun ifStill(f: () -> Unit): Boolean {
        if (!held.tryAcquire()) return false
        try { f() } finally { held.release() }
        return true
    }

    /**
     * Paints a panel through a picture it keeps: drawn afresh by [paint] when the world stands still,
     * while a week runs the last picture again (and once more after it), so that panning or a
     * repaint mid-week neither waits for the week nor reads it half done.
     */
    inner class Painter(private val panel: JComponent) {
        private var last: BufferedImage? = null

        fun paint(g0: Graphics, paint: (Graphics2D) -> Unit) {
            // (The picture at the screen's own scale, so a scaled display shows it as sharp as drawn straight.)
            val scale = (g0 as Graphics2D).transform.scaleX.coerceAtLeast(1.0)
            val w = max(1, (panel.width * scale).roundToInt()); val h = max(1, (panel.height * scale).roundToInt())
            val fresh = ifStill {
                val img = last?.takeIf { it.width == w && it.height == h } ?: BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
                val g = img.createGraphics()
                try {
                    g.scale(scale, scale)
                    g.color = panel.background; g.fillRect(0, 0, panel.width, panel.height)
                    g.font = panel.font
                    paint(g)
                } finally { g.dispose() }
                last = img
            }
            last?.let { g0.drawImage(it, 0, 0, panel.width, panel.height, null) }
            if (!fresh) Timer(RETRY_MS) { panel.repaint() }.apply { isRepeats = false }.start()
        }
    }

    private companion object {
        const val RETRY_MS = 50
    }
}
