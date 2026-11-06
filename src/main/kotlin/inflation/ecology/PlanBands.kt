package inflation.ecology

import inflation.ecology.GroupPlanner.Scratch
import inflation.world.Hex
import inflation.world.HexArray
import inflation.world.Place
import inflation.world.get
import inflation.world.set
import inflation.world.Date
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

// Choosing: what draws a band beyond what any group weighs, its will to explore, the planning mind's reckoned move, its crop, herds and fish.

/**
 * Where band [g] goes by its will to explore ([exploreChoice]), of the places weighed in [buf]: scored by its [policy]'s
 * scores (the network's), or without one by the hand-written; [best] where the scores alone would send it.
 */
internal fun GroupPlanner.explored(g: HexGroup, policy: HexPolicy?, buf: Scratch, best: Place, date: Date): Place {
    val n = buf.explPlaces.size
    val b = g.band ?: return best
    if (n == 0) return best
    val scores = if (policy == null) buf.explScores else policy.scores(g, Array(n) { buf.candFeats[it] }, buf.candScores.copyOf(n)) ?: return best
    val stays = (0 until n).firstOrNull { buf.explPlaces[it] == g.place.i } ?: -1
    val k = if (b.mind.gate == MoveGate.BEST) exploreBest(n, scores, buf.explDeaths)
        else gs.exploreChoice(g, b.explore, n, scores, buf.explDeaths, stays, if (policy == null) HAND_SCORE_UNIT else 1f, date.absoluteDay)
    return if (k < 0) best else Place(buf.explPlaces[k])
}

/**
 * Where planning band [g] goes ([plannedMove]), of the places weighed in [buf], scored as in [explored] (asked only once it is
 * leaving); [owed]: its customs send it this week (an appointment due, a raid set, a camp left after a death); else the
 * reckoned move ([reckonedGain], [reckonedWalk]) from its camp in [here]; [best] if none can be weighed.
 */
internal fun GroupPlanner.planned(g: HexGroup, policy: HexPolicy?, buf: Scratch, best: Place, owed: Boolean, here: Hex, date: Date): Place {
    val n = buf.explPlaces.size
    if (n == 0) return best
    val b = g.band ?: return best
    val stays = (0 until n).firstOrNull { buf.explPlaces[it] == g.place.i } ?: -1
    // (planTrace: why it went or stayed, and the parts of its case, [bookReckoned].)
    // (The viewer's Plan tab, for the followed band: the same, and every place's scores and walk, [MoveLook].)
    val looked = gs.looked(g)
    val terms = if ((PLAN_TRACE.on || looked) && !owed) FloatArray(ReckonTerm.entries.size) { Float.NaN } else null
    val why = if (PLAN_TRACE.on || looked) arrayOfNulls<Reckoned>(1) else null
    val gain = if (owed) Float.NaN else reckonedGain(g, b, here, date, terms)
    var asked: FloatArray? = null
    val k = plannedMove(n, buf.explScores, buf.explDeaths, stays, owed, gain,
        { gs.reckonedWalk(g, b, buf.dist[gs.hw.placeHex[buf.explPlaces[it]]]) }, {
        (if (policy == null) buf.explScores else policy.scores(g, Array(n) { buf.candFeats[it] }, buf.candScores.copyOf(n))).also { asked = it } }, why)
    if (PLAN_TRACE.on) why?.get(0)?.let { bookReckoned(it, terms) }
    if (looked) gs.moveLook = MoveLook(g.id, date, owed, IntArray(n) { buf.explPlaces[it] }, FloatArray(n) { buf.explScores[it] },
        FloatArray(n) { buf.explDeaths[it] }, asked?.copyOf(n),
        FloatArray(n) { gs.reckonedWalk(g, b, buf.dist[gs.hw.placeHex[buf.explPlaces[it]]]) },
        FloatArray(n) { buf.dist[gs.hw.placeHex[buf.explPlaces[it]]] }, stays,
        terms ?: FloatArray(ReckonTerm.entries.size) { Float.NaN }, gain, b.plannedStay(), why?.get(0), k)
    // (Moving, it reckons how many loads to take, the days of relays against the stores it would leave: [relayLoads].)
    if (k >= 0 && k != stays) b.planLoads = gs.relayLoads(g, b, buf.dist[gs.hw.placeHex[buf.explPlaces[k]]])
    return if (k < 0) best else Place(buf.explPlaces[k])
}

