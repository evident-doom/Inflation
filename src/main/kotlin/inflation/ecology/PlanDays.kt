package inflation.ecology

import inflation.Config
import inflation.EnumFloats
import inflation.HASH_A
import inflation.HASH_B
import inflation.enumFloats
import inflation.mix64
import inflation.unitOf
import inflation.world.Weather
import inflation.world.get
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
 * - what it holds by lives saved ([PlanTier.SCORED]): first to whichever of the stores, the houses and drying saves the
 *   more lives a day ([planRates]), each as far as it asks, while it saves any;
 * - what has no reckoning in lives yet ([PlanTier.UNRECKONED]: tools beyond the clothes, training, materials, skins, caching,
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
    // What it holds by lives saved: each item's days at the lives a day of it saves, the best first, each as far as it
    // asks this week ([planRates]).
    val rate = planRates(g, b, weather, perDay, asks[Activity.HOUSES] > 0f, days(Activity.DRYING, asks[Activity.DRYING]))
    val ask = floatArrayOf(asks[Activity.FOOD], asks[Activity.HOUSES], asks[Activity.DRYING])
    val acts = arrayOf(Activity.FOOD, Activity.HOUSES, Activity.DRYING)
    val d = FloatArray(SCORED_ITEMS)
    for (i in (0 until SCORED_ITEMS).sortedByDescending { rate[it] }) {
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
    add(PlanTier.OBLIGED, paid.sum()); add(PlanTier.SCORED, (0 until SCORED_ITEMS).sumOf { (d[it] / time.pace(acts[it])).toDouble() }.toFloat())
    add(PlanTier.UNRECKONED, shared.sum()); add(PlanTier.LEISURE, left)
    add(PlanTier.ASKED_BEYOND, max(0f, rest.sum() - shared.sum()))
    add(PlanTier.STORES_ASKED, days(Activity.FOOD, ask[0])); add(PlanTier.STORES_HELD, days(Activity.FOOD, d[0]))
    add(PlanTier.HOUSES_ASKED, days(Activity.HOUSES, ask[1])); add(PlanTier.HOUSES_HELD, days(Activity.HOUSES, d[1]))
    add(PlanTier.DRYING_ASKED, days(Activity.DRYING, ask[2])); add(PlanTier.DRYING_HELD, days(Activity.DRYING, d[2]))
    stats.hunting.merge(Tally.planTier(PlanTier.LIVES), (1e4f * (0 until SCORED_ITEMS).sumOf { (d[it] * rate[it]).toDouble() }.toFloat()).roundToInt(), Int::plus)
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
 *   ([houseFoodAYear]: what keeping warm no longer takes, as larder) over the years it expects to stay ([Band.stayWeeks]),
 *   over a whole house's days ([HOUSE_DAYS] a person);
 * - drying ([Activity.DRYING]): the fresh food it means to dry kept from this week's spoiling, as larder, over the [dryDays]
 *   its ask takes. (Only this week's spoiling: what fresh food left over would lose in the weeks after is not counted.)
 * (Reckoned a week's work at a time, from what it has, the first days of either did nothing: a larder a few days fuller
 * leaves a lean spell that burns past the body's protein as deadly, and a house's food came in as a week's sliver of it.
 * The forecast foresaw as much hunger as came, and more (ab461c, 2026-10-30), yet the stores were given 2 to 3% of their
 * ask, the houses none (ab461b). A whole is worth what it saves; the band works towards it a week at a time.)
 * Not foreseen, as in [yearAhead]: the larder spoiling or raided, and the year beyond the next. ([houses]: whether it asks
 * for days for houses; the houses are reckoned only then.)
 */
private fun HexGroups.planRates(g: HexGroup, b: Band, weather: Weather, perDay: Float, houses: Boolean, dryDays: Float): FloatArray {
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
    // Drying: the fresh food it means to dry ([Band.dryWill]), which its ask is sized to, kept from this week's spoiling
    // ([freshSpoils]) less what dried food loses ([driedKeep]); in its larder through the year, by its own weeks or the year ahead.
    if (dryDays > 0f) {
        val dried = g.stores[Store.FRESH] * b.dryWill.coerceIn(0f, 1f)
        val saved = dried * (freshSpoils(weather.temperature[g.place]) - min(1f, Store.DRIED.lostWeekly * driedKeep(b) * days / 7f))
        if (saved > 0f) {
            val was = storesAlive(g, b, weather, g.larder)
            val kept = if (was != null) storesAlive(g, b, weather, g.larder + saved) else null
            rate[2] = (if (was != null && kept != null) people * (kept - was) else alive(saved) - now) / dryDays
        }
    }
    if (houses) {
        val stayYears = max(b.stayWeeks, b.campWeeks + 1f) / stepsPerYear
        val kg = houseFoodAYear(g, b, g.place, weather) * people * stayYears
        rate[1] = (houseLivesAYear(b, g.place, weather) * people * stayYears + alive(kg) - now) / (HOUSE_DAYS * g.adults.coerceAtLeast(1))
    }
    return rate
}

/**
 * The tiers of a [Mind.PLANS] band's week beyond its essentials ([planDays]), for the census, person-days ([LIVES]: the
 * lives its scored days were reckoned to save, ten-thousandths; [ASKED_BEYOND]: the unreckoned chores' asks it could not give;
 * the stores' and the houses' asks and what of them it held by lives).
 */
enum class PlanTier(val label: String) {
    OBLIGED("owed by custom"), SCORED("held by lives"), UNRECKONED("no reckoning yet"), LEISURE("leisure"),
    ASKED_BEYOND("unreckoned asks not given"), LIVES("lives reckoned saved, 1/10000"),
    STORES_ASKED("stores asked"), STORES_HELD("stores held"), HOUSES_ASKED("houses asked"), HOUSES_HELD("houses held"),
    DRYING_ASKED("drying asked"), DRYING_HELD("drying held")
}

/** planTrace=true: a sample of planning bands' weeks, how the year ahead reckons their stores ([planRates]), on stderr. */
internal val PLAN_TRACE = Config.flag(Config.Key.PLAN_TRACE, false)
private val planTraced = java.util.concurrent.atomic.AtomicInteger()
private const val PLAN_TRACE_MOST = 400
private const val PLAN_TRACE_SALT = 0x50545243L

/** The chores with no reckoning in lives yet ([planDays]): shared out at equal weights. */
private val UNRECKONED_ACTS = arrayOf(Activity.TOOLS, Activity.TRAINING, Activity.MATERIALS, Activity.SKINS, Activity.CACHING,
    Activity.SOWING, Activity.PALISADE, Activity.WEIRS)
private const val SCORED_ITEMS = 3
/** Fewer lives than this a person are none saved: the rounding in the reckoning, not a difference. */
private const val SAVES_NONE = 1e-6f
