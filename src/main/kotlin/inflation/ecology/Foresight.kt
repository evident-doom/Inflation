package inflation.ecology

import inflation.world.get
import inflation.world.Biome
import inflation.world.Date
import inflation.world.Weather
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Foreseen survival: the share of band [b]'s (group [g]'s) people alive a year ahead, as the band stands now and by the
 * sim's own rules, month by month at the place's usual temperatures (the wind, rain and snow as this week's there):
 * - exposure, by its people's body heat ([Band.warmth]) with its clothes, fire, the shelters it puts up and, at home,
 *   its houses;
 * - hunger, by the food it got in each month over the last years ([Band.monthSupply]) against what keeping warm will take
 *   then ([warmthNeed]), its larder and then its fat paying the shortfalls as they come, and its people burning their
 *   body's protein past that ([Hazards.hunger]);
 * - and its kind's background deaths.
 * Not foreseen: disease, outbreaks, raids, beasts, fire and flood, thirst; tools and clothes wearing out over the year; the
 * larder spoiling. The food a band will get is last years', so what it does differently now shows only through its
 * stores, its fat and what keeping warm takes. What training may judge a band's state by ([HexGroup.foreseen]).
 * On the way it reckons the days of food the band falls short of over the year before its larder pays ([Band.leanDays]).
 */
internal fun HexGroups.foreseenSurvival(g: HexGroup, b: Band, weather: Weather): Float {
    val year = yearAhead(g, b, weather)
    b.leanDays = year.leanDays
    val larderDays = g.larder / g.dailyNeedKg.coerceAtLeast(1e-3f)
    // (Of the deaths it foresees, those of hunger and of exposure, by the kind of country, for the census: whether the
    // forecast sees the hunger that comes. Whole numbers summed, so the order the cores add them in changes nothing.)
    val causes = FloatArray(2)
    year.causes(g.species, larderDays, g.fat, g.spent, causes)
    val c = Biome.entries[biomeOfPlace[g.place]]
    stats.hunting.merge(Tally.bandsIn(c, Tally.Bands.FORESEEN_HUNGER), (1000f * causes[0] * g.size).roundToInt(), Int::plus)
    stats.hunting.merge(Tally.bandsIn(c, Tally.Bands.FORESEEN_EXPOSURE), (1000f * causes[1] * g.size).roundToInt(), Int::plus)
    return year.alive(g.species, larderDays, g.fat, g.spent)
}

/**
 * The months of the year ahead as [foreseenSurvival] reckons them for band [b] (group [g]), worked out once: each month's
 * exposure odds and the share of its need the food it got then meets, against what keeping warm will take then. Through
 * the [stayDays] it expects to keep its camp its shelters stand at [shelter] (the week's survival pass weighs a shelter by
 * it, [survivalDays]); otherwise, and after, as they are. Its clothes are a kit of [clothes] all year (the pass weighs
 * making them by it; their wearing out is not foreseen, as for the kit it has).
 */
internal fun HexGroups.yearAhead(g: HexGroup, b: Band, weather: Weather, shelter: Float = b.shelter, stayDays: Float = 0f,
    clothes: Float = b.has(Tool.CLOTHES)): YearAhead {
    val p = g.place; val now = weather.date.month
    val home = b.homeShelter()
    val odds = FloatArray(Date.MONTHS_PER_YEAR); val fed = FloatArray(Date.MONTHS_PER_YEAR)
    for (k in 1..Date.MONTHS_PER_YEAR) {
        val m = (now + k) % Date.MONTHS_PER_YEAR
        val air = airAt(p, weather, weather.usualTemperature(m, p))
        val w = b.warmth(air, b.fire, home, Date.DAYS_PER_MONTH, b.shelter, clothes)
        // (The food it got then, against what keeping warm will take, as [warmthNeed] has it: more food to keep warm is less
        // of the need met.)
        odds[k - 1] = w.odds; fed[k - 1] = b.monthSupply[m] / warmthNeed(w, b.fire)
        // (The share of the month it is still at its camp, under the shelters it weighs.)
        val stays = ((stayDays - (k - 1) * Date.DAYS_PER_MONTH) / Date.DAYS_PER_MONTH).coerceIn(0f, 1f)
        if (stays > 0f && shelter != b.shelter) {
            val ws = b.warmth(air, b.fire, home, Date.DAYS_PER_MONTH, shelter, clothes)
            odds[k - 1] += stays * (ws.odds - w.odds)
            fed[k - 1] += stays * (b.monthSupply[m] / warmthNeed(ws, b.fire) - fed[k - 1])
        }
    }
    return YearAhead(odds, fed)
}

/** The months ahead in order from next month ([yearAhead]): the exposure odds and the share of the need met in each. */
internal class YearAhead(private val odds: FloatArray, private val fed: FloatArray) {
    /** The days of food it falls short of over the months ahead, before its larder pays ([Band.leanDays]). */
    val leanDays: Float = run { var lean = 0f; for (f in fed) lean += max(0f, 1f - f) * Date.DAYS_PER_MONTH; lean }

    /**
     * The share of people of kind [s] alive through the months ahead, starting with [larderDays] of the need in the larder,
     * [fat] days of fat and [spent] days of body protein burnt: the larder and then the fat pay the shortfalls as they come.
     */
    fun alive(s: Species, larderDays: Float, fat: Float, spent: Float): Float {
        val days = Date.DAYS_PER_MONTH
        var larder = larderDays; var fatLeft = fat; var burnt = spent
        var alive = 1f
        for (k in odds.indices) {
            var fed = fed[k]
            val paid = min(max(0f, 1f - fed) * days, larder); larder -= paid; fed += paid / days
            val onFat = Hazards.onFat(fed, fatLeft, days); fatLeft = max(0f, fatLeft - (onFat - fed) * days)
            val hunger = Hazards.hunger(s, burnt, onFat, days); burnt = Hazards.spentAfter(s, burnt, onFat, days)
            alive *= (1f - odds[k]) * (1f - hunger) * (1f - s.backgroundMortality / Date.MONTHS_PER_YEAR)
        }
        return alive
    }

    /**
     * The months ahead as [alive] reckons them for kind [s] from [larderDays], [fat] and [spent], one line a month: the share
     * of the need the food meets, the larder days paid and left, how well fed on the fat, the fat days left, the body's
     * protein burnt, and the hunger and exposure odds. For tracing ([PLAN_TRACE]).
     */
    fun trace(s: Species, larderDays: Float, fat: Float, spent: Float): String {
        val days = Date.DAYS_PER_MONTH
        var larder = larderDays; var fatLeft = fat; var burnt = spent
        val sb = StringBuilder()
        for (k in odds.indices) {
            var fed = fed[k]
            val paid = min(max(0f, 1f - fed) * days, larder); larder -= paid; val got = fed; fed += paid / days
            val onFat = Hazards.onFat(fed, fatLeft, days); fatLeft = max(0f, fatLeft - (onFat - fed) * days)
            val hunger = Hazards.hunger(s, burnt, onFat, days); burnt = Hazards.spentAfter(s, burnt, onFat, days)
            sb.append("  +%d: fed %.2f paid %.1f larder %.1f onFat %.2f fat %.1f burnt %.1f hunger %.3f exposure %.3f\n".format(k + 1, got, paid, larder, onFat, fatLeft, burnt, hunger, odds[k]))
        }
        return sb.toString()
    }

    /**
     * The shares of people of kind [s] foreseen to die through the months ahead of hunger and of exposure, as [alive] reckons
     * them (each month's deaths of each among those still alive then), into [out] (hunger, exposure). For the census.
     */
    fun causes(s: Species, larderDays: Float, fat: Float, spent: Float, out: FloatArray) {
        val days = Date.DAYS_PER_MONTH
        var larder = larderDays; var fatLeft = fat; var burnt = spent
        var alive = 1f; out[0] = 0f; out[1] = 0f
        for (k in odds.indices) {
            var fed = fed[k]
            val paid = min(max(0f, 1f - fed) * days, larder); larder -= paid; fed += paid / days
            val onFat = Hazards.onFat(fed, fatLeft, days); fatLeft = max(0f, fatLeft - (onFat - fed) * days)
            val hunger = Hazards.hunger(s, burnt, onFat, days); burnt = Hazards.spentAfter(s, burnt, onFat, days)
            out[1] += alive * odds[k]; out[0] += alive * (1f - odds[k]) * hunger
            alive *= (1f - odds[k]) * (1f - hunger) * (1f - s.backgroundMortality / Date.MONTHS_PER_YEAR)
        }
    }
}

/**
 * Each band's week: the food it got this month, folded into what it got in that month over the last years
 * ([Band.monthSupply]); and its foreseen survival a year ahead ([foreseenSurvival]) into [HexGroup.foreseen].
 */
internal fun HexGroups.foresee(weather: Weather) {
    val m = weather.date.month
    val k = 1f / (stepsPerYear / Date.MONTHS_PER_YEAR)
    // (Each band on its own: on all cores.)
    groups.parallelStream().forEach { g ->
        val b = g.band
        if (b != null && g.alive) {
            // (What it got as a share of its need before keeping warm: [HexGroup.fed] is of the need with it, and counts the
            // fat it drew on, so lean months look a little better fed than they were.)
            b.monthSupply[m] += k * (g.fed * g.warmth - b.monthSupply[m])
            g.foreseen = foreseenSurvival(g, b, weather)
        }
    }
    // (Tallied by the kind of country, against the deaths that come: in order, off the cores.)
    for (g in groups) { val b = g.band ?: continue; if (!g.alive) continue
        val c = Biome.entries[biomeOfPlace[g.place]]
        stats.hunting.merge(Tally.bandsIn(c, Tally.Bands.FORESEEN), (1000f * (1f - g.foreseen) * g.size).roundToInt(), Int::plus)
        stats.hunting.merge(Tally.bandsIn(c, Tally.Bands.SISU), (1000f * b.sisu * g.size).roundToInt(), Int::plus)
    }
}
