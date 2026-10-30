package inflation.ecology

import inflation.Config
import inflation.world.get
import inflation.world.Biome
import inflation.world.Date
import inflation.world.Weather
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Foreseen survival: the share of band [b]'s (group [g]'s) people alive a year ahead, as the band stands now and by the
 * sim's own rules, month by month at the place's usual temperatures (the wind, rain and snow as this week's there):
 * - exposure, by its people's body heat ([Band.warmth]) with its clothes, fire, the shelters it puts up and, at home,
 *   its houses;
 * - hunger, by the food it got in each month over the last years ([Band.monthSupply]: net of its larder, so what it laid
 *   by and lived off then) against what keeping warm will take then ([warmthNeed]), what it lays by going to its larder,
 *   its larder and then its fat paying the shortfalls as they come, and its people burning their body's protein past that
 *   ([Hazards.hunger]); over a bad, a usual and a good year, as far apart as its years have been ([Band.supplySpread]);
 * - what it cannot carry when it moves camp, at its usual stay ([Band.stayWeeks]), lost;
 * - and its kind's background deaths.
 * Not foreseen: disease, outbreaks, raids, beasts, fire and flood, thirst; tools and clothes wearing out over the year; the
 * larder it has now spoiling (what spoilt of what it laid by before is in its months). The food a band will get is last
 * years', so what it does differently now shows only through its stores, its fat and what keeping warm takes. What training may judge a band's state by ([HexGroup.foreseen]).
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
    val alive = year.alive(g.species, larderDays, g.fat, g.spent)
    // (And what a camp move would cost it, for next week's choice of place: [Band.leavingDying].)
    val share = if (b.campPlace.none) 0f else leftBehindShare(g, b)
    b.leavingDying = if (share > 0f) max(0f, alive - year.alive(g.species, larderDays * (1f - share), g.fat, g.spent)) else 0f
    return alive
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
    val odds = FloatArray(Date.MONTHS_PER_YEAR); val fed = FloatArray(Date.MONTHS_PER_YEAR); val spread = FloatArray(Date.MONTHS_PER_YEAR)
    for (k in 1..Date.MONTHS_PER_YEAR) {
        val m = (now + k) % Date.MONTHS_PER_YEAR
        val air = airAt(p, weather, weather.usualTemperature(m, p))
        val w = b.warmth(air, b.fire, home, Date.DAYS_PER_MONTH, b.shelter, clothes)
        // (The food it got then, against what keeping warm will take, as [warmthNeed] has it: more food to keep warm is less
        // of the need met.)
        val need = warmthNeed(w, b.fire)
        odds[k - 1] = w.odds; fed[k - 1] = b.monthSupply[m] / need; spread[k - 1] = b.supplySpread(m) / need
        // (The share of the month it is still at its camp, under the shelters it weighs.)
        val stays = ((stayDays - (k - 1) * Date.DAYS_PER_MONTH) / Date.DAYS_PER_MONTH).coerceIn(0f, 1f)
        if (stays > 0f && shelter != b.shelter) {
            val ws = b.warmth(air, b.fire, home, Date.DAYS_PER_MONTH, shelter, clothes)
            val needS = warmthNeed(ws, b.fire)
            odds[k - 1] += stays * (ws.odds - w.odds)
            fed[k - 1] += stays * (b.monthSupply[m] / needS - fed[k - 1])
            spread[k - 1] += stays * (b.supplySpread(m) / needS - spread[k - 1])
        }
    }
    // (The months it moves camp in, at its usual stay ([Band.stayWeeks]) from the weeks it has kept this one, as
    // [survivalDays] reckons its stay: on a move it keeps only what it can carry ([foodCarried]). A band that has not
    // moved yet, or keeps its camp a year and more, moves in none.)
    val moves = BooleanArray(Date.MONTHS_PER_YEAR)
    if (!b.campPlace.none) {
        val stay = max(1f, b.stayWeeks); val perMonth = stepsPerYear / Date.MONTHS_PER_YEAR
        var week = max(1f, stay - b.campWeeks)
        while (week < stepsPerYear) { moves[(week / perMonth).toInt().coerceAtMost(Date.MONTHS_PER_YEAR - 1)] = true; week += stay }
    }
    return YearAhead(odds, fed, spread, foodCarried(g, b) / g.dailyNeedKg.coerceAtLeast(1e-3f), moves)
}


