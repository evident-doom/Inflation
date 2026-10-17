package inflation.ecology

import inflation.wrapMonth
import inflation.monthsFrom
import inflation.world.Date
import inflation.world.HexLand
import inflation.world.HexWorld
import inflation.world.LifeForm
import inflation.world.Weather
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.min
import kotlin.random.Random
import inflation.world.Biome
import java.util.concurrent.atomic.DoubleAdder
import java.util.stream.IntStream
import kotlin.math.abs

// Grazing, births and deaths, splitting, and what herds remember of the seasons.

/**
 * What a herd's forebears knew: for each month, the place within a few weeks' trek where its kind
 * does best then: warm enough and not too hot for its coat, growing (warmth and rain against
 * evaporation), and free of lying snow; nearer ground preferred when much the same. This is the
 * template a herd starts from; it then remembers where it actually fared better.
 */
internal fun HexGroups.inheritSeasons(g: HexGroup) {
    val s = g.species
    val here = hw.placeHex[g.place]
    val (hx, hy) = hex.centreKm(here)
    val reachKm = SEASON_RANGE_KM
    val r = (reachKm / hex.hexKm).toInt() + 1
    val near = ArrayList<Int>()
    for (p in 0 until n) {
        val (cx, cy) = hex.centreKm(hw.placeHex[p])
        if (abs(cx - hx) <= reachKm && abs(cy - hy) <= reachKm && hypot(cx - hx, cy - hy) <= reachKm) near += p
    }
    if (near.isEmpty()) return
    var top = 0f
    for (m in 0 until 12) {
        var best = -1; var bestScore = 0f
        for (p in near) {
            val t = hw.placeTemp(p, m)
            val h = hw.placeHex[p]
            val grow = ((t - 2f) / 13f).coerceIn(0f, 1f) * (hw.precip[m][h] / hw.pet[m][h].coerceAtLeast(1f)).coerceIn(0.1f, 1f)
            val discomfort = s.discomfortC(t, m) / 10f
            val snow = if (t < -1f && s.diet.grass > 0.3f) 0.2f else 1f
            val (cx, cy) = hex.centreKm(h)
            val dist = hypot(cx - hx, cy - hy) / reachKm
            // ... and how much forage grows there at all: a desert flush is little food.
            val food = foodFor(s, p)
            val score = (0.2f + grow) * food / (food + 0.3f) * fauna.suits(s, p) * snow / (1f + discomfort * discomfort) /
                (1f + SEASON_DISTANCE * dist.toFloat())
            if (score > bestScore) { bestScore = score; best = p }
        }
        g.seasonPlace[m] = best; g.seasonQ[m] = bestScore
        top = max(top, bestScore)
    }
    // What the forebears knew is trusted until the herd learns better (see [remember]).
    if (top > 0f) for (m in 0 until 12) g.seasonQ[m] = 1f
}

/**
 * Herds eat in the place they end up in, after the resident herds have had theirs: grass, browse,
 * fruit and roots as their diet goes and as far as they can get at it, then straw for a shortfall.
 */
