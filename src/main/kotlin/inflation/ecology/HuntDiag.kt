package inflation.ecology

import kotlin.math.roundToInt

/** Diagnostic (huntSteps): counts each step of the hunt by prey kind, into the history ("hunt diag/KIND/STEP"). */
internal val HUNT_DIAG = inflation.Config.flag(inflation.Config.Key.HUNT_STEPS, false)

/** A step of the hunt that [diag] counts. */
internal enum class HuntStep(val label: String) {
    RESIDENT_KILLS("resident kills"), RESIDENT_BANDS_MET("resident bands met"), RESIDENT_BANDS_MET_UNSEEN("resident bands met unseen"),
    // (A beast's raids on kept herds, by the beast ([herdOdds], [raidHerd]): its weeks with herds on its ground, the herds it
    // would come on in them, the kills its odds would give it if it hunted nothing else, the kills it set out for, and those made.)
    HERD_WEEKS("weeks with herds on its ground"), HERDS_MET("herds met"), HERD_KILLS_ON_OFFER("herd kills on offer"),
    HERD_KILLS_SOUGHT("herd kills sought"), HERD_KILLS("herd kills"),
    // (And per herd it could hunt, summed: the herds, the band's guard, and in the week's first pass what a kill of it pays a
    // day of handling against the least the beast took up, [Offers.takeUp]'s cut-off; their means are these over the herds.)
    HERDS_ON_OFFER("herds on offer"), HERD_GUARD("herd guard"), HERD_PAY_OVER_CUTOFF("herd pay over cut-off"),
    // (The resident hunters' take at places where bands camp with herds, by the prey kind, kept and wild alike: heads held as
    // the week starts, summed over the weeks, and heads the residents took in them; taken over held is each one's weekly
    // share lost ([HexGroups.herdsForBeasts], [HexFauna.campTaken]).)
    KEPT_YOUNG_HELD("kept young held"), KEPT_YOUNG_TAKEN("kept young taken"), KEPT_GROWN_HELD("kept grown held"),
    KEPT_GROWN_TAKEN("kept grown taken"), WILD_YOUNG_AT_CAMPS("wild young at camps"), WILD_YOUNG_TAKEN_AT_CAMPS("wild young taken at camps"),
    WILD_GROWN_AT_CAMPS("wild grown at camps"), WILD_GROWN_TAKEN_AT_CAMPS("wild grown taken at camps");

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
