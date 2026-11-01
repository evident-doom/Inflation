package inflation.ecology

import inflation.Config
import inflation.EnumFloats
import inflation.HASH_A
import inflation.HASH_B
import inflation.enumFloats
import inflation.mix64
import inflation.unitOf
import inflation.world.Date
import inflation.world.Weather
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
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
    // What its customs owe.
    val owed = floatArrayOf(days(Activity.CUSTOMS, asks[Activity.CUSTOMS]), days(Activity.ORNAMENTS, asks[Activity.ORNAMENTS]))
    val paid = BandTime.shareOut(owed, floatArrayOf(1f, 1f), left)
    given[Activity.CUSTOMS] = paid[0] * time.pace(Activity.CUSTOMS); given[Activity.ORNAMENTS] = paid[1] * time.pace(Activity.ORNAMENTS)
    left -= paid.sum()
    // What it holds by lives saved, tier by tier: survival now (the stores), then over generations (the houses and weirs,
    // the one that saves the most days for its days first), each as far as it asks this week ([planRates]); a tier has
    // what the one below it left.
    val rate = planRates(g, b, weather, perDay, asks[Activity.HOUSES] > 0f, asks[Activity.WEIRS] > 0f)
    val acts = SCORED_ACTS
    val ask = FloatArray(SCORED_ITEMS) { asks[acts[it]] }
    // (Its tools: each it reckons worth making ([toolRates]) asks its days, the rest none, beside the clothes the survival pass gave.)
    toolRates(g, b, perDay).let { (r, a) -> rate[3] = r; ask[3] = a }
    val d = FloatArray(SCORED_ITEMS)
    // (The stores while they save any lives; a house, a weir or its tools when they save more days than they take.)
    val order = listOf(0) + (1 until SCORED_ITEMS).sortedByDescending { rate[it] }
    for (i in order) {
        if (rate[i] <= (if (i == 0) SAVES_NONE * g.size else 1f) || left <= 0f) continue
        d[i] = min(ask[i], left * time.pace(acts[i])); left -= d[i] / time.pace(acts[i])
    }
    for (i in 0 until SCORED_ITEMS) given[acts[i]] = d[i]
    left = max(0f, left)
    // What has no reckoning in lives yet, at equal weights.
    // (And its clothes beyond the days the survival pass gave them, as they were before tools were reckoned: kept up, not
    // weighed, the last entry. They are made first of its tools' days, [makeTools].)
    val clothes = days(Activity.TOOLS, max(0f, makeTools(b, g.adults.toFloat(), null, only = Tool.CLOTHES) - warm[Activity.TOOLS]))
    val rest = FloatArray(UNRECKONED_ACTS.size + 1) { if (it < UNRECKONED_ACTS.size) days(UNRECKONED_ACTS[it], asks[UNRECKONED_ACTS[it]]) else clothes }
    // (Each as far as the days left at most: an ask far beyond them, as materials' year of need can be, would leave the
    // share-out's weighing too fine for a float, and the others nothing.)
    val shared = BandTime.shareOut(FloatArray(rest.size) { min(rest[it], left) }, FloatArray(rest.size) { 1f }, left)
    for (i in UNRECKONED_ACTS.indices) given[UNRECKONED_ACTS[i]] = shared[i] * time.pace(UNRECKONED_ACTS[i])
    given[Activity.TOOLS] += shared[UNRECKONED_ACTS.size] * time.pace(Activity.TOOLS)
    left = max(0f, left - shared.sum())
    given[Activity.LEISURE] = left * time.pace(Activity.LEISURE)
    // (For the census: the days of each tier, and the lives the scored days were reckoned to save.)
    fun add(t: PlanTier, v: Float) { stats.tally(Tally.planTier(t), (10f * v).roundToInt()) }
    add(PlanTier.OBLIGED, paid.sum()); add(PlanTier.SCORED, d[0] / time.pace(acts[0])); add(PlanTier.GENERATIONS, (1 until SCORED_ITEMS).sumOf { (d[it] / time.pace(acts[it])).toDouble() }.toFloat())
    add(PlanTier.UNRECKONED, shared.sum()); add(PlanTier.LEISURE, left)
    add(PlanTier.ASKED_BEYOND, max(0f, rest.sum() - shared.sum()))
    add(PlanTier.STORES_ASKED, days(Activity.FOOD, ask[0])); add(PlanTier.STORES_HELD, days(Activity.FOOD, d[0]))
    add(PlanTier.HOUSES_ASKED, days(Activity.HOUSES, ask[1])); add(PlanTier.HOUSES_HELD, days(Activity.HOUSES, d[1]))
    add(PlanTier.WEIRS_ASKED, days(Activity.WEIRS, ask[2])); add(PlanTier.WEIRS_HELD, days(Activity.WEIRS, d[2]))
    add(PlanTier.TOOLS_ASKED, days(Activity.TOOLS, ask[3])); add(PlanTier.TOOLS_HELD, days(Activity.TOOLS, d[3]))
    stats.tally(Tally.planTier(PlanTier.LIVES), (1e4f * d[0] * rate[0]).roundToInt())
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
private fun HexGroups.planRates(g: HexGroup, b: Band, weather: Weather, perDay: Float, houses: Boolean, weirs: Boolean): FloatArray {
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
    }
    if (PLAN_TRACE && unitOf(mix64(g.id * HASH_A + weather.date.absoluteDay * HASH_B + PLAN_TRACE_SALT)) < 0.01f &&
        planTraced.incrementAndGet() <= PLAN_TRACE_MOST) {
        val larderDays = g.larder / need
        System.err.println(("plan trace: band %d, %s, %d people, larder %.1f days, fat %.1f, burnt %.1f, store aim %.1f days (lean %.1f), " +
            "gap %.1f days, %.2f kg a person-day, alive %.4f now, %.4f filled, stores %.2g lives a person-day\n").format(g.id, weather.date, g.size,
            larderDays, g.fat, g.spent, b.storeAimAt1, b.leanDays, max(0f, gap) / need, perDay, now / people, if (gap > 0f) alive(gap) / people else now / people, rate[0]) +
            " as it stands:\n" + year.trace(g.species, larderDays, g.fat, g.spent) +
            (if (gap > 0f) " stores filled:\n" + year.trace(g.species, larderDays + gap / need, g.fat, g.spent) else ""))
    }
    if (houses) {
        val years = keepYears(b, stepsPerYear)
        // (The food a whole house would have saved it a person a year as it lived its weeks ([Band.houseFood]: keeping warm,
        // stores kept from spoiling, less to raiders and to mice), or at the place's usual cold if more ([houseFoodAYear]:
        // a band new to a place has lived few weeks there).)
        val kg = max(b.houseFood, houseFoodAYear(g, b, g.place, weather)) * people * years
        // (Tier 2 is weighed in days, not lives: a whole house's days against the days of foraging the food it saves over
        // the years kept would take, at this week's [perDay]. In lives a house was worth none: house trace, 2026-10-31.)
        val saved = if (perDay > 0f) kg / perDay else 0f
        rate[1] = saved / (HOUSE_DAYS * g.adults.coerceAtLeast(1))
        // (planTrace: what a house was reckoned at.)
        if (PLAN_TRACE && unitOf(mix64(g.id * HASH_A + weather.date.absoluteDay * HASH_B + PLAN_TRACE_SALT + 1)) < 0.02f &&
            houseTraced.incrementAndGet() <= PLAN_TRACE_MOST)
            System.err.println(("house trace: band %d, %s, %d people (%d grown), camp kept %d steps, at home %b, houses %.2f, reckoned kept %.2f y; " +
                "a whole house a person a year: %.1f kg (as lived %.1f, usual cold %.1f); over the years: %.0f kg, %.0f days of foraging at %.2f kg a person-day, against %.0f days to build: %.2f " +
                "(given days at 1 or more)").format(g.id, weather.date, g.size, g.adults, b.campWeeks, b.atHome, b.house, years, kg / years / people, b.houseFood, houseFoodAYear(g, b, g.place, weather),
                kg, saved, perDay, HOUSE_DAYS * g.adults.coerceAtLeast(1), rate[1]))
    }
    if (weirs) {
        // (A whole weir across the river by its camp: the days of foraging the food it would take a year as the band lived
        // ([Band.weirFood]) would have taken at [perDay], less the days its traps want ([Band.weirTend]) and its mending
        // ([WEIR_WEAR] of it a year), over the years it and its heirs expect to keep the place, against its building.)
        val whole = weirLengthM(hw.placeHex[g.place]) * WEIR_DAYS_PER_M
        val years = keepYears(b, stepsPerYear)
        val aYear = (if (perDay > 0f) b.weirFood / perDay else 0f) - b.weirTend - WEIR_WEAR * whole
        rate[2] = if (whole > 0f) aYear * years / whole else 0f
        if (PLAN_TRACE && unitOf(mix64(g.id * HASH_A + weather.date.absoluteDay * HASH_B + PLAN_TRACE_SALT + 2)) < 0.2f &&
            weirTraced.incrementAndGet() <= PLAN_TRACE_MOST)
            System.err.println(("weir trace: band %d, %s, %d people, camp kept %d steps, reckoned kept %.2f y; a whole weir %.0f days to build; " +
                "a year as lived: %.0f kg of need (%.0f days of foraging at %.2f kg a person-day), %.0f days tending, %.0f mending: %.2f " +
                "(given days at 1 or more)").format(g.id, weather.date, g.size, b.campWeeks, years, whole, b.weirFood,
                if (perDay > 0f) b.weirFood / perDay else 0f, perDay, b.weirTend, WEIR_WEAR * whole, rate[2]))
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
    TOOLS_ASKED("tools asked"), TOOLS_HELD("tools held")
}

