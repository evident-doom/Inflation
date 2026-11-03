package inflation.ecology

import inflation.Part
import inflation.ecology.HexFeatures as Feature
import inflation.ecology.GroupPlanner.PlaceTerms
import inflation.ecology.GroupPlanner.PlanContext
import inflation.world.Hex
import inflation.world.HexArray
import inflation.world.Place
import inflation.world.get
import inflation.world.set
import inflation.KgM2
import inflation.monthsFrom
import inflation.world.Date
import inflation.world.Weather
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

// Choosing: weighing a place, the features a network reads and the hand-written score, what would kill a group there, and what it knows of the game, hunters and its own kind about.

/**
 * The features of place [p] (in hex [h]) for the group weighing its places in [c], into [f] (cleared): what a learned
 * policy sees of it ([Feature]). What it knows of the place ([PlanContext.at]) is worked out in [planInner] as the
 * hand-written score weighs it.
 */
@Part("choosing: features of a place")
internal fun GroupPlanner.fillFeatures(f: FloatArray, c: PlanContext, t: PlaceTerms, p: Place, h: Hex) {
    val d = c.at.d; val toGoal = c.at.toGoal; val pull = c.at.pull; val beyond = c.at.beyond; val foreign = c.at.foreign
    val prey = c.at.prey; val crowded = c.at.crowded; val hunters = c.at.hunters
    val g = c.g; val s = g.species; val here = c.here; val month = c.month; val reach = c.reach; val goal = c.goal
    val fromHere = c.fromHere; val weather = c.weather; val date = c.date
    // (The herds' slots, [Feature.HERDS_ABOUT], [Feature.KEEPS_HEADING] and [Feature.FORAGE_QUALITY], stay 0: kept for the
    // networks' layout until they are trained again.)
    // What the place offers it, as the baseline weighs it: game, and for bands plant food and a safe camp.
    f[Feature.FOOD] = prey / (prey + PREY_HALF)
    f[Feature.CLIMATE] = t.suitsLn / InputScale.SUITS_LN
    // (What the coming months are usually like there.)
    for (k in 0 until Feature.LORE_MONTHS)
        f[Feature.USUAL_AHEAD + k] = weather.usualTemperature((month + k + 1) % Date.MONTHS_PER_YEAR, p) / InputScale.USUAL_C
    f[Feature.CROWDING] = crowded
    f[Feature.HUNTERS] = hunters / (hunters + 3f)
    f[Feature.DISCOMFORT] = min(2f, t.discomfort + t.drain)
    // (The flies are felt now; only the weather is foreseen.)
    f[Feature.DISCOMFORT_FORECAST] = min(2f, t.aheadC / InputScale.AHEAD_C + t.drain)
    // People see only what they can read: the weather coming as their lore and sky-watching
    // allow (else they expect this week's again), the beasts about as their tracking does,
    // and the food of country they do not know only dimly.
    g.band?.let { b ->
        val sense = ((if (b.holds(Tradition.SKY_LORE)) 0.5f else 0f) + 0.5f * b.skill(Skill.LORE)).coerceIn(0f, 1f)
        f[Feature.DISCOMFORT_FORECAST] = f[Feature.DISCOMFORT] + sense * (f[Feature.DISCOMFORT_FORECAST] - f[Feature.DISCOMFORT])
        f[Feature.HUNTERS] = SIGN_GUESS + b.skill(Skill.TRACK) * (f[Feature.HUNTERS] - SIGN_GUESS)
        if (!b.knows(h)) f[Feature.FOOD] = 0.5f * f[Feature.FOOD] + 0.5f * SIGN_GUESS
    }
    f[Feature.SNOW_OVER_LIMIT] = if (weather.snowpack[p] > s.snowLimitMm) 1f else 0f
    f[Feature.SNOW] = min(2f, weather.snowpack[p] / s.snowLimitMm)
    // (What the hand-written score weighs besides: snow coming with a freeze, as keenly as its kind foresees it; fire;
    // the fear of ground where game was killed lately; and a much stronger hunter of another kind holding the place.)
    if (weather.forecastTemp(p.i) < 0f && weather.snowpack[p] + weather.forecastRain(p.i) > s.snowLimitMm) f[Feature.SNOW_AHEAD] = s.foresight
    f[Feature.BURNING] = gs.land.fire.burning[p]
    f[Feature.KILL_FEAR] = gs.fauna.afraid(p)
    if (gs.hex.distanceKm(h, here) <= gs.hex.hexKm * 1.01 && topKind[p] >= 0 && topKind[p] != s.index)
        f[Feature.STRONGER_HUNTER] = min(1f, topStrength[p] / (4f * gs.strength(g)).coerceAtLeast(1e-3f))
    f[Feature.CLIMB] = ((gs.hw.placeElevationM[p] - gs.hw.placeElevationM[g.place]) / 1000f).coerceIn(-3f, 3f)
    f[Feature.EFFORT] = (d / reach.coerceAtLeast(1f)).coerceIn(0f, 1f)
    f[Feature.STAYS] = if (p == g.place) 1f else 0f
    f[Feature.SAME_HEX] = if (h == here) 1f else 0f
    if (goal >= 0) {
        f[Feature.TOWARD_BEARING] = if (fromHere > gs.hex.hexKm) ((fromHere - toGoal) / fromHere).toFloat().coerceIn(-1f, 1f) else 0f
        f[Feature.SEASON_PLACE] = (c.goalQ - g.fed).coerceIn(-1f, 1f)
    }
    f[Feature.FED] = g.fed
    f[Feature.FAT] = g.fat / s.maxFatDays.coerceAtLeast(1f)
    f[Feature.SEASON_SIN] = sin(c.season).toFloat(); f[Feature.SEASON_COS] = cos(c.season).toFloat()
    f[Feature.RESTLESS] = if (c.restless) 1f else 0f
    f[Feature.WATER_DISTANCE] = min(3f, t.waterKm / s.dailyTravelKm)
    f[Feature.DAYS_DRY] = min(2f, g.dry / s.daysWithoutWater)
    f[Feature.PULL] = pull
    // The moon (night hunters see and are seen by it) and how far off its young are due.
    f[Feature.MOON] = gs.moon
    val toBirth = 2.0 * Math.PI * monthsFrom(month, if (g.birthMonth >= 0) g.birthMonth else s.birthMonth) / 12.0
    f[Feature.TO_BIRTHS_SIN] = sin(toBirth).toFloat(); f[Feature.TO_BIRTHS_COS] = cos(toBirth).toFloat()
    // Its ancestors buried here, and how far going here brings it toward its appointment.
    f[Feature.ANCESTORS] = min(1f, g.memory.deadAt(h) / InputScale.ANCESTOR_DEAD)
    f[Feature.APPOINTMENT] = g.band?.let { gs.appointPull(g, it, month, h) } ?: 0f
    f[Feature.HOUSES] = if (g.band != null) gs.housesAt(g, h) else 0f
    f[Feature.OWN_SIZE] = ln(g.size.toFloat().coerceAtLeast(1f) / gs.unitSize(s)) / InputScale.OWN_SIZE_LN
    f[Feature.LASTING_WATER] = min(1f, gs.land.drink.nearRiver[p] + gs.land.drink.nearLake[p] + gs.land.drink.nearSpring[p])
    f[Feature.SICKNESS] = min(1f, gs.fauna.outbreakDeaths(s, p) * InputScale.SICKNESS_PER_DEATH)
    // The signs of the land, as anyone who looks can read them.
    f[Feature.TREES] = min(1f, gs.land.plants.tree[p] / InputScale.TREES_FULL); f[Feature.SHRUBS] = min(1f, gs.land.plants.shrub[p] / InputScale.SHRUBS_FULL)
    f[Feature.GRASS] = min(1f, gs.land.plants.herb[p] / InputScale.GRASS_FULL)
    f[Feature.MARSH] = min(1f, gs.land.waterlogged[p])
    f[Feature.WATER_AGAINST_USUAL] = (gs.land.drink.water[p] - min(1f, gs.land.drink.nearRiver[p] + gs.land.drink.nearLake[p] +
        gs.land.drink.nearSpring[p])).coerceIn(-1f, 1f)
    f[Feature.PATHS] = gs.wear.at(h)
    f[Feature.DUNG] = min(1f, gs.fauna.small.dung[p] * InputScale.DUNG_PER)
    f[Feature.GRAZING_AND_KILLS] = min(1f, (gs.land.plants.grazing[p] + gs.land.plants.browsing[p]) / InputScale.GRAZING_FULL) +
        min(1f, KgM2(gs.fauna.small.carrion[p] + gs.fauna.small.bones[p]).perKm2.v / 20f)
    f[Feature.KNOWN_GROUND] = g.band?.let { if (it.knows(h)) 1f else 0f } ?: 1f
    f[Feature.BEYOND_OWN_GROUND] = beyond; f[Feature.OTHERS_MARKS] = foreign
    // The fish and shellfish a band could take there, with its skill, and from a weir standing there that it may use,
    // against its need (the shore and the river are plain to see; how rich they are, less so where it is a stranger).
    f[Feature.FISH] = g.band?.let { b -> min(2f, catchWithin(g, b, h) * Nourishment.FISH * s.needScale / (g.size * s.dailyIntakeKg * gs.days).coerceAtLeast(1f)) *
        (if (b.knows(h)) 1f else 0.5f) } ?: 0f
    // The plant food its gatherers could take there, as dimly where it is a stranger.
    g.band?.let { b ->
        gs.plantFood(g, b, p, f, Feature.PLANT_FOOD)
        if (!b.knows(h)) for (k in 0 until 3) f[Feature.PLANT_FOOD + k] *= 0.5f
    }
    // What it remembers of this hex: in this season, or failing that in any.
    val mem = g.memory
    var slot = mem.find(h, PlaceMemory.seasonOf(month)); val sameSeason = slot >= 0
    if (slot < 0) for (i in 0 until PlaceMemory.MEMORY_SLOTS) if (mem.hex[i] == h) { slot = i; break }
    if (slot >= 0) {
        f[Feature.REMEMBERED] = 1f; f[Feature.REMEMBERED_SAME_SEASON] = if (sameSeason) 1f else 0f
        f[Feature.REMEMBERED_FED] = mem.fed[slot]; f[Feature.REMEMBERED_WATER] = mem.water[slot]; f[Feature.REMEMBERED_DANGER] = mem.danger[slot]
        f[Feature.REMEMBERED_AGE] = min(1f, (gs.stepOf(date) - mem.lastWeek[slot]) / gs.stepsPerYear)
    }
    // What it remembers finding there that it lacks, and how far the place takes it toward the best of that it knows.
    c.lacked?.let { lacked ->
        var most = 0f
        for (i in 0 until PlaceMemory.MEMORY_SLOTS) if (mem.hex[i] == h) for (k in lacked.indices) most = max(most, lacked[k] * mem.foundAt(i, k))
        f[Feature.REMEMBERED_LACKED] = most
        if (!c.lackedHex.none && c.lackedFrom > gs.hex.hexKm)
            f[Feature.TOWARD_LACKED] = ((c.lackedFrom - gs.hexKm(h, c.lackedHex)) / c.lackedFrom).toFloat().coerceIn(-1f, 1f) * c.lackedQ
    }
}

