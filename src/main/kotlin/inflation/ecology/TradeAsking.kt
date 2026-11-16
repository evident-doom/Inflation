package inflation.ecology

import inflation.Part
import inflation.world.Weather
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/*
 * The side that lacks (docs/design/trade.md, step 2). A band short of good stone, salt, ochre or grinding stone does not only
 * wait for a band to come by with some to spare: it may send people to a band it knows holds more than it needs, as the
 * hungry go to kin for food ([visitKin]). It goes where the goods it can hope for are worth the most of its days beyond the
 * walk there and back, each kg at what fetching it from the nearest source it knows would cost it ([worthDaysPerKg]): so it
 * asks only where that is cheaper than fetching. What it is given makes favours as goods given at a meeting do ([gaveGoods]).
 */

/** How far a band goes to ask another for goods, km of easy going: as far as it looks for any goal, six days' walk ([GOAL_GOING_KM]). */
internal const val ASK_REACH_KM = GOAL_GOING_KM

/** What band [b] of [adults] grown holds of [m] ([TRADED]) beyond its own year's keep ([KEEP_YEARS], [YEAR_NEED]), kg: what it can give. */
internal fun spareGoods(b: Band, adults: Int, m: Material): Float = b.goods[m] - KEEP_YEARS * YEAR_NEED[m] * adults

/** What band [b] of [adults] grown lacks of [m] ([TRADED]) for its own year's keep, kg; grinding stone only to a band that makes grinding stones. */
internal fun lackGoods(b: Band, adults: Int, m: Material): Float =
    if (m == Material.GROUND_STONE && !b.knows[Tool.GRINDER]) 0f else KEEP_YEARS * YEAR_NEED[m] * adults - b.goods[m]

/**
 * What [giver] ([adultsGiving] grown) gives [taker] ([adultsTaking]) of [m], kg: of what it can spare, as far as the taker lacks
 * it, by how freely it gives ([Band.giveWill]: above its 1 beyond the lack, for the regard it wins), as at a meeting.
 */
internal fun goodsGiven(giver: Band, adultsGiving: Int, taker: Band, adultsTaking: Int, m: Material): Float {
    val spare = spareGoods(giver, adultsGiving, m); val lack = lackGoods(taker, adultsTaking, m)
    return if (spare <= 0f || lack <= 0f) 0f else min(spare, lack * giver.giveWill)
}

/** The people a band of [adults] grown sends to carry home [kg] of goods: a load each ([FETCH_LOAD]), one at least, all its grown at most. */
internal fun carriersFor(kg: Float, adults: Int): Float = min(adults.toFloat(), max(1f, ceil(kg / FETCH_LOAD)))

/**
 * Bands short of a traded good ([TRADED], [lackGoods]) go, in a drawn order, to the band they have met ([Band.lastMet]) within
 * [ASK_REACH_KM] by the ground that they can hope to be given the most of, in their own days ([worthDaysPerKg]), beyond the
 * walk of the people who carry it home ([carriersFor], [visitDays]); not to a band at feud with them ([FEUD_GIFT]). The
 * carriers walk there and back by the ground from the band's next step's days ([tripAlong]); the taker owes favours for what
 * it was given ([gaveGoods]) and holds the giver the dearer for it, and the giver's name goes about, as at a meeting. Off with
 * `t.askForGoods=0`.
 */
@Part("people: ask for goods")
internal fun HexGroups.askForGoods(weather: Weather) {
    if (tuning.askForGoods <= 0f) return
    val today = weather.date.absoluteDay
    val bandsNow = groups.filter { it.alive && it.band != null }
    val short = bandsNow.filter { g -> val b = g.band; b != null && g.adults > 0 && TRADED.any { lackGoods(b, g.adults, it) > 0f } }
        .sortedBy { tieBreak(seedBase, it.id, 1, today) }
    if (short.isEmpty()) return
    val byId = bandsNow.associateBy { it.id }
    val worth = FloatArray(TRADED.size)
    for (g in short) {
        val gb = g.band ?: continue
        if (!g.alive || g.adults <= 0) continue
        val h0 = hw.placeHex[g.place]
        // (Those it has met within reach as the crow flies that hold some of what it lacks: the ground is searched only then.)
        val known = gb.lastMet.keys.mapNotNull { byId[it] }.filter { o ->
            val ob = o.band
            ob != null && o.alive && o !== g && hexKm(h0, hw.placeHex[o.place]) <= ASK_REACH_KM &&
                TRADED.any { m -> goodsGiven(ob, o.adults, gb, g.adults, m) > 0f }
        }.sortedBy { it.id }
        if (known.isEmpty()) continue
        for ((j, m) in TRADED.withIndex()) worth[j] = if (lackGoods(gb, g.adults, m) > 0f) worthDaysPerKg(g, gb, m, weather) else 0f
        val reach = reachOf(g, h0, ASK_REACH_KM, weather)
        var best: HexGroup? = null; var bestNet = 0f; var bestCarriers = 0f
        for (o in known) {
            val ob = o.band ?: continue
            if (Regard(ob, g.id, gb, map = hex).tie < FEUD_GIFT) continue
            val km = reach.km(hw.placeHex[o.place], ASK_REACH_KM) ?: continue
            var kg = 0f; var days = 0f
            for ((j, m) in TRADED.withIndex()) { val hope = goodsGiven(ob, o.adults, gb, g.adults, m); kg += hope; days += hope * worth[j] }
            if (kg <= 0f) continue
            val carriers = carriersFor(kg, g.adults)
            val net = days - visitDays(carriers, km.toDouble())
            if (net > bestNet) { bestNet = net; best = o; bestCarriers = carriers }
        }
        val o = best ?: continue
        val ob = o.band ?: continue
        val way = findWay(g, h0, hw.placeHex[o.place], weather, ASK_REACH_KM) ?: continue
        val gifts = ArrayList<Given>(2)
        var useful = 0f
        for ((j, m) in TRADED.withIndex()) {
            val gift = goodsGiven(ob, o.adults, gb, g.adults, m)
            if (gift <= 0f) continue
            ob.goods[m] -= gift; gb.goods[m] += gift
            if (m == Material.SALT) { ob.noteSalt(SaltFlow.GIVEN_OUT, gift); gb.noteSalt(SaltFlow.GIVEN_IN, gift) }
            gifts += Given(m.name.lowercase(), gift, gift * worth[j], if (stats.census) gift * worthDaysPerKg(o, ob, m, weather) else 0f)
            useful += gift / (KEEP_YEARS * YEAR_NEED[m] * max(1, g.adults))
            stats.meeting(Met.Of.ASKED, m.name.lowercase())
        }
        val days = gifts.sumOf { it.takerDays.toDouble() }.toFloat()
        val favours = gaveGoods(o, g, days, weather)
        if (stats.census) for (x in gifts) bookGiven(x.of, x.amount, x.takerDays, x.giverDays, if (days > 0f) favours * x.takerDays / days else 0f)
        // (Its carriers walk there and back, wearing the way, from its next step's days.)
        tripAlong(g, way, bestCarriers, WalkPaid.NEXT_STEP)
        gb.befriend(o.id, TIE_USEFUL * useful); ob.befriend(g.id, 0.5f * TIE_USEFUL * useful)
        ob.renown += useful
        stats.meeting(Met.BANDS_ASKED_FOR_GOODS)
        note(g) { "sent %.0f to band #%d for stone, salt or ochre it lacked, %.0f km off".format(bestCarriers, o.id, way.km) }
        note(o) { "gave stone, salt or ochre to band #%d, which came asking".format(g.id) }
    }
}
