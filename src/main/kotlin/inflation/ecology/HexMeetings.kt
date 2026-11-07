package inflation.ecology

import inflation.drawKey
import inflation.mix64
import inflation.Par
import inflation.Part
import inflation.Profile
import inflation.monthsFrom
import inflation.Stream
import inflation.stream
import inflation.world.Hex
import inflation.world.HexArray
import inflation.world.Place
import inflation.world.get
import inflation.world.set
import inflation.ecology.HexSmallLife.DeadPool
import inflation.Books
import inflation.Kg
import inflation.world.Weather
import kotlin.math.roundToInt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import java.util.Arrays
import kotlin.math.sqrt

// The meetings of a week, settled one by one in the order they happen.

/** Group [k] is in hex [hex] (its path's [index]) from day [from] to day [to]. */
private class Stay(val k: Int, val index: Int, val hex: Hex, val from: Float, val to: Float)
/** Groups [a] and [b] are in hex [hex] together from [day], for [span] days, at their paths' [ai] and [bi]. */
private class Meeting(val day: Float, val span: Float, val a: Int, val ai: Int, val b: Int, val bi: Int, val hex: Hex) { var tie = 0L }

/**
 * A draw of order for groups [id] and [other] on day [day] in the world of [seed]: who comes first when two things
 * happen at once. Fixed by the seed, the ids and the day, so a run repeats on any number of cores, but no group is
 * first week after week for being older (lower in the list).
 */
internal fun tieBreak(seed: Long, id: Int, other: Int, day: Long): Long = mix64(drawKey(seed, Stream.TIE, id.toLong(), day) + other)

/**
 * Each party's stays on its way ([Stay]), by the hex they are in: the groups', and the resident stand-ins' ([residents]) in
 * the hexes a group comes to. (Stand-ins meet each other apart, [meet]: a hex none of the groups comes to holds no meeting here.)
 */
@Part("meetings: who is in each hex")
private fun HexGroups.staysByHex(parties: List<HexGroup>, residents: ResidentParties): Collection<ArrayList<Stay>> {
    // (Filed by hex number straight into an array; the lists in the order their hex was first reached, as a map would give them.)
    val byHex = arrayOfNulls<ArrayList<Stay>>(hex.size)
    val made = ArrayList<ArrayList<Stay>>()
    val nGroups = groups.size
    for (k in 0 until nGroups) {
        val g = parties[k]
        if (!g.alive) continue
        val last = g.path.size - 1
        for (i in g.path.indices) {
            val to = if (i < last) g.days[i + 1] else days.toFloat()
            val h = g.path[i]
            (byHex[h.i] ?: ArrayList<Stay>().also { byHex[h.i] = it; made += it }) += Stay(k, i, h, g.days[i], to)
        }
    }
    // (A stand-in stays the whole week in its hex, its path that one hex: filed after the groups, in the parties' order.)
    for (l in made) {
        val h = l[0].hex
        residents.forEachIn(h.i) { x -> val k = nGroups + x; val g = parties[k]
            if (g.alive) l += Stay(k, 0, h, g.days[0], days.toFloat()) }
    }
    return made
}

/** [events] put in the order they happen: by day, then by their drawn tie-break, then by the parties (a total order, so the same on any cores). */
@Part("meetings: sorting")
private fun inTimeOrder(events: ArrayList<Meeting>) {
    val sorted = events.toTypedArray()
    Arrays.parallelSort(sorted) { x, y ->
        val c = java.lang.Float.compare(x.day, y.day)
        if (c != 0) c else {
            val t = java.lang.Long.compare(x.tie, y.tie)
            if (t != 0) t else {
                val ca = Integer.compare(x.a, y.a)
                if (ca != 0) ca else Integer.compare(x.b, y.b)
            }
        }
    }
    events.clear(); events.addAll(sorted)
}

/** The day [g] enters each hex of its path: effort so far over its pace, the first hex on day 0. */
internal fun GroupWorld.timetable(s: Species, path: HexArray): FloatArray {
    val out = FloatArray(path.size)
    if (path.size <= 1) return out
    val cross = hex.crossings[Catalogue.travel(s).ordinal]
    var effort = 0f
    for (i in 1 until path.size) {
        val a = path[i - 1]; val b = path[i]
        var d = 0
        while (d < 6 && hex.neighbour(a, d) != b) d++
        if (d == 6) d = 0
        effort += cross[hex.edge(a, d)].takeIf { it.isFinite() } ?: hex.hexKm.toFloat()
        out[i] = effort
    }
    // Its pace: as fast as it had to go to get there this week, at most a forced march.
    val pace = max(s.dailyTravelKm, effort / days)
    for (i in out.indices) out[i] = (out[i] / pace).coerceAtMost(days.toFloat())
    return out
}

/**
 * Finds where groups' timetables overlap in a hex and settles the meetings in the order they
 * happen, each by the two sides' standing orders. A group whose week is cut short (turned
 * aside, driven off, stopping to eat a kill) ends it where that happened, and its later
 * meetings do not take place. Hunters and bands of every kind meet each other.
 */
