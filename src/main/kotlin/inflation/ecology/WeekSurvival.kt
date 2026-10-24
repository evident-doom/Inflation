package inflation.ecology

import inflation.world.Weather
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The days [g]'s band [b] holds first this week to keep its people alive (survival first): all the week's food it wants,
 * and of the hearth's firewood and the camp's shelters as much as averts deaths, by the sim's own reckoning: this week's
 * exposure by its people's body heat ([Band.warmth]) with the fire the wood keeps and the shelters it puts up, and with
 * them what keeping warm takes of its food ([warmthNeed]) and so its hunger, this week and through the year after
 * ([yearAhead]), each counted in full.
 *
 * The food is not weighed against the warmth: what a band foresees of its hunger is by the food it got in each month over
 * the last years, which in its first year it has none of (all foreseen dead whatever is done now) and which does not see
 * the fat a short week burns, so a shortfall would look free (a 2026-10-24 check: hunger deaths ten times main's).
 *
 * The firewood and shelters' days go a few at a time to whichever averts the more deaths for its days (or for all it asks,
 * where a little does nothing, as wood in a cold too deep for half a fire), until each has all it asks, neither averts any
 * more, or the week is spent. (The deaths are reckoned, not drawn, so there is no chance to allow for.) When the food and
 * these come to more than the week, [BandTime.plan] shares the week among them pro rata. What they ask beyond, the days that
 * avert no death (wood in a warm week), is shared with the other chores.
 *
 * [foodAsk]: the person-days of work that would forage the rest of the week's want, [gotShare] of it got already;
 * [fuelAsk]: those that would fetch all the hearth asks ([fuelAsk]); [moved]: whether it has come to a new camp, where no
 * shelter stands. Returns the person-days of work for each [Activity] (none but these three).
 */
internal fun HexGroups.survivalDays(g: HexGroup, b: Band, weather: Weather, time: BandTime, hands: Int, foodAsk: Float, gotShare: Float,
    fuelAsk: Float, moved: Boolean): FloatArray {
    val first = FloatArray(Activity.entries.size)
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
    // (Its body heat at each fire and shelter, worked out once a week for each, to the twentieth.)
    val warmthAt = HashMap<Int, Warmth>()
    fun warmth(fire: Float, shelter: Float): Warmth {
        val f = (fire * GRID).roundToInt(); val sh = (shelter * GRID).roundToInt()
        return warmthAt.getOrPut(f * 10_000 + sh) { b.warmth(air, f / GRID, home, days, sh / GRID) }
    }
    val larderDays = g.larder / g.dailyNeedKg.coerceAtLeast(1e-3f)
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
        return 1f - week + 1f - year.alive(s, larderDays - paid, fat, Hazards.spentAfter(s, g.spent, onFat, days))
    }
    // (The week's food it wants is held in full: see above.)
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
    for (i in 0 until 3) first[acts[i].ordinal] = d[i]
    return first
}

private const val FOOD = 0
private const val FIRE = 1
private const val SHELTER = 2
/** The pieces the week is given out in ([survivalDays]); and the steps of fire and shelter its body heat is reckoned at. */
private const val CHUNKS = 40f
private const val GRID = 20f
/** Fewer deaths than this, a share of the band, are no deaths averted: the rounding in the reckoning, not a difference. */
private const val AVERTS_NONE = 1e-6f
