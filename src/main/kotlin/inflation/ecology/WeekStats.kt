package inflation.ecology

import inflation.world.Biome
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

    /** Counts [n] more of [what] in [meetings]. */
    fun meeting(what: String, n: Int = 1) { meetings.merge(what, n, Int::plus) }

    /** Starts a new week (the children's ledger runs on until read). */
    fun clear() { kills.clear(); meetings.clear(); deaths.clear(); births.clear(); hunting.clear(); residentDeaths.clear() }

}

/**
 * The tally keys the reports read back by name, built here by writer and reader alike from typed parts, so that
 * renaming one changes both and cannot silently zero a report. Keys only ever listed whole stay free text.
 */
object Tally {
    /** A [hunting][WeekStats.hunting] tally of kind [s]: [what]. */
    fun of(s: Species, what: OfKind) = of(s.name, what.label)
    /** A death of kind [s] put down to [cause] (a [DeathCause]'s label, an illness's, or "taken by lion" and its kin). */
    fun of(s: Species, cause: String) = of(s.name, cause)
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

    /** What a kind's [hunting][WeekStats.hunting] tallies count. */
    enum class OfKind(val label: String) {
        KILLS_OF_RESIDENTS("kills of residents"), CHILDREN_DIED("children died"), MEAT_EATEN("meat eaten, kg"),
        PLANTS_EATEN("plants eaten, kg"), GRAIN_REAPED("grain reaped, kg"), PLANTS_LEACHED("plants leached, kg"), FISH("fish, kg"),
    }

    /** What a hunting kind's diet asked for ([ASKED]) and got of each part, kg (meat includes fish). */
    enum class DietPart(val label: String) { ASKED("asked"), MEAT("meat"), SMALL_PREY("small prey"), CARRION("carrion"), PLANTS("plants") }
    fun diet(s: Species, part: DietPart) = of(s.name, "diet ${part.label}, kg")

    /** What bands' tallies by the kind of country they are in ([bandsIn]) and by their clothing ([bandsClad]) count. */
    enum class Bands(val label: String) {
        /** Food wanted, and got of meat (with small game and carrion), plants and fish, kg. */
        ASKED("asked"), MEAT("meat"), PLANTS("plants"), FISH("fish"),
        BAND_WEEKS("band-weeks"), PEOPLE_WEEKS("people-weeks"), BORN("born"),
        /** The hearth fire fed by the firewood got, and the days spent fetching it, % summed over band-weeks. */
        HEARTH("hearth, %"), FUEL_DAYS("fuel days, %"),
    }
    /**
     * In the weeks colder than all the cover a band had ([WEEKS], tenths), what it was short by and how much each
     * cover gave, tenths of a °C.
     */
    enum class Cold(val label: String) { WEEKS("weeks"), SHORT("short"), AIR("air"), CLOTHES("clothes"), FIRE("fire"), SHELTER("shelter") }
    /** Bands' tallies by the kind of country they are in. */
    fun bandsIn(country: Biome, what: Bands) = bandsIn(country, what.label)
    fun bandsIn(country: Biome, cold: Cold) = bandsIn(country, "cold ${cold.label}")
    /** Bands' deaths of [cause] by the kind of country they are in ("" for all of them, as a prefix). */
    fun bandsDied(country: Biome, cause: String) = bandsIn(country, "$DIED_OF$cause")
    private fun bandsIn(country: Biome, what: String) = "$BANDS_IN${country.label}:$what"
    /** The country of a [bandsIn] key counting [what], or null if it is another key. */
    fun bandsCountry(key: String, what: Bands): Biome? =
        if (key.startsWith(BANDS_IN) && key.endsWith(":${what.label}")) BIOME_BY_LABEL[key.removePrefix(BANDS_IN).removeSuffix(":${what.label}")] else null
    private const val BANDS_IN = "bands in "
    // (Here, not on Biome: a change there would change the world key.)
    private val BIOME_BY_LABEL by lazy { Biome.entries.associateBy { it.label } }

    /** How a band is clad: [CLAD], a kit of clothes of at least half. */
    enum class Clad(val label: String) { CLAD("clad"), POORLY_CLAD("poorly clad") }
    /** Bands' tallies by how they are clad. */
    fun bandsClad(clad: Clad, what: Bands) = "bands ${clad.label}:${what.label}"
    /** Bands' deaths of [cause] by how they are clad ("" for all of them, as a prefix). */
    fun bandsCladDied(clad: Clad, cause: String) = "bands ${clad.label}:$DIED_OF$cause"
    /** The cause of a death in a [bandsDied] or [bandsCladDied] key. */
    fun diedOf(key: String) = key.substringAfter(DIED_OF)
    private const val DIED_OF = "died of "

    /** How bands spent their hands' days, tenths of a day: on each [Activity], and in all or left over. */
    fun bandTime(a: Activity) = bandTime(a.label)
    fun bandTime(t: TimeTotal) = bandTime(t.label)
    private fun bandTime(what: String) = "band time:$what"
    enum class TimeTotal(val label: String) { ALL("all"), LEFT("left") }

    /** Where people's hides came from. */
    enum class HideSource(val label: String) { RESIDENTS("residents"), SMALL_PELTS("small pelts") }
    /** Hide people got from [source], grams. */
    fun hide(source: HideSource) = "people hide:${source.label}"
}