@Part("meetings: settle")
internal fun HexGroups.meet(weather: Weather) {
    meetMonth = weather.date.month
    // (The resident hunters meet as packs do, through bands standing in for them: [ResidentParties].)
    val residents = ResidentParties(this)
    val parties = groups + residents.parties
    val byHex = staysByHex(parties, residents)
    // Each pair once, hex by hex on all cores.
    val shared = byHex.filter { it.size >= 2 }
    val found = Array<List<Meeting>>(shared.size) { emptyList() }
    Par.forEach("meetings: pairs in each hex", shared.size) { at -> val stays = shared[at]
        val out = ArrayList<Meeting>()
        for (i in stays.indices) { for (j in i + 1 until stays.size) {
            val sa = stays[i]; val sb = stays[j]
            // (A kind's own residents keep their ground by their strife, [HexFauna.strife], not by meeting.)
            if ((sa.k >= groups.size || sb.k >= groups.size) && !residentsMeet(parties[sa.k], parties[sb.k])) continue
            // (Residents meeting residents are settled apart, below: no group is among them.)
            if (sa.k >= groups.size && sb.k >= groups.size) continue
            val from = max(sa.from, sb.from); val to = min(sa.to, sb.to)
            if (from > to) continue
            out += Meeting(from, to - from, sa.k, sa.index, sb.k, sb.index, sa.hex)
        } }
        found[at] = out
    }
    val events = ArrayList<Meeting>().also { all -> for (l in found) all += l }
    // (Meetings where the weaker is a stand-in change nothing for the stronger but what it eats: they are drawn after the
    // others, on all cores, each by its own draws, and booked in their order, for those still on their way there.)
    val withStandIns = ArrayList<Meeting>()
    run {
        val apart = BooleanArray(events.size)
        Par.forEach("meetings: stand-ins apart", events.size) { i ->
            val e = events[i]; val sa = e.a >= groups.size; val sb = e.b >= groups.size
            apart[i] = parties[e.a].species != parties[e.b].species && ((sa && !sb && strongerThan(parties[e.b], parties[e.a], e.hex)) || (sb && !sa && strongerThan(parties[e.a], parties[e.b], e.hex)))
        }
        val kept = ArrayList<Meeting>(events.size)
        for (i in events.indices) if (apart[i]) withStandIns += events[i] else kept += events[i]
        events.clear(); events.addAll(kept)
    }
    // Meetings at the same hour come in an order drawn afresh each week, not by age in the list.
    val today = weather.date.absoluteDay
    // (Each meeting's draw worked out once, then sorted on all cores: the order is the same as one by one.)
    Par.forEach("meetings: tie-breaks", events) { it.tie = tieBreak(seedBase, parties[it.a].id, parties[it.b].id, today) }
    Par.forEach("meetings: tie-breaks with stand-ins", withStandIns) { it.tie = tieBreak(seedBase, parties[it.a].id, parties[it.b].id, today) }
    inTimeOrder(events)
    val stop = IntArray(parties.size) { parties[it].path.size - 1 }
    for (e in events) {
        // Both must still be on their way at that point.
        if (e.ai > stop[e.a] || e.bi > stop[e.b]) continue
        val a = parties[e.a]; val b = parties[e.b]
        if (!a.alive || !b.alive) continue
        val h = e.hex
        // Sharing a hex is not meeting: they must cross each other's ground, be about at the same
        // hours, and neither slip away first. (How often they run into each other while both are there,
        // for the share of the time each is in it.)
        val p = hw.placeNear(a.place, h).takeIf { !it.none } ?: a.place
        // (A stand-in is as many resident bands as there are there: each may meet the other, each meeting settled alone.)
        val ra = residents.of(e.a); val rb = residents.of(e.b)
        val met = poisson(encounters(a, b, p, e.span) * (ra?.bands ?: 1f) * (rb?.bands ?: 1f), rnd)
        if (met == 0) continue
        // (Two groups meet at most once a tick of the step, each stand-in band once a tick: a step of several ticks holds the
        // meetings its ticks would have, [StepClock.ticks].)
        val times = if (ra == null && rb == null) min(met, ticks) else min(met, kotlin.math.ceil(min(ra?.bands ?: Float.MAX_VALUE, rb?.bands ?: Float.MAX_VALUE)).toInt() * ticks)
        for (t in 0 until times) {
            ra?.fresh(); rb?.fresh()
            if (!a.alive || !b.alive) break
            if (slipsAway(a, b, p) || slipsAway(b, a, p)) stats.meeting(Met.SLIPPED_AWAY)
            else if (a.species.human && b.species.human) run { val t0 = Profile.mark(); bandsMeet(a, b, weather); Profile.slow("meeting: bands", t0, weather.date.absoluteDay) { a.probe(weather.date.absoluteDay) + " | " + b.probe(-1L) } }
            else if (a.species == b.species) rivalsMeet(a, e.a, e.ai, b, e.b, e.bi, h, stop)
            else enemiesMeet(a, e.a, e.ai, b, e.b, e.bi, h, stop)
            ra?.settle(); rb?.settle()
        }
    }
    run {
        val drawn = arrayOfNulls<StandInsMet>(withStandIns.size)
        Par.forEach("meetings: stand-ins drawn", withStandIns.size) { i ->
            val e = withStandIns[i]; val a = parties[e.a]; val b = parties[e.b]
            if (e.ai > stop[e.a] || e.bi > stop[e.b] || !a.alive || !b.alive) return@forEach
            val (strong, weak) = if (residents.of(e.a) == null) a to b else b to a
            val si = residents.of(if (weak === a) e.a else e.b) ?: return@forEach
            val r = kotlin.random.Random(e.tie)
            val p = weak.place
            val met = poisson(encounters(a, b, p, e.span) * si.bands, r)
            if (met > 0) drawn[i] = standInsMeetDraw(strong, weak, p, min(met, kotlin.math.ceil(si.bands).toInt() * ticks), r).also { it.weak = si }
        }
        for ((i, m) in drawn.withIndex()) if (m != null) {
            val e = withStandIns[i]; val strong = if (residents.of(e.a) == null) parties[e.a] else parties[e.b]
            standInsMeetBook(strong, m.weak.g, m.weak, m)
        }
    }
    // Resident hunters of different kinds meet each other where they live, hex by hex on all cores (each hex with its own
    // draws, so the same on any number of cores); what came of it is booked after, in the hexes' order.
    run {
        val byStandIns = residents.standInsByHex()
        val outcomes = arrayOfNulls<List<StandInsMet>>(byStandIns.size)
        Par.forEach("meetings: residents by hex", byStandIns.size) { j ->
            val list = byStandIns[j]; if (list.size < 2) return@forEach
            val r = stream(seedBase, Stream.RESIDENT_MEETINGS, list[0].g.home.i.toLong(), today)
            val out = ArrayList<StandInsMet>()
            for (x in list.indices) for (y in x + 1 until list.size) {
                val sx = list[x]; val sy = list[y]
                if (!residentsMeet(sx.g, sy.g)) continue
                val (strong, weak) = if (strength(sx.g) >= strength(sy.g)) sx to sy else sy to sx
                val p = weak.g.place
                // (A settled band is in this hex only the share of the week its territory lies in it, as a pack's timetable would put it:
                // both must be there at once, so the two shares multiply, [weekInHex].)
                val met = poisson(encounters(sx.g, sy.g, p, days.toFloat() * weekInHex(sx.g.species) * weekInHex(sy.g.species)) * sx.bands * sy.bands, r)
                if (met == 0) continue
                out += standInsMeetDraw(strong.g, weak.g, p, min(met, kotlin.math.ceil(min(sx.bands, sy.bands)).toInt() * ticks), r).also { it.strong = strong; it.weak = weak }
            }
            outcomes[j] = out
        }
        for (o in outcomes) if (o != null) for (m in o) { val st = m.strong ?: continue; standInsMeetBook(st.g, m.weak.g, m.weak, m); st.settle() }
    }
    residents.done()
    // Each group ends its week where it stopped: on the ground in that hex nearest in height to where it meant to go.
    for ((k, g) in groups.withIndex()) {
        if (!g.alive || stop[k] == g.path.size - 1) continue
        val i = max(0, stop[k])
        val h = g.path[i]
        g.path = g.path.copyOf(i + 1); g.days = g.days.copyOf(i + 1)
        g.place = hw.placeNear(g.place, h).takeIf { !it.none } ?: g.place
    }
}

