package inflation.ecology
import inflation.world.HexLand
import kotlin.math.exp
import kotlin.math.max

/** A herd, pack or pride on the hexes: where it stands (a place: a height band of a hex) and who it is. */
class HexGroup(val id: Int, val species: Species, var place: Place, var adults: Int, var young: Int) {
    val size get() = adults + young
    /**
     * For a herd: how large it likes to grow against its kind's usual, its own leaning (a third to
     * three times), kept by both halves when it splits; so herds of every size live side by side.
     */
    var sizePref = 1f
    val alive get() = adults > 0
    /** Fed last week, 0..1; body reserves, days of food. */
    var fed = 1f
    var fat = 0f
    /** What it ate this week beyond its needs, days of food: laid on as reserves (see [liveWeek]). */
    var surplus = 0f
    /** The hexes it passed through this week, in order (its own last), and the day it entered each. */
    var path = IntArray(0)
    var days = FloatArray(0)
    /** Meat it got this week from herds it met on the way, kg. */
    var meatMet = 0f
    /** Whether [meatMet] is eaten, stored or left by now this week (for the books, which count it in hand until then; a loaded group's is spent). */
    @Transient var meatSpent = true
    /** For a herd of a kind that also lives spread out: the place it condensed at ([gather]), -1 for none. */
    var formedAt = -1
    /** For such a herd: heads it took in from the residents this week, and gave back to them ([scatter]). Not saved: read in the same step. */
    @Transient var joined = 0
    @Transient var settled = 0
    /** For a hunter: the hex at the heart of the ground it holds, -1 while it holds none. */
    var home = Hex.NONE
    /** For a herd: the place it remembers doing best in each month, and how well (0..1). */
    val seasonPlace = IntArray(12) { -1 }
    val seasonQ = FloatArray(12)
    /**
     * Its yearly round, as its elders taught it: the place it makes for each month ([ROUND_OPEN]: none yet; [NO_ROUND]:
     * keeps to its own country; see [growRounds]).
     */
    val route = IntArray(12) { ROUND_OPEN }
    /** Days since it last drank. */
    var dry = 0f
    /** Weeks in a row it has gone hungry (fed under half); the last hexes it hunted in, newest first. */
    var lean = 0
    val recent = IntArray(4) { -1 }
    /** Lineage tag for learning, -1 for none; the group it split from (-1 for none); its size when it merged into another (-1 if it did
     * not), for following a choice's outcome. */
    var tag = -1
    var parent = -1
    /** The places it remembers (see [PlaceMemory]). */
    val memory = PlaceMemory()
    var mergedSize = -1
    /** The group it merged into, the step it did (else -1): for following what became of its members. Not saved: read in the same step. */
    @Transient var mergedInto = -1
    /** How well its diet met its need for protein over the last weeks, 0..1 (see [HexFauna.proteinMet]). */
    var protein = 1f
    /** How well its diet met its need for protein in each month, as it remembers (births move toward the months rich in it). */
    val proteinByMonth = FloatArray(12) { 1f }
    /**
     * Which way a herd has been heading, as east and south parts (km-free, at most 1 long): kept from week
     * to week and bent a little each week toward better country, so small nudges add up to a long trek.
     */
    var headE = 0f
    var headS = 0f
    /**
     * A band's luck in its own week's births and deaths, summed since it began: born less died as
     * drawn, against as expected from the week's odds (for training, which judges people by what
     * their choices made likely, not by chance; animals keep 0).
     */
    var luck = 0f
    /** The month its young are born in, drifting towards the months that feed them best; -1 for its kind's. */
    var birthMonth = -1
    /** For a herd or pack: its ways this week, 1 the hand-written way (see [AnimalWay]). */
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
    /** When it was last chased, in days from the start (with the hour): it is alarmed for a while after (see [wary]). */
    var chasedOn = Double.NEGATIVE_INFINITY
    /** How alarmed it still is at [day] (days from the start) from its last chase, 0..1: it keeps a sharper watch, fading over [ALARM_DAYS]. */
    fun wary(day: Double) = exp(-(day - chasedOn) / ALARM_DAYS).toFloat()
    /** For hunters: meetings with game of each kind ([Species.index]), herds and residents alike, as of [herdsMetOn], fading over [SEARCH_DAYS] (null: none yet; see [SEARCH_HALF]). */
    var herdsMet: FloatArray? = null
    var herdsMetOn = 0.0
    /**
     * For hunters: the rate of intake its last week's diet promised, kg a day of searching and handling (see [forage]).
     * Game it meets is worth chasing only if a kill of it yields more for the time it takes (Charnov's rule).
     */
    var dietRate = 0f
    /** Its grown ones' odds of dying in a step, as its last week's hazards had them (0 before its first week): what a life risked is set against. */
    var deathOdds = 0f
    /** How often it has come on herds of [k] lately, at [day]. */
    fun metLately(k: Int, day: Double): Float = herdsMet?.let { it[k] * exp(-(day - herdsMetOn) / SEARCH_DAYS).toFloat() } ?: SEARCH_HALF
    /** Counts a meeting with a herd of [k] at [day]. */
    fun metHerd(k: Int, day: Double) {
        val m = herdsMet ?: FloatArray(Catalogue.all.size) { SEARCH_HALF }.also { herdsMet = it; herdsMetOn = day }
        val fade = exp(-(day - herdsMetOn) / SEARCH_DAYS).toFloat()
        for (i in m.indices) m[i] *= fade
        m[k] += 1f
        herdsMetOn = day
    }
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
}

