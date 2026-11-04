package inflation.ecology

import inflation.Config
import inflation.EnumFloats
import inflation.enumFloats
import inflation.Trace
import inflation.world.Date
import inflation.world.Weather
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A [Mind.PLANS] band's week's days (doc: Discrete planning: build spec, stage 2), in place of the ways-weighted share-out
 * of today's chore asks ([BandTime.plan]'s weights): after the walk, the essentials held first by the survival pass
 * ([survivalDays]: the week's food, the fire, shelters and clothes) and its herders' days, as for every band, the rest of
 * its week goes in tiers ([PlanTier]):
 * - what its customs owe ([PlanTier.OBLIGED]: rites and burials, keeping clean and nursing where it holds to them, its ornaments), not
 *   scored in lives: belief and custom decide them, and what cannot be paid strains them as now;
 * - what it holds by lives saved this year ([PlanTier.SCORED], survival now; doc: Needs tiers for band choices): the stores
 *   ([planRates]), as far as they ask, while they save any;
 * - then what it holds by the days a work saves it and its heirs over the years it serves them ([PlanTier.GENERATIONS],
 *   security over generations): the houses, weirs and tools ([toolRates]), with the days the stores left, the one that
 *   saves the most days for its days first, each as far as it asks, while it saves more days than it takes;
 * - what has no reckoning in lives yet ([PlanTier.UNRECKONED]: training, materials, drying, skins, caching,
 *   sowing, palisade), shared out at equal weights of what is left, no dials;
 * - and what is left is its leisure ([PlanTier.LEISURE]), held as such, so no chore takes it.
 * The shelters beyond what the survival pass gave are worth no lives by its own reckoning, and get none. Each ask is
 * today's ([choreAsks]), the ways at the scaffold's ([Band.scaffoldWays]): the ask is the most an item is offered, not
 * what it gets. [needs], [warm] and [kept] are what the plan holds first, [asks] today's chore asks (the stores' in
 * [Activity.FOOD]); [perDay], kg of need a person-day's foraging brings this week. Returns the days of work to hold for each
 * activity beyond those held first, [Activity.LEISURE]'s included, for [BandTime.plan].
 */
