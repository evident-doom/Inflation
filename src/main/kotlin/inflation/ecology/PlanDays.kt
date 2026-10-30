package inflation.ecology

import inflation.EnumFloats
import inflation.enumFloats
import inflation.world.Weather
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
 * - what it holds by lives saved ([PlanTier.SCORED]): a few days at a time to whichever of the stores and the houses saves the
 *   more lives a day ([planLives]), until neither saves any more or the days are gone;
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
    // What it holds by lives saved.
    val lives = planLives(g, b, weather, perDay, asks[Activity.HOUSES] > 0f)
    val ask = floatArrayOf(asks[Activity.FOOD], asks[Activity.HOUSES])
    val acts = arrayOf(Activity.FOOD, Activity.HOUSES)
    val d = FloatArray(SCORED_ITEMS)
    var now = lives.saved(d)
    val chunk = max(1e-3f, left / CHUNKS)
    val trial = FloatArray(SCORED_ITEMS)
    while (left > 1e-4f) {
        var best = -1; var bestGain = 0f; var bestWork = 0f
        for (i in 0 until SCORED_ITEMS) {
            val room = ask[i] - d[i]
            if (room <= 0f) continue
            val pace = time.pace(acts[i])
            // (A few days more, or all it asks: whichever saves more for its days, as in [survivalDays].)
            for (work in floatArrayOf(min(room, chunk * pace), min(room, left * pace))) {
                d.copyInto(trial); trial[i] += work
                val more = lives.saved(trial) - now; val gain = more / (work / pace)
                if (more > SAVES_NONE * g.size && gain > bestGain) { best = i; bestGain = gain; bestWork = work }
            }
        }
        if (best < 0) break
        d[best] += bestWork; left -= bestWork / time.pace(acts[best])
        now = lives.saved(d)
    }
    for (i in 0 until SCORED_ITEMS) given[acts[i]] = d[i]
    left = max(0f, left)
    // What has no reckoning in lives yet, at equal weights.
    val rest = FloatArray(UNRECKONED_ACTS.size) { days(UNRECKONED_ACTS[it], asks[UNRECKONED_ACTS[it]]) }
    val shared = BandTime.shareOut(rest, FloatArray(rest.size) { 1f }, left)
    for (i in UNRECKONED_ACTS.indices) given[UNRECKONED_ACTS[i]] = shared[i] * time.pace(UNRECKONED_ACTS[i])
    left = max(0f, left - shared.sum())
    given[Activity.LEISURE] = left * time.pace(Activity.LEISURE)
    // (For the census: the days of each tier, and the lives the scored days were reckoned to save.)
    fun add(t: PlanTier, v: Float) { stats.hunting.merge(Tally.planTier(t), (10f * v).roundToInt(), Int::plus) }
    add(PlanTier.OBLIGED, paid.sum()); add(PlanTier.SCORED, d[0] / time.pace(acts[0]) + d[1] / time.pace(acts[1]))
    add(PlanTier.UNRECKONED, shared.sum()); add(PlanTier.LEISURE, left)
    add(PlanTier.ASKED_BEYOND, max(0f, rest.sum() - shared.sum()))
    stats.hunting.merge(Tally.planTier(PlanTier.LIVES), (1e4f * (now - lives.saved(FloatArray(SCORED_ITEMS)))).roundToInt(), Int::plus)
    return given
}

/**
 * What [planDays]' scored items save of band [b]'s (group [g]'s) people through the year ahead, by the sim's own forecast
 * ([yearAhead], [YearAhead.alive]):
 * - the stores ([Activity.FOOD] beyond the week's food): what [perDay] kg a day of work lays by, in the larder from now;
 * - houses ([Activity.HOUSES]): a whole house's lives a year ([houseLivesAYear]: exposure averted) and its food a year
 *   ([houseFoodAYear]: what keeping warm no longer takes, as larder), over the years it expects to stay ([Band.stayWeeks]),
 *   a day of work worth its share of a whole house's days ([HOUSE_DAYS] a person). Its effects come in only once it is
 *   whole; the share is the forecast's estimate.
 * Not foreseen, as in [yearAhead]: the larder spoiling or raided, and the year beyond the next. ([houses]: whether it asks
 * for days for houses; the houses are reckoned only then.)
 */
private fun HexGroups.planLives(g: HexGroup, b: Band, weather: Weather, perDay: Float, houses: Boolean): PlanLives {
    val year = yearAhead(g, b, weather)
    val need = g.dailyNeedKg.coerceAtLeast(1e-3f)
    val people = g.size.toFloat()
    val stayYears = max(b.stayWeeks, b.campWeeks + 1f) / stepsPerYear
    val houseDays = HOUSE_DAYS * g.adults.coerceAtLeast(1)
    // (Reckoned only when it asks for days for houses.)
    val houseLives = if (houses) houseLivesAYear(b, g.place, weather) * people * stayYears / houseDays else 0f
    val houseKg = if (houses) houseFoodAYear(g, b, g.place, weather) * people * stayYears / houseDays else 0f
    return PlanLives { d ->
        val kg = d[0] * perDay + d[1] * houseKg
        people * year.alive(g.species, (g.larder + kg) / need, g.fat, g.spent) + d[1] * houseLives
    }
}

/** The people of a band alive through the year ahead with the days of work [planDays] gives each scored item. */
private fun interface PlanLives { fun saved(d: FloatArray): Float }

/**
 * The tiers of a [Mind.PLANS] band's week beyond its essentials ([planDays]), for the census, person-days ([LIVES]: the
 * lives its scored days were reckoned to save, ten-thousandths; [ASKED_BEYOND]: the unreckoned chores' asks it could not give).
 */
enum class PlanTier(val label: String) {
    OBLIGED("owed by custom"), SCORED("held by lives"), UNRECKONED("no reckoning yet"), LEISURE("leisure"),
    ASKED_BEYOND("unreckoned asks not given"), LIVES("lives reckoned saved, 1/10000")
}

/** The chores with no reckoning in lives yet ([planDays]): shared out at equal weights. */
private val UNRECKONED_ACTS = arrayOf(Activity.TOOLS, Activity.TRAINING, Activity.MATERIALS, Activity.DRYING, Activity.SKINS, Activity.CACHING,
    Activity.SOWING, Activity.PALISADE, Activity.WEIRS)
private const val SCORED_ITEMS = 2
/** The pieces the week is given out in, as in [survivalDays]. */
private const val CHUNKS = 40f
/** Fewer lives than this a person are none saved: the rounding in the reckoning, not a difference. */
private const val SAVES_NONE = 1e-6f
