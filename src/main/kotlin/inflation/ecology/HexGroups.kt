package inflation.ecology

import inflation.Profile
import inflation.world.Phase
import inflation.world.Hex
import inflation.world.HexArray
import inflation.world.HexList
import inflation.world.forEachHex
import inflation.world.hexArray
import inflation.world.toHexArray
import inflation.world.Place
import inflation.world.get
import inflation.world.set

import inflation.gaussianF
import inflation.KindStock
import inflation.StockName
import inflation.ecology.HexSmallLife.DeadPool
import inflation.Books
import inflation.Stocks
import inflation.Kg
import inflation.KgM2
import inflation.Tuning
import inflation.store.MovedInto
import inflation.wrapMonth
import inflation.saturate
import inflation.monthsFrom
import inflation.HASH_A
import inflation.HASH_B
import inflation.HASH_C
import inflation.mix64Once
import inflation.unitOf
import inflation.Config

import inflation.world.Date
import inflation.world.HexLand
import inflation.world.HexMap
import inflation.world.HexWorld
import inflation.world.Weather
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.min
import kotlin.random.Random
import inflation.ecology.HexFeatures as Feature
import inflation.world.Biome
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.stream.IntStream
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The hunters on the hexes, as groups: packs and prides, and bands of people. Each week every group looks at the
 * places it can reach for the effort it is willing to spend (a few hexes when content, a forced
 * march when hungry, cold or on its season's trek), over the borders as its way of travelling
 * allows, and goes to the best by its own lights; the hexes it passes through are where it may
 * meet others. Hunters hunt the residents of the places they pass; packs hold ground and meet
 * rivals. Young are born in season, the hungry and the old die, and groups too big for their food split.
 */
class HexGroups(
    hw: HexWorld, land: HexLand, fauna: HexFauna, days: Int, seed: Long,
    /** The kinds that live as groups here (default: the hunting packs and people). */
    kindsHere: List<Species>? = null,
    /** Start every kind spread evenly over the land, wherever it suits it or not (to see them find their country). */
    uniformStart: Boolean = false,
) : GroupWorld(hw, land, fauna, days, seed) {
    val groups = ArrayList<HexGroup>()
    internal var nextId = 0
    /**
     * The kinds that live as groups: the hunters other than the residents' ([Catalogue.residentHunters]), and people (plant-eaters
     * live only as residents). For comparison runs, people=false ([inflation.Tuning.people]) leaves people out.
     */
    @Transient val kinds = (kindsHere ?: Catalogue.predators.filter { it !in Catalogue.residentHunters && (!it.human || tuning.people) })
        .also { k -> require(k.all { it.carnivore }) { "plant-eaters live only as residents, not as groups: ${k.filter { !it.carnivore }.joinToString { it.name }}" } }
    /** Each kind's sense of where better country lies: prey, its ground, water, and the climate this month. */
    val bearings by lazy {
        HexBearings(hw, kinds, { s, p, m ->
            val fit = fauna.monthFit(s, Place(p), m)
            // The game it can catch there, by weight.
            val prey = huntable[kindSlot[s.index]][p] +
                if (s.human) KgM2(fauna.food.fruit[p] + rootsFound(Place(p)) + fauna.food.seeds[p]).perKm2.v * PLANT_FOOD_WEIGHT else 0f
            // Hunters that live on the water in their prey (cheetahs, leopards) care less how far it is.
            val w = land.drink.waterKm[p] / (2f * s.dailyTravelKm) * (if (s.waterFromFood >= 0.8f) 0.3f else 1f)
            prey / (prey + PREY_HALF) * fit / (1f + w * w)
        }, { s -> groups.any { it.alive && it.species == s } }, tuning)
    }
    /** Game each hunting kind can catch in each place, kg/km² weighed by catchability, worked out once a month for the bearings. */
    internal val huntable by lazy { Array(kinds.size) { FloatArray(n) } }
    /**
     * The beasts of prey in each place this month, kg: the hunters other than people, what a band reads as danger in their
     * sign (made afresh after a load).
     */
    @delegate:Transient internal val monthBeastKg by lazy { FloatArray(n).also { beastsKg(it, people = false) } }
    /** [monthBeastKg] by [BeastClass], for the bands' view of the beasts about. */
    @delegate:Transient internal val monthBeastClassKg by lazy { Array(BeastClass.entries.size) { FloatArray(n) }.also { beastClassKg(it) } }
    /** What the people kind's [huntable] was a month ago, and whether it is known (not after a load, until the next month). */
    @delegate:Transient private val peopleGameBefore by lazy { FloatArray(n) }
    @Transient private var gameBeforeKnown = false
    @Transient private var huntableFilled = false
    /** People sick of each catching illness in each place as the week's choosing begins (heads × share sick). */
    @delegate:Transient internal val sickPeople by lazy { Array(Illness.entries.size) { FloatArray(n) } }
    /** The beasts of prey in each place as the week's choosing begins, kg (the hunters other than people). */
    @delegate:Transient internal val beastKg by lazy { FloatArray(n) }
    /** What the bands share ([Bands]; saves from before it was split out kept its tables here). */
    @field:MovedInto("built", "followersByHex", "bandHex", "houseOf", "materialSources\$delegate", "saltSources\$delegate")
    val bands = Bands(this)
    /**
     * How each group chooses where to go ([GroupPlanner]; saves from before it was split out kept its tables and its usual densities here).
     */
    @field:MovedInto("placeValue\$delegate", "expectHeads\$delegate", "expectMonth", "usualDensity", "usualHuntersKm2", "topStrength", "topKind", "manEaterKm2", "planCounts", "scratch")
    internal val planner = GroupPlanner(this)
    /** Scent marks of the hunting kinds (saves from before it was split out kept them here as marks and markers). */
    @field:MovedInto("marks", "markers") internal val scent = Scent()
    /** Learned choosers by kind; kinds without one use the hand-written score. */
    val policies = HashMap<Species, HexPolicy>()
    /** How bands set their ways each week; null for the scaffold's. Not saved: what drives a run is set by the config (ways=), not carried in a save. */
    @Transient var ways: BandWays? = null
    /** Counts how the bands' ways act (dialUse=FILE, see [DialUse]); null: not counted. Not saved. */
    @Transient var dialUse: DialUse? = null
    /**
     * The bands that held a rite at each site this month and last ([gatherAtRite]), keyed by the rite's month (counted from
     * the world's start) and its site. Not saved: a gathering under way when a save is taken is forgotten.
     */
    @Transient internal val riteGatherings = HashMap<Long, MutableList<HexGroup>>()
    /** How packs set their ways each week ([AnimalWays]); null for the hand-written ways. */
    @Transient var hunterWays: AnimalWays? = null

    init {
        // The residents swim the same straits.
        fauna.seaLinks = lazy { seaLinks }; fauna.seaGaps = lazy { seaGaps }
        // Start each kind at its usual density over the land that suits it, in groups of its usual size.
        val area = land.areaKm2.sum()
        for (s in kinds) {
            val per = if (s.startKm2PerAnimal > 0f) s.startKm2PerAnimal else 50f
            // At the density it settles at over a long run, where one is known.
            val settled = Catalogue.settledPer1000Km2(s)
            // (People's bands can start larger, the same people in fewer of them: t.peopleBand.)
            val unit = if (s.human) max(1, (unitSize(s) * tuning.peopleBand).toInt()) else unitSize(s)
            val usual = if (settled != null) max(if (settled > 0f) 1 else 0, (settled * area / 1000f / unit).toInt()) else (area / per / unit).toInt()
            // (People's start can be a multiple of the usual, to find what the land would carry: t.peopleStart.)
            val count = if (s.human) (usual * tuning.peopleStart).toInt() else usual
            val weights = if (uniformStart) FloatArray(n) { p -> land.areaKm2[p] } else startWeights(s)
            val total = weights.sum()
            // (Groups start of all sizes about the usual, the same heads in all: [startSizes].)
            val sizes = startSizes(s, unit, count)
            for (size in sizes) {
                val p = inflation.drawWeighted(weights, total, rnd)
                val g = spawn(s, Place(p), size)
                if (s.human) {
                    g.larder = START_LARDER_DAYS * g.dailyNeedKg
                    val (x, y) = hex.centreKm(hw.placeHex[p])
                    g.band = Band.start(fauna.kinds.size, land.drink.waterKm[p] < 3f, x, y, tuning).also { b ->
                        b.sisu = Sisu.founder(g.id, seedBase)
                        b.elders = g.adults / 6
                        // The rest of the adults spread over the three spans of adult life.
                        b.ageStage[0] = (g.adults - b.elders) / 3; b.ageStage[1] = (g.adults - b.elders) / 3
                        // It knows the country within a few hexes of its first camp.
                        var ring = listOf(hw.placeHex[p]); val seen = hashSetOf(ring[0])
                        repeat(KNOWN_AT_START) { ring = hex.nextRing(ring, seen) }
                        for (h in seen) b.learn(h, hex.size)
                        // And the plants of the country round it.
                        for (h in seen) for (k in Biome.entries) if (hw.biome[k.ordinal][h] > 0.1f) b.plants[k] = 0.9f
                    }
                }
                if (s.lifeway == Lifeway.PACK) claimGround(g, hw.placeHex[p]) else inheritSeasons(g)
                groups += g
            }
        }
        // (A world begun once farming is known starts some bands as villages, t.startSettled; once herding is known, some
        // bands with herds, t.startHerders.)
        if (tuning.startSettled > 0f) startVillages()
        if (tuning.startHerders > 0f) startHerders()
        // (Some founding bands start with the planning kind's mind, t.planners.)
        foundMinds()
    }

    /**
     * The adults of each of [s]'s groups at the start: [count] groups' worth of [unit] adults, shared out in groups whose sizes
     * spread about [unit] as bands' camps and packs do (lognormal, [START_BAND_SPREAD] for people, [START_PACK_SPREAD] for
     * packs), each of at least a pair (a lone hunter where its kind hunts alone) and at most what a group holds before it splits
     * ([Species.maxGroupSize]). Its own stream of chance for each kind, from the world's seed.
     */
    private fun startSizes(s: Species, unit: Int, count: Int): IntArray {
        val r = Random(seedBase * 41 + 7 + 1009L * s.name.hashCode())
        val spread = if (s.human) START_BAND_SPREAD else START_PACK_SPREAD
        val least = min(2, unit)
        var left = count * unit
        val sizes = ArrayList<Int>()
        while (left > 0) {
            val drawn = (unit * exp(spread * gaussianF(r))).roundToInt().coerceIn(least, max(least, s.maxGroupSize))
            val size = if (left - drawn < least) left else drawn
            sizes += size; left -= size
        }
        return sizes.toIntArray()
    }

    /**
     * Newcomers: once a year, a kind of pack that has dwindled to a few groups (or died out)
     * gets a few groups more, at places that suit it, as if wandering in from beyond the map. Its
     * niche then decides whether they last. Only in training's play worlds ([newcomersOn]), to keep
     * every kind in the data; never in checks or ordinary runs, where a collapse must show.
     */
    var newcomersOn = false
    internal fun newcomers(date: Date) {
        if (!newcomersOn || date.month != 0 || date.dayOfMonth > days) return
        for (s in kinds) {
            if (s.human) continue
            val now = groups.count { it.alive && it.species == s }
            if (now >= NEWCOMER_BELOW) continue
            val weights = startWeights(s)
            val total = weights.sum(); if (total <= 0f) continue
            val unit = unitSize(s)
            repeat(NEWCOMERS) {
                val p = inflation.drawWeighted(weights, total, rnd)
                val g = spawn(s, Place(p), unit)
                // (Unlike at the start, any newcomer hunter, not only a pack, comes with ground of its own.)
                claimGround(g, hw.placeHex[p])
                groups += g
            }
            stats.meeting(Met.Of.NEWCOMERS, s.name, NEWCOMERS)
        }
    }


    /** Where groups of [s] start or wander in: by area, how the ground and climate suit it, and water within its reach in a usual year. */
    private fun startWeights(s: Species) = FloatArray(n) { p -> startWeight(s, Place(p)) }
    private fun startWeight(s: Species, p: Place) = fauna.startWeight(s, p)

    /** A new group of [s] at place [p], [unit] adults and a third as many young, half-fat. */
    internal fun spawn(s: Species, p: Place, unit: Int): HexGroup {
        val g = HexGroup(nextId++, s, p, unit, max(0, unit / 3))
        g.temper = Temperament.founder(g.id, seedBase)
        g.fat = 0.5f * s.maxFatDays
        return g
    }

    fun count(s: Species) = groups.filter { it.species == s && it.alive }.sumOf { it.size }

    /** The group being followed (in the viewer), -1 for none; what happened to it this week, in words. */
    @Volatile var watched = -1
    val watchLog = ConcurrentLinkedQueue<String>()
    internal inline fun note(g: HexGroup, msg: () -> String) { if (g.id == watched) watchLog += msg() }
    /** The player's hand on the followed group (the viewer's group controls), null for none: see [GroupControl]. */
    @Transient @Volatile var control: GroupControl? = null

    /**
     * Names the groups' stocks in [books]: the animals of each kind in packs and bands (the young at a
     * resident's [HexFauna.YOUNG_WEIGHT]), the meat hunters hold from the week's kills, and bands' food, caches, kept
     * animals and the goods that come from living things (wood, fibre, resin, hide, bone, sinew).
     */
    internal fun listStocks(stocks: Stocks) {
        stocks.within(StockName.GROUPS) { for (s in kinds) stocks.ofKind(KindStock.GROUP, s) {
            var sum = 0.0; for (g in groups) if (g.alive && g.species == s) sum += (g.adults + HexFauna.YOUNG_WEIGHT * g.young) * s.massKg.toDouble(); sum } }
        stocks.add(Books.Stuff.MATTER, StockName.HUNTERS_MEAT_IN_HAND) {
            var sum = 0.0; for (g in groups) if (g.meatMet > 0f && !g.meatSpent) sum += g.meatMet * meatShare(g.band) / creditShare(g.band).toDouble(); sum }
        fun bands(f: (Band) -> Double) = run { var sum = 0.0; for (g in groups) if (g.alive) g.band?.let { sum += f(it) }; sum }
        stocks.add(Books.Stuff.MATTER, StockName.BANDS_FOOD) { var sum = 0.0; for (g in groups) if (g.alive && g.band != null) sum += massOf(g.stores, g.storeKgPer); sum }
        stocks.add(Books.Stuff.MATTER, StockName.BANDS_CACHES) { bands { it.cacheMass().toDouble() } }
        stocks.add(Books.Stuff.MATTER, StockName.BANDS_KEPT_ANIMALS) { bands { b -> var sum = 0.0; val y = b.herdYoung
            for (k in b.herd.indices) sum += (b.herd[k] + HexFauna.YOUNG_WEIGHT * y.getOrElse(k) { 0f }) * fauna.kinds[k].massKg; sum } }
        stocks.add(Books.Stuff.MATTER, StockName.BANDS_GOODS) { bands { it.livingGoods().toDouble() } }
    }

    /** The bands with kept herds, by the place they are camped in, for the resident hunters' week ([HexFauna.keptAt]). */
    internal fun herdsForBeasts(): Map<Int, List<Band>> {
        val at = HashMap<Int, ArrayList<Band>>()
        for (g in groups) if (g.alive) g.band?.let { b -> if (b.herd.indices.any { k -> b.herd[k] + b.herdYoung.getOrElse(k) { 0f } >= 1f }) at.getOrPut(g.place.i) { ArrayList() } += b }
        if (HUNT_DIAG) {
            // (Last week's take at the camps, then what is held there as this week starts: [HuntStep.KEPT_YOUNG_HELD] and on.)
            for ((k, s) in fauna.kinds.withIndex()) { val t = fauna.campTaken[k]
                diag(s, HuntStep.KEPT_YOUNG_TAKEN, t[0].sumThenReset().toFloat()); diag(s, HuntStep.KEPT_GROWN_TAKEN, t[1].sumThenReset().toFloat())
                diag(s, HuntStep.WILD_YOUNG_TAKEN_AT_CAMPS, t[2].sumThenReset().toFloat()); diag(s, HuntStep.WILD_GROWN_TAKEN_AT_CAMPS, t[3].sumThenReset().toFloat()) }
            for ((p, bs) in at) { val area = land.areaKm2[p]
                // (The wild at every camp with a herd, as the take is counted there.)
                for ((k, s) in fauna.kinds.withIndex()) {
                    diag(s, HuntStep.KEPT_YOUNG_HELD, bs.sumOf { it.herdYoung.getOrElse(k) { 0f }.toDouble() }.toFloat())
                    diag(s, HuntStep.KEPT_GROWN_HELD, bs.sumOf { it.herd.getOrElse(k) { 0f }.toDouble() }.toFloat())
                    diag(s, HuntStep.WILD_YOUNG_AT_CAMPS, fauna.young[k][p] * area); diag(s, HuntStep.WILD_GROWN_AT_CAMPS, fauna.adults[k][p] * area)
                } }
        }
        return at
    }

    /** The groups' part of the week, in order. */
    @Transient val phases = listOf(
        Phase("groups: choosing and moving") { chooseAndMove(it, it.date) },
        // The week, meeting by meeting: where two groups' timetables put them in the same hex on the
        // same days, the earlier meetings are settled first, by each side's standing orders.
        Phase("groups: meetings") { val t0 = System.nanoTime(); meet(it); meetNs += System.nanoTime() - t0 },
        Phase("groups: wearing paths") { wear.week(groups, hw, land, days); routes.week() },
        Phase("groups: hunting and foraging") { hunt(it) },
        Phase("people: burning off") { burnOff(it) },
        Phase("people: robbing caches") { robCaches(it) },
        Phase("people: visiting kin") { visitKin(it) },
        Phase("groups: births, deaths") {
            Profile.part("hunters: marking ground") { mark() }
            Profile.part("hunters: settling ground") { settle(it) }
            demography(it, it.date)
        },
        Phase("groups: the dead and newcomers") {
            // (Groups that merged into another this week, kept for following what became of their choices.)
            merged.clear(); for (g in groups) if (!g.alive && g.mergedSize >= 0) merged += g
            // (A band that died out leaves what it had; one that joined another took it along.)
            val freed = ArrayList<HexGroup>()
            // (A band followed for the forecast trace that died out or joined another: what came till then.)
            if (FORECAST_TRACE) for (g in groups) if (!g.alive) g.band?.let { b -> b.forecastTrace?.let { t -> b.forecastTrace = null
                System.err.print(t.report(if (g.mergedSize < 0) "died out" else "joined another band")) } }
            for (g in groups) if (!g.alive && g.mergedSize < 0) {
                leaveDead(g, freed)
                if (g.species.lifeway == Lifeway.PACK && g.band == null) {
                    stats.meeting(Met.Of.PACK_ENDED, g.species.name)
                    if (g.home.none) stats.meeting(Met.Of.PACK_ENDED_HOMELESS, g.species.name)
                }
            }
            groups.removeIf { !it.alive }
            groups += freed
            newcomers(it.date)
        },
        Phase("groups: remembering places") { remember(it.date) },
        Phase("groups: foreseeing") { foresee(it) },
    )

    /**
     * The week's start: each group's warmth, the month's sense of where game is, the ways packs set, the bands'
     * view of themselves; then each group plans and moves, in parallel, each by last week's world.
     */
    private fun chooseAndMove(weather: Weather, date: Date) {
        watchLog.clear()
        fauna.moon = date.meanMoonlight(days).toFloat()
        // Keeping warm costs food: each group's need this week, against the cold it feels (people
        // by their body heat, through their clothes, fire, shelters and, at home, their houses).
        // (Raw meals give less: a band without fire needs more food for the same strength.)
        // (And a band's people work the less, the colder and hungrier they are, as far as they do not persevere: see [Sisu].)
        for (g in groups) if (g.alive) {
            val b = g.band
            if (b == null) { g.warmth = warmthNeed(g.species, null, g.place, weather, 0f, 0f); continue }
            val w = b.warmth(airAt(g.place, weather), b.fire, b.homeShelter(), days)
            g.warmth = warmthNeed(w, b.fire)
            b.coldPace = w.pace; b.campPace = w.campPace
            b.hungerPace = Sisu.hungerPace(g.species, g.spent, b.sisu)
            b.sleepPace = watchPace(b.watchHours, g.adults)
        }
        if (bearings.month != date.month) {
            beastsKg(monthBeastKg, people = false)
            beastClassKg(monthBeastClassKg)
            val people = kindSlot[Catalogue.HUMAN.index]
            if (people >= 0) { huntable[people].copyInto(peopleGameBefore); gameBeforeKnown = huntableFilled }
            IntStream.range(0, n).parallel().forEach { p -> for ((k, s) in kinds.withIndex()) huntable[k][p] = dietValue(s, Place(p), preyHere(s, Place(p))) }
            huntableFilled = true
            Profile.time("sense of direction") { bearings.update(date.month) }
        }
        val predatorKg = FloatArray(n).also { beastsKg(it, people = true) }
        beastsKg(beastKg, people = false)
        for (a in sickPeople) a.fill(0f)
        for (g in groups) if (g.alive) g.band?.let { b -> for (d in Illness.entries) if (d.contagion > 0f) sickPeople[d.ordinal][g.place] += g.size * b.sick[d] }
        planner.takeStock()
        // Packs set their ways for the week (bands set theirs as they forage, see [hunt]).
        Profile.part("animals: setting their ways") { setAnimalWays(weather) }
        // Bands take stock of themselves and of the bands about them, for the people networks.
        Profile.part("bands: taking stock") { fillBandStates(weather) }
        // Plan and move, in parallel: each group's choice depends only on last week's world.
        Profile.part("choosing: valuing places for hunters") { planner.beginChoosing(predatorKg, date.month) }
        val choice = IntArray(groups.size); val paths = arrayOfNulls<HexArray>(groups.size); val times = arrayOfNulls<FloatArray>(groups.size)
        IntStream.range(0, groups.size).parallel().forEach { k ->
            val g = groups[k]
            if (!g.alive) return@forEach
            // (With profile=fine, the choosing is timed by packs and bands apart.)
            val plan = Profile.part(if (g.band != null) "choosing: bands" else "choosing: packs") { planner.plan(g, weather, date, predatorKg) }
            choice[k] = plan.place.i; paths[k] = plan.path
            times[k] = Profile.part("choosing: timetable") { timetable(g.species, plan.path) }
        }
        for ((k, g) in groups.withIndex()) if (g.alive) {
            if (g.band != null && g.fed < Thresholds.HUNGRY)
                stats.meeting(if (paths[k]!!.size <= 1) Met.HUNGRY_BANDS_CHOSE_TO_STAY else Met.HUNGRY_BANDS_CHOSE_TO_MOVE)
            g.climbedM = max(0f, hw.placeElevationM[choice[k]] - hw.placeElevationM[g.place.i])
            g.place = Place(choice[k]); g.path = paths[k]!!; g.days = times[k]!!; g.meatMet = 0f; g.meatSpent = false
            crossWater(g, g.path)
        }
    }

    /** A crossing of open water on [path] drowns some of [g], the more the nearer the strait is to all they can swim. */
    private fun crossWater(g: HexGroup, path: HexArray) {
        for (i in 0 until path.size - 1) {
            val gapKm = seaGap(path[i], path[i + 1]); if (!gapKm.isFinite()) continue
            val risk = Hazards.drowning(g.species, gapKm)
            val lostA = binomial(g.adults, risk, rnd); val lostY = binomial(g.young, 2f * risk, rnd)
            adultsDied(g, lostA, rnd); g.young -= lostY
            // (The drowned wash up on the far shore.)
            small.deposit(DeadPool.CARRION, g.place, Kg((lostA + HexFauna.YOUNG_WEIGHT * lostY) * g.species.massKg))
            if (lostA + lostY > 0) stats.deaths.merge(Tally.of(g.species, DeathCause.DROWNED_CROSSING), lostA + lostY, Int::plus)
            stats.meeting(Met.Of.SEA_CROSSINGS, g.species.name)
        }
    }



    /** The groups that merged into another in the last step (gone from [groups]), with their numbers when they did ([HexGroup.mergedSize]). */
    @Transient val merged = ArrayList<HexGroup>()
    /** Outbreaks breaking out among the groups this week, by kind and hex (see [liveWeek]), started once all have lived it. */
    internal val outbreaksStarting: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    /** The worn ways between hexes ([PathWear]; saves from before it was split out kept them here as paths). */
    @field:MovedInto("paths") val wear = PathWear(hex)
    /**
     * Sea crossings: for each coastal land hex, the land hexes across a strait (one or two hexes of
     * sea) and the open water between, km. Swimmers that can cover it may cross, at a great effort
     * and a risk of drowning; for the rest the sea is a wall.
     */
    @delegate:Transient val seaLinks: Array<HexArray> by lazy { links().to }
    @delegate:Transient val seaGaps: Array<FloatArray> by lazy { links().gapKm }
    /** For each hex, the hexes across the sea from it ([to]) and the open water to each ([gapKm]). */
    class SeaCrossings(val to: Array<HexArray>, val gapKm: Array<FloatArray>)
    @Transient private var linksMade: SeaCrossings? = null
    private fun links(): SeaCrossings = linksMade ?: run {
        val to = Array(hex.size) { HexArray.EMPTY }; val gap = Array(hex.size) { FloatArray(0) }
        for (a in hex.all) {
            if (hex.heart[a] < 0 || (0 until 6).none { d -> val b = hex.neighbour(a, d); !b.none && hex.heart[b] < 0 }) continue
            val found = LinkedHashMap<Hex, Float>()
            var ring = (0 until 6).mapNotNull { d -> hex.neighbour(a, d).takeIf { !it.none && hex.heart[it] < 0 } }; val seen = HashSet(ring + a)
            repeat(SEA_HEXES) {
                val next = ArrayList<Hex>()
                for (w in ring) for (d in 0 until 6) {
                    val b = hex.neighbour(w, d); if (b.none || !seen.add(b)) continue
                    if (hex.heart[b] < 0) next += b
                    else if ((0 until 6).none { e -> hex.neighbour(a, e) == b }) found[b] = (hex.distanceKm(a, b) - hex.hexKm).toFloat().coerceAtLeast(1f)
                }
                ring = next
            }
            to[a] = found.keys.toHexArray(); gap[a] = found.values.toFloatArray()
        }
        SeaCrossings(to, gap).also { linksMade = it }
    }
    /** The open water between hexes [a] and [b], km, if a swimmer can cross from one to the other; else infinite. */
    fun seaGap(a: Hex, b: Hex): Float { val l = seaLinks[a]; for (i in l.indices) if (l[i] == b) return seaGaps[a][i]; return Float.POSITIVE_INFINITY }

    /** The worn paths read as a graph of landmarks and the legs between them. */
    val routes = HexRoutes(this)




    /** Head count of each kind in each place this week, and each species' slot in [kinds]. */
    internal val headsHere by lazy { Array(kinds.size) { IntArray(n) } }
    internal val peopleSlot by lazy { kinds.indexOfFirst { it.human } }
    /**
     * Resident kinds that dig up a band's cache where they are about: those strong enough to break into it (50 kg and
     * more) that root for a fifth or more of their food.
     */
    internal val cacheDiggers by lazy { fauna.kinds.indices.filter { fauna.kinds[it].massKg >= 50f && fauna.kinds[it].diet.roots >= 0.2f } }
    /** Each catalogue kind's slot among the group [kinds], -1 for a kind that keeps no groups (so a wrong lookup fails, not reads kind 0); worked out afresh, not saved. */
    /** Fills [into] with the kg of the groups' beasts of prey at each place, people with them or not. */
    private fun beastsKg(into: FloatArray, people: Boolean) {
        into.fill(0f)
        for (g in groups) if (g.alive && (people || !g.species.human)) into[g.place] += g.size * g.species.massKg
    }

    private fun beastClassKg(into: Array<FloatArray>) {
        for (a in into) a.fill(0f)
        for (g in groups) if (g.alive && !g.species.human) into[BeastClass.of(g.species).ordinal][g.place] += g.size * g.species.massKg
    }

    /** How the game people can take at [p] has changed over the last month, -1 (all gone) to 1 (all new): their [huntable] against a month ago. */
    internal fun gameTrend(p: Place): Float {
        val people = kindSlot[Catalogue.HUMAN.index]
        if (!gameBeforeKnown || people < 0) return 0f
        val now = huntable[people][p]; val before = peopleGameBefore[p]
        return if (now + before > 0f) (now - before) / (now + before) else 0f
    }

    /** Each kind's slot in [kinds], by its [Species.index]; -1 for a kind not kept in groups. */
    @delegate:Transient internal val kindSlot by lazy { IntArray(Catalogue.all.size) { i -> kinds.indexOfFirst { it.index == i } } }










    /**
     * Bands burn off the dry grass around their camps in the dry season: the burn greens up and draws
     * game (and the fire may spread with the wind).
     */
    private fun burnOff(weather: Weather) {
        for (g in groups) {
            if (!g.alive || !g.species.human) continue
            val p = g.place
            // (Dry enough by the same test that lets any fire take: a gate of its own, stricter, had kept bands from ever burning.)
            if (land.fire.canBurn(p, weather) && land.fire.burnt[p] < 0.1f &&
                drawn(DialSite.BURNING_OFF, g.band?.burnWill ?: 1f, rnd.nextFloat(), BURN_OFF * (g.band?.burnWill ?: 1f)) { BURN_OFF }) {
                land.fire.ignite(p)
                stats.meeting(Met.BANDS_BURNED_OFF_GRASS)
            }
        }
    }



    /** How big a group of [g]'s kind grows before it splits: packs and prides keep their kind's size, bands as their ways hold them together. */
    internal fun maxSize(g: HexGroup): Int {
        val s = g.species
        return g.band?.let { b ->
            // A band at home in its houses holds a village's number; feasts keep the peace in bigger bands, settled or not.
            val most = if (b.atHome) max(s.maxGroupSize, SETTLED_SIZE) else s.maxGroupSize
            val size = if (b.holds(Tradition.FEASTS)) (most * FEAST_SIZE).toInt() else most
            // A strained belief is one more quarrel: strained as far as it bears, a band holds together at half the size.
            if (tuning.rounds && b.strain > 0f) (size / (1f + b.strain / b.strainBorne())).toInt() else size
        } ?: s.maxGroupSize
    }

    /** How far going to hex [h] brings band [g] toward its appointment, -1..1, in its month (0 otherwise). */
    internal fun appointPull(g: HexGroup, b: Band, month: Int, h: Hex): Float {
        val ap = b.appointment ?: return 0f
        val off = minOf(monthsFrom(ap.month, month), monthsFrom(month, ap.month))
        // The month before, it is on its way; without sky lore it also lingers a month after, the count being loose.
        val before = monthsFrom(month, ap.month) == 1
        val weight = when { off == 0 -> 1f; before -> 0.7f; off == 1 && !b.holds(Tradition.SKY_LORE) -> 0.5f; else -> return 0f }
        val here = hw.placeHex[g.place]
        val fromHere = hexKm(here, ap.hex)
        if (fromHere <= hex.hexKm) return if (h == here) weight else 0f
        return weight * ((fromHere - hexKm(h, ap.hex)) / fromHere).toFloat().coerceIn(-1f, 1f)
    }

    /** The main kind of country of each place. */
    internal val biomeOfPlace: IntArray get() = hw.placeBiome
    /** A kind's usual group as an agent. */
    internal fun unitSize(s: Species) = s.groupSize





    /** A hunting party's fighting strength: weight, ferocity. */
    internal fun strength(g: HexGroup) = foragers(g) * g.species.massKg * ferocity(g)
    /** Those of [g]'s grown that go about together ([Species.foragingParty]). */
    internal fun foragers(g: HexGroup) = g.species.foragingParty.let { if (it > 0) min(it, g.adults) else g.adults }
    internal fun ferocity(g: HexGroup) = g.band?.ferocity(g.species) ?: g.species.ferocity

















    /**
     * What band [g]'s food would do for it at place [p] (in hex [h]) for its sodium ([saltFeeds], as its meal has it): as short
     * as it was last week, unless salt is to be had there (a salt pan or dry lake bed, the shore), which makes up the lack.
     */
    internal fun saltFeedsAt(g: HexGroup, p: Place, h: Hex): Float {
        val b = g.band ?: return 1f
        if (b.sodiumShort <= 0f || land.salt[p] > 0f || hw.coast[h]) return 1f
        return saltFeeds(b.sodiumShort, 1f, tuning.saltLack)
    }



    /**
     * Deaths [dying] (a share of [g] this week) in steps of the chance in a week's deaths: the spread of the share that dies
     * of a group of [g]'s size at the odds it lives under now (at least its kind's background). A difference within a step
     * is no more than chance and weighs as one unit of the rest of the score; one of many steps outweighs all of it.
     */
    internal fun survivalSteps(g: HexGroup, dying: Float) =
        Hazards.survivalSteps(dying, g.deathOdds, g.species.backgroundMortality * years, g.size.toFloat())



    /**
     * What place [p] drains from group [g] this week, as a share of its week's need (the unit the going
     * is reckoned in): the extra food it burns keeping warm in the cold its coat (or clothes, fire and
     * shelter) does not keep off ([coldNeed]), and what the biting insects cost it ([bitten]).
     */
    internal fun drain(g: HexGroup, p: Place, weather: Weather): Float {
        val s = g.species; val t = weather.temperature[p]
        val b = g.band
        return warmthNeed(s, b, p, weather, b?.let { fireAt(g, it, p) } ?: 0f, if (b != null) houseAt(g, p) else 0f, t) - 1f + bitten(s, p)
    }


    /**
     * The fire band [b] (group [g]) would keep camped at place [p]: its fire now; elsewhere the fire it keeps lit, as big
     * as the days it gives firewood now fetch there, and at least a cooking fire as far as the wood its hands can fetch
     * there feeds it ([hearthShare], as [fetchFuel] fetches it). (A band that had no wood at all this week counts on
     * keeping a fire lit where there is wood.)
     */
    internal fun fireAt(g: HexGroup, b: Band, p: Place): Float {
        if (p == g.place) return b.fire
        val lit = if (b.hearthWood > 0f) min(1f, b.fire / b.hearthWood) else 1f
        val here = woodCover(g.place); val there = woodCover(p)
        val same = if (here > 0f) b.hearthWood * there / here else 0f
        return lit * max(same, hearthShare(there, g.size.toFloat() / forageHands(g)))
    }

    /**
     * The brush shelters band [b] (group [g]) would have camped at place [p], [km] off: its shelters now; elsewhere what it
     * puts up there in the days it had to spare, less the walk's days, as far as it aims to ([shelterReach]). (A band whose
     * walk takes its spare days sleeps in the open where it comes to: what being caught on the move in the cold costs.)
     * (Last week's spare days stand for this week's: an estimate.)
     */
    internal fun shelterAt(g: HexGroup, b: Band, p: Place, km: Float): Float {
        if (p == g.place) return b.shelter
        val people = g.adults.toFloat()
        if (people <= 0f) return 0f
        val left = max(0f, b.spareDays - moveDays(g, km) * forageHands(g))
        return shelterReach(b, people, 0f, b.shelterWill, left)
    }

    /** How far [g] camped at place [p] would be sheltered by houses there it may use (0 for none worth the name). */
    internal fun houseAt(g: HexGroup, p: Place) = housesAt(g, hw.placeHex[p]).takeIf { it > 0.05f } ?: 0f








    /** Time spent settling meetings, ns, over the run (for measuring the cost). */
    @Transient var meetNs = 0L




}

/** How well band [b] knows the plants of hex [h]'s country: [PLANT_FLOOR] for a stranger to 1. */
internal fun GroupWorld.plantKnow(b: Band, h: Hex): Float {
    var s = 0f; var w = 0f
    for (k in Biome.entries) { val share = hw.biome[k.ordinal][h]; if (share > 0f) { s += share * b.plants[k]; w += share } }
    return PLANT_FLOOR + (1f - PLANT_FLOOR) * (if (w > 0f) s / w else 0f)
}

/** A week's gathering in hex [h] teaches band [b] its country's plants. */
internal fun GroupWorld.learnPlants(b: Band, h: Hex) {
    var w = 0f
    for (k in Biome.entries) w += hw.biome[k.ordinal][h]
    if (w <= 0f) return
    for (k in Biome.entries) { val share = hw.biome[k.ordinal][h] / w; if (share > 0f) b.plants[k] += PLANT_LEARN * share * (1f - b.plants[k]) }
}
