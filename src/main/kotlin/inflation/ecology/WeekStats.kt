package inflation.ecology

import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.DoubleAdder

/**
 * The groups' tallies for the week, by free-form key: what the reports and checks read back.
 * (Concurrent and sorted: hunting runs region by region on all cores.)
 */
class WeekStats {
    /** Kills this week by prey kind. */
    val kills = ConcurrentSkipListMap<String, Int>()
    /** Meetings this week by kind of meeting. */
    val meetings = ConcurrentSkipListMap<String, Int>()
    /** Deaths this week by "kind:cause". */
    val deaths = ConcurrentSkipListMap<String, Int>()
    /** This week's hunting by kind of hunter: chases, kills from herds and of residents, herds that saw it first. */
    val hunting = ConcurrentSkipListMap<String, Int>()
    /** For the check: people's children expected to die of each cause, by the week's odds, since last read (a report ledger, not saved). */
    @Transient val childDeaths = ConcurrentHashMap<String, DoubleAdder>()
    /** Young born this week by kind. */
    val births = TreeMap<String, Int>()

    /** Starts a new week (the children's ledger runs on until read). */
    fun clear() { kills.clear(); meetings.clear(); deaths.clear(); births.clear(); hunting.clear() }

    companion object {
        /** [hunting] keys the reports read back ("people:children died"). */
        const val CHILDREN_DIED = "children died"
        const val MEAT_EATEN = "meat eaten, kg"
        const val PLANTS_EATEN = "plants eaten, kg"
        const val GRAIN_REAPED = "grain reaped, kg"
    }
}

/**
 * The tally keys the reports read back by name, built here by writer and reader alike, so that renaming
 * one changes both and cannot silently zero a report. Keys only ever listed whole stay free text.
 */
object Tally {
    /** A [WeekStats.hunting] tally of kind [s]: [what] (as [CHASES]). */
    fun of(s: Species, what: String) = "${s.name}:$what"
    const val CHASES = "chases"
    const val SAW_FIRST = "herds that saw it first"
    const val KILLS_FROM_HERDS = "kills from herds"
    const val KILLS_OF_RESIDENTS = "kills of residents"
    const val RANGED = "ranged over places"
    /** What a herd kind ate of [food] (grass, browse, fruit and roots, straw), kg. */
    fun ate(s: Species, food: String) = of(s, "ate $food, kg")
    /** What a hunting kind's diet asked for ([part] "asked") and got of each part (meat, small prey, carrion, plants), kg. */
    fun diet(s: Species, part: String) = of(s, "diet $part, kg")
    /** Herd deaths by size class ([HexGroups.sizeClass]) and [what] befell them. */
    fun herdSize(sizeClass: Int, what: String) = "$HERD_SIZE$sizeClass:$what"
    const val HERD_SIZE = "herd size "
    /** Bands' tallies by the kind of country they are in. */
    fun bandsIn(country: String, what: String) = "$BANDS_IN$country:$what"
    const val BANDS_IN = "bands in "
    /** Bands' tallies by how they are clad ([CLAD] or [POORLY_CLAD]). */
    fun bandsClad(clad: String, what: String) = "bands $clad:$what"
    const val CLAD = "clad"
    const val POORLY_CLAD = "poorly clad"
    /** How bands spent their hands' days, tenths of a day. */
    fun bandTime(what: String) = "band time:$what"
    /** Deaths of [cause], within a band tally. */
    fun diedOf(cause: String) = "$DIED_OF$cause"
    const val DIED_OF = "died of "
}