internal fun HexGroups.planDays(g: HexGroup, b: Band, weather: Weather, time: BandTime, needs: EnumFloats<Activity>, asks: EnumFloats<Activity>,
    warm: EnumFloats<Activity>, kept: EnumFloats<Activity>, perDay: Float): EnumFloats<Activity> {
    val given = enumFloats<Activity>()
    // (People's days, as [BandTime.plan] reckons them: work at each activity's pace.)
    fun days(a: Activity, work: Float) = work / time.pace(a)
    var left = time.left
    for (a in Activity.entries) left -= days(a, needs[a] + warm[a] + kept[a])
    left = max(0f, left)
    // (The viewer's Plan tab, for the followed band: the week's reckoning as it goes, [DaysLook].)
    val look = if (looked(g)) DaysLook(g.id, weather.date, g.size, time.total).also { l ->
        l.walkAndWater = time.total - time.left
        for (a in Activity.entries) l.essentials[a.ordinal] = days(a, needs[a] + warm[a] + kept[a])
        l.afterEssentials = left
    } else null
    // (The player's picks, for the followed band with the controls open: hold or drop an item whatever its reckoning, [PlanPick].)
    val ctl = if (look != null) control else null
    fun pick(i: PlanItem) = (ctl?.planPick(g, i) ?: PlanPick.MODEL).also { p -> look?.let { it.picks[i.ordinal] = p } }
    // What its customs owe.
    val owed = floatArrayOf(if (pick(PlanItem.CUSTOMS) == PlanPick.DROP) 0f else days(Activity.CUSTOMS, asks[Activity.CUSTOMS]),
        if (pick(PlanItem.ORNAMENTS) == PlanPick.DROP) 0f else days(Activity.ORNAMENTS, asks[Activity.ORNAMENTS]))
    val paid = BandTime.shareOut(owed, floatArrayOf(1f, 1f), left)
    given[Activity.CUSTOMS] = paid[0] * time.pace(Activity.CUSTOMS); given[Activity.ORNAMENTS] = paid[1] * time.pace(Activity.ORNAMENTS)
    left -= paid.sum()
    look?.let { it.customsAsked = owed[0]; it.customsPaid = paid[0]; it.ornamentsAsked = owed[1]; it.ornamentsPaid = paid[1] }
    // Its ripe crop, reaped as it ripens: food in hand, before anything reckoned. (Not asked among the chores: [harvestCrop]
    // takes what days are free, and a planning band holds its leisure, so none were.)
    val reap = min(cropRipe(g, b) * cropReapDays(b), left * time.pace(Activity.SOWING))
    left -= reap / time.pace(Activity.SOWING)
    // What it holds by lives saved, tier by tier: survival now (the stores), then over generations (the houses and weirs,
    // the one that saves the most days for its days first), each as far as it asks this week ([planRates]); a tier has
    // what the one below it left.
    val rate = planRates(g, b, weather, perDay, asks[Activity.HOUSES] > 0f, asks[Activity.WEIRS] > 0f, look)
    val acts = SCORED_ACTS
    val ask = FloatArray(SCORED_ITEMS) { asks[acts[it]] }
    // (Its tools: each it reckons worth making ([toolRates]) asks its days, the rest none, beside the clothes the survival pass gave.)
    // (Its grinding stones first by the births their gruel adds, in lives ([gruelRate]); if they add none, by their days
    // with its other tools.)
    gruelRate(g, b, look).let { (r, a) -> rate[GRUEL] = r; ask[GRUEL] = a }
    toolRates(g, b, perDay, skip = if (rate[GRUEL] > 0f) Tool.GRINDER else null, look = look).let { (r, a) -> rate[TOOLS] = r; ask[TOOLS] = a }
    // (Its sowing by the days its crop saves, less what living against its way of life would cost it: [sowRate]. Its ask
    // for the sowing activity holds the reaping of its ripe crop too ([choreAsks]), given above: the sowing is the rest.)
    ask[SOW] = max(0f, ask[SOW] - cropRipe(g, b) * cropReapDays(b))
    rate[SOW] = sowRate(g, b, perDay, ask[SOW], weather)
    val d = FloatArray(SCORED_ITEMS)
    // (The stores and the gruel while they save any lives; a house, a weir or its tools when they save more days than they take.)
    val order = (0 until LIVES_ITEMS).toList() + (LIVES_ITEMS until SCORED_ITEMS).sortedByDescending { rate[it] }
    // (A pick of the player's: held whatever its rate, or dropped; held items first of their tier.)
    val picked = Array(SCORED_ITEMS) { if (ctl != null) pick(SCORED_PICKS[it]) else PlanPick.MODEL }
    val weighed = if (ctl == null) order else order.sortedBy { if (picked[it] == PlanPick.HOLD) (if (it < LIVES_ITEMS) 0 else 1) else (if (it < LIVES_ITEMS) 0 else 2) }
    for (i in weighed) {
        if (picked[i] == PlanPick.DROP) continue
        if ((picked[i] != PlanPick.HOLD && rate[i] <= (if (i < LIVES_ITEMS) SAVES_NONE * g.size else 1f)) || left <= 0f) continue
        d[i] = min(ask[i], left * time.pace(acts[i])); left -= d[i] / time.pace(acts[i])
    }
    // (Added: the grinding stones and the other tools are both tools' days.)
    for (i in 0 until SCORED_ITEMS) given[acts[i]] += d[i]
    given[Activity.SOWING] += reap
    look?.let { l ->
        for (i in 0 until SCORED_ITEMS) l.items += ItemLook(SCORED_PICKS[i], SCORED_NAMES[i], if (i < LIVES_ITEMS) "lives" else "days", ask[i] / time.pace(acts[i]), rate[i],
            if (i < LIVES_ITEMS) SAVES_NONE * g.size else 1f, d[i] / time.pace(acts[i]), order.indexOf(i), l.pending[i])
    }
    left = max(0f, left)
    // What has no reckoning in lives yet, at equal weights.
    // (And its clothes beyond the days the survival pass gave them, as they were before tools were reckoned: kept up, not
    // weighed, the last entry. They are made first of its tools' days, [makeTools].)
    val clothes = days(Activity.TOOLS, max(0f, makeTools(b, g.adults.toFloat(), null, only = Tool.CLOTHES) - warm[Activity.TOOLS]))
    val rest = FloatArray(UNRECKONED_ACTS.size + 1) { if (it < UNRECKONED_ACTS.size) days(UNRECKONED_ACTS[it], asks[UNRECKONED_ACTS[it]]) else clothes }
    // (The player's picks: a dropped chore asks none; a held one is given first, as far as it asks, the rest shared after.)
    val held = FloatArray(rest.size)
    if (ctl != null) for (i in rest.indices) when (pick(UNRECKONED_PICKS[i])) {
        PlanPick.DROP -> rest[i] = 0f
        PlanPick.HOLD -> { held[i] = min(rest[i], left); left -= held[i]; rest[i] -= held[i] }
        PlanPick.MODEL -> {}
    }
    // (Each as far as the days left at most: an ask far beyond them, as materials' year of need can be, would leave the
    // share-out's weighing too fine for a float, and the others nothing.)
    val shared = BandTime.shareOut(FloatArray(rest.size) { min(rest[it], left) }, FloatArray(rest.size) { 1f }, left)
    for (i in rest.indices) { shared[i] += held[i]; rest[i] += held[i]; left += held[i] }
    for (i in UNRECKONED_ACTS.indices) given[UNRECKONED_ACTS[i]] = shared[i] * time.pace(UNRECKONED_ACTS[i])
    given[Activity.TOOLS] += shared[UNRECKONED_ACTS.size] * time.pace(Activity.TOOLS)
    left = max(0f, left - shared.sum())
    given[Activity.LEISURE] = left * time.pace(Activity.LEISURE)
    look?.let { l ->
        for (i in rest.indices) l.unreckoned += ChoreLook(UNRECKONED_PICKS[i], if (i < UNRECKONED_ACTS.size) UNRECKONED_ACTS[i].label else PlanItem.CLOTHES.label, rest[i], shared[i])
        l.leisure = left
        daysLook = l
    }
    // (For the census: the days of each tier, and the lives the scored days were reckoned to save.)
    fun add(t: PlanTier, v: Float) { stats.tally(Tally.planTier(t), (10f * v).roundToInt()) }
    add(PlanTier.OBLIGED, paid.sum()); add(PlanTier.SCORED, (0 until LIVES_ITEMS).sumOf { (d[it] / time.pace(acts[it])).toDouble() }.toFloat())
    add(PlanTier.GENERATIONS, (LIVES_ITEMS until SCORED_ITEMS).sumOf { (d[it] / time.pace(acts[it])).toDouble() }.toFloat())
    add(PlanTier.UNRECKONED, shared.sum()); add(PlanTier.LEISURE, left)
    add(PlanTier.ASKED_BEYOND, max(0f, rest.sum() - shared.sum()))
    add(PlanTier.STORES_ASKED, days(Activity.FOOD, ask[0])); add(PlanTier.STORES_HELD, days(Activity.FOOD, d[0]))
    add(PlanTier.HOUSES_ASKED, days(Activity.HOUSES, ask[HOUSES])); add(PlanTier.HOUSES_HELD, days(Activity.HOUSES, d[HOUSES]))
    add(PlanTier.WEIRS_ASKED, days(Activity.WEIRS, ask[WEIRS])); add(PlanTier.WEIRS_HELD, days(Activity.WEIRS, d[WEIRS]))
    add(PlanTier.TOOLS_ASKED, days(Activity.TOOLS, ask[GRUEL] + ask[TOOLS])); add(PlanTier.TOOLS_HELD, days(Activity.TOOLS, d[GRUEL] + d[TOOLS]))
    add(PlanTier.GRUEL_ASKED, days(Activity.TOOLS, ask[GRUEL])); add(PlanTier.GRUEL_HELD, days(Activity.TOOLS, d[GRUEL]))
    add(PlanTier.SOWING_ASKED, days(Activity.SOWING, ask[SOW])); add(PlanTier.SOWING_HELD, days(Activity.SOWING, d[SOW]))
    add(PlanTier.REAPING_HELD, days(Activity.SOWING, reap))
    stats.tally(Tally.planTier(PlanTier.LIVES), (1e4f * (d[0] * rate[0] + d[GRUEL] * rate[GRUEL])).roundToInt())
    return given
}

