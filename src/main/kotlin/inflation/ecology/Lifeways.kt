package inflation.ecology

import inflation.saturate
import inflation.world.Weather

/*
 * What differs between the three ways groups live ([Lifeway]) when they choose where to go: when a group is
 * restless, how often a content one weighs its places, what a place is worth to it, and how its food there
 * counts in the hand-written score. Each rule is written once per lifeway here, so that a herd's, a pack's
 * and a band's way of choosing can be read side by side; [HexGroups.planInner] asks them once per group.
 */

/**
 * Whether [g] is restless this week: it ranges as far as it can, and weighs its places whether content or not.
 * A herd when hungry, ill at ease in the weather, snowed in, thirsty, astray or far from its season's place; a
 * pack when hungry, without ground of its own, thirsty or astray; a band when hungry, thirsty, astray, due
 * somewhere soon, behind its round, or ill at ease where it is (the cold or heat as its clothes, fire and shelter leave it).
 */
internal fun HexGroups.restless(g: HexGroup, weather: Weather, thirsty: Boolean, astray: Boolean, dueSoon: Boolean, onRound: Boolean, goalFar: Boolean): Boolean {
    val s = g.species
    return when (s.lifeway) {
        Lifeway.BAND -> g.fed < Thresholds.HUNGRY || thirsty || astray || dueSoon || (onRound && goalFar) ||
            (g.band?.let { s.discomfortC(it.feels(weather.temperature[g.place]), weather.date.month) / 10f } ?: 0f) > 0.5f
        Lifeway.PACK -> g.fed < Thresholds.HUNGRY || g.home.none || thirsty || astray
        Lifeway.HERD -> g.fed < Thresholds.HERD_HUNGRY || discomfort(s, g.place.i, weather) > 0.5f || weather.snowpack[g.place.i] > s.snowLimitMm ||
            thirsty || astray || goalFar
    }
}

/**
 * How often a group of [s] content where it is weighs its places, weeks (by turns; the other weeks it keeps to its ground).
 * Herds one week in [Tuning.contentWeeks][inflation.Tuning.contentWeeks]: they stay while the grazing holds. Packs every other
 * week. Bands choosing by a network every week, [BAND_CONTENT_WEEKS]; the hand-written bands every other week, so that they
 * stay the baseline learned bands are judged against.
 */
internal fun HexGroups.contentEvery(s: Species): Int = when (s.lifeway) {
    Lifeway.BAND -> if (policies[s] != null) BAND_CONTENT_WEEKS else 2
    Lifeway.PACK -> 2
    Lifeway.HERD -> tuning.contentWeeks
}

/**
 * What place [p] is worth to kind [s] (its [k]th) this week, before crowding, hunters and the going. A herd: its food, as
 * its country suits it, not burning, and as near its usual country. A pack: the game it may expect there, as its diet
 * values it. A band: the game it expects, and the plant food, as good camping ground ([campFactor]).
 * (Hunters and bands value a place by the herds they expect there; where they see or read sign, by what they find: see [knownGame].)
 */
internal fun HexGroups.lifewayValue(k: Int, s: Species, p: Int): Float = when (s.lifeway) {
    Lifeway.BAND -> (preyHere(s, p) + expectPrey[k][p]) * campFactor(p) + campPlants(p)
    Lifeway.PACK -> dietValue(s, p, preyHere(s, p) + expectPrey[k][p])
    Lifeway.HERD -> foodFor(s, p) * fauna.suits(s, p) * (1f - land.fire.burning[p]) * fauna.tether(s, p)
}

/**
 * The food term of the hand-written score of place [p] (in hex [h]) for [g], with its own kind's crowding and the
 * hunters it fears there. Packs and bands: the game it knows of there ([prey]), less crowding, and less where a much
 * stronger hunter of another kind holds sway (they hear the roars and smell the marks: cheetahs and hyenas keep out of
 * lions' way, jackals out of wolves'). Herds: their food, as fresh as it is ([PlaceTerms.fresh]), less crowding,
 * hunters and the fear of ground where game was killed lately. (At this scale every hex has its hunters: a herd does not
 * shift its ground to get away from them, it watches and hides where it is; it moves for food, water, weather and the season.)
 */
internal fun HexGroups.foodTerm(g: HexGroup, kind: Int, fresh: Float, p: Int, h: Int, sensed: IntArray, prey: Float, crowded: Float, hunters: Float): Float {
    val s = g.species
    return when (s.lifeway) {
        Lifeway.PACK, Lifeway.BAND -> {
            val danger = if (h in sensed && topKind[p] >= 0 && topKind[p] != s.index && topStrength[p] > 2f * strength(g)) AVOID_STRONGER else 0f
            prey / (prey + PREY_HALF) - 0.5f * crowded - danger
        }
        // (How much there is levels off, how good it is does not: fresh, protein-rich growth is worth
        // more however much of the dry stuff stands, as it is to a mother and her young.)
        Lifeway.HERD -> saturate(placeValue[kind][p], Thresholds.FOOD_HALF) * fresh - 0.5f * crowded -
            SHUN_HUNTERS * hunters / (hunters + tuning.huntersHalfKg) - HexFauna.FEAR_WEIGHT * fauna.afraid(p)
    }
}
