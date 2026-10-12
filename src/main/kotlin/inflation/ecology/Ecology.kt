package inflation.ecology

import inflation.world.Date
import inflation.world.LifeForm
import inflation.world.Simulation
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Animal life on top of the vegetation: plankton, soil life and small fauna as fields, and large
 * animals as groups. There is one clock, the world step of [days] days (the week): each week a
 * group chooses where to centre the range it roams and how watchful to be; groups whose ranges
 * overlap are in contact.
 *
 * Herbivores eat from [graze] (young grass and herbs) and [browse] (shoots and leaves of shrubs
 * and low trees; for boar also roots and mast), in the shares of their diet: the nutritious part
 * of the plants that they select, which regrows over about a month. Standing biomass is far
 * larger than what herbivores can use; these stocks are what limit them. Their size per unit of
 * plant biomass ([PALATABLE_SHARE]) is a calibration constant.
 */
class Ecology(private val sim: Simulation) {
    private val world = sim.world
    private val grid = world.grid
    private val terrain = world.terrain
    private val n = grid.size
    private val days = world.config.daysPerStep
    private val rnd = Random(world.config.seed * 31 + 7)
    private val cellKm = grid.cellKm.toFloat()
    private val cellKm2 = cellKm * cellKm

    val plankton = Plankton(sim)
    val soil = SoilLife(sim)
    val small = SmallFauna(sim)
    val fish = Fish(sim, plankton)

    /** Edible young grass and herbs, kg per km². */
    val graze = FloatArray(n)
    /** Edible shoots and leaves of shrubs and low trees, kg per km². */
    val browse = FloatArray(n)
    /** Bulbs, roots and fallen mast that rooting animals dig up, kg per km²; slow to replace. */
    val roots = FloatArray(n)
    /** Fruit, berries, seeds and nuts on shrubs and trees, kg per km²: ripens in summer and autumn, gone by winter. */
    val fruit = FloatArray(n)
    /**
     * Standing dead grass, stems and twigs that grazers fall back on in lean months, kg per km²:
     * plentiful but poor, and eaten down where herds crowd, so the dry season sets how many
     * grazers the land carries. Builds up over months as plants grow and die back.
     */
    val straw = FloatArray(n)
    /**
     * How wet the ground is now, 0 (parched) to 1 (marsh, standing water): from soil moisture,
     * recent rain, shade, floodwater and wetland. Animals get dew and moisture from plants on wet
     * ground; very wet ground has rain pools, seeps and springs to drink from.
     */
    val wetness = FloatArray(n)
    /** Distance to drinkable fresh water now (rivers, fresh lakes, floodwater, pools and seeps), km. */
    val waterKm = FloatArray(n)
    /**
     * Springs and water holes that flow all year in dry country, where groundwater from the hills
     * comes up: at the foot of a slope, along a fault, out of limestone. Fixed for the world.
     */
    val spring = BooleanArray(n)
    /** The biggest springs in the driest land: oases, whose water keeps the ground around them damp. */
    val oasis = BooleanArray(n)
    /**
     * Crocodiles per cell, 0..1 of the most a river holds: in warm rivers and lakes big enough for
     * them. Not tracked as animals: they wait at the water, and a herd that drinks there may lose one.
     */
    val crocodiles = FloatArray(n)
    /** Distance to crocodile water, km. */
    private val crocKm = FloatArray(n)
    /** Eagles hunting over each cell, 0..1: they nest on cliffs and take kids, fawns and lambs in the open. */
    val eagles = FloatArray(n)
    /** [inflation.world.Terrain.concealmentKm] and [Terrain.sightKm] for this step: plants change only between steps. */
    private val concealKm = FloatArray(n)
    private val sightKm = FloatArray(n)

    val groups = ArrayList<AnimalGroup>()
    val policies = HashMap<Species, Policy>()

    /**
     * For each species, the predators that can kill it: for prey, any predator group heavy enough
     * to bring down at least its young; for predators, much stronger rivals, which kill them when
     * they meet and drive them off their kills.
     */
    val enemies: Map<Species, List<Species>> = Catalogue.all.associateWith { s -> Catalogue.predators.filter { p -> p != s && threatens(p, s) } }
    /**
     * How much the presence of each prey species draws each predator, by species index: its mass,
     * scaled down for prey too big for the group to take.
     */
    private val lure: Map<Species, FloatArray> = Catalogue.predators.associateWith { p ->
        FloatArray(Catalogue.all.size) { i ->
            val q = Catalogue.all[i]
            val packKg = p.massKg * p.groupSize
            if (q.carnivore) 0f else min(1f, packKg / (1.5f * q.massKg)) * (1f - effectiveDefence(q, packKg)) * q.massKg / 20f
        }
    }
    private var nextId = 0
    private var stepCount = 0

    /** Running totals for the last step, for logs and tests. */
    private val killCount = java.util.concurrent.atomic.AtomicInteger()
    private val deathCount = java.util.concurrent.atomic.AtomicInteger()
    val kills: Int get() = killCount.get()
    private val birthCount = java.util.concurrent.atomic.AtomicInteger()
    val births: Int get() = birthCount.get()
    val deaths: Int get() = deathCount.get()
    /** Kills by prey species since the counter was last cleared. */
    val killsBy = java.util.concurrent.ConcurrentHashMap<Species, Int>()
    /** Kills by predator species, and kills taken from others by predator species, since last cleared. */
    val killsByPredator = java.util.concurrent.ConcurrentHashMap<Species, Int>()
    val stolen = java.util.concurrent.ConcurrentHashMap<Species, Int>()
    /** Deaths other than predation, by "species:cause", since last cleared. */
    val deathsBy = java.util.concurrent.ConcurrentHashMap<String, Int>()

    // Spatial buckets per species, rebuilt each week, for finding nearby groups.
    private val bucketKm = 8f
    private val bw = ceil(grid.width * cellKm / bucketKm).toInt()
    private val bh = ceil(grid.height * cellKm / bucketKm).toInt()
    private val buckets = Array(Catalogue.all.size) { Array(bw * bh) { ArrayList<AnimalGroup>() } }

    init {
        for (s in Catalogue.all) {
            policies[s] = BaselinePolicy(::noise)
        }
        for (i in 0 until n) if (sim.vegetation.isVegetated(i)) {
            graze[i] = PALATABLE_SHARE * 1e6f * sim.vegetation.herb[i] * 0.7f
            browse[i] = PALATABLE_SHARE * 1e6f * woody(i) * 0.7f
            roots[i] = rootsCap(i) * 0.7f
            straw[i] = strawCap(i) * 0.7f
        }
        findSprings()
        findCrocodilesAndEagles()
        updateWater()
        updateSight()
        populate()
        inheritSeasons(groups.filter { !it.species.carnivore })
    }

    /** Time spent in each part of the step, ns, summed over steps: for profiling. */
    val timings = LinkedHashMap<String, Long>()
    private inline fun <T> timed(name: String, f: () -> T): T {
        val t0 = System.nanoTime()
        val r = f()
        timings.merge(name, System.nanoTime() - t0, Long::plus)
        return r
    }

    fun step() {
        killCount.set(0); birthCount.set(0); deathCount.set(0)
        timed("fields") { plankton.step(); fish.step(); soil.step(); small.step() }
        timed("regrow") { regrow() }
        stepCount++
        timed("water") { updateWater() }
        timed("sight") { updateSight() }
        week()
        timed("demography") { demography() }
        groups.removeAll { !it.alive }
    }

    fun count(s: Species) = groups.filter { it.species == s }.sumOf { it.size }

    /** One line: animals of each species, for logs. */
    fun census() = Catalogue.all.joinToString(", ") { "${count(it)} ${it.name}" }
    fun groupCount(s: Species) = groups.count { it.species == s }

    // ---------------------------------------------------------------- plants as food

    /** Browse within reach of a deer: shrubs and the lowest branches of trees, kg/m². */
    private fun woody(i: Int) = 0.3f * sim.vegetation.shrub[i] + 0.02f * sim.vegetation.tree[i]

    /**
     * Grass and herbs grow only when it is warm and moist enough; evergreen scrub and trees keep
     * some browse through the dry summer and the winter.
     */
    private fun regrow() {
        val v = sim.vegetation
        java.util.stream.IntStream.range(0, n).parallel().forEach { i ->
            if (!v.isVegetated(i)) { graze[i] = 0f; browse[i] = 0f; return@forEach }
            val t = sim.weather.temperature[i]
            val warm = ((t - 2f) / 10f).coerceIn(0f, 1f)
            val wet = (sim.water.soil[i] / 60f).coerceIn(0.1f, 1f)
            val month = sim.weather.date.month
            // Spring flush: the first warm, wet weeks of the year grow the richest grass.
            val flush = if (month in 0..1) 1.5f else 1f
            val g = PALATABLE_SHARE * 1e6f * v.herb[i] * warm * wet * flush / REGROWTH_DAYS
            // Deciduous leaves fall in the cold; only evergreen scrub and trees keep some browse.
            val b = PALATABLE_SHARE * 1e6f * woody(i) * max(0.15f, warm) * max(0.5f, wet) / REGROWTH_DAYS
            // Fruit ripens from midsummer to autumn and rots or is gone by winter.
            val ripe = if (month in 4..7) FRUIT_SHARE * 1e6f * fruitBearing(i) / FRUIT_DAYS else 0f
            fruit[i] = max(0f, fruit[i] + days * (ripe - fruit[i] / FRUIT_DAYS))
            graze[i] = max(0f, graze[i] + days * (g - graze[i] / REGROWTH_DAYS))
            browse[i] = max(0f, browse[i] + days * (b - browse[i] / REGROWTH_DAYS))
            val autumn = sim.weather.date.month in 6..8
            val r = rootsCap(i) * (if (autumn) 3f else 0.5f) / ROOTS_DAYS
            roots[i] = max(0f, roots[i] + days * (r - roots[i] / ROOTS_DAYS))
            straw[i] = max(0f, straw[i] + days * (strawCap(i) - straw[i]) / STRAW_DAYS)
        }
    }

    /**
     * Bulbs, roots and mast a rooting animal can find, kg/km²: from herbs (geophytes) and trees
     * (acorns, nuts). Replaced over about a year, with mast falling in autumn.
     */
    private fun strawCap(i: Int) = STRAW_SHARE * 1e6f * (sim.vegetation.herb[i] + 0.3f * sim.vegetation.shrub[i])

    private fun rootsCap(i: Int) = ROOTS_SHARE * 1e6f * (sim.vegetation.herb[i] + 0.05f * sim.vegetation.tree[i])

    private fun plantDiet(s: Species) = max(1e-3f, s.diet.grass + s.diet.browse + s.diet.roots + s.diet.fruit)

    /** Fruit-bearing plants at [i], kg/m²: shrubs and the fruiting share of trees. */
    private fun fruitBearing(i: Int) = 0.5f * sim.vegetation.shrub[i] + 0.05f * sim.vegetation.tree[i]

    /** Food for [s] at [i] in the shares of its diet, kg/km². */
    fun food(s: Species, i: Int) = (s.diet.grass * graze[i] + s.diet.browse * browse[i] + s.diet.roots * roots[i] + s.diet.fruit * fruit[i]) / plantDiet(s)

    // ---------------------------------------------------------------- sight


    /** Caches [concealKm] and [sightKm] for this step. */
    private fun updateSight() {
        val v = sim.vegetation
        java.util.stream.IntStream.range(0, n).parallel().forEach { i ->
            concealKm[i] = terrain.concealmentKm(i, v)
            sightKm[i] = terrain.sightKm(i, v)
        }
    }

    // ---------------------------------------------------------------- warmth

    /** Degrees [g] is outside its comfort range at [tempC], over 10; its coat stretches the cold end and shortens the hot. */
    private fun thermalStress(g: AnimalGroup, tempC: Float): Float {
        val s = g.species
        val lo = s.comfortC.start - 10f * g.coat
        val hi = s.comfortC.endInclusive - 5f * g.coat
        return max(0f, max(lo - tempC, tempC - hi)) / 10f
    }

