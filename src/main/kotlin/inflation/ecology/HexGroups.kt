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

/** A herd, pack or pride on the hexes: where it stands (a place: a height band of a hex) and who it is. */
class HexGroup(val id: Int, val species: Species, var place: Int, var adults: Int, var young: Int) {
    val size get() = adults + young
    val alive get() = adults > 0
    /** Fed last week, 0..1; body reserves, days of food. */
    var fed = 1f
    var fat = 0f
    /** The hexes it passed through this week, in order (its own last), and the day it entered each. */
    var path = IntArray(0)
    var days = FloatArray(0)
    /** Meat it got this week from herds it met on the way, kg. */
    var meatMet = 0f
    /** For a hunter: the hex at the heart of the ground it holds, -1 while it holds none. */
    var home = -1
    /** For a herd: the place it remembers doing best in each month, and how well (0..1). */
    val seasonPlace = IntArray(12) { -1 }
    val seasonQ = FloatArray(12)
    /** Lineage tag for learning, -1 for none. */
    var tag = -1
    val dailyNeedKg get() = species.dailyIntakeKg * (adults + 0.5f * young)
}

/**
 * The migratory herds and the hunters on the hexes, as groups. Each week every group looks at the
 * places it can reach for the effort it is willing to spend (a few hexes when content, a forced
 * march when hungry, cold or on its season's trek), over the borders as its way of travelling
 * allows, and goes to the best by its own lights; the hexes it passes through are where it may
 * meet others. Herds graze where they end up; hunters hunt the resident herds of the places they
 * pass and the herds they meet; packs hold ground and meet rivals. Young are born in season, the
 * hungry and the old die, and groups too big for their food split.
 */
/**
 * How a group of a kind chooses where to go this week, among the places it can reach: each
 * candidate described by [HexFeatures] (in [features]), with the hand-written score for it in
 * [baseline]. Returns the index of the chosen candidate.
 */
fun interface HexPolicy {
    fun choose(g: HexGroup, features: Array<FloatArray>, baseline: FloatArray): Int
}

/**
 * What a group sees of a candidate place, as numbers for a learned policy: the food there and the
 * ground, crowding and danger, the weather now and coming, the snow, how far up or down it lies and
 * how much effort it takes to reach, whether it means staying, the pull of the season's remembered
 * place, and the group's own state and the time of year.
 */
object HexFeatures {
    const val SIZE = 19
}