internal fun HexGroups.graze(weather: Weather) {
    val food = fauna.food
    // Where food is short the bigger kinds drive the smaller off it: the herds graze in turn by body mass,
    // the biggest first, as the residents do (see [HexFauna.graze]); within a kind, as they stand.
    val count = IntArray(Catalogue.all.size)
    for (g in groups) count[g.species.index]++
    val start = IntArray(Catalogue.all.size)
    var at = 0
    for (i in Catalogue.all.indices.sortedByDescending { Catalogue.all[it].massKg }) { start[i] = at; at += count[i] }
    val ordered = arrayOfNulls<HexGroup>(groups.size)
    for (g in groups) ordered[start[g.species.index]++] = g
    for (g in ordered) {
        g!!
        val s = g.species
        if (!g.alive || s.carnivore) continue
        // A herd grazes over its range: as many hexes round where it stands as it takes to find a good
        // many weeks' food, each giving its share by what it has (a herd of hundreds spreads thin over
        // poor steppe, and keeps close on rich pasture).
        val range = grazingRange(g)
        val wantKg = g.dailyNeedKg * days * (1f + walkCost(g))
        var gotKg = 0f; var surplusKg = 0f; var eatenKg = 0f; var proteinKg = 0f
        g.surplus = 0f
        for ((p, share) in range) {
            // (Walking costs food: a share of a day's need for each km of the week's going, see [walkCost].)
            val want = wantKg * share / 1e6f / land.areaKm2[p] // kg per m² of the place
            // (Far from water, a kind that must drink often uses only the ground within reach of it, as the residents do: see [HexFauna.tether].)
            // (Biting insects harassing it cost it feeding time.)
            val access = HexFood.access(s, land.crag[p]) * fauna.tether(s, p) * (1f - HexSmallLife.HARASS_FEED * small.harassment(p))
            val snowed = weather.snowpack[p] > s.snowLimitMm
            // (Its diet as it leans this week: its kind's, weighted by its own choice.)
            val d = s.diet.let { k -> Diet(grass = k.grass * g.will[AnimalWay.GRASS], browse = k.browse * g.will[AnimalWay.BROWSE],
                fruit = k.fruit * g.will[AnimalWay.FRUIT], roots = k.roots * g.will[AnimalWay.FRUIT]) }
            val total = (d.grass + d.browse + d.fruit + d.roots).coerceAtLeast(1e-6f)
            // Horses and asses make up for poor forage by eating more of it; ruminants eat less of it.
            val fill = gutFill(s, land.nourishment(p))
            val wantGrass = if (snowed) 0f else want * fill * access * d.grass / total
            // (The week's grazing and browsing at a place is shared by all that eat there: what the residents
            // and the herds before it left, see [HexFauna.grazeLeft].)
            fun eatGrass(kg: Float): Float { val t = land.consume(p, LifeForm.HERB, min(kg, fauna.grazeLeft[p])); fauna.grazeLeft[p] -= t; return t }
            fun eatBrowse(kg: Float): Float { val t = land.eatBrowse(p, min(kg, fauna.browseLeft[p])); fauna.browseLeft[p] -= t; return t }
            val grass = eatGrass(wantGrass)
            val wantBrowse = want * fill * access * d.browse / total
            val browse = eatBrowse(wantBrowse)
            val fruit = food.take(food.fruit, p, want * access * d.fruit / total)
            val roots = food.take(food.roots, p, want * access * d.roots / total)
            // Diet switching: a food that fell short is made up from the others: grass and browse as far as the
            // gut holds (a wild ass short of grass eats shrubs), fruit and roots to at most (1 + SWITCH) times their
            // usual share (an ostrich out of fruit season picks more shoots and leaves).
            val q = land.nourishment(p)
            var gap = want - ((grass + browse) * q + fruit + roots)
            var grass2 = 0f; var browse2 = 0f; var fruit2 = 0f; var roots2 = 0f
            // But the gut holds only so much bulk a day: on poor forage a ruminant cannot make up by eating
            // more of it (switching shifts what it eats, not how much its gut takes).
            // (Its need is reckoned in forage of middling quality, [HexFauna.GUT_QREF]: on that it fills its need exactly.)
            var room = max(0f, want * fill * access / HexFauna.GUT_QREF - (grass + browse))
            // (What it switches to nourishes it as well as its gut digests fibre, [Species.fibreDigestion].)
            val qf = q * s.fibreDigestion
            if (gap > 0f && d.browse > 0f) { browse2 = eatBrowse(min(gap / qf, room)); gap -= browse2 * qf; room -= browse2 }
            if (gap > 0f && d.grass > 0f && !snowed) { grass2 = eatGrass(min(gap / qf, room)); gap -= grass2 * qf; room -= grass2 }
            if (gap > 0f && d.fruit > 0f) { fruit2 = food.take(food.fruit, p, min(gap, SWITCH * want * access * d.fruit / total)); gap -= fruit2 }
            if (gap > 0f && d.roots > 0f) { roots2 = food.take(food.roots, p, min(gap, SWITCH * want * access * d.roots / total)); gap -= roots2 }
            // What grass and browse could not give, straw makes up as far as there is any, at what its gut gets out of it.
            val short = max(0f, gap)
            val straw = food.take(food.straw, p, min(short * HexFauna.GUT_FILL * access, max(0f, room)))
            small.dung[p] += HexSmallLife.DUNG * (grass + browse + fruit + roots + straw + grass2 + browse2 + fruit2 + roots2)
            // On the way it beat down the plants and dropped the seed of the fruit it ate.
            val band = hw.placeBand[p]
            if (p == g.place) for (h in g.path) {
                val q = hw.placeOf[band][h].takeIf { it >= 0 } ?: continue
                land.trample(q, g.size * s.massKg / 1000f)
                if (fruit > 0f) land.animalSeed[q] =
                    min(1f, land.animalSeed[q] + fruit * land.areaKm2[p] * 1e6f / g.path.size / land.areaKm2[q] / HexFauna.SEED_KG)
            }
            // Grass and browse nourish fully only when green; fruit and roots keep their value.
            val got = (grass + browse + grass2 + browse2) * q + fruit + roots + fruit2 + roots2 + min(short, straw * HexFood.roughage(s))
            gotKg += got * land.areaKm2[p] * 1e6f
            // (And the protein in it: fresh growth full, cured grass and straw little; see [HexFauna.proteinMet].)
            val m2p = land.m2(p)
            eatenKg += (grass + grass2 + browse + browse2 + fruit + fruit2 + roots + roots2 + straw) * m2p
            proteinKg += ((grass + grass2) * fauna.grassProtein(p) + (browse + browse2) * fauna.browseProtein(p) +
                (fruit + fruit2 + roots + roots2) * HexFauna.PROTEIN_FRUIT +
                straw * HexFauna.PROTEIN_STRAW) * m2p
            if (p == g.place) note(g) {
                "grazed at %.0f m: fed %.2f (green %.2f, grass %.3f browse %.3f kg/m² there; ate grass %.2g browse %.2g straw %.2g; gut room left %.2g; snowed %s; grazing over %d places)".format(
                    hw.placeElevationM[p], got / want.coerceAtLeast(1e-9f), land.green[p], land.herb[p], land.shrub[p], grass + grass2, browse + browse2, straw,
                    room, snowed, range.size)
            }
            // With food to spare and reserves short, it eats more and lays the surplus on.
            var grass3 = 0f; var browse3 = 0f
            if (got >= want && g.fat < s.maxFatDays) {
                val extra = SURPLUS_EAT * g.will[AnimalWay.FATTEN] * want
                if (d.grass > 0f && !snowed) grass3 = eatGrass(extra / q * d.grass / (d.grass + d.browse).coerceAtLeast(1e-6f))
                if (d.browse > 0f) browse3 = eatBrowse(extra / q * d.browse / (d.grass + d.browse).coerceAtLeast(1e-6f))
                surplusKg += (grass3 + browse3) * q * land.areaKm2[p] * 1e6f
                small.dung[p] += HexSmallLife.DUNG * (grass3 + browse3)
            }
            land.grazing[p] += (grass + grass2 + grass3) * 1e6f / days
            land.browsing[p] += (browse + browse2 + browse3) * 1e6f / days
            land.rooting[p] += (roots + roots2) * 1e6f / days
        val m2 = land.m2(p)
        stats.hunting.merge("${s.name}:ate grass, kg", ((grass + grass2 + grass3) * m2).toInt(), Int::plus)
        stats.hunting.merge("${s.name}:ate browse, kg", ((browse + browse2 + browse3) * m2).toInt(), Int::plus)
        stats.hunting.merge("${s.name}:ate fruit and roots, kg", ((fruit + fruit2 + roots + roots2) * m2).toInt(), Int::plus)
        stats.hunting.merge("${s.name}:ate straw, kg", (straw * m2).toInt(), Int::plus)
        }
        g.fed = (gotKg / wantKg.coerceAtLeast(1e-6f)).coerceIn(0f, 1f)
        if (eatenKg > 0f) g.protein += (fauna.proteinMet(proteinKg / eatenKg) - g.protein) * HexFauna.PROTEIN_MEMORY
        val pm = weather.date.month; g.proteinByMonth[pm] += (g.protein - g.proteinByMonth[pm]) * HexFauna.PROTEIN_MEMORY
        if (surplusKg > 0f) g.surplus = surplusKg / (wantKg / days)
        stats.hunting.merge("${s.name}:ranged over places", range.size, Int::plus)
    }
}

/**
 * The places a herd grazes this week and each one's share of its need: its own and the hexes round
 * it at its height, ring by ring, until the edible forage in reach is [RANGE_WEEKS] weeks' need (at
 * most [RANGE_RINGS] rings), shared by the forage each has.
 */
internal fun HexGroups.grazingRange(g: HexGroup): List<Pair<Int, Float>> {
    val p0 = g.place; val band = hw.placeBand[p0]; val h0 = hw.placeHex[p0]
    val needKg = g.dailyNeedKg * days * RANGE_WEEKS
    fun forage(p: Int) = land.palatable[p] * (land.herb[p] + land.shrub[p]) * land.areaKm2[p] * 1e6f
    val places = arrayListOf(p0); var have = forage(p0)
    var ring = listOf(h0); val seen = hashSetOf(h0)
    var r = 0
    while (have < needKg && r < RANGE_RINGS) {
        val next = hex.nextRing(ring, seen)
        for (h in next) { val q = hw.placeOf[band][h]; if (q >= 0) { places += q; have += forage(q) } }
        ring = next; r++
    }
    if (places.size == 1) return listOf(p0 to 1f)
    val total = have.coerceAtLeast(1e-6f)
    return places.map { it to forage(it) / total }
}
/** A herd's range: weeks of its need in edible forage it looks to have within reach; most rings of hexes round it it spreads over. */
const val RANGE_WEEKS = 20f
const val RANGE_RINGS = 3

