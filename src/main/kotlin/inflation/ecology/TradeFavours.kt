package inflation.ecology

import inflation.world.Weather
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

/*
 * Calling in favours in goods (docs/design/trade.md, toward money, step 2). Goods given make favours as food does
 * ([gaveGoods]); without this they were returned only in food, when the creditor went hungry ([given]). Now a band that
 * owes favours to a band lacking goods gives it, of what it can spare, no less than it owes, as fully as it returns favours
 * ([Band.repayWill]); a return it could have made and held back costs the creditor's regard, as with food. The debt is
 * reckoned in the creditor's days ([daysOfFavours]) and each good at the creditor's worth of it ([worthDaysPerKg]), so a
 * debt made by salt may be paid in stone. A band going to ask for goods counts what each band owes it ([askForGoods]).
 */

/**
 * The favours a gift worth [weeks] of the taker's need earns, on the log scale ([inflation.Tuning.logFavours]): half of
 * ln(1 + weeks²). A gift of a week earns 0.35, of ten weeks 2.3, of a tenth of a week 0.005: gifts under a week add
 * little, so a big gift outweighs many small ones, and above a week each week more earns less (Olli, 2026-11-16: "bigger
 * gifts are always more valuable than many smaller ones"). The ledger grew by some 1,600 favours a year on m7-1 without it,
 * a gift's favours being its weeks. With [log] false, the weeks.
 */
internal fun favoursOfGift(weeks: Float, log: Boolean): Float = if (!log) weeks else 0.5f * ln(1f + weeks * weeks)

/**
 * The weeks of the creditor's need that, given at once, settle [f] favours ([favoursOfGift]'s inverse): what a debt of [f]
 * asks of the debtor. (Capped where the scale runs out of numbers; no larder holds as much.)
 */
internal fun weeksOfFavours(f: Float, log: Boolean): Float = if (!log) f else sqrt(exp(2f * min(f, 40f)) - 1f)

/**
 * A band's ledger ([Band.favours]) put on the log scale once ([Band.favoursLog]): each balance as if one gift. Saves from
 * before it hold weeks; a band from a world begun with it has nothing to change.
 */
internal fun HexGroups.ledgerOnScale(b: Band) {
    if (tuning.logFavours <= 0f || b.favoursLog) return
    for (e in b.favours.entries) e.setValue(sign(e.value) * favoursOfGift(abs(e.value), true))
    b.favoursLog = true
}

/** Person-days of its work [f] favours are to a band of [adults] grown and [size] people at its yield of late: the inverse of [favoursOfDays]. */
internal fun daysOfFavours(f: Float, recentYield: Float, adults: Int, size: Int): Float =
    if (recentYield.isFinite() && recentYield > 0f) f * FAVOUR_DAYS * max(1, adults) / recentYield else f * FAVOUR_DAYS * max(1, size)

/**
 * What [giver]'s band [gb] owes [taker]'s band [tb] in favours, as person-days of the taker's ([daysOfFavours]): what it
 * returns in goods at one encounter, used up as it gives ([goodsRepaid]). 0 with `t.callInFavours=0` or nothing owed.
 */
internal fun HexGroups.goodsOwedDays(gb: Band, taker: HexGroup, tb: Band, weather: Weather): Float {
    if (tuning.callInFavours <= 0f) return 0f
    val f = gb.owesTo(taker.id)
    return if (f <= 0f) 0f else daysOfFavours(weeksOfFavours(f, tuning.logFavours > 0f), tb.recentYield(weather.date, days), taker.adults, taker.size)
}

/**
 * What a band that owes [owedDays] of the taker's days gives of a good it would of its own accord give [chosen] kg of, when
 * it can spare [spare] kg and the taker lacks [lack] kg, a kg worth [takerWorth] days to the taker, returning favours as
 * fully as [repayWill]: no less than it owes, as far as it can spare and the other lacks (above its 1, more than it owes,
 * as far as that goes). Without a debt, [chosen].
 */
internal fun goodsDue(chosen: Float, spare: Float, lack: Float, takerWorth: Float, owedDays: Float, repayWill: Float): Float {
    if (owedDays <= 0f || takerWorth <= 0f) return chosen
    val room = min(spare, lack)
    if (room <= 0f) return chosen
    return max(chosen, min(owedDays / takerWorth * max(0f, repayWill), room))
}

/**
 * [goodsDue], given: [giver] repays [taker] of good with [owed] days owed left, and the debt left shrinks by what it gave at
 * the taker's worth. A return it could have made ([due]) and held back costs it the taker's regard, as far as it fell short,
 * as with food ([given]).
 */
internal fun HexGroups.goodsRepaid(giver: HexGroup, gb: Band, taker: HexGroup, tb: Band, chosen: Float, spare: Float, lack: Float,
                                   takerWorth: Float, owed: FloatArray): Float {
    if (owed[0] <= 0f) return chosen
    val gift = goodsDue(chosen, spare, lack, takerWorth, owed[0], gb.repayWill)
    val due = min(owed[0] / takerWorth, min(spare, lack))
    if (due > 0f) {
        if (gift < due) { tb.befriend(giver.id, -TIE_GIFT * (due - gift) / due); stats.meeting(Met.GOODS_FAVOURS_HELD_BACK) }
        else stats.meeting(Met.GOODS_FAVOURS_RETURNED)
    }
    owed[0] = max(0f, owed[0] - gift * takerWorth)
    return gift
}
