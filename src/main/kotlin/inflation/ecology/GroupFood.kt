package inflation.ecology

import inflation.Books
import inflation.Config
import inflation.EnumFloats
import inflation.HASH_A
import inflation.HASH_B
import inflation.HASH_C
import inflation.Kg
import inflation.KgM2
import inflation.Profile
import inflation.ecology.HexSmallLife.DeadPool
import inflation.enumFloats
import inflation.mix64
import inflation.unitOf
import inflation.world.Biome
import inflation.world.Date
import inflation.world.Hex
import inflation.world.HexDrinkingWater
import inflation.world.Place
import inflation.world.Weather
import inflation.world.get
import inflation.world.set
import java.util.stream.IntStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

// Hunting and foraging along the way: what a group gets, eats, stores and keeps of its week's food.

/** How catchable prey [ps] is for hunter [s] in place [p], 0..1, this week ([HexFauna.catchable], as resident hunters reckon it too). */
internal fun GroupWorld.catchable(s: Species, ps: Species, p: Place): Float = fauna.catchable(s, ps, ps.massKg, p)

/**
 * Hunters (and bands) feed along their way: the resident game of every place they passed, small game,
 * carrion, and for those that eat them plants, and for bands fish (see [forage]).
 */
internal fun HexGroups.hunt(weather: Weather) {
    val ways = this.ways
    val inputs = FloatArray(WAYS_IN); val out = FloatArray(WAYS_OUT)
    // (What a party killed this week before the last of it died lies where it fell: the meat of it, a beast's need being
    // counted in the whole weight of its kills, see [creditShare].)
    for (g in groups) if (!g.alive && g.meatMet > 0f) { small.deposit(DeadPool.CARRION, g.place, Kg(g.meatMet * meatShare(g.band) / creditShare(g.band))); g.meatMet = 0f }
    val hunters = groups.filter { it.alive }.shuffled(rnd)
    for (a in fauna.groupKilled) a.fill(0f)
    // Bands first set their ways for the week.
    // (Where the bands about it stand now, after this week's moves, as its own place is; and where beasts of prey may come on
    // their herds, see [herdOdds].)
    indexBands()
    // (Without a ways network the bands keep the scaffold's ways, not those a saved world's bands last had from theirs.)
    // (A planning band reads no ways: it keeps the scaffold's, [planDays].)
    if (ways != null) { for (g in hunters) g.band?.let { b -> if (b.mind == Mind.PLANS) b.scaffoldWays() else setWays(g, weather, ways, inputs, out) } }
    else for (g in hunters) g.band?.scaffoldWays()
    dialUse?.let { u -> for (g in hunters) if (g.band != null) u.bandWeeks.increment() }
    // (A band followed under the player's hand sets its ways every week, so the player's pins hold.)
    if (ways == null) controlledWays()?.let { w -> hunters.firstOrNull { it.id == watched && it.band != null }?.let { setWays(it, weather, w, inputs, out) } }
    // Then region by region on all cores: the map is cut into squares coloured like a chessboard of four
    // colours, so that squares of one colour lie a square apart; the squares of one colour are worked at
    // once. Parties whose week kept within half a square of where they ended it are worked first in squares
    // of [HUNT_FINE_KM] (two such parties in squares of one colour cannot have passed the same ground); the
    // few that ranged further after them, in squares of [HUNT_BLOCK_KM], wider than twice any party's week.
    fun work(parties: List<HexGroup>, squareKm: Float) {
        val blocks = HashMap<Int, ArrayList<HexGroup>>()
        for (g in parties) {
            val (x, y) = hex.centreKm(hw.placeHex[g.place])
            blocks.getOrPut((x / squareKm).toInt() + 1000 * (y / squareKm).toInt()) { ArrayList() } += g
        }
        for (colour in 0 until 4) {
            // (The squares with the most parties first, so the slowest is not left to last on one core.)
            val squares = blocks.entries.filter { (b, _) -> (b % 1000) % 2 + 2 * ((b / 1000) % 2) == colour }
                .sortedByDescending { e -> e.value.sumOf { g -> if (g.band != null) 4 * g.path.size else g.path.size } }
            IntStream.range(0, squares.size).parallel().forEach { i ->
                for (g in squares[i].value) {
                    val s = g.species
                    // The ground it searches this week, shared over the hexes it passed.
                    val sweep = searchKm2(g, g.place, weather) / g.path.size.coerceAtLeast(1)
                    Profile.part(if (s.human) "foraging: people" else "foraging: hunters") { forage(g, sweep, max(1, g.adults) * s.massKg, weather) }
                }
            }
        }
    }
    val (near, far) = hunters.partition { g ->
        val end = hw.placeHex[g.place]
        // (Its hunting ground reaches a hex beyond its way, see [huntGround].)
        g.path.all { h -> hex.distanceKm(end, h) + 2 * hex.hexKm < HUNT_FINE_KM / 2 }
    }
    work(near, HUNT_FINE_KM)
    work(far, HUNT_BLOCK_KM)
}

/**
 * The hands [g] forages with: its grown, less a band's scouts (who range ahead instead), lookouts and adults kept back
 * with the children beyond the usual.
 */
internal fun forageHands(g: HexGroup): Int {
    val band = g.band
    return forageHands(g, band?.scoutShare ?: 0f, band?.childWatch ?: 1f)
}

/** [forageHands] with scouts [scouts] (a share of its adults) and its way of watching its children [watch]. */
private fun forageHands(g: HexGroup, scouts: Float, watch: Float): Int = max(1, (g.adults * (g.band?.ableShare ?: 1f) * (1f - scouts) *
    (1f - CHILD_WATCH_HANDS * (watch - 1f)).coerceIn(0.5f, 1.2f)).toInt())

/** Counts in [u] how band [b]'s scouts and its watching its children set the [hands] it forages with ([forageHands]). */
private fun countHands(u: DialUse, g: HexGroup, b: Band, hands: Int) {
    val watch = b.childWatch
    u.amount(DialSite.SCOUTS_HANDS, b.will[BandWay.SCOUTS], hands.toFloat(), forageHands(g, SCOUT_SHARE, watch).toFloat())
    val raw = 1f - CHILD_WATCH_HANDS * (watch - 1f)
    u.amount(DialSite.CHILDREN_HANDS, watch, hands.toFloat(), forageHands(g, b.scoutShare, 1f).toFloat(), cut = raw < 0.5f || raw > 1.2f)
}

/**
 * A party's week, as optimal foraging: along its way it meets big game, small game, carrion (if it
 * eats it), plant food (if it eats plants) and, for bands, fish, each at a rate set by how much there
 * is, how much ground it searches for that kind of food, and how likely a meeting ends in a meal (its
 * size, speed, stalking and the cover against the prey's size, speed, weapons and refuge). Each food
 * is worth its kilos over the time it takes to catch, gather or dig, prepare and eat. The party takes
 * the most rewarding foods first and adds poorer ones only while they raise its rate of intake
 * (Charnov's diet model), until it is fed. A band forages as two parties side by side, its hunters after big game
 * and the rest after everything else, each by its own diet and its own time. So who eats whom, and how much of each, comes from the
 * animals' traits and what is about. A band's tools and skills enter only as what they change: a
 * spear or bow the odds of a kill, snares and nets the ground searched, sickles and grinding stones
 * the time seed takes, baskets the time to carry a harvest home.
 */
internal fun HexGroups.forage(g: HexGroup, sweep: Float, packKg: Float, weather: Weather) {
    val w = ForageWeek(this, g, sweep, packKg, weather)
    huntingOdds(w)
    if (w.band == null) herdOdds(w)
    val o = Offers(w.games + w.herds.size + g.path.size * 4 + (w.gatherAt.size + w.roundCamp.size) * 6 + 2)
    // The party plans its week on what it finds, eats, and if others got there first and it is still
    // hungry, looks again at what is left.
    for (pass in 0 until FORAGE_PASSES) {
        val left = w.aim - w.fed
        if (left <= 0.01f * w.wantAll) break
        offerFoods(w, o)
        o.takeUp()
        if (HUNT_DIAG && pass == 0 && w.band == null && w.herds.isNotEmpty()) {
            val cut = o.gain[0] / o.spent[0]
            for (i in 0 until o.m) if (o.herd[i] >= 0) { diag(g.species, HuntStep.HERDS_ON_OFFER, 1f)
                diag(g.species, HuntStep.HERD_PAY_OVER_CUTOFF, if (cut > 0f) o.profit[i] / cut else 0f) }
        }
        if (pass == 0 && w.band != null) { w.fishPull = max(o.pull(Food.FISH), o.pull(Food.FISH, RUN_KIND)); w.weirPull = o.pullAt(w.weirProbe) }
        note(g) {
            "pass %d: took up %d of %d foods, best first: ".format(pass + 1, o.used, o.m) + (0 until min(o.used, 6)).joinToString(", ") { r -> val i = o.order[r]
                (if (o.type[i] == Food.BIG_GAME) fauna.kinds[o.kind[i]].name else o.type[i].label) + " %.1f kg/day of work".format(o.profit[i]) }
        }
        // What the week brings at those rates, the parties working side by side, as far as the band wants: it stops when fed.
        val intake = o.fed[0] / o.spent[0] + o.fed[1] / o.spent[1]
        // (A band plans its week once it sees what food asks of it: see [planWeek]. It forages no longer than the plan gives it.)
        if (w.band != null && w.time == null) {
            // (What its herds will feed it this week it need not forage for: their increase and milk, see [herdFoodWeek].)
            val need = max(0f, w.wantAll - w.fed - herdFoodWeek(g, w.band, weather.date))
            fun daysFor(kg: Float) = if (intake > 0f) min(days.toFloat(), kg / intake) else 0f
            planWeek(w, daysFor(need), daysFor(left) - daysFor(need), if (intake > 0f) intake / w.hands.coerceAtLeast(1) else 0f)
        }
        val time = w.time
        var f = if (intake > 0f) min(1f, left / (w.workDays * intake)) else 0f
        if (time != null) f = min(f, time.leftFor(Activity.FOOD) / (w.hands * w.workDays).coerceAtLeast(1e-6f))
        val busy0 = w.busy
        if (pass == 0) w.searched = f
        if (FORAGE_DIAG == w.s.name && diagSampled(g, weather, 0.002f, 1)) System.err.println(
            "forage ${w.s.name} adults ${g.adults} want ${w.wantAll} left $left offers ${o.m} used ${o.used} gain ${o.gain.toList()} spent ${o.spent.toList()} f $f: " +
            o.order.take(min(6, o.m)).joinToString("; ") { "t${o.type[it].ordinal} k${o.kind[it]} r=${o.rate[it]} e=${o.kg[it]} h=${o.time[it]}" })
        takeFoods(w, o, f)
        time?.spend(Activity.FOOD, (w.busy - busy0) * w.hands)
    }
    if (w.band != null && w.time == null) planWeek(w, 0f, 0f, 0f)
    // (What a day's foraging brought it this week, kg of its need, kept month by month as a mean over the years: what a
    // herder's day must bring to be worth it, [herdPays].)
    w.band?.let { b -> w.time?.spentOn(Activity.FOOD)?.takeIf { it > 0f }?.let { d ->
        val got = w.fed / d; val m = weather.date.month; val was = b.forageReturn[m]
        b.forageReturn[m] = if (was < 0f) got else was + min(1f, years * Date.MONTHS_PER_YEAR) * (got - was)
    } }
    if (WARM_DIAG) w.band?.let { b -> w.time?.let { b.diagFoodSpent = it.spentOn(Activity.FOOD) } }
    w.time?.let { countFoodTime(w, it) }
    w.time?.let { restedDays(w, it) }
    w.time?.release(Activity.FOOD)
    if (HUNT_DIAG && w.band == null) packWeekDiag(w)
    eatTheWeek(w)
}

/** Diagnostic (huntSteps): how a beast pack's week of foraging went, by the hunter ([HuntStep.PACK_WEEKS] and on). */
private fun HexGroups.packWeekDiag(w: ForageWeek) {
    val s = w.g.species
    diag(s, HuntStep.PACK_WEEKS, 1f); diag(s, HuntStep.PACK_WORK_DAYS, w.workDays); diag(s, HuntStep.PACK_BUSY_DAYS, w.busy)
    diag(s, HuntStep.PACK_EATING_DAYS, (w.meat - w.g.meatMet + w.killFat + w.smallGot + w.scraps) * w.eats)
    if (w.wantAll > 0f) diag(s, HuntStep.PACK_FED, w.fed / w.wantAll)
    if (w.fed >= w.aim - 0.01f * w.wantAll) diag(s, HuntStep.PACK_WEEKS_FULL, 1f)
    else if (w.busy >= 0.95f * w.workDays) diag(s, HuntStep.PACK_WEEKS_OUT_OF_TIME, 1f)
}

/**
 * What [w]'s band need not eat this week for the days it does not forage: once foraging has taken the days it took (from
 * its own and from those nobody asked for), the days the plan keeps for lighter work, and the days left to no one, spent
 * at rest, take less food than days foraging; fetching wood and materials, more ([Activity.restSaves]). Its want falls by
 * that; what it forages for does not (it learns how hard its week was only as it goes).
 */
private fun restedDays(w: ForageWeek, time: BandTime) {
    // (The working time lost to the cold and hunger, in what was spent and in what is held, is spent idle, as rest: see [Sisu].)
    var savedDays = (time.unheld + time.heldFor(Activity.FOOD) + time.lost) * Activity.LEISURE.restSaves
    for (a in Activity.entries) if (a != Activity.FOOD) {
        val idle = time.heldFor(a) * (1f - time.pace(a))
        savedDays += (time.heldFor(a) - idle) * a.restSaves + idle * Activity.LEISURE.restSaves
    }
    w.wantAll -= savedDays * w.s.dailyIntakeKg
}

/**
 * A band's plan of its week, made once [forage] sees what food asks of it: [needDays] of its hands' week to eat what it
 * wants this week, [storeDays] more to lay by what it aims to keep. The walk, when it moved camp ([moveDays]), and digging
 * soaks for water, where that is the only water, come off first. Then the days the hearth's firewood and the camp's
 * shelters take to keep its people warm ([survivalDays]), then the week's food, then its herders' days; then the rest of what each activity asks for ([choreAsks]: the rest of the week's food, the stores and its chores),
 * shared out when it comes to more than the days left ([BandTime.plan]). Leisure asks for nothing: it is what the week
 * leaves (and takes less food: [restedDays]). Then it fetches the hearth's firewood, puts up its shelters and herds, in the days the plan gives them.
 */