/** What came of [times] bands of a resident hunter kind meeting a stronger party ([standInsMeetDraw]). */
private class StandInsMet(val slipped: Int, val stayed: Int, val youngKilled: Int, val grownKilled: Int) {
    lateinit var weak: ResidentParties.StandIn
    /** The stronger, where it too is a stand-in (residents meeting residents); null for a group. */
    var strong: ResidentParties.StandIn? = null
}

/**
 * [times] bands of resident hunter [weak] (a stand-in, [ResidentParties]) meet [strong] at place [p], each as one band of it
 * would ([slipsAway], [enemiesMeet]): drawn together by [r], those that slip away, and of the rest those that lose one of
 * their young, else one of their grown. (A stand-in carries no kill to rob.)
 */
private fun HexGroups.standInsMeetDraw(strong: HexGroup, weak: HexGroup, p: Place, times: Int, r: kotlin.random.Random): StandInsMet {
    val h = hw.placeHex[p]
    val ss = strength(strong) * (if (strong.home == h) HOME_ADVANTAGE else 1f); val sw = strength(weak) * (if (weak.home == h) HOME_ADVANTAGE else 1f)
    val slip = if (strength(strong) > 2f * strength(weak)) slipChance(weak.species, strong.species, p) else 0f
    val stayed = binomial(times, 1f - slip, r)
    if (stayed == 0 || ss / max(1f, sw) <= 2f) return StandInsMet(times - stayed, stayed, 0, 0)
    val odds = shelteredFromKilling(weak.species, weak.place) * strong.will[AnimalWay.KILL]
    // (A resident kind's young are born in its season: [Species.birthMonth].)
    val youngKilled = if (weak.young > 0 && !strong.species.human)
        binomial(stayed, min(1f, INTRAGUILD_KILL_YOUNG * tuning.intraguildYoung * odds * youngHidden(weak.species, weak.species.birthMonth)), r) else 0
    val grownKilled = if (weak.adults > 0) binomial(stayed - youngKilled, min(1f, INTRAGUILD_KILL * odds), r) else 0
    return StandInsMet(times - stayed, stayed, youngKilled, grownKilled)
}

