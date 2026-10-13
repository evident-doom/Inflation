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
    if (top > 0f) for (m in 0 until 12) g.seasonQ[m] /= top
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
        val reach = if (weather.temperature[p] > 4f) HexFauna.REACH else HexFauna.REACH_DORMANT
        // Poor forage is made up for by eating more of it, as far as the gut holds.
        val fill = min(HexFauna.GUT_FILL, 1f / land.nourishment(p))
        val wantGrass = if (snowed) 0f else want * fill * access * d.grass / total
        val grass = land.consume(p, LifeForm.HERB, min(wantGrass, reach * land.herb[p]))
        val wantBrowse = want * fill * access * d.browse / total
        val browse = land.consume(p, LifeForm.SHRUB, min(wantBrowse, reach * land.shrub[p]))
        val fruit = food.take(food.fruit, p, want * access * d.fruit / total)
        val roots = food.take(food.roots, p, want * access * d.roots / total)
        // What grass and browse could not give, straw makes up as far as there is any, at what its gut gets out of it.
        val short = max(0f, want * (d.grass + d.browse) / total - (grass + browse) * land.nourishment(p))
        val straw = food.take(food.straw, p, short * HexFauna.GUT_FILL * access)
        small.dung[p] += HexSmallLife.DUNG * (grass + browse + fruit + roots + straw)
        // On the way it beat down the plants and dropped the seed of the fruit it ate.
        val band = hw.placeBand[p]
        for (h in g.path) {
            val q = hw.placeOf[band][h].takeIf { it >= 0 } ?: continue
            land.trample(q, g.size * s.massKg / 1000f)
            if (fruit > 0f) land.animalSeed[q] = min(1f, land.animalSeed[q] + fruit * land.areaKm2[p] * 1e6f / g.path.size / land.areaKm2[q] / HexFauna.SEED_KG)
        }
        // Grass and browse nourish fully only when green; fruit and roots keep their value.
        val got = (grass + browse) * land.nourishment(p) + fruit + roots + min(short, straw * HexFood.roughage(s))
        g.fed = (got / want.coerceAtLeast(1e-9f)).coerceIn(0f, 1f)
        land.grazing[p] += grass * 1e6f / days; land.browsing[p] += browse * 1e6f / days; land.rooting[p] += roots * 1e6f / days
    }
}