private fun HexGroups.planWeek(w: ForageWeek, needDays: Float, storeDays: Float, perDay: Float) {
    val g = w.g; val b = checkNotNull(w.band); val hands = w.hands
    // (Its work goes the slower, the colder and hungrier its people: see [Sisu].)
    val time = BandTime(days * hands.toFloat(), b.paces())
    // (Its water: carried home, dug for in a soak or drawn from a well ([waterShare]); counted with the food.)
    val fetch = w.s.dailyTravelKm; val tempC = w.weather.temperature[g.place]; val perHand = g.size.toFloat() / hands
    w.digDays = waterShare(g.place, fetch, waterLitres(tempC), perHand) * days * hands
    time.spend(Activity.FOOD, w.digDays)
    // (What of its days a well at home would have spared it, for its wells: [Band.waterShort].)
    if (b.atHome) b.waterShort += (wellSpares(g.place, fetch, tempC, perHand) - b.waterShort) * min(1f, years)
    // (Moving camp takes its days first: everyone walks, at the pace of the slowest, see [moveDays].)
    time.spend(Activity.MOVING, min(days.toFloat(), moveDays(g, max(0, g.path.size - 1) * hex.hexKm.toFloat())) * hands)
    // (Then the days that keep its people alive this week and through the year after, by its own foresight ([survivalDays]):
    // food, the hearth's firewood and the camp's shelters, each as far as it averts deaths for its days; a band that has
    // walked most of its week gives what is left where it does most.)
    val fuel = fuelAsk(g, hands)
    val foodAsk = needDays * hands
    val warm = survivalDays(g, b, w.weather, time, hands, foodAsk, if (w.wantAll > 0f) (w.fed / w.wantAll).coerceIn(0f, 1f) else 1f, movedCamp(g, b))
    val needs = enumFloats<Activity>()
    // (The rest of a whole cooking fire's firewood shares the week with the food, as it always did: raw meals feed less,
    // [RAW_COST]. More wood than that only warms, and the survival pass weighs it.)
    needs[Activity.FOOD] = foodAsk
    needs[Activity.FIREWOOD] = max(0f, fuel - warm[Activity.FIREWOOD])
    // (Its herders' days, as far as the band does not walk with its herd this week, are held after the week's food and
    // before every other ask: what it cannot herd it culls and eats ([keepHerds]). Shared out with the asks, they were cut
    // whenever foraging alone would fill the week, and the cuts compounded week on week till no herd was left; held with
    // the fire and shelters, a band that shrank kept its whole herd, which took its firewood and food till it died.)
    val kept = enumFloats<Activity>()
    kept[Activity.SOWING] = herdDays(b) * (1f - walkedShare(time))
    val asks = enumFloats<Activity>()
    asks[Activity.FOOD] = storeDays * hands
    choreAsks(g, b, w.weather.date, asks, warm)
    // (The band weighs the rest of the asks against each other by its ways: see [timeWay]. A planning band holds its days by
    // its customs, the lives they save and what is left: [planDays].)
    if (b.mind == Mind.PLANS) {
        time.plan(needs, planDays(g, b, w.weather, time, needs, asks, warm, kept, perDay), null, warm, kept)
        // (What each chore asked, not what it was given, for the census of the week's squeeze: [countSqueeze].)
        for (a in Activity.entries) if (a != Activity.LEISURE) time.asked[a] = needs[a] + asks[a] + warm[a] + kept[a]
    } else time.plan(needs, asks, enumFloats<Activity> { a -> timeWay(a)?.let { b.will[it] } ?: 1f }, warm, kept)
    w.room = time.leftFor(Activity.FOOD) / time.pace(Activity.FOOD)
    // (What its fire, shelters and food leave of the week, kept month by month as a mean over the years: what its
    // leanest month could give its herders, [keepHerds].)
    run {
        val free = if (time.total > 0f) max(0f, time.left - time.vital) / time.total else 0f
        val m = w.weather.date.month; val was = b.freeShare[m]
        b.freeShare[m] = if (was < 0f) free else was + min(1f, years * Date.MONTHS_PER_YEAR) * (free - was)
    }
    if (WARM_DIAG) b.diagFoodHeld = time.heldFor(Activity.FOOD)
    b.binding?.let { bind ->
        if (time.short) for (a in Activity.entries) if (asks[a] > 0f) bind[a.ordinal] += 1f
        if (g.path.size > 1) bind[Binding.MOVES] += 1f
    }
    dialUse?.let { u -> for (a in Activity.entries) timeWay(a)?.let { way ->
        if (asks[a] > 0f) u.amount(DialSite.TIME_WEIGHTS, b.will[way], time.heldFor(a), time.heldAtOneFor(a), dial = way.slot) } }
    note(g) {
        "plan: %.1f person-days, moving %.1f; food asked %.1f; first for warmth: firewood %.1f of %.1f, shelter %.1f; held food %.1f, firewood %.1f, shelter %.1f; stores asked %.1f; short %s".format(
            time.total, time.spentOn(Activity.MOVING), foodAsk, warm[Activity.FIREWOOD], fuel,
            warm[Activity.SHELTER], time.heldFor(Activity.FOOD), time.heldFor(Activity.FIREWOOD), time.heldFor(Activity.SHELTER),
            storeDays * hands, time.short) + run {
            val air = airAt(g.place, w.weather); val home = b.homeShelter()
            val unlit = b.warmth(air, 0f, home, days); val lit = b.warmth(air, 1f, home, days)
            "; exposure odds without a fire %.2g, with a full one %.2g (%.1f °C, shelter %.2f, fire now %.2f)".format(unlit.odds, lit.odds, air.meanC, b.shelter, b.fire)
        }
    }
    w.fuelDays = fetchFuel(g, b, hands, min(time.leftFor(Activity.FIREWOOD), max(fuel, warm[Activity.FIREWOOD])))
    time.spend(Activity.FIREWOOD, w.fuelDays * hands)
    time.release(Activity.FIREWOOD)
    // (It puts up its camp's shelters in the days the plan gives them, before the foraging can eat into them: [putUpShelter].)
    putUpShelter(b, g.adults.toFloat(), time, movedCamp(g, b), max(b.shelterWill, b.shelterAim))
    // (And makes the clothes the plan gives days first, before the foraging can eat into them: [survivalDays].)
    if (warm[Activity.TOOLS] > 0f) makeTools(b, g.adults.toFloat(), time, Tool.CLOTHES, warm[Activity.TOOLS])
    // (And its herders go out with the herd in the days the plan gives them, before the foraging can eat into them.)
    val herd = herdDays(b) * (1f - walkedShare(time))
    b.herded = if (herd > 0f) min(1f, time.leftFor(Activity.SOWING) / herd) else 1f
    if (herd > 0f) time.spend(Activity.SOWING, herd * b.herded)
    w.workDays = days - w.fuelDays
    w.time = time
}

/**
 * A party's week of [forage]: what it brings to it, worked out once (its hands, its want, how fast it handles each
 * kind of food, where it gathers), the odds of a kill of each kind on its way, and what it has got so far.
 */
private class ForageWeek(gs: HexGroups, val g: HexGroup, val sweep: Float, packKg: Float, val weather: Weather) {
    val s = g.species
    val d = s.diet
    val band = g.band
    // (Its own stream of chance, so that regions can be worked at once.)
    val rnd = kotlin.random.Random(g.id * 1_000_003L + weather.date.absoluteDay)
    val hands = forageHands(g).also { h -> gs.dialUse?.let { u -> band?.let { b -> countHands(u, g, b, h) } } }
    // A band splits: its hunters go after big game, searching all the days they are out, while the rest
    // gather, fish and snare (see [HUNTERS_SHARE]); an animal's party forages as one.
    val hunt = band?.let { b -> huntersShare(b).also { v -> gs.dialUse?.amount(DialSite.HUNTERS_SHARE, b.effort[Food.BIG_GAME], v,
        HUNTERS_SHARE.coerceIn(0f, 1f), cut = HUNTERS_SHARE * b.effort[Food.BIG_GAME] > 1f) } } ?: 1f
    val gatherHands = if (band != null) hands * (1f - hunt) else hands.toFloat()
    // (Walking costs food, see [walkCost]; a band's days at rest take less, see [restedDays].)
    var wantAll = g.dailyNeedKg * gs.days * (1f + gs.walkCost(g))
    val eats = 1f / (packKg * EATING_RATE) // days to eat a kilo
    // Small game is found within a few paces of the way, not as far as the party can see; snare
    // lines and nets search it while no one is there.
    val smallSweep = s.dailyTravelKm * gs.days * 2f * SMALL_SENSE_KM * sqrt(gatherHands / s.groupSize) / g.path.size.coerceAtLeast(1) * (band?.snares() ?: 1f)
    val smallCatch = smallCatch(s)
    val smallDays = SMALL_HANDLING_DAYS_PER_KG / gatherHands * (band?.slowness(Skill.SMALL_GAME) ?: 1f) + eats
    // Plant food: picked, dug or reaped by all hands, and carried home (baskets carry more at a time).
    val eatsPlants = d.fruit + d.roots > 0f
    val carry = 1f + BASKET_GAIN * (band?.has(Tool.BASKET) ?: 0f)
    // (A band's children gather too, near camp and slower than grown hands: fruit, roots, small finds and seed.)
    val gatherers = gatherHands + (if (band != null) CHILD_GATHER * g.young else 0f)
    // (A band renders bones' grease where it gathers them: see [greaseOf].)
    val renderDays = if (band != null) BONE_RENDER_DAYS / gatherers.coerceAtLeast(1e-3f) else 0f
    val pickDays = PLANT_HANDLING_DAYS_PER_KG * gs.tuning.plantHandling / gatherers / carry + eats
    // (People pick fruit a berry at a time, at the record's rate: see [BAND_FRUIT_DAYS_PER_KG].)
    val fruitDays = if (band != null) BAND_FRUIT_DAYS_PER_KG * gs.tuning.plantHandling / gatherers / carry + eats else pickDays
    val seedDays = reapDaysPerKg(band, gs.tuning) / gatherers / carry + eats
    // (A band's gatherers walk to the food and carry it home: party-days a km of the way out costs a fresh kg brought
    // home, there and back; see [GATHER_LOAD_KG]. An animal eats where it finds.)
    val walkDaysPerKm = if (band != null) 2f / (WALK_KM_PER_HOUR * Activity.WORK_HOURS * GATHER_LOAD_KG * carry * gatherers) else 0f
    // Bands gather over all the ground they walked, the women's and children's day round; animals where they end the week.
    val gatherAt = if (band != null) g.path.toList().mapNotNull { h: Hex -> gs.hw.placeNear(g.place, h).takeIf { !it.none }?.i }
        .ifEmpty { listOf(g.place.i) } else listOf(g.place.i)
    // A band's gatherers also work the country round its camp, the further the more: a camp where
    // woodland, valley and plain meet has more kinds of food within a day's walk than one in the
    // middle of any one of them. (Each hex round gives a share of what the camp's own does.)
    val roundCamp: List<Int> = if (band != null) { val h0 = gs.hw.placeHex[g.place]
        (0 until 6).mapNotNull { d ->
            gs.hex.neighbour(h0, d).takeIf { !it.none }?.let { gs.hw.placeNear(g.place, it) }?.takeIf { !it.none && it.i !in gatherAt }?.i
        } } else emptyList()
    val roundShare = (band?.let { b -> (RING_REACH * b.gatherRange).also { v -> gs.dialUse?.amount(DialSite.GATHER_RING, b.gatherRange, v, RING_REACH) } } ?: 0f)
    // (Which of the band's parties takes each food, [Offers.who]: its share of the hands.)
    val party = floatArrayOf(hunt, 1f - hunt)
    // What a kg of each food holds for it, in kg of its need: its energy ([Food.energy]) by the kind's [Species.needScale]
    // (a band's bones, the grease it renders of them).
    private val worths = enumFloats<Food> { t ->
        s.needScale * when {
            band == null -> if (t == Food.BIG_GAME) Nourishment.PREY else t.energy
            t == Food.BONES -> greaseOf(band) * Nourishment.GREASE
            else -> t.energy
        } }
    /** What a kg of [t] holds for it, in kg of its need. */
    fun worth(t: Food) = worths[t]
    // What it reckons a kg of each feeds it: a band, lean food less the share of its protein that went to waste of late
    // ([Band.leanLost]: protein feeds people only so far, see [Meal]).
    private val feedsBy = enumFloats<Food> { t ->
        worths[t] * (if (band != null) 1f - band.leanLost * t.protein else 1f) }
    /** What it reckons a kg of [t] feeds it, in kg of its need. */
    fun feeds(t: Food) = feedsBy[t]
    // What it has got: meat (the meat it took in its meetings with it), small game, carrion and bones, plants, fish; and what
    // all of it feeds, in kg of its need (see [worth]).
    var meat = g.meatMet; var smallGot = 0f; var scraps = 0f; var plants = 0f; var fish = 0f
    // (The fat of its kills, kg, apart from the lean [meat]; and what a kg of it feeds, as grease: see [fatOfKill].)
    var killFat = 0f
    val fatFeeds = s.needScale * Nourishment.GREASE
    // (Of the fish, what was caught rather than gathered off the shore: that alone is practice at fishing.)
    var fished = 0f
    // (How fish paid against the gatherers' least paying food taken up, the first pass: see [Offers.pull]. And what fish from a
    // whole weir across the river by the camp would pay as it weighs them, a day of handling, and how that pulled: see
    // [weirAsk].)
    var fishPull = 0f
    var weirProbe = 0f; var weirPull = 0f
    // (A band's grease rendered from bones, kg, and what it feeds.)
    var grease = 0f; var greaseFed = 0f
    var fed = g.meatMet * feeds(Food.BIG_GAME)
    // A band also works to fill its stores towards what it aims to keep (its choice); party-days spent this week.
    // (A band holding to demand sharing stores less: what it lays by is asked of it.)
    val aim = wantAll + (band?.let { b -> gs.storeWork(g, b).also { v -> gs.dialUse?.let { u -> countStoreWork(u, g, b, v) } } } ?: 0f)
    // What it weighs a kg of each food at in its choice: what it feeds, the part of the week's work that is for the stores
    // weighed by its will to lay that food by (its choice, [Band.storeWill]: what keeps, how long, and against what
    // lean season is for it to learn).
    private val weights = enumFloats<Food> { t ->
        val toStore = if (band != null && aim > wantAll) (aim - wantAll) / aim else 0f
        feedsBy[t] * (1f - toStore + toStore * (band?.storeWill(t) ?: 1f)) }
    /** What it weighs a kg of [t] at in choosing its foods ([Offers.takeUp]). */
    fun weigh(t: Food) = weights[t]
    init {
        val u = gs.dialUse
        if (u != null && band != null && aim > wantAll) for (t in Food.entries)
            u.amount(DialSite.STORING, band.storeWill(t), weights[t], worths[t], dial = storeWay(t).slot)
    }
    val plantKind = enumFloats<Store>()
    // (The same by the plant food it came from, for the census.)
    val plantFrom = enumFloats<PlantFrom>()
    // (And what it weighs, which grinding, cooking and leaching do not change: see [addFood].)
    val plantKg = enumFloats<Store>()
    var busy = 0f
    /**
     * For the census of foraging's days against its plan ([countFoodTime]), days of a hand: what kills beyond and short of
     * those expected took and saved, and what hauling took beyond the take itself; and the people-days digging soaks took,
     * and those the plan left foraging (its hold and what no one held).
     */
    var killOver = 0f
    var killUnder = 0f
    var haulOver = 0f
    var digDays = 0f
    var room = 0f
    // A band fetches the hearth's firewood first and forages in the days left, as far as its week's plan gives them
    // (see [planWeek]); an animal forages all week.
    var fuelDays = 0f
    var workDays = gs.days.toFloat()
    /** A band's week's time, once planned ([planWeek]). */
    var time: BandTime? = null
    val spareYoung = band?.holds(Tradition.TABOO_YOUNG) == true
    // The kind its hunters leave alone, its totem: unless the band went hungry last week, when it hunts that too, and the
    // bands of its totem who meet it come to know it ([Band.totemBroken]). (Its resident kind's index; -1 for none.)
    val avoid = if (band != null && g.fed >= Thresholds.HUNGRY) band.totem.i else -1
    // The ground its hunters search for game this week, and how much of each place (see [huntGround]).
    val ground = gs.huntGround(g, sweep * g.path.size.coerceAtLeast(1) * sqrt(hunt))
    // The odds a meeting with each kind ends in a kill, in each place of that ground (they do not change within the week).
    val nk = gs.fauna.kinds.size
    val odds = FloatArray(ground.size * nk)
    // (And the stalks a kill takes, on average: one over the share of them that end in one.)
    val stalks = FloatArray(ground.size * nk)
    // (And the bands of each kind it comes on for each animal there, unseen: for its search image.)
    val met = FloatArray(ground.size * nk)
    // (The game worth stalking in each place of the ground, the kinds with odds of a kill in the order they are offered:
    // the [j]th place's are [gameAt] from [gameFrom] j to [gameFrom] j + 1, each its index into [odds].)
    val gameFrom = IntArray(ground.size + 1); val gameAt = IntArray(ground.size * nk); var games = 0
    // (A beast's: the herds kept at bands' camps on its ground that it may come on, see [herdOdds].)
    val herds = ArrayList<HerdGame>()
    val today = weather.date.absoluteDay.toDouble()
    // (The share of the week it searched before it was fed.)
    var searched = 0f
    val haul = Haul()
}

