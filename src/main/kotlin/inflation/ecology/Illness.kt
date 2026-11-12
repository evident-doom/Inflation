package inflation.ecology

import inflation.EnumFloats
import inflation.enumFloats
import inflation.world.Place
import inflation.world.get
import inflation.world.set

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
enum class Illness(override val label: String, val weeks: Float, val deadly: Float, val young: Float, val contagion: Float, val immuneYears: Float, val endemic: Float) : DeathReason {
    /**
     * Dysentery and its kin: fouled water and long-kept camps, worst in the warm months and in crowds. Of those who fall
     * ill, 1% of the grown and 3% of children die: shigellosis killed 0-2.6% of cases in population-based studies in low
     * and middle income countries, most of them children (Ram et al. 2008, Epidemiol. Infect. 136: 577-603); it was 3%,
     * above the record, and where bands meet often, when the flux is caught at meetings and comes back as the spared forget
     * it, that set its deaths at some 15 a 1000 a year (flux716, 11 Nov 2026) against the 2-7 of [FLUX_RATE]'s record.
     * Passed on at meetings at 0.1 (was 0.25): people fell ill some 140 times a 1000 a year where bands crowd, against
     * 0.6-107 for shigellosis in population-based studies (the same review); at 0.1, 83-86, and its deaths 2.6 a 1000,
     * inside both records (sick722, 12 Nov 2026).
     */
    FLUX("the flux", 3f, 0.01f, 3f, 0.1f, 2f, 0.4f),
    /** Malaria: carried by the mosquitoes about the camp in the warm months; not caught from one another. */
    MARSH_FEVER("marsh fever", 6f, 0.04f, 3f, 0f, 4f, 0.3f),
    /**
     * A virulent cough that runs from band to band, worst in winter and at gatherings, and can empty a camp. Of those who
     * fall ill, 5% of the grown die (was 8%: it killed 4-5 a 1000 grown a year at x4 against a rough 2.5 of the record,
     * respiratory illness 22% of forager deaths at adult deaths of 10-15 a 1000, Gurven and Kaplan 2007; fitted, not sourced).
     */
    COUGH("the cough", 4f, 0.05f, 2f, 0.6f, 20f, 0.6f);

    override val shown get() = PlaceRecord.Cause.ILLNESS
}

/**
 * A band's sicknesses this week: new bouts from where it is and how it lives (fouled camps, marsh in
 * the warm months, now and then a cough breaking out where people crowd), bouts ending in recovery
 * and a spell of immunity; returns, for each sickness, the weekly share of adults it kills (children
 * by [Illness.young] times that), over the step. Worked as rates a day in closed form over the step's days ([sickOver]):
 * bouts are weeks long, and a month leaves as many sick to pass on at a meeting as its days would.
 */
internal fun HexGroups.illnessWeek(g: HexGroup, b: Band, weather: Weather, r: kotlin.random.Random): EnumFloats<Illness> {
    val p = g.place
    val t = weather.temperature[p]
    val crowd = peopleCrowding(g)
    val deaths = enumFloats<Illness>()
    for (d in Illness.entries) {
        val i = d
        val h = hw.placeHex[p]
        val catching = catching(d, b, p, t, crowd, campWeeksAt(h), hygieneAt(b, h), weather)
        // (Rates a day over the step's days in closed form, the same at any step length (plan section 52.A.4): the well
        // fall ill at a week's [catching] spread over its days, bouts end at 1 / [Illness.weeks] a week, the dead [Illness.deadly]
        // of those, and the spared lose it over [Illness.immuneYears]. The cough breaks out at most once a step, at a moment
        // drawn within it, striking [COUGH_FIRST] of the well, and is otherwise caught only at meetings.)
        val fallIll = if (d == Illness.COUGH) 0f else catching / 7f
        val end = 1f / (7f * d.weeks)
        val forget = Date.years(days) / days / d.immuneYears.coerceAtLeast(0.1f)
        var ill = 0.0
        // (The cough's [catching] is its chance over the step, as a hazard: 1 - exp(-catching) of breaking out.)
        val breaks = d == Illness.COUGH && r.nextFloat() < 1f - kotlin.math.exp(-catching)
        if (breaks) {
            val at = r.nextFloat() * days
            ill += sickOver(b, i, fallIll, end, d.deadly, forget, at)
            b.sick[i] = min(1f, b.sick[i] + COUGH_FIRST * (1f - b.sick[i] - b.immune[i]).coerceAtLeast(0f))
            ill += sickOver(b, i, fallIll, end, d.deadly, forget, days - at)
        } else ill += sickOver(b, i, fallIll, end, d.deadly, forget, days.toFloat())
        deaths[i] += (d.deadly * end * ill).toFloat()
        if (b.sick[i] < 1e-4f) b.sick[i] = 0f
    }
    return deaths
}