internal fun HexGroups.demography(weather: Weather, date: Date) {
    // Hunters remember where they hunted, and a pack long hungry on its ground gives it up.
    for (g in groups) {
        if (!g.alive || !g.species.carnivore) continue
        val h = hw.placeHex[g.place]
        if (g.recent[0] != h) { for (i in g.recent.size - 1 downTo 1) g.recent[i] = g.recent[i - 1]; g.recent[0] = h }
        g.lean = if (g.fed < 0.5f) g.lean + 1 else 0
        if (g.lean >= GIVE_UP_WEEKS) g.home = -1
    }
    val kinNow = HashMap<Long, Int>()
    for (g in groups) if (g.alive) kinNow.merge(g.species.index.toLong() * hex.size + hw.placeHex[g.place], g.size, Int::plus)
    val born = ArrayList<HexGroup>()
    for (g in groups) {
        if (!g.alive) continue
        val s = g.species
        // Reserves carry a group through lean weeks.
        // Reserves carry a group through lean weeks; it lays on most in late summer and autumn.
        if (g.fed < 1f) { val draw = min(g.fat, (1f - g.fed) * days); g.fat -= draw; g.fed = min(1f, g.fed + draw / days) }
        else g.fat = min(s.maxFatDays, g.fat + (if (date.month in 4..7) AUTUMN_FAT else 0.3f) * days)
        if (g.birthMonth < 0) g.birthMonth = s.birthMonth
        // Once a year its births move a month towards the months it fed best in (the birth month and the two after).
        if (date.month == 0 && date.dayOfMonth <= days && !s.carnivore) {
            fun window(m0: Int) = (0 until 3).sumOf { g.seasonQ[(m0 + it + 12) % 12].toDouble() }
            val m = g.birthMonth
            if (window(m - 1) > window(m) + 0.1 && window(m - 1) >= window(m + 1)) g.birthMonth = (m + 11) % 12
            else if (window(m + 1) > window(m) + 0.1) g.birthMonth = (m + 1) % 12
        }
        val inSeason = (date.month - g.birthMonth + 12) % 12 < s.birthMonths
        if (inSeason && g.adults >= 2) {
            val births = if (s.packLitter > 0) {
                if (date.dayOfMonth <= days && date.month == g.birthMonth && g.home >= 0) (s.packLitter * g.fed + rnd.nextFloat()).toInt() else 0
            } else {
                val stepsInSeason = s.birthMonths * Date.DAYS_PER_MONTH / days.toFloat()
                binomial(g.adults / 2, s.youngPerFemale * s.littersPerYear * g.fed / stepsInSeason)
            }
            g.young += births
        }
        val hunger = if (g.fed < 0.5f) (0.5f - g.fed) * 0.3f else 0f
        val cold = discomfort(s, g.place, weather) * 0.02f
        // Thirst: drinks where there is water; otherwise days without water mount, eased by the water in green food.
        val p0 = g.place
        if (land.water[p0] >= 0.05f || (s.maxWaterKm.isInfinite() && land.waterKm[p0] <= 1.5f)) g.dry = 0f
        else g.dry += days * max(0f, 1f - s.waterFromFood * (0.5f + 0.5f * land.green[p0]))
        val thirst = if (g.dry > s.daysWithoutWater) min(0.5f, 0.05f * (g.dry - s.daysWithoutWater)) else 0f
        // Crowded herds catch each other's diseases and parasites.
        val usual = if (s.startKm2PerAnimal > 0f) 1f / s.startKm2PerAnimal else 0.1f
        val crowd = (kinNow[s.index.toLong() * hex.size + hw.placeHex[g.place]] ?: 0) / (hex.hexKm * hex.hexKm * 0.87).toFloat() / (HexFauna.CROWDED * usual)
        val disease = if (s.carnivore) 0f else HexFauna.DISEASE * crowd * crowd * years
        g.wary *= WARY_KEEP
        // Packs without ground of their own die more: fights, injuries and hunger on others' ground.
        val homeless = if (s.carnivore && g.home < 0) DISPERSAL_DEATHS * years else 0f
        // Eagles take the young of the smaller hoofed animals in the open.
        val eagle = if (!s.carnivore && s.massKg <= EAGLE_PREY_KG) small.eagleRisk(g.place) * years else 0f
        val (fireA, floodA, snowA) = fauna.calamities(s, g.place, weather, false)
        val (fireY, floodY, snowY) = fauna.calamities(s, g.place, weather, true)
        val pa = (s.backgroundMortality * years + hunger + cold + disease + thirst + homeless + fireA + floodA + snowA).coerceIn(0f, 0.9f)
        val croc = if (s.carnivore) 0f else fauna.water.crocodileRisk(g.place, weather) * years
        val py = (s.youngMortality * years + 1.5f * (hunger + cold + thirst) + disease + homeless + eagle + fireY + floodY + snowY + croc).coerceIn(0f, 0.95f)
        val da = binomial(g.adults, pa); val dy = binomial(g.young, py)
        g.adults -= da; g.young -= dy
        // The dead lie where they fell, for the scavengers and the soil.
        small.carrion[g.place] += (da + 0.3f * dy) * s.massKg / (land.areaKm2[g.place] * 1e6f)
        if (da + dy > 0) {
            val causes = listOf("hunger" to hunger, "cold" to cold, "thirst" to thirst, "fire" to fireY, "flood" to floodY, "snow" to snowY, "crocodiles" to croc)
            val main = causes.maxBy { it.second }
            deaths.merge("${s.name}:${if (main.second > 0f) main.first else "other"}", da + dy, Int::plus)
        }
        val up = binomial(g.young, years / s.yearsToMaturity)
        g.young -= up; g.adults += up
        if (g.adults == 0) { g.young = 0; continue }
        if (g.size > s.maxGroupSize) {
            val c = HexGroup(nextId++, s, g.place, g.adults / 2, g.young / 2)
            c.tag = g.tag
            g.adults -= c.adults; g.young -= c.young
            c.fed = g.fed; c.fat = g.fat; c.tag = g.tag; c.dry = g.dry; c.wary = g.wary; c.birthMonth = g.birthMonth
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
internal fun HexGroups.remember(date: Date) {
    val m = date.month
    for (g in groups) {
        if (g.species.carnivore) continue
        if (g.seasonPlace[m] < 0 || g.fed > g.seasonQ[m] + 0.05f) { g.seasonPlace[m] = g.place; g.seasonQ[m] = g.fed }
        else if (g.seasonPlace[m] == g.place) g.seasonQ[m] += 0.3f * (g.fed - g.seasonQ[m])
    }
}
