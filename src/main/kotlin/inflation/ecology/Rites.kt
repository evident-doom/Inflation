package inflation.ecology

import inflation.Part
import inflation.world.get
import inflation.world.set
import inflation.Tuning
import inflation.world.Date
import inflation.world.Place
import inflation.world.Weather
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.min
import kotlin.math.exp
import kotlin.math.roundToInt

// Bands' rites: born of an omen at a place in a month, kept through the year, gathered at with the bands that keep them too, inherited and taken up, cut short by need.

/** Most months of the year a band's rites take it somewhere ([Band.rite]). */
internal const val RITE_SITES = 3

/** Person-days each adult gives a rite held at its site: the gathering, the dances, the telling. An estimate; no figure found. */
internal const val RITE_DAYS = 2f

/**
 * Strain a young belief bears ([Band.strainBorne]): rites held back from, about the year's rites. Strained that far, a
 * band holds together at half its size ([HexGroups.maxSize]), and the part that leaves at a split goes without the belief
 * as likely as the strain is near it. A belief kept [BELIEF_AGE_YEARS] bears twice that (older beliefs are clung to). Strain eases to a third over
 * [STRAIN_EASE_YEARS] of weeks the band eats its fill. Estimates; no figure found.
 */
internal const val STRAIN_SPLIT = 3f

internal const val BELIEF_AGE_YEARS = 25f

internal const val STRAIN_EASE_YEARS = 1f

/** Years over which a place's sanctity fades to a third (1/e) while no rites are held and no dead laid there: about a generation. An estimate. */
internal const val SANCTITY_YEARS = 25f

/** How much a band's hold on its rites changes when it splits: a fifth of the founders' range, as temperament drifts. Not measured. */
internal val HexGroups.riteDrift get() = tuning.riteFounderMax / 5f

/** Chance at a split that one of the offshoot's rites shifts by a month (the telling changes). Not measured. */
internal const val RITE_SHIFT = 0.1f

/** The founders' hold on their rites in lineage [lineage] (the same for every band of it, as [traditionsAt]). */
fun riteStrengthAt(lineage: Int, tuning: Tuning) = tuning.riteFounderMax * Math.floorMod((lineage + 7) * 2_654_435_761L.toInt() xor 0x5bd1e995, 1000) / 1000f

/**
 * A band's rites through the year (rounds on, see [inflation.Tuning.rounds]). A rite is born of an omen ([omen]): a
 * salient event at a place in a month (many deaths in a week, plenty after long want, a strange sign) that is borne out. Nothing here
 * weighs what a rite brings, and a rite is not let go: a belief ends with the bands that keep it, or with the part of a
 * band that breaks from it ([HexGroups.maxSize]).
 *
 * A rite obliges: at the start of its month the band's adults owe [RITE_DAYS] each of their customs' time, and, unless
 * the band is camped at the site ([inflation.Tuning.roundArrivedKm]), the walk there and back. Nothing weighs the trip
 * against what it brings; the band does not move for it, its adults go and come back. Once the days are given the rite
 * is held and the place grows holier by the adults at it. A rite adjusts to the season: one whose month ran out before
 * its days were given is held in a lesser form, with what was given (the place grows holier as far), and the belief is
 * strained only as far as the days not given went to other work than the band's needs ([Band.riteSpare],
 * [Band.strain]; see [HexGroups.maxSize]): need excuses, choice does not. What need cut short moves, as often as
 * not, to a month beside it the band eats better in ([riteToPlenty]): harvest feasts, not famine fasts.
 */