/**
 * The ground hunting party [g] searches for game this week with [km2] of search (see [searchKm2]): the places on its
 * way, each its share of the search as far as the place goes; and where a place is too small for its share (a lion's
 * week sweeps several hexes' worth), the rest spread evenly over the places it can cross into about its way, each as
 * far as it goes. (A party ranges round where it goes, not only through it; what is left over after that is the same
 * ground searched again, which finds no more game.) The places nearest in height to its own.
 */
internal fun GroupWorld.huntGround(g: HexGroup, km2: Float): HuntGround {
    val path = g.path; val area = land.areaKm2
    var places = IntArray(path.size + 6); var swept = FloatArray(path.size + 6); var m = 0
    fun indexOf(p: Int): Int { for (i in 0 until m) if (places[i] == p) return i; return -1 }
    fun add(p: Int, s: Float) {
        if (m == places.size) { places = places.copyOf(2 * m); swept = swept.copyOf(2 * m) }
        places[m] = p; swept[m] = s; m++
    }
    val share = km2 / path.size.coerceAtLeast(1)
    var over = 0f
    for (h in path) {
        val p = hw.placeNear(g.place, h).takeIf { !it.none } ?: continue
        val i = indexOf(p.i)
        val had = if (i >= 0) swept[i] else 0f
        val s = min(area[p], had + share)
        over += had + share - s
        if (i >= 0) swept[i] = s else add(p.i, s)
    }
    val onWay = m
    if (over > 0f) {
        val cross = hex.crossings[Catalogue.travel(g.species).ordinal]
        for (h in path) for (d in 0 until 6) {
            val nh = hex.neighbour(h, d); if (nh.none || !cross[hex.edge(h, d)].isFinite() || nh in path) continue
            val q = hw.placeNear(g.place, nh).takeIf { !it.none } ?: continue
            if (indexOf(q.i) < 0) add(q.i, 0f)
        }
        // (Evenly over the places about, those too small for their share filled and the rest shared again among the others.)
        var open = m - onWay
        while (over > 1e-3f && open > 0) {
            val each = over / open; open = 0
            for (i in onWay until m) {
                val room = area[places[i]] - swept[i]; if (room <= 0f) continue
                val s = min(room, each); swept[i] += s; over -= s
                if (s < room) open++
            }
        }
    }
    return HuntGround(places.copyOf(m), swept.copyOf(m))
}

/** The places of a hunting party's week's ground ([huntGround]), and the km² of each it searches. */
internal class HuntGround(val place: IntArray, val km2: FloatArray) { val size get() = place.size }

/** A herd of kind [k] kept by band [g] at the [j]th place of a beast's hunting ground: the odds per head it comes on it and kills, and the stalks a kill takes ([herdOdds]). */
private class HerdGame(val g: HexGroup, val k: Int, val j: Int, val odds: Float, val stalks: Float, val young: Boolean)

/**
 * [forage]'s odds that a beast of prey comes on and kills an animal of the herds kept at bands' camps on its hunting ground,
 * into [ForageWeek.herds]: as it would one of their kind in the wild there ([huntingOdds]), the herd come on as one band of
 * all its heads (it is kept together), and through the band's guard against beasts ([stalkGuard]: by night its fire, dogs,
 * watch and palisade, by day its dogs; its houses keep its people, not its stock).
 */
private fun HexGroups.herdOdds(w: ForageWeek) {
    val s = w.g.species; val view = FloatArray(2)
    for (j in 0 until w.ground.size) {
        val p = Place(w.ground.place[j])
        val camps = bands.bandIndex[hw.placeHex[p]] ?: continue
        var looked = false
        for (c in camps) {
            val b = c.band ?: continue
            if (c.place != p || !c.alive) continue
            val guard = stalkGuard(b, 0f, b.fencedNow())
            val young = b.youngHerd()
            for (k in b.herd.indices) {
                val all = b.herd[k] + young[k]; if (all < 1f) continue
                val ps = fauna.kinds[k]
                // (The grown and the young, each as catchable as their weight makes them ([HexFauna.catchable]).)
                for (y in 0..1) {
                    val n = if (y == 1) young[k] else b.herd[k]; if (n <= 0f) continue
                    val success = killChance(fauna.catchable(s, ps, if (y == 1) HexFauna.YOUNG_WEIGHT * ps.massKg else ps.massKg, p), 1f) * guard
                    if (!(success > 0f)) continue
                    if (!looked) { openness(s, p, w.weather, view); looked = true }
                    val image = w.g.metLately(ps.index, w.today).let { it / (it + SEARCH_HALF) }
                    val odds = success * (0.5f + 0.5f * Niches.overlap(s, ps)) * (1f - seenFirst(view[0], view[1], null, ps)) / all * image
                    if (odds > 0f) w.herds += HerdGame(c, k, j, odds, 1f / success.coerceIn(1e-3f, 1f), y == 1)
                    if (HUNT_DIAG) { val swept = w.ground.km2[j] / land.areaKm2[p]
                        diag(s, HuntStep.HERDS_MET, swept); diag(s, HuntStep.HERD_KILLS_ON_OFFER, n * swept * odds); diag(s, HuntStep.HERD_GUARD, guard) }
                }
            }
        }
    }
    if (HUNT_DIAG && w.herds.isNotEmpty()) diag(s, HuntStep.HERD_WEEKS, 1f)
}

/**
 * [forage]'s odds that [w]'s party comes on and kills each kind present in each place of its hunting ground, per animal
 * there, into [ForageWeek.odds]; the stalks a kill takes into [ForageWeek.stalks], and the bands come on unseen per
 * animal into [ForageWeek.met]; and the game worth stalking there. Prey resting while the hunter is about lie up in
 * cover and are found less; a band's weapons, dogs and hunting skill change the odds that a stalk ends in a kill
 * ([killChance]). Residents go about in their kind's usual bands: a band is come on as one, and may see the hunters
 * first and keep out of their way ([seenFirst]); one try at it, for a band chased scatters. And game it seldom comes on
 * it does not look for (prey switching, see [SEARCH_HALF]). (What depends on the kind alone is reckoned once a kind,
 * what depends on the place alone once a place.)
 */
private fun GroupWorld.huntingOdds(w: ForageWeek) {
    val g = w.g; val s = g.species; val band = g.band; val nk = w.nk
    val adults = fauna.adults; val young = fauna.young
    val edge = FloatArray(nk); val image = FloatArray(nk); val meets = FloatArray(nk); val view = FloatArray(2)
    for (k in 0 until nk) {
        val ps = fauna.kinds[k]
        edge[k] = band?.huntEdge(ps, tuning) ?: 1f
        image[k] = g.metLately(ps.index, w.today).let { it / (it + SEARCH_HALF) }
        meets[k] = 0.5f + 0.5f * Niches.overlap(s, ps)
    }
    for (j in 0 until w.ground.size) {
        w.gameFrom[j] = w.games
        val p = w.ground.place[j]
        openness(s, Place(p), w.weather, view)
        for (k in 0 until nk) {
            if (adults[k][p] + young[k][p] <= 0f) continue
            val ps = fauna.kinds[k]; val i = j * nk + k
            val success = killChance(catchable(s, ps, Place(p)), edge[k])
            w.met[i] = meets[k] * (1f - seenFirst(view[0], view[1], band, ps)) / ps.groupSize
            w.odds[i] = success * w.met[i] * image[k]
            w.stalks[i] = 1f / success.coerceIn(1e-3f, 1f)
            if (!(w.odds[i] <= 0f) && k != w.avoid) w.gameAt[w.games++] = i
        }
    }
    w.gameFrom[w.ground.size] = w.games
}

/** The foods [forage] offers a party in one pass, and its pick of them. */
private class Offers(n: Int) {
    // Each food: its [Food] (big game of [kinds] index k, or another food), place, rate a day, kg each, what each feeds
    // (kg of the party's need, see [ForageWeek.worth]), what the party weighs it at ([ForageWeek.weigh]), days each.
    val type = Array(n) { Food.BIG_GAME }; val kind = IntArray(n); val place = IntArray(n)
    val rate = FloatArray(n); val kg = FloatArray(n); val value = FloatArray(n); val weight = FloatArray(n); val time = FloatArray(n)
    // (Which of the band's parties takes each food: 0 the hunters, 1 the gatherers; an animal's is all 0.)
    val who = IntArray(n)
    // (For a beast, the herd in [ForageWeek.herds] each big game is of; -1 for game in the wild.)
    val herd = IntArray(n)
    var m = 0
    val order = IntArray(n); val profit = FloatArray(n); val sortKeys = LongArray(n)
    // What each party gains (as it weighs it, and what it feeds) and the time it spends on the foods it takes up, and
    // whether it wants no more; the foods taken up are the first [used] of [order].
    val gain = FloatArray(2); val fed = FloatArray(2); val spent = FloatArray(2); val full = BooleanArray(2); var used = 0

    fun add(t: Food, k: Int, p: Place, r: Float, e: Float, v: Float, wt: Float, h: Float, w: Int, hd: Int = -1) {
        type[m] = t; kind[m] = k; place[m] = p.i; rate[m] = r; kg[m] = e; value[m] = v; weight[m] = wt; time[m] = h; who[m] = w; herd[m] = hd; m++
    }

    /**
     * Takes up the foods best first by what the party weighs them at a day of handling while they raise each party's own
     * rate (Charnov's rule).
     * (Foods worth the same in the order they were offered in. They are drawn best first from a heap, as far as the
     * parties go: each stops at the first food not worth its while, so most are never ranked.)
     */
    fun takeUp() {
        for (i in 0 until m) profit[i] = weight[i] / time[i]
        rankKeys(profit, m, sortKeys)
        heapify(sortKeys, m)
        gain.fill(0f); fed.fill(0f); spent.fill(1f); full.fill(false); used = 0
        // (A party with no food on offer is as good as done.)
        var hunters = 0; for (i in 0 until m) if (who[i] == 0) hunters++
        if (hunters == 0) full[0] = true
        if (hunters == m) full[1] = true
        var left = m
        while (left > 0 && !(full[0] && full[1])) {
            val i = (popMin(sortKeys, left--) and 0xffffffffL).toInt(); val w = who[i]
            if (full[w]) continue
            if (profit[i] <= gain[w] / spent[w]) { full[w] = true; continue }
            gain[w] += rate[i] * weight[i]; fed[w] += rate[i] * value[i]; spent[w] += rate[i] * time[i]; order[used++] = i
        }
    }

    /**
     * How the gatherers' [t] (of kind [k]) paid after [takeUp]: its yield a day of handling over itself and the least the
     * gatherers took up (their rate when they stopped): above 1/2 it was taken up, below it ranked out; 0 when none was on offer.
     */
    fun pull(t: Food, k: Int = 0): Float {
        for (i in 0 until m) if (type[i] == t && kind[i] == k && who[i] == 1) return pullAt(profit[i])
        return 0f
    }

    /** How a food paying [p] a day of handling, as the gatherers weigh it, would have pulled against their least paying food taken up (see [pull]). */
    fun pullAt(p: Float): Float = if (p > 0f) p / (p + gain[1] / spent[1]) else 0f
}

/**
 * Offers [t] to [w]'s party in [o]: [r] a day of [e] kg each, taking [h] days each, each kg feeding [rich] times a kg of [t]
 * (a kill's fat, [fatOfKill]), weighed also for the food [spared] a kill takes from a rival (see [offerFoods]). A band's gatherers rank each kind of
 * food by what it weighs it at, times its effort on it: above 1 they take it up before foods that pay more, and below 1
 * leave it for ones that pay less, and either way get what it truly yields ([Offers.takeUp]). (Big game's effort is
 * the hunters' share instead, see [huntersShare].)
 */
private fun HexGroups.offer(w: ForageWeek, o: Offers, t: Food, k: Int, p: Place, r: Float, e: Float, h: Float, spared: Float = 0f, herd: Int = -1, rich: Float = 1f) {
    val band = w.band
    val keen = if (band != null && t != Food.BIG_GAME) band.effort[t] else 1f
    if (band != null && t != Food.BIG_GAME && r > 0f) dialUse?.let { u -> val f = FoodEfforts.slot(t)
        u.amount(DialSite.EFFORT_RANK, keen, keen, 1f, dial = f) }
    if (r <= 0f || e <= 0f || (band != null && t != Food.BIG_GAME && w.gatherers <= 0f)) return
    // (Biting insects slow people at whatever they do out there; fish are offered at the camp, whose place stands for the water they fish.)
    val hh = if (band != null) harassedDays(p, h) else h
    o.add(t, k, p, r, e, e * w.feeds(t) * rich, (e * rich + spared) * w.weigh(t) * keen, hh, if (band != null && t != Food.BIG_GAME) 1 else 0, herd)
}

/** [h] days of a band's work at place [p], as biting insects slow it there ([HexSmallLife.HARASS_TIME]). */
private fun HexGroups.harassedDays(p: Place, h: Float) = h * (1f + HexSmallLife.HARASS_TIME * small.harassment(p))