/**
 * The lives a day of work at each of [planDays]' scored items saves of band [b]'s (group [g]'s) people through the year ahead,
 * by the sim's own forecast ([yearAhead], [YearAhead.alive]), each reckoned over the whole of what it is for and shared
 * out over the days the whole takes:
 * - the stores ([Activity.FOOD] beyond the week's food): filling its larder to the stores it aims at ([Band.storeAimAt1]:
 *   its lean days foreseen and a margin), at [perDay] kg a day of work; by how often its larder runs out over years made of
 *   its own past weeks ([storesAlive]) once it has a year of them, by the year ahead before;
 * - houses ([Activity.HOUSES]), in days, not lives: the food a whole house saves a year (as the band lived its weeks,
 *   [Band.houseFood]: keeping warm, stores kept from spoiling, less to raiders and mice; or at the place's usual cold,
 *   [houseFoodAYear], if more) over the years it and its heirs expect to keep the place ([keepYears]), as the days of foraging
 *   it would take at [perDay], over a whole house's days ([HOUSE_DAYS] a person): given days when it saves more than it
 *   takes. (Over its usual stay a house was reckoned over a few months to a year: built for the next season, not for the
 *   next generation; Olli, 2026-10-31. In lives it was worth none: a fed band foresees no deaths, and the year's usual cold
 *   kills none behind clothes and fire; house trace, 2026-10-31. What it would save of a cold snap is not weighed.)
 * (Reckoned a week's work at a time, from what it has, the first days of either did nothing: a larder a few days fuller
 * leaves a lean spell that burns past the body's protein as deadly, and a house's food came in as a week's sliver of it.
 * The forecast foresaw as much hunger as came, and more (ab461c, 2026-10-30), yet the stores were given 2 to 3% of their
 * ask, the houses none (ab461b). A whole is worth what it saves; the band works towards it a week at a time.)
 * Not foreseen, as in [yearAhead]: the larder spoiling or raided, and the year beyond the next. ([houses]: whether it asks
 * for days for houses; the houses are reckoned only then.)
 */
