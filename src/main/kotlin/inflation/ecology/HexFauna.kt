package inflation.ecology

import inflation.world.Date
import inflation.world.HexLand
import inflation.world.HexMap
import inflation.world.HexWorld
import inflation.world.LifeForm
import inflation.world.Weather
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * The resident plant-eaters on the hexes, as densities: adults and young per km² of each place
 * (a height band of a hex). Each week, in each place:
 *
 * - The herds of every kind graze and browse the place's plants, sharing them by what each wants
 *   of grass, browse and fruit; snow deeper than a kind can dig through hides the grass.
 * - Young are born in the season, and grow up over the years to maturity.
 * - Animals die of age and accident, faster when hungry, in cold beyond what their coat allows,
 *   and the young most.
 * - Within a hex they shift between its height bands towards better feeding and kinder weather,
 *   up the mountain in summer and down in winter.
 * - When hungry or crowded, some spill over into the places around, over the hex borders as their
 *   way of travelling allows (climbers over crags, swimmers across rivers), towards better feeding.
 *
 * They stay in their country: nothing here wanders far, but over the years a kind spreads into
 * empty ground that suits it, and withdraws from ground that fails it.
 */
class HexFauna(private val hw: HexWorld, private val land: HexLand, private val days: Int) {
    private val hex = hw.hex
    val n = land.n
    /** The resident kinds, and for each, adults and young per km² of each place. */
    val kinds = Catalogue.herbivores.filter { Catalogue.resident(it) }
    val adults = Array(kinds.size) { FloatArray(n) }
    val young = Array(kinds.size) { FloatArray(n) }
    /** Last week's share of its wants each kind found in each place, 0..1. */
    val fed = Array(kinds.size) { FloatArray(n) { 1f } }
    /** The soil life and small animals the herds live among. */
    val small = HexSmallLife(hw, land, days)
    /** Each kind's sense of where better country lies. */
    val bearings = HexBearings(hw, kinds, { s, p, m ->
        val food = foodFor(s, p) * min(1.5f, suits(s, p)) / 1.5f
        food / (food + 0.3f) * tether(s, p) * monthFit(s, p, m)
    }, { s -> val k = kinds.indexOf(s); (0 until n).any { adults[k][it] > 0f } })
    /**
     * Each kind's month births begin in each place, which drifts year by year towards the months
     * that feed mothers and young best; how well fed it was in each month lately (place × 12 + month).
     */
    val birthStart = Array(kinds.size) { k -> ByteArray(n) { kinds[k].birthMonth.toByte() } }
    private val fedByMonth = Array(kinds.size) { FloatArray(n * 12) { 0.7f } }
    /** For kinds that sleep through the winter: how well fed they went to sleep, 0..1. */
    private val autumnFed = Array(kinds.size) { FloatArray(n) { 0.8f } }
    /** Fruit, roots and straw as stocks. */
    val food = HexFood(land, small, days)
    /** Carcasses left this week in each place, kg (deaths other than kills, which the hunters count). */
    val carcassKg = FloatArray(n)
    /** Deaths this week by cause, animals. */
    val deaths = java.util.TreeMap<String, Double>()

    private val years = days / Date.DAYS_PER_YEAR.toFloat()
    /** How each kind travels between places. */
    private val travel = kinds.map { Catalogue.travel(it) }

    init {
        // Start near what the land can carry: the kind's usual density where its food grows.
        for ((k, s) in kinds.withIndex()) {
            val dens = if (s.startKm2PerAnimal > 0f) 1f / s.startKm2PerAnimal else 0.5f
            for (p in 0 until n) {
                val food = foodFor(s, p)
                adults[k][p] = dens * min(1f, food / FOOD_FULL) * suits(s, p) * climateSuits(s, p) * 0.8f
                young[k][p] = 0.2f * adults[k][p]
            }
        }
    }

    /**
     * How well the climate of place [p] suits [s] over the year, 0..1: 1 where every month's normal
     * temperature is within what its coat bears, falling off with each degree beyond.
     */
    fun climateSuits(s: Species, p: Int): Float {
        var worst = 0f
        for (m in 0 until 12) worst = max(worst, s.discomfortC(hw.placeTemp(p, m), m))
        return exp(-worst / 5f)
    }

