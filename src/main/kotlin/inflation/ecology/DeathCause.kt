package inflation.ecology

/**
 * What a group's deaths are put down to in the week's tallies ([WeekStats.deaths], keyed "kind:cause"). The reports
 * print these labels and parse them back, and a band reacts to [TAKEN_BY], so each is written here once.
 */
enum class DeathCause(val label: String) {
    OTHER("other"), HUNGER("hunger"), COLD("cold"), EXPOSURE("exposure"), THIRST("thirst"), DISEASE("disease"), OUTBREAK("outbreak"),
    FIRE("fire"), FLOOD("flood"), SNOW("snow"), CROCODILES("crocodiles"), EAGLES("eagles"), SHORT_OF_PROTEIN("short of protein"),
    HOMELESS("homeless"), CAMP_FOULING("camp fouling"), SNAKES("snakes and scorpions"), INBREEDING("inbreeding"), WOUNDS("wounds"),
    OLD_AGE("old age"), ORPHANED("orphaned"), DROWNED_CROSSING("drowned crossing"), RAIDS("raids"), PREY_FOUGHT_BACK("prey fought back"),
    RIVALS("rivals"), YOUNG_KILLED_BY_RIVALS("young killed by rivals");

    override fun toString() = label

    companion object {
        /** Prefix of a death to a beast of prey that stalked a band ("taken by lion"). */
        const val TAKEN_BY = "taken by"
        /** Prefix of a death in a fight between kinds ("killed by wolf"). */
        const val KILLED_BY = "killed by"
        fun takenBy(stalker: String) = "$TAKEN_BY $stalker"
        fun killedBy(killer: String) = "$KILLED_BY $killer"
        fun youngKilledBy(killer: String) = "young $KILLED_BY $killer"
    }
}
