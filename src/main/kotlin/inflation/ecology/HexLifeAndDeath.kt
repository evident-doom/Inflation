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
        if (kotlin.math.abs(cx - hx) <= reachKm && kotlin.math.abs(cy - hy) <= reachKm && hypot(cx - hx, cy - hy) <= reachKm) near += p
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
            val score = (0.2f + grow) * fauna.suits(s, p) * snow / (1f + discomfort * discomfort) / (1f + dist.toFloat())
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
    for (g in groups) {
        val s = g.species
        if (!g.alive || s.carnivore) continue
        val p = g.place
        val want = g.dailyNeedKg * days / 1e6f / land.areaKm2[p] // kg per m² of the place
        val access = HexFood.access(s, land.ruggedness[p])
        val snowed = weather.snowpack[p] > s.snowLimitMm
        val d = s.diet; val total = (d.grass + d.browse + d.fruit + d.roots).coerceAtLeast(1e-6f)
        val reach = HexFauna.REACH_DORMANT + (HexFauna.REACH - HexFauna.REACH_DORMANT) * land.green[p]
        // Poor forage is made up for by eating more of it, as far as the gut holds.
        val fill = min(HexFauna.GUT_FILL, 1f / land.nourishment(p))
        val wantGrass = if (snowed) 0f else want * fill * access * d.grass / total
        val grass = land.consume(p, LifeForm.HERB, min(wantGrass, reach * land.herb[p]))
        val wantBrowse = want * fill * access * d.browse / total
        val browse = land.consume(p, LifeForm.SHRUB, min(wantBrowse, reach * land.shrub[p]))
        val fruit = food.take(food.fruit, p, want * access * d.fruit / total)
        val roots = food.take(food.roots, p, want * access * d.roots / total)
        // Diet switching: a food that fell short is made up from the others, each to at most (1 + SWITCH)
        // times its usual share (an ostrich out of fruit season picks more shoots and leaves).
        val q = land.nourishment(p)
        var gap = want - ((grass + browse) * q + fruit + roots)
        var grass2 = 0f; var browse2 = 0f; var fruit2 = 0f; var roots2 = 0f
        if (gap > 0f && d.browse > 0f) { browse2 = land.consume(p, LifeForm.SHRUB, min(gap / q, min(SWITCH * wantBrowse, reach * land.shrub[p] - browse).coerceAtLeast(0f))); gap -= browse2 * q }
        if (gap > 0f && d.grass > 0f && !snowed) { grass2 = land.consume(p, LifeForm.HERB, min(gap / q, min(SWITCH * want * fill * access * d.grass / total, reach * land.herb[p] - grass).coerceAtLeast(0f))); gap -= grass2 * q }
        if (gap > 0f && d.fruit > 0f) { fruit2 = food.take(food.fruit, p, min(gap, SWITCH * want * access * d.fruit / total)); gap -= fruit2 }
        if (gap > 0f && d.roots > 0f) { roots2 = food.take(food.roots, p, min(gap, SWITCH * want * access * d.roots / total)); gap -= roots2 }
        // What grass and browse could not give, straw makes up as far as there is any, at what its gut gets out of it.
        val short = max(0f, gap)
        val straw = food.take(food.straw, p, short * HexFauna.GUT_FILL * access)
        small.dung[p] += HexSmallLife.DUNG * (grass + browse + fruit + roots + straw + grass2 + browse2 + fruit2 + roots2)
        // On the way it beat down the plants and dropped the seed of the fruit it ate.
        val band = hw.placeBand[p]
        for (h in g.path) {
            val q = hw.placeOf[band][h].takeIf { it >= 0 } ?: continue
            land.trample(q, g.size * s.massKg / 1000f)
            if (fruit > 0f) land.animalSeed[q] = min(1f, land.animalSeed[q] + fruit * land.areaKm2[p] * 1e6f / g.path.size / land.areaKm2[q] / HexFauna.SEED_KG)
        }
        // Grass and browse nourish fully only when green; fruit and roots keep their value.
        val got = (grass + browse + grass2 + browse2) * q + fruit + roots + fruit2 + roots2 + min(short, straw * HexFood.roughage(s))
        g.fed = (got / want.coerceAtLeast(1e-9f)).coerceIn(0f, 1f)
        land.grazing[p] += (grass + grass2) * 1e6f / days; land.browsing[p] += (browse + browse2) * 1e6f / days; land.rooting[p] += (roots + roots2) * 1e6f / days
    }
}