    /** How well the normal temperatures of month [m] and the next at place [p] suit [s]'s coat, 0..1. */
    fun monthFit(s: Species, p: Int, m: Int): Float {
        val m1 = (m + 1) % 12
        return exp(-(s.discomfortC(hw.placeTemp(p, m), m) + s.discomfortC(hw.placeTemp(p, m1), m1)) / 10f)
    }

    /** Whether [s] is asleep in its winter den in place [p]: kinds that hibernate, in the cold. */
    fun asleep(s: Species, p: Int, weather: Weather) = s.hibernates && weather.temperature[p] < HIBERNATE_C

    /**
     * Once a year: in each place, a kind's births move a month earlier or later where that puts them
     * in better-fed months for the mothers and the young (the birth month and the two after it).
     */
    private fun shiftBreeding() {
        for ((k, s) in kinds.withIndex()) {
            val fm = fedByMonth[k]; val start = birthStart[k]
            for (p in 0 until n) {
                if (adults[k][p] <= 0f) continue
                fun window(m0: Int) = (0 until 3).sumOf { fm[p * 12 + (m0 + it + 12) % 12].toDouble() }
                val m = start[p].toInt()
                val now = window(m); val earlier = window(m - 1); val later = window(m + 1)
                if (earlier > now + BREED_SHIFT_GAIN && earlier >= later) start[p] = ((m + 11) % 12).toByte()
                else if (later > now + BREED_SHIFT_GAIN) start[p] = ((m + 1) % 12).toByte()
            }
        }
    }

    /**
     * Shares of [s]'s adults and young ([young]) that die this week in place [p] of fire, flood and
     * deep snow: fire catches the slow and the young on the share of the place that burns; a high
     * flood drowns some on the floodplain (not the kinds that live in the water); snow far deeper
     * than a kind can dig through starves and exhausts it. Returned as fire, flood and snow.
     */
    fun calamities(s: Species, p: Int, weather: Weather, young: Boolean): Triple<Float, Float, Float> {
        val fire = land.burning[p] * (if (young) FIRE_YOUNG else FIRE_ADULT)
        val flood = if (s.maxWaterKm.isFinite() || land.flood[p] <= FLOOD_DROWN_M) 0f
            else land.floodShare[p] * (if (young) FLOOD_YOUNG else FLOOD_ADULT)
        val over = weather.snowpack[p] / s.snowLimitMm
        val snow = if (over <= 2f) 0f else min(0.1f, SNOW_DEATHS * (over - 2f)) * (if (young) 3f else 1f)
        return Triple(fire, flood, snow)
    }

    /**
     * How well place [p] suits [s] by the ground it prefers, about 0.2..2.5: ibex the crags, roe deer
     * cover, boar wet thickets, aurochs open ground; 1 for a kind with no preference.
     */
    fun suits(s: Species, p: Int): Float {
        val h = s.habitat
        val cover = land.cover(p)
        val wet = land.floodShare[p]
        return exp(HABITAT * (h.cover * (cover - 0.5f) + h.rugged * (land.ruggedness[p] - 0.3f) + h.wet * (wet - 0.2f)))
    }

    /**
     * Share of place [p]'s food that [s] can use for its water: grazers that must drink every day or
     * two graze out from water and back, and leave the far country to those that live on the water
     * in their food (gazelle, oryx, camels). Kinds bound to water (hippos) use only what lies by it.
     */
    fun tether(s: Species, p: Int): Float {
        if (s.maxWaterKm.isFinite()) return land.water[p]
        val reachKm = s.dailyTravelKm * s.daysWithoutWater / 4f * (1f + 10f * s.waterFromFood * s.waterFromFood)
        val r = land.waterKm[p] / reachKm
        return 1f / (1f + r * r)
    }

    /**
     * Food for [s] in place [p] from its diet, as standing plants, kg/m²: grass from herbs, browse
     * from shrubs and trees, and the fruit and roots stocks (counted as the standing plants that
     * would give as much in a week); less on cliffs it cannot climb.
     */
    fun foodFor(s: Species, p: Int): Float {
        val d = s.diet
        val total = d.grass + d.browse + d.fruit + d.roots
        if (total <= 0f) return 0f
        val plants = d.grass * land.herb[p] + d.browse * (land.shrub[p] + BROWSE_OF_TREE * land.tree[p]) + (d.fruit * food.fruit[p] + d.roots * food.roots[p]) / REACH
        return plants / total * HexFood.access(s, land.ruggedness[p])
    }

