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
internal fun HexGroups.catchable(s: Species, ps: Species, p: Int, pack: Float = s.groupSize * s.massKg): Float {
    val size = min(1f, pack / (1.5f * ps.massKg))
    val small = if (ps.massKg < s.massKg * 0.05f) 0.3f else 1f // too small to be worth a pack's while
    // Shelter: crags, dens, thickets and trees, as the prey uses them; small prey hide in any cover.
    // (Hunters that follow the scent find much of what hides; shelter counts for half against them.)
    val refuge = 1f - 0.5f * SHELTER_EFFECT * Niches.shelter(ps, p, land)
    // Small prey hide in thick cover from hunters that run them down; a stalker uses the same cover to get close.
    val hide = 1f - HIDING * land.cover(p) * (1f - s.stalking) * max(0f, 1f - ps.massKg / HIDING_KG)
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
        // What the herds it met on the way gave it (see [meet]).
        got += g.meatMet
        // The ground it searches this week, shared over the hexes it passed: a bigger party fans out
        // over more of it, as the square root of its numbers against its kind's usual party.
        val sweep = sweptKm2(s) * kotlin.math.sqrt(max(1, g.adults).toFloat() / s.groupSize) / g.path.size.coerceAtLeast(1)
        val packKg = max(1, g.adults) * s.massKg
        // Small game and carrion along the way first: they cost little and come as found.
        val smallWant = g.dailyNeedKg * days * s.diet.smallPrey
        val scraps = g.dailyNeedKg * days * s.diet.carrion
        var gotSmall = 0f; var gotScraps = 0f
        for (h in g.path) {
            val p = hw.placeOf[hw.placeBand[g.place]][h].takeIf { it >= 0 } ?: continue
            val swept = min(land.areaKm2[p], sweep)
            // Bands set snare lines and nets across the ground, as many as they have and know how to.
            val snares = g.band?.snares() ?: 1f
            // Only the ground searched yields: snare lines and nets widen it.
            if (smallWant - gotSmall > 0f) gotSmall += this.small.catchSmallIn(p, smallWant - gotSmall, swept * snares, weather)
            if (scraps - gotScraps > 0f) gotScraps += this.small.scavengeIn(p, scraps - gotScraps, swept)
            // Bone-crackers live on what the others leave long after the meat is gone.
            if (Niches.cracksBones(s) && scraps - gotScraps > 0f) gotScraps += this.small.crackBonesIn(p, scraps - gotScraps, swept)
        }
        // Then big game: its own share of the diet, and what small game and carrion did not give, up
        // to (1 + SWITCH) times its share. The resident herds of the places passed (the band it ends in,
        // in each hex on its way): in each, the party sweeps its ground and comes on the herds there,
        // and goes after what pays best first.
        val target = min((1f + SWITCH) * need, need + max(0f, smallWant - gotSmall) + max(0f, scraps - gotScraps))
        val order = IntArray(fauna.kinds.size); val worth = FloatArray(fauna.kinds.size); val chance = FloatArray(fauna.kinds.size)
        for (h in g.path) {
            if (got >= target) break
            val p = hw.placeOf[hw.placeBand[g.place]][h].takeIf { it >= 0 } ?: continue
            val area = land.areaKm2[p]
            val swept = min(area, sweep)
            var count = 0
            for (k in fauna.kinds.indices) {
                val ps = fauna.kinds[k]
                val dens = fauna.adults[k][p] + fauna.young[k][p]
                if (dens <= 0f) continue
                // Prey resting while the hunter is about lie up in cover and are found less.
                chance[k] = CATCH * catchable(s, ps, p, packKg) * (0.5f + 0.5f * Niches.overlap(s, ps)) * (g.band?.huntEdge(ps) ?: 1f)
                worth[k] = dens * chance[k] * ps.massKg
                // Insertion into the ranking, best-paying first.
                var i = count++
                while (i > 0 && worth[order[i - 1]] < worth[k]) { order[i] = order[i - 1]; i-- }
                order[i] = k
            }
            for (r in 0 until count) {
                val k = order[r]
                if (got >= target) break
                val ps = fauna.kinds[k]
                val dens = fauna.adults[k][p] + fauna.young[k][p]
                if (dens <= 0f || chance[k] <= 0f) continue
                // Each kill takes time to make and eat, so kills level off where game is thick (Holling).
                val handling = HANDLING_DAYS + ps.massKg * MEAT_SHARE / (packKg * EATING_RATE)
                val found = dens * swept * chance[k]
                val expected = found / (1f + found * handling / days)
                val meat = ps.massKg * MEAT_SHARE
                val wanted = ((target - got) / (0.6f * meat)).coerceAtLeast(0f)
                var n = poisson(min(expected, wanted + 1f))
                n = min(n, (dens * area * 0.5f).toInt())
                if (n <= 0) continue
                val spare = g.band?.holds(Tradition.TABOO_YOUNG) == true
                val youngShare = if (spare) 0f else fauna.young[k][p] / dens
                val ny = (n * youngShare).toInt(); val na = n - ny
                fauna.young[k][p] = max(0f, fauna.young[k][p] - ny / area); fauna.adults[k][p] = max(0f, fauna.adults[k][p] - na / area)
                got += na * meat + ny * 0.3f * ps.massKg
                g.band?.let { it.skin(ps, na); it.practise(Forage.HUNT) }
                // Bones, hide and gut left at the kill.
                small.carrion[p] += na * ps.massKg * (1f - MEAT_SHARE) / (area * 1e6f)
                kills.merge(ps.name, n, Int::plus)
                hunting.merge("${s.name}:kills of residents", n, Int::plus)
            }
        }
        if (gotSmall > 0f) g.band?.practise(Forage.SMALL_GAME)
        // A band short of meat fishes the river, the lake or the sea shore by its camp.
        val band = g.band
        if (band != null && got < need) {
            val caught = fauna.water.fishFor(hw.placeHex[g.place], need - got, band.fishing())
            got += caught
            if (caught > 0f) band.practise(Forage.FISH)
            hunting.merge("${s.name}:fish, kg", caught.toInt(), Int::plus)
        }
        // Omnivorous hunters (jackals, hyenas) eat fruit and roots as well, where they end the week;
        // bands gather them over all the ground they walked, the women's and children's day round.
        val d = s.diet
        val gatherAt = if (s.human) g.path.toList().mapNotNull { h: Int -> hw.placeOf[hw.placeBand[g.place]][h].takeIf { it >= 0 } }.ifEmpty { listOf(g.place) } else listOf(g.place)
        var plants = 0f
        // A band gets as much of what grows as its skill and its baskets, sickles and grinding stones allow.
        fun gather(stock: FloatArray, q: Int, want: Float, seed: Boolean = false) =
            fauna.food.take(stock, q, if (band != null) min(want, band.gatherShare(seed) * stock[q]) else want)
        if (d.fruit + d.roots > 0f) for (q in gatherAt) {
            val want = g.dailyNeedKg * days * (d.fruit + d.roots) / gatherAt.size / (land.areaKm2[q] * 1e6f)
            // Bands also reap and beat ripe grass seed into baskets, and take it before the fruit.
            val wantFruit = want * d.fruit / (d.fruit + d.roots)
            val seed = if (s.human) gather(fauna.food.seeds, q, wantFruit, true) else 0f
            plants += (seed + gather(fauna.food.fruit, q, wantFruit - seed) +
                gather(fauna.food.roots, q, want * d.roots / (d.fruit + d.roots))) * land.areaKm2[q] * 1e6f
        }
        if (plants > 0f) band?.practise(Forage.GATHER)
        val wantAll = g.dailyNeedKg * days * (d.meat + d.smallPrey + d.carrion + d.fruit + d.roots).coerceAtLeast(1e-6f)
        // Diet switching: what one part of the diet fell short by is made up from the others, each
        // stretching to at most (1 + SWITCH) times its usual share: the rest of a big kill, more small
        // game, more scavenging, more fruit. A generalist (jackal, wolf, leopard) gets through a lean
        // spell that a specialist (cheetah, lion) does not.
        var meatEaten = min(got, need)
        var short = wantAll - (meatEaten + gotSmall + gotScraps + plants)
        if (short > 0f) {
            val more = min(got - meatEaten, min(short, SWITCH * need)); meatEaten += more; short -= more
            if (short > 0f && smallWant > 0f) {
                var extra = 0f; val cap = min(short, SWITCH * smallWant)
                for (h in g.path) {
                    if (extra >= cap) break
                    val q = hw.placeOf[hw.placeBand[g.place]][h].takeIf { it >= 0 } ?: continue
                    val swept = min(land.areaKm2[q], sweep)
                    extra += this.small.catchSmallIn(q, cap - extra, swept * (g.band?.snares() ?: 1f), weather)
                }
                gotSmall += extra; short -= extra
            }
            if (short > 0f && scraps > 0f) {
                var extra = 0f; val cap = min(short, SWITCH * scraps)
                for (h in g.path) {
                    if (extra >= cap) break
                    val q = hw.placeOf[hw.placeBand[g.place]][h].takeIf { it >= 0 } ?: continue
                    val swept = min(land.areaKm2[q], sweep)
                    extra += this.small.scavengeIn(q, cap - extra, swept)
                }
                gotScraps += extra; short -= extra
            }
            if (short > 0f && d.fruit + d.roots > 0f && !s.human) {
                val m2 = land.areaKm2[g.place] * 1e6f
                val cap = min(short, SWITCH * g.dailyNeedKg * days * (d.fruit + d.roots)) / m2
                val extra = (fauna.food.take(fauna.food.fruit, g.place, cap * d.fruit / (d.fruit + d.roots)) + fauna.food.take(fauna.food.roots, g.place, cap * d.roots / (d.fruit + d.roots))) * m2
                plants += extra; short -= extra
            }
        }
        var eaten = meatEaten + gotSmall + gotScraps + plants
        // What each hunting kind's diet asked for this week and what each part of it brought (for the diet line in the check).
        hunting.merge("${s.name}:diet asked, kg", (g.dailyNeedKg * days).toInt(), Int::plus)
        hunting.merge("${s.name}:diet meat, kg", meatEaten.toInt(), Int::plus)
        hunting.merge("${s.name}:diet small prey, kg", gotSmall.toInt(), Int::plus)
        hunting.merge("${s.name}:diet carrion, kg", gotScraps.toInt(), Int::plus)
        hunting.merge("${s.name}:diet plants, kg", plants.toInt(), Int::plus)
        if (s.human) { hunting.merge("${s.name}:meat eaten, kg", (meatEaten + gotSmall + gotScraps).toInt(), Int::plus); hunting.merge("${s.name}:plants eaten, kg", plants.toInt(), Int::plus) }
        // Gorging: what is left of a kill is eaten beyond need, as far as the gut holds and reserves are short.
        if (got > meatEaten && g.fat < s.maxFatDays) {
            val gorge = min(got - meatEaten, FATTEN * wantAll)
            g.surplus = gorge / g.dailyNeedKg.coerceAtLeast(1e-3f)
            got -= gorge
            small.dung[g.place] += HexSmallLife.DUNG * gorge / (land.areaKm2[g.place] * 1e6f)
        }
        var leftMeat = max(0f, got - meatEaten)
        if (band != null) {
            // A band keeps a larder: meat it could not eat is dried, and in good weeks it gathers more
            // nuts, acorns, seeds and roots than it needs and stores them; in lean weeks it lives on them.
            // How much it can keep is its storing skill and its baskets.
            val cap = LARDER_DAYS * g.dailyNeedKg * band.storeShare()
            val dried = min(0.5f * leftMeat, max(0f, cap - g.larder)); g.larder += dried; leftMeat -= dried
            if (eaten < wantAll && g.larder > 0f) { val draw = min(g.larder, wantAll - eaten); g.larder -= draw; eaten += draw }
            else if (g.larder < cap) {
                val room = min(cap - g.larder, g.dailyNeedKg * days)
                var stored = 0f
                for (q in gatherAt) {
                    val want = room / gatherAt.size / (land.areaKm2[q] * 1e6f)
                    stored += (gather(fauna.food.seeds, q, 0.4f * want, true) + gather(fauna.food.fruit, q, 0.3f * want) + gather(fauna.food.roots, q, 0.3f * want)) * land.areaKm2[q] * 1e6f
                }
                g.larder += stored
                if (stored + dried > 0f) band.practise(Forage.STORE)
            }
            g.larder *= 1f - LARDER_SPOIL
        }
        g.fed = (eaten / wantAll).coerceIn(0f, 1f)
        // What the pack could not finish is left for the scavengers; what it ate comes back in part as dung.
        val p = g.place
        small.carrion[p] += leftMeat / (land.areaKm2[p] * 1e6f)
        small.dung[p] += HexSmallLife.DUNG * (meatEaten + gotSmall + gotScraps) / (land.areaKm2[p] * 1e6f)
    }
}