class HexGroups(
    private val hw: HexWorld, private val land: HexLand, private val fauna: HexFauna, private val days: Int, seed: Long,
    /** The kinds that live as groups here (default: the migratory herds and the hunters other than people). */
    kindsHere: List<Species>? = null,
) {
    private val hex = hw.hex
    private val n = land.n
    private val rnd = Random(seed * 41 + 3)
    val groups = ArrayList<HexGroup>()
    private var nextId = 0
    /** The kinds that live as groups: the migratory herds and the hunters other than people. */
    val kinds = kindsHere ?: (Catalogue.herbivores.filter { it in Catalogue.migratory } + Catalogue.predators.filter { !it.human })
    /** Learned choosers by kind; kinds without one use the hand-written score. */
    val policies = HashMap<Species, HexPolicy>()
    /** Kills this week by prey kind; meetings this week by kind of meeting. */
    val kills = java.util.TreeMap<String, Int>()
    val meetings = java.util.TreeMap<String, Int>()
    val deaths = java.util.TreeMap<String, Int>()
    private val years = days / Date.DAYS_PER_YEAR.toFloat()
    /** Small game (rodents, hares, birds) in each place, kg per km²; carrion lying about, kg per km². */
    val smallGame = FloatArray(n) { p -> SMALL_GAME_K * land.herb[p] * 0.5f }
    val carrion = FloatArray(n)

    init {
        // Start each kind at its usual density over the land that suits it, in groups of its usual size.
        val area = land.areaKm2.sum()
        for (s in kinds) {
            val per = if (s.startKm2PerAnimal > 0f) s.startKm2PerAnimal else 50f
            val count = (area / per / s.groupSize).toInt()
            val weights = FloatArray(n) { p -> land.areaKm2[p] * fauna.suits(s, p) * fauna.climateSuits(s, p) * (if (s.carnivore) 1f else min(1f, foodFor(s, p) / 0.3f)) }
            val total = weights.sum()
            repeat(count) {
                var u = rnd.nextFloat() * total; var p = 0
                while (p < n - 1 && u > weights[p]) { u -= weights[p]; p++ }
                val g = HexGroup(nextId++, s, p, s.groupSize, max(0, s.groupSize / 3))
                g.fat = 0.5f * s.maxFatDays
                if (s.carnivore) g.home = hw.placeHex[p]
                groups += g
            }
        }
    }

    private fun travel(s: Species) = if (s in Routes.CLIMBERS) HexMap.Travel.CLIMB else if (!s.carnivore && s.massKg > 60f) HexMap.Travel.SWIM else HexMap.Travel.FOOT

    private fun foodFor(s: Species, p: Int): Float {
        val d = s.diet
        val total = d.grass + d.browse + d.fruit + d.roots
        if (total <= 0f) return 0f
        return (d.grass * land.herb[p] + d.browse * (land.shrub[p] + HexFauna.BROWSE_OF_TREE * land.tree[p]) + (d.fruit + d.roots) * (HexFauna.FRUIT_OF_TREE * land.tree[p] + 0.3f * land.herb[p])) / total
    }

    fun count(s: Species) = groups.filter { it.species == s && it.alive }.sumOf { it.size }

    fun step(weather: Weather, date: Date) {
        kills.clear(); meetings.clear(); deaths.clear()
        val bySpeciesPlace = HashMap<Long, Int>()
        for (g in groups) if (g.alive) bySpeciesPlace.merge(g.species.index.toLong() * n + g.place, g.size, Int::plus)
        val predatorKg = FloatArray(n)
        for (g in groups) if (g.alive && g.species.carnivore) predatorKg[g.place] += g.size * g.species.massKg
        // Plan and move, in parallel: each group's choice depends only on last week's world.
        val choice = IntArray(groups.size); val paths = arrayOfNulls<IntArray>(groups.size)
        java.util.stream.IntStream.range(0, groups.size).parallel().forEach { k ->
            val g = groups[k]
            if (!g.alive) return@forEach
            val (p, path) = plan(g, weather, date, bySpeciesPlace, predatorKg)
            choice[k] = p; paths[k] = path
        }
        for ((k, g) in groups.withIndex()) if (g.alive) { g.place = choice[k]; g.path = paths[k]!!; g.days = timetable(g); g.meatMet = 0f }
        // The week, meeting by meeting: where two groups' timetables put them in the same hex on the
        // same days, the earlier meetings are settled first, by each side's standing orders.
        val t0 = System.nanoTime()
        meet(weather)
        meetNs += System.nanoTime() - t0
        // Who passed through which hex this week.
        val visits = HashMap<Int, ArrayList<HexGroup>>()
        for (g in groups) if (g.alive) for (h in g.path) visits.getOrPut(h) { ArrayList() } += g
        graze(weather)
        stepSmallGame()
        hunt(visits)
        settle()
        demography(weather, date)
        groups.removeIf { !it.alive }
        remember(date)
    }

    /**
     * Where [g] goes this week: the reachable place it likes best, and the hexes it passes on the way.
     * Reach is a few hexes when content; a forced march when hungry, cold, or heading for its season's
     * place. Hunters keep to their ground unless starving.
     */
    private fun plan(g: HexGroup, weather: Weather, date: Date, kin: HashMap<Long, Int>, predatorKg: FloatArray): Pair<Int, IntArray> {
        val s = g.species
        val here = hw.placeHex[g.place]
        // How far it will shift its ground this week. Most of a week's walking is within its range
        // (grazing, patrolling); the net shift is a couple of days' going when content, more on a trek
        // when hungry, cold or snowed in. A pack holding ground patrols it; a starving or homeless one roams.
        val restless = if (s.carnivore) g.fed < 0.5f || g.home < 0
            else g.fed < 0.7f || discomfort(s, g.place, weather) > 0.5f || weather.snowpack[g.place] > s.snowLimitMm
        val reach = (if (restless) s.maxDailyTravelKm else s.dailyTravelKm) * SHIFT_DAYS
        val cross = hex.crossings[travel(s).ordinal]
        // Least effort to each hex within reach.
        val dist = HashMap<Int, Float>(); val via = HashMap<Int, Int>()
        val q = PriorityQueue<Pair<Float, Int>>(compareBy { it.first })
        dist[here] = 0f; q += 0f to here
        while (q.isNotEmpty()) {
            val (d, h) = q.poll()
            if (d > (dist[h] ?: Float.MAX_VALUE)) continue
            for (k in 0 until 6) {
                val nh = hex.neighbour(h, k)
                if (nh < 0 || hex.heart[nh] < 0) continue
                val nd = d + cross[h * 6 + k]
                if (!nd.isFinite() || nd > reach) continue
                if (nd < (dist[nh] ?: Float.MAX_VALUE)) { dist[nh] = nd; via[nh] = h; q += nd to nh }
            }
        }
        val month = (date.month + if (date.dayOfMonth > Date.DAYS_PER_MONTH / 2) 1 else 0) % 12
        val goal = g.seasonPlace[month]
        var best = g.place; var bestScore = Float.NEGATIVE_INFINITY
        val policy = policies[s]
        val candPlaces = ArrayList<Int>(); val candFeats = ArrayList<FloatArray>(); val candScores = ArrayList<Float>()
        val season = 2 * Math.PI * date.dayOfYear / Date.DAYS_PER_YEAR
        for ((h, d) in dist) {
            // A pack on its own ground stays on it while fed.
            if (s.carnivore && g.home >= 0 && g.fed >= 0.5f && hexKm(h, g.home) > territoryKm(s)) continue
            for (b in 0 until HexWorld.BANDS_M.size) {
                val p = hw.placeOf[b][h]
                if (p < 0) continue
                var score = if (s.carnivore) {
                    val prey = preyHere(s, p) + herdPrey(s, p, kin)
                    prey / (prey + PREY_HALF) - 0.5f * crowd(s, p, kin, g)
                } else {
                    val food = foodFor(s, p) * fauna.suits(s, p)
                    food / (food + 0.3f) - 0.5f * crowd(s, p, kin, g) - DANGER * predatorKg[p] / land.areaKm2[p] / (predatorKg[p] / land.areaKm2[p] + 3f) * (1f - s.defence)
                }
                score -= 0.5f * discomfort(s, p, weather)
                if (weather.snowpack[p] > s.snowLimitMm) score -= 1f
                score -= 0.1f * d / (s.dailyTravelKm * days)
                if (goal >= 0 && !s.carnivore) {
                    val toGoal = hexKm(h, hw.placeHex[goal]); val fromHere = hexKm(here, hw.placeHex[goal])
                    if (fromHere > hex.hexKm) score += SEASON_PULL * (g.seasonQ[month] - g.fed).coerceIn(0f, 1f) * ((fromHere - toGoal) / fromHere).toFloat()
                }
                if (p == g.place) score += STAY
                if (policy != null) {
                    val f = FloatArray(HexFeatures.SIZE)
                    val food = foodFor(s, p)
                    f[0] = food / (food + 0.3f)
                    f[1] = kotlin.math.ln(fauna.suits(s, p).coerceAtLeast(1e-3f)) / 2f
                    f[2] = crowd(s, p, kin, g)
                    f[3] = predatorKg[p] / land.areaKm2[p] / (predatorKg[p] / land.areaKm2[p] + 3f)
                    f[4] = min(2f, discomfort(s, p, weather))
                    val coat = if (s.winterCoat) 10f else 0f
                    val tAhead = weather.forecastTemp(p)
                    f[5] = min(2f, (max(0f, s.comfortC.start - coat - tAhead) + max(0f, tAhead - s.comfortC.endInclusive)) / 10f)
                    f[6] = if (weather.snowpack[p] > s.snowLimitMm) 1f else 0f
                    f[7] = min(2f, weather.snowpack[p] / s.snowLimitMm)
                    f[8] = ((hw.placeElevationM[p] - hw.placeElevationM[g.place]) / 1000f).coerceIn(-3f, 3f)
                    f[9] = (d / reach.coerceAtLeast(1f)).coerceIn(0f, 1f)
                    f[10] = if (p == g.place) 1f else 0f
                    f[11] = if (h == here) 1f else 0f
                    if (goal >= 0) {
                        val toGoal = hexKm(h, hw.placeHex[goal]); val fromHere = hexKm(here, hw.placeHex[goal])
                        f[12] = if (fromHere > hex.hexKm) ((fromHere - toGoal) / fromHere).toFloat().coerceIn(-1f, 1f) else 0f
                        f[13] = (g.seasonQ[month] - g.fed).coerceIn(-1f, 1f)
                    }
                    f[14] = g.fed
                    f[15] = g.fat / s.maxFatDays.coerceAtLeast(1f)
                    f[16] = kotlin.math.sin(season).toFloat(); f[17] = kotlin.math.cos(season).toFloat()
                    f[18] = if (restless) 1f else 0f
                    candPlaces += p; candFeats += f; candScores += score
                    continue
                }
                score += 0.02f * (hashNoise(g.id, p) - 0.5f)
                if (score > bestScore) { bestScore = score; best = p }
            }
        }
        if (policy != null && candPlaces.isNotEmpty()) best = candPlaces[policy.choose(g, candFeats.toTypedArray(), candScores.toFloatArray())]
        // The hexes passed on the way, by the cheapest chain.
        val chain = ArrayList<Int>()
        var h = hw.placeHex[best]
        while (true) { chain += h; if (h == here) break; h = via[h] ?: break }
        chain.reverse()
        return best to chain.toIntArray()
    }

    private fun hexKm(a: Int, b: Int): Double { val (ax, ay) = hex.centreKm(a); val (bx, by) = hex.centreKm(b); return hypot(ax - bx, ay - by) }
    private fun territoryKm(s: Species) = kotlin.math.sqrt(max(1f, s.startKm2PerAnimal) * s.groupSize / Math.PI.toFloat()) + 5f

    private fun discomfort(s: Species, p: Int, weather: Weather): Float {
        val coat = if (s.winterCoat) 10f else 0f
        val t = weather.temperature[p]
        return (max(0f, s.comfortC.start - coat - t) + max(0f, t - s.comfortC.endInclusive)) / 10f
    }

    private fun crowd(s: Species, p: Int, kin: HashMap<Long, Int>, g: HexGroup): Float {
        val others = (kin[s.index.toLong() * n + p] ?: 0) - (if (p == g.place) g.size else 0)
        val usual = if (s.startKm2PerAnimal > 0f) land.areaKm2[p] / s.startKm2PerAnimal * 3f else 10f
        return others / (others + usual + 1f)
    }

    /** Resident prey a hunter of kind [s] can take in place [p], kg of meat per km², weighed by how catchable. */
    private fun preyHere(s: Species, p: Int): Float {
        var v = 0f
        for ((k, ps) in fauna.kinds.withIndex()) v += (fauna.adults[k][p] + 0.5f * fauna.young[k][p]) * ps.massKg * catchable(s, ps, p)
        return v
    }

    private fun herdPrey(s: Species, p: Int, kin: HashMap<Long, Int>): Float {
        var v = 0f
        for (ps in kinds) if (!ps.carnivore) v += (kin[ps.index.toLong() * n + p] ?: 0) * ps.massKg * catchable(s, ps, p) / land.areaKm2[p]
        return v
    }

    /** How catchable prey [ps] is for hunter [s] in place [p], 0..1: size against the pack, defence, and refuge in broken ground. */
    private fun catchable(s: Species, ps: Species, p: Int): Float {
        val pack = s.groupSize * s.massKg
        val size = min(1f, pack / (1.5f * ps.massKg))
        val small = if (ps.massKg < s.massKg * 0.05f) 0.3f else 1f // too small to be worth a pack's while
        val refuge = 1f - 0.8f * max(0f, ps.habitat.rugged) * land.ruggedness[p]
        return size * small * (1f - ps.defence) * refuge
    }

    /** Herds eat in the place they end up in, after the resident herds have had theirs. */
    private fun graze(weather: Weather) {
        for (g in groups) {
            val s = g.species
            if (!g.alive || s.carnivore) continue
            val p = g.place
            val want = g.dailyNeedKg * days / 1e6f / land.areaKm2[p] // kg per m² of the place
            val snowed = weather.snowpack[p] > s.snowLimitMm
            val d = s.diet; val total = (d.grass + d.browse + d.fruit + d.roots).coerceAtLeast(1e-6f)
            val reach = if (weather.temperature[p] > 4f) HexFauna.REACH else HexFauna.REACH_DORMANT
            val grass = if (snowed) 0f else min(want * d.grass / total, reach * land.herb[p])
            val browse = min(want * d.browse / total, reach * land.shrub[p])
            land.consume(p, LifeForm.HERB, grass); land.consume(p, LifeForm.SHRUB, browse)
            val got = grass + browse + want * (d.fruit + d.roots) / total * min(1f, foodFor(s, p) / 0.3f)
            g.fed = (got / want.coerceAtLeast(1e-9f)).coerceIn(0f, 1f)
            land.grazing[p] += grass * 1e6f / days; land.browsing[p] += browse * 1e6f / days
        }
    }

    /**
     * Hunters hunt along their way: the resident herds of every place they passed, and the herds they
     * met (that passed the same hexes this week). Each kill feeds the pack; the hunt stops when it has
     * eaten its fill or its chances are spent.
     */
    private fun hunt(visits: HashMap<Int, ArrayList<HexGroup>>) {
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
            val small = g.dailyNeedKg * days * s.diet.smallPrey
            val scraps = g.dailyNeedKg * days * s.diet.carrion
            var gotSmall = 0f; var gotScraps = 0f
            for (h in g.path) {
                val p = hw.placeOf[hw.placeBand[g.place]][h].takeIf { it >= 0 } ?: continue
                val swept = min(land.areaKm2[p], SWEEP_KM2 / g.path.size.coerceAtLeast(1))
                val ts = min(small - gotSmall, smallGame[p] * swept * SMALL_TAKE).coerceAtLeast(0f)
                smallGame[p] -= ts / swept; gotSmall += ts
                val tc = min(scraps - gotScraps, carrion[p] * swept * 0.5f).coerceAtLeast(0f)
                carrion[p] -= tc / swept; gotScraps += tc
            }
            val wantAll = g.dailyNeedKg * days * (s.diet.meat + s.diet.smallPrey + s.diet.carrion).coerceAtLeast(1e-6f)
            g.fed = ((min(got, need) + gotSmall + gotScraps) / wantAll).coerceIn(0f, 1f)
            // What the pack could not finish is left for the scavengers.
            val p = g.place
            carrion[p] += max(0f, got - need) * 0.5f / land.areaKm2[p]
        }
    }

    /** Small game breeds towards what the grass carries; carcasses rot or are picked clean within weeks. */
    private fun stepSmallGame() {
        for (p in 0 until n) {
            val k = SMALL_GAME_K * land.herb[p]
            smallGame[p] += SMALL_GAME_GROWTH * years * smallGame[p] * (1f - smallGame[p] / k.coerceAtLeast(1e-3f)) + 0.01f
            smallGame[p] = smallGame[p].coerceIn(0f, max(k, 0f) * 1.2f)
            carrion[p] = carrion[p] * CARRION_KEEP + fauna.carcassKg[p] / land.areaKm2[p]
        }
    }

    /** Time spent settling meetings, ns, over the run (for measuring the cost). */
    var meetNs = 0L

    /** The day [g] enters each hex of its path: effort so far over its pace, the first hex on day 0. */
    private fun timetable(g: HexGroup): FloatArray {
        val s = g.species
        val out = FloatArray(g.path.size)
        if (g.path.size <= 1) return out
        val cross = hex.crossings[travel(s).ordinal]
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

    private class Stay(val k: Int, val index: Int, val from: Float, val to: Float)
    private class Meeting(val day: Float, val a: Int, val ai: Int, val b: Int, val bi: Int)

    /**
     * Finds where groups' timetables overlap in a hex and settles the meetings in the order they
     * happen, each by the two sides' standing orders. A group whose week is cut short (turned
     * aside, scattered, driven off, stopping to eat a kill) ends it where that happened, and its
     * later meetings do not take place. Herds meet hunters, and hunters of a kind meet each other;
     * herds of different kinds pass each other by. After [MAX_MEETINGS] meetings a group's week
     * runs on without more.
     */
    private fun meet(weather: Weather) {
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
    private fun openness(h: Int, band: Int): Float {
        val p = hw.placeOf[band][h].takeIf { it >= 0 } ?: return 0.5f
        return 1f - min(1f, land.shrub[p] / 2f + land.canopy(p))
    }

    /**
     * Standing orders, hunter and herd. The herd watches for hunters: in open country it often sees
     * them first and turns aside, ending its week short of that hex. Otherwise there is a chase,
     * likelier to end in a kill if the hunters came on it unseen; a herd that was chased scatters and
     * stops where it is; hunters that killed stop there to eat.
     */
    private fun hunterMeetsHerd(hunter: HexGroup, hk: Int, hi: Int, herd: HexGroup, rk: Int, ri: Int, h: Int, stop: IntArray) {
        meetings.merge("hunter-herd", 1, Int::plus)
        val open = openness(h, hw.placeBand[herd.place])
        val seen = rnd.nextFloat() < VIGILANCE * open * (1f - hunter.species.stalking)
        if (seen) {
            meetings.merge("herd turned aside", 1, Int::plus)
            stop[rk] = min(stop[rk], max(0, ri - 1))
            return
        }
        // A hunter that has eaten its fill this week lets the herd pass.
        val need = hunter.dailyNeedKg * days * hunter.species.diet.meat
        if (hunter.meatMet >= need) return
        val p = hw.placeOf[hw.placeBand[herd.place]][h].takeIf { it >= 0 } ?: herd.place
        val chance = CATCH * SURPRISE * catchable(hunter.species, herd.species, p)
        stop[rk] = min(stop[rk], ri) // scattered
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
    private fun rivalsMeet(a: HexGroup, ak: Int, ai: Int, b: HexGroup, bk: Int, bi: Int, h: Int, stop: IntArray) {
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
    private fun settle() {
        val held = groups.filter { it.alive && it.species.carnivore && it.home >= 0 }.map { it.species.index.toLong() * hex.size + it.home }.toHashSet()
        for (g in groups) if (g.alive && g.species.carnivore && g.home < 0) {
            val h = hw.placeHex[g.place]
            if (g.species.index.toLong() * hex.size + h !in held) { g.home = h; held += g.species.index.toLong() * hex.size + h }
        }
    }

    private fun demography(weather: Weather, date: Date) {
        val kinNow = HashMap<Long, Int>()
        for (g in groups) if (g.alive) kinNow.merge(g.species.index.toLong() * hex.size + hw.placeHex[g.place], g.size, Int::plus)
        val born = ArrayList<HexGroup>()
        for (g in groups) {
            if (!g.alive) continue
            val s = g.species
            // Reserves carry a group through lean weeks.
            if (g.fed < 1f) { val draw = min(g.fat, (1f - g.fed) * days); g.fat -= draw; g.fed = min(1f, g.fed + draw / days) }
            else g.fat = min(s.maxFatDays, g.fat + 0.3f * days)
            val inSeason = (date.month - s.birthMonth + 12) % 12 < s.birthMonths
            if (inSeason && g.adults >= 2) {
                val births = if (s.packLitter > 0) {
                    if (date.dayOfMonth <= days && date.month == s.birthMonth && g.home >= 0) (s.packLitter * g.fed + rnd.nextFloat()).toInt() else 0
                } else {
                    val stepsInSeason = s.birthMonths * Date.DAYS_PER_MONTH / days.toFloat()
                    binomial(g.adults / 2, s.youngPerFemale * s.littersPerYear * g.fed / stepsInSeason)
                }
                g.young += births
            }
            val hunger = if (g.fed < 0.5f) (0.5f - g.fed) * 0.3f else 0f
            val cold = discomfort(s, g.place, weather) * 0.02f
            // Crowded herds catch each other's diseases and parasites.
            val usual = if (s.startKm2PerAnimal > 0f) 1f / s.startKm2PerAnimal else 0.1f
            val crowd = (kinNow[s.index.toLong() * hex.size + hw.placeHex[g.place]] ?: 0) / (hex.hexKm * hex.hexKm * 0.87).toFloat() / (HexFauna.CROWDED * usual)
            val disease = if (s.carnivore) 0f else HexFauna.DISEASE * crowd * crowd * years
            val pa = (s.backgroundMortality * years + hunger + cold + disease).coerceIn(0f, 0.9f)
            val py = (s.youngMortality * years + 1.5f * (hunger + cold) + disease).coerceIn(0f, 0.95f)
            val da = binomial(g.adults, pa); val dy = binomial(g.young, py)
            g.adults -= da; g.young -= dy
            if (da + dy > 0) deaths.merge("${s.name}:${if (hunger > cold) "hunger" else "other"}", da + dy, Int::plus)
            val up = binomial(g.young, years / s.yearsToMaturity)
            g.young -= up; g.adults += up
            if (g.adults == 0) { g.young = 0; continue }
            if (g.size > s.maxGroupSize) {
                val c = HexGroup(nextId++, s, g.place, g.adults / 2, g.young / 2)
                c.tag = g.tag
                g.adults -= c.adults; g.young -= c.young
                c.fed = g.fed; c.fat = g.fat; c.tag = g.tag
                g.seasonPlace.copyInto(c.seasonPlace); g.seasonQ.copyInto(c.seasonQ)
                born += c // a young pack has no ground yet
            }
        }
        groups += born
        // Small herds of a kind that end the week in the same place join up, as far as the herd can grow.
        val byPlace = HashMap<Long, HexGroup>()
        for (g in groups) {
            val s = g.species
            if (!g.alive || s.carnivore) continue
            val key = (s.index.toLong() * n + g.place) * 1000 + (g.tag + 1)
            val other = byPlace[key]
            if (other != null && other.size + g.size <= s.maxGroupSize) {
                val share = g.size.toFloat() / (other.size + g.size)
                other.fed += (g.fed - other.fed) * share; other.fat += (g.fat - other.fat) * share
                other.adults += g.adults; other.young += g.young
                g.adults = 0; g.young = 0
            } else byPlace[key] = g
        }
    }

    /** Herds remember the place where they did best in each month. */
    private fun remember(date: Date) {
        val m = date.month
        for (g in groups) {
            if (g.species.carnivore) continue
            if (g.seasonPlace[m] < 0 || g.fed > g.seasonQ[m] + 0.05f) { g.seasonPlace[m] = g.place; g.seasonQ[m] = g.fed }
            else if (g.seasonPlace[m] == g.place) g.seasonQ[m] += 0.3f * (g.fed - g.seasonQ[m])
        }
    }

    private fun poisson(mean: Float): Int {
        if (mean <= 0f) return 0
        if (mean > 30f) return (mean + kotlin.math.sqrt(mean) * gaussian()).toInt().coerceAtLeast(0)
        val l = exp(-mean.toDouble()); var k = 0; var pr = 1.0
        do { k++; pr *= rnd.nextDouble() } while (pr > l)
        return k - 1
    }

    private fun binomial(n: Int, p: Float): Int {
        if (n <= 0 || p <= 0f) return 0
        if (p >= 1f) return n
        if (n > 50) return (n * p + kotlin.math.sqrt(n * p * (1 - p)) * gaussian()).toInt().coerceIn(0, n)
        var k = 0; repeat(n) { if (rnd.nextFloat() < p) k++ }; return k
    }

    private fun gaussian(): Float {
        val u = max(rnd.nextDouble(), 1e-12)
        return (kotlin.math.sqrt(-2 * kotlin.math.ln(u)) * kotlin.math.cos(2 * Math.PI * rnd.nextDouble())).toFloat()
    }

    private fun hashNoise(id: Int, p: Int): Float {
        var h = id * 0x9E3779B97F4A7C15uL.toLong() + p * 0x632BE59BD9B4E019L
        h = (h xor (h ushr 33)) * -0xae502812aa7333L
        h = h xor (h ushr 33)
        return (h ushr 40).toFloat() / (1L shl 24).toFloat()
    }

    companion object {
        /** Share of its fastest daily travel a group keeps up over a week of forced march. */
        const val MARCH_SHARE = 0.6f
        /** Days of going a week's net shift amounts to, at the usual pace or a trek's. */
        const val SHIFT_DAYS = 2f
        /** Prey, kg per km² weighed by catchability, at which a place is half as good as it gets for a hunter. */
        const val PREY_HALF = 200f
        /** How much herds shun hunters' ground. */
        const val DANGER = 1.5f
        /** Pull of the season's remembered place, and of staying where one is. */
        const val SEASON_PULL = 1.0f
        const val STAY = 0.15f
        /** Most chases a hunter makes in a week; chance a chase at a fully catchable quarry succeeds. */
        const val MAX_CHASES = 12
        const val CATCH = 0.35f
        /** Ground a hunting party sweeps in a week, km² (shared over the hexes it passes). */
        const val SWEEP_KM2 = 40f
        /** Small game: kg per km² the grass carries per kg/m² of herbs; growth per year; share of what a hunter's sweep turns up that it catches. */
        const val SMALL_GAME_K = 120f
        const val SMALL_GAME_GROWTH = 4f
        const val SMALL_TAKE = 0.3f
        /** Share of carrion left after a week. */
        const val CARRION_KEEP = 0.6f
        /** Share of a kill's weight that is eaten. */
        const val MEAT_SHARE = 0.6f
        const val HOME_ADVANTAGE = 1.5f
        const val RIVAL_KILL = 0.05f
        /** Most meetings settled one by one for a group in a week. */
        const val MAX_MEETINGS = 4
        /** Chance a herd in fully open country sees coursing hunters first; how much likelier a kill when hunters come unseen. */
        const val VIGILANCE = 0.6f
        const val SURPRISE = 1.5f
        /** Strength ratio beyond which the weaker pack gives way without a fight. */
        const val FIGHT_RATIO = 1.5f
    }
}
