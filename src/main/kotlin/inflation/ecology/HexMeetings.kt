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
import inflation.world.hexArray
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

/**
 * Group [k] is in hex [hex] (its path's [index]) from day [from] to day [to], for the [share] of that time: 1 on its way, its
 * weight's share for a pack that holds its ground and is about its footprint all step ([footprint]).
 */
private class Stay(val k: Int, val index: Int, val hex: Hex, val from: Float, val to: Float, val share: Float = 1f,
                   /** Whether it is a pack holding its ground about its footprint: its meetings with other groups are its contacts ([contacts]). */
                   val holds: Boolean = false)
/**
 * Groups [a] and [b] are in hex [hex] together from [day], for [span] days, at their paths' [ai] and [bi]. For a contact
 * ([contacts]), the encounters it comes to are its [mean] (worked out over all the hexes they share) and what the one that
 * gives way gives up is [contested]; NaN and null for a meeting by timetable.
 */
private class Meeting(val day: Float, val span: Float, val a: Int, val ai: Int, val b: Int, val bi: Int, val hex: Hex,
                      val mean: Float = Float.NaN, val contested: HexArray? = null) { var tie = 0L }

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
        // (A pack that holds its ground is about all its footprint all step, in each hex for its weight's share of the time:
        // whoever comes into its territory may meet it, not only whoever comes to its home, [footprint].)
        val foot = g.footprint; val weights = g.footWeights
        if (foot != null && weights != null && g.path.size == 1) {
            var sum = 0f; for (w in weights) sum += w
            for (i in 0 until foot.size) { val h = foot[i]
                (byHex[h.i] ?: ArrayList<Stay>().also { byHex[h.i] = it; made += it }) += Stay(k, 0, h, 0f, days.toFloat(), weights[i] / sum, true) }
            continue
        }
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
    meetSettle(weather)
    // (Bands that hold their home hex also meet their neighbours, wherever their timetables are, and keep their appointments.)
    if (tuning.bandsStay) { neighboursMeet(weather); keepAppointments(weather) }
}

/**
 * Bands that hold their home hex ([staysHome]) keep their appointments ([Band.appointment]) without moving camp: in the step
 * that holds the start of an appointment's month, the bands that share its place each send people there, and every two of
 * them meet ([bandsMeet]), the first [GATHERING_MOST] to come; each one's going there and back wears the way
 * ([wearBetween]). A band that took up another's appointment (when bands meet) comes to its gatherings: so gatherings
 * grow where bands' dead lie and where they have met. (A band alone at its place meets no one there.)
 */
@Part("meetings: appointments kept")
internal fun HexGroups.keepAppointments(weather: Weather) {
    // (By place and month, in the order the gatherings were first come to: a long step may hold two months' starts.)
    val at = LinkedHashMap<Pair<Hex, Int>, ArrayList<HexGroup>>()
    for (g in groups) {
        if (!g.alive || !staysHome(g)) continue
        val ap = g.band?.appointment ?: continue
        if (!weather.date.holdsMonthStart(days, ap.month)) continue
        at.getOrPut(ap.hex to ap.month) { ArrayList() } += g
    }
    for ((key, there) in at) {
        if (there.size < 2) continue
        val site = key.first
        val came = there.take(GATHERING_MOST)
        for (g in came) wearBetween(hw.placeHex[g.place], site, g)
        stats.meeting(Met.APPOINTMENTS_GATHERED); stats.meeting(Met.BANDS_AT_APPOINTMENTS, came.size)
        for (i in came.indices) for (j in i + 1 until came.size) if (came[i].alive && came[j].alive) {
            if (tuning.packsStay) settleAt(came[i], came[j], site, weather, intent = Intent.GATHERING) else bandsMeet(came[i], came[j], weather) }
    }
}

/** The most bands that meet at one appointment's gathering ([keepAppointments]): an estimate, as the largest gatherings of foragers held some hundreds of people. */
internal const val GATHERING_MOST = 12

