package inflation.ecology

import inflation.ecology.HexFauna.Carry
import inflation.ecology.HexFauna.Companion.BAND_SHIFT
import inflation.ecology.HexFauna.Companion.BEARING_SPILL
import inflation.ecology.HexFauna.Companion.EXTINCT
import inflation.ecology.HexFauna.Companion.FEAR_WEIGHT
import inflation.ecology.HexFauna.Companion.FIRE_ADULT
import inflation.ecology.HexFauna.Companion.FLOW_STEP
import inflation.ecology.HexFauna.Companion.scratch
import inflation.ecology.HexFauna.Companion.SEA_SPILL
import inflation.ecology.HexFauna.Companion.SPARE_CROWD
import inflation.ecology.HexFauna.Companion.SPILL_BASE
import inflation.ecology.HexFauna.Companion.SPILL_BETTER
import inflation.ecology.HexFauna.Companion.SPILL_HUNGER
import inflation.ecology.HexFauna.Companion.SPILL_MISFIT
import inflation.ecology.HexFauna.Companion.STUCK_FED
import inflation.ecology.HexFauna.Companion.YOUNG_WEIGHT
import inflation.ecology.HexFauna.FlowWays
import inflation.ecology.HexFauna.WeekFixed
import inflation.Par
import inflation.Part
import inflation.EnumFloats
import inflation.Profile
import inflation.world.Hex
import inflation.world.Place
import inflation.world.get
import inflation.world.set
import inflation.Tuning
import inflation.Books
import inflation.min
import inflation.world.Date
import inflation.world.HexWorld
import inflation.world.Weather
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// The residents' moving: what a place is worth to each kind, the shift between low and high ground, the plant-eaters' flow and the hunters' spill to neighbouring hexes.

/**
 * How well [heads] of kind [s] coming to place [p] together would be fed there, 0..1 of their need: wanting [each] a
 * head ([plantWant]), getting at [reach] of the food ([reachOf]) under [snowMm] of snow, eating by the same rule as
 * those there ([forage]) from the food they left uneaten ([spareGrass] and the rest), so poor food feeds them no
 * better than it feeds those already there.
 */
internal fun HexFauna.fedOnSpare(s: Species, p: Place, heads: Float, each: Float, reach: Float, snowMm: Float, dry: DryPantry, b: Bites): Float {
    val want = heads / land.areaKm2[p] * each
    if (!(want > 0f)) return 1f
    dry.reach = reach; dry.grass = spareGrass[p]; dry.browse = spareBrowse[p]
    dry.fruit = spareFruit[p]; dry.roots = spareRoots[p]
    return (forage(s, s.diet, land.plants.nourishment(p), want, grassUnderSnow(s, snowMm), dry, b).got / want).coerceIn(0f, 1f)
}

/** [fedOnSpare] for [heads] of plant-eating kind [k] coming to place [p] this week. */
internal fun HexFauna.roomFor(k: FaunaKind, p: Place, heads: Float, weather: Weather, dry: DryPantry, b: Bites): Float =
    fedOnSpare(kinds[k], p, heads, plantWant(k, p, weather), reachOf(kinds[k], p), weather.snowpack[p], dry, b)

/**
 * How well a band of plant-eating kind [k] ([Species.maxGroupSize]) coming to place [p] would be fed there this week, 0..1 of
 * its need, from the food left uneaten after every kind has eaten ([roomFor]); how well those of a kind that eats meat were fed
 * ([fed]). What [arriveOdds] and [growthParts] weigh a place by unless told how well fed.
 */
internal fun HexFauna.fedAt(k: FaunaKind, p: Place, weather: Weather): Float =
    if (kinds[k].carnivore) fed[k][p] else roomFor(k, p, kinds[k].maxGroupSize.toFloat(), weather, DryPantry(), Bites())

/**
 * Per km², the heads a band of each kind ([Species.maxGroupSize]) coming to a place adds to those there: spread over
 * the ground it ranges in a day, a circle half its day's going across (out and back).
 */
private fun HexFauna.arrivingKm2(k: FaunaKind): Float {
    val s = kinds[k]; val r = 0.5f * s.dailyTravelKm
    return s.maxGroupSize / (Math.PI.toFloat() * r * r)
}

/**
 * What the grown of kind [k] carry with them wherever they move ([flow], [spillHunters], [settle]), a grown head's at
 * each place, by [Carry]: their season of births had ([bred]), their fat ([reserve]), their body burnt ([spent]) and
 * their boldness of people ([bold]).
 * A number that should go with them is added here and in [bound], and moves with them by every way they move.
 */
internal fun HexFauna.carried(k: FaunaKind) = Array(Carry.entries.size) { c ->
    when (Carry.entries[c]) { Carry.BRED -> bred[k]; Carry.FAT -> reserve[k]; Carry.SPENT -> spent[k]; Carry.BOLD -> bold[k] }
}

/** [v], a grown head's carried number [c] of kind [k] ([carried]), in its bounds: a share, fat up to the most the kind carries, body burnt from none. */
internal fun HexFauna.bound(c: Int, k: FaunaKind, v: Float) = when (Carry.entries[c]) {
    Carry.BRED -> v.coerceIn(0f, 1f); Carry.FAT -> v.coerceIn(0f, kinds[k].maxFatDays); Carry.SPENT -> max(0f, v); Carry.BOLD -> v.coerceIn(0f, 1f)
}

/**
 * In the weeks the residents move: for the hunters, what each place is worth to them ([worth]) and, on the split hexes
 * where they shift between low and high ground ([shiftBands]), [arrive]; for the plant-eaters (who weigh places as they
 * flow, [flow]), their young to a grown one and fat ([moverYoung], [moverFat]).
 */