internal fun HexGroups.demography(weather: Weather, date: Date) {
    // Bands keep or move camp, gather materials and make their tools.
    followersByHex = groups.filter { it.alive && it.species.followsPeople && !it.species.human }.groupBy { hw.placeHex[it.place] }
    for (g in groups) if (g.alive && g.band != null) { bandWeek(g, date); cultureWeek(g) }
    // Hunters remember where they hunted, and a pack long hungry on its ground gives it up.
    for (g in groups) {
        if (!g.alive || !g.species.carnivore) continue
        val h = hw.placeHex[g.place]
        if (g.recent[0] != h) { for (i in g.recent.size - 1 downTo 1) g.recent[i] = g.recent[i - 1]; g.recent[0] = h }
        g.lean = if (g.fed < 0.5f) g.lean + 1 else 0
        // A pack long hungry keeps its ground but ranges wider over it (see [HexGroups.plan]).
    }
    val kinNow = HashMap<Long, Int>()
    for (g in groups) if (g.alive) kinNow.merge(g.species.index.toLong() * hex.size + hw.placeHex[g.place], g.size, Int::plus)
    // Each group on its own core, with its own random stream; what it leaves for others (carcasses,
    // the counts, a split-off herd) is gathered and handed on after, in the groups' order.
    val list = groups.toList()
    val outcome = arrayOfNulls<Outcome>(list.size)
    java.util.stream.IntStream.range(0, list.size).parallel().forEach { k ->
        val g = list[k]
        if (g.alive) outcome[k] = liveWeek(g, weather, date, kinNow, groupRandom(g.id, date.absoluteDay))
    }
    val born = ArrayList<HexGroup>()
    for ((k, o) in outcome.withIndex()) {
        val g = list[k]; val s = g.species
        if (o == null) {
            // Young whose last grown animal was killed this week (by hunters or rivals) die too.
            if (!g.alive && g.young > 0) {
                deaths.merge("${s.name}:orphaned", g.young, Int::plus)
                small.carrion[g.place] += 0.3f * g.young * s.massKg / (land.areaKm2[g.place] * 1e6f)
                g.young = 0
            }
            continue
        }
        if (o.carrionKg > 0f) small.carrion[g.place] += o.carrionKg / (land.areaKm2[g.place] * 1e6f)
        if (o.births > 0) births.merge(s.name, o.births, Int::plus)
        for ((cause, k) in o.causes) if (k > 0) deaths.merge("${s.name}:$cause", k, Int::plus)
        if (o.orphans > 0) deaths.merge("${s.name}:orphaned", o.orphans, Int::plus)
        // Children's deaths apart, for the life tables.
        if (s.human && o.youngDied > 0) hunting.merge("${s.name}:children died", o.youngDied, Int::plus)
        o.split?.let { c -> born += HexGroup(nextId++, s, g.place, c.first, c.second).also { n ->
            n.fed = g.fed; n.fat = g.fat; n.tag = g.tag; n.dry = g.dry; n.wary = g.wary; n.birthMonth = g.birthMonth
            // A band that splits shares out its larder by heads.
            val share = (c.first + c.second).toFloat() / (g.size + c.first + c.second).coerceAtLeast(1)
            n.larder = g.larder * share; g.larder -= n.larder
            // Both halves keep what the band knew and share out its tools and goods.
            n.bold = g.bold
            g.band?.let { b -> n.band = Band().also { nb -> b.copyInto(nb); for (i in nb.kept.indices) { nb.kept[i] *= share; b.kept[i] -= nb.kept[i] }; for (m in b.goods.indices) { nb.goods[m] = b.goods[m] * share; b.goods[m] -= nb.goods[m] }
                nb.elders = b.elders * c.first / (g.adults + c.first).coerceAtLeast(1); b.elders -= nb.elders } }
            g.seasonPlace.copyInto(n.seasonPlace); g.seasonQ.copyInto(n.seasonQ)
        } } // a young pack has no ground yet
    }
    groups += born
    mergeAndPair()
}

