package inflation.ecology

import inflation.ecology.HexSmallLife.DeadPool
import inflation.Books

import inflation.Kg
import inflation.KgM2
import inflation.Config
import inflation.HASH_A
import inflation.HASH_B
import inflation.HASH_C
import inflation.mix64
import inflation.unitOf

import inflation.world.HexDrinkingWater
import inflation.world.HexLand
import inflation.world.HexWorld
import inflation.world.Weather
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.pow
import kotlin.math.min
import kotlin.random.Random
import inflation.world.Biome
import java.util.Arrays
import java.util.BitSet
import java.util.stream.Collectors
import java.util.stream.IntStream
import kotlin.math.ceil
import kotlin.math.sqrt

// Hunting along the way, and the meetings of a week settled one by one in the order they happen.

/**
 * Group [k] is in hex [hex] (its path's [index], or one round the hex it grazes) from day [from] to day [to],
 * for [share] of that time (see [rangeShares]).
 */
private class Stay(val k: Int, val index: Int, val hex: Int, val from: Float, val to: Float, val share: Float = 1f)
/**
 * Groups [a] and [b] are in hex [hex] together from [day], for [span] days, at their paths' [ai] and [bi], each
 * there for its share of that time (the product is [share]).
 */
private class Meeting(val day: Float, val span: Float, val a: Int, val ai: Int, val b: Int, val bi: Int, val hex: Int, val share: Float) { var tie = 0L }

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

/**
 * How catchable prey [ps] is for hunter [s] in place [p], 0..1: its size against what the hunter takes
 * ([Species.preySize], as resident hunters reckon it too), defence, refuge in broken ground, small prey hiding in
 * thick cover, and for stalkers, dark nights.
 */
internal fun HexGroups.catchable(s: Species, ps: Species, p: Int): Float =
    (s.preySize(ps.massKg) * (1f - ps.defence) * reachRaw(s, ps, p)).coerceIn(0f, 1f)

/** How often hunter [s] gets to grips with prey [ps] in place [p], 0..1: the part of [catchable] that is closing in, not overpowering it. */
internal fun HexGroups.reach(s: Species, ps: Species, p: Int) = reachRaw(s, ps, p).coerceIn(1e-3f, 1f)

private fun HexGroups.reachRaw(s: Species, ps: Species, p: Int): Float {
    // Shelter: crags, dens, thickets and trees, as the prey uses them; small prey hide in any cover.
    // (Hunters that follow the scent find much of what hides; shelter counts for half against them.)
    // (But crags are a wild goat's whole defence: no hunter follows it up a cliff.)
    val refuge = 1f - (if (ps.habitat.rugged >= 0.5f) 1f else 0.5f) * SHELTER_EFFECT * Niches.shelter(ps, p, land)
    // Small prey hide in thick cover from hunters that run them down; a stalker uses the same cover to get close.
    val hide = 1f - HIDING * land.plants.cover(p) * (1f - s.stalking) * max(0f, 1f - ps.massKg / HIDING_KG)
    // Stalkers hunt by night: dark weeks around the new moon hide them, bright ones give them away.
    val dark = 1f + MOON_DARK * s.stalking * (1f - 2f * moon)
    // A courser must run its quarry down; a stalker gets close unseen and needs no speed.
    val speed = s.stalking + (1f - s.stalking) * min(1f, s.speedKmh / ps.speedKmh)
    // And it needs room to run: in thick woods and scrub a chase ends at the first tangle.
    val room = 1f - COURSING_COVER * (1f - s.stalking) * land.plants.cover(p)
    return refuge * hide * dark * speed * room
}

/**
 * Hunters (and bands) feed along their way: the resident herds of every place they passed, the herds
 * they met (that passed the same hexes this week), small game, carrion, and for those that eat them
 * plants, and for bands fish (see [forage]).
 */
internal fun HexGroups.hunt(weather: Weather) {
    val ways = this.ways
    val inputs = FloatArray(WAYS_IN); val out = FloatArray(WAYS_OUT)
    // (What a party killed this week before the last of it died lies where it fell: the meat of it, a beast's need being
    // counted in the whole weight of its kills, see [creditShare].)
    for (g in groups) if (!g.alive && g.meatMet > 0f) { small.deposit(DeadPool.CARRION, g.place, Kg(g.meatMet * meatShare(g.band) / creditShare(g.band))); g.meatMet = 0f }
    val hunters = groups.filter { it.alive && it.species.carnivore }.shuffled(rnd)
    // Bands first set their ways for the week.
    if (ways != null) for (g in hunters) if (g.band != null) setWays(g, weather, ways, inputs, out)
    // (A band followed under the player's hand sets its ways every week, so the player's pins hold.)
    if (ways == null) controlledWays()?.let { w -> hunters.firstOrNull { it.id == watched && it.band != null }?.let { setWays(it, weather, w, inputs, out) } }
    // Then region by region on all cores: the map is cut into squares coloured like a chessboard of four
    // colours, so that squares of one colour lie a square apart; the squares of one colour are worked at
    // once. Parties whose week kept within half a square of where they ended it are worked first in squares
    // of [HUNT_FINE_KM] (two such parties in squares of one colour cannot have passed the same ground); the
    // few that ranged further after them, in squares of [HUNT_BLOCK_KM], wider than twice any party's week.
    fun work(parties: List<HexGroup>, squareKm: Float) {
        val blocks = HashMap<Int, ArrayList<HexGroup>>()
        for (g in parties) {
            val (x, y) = hex.centreKm(hw.placeHex[g.place])
            blocks.getOrPut((x / squareKm).toInt() + 1000 * (y / squareKm).toInt()) { ArrayList() } += g
        }
        for (colour in 0 until 4) {
            // (The squares with the most parties first, so the slowest is not left to last on one core.)
            val squares = blocks.entries.filter { (b, _) -> (b % 1000) % 2 + 2 * ((b / 1000) % 2) == colour }
                .sortedByDescending { e -> e.value.sumOf { g -> if (g.band != null) 4 * g.path.size else g.path.size } }
            IntStream.range(0, squares.size).parallel().forEach { i ->
                for (g in squares[i].value) {
                    val s = g.species
                    // The ground it searches this week, shared over the hexes it passed: a bigger party fans out
                    // over more of it, as the square root of its numbers against its kind's usual party.
                    // And it searches less of it when the rain, haze or cover hide game (see [sight]).
                    val sweep = sweptKm2(s) * sqrt(max(1, g.adults).toFloat() / s.groupSize) / g.path.size.coerceAtLeast(1) * sight(g.place.i, weather)
                    Profile.part(if (s.human) "foraging: people" else "foraging: hunters") { forage(g, sweep, max(1, g.adults) * s.massKg, weather) }
                }
            }
        }
    }
    val (near, far) = hunters.partition { g ->
        val end = hw.placeHex[g.place]
        g.path.all { h -> hex.distanceKm(end, h) + hex.hexKm < HUNT_FINE_KM / 2 }
    }
    work(near, HUNT_FINE_KM)
    work(far, HUNT_BLOCK_KM)
}

/**
 * A party's week, as optimal foraging: along its way it meets big game, small game, carrion (if it
 * eats it), plant food (if it eats plants) and, for bands, fish, each at a rate set by how much there
 * is, how much ground it searches for that kind of food, and how likely a meeting ends in a meal (its
 * size, speed, stalking and the cover against the prey's size, speed, weapons and refuge). Each food
 * is worth its kilos over the time it takes to catch, gather or dig, prepare and eat. The party takes
 * the most rewarding foods first and adds poorer ones only while they raise its rate of intake
 * (Charnov's diet model), until it is fed. So who eats whom, and how much of each, comes from the
 * animals' traits and what is about. A band's tools and skills enter only as what they change: a
 * spear or bow the odds of a kill, snares and nets the ground searched, sickles and grinding stones
 * the time seed takes, baskets the time to carry a harvest home.
 */
