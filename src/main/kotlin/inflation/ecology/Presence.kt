package inflation.ecology

import inflation.world.Hex
import kotlin.math.min

// Who is present where two parties meet, and how strong they are there (docs/design/coherence-review.md, C4): the one
// rule every meeting's outcome reads, the rivals', the hunters' of other kinds, the stand-ins' and the engagements'.
// Before, rivals counted their grown heads, the rest a party's strength, and a band was met whole wherever it was met.
//
// A pack moves, hunts and fights together: all of its party is there ([foragers]). A band does not go about as one
// (Olli, 14 Nov 2026): most of its people are at its camp most of the time, and its foragers go out in small parties. Met
// at its camp's hex, it is its able grown people ([Band.ableShare]: the wounded and lamed do not fight) at home, its
// home share of them ([homeShare]: the rest are out, as a band's parties are, uncounted); met anywhere
// else on its ground, it is one party of them out foraging ([BAND_PARTY]), whatever the band's size; at a gathering, where
// its grown all walked ([gatherAtRite]), it is its able grown. A raid's party is the raid's ([raidParty]), counted where
// the raid is fought.
//
// A group is the stronger in its lair ([lairAdvantage]), wherever the meeting is settled: by the kinds' own rules
// ([strengthAt]) or by the strategies ([engage]), where holding a hex adds only its lie ([holding]).

/**
 * How many of [g] are there where it is met at hex [h]: a pack's party; a band's able grown at its camp's hex or at a
 * gathering ([gathered]), else one party out ([BAND_PARTY]).
 */
internal fun HexGroups.presentAt(g: HexGroup, h: Hex, gathered: Boolean = false): Float {
    val b = g.band ?: return foragers(g).toFloat()
    val able = g.adults * b.ableShare
    // (At its camp, those of them at home: its home share, the share of its time its ground gives the camp ([bandGround]),
    // standing for the parties it has out, foraging, fetching, visiting, raiding, with no count of who went where. Olli,
    // 14 Nov 2026: below the band, approximate. At a gathering all walked there.)
    return if (gathered) able else if (h == hw.placeHex[g.place]) able * homeShare(b) else min(able, BAND_PARTY)
}

/** How strong [g] is as it is met at hex [h], before any advantage of the ground: those there ([presentAt]), their weight and their weapons ([ferocity]). */
internal fun HexGroups.presentStrength(g: HexGroup, h: Hex, gathered: Boolean = false): Float = presentAt(g, h, gathered) * g.species.massKg * ferocity(g)

/** Whether hex [h] is [g]'s lair: a pack's home hex, a band's camp's hex. */
internal fun HexGroups.inLair(g: HexGroup, h: Hex): Boolean =
    (!g.home.none && h == g.home) || (g.band != null && h == hw.placeHex[g.place])

/** How much stronger [g] is at hex [h] for its being its lair ([inLair]): [HOME_ADVANTAGE] there, else 1. The one lair rule. */
internal fun HexGroups.lairAdvantage(g: HexGroup, h: Hex): Float = if (inLair(g, h)) HOME_ADVANTAGE else 1f

/** How strong [g] is as it is met at hex [h] ([presentStrength]), the stronger in its lair ([lairAdvantage]): what every meeting weighs. */
internal fun HexGroups.strengthAt(g: HexGroup, h: Hex, gathered: Boolean = false): Float = presentStrength(g, h, gathered) * lairAdvantage(g, h)

/**
 * Whether [a] is much the stronger of the two at hex [h]: of another kind, and more than [AVOID_RATIO] times as strong as
 * [b] by those present there ([presentStrength]). The one test of when the weaker keeps out of the way: hides from it
 * ([avoids]), must outrun it to get away ([escapes]), as the stand-ins weigh it too.
 */
internal fun HexGroups.muchStronger(a: HexGroup, b: HexGroup, h: Hex): Boolean =
    a.species != b.species && presentStrength(a, h) > AVOID_RATIO * presentStrength(b, h)

/**
 * The grown people of a band out foraging together that a beast or another band comes on away from its camp ([presentAt]):
 * a party of a few, as foragers go out in twos and threes to gather and in small parties to hunt; an estimate.
 */
internal const val BAND_PARTY = 4f
