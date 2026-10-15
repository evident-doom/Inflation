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
    /**
     * For the check: where each kind's food came from, summed over its animals since last read (fresh
     * grass, browse, fruit and roots; standing straw; small prey and carrion; its reserves), and the
     * animals counted, so that each over the last is the share of a full need met that way.
     */
    val fedFrom = Array(kinds.size) { Array(5) { java.util.concurrent.atomic.DoubleAdder() } }
    /**
     * Fear in each place: kg of game killed there lately per km², fading over a few weeks. Game keeps
     * away from ground where its kind has been killed (the landscape of fear).
     */
    val fear = FloatArray(n)
    /**
     * Each kind's usual density, per km²: as the catalogue gives it, else by Damuth's rule from its
     * body mass (8/km² for roe deer, 0.4 for hippos), so that crowding is reckoned against what is
     * crowded for its size, not one density for every kind.
     */
    private val usual = FloatArray(kinds.size) { k -> val s = kinds[k]; if (s.startKm2PerAnimal > 0f) 1f / s.startKm2PerAnimal else Allometry.herbivoreDensityPerKm2(s.massKg) }
    /**
     * Outbreaks (rinderpest-like plagues among grazers, distemper and rabies among hunters, fevers
     * among people), by kind ([Species.index]) and hex: how strongly one is raging, 0..1. One starts
     * where a kind is crowded, passes to neighbouring hexes where the kind lives, and burns out over
     * some weeks; while it rages it kills.
     */
    val outbreak = Array(Catalogue.all.size) { FloatArray(hex.size) }
    /** Heads of each kind living in groups, by hex, this week (set by the groups) for outbreaks to pass on. */
    val groupHeads = Array(Catalogue.all.size) { IntArray(hex.size) }
    /** Starts an outbreak of [s] in hex [h] (a shared array: concurrent starts all set the same value). */
    fun startOutbreak(s: Species, h: Int) { outbreak[s.index][h] = 1f }
    /** This week's share of [s] that an outbreak kills at place [p]. */
    fun outbreakDeaths(s: Species, p: Int) = OUTBREAK_KILL * outbreak[s.index][hw.placeHex[p]]

    private var outbreakSalt = 0L
    /** A number in 0..1 that changes from week to week, for place [p] and kind [k]. */
    private fun hash(p: Int, k: Int): Float {
        var h = (p.toLong() * 0x9E3779B97F4A7C15uL.toLong()) xor (k.toLong() * 0x632BE59BD9B4E019L) xor (outbreakSalt * 0x165667B19E3779F9L)
        h = (h xor (h ushr 33)) * -0xae502812aa7333L; h = h xor (h ushr 33)
        return (h ushr 40).toFloat() / (1L shl 24).toFloat()
    }

    /** Outbreaks pass to neighbouring hexes where the kind lives, and burn out. */
    private fun stepOutbreaks() {
        for ((si, s) in Catalogue.all.withIndex()) {
            val o = outbreak[si]
            if (o.all { it < 0.01f }) continue
            val k = kinds.indexOf(s)
            val old = o.copyOf()
            for (h in 0 until hex.size) {
                if (old[h] < 0.05f) continue
                for (d in 0 until 6) {
                    val q = hex.neighbour(h, d); if (q < 0) continue
                    val here = groupHeads[si][q] > 0 || (k >= 0 && (0 until HexWorld.BANDS_M.size).any { b -> hw.placeOf[b][q].let { p -> p >= 0 && adults[k][p] + young[k][p] > 0.01f } })
                    if (here) o[q] = min(1f, max(o[q], OUTBREAK_SPREAD * old[h]))
                }
            }
            for (h in 0 until hex.size) o[h] *= OUTBREAK_KEEP
        }
    }
    fun killed(p: Int, kg: Float) { fear[p] += kg / land.areaKm2[p] }
    /** How much fear puts game off a place, 0..1. */
    fun afraid(p: Int) = fear[p] / (fear[p] + FEAR_HALF)
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
    /** Days of food each kind carries as fat in each place. */
    val reserve = Array(kinds.size) { k -> FloatArray(n) { 0.5f * kinds[k].maxFatDays } }
    /** For kinds that sleep through the winter: how well fed they went to sleep, 0..1. */
    private val autumnFed = Array(kinds.size) { FloatArray(n) { 0.8f } }
    /** Life in the sea, lakes and rivers. */
    val water = HexWater(hw, land, days, small)
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
        // Start below what the land can carry, so that each kind grows into its country rather than
        // crashing out of it: half its usual density where its food grows, no denser where the ground
        // suits it best, and thinner where it is far from water or the climate is wrong for it.
        for ((k, s) in kinds.withIndex()) {
            val dens = if (s.startKm2PerAnimal > 0f) 1f / s.startKm2PerAnimal else 0.5f
            for (p in 0 until n) {
                val food = foodFor(s, p)
                adults[k][p] = START_SHARE * dens * min(1f, food / FOOD_FULL) * min(1f, suits(s, p)) * climateSuits(s, p) * usualTether(s, p)
                young[k][p] = 0.2f * adults[k][p]
            }
            // Where a long run showed where the kind settles, start at that many, spread the same way.
            val settled = Catalogue.settledPer1000Km2(s)
            if (settled != null && USE_SETTLED) {
                val want = settled * land.areaKm2.sum() / 1000f
                val now = count(k).toFloat()
                if (now > 0f) { val f = want / now; for (p in 0 until n) { adults[k][p] *= f; young[k][p] *= f } }
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

    /** Share of [s]'s diet that is plants. */
    fun plantShare(s: Species): Float {
        val d = s.diet
        val all = d.grass + d.browse + d.fruit + d.roots + d.meat + d.smallPrey + d.carrion
        return if (all <= 0f) 1f else (d.grass + d.browse + d.fruit + d.roots) / all
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
        // Grazing in fresh ash wears teeth and poisons with fluorine: counted with the snow, as a smothered winter.
        val ash = if (land.ash[p] < ASH_HARM_MM) 0f else min(0.1f, ASH_DEATHS * land.ash[p] / 50f) * (if (young) 2f else 1f)
        return Triple(fire, flood, snow + ash)
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
    fun tether(s: Species, p: Int): Float = tetherAt(s, p, land.water[p], land.waterKm[p])

    /** [tether] in an ordinary year: from the lasting water around, not this week's. */
    fun usualTether(s: Species, p: Int): Float = tetherAt(s, p, land.nearRiver[p] + land.nearLake[p] + land.nearSpring[p], land.waterKmMean[p])

    private fun tetherAt(s: Species, p: Int, water: Float, waterKm: Float): Float {
        if (s.maxWaterKm.isFinite()) return min(1f, water)
        val reachKm = s.dailyTravelKm * s.daysWithoutWater / 4f * (1f + 10f * s.waterFromFood * s.waterFromFood)
        val r = waterKm / reachKm
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
        val plants = land.palatable[p] * (d.grass * land.herb[p] + d.browse * (land.shrub[p] + BROWSE_OF_TREE * land.tree[p])) + (d.fruit * food.fruit[p] + d.roots * food.roots[p]) / REACH
        // (Reindeer also reckon with the lichen they can dig.)
        val lichen = if (s.eatsLichen) food.lichen[p] * HexFood.LICHEN_VALUE / REACH else 0f
        return (plants / total + lichen) * HexFood.access(s, land.ruggedness[p])
    }

    fun count(k: Int): Double = (0 until n).sumOf { ((adults[k][it] + young[k][it]) * land.areaKm2[it]).toDouble() }

    /** One week, with the weather on the same places. */
    /**
     * Newcomers: once a year a resident kind that has dwindled below [NEWCOMER_ANIMALS] (or died out)
     * gets a few animals at places that suit it, as if wandering in from beyond the map; whether they
     * last is up to their niche. Only in training's play worlds ([newcomersOn]).
     */
    var newcomersOn = System.getProperty("hex.newcomers") != null
    private fun newcomers(date: Date) {
        if (!newcomersOn) return
        val r = kotlin.random.Random(date.absoluteDay * 7919L + 13)
        for (k in kinds.indices) {
            if (count(k) >= NEWCOMER_ANIMALS) continue
            val s = kinds[k]
            val w = FloatArray(n) { p -> land.areaKm2[p] * suits(s, p) * climateSuits(s, p) }
            val total = w.sum(); if (total <= 0f) continue
            repeat(NEWCOMER_PLACES) {
                var u = r.nextFloat() * total; var p = 0
                while (p < n - 1 && u > w[p]) { u -= w[p]; p++ }
                adults[k][p] += NEWCOMER_HEADS / land.areaKm2[p]
            }
        }
    }

    fun step(weather: Weather, date: Date) {
        for (p in 0 until n) fear[p] *= FEAR_KEEP
        Profile.part("outbreaks") { stepOutbreaks() }; outbreakSalt++
        deaths.clear()
        carcassKg.fill(0f)
        if (bearings.month != date.month) {
            if (date.month == 0) newcomers(date)
            Profile.time("sense of direction") { bearings.update(date.month) }
            if (date.month == 3) shiftBreeding()
        }
        Profile.time("food stocks") { food.step(weather) }
        Profile.time("water life") { water.step(weather) }
        Profile.time("residents: grazing") { graze(weather) }
        Profile.time("residents: births, deaths") { breedAndDie(weather, date) }
        Profile.time("residents: moving") { Profile.part("residents: moving up and down the slopes") { shiftBands(weather) }; Profile.part("residents: spreading to neighbouring hexes") { spill() } }
        Profile.time("soil and small life") { small.step(weather, carcassKg) }
    }

    /**
     * Every kind takes its share of the place's grass, browse, fruit and roots, in proportion to
     * what each wants and can get at (cliffs keep all but climbers off part of it); a shortfall of
     * grass and browse is made up from the standing straw, at what each kind's gut gets out of it.
     */
    /**
     * The grass and browse, kg/m², that can still be eaten at each place this week without eating it
     * bare, once the residents have had theirs: the herds passing through share it (see [HexGroups.graze]);
     * and the share of browse that comes off shrubs (the rest off trees).
     */
    val grazeLeft = FloatArray(n)
    val browseLeft = FloatArray(n)
    val shrubShareOfBrowse = FloatArray(n) { 1f }

    private fun graze(weather: Weather) {
        java.util.stream.IntStream.range(0, n).parallel().forEach { p ->
            // How much each kind eats of poor forage depends on its gut (see [gutFill]).
            val quality = land.nourishment(p)
            var wantGrass = 0f; var wantBrowse = 0f; var wantFruit = 0f; var wantRoots = 0f
            val need = FloatArray(kinds.size)
            // How much of the place's food each kind can get at: cliffs keep all but climbers off some of
            // it, and far from water a kind that must drink often uses only the ground within reach of it.
            val avail = FloatArray(kinds.size)
            for ((k, s) in kinds.withIndex()) {
                val heads = adults[k][p] + 0.5f * young[k][p]
                // Bears asleep through the cold eat nothing.
                // (Only the plant part of the diet here; omnivores' small prey and carrion below.)
                // (More in the cold, see [coldNeed].)
                need[k] = if (asleep(s, p, weather)) 0f else heads * s.dailyIntakeKg * coldNeed(s, weather.temperature[p], weather.date.month) * days / 1e6f * plantShare(s) // kg per m2 this week
                avail[k] = HexFood.access(s, land.ruggedness[p]) * tether(s, p)
                val d = s.diet; val total = (d.grass + d.browse + d.fruit + d.roots).coerceAtLeast(1e-6f)
                val snowed = weather.snowpack[p] > s.snowLimitMm
                val fill = gutFill(s, quality)
                wantGrass += need[k] * fill * d.grass / total * (if (snowed) 0f else 1f)
                wantBrowse += need[k] * fill * d.browse / total
                wantFruit += need[k] * d.fruit / total
                wantRoots += need[k] * d.roots / total
            }
            // What can be eaten this week without eating the place bare, kg per m2: new growth in the
            // growing season, far less of the dry standing stuff in the cold. Supply against demand:
            val reach = REACH_DORMANT + (REACH - REACH_DORMANT) * land.green[p]
            // (Only the edible share of the plants, see [HexLand.palatable].)
            val grass = reach * land.palatable[p] * land.herb[p]; val browse = reach * land.palatable[p] * (land.shrub[p] + BROWSE_OF_TREE * land.tree[p])
            // (Browse comes off shrubs and trees in the shares they offer it.)
            val shrubBrowse = if (land.shrub[p] + BROWSE_OF_TREE * land.tree[p] > 0f) land.shrub[p] / (land.shrub[p] + BROWSE_OF_TREE * land.tree[p]) else 1f
            val rg = if (wantGrass > 0f) grass / wantGrass else 1f
            val rb = if (wantBrowse > 0f) browse / wantBrowse else 1f
            val rf = if (wantFruit > 0f) food.fruit[p] / wantFruit else 1f
            val rr = if (wantRoots > 0f) food.roots[p] / wantRoots else 1f
            // Each kind gets its share, as far as it can get at it; nourishment from what it ate.
            val got = FloatArray(kinds.size); val short = FloatArray(kinds.size); val surplus = FloatArray(kinds.size)
            var eatG = 0f; var eatB = 0f; var eatF = 0f; var eatR = 0f; var wantStraw = 0f
            // What is left over once every kind has its usual share, for diet switching.
            var spareG = max(0f, grass - wantGrass); var spareB = max(0f, browse - wantBrowse)
            var spareF = max(0f, food.fruit[p] - wantFruit); var spareR = max(0f, food.roots[p] - wantRoots)
            for ((k, s) in kinds.withIndex()) {
                if (need[k] <= 0f) continue
                val d = s.diet; val total = (d.grass + d.browse + d.fruit + d.roots).coerceAtLeast(1e-6f)
                val snowed = weather.snowpack[p] > s.snowLimitMm
                val fill = gutFill(s, quality)
                val g = if (snowed) 0f else min(1f, avail[k] * rg)
                val b = min(1f, avail[k] * rb); val f = min(1f, avail[k] * rf); val r = min(1f, avail[k] * rr)
                eatG += need[k] * fill * d.grass / total * g; eatB += need[k] * fill * d.browse / total * b
                eatF += need[k] * d.fruit / total * f; eatR += need[k] * d.roots / total * r
                got[k] = (d.grass / total * g + d.browse / total * b) * fill * quality + d.fruit / total * f + d.roots / total * r
                // Diet switching: a food that fell short is made up from what the others left over, each
                // to at most (1 + SWITCH) times its usual share.
                var gap = 1f - got[k]
                // (Within what the gut holds: switching shifts what it eats, not how much bulk it takes.)
                var room = max(0f, fill / GUT_QREF - (d.grass / total * g + d.browse / total * b) * fill)
                if (gap > 0f && d.browse > 0f) { val x = min(min(min(gap, room * quality), SWITCH * d.browse / total * fill * quality), avail[k] * spareB / need[k] * quality); spareB -= x * need[k] / quality; eatB += x * need[k] / quality; got[k] += x; gap -= x; room -= x / quality }
                if (gap > 0f && d.grass > 0f && !snowed) { val x = min(min(min(gap, room * quality), SWITCH * d.grass / total * fill * quality), avail[k] * spareG / need[k] * quality); spareG -= x * need[k] / quality; eatG += x * need[k] / quality; got[k] += x; gap -= x; room -= x / quality }
                if (gap > 0f && d.fruit > 0f) { val x = min(min(gap, SWITCH * d.fruit / total), avail[k] * spareF / need[k]); spareF -= x * need[k]; eatF += x * need[k]; got[k] += x; gap -= x }
                if (gap > 0f && d.roots > 0f) { val x = min(min(gap, SWITCH * d.roots / total), avail[k] * spareR / need[k]); spareR -= x * need[k]; eatR += x * need[k]; got[k] += x; gap -= x }
                // With food to spare and reserves short, they eat more and lay the surplus on.
                if (gap <= 0f && reserve[k][p] < s.maxFatDays) {
                    var want = FATTEN
                    if (d.grass > 0f && !snowed) { val x = min(want * d.grass / total, avail[k] * spareG / need[k] * quality); spareG -= x * need[k] / quality; eatG += x * need[k] / quality; surplus[k] += x; want -= x }
                    if (d.browse > 0f) { val x = min(want * d.browse / total, avail[k] * spareB / need[k] * quality); spareB -= x * need[k] / quality; eatB += x * need[k] / quality; surplus[k] += x; want -= x }
                    if (d.fruit > 0f) { val x = min(want * d.fruit / total, avail[k] * spareF / need[k]); spareF -= x * need[k]; eatF += x * need[k]; surplus[k] += x; want -= x }
                }
                // A shortfall is made up with the standing straw, as far as there is any.
                short[k] = need[k] * max(0f, 1f - got[k])
                // (Only grazers and browsers eat it, and only as far as they can get at it.)
                if (d.grass + d.browse > 0f) wantStraw += short[k] * GUT_FILL * min(1f, avail[k]) else short[k] = 0f
            }
            val rs = if (wantStraw > 0f) min(1f, food.straw[p] / wantStraw) else 1f
            for ((k, s) in kinds.withIndex()) {
                val strawPart = if (need[k] > 0f) short[k] / need[k] * min(1f, GUT_FILL * HexFood.roughage(s)) * min(1f, avail[k]) * rs else 0f
                val sleeping = asleep(s, p, weather)
                var f = if (sleeping) {
                    // Asleep, it lives on its fat at a quarter of its waking need; fed as far as the fat lasts.
                    val want = SLEEP_NEED * days
                    val draw = min(reserve[k][p], want)
                    reserve[k][p] -= draw
                    draw / want
                } else {
                    // Omnivores: the rest of the diet is small animals they catch and carrion they find.
                    val ps = plantShare(s)
                    var animal = 0f
                    if (ps < 1f && need[k] > 0f) {
                        val d = s.diet; val all = d.grass + d.browse + d.fruit + d.roots + d.meat + d.smallPrey + d.carrion
                        val needKg = need[k] / ps // all of the diet, kg per m2
                        val smallWant = needKg * (d.smallPrey + d.meat) / all
                        val carrionWant = needKg * d.carrion / all
                        val gotSmall = small.catchSmall(p, smallWant * 1e6f * avail[k], weather) / 1e6f
                        val gotCarrion = small.scavenge(p, min(carrionWant, 0.5f * small.carrion[p]))
                        animal = (gotSmall + gotCarrion) / (needKg * (1f - ps))
                        small.dung[p] += HexSmallLife.DUNG * (gotSmall + gotCarrion)
                    }
                    val heads = adults[k][p] + 0.5f * young[k][p]
                    if (heads > 0f) { val ff = fedFrom[k]; ff[0].add((min(1f, got[k]) * ps * heads).toDouble()); ff[1].add((strawPart * ps * heads).toDouble()); ff[2].add((min(1f, animal) * (1f - ps) * heads).toDouble()); ff[4].add(heads.toDouble()) }
                    ((got[k] + strawPart) * ps + min(1f, animal) * (1f - ps)).coerceIn(0f, 1f)
                }
                // Reserves carry them through lean weeks; they are laid on from what was eaten beyond need.
                val r = reserve[k]
                if (!sleeping) {
                    if (f < WELL_FED) { val draw = min(r[p], (1f - f) * days); r[p] -= draw; f = min(1f, f + draw / days)
                        val heads = adults[k][p] + 0.5f * young[k][p]; if (heads > 0f) fedFrom[k][3].add((draw / days * heads).toDouble()) }
                    else r[p] = min(s.maxFatDays, r[p] + FAT_EFFICIENCY * surplus[k] * days)
                }
                fed[k][p] = f
                val fm = fedByMonth[k]; val i = p * 12 + weather.date.month
                fm[i] += (fed[k][p] - fm[i]) * FED_MEMORY
                // Laying on fat for the winter: how well fed through late summer and autumn.
                if (s.hibernates && weather.date.month in 7..11 && !asleep(s, p, weather)) autumnFed[k][p] += (fed[k][p] - autumnFed[k][p]) * 0.2f
            }
            // What is left of the week's grazing and browsing for the herds that come through after.
            grazeLeft[p] = max(0f, grass - eatG); browseLeft[p] = max(0f, browse - eatB); shrubShareOfBrowse[p] = shrubBrowse
            val dug = food.take(food.roots, p, eatR)
            val ate = food.take(food.fruit, p, eatF)
            val eaten = land.consume(p, LifeForm.HERB, eatG) +
                land.consume(p, LifeForm.SHRUB, eatB * shrubBrowse) + land.consume(p, LifeForm.TREE, eatB * (1f - shrubBrowse)) +
                ate + dug + food.take(food.straw, p, wantStraw * rs)
            // Fruit-eaters drop the seed where they go: shrubs and trees come up away from any parent.
            land.animalSeed[p] = min(1f, land.animalSeed[p] * SEED_KEEP + ate * 1e6f / SEED_KG)
            // Their feet beat down the plants and wear paths: tonnes of animal going about the place.
            var tonnes = 0f
            for ((k, s) in kinds.withIndex()) tonnes += (adults[k][p] + young[k][p]) * s.massKg / 1000f
            land.trample(p, RESIDENT_TRAMPLE * tonnes * land.areaKm2[p])
            small.dung[p] += HexSmallLife.DUNG * eaten
            land.grazing[p] = eatG * 1e6f / days; land.browsing[p] = eatB * 1e6f / days
            // Rooting churns the ground: kg of roots dug per km2 a day.
            land.rooting[p] = dug * 1e6f / days
        }
    }

    private fun breedAndDie(weather: Weather, date: Date) {
        val stepsPerYear = Date.DAYS_PER_YEAR / days.toFloat()
        val dead = Array(kinds.size) { DoubleArray(9) } // age, hunger, cold, disease, eagles, fire, flood, snow, crocodiles
        // Each kind on its own core, with its own carcasses, added up after.
        val carcasses = Array(kinds.size) { FloatArray(n) }
        kinds.indices.toList().parallelStream().forEach { k ->
            val s = kinds[k]
            val carcassKg = carcasses[k]
            val start = birthStart[k]
            val stepsInSeason = s.birthMonths * Date.DAYS_PER_MONTH / days.toFloat()
            val grow = years / s.yearsToMaturity
            val a = adults[k]; val y = young[k]; val f = fed[k]
            for (p in 0 until n) {
                if (a[p] + y[p] <= 0f) continue
                val area = land.areaKm2[p]
                // Births: half the adults are mothers; a hungry mother bears and keeps fewer.
                val inSeason = (date.month - start[p] + 12) % 12 < s.birthMonths
                if (inSeason) {
                    val born = 0.5f * a[p] * s.youngPerFemale * s.littersPerYear * f[p] / stepsInSeason
                    y[p] += born
                    // The young are made of what their mothers ate: their weight comes off the mothers' reserves (as in groups).
                    if (born > 0f && a[p] > 0f) reserve[k][p] = max(0f, reserve[k][p] - born * NEWBORN_SHARE * s.massKg / s.dailyIntakeKg / (0.5f * a[p]))
                }
                // Deaths.
                val hunger = if (f[p] < 0.6f) (0.6f - f[p]) * HUNGER_DEATHS else 0f
                val t = weather.temperature[p]
                val sleeping = asleep(s, p, weather)
                val cold = if (sleeping) 0f else s.coldC(t, date.month) / 10f * COLD_DEATHS
                // Crowding breeds disease and parasites, far more among the underfed: herds grow until the
                // lean season binds, and then hunger and sickness together hold them.
                val crowd = (a[p] + y[p]) / (CROWDED * usual[k])
                val disease = DISEASE * crowd * crowd * years * (1f + MALNOURISHED * (1f - f[p])) + outbreakDeaths(s, p)
                // Where the herds are crowded, now and then a plague breaks out among them.
                if (crowd > 0f && hash(p, k) < OUTBREAK_START * crowd * crowd * years) startOutbreak(s, hw.placeHex[p])
                val (fireA, floodA, snowA0) = calamities(s, p, weather, false)
                val (fireY, floodY, snowY0) = calamities(s, p, weather, true)
                val snowA = if (sleeping) 0f else snowA0; val snowY = if (sleeping) 0f else snowY0
                val pa = (s.backgroundMortality * years + hunger + cold + disease + fireA + floodA + snowA).coerceIn(0f, 0.9f)
                // Eagles take the young of the smaller hoofed animals in the open.
                val eagle = if (s.massKg <= EAGLE_PREY_KG) small.eagleRisk(p) * years else 0f
                // Crocodiles take the young that come down to warm rivers and lakes to drink.
                val croc = water.crocodileRisk(p, weather) * years
                val py = (s.youngMortality * years + 1.5f * hunger + 1.5f * cold + disease + eagle + fireY + floodY + snowY + croc).coerceIn(0f, 0.95f)
                val da = a[p] * pa; val dy = y[p] * py
                dead[k][0] += (a[p] * s.backgroundMortality * years + y[p] * s.youngMortality * years) * area
                dead[k][1] += ((a[p] + 1.5f * y[p]) * hunger) * area
                dead[k][2] += ((a[p] + 1.5f * y[p]) * cold) * area
                dead[k][3] += ((a[p] + y[p]) * disease) * area
                dead[k][4] += y[p] * eagle * area
                dead[k][5] += (a[p] * fireA + y[p] * fireY) * area
                dead[k][6] += (a[p] * floodA + y[p] * floodY) * area
                dead[k][7] += (a[p] * snowA + y[p] * snowY) * area
                dead[k][8] += y[p] * croc * area
                a[p] -= da; y[p] -= dy
                carcassKg[p] += (da + 0.3f * dy) * area * s.massKg
                // Growing up.
                val up = y[p] * grow
                y[p] -= up; a[p] += up
                if (a[p] + y[p] < EXTINCT) { a[p] = 0f; y[p] = 0f }
            }
        }
        for (c in carcasses) for (p in 0 until n) carcassKg[p] += c[p]
        for ((k, s) in kinds.withIndex()) {
            deaths["${s.name}:age"] = dead[k][0]; deaths["${s.name}:hunger"] = dead[k][1]; deaths["${s.name}:cold"] = dead[k][2]; deaths["${s.name}:disease"] = dead[k][3]; deaths["${s.name}:eagles"] = dead[k][4]
            deaths["${s.name}:fire"] = dead[k][5]; deaths["${s.name}:flood"] = dead[k][6]; deaths["${s.name}:snow"] = dead[k][7]; deaths["${s.name}:crocodiles"] = dead[k][8]
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
        return foodFor(s, p) * suits(s, p) * tether(s, p) * snowed * (1f - FEAR_WEIGHT * afraid(p)) / (1f + heads / (CROWD * usual[k])) / (1f + discomfort)
    }

    /** Within a hex, a share of each kind moves between neighbouring height bands towards the better one. */
    private fun shiftBands(weather: Weather) {
        val bands = HexWorld.BANDS_M.size
        // Each kind on its own core: the kinds' numbers are kept apart.
        kinds.indices.toList().parallelStream().forEach { k ->
            val a = adults[k]; val y = young[k]
            for (h in 0 until hex.size) for (b in 0 until bands - 1) {
                val lo = hw.placeOf[b][h]; val hi = hw.placeOf[b + 1][h]
                if (lo < 0 || hi < 0) continue
                if (a[lo] + y[lo] <= 0f && a[hi] + y[hi] <= 0f) continue
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
    /** The straits from each hex, as (hex across, km of open water) pairs; set by the groups (see [HexGroups.seaLinks]). */
    var seaLinks: ((Int) -> List<Pair<Int, Float>>)? = null

    private fun spill() {
        kinds.indices.toList().parallelStream().forEach { k ->
            val s = kinds[k]
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
                    ws[d] = ease * (foodFor(s, q) * land.nourishment(q) * suits(s, q) * tether(s, q) * (1f - FEAR_WEIGHT * afraid(q)) / (1f + (a[q] + y[q]) / (CROWD * usual[k])) + 1e-4f) * max(0.05f, 1f + BEARING_SPILL * bearings.along(k, h, nh, hex.hexKm))
                    total += ws[d]
                }
                // A few swim the strait to the coast across, as far as they can swim it.
                val swim = Catalogue.swimKm(s)
                var seaW = 0f; var seaTo = -1
                if (swim > 0f && seaLinks != null) {
                    val links = seaLinks!!(h)
                    for ((nh, gapKm) in links) { if (gapKm > swim) continue; val q = hw.placeOf[b][nh].takeIf { it >= 0 } ?: continue
                        val wq = SEA_SPILL * (1f - gapKm / swim) * land.areaKm2[q] * (foodFor(s, q) + 1e-4f); if (wq > seaW) { seaW = wq; seaTo = q } }
                }
                if (total <= 0f) continue
                val goA = a[p] * leave * land.areaKm2[p]; val goY = y[p] * leave * land.areaKm2[p]
                if (seaTo >= 0) { val share = seaW / (total + seaW); moveA[p] -= goA * share; moveY[p] -= goY * share; moveA[seaTo] += goA * share; moveY[seaTo] += goY * share }
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
        const val CROWDED = 4f
        /** How strongly a strait draws spilling residents against a neighbouring hex on land (a trickle). */
        const val SEA_SPILL = 0.02f
        /** Newcomers among the residents: a kind under this many animals gets this many at each of this many places, once a year. */
        const val NEWCOMER_ANIMALS = 300.0
        const val NEWCOMER_HEADS = 20f
        const val NEWCOMER_PLACES = 5
        /** How many times more disease strikes a herd that is not fed at all than a well-fed one. */
        const val MALNOURISHED = 4f
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
        /** Density, as a multiple of a kind's usual, at which crowding halves a place's appeal (10/km² against the 0.5/km² all kinds were given before). */
        const val CROWD = 20f
        /** Outbreaks: yearly chance one starts in a place at its kind's crowded density; share of the kind it kills a week at full strength; share of its strength it passes to a neighbouring hex; share kept a week. */
        const val OUTBREAK_START = 0.02f
        const val OUTBREAK_KILL = 0.04f
        const val OUTBREAK_SPREAD = 0.6f
        const val OUTBREAK_KEEP = 0.85f
        /** Fear: kg of game killed lately per km² at which a place is half as frightening as it gets; how much it puts game off at most; share kept a week. */
        const val FEAR_HALF = 5f
        const val FEAR_WEIGHT = 0.5f
        const val FEAR_KEEP = 0.7f
        /** Weekly share moving between bands at full advantage. */
        const val BAND_SHIFT = 0.15f
        /** Weekly share spilling to neighbouring hexes anyway, and more when hungry. */
        const val SPILL_BASE = 0.005f
        const val SPILL_HUNGER = 0.1f
        /** Share of its usual density a kind starts at. */
        const val START_SHARE = 0.5f
        /** Whether new worlds start at the settled densities (-Dhex.catalogueStart starts from the catalogue instead). */
        val USE_SETTLED = System.getProperty("hex.catalogueStart") == null
        /** Most a plant-eater can eat in a week as a multiple of its need: coarse food fills the gut before it nourishes, so poor dry forage cannot be fully made up for (a quarter more at most). */
        const val GUT_FILL = 1.25f
        /** Forage quality (against green, 1) at which a herbivore's gut just holds its need: middling forage. */
        const val GUT_QREF = 0.8f
        /** Seed carried by fruit-eaters: kg of fruit eaten per km² that seeds a place fully; share kept from week to week. */
        const val SEED_KG = 200f
        const val SEED_KEEP = 0.9f
        /** Share of a resident herd's weight that tramples its place in a week, against a herd passing through. */
        const val RESIDENT_TRAMPLE = 0.1f
        /** Hibernators sleep below this temperature, °C. */
        const val HIBERNATE_C = 0f
        /** Share of its waking food a sleeping animal burns from its fat. */
        const val SLEEP_NEED = 0.25f
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
        /** Ash: depth from which grazing in it harms, mm; share a week dying per 50 mm. */
        const val ASH_HARM_MM = 5f
        const val ASH_DEATHS = 0.02f
        /** Share a week that leaves country that does not suit it, at full strength of the bearing; how strongly the bearing steers them. */
        const val SPILL_MISFIT = 0.05f
        const val BEARING_SPILL = 3f
    }
}