/**
 * The hand-written score of place [p] (in hex [h]; what the group knows of it in [PlanContext.at]) for the group weighing its places in [c]:
 * its food or game, crowding and hunters, the cold, heat and flies, its own kind of country, what it senses coming,
 * water, the going, the season's place or round, its bearing, staying, and a band's own pulls.
 */
@Part("choosing: score a place")
internal fun GroupPlanner.scorePlace(c: PlanContext, t: PlaceTerms, p: Place, h: Hex, parts: FloatArray? = null): Float {
    val d = c.at.d; val toGoal = c.at.toGoal; val offGround = c.at.offGround; val pull = c.at.pull
    val prey = c.at.prey; val crowded = c.at.crowded
    val g = c.g; val s = g.species; val here = c.here; val sensed = c.sensed; val month = c.month; val goal = c.goal
    val goalQ = c.goalQ; val fromHere = c.fromHere; val reach = c.reach
    val thirsty = c.thirsty; val keepOff = c.keepOff; val weather = c.weather
    // Its food or game there, against crowding (see [foodTerm]).
    var score = gs.foodTerm(g, p, h, sensed, prey, crowded)
    // (moveTrace: each part of the score as it is added, [MoveTerm].)
    var last = 0f
    if (parts != null) { parts[MoveTerm.FOOD.ordinal] += score - last; last = score }
    // The cold or heat it bears there, and what the place drains from it (the extra food the
    // cold burns, the feeding or working time the flies take), weighed as the going is: in
    // shares of the week's need.
    score -= 0.5f * t.discomfort + t.drain + offGround
    if (parts != null) { parts[MoveTerm.COLD.ordinal] += score - last; last = score }
    // Its own kind of country: open plains for a cheetah, crags for a wild goat, whatever the food.
    if (!s.human) score += GROUP_HABITAT * t.suitsLn
    // What it senses coming, as keenly as its kind does: a cold snap or heat wave, snow
    // too deep to dig through, and in a dry spell the rain on its way.
    val tAhead = weather.forecastTemp(p.i)
    score -= 0.5f * s.foresight * t.aheadC / 10f
    if (tAhead < 0f && weather.snowpack[p] + weather.forecastRain(p.i) > s.snowLimitMm) score -= 0.5f * s.foresight
    if (gs.land.hydro.soil[p] < DRY_SOIL_MM) score += 0.3f * s.foresight * min(1f, weather.forecastRain(p.i) / RAIN_AHEAD_MM)
    if (parts != null) { parts[MoveTerm.FORECAST.ordinal] += score - last; last = score }
    // Water: a thirsty group heads for it; any group keeps within reach of it, the more so the more often it must drink.
    // (People can dig for water in a dry bed where it lies near; the days their water takes them are in the place's food
    // value already, [GroupWorld.campFactor].)
    score -= ((if (s.human) 0f else 0.02f) + 0.3f * g.dry / s.daysWithoutWater) * t.waterKm / s.dailyTravelKm
    if (thirsty && !gs.canDrink(s, p)) score -= 1f
    if (weather.snowpack[p] > s.snowLimitMm) score -= 1f
    if (parts != null) { parts[MoveTerm.WATER.ordinal] += score - last; last = score }
    // The going costs food: a share of the week's need, the height gained as the level walk it equals (see [walkCost]).
    score -= WALK_COST * (d + climbKmPerM(s.massKg) * max(0f, gs.hw.placeElevationM[p.i] - gs.hw.placeElevationM[g.place.i])) / gs.days
    if (parts != null) { parts[MoveTerm.WALK.ordinal] += score - last; last = score }
    // A band moving camp loses the days the walk takes from its week's work, beyond those it had to spare; the new camp
    // pays them back over the weeks it will keep it (as foragers weigh travel time against a patch's stay).
    g.band?.let { b -> score -= max(0f, gs.moveDays(g, d) - b.spareDays / forageHands(g)) / (gs.days * b.stayWeeks) }
    if (parts != null) { parts[MoveTerm.MOVE_DAYS.ordinal] += score - last; last = score }
    // (Above all, it keeps alive: its deaths there are weighed against all this by the caller, see [planInner].)
    // Bands remember where they did best each month and are drawn back there.
    if (goal >= 0 && s.human) {
        // The season's place draws a band on, the more so the better it promises and the worse it fares now.
        // Bands that watch the sky keep to their season's place on time.
        val sky = if (g.band?.holds(Tradition.SKY_LORE) == true) 1.3f else 1f
        val prog = ((fromHere - toGoal) / min(fromHere, reach.toDouble())).toFloat().coerceIn(-1f, 1f)
        if (fromHere > gs.hex.hexKm) score += sky * gs.tuning.seasonPull * (0.3f * goalQ + (goalQ - g.fed).coerceIn(0f, 1f)) * prog
    }
    if (parts != null) { parts[MoveTerm.SEASON.ordinal] += score - last; last = score }
    score += gs.tuning.bearingPull * pull
    if (parts != null) { parts[MoveTerm.BEARING.ordinal] += score - last; last = score }
    // (Staying is staying in the camp's hex: its other ground is the same camp, with the same fouling.)
    if (h == here) score += STAY - (g.band?.let { gs.stayCost(g, it) } ?: 0f)
    if (parts != null) { parts[MoveTerm.STAY.ordinal] += score - last; last = score }
    // Bold animals of kinds that follow people are drawn to their camps: refuse, scraps, warmth.
    if (s.followsPeople && gs.peopleSlot >= 0) score += CAMP_LURE * boldness(g) * min(1f, knownHeads(gs.peopleSlot, p, h, sensed) / 25f)
    g.band?.let { b -> score += bandPull(g, b, month, h, here, p, keepOff, sensed, reach) }
    // A hungry pack gives up ground it has hunted out lately and looks further afield.
    if (g.fed < Thresholds.HUNGRY && (h == here || h in g.recent)) score -= HUNGRY_LEAVE
    if (parts != null) parts[MoveTerm.BAND.ordinal] += score - last
    return score
}

