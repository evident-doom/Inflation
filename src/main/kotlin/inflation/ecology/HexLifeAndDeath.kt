package inflation.ecology

import inflation.Part
import inflation.Par
import inflation.Profile
import inflation.Stream
import inflation.stream
import inflation.world.Hex
import inflation.world.Place
import inflation.world.get
import inflation.world.set
import inflation.ecology.HexSmallLife.DeadPool
import inflation.Books
import inflation.KgM2
import inflation.monthsFrom
import inflation.world.Date
import inflation.world.Weather
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.min
import kotlin.random.Random
import inflation.world.Biome
import java.util.concurrent.atomic.DoubleAdder
import kotlin.math.abs

// Foraging's rule, births and deaths, splitting, and what groups remember of the seasons.

/**
 * What a band's forebears knew: for each month, the place within a few weeks' trek where its kind
 * does best then: warm enough and not too hot for its coat, growing (warmth and rain against
 * evaporation), and free of lying snow; nearer ground preferred when much the same. This is the
 * template a band starts from; it then remembers where it actually fared better.
 */
internal fun HexGroups.inheritSeasons(g: HexGroup) {
    val b = g.band ?: return
    val s = g.species
    val here = hw.placeHex[g.place]
    val (hx, hy) = hex.centreKm(here)
    val reachKm = SEASON_RANGE_KM
    val r = (reachKm / hex.hexKm).toInt() + 1
    var near = IntArray(64); var count = 0
    // (The hexes of the square about it, row by row, rather than every place on the map; in the places' order.)
    val r0 = hex.row(here); val c0 = hex.col(here)
    val dr = (reachKm / (hex.hexKm * 0.866)).toInt() + 2; val dc = r + 1
    for (row in max(0, r0 - dr)..min(hex.rows - 1, r0 + dr)) for (col in max(0, c0 - dc)..min(hex.cols - 1, c0 + dc)) {
        val h = hex.at(col, row)
        val (cx, cy) = hex.centreKm(h)
        if (abs(cx - hx) <= reachKm && abs(cy - hy) <= reachKm && hypot(cx - hx, cy - hy) <= reachKm)
            for (p in hw.placesOf(h)) { if (count == near.size) near = near.copyOf(2 * count); near[count++] = p }
    }
    if (count == 0) return
    java.util.Arrays.sort(near, 0, count)
    // (How far each lies, the same every month.)
    val distOf = DoubleArray(count) { i -> val (cx, cy) = hex.centreKm(hw.placeHex[near[i]]); hypot(cx - hx, cy - hy) / reachKm }
    // (And its forage, ground and water, the same every month as the forebears reckon them.)
    val foodOf = FloatArray(count) { foodFor(s, Place(near[it])) }
    val suitsOf = FloatArray(count) { fauna.suits(s, Place(near[it])) }
    val tetherOf = FloatArray(count) { fauna.usualTether(s, Place(near[it])) }
    var top = 0f
    for (m in 0 until 12) {
        var best = -1; var bestScore = 0f
        for (i in 0 until count) {
            val p = near[i]
            val t = hw.placeTemp(Place(p), m)
            val h = hw.placeHex[p]
            val grow = ((t - 2f) / 13f).coerceIn(0f, 1f) * (hw.precip[m][h] / hw.pet[m][h].coerceAtLeast(1f)).coerceIn(0.1f, 1f)
            val discomfort = s.discomfortC(t, m) / 10f
            val snow = if (t < -1f && s.diet.grass > 0.3f) 0.2f else 1f
            val dist = distOf[i]
            // ... and how much forage grows there at all: a desert flush is little food; and whether it can drink there in a usual year.
            val food = foodOf[i]
            val score = (0.2f + grow) * food / (food + 0.3f) * suitsOf[i] * tetherOf[i] * snow / (1f + discomfort * discomfort) /
                (1f + SEASON_DISTANCE * dist.toFloat())
            if (score > bestScore) { bestScore = score; best = p }
        }
        b.seasonPlace[m] = best; b.seasonQ[m] = bestScore
        top = max(top, bestScore)
    }
    // What the forebears knew is trusted until the band learns better (see [remember]).
    if (top > 0f) for (m in 0 until 12) b.seasonQ[m] = 1f
}

