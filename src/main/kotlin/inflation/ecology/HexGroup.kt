package inflation.ecology

import inflation.enumFloats
import inflation.world.Hex
import inflation.world.HexArray
import inflation.world.Place
import inflation.world.get
import inflation.world.set
import inflation.world.HexLand
import kotlin.math.exp
import kotlin.math.max

/** A pack, pride or band of people on the hexes: where it stands (a place: a hex, or its low or high ground) and who it is. */
class HexGroup(val id: Int, val species: Species, var place: Place, var adults: Int, var young: Int) {
    val size get() = adults + young
    val alive get() = adults > 0
    /** Fed last week, 0..1; body reserves, days of food. */
    var fed = 1f
    var fat = 0f
    /** Days of its body's protein it has burnt, its fat spent ([Hazards.hunger]); won back before fat. */
    var spent = 0f
    /** What it ate this week beyond its needs, days of food: laid on as reserves (see [liveWeek]). */
    var surplus = 0f
    /** The hexes it passed through this week, in order (its own last), and the day it entered each. */
    var path = HexArray.EMPTY
    var days = FloatArray(0)
    /** The height it gained this week, from the place it left to the place it came to, m (none going down): see [walkCost]. */
    var climbedM = 0f
    /** Meat it took this week from other hunters it met on the way, kg (see [enemiesMeet]). */
    var meatMet = 0f
    /**
     * A pack's carcass: what it left last week of its kills where it ended the week, kg as its need counts them ([creditShare]),
     * lying in the carrion there at [carcassAt] (-1: none) when the place's [HexSmallLife.carrionKeptLog] stood at
     * [carcassLog]; it eats what the place's rot and scavengers left of it as its next week starts, if it passes there
     * ([eatCarcass]). [carcassKept]: the share of its carcasses it has found again, of late, which it weighs a kill's
     * leftovers by ([offerFoods]).
     */
    var carcassKg = 0f
    var carcassAt = -1
    var carcassLog = 0.0
    var carcassKept = 0f
    /** Whether [meatMet] is eaten, stored or left by now this week (for the books, which count it in hand until then; a loaded group's is spent). */
    @Transient var meatSpent = true
    /** For a hunter: the hex at the heart of the ground it holds, -1 while it holds none. */
    var home = Hex.NONE
    /** Days since it last drank. */
    var dry = 0f
    /** Weeks in a row it has gone hungry (fed under half); the last hexes it hunted in, newest first. */
    var lean = 0
    val recent = HexArray(4)
    /** Lineage tag for learning, -1 for none; the group it split from (-1 for none). */
    var tag = -1
    var parent = -1
    /** The places it remembers (see [PlaceMemory]). */
    val memory = PlaceMemory()
    /** Its size when it merged into another (-1 if it did not), for following a choice's outcome. */
    var mergedSize = -1
    /** The group it merged into, the step it did (else -1): for following what became of its members. Not saved: read in the same step. */
    @Transient var mergedInto = -1
    /**
     * A band's luck in its own week's births and deaths, summed since it began: born less died as
     * drawn, against as expected from the week's odds (for training, which judges people by what
     * their choices made likely, not by chance; animals keep 0).
     */
    var luck = 0f
    /**
     * A band's deaths as the week's odds made them likely, summed since it began (training, with [luck]; not saved, and so not made until first added to): of the causes in
     * [DeathClass.KIT], in [DeathClass.FOOD], and of all of them, at those indices ([LOST_ALL] for all).
     */
    @Transient var lost: FloatArray? = null
    /** Adds [heads] to the deaths expected in class [c] (a [DeathClass] ordinal, or [LOST_ALL]). */
    fun addLost(c: Int, heads: Float) { (lost ?: FloatArray(3).also { lost = it })[c] += heads }
    /** The deaths expected so far in class [c] ([lost]). */
    fun lost(c: Int) = lost?.get(c) ?: 0f
    /** A band's foreseen survival: the share of its people alive a year ahead as it stands ([foreseenSurvival]); 1 for animals. */
    var foreseen = 1f
    /** The month its young are born in; -1 until its first week (then its kind's). */
    var birthMonth = -1
    /** Share of this year's birth season its mothers have borne (as [HexFauna]'s residents keep it). */
    var bred = 0f
    /** For a pack: its ways this week, 1 the hand-written way (see [AnimalWay]). */
    val will = FloatArray(ANIMAL_WAYS_OUT) { 1f }
    /** For a band: food on hand, kg, by kind ([Store]). */
    val stores = enumFloats<Store>()
    /** The kg each kind of its food weighs for each kg of its worth (see [addFood]). */
    val storeKgPer = enumFloats<Store> { 1f }
    /** All food on hand, kg; setting it scales every kind alike (gifts, raids, splits), or stores it dried. */
    var larder: Float
        get() = stores.sum()
        set(v) {
            val s = stores.sum()
            if (s > 0f) {
                val k = max(0f, v) / s
                for (i in Store.entries) stores[i] *= k
            } else {
                stores.fill(0f)
                stores[Store.DRIED] = max(0f, v)
            }
        }
    /** A band's skills, tools and camp: set for every group of people ([Species.human]) and only for them. */
    var band: Band? = null
    /** For kinds that follow people's camps: the share of it bold enough to come close, 0..1 (-1 until first needed: its kind's share). */
    var bold = -1f
    /** Its temperament ([Temperament]): a founder's by chance, an offshoot's from its parent. */
    var temper = Temperament.founder(id, 0L)
    /**
     * For a band whose moves are learned: how its mover saw the place it chose this week ([HexPolicy.planned]), for its ways
     * to read with the rest of the week's plan; null where its moves are hand-written. (Made anew each week before it is read.)
     */
    @Transient var plan: FloatArray? = null
    /**
     * Whether its policy drew its place by chance this week (a choice recorded to learn from, see [HexPolicy.choose]): then a band's
     * will to explore leaves the draw be. (Made anew each week before it is read.)
     */
    @Transient var drawn = false
    /** For hunters: meetings with game of each kind ([Species.index]), as of [herdsMetOn], fading over [SEARCH_DAYS] (null: none yet; see [SEARCH_HALF]). */
    var herdsMet: FloatArray? = null
    var herdsMetOn = 0.0
    /** Its grown ones' odds of dying in a step, as its last week's hazards had them (0 before its first week): what a life risked is set against. */
    var deathOdds = 0f
    /** How often it has come on game of [k] lately, at [day]. */
    fun metLately(k: Int, day: Double): Float = herdsMet?.let { it[k] * exp(-(day - herdsMetOn) / SEARCH_DAYS).toFloat() } ?: SEARCH_HALF
    /** Counts the meetings in [counts] (by [Species.index]) at [day]. */
    fun metGame(counts: FloatArray, day: Double) {
        val m = herdsMet ?: FloatArray(Catalogue.all.size) { SEARCH_HALF }.also { herdsMet = it; herdsMetOn = day }
        val fade = exp(-(day - herdsMetOn) / SEARCH_DAYS).toFloat()
        for (i in m.indices) m[i] = m[i] * fade + counts[i]
        herdsMetOn = day
    }
    /** How much more food it needs this week to keep warm, 1 when comfortable (see [coldNeed]). */
    var warmth = 1f
    val dailyNeedKg get() = species.dailyIntakeKg * (adults + 0.5f * young) * warmth
}

