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
    WILD_GROWN_AT_CAMPS("wild grown at camps"), WILD_GROWN_TAKEN_AT_CAMPS("wild grown taken at camps"),
    // (A beast pack's foraging week, by the hunter ([forage]): its weeks, the days it could work, the days its foods took
    // and of them the days spent eating, its food got over what it wanted; and its weeks it ran out of days short of
    // what it aimed for, against those it stopped because it was fed.)
    PACK_WEEKS("pack weeks"), PACK_WORK_DAYS("pack work days"), PACK_BUSY_DAYS("pack busy days"), PACK_EATING_DAYS("pack eating days"),
    PACK_FED("pack fed of want"), PACK_WEEKS_OUT_OF_TIME("pack weeks out of time"), PACK_WEEKS_FULL("pack weeks full"),
    // (And as it ate the week ([eatTheWeek]): its weeks with no kill of its own, those it ate under [WELL_FED] of its want
    // with and without a kill, the kg it ate and the kg of its kills it left past what it could gorge.)
    PACK_WEEKS_NO_KILL("pack weeks no kill"), PACK_WEEKS_HUNGRY_NO_KILL("pack weeks hungry no kill"),
    PACK_WEEKS_HUNGRY_WITH_KILL("pack weeks hungry with kill"), PACK_KG_EATEN("pack kg eaten"), PACK_KG_LEFT("pack kg left"),
    // (And how it planned the week: the passes, the days they gave it to search ([forage]'s share f of its work days, summed
    // over the passes), and the kills of big game it could expect in them; the same over its weeks hungry with no kill,
    // with the work days it had in them.)
    PACK_PASSES("pack passes"), PACK_SEARCH_DAYS("pack search days"), PACK_KILLS_EXPECTED("pack kills expected"),
    HUNGRY_NO_KILL_PASSES("hungry no kill passes"), HUNGRY_NO_KILL_SEARCH_DAYS("hungry no kill search days"),
    HUNGRY_NO_KILL_KILLS_EXPECTED("hungry no kill kills expected"), HUNGRY_NO_KILL_WORK_DAYS("hungry no kill work days"),
    // (And its gorging ([eatTheWeek]): its fat as a share of the most it holds, and its week's want over what its gut holds,
    // every week; in its weeks hungry with no kill, its fat share then; and in its weeks with a kill's meat left past its
    // want, that meat, the room its gut had beyond its want, the room its reserves had (kg it would eat to fill them),
    // those weeks it was already as fat as it gets, and the kg it gorged.)
    PACK_FAT_SHARE("pack fat share"), PACK_WANT_OVER_GUT("pack want over gut"), HUNGRY_NO_KILL_FAT_SHARE("hungry no kill fat share"),
    GORGE_WEEKS("gorge weeks"), GORGE_SPARE_KG("gorge spare kg"), GORGE_GUT_ROOM_KG("gorge gut room kg"), GORGE_FAT_ROOM_KG("gorge fat room kg"),
    GORGE_WEEKS_FAT_FULL("gorge weeks fat full"), GORGE_KG("gorge kg"),
    // (And what stronger scavengers took of its kills by scent ([scavengedAtKill]): kills small
    // and large, those found of each (expected), those the scavengers won, the kills' kg and the kg it lost, the days the
    // kills lie and the finds a day, summed over kills; the finds a day, kills won and kg lost also by the scavenger's kind.)
    SCENT_KILLS_SMALL("scent kills small"), SCENT_KILLS_LARGE("scent kills large"), SCENT_FOUND_SMALL("scent found small"),
    SCENT_FOUND_LARGE("scent found large"), SCENT_WON("scent won"), SCENT_KG("scent kg"), SCENT_KG_LOST("scent kg lost"),
    SCENT_LIE_DAYS("scent lie days"), SCENT_FINDS_A_DAY("scent finds a day"),
    // (And what it ate, by what it was ([forage]): its kills of big game and their kg by the prey's kind, and the kg of small
    // game and carrion it got.)
    PACK_KILLS("pack kills"), PACK_KILL_KG("pack kill kg"), PACK_SMALL_GAME_KG("pack small game kg"), PACK_CARRION_KG("pack carrion kg");

    companion object {
        private const val PREFIX = "diag:"
        /** The hunting-ledger key of step [what] for prey [s]. */
        @OptIn(RawKey::class) fun key(s: Species, what: HuntStep) = TallyKey.raw("$PREFIX${s.name}:${what.label}")
        /** The same for step [what] of prey [s] that kind [by] made: "STEP by BY". */
        @OptIn(RawKey::class) fun key(s: Species, what: HuntStep, by: Species) = TallyKey.raw("$PREFIX${s.name}:${what.label} by ${by.name}")
        /** The history series of a hunting-ledger [key], or null if it is no hunt step's. */
        fun series(key: TallyKey) = key.text.let { k -> if (k.startsWith(PREFIX)) "hunt diag/" + k.removePrefix(PREFIX).replace(':', '/') else null }
    }
}

/** Adds [n] to the hunt-step count [what] for prey [s] (kept in hundredths, see [HexHistory]). */
internal fun GroupWorld.diag(s: Species, what: HuntStep, n: Float) {
    if (HUNT_DIAG && n > 0f) stats.tally(HuntStep.key(s, what), (n * 100f).roundToInt())
}

/** Adds [n] to the hunt-step count [what] for [s] that kind [by] made. */
internal fun GroupWorld.diag(s: Species, what: HuntStep, by: Species, n: Float) {
    if (HUNT_DIAG && n > 0f) stats.tally(HuntStep.key(s, what, by), (n * 100f).roundToInt())
}