/**
 * The share of [c]'s group that would die this week at place [p] (in hex [h]), by the rules that
 * kill it there ([placeHazards], as [liveWeek] has them): hunger at how [fed] it foresees it would be ([foreseenFed]), thirst, the cold, fire, flood and deep snow,
 * plague, crocodiles, eagles, and for a band snakes and the beasts that take people (those of the groups about only
 * where it senses them). The young's odds count for the young. (What it shares wherever it goes, age, the background and its own band's ills, is
 * left out: it cannot tell places apart.)
 */
@Part("choosing: deaths at a place")
internal fun GroupPlanner.deathsAt(c: PlanContext, p: Place, h: Hex, fed: Float, km: Float, why: ((String, Float, Float) -> Unit)? = null): Float {
    val g = c.g; val s = g.species; val weather = c.weather; val b = g.band
    val thirst = gs.thirstOdds(g, p, gs.dryAfter(g, p, fed))
    val odds = c.odds.also { it.adult = 0f; it.young = 0f }
    gs.foreseenHazards(g, p, weather, fed, thirst, km,
        if (why == null) odds else HazardSink { cause, a, y -> odds.add(cause, a, y); if (a != 0f || y != 0f) why(cause.toString().lowercase(), a, y) })
    var adult = odds.adult; var young = odds.young
    if (b != null) {
        val venom = gs.venomOdds(s, p, weather)
        // (At its houses it would sleep behind their walls.)
        val home = h == b.houseHex
        val walled = if (home) b.house * gs.wallsAt(g, h) else 0f
        val stalked = gs.stalkRate(g, b, p, gs.residentManEaters(p) + (if (h in c.sensed) manEaterKm2[h] else 0f), walled,
            if (home) gs.palisadeAt(g, h) else 0f)
        adult += venom + stalked; young += venom + YOUNG_STALKED / b.childWatch.coerceAtLeast(0.25f) * stalked
        why?.let { it("venom", venom, venom); it("stalked", stalked, YOUNG_STALKED / b.childWatch.coerceAtLeast(0.25f) * stalked) }
        gs.dialUse?.amount(DialSite.CHILDREN_PLAN, b.childWatch, YOUNG_STALKED / b.childWatch.coerceAtLeast(0.25f) * stalked, YOUNG_STALKED * stalked, cut = b.childWatch < 0.25f)
        // The sicknesses it would catch there (marsh fever by the mosquitoes, the flux in a long-kept camp, the cough
        // where people crowd), and the fouling of the ground there, whoever left it: its own camping adds to it if it stays.
        val campWeeks = gs.campWeeksAt(h) + (if (h == c.here) 1 else 0)
        val crowd = gs.peopleCrowding(g, h); val t = weather.temperature[p]; val healed = healedShare(b); val kept = gs.hygieneAt(b, h)
        val illA = gs.illnessAhead(b, p, t, crowd, campWeeks, healed, false, kept)
        val illY = gs.illnessAhead(b, p, t, crowd, campWeeks, healed, true, kept); val fouled = gs.campFouling(g, h, campWeeks) * gs.years
        adult += illA; young += illY + fouled
        why?.let { it("illness ahead (camp %d weeks, crowd %.2f, %.0f °C)".format(campWeeks, crowd, t), illA, illY); it("fouling", 0f, fouled) }
        // What it would catch of the bands it sees gathered there (a feast draws bands together, and the cough with them).
        if (h in c.sensed && gs.peopleSlot >= 0) {
            // (Bands meet over the whole hex, whichever ground each stands on.)
            val stays = h == c.here
            var heads = 0; for (q in gs.hw.placesOf(h)) heads += gs.headsHere[gs.peopleSlot][Place(q)]
            val others = heads - (if (stays) g.size else 0)
            if (others > 0) for (ill in Illness.entries) if (ill.contagion > 0f) {
                var sick = 0f; for (q in gs.hw.placesOf(h)) sick += gs.sickPeople[ill.ordinal][Place(q)]
                val theirSick = (sick - (if (stays) g.size * b.sick[ill] else 0f)).coerceAtLeast(0f) / others
                val dying = caughtFrom(ill, theirSick, b) * ill.deadly * healed
                adult += dying; young += dying * ill.young
                why?.invoke("caught from others: ${ill.name.lowercase()}", dying, dying * ill.young)
            }
        }
    }
    // (A beast of prey foresees, besides, the much stronger parties of other kinds it would run into there.)
    val killed = if (b == null && !s.human) killedByStronger(c, p, h) else 0f
    return (g.adults * adult.coerceIn(0f, Hazards.MAX_GROWN_ODDS) + g.young * young.coerceIn(0f, Hazards.MAX_YOUNG_ODDS) + killed) / max(1, g.size)
}