private fun HexGroups.planRates(g: HexGroup, b: Band, weather: Weather, perDay: Float, houses: Boolean, weirs: Boolean, look: DaysLook? = null): FloatArray {
    val year = yearAhead(g, b, weather)
    val need = g.dailyNeedKg.coerceAtLeast(1e-3f)
    val people = g.size.toFloat()
    fun alive(kg: Float) = people * year.alive(g.species, (g.larder + kg) / need, g.fat, g.spent)
    val now = alive(0f)
    val rate = FloatArray(SCORED_ITEMS)
    val gap = b.storeAimAt1 * need - g.larder
    if (gap > 0f && perDay > 0f) {
        // (By its own weeks as they came where it has a year of them ([storesAlive]); else by the year ahead.)
        val was = storesAlive(g, b, weather, g.larder)
        val filled = if (was != null) storesAlive(g, b, weather, g.larder + gap) else null
        rate[0] = (if (was != null && filled != null) people * (filled - was) else alive(gap) - now) / (gap / perDay)
        look?.let { l ->
            val s = 0
            l.note(s, "reckoned by", if (was != null && filled != null) "its own past weeks (how often its larder ran out)" else "the year-ahead forecast")
            l.note(s, "people alive through the year, as it stands", "%.3f".format(if (was != null) people * was else now))
            l.note(s, "... with the stores filled", "%.3f".format(if (was != null && filled != null) people * filled else alive(gap)))
            l.note(s, "days of work to fill them", "%.1f".format(gap / perDay))
        }
    }
    look?.let { l ->
        val s = 0
        l.note(s, "larder", "%.1f days of need".format(g.larder / need))
        l.note(s, "store aim", "%.1f days (lean %.1f)".format(b.storeAimAt1, b.leanDays))
        l.note(s, "gap to fill", "%.1f days of need".format(max(0f, gap) / need))
        l.note(s, "kg of need a person-day of foraging brings", "%.2f".format(perDay))
        l.note(s, "fat, burnt", "%.1f, %.1f".format(g.fat, g.spent))
    }
    if (PLAN_TRACE.wants(g.id.toLong(), weather.date.absoluteDay)) {
        val larderDays = g.larder / need
        System.err.println(("plan trace: band %d, %s, %d people, larder %.1f days, fat %.1f, burnt %.1f, store aim %.1f days (lean %.1f), " +
            "gap %.1f days, %.2f kg a person-day, alive %.4f now, %.4f filled, stores %.2g lives a person-day\n").format(g.id, weather.date, g.size,
            larderDays, g.fat, g.spent, b.storeAimAt1, b.leanDays, max(0f, gap) / need, perDay, now / people, if (gap > 0f) alive(gap) / people else now / people, rate[0]) +
            " as it stands:\n" + year.trace(g.species, larderDays, g.fat, g.spent) +
            (if (gap > 0f) " stores filled:\n" + year.trace(g.species, larderDays + gap / need, g.fat, g.spent) else ""))
    }
    // (The viewer: whether its houses may ask days at all ([buildHouse]'s gates), and the houses that stand here and who uses them.)
    look?.let { l -> houseGates(g, b).forEach { (k, v) -> l.note(HOUSES, k, v) } }
    if (houses) {
        val years = keepYears(b, stepsPerYear)
        // (The food a whole house would have saved it a person a year as it lived its weeks ([Band.houseFood]: keeping warm,
        // stores kept from spoiling, less to raiders and to mice), or at the place's usual cold if more ([houseFoodAYear]:
        // a band new to a place has lived few weeks there).)
        val kg = max(b.houseFood, houseFoodAYear(g, b, g.place, weather)) * people * years
        // (Tier 2 is weighed in days, not lives: a whole house's days against the days of foraging the food it saves over
        // the years kept would take, at this week's [perDay]. In lives a house was worth none: house trace, 2026-10-31.)
        val saved = if (perDay > 0f) kg / perDay else 0f
        rate[HOUSES] = saved / (HOUSE_DAYS * g.adults.coerceAtLeast(1))
        look?.let { l ->
            val s = HOUSES
            l.note(s, "camp kept", "%d steps; at home %b; houses %.0f%% built".format(b.campWeeks, b.atHome, 100 * b.house))
            l.note(s, "years it expects to keep the place", "%.2f".format(years))
            l.note(s, "food a whole house saves a person a year", "%.1f kg (as lived %.1f, usual cold %.1f)".format(kg / years.coerceAtLeast(1e-6f) / people.coerceAtLeast(1f), b.houseFood, houseFoodAYear(g, b, g.place, weather)))
            l.note(s, "over the years", "%.0f kg = %.0f days of foraging at %.2f kg a person-day".format(kg, saved, perDay))
            l.note(s, "days to build a whole house", "%.0f".format(HOUSE_DAYS * g.adults.coerceAtLeast(1)))
        }
        // (planTrace: what a house was reckoned at.)
        if (HOUSE_TRACE.wants(g.id.toLong(), weather.date.absoluteDay))
            System.err.println(("house trace: band %d, %s, %d people (%d grown), camp kept %d steps, at home %b, houses %.2f, reckoned kept %.2f y; " +
                "a whole house a person a year: %.1f kg (as lived %.1f, usual cold %.1f); over the years: %.0f kg, %.0f days of foraging at %.2f kg a person-day, against %.0f days to build: %.2f " +
                "(given days at 1 or more)").format(g.id, weather.date, g.size, g.adults, b.campWeeks, b.atHome, b.house, years, kg / years / people, b.houseFood, houseFoodAYear(g, b, g.place, weather),
                kg, saved, perDay, HOUSE_DAYS * g.adults.coerceAtLeast(1), rate[HOUSES]))
    }
    look?.let { l ->
        val h = hw.placeHex[g.place]
        l.note(WEIRS, "weir standing here", "%.0f%% of the river's width whole, %.0f%% with what is begun (%.0f m wide); that it may use".format(
            100 * weirAt(g, h), 100 * weirAt(g, h, begun = true), fauna.water.riverWidthM(h)))
    }
    if (weirs) {
        // (A whole weir across the river by its camp: the days of foraging the food it would take a year as the band lived
        // ([Band.weirFood]) would have taken at [perDay], less the days its traps want ([Band.weirTend]) and its mending
        // ([WEIR_WEAR] of it a year), over the years it and its heirs expect to keep the place, against its building.)
        val whole = weirLengthM(hw.placeHex[g.place]) * WEIR_DAYS_PER_M
        val years = keepYears(b, stepsPerYear)
        val aYear = (if (perDay > 0f) b.weirFood / perDay else 0f) - b.weirTend - WEIR_WEAR * whole
        rate[WEIRS] = if (whole > 0f) aYear * years / whole else 0f
        look?.let { l ->
            val s = WEIRS
            l.note(s, "days to build a whole weir", "%.0f".format(whole))
            l.note(s, "years it expects to keep the place", "%.2f".format(years))
            l.note(s, "a year as lived", "%.0f kg of need = %.0f days of foraging".format(b.weirFood, if (perDay > 0f) b.weirFood / perDay else 0f))
            l.note(s, "less tending, mending a year", "%.0f, %.0f days".format(b.weirTend, WEIR_WEAR * whole))
            l.note(s, "days saved a year", "%.1f".format(aYear))
        }
        if (WEIR_TRACE.wants(g.id.toLong(), weather.date.absoluteDay))
            System.err.println(("weir trace: band %d, %s, %d people, camp kept %d steps, reckoned kept %.2f y; a whole weir %.0f days to build; " +
                "a year as lived: %.0f kg of need (%.0f days of foraging at %.2f kg a person-day), %.0f days tending, %.0f mending: %.2f " +
                "(given days at 1 or more)").format(g.id, weather.date, g.size, b.campWeeks, years, whole, b.weirFood,
                if (perDay > 0f) b.weirFood / perDay else 0f, perDay, b.weirTend, WEIR_WEAR * whole, rate[WEIRS]))
    }
    return rate
}

