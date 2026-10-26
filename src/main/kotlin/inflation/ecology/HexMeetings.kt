package inflation.ecology

import inflation.Profile
import inflation.world.Hex
import inflation.world.HexArray
import inflation.world.Place
import inflation.world.get
import inflation.world.set
import inflation.ecology.HexSmallLife.DeadPool
import inflation.Books
import inflation.Kg
import inflation.world.Weather
import kotlin.math.max
import kotlin.math.min
import java.util.Arrays
import java.util.stream.Collectors
import kotlin.math.sqrt

// The meetings of a week, settled one by one in the order they happen.

/** Group [k] is in hex [hex] (its path's [index]) from day [from] to day [to]. */
private class Stay(val k: Int, val index: Int, val hex: Hex, val from: Float, val to: Float)
/** Groups [a] and [b] are in hex [hex] together from [day], for [span] days, at their paths' [ai] and [bi]. */
private class Meeting(val day: Float, val span: Float, val a: Int, val ai: Int, val b: Int, val bi: Int, val hex: Hex) { var tie = 0L }

/**
 * A draw of order for groups [id] and [other] on day [day]: who comes first when two things happen at
 * once. Fixed by the ids and the day, so a run repeats on any number of cores, but no group is first
 * week after week for being older (lower in the list).
 */
