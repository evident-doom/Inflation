package inflation.ecology

import inflation.world.Date
import inflation.world.HexLand
import inflation.world.HexMap
import inflation.world.HexWorld
import inflation.world.LifeForm
import inflation.world.Weather
import java.util.PriorityQueue
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

// Hunting along the way, and the meetings of a week settled one by one in the order they happen.

/** Group [k] is in hex at its path's [index] from day [from] to day [to]. */
private class Stay(val k: Int, val index: Int, val from: Float, val to: Float)
/** Groups [a] and [b] are in the same hex from [day], for [span] days, at their paths' [ai] and [bi]. */
private class Meeting(val day: Float, val span: Float, val a: Int, val ai: Int, val b: Int, val bi: Int)

/**
 * How catchable prey [ps] is for hunter [s] in place [p], 0..1: size against the pack, defence,
 * refuge in broken ground, small prey hiding in thick cover, and for stalkers, dark nights.
 */
internal fun HexGroups.catchable(s: Species, ps: Species, p: Int): Float {
    val pack = s.groupSize * s.massKg
    val size = min(1f, pack / (1.5f * ps.massKg))
    val small = if (ps.massKg < s.massKg * 0.05f) 0.3f else 1f // too small to be worth a pack's while
    // Shelter: crags, dens, thickets and trees, as the prey uses them; small prey hide in any cover.
    // (Hunters that follow the scent find much of what hides; shelter counts for half against them.)
    val refuge = 1f - 0.5f * SHELTER_EFFECT * Niches.shelter(ps, p, land)
    val hide = if (ps.massKg < HIDING_KG) 1f - HIDING * land.cover(p) else 1f
    // Stalkers hunt by night: dark weeks around the new moon hide them, bright ones give them away.
    val dark = 1f + MOON_DARK * s.stalking * (1f - 2f * moon)
    // A courser must run its quarry down; a stalker gets close unseen and needs no speed.
    val speed = s.stalking + (1f - s.stalking) * min(1f, s.speedKmh / ps.speedKmh)
    return (size * small * (1f - ps.defence) * refuge * hide * dark * speed).coerceIn(0f, 1f)
}

/**
 * Hunters hunt along their way: the resident herds of every place they passed, and the herds they
 * met (that passed the same hexes this week). Each kill feeds the pack; the hunt stops when it has
 * eaten its fill or its chances are spent.
 */