    fun count(k: Int): Double = (0 until n).sumOf { ((adults[k][it] + young[k][it]) * land.areaKm2[it]).toDouble() }

    /** One week, with the weather on the same places. */
    fun step(weather: Weather, date: Date) {
        deaths.clear()
        carcassKg.fill(0f)
        if (bearings.month != date.month) {
            bearings.update(date.month)
            if (date.month == 0) shiftBreeding()
        }
        food.step(weather)
        graze(weather)
        breedAndDie(weather, date)
        shiftBands(weather)
        spill()
        small.step(weather, carcassKg)
    }

    /**
     * Every kind takes its share of the place's grass, browse, fruit and roots, in proportion to
     * what each wants and can get at (cliffs keep all but climbers off part of it); a shortfall of
     * grass and browse is made up from the standing straw, at what each kind's gut gets out of it.
     */
    private fun graze(weather: Weather) {
        java.util.stream.IntStream.range(0, n).parallel().forEach { p ->
            var wantGrass = 0f; var wantBrowse = 0f; var wantFruit = 0f; var wantRoots = 0f
            val want = FloatArray(kinds.size)
            val reachable = FloatArray(kinds.size)
            for ((k, s) in kinds.withIndex()) {
                val heads = adults[k][p] + 0.5f * young[k][p]
                // Bears asleep through the cold eat nothing.
                want[k] = if (asleep(s, p, weather)) 0f else heads * s.dailyIntakeKg * days / 1e6f // kg per m² this week
                reachable[k] = HexFood.access(s, land.ruggedness[p])
                val w = want[k] * reachable[k]
                val d = s.diet; val total = (d.grass + d.browse + d.fruit + d.roots).coerceAtLeast(1e-6f)
                val snowed = weather.snowpack[p] > s.snowLimitMm
                wantGrass += w * d.grass / total * (if (snowed) 0f else 1f)
                wantBrowse += w * d.browse / total
                wantFruit += w * d.fruit / total
                wantRoots += w * d.roots / total
            }
            // What can be eaten this week without eating the place bare, kg per m²: new growth in the
            // growing season, far less of the dry standing stuff in the cold.
            val reach = if (weather.temperature[p] > 4f) REACH else REACH_DORMANT
            val grass = reach * land.herb[p]; val browse = reach * (land.shrub[p] + BROWSE_OF_TREE * land.tree[p])
            val gShare = if (wantGrass > 0f) min(1f, grass / wantGrass) else 1f
            val bShare = if (wantBrowse > 0f) min(1f, browse / wantBrowse) else 1f
            val fShare = if (wantFruit > 0f) min(1f, food.fruit[p] / wantFruit) else 1f
            val rShare = if (wantRoots > 0f) min(1f, food.roots[p] / wantRoots) else 1f
            // The shortfall of grass and browse, to be made up with straw as far as there is any.
            var wantStraw = 0f
            val short = FloatArray(kinds.size)
            for ((k, s) in kinds.withIndex()) {
                if (want[k] <= 0f) continue
                val d = s.diet; val total = (d.grass + d.browse + d.fruit + d.roots).coerceAtLeast(1e-6f)
                val snowed = weather.snowpack[p] > s.snowLimitMm
                val g = if (snowed) 0f else gShare
                short[k] = want[k] * reachable[k] * (d.grass / total * (1f - g) + d.browse / total * (1f - bShare))
                wantStraw += short[k]
            }
            val sShare = if (wantStraw > 0f) min(1f, food.straw[p] / wantStraw) else 1f
            for ((k, s) in kinds.withIndex()) {
                val d = s.diet; val total = (d.grass + d.browse + d.fruit + d.roots).coerceAtLeast(1e-6f)
                val snowed = weather.snowpack[p] > s.snowLimitMm
                // A kind that cannot reach the grass under snow falls back on browse and straw.
                val g = if (snowed) 0f else d.grass / total * gShare
                // Grass and browse nourish fully only when green; fruit and roots keep their value; straw
                // gives what the kind's gut gets out of it.
                val strawPart = if (want[k] > 0f) short[k] * sShare / want[k] * HexFood.roughage(s) else 0f
                val got = ((g + d.browse / total * bShare) * land.nourishment(p) + d.fruit / total * fShare + d.roots / total * rShare) * reachable[k] + strawPart
                // Far from water, a kind that must drink often can only use the ground within reach of it.
                fed[k][p] = if (asleep(s, p, weather)) autumnFed[k][p] else (got * tether(s, p)).coerceIn(0f, 1f)
                val fm = fedByMonth[k]; val i = p * 12 + weather.date.month
                fm[i] += (fed[k][p] - fm[i]) * FED_MEMORY
                // Laying on fat for the winter: how well fed through late summer and autumn.
                if (s.hibernates && weather.date.month in 4..8 && !asleep(s, p, weather)) autumnFed[k][p] += (fed[k][p] - autumnFed[k][p]) * 0.2f
            }
            val eatenGrass = min(grass, wantGrass); val eatenBrowse = min(browse, wantBrowse)
            val dug = food.take(food.roots, p, wantRoots)
            val ate = food.take(food.fruit, p, wantFruit)
            val eaten = land.consume(p, LifeForm.HERB, eatenGrass) +
                land.consume(p, LifeForm.SHRUB, eatenBrowse * 0.7f) + land.consume(p, LifeForm.TREE, eatenBrowse * 0.3f) +
                ate + dug + food.take(food.straw, p, wantStraw)
            // Fruit-eaters drop the seed where they go: shrubs and trees come up away from any parent.
            land.animalSeed[p] = min(1f, land.animalSeed[p] * SEED_KEEP + ate * 1e6f / SEED_KG)
            // Their feet beat down the plants and wear paths: tonnes of animal going about the place.
            var tonnes = 0f
            for ((k, s) in kinds.withIndex()) tonnes += (adults[k][p] + young[k][p]) * s.massKg / 1000f
            land.trample(p, RESIDENT_TRAMPLE * tonnes * land.areaKm2[p])
            small.dung[p] += HexSmallLife.DUNG * eaten
            land.grazing[p] = eatenGrass * 1e6f / days; land.browsing[p] = eatenBrowse * 1e6f / days
            // Rooting churns the ground: kg of roots dug per km² a day.
            land.rooting[p] = dug * 1e6f / days
        }
    }