@Part("groups: demography")
internal fun HexGroups.demography(weather: Weather, date: Date) {
    // Bands keep or move camp, gather materials and make their tools.
    bands.followersByHex = groups.filter { it.alive && it.species.followsPeople && !it.species.human }.groupBy { hw.placeHex[it.place] }
    bands.groupById = groups.associateBy { it.id }
    bands.bandHex = groups.filter { it.alive && it.band != null }.associate { it.id to hw.placeHex[it.place] }
    tendStructures(weather)
    foulGround()
    bands.houseOf = groups.filter { g -> g.alive && g.band.let { it != null && !it.houseHex.none && it.house >= Thresholds.HOUSE_STANDING } }
        .associate { it.id to it.band!!.houseHex }
    run {
        // The bands' own weeks go one after another (they share the ground, the structures and the week's tallies), then their
        // customs' year, which touches only the band, on all cores, each with a stream of its own; then the rites, which bring
        // bands together, one after another.
        val bandGroups = groups.filter { it.alive && it.band != null }
        for (g in bandGroups) if (g.alive) bandWeek(g, weather, date)
        bands.tallies.flush(if (stats.census) stats.hunting else null)
        Par.forEach("people: customs year", bandGroups.size) { k ->
            val g = bandGroups[k]
            if (g.alive) cultureWeek(g, stream(seedBase, Stream.CUSTOMS, g.id.toLong(), date.absoluteDay))
        }
        for (g in bandGroups) if (g.alive) riteWeek(g, weather)
        groups += bands.strays; bands.strays.clear()
        // Holy places fade while no one comes.
        if (tuning.rounds) { val keep = exp(-years / SANCTITY_YEARS); for (p in land.sanctity.indices) land.sanctity[p] *= keep }
        fightRaids(weather)
    }
    // Hunters remember where they hunted, and a pack long hungry on its ground gives it up.
    for (g in groups) {
        if (!g.alive) continue
        val h = hw.placeHex[g.place]
        if (g.recent[0] != h) { for (i in g.recent.size - 1 downTo 1) g.recent[i] = g.recent[i - 1]; g.recent[0] = h }
        g.lean = if (g.fed < Thresholds.HUNGRY) g.lean + 1 else 0
        // A pack long hungry keeps its ground but ranges wider over it (see [HexGroups.plan]).
    }
    val kinNow = HashMap<Long, Int>()
    for (g in groups) if (g.alive) kinNow.merge(g.species.index.toLong() * hex.size + hw.placeHex[g.place].i, g.size, Int::plus)
    // Where each kind's groups are, for outbreaks to pass between them.
    for (a in fauna.groupHeads) a.fill(0)
    for (g in groups) if (g.alive) fauna.groupHeads[g.species.index][hw.placeHex[g.place]] += g.size
    // Each group on its own core, with its own random stream; what it leaves for others (carcasses,
    // the counts, a split-off pack or band) is gathered and handed on after, in the groups' order.
    val list = groups.toList()
    val outcome = arrayOfNulls<Outcome>(list.size)
    Par.forEach("groups: life and death", list.size) { k ->
        val g = list[k]
        if (g.alive) outcome[k] = Profile.kind("groups: life and death", if (g.band != null) "bands" else g.species.name) {
            liveWeek(g, weather, date, kinNow, groupRandom(g.id, date.absoluteDay)) }
    }
    // Outbreaks that broke out this week rage from next week: started after every group has lived its
    // week, so no group's plague depends on which core another group's ran on.
    for (key in outbreaksStarting.sorted()) fauna.startOutbreak(Catalogue.all[(key / hex.size).toInt()], Hex((key % hex.size).toInt()))
    outbreaksStarting.clear()
    val born = ArrayList<HexGroup>()
    for ((k, o) in outcome.withIndex()) {
        val g = list[k]; val s = g.species
        if (o == null) {
            // Young whose last grown animal was killed this week (by hunters or rivals) die too.
            if (!g.alive && g.young > 0) {
                stats.deaths.merge(Tally.of(s, DeathCause.ORPHANED), g.young, Int::plus)
                small.deposit(DeadPool.CARRION, g.place, KgM2(HexFauna.YOUNG_WEIGHT * g.young * s.massKg / (land.areaKm2[g.place] * 1e6f)))
                g.young = 0
            }
            continue
        }
        bookOutcome(g, o, weather)
        mournDead(g, o, date)
        o.split?.let { c ->
            val n = splitOff(g, c.adults, c.young) // a young pack has no ground yet
            born += n
            if (s.lifeway == Lifeway.PACK && g.band == null) stats.meeting(Met.Of.PACK_SPLIT_OFF, s.name)
            if (g.band != null) stats.meeting(Met.BANDS_SPLIT)
            leaveBelief(g, n)
        }
        if (o.leavers > 0) {
            repeat(o.leavers) { born += splitOff(g, 1, 0) } // each goes alone, to find a mate and ground
            stats.meeting(Met.YOUNG_HUNTERS_LEFT_THEIR_PACK, o.leavers)
            stats.meeting(Met.Of.PACK_LEFT_ALONE, s.name, o.leavers)
        }
    }
    groups += born
    tallyVillages()
    mergeAndPair(date)
}

/**
 * When a band [g] splits ([HexGroups.maxSize]: a strained belief is one of the quarrels that make it split smaller), the
 * part that leaves, [n], goes without the belief as likely as the strain is near what the belief bears ([Band.strainBorne]),
 * and the strain leaves with it (rounds on). The belief alone never splits a band; nothing here weighs whether it pays.
 */
private fun HexGroups.leaveBelief(g: HexGroup, n: HexGroup) {
    val b = g.band ?: return; val nb = n.band ?: return
    if (!tuning.rounds || b.strain <= 0f || rnd.nextFloat() >= min(1f, b.strain / b.strainBorne())) return
    nb.dropBelief(); b.strain = 0f
    stats.meeting(Met.BANDS_SPLIT_OVER_THEIR_BELIEF)
}

/**
 * The group that splits off [g] with [adults] and [young] of its number: it takes its memory, condition, taste
 * and temper, a share of its larder by heads, and, for a band, a copy of what it knew and its share of its
 * tools, goods, elders, practised people and ages; and the places it remembers for each month.
 */
@Part("groups: split off")
private fun HexGroups.splitOff(g: HexGroup, adults: Int, young: Int): HexGroup {
    val s = g.species
    val n = HexGroup(nextId++, s, g.place, adults, young)
    g.memory.copyInto(n.memory)
    n.fed = g.fed
    n.fat = g.fat
    n.spent = g.spent
    n.tag = g.tag
    n.parent = g.id
    n.dry = g.dry
    n.herdsMet = g.herdsMet?.copyOf(); n.herdsMetOn = g.herdsMetOn; n.deathOdds = g.deathOdds
    n.birthMonth = g.birthMonth; n.bred = g.bred
    n.temper = Temperament.inherit(g.temper, n.id, seedBase)
    // A band that splits shares out its larder by heads.
    val share = (adults + young).toFloat() / (g.size + adults + young).coerceAtLeast(1)
    moveFood(g, n, g.larder * share)
    // Both halves keep what the band knew and share out its tools and goods.
    n.bold = g.bold
    g.band?.let { b -> n.band = Band(fauna.kinds.size, fauna.totemKinds).also { nb -> b.splitInto(nb, adults, g.adults, share, rnd) { riteInherit(it) }; nb.livelihoodHold = livelihoodHoldInherit(b.livelihoodHold, n.id, seedBase); nb.sisu = Sisu.inherit(b.sisu, n.id, seedBase)
        // The halves were one band: each holds the other as close as can be, until living apart fades it.
        b.ties[n.id] = SPLIT_TIE; nb.ties[g.id] = SPLIT_TIE } }
    return n
}

/**
 * [n] of [g]'s grown members died, of any cause but old age (hunger, illness, a fight, a horn, a
 * crossing): in a band the elders and each stage of the childbearing years lose their share of them
 * (rounded at random, so single deaths fall on the old as often as their share says), so the age
 * structure stays whole and births are not reckoned on the dead. Never more than there are.
 */
internal fun adultsDied(g: HexGroup, n: Int, r: Random, elderRisk: Float = 1f) {
    val dead = min(n, g.adults)
    if (dead <= 0) return
    g.band?.let { b ->
        // (The elders' share of the dead is the heavier by [elderRisk] when they are likelier to die: of hunger, see [ELDERS_YIELD].)
        if (elderRisk == 1f || b.elders <= 0 || b.elders >= g.adults) {
            b.elders -= min(b.elders, (dead * b.elders + r.nextInt(g.adults)) / g.adults)
            for (i in b.ageStage.indices) b.ageStage[i] -= min(b.ageStage[i], (dead * b.ageStage[i] + r.nextInt(g.adults)) / g.adults)
        } else {
            val others = g.adults - b.elders
            val elderDead = min(b.elders, min(dead, (dead * b.elders * elderRisk / (b.elders * elderRisk + others) + r.nextFloat()).toInt()))
            // (The rest of the dead fall on the childbearing stages, each by its share of them.)
            val rest = dead - elderDead
            b.elders -= elderDead
            for (i in b.ageStage.indices) b.ageStage[i] -= min(b.ageStage[i], (rest * b.ageStage[i] + r.nextInt(others)) / others)
        }
    }
    // The lamed die more than the rest: their share of the dead is [LAME_RISK] heavier than their share of the living.
    g.band?.let { b ->
        val left = g.adults - dead
        b.lame = if (left <= 0) 0f else max(0f, (b.lame * g.adults - dead * b.lame * (1f + LAME_RISK) / (1f + LAME_RISK * b.lame)) / left)
    }
    g.adults -= dead
    g.band?.let { b ->
        // (Rounding can leave more counted old than are left alive; the rest are of the first stage.)
        b.elders = min(b.elders, g.adults)
        for (i in b.ageStage.indices) b.ageStage[i] = min(b.ageStage[i], g.adults - b.elders - b.ageStage.take(i).sum())
    }
}

