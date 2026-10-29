package inflation.ecology

import inflation.Config
import inflation.EnumFloats
import inflation.enumFloats
import inflation.world.Weather
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The days [g]'s band [b] holds first this week to keep its people warm (survival first): of the hearth's firewood and the
 * camp's shelters as much as averts deaths, by the sim's own reckoning: this week's exposure by its people's body heat
 * ([Band.warmth]) with the fire the wood keeps and the shelters it puts up, and with them what keeping warm takes of its food
 * ([warmthNeed]) and so its hunger, this week and through the year after ([yearAhead]), each counted in full; and the food
 * the days they take leave unforaged: the food gets what they leave of the week, as far as it asks, and a week left short is
 * bridged by the larder and fat, or is hunger. These come before the food in the week ([BandTime.plan]): a cold night kills
 * within the week, a hungry week is bridged by the larder and fat. (Reckoned with all the week's food got whatever the
 * warmth took, a thatched hut's two days a person were free, and its days came out of the foraging: hunger twice main's in a
 * 10-year run of 2026-10-28.)
 *
 * The food is not bid for days itself: what a band foresees of its hunger is by the food it got in each month over the last
 * years, which in its first year it has none of (all foreseen dead whatever is done now) and which does not see the fat a
 * short week burns, so a shortfall would look free (a 2026-10-24 check: hunger deaths ten times main's). Holding the warmth
 * only after the food, pro rata, left bands that had walked most of their week a tenth of a fire (exposure five times main's).
 *
 * The days go a few at a time to whichever of the two averts the more deaths for its days (or for all it asks, where a
 * little does nothing, as wood in a cold too deep for half a fire), until each has all it asks, neither averts any more, or
 * the week is spent. A shelter stands while the band keeps its camp: through the weeks the band expects to stay on
 * ([Band.stayWeeks]) the year after is reckoned under it, its exposure and what keeping warm takes of the food it will get
 * ([yearAhead]). The fire asks all the days a band gives firewood at most ([fuelMost]): the more wood it burns the warmer it
 * keeps them ([fireOf], [BodyHeat.fireC]), the more so in a thatched hut or a house. (The deaths are reckoned, not drawn,
 * so there is no chance to allow for.) What they ask beyond, the days that avert no death (wood in a warm week), is
 * shared with the other chores.
 *
 * [foodAsk]: the person-days of work that would forage the rest of the week's want, [gotShare] of it got already;
 * [moved]: whether it has come to a new camp, where no shelter stands. Returns the person-days of work for the firewood and
 * the shelters (none for the rest).
 */
internal fun HexGroups.survivalDays(g: HexGroup, b: Band, weather: Weather, time: BandTime, hands: Int, foodAsk: Float, gotShare: Float,
    moved: Boolean): EnumFloats<Activity> {
    val first = enumFloats<Activity>()
    val s = g.species; val p = g.place; val people = g.adults.toFloat()
    val air = airAt(p, weather); val home = b.homeShelter()
    // (Its shelters as far as a thatched hut, whatever its ways aim at: they are its people's lives, [shelterWork].)
    val shelter0 = if (moved) 0f else b.shelter
    val shelterAsk = shelterWork(b, people, shelter0, SHELTER_MOST)
    val asks = FloatArray(3); asks[FOOD] = foodAsk; asks[FIRE] = fuelMost(g, hands); asks[SHELTER] = shelterAsk
    val acts = arrayOf(Activity.FOOD, Activity.FIREWOOD, Activity.SHELTER)
    // (Its body heat at each fire and shelter, worked out once a week for each, to the hundredth: a fire cut short by a long
    // walk is a small share of one, and coarser steps would round it to none.)
    val warmthAt = HashMap<Int, Warmth>()
    fun warmth(fire: Float, shelter: Float): Warmth {
        val f = (fire * GRID).roundToInt(); val sh = (shelter * GRID).roundToInt()
        return warmthAt.getOrPut(f * 10_000 + sh) { b.warmth(air, f / GRID, home, days, sh / GRID) }
    }
    val larderDays = g.larder / g.dailyNeedKg.coerceAtLeast(1e-3f)
    // (The year after under each shelter it might put up, worked out once for each to the hundredth: through the days it
    // expects to stay on after this week. It decides each week whether to move on, so the weeks it has kept the camp take
    // nothing from those still to come: with a week's chance of leaving of one in its usual stay, it stays that less this
    // week. The weeks it has kept this camp count as well as its usual stay, as for its moves ([birthSpacing]). Before,
    // its usual stay less the weeks kept: a band that had outstayed it was reckoned to leave next week, whatever it did.)
    val stayDays = (max(b.stayWeeks, (if (moved) 0 else b.campWeeks) + 1f) - 1f) * days
    val yearAt = HashMap<Int, YearAhead>()
    fun year(shelter: Float): YearAhead {
        val sh = (shelter * GRID).roundToInt()
        return yearAt.getOrPut(sh) { yearAhead(g, b, weather, sh / GRID, stayDays) }
    }
    // (The food gets what the warmth leaves of the week, as far as it asks: days held for the fire and shelters are days not
    // foraged, and a week they leave short is bridged by the larder and fat, or is hunger, now and in the year after.)
    val total = time.left
    fun foodWork(d: FloatArray) =
        min(asks[FOOD], max(0f, total - d[FIRE] / time.pace(Activity.FIREWOOD) - d[SHELTER] / time.pace(Activity.SHELTER)) * time.pace(Activity.FOOD))
    fun deaths(d: FloatArray, parts: FloatArray? = null): Float {
        val fire = fireOf(g, d[FIRE])
        val shelter = shelterReach(b, people, shelter0, SHELTER_MOST, d[SHELTER])
        val w = warmth(fire, shelter)
        // (The want was reckoned at the fire it had: a warmer or colder week needs less or more, [warmthNeed].)
        val needs = warmthNeed(w, fire) / g.warmth.coerceAtLeast(1e-3f)
        var fed = (gotShare + (1f - gotShare) * (if (asks[FOOD] > 0f) min(1f, foodWork(d) / asks[FOOD]) else 1f)) / needs
        val paid = min(max(0f, 1f - fed) * days, larderDays); fed += paid / days
        val onFat = Hazards.onFat(fed, g.fat, days)
        val fat = max(0f, g.fat - (onFat - fed) * days)
        val hunger = Hazards.hunger(s, g.spent, onFat, days)
        // (This week's deaths and the year after's, each counted in full: a year that looks bleak whatever is done now, as
        // it does before a band has a year's food behind it to foresee by, takes nothing from what this week's days do.)
        val week = (1f - w.odds) * (1f - hunger)
        val yearDied = 1f - year(shelter).alive(s, larderDays - paid, fat, Hazards.spentAfter(s, g.spent, onFat, days))
        if (parts != null) { parts[0] = 1f - w.odds; parts[1] = (1f - w.odds) * hunger; parts[2] = yearDied }
        return 1f - week + yearDied
    }
    val d = FloatArray(3)
    var now = deaths(d)
    var left = time.left
    val chunk = max(1e-3f, time.left / CHUNKS)
    val trial = FloatArray(3)
    while (left > 1e-4f) {
        var best = -1; var bestGain = 0f; var bestWork = 0f
        for (i in FIRE..SHELTER) {
            val room = asks[i] - d[i]
            if (room <= 0f) continue
            val pace = time.pace(acts[i])
            // (A few days more, or all it asks: whichever does more for its days.)
            for (work in floatArrayOf(min(room, chunk * pace), min(room, left * pace))) {
                d.copyInto(trial); trial[i] += work
                val averted = now - deaths(trial); val gain = averted / (work / pace)
                if (averted > AVERTS_NONE && gain > bestGain) { best = i; bestGain = gain; bestWork = work }
            }
        }
        if (best < 0) break
        d[best] += bestWork; left -= bestWork / time.pace(acts[best])
        now = deaths(d)
    }
    for (i in FIRE..SHELTER) first[acts[i]] = d[i]
    // (What it reckoned with, for the census: [bookSqueeze].)
    b.planFire = fireOf(g, d[FIRE])
    val planShelter = shelterReach(b, people, shelter0, SHELTER_MOST, d[SHELTER])
    b.shelterAim = planShelter
    b.planOdds = warmth(b.planFire, planShelter).odds
    if (WARM_DIAG) { b.planAir = air; b.planCover = b.cover(air.meanC, b.planFire, home, planShelter); b.planSisu = b.sisu }
    b.planLeft = if (time.total > 0f) time.left / time.total else 0f
    b.planShelterFirst = if (asks[SHELTER] > 0f) min(1f, d[SHELTER] / asks[SHELTER]) else -1f
    if (THATCH_TRACE && asks[SHELTER] - d[SHELTER] > 1e-3f) {
        // (What thatching on to the full would have done by its own reckoning, its fire as planned: [bookThatchTrace].)
        val had = FloatArray(3); val full = FloatArray(3); val thatched = d.copyOf(); thatched[SHELTER] = asks[SHELTER]
        val averted = deaths(d, had) - deaths(thatched, full)
        val extra = (asks[SHELTER] - d[SHELTER]) / time.pace(Activity.SHELTER)
        bookThatchTrace(planShelter, g.size.toFloat(), had, full, averted, extra, left, stayDays / days)
    }
    return first
}

/**
 * thatchTrace=true: for the census, each band-week its survival pass stopped short of a thatched hut, what thatching on to
 * the full would have done by the pass's own reckoning, its fire as planned: the deaths it would avert this week of
 * exposure and of hunger and through the year after ([had] against [full], shares of the band; a negative is deaths it would
 * bring, the food its days leave unforaged; [averted] all of them), the days it would take ([extra], of its hands')
 * against those the pass left ([left]), and the weeks it expects to stay on ([stayWeeks]). By how far its shelters reached ([shelter]). Counts only.
 */
private fun HexGroups.bookThatchTrace(shelter: Float, people: Float, had: FloatArray, full: FloatArray, averted: Float, extra: Float, left: Float,
    stayWeeks: Float) {
    val hut = Tally.Hut.of(shelter)
    fun add(k: Tally.Thatch, v: Float) { stats.hunting.merge(Tally.thatchTrace(hut, k), v.roundToInt(), Int::plus) }
    add(Tally.Thatch.WEEKS, 1f)
    add(Tally.Thatch.EXPOSURE, 1e4f * people * (had[0] - full[0]))
    add(Tally.Thatch.HUNGER, 1e4f * people * (had[1] - full[1]))
    add(Tally.Thatch.YEAR, 1e4f * people * (had[2] - full[2]))
    add(Tally.Thatch.DAYS, 10f * extra); add(Tally.Thatch.LEFT, 10f * left); add(Tally.Thatch.STAY, 10f * stayWeeks)
    add(if (averted <= AVERTS_NONE) Tally.Thatch.NOT_WORTH else if (extra <= left) Tally.Thatch.WORTH_FITS else Tally.Thatch.WORTH_SHORT, 1f)
}

/** thatchTrace=true: [bookThatchTrace] each band-week. Report-only; costs two more reckonings a band-week. */
internal val THATCH_TRACE = Config.flag(Config.Key.THATCH_TRACE, false)

private const val FOOD = 0
private const val FIRE = 1
private const val SHELTER = 2
/** The pieces the week is given out in ([survivalDays]); and the steps of fire and shelter its body heat is reckoned at. */
private const val CHUNKS = 40f
private const val GRID = 100f
/** Fewer deaths than this, a share of the band, are no deaths averted: the rounding in the reckoning, not a difference. */
private const val AVERTS_NONE = 1e-6f