/** Books what came of [m] ([standInsMeetDraw]): the meetings, and the kills, eaten by [strong] and left as carrion, and lost to [si]. */
private fun HexGroups.standInsMeetBook(strong: HexGroup, weak: HexGroup, si: ResidentParties.StandIn, m: StandInsMet) {
    stats.meeting(Met.SLIPPED_AWAY, m.slipped)
    stats.meeting(Met.HUNTERS_OF_OTHER_KINDS, m.stayed)
    val ws = weak.species; val mass = ws.massKg
    // (No more die than there are in the hex.)
    val youngKilled = si.take(m.youngKilled, true); val grownKilled = si.take(m.grownKilled, false)
    if (youngKilled > 0) {
        val kg = youngKilled * HexFauna.YOUNG_WEIGHT * mass
        strong.meatMet += 0.5f * kg; small.deposit(DeadPool.CARRION, weak.place, Kg(0.5f * kg)); bookKill(strong, 0.5f * kg, kg, 0.5f * kg)
        stats.deaths.merge(Tally.of(ws, ByBeast.of(ByBeast.How.YOUNG_KILLED_BY, strong.species)), youngKilled, Int::plus)
    }
    if (grownKilled > 0) {
        val kg = grownKilled * mass
        strong.meatMet += 0.5f * kg; small.deposit(DeadPool.CARRION, weak.place, Kg(0.5f * kg)); bookKill(strong, 0.5f * kg, kg, 0.5f * kg)
        stats.deaths.merge(Tally.of(ws, ByBeast.of(ByBeast.How.KILLED_BY, strong.species)), grownKilled, Int::plus)
    }
}

/** Whether [a] is the stronger at meeting [b] in hex [h], each at home the stronger ([enemiesMeet]). */
private fun HexGroups.strongerThan(a: HexGroup, b: HexGroup, h: Hex) =
    strength(a) * (if (a.home == h) HOME_ADVANTAGE else 1f) >= strength(b) * (if (b.home == h) HOME_ADVANTAGE else 1f)

/**
 * Whether a resident hunters' stand-in ([ResidentParties]) and another party meet to any end: not its own kind (they keep
 * their ground by strife), and only where one is the much stronger ([enemiesMeet]: else they keep their distance, and a
 * stand-in carries no kill to rob).
 */
private fun HexGroups.residentsMeet(a: HexGroup, b: HexGroup): Boolean {
    // (But roamers of a kind that also lives settled meet its settled as rival packs: [rivalsMeet].)
    if (a.species == b.species) return a.species in Catalogue.roamingResidents && (a.id < 0) != (b.id < 0)
    val sa = strength(a); val sb = strength(b)
    return max(sa, sb) > 2f * max(1f, min(sa, sb))
}

/**
 * Two bands meet: they share news of good ground (each learns where the other did best this month),
 * and a band that is going hungry is given a share of the other's larder. [rite]: the gathering they meet at, if any ([gatherAtRite]).
 */