/**
 * What a group's week left for others: carcasses (kg), births, deaths by cause, orphans, a split-off part (adults, young),
 * and the young grown up this week that leave a pack on their own ([leavers]).
 */
internal class Outcome(val carrionKg: Float, val births: Int, val deaths: Int, val causes: List<Died>, val orphans: Int,
    val split: Split?, val youngDied: Int = 0, val leavers: Int = 0)

/** [n] of a group died of [cause] this week, of its young if [young], else of its grown. */
internal data class Died(val cause: DeathReason, val n: Int, val young: Boolean = false)

/** Whether this is a death to a beast of prey that stalked the band. */
private fun DeathReason.isTakenBy() = this is ByBeast && how == ByBeast.How.TAKEN_BY

/** The part that splits off a group: [adults] and [young]. */
internal class Split(val adults: Int, val young: Int)

/**
 * Books what group [g]'s week brought ([o]) in the week's tallies and records: its carrion, births and deaths by cause,
 * and for a band the deaths and births by country, by how well clad it was and in the cold weeks by what each cover gave.
 */
@Part("groups: book an outcome")
private fun HexGroups.bookOutcome(g: HexGroup, o: Outcome, weather: Weather) {
    val s = g.species
    groupWatch?.outcome(g, o)
    // (A band that buries its dead puts them in the ground, out of the scavengers' reach ([mournDead]); the others' dead
    // lie where they died, carrion that draws the beasts that eat it.)
    val buried = s.human && g.band?.holds(Tradition.BURIAL) == true
    if (o.carrionKg > 0f && !buried) small.deposit(DeadPool.CARRION, g.place, KgM2(o.carrionKg / (land.areaKm2[g.place] * 1e6f)))
    if (o.births > 0) stats.births.merge(s, o.births, Int::plus)
    note(g) { "born %d; died: %s".format(o.births, o.causes.filter { it.n > 0 }.joinToString(", ") { "${it.n} ${it.cause.label}" }.ifEmpty { "none" }) }
    for ((cause, k) in o.causes) if (k > 0) stats.deaths.merge(Tally.of(s, cause), k, Int::plus)
    fauna.record?.let { rec -> for ((cause, k) in o.causes) if (k > 0) rec.group(s, g.place, cause, k) }
    // People's deaths by age apart, for the history: the young (children) and the grown (the elders too).
    if (s.human) for (d in o.causes) if (d.n > 0) (if (d.young) stats.youngDeaths else stats.adultDeaths).merge(Tally.of(s, d.cause), d.n, Int::plus)
    if (s.human) g.band?.let { bookHouseWorth(g, it, weather) }
    // (The census's splits of bands are booked only where a census reads them: [WeekStats.bandSplits].)
    if (s.human && stats.bandSplits) {
        val c = Biome.entries[biomeOfPlace[g.place]]
        val country = BandSplit.country(c)
        stats.bookDeaths(country, o.causes)
        if (o.births > 0) stats.book(country, Tally.Bands.BORN, o.births)
        // And by how well clad the band was: what clothes are worth in lives.
        g.band?.let { b ->
            val clad = if (b.has(Tool.CLOTHES) >= 0.5f) Tally.Clad.CLAD else Tally.Clad.POORLY_CLAD
            val cladSplit = BandSplit.clad(clad)
            stats.book(cladSplit, Tally.Bands.PEOPLE_WEEKS, g.size)
            stats.bookDeaths(cladSplit, o.causes)
            // And by how it lives, settled or on the move: what each way of life costs and gives.
            val way = bandLiving(b)
            val living = BandSplit.living(way)
            stats.bookWeek(living, g.size)
            if (o.births > 0) stats.book(living, Tally.Bands.BORN, o.births)
            stats.bookDeaths(living, o.causes)
            // And by how it chooses ([Mind]): the two kinds of band side by side.
            val mind = BandSplit.mind(b.mind)
            stats.bookWeek(mind, g.size)
            if (o.births > 0) stats.book(mind, Tally.Bands.BORN, o.births)
            if (g.path.size > 1) stats.book(mind, Tally.Bands.MOVES, 1)
            stats.bookDeaths(mind, o.causes)
            // (And those within a month of a camp move: what [Tally.starvedMoved] is set against.)
            if (b.campWeeks * days < Date.DAYS_PER_MONTH) stats.tally(Tally.movedWeeks(b.mind), g.size)
            // (And against what it foresaw in the year before: [bookCalibration].)
            for ((cause, k) in o.causes) if (k > 0) bookCalibration(b, cause, k)
            // (And against what it foresaw of the week's deaths as it chose its places, the weeks before: [bookWarning].)
            bookWarning(g, b, o.causes)
            // (And whether it leaves camps after a death, by how it lives and holds its dead: [bookUnlucky].)
            bookUnlucky(g, b, way, o.deaths > 0)
            // (And its hunger deaths by what its larder held then, and whether it moved camp in the month before.)
            val starved = o.causes.sumOf { if (it.cause == DeathCause.HUNGER) it.n else 0 }
            // (And by how often it has moved camp lately, in the season: [moveCensus].)
            if (b.leanKey >= 0) {
                val n = inflation.world.Season.entries.size
                val ml = Tally.MovedLately.entries[b.leanKey / n]; val se = inflation.world.Season.entries[b.leanKey % n]
                if (starved > 0) stats.tally(Tally.movedLately(ml, se, Tally.Lately.STARVED), starved)
                val all = o.causes.sumOf { it.n }
                if (all > 0) stats.tally(Tally.movedLately(ml, se, Tally.Lately.DIED), all)
            }
            // (And in the weeks it is followed as a hungry band that moved or stayed: [moveCensus].)
            if (b.hungryCohort >= 0 && b.hungryWeek > 0) {
                val hc = Tally.HungryCohort.entries[b.hungryCohort]
                if (starved > 0) stats.tally(Tally.hungryCohort(hc, Tally.HungryFollowed.STARVED), starved)
                val all = o.causes.sumOf { it.n }
                if (all > 0) stats.tally(Tally.hungryCohort(hc, Tally.HungryFollowed.DIED), all)
            }
            if (starved > 0) {
                val larderDays = g.larder / (g.dailyNeedKg / g.warmth.coerceAtLeast(1e-3f)).coerceAtLeast(1e-3f)
                stats.tally(Tally.starvedAt(b.mind, Tally.LarderAt.of(larderDays)), starved)
                if (b.campWeeks * days < Date.DAYS_PER_MONTH) {
                    stats.tally(Tally.starvedMoved(b.mind), starved)
                    if (b.fedBeforeMove < HUNGRY_BEFORE) stats.tally(Tally.starvedMovedHungry(b.mind), starved)
                }
            }
            // And by whether it keeps a herd: what herding costs its people, its children apart.
            val herding = bandHerding(b, g.adults)
            val herdSplit = BandSplit.herding(herding)
            stats.bookWeek(herdSplit, g.size)
            stats.book(herdSplit, Tally.Bands.CHILD_WEEKS, g.young)
            stats.book(herdSplit, Tally.Bands.FED, (1000f * min(1f, g.fed) * g.size).roundToInt())
            if (clad == Tally.Clad.CLAD) stats.book(herdSplit, Tally.Bands.CLAD_WEEKS, g.size)
            if (way == Tally.Living.MOBILE) stats.book(herdSplit, Tally.Bands.MOBILE_WEEKS, 1)
            stats.book(herdSplit, Tally.Bands.AIR, (10f * weather.temperature[g.place] * g.size).roundToInt())
            if (o.births > 0) stats.book(herdSplit, Tally.Bands.BORN, o.births)
            for (d in o.causes) if (d.n > 0) stats.tally(Tally.bandsHerdingDied(herding, d.young, d.cause), d.n)
            // And by how well it was fed, with what it held against hunger: who starves, and with what in hand.
            val fedSplit = BandSplit.fed(Tally.Fed.of(g.fed))
            stats.bookWeek(fedSplit, g.size)
            stats.book(fedSplit, Tally.Bands.LARDER, (10f * g.larderDays()).roundToInt())
            stats.book(fedSplit, Tally.Bands.FAT, (10f * g.fat).roundToInt())
            val meatKg = Store.entries.filter { it.meat }.sumOf { g.stores[it].toDouble() }.toFloat()
            stats.book(fedSplit, Tally.Bands.LARDER_MEAT, (10f * meatKg / max(1e-3f, g.dailyNeedKg)).roundToInt())
            stats.book(fedSplit, Tally.Bands.LEAN_LOST, (100f * b.leanLost).roundToInt())
            stats.book(fedSplit, Tally.Bands.SALT_HELD, (1000f * b.goods[Material.SALT] / max(1, g.size)).roundToInt())
            stats.book(fedSplit, Tally.Bands.SODIUM_SHORT, (100f * b.sodiumShort).roundToInt())
            val saltHere = if (hw.coast[hw.placeHex[g.place]]) 1f else min(1f, 5f * land.salt[g.place])
            stats.book(fedSplit, Tally.Bands.SALT_HERE, (100f * saltHere).roundToInt())
            val salt = bands.saltKnown(b, hw.placeHex[g.place])
            if (!salt.none) {
                stats.book(fedSplit, Tally.Bands.SALT_KNOWN, 1)
                stats.book(fedSplit, Tally.Bands.SALT_KM, hexKm(hw.placeHex[g.place], salt).roundToInt())
            }
            stats.bookDeaths(fedSplit, o.causes)
            // And by its spare days and its herd: whether work it took on left its people short of what keeps them alive.
            bookSqueeze(g, b, o.causes, weather)
        }
        // And, in the weeks the cold ran its people into heat debt through all the cover the band had, what it was and had.
        val b = g.band
        if (b != null) {
            val air = airAt(g.place, weather)
            val w = b.warmth(air, b.fire, b.homeShelter(), days)
            if (w.debt > 0f) {
                val herding = bandHerding(b, g.adults)
                fun add(k: Tally.Cold, v: Float) {
                    stats.tally(Tally.bandsIn(c, k), (10f * v).roundToInt())
                    stats.tally(Tally.bandsHerding(herding, k), (10f * v).roundToInt())
                }
                add(Tally.Cold.WEEKS, 1f); add(Tally.Cold.SHORT, w.debt); add(Tally.Cold.AIR, air.nightC)
                add(Tally.Cold.CLOTHES, b.clothesClo()); add(Tally.Cold.FIRE, b.fire); add(Tally.Cold.SHELTER, max(b.shelter, b.homeShelter()))
                add(Tally.Cold.MOVED, if (b.campWeeks == 0) 1f else 0f); add(Tally.Cold.AIM, b.shelterWill)
                add(Tally.Cold.SPARE, b.spareDays / max(1f, g.adults * days.toFloat()))
            }
        }
    }
    if (o.orphans > 0) stats.deaths.merge(Tally.of(s, DeathCause.ORPHANED), o.orphans, Int::plus)
    if (s.human && o.orphans > 0) stats.youngDeaths.merge(Tally.of(s, DeathCause.ORPHANED), o.orphans, Int::plus)
    // Children's deaths apart, for the life tables.
    if (s.human && o.youngDied > 0) stats.tally(Tally.of(s, Tally.OfKind.CHILDREN_DIED), o.youngDied)
}