@Part("people: riteWeek")
internal fun HexGroups.riteWeek(g: HexGroup, weather: Weather) {
    if (!tuning.rounds) return
    val b = g.band ?: return
    val now = weather.date.year * 12 + weather.date.month
    // (A rite whose days are all given is held; one whose month ran out before they were, was cut short.)
    if (b.riteDue >= 0 && b.riteOwed <= 0f) {
        land.sanctity[b.riteDue] += g.adults.toFloat()
        b.riteKept = now
        if (b.riteTrip) gatherAtRite(g, b.riteDue, b.riteHeld, weather)
        stats.meeting(Met.BANDS_HELD_A_RITE)
        stats.meeting(Met.Of.RITES_HELD, RiteWalk.of(riteKm(g, b.riteDue), tuning).label)
        b.riteDue = -1
    } else if (b.riteDue >= 0 && b.riteHeld != now) {
        // (For the report: how far off the site is now, and how much of the rite's days had been given.)
        val km = riteKm(g, b.riteDue); val walk = RiteWalk.of(km, tuning)
        val asked = riteDays(km) * g.adults
        stats.meeting(Met.Of.RITES_CUT_SHORT, walk.label)
        stats.meeting(Met.Of.RITES_CUT_SHORT_PERCENT_GIVEN, walk.label, if (asked > 0f) (100f * (1f - b.riteOwed / asked)).roundToInt().coerceIn(0, 100) else 0)
        // (Held in a lesser form with what was given; the strain is what other work took of what it did not give.)
        val month = Math.floorMod(b.riteHeld, 12L).toInt()
        val given = (b.riteAsked - b.riteOwed).coerceAtLeast(0f)
        val withheld = min(b.riteOwed, b.riteSpare)
        if (given > 0f && b.riteAsked > 0f) {
            land.sanctity[b.riteDue] += g.adults * given / b.riteAsked
            stats.meeting(Met.BANDS_HELD_A_RITE_IN_A_LESSER_FORM)
            // (It meets the others gathered there only if its people walked there: the walk's days given in full. Before 13 Nov
            // 2026 any share given brought it to the gathering, however far.)
            if (b.riteTrip && given >= b.riteAsked - RITE_DAYS * g.adults) gatherAtRite(g, b.riteDue, b.riteHeld, weather)
        }
        b.strain += if (b.riteAsked > 0f) withheld / b.riteAsked else 1f
        val byNeed = if (b.riteAsked > 0f) (b.riteOwed - withheld) / b.riteAsked else 0f
        b.riteOwed = 0f; b.riteDue = -1; b.riteAsked = 0f; b.riteSpare = 0f
        stats.meeting(Met.BANDS_CUT_A_RITE_SHORT)
        if (rnd.nextFloat() < byNeed) riteToPlenty(b, month)
    }
    // (A belief grows older while it is kept; its strain eases while the band eats its fill.)
    if (b.rite.any { it >= 0 }) b.beliefYears += years
    if (g.fed >= Thresholds.HUNGRY) b.strain *= exp(-years / STRAIN_EASE_YEARS)
    val site = b.rite[weather.date.month]
    if (site >= 0 && b.riteHeld != now) {
        b.riteHeld = now; b.riteDue = site
        // (A far site, beyond a day or two's walk, is walked to at most once a year and not in a lean month; else the rite
        // is held at the camp, without the gathering. Olli, 13 Nov 2026: trips of 100-300 km are on record, yearly or rarer,
        // as at Australian ceremonial gatherings, from memory.)
        val km = riteKm(g, site)
        val lean = g.fed < Thresholds.HUNGRY || g.fat / g.species.maxFatDays.coerceAtLeast(1f) < FERTILE_RESERVE
        b.riteTrip = km <= CONTACT_KM || (b.farRiteYear != weather.date.year && !lean)
        if (km > CONTACT_KM && b.riteTrip) b.farRiteYear = weather.date.year
        if (km > CONTACT_KM && !b.riteTrip) stats.meeting(Met.FAR_RITES_HELD_AT_CAMP)
        b.riteOwed = (if (b.riteTrip) riteDays(km) else RITE_DAYS) * g.adults
        b.riteAsked = b.riteOwed; b.riteSpare = 0f
        stats.meeting(Met.Of.CUSTOMS_DAYS_ASKED, CustomsPart.RITES.label, (10f * b.riteOwed).roundToInt())
    }
    // Plenty after long want is an omen too: where and when a spell on its reserves ended, against its usual spell.
    if (b.wantBroke > 0) {
        omen(b, g.place, weather.date, b.wantBroke / max(LEAN_USUAL_WEEKS, b.wantUsual), Met.BANDS_MARKED_PLENTY_AFTER_WANT)
        b.wantUsual += 0.5f * (b.wantBroke - b.wantUsual); b.wantBroke = 0
    }
    // So is the strange thing with no cause in the world: a white bird, a shape in the trees, a lightning strike, a
    // misborn child, a fall. It befalls the band where it is, and the stranger the rarer ([STRANGE_SIGNS_A_YEAR]).
    if (rnd.nextFloat() < STRANGE_SIGNS_A_YEAR * years)
        omen(b, g.place, weather.date, 1f / (1f - rnd.nextFloat()), Met.BANDS_MARKED_A_STRANGE_SIGN)
    // An omen fades unless it is borne out: the same month going well again where it fell, a year or more on.
    if (b.omenPlace >= 0) {
        b.omenWeight *= exp(-years / OMEN_YEARS)
        if (b.omenWeight < 1f) b.omenPlace = -1
        else if (weather.date.month == b.omenMonth && weather.date.year > b.omenYear && g.fed >= Thresholds.HUNGRY &&
            hex.distanceKm(hw.placeHex[g.place], hw.placeHex[b.omenPlace]) <= tuning.roundArrivedKm) bearRite(b)
    }
}

