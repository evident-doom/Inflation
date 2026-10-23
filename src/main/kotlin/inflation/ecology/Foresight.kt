package inflation.ecology

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
 */
internal fun HexGroups.foreseenSurvival(g: HexGroup, b: Band, weather: Weather): Float {
    val s = g.species; val p = g.place; val now = weather.date.month
    val days = Date.DAYS_PER_MONTH
    val home = b.homeShelter()
    var larder = g.larder / g.dailyNeedKg.coerceAtLeast(1e-3f)
    var fat = g.fat; var spent = g.spent
    var alive = 1f
    for (k in 1..Date.MONTHS_PER_YEAR) {
        val m = (now + k) % Date.MONTHS_PER_YEAR
        val t = weather.usualTemperature(m, p)
        val w = b.warmth(airAt(p, weather, t), b.fire, home, days)
        // (The food it got then, against what keeping warm will take, as [warmthNeed] has it: more food to keep warm is less
        // of the need met.)
        var fed = b.monthSupply[m] / warmthNeed(w, b.fire)
        val paid = min(max(0f, 1f - fed) * days, larder); larder -= paid; fed += paid / days
        val onFat = Hazards.onFat(fed, fat, days); fat = max(0f, fat - (onFat - fed) * days)
        val hunger = Hazards.hunger(s, spent, onFat, days); spent = Hazards.spentAfter(s, spent, onFat, days)
        alive *= (1f - w.odds) * (1f - hunger) * (1f - s.backgroundMortality / Date.MONTHS_PER_YEAR)
    }
    return alive
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
