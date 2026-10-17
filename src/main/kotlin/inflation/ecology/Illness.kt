package inflation.ecology

import inflation.world.Date
import inflation.world.Weather
import kotlin.math.max
import kotlin.math.min

/**
 * Sicknesses of people, each with its own way of coming and going: how long a bout lasts (weeks),
 * the share of the sick who die of it over a bout, how much harder it falls on children, how readily
 * it passes from band to band when they meet (0: it does not), and how long those who lived through
 * it are spared (years); [endemic] is the share spared when the world begins (people have lived with
 * these sicknesses for ages: the first year is not a plague year).
 */
enum class Illness(val label: String, val weeks: Float, val deadly: Float, val young: Float, val contagion: Float, val immuneYears: Float, val endemic: Float) {
    /** Dysentery and its kin: fouled water and long-kept camps, worst in the warm months and in crowds. */
    FLUX("the flux", 3f, 0.03f, 3f, 0.25f, 2f, 0.4f),
    /** Malaria: carried by the mosquitoes about the camp in the warm months; not caught from one another. */
    MARSH_FEVER("marsh fever", 6f, 0.04f, 3f, 0f, 4f, 0.3f),
    /** A virulent cough that runs from band to band, worst in winter and at gatherings, and can empty a camp. */
    COUGH("the cough", 4f, 0.08f, 2f, 0.6f, 20f, 0.6f),
}

/**
 * A band's sicknesses this week: new bouts from where it is and how it lives (fouled camps, marsh in
 * the warm months, now and then a cough breaking out where people crowd), bouts ending in recovery
 * and a spell of immunity; returns, for each sickness, the weekly share of adults it kills (children
 * by [Illness.young] times that).
 */
internal fun HexGroups.illnessWeek(g: HexGroup, b: Band, weather: Weather, r: kotlin.random.Random): FloatArray {
    val p = g.place
    val t = weather.temperature[p]
    val hygiene = b.hygiene
    val crowd = peopleCrowding(g)
    val deaths = FloatArray(Illness.entries.size)
    for (d in Illness.entries) {
        val i = d.ordinal
        val open = (1f - b.sick[i] - b.immune[i]).coerceAtLeast(0f)
        val rate = when (d) {
            Illness.FLUX -> FLUX_RATE * (min(1f, b.campWeeks / 26f) + 0.3f * crowd) * (if (t > 15f) 1f else 0.3f) * hygiene
            // (As many mosquitoes as breed in the standing water about the camp: marsh, floods, sodden
            // ground after rain; the parasite ripens in them only in warm weeks.)
            Illness.MARSH_FEVER -> FEVER_RATE * tuning.marshFever * small.biting[p] / (small.biting[p] + HexSmallLife.HARASS_HALF) * (if (t > 20f) 1f else 0f)
            Illness.COUGH -> if (r.nextFloat() < COUGH_BREAKS * crowd * crowd * (if (t < 10f) 2f else 1f) * days / 7f) COUGH_FIRST else 0f
        }
        b.sick[i] = min(1f, b.sick[i] + rate * open * days / 7f)
        // Bouts end: the share that dies is reckoned week by week; the rest recover, spared for a while.
        val ending = min(1f, days / 7f / d.weeks)
        deaths[i] = b.sick[i] * d.deadly * ending
        val recovered = b.sick[i] * ending
        b.sick[i] -= recovered
        b.immune[i] = min(1f, b.immune[i] + recovered * (1f - d.deadly)) * (1f - days / Date.DAYS_PER_YEAR.toFloat() / d.immuneYears.coerceAtLeast(0.1f))
        if (b.sick[i] < 1e-4f) b.sick[i] = 0f
    }
    return deaths
}

/**
 * Two bands meet: the sick pass on what is catching, the less so to a band that keeps away from the
 * sick ([Tradition.HYGIENE]) and to those spared by having had it.
 */
internal fun passIllness(a: Band, b: Band) {
    for (d in Illness.entries) {
        if (d.contagion <= 0f) continue
        val i = d.ordinal
        val sa = a.sick[i]; val sb = b.sick[i]
        if (sa > 0f) b.sick[i] = min(1f, b.sick[i] + d.contagion * sa * max(0f, 1f - b.sick[i] - b.immune[i]) *
            b.hygiene)
        if (sb > 0f) a.sick[i] = min(1f, a.sick[i] + d.contagion * sb * max(0f, 1f - a.sick[i] - a.immune[i]) *
            a.hygiene)
    }
}

/**
 * Sickness: weekly share of the well who fall to the flux in a fouled, warm camp; to marsh fever where
 * the mosquitoes swarm in the warm months (at half their worst); weekly chance a cough breaks out among crowded people (times the
 * crowding squared), and the share it first strikes; how much keeping away from the sick spares a band.
 */
const val FLUX_RATE = 0.003f
const val FEVER_RATE = 0.03f
const val COUGH_BREAKS = 0.0002f
const val COUGH_FIRST = 0.2f
const val HYGIENE_GUARD = 0.4f
