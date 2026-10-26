package inflation.ecology

import inflation.world.Hex
import inflation.world.HexArray
import inflation.world.Place
import inflation.world.get
import inflation.world.set

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
 * somewhere soon, behind its round, or ill at ease where it is (in the cold: [coldRestless]; or the heat).
 */
internal fun HexGroups.restless(g: HexGroup, weather: Weather, thirsty: Boolean, astray: Boolean, dueSoon: Boolean, onRound: Boolean, goalFar: Boolean): Boolean {
    val s = g.species
    return when (s.lifeway) {
        Lifeway.BAND -> g.fed < Thresholds.HUNGRY || thirsty || astray || dueSoon || (onRound && goalFar) ||
            (g.band?.let { coldRestless(it.warmth(airAt(g.place, weather), it.fire, it.homeShelter(), days)) } ?: false) ||
            s.hotC(weather.temperature[g.place]) / 10f > 0.5f
        Lifeway.PACK -> g.fed < Thresholds.HUNGRY || g.home.none || thirsty || astray
    }
}

/**
 * Whether a band's week in the cold, as its clothes, fire, shelter and house leave it ([Band.warmth]), sets it looking for
 * better: when keeping warm takes [COLD_RESTLESS] more food or more, or the cold kills (both estimates).
 */
private fun coldRestless(w: Warmth) = w.extra >= COLD_RESTLESS || w.odds > 0f
const val COLD_RESTLESS = 0.25f

/**
 * What place [p] is worth to kind [s] this week, before crowding, hunters and the going. A pack: the game it may expect there, as its diet
 * values it. A band: the game it expects, small game and carrion as for a pack, and the plant food, as good camping ground ([campFactor]).
 * (Where they see or read sign, by what they find: see [knownGame].)
 */
internal fun HexGroups.lifewayValue(s: Species, p: Place): Float = when (s.lifeway) {
    Lifeway.BAND -> dietValue(s, p, preyHere(s, p)) * campFactor(p) + campPlants(p)
    Lifeway.PACK -> dietValue(s, p, preyHere(s, p))
}

/**
 * The food term of the hand-written score of place [p] (in hex [h]) for [g], with its own kind's crowding there: the
 * game it knows of there ([prey]), less crowding, and less where a much stronger hunter of another kind holds sway (they
 * hear the roars and smell the marks: cheetahs and hyenas keep out of lions' way, jackals out of wolves').
 */
internal fun HexGroups.foodTerm(g: HexGroup, p: Place, h: Hex, sensed: HexArray, prey: Float, crowded: Float): Float {
    val danger = if (h in sensed && topKind[p] >= 0 && topKind[p] != g.species.index && topStrength[p] > 2f * strength(g)) AVOID_STRONGER else 0f
    return prey / (prey + PREY_HALF) - 0.5f * crowded - danger
}