internal fun HexGroups.demography(weather: Weather, date: Date) {
    // Bands keep or move camp, gather materials and make their tools.
    followersByHex = groups.filter { it.alive && it.species.followsPeople && !it.species.human }.groupBy { hw.placeHex[it.place] }
    houseOf = groups.filter { g -> g.alive && g.band.let { it != null && it.houseHex >= 0 && it.house >= Thresholds.HOUSE_STANDING } }
        .associate { it.id to it.band!!.houseHex }
    Profile.part("people: camps, tools, culture") {
        for (g in groups) if (g.alive && g.band != null) {
            bandWeek(g, weather, date)
            cultureWeek(g)
            riteWeek(g, weather)
        }
    }
    // Hunters remember where they hunted, and a pack long hungry on its ground gives it up.
    for (g in groups) {
        if (!g.alive || !g.species.carnivore) continue
        val h = hw.placeHex[g.place]
        if (g.recent[0] != h) { for (i in g.recent.size - 1 downTo 1) g.recent[i] = g.recent[i - 1]; g.recent[0] = h }
        g.lean = if (g.fed < Thresholds.HUNGRY) g.lean + 1 else 0
        // A pack long hungry keeps its ground but ranges wider over it (see [HexGroups.plan]).
    }
    val kinNow = HashMap<Long, Int>()
    for (g in groups) if (g.alive) kinNow.merge(g.species.index.toLong() * hex.size + hw.placeHex[g.place], g.size, Int::plus)
    // Where each kind's groups are, for outbreaks to pass between them.
    for (a in fauna.groupHeads) a.fill(0)
    for (g in groups) if (g.alive) fauna.groupHeads[g.species.index][hw.placeHex[g.place]] += g.size
    // Each group on its own core, with its own random stream; what it leaves for others (carcasses,
    // the counts, a split-off herd) is gathered and handed on after, in the groups' order.
    val list = groups.toList()
    val outcome = arrayOfNulls<Outcome>(list.size)
    IntStream.range(0, list.size).parallel().forEach { k ->
        val g = list[k]
        if (g.alive) outcome[k] = Profile.part("groups: life and death of each") { liveWeek(g, weather, date, kinNow, groupRandom(g.id, date.absoluteDay)) }
    }
    // Outbreaks that broke out this week rage from next week: started after every group has lived its
    // week, so no group's plague depends on which core another group's ran on.
    for (key in outbreaksStarting.sorted()) fauna.startOutbreak(Catalogue.all[(key / hex.size).toInt()], (key % hex.size).toInt())
    outbreaksStarting.clear()
    val born = ArrayList<HexGroup>()
    for ((k, o) in outcome.withIndex()) {
        val g = list[k]; val s = g.species
        if (o == null) {
            // Young whose last grown animal was killed this week (by hunters or rivals) die too.
            if (!g.alive && g.young > 0) {
                stats.deaths.merge("${s.name}:${DeathCause.ORPHANED}", g.young, Int::plus)
                small.carrion[g.place] += 0.3f * g.young * s.massKg / (land.areaKm2[g.place] * 1e6f)
                g.young = 0
            }
            continue
        }
        if (o.carrionKg > 0f) small.carrion[g.place] += o.carrionKg / (land.areaKm2[g.place] * 1e6f)
        if (o.births > 0) stats.births.merge(s.name, o.births, Int::plus)
        note(g) { "born %d; died: %s".format(o.births, o.causes.filter { it.second > 0 }.joinToString(", ") { "${it.second} ${it.first}" }.ifEmpty { "none" }) }
        for ((cause, k) in o.causes) if (k > 0) stats.deaths.merge("${s.name}:$cause", k, Int::plus)
        fauna.record?.let { rec -> for ((cause, k) in o.causes) if (k > 0) rec.group(s, g.place, cause, k) }
        if (!s.carnivore) for ((cause, k) in o.causes) if (k > 0) stats.deaths.merge("herd size ${sizeClass(g)}:$cause", k, Int::plus)
        // (With rounds, herds keeping one and herds keeping to their own country apart: for the rounds check.)
        if (ROUNDS && !s.carnivore && g.route[0] != ROUND_OPEN) {
            val way = if (g.route[0] >= 0) "on round" else "own country"
            for ((cause, k) in o.causes) if (k > 0) stats.deaths.merge("${s.name} $way:$cause", k, Int::plus)
            if (o.births > 0) stats.deaths.merge("${s.name} $way:born", o.births, Int::plus)
        }
        if (s.human) {
            val c = Biome.entries[biomeOfPlace[g.place]].name.lowercase().replace('_', ' ')
            for ((cause, k) in o.causes) if (k > 0) stats.hunting.merge("bands in $c:died of $cause", k, Int::plus)
            if (o.births > 0) stats.hunting.merge("bands in $c:born", o.births, Int::plus)
        }
        if (o.orphans > 0) stats.deaths.merge("${s.name}:${DeathCause.ORPHANED}", o.orphans, Int::plus)
        // Children's deaths apart, for the life tables.
        if (s.human && o.youngDied > 0) stats.hunting.merge("${s.name}:${WeekStats.CHILDREN_DIED}", o.youngDied, Int::plus)
        // A band that keeps the old way leaves a camp where someone died.
        if (s.human && o.deaths > 0) g.band?.let { b ->
            if (b.holds(Tradition.LEAVE_DEAD) && !b.holds(Tradition.BURIAL)) {
                b.unluckyHex = hw.placeHex[g.place]
                b.unluckyWeeks = UNLUCKY_WEEKS
                note(g) { "left the camp where its dead lie" }
            }
        }
        // A band that buries its dead buries them where it is: its ancestors' place.
        // For each taken by a beast, others got away hurt.
        g.band?.let { b ->
            val taken = o.causes.filter { it.first.startsWith(DeathCause.TAKEN_BY) }.sumOf { it.second }
            if (taken > 0) {
                b.wounded(WOUNDED_PER_DEAD * taken, g.adults)
                b.beastLoss += taken / g.size.coerceAtLeast(1).toFloat()
            }
        }
        // Where a beast took one of them, the band remembers the place as dangerous.
        if (s.human && o.causes.any { it.first.startsWith(DeathCause.TAKEN_BY) && it.second > 0 })
            g.memory.mark(hw.placeHex[g.place], PlaceMemory.seasonOf(date.month), (date.absoluteDay / days).toInt(), g.memory.danger, 1f)
        if (s.human && o.deaths > 0 && g.band?.holds(Tradition.BURIAL) == true) {
            g.memory.mark(hw.placeHex[g.place], PlaceMemory.seasonOf(date.month), (date.absoluteDay / days).toInt(), g.memory.dead, o.deaths.toFloat())
            land.burials[g.place] += o.deaths.toFloat()
            // Ornaments go into the grave with them: the more a band has, the richer its graves.
            g.band?.let { b -> val goods = min(b.ornaments, GRAVE_GOODS * b.ornaments * o.deaths); val ex = if (b.ornaments > 0f) b.exotic / b.ornaments else 0f
                b.ornaments -= goods; b.exotic -= goods * ex; land.graveGoods[g.place] += goods }
        }
        o.split?.let { c -> born += splitOff(g, c.first, c.second) } // a young pack has no ground yet
    }
    groups += born
    Profile.part("groups: merging and pairing") { mergeAndPair(date) }
}

/**
 * The group that splits off [g] with [adults] and [young] of its number: it takes its memory, condition, taste
 * and temper, a share of its larder by heads, and, for a band, a copy of what it knew and its share of its
 * tools, goods, elders, practised people and ages; a herd keeps its seasons and round, drifted a little.
 */