internal fun HexGroups.hunt(visits: HashMap<Int, ArrayList<HexGroup>>, weather: Weather) {
    for (g in groups.filter { it.alive && it.species.carnivore }.shuffled(rnd)) {
        val s = g.species
        val need = g.dailyNeedKg * days * s.diet.meat
        var got = 0f
        var tries = MAX_CHASES
        // What the herds it met on the way gave it (see [meet]).
        got += g.meatMet
        // The resident herds of the places passed (the band it ends in, in each hex on its way): in
        // each, the pack sweeps some km² of ground and comes on the herds there; a share of the
        // chases ends in a kill.
        val sweep = sweptKm2(s) / g.path.size.coerceAtLeast(1) * min(2f, g.path.size.toFloat())
        for (h in g.path) {
            if (got >= need) break
            val p = hw.placeOf[hw.placeBand[g.place]][h].takeIf { it >= 0 } ?: continue
            val area = land.areaKm2[p]
            val swept = min(area, sweep)
            for ((k, ps) in fauna.kinds.withIndex()) {
                if (got >= need) break
                val dens = fauna.adults[k][p] + fauna.young[k][p]
                if (dens <= 0f) continue
                // Prey resting while the hunter is about lie up in cover and are found less.
                val awake = 0.5f + 0.5f * Niches.overlap(s, ps)
                val expected = dens * swept * CATCH * catchable(s, ps, p) * awake
                val meat = ps.massKg * MEAT_SHARE
                val wanted = ((need - got) / (0.6f * meat)).coerceAtLeast(0f)
                var n = poisson(min(expected, wanted + 1f))
                n = min(n, (dens * area * 0.5f).toInt())
                if (n <= 0) continue
                val youngShare = fauna.young[k][p] / dens
                val ny = (n * youngShare).toInt(); val na = n - ny
                fauna.young[k][p] = max(0f, fauna.young[k][p] - ny / area); fauna.adults[k][p] = max(0f, fauna.adults[k][p] - na / area)
                got += na * meat + ny * 0.3f * ps.massKg
                // Bones, hide and gut left at the kill.
                small.carrion[p] += na * ps.massKg * (1f - MEAT_SHARE) / (area * 1e6f)
                kills.merge(ps.name, n, Int::plus)
                hunting.merge("${s.name}:kills of residents", n, Int::plus)
            }
        }
        // Small game and carrion along the way make up what the big game did not.
        val smallWant = g.dailyNeedKg * days * s.diet.smallPrey
        val scraps = g.dailyNeedKg * days * s.diet.carrion
        var gotSmall = 0f; var gotScraps = 0f
        for (h in g.path) {
            val p = hw.placeOf[hw.placeBand[g.place]][h].takeIf { it >= 0 } ?: continue
            val swept = min(land.areaKm2[p], sweptKm2(s) / g.path.size.coerceAtLeast(1))
            if (smallWant - gotSmall > 0f) gotSmall += this.small.catchSmall(p, (smallWant - gotSmall) / swept, weather) * swept
            if (scraps - gotScraps > 0f) gotScraps += this.small.scavenge(p, min((scraps - gotScraps) / (swept * 1e6f), 0.5f * this.small.carrion[p])) * swept * 1e6f
            // Bone-crackers live on what the others leave long after the meat is gone.
            if (Niches.cracksBones(s) && scraps - gotScraps > 0f) gotScraps += this.small.crackBones(p, min((scraps - gotScraps) / (swept * 1e6f), 0.5f * this.small.bones[p])) * swept * 1e6f
        }
        // Omnivorous hunters (jackals, hyenas) eat fruit and roots as well, where they end the week;
        // bands gather them over all the ground they walked, the women's and children's day round.
        val d = s.diet
        val gatherAt = if (s.human) g.path.toList().mapNotNull { h: Int -> hw.placeOf[hw.placeBand[g.place]][h].takeIf { it >= 0 } }.ifEmpty { listOf(g.place) } else listOf(g.place)
        var plants = 0f
        if (d.fruit + d.roots > 0f) for (q in gatherAt) {
            val want = g.dailyNeedKg * days * (d.fruit + d.roots) / gatherAt.size / (land.areaKm2[q] * 1e6f)
            // Bands also reap and beat ripe grass seed into baskets, and take it before the fruit.
            val wantFruit = want * d.fruit / (d.fruit + d.roots)
            val seed = if (s.human) fauna.food.take(fauna.food.seeds, q, wantFruit) else 0f
            plants += (seed + fauna.food.take(fauna.food.fruit, q, wantFruit - seed) +
                fauna.food.take(fauna.food.roots, q, want * d.roots / (d.fruit + d.roots))) * land.areaKm2[q] * 1e6f
        }
        val wantAll = g.dailyNeedKg * days * (d.meat + d.smallPrey + d.carrion + d.fruit + d.roots).coerceAtLeast(1e-6f)
        var eaten = min(got, need) + gotSmall + gotScraps + plants
        var leftMeat = max(0f, got - need)
        if (s.human) {
            // A band keeps a larder: meat it could not eat is dried, and in good weeks it gathers more
            // nuts, acorns, seeds and roots than it needs and stores them; in lean weeks it lives on them.
            val cap = LARDER_DAYS * g.dailyNeedKg
            val dried = min(0.5f * leftMeat, max(0f, cap - g.larder)); g.larder += dried; leftMeat -= dried
            if (eaten < wantAll && g.larder > 0f) { val draw = min(g.larder, wantAll - eaten); g.larder -= draw; eaten += draw }
            else if (g.larder < cap) {
                val room = min(cap - g.larder, g.dailyNeedKg * days)
                var stored = 0f
                for (q in gatherAt) {
                    val want = room / gatherAt.size / (land.areaKm2[q] * 1e6f)
                    stored += (fauna.food.take(fauna.food.seeds, q, 0.4f * want) + fauna.food.take(fauna.food.fruit, q, 0.3f * want) + fauna.food.take(fauna.food.roots, q, 0.3f * want)) * land.areaKm2[q] * 1e6f
                }
                g.larder += stored
            }
            g.larder *= 1f - LARDER_SPOIL
        }
        g.fed = (eaten / wantAll).coerceIn(0f, 1f)
        // What the pack could not finish is left for the scavengers; what it ate comes back in part as dung.
        val p = g.place
        small.carrion[p] += leftMeat / (land.areaKm2[p] * 1e6f)
        small.dung[p] += HexSmallLife.DUNG * (min(got, need) + gotSmall + gotScraps) / (land.areaKm2[p] * 1e6f)
    }
}

