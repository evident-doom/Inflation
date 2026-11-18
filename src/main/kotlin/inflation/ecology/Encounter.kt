package inflation.ecology

import inflation.world.Hex
import inflation.world.HexArray
import inflation.world.Place
import kotlin.math.max
import kotlin.math.min

/**
 * Two parties' coming on each other, as it is being settled ([settle]): who ([a], [b]), where ([hex], at [place]), what
 * the one that gives way gives up ([contested]: the hexes of their contact, or the meeting's hex), how they came to meet
 * ([intent]), and on which day ([day]). Carried down to whatever settles it, the engagement ([engage]) or the rules of the
 * kinds that meet ([rivalsMeet], [enemiesMeet], [bandsMeet]), so that nothing it is settled by is left in the world for the
 * next meeting to find (docs/design/coherence-review.md, C2: these were globals set by the caller).
 *
 * A party on its way may be turned back ([turnBack]): it ends its step at the point of its way before this meeting
 * ([meetSettle]'s stops, by the parties' indices [ak], [bk] and the points of their ways [ai], [bi]). Parties that meet
 * by a visit or a gathering have no way to turn back on ([withoutWays]).
 */
internal class Encounter(
    val a: HexGroup, val b: HexGroup, val hex: Hex, val place: Place, val contested: HexArray, val intent: Intent, val day: Long,
    private val stop: IntArray, private val ak: Int, private val ai: Int, private val bk: Int, private val bi: Int,
) {
    /** [g], one of the two, turns back: back the way it came, its step ending before this meeting (nothing for one not on a way). */
    fun turnBack(g: HexGroup) {
        if (g === a) stop[ak] = min(stop[ak], max(0, ai - 1)) else if (g === b) stop[bk] = min(stop[bk], max(0, bi - 1))
    }

    /** The same encounter met at hex [h], place [p] instead: the ground the engagement chose to meet on ([battleground]). */
    fun at(h: Hex, p: Place) = Encounter(a, b, h, p, contested, intent, day, stop, ak, ai, bk, bi)

    companion object {
        /** An encounter of two that hold their ground and meet at [h] (place [p]) by a visit or a gathering: neither is on a way to turn back on. */
        fun withoutWays(a: HexGroup, b: HexGroup, h: Hex, p: Place, contested: HexArray, intent: Intent, day: Long) =
            Encounter(a, b, h, p, contested, intent, day, IntArray(2), 0, 0, 1, 0)
    }
}
