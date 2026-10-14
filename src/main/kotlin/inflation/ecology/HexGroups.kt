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

/**
 * The migratory herds and the hunters on the hexes, as groups. Each week every group looks at the
 * places it can reach for the effort it is willing to spend (a few hexes when content, a forced
 * march when hungry, cold or on its season's trek), over the borders as its way of travelling
 * allows, and goes to the best by its own lights; the hexes it passes through are where it may
 * meet others. Herds graze where they end up; hunters hunt the resident herds of the places they
 * pass and the herds they meet; packs hold ground and meet rivals. Young are born in season, the
 * hungry and the old die, and groups too big for their food split.
 */
class HexGroups(
    internal val hw: HexWorld, internal val land: HexLand, internal val fauna: HexFauna, internal val days: Int, seed: Long,
    /** The kinds that live as groups here (default: the migratory herds and the hunters other than people). */
    kindsHere: List<Species>? = null,
    /** Start every kind spread evenly over the land, wherever it suits it or not (to see them find their country). */
    uniformStart: Boolean = false,
) {
    internal val hex = hw.hex
    internal val n = land.n
    internal val rnd = Random(seed * 41 + 3)
    internal val seedBase = seed
    val groups = ArrayList<HexGroup>()
    internal var nextId = 0
    /** The kinds that live as groups: the migratory herds and the hunters other than people. */
    /** For comparison runs, -Dhex.noPeople leaves people out. */
    val kinds = kindsHere ?: (Catalogue.herbivores.filter { it in Catalogue.migratory } + Catalogue.predators.filter { !it.human || System.getProperty("hex.noPeople") == null })
    /** Each kind's sense of where better country lies: food or prey, its ground, water, and the climate this month. */
    val bearings by lazy {
        HexBearings(hw, kinds, { s, p, m ->
            val fit = fauna.monthFit(s, p, m)
            if (s.carnivore) {
                // The game it can catch there, resident herds and migratory ones alike, by weight.
                val prey = huntable[kindSlot[s.index]][p] + if (s.human) (fauna.food.fruit[p] + fauna.food.roots[p] + fauna.food.seeds[p]) * 1e6f * PLANT_FOOD_WEIGHT else 0f
                // Hunters that live on the water in their prey (cheetahs, leopards) care less how far it is.
                val w = land.waterKm[p] / (2f * s.dailyTravelKm) * (if (s.waterFromFood >= 0.8f) 0.3f else 1f)
                prey / (prey + PREY_HALF) * fit / (1f + w * w)
            } else {
                val food = foodFor(s, p) * min(1.5f, fauna.suits(s, p)) / 1.5f
                // Country thick with hunters is the less good for it.
                val hunters = monthHunterKg[p] / land.areaKm2[p]
                food / (food + 0.3f) * fauna.tether(s, p) * fit * (1f - 0.5f * hunters / (hunters + HUNTERS_HALF_KG))
            }
        }, { s -> groups.any { it.alive && it.species == s } })
    }
    /** Game each hunting kind can catch in each place, kg/km² weighed by catchability, worked out once a month for the bearings. */
    internal val huntable by lazy { Array(kinds.size) { FloatArray(n) } }
    /** Resident game in each place, kg/km², worked out once a month for the bearings. */
    internal val residentKg = FloatArray(n)
    internal fun residentKg(p: Int) = residentKg[p]
    private val monthHeads by lazy { Array(kinds.size) { IntArray(n) } }
    private val monthHunterKg by lazy { FloatArray(n) }
    internal val kindIndex by lazy { kinds.withIndex().associate { it.value to it.index } }
    /** Which pack of a kind holds each hex as its ground (key: kind × hexes + hex). */
    /**
     * Scent marks of each hunting kind on each hex: how fresh (0..1, fading week by week unless
     * renewed) and whose. A pack marks the ground it works; others of its kind keep off fresh marks.
     */
    internal val marks = HashMap<Int, FloatArray>()
    internal val markers = HashMap<Int, IntArray>()

    /** Freshness of marks other than [id]'s of kind [s] on hex [h]. */
    internal fun foreignMark(s: Species, h: Int, id: Int): Float {
        val m = marks[s.index] ?: return 0f
        return if (markers.getValue(s.index)[h] == id) 0f else m[h]
    }
    /** Learned choosers by kind; kinds without one use the hand-written score. */
    val policies = HashMap<Species, HexPolicy>()
    /** How bands set their ways each week; null for the scaffold's. */
    var ways: BandWays? = null
    /** Kills this week by prey kind; meetings this week by kind of meeting. */
    // (Concurrent and sorted: hunting runs region by region on all cores.)
    val kills = java.util.concurrent.ConcurrentSkipListMap<String, Int>()
    val meetings = java.util.concurrent.ConcurrentSkipListMap<String, Int>()
    val deaths = java.util.concurrent.ConcurrentSkipListMap<String, Int>()
    /** This week's hunting by kind of hunter: chases, kills from herds and of residents, herds that saw it first. */
    val hunting = java.util.concurrent.ConcurrentSkipListMap<String, Int>()
    /** Young born this week by kind. */
    val births = java.util.TreeMap<String, Int>()
    internal val years = days / Date.DAYS_PER_YEAR.toFloat()
    internal val small get() = fauna.small

    init {
        // Start each kind at its usual density over the land that suits it, in groups of its usual size.
        val area = land.areaKm2.sum()
        for (s in kinds) {
            val per = if (s.startKm2PerAnimal > 0f) s.startKm2PerAnimal else 50f
            // At the density a long run showed it settles at, where there is one.
            val settled = Catalogue.settledPer1000Km2(s)?.takeIf { HexFauna.USE_SETTLED }
            val unit = unitSize(s)
            val count = if (settled != null) max(if (settled > 0f) 1 else 0, (settled * area / 1000f / unit).toInt()) else (area / per / unit).toInt()
            val weights = if (uniformStart) FloatArray(n) { p -> land.areaKm2[p] } else FloatArray(n) { p -> land.areaKm2[p] * fauna.suits(s, p) * fauna.climateSuits(s, p) * (if (s.carnivore) 1f else min(1f, foodFor(s, p) / 0.3f)) }
            val total = weights.sum()
            repeat(count) {
                var u = rnd.nextFloat() * total; var p = 0
                while (p < n - 1 && u > weights[p]) { u -= weights[p]; p++ }
                val g = HexGroup(nextId++, s, p, unit, max(0, unit / 3))
                g.fat = 0.5f * s.maxFatDays
                if (s.human) {
                    g.larder = START_LARDER_DAYS * g.dailyNeedKg
                    val (x, y) = hex.centreKm(hw.placeHex[p])
                    g.band = Band.start(land.waterKm[p] < 3f, x, y).also { b ->
                        b.elders = g.adults / 6
                        // The rest of the adults spread over the three spans of adult life.
                        b.ageStage[0] = (g.adults - b.elders) / 3; b.ageStage[1] = (g.adults - b.elders) / 3
                        // It knows the country within a few hexes of its first camp.
                        var ring = listOf(hw.placeHex[p]); val seen = hashSetOf(ring[0])
                        repeat(KNOWN_AT_START) { val next = ArrayList<Int>(); for (h in ring) for (d in 0 until 6) { val q = hex.neighbour(h, d); if (q >= 0 && seen.add(q)) next += q }; ring = next }
                        for (h in seen) b.learn(h, hex.size)
                    }
                }
                if (s.carnivore && !s.human) g.home = hw.placeHex[p] else inheritSeasons(g)
                groups += g
            }
        }
    }

    internal fun foodFor(s: Species, p: Int) = fauna.foodFor(s, p)

    fun count(s: Species) = groups.filter { it.species == s && it.alive }.sumOf { it.size }

    /** This week's weather (for sight). */
    internal lateinit var weatherNow: Weather

    /** The group being followed (in the viewer), -1 for none; what happened to it this week, in words. */
    @Volatile var watched = -1
    val watchLog = java.util.concurrent.ConcurrentLinkedQueue<String>()
    internal inline fun note(g: HexGroup, msg: () -> String) { if (g.id == watched) watchLog += msg() }

    fun step(weather: Weather, date: Date) {
        kills.clear(); meetings.clear(); deaths.clear(); births.clear(); hunting.clear(); watchLog.clear()
        weatherNow = weather
        moon = date.meanMoonlight(days).toFloat()
        if (bearings.month != date.month) {
            // Head counts of the migratory herds in each place, for the hunters' sense of where game is.
            for (a in monthHeads) a.fill(0)
            for (g in groups) if (g.alive) monthHeads[kindSlot[g.species.index]][g.place] += g.size
            monthHunterKg.fill(0f)
            for (g in groups) if (g.alive && g.species.carnivore) monthHunterKg[g.place] += g.size * g.species.massKg
            java.util.stream.IntStream.range(0, n).parallel().forEach { p ->
                var v = 0f
                for ((k, ps) in fauna.kinds.withIndex()) v += (fauna.adults[k][p] + 0.5f * fauna.young[k][p]) * ps.massKg
                residentKg[p] = v
                for ((k, s) in kinds.withIndex()) {
                    if (!s.carnivore) continue
                    var h = preyHere(s, p)
                    // Herds on the move watch and are hard to come at: worth a share of their weight in game.
                    for ((j, ps) in kinds.withIndex()) if (!ps.carnivore && monthHeads[j][p] > 0) h += HERD_GAME * monthHeads[j][p] * ps.massKg * catchable(s, ps, p) / land.areaKm2[p]
                    huntable[k][p] = if (s.human) h else dietValue(s, p, h)
                }
            }
            Profile.time("sense of direction") { bearings.update(date.month) }
        }
        val predatorKg = FloatArray(n)
        for (g in groups) if (g.alive && g.species.carnivore) predatorKg[g.place] += g.size * g.species.massKg
        // The strongest hunting party in each place, and its kind: weaker hunters keep out of its way.
        topStrength.fill(0f); topKind.fill(-1)
        for (g in groups) if (g.alive && g.species.carnivore) {
            val st = strength(g)
            if (st > topStrength[g.place]) { topStrength[g.place] = st; topKind[g.place] = g.species.index }
        }
        // Plan and move, in parallel: each group's choice depends only on last week's world.
        val tPlan = System.nanoTime(); val cPlan = Profile.cpuNow()
        Profile.part("choosing: valuing places for hunters") { valuePlaces() }
        reachCache.clear()
        val choice = IntArray(groups.size); val paths = arrayOfNulls<IntArray>(groups.size); val times = arrayOfNulls<FloatArray>(groups.size)
        java.util.stream.IntStream.range(0, groups.size).parallel().forEach { k ->
            val g = groups[k]
            if (!g.alive) return@forEach
            val (p, path) = plan(g, weather, date, predatorKg)
            choice[k] = p; paths[k] = path; times[k] = Profile.part("choosing: timetable") { timetable(g.species, path) }
        }
        for ((k, g) in groups.withIndex()) if (g.alive) {
            if (g.band != null && g.fed < 0.5f) meetings.merge(if (paths[k]!!.size <= 1) "hungry bands chose to stay" else "hungry bands chose to move", 1, Int::plus)
            g.place = choice[k]; g.path = paths[k]!!; g.days = times[k]!!; g.meatMet = 0f
        }
        Profile.add("groups: choosing where to go", System.nanoTime() - tPlan, Profile.cpuNow() - cPlan)
        // The week, meeting by meeting: where two groups' timetables put them in the same hex on the
        // same days, the earlier meetings are settled first, by each side's standing orders.
        val t0 = System.nanoTime()
        Profile.time("groups: meetings") { meet(weather) }
        meetNs += System.nanoTime() - t0
        // Who passed through which hex this week.
        val visits = HashMap<Int, ArrayList<HexGroup>>()
        for (g in groups) if (g.alive) for (h in g.path) visits.getOrPut(h) { ArrayList() } += g
        Profile.time("groups: grazing") { graze(weather) }
        Profile.time("groups: hunting") { hunt(visits, weather) }
        Profile.part("people: burning off") { burnOff(weather) }
        Profile.part("people: visiting kin") { visitKin() }
        Profile.time("groups: ground, births, deaths") { Profile.part("hunters: marking ground") { mark() }; Profile.part("hunters: settling ground") { settle() }; demography(weather, date) }
        groups.removeIf { !it.alive }
        Profile.part("herds: remembering places") { remember(date) }
    }

    /**
     * Where [g] goes this week: the reachable place it likes best, and the hexes it passes on the way.
     * Reach is a few hexes when content; a forced march when hungry, cold, or heading for its season's
     * place. Hunters keep to their ground unless starving.
     */
    /** Per thread: least effort to each hex, the hex it was reached from, the hexes reached, the queue. */
    private class Scratch(size: Int) {
        val dist = FloatArray(size) { Float.MAX_VALUE }; val via = IntArray(size) { -1 }
        val reached = ArrayList<Int>(); val queue = PriorityQueue<Long>()
    }
    private val scratch = ThreadLocal.withInitial { Scratch(hex.size) }
    /** This week's searches, shared by the groups of a kind starting from the same place in the same mood. */
    private class Reach(val hexes: IntArray, val dist: FloatArray, val via: IntArray)
    private val reachCache = java.util.concurrent.ConcurrentHashMap<Long, Reach>()
    /** Effort and hex packed in one number, ordered by effort (efforts are never negative). */
    private fun Long.withPlace(d: Float, h: Int): Long = (d.toRawBits().toLong() shl 32) or h.toLong()

    /**
     * What each place offers each kind this week, worked out once for all its groups: for hunters the
     * prey they can catch there (resident and in herds), for herds the food as worth to them.
     */
    private val placeValue by lazy { Array(kinds.size) { FloatArray(n) } }

    /** Head count of each kind in each place this week, and each species' slot in [kinds]. */
    internal val headsHere by lazy { Array(kinds.size) { IntArray(n) } }
    private val peopleSlot by lazy { kinds.indexOfFirst { it.human } }
    internal val kindSlot by lazy { IntArray(Catalogue.all.size) { i -> kinds.indexOfFirst { it.index == i }.coerceAtLeast(0) } }

    private fun valuePlaces() {
        for (a in headsHere) a.fill(0)
        for (g in groups) if (g.alive) headsHere[kindSlot[g.species.index]][g.place] += g.size
        java.util.stream.IntStream.range(0, n).parallel().forEach { p ->
            for ((k, s) in kinds.withIndex()) placeValue[k][p] =
                if (s.human) campValue(s, p)
                else if (s.carnivore) dietValue(s, p, preyHere(s, p) + herdPrey(s, p))
                else foodFor(s, p) * land.nourishment(p) * fauna.suits(s, p) * (1f - land.burning[p])
        }
    }

    /**
     * What place [p] offers a band for a camp, as game-weight per km²: the game it can hunt there, and
     * the fruit, nuts and roots it can gather (counted at their weight against meat), the more so where
     * the camp can stand on high, broken or water-girt ground safe from lions and raiders.
     */
    internal fun campValue(s: Species, p: Int): Float {
        val game = preyHere(s, p) + herdPrey(s, p)
        val plants = (fauna.food.fruit[p] + fauna.food.roots[p] + fauna.food.seeds[p]) * 1e6f * PLANT_FOOD_WEIGHT
        // Water close to camp: women and children fetch it every day.
        val w = land.waterKm[p] / 3f
        return (game + plants) * (1f + CAMP_SAFETY * land.bestDefence[p] + CAVE_CAMP * land.caves[p]) / (1f + w * w)
    }

    /**
     * Bands burn off the dry grass around their camps in the dry season: the burn greens up and draws
     * game (and the fire may spread with the wind).
     */
    private fun burnOff(weather: Weather) {
        for (g in groups) {
            if (!g.alive || !g.species.human) continue
            val p = g.place
            val dry = land.soil[p] < DRY_SOIL_MM && weather.temperature[p] > 15f && land.green[p] < 0.4f
            if (dry && land.herb[p] > 0.3f && land.burnt[p] < 0.1f && rnd.nextFloat() < BURN_OFF) { land.ignite(p); meetings.merge("bands burned off grass", 1, Int::plus) }
        }
    }

    /** Packs of kinds that follow people, by the hex they end the week in (for taming). */
    internal var followersByHex: Map<Int, List<HexGroup>> = emptyMap()

    /**
     * How big a herd of [g]'s kind grows before it splits, now: herds gather on a green flush and
     * scatter in the lean season, and break up to calve; packs, prides and bands keep their kind's size.
     */
    internal fun maxSize(g: HexGroup, date: Date): Int {
        val s = g.species
        if (s.carnivore) return if (g.band?.holds(Tradition.FEASTS) == true) (s.maxGroupSize * FEAST_SIZE).toInt() else s.maxGroupSize
        val birth = if (g.birthMonth >= 0) g.birthMonth else s.birthMonth
        val calving = (date.month - birth + 12) % 12 < s.birthMonths
        val flush = 1f + AGGREGATION * land.green[g.place] * g.fed
        return max(unitSize(s), (s.maxGroupSize * HERD_UNIT * flush * (if (calving) 0.5f else 1f)).toInt())
    }

    /** A kind's usual group as an agent: herds move as units of [HERD_UNIT] of their usual bands. */
    internal fun unitSize(s: Species) = if (s.carnivore) s.groupSize else s.groupSize * HERD_UNIT

    /** Choices made in planning, by kind: kept to its ground without weighing (content), weighed and stayed, weighed and moved. */
    val planCounts = java.util.concurrent.atomic.AtomicIntegerArray(64 * 3)

    /** Desert kites in each place, 0..1 built: long low walls funnelling open-country herds into a pen or pits. They stand for generations. */
    val kites = FloatArray(n)

    /** Strongest hunting party in each place last week, and its kind's index (-1 for none). */
    private val topStrength = FloatArray(n)
    private val topKind = IntArray(n) { -1 }

    /** A hunting party's fighting strength: weight, ferocity. */
    internal fun strength(g: HexGroup) = g.adults * g.species.massKg * ferocity(g)
    internal fun ferocity(g: HexGroup) = g.band?.ferocity(g.species) ?: g.species.ferocity

    internal fun plan(g: HexGroup, weather: Weather, date: Date, predatorKg: FloatArray): Pair<Int, IntArray> {
        var pt = Profile.mark()
        val r = planInner(g, weather, date, predatorKg) { name -> pt = Profile.lap(name, pt) }
        Profile.lap("choosing: route", pt)
        return r
    }

    private fun planInner(g: HexGroup, weather: Weather, date: Date, predatorKg: FloatArray, lap: (String) -> Unit): Pair<Int, IntArray> {
        val s = g.species
        val here = hw.placeHex[g.place]
        // How far it will shift its ground this week. Most of a week's walking is within its range
        // (grazing, patrolling); the net shift is a couple of days' going when content, more on a trek
        // when hungry, cold or snowed in. A pack holding ground patrols it; a starving or homeless one roams.
        val thirsty = g.dry > 0.5f * s.daysWithoutWater
        // The place it remembers doing best at this time of year: while it is far off, the herd is on its way.
        val month = (date.month + if (date.dayOfMonth > Date.DAYS_PER_MONTH / 2) 1 else 0) % 12
        val goal = g.seasonPlace[month]
        val goalFar = goal >= 0 && (!s.carnivore || s.human) && hexKm(here, hw.placeHex[goal]) > 2 * hex.hexKm
        // Far out of its country, it treks.
        val astray = bearings.strength(kindIndex[s] ?: 0, here) > ASTRAY
        // (Bands hold no hunting ground of the packs' kind: a band is restless only when hungry, thirsty or astray.)
        val restless = if (s.human) g.fed < 0.5f || thirsty || astray
            else if (s.carnivore) g.fed < 0.5f || g.home < 0 || thirsty || astray
            else g.fed < 0.7f || discomfort(s, g.place, weather) > 0.5f || weather.snowpack[g.place] > s.snowLimitMm || thirsty || astray || goalFar
        // A group content where it is (fed, watered, comfortable, in its country) keeps to its ground
        // this week without weighing the alternatives: every other week, by turns.
        // (Herds content where they are weigh their places only one week in [CONTENT_WEEKS]: they stay while the grazing holds.)
        val every = if (s.carnivore) 2 else Tuning.contentWeeks
        if (!restless && g.fed >= CONTENT && (date.absoluteDay / days + g.id) % every != 0L) { planCounts.incrementAndGet(s.index * 3); return g.place to intArrayOf(here) }
        val reach = (if (restless) s.maxDailyTravelKm else s.dailyTravelKm) * SHIFT_DAYS
        val ki = kindIndex[s] ?: 0
        val cross = hex.crossings[Catalogue.travel(s).ordinal]
        // Least effort to each hex within reach.
        val sc = scratch.get()
        val dist = sc.dist; val via = sc.via; val reached = sc.reached
        for (h in reached) { dist[h] = Float.MAX_VALUE; via[h] = -1 }
        reached.clear()
        val q = sc.queue; q.clear()
        // Groups of a kind starting from the same place this week share the search (herds often do).
        val reachKey = ((s.index.toLong() * 8 + hw.placeBand[g.place]) * hex.size + here) * 2 + (if (restless) 1 else 0)
        val shared = reachCache[reachKey]
        if (shared != null) {
            for (i in shared.hexes.indices) { val h = shared.hexes[i]; reached += h; dist[h] = shared.dist[i]; via[h] = shared.via[i] }
        } else {
        dist[here] = 0f; reached += here; q += 0L.withPlace(0f, here)
        while (q.isNotEmpty()) {
            val e = q.poll()
            val d = Float.fromBits((e ushr 32).toInt()); val h = (e and 0xffffffffL).toInt()
            if (d > dist[h]) continue
            for (k in 0 until 6) {
                val nh = hex.neighbour(h, k)
                if (nh < 0 || hex.heart[nh] < 0) continue
                // Lying snow makes the going harder: about twice the effort in 15 cm, more in deeper.
                val np = hw.placeOf[hw.placeBand[g.place]][nh].takeIf { it >= 0 } ?: hex.heart[nh]
                val snowMm = if (np in 0 until n) weather.snowpack[np] else 0f
                val nd = d + cross[h * 6 + k] * (1f + snowMm / SNOW_EFFORT_MM)
                if (!nd.isFinite() || nd > reach) continue
                if (nd < dist[nh]) { if (dist[nh] == Float.MAX_VALUE) reached += nh; dist[nh] = nd; via[nh] = h; q += 0L.withPlace(nd, nh) }
            }
        }
        val hs = reached.toIntArray()
        reachCache[reachKey] = Reach(hs, FloatArray(hs.size) { dist[hs[it]] }, IntArray(hs.size) { via[hs[it]] })
        }
        lap("choosing: reach (least-effort search)")
        var best = g.place; var bestScore = Float.NEGATIVE_INFINITY
        val policy = policies[s]
        // (For a followed group: every place it weighed, and how it scored.)
        val seen = if (g.id == watched) ArrayList<Pair<Int, Float>>() else null
        val candPlaces = ArrayList<Int>(); val candFeats = ArrayList<FloatArray>(); val candScores = ArrayList<Float>()
        val season = 2 * Math.PI * (date.dayOfYear - Date.START_DAY) / Date.DAYS_PER_YEAR
        // Its ground is as big as the game on it needs it to be (smaller where game is thick), and a
        // hungry pack ranges wider: up to three times that after weeks of hunger.
        val homeValue = if (g.home >= 0) placeValue[ki][hw.placeOf[hw.placeBand[g.place]][g.home].takeIf { it >= 0 } ?: g.place] else PREY_HALF
        val territory = territoryKm(s) * kotlin.math.sqrt(PREY_HALF / homeValue.coerceAtLeast(1f)).coerceIn(0.5f, 3f) * (1f + min(2f, g.lean.toFloat() / GIVE_UP_WEEKS))
        val goalHex = if (goal >= 0) hw.placeHex[goal] else -1
        val fromHere = if (goal >= 0) hexKm(here, goalHex) else 0.0
        val pullKm = reach.toDouble().coerceAtLeast(hex.hexKm)
        for (h in reached) {
            val d = dist[h]
            // A pack on its own ground stays on it while fed; and keeps off ground another pack of its kind holds.
            if (s.carnivore && !s.human && g.home >= 0 && g.fed >= 0.5f && !astray && hexKm(h, g.home) > territory) continue
            if (s.carnivore && !s.human && g.fed >= 0.3f && h != g.home && foreignMark(s, h, g.id) > MARK_FRESH) continue
            val toGoal = if (goal >= 0) hexKm(h, goalHex) else 0.0
            // Better country further off: the way the kind's sense of the land points.
            val pull = bearings.along(ki, here, h, pullKm)
            for (b in 0 until HexWorld.BANDS_M.size) {
                val p = hw.placeOf[b][h]
                if (p < 0) continue
                var score = if (s.carnivore) {
                    val prey = placeValue[ki][p]
                    // Smaller hunters keep out of the way of much stronger ones of other kinds (they hear
                    // the roars and smell the marks): cheetahs and hyenas of lions, jackals of wolves.
                    val danger = if (topKind[p] >= 0 && topKind[p] != s.index && topStrength[p] > 2f * strength(g)) AVOID_STRONGER else 0f
                    prey / (prey + PREY_HALF) - 0.5f * crowd(s, p, g) - danger
                } else {
                    // At this scale every hex has its hunters: a herd does not shift its ground to get away
                    // from them (it watches and hides where it is), only for food, water, weather and the season.
                    val food = placeValue[ki][p]
                    // Ground thick with hunters is shunned, as far as the food elsewhere allows.
                    val hunters = predatorKg[p] / land.areaKm2[p]
                    // And ground where game was killed lately is shunned (the landscape of fear).
                    food / (food + 0.3f) - 0.5f * crowd(s, p, g) - SHUN_HUNTERS * hunters / (hunters + HUNTERS_HALF_KG) - HexFauna.FEAR_WEIGHT * fauna.afraid(p)
                }
                score -= 0.5f * discomfort(s, p, weather)
                // What it senses coming, as keenly as its kind does: a cold snap or heat wave, snow
                // too deep to dig through, and in a dry spell the rain on its way.
                val tAhead = weather.forecastTemp(p)
                score -= 0.5f * s.foresight * s.discomfortC(tAhead, date.month) / 10f
                if (tAhead < 0f && weather.snowpack[p] + weather.forecastRain(p) > s.snowLimitMm) score -= 0.5f * s.foresight
                if (land.soil[p] < DRY_SOIL_MM) score += 0.3f * s.foresight * min(1f, weather.forecastRain(p) / RAIN_AHEAD_MM)
                // Water: a thirsty group heads for it; any group keeps within reach of it, the more so the more often it must drink.
                score -= (0.02f + 0.3f * g.dry / s.daysWithoutWater) * land.waterKm[p] / s.dailyTravelKm
                if (thirsty && land.water[p] < 0.05f) score -= 1f
                if (weather.snowpack[p] > s.snowLimitMm) score -= 1f
                // The going costs food: a share of the week's need (see [walkCost]).
                score -= WALK_COST * d / days
                // Herds and bands remember where they did best each month and are drawn back there.
                if (goal >= 0 && (!s.carnivore || s.human)) {
                    // The season's place draws a herd on, the more so the better it promises and the worse it fares now.
                    // Bands that watch the sky keep their round on time.
                    val sky = if (g.band?.holds(Tradition.SKY_LORE) == true) 1.3f else 1f
                    if (fromHere > hex.hexKm) score += sky * SEASON_PULL * (0.3f * g.seasonQ[month] + (g.seasonQ[month] - g.fed).coerceIn(0f, 1f)) * ((fromHere - toGoal) / min(fromHere, reach.toDouble())).toFloat().coerceIn(-1f, 1f)
                }
                score += BEARING_PULL * pull
                if (p == g.place) score += STAY - (g.band?.let { stayCost(it) } ?: 0f)
                // Bold animals of kinds that follow people are drawn to their camps: refuse, scraps, warmth.
                if (TAMING && s.followsPeople && peopleSlot >= 0) score += CAMP_LURE * boldness(g) * min(1f, headsHere[peopleSlot][p] / 25f)
                // A band with food enough to feast others is drawn to where other bands are (gatherings).
                g.band?.let { b -> if (peopleSlot >= 0 && b.holds(Tradition.FEASTS) && g.larder > b.storeAim * g.dailyNeedKg) {
                    val others = headsHere[peopleSlot][p] - (if (p == g.place) g.size else 0)
                    if (others > 0) score += GATHER_PULL * min(1f, others / GATHER_HEADS)
                } }
                // Bands keep to country they know; what lies beyond they know only as a sense of better land.
                g.band?.let { b -> if (!b.knows(h)) score -= UNKNOWN_COUNTRY }
                // A hungry pack gives up ground it has hunted out lately and looks further afield.
                if (s.carnivore && g.fed < 0.5f && (h == here || h in g.recent)) score -= HUNGRY_LEAVE
                seen?.add(p to score)
                if (policy != null) {
                    val f = FloatArray(HexFeatures.SIZE)
                    // What the place offers it: plants for herds; for hunters and bands, what the baseline weighs (game, and for bands plant food and a safe camp).
                    if (s.carnivore) { val v = placeValue[ki][p]; f[0] = v / (v + PREY_HALF) } else { val food = foodFor(s, p); f[0] = food / (food + 0.3f) }
                    f[1] = kotlin.math.ln(fauna.suits(s, p).coerceAtLeast(1e-3f)) / 2f
                    f[2] = crowd(s, p, g)
                    f[3] = predatorKg[p] / land.areaKm2[p] / (predatorKg[p] / land.areaKm2[p] + 3f)
                    f[4] = min(2f, discomfort(s, p, weather))
                    f[5] = min(2f, s.discomfortC(weather.forecastTemp(p), date.month) / 10f)
                    f[6] = if (weather.snowpack[p] > s.snowLimitMm) 1f else 0f
                    f[7] = min(2f, weather.snowpack[p] / s.snowLimitMm)
                    f[8] = ((hw.placeElevationM[p] - hw.placeElevationM[g.place]) / 1000f).coerceIn(-3f, 3f)
                    f[9] = (d / reach.coerceAtLeast(1f)).coerceIn(0f, 1f)
                    f[10] = if (p == g.place) 1f else 0f
                    f[11] = if (h == here) 1f else 0f
                    if (goal >= 0) {
                        f[12] = if (fromHere > hex.hexKm) ((fromHere - toGoal) / fromHere).toFloat().coerceIn(-1f, 1f) else 0f
                        f[13] = (g.seasonQ[month] - g.fed).coerceIn(-1f, 1f)
                    }
                    f[14] = g.fed
                    f[15] = g.fat / s.maxFatDays.coerceAtLeast(1f)
                    f[16] = kotlin.math.sin(season).toFloat(); f[17] = kotlin.math.cos(season).toFloat()
                    f[18] = if (restless) 1f else 0f
                    f[19] = min(3f, land.waterKm[p] / s.dailyTravelKm)
                    f[20] = min(2f, g.dry / s.daysWithoutWater)
                    f[21] = pull
                    // The moon (night hunters see and are seen by it) and how far off its young are due.
                    f[22] = moon
                    val toBirth = 2.0 * Math.PI * (((if (g.birthMonth >= 0) g.birthMonth else s.birthMonth) - month + 12) % 12) / 12.0
                    f[23] = kotlin.math.sin(toBirth).toFloat(); f[24] = kotlin.math.cos(toBirth).toFloat()
                    candPlaces += p; candFeats += f; candScores += score
                    continue
                }
                score += 0.02f * (hashNoise(g.id, p) - 0.5f)
                if (score > bestScore) { bestScore = score; best = p }
            }
        }
        lap("choosing: weighing places (hand-written score)")
        if (policy != null && candPlaces.isNotEmpty()) best = candPlaces[policy.choose(g, candFeats.toTypedArray(), candScores.toFloatArray())]
        lap("choosing: network")
        if (seen != null) {
            fun where(p: Int) = "hex %d at %.0f m".format(hw.placeHex[p], hw.placeElevationM[p])
            val top = seen.sortedByDescending { it.second }.take(5)
            watchLog += "weighed %d places%s; went to %s (hand-written score %.2f%s)".format(seen.size, if (restless) ", restless" else "", where(best),
                seen.firstOrNull { it.first == best }?.second ?: 0f, if (policy != null) ", chosen by the network" else "")
            for ((p, sc) in top) watchLog += "   %s%s: %.2f".format(where(p), if (p == g.place) " (stay)" else "", sc)
        }
        // -Dhex.planDiag=kind: once a month, one herd's reasons, for the place it chose and the best-placed step toward its season's place.
        if (PLAN_DIAG == s.name && goal >= 0 && date.dayOfMonth <= days && g.id % 97 == 0) {
            fun parts(p: Int): String {
                val h = hw.placeHex[p]; val food = placeValue[ki][p]; val hunters = predatorKg[p] / land.areaKm2[p]
                val toGoal = hexKm(h, goalHex)
                val prog = if (fromHere > hex.hexKm) ((fromHere - toGoal) / min(fromHere, reach.toDouble())).toFloat().coerceIn(-1f, 1f) else 0f
                return "food %.2f crowd %.2f hunters %.2f cold/heat %.2f water %.2f going %.2f season %.2f bearing %.2f stay %.2f (%.0f km from goal)".format(
                    food / (food + 0.3f), -0.5f * crowd(s, p, g), -SHUN_HUNTERS * hunters / (hunters + HUNTERS_HALF_KG), -0.5f * discomfort(s, p, weather),
                    -(0.02f + 0.3f * g.dry / s.daysWithoutWater) * land.waterKm[p] / s.dailyTravelKm, -WALK_COST * dist[h] / days,
                    SEASON_PULL * (0.3f * g.seasonQ[month] + (g.seasonQ[month] - g.fed).coerceIn(0f, 1f)) * prog, BEARING_PULL * bearings.along(ki, here, h, pullKm),
                    if (p == g.place) STAY else 0f, toGoal)
            }
            val towards = reached.minByOrNull { hexKm(it, goalHex) }?.let { h -> hw.placeOf[hw.placeBand[g.place]][h].takeIf { it >= 0 } ?: hex.heart[h] }?.takeIf { it >= 0 }
            println("plan ${s.name} #${g.id} month ${date.month + 1}: chose " + parts(best) + (towards?.let { " | toward goal " + parts(it) } ?: ""))
        }
        // The hexes passed on the way, by the cheapest chain.
        val chain = ArrayList<Int>()
        var h = hw.placeHex[best]
        while (true) { chain += h; if (h == here) break; h = via[h].takeIf { it >= 0 } ?: break }
        chain.reverse()
        planCounts.incrementAndGet(s.index * 3 + (if (best == g.place) 1 else 2))
        return best to chain.toIntArray()
    }

    internal fun hexKm(a: Int, b: Int): Double = hex.distanceKm(a, b)
    internal fun territoryKm(s: Species) = kotlin.math.sqrt(max(1f, s.startKm2PerAnimal) * s.groupSize / Math.PI.toFloat()) + 5f

    internal fun discomfort(s: Species, p: Int, weather: Weather): Float {
        return s.discomfortC(weather.temperature[p], weather.date.month) / 10f
    }

    internal fun crowd(s: Species, p: Int, g: HexGroup): Float {
        val others = headsHere[kindSlot[s.index]][p] - (if (p == g.place) g.size else 0)
        // Heads of its kind a place usually holds: for herds, their usual density as they live gathered (as for disease).
        val usual = if (s.startKm2PerAnimal > 0f) land.areaKm2[p] / s.startKm2PerAnimal * 3f else land.areaKm2[p] * 0.1f * MIGRANTS_GATHER
        return others / (others + usual + 1f)
    }

    /** Resident prey a hunter of kind [s] can take in place [p], kg of meat per km², weighed by how catchable. */
    /**
     * What place [p] offers a hunter of kind [s] by its whole diet, kg/km²: the big game it can catch
     * ([game]), the small prey on the ground and the carrion lying there, each by its share of the
     * diet. A jackal looks for rodents and carrion, a lion for big game.
     */
    internal fun dietValue(s: Species, p: Int, game: Float): Float {
        // Small game is searched for over a narrower strip than big game, and carrion only by those that eat it.
        val smallWorth = smallCatch(s) * SMALL_SENSE_KM / (s.senseKm * SEARCHING * CATCH)
        return game + smallWorth * small.smallPreyKg(p) + (if (s.diet.carrion > 0f) small.carrion[p] * 1e6f else 0f)
    }

    internal fun preyHere(s: Species, p: Int): Float {
        var v = 0f
        for ((k, ps) in fauna.kinds.withIndex()) v += (fauna.adults[k][p] + 0.5f * fauna.young[k][p]) * ps.massKg * catchable(s, ps, p)
        return v
    }

    internal fun herdPrey(s: Species, p: Int): Float {
        var v = 0f
        for ((k, ps) in kinds.withIndex()) {
            if (ps.carnivore) continue
            val heads = headsHere[k][p]
            if (heads > 0) v += heads * ps.massKg * catchable(s, ps, p) / land.areaKm2[p]
        }
        return v
    }

    /** Mean moonlight over this week's nights, 0 (new moon) to 1 (full). */
    internal var moon = 0.5f

    /** Time spent settling meetings, ns, over the run (for measuring the cost). */
    var meetNs = 0L


    internal fun poisson(mean: Float, r: Random = rnd): Int {
        if (mean <= 0f) return 0
        if (mean > 30f) return (mean + kotlin.math.sqrt(mean) * gaussian(r)).toInt().coerceAtLeast(0)
        val l = exp(-mean.toDouble()); var k = 0; var pr = 1.0
        do { k++; pr *= r.nextDouble() } while (pr > l)
        return k - 1
    }

    internal fun binomial(n: Int, p: Float, r: Random = rnd): Int {
        if (n <= 0 || p <= 0f) return 0
        if (p >= 1f) return n
        if (n > 50) return (n * p + kotlin.math.sqrt(n * p * (1 - p)) * gaussian(r)).toInt().coerceIn(0, n)
        var k = 0; repeat(n) { if (r.nextFloat() < p) k++ }; return k
    }

    internal fun gaussian(r: Random = rnd): Float {
        val u = max(r.nextDouble(), 1e-12)
        return (kotlin.math.sqrt(-2 * kotlin.math.ln(u)) * kotlin.math.cos(2 * Math.PI * r.nextDouble())).toFloat()
    }

    /** A random stream of group [id]'s own for the week starting on [day]: the same whatever the cores do. */
    internal fun groupRandom(id: Int, day: Long) = Random(seedBase * 1_000_003L + id * 7_919L + day)

    internal fun hashNoise(id: Int, p: Int): Float {
        var h = id * 0x9E3779B97F4A7C15uL.toLong() + p * 0x632BE59BD9B4E019L
        h = (h xor (h ushr 33)) * -0xae502812aa7333L
        h = h xor (h ushr 33)
        return (h ushr 40).toFloat() / (1L shl 24).toFloat()
    }
}
