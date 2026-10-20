package inflation.ecology

import inflation.world.Weather

/*
 * What differs between the two ways groups live ([Lifeway]) when they choose where to go: when a group is
 * restless, how often a content one weighs its places, what a place is worth to it, and how its food there
 * counts in the hand-written score. Each rule is written once per lifeway here, so that a pack's and a
 * band's way of choosing can be read side by side; [HexGroups.planInner] asks them once per group.
 */

/**
 * Whether [g] is restless this week: it ranges as far as it can, and weighs its places whether content or not.
 * A pack when hungry, without ground of its own, thirsty or astray; a band when hungry, thirsty, astray, due
 * somewhere soon, behind its round, or ill at ease where it is (the cold or heat as its clothes, fire and shelter leave it).
 */
internal fun HexGroups.restless(g: HexGroup, weather: Weather, thirsty: Boolean, astray: Boolean, dueSoon: Boolean, onRound: Boolean, goalFar: Boolean): Boolean {
    val s = g.species
    return when (s.lifeway) {
        Lifeway.BAND -> g.fed < Thresholds.HUNGRY || thirsty || astray || dueSoon || (onRound && goalFar) ||
            (g.band?.let { s.discomfortC(it.feels(weather.temperature[g.place]), weather.date.month) / 10f } ?: 0f) > 0.5f
        Lifeway.PACK -> g.fed < Thresholds.HUNGRY || g.home.none || thirsty || astray
    }
}

/**
 * How often a group of [s] content where it is weighs its places, weeks (by turns; the other weeks it keeps to its ground).
 * Packs every other week. Bands choosing by a network every week, [BAND_CONTENT_WEEKS]; the hand-written bands every other week, so that they
 * stay the baseline learned bands are judged against.
 */
internal fun HexGroups.contentEvery(s: Species): Int = when (s.lifeway) {
    Lifeway.BAND -> if (policies[s] != null) BAND_CONTENT_WEEKS else 2
    Lifeway.PACK -> 2
}

/**
 * What place [p] is worth to kind [s] this week, before crowding, hunters and the going. A pack: the game it may expect there, as its diet
 * values it. A band: the game it expects, small game and carrion as for a pack, and the plant food, as good camping ground ([campFactor]).
 * (Where they see or read sign, by what they find: see [knownGame].)
 */
internal fun HexGroups.lifewayValue(s: Species, p: Place): Float = when (s.lifeway) {
    Lifeway.BAND -> dietValue(s, Place(p.i), preyHere(s, Place(p.i))) * campFactor(Place(p.i)) + campPlants(Place(p.i))
    Lifeway.PACK -> dietValue(s, Place(p.i), preyHere(s, Place(p.i)))
}

/**
 * The food term of the hand-written score of place [p] (in hex [h]) for [g], with its own kind's crowding there: the
 * game it knows of there ([prey]), less crowding, and less where a much stronger hunter of another kind holds sway (they
 * hear the roars and smell the marks: cheetahs and hyenas keep out of lions' way, jackals out of wolves').
 */
internal fun HexGroups.foodTerm(g: HexGroup, p: Place, h: Hex, sensed: IntArray, prey: Float, crowded: Float): Float {
    val danger = if (h in sensed && topKind[p] >= 0 && topKind[p] != g.species.index && topStrength[p] > 2f * strength(g)) AVOID_STRONGER else 0f
    return prey / (prey + PREY_HALF) - 0.5f * crowded - danger
}
