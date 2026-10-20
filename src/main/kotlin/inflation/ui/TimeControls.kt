package inflation.ui

import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSlider

/**
 * The overview's time controls, Run/Pause, Step and the speed in weeks a second, shared with the follow window. Every
 * panel made by [panel] shows the same state and sets it, so using the controls in one window moves them in the other.
 * Used on the Swing thread; the simulation's thread reads [running] and [stepsPerSecond].
 */
class TimeControls(
    speed: Int,
    /** The controls were used: a week waiting for the player in the follow window goes on with the picks shown. */
    private val takeOver: () -> Unit,
    /** Lets one more week run (on the simulation's thread). */
    private val step: () -> Unit,
) {
    @Volatile var running = false
        private set
    @Volatile var stepsPerSecond = speed
        private set
    private val runButtons = ArrayList<JButton>()
    private val sliders = ArrayList<JSlider>()
    private val listeners = ArrayList<() -> Unit>()

    /** [f] is called (on the Swing thread) after running or the speed changes, from either window. */
    fun onChange(f: () -> Unit) { listeners += f }

    /** A set of the controls for a window: it follows the others and sets them. */
    fun panel(): JPanel {
        val run = JButton(runText())
        run.addActionListener { running = !running; showState(); takeOver(); changed() }
        val stepOnce = JButton("Step")
        stepOnce.addActionListener { takeOver(); step() }
        val speedSlider = JSlider(1, 10, stepsPerSecond)
        speedSlider.addChangeListener { if (speedSlider.value != stepsPerSecond) { stepsPerSecond = speedSlider.value; showState(); changed() } }
        runButtons += run; sliders += speedSlider
        val p = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        p.add(run); p.add(stepOnce); p.add(JLabel("weeks/s")); p.add(speedSlider)
        return p
    }

    private fun runText() = if (running) "Pause" else "Run"

    /** Every panel shows the state now. */
    private fun showState() {
        for (b in runButtons) b.text = runText()
        for (s in sliders) if (s.value != stepsPerSecond) s.value = stepsPerSecond
    }

    private fun changed() { for (l in listeners) l() }
}