/**
 * The census of unlucky camps ([Tally.Unluck]), report only: this band-week of [b] (living [way]), whether it had a death,
 * and, while a camp of its is marked unlucky, whether it is still on it, apart for marks at its own houses.
 */
private fun HexGroups.bookUnlucky(g: HexGroup, b: Band, way: Tally.Living, died: Boolean) {
    val dead = Tally.DeadWay.of(b.holds(Tradition.LEAVE_DEAD), b.holds(Tradition.BURIAL))
    val k = { what: Tally.Unluck -> Tally.unlucky(way, dead, what) }
    stats.tally(k(Tally.Unluck.BAND_WEEKS), 1)
    if (died) stats.tally(k(Tally.Unluck.DEATH_WEEKS), 1)
    val u = b.unlucky ?: return
    val on = hw.placeHex[g.place] == u.hex
    stats.tally(k(if (on) Tally.Unluck.ON else Tally.Unluck.AWAY), 1)
    if (b.house > 0f && b.houseHex == u.hex) stats.tally(k(if (on) Tally.Unluck.ON_HOUSES else Tally.Unluck.AWAY_HOUSES), 1)
}

/**
 * What a band's dead do to it ([o] is its week): a camp left where someone died, the wounded of a beast's attack, the place
 * remembered as dangerous or as its dead's, burials and the ornaments that go with them.
 */
