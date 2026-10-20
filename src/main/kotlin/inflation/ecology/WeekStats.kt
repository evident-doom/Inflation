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
    /** Kills this week by prey kind (in order of name, as the reports list them). */
    val kills = ConcurrentSkipListMap<Species, Int>(BY_NAME)
    /** Meetings this week by kind of meeting. */
    val meetings = ConcurrentSkipListMap<String, Int>()
    /** Deaths this week by "kind:cause". */
    val deaths = ConcurrentSkipListMap<String, Int>()
    /** This week's hunting and feeding tallies by kind, as [Tally] keys them: kills of residents, diets, bands' food and time. */
    val hunting = ConcurrentSkipListMap<String, Int>()
    /** For the check: people's children expected to die of each cause, by the week's odds, since last read (a report ledger, not saved). */
    @Transient val childDeaths = ConcurrentHashMap<String, DoubleAdder>()
    /** Young born this week by kind. */
    val births = TreeMap<Species, Int>(BY_NAME)
    /** The residents' deaths this week by "kind:cause", head (fractions: the residents are densities). */
    val residentDeaths = TreeMap<String, Double>()

    /** Counts [n] more of [what] in [meetings]. */
    fun meeting(what: Met, n: Int = 1) { meetings.merge(what.label, n, Int::plus) }
    /** Counts [n] more of [what] of [of] (a kind, a skill, a custom...) in [meetings]. */
    fun meeting(what: Met.Of, of: String, n: Int = 1) { meetings.merge(what.key(of), n, Int::plus) }

    /** Starts a new week (the children's ledger runs on until read). */
    fun clear() { kills.clear(); meetings.clear(); deaths.clear(); births.clear(); hunting.clear(); residentDeaths.clear() }

    private companion object { val BY_NAME = compareBy<Species> { it.name } }

}

/** What the week's [meetings][WeekStats.meetings] count, each under its [label]. */
enum class Met(val label: String) {
    BANDS_BACK_AT_A_CACHE("bands back at a cache"),
    BANDS_BUILT_HOUSES("bands built houses"),
    BANDS_BUILT_PALISADES("bands built palisades"),
    BANDS_BURNED_OFF_GRASS("bands burned off grass"),
    BANDS_EXCHANGED_PARTNERS("bands exchanged partners"),
    BANDS_KEPT_AWAY_FROM_THE_SICK("bands kept away from the sick"),
    BANDS_LET_A_RITE_GO("bands let a rite go"),
    BANDS_MADE_AN_APPOINTMENT("bands made an appointment"),
    BANDS_MAULED_AT_A_CARCASS("bands mauled at a carcass"),
    BANDS_MET("bands met"),
    BANDS_SOWED("bands sowed"),
    BANDS_TOLD_OF_COUNTRY("bands told of country"),
    BANDS_TOLD_OF_ROUTES("bands told of routes"),
    BANDS_TOOK_UP_A_RITE("bands took up a rite"),
    BANDS_TOOK_UP_ANOTHERS_APPOINTMENT("bands took up another's appointment"),
    BANDS_TOOK_UP_ANOTHERS_RITE("bands took up another's rite"),
    CACHES_ROBBED("caches robbed"),
    FAVOURS_HELD_BACK("favours held back"),
    FAVOURS_RETURNED("favours returned"),
    FEASTS("feasts"),
    FEASTS_WITH_BEER("feasts with beer"),
    FOOD_GIVEN("food given"),
    HUNGRY_BANDS_CHOSE_TO_MOVE("hungry bands chose to move"),
    HUNGRY_BANDS_CHOSE_TO_STAY("hungry bands chose to stay"),
    HUNGRY_BANDS_FED_BY_KIN("hungry bands fed by kin"),
    HUNTERS_OF_OTHER_KINDS("hunters of other kinds"),
    KEPT_ANIMALS_CULLED("kept animals culled"),
    KILL_STOLEN("kill stolen"),
    LARDERS_RAIDED("larders raided"),
    ORNAMENTS_GIVEN("ornaments given"),
    PACK_GAVE_WAY("pack gave way"),
    PACKS_FOUGHT("packs fought"),
    RAIDED_BANDS_SLIPPED_AWAY("raided bands slipped away"),
    RAIDS("raids"),
    RAIDS_ON_HOMES("raids on homes"),
    RAIDS_SET_OUT("raids set out"),
    RIVAL_PACKS("rival packs"),
    SLIPPED_AWAY("slipped away"),
    SMALL_BANDS_JOINED_KIN("small bands joined kin"),
    YOUNG_ANIMALS_KEPT("young animals kept"),
    YOUNG_HUNTERS_LEFT_THEIR_PACK("young hunters left their pack");

    /** Meetings of something named (a kind, a skill, a tool, a custom, a good), keyed [before] its name [after]. */
    enum class Of(val before: String, val after: String = "") {
        NEWCOMERS("newcomers: "), SEA_CROSSINGS("sea crossings: "), HIT_ON("band hit on "), LOST("band lost "), TOOK_A_PUP("bands took a ", " pup"),
        LEARNED("band learned "), TOOK_UP("band took up "), TOOK_LEAVE_OF("band took leave of "), DROPPED("band dropped "), GIVEN("", " given"),
        TURNED_BACK_BY("bands turned back by ");

        fun key(of: String) = before + of + after
    }
}

/**
 * The tally keys the reports read back by name, built here by writer and reader alike from typed parts, so that
 * renaming one changes both and cannot silently zero a report. Keys only ever listed whole stay free text.
 */
object Tally {
    /** A [hunting][WeekStats.hunting] tally of kind [s]: [what]. */
    fun of(s: Species, what: OfKind) = of(s.name, what.label)
    /** A death of kind [s] put down to [cause]. */
    fun of(s: Species, cause: DeathReason) = of(s.name, cause.label)
    /** The start of every tally of kind [s] (to find them all by). */
    fun of(s: Species) = of(s.name, "")
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
    /** Bands' deaths of [cause] by the kind of country they are in; [bandsDied] (country) starts all of them. */
    fun bandsDied(country: Biome, cause: DeathReason) = bandsIn(country, "$DIED_OF${cause.label}")
    fun bandsDied(country: Biome) = bandsIn(country, DIED_OF)
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
    /** Bands' deaths of [cause] by how they are clad; [bandsCladDied] (clad) starts all of them. */
    fun bandsCladDied(clad: Clad, cause: DeathReason) = bandsCladDied(clad) + cause.label
    fun bandsCladDied(clad: Clad) = "bands ${clad.label}:$DIED_OF"
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