/**
 * Bands that hold their home hex ([staysHome]) are in touch with the bands about them without moving camp, the nearer the
 * oftener, as far as the going between them over the map allows (a day or two's walk: Eastern Hadza bands lie 0.2 to 80 km
 * apart, 35 on average, Hill et al. 2014; Agta camps visit each other almost daily, Migliano et al. 2020). How near is the
 * least effort of the way there ([effortsFrom]: slope, broken ground, rivers and their fords, cliffs, snow and worn paths,
 * as the groups' going weighs every border this step), in km of easy going: a pair a hex's easy going apart meets
 * [inflation.Tuning.neighbourMeetings] times a year (weekly: friends and kin), each hex's worth of going further
 * [NEIGHBOUR_FALL] as often, beyond [CONTACT_KM] not at all. Each meeting is a meeting of bands as any ([bandsMeet]: news of
 * the country about both their homes, gifts, words and customs, and a raid if their regard comes to it), and its visitors'
 * going there and back wears the way they went ([VISITORS], [PathWear.along]). The rate is a day's, so a step holds the
 * meetings of its days, whatever its length. Bands in the same hex meet as their timetables bring them together, as before.
 * (The going is the lower id's, there; the way back is taken to be as hard. The seed of the contracts between neighbours,
 * docs/design/band-movement-removal.md.)
 */
@Part("meetings: neighbours")
internal fun HexGroups.neighboursMeet(weather: Weather) {
    val byHex = HashMap<Hex, ArrayList<HexGroup>>()
    for (g in groups) if (g.alive && staysHome(g)) byHex.getOrPut(hw.placeHex[g.place]) { ArrayList() } += g
    val rate = tuning.neighbourMeetings * years
    if (rate <= 0f) return
    val r = stream(seedBase, Stream.NEIGHBOUR_MEETINGS, 0L, weather.date.absoluteDay)
    val easy = hex.hexKm.toFloat()
    // (A pair's meetings in the step are held as one, standing for as many as they were ([meetTimes], [meetChance]: what each
    // meeting brings, over that many), at the tick of the last of them, drawn: it is as fresh as the ticks since the pair last
    // met ([meetingFresh], [meetTick]), and the freshness of meetings adds up, so the step's tellings and feasts come to what
    // its meetings one by one would have. The pairs are met in the order of those ticks. So a step costs a meeting a pair,
    // not a meeting a meeting: a 60-day step's ten meetings between neighbours are one.)
    class Due(val tick: Int, val times: Int, val a: HexGroup, val b: HexGroup, val way: List<Hex>)
    val due = ArrayList<Due>()
    // (Each band's going to the hexes about it, and those of them where bands live in index order, on all cores: they read
    // only the step's crossings. The draws then go band by band in the groups' order, as they always did.)
    val stayers = groups.filter { it.alive && staysHome(it) }
    val reaches = arrayOfNulls<Map<Hex, Pair<Float, Hex>>>(stayers.size)
    val peopled = arrayOfNulls<List<Hex>>(stayers.size)
    Par.forEach("meetings: neighbours' going", stayers.size) { i ->
        val a = stayers[i]; val h0 = hw.placeHex[a.place]
        val reach = effortsFrom(h0, Catalogue.travel(a.species).ordinal, hw.placeBand[a.place], weather, CONTACT_KM)
        reaches[i] = reach
        // (With t.packsStay, the bands of its own hex too, a budded band and its parent: their every contact is visiting, [contend].)
        peopled[i] = reach.keys.filter { (it != h0 || tuning.packsStay) && it in byHex }.sortedBy { it.i }
    }
    for ((i, a) in stayers.withIndex()) {
        val h0 = hw.placeHex[a.place]
        val reach = reaches[i] ?: continue
        // (Each pair once, the band of the lower id drawing for both; the hexes in index order, so the draws repeat.)
        for (h in peopled[i] ?: continue) {
            val bs = byHex[h] ?: continue
            val km = reach.getValue(h).first
            val mean = rate * NEIGHBOUR_FALL.pow(max(0f, km - easy) / easy)
            for (b in bs) if (b.id > a.id) {
                val times = poisson(mean, r); if (times == 0) continue
                var last = 0; repeat(times) { last = max(last, r.nextInt(ticks)) }
                due += Due(last, times, a, b, wayTo(reach, h0, h))
            }
        }
    }
    due.sortBy { it.tick }
    val w = PATH_WEAR * min(1f, VISITORS * Catalogue.HUMAN.massKg / PATH_MASS_KG)
    for (m in due) {
        if (!m.a.alive || !m.b.alive) continue
        meetTick = m.tick; meetTimes = m.times
        stats.meeting(Met.NEIGHBOURS_MET, m.times)
        // (Each going there and back wears the way: the meetings' wear compounded, as one after another would.)
        wear.along(m.way, 1f - (1f - w).pow(m.times))
        if (tuning.packsStay) settleAt(m.a, m.b, hw.placeHex[m.b.place], weather) else bandsMeet(m.a, m.b, weather)
    }
    meetTick = 0; meetTimes = 1
}