/**
 * The years band [b] and its heirs can expect to keep the place they have kept for [Band.campWeeks] steps ([perYear] a
 * year), up to a generation ([GENERATION_YEARS]): as long as they have kept it is as likely as not, and the longer kept, the
 * longer still (Gott 1993: kept T years, still there after t more as T / (T + t)), summed over a generation:
 * T ln(1 + G / T). A band half a year at a camp reckons 2 years; 2 years, 5; 10 years, 12.5. (Gott's rule ignores the time
 * scales of what it is applied to, Caves 2000; here the generation bounds it. A split's heirs keep the weeks, so a lineage
 * counts its own.)
 */
internal fun keepYears(b: Band, perYear: Float): Float {
    val kept = (b.campWeeks + 1f) / perYear
    return kept * ln(1f + GENERATION_YEARS / kept)
}

/**
 * The tiers of a [Mind.PLANS] band's week beyond its essentials ([planDays]), for the census, person-days ([LIVES]: the
 * lives its scored days were reckoned to save, ten-thousandths; [ASKED_BEYOND]: the unreckoned chores' asks it could not give;
 * the stores' and the houses' asks and what of them it held by lives).
 */
enum class PlanTier(val label: String) {
    OBLIGED("owed by custom"), SCORED("held by lives this year"), GENERATIONS("held by days saved over generations"), UNRECKONED("no reckoning yet"), LEISURE("leisure"),
    ASKED_BEYOND("unreckoned asks not given"), LIVES("lives reckoned saved, 1/10000"),
    STORES_ASKED("stores asked"), STORES_HELD("stores held"), HOUSES_ASKED("houses asked"), HOUSES_HELD("houses held"),
    WEIRS_ASKED("weirs asked"), WEIRS_HELD("weirs held"),
    TOOLS_ASKED("tools asked"), TOOLS_HELD("tools held"), GRUEL_ASKED("grinders for gruel asked"), GRUEL_HELD("grinders for gruel held"),
    SOWING_ASKED("sowing asked"), SOWING_HELD("sowing held"), REAPING_HELD("reaping held")
}

/** planTrace=true: a sample of planning bands' weeks, how the year ahead reckons their stores ([planRates]), on stderr. */
internal val PLAN_TRACE = Trace(Config.Key.PLAN_TRACE, "plan", share = 0.01f, most = 400)
/** planTrace=true as well: what a house, a weir and a tool were reckoned at. */
private val HOUSE_TRACE = Trace(Config.Key.PLAN_TRACE, "house", share = 0.02f, most = 400)
private val WEIR_TRACE = Trace(Config.Key.PLAN_TRACE, "weir", share = 0.2f, most = 400)
private val TOOL_TRACE = Trace(Config.Key.PLAN_TRACE, "tool", share = 0.02f, most = 400)
private val SOW_TRACE = Trace(Config.Key.PLAN_TRACE, "sow", share = 0.005f, most = 400)

