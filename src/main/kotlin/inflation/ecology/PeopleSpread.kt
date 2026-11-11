package inflation.ecology

import inflation.Part
import inflation.world.Hex
import inflation.world.Place
import inflation.world.Date
import inflation.world.Weather
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
 * Of a band past its easy size ([HexGroups.easySize]), the chance a year that those it holds beyond it leave ([discord]).
 * Forager households move between camps every few weeks (Lee 1979, Woodburn 1982, from memory); a year's single draw at
 * this chance keeps the band near its easy size whatever its growth. An estimate.
 */
internal const val DISCORD_LEAVE = 0.5f

/**
 * Land fit for foragers, for the census: a hex whose ground suits people ([hexSuit]) over at least this share of its area.
 * Olli (10 Nov 2026): all such land should hold people at least sparsely, the status quo from which farming builds
 * denser numbers. An estimate.
 */
internal const val FORAGE_LAND = 0.2f

/**
 * Whether band [g] wants for food: its people's reserves fell below [FERTILE_RESERVE] of full at some step of its year
 * ([Band.fatLow]; read once a year they missed the lean months, fit699e), where its mothers
 * conceive the less ([reserveFertility]). Only then do its buds leave its hex ([findHome]) and its households move off
 * ([spreadYear]). Foragers spread to new country as their own grows short in its worst season, not because the next
 * valley holds fewer people (Binford 2001, Kelly 2013, from memory: density follows what the leanest season gives). So a
 * hex fills to what its food carries, many bands on rich ground and one thin band on poor, before people move on.
 * (The band's lean days, [Band.leanDays], were tried first: they count the seasons' shortfall before the larder pays, so
 * nearly every band had them, fit699d.)
 */
internal fun wantsFood(g: HexGroup) = (g.band?.fatLow ?: (g.fat / g.species.maxFatDays.coerceAtLeast(1f))) < FERTILE_RESERVE

/** How well hex [h]'s ground suits [s]: its places' area weighted by how they suit the kind ([HexFauna.startWeight]), km². */
internal fun HexGroups.hexSuit(s: Species, h: Hex): Float {
    var w = 0f
    for (pi in hw.placesOf(h)) w += fauna.startWeight(s, Place(pi))
    return w
}

/**
 * Share of the palatable forage standing on the ground (grass and browse) that comes to people as game a year: grazers and
 * browsers make some few per cent of their forage into flesh a year, and hunters take a fraction of that (an estimate, of
 * the order of secondary production in grassland, Coe et al. 1976, from memory, and a hunter's take of a fifth).
 */
internal const val GAME_OF_FORAGE = 0.01f
/** Share of the roots in the ground that people dig a year where they live on them (an estimate). */
internal const val ROOTS_TAKEN = 0.1f
/**
 * Share of the fish a band could catch at most step by step ([HexWater.fishWithin]) that it lands over a year: small, as
 * the model's bands eat next to no fish (diet censuses since 2026-10-22; Olli 2026-11-10). An estimate.
 */
internal const val FISH_TAKEN = 0.03f

/**
 * What place [p] gives people a year, kg of food: game off its forage ([GAME_OF_FORAGE]) and roots dug from its ground
 * ([ROOTS_TAKEN]). The foods of the lean months: fruit, seed and grain come in their season and keep only as stores, so
 * they are left out. (Forager density follows the land's productivity and, where it is cold, its waters: Binford 2001,
 * Kelly 2013, from memory.) A measure for where people start and spread to, against how many hold it; not food anyone eats.
 */
internal fun HexGroups.placeFood(p: Place): Float {
    val m2 = land.areaKm2[p.i] * 1e6f
    val forage = land.palatable[p.i] * (land.plants.herb[p.i] + land.plants.shrub[p.i]) * m2
    return forage * GAME_OF_FORAGE + fauna.food.roots[p.i] * m2 * ROOTS_TAKEN
}

/** What hex [h] gives people a year, kg of food: its places' ([placeFood]) and the fish of its waters ([FISH_TAKEN]). */
internal fun HexGroups.hexFood(h: Hex): Float {
    var kg = 0f
    for (pi in hw.placesOf(h)) kg += placeFood(Place(pi))
    // (The fish and shellfish within reach, not the spring run, which comes in its season.)
    val fish = fauna.water.fishWithin(h, 1f, HexWater.Catch.FISH) + fauna.water.fishWithin(h, 1f, HexWater.Catch.SHELLFISH)
    return kg + fish * Date.DAYS_PER_YEAR / days * FISH_TAKEN
}