internal fun HexGroups.forage(g: HexGroup, sweep: Float, packKg: Float, weather: Weather) {
    val s = g.species
    val d = s.diet
    val band = g.band
    // (Its own stream of chance, so that regions can be worked at once.)
    val rnd = kotlin.random.Random(g.id * 1_000_003L + weather.date.absoluteDay)
    // (A band's scouts range ahead instead of foraging.)
    // (Lookouts, and adults kept back with the children beyond the usual, do not gather or hunt.)
    val hands = max(1, (g.adults * (band?.ableShare ?: 1f) * (1f - (band?.scoutShare ?: 0f)) *
        (1f - CHILD_WATCH_HANDS * ((band?.childWatch ?: 1f) - 1f)).coerceIn(0.5f, 1.2f)).toInt())
    // (Walking costs food, see [walkCost].)
    val wantAll = g.dailyNeedKg * days * (1f + walkCost(g))
    val eats = 1f / (packKg * EATING_RATE) // days to eat a kilo
    // Small game is found within a few paces of the way, not as far as the party can see; snare
    // lines and nets search it while no one is there.
    val smallSweep = s.dailyTravelKm * days * 2f * SMALL_SENSE_KM * sqrt(hands.toFloat() / s.groupSize) / g.path.size.coerceAtLeast(1) * (band?.snares() ?: 1f)
    val smallCatch = smallCatch(s)
    val smallDays = SMALL_HANDLING_DAYS_PER_KG / hands * (band?.slowness(Skill.SMALL_GAME) ?: 1f) + eats
    // Plant food: picked, dug or reaped by all hands, and carried home (baskets carry more at a time).
    val eatsPlants = d.fruit + d.roots > 0f
    val carry = 1f + BASKET_GAIN * (band?.has(Tool.BASKET) ?: 0f)
    // (A band's children gather too, near camp and slower than grown hands: fruit, roots, small finds and seed.)
    val gatherers = hands + (if (band != null) CHILD_GATHER * g.young else 0f)
    val pickDays = PLANT_HANDLING_DAYS_PER_KG * tuning.plantHandling / gatherers / carry + eats
    val seedDays = SEED_HANDLING_DAYS_PER_KG * tuning.plantHandling / gatherers / carry /
        (1f + SICKLE_GAIN * (band?.has(Tool.SICKLE) ?: 0f) + GRINDER_GAIN * (band?.has(Tool.GRINDER) ?: 0f)) + eats
    // Bands gather over all the ground they walked, the women's and children's day round; animals where they end the week.
    val gatherAt = if (band != null) g.path.toList().mapNotNull { h: Int -> hw.sameBandPlace(g.place.i, h).takeIf { it >= 0 } }
        .ifEmpty { listOf(g.place.i) } else listOf(g.place.i)
    // A band's gatherers also work the country round its camp, the further the more: a camp where
    // woodland, valley and plain meet has more kinds of food within a day's walk than one in the
    // middle of any one of them. (Each hex round gives a share of what the camp's own does.)
    val roundCamp: List<Int> = if (band != null) { val h0 = hw.placeHex[g.place]; val campBand = hw.placeBand[g.place]
        (0 until 6).mapNotNull { d ->
            hex.neighbour(h0, d).takeIf { it >= 0 }?.let { hw.placeOf[campBand][it] }?.takeIf { it >= 0 && it !in gatherAt }
        } } else emptyList()
    val roundShare = (band?.let { RING_REACH * it.gatherRange } ?: 0f)
    // The foods on offer: type (0 big game of [kinds] index k, 1 small game, 2 carrion, 3 bones,
    // 4 fruit, 5 roots, 6 grass seed, 7 small finds, 8 fish), place, rate a day, kg each, days each ([Food] numbers the types).
    val n = g.path.size * (fauna.kinds.size + 4) + (gatherAt.size + roundCamp.size) * 5 + 1
    val type = Array(n) { Food.BIG_GAME }; val kind = IntArray(n); val place = IntArray(n)
    val rate = FloatArray(n); val kg = FloatArray(n); val time = FloatArray(n)
    var m = 0
    var meat = g.meatMet; var smallGot = 0f; var scraps = 0f; var plants = 0f; var fish = 0f
    // A band also works to fill its stores towards what it aims to keep (its choice); party-days spent this week.
    // (A band holding to demand sharing stores less: what it lays by is asked of it.)
    val aim = wantAll + (band?.let { storeWork(g, it) } ?: 0f)
    val plantKind = FloatArray(Store.entries.size)
    // (And what it weighs, which grinding, cooking and leaching do not change: see [addFood].)
    val plantKg = FloatArray(Store.entries.size)
    var busy = 0f
    // A band fetches the hearth's firewood first; it forages in the days left.
    val fuelDays = if (band != null) fetchFuel(g, band, hands) else 0f
    val workDays = days - fuelDays
    val spareYoung = band?.holds(Tradition.TABOO_YOUNG) == true
    // The odds a meeting with each kind ends in a kill, in each place on the way (they do not change within the week).
    val nk = fauna.kinds.size
    val odds = FloatArray(g.path.size * nk)
    // (And the stalks a kill takes, on average: one over the share of them that end in one.)
    val stalks = FloatArray(g.path.size * nk)
    // (And the bands of each kind it comes on for each animal there, unseen: for its search image.)
    val met = FloatArray(g.path.size * nk)
    val today = weather.date.absoluteDay.toDouble()
    for ((j, h) in g.path.withIndex()) {
        val p = hw.sameBandPlace(g.place.i, h).takeIf { it >= 0 } ?: continue
        for (k in 0 until nk) {
            if (fauna.adults[k][p] + fauna.young[k][p] <= 0f) continue
            val ps = fauna.kinds[k]
            residentOdds(g, ps, p, weather, today, j * nk + k, odds, stalks, met)
        }
    }
    val order = IntArray(n); val profit = FloatArray(n)
    // (The share of the week it searched before it was fed.)
    var searched = 0f
    val haul = Haul()
    // The party plans its week on what it finds, eats, and if others got there first and it is still
    // hungry, looks again at what is left.
    for (pass in 0 until FORAGE_PASSES) {
        val left = aim - (meat + smallGot + scraps + plants + fish)
        if (left <= 0.01f * wantAll) break
        m = 0
        fun offer(t: Food, k: Int, p: Int, r: Float, e: Float, h: Float) {
            // A band meets each kind of food the more, the more of its effort it puts into it.
            val rr = if (band != null) r * band.effort[when (t) { Food.GRAIN -> Food.SEED; else -> t }.ordinal] else r
            if (rr <= 0f || e <= 0f) return
            // (Biting insects slow people at whatever they do out there; fish are offered by hex, so the
            // camp's place stands for the water they fish.)
            val hh = if (band != null) h * (1f + HexSmallLife.HARASS_TIME * small.harassment(if (t == Food.FISH) g.place.i else p)) else h
            type[m] = t; kind[m] = k; place[m] = p; rate[m] = rr; kg[m] = e; time[m] = hh; m++
        }
        for ((j, h) in g.path.withIndex()) {
            val p = hw.sameBandPlace(g.place.i, h).takeIf { it >= 0 } ?: continue
            val area = land.areaKm2[p]
            val swept = min(area, sweep)
            for (k in 0 until nk) {
                val ps = fauna.kinds[k]
                val dens = fauna.adults[k][p] + fauna.young[k][p]
                val chance = odds[j * nk + k]
                if (dens <= 0f || chance <= 0f) continue
                // A kill is worth what the party can eat of it, gorging included (a band also what it can
                // dry); the rest is left to others.
                val meat1 = min(ps.massKg * creditShare(band), (1f + SURPLUS_EAT) * wantAll)
                // (Each kill also costs the stalks and chases that failed before it.)
                offer(Food.BIG_GAME, k, p, dens * swept * chance / days, meat1, HANDLING_DAYS + STALK_DAYS * stalks[j * nk + k] + meat1 * eats)
            }
            val small = this.small
            offer(Food.SMALL_GAME, 0, p, small.smallPreyKg(p) * min(area, smallSweep) * smallCatch / days, 1f, smallDays)
            if (d.carrion > 0f) {
                // (At most half of what lies on the ground searched: the rest is not found in time, or others get it;
                // and only the scavengers by trade, which watch the vultures and quarter the ground for it, find
                // that much: a wolf or a band comes on a carcass now and then, not every week.)
                val found = CARRION_FOUND * min(1f, d.carrion / SCAVENGER_CARRION)
                offer(Food.CARRION, 0, p, KgM2(found * small.carrion[p]).perKm2.over(swept).v / days, 1f, eats)
                if (Niches.cracksBones(s)) offer(Food.BONES, 0, p, KgM2(found * small.bones[p]).perKm2.over(swept).v / days, 1f, eats * BONE_DAYS)
            }
        }
        // (The ground it walked, then the country round its camp, which holds none of it.)
        if (eatsPlants) for (qi in 0 until gatherAt.size + roundCamp.size) {
            val q = if (qi < gatherAt.size) gatherAt[qi] else roundCamp[qi - gatherAt.size]
            val part = if (qi < gatherAt.size) 1f else roundShare
            // A band can strip so much of a place in a week, as its skill allows; an animal finds what lies along its way.
            // (On strange ground people find little of it.)
            val knowHere = band?.let { plantKnow(it, Hex(hw.placeHex[q])) } ?: 1f
            // (Gatherers who go further from camp cover more of the ground.)
            val reach = band?.let { min(0.9f, it.gatherShare(false, tuning) * knowHere * it.gatherRange) * part } ?: min(1f, smallSweep * g.path.size / land.areaKm2[q])
            // (The unskilled, and strangers to its plants, are slow at it.)
            val slow = band?.slowness(Skill.GATHER, knowHere) ?: 1f
            val pickQ = (pickDays - eats) * slow + eats; val seedQ = (seedDays - eats) * slow + eats
            offer(Food.FRUIT, 0, q, land.total(q, KgM2(reach * fauna.food.fruit[q])).v / days, 1f, pickQ)
            offer(Food.ROOTS, 0, q, land.total(q, KgM2(reach * fauna.food.roots[q])).v / days, 1f, ROOT_DIGGING * pickQ)
            offer(Food.FINDS, 0, q, land.total(q, KgM2(reach * fauna.food.finds[q])).v / days, 1f, ROOT_DIGGING * pickQ)
            // Grass seed must be reaped, threshed and ground: worth it only with the tools and the hands.
            offer(Food.SEED, 0, q, land.total(q, KgM2((band?.let { it.gatherShare(true, tuning) * knowHere * part } ?: reach) * fauna.food.seeds[q])).v / days, 1f, seedQ)
            // Wild and sown cereals: reaped like grass seed, but richer where they stand.
            offer(Food.GRAIN, 0, q, land.total(q, KgM2((band?.let { it.gatherShare(true, tuning) * knowHere * part } ?: reach) * fauna.food.grain[q])).v / days, 1f, seedQ)
        }
        // Bands fish the river, the lake or the sea shore by their camp.
        if (band != null) {
            val h = hw.placeHex[g.place]
            offer(Food.FISH, 0, h, fauna.water.fishWithin(h, band.fishing()) / days, 1f, FISH_HANDLING_DAYS_PER_KG / hands * band.slowness(Skill.FISH) + eats)
        }
        // Rank by kilos a day of handling, best first. (A stable sort: foods worth the same keep the order they were offered in.)
        for (i in 0 until m) profit[i] = kg[i] / time[i]
        val ranked = (0 until m).sortedWith { x, y -> java.lang.Float.compare(profit[y], profit[x]) }
        for (r in 0 until m) order[r] = ranked[r]
        var gain = 0f; var spent = 1f; var used = 0
        for (r in 0 until m) {
            val i = order[r]
            if (profit[i] <= gain / spent) break
            gain += rate[i] * kg[i]; spent += rate[i] * time[i]; used++
        }
        // (What the week's diet promised, at first sight of it: the bar game it meets must clear, see [worthChasing].)
        if (pass == 0) g.dietRate = gain / spent
        note(g) {
            "pass %d: took up %d of %d foods, best first: ".format(pass + 1, used, m) + (0 until min(used, 6)).joinToString(", ") { r -> val i = order[r]
                (if (type[i] == Food.BIG_GAME) fauna.kinds[kind[i]].name else type[i].label) + " %.1f kg/day of work".format(profit[i]) }
        }
        // What the week brings at that rate, as far as the party wants: it stops when fed.
        val f = if (gain > 0f) min(1f, left / (workDays * gain / spent)) else 0f
        if (pass == 0) searched = f
        if (FORAGE_DIAG == s.name && diagSampled(g, weather, 0.002f, 1)) System.err.println(
            "forage ${s.name} adults ${g.adults} want ${wantAll} left $left offers $m used $used gain $gain spent $spent f $f: " +
            order.take(min(6, m)).joinToString("; ") { "t${type[it].ordinal} k${kind[it]} r=${rate[it]} e=${kg[it]} h=${time[it]}" })
        for (r in 0 until used) {
            val i = order[r]
            val expected = workDays * rate[i] / spent * f
            val p = place[i]
            if (type[i] != Food.BIG_GAME) busy += expected * time[i]
            when (type[i]) {
                Food.BIG_GAME -> {
                    val kills1 = huntGame(g, kind[i], p, expected, kg[i], spareYoung, rnd, haul)
                    if (kills1 <= 0) continue
                    busy += kills1 * time[i]
                    meat += haul.kg
                }
                Food.SMALL_GAME -> smallGot += small.catchSmallIn(p, Kg(expected), min(land.areaKm2[p], smallSweep), weather).v
                Food.CARRION -> scraps += scavenge(g, p, expected, sweep, rnd)
                Food.BONES -> scraps += small.crackBonesIn(p, Kg(expected), min(land.areaKm2[p], sweep)).v
                Food.FRUIT, Food.ROOTS, Food.SEED, Food.FINDS -> {
                    gather(g, type[i], p, expected, hands, haul)
                    if (haul.busy > 0f) busy += haul.busy
                    plants += haul.kg
                    val st = when (type[i]) { Food.ROOTS -> Store.ROOTS; Food.SEED -> Store.SEED; else -> Store.FRUIT }.ordinal
                    plantKind[st] += haul.kg; plantKg[st] += haul.mass
                }
                Food.FISH -> fish += fauna.water.fishFor(p, expected, band?.fishing() ?: 1f)
                Food.GRAIN -> { reap(g, p, expected, haul); plants += haul.kg; plantKind[Store.SEED.ordinal] += haul.kg; plantKg[Store.SEED.ordinal] += haul.mass }
            }
        }
    }
    // The resident bands it came on while it searched count towards its search image, as the herds it met do.
    if (searched > 0f) countResidentsMet(g, sweep, met, searched, today)
    if (band != null) practise(g, band, meat > g.meatMet, smallGot, plants, fish)
    var meatEaten = min(meat, max(0f, wantAll - smallGot - scraps - plants - fish))
    note(g) {
        "foraged for %.0f kg wanted: meat %.0f (%.0f from herds met), small game %.0f, carrion %.0f, plants %.0f, fish %.0f; busy %.1f of %d days; sight %.2f of a clear day in the open%s".format(
            wantAll, meat, g.meatMet, smallGot, scraps, plants, fish, busy, days, sight(g.place.i, weather),
            if (band != null) "; %.0f%% of adults scouting".format(100 * band.scoutShare) else "")
    }
    if (FORAGE_DIAG == s.name && diagSampled(g, weather, 0.003f, 2)) System.err.println(
        "ate ${s.name} want $wantAll meat $meat small $smallGot scraps $scraps plants $plants fish $fish path ${g.path.size}")
    var eaten = meatEaten + smallGot + scraps + plants + fish
    stats.hunting.merge(Tally.diet(s, "asked"), wantAll.toInt(), Int::plus)
    stats.hunting.merge(Tally.diet(s, "meat"), (meatEaten + fish).toInt(), Int::plus)
    stats.hunting.merge(Tally.diet(s, "small prey"), smallGot.toInt(), Int::plus)
    stats.hunting.merge(Tally.diet(s, "carrion"), scraps.toInt(), Int::plus)
    stats.hunting.merge(Tally.diet(s, "plants"), plants.toInt(), Int::plus)
    // For bands, the same by the kind of country they are in, and how they fared.
    if (band != null) {
        val c = Biome.entries[biomeOfPlace[g.place]].name.lowercase().replace('_', ' ')
        stats.hunting.merge(Tally.bandsIn(c, "asked"), wantAll.toInt(), Int::plus)
        stats.hunting.merge(Tally.bandsIn(c, "meat"), (meat + smallGot + scraps).toInt(), Int::plus)
        stats.hunting.merge(Tally.bandsIn(c, "plants"), plants.toInt(), Int::plus); stats.hunting.merge(Tally.bandsIn(c, "fish"), fish.toInt(), Int::plus)
        stats.hunting.merge(Tally.bandsIn(c, "band-weeks"), 1, Int::plus); stats.hunting.merge(Tally.bandsIn(c, "people-weeks"), g.size, Int::plus)
        stats.hunting.merge(Tally.bandsIn(c, "hearth, %"), (100f * band.hearthWood).toInt(), Int::plus)
        stats.hunting.merge(Tally.bandsIn(c, "fuel days, %"), (100f * fuelDays / days).toInt(), Int::plus)
    }
    var spare = meat - meatEaten
    if (band != null) {
        // This week's food: fresh meat and fish (small game and scraps with it), and plant food by kind.
        val got = plantKind
        // (The small game's pelts are set aside as it is caught: they are not food, and go to the skins or the scavengers.)
        val pelts = SMALL_SKIN * smallGot
        got[Store.FRESH.ordinal] = meat + smallGot - pelts + scraps + fish; plantKg[Store.FRESH.ordinal] = got[Store.FRESH.ordinal]
        // Eaten in the order it spoils, this week's first, then from the stores.
        var need = wantAll; var ateMeat = 0f; var ateKg = 0f
        for (st in EAT_ORDER) {
            val i = st.ordinal; val e = min(need, got[i])
            if (e > 0f) { val kg = plantKg[i] * e / got[i]; plantKg[i] -= kg; ateKg += kg }
            got[i] -= e; need -= e; if (st.meat) ateMeat += e
        }
        for (st in EAT_ORDER) { val e = min(need, g.stores[st]); g.stores[st] -= e; need -= e; if (st.meat) ateMeat += e; ateKg += e * g.storeKgPer[st] }
        eaten = wantAll - need
        land.books?.flow(Books.Stuff.MATTER, "eaten", -ateKg.toDouble())
        // (People living mostly on plant food need salt, which meat and fish carry; short of it,
        // they are listless and weak, and the food does them the less good.)
        if (eaten > 0f) {
            val plantShare = ((eaten - ateMeat) / eaten).coerceIn(0f, 1f)
            val want = SALT_A_PERSON_WEEK * plantShare * g.size * days / 7f
            if (want > 0f) {
                val had = min(want, band.goods[Material.SALT]); band.goods[Material.SALT] -= had
                eaten *= 1f - tuning.saltLack * plantShare * (1f - had / want)
            }
        }
        stats.hunting.merge(Tally.of(s, WeekStats.MEAT_EATEN), ateMeat.toInt(), Int::plus)
        stats.hunting.merge(Tally.of(s, WeekStats.PLANTS_EATEN), (eaten - ateMeat).toInt(), Int::plus)
        keepAndSpoil(g, band, got, plantKg, workDays, busy, hands, fuelDays, pelts, weather)
        spare = 0f; meatEaten = ateMeat
    }
    // Gorging: what is left of a kill is eaten beyond need, as far as the gut holds and reserves are short.
    if (spare > 0f && g.fat < s.maxFatDays && band == null) {
        val gorge = min(spare, SURPLUS_EAT * wantAll)
        g.surplus = gorge / g.dailyNeedKg.coerceAtLeast(1e-3f)
        spare -= gorge
        small.deposit(DeadPool.DUNG, g.place, Kg(HexSmallLife.DUNG * gorge))
        land.books?.let { bookEaten(it, gorge * meatShare(null), HexSmallLife.DUNG * gorge) }
    }
    g.fed = (eaten / wantAll).coerceIn(0f, 1f)
    // What the pack could not finish is left for the scavengers; what it ate comes back in part as dung.
    small.deposit(DeadPool.CARRION, g.place, Kg(spare))
    // (A band's meat eaten already holds the small game, scraps and fish it ate; what it stored is not yet eaten.)
    val animalEaten = if (band != null) meatEaten else meatEaten + smallGot + scraps + fish
    small.deposit(DeadPool.DUNG, g.place, Kg(HexSmallLife.DUNG * animalEaten))
    land.books?.let { bookForage(it, band, meatEaten, smallGot + scraps + plants + fish, fish, spare, HexSmallLife.DUNG * animalEaten) }
    // (The meat it held from the herds it met is eaten, stored or left by now.)
    g.meatSpent = true
}