@Part("meetings: band meets band")
internal fun HexGroups.bandsMeet(a: HexGroup, b: HexGroup, weather: Weather, rite: RiteGathering? = null) {
    stats.meeting(Met.BANDS_MET)
    note(a) { "met band #%d (%d people; tie %.2f)".format(b.id, b.size, a.band?.tie(b.id) ?: 0f) }
    note(b) { "met band #%d (%d people; tie %.2f)".format(a.id, a.size, b.band?.tie(a.id) ?: 0f) }
    val fresh = meetingFresh(a, b, weather)
    // (Each tells the other how its camp has lately yielded: how a planning band judges a fresh camp, [tellYield].)
    tellYield(a, b, weather.date); tellYield(b, a, weather.date)
    if (meetingEndsInRaid(a, b, weather, rite, fresh)) return
    val (poor, rich) = if (a.wellOff() < b.wellOff()) a to b else b to a
    // Bands that hold to sharing give more, and sooner.
    val sharing = rich.band?.holds(Tradition.SHARING) == true
    // (More to a band it is close to.)
    val close = rich.band?.let { rb -> poor.band?.let { Regard(rb, poor.id, it, map = hex).close } } ?: 0f
    if (poor.fed < (if (sharing) 0.9f else 0.8f) && rich.larder > 0f) {
        // (As much as the giver chooses to give.)
        // (Of the nine tenths of its larder it could spare: more of the same gives ever less of what is left.)
        val atOne = min(0.9f, BAND_GIFT * (if (sharing) 2f else 1f) * (1f + close))
        val chosen = 0.9f * shareOfWill(rich.band?.giveWill ?: 1f, atOne / 0.9f) * rich.larder
        rich.band?.let { rb -> dialUse?.amount(DialSite.GIVE_MEETING, rb.giveWill, chosen, atOne * rich.larder) }
        // (And no less than the favours it owes the poor band, as fully as it returns them.)
        val week = poor.dailyNeedKg * days
        val gift = given(rich, poor, chosen, (1f - poor.fed) * week + week)
        if (gift > 0f) {
        moveFood(rich, poor, gift, LarderMove.GIFT)
        poor.band?.befriend(rich.id, TIE_GIFT); rich.band?.befriend(poor.id, 0.5f * TIE_GIFT)
        helped(rich, poor, gift)
        stats.meeting(Met.FOOD_GIVEN)
        note(rich) { "gave %.0f kg of food to hungry band #%d".format(gift, poor.id) }
        note(poor) { "was given %.0f kg of food by band #%d".format(gift, rich.id) }
        }
    }
    // Gifts: bands close to each other give each other ornaments, which travel on along the ties.
    for ((giver, taker) in listOf(a to b, b to a)) {
        val gb = giver.band ?: continue; val tb = taker.band ?: continue
        if (Regard(gb, taker.id, tb, map = hex).tie < FRIEND_TIE || gb.ornaments < 1f) continue
        val gift = min(1f, ORNAMENT_GIFT * gb.giveWill) * gb.ornaments
        dialUse?.amount(DialSite.GIVE_ORNAMENTS, gb.giveWill, gift, min(1f, ORNAMENT_GIFT) * gb.ornaments, cut = ORNAMENT_GIFT * gb.giveWill > 1f)
        // (Less at a meeting soon after the last: gifts mark meetings after time apart, [meetingFresh].)
        val given = gift * fresh
        if (given <= 0f) continue
        val shell = gb.takeOrnaments(given)
        tb.ornaments += given; tb.exotic += shell
        tb.befriend(giver.id, 0.5f * TIE_GIFT)
        stats.meeting(Met.ORNAMENTS_GIVEN)
    }
    // Useful gifts: a band gives of the good stone, salt and ochre it has beyond its own needs to
    // a band that lacks them. The more the gift meets the other's want, the more it binds them both, and
    // the giver's name goes about among the bands.
    for ((giver, taker) in listOf(a to b, b to a)) {
        val gb = giver.band ?: continue; val tb = taker.band ?: continue
        if (Regard(gb, taker.id, tb, map = hex).tie < FEUD_GIFT) continue
        var useful = 0f
        for (m in TRADED) {
            val need = YEAR_NEED[m]
            val spare = gb.goods[m] - KEEP_YEARS * need * giver.adults
            val lack = KEEP_YEARS * need * taker.adults - tb.goods[m]
            if (spare <= 0f || lack <= 0f) continue
            // (Above its 1 a band gives beyond what the other lacks, as far as it can spare: goods given away for the regard they win.)
            val gift = min(spare, lack * gb.giveWill)
            dialUse?.amount(DialSite.GIVE_SPARE, gb.giveWill, gift, min(spare, lack))
            gb.goods[m] -= gift; tb.goods[m] += gift
            useful += gift / (KEEP_YEARS * need * max(1, taker.adults))
            stats.meeting(Met.Of.GIVEN, m.name.lowercase())
        }
        // Salt to eat: a band short of sodium is given a year of it for its people by one that holds more than a year of it
        // for its own, whatever it keeps for curing (a person's year of sodium is some 20 g of salt, [SALT_BODY_YEAR]).
        if (tb.sodiumShort > 0f) {
            val spare = gb.goods[Material.SALT] - SALT_BODY_YEAR * giver.size
            val lack = SALT_BODY_YEAR * taker.size - tb.goods[Material.SALT]
            if (spare > 0f && lack > 0f) {
                val gift = min(spare, lack) * min(1f, gb.giveWill)
                gb.goods[Material.SALT] -= gift; tb.goods[Material.SALT] += gift
                useful += gift / (SALT_BODY_YEAR * max(1, taker.size))
                stats.meeting(Met.Of.GIVEN, "salt to eat")
            }
        }
        if (useful <= 0f) continue
        tb.befriend(giver.id, TIE_USEFUL * useful); gb.befriend(taker.id, 0.5f * TIE_USEFUL * useful)
        gb.renown += useful
        note(giver) { "gave band #%d stone, salt or ochre it lacked".format(taker.id) }
        note(taker) { "was given stone, salt or ochre by band #%d".format(giver.id) }
    }
    // Feasts: a band that holds to feasting and has more laid by than it means to keep sets food
    // before the other; the guests remember it.
    for ((host, guest) in listOf(a to b, b to a)) {
        val hb = host.band ?: continue
        // (Feasting is its choice: by its custom, unless its will leads it away from it.)
        val keen = hb.byCustom(hb.holds(Tradition.FEASTS), hb.feastWill)
        dialUse?.amount(DialSite.FEASTING, hb.feastWill, keen, hb.byCustom(hb.holds(Tradition.FEASTS), 1f),
            cut = keen == 0f && hb.feastWill > 0f)
        // (Not at every meeting: a feast is an occasion, [meetingFresh].)
        if (keen <= 0f || rnd.nextFloat() >= fresh) continue
        val spare = host.larder - hb.storeAim * host.dailyNeedKg
        dialUse?.amount(DialSite.STORE_HOST, hb.storeShare, max(0f, spare), max(0f, host.larder - hb.storeAimAt1 * host.dailyNeedKg))
        if (spare <= 0f) continue
        val meal = min(spare, FEAST_KG * guest.size * keen)
        moveFood(host, guest, meal, LarderMove.FEAST)
        // With grain to spare and vessels to brew in, the host pours beer: the feast is remembered better. It brews only grain
        // beyond its seed corn and beyond what it means to keep laid by: a band does not drink the seed it sows.
        val grain = host.stores[Store.SEED] - hb.seedCornIn(host)
        val beer = if (hb.has(Tool.BASKET) > 0.5f && hb.has(Tool.GRINDER) > 0.2f)
            max(0f, minOf(0.2f * grain, spare - meal, BEER_KG * guest.size)) else 0f
        if (beer > 0f) {
            host.stores[Store.SEED] -= beer; stats.meeting(Met.FEASTS_WITH_BEER)
            land.books?.flow(Books.Stuff.MATTER, "eaten", -(beer * host.storeKgPer[Store.SEED]).toDouble())
        }
        val warmth = 1f + BEER_TIES * min(1f, beer / (BEER_KG * guest.size).coerceAtLeast(1e-3f))
        guest.band?.befriend(host.id, TIE_FEAST * warmth); hb.befriend(guest.id, 0.5f * TIE_FEAST * warmth)
        stats.meeting(Met.FEASTS)
        note(host) { "feasted band #%d with %.0f kg".format(guest.id, meal) }; note(guest) { "was feasted by band #%d".format(host.id) }
    }
    // Word of where the other did better this month.
    val ab = a.band; val bb = b.band
    if (ab != null && bb != null) ab.shareSeasons(bb)
}

