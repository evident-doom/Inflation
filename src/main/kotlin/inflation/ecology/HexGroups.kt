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
    internal val hw: HexWorld, internal val land: HexLand, internal val fauna: HexFauna, internal val days: Int, seed: Long,
    /** The kinds that live as groups here (default: the hunting packs and people). */
    kindsHere: List<Species>? = null,
    /** Start every kind spread evenly over the land, wherever it suits it or not (to see them find their country). */
    uniformStart: Boolean = false,
) {
    internal val hex = hw.hex
    internal val n = land.n
    internal val tuning get() = land.tuning
    internal val rnd = Random(seed * 41 + 3)
    internal val seedBase = seed
    val groups = ArrayList<HexGroup>()
    /** What bands have built that stands in the world ([Structure]): no band's own, any band's to use. */
    val built = ArrayList<Structure>()
    /** (The [built] by hex, as the list last stood: see [structuresAt].) */
    @Volatile @Transient internal var builtByHex: StructureIndex? = null
    /** (Whether a world saved before [built] was kept has had its bands' houses raised in it: see [tendStructures].) */
    @Transient internal var structuresSeen = false
    /** Bands' kept animals since the census last read them ([HerdFlow]): heads born, dead, run off and culled, and kg herded and fed. */
    @Transient val herdFlows = enumFloats<HerdFlow>()
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
    @delegate:Transient private val sickPeople by lazy { Array(Illness.entries.size) { FloatArray(n) } }
    /** The beasts of prey in each place as the week's choosing begins, kg (the hunters other than people). */
    @delegate:Transient private val beastKg by lazy { FloatArray(n) }
    /** Scent marks of the hunting kinds (saves from before it was split out kept them here as marks and markers). */
    @field:MovedInto("marks", "markers") internal val scent = Scent()
    /** Learned choosers by kind; kinds without one use the hand-written score. */
    val policies = HashMap<Species, HexPolicy>()
    /** How bands set their ways each week; null for the scaffold's. Not saved: what drives a run is set by the config (ways=), not carried in a save. */
    @Transient var ways: BandWays? = null
    /** Counts how the bands' ways act (dialUse=FILE, see [DialUse]); null: not counted. Not saved. */
    @Transient var dialUse: DialUse? = null
    /** How packs set their ways each week ([AnimalWays]); null for the hand-written ways. */
    @Transient var hunterWays: AnimalWays? = null
    /** This week's tallies ([HexSim.stats]). */
    val stats get() = fauna.stats
    internal val years = Date.years(days)
    internal val small get() = fauna.small

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

    internal fun foodFor(s: Species, p: Place) = fauna.foodFor(s, p)

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
        stocks.add(Books.Stuff.MATTER, StockName.BANDS_KEPT_ANIMALS) { bands { b -> var sum = 0.0; for (k in b.herd.indices) sum += b.herd[k] * fauna.kinds[k].massKg; sum } }
        stocks.add(Books.Stuff.MATTER, StockName.BANDS_GOODS) { bands { it.livingGoods().toDouble() } }
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
            for (g in groups) if (!g.alive && g.mergedSize < 0) leaveDead(g)
            groups.removeIf { !it.alive }
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
        manEaterKm2.fill(0f)
        for (g in groups) if (g.alive && g.species.takesPeople > 0f)
            manEaterKm2[hw.placeHex[g.place]] += g.species.takesPeople * g.size * g.species.massKg / hex.areaKm2.toFloat()
        // The strongest hunting party in each place, and its kind: weaker hunters keep out of its way.
        topStrength.fill(0f); topKind.fill(-1)
        for (g in groups) if (g.alive) {
            val st = strength(g)
            if (st > topStrength[g.place]) { topStrength[g.place] = st; topKind[g.place] = g.species.index }
        }
        // Packs set their ways for the week (bands set theirs as they forage, see [hunt]).
        Profile.part("animals: setting their ways") { setAnimalWays(weather) }
        // Bands take stock of themselves and of the bands about them, for the people networks.
        Profile.part("bands: taking stock") { fillBandStates(weather) }
        // Plan and move, in parallel: each group's choice depends only on last week's world.
        Profile.part("choosing: valuing places for hunters") { valuePlaces(predatorKg, date.month) }
        reachCache.clear(); stepEffort.clear()
        val choice = IntArray(groups.size); val paths = arrayOfNulls<HexArray>(groups.size); val times = arrayOfNulls<FloatArray>(groups.size)
        IntStream.range(0, groups.size).parallel().forEach { k ->
            val g = groups[k]
            if (!g.alive) return@forEach
            // (With profile=fine, the choosing is timed by packs and bands apart.)
            val plan = Profile.part(if (g.band != null) "choosing: bands" else "choosing: packs") { plan(g, weather, date, predatorKg) }
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

    /** Per thread: least effort to each hex, the hex it was reached from, the hexes reached, the queue. */
    private class Scratch(size: Int) {
        val dist = FloatArray(size) { Float.MAX_VALUE }; val via = HexArray(size)
        val reached = HexList(); val queue = LongHeap()
        /** The candidates weighed for a policy: places, their features (arrays reused from week to week), hand-written scores. */
        val candPlaces = IntList(); val candFeats = ArrayList<FloatArray>(); var candScores = FloatArray(64)
        /** For a band, every place weighed ([exploreChoice]): the place, its whole hand-written score, and the survival steps it would cost. */
        val explPlaces = IntList(); var explScores = FloatArray(64); var explDeaths = FloatArray(64)
        fun explore(p: Place, score: Float, deaths: Float) {
            val k = explPlaces.size
            if (k == explScores.size) { explScores = explScores.copyOf(2 * k); explDeaths = explDeaths.copyOf(2 * k) }
            explPlaces += p.i; explScores[k] = score; explDeaths[k] = deaths
        }
        /** The place being weighed: what the hand-written score and the features both read of it. */
        val terms = PlaceTerms()
        val at = PlaceLook()
        /**
         * The places weighed by the hand-written score whose deaths are left till after ([planInner]): each place, its
         * hex, the going there, the game and crowding it knows of there, and its score without its deaths (and with
         * its noise and temper).
         */
        val laterPlace = IntList(); var laterD = FloatArray(64); var laterPrey = FloatArray(64); var laterFed = FloatArray(64)
        var laterScore = FloatArray(64); var laterBeside = FloatArray(64); var laterUpper = FloatArray(64)
        fun later(p: Place, d: Float, prey: Float, fed: Float, score: Float, beside: Float) {
            val k = laterPlace.size
            if (k == laterD.size) { val m = 2 * k; laterD = laterD.copyOf(m); laterPrey = laterPrey.copyOf(m); laterFed = laterFed.copyOf(m)
                laterScore = laterScore.copyOf(m); laterBeside = laterBeside.copyOf(m); laterUpper = laterUpper.copyOf(m) }
            laterPlace += p.i; laterD[k] = d; laterPrey[k] = prey; laterFed[k] = fed; laterScore[k] = score; laterBeside[k] = beside; laterUpper[k] = score + beside
        }
    }

    /**
     * The raw quantities of a place that the hand-written score ([scorePlace]) and a learned policy's features
     * ([fillFeatures]) both weigh, worked out once for each place weighed; each scales them its own way.
     */
    private class PlaceTerms {
        /** The cold or heat felt there now ([discomfort]), and what the place drains from the group ([drain]). */
        var discomfort = 0f; var drain = 0f
        /** The cold or heat coming there, in degrees beyond the kind's comfort (unscaled). */
        var aheadC = 0f
        /** How far drinking water lies, km (for people, including what they can dig for). */
        var waterKm = 0f
        /** How well the place's country suits the kind, as a log. */
        var suitsLn = 0f
    }

    private fun fillTerms(t: PlaceTerms, g: HexGroup, p: Place, weather: Weather, date: Date) {
        val s = g.species
        t.discomfort = discomfort(s, p, weather)
        t.drain = drain(g, p, weather)
        t.aheadC = s.discomfortC(weather.forecastTemp(p.i), date.month)
        t.waterKm = if (s.human) land.drink.peopleWaterKm(p) else land.drink.animalWaterKm(p)
        t.suitsLn = ln(fauna.suits(s, p).coerceAtLeast(1e-3f))
    }
    /** A growable list of ints, in the order added. */
    internal class IntList {
        var items = IntArray(64); var size = 0
        operator fun plusAssign(v: Int) { if (size == items.size) items = items.copyOf(2 * size); items[size++] = v }
        operator fun get(i: Int) = items[i]
        fun clear() { size = 0 }
        fun toIntArray() = items.copyOf(size)
    }
    /** A binary min-heap of longs (no boxing). */
    internal class LongHeap {
        private var a = LongArray(256); private var n = 0
        fun clear() { n = 0 }
        fun isNotEmpty() = n > 0
        operator fun plusAssign(v: Long) {
            if (n == a.size) a = a.copyOf(2 * n)
            var i = n++
            while (i > 0) { val up = (i - 1) / 2; if (a[up] <= v) break; a[i] = a[up]; i = up }
            a[i] = v
        }
        fun poll(): Long {
            val top = a[0]; val last = a[--n]
            var i = 0
            while (true) {
                var c = 2 * i + 1; if (c >= n) break
                if (c + 1 < n && a[c + 1] < a[c]) c++
                if (a[c] >= last) break
                a[i] = a[c]; i = c
            }
            if (n > 0) a[i] = last
            return top
        }
    }
    private val scratch = ThreadLocal.withInitial { Scratch(hex.size) }
    /** This week's searches, shared by the groups of a kind starting from the same place in the same mood. */
    private class Reach(val reach: Float, val hexes: HexArray, val dist: FloatArray, val via: HexArray)
    /** The groups that merged into another in the last step (gone from [groups]), with their numbers when they did ([HexGroup.mergedSize]). */
    @Transient val merged = ArrayList<HexGroup>()
    /** Bands by the hex they are in ([indexBands]): at the start of the week for [fillBandStates], after the moves for [setWays]. */
    @Transient internal val bandIndex = HashMap<Hex, ArrayList<HexGroup>>()
    /** Raiders that reached their target's home this week, with the band they came for: fought once every band has had its week. */
    @Transient internal val raidsDue = ArrayList<Pair<HexGroup, HexGroup>>()
    @Transient private val reachCache = ConcurrentHashMap<Long, Reach>()
    /**
     * This week's effort of each crossing (hex × 6 + direction) for a kind's way of going and the height
     * band it walks in: the crossing's own effort, harder through lying snow, easier along a worn path.
     * The same for every group of those, so worked out once a week for each (see [planInner]); +∞ where
     * there is no way across.
     */
    @Transient private val stepEffort = ConcurrentHashMap<Int, FloatArray>()
    private fun stepEffort(travel: Int, band: Int, weather: Weather): FloatArray = stepEffort.computeIfAbsent(travel * 64 + band) {
        val cross = hex.crossings[travel]
        val out = FloatArray(hex.size * 6) { Float.POSITIVE_INFINITY }
        IntStream.range(0, hex.size).parallel().forEachHex { h ->
            for (k in 0 until 6) {
                val nh = hex.neighbour(h, k)
                if (nh.none || hex.heart[nh] < 0) continue
                // Lying snow makes the going harder: about twice the effort in 15 cm, more in deeper. (Read at the height
                // it walks at: in the next hex, the ground nearest the low or high ground of this one it keeps to.)
                val np = hw.placeIn(band, h).let { if (!it.none) hw.placeNear(it, nh) else hw.placeIn(band, nh) }
                val snowMm = if (!np.none) weather.snowpack[np] else 0f
                // A worn path makes the going easier, the more so where the country is broken.
                val worn = wear.paths[hex.edge(h, k)] * PATH_EASE * (0.5f + (if (!np.none) land.ruggedness[np] else 0f))
                out[hex.edge(h, k)] = cross[hex.edge(h, k)] * (1f + snowMm / SNOW_EFFORT_MM) * (1f - min(0.7f, worn))
            }
        }
        out
    }
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
    /**
     * Where each material bands give each other ([TRADED]) is got (trade on): for each, the hexes where
     * it is richest, one in a square of [SOURCE_KM] at most, for bands' rites to take them to.
     */
    @delegate:Transient internal val materialSources: Map<Material, HexArray> by lazy {
        TRADED.associateWith { m ->
            val share = requireNotNull(m.deposit) { "no deposits known for traded $m" }(land)
            val best = HashMap<Long, Int>()
            for (p in 0 until n) {
                if (share[p] < SOURCE_MIN) continue
                val (x, y) = hex.centreKm(hw.placeHex[p]); val key = (x / SOURCE_KM).toLong() * 100_000 + (y / SOURCE_KM).toLong()
                val b = best[key]; if (b == null || share[p] > share[b]) best[key] = p
            }
            best.values.map { hw.placeHex[it] }.sorted().toHexArray()
        }
    }

    /**
     * Where people get salt to eat: the salt pans and dry lake beds richest in it ([materialSources]) and the shore, where
     * it dries out of sea spray and pools ([SEA_SALT_A_WEEK]), each one in a square of [SOURCE_KM] at most.
     */
    @delegate:Transient internal val saltSources: HexArray by lazy {
        val shore = HashMap<Long, Hex>()
        for (h in hex.all) if (hw.coast[h]) {
            val (x, y) = hex.centreKm(h); shore.putIfAbsent((x / SOURCE_KM).toLong() * 100_000 + (y / SOURCE_KM).toLong(), h)
        }
        (materialSources.getValue(Material.SALT).toList() + shore.values).distinct().sorted().toHexArray()
    }
    /** The nearest of the [saltSources] band [b] knows, from hex [here], or -1 if it knows none (kept on the band till it moves on). */
    internal fun saltKnown(b: Band, here: Hex): Hex {
        if (b.saltFrom == here) return b.saltAt
        var best = Hex.NONE; var bestKm = Double.MAX_VALUE
        for (h in saltSources) if (b.knows(h)) { val km = hexKm(here, h); if (km < bestKm) { bestKm = km; best = h } }
        b.saltFrom = here; b.saltAt = best
        return best
    }

    /** Effort and hex packed in one number, ordered by effort (efforts are never negative). */
    private fun Long.withPlace(d: Float, h: Hex): Long = (d.toRawBits().toLong() shl 32) or h.i.toLong()

    /**
     * What each place offers each kind this week, worked out once for all its groups: the game it can
     * catch there, and for bands the plant food and the camp too ([lifewayValue]).
     */
    internal val placeValue by lazy { Array(kinds.size) { FloatArray(n) } }

    /** Head count of each kind in each place this week, and each species' slot in [kinds]. */
    internal val headsHere by lazy { Array(kinds.size) { IntArray(n) } }
    private val peopleSlot by lazy { kinds.indexOfFirst { it.human } }
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

    private fun valuePlaces(predatorKg: FloatArray, month: Int) {
        for (a in headsHere) a.fill(0)
        for (g in groups) if (g.alive) headsHere[kindSlot[g.species.index]][g.place] += g.size
        // (What may be expected changes slowly: it is reckoned once a month.)
        if (month != expectMonth) { expect(predatorKg); expectMonth = month }
        IntStream.range(0, n).parallel().forEach { p -> for ((k, s) in kinds.withIndex()) placeValue[k][p] = lifewayValue(s, Place(p)) }
    }

    /**
     * What a group may expect of the others where it has not looked: each kind as thick as it usually is
     * on ground that suits it, by how well a place suits it; hunters as thick as they usually are.
     */
    private fun expect(predatorKg: FloatArray) {
        var land2 = 0.0; var kg = 0.0
        for (p in 0 until n) { land2 += land.areaKm2[p]; kg += predatorKg[p] }
        val huntersNow = (kg / land2.coerceAtLeast(1.0)).toFloat()
        usualHuntersKm2 = if (usualHuntersKm2 <= 0f) huntersNow else usualHuntersKm2 + (huntersNow - usualHuntersKm2) / USUAL_MONTHS
        for ((k, s) in kinds.withIndex()) {
            val e = expectHeads[k]
            val suitArea = IntStream.range(0, n).parallel().mapToDouble { p -> val v = fauna.suits(s, Place(p)) * land.areaKm2[p]; e[p] = v; v.toDouble() }.sum()
            var heads = 0L; for (p in 0 until n) heads += headsHere[k][p]
            val now = (heads / suitArea.coerceAtLeast(1.0)).toFloat()
            usualDensity[k] = if (usualDensity[k] <= 0f) now else usualDensity[k] + (now - usualDensity[k]) / USUAL_MONTHS
            val d = usualDensity[k]
            IntStream.range(0, n).parallel().forEach { p -> e[p] *= d }
        }
    }

    /** What place [p] is worth to hunter [g] as it knows it: [placeValue], and for a band the fish it could take there ([fishWorth]). */
    private fun knownGame(g: HexGroup, kind: Int, p: Place, h: Hex): Float = placeValue[kind][p] + (g.band?.let { fishWorth(g, it, h) } ?: 0f)

    /**
     * The fish and shellfish band [b] could take from hex [h] in a week, with its skill ([HexWater.bandCatchWithin]; less
     * plain where it is a stranger), as the game standing on the ground that would yield as much to its hunters: game
     * is met over the ground its party sweeps in a week ([sweptKm2]) and caught at [CATCH].
     */
    private fun fishWorth(g: HexGroup, b: Band, h: Hex): Float =
        fauna.water.bandCatchWithin(h, b.fishing()) * (if (b.knows(h)) 1f else 0.5f) / (sweptKm2(g.species) * CATCH)

    /**
     * What draws band [b] (group [g]) to place [p] in hex [h] or puts it off, beyond what any group weighs: its
     * appointment, its dead, its houses, raids and the homes of others, gatherings, and country it does not know.
     * (Kept apart from [planInner] so that the planner stays small enough for the JIT to compile.)
     */
    private fun bandPull(g: HexGroup, b: Band, month: Int, h: Hex, here: Hex, p: Place, keepOff: HexArray?, sensed: HexArray, reach: Float): Float {
        var score = 0f
        // Its appointment draws a band in its month (to the month with sky lore; within a month
        // either way, more weakly, without); its ancestors' places draw it a little always.
        val pull = appointPull(g, b, month, h)
        if (pull != 0f) score += APPOINT_PULL * pull
        val dead = g.memory.deadAt(h)
        if (dead > 0f) score += ANCESTOR_PULL * min(1f, dead / 10f)
        // Houses it may use draw it (its own or any left empty); a camp where someone died lately is shunned by those who leave their dead.
        score += HOME_PULL * housesAt(g, h)
        score += cropPull(g, b, h)
        score -= herdLoss(g, b, p)
        if (h == b.unluckyHex) score -= UNLUCKY
        // It keeps off the known homes of bands it is not close to (unless set on raiding them).
        if (b.raidHex != h && keepOff != null && h in keepOff) score -= TRESPASS_AVOID
        // A band set on a raid heads for its target.
        if (!b.raidHex.none) {
            val from = hexKm(here, b.raidHex)
            if (from > 0.0) score += RAID_PULL * ((from - hexKm(h, b.raidHex)) / from).toFloat().coerceIn(-1f, 1f)
        }
        // A band short of sodium heads for the nearest salt it knows of, as a hungry animal seeks a lick. What it gets there
        // lasts it years (it keeps [KEEP_YEARS] of [YEAR_NEED]): a year of weeks without the share of its food that salt lack
        // spoils ([saltFeeds]), a year being as far ahead as a band foresees ([foreseenSurvival]), paid for by the weeks the
        // walk there takes, at its reach a week.
        // (Not where it can get salt already: on the shore or a salt pan, as [saltFeedsAt] has it.)
        if (b.sodiumShort > 0f && !hw.coast[here] && land.salt[g.place] <= 0f) {
            val salt = saltKnown(b, here)
            val from = if (!salt.none) hexKm(here, salt) else 0.0
            if (from > hex.hexKm) {
                val weeks = max(1.0, from / reach).toFloat()
                score += (1f - saltFeeds(b.sodiumShort, 1f, tuning.saltLack)) * stepsPerYear / weeks *
                    ((from - hexKm(h, salt)) / min(from, reach.toDouble())).toFloat().coerceIn(-1f, 1f)
            }
        }
        // A band with food enough to feast others is drawn to where other bands are (gatherings).
        if (peopleSlot >= 0 && b.holds(Tradition.FEASTS))
            dialUse?.choice(DialSite.STORE_FEAST, b.storeShare, g.larder > b.storeAim * g.dailyNeedKg, g.larder > b.storeAimAt1 * g.dailyNeedKg)
        if (peopleSlot >= 0 && b.holds(Tradition.FEASTS) && g.larder > b.storeAim * g.dailyNeedKg) {
            val others = knownHeads(peopleSlot, p, h, sensed) - (if (p == g.place) g.size else 0)
            if (others > 0f) score += GATHER_PULL * min(1f, others / GATHER_HEADS)
        }
        // Bands keep to country they know; what lies beyond they know only as a sense of better land.
        // Strange country puts a band off, unless it is out to explore (then it draws it on).
        if (!b.knows(h)) score -= UNKNOWN_COUNTRY * (2f - b.explore)
        if (!b.knows(h)) dialUse?.amount(DialSite.EXPLORE_SCORE, b.explore, UNKNOWN_COUNTRY * (2f - b.explore), UNKNOWN_COUNTRY)
        return score
    }

    /**
     * What the crop band [b] (group [g]) has sown and not yet reaped draws it to the hex it stands in: leaving it, the band
     * would lose its harvest (others reap a field no one keeps), a loss paid for over the weeks it keeps a camp ([Band.stayWeeks]),
     * as a move's lost days are. The harvest is its share of the field as the sim would ripen it now ([HexFood.fieldExpect]),
     * in weeks of its food.
     */
    private fun cropPull(g: HexGroup, b: Band, h: Hex): Float {
        val f = b.sownPlace
        if (f.none || hw.placeHex[f.i] != h) return 0f
        val food = fauna.food
        val kg = min(b.sownKg, food.field[f] * land.m2(f))
        if (kg <= 0f) return 0f
        return kg * food.fieldExpect(f) * seedValue(b) / (g.dailyNeedKg.coerceAtLeast(1e-3f) * days) / b.stayWeeks
    }

    /**
     * What a camp at [p] would cost band [b]'s (group [g]'s) kept animals a week, in weeks of its food: as far as they would go
     * short of forage there ([herdForage], the reckoning that feeds them), those that would die of it and the young that
     * would not be born ([keepHerds]' rule, its birth season taken over the year), at their meat.
     */
    private fun herdLoss(g: HexGroup, b: Band, p: Place): Float {
        var kg = 0f
        for (k in b.herd.indices) {
            val n = b.herd[k]
            if (n <= 0f) continue
            val s = fauna.kinds[k]
            val short = 1f - herdForage(p, s, n).fed
            if (short <= 0f) continue
            kg += n * short * 0.5f * (1f + s.youngPerFemale * (0.3f + 0.7f * b.herdTame[k])) * years * s.massKg
        }
        return kg * MEAT_SHARE * Nourishment.MEAT * g.species.needScale / (g.dailyNeedKg.coerceAtLeast(1e-3f) * days)
    }

    /** Heads of kind slot [k] in place [p] as a group knows them: seen in its own hex and the six around, else expected. */
    private fun knownHeads(k: Int, p: Place, h: Hex, sensed: HexArray): Float = if (h in sensed) headsHere[k][p].toFloat() else expectHeads[k][p]

    /**
     * What place [p] offers a band for a camp, as game-weight per km²: the game it can hunt there, and
     * the fruit, nuts and roots it can gather (counted at their weight against meat), the more so where
     * the camp can stand on high, broken or water-girt ground safe from lions and raiders.
     */
    internal fun campValue(s: Species, p: Place): Float = preyHere(s, p) * campFactor(p) + campPlants(p)
    /**
     * What a camp's game is worth to a band, times: the more where the camp is safe, the less the further water is,
     * and the less the more of its days the hearth's firewood takes there ([fuelShare], for a band whose every mouth
     * fetches: the place's worth to bands at large). Where there is too little wood, the cold and the raw meals it
     * costs are in [drain].
     */
    internal fun campFactor(p: Place): Float {
        // Water close to camp: women and children fetch it every day (from a well dug in a dry bed, where water lies near).
        val w = land.drink.peopleWaterKm(p) / 3f
        val fuel = fuelShare(woodCover(p), 1f)
        return (1f + CAMP_SAFETY * land.bestDefence[p] + CAVE_CAMP * land.caves[p]) / (1f + w * w) * (1f - fuel)
    }
    internal fun campPlants(p: Place) = KgM2(fauna.food.fruit[p] + rootsFound(p) + fauna.food.seeds[p] + fauna.food.grain[p] + fauna.food.fieldGrain[p]).perKm2.v *
        PLANT_FOOD_WEIGHT * campFactor(p)
    /**
     * The roots at [p] as a band's gathering would find them this week, kg/m²: the thinner they grow, the more of a
     * gatherer's day goes to finding them ([ROOT_SEARCH_M2_PER_DAY]), so they count as the share of the day that is digging
     * (none while the ground is frozen, [HexFood.diggable]); and the mast lying under the trees, as the share of its days
     * that is gathering it, the rest going to leaching its bitter share ([MAST_LEACH_DAYS_PER_KG]). (A hand's work at the
     * start rate, without skill or baskets: an approximation of [forage]'s own rate.)
     */
    internal fun rootsFound(p: Place): Float {
        val kg = fauna.food.diggable(p)
        val dug = ROOT_DIGGING * PLANT_HANDLING_DAYS_PER_KG * ROOT_SEARCH_M2_PER_DAY * kg
        return kg * dug / (1f + dug) + fauna.food.mast[p] * MAST_GATHERED
    }

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

    /**
     * The model's clock: the step [date] falls in (what the groups' "weeks" count: a step of [days] days), and how
     * many steps make a year, for counts of weeks compared against spans of years.
     */
    internal fun stepOf(date: Date) = (date.absoluteDay / days).toInt()
    internal val stepsPerYear get() = Date.stepsPerYear(days).toFloat()

    /** Packs of kinds that follow people, by the hex they end the week in (for taming). */
    internal var followersByHex: Map<Hex, List<HexGroup>> = emptyMap()
    /** Each living band's hex by its id, as of this week's camps (for visiting, see [cultureWeek]). */
    internal var bandHex: Map<Int, Hex> = emptyMap()
    /** Where each living band with a standing house has it, by the band's id (set each week before the bands' own weeks). */
    internal var houseOf: Map<Int, Hex> = emptyMap()

    /** How big a group of [g]'s kind grows before it splits: packs and prides keep their kind's size, bands as their ways hold them together. */
    internal fun maxSize(g: HexGroup): Int {
        val s = g.species
        return g.band?.let { b ->
            // Feasts keep the peace in bigger bands; a settled band without them quarrels and splits sooner.
            if (b.holds(Tradition.FEASTS)) (s.maxGroupSize * FEAST_SIZE).toInt()
            else if (b.atHome) (s.maxGroupSize * SETTLED_QUARRELS).toInt() else s.maxGroupSize
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

    /** Choices made in planning, by kind: (slot 0 unused: every group weighs every week), weighed and stayed, weighed and moved. */
    val planCounts = AtomicIntegerArray(64 * 3)


    /** Strongest hunting party in each place last week, and its kind's index (-1 for none). */
    internal val topStrength = FloatArray(n)
    /** The flesh of the groups of beasts that take people in each hex this week, kg/km², by how readily they do ([stalkRate]). */
    internal val manEaterKm2 = FloatArray(hex.size)
    internal val topKind = IntArray(n) { -1 }

    /** Each kind's heads per km² of ground that suits it fully, as it usually is (kept over about a year). */
    val usualDensity = FloatArray(kinds.size)
    /** Hunters' weight per km² of land, kg, as it usually is (kept over about a year). */
    var usualHuntersKm2 = 0f
    /** Heads of each kind a place may be expected to hold, by how well it suits the kind: what a group goes by where it has not looked. */
    private val expectHeads by lazy { Array(kinds.size) { FloatArray(n) } }
    /** The month [expectHeads] was reckoned for. */
    private var expectMonth = -1

    /** A hunting party's fighting strength: weight, ferocity. */
    internal fun strength(g: HexGroup) = g.adults * g.species.massKg * ferocity(g)
    internal fun ferocity(g: HexGroup) = g.band?.ferocity(g.species) ?: g.species.ferocity

    /** A week's plan: the [place] a group heads for and the hexes of its [path] there. */
    class Plan(val place: Place, val path: HexArray) { operator fun component1() = place; operator fun component2() = path }

    /**
     * Where [g] goes this week: the reachable place it likes best, and the hexes it passes on the way.
     * Reach is a few hexes when content; a forced march when hungry, cold, or heading for its season's
     * place. Hunters keep to their ground unless starving.
     */
    internal fun plan(g: HexGroup, weather: Weather, date: Date, predatorKg: FloatArray): Plan {
        var pt = Profile.mark()
        val r = planInner(g, weather, date, predatorKg) { name -> pt = Profile.lap(name, pt) }
        Profile.lap("choosing: route", pt)
        return r
    }

    /**
     * Least effort to each hex within [reach] of [here] for [g] this week, into [buf] (its dist, via and
     * reached): from a search shared with groups of its kind starting from the same place that reach as
     * far or further, else from a new one (then shared).
     */
    private fun searchReach(g: HexGroup, here: Hex, reach: Float, restless: Boolean, weather: Weather, buf: Scratch) {
        val s = g.species
        val dist = buf.dist; val via = buf.via; val reached = buf.reached
        for (i in 0 until reached.size) { val h = reached[i]; dist[h] = Float.MAX_VALUE; via[h] = Hex.NONE }
        reached.clear()
        val q = buf.queue; q.clear()
        // Groups of a kind starting from the same place this week share the search.
        // Each group's reach is its own (hunger, the band's weak), so a search is shared only
        // with groups that reach no further, and each takes the hexes within its own reach: least-effort
        // distances within a reach do not depend on how far the search went beyond it. The hexes are kept
        // in index order, so the choice does not depend on which group's search was kept.
        val reachKey = ((s.index.toLong() * 8 + hw.placeBand[g.place]) * hex.size + here.i) * 2 + (if (restless) 1 else 0)
        var shared = reachCache[reachKey]
        if (shared == null || shared.reach < reach) {
            dist[here] = 0f; reached += here; q += 0L.withPlace(0f, here)
            // (The effort of each crossing this week, through snow and along worn paths: see [stepEffort].)
            val effort = stepEffort(Catalogue.travel(s).ordinal, hw.placeBand[g.place], weather)
            while (q.isNotEmpty()) {
                val e = q.poll()
                val d = Float.fromBits((e ushr 32).toInt()); val h = Hex((e and 0xffffffffL).toInt())
                if (d > dist[h]) continue
                for (k in 0 until 6) {
                    val nh = hex.neighbour(h, k)
                    if (nh.none) continue
                    val nd = d + effort[hex.edge(h, k)]
                    if (!nd.isFinite() || nd > reach) continue
                    if (nd < dist[nh]) { if (dist[nh] == Float.MAX_VALUE) reached += nh; dist[nh] = nd; via[nh] = h; q += 0L.withPlace(nd, nh) }
                }
                // Swimmers may cross a strait they can cover, at a great effort.
                val swim = Catalogue.swimKm(s)
                if (swim > 0f) for (i in seaLinks[h].indices) { val nh = seaLinks[h][i]
                    val gapKm = seaGaps[h][i]
                    if (gapKm > swim) continue
                    val nd = d + gapKm * SWIM_EFFORT
                    if (nd > reach) continue
                    if (nd < dist[nh]) { if (dist[nh] == Float.MAX_VALUE) reached += nh; dist[nh] = nd; via[nh] = h; q += 0L.withPlace(nd, nh) }
                }
            }
            val hs = reached.toHexArray().also { it.sort() }
            val mine = Reach(reach, hs, FloatArray(hs.size) { dist[hs[it]] }, hexArray(hs.size) { via[hs[it]] })
            shared = reachCache.merge(reachKey, mine) { a, b -> if (a.reach >= b.reach) a else b }!!
            for (i in 0 until reached.size) { val h = reached[i]; dist[h] = Float.MAX_VALUE; via[h] = Hex.NONE }
            reached.clear()
        }
        for (i in shared.hexes.indices) {
            if (shared.dist[i] > reach) continue
            val h = shared.hexes[i]; reached += h; dist[h] = shared.dist[i]; via[h] = shared.via[i]
        }
    }

    /**
     * The features of place [p] (in hex [h]) for the group weighing its places in [c], into [f] (cleared): what a learned
     * policy sees of it ([Feature]). What it knows of the place ([PlanContext.at]) is worked out in [planInner] as the
     * hand-written score weighs it.
     */
    private fun fillFeatures(f: FloatArray, c: PlanContext, t: PlaceTerms, p: Place, h: Hex) {
        val d = c.at.d; val toGoal = c.at.toGoal; val pull = c.at.pull; val beyond = c.at.beyond; val foreign = c.at.foreign
        val prey = c.at.prey; val crowded = c.at.crowded; val hunters = c.at.hunters
        val g = c.g; val s = g.species; val here = c.here; val month = c.month; val reach = c.reach; val goal = c.goal
        val fromHere = c.fromHere; val weather = c.weather; val date = c.date
        // (The herds' slots, [Feature.HERDS_ABOUT], [Feature.KEEPS_HEADING] and [Feature.FORAGE_QUALITY], stay 0: kept for the
        // networks' layout until they are trained again.)
        // What the place offers it, as the baseline weighs it: game, and for bands plant food and a safe camp.
        f[Feature.FOOD] = prey / (prey + PREY_HALF)
        f[Feature.CLIMATE] = t.suitsLn / 2f
        // (What the coming months are usually like there.)
        for (k in 0 until Feature.LORE_MONTHS)
            f[Feature.USUAL_AHEAD + k] = weather.usualTemperature((month + k + 1) % Date.MONTHS_PER_YEAR, p) / 20f
        f[Feature.CROWDING] = crowded
        f[Feature.HUNTERS] = hunters / (hunters + 3f)
        f[Feature.DISCOMFORT] = min(2f, t.discomfort + t.drain)
        // (The flies are felt now; only the weather is foreseen.)
        f[Feature.DISCOMFORT_FORECAST] = min(2f, t.aheadC / 10f + t.drain)
        // People see only what they can read: the weather coming as their lore and sky-watching
        // allow (else they expect this week's again), the beasts about as their tracking does,
        // and the food of country they do not know only dimly.
        g.band?.let { b ->
            val sense = ((if (b.holds(Tradition.SKY_LORE)) 0.5f else 0f) + 0.5f * b.skill(Skill.LORE)).coerceIn(0f, 1f)
            f[Feature.DISCOMFORT_FORECAST] = f[Feature.DISCOMFORT] + sense * (f[Feature.DISCOMFORT_FORECAST] - f[Feature.DISCOMFORT])
            f[Feature.HUNTERS] = SIGN_GUESS + b.skill(Skill.TRACK) * (f[Feature.HUNTERS] - SIGN_GUESS)
            if (!b.knows(h)) f[Feature.FOOD] = 0.5f * f[Feature.FOOD] + 0.5f * SIGN_GUESS
        }
        f[Feature.SNOW_OVER_LIMIT] = if (weather.snowpack[p] > s.snowLimitMm) 1f else 0f
        f[Feature.SNOW] = min(2f, weather.snowpack[p] / s.snowLimitMm)
        // (What the hand-written score weighs besides: snow coming with a freeze, as keenly as its kind foresees it; fire;
        // the fear of ground where game was killed lately; and a much stronger hunter of another kind holding the place.)
        if (weather.forecastTemp(p.i) < 0f && weather.snowpack[p] + weather.forecastRain(p.i) > s.snowLimitMm) f[Feature.SNOW_AHEAD] = s.foresight
        f[Feature.BURNING] = land.fire.burning[p]
        f[Feature.KILL_FEAR] = fauna.afraid(p)
        if (hex.distanceKm(h, here) <= hex.hexKm * 1.01 && topKind[p] >= 0 && topKind[p] != s.index)
            f[Feature.STRONGER_HUNTER] = min(1f, topStrength[p] / (4f * strength(g)).coerceAtLeast(1e-3f))
        f[Feature.CLIMB] = ((hw.placeElevationM[p] - hw.placeElevationM[g.place]) / 1000f).coerceIn(-3f, 3f)
        f[Feature.EFFORT] = (d / reach.coerceAtLeast(1f)).coerceIn(0f, 1f)
        f[Feature.STAYS] = if (p == g.place) 1f else 0f
        f[Feature.SAME_HEX] = if (h == here) 1f else 0f
        if (goal >= 0) {
            f[Feature.TOWARD_BEARING] = if (fromHere > hex.hexKm) ((fromHere - toGoal) / fromHere).toFloat().coerceIn(-1f, 1f) else 0f
            f[Feature.SEASON_PLACE] = (c.goalQ - g.fed).coerceIn(-1f, 1f)
            // (On its round, how far the place takes it on toward the round's place this month: see [SharedMover.pull].)
            if (c.onRound) f[Feature.ON_ROUND] = f[Feature.TOWARD_BEARING] * c.roundStrength
        }
        f[Feature.FED] = g.fed
        f[Feature.FAT] = g.fat / s.maxFatDays.coerceAtLeast(1f)
        f[Feature.SEASON_SIN] = sin(c.season).toFloat(); f[Feature.SEASON_COS] = cos(c.season).toFloat()
        f[Feature.RESTLESS] = if (c.restless) 1f else 0f
        f[Feature.WATER_DISTANCE] = min(3f, t.waterKm / s.dailyTravelKm)
        f[Feature.DAYS_DRY] = min(2f, g.dry / s.daysWithoutWater)
        f[Feature.PULL] = pull
        // The moon (night hunters see and are seen by it) and how far off its young are due.
        f[Feature.MOON] = moon
        val toBirth = 2.0 * Math.PI * monthsFrom(month, if (g.birthMonth >= 0) g.birthMonth else s.birthMonth) / 12.0
        f[Feature.TO_BIRTHS_SIN] = sin(toBirth).toFloat(); f[Feature.TO_BIRTHS_COS] = cos(toBirth).toFloat()
        // Its ancestors buried here, and how far going here brings it toward its appointment.
        f[Feature.ANCESTORS] = min(1f, g.memory.deadAt(h) / 10f)
        f[Feature.APPOINTMENT] = g.band?.let { appointPull(g, it, month, h) } ?: 0f
        f[Feature.HOUSES] = if (g.band != null) housesAt(g, h) else 0f
        f[Feature.OWN_SIZE] = ln(g.size.toFloat().coerceAtLeast(1f) / unitSize(s)) / 2f
        f[Feature.LASTING_WATER] = min(1f, land.drink.nearRiver[p] + land.drink.nearLake[p] + land.drink.nearSpring[p])
        f[Feature.SICKNESS] = min(1f, fauna.outbreakDeaths(s, p) * 10f)
        // The signs of the land, as anyone who looks can read them.
        f[Feature.TREES] = min(1f, land.plants.tree[p] / 10f); f[Feature.SHRUBS] = min(1f, land.plants.shrub[p] / 2f)
        f[Feature.GRASS] = min(1f, land.plants.herb[p] / 0.5f)
        f[Feature.MARSH] = min(1f, land.waterlogged[p])
        f[Feature.WATER_AGAINST_USUAL] = (land.drink.water[p] - min(1f, land.drink.nearRiver[p] + land.drink.nearLake[p] +
            land.drink.nearSpring[p])).coerceIn(-1f, 1f)
        f[Feature.PATHS] = wear.at(h)
        f[Feature.DUNG] = min(1f, fauna.small.dung[p] * 1e4f)
        f[Feature.GRAZING_AND_KILLS] = min(1f, (land.plants.grazing[p] + land.plants.browsing[p]) / 1000f) +
            min(1f, KgM2(fauna.small.carrion[p] + fauna.small.bones[p]).perKm2.v / 20f)
        f[Feature.KNOWN_GROUND] = g.band?.let { if (it.knows(h)) 1f else 0f } ?: 1f
        f[Feature.BEYOND_OWN_GROUND] = beyond; f[Feature.OTHERS_MARKS] = foreign
        // The fish and shellfish a band could take there, with its skill, against its need (the
        // shore and the river are plain to see; how rich they are, less so where it is a stranger).
        f[Feature.FISH] = g.band?.let { b -> min(2f, fauna.water.bandCatchWithin(h, b.fishing()) * Nourishment.FISH * s.needScale / (g.size * s.dailyIntakeKg * days).coerceAtLeast(1f)) *
            (if (b.knows(h)) 1f else 0.5f) } ?: 0f
        // What it remembers of this hex: in this season, or failing that in any.
        val mem = g.memory
        var slot = mem.find(h, PlaceMemory.seasonOf(month)); val sameSeason = slot >= 0
        if (slot < 0) for (i in 0 until PlaceMemory.MEMORY_SLOTS) if (mem.hex[i] == h) { slot = i; break }
        if (slot >= 0) {
            f[Feature.REMEMBERED] = 1f; f[Feature.REMEMBERED_SAME_SEASON] = if (sameSeason) 1f else 0f
            f[Feature.REMEMBERED_FED] = mem.fed[slot]; f[Feature.REMEMBERED_WATER] = mem.water[slot]; f[Feature.REMEMBERED_DANGER] = mem.danger[slot]
            f[Feature.REMEMBERED_AGE] = min(1f, (stepOf(date) - mem.lastWeek[slot]) / stepsPerYear)
        }
        // What it remembers finding there that it lacks, and how far the place takes it toward the best of that it knows.
        c.lacked?.let { lacked ->
            var most = 0f
            for (i in 0 until PlaceMemory.MEMORY_SLOTS) if (mem.hex[i] == h) for (k in lacked.indices) most = max(most, lacked[k] * mem.foundAt(i, k))
            f[Feature.REMEMBERED_LACKED] = most
            if (!c.lackedHex.none && c.lackedFrom > hex.hexKm)
                f[Feature.TOWARD_LACKED] = ((c.lackedFrom - hexKm(h, c.lackedHex)) / c.lackedFrom).toFloat().coerceIn(-1f, 1f) * c.lackedQ
        }
    }

    private fun planInner(g: HexGroup, weather: Weather, date: Date, predatorKg: FloatArray, lap: (String) -> Unit): Plan {
        val s = g.species
        val here = hw.placeHex[g.place]
        // What it sees of the others: in its own hex and the six around, which its week's ranging covers.
        // Beyond, it goes by what it expects (see [expect]).
        val sensed = hexArray(7) { if (it == 0) here else hex.neighbour(here, it - 1) }
        // How far it will shift its ground this week. Most of a week's walking is within its range
        // (grazing, patrolling); the net shift is a couple of days' going when content, more on a trek
        // when hungry, cold or snowed in. A pack holding ground patrols it; a starving or homeless one roams.
        val thirsty = g.dry > 0.5f * s.daysWithoutWater
        // The place a band remembers doing best at this time of year: while it is far off, the band is on its way.
        val month = wrapMonth(date.month + if (date.dayOfMonth > Date.DAYS_PER_MONTH / 2) 1 else 0)
        // (Its round, if its rites keep one, else the place it remembers doing best at this time of year.)
        // (Within [inflation.Tuning.roundArrivedKm] of the round's place it has arrived, and goes about it as it likes.)
        val round = roundOf(g, month)?.takeIf { hexKm(here, hw.placeHex[it.place]) > tuning.roundArrivedKm }
        val onRound = round != null
        val goal = round?.place ?: g.band?.seasonPlace?.get(month) ?: -1
        val remembered = g.band?.seasonQ?.get(month) ?: 0f
        val goalQ = if (round != null) max(remembered, round.strength) else remembered
        val roundStrength = round?.strength ?: 0f
        val goalFar = goal >= 0 && s.human && hexKm(here, hw.placeHex[goal]) > 2 * hex.hexKm
        // Far out of its country, it treks.
        val astray = bearings.strength(kindSlot[s.index], here) > ASTRAY
        // (A band whose appointment falls this month or next, and is not there, sets off: it is restless.)
        val dueSoon = g.band?.let { b -> b.appointment.let { ap -> ap != null && ap.hex != here && monthsFrom(month, ap.month) <= 1 } ||
            b.unluckyHex == here || b.raid != null } ?: false
        val restless = restless(g, weather, thirsty, astray, dueSoon, onRound, goalFar)
        // (Every group weighs its places every week.)
        val capture = control != null && g.id == watched
        // (A band goes at the pace of its slowest: the old, the sick and the lamed hold it back.)
        val weak = weakShare(g)
        val reach = (if (restless) s.maxDailyTravelKm else s.dailyTravelKm) * SHIFT_DAYS * (1f - WEAK_SLOW * weak) *
            (if (s.human) 1f / (1f + COVER_GOING * land.plants.cover(g.place)) else 1f)
        val kind = kindSlot[s.index]
        // Least effort to each hex within reach.
        val buf = scratch.get()
        val dist = buf.dist; val via = buf.via; val reached = buf.reached
        searchReach(g, here, reach, restless, weather, buf)
        lap("choosing: reach (least-effort search)")
        var best = g.place; var bestScore = Float.NEGATIVE_INFINITY
        val policy = policies[s]
        // (For a followed group: every place it weighed, and how it scored.)
        // planDiag=kind: once a month, one group's reasons, for the place it chose and the best-placed step toward its season's place.
        val diag = PLAN_DIAG == s.name && goal >= 0 && date.dayOfMonth <= days && g.id % 97 == 0
        val seen = if (g.id == watched || diag) ArrayList<Pair<Int, Float>>() else null
        val candPlaces = buf.candPlaces; candPlaces.clear(); val candFeats = buf.candFeats
        // (Every place's whole score is wanted for a network, for capture and for a watched group; else only the best.)
        // (A band draws its place by its will to explore, from every place it weighs, see [exploreChoice].)
        val explores = g.band != null
        val wantAll = seen != null || policy != null || capture || explores
        buf.laterPlace.clear(); buf.explPlaces.clear()
        val season = 2 * Math.PI * (date.dayOfYear - Date.START_DAY) / Date.DAYS_PER_YEAR
        // Its ground is as big as the game on it needs it to be (smaller where game is thick), and a
        // hungry pack ranges wider: up to three times that after weeks of hunger.
        val homeValue = if (!g.home.none) placeValue[kind][hw.placeNear(g.place, g.home).takeIf { !it.none }?.i ?: g.place.i] else PREY_HALF
        // (A pack with young in the den, from its births for [inflation.Tuning.denMonths], hunts only
        // within [inflation.Tuning.denKm] of its home, however hungry: it cannot follow game that moves on.)
        val denning = s.lifeway == Lifeway.PACK && !g.home.none && g.young > 0 && monthsFrom(g.birthMonth, month) < tuning.denMonths
        val territory = if (denning) tuning.denKm else territoryKm(s) * sqrt(PREY_HALF / homeValue.coerceAtLeast(1f)).coerceIn(0.5f, 3f) *
            (1f + min(2f, g.lean.toFloat() / GIVE_UP_WEEKS))
        val goalHex = if (goal >= 0) hw.placeHex[goal] else Hex.NONE
        val fromHere = if (goal >= 0) hexKm(here, goalHex) else 0.0
        val pullKm = reach.toDouble().coerceAtLeast(hex.hexKm)
        // (A band keeps off the known homes of bands it is not close to: those hexes, once for the week.)
        val keepOff = g.band?.let { b -> b.knownHomes.entries.filter { b.tie(it.key) <= 0f }.map { it.value }.toHexArray() }
        // A band: how far it lacks each of the rarer materials, and the remembered place with the most of what it lacks.
        val lacked = g.band?.let { b -> FloatArray(PlaceMemory.RARE.size) { k -> lacking(g, b, PlaceMemory.RARE[k]) } }
        var lackedHex = Hex.NONE; var lackedQ = 0f
        if (lacked != null) { val mem = g.memory
            for (i in 0 until PlaceMemory.MEMORY_SLOTS) if (!mem.hex[i].none) for (k in lacked.indices) {
                val q = lacked[k] * mem.foundAt(i, k)
                if (q > lackedQ) { lackedQ = q; lackedHex = mem.hex[i] }
            } }
        val c = PlanContext(g, kind, here, sensed, month, goal, goalQ, onRound, roundStrength, fromHere, reach, thirsty, restless, season, keepOff,
            weather, date, goalHex, predatorKg, buf, lacked, lackedHex, lackedQ, if (!lackedHex.none) hexKm(here, lackedHex) else 0.0)
        val at = c.at
        for (ri in 0 until reached.size) {
            val h = reached[ri]
            val d = dist[h]
            at.d = d
            // How far beyond its ground the hex lies (0 within it), and how fresh another pack's mark on it is.
            val beyond = if (s.lifeway == Lifeway.PACK && !g.home.none) (hexKm(h, g.home) / territory - 1.0).toFloat().coerceIn(0f, 2f) else 0f
            val foreign = if (s.lifeway == Lifeway.PACK && h != g.home) scent.foreign(s, h, g.id) else 0f
            at.beyond = beyond; at.foreign = foreign
            // A pack on its own ground stays on it while fed, and keeps off ground another pack of its kind holds
            // (its choice: the hand-written score weighs these heavily; a learned one sees both, see [HexFeatures]).
            at.offGround = (if (beyond > 0f && (denning || (g.fed >= Thresholds.HUNGRY && !astray))) KEEP_TO_GROUND else 0f) +
                (if (foreign > MARK_FRESH && g.fed >= 0.3f) KEEP_OFF_MARKS else 0f)
            at.toGoal = if (goal >= 0) hexKm(h, goalHex) else 0.0
            // The open water on the way there, as the share of it a crossing drowns (see [move]): worked out when first wanted.
            var drowning = -1f
            // Better country further off: the way the kind's sense of the land points.
            at.pull = bearings.along(kind, here, h, pullKm)
            // (Its own kind about there, as it knows it.)
            val kin = kinAround(g, kind, h, sensed)
            val crowded = crowdedBy(s, kin)
            at.crowded = crowded
            for (pi in hw.placesOf(h)) {
                val p = Place(pi)
                val prey = knownGame(g, kind, p, h)
                val fed = foreseenFed(g, prey, kin, crowded) * saltFeedsAt(g, p, h)
                val hunters = huntersKm2(c, p, h)
                at.prey = prey; at.fed = fed; at.hunters = hunters
                val t = buf.terms.also { fillTerms(it, g, p, weather, date) }
                var score = scorePlace(c, t, p, h)
                // (Above all, it keeps alive: see below.)
                val beside = 0.02f * (hashNoise(g.id, p) - 0.5f) + temperPull(g, p, h, hunters, crowded)
                // (Where only the best place is wanted, its deaths are worked out after, best first, see below.)
                if (!wantAll) { buf.later(p, d, prey, fed, score, beside); continue }
                if (drowning < 0f) drowning = crossingDeaths(s, h, here, via)
                val alive = score
                score -= survivalSteps(g, deathsAt(c, p, h, fed, d) + drowning)
                if (!score.isFinite()) tellNotANumber(c, t, p, h, drowning, alive)
                if (explores) buf.explore(p, score + beside, alive - score)
                seen?.add(p.i to score)
                if (policy != null || capture) {
                    val nc = candPlaces.size
                    if (nc == candFeats.size) candFeats += FloatArray(Feature.size)
                    val f = candFeats[nc].also { it.fill(0f) }
                    fillFeatures(f, c, t, p, h)
                    if (nc == buf.candScores.size) buf.candScores = buf.candScores.copyOf(2 * nc)
                    candPlaces += p.i; buf.candScores[nc] = score
                    if (policy != null) continue
                }
                score += beside
                if (capture) buf.candScores[candPlaces.size - 1] = score
                if (score > bestScore) { bestScore = score; best = p }
            }
        }
        // Above all, it keeps alive: the share of it that would die at a place this week weighs against all the rest
        // (see [survivalSteps]). Its deaths only take away, so they are worked out best place first, and only while a place
        // could still beat the best so far: the same place is chosen as if every place's were (the first weighed, of equals).
        val nl = buf.laterPlace.size
        if (nl > 0) {
            val up = buf.laterUpper
            val order = (0 until nl).sortedWith { a, b -> if (up[a] != up[b]) up[b].compareTo(up[a]) else a.compareTo(b) }
            var bestK = Int.MAX_VALUE
            for (k in order) {
                if (up[k] < bestScore) break
                val p = Place(buf.laterPlace[k]); val h = hw.placeHex[p]
                val total = buf.laterScore[k] - survivalSteps(g, deathsAt(c, p, h, buf.laterFed[k], buf.laterD[k]) +
                    crossingDeaths(s, h, here, via)) + buf.laterBeside[k]
                if (!total.isFinite()) {
                    at.d = buf.laterD[k]; at.prey = buf.laterPrey[k]; at.crowded = Float.NaN; at.fed = buf.laterFed[k]
                    tellNotANumber(c, null, p, h, crossingDeaths(s, h, here, via), buf.laterScore[k])
                }
                if (total > bestScore || (total == bestScore && k < bestK)) { bestScore = total; best = p; bestK = k }
            }
        }
        lap("choosing: weighing places (hand-written score)")
        g.drawn = false
        if (policy != null && candPlaces.size > 0) {
            val nc = candPlaces.size
            best = Place(candPlaces[policy.choose(g, Array(nc) { candFeats[it] }, buf.candScores.copyOf(nc), policyRandom(g.id, date.absoluteDay))])
        }
        // (A choice the policy drew by chance to learn from stands; every other band draws by its will to explore, in training worlds as outside them.)
        if (explores && !g.drawn) best = explored(g, policy, buf, best, date)
        lap("choosing: network")
        // The hexes passed on the way to [p], by the cheapest chain.
        fun pathTo(p: Int): HexArray {
            val chain = ArrayList<Hex>(); var h = hw.placeHex[p]
            while (true) { chain += h; if (h == here) break; h = via[h].takeIf { !it.none } ?: break }
            chain.reverse(); return chain.toHexArray()
        }
        if (capture) best = Place(askControl(g, weather, date, policy, buf.candPlaces, buf.candFeats, buf.candScores, best.i,
            restless, reach, goal, onRound) { pathTo(it) })
        // (A band's ways read how its mover saw the place it goes to: see [HexGroup.plan].)
        if (g.band != null) {
            var k = -1
            if (policy != null) for (i in 0 until candPlaces.size) if (candPlaces[i] == best.i) { k = i; break }
            g.plan = if (k >= 0) policy?.planned(g, candFeats[k]) else null
        }
        if (seen != null && g.id == watched) logWatched(c, seen, best, policy != null)
        if (diag && seen != null) printPlanDiag(c, seen, best)
        planCounts.incrementAndGet(s.index * 3 + (if (best == g.place) 1 else 2))
        return Plan(best, pathTo(best.i))
    }

    /**
     * Where band [g] goes by its will to explore ([exploreChoice]), of the places weighed in [buf]: scored by its [policy]'s
     * scores (the network's), or without one by the hand-written; [best] where the scores alone would send it.
     */
    private fun explored(g: HexGroup, policy: HexPolicy?, buf: Scratch, best: Place, date: Date): Place {
        val n = buf.explPlaces.size
        val b = g.band ?: return best
        if (n == 0) return best
        val scores = if (policy == null) buf.explScores else policy.scores(g, Array(n) { buf.candFeats[it] }, buf.candScores.copyOf(n)) ?: return best
        val stays = (0 until n).firstOrNull { buf.explPlaces[it] == g.place.i } ?: -1
        val k = exploreChoice(g, b.explore, n, scores, buf.explDeaths, stays, if (policy == null) HAND_SCORE_UNIT else 1f, date.absoluteDay)
        return if (k < 0) best else Place(buf.explPlaces[k])
    }

    /**
     * The hunters [c]'s group expects at place [p] (in hex [h]), kg per km². A band reads the beasts of prey about, not
     * other people: those it weighs as bands. Where it has not looked it expects hunters as thick as they usually are,
     * people among them.
     */
    private fun huntersKm2(c: PlanContext, p: Place, h: Hex) =
        if (h in c.sensed) (if (c.g.species.human) beastKg else c.predatorKg)[p] / land.areaKm2[p] else usualHuntersKm2

    /** For the watched group ([watched]): the places it weighed ([seen], with their scores) and where it went ([best]), into [watchLog]. */
    private fun logWatched(c: PlanContext, seen: List<Pair<Int, Float>>, best: Place, byNetwork: Boolean) {
        val g = c.g
        fun where(p: Int) = "hex %d at %.0f m".format(hw.placeHex[p].i, hw.placeElevationM[p])
        val top = seen.sortedByDescending { it.second }.take(5)
        watchLog += "weighed %d places%s; went to %s (hand-written score %.2f%s)".format(seen.size, if (c.restless) ", restless" else "", where(best.i),
            seen.firstOrNull { it.first == best.i }?.second ?: 0f, if (byNetwork) ", chosen by the network" else "")
        for ((p, score) in top) watchLog += "   %s%s: %.2f".format(where(p), if (p == g.place.i) " (stay)" else "", score)
    }

    /**
     * planDiag=kind: one group's reasons ([c], the places it weighed in [seen]), for the place it chose ([best]), staying
     * and the best-placed step toward its season's place.
     */
    private fun printPlanDiag(c: PlanContext, seen: List<Pair<Int, Float>>, best: Place) {
        val g = c.g; val s = g.species; val kind = c.kind; val goalHex = c.goalHex; val reached = c.buf.reached
        // (The score is the hand-written one before its small noise and the group's temper, as [scorePlace] gives it.)
        fun parts(p: Place): String {
            val h = hw.placeHex[p]; val hunters = huntersKm2(c, p, h)
            val dying = deathsAt(c, p, h, 0f, c.buf.dist[h])
            return "score %.2f: deaths %.4f (%.1f steps) food %.2f crowd %.2f hunters %.2f kg/km2 water %.1f km going %.0f km (%.0f km from goal)".format(
                seen.firstOrNull { it.first == p.i }?.second ?: Float.NaN, dying, survivalSteps(g, dying), placeValue[kind][p],
                crowdedBy(s, kinAround(g, kind, h, c.sensed)), hunters,
                if (s.human) land.drink.peopleWaterKm(p) else land.drink.waterKm[p], c.buf.dist[h], hexKm(h, goalHex))
        }
        val towards = (0 until reached.size).map { reached[it] }.minByOrNull { hexKm(it, goalHex) }?.let { h ->
            hw.placeNear(g.place, h).takeIf { !it.none } }
        println("plan ${s.name} #${g.id} of ${g.size} month ${c.date.month + 1}, ${if (c.restless) "restless" else "content"}, reach %.0f km, weighed %d places: chose ".format(c.reach, seen.size) +
            parts(best) + (if (best != g.place) " | stay " + parts(g.place) else "") + (towards?.let { " | toward goal " + parts(it) } ?: ""))
    }

    /** How many places' hand-written scores have come out not a number this run (see [tellNotANumber]). */
    @Transient private val notANumber = java.util.concurrent.atomic.AtomicInteger()

    /**
     * Prints the first few places whose hand-written score for [c]'s group came out not a number (NaN or infinite), with
     * the parts it is made of: such a place is never chosen, by the hand-written rules or a network, so nothing else shows
     * it. [alive] is the score before its deaths, [drowning] the crossing's; [t] the place's terms (null where they were not kept);
     * the rest as [PlanContext.at] holds them.
     */
    private fun tellNotANumber(c: PlanContext, t: PlaceTerms?, p: Place, h: Hex, drowning: Float, alive: Float) {
        if (notANumber.incrementAndGet() > 20) return
        val g = c.g; val s = g.species
        val d = c.at.d; val prey = c.at.prey; val crowded = c.at.crowded; val fed = c.at.fed
        val deaths = deathsAt(c, p, h, fed, d)
        val thirst = thirstOdds(g, p, dryAfter(g, p, fed))
        val causes = StringBuilder()
        foreseenHazards(g, p, c.weather, fed, thirst, d) { cause, a, y ->
            if (a != 0f || y != 0f) causes.append(" ").append(cause).append(" ").append(a).append("/").append(y) }
        val terms = t?.let { "food value %s discomfort %s drain %s suits %s water %s km".format(placeValue[c.kind][p], it.discomfort, it.drain, it.suitsLn, it.waterKm) } ?: "not kept"
        println("not a number: ${s.name} #${g.id} of ${g.size} (${g.adults} grown, ${g.young} young, odds of dying ${g.deathOdds}, fed ${g.fed}) at place ${p.i}, " +
            "$d km of going: score before deaths $alive, deaths $deaths, drowning $drowning, steps ${survivalSteps(g, deaths + drowning)}; " +
            "foreseen fed $fed, thirst $thirst, crowded $crowded, prey $prey; " +
            "hazards:$causes; terms: $terms")
    }

    /**
     * What a group weighing its places this week brings to each of them: worked out once in [planInner], read by
     * [scorePlace], [fillFeatures], [deathsAt] and the diagnostics. [buf] is the thread's scratch: the reach search's
     * hexes and the place being weighed ([at]).
     */
    private class PlanContext(val g: HexGroup, val kind: Int, val here: Hex, val sensed: HexArray, val month: Int, val goal: Int, val goalQ: Float,
        val onRound: Boolean, val roundStrength: Float, val fromHere: Double, val reach: Float, val thirsty: Boolean, val restless: Boolean,
        /** The time of year as an angle, 0 at the year's start ([Date.START_DAY]). */
        val season: Double, val keepOff: HexArray?,
        val weather: Weather, val date: Date,
        /** The hex of its season's place or round ([goal]), else [Hex.NONE]. */
        val goalHex: Hex, val predatorKg: FloatArray, val buf: Scratch,
        /** For a band, how far it lacks each rarer material ([PlaceMemory.RARE]); the remembered hex with the most of what it lacks, how much, and how far off (km). */
        val lacked: FloatArray?, val lackedHex: Hex, val lackedQ: Float, val lackedFrom: Double) {
        /** Reused for each place it weighs: see [deathsAt]. */
        val odds = Odds()
        /** The place being weighed, as it knows it. */
        val at get() = buf.at
    }

    /**
     * What a group knows of the place it is weighing ([PlanContext.at]), filled in by [planInner] hex by hex and place by
     * place: the going there ([d], km), how far it would then be from its goal ([toGoal], km), how far beyond its own ground
     * ([beyond]) and how fresh another pack's marks ([foreign]), the cost of leaving its ground ([offGround]), the kind's
     * sense of better country that way ([pull]), the game it knows of ([prey]), its own kind's crowding ([crowded]),
     * the hunters about ([hunters], kg per km²) and how fed it foresees it would be ([fed]).
     */
    private class PlaceLook {
        var d = 0f; var toGoal = 0.0; var beyond = 0f; var foreign = 0f; var offGround = 0f; var pull = 0f
        var prey = 0f; var crowded = 0f; var hunters = 0f; var fed = 0f
    }

    /**
     * The hand-written score of place [p] (in hex [h]; what the group knows of it in [PlanContext.at]) for the group weighing its places in [c]:
     * its food or game, crowding and hunters, the cold, heat and flies, its own kind of country, what it senses coming,
     * water, the going, the season's place or round, its bearing, staying, and a band's own pulls.
     */
    private fun scorePlace(c: PlanContext, t: PlaceTerms, p: Place, h: Hex): Float {
        val d = c.at.d; val toGoal = c.at.toGoal; val offGround = c.at.offGround; val pull = c.at.pull
        val prey = c.at.prey; val crowded = c.at.crowded
        val g = c.g; val s = g.species; val here = c.here; val sensed = c.sensed; val month = c.month; val goal = c.goal
        val goalQ = c.goalQ; val onRound = c.onRound; val roundStrength = c.roundStrength; val fromHere = c.fromHere; val reach = c.reach
        val thirsty = c.thirsty; val keepOff = c.keepOff; val weather = c.weather
        // Its food or game there, against crowding (see [foodTerm]).
        var score = foodTerm(g, p, h, sensed, prey, crowded)
        // The cold or heat it bears there, and what the place drains from it (the extra food the
        // cold burns, the feeding or working time the flies take), weighed as the going is: in
        // shares of the week's need.
        score -= 0.5f * t.discomfort + t.drain + offGround
        // Its own kind of country: open plains for a cheetah, crags for a wild goat, whatever the food.
        if (!s.human) score += GROUP_HABITAT * t.suitsLn
        // What it senses coming, as keenly as its kind does: a cold snap or heat wave, snow
        // too deep to dig through, and in a dry spell the rain on its way.
        val tAhead = weather.forecastTemp(p.i)
        score -= 0.5f * s.foresight * t.aheadC / 10f
        if (tAhead < 0f && weather.snowpack[p] + weather.forecastRain(p.i) > s.snowLimitMm) score -= 0.5f * s.foresight
        if (land.hydro.soil[p] < DRY_SOIL_MM) score += 0.3f * s.foresight * min(1f, weather.forecastRain(p.i) / RAIN_AHEAD_MM)
        // Water: a thirsty group heads for it; any group keeps within reach of it, the more so the more often it must drink.
        // (People can dig for water in a dry bed where it lies near.)
        score -= (0.02f + 0.3f * g.dry / s.daysWithoutWater) * t.waterKm / s.dailyTravelKm
        if (thirsty && !canDrink(s, p)) score -= 1f
        if (weather.snowpack[p] > s.snowLimitMm) score -= 1f
        // The going costs food: a share of the week's need, the height gained as the level walk it equals (see [walkCost]).
        score -= WALK_COST * (d + climbKmPerM(s.massKg) * max(0f, hw.placeElevationM[p.i] - hw.placeElevationM[g.place.i])) / days
        // A band moving camp loses the days the walk takes from its week's work, beyond those it had to spare; the new camp
        // pays them back over the weeks it will keep it (as foragers weigh travel time against a patch's stay).
        g.band?.let { b -> score -= max(0f, moveDays(g, d) - b.spareDays / forageHands(g)) / (days * b.stayWeeks) }
        // (Above all, it keeps alive: its deaths there are weighed against all this by the caller, see [planInner].)
        // Bands remember where they did best each month and are drawn back there.
        if (goal >= 0 && s.human) {
            // The season's place draws a band on, the more so the better it promises and the worse it fares now.
            // Bands that watch the sky keep their round on time.
            val sky = if (g.band?.holds(Tradition.SKY_LORE) == true) 1.3f else 1f
            val prog = ((fromHere - toGoal) / min(fromHere, reach.toDouble())).toFloat().coerceIn(-1f, 1f)
            // (A round draws it on however well it fares where it is: it is what its rites keep in this month.)
            // (A hungry band stops to forage before going on: the round draws it the less the hungrier it is.)
            if (fromHere > hex.hexKm) score += if (onRound) sky * tuning.roundPull * roundStrength * (g.fed / 0.7f).coerceIn(0f, 1f) * prog
                else sky * SEASON_PULL * (0.3f * goalQ + (goalQ - g.fed).coerceIn(0f, 1f)) * prog
        }
        score += tuning.bearingPull * pull
        // (Staying is staying in the camp's hex: its other ground is the same camp, with the same fouling.)
        if (h == here) score += STAY - (g.band?.let { stayCost(it) } ?: 0f)
        // Bold animals of kinds that follow people are drawn to their camps: refuse, scraps, warmth.
        if (s.followsPeople && peopleSlot >= 0) score += CAMP_LURE * boldness(g) * min(1f, knownHeads(peopleSlot, p, h, sensed) / 25f)
        g.band?.let { b -> score += bandPull(g, b, month, h, here, p, keepOff, sensed, reach) }
        // A hungry pack gives up ground it has hunted out lately and looks further afield.
        if (g.fed < Thresholds.HUNGRY && (h == here || h in g.recent)) score -= HUNGRY_LEAVE
        return score
    }

    /**
     * The share of [c]'s group that would die this week at place [p] (in hex [h]), by the rules that
     * kill it there ([placeHazards], as [liveWeek] has them): hunger at how [fed] it foresees it would be ([foreseenFed]), thirst, the cold, fire, flood and deep snow,
     * plague, crocodiles, eagles, and for a band snakes and the beasts that take people (those of the groups about only
     * where it senses them). The young's odds count for the young. (What it shares wherever it goes, age, the background and its own band's ills, is
     * left out: it cannot tell places apart.)
     */
    private fun deathsAt(c: PlanContext, p: Place, h: Hex, fed: Float, km: Float): Float {
        val g = c.g; val s = g.species; val weather = c.weather; val b = g.band
        val thirst = thirstOdds(g, p, dryAfter(g, p, fed))
        val odds = c.odds.also { it.adult = 0f; it.young = 0f }
        foreseenHazards(g, p, weather, fed, thirst, km, odds)
        var adult = odds.adult; var young = odds.young
        if (b != null) {
            val venom = venomOdds(s, p, weather)
            // (At its houses it would sleep behind their walls.)
            val home = h == b.houseHex
            val walled = if (home) b.house * wallsAt(g, h) else 0f
            val stalked = stalkRate(g, b, p, residentManEaters(p) + (if (h in c.sensed) manEaterKm2[h] else 0f), walled,
                if (home) palisadeAt(g, h) else 0f)
            adult += venom + stalked; young += venom + YOUNG_STALKED / b.childWatch.coerceAtLeast(0.25f) * stalked
            dialUse?.amount(DialSite.CHILDREN_PLAN, b.childWatch, YOUNG_STALKED / b.childWatch.coerceAtLeast(0.25f) * stalked, YOUNG_STALKED * stalked, cut = b.childWatch < 0.25f)
            // The sicknesses it would catch there (marsh fever by the mosquitoes, the flux in a long-kept camp, the cough
            // where people crowd), and the fouling of a camp kept on: weeks camped there go on if it stays, else start afresh.
            val campWeeks = if (h == c.here) b.campWeeks + 1 else 0
            val crowd = peopleCrowding(g, h); val t = weather.temperature[p]; val healed = healedShare(b)
            adult += illnessAhead(b, p, t, crowd, campWeeks, healed, false)
            young += illnessAhead(b, p, t, crowd, campWeeks, healed, true) + campFouling(g, campWeeks) * years
            // What it would catch of the bands it sees gathered there (a feast draws bands together, and the cough with them).
            if (h in c.sensed && peopleSlot >= 0) {
                // (Bands meet over the whole hex, whichever ground each stands on.)
                val stays = h == c.here
                var heads = 0; for (q in hw.placesOf(h)) heads += headsHere[peopleSlot][Place(q)]
                val others = heads - (if (stays) g.size else 0)
                if (others > 0) for (ill in Illness.entries) if (ill.contagion > 0f) {
                    var sick = 0f; for (q in hw.placesOf(h)) sick += sickPeople[ill.ordinal][Place(q)]
                    val theirSick = (sick - (if (stays) g.size * b.sick[ill] else 0f)).coerceAtLeast(0f) / others
                    val dying = caughtFrom(ill, theirSick, b) * ill.deadly * healed
                    adult += dying; young += dying * ill.young
                }
            }
        }
        return (g.adults * adult.coerceIn(0f, 0.9f) + g.young * young.coerceIn(0f, 0.95f)) / max(1, g.size)
    }

    /**
     * How fed group [g] foresees it would be where it knows of [prey] (see [knownGame]) among [kinKm2] of its kind per km²
     * (itself counted; [crowded] by the one measure). A beast of prey by the residents' hunters' feeding rule: its kind
     * takes at most [HexFauna.RESIDENT_KILL_MOST] of the game in a week, shared by heads against their need. A band (whose
     * food is mostly gathered) still by how much there is against [PREY_HALF], less for crowding.
     */
    private fun foreseenFed(g: HexGroup, prey: Float, kinKm2: Float, crowded: Float): Float {
        val s = g.species
        if (s.human) return (prey / (prey + PREY_HALF) - 0.5f * crowded).coerceIn(0f, 1f)
        val needKg = s.dailyIntakeKg * g.warmth * days * (g.adults + 0.5f * g.young) / max(1, g.size)
        return min(1f, HexFauna.RESIDENT_KILL_MOST * prey * Nourishment.PREY * s.needScale / max(1e-6f, kinKm2 * needKg))
    }

    /**
     * What band [g]'s food would do for it at place [p] (in hex [h]) for its sodium ([saltFeeds], as its meal has it): as short
     * as it was last week, unless salt is to be had there (a salt pan or dry lake bed, the shore), which makes up the lack.
     */
    internal fun saltFeedsAt(g: HexGroup, p: Place, h: Hex): Float {
        val b = g.band ?: return 1f
        if (b.sodiumShort <= 0f || land.salt[p] > 0f || hw.coast[h]) return 1f
        return saltFeeds(b.sodiumShort, 1f, tuning.saltLack)
    }

    /** The odds of the hazards at a place, summed ([deathsAt]). */
    private class Odds : HazardSink {
        var adult = 0f; var young = 0f
        override fun add(cause: DeathCause, adult: Float, young: Float) { this.adult += adult; this.young += young }
    }

    /**
     * The share of a group of [s] that drowns on the way from hex [here] to hex [to] along the cheapest chain ([via]):
     * each strait crossed, as [move] drowns it (the young twice as many, counted here as grown).
     */
    private fun crossingDeaths(s: Species, to: Hex, here: Hex, via: HexArray): Float {
        var risk = 0f; var h = to
        while (h != here) {
            val from = via[h]; if (from.none) break
            val gap = seaGap(from, h)
            if (gap.isFinite()) risk += Hazards.drowning(s, gap)
            h = from
        }
        return min(0.9f, risk)
    }

    /**
     * Deaths [dying] (a share of [g] this week) in steps of the chance in a week's deaths: the spread of the share that dies
     * of a group of [g]'s size at the odds it lives under now (at least its kind's background). A difference within a step
     * is no more than chance and weighs as one unit of the rest of the score; one of many steps outweighs all of it.
     */
    internal fun survivalSteps(g: HexGroup, dying: Float) =
        Hazards.survivalSteps(dying, g.deathOdds, g.species.backgroundMortality * years, g.size.toFloat())

    internal fun hexKm(a: Hex, b: Hex): Double = hex.distanceKm(a, b)
    internal fun territoryKm(s: Species) = sqrt(max(1f, s.startKm2PerAnimal) * s.groupSize / Math.PI.toFloat()) + 5f

    internal fun discomfort(s: Species, p: Place, weather: Weather): Float {
        return s.discomfortC(weather.temperature[p], weather.date.month) / 10f
    }

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
     * The week's weather at place [p] as people's body heat meets it ([BodyHeat]), at [t] °C: the swing between night and day
     * under a sky as cloudy as the week's rain and snow make it, the wind at a person's height (less in woods and thickets), the
     * rain that wets clothes, and whether snow lies.
     */
    internal fun airAt(p: Place, weather: Weather, t: Float = weather.temperature[p]): Air {
        val snow = weather.snowpack[p] > BodyHeat.SNOW_LIES_MM
        val wind = hypot(weather.windEast(p.i), weather.windSouth(p.i)) * BodyHeat.BODY_WIND * (1f - BodyHeat.FOREST_CALM * land.plants.cover(p))
        return Air(t, Air.swingC(Air.opaque(weather.precipitation[p] / days), snow), wind, Air.wet(weather.rain(p.i), days), snow)
    }

    /**
     * The fire band [b] (group [g]) would keep camped at place [p]: its fire now; elsewhere the fire it keeps lit, as far
     * as the wood its hands can fetch there feeds it ([hearthShare], as [fetchFuel] fetches it). (A band that had no
     * wood at all this week counts on keeping a fire lit where there is wood.)
     */
    internal fun fireAt(g: HexGroup, b: Band, p: Place): Float {
        if (p == g.place) return b.fire
        val lit = if (b.hearthWood > 0f) min(1f, b.fire / b.hearthWood) else 1f
        return lit * hearthShare(woodCover(p), g.size.toFloat() / forageHands(g))
    }

    /**
     * The brush shelters band [b] (group [g]) would have camped at place [p], [km] off: its shelters now; elsewhere what it
     * puts up there in the days it had to spare, less the walk's days, as far as it aims to ([shelterDays]). (A band whose
     * walk takes its spare days sleeps in the open where it comes to: what being caught on the move in the cold costs.)
     * (Last week's spare days stand for this week's: an estimate.)
     */
    internal fun shelterAt(g: HexGroup, b: Band, p: Place, km: Float): Float {
        if (p == g.place) return b.shelter
        val people = g.adults.toFloat()
        if (people <= 0f) return 0f
        val left = max(0f, b.spareDays - moveDays(g, km) * forageHands(g))
        return min(b.shelterWill, left / shelterDays(b, people))
    }

    /** How far [g] camped at place [p] would be sheltered by houses there it may use (0 for none worth the name). */
    internal fun houseAt(g: HexGroup, p: Place) = housesAt(g, hw.placeHex[p]).takeIf { it > 0.05f } ?: 0f

    /**
     * What the biting insects at [p] cost a group of kind [s] this week, as a share of the working time
     * people lose to them. Hunters lose nothing to them here, so they do not mind them.
     */
    internal fun bitten(s: Species, p: Place): Float {
        val h = small.harassment(p)
        return when {
            h <= 0f -> 0f
            s.human -> HexSmallLife.HARASS_TIME * h / (1f + HexSmallLife.HARASS_TIME * h)
            else -> 0f
        }
    }

    /**
     * The heads of its own kind per km² group [g] (of kind slot [kind]) foresees about it in hex [h]: its kind there as
     * it knows it ([knownHeads]) and itself, over the ground it would range from there (the hex, and the six round it
     * where its kind's home range reaches beyond one: [rangesWide]), as [liveWeek] counts its kin where it is.
     */
    internal fun kinAround(g: HexGroup, kind: Int, h: Hex, sensed: HexArray): Float {
        val s = g.species; val own = hw.placeHex[g.place]
        var heads = 0f; var area = 0f
        for (d in -1 until (if (rangesWide(s)) 6 else 0)) {
            val q = if (d < 0) h else hex.neighbour(h, d)
            if (q.none) continue
            for (pp in hw.placesOf(q)) { heads += knownHeads(kind, Place(pp), q, sensed); area += land.areaKm2[pp] }
            if (q == own) heads -= g.size
        }
        return if (area > 0f) (max(0f, heads) + g.size) / area else 0f
    }

    /** How crowded kind [s] is among [kinKm2] of its own kind per km², 0..1: the one measure ([Hazards.crowding]) as x / (1 + x). */
    internal fun crowdedBy(s: Species, kinKm2: Float): Float {
        val x = Hazards.crowding(kinKm2, HexFauna.usualDensity(s))
        return x / (1f + x)
    }

    /** Whether kind [s]'s home range reaches beyond its hex, so that it counts its kind over the six round it too. */
    internal fun rangesWide(s: Species) = territoryKm(s) > 0.6f * hex.hexKm

    /**
     * What place [p] offers a hunter of kind [s] by its whole diet, kg/km²: the big game it can catch
     * ([game]), the small prey on the ground and the carrion lying there, each by its share of the
     * diet. A jackal looks for rodents and carrion, a lion for big game.
     */
    internal fun dietValue(s: Species, p: Place, game: Float): Float {
        // Small game is searched for over a narrower strip than big game, and carrion only by those that eat it.
        val smallWorth = smallCatch(s) * SMALL_SENSE_KM / (s.senseKm * SEARCHING * CATCH)
        return game + smallWorth * small.smallPreyKg(p) + (if (s.diet.carrion > 0f) KgM2(small.carrion[p]).perKm2.v else 0f)
    }

    /** The resident game hunter [s] sees at place [p], kg per km² ([HexFauna.game], as resident hunters count it). */
    internal fun preyHere(s: Species, p: Place): Float = fauna.game(s, p)

    /** Mean moonlight over this week's nights, 0 (new moon) to 1 (full). */
    internal val moon get() = fauna.moon

    /** Time spent settling meetings, ns, over the run (for measuring the cost). */
    @Transient var meetNs = 0L


    internal fun poisson(mean: Float, r: Random): Int = inflation.poisson(mean, r)
    internal fun binomial(n: Int, p: Float, r: Random): Int = inflation.binomial(n, p, r)

    /** A random stream of group [id]'s own for the week starting on [day]: the same whatever the cores do. */
    internal fun groupRandom(id: Int, day: Long) = Random(seedBase * 1_000_003L + id * 7_919L + day)
    /** A group's own stream for its choice of ground this week (apart from [groupRandom]'s, which its week's life draws on). */
    internal fun policyRandom(id: Int, day: Long) = Random((seedBase * 1_000_003L + id * 7_919L + day) * 0x9E3779B97F4A7C15uL.toLong() + 0x2545F4914F6CDD1DL)

    internal fun hashNoise(id: Int, p: Place): Float {
        return unitOf(mix64Once(id * HASH_A + p.i * HASH_B + seedBase * HASH_C))
    }
}

/** How well band [b] knows the plants of hex [h]'s country: [PLANT_FLOOR] for a stranger to 1. */
internal fun HexGroups.plantKnow(b: Band, h: Hex): Float {
    var s = 0f; var w = 0f
    for (k in Biome.entries) { val share = hw.biome[k.ordinal][h]; if (share > 0f) { s += share * b.plants[k]; w += share } }
    return PLANT_FLOOR + (1f - PLANT_FLOOR) * (if (w > 0f) s / w else 0f)
}

/** A week's gathering in hex [h] teaches band [b] its country's plants. */
internal fun HexGroups.learnPlants(b: Band, h: Hex) {
    var w = 0f
    for (k in Biome.entries) w += hw.biome[k.ordinal][h]
    if (w <= 0f) return
    for (k in Biome.entries) { val share = hw.biome[k.ordinal][h] / w; if (share > 0f) b.plants[k] += PLANT_LEARN * share * (1f - b.plants[k]) }
}