/** Books [kg] eaten by a beast and the [dung] it dropped of it (see [Books]). */
private fun bookEaten(b: Books, kg: Float, dung: Float) {
    b.flow(Books.Stuff.MATTER, "eaten", -kg.toDouble()); b.flow(Books.Stuff.MATTER, "dung and frass", dung.toDouble())
}

/**
 * Books [forage]'s week (see [Books]): the [fish] it caught come from the water life; a beast ate the meat of its [meatEaten]
 * (its need counted in the whole weight of its kills, see [creditShare]) and [rest], kg, and dropped [dung]; and the [spare]
 * it left lies as carrion at the whole weight credited, more than the meat it is (a known leak). A band's meal is booked as it eats.
 */
private fun HexGroups.bookForage(b: Books, band: Band?, meatEaten: Float, rest: Float, fish: Float, spare: Float, dung: Float) {
    b.flow(Books.Stuff.MATTER, "to and from the water life", fish.toDouble())
    if (band == null) {
        bookEaten(b, meatEaten * meatShare(null) + rest, 0f)
        if (spare > 0f) b.leak(Books.Stuff.MATTER, "beasts' leftovers left at their whole credited weight", (spare * (1f - meatShare(null))).toDouble())
    }
    b.flow(Books.Stuff.MATTER, "dung and frass", dung.toDouble())
}


/**
 * [forage]'s end of a band's week with its food: what is left of the week's [got] (kg by [Store]) is kept, spare hands
 * ([hands] of them, [workDays] less the [busy] days foraging) dry fresh meat and fish, the small game's skins are kept,
 * and the stores spoil, what rots going to carrion where the band is.
 */
private fun HexGroups.keepAndSpoil(g: HexGroup, band: Band, got: FloatArray, gotKg: FloatArray, workDays: Float, busy: Float, hands: Int, fuelDays: Float, pelts: Float,
    weather: Weather) {
    val s = g.species
    // What is left is kept; spare hands dry fresh meat and fish over the fire, as fast as their skill allows.
    for (st in Store.entries) addFood(g.stores, g.storeKgPer, st, got[st.ordinal], gotKg[st.ordinal])
    // Digging soaks and drawing their water takes time where that is the only water (see [HexDrinkingWater.mustDig]).
    val spareDays = max(0f, workDays - busy - (if (land.drink.mustDig(g.place, s.dailyTravelKm)) HexDrinkingWater.DIG_SHARE * days else 0f))
    // (Racks in the sun dry little in wet weather; smoking over a slow fire works in any.)
    val sun = band.skill(Skill.STORE) / (1f + weather.precipitation[g.place] / DRY_RAIN_MM)
    val smoke = if (band.knowsSkill[Skill.SMOKING] && band.fire > 0f) band.skill(Skill.SMOKING) * band.fire else 0f
    // (A band with salt salts what it cures: salt cures in any weather.)
    val salt = band.goods[Material.SALT]
    val salting = if (salt > 0f) band.skill(Skill.STORE) else 0f
    val dryRate = max(max(sun, smoke), salting)
    // (As much of it as the band chooses to.)
    val dried = min(g.stores[Store.FRESH] * min(1f, band.dryWill), spareDays * hands * dryRate / DRY_DAYS_PER_KG)
    if (dried > 0f) {
        val saltedNow = min(dried, salt / SALT_PER_KG); band.goods[Material.SALT] -= saltedNow * SALT_PER_KG
        val before = g.stores[Store.DRIED]; band.salted = (before * band.salted + saltedNow) / (before + dried)
        g.stores[Store.FRESH] -= dried; addFood(g.stores, g.storeKgPer, Store.DRIED, dried, dried * g.storeKgPer[Store.FRESH]); band.practise(if (smoke > sun) Skill.SMOKING else Skill.STORE)
    }
    // What time is left is for making and mending (see [bandWeek]).
    val time = BandTime(spareDays * hands)
    time.took(Activity.FIREWOOD, fuelDays * hands); time.took(Activity.FOOD, (workDays - spareDays) * hands)
    time.spendAtMost(Activity.DRYING, if (dried > 0f) dried * DRY_DAYS_PER_KG / dryRate else 0f)
    val unskinned = skinSmallGame(band, time, pelts)
    band.spareDays = time.left
    time.count(stats.hunting, "all", days * hands.toFloat())
    time.count(stats.hunting, Activity.FIREWOOD, Activity.FOOD, Activity.DRYING, Activity.SKINS)
    // And it spoils, each kind at its own rate, fresh meat the faster the warmer.
    // (In storage pits at home stores keep better, though mice get into the grain, nuts and roots.)
    val home = if (band.atHome) band.house else 0f
    // (Smoked meat keeps longer than sun-dried; pits sealed with clay and plaster keep the mice out.)
    val smoked = if (band.knowsSkill[Skill.SMOKING]) 1f - SMOKE_KEEP * band.skill(Skill.SMOKING) else 1f
    var rotten = spoil(g.stores, weather.temperature[g.place], 1f - PIT_KEEP * home, smoked * (1f - tuning.saltKeep * band.salted), g.storeKgPer)
    val sealed = if (band.knowsSkill[Skill.SEALING]) 1f - SEAL_KEEP * band.skill(Skill.SEALING) else 1f
    if (home > 0f) {
        for (st in listOf(Store.SEED, Store.FRUIT, Store.ROOTS)) {
            val x = g.stores[st] * MICE * sealed * home * days / 7f
            g.stores[st] -= x
            rotten += x * g.storeKgPer[st]
        }
        if (band.knowsSkill[Skill.SEALING] && g.larder > 0f) band.practise(Skill.SEALING, 0.2f * PRACTICE)
    }
    small.deposit(DeadPool.CARRION, g.place, Kg(rotten + unskinned))
}

