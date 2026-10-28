@file:OptIn(RawKey::class)

package inflation.ecology

import inflation.world.Biome
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.DoubleAdder

/** Marks a function that makes a [TallyKey] from free text: outside the [Tally] builders, a use must opt in, and so shows in review. */
@RequiresOptIn("Builds a tally key from free text; use a typed builder of Tally instead", RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
annotation class RawKey

/**
 * A key of the week's ledgers ([WeekStats]). Only the [Tally] builders (and [Met]) make one, so a writer cannot
 * add, nor a reader ask for, a key typed by hand. It prints and sorts as its [text], as the string keys did.
 */
@JvmInline
value class TallyKey private constructor(val text: String) : Comparable<TallyKey> {
    override fun compareTo(other: TallyKey) = text.compareTo(other.text)
    override fun toString() = text
    /** Whether this key starts with [prefix] (a builder for all of a kind's, or a country's, tallies). */
    fun startsWith(prefix: TallyKey) = text.startsWith(prefix.text)

    companion object {
        @RawKey fun raw(text: String) = TallyKey(text)
    }
}

/**
 * The week's tallies, by typed key ([TallyKey]): what the reports and checks read back. The model never reads them. One
 * ledger for the whole step, kept by the land and cleared once as the week starts ([inflation.ecology.HexSim.step]),
 * so a tally written in any phase lasts until the reports read it.
 * (Concurrent and sorted: hunting runs region by region on all cores.)
 */
class WeekStats {
    /** Kills this week by prey kind (in order of name, as the reports list them). */
    val kills = ConcurrentSkipListMap<Species, Int>(BY_NAME)
    /** Meetings this week by kind of meeting. */
    val meetings = ConcurrentSkipListMap<TallyKey, Int>()
    /** Deaths this week by "kind:cause". */
    val deaths = ConcurrentSkipListMap<TallyKey, Int>()
    /** People's deaths this week by "kind:cause", the grown (elders too) and the young apart (the two add up to the part of [deaths] that is people's, bar the orphans). */
    val adultDeaths = ConcurrentSkipListMap<TallyKey, Int>()
    val youngDeaths = ConcurrentSkipListMap<TallyKey, Int>()
    /** This week's hunting and feeding tallies by kind, as [Tally] keys them: kills of residents, diets, bands' food and time. */
    val hunting = ConcurrentSkipListMap<TallyKey, Int>()
    /** For the check: people's children expected to die of each cause, by the week's odds, since last read (a report ledger, not saved). */
    @Transient val childDeaths = ConcurrentHashMap<String, DoubleAdder>()
    /** Young born this week by kind. */
    val births = TreeMap<Species, Int>(BY_NAME)
    /** The residents' deaths this week by "kind:cause", head (fractions: the residents are densities). */
    val residentDeaths = TreeMap<TallyKey, Double>()

    /** Counts [n] more of [what] in [meetings]. */
    fun meeting(what: Met, n: Int = 1) { meetings.merge(what.key, n, Int::plus) }
    /** Counts [n] more of [what] of [of] (a kind, a skill, a custom...) in [meetings]. */
    fun meeting(what: Met.Of, of: String, n: Int = 1) { meetings.merge(TallyKey.raw(what.key(of)), n, Int::plus) }

    /** Starts a new week (the children's ledger runs on until read). */
    fun clear() { kills.clear(); meetings.clear(); deaths.clear(); adultDeaths.clear(); youngDeaths.clear(); births.clear(); hunting.clear(); residentDeaths.clear() }

    private companion object { val BY_NAME = compareBy<Species> { it.name } }

}

/** What the week's [meetings][WeekStats.meetings] count, each under its [label]. */
enum class Met(val label: String) {
    BANDS_BACK_AT_A_CACHE("bands back at a cache"),
    BANDS_BUILT_HOUSES("bands built houses"),
    BANDS_BUILT_PALISADES("bands built palisades"),
    BANDS_BUILT_WEIRS("bands built weirs"),
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
    BANDS_VISITED_FRIENDS("bands visited friends"),
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

    /** Its key in [WeekStats.meetings]. */
    val key = TallyKey.raw(label)

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
    fun of(kind: String, what: String) = TallyKey.raw("$kind:$what")
    /** The kind a tally is of, and what it counts. */
    fun kindOf(key: TallyKey) = key.text.substringBefore(':')
    fun whatOf(key: TallyKey) = key.text.substringAfter(':')
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
    /** Bands' plant food got by the plant food it came from, at what it feeds, kg of need. */
    fun plantsFrom(f: PlantFrom) = TallyKey.raw("bands' plants from ${f.label}")

    /** What bands' tallies by the kind of country they are in ([bandsIn]) and by their clothing ([bandsClad]) count. */
    enum class Bands(val label: String) {
        /** Food wanted, and got of meat (with small game and carrion), plants and fish, kg. */
        ASKED("asked"), MEAT("meat"), PLANTS("plants"), FISH("fish"),
        BAND_WEEKS("band-weeks"), PEOPLE_WEEKS("people-weeks"), BORN("born"),
        /** Deaths foreseen a year ahead ([HexGroup.foreseen]): people-weeks times the share foreseen to die, thousandths. */
        FORESEEN("foreseen deaths, 1/1000"),
        /** Perseverance ([Band.sisu]): people-weeks times the band's, thousandths. */
        SISU("perseverance, 1/1000"),
        /** The hearth fire fed by the firewood got, and the days spent fetching it, % summed over band-weeks. */
        HEARTH("hearth, %"), FUEL_DAYS("fuel days, %"),
        /** The larder and the fat a band held at the week's end, days of its food, tenths summed over band-weeks. */
        LARDER("larder days, 1/10"), FAT("fat days, 1/10"),
        /** Of the larder, the meat (fresh and dried), days of food, tenths; and the share of the protein eaten that went to waste ([Band.leanLost]), %. */
        LARDER_MEAT("larder meat days, 1/10"), LEAN_LOST("lean lost, %"),
        /** Its salt: held, g a person; the share of its sodium need unmet last week, %; and salt to be had where it stands, %. */
        SALT_HELD("salt held, g a person"), SODIUM_SHORT("sodium short, %"), SALT_HERE("salt here, %"),
        /** The band-weeks it knew of a place to get salt ([HexGroups.saltSources]), and how far the nearest was then, km summed. */
        SALT_KNOWN("knows salt, band-weeks"), SALT_KM("nearest known salt, km"),
        /** For [Herding]: people-weeks clad (a kit of at least half), children-weeks, mobile band-weeks ([Living.MOBILE]), and the air where they stood, tenths of °C summed over people-weeks. */
        CLAD_WEEKS("clad people-weeks"), CHILD_WEEKS("children-weeks"), MOBILE_WEEKS("mobile band-weeks"), AIR("air, 1/10 °C"),
        /** For [Herding]: how well fed through the week ([HexGroup.fed], at most 1), thousandths summed over people-weeks. */
        FED("fed, 1/1000"),
        /** Its clothes ([Band.clothesClo]), clo, and its spare days over the last season ([Band.spareMean]), share: tenths and thousandths summed over band-weeks. */
        CLOTHES("clothes clo, 1/10"), SPARE("spare days, 1/1000"),
        /** The week's mean air at its camp, °C, tenths summed over band-weeks; the band-weeks it came to a new camp. */
        CAMP_AIR("camp air °C, 1/10"), MOVED("new camp, band-weeks"),
        /**
         * Its fire as its week's survival pass reckoned it, as it got it, and the share of that lit ([keepFire]), %; the
         * week's exposure odds as reckoned and as it came, 1/10000; the share of its hands' days left after the walk, %; the
         * band-weeks it asked for shelter and the share of that it held first, % ([Band.planFire] and the rest).
         */
        PLAN_FIRE("fire planned, %"), FIRE_GOT("fire got, %"), LIT("fire lit, %"), PLAN_ODDS("exposure planned, 1/10000"),
        ODDS("exposure, 1/10000"), LEFT("days after the walk, %"), SHELTER_ASKED("shelter asked, band-weeks"), SHELTER_FIRST("shelter held first, %"),
    }
    /**
     * In the weeks the cold ran a band's people into heat debt through all the cover it had ([WEEKS], tenths): the worst debt
     * of a day beyond the bearable, Wh/m², the night's air, °C, its clothes, clo, its fire and its shelter or house; whether it
     * came to a new camp that week, the shelter it aims at ([Band.shelterWill]) and the share of its people's days left spare
     * ([Band.spareDays]), all tenths.
     */
    enum class Cold(val label: String) { WEEKS("weeks"), SHORT("debt"), AIR("night air"), CLOTHES("clothes"), FIRE("fire"), SHELTER("shelter"),
        MOVED("new camp"), AIM("shelter aim"), SPARE("days spare") }
    /** Bands' tallies by the kind of country they are in. */
    fun bandsIn(country: Biome, what: Bands) = bandsIn(country, what.label)
    fun bandsIn(country: Biome, cold: Cold) = bandsIn(country, "cold ${cold.label}")
    /** Bands' deaths of [cause] by the kind of country they are in; [bandsDied] (country) starts all of them. */
    fun bandsDied(country: Biome, cause: DeathReason) = bandsIn(country, "$DIED_OF${cause.label}")
    fun bandsDied(country: Biome) = bandsIn(country, DIED_OF)
    private fun bandsIn(country: Biome, what: String) = TallyKey.raw("$BANDS_IN${country.label}:$what")
    /** The country of a [bandsIn] key counting [what], or null if it is another key. */
    fun bandsCountry(key: TallyKey, what: Bands): Biome? = key.text.let { k ->
        if (k.startsWith(BANDS_IN) && k.endsWith(":${what.label}")) BIOME_BY_LABEL[k.removePrefix(BANDS_IN).removeSuffix(":${what.label}")] else null }
    private const val BANDS_IN = "bands in "
    // (Here, not on Biome: a change there would change the world key.)
    private val BIOME_BY_LABEL by lazy { Biome.entries.associateBy { it.label } }

    /** How a band is clad: [CLAD], a kit of clothes of at least half. */
    enum class Clad(val label: String) { CLAD("clad"), POORLY_CLAD("poorly clad") }
    /** Bands' tallies by how they are clad. */
    fun bandsClad(clad: Clad, what: Bands) = TallyKey.raw("bands ${clad.label}:${what.label}")
    /** Bands' deaths of [cause] by how they are clad; [bandsCladDied] (clad) starts all of them. */
    fun bandsCladDied(clad: Clad, cause: DeathReason) = TallyKey.raw(bandsCladDied(clad).text + cause.label)
    fun bandsCladDied(clad: Clad) = TallyKey.raw("bands ${clad.label}:$DIED_OF")
    /** How well a band was fed through the week, its fat drawn on ([HexGroup.fed]): the first bin it falls [below]. */
    enum class Fed(val label: String, val below: Float) {
        STARVING("under half", 0.5f), SHORT("half to 0.9", 0.9f), FED("0.9 and over", Float.POSITIVE_INFINITY);
        companion object { fun of(fed: Float) = entries.firstOrNull { fed < it.below } ?: FED }
    }
    /** Bands' tallies by how well they were fed. */
    fun bandsFed(fed: Fed, what: Bands) = TallyKey.raw("bands fed ${fed.label}:${what.label}")
    /** Bands' deaths of [cause] by how well they were fed; [bandsFedDied] (fed) starts all of them. */
    fun bandsFedDied(fed: Fed, cause: DeathReason) = TallyKey.raw(bandsFedDied(fed).text + cause.label)
    fun bandsFedDied(fed: Fed) = TallyKey.raw("bands fed ${fed.label}:$DIED_OF")
    /**
     * How a band lives: [SETTLED], at home in its houses or at a camp it has kept half a year or more ([HexGroups.bandLiving]);
     * else [MOBILE]. The line is the report's, not the model's: nothing chooses by it.
     */
    enum class Living(val label: String) { SETTLED("settled"), MOBILE("mobile") }
    /** Bands' tallies by how they live. */
    fun bandsLiving(way: Living, what: Bands) = TallyKey.raw("bands ${way.label}:${what.label}")
    /** Bands' deaths of [cause] by how they live; [bandsLivingDied] (way) starts all of them. */
    fun bandsLivingDied(way: Living, cause: DeathReason) = TallyKey.raw(bandsLivingDied(way).text + cause.label)
    fun bandsLivingDied(way: Living) = TallyKey.raw("bands ${way.label}:$DIED_OF")
    /**
     * Whether a band keeps a herd, and how much of its hands' days the herd asks ([herdDays]): under a tenth, a tenth to 0.3,
     * or more. The line is the report's, not the model's: nothing chooses by it.
     */
    enum class Herding(val label: String, val below: Float) {
        LIGHT("herd under 10%", 0.1f), MID("herd 10-30%", 0.3f), HEAVY("herd 30%+", Float.POSITIVE_INFINITY), NOT_HERDING("not herding", 0f)
    }
    /** Bands' tallies by whether they keep a herd. */
    fun bandsHerding(h: Herding, what: Bands) = TallyKey.raw("bands ${h.label}:${what.label}")
    fun bandsHerding(h: Herding, cold: Cold) = TallyKey.raw("bands ${h.label}:cold ${cold.label}")
    /** Bands' time on [a] (or lost, [t]) by whether they keep a herd, tenths of a day. */
    fun bandsHerdingTime(h: Herding, a: Activity) = TallyKey.raw("bands ${h.label}:time ${a.label}")
    fun bandsHerdingTime(h: Herding, t: TimeTotal) = TallyKey.raw("bands ${h.label}:time ${t.label}")
    /** Bands' deaths of [cause] by whether they keep a herd, the [young] apart; [bandsHerdingDied] (h, young) starts all of them. */
    fun bandsHerdingDied(h: Herding, young: Boolean, cause: DeathReason) = TallyKey.raw(bandsHerdingDied(h, young).text + cause.label)
    fun bandsHerdingDied(h: Herding, young: Boolean) = TallyKey.raw("bands ${h.label}:${if (young) "children " else "grown "}$DIED_OF")
    /**
     * How a band's chores fared against what they asked of its week ([HexGroups.countSqueeze]): the band-weeks each asked for
     * days, those it got less than [SQUEEZED] of its ask in a week that ran out of days, and the days asked and not got, tenths;
     * and of the days asked, those the survival pass asked first ([survivalDays]), tenths.
     */
    enum class Squeeze(val label: String) { ASKED_WEEKS("weeks asked"), SHORT_WEEKS("weeks short"), ASKED_DAYS("days asked"), MISSING_DAYS("days missing"),
        FIRST_DAYS("days asked first") }
    fun squeeze(a: Activity, what: Squeeze) = TallyKey.raw("band squeeze:${a.label}:${what.label}")
    /** A band's spare days over the last season or so ([Band.spareMean], a share of its hands' days): the first bin it falls [below]. */
    enum class Spare(val label: String, val below: Float) {
        NONE("under 5%", 0.05f), LITTLE("5 to 15%", 0.15f), SOME("15% and over", Float.POSITIVE_INFINITY);
        companion object { fun of(share: Float) = entries.firstOrNull { share < it.below } ?: SOME }
    }
    /** Bands' tallies by their spare days; [bandsSpareDied] (spare) starts all their deaths. */
    fun bandsSpare(spare: Spare, what: Bands) = TallyKey.raw("bands spare ${spare.label}:${what.label}")
    fun bandsSpareDied(spare: Spare, cause: DeathReason) = TallyKey.raw(bandsSpareDied(spare).text + cause.label)
    fun bandsSpareDied(spare: Spare) = TallyKey.raw("bands spare ${spare.label}:$DIED_OF")
    /**
     * How a band was kept warm at the week's end: its fire ([Band.fire]) and its shelters against those it aims at
     * ([Band.shelterWill]), each at least half or not. The report's line.
     */
    enum class Warmed(val label: String) {
        BOTH("fire and shelter"), FIRE("fire, little shelter"), SHELTER("shelter, little fire"), NEITHER("little of either");
        companion object { fun of(fire: Float, shelter: Float) = if (fire >= 0.5f) (if (shelter >= 0.5f) BOTH else FIRE) else if (shelter >= 0.5f) SHELTER else NEITHER }
    }
    /** Bands' tallies by their spare days and how they were kept warm; [bandsWarmedDied] (spare, warmed) starts all their deaths. */
    fun bandsWarmed(spare: Spare, w: Warmed, what: Bands) = TallyKey.raw("bands spare ${spare.label} warmed ${w.label}:${what.label}")
    fun bandsWarmedDied(spare: Spare, w: Warmed, cause: DeathReason) = TallyKey.raw(bandsWarmedDied(spare, w).text + cause.label)
    fun bandsWarmedDied(spare: Spare, w: Warmed) = TallyKey.raw("bands spare ${spare.label} warmed ${w.label}:$DIED_OF")
    /**
     * A band's foraging against its week's plan ([countFoodTime]), tenths of people-days summed over band-weeks: the
     * band-weeks, the days the plan left it, those it spent and those past them, the band-weeks it went more than 5% past
     * them, and what kills beyond and short of those expected, extra hauling and digging soaks took.
     */
    enum class FoodTime(val label: String) {
        WEEKS("band-weeks"), ROOM("days left it"), SPENT("days spent"), OVER("days past"), OVER_WEEKS("weeks past"),
        KILLS_OVER("kills beyond expected"), KILLS_UNDER("kills short of expected"), HAUL("hauling"), DIG("digging soaks")
    }
    fun foodTime(k: FoodTime) = TallyKey.raw("band foraging time:${k.label}")
    /** Whether a band keeps a herd ([Band.herd]). */
    enum class HerdKept(val label: String) { HERDING("keeping a herd"), NONE("keeping none") }
    /** Bands' tallies by whether they keep a herd; [bandsHerdKeptDied] (herding) starts all their deaths. */
    fun bandsHerdKept(h: HerdKept, what: Bands) = TallyKey.raw("bands ${h.label}:${what.label}")
    fun bandsHerdKeptDied(h: HerdKept, cause: DeathReason) = TallyKey.raw(bandsHerdKeptDied(h).text + cause.label)
    fun bandsHerdKeptDied(h: HerdKept) = TallyKey.raw("bands ${h.label}:$DIED_OF")
    /** How a band's week of making clothes went, where it wanted any ([Band.clothesMade]). */
    enum class ClothesMade(val label: String) {
        MADE("made all it wanted"), TIME_SHORT("short of time"), GOODS_SHORT("short of hide or sinew"), NOT_REACHED("time gone before clothes came up")
    }
    /** Band-weeks clothes making went as [c]. */
    fun clothesMade(c: ClothesMade) = TallyKey.raw("band clothes:${c.label}")
    /** The cause of a death in a [bandsDied] or [bandsCladDied] key (or [bandsLivingDied], [bandsHerdingDied], [bandsHerdKeptDied]). */
    fun diedOf(key: TallyKey) = key.text.substringAfter(DIED_OF)
    private const val DIED_OF = "died of "

    /** How bands spent their hands' days, tenths of a day: on each [Activity], and in all or left over. */
    fun bandTime(a: Activity) = bandTime(a.label)
    fun bandTime(t: TimeTotal) = bandTime(t.label)
    private fun bandTime(what: String) = TallyKey.raw("band time:$what")
    enum class TimeTotal(val label: String) { ALL("all"), LEFT("left"), LOST("lost to cold and hunger") }

    /** Where people's hides came from. */
    enum class HideSource(val label: String) { RESIDENTS("residents"), SMALL_PELTS("small pelts"), HERDS("herds") }
    /** Hide people got from [source], grams. */
    fun hide(source: HideSource) = TallyKey.raw("people hide:${source.label}")
}
