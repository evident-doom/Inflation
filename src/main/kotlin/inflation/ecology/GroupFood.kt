package inflation.ecology

import inflation.Config
import inflation.ecology.HexSmallLife.DeadPool
import inflation.enumFloats
import inflation.Kg
import inflation.Par
import inflation.Part
import inflation.Profile
import inflation.Stream
import inflation.stream
import inflation.Trace
import inflation.world.Date
import inflation.world.get
import inflation.world.Hex
import inflation.world.set
import inflation.world.Weather
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Hunting and foraging along the way: what a group gets, eats, stores and keeps of its week's food.

/**
 * Hunters (and bands) feed along their way: the resident game of every place they passed, small game,
 * carrion, and for those that eat them plants, and for bands fish (see [forage]).
 */
@Part("hunting: set-up")
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
    // Then region by region on all cores: the map is cut into squares coloured in a 3 by 3 pattern of nine
    // colours, so that squares of one colour lie two squares apart; the squares of one colour are worked at
    // once. Parties whose week kept within a square of where they ended it are worked first in squares
    // of [HUNT_FINE_KM] (two such parties in squares of one colour cannot have passed the same ground); the
    // few that ranged further after them, in squares of [HUNT_BLOCK_KM], wider than any party's week.
    // (Nine small squares rather than four of twice the width: a crowded place is shared out over more of them,
    // so one square's parties do not keep the other cores waiting.)
    fun work(parties: List<HexGroup>, squareKm: Float) {
        val blocks = HashMap<Int, ArrayList<HexGroup>>()
        for (g in parties) {
            val (x, y) = hex.centreKm(hw.placeHex[g.place])
            blocks.getOrPut((x / squareKm).toInt() + 1000 * (y / squareKm).toInt()) { ArrayList() } += g
        }
        for (colour in 0 until 9) {
            // (The squares with the most parties first, so the slowest is not left to last on one core.)
            val squares = blocks.entries.filter { (b, _) -> (b % 1000) % 3 + 3 * ((b / 1000) % 3) == colour }
                .sortedByDescending { e -> e.value.sumOf { g -> if (g.band != null) 4 * g.path.size else g.path.size } }
            Par.forEach("foraging: squares", squares.size) { i ->
                for (g in squares[i].value) {
                    val s = g.species
                    // The ground it searches this week, shared over the hexes it passed.
                    val sweep = searchKm2(g, g.place, weather) / g.path.size.coerceAtLeast(1)
                    val t0 = Profile.mark()
                    Profile.kind("foraging", if (s.human) "bands" else s.name) { forage(g, sweep, max(1, g.adults) * s.massKg, weather) }
                    Profile.slow(if (s.human) "forage: band" else "forage: " + s.name, t0, weather.date.absoluteDay) { g.probe(weather.date.absoluteDay) }
                }
            }
        }
    }
    val (near, far) = hunters.partition { g ->
        val end = hw.placeHex[g.place]
        // (Its hunting ground reaches a hex beyond its way, see [huntGround].)
        g.path.all { h -> hex.distanceKm(end, h) + 2 * hex.hexKm < HUNT_FINE_KM }
    }
    stats.summedByThread { work(near, HUNT_FINE_KM); work(far, HUNT_BLOCK_KM) }
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
@Part("foraging: one group")
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
        // (A beast's party keeps no plan of its days: each pass has only the days the passes before it left.)
        else if (w.band == null) f = min(f, max(0f, w.workDays - w.busy - w.searchUsed) / w.workDays.coerceAtLeast(1e-6f))
        val busy0 = w.busy
        if (pass == 0) w.searched = f
        if (HUNT_DIAG && w.band == null) { w.passes++; w.searchDays += f * w.workDays }
        if (FORAGE_PASS_TRACE.wants(g.id.toLong(), weather.date.absoluteDay, w.s.name)) System.err.println(
            "forage ${w.s.name} adults ${g.adults} want ${w.wantAll} left $left offers ${o.m} used ${o.used} gain ${o.gain.toList()} spent ${o.spent.toList()} f $f: " +
            o.order.take(min(6, o.m)).joinToString("; ") { "t${o.type[it].ordinal} k${o.kind[it]} r=${o.rate[it]} e=${o.kg[it]} h=${o.time[it]}" })
        takeFoods(w, o, f)
        // (A beast's search took the days of the pass not spent handling: [Offers.spent] is days a day of searching asks.)
        if (w.band == null) w.searchUsed += f * w.workDays / o.spent[0]
        time?.spend(Activity.FOOD, (w.busy - busy0) * w.hands)
    }
    if (w.band != null && w.time == null) planWeek(w, 0f, 0f, 0f)
    // (What a day's foraging brought it this week, kg of its need, kept month by month as a mean over the years: what a
    // herder's day must bring to be worth it, [herdPays].)
    w.band?.let { b -> w.time?.spentOn(Activity.FOOD)?.takeIf { it > 0f }?.let { d ->
        val got = w.fed / d; val m = weather.date.month; val was = b.forageReturn[m]
        b.forageReturn[m] = if (was < 0f) got else was + min(1f, years * Date.MONTHS_PER_YEAR) * (got - was)
    } }
    if (WARM_DIAG.on) w.band?.let { b -> w.time?.let { b.diagFoodSpent = it.spentOn(Activity.FOOD) } }
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
internal fun HexGroups.planWeek(w: ForageWeek, needDays: Float, storeDays: Float, perDay: Float) {
    val g = w.g; val b = checkNotNull(w.band); val hands = w.hands
    // (What a day of all its hands' foraging brings, in days of its people's need: for the reckoned move, [Band.weekYield].)
    b.yieldNow = perDay * hands / g.dailyNeedKg.coerceAtLeast(1e-3f)
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
    for (a in Activity.entries) b.heldShare[a] = if (time.total > 0f) time.heldFor(a) / time.total else 0f
    w.room = time.leftFor(Activity.FOOD) / time.pace(Activity.FOOD)
    // (What its fire, shelters and food leave of the week, kept month by month as a mean over the years: what its
    // leanest month could give its herders, [keepHerds].)
    run {
        val free = if (time.total > 0f) max(0f, time.left - time.vital) / time.total else 0f
        val m = w.weather.date.month; val was = b.freeShare[m]
        b.freeShare[m] = if (was < 0f) free else was + min(1f, years * Date.MONTHS_PER_YEAR) * (free - was)
    }
    if (WARM_DIAG.on) b.diagFoodHeld = time.heldFor(Activity.FOOD)
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
internal class ForageWeek(gs: HexGroups, val g: HexGroup, val sweep: Float, packKg: Float, val weather: Weather) {
    val s = g.species
    val d = s.diet
    val band = g.band
    // (Its own stream of chance, so that regions can be worked at once.)
    val rnd = stream(gs.seedBase, Stream.FORAGE, g.id.toLong(), weather.date.absoluteDay)
    val hands = forageHands(g).also { h -> gs.dialUse?.let { u -> band?.let { b -> countHands(u, g, b, h) } } }
    // A band splits: its hunters go after big game, searching all the days they are out, while the rest
    // gather, fish and snare (see [HUNTERS_SHARE]); an animal's party forages as one.
    val hunt = band?.let { b -> gs.dialed(DialSite.HUNTERS_SHARE, b.effort[Food.BIG_GAME], huntersShare(b),
        cut = HUNTERS_SHARE * b.effort[Food.BIG_GAME] > 1f) { HUNTERS_SHARE.coerceIn(0f, 1f) } } ?: 1f
    val gatherHands = if (band != null) hands * (1f - hunt) else hands.toFloat()
    // (Walking costs food, see [walkCost]; a band's days at rest take less, see [restedDays].)
    var wantAll = g.dailyNeedKg * gs.days * (1f + gs.walkCost(g))
    val eats = 1f / (packKg * EATING_RATE) // days to eat a kilo
    /**
     * The most a beast's party can eat in the week, kg: each of it eating at [EATING_RATE] of its weight every day of it,
     * its young at half a grown one as they need half ([HexGroup.dailyNeedKg]); gorging at a kill, see [eatTheWeek].
     */
    val gutKg = s.massKg * (g.adults + 0.5f * g.young) * EATING_RATE * gs.days
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
    val roundShare = band?.let { b -> gs.dialed(DialSite.GATHER_RING, b.gatherRange, RING_REACH * b.gatherRange) { RING_REACH } } ?: 0f
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
    // (A whole weir there this week: what it would take, kg of its need, and the days its traps would want: for [Band.weirFood].)
    var weirFood = 0f; var weirTend = 0f
    // (The band's days at gathering plant food this week, and of them at seed and grain: what its tools speed, for [Band.toolWork].)
    var plantWorkDays = 0f; var seedWorkDays = 0f
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
    /** A beast's days spent searching in the passes so far (as [busy] holds those spent handling): the passes share its work days. */
    var searchUsed = 0f
    /** Diagnostic (huntSteps), a beast's week: the passes it planned, the days they gave it to search, and the kills of big game it could expect in them. */
    var passes = 0
    var searchDays = 0f
    var killsExpected = 0f
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
 * Counts for the census how a band's foraging ([w], its week's [time]) fared against the days its plan left it: the
 * people-days it spent, those past what the plan left it, and what kills beyond and short of those expected, extra hauling
 * and digging soaks took. Counts only.
 */
private fun HexGroups.countFoodTime(w: ForageWeek, time: BandTime) {
    val pace = time.pace(Activity.FOOD); val hands = w.hands / pace
    val spent = time.spentOn(Activity.FOOD) - w.digDays / pace
    val over = max(0f, spent - w.room)
    fun add(k: Tally.FoodTime, x: Float) { stats.tally(Tally.foodTime(k), (10f * x).roundToInt()) }
    add(Tally.FoodTime.WEEKS, 0.1f); add(Tally.FoodTime.ROOM, w.room); add(Tally.FoodTime.SPENT, spent); add(Tally.FoodTime.OVER, over)
    if (over > 0.05f * w.room) add(Tally.FoodTime.OVER_WEEKS, 0.1f)
    add(Tally.FoodTime.KILLS_OVER, w.killOver * hands); add(Tally.FoodTime.KILLS_UNDER, w.killUnder * hands)
    add(Tally.FoodTime.HAUL, w.haulOver * hands); add(Tally.FoodTime.DIG, w.digDays / pace)
}

/** forageDiag=kind: a sample of that kind's groups' weeks, each pass of their forage and what they ate, on stderr. */
private val FORAGE_PASS_TRACE = Trace(Config.Key.FORAGE_DIAG, "forage passes", share = 0.002f, byKind = true)

internal val FORAGE_MEAL_TRACE = Trace(Config.Key.FORAGE_DIAG, "forage meals", share = 0.003f, byKind = true)

/** [forage]'s practice for band [band] of [g] at what it did this week (and its tally of fish). */
internal fun GroupWorld.practise(g: HexGroup, band: Band, hunted: Boolean, smallGot: Float, plants: Float, fish: Float) {
    if (hunted) band.practise(Skill.HUNT)
    if (smallGot > 0f) band.practise(Skill.SMALL_GAME)
    if (plants > 0f) { band.practise(Skill.GATHER); learnPlants(band, hw.placeHex[g.place]) }
    if (fish > 0f) band.practise(Skill.FISH)
    if (band.fire > 0f) band.practise(Skill.ROASTING)
    stats.tally(Tally.of(g.species, Tally.OfKind.FISH), fish.toInt())
}

/** [g]'s band [band]'s food this week by where it came from, and how its fishing paid, into its running means over about a year. */
internal fun GroupWorld.recordFood(g: HexGroup, band: Band, w: ForageWeek) {
    val k = 1f / stepsPerYear; val per = 1f / (max(1, g.size) * days)
    fun add(f: FoodFrom, kg: Float) { band.foodFrom[f] += k * (kg * per - band.foodFrom[f]) }
    add(FoodFrom.PLANTS, w.plants); add(FoodFrom.SMALL_GAME, w.smallGot); add(FoodFrom.FISH, w.fish)
    add(FoodFrom.BIG_GAME, w.meat + w.killFat); add(FoodFrom.CARRION, w.scraps)
    band.fishPull += k * (w.fishPull - band.fishPull)
    // (Whether a weir would pay goes with the season's fish, so it is this week's, not a running worth.)
    band.weirPull = w.weirPull
    // (And what a whole weir by its camps would have taken and wanted tended as it lived its weeks, a year: [Band.weirFood].)
    band.weirFood += k * (w.weirFood * stepsPerYear - band.weirFood)
    band.weirTend += k * (w.weirTend * stepsPerYear - band.weirTend)
    // (And what its tools served, a year as it lived its weeks: [Band.toolWork].)
    fun work(t: ToolWork, v: Float) { band.toolWork[t] += k * (v * stepsPerYear - band.toolWork[t]) }
    // (Days a hand, as the week's busy days are: the band's are its hands'.)
    work(ToolWork.PLANT_DAYS, w.plantWorkDays * w.hands); work(ToolWork.SEED_DAYS, w.seedWorkDays * w.hands)
    work(ToolWork.SEED_FOOD, w.plantFrom[PlantFrom.SEED] + w.plantFrom[PlantFrom.GRAIN])
}