/**
 * The small game's skins are kept too, as far as [b] has time to scrape and dry them: many small pelts sewn
 * together (hare, fox, marmot) clothe as well as a few big hides. [pelts] kg were set aside from the small game
 * caught, in the [time] it has to spare; returns the kg left unskinned, for the scavengers.
 */
private fun HexGroups.skinSmallGame(b: Band, time: BandTime, pelts: Float): Float {
    val skinDays = min(time.left, SKIN_DAYS * pelts)
    b.goods[Material.HIDE] += skinDays / SKIN_DAYS
    time.spend(Activity.SKINS, skinDays)
    stats.hunting.merge(Tally.hide("small pelts"), (1000f * skinDays / SKIN_DAYS).roundToInt(), Int::plus)
    return max(0f, pelts - skinDays / SKIN_DAYS)
}

/** Tallies the hide in the [took] kg of hide, bone and sinew a band took from kills of [ps] (none if no band), by [source]; returns [took]. */
internal fun HexGroups.tallyHide(source: String, ps: Species, took: Float?): Float {
    if (took == null || took <= 0f) return 0f
    val p = ps.products
    stats.hunting.merge(Tally.hide(source), (1000f * took * p.hide / (p.hide + p.bone + p.sinew)).roundToInt(), Int::plus)
    return took
}

/** What one of [forage]'s takes brought: kilos of food, and party-days spent on it beyond the take itself. */
private class Haul { var kg = 0f; var busy = 0f; var mass = 0f }

/**
 * [forage]'s hunt of kind [k] at place [p]: [expected] kills on average, each worth [kgEach] to the party.
 * Returns the kills (0: none, and nothing done); [out] gets the meat. A band at home may keep young it catches.
 */
private fun HexGroups.huntGame(g: HexGroup, k: Int, p: Int, expected: Float, kgEach: Float, spareYoung: Boolean, rnd: kotlin.random.Random, out: Haul): Int {
    val s = g.species; val band = g.band
    val ps = fauna.kinds[k]
    val area = land.areaKm2[p]
    val dens = fauna.adults[k][p] + fauna.young[k][p]
    var kills1 = poisson(expected, rnd)
    kills1 = min(kills1, (dens * area * 0.5f).toInt())
    if (kills1 <= 0) return kills1
    // (No more of either are killed than are there.)
    var ny = min(if (spareYoung) 0 else (kills1 * fauna.young[k][p] / dens).toInt(), (fauna.young[k][p] * area).toInt())
    val na = min(kills1 - ny, (fauna.adults[k][p] * area).toInt())
    kills1 = na + ny
    if (kills1 <= 0) return kills1
    // A band at home may keep young it catches, to raise by its camp.
    if (band != null && band.atHome && ny > 0 && k < band.herd.size) {
        val kept = binomial(ny, min(1f, CAPTURE * band.keepWill * ps.domestication.average().toFloat()), rnd)
        if (kept > 0) {
            band.herd[k] += kept; ny -= kept
            fauna.young[k][p] = max(0f, fauna.young[k][p] - kept / area)
            stats.meetings.merge("young animals kept", kept, Int::plus)
        }
    }
    fauna.young[k][p] = max(0f, fauna.young[k][p] - ny / area); fauna.adults[k][p] = max(0f, fauna.adults[k][p] - na / area)
    val young = min(HexFauna.YOUNG_WEIGHT * ps.massKg, kgEach)
    out.kg = na * kgEach + ny * young
    // (Hides, bone and sinew come out of what the hunters leave of the carcasses.)
    val leftA = na * leftOver(ps, kgEach, band)
    small.deposit(DeadPool.CARRION, p, KgM2((leftA + ny * (HexFauna.YOUNG_WEIGHT * ps.massKg - young) - tallyHide("residents", ps, band?.skin(ps, na, leftA))) / (area * 1e6f)))
    band?.let { it.practise(Skill.HUNT); it.practise(Skill.TRACK); it.practise(Skill.STALK); it.practise(Skill.BUTCHER) }
    stats.kills.merge(ps.name, kills1, Int::plus)
    note(g) { "killed %d %s".format(kills1, ps.name) }
    fauna.killed(p, Kg(kills1 * ps.massKg))
    stats.hunting.merge(Tally.of(s, Tally.KILLS_OF_RESIDENTS), kills1, Int::plus)
    diag(ps, "resident kills", kills1.toFloat())
    return kills1
}

/** [forage]'s reaping of wild or sown grain at place [p], [expected] kg a party could take: [out] gets the kilos it is worth, and weighs. */
private fun HexGroups.reap(g: HexGroup, p: Int, expected: Float, out: Haul) {
    val band = g.band
    val taken = land.total(p, fauna.food.take(fauna.food.grain, p, land.spread(p, Kg(expected)))).v
    val took = taken * seedValue(band) * (band?.cookValue(Food.GRAIN) ?: 1f)
    out.kg = took; out.mass = taken
    // A sickle takes the ears that hold their grain more than those that shatter at a touch.
    if (band != null && took > 0f) {
        val d = fauna.food.domestic[p]; val sel = SICKLE_SELECT * band.has(Tool.SICKLE)
        val harvested = d * (1f + sel) / (1f + sel * d).coerceAtLeast(1e-6f)
        val have = g.stores[Store.SEED]
        band.seedDomestic = (band.seedDomestic * have + harvested * took) / (have + took).coerceAtLeast(1e-6f)
        stats.hunting.merge(Tally.of(g.species, WeekStats.GRAIN_REAPED), took.toInt(), Int::plus)
    }
}

/**
 * [forage]'s odds that [g] comes on and kills resident [ps] at place [p], per animal there, into [odds] at [i]; the
 * stalks a kill takes into [stalks], and the bands come on unseen per animal into [met]. Prey resting while the hunter
 * is about lie up in cover and are found less; a band's weapons, dogs and hunting skill change the odds that a stalk
 * ends in a kill, as at a herd ([killChance]). Residents too go about in their kind's usual bands: a band is come on
 * as one, as a herd's are, and may see the hunters first and keep out of their way ([seenFirst]); one try at it, for a
 * band chased scatters. And game it seldom comes on it does not look for (prey switching, see [SEARCH_HALF]).
 */
private fun HexGroups.residentOdds(g: HexGroup, ps: Species, p: Int, weather: Weather, today: Double, i: Int,
    odds: FloatArray, stalks: FloatArray, met: FloatArray) {
    val s = g.species
    val success = killChance(g, ps, catchable(s, ps, p), 0f, 1f)
    val image = g.metLately(ps.index, today).let { it / (it + SEARCH_HALF) }
    met[i] = (0.5f + 0.5f * Niches.overlap(s, ps)) * (1f - seenFirst(g, ps, p, 0f, weather)) / ps.groupSize
    odds[i] = success * met[i] * image
    stalks[i] = 1f / success.coerceIn(1e-3f, 1f)
}

/** [forage]'s count of the resident bands [g] came on unseen ([met] per animal, by path step and kind) in the [searched] share of its week. */
private fun HexGroups.countResidentsMet(g: HexGroup, sweep: Float, met: FloatArray, searched: Float, today: Double) {
    val nk = fauna.kinds.size
    val counts = FloatArray(Catalogue.all.size)
    for ((j, h) in g.path.withIndex()) {
        val p = hw.sameBandPlace(g.place.i, h).takeIf { it >= 0 } ?: continue
        val swept = min(land.areaKm2[p], sweep)
        for (k in 0 until nk) counts[fauna.kinds[k].index] += (fauna.adults[k][p] + fauna.young[k][p]) * swept * met[j * nk + k] * searched
        if (HUNT_DIAG) for (k in 0 until nk) { val ps = fauna.kinds[k]
            diag(ps, "resident bands met", (fauna.adults[k][p] + fauna.young[k][p]) * swept * (0.5f + 0.5f * Niches.overlap(g.species, ps)) / ps.groupSize * searched) }
    }
    if (HUNT_DIAG) for (k in 0 until nk) diag(fauna.kinds[k], "resident bands met unseen", counts[fauna.kinds[k].index])
    g.metGame(counts, today)
}

/**
 * [forage]'s gathering of plant food [t] (fruit, roots, grass seed or small finds) at place [p], [expected] kg a
 * party could take: [out] gets the kilos it is worth once seed is ground and food cooked or leached, what they weigh, and the
 * party-days the leaching took ([hands] at it).
 */