/**
 * Band [b]'s sick and spared of sickness [i] carried [t] days on, the well falling ill at [fallIll] a day, bouts ending at
 * [end] a day, [deadly] of the ended dying, the spared forgetting at [forget] a day: the linear system
 * sick' = fallIll (1 - sick - spared) - end sick, spared' = end (1 - deadly) sick - forget spared, solved exactly
 * (its 2x2 exponential). Returns the sick carried over the [t] days (sick-days, as a share of the band).
 */
private fun sickOver(b: Band, i: Illness, fallIll: Float, end: Float, deadly: Float, forget: Float, t: Float): Double {
    if (t <= 0f) return 0.0
    val c = fallIll.toDouble(); val e = end.toDouble(); val l = forget.toDouble()
    // A = [[-(c + e), -c], [e (1 - deadly), -l]], input [c, 0].
    val a11 = -(c + e); val a12 = -c; val a21 = e * (1.0 - deadly); val a22 = -l
    val det = a11 * a22 - a12 * a21
    // (The settled state x* = -A^-1 [c, 0].)
    val s0 = if (det != 0.0) -(a22 * c) / det else 0.0; val m0 = if (det != 0.0) (a21 * c) / det else 0.0
    val ds = b.sick[i] - s0; val dm = b.immune[i] - m0
    val m = (a11 + a22) / 2.0; val disc = ((a11 - a22) / 2.0).let { it * it } + a12 * a21
    // exp(A t) = e^{m t} (f0 I + f1 (A - m I)), and its integral over [0, t] likewise with g0, g1.
    val q = kotlin.math.sqrt(kotlin.math.abs(disc))
    val f0: Double; val f1: Double
    if (q * t < 1e-6) { f0 = 1.0; f1 = t.toDouble() }
    else if (disc > 0) { f0 = kotlin.math.cosh(q * t); f1 = kotlin.math.sinh(q * t) / q }
    else { f0 = kotlin.math.cos(q * t); f1 = kotlin.math.sin(q * t) / q }
    val em = kotlin.math.exp(m * t)
    val e11 = em * (f0 + f1 * (a11 - m)); val e12 = em * f1 * a12; val e21 = em * f1 * a21; val e22 = em * (f0 + f1 * (a22 - m))
    val sick = s0 + e11 * ds + e12 * dm; val spared = m0 + e21 * ds + e22 * dm
    // (The integral of the deviation: A^-1 (exp(A t) - I) applied to it; with A singular (no catching, no forgetting), by its parts.)
    val carried = if (det != 0.0) {
        val x1 = (e11 - 1.0) * ds + e12 * dm; val x2 = e21 * ds + (e22 - 1.0) * dm
        s0 * t + (a22 * x1 - a12 * x2) / det
    } else if (e > 0.0) ds * (1.0 - kotlin.math.exp(-e * t)) / e else ds * t
    b.sick[i] = sick.toFloat().coerceIn(0f, 1f); b.immune[i] = spared.toFloat().coerceIn(0f, 1f - b.sick[i])
    return max(0.0, carried)
}

/**
 * How readily band [b] catches [d] at place [p] at [t] °C, as crowded with people as [crowd], the ground there holding
 * [campWeeks] weeks of camping's fouling ([HexGroups.campWeeksAt]) and kept clean as far as [hygiene] says
 * ([HexGroups.hygieneAt]): the weekly share of the well who fall ill; for the cough, the chance it breaks out this week (striking
 * [COUGH_FIRST] of them). The one rule for the week's sickness ([illnessWeek]) and for the sickness a band foresees at a
 * place ([HexGroups.deathsAt]).
 */
internal fun GroupWorld.catching(d: Illness, b: Band, p: Place, t: Float, crowd: Float, campWeeks: Int, hygiene: Float = b.hygiene, weather: Weather? = null): Float {
    // (Over a step of several ticks, given its [weather], each warm or cold gate counts the share of the ticks on its side, not the step's mean.)
    fun gate(c: Float, above: Float, below: Float, now: Float): Float {
        if (weather == null) return now
        val s = weather.shareAbove(p, c)
        return below + (above - below) * s
    }
    return when (d) {
        Illness.FLUX -> FLUX_RATE * (min(1f, campWeeks / (ticksPerYear / 2f)) + 0.3f * crowd) * gate(15f, 1f, 0.3f, if (t > 15f) 1f else 0.3f) * hygiene
        // (As many mosquitoes as breed in the standing water about the camp: marsh, floods, sodden
        // ground after rain; the parasite ripens in them only in warm weeks.)
        Illness.MARSH_FEVER -> FEVER_RATE * tuning.marshFever * small.biting[p] / (small.biting[p] + HexSmallLife.HARASS_HALF) * gate(20f, 1f, 0f, if (t > 20f) 1f else 0f)
        Illness.COUGH -> COUGH_BREAKS * crowd * crowd * gate(10f, 1f, 2f, if (t < 10f) 2f else 1f) * days / 7f
    }
}