/** [forage]'s foods on offer to [w]'s party in a pass, into [o]: along its way, round its camp and, for a band, in the water by it. */
private fun HexGroups.offerFoods(w: ForageWeek, o: Offers) {
    val g = w.g; val band = w.band; val nk = w.nk; val sweep = w.sweep; val days = this.days
    val adults = fauna.adults; val young = fauna.young
    o.m = 0
    // (What a kill is worth to it: see below. Its butchers' skill grows with each kill, so once a pass.)
    val credit = creditShare(band); val most = (1f + SURPLUS_EAT) * w.wantAll
    // (A beast weighs a kill also by the food the dead one would have taken from it: a week of its intake, as far as their
    // diets overlap, valued as its own. A rival of its own food is the more worth killing; a plant-eater is not one.)
    val spared = FloatArray(nk) { k -> if (band != null) 0f else { val ps = fauna.kinds[k]; w.d.overlap(ps.diet) * ps.dailyIntakeKg * days } }
    // Big game over its hunting ground. (A band's hunters fan out over less of it than the whole band would, as for a
    // smaller party: see [searchKm2] and [ForageWeek.ground].)
    for (j in 0 until w.ground.size) {
        val p = w.ground.place[j]; val huntSwept = w.ground.km2[j]
        for (c in w.gameFrom[j] until w.gameFrom[j + 1]) {
            val i = w.gameAt[c]; val k = i - j * nk
            val dens = adults[k][p] + young[k][p]
            if (dens <= 0f) continue
            // A kill is worth what the party can eat of it, gorging included (a band also what it can
            // dry); the rest is left to others.
            val meat1 = min(fauna.kinds[k].massKg * credit, most)
            // (A band's kill of a grown one is the richer for its fat, the young lean: what it weighs is what its kills would be, as
            // many grown as there are among them.)
            val rich = if (band != null && meat1 > 0f) {
                val fat1 = fatOfKill(k, Place(p), meat1, band) * adults[k][p] / dens
                1f + fat1 / meat1 * (w.fatFeeds / w.feeds(Food.BIG_GAME).coerceAtLeast(1e-6f) - 1f)
            } else 1f
            // (Each kill also costs the stalks and chases that failed before it.)
            offer(w, o, Food.BIG_GAME, k, Place(p), dens * huntSwept * w.odds[i] / days, meat1, HANDLING_DAYS + STALK_DAYS * w.stalks[i] + meat1 * w.eats,
                spared[k], rich = rich)
        }
    }
    // And the herds kept at bands' camps on it, as their kind in the wild ([herdOdds]).
    for ((x, hg) in w.herds.withIndex()) {
        val b = hg.g.band ?: continue
        val n = (if (hg.young) b.youngHerd() else b.herd).getOrNull(hg.k) ?: continue
        if (n < 1f) continue
        val p = w.ground.place[hg.j]
        val meat1 = min((if (hg.young) HexFauna.YOUNG_WEIGHT else 1f) * fauna.kinds[hg.k].massKg * credit, most)
        offer(w, o, Food.BIG_GAME, hg.k, Place(p), n / land.areaKm2[p] * w.ground.km2[hg.j] * hg.odds / days, meat1,
            HANDLING_DAYS + STALK_DAYS * hg.stalks + meat1 * w.eats, spared[hg.k], x)
    }
    // The rest along its way.
    for (h in g.path) {
        val p = hw.placeNear(g.place, h).takeIf { !it.none } ?: continue
        val area = land.areaKm2[p]
        val swept = min(area, sweep)
        val small = this.small
        // (Small game in cover is found the less, on burnt or grazed bare ground the more: [smallSeen].)
        offer(w, o, Food.SMALL_GAME, 0, p, small.smallPreyKg(p) * min(area, w.smallSweep * smallSeen(p)) * w.smallCatch / days, 1f, w.smallDays)
        if (w.d.carrion > 0f) {
            // (At most half of what lies on the ground searched: the rest is not found in time, or others get it;
            // and only the scavengers by trade, which watch the vultures and quarter the ground for it, find
            // that much: a wolf or a band comes on a carcass now and then, not every week.)
            val found = CARRION_FOUND * min(1f, w.d.carrion / SCAVENGER_CARRION)
            offer(w, o, Food.CARRION, 0, p, KgM2(found * small.carrion[p]).perKm2.over(swept).v / days, 1f, w.eats)
            if (Niches.cracksBones(w.s)) offer(w, o, Food.BONES, 0, p, KgM2(found * small.bones[p]).perKm2.over(swept).v / days, 1f, w.eats * BONE_DAYS)
            else if (band != null && w.worth(Food.BONES) > 0f)
                offer(w, o, Food.BONES, 0, p, KgM2(found * small.bones[p]).perKm2.over(swept).v / days, 1f, w.renderDays + w.eats * GREASE_YIELD)
        }
    }
    // (The ground it walked, then the country round its camp, which holds none of it.)
    if (w.eatsPlants) for (qi in 0 until w.gatherAt.size + w.roundCamp.size) {
        val q = if (qi < w.gatherAt.size) w.gatherAt[qi] else w.roundCamp[qi - w.gatherAt.size]
        val part = if (qi < w.gatherAt.size) 1f else w.roundShare
        // A band can strip so much of a place in a week, as its skill allows; an animal finds what lies along its way.
        // (On strange ground people find little of it.)
        val knowHere = band?.let { plantKnow(it, hw.placeHex[q]) } ?: 1f
        // (Gatherers who go further from camp cover more of the ground.)
        val reach = band?.let { b -> (min(0.9f, b.gatherShare(false, tuning) * knowHere * b.gatherRange) * part).also { v -> dialUse?.amount(DialSite.GATHER_REACH,
            b.gatherRange, v, min(0.9f, b.gatherShare(false, tuning) * knowHere) * part, cut = b.gatherShare(false, tuning) * knowHere * b.gatherRange > 0.9f) } }
            ?: min(1f, w.smallSweep * g.path.size / land.areaKm2[q])
        // (The unskilled, and strangers to its plants, are slow at it.)
        val slow = band?.slowness(Skill.GATHER, knowHere) ?: 1f
        val eats = w.eats
        val pickQ = (w.pickDays - eats) * slow + eats; val seedQ = (w.seedDays - eats) * slow + eats
        val fruitQ = (w.fruitDays - eats) * slow + eats
        val seedReach = band?.let { it.gatherShare(true, tuning) * knowHere * part } ?: reach
        // (Gatherers take the near ground first: the share they reach of a place as a disc about the camp, two thirds of
        // its radius out on average; a place round the camp lies half a hex further. The walk takes no skill.)
        val off = if (qi < w.gatherAt.size) 0f else hex.hexKm.toFloat() / 2f
        fun walkKm(share: Float) = 2f / 3f * sqrt(share * land.areaKm2[q] / PI.toFloat()) + off
        val walk = walkKm(reach)
        val seedWalk = walkKm(seedReach)
        fun walked(t: Food, km: Float) = km * w.walkDaysPerKm / dryShare(t)
        // (A band's gatherers must find fruit before they pick it, as they must roots: the thinner it grows, the longer a
        // kilo; see [PLANT_SIGHT_M2_PER_DAY].)
        val food = fauna.food
        // (A plant food on offer at [q]: [share] of the ground reached, of [density] kg a km², for [workDays] a kg.)
        fun plant(f: Food, kind: Int, share: Float, density: Float, workDays: Float) =
            offer(w, o, f, kind, Place(q), land.total(Place(q), KgM2(share * density)).v / days, 1f, workDays)
        fun sought(density: Float) = if (band != null && density > 0f) 1f / (PLANT_SIGHT_M2_PER_DAY * density * w.gatherers) else 0f
        plant(Food.FRUIT, 0, reach, food.fruit[q], fruitQ + sought(food.fruit[q]) + walked(Food.FRUIT, walk))
        // (Roots likewise, searched out over less ground a day, [ROOT_SEARCH_M2_PER_DAY]; none are dug in frozen ground,
        // [HexFood.diggable]. The mast lying under the trees is picked up a nut at a time, as fruit is picked ([MAST_KIND]);
        // its bitter share must be leached before it is eaten, by bands that know how and have water by them
        // ([MAST_LEACH_DAYS_PER_KG]). An animal roots for both as one.)
        if (band != null) {
            val bulbs = food.diggable(Place(q))
            val rootSearch = if (bulbs > 0f) 1f / (ROOT_SEARCH_M2_PER_DAY * bulbs * w.gatherers) else 0f
            plant(Food.ROOTS, 0, reach, bulbs, ROOT_DIGGING * pickQ + rootSearch + walked(Food.ROOTS, walk))
            val bitter = if (band.leachesMast(land.drink.water[q] >= Thresholds.WET || land.drink.waterKm[q] < 3f)) MAST_BITTER else 0f
            val eaten = 1f - MAST_BITTER + bitter
            val leach = bitter / eaten * MAST_LEACH_DAYS_PER_KG / w.gatherers * band.slowness(Skill.LEACHING)
            plant(Food.ROOTS, MAST_KIND, reach * eaten, food.mast[q], fruitQ + leach + sought(food.mast[q]) + walked(Food.ROOTS, walk))
        } else plant(Food.ROOTS, 0, reach, food.underground(Place(q)), ROOT_DIGGING * pickQ + walked(Food.ROOTS, walk))
        plant(Food.FINDS, 0, reach, food.finds[q], ROOT_DIGGING * pickQ + walked(Food.FINDS, walk))
        // Grass seed must be reaped, threshed and ground: worth it only with the tools and the hands.
        // (The small seeds of wild grasses take longer a kilo than wild cereal's, [SMALL_SEED_SLOWER]; both must be found, as
        // fruit is.)
        plant(Food.SEED, 0, seedReach, food.seeds[q], (seedQ - eats) * SMALL_SEED_SLOWER + eats + sought(food.seeds[q]) + walked(Food.SEED, seedWalk))
        // Wild and sown cereals: reaped like grass seed, but richer where they stand.
        val cereal = food.grain[q] + food.fieldGrain[q]
        plant(Food.GRAIN, 0, seedReach, cereal, seedQ + sought(cereal) + walked(Food.GRAIN, seedWalk))
    }
    // Bands fish the river, the lake or the sea by their camp, as their skill and nets allow ([Band.fishing]); and gather
    // shellfish off the shore, which wants neither (kind [SHELLFISH_KIND] of the fish offered).
    if (band != null) {
        val h = hw.placeHex[g.place]
        offer(w, o, Food.FISH, 0, g.place, fauna.water.fishWithin(h, band.fishing(), HexWater.Catch.FISH) / days, 1f, FISH_HANDLING_DAYS_PER_KG / w.gatherHands * band.slowness(Skill.FISH) + w.eats)
        offer(w, o, Food.FISH, SHELLFISH_KIND, g.place, fauna.water.fishWithin(h, 1f, HexWater.Catch.SHELLFISH) / days, 1f, SHELLFISH_DAYS_PER_KG / w.gatherHands + w.eats)
        // And the spring run's fish going up the river by the camp, speared or dip-netted from the bank (kind [RUN_KIND]): a
        // kilo takes the wait for fish to come within reach ([HexWater.runWithinReach]), slower for unskilled hands, and its
        // landing and gutting ([WEIR_HANDLING_DAYS_PER_KG]). The more fish go by, the less the wait: where the run crowds a
        // narrow river it pays as nothing else does, and elsewhere hardly at all.
        val runKg = fauna.water.fishWithin(h, band.fishing(), HexWater.Catch.RUN)
        val reachKg = fauna.water.runWithinReach(h)
        if (runKg > 0f && reachKg > 0f)
            offer(w, o, Food.FISH, RUN_KIND, g.place, runKg / days, 1f, (band.slowness(Skill.FISH) / reachKg + WEIR_HANDLING_DAYS_PER_KG) / w.gatherHands + w.eats)
        // And fish trapped at a weir across the river by the camp, where one stands that it may use (kind [WEIR_KIND]): the
        // tending of its traps over what they take, and the landing and gutting of each kilo ([weirDays]). A weir pays
        // only where many fish pass; it wants no fishing skill.
        val width = fauna.water.riverWidthM(h)
        val blocked = weirAt(g, h)
        if (blocked > 0f) {
            val kg = fauna.water.weirWithin(h, blocked)
            if (kg > 0f) offer(w, o, Food.FISH, WEIR_KIND, g.place, kg / days, 1f, weirDays(blocked * width, kg) / w.gatherHands + w.eats)
        }
        // (What fish from a whole weir there would pay it, as it weighs them, a day of handling: whether one would be worth
        // building, see [weirAsk].)
        val whole = weirLengthM(h)
        val wholeKg = if (whole > 0f) fauna.water.weirWouldTake(h, whole / width) else 0f
        w.weirProbe = if (wholeKg > 0f && w.gatherers > 0f)
            w.weigh(Food.FISH) * band.effort[Food.FISH] / harassedDays(g.place, weirDays(whole, wholeKg) / w.gatherHands + w.eats) else 0f
    }
}

