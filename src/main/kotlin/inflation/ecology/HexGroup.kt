package inflation.ecology

/** A herd, pack or pride on the hexes: where it stands (a place: a height band of a hex) and who it is. */
class HexGroup(val id: Int, val species: Species, var place: Int, var adults: Int, var young: Int) {
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
    /** For a hunter: the hex at the heart of the ground it holds, -1 while it holds none. */
    var home = -1
    /** For a herd: the place it remembers doing best in each month, and how well (0..1). */
    val seasonPlace = IntArray(12) { -1 }
    val seasonQ = FloatArray(12)
    /** Days since it last drank. */
    var dry = 0f
    /** Weeks in a row it has gone hungry (fed under half); the last hexes it hunted in, newest first. */
    var lean = 0
    val recent = IntArray(4) { -1 }
    /** Lineage tag for learning, -1 for none; the group it split from (-1 for none); its size when it merged into another (-1 if it did not), for following a choice's outcome. */
    var tag = -1
    var parent = -1
    /** The places it remembers (see [PlaceMemory]). */
    val memory = PlaceMemory()
    var mergedSize = -1
    /** How well its diet met its need for protein over the last weeks, 0..1 (see [HexFauna.proteinMet]). */
    var protein = 1f
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
    val stores = FloatArray(Store.entries.size)
    /** All food on hand, kg; setting it scales every kind alike (gifts, raids, splits), or stores it dried. */
    var larder: Float
        get() = stores.sum()
        set(v) { val s = stores.sum(); if (s > 0f) { val k = kotlin.math.max(0f, v) / s; for (i in stores.indices) stores[i] *= k } else { stores.fill(0f); stores[Store.DRIED.ordinal] = kotlin.math.max(0f, v) } }
    /** For a band: its skills, tools and camp. */
    var band: Band? = null
    /** For kinds that follow people's camps: the share of it bold enough to come close, 0..1 (-1 until first needed: its kind's share). */
    var bold = -1f
    /** Its temperament ([Temperament]): a founder's by chance, an offshoot's from its parent. */
    var temper = Temperament.founder(id)
    /** How wary it is from being hunted lately, 0..1: it keeps a sharper watch, and fades over weeks. */
    var wary = 0f
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
}

/**
 * What a group sees of a candidate place, as numbers for a learned policy: the food there and the
 * ground, crowding and danger, the weather now and coming, the snow, how far up or down it lies and
 * how much effort it takes to reach, whether it means staying, the pull of the season's remembered
 * place, the way to better country it senses, and the group's own state and the time of year.
 */
object HexFeatures {
    /** 0-24 the place now; 25-30 what the group remembers of it ([PlaceMemory]); 31 its ancestors there; 32 the pull of its appointment; 33 its houses there; 34 its own size against its kind's usual group; 35 the lasting water there through the dry season; 36 sickness raging there among its kind;
     *  37-46 the signs of the land: tree, shrub and grass cover, marsh, water now against its usual, worn paths, herds about
     *  this month, dung, grazing this week, carcasses and bones; 46 whether a band knows the hex;
     *  47 for a pack holding ground, how far beyond it the hex lies (0 within it, against its ground's size); 48 how fresh another pack of its kind's mark is there;
     *  49 for a band, the fish and shellfish it could take there in a week with its skill, against its week's need (0 for animals);
     *  50 for a herd, how far going there keeps to the way it has been heading ([HexGroup.headE]; 0 for others). */
    const val SIZE = 51
}