/**
 * The least effort of the going from hex [from] to each hex within [most] (km of easy going) for a group of travel [travel]
 * on ground band [band] this step ([stepEffort]: the planner's crossings, made for every group at the step's start), and the
 * hex each is reached from: a search over the hexes' borders, by land.
 */
internal fun HexGroups.effortsFrom(from: Hex, travel: Int, band: Int, weather: Weather, most: Float): Map<Hex, Pair<Float, Hex>> {
    val effort = planner.stepEffort(travel, band, weather)
    val out = HashMap<Hex, Pair<Float, Hex>>(); out[from] = 0f to Hex.NONE
    // (The nearest first; of equals, the lower hex, so the search is the same on every run.)
    val q = java.util.PriorityQueue<Pair<Float, Hex>>(compareBy<Pair<Float, Hex>> { it.first }.thenBy { it.second.i }); q += 0f to from
    while (q.isNotEmpty()) {
        val (d, h) = q.poll()
        if (d > out.getValue(h).first) continue
        for (k in 0 until 6) {
            val nh = hex.neighbour(h, k); if (nh.none) continue
            val nd = d + effort[hex.edge(h, k)]
            if (!nd.isFinite() || nd > most) continue
            val was = out[nh]
            if (was == null || nd < was.first) { out[nh] = nd to h; q += nd to nh }
        }
    }
    return out
}

/** The hexes of the least-effort way from [from] to [to] in [reach] ([effortsFrom]), from first to last. */
internal fun wayTo(reach: Map<Hex, Pair<Float, Hex>>, from: Hex, to: Hex): List<Hex> {
    val way = ArrayList<Hex>(); var h = to
    while (true) { way += h; if (h == from) break; h = reach[h]?.second?.takeIf { !it.none } ?: break }
    way.reverse(); return way
}

/**
 * How much less often bands a hex's easy going further apart are in touch ([neighboursMeet]), and how far apart they are in
 * touch at all, in km of easy going (a day or two's walk). Estimates.
 */
internal const val NEIGHBOUR_FALL = 0.5f
internal const val CONTACT_KM = 50f
/** The ties [wearToward] shares a band's wear by: those within this many hexes. */
internal const val NEIGHBOUR_RINGS = 5
/** The people who go to a band about and back for a meeting ([neighboursMeet]), for the wear of their going. An estimate. */
internal const val VISITORS = 4f

/**
 * Two parties come on each other at hex [h] (place [p]), however they came to: by their timetables, by their grounds
 * overlapping ([contacts]), by visiting ([neighboursMeet]) or at a gathering ([keepAppointments]). The one way every
 * meeting is settled: by the two sides' strategies where either has one other than the animals' ([engage], over
 * [byStrategy], the hexes it is over); else by the rules of the kinds that meet: a hunter that senses a much stronger one
 * first keeps out of its way ([slipsAway]); two bands talk or raid ([bandsMeet]); rivals of one kind and hunters of
 * different kinds by their standing orders ([rivalsMeet], [enemiesMeet]). [ak], [ai], [bk], [bi]: each party's index among
 * the meeting's parties and the point of its way, for [stop] (where a party on its way turns back).
 */
internal fun HexGroups.settle(a: HexGroup, ak: Int, ai: Int, b: HexGroup, bk: Int, bi: Int, h: Hex, p: Place, byStrategy: HexArray?,
                              stop: IntArray, weather: Weather, rite: RiteGathering? = null, intent: Intent = Intent.CHANCE) {
    // (Two bands that are friends both ways greet whatever their strategies, but where one came to raid.)
    val friends = intent != Intent.RAID && a.band?.let { ba -> b.band?.let { bb -> ba.tie(b.id) >= FRIEND_TIE && bb.tie(a.id) >= FRIEND_TIE } } == true
    if (byStrategy != null && !friends) { engage(a, ak, ai, b, bk, bi, byStrategy, h, stop, rnd, intent, weather); return }
    // (A raid set out on the other's home, by the rules of a raid: the defenders behind their works, [fight].)
    if (intent == Intent.RAID) { val ba = a.band; val bb = b.band; if (ba != null && bb != null) fight(a, ba, b, bb, planned = true, weather); return }
    if (slipsAway(a, b, p) || slipsAway(b, a, p)) stats.meeting(Met.SLIPPED_AWAY)
    else if (a.species.human && b.species.human) run { val t0 = Profile.mark(); bandsMeet(a, b, weather, rite); Profile.slow("meeting: bands", t0, weather.date.absoluteDay) { a.probe(weather.date.absoluteDay) + " | " + b.probe(-1L) } }
    else if (a.species == b.species) rivalsMeet(a, ak, ai, b, bk, bi, h, stop)
    else enemiesMeet(a, ak, ai, b, bk, bi, h, stop)
}