/** What a group's week left for others: carcasses (kg), births, deaths by cause, orphans, a split-off part (adults, young). */
internal class Outcome(val carrionKg: Float, val births: Int, val deaths: Int, val causes: List<Pair<String, Int>>, val orphans: Int, val split: Pair<Int, Int>?, val youngDied: Int = 0)

/** One group's week of feeding on its reserves, births, deaths and growing up; with its own random stream [r]. */
internal fun HexGroups.liveWeek(g: HexGroup, weather: Weather, date: Date, kinNow: HashMap<Long, Int>, r: kotlin.random.Random): Outcome {
    run {
        val s = g.species
        // Reserves carry a group through lean weeks; a group that ate (nearly) its fill lays them on,
        // most in late summer and autumn. (Only a full 100% used to count, which a pack or band that
        // hunts and gathers seldom reaches exactly: they never laid on reserves.)
        if (g.fed < WELL_FED) { val draw = min(g.fat, (1f - g.fed) * days); g.fat -= draw; g.fed = min(1f, g.fed + draw / days) }
        else g.fat = min(s.maxFatDays, g.fat + (if (date.month in 7..10) AUTUMN_FAT else 0.3f) * days * (g.fed - WELL_FED) / (1f - WELL_FED))
        if (g.birthMonth < 0) g.birthMonth = s.birthMonth
        // Once a year its births move a month towards the months it fed best in (the birth month and the two after).
        if (date.month == 3 && date.dayOfMonth <= days && !s.carnivore) {
            fun window(m0: Int) = (0 until 3).sumOf { g.seasonQ[(m0 + it + 12) % 12].toDouble() }
            val m = g.birthMonth
            if (window(m - 1) > window(m) + 0.1 && window(m - 1) >= window(m + 1)) g.birthMonth = (m + 11) % 12
            else if (window(m + 1) > window(m) + 0.1) g.birthMonth = (m + 1) % 12
        }
        val inSeason = (date.month - g.birthMonth + 12) % 12 < s.birthMonths
        var bornNow = 0
        // In a band, the elders are past bearing children; they help their daughters raise theirs.
        val band = g.band
        val mothers = if (band != null) (g.adults - band.elders) / 2 else g.adults / 2
        val help = if (band != null) min(1f, band.elders * ELDER_MOTHERS / max(1, mothers)) else 0f
        if (inSeason && g.adults >= 2) {
            val births = if (s.packLitter > 0) {
                if (date.dayOfMonth <= days && date.month == g.birthMonth && g.home >= 0) (s.packLitter * g.fed + r.nextFloat()).toInt() else 0
            } else {
                val stepsInSeason = s.birthMonths * Date.DAYS_PER_MONTH / days.toFloat()
                binomial(mothers, s.youngPerFemale * s.littersPerYear * g.fed * settledFertility(g) * (1f + GRANDMOTHERS * help) / stepsInSeason, r)
            }
            g.young += births
            bornNow = births
        }
        val hunger = if (g.fed < 0.5f) (0.5f - g.fed) * 0.3f else 0f
        // People feel the cold as their clothes allow.
        val cold = (g.band?.let { s.discomfortC(it.feels(weather.temperature[g.place]), weather.date.month) / 10f } ?: discomfort(s, g.place, weather)) * 0.02f
        val fouled = campFouling(g) * years
        // Children of a closed band are inbred; medicine saves some of the sick and wounded.
        val inbred = if (band != null) INBREEDING_DEATHS * band.inbred * years else 0f
        val healed = if (band?.holds(Tradition.MEDICINE) == true) 0.85f else 1f
        // Thirst: drinks where there is water; otherwise days without water mount, eased by the water in green food.
        val p0 = g.place
        // It drinks where there is water, or where water is within half a day's going of where it is.
        // People carry water home in skins, gourds and ostrich eggshells: anywhere within the day's round will do.
        val fetch = if (s.human) s.dailyTravelKm else 0.5f * s.dailyTravelKm
        if (land.water[p0] >= 0.05f || (s.maxWaterKm.isInfinite() && land.waterKm[p0] <= fetch)) g.dry = 0f
        // Plant-eaters get water from green food; hunters from the body water of what they eat, in any season.
        else g.dry += days * max(0f, 1f - s.waterFromFood * (if (s.carnivore) g.fed else 0.5f + 0.5f * land.green[p0]))
        val thirst = if (g.dry > s.daysWithoutWater) min(0.5f, 0.05f * (g.dry - s.daysWithoutWater)) else 0f
        // Crowded herds catch each other's diseases and parasites.
        val usual = Allometry.usualDensity(s)
        // Migratory herds live gathered: they bear densities on the move that would sicken a resident kind.
        val crowd = (kinNow[s.index.toLong() * hex.size + hw.placeHex[g.place]] ?: 0) / (hex.hexKm * hex.hexKm * 0.87).toFloat() / (HexFauna.CROWDED * usual * MIGRANTS_GATHER)
        val disease = if (s.human) peopleSickness(g) * years * (1f + HexFauna.MALNOURISHED * (1f - g.fed))
            else if (s.carnivore) 0f else HexFauna.DISEASE * crowd * years * (1f + HexFauna.MALNOURISHED * (1f - g.fed))
        g.wary *= WARY_KEEP
        // Packs without ground of their own die more: fights, injuries and hunger on others' ground.
        val homeless = if (s.carnivore && !s.human && g.home < 0) DISPERSAL_DEATHS * years else 0f
        // Eagles take the young of the smaller hoofed animals in the open.
        val eagle = if (!s.carnivore && s.massKg <= EAGLE_PREY_KG) small.eagleRisk(g.place) * years else 0f
        val (fireA, floodA, snowA) = fauna.calamities(s, g.place, weather, false)
        val (fireY, floodY, snowY) = fauna.calamities(s, g.place, weather, true)
        val pa = (healed * s.backgroundMortality * years + hunger + cold + disease + thirst + homeless + fireA + floodA + snowA).coerceIn(0f, 0.9f)
        val croc = if (s.carnivore) 0f else fauna.water.crocodileRisk(g.place, weather) * years
        val py = ((healed * s.youngMortality * years + 1.5f * (hunger + cold + thirst) + disease + homeless + eagle + fireY + floodY + snowY + croc + fouled + inbred) * (1f - ELDER_CARE * help)).coerceIn(0f, 0.95f)
        val da = binomial(g.adults, pa, r); val dy = binomial(g.young, py, r)
        // Elders die as the others do (their share of the adults who died), and of old age besides.
        var oldAge = 0
        if (band != null && g.adults > 0) {
            band.elders -= min(band.elders, (da * band.elders + r.nextInt(g.adults)) / g.adults)
            oldAge = binomial(band.elders, ELDER_DEATHS * years, r)
            band.elders -= oldAge
        }
        g.adults -= da + oldAge
        g.young -= dy
        // The dead lie where they fell, for the scavengers and the soil.
        var carrion = (da + oldAge + 0.3f * dy) * s.massKg
        // The week's deaths are shared among the causes by how much each weighed ("other": age, accidents, ordinary illness).
        val hazards = listOf("other" to healed * s.backgroundMortality * years, "hunger" to hunger, "cold" to cold, "thirst" to thirst, "disease" to disease, "fire" to fireY, "flood" to floodY, "snow" to snowY, "crocodiles" to croc, "homeless" to homeless, "camp fouling" to fouled, "inbreeding" to inbred)
        var left = da + dy; var weight = hazards.sumOf { it.second.toDouble() }.toFloat()
        val causes = ArrayList<Pair<String, Int>>()
        for ((name, h) in hazards) {
            if (left <= 0 || weight <= 0f) break
            val k = if (h >= weight) left else binomial(left, h / weight, r)
            if (k > 0) causes += name to k
            left -= k; weight -= h
        }
        if (left > 0) causes += "other" to left
        if (oldAge > 0) causes += "old age" to oldAge
        val up = binomial(g.young, years / s.yearsToMaturity, r)
        g.young -= up; g.adults += up
        band?.let { it.elders += binomial(g.adults - it.elders, years / ADULT_YEARS, r) }
        if (g.adults == 0) {
            // Young left without a grown animal of their kind die.
            val orphans = g.young
            carrion += 0.3f * orphans * s.massKg
            g.young = 0
            return Outcome(carrion, bornNow, da + oldAge + dy, causes, orphans, null, dy + orphans)
        }
        var split: Pair<Int, Int>? = null
        // Bands that feast together hold together longer.
        if (g.size > (if (band?.holds(Tradition.FEASTS) == true) (s.maxGroupSize * FEAST_SIZE).toInt() else s.maxGroupSize)) {
            split = (g.adults / 2) to (g.young / 2)
            g.adults -= split.first; g.young -= split.second
        }
        return Outcome(carrion, bornNow, da + oldAge + dy, causes, 0, split, dy)
    }
}