/** planTrace=true: a sample of planning bands' weeks, how the year ahead reckons their stores ([planRates]), on stderr. */
internal val PLAN_TRACE = Config.flag(Config.Key.PLAN_TRACE, false)
private val planTraced = java.util.concurrent.atomic.AtomicInteger()
private val houseTraced = java.util.concurrent.atomic.AtomicInteger()
private val weirTraced = java.util.concurrent.atomic.AtomicInteger()
private val toolTraced = java.util.concurrent.atomic.AtomicInteger()
private const val PLAN_TRACE_MOST = 400
private const val PLAN_TRACE_SALT = 0x50545243L

/** The chores with no reckoning in lives yet ([planDays]): shared out at equal weights. */
private val UNRECKONED_ACTS = arrayOf(Activity.TRAINING, Activity.MATERIALS, Activity.DRYING, Activity.SKINS, Activity.CACHING,
    Activity.SOWING, Activity.PALISADE)
/** The chores [planDays] holds by what they save ([planRates], [toolRates]): the stores in lives, then houses, weirs and tools in days. */
private val SCORED_ACTS = arrayOf(Activity.FOOD, Activity.HOUSES, Activity.WEIRS, Activity.TOOLS)
private val SCORED_ITEMS = SCORED_ACTS.size
/** Fewer lives than this a person are none saved: the rounding in the reckoning, not a difference. */
private const val SAVES_NONE = 1e-6f

