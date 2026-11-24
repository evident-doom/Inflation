package inflation.ecology

import inflation.Par
import inflation.Part
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

// Bands' spread over their ground (docs/design/band-flow.md, step 1): the share of a band's time camped at its home hex and at
// each of the six beside it, flowing each step toward where food a head draws it, at a pace the step's length does not change.

/** Where a band's home hex stands in its [Band.spread]; the hex beside it in direction d stands at 1 + d. */
internal const val SPREAD_HOME = 0
internal const val SPREAD_SIZE = 7

/**
 * How sharply a band's spread follows food a head ([flowSpreads]): a hex beside whose food a head is e^[SPREAD_SOFTNESS]
 * times the home's, over what moving camp there costs, draws e times the home's share of its time. Soft, so a band
 * splits its time between places nearly as good rather than going all to the best. An estimate, to tune.
 */
internal const val SPREAD_SOFTNESS = 0.1f

/**
 * Each step, the spread of each band that holds its home hex ([staysHome]) closes on where food a head now draws it, by
 * the share 1 - e^(-days / [inflation.Tuning.bandFlowWeeks] weeks): the same over a year whatever the step's length.
 * - Where it draws it: each hex beside by its food a head now ([hexFoodNow], over the people of the bands living there and
 *   the band's own) against its home's, less what a stay there costs ([spreadCost]): a share of the time e^((ln ratio -
 *   ln(1 + cost)) / [SPREAD_SOFTNESS]) of the home's. Not hexes it keeps from ([shuns]), at sea or with no ground.
 * - Its houses at home draw it home by what they save it there ([spreadCost]): a pull, not a lock, so hunger can still
 *   take it out about its ground. A band tied to a spot by fresh sowing or a palisade ([pinnedHome]) is drawn wholly home.
 * - A band out on a party, or that has come to live in another hex, starts wholly at home there.
 * Its foraging reads the spread ([spreadParts]), and what houses are worth to it reads its time at home ([Band.homeTime],
 * [housePays]). Moving a band's home is left to [moveCamps] and the budding as before.
 */
@Part("people: spread over the ground")
internal fun HexGroups.flowSpreads(weather: inflation.world.Weather) {
    if (!tuning.bandsStay) return
    val people = bandPeopleByHex()
    // (Each hex's food now for people, worked out once a step, when first wanted: bands' reads of one hex race only to write the same number.)
    val food = FloatArray(hex.size) { Float.NaN }
    fun foodAt(g: HexGroup, h: inflation.world.Hex): Float { var f = food[h.i]; if (f.isNaN()) { f = hexFoodNow(g.species, h); food[h.i] = f }; return f }
    val closes = 1f - exp(-days / (7f * tuning.bandFlowWeeks))
    val year = 1f - exp(-days / inflation.world.Date.DAYS_PER_YEAR.toFloat())
    val today = weather.date.absoluteDay
    val gs = groups
    Par.forEach("people: spread over the ground", gs.size) { i ->
        val g = gs[i]; val b = g.band
        if (b != null && g.alive && staysHome(g)) {
            val h = hw.placeHex[g.place]
            if (b.spreadHex != h.i) { b.spread.fill(0f); b.spread[SPREAD_HOME] = 1f; b.spreadHex = h.i; b.houseKgDay = Long.MIN_VALUE }
            // (What houses at its camp would save it, worked out afresh each month: [housePays], and the pull of those it has.)
            if (today - b.houseKgDay >= inflation.world.Date.DAYS_PER_MONTH) { b.houseKgYear = houseFoodAYear(g, b, g.place, weather); b.houseKgDay = today }
            val target = FloatArray(SPREAD_SIZE)
            target[SPREAD_HOME] = 1f
            if (!inParty(g) && !pinnedHome(b)) {
                val here = foodAt(g, h) / max(1f, people[h.i])
                val cost = ln(1f + spreadCost(g, b))
                for (d in 0 until 6) {
                    val q = hex.neighbour(h, d)
                    if (q.none || hw.placesOf(q).isEmpty() || shuns(g, q)) continue
                    val there = foodAt(g, q) / (people[q.i] + g.size)
                    target[1 + d] = if (there <= 0f) 0f else if (here <= 0f) 1f
                        else exp(min(30f, (ln(there / here) - cost) / SPREAD_SOFTNESS))
                }
            }
            var sum = 0f; for (x in target) sum += x
            for (k in 0 until SPREAD_SIZE) b.spread[k] += (target[k] / sum - b.spread[k]) * closes
            b.homeTime += (b.spread[SPREAD_HOME] - b.homeTime) * year
        }
    }
}

/**
 * What a stay in a hex beside costs [g]'s band [b], as a share of what its food there must beat its home's by: the days it
 * walks there and back with its camp ([moveDays], a hex's width each way) over the days of a stay
 * ([inflation.Tuning.bandFlowWeeks]); and, living in its houses ([Band.atHome]), the share of its need they save it while
 * there ([houseSaves] as far as they stand); and its stores ([storesFetched]). Not the stores it could not carry
 * ([leftBehindShare]), as a whole camp move leaves them: spread over its ground, the band keeps its stores at its home camp
 * and comes and goes (Olli 2026-11-23: a hard loss on leaving belongs to the discrete model).
 */