/**
 * How a group of a kind chooses where to go this week, among the places it can reach: each
 * candidate described by [HexFeatures] (in [features]), with the hand-written score for it in
 * [baseline]. Returns the index of the chosen candidate. Any chance in the choice comes from [rnd],
 * the group's own stream for the week, so a run is the same whichever core the group chose on.
 */
fun interface HexPolicy {
    fun choose(g: HexGroup, features: Array<FloatArray>, baseline: FloatArray, rnd: kotlin.random.Random): Int
    /** How it scores each candidate, higher the likelier it goes there; null if it does not tell (only asked for a followed group, see [GroupControl]). */
    fun scores(g: HexGroup, features: Array<FloatArray>, baseline: FloatArray): FloatArray? = null
    /** How it sees the place [g] goes to this week, described by [chosen] (as in [choose]), for the band's ways to read ([HexGroup.plan]); null if it does not tell. */
    fun planned(g: HexGroup, chosen: FloatArray): FloatArray? = null
}

/**
 * What a group sees of a candidate place, as numbers for a learned policy: the food there and the
 * ground, crowding and danger, the weather now and coming, the snow, how far up or down it lies and
 * how much effort it takes to reach, whether it means staying, the pull of the season's remembered
 * place, the way to better country it senses, and the group's own state and the time of year.
 */
