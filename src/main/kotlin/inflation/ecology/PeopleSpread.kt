package inflation.ecology

import inflation.Part
import inflation.world.Hex
import inflation.world.Place
import kotlin.math.max
import kotlin.math.min

/*
 * How people spread over the land when bands hold their home hex ([staysHome], t.bandsStay): not by treks, but as a band's
 * daughters settle where there is room within a few days' walk ([findHome]), as households drift year by year from a hex
 * crowded for what it offers to a roomier one beside it, and as a band tied to no spot leaves a hex that has become much
 * poorer than one beside it ([spreadYear]). One measure for all three: a hex's people against how well its ground suits
 * people ([hexSuit]: its places' suitable area, km²), its crowding.
 *
 * Why (Olli, 10 Nov 2026): with daughters taking only an empty hex beside the parent's, people spread only from the edge
 * of each patch of held hexes; a world's people levelled off where its start left them, a world started with more bands
 * higher (overpop698: x0.5 0.03-0.05 per km², x4 0.10-0.12 after 300 years). The spread of farming went on at about a
 * kilometre a year (Ammerman and Cavalli-Sforza 1971, from memory: to check), by many short moves, not by long ones.
 * The rates below are estimates, to be measured against that.
 */

/** How many rings of hexes about its parent's a daughter band weighs for its home (about three days' walk, what kin know). */
internal const val SPREAD_RINGS = 3
/** How much less a hex a ring further off is worth to a daughter band than one nearer: the further, the less known. */
internal const val SPREAD_RING_KEEP = 0.7f
/** Households leaving a crowded hex a year, a share of the band's people for each unit by which the roomiest hex beside is roomier. */
internal const val SEEP_RATE = 0.03f
/** At most that share of a band's people leave in a year. */
internal const val SEEP_MOST = 0.05f
/** A band tied to its spot ([livesFixed]: houses, fields, a palisade) loses households at that share of the rate. */
internal const val SEEP_FIXED = 0.3f
/** The roomiest hex beside must be roomier by this share before any household leaves for it. */
internal const val SEEP_MARGIN = 0.25f
/** The fewest grown people who leave together: a household or two. */
internal const val SEEP_ADULTS = 3
/** A band tied to no spot moves its whole camp to the hex beside when that hex is this many times roomier, its band there counted. */
internal const val RELOCATE_GAP = 3f
/**
 * Or, when its food has fallen short over the year by this many days ([Band.leanDays]: its ground has worsened, by drought,
 * a shifting climate or the game gone), already when the hex beside is [RELOCATE_LEAN_GAP] times roomier. (Suitability,
 * [hexSuit], is the ground's lasting worth and does not see a bad run of years; the band's own shortfall does.)
 */
internal const val RELOCATE_LEAN_DAYS = 60f
internal const val RELOCATE_LEAN_GAP = 1.5f

/**
 * Land fit for foragers, for the census: a hex whose ground suits people ([hexSuit]) over at least this share of its area.
 * Olli (10 Nov 2026): all such land should hold people at least sparsely, the status quo from which farming builds
 * denser numbers. An estimate.
 */
internal const val FORAGE_LAND = 0.2f

/**
 * Whether band [g] wants for food: its people's reserves are down below [FERTILE_RESERVE] of full, where its mothers
 * conceive the less ([reserveFertility]). Only then do its buds leave its hex ([findHome]) and its households move off
 * ([spreadYear]). Foragers spread to new country as their own grows short in its worst season, not because the next
 * valley holds fewer people (Binford 2001, Kelly 2013, from memory: density follows what the leanest season gives). So a
 * hex fills to what its food carries, many bands on rich ground and one thin band on poor, before people move on.
 * (The band's lean days, [Band.leanDays], were tried first: they count the seasons' shortfall before the larder pays, so
 * nearly every band had them, fit699d.)
 */
internal fun wantsFood(g: HexGroup) = g.fat < FERTILE_RESERVE * g.species.maxFatDays

/** How well hex [h]'s ground suits [s]: its places' area weighted by how they suit the kind ([HexFauna.startWeight]), km². */
internal fun HexGroups.hexSuit(s: Species, h: Hex): Float {
    var w = 0f
    for (pi in hw.placesOf(h)) w += fauna.startWeight(s, Place(pi))
    return w
}

/** The people of the bands living in each hex, by the hex's index, those in [also] counted too (bands made this step). */
internal fun HexGroups.bandPeopleByHex(also: List<HexGroup> = emptyList()): FloatArray {
    val people = FloatArray(hex.size)
    for (g in groups) if (g.alive && g.band != null) people[hw.placeHex[g.place].i] += g.size.toFloat()
    for (g in also) if (g.alive && g.band != null) people[hw.placeHex[g.place].i] += g.size.toFloat()
    return people
}

/**
 * Where band [n], just split off its parent ([splitOff], at the parent's place), makes its home when bands hold their
 * home hex ([staysHome]): of its parent's hex and the hexes within [SPREAD_RINGS] rings of it, the one with the most
 * suitable ground for each person once it is there ([hexSuit] over the people there and its own), a ring further off
 * worth [SPREAD_RING_KEEP] as much; held or empty alike. Staying in the parent's hex, the two share it. People spread by
 * such budding a few hexes at a time, never on a trek. (It carries what it took from its parent: the move itself is
 * charged at its first camp there, [campAndCache], as any band's leaving.)
 */
