package inflation.ecology

import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.DoubleAdder

/**
 * The week's tallies, by free-form key: what the reports and checks read back. The model never reads them. One
 * ledger for the whole step, kept by the land and cleared once as the week starts ([inflation.ecology.HexSim.step]),
 * so a tally written in any phase lasts until the reports read it.
 * (Concurrent and sorted: hunting runs region by region on all cores.)
 */
class WeekStats {
    /** Kills this week by prey kind. */
    val kills = ConcurrentSkipListMap<String, Int>()
    /** Meetings this week by kind of meeting. */
    val meetings = ConcurrentSkipListMap<String, Int>()
    /** Deaths this week by "kind:cause". */
    val deaths = ConcurrentSkipListMap<String, Int>()
    /** This week's hunting and feeding tallies by kind, as [Tally] keys them: kills of residents, diets, bands' food and time. */
    val hunting = ConcurrentSkipListMap<String, Int>()
    /** For the check: people's children expected to die of each cause, by the week's odds, since last read (a report ledger, not saved). */
    @Transient val childDeaths = ConcurrentHashMap<String, DoubleAdder>()
    /** Young born this week by kind. */
    val births = TreeMap<String, Int>()
    /** The residents' deaths this week by "kind:cause", head (fractions: the residents are densities). */
    val residentDeaths = TreeMap<String, Double>()

    /** Starts a new week (the children's ledger runs on until read). */
    fun clear() { kills.clear(); meetings.clear(); deaths.clear(); births.clear(); hunting.clear(); residentDeaths.clear() }

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
    /** A tally of kind [s]: [what] (as [KILLS_OF_RESIDENTS], or a death's cause). */
    fun of(s: Species, what: String) = of(s.name, what)
    /** A death of kind [s] of [cause]. */
    fun of(s: Species, cause: DeathCause) = of(s.name, cause.label)
    /** A tally of the kind named [kind]: [what] (the kills, kept by prey name). */
    fun of(kind: String, what: String) = "$kind:$what"
    /** The kind a tally is of, and what it counts. */
    fun kindOf(key: String) = key.substringBefore(':')
    fun whatOf(key: String) = key.substringAfter(':')
    /** Births and kills among the run's deaths ([inflation.tools.fauna.DeathTally]). */
    const val BORN = "+born"
    const val HUNTED = "hunted"
    const val KILLS_OF_RESIDENTS = "kills of residents"
    /** What a hunting kind's diet asked for ([part] "asked") and got of each part (meat, small prey, carrion, plants), kg. */
    fun diet(s: Species, part: String) = of(s, "diet $part, kg")
    /** Bands' tallies by the kind of country they are in. */
    fun bandsIn(country: String, what: String) = "$BANDS_IN$country:$what"
    const val BANDS_IN = "bands in "
    /** Bands' tallies by how they are clad ([CLAD] or [POORLY_CLAD]). */
    fun bandsClad(clad: String, what: String) = "bands $clad:$what"
    const val CLAD = "clad"
    const val POORLY_CLAD = "poorly clad"
    /** How bands spent their hands' days, tenths of a day. */
    fun bandTime(what: String) = "band time:$what"
    /** Hide people got from [source] (residents, small pelts), grams. */
    fun hide(source: String) = "people hide:$source"
    /** Deaths of [cause], within a band tally. */
    fun diedOf(cause: String) = "$DIED_OF$cause"
    const val DIED_OF = "died of "
}