/** What a [Mind.PLANS] band's tools served a year as it lived its weeks ([Band.toolWork]). */
enum class ToolWork { PLANT_DAYS, SEED_DAYS }

/**
 * Band [b]'s (group [g]'s) tools in days (tier 2, as houses and weirs, [planDays]): for each tool it knows and lacks of a
 * whole kit, the days of work a whole kit would save it a year as it lived its weeks ([Band.toolWork]) over the years a
 * kit lasts ([Tool.wearPerYear]; a tool too heavy to carry, as long as it keeps the place, [keepYears], if less), against
 * the days a whole kit takes to make ([Tool.makeDays] a person, by its craft's skill). Each saves what the sim has it do:
 * - a basket speeds all plant work (more carried a trip), a sickle and grinding stones the seed and grain
 *   ([reapDaysPerKg]): the days at that work less those it would take with a whole kit;
 * - spears and bows make more stalks end in a kill ([Band.huntEdge]), nets sweep more ground for small game and take
 *   more fish ([Band.snares], [Band.fishing]): the more food it would have got, as days of foraging at [perDay].
 * (Not weighed: what grinding adds to seed's food, [seedValue]; spears and bows against beasts; the pick, for wells, which
 * no band yet builds. The clothes are the survival pass's ([survivalDays]). A bow's gain is taken at half its most, for
 * game of middling speed.)
 * Marks the tools that save more days than they take in [Band.toolPays] (it makes and mends those alone), and returns
 * their days saved over their days ([planDays]' rate) and the days they ask.
 */
private fun HexGroups.toolRates(g: HexGroup, b: Band, perDay: Float): Pair<Float, Float> {
    val people = g.adults.toFloat()
    val wk = b.toolWork
    // (Its kills' meat and fat, and its small game and fish, kg a year, as it lived ([Band.foodFrom], kept in a save).)
    val perYear = g.size * Date.DAYS_PER_YEAR.toFloat()
    val meatKg = b.foodFrom[FoodFrom.BIG_GAME] * perYear
    val netKg = (b.foodFrom[FoodFrom.SMALL_GAME] + b.foodFrom[FoodFrom.FISH]) * perYear
    var saved = 0f; var cost = 0f; var asked = 0f
    for (t in Tool.entries) {
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
            Tool.GRINDER -> speeds(wk[ToolWork.SEED_DAYS], GRINDER_GAIN)
            Tool.SPEAR -> more(meatKg, WEAPONLESS + (1f - WEAPONLESS) * k, 1f)
            Tool.BOW -> more(meatKg, 1f + 0.5f * BOW_GAIN * k, 1f + 0.5f * BOW_GAIN)
            Tool.NET -> more(netKg, 1f + NET_GAIN * k, 1f + NET_GAIN)
        }
        val lasts = (1f / t.wearPerYear).let { if (t.heavy) min(it, keepYears(b, stepsPerYear)) else it }
        val whole = t.makeDays * people / b.makeSkill(t).coerceAtLeast(1e-3f)
        val over = aYear / (1f - k) * lasts / whole
        if (PLAN_TRACE && unitOf(mix64(g.id * HASH_A + b.campWeeks * HASH_B + t.ordinal + PLAN_TRACE_SALT + 3)) < 0.02f &&
            toolTraced.incrementAndGet() <= PLAN_TRACE_MOST)
            System.err.println(("tool trace: band %d, %s, %d grown, kit %.2f; a year as lived: %.0f plant days, %.0f seed days, %.0f kg meat, " +
                "%.0f kg small game and fish; a whole kit saves %.1f days a year, lasts %.1f y, takes %.0f days to make: %.2f (made at 1 or more)").format(
                g.id, t.name, g.adults, k, wk[ToolWork.PLANT_DAYS], wk[ToolWork.SEED_DAYS], meatKg, netKg,
                aYear / (1f - k), lasts, whole, over))
        if (over < 1f) continue
        b.toolPays[t] = true
        saved += aYear * lasts; cost += (1f - k) * whole
        asked += makeTools(b, people, null, only = t)
    }
    return (if (cost > 0f) saved / cost else 0f) to asked
}