/**
 * The months ahead in order from next month ([yearAhead]): the exposure odds, the share of the need the food it got then
 * meets ([fed], above 1 where it laid food by) and how far that share has gone from year to year ([spread], a standard
 * deviation; 0 for none seen); the months it [moves] camp in, keeping only the [carryDays] of its need it can carry.
 */
internal class YearAhead(private val odds: FloatArray, internal val fed: FloatArray, internal val spread: FloatArray = FloatArray(fed.size),
    private val carryDays: Float = Float.POSITIVE_INFINITY, private val moves: BooleanArray = BooleanArray(fed.size)) {
    /** The days of food it falls short of over the months ahead, before its larder pays ([Band.leanDays]), in a usual year. */
    val leanDays: Float = run { var lean = 0f; for (f in fed) lean += max(0f, 1f - f) * Date.DAYS_PER_MONTH; lean }
    /** Whether its years have differed at all: if not, one year is reckoned, not [YEARS]. */
    private val spreads = YEAR_SPREAD && spread.any { it > SPREAD_NONE }

    /**
     * The share of people of kind [s] alive through the months ahead, starting with [larderDays] of the need in the larder,
     * [fat] days of fat and [spent] days of body protein burnt: what it lays by in a month goes to the larder, and the larder
     * and then the fat pay the shortfalls as they come. Over a bad, a usual and a good year ([YEARS]), each its months as
     * much better or worse as its years have been ([spread]), all together: a bad year is bad all through.
     */
    fun alive(s: Species, larderDays: Float, fat: Float, spent: Float): Float {
        if (!spreads) return year(s, larderDays, fat, spent, 0f, null, null)
        var alive = 0f
        for (i in YEARS.indices) alive += YEAR_WEIGHTS[i] * year(s, larderDays, fat, spent, YEARS[i], null, null)
        return alive
    }

    /**
     * The months ahead as [alive] reckons them for kind [s] from [larderDays], [fat] and [spent], one line a month of a
     * usual year: the share of the need the food meets, the larder days paid (laid by, below 0) and left, how well fed on the
     * fat, the fat days left, the body's protein burnt, and the hunger and exposure odds; then the share alive in a bad, a
     * usual and a good year. For tracing ([PLAN_TRACE]).
     */
    fun trace(s: Species, larderDays: Float, fat: Float, spent: Float): String {
        val sb = StringBuilder()
        year(s, larderDays, fat, spent, 0f, null, sb)
        sb.append("  alive in a bad, usual and good year: ")
        sb.append(YEARS.joinToString(" / ") { "%.4f".format(year(s, larderDays, fat, spent, if (spreads) it else 0f, null, null)) }).append('\n')
        return sb.toString()
    }

    /**
     * The shares of people of kind [s] foreseen to die through the months ahead of hunger and of exposure, as [alive] reckons
     * them (each month's deaths of each among those still alive then), into [out] (hunger, exposure). For the census.
     */
    fun causes(s: Species, larderDays: Float, fat: Float, spent: Float, out: FloatArray) {
        out[0] = 0f; out[1] = 0f
        if (!spreads) { year(s, larderDays, fat, spent, 0f, out, null); return }
        val one = FloatArray(2)
        for (i in YEARS.indices) {
            one[0] = 0f; one[1] = 0f
            year(s, larderDays, fat, spent, YEARS[i], one, null)
            out[0] += YEAR_WEIGHTS[i] * one[0]; out[1] += YEAR_WEIGHTS[i] * one[1]
        }
    }

    /**
     * One year ahead [z] standard deviations of its years better than the usual ([spread]): the share alive at its end; the
     * deaths of hunger and of exposure added into [causes], and its months into [sb], where given.
     */
    @IgnorableReturnValue private fun year(s: Species, larderDays: Float, fat: Float, spent: Float, z: Float, causes: FloatArray?, sb: StringBuilder?): Float {
        val days = Date.DAYS_PER_MONTH
        var larder = larderDays; var fatLeft = fat; var burnt = spent
        var alive = 1f
        for (k in odds.indices) {
            // (A month it moves camp in, it keeps only what it carries.)
            if (moves[k]) larder = min(larder, carryDays)
            val got = max(0f, fed[k] + z * spread[k])
            // (What it gets beyond its need it lays by, as it did in that month before; a shortfall the larder pays.)
            val paid = if (got > 1f) -(got - 1f) * days else min((1f - got) * days, larder)
            larder -= paid
            val ate = min(1f, got + max(0f, paid) / days)
            val onFat = Hazards.onFat(ate, fatLeft, days); fatLeft = max(0f, fatLeft - (onFat - ate) * days)
            val hunger = Hazards.hunger(s, burnt, onFat, days); burnt = Hazards.spentAfter(s, burnt, onFat, days)
            if (causes != null) { causes[1] += alive * odds[k]; causes[0] += alive * (1f - odds[k]) * hunger }
            sb?.append("  +%d: fed %.2f paid %.1f larder %.1f onFat %.2f fat %.1f burnt %.1f hunger %.3f exposure %.3f\n".format(k + 1, got, paid, larder, onFat, fatLeft, burnt, hunger, odds[k]))
            alive *= (1f - odds[k]) * (1f - hunger) * (1f - s.backgroundMortality / Date.MONTHS_PER_YEAR)
        }
        return alive
    }

    companion object {
        /**
         * The years [YearAhead.alive] reckons, standard deviations from the usual, and how much each counts: the three-point
         * Gauss-Hermite rule, which weighs a normal spread of years exactly for anything up to its fifth power.
         */
        private val YEARS = floatArrayOf(-sqrt(3f), 0f, sqrt(3f))
        private val YEAR_WEIGHTS = floatArrayOf(1f / 6f, 2f / 3f, 1f / 6f)
        /** A spread smaller than this, of a month's share of the need, is none: its years have been alike. */
        private const val SPREAD_NONE = 1e-4f
    }
}

