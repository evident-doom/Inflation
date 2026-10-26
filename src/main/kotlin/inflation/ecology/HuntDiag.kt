package inflation.ecology

import kotlin.math.roundToInt

/** Diagnostic (huntSteps): counts each step of the hunt by prey kind, into the history ("hunt diag/KIND/STEP"). */
internal val HUNT_DIAG = inflation.Config.flag(inflation.Config.Key.HUNT_STEPS, false)

/** A step of the hunt that [diag] counts. */
internal enum class HuntStep(val label: String) {
    RESIDENT_KILLS("resident kills"), RESIDENT_BANDS_MET("resident bands met"), RESIDENT_BANDS_MET_UNSEEN("resident bands met unseen");

    companion object {
        private const val PREFIX = "diag:"
        /** The hunting-ledger key of step [what] for prey [s]. */
        @OptIn(RawKey::class) fun key(s: Species, what: HuntStep) = TallyKey.raw("$PREFIX${s.name}:${what.label}")
        /** The history series of a hunting-ledger [key], or null if it is no hunt step's. */
        fun series(key: TallyKey) = key.text.let { k -> if (k.startsWith(PREFIX)) "hunt diag/" + k.removePrefix(PREFIX).replace(':', '/') else null }
    }
}

/** Adds [n] to the hunt-step count [what] for prey [s] (kept in hundredths, see [HexHistory]). */
internal fun GroupWorld.diag(s: Species, what: HuntStep, n: Float) {
    if (HUNT_DIAG && n > 0f) stats.hunting.merge(HuntStep.key(s, what), (n * 100f).roundToInt(), Int::plus)
}