/**
 * Yearly deaths from crowding among people: fevers and worms where many camp near each other, by the
 * people in a band's hex and the six round it.
 */
private fun HexGroups.peopleSickness(g: HexGroup): Float {
    val h = hw.placeHex[g.place]
    var heads = 0
    for (k in -1 until 6) {
        val q = if (k < 0) h else hex.neighbour(h, k)
        if (q < 0) continue
        for (b in 0 until HexWorld.BANDS_M.size) { val p = hw.placeOf[b][q]; if (p >= 0) heads += headsHere[kindSlot[g.species.index]][p] }
    }
    val dens = heads / (7f * (hex.hexKm * hex.hexKm * 0.866).toFloat()) / PEOPLE_FULL_KM2
    return PEOPLE_SICK * dens * dens
}

/** Small herds join up, and lone hunters find mates (see [demography]). */
internal fun HexGroups.mergeAndPair() {
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
    // A hunter left on its own looks for a mate: lone adults of a kind within a few hexes pair up, and
    // the pair holds the ground of the one that had any.
    val lone = HashMap<Int, ArrayList<HexGroup>>()
    for (g in groups) if (g.alive && g.species.carnivore && !g.species.human && g.adults == 1) lone.getOrPut(g.species.index) { ArrayList() } += g
    for ((_, list) in lone) {
        val used = HashSet<Int>()
        for (a in list) {
            if (a.id in used) continue
            val b = list.firstOrNull { it.id != a.id && it.id !in used && hex.distanceKm(hw.placeHex[it.place], hw.placeHex[a.place]) <= MATE_SEARCH_KM } ?: continue
            used += a.id; used += b.id
            a.adults += b.adults; a.young += b.young
            a.fed = (a.fed + b.fed) / 2f; a.fat = (a.fat + b.fat) / 2f
            if (a.home < 0) a.home = b.home
            b.adults = 0; b.young = 0
        }
    }
}

/** Herds remember the place where they did best in each month. */
internal fun HexGroups.remember(date: Date) {
    val m = date.month
    for (g in groups) {
        if (g.species.carnivore && !g.species.human) continue
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