internal fun GroupWorld.spreadCost(g: HexGroup, b: Band) =
    2f * moveDays(g, hex.hexKm.toFloat()) / (7f * tuning.bandFlowWeeks) + storesFetched(g, b) +
        (if (b.atHome) b.house * houseSaves(b) / (g.species.dailyIntakeKg * inflation.world.Date.DAYS_PER_YEAR) else 0f)

/**
 * What living on its stores costs [g]'s band [b] at a camp in a hex beside, as a share of the stay's food: its stores stay at
 * its home camp, and what of the stay's need they would meet (its larder over the stay's need, up to all of it) is carried
 * out a hex's width, there and back for each load ([GATHER_LOAD_KG], more with baskets, at [DAY_WALK_KM] a day), in the
 * days of foraging those walks take ([handDayKg]). A band wintering on its stores is held home by them; one living from
 * hand to mouth is not. (In place of losing what it could not carry, the discrete model's rule.)
 */
internal fun GroupWorld.storesFetched(g: HexGroup, b: Band): Float {
    val stay = 7f * tuning.bandFlowWeeks
    val share = min(1f, g.larder / (g.dailyNeedKg * stay).coerceAtLeast(1e-3f))
    if (share <= 0f) return 0f
    val daysPerKg = 2f * hex.hexKm.toFloat() / (DAY_WALK_KM * GATHER_LOAD_KG * (1f + BASKET_GAIN * b.has(Tool.BASKET)))
    return share * daysPerKg * handDayKg(g, b)
}

/** The food a day of one of [g]'s band [b]'s hands at foraging brings, kg: its yield this week ([Band.yieldNow], days of its people's need a day of all its hands), else a day's need a hand. */
internal fun handDayKg(g: HexGroup, b: Band): Float {
    val yieldDay = b.yieldNow.takeIf { it.isFinite() && it > 0f } ?: 1f
    return yieldDay * g.dailyNeedKg / max(1, g.adults)
}

/** Whether band [b] is held wholly at home: its fresh sowing to tend ([Band.sinceSowed]) or its palisade. (Not its houses: they pull, [spreadCost].) */
internal fun pinnedHome(b: Band) = (b.sownKg > 0f && b.sinceSowed < 1f) || b.palisade > 0f

/** The food a whole house saves each of [b]'s people a year, kg: as it lived its weeks ([Band.houseFood]), or keeping warm at the place's usual cold if more ([Band.houseKgYear]). */
internal fun houseSaves(b: Band) = max(b.houseFood, b.houseKgYear.takeIf { it.isFinite() } ?: 0f)

/**
 * Whether houses at its camp (place [p]) pay [g]'s band [b], at its keenness [will] ([BandWay.HOUSES], 1 the scaffold's):
 * what they would save each of its people over the years it expects to keep the place ([keepYears]), while it is at home
 * ([Band.homeTime]: a band out about its ground half the year has half the use of them), against the food its hands would
 * have brought in the days building takes ([HOUSE_DAYS] a person, at its foraging's yield, [handDayKg]). The food they
 * save: keeping warm, stores kept from spoiling and mice, less to raiders ([Band.houseFood] as it lived, or the warmth at
 * the place's usual cold, [Band.houseKgYear]). In place of building after a fixed half year at a camp
 * (docs/design/band-flow.md, step 3).
 */
internal fun GroupWorld.housePays(g: HexGroup, b: Band, will: Float): Boolean {
    // (As the planning kind weighs them ([planDays]): the food a whole house would have saved it a person a year as it lived
    // its weeks ([Band.houseFood]), or at the place's usual cold if more ([Band.houseKgYear]).)
    val saved = houseSaves(b)
    if (!(saved > 0f)) return false
    val worth = saved * b.homeTime * keepYears(b, ticksPerYear)
    return will * worth >= HOUSE_DAYS * handDayKg(g, b)
}

/**
 * How much of each hex of its ground [b]'s gatherers work this step, as shares of a camp's own hex ([ForageOffers]): of
 * [ring] its home hex's spread at [SPREAD_HOME] (then each hex beside, 1 + the direction) as [Band.spread] has its camps.
 * A camp works its own hex whole and each hex beside it at [ring] ([RING_REACH] as the band's range has it): from home, the
 * six beside; from a hex beside, home, the two beside both, and three hexes further out, which are counted as that hex's
 * own ground (the ground the band forages is the seven hexes). With every camp at home: 1 at home and [ring] beside, as
 * before the spread. Into [part]; into [off] the share of each that is worked from a camp in another hex (walked out to).
 */
internal fun spreadParts(b: Band, ring: Float, part: FloatArray, off: FloatArray) {
    val s = b.spread
    var away = 0f; for (d in 0 until 6) away += s[1 + d]
    part[SPREAD_HOME] = s[SPREAD_HOME] + ring * away
    off[SPREAD_HOME] = if (part[SPREAD_HOME] > 0f) ring * away / part[SPREAD_HOME] else 0f
    for (d in 0 until 6) {
        val own = s[1 + d]
        val from = ring * (3f * own + s[SPREAD_HOME] + s[1 + (d + 5) % 6] + s[1 + (d + 1) % 6])
        part[1 + d] = own + from
        off[1 + d] = if (part[1 + d] > 0f) from / part[1 + d] else 0f
    }
}