/** The chores with no reckoning in lives yet ([planDays]): shared out at equal weights. */
private val UNRECKONED_ACTS = arrayOf(Activity.TRAINING, Activity.MATERIALS, Activity.DRYING, Activity.SKINS, Activity.CACHING,
    Activity.PALISADE)
/**
 * The chores [planDays] holds by what they save ([planRates], [gruelRate], [toolRates], [sowRate]): the stores and the
 * grinding stones for gruel in lives (the first [LIVES_ITEMS]), then houses, weirs, tools and sowing in days.
 */
private val SCORED_ACTS = arrayOf(Activity.FOOD, Activity.TOOLS, Activity.HOUSES, Activity.WEIRS, Activity.TOOLS, Activity.SOWING)
private val SCORED_ITEMS = SCORED_ACTS.size
/** The scored items held by lives: the stores, then the grinding stones by their gruel. */
private const val LIVES_ITEMS = 2
/** The scored items by their place in [SCORED_ACTS]: the grinding stones by their gruel ([gruelRate]), houses, weirs, tools by their days. */
private const val GRUEL = 1
private const val HOUSES = 2
private const val WEIRS = 3
private const val TOOLS = 4
private const val SOW = 5

/**
 * Band [b]'s (group [g]'s) sowing in days (tier 2, as houses, weirs and tools, [planDays]): the [ask] days sow its seed
 * ([SOW_DAYS_PER_KG]), and the crop grows [HexFood.FIELD_YIELD] times the seed, as food by its kit ([seedValue], cooked):
 * the days of foraging at [perDay] the food beyond the seed would take, less the days to reap it ([reapDaysPerKg], as thick as
 * a field ripened in full stands), and less
 * what living against its way of life would cost it ([livingAgainstDays]) where it holds another than farming; against the
 * days of sowing; the crop as its stores would keep it ([cropKept]). (Not weighed: a crop that fails or that others reap, the
 * field yield as it went before.)
 */
private fun HexGroups.sowRate(g: HexGroup, b: Band, perDay: Float, ask: Float, weather: Weather): Float {
    if (ask <= 0f || perDay <= 0f) return 0f
    val sown = ask / SOW_DAYS_PER_KG
    val raw = sown * g.storeKgPer[Store.SEED]
    // (The crop is eaten through the year till the next harvest, so kept half a year on the mean: less what its stores would
    // lose of it in that time, where it keeps them now, [cropKept].)
    val food = raw * HexFood.FIELD_YIELD * seedValue(b) * b.cookValue(Food.GRAIN) * cropKept(b) - sown
    val reap = raw * HexFood.FIELD_YIELD * reapDaysPerKg(b, tuning, HexFood.FIELD_YIELD * HexFood.SEED_RATE)
    val against = if (b.heldLivelihood != Livelihood.FARMING) livingAgainstDays(g, b) else 0f
    // (Grain is a store, eaten when foraging is poor: its food as days of foraging in the month a day's foraging brought it
    // least, as a herd's is ([herdPays], [Band.forageReturn]); this week's with no month on record yet.)
    val leanDay = min(perDay, b.forageReturn.filter { it >= 0f }.minOrNull()?.takeIf { it > 0f } ?: perDay)
    val rate = (food / leanDay - reap - against) / ask
    if (SOW_TRACE.wants(g.id.toLong(), weather.date.absoluteDay))
        System.err.println(("sow trace: band %d, %s, %d people, holds %s (firmly %.2f), lives %s; sows %.0f kg in %.0f days; crop beyond the seed %.0f kg of need " +
            "(%.0f days of foraging at %.2f kg a person-day in its leanest month), %.0f days to reap, living against its way %.0f days (raid deaths %.3f a person a year): %.2f (given days at 1 or more)")
            .format(g.id, weather.date, g.size, b.heldLivelihood.name.lowercase(), b.livelihoodHold, b.livelihood().name.lowercase(), raw, ask, food,
                food / leanDay, leanDay, reap, against, b.raidDeathRate, rate))
    return rate
}

/**
 * The share of a harvest band [b] would still have to eat after keeping it half a year ([CROP_KEPT_WEEKS]), as its grain
 * keeps where it keeps it now: its damp and mould ([Store.SEED]) and the mice ([SEED_RODENTS]), less in the storage pits of
 * a house at home ([PIT_KEEP], [seedRodents]), and the insects in its stores as infested as they are now (at 15 °C, [infest]).
 */
internal fun cropKept(b: Band): Float {
    val home = if (b.atHome) b.house else 0f
    val weekly = Store.SEED.lostWeekly * (1f - PIT_KEEP * home) + SEED_RODENTS * seedRodents(home, b.sealedLetsThrough()) +
        Store.SEED.pestWeekly * b.pests * (1f - home * (1f - b.sealedLetsThrough()))
    return (1f - weekly.coerceIn(0f, 1f)).pow(CROP_KEPT_WEEKS)
}
/** Weeks a harvest is kept on the mean, eaten evenly through the year till the next one. */
private const val CROP_KEPT_WEEKS = 26f