    private fun breedAndDie(weather: Weather, date: Date) {
        val stepsPerYear = Date.DAYS_PER_YEAR / days.toFloat()
        val dead = Array(kinds.size) { DoubleArray(8) } // age, hunger, cold, disease, eagles, fire, flood, snow
        for ((k, s) in kinds.withIndex()) {
            val start = birthStart[k]
            val stepsInSeason = s.birthMonths * Date.DAYS_PER_MONTH / days.toFloat()
            val grow = years / s.yearsToMaturity
            val a = adults[k]; val y = young[k]; val f = fed[k]
            for (p in 0 until n) {
                if (a[p] + y[p] <= 0f) continue
                val area = land.areaKm2[p]
                // Births: half the adults are mothers; a hungry mother bears and keeps fewer.
                val inSeason = (date.month - start[p] + 12) % 12 < s.birthMonths
                if (inSeason) y[p] += 0.5f * a[p] * s.youngPerFemale * s.littersPerYear * f[p] / stepsInSeason
                // Deaths.
                val hunger = if (f[p] < 0.6f) (0.6f - f[p]) * HUNGER_DEATHS else 0f
                val t = weather.temperature[p]
                val sleeping = asleep(s, p, weather)
                val cold = if (sleeping) 0f else s.coldC(t, date.month) / 10f * COLD_DEATHS
                // Crowding breeds disease and parasites: the denser the herds, the more die of them.
                val usual = if (s.startKm2PerAnimal > 0f) 1f / s.startKm2PerAnimal else 0.5f
                val crowd = (a[p] + y[p]) / (CROWDED * usual)
                val disease = DISEASE * crowd * crowd * years
                val (fireA, floodA, snowA0) = calamities(s, p, weather, false)
                val (fireY, floodY, snowY0) = calamities(s, p, weather, true)
                val snowA = if (sleeping) 0f else snowA0; val snowY = if (sleeping) 0f else snowY0
                val pa = (s.backgroundMortality * years + hunger + cold + disease + fireA + floodA + snowA).coerceIn(0f, 0.9f)
                // Eagles take the young of the smaller hoofed animals in the open.
                val eagle = if (s.massKg <= EAGLE_PREY_KG) small.eagleRisk(p) * years else 0f
                val py = (s.youngMortality * years + 1.5f * hunger + 1.5f * cold + disease + eagle + fireY + floodY + snowY).coerceIn(0f, 0.95f)
                val da = a[p] * pa; val dy = y[p] * py
                dead[k][0] += (a[p] * s.backgroundMortality * years + y[p] * s.youngMortality * years) * area
                dead[k][1] += ((a[p] + 1.5f * y[p]) * hunger) * area
                dead[k][2] += ((a[p] + 1.5f * y[p]) * cold) * area
                dead[k][3] += ((a[p] + y[p]) * disease) * area
                dead[k][4] += y[p] * eagle * area
                dead[k][5] += (a[p] * fireA + y[p] * fireY) * area
                dead[k][6] += (a[p] * floodA + y[p] * floodY) * area
                dead[k][7] += (a[p] * snowA + y[p] * snowY) * area
                a[p] -= da; y[p] -= dy
                carcassKg[p] += (da + 0.3f * dy) * area * s.massKg
                // Growing up.
                val up = y[p] * grow
                y[p] -= up; a[p] += up
                if (a[p] + y[p] < EXTINCT) { a[p] = 0f; y[p] = 0f }
            }
        }
        for ((k, s) in kinds.withIndex()) {
            deaths["${s.name}:age"] = dead[k][0]; deaths["${s.name}:hunger"] = dead[k][1]; deaths["${s.name}:cold"] = dead[k][2]; deaths["${s.name}:disease"] = dead[k][3]; deaths["${s.name}:eagles"] = dead[k][4]
            deaths["${s.name}:fire"] = dead[k][5]; deaths["${s.name}:flood"] = dead[k][6]; deaths["${s.name}:snow"] = dead[k][7]
        }
    }