private fun HexGroups.gather(g: HexGroup, t: Food, p: Int, expected: Float, hands: Int, out: Haul) {
    val s = g.species; val band = g.band
    out.busy = 0f
    val stock = when (t) { Food.FRUIT -> fauna.food.fruit; Food.ROOTS -> fauna.food.roots; Food.SEED -> fauna.food.seeds; else -> fauna.food.finds }
    val taken = land.total(p, fauna.food.take(stock, p, land.spread(p, Kg(expected)))).v
    var took = taken; var mass = taken
    // Raw seed is poorly digested; ground and baked into bread it feeds fully.
    if (t == Food.SEED) took *= seedValue(band)
    if (band != null && took > 0f) {
        // Earth ovens and gruel make more of it; bands that know how leach bitter plants too.
        val cook = band.cookValue(t)
        if (cook > 1f) { took *= cook; band.practise(if (t == Food.ROOTS) Skill.EARTH_OVEN else Skill.STONE_BOILING) }
        if (t != Food.FINDS) {
            var extra = took * band.detoxExtra(land.palatable[p], land.drink.water[p] >= Thresholds.WET || land.drink.waterKm[p] < 3f)
            // (What is leached is more of the plants, bitter before: taken from them, as far as there is any.)
            if (extra > 0f) {
                val more = land.total(p, fauna.food.take(stock, p, land.spread(p, Kg(extra * taken / took)))).v
                extra = more * took / taken; mass += more
            }
            if (extra > 0f) {
                took += extra
                out.busy = extra * LEACH_DAYS_PER_KG / hands
                band.practise(Skill.LEACHING)
                stats.hunting.merge(Tally.of(s, "plants leached, kg"), extra.toInt(), Int::plus)
            }
        }
    }
    out.kg = took; out.mass = mass
}

/** The kinds of food [forage] offers a party; all but [GRAIN] are also a band's effort slots ([FOOD_TYPES]: grain goes by grass seed's). */
enum class Food(val label: String) {
    BIG_GAME("big game"), SMALL_GAME("small game"), CARRION("carrion"), BONES("bones"), FRUIT("fruit"), ROOTS("roots"),
    SEED("grass seed"), FINDS("small finds"), FISH("fish"), GRAIN("grain")
}

private val FORAGE_DIAG = Config.string("forageDiag")

/**
 * Whether group [g]'s week is one of the [share] sampled for the forage diagnostic: drawn from a hash of the group
 * and the day, not from the group's random stream, so that turning the diagnostic on does not change the run.
 */
private fun diagSampled(g: HexGroup, weather: Weather, share: Float, salt: Int) =
    unitOf(mix64(g.id * HASH_A + weather.date.absoluteDay * HASH_B + salt * HASH_C)) < share

/** Spoils a week of [stores] at [t] °C, each kind at its own rate; returns kg lost (weighed at [kgPer] for each kg of worth). */
internal fun HexGroups.spoil(stores: EnumFloats<Store>, t: Float, keep: Float = 1f, driedKeep: Float = 1f, kgPer: EnumFloats<Store>? = null): Float {
    var lost = 0f
    val warm = if (t <= 0f) 0f else min(2.8f, exp(0.069f * (t - 15f)))
    for (st in Store.entries) {
        val share = if (st == Store.FRESH) 1f - exp(-HexSmallLife.CARRION_ROT * warm * days) else min(1f,
            st.lostWeekly * keep * (if (st == Store.DRIED) driedKeep else 1f) * days / 7f)
        val x = stores[st] * share
        stores[st] -= x; lost += x * (kgPer?.get(st) ?: 1f)
    }
    return lost
}

/** [forage]'s practice for band [band] of [g] at what it did this week (and its tally of fish). */
private fun HexGroups.practise(g: HexGroup, band: Band, hunted: Boolean, smallGot: Float, plants: Float, fish: Float) {
    if (hunted) band.practise(Skill.HUNT)
    if (smallGot > 0f) band.practise(Skill.SMALL_GAME)
    if (plants > 0f) { band.practise(Skill.GATHER); learnPlants(band, hw.placeHex[g.place]) }
    if (fish > 0f) band.practise(Skill.FISH)
    if (band.fire > 0f) band.practise(Skill.ROASTING)
    stats.hunting.merge(Tally.of(g.species, "fish, kg"), fish.toInt(), Int::plus)
}

/** [forage]'s scavenging of [expected] kg at place [p] by [g], over [sweep] km²: what it found, kg. */
private fun HexGroups.scavenge(g: HexGroup, p: Int, expected: Float, sweep: Float, rnd: kotlin.random.Random): Float {
    val band = g.band
    val got = small.scavengeIn(p, Kg(expected), min(land.areaKm2[p], sweep)).v
    // A band at a carcass may meet its owner: lions or hyenas back at their kill. Driving
    // them off is done with a crowd and a din, but now and then someone is mauled.
    if (band != null && got > 1f) {
        val beasts = monthHunterKg[p] / land.areaKm2[p]
        if (rnd.nextFloat() < SCAVENGE_RISK * beasts / (beasts + 3f)) {
            band.wounded(1f, g.adults)
            stats.meetings.merge("bands mauled at a carcass", 1, Int::plus)
        }
    }
    return got
}

/** Chance a band scavenging a carcass where hunters are thick has one of its people mauled by them. */
const val SCAVENGE_RISK = 0.01f

/** Share of a carcass a hunter takes as food: a band as its butchers' skill allows (all the fat and marrow, at best). */
internal fun meatShare(band: Band?) = if (band == null) MEAT_SHARE else min(MEAT_MOST, MEAT_SHARE * band.edge(Skill.BUTCHER))
/**
 * Share of a kill's weight that counts towards a hunter's need: all of it for a beast, whose need is reckoned in the weight
 * of the prey it kills, the parts it leaves included ([Allometry.dailyIntakeKg]); for a band, what its butchers make of it.
 */
internal fun creditShare(band: Band?) = if (band == null) 1f else meatShare(band)
/**
 * For the books: [credit] kg added to [hunter]'s meat in hand (taken off when below zero) from a kill of [kg] that left
 * [carrion] kg; a beast holds only [meatShare] of what it is credited, so the rest of a kill credited whole goes nowhere.
 */
private fun HexGroups.bookKill(hunter: HexGroup, credit: Float, kg: Float, carrion: Float) {
    land.books?.leak(Books.Stuff.MATTER, "hunters' kills credited at more or less than they weigh (young, rivals, theft)",
        (credit * meatShare(hunter.band) / creditShare(hunter.band) + carrion - kg).toDouble())
}
/** What is left of a kill of [ps] as carrion when the hunter took [kgEach] of it towards its need. */
internal fun leftOver(ps: Species, kgEach: Float, band: Band?) = max(0f, ps.massKg - kgEach * meatShare(band) / creditShare(band))
const val MEAT_MOST = 0.7f

/** What a kilo of reaped seed or grain feeds against a kilo of other food: raw, little; ground and baked, fully. */
internal fun seedValue(band: Band?) = RAW_SEED + (1f - RAW_SEED) * (band?.has(Tool.GRINDER) ?: 0f)
const val RAW_SEED = 0.6f
/** Beer: grain brewed for each guest at a feast, kg; how much more a feast with beer binds. */
const val BEER_KG = 1f
const val BEER_TIES = 0.5f

/** How readily [s] catches small game it comes on, 0..1: the smaller and nimbler the hunter, the better; cover hides the prey from all but stalkers. */
internal fun smallCatch(s: Species) = SMALL_CATCH * min(1f, SMALL_HUNTER_KG / s.massKg)

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
 * herds of different kinds pass each other by.
 */
internal fun HexGroups.meet(weather: Weather) {
    // Only hexes a hunter passes through can hold a meeting that matters: herds pass each other by.
    var mt = Profile.mark()
    val hunted = BitSet(hex.size)
    for (g in groups) if (g.alive && g.species.carnivore) for (h in g.path) hunted.set(h)
    // (With rounds on, for hex-rounds-check: each herd's week as it set out, by heads and by herds.)
    if (ROUNDS) for (g in groups) if (g.alive && !g.species.carnivore && g.route[0] != ROUND_OPEN) {
        val c = roundsWhere(g, weather); stats.hunting.merge(c + "heads", g.size, Int::plus); stats.hunting.merge(c + "herds", 1, Int::plus)
    }
    // (With rounds on, for hex-rounds-check: hunters about where each herd set out, its own hex and the six round it.)
    if (ROUNDS) roundsStart(weather)
    val byHex = HashMap<Int, ArrayList<Stay>>()
    for ((k, g) in groups.withIndex()) {
        if (!g.alive) continue
        val last = g.path.size - 1
        for (i in g.path.indices) {
            val to = if (i < last) g.days[i + 1] else days.toFloat()
            // A herd grazing where it ends its week walks its usual day's going about it, out into the hexes
            // round as far as that takes it, and can be come on there (see [rangeShares]); on its way, it is
            // where its path runs.
            if (i == last && !g.species.carnivore) {
                rangeShares(g, g.path[i]) { h, share -> if (hunted.get(h)) byHex.getOrPut(h) { ArrayList() } += Stay(k, i, h, g.days[i], to, share) }
                continue
            }
            if (!hunted.get(g.path[i])) continue
            byHex.getOrPut(g.path[i]) { ArrayList() } += Stay(k, i, g.path[i], g.days[i], to)
        }
    }
    mt = Profile.lap("meetings: who is in each hex", mt)
    // Pairs with a hunter or band in them (herds pass each other by), hex by hex on all cores.
    val events = byHex.values.parallelStream().filter { it.size >= 2 }.flatMap { stays ->
        val out = ArrayList<Meeting>()
        for (i in stays.indices) { if (!groups[stays[i].k].species.carnivore) continue; for (j in stays.indices) {
            // Each hunter with every herd, and each pair of hunters once.
            if (j == i || (j < i && groups[stays[j].k].species.carnivore)) continue
            val sa = stays[i]; val sb = stays[j]
            val from = max(sa.from, sb.from); val to = min(sa.to, sb.to)
            if (from > to) continue
            out += Meeting(from, to - from, sa.k, sa.index, sb.k, sb.index, sa.hex, sa.share * sb.share)
        } }
        out.stream()
    }.collect(Collectors.toCollection { ArrayList<Meeting>() })
    mt = Profile.lap("meetings: pairs", mt)
    // Meetings at the same hour come in an order drawn afresh each week, not by age in the list
    // (the first gets first chase and uses up the herd's meetings).
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
        val p = hw.sameBandPlace(a.place, h).takeIf { !it.none } ?: a.place
        val met = poisson(encounters(a, b, p, e.span * e.share), rnd)
        if (met == 0) continue
        if (slipsAway(a, b, p) || slipsAway(b, a, p)) { stats.meetings.merge("slipped away", 1, Int::plus); continue }
        if (a.species.carnivore && b.species.carnivore) {
            if (a.species.human && b.species.human) Profile.part("meetings: bands (culture, gifts, feasts, raids)") { bandsMeet(a, b, weather) }
            else if (a.species == b.species) Profile.part("meetings: rival packs") { rivalsMeet(a, e.a, e.ai, b, e.b, e.bi, h, stop) }
            else Profile.part("meetings: hunters of other kinds") { enemiesMeet(a, e.a, e.ai, b, e.b, e.bi, h, stop) }
        }
        else {
            val (hunter, hi, herd, ri) = if (a.species.carnivore) listOf(e.a, e.ai, e.b, e.bi) else listOf(e.b, e.bi, e.a, e.ai)
            if (ROUNDS) roundsMeeting(groups[herd], ri, h, weather)
            Profile.part("meetings: hunters and herds") { hunterMeetsHerd(groups[hunter], hunter, hi, groups[herd], herd, ri, h, stop, weather, met, today + e.day.toDouble()) }
        }
    }
    // Each group ends its week where it stopped: the same height band in that hex if it has one.
    for ((k, g) in groups.withIndex()) {
        if (!g.alive || stop[k] == g.path.size - 1) continue
        val i = max(0, stop[k])
        val h = g.path[i]
        g.path = g.path.copyOf(i + 1); g.days = g.days.copyOf(i + 1)
        val band = hw.placeBand[g.place]
        g.place = Place(hw.placeOf[band][h].takeIf { it >= 0 } ?: (0 until HexWorld.BANDS_M.size).map { hw.placeOf[it][h] }.firstOrNull { it >= 0 } ?: g.place.i)
    }
    Profile.lap("meetings: settling them", mt)
}

