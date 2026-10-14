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
import kotlin.math.pow
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
 * Hunters (and bands) feed along their way: the resident herds of every place they passed, the herds
 * they met (that passed the same hexes this week), small game, carrion, and for those that eat them
 * plants, and for bands fish (see [forage]).
 */
internal fun HexGroups.hunt(visits: HashMap<Int, ArrayList<HexGroup>>, weather: Weather) {
    val ways = this.ways
    val inputs = FloatArray(WAYS_IN); val out = FloatArray(WAYS_OUT)
    val hunters = groups.filter { it.alive && it.species.carnivore }.shuffled(rnd)
    // Bands first set their ways for the week.
    if (ways != null) for (g in hunters) if (g.band != null) setWays(g, weather, ways, inputs, out)
    // Then region by region on all cores: the map is cut into squares of [HUNT_BLOCK_KM] coloured
    // like a chessboard of four colours, so that squares of one colour lie a square apart, further
    // than any party's week takes it; the squares of one colour are worked at once.
    val blocks = HashMap<Int, ArrayList<HexGroup>>()
    for (g in hunters) {
        val (x, y) = hex.centreKm(hw.placeHex[g.place])
        blocks.getOrPut((x / HUNT_BLOCK_KM).toInt() + 1000 * (y / HUNT_BLOCK_KM).toInt()) { ArrayList() } += g
    }
    for (colour in 0 until 4) {
        blocks.entries.filter { (b, _) -> (b % 1000) % 2 + 2 * ((b / 1000) % 2) == colour }.parallelStream().forEach { (_, list) ->
            for (g in list) {
                val s = g.species
                // The ground it searches this week, shared over the hexes it passed: a bigger party fans out
                // over more of it, as the square root of its numbers against its kind's usual party.
                // And it searches less of it when the rain, haze or cover hide game (see [sight]).
                val sweep = sweptKm2(s) * kotlin.math.sqrt(max(1, g.adults).toFloat() / s.groupSize) / g.path.size.coerceAtLeast(1) * sight(g.place)
                Profile.part(if (s.human) "foraging: people" else "foraging: hunters") { forage(g, sweep, max(1, g.adults) * s.massKg, weather) }
            }
        }
    }
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
    val rnd = kotlin.random.Random(g.id * 1_000_003L + weatherNow.date.absoluteDay)
    // (A band's scouts range ahead instead of foraging.)
    val hands = max(1, (g.adults * (1f - (band?.scoutShare ?: 0f))).toInt())
    // (Walking costs food, see [walkCost].)
    val wantAll = g.dailyNeedKg * days * (1f + walkCost(g))
    val eats = 1f / (packKg * EATING_RATE) // days to eat a kilo
    // Small game is found within a few paces of the way, not as far as the party can see; snare
    // lines and nets search it while no one is there.
    val smallSweep = s.dailyTravelKm * days * 2f * SMALL_SENSE_KM * kotlin.math.sqrt(hands.toFloat() / s.groupSize) / g.path.size.coerceAtLeast(1) * (band?.snares() ?: 1f)
    val smallCatch = smallCatch(s)
    val smallDays = SMALL_HANDLING_DAYS_PER_KG / hands * (band?.slowness(Skill.SMALL_GAME) ?: 1f) + eats
    // Plant food: picked, dug or reaped by all hands, and carried home (baskets carry more at a time).
    val eatsPlants = d.fruit + d.roots > 0f
    val carry = 1f + BASKET_GAIN * (band?.has(Tool.BASKET) ?: 0f)
    val pickDays = PLANT_HANDLING_DAYS_PER_KG * Tuning.plantHandling / hands / carry + eats
    val seedDays = SEED_HANDLING_DAYS_PER_KG * Tuning.plantHandling / hands / carry / (1f + SICKLE_GAIN * (band?.has(Tool.SICKLE) ?: 0f) + GRINDER_GAIN * (band?.has(Tool.GRINDER) ?: 0f)) + eats
    // Bands gather over all the ground they walked, the women's and children's day round; animals where they end the week.
    val gatherAt = if (band != null) g.path.toList().mapNotNull { h: Int -> hw.placeOf[hw.placeBand[g.place]][h].takeIf { it >= 0 } }.ifEmpty { listOf(g.place) } else listOf(g.place)
    // The foods on offer: type (0 big game of [kinds] index k, 1 small game, 2 carrion, 3 bones,
    // 4 fruit, 5 roots, 6 grass seed, 7 small finds, 8 fish), place, rate a day, kg each, days each.
    val n = g.path.size * (fauna.kinds.size + 4) + gatherAt.size * 5 + 1
    val type = IntArray(n); val kind = IntArray(n); val place = IntArray(n)
    val rate = FloatArray(n); val kg = FloatArray(n); val time = FloatArray(n)
    var m = 0
    var meat = g.meatMet; var smallGot = 0f; var scraps = 0f; var plants = 0f; var fish = 0f
    // A band also works to fill its stores towards what it aims to keep (its choice); party-days spent this week.
    // (A band holding to demand sharing stores less: what it lays by is asked of it.)
    val aim = wantAll + if (band != null) max(0f, band.storeAim * (if (band.holds(Tradition.SHARING)) SHARING_STORE else 1f) * g.dailyNeedKg - g.larder) * STORE_FILL else 0f
    val plantKind = FloatArray(Store.entries.size)
    var busy = 0f
    val spareYoung = band?.holds(Tradition.TABOO_YOUNG) == true
    // The odds a meeting with each kind ends in a kill, in each place on the way (they do not change within the week).
    val nk = fauna.kinds.size
    val odds = FloatArray(g.path.size * nk)
    for ((j, h) in g.path.withIndex()) {
        val p = hw.placeOf[hw.placeBand[g.place]][h].takeIf { it >= 0 } ?: continue
        for (k in 0 until nk) {
            if (fauna.adults[k][p] + fauna.young[k][p] <= 0f) continue
            val ps = fauna.kinds[k]
            // Prey resting while the hunter is about lie up in cover and are found less; a band's
            // weapons, dogs and hunting skill change the odds that a stalk ends in a kill.
            odds[j * nk + k] = CATCH * catchable(s, ps, p, packKg) * (0.5f + 0.5f * Niches.overlap(s, ps)) * (band?.huntEdge(ps) ?: 1f)
        }
    }
    val order = IntArray(n); val profit = FloatArray(n)
    // The party plans its week on what it finds, eats, and if others got there first and it is still
    // hungry, looks again at what is left.
    for (pass in 0 until FORAGE_PASSES) {
    val left = aim - (meat + smallGot + scraps + plants + fish)
    if (left <= 0.01f * wantAll) break
    m = 0
    fun offer(t: Int, k: Int, p: Int, r: Float, e: Float, h: Float) {
        // A band meets each kind of food the more, the more of its effort it puts into it.
        val rr = if (band != null) r * band.effort[if (t == 9) 6 else t] else r
        if (rr <= 0f || e <= 0f) return
        type[m] = t; kind[m] = k; place[m] = p; rate[m] = rr; kg[m] = e; time[m] = h; m++
    }
    for ((j, h) in g.path.withIndex()) {
        val p = hw.placeOf[hw.placeBand[g.place]][h].takeIf { it >= 0 } ?: continue
        val area = land.areaKm2[p]
        val swept = min(area, sweep)
        for (k in 0 until nk) {
            val ps = fauna.kinds[k]
            val dens = fauna.adults[k][p] + fauna.young[k][p]
            val chance = odds[j * nk + k]
            if (dens <= 0f || chance <= 0f) continue
            // A kill is worth what the party can eat of it, gorging included (a band also what it can
            // dry); the rest is left to others.
            val meat1 = min(ps.massKg * meatShare(band), (1f + FATTEN) * wantAll)
            offer(0, k, p, dens * swept * chance / days, meat1, HANDLING_DAYS + meat1 * eats)
        }
        val small = this.small
        offer(1, 0, p, small.smallPreyKg(p) * min(area, smallSweep) * smallCatch / days, 1f, smallDays)
        if (d.carrion > 0f) {
            // (At most half of what lies on the ground searched: the rest is not found in time, or others get it.)
            offer(2, 0, p, 0.5f * small.carrion[p] * 1e6f * swept / days, 1f, eats)
            if (Niches.cracksBones(s)) offer(3, 0, p, 0.5f * small.bones[p] * 1e6f * swept / days, 1f, eats * BONE_DAYS)
        }
    }
    if (eatsPlants) for (q in gatherAt) {
        val m2 = land.areaKm2[q] * 1e6f
        // A band can strip so much of a place in a week, as its skill allows; an animal finds what lies along its way.
        // (On strange ground people find little of it.)
        val knowHere = band?.let { plantKnow(it, hw.placeHex[q]) } ?: 1f
        val reach = band?.let { it.gatherShare(false) * knowHere } ?: min(1f, smallSweep * g.path.size / land.areaKm2[q])
        // (The unskilled, and strangers to its plants, are slow at it.)
        val slow = band?.slowness(Skill.GATHER, knowHere) ?: 1f
        val pickQ = (pickDays - eats) * slow + eats; val seedQ = (seedDays - eats) * slow + eats
        offer(4, 0, q, reach * fauna.food.fruit[q] * m2 / days, 1f, pickQ)
        offer(5, 0, q, reach * fauna.food.roots[q] * m2 / days, 1f, ROOT_DIGGING * pickQ)
        offer(7, 0, q, reach * fauna.food.finds[q] * m2 / days, 1f, ROOT_DIGGING * pickQ)
        // Grass seed must be reaped, threshed and ground: worth it only with the tools and the hands.
        offer(6, 0, q, (band?.let { it.gatherShare(true) * knowHere } ?: reach) * fauna.food.seeds[q] * m2 / days, 1f, seedQ)
        // Wild and sown cereals: reaped like grass seed, but richer where they stand.
        offer(9, 0, q, (band?.let { it.gatherShare(true) * knowHere } ?: reach) * fauna.food.grain[q] * m2 / days, 1f, seedQ)
    }
    // Bands fish the river, the lake or the sea shore by their camp.
    if (band != null) {
        val h = hw.placeHex[g.place]
        offer(8, 0, h, fauna.water.fishWithin(h, band.fishing()) / days, 1f, FISH_HANDLING_DAYS_PER_KG / hands * band.slowness(Skill.FISH) + eats)
    }
    // Rank by kilos a day of handling, best first.
    for (i in 0 until m) {
        profit[i] = kg[i] / time[i]
        var r = i
        while (r > 0 && profit[order[r - 1]] < profit[i]) { order[r] = order[r - 1]; r-- }
        order[r] = i
    }
    var gain = 0f; var spent = 1f; var used = 0
    for (r in 0 until m) {
        val i = order[r]
        if (profit[i] <= gain / spent) break
        gain += rate[i] * kg[i]; spent += rate[i] * time[i]; used++
    }
    note(g) {
        val names = arrayOf("big game", "small game", "carrion", "bones", "fruit", "roots", "grass seed", "small finds", "fish")
        "pass %d: took up %d of %d foods, best first: ".format(pass + 1, used, m) + (0 until min(used, 6)).joinToString(", ") { r -> val i = order[r]
            (if (type[i] == 0) fauna.kinds[kind[i]].name else names[type[i]]) + " %.1f kg/day of work".format(profit[i]) }
    }
    // What the week brings at that rate, as far as the party wants: it stops when fed.
    val f = if (gain > 0f) min(1f, left / (days * gain / spent)) else 0f
    if (FORAGE_DIAG == s.name && rnd.nextFloat() < 0.002f) System.err.println("forage ${s.name} adults ${g.adults} want ${wantAll} left $left offers $m used $used gain $gain spent $spent f $f: " +
        order.take(min(6, m)).joinToString("; ") { "t${type[it]} k${kind[it]} r=${rate[it]} e=${kg[it]} h=${time[it]}" })
    for (r in 0 until used) {
        val i = order[r]
        val expected = days * rate[i] / spent * f
        val p = place[i]
        if (type[i] != 0) busy += expected * time[i]
        when (type[i]) {
            0 -> {
                val k = kind[i]; val ps = fauna.kinds[k]
                val area = land.areaKm2[p]
                val dens = fauna.adults[k][p] + fauna.young[k][p]
                var kills1 = poisson(expected, rnd)
                kills1 = min(kills1, (dens * area * 0.5f).toInt())
                if (kills1 <= 0) continue
                busy += kills1 * time[i]
                var ny = if (spareYoung) 0 else (kills1 * fauna.young[k][p] / dens).toInt(); val na = kills1 - ny
                // A band at home may keep young it catches, to raise by its camp.
                if (HERDING && band != null && band.atHome && ny > 0) {
                    val kept = binomial(ny, CAPTURE * ps.domestication.average().toFloat(), rnd)
                    if (kept > 0) {
                        if (band.herd.size < fauna.kinds.size) { band.herd = band.herd.copyOf(fauna.kinds.size); band.herdTame = band.herdTame.copyOf(fauna.kinds.size) }
                        band.herd[k] += kept; ny -= kept
                        fauna.young[k][p] = max(0f, fauna.young[k][p] - kept / area)
                        meetings.merge("young animals kept", kept, Int::plus)
                    }
                }
                fauna.young[k][p] = max(0f, fauna.young[k][p] - ny / area); fauna.adults[k][p] = max(0f, fauna.adults[k][p] - na / area)
                val young = min(0.3f * ps.massKg, kg[i])
                meat += na * kg[i] + ny * young
                small.carrion[p] += (na * (ps.massKg - kg[i]) + ny * (0.3f * ps.massKg - young)) / (area * 1e6f)
                band?.let { it.skin(ps, na); it.practise(Skill.HUNT); it.practise(Skill.TRACK); it.practise(Skill.STALK); it.practise(Skill.BUTCHER) }
                kills.merge(ps.name, kills1, Int::plus)
                note(g) { "killed %d %s".format(kills1, ps.name) }
                fauna.killed(p, kills1 * ps.massKg)
                hunting.merge("${s.name}:kills of residents", kills1, Int::plus)
            }
            1 -> smallGot += small.catchSmallIn(p, expected, min(land.areaKm2[p], smallSweep), weather)
            2 -> scraps += small.scavengeIn(p, expected, min(land.areaKm2[p], sweep))
            3 -> scraps += small.crackBonesIn(p, expected, min(land.areaKm2[p], sweep))
            4, 5, 6, 7 -> {
                val stock = when (type[i]) { 4 -> fauna.food.fruit; 5 -> fauna.food.roots; 6 -> fauna.food.seeds; else -> fauna.food.finds }
                val m2 = land.areaKm2[p] * 1e6f
                var took = fauna.food.take(stock, p, expected / m2) * m2
                // Raw seed is poorly digested; ground and baked into bread it feeds fully.
                if (type[i] == 6) took *= seedValue(band)
                if (band != null && took > 0f) {
                    // Earth ovens and gruel make more of it; bands that know how leach bitter plants too.
                    val cook = band.cookValue(type[i])
                    if (cook > 1f) { took *= cook; band.practise(if (type[i] == 5) Skill.EARTH_OVEN else Skill.STONE_BOILING) }
                    if (type[i] != 7) {
                        val extra = took * band.detoxExtra(land.palatable[p], land.water[p] >= 0.05f || land.waterKm[p] < 3f)
                        if (extra > 0f) { took += extra; busy += extra * LEACH_DAYS_PER_KG / hands; band.practise(Skill.LEACHING); hunting.merge("${s.name}:plants leached, kg", extra.toInt(), Int::plus) }
                    }
                }
                plants += took
                plantKind[when (type[i]) { 5 -> Store.ROOTS; 6 -> Store.SEED; else -> Store.FRUIT }.ordinal] += took
            }
            8 -> fish += fauna.water.fishFor(p, expected, band?.fishing() ?: 1f)
            9 -> {
                val m2 = land.areaKm2[p] * 1e6f
                val took = fauna.food.take(fauna.food.grain, p, expected / m2) * m2 * seedValue(band) * (band?.cookValue(9) ?: 1f)
                plants += took; plantKind[Store.SEED.ordinal] += took
                // A sickle takes the ears that hold their grain more than those that shatter at a touch.
                if (band != null && took > 0f) {
                    val d = fauna.food.domestic[p]; val sel = SICKLE_SELECT * band.has(Tool.SICKLE)
                    val harvested = d * (1f + sel) / (1f + sel * d).coerceAtLeast(1e-6f)
                    val have = g.stores[Store.SEED.ordinal]
                    band.seedDomestic = (band.seedDomestic * have + harvested * took) / (have + took).coerceAtLeast(1e-6f)
                    hunting.merge("${s.name}:grain reaped, kg", took.toInt(), Int::plus)
                }
            }
        }
    }
    }
    if (band != null) {
        if (meat > g.meatMet) band.practise(Skill.HUNT)
        if (smallGot > 0f) band.practise(Skill.SMALL_GAME)
        if (plants > 0f) { band.practise(Skill.GATHER); learnPlants(band, hw.placeHex[g.place]) }
        if (fish > 0f) band.practise(Skill.FISH)
        if (band.fire > 0f) band.practise(Skill.ROASTING)
        hunting.merge("${s.name}:fish, kg", fish.toInt(), Int::plus)
    }
    var meatEaten = min(meat, max(0f, wantAll - smallGot - scraps - plants - fish))
    note(g) { "foraged for %.0f kg wanted: meat %.0f (%.0f from herds met), small game %.0f, carrion %.0f, plants %.0f, fish %.0f; busy %.1f of %d days; sight %.2f of a clear day in the open%s".format(wantAll, meat, g.meatMet, smallGot, scraps, plants, fish, busy, days, sight(g.place), if (band != null) "; %.0f%% of adults scouting".format(100 * band.scoutShare) else "") }
    if (FORAGE_DIAG == s.name && rnd.nextFloat() < 0.003f) System.err.println("ate ${s.name} want $wantAll meat $meat small $smallGot scraps $scraps plants $plants fish $fish path ${g.path.size}")
    var eaten = meatEaten + smallGot + scraps + plants + fish
    hunting.merge("${s.name}:diet asked, kg", wantAll.toInt(), Int::plus)
    hunting.merge("${s.name}:diet meat, kg", (meatEaten + fish).toInt(), Int::plus)
    hunting.merge("${s.name}:diet small prey, kg", smallGot.toInt(), Int::plus)
    hunting.merge("${s.name}:diet carrion, kg", scraps.toInt(), Int::plus)
    hunting.merge("${s.name}:diet plants, kg", plants.toInt(), Int::plus)
    var spare = meat - meatEaten
    if (band != null) {
        // This week's food: fresh meat and fish (small game and scraps with it), and plant food by kind.
        val got = plantKind
        got[Store.FRESH.ordinal] = meat + smallGot + scraps + fish
        // Eaten in the order it spoils, this week's first, then from the stores.
        var need = wantAll; var ateMeat = 0f
        for (st in EAT_ORDER) { val e = min(need, got[st.ordinal]); got[st.ordinal] -= e; need -= e; if (st.meat) ateMeat += e }
        for (st in EAT_ORDER) { val e = min(need, g.stores[st.ordinal]); g.stores[st.ordinal] -= e; need -= e; if (st.meat) ateMeat += e }
        eaten = wantAll - need
        hunting.merge("${s.name}:meat eaten, kg", ateMeat.toInt(), Int::plus); hunting.merge("${s.name}:plants eaten, kg", (eaten - ateMeat).toInt(), Int::plus)
        // What is left is kept; spare hands dry fresh meat and fish over the fire, as fast as their skill allows.
        for (i in got.indices) g.stores[i] += got[i]
        val spareDays = max(0f, days - busy)
        // (Racks in the sun dry little in wet weather; smoking over a slow fire works in any.)
        val sun = band.skill(Skill.STORE) / (1f + weather.precipitation[g.place] / DRY_RAIN_MM)
        val smoke = if (band.knowsSkill[Skill.SMOKING.ordinal] && band.fire > 0f) band.skill(Skill.SMOKING) * band.fire else 0f
        val dryRate = max(sun, smoke)
        val dried = min(g.stores[Store.FRESH.ordinal], spareDays * hands * dryRate / DRY_DAYS_PER_KG)
        if (dried > 0f) { g.stores[Store.FRESH.ordinal] -= dried; g.stores[Store.DRIED.ordinal] += dried; band.practise(if (smoke > sun) Skill.SMOKING else Skill.STORE) }
        // What time is left is for making and mending (see [bandWeek]).
        band.spareDays = max(0f, spareDays * hands - if (dried > 0f) dried * DRY_DAYS_PER_KG / dryRate else 0f)
        // And it spoils, each kind at its own rate, fresh meat the faster the warmer.
        // (In storage pits at home stores keep better, though mice get into the grain, nuts and roots.)
        val home = if (band.atHome) band.house else 0f
        // (Smoked meat keeps longer than sun-dried; pits sealed with clay and plaster keep the mice out.)
        val smoked = if (band.knowsSkill[Skill.SMOKING.ordinal]) 1f - SMOKE_KEEP * band.skill(Skill.SMOKING) else 1f
        var rotten = spoil(g.stores, weather.temperature[g.place], 1f - PIT_KEEP * home, smoked)
        val sealed = if (band.knowsSkill[Skill.SEALING.ordinal]) 1f - SEAL_KEEP * band.skill(Skill.SEALING) else 1f
        if (home > 0f) {
            for (st in listOf(Store.SEED, Store.FRUIT, Store.ROOTS)) { val x = g.stores[st.ordinal] * MICE * sealed * home * days / 7f; g.stores[st.ordinal] -= x; rotten += x }
            if (band.knowsSkill[Skill.SEALING.ordinal] && g.larder > 0f) band.practise(Skill.SEALING, 0.2f * PRACTICE)
        }
        small.carrion[g.place] += rotten / (land.areaKm2[g.place] * 1e6f)
        spare = 0f; meatEaten = ateMeat
    }
    // Gorging: what is left of a kill is eaten beyond need, as far as the gut holds and reserves are short.
    if (spare > 0f && g.fat < s.maxFatDays && band == null) {
        val gorge = min(spare, FATTEN * wantAll)
        g.surplus = gorge / g.dailyNeedKg.coerceAtLeast(1e-3f)
        spare -= gorge
        small.dung[g.place] += HexSmallLife.DUNG * gorge / (land.areaKm2[g.place] * 1e6f)
    }
    g.fed = (eaten / wantAll).coerceIn(0f, 1f)
    // What the pack could not finish is left for the scavengers; what it ate comes back in part as dung.
    small.carrion[g.place] += spare / (land.areaKm2[g.place] * 1e6f)
    small.dung[g.place] += HexSmallLife.DUNG * (meatEaten + smallGot + scraps + fish) / (land.areaKm2[g.place] * 1e6f)
}