/**
 * How many of pack [c]'s group the much stronger parties of other kinds would kill at place [p] (in hex [h]) in the
 * week, as the meetings do ([enemiesMeet]): the groups of each kind it knows of in the hex ([knownHeads], in usual
 * groups), how likely it runs into each ([encounters]), does not slip away ([slipChance]), and then loses one of its
 * young, or failing that one of its grown. (A party of usual size and strength, of no particular will to kill; a band
 * as one with spears, which kills grown beasts only.) So a cheetah with cubs keeps off the lions' ground, at the cost of
 * the game there (Durant 1998), and lions and wolves off the ground bands are about on: of all their killers they fear
 * people most (Zanette et al. 2023).
 */
private fun GroupPlanner.killedByStronger(c: PlanContext, p: Place, h: Hex): Float {
    val g = c.g; val s = g.species
    val mine = max(1f, gs.strength(g))
    val sheltered = gs.shelteredFromKilling(s, p)
    val young = if (g.young > 0) INTRAGUILD_KILL_YOUNG * gs.tuning.intraguildYoung * sheltered else 0f
    val grownOnly = if (g.adults > 0) INTRAGUILD_KILL * sheltered else 0f
    val grown = (1f - young) * grownOnly
    if (young + grown <= 0f) return 0f
    var killed = 0f
    for ((k, o) in gs.kinds.withIndex()) {
        if (o == s || residentStrength(o) <= 2f * mine) continue
        var heads = 0f; for (q in gs.hw.placesOf(h)) heads += knownHeads(k, Place(q), h, c.sensed)
        if (heads <= 0f) continue
        val met = 1f - exp(-gs.encounters(s, o, false, p, gs.days.toFloat()))
        // (People drive beasts off and now and then kill a grown one, but do not seek out their young: [enemiesMeet].)
        killed += heads / gs.unitSize(o) * met * (1f - gs.slipChance(s, o, p)) * (if (o.human) grownOnly else young + grown)
    }
    return killed
}