/**
 * Each band's week: the food it got this week, net of its larder, added to its month's and so to what it got in that month
 * over the last years ([Band.addSupply]); and its foreseen survival a year ahead ([foreseenSurvival]) into [HexGroup.foreseen].
 * (Before, what it ate, its larder's and fat's share in it: a band that lived off its larder in a lean month foresaw it well
 * fed, larder or none, and one that laid by never refilled it. 90% of people-weeks foresaw 0.4% dead and 4 to 6% died; the
 * 6% that foresaw nearly all dead lost 5 to 13% (calib461, 2026-10-30).)
 */
internal fun HexGroups.foresee(weather: Weather) {
    val m = weather.date.month
    val k = 1f / (stepsPerYear / Date.MONTHS_PER_YEAR)
    // (Each band on its own: on all cores.)
    groups.parallelStream().forEach { g ->
        val b = g.band
        if (b != null && g.alive) {
            // (What it got as a share of its need before keeping warm, net of its larder: what it ate ([HexGroup.fed], of
            // the need with keeping warm) less what its fat gave ([Band.fatFed]), and what its larder gained since last week,
            // or lost: laid by, or eaten from it, spoilt, raided or given. The larder is counted in days of its people's need
            // before keeping warm, so a split, which halves both, or a colder week changes nothing.)
            val need = (g.dailyNeedKg / g.warmth.coerceAtLeast(1e-3f)).coerceAtLeast(1e-3f)
            val larder = g.larder / need
            // (What it cached or left on a move is not food lost as its months count it: the forecast foresees its moves on
            // their own, [yearAhead].)
            val laid = if (b.larderSeen.isNaN()) 0f else (larder - b.larderSeen + b.leftBehind / need) / days
            b.larderSeen = larder; b.leftBehind = 0f
            val got = (g.fed - b.fatFed) * g.warmth + laid
            b.addSupply(m, got, k)
            b.fatFed = 0f
            b.forecastTrace?.let { t -> t.week(m, got, larder, g.fat, g.spent, g.size, g.path.size > 1)
                if (t.over) { System.err.print(t.report("a year followed")); b.forecastTrace = null } }
            g.foreseen = foreseenSurvival(g, b, weather)
        }
    }
    // (Tallied by the kind of country, against the deaths that come: in order, off the cores.)
    for (g in groups) { val b = g.band ?: continue; if (!g.alive) continue
        val c = Biome.entries[biomeOfPlace[g.place]]
        stats.hunting.merge(Tally.bandsIn(c, Tally.Bands.FORESEEN), (1000f * (1f - g.foreseen) * g.size).roundToInt(), Int::plus)
        // (And by how many it foresaw dead, for the calibration against the deaths that came in the year after: [bookCalibration].)
        val bin = ForeseenBin.of(1f - g.foreseen)
        b.foreseenRing[b.foreseenAt] = bin.ordinal.toByte(); b.foreseenAt = (b.foreseenAt + 1) % FORESEEN_WEEKS
        stats.hunting.merge(Tally.calibration(b.mind, bin, Tally.Calib.PEOPLE_WEEKS), g.size, Int::plus)
        stats.hunting.merge(Tally.calibration(b.mind, bin, Tally.Calib.FORESEEN), (1000f * (1f - g.foreseen) * g.size).roundToInt(), Int::plus)
        stats.hunting.merge(Tally.bandsIn(c, Tally.Bands.SISU), (1000f * b.sisu * g.size).roundToInt(), Int::plus)
        if (FORECAST_TRACE) traceForecast(g, b, weather)
    }
}