/** The day [g] enters each hex of its path: effort so far over its pace, the first hex on day 0. */
internal fun HexGroups.timetable(s: Species, path: IntArray): FloatArray {
    val out = FloatArray(path.size)
    if (path.size <= 1) return out
    val cross = hex.crossings[Catalogue.travel(s).ordinal]
    var effort = 0f
    for (i in 1 until path.size) {
        val a = path[i - 1]; val b = path[i]
        var d = 0
        while (d < 6 && hex.neighbour(a, d) != b) d++
        if (d == 6) d = 0
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
    // Only hexes a hunter passes through can hold a meeting that matters: herds pass each other by.
    val hunted = java.util.BitSet(hex.size)
    for (g in groups) if (g.alive && g.species.carnivore) for (h in g.path) hunted.set(h)
    val byHex = HashMap<Int, ArrayList<Stay>>()
    for ((k, g) in groups.withIndex()) {
        if (!g.alive) continue
        for (i in g.path.indices) {
            if (!hunted.get(g.path[i])) continue
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
    if (cultureMeets(a, b)) return
    val (poor, rich) = if (a.fed + a.larder / (a.dailyNeedKg * 30f) < b.fed + b.larder / (b.dailyNeedKg * 30f)) a to b else b to a
    // Bands that hold to sharing give more, and sooner.
    val sharing = rich.band?.holds(Tradition.SHARING) == true
    if (poor.fed < (if (sharing) 0.9f else 0.8f) && rich.larder > 0f) {
        val gift = BAND_GIFT * (if (sharing) 2f else 1f) * rich.larder
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
    // Game bred to fear people over the millennia keeps a sharper watch for them.
    val fear = if (hunter.band != null) 0.5f + herd.species.fearOfPeople else 1f
    val seen = rnd.nextFloat() < min(0.95f, VIGILANCE * open * (1f - hunter.species.stalking) * (1f + herd.wary) * fear)
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
    val catchable = catchable(hunter.species, herd.species, p, max(1, hunter.adults) * hunter.species.massKg)
    if (catchable < NOT_WORTH_TRYING) return
    val chance = CATCH * SURPRISE * catchable * (1f - 0.5f * herd.wary) * (hunter.band?.huntEdge(herd.species) ?: 1f)
    hunter.band?.practise(Forage.HUNT)
    // By a kite, a band drives the herd between the walls and takes a large share of it at once.
    if (hunter.band != null && kites[p] >= 1f && herd.species in KITE_QUARRY) {
        val spare = hunter.band?.holds(Tradition.TABOO_YOUNG) == true
        val ta = binomial(herd.adults, KITE_TAKE); val ty = if (spare) 0 else binomial(herd.young, KITE_TAKE)
        herd.adults -= ta; herd.young -= ty
        val meat = ta * herd.species.massKg * MEAT_SHARE + ty * 0.3f * herd.species.massKg
        hunter.meatMet += meat
        hunter.band?.skin(herd.species, ta)
        small.carrion[herd.place] += ta * herd.species.massKg * (1f - MEAT_SHARE) / (land.areaKm2[herd.place] * 1e6f)
        kills.merge(herd.species.name, ta + ty, Int::plus)
        hunting.merge("${hunter.species.name}:kills in kite drives", ta + ty, Int::plus)
        stop[hk] = min(stop[hk], hi); stop[rk] = min(stop[rk], ri)
        herd.wary += WARY_GAIN * (1f - herd.wary)
        return
    }
    stop[rk] = min(stop[rk], ri) // scattered
    // Caught or not, the herd is wary for weeks after.
    herd.wary += WARY_GAIN * (1f - herd.wary)
    hunting.merge("${hunter.species.name}:chases", 1, Int::plus)
    if (rnd.nextFloat() < chance && herd.adults > 0) {
        if (herd.young > 0 && hunter.band?.holds(Tradition.TABOO_YOUNG) != true && rnd.nextFloat() < 0.5f) { herd.young--; hunter.meatMet += 0.3f * herd.species.massKg }
        else {
            herd.adults--; hunter.meatMet += herd.species.massKg * MEAT_SHARE
            hunter.band?.skin(herd.species, 1)
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
    val sa = strength(a) * (if (a.home == h) HOME_ADVANTAGE else 1f)
    val sb = strength(b) * (if (b.home == h) HOME_ADVANTAGE else 1f)
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
    // A camp fire, and dogs that bark at night, keep lions and hyenas from taking people.
    val fire = if (weak.species.human) (1f - CAMPFIRE) * (1f - DOG_GUARD * (weak.band?.dogShare ?: 0f)) else 1f
    // A much stronger party now and then kills one of the other: its young most often, an adult seldom.
    val odds = fire * (1f - SHELTER_EFFECT * Niches.shelter(weak.species, weak.place, land))
    if (ratio > 2f && weak.young > 0 && rnd.nextFloat() < INTRAGUILD_KILL_YOUNG * odds) {
        weak.young--
        strong.meatMet += 0.3f * 0.5f * weak.species.massKg
        deaths.merge("${weak.species.name}:young killed by ${strong.species.name}", 1, Int::plus)
    } else if (ratio > 2f && weak.adults > 0 && rnd.nextFloat() < INTRAGUILD_KILL * odds) {
        weak.adults--
        strong.meatMet += 0.5f * weak.species.massKg
        deaths.merge("${weak.species.name}:killed by ${strong.species.name}", 1, Int::plus)
    }
    stop[wk] = min(stop[wk], max(0, wi - 1)) // the weaker turns back
    if (weak.band != null) meetings.merge("bands turned back by ${strong.species.name}", 1, Int::plus)
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
    if (loser.home == h) {
        loser.home = -1
        // The winners take over the ground and kill the young they find there (as lions and wolves do).
        if (loser.young > 0) {
            val killed = binomial(loser.young, INFANTICIDE)
            loser.young -= killed
            if (killed > 0) deaths.merge("${loser.species.name}:young killed by rivals", killed, Int::plus)
        }
    }
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