/**
 * How fed group [g] foresees it would be where it knows of [prey] (see [knownGame]) among [kinKm2] of its kind per km²
 * (itself counted; [crowded] by the one measure). A beast of prey by the residents' hunters' feeding rule: its kind
 * takes at most [HexFauna.RESIDENT_KILL_MOST] of the game in a week, shared by heads against their need. A band (whose
 * food is mostly gathered) still by how much there is against [PREY_HALF], less for crowding.
 */
@Part("choosing: foreseen fed")
internal fun GroupPlanner.foreseenFed(g: HexGroup, prey: Float, kinKm2: Float, crowded: Float): Float {
    val s = g.species
    if (s.human) return (prey / (prey + PREY_HALF) - 0.5f * crowded).coerceIn(0f, 1f)
    val needKg = s.dailyIntakeKg * g.warmth * gs.days * (g.adults + 0.5f * g.young) / max(1, g.size)
    return min(1f, HexFauna.RESIDENT_KILL_MOST * prey * Nourishment.PREY * s.needScale / max(1e-6f, kinKm2 * needKg))
}

/**
 * The hunters [c]'s group expects at place [p] (in hex [h]), kg per km². A band reads the beasts of prey about, not
 * other people: those it weighs as bands. Where it has not looked it expects hunters as thick as they usually are,
 * people among them.
 */