    /**
     * The week's weather on [g]: the coat grows in as nights get cold and is shed in warmth, and
     * outside its comfort range a group burns condition keeping warm or cool.
     */
    private fun endure(g: AnimalGroup) {
        val s = g.species
        val t = sim.weather.temperature[cellOf(g)]
        // Hibernators den up when it turns cold and they have fat enough, and wake in the spring warmth.
        if (s.hibernates) g.torpid = if (g.torpid) t < HIBERNATE_C + 4f && g.fat > 0f else t < HIBERNATE_C && g.fat > 0.3f * s.maxFatDays
        if (g.torpid) { g.thermal = 0f; return }
        if (s.winterCoat) g.coat = if (t < s.comfortC.start + 10f) min(1f, g.coat + 0.15f) else max(0f, g.coat - 0.2f)
        g.thermal = thermalStress(g, t)
        if (g.thermal > 0f) g.condition = max(0f, g.condition - THERMAL_COST * days * min(2f, g.thermal))
    }

    // ---------------------------------------------------------------- water

    /**
     * Places [spring]s and [oasis]es in dry land (rain under about two thirds of what could
     * evaporate), away from rivers that flow all year. Rain that sinks in upslope flows through
     * the ground below dry valleys and comes up where the slope eases at the foot of hills, along
     * faults, and from limestone. Each candidate gets a chance from its upslope recharge and rock;
     * on average about one water hole per [SPRING_KM2] of dry country where groundwater is ample.
     */
    private fun findSprings() {
        val c = world.climate
        val dr = world.water.drainage
        val area = grid.cellAreaM2.toFloat()
        val r = Random(world.config.seed * 131 + 11)
        // Groundwater carried below each cell: a share of the runoff of everything upslope, m³/yr.
        val recharge = dr.accumulate(FloatArray(n) { if (world.isSea[it]) 0f else GROUND_SHARE * c.runoff[it] / 1000f * area })
        val reach = max(1, (4 / cellKm).toInt())
        for (i in 0 until n) {
            if (world.isSea[i] || world.water.isLake(i)) continue
            val aridity = c.annualPrecip[i] / max(1f, c.annualPet[i])
            if (aridity > 0.65f || world.water.discharge[i] >= 0.1f || recharge[i] < MIN_SPRING_FLOW) continue
            // Hills close above: the slope eases here.
            var high = world.elevation[i]
            val x = grid.x(i); val y = grid.y(i)
            for (dy in -reach..reach) for (dx in -reach..reach)
                if (grid.inBounds(x + dx, y + dy)) high = max(high, world.elevation[grid.index(x + dx, y + dy)])
            val foothill = ((high - world.elevation[i]) / 200f).coerceIn(0f, 1f)
            val fault = if (world.tectonics.boundaryDistKm[i] < 10f) 0.5f else 0f
            val karst = if (world.geology.rock[i] == inflation.world.Rock.LIMESTONE) 0.5f else 0f
            val flow = min(1f, recharge[i] / (10f * MIN_SPRING_FLOW))
            val p = cellKm2 / SPRING_KM2 * 3f * flow * (0.2f + foothill + fault + karst)
            if (r.nextFloat() < p) {
                spring[i] = true
                oasis[i] = aridity < 0.3f && recharge[i] > 10f * MIN_SPRING_FLOW
            }
        }
    }