internal fun HexGroups.findHome(n: HexGroup, born: List<HexGroup>) {
    val from = hw.placeHex[n.place]
    // (A bud of a band that wants for no food stays on its parent's ground, a band of its own: [wantsFood].)
    if (!wantsFood(n)) { stats.meeting(Met.BANDS_BUDDED_IN_PLACE); return }
    val people = bandPeopleByHex(born)
    val own = n.size.toFloat()
    var best = Place.NONE
    var bestV = hexSuit(n.species, from) / (people[from.i] + own)
    var ring = listOf(from); val seen = hashSetOf(from); var keep = 1f
    for (r in 1..SPREAD_RINGS) {
        ring = hex.nextRing(ring, seen); keep *= SPREAD_RING_KEEP
        for (h in ring) {
            if (hw.placesOf(h).isEmpty()) continue
            val v = keep * hexSuit(n.species, h) / (people[h.i] + own)
            if (v > bestV) { bestV = v; best = hw.firstPlaceIn(h) }
        }
    }
    if (best.none) { stats.meeting(Met.BANDS_BUDDED_IN_PLACE); return }
    n.place = best
    stats.meeting(Met.BANDS_BUDDED_OUT)
}

/**
 * Once a year (the step that holds the year's first month start, whatever its length): bands that hold their home hex
 * ease their crowding into the hexes beside. For each, the roomiest hex beside ([hexSuit] over the people there and a
 * household more) against its own; where that is roomier by more than [SEEP_MARGIN]:
 * - a band tied to no spot moves its whole camp there if it is [RELOCATE_GAP] times roomier, its own people counted
 *   (the hex beside emptied), or [RELOCATE_LEAN_GAP] times when its food fell short by [RELOCATE_LEAN_DAYS] over the year
 *   (its ground has worsened);
 * - otherwise some households leave for it, [SEEP_RATE] of its people for each unit of the gap, at most [SEEP_MOST], a
 *   tied band [SEEP_FIXED] as many, in parties of [SEEP_ADULTS] grown people with their share of the young (of
 *   [SMALL_BAND] to an empty hex), as a band of their own ([splitOff]); a party too small to live alone joins kin there
 *   ([mergeAndPair]).
 * Returns the bands made, to be added to the groups.
 */
@Part("people: spread")
internal fun HexGroups.spreadYear(): List<HexGroup> {
    val made = ArrayList<HexGroup>()
    if (!tuning.bandsStay) return made
    val people = bandPeopleByHex()
    val suit = FloatArray(hex.size) { Float.NaN }
    fun suitAt(s: Species, h: Hex): Float { if (suit[h.i].isNaN()) suit[h.i] = hexSuit(s, h); return suit[h.i] }
    for (g in groups.toList()) {
        val b = g.band ?: continue
        if (!g.alive || !staysHome(g) || inParty(g) || g.adults < SMALL_BAND + SEEP_ADULTS || !wantsFood(g)) continue
        // (Each year's draw from the band's own stream, so the year runs the same in any order.)
        val h = hw.placeHex[g.place]
        val here = suitAt(g.species, h) / max(1f, people[h.i])
        var best = Hex.NONE; var bestV = 0f
        for (d in 0 until 6) {
            val q = hex.neighbour(h, d)
            if (q.none || hw.placesOf(q).isEmpty()) continue
            val v = suitAt(g.species, q) / (people[q.i] + SEEP_ADULTS)
            if (v > bestV) { bestV = v; best = q }
        }
        if (best.none || bestV <= here * (1f + SEEP_MARGIN)) continue
        val fixed = b.livesFixed()
        val gapThere = suitAt(g.species, best) / (people[best.i] + g.size) / here
        if (!fixed && (gapThere >= RELOCATE_GAP || (b.leanDays >= RELOCATE_LEAN_DAYS && gapThere >= RELOCATE_LEAN_GAP))) {
            people[h.i] -= g.size; people[best.i] += g.size
            g.place = hw.firstPlaceIn(best)
            stats.meeting(Met.BANDS_RELOCATED)
            continue
        }
        val share = min(SEEP_MOST, SEEP_RATE * (bestV / here - 1f)) * (if (fixed) SEEP_FIXED else 1f)
        // (A party goes whole or not at all: as often as the year's share of its people makes up a party. To an empty hex
        // it must be a band that can live alone, else it would only join kin back home, [mergeAndPair].)
        val party = if (people[best.i] > 0f) SEEP_ADULTS else SMALL_BAND
        if (g.adults - party < SMALL_BAND || rnd.nextFloat() >= g.adults * share / party) continue
        val adults = party
        val young = g.young * adults / g.adults
        g.adults -= adults; g.young -= young
        val n = splitOff(g, adults, young)
        n.place = hw.firstPlaceIn(best)
        people[h.i] -= n.size; people[best.i] += n.size
        made += n
        stats.meeting(Met.HOUSEHOLDS_SEEPED)
    }
    return made
}
