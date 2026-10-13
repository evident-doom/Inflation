package inflation.ecology

/** A herd, pack or pride on the hexes: where it stands (a place: a height band of a hex) and who it is. */
class HexGroup(val id: Int, val species: Species, var place: Int, var adults: Int, var young: Int) {
    val size get() = adults + young
    val alive get() = adults > 0
    /** Fed last week, 0..1; body reserves, days of food. */
    var fed = 1f
    var fat = 0f
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
    /** Lineage tag for learning, -1 for none. */
    var tag = -1
    /** The month its young are born in, drifting towards the months that feed them best; -1 for its kind's. */
    var birthMonth = -1
    /** How wary it is from being hunted lately, 0..1: it keeps a sharper watch, and fades over weeks. */
    var wary = 0f
    val dailyNeedKg get() = species.dailyIntakeKg * (adults + 0.5f * young)
}

/**
 * How a group of a kind chooses where to go this week, among the places it can reach: each
 * candidate described by [HexFeatures] (in [features]), with the hand-written score for it in
 * [baseline]. Returns the index of the chosen candidate.
 */
fun interface HexPolicy {
    fun choose(g: HexGroup, features: Array<FloatArray>, baseline: FloatArray): Int
}

/**
 * What a group sees of a candidate place, as numbers for a learned policy: the food there and the
 * ground, crowding and danger, the weather now and coming, the snow, how far up or down it lies and
 * how much effort it takes to reach, whether it means staying, the pull of the season's remembered
 * place, the way to better country it senses, and the group's own state and the time of year.
 */
object HexFeatures {
    const val SIZE = 22
}