@Part("groups: mourn the dead")
private fun HexGroups.mournDead(g: HexGroup, o: Outcome, date: Date) {
    val s = g.species
    // A band that keeps the old way leaves a camp where someone died.
    if (s.human && o.deaths > 0) g.band?.let { b ->
        if (b.holds(Tradition.LEAVE_DEAD) && !b.holds(Tradition.BURIAL)) {
            b.unlucky = Unlucky(hw.placeHex[g.place], UNLUCKY_WEEKS)
            note(g) { "left the camp where its dead lie" }
            if (stats.bandSplits) {
                val k = { what: Tally.Unluck -> Tally.unlucky(bandLiving(b), Tally.DeadWay.LEAVES, what) }
                stats.tally(k(Tally.Unluck.MARKED), 1)
                if (b.house > 0f && b.houseHex == hw.placeHex[g.place]) stats.tally(k(Tally.Unluck.MARKED_HOUSES), 1)
            }
        }
    }
    // A week's toll far above what it is used to (one death a week at the least) is an omen where it fell ([omen]); what it
    // is used to follows its weeks over about a year.
    if (s.human) g.band?.let { b ->
        val size = g.size + o.deaths
        if (o.deaths > 0) omen(b, g.place, date, o.deaths / max(1f, b.deathsUsual * size), Met.BANDS_MARKED_A_DEATH_TOLL)
        b.deathsUsual += min(1f, years) * (o.deaths / max(1, size).toFloat() - b.deathsUsual)
    }
    // A band that buries its dead buries them where it is: its ancestors' place.
    // For each taken by a beast, others got away hurt.
    g.band?.let { b ->
        val taken = o.causes.filter { it.cause.isTakenBy() }.sumOf { it.n }
        if (taken > 0) {
            b.wounded(WOUNDED_PER_DEAD * taken, g.adults)
            b.beastLoss += taken / g.size.coerceAtLeast(1).toFloat()
        }
    }
    // Where a beast took one of them, the band remembers the place as dangerous.
    if (s.human && o.causes.any { it.cause.isTakenBy() && it.n > 0 })
        g.memory.mark(hw.placeHex[g.place], PlaceMemory.seasonOf(date.month), stepOf(date), stepsPerYear, g.memory.danger, 1f)
    if (s.human && o.deaths > 0) g.band?.takeIf { it.holds(Tradition.BURIAL) }?.let { b ->
        // Where it lays them by its way ([BurialWay]): where it camps, or carried to where most of its dead lie, if that is
        // within a day's walk ([BODY_CARRY_KM]); the digging and any carrying are owed of its customs' time.
        val camp = hw.placeHex[g.place]
        val site = if (b.burialWay == BurialWay.AT_ANCESTORS) ancestorsHex(g) else Hex.NONE
        val at = if (!site.none && hexKm(camp, site) <= BODY_CARRY_KM) site else camp
        val p = if (at == camp) g.place else hw.placeNear(g.place, at).takeIf { !it.none } ?: g.place
        val carryKm = if (at == camp) 0.0 else hexKm(camp, at)
        val owed = o.deaths * (BURY_DAYS + BEARERS * (2.0 * carryKm / (WALK_KM_PER_HOUR * Activity.WORK_HOURS)).toFloat())
        b.burialOwed += owed
        stats.meeting(Met.Of.CUSTOMS_DAYS_ASKED, CustomsPart.BURIALS.label, (10f * owed).roundToInt())
        if (at != camp) { stats.meeting(Met.BANDS_CARRIED_THEIR_DEAD); note(g) { "carried its dead to its ancestors at hex %d".format(at.i) } }
        g.memory.mark(at, PlaceMemory.seasonOf(date.month), stepOf(date), stepsPerYear, g.memory.dead, o.deaths.toFloat())
        land.burials[p] += o.deaths.toFloat()
        land.sanctity[p] += o.deaths.toFloat()
        // Ornaments go into the grave with them: the more a band has, the richer its graves.
        val goods = min(b.ornaments, GRAVE_GOODS * b.ornaments * o.deaths)
        b.takeOrnaments(goods); land.graveGoods[p] += goods
    }
}