/**
 * The share of band [b]'s adults ([young] false) or young that would die in time of the sicknesses they catch in a week
 * at place [p] (at [t] °C, as crowded as [crowd], camped there [campWeeks] weeks), healers saving [healed] of them: what
 * the place's sickness costs a band choosing it ([HexGroups.deathsAt]).
 */
internal fun GroupWorld.illnessAhead(b: Band, p: Place, t: Float, crowd: Float, campWeeks: Int, healed: Float, young: Boolean, hygiene: Float = b.hygiene): Float {
    var dying = 0f
    for (d in Illness.entries) {
        val open = (1f - b.sick[d] - b.immune[d]).coerceAtLeast(0f)
        val c = catching(d, b, p, t, crowd, campWeeks, hygiene) * (if (d == Illness.COUGH) COUGH_FIRST else 1f)
        dying += c * open * days / 7f * d.deadly * healed * (if (young) d.young else 1f)
    }
    return dying
}

/**
 * Two bands meet: the sick pass on what is catching, the less so to a band that keeps away from the
 * sick ([Tradition.HYGIENE]) and to those spared by having had it. [strangers], 0..1: how far they are strangers to each
 * other (never met before, and not kin: [strangersAt]), whose sicknesses the spared are the less spared from ([STRANGER_GERMS]).
 */
internal fun passIllness(a: Band, b: Band, strangers: Float = 0f) {
    for (d in Illness.entries) {
        if (d.contagion <= 0f) continue
        val i = d
        val sa = a.sick[i]; val sb = b.sick[i]
        if (sa > 0f) b.sick[i] = min(1f, b.sick[i] + caughtFrom(d, sa, b, strangers))
        if (sb > 0f) a.sick[i] = min(1f, a.sick[i] + caughtFrom(d, sb, a, strangers))
    }
}

/**
 * How far two bands meeting are strangers, 0..1, for the sicknesses they bring each other ([passIllness]): bands that have
 * met before share what goes round between them, and kin carry what their common forebears did; bands meeting for the
 * first time carry, as far as they are not kin, kinds of their own. Read before the meeting is recorded ([meetingFresh]).
 * (The rough pattern only, not strains: where people stay put it rarely tells; where they range far and meet many new bands,
 * the sicknesses they bring find people less spared, the outbreaks that came with the first long journeys.)
 */
internal fun strangersAt(a: Band, aId: Int, b: Band, bId: Int): Float =
    if (a.lastMet.containsKey(bId) || b.lastMet.containsKey(aId)) 0f else {
        // (Kin as alike lineages: 1 for bands of the same forebears in the same shares, however mixed.)
        val ab = kinship(a.lineage, b.lineage); val norm = kotlin.math.sqrt(kinship(a.lineage, a.lineage) * kinship(b.lineage, b.lineage))
        (1f - (if (norm > 0f) ab / norm else 0f)).coerceIn(0f, 1f)
    }

/**
 * The share of band [b] that catches [d] from a band it meets with [theirSick] of its people sick of it. The one rule for
 * a meeting ([passIllness]) and for the sickness a band foresees among the bands gathered at a place ([HexGroups.deathsAt]).
 */
internal fun caughtFrom(d: Illness, theirSick: Float, b: Band, strangers: Float = 0f) =
    d.contagion * theirSick * max(0f, 1f - b.sick[d] - b.immune[d] * (1f - STRANGER_GERMS * strangers)) * b.hygiene

/** The share of a band's sparing (immunity) that does not hold against strangers' sicknesses ([strangersAt]). An estimate. */
internal const val STRANGER_GERMS = 0.5f

/**
 * Sickness: weekly share of the well who fall to the flux in a fouled, warm camp; to marsh fever where
 * the mosquitoes swarm in the warm months (at half their worst); weekly chance a cough breaks out among crowded people (times the
 * crowding squared), and the share it first strikes; how much keeping away from the sick spares a band.
 * The flux, with [CAMP_FOULING], set to the forager and forager-farmer record: gastrointestinal illness 5-18% of deaths
 * across 13 groups (Gurven and Kaplan 2007, Popul. Dev. Rev. 33: 321-365). At 0.003, with bands holding their home hex, the
 * two came to about a third of people's deaths (allon A/B, 9 Nov 2026); at 0.4 of that, an estimated 16% where most live
 * fixed and 6% where most move. (Where bands meet often the flux is endemic, caught at meetings ([passIllness]), and its
 * deaths are set by [Illness.deadly] and [Illness.immuneYears], not by this: 0.0005 left them as they were, flux716, 11 Nov 2026.)
 */
internal const val FLUX_RATE = 0.0012f
internal const val FEVER_RATE = 0.03f
internal const val COUGH_BREAKS = 0.0002f
internal const val COUGH_FIRST = 0.2f
internal const val HYGIENE_GUARD = 0.4f