/**
 * The case for planning band [b] (group [g]) to leave its camp in [here] this week, before the walk ([reckonedWalk]),
 * in shares of a week (doc: Moving and settling under the planner, step 2): the share of its hands' week this camp has
 * lately taken to feed it ([campWork]) beyond what a fresh camp usually takes at this time of year ([usualWork]), a day of
 * work weighed as a day of need, as the walk's days are; less what leaving would give up, each paid for over the weeks it
 * keeps a camp ([Band.plannedStay]) as the walk is:
 * - its houses, left to weather and to others ([Band.leavingHouse]);
 * - what it has sown and not yet reaped ([cropPull]);
 * - its heavy tools, left behind (grinding stones, [Tool.heavy]), the days of making them again;
 * - its stores beyond what it carries, with the relays it would choose, are weighed with the walk ([reckonedWalk]).
 * (A weir it may use here is not taken off again: its fish are in this camp's yield already, so in [campWork]. Weighing
 * a whole weir's week of food against the case as well counted it twice, and in food against work; band 108139 trace,
 * 2026-11-03.)
 * NaN if it cannot judge: no whole week at this camp yet, or no such weeks in the years before.
 */
private fun GroupPlanner.reckonedGain(g: HexGroup, b: Band, here: Hex, date: Date, terms: FloatArray? = null): Float {
    val case = b.campWork(date, gs.days) - b.usualWork(date, gs.days)
    if (case.isNaN()) return Float.NaN
    val stay = b.plannedStay()
    var tools = 0f
    for (t in Tool.entries) if (t.heavy && b.kit[t] > 0f) tools += b.kit[t] * t.makeDays / b.makeSkill(t).coerceAtLeast(1e-3f)
    val house = if (b.houseHex == here) b.leavingHouse else 0f
    val crop = cropPull(g, b, here)
    val heavy = tools / (gs.days * stay)
    if (terms != null) {
        terms[ReckonTerm.CASE.ordinal] = case; terms[ReckonTerm.HOUSE.ordinal] = house; terms[ReckonTerm.CROP.ordinal] = crop
        terms[ReckonTerm.TOOLS.ordinal] = heavy
        terms[ReckonTerm.CAMP.ordinal] = b.campWork(date, gs.days); terms[ReckonTerm.USUAL.ordinal] = b.usualWork(date, gs.days)
    }
    return case - house - crop - heavy
}

/** The parts of a planning band's reckoned case for leaving ([reckonedGain]), as planTrace sums them, in shares of a week. */
enum class ReckonTerm(val label: String) {
    CASE("case"), CAMP("camp's work"), USUAL("usual work"), HOUSE("houses"), CROP("crop"), TOOLS("heavy tools")
}

/** planTrace: books why a planning band went or stayed this week ([Reckoned]) and, where it judged a case, its parts ([GroupPlanner.reckonCounts]). */
private fun GroupPlanner.bookReckoned(why: Reckoned, terms: FloatArray?) {
    reckonCounts.incrementAndGet(why.ordinal)
    if (terms == null || terms[ReckonTerm.CASE.ordinal].isNaN()) return
    reckonCounts.incrementAndGet(RECKON_JUDGED)
    for (t in ReckonTerm.entries) reckonCounts.addAndGet(RECKON_SUMS + t.ordinal, (1e4f * terms[t.ordinal]).roundToLong())
}

/** Where [GroupPlanner.reckonCounts] keeps the weeks a case was judged, and the parts' sums (1/10000 of a week). */
internal val RECKON_JUDGED = Reckoned.entries.size
internal val RECKON_SUMS = RECKON_JUDGED + 1

/**
 * The fish and shellfish band [b] could take from hex [h] in a week, with its skill and its weirs ([catchWithin]; less
 * plain where it is a stranger), as the game standing on the ground that would yield as much to its hunters: game
 * is met over the ground its party sweeps in a week ([sweptKm2]) and caught at [CATCH].
 */
internal fun GroupPlanner.fishWorth(g: HexGroup, b: Band, h: Hex): Float =
    catchWithin(g, b, h) * (if (b.knows(h)) 1f else 0.5f) / (gs.sweptKm2(g.species) * CATCH)