/**
 * The share of a week a settled band of kind [s] is in any one hex: its kind's territory (its usual group size over its usual
 * density, [HexFauna.usualDensity]) against the hex's area, at most all of it. Kinds whose territory is larger than a hex
 * (lions, bears, wolves at their catalogue density) are in a hex only part of the week, as their packs' timetables put them.
 */
internal fun GroupWorld.weekInHex(s: Species): Float =
    min(1f, hex.areaKm2.toFloat() * HexFauna.usualDensity(s) / s.groupSize)

/**
 * How many times two groups that are in the same place for [span] days run into each other, on
 * average: as gas molecules do (Hutchinson & Waser 2007), 2 r v T / A, with r how far the one that
 * finds the other senses it, v how fast they close on each other, T the time both spend about
 * (searching, at the same hours) and A the place's size.
 */
internal fun GroupWorld.encounters(a: HexGroup, b: HexGroup, p: Place, span: Float): Float =
    encounters(a.species, b.species, a.band != null && b.band != null, p, span)

/** [encounters] for a group of kind [a] and one of kind [b] ([bands] when both are bands), as a group foresees them. */
internal fun GroupWorld.encounters(a: Species, b: Species, bands: Boolean, p: Place, span: Float): Float {
    // By the keener's senses.
    val r = max(a.senseKm, b.senseKm)
    val v = sqrt(a.dailyTravelKm * a.dailyTravelKm + b.dailyTravelKm * b.dailyTravelKm)
    // Bands seek each other out at springs and lake shores, where people come together.
    val gather = if (bands) 1f + 2f * (land.drink.nearSpring[p] + land.drink.nearLake[p]).coerceAtMost(1f) else 1f
    return 2f * r * v * span * SEARCHING * gather / land.areaKm2[p] * Niches.overlap(a, b)
}

/**
 * Whether hunter [g] senses [other] first and keeps out of its way, as it does a much stronger one
 * of another kind; the keener its senses and the better its shelter, the likelier.
 */
internal fun HexGroups.slipsAway(g: HexGroup, other: HexGroup, p: Place): Boolean {
    if (g.species == other.species || strength(other) <= 2f * strength(g)) return false
    return rnd.nextFloat() < slipChance(g.species, other.species, p)
}

/** The chance a hunter of kind [s] slips away from a much stronger party of kind [o] at place [p] ([slipsAway]). */
internal fun HexGroups.slipChance(s: Species, o: Species, p: Place): Float {
    val first = s.senseKm / (s.senseKm + o.senseKm)
    return min(0.9f, AVOID_SKILL * first * (1f - o.stalking * 0.5f) + SHELTER_EFFECT * 0.5f * Niches.shelter(s, p, land))
}

/**
 * The share of a much stronger party's chances at the young of [s], born in month [birthMonth], that it still has: where
 * the mothers hide their young and move them from lair to lair ([Species.hidesYoung]), for their first months in the
 * lair ([inflation.Tuning.denMonths]), most of those chances come to nothing ([YOUNG_HIDDEN]).
 */
internal fun HexGroups.youngHidden(s: Species, birthMonth: Int): Float =
    if (s.hidesYoung && monthsFrom(birthMonth, meetMonth) < tuning.denMonths) 1f - YOUNG_HIDDEN else 1f

/** What keeps a much stronger party ([enemiesMeet]) from killing one of kind [weak] at place [p]: its shelter there. */
internal fun HexGroups.shelteredFromKilling(weak: Species, p: Place) = 1f - SHELTER_EFFECT * Niches.shelter(weak, p, land)


/**
 * Standing orders, hunters of different kinds. The weaker gives way; if it had made a kill this week
 * the stronger drives it off and takes most of the meat (hyenas rob leopards, lions rob everyone);
 * a much stronger party now and then kills one of the other, and leaves it where it fell, as rivals of one kind do
 * ([rivalsMeet]): killing a rival is not hunting it. (Lions leave the cheetahs they kill uneaten; wolves eat a coyote
 * now and then: Palomares & Caro 1999. What lies there is carrion, for whoever eats carrion, the killer too.)
 */