    /**
     * Updates [wetness] and the distance to drinking water. Fresh water: flowing rivers and creeks,
     * open (not salt) lakes, floodwater, and the pools and seeps of very wet ground that is not
     * frozen. In the wet season water is everywhere; in the dry season herds gather at rivers and
     * the few springs. Distance by chamfer transform.
     */
    private fun updateWater() {
        val big = 1e6f
        val v = sim.vegetation
        // Each cell's wetness and water on its own, in parallel; distances after.
        java.util.stream.IntStream.range(0, n).parallel().forEach { i ->
            if (world.isSea[i]) { wetness[i] = 0f; waterKm[i] = big; return@forEach }
            val soilWet = sim.water.soil[i] / inflation.world.LiveWater.SOIL_CAPACITY
            val rain = min(1f, sim.weather.precipitation[i] / days / 3f) // 3 mm a day soaks everything
            val ground = 0.75f * soilWet + 0.15f * rain + 0.1f * v.canopy(i)
            wetness[i] = when {
                isWetland(i) || sim.water.flood[i] > 0f || world.water.isLake(i) -> 1f
                world.floodplain.isFloodplain[i] -> max(0.6f, ground)
                else -> ground
            }.coerceIn(0f, 1f)
            if (oasis[i]) wetness[i] = max(wetness[i], 0.9f) else if (spring[i]) wetness[i] = max(wetness[i], 0.6f)
            val lake = world.water.isLake(i)
            val thawed = sim.weather.temperature[i] > 0f
            val pools = wetness[i] >= POOL_WETNESS && thawed
            val fresh = (lake && !world.water.lakeClosed[i]) || (!lake && sim.water.discharge[i] >= 0.1f) || sim.water.flood[i] > 0f || pools || (spring[i] && thawed)
            waterKm[i] = if (fresh) 0f else big
        }
        val d1 = cellKm; val d2 = cellKm * 1.4142f
        val w = grid.width; val h = grid.height
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            var d = waterKm[i]
            if (x > 0) d = min(d, waterKm[i - 1] + d1)
            if (y > 0) {
                d = min(d, waterKm[i - w] + d1)
                if (x > 0) d = min(d, waterKm[i - w - 1] + d2)
                if (x < w - 1) d = min(d, waterKm[i - w + 1] + d2)
            }
            waterKm[i] = d
        }
        for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
            val i = y * w + x
            var d = waterKm[i]
            if (x < w - 1) d = min(d, waterKm[i + 1] + d1)
            if (y < h - 1) {
                d = min(d, waterKm[i + w] + d1)
                if (x < w - 1) d = min(d, waterKm[i + w + 1] + d2)
                if (x > 0) d = min(d, waterKm[i + w - 1] + d2)
            }
            waterKm[i] = d
        }
    }

    // ---------------------------------------------------------------- initial populations

    /** How well cell [i] suits species [s] to start in, 0..1, from its food and favoured ground. */
    private fun suitability(s: Species, i: Int): Float {
        val v = sim.vegetation
        if (!v.isVegetated(i) || world.elevation[i] > 2500f || waterKm[i] > min(15f, s.maxWaterKm)) return 0f
        val food = ((s.diet.grass * v.herb[i] / 0.3f + s.diet.browse * woody(i) / 0.45f + s.diet.roots * (v.herb[i] + 0.05f * v.tree[i]) / 0.7f) / plantDiet(s)).coerceIn(0f, 1f)
        val h = s.habitat
        val ground = 0.5f + h.cover * (cover(i) - 0.5f) + h.rugged * (terrain.ruggedness[i] - 0.3f) * 1.5f + h.wet * (wetness[i] - 0.3f)
        return food * ground.coerceIn(0f, 1f)
    }

    private fun isWetland(i: Int) = world.biome[i].name.let { it == "MARSH" || it == "SWAMP_FOREST" || it == "BOG" || it == "SALT_MARSH" || it == "MANGROVE" }

    private fun populate() {
        for (s in Catalogue.herbivores) {
            // Several species share the plants, so each starts well below its density alone.
            val density = if (s.startKm2PerAnimal > 0f) 1f / s.startKm2PerAnimal else 0.15f * Allometry.herbivoreDensityPerKm2(s.massKg)
            val perCell = density * cellKm2 / s.groupSize
            for (i in 0 until n) {
                val p = perCell * suitability(s, i)
                if (p <= 0f || rnd.nextFloat() >= p) continue
                spawn(s, i, s.groupSize)
            }
        }
        // Predators start among the herds, at densities from field studies (from memory).
        val land = (0 until n).count { sim.vegetation.isVegetated(it) } * cellKm2
        val herds = groups.toList()
        for (p in Catalogue.predators) {
            val packs = (land / p.startKm2PerAnimal / p.groupSize).toInt()
            repeat(min(packs, herds.size)) {
                val h = herds[rnd.nextInt(herds.size)]
                spawn(p, grid.index(h.x.toInt(), h.y.toInt()), p.groupSize)
            }
        }
    }

    private fun spawn(s: Species, cell: Int, size: Int): AnimalGroup {
        val young = (size * 0.3f).toInt()
        val group = AnimalGroup(nextId++, s, grid.x(cell) + rnd.nextDouble(), grid.y(cell) + rnd.nextDouble(), size - young, young)
        group.condition = 0.6f + 0.2f * rnd.nextFloat()
        group.birthMonth = s.birthMonth
        group.fat = 0.5f * s.maxFatDays
        groups += group
        return group
    }

    // ---------------------------------------------------------------- one week

    private fun cellOf(g: AnimalGroup) = grid.index(g.x.toInt().coerceIn(0, grid.width - 1), g.y.toInt().coerceIn(0, grid.height - 1))
    private fun bucketIndex(x: Double, y: Double) =
        (y * cellKm / bucketKm).toInt().coerceIn(0, bh - 1) * bw + (x * cellKm / bucketKm).toInt().coerceIn(0, bw - 1)

    /** Roaming radius of a group of [s] for a week, km: about a day's travel. */
    fun rangeKm(s: Species) = max(1.5f, RANGE_FACTOR * s.dailyTravelKm).let { if (s.maxWaterKm.isFinite()) min(it, s.maxWaterKm + 1f) else it }

    /**
     * One week (one world step): every group chooses where to centre its range and how watchful to
     * be, all at once and in parallel, from what it knows at the start of the week; then the groups
     * move, the herds share out the food of the ground they roam, predators meet the groups whose
     * ranges overlap theirs, and everyone drinks.
     */
    private fun week() {
        timed("summarise") { summarise() }
        val size = groups.size
        if (planX.size < size) { planX = DoubleArray(size * 2); planY = DoubleArray(size * 2) }
        timed("plan") {
            java.util.stream.IntStream.range(0, size).parallel().forEach { k ->
                val g = groups[k]
                if (g.alive) plan(g, k, scratch.get()) else planX[k] = Double.NaN
            }
        }
        for (k in 0 until size) {
            val g = groups[k]
            if (!g.alive) continue
            val moved = if (planX[k].isNaN()) 0f else go(g, planX[k], planY[k])
            g.marching = moved > MARCH_RANGES * g.rangeKm
            // The further beyond its usual drift, the more of the week goes into walking, not grazing.
            val reach = MARCH_SHARE * g.species.maxDailyTravelKm * days
            g.walking = ((moved - g.rangeKm) / max(1f, reach - g.rangeKm)).coerceIn(0f, 1f)
            g.movedKm = moved
            if (g.alert > 0) g.alert--
        }
        timed("occupancy") { occupancy() }
        timed("watch") { watch() }
        timed("graze") { graze() }
        timed("prowl") { prowlAll() }
        timed("drink") { for (g in groups) if (g.alive) drink(g) }
        timed("weather") { for (g in groups) if (g.alive) endure(g) }
        timed("seasons") { for (g in groups) if (g.alive && !g.species.carnivore) learnSeason(g) }
    }

    private var planX = DoubleArray(0)
    private var planY = DoubleArray(0)

    /** Per-thread buffers for [plan]. */
    private class Scratch {
        val box = CoarseMaps.Box()
        val tx = DoubleArray(CANDIDATES)
        val ty = DoubleArray(CANDIDATES)
        val options = ArrayList<Option>(CANDIDATES)
    }
    private val scratch = ThreadLocal.withInitial { Scratch() }

    /**
     * Deterministic noise in -0.5..0.5 for [g]'s option [k] this week: a hash of the seed, the step
     * and the group, so results do not depend on which thread decided for which group.
     */
    fun noise(g: AnimalGroup, k: Int): Float {
        var h = world.config.seed.toLong() * -0x61c8864680b583ebL + stepCount * 0x9E3779B97F4A7C15uL.toLong() + g.id * 0x632BE59BD9B4E019L + k
        h = (h xor (h ushr 33)) * -0xae502812aa7333L
        h = (h xor (h ushr 33)) * -0x3b314601e57a13adL
        h = h xor (h ushr 33)
        return (h ushr 40).toFloat() / (1L shl 24).toFloat() - 0.5f
    }

    private fun dist(dx: Double, dy: Double) = sqrt(dx * dx + dy * dy)

    /** Distance between two groups' centres, km. */
    private fun kmBetween(a: AnimalGroup, b: AnimalGroup) = (dist(a.x - b.x, a.y - b.y) * cellKm).toFloat()

    /** Collects into [out] the live groups of [s] whose centres lie within [km] of (x, y). */
    private fun collect(s: Species, x: Double, y: Double, km: Float, out: ArrayList<AnimalGroup>) {
        val b = buckets[s.index]
        val bx = (x * cellKm / bucketKm).toInt(); val by = (y * cellKm / bucketKm).toInt()
        val r = ceil(km / bucketKm).toInt()
        val lim = km / cellKm
        for (yy in max(0, by - r)..min(bh - 1, by + r)) for (xx in max(0, bx - r)..min(bw - 1, bx + r))
            for (o in b[yy * bw + xx]) if (o.alive && dist(o.x - x, o.y - y) < lim) out += o
    }

    /** The nearest live group of [s] to (x, y) within [km], and its distance in km. */
    private fun nearest(s: Species, x: Double, y: Double, km: Float, except: AnimalGroup? = null): Pair<AnimalGroup?, Float> {
        val b = buckets[s.index]
        val bx = (x * cellKm / bucketKm).toInt(); val by = (y * cellKm / bucketKm).toInt()
        val r = ceil(km / bucketKm).toInt()
        var best: AnimalGroup? = null; var bestKm = km
        for (yy in by - r..by + r) for (xx in bx - r..bx + r) {
            if (xx !in 0 until bw || yy !in 0 until bh) continue
            for (o in b[yy * bw + xx]) {
                if (o === except || !o.alive) continue
                val d = (dist(o.x - x, o.y - y) * cellKm).toFloat()
                if (d < bestKm) { bestKm = d; best = o }
            }
        }
        return Pair(best, bestKm)
    }

    /** How well adults of [prey] fend off hunters weighing [packKg] together: horns and bulk deter a few hunters more than a big pack or pride. */
    private fun effectiveDefence(prey: Species, packKg: Float) = prey.defence * sqrt(min(1f, prey.massKg / (2f * packKg)))

    private fun threatens(p: Species, s: Species) =
        if (s.carnivore) strength(p, p.groupSize) > 1.5f * strength(s, s.groupSize) else p.massKg * p.groupSize >= 0.3f * s.massKg

    /** Fighting weight of [adults] of predator species [s]. */
    private fun strength(s: Species, adults: Int) = s.massKg * adults * s.ferocity

    /** Fighting weight of group [g]: hungry, thirsty animals fight poorly. */
    private fun strength(g: AnimalGroup) = strength(g.species, g.adults) * fitness(g)

    /** How fit [g] is to fight or run, 0.3..1: from its condition and thirst. */
    private fun fitness(g: AnimalGroup): Float {
        val thirst = (g.daysWithoutWater / g.species.daysWithoutWater - 1f).coerceIn(0f, 1f)
        return (0.3f + 0.7f * g.condition) * (1f - 0.4f * thirst)
    }

    /** Share of [g] that is young: mothers with young defend harder. */
    private fun youngShare(g: AnimalGroup) = if (g.size == 0) 0f else g.young.toFloat() / g.size

    /** How well the ground and plants at [i] hide things, 0 (seen from 2 km or more) to 1. */
    private fun cover(i: Int) = 1f - min(1f, concealKm[i] / 2f)

    /** How well ground with this [cover], [rugged]ness and [wet]ness suits [s], 0..1: its element. */
    private fun habitatMatch(s: Species, cover: Float, rugged: Float, wet: Float): Float {
        val h = s.habitat
        return (0.5f + h.cover * (cover - 0.5f) + h.rugged * (rugged - 0.3f) * 1.5f + h.wet * (wet - 0.3f)).coerceIn(0f, 1f)
    }

    // ---------------------------------------------------------------- the coarse summary

    private val maps = CoarseMaps(grid.width, grid.height, cellKm, COARSE_KM)
    private val mLand = maps.layer()
    private val mGraze = maps.layer()
    private val mBrowse = maps.layer()
    private val mRoots = maps.layer()
    private val mFruit = maps.layer()
    private val mStraw = maps.layer()
    private val mWater = maps.layer()
    private val mWaterKm = maps.layer()
    private val mCover = maps.layer()
    private val mRugged = maps.layer()
    private val mWet = maps.layer()
    private val mSnow = maps.layer()
    private val mCost = maps.layer()
    private val mRodents = maps.layer()
    private val mCroc = maps.layer()
    private val mTemp = maps.layer()
    /** Next week's weather, as far as it can be sensed: temperature, rain against normal, and dangers. */
    private val mTempAhead = maps.layer()
    private val mRainAhead = maps.layer()
    private val mHazard = maps.layer()
    /** Animals of each species per coarse cell, each group spread over its range. */
    private val mOcc = Array(Catalogue.all.size) { maps.layer() }
    /** Per species: mass of its enemies per coarse cell; for predators, lure-weighted prey. */
    private val mDanger = Array(Catalogue.all.size) { maps.layer() }
    private val mPrey = Array(Catalogue.all.size) { maps.layer() }
    private val fineLayers = listOf(mTempAhead, mRainAhead, mHazard, mTemp, mLand, mGraze, mBrowse, mRoots, mFruit, mStraw, mWater, mWaterKm, mCover, mRugged, mWet, mSnow, mCost, mRodents, mCroc)

    /** Sums this week's ground, plants and water onto the coarse grid. */
    private fun summarise() {
        for (l in fineLayers) l.clear()
        val v = sim.vegetation
        // Each band of coarse rows covers its own fine rows, so bands can be summed in parallel.
        java.util.stream.IntStream.range(0, maps.h).parallel().forEach { band ->
          for (fy in band * maps.ratio until min(grid.height, (band + 1) * maps.ratio)) for (fx in 0 until grid.width) {
            val i = grid.index(fx, fy)
            if (!v.isVegetated(i)) continue
            val m = maps.of(fx, fy)
            mLand.cell[m] += 1f
            mTemp.cell[m] += sim.weather.temperature[i]
            mTempAhead.cell[m] += sim.weather.forecastTemp(i)
            val normal = world.climate.monthlyPrecip[sim.weather.date.month][i] * days / Date.DAYS_PER_MONTH
            val ahead = sim.weather.forecastRain(i) / max(1f, normal)
            mRainAhead.cell[m] += ahead
            // A flood is coming where heavy rain is due on a floodplain or where water already stands.
            if (world.floodplain.isFloodplain[i] || sim.water.flood[i] > 0f) mHazard.cell[m] += ((ahead - 1.5f) / 2f).coerceIn(0f, 1f)
            mGraze.cell[m] += graze[i] * cellKm2
            mBrowse.cell[m] += browse[i] * cellKm2
            mRoots.cell[m] += roots[i] * cellKm2
            mFruit.cell[m] += fruit[i] * cellKm2
            mStraw.cell[m] += straw[i] * cellKm2
            if (waterKm[i] <= 1.5f) mWater.cell[m] += 1f
            mWaterKm.cell[m] += min(waterKm[i], 50f)
            mCover.cell[m] += cover(i)
            mRugged.cell[m] += terrain.ruggedness[i]
            mWet.cell[m] += wetness[i]
            mSnow.cell[m] += sim.weather.snowpack[i]
            mCost.cell[m] += min(10f, terrain.travelCost(i, v, sim.weather.snowpack[i]))
            mRodents.cell[m] += small.rodents[i] * cellKm2
            if (crocKm[i] <= 1.5f) mCroc.cell[m] += 1f
          }
        }
        // Eruptions due next week: tremors and gas warn of them for tens of km around.
        for (e in sim.eruptions.upcoming) {
            if (e.vei < 2) continue
            val reachKm = 8f * e.vei * e.vei
            val r = (reachKm / COARSE_KM).toInt()
            val cx = (e.volcano.x * cellKm / COARSE_KM).toInt(); val cy = (e.volcano.y * cellKm / COARSE_KM).toInt()
            for (y in max(0, cy - r)..min(maps.h - 1, cy + r)) for (x in max(0, cx - r)..min(maps.w - 1, cx + r)) {
                val d = kotlin.math.hypot((x - cx).toFloat(), (y - cy).toFloat()) * COARSE_KM
                if (d < reachKm) mHazard.cell[y * maps.w + x] += mLand.cell[y * maps.w + x] * (1f - d / reachKm)
            }
        }
        for (l in fineLayers) l.build()
        occupancy()
    }

    /** Spreads every group over its range on the coarse grid, and files groups by place for finding neighbours. */
    private fun occupancy() {
        for (l in mOcc) l.clear()
        for (b in buckets) for (l in b) l.clear()
        val box = CoarseMaps.Box()
        for (g in groups) {
            if (!g.alive) continue
            buckets[g.species.index][bucketIndex(g.x, g.y)] += g
            box.set(maps, g.x, g.y, g.rangeKm)
            val per = g.size.toFloat() / box.cells
            val cell = mOcc[g.species.index].cell
            for (y in box.y0 until box.y1) for (x in box.x0 until box.x1) cell[y * maps.w + x] += per
        }
        // Per species: the mass of its enemies over the map, and for predators the draw of prey there.
        java.util.stream.IntStream.range(0, Catalogue.all.size).parallel().forEach { si ->
            mOcc[si].build()
        }
        java.util.stream.IntStream.range(0, Catalogue.all.size).parallel().forEach { si ->
            val s = Catalogue.all[si]
            val d = mDanger[si].cell
            d.fill(0f)
            for (e in enemies.getValue(s)) { val o = mOcc[e.index].cell; for (m in d.indices) d[m] += o[m] * e.massKg }
            mDanger[si].build()
            if (s.carnivore) {
                val pv = mPrey[si].cell
                pv.fill(0f)
                val w = lure.getValue(s)
                for (q in Catalogue.herbivores) { val o = mOcc[q.index].cell; val wq = w[q.index]; for (m in pv.indices) pv[m] += o[m] * wq }
                mPrey[si].build()
            }
        }
    }

    // ---------------------------------------------------------------- choosing a range

    /** Chooses where [g] centres its range this week; stores it in [planX], [planY] at [slot] (NaN to stay). */
    private fun plan(g: AnimalGroup, slot: Int, sc: Scratch) {
        val s = g.species
        g.rangeKm = rangeKm(s)
        if (g.torpid) { planX[slot] = Double.NaN; return }
        gather(g, sc)
        val choice = policies.getValue(s).choose(g, sc.options)
        if (sc.options[choice].cell < 0 || choice == 0) { planX[slot] = Double.NaN; return }
        planX[slot] = sc.tx[choice]; planY[slot] = sc.ty[choice]
    }

    /**
     * Fills [sc] with the candidate ranges: staying, and eight directions at a short drift, a long
     * drift and as far as a forced march reaches in a week. Each is described by its totals.
     */
    private fun gather(g: AnimalGroup, sc: Scratch) {
        val s = g.species
        val r = g.rangeKm
        val reach = MARCH_SHARE * s.maxDailyTravelKm * days
        val options = sc.options
        options.clear()
        var k = 0
        for (ring in 0..3) {
            val d = when (ring) { 0 -> 0f; 1 -> r; 2 -> min(reach, 3f * r); else -> reach }
            val dirs = if (ring == 0) 1 else 8
            for (dir in 0 until dirs) {
                if (ring == 3 && reach <= 3f * r) { options += NOWHERE; sc.tx[k] = g.x; sc.ty[k] = g.y; k++; continue }
                val x = g.x + COS[dir] * d / cellKm; val y = g.y + SIN[dir] * d / cellKm
                sc.tx[k] = x; sc.ty[k] = y; k++
                options += describe(g, x, y, d, sc.box)
            }
        }
        // The season's range: a week's march towards where it remembers doing well in the coming weeks.
        val m = seasonAhead()
        val sx = g.seasonX[m]; val sy = g.seasonY[m]
        if (sx.isNaN() || s.carnivore) { options += NOWHERE; sc.tx[k] = g.x; sc.ty[k] = g.y; return }
        val dx = sx - g.x; val dy = sy - g.y
        val toGo = (dist(dx, dy) * cellKm).toFloat()
        if (toGo < r) { options += NOWHERE; sc.tx[k] = g.x; sc.ty[k] = g.y; return }
        val step = min(toGo, reach) / toGo
        val x = g.x + dx * step; val y = g.y + dy * step
        sc.tx[k] = x; sc.ty[k] = y
        val o = describe(g, x, y, min(toGo, reach), sc.box)
        val pull = (g.seasonQ[m] - g.fare).coerceIn(0f, 1f)
        options += if (o.cell < 0) o else Option(o.cell, o.km, o.forage, o.danger, o.prey, o.kin, o.waterKm, o.snowMm, o.cover, o.rugged, o.wet, o.thermal, pull, o.hazard, o.rainAhead)
    }

    /** The month whose range a group heads for now: the next one in the second half of a month. */
    private fun seasonAhead(): Int {
        val d = sim.weather.date
        return if (d.dayOfMonth > Date.DAYS_PER_MONTH / 2) (d.month + 1) % 12 else d.month
    }

    /**
     * Updates [g]'s map of the seasons with how it fared this week: a place that served better than
     * remembered replaces the memory; returning to the remembered place refreshes how good it is.
     */
    private fun learnSeason(g: AnimalGroup) {
        val m = sim.weather.date.month
        val q = (g.lastIntakeRatio * (0.5f + 0.5f * g.freshShare) * (1f - min(1f, g.thermal)) * if (g.daysWithoutWater > g.species.daysWithoutWater) 0.5f else 1f).coerceIn(0f, 1f)
        g.fare += 0.3f * (q - g.fare)
        val known = !g.seasonX[m].isNaN()
        val there = known && dist(g.x - g.seasonX[m], g.y - g.seasonY[m]) * cellKm < g.rangeKm
        when {
            !known || q > g.seasonQ[m] + 0.05f -> { g.seasonX[m] = g.x.toFloat(); g.seasonY[m] = g.y.toFloat(); g.seasonQ[m] = q }
            there -> g.seasonQ[m] += 0.3f * (q - g.seasonQ[m])
        }
    }

    // ---------------------------------------------------------------- tradition

    /**
     * Fills in each new herd's map of the seasons from the climate, standing in for what its
     * ancestors learned: for each month, the best ground for its kind within a few weeks' march,
     * judged by the growth that month, the warmth against its comfort range, and water. Nearer
     * ground is preferred when much the same.
     */
    private fun inheritSeasons(herds: List<AnimalGroup>) {
        val tk = TRADITION_KM
        val ratio = max(1, (tk / cellKm).toInt())
        val tw = (grid.width + ratio - 1) / ratio; val th = (grid.height + ratio - 1) / ratio
        val c = world.climate
        val v = sim.vegetation
        val land = FloatArray(tw * th); val herb = FloatArray(tw * th); val woody = FloatArray(tw * th); val water = FloatArray(tw * th)
        val temp = Array(12) { FloatArray(tw * th) }; val wet = Array(12) { FloatArray(tw * th) }
        for (i in 0 until n) {
            if (!v.isVegetated(i)) continue
            val b = (grid.y(i) / ratio) * tw + grid.x(i) / ratio
            land[b] += 1f; herb[b] += v.herb[i]; woody[b] += woody(i)
            if (world.water.discharge[i] >= 0.1f || world.water.isLake(i) || spring[i]) water[b] += 1f
            for (m in 0 until 12) {
                temp[m][b] += c.monthlyTemp[m][i]
                wet[m][b] += (c.monthlyPrecip[m][i] / max(1f, c.monthlyPet[m][i])).coerceIn(0f, 1f)
            }
        }
        val suit = Array(12) { FloatArray(tw * th) }
        for (s in Catalogue.herbivores) {
            for (m in 0 until 12) for (b in 0 until tw * th) {
                if (land[b] < 0.3f * ratio * ratio) { suit[m][b] = 0f; continue }
                val l = land[b]
                val t = temp[m][b] / l; val w = wet[m][b] / l
                val warm = ((t - 2f) / 10f).coerceIn(0f, 1f)
                val grow = s.diet.grass * herb[b] / l * warm * max(0.1f, w) + s.diet.browse * woody[b] / l * max(0.3f, warm) * max(0.5f, w) + s.diet.roots * herb[b] / l
                val coat = if (s.winterCoat) 10f else 0f
                val stress = max(0f, max(s.comfortC.start - coat - t, t - s.comfortC.endInclusive)) / 10f
                val drink = if (water[b] > 0f || w > 0.5f) 1f else 0.3f + 0.7f * s.waterFromFood
                suit[m][b] = grow * drink / (1f + 3f * stress * stress)
            }
            for (g in herds) {
                if (g.species != s) continue
                val reach = MARCH_SHARE * s.maxDailyTravelKm * days * TRADITION_WEEKS
                val rb = (reach / tk).toInt()
                val gx = (g.x / ratio).toInt(); val gy = (g.y / ratio).toInt()
                for (m in 0 until 12) {
                    var best = -1; var bestScore = 0f; var top = 0f
                    for (by in max(0, gy - rb)..min(th - 1, gy + rb)) for (bx in max(0, gx - rb)..min(tw - 1, gx + rb)) {
                        val b = by * tw + bx
                        val dKm = kotlin.math.hypot((bx - gx).toFloat(), (by - gy).toFloat()) * tk
                        if (dKm > reach) continue
                        val score = suit[m][b] / (1f + dKm / reach)
                        if (score > bestScore) { bestScore = score; best = b; top = suit[m][b] }
                    }
                    if (best < 0) continue
                    g.seasonX[m] = ((best % tw) * ratio + ratio / 2f).coerceAtMost(grid.width - 1f)
                    g.seasonY[m] = ((best / tw) * ratio + ratio / 2f).coerceAtMost(grid.height - 1f)
                    g.seasonQ[m] = top
                }
                // Scale remembered quality to 0..1 against the best month.
                val mx = g.seasonQ.max()
                if (mx > 0f) for (m in 0 until 12) g.seasonQ[m] = g.seasonQ[m] / mx
            }
        }
    }

    /** What [g] would find in a range centred on (x, y), [d] km from where it is now. */
    private fun describe(g: AnimalGroup, x: Double, y: Double, d: Float, box: CoarseMaps.Box): Option {
        val s = g.species
        if (x < 0 || y < 0 || !grid.inBounds(x.toInt(), y.toInt())) return NOWHERE
        val centre = grid.index(x.toInt(), y.toInt())
        if (!sim.vegetation.isVegetated(centre)) return NOWHERE
        val r = g.rangeKm
        box.set(maps, x, y, r)
        val land = mLand.box(box.x0, box.y0, box.x1, box.y1).toFloat()
        if (land < 1f) return NOWHERE
        val areaKm2 = land * cellKm2
        val waterHere = mWaterKm.box(box.x0, box.y0, box.x1, box.y1).toFloat() / land
        // Water-bound animals do not leave the river.
        if (d > 0f && waterKm[centre] > max(s.maxWaterKm, waterKm[cellOf(g)])) return NOWHERE
        val waterCells = mWater.box(box.x0, box.y0, box.x1, box.y1).toFloat()
        val forage = if (s.carnivore) mRodents.box(box.x0, box.y0, box.x1, box.y1).toFloat() / areaKm2
            else ((s.diet.grass * mGraze.box(box.x0, box.y0, box.x1, box.y1) + s.diet.browse * mBrowse.box(box.x0, box.y0, box.x1, box.y1) +
                s.diet.roots * mRoots.box(box.x0, box.y0, box.x1, box.y1) + s.diet.fruit * mFruit.box(box.x0, box.y0, box.x1, box.y1)) / plantDiet(s)).toFloat() / areaKm2
        val boxKm2 = box.cells * COARSE_KM * COARSE_KM
        // Enemies: their mass per km² over the range, against this group's own mass.
        val enemyKg = mDanger[s.index].box(box.x0, box.y0, box.x1, box.y1)
        val enemyDensity = (enemyKg / boxKm2).toFloat()
        val danger = enemyDensity / (enemyDensity + DANGER_KG_KM2)
        val preyValue = if (s.carnivore) mPrey[s.index].box(box.x0, box.y0, box.x1, box.y1) else 0.0
        val preyDensity = (preyValue / boxKm2).toFloat()
        val prey = preyDensity / (preyDensity + PREY_NORM)
        // Others of its kind, not counting itself where it stands.
        val own = if (d == 0f) g.size.toFloat() else 0f
        val kinDensity = max(0f, mOcc[s.index].box(box.x0, box.y0, box.x1, box.y1).toFloat() - own) / boxKm2
        val kin = kinDensity / (kinDensity + s.groupSize / (3.14f * r * r))
        // Effort to shift the range there: distance times the ground's travel cost along the way.
        val effort = if (d == 0f) 0f else d * pathCost(g.x, g.y, x, y) / days
        return Option(centre, effort, forage, danger, prey, kin, if (waterCells > 0f) 0f else waterHere,
            mSnow.box(box.x0, box.y0, box.x1, box.y1).toFloat() / land, mCover.box(box.x0, box.y0, box.x1, box.y1).toFloat() / land,
            mRugged.box(box.x0, box.y0, box.x1, box.y1).toFloat() / land, mWet.box(box.x0, box.y0, box.x1, box.y1).toFloat() / land,
            // Now and, as far as it can sense it, next week.
            max(thermalStress(g, mTemp.box(box.x0, box.y0, box.x1, box.y1).toFloat() / land),
                s.foresight * thermalStress(g, mTempAhead.box(box.x0, box.y0, box.x1, box.y1).toFloat() / land)),
            0f,
            (s.foresight * mHazard.box(box.x0, box.y0, box.x1, box.y1).toFloat() / land).coerceIn(0f, 1f),
            1f + s.foresight * (mRainAhead.box(box.x0, box.y0, box.x1, box.y1).toFloat() / land - 1f))
    }

    /** Mean travel cost on the straight line from (x0, y0) to (x1, y1), sampled on the coarse grid; open water is very costly. */
    private fun pathCost(x0: Double, y0: Double, x1: Double, y1: Double): Float {
        // A few samples along the way: enough to see a lake or a mountain range in the path.
        val steps = (dist(x1 - x0, y1 - y0) * cellKm / (4f * COARSE_KM)).toInt().coerceIn(1, 6)
        var sum = 0f
        for (t in 1..steps) {
            val f = t.toDouble() / steps
            val m = maps.of(x0 + (x1 - x0) * f, y0 + (y1 - y0) * f)
            val land = mLand.cell[m]
            sum += if (land < 0.25f * maps.ratio * maps.ratio) 20f else mCost.cell[m] / land
        }
        return sum / steps
    }

    /** Moves [g]'s centre to (x, y), trampling along the way; returns the km moved. */
    private fun go(g: AnimalGroup, x: Double, y: Double): Float {
        val from = cellOf(g)
        g.x = x; g.y = y
        val to = cellOf(g)
        val steps = max(1, max(abs(grid.x(to) - grid.x(from)), abs(grid.y(to) - grid.y(from))))
        // A herd drifting about its range passes the same ground daily; a march passes once.
        for (t in 0..steps) {
            val xi = grid.x(from) + (grid.x(to) - grid.x(from)) * t / steps
            val yi = grid.y(from) + (grid.y(to) - grid.y(from)) * t / steps
            sim.vegetation.trample(grid.index(xi, yi), g.biomassKg / TRAMPLE_KG)
        }
        sim.vegetation.trample(to, days * g.biomassKg / TRAMPLE_KG)
        return (dist(grid.x(to) - grid.x(from).toDouble(), grid.y(to) - grid.y(from).toDouble()) * cellKm).toFloat()
    }

    // ---------------------------------------------------------------- watchfulness

    /**
     * Sets each group's posture for the week: from roaming freely (0) to bunched up, watchful and
     * ready to defend (1), as much as the danger it senses in its range calls for. A watchful group
     * feeds less but is far harder to surprise or bring down.
     */
    private fun watch() {
        val box = CoarseMaps.Box()
        for (g in groups) {
            if (!g.alive) continue
            box.set(maps, g.x, g.y, g.rangeKm)
            val enemyKg = mDanger[g.species.index].box(box.x0, box.y0, box.x1, box.y1)
            val density = (enemyKg / (box.cells * COARSE_KM * COARSE_KM)).toFloat()
            val danger = density / (density + DANGER_KG_KM2)
            // A hungry herd cannot afford to stand watching: it grazes and takes its chances.
            g.posture = min(1f, (POSTURE_GAIN * danger + if (g.alert > 0) 0.5f else 0f) * (0.4f + 0.6f * g.condition))
        }
    }

    // ---------------------------------------------------------------- grazing

    /**
     * The herds share out the food of the ground they roam. Each asks for its week's food from the
     * coarse cells of its range, in the shares of its diet and where the food is that it can reach;
     * where several ask more than a cell holds, each gets the same fraction of what it asked.
     * Shortfalls are made up with straw.
     */
    private fun graze() {
        val herds = groups.filter { it.alive && !it.species.carnivore }
        val stocks = listOf(mGraze, mBrowse, mRoots, mFruit)
        val want = FloatArray(herds.size)
        // What each herd wants of each food, and how much of it its range offers: in parallel.
        val split = Array(herds.size) { FloatArray(4) }
        val offered = Array(herds.size) { FloatArray(4) }
        val boxes = Array(herds.size) { CoarseMaps.Box() }
        val autumn = sim.weather.date.month in 5..8
        java.util.stream.IntStream.range(0, herds.size).parallel().forEach { h ->
            val g = herds[h]
            val s = g.species
            if (g.torpid) return@forEach
            val c = cellOf(g)
            // On the march they snatch a few mouthfuls on the way; watchful herds graze less.
            val appetite = (1f - (1f - MARCH_APPETITE) * g.walking) * (1f - POSTURE_FEED_COST * g.posture)
            // Room to fatten: more in late summer and autumn, when animals eat all they can before winter.
            val fatten = if (g.fat < s.maxFatDays) (if (autumn) FATTEN_AUTUMN else FATTEN) else 0f
            want[h] = if (sim.weather.snowpack[c] > s.snowLimitMm) 0f else g.dailyNeedKg * days * appetite * (1f + fatten)
            val box = boxes[h]
            box.set(maps, g.x, g.y, g.rangeKm)
            for (k in 0..3) offered[h][k] = if (diet(s)[k] > 0f) reachable(s, stocks[k], box) else 0f
            split(s, want[h], offered[h], split[h])
        }
        // Each herd's demand spread over its range, where the food is that it can reach.
        val asked = Array(4) { FloatArray(maps.size) }
        forEachByTile(herds.indices.toList(), { herds[it] }) { h ->
            val g = herds[h]
            val box = boxes[h]
            for (k in 0..3) {
                val wantK = split[h][k]; val total = offered[h][k]
                if (wantK <= 0f || total <= 0f) continue
                val cell = stocks[k].cell
                for (y in box.y0 until box.y1) for (x in box.x0 until box.x1) {
                    val m = y * maps.w + x
                    asked[k][m] += wantK * cell[m] * access(g.species, m) / total
                }
            }
        }
        // Share of each cell's stock that each asker gets, and what is eaten there.
        val share = Array(4) { k -> FloatArray(maps.size) { m -> if (asked[k][m] <= 0f) 0f else min(1f, stocks[k].cell[m] / asked[k][m]) } }
        val eaten = Array(4) { k -> FloatArray(maps.size) { m -> asked[k][m] * share[k][m] } }
        val got = FloatArray(herds.size)
        java.util.stream.IntStream.range(0, herds.size).parallel().forEach { h ->
            val g = herds[h]
            val box = boxes[h]
            var sum = 0f
            for (k in 0..3) {
                val wantK = split[h][k]; val total = offered[h][k]
                if (wantK <= 0f || total <= 0f) continue
                val cell = stocks[k].cell
                for (y in box.y0 until box.y1) for (x in box.x0 until box.x1) {
                    val m = y * maps.w + x
                    sum += wantK * cell[m] * access(g.species, m) / total * share[k][m]
                }
            }
            got[h] = sum
        }
        for ((h, g) in herds.withIndex()) {
            // A hibernating bear lives on its fat at a quarter of its need.
            if (g.torpid) { g.freshShare = 1f; feed(g, 1f - HIBERNATION_NEED) }
            else if (want[h] <= 0f) { g.freshShare = 0f; feed(g, 0f) }
        }
        // Take what was eaten from the fine cells, in proportion to what each held.
        val v = sim.vegetation
        val forms = arrayOf(LifeForm.HERB, LifeForm.SHRUB, null, null)
        val fine = arrayOf(graze, browse, roots, fruit)
        java.util.stream.IntStream.range(0, n).parallel().forEach { i ->
            val m = maps.of(grid.x(i), grid.y(i))
            for (k in 0..3) {
                val total = stocks[k].cell[m]
                if (total <= 0f || eaten[k][m] <= 0f) continue
                val f = min(1f, eaten[k][m] / total)
                val e = fine[k][i] * f
                fine[k][i] -= e
                forms[k]?.let { v.consume(i, it, (e * cellKm2 / grid.cellAreaM2).toFloat()) }
            }
        }
        // Straw fills the gap, as far as the gut allows: from the centre cell's neighbourhood.
        forEachByTile(herds.indices.toList(), { herds[it] }) { h ->
            val g = herds[h]
            if (want[h] <= 0f || g.torpid) return@forEachByTile
            val s = g.species
            var food = got[h]
            val fresh = food
            val value = roughageValue(s)
            // Straw keeps an animal going but does not fatten it: only up to the week's need.
            val need = min(want[h], g.dailyNeedKg * days)
            if (food < need) food += roughage(cellOf(g), min(need - food, max(0f, GUT_FILL * need - fresh) * value), value)
            // Omnivores find the animal part of their diet too: small prey, insects and carrion.
            val animal = s.diet.smallPrey + s.diet.carrion + s.diet.meat
            if (animal > 0f) {
                val wantAnimal = g.dailyNeedKg * days * animal / plantDiet(s)
                food += catchRodents(cellOf(g), wantAnimal * (s.diet.smallPrey + s.diet.meat) / animal) +
                    scavenge(cellOf(g), wantAnimal * s.diet.carrion / animal)
            }
            g.freshShare = if (food > 0f) fresh / food else 0f
            feed(g, food / (g.dailyNeedKg * days))
        }
    }

    /**
     * Splits a week's [want] of food across grass, browse, roots and fruit: by [s]'s preferences,
     * shifted towards what the range actually offers, so that a herd eats fruit in autumn and more
     * browse when the grass is gone, as far as its diet allows.
     */
    private fun split(s: Species, want: Float, offered: FloatArray, out: FloatArray) {
        val d = diet(s)
        var sum = 0f
        for (k in 0..3) {
            out[k] = 0f
            if (d[k] <= 0f) continue
            // How fully this food could cover its share of the diet: up to twice as much if plentiful.
            val avail = (offered[k] / max(1e-3f, 3f * want * d[k] / plantDiet(s))).coerceIn(0f, 2f)
            out[k] = d[k] * avail; sum += out[k]
        }
        if (sum <= 0f) return
        for (k in 0..3) out[k] = want * out[k] / sum
    }

    /** [s]'s diet shares of grass, browse, roots and fruit. */
    private fun diet(s: Species) = floatArrayOf(s.diet.grass, s.diet.browse, s.diet.roots, s.diet.fruit)

    /**
     * How much of coarse cell [m]'s food [s] can get at, 0..1: steep, rocky ground keeps all but
     * climbers off much of it, which is what leaves the ibex its cliffs.
     */
    private fun access(s: Species, m: Int): Float {
        val rugged = mRugged.cell[m] / max(1f, mLand.cell[m])
        val climber = max(0f, s.habitat.rugged)
        return 1f - 0.8f * (1f - climber) * ((rugged - 0.4f) / 0.4f).coerceIn(0f, 1f)
    }

    /** Food of [stock] in [box] that [s] can get at: the weights its demand is spread by. */
    private fun reachable(s: Species, stock: CoarseMaps.Layer, box: CoarseMaps.Box): Float {
        var total = 0f
        for (y in box.y0 until box.y1) for (x in box.x0 until box.x1) { val m = y * maps.w + x; total += stock.cell[m] * access(s, m) }
        return total
    }

    /**
     * Winter and dry-season fallback: [straw]. Poor food: it fills the stomach with [value] of the
     * nourishment, so animals live through lean months on it while losing condition, and only
     * while it lasts. Returns nourishment in kg of good food.
     */
    private fun roughage(c: Int, nourishmentKg: Float, value: Float): Float =
        value * eat(c, straw, nourishmentKg / value, LifeForm.HERB, STRAW_REACH)

    /**
     * How much a species gets out of coarse forage (the Jarman-Bell principle): a big gut holds
     * food longer and digests fibre better, while a small animal's high metabolism needs rich food.
     * Aurochs and wild asses live through the dry season on straw that starves a gazelle.
     */
    private fun roughageValue(s: Species) = (ROUGHAGE_VALUE * s.fibreDigestion * (s.massKg / 100f).pow(0.25f)).coerceAtMost(0.75f)

    /** Eats up to [kg] from [stock] over the cells within [reach] of [c], taking it from plants of [form]; returns kg eaten. */
    private fun eat(c: Int, stock: FloatArray, kg: Float, form: LifeForm?, reach: Int): Float {
        if (kg <= 0f) return 0f
        val cx = grid.x(c); val cy = grid.y(c)
        var available = 0f
        for (dy in -reach..reach) for (dx in -reach..reach) if (grid.inBounds(cx + dx, cy + dy)) available += stock[grid.index(cx + dx, cy + dy)] * cellKm2
        if (available <= 0f) return 0f
        val share = min(1f, kg / available)
        var eaten = 0f
        for (dy in -reach..reach) for (dx in -reach..reach) {
            if (!grid.inBounds(cx + dx, cy + dy)) continue
            val i = grid.index(cx + dx, cy + dy)
            val e = stock[i] * share
            stock[i] -= e
            if (form != null) sim.vegetation.consume(i, form, (e * cellKm2 / grid.cellAreaM2).toFloat())
            eaten += e * cellKm2
        }
        return eaten
    }

    /**
     * Feeds [g] a week's food at [ratio] of its need: a surplus is laid on as fat, up to what its kind
     * can carry; a shortfall is met from fat first, and only then does condition suffer.
     */
    private fun feed(g: AnimalGroup, ratio: Float) {
        val s = g.species
        var r = ratio
        if (r > 1f) {
            g.fat = min(s.maxFatDays, g.fat + (r - 1f) * days * FAT_EFFICIENCY)
            r = 1f
        } else {
            val short = (1f - r) * days
            val burned = min(g.fat, short)
            g.fat -= burned
            r = 1f - (short - burned) / days
        }
        g.lastIntakeRatio = r
        // Losing condition is slower than for a starving animal: on poor food they cut their metabolism.
        g.condition = if (r >= 0.9f) min(1f, g.condition + 0.01f * days) else max(0f, g.condition - 0.012f * days * (1f - r))
    }

    // ---------------------------------------------------------------- predators


    /**
     * A predator group's week: rivals and stronger enemies whose ranges overlap its own cost it
     * days and sometimes lives; then it hunts the herds in contact, eats carrion and small prey.
     */
    private fun prowl(g: AnimalGroup, r: Random) {
        if (!g.alive) return
        val s = g.species
        val c = cellOf(g)
        val need = g.dailyNeedKg * days
        var free = days.toFloat()
        free -= meetEnemies(g, r)
        if (g.alive) free -= meetRivals(g, r)
        if (g.adults == 0) return
        // Carrion first, up to its share of the diet: a scavenger finds most of its food this way.
        if (g.stored < need) g.stored += scavenge(c, s.diet.carrion * need)
        if (free > 0f) hunt(g, free, r)
        // A pack gorges when there is meat: some of it goes on as fat.
        val room = if (g.fat < s.maxFatDays) 1f + FATTEN else 1f
        var eaten = min(g.stored, need * room)
        g.stored -= eaten
        // Small prey and carrion fill the gap. Catching voles one at a time yields little: at most
        // the small-prey share of the diet.
        if (eaten < need) eaten += catchRodents(c, min(need - eaten, s.diet.smallPrey * need))
        if (eaten < need) eaten += scavenge(c, need - eaten)
        // A pack caches a few days' food (a leopard in a tree, more); the rest is left to rot and to scavengers.
        val cap = (0.5f + 0.5f * s.stalking) * need
        if (g.stored > cap) {
            soil.carrion[c] += ((g.stored - cap) / grid.cellAreaM2).toFloat()
            g.stored = cap
        }
        g.freshShare = min(1f, eaten / need)
        feed(g, eaten / need)
    }

    /**
     * Every predator group's week. Groups more than [TILE_KM] apart cannot touch each other's prey,
     * rivals or carcasses this week, so the map is cut into tiles of that size and coloured like a
     * chessboard of four colours; the tiles of one colour are worked in parallel, each tile's
     * groups one at a time in a shuffled order. Each group draws from its own random stream, so
     * the result does not depend on the threads.
     */
    private fun prowlAll() {
        val hunters = groups.filter { it.species.carnivore && it.alive }.toMutableList()
        hunters.shuffle(rnd)
        forEachByTile(hunters, { it }) { g -> prowl(g, groupRandom(g)) }
    }

    /**
     * Runs [action] on [items] (located by their group, [at]) tile by tile: the tiles of one of four
     * chessboard colours in parallel, each tile's items in their given order. Items in different
     * tiles of a colour are too far apart to touch the same ground in a week.
     */
    private fun <T> forEachByTile(items: List<T>, at: (T) -> AnimalGroup, action: (T) -> Unit) {
        val tile = TILE_KM / cellKm
        val tw = (grid.width / tile).toInt() + 1
        val byTile = LinkedHashMap<Int, MutableList<T>>()
        for (it in items) { val g = at(it); byTile.getOrPut((g.y / tile).toInt() * tw + (g.x / tile).toInt()) { ArrayList() } += it }
        for (colour in 0 until 4) {
            val lists = byTile.entries.filter { (k, _) -> (k % tw) % 2 + 2 * ((k / tw) % 2) == colour }.map { it.value }
            lists.parallelStream().forEach { list -> for (it in list) action(it) }
        }
    }

    /** A random stream for [g] this week, the same whichever thread uses it. */
    private fun groupRandom(g: AnimalGroup, salt: Int = 0) = Random(world.config.seed * 1_000_003L + stepCount * 7_919L + g.id * 104_729L + salt * 15_485_863L)

    /** Carrion within a few km of [c], up to [kg]; returns kg eaten. */
    private fun scavenge(c: Int, kg: Float): Float {
        if (kg <= 0f) return 0f
        var got = 0f
        val cx = grid.x(c); val cy = grid.y(c)
        val r = STRAW_REACH
        val per = kg / ((2 * r + 1) * (2 * r + 1))
        for (dy in -r..r) for (dx in -r..r) if (grid.inBounds(cx + dx, cy + dy))
            got += soil.scavenge(grid.index(cx + dx, cy + dy), (per / grid.cellAreaM2).toFloat()) * grid.cellAreaM2.toFloat()
        return got
    }

    /** Rodents within a few km of [c], up to [kg]; returns kg caught. */
    private fun catchRodents(c: Int, kg: Float): Float {
        if (kg <= 0f) return 0f
        var got = 0f
        val cx = grid.x(c); val cy = grid.y(c)
        val r = STRAW_REACH
        val per = kg / ((2 * r + 1) * (2 * r + 1))
        for (dy in -r..r) for (dx in -r..r) if (grid.inBounds(cx + dx, cy + dy))
            got += small.catchRodents(grid.index(cx + dx, cy + dy), per / cellKm2) * cellKm2
        return got
    }

    /**
     * The hunt. Over the week the group meets the herds whose ranges overlap its own, more often the
     * more they overlap, and tests them until it has the week's meat or runs out of chances.
     *
     * A herd that notices the hunters closes up and defends itself, and on its own kind of ground it
     * is hard to beat: healthy adults are then rarely taken. Surprise is what predators need: cover
     * for a stalker, a herd that was not watchful. The young and the weak are always easier. Hunters
     * outside their own element do worse and are more often hurt.
     */
    private fun hunt(g: AnimalGroup, free: Float, r: Random) {
        val s = g.species
        val c = cellOf(g)
        val need = g.dailyNeedKg * days
        val wantMeat = need * min(1f, 2f * s.diet.meat)
        if (g.stored >= wantMeat || g.adults == 0) return
        val near = ArrayList<AnimalGroup>()
        for (q in Catalogue.herbivores) collect(q, g.x, g.y, g.rangeKm + rangeKm(q), near)
        if (near.isEmpty()) return
        val contact = FloatArray(near.size) { overlap(kmBetween(g, near[it]), g.rangeKm, near[it].rangeKm) }
        val chances = min(MAX_CHASES * free / days, CHASES_PER_CONTACT * free * contact.sum()).toInt()
        val packKg = g.adults * s.massKg
        val home = habitatMatch(s, cover(c), terrain.ruggedness[c], wetness[c])
        // Each herd's chances, worked out once for the week: for an adult and for a young one.
        val pAdult = FloatArray(near.size); val pYoung = FloatArray(near.size); val risk = FloatArray(near.size)
        for ((k, o) in near.withIndex()) {
            if (contact[k] <= 0f) continue
            val at = cellOf(o)
            pAdult[k] = catchChance(g, o, at, false, packKg, home)
            pYoung[k] = if (o.young > 0) catchChance(g, o, at, true, packKg, home) else 0f
            // Big, armed prey fight back: horns and tusks kill hunters. Worth it only for much meat.
            risk[k] = fightBack(o.species, s) * (2f - home) * (1f + youngShare(o)) * fitness(o)
        }
        // Each herd in contact is tried at most once a week (after a chase it moves off), so the
        // herds are ranked once by what they promise: chance times meat, for its young or an adult,
        // whichever is better. A hungry hunter takes on prey it would leave alone when fed.
        val caution = 5f * g.condition
        val value = FloatArray(near.size); val takeYoung = BooleanArray(near.size)
        for ((k, o) in near.withIndex()) {
            if (contact[k] <= 0f) continue
            val ps = o.species
            val adult = pAdult[k] * max(0f, 1f - caution * risk[k]) * min(ps.massKg, need)
            val young = if (o.young > 0) pYoung[k] * min(0.3f * ps.massKg, need) * min(1f, 2f * o.young / (o.size + o.young)) else 0f
            takeYoung[k] = young > adult
            value[k] = contact[k] * max(adult, young)
        }
        val ranked = near.indices.filter { value[it] > 0f }.sortedByDescending { value[it] }
        var attempts = 0
        for (best in ranked) {
            if (g.stored >= wantMeat || g.adults == 0 || attempts++ >= chances) break
            val prey = near[best]
            if (!prey.alive) continue
            val bestYoung = takeYoung[best] && prey.young > 0
            val bestP = if (bestYoung) pYoung[best] else pAdult[best]
            val bestRisk = if (bestYoung) 0f else risk[best]
            // Caught or not, the herd is alarmed for weeks, keeps watch and moves off out of reach.
            prey.alert = ALERT_WEEKS
            contact[best] = 0f
            if (r.nextFloat() >= bestP) { shove(prey, g); continue }
            // Brought to bay, a big animal may take one of its killers with it.
            if (bestRisk > 0f && r.nextFloat() < bestRisk) {
                g.adults--; deathCount.incrementAndGet()
                deathsBy.merge("${s.name}:fought back", 1, Int::plus)
                soil.carrion[c] += (s.massKg / grid.cellAreaM2).toFloat()
            }
            val meat = if (bestYoung) { prey.young--; 0.3f * prey.species.massKg } else { prey.adults--; prey.species.massKg }
            g.stored += meat - steal(g, meat, r)
            killCount.incrementAndGet()
            killsBy.merge(prey.species, 1, Int::plus)
            killsByPredator.merge(s, 1, Int::plus)
            shove(prey, g)
        }
    }

    /** Chance that one chase by [g] after herd [o] (cell [at]) succeeds. */
    private fun catchChance(g: AnimalGroup, o: AnimalGroup, at: Int, young: Boolean, packKg: Float, home: Float): Float {
        val s = g.species; val ps = o.species
        val cov = cover(at)
        // Surprise: cover lets a stalker close in unseen; a watchful herd is seldom surprised.
        val surprise = (SURPRISE_BASE + s.stalking * cov) * (1f - WATCH_EFFECT * o.posture)
        // An aware herd in its element bunches, faces the hunters and defends: on cliffs, in thickets
        // or marsh, wherever it is at home. In open ground it can only run.
        val inElement = habitatMatch(ps, cov, terrain.ruggedness[at], wetness[at])
        val aware = 1f - surprise.coerceIn(0f, 1f)
        // Mothers defend their young; the hungry and thirsty fight poorly.
        val stand = aware * (AWARE_DEFENCE * inElement + 0.3f * o.posture + 0.3f * youngShare(o)) * fitness(o)
        val eyes = 1f - 0.3f * min(1f, o.size / 40f)
        val kg = if (young) 0.3f * ps.massKg else ps.massKg
        // Hunters much lighter than their quarry rarely bring it down.
        val size = min(1f, packKg / (1.5f * kg))
        val defence = effectiveDefence(ps, packKg)
        val base = if (young) YOUNG_CATCH * (1f - 0.5f * defence) else (0.15f + 0.5f * (1f - o.condition)) * (1f - defence)
        // A courser must outrun its quarry; a stalker strikes before the chase begins. The young run slower.
        val preySpeed = if (young) 0.7f * ps.speedKmh else ps.speedKmh
        val chase = s.stalking + (1f - s.stalking) * (s.speedKmh / preySpeed).coerceIn(0.5f, 1.3f)
        // Out of its element a hunter is slower and clumsier; ibex on cliffs cannot be followed at all.
        // Hunger slows a hunter only a little: it also makes it bolder.
        val skill = (0.6f + 0.4f * home) * (0.7f + 0.3f * fitness(g))
        val refuge = 1f - 0.8f * max(0f, ps.habitat.rugged) * terrain.ruggedness[at]
        return (min(1f, g.adults / 2f) * eyes * size * base * chase * skill * refuge * (1f + surprise) * (1f - stand.coerceIn(0f, 0.9f))).coerceIn(0f, 1f)
    }

    /** Pushes [loser]'s range away from [winner] by its own radius, if there is land there. */
    private fun shove(loser: AnimalGroup, winner: AnimalGroup) {
        val dx = loser.x - winner.x; val dy = loser.y - winner.y
        val d = max(1e-3, dist(dx, dy))
        val run = loser.rangeKm / cellKm
        val nx = loser.x + dx / d * run; val ny = loser.y + dy / d * run
        if (nx >= 0 && ny >= 0 && grid.inBounds(nx.toInt(), ny.toInt()) && sim.vegetation.isVegetated(grid.index(nx.toInt(), ny.toInt()))) { loser.x = nx; loser.y = ny }
    }

    /**
     * Chance per kill that an adult of [prey] kills one of the hunting [predator]s: grows with its
     * defence and with how much heavier it is than one hunter.
     */
    private fun fightBack(prey: Species, predator: Species) = FIGHT_BACK * prey.defence * min(2f, prey.massKg / predator.massKg)

    /**
     * A kill draws rivals: the strongest other predator group in contact, if it outweighs the
     * killers, drives them off and takes most of the meat (hyenas rob leopards, lions rob everyone).
     * Returns the kg taken from [g].
     */
    private fun steal(g: AnimalGroup, kg: Float, r: Random): Float {
        var thief: AnimalGroup? = null; var best = strength(g)
        for (p in Catalogue.predators) {
            if (p == g.species) continue
            val (o, d) = nearest(p, g.x, g.y, g.rangeKm + rangeKm(p))
            if (o == null || o.adults == 0) continue
            if (r.nextFloat() >= THEFT * overlap(d, g.rangeKm, o.rangeKm)) continue
            if (strength(o) * (0.7f + 0.6f * r.nextFloat()) > best) { thief = o; best = strength(o) }
        }
        if (thief == null) return 0f
        val taken = 0.7f * kg
        thief.stored += taken
        stolen.merge(thief.species, 1, Int::plus)
        return taken
    }

    /**
     * Much stronger predators whose ranges overlap [g]'s: the more they overlap, the likelier they
     * meet; a meeting may cost [g] one of its number (less when it is watchful and at home) and
     * drives it off. Returns the days lost to it.
     */
    private fun meetEnemies(g: AnimalGroup, r: Random): Float {
        var lost = 0f
        for (p in enemies.getValue(g.species)) {
            val (o, d) = nearest(p, g.x, g.y, g.rangeKm + rangeKm(p))
            if (o == null || o.adults == 0) continue
            val ov = overlap(d, g.rangeKm, o.rangeKm)
            if (r.nextFloat() >= ov) continue
            val c = cellOf(g)
            val home = habitatMatch(g.species, cover(c), terrain.ruggedness[c], wetness[c])
            if (g.adults > 0 && r.nextFloat() < INTRAGUILD_KILL * (1.5f - home) * (1f - 0.5f * g.posture)) {
                g.adults--; deathCount.incrementAndGet()
                deathsBy.merge("${g.species.name}:killed by ${p.name}", 1, Int::plus)
                o.stored += g.species.massKg * 0.5f
            }
            shove(g, o)
            lost += 2f
        }
        return lost
    }

    /**
     * Packs defend their ground: when the ranges of two groups of a kind overlap, they spend days in
     * threat and chase instead of hunting, the smaller is usually driven off, and now and then one is
     * killed. Crowded packs therefore hunt less each; in studied wolf populations strife is a leading
     * cause of death. Returns the days lost.
     */
    private fun meetRivals(g: AnimalGroup, r: Random): Float {
        val (other, d) = nearest(g.species, g.x, g.y, g.species.territoryKm + g.rangeKm, except = g)
        if (other == null || other.adults == 0) return 0f
        if (r.nextFloat() >= overlap(d, g.species.territoryKm, other.rangeKm)) return 0f
        val loser = if (r.nextFloat() < strength(g) / (strength(g) + strength(other) + 1e-3f)) other else g
        // Most meetings end in a chase; a few in a death.
        if (loser.adults > 0 && r.nextFloat() < RIVAL_KILL) {
            loser.adults--; deathCount.incrementAndGet()
            soil.carrion[cellOf(loser)] += (loser.species.massKg / grid.cellAreaM2).toFloat()
        }
        shove(loser, if (loser === g) other else g)
        return 2f
    }

    // ---------------------------------------------------------------- water

    /**
     * Drinks if its range holds water; otherwise fresh food and dew cover part of the week's need
     * (more on wet ground), and the rest adds to the group's thirst. Past its limit it loses
     * condition fast.
     */
    private fun drink(g: AnimalGroup) {
        val s = g.species
        if (g.torpid) return
        val box = CoarseMaps.Box().also { it.set(maps, g.x, g.y, g.rangeKm) }
        val c = cellOf(g)
        if (mWater.box(box.x0, box.y0, box.x1, box.y1) > 0.0 || waterKm[c] <= 1.5f) {
            g.daysWithoutWater = 0f
            crocodile(g, mCroc.box(box.x0, box.y0, box.x1, box.y1).toFloat() / max(1f, mWater.box(box.x0, box.y0, box.x1, box.y1).toFloat()))
            return
        }
        val fromFood = s.waterFromFood * g.freshShare * (0.5f + 0.5f * wetness[c])
        g.daysWithoutWater += days * max(0f, 1f - fromFood)
        val over = g.daysWithoutWater - s.daysWithoutWater
        if (over > 0f) g.condition = max(0f, g.condition - 0.05f * min(over, days.toFloat()))
    }

    /**
     * A group drinking where crocodiles live ([share] of its watering places) may lose one of its
     * number in a week, the young most often; adult hippos are too big. Crocodiles lie still in
     * the cold months.
     */
    private fun crocodile(g: AnimalGroup, share: Float) {
        val c = cellOf(g)
        if (share <= 0f || sim.weather.temperature[c] < 16f || g.species.carnivore) return
        val p = CROC_WEEKLY * min(1f, share)
        if (rnd.nextFloat() >= p) return
        val young = g.young > 0 && (g.species.massKg > 500f || rnd.nextFloat() < 0.6f)
        if (young) g.young-- else if (g.species.massKg <= 500f) g.adults-- else return
        deathCount.incrementAndGet()
        deathsBy.merge("${g.species.name}:crocodile", 1, Int::plus)
    }

    /**
     * Crocodiles live in rivers carrying at least [CROC_RIVER] m³/s and in fresh lakes, where the
     * coldest month stays above 12 °C; eagles where cliffs and steep ground are near.
     */
    private fun findCrocodilesAndEagles() {
        val c = world.climate
        val big = 1e6f
        for (i in 0 until n) {
            val coldest = c.monthlyTemp.minOf { it[i] }
            val water = !world.isSea[i] && ((world.water.isLake(i) && !world.water.lakeClosed[i]) || world.water.discharge[i] >= CROC_RIVER)
            crocodiles[i] = if (water && coldest >= 12f) min(1f, (coldest - 12f) / 6f + 0.2f) else 0f
            crocKm[i] = if (crocodiles[i] > 0f) 0f else big
        }
        val d1 = cellKm; val d2 = cellKm * 1.4142f
        val w = grid.width; val h = grid.height
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            var d = crocKm[i]
            if (x > 0) d = min(d, crocKm[i - 1] + d1)
            if (y > 0) { d = min(d, crocKm[i - w] + d1); if (x > 0) d = min(d, crocKm[i - w - 1] + d2); if (x < w - 1) d = min(d, crocKm[i - w + 1] + d2) }
            crocKm[i] = d
        }
        for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
            val i = y * w + x
            var d = crocKm[i]
            if (x < w - 1) d = min(d, crocKm[i + 1] + d1)
            if (y < h - 1) { d = min(d, crocKm[i + w] + d1); if (x < w - 1) d = min(d, crocKm[i + w + 1] + d2); if (x > 0) d = min(d, crocKm[i + w - 1] + d2) }
            crocKm[i] = d
        }
        // Eagles: share of rugged ground within about 5 km.
        val r = max(1, (5 / cellKm).toInt())
        val step = max(1, r / 2)
        for (i in 0 until n) {
            if (world.isSea[i]) continue
            val x = grid.x(i); val y = grid.y(i)
            var sum = 0f; var cnt = 0
            for (dy in -r..r step step) for (dx in -r..r step step) if (grid.inBounds(x + dx, y + dy)) { sum += terrain.ruggedness[grid.index(x + dx, y + dy)]; cnt++ }
            eagles[i] = min(1f, 2f * sum / max(1, cnt))
        }
    }

    // ---------------------------------------------------------------- births, deaths, splitting

    /**
     * How crowded [g]'s range is with others of its kind, against the density its kind lives at when the
     * land is full (Damuth's rule for herbivores, field densities for predators): 1 = full.
     */
    private fun crowding(g: AnimalGroup): Float {
        val s = g.species
        val box = CoarseMaps.Box().also { it.set(maps, g.x, g.y, g.rangeKm) }
        // Others of its kind over its range: its own group does not crowd itself.
        val others = max(0f, mOcc[s.index].box(box.x0, box.y0, box.x1, box.y1).toFloat() - g.size)
        val density = others / (box.cells * COARSE_KM * COARSE_KM)
        val full = if (s.startKm2PerAnimal > 0f) CROWD_PREDATOR / s.startKm2PerAnimal else Allometry.herbivoreDensityPerKm2(s.massKg) * CROWD_HERBIVORE
        return density / full
    }

    /** Adds this week's food, its quality counting, to [g]'s memory of [month]. */
    private fun remember(g: AnimalGroup, month: Int) {
        val q = g.lastIntakeRatio * (0.5f + 0.5f * g.freshShare)
        g.foodByMonth[month] += 0.1f * (q - g.foodByMonth[month])
    }

    /**
     * Moves [g]'s birth season one month towards the timing that puts the young's first months on
     * the best food it remembers: the slow drift by which a population settles on its season.
     */
    private fun retime(g: AnimalGroup) {
        if (g.birthMonth < 0) g.birthMonth = g.species.birthMonth
        val f = g.foodByMonth
        var best = g.birthMonth; var bestFood = -1f
        for (m in 0 until 12) {
            // Births at m: mothers nurse through m and m+1, the young wean in m+2.
            val food = f[m] + f[(m + 1) % 12] + 0.5f * f[(m + 2) % 12]
            if (food > bestFood + 0.02f) { bestFood = food; best = m }
        }
        val ahead = (best - g.birthMonth + 12) % 12
        if (ahead != 0) g.birthMonth = (g.birthMonth + if (ahead <= 6) 1 else 11) % 12
    }

    private fun binomial(n: Int, p: Float, rnd: Random = this.rnd): Int {
        if (n <= 0 || p <= 0f) return 0
        if (p >= 1f) return n
        if (n > 30) {
            // Large groups: the normal approximation, not one draw per animal.
            val mean = n * p; val sd = sqrt(n * p * (1f - p))
            val u = max(rnd.nextDouble(), 1e-12); val z = sqrt(-2 * kotlin.math.ln(u)) * kotlin.math.cos(2 * Math.PI * rnd.nextDouble())
            return (mean + sd * z + 0.5).toInt().coerceIn(0, n)
        }
        var k = 0
        repeat(n) { if (rnd.nextFloat() < p) k++ }
        return k
    }

    private fun demography() {
        val years = days / Date.DAYS_PER_YEAR.toFloat()
        val date = sim.weather.date
        val firstStepOfMonth = date.dayOfMonth <= days
        val newcomers = ArrayList<AnimalGroup>()
        // Crowding for every group at once: it only reads this week's maps.
        val crowds = FloatArray(groups.size)
        java.util.stream.IntStream.range(0, groups.size).parallel().forEach { k -> if (groups[k].alive) crowds[k] = crowding(groups[k]) }
        // Each group's births and deaths are its own affair: all groups at once, each with its own
        // random stream; what they leave on the ground and how they split is applied after.
        val carrionKg = FloatArray(groups.size)
        val splitA = IntArray(groups.size); val splitY = IntArray(groups.size)
        java.util.stream.IntStream.range(0, groups.size).parallel().forEach { gi ->
            val g = groups[gi]
            if (!g.alive) return@forEach
            val r = groupRandom(g, 1)
            val s = g.species
            val c = cellOf(g)
            val fit = ((g.condition - 0.3f) / 0.4f).coerceIn(0f, 1f)
            // The group remembers how good each month's food was, and over the years shifts its
            // births towards the months that feed mothers and young best.
            if (g.birthMonth < 0) g.birthMonth = s.birthMonth
            remember(g, date.month)
            if (firstStepOfMonth && date.month == 0) retime(g)
            // Births in the season; small breeders fit in more than one litter a year.
            for (litter in 0 until s.littersPerYear) {
                val start = (g.birthMonth + litter * 12 / s.littersPerYear) % 12
                val inSeason = (date.month - start + 12) % 12 < s.birthMonths
                if (!inSeason) continue
                val born = if (s.packLitter > 0) {
                    if (firstStepOfMonth && date.month == start && g.adults >= 2) (s.packLitter * fit + r.nextFloat()).toInt() else 0
                } else {
                    val stepsInSeason = s.birthMonths * Date.DAYS_PER_MONTH / days.toFloat()
                    binomial(g.adults, 0.5f * s.youngPerFemale / s.littersPerYear * fit / stepsInSeason, r)
                }
                g.young += born; birthCount.addAndGet(born)
            }
            // Growing up.
            val matured = binomial(g.young, years / s.yearsToMaturity, r)
            g.young -= matured; g.adults += matured
            // Deaths: background, hunger, and the world.
            var pAdult = s.backgroundMortality * years
            if (g.condition < 0.2f) pAdult += 0.15f
            val flood = sim.water.flood[c]
            if (flood > 1.5f) pAdult += 0.2f
            if (sim.eruptions.ash[c] > 300f) pAdult = 1f
            if (sim.weather.snowpack[c] > 3f * s.snowLimitMm && sim.weather.temperature[c] < -5f) pAdult += 0.1f
            // Young die twice as often, and far more when the group is hungry: for wolves, pup
            // survival is what tracks the food supply.
            val hunger = ((0.5f - g.condition) / 0.3f).coerceIn(0f, 1f)
            // Crowding breeds parasites and disease: beyond what its ground carries, deaths rise,
            // and a crowded population now and then suffers an outbreak.
            val crowd = crowds[gi]
            pAdult += DISEASE * years * max(0f, crowd - 1f)
            if (crowd > OUTBREAK_CROWDING && r.nextFloat() < OUTBREAK_WEEKLY * (crowd - OUTBREAK_CROWDING)) {
                val severity = 0.1f + 0.3f * r.nextFloat()
                val sick = binomial(g.adults, severity, r) + binomial(g.young, min(1f, 1.5f * severity), r)
                if (sick > 0) {
                    val a = min(g.adults, sick); g.adults -= a; g.young = max(0, g.young - (sick - a))
                    deathCount.addAndGet(sick); deathsBy.merge("${s.name}:outbreak", sick, Int::plus)
                }
            }
            // Hard cold or heat kills the young and the weak first.
            val stress = g.thermal * g.thermal
            pAdult += THERMAL_DEATHS * years * stress * (1.5f - g.condition)
            // Eagles take the young of the smaller hoofed animals in the open.
            val eagle = if (!s.carnivore && s.massKg <= 80f) EAGLE_RATE * years * eagles[c] * (1f - cover(c)) else 0f
            val pYoung = min(1f, pAdult + s.youngMortality * years + 0.1f * hunger + eagle + THERMAL_DEATHS * years * stress)
            val deadA = binomial(g.adults, pAdult, r)
            val deadY = binomial(g.young, pYoung, r)
            if (deadA + deadY > 0) {
                val cause = when {
                    sim.eruptions.ash[c] > 300f -> "ash"
                    crowd > 1.2f && g.condition >= 0.2f -> "disease"
                    g.thermal > 1f -> if (sim.weather.temperature[c] < s.comfortC.start) "cold" else "heat"
                    g.condition < 0.2f && g.daysWithoutWater > s.daysWithoutWater -> "thirst"
                    g.condition < 0.2f -> "hunger"
                    flood > 1.5f -> "flood"
                    sim.weather.snowpack[c] > 3f * s.snowLimitMm && sim.weather.temperature[c] < -5f -> "snow"
                    else -> "other"
                }
                deathsBy.merge("${s.name}:$cause", deadA + deadY, Int::plus)
            }
            // A pack or herd without adults does not survive.
            g.adults -= deadA
            val orphaned = if (g.adults == 0) g.young - deadY else 0
            if (orphaned > 0) deathsBy.merge("${s.name}:orphaned", orphaned, Int::plus)
            g.young -= deadY + orphaned
            val dead = deadA + deadY + orphaned
            deathCount.addAndGet(dead)
            if (dead > 0) carrionKg[gi] = s.massKg * (deadA + 0.5f * (deadY + orphaned))
            // Splitting: a group too big for its food divides.
            if (g.size > s.maxGroupSize) {
                splitA[gi] = g.adults / 2; splitY[gi] = g.young / 2
                g.adults -= splitA[gi]; g.young -= splitY[gi]
            }
        }
        for ((gi, g) in groups.withIndex()) {
            if (carrionKg[gi] > 0f) soil.carrion[cellOf(g)] += (carrionKg[gi] / grid.cellAreaM2).toFloat()
            if (splitA[gi] + splitY[gi] == 0) continue
            val child = AnimalGroup(nextId++, g.species, g.x + rnd.nextDouble() - 0.5, g.y + rnd.nextDouble() - 0.5, splitA[gi], splitY[gi])
            child.condition = g.condition
            child.tag = g.tag
            child.birthMonth = g.birthMonth
            g.foodByMonth.copyInto(child.foodByMonth)
            g.seasonX.copyInto(child.seasonX); g.seasonY.copyInto(child.seasonY); g.seasonQ.copyInto(child.seasonQ)
            child.fare = g.fare
            child.fat = g.fat
            newcomers += child
        }
        groups += newcomers
        // Joining: small remnants merge into a nearby group of their kind.
        val bySpecies = groups.filter { it.alive }.groupBy { it.species }
        for ((s, list) in bySpecies) {
            fun alone(g: AnimalGroup) = g.size < s.groupSize / 3 || (s.packLitter > 0 && g.adults < 2)
            if (list.none { alone(it) }) continue
            val b = buckets[s.index]
            for (l in b) l.clear()
            for (g in list) b[bucketIndex(g.x, g.y)] += g
            for (g in list) {
                // A pair-breeding predator left without a mate looks for one as well.
                if (!g.alive || !alone(g)) continue
                val (other, _) = nearest(s, g.x, g.y, if (s.packLitter > 0) 3f * s.dailyTravelKm else 5f, except = g)
                if (other == null || other.size + g.size > s.maxGroupSize) continue
                other.adults += g.adults; other.young += g.young
                g.adults = 0; g.young = 0
            }
        }
    }

    companion object {
        /** Share of standing herb and shrub biomass that is young, palatable growth. Calibration constant. */
        const val PALATABLE_SHARE = 0.0003f
        /** Days for grazed palatable growth to come back. */
        const val REGROWTH_DAYS = 30f
        /** Chance per kill that a dangerous adult prey (defence 1, at least twice a hunter's mass) kills a hunter. */
        const val FIGHT_BACK = 0.02f
        /** Distance from which rivals notice a kill and come to take it, km. */
        const val THEFT_KM = 2f
        /** Chance a much stronger predator kills one of a weaker group it meets. */
        const val INTRAGUILD_KILL = 0.005f
        /** Chance a group whose watering places are all crocodile water loses one, per week. */
        const val CROC_WEEKLY = 0.05f
        /** Smallest river that holds crocodiles, m³/s mean flow. */
        const val CROC_RIVER = 3f
        /** Yearly share of young taken by eagles in open, cliff-rich country. */
        const val EAGLE_RATE = 0.15f
        /** Chance a chase after a young animal succeeds, before its group's defence: most are hidden or guarded. */
        const val YOUNG_CATCH = 0.15f
        /** Bulbs, roots and mast available to rooting animals, per kg/m² of herbs (and trees x 0.05). */
        const val ROOTS_SHARE = 0.0003f
        /** Nourishment of dead grass and twigs relative to fresh growth, for a 100 kg animal. */
        const val ROUGHAGE_VALUE = 0.4f
        /** Most a herbivore can eat in a day, as a multiple of its daily need: coarse food fills the gut before it nourishes. */
        const val GUT_FILL = 1.5f
        /** Dead grass and twigs standing as fodder, per kg/m² of herbs (and shrubs x 0.3). Calibration constant. */
        const val STRAW_SHARE = 0.0025f
        /** Days for eaten-down straw to build up again. */
        const val STRAW_DAYS = 180f
        /** Ripe fruit on fruiting plants at the season's peak, per kg/m² of them. Calibration constant. */
        const val FRUIT_SHARE = 0.0008f
        /** Days ripe fruit lasts before it falls, rots or is gone. */
        const val FRUIT_DAYS = 30f
        /** Days for dug-up roots and mast to be replaced. */
        const val ROOTS_DAYS = 180f
        /** Size of the coarse summary grid, km. */
        const val COARSE_KM = 2f
        /** A group's weekly range radius, in days' travel. */
        const val RANGE_FACTOR = 0.8f
        /** Moving the centre further than this many range radii in a week is a march. */
        const val MARCH_RANGES = 3.5f
        /** Candidate ranges per decision: staying, 8 directions at 3 distances, and the season's range. */
        const val CANDIDATES = 26
        /** Predator mass per km² over a range at which danger reads one half. */
        const val DANGER_KG_KM2 = 3f
        /** Lure-weighted prey per km² at which the draw of prey reads one half. */
        const val PREY_NORM = 1f
        /** Posture per unit of sensed danger. */
        const val POSTURE_GAIN = 1f
        /** Share of grazing a fully watchful herd gives up. */
        const val POSTURE_FEED_COST = 0.3f
        /** Cells around the centre within which straw, carrion and rodents are taken. */
        const val STRAW_REACH = 2
        /** Most chases a predator group makes in a week. */
        const val MAX_CHASES = 24f
        /** Chases per day per unit of range overlap with a herd. */
        const val CHASES_PER_CONTACT = 1f
        /** Weeks a herd stays alarmed after an attack. */
        const val ALERT_WEEKS = 2
        /** Chance a hunt takes even an open, unwatchful herd by surprise. */
        const val SURPRISE_BASE = 0.2f
        /** How much full watchfulness cuts the chance of surprise. */
        const val WATCH_EFFECT = 0.8f
        /** Share of hunts an aware herd fully in its element defeats. */
        const val AWARE_DEFENCE = 0.5f
        /** Chance a rival whose range fully overlaps notices a kill. */
        const val THEFT = 0.5f
        /** Chance a territorial clash costs the loser one of its number. */
        const val RIVAL_KILL = 0.05f
        /** Extra deaths per year per unit of crowding beyond full. */
        const val DISEASE = 0.5f
        /** Crowding beyond which outbreaks strike, and their weekly chance per unit beyond it. */
        const val OUTBREAK_CROWDING = 1.5f
        const val OUTBREAK_WEEKLY = 0.02f
        /** Herbivore density at which the land counts as full, as a share of Damuth's rule. */
        const val CROWD_HERBIVORE = 0.5f
        /** Predator density at which the land counts as full, as a multiple of the starting density. */
        const val CROWD_PREDATOR = 4f
        /** Condition lost per day per unit of thermal stress. */
        const val THERMAL_COST = 0.006f
        /** Yearly deaths per unit of squared thermal stress (young more, weak adults more). */
        const val THERMAL_DEATHS = 0.6f
        /** Size of the grid on which herds' inherited seasonal ranges are judged, km. */
        const val TRADITION_KM = 16f
        /** Weeks of marching within which inherited seasonal ranges lie. */
        const val TRADITION_WEEKS = 3f
        /** Extra food a herd eats to lay on fat, as a share of its need: in most weeks, and in late summer and autumn. */
        const val FATTEN = 0.2f
        const val FATTEN_AUTUMN = 0.6f
        /** Days of fat laid on per day of surplus food. */
        const val FAT_EFFICIENCY = 0.7f
        /** Share of its food a hibernating animal burns. */
        const val HIBERNATION_NEED = 0.25f
        /** Weekly mean temperature below which hibernators den up, °C. */
        const val HIBERNATE_C = 2f
        /** Size of the tiles predators are worked in, km: more than twice the furthest a group reaches in a week. */
        const val TILE_KM = 80f
        /** Stands for a place a group cannot go. */
        val NOWHERE = Option(-1, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        /** Animal mass whose passage counts as trampling intensity 1 (see Vegetation.trample). */
        const val TRAMPLE_KG = 150_000f
        private val COS = DoubleArray(8) { kotlin.math.cos(it * Math.PI / 4) }
        private val SIN = DoubleArray(8) { kotlin.math.sin(it * Math.PI / 4) }
        /** Ground wetter than this has rain pools, seeps or springs to drink from (when not frozen). */
        const val POOL_WETNESS = 0.8f
        /** A week's march covers this share of a species' longest day's travel, every day. */
        const val MARCH_SHARE = 0.6f
        /** Share of its daily food a marching herd eats on the way. */
        const val MARCH_APPETITE = 0.3f
        /** Share of runoff that sinks in and flows on underground. */
        const val GROUND_SHARE = 0.3f
        /** Groundwater flow below a cell that can feed a spring, m³/yr (about a litre a second). */
        const val MIN_SPRING_FLOW = 3e4f
        /** Dry country per spring or water hole where groundwater is ample, km². */
        const val SPRING_KM2 = 150f
    }
}