/** One group's week of feeding on its reserves, births, deaths and growing up; with its own random stream [r]. */
@Part("groups: live week of one")
internal fun HexGroups.liveWeek(g: HexGroup, weather: Weather, date: Date, kinNow: HashMap<Long, Int>, r: kotlin.random.Random): Outcome {
    val s = g.species
    feedOnReserves(g)
    if (g.birthMonth < 0) g.birthMonth = s.birthMonth
    if (date.month == offSeason(s, g.birthMonth) && date.dayOfMonth <= days) g.bred = 0f
    val inSeason = monthsFrom(g.birthMonth, date.month) < s.birthMonths
    var bornNow = 0
    var bornExpected = 0f
    // In a band, the elders are past bearing children; they help their daughters raise theirs.
    val band = g.band
    val mothersExact = mothersIn(g)
    // (Whole mothers, the fraction drawn by chance: cutting it would lose half a mother a week, most of all in small bands.)
    val mothers = mothersExact.toInt() + if (band != null && r.nextFloat() < mothersExact - mothersExact.toInt()) 1 else 0
    val help = if (band != null) min(1f, band.elders * ELDER_MOTHERS / max(1, mothers)) else 0f
    // (Births follow one rule for every kind, groups and residents alike: each mother bears her
    // litter as her food and reserves can build it (people as they are fed), through the birth season; only where just the leading pair of a pack
    // breeds ([Species.packLitter]: wolves) is there one litter a pack. A pack bears only on ground of its own.)
    if (inSeason && g.adults >= 2 && !(s.lifeway == Lifeway.PACK && g.home.none)) {
        val births = if (s.packLitter > 0) {
            if (date.dayOfMonth <= days && date.month == g.birthMonth && !g.home.none) (s.packLitter * breeding(s, s.packLitter.toFloat(), g.fat, g.fed, days) + r.nextFloat()).toInt() else 0
        } else {
            val rate = if (s.human) {
                val stepsInSeason = s.birthMonths * Date.DAYS_PER_MONTH / days.toFloat()
                s.youngPerFemale * g.fed * (band?.let { birthSpacing(it) } ?: 1f) * HUMAN_BIRTHS * tuning.humanBirths * (1f + GRANDMOTHERS * help) / stepsInSeason
            } else {
                // (By the residents' rule: a week's share of the season, no more than the mother has left of it, spaced by how crowded her kind is.)
                val share = seasonShare(s, g.birthMonth, max(0f, 1f - g.bred), date, days)
                g.bred += share
                // (Meat is rich in protein: their young are short of none.)
                val due = youngDue(s, kinKm2(g, kinNow), HexFauna.usualDensity(s), tuning.breedSpacing, 1f, share)
                due * breeding(s, due, g.fat, g.fed, days) * (1f + GRANDMOTHERS * help)
            }
            bornExpected = mothersExact * rate
            // (A big litter over a short season is more than one a mother a week: the whole ones, and the rest by chance.)
            val whole = rate.toInt()
            mothers * whole + binomial(mothers, rate - whole, r)
        }
        g.young += births
        bornNow = births
        // The young are made of what their mothers ate: their weight comes off the mothers' reserves.
        if (births > 0) g.fat = max(0f, g.fat - fromReserves(s, births.toFloat() / max(1, g.adults / 2), g.fed, days))
    }
    val fouled = campFouling(g) * years
    // Wounds: mishaps of the work (the further the gatherers range, the more), and those hurt in
    // fights, hunts and by beasts last week; tended wounds heal faster, fester less and lame fewer.
    val festered = if (band != null) woundsWeek(band) else 0f
    // Beasts of prey about: now and then one takes a gatherer, a child or an old one alone.
    val (stalked, stalker) = if (band != null) stalkedBy(g, band, kinNow) else Stalking.NONE
    val venom = venomOdds(s, g.place, weather)
    // Children of a closed band are inbred; medicine saves some of the sick and wounded.
    val inbred = if (band != null) INBREEDING_DEATHS * band.inbred * years else 0f
    // (Healers save some of the sick and wounded, the more the more they know.)
    val healed = healedShare(band)
    // (And practised midwives and carers lose fewer infants.)
    val cared = band?.lapse(Skill.MIDWIFERY)?.let { 1f + HEALING_REACH * (it - 1f) } ?: 1f
    // Sicknesses of their own: the flux, marsh fever, the cough (see [Illness]); healers save some.
    val ill = if (band != null) illnessWeek(g, band, weather, r).also { for (i in Illness.entries) it[i] *= healed } else null
    val thirst = thirstWeek(g)
    // Where its kind is crowded, an outbreak may break out.
    val sick = sicknessWeek(g, weather, kinNow, r)
    val disease = fauna.outbreakDeaths(s, g.place) + sick
    // Packs without ground of their own die more: fights, injuries and hunger on others' ground.
    val homeless = if (s.lifeway == Lifeway.PACK && g.home.none) DISPERSAL_DEATHS * years else 0f
    // Every hazard of the week, once: its cause and the odds it adds for a grown one and for a young one.
    val hazards = arrayListOf(
        Hazard(DeathCause.OTHER, healed * s.backgroundMortality * (if (s.human) tuning.humanOther else 1f) * years,
            healed * cared * s.youngMortality * (if (s.human) tuning.humanYoung else 1f) * years))
    placeHazards(g, g.place, weather, g.fed, thirst, sick, band?.fire ?: 0f, band?.homeShelter() ?: 0f) { c, a, y -> hazards += Hazard(c, a, y) }
    band?.let { houseWorthWeek(g, it, weather, stalked) }
    val spentBefore = g.spent
    g.spent = Hazards.spentAfter(s, g.spent, g.fed, days)
    // (No beast to blame, none taken: a hazard of none weighs nothing in sharing the deaths, and is left out.)
    hazards.addAll(listOfNotNull(
        Hazard(DeathCause.HOMELESS, homeless, homeless),
        Hazard(DeathCause.CAMP_FOULING, 0f, fouled), Hazard(DeathCause.SNAKES, venom, venom), Hazard(DeathCause.INBREEDING, 0f, inbred),
        stalker?.let {
            val young = band?.let { b -> dialed(DialSite.CHILDREN_TAKEN, b.childWatch,
                YOUNG_STALKED / b.childWatch.coerceAtLeast(0.25f) * stalked, cut = b.childWatch < 0.25f) { YOUNG_STALKED * stalked } } ?: YOUNG_STALKED * stalked
            // (Those who watch the children step between them and the beast: [CARETAKERS_SHIELD].)
            val k = band?.let { b -> caretakersTake(b) } ?: 0f
            Hazard(ByBeast.of(ByBeast.How.TAKEN_BY, it), stalked + k * young * g.young / g.adults.coerceAtLeast(1), (1f - k) * young) },
        Hazard(DeathCause.WOUNDS, festered, 0f)))
    ill?.let { a -> for (it in Illness.entries) hazards += Hazard(it, a[it], a[it] * it.young) }
    // The elders' care spares some of the young.
    val spared = 1f - ELDER_CARE * help
    var adultSum = 0f; var youngSum = 0f
    for (h in hazards) { adultSum += h.adult; youngSum += h.young }
    // (The lamed die more: the grown's odds are heavier by their share times [LAME_RISK].)
    val adultOdds = (adultSum * (1f + LAME_RISK * (band?.lame ?: 0f))).coerceIn(0f, Hazards.MAX_GROWN_ODDS)
    g.deathOdds = adultOdds
    val youngOdds = (youngSum * spared).coerceIn(0f, Hazards.MAX_YOUNG_ODDS)
    if (s.human && g.young > 0) for (h in hazards) if (h.young > 0f)
        stats.childDeaths.computeIfAbsent(h.cause) { DoubleAdder() }.add((g.young * spared * h.young).toDouble())
    val da = binomial(g.adults, adultOdds, r); val dy = binomial(g.young, youngOdds, r)
    // (For training: the years a grown one who dies has ahead, on average over the elders and the rest; see [FutureYears].)
    // (Elders die the more of hunger, as they yield their food: [elderRisk].)
    val elderRisk = if (band != null) elderRisk(g, g.fed, spentBefore, adultSum) else 1f
    val grownYears = if (s.human && g.adults > 0) (band?.elders ?: 0).coerceAtMost(g.adults).let { e ->
        val others = g.adults - e; (others + FutureYears.ELDER * elderRisk * e) / (others + elderRisk * e) } else 1f
    // (For training: what the week's odds made likely, before the elders' deaths are drawn.)
    val oldAgeExpected = band?.let { b -> (b.elders - (da * b.elders.toFloat() / g.adults.coerceAtLeast(1))).coerceAtLeast(0f) * min(1f, ELDER_DEATHS * years) } ?: 0f
    val grownExpected = g.adults * adultOdds; val youngExpected = g.young * youngOdds
    val diedExpected = grownExpected + youngExpected + oldAgeExpected
    if (s.human) {
        // (And by cause class: each hazard's share of the odds, which are held under a ceiling, of the heads they were drawn from.)
        val adultScale = if (adultSum > 0f) adultOdds / adultSum else 0f
        val youngScale = if (youngSum * spared > 0f) youngOdds / (youngSum * spared) else 0f
        for (h in hazards) h.cause.deathClass()?.let { c -> g.addLost(c.ordinal, g.adults * h.adult * adultScale + g.young * spared * h.young * youngScale) }
        g.addLost(LOST_ALL, diedExpected)
    }
    // Elders die as the others do (their share of the adults who died), and of old age besides.
    adultsDied(g, da, r, elderRisk)
    var oldAge = 0
    if (band != null) {
        oldAge = binomial(band.elders, ELDER_DEATHS * years, r)
        band.elders -= oldAge; g.adults -= oldAge
    }
    if (s.human) {
        g.luck += (bornNow - bornExpected) - (da + dy + oldAge - diedExpected)
        // (The same luck in years ahead: the young count [FutureYears.YOUNG], the elders [FutureYears.ELDER].)
        g.yearsLuck += FutureYears.YOUNG * (bornNow - bornExpected) - (grownYears * (da - grownExpected) +
            FutureYears.YOUNG * (dy - youngExpected) + FutureYears.ELDER * (oldAge - oldAgeExpected))
    }
    // The sick and the newborn teach their carers.
    band?.let { b -> if (disease > 0f || da > 0) b.practise(Skill.HERBS, 0.2f * PRACTICE); if (bornNow > 0) b.practise(Skill.MIDWIFERY) }
    g.young -= dy
    // The dead lie where they fell, for the scavengers and the soil.
    var carrion = (da + oldAge + HexFauna.YOUNG_WEIGHT * dy) * s.massKg
    // The week's deaths are shared among the causes by how much each weighed, the grown and the young apart ("other": age, accidents, ordinary illness).
    val causes = shareDeaths(hazards, da, dy, r)
    if (oldAge > 0) causes += Died(DeathCause.OLD_AGE, oldAge)
    // (Growing up and growing old take years ahead from a band by no one's choice: for training, they count as its luck.)
    val yearsBefore = if (s.human) FutureYears.of(g) else 0f
    val grown = growUp(g, r)
    if (s.human) g.yearsLuck -= yearsBefore - FutureYears.of(g)
    land.books?.adder(Books.Stuff.MATTER, "groups born and grew up")?.add(((HexFauna.YOUNG_WEIGHT * bornNow + (1f - HexFauna.YOUNG_WEIGHT) * grown) * s.massKg).toDouble())
    if (g.adults == 0) {
        // Young left without a grown animal of their kind die.
        val orphans = g.young
        carrion += HexFauna.YOUNG_WEIGHT * orphans * s.massKg
        g.young = 0
        return Outcome(carrion, bornNow, da + oldAge + dy, causes, orphans, null, dy + orphans)
    }
    // Young hunters grown up leave a pack already as big as its kind's usual one, each alone, to find a mate
    // and ground of their own: how packs are founded (a pack that only grows until it splits seldom gets there).
    val leavers = if (s.lifeway == Lifeway.PACK && band == null) min(grown, max(0, g.adults - s.groupSize)) else 0
    g.adults -= leavers
    var split: Split? = null
    // Bands that feast together hold together longer.
    if (g.size > maxSize(g)) {
        split = Split(g.adults / 2, g.young / 2)
        g.adults -= split.adults; g.young -= split.young
    }
    return Outcome(carrion, bornNow, da + oldAge + dy, causes, 0, split, dy, leavers)
}