@Part("residents: weighing places")
internal fun HexFauna.fillArrive(weather: Weather) {
    for (k in kindIds) {
        val s = kinds[k]
        if (kinds[k].carnivore) {
            val odds = arrive[k]
            Par.forEachPlace("residents: fill arrive", n) { p ->
                if (splitPlace[p]) odds[p] = arriveOdds(k, p, weather, scratch.get().outs); worth[k][p] = worthOrNothing(appeal(k, p, weather)) }
            continue
        }
        // (Those that move are the kind as it is: its young to its grown, and the fat its grown carry, all over.)
        val a = adults[k]; val y = young[k]; val fat = reserve[k]
        var grown = 0.0; var little = 0.0; var fatDays = 0.0; var spentDays = 0.0
        for (p in land.places) { val area = land.areaKm2[p]; grown += a[p] * area; little += y[p] * area; fatDays += fat[p] * a[p] * area
            spentDays += spent[k][p] * a[p] * area }
        val youngPerGrown = if (grown > 0.0) (little / grown).toFloat() else 0f
        val carried = if (grown > 0.0) (fatDays / grown).toFloat() else 0.5f * s.maxFatDays
        moverYoung[k] = youngPerGrown; moverFat[k] = carried; moverSpent[k] = if (grown > 0.0) (spentDays / grown).toFloat() else 0f
    }
}

/**
 * What [placeWorth] makes of [growthOf]'s week in [parts] for kind [k] ([youngPerGrown] young to a grown one): the
 * rate those coming there would grow at over a year as they would fare there this week. The week's deaths are taken
 * over the year's weeks; its births over the weeks of the birth season only, since they are born in that season and
 * not all year (else a week of it would count as a year of births and outweigh any death). The births are those a
 * mother would bear there by its food in a week of her season, whatever the month and the place's own calendar: she
 * bears a year's young once ([bred]), so a place in its season gives her none she would not bear elsewhere. (Over a
 * year, not the rest of the month: over a few weeks the places about differ too little for the residents to leave
 * the bad ones, and the dry country's walkers die where they stand.)
 */
private fun HexFauna.growthFor(k: FaunaKind, parts: EnumFloats<Growth>, youngPerGrown: Float): Float {
    val head = 1f + youngPerGrown
    val stepsInSeason = kinds[k].birthMonths * Date.DAYS_PER_MONTH / days.toFloat()
    val dying = ((parts[Growth.GROWN_DIE] + youngPerGrown * parts[Growth.YOUNG_DIE]) / head).coerceIn(0f, 1f)
    // (Survival over the year as the week's, compounded: ln(1 - dying) in double, near enough to log1p for these odds and far cheaper.)
    return (1f + stepsInSeason * parts[Growth.BORN] / head) * exp(Date.DAYS_PER_YEAR / days.toFloat() * ln(1.0 - dying)).toFloat()
}

/**
 * [growthFor]'s week: the change in heads a head of kind [k] coming to place [p] would see there in a week, with the
 * [hazards] there in [out]. Where [parts] is given, it takes the young born to a grown one ([Growth.BORN]), the shares
 * of the grown and the young that die ([Growth.GROWN_DIE], [Growth.YOUNG_DIE]), and how well fed and how well met
 * their need for protein would be ([Growth.FED], [Growth.PROTEIN]). Fed [f], among [heads] of its kind per km² (by
 * default, those there and a band come).
 */
@IgnorableReturnValue internal fun HexFauna.growthParts(k: FaunaKind, p: Place, weather: Weather, out: EnumFloats<Danger>, youngPerGrown: Float, carried: Float, parts: EnumFloats<Growth>?,
                         f: Float = fedAt(k, p, weather), heads: Float = adults[k][p] + young[k][p] + arrivingKm2(k)): Float {
    val s = kinds[k]
    val protein = forageProtein(s, p)
    return growthOf(k, p, grownFixed(s, out), youngFixed(s, out, eagleOdds(s, p), crocodileOdds(p, weather), starvedOdds(protein)),
        huntedOdds(k, p, true), protein, youngPerGrown, carried, f, heads, parts)
}

/**
 * [growthParts] from what does not change at a place within a week ([grownFixed], [youngFixed], the hunters' take of
 * the young [huntedYoung], and the forage's [protein]) and what does as animals come and go: fed [f], among [heads]
 * of its kind per km², hunger and the sickness of crowding, and the young its mothers would bear.
 */
@IgnorableReturnValue private fun HexFauna.growthOf(k: FaunaKind, p: Place, grownFixed: Float, youngFixed: Float, huntedYoung: Float, protein: Float, youngPerGrown: Float,
                     carried: Float, f: Float, heads: Float, parts: EnumFloats<Growth>?): Float {
    val s = kinds[k]
    val hunger = Hazards.hunger(s, moverSpent[k], Hazards.onFat(f, carried, days, 1f), days); val sick = Hazards.crowdDisease(Hazards.crowding(heads, usual[k]), f, years)
    val due = dueYoung(k, p, heads, protein, days / (s.birthMonths * Date.DAYS_PER_MONTH.toFloat()))
    val born = 0.5f * due * breeding(s, due, carried, f, days)
    val grownDie = (grownFixed + hunger + sick).coerceIn(0f, Hazards.MAX_GROWN_ODDS)
    val youngDie = min(Hazards.MAX_YOUNG_ODDS, (youngFixed + 1.5f * hunger + sick).coerceIn(0f, Hazards.MAX_YOUNG_ODDS) + huntedYoung)
    if (parts != null) { parts[Growth.BORN] = born; parts[Growth.GROWN_DIE] = grownDie; parts[Growth.YOUNG_DIE] = youngDie; parts[Growth.FED] = f; parts[Growth.PROTEIN] = protein }
    return (born - grownDie - youngPerGrown * youngDie) / (1f + youngPerGrown)
}

/**
 * The share of grown animals of kind [k] coming to place [p] that would die there in a week ([hazards]): fed as far
 * as the food left uneaten there ([fedAt]) feeds them (a hunter, as well as the residents there were fed), with water
 * to drink; NaN where there is none, a place no one can stay at. Crowded by those there and a band of the kind come
 * to them ([arrivingKm2]): over the ground a band ranges in a day, the same wherever it goes. (Counted on the place's
 * own ground, a band crowded the smaller places, the high ground's above all, past any that live there; not counted
 * at all, all that move see a place as it is and crowd into the same ones together.)
 */
internal fun HexFauna.arriveOdds(k: FaunaKind, p: Place, weather: Weather, out: EnumFloats<Danger>, f: Float = fedAt(k, p, weather)): Float {
    val s = kinds[k]
    if (!drinks(s, p)) return Float.NaN
    // (They come with the fat and the burnt body of their kind as it moves, [moverFat] and [moverSpent].)
    hazards(k, p, weather, weather.date.month, Hazards.onFat(f, moverFat[k], days, 1f), 0f, adults[k][p] + young[k][p] + keptKm2(k, p) + arrivingKm2(k), out, moverSpent[k])
    return adultShare(out)
}

