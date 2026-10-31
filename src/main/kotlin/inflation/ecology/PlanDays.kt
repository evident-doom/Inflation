package inflation.ecology

import inflation.Config
import inflation.EnumFloats
import inflation.HASH_A
import inflation.HASH_B
import inflation.enumFloats
import inflation.mix64
import inflation.unitOf
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
 * - then what it holds by lives saved over the years a work serves it and its heirs ([PlanTier.GENERATIONS], security over
 *   generations): the houses, with the days the stores left, as far as they ask, while they save any;
 * - what has no reckoning in lives yet ([PlanTier.UNRECKONED]: tools beyond the clothes, training, materials, drying, skins, caching,
 *   sowing, palisade, weirs), shared out at equal weights of what is left, no dials;
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
    // What it holds by lives saved, tier by tier: survival now (the stores), then over generations (the houses), each as
    // far as it asks this week ([planRates]); a tier has what the one below it left.
    val rate = planRates(g, b, weather, perDay, asks[Activity.HOUSES] > 0f)
    val ask = floatArrayOf(asks[Activity.FOOD], asks[Activity.HOUSES])
    val acts = arrayOf(Activity.FOOD, Activity.HOUSES)
    val d = FloatArray(SCORED_ITEMS)
    for (i in 0 until SCORED_ITEMS) {
        if (rate[i] <= SAVES_NONE * g.size || left <= 0f) continue
        d[i] = min(ask[i], left * time.pace(acts[i])); left -= d[i] / time.pace(acts[i])
    }
    for (i in 0 until SCORED_ITEMS) given[acts[i]] = d[i]
    left = max(0f, left)
    // What has no reckoning in lives yet, at equal weights.
    val rest = FloatArray(UNRECKONED_ACTS.size) { days(UNRECKONED_ACTS[it], asks[UNRECKONED_ACTS[it]]) }
    // (Each as far as the days left at most: an ask far beyond them, as materials' year of need can be, would leave the
    // share-out's weighing too fine for a float, and the others nothing.)
    val shared = BandTime.shareOut(FloatArray(rest.size) { min(rest[it], left) }, FloatArray(rest.size) { 1f }, left)
    for (i in UNRECKONED_ACTS.indices) given[UNRECKONED_ACTS[i]] = shared[i] * time.pace(UNRECKONED_ACTS[i])
    left = max(0f, left - shared.sum())
    given[Activity.LEISURE] = left * time.pace(Activity.LEISURE)
    // (For the census: the days of each tier, and the lives the scored days were reckoned to save.)
    fun add(t: PlanTier, v: Float) { stats.hunting.merge(Tally.planTier(t), (10f * v).roundToInt(), Int::plus) }
    add(PlanTier.OBLIGED, paid.sum()); add(PlanTier.SCORED, d[0] / time.pace(acts[0])); add(PlanTier.GENERATIONS, d[1] / time.pace(acts[1]))
    add(PlanTier.UNRECKONED, shared.sum()); add(PlanTier.LEISURE, left)
    add(PlanTier.ASKED_BEYOND, max(0f, rest.sum() - shared.sum()))
    add(PlanTier.STORES_ASKED, days(Activity.FOOD, ask[0])); add(PlanTier.STORES_HELD, days(Activity.FOOD, d[0]))
    add(PlanTier.HOUSES_ASKED, days(Activity.HOUSES, ask[1])); add(PlanTier.HOUSES_HELD, days(Activity.HOUSES, d[1]))
    stats.hunting.merge(Tally.planTier(PlanTier.LIVES), (1e4f * (d[0] * rate[0] + d[1] * rate[1])).roundToInt(), Int::plus)
    return given
}

/**
 * The lives a day of work at each of [planDays]' scored items saves of band [b]'s (group [g]'s) people through the year ahead,
 * by the sim's own forecast ([yearAhead], [YearAhead.alive]), each reckoned over the whole of what it is for and shared
 * out over the days the whole takes:
 * - the stores ([Activity.FOOD] beyond the week's food): filling its larder to the stores it aims at ([Band.storeAimAt1]:
 *   its lean days foreseen and a margin), at [perDay] kg a day of work; by how often its larder runs out over years made of
 *   its own past weeks ([storesAlive]) once it has a year of them, by the year ahead before;
 * - houses ([Activity.HOUSES]): a whole house's lives a year ([houseLivesAYear]: exposure averted) and its food a year
 *   ([houseFoodAYear]: what keeping warm no longer takes, as larder) over the years it and its heirs expect to keep the
 *   place ([keepYears]), over a whole house's days ([HOUSE_DAYS] a person). (Over the years it expected to keep its camp,
 *   by its usual stay, a house was reckoned over a few months to a year: built for the next season, not for the next
 *   generation; Olli, 2026-10-31.)
 * (Reckoned a week's work at a time, from what it has, the first days of either did nothing: a larder a few days fuller
 * leaves a lean spell that burns past the body's protein as deadly, and a house's food came in as a week's sliver of it.
 * The forecast foresaw as much hunger as came, and more (ab461c, 2026-10-30), yet the stores were given 2 to 3% of their
 * ask, the houses none (ab461b). A whole is worth what it saves; the band works towards it a week at a time.)
 * Not foreseen, as in [yearAhead]: the larder spoiling or raided, and the year beyond the next. ([houses]: whether it asks
 * for days for houses; the houses are reckoned only then.)
 */
private fun HexGroups.planRates(g: HexGroup, b: Band, weather: Weather, perDay: Float, houses: Boolean): FloatArray {
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
        val kg = houseFoodAYear(g, b, g.place, weather) * people * years
        rate[1] = (houseLivesAYear(b, g.place, weather) * people * years + alive(kg) - now) / (HOUSE_DAYS * g.adults.coerceAtLeast(1))
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
    OBLIGED("owed by custom"), SCORED("held by lives this year"), GENERATIONS("held by lives over generations"), UNRECKONED("no reckoning yet"), LEISURE("leisure"),
    ASKED_BEYOND("unreckoned asks not given"), LIVES("lives reckoned saved, 1/10000"),
    STORES_ASKED("stores asked"), STORES_HELD("stores held"), HOUSES_ASKED("houses asked"), HOUSES_HELD("houses held")
}

/** planTrace=true: a sample of planning bands' weeks, how the year ahead reckons their stores ([planRates]), on stderr. */
internal val PLAN_TRACE = Config.flag(Config.Key.PLAN_TRACE, false)
private val planTraced = java.util.concurrent.atomic.AtomicInteger()
private const val PLAN_TRACE_MOST = 400
private const val PLAN_TRACE_SALT = 0x50545243L

/** The chores with no reckoning in lives yet ([planDays]): shared out at equal weights. */
private val UNRECKONED_ACTS = arrayOf(Activity.TOOLS, Activity.TRAINING, Activity.MATERIALS, Activity.DRYING, Activity.SKINS, Activity.CACHING,
    Activity.SOWING, Activity.PALISADE, Activity.WEIRS)
private const val SCORED_ITEMS = 2
/** Fewer lives than this a person are none saved: the rounding in the reckoning, not a difference. */
private const val SAVES_NONE = 1e-6f