/** Reserves carry [g] through lean weeks; a group that ate (nearly) its fill lays them on, most in late summer and autumn. */
private fun GroupWorld.feedOnReserves(g: HexGroup) {
    val s = g.species
    val drew = g.fed < WELL_FED && g.fat > 0f
    if (g.fed < WELL_FED) { val draw = min(g.fat, (1f - g.fed) * days); g.fat -= draw; g.fed = min(1f, g.fed + draw / days); g.band?.fatFed = draw / days }
    else { val gain = FAT_EFFICIENCY * g.surplus; val back = min(g.spent, gain); g.spent -= back
        // (What it ate beyond its need wins back the body it burnt before it lays on fat.)
        g.fat = min(s.maxFatDays, g.fat + gain - back) }
    g.surplus = 0f
    // (A band counts the steps on end it lives on its reserves; the spell ends with a step it does not: see [riteWeek].)
    val b = g.band ?: return
    if (drew) b.wantSteps++ else if (b.wantSteps > 0) { b.wantBroke = b.wantSteps; b.wantSteps = 0 }
}

/** Where the week's hazards go as they are reckoned: each cause, and the odds it adds for a grown one and for a young one. */
internal fun interface HazardSink { fun add(cause: DeathCause, adult: Float, young: Float) }

/** [g]'s young grow up, and in a band the adults age through three spans of adult life, then become elders (drawn from [r]); returns how many young grew up. */
private fun GroupWorld.growUp(g: HexGroup, r: Random): Int {
    val s = g.species
    val band = g.band
    val up = binomial(g.young, years / s.yearsToMaturity, r)
    g.young -= up; g.adults += up
    // Those who grow up are not lamed: the lamed share thins as they join.
    if (up > 0) band?.let { b -> b.lame *= (g.adults - up).toFloat() / g.adults }
    // Adults age through three spans of adult life, then become elders.
    band?.let { b ->
        val rate = 3f * years / ADULT_YEARS
        val toElder = binomial(b.ageStage[1], rate, r); val toThird = binomial(b.ageStage[0], rate, r)
        val first = max(0, g.adults - b.elders - b.ageStage[0] - b.ageStage[1])
        val toSecond = binomial(first, rate, r)
        b.elders += toElder; b.ageStage[1] += toThird - toElder; b.ageStage[0] += toSecond - toThird
    }
    return up
}