/**
 * Each place kind [k]'s residents at place [p] can walk to in a step: across each border it can cross, the place of
 * the hex beyond nearest in height to its own. (A hex's low and high ground are the foot and top of one slope: going
 * from one hex to the next keeps to the height walked at, and height is gained or lost within a hex.)
 */
internal inline fun HexFauna.neighbourPlaces(k: FaunaKind, p: Place, f: (Int) -> Unit) {
    val t = stepPlaces[travel[k].ordinal]
    val from = t.from; val to = t.to
    for (i in from[p] until from[p.i + 1]) f(to[i])
}

/**
 * How good a place is for resident hunter [k] this week (the plant-eaters weigh places by [growthFor]): food per head,
 * its ground, water within reach, the weather against its comfort, deep snow, fear, and the share that would live
 * through the week's fire and plague there.
 */
internal fun HexFauna.appeal(k: FaunaKind, p: Place, weather: Weather): Float {
    val s = kinds[k]
    val heads = adults[k][p] + young[k][p]
    // Weather now, and as far as the kind senses it, what is coming.
    val m = weather.date.month
    val discomfort = ((1f - s.foresight) * s.discomfortC(feltC(s, p, weather.temperature[p]), m) +
        s.foresight * s.discomfortC(feltC(s, p, weather.forecastTemp(p.i)), m)) / 10f
    // (A plant-eater weighs the snow as it feeds under it: its grass and roots by the share it gets at, see [grassUnderSnow].)
    val snowed = if (s.carnivore) { if (weather.snowpack[p] > s.snowLimitMm) 0.3f else 1f } else s.diet.let { d ->
        val plants = d.grass + d.browse + d.fruit + d.roots
        if (plants <= 0f) 1f else 1f - (d.grass + d.roots) * (1f - grassUnderSnow(s, weather.snowpack[p])) / plants }
    // (Plant-eaters weigh their forage by how fresh it is and how well it would meet their need for
    // protein: so they go up to the green high pasture in summer and down for the winter.)
    val fresh = if (s.carnivore) 1f else land.plants.nourishment(p) * forageProtein(s, p)
    return fareFor(s, p) * fresh * suits(s, p) * tether(s, p) * unbitten(p) * snowed * (1f - FEAR_WEIGHT * shy(s, p)) * (1f - outranked(s, p)) *
        (1f - land.fire.stepBurning[p] * FIRE_ADULT) * (1f - outbreakDeaths(s, p)) /
        (1f + Hazards.crowding(heads, usual[k])) / (1f + discomfort)
}

/** Whether [s] can drink at place [p] ([Hazards.canDrink]). */
internal fun HexFauna.drinks(s: Species, p: Place) = Hazards.canDrink(land.drink, s, p)

/** What a place is worth to a kind for moving there: nothing when below nothing or not a number (see [shiftBands], [spill]). */
internal fun HexFauna.worthOrNothing(w: Float) = if (w > 0f && w.isFinite()) w else 0f

/**
 * Within a split hex, a share of each resident hunter kind moves between its low and high ground (the plant-eaters
 * move between them as they [flow]): death first. Where staying on one is clearly deadlier than coming to the other
 * ([arrive], [clearlyWorse]), a share moves to the safer one, by how much safer it is; else towards the one of the
 * better hunting and ground ([appeal]), if it is no deadlier.
 */
@Part("residents: moving up and down the slopes")
internal fun HexFauna.shiftBands(weather: Weather) {
    // (Moving every few weeks, a move takes the share those weeks' would have.)
    val weeks = tuning.residentMoveWeeks
    // Each kind on its own core: the kinds' numbers are kept apart.
    Par.forEach("residents: shift bands", kindIds.toList()) { k ->
        if (!kinds[k].carnivore) return@forEach
        val t = Profile.mark()
        val a = adults[k]; val y = young[k]
        for (h in hex.all) {
            val lo = hw.placeIn(HexWorld.LOW, h); val hi = hw.placeIn(HexWorld.HIGH, h)
            if (lo.none || hi == lo) continue
            if (a[lo] + y[lo] <= 0f && a[hi] + y[hi] <= 0f) continue
            // (A place worth less than nothing, or past reckoning, counts as nothing: else the gain
            // passes 1, the share can take more than the place holds and leave it below nothing,
            // and spill's floor at nothing then makes animals out of nothing.)
            // (Where the kind cannot drink, it cannot stay: deadlier than anywhere it can.)
            val ol = arrive[k][lo].let { if (it.isNaN()) Float.POSITIVE_INFINITY else it }
            val oh = arrive[k][hi].let { if (it.isNaN()) Float.POSITIVE_INFINITY else it }
            if (ol.isInfinite() && oh.isInfinite()) continue
            val band = kinds[k].maxGroupSize.toFloat(); val background = kinds[k].backgroundMortality * years
            val from: Int; val to: Int; val gain: Float
            if (a[lo] + y[lo] > 0f && (oh.isFinite() && (ol.isInfinite() || clearlyWorse(ol, oh, band, background)))) {
                from = lo.i; to = hi.i; gain = if (ol.isInfinite()) 1f else (ol - oh) / ol
            } else if (a[hi] + y[hi] > 0f && (ol.isFinite() && (oh.isInfinite() || clearlyWorse(oh, ol, band, background)))) {
                from = hi.i; to = lo.i; gain = if (oh.isInfinite()) 1f else (oh - ol) / oh
            } else {
                val al = worth[k][lo]; val ah = worth[k][hi]
                if (al <= 0f && ah <= 0f) continue
                if (ah > al) { from = lo.i; to = hi.i } else { from = hi.i; to = lo.i }
                if (!(arrive[k][to] <= (if (to == hi.i) ol else oh))) continue
                gain = (max(al, ah) - min(al, ah)) / max(al, ah)
            }
            val share = (BAND_SHIFT * gain).let { if (weeks == 1) it else 1f - (1f - it).pow(weeks) }
            if (!(share > 0f)) continue
            val ma = a[from] * share * land.areaKm2[from]; val my = y[from] * share * land.areaKm2[from]
            a[from] -= ma / land.areaKm2[from]; y[from] -= my / land.areaKm2[from]
            settle(k, to, ma, my, from)
            // (Those that climbed pay for it out of their fat, as for a walk, see [climbKm].)
            val heads = (a[to] + y[to]) * land.areaKm2[to]
            if (heads > 0f) reserve[k][to] = max(0f, reserve[k][to] - WALK_COST * climbKm(k, Place(from), Place(to)) * (ma + my) / heads)
        }
        Profile.kindLap("residents: moving (shift)", kinds[k].name, t)
    }
}