/** The fish and shellfish band [b] (group [g]) could take from hex [h] in a week ([HexWater.bandCatchWithin]), and from a weir standing there that it may use ([HexWater.weirWithin]). */
internal fun GroupPlanner.catchWithin(g: HexGroup, b: Band, h: Hex): Float =
    gs.fauna.water.bandCatchWithin(h, b.fishing()) + gs.fauna.water.weirWithin(h, gs.weirAt(g, h))

/**
 * What draws band [b] (group [g]) to place [p] in hex [h] or puts it off, beyond what any group weighs: its
 * appointment, its dead, its houses, raids and the homes of others, gatherings, and country it does not know.
 * (Kept apart from [planInner] so that the planner stays small enough for the JIT to compile.)
 */
internal fun GroupPlanner.bandPull(g: HexGroup, b: Band, month: Int, h: Hex, here: Hex, p: Place, keepOff: HexArray?, sensed: HexArray, reach: Float): Float {
    var score = 0f
    // Its appointment draws a band in its month (to the month with sky lore; within a month
    // either way, more weakly, without); its ancestors' places draw it a little always.
    val pull = gs.appointPull(g, b, month, h)
    if (pull != 0f) score += APPOINT_PULL * pull
    val dead = g.memory.deadAt(h)
    if (dead > 0f) score += ANCESTOR_PULL * min(1f, dead / 10f)
    // Houses it may use draw it (its own or any left empty); a camp where someone died lately is shunned by those who leave their dead.
    score += HOME_PULL * gs.housesAt(g, h)
    score += cropPull(g, b, h)
    // A planning band at its houses would leave them to weather and to others ([Band.leavingHouse]).
    if (b.leavingHouse > 0f && h != b.houseHex) score -= b.leavingHouse
    score -= herdLoss(g, b, p)
    if (h == b.unluckyHex) score -= UNLUCKY
    // It keeps off the known homes of bands it is not close to (unless set on raiding them).
    if (b.raidHex != h && keepOff != null && keepOff.binarySearch(h) >= 0) score -= TRESPASS_AVOID
    // A band set on a raid heads for its target.
    score += RAID_PULL * raidProgress(b, here, h)
    // A band short of sodium heads for the nearest salt it knows of, as a hungry animal seeks a lick. What it gets there
    // lasts it years (it keeps [KEEP_YEARS] of [YEAR_NEED]): a year of weeks without the share of its food that salt lack
    // spoils ([saltFeeds]), a year being as far ahead as a band foresees ([foreseenSurvival]), paid for by the weeks the
    // walk there takes, at its reach a week.
    // (Not where it can get salt already: on the shore or a salt pan, as [saltFeedsAt] has it.)
    val saltWeeks = saltWeeks(g, b, here, reach)
    if (saltWeeks > 0f) score += (1f - saltFeeds(b.sodiumShort, 1f, gs.tuning.saltLack)) * gs.stepsPerYear / saltWeeks * saltProgress(b, here, h, reach)
    // A band with food enough to feast others is drawn to where other bands are (gatherings).
    if (gs.peopleSlot >= 0 && b.holds(Tradition.FEASTS))
        gs.dialUse?.choice(DialSite.STORE_FEAST, b.storeShare, g.larder > b.storeAim * g.dailyNeedKg, g.larder > b.storeAimAt1 * g.dailyNeedKg)
    score += GATHER_PULL * feastDraw(g, b, p, h, sensed)
    // Bands keep to country they know; what lies beyond they know only as a sense of better land.
    // Strange country puts a band off, unless it is out to explore (then it draws it on).
    if (!b.knows(h)) score -= UNKNOWN_COUNTRY * (2f - b.explore)
    if (!b.knows(h)) gs.dialUse?.amount(DialSite.EXPLORE_SCORE, b.explore, UNKNOWN_COUNTRY * (2f - b.explore), UNKNOWN_COUNTRY)
    return score
}

/**
 * What the crop band [b] (group [g]) has sown and not yet reaped draws it to the hex it stands in: leaving it, the band
 * would lose its harvest (others reap a field no one keeps), a loss paid for over the weeks it keeps a camp ([Band.stayWeeks];
 * a planning band, [Band.plannedStay]),
 * as a move's lost days are. The harvest is its share of the field as the sim would ripen it now ([HexFood.fieldExpect]),
 * in weeks of its food.
 */
