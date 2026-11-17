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
 * nearest first by id: each pledges people for as many of the days as it owes ([daysOfFavours] of [weeksOfFavours]), as
 * fully as it returns favours ([Band.repayWill]), at most [LABOUR_DAYS_A_WEEK] an adult. They walk there and back by the
 * ground at once ([tripAlong]); the days are the creditor's to build with next week ([Band.labourFrom], [BandTime.lend]). What
 * they built with is settled at the next call ([settleLabour]): their band pays those days ([Band.raidWalkOwed]), the creditor
 * feeds them for them from its larder (a work feast, the food going to their band), and the debt shrinks by their worth to it
 * ([helpedBy], on the log scale). Days pledged and not built with cost the walk alone: paid for as pledged, 60% of them went
 * unused, and labour cost people 1% on m7-1 (A/B 2026-11-17, every pair).
 */
@Part("people: labour for favours")
internal fun HexGroups.callInLabour(weather: Weather) {
    if (tuning.labourFavours <= 0f) return
    val today = weather.date.absoluteDay
    val bandsNow = groups.filter { it.alive && it.band != null }
    val byId = bandsNow.associateBy { it.id }
    val log = tuning.logFavours > 0f
    // (First what was pledged last week: paid, fed and counted as far as it was built with.)
    for (c in bandsNow.sortedBy { it.id }) { val cb = c.band ?: continue; if (cb.labourFrom.isNotEmpty()) settleLabour(c, cb, byId, weather, log) }
    val creditors = bandsNow.filter { c -> val b = c.band; b != null && b.buildShort > 0f && c.larder > 0f && b.favours.values.any { it > 0f } }
        .sortedBy { tieBreak(seedBase, it.id, 2, today) }
    if (creditors.isEmpty()) return
    for (c in creditors) {
        val cb = c.band ?: continue
        if (!c.alive || c.size <= 0) continue
        val here = hw.placeHex[c.place]
        val perPerson = c.dailyNeedKg / max(1, c.size)
        var short = cb.buildShort - cb.labourFrom.values.sum()
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
            // (Its people walk there and back, wearing the way, from their band's next step's days: the work is paid as it is done.)
            tripAlong(d, way, min(d.adults.toFloat(), work / days), WalkPaid.NEXT_STEP)
            cb.labourFrom.merge(d.id, work, Float::plus); cb.labourUsed = Float.NaN; short -= work
            stats.meeting(Met.BANDS_WORKED_FOR_FAVOURS)
            stats.tally(labourKey(LabourCount.GIVEN), Math.round(10f * work))
            note(d) { "sent people to build for band #%d, which it owed: %.1f person-days".format(c.id, work) }
        }
    }
}

/**
 * Creditor [c]'s band [cb] settles the labour pledged it last week ([Band.labourFrom]) as far as it built with it
 * ([Band.labourUsed]; none if it had no week since): each band that sent people pays those days from its next step's days,
 * is fed for them at the creditor's feast as far as its larder goes (the food to that band), and its debt shrinks by their
 * worth to the creditor ([helpedBy], on the log scale). The pledges are then done.
 */
private fun HexGroups.settleLabour(c: HexGroup, cb: Band, byId: Map<Int, HexGroup>, weather: Weather, log: Boolean) {
    val used = cb.labourUsed.takeIf { !it.isNaN() } ?: 0f
    val perPerson = c.dailyNeedKg / max(1, c.size)
    val y = cb.recentYield(weather.date, days)
    for ((id, pledged) in cb.labourFrom.entries.sortedBy { it.key }) {
        val d = byId[id] ?: continue
        val db = d.band ?: continue
        val work = pledged * used
        if (work <= 0f) continue
        db.raidWalkOwed += work
        val fed = min(work * perPerson, c.larder)
        moveFood(c, d, fed, LarderMove.FEAST)
        helpedBy(d, c, favoursOfGift(favoursOfDays(work, y, c.adults, c.size), log))
        stats.tally(labourKey(LabourCount.USED), Math.round(10f * work)); stats.tally(labourKey(LabourCount.FED), Math.round(10f * fed))
        note(c) { "band #%d, which owed it, built for it %.1f person-days, fed at its feast".format(d.id, work) }
    }
    cb.labourFrom.clear(); cb.labourUsed = Float.NaN
}