@Part("choosing: hunters about")
internal fun GroupPlanner.huntersKm2(c: PlanContext, p: Place, h: Hex) =
    if (h in c.sensed) (if (c.g.species.human) gs.beastKg else c.predatorKg)[p] / gs.land.areaKm2[p] else usualHuntersKm2

/**
 * The share of a group of [s] that drowns on the way from hex [here] to hex [to] along the cheapest chain ([via]):
 * each strait crossed, as [move] drowns it (the young twice as many, counted here as grown).
 */
@Part("choosing: crossing deaths")
internal fun GroupPlanner.crossingDeaths(s: Species, to: Hex, here: Hex, via: HexArray): Float {
    var risk = 0f; var h = to
    while (h != here) {
        val from = via[h]; if (from.none) break
        val gap = gs.seaGap(from, h)
        if (gap.isFinite()) risk += Hazards.drowning(s, gap)
        h = from
    }
    return min(0.9f, risk)
}

/** What place [p] is worth to hunter [g] as it knows it: [placeValue], and for a band the fish it could take there ([fishWorth]). */
@Part("choosing: known game")
internal fun GroupPlanner.knownGame(g: HexGroup, kind: Int, p: Place, h: Hex): Float = placeValue[kind][p] + (g.band?.let { fishWorth(g, it, h) } ?: 0f)

