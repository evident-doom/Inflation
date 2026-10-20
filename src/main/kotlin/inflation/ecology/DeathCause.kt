package inflation.ecology

/**
 * What a group's deaths are put down to in the week's tallies ([WeekStats.deaths], keyed "kind:cause"). The reports
 * print these labels and parse them back, and a band reacts to [TAKEN_BY], so each is written here once.
 */
enum class DeathCause(val label: String, val shown: PlaceRecord.Cause) {
    OTHER("other", PlaceRecord.Cause.OTHER), AGE("age", PlaceRecord.Cause.OTHER), HUNGER("hunger", PlaceRecord.Cause.HUNGER), COLD("cold", PlaceRecord.Cause.COLD), HEAT("heat", PlaceRecord.Cause.OTHER),
    EXPOSURE("exposure", PlaceRecord.Cause.COLD), THIRST("thirst", PlaceRecord.Cause.THIRST), DISEASE("disease", PlaceRecord.Cause.ILLNESS),
    OUTBREAK("outbreak", PlaceRecord.Cause.ILLNESS), FIRE("fire", PlaceRecord.Cause.OTHER), FLOOD("flood", PlaceRecord.Cause.OTHER),
    SNOW("snow", PlaceRecord.Cause.COLD), CROCODILES("crocodiles", PlaceRecord.Cause.KILLED), EAGLES("eagles", PlaceRecord.Cause.KILLED),
    SHORT_OF_PROTEIN("short of protein", PlaceRecord.Cause.OTHER), HOMELESS("homeless", PlaceRecord.Cause.OTHER),
    CAMP_FOULING("camp fouling", PlaceRecord.Cause.ILLNESS), SNAKES("snakes and scorpions", PlaceRecord.Cause.OTHER),
    INBREEDING("inbreeding", PlaceRecord.Cause.OTHER), WOUNDS("wounds", PlaceRecord.Cause.OTHER), OLD_AGE("old age", PlaceRecord.Cause.OTHER),
    ORPHANED("orphaned", PlaceRecord.Cause.HUNGER), DROWNED_CROSSING("drowned crossing", PlaceRecord.Cause.OTHER),
    RAIDS("raids", PlaceRecord.Cause.KILLED), PREY_FOUGHT_BACK("prey fought back", PlaceRecord.Cause.KILLED), RIVALS("rivals", PlaceRecord.Cause.KILLED),
    YOUNG_KILLED_BY_RIVALS("young killed by rivals", PlaceRecord.Cause.KILLED),
    RESIDENT_HUNTERS("resident hunters", PlaceRecord.Cause.KILLED);

    override fun toString() = label

    companion object {
        /** Prefix of a death to a beast of prey that stalked a band ("taken by lion"). */
        const val TAKEN_BY = "taken by"
        /** Prefix of a death in a fight between kinds ("killed by wolf"). */
        const val KILLED_BY = "killed by"
        fun takenBy(stalker: String) = "$TAKEN_BY $stalker"
        fun killedBy(killer: String) = "$KILLED_BY $killer"
        fun youngKilledBy(killer: String) = "young $KILLED_BY $killer"
        /** The cause labelled [label], if it is one of these (not one of the "taken by" or "killed by" kinds). */
        fun byLabel(label: String): DeathCause? = BY_LABEL[label]
        private val BY_LABEL by lazy { entries.associateBy { it.label } }
    }
}
