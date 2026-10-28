package inflation.ecology

import inflation.EnumFloats
import inflation.enumFloats
import inflation.world.Date
import inflation.world.Weather
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The days [g]'s band [b] holds first this week to keep its people warm (survival first): of the hearth's firewood and the
 * camp's shelters as much as averts deaths, by the sim's own reckoning: this week's exposure by its people's body heat
 * ([Band.warmth]) with the fire the wood keeps and the shelters it puts up, and with them what keeping warm takes of its food
 * ([warmthNeed]) and so its hunger, this week and through the year after ([yearAhead]), each counted in full, with all the
 * week's food it wants got. These come before the food in the week ([BandTime.plan]): a cold night kills within the week,
 * a hungry week is bridged by the larder and fat.
 *
 * A shelter is not this week's alone: it stands, once put up, for as long as the band keeps the camp, and what it averts
 * through the stay counts with this week's. The stay ahead is taken as the weeks the band keeps a camp ([Band.stayWeeks])
 * less this one, wherever it is in its stay (moves are chosen afresh each week; an estimate), its months at the place's usual
 * temperatures as the year ahead's are ([yearAhead]), from next month on; past the stay, the year ahead stays as foreseen.
 * Valued by this week alone, the shelters a band came to a new camp to put up averted nothing in a mild week and were left to
 * the other chores' share, and full weeks put up less than nine tenths of what they aimed at four times in five (the
 * squeeze census, 2026-10-27), while bands with no days to spare died of exposure two to four times as often.
 *
 * The food itself is not priced so: what a band foresees of its hunger is by the food it got in each month over the last
 * years, which in its first year it has none of (all foreseen dead whatever is done now) and which does not see the fat a
 * short week burns, so a shortfall would look free (a 2026-10-24 check: hunger deaths ten times main's). Holding the warmth
 * only after the food, pro rata, left bands that had walked most of their week a tenth of a fire (exposure five times main's).
 *
 * The days go a few at a time to whichever of the two averts the more deaths for its days (or for all it asks, where a
 * little does nothing, as wood in a cold too deep for half a fire), until each has all it asks, neither averts any more, or
 * the week is spent. (The deaths are reckoned, not drawn, so there is no chance to allow for.) What they ask beyond, the
 * days that avert no death (wood in a warm week), is shared with the other chores.
 *
 * [foodAsk]: the person-days of work that would forage the rest of the week's want, [gotShare] of it got already;
 * [fuelAsk]: those that would fetch all the hearth asks ([fuelAsk]); [moved]: whether it has come to a new camp, where no
 * shelter stands. Returns the person-days of work for the firewood and the shelters (none for the rest).
 */
internal fun HexGroups.survivalDays(g: HexGroup, b: Band, weather: Weather, time: BandTime, hands: Int, foodAsk: Float, gotShare: Float,
    fuelAsk: Float, moved: Boolean): EnumFloats<Activity> {
    val first = enumFloats<Activity>()
    val s = g.species; val p = g.place; val people = g.adults.toFloat()
    val year = yearAhead(g, b, weather)
    val air = airAt(p, weather); val home = b.homeShelter()
    // (What all the wood asked for keeps of the hearth: less where wood is so thin that the most a band gives it falls short, [fuelShare].)
    val cover = woodCover(p)
    val hearthWant = HEARTH_WOOD * g.size
    val hearthAll = if (hearthWant <= 0f) 1f else if (cover <= 0f) 0f else hearthShare(cover, g.size.toFloat() / max(1, hands))
    val shelter0 = if (moved) 0f else b.shelter
    val shelterAsk = max(0f, b.shelterWill - shelter0) * shelterDays(b, people)
    val asks = FloatArray(3); asks[FOOD] = foodAsk; asks[FIRE] = fuelAsk; asks[SHELTER] = shelterAsk
    val acts = arrayOf(Activity.FOOD, Activity.FIREWOOD, Activity.SHELTER)
    // (Its body heat at each fire and shelter, worked out once a week for each, to the hundredth: a fire cut short by a long
    // walk is a small share of one, and coarser steps would round it to none.)
    val warmthAt = HashMap<Int, Warmth>()
    fun warmth(fire: Float, shelter: Float): Warmth {
        val f = (fire * GRID).roundToInt(); val sh = (shelter * GRID).roundToInt()
        return warmthAt.getOrPut(f * 10_000 + sh) { b.warmth(air, f / GRID, home, days, sh / GRID) }
    }
    val larderDays = g.larder / g.dailyNeedKg.coerceAtLeast(1e-3f)
    // (The months of the stay ahead, after this week, and each month's exposure odds through them by the shelter standing,
    // worked out once for each step of [STAY_GRID] and taken between the two steps either side. Only where it would build.)
    val stayMonths = if (shelterAsk > 0f) max(0f, b.stayWeeks - 1f) * Date.MONTHS_PER_YEAR / stepsPerYear else 0f
    val months = min(Date.MONTHS_PER_YEAR, ceil(stayMonths).toInt())
    val stayOddsAt = HashMap<Int, Float>()
    fun stayOdds(i: Int, step: Int) = stayOddsAt.getOrPut(i * 1_000 + step) {
        val t = weather.usualTemperature((weather.date.month + i + 1) % Date.MONTHS_PER_YEAR, p)
        b.warmth(airAt(p, weather, t), b.fire, home, Date.DAYS_PER_MONTH, step / STAY_GRID).odds
    }
    val yearOdds = FloatArray(Date.MONTHS_PER_YEAR) { year.odds(it) }
    fun aheadOdds(shelter: Float): FloatArray {
        if (months == 0) return yearOdds
        val x = shelter.coerceAtLeast(0f) * STAY_GRID; val lo = floor(x).toInt(); val f = x - lo
        for (i in 0 until months) {
            val stayed = min(1f, stayMonths - i)
            val o = stayOdds(i, lo) * (1f - f) + (if (f > 0f) stayOdds(i, lo + 1) * f else 0f)
            yearOdds[i] = stayed * o + (1f - stayed) * year.odds(i)
        }
        return yearOdds
    }
    fun deaths(d: FloatArray): Float {
        val fire = if (asks[FIRE] > 0f) hearthAll * min(1f, d[FIRE] / asks[FIRE]) else hearthAll
        val shelter = shelter0 + if (asks[SHELTER] > 0f) (b.shelterWill - shelter0) * min(1f, d[SHELTER] / asks[SHELTER]) else 0f
        val w = warmth(fire, shelter)
        // (The want was reckoned at the fire it had: a warmer or colder week needs less or more, [warmthNeed].)
        val needs = warmthNeed(w, fire) / g.warmth.coerceAtLeast(1e-3f)
        var fed = (gotShare + (1f - gotShare) * (if (asks[FOOD] > 0f) min(1f, d[FOOD] / asks[FOOD]) else 1f)) / needs
        val paid = min(max(0f, 1f - fed) * days, larderDays); fed += paid / days
        val onFat = Hazards.onFat(fed, g.fat, days)
        val fat = max(0f, g.fat - (onFat - fed) * days)
        val hunger = Hazards.hunger(s, g.spent, onFat, days)
        // (This week's deaths and the year after's, each counted in full: a year that looks bleak whatever is done now, as
        // it does before a band has a year's food behind it to foresee by, takes nothing from what this week's days do.)
        val week = (1f - w.odds) * (1f - hunger)
        return 1f - week + 1f - year.alive(s, larderDays - paid, fat, Hazards.spentAfter(s, g.spent, onFat, days), aheadOdds(shelter))
    }
    // (Reckoned with all the week's food it wants: see above.)
    val d = FloatArray(3); d[FOOD] = asks[FOOD]
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
    b.planFire = if (asks[FIRE] > 0f) hearthAll * min(1f, d[FIRE] / asks[FIRE]) else hearthAll
    val planShelter = shelter0 + if (asks[SHELTER] > 0f) (b.shelterWill - shelter0) * min(1f, d[SHELTER] / asks[SHELTER]) else 0f
    b.planOdds = warmth(b.planFire, planShelter).odds
    if (WARM_DIAG) { b.planAir = air; b.planCover = b.cover(air.meanC, b.planFire, home, planShelter); b.planSisu = b.sisu }
    b.planLeft = if (time.total > 0f) time.left / time.total else 0f
    b.planShelterFirst = if (asks[SHELTER] > 0f) min(1f, d[SHELTER] / asks[SHELTER]) else -1f
    return first
}

private const val FOOD = 0
private const val FIRE = 1
private const val SHELTER = 2
/** The pieces the week is given out in ([survivalDays]); and the steps of fire and shelter its body heat is reckoned at. */
private const val CHUNKS = 40f
private const val GRID = 100f
/** The steps of shelter the months of a stay ahead are reckoned at ([survivalDays]): fewer, as each is a month's reckoning. */
private const val STAY_GRID = 10f
/** Fewer deaths than this, a share of the band, are no deaths averted: the rounding in the reckoning, not a difference. */
private const val AVERTS_NONE = 1e-6f