/** [forage]'s taking of the foods [w]'s party took up in [o], at those rates for the [f] share of its working days. */
private fun HexGroups.takeFoods(w: ForageWeek, o: Offers, f: Float) {
    val g = w.g; val band = w.band; val haul = w.haul
    for (r in 0 until o.used) {
        val i = o.order[r]
        val expected = w.workDays * o.rate[i] / o.spent[o.who[i]] * f
        val p = o.place[i]
        // (Days of work counted for the whole band: each party's days in its share of the hands.)
        if (o.type[i] != Food.BIG_GAME) w.busy += expected * o.time[i] * w.party[o.who[i]]
        when (o.type[i]) {
            Food.BIG_GAME -> {
                val kills1 = if (o.herd[i] >= 0) raidHerd(g, w.herds[o.herd[i]], Place(p), expected, o.kg[i], w.rnd, haul)
                    .also { k -> diag(g.species, HuntStep.HERD_KILLS_SOUGHT, expected); diag(g.species, HuntStep.HERD_KILLS, k.toFloat()) }
                    else huntGame(g, o.kind[i], Place(p), expected, o.kg[i], w.spareYoung, w.rnd, haul)
                val luck = (kills1 - expected) * o.time[i] * w.party[0]
                if (luck > 0f) w.killOver += luck else w.killUnder -= luck
                if (kills1 <= 0) continue
                if (band != null && o.kind[i] == band.totem.i) {
                    band.totemBroken = TOTEM_SHAME_YEARS
                    stats.meeting(Met.BANDS_ATE_THEIR_TOTEM)
                }
                w.busy += kills1 * o.time[i] * w.party[0]
                // (A beast's kills may be found by stronger scavengers before it has eaten them: [foundByScent].)
                if (band == null) foundByScent(g, Place(p), kills1, haul)
                // (Its fat feeds as grease, the rest as lean meat; for the census, the kills by month and by prey kind, lean and fat by weight.)
                w.meat += haul.kg - haul.fat; w.killFat += haul.fat
                w.fed += (haul.kg - haul.fat) * w.feeds(Food.BIG_GAME) + haul.fat * w.fatFeeds
                if (band != null) { val m = w.weather.date.month
                    stats.hunting.merge(Tally.bandKills(m, Tally.KillPart.KILLS), kills1, Int::plus)
                    stats.hunting.merge(Tally.bandKills(m, Tally.KillPart.LEAN_KG), (haul.kg - haul.fat).roundToInt(), Int::plus)
                    stats.hunting.merge(Tally.bandKills(m, Tally.KillPart.FAT_KG), haul.fat.roundToInt(), Int::plus)
                    val prey = fauna.kinds[o.kind[i]].name
                    stats.hunting.merge(Tally.bandKillsOf(prey, Tally.KillPart.KILLS), kills1, Int::plus)
                    stats.hunting.merge(Tally.bandKillsOf(prey, Tally.KillPart.LEAN_KG), (haul.kg - haul.fat).roundToInt(), Int::plus)
                    stats.hunting.merge(Tally.bandKillsOf(prey, Tally.KillPart.FAT_KG), haul.fat.roundToInt(), Int::plus) }
            }
            Food.SMALL_GAME -> small.catchSmallIn(Place(p), Kg(expected), min(land.areaKm2[p], w.smallSweep * smallSeen(Place(p))), w.weather).v.let { w.smallGot += it; w.fed += it * w.feeds(Food.SMALL_GAME) }
            Food.CARRION -> scavenge(g, Place(p), expected, w.sweep, w.rnd).let { w.scraps += it; w.fed += it * w.feeds(Food.CARRION) }
            Food.BONES -> small.crackBonesIn(Place(p), Kg(expected), min(land.areaKm2[p], w.sweep)).v.let { kg ->
                if (band == null) { w.scraps += kg; w.fed += kg * w.worth(Food.BONES) }
                else if (kg > 0f) {
                    // (The boiled bone, its grease taken, is thrown out at the camp.)
                    val grease = kg * greaseOf(band); val fed = kg * w.worth(Food.BONES)
                    w.grease += grease; w.greaseFed += fed; w.fed += fed
                    small.deposit(DeadPool.LITTER, Place(p), Kg(kg - grease)); band.practise(Skill.STONE_BOILING)
                }
            }
            Food.FRUIT, Food.ROOTS, Food.SEED, Food.FINDS -> {
                gather(g, o.type[i], o.kind[i], Place(p), expected, w.hands, haul)
                if (haul.busy > 0f) { w.busy += haul.busy; w.haulOver += haul.busy }
                val fed = haul.kg * w.worth(o.type[i])
                w.plants += haul.kg; w.fed += haul.kg * w.feeds(o.type[i])
                // (Mast, dried, keeps as seed does, for a year or more; bulbs and roots as roots.)
                val st = when (o.type[i]) { Food.ROOTS -> if (o.kind[i] == MAST_KIND) Store.SEED else Store.ROOTS; Food.SEED -> Store.SEED; else -> Store.FRUIT }
                w.plantKind[st] += fed; w.plantKg[st] += haul.mass
                val from = when (o.type[i]) { Food.FRUIT -> PlantFrom.FRUIT; Food.SEED -> PlantFrom.SEED; Food.FINDS -> PlantFrom.FINDS
                    else -> if (o.kind[i] == MAST_KIND) PlantFrom.MAST else PlantFrom.BULBS }
                w.plantFrom[from] += fed
                if (from == PlantFrom.MAST && haul.kg > 0f && band != null && band.knowsSkill[Skill.LEACHING]) band.practise(Skill.LEACHING)
            }
            Food.FISH -> if (o.kind[i] == SHELLFISH_KIND) fauna.water.fishFor(hw.placeHex[p], expected, 1f, HexWater.Catch.SHELLFISH).let { w.fish += it; w.fed += it * w.feeds(Food.FISH) }
                else if (o.kind[i] == RUN_KIND) fauna.water.fishFor(hw.placeHex[p], expected, band?.fishing() ?: 1f, HexWater.Catch.RUN).let { w.fish += it; w.fished += it; w.fed += it * w.feeds(Food.FISH) }
                else if (o.kind[i] == WEIR_KIND) fauna.water.weirFor(hw.placeHex[p], expected, weirAt(g, hw.placeHex[p])).let { w.fish += it; w.fished += it; w.fed += it * w.feeds(Food.FISH) }
                else fauna.water.fishFor(hw.placeHex[p], expected, band?.fishing() ?: 1f, HexWater.Catch.FISH).let { w.fish += it; w.fished += it; w.fed += it * w.feeds(Food.FISH) }
            Food.GRAIN -> {
                reap(g, Place(p), expected, haul)
                val fed = haul.kg * w.worth(Food.GRAIN)
                w.plants += haul.kg; w.fed += haul.kg * w.feeds(Food.GRAIN); w.plantKind[Store.SEED] += fed; w.plantKg[Store.SEED] += haul.mass
                w.plantFrom[PlantFrom.GRAIN] += fed
            }
        }
    }
}

/**
 * The end of [w]'s week of [forage]: what it learnt, the week's tallies, and what it ate: a band eats from the
 * week's food and its stores and keeps the rest; a beast gorges on what is left of a kill and leaves the rest.
 */
private fun HexGroups.eatTheWeek(w: ForageWeek) {
    val g = w.g; val s = w.s; val band = w.band; val weather = w.weather; val wantAll = w.wantAll
    val meat = w.meat; val smallGot = w.smallGot; val scraps = w.scraps; val plants = w.plants; val fish = w.fish
    // The resident bands it came on while it searched count towards its search image.
    if (w.searched > 0f) countResidentsMet(g, w.ground, w.met, w.searched, w.today)
    if (band != null) { practise(g, band, meat + w.killFat > g.meatMet, smallGot, plants, w.fished); recordFood(g, band, w) }
    // (All counted at what it feeds, see [ForageWeek.worth]: the meat eaten as far as the rest falls short.)
    val meatWorth = w.feeds(Food.BIG_GAME); val rest = w.fed - meat * meatWorth - w.killFat * w.fatFeeds
    // (What its plant food weighs: raw seed weighs more than it feeds, see [gather].)
    val plantMass = w.plantKg.sum()
    var meatEaten = min(meat, max(0f, (wantAll - rest) / meatWorth))
    note(g) {
        "foraged for %.0f kg wanted: meat %.0f (%.0f taken from other hunters), small game %.0f, carrion %.0f, plants %.0f, fish %.0f; busy %.1f of %d days; sight %.2f of a clear day in the open%s".format(
            wantAll, meat, g.meatMet, smallGot, scraps, plants, fish, w.busy, days, sight(g.place, weather),
            if (band != null) "; %.0f%% of adults scouting".format(100 * band.scoutShare) else "")
    }
    if (FORAGE_DIAG == s.name && diagSampled(g, weather, 0.003f, 2)) System.err.println(
        "ate ${s.name} want $wantAll meat $meat small $smallGot scraps $scraps plants $plants fish $fish path ${g.path.size}")
    var eaten = meatEaten * meatWorth + rest
    tallyForage(w, meatEaten)
    var spare = meat - meatEaten
    if (band != null) {
        // This week's food: fresh meat and fish (small game and scraps with it), and plant food by kind.
        val got = w.plantKind; val plantKg = w.plantKg
        // (The small game's pelts are set aside as it is caught: they are not food, and go to the skins or the scavengers.)
        val pelts = SMALL_SKIN * smallGot
        // (Fresh food is kept at what it feeds, with what it weighs, as plant food is: see [ForageWeek.worth].)
        plantKg[Store.FRESH] = meat + smallGot - pelts + scraps + fish
        got[Store.FRESH] = w.worth(Food.BIG_GAME) * meat + w.worth(Food.SMALL_GAME) * (smallGot - pelts) + w.worth(Food.CARRION) * scraps + w.worth(Food.FISH) * fish
        // (The fat of its kills is eaten as grease is: it holds no protein.)
        plantKg[Store.GREASE] = w.grease + w.killFat; got[Store.GREASE] = w.greaseFed + w.killFat * w.fatFeeds
        val meal = Meal(wantAll)
        // (Salt hunger: its people eat meat for its sodium before anything else, as far as the kill and the larder have it. Salt
        // appetite is inborn: mammals short of sodium seek it out and prefer salty food unlearned, Denton 1982, The Hunger for Salt.)
        val sodiumNeed = SODIUM_A_PERSON_DAY * g.size * days
        val stored = g.larder
        meal.eatForSodium(sodiumNeed / SODIUM_PER_KG_MEAT, got, plantKg, g)
        meal.eat(got, plantKg, g)
        band.larderMoved(g, LarderMove.ATE, g.larder - stored)
        band.leanLost = meal.leanLost
        // (Its weanlings get gruel as far as there is seed, eaten this week or still in store: their share of the meals is
        // already in its need, so they eat seed and the rest eat the rest in its stead.)
        val weaned = band.gruel() * min(1f, (meal.ateSeed + g.stores[Store.SEED]) / (WEANING_SHARE * wantAll).coerceAtLeast(1e-3f))
        band.weaningFood += (weaned - band.weaningFood) / stepsPerYear
        val ateMeat = meal.ateMeat; val ateKg = meal.ateKg; val ateMeatKg = meal.ateMeatKg
        eaten = wantAll - meal.need
        land.books?.flow(Books.Stuff.MATTER, "eaten", -ateKg.toDouble())
        // (People need sodium, which meat and fish carry and plant food hardly does; what the week's meat falls short of
        // they make up with salt, and short of that they are listless and weak, and the food does them the less good.)
        // (Salted meat and fish carry their salt: [SALT_PER_KG] a kg, the share of the dried that was salted.)
        if (eaten > 0f) {
            var short = max(0f, sodiumNeed - SODIUM_PER_KG_MEAT * ateMeatKg - meal.ateDriedKg * band.salted * SALT_PER_KG * SALT_SODIUM)
            if (short > 0f) {
                val had = min(short / SALT_SODIUM, band.goods[Material.SALT]); band.goods[Material.SALT] -= had
                short = max(0f, short - had * SALT_SODIUM)
            }
            band.sodiumShort = if (sodiumNeed > 0f) short / sodiumNeed else 0f
            eaten *= saltFeeds(short, sodiumNeed, tuning.saltLack)
        }
        stats.hunting.merge(Tally.of(s, Tally.OfKind.MEAT_EATEN), ateMeat.toInt(), Int::plus)
        stats.hunting.merge(Tally.of(s, Tally.OfKind.PLANTS_EATEN), (eaten - ateMeat).toInt(), Int::plus)
        keepAndSpoil(w, got, plantKg, pelts)
        band.seedCorn = band.seedCornIn(g)
        spare = 0f; meatEaten = ateMeatKg
    }
    // Gorging: what is left of a kill is eaten beyond need, as far as the gut holds and reserves are short.
    if (spare > 0f && g.fat < s.maxFatDays && band == null) {
        val gorge = min(spare, SURPLUS_EAT * wantAll)
        g.surplus = gorge * meatWorth / g.dailyNeedKg.coerceAtLeast(1e-3f)
        spare -= gorge
        small.deposit(DeadPool.DUNG, g.place, Kg(HexSmallLife.DUNG * gorge))
        land.books?.let { bookEaten(it, gorge * meatShare(null), HexSmallLife.DUNG * gorge) }
    }
    g.fed = (eaten / wantAll).coerceIn(0f, 1f)
    if (band != null) note(g) {
        "ate %.0f of %.0f kg wanted (fed %.2f); larder left %.0f kg (%s); fat %.1f days; sodium short %.0f%%, salt %.1f kg; lean lost %.0f%%".format(
            eaten, wantAll, g.fed, g.larder, Store.entries.joinToString(" ") { "${it.name.lowercase()} %.0f".format(g.stores[it]) },
            g.fat, 100f * band.sodiumShort, band.goods[Material.SALT], 100f * band.leanLost)
    }
    // What the pack could not finish is left for the scavengers; what it ate comes back in part as dung.
    small.deposit(DeadPool.CARRION, g.place, Kg(spare))
    // (A band's meat eaten already holds the small game, scraps and fish it ate; what it stored is not yet eaten.)
    val animalEaten = if (band != null) meatEaten else meatEaten + smallGot + scraps + fish
    small.deposit(DeadPool.DUNG, g.place, Kg(HexSmallLife.DUNG * animalEaten))
    land.books?.let { bookForage(it, band, meatEaten, smallGot + scraps + plantMass + fish, fish, spare, HexSmallLife.DUNG * animalEaten) }
    // (The meat it took in its meetings is eaten, stored or left by now.)
    g.meatSpent = true
}

/**
 * A band's eating of [wantAll] (kg of its need) from the week's food and then its stores, each in the order it spoils. Protein
 * feeds people only up to [Nourishment.PROTEIN_MOST] of their need: beyond that the liver can make no more of it, and lean
 * meat feeds by its fat alone ("rabbit starvation"). So it eats lean food first only as far as its protein can feed it, then
 * plant food and grease, and only when those run out more lean food, for its fat. What it ate is counted as it feeds (the
 * protein beyond the ceiling not at all), and also by weight; [leanLost] is the share of the protein it ate that went to waste.
 */
private class Meal(val wantAll: Float) {
    var need = wantAll; var ateMeat = 0f; var ateKg = 0f; var ateMeatKg = 0f; var ateDriedKg = 0f; var ateSeed = 0f
    private var room = Nourishment.PROTEIN_MOST * wantAll; private var protein = 0f; private var lost = 0f
    val leanLost get() = if (protein > 0f) lost / protein else 0f

    /** Eats [got] (by [Store], kg of need, each weighing [gotKg]) and then [g]'s stores. */
    fun eat(got: EnumFloats<Store>, gotKg: EnumFloats<Store>, g: HexGroup) {
        for (beyond in 0..1) {
            for (st in EAT_ORDER) {
                if (got[st] <= 0f) continue
                val kgPer = gotKg[st] / got[st]; val e = take(st, got[st], kgPer, beyond == 1)
                got[st] -= e; gotKg[st] -= e * kgPer
            }
            for (st in EAT_ORDER) g.stores[st] -= take(st, g.stores[st], g.storeKgPer[st], beyond == 1)
        }
    }

    /**
     * Eats up to [kg] (by weight) of meat and fish, from [got] (by [Store], kg of need, each weighing [gotKg]) and then [g]'s
     * stores, each in the order it spoils, within the protein ceiling: the meat its people's sodium asks for.
     */
    fun eatForSodium(kg: Float, got: EnumFloats<Store>, gotKg: EnumFloats<Store>, g: HexGroup) {
        var left = kg
        for (st in EAT_ORDER) {
            if (!st.meat) continue
            if (left > 0f && got[st] > 0f) {
                val kgPer = gotKg[st] / got[st]; val e = take(st, min(got[st], left / kgPer), kgPer, false)
                got[st] -= e; gotKg[st] -= e * kgPer; left -= e * kgPer
            }
            if (left > 0f && g.stores[st] > 0f) {
                val kgPer = g.storeKgPer[st]; val e = take(st, min(g.stores[st], left / kgPer), kgPer, false)
                g.stores[st] -= e; left -= e * kgPer
            }
        }
    }

    /** Eats of [have] of [st] (weighing [kgPer] a kg of need): within the protein ceiling, or [beyond] it; what it ate. */
    private fun take(st: Store, have: Float, kgPer: Float, beyond: Boolean): Float {
        val p = st.protein
        if (have <= 0f || need <= 0f || (beyond && p <= 0f)) return 0f
        val e = if (!beyond) min(min(need, have), if (p > 0f) max(0f, room) / p else need) else min(have, need / (1f - p))
        if (e <= 0f) return 0f
        val fed = if (beyond) e * (1f - p) else e
        need -= fed; protein += e * p; if (beyond) lost += e * p else room -= e * p
        ateKg += e * kgPer; if (st.meat) { ateMeat += fed; ateMeatKg += e * kgPer }; if (st == Store.DRIED) ateDriedKg += e * kgPer
        if (st == Store.SEED) ateSeed += fed
        return e
    }
}