/** The day [g] enters each hex of its path: effort so far over its pace, the first hex on day 0. */
internal fun HexGroups.timetable(g: HexGroup): FloatArray {
    val s = g.species
    val out = FloatArray(g.path.size)
    if (g.path.size <= 1) return out
    val cross = hex.crossings[Catalogue.travel(s).ordinal]
    var effort = 0f
    for (i in 1 until g.path.size) {
        val a = g.path[i - 1]; val b = g.path[i]
        val d = (0 until 6).firstOrNull { hex.neighbour(a, it) == b } ?: 0
        effort += cross[a * 6 + d].takeIf { it.isFinite() } ?: hex.hexKm.toFloat()
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
 * aside, scattered, driven off, stopping to eat a kill) ends it where that happened, and its
 * later meetings do not take place. Herds meet hunters, and hunters of a kind meet each other;
 * herds of different kinds pass each other by. After [MAX_MEETINGS] meetings a group's week
 * runs on without more.
 */
internal fun HexGroups.meet(weather: Weather) {
    val byHex = HashMap<Int, ArrayList<Stay>>()
    for ((k, g) in groups.withIndex()) {
        if (!g.alive) continue
        for (i in g.path.indices) {
            val to = if (i + 1 < g.path.size) g.days[i + 1] else days.toFloat()
            byHex.getOrPut(g.path[i]) { ArrayList() } += Stay(k, i, g.days[i], to)
        }
    }
    val events = ArrayList<Meeting>()
    for ((_, stays) in byHex) {
        if (stays.size < 2) continue
        for (x in stays.indices) for (y in x + 1 until stays.size) {
            val sa = stays[x]; val sb = stays[y]
            val a = groups[sa.k]; val b = groups[sb.k]
            val matters = a.species.carnivore || b.species.carnivore
            if (!matters) continue
            val from = max(sa.from, sb.from); val to = min(sa.to, sb.to)
            if (from > to) continue
            events += Meeting(from, to - from, sa.k, sa.index, sb.k, sb.index)
        }
    }
    events.sortWith(compareBy({ it.day }, { it.a }, { it.b }))
    val stop = IntArray(groups.size) { groups[it].path.size - 1 }
    val count = IntArray(groups.size)
    for (e in events) {
        // Both must still be on their way at that point, and not past their share of meetings.
        if (e.ai > stop[e.a] || e.bi > stop[e.b]) continue
        if (count[e.a] >= MAX_MEETINGS || count[e.b] >= MAX_MEETINGS) continue
        val a = groups[e.a]; val b = groups[e.b]
        if (!a.alive || !b.alive) continue
        val h = a.path[e.ai]
        // Sharing a hex is not meeting: they must cross each other's ground, be about at the same
        // hours, and neither slip away first.
        val p = hw.placeOf[hw.placeBand[a.place]][h].takeIf { it >= 0 } ?: a.place
        if (rnd.nextFloat() >= meetChance(a, b, p, e.span)) continue
        if (slipsAway(a, b, p) || slipsAway(b, a, p)) { meetings.merge("slipped away", 1, Int::plus); continue }
        count[e.a]++; count[e.b]++
        if (a.species.carnivore && b.species.carnivore) {
            if (a.species.human && b.species.human) bandsMeet(a, b)
            else if (a.species == b.species) rivalsMeet(a, e.a, e.ai, b, e.b, e.bi, h, stop) else enemiesMeet(a, e.a, e.ai, b, e.b, e.bi, h, stop)
        }
        else {
            val (hunter, hi, herd, ri) = if (a.species.carnivore) listOf(e.a, e.ai, e.b, e.bi) else listOf(e.b, e.bi, e.a, e.ai)
            hunterMeetsHerd(groups[hunter], hunter, hi, groups[herd], herd, ri, h, stop)
        }
    }
    // Each group ends its week where it stopped: the same height band in that hex if it has one.
    for ((k, g) in groups.withIndex()) {
        if (!g.alive || stop[k] == g.path.size - 1) continue
        val i = max(0, stop[k])
        val h = g.path[i]
        g.path = g.path.copyOf(i + 1); g.days = g.days.copyOf(i + 1)
        val band = hw.placeBand[g.place]
        g.place = hw.placeOf[band][h].takeIf { it >= 0 } ?: (0 until HexWorld.BANDS_M.size).map { hw.placeOf[it][h] }.firstOrNull { it >= 0 } ?: g.place
    }
}

/**
 * Two bands meet: they share news of good ground (each learns where the other did best this month),
 * and a band that is going hungry is given a share of the other's larder.
 */
internal fun HexGroups.bandsMeet(a: HexGroup, b: HexGroup) {
    meetings.merge("bands met", 1, Int::plus)
    val (poor, rich) = if (a.fed + a.larder / (a.dailyNeedKg * 30f) < b.fed + b.larder / (b.dailyNeedKg * 30f)) a to b else b to a
    if (poor.fed < 0.8f && rich.larder > 0f) {
        val gift = BAND_GIFT * rich.larder
        rich.larder -= gift; poor.larder += gift
        meetings.merge("food given", 1, Int::plus)
    }
    // Word of where the other did better this month.
    for (m in 0 until 12) {
        if (b.seasonQ[m] > a.seasonQ[m] + 0.1f && b.seasonPlace[m] >= 0) { a.seasonPlace[m] = b.seasonPlace[m]; a.seasonQ[m] = 0.5f * (a.seasonQ[m] + b.seasonQ[m]) }
        else if (a.seasonQ[m] > b.seasonQ[m] + 0.1f && a.seasonPlace[m] >= 0) { b.seasonPlace[m] = a.seasonPlace[m]; b.seasonQ[m] = 0.5f * (a.seasonQ[m] + b.seasonQ[m]) }
    }
}

/**
 * Chance two groups that are in the same place for [span] days run into each other: the ground both
 * search in that time against the place's size, times how much they are about at the same hours.
 */
internal fun HexGroups.meetChance(a: HexGroup, b: HexGroup, p: Int, span: Float): Float {
    // A hunter after a herd follows its tracks and smell, so it covers far more of the ground that
    // matters; and finds the herd lying up as well as out feeding, if less often.
    val hunting = a.species.carnivore != b.species.carnivore
    val track = if (hunting) TRACKING else 1f
    val searched = (sweptKm2(a.species) + sweptKm2(b.species)) * track * max(span, 0.5f) / days
    val hours = Niches.overlap(a.species, b.species)
    return min(1f, searched / land.areaKm2[p]) * (if (hunting) 0.5f + 0.5f * hours else hours)
}

/**
 * Whether hunter [g] senses [other] first and keeps out of its way, as it does a much stronger one
 * of another kind; the keener its senses and the better its shelter, the likelier. (Herds watch for
 * hunters when they meet: see [hunterMeetsHerd].)
 */
internal fun HexGroups.slipsAway(g: HexGroup, other: HexGroup, p: Int): Boolean {
    val s = g.species; val o = other.species
    val wants = when {
        s.carnivore && o.carnivore && s != o -> strength(other) > 2f * strength(g)
        else -> false
    }
    if (!wants) return false
    val first = s.senseKm / (s.senseKm + o.senseKm)
    val chance = AVOID_SKILL * first * (1f - o.stalking * 0.5f) + SHELTER_EFFECT * 0.5f * Niches.shelter(s, p, land)
    return rnd.nextFloat() < min(0.9f, chance)
}

/** How open hex [h] is where a group passes: the share of the ground with no cover to hide in. */
internal fun HexGroups.openness(h: Int, band: Int): Float {
    val p = hw.placeOf[band][h].takeIf { it >= 0 } ?: return 0.5f
    return 1f - land.cover(p)
}

/**
 * Standing orders, hunter and herd. The herd watches for hunters: in open country it often sees
 * them first and turns aside, ending its week short of that hex. Otherwise there is a chase,
 * likelier to end in a kill if the hunters came on it unseen; a herd that was chased scatters and
 * stops where it is; hunters that killed stop there to eat.
 */
internal fun HexGroups.hunterMeetsHerd(hunter: HexGroup, hk: Int, hi: Int, herd: HexGroup, rk: Int, ri: Int, h: Int, stop: IntArray) {
    meetings.merge("hunter-herd", 1, Int::plus)
    val open = openness(h, hw.placeBand[herd.place])
    // A herd hunted lately keeps a sharper watch.
    val seen = rnd.nextFloat() < min(0.95f, VIGILANCE * open * (1f - hunter.species.stalking) * (1f + herd.wary))
    if (seen) {
        meetings.merge("herd turned aside", 1, Int::plus)
        hunting.merge("${hunter.species.name}:herds that saw it first", 1, Int::plus)
        stop[rk] = min(stop[rk], max(0, ri - 1))
        return
    }
    // A hunter that has eaten its fill this week lets the herd pass.
    val need = hunter.dailyNeedKg * days * hunter.species.diet.meat
    if (hunter.meatMet >= need) return
    val p = hw.placeOf[hw.placeBand[herd.place]][h].takeIf { it >= 0 } ?: herd.place
    // Quarry far beyond the party (an aurochs herd for a pair of jackals) is not tried at all.
    val catchable = catchable(hunter.species, herd.species, p)
    if (catchable < NOT_WORTH_TRYING) return
    val chance = CATCH * SURPRISE * catchable * (1f - 0.5f * herd.wary)
    stop[rk] = min(stop[rk], ri) // scattered
    // Caught or not, the herd is wary for weeks after.
    herd.wary += WARY_GAIN * (1f - herd.wary)
    hunting.merge("${hunter.species.name}:chases", 1, Int::plus)
    if (rnd.nextFloat() < chance && herd.adults > 0) {
        if (herd.young > 0 && rnd.nextFloat() < 0.5f) { herd.young--; hunter.meatMet += 0.3f * herd.species.massKg }
        else {
            herd.adults--; hunter.meatMet += herd.species.massKg * MEAT_SHARE
            small.carrion[herd.place] += herd.species.massKg * (1f - MEAT_SHARE) / (land.areaKm2[herd.place] * 1e6f)
        }
        kills.merge(herd.species.name, 1, Int::plus)
        hunting.merge("${hunter.species.name}:kills from herds", 1, Int::plus)
        stop[hk] = min(stop[hk], hi) // stays to eat
    }
    // Big, armed prey fight back: a horn or a kick now and then kills one of the hunters.
    if (hunter.adults > 0 && rnd.nextFloat() < fightBack(herd.species, hunter.adults * hunter.species.massKg)) {
        hunter.adults--
        deaths.merge("${hunter.species.name}:prey fought back", 1, Int::plus)
        small.carrion[hunter.place] += hunter.species.massKg / (land.areaKm2[hunter.place] * 1e6f)
    }
}

/** Chance a chase after [prey] costs a hunting party of [packKg] one of its number: armed prey, and prey big against the party. */
internal fun fightBack(prey: Species, packKg: Float) = FIGHT_BACK * prey.defence * min(2f, prey.massKg / max(1f, packKg) * 2f)

/**
 * Standing orders, hunters of different kinds. The weaker gives way; if it had made a kill this week
 * the stronger drives it off and takes most of the meat (hyenas rob leopards, lions rob everyone);
 * a much stronger party now and then kills one of the other.
 */
internal fun HexGroups.enemiesMeet(a: HexGroup, ak: Int, ai: Int, b: HexGroup, bk: Int, bi: Int, h: Int, stop: IntArray) {
    meetings.merge("hunters of other kinds", 1, Int::plus)
    val sa = a.adults * a.species.massKg * a.species.ferocity * (if (a.home == h) HOME_ADVANTAGE else 1f)
    val sb = b.adults * b.species.massKg * b.species.ferocity * (if (b.home == h) HOME_ADVANTAGE else 1f)
    val (strong, weak, wk, wi) = if (sa >= sb) listOf(a, b, bk, bi) else listOf(b, a, ak, ai)
    strong as HexGroup; weak as HexGroup; wk as Int; wi as Int
    val ratio = max(sa, sb) / max(1f, min(sa, sb))
    if (ratio < THEFT_RATIO) return // near equals keep their distance
    // A leopard hauls its kill up a tree where there are trees; others guard it where they can.
    val cached = if (Niches.Shelter.TREE in Niches.shelters(weak.species)) land.canopy(weak.place) else 0f
    if (weak.meatMet > 0f && rnd.nextFloat() < THEFT * (1f - cached)) {
        val taken = 0.7f * weak.meatMet
        weak.meatMet -= taken; strong.meatMet += taken
        meetings.merge("kill stolen", 1, Int::plus)
    }
    if (ratio > 2f && weak.adults > 0 && rnd.nextFloat() < INTRAGUILD_KILL * (1f - SHELTER_EFFECT * Niches.shelter(weak.species, weak.place, land))) {
        weak.adults--
        strong.meatMet += 0.5f * weak.species.massKg
        deaths.merge("${weak.species.name}:killed by ${strong.species.name}", 1, Int::plus)
    }
    stop[wk] = min(stop[wk], max(0, wi - 1)) // the weaker turns back
}

/**
 * Packs that hold ground mark the hexes they worked this week within their territory; marks fade
 * unless renewed, and a stale mark of another pack is marked over.
 */
internal fun HexGroups.mark() {
    for (m in marks.values) for (i in m.indices) m[i] *= MARK_KEEP
    for (g in groups) {
        val s = g.species
        if (!g.alive || !s.carnivore || s.human || g.home < 0) continue
        val m = marks.getOrPut(s.index) { FloatArray(hex.size) }
        val who = markers.getOrPut(s.index) { IntArray(hex.size) { -1 } }
        for (h in g.path + g.home) {
            if (hex.distanceKm(h, g.home) > territoryKm(s)) continue
            if (who[h] == g.id || m[h] < MARK_FRESH) { m[h] = 1f; who[h] = g.id }
        }
    }
}

/**
 * Standing orders, rival packs. On its own ground a pack holds; an intruder, or the weaker of two
 * strangers, gives way and turns back. Packs of near equal strength fight: now and then one dies,
 * and the loser gives up the ground.
 */
internal fun HexGroups.rivalsMeet(a: HexGroup, ak: Int, ai: Int, b: HexGroup, bk: Int, bi: Int, h: Int, stop: IntArray) {
    meetings.merge("rival packs", 1, Int::plus)
    val sa = a.adults * (if (a.home == h) HOME_ADVANTAGE else 1f); val sb = b.adults * (if (b.home == h) HOME_ADVANTAGE else 1f)
    val ratio = max(sa, sb) / max(1f, min(sa, sb))
    if (ratio > FIGHT_RATIO) {
        // The weaker gives way: back the way it came.
        if (sa < sb) stop[ak] = min(stop[ak], max(0, ai - 1)) else stop[bk] = min(stop[bk], max(0, bi - 1))
        meetings.merge("pack gave way", 1, Int::plus)
        return
    }
    meetings.merge("packs fought", 1, Int::plus)
    val loser = if (rnd.nextFloat() < sa / (sa + sb)) b else a
    if (rnd.nextFloat() < RIVAL_KILL && loser.adults > 0) { loser.adults--; deaths.merge("${loser.species.name}:rivals", 1, Int::plus) }
    if (loser.home == h) loser.home = -1
    if (loser === a) stop[ak] = min(stop[ak], max(0, ai - 1)) else stop[bk] = min(stop[bk], max(0, bi - 1))
}

/** A pack without ground settles where no other pack of its kind has fresh marks. */
internal fun HexGroups.settle() {
    val claimed = HashSet<Long>()
    for (g in groups) if (g.alive && g.species.carnivore && !g.species.human && g.home < 0) {
        val h = hw.placeHex[g.place]
        val key = g.species.index.toLong() * hex.size + h
        if (foreignMark(g.species, h, g.id) < MARK_FRESH && claimed.add(key)) g.home = h
    }
}