/**
 * What band [b] (group [g]) reckons living against the way of life it holds would cost it, in days of work: seen to keep
 * none ([Band.shownLivelihood]), it is one custom further apart ([CUSTOMS]) from the bands of its own way, who fall on it the
 * readier ([Regard.fightChance]: [CUSTOMS_HOSTILITY] of a custom's share more at most); its raid deaths, as it has lived
 * them ([Band.raidDeathRate]), that much more, each a person's working generation of days ([DEATH_DAYS]), over the years it
 * expects to keep the place ([keepYears]). (Not weighed: partners less readily exchanged, [Regard.weds], and trust.)
 */
private fun HexGroups.livingAgainstDays(g: HexGroup, b: Band) =
    b.raidDeathRate * g.size * CUSTOMS_HOSTILITY / CUSTOMS * DEATH_DAYS * keepYears(b, stepsPerYear)

/** Person-days a death costs a band: a person's working generation ([GENERATION_YEARS] of days). An estimate. */
private const val DEATH_DAYS = GENERATION_YEARS * 365f
/** [SCORED_ACTS]' items by name, for the viewer ([ItemLook]). */
private val SCORED_NAMES = arrayOf("stores", "grinding stones for gruel", "houses", "weirs", "tools", "sowing")
/** [SCORED_ACTS]' and [UNRECKONED_ACTS]' items (the clothes last) as the player may pick them ([PlanItem]). */
private val SCORED_PICKS = arrayOf(PlanItem.STORES, PlanItem.GRUEL, PlanItem.HOUSES, PlanItem.WEIRS, PlanItem.TOOLS, PlanItem.SOWING)
private val UNRECKONED_PICKS = arrayOf(PlanItem.TRAINING, PlanItem.MATERIALS, PlanItem.DRYING, PlanItem.SKINS, PlanItem.CACHING,
    PlanItem.PALISADE, PlanItem.CLOTHES)
/** Fewer lives than this a person are none saved: the rounding in the reckoning, not a difference. */
private const val SAVES_NONE = 1e-6f

/**
 * What a [Mind.PLANS] band's tools served a year as it lived its weeks ([Band.toolWork]): days at plant work and of them at
 * seed and grain; and the seed and grain it got, kg of its need as its kit made them ([seedValue]).
 */
enum class ToolWork { PLANT_DAYS, SEED_DAYS, SEED_FOOD }

/**
 * Band [b]'s (group [g]'s) tools in days (tier 2, as houses and weirs, [planDays]): for each tool it knows and lacks of a
 * whole kit, the days of work a whole kit would save it a year as it lived its weeks ([Band.toolWork]) over the years a
 * kit lasts ([Tool.wearPerYear]; a tool too heavy to carry, as long as it keeps the place, [keepYears], if less), against
 * the days a whole kit takes to make ([Tool.makeDays] a person, by its craft's skill). Each saves what the sim has it do:
 * - a basket speeds all plant work (more carried a trip), a sickle and grinding stones the seed and grain
 *   ([reapDaysPerKg]): the days at that work less those it would take with a whole kit;
 * - spears and bows make more stalks end in a kill ([Band.huntEdge]), nets sweep more ground for small game and take
 *   more fish ([Band.snares], [Band.fishing]), grinding stones make more of the seed and grain it got ([seedValue]): the
 *   more food it would have got, as days of foraging at [perDay].
 * (Grinding stones that their gruel holds in lives are weighed there, not here ([gruelRate], [skip]). Not weighed: spears and bows against beasts; the pick, for
 * wells, which no band yet builds. The clothes are the survival pass's ([survivalDays]). A bow's gain is taken at half its most, for
 * game of middling speed.)
 * Marks the tools that save more days than they take in [Band.toolPays] (it makes and mends those alone), and returns
 * their days saved over their days ([planDays]' rate) and the days they ask.
 */