private fun HexGroups.splitOff(g: HexGroup, adults: Int, young: Int): HexGroup {
    val s = g.species
    val n = HexGroup(nextId++, s, g.place, adults, young)
    g.memory.copyInto(n.memory)
    n.fed = g.fed
    n.fat = g.fat
    n.tag = g.tag
    n.sizePref = g.sizePref
    n.parent = g.id
    n.dry = g.dry
    n.chasedOn = g.chasedOn
    n.birthMonth = g.birthMonth
    n.temper = Temperament.inherit(g.temper, n.id)
    // A band that splits shares out its larder by heads.
    val share = (adults + young).toFloat() / (g.size + adults + young).coerceAtLeast(1)
    n.larder = g.larder * share; g.larder -= n.larder
    // Both halves keep what the band knew and share out its tools and goods.
    n.bold = g.bold
    g.band?.let { b -> n.band = Band().also { nb -> b.copyInto(nb); riteInherit(nb)
        for (i in nb.kept.indices) { nb.kept[i] *= share; b.kept[i] -= nb.kept[i] }
        for (m in b.goods.indices) { nb.goods[m] = b.goods[m] * share; b.goods[m] -= nb.goods[m] }
        nb.ornaments = b.ornaments * share; b.ornaments -= nb.ornaments; nb.exotic = b.exotic * share; b.exotic -= nb.exotic
        nb.elders = b.elders * adults / (g.adults + adults).coerceAtLeast(1); b.elders -= nb.elders
        // Its practised people go one way or the other: a craft held by one or two may go wholly with either half.
        val go = adults.toFloat() / (g.adults + adults).coerceAtLeast(1)
        for (i in b.holders.indices) if (b.holders[i] > 0f) {
            val held = b.holders[i].toInt() + (if (rnd.nextFloat() < b.holders[i] - b.holders[i].toInt()) 1 else 0)
            val took = binomial(held, go, rnd).toFloat(); nb.holders[i] = took; b.holders[i] = max(0f, b.holders[i] - took)
        }
        nb.lastAdults = adults; b.lastAdults = g.adults
        for (i in b.ageStage.indices) { nb.ageStage[i] = b.ageStage[i] * adults / (g.adults + adults).coerceAtLeast(1); b.ageStage[i] -= nb.ageStage[i] } } }
    g.seasonPlace.copyInto(n.seasonPlace); g.seasonQ.copyInto(n.seasonQ); g.route.copyInto(n.route); g.proteinByMonth.copyInto(n.proteinByMonth)
    if (ROUNDS && !s.carnivore) driftRound(n)
    return n
}

/**
 * [n] of [g]'s grown members died, of any cause but old age (hunger, illness, a fight, a horn, a
 * crossing): in a band the elders and each stage of the childbearing years lose their share of them
 * (rounded at random, so single deaths fall on the old as often as their share says), so the age
 * structure stays whole and births are not reckoned on the dead. Never more than there are.
 */
internal fun adultsDied(g: HexGroup, n: Int, r: Random) {
    val dead = min(n, g.adults)
    if (dead <= 0) return
    g.band?.let { b ->
        b.elders -= min(b.elders, (dead * b.elders + r.nextInt(g.adults)) / g.adults)
        for (i in b.ageStage.indices) b.ageStage[i] -= min(b.ageStage[i], (dead * b.ageStage[i] + r.nextInt(g.adults)) / g.adults)
    }
    g.adults -= dead
    g.band?.let { b ->
        // (Rounding can leave more counted old than are left alive; the rest are of the first stage.)
        b.elders = min(b.elders, g.adults)
        for (i in b.ageStage.indices) b.ageStage[i] = min(b.ageStage[i], g.adults - b.elders - b.ageStage.take(i).sum())
    }
}

/** What a group's week left for others: carcasses (kg), births, deaths by cause, orphans, a split-off part (adults, young). */
internal class Outcome(val carrionKg: Float, val births: Int, val deaths: Int, val causes: List<Pair<String, Int>>, val orphans: Int,
    val split: Pair<Int, Int>?, val youngDied: Int = 0)

