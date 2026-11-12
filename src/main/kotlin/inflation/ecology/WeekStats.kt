@file:OptIn(RawKey::class)

package inflation.ecology

import inflation.Config
import inflation.world.Biome
import inflation.world.Season
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicIntegerArray
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
    /** Meetings this week by kind of meeting (the counts of the [Met]s are kept in [metSums] and put in when this is read). */
    val meetings: ConcurrentSkipListMap<TallyKey, Int> get() { drainMet(); return meetingLedger }
    private val meetingLedger = ConcurrentSkipListMap<TallyKey, Int>()
    /** The week's counts of each [Met] by ordinal, and whether it was counted at all (a count of nought still lists it): a sorted-map merge on a long label cost more than the count. */
    private val metSums = AtomicIntegerArray(Met.entries.size)
    private val metSeen = java.util.concurrent.atomic.AtomicIntegerArray(Met.entries.size)
    private fun drainMet() {
        for (m in Met.entries) if (metSeen.get(m.ordinal) != 0) {
            val n = metSums.getAndSet(m.ordinal, 0); metSeen.set(m.ordinal, 0)
            meetingLedger.merge(m.key, n, Int::plus)
        }
    }
    /** Deaths this week by "kind:cause". */
    val deaths = ConcurrentSkipListMap<TallyKey, Int>()
    /** People's deaths this week by "kind:cause", the grown (elders too) and the young apart (the two add up to the part of [deaths] that is people's, bar the orphans). */
    val adultDeaths = ConcurrentSkipListMap<TallyKey, Int>()
    val youngDeaths = ConcurrentSkipListMap<TallyKey, Int>()
    /** This week's hunting and feeding tallies by kind, as [Tally] keys them: kills of residents, diets, bands' food and time. */
    val hunting = ConcurrentSkipListMap<TallyKey, Int>()
    /** For the check: people's children expected to die of each cause, by the week's odds, since last read (a report ledger, not saved). */
    @Transient val childDeaths = ConcurrentHashMap<DeathReason, DoubleAdder>()
    /** People's grown ones' deaths expected from each week's hazards, by cause (census only; raids and hunts are booked elsewhere). */
    @Transient val grownHazardDeaths = ConcurrentHashMap<DeathReason, DoubleAdder>()
    /** Young born this week by kind. */
    val births = TreeMap<Species, Int>(BY_NAME)
    /** The residents' deaths this week by "kind:cause", head (fractions: the residents are densities). */
    val residentDeaths = TreeMap<TallyKey, Double>()

    /** Whether the census's splits of bands (by country, clothing, way of living and so on: [BandSplit]) are booked in [hunting]: set by the census that reads them, as booking them costs a band-week some forty ledger merges. */
    var bandSplits = false

    /**
     * Whether the census is kept: the report-only tallies ([tally], [meeting], the bands' time and splits). The model never
     * reads them, and neither does the history; what the history and the nominal limits read is booked by [keep] and the
     * ledgers of kills, deaths and births, which do not depend on this. Off in trainings (see [inflation.learning.HexPpo]).
     */
    @Volatile var census = Config.flag(Config.Key.CENSUS, true)

    /** Counts [n] more of [key] in [hunting] when the census is kept (while [summedByThread] runs, in the thread's own sums first). */
    fun tally(key: TallyKey, n: Int) { if (census) huntingSums.add(key, n) }
    /** Counts [n] more of [key] in [hunting] whether the census is kept or not: only for what the history reads back ([Tally.diet], the meat and plants eaten, the kills, the hunt steps). */
    fun keep(key: TallyKey, n: Int) = huntingSums.add(key, n)
    /** Counts [n] more kills of [s] in [kills] (as [tally] does). */
    fun killed(s: Species, n: Int) = killSums.add(s, n)
    private val huntingSums = ThreadSums(hunting)
    private val killSums = ThreadSums(kills)

    /**
     * Runs [work] (loops on all cores) with [tally] and [killed] summing in each thread's own sums, put in the ledgers at
     * its end: a party's week made some thirty merges into one sorted map that all cores wrote to. The sums are whole
     * numbers, so the ledgers come out the same whatever the order.
     */
    fun <T> summedByThread(work: () -> T): T {
        huntingSums.on = true; killSums.on = true
        try { return work() } finally { huntingSums.flush(); killSums.flush() }
    }

    /** Counts [n] more of [what] in [meetings]. */
    fun meeting(what: Met, n: Int = 1) { if (!census) return; metSums.addAndGet(what.ordinal, n); metSeen.set(what.ordinal, 1) }
    /** Counts [n] more of [what] of [of] (a kind, a skill, a custom...) in [meetings]. */
    fun meeting(what: Met.Of, of: String, n: Int = 1) { if (!census) return; meetingLedger.merge(TallyKey.raw(what.key(of)), n, Int::plus) }

    /** Starts a new week (the children's ledger runs on until read). */
    fun clear() { kills.clear(); meetingLedger.clear(); for (i in 0 until Met.entries.size) { metSums.set(i, 0); metSeen.set(i, 0) }; deaths.clear(); adultDeaths.clear(); youngDeaths.clear(); births.clear(); hunting.clear(); residentDeaths.clear() }

    private companion object { val BY_NAME = compareBy<Species> { it.name } }

}

/**
 * Sums for [ledger] kept by each thread while [on] (see [WeekStats.summedByThread]), put in it by [flush]; while off,
 * [add] merges into it at once. A key added to has it in the ledger after the flush, with a sum of nought if need be,
 * as if each had been merged on its own.
 */
private class ThreadSums<K>(private val ledger: ConcurrentSkipListMap<K, Int>) {
    @Volatile var on = false
    /** One thread's sums, by key, and the keys it added to in the order it first did (walked at the flush instead of the map). */
    private class Mine<T> { val at = HashMap<T, IntArray>(); val keys = ArrayList<T>(); val sums = ArrayList<IntArray>() }
    private val all = ConcurrentLinkedQueue<Mine<K>>()
    private val mine = ThreadLocal.withInitial { Mine<K>().also { all += it } }