/** Heads of kind slot [k] in place [p] as a group knows them: seen in its own hex and the six around, else expected. */
internal fun GroupPlanner.knownHeads(k: Int, p: Place, h: Hex, sensed: HexArray): Float = if (h in sensed) gs.headsHere[k][p].toFloat() else expectHeads[k][p]

/**
 * The heads of its own kind per km² group [g] (of kind slot [kind]) foresees about it in hex [h]: its kind there as
 * it knows it ([knownHeads]) and itself, over the ground it would range from there (the hex, and the six round it
 * where its kind's home range reaches beyond one: [rangesWide]), as [liveWeek] counts its kin where it is.
 */
@Part("choosing: kin about")
internal fun GroupPlanner.kinAround(g: HexGroup, kind: Int, h: Hex, sensed: HexArray): Float {
    val s = g.species; val own = gs.hw.placeHex[g.place]
    var heads = 0f; var area = 0f
    for (d in -1 until (if (gs.rangesWide(s)) 6 else 0)) {
        val q = if (d < 0) h else gs.hex.neighbour(h, d)
        if (q.none) continue
        for (pp in gs.hw.placesOf(q)) { heads += knownHeads(kind, Place(pp), q, sensed); area += gs.land.areaKm2[pp] }
        if (q == own) heads -= g.size
    }
    return if (area > 0f) (max(0f, heads) + g.size) / area else 0f
}

@Part("choosing: terms of a place")
internal fun GroupPlanner.fillTerms(t: PlaceTerms, g: HexGroup, p: Place, weather: Weather, date: Date) {
    val s = g.species
    t.discomfort = gs.discomfort(s, p, weather)
    t.drain = gs.drain(g, p, weather)
    t.aheadC = s.discomfortC(weather.forecastTemp(p.i), date.month)
    t.waterKm = if (s.human) gs.land.drink.peopleWaterKm(p) else gs.land.drink.animalWaterKm(p)
    t.suitsLn = ln(gs.fauna.suits(s, p).coerceAtLeast(1e-3f))
}