    /** How good a place is for kind [k] this week: food per head and the weather against its comfort. */
    private fun appeal(k: Int, p: Int, weather: Weather): Float {
        val s = kinds[k]
        val heads = adults[k][p] + young[k][p]
        // Weather now, and as far as the kind senses it, what is coming.
        val m = weather.date.month
        val discomfort = ((1f - s.foresight) * s.discomfortC(weather.temperature[p], m) + s.foresight * s.discomfortC(weather.forecastTemp(p), m)) / 10f
        val snowed = if (weather.snowpack[p] > s.snowLimitMm) 0.3f else 1f
        return foodFor(s, p) * suits(s, p) * tether(s, p) * snowed / (1f + heads / CROWD) / (1f + discomfort)
    }

    /** Within a hex, a share of each kind moves between neighbouring height bands towards the better one. */
    private fun shiftBands(weather: Weather) {
        val bands = HexWorld.BANDS_M.size
        for (k in kinds.indices) {
            val a = adults[k]; val y = young[k]
            for (h in 0 until hex.size) for (b in 0 until bands - 1) {
                val lo = hw.placeOf[b][h]; val hi = hw.placeOf[b + 1][h]
                if (lo < 0 || hi < 0) continue
                val al = appeal(k, lo, weather); val ah = appeal(k, hi, weather)
                if (al <= 0f && ah <= 0f) continue
                val (from, to) = if (ah > al) lo to hi else hi to lo
                val gain = (max(al, ah) - min(al, ah)) / max(al, ah)
                val share = BAND_SHIFT * gain
                if (share <= 0f) continue
                val ma = a[from] * share * land.areaKm2[from]; val my = y[from] * share * land.areaKm2[from]
                a[from] -= ma / land.areaKm2[from]; y[from] -= my / land.areaKm2[from]
                a[to] += ma / land.areaKm2[to]; y[to] += my / land.areaKm2[to]
            }
        }
    }