    fun add(key: K, n: Int) {
        if (!on) { ledger.merge(key, n, Int::plus); return }
        val m = mine.get()
        m.at.getOrPut(key) { IntArray(1).also { m.keys += key; m.sums += it } }[0] += n
    }

    /**
     * Puts every thread's sums in the ledger and turns them off (once the loops have joined): summed over the threads first,
     * then each key merged once (the sums are whole numbers, so the ledger is the same as merging thread by thread). A
     * thread's keys are walked from its list, not its map, whose table stays as wide as its busiest week.
     */
    fun flush() {
        on = false
        val total = HashMap<K, IntArray>()
        for (m in all) {
            for (i in m.keys.indices) total.getOrPut(m.keys[i]) { IntArray(1) }[0] += m.sums[i][0]
            m.at.clear(); m.keys.clear(); m.sums.clear()
        }
        for ((k, v) in total) ledger.merge(k, v[0], Int::plus)
    }
}

/** What the week's [meetings][WeekStats.meetings] count, each under its [label]. */
enum class Met(val label: String) {
    BANDS_ATE_THEIR_TOTEM("bands ate their totem"),
    BANDS_BACK_AT_A_CACHE("bands back at a cache"),
    BANDS_BUILT_HOUSES("bands built houses"),
    BANDS_BUILT_PALISADES("bands built palisades"),
    BANDS_BUILT_WEIRS("bands built weirs"),
    BANDS_DUG_WELLS("bands dug wells"),
    BANDS_BUILT_PENS("bands built pens for their herds"),
    BANDS_BURNED_OFF_GRASS("bands burned off grass"),
    BANDS_CARRIED_THEIR_DEAD("bands carried their dead to their ancestors"),
    BANDS_CAME_TO_HOLD_THEIR_WAY_OF_LIFE("bands came to hold the way of life they live"),
    BANDS_BECAME_ONE("bands at home together became one"),
    BANDS_OF_TWO_MINDS_BECAME_ONE("bands at home together of the two kinds of choosing became one"),
    BANDS_DRIVEN_FROM_HOME("bands driven from their homes"),
    BEATEN_BANDS_MOVED_AWAY("bands driven from their homes that moved a hex away from the raiders"),
    BEATEN_HOUSEHOLDS_TO_KIN("households of bands driven from their homes that went to kin"),
    BEATEN_BANDS_JOINED_KIN("bands driven from their homes that joined kin whole"),
    BANDS_EXCHANGED_PARTNERS("bands exchanged partners"),
    BANDS_KEPT_AWAY_FROM_THE_SICK("bands kept away from the sick"),
    BANDS_MARKED_A_DEATH_TOLL("bands marked a death toll as an omen"),
    BANDS_MARKED_PLENTY_AFTER_WANT("bands marked plenty after want as an omen"),
    BANDS_MARKED_A_STRANGE_SIGN("bands marked a strange sign as an omen"),
    BANDS_MADE_AN_APPOINTMENT("bands made an appointment"),
    BANDS_MAULED_AT_A_CARCASS("bands mauled at a carcass"),
    BANDS_MET("bands met"),
    PARTIES_SENT("bands sent out a scouting party"),
    PARTIES_BACK("scouting parties came back to their band"),
    PARTIES_LOST("scouting parties lost"),
    PARTIES_STAYED_AWAY("scouting parties stayed away as bands of their own"),
    BANDS_AT_A_RECRUITER("bands that ended a move at a recruiter (the hurricane test's), recruited or not"),
    BANDS_RECRUITED("bands took recruits (the hurricane test's recruiters)"),
    PEOPLE_RECRUITED("people recruited (the hurricane test's recruiters)"),
    BANDS_STRUCK_ARRIVING("bands killed by a storm where they ended their move (the hurricane test's)"),
    BANDS_STRUCK_CROSSING("bands killed by a storm on their way (the hurricane test's)"),
    PEOPLE_KILLED_BY_STORMS("people killed by the hurricane test's storms"),
    PACKS_STRUCK_BY_STORMS("packs killed by the hurricane test's storms, arriving or on their way"),
    BANDS_WARNED_OF_PERIL("band-weeks whose camp a peril was foreseen to strike within the weeks told, not this week"),
    BANDS_WARNED_REACHED_SAFETY("band-weeks warned of a peril at camp that chose a place with none foreseen"),
    BANDS_WARNED_NO_SAFE_PLACE("band-weeks warned of a peril at camp with no place in reach free of one"),
    STORM_DEAD_CAMP_STRUCK("bands killed by a storm that struck their camp this week (no escape that week)"),
    STORM_DEAD_WARNED_BEFORE("of those, bands whose camp was foreseen struck as they chose the week before"),
    STORM_DEAD_SAFE_BEFORE("of those, bands that had a place free of peril in reach the week before"),
    STORM_DEAD_WALKED_IN("bands killed by a storm that struck the place they chose, their camp not struck"),
    BANDS_SOWED("bands sowed"),
    BANDS_SOWED_PULSES("bands sowed pulses"),
    BANDS_MANURED_FIELDS("band-weeks spreading penned dung on worn field ground"),
    BANDS_TOLD_OF_COUNTRY("bands told of country"),
    BANDS_TOLD_OF_ROUTES("bands told of routes"),
    BANDS_BORE_A_RITE("bands bore a rite of an omen"),
    BANDS_HELD_A_RITE("bands held a rite at its site"),
    BANDS_CUT_A_RITE_SHORT("bands cut a rite short for want of days"),
    BANDS_HELD_A_RITE_IN_A_LESSER_FORM("bands held a rite in a lesser form"),
    BANDS_GATHERED_AT_A_RITE("bands met at a rite they held together"),
    SICK_BANDS_KEPT_FROM_A_RITE("bands with sickness kept away from a rite's gathering"),
    RAIDS_ON_HOLY_GROUND("raids at a rite both keep, the peace broken"),
    BANDS_MOVED_A_RITE_TO_A_BETTER_MONTH("bands moved a rite to a month of plenty"),
    BANDS_SPLIT("bands split"),
    BANDS_BUDDED_OUT("bands budded into another hex"),
    BANDS_BUDDED_IN_PLACE("bands budded staying in their parent's hex"),
    HOUSEHOLDS_SEEPED("households left a crowded hex for a roomier one beside"),
    BANDS_SPLIT_BY_DISCORD("bands split off by discord in a band past its easy size"),
    BANDS_TOOK_UP_A_DISPUTE_WAY("bands split by discord where leaving is dear took up the next way of settling quarrels"),
    BANDS_LET_A_DISPUTE_WAY_LAPSE("small bands let their way of settling quarrels lapse a step"),
    BANDS_TOOK_UP_ANOTHERS_DISPUTE_WAY("bands took up another's way of settling quarrels"),
    HOUSEHOLDS_LEFT_IN_DISCORD("households left a band past its easy size for one with room"),
    BANDS_RELOCATED("bands moved their camp to a hex beside with more food a head"),
    BANDS_SPLIT_OVER_THEIR_BELIEF("bands split over their belief"),
    BANDS_SPLIT_ALONG_FORTUNES("bands split along their fortunes, richer and poorer"),
    BANDS_KEPT_APART_BY_WEALTH("bands at home together kept apart by their wealth"),
    BANDS_TOOK_UP_ANOTHERS_APPOINTMENT("bands took up another's appointment"),
    BANDS_TOOK_UP_ANOTHERS_BURIAL("bands took up another's burial way"),
    BANDS_TOOK_UP_ANOTHERS_STYLE("bands took up another's style mark"),
    BANDS_TOOK_UP_ANOTHERS_RITE("bands took up another's rite"),
    BANDS_TOOK_UP_ANOTHERS_TOTEM("bands took up another's totem"),
    BANDS_TOOK_UP_ANOTHERS_WAY_OF_LIFE("bands came to hold another's way of life"),
    BANDS_VISITED_FRIENDS("bands visited friends"),
    CACHES_ROBBED("caches robbed"),
    FAVOURS_HELD_BACK("favours held back"),
    FAVOURS_RETURNED("favours returned"),
    FEASTS("feasts"),
    KILL_FEASTS("feasts on kills too big to eat before they rot"),
    FEASTS_WITH_BEER("feasts with beer"),
    FOOD_GIVEN("food given"),
    HUNGRY_BANDS_CHOSE_TO_MOVE("hungry bands chose to move"),
    HUNGRY_BANDS_CHOSE_TO_STAY("hungry bands chose to stay"),
    HUNGRY_BANDS_FED_BY_KIN("hungry bands fed by kin"),
    HUNTERS_OF_OTHER_KINDS("hunters of other kinds"),
    KEPT_ANIMALS_TAKEN("kept animals taken by beasts"),
    KILL_STOLEN("kill stolen"),
    LARDERS_RAIDED("larders raided"),
    NEIGHBOURS_DEFENDED("neighbours stood with the raided"),
    NEIGHBOURS_MET("bands met neighbours in hexes about"),
    BANDS_SHIFTED_CAMP("bands shifted camp within their hex"),
    APPOINTMENTS_GATHERED("appointments kept by gatherings of bands"),
    BANDS_AT_APPOINTMENTS("bands at appointments' gatherings"),
    ORNAMENTS_GIVEN("ornaments given"),
    PACK_GAVE_WAY("pack gave way"),
    /** People killed in raids, by whether their band lives fixed ([Band.livesFixed]: in its houses, by fresh sowing, behind a palisade) or not. */
    RAID_KILLED_FIXED("people killed in raids, of bands living fixed"),
    RAID_KILLED_MOBILE("people killed in raids, of bands on the move"),
    /** Contacts between groups whose grounds overlap ([contacts]) settled by their strategies ([engage]): how each went. */
    ENGAGEMENTS("engagements by strategy"),
    ENGAGEMENTS_PASSED_UNSEEN("engagements passed unseen"),
    ENGAGEMENTS_SCOUTED("engagements where one watched the other unseen"),
    ENGAGEMENTS_GAVE_WAY("engagements where one gave way"),
    ENGAGEMENTS_AMBUSHED("engagements opened by an ambush"),
    ENGAGEMENTS_FOUGHT("engagements fought"),
    HEXES_YIELDED("hexes given up by packs holding ground"),
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
        TURNED_BACK_BY("bands turned back by "), DOGS_GONE_WILD("dogs gone wild as ", " packs"), DOGS_LEFT("dogs left hungry camps for ", " packs"),
        /** How packs of a kind come and go (report only): grown young gone alone, packs split off, lone pairs, ground taken, packs ended (and those without ground). */
        PACK_LEFT_ALONE("pack young left alone, "), PACK_SPLIT_OFF("packs split off, "), PACK_PAIRED("lone hunters paired, "),
        PACK_SETTLED("packs settled ground, "), PACK_ENDED("packs ended, "), PACK_ENDED_HOMELESS("packs ended without ground, "),
        /**
         * Of kinds that also live settled (report only): grown young that left settled ground as roamers, roamers that settled in
         * where there was room.
         */
        ROAMERS_LEFT("roamers left settled ground, "), ROAMERS_SETTLED("roamers settled in, "),
        /** Kills robbed (report only): by the thief's kind and from the victim's, with the kg taken. */
        KILLS_STOLEN_BY("kills stolen by "), KILLS_STOLEN_FROM("kills stolen from "), KG_STOLEN_BY("kg of kills stolen by "), KG_STOLEN_FROM("kg of kills stolen from "),
        RITES_HELD("rites held, band "), RITES_CUT_SHORT("rites cut short, band "),
        RITES_CUT_SHORT_PERCENT_GIVEN("percent of days given to rites cut short, band "),
        CUSTOMS_DAYS_ASKED("tenths of days customs asked: "), CUSTOMS_DAYS_GIVEN("tenths of days customs got: "),
        CUSTOMS_DAYS_TO_HAND("tenths of days to hand for customs, ");