/**
 * [g] held its rite at [site] in [month] (counted from the world's start): there it meets each band that held its own rite
 * at the same site that month, as bands that come across each other do ([bandsMeet]: news, gifts, words and customs, and
 * a raid if how they regard each other comes to it). Nothing keeps the peace but what the meeting makes of them; a shared
 * date ([turnOfYear]) makes such gatherings the likelier.
 */
internal fun HexGroups.gatherAtRite(g: HexGroup, site: Int, month: Long, weather: Weather) {
    riteGatherings.keys.removeIf { (it shr 32) < month - 1 }
    val here = riteGatherings.getOrPut((month shl 32) or site.toLong()) { ArrayList() }
    // (A band with sickness among its people that keeps away from the sick keeps its own away too: it holds the rite where
    // it is, as far as its will to shun the sick goes, and misses the gathering.)
    val b = g.band
    if (b != null && b.sick.sum() > SICK_SHOWS &&
        drawn(DialSite.SHUN_SICK, b.will[BandWay.SHUNNING_THE_SICK], rnd.nextFloat(), b.shunSick) { 0.5f }) {
        stats.meeting(Met.SICK_BANDS_KEPT_FROM_A_RITE); return
    }
    // (Its larder stays at its camp: at a gathering away from it, it has with it only food for the trip, and gives of that
    // only. Olli, 13 Nov 2026: bands gave food 10 hexes from home. A guess: food for each day of the rite and the walk.)
    if (b != null) { val km = riteKm(g, site); b.riteFood = if (km <= tuning.roundArrivedKm) Float.POSITIVE_INFINITY else riteDays(km) * g.dailyNeedKg }
    val rite = RiteGathering(site, here)
    for (o in here) if (g.alive && o.alive && o !== g) {
        stats.meeting(Met.BANDS_GATHERED_AT_A_RITE)
        bandsMeet(g, o, weather, rite)
    }
    here += g
}

/** A rite's gathering under way ([gatherAtRite]): its [site], and the bands [there] before the one now coming. */
internal class RiteGathering(val site: Int, val there: List<HexGroup>)

/**
 * [raider] (group [raiderId]) fell on band [victim] at a rite both keep, before the others gathered there: each that keeps the rite holds it
 * against the raider's people as far as it holds to its rites ([Band.riteStrength], as for the peace itself), and thinks
 * the less of the raider. The cost of breaking the peace is what the others remember.
 */
internal fun HexGroups.sacrilege(raider: Band, raiderId: Int, victim: Int, rite: RiteGathering) {
    stats.meeting(Met.RAIDS_ON_HOLY_GROUND)
    for (w in rite.there) {
        val wb = w.band ?: continue
        if (!w.alive || w.id == raiderId || w.id == victim || rite.site !in wb.rite) continue
        val held = wb.riteStrength / (1f + wb.riteStrength)
        wb.grudge[raider.mainLineage()] += held * GRUDGE
        wb.befriend(raiderId, -held * TIE_RAID)
    }
}