object HexFeatures : NamedSlots() {
    // Each slot is named where it is placed, as a learned network's `.names` file records it (see [inflation.learning.Schema]):
    // the strings must not change, or saved networks lose the input.
    /** The food there for the group, 0..1. */
    val FOOD = one("food")
    /** How well the climate there suits its kind, log. */
    val CLIMATE = one("climate suits")
    /** Crowding by its own kind. */
    val CROWDING = one("crowding")
    /** The hunters about. */
    val HUNTERS = one("hunters")
    /** Its discomfort there now: the cold or heat, and what the place drains from it. */
    val DISCOMFORT = one("discomfort")
    /** Its discomfort there as the forecast reads. */
    val DISCOMFORT_FORECAST = one("discomfort forecast")
    /** 1 where the snow is deeper than its kind can go. */
    val SNOW_OVER_LIMIT = one("snow over limit")
    /** The snow against its kind's limit. */
    val SNOW = one("snow")
    /** How far up or down the place lies, km. */
    val CLIMB = one("climb")
    /** The effort of getting there, against its reach. */
    val EFFORT = one("effort")
    /** 1 for the place it is in: staying. */
    val STAYS = one("stays")
    /** 1 for a place in its own hex. */
    val SAME_HEX = one("same hex")
    /** How far going there brings it toward its goal place. */
    val TOWARD_BEARING = one("toward bearing")
    /** How much better it did at its goal place than it is fed now. */
    val SEASON_PLACE = one("season's place")
    /** How fed it is. */
    val FED = one("fed")
    /** Its fat against its kind's most. */
    val FAT = one("fat")
    /** The time of year, sine. */
    val SEASON_SIN = one("season sin")
    /** The time of year, cosine. */
    val SEASON_COS = one("season cos")
    /** 1 if it is restless. */
    val RESTLESS = one("restless")
    /** How far the water is there, in days' travel. */
    val WATER_DISTANCE = one("water distance")
    /** How long it has gone without water, against how long it can. */
    val DAYS_DRY = one("days dry")
    /** The pull of better country it senses. */
    val PULL = one("pull")
    /** The moon. */
    val MOON = one("moon")
    /** The time to its births, sine. */
    val TO_BIRTHS_SIN = one("to births sin")
    /** The time to its births, cosine. */
    val TO_BIRTHS_COS = one("to births cos")
    /** 1 if it remembers the place ([PlaceMemory]). */
    val REMEMBERED = one("remembered")
    /** 1 if it remembers it from this season. */
    val REMEMBERED_SAME_SEASON = one("remembered same season")
    /** How fed it was there. */
    val REMEMBERED_FED = one("remembered fed")
    /** The water it found there. */
    val REMEMBERED_WATER = one("remembered water")
    /** The danger it met there. */
    val REMEMBERED_DANGER = one("remembered danger")
    /** How long ago, against a year. */
    val REMEMBERED_AGE = one("remembered age")
    /** Its ancestors buried there. */
    val ANCESTORS = one("ancestors")
    /** The pull of its appointment. */
    val APPOINTMENT = one("appointment")
    /** Its houses there. */
    val HOUSES = one("houses")
    /** Its own size against its kind's usual group, log. */
    val OWN_SIZE = one("own size")
    /** The lasting water there through the dry season. */
    val LASTING_WATER = one("lasting water")
    /** Sickness raging there among its kind. */
    val SICKNESS = one("sickness")
    /** Tree cover. */
    val TREES = one("trees")
    /** Shrub cover. */
    val SHRUBS = one("shrubs")
    /** Grass cover. */
    val GRASS = one("grass")
    /** Waterlogged ground ([HexLand.waterlogged]). */
    val MARSH = one("marsh")
    /** Water now against its usual. */
    val WATER_AGAINST_USUAL = one("water against usual")
    /** Worn paths. */
    val PATHS = one("paths")
    /** Herds about this month: kept for the layout until retraining, 0. */
    val HERDS_ABOUT = one("herds about")
    /** Dung. */
    val DUNG = one("dung")
    /** Grazing and browsing this week, and carcasses and bones. */
    val GRAZING_AND_KILLS = one("grazing and kills")
    /** For a band, 1 if it knows the hex (1 for animals). */
    val KNOWN_GROUND = one("known ground")
    /** For a pack holding ground, how far beyond it the hex lies (0 within it, against its ground's size). */
    val BEYOND_OWN_GROUND = one("beyond own ground")
    /** How fresh another pack of its kind's mark is there. */
    val OTHERS_MARKS = one("others' marks")
    /** For a band, the fish and shellfish it could take there in a week with its skill, against its week's need (0 for animals). */
    val FISH = one("fish and shellfish")
    /** For a herd, how far going there kept to the way it had been heading: kept for the layout until retraining, 0. */
    val KEEPS_HEADING = one("keeps heading")
    /** For a herd, how good the forage there was: kept for the layout until retraining, 0. */
    val FORAGE_QUALITY = one("forage quality")
    /** The fear of ground where game was killed lately ([HexFauna.afraid]). */
    val KILL_FEAR = one("kill fear")
    /** The share of the place burning this week. */
    val BURNING = one("burning")
    /** 1 if a freeze is coming with snow beyond what it can dig through, as its kind foresees. */
    val SNOW_AHEAD = one("snow ahead")
    /** How much stronger another kind's hunting party holding the place is than its own, when it can tell (0 when none is). */
    val STRONGER_HUNTER = one("stronger hunter")
    /** For a band, the most it remembers finding at the hex of a rarer material it lacks ([PlaceMemory.found] times how far it lacks it; 0 for animals). */
    val REMEMBERED_LACKED = one("remembered lacked")
    /**
     * For a band, how far the place takes it toward where it remembers the most of a rarer material it lacks, times how much
     * it lacks it (0 with nothing lacked or remembered, or within a hex of it).
     */
    val TOWARD_LACKED = one("toward lacked")
    /**
     * The place's usual temperature in each of the next three months, against 20 °C: what any forager knows of their country
     * from the seasons past (lore, not a forecast), for a network to learn what the cold to come means ([LORE_MONTHS]).
     */
    val USUAL_AHEAD = block(List(LORE_MONTHS) { k -> "usual temperature, month ${k + 1} ahead" })
    const val LORE_MONTHS = 3
}