        fun key(of: String) = before + of + after
        /** Its tally of [of] in [WeekStats.meetings], for a report to read back. */
        fun tally(of: String) = TallyKey.raw(key(of))
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
        KILLS_OF_RESIDENTS("kills of residents"), KILLS_OF_KEPT("kills of kept animals"), CHILDREN_DIED("children died"), MEAT_EATEN("meat eaten, kg"),
        PLANTS_EATEN("plants eaten, kg"), GRAIN_REAPED("grain reaped, kg"), PLANTS_LEACHED("plants leached, kg"), FISH("fish, kg"),
    }

    /** What a hunting kind's diet asked for ([ASKED]) and got of each part, kg (meat includes fish). */
    enum class DietPart(val label: String) { ASKED("asked"), MEAT("meat"), SMALL_PREY("small prey"), CARRION("carrion"), PLANTS("plants") }
    fun diet(s: Species, part: DietPart) = of(s.name, "diet ${part.label}, kg")
    /** Bands' plant food got by the plant food it came from, at what it feeds, kg of need. */
    fun plantsFrom(f: PlantFrom) = TallyKey.raw("bands' plants from ${f.label}")
    /** What bands' kills of big game by month ([bandKills]) count: the kills, and their meat brought in by weight, lean and fat. */
    enum class KillPart(val label: String) { KILLS("kills"), LEAN_KG("lean, kg"), FAT_KG("fat, kg") }
    /** Bands' kills of big game in [month] (0 from midwinter): [part] of them. */
    fun bandKills(month: Int, part: KillPart) = TallyKey.raw("bands' kills in month ${month + 1}, ${part.label}")
    /** Bands' kills of big game of the kind named [kind]: [part] of them. */
    fun bandKillsOf(kind: String, part: KillPart) = of(kind, "bands' kills, ${part.label}")

    /** What bands' tallies by the kind of country they are in ([bandsIn]) and by their clothing ([bandsClad]) count. */
    enum class Bands(val label: String) {
        /** Food wanted, and got of meat (with small game and carrion), plants and fish, kg. */
        ASKED("asked"), MEAT("meat"), PLANTS("plants"), FISH("fish"),
        BAND_WEEKS("band-weeks"), PEOPLE_WEEKS("people-weeks"), BORN("born"),
        /** Band-weeks it moved camp. */
        MOVES("camp moves"),
        /** Deaths foreseen a year ahead ([HexGroup.foreseen]): people-weeks times the share foreseen to die, thousandths. */
        FORESEEN("foreseen deaths, 1/1000"),
        /** Of [FORESEEN], those foreseen of hunger and of exposure ([YearAhead.causes]). */
        FORESEEN_HUNGER("foreseen deaths of hunger, 1/1000"), FORESEEN_EXPOSURE("foreseen deaths of exposure, 1/1000"),
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
        /** What keeping warm adds to a band's food ([Warmth.extra]), and the shelter its survival pass aimed at ([Band.shelterAim]), summed over band-weeks. */
        WARM_EXTRA("keeping warm extra, 1/1000"), AIM("shelter aimed, 1/100"),
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
    fun bandsCladDied(clad: Clad, cause: DeathReason) = ofCause(bandsCladDied(clad), cause)
    fun bandsCladDied(clad: Clad) = TallyKey.raw("bands ${clad.label}:$DIED_OF")
    /** How well a band was fed through the week, its fat drawn on ([HexGroup.fed]): the first bin it falls [below]. */
    enum class Fed(val label: String, val below: Float) {
        STARVING("under half", 0.5f), SHORT("half to 0.9", 0.9f), FED("0.9 and over", Float.POSITIVE_INFINITY);
        companion object { fun of(fed: Float) = entries.firstOrNull { fed < it.below } ?: FED }
    }
    /** Bands' tallies by how well they were fed. */
    fun bandsFed(fed: Fed, what: Bands) = TallyKey.raw("bands fed ${fed.label}:${what.label}")
    /** Bands' deaths of [cause] by how well they were fed; [bandsFedDied] (fed) starts all of them. */
    fun bandsFedDied(fed: Fed, cause: DeathReason) = ofCause(bandsFedDied(fed), cause)
    fun bandsFedDied(fed: Fed) = TallyKey.raw("bands fed ${fed.label}:$DIED_OF")
    /**
     * How a band lives: [SETTLED], at home in its houses or at a camp it has kept half a year or more ([HexGroups.bandLiving]);
     * else [MOBILE]. The line is the report's, not the model's: nothing chooses by it.
     */
    enum class Living(val label: String) { SETTLED("settled"), MOBILE("mobile") }
    /** Bands' tallies by how they live. */
    fun bandsLiving(way: Living, what: Bands) = TallyKey.raw("bands ${way.label}:${what.label}")
    /** Bands' deaths of [cause] by how they live; [bandsLivingDied] (way) starts all of them. */
    fun bandsLivingDied(way: Living, cause: DeathReason) = ofCause(bandsLivingDied(way), cause)
    fun bandsLivingDied(way: Living) = TallyKey.raw("bands ${way.label}:$DIED_OF")
    /** Bands' tallies by how they choose ([Mind]). */
    fun bandsMind(mind: Mind, what: Bands) = TallyKey.raw("bands of mind ${mind.label}:${what.label}")
    /** Bands' tallies by the way of life they live ([Band.livelihood]): what camp moves each way makes, against the record. */
    fun bandsLivelihood(way: Livelihood, what: Bands) = TallyKey.raw("bands living by ${way.name.lowercase()}:${what.label}")
    /** What the calibration of bands' forecasts books in each [ForeseenBin] ([bookCalibration]). */
    enum class Calib(val label: String) {
        /** People in the band when it foresaw; the deaths it foresaw among them, thousandths; deaths, and hunger's, booked against each forecast its people made in the year before them, thousandths. */
        PEOPLE_WEEKS("people-weeks"), FORESEEN("foreseen dead, 1/1000"), DIED("died in the year after, 1/1000"), HUNGER("died of hunger in the year after, 1/1000")
    }
    /** Bands' forecasts of mind [mind] in [bin], [what] ([bookCalibration]). */
    fun calibration(mind: Mind, bin: ForeseenBin, what: Calib) = TallyKey.raw("foreseen calibration ${mind.label}:${bin.label}:${what.label}")
    /** What moved bands' larders of mind [mind] in [season], by [why], person-days of the need before keeping warm ([Band.larderFlow]). */
    fun larderFlow(mind: Mind, season: Season, why: LarderMove) = TallyKey.raw("larder flow ${mind.label}:${season.name}:${why.label}")
    /** What moved bands' stores of kind [store] in [season], by [why], person-days of the need before keeping warm ([Band.storeFlow]). */
    fun storeFlow(store: Store, season: Season, why: StoreFlow) = TallyKey.raw("store flow ${store.name}:${season.name}:${why.label}")
    /**
     * Bands' drying of fresh meat and fish in their weeks with some left ([keepAndSpoil]): the fresh left, what their way of drying
     * would have dried of it, what the days and the drying rate let them dry, what they dried, all person-days of the need;
     * the way and the rate (tenths, weighed by the fresh left); and the weeks counted, those where the days held them back.
     */
    enum class Drying(val label: String) {
        FRESH("fresh left"), BY_WAY("by its way"), BY_DAYS("by its days"), DRIED("dried"), WAY("way"), RATE("rate"), WEEKS("weeks"), DAYS_BOUND("held back by days")
    }
    fun drying(what: Drying) = TallyKey.raw("bands drying:${what.label}")
    /** What a band's larder held when its people died of hunger, days of its need before keeping warm. */
    enum class LarderAt(val label: String, val below: Float) {
        EMPTY("under a day", 1f), SOME("1-10 days", 10f), FULL("10 days and over", Float.POSITIVE_INFINITY);
        companion object { fun of(days: Float) = entries.first { days < it.below } }
    }
    /** Hunger deaths in bands of mind [mind] by what their larder held then ([LarderAt]). */
    fun starvedAt(mind: Mind, at: LarderAt) = TallyKey.raw("starved at larder ${mind.label}:${at.label}")
    /** Hunger deaths in bands of mind [mind] that moved camp in the month before. */
    fun starvedMoved(mind: Mind) = TallyKey.raw("starved after a move ${mind.label}")
    /** Bands of mind [mind] [offset] weeks from a camp move (below 0 before it), [what] ([moveCensus]). */
    fun aroundMove(mind: Mind, offset: Int, what: AroundMove) = TallyKey.raw("around a move ${mind.label}:$offset:${what.label}")
    enum class AroundMove(val label: String) {
        /**
         * Band-weeks; the share of the need they ate, thousandths; their larders, tenths of a day of the need before keeping warm;
         * the band-weeks with a forage yield seen, and the yield ([Band.yieldNow]: days of the need a day of all hands' foraging brings), thousandths.
         */
        WEEKS("band-weeks"), FED("fed, 1/1000"), LARDER("larder days, 1/10"), YIELD_WEEKS("yield weeks"), YIELD("yield, 1/1000")
    }
    /** Hunger deaths within a month of a camp move in bands of mind [mind] already short of food in the weeks before it ([HUNGRY_BEFORE]). */
    fun starvedMovedHungry(mind: Mind) = TallyKey.raw("starved after a move, short before ${mind.label}")
    /**
     * Hungry bands followed for [MOVE_WEEKS] weeks ([moveCensus]): fed below [HUNGRY_BEFORE] over the weeks before, they
     * moved camp that week or stayed, with a larder then of under a day, 1-5 days or more ([LarderAt] at its own bounds).
     */
    enum class HungryCohort(val label: String, val moved: Boolean, val below: Float) {
        MOVED_EMPTY("moved, larder under a day", true, 1f), MOVED_SOME("moved, 1-5 days", true, 5f), MOVED_MORE("moved, 5 days and over", true, Float.POSITIVE_INFINITY),
        STAYED_EMPTY("stayed, larder under a day", false, 1f), STAYED_SOME("stayed, 1-5 days", false, 5f), STAYED_MORE("stayed, 5 days and over", false, Float.POSITIVE_INFINITY);
        companion object { fun of(moved: Boolean, larder: Float) = entries.first { it.moved == moved && !(larder >= it.below) } }
    }
    /** Bands entering a cohort; people-weeks after; the share of the need eaten, thousandths, people-weighted; hunger and all deaths. */
    enum class HungryFollowed(val label: String) { ENTERED("entered"), PEOPLE_WEEKS("people-weeks"), FED("fed, 1/1000"), STARVED("hunger deaths"), DIED("deaths") }
    /** Bands by their camp moves lately ([Band.movesLately], over about a season): none, 1-2, 3-6, 7 and more. */
    enum class MovedLately(val label: String, val below: Float) {
        NONE("none", 0.5f), FEW("1-2", 2.5f), SOME("3-6", 6.5f), MANY("7 and more", Float.POSITIVE_INFINITY);
        companion object { fun of(n: Float) = entries.first { n < it.below } }
    }
    /** People-weeks; larder and fat, tenths of a day, people-weighted; hunger and all deaths. */
    enum class Lately(val label: String) { PEOPLE_WEEKS("people-weeks"), LARDER("larder, 1/10 day"), FAT("fat, 1/10 day"), STARVED("hunger deaths"), DIED("deaths"), STORE_AIM("store aim, 1/10 day") }
    /** What moved the larders of bands by their camp moves lately ([MovedLately]), person-days of the need: [LarderMove]. */
    fun movedLatelyFlow(m: MovedLately, why: LarderMove) = TallyKey.raw("bands moved lately ${m.label}:larder flow ${why.label}")
    /** Their hands' days by [Activity] (null: all their hands' days), hundredths of a person-day. */
    fun movedLatelyDays(m: MovedLately, a: Activity?) = TallyKey.raw("bands moved lately ${m.label}:days ${a?.label ?: "all"}")
    /**
     * What a day's work brought bands, and why ([Band.workRate]): what a day of all its hands' foraging brings, days of its
     * people's need ([Band.yieldNow]); the share of their work out of doors the hunger and the cold leave ([Band.hungerPace],
     * [Band.coldPace]); how well they know the plants of their camp's country ([plantKnow]).
     */
    enum class WorkRate(val label: String) { YIELD("forage yield"), HUNGER_PACE("hunger pace"), COLD_PACE("cold pace"), PLANT_KNOW("plants known") }
    /** [WorkRate] of bands by [MovedLately], hundredths (thousandths overflow), people-weighted; with [what] null, the people-weeks seen. */
    fun movedLatelyRate(m: MovedLately, what: WorkRate?) = TallyKey.raw("bands moved lately ${m.label}:rate ${what?.label ?: "people-weeks"}")
    /** Deaths of [d], [lag] weeks after the band foresaw [what] ([Warn]) in [bin], for whether bands saw them coming ([bookWarning]). */
    fun warnDied(d: WarnDeaths, lag: Int, what: Warn, bin: WarnBin) = TallyKey.raw("warning:${d.label}:lag $lag:${what.name.lowercase()}:${bin.label}")
    /** The same deaths' band's [what] ([WARN_STATE]), hundredths, summed over the deaths where it was seen; with [count], those deaths. */
    fun warnReserves(d: WarnDeaths, lag: Int, what: Warn, count: Boolean) = TallyKey.raw("warning:${d.label}:lag $lag:reserves:${what.name.lowercase()}${if (count) ":deaths" else ""}")
    /** People-weeks of bands that foresaw [what] in [bin] when they chose this week's place ([weekWarning]); their deaths that week are [warnDied] at lag 0. */
    fun warnPeopleWeeks(what: Warn, bin: WarnBin) = TallyKey.raw("warning:people-weeks:${what.name.lowercase()}:${bin.label}")
    /** How a band holds its dead: leaves the camp where they die ([Tradition.LEAVE_DEAD] without [Tradition.BURIAL]), leaves them but buries them too (burial wins), or neither. */
    enum class DeadWay(val label: String) {
        LEAVES("leaves its dead"), BURIES_TOO("leaves its dead, buries too"), NEITHER("does not leave its dead");
        companion object { fun of(leaves: Boolean, buries: Boolean) = if (!leaves) NEITHER else if (buries) BURIES_TOO else LEAVES }
    }
    /**
     * Unlucky camps, for whether bands leave camps they have built up after a death ([bookUnlucky]): band-weeks; those with a
     * death; camps marked unlucky, and of them those at the band's own houses; band-weeks while a mark lasts, on the marked
     * camp or away from it, and the same for marks at the band's houses.
     */
    enum class Unluck(val label: String) {
        BAND_WEEKS("band-weeks"), DEATH_WEEKS("with a death"), MARKED("marked"), MARKED_HOUSES("marked at its houses"),
        ON("weeks on the marked camp"), AWAY("weeks away"), ON_HOUSES("weeks on the marked houses"), AWAY_HOUSES("weeks away from them")
    }
    fun unlucky(living: Living, way: DeadWay, what: Unluck) = TallyKey.raw("unlucky camps:${living.label}:${way.label}:${what.label}")
    fun movedLately(m: MovedLately, season: Season, what: Lately) = TallyKey.raw("bands moved lately ${m.label}:${season.name}:${what.label}")
    fun hungryCohort(c: HungryCohort, what: HungryFollowed) = TallyKey.raw("hungry bands ${c.label}:${what.label}")
    /** People-weeks of bands of mind [mind] within a month of a camp move. */
    fun movedWeeks(mind: Mind) = TallyKey.raw("people-weeks after a move ${mind.label}")
    /** A planning band's days in each tier of its week beyond its essentials ([planDays]), tenths of a day. */
    fun planTier(t: PlanTier) = TallyKey.raw("plans:${t.label}")
    /** Bands' deaths of [cause] by how they choose; [bandsMindDied] (mind) starts all of them. */
    fun bandsMindDied(mind: Mind, cause: DeathReason) = ofCause(bandsMindDied(mind), cause)
    fun bandsMindDied(mind: Mind) = TallyKey.raw("bands of mind ${mind.label}:$DIED_OF")
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
    // (The keys of the tallies every band books every week are made once: building their text was a large part of a band-week.)
    private val herdingTimeKeys = Array(Herding.entries.size) { h -> Array(Activity.entries.size) { a ->
        TallyKey.raw("bands ${Herding.entries[h].label}:time ${Activity.entries[a].label}") } }
    fun bandsHerdingTime(h: Herding, a: Activity) = herdingTimeKeys[h.ordinal][a.ordinal]
    private val herdingTotalKeys = Array(Herding.entries.size) { h -> Array(TimeTotal.entries.size) { t ->
        TallyKey.raw("bands ${Herding.entries[h].label}:time ${TimeTotal.entries[t].label}") } }
    fun bandsHerdingTime(h: Herding, t: TimeTotal) = herdingTotalKeys[h.ordinal][t.ordinal]
    /** Bands' deaths of [cause] by whether they keep a herd, the [young] apart; [bandsHerdingDied] (h, young) starts all of them. */
    fun bandsHerdingDied(h: Herding, young: Boolean, cause: DeathReason) = ofCause(bandsHerdingDied(h, young), cause)
    fun bandsHerdingDied(h: Herding, young: Boolean) = TallyKey.raw("bands ${h.label}:${if (young) "children " else "grown "}$DIED_OF")
    /**
     * How well two bands that met understand each other ([Language.understanding]): the first bin it falls [below]. For the
     * identity census; the line is the report's, not the model's: nothing chooses by it.
     */
    enum class Understood(val label: String, val below: Float) {
        LITTLE("understood under 0.5", 0.5f), PART("understood 0.5 to 0.85", 0.85f), WELL("understood 0.85 and over", Float.POSITIVE_INFINITY);
        companion object { fun of(u: Float) = entries.firstOrNull { u < it.below } ?: WELL }
    }
    /** How many of their [Tradition]s two bands that met hold differently: the first bin the count is [below]. */
    enum class CustomsApart(val label: String, val below: Int) {
        SAME("same customs", 1), FEW("1-2 customs apart", 3), MANY("3+ customs apart", Int.MAX_VALUE);
        companion object { fun of(n: Int) = entries.first { n < it.below } }
    }
    /**
     * How wary of each other two bands that met were ([Regard.wariness], the warier side's): how much readier than at its least
     * either was to fight the other, given what drove it. The first bin it falls [below]; for the identity census.
     */
    enum class Wary(val label: String, val below: Float) {
        LITTLE("wary under 0.5", 0.5f), SOME("wary 0.5 to 1", 1f), MORE("wary 1 to 1.5", 1.5f), MOST("wary 1.5 and over", Float.POSITIVE_INFINITY);
        companion object { fun of(w: Float) = entries.firstOrNull { w < it.below } ?: MOST }
    }
    /** What came of two bands meeting, or of a raid set on a home, for the identity census. */
    enum class MeetingEnd(val label: String) { MET("met"), RAIDED("raided at meeting"), PARTNERS("exchanged partners"), RAIDED_HOME("raided at home") }
    /** Meetings of bands that ended in [what], by how well they understood each other, how far apart their customs were, and how wary they were. */
    fun meetingsBy(u: Understood, what: MeetingEnd) = TallyKey.raw("meetings ${u.label}:${what.label}")
    fun meetingsBy(c: CustomsApart, what: MeetingEnd) = TallyKey.raw("meetings ${c.label}:${what.label}")
    fun meetingsBy(w: Wary, what: MeetingEnd) = TallyKey.raw("meetings ${w.label}:${what.label}")
    /**
     * How a band's chores fared against what they asked of its week ([HexGroups.countSqueeze]): the band-weeks each asked for
     * days, those it got less than [SQUEEZED] of its ask in a week that ran out of days, and the days asked and not got, tenths;
     * and of the days asked, those the survival pass asked first ([survivalDays]), tenths.
     */
    enum class Squeeze(val label: String) { ASKED_WEEKS("weeks asked"), SHORT_WEEKS("weeks short"), ASKED_DAYS("days asked"), MISSING_DAYS("days missing"),
        FIRST_DAYS("days asked first") }
    private val squeezeKeys = Array(Activity.entries.size) { a -> Array(Squeeze.entries.size) { k ->
        TallyKey.raw("band squeeze:${Activity.entries[a].label}:${Squeeze.entries[k].label}") } }
    fun squeeze(a: Activity, what: Squeeze) = squeezeKeys[a.ordinal][what.ordinal]
    /** A band's spare days over the last season or so ([Band.spareMean], a share of its hands' days): the first bin it falls [below]. */
    enum class Spare(val label: String, val below: Float) {
        NONE("under 5%", 0.05f), LITTLE("5 to 15%", 0.15f), SOME("15% and over", Float.POSITIVE_INFINITY);
        companion object { fun of(share: Float) = entries.firstOrNull { share < it.below } ?: SOME }
    }
    /** Bands' tallies by their spare days; [bandsSpareDied] (spare) starts all their deaths. */
    fun bandsSpare(spare: Spare, what: Bands) = TallyKey.raw("bands spare ${spare.label}:${what.label}")
    fun bandsSpareDied(spare: Spare, cause: DeathReason) = ofCause(bandsSpareDied(spare), cause)
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
    fun bandsWarmedDied(spare: Spare, w: Warmed, cause: DeathReason) = ofCause(bandsWarmedDied(spare, w), cause)
    fun bandsWarmedDied(spare: Spare, w: Warmed) = TallyKey.raw("bands spare ${spare.label} warmed ${w.label}:$DIED_OF")
    /**
     * A band's foraging against its week's plan ([countFoodTime]), tenths of people-days summed over band-weeks: the
     * band-weeks, the days the plan left it, those it spent and those past them, the band-weeks it went more than 5% past
     * them, and what kills beyond and short of those expected, extra hauling and digging soaks took.
     */
    enum class FoodTime(val label: String) {
        WEEKS("band-weeks"), ROOM("days left it"), SPENT("days spent"), OVER("days past"), OVER_WEEKS("weeks past"),
        KILLS_OVER("kills beyond expected"), KILLS_UNDER("kills short of expected"), HAUL("hauling"), DIG("water: digging soaks, drawing from wells, carrying"),
        HUNT_SEARCH("hunters searching"), HUNT_HANDLE("hunters handling"), GATHER_SEARCH("gatherers searching"), GATHER_HANDLE("gatherers handling"),
        PLANT_SEEK("of it finding plant food"), PLANT_WALK("of it walking to plant food"), FED("food got, kg of need"),
        HUNT_FED("hunters' take, kg of need"), GATHER_FED("gatherers' take, kg of need"), CHILD_FED("children's take, kg of need")
    }
    fun foodTime(k: FoodTime) = TallyKey.raw("band foraging time:${k.label}")
    /**
     * Bands' hungry weeks (fed under [Thresholds.HUNGRY] after eating), counted at the week's end ([countHungryWeek]) with what
     * was unused: band-weeks, those whose hunters' party took up no food (their hands, tenths of people, idle), those with
     * foods the gatherers ranked out (the best of them: its kind, and what it paid a day of handling against the cut-off the gatherers stopped at), those
     * holding a herd, those with something in the larder, and the hunger deaths of the week's people (tenths of a person).
     */
    enum class Hungry(val label: String) {
        WEEKS("hungry band-weeks"), HUNTERS_IDLE_WEEKS("hunters took up nothing"), HUNTERS_IDLE_HANDS("their idle hands"),
        RANKED_OUT_WEEKS("gatherers ranked foods out"), RANKED_OUT_RATIO("best one's pay as a percent of the cut-off, summed"),
        HERD_WEEKS("holding a herd"), HERD_HEADS("herd heads"), LARDER_WEEKS("larder not empty"), PEOPLE("people")
    }
    fun hungry(k: Hungry) = TallyKey.raw("hungry band-weeks:${k.label}")
    /**
     * Bands' small game over the year ([countSmallGame]): band-weeks, people-days, the weeks the children's party caught any,
     * tenths of kg caught by the children and by the grown gatherers, and tenths of kg of need the bands asked for.
     */
    enum class SmallGame(val label: String) {
        WEEKS("band-weeks"), PEOPLE_DAYS("people-days"), KIDS_WEEKS("weeks the children caught any"),
        KIDS_KG("children's catch, kg"), GROWN_KG("grown gatherers' catch, kg"), ASKED("asked, kg of need")
    }
    fun smallGame(k: SmallGame) = TallyKey.raw("bands' small game:${k.label}")
    /** Hungry band-weeks by the kind of food the gatherers' best ranked-out food was ([Hungry]). */
    fun hungryBest(f: Food) = TallyKey.raw("hungry band-weeks:best ranked-out food ${f.label}")
    /**
     * What each kind of food paid the gatherers against the rate they stopped at, by month (first pass of the week, [noteUnused]):
     * band-weeks it was on offer, those it was taken up in, and its best offer's pay a day of handling as a percent of the cut-off, summed.
     */
    enum class Pay(val label: String) { OFFERED("weeks offered"), TAKEN("weeks taken up"), PERCENT("pay percent of the cut-off, summed") }
    fun pay(f: Food, month: Int, k: Pay) = TallyKey.raw("gatherers' food pay:${f.label}:month $month:${k.label}")
    /**
     * Bands' grain, summed over the year: kilos as weighed, reaped from wild stands and from sown fields while foraging, and
     * of the crop brought in whole ([HexGroups.harvestCrop]); and tenths of people-days at each (foraging's include finding it
     * and walking to it), and of all of them grinding, at the quern's rate.
     */
    enum class Grain(val label: String) {
        WILD_KG("wild, kg"), FIELD_KG("sown, foraged, kg"), CROP_KG("crop brought in, kg"),
        WILD_DAYS("wild, days"), FIELD_DAYS("sown, foraged, days"), CROP_DAYS("crop brought in, days"), GRIND_DAYS("grinding, days")
    }
    fun grain(k: Grain) = TallyKey.raw("bands' grain:${k.label}")
    /** How far a band's shelters stood at the week's end ([Band.shelter]): the first bin it falls [below]. The report's line. */
    enum class Hut(val label: String, val below: Float) {
        NONE("no hut", 0.5f), LIGHT("light hut", 1.05f), PART("part thatched", 1.5f), THATCHED("thatched", Float.POSITIVE_INFINITY);
        companion object { fun of(s: Float) = entries.firstOrNull { s < it.below } ?: THATCHED }
    }
    /** Bands' tallies by how far their shelters stood; [bandsHutDied] (hut) starts all their deaths. */
    fun bandsHut(h: Hut, what: Bands) = TallyKey.raw("bands shelter ${h.label}:${what.label}")
    fun bandsHutDied(h: Hut, cause: DeathReason) = ofCause(bandsHutDied(h), cause)
    fun bandsHutDied(h: Hut) = TallyKey.raw("bands shelter ${h.label}:$DIED_OF")
    /** The quarter of the land hexes, by the food their ground gives people ([HexGroups.hexFood]), that a band's hex falls in. */
    enum class FoodQuarter(val label: String) { POOREST("poorest"), POOR("poor"), RICH("rich"), RICHEST("richest") }
    /** Bands' tallies by their hex's food quarter: whether births and deaths answer to the ground. */
    fun bandsFoodQ(q: FoodQuarter, what: Bands) = TallyKey.raw("bands food quarter ${q.label}:${what.label}")
    fun bandsFoodQDied(q: FoodQuarter, cause: DeathReason) = ofCause(bandsFoodQDied(q), cause)
    fun bandsFoodQDied(q: FoodQuarter) = TallyKey.raw("bands food quarter ${q.label}:$DIED_OF")
    /** What bands of a food quarter foraged in a season, need-kg: what they wanted ([QuarterFood.ASKED]) and what each source brought. */
    enum class QuarterFood(val label: String) { ASKED("asked"), MEAT("meat"), FISH("fish"), FRUIT("fruit"), MAST("mast"), ROOTS("bulbs and roots"), SEED("seed and grain"), FINDS("small finds") }
    fun bandsFoodQSeason(q: FoodQuarter, s: inflation.world.Season, f: QuarterFood) = TallyKey.raw("bands food quarter ${q.label} ${s.name.lowercase()}:${f.label}")
    /**
     * What thatching on to the full would have done, by the survival pass's own reckoning, in the band-weeks it stopped short
     * ([WEEKS]): deaths averted this week of exposure and of hunger and through the year after, people, 1/10000 (a negative
     * brings deaths); the days it would take and those the pass left, and the weeks the band expects to stay on, tenths; and
     * the band-weeks it would avert deaths and its days fit in those left, would avert them but not fit, or would not.
     */
    enum class Thatch(val label: String) {
        WEEKS("band-weeks"), EXPOSURE("exposure averted"), HUNGER("hunger averted"), YEAR("year averted"), DAYS("days"), LEFT("days left"),
        STAY("stay weeks"), WORTH_FITS("worth, fits"), WORTH_SHORT("worth, short of days"), NOT_WORTH("not worth")
    }
    fun thatchTrace(h: Hut, what: Thatch) = TallyKey.raw("thatch trace ${h.label}:${what.label}")
    /** Whether a band keeps a herd ([Herd.herd]). */
    enum class HerdKept(val label: String) { HERDING("keeping a herd"), NONE("keeping none") }
    /** Bands' tallies by whether they keep a herd; [bandsHerdKeptDied] (herding) starts all their deaths. */
    fun bandsHerdKept(h: HerdKept, what: Bands) = TallyKey.raw("bands ${h.label}:${what.label}")
    fun bandsHerdKeptDied(h: HerdKept, cause: DeathReason) = ofCause(bandsHerdKeptDied(h), cause)
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
    /** The key counting deaths of [cause] among those [started] by a `bands...Died` key without a cause. */
    private fun ofCause(started: TallyKey, cause: DeathReason) = TallyKey.raw(started.text + cause.label)

    /** How bands spent their hands' days, tenths of a day: on each [Activity], and in all or left over. */
    private val activityTimeKeys = Array(Activity.entries.size) { bandTime(Activity.entries[it].label) }
    private val totalTimeKeys = Array(TimeTotal.entries.size) { bandTime(TimeTotal.entries[it].label) }
    fun bandTime(a: Activity) = activityTimeKeys[a.ordinal]
    fun bandTime(t: TimeTotal) = totalTimeKeys[t.ordinal]
    private fun bandTime(what: String) = TallyKey.raw("band time:$what")
    enum class TimeTotal(val label: String) { ALL("all"), LEFT("left"), LOST("lost to cold and hunger") }

    /** Where people's hides came from. */
    enum class HideSource(val label: String) { RESIDENTS("residents"), SMALL_PELTS("small pelts"), HERDS("herds") }
    /** Hide people got from [source], grams. */
    fun hide(source: HideSource) = TallyKey.raw("people hide:${source.label}")
}
