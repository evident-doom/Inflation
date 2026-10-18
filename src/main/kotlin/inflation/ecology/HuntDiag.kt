package inflation.ecology

import kotlin.math.roundToInt

/** Diagnostic (t.huntDiag): counts each step of the hunt, herds and residents alike, by prey kind, into the history ("hunt diag/KIND/STEP"). */
internal val HUNT_DIAG = inflation.Config.flag("t.huntDiag", false)

/** Adds [n] to the hunt-step count [what] for prey [s] (kept in hundredths, see [HexHistory]). */
internal fun HexGroups.diag(s: Species, what: String, n: Float) {
    if (HUNT_DIAG && n > 0f) stats.hunting.merge("diag:${s.name}:$what", (n * 100f).roundToInt(), Int::plus)
}