private fun HexGroups.toolRates(g: HexGroup, b: Band, perDay: Float, skip: Tool? = null, look: DaysLook? = null): Pair<Float, Float> {
    val people = g.adults.toFloat()
    val wk = b.toolWork
    // (Its kills' meat and fat, and its small game and fish, kg a year, as it lived ([Band.foodFrom], kept in a save).)
    val perYear = g.size * Date.DAYS_PER_YEAR.toFloat()
    val meatKg = b.foodFrom[FoodFrom.BIG_GAME] * perYear
    val netKg = (b.foodFrom[FoodFrom.SMALL_GAME] + b.foodFrom[FoodFrom.FISH]) * perYear
    var saved = 0f; var cost = 0f; var asked = 0f
    for (t in Tool.entries) {
        if (t == skip) continue
        b.toolPays[t] = false
        if (!b.knows[t] || t == Tool.CLOTHES || t == Tool.PICK || people <= 0f) continue
        val k = b.has(t)
        if (k >= 1f) continue
        // (A year's days saved by a whole kit against the kit it has.)
        fun speeds(days: Float, gain: Float) = days * (1f - (1f + gain * k) / (1f + gain))
        fun more(kg: Float, now: Float, whole: Float) = if (perDay > 0f && now > 0f) kg * (whole / now - 1f) / perDay else 0f
        val aYear = when (t) {
            Tool.BASKET -> speeds(wk[ToolWork.PLANT_DAYS], BASKET_GAIN)
            Tool.SICKLE -> speeds(wk[ToolWork.SEED_DAYS], SICKLE_GAIN)
            Tool.GRINDER -> speeds(wk[ToolWork.SEED_DAYS], GRINDER_GAIN) + more(wk[ToolWork.SEED_FOOD], seedValue(b), 1f)
            Tool.SPEAR -> more(meatKg, WEAPONLESS + (1f - WEAPONLESS) * k, 1f)
            Tool.BOW -> more(meatKg, 1f + 0.5f * BOW_GAIN * k, 1f + 0.5f * BOW_GAIN)
            Tool.NET -> more(netKg, 1f + NET_GAIN * k, 1f + NET_GAIN)
        }
        val lasts = (1f / t.wearPerYear).let { if (t.heavy) min(it, keepYears(b, stepsPerYear)) else it }
        val whole = t.makeDays * people / b.makeSkill(t).coerceAtLeast(1e-3f)
        val over = aYear / (1f - k) * lasts / whole
        if (TOOL_TRACE.wants(g.id.toLong(), b.campWeeks.toLong(), part = t.ordinal.toLong()))
            System.err.println(("tool trace: band %d, %s, %d grown, kit %.2f; a year as lived: %.0f plant days, %.0f seed days, %.0f kg of need from seed, %.0f kg meat, " +
                "%.0f kg small game and fish; a whole kit saves %.1f days a year, lasts %.1f y, takes %.0f days to make: %.2f (made at 1 or more)").format(
                g.id, t.name, g.adults, k, wk[ToolWork.PLANT_DAYS], wk[ToolWork.SEED_DAYS], wk[ToolWork.SEED_FOOD], meatKg, netKg,
                aYear / (1f - k), lasts, whole, over))
        look?.let { it.tools += ToolLook(t, k, aYear / (1f - k), lasts, whole, over, over >= 1f) }
        if (over < 1f) continue
        b.toolPays[t] = true
        saved += aYear * lasts; cost += (1f - k) * whole
        asked += makeTools(b, people, null, only = t)
    }
    look?.let { l ->
        val s = TOOLS
        l.note(s, "a year as lived", "%.0f plant days, %.0f seed days, %.0f kg of need from seed".format(wk[ToolWork.PLANT_DAYS], wk[ToolWork.SEED_DAYS], wk[ToolWork.SEED_FOOD]))
        l.note(s, "meat; small game and fish, kg a year", "%.0f; %.0f".format(meatKg, netKg))
        l.note(s, "the tools that pay, together", "%.0f days saved over their life for %.0f days of making".format(saved, cost))
    }
    return (if (cost > 0f) saved / cost else 0f) to asked
}

/**
 * Band [b]'s (group [g]'s) grinding stones by the births their gruel adds (tier 1, in lives, beside the stores: [planDays]):
 * a whole kit's gruel ([Band.gruel]) weans its children early as far as its seed goes ([WEANING_SHARE] of its need, against
 * the seed it got a year as it lived, [ToolWork.SEED_FOOD]), so its mothers bear the sooner ([birthSpacing]). The births a
 * year a whole kit would add against the kit it has, each counted a life, over the years the kit lasts (as long as it
 * keeps the place, [keepYears], a kit too heavy to carry), against the days the rest of a kit takes to make
 * ([Tool.makeDays] a person, by its craft's skill). Marks the grinding stones in [Band.toolPays] when they add any lives;
 * returns lives a day of work and the days they ask. (A band that leaves its grinding stones on a move so reckons making
 * them again at its new camp; doc: Moving and settling under the planner.)
 */
private fun HexGroups.gruelRate(g: HexGroup, b: Band, look: DaysLook? = null): Pair<Float, Float> {
    val t = Tool.GRINDER
    val people = g.adults.toFloat()
    val k = b.has(t)
    if (!b.knows[t] || k >= 1f || people <= 0f) return 0f to 0f
    val seed = min(1f, b.toolWork[ToolWork.SEED_FOOD] / (WEANING_SHARE * g.dailyNeedKg * Date.DAYS_PER_YEAR).coerceAtLeast(1e-3f))
    val perMother = g.species.youngPerFemale * HUMAN_BIRTHS * tuning.humanBirths
    val births = mothersIn(g) * perMother * (birthSpacing(b, b.gruel(1f) * seed) - birthSpacing(b, b.gruel(k) * seed))
    val lasts = min(1f / t.wearPerYear, keepYears(b, stepsPerYear))
    val whole = (1f - k) * t.makeDays * people / b.makeSkill(t).coerceAtLeast(1e-3f)
    val rate = births * lasts / whole
    look?.let { l ->
        val s = GRUEL
        l.note(s, "kit", "%.0f%% of a whole kit".format(100 * k))
        l.note(s, "seed for gruel", "%.0f%% of what weaning wants".format(100 * seed))
        l.note(s, "births a year a whole kit adds", "%.3f".format(births))
        l.note(s, "lasts, days to make the rest", "%.1f y, %.0f days".format(lasts, whole))
    }
    if (rate <= SAVES_NONE * g.size) return 0f to 0f
    b.toolPays[t] = true
    return rate to makeTools(b, people, null, only = t)
}