/** One group's week of feeding on its reserves, births, deaths and growing up; with its own random stream [r]. */
internal fun HexGroups.liveWeek(g: HexGroup, weather: Weather, date: Date, kinNow: HashMap<Long, Int>, r: kotlin.random.Random): Outcome {
    val s = g.species
    feedOnReserves(g)
    moveBirthMonth(g, date)
    val inSeason = monthsFrom(g.birthMonth, date.month) < s.birthMonths
    var bornNow = 0
    var bornExpected = 0f
    // In a band, the elders are past bearing children; they help their daughters raise theirs.
    val band = g.band
    val mothers = mothersIn(g)
    val help = if (band != null) min(1f, band.elders * ELDER_MOTHERS / max(1, mothers)) else 0f
    // (Births follow one rule for every kind, groups and residents alike: each mother bears her
    // litter as she is fed, through the birth season; only where just the leading pair of a pack
    // breeds ([Species.packLitter]: wolves) is there one litter a pack. A pack bears only on ground of its own.)
    if (inSeason && g.adults >= 2 && !(s.pack && g.home < 0)) {
        val births = if (s.packLitter > 0) {
            if (date.dayOfMonth <= days && date.month == g.birthMonth && g.home >= 0) (s.packLitter * g.fed + r.nextFloat()).toInt() else 0
        } else {
            val stepsInSeason = s.birthMonths * Date.DAYS_PER_MONTH / days.toFloat()
            val rate = s.youngPerFemale * g.fed * settledFertility(g) * (if (s.human) HUMAN_BIRTHS * Tuning.humanBirths else 1f) *
                (1f + GRANDMOTHERS * help) / stepsInSeason
            bornExpected = mothers * min(1f, rate)
            // (Mothers short of protein bear and suckle fewer: see [HexFauna.proteinMet].)
            binomial(mothers, rate * (if (s.carnivore) 1f else g.protein * herdRanges(g, kinNow)), r)
        }
        g.young += births
        bornNow = births
        // The young are made of what their mothers ate: their weight comes off the mothers' reserves.
        if (births > 0) g.fat = max(0f, g.fat - births * NEWBORN_SHARE * s.massKg / s.dailyIntakeKg / max(1, g.adults / 2))
    }
    val hunger = if (g.fed < HexFauna.HUNGER_FROM) (HexFauna.HUNGER_FROM - g.fed) * HexFauna.HUNGER_RATE else 0f
    // People feel the cold as their clothes allow.
    // Exposure: the unprotected die of the cold within days.
    val exposure = g.band?.let { min(0.9f, EXPOSURE * it.exposure(weather.temperature[g.place]) * days / 7f) } ?: 0f
    val cold = (g.band?.let { s.discomfortC(it.feels(weather.temperature[g.place]), weather.date.month) / 10f } ?: discomfort(s, g.place, weather)) * 0.02f
    val fouled = campFouling(g) * years
    // Wounds: mishaps of the work (the further the gatherers range, the more), and those hurt in
    // fights, hunts and by beasts last week; tended wounds heal faster, fester less and lame fewer.
    val festered = if (band != null) woundsWeek(band) else 0f
    // Beasts of prey about: now and then one takes a gatherer, a child or an old one alone.
    val (stalked, stalker) = if (band != null) stalkedBy(g, band, kinNow) else Stalking.NONE
    // Vipers and scorpions, out in the warm months.
    val venom = if (s.human && weather.temperature[g.place] > 18f) VENOM * years else 0f
    // Children of a closed band are inbred; medicine saves some of the sick and wounded.
    val inbred = if (band != null) INBREEDING_DEATHS * band.inbred * years else 0f
    // (Healers save some of the sick and wounded, the more the more they know.)
    val healed = (1f - 0.15f * (band?.medicine ?: 0f)) * (band?.lapse(Skill.HERBS)?.let { 1f + HEALING_REACH * (it - 1f) } ?: 1f)
    // (And practised midwives and carers lose fewer infants.)
    val cared = band?.lapse(Skill.MIDWIFERY)?.let { 1f + HEALING_REACH * (it - 1f) } ?: 1f
    // Sicknesses of their own: the flux, marsh fever, the cough (see [Illness]); healers save some.
    val ill = if (band != null) illnessWeek(g, band, weather, r).also { for (i in it.indices) it[i] *= healed } else null
    val thirst = thirstWeek(g)
    // An outbreak raging where it is kills its share; where its kind is crowded, one may break out.
    val plague = fauna.outbreakDeaths(s, g.place)
    val sick = sicknessWeek(g, weather, kinNow, r)
    val disease = plague + sick
    // Packs without ground of their own die more: fights, injuries and hunger on others' ground.
    val homeless = if (s.pack && g.home < 0) DISPERSAL_DEATHS * years else 0f
    // Eagles take the young of the smaller hoofed animals in the open.
    val eagle = if (!s.human && s.massKg <= EAGLE_PREY_KG) small.eagleRisk(g.place) * years else 0f
    val (fireA, floodA, snowA) = fauna.calamities(s, g.place, weather, false)
    val (fireY, floodY, snowY) = fauna.calamities(s, g.place, weather, true)
    val croc = if (s.human) 0f else fauna.water.crocodileRisk(g.place, weather) * years
    // (Young plant-eaters short of protein grow slowly and die more.)
    val starved = if (s.carnivore) 0f else HexFauna.YOUNG_PROTEIN_DEATHS * (1f - g.protein) * years
    // Every hazard of the week, once: its cause and the odds it adds for a grown one and for a young one.
    val hazards = arrayListOf(
        Hazard(DeathCause.OTHER.label, healed * s.backgroundMortality * (if (s.human) Tuning.humanOther else 1f) * years,
            healed * cared * s.youngMortality * (if (s.human) Tuning.humanYoung else 1f) * years),
        Hazard(DeathCause.HUNGER.label, hunger, 1.5f * hunger), Hazard(DeathCause.COLD.label, cold, 1.5f * cold),
        Hazard(DeathCause.EXPOSURE.label, exposure, 1.5f * exposure), Hazard(DeathCause.THIRST.label, thirst, 1.5f * thirst),
        Hazard(DeathCause.DISEASE.label, sick, sick), Hazard(DeathCause.OUTBREAK.label, plague, plague),
        Hazard(DeathCause.FIRE.label, fireA, fireY), Hazard(DeathCause.FLOOD.label, floodA, floodY), Hazard(DeathCause.SNOW.label, snowA, snowY),
        Hazard(DeathCause.CROCODILES.label, 0f, croc), Hazard(DeathCause.EAGLES.label, 0f, eagle), Hazard(DeathCause.SHORT_OF_PROTEIN.label, 0f, starved),
        Hazard(DeathCause.HOMELESS.label, homeless, homeless),
        Hazard(DeathCause.CAMP_FOULING.label, 0f, fouled), Hazard(DeathCause.SNAKES.label, venom, venom), Hazard(DeathCause.INBREEDING.label, 0f, inbred),
        Hazard(DeathCause.takenBy(stalker), stalked, YOUNG_STALKED / (band?.childWatch ?: 1f).coerceAtLeast(0.25f) * stalked),
        Hazard(DeathCause.WOUNDS.label, festered, 0f))
    ill?.let { a -> for (it in Illness.entries) hazards += Hazard(it.label, a[it.ordinal], a[it.ordinal] * it.young) }
    // The elders' care spares some of the young.
    val spared = 1f - ELDER_CARE * help
    var adultSum = 0f; var youngSum = 0f
    for (h in hazards) { adultSum += h.adult; youngSum += h.young }
    val adultOdds = adultSum.coerceIn(0f, 0.9f)
    val youngOdds = (youngSum * spared).coerceIn(0f, 0.95f)
    if (s.human && g.young > 0) for (h in hazards) if (h.young > 0f)
        stats.childDeaths.computeIfAbsent(h.cause) { DoubleAdder() }.add((g.young * spared * h.young).toDouble())
    val da = binomial(g.adults, adultOdds, r); val dy = binomial(g.young, youngOdds, r)
    // (For training: what the week's odds made likely, before the elders' deaths are drawn.)
    val diedExpected = g.adults * adultOdds + g.young * youngOdds +
        (band?.let { b -> (b.elders - (da * b.elders.toFloat() / g.adults.coerceAtLeast(1))).coerceAtLeast(0f) * min(1f, ELDER_DEATHS * years) } ?: 0f)
    // Elders die as the others do (their share of the adults who died), and of old age besides.
    adultsDied(g, da, r)
    var oldAge = 0
    if (band != null) {
        oldAge = binomial(band.elders, ELDER_DEATHS * years, r)
        band.elders -= oldAge; g.adults -= oldAge
    }
    if (s.human) g.luck += (bornNow - bornExpected) - (da + dy + oldAge - diedExpected)
    // The sick and the newborn teach their carers.
    band?.let { b -> if (disease > 0f || da > 0) b.practise(Skill.HERBS, 0.2f * PRACTICE); if (bornNow > 0) b.practise(Skill.MIDWIFERY) }
    g.young -= dy
    // The dead lie where they fell, for the scavengers and the soil.
    var carrion = (da + oldAge + 0.3f * dy) * s.massKg
    // The week's deaths are shared among the causes by how much each weighed, the grown and the young apart ("other": age, accidents, ordinary illness).
    val causes = shareDeaths(hazards, da, dy, r)
    if (oldAge > 0) causes += DeathCause.OLD_AGE.label to oldAge
    growUp(g, r)
    if (g.adults == 0) {
        // Young left without a grown animal of their kind die.
        val orphans = g.young
        carrion += 0.3f * orphans * s.massKg
        g.young = 0
        return Outcome(carrion, bornNow, da + oldAge + dy, causes, orphans, null, dy + orphans)
    }
    var split: Pair<Int, Int>? = null
    // Bands that feast together hold together longer.
    if (g.size > maxSize(g, date)) {
        split = (g.adults / 2) to (g.young / 2)
        g.adults -= split.first; g.young -= split.second
    }
    return Outcome(carrion, bornNow, da + oldAge + dy, causes, 0, split, dy)
}

/** Reserves carry [g] through lean weeks; a group that ate (nearly) its fill lays them on, most in late summer and autumn. */
private fun HexGroups.feedOnReserves(g: HexGroup) {
    val s = g.species
    if (g.fed < WELL_FED) { val draw = min(g.fat, (1f - g.fed) * days); g.fat -= draw; g.fed = min(1f, g.fed + draw / days) }
    else g.fat = min(s.maxFatDays, g.fat + FAT_EFFICIENCY * g.surplus)
    g.surplus = 0f
}

/**
 * [g]'s birth month: its kind's to begin with; once a year it moves a month towards the months it fed best in (the birth
 * month and the two after), or as the group leans.
 */