/** The people of the bands living in each hex, by the hex's index, those in [also] counted too (bands made this step). */
internal fun HexGroups.bandPeopleByHex(also: List<HexGroup> = emptyList()): FloatArray {
    val people = FloatArray(hex.size)
    for (g in groups) if (g.alive && g.band != null) people[hw.placeHex[g.place].i] += g.size.toFloat()
    for (g in also) if (g.alive && g.band != null) people[hw.placeHex[g.place].i] += g.size.toFloat()
    return people
}

/** Whether any band living in each hex wants for food ([wantsFood]), by the hex's index: its ground is full. */
internal fun HexGroups.hexesWanting(): BooleanArray {
    val w = BooleanArray(hex.size)
    for (g in groups) if (g.alive && g.band != null && wantsFood(g)) w[hw.placeHex[g.place].i] = true
    return w
}

/**
 * Where band [n], just split off its parent ([splitOff], at the parent's place), makes its home when bands hold their
 * home hex ([staysHome]): an empty hex beside its parent's if there is one (the richest of them), so settlement spreads at
 * its edge (night705: without it, buds stayed home, the held share fell from 7% to 5-6% and bands grew to 40-46);
 * else the nearest hex whose ground still feeds the people on it. It stays in its parent's hex unless
 * some band there wants for food ([wantsFood]: its people's reserves fell low at some step of its year); else it goes to
 * the nearest ring, within [SPREAD_RINGS], holding a hex where no band wants, and in that ring to the hex with the most
 * food a head once it is there ([hexFood]). Forager groups split off within or beside their home range and move on only
 * as it grows short (Binford 2001, Kelly 2013, from memory), so a rich hex fills with several bands before its buds go,
 * and a poor one sends them on with one. Where every hex within reach wants, it goes where food a head is most, as
 * before (an ideal free distribution: Fretwell and Lucas 1970). People spread by such budding a few hexes at a time,
 * never on a trek. (It carries what it took from its parent: the move itself is charged at its first camp there,
 * [campAndCache], as any band's leaving.) The rule before (2026-11-10, fit699h-dens704): always the most food a head,
 * held or empty alike, which while food never ran short sent every bud to an empty hex: one band a hex.
 */
