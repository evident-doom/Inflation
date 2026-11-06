package inflation.ecology

import inflation.Part
import inflation.world.Date
import inflation.world.Hex
import inflation.world.Place
import inflation.world.get
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Scouting parties (t.parties; review/2026-11-06-scouting-party-design.md). A band with food to spare may send some of its
 * grown people out for some weeks to see country beyond its reach, while the rest keep the camp and its stores: the safe haven
 * the party comes back to. The party splits off as any band does ([splitOff]) and is a band of its own; what binds the two is a
 * contract between bands ([ContractKind.PARTY]), a pattern the engine writes down, never forced: the party walks out toward the
 * country most unlike home within its reach ([destination]), turns back there or halfway, and rejoins its band where it finds
 * it ([joinBand], its place memory the report); one that is not back well after it was due, or whose band is gone, stays a band
 * of its own. The risk of the walk is the party's, not the camp's.
 */

/** Share of a band's grown people, not elders, that goes on a party at the scaffold's will to explore (an estimate). */
internal const val PARTY_SHARE = 0.15f
/** Fewest grown people a party is made of. */
internal const val PARTY_MIN = 2
/** Weeks a party is out at the scaffold's will to explore (an estimate: "a month or two"). */
internal const val PARTY_WEEKS = 6f
/** Days of food each of a party's people carries from the band's larder; the rest stays at the camp. */
internal const val PARTY_FOOD_DAYS = 5f
/** Weeks after a party is due back that its contract lapses, and it is a band of its own. */
internal const val PARTY_GRACE = 8
/** Weeks a band keeps its whole number at camp after a party has come back (or was lost) before it sends another. */
internal const val PARTY_REST = 13
/** Metres of height that make country as different as another kind of country altogether ([difference]); an estimate. */
internal const val PARTY_HEIGHT_M = 500f
/** Water's share of a hex (lake) at which it counts as a lake to go and see. */
internal const val PARTY_LAKE = 0.1f
/** Share of its outward range that a party's destination is lifted by for distance: of two places as different, the farther. */
internal const val PARTY_FAR = 0.1f

/**
 * The contract of a party ([ContractKind.PARTY], [contract]): its band's id [band], the hex [homeHex] it left from, the country it
 * set out to see ([target], [destination]), the week [sent], the week it turns back at the latest [turn], [due] back.
 */
class Party(val band: Int, val homeHex: Hex, val target: Hex, val sent: Int, val turn: Int, val due: Int) {
    val contract get() = ContractKind.PARTY
    /** Whether it has been to the country it set out to see: then it turns back. */
    var reached = false
}

/** Whether [g] is out on a party for its band, or is a band with a party out: neither joins nor takes in another band meanwhile. */
internal fun HexGroups.inParty(g: HexGroup) = parties.containsKey(g.id) || partyOut.containsKey(g.id)

/**
 * The week's parties ([PARTY_SHARE]): each one out comes back to its band where it finds it once it has turned, is lost, or
 * stays a band of its own; then each band with food to spare ([hasSurplus]), settled in its camp and with no party out, sends
 * one, as many and for as long as its will to explore says ([BandWay.EXPLORING]). Serial, in the groups' order.
 */
@Part("people: scouting parties")
internal fun HexGroups.tendParties(date: Date) {
    if (!tuning.parties) return
    val week = stepOf(date)
    val byId = groups.associateBy { it.id }
    val iter = parties.entries.iterator()
    while (iter.hasNext()) {
        val (id, p) = iter.next()
        val party = byId[id]
        val home = byId[p.band]?.takeIf { it.alive }
        when {
            party == null || !party.alive -> { stats.meeting(Met.PARTIES_LOST); rested[p.band] = week }
            home == null || week >= p.due + PARTY_GRACE -> stats.meeting(Met.PARTIES_STAYED_AWAY)
            (p.reached || week >= p.turn) && hw.placeHex[party.place] == hw.placeHex[home.place] -> {
                joinBand(home, party, week); stats.meeting(Met.PARTIES_BACK); rested[p.band] = week
            }
            else -> continue
        }
        partyOut.remove(p.band); iter.remove()
    }
    val sent = ArrayList<HexGroup>()
    for (g in groups) {
        val b = g.band ?: continue
        if (!g.alive || inParty(g) || !hasSurplus(g, b) || b.campWeeks < PARTY_REST) continue
        if (rested[g.id]?.let { week - it < PARTY_REST } == true) continue
        val will = b.explore
        val k = partySize(g.adults, b.elders, will)
        if (k == 0) continue
        val weeks = max(2, (PARTY_WEEKS * will).roundToInt())
        val carry = g.dailyNeedKg * k / g.size.coerceAtLeast(1) * PARTY_FOOD_DAYS
        // (Those who go leave the band first, as at any split ([liveWeek]): [splitOff] makes the new band, it takes no one from the old.)
        g.adults -= k
        val n = splitOff(g, k, 0)
        // (It carries a few days' food; the rest of its share stays in the camp's larder.)
        if (n.larder > carry) moveFood(n, g, n.larder - carry)
        val home = hw.placeHex[g.place]
        val rangeKm = weeks / 2 * g.species.maxDailyTravelKm * SHIFT_DAYS
        parties[n.id] = Party(g.id, home, destination(home, rangeKm), week, week + weeks / 2, week + weeks)
        partyOut[g.id] = n.id
        sent += n
        stats.meeting(Met.PARTIES_SENT)
    }
    groups += sent
}