/** [forage]'s tallies of [w]'s week: its diet, [meatEaten] of its meat eaten, and for bands how they fared by the kind of country. */
private fun HexGroups.tallyForage(w: ForageWeek, meatEaten: Float) {
    val g = w.g; val s = w.s; val band = w.band; val wantAll = w.wantAll
    val meat = w.meat; val smallGot = w.smallGot; val scraps = w.scraps; val fish = w.fish
    // (Each at what it feeds, in kg of its need as asked: see [ForageWeek.worth].)
    val meatFed = meatEaten * w.worth(Food.BIG_GAME) + w.killFat * w.fatFeeds + fish * w.worth(Food.FISH)
    val smallFed = smallGot * w.worth(Food.SMALL_GAME); val scrapsFed = scraps * w.worth(Food.CARRION)
    val plantsFed = w.plantKind.sum()
    stats.hunting.merge(Tally.diet(s, Tally.DietPart.ASKED), wantAll.toInt(), Int::plus)
    stats.hunting.merge(Tally.diet(s, Tally.DietPart.MEAT), meatFed.toInt(), Int::plus)
    stats.hunting.merge(Tally.diet(s, Tally.DietPart.SMALL_PREY), smallFed.toInt(), Int::plus)
    stats.hunting.merge(Tally.diet(s, Tally.DietPart.CARRION), scrapsFed.toInt(), Int::plus)
    stats.hunting.merge(Tally.diet(s, Tally.DietPart.PLANTS), plantsFed.toInt(), Int::plus)
    // For bands, the same by the kind of country they are in, and how they fared.
    if (band != null) {
        val c = Biome.entries[biomeOfPlace[g.place]]
        stats.hunting.merge(Tally.bandsIn(c, Tally.Bands.ASKED), wantAll.toInt(), Int::plus)
        stats.hunting.merge(Tally.bandsIn(c, Tally.Bands.MEAT), (meat * w.worth(Food.BIG_GAME) + w.killFat * w.fatFeeds + smallFed + scrapsFed).toInt(), Int::plus)
        stats.hunting.merge(Tally.bandsIn(c, Tally.Bands.PLANTS), plantsFed.toInt(), Int::plus)
        for (f in PlantFrom.entries) stats.hunting.merge(Tally.plantsFrom(f), w.plantFrom[f].toInt(), Int::plus)
        stats.hunting.merge(Tally.bandsIn(c, Tally.Bands.FISH), (fish * w.worth(Food.FISH)).toInt(), Int::plus)
        stats.hunting.merge(Tally.bandsIn(c, Tally.Bands.BAND_WEEKS), 1, Int::plus); stats.hunting.merge(Tally.bandsIn(c, Tally.Bands.PEOPLE_WEEKS), g.size, Int::plus)
        stats.hunting.merge(Tally.bandsIn(c, Tally.Bands.HEARTH), (100f * band.hearthWood).toInt(), Int::plus)
        stats.hunting.merge(Tally.bandsIn(c, Tally.Bands.FUEL_DAYS), (100f * w.fuelDays / days).toInt(), Int::plus)
        // (And by whether it keeps a herd: what it foraged against what it wanted, and its hearth.)
        val herding = bandHerding(band, g.adults)
        stats.hunting.merge(Tally.bandsHerding(herding, Tally.Bands.ASKED), wantAll.toInt(), Int::plus)
        stats.hunting.merge(Tally.bandsHerding(herding, Tally.Bands.MEAT), (meat * w.worth(Food.BIG_GAME) + w.killFat * w.fatFeeds + smallFed + scrapsFed + fish * w.worth(Food.FISH)).toInt(), Int::plus)
        stats.hunting.merge(Tally.bandsHerding(herding, Tally.Bands.PLANTS), plantsFed.toInt(), Int::plus)
        stats.hunting.merge(Tally.bandsHerding(herding, Tally.Bands.HEARTH), (100f * band.hearthWood).toInt(), Int::plus)
        stats.hunting.merge(Tally.bandsHerding(herding, Tally.Bands.FUEL_DAYS), (100f * w.fuelDays / days).toInt(), Int::plus)
    }
}

/** Books [kg] eaten by a beast and the [dung] it dropped of it (see [Books]). */
private fun bookEaten(b: Books, kg: Float, dung: Float) {
    b.flow(Books.Stuff.MATTER, "eaten", -kg.toDouble()); b.flow(Books.Stuff.MATTER, "dung and frass", dung.toDouble())
}

/**
 * Books [forage]'s week (see [Books]): the [fish] it caught come from the water life; a beast ate the meat of its [meatEaten]
 * (its need counted in the whole weight of its kills, see [creditShare]) and [rest], kg, and dropped [dung]; and the [spare]
 * it left lies as carrion at the whole weight credited, more than the meat it is (a known leak). A band's meal is booked as it eats.
 */
private fun GroupWorld.bookForage(b: Books, band: Band?, meatEaten: Float, rest: Float, fish: Float, spare: Float, dung: Float) {
    b.flow(Books.Stuff.MATTER, "to and from the water life", fish.toDouble())
    if (band == null) {
        bookEaten(b, meatEaten * meatShare(null) + rest, 0f)
        if (spare > 0f) b.leak(Books.Stuff.MATTER, "beasts' leftovers left at their whole credited weight", (spare * (1f - meatShare(null))).toDouble())
    }
    b.flow(Books.Stuff.MATTER, "dung and frass", dung.toDouble())
}

/**
 * [forage]'s end of a band's week with its food: what is left of the week's [got] (kg by [Store]) is kept, its hands
 * dry fresh meat and fish and keep the small game's skins in the days its week gives them ([planWeek]), and the stores
 * spoil, what rots going to carrion where the band is.
 */
private fun HexGroups.keepAndSpoil(w: ForageWeek, got: EnumFloats<Store>, gotKg: EnumFloats<Store>, pelts: Float) {
    val g = w.g; val band = checkNotNull(w.band); val weather = w.weather
    val time = checkNotNull(w.time)
    // (What is left of its kills' fat keeps with the fresh meat, as the carcass it came on: only rendered grease keeps as
    // grease. Of the grease left, the kills' fat is its share of the week's.)
    val killFatFed = w.killFat * w.fatFeeds
    if (killFatFed > 0f && got[Store.GREASE] > 0f) {
        val share = killFatFed / (killFatFed + w.greaseFed); val fed = got[Store.GREASE] * share; val kg = gotKg[Store.GREASE] * share
        got[Store.GREASE] -= fed; gotKg[Store.GREASE] -= kg; got[Store.FRESH] += fed; gotKg[Store.FRESH] += kg
    }
    // What is left is kept; spare hands dry fresh meat and fish over the fire, as fast as their skill allows.
    val unkept = g.larder
    for (st in Store.entries) addFood(g.stores, g.storeKgPer, st, got[st], gotKg[st])
    band.larderMoved(g, LarderMove.KEPT, g.larder - unkept)
    // (Racks in the sun dry little in wet weather; smoking over a slow fire works in any.)
    val sun = band.skill(Skill.STORE) / (1f + weather.precipitation[g.place] / DRY_RAIN_MM)
    val smoke = if (band.knowsSkill[Skill.SMOKING] && band.fire > 0f) band.skill(Skill.SMOKING) * min(1f, band.fire) else 0f
    // (A band that knows salting and has salt salts what it cures: salt cures in any weather.)
    val salt = if (band.knowsSkill[Skill.SALTING]) band.goods[Material.SALT] else 0f
    val salting = if (salt > 0f) band.skill(Skill.SALTING) else 0f
    val dryRate = max(max(sun, smoke), salting)
    // (As much of it as the band chooses to, in the days its week gives drying. Fresh food is kept at what it feeds;
    // drying takes its time by what it weighs. What it would have liked to dry is what drying asks of next week's plan.)
    val freshKgPer = g.storeKgPer[Store.FRESH].coerceAtLeast(1e-6f)
    val dryDays = time.leftFor(Activity.DRYING)
    // (All of it from the scaffold's 1 up; above 1 more of the same: dried for longer, a kilo taking that much more of its
    // days and keeping that much better ([driedKeep]).)
    val dryShare = band.dryWill.coerceIn(0f, 1f)
    val depth = max(1f, band.dryWill)
    val daysPerKg = DRY_DAYS_PER_KG * depth
    val dried = min(g.stores[Store.FRESH] * dryShare, dryDays * dryRate / daysPerKg / freshKgPer)
    dialUse?.amount(DialSite.DRYING, band.dryWill, dried, min(g.stores[Store.FRESH], dryDays * dryRate / DRY_DAYS_PER_KG / freshKgPer))
    band.dryAsk = if (dryRate > 0f) g.stores[Store.FRESH] * dryShare * freshKgPer * daysPerKg / dryRate else 0f
    if (dried > 0f) {
        val saltedNow = min(dried, salt / SALT_PER_KG); band.goods[Material.SALT] -= saltedNow * SALT_PER_KG
        val before = g.stores[Store.DRIED]; band.salted = (before * band.salted + saltedNow) / (before + dried)
        band.dryDepth = (before * band.dryDepth + dried * depth) / (before + dried)
        g.stores[Store.FRESH] -= dried; addFood(g.stores, g.storeKgPer, Store.DRIED, dried, dried * g.storeKgPer[Store.FRESH]); band.practise(if (salting >= max(sun, smoke)) Skill.SALTING else if (smoke > sun) Skill.SMOKING else Skill.STORE)
    }
    time.spendAtMost(Activity.DRYING, if (dried > 0f) dried * freshKgPer * daysPerKg / dryRate else 0f)
    time.release(Activity.DRYING)
    band.skinAsk = SKIN_DAYS * pelts
    val unskinned = skinSmallGame(band, time, pelts)
    time.release(Activity.SKINS)
    // What time is left is for making and mending (see [bandWeek]).
    band.spareDays = max(0f, time.left)
    band.week = time
    time.count(stats.hunting, Tally.TimeTotal.ALL, days * w.hands.toFloat())
    // And it spoils, each kind at its own rate, fresh meat the faster the warmer.
    // (In storage pits at home stores keep better, though mice get into the grain, nuts and roots.)
    val home = if (band.atHome) band.house else 0f
    val kept = keepWorth(g, band, home)
    band.houseFoodWeek += kept; band.houseWeek[HouseWorth.KEPT_KG] += kept
    // (Pits sealed with clay and plaster keep the mice out.)
    val unspoilt = g.larder
    var rotten = spoil(g.stores, weather.temperature[g.place], 1f - PIT_KEEP * home, driedKeep(band), g.storeKgPer)
    val sealed = if (band.knowsSkill[Skill.SEALING]) 1f - SEAL_KEEP * band.skill(Skill.SEALING) else 1f
    if (home > 0f) {
        for (st in listOf(Store.SEED, Store.FRUIT, Store.ROOTS)) {
            val x = g.stores[st] * MICE * sealed * home * days / 7f
            g.stores[st] -= x
            rotten += x * g.storeKgPer[st]
        }
        if (band.knowsSkill[Skill.SEALING] && g.larder > 0f) band.practise(Skill.SEALING, 0.2f * PRACTICE)
    }
    band.larderMoved(g, LarderMove.SPOILT, g.larder - unspoilt)
    small.deposit(DeadPool.CARRION, g.place, Kg(rotten + unskinned))
}

/**
 * The small game's skins are kept too, as far as [b] has time to scrape and dry them: many small pelts sewn
 * together (hare, fox, marmot) clothe as well as a few big hides. [pelts] kg were set aside from the small game
 * caught, in the [time] it has to spare; returns the kg left unskinned, for the scavengers.
 */
private fun GroupWorld.skinSmallGame(b: Band, time: BandTime, pelts: Float): Float {
    val skinDays = min(time.leftFor(Activity.SKINS), SKIN_DAYS * pelts)
    b.goods[Material.HIDE] += skinDays / SKIN_DAYS
    time.spend(Activity.SKINS, skinDays)
    stats.hunting.merge(Tally.hide(Tally.HideSource.SMALL_PELTS), (1000f * skinDays / SKIN_DAYS).roundToInt(), Int::plus)
    return max(0f, pelts - skinDays / SKIN_DAYS)
}

/** Tallies the hide in the [took] kg of hide, bone and sinew a band took from kills of [ps] (none if no band), by [source]; returns [took]. */
internal fun GroupWorld.tallyHide(source: Tally.HideSource, ps: Species, took: Float?): Float {
    if (took == null || took <= 0f) return 0f
    val p = ps.products
    stats.hunting.merge(Tally.hide(source), (1000f * took * p.hide / (p.hide + p.bone + p.sinew)).roundToInt(), Int::plus)
    return took
}

/**
 * Counts for the census how a band's foraging ([w], its week's [time]) fared against the days its plan left it: the
 * people-days it spent, those past what the plan left it, and what kills beyond and short of those expected, extra hauling
 * and digging soaks took. Counts only.
 */
private fun HexGroups.countFoodTime(w: ForageWeek, time: BandTime) {
    val pace = time.pace(Activity.FOOD); val hands = w.hands / pace
    val spent = time.spentOn(Activity.FOOD) - w.digDays / pace
    val over = max(0f, spent - w.room)
    fun add(k: Tally.FoodTime, x: Float) { stats.hunting.merge(Tally.foodTime(k), (10f * x).roundToInt(), Int::plus) }
    add(Tally.FoodTime.WEEKS, 0.1f); add(Tally.FoodTime.ROOM, w.room); add(Tally.FoodTime.SPENT, spent); add(Tally.FoodTime.OVER, over)
    if (over > 0.05f * w.room) add(Tally.FoodTime.OVER_WEEKS, 0.1f)
    add(Tally.FoodTime.KILLS_OVER, w.killOver * hands); add(Tally.FoodTime.KILLS_UNDER, w.killUnder * hands)
    add(Tally.FoodTime.HAUL, w.haulOver * hands); add(Tally.FoodTime.DIG, w.digDays / pace)
}

/** What one of [forage]'s takes brought: kilos of food, and party-days spent on it beyond the take itself. */
private class Haul { var kg = 0f; var busy = 0f; var mass = 0f; var fat = 0f }

/**
 * [forage]'s raid on herd [hg] at place [p]: [expected] kills on average, each worth [kgEach] to the beast, out of the band's
 * herd (no more than half of it, as of game in the wild, [huntGame]), the band to learn of them as it keeps its herds
 * ([Band.herdTaken]). Returns the kills; [out] gets the meat.
 */