private fun HexGroups.moveBirthMonth(g: HexGroup, date: Date) {
    val s = g.species
    if (g.birthMonth < 0) g.birthMonth = s.birthMonth
    // Once a year its births move a month towards the months it fed best in (the birth month and the two after).
    if (date.month == 3 && date.dayOfMonth <= days && !s.carnivore) {
        // (How well fed, and on how rich a diet, in the months the young would be born and suckled.)
        fun window(m0: Int) = (0 until 3).sumOf { (g.seasonQ[wrapMonth(m0 + it)] * g.proteinByMonth[wrapMonth(m0 + it)]).toDouble() }
        val m = g.birthMonth
        // (Or it moves them itself, when it leans far enough one way, see [AnimalWay.BIRTHS].)
        val lean = g.will[AnimalWay.BIRTHS] - 1f
        if (lean < -AnimalWay.BIRTHS_LEAN) g.birthMonth = wrapMonth(m - 1)
        else if (lean > AnimalWay.BIRTHS_LEAN) g.birthMonth = wrapMonth(m + 1)
        else if (window(m - 1) > window(m) + 0.1 && window(m - 1) >= window(m + 1)) g.birthMonth = wrapMonth(m - 1)
        else if (window(m + 1) > window(m) + 0.1) g.birthMonth = wrapMonth(m + 1)
    }
}

/**
 * [band]'s wounds this week: mishaps of the work and last week's hurts heal, the tended faster; some lame, some fester.
 * Returns the deaths from festering, a year's rate.
 */
private fun HexGroups.woundsWeek(band: Band): Float {
    val care = 0.5f * band.medicine + 0.5f * band.skill(Skill.HERBS)
    band.hurt = min(1f, band.hurt + MISHAPS * band.gatherRange * years)
    val heal = min(1f, (HEAL_UNTENDED + (HEAL_TENDED - HEAL_UNTENDED) * care) * days / 7f)
    val fester = FESTER * (1f - 0.8f * care) * band.hurt * days / 7f
    val mended = band.hurt * heal
    band.lame = min(1f, band.lame * (1f - LAME_TURNOVER * years) + mended * LAMED * (1f - 0.5f * care))
    band.hurt = max(0f, band.hurt - mended - fester)
    return fester
}

/** [g] drinks where there is water; otherwise days without water mount, eased by the water in green food. Returns the deaths from thirst, a year's rate. */
private fun HexGroups.thirstWeek(g: HexGroup): Float {
    val s = g.species
    val p0 = g.place
    // It drinks where there is water, or where water is within half a day's going of where it is.
    // People carry water home in skins, gourds and ostrich eggshells: anywhere within the day's round
    // will do. Animals go to water and back as often as they must: a kind that can go days without
    // drinking can live as many half-days' going from it.
    val fetch = if (s.human) s.dailyTravelKm else 0.5f * s.dailyTravelKm * max(1, s.daysWithoutWater)
    // (People also dig for it in a dry bed where the water lies near: see [HexLand.dug].)
    if (land.water[p0] >= Thresholds.WET || (s.maxWaterKm.isInfinite() && land.waterKm[p0] <= fetch) || (s.human && land.mustDig(p0, fetch))) g.dry = 0f
    // Plant-eaters get water from green food; hunters from the body water of what they eat, in any season.
    else g.dry += days * max(0f, 1f - s.waterFromFood * (if (s.carnivore) g.fed else 0.5f + 0.5f * land.green[p0]))
    // (People carry water in skins and know every spring: they suffer far less from it than animals.)
    // (Strangers to the country do not know its springs.)
    val knowsWater = g.band?.knows(hw.placeHex[p0]) ?: true
    return (if (g.dry > s.daysWithoutWater) min(0.5f, 0.05f * (g.dry - s.daysWithoutWater)) else 0f) *
        (if (s.human) HUMAN_THIRST * Tuning.humanThirst * (if (knowsWater) 1f else STRANGER_THIRST) else 1f)
}

/**
 * The mothers among [g]'s adults: half of them, or in a band, the women in their bearing years: most in
 * their twenties and early thirties, few in their teens, fewer after thirty-five, none past the
 * childbearing years (the elders).
 */
private fun mothersIn(g: HexGroup): Int {
    val band = g.band
    return if (band != null) {
        val first = max(0, g.adults - band.elders - band.ageStage[0] - band.ageStage[1])
        ((FERTILE_BY_SPAN[0] * first + FERTILE_BY_SPAN[1] * band.ageStage[0] + FERTILE_BY_SPAN[2] * band.ageStage[1]) / 2f).toInt()
    } else g.adults / 2
}

/**
 * [g]'s deaths of sickness this week besides a raging outbreak's, a year's rate: people's crowd
 * sicknesses; hunters' mange, distemper and rabies from packs of their kind about; herds' diseases and
 * parasites where they crowd. Where its kind is crowded, an outbreak may break out (drawn from [r]).
 */
private fun HexGroups.sicknessWeek(g: HexGroup, weather: Weather, kinNow: HashMap<Long, Int>, r: Random): Float {
    val s = g.species
    // Crowded herds catch each other's diseases and parasites.
    val usual = HexFauna.usualDensity(s)
    // Migratory herds live gathered: they bear densities on the move that would sicken a resident kind.
    val crowd = (kinNow[s.index.toLong() * hex.size + hw.placeHex[g.place]] ?: 0) / hex.areaKm2.toFloat() / (HexFauna.CROWDED * usual * MIGRANTS_GATHER)
    val crowdNow = if (s.human) peopleCrowding(g) else crowd
    if (r.nextFloat() < HexFauna.OUTBREAK_START * (if (s.human) PEOPLE_OUTBREAK * Tuning.peopleOutbreak else 1f) * crowdNow * crowdNow * years)
        outbreaksStarting += s.index.toLong() * hex.size + hw.placeHex[g.place]
    return if (s.human) peopleSickness(g, weather) * years * (1f + HexFauna.MALNOURISHED * (1f - g.fed))
        else if (s.carnivore) {
            // Hunters catch mange, distemper and rabies from other packs of their kind crowding the same ground.
            // (Counted, as for residents, over the ground it ranges over: its hex, and the six round it
            // where its kind's home range reaches beyond one hex.)
            val h0 = hw.placeHex[g.place]
            var heads = -g.size; var hexes = 0
            val wide = territoryKm(s) > 0.6f * hex.hexKm
            for (k in -1 until (if (wide) 6 else 0)) {
                val q = if (k < 0) h0 else hex.neighbour(h0, k)
                if (q >= 0) {
                    heads += kinNow[s.index.toLong() * hex.size + q] ?: 0
                    hexes++
                }
            }
            val others = max(0, heads) / (hexes * hex.areaKm2.toFloat()) / (HexFauna.CROWDED * usual)
            HexFauna.DISEASE * others * others * years * (1f + HexFauna.MALNOURISHED * (1f - g.fed))
        }
        else HexFauna.DISEASE * crowd * crowd * years * (1f + HexFauna.MALNOURISHED * (1f - g.fed))
}

/** One of the week's hazards: its [cause] and the odds it adds that a grown one ([adult]) or a young one ([young]) dies. */
private class Hazard(val cause: String, val adult: Float, val young: Float)