internal fun HexGroups.findHome(n: HexGroup, born: List<HexGroup>) {
    val from = hw.placeHex[n.place]
    val people = bandPeopleByHex(born)
    // (First an empty hex beside its parent's, the one whose ground gives most: daughters take up the range next door.)
    var empty = Hex.NONE; var emptyFood = 0f
    for (d in 0 until 6) {
        val q = hex.neighbour(from, d)
        if (q.none || hw.placesOf(q).isEmpty() || people[q.i] > 0f || shuns(n, q)) continue
        val f = hexFood(q)
        if (f > emptyFood) { emptyFood = f; empty = q }
    }
    if (!empty.none) { n.place = hw.firstPlaceIn(empty); stats.meeting(Met.BANDS_BUDDED_OUT); return }
    val wanting = hexesWanting()
    if (!wanting[from.i]) { stats.meeting(Met.BANDS_BUDDED_IN_PLACE); return }
    val own = n.size.toFloat()
    var best = Place.NONE
    val bestV = hexFood(from) / (people[from.i] + own)
    // (Failing a ring that feeds, the most food a head over all of them.)
    var fallback = Place.NONE; var fallbackV = bestV
    var ring = listOf(from); val seen = hashSetOf(from); var keep = 1f
    for (r in 1..SPREAD_RINGS) {
        ring = hex.nextRing(ring, seen); keep *= SPREAD_RING_KEEP
        var ringV = 0f
        for (h in ring) {
            if (hw.placesOf(h).isEmpty() || shuns(n, h)) continue
            val v = hexFood(h) / (people[h.i] + own)
            if (!wanting[h.i] && v > ringV) { ringV = v; best = hw.firstPlaceIn(h) }
            if (keep * v > fallbackV) { fallbackV = keep * v; fallback = hw.firstPlaceIn(h) }
        }
        if (!best.none) break
    }
    if (best.none) best = fallback
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
internal fun HexGroups.spreadYear(date: Date): List<HexGroup> {
    val made = ArrayList<HexGroup>()
    if (!tuning.bandsStay) return made
    val people = bandPeopleByHex()
    val wanting = hexesWanting()
    val bandsIn = HashMap<Int, ArrayList<HexGroup>>()
    for (g in groups) if (g.alive && g.band != null) bandsIn.getOrPut(hw.placeHex[g.place].i) { ArrayList() } += g
    val suit = FloatArray(hex.size) { Float.NaN }
    fun suitAt(s: Species, h: Hex): Float { if (suit[h.i].isNaN()) suit[h.i] = hexFood(h); return suit[h.i] }
    for (g in groups.toList()) {
        val b = g.band ?: continue
        if (!g.alive || !staysHome(g) || inParty(g)) continue
        if (discord(g, date, people, wanting, bandsIn, made)) continue
        if (g.adults < SMALL_BAND + SEEP_ADULTS) continue
        // (Only a band that wants for food ([wantsFood]) sends households off, to a hex beside whose bands do not, the one with
        // most food a head, as its buds go ([findHome]); or moves whole.)
        val want = wantsFood(g)
        if (!want) continue
        // (Each year's draw from the band's own stream, so the year runs the same in any order.)
        val h = hw.placeHex[g.place]
        val here = suitAt(g.species, h) / max(1f, people[h.i])
        var best = Hex.NONE; var bestV = 0f
        for (d in 0 until 6) {
            val q = hex.neighbour(h, d)
            if (q.none || hw.placesOf(q).isEmpty() || wanting[q.i] || shuns(g, q)) continue
            val v = suitAt(g.species, q) / (people[q.i] + SEEP_ADULTS)
            if (v > bestV) { bestV = v; best = q }
        }
        if (best.none || bestV <= here * (1f + SEEP_MARGIN)) continue
        val fixed = b.livesFixed()
        val gapThere = suitAt(g.species, best) / (people[best.i] + g.size) / here
        if (want && !fixed && (gapThere >= RELOCATE_GAP || (b.leanDays >= RELOCATE_LEAN_DAYS && gapThere >= RELOCATE_LEAN_GAP))) {
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
    // (The year's lowest reserves start afresh once read: [Band.fatLow]. The buds of the year to come read it as they part.)
    for (g in groups) g.band?.let { b -> b.fatLow = g.fat / g.species.maxFatDays.coerceAtLeast(1f) }
    return made
}

/**
 * Once a year, band [g] past its easy size ([HexGroups.easySize]) quarrels, and at [DISCORD_LEAVE] those beyond it leave
 * (Olli, 11 Nov 2026: band size is not all food; group dynamics split bands). Enough to live alone ([SMALL_BAND] grown) go
 * as a band of their own and settle as any daughter band ([findHome]: an empty hex beside first). Fewer, a household or
 * two ([SEEP_ADULTS] at least), join the band of the same mind in its hex or one beside, whose people do not want for food
 * ([wantsFood]), with the most room under its own easy size; with none, the band splits in two camps that can each live
 * alone, [SMALL_BAND] grown going (discord711: with none taking them in, bands stayed up to a small band past their easy
 * size and drifted from 27 to 35-38 people over 60 years); too small for that, they stay. Fed or not: households leave a camp
 * over quarrels, not only hunger. [people] and [bandsIn] are kept up to date; bands made go to [made]. Returns whether
 * anyone left.
 */
private fun HexGroups.discord(g: HexGroup, date: Date, people: FloatArray, wanting: BooleanArray,
                              bandsIn: HashMap<Int, ArrayList<HexGroup>>, made: MutableList<HexGroup>): Boolean {
    val b = g.band ?: return false
    val over = g.size - easySize(g)
    // (A band grown small again, below its kind's easy size, needs its way no longer: it lapses a step at a time.)
    if (b.disputeWay != DisputeWay.LEAVING && g.size < g.species.maxGroupSize / 2 && rnd.nextFloat() < DISPUTE_LAPSE) {
        b.disputeWay = DisputeWay.entries[b.disputeWay.ordinal - 1]; stats.meeting(Met.BANDS_LET_A_DISPUTE_WAY_LAPSE)
    }
    if (over <= 0 || rnd.nextFloat() >= DISCORD_LEAVE) return false
    val adults = min(over * g.adults / g.size, g.adults - SMALL_BAND)
    if (adults < SEEP_ADULTS) return false
    val young = g.young * adults / g.adults
    val h = hw.placeHex[g.place]
    // (Where leaving is dear, tied to its spot or with no empty hex beside, the band may take up the next way of settling
    // quarrels; its people leave this time all the same.)
    if (b.disputeWay != DisputeWay.HEADMAN && (b.livesFixed() || (0 until 6).none { d -> hex.neighbour(h, d).let { q ->
            !q.none && hw.placesOf(q).isNotEmpty() && people[q.i] <= 0f } }) && rnd.nextFloat() < DISPUTE_TAKE_UP) {
        b.disputeWay = DisputeWay.entries[b.disputeWay.ordinal + 1]; stats.meeting(Met.BANDS_TOOK_UP_A_DISPUTE_WAY)
    }
    // (A camp of their own: those beyond the easy size if they are enough to live alone; else, with no camp nearby to take
    // them in (below), [SMALL_BAND] of them, those who side with them going along, if as many stay.)
    fun ownCamp(going: Int): Boolean {
        val goingYoung = g.young * going / g.adults
        g.adults -= going; g.young -= goingYoung
        val n = splitOff(g, going, goingYoung)
        findHome(n, made)
        val to = hw.placeHex[n.place]
        people[h.i] -= n.size; people[to.i] += n.size
        bandsIn.getOrPut(to.i) { ArrayList() } += n
        made += n
        stats.meeting(Met.BANDS_SPLIT_BY_DISCORD)
        return true
    }
    if (adults >= SMALL_BAND) return ownCamp(adults)
    // (Too few to live alone: the camp nearby with most room for them.)
    var into: HexGroup? = null; var room = adults + young - 1
    for (d in -1 until 6) {
        val q = if (d < 0) h else hex.neighbour(h, d)
        if (q.none || wanting[q.i]) continue
        for (o in bandsIn[q.i].orEmpty()) {
            val ob = o.band ?: continue
            if (o === g || !o.alive || ob.mind != b.mind || inParty(o) || !staysHome(o)) continue
            val r = easySize(o) - o.size
            if (r > room) { room = r; into = o }
        }
    }
    val o = into ?: return if (g.adults >= 2 * SMALL_BAND) ownCamp(SMALL_BAND) else false
    g.adults -= adults; g.young -= young
    val n = splitOff(g, adults, young)
    people[h.i] -= n.size; people[hw.placeHex[o.place].i] += n.size
    joinBand(o, n, stepOf(date))
    stats.meeting(Met.HOUSEHOLDS_LEFT_IN_DISCORD)
    return true
}

/** Whether band [g] keeps off hex [h]: it was driven from it, and still fears those who drove it off ([Band.keptFrom]). */
internal fun shuns(g: HexGroup, h: Hex) = g.band?.keptFrom(h) == true

/**
 * The share of its households a band driven from its home ([driveOff]) sends to kin elsewhere ([scatterBeaten]). An estimate:
 * the beaten scatter, and many take refuge with kin and allies rather than stay with the band (Chagnon on the Yanomamo,
 * Meggitt 1977 on the Mae Enga, from memory: to check).
 */
internal const val DEFEAT_SCATTER = 0.3f

/**
 * The bands driven from their homes in this step's raids ([HexGroups.beaten]), when bands hold their home hex ([staysHome])
 * (docs/design/vacating-a-hex.md, Olli 2026-11-11). Before, a band beaten at home lost its houses but stayed in the hex,
 * beside those who beat it. Now, for each one still alive:
 * - [DEFEAT_SCATTER] of its households, in parties of [SEEP_ADULTS] grown people with their share of the young, go to the
 *   nearest kin band within [VISIT_RINGS] hexes that is not of the raiders' line, and join it ([mergeAndPair]);
 * - the rest moves its camp to the empty hex beside that lies farthest from the raiders' home (the one whose ground gives
 *   most, of those as far), never back to one it shuns ([shuns]);
 * - with no empty hex beside, or too few left to live alone ([SMALL_BAND]), the whole band joins that kin band if it has
 *   room; failing all, it stays.
 * The hex it left is kept off by its line while the feud lasts ([Band.keptFrom]); the raiders take it up only as their own
 * buds and households would any empty hex ([findHome], [spreadYear]). Returns the parties made, to be added to the groups.
 */
@Part("people: the beaten scatter")
internal fun HexGroups.scatterBeaten(date: Date): List<HexGroup> {
    val made = ArrayList<HexGroup>()
    if (beaten.isEmpty()) return made
    val list = beaten.toList(); beaten.clear()
    for ((o, byHex) in list) {
        val ob = o.band ?: continue
        if (!o.alive || !staysHome(o) || inParty(o)) continue
        val people = bandPeopleByHex(made)
        val h = hw.placeHex[o.place]
        // (The kin band to take refuge with: the nearest of most kinship, not of the raiders' line, with room for them.)
        val raiders = ob.drivenBy
        var host: HexGroup? = null; var hostKin = -1f
        var ring = listOf(h); val seen = hashSetOf(h)
        for (r in 0..VISIT_RINGS) {
            for (q in ring) for (k in groups) {
                if (k === o || !k.alive || hw.placeHex[k.place] != q || inParty(k)) continue
                val kb = k.band ?: continue
                if (kb.mainLineage() == raiders || kb.mind != ob.mind || k.size + SEEP_ADULTS > maxSize(k)) continue
                val kin = kinship(ob.lineage, kb.lineage) / (1f + r)
                if (kin > hostKin) { hostKin = kin; host = k }
            }
            if (host != null || r == VISIT_RINGS) break
            ring = hex.nextRing(ring, seen)
        }
        // (The hex to move to: empty, beside, farthest from the raiders' home; of those as far, the one whose ground gives most.)
        var to = Hex.NONE; var toKm = -1.0; var toFood = 0f
        for (d in 0 until 6) {
            val q = hex.neighbour(h, d)
            if (q.none || hw.placesOf(q).isEmpty() || people[q.i] > 0f || shuns(o, q)) continue
            val km = hexKm(q, byHex); val f = hexFood(q)
            if (km > toKm + 1e-6 || (km > toKm - 1e-6 && f > toFood)) { to = q; toKm = km; toFood = f }
        }
        val parties = if (host == null) 0 else (o.adults * DEFEAT_SCATTER / SEEP_ADULTS).toInt()
        if (to.none || o.adults - parties * SEEP_ADULTS < SMALL_BAND) {
            val k = host
            if (k != null && k.size + o.size <= maxSize(k)) {
                joinBand(k, o, stepOf(date))
                stats.meeting(Met.BEATEN_BANDS_JOINED_KIN)
                note(o) { "driven from its home, joined kin band #%d".format(k.id) }
            }
            continue
        }
        val k = host
        if (k != null) repeat(parties) {
            val young = o.young * SEEP_ADULTS / o.adults
            o.adults -= SEEP_ADULTS; o.young -= young
            val n = splitOff(o, SEEP_ADULTS, young)
            n.place = k.place
            made += n
            stats.meeting(Met.BEATEN_HOUSEHOLDS_TO_KIN)
        }
        o.place = hw.firstPlaceIn(to)
        stats.meeting(Met.BEATEN_BANDS_MOVED_AWAY)
        note(o) { "driven from its home, moved to hex %d".format(to.i) }
    }
    return made
}

/** A band that left a hex because it could not live through the season there keeps off it this many days ([leaveFailingGround]). */
internal const val FLED_KEEP_DAYS = 360L

/**
 * Days of need band [g] would go short of, from today until its food comes back, if each month gave it [yields] (share of
 * need a day of all hands' foraging brings: [Band.yieldNow], [usualYield]; [yields] (0) is this month's), starting after
 * [lostDays] spent on the way: each month short of 1 adds its shortfall for its days; the first month after this one that
 * gives 1 or more ends it (a year at most). A month no one knows counts as giving enough: a band does not fear what it
 * cannot foresee.
 */
private fun shortfallDays(yields: FloatArray, dayOfMonth: Int, lostDays: Float): Float {
    var short = lostDays
    for (k in yields.indices) {
        val y = yields[k]
        if (k > 0 && (y.isNaN() || y >= 1f)) break
        val days = if (k == 0) (Date.DAYS_PER_MONTH - dayOfMonth + 1).toFloat() else Date.DAYS_PER_MONTH.toFloat()
        if (!y.isNaN()) short += max(0f, 1f - y) * days
    }
    return short
}

/**
 * Bands that hold their home hex ([staysHome]) and whose stores will not last until the food comes back leave it for a hex
 * beside, when that gives them a better chance (Olli 2026-11-11: a band that has lived through many winters where it is stays
 * through a hard one; leaving in the snow may be death too; but at the point where staying is death, if leaving gives even
 * the slightest chance, people leave). Each step, in days, so at any step length:
 * - its stores: its people's fat and its larder, in days of need;
 * - staying: the days it would go short until its camp gives its need again, by its yield now ([Band.yieldNow]) and the
 *   lore of the months ahead at its camp ([usualYield]);
 * - it acts only when staying runs past its stores: then for each hex beside, open to it this week ([stepEffort]: snow,
 *   rivers, cliffs) and not one it shuns or fled within [FLED_KEEP_DAYS], the same months scaled by how much more food a
 *   head that hex gives ([hexFood] over its people and the band), plus the days the move takes ([moveDays] over the way's
 *   effort, eating from its stores and gathering nothing); it goes to the one that leaves it the most days to spare, if
 *   that is more than staying leaves.
 * A band tied to its spot (houses, fields, a palisade) leaves them. No network chooses this: it is the survival layer under
 * the provider (docs/design/vacating-a-hex.md). Doom trace starve710 (2026-11-11): of the bands that starved, every one
 * stayed at its first camp; a hex 1.25 times as rich lay a median 19 km off; their fat lasted 4-8 weeks past the month their
 * camp's yield fell under their need.
 */
@Part("people: leave failing ground")
internal fun HexGroups.leaveFailingGround(weather: Weather, date: Date) {
    if (!tuning.bandsStay) return
    var people: FloatArray? = null
    val today = date.absoluteDay
    val yields = FloatArray(Date.MONTHS_PER_YEAR)
    for (g in groups) {
        val b = g.band ?: continue
        if (!g.alive || !staysHome(g) || inParty(g) || g.dailyNeedKg <= 0f) continue
        val now = b.yieldNow
        if (now.isNaN() || now >= 1f) continue
        val stores = g.fat + g.larder / g.dailyNeedKg
        val month = date.month
        yields[0] = now
        for (k in 1 until yields.size) yields[k] = usualYield(g.place, (month + k) % Date.MONTHS_PER_YEAR)
        val stay = stores - shortfallDays(yields, date.dayOfMonth, 0f)
        if (stay >= 0f) continue
        val ppl = people ?: bandPeopleByHex().also { people = it }
        val h = hw.placeHex[g.place]
        val hereHead = hexFood(h) / max(1f, ppl[h.i])
        if (hereHead <= 0f) continue
        val effort = planner.stepEffort(Catalogue.travel(g.species).ordinal, hw.placeBand[g.place], weather)
        val fled = fledFrom[g.id]?.takeIf { today - it.second < FLED_KEEP_DAYS }?.first
        var best = Hex.NONE; var bestSpare = stay
        val scaled = FloatArray(yields.size)
        for (d in 0 until 6) {
            val q = hex.neighbour(h, d)
            if (q.none || hw.placesOf(q).isEmpty() || q == fled || shuns(g, q)) continue
            val km = effort[hex.edge(h, d)]
            if (!km.isFinite()) continue
            val gain = hexFood(q) / (ppl[q.i] + g.size) / hereHead
            if (gain <= 1f) continue
            for (k in yields.indices) scaled[k] = yields[k] * gain
            val spare = stores - shortfallDays(scaled, date.dayOfMonth, moveDays(g, km))
            if (spare > bestSpare) { bestSpare = spare; best = q }
        }
        if (best.none) { stats.meeting(Met.BANDS_FAILING_NOWHERE_BETTER); continue }
        if (b.livesFixed()) {
            if (!b.houseHex.none) leaveStructures(g, b.houseHex)
            b.houseHex = Hex.NONE; b.house = 0f; b.atHome = false; b.palisade = 0f
            b.homeEarth = 0f; b.homeWalls = 0f; b.homeGroundC = 0f
        }
        ppl[h.i] -= g.size; ppl[best.i] += g.size
        fledFrom[g.id] = h to today
        g.place = hw.firstPlaceIn(best)
        stats.meeting(Met.BANDS_LEFT_FAILING_GROUND)
        note(g) { "its stores would not last the season at hex %d (%.0f days short); left for hex %d (%.0f to spare)".format(h.i, -stay, best.i, bestSpare) }
    }
}