/**
 * Two bands meet: they share news of good ground (each learns where the other did best this month),
 * and a band that is going hungry is given a share of the other's larder.
 */
internal fun HexGroups.bandsMeet(a: HexGroup, b: HexGroup, weather: Weather) {
    stats.meetings.merge("bands met", 1, Int::plus)
    note(a) { "met band #%d (%d people; tie %.2f)".format(b.id, b.size, a.band?.tie(b.id) ?: 0f) }
    note(b) { "met band #%d (%d people; tie %.2f)".format(a.id, a.size, b.band?.tie(a.id) ?: 0f) }
    if (meetingEndsInRaid(a, b, weather)) return
    val (poor, rich) = if (a.fed + a.larder / (a.dailyNeedKg * 30f) < b.fed + b.larder / (b.dailyNeedKg * 30f)) a to b else b to a
    // Bands that hold to sharing give more, and sooner.
    val sharing = rich.band?.holds(Tradition.SHARING) == true
    // (More to a band it is close to.)
    val close = (rich.band?.tie(poor.id) ?: 0f).coerceAtLeast(0f)
    if (poor.fed < (if (sharing) 0.9f else 0.8f) && rich.larder > 0f) {
        // (As much as the giver chooses to give.)
        val chosen = min(0.9f, BAND_GIFT * (if (sharing) 2f else 1f) * (1f + close) * (rich.band?.giveWill ?: 1f)) * rich.larder
        // (And no less than the favours it owes the poor band, as fully as it returns them.)
        val week = poor.dailyNeedKg * days
        val gift = given(rich, poor, chosen, (1f - poor.fed) * week + week)
        if (gift > 0f) {
        moveFood(rich, poor, gift)
        poor.band?.befriend(rich.id, TIE_GIFT); rich.band?.befriend(poor.id, 0.5f * TIE_GIFT)
        helped(rich, poor, gift)
        stats.meetings.merge("food given", 1, Int::plus)
        note(rich) { "gave %.0f kg of food to hungry band #%d".format(gift, poor.id) }
        note(poor) { "was given %.0f kg of food by band #%d".format(gift, rich.id) }
        }
    }
    // Gifts: bands close to each other give each other ornaments, which travel on along the ties.
    for ((giver, taker) in listOf(a to b, b to a)) {
        val gb = giver.band ?: continue; val tb = taker.band ?: continue
        if (gb.tie(taker.id) < 0.3f || gb.ornaments < 1f) continue
        val gift = min(1f, ORNAMENT_GIFT * gb.giveWill) * gb.ornaments
        if (gift <= 0f) continue
        val exoticShare = gb.exotic / gb.ornaments
        gb.ornaments -= gift; gb.exotic -= gift * exoticShare
        tb.ornaments += gift; tb.exotic += gift * exoticShare
        tb.befriend(giver.id, 0.5f * TIE_GIFT)
        stats.meetings.merge("ornaments given", 1, Int::plus)
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
            val gift = min(spare, lack) * min(1f, gb.giveWill)
            gb.goods[m] -= gift; tb.goods[m] += gift
            useful += gift / (KEEP_YEARS * need * max(1, taker.adults))
            stats.meetings.merge("${m.name.lowercase()} given", 1, Int::plus)
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
        if (keen <= 0f) continue
        val spare = host.larder - hb.storeAim * host.dailyNeedKg
        if (spare <= 0f) continue
        val meal = min(spare, FEAST_KG * guest.size * keen)
        moveFood(host, guest, meal)
        // With grain to spare and vessels to brew in, the host pours beer: the feast is remembered better.
        val grain = host.stores[Store.SEED]
        val beer = if (hb.has(Tool.BASKET) > 0.5f && hb.has(Tool.GRINDER) > 0.2f) min(0.2f * grain, BEER_KG * guest.size) else 0f
        if (beer > 0f) {
            host.stores[Store.SEED] -= beer; stats.meetings.merge("feasts with beer", 1, Int::plus)
            land.books?.flow(Books.Stuff.MATTER, "eaten", -(beer * host.storeKgPer[Store.SEED]).toDouble())
        }
        val warmth = 1f + BEER_TIES * min(1f, beer / (BEER_KG * guest.size).coerceAtLeast(1e-3f))
        guest.band?.befriend(host.id, TIE_FEAST * warmth); hb.befriend(guest.id, 0.5f * TIE_FEAST * warmth)
        stats.meetings.merge("feasts", 1, Int::plus)
        note(host) { "feasted band #%d with %.0f kg".format(guest.id, meal) }; note(guest) { "was feasted by band #%d".format(host.id) }
    }
    // Word of where the other did better this month.
    for (m in 0 until 12) {
        if (b.seasonQ[m] > a.seasonQ[m] + 0.1f && b.seasonPlace[m] >= 0) {
            a.seasonPlace[m] = b.seasonPlace[m]
            a.seasonQ[m] = 0.5f * (a.seasonQ[m] + b.seasonQ[m])
        }
        else if (a.seasonQ[m] > b.seasonQ[m] + 0.1f && a.seasonPlace[m] >= 0) {
            b.seasonPlace[m] = a.seasonPlace[m]
            b.seasonQ[m] = 0.5f * (a.seasonQ[m] + b.seasonQ[m])
        }
    }
}

/**
 * How many times two groups that are in the same place for [span] days run into each other, on
 * average: as gas molecules do (Hutchinson & Waser 2007), 2 r v T / A, with r how far the one that
 * finds the other senses it, v how fast they close on each other, T the time both spend about
 * (searching, at the same hours) and A the place's size. A herd stands for several of its kind's
 * usual bands spread over the ground: each can be run into.
 */
internal fun HexGroups.encounters(a: HexGroup, b: HexGroup, p: Place, span: Float): Float {
    val hunting = a.species.carnivore != b.species.carnivore
    // A hunter finds a herd by its own senses (a band as well as its trackers read sign); two hunters, by the keener.
    val r = if (hunting) { val hunter = if (a.species.carnivore) a else b; hunter.species.senseKm * (hunter.band?.edge(Skill.TRACK) ?: 1f) }
        else max(a.species.senseKm, b.species.senseKm)
    val v = sqrt(a.species.dailyTravelKm * a.species.dailyTravelKm + b.species.dailyTravelKm * b.species.dailyTravelKm)
    val hours = Niches.overlap(a.species, b.species)
    // Bands seek each other out at springs and lake shores, where people come together.
    val gather = if (a.band != null && b.band != null) 1f + 2f * (land.drink.nearSpring[p] + land.drink.nearLake[p]).coerceAtMost(1f) else 1f
    // (Hunters wait where the paths funnel the game: at fords, passes and water.)
    val waylaid = if (hunting) 1f + AMBUSH * wear.at(hw.placeHex[p]) else 1f
    // (A hunter finds a herd lying up as well as out feeding, if less often.)
    val one = 2f * r * v * span * SEARCHING * gather * waylaid / land.areaKm2[p] * (if (hunting) 0.5f + 0.5f * hours else hours)
    if (!hunting) return one
    val herd = if (a.species.carnivore) b else a
    val bands = bandsIn(herd)
    // A small herd can lie hidden in cover; a big one cannot.
    val hidden = if (bands < SMALL_HERD_BANDS) HIDE_IN_COVER * Niches.shelter(herd.species, p.i, land) * (1f - bands / SMALL_HERD_BANDS) else 0f
    return one * bands * (1f - hidden)
}

/**
 * Whether hunter [g] senses [other] first and keeps out of its way, as it does a much stronger one
 * of another kind; the keener its senses and the better its shelter, the likelier. (Herds watch for
 * hunters when they meet: see [hunterMeetsHerd].)
 */
internal fun HexGroups.slipsAway(g: HexGroup, other: HexGroup, p: Place): Boolean {
    val s = g.species; val o = other.species
    val wants = when {
        s.carnivore && o.carnivore && s != o -> strength(other) > 2f * strength(g)
        else -> false
    }
    if (!wants) return false
    val first = s.senseKm / (s.senseKm + o.senseKm)
    val chance = AVOID_SKILL * first * (1f - o.stalking * 0.5f) + SHELTER_EFFECT * 0.5f * Niches.shelter(s, p.i, land)
    return rnd.nextFloat() < min(0.9f, chance)
}

/** How open hex [h] is where a group passes: the share of the ground with no cover to hide in. */
internal fun HexGroups.openness(h: Int, band: Int): Float {
    val p = hw.placeOf[band][h].takeIf { it >= 0 } ?: return 0.5f
    return 1f - land.plants.cover(p)
}

/**
 * The food [band] works to lay by this week: while its kit is worn it holds back from storing as far as its way of
 * mending first goes ([Band.mendFirst]: the scaffold's 1 stores nothing, mending from the time the stores would take).
 */
internal fun HexGroups.storeWork(g: HexGroup, band: Band) = if (band.worn()) storeWant(g, band) * (1f - band.mendFirst) else storeWant(g, band)

/**
 * What band [g] works this week to lay by towards the stores it aims to keep (its choice), kg: a share
 * of what it lacks. (A band holding to demand sharing stores less: what it lays by is asked of it.)
 */
internal fun storeWant(g: HexGroup, band: Band) =
    max(0f, band.storeAim * (if (band.holds(Tradition.SHARING)) SHARING_STORE else 1f) * g.dailyNeedKg - g.larder) * STORE_FILL

/**
 * The odds that game of kind [ps] at place [p] sees hunter [hunter] first and keeps out of its way, herd or resident
 * alike: likelier in the open, in clear weather, against a courser than a stalker, when it is [wary] (0..1) after a
 * chase, and against people for game bred to fear them.
 */