/** The level km that going from place [p] to [q] up costs kind [k] as much as, by the height gained (none going down): see [climbKmPerM]. */
private fun HexFauna.climbKm(k: FaunaKind, p: Place, q: Place): Float = climbPerM[k] * max(0f, hw.placeElevationM[q] - hw.placeElevationM[p])

/** Adds [heads] going from hex [h] to hex [to] to the flow arrows ([flowEast], [flowSouth], [flowOut]). */
private fun HexFauna.recordFlow(h: Hex, to: Hex, heads: Float) {
    val (x0, y0) = hex.centreKm(h); val (x1, y1) = hex.centreKm(to)
    flowEast[h] += heads * (x1 - x0).toFloat(); flowSouth[h] += heads * (y1 - y0).toFloat(); flowOut[h] += heads
}

/**
 * The residents move to neighbouring hexes: the plant-eaters flow as a fluid finds its level ([flow]), the hunters
 * spill towards better hunting ([spillHunters]); then the thinly spread gather ([gatherThin]), a place left with too
 * few to count has none, and those that came pay for their walk out of their fat.
 */
@Part("residents: spreading to neighbouring hexes")
internal fun HexFauna.spill(weather: Weather) {
    // (The swimmers drowned this week, kind by kind: their weight as grown animals by place, and their heads.)
    val drowned = Array(kinds.size) { FloatArray(n) }; val drownedHeads = DoubleArray(kinds.size)
    Par.forEach("residents: spill", kindIds.toList()) { k ->
        val a = adults[k]; val y = young[k]
        // (The heads that came, and the km they walked to come: what the walk costs their fat.)
        val arrived = FloatArray(n); val walkedKm = FloatArray(n)
        val t = Profile.mark()
        drownedHeads[k] = if (!kinds[k].carnivore) flow(k, weather, arrived, walkedKm, drowned[k]) else spillHunters(k, weather, arrived, walkedKm, drowned[k])
        Profile.kindLap("residents: moving (spill)", kinds[k].name, t)
        gatherThin(k, arrived, walkedKm)
        // (A place left with too few to count, by those leaving or the few that came, has none: as after births and deaths,
        // they go to the soil with the drowned. Else they would feed and die there as fractions too small to reckon with.)
        for (p in land.places) if (a[p] + y[p] > 0f && a[p] + y[p] < EXTINCT) { drowned[k][p] += a[p] + y[p] * YOUNG_WEIGHT; a[p] = 0f; y[p] = 0f }
        // (Those that came walked here, at a group's cost a km out of their fat, see [walkCost].)
        for (p in land.places) if (arrived[p] > 0f) {
            val heads = (a[p] + y[p]) * land.areaKm2[p]
            if (heads > 0f) reserve[k][p] = max(0f, reserve[k][p] - WALK_COST * walkedKm[p] / heads)
        }
    }
    // (The drowned, counted kind by kind, go to the soil with the week's other carcasses.)
    var drownedKg = 0.0
    for ((k, s) in kinds.withKinds()) {
        for (p in land.places) { val d = drowned[k][p]; if (d <= 0f) continue; carcassKg[p] += d * s.massKg; drownedKg += d * s.massKg }
        if (drownedHeads[k] > 0.0) deaths.merge(Tally.of(s, DeathCause.DROWNED_CROSSING), drownedHeads[k], Double::plus)
    }
    land.books?.flow(Books.Stuff.MATTER, "carcasses (to the soil)", -drownedKg)
}

/**
 * Hungry or crowded resident hunters [k] spill into neighbouring hexes, at about the same height, towards better
 * hunting; the harder the border is for their way of travelling, the fewer cross it. Adds the heads that came to each
 * place to [arrived], the km they walked to [walkedKm], and the drowned to [drowned]; gives the heads drowned.
 */