/**
 * How different country [h] is from [home], 0 alike: the share of its land of other kinds ([inflation.world.Biome]), its height
 * against [PARTY_HEIGHT_M], and the sea or a lake where home has none, each up to 1. What a party sets out to see: the mountains,
 * the sea, a lake, another kind of country (Olli 2026-11-06), the places that might feed the band if its own ground gives out.
 */
internal fun HexGroups.difference(h: Hex, home: Hex): Float {
    var kinds = 0f
    for (b in hw.biome) kinds += kotlin.math.abs(b[h] - b[home])
    val height = min(1f, kotlin.math.abs(hw.elevationM[h] - hw.elevationM[home]) / PARTY_HEIGHT_M)
    val sea = if (hw.coast[h] && !hw.coast[home]) 1f else 0f
    val lake = if (hw.lake[h] >= PARTY_LAKE && hw.lake[home] < PARTY_LAKE) 1f else 0f
    return 0.5f * kinds + height + max(sea, lake)
}

/** The land hex within [rangeKm] of [home] most different from it ([difference]), the farther of two alike; [home] if none is. */
internal fun HexGroups.destination(home: Hex, rangeKm: Float): Hex {
    var best = home; var top = 0f
    var ring = listOf(home); val seen = hashSetOf(home)
    val rings = (rangeKm / hex.hexKm).toInt()
    for (r in 1..rings) {
        ring = hex.nextRing(ring, seen)
        for (h in ring) {
            if (hw.land[h] < 0.5f) continue
            val d = difference(h, home) * (1f + PARTY_FAR * r / rings.coerceAtLeast(1))
            if (d > top) { top = d; best = h }
        }
    }
    return best
}

/**
 * Where a party goes this week ([Party]), of the places weighed in [buf] no deadlier than the safest by a survival step:
 * going out, the nearest to the country it set out to see ([Party.target]), until it is there or it is time to turn; then the
 * nearest to its band's camp (where it is now, or where it left it). [best] if it can weigh none.
 */
internal fun GroupPlanner.partyPlace(p: Party, here: Hex, buf: GroupPlanner.Scratch, best: Place, week: Int): Place {
    val n = buf.explPlaces.size
    var safest = Float.POSITIVE_INFINITY
    for (k in 0 until n) if (buf.explDeaths[k].isFinite() && buf.explDeaths[k] < safest) safest = buf.explDeaths[k]
    if (!safest.isFinite()) return best
    val hw = gs.hw
    if (here == p.target) p.reached = true
    val back = p.reached || week >= p.turn
    val to = if (back) gs.bands.groupById[p.band]?.takeIf { it.alive }?.let { hw.placeHex[it.place] } ?: p.homeHex else p.target
    var pick = -1; var near = Double.POSITIVE_INFINITY
    for (k in 0 until n) {
        if (!buf.explDeaths[k].isFinite() || buf.explDeaths[k] - safest > 1f) continue
        val d = gs.hex.distanceKm(hw.placeHex[buf.explPlaces[k]], to)
        if (d < near) { near = d; pick = k }
    }
    return if (pick < 0) best else Place(buf.explPlaces[pick])
}

/** How many grown people a band of [adults] (of them [elders]) would send at will to explore [will], or 0 if it would send none. */
internal fun partySize(adults: Int, elders: Int, will: Float): Int {
    val able = adults - elders
    val k = max(PARTY_MIN, (PARTY_SHARE * will * able).roundToInt())
    return if (k > able || adults - k < SMALL_BAND) 0 else k
}