internal fun HexGroups.seenFirst(hunter: HexGroup, ps: Species, p: Int, wary: Float, weather: Weather): Float {
    val cover = land.plants.cover(p)
    // Game bred to fear people over the millennia keeps a sharper watch for them.
    val fear = if (hunter.band != null) 0.5f + ps.fearOfPeople else 1f
    return min(0.95f, VIGILANCE * (1f - cover) * (1f - hunter.species.stalking) * (1f + wary) * fear *
        (0.5f + 0.5f * sight(p, weather) / (1f - SIGHT_COVER * cover).coerceAtLeast(0.1f)))
}

/**
 * The odds that one try at a group of [bands] of its kind's usual bands of [ps] ends in a kill, once the hunter has
 * come on it unseen, herd or resident alike ([catchable] the odds against the prey's traits there, [wary] its alarm).
 */
internal fun HexGroups.killChance(hunter: HexGroup, ps: Species, catchable: Float, wary: Float, bands: Float): Float =
    CATCH * SURPRISE * catchable * (1f - 0.5f * wary) * (hunter.band?.huntEdge(ps, tuning) ?: 1f) * bands.pow(-BIG_HERD_EYES)

/** What a kill of [ps] is worth to [hunter], kg: what it can eat of it, gorging included (a band also what it can dry). */
internal fun HexGroups.killKg(hunter: HexGroup, ps: Species) =
    min(ps.massKg * creditShare(hunter.band), (1f + SURPLUS_EAT) * hunter.dailyNeedKg * days * (1f + walkCost(hunter)))

/** Days a kill of [ps] takes [hunter], at [chance] a try: handling, the stalks that failed before it, and eating. */
internal fun killDays(chance: Float, kg: Float, packKg: Float) =
    HANDLING_DAYS + STALK_DAYS / chance.coerceIn(1e-3f, 1f) + kg / (packKg * EATING_RATE)

/**
 * Whether game of [ps], killed at [chance] a try, is worth [hunter]'s chase: a kill must yield at least what the
 * rest of its diet brings for the time (Charnov's rule against [HexGroup.dietRate]), less what the hunters it may
 * cost are worth. Armed prey kill a hunter now and then ([fightBack]) when the hunters get to grips with them
 * (at [reach] a try); a hunter lost is all it would
 * yet have eaten, its share of the food for the life still before it, by the odds it now lives under (the hungry,
 * the cold and the sick risk more, having less to lose). People do not weigh it: by meat alone it would turn them
 * from big game altogether, while what a herd kill brings a band besides meat (its hide, for clothes) is not counted.
 */
internal fun HexGroups.worthChasing(hunter: HexGroup, ps: Species, chance: Float, packKg: Float, reach: Float): Boolean {
    val kg = killKg(hunter, ps)
    val lost = if (hunter.band != null) 0f else fightBack(ps, packKg) * reach / chance.coerceAtLeast(1e-3f)
    val life = hunter.species.dailyIntakeKg * days / max(hunter.deathOdds, hunter.species.backgroundMortality * years)
    return (kg - lost * life) / killDays(chance, kg, packKg) >= hunter.dietRate
}

/**
 * Standing orders, hunter and herd. The herd watches for hunters: in open country it often sees
 * them first and turns aside, ending its week short of that hex. Otherwise there is a chase,
 * likelier to end in a kill if the hunters came on it unseen; a herd that was chased scatters and
 * stops where it is; hunters that killed stop there to eat.
 */
internal fun HexGroups.hunterMeetsHerd(hunter: HexGroup, hk: Int, hi: Int, herd: HexGroup, rk: Int, ri: Int, h: Int, stop: IntArray,
    weather: Weather, met: Int = 1, day: Double = weather.date.absoluteDay.toDouble()) {
    stats.meetings.merge("hunter-herd", 1, Int::plus)
    diag(herd.species, "herd bands met", met.toFloat())
    // (With rounds on, for hex-rounds-check: where herds meet hunters, by how the herd lives and where it was.)
    val roundsKey = if (ROUNDS && herd.route[0] != ROUND_OPEN) roundsWhere(herd, weather) + (if (hunter.band != null) "people|" else "animals|") else null
    roundsKey?.let { stats.hunting.merge(it + "met", 1, Int::plus); stats.hunting.merge(it + "heads met", herd.size, Int::plus) }
    note(hunter) { "came on a herd of %d %s".format(herd.size, herd.species.name) }; note(herd) { "met %d %s".format(hunter.size, hunter.species.name) }
    val p = hw.sameBandPlace(herd.place, h).takeIf { !it.none } ?: herd.place
    // A herd chased lately keeps a sharper watch.
    val wary = herd.wary(day)
    val seen = rnd.nextFloat() < seenFirst(hunter, herd.species, p.i, wary, weather)
    if (seen) {
        diag(herd.species, "herd saw first", met.toFloat())
        stats.meetings.merge("herd turned aside", 1, Int::plus)
        note(herd) { "saw the hunters first and turned aside" }; note(hunter) { "was seen first; the herd turned aside" }
        stats.hunting.merge(Tally.of(hunter.species, Tally.SAW_FIRST), 1, Int::plus)
        roundsKey?.let { stats.hunting.merge(it + "saw them first", 1, Int::plus) }
        stop[rk] = min(stop[rk], max(0, ri - 1))
        return
    }
    // A hunter that has eaten its fill this week lets the herd pass; a band hunts on for the stores it
    // aims to keep, as it does at its own foraging.
    val need = hunter.dailyNeedKg * days * (if (hunter.band == null) hunter.will[AnimalWay.CHASE] else 1f) + (hunter.band?.let { storeWork(hunter, it) } ?: 0f)
    if (hunter.meatMet >= need) { diag(herd.species, "herd hunters fed", met.toFloat()); roundsKey?.let { stats.hunting.merge(it + "hunters fed", 1, Int::plus) }; return }
    // Game it seldom comes on it does not look for, and lets pass (prey switching, see [SEARCH_HALF]).
    val image = hunter.metLately(herd.species.index, day)
    hunter.metHerd(herd.species.index, day)
    if (rnd.nextFloat() >= image / (image + SEARCH_HALF)) {
        diag(herd.species, "herd let pass, rare", met.toFloat())
        stats.hunting.merge("${hunter.species.name}:herds let pass", 1, Int::plus)
        note(hunter) { "let the %s pass: it seldom comes on them".format(herd.species.name) }
        return
    }
    // Quarry beyond what the kind takes (an aurochs herd for wolves) is not tried at all.
    val packKg = max(1, hunter.adults) * hunter.species.massKg
    val catchable = catchable(hunter.species, herd.species, p.i)
    if (catchable < NOT_WORTH_TRYING) { diag(herd.species, "herd beyond reach", met.toFloat()); return }
    // (Many eyes see the hunters sooner, and one animal is hard to single out of a milling crowd.)
    val chance = killChance(hunter, herd.species, catchable, wary, bandsIn(herd))
    // Game worth less for the time a kill takes than the rest of its diet brings is let pass, herd or resident
    // alike (Charnov's rule; see [HexGroup.dietRate]).
    val reach = reach(hunter.species, herd.species, p.i)
    if (!worthChasing(hunter, herd.species, chance, packKg, reach)) {
        diag(herd.species, "herd not worth it", met.toFloat())
        stats.hunting.merge("${hunter.species.name}:herds not worth it", 1, Int::plus)
        note(hunter) { "let the %s pass: not worth the chase".format(herd.species.name) }
        return
    }
    hunter.band?.practise(Skill.HUNT)
    stop[rk] = min(stop[rk], ri) // scattered
    // Caught or not, the herd is alarmed for a while after.
    herd.chasedOn = day
    stats.hunting.merge(Tally.of(hunter.species, Tally.CHASES), 1, Int::plus)
    // A big herd stands for several of its kind's usual bands: the hunters try each one they ran into while hungry
    // (once each: a band that was chased has scattered).
    val tries = max(1, min(met, ceil(bandsIn(herd)).toInt()))
    diag(herd.species, "herd tries", tries.toFloat())
    roundsKey?.let { stats.hunting.merge(it + "chased", 1, Int::plus); stats.hunting.merge(it + "tries", tries, Int::plus)
        stats.hunting.merge(it + "wary", (100 * wary).toInt(), Int::plus) }
    for (attempt in 0 until tries) {
    if (attempt > 0 && hunter.meatMet >= need) break
    stats.hunting.merge(Tally.of(hunter.species, "${Tally.TRIES_ON}${herd.species.name}"), 1, Int::plus)
    // The hunters close in on an animal (at [reach]), and then it falls or it fights them off.
    val closed = rnd.nextFloat() < reach
    if (closed && rnd.nextFloat() < chance / reach && herd.adults > 0) {
        // (The young keep to the middle of a big herd, out of reach.)
        if (herd.young > 0 && hunter.band?.holds(Tradition.TABOO_YOUNG) != true && rnd.nextFloat() < 0.5f * bandsIn(herd).pow(-BIG_HERD_SHELTERS)) {
            herd.young--
            hunter.meatMet += HexFauna.YOUNG_WEIGHT * herd.species.massKg
            bookKill(hunter, HexFauna.YOUNG_WEIGHT * herd.species.massKg, HexFauna.YOUNG_WEIGHT * herd.species.massKg, 0f)
        }
        else {
            herd.adults--
            hunter.meatMet += herd.species.massKg * creditShare(hunter.band)
            hunter.band?.let { it.practise(Skill.TRACK); it.practise(Skill.STALK); it.practise(Skill.BUTCHER) }
            val left = herd.species.massKg * (1f - meatShare(hunter.band))
            small.deposit(DeadPool.CARRION, herd.place, KgM2((left - tallyHide("herds", herd.species, hunter.band?.skin(herd.species, 1, left))) / (land.areaKm2[herd.place] * 1e6f)))
        }
        stats.kills.merge(herd.species.name, 1, Int::plus)
        diag(herd.species, "herd kills", 1f)
        roundsKey?.let { stats.hunting.merge(it + "killed", 1, Int::plus) }
        if (ROUNDS && herd.route[0] != ROUND_OPEN) stats.deaths.merge(
            "${herd.species.name} ${if (herd.route[0] >= 0) "on round" else "own country"}:hunted by ${if (hunter.band != null) "people" else hunter.species.name}",
            1, Int::plus)
        fauna.killed(herd.place.i, Kg(herd.species.massKg))
        stats.hunting.merge(Tally.of(hunter.species, Tally.KILLS_FROM_HERDS), 1, Int::plus)
        stats.hunting.merge(Tally.herdSize(sizeClass(herd), "killed"), 1, Int::plus)
        note(hunter) { "made a kill from the herd" }; note(herd) { "lost one to the hunters" }
        stop[hk] = min(stop[hk], hi) // stays to eat
    }
    // Big, armed prey fight back when the hunters close in: a horn or a kick now and then kills one of them.
    if (closed && hunter.adults > 0 && rnd.nextFloat() < fightBack(herd.species, hunter.adults * hunter.species.massKg)) {
        adultsDied(hunter, 1, rnd)
        stats.deaths.merge(Tally.of(hunter.species, DeathCause.PREY_FOUGHT_BACK), 1, Int::plus)
        stats.hunting.merge(Tally.of(hunter.species, "${Tally.HURT_BY}${herd.species.name}"), 1, Int::plus)
        hunter.band?.wounded(WOUNDED_PER_DEAD, hunter.adults)
        small.deposit(DeadPool.CARRION, hunter.place, KgM2(hunter.species.massKg / (land.areaKm2[hunter.place] * 1e6f)))
    }
    }
}

