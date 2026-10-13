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
    val coat = if (s.winterCoat) 10f else 0f
    var top = 0f
    for (m in 0 until 12) {
        var best = -1; var bestScore = 0f
        for (p in near) {
            val t = hw.placeTemp(p, m)
            val h = hw.placeHex[p]
            val grow = ((t - 2f) / 13f).coerceIn(0f, 1f) * (hw.precip[m][h] / hw.pet[m][h].coerceAtLeast(1f)).coerceIn(0.1f, 1f)
            val discomfort = (max(0f, s.comfortC.start - coat - t) + max(0f, t - s.comfortC.endInclusive)) / 10f
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

/** Herds eat in the place they end up in, after the resident herds have had theirs. */
internal fun HexGroups.graze(weather: Weather) {
    for (g in groups) {
        val s = g.species
        if (!g.alive || s.carnivore) continue
        val p = g.place
        val want = g.dailyNeedKg * days / 1e6f / land.areaKm2[p] // kg per m² of the place
        val snowed = weather.snowpack[p] > s.snowLimitMm
        val d = s.diet; val total = (d.grass + d.browse + d.fruit + d.roots).coerceAtLeast(1e-6f)
        val reach = if (weather.temperature[p] > 4f) HexFauna.REACH else HexFauna.REACH_DORMANT
        val grass = if (snowed) 0f else min(want * d.grass / total, reach * land.herb[p])
        val browse = min(want * d.browse / total, reach * land.shrub[p])
        // Fruit and roots, as far as the place has them, off the trees and the herb layer.
        val fruitWant = want * (d.fruit + d.roots) / total * min(1f, foodFor(s, p) / 0.3f)
        val fromTrees = HexFauna.FRUIT_OF_TREE * land.tree[p] * small.fruitSet(p); val fromHerbs = 0.3f * land.herb[p]
        val treeShare = if (fromTrees + fromHerbs > 0f) fromTrees / (fromTrees + fromHerbs) else 0f
        val fruitGot = land.consume(p, LifeForm.TREE, fruitWant * treeShare) + land.consume(p, LifeForm.HERB, fruitWant * (1f - treeShare))
        val eaten = land.consume(p, LifeForm.HERB, grass) + land.consume(p, LifeForm.SHRUB, browse) + fruitGot
        small.dung[p] += HexSmallLife.DUNG * eaten
        // Grass and browse nourish fully only when green; fruit and roots keep their value.
        val got = (grass + browse) * land.nourishment(p) + fruitGot
        g.fed = (got / want.coerceAtLeast(1e-9f)).coerceIn(0f, 1f)
        land.grazing[p] += grass * 1e6f / days; land.browsing[p] += browse * 1e6f / days
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
        if (g.fed < 1f) { val draw = min(g.fat, (1f - g.fed) * days); g.fat -= draw; g.fed = min(1f, g.fed + draw / days) }
        else g.fat = min(s.maxFatDays, g.fat + 0.3f * days)
        val inSeason = (date.month - s.birthMonth + 12) % 12 < s.birthMonths
        if (inSeason && g.adults >= 2) {
            val births = if (s.packLitter > 0) {
                if (date.dayOfMonth <= days && date.month == s.birthMonth && g.home >= 0) (s.packLitter * g.fed + rnd.nextFloat()).toInt() else 0
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
        val pa = (s.backgroundMortality * years + hunger + cold + disease + thirst + homeless).coerceIn(0f, 0.9f)
        val py = (s.youngMortality * years + 1.5f * (hunger + cold + thirst) + disease + homeless + eagle).coerceIn(0f, 0.95f)
        val da = binomial(g.adults, pa); val dy = binomial(g.young, py)
        g.adults -= da; g.young -= dy
        // The dead lie where they fell, for the scavengers and the soil.
        small.carrion[g.place] += (da + 0.3f * dy) * s.massKg / (land.areaKm2[g.place] * 1e6f)
        if (da + dy > 0) deaths.merge("${s.name}:${when { thirst > max(hunger, cold) -> "thirst"; hunger > cold -> "hunger"; else -> "other" }}", da + dy, Int::plus)
        val up = binomial(g.young, years / s.yearsToMaturity)
        g.young -= up; g.adults += up
        if (g.adults == 0) { g.young = 0; continue }
        if (g.size > s.maxGroupSize) {
            val c = HexGroup(nextId++, s, g.place, g.adults / 2, g.young / 2)
            c.tag = g.tag
            g.adults -= c.adults; g.young -= c.young
            c.fed = g.fed; c.fat = g.fat; c.tag = g.tag; c.dry = g.dry; c.wary = g.wary
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