    /**
     * Hungry or crowded animals spill into neighbouring hexes, at about the same height, towards
     * better feeding; the harder the border is for their way of travelling, the fewer cross it.
     */
    private fun spill() {
        for ((k, s) in kinds.withIndex()) {
            val a = adults[k]; val y = young[k]; val f = fed[k]
            val cross = hex.crossings[travel[k].ordinal]
            val moveA = FloatArray(n); val moveY = FloatArray(n)
            for (p in 0 until n) {
                if (a[p] + y[p] <= 0f) continue
                val need = (1f - f[p]).coerceIn(0f, 1f)
                val h = hw.placeHex[p]; val b = hw.placeBand[p]
                // Hungry ones leave, and so do those whose country does not suit them, towards better.
                val leave = SPILL_BASE + SPILL_HUNGER * need + SPILL_MISFIT * bearings.strength(k, h)
                // Where they could go: the same height band in each neighbouring hex, weighed by food and how easy the border is.
                var total = 0f
                val ws = FloatArray(6)
                for (d in 0 until 6) {
                    val nh = hex.neighbour(h, d)
                    if (nh < 0) continue
                    val q = hw.placeOf[b][nh]
                    if (q < 0) continue
                    val e = cross[h * 6 + d]
                    if (!e.isFinite()) continue
                    val ease = hex.hexKm.toFloat() / e
                    ws[d] = ease * (foodFor(s, q) * land.nourishment(q) * suits(s, q) * tether(s, q) / (1f + (a[q] + y[q]) / CROWD) + 1e-4f) * max(0.05f, 1f + BEARING_SPILL * bearings.along(k, h, nh, hex.hexKm))
                    total += ws[d]
                }
                if (total <= 0f) continue
                val goA = a[p] * leave * land.areaKm2[p]; val goY = y[p] * leave * land.areaKm2[p]
                moveA[p] -= goA; moveY[p] -= goY
                for (d in 0 until 6) {
                    if (ws[d] <= 0f) continue
                    val q = hw.placeOf[b][hex.neighbour(h, d)]
                    moveA[q] += goA * ws[d] / total; moveY[q] += goY * ws[d] / total
                }
            }
            for (p in 0 until n) { a[p] = max(0f, a[p] + moveA[p] / land.areaKm2[p]); y[p] = max(0f, y[p] + moveY[p] / land.areaKm2[p]) }
        }
    }

    companion object {
        /** Share of the standing plants a week's grazing can take without eating the place bare. */
        const val REACH = 0.01f
        /** How strongly a kind is drawn to the ground it prefers. */
        const val HABITAT = 3f
        const val REACH_DORMANT = 0.002f
        /** Density, as a multiple of a kind's usual, at which disease kills a [DISEASE] share a year. */
        const val CROWDED = 3f
        const val DISEASE = 0.3f
        /** Share of tree biomass that is browse within reach, and that is fruit and mast. */
        const val BROWSE_OF_TREE = 0.03f
        const val FRUIT_OF_TREE = 0.01f
        /** Food, kg/m², at which a place carries a kind's usual density. */
        const val FOOD_FULL = 0.3f
        /** Extra share dying in a week per unit of hunger below 0.6 fed; per 10 °C of cold beyond the coat. */
        const val HUNGER_DEATHS = 0.08f
        const val COLD_DEATHS = 0.02f
        /** Below this density, per km², a kind is gone from a place. */
        const val EXTINCT = 1e-4f
        /** Density per km² at which crowding halves a place's appeal. */
        const val CROWD = 10f
        /** Weekly share moving between bands at full advantage. */
        const val BAND_SHIFT = 0.15f
        /** Weekly share spilling to neighbouring hexes anyway, and more when hungry. */
        const val SPILL_BASE = 0.005f
        const val SPILL_HUNGER = 0.1f
        /** Seed carried by fruit-eaters: kg of fruit eaten per km² that seeds a place fully; share kept from week to week. */
        const val SEED_KG = 200f
        const val SEED_KEEP = 0.9f
        /** Share of a resident herd's weight that tramples its place in a week, against a herd passing through. */
        const val RESIDENT_TRAMPLE = 0.1f
        /** Hibernators sleep below this temperature, °C. */
        const val HIBERNATE_C = 0f
        /** How fast the memory of each month's feeding follows the year (per week in that month). */
        const val FED_MEMORY = 0.1f
        /** How much better fed (summed over three months) a month earlier or later must be to move the births. */
        const val BREED_SHIFT_GAIN = 0.1
        /** Fire: share of adults and of young caught on ground that burns entirely. */
        const val FIRE_ADULT = 0.05f
        const val FIRE_YOUNG = 0.3f
        /** Floods: depth over the floodplain that drowns animals, m; share of adults and of young drowned on a fully flooded place. */
        const val FLOOD_DROWN_M = 2f
        const val FLOOD_ADULT = 0.05f
        const val FLOOD_YOUNG = 0.2f
        /** Deep snow: share a week dying per snow depth (in multiples of what the kind can dig through) beyond two. */
        const val SNOW_DEATHS = 0.02f
        /** Share a week that leaves country that does not suit it, at full strength of the bearing; how strongly the bearing steers them. */
        const val SPILL_MISFIT = 0.05f
        const val BEARING_SPILL = 3f
    }
}
