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
/** Groups [a] and [b] meet on [day], at their paths' [ai] and [bi]. */
private class Meeting(val day: Float, val a: Int, val ai: Int, val b: Int, val bi: Int)

/**
 * How catchable prey [ps] is for hunter [s] in place [p], 0..1: size against the pack, defence,
 * refuge in broken ground, small prey hiding in thick cover, and for stalkers, dark nights.
 */
internal fun HexGroups.catchable(s: Species, ps: Species, p: Int): Float {
    val pack = s.groupSize * s.massKg
    val size = min(1f, pack / (1.5f * ps.massKg))
    val small = if (ps.massKg < s.massKg * 0.05f) 0.3f else 1f // too small to be worth a pack's while
    val refuge = 1f - 0.8f * max(0f, ps.habitat.rugged) * land.ruggedness[p]
    val hide = if (ps.massKg < HIDING_KG) 1f - HIDING * land.cover(p) else 1f
    // Stalkers hunt by night: dark weeks around the new moon hide them, bright ones give them away.
    val dark = 1f + MOON_DARK * s.stalking * (1f - 2f * moon)
    return (size * small * (1f - ps.defence) * refuge * hide * dark).coerceIn(0f, 1f)
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
        val sweep = SWEEP_KM2 / g.path.size.coerceAtLeast(1) * min(2f, g.path.size.toFloat())
        for (h in g.path) {
            if (got >= need) break
            val p = hw.placeOf[hw.placeBand[g.place]][h].takeIf { it >= 0 } ?: continue
            val area = land.areaKm2[p]
            val swept = min(area, sweep)
            for ((k, ps) in fauna.kinds.withIndex()) {
                if (got >= need) break
                val dens = fauna.adults[k][p] + fauna.young[k][p]
                if (dens <= 0f) continue
                val expected = dens * swept * CATCH * catchable(s, ps, p)
                val meat = ps.massKg * MEAT_SHARE
                val wanted = ((need - got) / (0.6f * meat)).coerceAtLeast(0f)
                var n = poisson(min(expected, wanted + 1f))
                n = min(n, (dens * area * 0.5f).toInt())
                if (n <= 0) continue
                val youngShare = fauna.young[k][p] / dens
                val ny = (n * youngShare).toInt(); val na = n - ny
                fauna.young[k][p] = max(0f, fauna.young[k][p] - ny / area); fauna.adults[k][p] = max(0f, fauna.adults[k][p] - na / area)
                got += na * meat + ny * 0.3f * ps.massKg
                kills.merge(ps.name, n, Int::plus)
            }
        }
        // Small game and carrion along the way make up what the big game did not.
        val smallWant = g.dailyNeedKg * days * s.diet.smallPrey
        val scraps = g.dailyNeedKg * days * s.diet.carrion
        var gotSmall = 0f; var gotScraps = 0f
        for (h in g.path) {
            val p = hw.placeOf[hw.placeBand[g.place]][h].takeIf { it >= 0 } ?: continue
            val swept = min(land.areaKm2[p], SWEEP_KM2 / g.path.size.coerceAtLeast(1))
            if (smallWant - gotSmall > 0f) gotSmall += this.small.catchSmall(p, (smallWant - gotSmall) / swept, weather) * swept
            if (scraps - gotScraps > 0f) gotScraps += this.small.scavenge(p, min((scraps - gotScraps) / (swept * 1e6f), 0.5f * this.small.carrion[p])) * swept * 1e6f
        }
        val wantAll = g.dailyNeedKg * days * (s.diet.meat + s.diet.smallPrey + s.diet.carrion).coerceAtLeast(1e-6f)
        g.fed = ((min(got, need) + gotSmall + gotScraps) / wantAll).coerceIn(0f, 1f)
        // What the pack could not finish is left for the scavengers.
        val p = g.place
        small.carrion[p] += max(0f, got - need) * 0.5f / (land.areaKm2[p] * 1e6f)
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
            val matters = (a.species.carnivore != b.species.carnivore) || (a.species.carnivore && a.species == b.species)
            if (!matters) continue
            val from = max(sa.from, sb.from); val to = min(sa.to, sb.to)
            if (from > to) continue
            events += Meeting(from, sa.k, sa.index, sb.k, sb.index)
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
        count[e.a]++; count[e.b]++
        val h = a.path[e.ai]
        if (a.species.carnivore && b.species.carnivore) rivalsMeet(a, e.a, e.ai, b, e.b, e.bi, h, stop)
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
        stop[rk] = min(stop[rk], max(0, ri - 1))
        return
    }
    // A hunter that has eaten its fill this week lets the herd pass.
    val need = hunter.dailyNeedKg * days * hunter.species.diet.meat
    if (hunter.meatMet >= need) return
    val p = hw.placeOf[hw.placeBand[herd.place]][h].takeIf { it >= 0 } ?: herd.place
    val chance = CATCH * SURPRISE * catchable(hunter.species, herd.species, p) * (1f - 0.5f * herd.wary)
    stop[rk] = min(stop[rk], ri) // scattered
    // Caught or not, the herd is wary for weeks after.
    herd.wary += WARY_GAIN * (1f - herd.wary)
    if (rnd.nextFloat() < chance && herd.adults > 0) {
        if (herd.young > 0 && rnd.nextFloat() < 0.5f) { herd.young--; hunter.meatMet += 0.3f * herd.species.massKg }
        else { herd.adults--; hunter.meatMet += herd.species.massKg * MEAT_SHARE }
        kills.merge(herd.species.name, 1, Int::plus)
        stop[hk] = min(stop[hk], hi) // stays to eat
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

/** A pack without ground settles where no other of its kind holds any. */
internal fun HexGroups.settle() {
    val held = groups.filter { it.alive && it.species.carnivore && it.home >= 0 }.map { it.species.index.toLong() * hex.size + it.home }.toHashSet()
    for (g in groups) if (g.alive && g.species.carnivore && g.home < 0) {
        val h = hw.placeHex[g.place]
        if (g.species.index.toLong() * hex.size + h !in held) { g.home = h; held += g.species.index.toLong() * hex.size + h }
    }
}