private fun HexFauna.spillHunters(k: FaunaKind, weather: Weather, arrived: FloatArray, walkedKm: FloatArray, drowned: FloatArray): Double {
    val s = kinds[k]
    val a = adults[k]; val y = young[k]; val f = fed[k]
    val cross = hex.crossings[travel[k].ordinal]
    val moveA = FloatArray(n); val moveY = FloatArray(n)
    // (The grown that came, and they times what each carries ([carried]): it goes with them.)
    val carry = carried(k); val cameA = FloatArray(n); val came = Array(carry.size) { FloatArray(n) }
    // (Moving every few weeks, a move takes the share those weeks' would have.)
    val weeks = tuning.residentMoveWeeks
    // What each place is worth to the kind this week, as it is worth to those moving up and down the slopes.
    val worthOf = worth[k]
    val dk = dry[k]
    var weekHeads = 0.0; var weekStuck = 0.0; var weekEdge = 0.0; var drownedHeads = 0.0
    val flow = flowOf == k.i
    val linksTo = seaLinks?.value; val gapsTo = seaGaps?.value
    // (What does not change from place to place: whether the kind holds its ground as settled, how far it swims, and the weights of the ways from a place.)
    val settled = s in Catalogue.roamingResidents
    val swim = Catalogue.swimKm(s)
    val ws = FloatArray(6); val wq = IntArray(6)
    if (flow) { flowEast.fill(0f); flowSouth.fill(0f); flowOut.fill(0f) }
    for (p in land.places) {
        if (a[p] + y[p] <= 0f) continue
        // (Thirst drives them as hunger does: by how far their days without water have gone towards what they can bear.)
        val thirst = (dk[p] / s.daysWithoutWater).coerceIn(0f, 1f)
        // (And the cold or heat, and snow deeper than they can dig through, as they drive groups: see [restless].)
        val uneasy = (s.discomfortC(feltC(s, p, weather.temperature[p]), weather.date.month) / 10f).coerceIn(0f, 1f)
        val snowedIn = (weather.snowpack[p] / s.snowLimitMm - 1f).coerceIn(0f, 1f)
        val need = maxOf((1f - f[p]).coerceIn(0f, 1f), thirst, max(uneasy, snowedIn))
        val h = hw.placeHex[p]
        // Hungry, thirsty, cold or snowed-in ones leave, and so do those whose country does not suit them, towards better.
        // (The settled of a kind that also roams hold their ground: only want drives them off it; roaming to better country
        // is the roamers' part, [Catalogue.roamingResidents].)
        val leave = if (settled) SPILL_HUNGER * need else SPILL_BASE + SPILL_HUNGER * need + SPILL_MISFIT * bearings.strength(k.i, h)
        // Where they could go: in each neighbouring hex, the ground nearest in height to theirs (see [neighbourPlaces]),
        // weighed by how good it is and how easy the border is.
        var total = 0f; var best = 0f; var closed = false
        ws.fill(0f); wq.fill(-1)
        for (d in 0 until 6) {
            val nh = hex.neighbour(h, d)
            if (nh.none) { closed = true; continue }
            val e = cross[hex.edge(h, d)]
            if (!e.isFinite()) { closed = true; continue }
            val ease = hex.hexKm.toFloat() / e
            val toward = max(0.05f, 1f + BEARING_SPILL * bearings.along(k.i, h, nh, hex.hexKm))
            val q = hw.placeNear(p, nh); if (q.none) continue
            // (The thirsty shun country where they could not drink, the more the thirstier: as groups head for water.)
            val worth = if (thirst > 0f && !drinks(s, q)) worthOf[q] * (1f - thirst) else worthOf[q]
            if (worth > best) best = worth
            ws[d] = ease * (worth + 1e-4f) * toward
            wq[d] = q.i
            total += ws[d]
        }
        // A few swim the strait to the coast across, as far as they can swim it.
        var seaW = 0f; var seaTo = -1; var seaGapKm = 0f
        if (swim > 0f && linksTo != null && gapsTo != null) {
            val to = linksTo[h]; val gaps = gapsTo[h]
            for (i in to.indices) { val nh = to[i]; val gapKm = gaps[i]
                if (gapKm > swim) continue; val q = hw.placeNear(p, nh).takeIf { !it.none } ?: continue
                // (Weighed by those that would reach the far shore: the strait drowns some, as it does groups.)
                val wq = SEA_SPILL * (1f - gapKm / swim) * (1f - drownRisk(s, gapKm)) * land.areaKm2[q] * (worthOf[q] + 1e-4f)
                if (wq > seaW) { seaW = wq; seaTo = q.i; seaGapKm = gapKm } }
        }
        val here = if (thirst > 0f && !drinks(s, p)) worthOf[p] * (1f - thirst) else worthOf[p]
        val heads = ((a[p] + y[p]) * land.areaKm2[p]).toDouble()
        weekHeads += heads
        if (f[p] < STUCK_FED && best <= here) { weekStuck += heads; if (closed) weekEdge += heads }
        if (total <= 0f) continue
        // (And the more so the better the country next door is than here.)
        val better = if (best > 0f) ((best - here) / best).coerceIn(0f, 1f) else 0f
        val go = min(0.5f, leave + (if (settled) 0f else SPILL_BETTER * better)).let { if (weeks == 1) it else 1f - (1f - it).pow(weeks) }
        val goA = a[p] * go * land.areaKm2[p]; val goY = y[p] * go * land.areaKm2[p]
        if (seaTo >= 0) {
            val share = seaW / (total + seaW)
            moveA[p] -= goA * share
            moveY[p] -= goY * share
            // (The drowned wash up on the far shore.)
            val risk = drownRisk(s, seaGapKm)
            val lostA = goA * share * risk; val lostY = goY * share * min(1f, 2f * risk)
            moveA[seaTo] += goA * share - lostA
            moveY[seaTo] += goY * share - lostY
            cameA[seaTo] += goA * share - lostA; for (c in carry.indices) came[c][seaTo] += (goA * share - lostA) * carry[c][p]
            drowned[seaTo] += lostA + lostY * YOUNG_WEIGHT; drownedHeads += (lostA + lostY).toDouble()
        }
        moveA[p] -= goA; moveY[p] -= goY
        for (i in ws.indices) {
            if (ws[i] <= 0f) continue
            val q = wq[i]
            val ga = goA * ws[i] / total; val gy = goY * ws[i] / total
            moveA[q] += ga; moveY[q] += gy
            cameA[q] += ga; for (c in carry.indices) came[c][q] += ga * carry[c][p]
            arrived[q] += ga + gy; walkedKm[q] += (ga + gy) * (hex.hexKm.toFloat() + climbKm(k, p, Place(q)))
            if (flow) recordFlow(h, hw.placeHex[q], ga + gy)
        }
    }
    countStuck(k, weekHeads, weekStuck, weekEdge)
    for (p in land.places) {
        // (Those that stayed are those there less those that left: the grown that came are all in moveA.)
        if (cameA[p] > 0f) { val stayed = max(0f, a[p] * land.areaKm2[p] + moveA[p] - cameA[p])
            for (c in carry.indices) carry[c][p] = bound(c, k, mixed(carry[c][p], stayed, came[c][p] / cameA[p], cameA[p])) }
        a[p] = max(0f, a[p] + moveA[p] / land.areaKm2[p]); y[p] = max(0f, y[p] + moveY[p] / land.areaKm2[p])
    }
    return drownedHeads
}

/**
 * Adds [grown] and [youngHeads] heads of kind [k] to place [to] from place [from], the grown with what they carry from
 * there ([carried]), mixed in by heads ([mixed]).
 */
private fun HexFauna.settle(k: FaunaKind, to: Int, grown: Float, youngHeads: Float, from: Int) {
    val a = adults[k]; val area = land.areaKm2[to]; val carry = carried(k)
    for (c in carry.indices) carry[c][to] = bound(c, k, mixed(carry[c][to], a[to] * area, carry[c][from], grown))
    a[to] += grown / area; young[k][to] += youngHeads / area
}

/**
 * [n] grown of resident kind [s] come into the wild at [p] from people's camps (dogs gone wild, [HexGroups.dogsGoWild]):
 * bold of people, without fat or body burnt, their season of births not begun. Nothing if [s] is not a resident.
 */