private val FORAGE_DIAG = System.getProperty("hex.forageDiag")

/** Spoils a week of [stores] at [t] °C, each kind at its own rate; returns kg lost. */
internal fun HexGroups.spoil(stores: FloatArray, t: Float, keep: Float = 1f, driedKeep: Float = 1f): Float {
    var lost = 0f
    val warm = if (t <= 0f) 0f else min(2.8f, exp(0.069f * (t - 15f)))
    for (st in Store.entries) {
        val share = if (st == Store.FRESH) 1f - exp(-HexSmallLife.CARRION_ROT * warm * days) else min(1f, st.lostWeekly * keep * (if (st == Store.DRIED) driedKeep else 1f) * days / 7f)
        val x = stores[st.ordinal] * share
        stores[st.ordinal] -= x; lost += x
    }
    return lost
}

/** Share of a carcass a hunter takes as food: a band as its butchers' skill allows (all the fat and marrow, at best). */
internal fun meatShare(band: Band?) = if (band == null) MEAT_SHARE else min(MEAT_MOST, MEAT_SHARE * band.edge(Skill.BUTCHER))
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
    // Pairs with a hunter or band in them (herds pass each other by), hex by hex on all cores.
    val events = byHex.values.parallelStream().filter { it.size >= 2 }.flatMap { stays ->
        val out = ArrayList<Meeting>()
        for (i in stays.indices) { if (!groups[stays[i].k].species.carnivore) continue; for (j in stays.indices) {
            // Each hunter with every herd, and each pair of hunters once.
            if (j == i || (j < i && groups[stays[j].k].species.carnivore)) continue
            val sa = stays[i]; val sb = stays[j]
            val from = max(sa.from, sb.from); val to = min(sa.to, sb.to)
            if (from > to) continue
            out += Meeting(from, to - from, sa.k, sa.index, sb.k, sb.index)
        } }
        out.stream()
    }.collect(java.util.stream.Collectors.toCollection { ArrayList<Meeting>() })
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
            if (a.species.human && b.species.human) Profile.part("meetings: bands (culture, gifts, feasts, raids)") { bandsMeet(a, b) }
            else if (a.species == b.species) Profile.part("meetings: rival packs") { rivalsMeet(a, e.a, e.ai, b, e.b, e.bi, h, stop) } else Profile.part("meetings: hunters of other kinds") { enemiesMeet(a, e.a, e.ai, b, e.b, e.bi, h, stop) }
        }
        else {
            val (hunter, hi, herd, ri) = if (a.species.carnivore) listOf(e.a, e.ai, e.b, e.bi) else listOf(e.b, e.bi, e.a, e.ai)
            Profile.part("meetings: hunters and herds") { hunterMeetsHerd(groups[hunter], hunter, hi, groups[herd], herd, ri, h, stop) }
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
    note(a) { "met band #%d (%d people; tie %.2f)".format(b.id, b.size, a.band?.tie(b.id) ?: 0f) }
    note(b) { "met band #%d (%d people; tie %.2f)".format(a.id, a.size, b.band?.tie(a.id) ?: 0f) }
    if (cultureMeets(a, b)) return
    val (poor, rich) = if (a.fed + a.larder / (a.dailyNeedKg * 30f) < b.fed + b.larder / (b.dailyNeedKg * 30f)) a to b else b to a
    // Bands that hold to sharing give more, and sooner.
    val sharing = rich.band?.holds(Tradition.SHARING) == true
    // (More to a band it is close to.)
    val close = (rich.band?.tie(poor.id) ?: 0f).coerceAtLeast(0f)
    if (poor.fed < (if (sharing) 0.9f else 0.8f) && rich.larder > 0f) {
        val gift = BAND_GIFT * (if (sharing) 2f else 1f) * (1f + close) * rich.larder
        rich.larder -= gift; poor.larder += gift
        poor.band?.befriend(rich.id, TIE_GIFT); rich.band?.befriend(poor.id, 0.5f * TIE_GIFT)
        meetings.merge("food given", 1, Int::plus)
        note(rich) { "gave %.0f kg of food to hungry band #%d".format(gift, poor.id) }; note(poor) { "was given %.0f kg of food by band #%d".format(gift, rich.id) }
    }
    // Gifts: bands close to each other give each other ornaments, which travel on along the ties.
    for ((giver, taker) in listOf(a to b, b to a)) {
        val gb = giver.band ?: continue; val tb = taker.band ?: continue
        if (gb.tie(taker.id) < 0.3f || gb.ornaments < 1f) continue
        val gift = ORNAMENT_GIFT * gb.ornaments
        val exoticShare = gb.exotic / gb.ornaments
        gb.ornaments -= gift; gb.exotic -= gift * exoticShare
        tb.ornaments += gift; tb.exotic += gift * exoticShare
        tb.befriend(giver.id, 0.5f * TIE_GIFT)
        meetings.merge("ornaments given", 1, Int::plus)
    }
    // Feasts: a band that holds to feasting and has more laid by than it means to keep sets food
    // before the other; the guests remember it.
    for ((host, guest) in listOf(a to b, b to a)) {
        val hb = host.band ?: continue
        if (!hb.holds(Tradition.FEASTS)) continue
        val spare = host.larder - hb.storeAim * host.dailyNeedKg
        if (spare <= 0f) continue
        val meal = min(spare, FEAST_KG * guest.size)
        host.larder -= meal; guest.larder += meal
        // With grain to spare and vessels to brew in, the host pours beer: the feast is remembered better.
        val grain = host.stores[Store.SEED.ordinal]
        val beer = if (hb.has(Tool.BASKET) > 0.5f && hb.has(Tool.GRINDER) > 0.2f) min(0.2f * grain, BEER_KG * guest.size) else 0f
        if (beer > 0f) { host.stores[Store.SEED.ordinal] -= beer; meetings.merge("feasts with beer", 1, Int::plus) }
        val warmth = 1f + BEER_TIES * min(1f, beer / (BEER_KG * guest.size).coerceAtLeast(1e-3f))
        guest.band?.befriend(host.id, TIE_FEAST * warmth); hb.befriend(guest.id, 0.5f * TIE_FEAST * warmth)
        meetings.merge("feasts", 1, Int::plus)
        note(host) { "feasted band #%d with %.0f kg".format(guest.id, meal) }; note(guest) { "was feasted by band #%d".format(host.id) }
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
    val track = if (hunting) TRACKING * ((if (a.species.carnivore) a.band else b.band)?.edge(Skill.TRACK) ?: 1f) else 1f
    val searched = (sweptKm2(a.species) + sweptKm2(b.species)) * track * max(span, 0.5f) / days
    val hours = Niches.overlap(a.species, b.species)
    // Bands seek each other out at springs and lake shores, where people come together.
    val gather = if (a.band != null && b.band != null) 1f + 2f * (land.nearSpring[p] + land.nearLake[p]).coerceAtMost(1f) else 1f
    val one = min(1f, searched * gather / land.areaKm2[p]) * (if (hunting) 0.5f + 0.5f * hours else hours)
    if (!hunting) return one
    // A herd agent stands for several of its kind's usual bands spread over the ground: a hunter finds
    // one of them as often as it would find any of so many herds (the bundling saves reckoning, and
    // must not hide the herds from the hunters).
    val herd = if (a.species.carnivore) b else a
    val bands = max(1f, herd.size.toFloat() / herd.species.groupSize)
    return 1f - (1f - one).pow(bands)
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
    note(hunter) { "came on a herd of %d %s".format(herd.size, herd.species.name) }; note(herd) { "met %d %s".format(hunter.size, hunter.species.name) }
    val open = openness(h, hw.placeBand[herd.place])
    // A herd hunted lately keeps a sharper watch.
    // Game bred to fear people over the millennia keeps a sharper watch for them.
    val fear = if (hunter.band != null) 0.5f + herd.species.fearOfPeople else 1f
    val seen = rnd.nextFloat() < min(0.95f, VIGILANCE * open * (1f - hunter.species.stalking) * (1f + herd.wary) * fear * (0.5f + 0.5f * sight(herd.place) / (1f - SIGHT_COVER * land.cover(herd.place)).coerceAtLeast(0.1f)))
    if (seen) {
        meetings.merge("herd turned aside", 1, Int::plus)
        note(herd) { "saw the hunters first and turned aside" }; note(hunter) { "was seen first; the herd turned aside" }
        hunting.merge("${hunter.species.name}:herds that saw it first", 1, Int::plus)
        stop[rk] = min(stop[rk], max(0, ri - 1))
        return
    }
    // A hunter that has eaten its fill this week lets the herd pass.
    val need = hunter.dailyNeedKg * days
    if (hunter.meatMet >= need) return
    val p = hw.placeOf[hw.placeBand[herd.place]][h].takeIf { it >= 0 } ?: herd.place
    // Quarry far beyond the party (an aurochs herd for a pair of jackals) is not tried at all.
    val catchable = catchable(hunter.species, herd.species, p, max(1, hunter.adults) * hunter.species.massKg)
    if (catchable < NOT_WORTH_TRYING) return
    val chance = CATCH * SURPRISE * catchable * (1f - 0.5f * herd.wary) * (hunter.band?.huntEdge(herd.species) ?: 1f)
    hunter.band?.practise(Skill.HUNT)
    // By a kite, a band drives the herd between the walls and takes a large share of it at once.
    if (hunter.band != null && kites[p] >= 1f && herd.species in KITE_QUARRY) {
        val spare = hunter.band?.holds(Tradition.TABOO_YOUNG) == true
        val ta = binomial(herd.adults, KITE_TAKE); val ty = if (spare) 0 else binomial(herd.young, KITE_TAKE)
        herd.adults -= ta; herd.young -= ty
        val meat = ta * herd.species.massKg * meatShare(hunter.band) + ty * 0.3f * herd.species.massKg
        hunter.meatMet += meat
        hunter.band?.skin(herd.species, ta)
        small.carrion[herd.place] += ta * herd.species.massKg * (1f - meatShare(hunter.band)) / (land.areaKm2[herd.place] * 1e6f)
        kills.merge(herd.species.name, ta + ty, Int::plus)
        fauna.killed(herd.place, (ta + ty) * herd.species.massKg)
        hunting.merge("${hunter.species.name}:kills in kite drives", ta + ty, Int::plus)
        stop[hk] = min(stop[hk], hi); stop[rk] = min(stop[rk], ri)
        herd.wary += WARY_GAIN * (1f - herd.wary)
        return
    }
    stop[rk] = min(stop[rk], ri) // scattered
    // Caught or not, the herd is wary for weeks after.
    herd.wary += WARY_GAIN * (1f - herd.wary)
    hunting.merge("${hunter.species.name}:chases", 1, Int::plus)
    // A big herd stands for several of its kind's usual bands: the hunters may try again while hungry.
    val tries = max(1, herd.size / herd.species.groupSize)
    for (attempt in 0 until tries) {
    if (attempt > 0 && hunter.meatMet >= need) break
    if (rnd.nextFloat() < chance && herd.adults > 0) {
        if (herd.young > 0 && hunter.band?.holds(Tradition.TABOO_YOUNG) != true && rnd.nextFloat() < 0.5f) { herd.young--; hunter.meatMet += 0.3f * herd.species.massKg }
        else {
            herd.adults--; hunter.meatMet += herd.species.massKg * meatShare(hunter.band); hunter.band?.let { it.practise(Skill.TRACK); it.practise(Skill.STALK); it.practise(Skill.BUTCHER) }
            hunter.band?.skin(herd.species, 1)
            small.carrion[herd.place] += herd.species.massKg * (1f - meatShare(hunter.band)) / (land.areaKm2[herd.place] * 1e6f)
        }
        kills.merge(herd.species.name, 1, Int::plus)
        fauna.killed(herd.place, herd.species.massKg)
        hunting.merge("${hunter.species.name}:kills from herds", 1, Int::plus)
        hunting.merge("herd size ${sizeClass(herd)}:killed", 1, Int::plus)
        note(hunter) { "made a kill from the herd" }; note(herd) { "lost one to the hunters" }
        stop[hk] = min(stop[hk], hi) // stays to eat
    }
    // Big, armed prey fight back: a horn or a kick now and then kills one of the hunters.
    if (hunter.adults > 0 && rnd.nextFloat() < fightBack(herd.species, hunter.adults * hunter.species.massKg)) {
        hunter.adults--
        deaths.merge("${hunter.species.name}:prey fought back", 1, Int::plus)
        small.carrion[hunter.place] += hunter.species.massKg / (land.areaKm2[hunter.place] * 1e6f)
    }
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
    // (People drive hunters off and now and then kill one, but do not seek out their young.)
    if (ratio > 2f && weak.young > 0 && !strong.species.human && rnd.nextFloat() < INTRAGUILD_KILL_YOUNG * Tuning.intraguildYoung * odds) {
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
        // (Prides and packs that take over ground do this; solitary hunters seldom.)
        if (loser.young > 0 && loser.species.groupSize >= 3) {
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