/** The week's deaths, [adults] and [young], shared among the [hazards] by how much each weighed for them (drawn from [r]); what is left over is "other". */
private fun HexGroups.shareDeaths(hazards: List<Hazard>, adults: Int, young: Int, r: Random): ArrayList<Pair<String, Int>> {
    val died = LinkedHashMap<String, Int>()
    for ((n, ofYoung) in listOf(adults to false, young to true)) {
        var left = n; var weight = 0f
        for (h in hazards) weight += if (ofYoung) h.young else h.adult
        for (h in hazards) {
            if (left <= 0 || weight <= 0f) break
            val w = if (ofYoung) h.young else h.adult
            val k = if (w >= weight) left else binomial(left, w / weight, r)
            if (k > 0) died.merge(h.cause, k, Int::plus)
            left -= k; weight -= w
        }
        if (left > 0) died.merge(DeathCause.OTHER.label, left, Int::plus)
    }
    return died.entries.mapTo(ArrayList()) { it.key to it.value }
}

/** [g]'s young grow up, and in a band the adults age through three spans of adult life, then become elders (drawn from [r]). */
private fun HexGroups.growUp(g: HexGroup, r: Random) {
    val s = g.species
    val band = g.band
    val up = binomial(g.young, years / s.yearsToMaturity, r)
    g.young -= up; g.adults += up
    // Adults age through three spans of adult life, then become elders.
    band?.let { b ->
        val rate = 3f * years / ADULT_YEARS
        val toElder = binomial(b.ageStage[1], rate, r); val toThird = binomial(b.ageStage[0], rate, r)
        val first = max(0, g.adults - b.elders - b.ageStage[0] - b.ageStage[1])
        val toSecond = binomial(first, rate, r)
        b.elders += toElder; b.ageStage[1] += toThird - toElder; b.ageStage[0] += toSecond - toThird
    }
}

/**
 * Yearly deaths from crowding among people: fevers and worms where many camp near each other, by the
 * people in a band's hex and the six round it.
 */
private fun HexGroups.peopleSickness(g: HexGroup, weather: Weather): Float {
    val dens = peopleCrowding(g)
    // Fevers by the marshes in the warm months, where the mosquitoes breed.
    val marsh = land.waterlogged[g.place] * if (weather.temperature[g.place] > 20f) 1f else 0f
    // (Marsh fever is now its own sickness: see [Illness].)
    return PEOPLE_SICK * Tuning.peopleSick * dens * dens
}

/** People round a band's camp (its hex and the six round it), per km², against [PEOPLE_FULL_KM2]. */
internal fun HexGroups.peopleCrowding(g: HexGroup): Float {
    val h = hw.placeHex[g.place]
    var heads = 0
    for (k in -1 until 6) {
        val q = if (k < 0) h else hex.neighbour(h, k)
        if (q < 0) continue
        for (b in 0 until HexWorld.BANDS_M.size) { val p = hw.placeOf[b][q]; if (p >= 0) heads += headsHere[kindSlot[g.species.index]][p] }
    }
    return heads / (7f * hex.areaKm2.toFloat()) / PEOPLE_FULL_KM2
}

/** Small herds join up, and lone hunters find mates (see [demography]). */
internal fun HexGroups.mergeAndPair(date: Date) {
    // Small herds of a kind that end the week in the same place join up, as far as the herd can grow.
    val byPlace = HashMap<Long, HexGroup>()
    for (g in groups) {
        val s = g.species
        if (!g.alive || s.carnivore) continue
        val key = (s.index.toLong() * n + g.place) * 1000 + (g.tag + 1)
        val other = byPlace[key]
        if (other != null && other.size + g.size <= maxSize(other, date)) {
            val share = g.size.toFloat() / (other.size + g.size)
            other.fed += (g.fed - other.fed) * share; other.fat += (g.fat - other.fat) * share
            g.mergedSize = g.size; g.mergedInto = other.id
            other.adults += g.adults; other.young += g.young
            g.adults = 0; g.young = 0
        } else byPlace[key] = g
    }
    // A band grown too small to hunt, gather and defend itself joins the nearest kin band that has room.
    val bandsByHex = HashMap<Int, ArrayList<HexGroup>>()
    for (g in groups) if (g.alive && g.band != null) bandsByHex.getOrPut(hw.placeHex[g.place]) { ArrayList() } += g
    for (g in groups) {
        val b = g.band ?: continue
        if (!g.alive || g.adults >= SMALL_BAND) continue
        var best: HexGroup? = null; var bestKin = -1f
        val h0 = hw.placeHex[g.place]
        var ring = listOf(h0); val seen = hashSetOf(h0)
        for (r in 0..VISIT_RINGS) {
            for (h in ring) for (o in bandsByHex[h].orEmpty()) {
                if (o === g || !o.alive || o.size + g.size > maxSize(o, date)) continue
                val k = kinship(b.lineage, o.band!!.lineage) / (1f + r)
                if (k > bestKin) { bestKin = k; best = o }
            }
            if (best != null || r == VISIT_RINGS) break
            ring = hex.nextRing(ring, seen)
        }
        val o = best ?: continue
        joinBand(o, g)
        stats.meetings.merge("small bands joined kin", 1, Int::plus)
    }
    // A hunter left on its own looks for a mate: lone adults of a kind within a few hexes pair up, and
    // the pair holds the ground of the one that had any.
    val lone = HashMap<Int, ArrayList<HexGroup>>()
    for (g in groups) if (g.alive && g.species.pack && g.adults == 1) lone.getOrPut(g.species.index) { ArrayList() } += g
    for ((_, list) in lone) {
        val used = HashSet<Int>()
        for (a in list) {
            if (a.id in used) continue
            // (As far as it chooses to look; and only among its own lineage while variants are weighed against each other.)
            val b = list.firstOrNull { it.id != a.id && it.id !in used && it.tag == a.tag &&
                hex.distanceKm(hw.placeHex[it.place], hw.placeHex[a.place]) <= MATE_SEARCH_KM * a.will[AnimalWay.MATE] } ?: continue
            used += a.id; used += b.id
            a.adults += b.adults; a.young += b.young
            a.fed = (a.fed + b.fed) / 2f; a.fat = (a.fat + b.fat) / 2f
            if (a.home < 0) claimGround(a, b.home)
            b.mergedSize = b.size; b.mergedInto = a.id
            b.adults = 0; b.young = 0
        }
    }
}

