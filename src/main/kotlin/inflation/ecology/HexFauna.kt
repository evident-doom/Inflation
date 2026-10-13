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
    /** Carcasses left this week in each place, kg (deaths other than kills, which the hunters count). */
    val carcassKg = FloatArray(n)
    /** Deaths this week by cause, animals. */
    val deaths = java.util.TreeMap<String, Double>()

    private val years = days / Date.DAYS_PER_YEAR.toFloat()
    /** How each kind travels between places. */
    private val travel = kinds.map { if (it in Routes.CLIMBERS) HexMap.Travel.CLIMB else if (it.diet.grass > 0f && it.massKg > 60f) HexMap.Travel.SWIM else HexMap.Travel.FOOT }

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
     * How well place [p] suits [s] by the ground it prefers, about 0.2..2.5: ibex the crags, roe deer
     * cover, boar wet thickets, aurochs open ground; 1 for a kind with no preference.
     */
    /**
     * How well the climate of place [p] suits [s] over the year, 0..1: 1 where every month's normal
     * temperature is within what its coat bears, falling off with each degree beyond.
     */
    fun climateSuits(s: Species, p: Int): Float {
        val coat = if (s.winterCoat) 10f else 0f
        var worst = 0f
        for (m in 0 until 12) {
            val t = hw.placeTemp(p, m)
            worst = max(worst, max(0f, s.comfortC.start - coat - t) + max(0f, t - s.comfortC.endInclusive))
        }
        return exp(-worst / 5f)
    }

    /** How well the normal temperatures of month [m] and the next at place [p] suit [s]'s coat, 0..1. */
    fun monthFit(s: Species, p: Int, m: Int): Float {
        val coat = if (s.winterCoat) 10f else 0f
        var dev = 0f
        for (mm in intArrayOf(m, (m + 1) % 12)) { val t = hw.placeTemp(p, mm); dev += max(0f, s.comfortC.start - coat - t) + max(0f, t - s.comfortC.endInclusive) }
        return exp(-dev / 10f)
    }

    fun suits(s: Species, p: Int): Float {
        val h = s.habitat
        val cover = min(1f, land.shrub[p] / 2f + land.canopy(p))
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

    /** Food for [s] in place [p], kg/m², from its diet: grass from herbs, browse from shrubs and trees, fruit from trees. */
    private fun foodFor(s: Species, p: Int): Float {
        val d = s.diet
        val total = d.grass + d.browse + d.fruit + d.roots
        if (total <= 0f) return 0f
        return (d.grass * land.herb[p] + d.browse * (land.shrub[p] + BROWSE_OF_TREE * land.tree[p]) + (d.fruit + d.roots) * (FRUIT_OF_TREE * land.tree[p] * small.fruitSet(p) + 0.3f * land.herb[p])) / total
    }

    fun count(k: Int): Double = (0 until n).sumOf { ((adults[k][it] + young[k][it]) * land.areaKm2[it]).toDouble() }

    /** One week, with the weather on the same places. */
    fun step(weather: Weather, date: Date) {
        deaths.clear()
        carcassKg.fill(0f)
        if (bearings.month != date.month) bearings.update(date.month)
        graze(weather)
        breedAndDie(weather, date)
        shiftBands(weather)
        spill()
        small.step(weather, carcassKg)
    }

    /** Every kind takes its share of the place's grass, browse and fruit, in proportion to what each wants. */
    private fun graze(weather: Weather) {
        java.util.stream.IntStream.range(0, n).parallel().forEach { p ->
            var wantGrass = 0f; var wantBrowse = 0f; var wantFruit = 0f
            val want = FloatArray(kinds.size)
            for ((k, s) in kinds.withIndex()) {
                val heads = adults[k][p] + 0.5f * young[k][p]
                want[k] = heads * s.dailyIntakeKg * days // kg per km² this week
                val d = s.diet; val total = (d.grass + d.browse + d.fruit + d.roots).coerceAtLeast(1e-6f)
                val snowed = weather.snowpack[p] > s.snowLimitMm
                wantGrass += want[k] * d.grass / total * (if (snowed) 0f else 1f)
                wantBrowse += want[k] * d.browse / total
                wantFruit += want[k] * (d.fruit + d.roots) / total
            }
            // What can be eaten this week without eating the place bare, kg per km²: new growth in the
            // growing season, far less of the dry standing stuff in the cold.
            val reach = if (weather.temperature[p] > 4f) REACH else REACH_DORMANT
            val grass = reach * land.herb[p] * 1e6f; val browse = reach * (land.shrub[p] + BROWSE_OF_TREE * land.tree[p]) * 1e6f
            val fruit = reach * (FRUIT_OF_TREE * land.tree[p] * small.fruitSet(p) + 0.3f * land.herb[p]) * 1e6f
            val gShare = if (wantGrass > 0f) min(1f, grass / wantGrass) else 1f
            // (Water: see [tether], applied per kind below.)
            val bShare = if (wantBrowse > 0f) min(1f, browse / wantBrowse) else 1f
            val fShare = if (wantFruit > 0f) min(1f, fruit / wantFruit) else 1f
            for ((k, s) in kinds.withIndex()) {
                val d = s.diet; val total = (d.grass + d.browse + d.fruit + d.roots).coerceAtLeast(1e-6f)
                val snowed = weather.snowpack[p] > s.snowLimitMm
                // A kind that cannot reach the grass under snow falls back on browse, as far as its diet stretches.
                val g = if (snowed) 0f else d.grass / total * gShare
                // Grass and browse nourish fully only when green; fruit and roots keep their value.
                // Far from water, a kind that must drink often can only use the ground within reach of it.
                fed[k][p] = (((g + d.browse / total * bShare) * land.nourishment(p) + (d.fruit + d.roots) / total * fShare) * tether(s, p)).coerceIn(0f, 1f)
            }
            val eatenGrass = min(grass, wantGrass) / 1e6f; val eatenBrowse = min(browse, wantBrowse) / 1e6f
            land.consume(p, LifeForm.HERB, eatenGrass)
            land.consume(p, LifeForm.SHRUB, eatenBrowse * 0.7f); land.consume(p, LifeForm.TREE, eatenBrowse * 0.3f)
            land.grazing[p] = min(grass, wantGrass) / days; land.browsing[p] = min(browse, wantBrowse) / days
        }
    }

    private fun breedAndDie(weather: Weather, date: Date) {
        val stepsPerYear = Date.DAYS_PER_YEAR / days.toFloat()
        val dead = Array(kinds.size) { DoubleArray(4) } // age, hunger, cold, disease
        for ((k, s) in kinds.withIndex()) {
            val start = s.birthMonth
            val inSeason = (date.month - start + 12) % 12 < s.birthMonths
            val stepsInSeason = s.birthMonths * Date.DAYS_PER_MONTH / days.toFloat()
            val grow = years / s.yearsToMaturity
            val coat = if (s.winterCoat) 10f else 0f
            val a = adults[k]; val y = young[k]; val f = fed[k]
            for (p in 0 until n) {
                if (a[p] + y[p] <= 0f) continue
                val area = land.areaKm2[p]
                // Births: half the adults are mothers; a hungry mother bears and keeps fewer.
                if (inSeason) y[p] += 0.5f * a[p] * s.youngPerFemale * s.littersPerYear * f[p] / stepsInSeason
                // Deaths.
                val hunger = if (f[p] < 0.6f) (0.6f - f[p]) * HUNGER_DEATHS else 0f
                val t = weather.temperature[p]
                val cold = max(0f, s.comfortC.start - coat - t) / 10f * COLD_DEATHS
                // Crowding breeds disease and parasites: the denser the herds, the more die of them.
                val usual = if (s.startKm2PerAnimal > 0f) 1f / s.startKm2PerAnimal else 0.5f
                val crowd = (a[p] + y[p]) / (CROWDED * usual)
                val disease = DISEASE * crowd * crowd * years
                val pa = (s.backgroundMortality * years + hunger + cold + disease).coerceIn(0f, 0.9f)
                val py = (s.youngMortality * years + 1.5f * hunger + 1.5f * cold + disease).coerceIn(0f, 0.95f)
                val da = a[p] * pa; val dy = y[p] * py
                dead[k][0] += (a[p] * s.backgroundMortality * years + y[p] * s.youngMortality * years) * area
                dead[k][1] += ((a[p] + 1.5f * y[p]) * hunger) * area
                dead[k][2] += ((a[p] + 1.5f * y[p]) * cold) * area
                dead[k][3] += ((a[p] + y[p]) * disease) * area
                a[p] -= da; y[p] -= dy
                carcassKg[p] += (da + 0.3f * dy) * area * s.massKg
                // Growing up.
                val up = y[p] * grow
                y[p] -= up; a[p] += up
                if (a[p] + y[p] < EXTINCT) { a[p] = 0f; y[p] = 0f }
            }
        }
        for ((k, s) in kinds.withIndex()) {
            deaths["${s.name}:age"] = dead[k][0]; deaths["${s.name}:hunger"] = dead[k][1]; deaths["${s.name}:cold"] = dead[k][2]; deaths["${s.name}:disease"] = dead[k][3]
        }
    }

    /** How good a place is for kind [k] this week: food per head and the weather against its comfort. */
    private fun appeal(k: Int, p: Int, weather: Weather): Float {
        val s = kinds[k]
        val heads = adults[k][p] + young[k][p]
        val coat = if (s.winterCoat) 10f else 0f
        val t = weather.temperature[p]
        val discomfort = (max(0f, s.comfortC.start - coat - t) + max(0f, t - s.comfortC.endInclusive)) / 10f
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
                    ws[d] = ease * (foodFor(s, q) * suits(s, q) * tether(s, q) / (1f + (a[q] + y[q]) / CROWD) + 1e-4f) * max(0.05f, 1f + BEARING_SPILL * bearings.along(k, h, nh, hex.hexKm))
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
        /** Share a week that leaves country that does not suit it, at full strength of the bearing; how strongly the bearing steers them. */
        const val SPILL_MISFIT = 0.05f
        const val BEARING_SPILL = 3f
    }
}