@Part("meetings: hunters of other kinds")
internal fun HexGroups.enemiesMeet(a: HexGroup, ak: Int, ai: Int, b: HexGroup, bk: Int, bi: Int, h: Hex, stop: IntArray) {
    stats.meeting(Met.HUNTERS_OF_OTHER_KINDS)
    val sa = strength(a) * (if (a.home == h) HOME_ADVANTAGE else 1f)
    val sb = strength(b) * (if (b.home == h) HOME_ADVANTAGE else 1f)
    val aStrong = sa >= sb
    val strong = if (aStrong) a else b; val weak = if (aStrong) b else a
    val wk = if (aStrong) bk else ak; val wi = if (aStrong) bi else ai
    val ratio = max(sa, sb) / max(1f, min(sa, sb))
    if (ratio < THEFT_RATIO) return // near equals keep their distance
    // A leopard hauls its kill up a tree where there are trees; others guard it where they can.
    val cached = if (Niches.Shelter.TREE in Niches.shelters(weak.species)) land.plants.canopy(weak.place) else 0f
    if (weak.meatMet > 0f && rnd.nextFloat() < THEFT * strong.will[AnimalWay.STEAL] * (1f - cached)) {
        val taken = 0.7f * weak.meatMet
        weak.meatMet -= taken; strong.meatMet += taken
        bookKill(strong, taken, 0f, 0f); bookKill(weak, -taken, 0f, 0f)
        stats.meeting(Met.KILL_STOLEN)
        stats.meeting(Met.Of.KILLS_STOLEN_BY, strong.species.name); stats.meeting(Met.Of.KILLS_STOLEN_FROM, weak.species.name)
        stats.meeting(Met.Of.KG_STOLEN_BY, strong.species.name, taken.roundToInt()); stats.meeting(Met.Of.KG_STOLEN_FROM, weak.species.name, taken.roundToInt())
    }
    // A camp fire, and dogs that bark at night, keep lions and hyenas from taking people.
    val fire = if (weak.species.human) (1f - CAMPFIRE) * (1f - DOG_GUARD * (weak.band?.dogs?.dogShare ?: 0f)) else 1f
    // A much stronger party now and then kills one of the other: its young most often, an adult seldom.
    val odds = fire * shelteredFromKilling(weak.species, weak.place) * strong.will[AnimalWay.KILL]
    // (People drive hunters off and now and then kill one, but do not seek out their young.)
    if (ratio > 2f && weak.young > 0 && !strong.species.human &&
        rnd.nextFloat() < INTRAGUILD_KILL_YOUNG * tuning.intraguildYoung * odds * youngHidden(weak.species, weak.birthMonth)) {
        weak.young--
        small.deposit(DeadPool.CARRION, weak.place, Kg(HexFauna.YOUNG_WEIGHT * weak.species.massKg))
        stats.deaths.merge(Tally.of(weak.species, ByBeast.of(ByBeast.How.YOUNG_KILLED_BY, strong.species)), 1, Int::plus)
    } else if (ratio > 2f && weak.adults > 0 && rnd.nextFloat() < INTRAGUILD_KILL * odds) {
        adultsDied(weak, 1, rnd)
        small.deposit(DeadPool.CARRION, weak.place, Kg(weak.species.massKg))
        stats.deaths.merge(Tally.of(weak.species, ByBeast.of(ByBeast.How.KILLED_BY, strong.species)), 1, Int::plus)
    }
    stop[wk] = min(stop[wk], max(0, wi - 1)) // the weaker turns back
    if (weak.band != null) stats.meeting(Met.Of.TURNED_BACK_BY, strong.species.name)
}

/** The strength of a usual band of resident [s], as [HexGroups.strength] reckons a pack's: its grown that go about together, their weight, their ferocity. */
internal fun residentStrength(s: Species) = (if (s.foragingParty > 0) minOf(s.foragingParty, s.groupSize) else s.groupSize) * s.massKg * s.ferocity

/**
 * Packs that hold ground mark the hexes they worked this week within their territory; marks fade
 * unless renewed, and a stale mark of another pack is marked over. A pack whose home has gone
 * unmarked until the mark is stale (it ranged elsewhere for weeks), or that another has marked over,
 * has lost its ground.
 */
@Part("hunters: marking ground")
internal fun HexGroups.mark() {
    // (A tick's fading a tick: over a step of several, compounded, so a mark goes stale in the same days whatever the step.)
    val keep = if (ticks == 1) MARK_KEEP else MARK_KEEP.pow(ticks)
    for (m in scent.marks.values) for (i in m.indices) m[i] *= keep
    for (g in groups) {
        val s = g.species
        if (!g.alive || s.human || g.home.none) continue
        val m = scent.marks.getOrPut(s.index) { FloatArray(hex.size) }
        val who = scent.markers.getOrPut(s.index) { IntArray(hex.size) { -1 } }
        for (h in g.path) {
            if (hex.distanceKm(h, g.home) > territoryKm(s)) continue
            if (who[h] == g.id || m[h] < MARK_FRESH) { m[h] = 1f; who[h] = g.id }
        }
        if (who[g.home] != g.id || m[g.home] < MARK_FRESH) g.home = Hex.NONE
    }
}

/**
 * Standing orders, rival packs. On its own ground a pack holds; an intruder, or the weaker of two
 * strangers, gives way and turns back. Packs of near equal strength fight: now and then one dies,
 * and the loser gives up the ground.
 */
@Part("meetings: rival packs")
internal fun GroupWorld.rivalsMeet(a: HexGroup, ak: Int, ai: Int, b: HexGroup, bk: Int, bi: Int, h: Hex, stop: IntArray) {
    stats.meeting(Met.RIVAL_PACKS)
    val sa = a.adults * (if (a.home == h) HOME_ADVANTAGE else 1f); val sb = b.adults * (if (b.home == h) HOME_ADVANTAGE else 1f)
    val ratio = max(sa, sb) / max(1f, min(sa, sb))
    // (How much stronger the other has to be before the weaker gives way is the weaker's choice.)
    if (ratio > FIGHT_RATIO * (if (sa < sb) a else b).will[AnimalWay.FIGHT]) {
        // The weaker gives way: back the way it came.
        if (sa < sb) stop[ak] = min(stop[ak], max(0, ai - 1)) else stop[bk] = min(stop[bk], max(0, bi - 1))
        stats.meeting(Met.PACK_GAVE_WAY)
        return
    }
    stats.meeting(Met.PACKS_FOUGHT)
    val loser = if (rnd.nextFloat() < sa / (sa + sb)) b else a
    if (rnd.nextFloat() < RIVAL_KILL && loser.adults > 0) {
        adultsDied(loser, 1, rnd)
        small.deposit(DeadPool.CARRION, loser.place, Kg(loser.species.massKg))
        stats.deaths.merge(Tally.of(loser.species, DeathCause.RIVALS), 1, Int::plus)
    }
    if (loser.home == h) {
        // (A settled band's stand-in keeps its ground: its ground is its kind's settled there, [ResidentParties].)
        if (loser.id >= 0) loser.home = Hex.NONE
        // The winners take over the ground and kill the young they find there (as lions and wolves do).
        // (Prides and packs that take over ground do this; solitary hunters seldom.)
        if (loser.young > 0 && loser.species.groupSize >= 3) {
            val killed = binomial(loser.young, INFANTICIDE, rnd)
            loser.young -= killed
            small.deposit(DeadPool.CARRION, loser.place, Kg(HexFauna.YOUNG_WEIGHT * killed * loser.species.massKg))
            if (killed > 0) stats.deaths.merge(Tally.of(loser.species, DeathCause.YOUNG_KILLED_BY_RIVALS), killed, Int::plus)
        }
    }
    // A roamer that beats a settled band on its ground takes it, and settles in there ([settle]): a pair or more, as it settles
    // only with a mate (a lion coalition, a wolf pair); a lone one only wins its way past.
    val winner = if (loser === a) b else a
    if (loser.id < 0 && loser.home == h && winner.id >= 0 && winner.home.none && winner.adults >= 2) { winner.home = h; stats.meeting(Met.Of.ROAMERS_TOOK_OVER, winner.species.name) }
    if (loser === a) stop[ak] = min(stop[ak], max(0, ai - 1)) else stop[bk] = min(stop[bk], max(0, bi - 1))
}