private fun HexGroups.raidHerd(g: HexGroup, hg: HerdGame, p: Place, expected: Float, kgEach: Float, rnd: kotlin.random.Random, out: Haul): Int {
    val b = hg.g.band ?: return 0
    val s = g.species; val ps = fauna.kinds[hg.k]
    val heads = if (hg.young) b.youngHerd() else b.herd
    val kills1 = min(poisson(expected, rnd), (heads[hg.k] * 0.5f).toInt())
    if (kills1 <= 0) return 0
    heads[hg.k] -= kills1.toFloat()
    if (b.herdTaken.size != b.herd.size) b.herdTaken = FloatArray(b.herd.size)
    b.herdTaken[hg.k] += kills1.toFloat()
    // (Kept herds have no fat state yet: their meat is lean.)
    out.kg = kills1 * kgEach; out.fat = 0f
    val each = if (hg.young) HexFauna.YOUNG_WEIGHT * ps.massKg else ps.massKg
    small.deposit(DeadPool.CARRION, p, Kg(kills1 * max(0f, each - kgEach * meatShare(null) / creditShare(null))))
    stats.meeting(Met.KEPT_ANIMALS_TAKEN, kills1)
    stats.hunting.merge(Tally.of(s, Tally.OfKind.KILLS_OF_KEPT), kills1, Int::plus)
    note(g) { "took %d kept %s".format(kills1, ps.name) }
    fauna.killed(p, Kg(kills1 * each))
    return kills1
}

/**
 * [forage]'s hunt of kind [k] at place [p]: [expected] kills on average, each worth [kgEach] to the party.
 * Returns the kills (0: none, and nothing done); [out] gets the meat. A band at home may keep young it catches.
 */
private fun HexGroups.huntGame(g: HexGroup, k: Int, p: Place, expected: Float, kgEach: Float, spareYoung: Boolean, rnd: kotlin.random.Random, out: Haul): Int {
    val s = g.species; val band = g.band
    val ps = fauna.kinds[k]
    val area = land.areaKm2[p]
    val dens = fauna.adults[k][p] + fauna.young[k][p]
    var kills1 = poisson(expected, rnd)
    kills1 = min(kills1, (dens * area * 0.5f).toInt())
    if (kills1 <= 0) return kills1
    // (No more of either are killed than are there.)
    var ny = min(if (spareYoung) 0 else (kills1 * fauna.young[k][p] / dens).toInt(), (fauna.young[k][p] * area).toInt())
    val na = min(kills1 - ny, (fauna.adults[k][p] * area).toInt())
    kills1 = na + ny
    if (kills1 <= 0) return kills1
    // A band may keep young it catches, to raise by its camp and drive along when it moves.
    if (band != null && ny > 0 && k < band.herd.size) {
        // (A kind is kept by the worst of Diamond's criteria, since it fails as stock on any one, as [herdKind] chooses.)
        val keepable = ps.domestication.min()
        val kept = binomial(ny, min(1f, CAPTURE * band.keepWill * keepable), rnd)
        dialUse?.amount(DialSite.KEEP_CAPTURE, band.keepWill, min(1f, CAPTURE * band.keepWill * keepable),
            min(1f, CAPTURE * keepable), cut = CAPTURE * band.keepWill * keepable > 1f)
        if (kept > 0) {
            // (Raised by hand from young, they come to the camp as tame as a herd's, [RAISED_TAME], whatever their kind: how
            // well a kind keeps on foot shows in how many stray, [straying].)
            val young = band.youngHerd(); val had = band.herd[k] + young[k]
            band.herdTame[k] = (band.herdTame[k] * had + RAISED_TAME * kept) / (had + kept)
            // (A herd begun so is kept as pets till they have grown, or sooner as their temper shows: [Band.herdPet], [petYears].)
            if (had < 0.5f) {
                bands.herdsBegun.incrementAndGet(k)
                if (band.herdPet.size != band.herd.size) band.herdPet = FloatArray(band.herd.size)
                band.herdPet[k] = petYears(ps)
            }
            bands.keptYoung.addAndGet(k, kept)
            young[k] += kept; ny -= kept
            fauna.young[k][p] = max(0f, fauna.young[k][p] - kept / area)
            stats.meeting(Met.YOUNG_ANIMALS_KEPT, kept)
        }
    }
    fauna.young[k][p] = max(0f, fauna.young[k][p] - ny / area); fauna.adults[k][p] = max(0f, fauna.adults[k][p] - na / area)
    // (The share of them it killed, kept for the residents' odds of being hunted, see [HexFauna.huntedOdds].)
    fauna.groupKilled[k][p] += kills1 / (dens * area)
    val young = min(HexFauna.YOUNG_WEIGHT * ps.massKg, kgEach)
    out.kg = na * kgEach + ny * young
    out.fat = na * fatOfKill(k, p, kgEach, band)
    // (Hides, bone and sinew come out of what the hunters leave of the carcasses.)
    val leftA = na * leftOver(ps, kgEach, band)
    small.deposit(DeadPool.CARRION, p, KgM2((leftA + ny * (HexFauna.YOUNG_WEIGHT * ps.massKg - young) - tallyHide(Tally.HideSource.RESIDENTS, ps, band?.skin(ps, na.toFloat(), leftA))) / (area * 1e6f)))
    band?.let { it.practise(Skill.HUNT); it.practise(Skill.TRACK); it.practise(Skill.STALK); it.practise(Skill.BUTCHER) }
    stats.kills.merge(ps, kills1, Int::plus)
    note(g) { "killed %d %s".format(kills1, ps.name) }
    fauna.killed(p, Kg(kills1 * ps.massKg))
    stats.hunting.merge(Tally.of(s, Tally.OfKind.KILLS_OF_RESIDENTS), kills1, Int::plus)
    diag(ps, HuntStep.RESIDENT_KILLS, kills1.toFloat())
    return kills1
}

/**
 * What a beast eats in a day at a kill, of its own weight: a wolf eats 15-19% of its weight in a feeding (Mech and Boitani
 * 2003, p. 201), taken as one feeding a day and for every beast of prey alike (an estimate).
 */
const val GORGE = 0.17f

/**
 * What stronger scavengers take of beast [g]'s [kills] at [p], out of [haul]. Each carcass lies there while the party gorges
 * on it, a kill's weight against what the party eats in a day at a kill ([GORGE]). Resident kinds that kill as well as
 * scavenge (meat and carrion in their diet: a boar roots up a carcass but does not drive wolves off one), awake
 * ([HexFauna.asleep]) and stronger than the party (a band of [Species.groupSize], by its mass and ferocity, as [strength] has
 * a party) come on it by scent as they range: downwind of it only, so a strip [Species.senseKm] wide along a day's travel,
 * their bands as common there as they are. The party stands its ground: the stronger wins the carcass as often as its share
 * of their strengths. A scavenger that wins eats its band's fill there ([GORGE], a day) of what is left (half the kill, on
 * average, found halfway through) while the party waits it out; that goes to the carrion there for the books. Bears so
 * come to 27% of Yellowstone wolf kills in summer, 45% of large ones, and wolves wait at the carcass rather than leave
 * (Tallian et al. 2017, 2021). A carcass hauled up a tree keeps, as far as there are trees ([enemiesMeet]).
 */
private fun HexGroups.foundByScent(g: HexGroup, p: Place, kills: Int, haul: Haul) {
    if (haul.kg <= 0f || kills <= 0) return
    val s = g.species
    val party = strength(g)
    val left = 0.5f * haul.kg / kills
    // (The finds a day, and of them, summed by each kind's finds: the scavenger's wins and the kg each takes.)
    var rate = 0f; var wins = 0f; var takes = 0f
    for ((k, r) in fauna.kinds.withIndex()) {
        if (r.diet.carrion <= 0f || r.diet.meat <= 0f || r.human || fauna.asleep(FaunaKind(k), p)) continue
        val band = r.groupSize * r.massKg * r.ferocity
        if (band <= party) continue
        val finds = (fauna.adults[k][p] + fauna.young[k][p]) / r.groupSize * r.senseKm * r.dailyTravelKm
        val win = band / (band + party)
        rate += finds; wins += finds * win; takes += finds * win * min(left, GORGE * r.massKg * r.groupSize)
    }
    if (rate <= 0f) return
    val lies = min(days.toFloat(), haul.kg / kills / (g.size.coerceAtLeast(1) * max(s.dailyIntakeKg, GORGE * s.massKg)))
    val cached = if (Niches.Shelter.TREE in Niches.shelters(s)) land.plants.canopy(p) else 0f
    val found = (1f - exp(-rate * lies)) * (1f - cached)
    val lost = min(haul.kg, found * kills * takes / rate)
    haul.fat *= 1f - lost / haul.kg; haul.kg -= lost
    small.deposit(DeadPool.CARRION, p, Kg(lost * meatShare(null) / creditShare(null)))
    diag(s, HuntStep.KILLS_MADE, kills.toFloat()); diag(s, HuntStep.KILLS_FOUND_BY_SCENT, found * kills)
    diag(s, HuntStep.KILLS_LOST_TO_SCENT, found * kills * wins / rate)
    diag(s, HuntStep.KILL_KG, haul.kg + lost); diag(s, HuntStep.KILL_KG_LOST_TO_SCENT, lost)
    diag(s, HuntStep.KILL_LIE_DAYS, lies * kills); diag(s, HuntStep.SCENT_RATE, rate * kills); diag(s, HuntStep.KILL_PARTY, g.size * kills.toFloat())
}

/**
 * The fat of a kill of a grown [k] at [p], kg, worth [kgEach] to [band]: the animal's fat ([HexFauna.fatShare]), as much of it
 * as its butchers take (all of it at [MEAT_MOST]) and as the party takes of the carcass. None for a beast: to it a kill is
 * whole prey, fat and all ([Nourishment.PREY]).
 */
internal fun HexGroups.fatOfKill(k: Int, p: Place, kgEach: Float, band: Band?): Float {
    if (band == null || kgEach <= 0f) return 0f
    val ps = fauna.kinds[k]
    val body = fauna.fatShare(k, p) * ps.massKg * min(1f, meatShare(band) / MEAT_MOST)
    return min(kgEach, body * min(1f, kgEach / (ps.massKg * creditShare(band)).coerceAtLeast(1e-6f)))
}

/**
 * Keys for ranking [v]'s first [m] values largest first, equal values in index order (as a stable sort by
 * [java.lang.Float.compare] would put them), into [keys]: each value's bits made to sort as signed ints, negated and put
 * above its index, so that the smallest key is the first in rank and its low half the index.
 */
internal fun rankKeys(v: FloatArray, m: Int, keys: LongArray) {
    for (i in 0 until m) {
        val bits = java.lang.Float.floatToIntBits(v[i])
        val sortable = bits xor ((bits shr 31) and 0x7fffffff)
        keys[i] = (-sortable.toLong() shl 32) or i.toLong()
    }
}

/** Makes the first [m] of [a] a binary min-heap. */
internal fun heapify(a: LongArray, m: Int) { for (i in m / 2 - 1 downTo 0) siftDown(a, i, m) }

/** Takes the smallest of min-heap [a]'s first [m] off it (its last moves up into its place) and returns it. */
internal fun popMin(a: LongArray, m: Int): Long {
    val top = a[0]; a[0] = a[m - 1]; siftDown(a, 0, m - 1)
    return top
}

private fun siftDown(a: LongArray, from: Int, m: Int) {
    var i = from; val x = a[i]
    while (true) {
        var c = 2 * i + 1; if (c >= m) break
        if (c + 1 < m && a[c + 1] < a[c]) c++
        if (a[c] >= x) break
        a[i] = a[c]; i = c
    }
    a[i] = x
}

/** [forage]'s reaping of wild or sown grain at place [p], [expected] kg a party could take: [out] gets the kilos it is worth, and weighs. */
private fun GroupWorld.reap(g: HexGroup, p: Place, expected: Float, out: Haul) {
    val band = g.band
    // (Sown fields are reaped first: their grain stands thick in one plot; then the wild stand.)
    val food = fauna.food
    val want = land.spread(p, Kg(expected)).v
    val fromField = food.take(food.fieldGrain, p, KgM2(want)).v
    val fromWild = food.take(food.grain, p, KgM2(want - fromField)).v
    val taken = land.total(p, KgM2(fromField + fromWild)).v
    val took = taken * seedValue(band) * (band?.cookValue(Food.GRAIN) ?: 1f)
    out.kg = took; out.mass = taken
    // A sickle takes the ears that hold their grain more than those that shatter at a touch. What is reaped from a sown
    // field is kept as seed corn; what is reaped from the wild stand goes with the rest of the grain.
    if (band != null && took > 0f) {
        fun harvested(d: Float) = band.reapedDomestic(d)
        val tookField = took * fromField / (fromField + fromWild).coerceAtLeast(1e-12f); val tookWild = took - tookField
        // (The week's reaping is kept with the stores at the week's end, where the seed corn is held to the grain kept.)
        val corn = band.seedCorn; val wild = max(0f, g.stores[Store.SEED] - corn)
        if (tookField > 0f) band.seedDomestic = (band.seedDomestic * corn + harvested(food.fieldDomestic[p]) * tookField) / (corn + tookField)
        if (tookWild > 0f) band.grainDomestic = (band.grainDomestic * wild + harvested(food.domestic[p]) * tookWild) / (wild + tookWild)
        band.seedCorn = corn + tookField
        stats.hunting.merge(Tally.of(g.species, Tally.OfKind.GRAIN_REAPED), took.toInt(), Int::plus)
    }
}

/** Person-days to reap, thresh and clean a kilo of grain or grass seed: [SEED_HANDLING_DAYS_PER_KG] by hand, less with a sickle and grinding stones. */
internal fun reapDaysPerKg(band: Band?, tuning: inflation.Tuning) =
    SEED_HANDLING_DAYS_PER_KG * tuning.plantHandling / (1f + (band?.sickleGain(tuning) ?: 0f) + GRINDER_GAIN * (band?.has(Tool.GRINDER) ?: 0f))

/** Of grain whose ears hold their grain at share [d], the share in what band [b] reaps: a sickle takes them more than those that shatter at a touch. */
internal fun Band.reapedDomestic(d: Float): Float { val sel = SICKLE_SELECT * has(Tool.SICKLE); return d * (1f + sel) / (1f + sel * d).coerceAtLeast(1e-6f) }

/** [forage]'s count of the resident bands [g] came on unseen ([met] per animal, by place of its hunting [ground] and kind) in the [searched] share of its week. */
private fun GroupWorld.countResidentsMet(g: HexGroup, ground: HuntGround, met: FloatArray, searched: Float, today: Double) {
    val nk = fauna.kinds.size
    val counts = FloatArray(Catalogue.all.size)
    for (j in 0 until ground.size) {
        val p = ground.place[j]
        val swept = ground.km2[j]
        for (k in 0 until nk) counts[fauna.kinds[k].index] += (fauna.adults[k][p] + fauna.young[k][p]) * swept * met[j * nk + k] * searched
        if (HUNT_DIAG) for (k in 0 until nk) { val ps = fauna.kinds[k]
            diag(ps, HuntStep.RESIDENT_BANDS_MET, (fauna.adults[k][p] + fauna.young[k][p]) * swept * (0.5f + 0.5f * Niches.overlap(g.species, ps)) / ps.groupSize * searched) }
    }
    if (HUNT_DIAG) for (k in 0 until nk) diag(fauna.kinds[k], HuntStep.RESIDENT_BANDS_MET_UNSEEN, counts[fauna.kinds[k].index])
    g.metGame(counts, today)
}