/**
 * What a group sees of a candidate place, as numbers for a learned policy: the food there and the
 * ground, crowding and danger, the weather now and coming, the snow, how far up or down it lies and
 * how much effort it takes to reach, whether it means staying, the pull of the season's remembered
 * place, the way to better country it senses, and the group's own state and the time of year.
 */
object HexFeatures {
    // Each slot's name in saved networks is [inflation.learning.Schema.PLACE] at the same index; SchemaTest checks they line up.
    /** The food there for the group, 0..1. */
    const val FOOD = 0
    /** How well the climate there suits its kind, log. */
    const val CLIMATE = 1
    /** Crowding by its own kind. */
    const val CROWDING = 2
    /** The hunters about. */
    const val HUNTERS = 3
    /** Its discomfort there now: the cold or heat, and what the place drains from it. */
    const val DISCOMFORT = 4
    /** Its discomfort there as the forecast reads. */
    const val DISCOMFORT_FORECAST = 5
    /** 1 where the snow is deeper than its kind can go. */
    const val SNOW_OVER_LIMIT = 6
    /** The snow against its kind's limit. */
    const val SNOW = 7
    /** How far up or down the place lies, km. */
    const val CLIMB = 8
    /** The effort of getting there, against its reach. */
    const val EFFORT = 9
    /** 1 for the place it is in: staying. */
    const val STAYS = 10
    /** 1 for a place in its own hex. */
    const val SAME_HEX = 11
    /** How far going there brings it toward its goal place. */
    const val TOWARD_BEARING = 12
    /** How much better it did at its goal place than it is fed now. */
    const val SEASON_PLACE = 13
    /** How fed it is. */
    const val FED = 14
    /** Its fat against its kind's most. */
    const val FAT = 15
    /** The time of year, sine. */
    const val SEASON_SIN = 16
    /** The time of year, cosine. */
    const val SEASON_COS = 17
    /** 1 if it is restless. */
    const val RESTLESS = 18
    /** How far the water is there, in days' travel. */
    const val WATER_DISTANCE = 19
    /** How long it has gone without water, against how long it can. */
    const val DAYS_DRY = 20
    /** The pull of better country it senses. */
    const val PULL = 21
    /** The moon. */
    const val MOON = 22
    /** The time to its births, sine. */
    const val TO_BIRTHS_SIN = 23
    /** The time to its births, cosine. */
    const val TO_BIRTHS_COS = 24
    /** 1 if it remembers the place ([PlaceMemory]). */
    const val REMEMBERED = 25
    /** 1 if it remembers it from this season. */
    const val REMEMBERED_SAME_SEASON = 26
    /** How fed it was there. */
    const val REMEMBERED_FED = 27
    /** The water it found there. */
    const val REMEMBERED_WATER = 28
    /** The danger it met there. */
    const val REMEMBERED_DANGER = 29
    /** How long ago, against a year. */
    const val REMEMBERED_AGE = 30
    /** Its ancestors buried there. */
    const val ANCESTORS = 31
    /** The pull of its appointment. */
    const val APPOINTMENT = 32
    /** Its houses there. */
    const val HOUSES = 33
    /** Its own size against its kind's usual group, log. */
    const val OWN_SIZE = 34
    /** The lasting water there through the dry season. */
    const val LASTING_WATER = 35
    /** Sickness raging there among its kind. */
    const val SICKNESS = 36
    /** Tree cover. */
    const val TREES = 37
    /** Shrub cover. */
    const val SHRUBS = 38
    /** Grass cover. */
    const val GRASS = 39
    /** Waterlogged ground ([HexLand.waterlogged]). */
    const val MARSH = 40
    /** Water now against its usual. */
    const val WATER_AGAINST_USUAL = 41
    /** Worn paths. */
    const val PATHS = 42
    /** Herds about this month. */
    const val HERDS_ABOUT = 43
    /** Dung. */
    const val DUNG = 44
    /** Grazing and browsing this week, and carcasses and bones. */
    const val GRAZING_AND_KILLS = 45
    /** For a band, 1 if it knows the hex (1 for animals). */
    const val KNOWN_GROUND = 46
    /** For a pack holding ground, how far beyond it the hex lies (0 within it, against its ground's size). */
    const val BEYOND_OWN_GROUND = 47
    /** How fresh another pack of its kind's mark is there. */
    const val OTHERS_MARKS = 48
    /** For a band, the fish and shellfish it could take there in a week with its skill, against its week's need (0 for animals). */
    const val FISH = 49
    /** For a herd, how far going there keeps to the way it has been heading ([HexGroup.headE]; 0 for others). */
    const val KEEPS_HEADING = 50
    /** For a herd on its yearly round, how far the place takes it on toward the round's place this month (0 off a round). */
    const val ON_ROUND = 51
    /** For a herd, how good the forage there is, 0..1: how nourishing and how rich in protein ([HexGroups.freshness]; 0 for others). */
    const val FORAGE_QUALITY = 52
    const val SIZE = 53