/** Small bands join kin, and lone hunters find mates (see [demography]). */
@Part("groups: merging and pairing")
internal fun HexGroups.mergeAndPair(date: Date) {
    // A band grown too small to hunt, gather and defend itself joins the nearest kin band that has room.
    val bandsByHex = HashMap<Hex, ArrayList<HexGroup>>()
    for (g in groups) if (g.alive && g.band != null) bandsByHex.getOrPut(hw.placeHex[g.place]) { ArrayList() } += g
    for (g in groups) {
        val b = g.band ?: continue
        if (!g.alive || g.adults >= SMALL_BAND) continue
        var best: HexGroup? = null; var bestKin = -1f
        val h0 = hw.placeHex[g.place]
        var ring = listOf(h0); val seen = hashSetOf(h0)
        for (r in 0..VISIT_RINGS) {
            for (h in ring) for (o in bandsByHex[h].orEmpty()) {
                // (A band joins only one of its own mind: the two kinds of choosing stay apart.)
                val ob = o.band ?: continue
                if (o === g || !o.alive || o.size + g.size > maxSize(o) || ob.mind != b.mind) continue
                val k = kinship(b.lineage, ob.lineage) / (1f + r)
                if (k > bestKin) { bestKin = k; best = o }
            }
            if (best != null || r == VISIT_RINGS) break
            ring = hex.nextRing(ring, seen)
        }
        val o = best ?: continue
        joinBand(o, g, stepOf(date))
        stats.meeting(Met.SMALL_BANDS_JOINED_KIN)
    }
    becomeOne(date)
    // A hunter left on its own looks for a mate: lone adults of a kind within a few hexes pair up, and
    // the pair holds the ground of the one that had any.
    val lone = HashMap<Int, ArrayList<HexGroup>>()
    for (g in groups) if (g.alive && g.species.lifeway == Lifeway.PACK && g.adults == 1) lone.getOrPut(g.species.index) { ArrayList() } += g
    for ((_, list) in lone) {
        val used = HashSet<Int>()
        for (a in list) {
            if (a.id in used) continue
            // (As far as it chooses to look; and only among its own lineage while variants are weighed against each other.)
            val b = list.firstOrNull { it.id != a.id && it.id !in used && it.tag == a.tag &&
                hex.distanceKm(hw.placeHex[it.place], hw.placeHex[a.place]) <= MATE_SEARCH_KM * a.will[AnimalWay.MATE] } ?: continue
            used += a.id; used += b.id
            a.adults += b.adults; a.young += b.young
            a.fed = (a.fed + b.fed) / 2f; a.fat = (a.fat + b.fat) / 2f; a.spent = (a.spent + b.spent) / 2f; a.bred = (a.bred + b.bred) / 2f
            if (a.home.none) claimGround(a, b.home)
            stats.meeting(Met.Of.PACK_PAIRED, a.species.name)
            b.mergedSize = b.size; b.mergedInto = a.id
            b.adults = 0; b.young = 0
        }
    }
}

/**
 * Bands at home in one hex that have grown alike become one band, as people who live together and do the same things come to
 * see themselves as one group (cooperation, shared fate and living side by side: the common ingroup identity of Gaertner and
 * Dovidio). How alike they are is read from [Regard] both ways (words understood, kin, tie, customs apart): halves of a split
 * still at home together are one again at once; bands that met as strangers grow alike as their ties, words and customs come
 * together. Nothing forces it, and it needs room under the size a band at home holds ([maxSize]). The smaller joins the larger.
 * Bands of the two kinds of choosing ([Band.mind]) become one alike: the joined band chooses as the larger did.
 */
private fun HexGroups.becomeOne(date: Date) {
    val atHome = HashMap<Hex, ArrayList<HexGroup>>()
    for (g in groups) { val b = g.band ?: continue; if (g.alive && b.atHome) atHome.getOrPut(b.houseHex) { ArrayList() } += g }
    for ((_, bands) in atHome) {
        if (bands.size < 2) continue
        for (i in bands.indices) for (j in i + 1 until bands.size) {
            val a = bands[i]; val c = bands[j]
            val ab = a.band ?: continue; val cb = c.band ?: continue
            if (!a.alive || !c.alive) continue
            if (a.size + c.size > min(maxSize(a), maxSize(c))) continue
            if (alike(Regard(ab, c.id, cb, map = hex)) < ONE_GROUP || alike(Regard(cb, a.id, ab, map = hex)) < ONE_GROUP) continue
            val (into, from) = if (a.size >= c.size) a to c else c to a
            joinBand(into, from, stepOf(date))
            stats.meeting(Met.BANDS_BECAME_ONE)
            if (ab.mind != cb.mind) stats.meeting(Met.BANDS_OF_TWO_MINDS_BECAME_ONE)
        }
    }
}

/** How alike a band finds another, 0 (strangers of wholly other customs, not understood, no kin) to 1. */
internal fun alike(r: Regard) = 1f - r.wariness / Regard.MOST_WARY

/** Band [from] joins band [into]: its people, food, goods and knowledge come with it. */
private fun GroupWorld.joinBand(into: HexGroup, from: HexGroup, week: Int) {
    val intoBand = into.band ?: return; val fromBand = from.band ?: return
    val w = from.adults.toFloat() / (into.adults + from.adults).coerceAtLeast(1)
    intoBand.absorb(fromBand, from.adults, w, into.stores[Store.DRIED], from.stores[Store.DRIED],
        into.stores[Store.SEED], from.stores[Store.SEED], into.id, from.id)
    into.fed += w * (from.fed - into.fed); into.fat += w * (from.fat - into.fat); into.spent += w * (from.spent - into.spent); into.bred += w * (from.bred - into.bred)
    for (i in Store.entries) addFood(into.stores, into.storeKgPer, i, from.stores[i], from.stores[i] * from.storeKgPer[i])
    into.memory.hear(from.memory, week, stepsPerYear, 1f)
    // (Its cache is left: no one goes back for it.)
    dropCache(fromBand)
    from.mergedSize = from.size; from.mergedInto = into.id
    into.adults += from.adults; into.young += from.young
    from.adults = 0; from.young = 0; from.larder = 0f
}

/**
 * Every group remembers where it was and how it fared; bands also remember the place where they did best in each month:
 * how well they fed there, by the share of them that would live through a month of the week's odds (a place they fed
 * well at but went dry, froze or sickened at is no good place).
 */
internal fun HexGroups.remember(date: Date) {
    val m = date.month
    val weeksAMonth = stepsPerYear / 12f
    // Every group remembers where it was this week and how it fared there.
    val week = stepOf(date)
    // (A band also what it found there of the rarer materials: how richly each crops out, as its gathering goes by it.)
    val finds = FloatArray(PlaceMemory.RARE.size)
    for (g in groups) if (g.alive) {
        val h = hw.placeHex[g.place.i]
        if (g.band != null) for ((k, mat) in PlaceMemory.RARE.withIndex())
            finds[k] = mat.deposit?.let { min(1f, 5f * it(land)[g.place.i]) } ?: (if (hw.coast[h]) 1f else 0f)
        g.memory.record(h, PlaceMemory.seasonOf(m), g.fed, land.drink.water[g.place.i], fauna.afraid(g.place), week, stepsPerYear,
            finds = if (g.band != null) finds else null)
    }
    for (g in groups) {
        val b = g.band ?: continue
        // At the remembered place it learns how good it really is; elsewhere the memory fades slowly,
        // and a clearly better place takes its place.
        val fared = g.fed * (1f - g.deathOdds).pow(weeksAMonth)
        if (b.seasonPlace[m] < 0) { b.seasonPlace[m] = g.place.i; b.seasonQ[m] = fared }
        else if (b.seasonPlace[m] == g.place.i) b.seasonQ[m] += 0.3f * (fared - b.seasonQ[m])
        else {
            b.seasonQ[m] *= MEMORY_FADE
            if (fared > b.seasonQ[m] + 0.1f) { b.seasonPlace[m] = g.place.i; b.seasonQ[m] = fared }
        }
    }
}