/**
 * Two parties that hold their ground meet at hex [h] not on a way but by a visit or a gathering ([neighboursMeet],
 * [keepAppointments]): settled as any meeting is ([settle]), the hex the meeting's ground; neither has a way to turn
 * back on.
 */
internal fun HexGroups.settleAt(a: HexGroup, b: HexGroup, h: Hex, weather: Weather, rite: RiteGathering? = null, intent: Intent = Intent.VISIT) {
    contested = hexArray(1) { h }; contestDay = weather.date.absoluteDay
    val p = hw.placeNear(b.place, h).takeIf { !it.none } ?: b.place
    settle(a, 0, 0, b, 1, 0, h, p, engagedOver(a, b, null, h)?.takeIf { engages(a, b, it) }, IntArray(2), weather, rite, intent)
}

/** How two parties came to meet ([settle]): by chance (timetables, grounds overlapping), visiting, at a gathering, or a raid set out on the other's home. */
enum class Intent { CHANCE, VISIT, GATHERING, RAID }

/** The meetings of groups whose timetables bring them into the same hex ([meet]). */
private fun HexGroups.meetSettle(weather: Weather) {
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
            // (Two groups of which one holds its ground meet as a contact, once over all the hexes they share: [contacts].)
            if (sa.k < groups.size && sb.k < groups.size && (sa.holds || sb.holds)) continue
            val from = max(sa.from, sb.from); val to = min(sa.to, sb.to)
            if (from > to) continue
            out += Meeting(from, (to - from) * sa.share * sb.share, sa.k, sa.index, sb.k, sb.index, sa.hex)
        } }
        found[at] = out
    }
    val events = ArrayList<Meeting>().also { all -> for (l in found) all += l }
    // (The contacts: each pair of which one holds its ground once, at the hex where their grounds overlap most, on the day the
    // one on its way comes there (a holder is about it from the step's start), with the encounters of all the hexes shared.)
    for (c in contacts()) {
        val a = groups[c.a]; val b = groups[c.b]
        fun indexAt(g: HexGroup) = if (g.footprint != null && g.path.size == 1) 0 else (0 until g.path.size).first { g.path[it] == c.hex }
        val ai = indexAt(a); val bi = indexAt(b)
        val day = max(if (ai > 0 || a.footprint == null) a.days[ai] else 0f, if (bi > 0 || b.footprint == null) b.days[bi] else 0f)
        events += Meeting(day, days * c.overlap, c.a, ai, c.b, bi, c.hex, c.mean, c.contested)
    }
    // (Meetings where the weaker is a stand-in change nothing for the stronger but what it eats: they are drawn after the
    // others, on all cores, each by its own draws, and booked in their order, for those still on their way there.)
    val withStandIns = ArrayList<Meeting>()
    run {
        val apart = BooleanArray(events.size)
        Par.forEach("meetings: stand-ins apart", events.size) { i ->
            val e = events[i]; val sa = e.a >= groups.size; val sb = e.b >= groups.size
            apart[i] = parties[e.a].species != parties[e.b].species && ((sa && !sb && strongerThan(parties[e.b], parties[e.a], e.hex)) || (sb && !sa && strongerThan(parties[e.a], parties[e.b], e.hex))) &&
                // (Not where a side has a strategy of its own: then it is settled by the strategies like any other, [engage].)
                engagedOver(parties[e.a], parties[e.b], e.contested, e.hex)?.let { engages(parties[e.a], parties[e.b], it, e.hex) } != true
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
    // (A stand-in's path is its one hex: its stop stays 0.)
    val stop = IntArray(parties.size); for (k in groups.indices) stop[k] = groups[k].path.size - 1
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
        val met = if (!e.mean.isNaN()) poisson(e.mean, rnd) else poisson(encounters(a, b, p, e.span) * (ra?.bands ?: 1f) * (rb?.bands ?: 1f), rnd)
        if (met == 0) continue
        // (Two groups meet at most once a tick of the step, each stand-in band once a tick: a step of several ticks holds the
        // meetings its ticks would have, [StepClock.ticks].)
        val times = if (ra == null && rb == null) min(met, ticks) else min(met, kotlin.math.ceil(min(ra?.bands ?: Float.MAX_VALUE, rb?.bands ?: Float.MAX_VALUE)).toInt() * ticks)
        // (What the one that gives way gives up: the contact's shared hexes, else the hex of the meeting, [gaveWay].)
        contested = e.contested ?: hexArray(1) { h }; contestDay = today
        val byStrategy = engagedOver(a, b, e.contested, h)?.takeIf { engages(a, b, it, h) }
        for (t in 0 until times) {
            ra?.fresh(); rb?.fresh()
            if (!a.alive || !b.alive) break
            // (A contact of which a side has a strategy other than the animals' is settled by the strategies, [engage].)
            settle(a, e.a, e.ai, b, e.b, e.bi, h, p, byStrategy, stop, weather)
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
        // (Each hex's list of stand-ins made by its own job, on all cores.)
        val byStandIns = residents.hexesWithStandIns()
        val outcomes = arrayOfNulls<List<StandInsMet>>(byStandIns.size)
        Par.forEach("meetings: residents by hex", byStandIns.size) { j ->
            val list = residents.standInsIn(byStandIns[j]); if (list.size < 2) return@forEach
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
    stats.meeting(Met.BANDS_MET, meetTimes)
    note(a) { "met band #%d (%d people; tie %.2f)".format(b.id, b.size, a.band?.tie(b.id) ?: 0f) }
    note(b) { "met band #%d (%d people; tie %.2f)".format(a.id, a.size, b.band?.tie(a.id) ?: 0f) }
    val fresh = meetingFresh(a, b, weather)
    // (Each sees what the other is, [noteSeen].)
    noteSeen(a, b, weather.date.absoluteDay); noteSeen(b, a, weather.date.absoluteDay)
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
            // (Grinding stone only to a band that makes grinding stones.)
            if (m == Material.GROUND_STONE && !tb.knows[Tool.GRINDER]) continue
            val need = YEAR_NEED[m]
            val spare = gb.goods[m] - KEEP_YEARS * need * giver.adults
            val lack = KEEP_YEARS * need * taker.adults - tb.goods[m]
            if (spare <= 0f || lack <= 0f) continue
            // (Above its 1 a band gives beyond what the other lacks, as far as it can spare: goods given away for the regard they win.)
            val gift = min(spare, lack * gb.giveWill)
            dialUse?.amount(DialSite.GIVE_SPARE, gb.giveWill, gift, min(spare, lack))
            gb.goods[m] -= gift; tb.goods[m] += gift
            if (m == Material.SALT) { gb.noteSalt(SaltFlow.GIVEN_OUT, gift); tb.noteSalt(SaltFlow.GIVEN_IN, gift) }
            useful += gift / (KEEP_YEARS * need * max(1, taker.adults))
            stats.meeting(Met.Of.GIVEN, m.name.lowercase())
        }
        // And finished tools it made beyond its own kit, to a band whose kit falls short.
        useful += giveTools(giver, gb, taker, tb)
        // And seed corn and breeding stock, to a band that knows farming or herding and has none to start with.
        useful += giveSeed(giver, gb, taker, tb) + giveStock(gb, tb)
        // Salt to eat: a band short of sodium is given a year of it for its people by one that holds more than a year of it
        // for its own, whatever it keeps for curing (a person's year of sodium is some 20 g of salt, [SALT_BODY_YEAR]).
        if (tb.sodiumShort > 0f) {
            val spare = gb.goods[Material.SALT] - SALT_BODY_YEAR * giver.size
            val lack = SALT_BODY_YEAR * taker.size - tb.goods[Material.SALT]
            if (spare > 0f && lack > 0f) {
                val gift = min(spare, lack) * min(1f, gb.giveWill)
                gb.goods[Material.SALT] -= gift; tb.goods[Material.SALT] += gift
                gb.noteSalt(SaltFlow.GIVEN_OUT, gift); tb.noteSalt(SaltFlow.GIVEN_IN, gift)
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
    enemiesClash(a, ak, ai, b, bk, bi, h, stop, sa, sb)
}

/**
 * What comes of hunters of different kinds, [a] and [b], meeting at [h] with strengths [sa] and [sb] as they stand there
 * ([enemiesMeet]; [engage] with their ground and surprise in them): near equals keep their distance; else the stronger may
 * take the weaker's kill and kill one of it, and the weaker turns back and gives way ([gaveWay]).
 */
internal fun HexGroups.enemiesClash(a: HexGroup, ak: Int, ai: Int, b: HexGroup, bk: Int, bi: Int, h: Hex, stop: IntArray, sa: Float, sb: Float) {
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
    gaveWay(weak, contested, contestDay)
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
    scent.marks2?.let { all -> for (m in all.values) for (i in m.indices) m[i] *= keep }
    for (g in groups) {
        val s = g.species
        if (!g.alive || s.human || g.home.none) continue
        val m = scent.marks.getOrPut(s.index) { FloatArray(hex.size) }
        val who = scent.markers.getOrPut(s.index) { IntArray(hex.size) { -1 } }
        // (A pack that holds its ground marks its footprint, each hex as fresh as its weight, its home the freshest: its
        // outskirts, marked faintly, others of its kind may share, [footprint].)
        val foot = g.footprint; val weights = g.footWeights
        if (foot != null && weights != null) for (i in 0 until foot.size) scent.claim(s.index, foot[i], g.id, weights[i], hex.size)
        else for (h in g.path) {
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
        gaveWay(if (sa < sb) a else b, contested, contestDay)
        stats.meeting(Met.PACK_GAVE_WAY)
        return
    }
    rivalsFight(a, ak, ai, b, bk, bi, h, stop, sa, sb)
}

/**
 * Rival packs [a] and [b] of one kind fight at [h], with strengths [sa] and [sb] as they stand there ([rivalsMeet]; [engage]
 * with their ground and surprise in them): one loses, by the strengths; now and then it loses one of its grown; beaten at
 * its home, it loses its ground and its young may be killed; it turns back and gives way ([gaveWay]).
 */
internal fun GroupWorld.rivalsFight(a: HexGroup, ak: Int, ai: Int, b: HexGroup, bk: Int, bi: Int, h: Hex, stop: IntArray, sa: Float, sb: Float) {
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
    gaveWay(loser, contested, contestDay)
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
        // (Whether it takes ground is its choice: always at the hand-written way. Over a step of several ticks it comes on ground
        // as often as a weekly pack would in so many weeks: its will each tick, and the hexes of its way, the last first, up to a
        // hex a tick. At one chance a step a 60-day pack was without ground 17-27% of its steps against 3-4% weekly, hunted no
        // footprint and starved: trace747, Olli 2026-11-12 "study starving steps".)
        val w = g.will[AnimalWay.SETTLE]
        if (w < 1f && rnd.nextFloat() >= (if (ticks > 1) 1f - (1f - w).pow(ticks) else w)) continue
        val here = hw.placeHex[g.place]
        val tries = if (ticks <= 1) listOf(here) else (listOf(here) + (g.path.size - 1 downTo 0).map { g.path[it] }).distinct().take(ticks)
        for (h in tries) {
            if (g.species in Catalogue.roamingResidents) {
                // (A roamer settles in where it stands: at its own place's hex only.)
                if (h == here && g.adults >= 2 && h != g.natal && fauna.roomFor(g.species, h) && scent.foreign(g.species, h, g.id) < MARK_FRESH) { settleIn(g); break }
                continue
            }
            val key = g.species.index.toLong() * hex.size + h.i
            if (scent.foreign(g.species, h, g.id) < MARK_FRESH && claimed.add(key)) {
                claimGround(g, h)
                stats.meeting(Met.Of.PACK_SETTLED, g.species.name)
                break
            }
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
    val dispersing = ArrayList<FaunaKind>()
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
        dispersing += k
    }
    // (The kinds disperse at once, each over its own field and pool, its dead into a table of its own; the dead are added to
    // the week's carcasses and deaths after, kind by kind in the order they always were, so the sums come out the same.)
    val carcass = Array(dispersing.size) { FloatArray(fauna.carcassKg.size) }
    val lost = FloatArray(dispersing.size)
    Par.forEach("residents: dispersing kinds", dispersing.size) { i -> lost[i] = fauna.disperse(dispersing[i], carcass[i]) }
    for ((i, k) in dispersing.withIndex()) {
        val c = carcass[i]; for (p in c.indices) if (c[p] > 0f) fauna.carcassKg[p] += c[p]
        if (lost[i] > 0f) fauna.deaths.merge(Tally.of(fauna.kinds[k], DeathCause.HOMELESS), lost[i].toDouble(), Double::plus)
    }
}

/** The least time two groups passing through the same hex count as spending there together, days */

/** Beer: grain brewed for each guest at a feast, kg; how much more a feast with beer binds. */
internal const val BEER_KG = 1f

internal const val BEER_TIES = 0.5f