/**
 * [forage]'s gathering of plant food [t] (fruit, roots of [kind]: a band's bulbs and roots, or its [MAST_KIND], an animal's
 * both, grass seed or small finds) at place [p], [expected] kg a party could take: [out] gets the kilos it is worth once seed
 * is ground and food cooked or leached, what they weigh, and the party-days the leaching took ([hands] at it).
 */
private fun GroupWorld.gather(g: HexGroup, t: Food, kind: Int, p: Place, expected: Float, hands: Int, out: Haul) {
    val s = g.species; val band = g.band
    out.busy = 0f
    val food = fauna.food
    val stock = when (t) { Food.FRUIT -> food.fruit; Food.ROOTS -> if (kind == MAST_KIND) food.mast else food.roots; Food.SEED -> food.seeds; else -> food.finds }
    fun take(kg: KgM2) = if (t == Food.ROOTS && band == null) food.takeUnderground(p, kg) else food.take(stock, p, kg)
    val taken = land.total(p, take(land.spread(p, Kg(expected)))).v
    var took = taken; var mass = taken
    // Raw seed is poorly digested; ground and baked into bread it feeds fully.
    if (t == Food.SEED) took *= seedValue(band)
    if (band != null && took > 0f) {
        // Earth ovens and gruel make more of it; bands that know how leach bitter plants too.
        val cook = band.cookValue(t)
        if (cook > 1f) { took *= cook; band.practise(if (t == Food.ROOTS) Skill.EARTH_OVEN else Skill.STONE_BOILING) }
        if (t != Food.FINDS) {
            var extra = took * band.detoxExtra(land.palatable[p], land.drink.water[p] >= Thresholds.WET || land.drink.waterKm[p] < 3f)
            // (What is leached is more of the plants, bitter before: taken from them, as far as there is any.)
            if (extra > 0f) {
                val more = land.total(p, take(land.spread(p, Kg(extra * taken / took)))).v
                extra = more * took / taken; mass += more
            }
            if (extra > 0f) {
                took += extra
                out.busy = extra * LEACH_DAYS_PER_KG / hands
                band.practise(Skill.LEACHING)
                stats.hunting.merge(Tally.of(s, Tally.OfKind.PLANTS_LEACHED), extra.toInt(), Int::plus)
            }
        }
    }
    out.kg = took; out.mass = mass
}

/** The kind of [Food.FISH] offer that is shellfish gathered off the shore (0 is fish caught: see [offerFoods]). */
internal const val SHELLFISH_KIND = 1
/** The kind of [Food.FISH] offer that is fish taken from the traps of a weir ([Built.WEIR]). */
internal const val WEIR_KIND = 2
/** The kind of [Food.FISH] offer that is the spring run's fish taken from the bank ([HexWater.runWithinReach]). */
internal const val RUN_KIND = 3
/**
 * The kind of a band's [Food.ROOTS] offer that is the fallen mast (0 is bulbs and roots dug: see [offerFoods]), picked up as
 * fruit is picked ([BAND_FRUIT_DAYS_PER_KG]): pine nuts in the Great Basin gave some 840-1,400 kcal/h (Simms 1987, from memory).
 */
internal const val MAST_KIND = 1

/**
 * The grease a band renders from a kg of bones it gathers, kg: bones smashed small and boiled with hot stones, the fat skimmed
 * as it rises ([Skill.STONE_BOILING], in tight baskets or hides). Experiments find most of it comes in the first hour of
 * boiling, and the yield rises with how small the bones are broken (Church & Lyman 2003); the yield a kg here is a guess
 * (no figure a kg of bone found yet), and so are the days.
 */
internal fun greaseOf(band: Band) = if (band.knowsSkill[Skill.STONE_BOILING])
    GREASE_YIELD * band.skill(Skill.STONE_BOILING) * max(band.has(Tool.BASKET), 0.5f) else 0f
internal const val GREASE_YIELD = 0.05f
/** Person-days to smash and boil a kg of bones for their grease, with the fuel to heat the stones (a guess: "many hours"). */
internal const val BONE_RENDER_DAYS = 0.2f

private val FORAGE_DIAG = Config.string(Config.Key.FORAGE_DIAG)

/**
 * Whether group [g]'s week is one of the [share] sampled for the forage diagnostic: drawn from a hash of the group
 * and the day, not from the group's random stream, so that turning the diagnostic on does not change the run.
 */
private fun diagSampled(g: HexGroup, weather: Weather, share: Float, salt: Int) =
    unitOf(mix64(g.id * HASH_A + weather.date.absoluteDay * HASH_B + salt * HASH_C)) < share

/**
 * How much of the usual loss band [band]'s dried meat and fish suffer: smoked keeps longer than sun-dried, salted longer still,
 * and dried for longer ([Band.dryDepth]) longer in proportion (drier meat keeps longer; the proportion is an estimate).
 */
internal fun GroupWorld.driedKeep(band: Band): Float {
    val smoked = if (band.knowsSkill[Skill.SMOKING]) 1f - SMOKE_KEEP * band.skill(Skill.SMOKING) else 1f
    return smoked * (1f - tuning.saltKeep * band.salted) / max(1f, band.dryDepth)
}

/**
 * The food a whole house would have kept from spoiling in [g]'s stores this week against the houses it has ([home]), less what
 * mice would have taken from its pits: kg of need, by [spoil]'s rates and the mice's. For [Band.houseFood].
 */
internal fun GroupWorld.keepWorth(g: HexGroup, b: Band, home: Float): Float {
    val more = 1f - home
    if (more <= 0f) return 0f
    var kept = 0f
    for (st in Store.entries) if (st != Store.FRESH)
        kept += g.stores[st] * min(1f, st.lostWeekly * (if (st == Store.DRIED) driedKeep(b) else 1f) * days / 7f) * PIT_KEEP * more
    val sealed = if (b.knowsSkill[Skill.SEALING]) 1f - SEAL_KEEP * b.skill(Skill.SEALING) else 1f
    for (st in listOf(Store.SEED, Store.FRUIT, Store.ROOTS)) kept -= g.stores[st] * MICE * sealed * more * days / 7f
    return kept
}

/** Spoils a week of [stores] at [t] °C, each kind at its own rate; returns kg lost (weighed at [kgPer] for each kg of worth). */
internal fun GroupWorld.spoil(stores: EnumFloats<Store>, t: Float, keep: Float = 1f, driedKeep: Float = 1f, kgPer: EnumFloats<Store>? = null): Float {
    var lost = 0f
    val warm = if (t <= 0f) 0f else min(2.8f, exp(0.069f * (t - 15f)))
    for (st in Store.entries) {
        val share = if (st == Store.FRESH) 1f - exp(-HexSmallLife.CARRION_ROT * warm * days) else min(1f,
            st.lostWeekly * keep * (if (st == Store.DRIED) driedKeep else 1f) * days / 7f)
        val x = stores[st] * share
        stores[st] -= x; lost += x * (kgPer?.get(st) ?: 1f)
    }
    return lost
}

/** [forage]'s practice for band [band] of [g] at what it did this week (and its tally of fish). */
private fun GroupWorld.practise(g: HexGroup, band: Band, hunted: Boolean, smallGot: Float, plants: Float, fish: Float) {
    if (hunted) band.practise(Skill.HUNT)
    if (smallGot > 0f) band.practise(Skill.SMALL_GAME)
    if (plants > 0f) { band.practise(Skill.GATHER); learnPlants(band, hw.placeHex[g.place]) }
    if (fish > 0f) band.practise(Skill.FISH)
    if (band.fire > 0f) band.practise(Skill.ROASTING)
    stats.hunting.merge(Tally.of(g.species, Tally.OfKind.FISH), fish.toInt(), Int::plus)
}

/** [g]'s band [band]'s food this week by where it came from, and how its fishing paid, into its running means over about a year. */
private fun GroupWorld.recordFood(g: HexGroup, band: Band, w: ForageWeek) {
    val k = 1f / stepsPerYear; val per = 1f / (max(1, g.size) * days)
    fun add(f: FoodFrom, kg: Float) { band.foodFrom[f] += k * (kg * per - band.foodFrom[f]) }
    add(FoodFrom.PLANTS, w.plants); add(FoodFrom.SMALL_GAME, w.smallGot); add(FoodFrom.FISH, w.fish)
    add(FoodFrom.BIG_GAME, w.meat + w.killFat); add(FoodFrom.CARRION, w.scraps)
    band.fishPull += k * (w.fishPull - band.fishPull)
    // (Whether a weir would pay goes with the season's fish, so it is this week's, not a running worth.)
    band.weirPull = w.weirPull
}

/** [forage]'s scavenging of [expected] kg at place [p] by [g], over [sweep] km²: what it found, kg. */
private fun HexGroups.scavenge(g: HexGroup, p: Place, expected: Float, sweep: Float, rnd: kotlin.random.Random): Float {
    val band = g.band
    val got = small.scavengeIn(p, Kg(expected), min(land.areaKm2[p], sweep)).v
    // A band at a carcass may meet its owner: lions or hyenas back at their kill. Driving
    // them off is done with a crowd and a din, but now and then someone is mauled.
    if (band != null && got > 1f) {
        val beasts = monthBeastKg[p] / land.areaKm2[p]
        if (rnd.nextFloat() < SCAVENGE_RISK * beasts / (beasts + 3f)) {
            band.wounded(1f, g.adults)
            stats.meeting(Met.BANDS_MAULED_AT_A_CARCASS)
        }
    }
    return got
}

/** Chance a band scavenging a carcass where hunters are thick has one of its people mauled by them. */
internal const val SCAVENGE_RISK = 0.01f

/** Share of a carcass a hunter takes as food: a band as its butchers' skill allows (all the fat and marrow, at best). */
internal fun meatShare(band: Band?) = if (band == null) MEAT_SHARE else min(MEAT_MOST, MEAT_SHARE * band.edge(Skill.BUTCHER))
/**
 * Share of a kill's weight that counts towards a hunter's need: all of it for a beast, whose need is reckoned in the weight
 * of the prey it kills, the parts it leaves included ([Allometry.dailyIntakeKg]); for a band, what its butchers make of it.
 */
internal fun creditShare(band: Band?) = if (band == null) 1f else meatShare(band)
/**
 * For the books: [credit] kg added to [hunter]'s meat in hand (taken off when below zero) from a kill of [kg] that left
 * [carrion] kg; a beast holds only [meatShare] of what it is credited, so the rest of a kill credited whole goes nowhere.
 */
internal fun GroupWorld.bookKill(hunter: HexGroup, credit: Float, kg: Float, carrion: Float) {
    land.books?.leak(Books.Stuff.MATTER, "hunters' kills credited at more or less than they weigh (young, rivals, theft)",
        (credit * meatShare(hunter.band) / creditShare(hunter.band) + carrion - kg).toDouble())
}
/** What is left of a kill of [ps] as carrion when the hunter took [kgEach] of it towards its need. */
internal fun leftOver(ps: Species, kgEach: Float, band: Band?) = max(0f, ps.massKg - kgEach * meatShare(band) / creditShare(band))
internal const val MEAT_MOST = 0.7f

/** What a kilo of reaped seed or grain feeds against a kilo of other food: raw, little; ground and baked, fully. */
internal fun seedValue(band: Band?) = RAW_SEED + (1f - RAW_SEED) * (band?.has(Tool.GRINDER) ?: 0f)
internal const val RAW_SEED = 0.6f
/** Beer: grain brewed for each guest at a feast, kg; how much more a feast with beer binds. */
internal const val BEER_KG = 1f
internal const val BEER_TIES = 0.5f

/** Share of the ground a party searches for small game at place [p] over which it finds it: less in standing cover ([SMALL_HIDE]). */
internal fun GroupWorld.smallSeen(p: Place) = 1f - SMALL_HIDE * land.plants.groundCover(p)

/** How readily [s] catches small game it comes on, 0..1: the smaller and nimbler the hunter, the better; cover hides the prey from all but stalkers. */
internal fun smallCatch(s: Species) = SMALL_CATCH * min(1f, SMALL_HUNTER_KG / s.massKg)

/**
 * The food [band] works to lay by this week: while its kit is worn it holds back from storing as far as its way of
 * mending first goes ([Band.mendFirst]: the scaffold's 1 stores a tenth of it, mending in the time the rest would take).
 */
internal fun GroupWorld.storeWork(g: HexGroup, band: Band) = if (band.worn()) storeWant(g, band) * (1f - band.mendFirst) else storeWant(g, band)

/**
 * What band [g] works this week to lay by towards the stores it aims to keep (its choice), kg: a share
 * of what it lacks. (A band holding to demand sharing stores less: what it lays by is asked of it.)
 */
internal fun storeWant(g: HexGroup, band: Band) = storeWant(g, band, band.storeAim)

/** [storeWant] were its store aim [aimDays]. */
private fun storeWant(g: HexGroup, band: Band, aimDays: Float) =
    max(0f, aimDays * (if (band.holds(Tradition.SHARING)) SHARING_STORE else 1f) * g.dailyNeedKg - g.larder) * STORE_FILL

/** Counts in [u] how band [b]'s store aim and its mending first set the food [work] it works to lay by ([storeWork]). */
private fun countStoreWork(u: DialUse, g: HexGroup, b: Band, work: Float) {
    val worn = b.worn()
    val mendAt1 = if (b.worn(MEND_BELOW)) 0f else storeWant(g, b)
    u.amount(DialSite.STORE_WANT, b.storeShare, work, if (worn) storeWant(g, b, b.storeAimAt1) * (1f - b.mendFirst) else storeWant(g, b, b.storeAimAt1))
    if (worn) u.amount(DialSite.MENDING_FIRST, b.will[BandWay.MENDING_FIRST], work, mendAt1)
}

/**
 * The odds that game of kind [ps] sees the hunters first and keeps out of their way: likelier in the [open] (the less
 * cover, and against a courser than a stalker) and in [clear] weather ([openness]), and against [band]s for game bred
 * to fear people over the millennia.
 */
internal fun seenFirst(open: Float, clear: Float, band: Band?, ps: Species): Float {
    val fear = if (band != null) 0.5f + ps.fearOfPeople else 1f
    return min(0.95f, open * fear * clear)
}

/** [seenFirst]'s parts at place [p] for hunter [s]: how open the ground is to a hunter of its way, and how clear the weather and cover leave the view. */
private fun GroupWorld.openness(s: Species, p: Place, weather: Weather, out: FloatArray) {
    val cover = land.plants.cover(p)
    out[0] = VIGILANCE * (1f - cover) * (1f - s.stalking)
    out[1] = 0.5f + 0.5f * sight(p, weather) / (1f - SIGHT_COVER * cover).coerceAtLeast(0.1f)
}

/**
 * The odds that one try at a band of game ends in a kill, once the hunter has come on it unseen: as [catchable] as it
 * is there, and by a band's [edge] over it ([Band.huntEdge]; 1 for a beast of prey).
 */
internal fun killChance(catchable: Float, edge: Float): Float = CATCH * SURPRISE * catchable * edge
