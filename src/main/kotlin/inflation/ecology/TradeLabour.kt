package inflation.ecology

import inflation.Part
import inflation.world.Weather
import kotlin.math.max
import kotlin.math.min

/*
 * Labour as the counterflow (docs/design/trade.md, "What flows back", 3a). Goods go one way, down the line from the
 * sources, and the favours they make come back as work: a band whose building ran short of days last week calls on the
 * bands near it that owe it, and they send people to build its houses and hall; it feeds them while they work (a work
 * feast, the food going to their band), and their debt shrinks by the days' worth to it. What they build is its only while
 * it stays (possession, not property). Off with `t.labourFavours=0`.
 */

/** The person-days an adult of a band that owes may work for its creditor a week, at most: a day a week. An estimate. */
internal const val LABOUR_DAYS_A_WEEK = 1f

/** The census of labour for favours, person-days: given (sent to work), used (built with), fed (kg of food set before the workers). */
enum class LabourCount(val label: String) { GIVEN("days given/10"), USED("days used/10"), FED("kg fed/10") }

@OptIn(RawKey::class)
internal fun labourKey(c: LabourCount) = TallyKey.raw("labour for favours:${c.label}")

/**
 * Bands whose house and hall building ran short of days last week ([Band.buildShort]) and that have food to set before
 * workers, in a drawn order, call on the bands within [CONTACT_KM] of easy going that owe them favours ([Band.owesTo]),
 * nearest first by id: each sends people for as many of the days as it owes ([daysOfFavours] of [weeksOfFavours]), as fully
 * as it returns favours ([Band.repayWill]), at most [LABOUR_DAYS_A_WEEK] an adult. They walk there and back by the ground and
 * work from their band's next step's days ([tripAlong], [Band.raidWalkOwed]); the creditor feeds them from its larder, the food
 * going to their band ([moveFood]); the days are the creditor's to build with next week ([Band.labourIn], [BandTime.lend]);
 * the debt shrinks by their worth to it ([helpedBy], on the log scale).
 */
@Part("people: labour for favours")
internal fun HexGroups.callInLabour(weather: Weather) {
    if (tuning.labourFavours <= 0f) return
    val today = weather.date.absoluteDay
    val bandsNow = groups.filter { it.alive && it.band != null }
    val creditors = bandsNow.filter { c -> val b = c.band; b != null && b.buildShort > 0f && c.larder > 0f && b.favours.values.any { it > 0f } }
        .sortedBy { tieBreak(seedBase, it.id, 2, today) }
    if (creditors.isEmpty()) return
    val byId = bandsNow.associateBy { it.id }
    val log = tuning.logFavours > 0f
    for (c in creditors) {
        val cb = c.band ?: continue
        if (!c.alive || c.size <= 0) continue
        val here = hw.placeHex[c.place]
        val perPerson = c.dailyNeedKg / max(1, c.size)
        var short = cb.buildShort
        val y = cb.recentYield(weather.date, days)
        val debtors = cb.favours.entries.filter { it.value > 0f }.mapNotNull { byId[it.key] }
            .filter { it.alive && it.adults > 0 && it !== c && hexKm(here, hw.placeHex[it.place]) <= CONTACT_KM }.sortedBy { it.id }
        for (d in debtors) {
            if (short <= 0f || c.larder <= 0f) break
            val db = d.band ?: continue
            val owes = db.owesTo(c.id)
            if (owes <= 0f) continue
            val owedDays = daysOfFavours(weeksOfFavours(owes, log), y, c.adults, c.size)
            val work = minOf(short, owedDays * max(0f, db.repayWill), LABOUR_DAYS_A_WEEK * d.adults * ticks, c.larder / max(1e-3f, perPerson))
            if (work <= 0f) continue
            val way = findWay(d, hw.placeHex[d.place], here, weather, CONTACT_KM) ?: continue
            // (Its people walk there and back, wearing the way, and work, from their band's next step's days.)
            tripAlong(d, way, min(d.adults.toFloat(), work / days), WalkPaid.NEXT_STEP)
            db.raidWalkOwed += work
            // (The creditor sets food before them while they work: a work feast, eaten by their band.)
            val fed = work * perPerson
            moveFood(c, d, fed, LarderMove.FEAST)
            cb.labourIn += work; short -= work
            helpedBy(d, c, favoursOfGift(favoursOfDays(work, y, c.adults, c.size), log))
            stats.meeting(Met.BANDS_WORKED_FOR_FAVOURS)
            stats.tally(labourKey(LabourCount.GIVEN), Math.round(10f * work)); stats.tally(labourKey(LabourCount.FED), Math.round(10f * fed))
            note(d) { "sent people to build for band #%d, which it owed: %.1f person-days".format(c.id, work) }
            note(c) { "band #%d, which owed it, built for it %.1f person-days, fed at its feast".format(d.id, work) }
        }
    }
}