/** Band [from] joins band [into]: its people, food, goods and knowledge come with it. */
private fun joinBand(into: HexGroup, from: HexGroup) {
    val a = into.band!!; val b = from.band!!
    val w = from.adults.toFloat() / (into.adults + from.adults).coerceAtLeast(1)
    for (i in a.skills.indices) a.skills[i] = max(a.skills[i], b.skills[i])
    for (i in a.holders.indices) a.holders[i] = max(0f, a.holders[i]) + max(0f, b.holders[i])
    a.lastAdults += from.adults
    for (i in a.knowsSkill.indices) a.knowsSkill[i] = a.knowsSkill[i] || b.knowsSkill[i]
    for (i in a.plants.indices) a.plants[i] = max(a.plants[i], b.plants[i])
    for (i in a.kit.indices) {
        val ka = a.kit[i] * (1f - w)
        val kb = b.kit[i] * w
        if (ka + kb > 0f) a.fine[i] = (ka * a.fine[i] + kb * b.fine[i]) / (ka + kb)
        a.kit[i] += w * (b.kit[i] - a.kit[i])
    }
    val dried = into.stores[Store.DRIED.ordinal] + from.stores[Store.DRIED.ordinal]
    if (dried > 0f) a.salted = (into.stores[Store.DRIED.ordinal] * a.salted + from.stores[Store.DRIED.ordinal] * b.salted) / dried
    a.renown = max(a.renown, b.renown)
    for (i in a.knows.indices) a.knows[i] = a.knows[i] || b.knows[i]
    for (i in a.lineage.indices) a.lineage[i] += w * (b.lineage[i] - a.lineage[i])
    for (i in a.grudge.indices) a.grudge[i] = max(a.grudge[i], b.grudge[i])
    for ((k, v) in b.ties) if (k != into.id) a.ties[k] = max(a.tie(k), v)
    a.ties.remove(from.id)
    for (i in a.goods.indices) a.goods[i] += b.goods[i]
    if (a.known.size < b.known.size) a.known = a.known.copyOf(b.known.size)
    for (i in b.known.indices) a.known[i] = a.known[i] or b.known[i]
    if (a.kept.size < b.kept.size) a.kept = a.kept.copyOf(b.kept.size)
    for (i in b.kept.indices) a.kept[i] += b.kept[i]
    a.inbred += w * (b.inbred - a.inbred); a.elders += b.elders
    for (i in a.ageStage.indices) a.ageStage[i] += b.ageStage[i]
    into.fed += w * (from.fed - into.fed); into.fat += w * (from.fat - into.fat)
    into.larder += from.larder
    into.memory.hear(from.memory, 0, 1f)
    from.mergedSize = from.size; from.mergedInto = into.id
    into.adults += from.adults; into.young += from.young
    from.adults = 0; from.young = 0; from.larder = 0f
}

/** Herds remember the place where they did best in each month. */
internal fun HexGroups.remember(date: Date) {
    val m = date.month
    // Every group remembers where it was this week and how it fared there.
    val week = (date.absoluteDay / days).toInt()
    for (g in groups) if (g.alive) g.memory.record(hw.placeHex[g.place], PlaceMemory.seasonOf(m), g.fed, land.water[g.place], fauna.afraid(g.place), week)
    for (g in groups) {
        if (g.species.pack) continue
        // At the remembered place it learns how good it really is; elsewhere the memory fades slowly,
        // and a clearly better place takes its place.
        if (g.seasonPlace[m] < 0) { g.seasonPlace[m] = g.place; g.seasonQ[m] = g.fed }
        else if (g.seasonPlace[m] == g.place) g.seasonQ[m] += 0.3f * (g.fed - g.seasonQ[m])
        else {
            g.seasonQ[m] *= MEMORY_FADE
            if (g.fed > g.seasonQ[m] + 0.1f) { g.seasonPlace[m] = g.place; g.seasonQ[m] = g.fed }
        }
    }
}

/**
 * The yearly-scaled share of a band taken by beasts of prey this week, and the beast most to blame:
 * lions, leopards, bears, wolves and hyenas that range its hex now and then take one who is alone
 * at the gathering, a child, an old one. The thicker the cover the likelier; a fire at night, dogs,
 * lookouts, company and an eye for sign (tracking) keep them off. No set battle: the beasts take
 * the unwary, not armed men.
 */
internal fun HexGroups.stalkedBy(g: HexGroup, b: Band, kinNow: HashMap<Long, Int>): Stalking {
    val h = hw.placeHex[g.place]; val p = g.place
    val hexKm2 = hex.areaKm2.toFloat()
    var pressure = 0f; var worst = ""; var worstP = 0f
    for (k in kinds) {
        val w = MAN_EATERS[k] ?: continue
        val heads = kinNow[k.index.toLong() * hex.size + h] ?: continue
        val v = w * heads * k.massKg / hexKm2
        pressure += v; if (v > worstP) { worstP = v; worst = k.name }
    }
    for ((ki, k) in fauna.kinds.withIndex()) {
        val w = MAN_EATERS[k] ?: continue
        val v = w * (fauna.adults[ki][p] + fauna.young[ki][p]) * k.massKg
        pressure += v; if (v > worstP) { worstP = v; worst = k.name }
    }
    if (pressure <= 0f) return Stalking.NONE
    val cover = 0.5f + land.cover(p)
    val guard = (1f - FIRE_GUARD * b.nightGuard) * (1f - DOGS_GUARD * min(1f, b.dogShare)) * (1f - LOOKOUTS_GUARD * min(1f, 10f * b.scoutShare))
    // (Gatherers spread further from camp leave more ground to watch: about as its square.)
    val spread = b.gatherRange * b.gatherRange
    val company = (STALKED_BAND / max(1, g.size).toFloat()).coerceIn(0.5f, 2f).pow(0.3f)
    val sign = b.lapse(Skill.TRACK).pow(0.5f)
    // (Beasts lie up by the paths people and game use: a camp on a busy way is the more dangerous.)
    val rate = STALKED * pressure / (pressure + STALKED_HALF) * cover * guard * company * sign * spread * (1f + 0.5f * AMBUSH * wear.at(h)) * years
    // (Where one was taken the band remembers the danger.)
    return Stalking(rate, worst)
}

/** A band's [rate] of loss to beasts of prey (yearly-scaled share) and the beast most to blame, [by] ("" when none). */
data class Stalking(val rate: Float, val by: String) {
    companion object { val NONE = Stalking(0f, "") }
}

/**
 * Beasts that take people, by how readily (leopards most, then lions; bears, wolves and hyenas
 * rarely); yearly share of people they take at most where they are thick, from the unwary in thick
 * cover with no fire, dogs or lookouts; density of beast flesh, kg/km² weighted, at which they take
 * half that; a band size the company factor is set for; how much a fire at night, dogs and lookouts
 * each keep off; how much more often children are taken.
 */
val MAN_EATERS by lazy { mapOf(Catalogue.LEOPARD to 1.2f, Catalogue.LION to 1f, Catalogue.BEAR to 0.4f, Catalogue.WOLF to 0.3f, Catalogue.HYENA to 0.3f) }
const val STALKED = 0.02f
const val STALKED_HALF = 3f
const val STALKED_BAND = 25f
const val FIRE_GUARD = 0.5f
const val DOGS_GUARD = 0.5f
const val LOOKOUTS_GUARD = 0.3f
const val YOUNG_STALKED = 2f

/**
 * Herds keep the residents' rule for breeding ranges (see [HexFauna.BREED_SPACING]) over the country
 * they range over, not the hex they gather in: the share of a herd's mothers that breed, as its kind
 * crowds the hex and the two rings of hexes about it beyond [HexFauna.BREED_SPACING] times its usual numbers.
 */
internal fun HexGroups.herdRanges(g: HexGroup, kinNow: HashMap<Long, Int>): Float {
    if (g.species.carnivore) return 1f
    val h0 = hw.placeHex[g.place]; val key = g.species.index.toLong() * hex.size
    val ring = HashSet<Int>(); ring += h0
    for (k in 0 until 6) {
        val q = hex.neighbour(h0, k)
        if (q >= 0) {
            ring += q
            for (j in 0 until 6) { val w = hex.neighbour(q, j); if (w >= 0) ring += w }
        }
    }
    var heads = 0; for (q in ring) heads += kinNow[key + q] ?: 0
    val density = heads / (ring.size * hex.areaKm2).toFloat()
    return if (density <= 0f) 1f else min(1f, HexFauna.BREED_SPACING * HexFauna.usualDensity(g.species) / density)
}
