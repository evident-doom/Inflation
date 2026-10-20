package inflation.ecology

import inflation.world.Date
import inflation.world.Weather

/**
 * The player's hand on the group followed in the viewer ([HexGroups.watched]): each week it is shown
 * what the group weighs (the places it can reach, as the group sees them, how the hand-written score
 * and the network score each, and which the model takes) and the ways the model would set, and it
 * may change both. Asked only for the followed group and only while set ([HexGroups.control]), so a
 * run without it costs nothing.
 */
interface GroupControl {
    /** Where the group goes this week: an index into [MoveDecision.places] ([MoveDecision.model] for the model's). May wait for the player. */
    fun move(d: MoveDecision): Int
    /** The group's ways as the model set them in [out] (0 the hand-written way, see [setWays] and [AnimalWay]): the player's pinned ones replace them. */
    fun ways(g: HexGroup, out: FloatArray)
}

/** One week's choice of where to go, for the followed group: all copies, safe to keep. */
class MoveDecision(
    val group: HexGroup,
    val date: Date,
    /** The places it can reach, each with what it sees of it ([HexFeatures]), its hand-written score, the network's (null without one),
     * and the hexes it would pass. */
    val places: IntArray,
    val features: Array<FloatArray>,
    val handScores: FloatArray,
    val netScores: FloatArray?,
    val paths: Array<IntArray>,
    /** The model's pick (an index into [places]); whether a learned network chose it. */
    val model: Int,
    val learned: Boolean,
    val restless: Boolean,
    val reachKm: Float,
    /** The place its round or its memory of the season draws it to (-1 none), and whether it is on its round. */
    val goal: Int,
    val onRound: Boolean,
    /** What it knows of itself (a band: [Band.state], as its networks read it; a pack: [animalInputs]). */
    val knows: FloatArray,
    /** The ways the model would set this week (0 the hand-written way each), and whether a learned network sets them. */
    val ways: FloatArray,
    val waysLearned: Boolean,
)

/** No ways network: the hand-written way for each (every output 0). */
private val HAND_WRITTEN_WAYS = BandWays { _, _, out -> out.fill(0f) }
private val HAND_WRITTEN_ANIMAL_WAYS = AnimalWays { _, _, out -> out.fill(0f) }

/** Asks [control] where the followed group [g] goes, among the candidates weighed in [HexGroups.plan]; returns the place. */
internal fun HexGroups.askControl(
    g: HexGroup, weather: Weather, date: Date, policy: HexPolicy?, cand: HexGroups.IntList, feats: ArrayList<FloatArray>, hand: FloatArray,
    modelPlace: Int, restless: Boolean, reach: Float, goal: Int, onRound: Boolean, pathTo: (Int) -> IntArray,
): Int {
    val ctl = control ?: return modelPlace
    val n = cand.size
    if (n == 0) return modelPlace
    val places = cand.toIntArray()
    val features = Array(n) { feats[it].copyOf() }
    val scores = hand.copyOf(n)
    val net = policy?.scores(g, features, scores)
    val model = places.indexOf(modelPlace).takeIf { it >= 0 } ?: places.indexOf(g.place.i).coerceAtLeast(0)
    // Its ways, as the model would set them now: a band's from what it knew of itself as the week began, a pack's from the same as set them.
    val knows: FloatArray; val out: FloatArray; val learnedWays: Boolean
    val band = g.band
    if (band != null) {
        knows = band.state.takeIf { it.size == WAYS_IN }?.copyOf() ?: FloatArray(WAYS_IN).also { bandInputs(g, weather, it) }
        out = FloatArray(WAYS_OUT); (ways ?: HAND_WRITTEN_WAYS).choose(g, knows.copyOf(), out); learnedWays = ways != null
    } else {
        knows = FloatArray(ANIMAL_WAYS_IN).also { animalInputs(g, weather, it) }
        val w = hunterWays
        out = FloatArray(ANIMAL_WAYS_OUT); (w ?: HAND_WRITTEN_ANIMAL_WAYS).choose(g, knows.copyOf(), out); learnedWays = w != null
    }
    val d = MoveDecision(g, date, places, features, scores, net, Array(n) { pathTo(places[it]) }, model, policy != null, restless, reach, goal,
        onRound, knows, out, learnedWays)
    val pick = ctl.move(d).takeIf { it in 0 until n } ?: model
    // A pack's ways were set before it chose (bands set theirs as they forage): the player's pins take hold now.
    if (band == null) setAnimalWays(g, weather, hunterWays ?: HAND_WRITTEN_ANIMAL_WAYS, FloatArray(ANIMAL_WAYS_IN),
        FloatArray(ANIMAL_WAYS_OUT))
    return places[pick]
}

/** The followed band's ways under the player's hand: set every week (with the hand-written ways when there is no network), so the player's pins hold. */
internal fun HexGroups.controlledWays(): BandWays? = if (control != null) (ways ?: HAND_WRITTEN_WAYS) else null