/**
 * The months the year turns in, which most peoples mark and gather at (the sun's year, [inflation.world.Date.solarDeclination],
 * from [inflation.world.Date.START_DAY]: the spring equinox opens month 3, midsummer month 6, the autumn equinox month 9,
 * midwinter month 0). Rites are drawn to them as they are born ([bearRite]) and moved ([riteToPlenty]).
 */
fun turnOfYear(month: Int) = month % 3 == 0

/** The two months beside [month]. */
private fun besideMonths(month: Int) = listOf(Math.floorMod(month - 1, 12), Math.floorMod(month + 1, 12))

/**
 * [b]'s rite in [month], cut short by need, moves to a month beside it with no rite of its own that the band eats
 * better in ([Band.monthSupply]), or as well if it is a turn of the year ([turnOfYear]); the turn of the year first,
 * else the best fed.
 */
private fun HexGroups.riteToPlenty(b: Band, month: Int) {
    val site = b.rite[month]
    if (site < 0) return
    val can = besideMonths(month).filter { b.rite[it] < 0 &&
        (b.monthSupply[it] > b.monthSupply[month] || turnOfYear(it) && b.monthSupply[it] >= b.monthSupply[month]) }
    val to = can.firstOrNull { turnOfYear(it) } ?: can.maxByOrNull { b.monthSupply[it] } ?: return
    b.rite[to] = site; b.rite[month] = -1
    stats.meeting(Met.BANDS_MOVED_A_RITE_TO_A_BETTER_MONTH)
}

/** How far [g] is from rite site [site], km. */
private fun HexGroups.riteKm(g: HexGroup, site: Int) = hex.distanceKm(hw.placeHex[g.place], hw.placeHex[site])

/** The days a rite [km] off asks of each adult: [RITE_DAYS], and the walk there and back unless the band is camped at it. */
private fun HexGroups.riteDays(km: Double) =
    RITE_DAYS + if (km <= tuning.roundArrivedKm) 0f else (2.0 * km / (WALK_KM_PER_HOUR * Activity.WORK_HOURS)).toFloat()

/** How far a band is from its rite's site, for the report ([Met.Of.RITES_HELD], [Met.Of.RITES_CUT_SHORT]). */
enum class RiteWalk(val label: String) {
    AT_SITE("at the site"), UNDER_100_KM("under 100 km"), UNDER_200_KM("100-200 km"), FARTHER("200 km or more");

    companion object {
        fun of(km: Double, tuning: Tuning) = when {
            km <= tuning.roundArrivedKm -> AT_SITE
            km < 100.0 -> UNDER_100_KM
            km < 200.0 -> UNDER_200_KM
            else -> FARTHER
        }
    }
}

/**
 * [b] marks what befell it at [place] on [date], [weight] times what it is used to ([SALIENT] at least, or it is not marked):
 * an omen it remembers over the next [OMEN_YEARS] (the greater kept before the lesser), which becomes a rite in its month
 * if borne out (see [riteWeek], [riteMeet]), or at once if it was very great ([OMEN_AT_ONCE]).
 */
internal fun GroupWorld.omen(b: Band, place: Place, date: Date, weight: Float, kind: Met) {
    if (weight < SALIENT || weight <= b.omenWeight) return
    b.omenPlace = place.i; b.omenMonth = date.month; b.omenYear = date.year; b.omenWeight = weight
    stats.meeting(kind)
    if (weight >= OMEN_AT_ONCE) bearRite(b)
}