private fun GroupPlanner.cropPull(g: HexGroup, b: Band, h: Hex): Float = cropHarvestWeeks(g, b, h) / (if (b.mind.gate == MoveGate.RECKONED) b.plannedStay() else b.stayWeeks)

/** The harvest band [b] (group [g]) has sown and not yet reaped in hex [h], in weeks of its food (0 elsewhere): [cropPull] before it is spread over the weeks it keeps a camp. */
internal fun GroupPlanner.cropHarvestWeeks(g: HexGroup, b: Band, h: Hex): Float {
    val f = b.sownPlace
    if (f.none || gs.hw.placeHex[f.i] != h) return 0f
    val food = gs.fauna.food
    val kg = min(b.sownKg, food.field[f] * gs.land.m2(f))
    if (kg <= 0f) return 0f
    return kg * food.fieldExpect(f) * seedValue(b) / (g.dailyNeedKg.coerceAtLeast(1e-3f) * gs.days)
}

/** How far going to hex [h] brings band [b] toward the hex it is set on raiding, from [here], -1 to 1 (0 if it is set on none). */
internal fun GroupPlanner.raidProgress(b: Band, here: Hex, h: Hex): Float {
    if (b.raidHex.none) return 0f
    val from = gs.hexKm(here, b.raidHex)
    return if (from > 0.0) ((from - gs.hexKm(h, b.raidHex)) / from).toFloat().coerceIn(-1f, 1f) else 0f
}

/** The weeks band [b] (group [g]) would walk from [here] to the salt it knows of, at its [reach] a week, if it is short of sodium and cannot get salt where it is and the salt is a hex off or more; else 0. */
internal fun GroupPlanner.saltWeeks(g: HexGroup, b: Band, here: Hex, reach: Float): Float {
    if (b.sodiumShort <= 0f || gs.hw.coast[here] || gs.land.salt[g.place] > 0f) return 0f
    val salt = gs.bands.saltKnown(b, here)
    val from = if (!salt.none) gs.hexKm(here, salt) else 0.0
    return if (from > gs.hex.hexKm) max(1.0, from / reach).toFloat() else 0f
}

/** How far going to hex [h] brings band [b] toward the salt it knows of ([saltWeeks] is above 0), from [here], -1 to 1. */
internal fun GroupPlanner.saltProgress(b: Band, here: Hex, h: Hex, reach: Float): Float {
    val salt = gs.bands.saltKnown(b, here)
    val from = gs.hexKm(here, salt)
    return ((from - gs.hexKm(h, salt)) / min(from, reach.toDouble())).toFloat().coerceIn(-1f, 1f)
}

/** How many of the bands about a feasting band [b] (group [g]) would draw it to place [p] (in hex [h]), against [GATHER_HEADS], 0 to 1; 0 for a band that does not feast or has not the food to. */
internal fun GroupPlanner.feastDraw(g: HexGroup, b: Band, p: Place, h: Hex, sensed: HexArray): Float {
    if (gs.peopleSlot < 0 || !b.holds(Tradition.FEASTS)) return 0f
    if (g.larder <= b.storeAim * g.dailyNeedKg) return 0f
    val others = knownHeads(gs.peopleSlot, p, h, sensed) - (if (p == g.place) g.size else 0)
    return if (others > 0f) min(1f, others / GATHER_HEADS) else 0f
}

/**
 * What a camp at [p] would cost band [b]'s (group [g]'s) kept animals a week, in weeks of its food: as far as they would go
 * short of forage there ([herdForage], the reckoning that feeds them), those that would die of it and the young that
 * would not be born ([keepHerds]' rule, its birth season taken over the year), at their meat.
 */
internal fun GroupPlanner.herdLoss(g: HexGroup, b: Band, p: Place): Float {
    var kg = 0f
    for (k in b.herds.herd.indices) {
        val n = b.herds.herd[k] + 0.5f * b.herds.herdYoung.getOrElse(k) { 0f }
        if (n <= 0f) continue
        val s = gs.fauna.kinds[k]
        val short = 1f - gs.herdForage(p, s, n).fed
        if (short <= 0f) continue
        kg += n * short * (0.5f + s.youngPerFemale * herdMothers(s, b.herds.herdTame[k], b.herds.sows()[k])) * gs.years * s.massKg
    }
    return kg * MEAT_SHARE * Nourishment.MEAT * g.species.needScale / (g.dailyNeedKg.coerceAtLeast(1e-3f) * gs.days)
}