/** yearSpread=false: the year ahead is reckoned as one usual year ([YearAhead.alive]), for comparing. */
internal val YEAR_SPREAD = Config.flag(Config.Key.YEAR_SPREAD, true)

/** Weeks back a band's deaths are booked against what it foresaw ([Band.foreseenRing]): the year its forecast looks ahead. */
const val FORESEEN_WEEKS = 52

/** Bands' forecasts by the share of their people they foresee dead in the year ahead ([HexGroup.foreseen]), for the calibration. */
enum class ForeseenBin(val label: String, val below: Float) {
    UNDER_1("under 1%", 0.01f), UNDER_5("1-5%", 0.05f), UNDER_20("5-20%", 0.2f), UNDER_50("20-50%", 0.5f), UNDER_90("50-90%", 0.9f),
    MOST("90% and over", Float.POSITIVE_INFINITY);
    companion object { fun of(dead: Float) = entries.first { dead < it.below } }
}

/**
 * Books [n] deaths of [cause] in band [b] against each of the forecasts it made in the year before them ([Band.foreseenRing]):
 * over many band-weeks, the deaths that came in the year after a forecast in each [ForeseenBin], to set against the people
 * foreseen and how many of them were foreseen dead. For the census only.
 */
internal fun HexGroups.bookCalibration(b: Band, cause: DeathReason, n: Int) {
    val counts = IntArray(ForeseenBin.entries.size)
    for (k in b.foreseenRing) if (k >= 0) counts[k.toInt()]++
    for (i in counts.indices) if (counts[i] > 0) {
        val bin = ForeseenBin.entries[i]
        stats.hunting.merge(Tally.calibration(b.mind, bin, Tally.Calib.DIED), n * counts[i], Int::plus)
        if (cause == DeathCause.HUNGER) stats.hunting.merge(Tally.calibration(b.mind, bin, Tally.Calib.HUNGER), n * counts[i], Int::plus)
    }
    b.forecastTrace?.died(n, cause == DeathCause.HUNGER)
}