fun HexFauna.cameWild(s: Species, p: Place, n: Int) {
    val k = slot(s); if (k.none || n < 1) return
    val a = adults[k]; val area = land.areaKm2[p]; val carry = carried(k)
    for (c in carry.indices) carry[c][p] = bound(c, k, mixed(carry[c][p], a[p] * area, if (Carry.entries[c] == Carry.BOLD) 1f else 0f, n.toFloat()))
    a[p] += n / area
}

/** Takes [heads] young of kind [k] off the places of hex [h], each place its share by its young there. */
fun HexFauna.takeYoung(k: FaunaKind, h: Hex, heads: Float) {
    val y = young[k]; val places = hw.placesOf(h)
    var there = 0f; for (q in places) there += y[q] * land.areaKm2[q]
    if (there <= 0f) return
    val keep = max(0f, 1f - heads / there)
    for (q in places) y[q] *= keep
}

/** Takes [heads] grown of kind [k] off the places of hex [h], each place its share by its grown there. Returns the place that held the most. */
fun HexFauna.takeGrown(k: FaunaKind, h: Hex, heads: Float): Place {
    val a = adults[k]; val places = hw.placesOf(h)
    var there = 0f; var most = places[0]; var mostHeads = -1f
    for (q in places) { val hq = a[q] * land.areaKm2[q]; there += hq; if (hq > mostHeads) { mostHeads = hq; most = q } }
    if (there > 0f) { val keep = max(0f, 1f - heads / there); for (q in places) a[q] *= keep }
    return Place(most)
}

/** The grown heads of kind [k] on the places of hex [h]. */
fun HexFauna.grownIn(k: FaunaKind, h: Hex): Float { var v = 0f; for (q in hw.placesOf(h)) v += adults[k][q] * land.areaKm2[q]; return v }

/**
 * Whether a roamer of [s] finds room to settle in hex [h]: its kind's settled there are fewer than its territories hold
 * ([usual] over the hex's land).
 */
fun HexFauna.roomFor(s: Species, h: Hex): Boolean {
    val k = slot(s); if (k.none) return false
    var heads = 0f; var area = 0f
    for (q in hw.placesOf(h)) { heads += (adults[k][q] + young[k][q]) * land.areaKm2[q]; area += land.areaKm2[q] }
    return heads < usual[k] * area
}

/**
 * Group [g] of a kind that also roams settles: it joins its kind's settled at its place, carrying its fat, body burnt,
 * season of births had and boldness of people in with them as any that move do ([carried]).
 */
fun HexFauna.joinSettled(g: HexGroup) {
    val k = slot(g.species); if (k.none) return
    val p = g.place; val a = adults[k]; val area = land.areaKm2[p]; val carry = carried(k)
    for (c in carry.indices) {
        val had = when (Carry.entries[c]) { Carry.BRED -> g.bred; Carry.FAT -> g.fat; Carry.SPENT -> g.spent; Carry.BOLD -> boldness(g) }
        carry[c][p] = bound(c, k, mixed(carry[c][p], a[p] * area, had, g.adults.toFloat()))
    }
    a[p] += g.adults / area; young[k][p] += g.young / area
}

/** A per-head number of [there] grown that had [was] and [came] that had [had], together. */
private fun HexFauna.mixed(was: Float, there: Float, had: Float, came: Float) =
    if (there + came > 0f) (was * there + had * came) / (there + came) else was

/** Adds a move's heads of kind [k], those hungry with nowhere better to go, and of them those at a closed border, to [stuck]. */
private fun HexFauna.countStuck(k: FaunaKind, heads: Double, stuckHeads: Double, atEdge: Double) {
    stuck[k].let { it[Stuck.HEADS.ordinal] += heads; it[Stuck.STUCK.ordinal] += stuckHeads; it[Stuck.AT_EDGE.ordinal] += atEdge
        if (heads > 0.0) it[Stuck.PEAK.ordinal] = max(it[Stuck.PEAK.ordinal], stuckHeads / heads) }
}

/**
 * Plant-eating kind [k]'s residents flow over the land as a fluid finds its level, a step each move: a share of those
 * at each place moves to the neighbouring places worth more to them than where they are, at the numbers there and here
 * as they stand ([placeWorth]): the neighbouring hexes at about the same height (their band or the one above or below),
 * the bands above and below in their own hex, and the shores across a strait they can swim (by those that reach it, the
 * strait drowning some). The share leaving is [FLOW_STEP] of how much better the best of them is (the gap, against its
 * worth), less over a hard border; they split among the better ones by how much better each is. As they come, a place
 * is worth less (more crowded, less food a head), and the place they left more: so from move to move they flow until
 * each place is worth about as much as those about it. Where those coming to a place would leave it worth less than the
 * places they came from, only as many come as would bring it down to them (as a straight line between it as it is and
 * with all of them would have it). The food does not move: what a place feeds is what those there ate and the food left
 * uneaten ([spareHeads]), shared among however many there are. Asleep, they stay. Adds the heads that came to each
 * place to [arrived], the km they walked to [walkedKm] (a hex's border crossing, and the height they climbed as level km,
 * see [climbKm]), and the drowned to [drowned]; leaves what each place is worth in [worth] (where the kind was and next
 * to it, those come to by the same straight line; elsewhere nothing); gives the heads drowned. (Each place gathers what
 * comes to it by its ways in ([FlowWays.inWay]), so the places are worked on all cores, in the same order each run.)
 */
