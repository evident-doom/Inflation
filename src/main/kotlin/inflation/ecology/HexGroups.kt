package inflation.ecology

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
import inflation.world.HexWorld
import inflation.world.Weather
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.min
import kotlin.random.Random
import inflation.ecology.HexFeatures as Feature
import inflation.world.Biome
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.DoubleAdder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.stream.IntStream
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

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
    internal val tuning get() = land.tuning
    internal val rnd = Random(seed * 41 + 3)
    internal val seedBase = seed
    val groups = ArrayList<HexGroup>()
    internal var nextId = 0
    /** The kinds that live as groups: the migratory herds and the hunters other than people. For comparison runs, people=false leaves people out. */
    @Transient val kinds = kindsHere ?: (Catalogue.herbivores.filter { it in Catalogue.migratory } +
        Catalogue.predators.filter { it !in Catalogue.residentHunters && (!it.human || Config.flag("people", true)) })
    /** Each kind's sense of where better country lies: food or prey, its ground, water, and the climate this month. */
    val bearings by lazy {
        HexBearings(hw, kinds, { s, p, m ->
            val fit = fauna.monthFit(s, p, m)
            if (s.carnivore) {
                // The game it can catch there, resident herds and migratory ones alike, by weight.
                val prey = huntable[kindSlot[s.index]][p] +
                    if (s.human) KgM2(fauna.food.fruit[p] + fauna.food.roots[p] + fauna.food.seeds[p]).perKm2.v * PLANT_FOOD_WEIGHT else 0f
                // Hunters that live on the water in their prey (cheetahs, leopards) care less how far it is.
                val w = land.drink.waterKm[p] / (2f * s.dailyTravelKm) * (if (s.waterFromFood >= 0.8f) 0.3f else 1f)
                prey / (prey + PREY_HALF) * fit / (1f + w * w)
            } else herdQuality(s, p, m)
        }, { s -> groups.any { it.alive && it.species == s } }, tuning)
    }
    /** For each herd kind and month: how good each place was for it in that month last time round (see [herdQuality]). */
    private val qualityByMonth = HashMap<Int, Array<FloatArray?>>()
    /** As [qualityByMonth], for the rounds; and each herd kind's rounds as last grown (see [growRounds]). */
    internal val seasonNotes get() = qualityByMonth
    internal val herdRounds = HashMap<Int, List<IntArray>>()
    /** Each month, the herds' kinds note how good every place is for them now (a memory of the land's seasons). */
    internal fun noteSeasons(month: Int) {
        if (!ROUNDS) return
        for (s in kinds) {
            // (A kind that also lives spread out keeps noting them while none of it is gathered: its rounds are its gatherings' routes.)
            if (s.carnivore || (s !in Catalogue.gathering && groups.none { it.alive && it.species == s })) continue
            val q = FloatArray(n) { p -> herdQuality(s, p, month) }
            qualityByMonth.getOrPut(s.index) { arrayOfNulls(12) }[month] = q
        }
    }

    /** How good the forage at place [p] is for [s], 0..1: how nourishing and how rich in protein (see [HexFauna.forageProtein]). */
    internal fun freshness(s: Species, p: Int) = land.plants.nourishment(p) * fauna.forageProtein(s, p)

    /** How good place [p] is for a herd of [s] in month [m], 0..1: food, its ground, water, the month's climate, few hunters. */
    internal fun herdQuality(s: Species, p: Int, m: Int): Float {
        val food = foodFor(s, p) * min(1.5f, fauna.suits(s, p)) / 1.5f
        // Country thick with hunters is the less good for it.
        val hunters = monthHunterKg[p] / land.areaKm2[p]
        return saturate(food, Thresholds.FOOD_HALF) * freshness(s, p) * fauna.tether(s, p) * fauna.monthFit(s, p, m) *
            (1f - 0.5f * hunters / (hunters + tuning.huntersHalfKg))
    }

    /** Game each hunting kind can catch in each place, kg/km² weighed by catchability, worked out once a month for the bearings. */
    internal val huntable by lazy { Array(kinds.size) { FloatArray(n) } }
    private val monthHeads by lazy { Array(kinds.size) { IntArray(n) } }
    /** The migratory herds' heads in each place this month, all kinds together (from [monthHeads]; made afresh after a load). */
    @Transient private var monthHerdHeads = FloatArray(0)
    @Transient private var herdHeadsMonth = -1
    internal val monthHunterKg by lazy { FloatArray(n) }
    /**
     * The beasts of prey in each place this month, kg: the hunters other than people, what a band reads as danger in their
     * sign (made afresh after a load).
     */
    @delegate:Transient internal val monthBeastKg by lazy { FloatArray(n).also { a -> for (g in groups) if (g.alive && g.species.carnivore && !g.species.human) a[g.place] += g.size * g.species.massKg } }
    /** People sick of each catching illness in each place as the week's choosing begins (heads × share sick). */
    @delegate:Transient private val sickPeople by lazy { Array(Illness.entries.size) { FloatArray(n) } }
    /** The beasts of prey in each place as the week's choosing begins, kg (the hunters other than people). */
    @delegate:Transient private val beastKg by lazy { FloatArray(n) }
    /** The hunting parties (packs and bands) in each hex as the groups choose their ways, for the herds' choosing ([huntedAhead]). */
    @delegate:Transient private val huntersIn by lazy { HashMap<Int, ArrayList<HexGroup>>() }
    /** Animals of each herd kind ([Species.index]) the hunting parties killed this week ([hunterMeetsHerd]), counted into [usualGroupKill]. */
    internal val herdKilledByGroups = IntArray(Catalogue.all.size)
    /** The share of each herd kind's animals ([Species.index]) the hunting parties usually kill in a week, over about [USUAL_MONTHS]. */
    private val usualGroupKill = FloatArray(Catalogue.all.size)
    /** Herds of each kind ([Species.index]) the hunting parties chased this week ([hunterMeetsHerd]), counted into [usualGroupChase]. */
    internal val herdChasedByGroups = IntArray(Catalogue.all.size)
    /** The chases by hunting parties a herd of each kind ([Species.index]) usually meets in a week, over about [USUAL_MONTHS]. */
    private val usualGroupChase = FloatArray(Catalogue.all.size)
    @delegate:Transient internal val kindIndex by lazy { kinds.withIndex().associate { it.value to it.index } }
    /** Scent marks of the hunting kinds (saves from before it was split out kept them here as marks and markers). */
    @field:MovedInto("marks", "markers") internal val scent = Scent()
    /** Learned choosers by kind; kinds without one use the hand-written score. */
    val policies = HashMap<Species, HexPolicy>()
    /** How bands set their ways each week; null for the scaffold's. Not saved: what drives a run is set by the config (ways=), not carried in a save. */
    @Transient var ways: BandWays? = null
    /** How herds and packs set their ways each week ([AnimalWays]); null for the hand-written ways. */
    @Transient var herdWays: AnimalWays? = null
    @Transient var hunterWays: AnimalWays? = null
    /** This week's tallies ([HexLand.stats]). */
    val stats get() = land.stats
    internal val years = days / Date.DAYS_PER_YEAR.toFloat()
    internal val small get() = fauna.small

    init {
        // The residents swim the same straits.
        val pairs by lazy { Array(hex.size) { h -> seaLinks[h].indices.map { seaLinks[h][it] to seaGaps[h][it] } } }
        fauna.seaLinks = { h -> pairs[h] }
        // Start each kind at its usual density over the land that suits it, in groups of its usual size.
        val area = land.areaKm2.sum()
        for (s in kinds) {
            val per = if (s.startKm2PerAnimal > 0f) s.startKm2PerAnimal else 50f
            // At the density it settles at over a long run, where one is known.
            val settled = Catalogue.settledPer1000Km2(s)
            val unit = unitSize(s)
            val count = if (settled != null) max(if (settled > 0f) 1 else 0, (settled * area / 1000f / unit).toInt()) else (area / per / unit).toInt()
            val weights = if (uniformStart) FloatArray(n) { p -> land.areaKm2[p] } else startWeights(s)
            val total = weights.sum()
            repeat(count) {
                val p = drawPlace(weights, total)
                val g = spawn(s, p, unit)
                if (s.human) {
                    g.larder = START_LARDER_DAYS * g.dailyNeedKg
                    val (x, y) = hex.centreKm(hw.placeHex[p])
                    g.band = Band.start(fauna.kinds.size, land.drink.waterKm[p] < 3f, x, y, tuning).also { b ->
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
                if (s.pack) claimGround(g, Hex(hw.placeHex[p])) else inheritSeasons(g)
                groups += g
            }
        }
    }

    /**
     * Newcomers: once a year, a kind of herd or pack that has dwindled to a few groups (or died out)
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
            // (A kind that also lives spread out has its newcomers only if it is gone from the land too.)
            if (now >= NEWCOMER_BELOW || (s in Catalogue.gathering && fauna.slot(s) >= 0 && fauna.count(fauna.slot(s)) >= herdOf(s))) continue
            val weights = startWeights(s)
            val total = weights.sum(); if (total <= 0f) continue
            val unit = unitSize(s)
            repeat(NEWCOMERS) {
                val p = drawPlace(weights, total)
                val g = spawn(s, p, unit)
                // (Unlike at the start, any newcomer hunter, not only a pack, comes with ground of its own.)
                if (s.carnivore) claimGround(g, Hex(hw.placeHex[p])) else inheritSeasons(g)
                groups += g
            }
            stats.meetings.merge("newcomers: ${s.name}", NEWCOMERS, Int::plus)
        }
    }

    internal fun foodFor(s: Species, p: Int) = fauna.foodFor(s, p)

    /**
     * Where groups of [s] start or wander in: by area, how the ground and climate suit it, (for grazers and browsers) its
     * food, and water within its reach: in a usual year, or this week's ([now]: where a herd scatters).
     */
    private fun startWeights(s: Species) = FloatArray(n) { p -> startWeight(s, p) }
    internal fun startWeight(s: Species, p: Int, now: Boolean = false) =
        land.areaKm2[p] * fauna.suits(s, p) * fauna.climateSuits(s, p) * (if (s.carnivore) 1f else min(1f, foodFor(s, p) / 0.3f)) *
            (if (now) fauna.tether(s, p) else fauna.usualTether(s, p))

    /** A place drawn at random by [weights] (summing to [total]). */
    internal fun drawPlace(weights: FloatArray, total: Float): Int {
        var u = rnd.nextFloat() * total; var p = 0
        while (p < n - 1 && u > weights[p]) { u -= weights[p]; p++ }
        return p
    }

    /** A new group of [s] at place [p], [unit] adults and a third as many young, half-fat; a herd with its own taste in herd size. */
    internal fun spawn(s: Species, p: Int, unit: Int): HexGroup {
        val g = HexGroup(nextId++, s, Place(p), unit, max(0, unit / 3))
        g.temper = Temperament.founder(g.id, seedBase)
        if (!s.carnivore) g.sizePref = exp((rnd.nextFloat() * 2f - 1f) * ln(SIZE_PREF_RANGE))
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
     * Names the groups' stocks in [books]: the animals of each kind in herds, packs and bands (the young at a
     * resident's [HexFauna.YOUNG_WEIGHT]), the meat hunters hold from the week's kills, and bands' food, caches, kept
     * animals and the goods that come from living things (wood, fibre, resin, hide, bone, sinew).
     */
    internal fun listStocks(stocks: Stocks) {
        stocks.within("herds, packs and bands") { for (s in kinds) stocks.add(Books.Stuff.MATTER, "group ${s.name}") {
            var sum = 0.0; for (g in groups) if (g.alive && g.species == s) sum += (g.adults + HexFauna.YOUNG_WEIGHT * g.young) * s.massKg.toDouble(); sum } }
        stocks.add(Books.Stuff.MATTER, "hunters' meat in hand") {
            var sum = 0.0; for (g in groups) if (g.meatMet > 0f && !g.meatSpent) sum += g.meatMet * meatShare(g.band) / creditShare(g.band).toDouble(); sum }
        fun bands(f: (Band) -> Double) = run { var sum = 0.0; for (g in groups) if (g.alive) g.band?.let { sum += f(it) }; sum }
        stocks.add(Books.Stuff.MATTER, "bands' food") { var sum = 0.0; for (g in groups) if (g.alive && g.band != null) sum += massOf(g.stores, g.storeKgPer); sum }
        stocks.add(Books.Stuff.MATTER, "bands' caches") { bands { it.cacheMass().toDouble() } }
        stocks.add(Books.Stuff.MATTER, "bands' kept animals") { bands { b -> var sum = 0.0; for (k in b.herd.indices) sum += b.herd[k] * fauna.kinds[k].massKg; sum } }
        stocks.add(Books.Stuff.MATTER, "bands' goods of living things") { bands { it.livingGoods().toDouble() } }
    }

    /** The groups' part of the week, in order. */
    @Transient val phases = listOf(
        Phase("groups: choosing and moving") { chooseAndMove(it, it.date) },
        // The week, meeting by meeting: where two groups' timetables put them in the same hex on the
        // same days, the earlier meetings are settled first, by each side's standing orders.
        Phase("groups: meetings") { val t0 = System.nanoTime(); meet(it); meetNs += System.nanoTime() - t0 },
        // Herds coming on ground more crowded than they looked move on to the next ([spreadOut]).
        Phase("groups: spreading out") { spreadOut(it) },
        Phase("groups: leaving sign") { leaveSign() },
        Phase("groups: wearing paths") { wear.week(groups, hw, land, days); routes.week() },
        Phase("groups: grazing") { graze(it) },
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
        // Herds of the kinds that also live spread out scatter where their round stays a while, and gather where it leaves.
        Phase("herds: gathering and scattering") {
            // (Not in the form-swap test, where each kind keeps the form it has.)
            if (!Catalogue.formsFixed) {
                scatter(it.date.month)
                if (it.date.dayOfMonth <= days) gather(it.date.month)
            }
        },
        Phase("herds: remembering places") { val date = it.date
            if (date.dayOfMonth <= days) {
                noteSeasons(date.month)
                // The rounds are grown once a whole year's seasons are in, and again every [ROUND_YEARS] years;
                // herds without one (newcomers) take the nearest.
                if (ROUNDS) {
                    if (herdRounds.isEmpty() || (date.month == 0 && date.year % ROUND_YEARS == 0L)) growRounds(date.month)
                    for (g in groups) if (g.alive && !g.species.carnivore && g.route[0] == ROUND_OPEN)
                        herdRounds[g.species.index]?.let { takeRound(g, it, date.month) }
                }
            }
            remember(date)
        },
    )

    /**
     * The week's start: each group's warmth, the month's sense of where game is, the ways herds and packs set, the bands'
     * view of themselves; then each group plans and moves, in parallel, each by last week's world.
     */
    private fun chooseAndMove(weather: Weather, date: Date) {
        watchLog.clear()
        moon = date.meanMoonlight(days).toFloat()
        // Keeping warm costs food: each group's need this week, against the cold it feels (people
        // through their clothes and, at home, their houses).
        // (Raw meals give less: a band without fire needs more food for the same strength.)
        for (g in groups) if (g.alive) {
            val t = weather.temperature[g.place]
            g.warmth = coldNeed(g.species, g.band?.feels(t) ?: t, date.month, tuning) * (g.band?.let { 1f + RAW_COST * (1f - it.fire) } ?: 1f)
        }
        if (bearings.month != date.month) {
            // Head counts of the migratory herds in each place, for the hunters' sense of where game is.
            for (a in monthHeads) a.fill(0)
            for (g in groups) if (g.alive) monthHeads[kindSlot[g.species.index]][g.place] += g.size
            monthHunterKg.fill(0f)
            for (g in groups) if (g.alive && g.species.carnivore) monthHunterKg[g.place] += g.size * g.species.massKg
            monthBeastKg.fill(0f)
            for (g in groups) if (g.alive && g.species.carnivore && !g.species.human) monthBeastKg[g.place] += g.size * g.species.massKg
            IntStream.range(0, n).parallel().forEach { p ->
                for ((k, s) in kinds.withIndex()) {
                    if (!s.carnivore) continue
                    var h = preyHere(s, p)
                    // Herds on the move watch and are hard to come at: worth a share of their weight in game.
                    for ((j, ps) in kinds.withIndex()) if (!ps.carnivore && monthHeads[j][p] > 0) h += HERD_GAME * monthHeads[j][p] * ps.massKg *
                        catchable(s, ps, p) / land.areaKm2[p]
                    huntable[k][p] = dietValue(s, p, h)
                }
            }
            Profile.time("sense of direction") { bearings.update(date.month) }
        }
        val predatorKg = FloatArray(n)
        for (g in groups) if (g.alive && g.species.carnivore) predatorKg[g.place] += g.size * g.species.massKg
        beastKg.fill(0f)
        for (g in groups) if (g.alive && g.species.carnivore && !g.species.human) beastKg[g.place] += g.size * g.species.massKg
        huntersIn.clear()
        for (g in groups) if (g.alive && g.species.carnivore) huntersIn.getOrPut(hw.placeHex[g.place]) { ArrayList() } += g
        // (The herds' usual losses to the hunting parties: last week's kills against the heads they have now.)
        // (And the chases they usually meet: last week's chases against the herds there are now.)
        val herdHeads = IntArray(Catalogue.all.size); val herdCount = IntArray(Catalogue.all.size)
        for (g in groups) if (g.alive && !g.species.carnivore) { herdHeads[g.species.index] += g.size; herdCount[g.species.index]++ }
        val fade = days / (USUAL_MONTHS * Date.DAYS_PER_MONTH)
        for (i in herdHeads.indices) {
            if (herdHeads[i] > 0) {
                val now = herdKilledByGroups[i].toFloat() / herdHeads[i]
                usualGroupKill[i] = if (usualGroupKill[i] <= 0f) now else usualGroupKill[i] + (now - usualGroupKill[i]) * fade
                val chased = herdChasedByGroups[i].toFloat() / herdCount[i]
                usualGroupChase[i] = if (usualGroupChase[i] <= 0f) chased else usualGroupChase[i] + (chased - usualGroupChase[i]) * fade
            }
            herdKilledByGroups[i] = 0; herdChasedByGroups[i] = 0
        }
        for (a in sickPeople) a.fill(0f)
        for (g in groups) if (g.alive) g.band?.let { b -> for (d in Illness.entries) if (d.contagion > 0f) sickPeople[d.ordinal][g.place] += g.size * b.sick[d] }
        Profile.part("choosing: herds' grazing map") { fillGrazed(); noteGround(planGround) { g -> grazingRange(g) } }
        manEaterKm2.fill(0f)
        for (g in groups) if (g.alive && g.species.takesPeople > 0f)
            manEaterKm2[hw.placeHex[g.place]] += g.species.takesPeople * g.size * g.species.massKg / hex.areaKm2.toFloat()
        // The strongest hunting party in each place, and its kind: weaker hunters keep out of its way.
        topStrength.fill(0f); topKind.fill(-1)
        for (g in groups) if (g.alive && g.species.carnivore) {
            val st = strength(g)
            if (st > topStrength[g.place]) { topStrength[g.place] = st; topKind[g.place] = g.species.index }
        }
        // Herds and packs set their ways for the week (bands set theirs as they forage, see [hunt]).
        Profile.part("animals: setting their ways") { setAnimalWays(weather) }
        // Bands take stock of themselves and of the bands about them, for the people networks.
        Profile.part("bands: taking stock") { fillBandStates(weather) }
        // Plan and move, in parallel: each group's choice depends only on last week's world.
        if (herdHeadsMonth != bearings.month || monthHerdHeads.size != n) {
            monthHerdHeads = FloatArray(n) { p -> kinds.indices.sumOf { k -> if (kinds[k].carnivore) 0.0 else monthHeads[k][p].toDouble() }.toFloat() }
            herdHeadsMonth = bearings.month
        }
        Profile.part("choosing: valuing places for hunters") { valuePlaces(predatorKg, date.month) }
        reachCache.clear(); stepEffort.clear(); chosenPlace.clear(); chosenDying.clear()
        val choice = IntArray(groups.size); val paths = arrayOfNulls<IntArray>(groups.size); val times = arrayOfNulls<FloatArray>(groups.size)
        val crowds = FloatArray(groups.size) { -1f }
        // Herds of a kind in one place making for the same place this month, and alike in hunger and thirst,
        // go together: the first of them weighs where to go, and the others follow it (see [OWN_PLAN_WEEKS]).
        // Each still weighs for itself one week in [OWN_PLAN_WEEKS], by turns, so herds that met can part
        // again. Bands and packs each choose their own.
        val leaderOf = IntArray(groups.size) { -1 }
        run {
            val month = wrapMonth(date.month + if (date.dayOfMonth > Date.DAYS_PER_MONTH / 2) 1 else 0)
            val first = HashMap<Long, Int>()
            for ((k, g) in groups.withIndex()) {
                if (!g.alive || g.species.carnivore || g.id == watched) continue
                if ((date.absoluteDay / days + g.id) % OWN_PLAN_WEEKS == 0L) continue
                val goal = roundOf(g, month)?.place ?: g.seasonPlace[month]
                val state = (if (g.fed < Thresholds.HERD_HUNGRY) 1 else 0) + (if (g.dry > 0.5f * g.species.daysWithoutWater) 2 else 0)
                val key = ((g.species.index.toLong() * n + g.place.i) * (n + 1L) + (goal + 1)) * 4 + state
                val l = first.putIfAbsent(key, k); if (l != null) leaderOf[k] = l
            }
        }
        IntStream.range(0, groups.size).parallel().forEach { k ->
            val g = groups[k]
            if (!g.alive || leaderOf[k] >= 0) return@forEach
            // (With profile=fine, the choosing is timed by herds, packs and bands apart.)
            val plan = Profile.part(if (g.band != null) "choosing: bands" else if (g.species.carnivore) "choosing: packs" else "choosing: herds") { plan(g, weather, date, predatorKg) }
            choice[k] = plan.place.i; paths[k] = plan.path; crowds[k] = plan.crowd
            times[k] = Profile.part("choosing: timetable") { timetable(g.species, plan.path) }
        }
        for (k in groups.indices) { val l = leaderOf[k]; if (l >= 0) { choice[k] = choice[l]; paths[k] = paths[l]; times[k] = times[l]; crowds[k] = crowds[l]
            chosenPlace[groups[l].id]?.let { chosenPlace[groups[k].id] = it }; chosenDying[groups[l].id]?.let { chosenDying[groups[k].id] = it } } }
        if (deathDiag != null) for (k in groups.indices) { val l = leaderOf[k]; if (l >= 0) {
            foreseenDeaths[groups[l].id]?.let { foreseenDeaths[groups[k].id] = it }; foreseenFed[groups[l].id]?.let { foreseenFed[groups[k].id] = it }
            chosenDiag[groups[l].id]?.let { chosenDiag[groups[k].id] = it } } }
        // (Herds that did not weigh their places foresee the crowd where they are, as the week begins: see [spreadOut].)
        foreseen.clear()
        for ((k, g) in groups.withIndex()) if (g.alive && !g.species.carnivore) foreseen[g.id] = if (crowds[k] >= 0f) crowds[k] else crowdAt(g)
        for ((k, g) in groups.withIndex()) if (g.alive) {
            if (g.band != null && g.fed < Thresholds.HUNGRY)
                stats.meetings.merge(if (paths[k]!!.size <= 1) "hungry bands chose to stay" else "hungry bands chose to move", 1, Int::plus)
            // A herd's heading: what it kept of last week's, bent toward the way it went and the way its sense of better country points.
            if (!g.species.carnivore) {
                val from = hw.placeHex[g.place]; val to = hw.placeHex[choice[k]]
                val (x0, y0) = hex.centreKm(from); val (x1, y1) = hex.centreKm(to)
                val km = hypot(x1 - x0, y1 - y0)
                val kind = kindIndex[g.species] ?: 0
                val mE = if (km > 0.0) ((x1 - x0) / km).toFloat() else 0f; val mS = if (km > 0.0) ((y1 - y0) / km).toFloat() else 0f
                // (On a round, it bends toward the round's place this month rather than the sense of better country.)
                val round = roundOf(g, date.month)
                var bE = bearings.east[kind][to]; var bS = bearings.south[kind][to]
                if (round != null) {
                    val (rx, ry) = hex.centreKm(hw.placeHex[round.place]); val rk = hypot(rx - x1, ry - y1)
                    if (rk > hex.hexKm) { bE = ((rx - x1) / rk).toFloat(); bS = ((ry - y1) / rk).toFloat() } else { bE = 0f; bS = 0f }
                }
                var e = tuning.headingKeep * g.headE + (1f - tuning.headingKeep) * (tuning.headingMoved * mE + bE)
                var so = tuning.headingKeep * g.headS + (1f - tuning.headingKeep) * (tuning.headingMoved * mS + bS)
                val len = hypot(e, so); if (len > 1f) { e /= len; so /= len }
                g.headE = e; g.headS = so
            }
            g.place = Place(choice[k]); g.path = paths[k]!!; g.days = times[k]!!; g.meatMet = 0f; g.meatSpent = false; g.detourKm = 0f
            crossWater(g, g.path)
        }
    }

    /** A crossing of open water on [path] drowns some of [g], the more the nearer the strait is to all they can swim. */
    private fun crossWater(g: HexGroup, path: IntArray) {
        for (i in 0 until path.size - 1) {
            val gapKm = seaGap(path[i], path[i + 1]); if (!gapKm.isFinite()) continue
            val risk = DROWN * gapKm / Catalogue.swimKm(g.species).coerceAtLeast(1f)
            val lostA = binomial(g.adults, risk, rnd); val lostY = binomial(g.young, 2f * risk, rnd)
            adultsDied(g, lostA, rnd); g.young -= lostY
            // (The drowned wash up on the far shore.)
            small.deposit(DeadPool.CARRION, g.place, Kg((lostA + HexFauna.YOUNG_WEIGHT * lostY) * g.species.massKg))
            if (lostA + lostY > 0) stats.deaths.merge(Tally.of(g.species, DeathCause.DROWNED_CROSSING), lostA + lostY, Int::plus)
            stats.meetings.merge("sea crossings: ${g.species.name}", 1, Int::plus)
        }
    }

    /** The place each herd that weighed its places chose this week, and the share of it it foresaw would die there (the meetings left out), by its id: see [spreadOut]. */
    @Transient private val chosenPlace = ConcurrentHashMap<Int, Int>()
    @Transient private val chosenDying = ConcurrentHashMap<Int, Float>()
    /** The crowd each herd foresaw this week where it chose to go (or, not weighing its places, where it was), by its id: see [spreadOut]. */
    @Transient private val foreseen = HashMap<Int, Float>()

    /** How crowded herd [g] is where it is, as [grazed] and [planGround] have the herds and residents now ([herdCrowd], its own heads included). */
    private fun crowdAt(g: HexGroup): Float {
        val k = kindSlot[g.species.index]; val a = grazed[k]; val heads = planGround.heads
        return herdCrowd(g, hw.placeHex[g.place], planGround.ranges[g.id] ?: grazingRange(g), { q, _ -> a[q] }) { q, _ -> heads[groundKey(k, q)] ?: 0f }
    }

    /** Takes herd [g] off ([sign] -1) or puts it on (+1) the herds' grazing maps ([grazed], [herdWantKg], [planGround]) where it is. */
    private fun onGround(g: HexGroup, sign: Float) {
        val k = kindSlot[g.species.index]; val a = grazed[k]; val week = g.dailyNeedKg * days
        rangeShares(g, hw.placeHex[g.place]) { h, share -> a[h] += sign * g.size * share; herdWantKg[h] += sign * week * share }
        val range = if (sign < 0f) planGround.ranges.remove(g.id) else grazingRange(g).also { planGround.ranges[g.id] = it }
        range?.forEach { (q, share) -> planGround.heads.merge(groundKey(k, q), sign * g.size * share, Float::plus) }
    }

    /**
     * Herds spread out as they arrive. All weighed their places at once, each knowing where the others were and not where
     * they were going, so many can make for the same ground (crowdDiag: ostrich met about twice the crowd they foresaw,
     * nine-tenths of it from others arriving). In life a herd coming on ground more crowded than it looked moves on to the
     * next: crowding is settled as they meet it, not by hexes and weeks. So each herd, the last to arrive first, that
     * finds a crowd it would die of clearly more than of the one it foresaw (by a step of its score, see
     * [survivalSteps]) weighs again the places within a day's going, by the same score, knowing where the others are
     * now; and the next herd sees where it went. A herd the hunters stopped short of the place it chose (it was chased and
     * scattered) weighs again too, if where it stands is clearly deadlier than it foresaw there: in life it moves on
     * rather than standing where the chase left it.
     */
    internal fun spreadOut(weather: Weather) {
        val predatorKg = FloatArray(n)
        for (g in groups) if (g.alive && g.species.carnivore) predatorKg[g.place] += g.size * g.species.massKg
        Profile.part("spreading out: herds' grazing map") { fillGrazed(); noteGround(planGround) { g -> grazingRange(g) } }
        val herds = groups.indices.filter { groups[it].alive && !groups[it].species.carnivore }
            .sortedWith(compareByDescending<Int> { groups[it].days.lastOrNull() ?: 0f }.thenBy { it })
        var moved = 0
        for (k in herds) {
            val g = groups[k]
            val before = foreseen[g.id] ?: continue
            val crowded = survivalSteps(g, crowdSickness(crowdAt(g), g.fed)) - survivalSteps(g, crowdSickness(before, g.fed)) > 1f
            // (Or the hunters stopped it short of the place it chose: it weighs again if where it stands is clearly deadlier.)
            val stopped = chosenPlace[g.id]?.let { it != g.place.i } ?: false
            if (!crowded && !stopped) continue
            val dying = if (crowded) -1f else chosenDying[g.id] ?: -1f
            val plan = Profile.part("spreading out: weighing again") { plan(g, weather, weather.date, predatorKg, shift = true, before = dying) }
            if (plan.place == g.place) continue
            onGround(g, -1f)
            g.place = plan.place; g.path = g.path + plan.path.copyOfRange(1, plan.path.size); g.days = timetable(g.species, g.path)
            onGround(g, 1f)
            crossWater(g, plan.path)
            moved++
            if (deathDiag == g.species.name) shiftedDiag += g.id
        }
        stats.meetings.merge("herds that spread out on arrival", moved, Int::plus)
    }

    /** Per thread: least effort to each hex, the hex it was reached from, the hexes reached, the queue. */
    private class Scratch(size: Int) {
        val dist = FloatArray(size) { Float.MAX_VALUE }; val via = IntArray(size) { -1 }
        val reached = IntList(); val queue = LongHeap()
        /** The candidates weighed for a policy: places, their features (arrays reused from week to week), hand-written scores. */
        val candPlaces = IntList(); val candFeats = ArrayList<FloatArray>(); var candScores = FloatArray(64)
        /** The place being weighed: what the hand-written score and the features both read of it. */
        val terms = PlaceTerms()
        /**
         * The places weighed by the hand-written score whose deaths are left till after ([planInner]): each place, its
         * hex, the going there, the game and crowding it knows of there, and its score without its deaths (and with
         * its noise and temper).
         */
        val laterPlace = IntList(); var laterD = FloatArray(64); var laterPrey = FloatArray(64); var laterCrowd = FloatArray(64)
        var laterScore = FloatArray(64); var laterBeside = FloatArray(64); var laterUpper = FloatArray(64)
        fun later(p: Int, d: Float, prey: Float, crowded: Float, score: Float, beside: Float) {
            val k = laterPlace.size
            if (k == laterD.size) { val m = 2 * k; laterD = laterD.copyOf(m); laterPrey = laterPrey.copyOf(m); laterCrowd = laterCrowd.copyOf(m)
                laterScore = laterScore.copyOf(m); laterBeside = laterBeside.copyOf(m); laterUpper = laterUpper.copyOf(m) }
            laterPlace += p; laterD[k] = d; laterPrey[k] = prey; laterCrowd[k] = crowded; laterScore[k] = score; laterBeside[k] = beside; laterUpper[k] = score + beside
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
        /** For plant-eaters, how nourishing the forage is ([freshness]); 0 for hunters and bands. */
        var fresh = 0f
    }

    private fun fillTerms(t: PlaceTerms, g: HexGroup, p: Place, weather: Weather, date: Date) {
        val s = g.species
        t.discomfort = discomfort(s, p.i, weather)
        t.drain = drain(g, p.i, weather)
        t.aheadC = s.discomfortC(weather.forecastTemp(p.i), date.month)
        t.waterKm = if (s.human) land.drink.peopleWaterKm(p.i) else land.drink.waterKm[p]
        t.suitsLn = ln(fauna.suits(s, p.i).coerceAtLeast(1e-3f))
        t.fresh = if (s.carnivore) 0f else freshness(s, p.i)
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
    private class Reach(val reach: Float, val hexes: IntArray, val dist: FloatArray, val via: IntArray)
    /** The groups that merged into another in the last step (gone from [groups]), with their numbers when they did ([HexGroup.mergedSize]). */
    @Transient val merged = ArrayList<HexGroup>()
    /** Bands by the hex they are in at the start of the week (see [fillBandStates]). */
    @Transient internal val bandIndex = HashMap<Int, ArrayList<HexGroup>>()
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
        IntStream.range(0, hex.size).parallel().forEach { h ->
            for (k in 0 until 6) {
                val nh = hex.neighbour(h, k)
                if (nh < 0 || hex.heart[nh] < 0) continue
                // Lying snow makes the going harder: about twice the effort in 15 cm, more in deeper.
                val np = hw.placeOf[band][nh].takeIf { it >= 0 } ?: hex.heart[nh]
                val snowMm = if (np in 0 until n) weather.snowpack[np] else 0f
                // A worn path makes the going easier, the more so where the country is broken.
                val worn = wear.paths[h * 6 + k] * PATH_EASE * (0.5f + (if (np in 0 until n) land.ruggedness[np] else 0f))
                out[h * 6 + k] = cross[h * 6 + k] * (1f + snowMm / SNOW_EFFORT_MM) * (1f - min(0.7f, worn))
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
    val seaLinks: Array<IntArray> by lazy { links().to }
    val seaGaps: Array<FloatArray> by lazy { links().gapKm }
    /** For each hex, the hexes across the sea from it ([to]) and the open water to each ([gapKm]). */
    class SeaCrossings(val to: Array<IntArray>, val gapKm: Array<FloatArray>)
    private var linksMade: SeaCrossings? = null
    private fun links(): SeaCrossings = linksMade ?: run {
        val to = Array(hex.size) { IntArray(0) }; val gap = Array(hex.size) { FloatArray(0) }
        for (a in 0 until hex.size) {
            if (hex.heart[a] < 0 || (0 until 6).none { d -> val b = hex.neighbour(a, d); b >= 0 && hex.heart[b] < 0 }) continue
            val found = LinkedHashMap<Int, Float>()
            var ring = (0 until 6).mapNotNull { d -> hex.neighbour(a, d).takeIf { it >= 0 && hex.heart[it] < 0 } }; val seen = HashSet(ring + a)
            repeat(SEA_HEXES) {
                val next = ArrayList<Int>()
                for (w in ring) for (d in 0 until 6) {
                    val b = hex.neighbour(w, d); if (b < 0 || !seen.add(b)) continue
                    if (hex.heart[b] < 0) next += b
                    else if ((0 until 6).none { e -> hex.neighbour(a, e) == b }) found[b] = (hex.distanceKm(a, b) - hex.hexKm).toFloat().coerceAtLeast(1f)
                }
                ring = next
            }
            to[a] = found.keys.toIntArray(); gap[a] = found.values.toFloatArray()
        }
        SeaCrossings(to, gap).also { linksMade = it }
    }
    /** The open water between hexes [a] and [b], km, if a swimmer can cross from one to the other; else infinite. */
    fun seaGap(a: Int, b: Int): Float { val l = seaLinks[a]; for (i in l.indices) if (l[i] == b) return seaGaps[a][i]; return Float.POSITIVE_INFINITY }

    /** The worn paths read as a graph of landmarks and the legs between them. */
    val routes = HexRoutes(this)
    /**
     * Where each material bands give each other ([TRADED]) is got (trade on): for each, the hexes where
     * it is richest, one in a square of [SOURCE_KM] at most, for bands' rites to take them to.
     */
    internal val materialSources: Map<Material, IntArray> by lazy {
        TRADED.associateWith { m ->
            val share = requireNotNull(m.deposit) { "no deposits known for traded $m" }(land)
            val best = HashMap<Long, Int>()
            for (p in 0 until n) {
                if (share[p] < SOURCE_MIN) continue
                val (x, y) = hex.centreKm(hw.placeHex[p]); val key = (x / SOURCE_KM).toLong() * 100_000 + (y / SOURCE_KM).toLong()
                val b = best[key]; if (b == null || share[p] > share[b]) best[key] = p
            }
            best.values.map { hw.placeHex[it] }.sorted().toIntArray()
        }
    }

    /** Effort and hex packed in one number, ordered by effort (efforts are never negative). */
    private fun Long.withPlace(d: Float, h: Int): Long = (d.toRawBits().toLong() shl 32) or h.toLong()

    /**
     * What each place offers each kind this week, worked out once for all its groups: for hunters the
     * prey they can catch there (resident and in herds), for herds the food as worth to them.
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
    @delegate:Transient internal val kindSlot by lazy { IntArray(Catalogue.all.size) { i -> kinds.indexOfFirst { it.index == i } } }

    private fun valuePlaces(predatorKg: FloatArray, month: Int) {
        for (a in headsHere) a.fill(0)
        for (g in groups) if (g.alive) headsHere[kindSlot[g.species.index]][g.place] += g.size
        // (What may be expected changes slowly: it is reckoned once a month.)
        if (month != expectMonth) { expect(predatorKg); expectMonth = month }
        IntStream.range(0, n).parallel().forEach { p -> for ((k, s) in kinds.withIndex()) placeValue[k][p] = lifewayValue(k, s, p) }
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
            val suitArea = IntStream.range(0, n).parallel().mapToDouble { p -> val v = fauna.suits(s, p) * land.areaKm2[p]; e[p] = v; v.toDouble() }.sum()
            var heads = 0L; for (p in 0 until n) heads += headsHere[k][p]
            val now = (heads / suitArea.coerceAtLeast(1.0)).toFloat()
            usualDensity[k] = if (usualDensity[k] <= 0f) now else usualDensity[k] + (now - usualDensity[k]) / USUAL_MONTHS
            val d = usualDensity[k]
            IntStream.range(0, n).parallel().forEach { p -> e[p] *= d }
        }
        for ((k, s) in kinds.withIndex()) if (s.carnivore) {
            val e = expectPrey[k]
            IntStream.range(0, n).parallel().forEach { p -> e[p] = herdPrey(s, p, expectHeads) }
        }
    }

    /**
     * Herd sign this week: each herd leaves it in the places it passed, by its heads and the days it
     * spent in each; last week's fades by half.
     */
    internal fun leaveSign() {
        for ((k, s) in kinds.withIndex()) if (!s.carnivore) { val a = herdSign[k]; IntStream.range(0, n).parallel().forEach { p -> a[p] *= 0.5f } }
        for (g in groups) {
            if (!g.alive || g.species.carnivore) continue
            val a = herdSign[kindSlot[g.species.index]]; val band = hw.placeBand[g.place]
            for (i in g.path.indices) {
                val p = hw.placeOf[band][g.path[i]].takeIf { it >= 0 } ?: g.place.i
                val to = if (i + 1 < g.days.size) g.days[i + 1] else days.toFloat()
                a[p] += g.size * (to - g.days.getOrElse(i) { 0f }).coerceAtLeast(0f)
            }
        }
        signWeeks++
    }

    /**
     * The herd game hunter [g] reckons on in place [p] (hex [h]), as [herdPrey] counts it: the herds there
     * if it can see them (its own hex and the six around, which its week's ranging covers); else what the
     * sign it crossed last week told it (as well as a band's trackers read it); else what it expects.
     */
    private fun knownHerdGame(g: HexGroup, kind: Int, p: Place, h: Hex, sensed: IntArray): Float {
        val s = g.species
        if (h in sensed) return herdPrey(s, p.i)
        val expected = expectPrey[kind][p]
        if (signWeeks < 2 || h !in g.path) return expected
        // (At a steady week's passing, the fading sign holds two weeks' heads × days.)
        var v = 0f
        for ((k, ps) in kinds.withIndex()) {
            if (ps.carnivore) continue
            val heads = herdSign[k][p] / (2f * days)
            if (heads > 0f) v += heads * ps.massKg * catchable(s, ps, p.i) / land.areaKm2[p]
        }
        val read = g.band?.skill(Skill.TRACK) ?: 1f
        return expected + read * (v - expected)
    }

    /** What place [p] is worth to hunter [g] as it knows it: [placeValue] with the herds it knows of there in place of those it expects. */
    private fun knownGame(g: HexGroup, kind: Int, p: Place, h: Hex, sensed: IntArray): Float {
        val s = g.species
        val fish = g.band?.let { fishWorth(g, it, h) } ?: 0f
        if (h !in sensed && (signWeeks < 2 || h !in g.path)) return placeValue[kind][p] + fish
        val diff = knownHerdGame(g, kind, p, h, sensed) - expectPrey[kind][p]
        return placeValue[kind][p] + diff * (if (s.human) campFactor(p.i) else 1f) + fish
    }

    /**
     * The fish and shellfish band [b] could take from hex [h] in a week, with its skill ([HexWater.fishWithin]; less
     * plain where it is a stranger), as the game standing on the ground that would yield as much to its hunters: game
     * is met over the ground its party sweeps in a week ([sweptKm2]) and caught at [CATCH].
     */
    private fun fishWorth(g: HexGroup, b: Band, h: Hex): Float =
        fauna.water.fishWithin(h.i, b.fishing()) * (if (b.knows(h.i)) 1f else 0.5f) / (sweptKm2(g.species) * CATCH)

    /**
     * What draws band [b] (group [g]) to place [p] in hex [h] or puts it off, beyond what any group weighs: its
     * appointment, its dead, its houses, raids and the homes of others, gatherings, and country it does not know.
     * (Kept apart from [planInner] so that the planner stays small enough for the JIT to compile.)
     */
    private fun bandPull(g: HexGroup, b: Band, month: Int, h: Hex, here: Hex, p: Place, keepOff: IntArray?, sensed: IntArray): Float {
        var score = 0f
        // Its appointment draws a band in its month (to the month with sky lore; within a month
        // either way, more weakly, without); its ancestors' places draw it a little always.
        val pull = appointPull(g, b, month, h)
        if (pull != 0f) score += APPOINT_PULL * pull
        val dead = g.memory.deadAt(h)
        if (dead > 0f) score += ANCESTOR_PULL * min(1f, dead / 10f)
        // Its houses draw it home; a camp where someone died lately is shunned by those who leave their dead.
        if (h == b.houseHex) score += HOME_PULL * b.house
        if (h == b.unluckyHex) score -= UNLUCKY
        // It keeps off the known homes of bands it is not close to (unless set on raiding them).
        if (b.raidHex != h && keepOff != null && h in keepOff) score -= TRESPASS_AVOID
        // A band set on a raid heads for its target.
        if (!b.raidHex.none) {
            val from = hexKm(here, b.raidHex)
            if (from > 0.0) score += RAID_PULL * ((from - hexKm(h, b.raidHex)) / from).toFloat().coerceIn(-1f, 1f)
        }
        // A band with food enough to feast others is drawn to where other bands are (gatherings).
        if (peopleSlot >= 0 && b.holds(Tradition.FEASTS) && g.larder > b.storeAim * g.dailyNeedKg) {
            val others = knownHeads(peopleSlot, p, h, sensed) - (if (p == g.place) g.size else 0)
            if (others > 0f) score += GATHER_PULL * min(1f, others / GATHER_HEADS)
        }
        // Bands keep to country they know; what lies beyond they know only as a sense of better land.
        // Strange country puts a band off, unless it is out to explore (then it draws it on).
        if (!b.knows(h.i)) score -= UNKNOWN_COUNTRY * (2f - b.explore)
        return score
    }

    /** Heads of kind slot [k] in place [p] as a group knows them: seen in its own hex and the six around, else expected. */
    private fun knownHeads(k: Int, p: Place, h: Hex, sensed: IntArray): Float = if (h in sensed) headsHere[k][p].toFloat() else expectHeads[k][p]

    /**
     * What place [p] offers a band for a camp, as game-weight per km²: the game it can hunt there, and
     * the fruit, nuts and roots it can gather (counted at their weight against meat), the more so where
     * the camp can stand on high, broken or water-girt ground safe from lions and raiders.
     */
    internal fun campValue(s: Species, p: Int): Float = (preyHere(s, p) + herdPrey(s, p)) * campFactor(p) + campPlants(p)
    /**
     * What a camp's game is worth to a band, times: the more where the camp is safe, the less the further water is,
     * and the less the more of its days the hearth's firewood takes there (none left where there is nothing to burn).
     */
    internal fun campFactor(p: Int): Float {
        // Water close to camp: women and children fetch it every day (from a well dug in a dry bed, where water lies near).
        val w = land.drink.peopleWaterKm(p) / 3f
        val fuel = min(1f, HEARTH_WOOD / (FUEL_A_DAY * min(1f, (land.plants.tree[p] + land.plants.shrub[p]) / 3f)).coerceAtLeast(1e-6f))
        return (1f + CAMP_SAFETY * land.bestDefence[p] + CAVE_CAMP * land.caves[p]) / (1f + w * w) * (1f - fuel)
    }
    internal fun campPlants(p: Int) = KgM2(fauna.food.fruit[p] + fauna.food.roots[p] + fauna.food.seeds[p]).perKm2.v * PLANT_FOOD_WEIGHT * campFactor(p)

    /**
     * Bands burn off the dry grass around their camps in the dry season: the burn greens up and draws
     * game (and the fire may spread with the wind).
     */
    private fun burnOff(weather: Weather) {
        for (g in groups) {
            if (!g.alive || !g.species.human) continue
            val p = g.place
            val dry = land.hydro.soil[p] < DRY_SOIL_MM && weather.temperature[p] > 15f && land.plants.green[p] < 0.4f
            if (dry && land.plants.herb[p] > 0.3f && land.fire.burnt[p] < 0.1f && rnd.nextFloat() < BURN_OFF * (g.band?.burnWill ?: 1f)) {
                land.fire.ignite(p.i)
                stats.meetings.merge("bands burned off grass", 1, Int::plus)
            }
        }
    }

    /**
     * The model's clock: the step [date] falls in (what the groups' "weeks" count: a step of [days] days), and how
     * many steps make a year, for counts of weeks compared against spans of years.
     */
    internal fun stepOf(date: Date) = (date.absoluteDay / days).toInt()
    internal val stepsPerYear get() = Date.DAYS_PER_YEAR.toFloat() / days

    /** Packs of kinds that follow people, by the hex they end the week in (for taming). */
    internal var followersByHex: Map<Int, List<HexGroup>> = emptyMap()
    /** Where each living band with a standing house has it, by the band's id (set each week before the bands' own weeks). */
    internal var houseOf: Map<Int, Int> = emptyMap()

    /**
     * How big a herd of [g]'s kind grows before it splits, now: herds gather on a green flush and
     * scatter in the lean season, and break up to calve; packs, prides and bands keep their kind's size.
     */
    internal fun maxSize(g: HexGroup, date: Date): Int {
        val s = g.species
        if (s.carnivore) return g.band?.let { b ->
            // Feasts keep the peace in bigger bands; a settled band without them quarrels and splits sooner.
            if (b.holds(Tradition.FEASTS)) (s.maxGroupSize * FEAST_SIZE).toInt()
            else if (b.atHome) (s.maxGroupSize * SETTLED_QUARRELS).toInt() else s.maxGroupSize
        } ?: s.maxGroupSize
        val birth = if (g.birthMonth >= 0) g.birthMonth else s.birthMonth
        val calving = monthsFrom(birth, date.month) < s.birthMonths
        val flush = 1f + AGGREGATION * land.plants.green[g.place] * g.fed
        return max(unitSize(s), (s.maxGroupSize * herdUnit(s) * g.sizePref * g.will[AnimalWay.HERD_SIZE] * flush * (if (calving) 0.5f else 1f)).toInt())
    }

    /** How far going to hex [h] brings band [g] toward its appointment, -1..1, in its month (0 otherwise). */
    internal fun appointPull(g: HexGroup, b: Band, month: Int, h: Hex): Float {
        val ap = b.appointment ?: return 0f
        val off = minOf(monthsFrom(ap.month, month), monthsFrom(month, ap.month))
        // The month before, it is on its way; without sky lore it also lingers a month after, the count being loose.
        val before = monthsFrom(month, ap.month) == 1
        val weight = when { off == 0 -> 1f; before -> 0.7f; off == 1 && !b.holds(Tradition.SKY_LORE) -> 0.5f; else -> return 0f }
        val here = hw.hexOf(g.place)
        val fromHere = hexKm(here, ap.hex)
        if (fromHere <= hex.hexKm) return if (h == here) weight else 0f
        return weight * ((fromHere - hexKm(h, ap.hex)) / fromHere).toFloat().coerceIn(-1f, 1f)
    }

    /** A herd's size as small (0: under its kind's unit), middling (1: up to three units) or large (2), for comparing them. */
    internal fun sizeClass(g: HexGroup) = (g.size.toFloat() / unitSize(g.species)).let { if (it < 1f) 0 else if (it < 3f) 1 else 2 }
    /** The main kind of country of each place. */
    internal val biomeOfPlace: IntArray by lazy {
        IntArray(land.n) { pl ->
            val c = IntArray(Biome.entries.size)
            for (cell in hw.placeCells[pl]) c[hw.world.biome[cell].ordinal]++
            c.indices.maxBy { c[it] }
        }
    }
    /** How many of its kind's usual bands a herd is (1 or more). */
    internal fun bandsIn(g: HexGroup) = max(1f, g.size.toFloat() / g.species.groupSize)
    /** A kind's usual group as an agent: herds move as units of [HERD_UNIT] of their usual bands. */
    internal fun unitSize(s: Species) = if (s.carnivore) s.groupSize else s.groupSize * herdUnit(s)
    /** How many of its usual bands a herd agent stands for: more for the migrants, whose herds gather in thousands. */
    internal fun herdUnit(s: Species) = HERD_UNIT

    /** Choices made in planning, by kind: kept to its ground without weighing (content), weighed and stayed, weighed and moved. */
    val planCounts = AtomicIntegerArray(64 * 3)


    /** Strongest hunting party in each place last week, and its kind's index (-1 for none). */
    internal val topStrength = FloatArray(n)
    /** The flesh of the groups of beasts that take people in each hex this week, kg/km², by how readily they do ([stalkRate]). */
    internal val manEaterKm2 = FloatArray(hex.size)
    /** Each herd kind's heads on the ground they graze this week, by hex, both forms: as [sicknessWeek] counts them (see [fillGrazed]). */
    @delegate:Transient internal val grazed by lazy { Array(kinds.size) { FloatArray(hex.size) } }
    /** For crowdDiag=KIND (a report): the crowd each herd of that kind foresaw at the place it chose this week, by its id. */
    @Transient internal val crowdDiag: String? = Config.string("crowdDiag")
    @Transient internal val foreseenCrowd = ConcurrentHashMap<Int, Float>()
    /** For crowdDiag=KIND: the crowd each herd of that kind met this week ([sicknessWeek]), by its id. */
    @Transient internal val metCrowd = ConcurrentHashMap<Int, Float>()
    /**
     * For crowdDiag=KIND: the crowd each herd of that kind would have met where it ended the week had the other herds and
     * the residents stayed as they were when it chose ([planCrowd]); and whether it reached the place it chose.
     */
    @Transient internal val metAsPlannedCrowd = ConcurrentHashMap<Int, Float>()
    @Transient internal val reachedChoice = ConcurrentHashMap<Int, Boolean>()
    /** For crowdDiag=KIND: each herd's own spread as it chose ([PlanContext.ownHexes], [PlanContext.own]) and the place it chose. */
    internal class ChoiceDiag(val ownHexes: IntArray, val ownShares: FloatArray, val own: Map<Int, Float>, val place: Int)
    @Transient internal val choiceDiag = ConcurrentHashMap<Int, ChoiceDiag>()
    /** For deathDiag=KIND (a report): the share of each herd of that kind, adults and young by cause, that it foresaw would die where it chose this week ([deathsAt]), by its id; a herd following another's choice takes that one's. */
    @Transient internal val deathDiag: String? = Config.string("deathDiag")
    @Transient internal val foreseenDeaths = ConcurrentHashMap<Int, Map<String, FloatArray>>()
    /** For deathDiag=KIND: how fed each herd of that kind foresaw it would be where it chose ([foreseeFed]), by its id. */
    @Transient internal val foreseenFed = ConcurrentHashMap<Int, Float>()
    /** For deathDiag=KIND: the place each herd of that kind chose this week, and the herds that weighed again on arrival ([spreadOut]), by id. */
    @Transient internal val chosenDiag = ConcurrentHashMap<Int, Int>()
    @Transient internal val shiftedDiag: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    /** For deathDiag=KIND: the deaths the week's odds made likely for that kind's herds ([liveWeek]), by "where|cause" ([diagWhere]). */
    @Transient internal val metOddsDiag = ConcurrentHashMap<String, DoubleAdder>()
    /** For deathDiag: where herd [g] ended the week against the place it chose: weighed again on arrival, reached it, stopped short of it, or chose none. */
    internal fun diagWhere(g: HexGroup): String {
        val chosen = chosenDiag[g.id] ?: return "chose none"
        return when { g.id in shiftedDiag -> "moved on arrival"; g.place.i == chosen -> "reached its choice"; else -> "stopped short" }
    }
    /** For deathDiag: each herd kind's animals ([Species.index]) the residents' hunters killed this week ([takeResidentsKills]). */
    @delegate:Transient internal val herdKilledByResidents by lazy { IntArray(Catalogue.all.size) }

    /**
     * The herds' grazing ground: each herd's ([grazingRange]: places, and its share of the week on each) by its id, and
     * the heads of each herd kind grazing each place (by [groundKey]).
     */
    internal class HerdGround { val ranges = HashMap<Int, List<Pair<Int, Float>>>(); val heads = HashMap<Long, Float>() }
    internal fun groundKey(kind: Int, p: Int) = kind.toLong() * n + p
    /** Where the herds graze as the week's choosing begins (what each weighs its places against). */
    @Transient internal var planGround = HerdGround()
    /** Where the herds grazed this week, as [graze] spread them (what they sicken of). */
    @Transient internal var weekGround = HerdGround()

    /** [ground] afresh: each live herd's grazing ground as [rangeOf] has it (worked out on all cores), and its heads there. */
    internal fun noteGround(ground: HerdGround, rangeOf: (HexGroup) -> List<Pair<Int, Float>>?) {
        ground.ranges.clear(); ground.heads.clear()
        val herds = groups.filter { it.alive && !it.species.carnivore }
        val ranges = herds.parallelStream().map { rangeOf(it) }.toList()
        for ((i, g) in herds.withIndex()) {
            val r = ranges[i] ?: continue
            ground.ranges[g.id] = r
            val k = kindSlot[g.species.index]
            for ((q, share) in r) ground.heads.merge(groundKey(k, q), g.size * share, Float::plus)
        }
    }
    /** All the herds' week's need on each hex's ground, kg, each herd spread over its grazing ground: what they will eat of it (see [fillGrazed]). */
    @delegate:Transient internal val herdWantKg by lazy { FloatArray(hex.size) }

    /** [grazed] afresh, before the week's choosing: each herd spread over its grazing ground ([rangeShares]), and the residents in their hex. */
    internal fun fillGrazed() {
        for (a in grazed) a.fill(0f)
        herdWantKg.fill(0f)
        for (g in groups) if (g.alive && !g.species.carnivore) {
            val a = grazed[kindSlot[g.species.index]]
            val week = g.dailyNeedKg * days
            rangeShares(g, hw.placeHex[g.place]) { h, share -> a[h] += g.size * share; herdWantKg[h] += week * share }
        }
        // (Each kind on its own core.)
        kinds.indices.toList().parallelStream().forEach { k ->
            val s = kinds[k]; val r = fauna.slot(s)
            if (s.carnivore || r < 0) return@forEach
            val a = grazed[k]; val ad = fauna.adults[r]; val yo = fauna.young[r]
            for (p in 0 until land.n) { val m = ad[p] + yo[p]; if (m > 0f) a[hw.placeHex[p]] += m * land.areaKm2[p] }
        }
    }
    internal val topKind = IntArray(n) { -1 }

    /**
     * Sign herds leave in each place, by kind: heads × days they spent there, fading by half each week
     * (hoofprints last days, dung weeks). A hunter reads it where it passed (see [knownGame]); the weeks it has been kept.
     */
    internal val herdSign = Array(kinds.size) { FloatArray(n) }
    var signWeeks = 0
    /** Each kind's heads per km² of ground that suits it fully, as it usually is (kept over about a year). */
    val usualDensity = FloatArray(kinds.size)
    /** Hunters' weight per km² of land, kg, as it usually is (kept over about a year). */
    var usualHuntersKm2 = 0f
    /** Heads of each kind a place may be expected to hold, by how well it suits the kind: what a group goes by where it has not looked. */
    private val expectHeads by lazy { Array(kinds.size) { FloatArray(n) } }
    /** The herd game each hunting kind may expect in each place ([herdPrey] of [expectHeads]); the month both were reckoned for. */
    internal val expectPrey by lazy { Array(kinds.size) { FloatArray(n) } }
    private var expectMonth = -1

    /** A hunting party's fighting strength: weight, ferocity. */
    internal fun strength(g: HexGroup) = g.adults * g.species.massKg * ferocity(g)
    internal fun ferocity(g: HexGroup) = g.band?.ferocity(g.species) ?: g.species.ferocity

    /** A week's plan: the [place] a group heads for and the hexes of its [path] there. */
    /** Where a group goes this week and its way there; for a herd that weighed its places, the crowd it foresaw there ([herdCrowd]; -1 for none). */
    class Plan(val place: Place, val path: IntArray, val crowd: Float = -1f) { operator fun component1() = place; operator fun component2() = path }

    /**
     * Where [g] goes this week: the reachable place it likes best, and the hexes it passes on the way.
     * Reach is a few hexes when content; a forced march when hungry, cold, or heading for its season's
     * place. Hunters keep to their ground unless starving.
     */
    internal fun plan(g: HexGroup, weather: Weather, date: Date, predatorKg: FloatArray, shift: Boolean = false, before: Float = -1f): Plan {
        var pt = Profile.mark()
        val r = planInner(g, weather, date, predatorKg, shift, before) { name -> pt = Profile.lap(name, pt) }
        Profile.lap("choosing: route", pt)
        return r
    }

    /**
     * Least effort to each hex within [reach] of [here] for [g] this week, into [buf] (its dist, via and
     * reached): from a search shared with groups of its kind starting from the same place that reach as
     * far or further, else from a new one (then shared).
     */
    private fun searchReach(g: HexGroup, here: Int, reach: Float, restless: Boolean, weather: Weather, buf: Scratch) {
        val s = g.species
        val dist = buf.dist; val via = buf.via; val reached = buf.reached
        for (i in 0 until reached.size) { val h = reached[i]; dist[h] = Float.MAX_VALUE; via[h] = -1 }
        reached.clear()
        val q = buf.queue; q.clear()
        // Groups of a kind starting from the same place this week share the search (herds often do).
        // Each group's reach is its own (hunger, herd size, the band's weak), so a search is shared only
        // with groups that reach no further, and each takes the hexes within its own reach: least-effort
        // distances within a reach do not depend on how far the search went beyond it. The hexes are kept
        // in index order, so the choice does not depend on which group's search was kept.
        val reachKey = ((s.index.toLong() * 8 + hw.placeBand[g.place]) * hex.size + here) * 2 + (if (restless) 1 else 0)
        var shared = reachCache[reachKey]
        if (shared == null || shared.reach < reach) {
            dist[here] = 0f; reached += here; q += 0L.withPlace(0f, here)
            // (The effort of each crossing this week, through snow and along worn paths: see [stepEffort].)
            val effort = stepEffort(Catalogue.travel(s).ordinal, hw.placeBand[g.place], weather)
            while (q.isNotEmpty()) {
                val e = q.poll()
                val d = Float.fromBits((e ushr 32).toInt()); val h = (e and 0xffffffffL).toInt()
                if (d > dist[h]) continue
                for (k in 0 until 6) {
                    val nh = hex.neighbour(h, k)
                    if (nh < 0) continue
                    val nd = d + effort[h * 6 + k]
                    if (!nd.isFinite() || nd > reach) continue
                    if (nd < dist[nh]) { if (dist[nh] == Float.MAX_VALUE) reached += nh; dist[nh] = nd; via[nh] = h; q += 0L.withPlace(nd, nh) }
                }
                // Swimmers may cross a strait they can cover, at a great effort.
                val swim = Catalogue.swimKm(s)
                if (swim > 0f) for ((i, nh) in seaLinks[h].withIndex()) {
                    val gapKm = seaGaps[h][i]
                    if (gapKm > swim) continue
                    val nd = d + gapKm * SWIM_EFFORT
                    if (nd > reach) continue
                    if (nd < dist[nh]) { if (dist[nh] == Float.MAX_VALUE) reached += nh; dist[nh] = nd; via[nh] = h; q += 0L.withPlace(nd, nh) }
                }
            }
            val hs = reached.toIntArray().also { it.sort() }
            val mine = Reach(reach, hs, FloatArray(hs.size) { dist[hs[it]] }, IntArray(hs.size) { via[hs[it]] })
            shared = reachCache.merge(reachKey, mine) { a, b -> if (a.reach >= b.reach) a else b }!!
            for (i in 0 until reached.size) { val h = reached[i]; dist[h] = Float.MAX_VALUE; via[h] = -1 }
            reached.clear()
        }
        for (i in shared.hexes.indices) {
            if (shared.dist[i] > reach) continue
            val h = shared.hexes[i]; reached += h; dist[h] = shared.dist[i]; via[h] = shared.via[i]
        }
    }

    /**
     * The features of place [p] (in hex [h]) for [g]'s choice this week, into [f] (cleared): what a learned
     * policy sees of it ([Feature]). The rest are worked out in [planInner] as the hand-written score weighs them.
     */
    private fun fillFeatures(f: FloatArray, t: PlaceTerms, g: HexGroup, p: Place, h: Hex, here: Hex, month: Int, prey: Float, crowded: Float, hunters: Float, d: Float,
                             reach: Float, goal: Int, fromHere: Double, toGoal: Double, goalQ: Float, onRound: Boolean, roundStrength: Float, season: Double,
                             restless: Boolean, pull: Float, beyond: Float, foreign: Float, keepOn: Float, weather: Weather, date: Date) {
        val s = g.species
        // What the place offers it: plants for herds; for hunters and bands, what the baseline weighs (game, and for bands plant food and a safe camp).
        if (s.carnivore) f[Feature.FOOD] = prey / (prey + PREY_HALF) else { val food = foodFor(s, p.i); f[Feature.FOOD] = saturate(food, Thresholds.FOOD_HALF) }
        // (And for herds how good it is, nourishment and protein, as the hand-written score weighs it: high ground's draw in summer.)
        if (s.lifeway == Lifeway.HERD) f[Feature.FORAGE_QUALITY] = t.fresh
        f[Feature.CLIMATE] = t.suitsLn / 2f
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
            if (!b.knows(h.i)) f[Feature.FOOD] = 0.5f * f[Feature.FOOD] + 0.5f * SIGN_GUESS
        }
        f[Feature.SNOW_OVER_LIMIT] = if (weather.snowpack[p] > s.snowLimitMm) 1f else 0f
        f[Feature.SNOW] = min(2f, weather.snowpack[p] / s.snowLimitMm)
        // (What the hand-written score weighs besides: snow coming with a freeze, as keenly as its kind foresees it; fire;
        // the fear of ground where game was killed lately; and a much stronger hunter of another kind holding the place.)
        if (weather.forecastTemp(p.i) < 0f && weather.snowpack[p] + weather.forecastRain(p.i) > s.snowLimitMm) f[Feature.SNOW_AHEAD] = s.foresight
        f[Feature.BURNING] = land.fire.burning[p]
        f[Feature.KILL_FEAR] = fauna.afraid(p.i)
        if (s.carnivore && hex.distanceKm(h.i, here.i) <= hex.hexKm * 1.01 && topKind[p] >= 0 && topKind[p] != s.index)
            f[Feature.STRONGER_HUNTER] = min(1f, topStrength[p] / (4f * strength(g)).coerceAtLeast(1e-3f))
        f[Feature.CLIMB] = ((hw.placeElevationM[p] - hw.placeElevationM[g.place]) / 1000f).coerceIn(-3f, 3f)
        f[Feature.EFFORT] = (d / reach.coerceAtLeast(1f)).coerceIn(0f, 1f)
        f[Feature.STAYS] = if (p == g.place) 1f else 0f
        f[Feature.SAME_HEX] = if (h == here) 1f else 0f
        if (goal >= 0) {
            f[Feature.TOWARD_BEARING] = if (fromHere > hex.hexKm) ((fromHere - toGoal) / fromHere).toFloat().coerceIn(-1f, 1f) else 0f
            f[Feature.SEASON_PLACE] = (goalQ - g.fed).coerceIn(-1f, 1f)
            // (On its round, how far the place takes it on toward the round's place this month: see [SharedMover.pull].)
            if (onRound) f[Feature.ON_ROUND] = f[Feature.TOWARD_BEARING] * roundStrength
        }
        f[Feature.FED] = g.fed
        f[Feature.FAT] = g.fat / s.maxFatDays.coerceAtLeast(1f)
        f[Feature.SEASON_SIN] = sin(season).toFloat(); f[Feature.SEASON_COS] = cos(season).toFloat()
        f[Feature.RESTLESS] = if (restless) 1f else 0f
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
        f[Feature.HOUSES] = g.band?.let { if (h == it.houseHex) it.house else 0f } ?: 0f
        f[Feature.OWN_SIZE] = ln(g.size.toFloat().coerceAtLeast(1f) / unitSize(s)) / 2f
        f[Feature.LASTING_WATER] = min(1f, land.drink.nearRiver[p] + land.drink.nearLake[p] + land.drink.nearSpring[p])
        f[Feature.SICKNESS] = min(1f, fauna.outbreakDeaths(s, p.i) * 10f)
        // The signs of the land, as anyone who looks can read them.
        f[Feature.TREES] = min(1f, land.plants.tree[p] / 10f); f[Feature.SHRUBS] = min(1f, land.plants.shrub[p] / 2f)
        f[Feature.GRASS] = min(1f, land.plants.herb[p] / 0.5f)
        f[Feature.MARSH] = min(1f, land.waterlogged[p])
        f[Feature.WATER_AGAINST_USUAL] = (land.drink.water[p] - min(1f, land.drink.nearRiver[p] + land.drink.nearLake[p] +
            land.drink.nearSpring[p])).coerceIn(-1f, 1f)
        f[Feature.PATHS] = wear.at(h.i)
        f[Feature.HERDS_ABOUT] = ln(1f + monthHerdHeads[p] / land.areaKm2[p]) / 3f
        f[Feature.DUNG] = min(1f, fauna.small.dung[p] * 1e4f)
        f[Feature.GRAZING_AND_KILLS] = min(1f, (land.plants.grazing[p] + land.plants.browsing[p]) / 1000f) +
            min(1f, KgM2(fauna.small.carrion[p] + fauna.small.bones[p]).perKm2.v / 20f)
        f[Feature.KNOWN_GROUND] = g.band?.let { if (it.knows(h.i)) 1f else 0f } ?: 1f
        f[Feature.BEYOND_OWN_GROUND] = beyond; f[Feature.OTHERS_MARKS] = foreign
        f[Feature.KEEPS_HEADING] = keepOn
        // The fish and shellfish a band could take there, with its skill, against its need (the
        // shore and the river are plain to see; how rich they are, less so where it is a stranger).
        f[Feature.FISH] = g.band?.let { b -> min(2f, fauna.water.fishWithin(h.i, b.fishing()) / (g.size * s.dailyIntakeKg * days).coerceAtLeast(1f)) *
            (if (b.knows(h.i)) 1f else 0.5f) } ?: 0f
        // What it remembers of this hex: in this season, or failing that in any.
        val mem = g.memory
        var slot = mem.find(h.i, PlaceMemory.seasonOf(month)); val sameSeason = slot >= 0
        if (slot < 0) for (i in 0 until PlaceMemory.MEMORY_SLOTS) if (mem.hex[i] == h.i) { slot = i; break }
        if (slot >= 0) {
            f[Feature.REMEMBERED] = 1f; f[Feature.REMEMBERED_SAME_SEASON] = if (sameSeason) 1f else 0f
            f[Feature.REMEMBERED_FED] = mem.fed[slot]; f[Feature.REMEMBERED_WATER] = mem.water[slot]; f[Feature.REMEMBERED_DANGER] = mem.danger[slot]
            f[Feature.REMEMBERED_AGE] = min(1f, (stepOf(date) - mem.lastWeek[slot]) / stepsPerYear)
        }
    }

    private fun planInner(g: HexGroup, weather: Weather, date: Date, predatorKg: FloatArray, shift: Boolean, before: Float, lap: (String) -> Unit): Plan {
        val s = g.species
        val here = hw.hexOf(g.place)
        // What it sees of the others: in its own hex and the six around, which its week's ranging covers.
        // Beyond, it goes by the sign it crossed, what it remembers, and what it expects (see [knownGame]).
        val sensed = IntArray(7) { if (it == 0) here.i else hex.neighbour(here.i, it - 1) }
        // (A band reads the beasts of prey about, not other people: those it weighs as bands. Where it has not looked it
        // expects hunters as thick as they usually are, people among them.)
        fun huntersKm2(p: Place, h: Hex) = if (h in sensed) (if (s.human) beastKg else predatorKg)[p] / land.areaKm2[p] else usualHuntersKm2
        // How far it will shift its ground this week. Most of a week's walking is within its range
        // (grazing, patrolling); the net shift is a couple of days' going when content, more on a trek
        // when hungry, cold or snowed in. A pack holding ground patrols it; a starving or homeless one roams.
        val thirsty = g.dry > 0.5f * s.daysWithoutWater
        // The place it remembers doing best at this time of year: while it is far off, the herd is on its way.
        val month = wrapMonth(date.month + if (date.dayOfMonth > Date.DAYS_PER_MONTH / 2) 1 else 0)
        // (Its round, if its elders taught it one, else the place it remembers doing best at this time of year.)
        // (Within a range's reach of the round's place it has arrived: it grazes the range as it likes, and
        // the herds on one round spread over it instead of crowding onto one spot.)
        val round = roundOf(g, month)?.takeIf { hexKm(here.i, hw.placeHex[it.place]) > tuning.roundArrivedKm }
        val onRound = round != null
        val goal = round?.place ?: g.seasonPlace[month]
        val goalQ = if (round != null) max(g.seasonQ[month], round.strength) else g.seasonQ[month]
        val roundStrength = round?.strength ?: 0f
        val goalFar = goal >= 0 && (!s.carnivore || s.human) && hexKm(here.i, hw.placeHex[goal]) > 2 * hex.hexKm
        // Far out of its country, it treks.
        val astray = bearings.strength(kindIndex[s] ?: 0, here.i) > ASTRAY
        // Any group weighs its places where what the place drains from it (the cold, the flies) is worse,
        // by more than a step's going costs, than at the next place over: relief lies a step away. (It
        // looks about, but does not trek for it: its usual week's going reaches the next place.)
        val relief = drain(g, g.place.i, weather) - leastDrainNear(g, here.i, weather) > WALK_COST * hex.hexKm.toFloat() / days
        // (A band whose appointment falls this month or next, and is not there, sets off: it is restless.)
        val dueSoon = g.band?.let { b -> b.appointment.let { ap -> ap != null && ap.hex != here && monthsFrom(month, ap.month) <= 1 } ||
            b.unluckyHex == here || b.raid != null } ?: false
        val restless = restless(g, weather, thirsty, astray, dueSoon, onRound, goalFar)
        // A group content where it is (fed, watered, comfortable, in its country) keeps to its ground
        // this week without weighing the alternatives: every other week, by turns.
        // (How often depends on its way of life: see [contentEvery].)
        val every = contentEvery(s)
        // (A followed group under the player's hand weighs its places every week, to show them; content, the model keeps it where it is.)
        val capture = control != null && g.id == watched
        val content = !restless && !relief && g.fed >= CONTENT && (date.absoluteDay / days + g.id) % every != 0L
        if (content && !capture && !shift) { planCounts.incrementAndGet(s.index * 3); return Plan(g.place, intArrayOf(here.i)) }
        // (A big herd moves more slowly than a small one: it waits for its stragglers.)
        // (A starving herd on eaten-out ground goes as far as it must, up to its whole week's travel.)
        // (A herd far behind its round treks: the whole week's going.)
        val shiftDays = if (onRound && goalFar && !s.carnivore && g.fed >= 0.6f) tuning.roundTrekDays
            else if (restless && !s.carnivore) SHIFT_DAYS + (days - SHIFT_DAYS) * (1f - g.fed / 0.7f).coerceIn(0f, 1f) else SHIFT_DAYS
        // (A band goes at the pace of its slowest: the old, the sick and the lamed hold it back.)
        val weak = g.band?.let { b -> ((b.elders + (b.hurt + b.lame) * g.adults + b.sick.sum() * g.size) / g.size.coerceAtLeast(1)).coerceIn(0f, 1f) } ?: 0f
        // (A herd spreading out on arrival ([spreadOut]) moves on a day's going at most.)
        val reach = if (shift) s.dailyTravelKm else (if (restless) s.maxDailyTravelKm else s.dailyTravelKm) * shiftDays * (if (s.carnivore) 1f else bandsIn(g).pow(-BIG_HERD_SLOW)) *
            (1f - WEAK_SLOW * weak) *
            (if (s.human) 1f / (1f + COVER_GOING * land.plants.cover(g.place)) else 1f)
        val kind = kindIndex[s] ?: 0
        // Least effort to each hex within reach.
        val buf = scratch.get()
        val dist = buf.dist; val via = buf.via; val reached = buf.reached
        searchReach(g, here.i, reach, restless, weather, buf)
        lap("choosing: reach (least-effort search)")
        var best = g.place; var bestScore = Float.NEGATIVE_INFINITY
        val policy = policies[s]
        // (For a followed group: every place it weighed, and how it scored.)
        // planDiag=kind: once a month, one herd's reasons, for the place it chose and the best-placed step toward its season's place.
        val diag = PLAN_DIAG == s.name && goal >= 0 && date.dayOfMonth <= days && g.id % 97 == 0
        val seen = if (g.id == watched || diag) ArrayList<Pair<Int, Float>>() else null
        val candPlaces = buf.candPlaces; candPlaces.clear(); val candFeats = buf.candFeats
        // (Every place's whole score is wanted for a network, for capture and for a watched herd; else only the best.)
        val wantAll = seen != null || policy != null || capture
        buf.laterPlace.clear()
        val season = 2 * Math.PI * (date.dayOfYear - Date.START_DAY) / Date.DAYS_PER_YEAR
        // Its ground is as big as the game on it needs it to be (smaller where game is thick), and a
        // hungry pack ranges wider: up to three times that after weeks of hunger.
        val homeValue = if (!g.home.none) placeValue[kind][hw.sameBandPlace(g.place.i, g.home.i).takeIf { it >= 0 } ?: g.place.i] else PREY_HALF
        // (A pack with young in the den, from its births for [inflation.Tuning.denMonths], hunts only
        // within [inflation.Tuning.denKm] of its home, however hungry: it cannot follow game that moves on.)
        val denning = s.pack && !g.home.none && g.young > 0 && monthsFrom(g.birthMonth, month) < tuning.denMonths
        val territory = if (denning) tuning.denKm else territoryKm(s) * sqrt(PREY_HALF / homeValue.coerceAtLeast(1f)).coerceIn(0.5f, 3f) *
            (1f + min(2f, g.lean.toFloat() / GIVE_UP_WEEKS))
        val goalHex = if (goal >= 0) hw.placeHex[goal] else -1
        val fromHere = if (goal >= 0) hexKm(here.i, goalHex) else 0.0
        val pullKm = reach.toDouble().coerceAtLeast(hex.hexKm)
        // (A band keeps off the known homes of bands it is not close to: those hexes, once for the week.)
        val keepOff = g.band?.let { b -> b.knownHomes.entries.filter { b.tie(it.key) <= 0f }.map { it.value }.toIntArray() }
        val c = PlanContext(g, kind, here, sensed, month, goal, goalQ, onRound, roundStrength, fromHere, reach, thirsty, keepOff, weather, date)
        c.via = via; c.dist = dist; c.shift = shift
        if (!s.carnivore) planGround.ranges[g.id]?.let { c.own = it.toMap() }
        if (!s.carnivore) { val hs = ArrayList<Int>(7); val ss = ArrayList<Float>(7); rangeShares(g, here.i) { q, share -> hs += q; ss += share }
            c.ownHexes = hs.toIntArray(); c.ownShares = ss.toFloatArray() }
        // (Stopped short of the place it chose, it weighs again only if where it stands is clearly deadlier than it foresaw
        // there ([before]: see [spreadOut]).)
        if (shift && before >= 0f && survivalSteps(g, deathsAt(c, g.place, here, 0f, 0f, 0f)) - survivalSteps(g, before) <= 1f)
            return Plan(g.place, intArrayOf(here.i))
        for (ri in 0 until reached.size) {
            val h = Hex(reached[ri])
            val d = dist[h]
            // How far beyond its ground the hex lies (0 within it), and how fresh another pack's mark on it is.
            val beyond = if (s.pack && !g.home.none) (hexKm(h, g.home) / territory - 1.0).toFloat().coerceIn(0f, 2f) else 0f
            val foreign = if (s.pack && h != g.home) scent.foreign(s, h, g.id) else 0f
            // A pack on its own ground stays on it while fed, and keeps off ground another pack of its kind holds
            // (its choice: the hand-written score weighs these heavily; a learned one sees both, see [HexFeatures]).
            val offGround = (if (beyond > 0f && (denning || (g.fed >= Thresholds.HUNGRY && !astray))) KEEP_TO_GROUND else 0f) +
                (if (foreign > MARK_FRESH && g.fed >= 0.3f) KEEP_OFF_MARKS else 0f)
            val toGoal = if (goal >= 0) hexKm(h.i, goalHex) else 0.0
            // The open water on the way there, as the share of it a crossing drowns (see [move]): worked out when first wanted.
            var drowning = -1f
            // Better country further off: the way the kind's sense of the land points.
            val pull = bearings.along(kind, here.i, h.i, pullKm)
            // And a herd goes on the way it has been going, as far as it has kept to one way (see [HexGroup.headE]).
            val keepOn = if (s.carnivore) 0f else {
                val (x0, y0) = hex.centreKm(here.i); val (x1, y1) = hex.centreKm(h.i)
                ((g.headE * (x1 - x0) + g.headS * (y1 - y0)) / pullKm).toFloat()
            }
            for (b in 0 until HexWorld.BANDS_M.size) {
                val p = hw.placeIn(b, h)
                if (p.none) continue
                // (Its own kind there, as it knows it.)
                val crowded = crowdOf(s, p.i, knownHeads(kind, p, h, sensed) - (if (p == g.place) g.size else 0))
                val prey = if (s.carnivore) knownGame(g, kind, p, h, sensed) else 0f
                val hunters = huntersKm2(p, h)
                val t = buf.terms.also { fillTerms(it, g, p, weather, date) }
                var score = scorePlace(c, t, p, h, d, toGoal, offGround, pull, keepOn, prey, crowded, hunters)
                // (Above all, it keeps alive: see below.)
                val beside = 0.02f * (hashNoise(g.id, p.i) - 0.5f) + temperPull(g, p, h, hunters, crowded)
                // (Where only the best place is wanted, its deaths are worked out after, best first, see below.)
                if (!wantAll) { buf.later(p.i, d, prey, crowded, score, beside); continue }
                if (drowning < 0f) drowning = crossingDeaths(s, h.i, here.i, via)
                score -= survivalSteps(g, deathsAt(c, p, h, d, prey, crowded) + drowning)
                seen?.add(p.i to score)
                if (policy != null || capture) {
                    val nc = candPlaces.size
                    if (nc == candFeats.size) candFeats += FloatArray(Feature.SIZE)
                    val f = candFeats[nc].also { it.fill(0f) }
                    fillFeatures(f, t, g, p, h, here, month, prey, crowded, hunters, d, reach, goal, fromHere, toGoal, goalQ, onRound, roundStrength, season,
                        restless, pull, beyond, foreign, keepOn, weather, date)
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
                val p = Place(buf.laterPlace[k]); val h = hw.hexOf(p)
                val total = buf.laterScore[k] - survivalSteps(g, deathsAt(c, p, h, buf.laterD[k], buf.laterPrey[k], buf.laterCrowd[k]) +
                    crossingDeaths(s, h.i, here.i, via)) + buf.laterBeside[k]
                if (total > bestScore || (total == bestScore && k < bestK)) { bestScore = total; best = p; bestK = k }
            }
        }
        lap("choosing: weighing places (hand-written score)")
        if (policy != null && candPlaces.size > 0) {
            val nc = candPlaces.size
            best = Place(candPlaces[policy.choose(g, Array(nc) { candFeats[it] }, buf.candScores.copyOf(nc), policyRandom(g.id, date.absoluteDay))])
        }
        lap("choosing: network")
        // The hexes passed on the way to [p], by the cheapest chain.
        fun pathTo(p: Int): IntArray {
            val chain = ArrayList<Int>(); var h = hw.placeHex[p]
            while (true) { chain += h; if (h == here.i) break; h = via[h].takeIf { it >= 0 } ?: break }
            chain.reverse(); return chain.toIntArray()
        }
        if (capture) best = Place(askControl(g, weather, date, policy, buf.candPlaces, buf.candFeats, buf.candScores, if (content) g.place.i else best.i, content,
            restless, reach, goal, onRound) { pathTo(it) })
        if (seen != null && g.id == watched) {
            fun where(p: Int) = "hex %d at %.0f m".format(hw.placeHex[p], hw.placeElevationM[p])
            val top = seen.sortedByDescending { it.second }.take(5)
            watchLog += "weighed %d places%s; went to %s (hand-written score %.2f%s)".format(seen.size, if (restless) ", restless" else "", where(best.i),
                seen.firstOrNull { it.first == best.i }?.second ?: 0f, if (policy != null) ", chosen by the network" else "")
            for ((p, score) in top) watchLog += "   %s%s: %.2f".format(where(p), if (p == g.place.i) " (stay)" else "", score)
        }
        if (diag && seen != null) {
            // (The score is the hand-written one before its small noise and the group's temper, as [scorePlace] gives it.)
            fun parts(p: Place): String {
                val h = hw.hexOf(p); val hunters = huntersKm2(p, h)
                return "score %.2f: food %.2f fresh %.2f crowd %.2f hunters %.2f kg/km2 water %.1f km going %.0f km (%.0f km from goal)".format(
                    seen.firstOrNull { it.first == p.i }?.second ?: Float.NaN, placeValue[kind][p], freshness(s, p.i),
                    crowdOf(s, p.i, knownHeads(kind, p, h, sensed) - (if (p == g.place) g.size else 0)), hunters,
                    if (s.human) land.drink.peopleWaterKm(p.i) else land.drink.waterKm[p], dist[h], hexKm(h.i, goalHex))
            }
            val towards = (0 until reached.size).map { reached[it] }.minByOrNull { hexKm(it, goalHex) }?.let { h ->
                hw.sameBandPlace(g.place.i, h).takeIf { it >= 0 } ?: hex.heart[h] }?.takeIf { it >= 0 }?.let { Place(it) }
            println("plan ${s.name} #${g.id} month ${date.month + 1}: chose " + parts(best) + (towards?.let { " | toward goal " + parts(it) } ?: ""))
        }
        if (shift) return Plan(best, pathTo(best.i))
        planCounts.incrementAndGet(s.index * 3 + (if (best == g.place) 1 else 2))
        val crowd = if (s.carnivore) -1f else foreseenCrowd(c, best.i, hw.placeHex[best])
        if (crowdDiag == s.name) { foreseenCrowd[g.id] = crowd; choiceDiag[g.id] = ChoiceDiag(c.ownHexes, c.ownShares, c.own, best.i) }
        if (!s.carnivore) {
            // (What it foresees dying of where it chose, the meetings on the way left out: should the hunters stop it short,
            // it weighs again if where it stands is clearly deadlier, see [spreadOut].)
            val bh = hw.hexOf(best)
            c.shift = true; chosenDying[g.id] = deathsAt(c, best, bh, dist[bh], 0f, 0f); c.shift = false
            chosenPlace[g.id] = best.i
        }
        if (deathDiag == s.name) {
            // (The deaths it foresaw where it chose, by cause, and how fed it foresaw it would be there.)
            val bh = hw.hexOf(best); val m = HashMap<String, FloatArray>()
            c.odds.byCause = m
            deathsAt(c, best, bh, dist[bh], 0f, 0f)
            c.odds.byCause = null
            val drown = crossingDeaths(s, bh.i, here.i, via); if (drown > 0f) m["drowned crossing"] = floatArrayOf(drown, drown)
            foreseenDeaths[g.id] = m; foreseenFed[g.id] = foreseeFed(c, best.i, bh.i, dist[bh]); chosenDiag[g.id] = best.i
        }
        return Plan(best, pathTo(best.i), crowd)
    }

    /** What a group weighing its places this week brings to each of them: worked out once in [planInner], read by [scorePlace]. */
    private class PlanContext(val g: HexGroup, val kind: Int, val here: Hex, val sensed: IntArray, val month: Int, val goal: Int, val goalQ: Float,
        val onRound: Boolean, val roundStrength: Float, val fromHere: Double, val reach: Float, val thirsty: Boolean, val keepOff: IntArray?,
        val weather: Weather, val date: Date) {
        /** For a herd: the hexes of the ground it grazes where it is now, and its share on each (its own heads in [grazed]). */
        var ownHexes = IntArray(0); var ownShares = FloatArray(0)
        /** For a herd: its share of the week on each place of the ground it grazes where it is now (its own heads in [planGround]). */
        var own: Map<Int, Float> = emptyMap()
        /** Its diet as it leans this week ([herdDiet]), for a herd. */
        val diet = if (g.species.carnivore) g.species.diet else herdDiet(g)
        /** Reused for each place it weighs: see [foreseeFed], [deathsAt] and [huntedAhead]. */
        val pantry = DryPantry(); val odds = Odds(); val hunted = FloatArray(4); val kill = FloatArray(3)
        /** The hex it reaches each hex from, on its cheapest way from [here], and the going to each, km. */
        var via = IntArray(0); var dist = FloatArray(0)
        /**
         * For a herd, from [huntedAhead]: the hexes on its way where hunting parties may chase it and stop it short, from
         * [here] on ([stops] of them), the odds that it is stopped in each, and the odds that it gets through ([through]).
         */
        var stopHex = IntArray(8); var stopOdds = FloatArray(8); var stops = 0; var through = 1f
        /** The share of it that would die were it stopped in a hex (see [deathsAt]), by hex, as worked out. */
        val stoppedDeaths = HashMap<Int, Float>()
        /** Whether it weighs again as it arrives, its week's meetings over (see [spreadOut]). */
        var shift = false
    }

    /**
     * The hand-written score of place [p] (in hex [h], [d] km of going away) for the group weighing its places in [c]:
     * its food or game, crowding and hunters, the cold, heat and flies, its own kind of country, what it senses coming,
     * water, the going, the season's place or round, its bearing and heading, staying, and a band's own pulls.
     */
    private fun scorePlace(c: PlanContext, t: PlaceTerms, p: Place, h: Hex, d: Float, toGoal: Double, offGround: Float, pull: Float, keepOn: Float,
        prey: Float, crowded: Float, hunters: Float): Float {
        val g = c.g; val s = g.species; val kind = c.kind; val here = c.here; val sensed = c.sensed; val month = c.month; val goal = c.goal
        val goalQ = c.goalQ; val onRound = c.onRound; val roundStrength = c.roundStrength; val fromHere = c.fromHere; val reach = c.reach
        val thirsty = c.thirsty; val keepOff = c.keepOff; val weather = c.weather; val date = c.date
        // Its food or game there, against crowding (see [foodTerm]).
        var score = foodTerm(g, kind, t.fresh, p, h, sensed, prey, crowded)
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
        if (thirsty && !canDrink(s, p.i)) score -= 1f
        if (weather.snowpack[p] > s.snowLimitMm) score -= 1f
        // The going costs food: a share of the week's need (see [walkCost]).
        score -= WALK_COST * d / days
        // (Above all, it keeps alive: its deaths there are weighed against all this by the caller, see [planInner].)
        // Herds and bands remember where they did best each month and are drawn back there.
        if (goal >= 0 && (!s.carnivore || s.human)) {
            // The season's place draws a herd on, the more so the better it promises and the worse it fares now.
            // Bands that watch the sky keep their round on time.
            val sky = if (g.band?.holds(Tradition.SKY_LORE) == true) 1.3f else 1f
            val prog = ((fromHere - toGoal) / min(fromHere, reach.toDouble())).toFloat().coerceIn(-1f, 1f)
            // (A round draws it on however well it fares where it is: it is what its kind does in this month.)
            // (A hungry herd stops to graze before going on: the round draws it the less the hungrier it is.)
            if (fromHere > hex.hexKm) score += if (onRound) sky * tuning.roundPull * roundStrength * (g.fed / 0.7f).coerceIn(0f, 1f) * prog
                else sky * SEASON_PULL * (0.3f * goalQ + (goalQ - g.fed).coerceIn(0f, 1f)) * prog
        }
        score += tuning.bearingPull * pull + tuning.headingPull * keepOn
        if (p == g.place) score += STAY - (g.band?.let { stayCost(it) } ?: 0f)
        // Bold animals of kinds that follow people are drawn to their camps: refuse, scraps, warmth.
        if (TAMING && s.followsPeople && peopleSlot >= 0) score += CAMP_LURE * boldness(g) * min(1f, knownHeads(peopleSlot, p, h, sensed) / 25f)
        g.band?.let { b -> score += bandPull(g, b, month, h, here, p, keepOff, sensed) }
        // A hungry pack gives up ground it has hunted out lately and looks further afield.
        if (s.carnivore && g.fed < Thresholds.HUNGRY && (h == here || h in g.recent)) score -= HUNGRY_LEAVE
        return score
    }

    /**
     * The share of [c]'s group that would die this week at place [p] (in hex [h], [d] km of going away), by the rules that
     * kill it there ([placeHazards], as [liveWeek] has them): hunger at how fed it foresees it would be ([foreseeFed] for a
     * herd; for hunters the game they know of there, [prey], against their own kind's [crowded]ing), thirst, the cold, its
     * kind's sickness where it crowds, fire, flood and deep snow, plague, crocodiles, eagles, the young short of protein,
     * for a herd the hunters ([huntedAhead]), and for a band snakes and the beasts that take people (those of the groups
     * about only where it senses them). The
     * young's odds count for the young. (What it shares wherever it goes, age, the background and its own band's ills, is
     * left out: it cannot tell places apart.) A herd that hunting parties may chase on its way, and so stop short (see
     * [meet]), weighs what it would die of where it stopped by those odds ([huntedAhead]).
     */
    private fun deathsAt(c: PlanContext, p: Place, h: Hex, d: Float, prey: Float, crowded: Float): Float {
        val g = c.g; val s = g.species; val weather = c.weather; val b = g.band
        // (For a herd, first the hunters ahead, and where it may be stopped short: worked out before the rest, which
        // weighing the stops reuses.)
        val w = c.odds.weight
        var partyA = 0f; var partyY = 0f; var resA = 0f; var resY = 0f; var through = 1f; var stopped = 0f
        if (!s.carnivore) {
            huntedAhead(c, p, h, d)
            val hu = c.hunted; resA = hu[2]; resY = hu[3]; partyA = hu[0] - hu[2]; partyY = hu[1] - hu[3]
            through = c.through
            val n = c.stops
            c.odds.byCause?.let { m -> m.getOrPut("hunting parties") { FloatArray(2) }.let { it[0] += w * partyA; it[1] += w * partyY } }
            for (i in 0 until n) {
                val q = c.stopHex[i]; val o = c.stopOdds[i]
                if (o <= 0f) continue
                stopped += o * if (c.odds.byCause == null) c.stoppedDeaths.getOrPut(q) { stoppedAt(c, q) } else {
                    c.odds.weight = w * o; stoppedAt(c, q).also { c.odds.weight = w } }
            }
        }
        c.odds.weight = w * through
        val fed = if (s.carnivore) (prey / (prey + PREY_HALF) - 0.5f * crowded).coerceIn(0f, 1f) else foreseeFed(c, p.i, h.i, d)
        val protein = if (s.carnivore) g.protein else c.pantry.protein
        val thirst = thirstOdds(g, p.i, dryAfter(g, p.i, fed))
        // (Herds sicken where their kind crowds: its kind's animals of both forms over each hex of the ground it would graze
        // there ([grazed], less its own spread where it is now), and its own spread over that ground.)
        val sick = if (s.carnivore) 0f else crowdSickness(foreseenCrowd(c, p.i, h.i), fed)
        val odds = c.odds.also { it.adult = 0f; it.young = 0f }
        placeHazards(g, p.i, weather, fed, protein, thirst, sick, b?.let { fireAt(g, it, p.i) } ?: 0f, b?.let { atHouse(it, p.i) } ?: false, odds)
        var adult = odds.adult + resA + partyA; var young = odds.young + resY + partyY
        c.odds.byCause?.let { m -> m.getOrPut("resident hunters") { FloatArray(2) }.let { it[0] += w * through * resA; it[1] += w * through * resY } }
        c.odds.weight = w
        if (b != null) {
            val venom = venomOdds(s, p.i, weather)
            val stalked = stalkRate(g, b, p.i, residentManEaters(p.i) + (if (h in c.sensed) manEaterKm2[h.i] else 0f))
            adult += venom + stalked; young += venom + YOUNG_STALKED / b.childWatch.coerceAtLeast(0.25f) * stalked
            // The sicknesses it would catch there (marsh fever by the mosquitoes, the flux in a long-kept camp, the cough
            // where people crowd), and the fouling of a camp kept on: weeks camped there go on if it stays, else start afresh.
            val campWeeks = if (p == g.place) b.campWeeks + 1 else 0
            val crowd = peopleCrowding(g, h.i); val t = weather.temperature[p]; val healed = healedShare(b)
            adult += illnessAhead(b, p.i, t, crowd, campWeeks, healed, false)
            young += illnessAhead(b, p.i, t, crowd, campWeeks, healed, true) + campFouling(g, campWeeks) * years
            // What it would catch of the bands it sees gathered there (a feast draws bands together, and the cough with them).
            if (h in c.sensed && peopleSlot >= 0) {
                val stays = p == g.place
                val others = headsHere[peopleSlot][p] - (if (stays) g.size else 0)
                if (others > 0) for (ill in Illness.entries) if (ill.contagion > 0f) {
                    val theirSick = (sickPeople[ill.ordinal][p] - (if (stays) g.size * b.sick[ill] else 0f)).coerceAtLeast(0f) / others
                    val dying = caughtFrom(ill, theirSick, b) * ill.deadly * healed
                    adult += dying; young += dying * ill.young
                }
            }
        }
        val there = (g.adults * adult.coerceIn(0f, 0.9f) + g.young * young.coerceIn(0f, 0.95f)) / max(1, g.size)
        if (through >= 1f) return there
        // (Stopped short, it is still hunted on the way as far as it got: the hunting parties' kills count whatever the stop.)
        return through * there + (1f - through) * (g.adults * partyA.coerceIn(0f, 0.9f) + g.young * partyY.coerceIn(0f, 0.95f)) / max(1, g.size) + stopped
    }

    /**
     * The share of [c]'s herd that would die were it chased and stopped in hex [q] on its way ([stopsAt], as [meet] has
     * it): its deaths there as if it had chosen it, the hunting parties left out (they are weighed on its way), as
     * [deathsAt] has them.
     */
    private fun stoppedAt(c: PlanContext, q: Int): Float {
        val shift = c.shift
        c.shift = true
        return deathsAt(c, stopsAt(c.g, q), Hex(q), c.dist[q], 0f, 0f).also { c.shift = shift }
    }

    /**
     * The share of [c]'s herd, adults and young (into [PlanContext.hunted]: all hunters, then the residents' alone), the hunters would kill were it to go to place
     * [p] (in hex [h], [d] km of going away): the residents' hunters about the ground it would graze there, as they hunted
     * this week ([HexFauna.residentKillOdds]; they find it there as next week begins), and the hunting parties it would
     * meet on its way and where it grazes ([herdKillsAhead]): those in each hex where it senses them, and its kind's usual
     * losses to them ([usualGroupKill]) where it does not. (Weighing again as it arrives, its week's meetings are over:
     * only the residents' hunters are still ahead.)
     */
    private fun huntedAhead(c: PlanContext, p: Place, h: Hex, d: Float) {
        val g = c.g; val s = g.species; val weather = c.weather
        var adult = 0f; var young = 0f
        rangeShares(g, h.i) { q, share ->
            val pq = hw.sameBandPlace(p.i, q).takeIf { it >= 0 } ?: p.i
            adult += share * fauna.residentKillOdds(s.massKg, pq); young += share * fauna.residentKillOdds(HexFauna.YOUNG_WEIGHT * s.massKg, pq)
        }
        c.hunted[2] = adult; c.hunted[3] = young
        c.stops = 0; c.through = 1f
        if (!c.shift) {
            var killedA = 0f; var killedY = 0f; var usual = 0f
            val day = weather.date.absoluteDay.toDouble(); val k = c.kill
            // (The kills it would suffer about hex [q] for [span] days, and the odds it is chased there.)
            fun stay(q: Int, span: Float): Float {
                if (q !in c.sensed) { usual += span / days; return 1f - exp(-usualGroupChase[s.index] * span / days) }
                val list = huntersIn[q] ?: return 0f
                val pq = Place(hw.sameBandPlace(p.i, q).takeIf { it >= 0 } ?: p.i)
                var free = 1f
                for (hunter in list) { herdKillsAhead(hunter, g, pq, span, weather, day, k); killedA += k[0]; killedY += k[1]; free *= 1f - k[2] }
                return 1f - free
            }
            // (On its way, its going's days spread evenly over the hexes it passes, as [meet] has them; then where it grazes.
            // A chase on the way stops it in that hex, as [meet] does.)
            val arrive = min(days.toFloat(), d / s.dailyTravelKm.coerceAtLeast(1e-3f))
            var steps = 0; var q = h.i
            while (q != c.here.i) { q = c.via[q].takeIf { it >= 0 } ?: break; steps++ }
            if (c.stopHex.size < steps) { c.stopHex = IntArray(steps); c.stopOdds = FloatArray(steps) }
            q = h.i
            for (i in steps - 1 downTo 0) { q = c.via[q]; c.stopHex[i] = q; c.stopOdds[i] = stay(q, arrive / steps) }
            for (i in 0 until steps) { val o = c.stopOdds[i] * c.through; c.through -= o; c.stopOdds[i] = o }
            c.stops = steps
            rangeShares(g, h.i) { r, share -> stay(r, (days - arrive) * share) }
            adult += killedA / max(1, g.adults) + usual * usualGroupKill[s.index]
            young += killedY / max(1, g.young) + usual * usualGroupKill[s.index]
        }
        c.hunted[0] = adult; c.hunted[1] = young
    }

    /**
     * How crowded [c]'s herd would be at place [p] (in hex [h]), as [sicknessWeek] reckons it ([herdCrowd]): its kind's
     * residents, the other herds as they are now ([grazed], [planGround], less its own heads where it is now) and its own
     * heads spread over the ground it would cover from there.
     */
    private fun foreseenCrowd(c: PlanContext, p: Int, h: Int): Float {
        val g = c.g; val a = grazed[c.kind]; val heads = planGround.heads
        return herdCrowd(g, h, grazingRange(g, p), { q, share -> max(0f, a[q] - ownShare(c, q) * g.size) + share * g.size }) { q, share ->
            max(0f, (heads[groundKey(c.kind, q)] ?: 0f) - (c.own[q] ?: 0f) * g.size) + share * g.size }
    }

    /**
     * For crowdDiag: how crowded herd [g] would be grazing [range] about hex [h] had the other herds and the residents
     * stayed as they were when it chose ([grazed], [planGround], less its own heads as it chose).
     */
    internal fun planCrowd(g: HexGroup, h: Int, range: List<Pair<Int, Float>>): Float? {
        val d = choiceDiag[g.id] ?: return null
        val a = grazed[kindSlot[g.species.index]]; val heads = planGround.heads; val k = kindSlot[g.species.index]
        fun ownAt(q: Int): Float { for (i in d.ownHexes.indices) if (d.ownHexes[i] == q) return d.ownShares[i]; return 0f }
        reachedChoice[g.id] = g.place.i == d.place
        return herdCrowd(g, h, range, { q, share -> max(0f, a[q] - ownAt(q) * g.size) + share * g.size }) { q, share ->
            max(0f, (heads[groundKey(k, q)] ?: 0f) - (d.own[q] ?: 0f) * g.size) + share * g.size }
    }

    /** The share of [c]'s herd at hex [q], spread over the ground it grazes where it is now (as it counts in [grazed] and [herdWantKg]). */
    private fun ownShare(c: PlanContext, q: Int): Float {
        for (i in 0 until c.ownHexes.size) if (c.ownHexes[i] == q) return c.ownShares[i]
        return 0f
    }

    /**
     * How fed [c]'s herd foresees it would be this week at place [p] (in hex [h], [d] km of going away), and the protein
     * it would get there, by the rule it grazes by ([forage]) without eating anything: its week's need, and the going's,
     * asked of the place as far as the forage the residents left there ([HexFauna.grazeLeft]) holds its range's (the rest
     * from ground like it about), from what is left of it after the other herds about would have eaten theirs ([herdWantKg]).
     */
    private fun foreseeFed(c: PlanContext, p: Int, h: Int, d: Float): Float {
        val g = c.g; val s = g.species
        val wantKg = g.dailyNeedKg * days * (1f + WALK_COST * d / days)
        val m2 = land.areaKm2[p] * 1e6f
        val food = fauna.food
        val stock = fauna.grazeLeft[p] + fauna.browseLeft[p] + food.fruit[p] + food.roots[p]
        val dry = c.pantry
        dry.protein = g.protein
        if (stock <= 0f || wantKg <= 0f) return 0f
        val share = min(1f, stock * m2 / (wantKg * RANGE_NEED))
        val want = wantKg * share / m2
        // (The other herds' week's need on this ground, kg per m², shared out over what is there.)
        val others = max(0f, herdWantKg[h] - ownShare(c, h) * g.dailyNeedKg * days) / (hex.areaKm2.toFloat() * 1e6f)
        val left = (1f - others / stock).coerceIn(0f, 1f)
        val reach = grazeAccess(s, p)
        dry.reach = reach
        dry.grass = left * fauna.grazeLeft[p]; dry.browse = left * fauna.browseLeft[p]
        dry.fruit = left * food.fruit[p]; dry.roots = left * food.roots[p]; dry.straw = left * food.straw[p]
        val b = forage(s, c.diet, p, want, c.weather.snowpack[p] > s.snowLimitMm, reach, dry)
        val eaten = b.eaten
        if (eaten > 0f) dry.protein = g.protein + (fauna.proteinMet(protein(b, p) / eaten) - g.protein) * HexFauna.PROTEIN_MEMORY
        return (b.got / want).coerceIn(0f, 1f)
    }

    /** A place's food as a herd only reckons on it ([foreseeFed]): what it would get of each, taken off what is there, without eating it; and the protein it would come away with. */
    private class DryPantry : Pantry {
        var reach = 0f; var grass = 0f; var browse = 0f; var fruit = 0f; var roots = 0f; var straw = 0f; var protein = 0f
        override fun grass(kg: Float): Float { val t = min(kg, reach * grass); grass -= t; return t }
        override fun browse(kg: Float): Float { val t = min(kg, reach * browse); browse -= t; return t }
        override fun fruit(kg: Float): Float { val t = min(kg, reach * fruit); fruit -= t; return t }
        override fun roots(kg: Float): Float { val t = min(kg, reach * roots); roots -= t; return t }
        override fun straw(kg: Float): Float { val t = min(kg, straw); straw -= t; return t }
    }

    /** The odds of the hazards at a place, summed ([deathsAt]). */
    private class Odds : HazardSink {
        var adult = 0f; var young = 0f
        /** For deathDiag: the odds by cause, adults and young, while it is set, each by the odds of the case it is in ([weight]). */
        var byCause: HashMap<String, FloatArray>? = null
        var weight = 1f
        override fun add(cause: String, adult: Float, young: Float) {
            this.adult += adult; this.young += young
            byCause?.let { m -> val v = m.getOrPut(cause) { FloatArray(2) }; v[0] += weight * adult; v[1] += weight * young }
        }
    }

    /**
     * The share of a group of [s] that drowns on the way from hex [here] to hex [to] along the cheapest chain ([via]):
     * each strait crossed, as [move] drowns it (the young twice as many, counted here as grown).
     */
    private fun crossingDeaths(s: Species, to: Int, here: Int, via: IntArray): Float {
        var risk = 0f; var h = to
        while (h != here) {
            val from = via[h]; if (from < 0) break
            val gap = seaGap(from, h)
            if (gap.isFinite()) risk += DROWN * gap / Catalogue.swimKm(s).coerceAtLeast(1f)
            h = from
        }
        return min(0.9f, risk)
    }

    /**
     * Deaths [dying] (a share of [g] this week) in steps of the chance in a week's deaths: the spread of the share that dies
     * of a group of [g]'s size at the odds it lives under now (at least its kind's background). A difference within a step
     * is no more than chance and weighs as one unit of the rest of the score; one of many steps outweighs all of it.
     */
    internal fun survivalSteps(g: HexGroup, dying: Float): Float {
        val q = max(g.deathOdds, g.species.backgroundMortality * years).coerceIn(1e-6f, 0.5f)
        return dying / sqrt(q * (1f - q) / max(1, g.size))
    }

    internal fun hexKm(a: Int, b: Int): Double = hex.distanceKm(a, b)
    internal fun hexKm(a: Hex, b: Hex): Double = hex.distanceKm(a.i, b.i)
    internal fun territoryKm(s: Species) = sqrt(max(1f, s.startKm2PerAnimal) * s.groupSize / Math.PI.toFloat()) + 5f

    internal fun discomfort(s: Species, p: Int, weather: Weather): Float {
        return s.discomfortC(weather.temperature[p], weather.date.month) / 10f
    }

    /**
     * What place [p] drains from group [g] this week, as a share of its week's need (the unit the going
     * is reckoned in): the extra food it burns keeping warm in the cold its coat (or clothes, fire and
     * shelter) does not keep off ([coldNeed]), and what the biting insects cost it ([bitten]).
     */
    internal fun drain(g: HexGroup, p: Int, weather: Weather): Float {
        val s = g.species; val t = weather.temperature[p]
        return coldNeed(s, g.band?.let { b -> b.feels(t, fireAt(g, b, p), atHouse(b, p)) } ?: t, weather.date.month, tuning) - 1f + bitten(s, p)
    }

    /**
     * The fire band [b] (group [g]) would keep camped at place [p]: its fire now, less where there is less wood about to
     * feed it than where it is (the hearth is fed from the trees and scrub round the camp, see [fetchFuel]).
     */
    internal fun fireAt(g: HexGroup, b: Band, p: Int): Float {
        if (p == g.place.i) return b.fire
        fun wood(q: Int) = min(1f, (land.plants.tree[q] + land.plants.shrub[q]) / 3f)
        val here = wood(g.place.i)
        return if (here <= 0f) b.fire else b.fire * min(1f, wood(p) / here)
    }

    /** Whether band [b] camped at place [p] would be at its house. */
    internal fun atHouse(b: Band, p: Int) = b.houseHex.i == hw.placeHex[p] && b.house > 0.05f

    /** The least [drain] at the places next to hex [here] (in the group's band of places). */
    private fun leastDrainNear(g: HexGroup, here: Int, weather: Weather): Float {
        val b = hw.placeBand[g.place]
        var least = Float.MAX_VALUE
        for (k in 0 until 6) {
            val q = hex.neighbour(here, k)
            if (q < 0) continue
            val p = hw.placeOf[b][q]
            if (p >= 0) least = min(least, drain(g, p, weather))
        }
        return if (least == Float.MAX_VALUE) drain(g, g.place.i, weather) else least
    }

    /**
     * What the biting insects at [p] cost a group of kind [s] this week, as a share: of the feeding time
     * a plant-eater loses to them, of the working time people lose. Hunters lose nothing to them here,
     * so they do not mind them.
     */
    internal fun bitten(s: Species, p: Int): Float {
        val h = small.harassment(p)
        return when {
            h <= 0f -> 0f
            s.human -> HexSmallLife.HARASS_TIME * h / (1f + HexSmallLife.HARASS_TIME * h)
            s.carnivore -> 0f
            else -> HexSmallLife.HARASS_FEED * h
        }
    }

    internal fun crowd(s: Species, p: Int, g: HexGroup): Float = crowdOf(s, p, (headsHere[kindSlot[s.index]][p] - (if (p == g.place.i) g.size else 0)).toFloat())
    internal fun crowdOf(s: Species, p: Int, others: Float): Float {
        // Heads of its kind a place usually holds: for herds, their usual density as they live gathered (as for disease).
        val usual = if (s.startKm2PerAnimal > 0f) land.areaKm2[p] / s.startKm2PerAnimal * 3f else land.areaKm2[p] * 0.1f * MIGRANTS_GATHER
        return others / (others + usual + 1f)
    }

    /**
     * What place [p] offers a hunter of kind [s] by its whole diet, kg/km²: the big game it can catch
     * ([game]), the small prey on the ground and the carrion lying there, each by its share of the
     * diet. A jackal looks for rodents and carrion, a lion for big game.
     */
    internal fun dietValue(s: Species, p: Int, game: Float): Float {
        // Small game is searched for over a narrower strip than big game, and carrion only by those that eat it.
        val smallWorth = smallCatch(s) * SMALL_SENSE_KM / (s.senseKm * SEARCHING * CATCH)
        return game + smallWorth * small.smallPreyKg(p) + (if (s.diet.carrion > 0f) KgM2(small.carrion[p]).perKm2.v else 0f)
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
    /** The same, with the heads a group expects ([heads], by kind slot and place). */
    private fun herdPrey(s: Species, p: Int, heads: Array<FloatArray>): Float {
        var v = 0f
        for ((k, ps) in kinds.withIndex()) {
            if (ps.carnivore) continue
            val hd = heads[k][p]
            if (hd > 0f) v += hd * ps.massKg * catchable(s, ps, p) / land.areaKm2[p]
        }
        return v
    }

    /** Mean moonlight over this week's nights, 0 (new moon) to 1 (full). */
    internal var moon = 0.5f

    /** Time spent settling meetings, ns, over the run (for measuring the cost). */
    @Transient var meetNs = 0L


    internal fun poisson(mean: Float, r: Random): Int = inflation.poisson(mean, r)
    internal fun binomial(n: Int, p: Float, r: Random): Int = inflation.binomial(n, p, r)
    internal fun gaussian(r: Random): Float = inflation.gaussian(r).toFloat()

    /** A random stream of group [id]'s own for the week starting on [day]: the same whatever the cores do. */
    internal fun groupRandom(id: Int, day: Long) = Random(seedBase * 1_000_003L + id * 7_919L + day)
    /** A group's own stream for its choice of ground this week (apart from [groupRandom]'s, which its week's life draws on). */
    internal fun policyRandom(id: Int, day: Long) = Random((seedBase * 1_000_003L + id * 7_919L + day) * 0x9E3779B97F4A7C15uL.toLong() + 0x2545F4914F6CDD1DL)

    internal fun hashNoise(id: Int, p: Int): Float {
        return unitOf(mix64Once(id * HASH_A + p * HASH_B + seedBase * HASH_C))
    }
}

/** How well band [b] knows the plants of hex [h]'s country: [PLANT_FLOOR] for a stranger to 1. */
internal fun HexGroups.plantKnow(b: Band, h: Hex): Float {
    var s = 0f; var w = 0f
    for (k in Biome.entries) { val share = hw.biome[k.ordinal][h]; if (share > 0f) { s += share * b.plants[k]; w += share } }
    return PLANT_FLOOR + (1f - PLANT_FLOOR) * (if (w > 0f) s / w else 0f)
}

/** A week's gathering in hex [h] teaches band [b] its country's plants. */
internal fun HexGroups.learnPlants(b: Band, h: Int) {
    var w = 0f
    for (k in Biome.entries) w += hw.biome[k.ordinal][h]
    if (w <= 0f) return
    for (k in Biome.entries) { val share = hw.biome[k.ordinal][h] / w; if (share > 0f) b.plants[k] += PLANT_LEARN * share * (1f - b.plants[k]) }
}