internal fun tieBreak(id: Int, other: Int, day: Long): Long {
    var z = day * -0x61c8864680b583ebL + id * 0x2545f4914f6cdd1dL + other * 0x632be59bd9b4e019L
    z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
    z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
    return z xor (z ushr 31)
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
internal fun HexGroups.meet(weather: Weather) {
    var mt = Profile.mark()
    val byHex = HashMap<Hex, ArrayList<Stay>>()
    for ((k, g) in groups.withIndex()) {
        if (!g.alive) continue
        val last = g.path.size - 1
        for (i in g.path.indices) {
            val to = if (i < last) g.days[i + 1] else days.toFloat()
            byHex.getOrPut(g.path[i]) { ArrayList() } += Stay(k, i, g.path[i], g.days[i], to)
        }
    }
    mt = Profile.lap("meetings: who is in each hex", mt)
    // Each pair once, hex by hex on all cores.
    val events = byHex.values.parallelStream().filter { it.size >= 2 }.flatMap { stays ->
        val out = ArrayList<Meeting>()
        for (i in stays.indices) { for (j in i + 1 until stays.size) {
            val sa = stays[i]; val sb = stays[j]
            val from = max(sa.from, sb.from); val to = min(sa.to, sb.to)
            if (from > to) continue
            out += Meeting(from, to - from, sa.k, sa.index, sb.k, sb.index, sa.hex)
        } }
        out.stream()
    }.collect(Collectors.toCollection { ArrayList<Meeting>() })
    mt = Profile.lap("meetings: pairs", mt)
    // Meetings at the same hour come in an order drawn afresh each week, not by age in the list.
    val today = weather.date.absoluteDay
    // (Each meeting's draw worked out once, then sorted on all cores: the order is the same as one by one.)
    events.parallelStream().forEach { it.tie = tieBreak(groups[it.a].id, groups[it.b].id, today) }
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
    mt = Profile.lap("meetings: sorting", mt)
    val stop = IntArray(groups.size) { groups[it].path.size - 1 }
    for (e in events) {
        // Both must still be on their way at that point.
        if (e.ai > stop[e.a] || e.bi > stop[e.b]) continue
        val a = groups[e.a]; val b = groups[e.b]
        if (!a.alive || !b.alive) continue
        val h = e.hex
        // Sharing a hex is not meeting: they must cross each other's ground, be about at the same
        // hours, and neither slip away first. (How often they run into each other while both are there,
        // for the share of the time each is in it.)
        val p = hw.placeNear(a.place, h).takeIf { !it.none } ?: a.place
        val met = poisson(encounters(a, b, p, e.span), rnd)
        if (met == 0) continue
        if (slipsAway(a, b, p) || slipsAway(b, a, p)) { stats.meeting(Met.SLIPPED_AWAY); continue }
        if (a.species.human && b.species.human) Profile.part("meetings: bands (culture, gifts, feasts, raids)") { bandsMeet(a, b, weather) }
        else if (a.species == b.species) Profile.part("meetings: rival packs") { rivalsMeet(a, e.a, e.ai, b, e.b, e.bi, h, stop) }
        else Profile.part("meetings: hunters of other kinds") { enemiesMeet(a, e.a, e.ai, b, e.b, e.bi, h, stop) }
    }
    // Each group ends its week where it stopped: on the ground in that hex nearest in height to where it meant to go.
    for ((k, g) in groups.withIndex()) {
        if (!g.alive || stop[k] == g.path.size - 1) continue
        val i = max(0, stop[k])
        val h = g.path[i]
        g.path = g.path.copyOf(i + 1); g.days = g.days.copyOf(i + 1)
        g.place = hw.placeNear(g.place, h).takeIf { !it.none } ?: g.place
    }
    Profile.lap("meetings: settling them", mt)
}

/**
 * Two bands meet: they share news of good ground (each learns where the other did best this month),
 * and a band that is going hungry is given a share of the other's larder.
 */
internal fun HexGroups.bandsMeet(a: HexGroup, b: HexGroup, weather: Weather) {
    stats.meeting(Met.BANDS_MET)
    note(a) { "met band #%d (%d people; tie %.2f)".format(b.id, b.size, a.band?.tie(b.id) ?: 0f) }
    note(b) { "met band #%d (%d people; tie %.2f)".format(a.id, a.size, b.band?.tie(a.id) ?: 0f) }
    if (meetingEndsInRaid(a, b, weather)) return
    val (poor, rich) = if (a.wellOff() < b.wellOff()) a to b else b to a
    // Bands that hold to sharing give more, and sooner.
    val sharing = rich.band?.holds(Tradition.SHARING) == true
    // (More to a band it is close to.)
    val close = (rich.band?.tie(poor.id) ?: 0f).coerceAtLeast(0f)
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
        moveFood(rich, poor, gift)
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
        if (gb.tie(taker.id) < 0.3f || gb.ornaments < 1f) continue
        val gift = min(1f, ORNAMENT_GIFT * gb.giveWill) * gb.ornaments
        dialUse?.amount(DialSite.GIVE_ORNAMENTS, gb.giveWill, gift, min(1f, ORNAMENT_GIFT) * gb.ornaments, cut = ORNAMENT_GIFT * gb.giveWill > 1f)
        if (gift <= 0f) continue
        val shell = gb.takeOrnaments(gift)
        tb.ornaments += gift; tb.exotic += shell
        tb.befriend(giver.id, 0.5f * TIE_GIFT)
        stats.meeting(Met.ORNAMENTS_GIVEN)
    }
    // Useful gifts: a band gives of the good stone, salt and ochre it has beyond its own needs to
    // a band that lacks them. The more the gift meets the other's want, the more it binds them both, and
    // the giver's name goes about among the bands.
    for ((giver, taker) in listOf(a to b, b to a)) {
        val gb = giver.band ?: continue; val tb = taker.band ?: continue
        if (gb.tie(taker.id) < FEUD_GIFT) continue
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
        if (keen <= 0f) continue
        val spare = host.larder - hb.storeAim * host.dailyNeedKg
        dialUse?.amount(DialSite.STORE_HOST, hb.storeShare, max(0f, spare), max(0f, host.larder - hb.storeAimAt1 * host.dailyNeedKg))
        if (spare <= 0f) continue
        val meal = min(spare, FEAST_KG * guest.size * keen)
        moveFood(host, guest, meal)
        // With grain to spare and vessels to brew in, the host pours beer: the feast is remembered better.
        val grain = host.stores[Store.SEED]
        val beer = if (hb.has(Tool.BASKET) > 0.5f && hb.has(Tool.GRINDER) > 0.2f) min(0.2f * grain, BEER_KG * guest.size) else 0f
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
 * How many times two groups that are in the same place for [span] days run into each other, on
 * average: as gas molecules do (Hutchinson & Waser 2007), 2 r v T / A, with r how far the one that
 * finds the other senses it, v how fast they close on each other, T the time both spend about
 * (searching, at the same hours) and A the place's size.
 */
internal fun GroupWorld.encounters(a: HexGroup, b: HexGroup, p: Place, span: Float): Float {
    // By the keener's senses.
    val r = max(a.species.senseKm, b.species.senseKm)
    val v = sqrt(a.species.dailyTravelKm * a.species.dailyTravelKm + b.species.dailyTravelKm * b.species.dailyTravelKm)
    // Bands seek each other out at springs and lake shores, where people come together.
    val gather = if (a.band != null && b.band != null) 1f + 2f * (land.drink.nearSpring[p] + land.drink.nearLake[p]).coerceAtMost(1f) else 1f
    return 2f * r * v * span * SEARCHING * gather / land.areaKm2[p] * Niches.overlap(a.species, b.species)
}

/**
 * Whether hunter [g] senses [other] first and keeps out of its way, as it does a much stronger one
 * of another kind; the keener its senses and the better its shelter, the likelier.
 */
internal fun HexGroups.slipsAway(g: HexGroup, other: HexGroup, p: Place): Boolean {
    val s = g.species; val o = other.species
    if (s == o || strength(other) <= 2f * strength(g)) return false
    val first = s.senseKm / (s.senseKm + o.senseKm)
    val chance = AVOID_SKILL * first * (1f - o.stalking * 0.5f) + SHELTER_EFFECT * 0.5f * Niches.shelter(s, p, land)
    return rnd.nextFloat() < min(0.9f, chance)
}


/**
 * Standing orders, hunters of different kinds. The weaker gives way; if it had made a kill this week
 * the stronger drives it off and takes most of the meat (hyenas rob leopards, lions rob everyone);
 * a much stronger party now and then kills one of the other.
 */
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
    }
    // A camp fire, and dogs that bark at night, keep lions and hyenas from taking people.
    val fire = if (weak.species.human) (1f - CAMPFIRE) * (1f - DOG_GUARD * (weak.band?.dogShare ?: 0f)) else 1f
    // A much stronger party now and then kills one of the other: its young most often, an adult seldom.
    val odds = fire * (1f - SHELTER_EFFECT * Niches.shelter(weak.species, weak.place, land)) * strong.will[AnimalWay.KILL]
    // (People drive hunters off and now and then kill one, but do not seek out their young.)
    if (ratio > 2f && weak.young > 0 && !strong.species.human && rnd.nextFloat() < INTRAGUILD_KILL_YOUNG * tuning.intraguildYoung * odds) {
        weak.young--
        strong.meatMet += HexFauna.YOUNG_WEIGHT * 0.5f * weak.species.massKg
        small.deposit(DeadPool.CARRION, weak.place, Kg(HexFauna.YOUNG_WEIGHT * 0.5f * weak.species.massKg)) // (what the killers leave)
        bookKill(strong, HexFauna.YOUNG_WEIGHT * 0.5f * weak.species.massKg, HexFauna.YOUNG_WEIGHT * weak.species.massKg, HexFauna.YOUNG_WEIGHT * 0.5f * weak.species.massKg)
        stats.deaths.merge(Tally.of(weak.species, ByBeast.of(ByBeast.How.YOUNG_KILLED_BY, strong.species)), 1, Int::plus)
    } else if (ratio > 2f && weak.adults > 0 && rnd.nextFloat() < INTRAGUILD_KILL * odds) {
        adultsDied(weak, 1, rnd)
        strong.meatMet += 0.5f * weak.species.massKg
        small.deposit(DeadPool.CARRION, weak.place, Kg(0.5f * weak.species.massKg))
        bookKill(strong, 0.5f * weak.species.massKg, weak.species.massKg, 0.5f * weak.species.massKg)
        stats.deaths.merge(Tally.of(weak.species, ByBeast.of(ByBeast.How.KILLED_BY, strong.species)), 1, Int::plus)
    }
    stop[wk] = min(stop[wk], max(0, wi - 1)) // the weaker turns back
    if (weak.band != null) stats.meeting(Met.Of.TURNED_BACK_BY, strong.species.name)
}