/**
 * For hex-rounds-check: "rounds|KIND|WAY|GOING|WHERE|" for herd [g] this week, as it set out (before meetings cut
 * its way short). WAY is on round or own country; GOING is moving (its week's way leaves its hex) or still; WHERE,
 * for a herd on a round, is near (it set out within [ROUND_NEAR_KM] of its round's place this month) or far, and "-"
 * for the others.
 */
internal fun HexGroups.roundsWhere(g: HexGroup, weather: Weather): String {
    val onRound = g.route[0] >= 0
    val going = if (g.path.size > 1) "moving" else "still"
    val where = if (!onRound) "-" else roundOf(g, weather.date.month)?.place
        ?.let { r -> if (hex.distanceKm(g.path[0], hw.placeHex[r]) <= ROUND_NEAR_KM) "near" else "far" } ?: "far"
    return "rounds|${g.species.name}|${if (onRound) "on round" else "own country"}|$going|$where|"
}

/**
 * Where herd [g], grazing about hex [h] this week, spends its time, as [into] (hex, share of the time): its
 * usual day's going ([Species.dailyTravelKm]) takes it out round the middle of its ground, over a disc of that
 * radius, of which the hex holds its own area's share (all of it for a herd whose day's going stays within the
 * hex); the rest it spends in the six hexes round, as evenly as it can cross into them. (A disc reaching beyond
 * the first ring is counted in the ring.)
 */
internal fun HexGroups.rangeShares(g: HexGroup, h: Int, into: (Int, Float) -> Unit) {
    val r = g.species.dailyTravelKm
    val home = min(1f, land.areaKm2[g.place] / (Math.PI.toFloat() * r * r))
    val cross = hex.crossings[Catalogue.travel(g.species).ordinal]
    var open = 0
    for (d in 0 until 6) { val n = hex.neighbour(h, d); if (n >= 0 && hex.heart[n] >= 0 && cross[h * 6 + d].isFinite()) open++ }
    if (open == 0 || home >= 1f) { into(h, 1f); return }
    into(h, home)
    for (d in 0 until 6) { val n = hex.neighbour(h, d); if (n >= 0 && hex.heart[n] >= 0 && cross[h * 6 + d].isFinite()) into(n, (1f - home) / open) }
}

/**
 * For hex-rounds-check: for each herd this week, by "rounds|KIND|WAY|GOING|WHERE|" (see [roundsWhere]), the hexes
 * its way crossed ("crossed") and the hunters about where it set out, its own hex and the six round it, in
 * hundredths of a kg per km² ("hunters at start").
 */
private fun HexGroups.roundsStart(weather: Weather) {
    val hunterKg = HashMap<Int, Float>()
    for (g in groups) if (g.alive && g.species.carnivore && g.path.isNotEmpty()) hunterKg.merge(g.path[0], g.size * g.species.massKg, Float::plus)
    for (g in groups) {
        if (!g.alive || g.species.carnivore || g.route[0] == ROUND_OPEN || g.path.isEmpty()) continue
        val c = roundsWhere(g, weather)
        val h0 = g.path[0]
        val hexKm2 = (sqrt(3.0) / 2 * hex.hexKm * hex.hexKm).toFloat()
        var kg = hunterKg[h0] ?: 0f; var km2 = hexKm2
        for (d in 0 until 6) { val n = hex.neighbour(h0, d); if (n >= 0 && hex.heart[n] >= 0) { kg += hunterKg[n] ?: 0f; km2 += hexKm2 } }
        stats.hunting.merge(c + "crossed", g.path.size - 1, Int::plus)
        stats.hunting.merge(c + "hunters at start", (100f * kg / km2).toInt(), Int::plus)
    }
}

/**
 * For hex-rounds-check: where on its week herd [g] met hunters, at its path's [ri] in hex [h]: on its way, where
 * it grazes, or in the hexes round that; and whether on a well-worn path (where hunters wait, see [encounters]).
 */
private fun HexGroups.roundsMeeting(g: HexGroup, ri: Int, h: Int, weather: Weather) {
    if (g.route[0] == ROUND_OPEN) return
    val c = roundsWhere(g, weather)
    stats.hunting.merge(c + (if (ri < g.path.size - 1) "met on the way" else if (h == g.path[ri]) "met where it grazes" else "met round it"), 1, Int::plus)
    if (wear.at(h) >= 0.5f) stats.hunting.merge(c + "met on worn ways", 1, Int::plus)
}

/** How close to its round's place a herd counts as there, km (as hex-rounds-check's "within 50 km"). */
const val ROUND_NEAR_KM = 50.0

/** Chance a chase after [prey] costs a hunting party of [packKg] one of its number: armed prey, and prey big against the party. */
internal fun fightBack(prey: Species, packKg: Float) = FIGHT_BACK * prey.defence * min(2f, prey.massKg / max(1f, packKg) * 2f)

/**
 * Standing orders, hunters of different kinds. The weaker gives way; if it had made a kill this week
 * the stronger drives it off and takes most of the meat (hyenas rob leopards, lions rob everyone);
 * a much stronger party now and then kills one of the other.
 */
internal fun HexGroups.enemiesMeet(a: HexGroup, ak: Int, ai: Int, b: HexGroup, bk: Int, bi: Int, h: Int, stop: IntArray) {
    stats.meetings.merge("hunters of other kinds", 1, Int::plus)
    val sa = strength(a) * (if (a.home.i == h) HOME_ADVANTAGE else 1f)
    val sb = strength(b) * (if (b.home.i == h) HOME_ADVANTAGE else 1f)
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
        stats.meetings.merge("kill stolen", 1, Int::plus)
    }
    // A camp fire, and dogs that bark at night, keep lions and hyenas from taking people.
    val fire = if (weak.species.human) (1f - CAMPFIRE) * (1f - DOG_GUARD * (weak.band?.dogShare ?: 0f)) else 1f
    // A much stronger party now and then kills one of the other: its young most often, an adult seldom.
    val odds = fire * (1f - SHELTER_EFFECT * Niches.shelter(weak.species, weak.place.i, land)) * strong.will[AnimalWay.KILL]
    // (People drive hunters off and now and then kill one, but do not seek out their young.)
    if (ratio > 2f && weak.young > 0 && !strong.species.human && rnd.nextFloat() < INTRAGUILD_KILL_YOUNG * tuning.intraguildYoung * odds) {
        weak.young--
        strong.meatMet += HexFauna.YOUNG_WEIGHT * 0.5f * weak.species.massKg
        small.deposit(DeadPool.CARRION, weak.place, Kg(HexFauna.YOUNG_WEIGHT * 0.5f * weak.species.massKg)) // (what the killers leave)
        bookKill(strong, HexFauna.YOUNG_WEIGHT * 0.5f * weak.species.massKg, HexFauna.YOUNG_WEIGHT * weak.species.massKg, HexFauna.YOUNG_WEIGHT * 0.5f * weak.species.massKg)
        stats.deaths.merge(Tally.of(weak.species, DeathCause.youngKilledBy(strong.species.name)), 1, Int::plus)
    } else if (ratio > 2f && weak.adults > 0 && rnd.nextFloat() < INTRAGUILD_KILL * odds) {
        adultsDied(weak, 1, rnd)
        strong.meatMet += 0.5f * weak.species.massKg
        small.deposit(DeadPool.CARRION, weak.place, Kg(0.5f * weak.species.massKg))
        bookKill(strong, 0.5f * weak.species.massKg, weak.species.massKg, 0.5f * weak.species.massKg)
        stats.deaths.merge(Tally.of(weak.species, DeathCause.killedBy(strong.species.name)), 1, Int::plus)
    }
    stop[wk] = min(stop[wk], max(0, wi - 1)) // the weaker turns back
    if (weak.band != null) stats.meetings.merge("bands turned back by ${strong.species.name}", 1, Int::plus)
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
        if (!g.alive || !s.carnivore || s.human || g.home.none) continue
        val m = scent.marks.getOrPut(s.index) { FloatArray(hex.size) }
        val who = scent.markers.getOrPut(s.index) { IntArray(hex.size) { -1 } }
        for (h in g.path) {
            if (hex.distanceKm(h, g.home.i) > territoryKm(s)) continue
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
internal fun HexGroups.rivalsMeet(a: HexGroup, ak: Int, ai: Int, b: HexGroup, bk: Int, bi: Int, h: Int, stop: IntArray) {
    stats.meetings.merge("rival packs", 1, Int::plus)
    val sa = a.adults * (if (a.home.i == h) HOME_ADVANTAGE else 1f); val sb = b.adults * (if (b.home.i == h) HOME_ADVANTAGE else 1f)
    val ratio = max(sa, sb) / max(1f, min(sa, sb))
    // (How much stronger the other has to be before the weaker gives way is the weaker's choice.)
    if (ratio > FIGHT_RATIO * (if (sa < sb) a else b).will[AnimalWay.FIGHT]) {
        // The weaker gives way: back the way it came.
        if (sa < sb) stop[ak] = min(stop[ak], max(0, ai - 1)) else stop[bk] = min(stop[bk], max(0, bi - 1))
        stats.meetings.merge("pack gave way", 1, Int::plus)
        return
    }
    stats.meetings.merge("packs fought", 1, Int::plus)
    val loser = if (rnd.nextFloat() < sa / (sa + sb)) b else a
    if (rnd.nextFloat() < RIVAL_KILL && loser.adults > 0) {
        adultsDied(loser, 1, rnd)
        small.deposit(DeadPool.CARRION, loser.place, Kg(loser.species.massKg))
        stats.deaths.merge(Tally.of(loser.species, DeathCause.RIVALS), 1, Int::plus)
    }
    if (loser.home.i == h) {
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
    for (g in groups.filter { it.alive && it.species.pack && it.home.none }.sortedBy { tieBreak(it.id, 0, today) }) {
        val h = hw.hexOf(g.place)
        val key = g.species.index.toLong() * hex.size + h.i
        // (Whether it takes it is its choice: always at the hand-written way.)
        val w = g.will[AnimalWay.SETTLE]
        if (w < 1f && rnd.nextFloat() >= w) continue
        if (scent.foreign(g.species, h, g.id) < MARK_FRESH && claimed.add(key)) claimGround(g, h)
    }
}

/** The least time two groups passing through the same hex count as spending there together, days */