internal fun HexFauna.flow(k: FaunaKind, weather: Weather, arrived: FloatArray, walkedKm: FloatArray, drowned: FloatArray): Double {
    val a = adults[k]; val y = young[k]; val area = land.areaKm2
    // (Its ways fetched once: they are looked up in the innermost loops.)
    val ways = flowWays[k]; val climb = climbPerM[k]
    val src = ways.src; val inFrom = ways.inFrom; val inWay = ways.inWay
    // Heads at each place, grown and young.
    val ga = FloatArray(n) { p -> a[p] * area[p] }; val gy = FloatArray(n) { p -> y[p] * area[p] }
    // (The grown times what each carries ([carried]): it goes with them.)
    val carry = carried(k); val gc = Array(carry.size) { c -> val per = carry[c]; FloatArray(n) { p -> per[p] * ga[p] } }
    // The food at each place weighed, in heads it feeds fully: those there, as fed as they were, and the food left uneaten.
    val food = FloatArray(n)
    val v = worth[k]
    // (What does not change at a place within the week is worked out once, as the place is first weighed.)
    val fixed = WeekFixed(n)
    // (A year of this week at a place, at [heads] there: how they would fare there, before what they remember.)
    fun weigh(p: Place, heads: Float): Float {
        val t = scratch.get()
        if (!fixed.done[p]) fix(k, p, weather, t.outs, fixed)
        return placeWorth(k, p, fixed, heads, food[p], t.parts)
    }
    // (Only the places the kind is at and those next to them are weighed: no other can matter.)
    Par.forEachPlace("residents: weigh", n) { p ->
        v[p] = 0f
        var near = ga[p] + gy[p] > 0f
        if (!near) for (j in inFrom[p] until inFrom[p.i + 1]) { val o = src[inWay[j]]; if (ga[o] + gy[o] > 0f) { near = true; break } }
        if (!near) return@forEachPlace
        val t = scratch.get()
        fix(k, p, weather, t.outs, fixed); if (!fixed.drinks[p]) return@forEachPlace
        food[p] = fed[k][p] * (ga[p] + gy[p]) + spareHeads(k, p, weather, t.pantry, t.bites)
        v[p] = weigh(p, ga[p] + gy[p])
    }

    // Each place's leavers.
    val share = FloatArray(n)      // the share of those at each place leaving it
    val gaps = FloatArray(ways.to.size)  // the gap along each way from a place leaving ([gapTo])
    val gapSum = FloatArray(n)     // the sum of the gaps to the better places about it, to split them by
    Par.forEachPlace("residents: weigh, spread", n) { p ->
        if (!(ga[p] + gy[p] > 0f) || asleep(k, p)) return@forEachPlace
        val here = v[p]; var most = 0f; var sum = 0f
        for (i in ways.from[p] until ways.from[p.i + 1]) {
            val g = gapTo(here, v[ways.to[i]] * ways.keep[i], ways.ease[i])
            gaps[i] = g
            if (g > 0f) { sum += g; if (g > most) most = g }
        }
        share[p] = FLOW_STEP * most; gapSum[p] = sum
    }
    // (Those hungry with nowhere better to go.)
    var weekHeads = 0.0; var weekStuck = 0.0; var weekEdge = 0.0; var any = false
    for (p in land.places) {
        val heads = ga[p] + gy[p]; if (!(heads > 0f)) continue
        weekHeads += heads
        if (share[p] > 0f) any = true
        else if (fed[k][p] < STUCK_FED) { weekStuck += heads; if (atClosedBorder(k, p)) weekEdge += heads }
    }
    countStuck(k, weekHeads, weekStuck, weekEdge)
    if (!any) return 0.0
    // (The heads that would go along each way, of all that would.)
    fun going(i: Int): Float { val p = src[i]; val g = gaps[i]; return if (g > 0f && share[p] > 0f) share[p] * (ga[p] + gy[p]) * g / gapSum[p] else 0f }
    // As many come as keep a place worth no less than where they came from; it is then worth what the straight line
    // between it as it is and with all of them gives for those that come.
    val coming = FloatArray(n)     // heads that would come to each place
    val take = FloatArray(n)       // the share of those coming that come
    val after = FloatArray(n)      // what each place is worth with them come
    Par.forEach("residents: going", n) { q ->
        take[q] = 1f
        var c = 0f; var fromWorth = 0f
        for (j in inFrom[q] until inFrom[q + 1]) { val i = inWay[j]; val m = going(i); if (m > 0f) { c += m; fromWorth += m * v[src[i]] } }
        coming[q] = c
        if (!(c > 0f)) return@forEach
        val now = v[q]; val from = fromWorth / c
        val all = weigh(Place(q), ga[q] + gy[q] + c)
        val t = if (all < from && now > all) ((now - from) / (now - all)).coerceIn(0f, 1f) else 1f
        take[q] = t; after[q] = now + t * (all - now)
    }
    // They go: the heads along each way, and of each place, those that left it.
    val moved = FloatArray(ways.to.size); val left = FloatArray(n)
    Par.forEachPlace("residents: going, gathered", n) { p ->
        if (!(share[p] > 0f)) return@forEachPlace
        var l = 0f
        for (i in ways.from[p] until ways.from[p.i + 1]) { val m = going(i) * take[ways.to[i]]; if (m > 0f) { moved[i] = m; l += m } }
        left[p] = l
    }
    // Each place: those that stayed, and those that came (a strait drowning some, the young the more, as in
    // [spillHunters]: they wash up on the far shore), with what they carry.
    val na = FloatArray(n); val ny = FloatArray(n); val nc = Array(gc.size) { FloatArray(n) }
    val drownedAt = DoubleArray(n); val climbedAt = DoubleArray(n)
    Par.forEach("residents: going, out", n) { q ->
        val l = left[q]
        if (!(l > 0f)) { na[q] = ga[q]; ny[q] = gy[q]; for (c in gc.indices) nc[c][q] = gc[c][q] }
        else {
            val ys = gy[q] / (ga[q] + gy[q])
            na[q] = max(0f, ga[q] - l * (1f - ys)); ny[q] = max(0f, gy[q] - l * ys)
            for (c in gc.indices) { val had = if (ga[q] > 0f) gc[c][q] / ga[q] else 0f; nc[c][q] = max(0f, gc[c][q] - l * (1f - ys) * had) }
        }
        val zq = hw.placeElevationM[q]
        for (j in inFrom[q] until inFrom[q + 1]) {
            val i = inWay[j]; val m = moved[i]; if (!(m > 0f)) continue
            val p = src[i]
            val mY = m * (gy[p] / (ga[p] + gy[p])); val mA = m - mY
            val risk = 1f - ways.keep[i]
            val lostA = mA * risk; val lostY = mY * min(1f, 2f * risk)
            if (lostA + lostY > 0f) { drowned[q] += lostA + lostY * YOUNG_WEIGHT; drownedAt[q] += (lostA + lostY).toDouble() }
            val came = m - lostA - lostY; val rise = zq - hw.placeElevationM[p]
            na[q] += mA - lostA; ny[q] += mY - lostY
            for (c in gc.indices) nc[c][q] += (mA - lostA) * (if (ga[p] > 0f) gc[c][p] / ga[p] else 0f)
            arrived[q] += came; walkedKm[q] += came * (ways.km[i] + climb * max(0f, rise))
            climbedAt[q] += came.toDouble() * rise
        }
    }
    if (flowOf == k.i) {
        flowEast.fill(0f); flowSouth.fill(0f); flowOut.fill(0f)
        for (i in moved.indices) if (moved[i] > 0f) { val h = hw.placeHex[src[i]]; val to = hw.placeHex[ways.to[i]]; if (to != h) recordFlow(h, to, moved[i]) }
    }
    climbed[k][weather.date.month] += climbedAt.sum()
    // (Those left are worth what they were, a little less than they now are, till the next move weighs them again.)
    Par.forEach("residents: going, in", n) { p ->
        if (coming[p] > 0f) v[p] = after[p]
        a[p] = na[p] / area[p]; y[p] = ny[p] / area[p]
        if (na[p] > 0f) for (c in carry.indices) carry[c][p] = bound(c, k, nc[c][p] / na[p])
    }
    return drownedAt.sum()
}