/** [g] takes hex [h] as its ground and marks it there and then. */
internal fun HexGroups.claimGround(g: HexGroup, h: Hex) {
    g.home = h
    if (h.none) return
    scent.marks.getOrPut(g.species.index) { FloatArray(hex.size) }[h] = 1f
    scent.markers.getOrPut(g.species.index) { IntArray(hex.size) { -1 } }[h] = g.id
}

/**
 * A pack without ground settles where no other pack of its kind has fresh marks. A roamer of a kind that also lives settled
 * ([Catalogue.roamingResidents]) settles in among them instead: where it took a settled band's ground at a meeting
 * ([rivalsMeet]), or, without ground, where it comes on room for it ([HexFauna.roomFor]) and no roamer's fresh marks, but
 * not back where it left ([HexGroup.natal]: young leave the ground they were born on), and only once it has a mate (a pair,
 * as wolves found their territories: lone ones pair up as lone hunters do, [mergeAndPair]).
 */
@Part("hunters: settling ground")
internal fun HexGroups.settle(weather: Weather) {
    for (g in groups) if (g.alive && g.band == null && g.species in Catalogue.roamingResidents && !g.home.none) settleIn(g)
    val claimed = HashSet<Long>()
    // (Packs come in a drawn order: the first to come on empty ground takes it.)
    val today = weather.date.absoluteDay
    for (g in groups.filter { it.alive && it.species.lifeway == Lifeway.PACK && it.home.none }.sortedBy { tieBreak(seedBase, it.id, 0, today) }) {
        val h = hw.placeHex[g.place]
        val key = g.species.index.toLong() * hex.size + h.i
        // (Whether it takes it is its choice: always at the hand-written way.)
        val w = g.will[AnimalWay.SETTLE]
        if (w < 1f && rnd.nextFloat() >= w) continue
        if (g.species in Catalogue.roamingResidents) {
            if (g.adults >= 2 && h != g.natal && fauna.roomFor(g.species, h) && scent.foreign(g.species, h, g.id) < MARK_FRESH) settleIn(g)
            continue
        }
        if (scent.foreign(g.species, h, g.id) < MARK_FRESH && claimed.add(key)) {
            claimGround(g, h)
            stats.meeting(Met.Of.PACK_SETTLED, g.species.name)
        }
    }
}

/** Roamer [g] settles in among its kind's settled at its place ([HexFauna.joinSettled]): as a group it is gone, as one merged. */
internal fun HexGroups.settleIn(g: HexGroup) {
    fauna.joinSettled(g)
    stats.meeting(Met.Of.ROAMERS_SETTLED, g.species.name)
    g.mergedSize = g.size; g.adults = 0; g.young = 0
}

/**
 * The grown young due to leave their kind's settled ground ([HexFauna.leaving]) go dispersing: out of their natal ground,
 * a pool at the hex they left, which settles in where there is room about it and loses some on the way, at rates a day
 * over the step's days ([HexFauna.disperse]). Rates over days, so the same at any step length (plan section 58): as
 * single roamer packs they settled, paired and died once a step, and their numbers swung with the step's length (inv692b:
 * wolf roamers 171 / 299 / 519 / 11 at 6 / 12 / 30 / 60 days). The packs a world starts with still roam and settle as packs.
 */
internal fun HexGroups.roamersLeave() {
    for ((k, s) in fauna.kinds.withKinds()) {
        val due = fauna.leaving[k] ?: continue
        if (s !in kinds) continue
        var went = 0f
        for (h in hex.all) {
            if (due[h] <= 0f) continue
            val n = min(due[h], fauna.grownIn(k, h)); due[h] = 0f
            if (n <= 0f) continue
            fauna.takeGrown(k, h, n); fauna.addDispersing(k, h, n); went += n
        }
        if (went >= 1f) stats.meeting(Met.Of.ROAMERS_LEFT, s.name, went.toInt())
        fauna.disperse(k)
    }
}

/** The least time two groups passing through the same hex count as spending there together, days */

/** Beer: grain brewed for each guest at a feast, kg; how much more a feast with beer binds. */
internal const val BEER_KG = 1f

internal const val BEER_TIES = 0.5f
