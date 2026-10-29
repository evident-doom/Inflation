package inflation.ecology

import java.util.concurrent.ConcurrentHashMap

/**
 * What a death is put down to: one of the [DeathCause]s, an [Illness], or a beast of another kind ([ByBeast]). The
 * tallies key it by its [label] ("kind:label", see [Tally]), and the place record files it under [shown].
 */
sealed interface DeathReason {
    val label: String
    val shown: PlaceRecord.Cause
}

/**
 * What a group's deaths are put down to in the week's tallies ([WeekStats.deaths], keyed "kind:cause"). The reports
 * print these labels and parse them back, so each is written here once.
 */
enum class DeathCause(override val label: String, override val shown: PlaceRecord.Cause) : DeathReason {
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
}

/**
 * The deaths a band's own dials act on, for judging its choices apart by cause (training, [DeathReason.deathClass]): [KIT] those of
 * the cold and the camp (what it wears, its fire and shelter, a clean camp, tended wounds), [FOOD] those of going without
 * (hunger, thirst, protein, the orphaned). Every other cause (beasts, snakes, sickness, raids, old age...) is of neither.
 */
enum class DeathClass { KIT, FOOD }

/** The index in [HexGroup.lost] of the deaths of all causes (those of a [DeathClass] are at its ordinal). */
const val LOST_ALL = 2

/** The class of deaths this is, if it is one a band's dials act on (else null). */
fun DeathReason.deathClass(): DeathClass? = when (this) {
    DeathCause.COLD, DeathCause.EXPOSURE, DeathCause.HEAT, DeathCause.CAMP_FOULING, DeathCause.WOUNDS -> DeathClass.KIT
    DeathCause.HUNGER, DeathCause.THIRST, DeathCause.SHORT_OF_PROTEIN, DeathCause.ORPHANED -> DeathClass.FOOD
    else -> null
}

/**
 * A death to a beast of another kind: [how] it came, [by] which kind ("taken by lion", "killed by wolf"). One instance
 * for each pair ([of]), so that it keys a map by identity, as an enum does.
 */
class ByBeast private constructor(val how: How, val by: Species) : DeathReason {
    enum class How(val words: String) {
        /** A beast of prey that stalked a band. */
        TAKEN_BY("taken by"),
        /** A fight between kinds, and the young killed in one. */
        KILLED_BY("killed by"), YOUNG_KILLED_BY("young killed by"),
    }
    override val label = "${how.words} ${by.name}"
    override val shown get() = PlaceRecord.Cause.KILLED
    override fun toString() = label

    companion object {
        private val made = ConcurrentHashMap<Int, ByBeast>()
        fun of(how: How, by: Species): ByBeast = made.computeIfAbsent(how.ordinal * 65536 + by.index) { ByBeast(how, by) }
    }
}