/** How much better a place worth [there] is than one worth [here], against its worth, eased by the border between ([flow]): none where it is no better. */
private fun HexFauna.gapTo(here: Float, there: Float, ease: Float) = if (there > here) ease * (there - here) / there else 0f

/**
 * The places a kind's residents at place [p] can flow to by its ways [w] ([flow], [flowWays]), each with how easy it is to cross to (1 at best),
 * the km of the crossing and the share that would live through it ([flowWays]).
 */
internal inline fun HexFauna.flowPlaces(w: FlowWays, p: Place, f: (q: Int, ease: Float, km: Float, keep: Float) -> Unit) {
    for (i in w.from[p] until w.from[p.i + 1]) f(w.to[i], w.ease[i], w.km[i], w.keep[i])
}

/** Whether some border of place [p]'s hex is closed to kind [k] (the map's edge, or one it cannot cross): for [stuck]. */
private fun HexFauna.atClosedBorder(k: FaunaKind, p: Place): Boolean {
    val h = hw.placeHex[p]; val cross = hex.crossings[travel[k].ordinal]
    for (d in 0 until 6) { val nh = hex.neighbour(h, d); if (nh.none || !cross[hex.edge(h, d)].isFinite()) return true }
    return false
}

/**
 * What place [p] is worth to plant-eating kind [k]'s residents with [heads] of them there ([flow]): how they would
 * grow there over a year as this week ([growthFor]), crowded as they would be there, and fed as far as the [food] there
 * (in heads it feeds fully) goes round them; none where they cannot drink. What does not change there within the week is [fixed]'s.
 */
internal fun HexFauna.placeWorth(k: FaunaKind, p: Place, fixed: WeekFixed, heads: Float, food: Float, parts: EnumFloats<Growth>): Float {
    if (!fixed.drinks[p]) return 0f
    val f = if (heads > 0f) min(1f, food / heads) else if (food > 0f) 1f else 0f
    growthOf(k, p, fixed.grown[p], fixed.young[p], fixed.huntedYoung[p], fixed.protein[p], moverYoung[k], moverFat[k], f, heads / land.areaKm2[p], parts)
    return worthOrNothing(growthFor(k, parts, moverYoung[k]))
}

/** Fills [w] at place [p] for kind [k] this week, by [hazards] (its parts that do not change with numbers or food). */
internal fun HexFauna.fix(k: FaunaKind, p: Place, weather: Weather, out: EnumFloats<Danger>, w: WeekFixed) {
    val s = kinds[k]
    w.done[p] = true; w.drinks[p] = drinks(s, p)
    if (!w.drinks[p]) return
    hazards(k, p, weather, weather.date.month, 1f, 0f, 0f, out)
    val protein = forageProtein(s, p)
    w.grown[p] = grownFixed(s, out); w.protein[p] = protein; w.huntedYoung[p] = huntedOdds(k, p, true)
    w.young[p] = youngFixed(s, out, eagleOdds(s, p), crocodileOdds(p, weather), starvedOdds(protein))
}

/**
 * How many heads of plant-eating kind [k] the food left uneaten at place [p] would feed fully this week ([flow]): the
 * share of their need a crowd far past what it could feed would get there ([roomFor]), times its heads: all the food
 * they could get at, in heads' needs.
 */
internal fun HexFauna.spareHeads(k: FaunaKind, p: Place, weather: Weather, dry: DryPantry, b: Bites): Float {
    if (asleep(k, p)) return 0f
    val crowd = SPARE_CROWD * land.areaKm2[p]
    return roomFor(k, p, crowd, weather, dry, b) * crowd
}

/**
 * Kind [k]'s animals too thinly spread to keep apart (under [inflation.Tuning.residentFloor] of its usual numbers at a
 * place) gather a step to the neighbouring place that holds the most of its thinly spread animals, where they can
 * stay: so the thin edge of a kind keeps together in fewer places, spreading still as the most of it does. Adds those
 * that came, and the km they walked, to [arrived] and [walkedKm].
 */
private fun HexFauna.gatherThin(k: FaunaKind, arrived: FloatArray, walkedKm: FloatArray) {
    val floor = tuning.residentFloor * usual[k]
    if (!(floor > 0f)) return
    val s = kinds[k]; val a = adults[k]; val y = young[k]; val area = land.areaKm2
    for (p in land.places) {
        val d = a[p] + y[p]
        if (!(d > 0f) || d >= floor) continue
        var to = -1; var most = d * area[p]
        neighbourPlaces(k, p) { q -> val dq = a[q] + y[q]; if (dq < floor && dq * area[q] > most && drinks(s, Place(q))) { most = dq * area[q]; to = q } }
        if (to < 0) continue
        val ga = a[p] * area[p]; val gy = y[p] * area[p]
        settle(k, to, ga, gy, p.i); a[p] = 0f; y[p] = 0f
        arrived[to] += ga + gy; walkedKm[to] += (ga + gy) * (hex.hexKm.toFloat() + climbKm(k, p, Place(to)))
    }
}

/** Share of [s] that drown swimming [gapKm] of open water: by the groups' rule, the nearer the strait to all it can swim, the more. */
internal fun HexFauna.drownRisk(s: Species, gapKm: Float) = min(1f, Hazards.drowning(s, gapKm))