    /**
     * Each feature's name, as a learned network's `.names` file records it (see [inflation.learning.Schema]): set by
     * the feature's slot, so a slot moved keeps its name. The strings must not change, or saved networks lose the input.
     */
    val NAMES: List<String> = arrayOfNulls<String>(SIZE).also {
        it[FOOD] = "food"
        it[CLIMATE] = "climate suits"
        it[CROWDING] = "crowding"
        it[HUNTERS] = "hunters"
        it[DISCOMFORT] = "discomfort"
        it[DISCOMFORT_FORECAST] = "discomfort forecast"
        it[SNOW_OVER_LIMIT] = "snow over limit"
        it[SNOW] = "snow"
        it[CLIMB] = "climb"
        it[EFFORT] = "effort"
        it[STAYS] = "stays"
        it[SAME_HEX] = "same hex"
        it[TOWARD_BEARING] = "toward bearing"
        it[SEASON_PLACE] = "season's place"
        it[FED] = "fed"
        it[FAT] = "fat"
        it[SEASON_SIN] = "season sin"
        it[SEASON_COS] = "season cos"
        it[RESTLESS] = "restless"
        it[WATER_DISTANCE] = "water distance"
        it[DAYS_DRY] = "days dry"
        it[PULL] = "pull"
        it[MOON] = "moon"
        it[TO_BIRTHS_SIN] = "to births sin"
        it[TO_BIRTHS_COS] = "to births cos"
        it[REMEMBERED] = "remembered"
        it[REMEMBERED_SAME_SEASON] = "remembered same season"
        it[REMEMBERED_FED] = "remembered fed"
        it[REMEMBERED_WATER] = "remembered water"
        it[REMEMBERED_DANGER] = "remembered danger"
        it[REMEMBERED_AGE] = "remembered age"
        it[ANCESTORS] = "ancestors"
        it[APPOINTMENT] = "appointment"
        it[HOUSES] = "houses"
        it[OWN_SIZE] = "own size"
        it[LASTING_WATER] = "lasting water"
        it[SICKNESS] = "sickness"
        it[TREES] = "trees"
        it[SHRUBS] = "shrubs"
        it[GRASS] = "grass"
        it[MARSH] = "marsh"
        it[WATER_AGAINST_USUAL] = "water against usual"
        it[PATHS] = "paths"
        it[HERDS_ABOUT] = "herds about"
        it[DUNG] = "dung"
        it[GRAZING_AND_KILLS] = "grazing and kills"
        it[KNOWN_GROUND] = "known ground"
        it[BEYOND_OWN_GROUND] = "beyond own ground"
        it[OTHERS_MARKS] = "others' marks"
        it[FISH] = "fish and shellfish"
        it[KEEPS_HEADING] = "keeps heading"
        it[ON_ROUND] = "on round"
        it[FORAGE_QUALITY] = "forage quality"
    }.mapIndexed { k, n -> requireNotNull(n) { "feature $k has no name" } }
}