/** [b]'s omen becomes a rite at its place in its month, if the band has that month free and room for another. */
internal fun GroupWorld.bearRite(b: Band) {
    val p = b.omenPlace; val m = b.omenMonth
    b.omenPlace = -1; b.omenWeight = 0f
    if (p < 0 || b.rite[m] >= 0 || p in b.rite || b.rite.count { it >= 0 } >= RITE_SITES) return
    // (Held at the turn of the year beside it, if that is free: the dates every band knows.)
    val at = if (turnOfYear(m)) m else besideMonths(m).firstOrNull { turnOfYear(it) && b.rite[it] < 0 } ?: m
    b.rite[at] = p
    stats.meeting(Met.BANDS_BORE_A_RITE)
}

/**
 * Material sources (trade on): the square each is gathered from, km; the least share of a place's
 * ground for it to count. Not measured.
 */
internal const val SOURCE_KM = 50.0

internal const val SOURCE_MIN = 0.1f

/** A band split off from another: its hold on the rites drifts, and now and then one shifts by a month (rounds on). */
internal fun HexGroups.riteInherit(nb: Band) {
    if (!tuning.rounds) return
    nb.riteStrength = (nb.riteStrength + riteDrift * (2f * rnd.nextFloat() - 1f) * 1.73f).coerceAtLeast(0f)
    if (rnd.nextFloat() < RITE_SHIFT) {
        val ms = (0 until 12).filter { nb.rite[it] >= 0 }
        if (ms.isNotEmpty()) {
            val m = ms[rnd.nextInt(ms.size)]
            val to = Math.floorMod(m + (if (rnd.nextBoolean()) 1 else -1), 12)
            if (nb.rite[to] < 0) {
                nb.rite[to] = nb.rite[m]
                nb.rite[m] = -1
            }
        }
    }
}

/**
 * Two bands talking (rounds on): each may take up a rite of the other's in a month it has none, as far as it believes what
 * the other tells ([Regard.trust]: understanding and closeness), never because the other does better; and each one's hold
 * on its rites moves toward the other's as far. Two that marked the same place each hear their omen told again.
 */
internal fun GroupWorld.riteMeet(a: Band, b: Band, ab: Regard, ba: Regard) {
    if (!tuning.rounds) return
    // (Two bands that marked the same place each hear their omen told again: it is borne out for both.)
    if (a.omenPlace >= 0 && a.omenPlace == b.omenPlace) { bearRite(a); bearRite(b) }
    fun takeUp(to: Band, from: Band, belief: Float) {
        for (m in 0 until 12) if (from.rite[m] >= 0 && to.rite[m] < 0 && to.rite.count { it >= 0 } < RITE_SITES && from.rite[m] !in to.rite &&
            rnd.nextFloat() < COPY_TRADITION * belief) {
            to.rite[m] = from.rite[m]; stats.meeting(Met.BANDS_TOOK_UP_ANOTHERS_RITE)
        }
    }
    val tA = ab.trust; val tB = ba.trust
    takeUp(a, b, tA); takeUp(b, a, tB)
    val sa = a.riteStrength; val sb = b.riteStrength
    a.riteStrength += COPY_TRADITION * tA * (sb - sa)
    b.riteStrength += COPY_TRADITION * tB * (sa - sb)
}

/**
 * Omens ([Band.omenPlace]): what a band marks is [SALIENT] times what it is used to or more (deaths in a week against its
 * usual, [Band.deathsUsual]; a spell of steps on its reserves ended, against its usual spell, [Band.wantUsual], and
 * never less than [LEAN_USUAL_WEEKS]); it fades to a
 * third over [OMEN_YEARS] and is forgotten once no more than the usual, unless borne out; one [OMEN_AT_ONCE] times the usual
 * is a rite at once. A band can't make a rite of what is usual for it.
 * Estimates; no figure found.
 */
internal const val SALIENT = 3f

internal const val OMEN_AT_ONCE = 10f

internal const val OMEN_YEARS = 1f

internal const val LEAN_USUAL_WEEKS = 2f

/**
 * Strange things a band sees in a year, good signs and bad alike; how strange each is, against the usual, falls off as
 * one over it (a third are [SALIENT], a tenth [OMEN_AT_ONCE]). Nothing in the world causes them and nothing follows from
 * them but the rite they may become. Estimate; no figure found.
 */
internal const val STRANGE_SIGNS_A_YEAR = 1f