/**
 * Packs that hold ground mark the hexes they worked this week within their territory; marks fade
 * unless renewed, and a stale mark of another pack is marked over. A pack whose home has gone
 * unmarked until the mark is stale (it ranged elsewhere for weeks), or that another has marked over,
 * has lost its ground.
 */
internal fun HexGroups.mark() {
    for (m in scent.marks.values) for (i in m.indices) m[i] *= MARK_KEEP
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
        loser.home = Hex.NONE
        // The winners take over the ground and kill the young they find there (as lions and wolves do).
        // (Prides and packs that take over ground do this; solitary hunters seldom.)
        if (loser.young > 0 && loser.species.groupSize >= 3) {
            val killed = binomial(loser.young, INFANTICIDE, rnd)
            loser.young -= killed
            small.deposit(DeadPool.CARRION, loser.place, Kg(HexFauna.YOUNG_WEIGHT * killed * loser.species.massKg))
            if (killed > 0) stats.deaths.merge(Tally.of(loser.species, DeathCause.YOUNG_KILLED_BY_RIVALS), killed, Int::plus)
        }
    }
    if (loser === a) stop[ak] = min(stop[ak], max(0, ai - 1)) else stop[bk] = min(stop[bk], max(0, bi - 1))
}

/** [g] takes hex [h] as its ground and marks it there and then. */
internal fun HexGroups.claimGround(g: HexGroup, h: Hex) {
    g.home = h
    if (h.none) return
    scent.marks.getOrPut(g.species.index) { FloatArray(hex.size) }[h] = 1f
    scent.markers.getOrPut(g.species.index) { IntArray(hex.size) { -1 } }[h] = g.id
}

/** A pack without ground settles where no other pack of its kind has fresh marks. */
internal fun HexGroups.settle(weather: Weather) {
    val claimed = HashSet<Long>()
    // (Packs come in a drawn order: the first to come on empty ground takes it.)
    val today = weather.date.absoluteDay
    for (g in groups.filter { it.alive && it.species.lifeway == Lifeway.PACK && it.home.none }.sortedBy { tieBreak(it.id, 0, today) }) {
        val h = hw.placeHex[g.place]
        val key = g.species.index.toLong() * hex.size + h.i
        // (Whether it takes it is its choice: always at the hand-written way.)
        val w = g.will[AnimalWay.SETTLE]
        if (w < 1f && rnd.nextFloat() >= w) continue
        if (scent.foreign(g.species, h, g.id) < MARK_FRESH && claimed.add(key)) claimGround(g, h)
    }
}

/** The least time two groups passing through the same hex count as spending there together, days */
