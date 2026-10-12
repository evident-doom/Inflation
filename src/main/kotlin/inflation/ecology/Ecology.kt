package inflation.ecology

import inflation.world.Date
import inflation.world.LifeForm
import inflation.world.Simulation
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
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

    /** Which landmass each cell is on (connected land), for the tongues people start with. */
    private val landmass: IntArray by lazy {
        val id = IntArray(n) { -1 }
        var next = 0
        val queue = IntArray(n)
        for (start in 0 until n) {
            if (id[start] >= 0 || world.isSea[start]) continue
            var head = 0; var tail = 0
            queue[tail++] = start; id[start] = next
            while (head < tail) {
                val c = queue[head++]
                grid.forNeighbours(c) { nb, _ -> if (id[nb] < 0 && !world.isSea[nb]) { id[nb] = next; queue[tail++] = nb } }
            }
            next++
        }
        id
    }


    val plankton = Plankton(sim)
    val soil = SoilLife(sim)
    val small = SmallFauna(sim)
    val fish = Fish(sim, plankton)
    val marine = Marine(sim, plankton, fish)
    val game = SmallGame(sim, small, soil)

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
     * How nourishing the grass is, 0..1: protein-rich in the spring flush and where grazing keeps
     * it short and growing, poor once it has matured and dried.
     */
    val grassQuality = FloatArray(n) { 0.6f }
    /** Grass and browse eaten this week, kg/km², passed on to the plants. */
    private val grazedNow = FloatArray(n)
    private val browsedNow = FloatArray(n)
    private val rootedNow = FloatArray(n)
    /** Fruit eaten this week per coarse cell, spread over the eaters' ranges: where their seed lands. */
    private val seedDrop by lazy { FloatArray(maps.size) }
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
    val enemies: Map<Species, List<Species>> = Catalogue.all.associateWith { s ->
        // Camp followers and people leave each other be.
        Catalogue.predators.filter { p ->
            p != s && !(p.human && s.followsPeople) && !(s.human && p.followsPeople) &&
                // People keep clear of the big cats, which take a sleeper or a straggler now and then.
                (threatens(p, s) || (s.human && p.dangerToHumans >= DANGEROUS_TO_PEOPLE))
        }
    }
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
    /** Animals taken in kite drives since the start. */
    private val kiteCount = java.util.concurrent.atomic.AtomicInteger()
    val kiteKills: Int get() = kiteCount.get()
    private val birthCount = java.util.concurrent.atomic.AtomicInteger()
    val births: Int get() = birthCount.get()
    val deaths: Int get() = deathCount.get()
    /** Kills by prey species since the counter was last cleared. */
    val killsBy = java.util.concurrent.ConcurrentHashMap<Species, Int>()
    /** Kills by predator species, and kills taken from others by predator species, since last cleared. */
    val killsByPredator = java.util.concurrent.ConcurrentHashMap<Species, Int>()
    /** What people have killed, by species, since the start. */
    val peopleKills = java.util.concurrent.ConcurrentHashMap<Species, Int>()
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
        timed("fields") { plankton.step(); fish.step(); soil.step(); small.step(); game.step() }
        timed("sea") { marine.step() }
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
            // How big the crop is depends on how well the spring's flowers were pollinated.
            val ripe = if (month in 4..7) FRUIT_SHARE * 1e6f * fruitBearing(i) * (0.3f + 0.7f * small.pollination[i]) / FRUIT_DAYS else 0f
            fruit[i] = max(0f, fruit[i] + days * (ripe - fruit[i] / FRUIT_DAYS))
            graze[i] = max(0f, graze[i] + days * (g - graze[i] / REGROWTH_DAYS))
            // Growing grass is rich; mature, dry grass poor. Grazing keeps a lawn short and green.
            val growing = warm * wet * if (month in 0..1) 1.2f else 1f
            val lawn = 1f - exp(-v.grazing[i] / 5f)
            // After a fire the new grass comes up green and rich: grazers come to the burn.
            val flushAfterFire = 0.3f * v.burnt[i] * warm
            grassQuality[i] += 0.3f * ((0.3f + 0.6f * growing.coerceAtMost(1f) + 0.15f * lawn + flushAfterFire).coerceAtMost(1f) - grassQuality[i])
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

    // ---------------------------------------------------------------- fire

    /**
     * A group whose ground is burning runs: most get away, but the young, the slow and the small
     * are caught by the flames now and then.
     */
    private fun flee(g: AnimalGroup) {
        val c = cellOf(g)
        if (!sim.vegetation.burning[c]) return
        val s = g.species
        // People know fire: they see it coming, burn breaks and step onto the burnt ground behind it.
        val slow = (40f / s.speedKmh).coerceIn(0.5f, 2f) * if (s.human) 0.1f else 1f
        val r = groupRandom(g, 3)
        val deadY = binomial(g.young, min(1f, FIRE_YOUNG * slow), r)
        val deadA = binomial(g.adults, min(1f, FIRE_ADULT * slow * if (g.torpid) 4f else 1f), r)
        if (deadA + deadY == 0) return
        g.young -= deadY; g.adults -= deadA
        deathCount.addAndGet(deadA + deadY)
        deathsBy.merge("${s.name}:fire", deadA + deadY, Int::plus)
        soil.carrion[c] += (s.massKg * (deadA + 0.5f * deadY) / grid.cellAreaM2).toFloat()
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
        if (s.human) g.coat = g.tools?.get(Tool.CLOTHES.ordinal) ?: 0.5f // clothes and shelter are their coat
        else if (s.winterCoat) g.coat = if (t < s.comfortC.start + 10f) min(1f, g.coat + 0.15f) else max(0f, g.coat - 0.2f)
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
     * the few springs. Distance in a straight line, exact.
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
        inflation.world.distanceKm(grid, waterKm, big) { waterKm[it] == 0f }
    }

    // ---------------------------------------------------------------- initial populations

    /** How well cell [i] suits species [s] to start in, 0..1, from its food and favoured ground. */
    private fun suitability(s: Species, i: Int): Float {
        val v = sim.vegetation
        if (!v.isVegetated(i) || world.elevation[i] > 2500f || waterKm[i] > min(15f, s.maxWaterKm)) return 0f
        val food = ((s.diet.grass * v.herb[i] / 0.3f + s.diet.browse * woody(i) / 0.45f + s.diet.roots * (v.herb[i] + 0.05f * v.tree[i]) / 0.7f) / plantDiet(s)).coerceIn(0f, 1f)
        val h = s.habitat
        val ground = 0.5f + h.cover * (cover(i) - 0.5f) + h.rugged * (terrain.ruggedness[i] - 0.3f) * 1.5f + h.wet * (wetness[i] - 0.3f)
        // Each starts where the climate suits it: the coldest month within its range with a full
        // winter coat, the warmest within it outright; each 10 °C beyond halves the chance.
        val coat = if (s.winterCoat) 10f else 0f
        val t = world.climate.monthlyTemp
        val cold = max(0f, s.comfortC.start - coat - t.minOf { it[i] })
        val hot = max(0f, t.maxOf { it[i] } - s.comfortC.endInclusive)
        val climate = 1f / (1f + (cold + hot) / 10f)
        return food * ground.coerceIn(0f, 1f) * climate
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
        group.bold = s.boldShare * (0.5f + rnd.nextFloat())
        if (s.human) {
            // A band knows the game and plants of its home ground from long practice: the more
            // there is of a species around, the better it hunts it.
            group.craft = FloatArray(Catalogue.all.size) { k ->
                val q = Catalogue.all[k]
                if (q.carnivore) 0f else (0.2f + 0.6f * suitability(q, cell)).coerceIn(0f, 1f)
            }
            group.elders = (group.adults * 0.15f).toInt()
            // A Natufian band starts with the kit of its time, worn to middling.
            group.goods = FloatArray(Material.entries.size) { 5f }
            group.tools = FloatArray(Tool.entries.size) { 0.6f }
            group.making = FloatArray(Making.entries.size) { 0.5f }
            // Traditions are regional: neighbouring bands mostly share them.
            val region = java.util.Random(world.config.seed * 31L + (grid.x(cell) / (LINEAGE_KM / cellKm)).toInt() + 7 * (grid.y(cell) / (LINEAGE_KM / cellKm)).toInt())
            group.traditions = BooleanArray(Tradition.entries.size) { region.nextFloat() < TRADITION_START }
            // One ancestral tongue per landmass: people who reached it long ago spoke alike. Separate
            // landmasses were settled separately. Dialects grow from here, by distance and barriers.
            val mass = landmass[cell]
            group.places = ArrayList()
            group.words = IntArray(Language.CONCEPTS) { k -> java.util.Random(world.config.seed * 977L + k * 131L + mass * 7919L).nextInt(Language.FORMS) }
            // Neighbouring bands share descent; distant ones do not.
            group.lineage = FloatArray(LINEAGES).also { it[((grid.x(cell) / (LINEAGE_KM / cellKm)).toInt() + 7 * (grid.y(cell) / (LINEAGE_KM / cellKm)).toInt()) % LINEAGES] = 1f }
            group.forageCraft = FloatArray(Forage.entries.size) { f ->
                when (Forage.entries[f]) {
                    Forage.FRUIT, Forage.ROOTS, Forage.SMALL_GAME -> 0.6f
                    Forage.FISH -> if (waterKm[cell] < 3f) 0.5f else 0.1f
                    Forage.STORE -> 0.4f
                }
            }
        }
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
        moonlight = sim.weather.date.meanMoonlight(days).toFloat()
        bandsById.clear(); for (g in groups) if (g.species.human && g.alive) bandsById[g.id] = g
        for (k in claimStrength.indices) if (claimOwner[k] >= 0) { claimStrength[k] *= CLAIM_KEEP; if (claimStrength[k] < 0.5f || claimOwner[k] !in bandsById) { claimOwner[k] = -1; claimStrength[k] = 0f } }
        timed("summarise") { summarise() }
        val size = groups.size
        if (planX.size < size) { planX = DoubleArray(size * 2); planY = DoubleArray(size * 2) }
        timed("plan") {
            // Groups are planned in map order, so neighbours share the coarse maps in the processor's
            // cache; each writes only its own slot, so the order does not change the outcome.
            val order = LongArray(size) { k ->
                val g = groups[k]
                (zOrder((g.x / maps.ratio).toInt(), (g.y / maps.ratio).toInt()) shl 32) or k.toLong()
            }
            order.sort()
            java.util.stream.IntStream.range(0, size).parallel().forEach { j ->
                val k = (order[j] and 0xffffffffL).toInt()
                val g = groups[k]
                if (g.alive) plan(g, k, scratch.get()) else planX[k] = Double.NaN
            }
        }
        for (k in 0 until size) {
            val g = groups[k]
            if (!g.alive) continue
            val moved = if (planX[k].isNaN()) 0f else go(g, planX[k], planY[k])
            if (g.species.human) {
                // Moving camp: what cannot be carried is left behind (cached stores are lost to mice and thieves).
                if (moved > g.rangeKm) {
                    g.campWeeks = 0; g.larder = min(g.larder, CARRY_DAYS * g.dailyNeedKg)
                    g.tools?.let { k -> for (tl in Tool.entries) if (tl.heavy) k[tl.ordinal] = 0f }
                } else g.campWeeks++
            }
            g.marching = moved > MARCH_RANGES * g.rangeKm
            // The further beyond its usual drift, the more of the week goes into walking, not grazing.
            val reach = MARCH_SHARE * g.species.maxDailyTravelKm * days
            g.walking = ((moved - g.rangeKm) / max(1f, reach - g.rangeKm)).coerceIn(0f, 1f)
            g.movedKm = moved
            if (g.alert > 0) g.alert--
        }
        timed("occupancy") { occupancy() }
        timed("watch") { watch() }
        timed("graze") { graze(); shapePlants() }
        timed("prowl") { prowlAll() }
        timed("drink") { for (g in groups) if (g.alive) drink(g) }
        timed("weather") { groups.parallelStream().forEach { g -> if (g.alive) { endure(g); g.wary *= WARY_KEEP; g.grudge?.let { gr -> for (k in gr.indices) gr[k] *= GRUDGE_KEEP }; g.words?.let { w -> driftWords(g, w) }; g.renown *= RENOWN_KEEP } } }
        timed("fire") { for (g in groups) if (g.alive) flee(g) }
        timed("seasons") { groups.parallelStream().forEach { g -> if (g.alive && (!g.species.carnivore || g.species.human)) learnSeason(g) } }
    }

    private var planX = DoubleArray(0)
    private var planY = DoubleArray(0)
    /** Mean moonlight over this week's nights, 0 (new moon) to 1 (full). */
    private var moonlight = 0.5f

    /** Morton (Z-order) key of a coarse cell: cells close on the map get close keys. */
    private fun zOrder(x: Int, y: Int): Long {
        fun spread(v: Int): Long {
            var b = (v.coerceIn(0, 0xffff)).toLong()
            b = (b or (b shl 8)) and 0x00ff00ffL
            b = (b or (b shl 4)) and 0x0f0f0f0fL
            b = (b or (b shl 2)) and 0x33333333L
            return (b or (b shl 1)) and 0x55555555L
        }
        return spread(x) or (spread(y) shl 1)
    }

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
    private fun strength(g: AnimalGroup) = strength(g.species, g.adults) * fitness(g) * (if (g.species.human) armed(g) / g.species.ferocity else 1f)

    /**
     * Fighting weight per kg of [g], from its natural or made weapons: a predator's [Species.ferocity]
     * (a lion's claws and jaws against a wolf's bite), or for people their spears and bows, without
     * which they are no match for anything their size.
     */
    private fun armed(g: AnimalGroup): Float {
        val s = g.species
        val k = g.tools ?: return s.ferocity
        return UNARMED_PEOPLE + (s.ferocity - UNARMED_PEOPLE) * k[Tool.SPEAR.ordinal] + 0.5f * k[Tool.BOW.ordinal]
    }

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
    /** The ground, plant and weather layers, read together for every candidate range. */
    private val fine = maps.stack(20)
    /** Per species: its numbers, its enemies and its prey, read together. */
    private val perSpecies = Array(Catalogue.all.size) { maps.stack(3) }
    private val mLand = fine.layer()
    private val mGraze = fine.layer()
    private val mBrowse = fine.layer()
    private val mRoots = fine.layer()
    private val mFruit = fine.layer()
    /** Grass weighted by its quality, to average quality over a range. */
    private val mGrazeQ = fine.layer()
    private val mStraw = fine.layer()
    private val mWater = fine.layer()
    private val mWaterKm = fine.layer()
    private val mCover = fine.layer()
    private val mRugged = fine.layer()
    private val mWet = fine.layer()
    private val mSnow = fine.layer()
    private val mCost = fine.layer()
    private val mRodents = fine.layer()
    private val mCroc = fine.layer()
    private val mTemp = fine.layer()
    /** Next week's weather, as far as it can be sensed: temperature, rain against normal, and dangers. */
    private val mTempAhead = fine.layer()
    private val mRainAhead = fine.layer()
    private val mHazard = fine.layer()
    /** Animals of each species per coarse cell, each group spread over its range. */
    private val mOcc = Array(Catalogue.all.size) { perSpecies[it].layer() }
    /** Per species: mass of its enemies per coarse cell; for predators, lure-weighted prey. */
    private val mDanger = Array(Catalogue.all.size) { perSpecies[it].layer() }
    private val mPrey = Array(Catalogue.all.size) { perSpecies[it].layer() }
    private val fineLayers = listOf(mTempAhead, mRainAhead, mHazard, mTemp, mLand, mGraze, mGrazeQ, mBrowse, mRoots, mFruit, mStraw, mWater, mWaterKm, mCover, mRugged, mWet, mSnow, mCost, mRodents, mCroc)

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
            // Fire burning now drives everything away.
            if (sim.vegetation.burning[i]) mHazard.cell[m] += 1f
            // A flood is coming where heavy rain is due on a floodplain or where water already stands.
            if (world.floodplain.isFloodplain[i] || sim.water.flood[i] > 0f) mHazard.cell[m] += ((ahead - 1.5f) / 2f).coerceIn(0f, 1f)
            mGraze.cell[m] += graze[i] * cellKm2
            mGrazeQ.cell[m] += graze[i] * cellKm2 * grassQuality[i]
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
            mCost.cell[m] += min(10f, terrain.travelCost(i, v, sim.weather.snowpack[i], dischargeNow = sim.water.discharge[i]))
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
        fine.build()
        occupancy()
    }

    /** Spreads every group over its range on the coarse grid, and files groups by place for finding neighbours. */
    private fun occupancy() {
        // Each species has its own layer and buckets, so species are filled in parallel; within a
        // species groups are added in list order, so the sums come out the same every run.
        val bySpecies = Array(Catalogue.all.size) { ArrayList<AnimalGroup>() }
        for (g in groups) if (g.alive) bySpecies[g.species.index] += g
        java.util.stream.IntStream.range(0, Catalogue.all.size).parallel().forEach { si ->
            val occ = mOcc[si]
            occ.clear()
            val bucket = buckets[si]
            for (l in bucket) l.clear()
            val box = CoarseMaps.Box()
            val cell = occ.cell
            for (g in bySpecies[si]) {
                bucket[bucketIndex(g.x, g.y)] += g
                box.set(maps, g.x, g.y, g.rangeKm)
                val per = g.size.toFloat() / box.cells
                for (y in box.y0 until box.y1) for (x in box.x0 until box.x1) cell[y * maps.w + x] += per
            }
        }
        // Per species: the mass of its enemies over the map, and for predators the draw of prey there.
        java.util.stream.IntStream.range(0, Catalogue.all.size).parallel().forEach { si ->
            val s = Catalogue.all[si]
            val d = mDanger[si].cell
            d.fill(0f)
            for (e in enemies.getValue(s)) { val o = mOcc[e.index].cell; for (m in d.indices) d[m] += o[m] * e.massKg }
            if (s.carnivore) {
                val pv = mPrey[si].cell
                pv.fill(0f)
                val w = lure.getValue(s)
                for (q in Catalogue.herbivores) { val o = mOcc[q.index].cell; val wq = w[q.index]; for (m in pv.indices) pv[m] += o[m] * wq }
                // Camp followers are drawn to people's camps and their scraps.
                if (s.followsPeople) { val o = mOcc[Catalogue.HUMAN.index].cell; for (m in pv.indices) pv[m] += o[m] * CAMP_LURE }
            }
            perSpecies[si].build()
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
     * drift and as far as a forced march reaches in a week. Each is described by its totals. A
     * group that is fed, watered, comfortable and unalarmed does not weigh a forced march.
     */
    private fun gather(g: AnimalGroup, sc: Scratch) {
        val s = g.species
        val r = g.rangeKm
        val restless = s.carnivore || g.lastIntakeRatio < 0.9f || g.daysWithoutWater > 0.5f * s.daysWithoutWater || g.thermal > 0.2f || g.alert > 0
        val reach = MARCH_SHARE * s.maxDailyTravelKm * days
        val options = sc.options
        options.clear()
        var k = 0
        for (ring in 0..3) {
            val d = when (ring) { 0 -> 0f; 1 -> r; 2 -> min(reach, 3f * r); else -> reach }
            val dirs = if (ring == 0) 1 else 8
            for (dir in 0 until dirs) {
                if (ring == 3 && (reach <= 3f * r || !restless)) { options += NOWHERE; sc.tx[k] = g.x; sc.ty[k] = g.y; k++; continue }
                val x = g.x + COS[dir] * d / cellKm; val y = g.y + SIN[dir] * d / cellKm
                sc.tx[k] = x; sc.ty[k] = y; k++
                options += describe(g, x, y, d, sc.box)
            }
        }
        // People in need of water head for the nearest spring or river they know of, however far.
        if (s.human) {
            val known = g.places?.filter { it.kind == PlaceKind.WATER }?.minByOrNull { dist(it.x - g.x, it.y - g.y) }
            val thirsty = g.daysWithoutWater > 0.3f * s.daysWithoutWater || waterKm[cellOf(g)] > 5f
            if (known != null && thirsty && dist(known.x - g.x, known.y - g.y) * cellKm > r) {
                val toGo = (dist(known.x - g.x, known.y - g.y) * cellKm).toFloat()
                val step = min(toGo, reach) / toGo
                val x = g.x + (known.x - g.x) * step; val y = g.y + (known.y - g.y) * step
                sc.tx[k] = x; sc.ty[k] = y; k++
                options += describe(g, x, y, min(toGo, reach), sc.box)
            } else { options += NOWHERE; sc.tx[k] = g.x; sc.ty[k] = g.y; k++ }
        }
        // In good times bands go to their gathering place: feasting bands most of all.
        if (s.human) {
            val site = g.places?.filter { it.kind == PlaceKind.GATHERING }?.minByOrNull { dist(it.x - g.x, it.y - g.y) }
            // Gatherings fall in the season of plenty: the band's best month for food, and the one before and after.
            val best = g.foodByMonth.indices.maxByOrNull { g.foodByMonth[it] } ?: 0
            val season = (sim.weather.date.month - best + 12) % 12 in setOf(0, 1, 11)
            // A settled band with full stores stays put and is visited: its base is the gathering place.
            val settled = g.campWeeks > 8 && g.larder > 20f * g.dailyNeedKg
            val festive = season && !settled && g.fare > (if (g.holds(Tradition.FEASTS)) 0.6f else 0.75f)
            if (site != null && festive && dist(site.x - g.x, site.y - g.y) * cellKm in r.toDouble()..GATHER_KM.toDouble()) {
                val toGo = (dist(site.x - g.x, site.y - g.y) * cellKm).toFloat()
                val step = min(toGo, reach) / toGo
                val x = g.x + (site.x - g.x) * step; val y = g.y + (site.y - g.y) * step
                sc.tx[k] = x; sc.ty[k] = y; k++
                val o = describe(g, x, y, min(toGo, reach), sc.box)
                // The pull of a gathering: partners, news, exchange and feasting.
                options += if (o.cell < 0) o else Option(o.cell, o.km, o.forage, o.danger, o.prey, o.kin, o.waterKm, o.snowMm, o.cover, o.rugged, o.wet, o.thermal, GATHER_PULL, o.hazard, o.rainAhead)
            } else { options += NOWHERE; sc.tx[k] = g.x; sc.ty[k] = g.y; k++ }
        }
        // The season's range: a week's march towards where it remembers doing well in the coming weeks.
        val m = seasonAhead()
        val sx = g.seasonX[m]; val sy = g.seasonY[m]
        if (sx.isNaN() || (s.carnivore && !s.human)) { options += NOWHERE; sc.tx[k] = g.x; sc.ty[k] = g.y; return }
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
        val forage = if (s.human) ((mFruit.box(box.x0, box.y0, box.x1, box.y1) + mRoots.box(box.x0, box.y0, box.x1, box.y1) + mRodents.box(box.x0, box.y0, box.x1, box.y1)) / areaKm2).toFloat()
            else if (s.carnivore) mRodents.box(box.x0, box.y0, box.x1, box.y1).toFloat() / areaKm2
            else ((s.diet.grass * mGraze.box(box.x0, box.y0, box.x1, box.y1) * worth(s, foodQuality(0, box)) + s.diet.browse * mBrowse.box(box.x0, box.y0, box.x1, box.y1) +
                s.diet.roots * mRoots.box(box.x0, box.y0, box.x1, box.y1) + s.diet.fruit * mFruit.box(box.x0, box.y0, box.x1, box.y1)) / plantDiet(s)).toFloat() / areaKm2
        val boxKm2 = box.cells * COARSE_KM * COARSE_KM
        val fed = if (s.carnivore) forage else forage * tether(s, waterHere)
        // Enemies: their mass per km² over the range, against this group's own mass.
        val enemyKg = mDanger[s.index].box(box.x0, box.y0, box.x1, box.y1)
        val enemyDensity = (enemyKg / boxKm2).toFloat()
        // People camp where they can defend themselves, which takes the edge off a dangerous range.
        val danger = enemyDensity / (enemyDensity + DANGER_KG_KM2) * (if (s.human) 1f - 0.5f * terrain.defensibility[centre] else 1f) +
            (if (s.human) trespass(g, x, y) else 0f)
        val preyValue = if (s.carnivore) mPrey[s.index].box(box.x0, box.y0, box.x1, box.y1) else 0.0
        val preyDensity = (preyValue / boxKm2).toFloat()
        val prey = preyDensity / (preyDensity + PREY_NORM)
        // Others of its kind, not counting itself where it stands.
        val own = if (d == 0f) g.size.toFloat() else 0f
        val kinDensity = max(0f, mOcc[s.index].box(box.x0, box.y0, box.x1, box.y1).toFloat() - own) / boxKm2
        val kin = kinDensity / (kinDensity + s.groupSize / (3.14f * r * r))
        // Effort to shift the range there: distance times the ground's travel cost along the way.
        val effort = if (d == 0f) 0f else d * pathCost(g.x, g.y, x, y) / days
        return Option(centre, effort, fed, danger, prey, kin, if (waterCells > 0f) 0f else waterHere,
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
        groups.parallelStream().forEach { g ->
            if (!g.alive) return@forEach
            val box = CoarseMaps.Box()
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
            val box = boxes[h]
            box.set(maps, g.x, g.y, g.rangeKm)
            // Animals that must drink often spend the week walking to water, not grazing far from it.
            val waterKmHere = (mWaterKm.box(box.x0, box.y0, box.x1, box.y1) / max(1.0, mLand.box(box.x0, box.y0, box.x1, box.y1))).toFloat()
            want[h] = if (sim.weather.snowpack[c] > s.snowLimitMm) 0f else g.dailyNeedKg * days * appetite * (1f + fatten) * tether(s, waterKmHere)
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
        val fruitKg = FloatArray(herds.size)
        java.util.stream.IntStream.range(0, herds.size).parallel().forEach { h ->
            val g = herds[h]
            val box = boxes[h]
            var sum = 0f
            for (k in 0..3) {
                val wantK = split[h][k]; val total = offered[h][k]
                if (wantK <= 0f || total <= 0f) continue
                val cell = stocks[k].cell
                var kg = 0f
                for (y in box.y0 until box.y1) for (x in box.x0 until box.x1) {
                    val m = y * maps.w + x
                    kg += wantK * cell[m] * access(g.species, m) / total * share[k][m]
                }
                // What the food is worth to this animal: poor food cannot meet a small grazer's needs.
                sum += kg * worth(g.species, foodQuality(k, box))
                if (k == 3) fruitKg[h] = kg
            }
            got[h] = sum
        }
        // Fruit-eaters pass the seed wherever they roam.
        seedDrop.fill(0f)
        for ((h, g) in herds.withIndex()) {
            if (fruitKg[h] <= 0f) continue
            val box = boxes[h]
            val per = fruitKg[h] / box.cells
            for (y in box.y0 until box.y1) for (x in box.x0 until box.x1) seedDrop[y * maps.w + x] += per
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
                when (k) { 0 -> grazedNow[i] += e; 1 -> browsedNow[i] += e; 2 -> rootedNow[i] += e }
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
     * Share of a range's food that [s] can use when its water is [waterKm] away on average: grazers
     * that must drink every day or two graze out from water and back, and leave the far country to
     * animals that live on dew and the water in their food (gazelle, oryx, camels).
     */
    private fun tether(s: Species, waterKm: Float): Float {
        if (s.maxWaterKm.isFinite()) return 1f // already kept beside its water (hippos)
        val reachKm = s.dailyTravelKm * s.daysWithoutWater / 4f * (1f + 10f * s.waterFromFood * s.waterFromFood)
        val r = waterKm / reachKm
        return 1f / (1f + r * r)
    }

    /** Quality of food kind [k] (grass, browse, roots, fruit) over [box], 0..1. */
    private fun foodQuality(k: Int, box: CoarseMaps.Box): Float = when (k) {
        0 -> { val kg = mGraze.box(box.x0, box.y0, box.x1, box.y1); if (kg > 0.0) (mGrazeQ.box(box.x0, box.y0, box.x1, box.y1) / kg).toFloat() else 0.5f }
        1 -> BROWSE_QUALITY
        2 -> ROOTS_QUALITY
        else -> FRUIT_QUALITY
    }

    /**
     * What a kg of food of [quality] is worth to [s], 0..1: a small animal's fast metabolism needs
     * rich food (about 0.75 for a gazelle), a big gut makes do with poorer (about 0.45 for an aurochs).
     */
    private fun worth(s: Species, quality: Float): Float {
        val big = ((kotlin.math.log10(s.massKg) - 1.3f) / 1.7f).coerceIn(0f, 1f)
        val needs = 0.75f - 0.3f * big
        // Small animals with narrow muzzles pick the best bites (green leaf, buds, seed heads) out of
        // a sward a big grazer must take whole: what they eat is richer than the average around them.
        val picked = quality + SELECTIVITY * (1f - big) * (1f - quality)
        return min(1f, picked / needs)
    }

    /** Passes the week's grazing and browsing on to the plants, as a running mean per day. */
    private fun shapePlants() {
        val v = sim.vegetation
        java.util.stream.IntStream.range(0, n).parallel().forEach { i ->
            v.grazing[i] += 0.3f * (grazedNow[i] / days - v.grazing[i])
            v.browsing[i] += 0.3f * (browsedNow[i] / days - v.browsing[i])
            v.rooting[i] += 0.3f * (rootedNow[i] / days - v.rooting[i])
            // Seed dropped this week, per km², against what makes a good seed rain.
            val drop = seedDrop[maps.of(grid.x(i), grid.y(i))] / (COARSE_KM * COARSE_KM)
            v.animalSeed[i] += 0.05f * (min(1f, drop / SEED_RAIN_KG) - v.animalSeed[i])
            grazedNow[i] = 0f; browsedNow[i] = 0f; rootedNow[i] = 0f
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
        value * eat(c, straw, nourishmentKg / value, LifeForm.HERB, STRAW_REACH, grazedNow)

    /**
     * How much a species gets out of coarse forage (the Jarman-Bell principle): a big gut holds
     * food longer and digests fibre better, while a small animal's high metabolism needs rich food.
     * Aurochs and wild asses live through the dry season on straw that starves a gazelle.
     */
    private fun roughageValue(s: Species) = (ROUGHAGE_VALUE * s.fibreDigestion * (s.massKg / 100f).pow(0.25f)).coerceAtMost(0.75f)

    /** Eats up to [kg] from [stock] over the cells within [reach] of [c], taking it from plants of [form]; returns kg eaten. */
    private fun eat(c: Int, stock: FloatArray, kg: Float, form: LifeForm?, reach: Int, record: FloatArray? = null): Float {
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
            if (record != null) record[i] += e
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
        if (g.alive) free -= if (s.human) { meetBands(g, r); 0f } else meetRivals(g, r)
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
        if (s.human) {
            eaten += gather(g, need - min(eaten, need))
            larder(g, need, eaten).let { eaten += it }
            burnOff(g, r)
            explore(g, r)
            buildKite(g, r)
            collectAndMake(g)
            claim(g)
            // Bones, offal and spoiled food go on the camp's midden, for jackals, wolves and vultures.
            soil.carrion[c] += (REFUSE * eaten / grid.cellAreaM2).toFloat()
        }
        if (eaten < need) eaten += catchRodents(c, min(need - eaten, s.diet.smallPrey * need))
        if (eaten < need) eaten += scavenge(c, need - eaten)
        // Camp followers: around people the bold eat best and raise the most young, generation on generation.
        if (s.followsPeople && peopleNear(g)) g.bold += BOLD_DRIFT * (1f - g.bold)
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
     * People gather plant food, small game and fish over their range, up to [kg]: each as far as
     * the band's skill with it and the season allow. Returns the kg gathered. Gathering is practice,
     * and skills not used this week fade a little.
     */
    private fun gather(g: AnimalGroup, kg: Float, plantsOnly: Boolean = false): Float {
        val fc = g.forageCraft ?: return 0f
        val s = g.species
        val box = CoarseMaps.Box().also { it.set(maps, g.x, g.y, g.rangeKm) }
        var got = 0f
        // Fruit, nuts and seeds in season, and roots and bulbs dug the year round, from the fine cells of the range.
        val wantPlants = if (plantsOnly) kg else kg * (s.diet.fruit + s.diet.roots) / (s.diet.fruit + s.diet.roots + s.diet.smallPrey).coerceAtLeast(1e-3f)
        for ((stock, skill, share) in listOf(Triple(fruit, Forage.FRUIT, s.diet.fruit), Triple(roots, Forage.ROOTS, s.diet.roots))) {
            val want = wantPlants * share / (s.diet.fruit + s.diet.roots)
            if (want <= 0f) continue
            val k = fc[skill.ordinal]
            var have = 0.0
            for (y in box.y0 until box.y1) for (x in box.x0 until box.x1) have += (if (stock === fruit) mFruit else mRoots).cell[y * maps.w + x]
            // A skilled band finds and processes several times what a clumsy one does in a week.
            // Sickles and baskets bring in more; grinding turns nuts, acorns and seeds into food.
            val kit = g.tools?.let { 1f + SICKLE_GAIN * it[Tool.SICKLE.ordinal] + BASKET_GAIN * it[Tool.BASKET.ordinal] + GRINDER_GAIN * it[Tool.GRINDER.ordinal] } ?: 1f
            val take = min(want, (have * GATHER_REACH * kit * (CRAFT_FLOOR + (1f - CRAFT_FLOOR) * k)).toFloat())
            if (take > 0f) {
                takeFrom(stock, box, take / have.toFloat())
                fc[skill.ordinal] += PRACTICE * (1f - k)
            }
            got += take
        }
        if (plantsOnly) return got
        // Small game: hares, birds, tortoises, hyrax, by snare, net and throwing stick.
        val sg = fc[Forage.SMALL_GAME.ordinal]
        val c = cellOf(g)
        if (got < kg) {
            // Snare lines and nets set across the range and checked every few days: more cells, more catch.
            val snares = g.tools?.get(Tool.NET.ordinal) ?: 0f
            val lines = 1 + (SNARE_LINES * snares).toInt()
            var caught = 0f
            for (k in 0 until lines) {
                val cell = if (k == 0) c else {
                    val a = k * 2.399; val d = g.rangeKm / cellKm * kotlin.math.sqrt(k.toDouble() / lines)
                    grid.index((g.x + kotlin.math.cos(a) * d).toInt().coerceIn(0, grid.width - 1), (g.y + kotlin.math.sin(a) * d).toInt().coerceIn(0, grid.height - 1))
                }
                caught += game.catch(cell, (kg - got - caught) / lines / cellKm2 * (1f + snares) * (CRAFT_FLOOR + (1f - CRAFT_FLOOR) * sg)) * cellKm2
            }
            if (caught > 0f) fc[Forage.SMALL_GAME.ordinal] += PRACTICE * (1f - sg)
            got += caught
        }
        // Fish from the river or shore near camp.
        val fs = fc[Forage.FISH.ordinal]
        if (got < kg && waterKm[c] < 3f) {
            val nets = 1f + NET_GAIN * (g.tools?.get(Tool.NET.ordinal) ?: 0f)
            val caught = fishNear(c, (kg - got) * nets * (CRAFT_FLOOR + (1f - CRAFT_FLOOR) * fs))
            if (caught > 0f) fc[Forage.FISH.ordinal] += PRACTICE * (1f - fs)
            got += caught
        }
        val forget = if (g.holds(Tradition.ORAL_TRADITION)) FORGET * 0.4f else FORGET
        for (f in fc.indices) fc[f] *= 1f - forget
        g.craft?.let { for (q in it.indices) it[q] *= 1f - forget }
        return got
    }

    /**
     * In the dry season a band now and then burns off old grass in its range, where it stands
     * thickest: the green flush after the fire draws game, and the open ground is easier to hunt.
     */
    private fun burnOff(g: AnimalGroup, r: Random) {
        if (sim.weather.date.month !in 3..7 || r.nextFloat() >= BURN_WEEKLY) return
        val v = sim.vegetation
        var best = -1; var bestGrass = 0.3f
        repeat(6) {
            val a = r.nextDouble() * 2 * Math.PI; val d = r.nextDouble() * g.rangeKm / cellKm
            val x = (g.x + kotlin.math.cos(a) * d).toInt(); val y = (g.y + kotlin.math.sin(a) * d).toInt()
            if (!grid.inBounds(x, y)) return@repeat
            val i = grid.index(x, y)
            if (v.herb[i] > bestGrass) { bestGrass = v.herb[i]; best = i }
        }
        if (best >= 0) v.ignite(best)
    }

    /**
     * Two bands whose ranges overlap meet now and then. Young people marry out: a few adults move
     * each way, bringing their descent and their skills; the children of the next generation are
     * less inbred for it. Bands that never meet others pay in dead children and lost skills.
     */
    private fun meetBands(g: AnimalGroup, r: Random) {
        val (o, d) = nearest(g.species, g.x, g.y, g.rangeKm * 2f, except = g)
        if (o == null || o.adults < 2 || g.adults < 2) return
        if (r.nextFloat() >= MARRY_WEEKLY * overlap(d, g.rangeKm, o.rangeKm)) return
        if (fight(g, o, r)) return
        val understood = talk(g, o, r)
        give(g, o); give(o, g)
        if (r.nextFloat() < understood) { imitate(g, o, r); imitate(o, g, r) }
        // Bands that share food give to the hungrier in bad times.
        if (g.holds(Tradition.SHARING) && o.holds(Tradition.SHARING)) {
            val (rich, poor) = if (g.condition > o.condition) g to o else o to g
            if (poor.condition < 0.5f && rich.larder > 0f) { val gift = 0.3f * rich.larder; rich.larder -= gift; poor.larder += gift }
        }
        // Partners are exchanged only where both bands marry out.
        if (!g.holds(Tradition.EXOGAMY) || !o.holds(Tradition.EXOGAMY)) return
        val la = g.lineage ?: return; val lb = o.lineage ?: return
        // One young adult moves each way: their share of each band.
        val ma = 1f / g.adults; val mb = 1f / o.adults
        val kin = la.indices.sumOf { (la[it] * lb[it]).toDouble() }.toFloat()
        val inA = g.inbred; val inB = o.inbred
        g.inbred = (1f - ma) * inA + ma * OUTSIDER_KIN * kin
        o.inbred = (1f - mb) * inB + mb * OUTSIDER_KIN * kin
        val a0 = la.copyOf()
        for (k in la.indices) { la[k] += ma * (lb[k] - la[k]); lb[k] += mb * (a0[k] - lb[k]) }
        // Partners bring what they know how to do.
        fun learnFrom(to: FloatArray?, from: FloatArray?, m: Float) {
            if (to == null || from == null) return
            for (k in to.indices) if (from[k] > to[k]) to[k] += m * (from[k] - to[k])
        }
        // Exchange: what one band has plenty of and the other lacks changes hands (obsidian, flint, hides).
        val ga = g.goods; val gb = o.goods
        if (ga != null && gb != null) for (m in ga.indices) {
            val diff = (ga[m] - gb[m]) * TRADE_SHARE
            ga[m] -= diff; gb[m] += diff
        }
        learnFrom(g.making, o.making, ma); learnFrom(o.making, g.making?.copyOf(), mb)
        // Know-how passes better between bands that understand each other (partners learn the language
        // in time, but the first years are halting).
        val tongue = 0.5f + 0.5f * understood
        val ca = g.craft?.copyOf(); val fa = g.forageCraft?.copyOf()
        learnFrom(g.craft, o.craft, ma * tongue); learnFrom(g.forageCraft, o.forageCraft, ma * tongue)
        learnFrom(o.craft, ca, mb * tongue); learnFrom(o.forageCraft, fa, mb * tongue)
    }

    /** Coarse cells holding good knapping stone (flint, chert, obsidian), counted once. */
    private val mStone by lazy {
        maps.layer().also { l ->
            for (i in 0 until n) if (world.geology.minerals[i] and (inflation.world.Mineral.FLINT or inflation.world.Mineral.OBSIDIAN) != 0) l.cell[maps.of(grid.x(i), grid.y(i))] += 1f
            l.build()
        }
    }

    /**
     * A band's week of craft: it collects materials from its range (stone where outcrops are, wood
     * and resin where trees grow, fibre from reeds, bark and grasses), and makes and mends its tools
     * from them, each as far as materials and skill allow; tools wear with use. Making is practice.
     */
    private fun collectAndMake(g: AnimalGroup) {
        val gd = g.goods ?: return; val kit = g.tools ?: return; val mk = g.making ?: return
        val box = CoarseMaps.Box().also { it.set(maps, g.x, g.y, g.rangeKm) }
        val land = max(1.0, mLand.box(box.x0, box.y0, box.x1, box.y1))
        val woody = mBrowse.box(box.x0, box.y0, box.x1, box.y1) / land
        val herby = mGraze.box(box.x0, box.y0, box.x1, box.y1) / land
        val people = g.adults.toFloat()
        // What a few hands bring in a week, kg per adult, if the range has it.
        g.places?.firstOrNull { it.kind == PlaceKind.ORNAMENT && dist(it.x - g.x, it.y - g.y) * cellKm < STONE_TRIP_KM }?.let { gd[Material.ORNAMENT.ordinal] += 0.005f * people }
        if (mStone.box(box.x0, box.y0, box.x1, box.y1) > 0.0) gd[Material.STONE.ordinal] += 1f * people
        // Otherwise a party fetches stone from a known outcrop within a few days' walk.
        else g.places?.firstOrNull { it.kind == PlaceKind.STONE && dist(it.x - g.x, it.y - g.y) * cellKm < STONE_TRIP_KM }?.let { gd[Material.STONE.ordinal] += 0.3f * people }
        gd[Material.WOOD.ordinal] += (2f * people * min(1.0, woody / 50.0)).toFloat()
        gd[Material.RESIN.ordinal] += (0.05f * people * min(1.0, woody / 50.0)).toFloat()
        gd[Material.FIBRE.ordinal] += (1f * people * min(1.0, (woody + herby) / 50.0)).toFloat()
        val years = days / Date.DAYS_PER_YEAR.toFloat()
        for (tl in Tool.entries) {
            val k = tl.ordinal
            kit[k] = max(0f, kit[k] - tl.wearPerYear * years)
            if (kit[k] >= 1f) continue
            // Make as much of the shortfall this week as the scarcest material and the skill allow.
            val skill = mk[tl.skill.ordinal]
            var can = min(1f - kit[k], MAKE_WEEKLY * (CRAFT_FLOOR + (1f - CRAFT_FLOOR) * skill))
            for ((m, perPerson) in tl.recipe) can = min(can, gd[m.ordinal] / max(1e-3f, perPerson * people))
            if (can <= 0f) continue
            for ((m, perPerson) in tl.recipe) gd[m.ordinal] -= can * perPerson * people
            kit[k] += can
            mk[tl.skill.ordinal] += PRACTICE * (1f - skill)
        }
        // A band carries only so much: the rest is left at old camps.
        for (m in gd.indices) gd[m] = min(gd[m], GOODS_PER_PERSON * people)
        for (m in mk.indices) mk[m] *= 1f - if (g.holds(Tradition.ORAL_TRADITION)) FORGET * 0.4f else FORGET
    }

    /**
     * Two bands talk. They align a few words (the naming game: the larger band's word tends to win),
     * and pass on news of good places by month, as far as they understand each other; hearsay is
     * trusted a little less than one's own memory. Returns how well they understood each other.
     */
    private fun talk(g: AnimalGroup, o: AnimalGroup, r: Random): Float {
        val wa = g.words ?: return 0f; val wb = o.words ?: return 0f
        val understood = Language.understanding(wa, wb)
        repeat(WORDS_PER_MEETING) {
            val k = r.nextInt(wa.size)
            if (wa[k] != wb[k]) { if (r.nextFloat() < g.size.toFloat() / (g.size + o.size)) wb[k] = wa[k] else wa[k] = wb[k] }
        }
        // Place names travel with the language: each side learns places it did not know, as far as it understands.
        val pa = g.places; val pb = o.places
        if (pa != null && pb != null) {
            for ((hearer, teller) in listOf(pa to pb, pb to pa)) for (p in teller) {
                if (r.nextFloat() >= understood * 0.3f) continue
                if (hearer.none { it.kind == p.kind && dist((it.x - p.x).toDouble(), (it.y - p.y).toDouble()) * cellKm < 5.0 }) addPlace(hearer, Place(p.x, p.y, p.kind, p.quality * HEARSAY, p.seen))
            }
        }
        for (m in 0 until 12) {
            if (r.nextFloat() >= understood) continue
            for ((hearer, teller) in listOf(g to o, o to g)) {
                if (teller.seasonX[m].isNaN()) continue
                if (hearer.seasonX[m].isNaN() || teller.seasonQ[m] * HEARSAY > hearer.seasonQ[m] + 0.1f) {
                    hearer.seasonX[m] = teller.seasonX[m]; hearer.seasonY[m] = teller.seasonY[m]; hearer.seasonQ[m] = teller.seasonQ[m] * HEARSAY
                }
            }
        }
        return understood
    }

    /**
     * Whether a meeting turns violent, and its toll if it does. Strangers (unrelated, with different
     * ways) fight more than kin; hunger, stores worth taking and a settled band's fixed ground raise
     * the stakes; old grievances call for revenge; bands bound by marriage or food-sharing seldom fight.
     * The stronger or hungrier side raids: both lose people, the raided more unless their camp is
     * defensible, and the raiders carry off stores. Each side then holds a grudge against the other's
     * descent. Returns whether there was a fight.
     */
    private fun fight(g: AnimalGroup, o: AnimalGroup, r: Random): Boolean {
        val la = g.lineage ?: return false; val lb = o.lineage ?: return false
        val kin = la.indices.sumOf { (la[it] * lb[it]).toDouble() }.toFloat()
        val ta = g.traditions; val tb = o.traditions
        val sameWays = if (ta != null && tb != null) ta.indices.count { ta[it] == tb[it] }.toFloat() / ta.size else 0.5f
        val sameTongue = g.words?.let { a -> o.words?.let { b -> Language.understanding(a, b) } } ?: 0.5f
        val alike = 0.5f * (sameWays + sameTongue)
        val hunger = 1f - min(g.condition, o.condition)
        val stakes = min(1f, (g.larder + o.larder) / max(1f, 60f * (g.dailyNeedKg + o.dailyNeedKg))) + if (g.campWeeks > 26 || o.campWeeks > 26) 0.5f else 0f
        val grievance = la.indices.sumOf { ((g.grudge?.get(it) ?: 0f) * lb[it] + (o.grudge?.get(it) ?: 0f) * la[it]).toDouble() }.toFloat()
        val allied = (if (g.holds(Tradition.EXOGAMY) && o.holds(Tradition.EXOGAMY)) 0.4f else 1f) * (if (g.holds(Tradition.SHARING) && o.holds(Tradition.SHARING)) 0.5f else 1f)
        val onLand = max(trespass(g, g.x, g.y), trespass(o, o.x, o.y))
        val p = HOSTILITY * (1f + 2f * onLand) * (1f - 0.8f * kin) * (1.5f - alike) * (1f + 2f * hunger) * (1f + stakes) * (1f + 1.5f * min(1f, grievance)) * allied
        if (r.nextFloat() >= p) return false
        // The stronger, or the hungrier when close, raids.
        val (raider, raided) = if (strength(g) * (1.5f - g.condition) >= strength(o) * (1.5f - o.condition)) g to o else o to g
        val ratio = strength(raider) / max(1f, strength(raided))
        val shelter = terrain.defensibility[cellOf(raided)]
        val lostRaided = binomial(raided.adults, (RAID_DEATHS * min(2f, ratio) * (1f - 0.6f * shelter)).coerceIn(0f, 0.5f), r)
        val lostRaider = binomial(raider.adults, (RAID_DEATHS * 0.5f / min(2f, ratio) * (1f + shelter)).coerceIn(0f, 0.5f), r)
        for ((band, lost) in listOf(raided to lostRaided, raider to lostRaider)) {
            if (lost <= 0) continue
            band.adults -= lost; band.elders = min(band.elders, band.adults)
            deathCount.addAndGet(lost); deathsBy.merge("people:killed by people", lost, Int::plus)
        }
        val loot = 0.5f * raided.larder; raided.larder -= loot; raider.larder += loot
        // Grievances against the other side's descent, the raided holding the deeper one.
        fun aggrieve(band: AnimalGroup, against: FloatArray, weight: Float) {
            val gr = band.grudge ?: FloatArray(LINEAGES).also { band.grudge = it }
            for (k in gr.indices) gr[k] += weight * against[k]
        }
        aggrieve(raided, raider.lineage!!, 1f); aggrieve(raider, raided.lineage!!, 0.3f)
        shove(raided, raider)
        return true
    }

    /**
     * Gifts: a band with ornaments to spare gives some to the other. Giving wins renown, and the
     * receivers let go of some of their grievance against the givers' people.
     */
    private fun give(giver: AnimalGroup, taker: AnimalGroup) {
        val gd = giver.goods ?: return; val td = taker.goods ?: return
        val spare = gd[Material.ORNAMENT.ordinal] - ORNAMENTS_KEPT * giver.adults
        if (spare <= 0f) return
        val gift = 0.5f * spare
        gd[Material.ORNAMENT.ordinal] -= gift; td[Material.ORNAMENT.ordinal] += gift
        giver.renown = min(1f, giver.renown + 0.02f * gift / (ORNAMENTS_KEPT * max(1, giver.adults)))
        val gl = giver.lineage ?: return
        taker.grudge?.let { gr -> for (k in gr.indices) gr[k] *= 1f - min(1f, gift / max(1, taker.adults)) * gl[k] }
    }

    /**
     * Cultural evolution at a meeting: [g] copies, now and then, a tradition from [o] if [o] has been
     * doing better, and drops one [o] lacks; and every band occasionally tries something new.
     */
    private fun imitate(g: AnimalGroup, o: AnimalGroup, r: Random) {
        val mine = g.traditions ?: return; val theirs = o.traditions ?: return
        // People copy the successful and the renowned.
        val better = o.prosper - g.prosper + RENOWN_WEIGHT * (o.renown - g.renown)
        if (better > 0f) {
            val k = r.nextInt(mine.size)
            if (mine[k] != theirs[k] && r.nextFloat() < IMITATE * min(1f, better / 0.02f)) mine[k] = theirs[k]
        }
        if (r.nextFloat() < INNOVATE) { val k = r.nextInt(mine.size); mine[k] = !mine[k] }
    }

    /** Words change by themselves, slowly: a band cut off from others drifts into its own dialect. */
    private fun driftWords(g: AnimalGroup, w: IntArray) {
        val r = groupRandom(g, 7)
        if (r.nextFloat() < WORD_DRIFT * w.size) w[r.nextInt(w.size)] = r.nextInt(Language.FORMS)
    }

    /**
     * A band learns its land beyond the camp: hunting parties and travellers come back with the
     * springs, rivers, stone outcrops and defensible sites within a few days' walk, which it names
     * and remembers. Old places it has not seen are forgotten, slower where elders and stories keep them.
     */
    private fun explore(g: AnimalGroup, r: Random) {
        val ps = g.places ?: return
        val reach = TASK_REACH * g.rangeKm * 2f / cellKm
        repeat(EXPLORE_TRIES) {
            val a = r.nextDouble() * 2 * Math.PI; val d = (0.3 + 0.7 * r.nextDouble()) * reach
            val x = (g.x + kotlin.math.cos(a) * d).toInt(); val y = (g.y + kotlin.math.sin(a) * d).toInt()
            if (!grid.inBounds(x, y)) return@repeat
            val i = grid.index(x, y)
            val kind = when {
                spring[i] || oasis[i] || (world.water.discharge[i] >= 1f && !world.isSea[i]) -> PlaceKind.WATER
                world.geology.minerals[i] and inflation.world.Mineral.OCHRE != 0 || (!world.isSea[i] && waterKm[i] < 1f && nearSea(i)) -> PlaceKind.ORNAMENT
                world.geology.minerals[i] and (inflation.world.Mineral.FLINT or inflation.world.Mineral.OBSIDIAN) != 0 -> PlaceKind.STONE
                gatheringSite(i) -> PlaceKind.GATHERING
                terrain.defensibility[i] > 0.6f -> PlaceKind.CAMP
                else -> return@repeat
            }
            val near = ps.firstOrNull { it.kind == kind && dist(it.x - x.toDouble(), it.y - y.toDouble()) * cellKm < 5.0 }
            if (near != null) near.seen = stepCount else addPlace(ps, Place(x.toFloat(), y.toFloat(), kind, 1f, stepCount))
        }
        // Forgetting: places not seen for decades fade, unless elders or stories carry them.
        val keepYears = PLACE_MEMORY_YEARS * (if (g.elders > 0) 1.5f else 1f) * (if (g.holds(Tradition.ORAL_TRADITION)) 2f else 1f)
        val stepsPerYear = Date.DAYS_PER_YEAR / days
        ps.removeAll { stepCount - it.seen > keepYears * stepsPerYear }
    }

    /**
     * Desert kites: a large band, or one that holds feasts and can call on its neighbours, builds
     * long converging walls across open, dry ground where herds pass, over a season of work. Once
     * built a kite stands for generations and can be used by whoever knows it.
     */
    private fun buildKite(g: AnimalGroup, r: Random) {
        val ps = g.places ?: return
        if (g.adults < KITE_HANDS && !g.holds(Tradition.FEASTS)) return
        if (ps.any { it.kind == PlaceKind.KITE && dist(it.x - g.x, it.y - g.y) * cellKm < 30.0 }) return
        val c = cellOf(g)
        if (cover(c) > 0.3f || r.nextFloat() >= KITE_WEEKLY) return
        // Where herds of the open plains pass in numbers.
        val box = CoarseMaps.Box().also { it.set(maps, g.x, g.y, g.rangeKm) }
        val herds = KITE_QUARRY.sumOf { mOcc[it.index].box(box.x0, box.y0, box.x1, box.y1) }
        if (herds < KITE_HERDS) return
        addPlace(ps, Place(g.x.toFloat(), g.y.toFloat(), PlaceKind.KITE, 1f, stepCount))
    }

    /**
     * A good place to gather: where rivers meet (and with them the routes along their valleys), or a
     * landmark that stands out over the country around, with water at hand.
     */
    private fun gatheringSite(i: Int): Boolean {
        if (world.isSea[i] || waterKm[i] > 2f) return false
        val drainage = world.water.drainage
        var feeders = 0
        grid.forNeighbours(i) { nb, _ -> if (drainage.receiver[nb] == i && world.water.discharge[nb] >= 0.5f) feeders++ }
        return feeders >= 2 || terrain.prominence[i] > 150f
    }

    private fun nearSea(i: Int): Boolean {
        val x = grid.x(i); val y = grid.y(i)
        for (dy in -1..1) for (dx in -1..1) if (grid.inBounds(x + dx, y + dy) && world.isSea[grid.index(x + dx, y + dy)]) return true
        return false
    }

    private fun addPlace(ps: ArrayList<Place>, p: Place) {
        if (ps.size >= MAX_PLACES) ps.removeAt(ps.indices.minByOrNull { ps[it].seen } ?: 0)
        ps += p
    }

    /** Land claims on a coarse grid: which band uses each block most, and how firmly. */
    private val claimKm = 10f
    private val cw = (grid.width * cellKm / claimKm).toInt() + 1
    private val ch = (grid.height * cellKm / claimKm).toInt() + 1
    private val claimOwner = IntArray(cw * ch) { -1 }
    private val claimStrength = FloatArray(cw * ch)
    private fun claimCell(x: Double, y: Double) = (y * cellKm / claimKm).toInt().coerceIn(0, ch - 1) * cw + (x * cellKm / claimKm).toInt().coerceIn(0, cw - 1)
    private val bandsById = HashMap<Int, AnimalGroup>()

    /** A band's use of its ground builds its claim there; long stays and graves build it faster. Claims fade unused. */
    private fun claim(g: AnimalGroup) {
        val c = claimCell(g.x, g.y)
        val add = 1f * (if (g.campWeeks > 26) 2f else 1f) * (if (g.holds(Tradition.BURIAL)) 1.5f else 1f)
        synchronized(claimStrength) {
            if (claimOwner[c] == g.id || claimOwner[c] < 0) { claimOwner[c] = g.id; claimStrength[c] = min(CLAIM_MAX, claimStrength[c] + add) }
            else { claimStrength[c] -= add; if (claimStrength[c] <= 0f) { claimOwner[c] = g.id; claimStrength[c] = add } }
        }
    }

    /**
     * How unwelcome [g] would be in a range centred on (x, y), 0..1: none on its own land or that of
     * kin, allies or bands of its own tongue, or when hunger drives it to bands that share; otherwise
     * as firm as the owner's claim.
     */
    private fun trespass(g: AnimalGroup, x: Double, y: Double): Float {
        val c = claimCell(x, y)
        val owner = claimOwner[c]
        if (owner < 0 || owner == g.id) return 0f
        val o = bandsById[owner] ?: return 0f
        val kin = g.lineage?.let { a -> o.lineage?.let { b -> a.indices.sumOf { (a[it] * b[it]).toDouble() }.toFloat() } } ?: 0f
        val tongue = g.words?.let { a -> o.words?.let { b -> Language.understanding(a, b) } } ?: 0f
        if (kin > 0.5f || tongue > 0.7f || (g.holds(Tradition.EXOGAMY) && o.holds(Tradition.EXOGAMY))) return 0f
        if (g.condition < 0.5f && o.holds(Tradition.SHARING)) return 0f
        return TRESPASS * claimStrength[c] / CLAIM_MAX
    }

    /** Whether any band's range overlaps [g]'s. */
    private fun peopleNear(g: AnimalGroup): Boolean {
        val box = CoarseMaps.Box().also { it.set(maps, g.x, g.y, g.rangeKm) }
        return mOcc[Catalogue.HUMAN.index].box(box.x0, box.y0, box.x1, box.y1) > 0.0
    }

    /**
     * A band's stores: in lean weeks it eats from them; in good weeks it gathers beyond the day's
     * need and lays the rest by, as far as its storing skill (drying, pits, baskets) allows. Stores
     * spoil slowly. Returns the kg eaten from store.
     */
    private fun larder(g: AnimalGroup, need: Float, eaten: Float): Float {
        val fc = g.forageCraft ?: return 0f
        var fromStore = 0f
        if (eaten < need && g.larder > 0f) {
            fromStore = min(g.larder, need - eaten)
            g.larder -= fromStore
        }
        val k = fc[Forage.STORE.ordinal]
        val cap = LARDER_DAYS * g.dailyNeedKg * k * (1f + BASKET_GAIN * (g.tools?.get(Tool.BASKET.ordinal) ?: 0f))
        if (g.larder < cap) {
            val extra = gather(g, min(cap - g.larder, need), plantsOnly = true)
            if (extra > 0f) { g.larder += extra; fc[Forage.STORE.ordinal] += PRACTICE * (1f - k) }
        }
        g.larder *= 1f - SPOIL
        return fromStore
    }

    /** Takes share [f] of [stock] (kg/km² per fine cell) from every fine cell under [box]. */
    private fun takeFrom(stock: FloatArray, box: CoarseMaps.Box, f: Float) {
        if (f <= 0f) return
        val keep = 1f - min(1f, f)
        val r = maps.ratio
        for (cy in box.y0 until box.y1) for (cx in box.x0 until box.x1)
            for (fy in cy * r until min(grid.height, (cy + 1) * r)) for (fx in cx * r until min(grid.width, (cx + 1) * r))
                stock[grid.index(fx, fy)] *= keep
    }

    /** Fish caught within a few km of [c], up to [kg]; returns kg. */
    private fun fishNear(c: Int, kg: Float): Float {
        var got = 0f
        val cx = grid.x(c); val cy = grid.y(c)
        for (dy in -2..2) for (dx in -2..2) {
            if (got >= kg || !grid.inBounds(cx + dx, cy + dy)) continue
            val i = grid.index(cx + dx, cy + dy)
            val have = fish.fish[i] * cellKm2
            val take = min(kg - got, 0.2f * have)
            if (take > 0f) { fish.fish[i] -= take / cellKm2; got += take }
        }
        return got
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
        for (dy in -r..r) for (dx in -r..r) if (grid.inBounds(cx + dx, cy + dy)) {
            val i = grid.index(cx + dx, cy + dy)
            // Hares, hyrax, game birds and tortoises for half, rodents and lizards for the rest.
            val fromGame = game.catch(i, 0.5f * per / cellKm2) * cellKm2
            got += fromGame + small.catchRodents(i, (per - fromGame) / cellKm2) * cellKm2
        }
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
        // People send hunting parties out for days, well beyond the camp's daily round.
        val reachKm = if (s.human) TASK_REACH * g.rangeKm else g.rangeKm
        for (q in Catalogue.herbivores) collect(q, g.x, g.y, reachKm + rangeKm(q), near)
        if (near.isEmpty()) return
        val contact = FloatArray(near.size) { overlap(kmBetween(g, near[it]), reachKm, near[it].rangeKm) }
        val chances = min(MAX_CHASES * free / days, CHASES_PER_CONTACT * free * contact.sum()).toInt()
        // What the party brings to the fight: weight, times its weapons (teeth and claws, or spears and bows).
        val packKg = g.adults * s.massKg * armed(g)
        val home = habitatMatch(s, cover(c), terrain.ruggedness[c], wetness[c])
        // Each herd's chances, worked out once for the week: for an adult and for a young one.
        val pAdult = FloatArray(near.size); val pYoung = FloatArray(near.size); val risk = FloatArray(near.size); val fear = FloatArray(near.size)
        for ((k, o) in near.withIndex()) {
            if (contact[k] <= 0f) continue
            val at = cellOf(o)
            pAdult[k] = catchChance(g, o, at, false, packKg, home)
            pYoung[k] = if (o.young > 0) catchChance(g, o, at, true, packKg, home) else 0f
            // Big, armed prey fight back: horns and tusks kill hunters. Worth it only for much meat.
            risk[k] = fightBack(o.species, packKg) * (2f - home) * (1f + youngShare(o)) * fitness(o)
            // People weigh what a beast could do to them, not what it is likely to: a hunter lost is a
            // family without meat for years. Experience with the quarry calms them a little.
            g.craft?.let { fear[k] = risk[k] * (1f - 0.4f * it[o.species.index]) }
            // But they kill from a distance with spears and arrows, and a band that knows the quarry
            // knows when to stand off: a cornered aurochs rarely reaches them.
            g.craft?.let { risk[k] *= RANGED_RISK * (1f - 0.7f * it[o.species.index]) }
        }
        // Each herd in contact is tried at most once a week (after a chase it moves off), so the
        // herds are ranked once by what they promise: chance times meat, for its young or an adult,
        // whichever is better. A hungry hunter takes on prey it would leave alone when fed.
        // People are far more careful than other hunters, and opportunistic: they go for the young,
        // the weak and the stragglers, and leave big dangerous beasts alone unless hunger drives them.
        val caution = (if (s.human) PEOPLE_CAUTION else 5f) * g.condition
        val value = FloatArray(near.size); val takeYoung = BooleanArray(near.size)
        for ((k, o) in near.withIndex()) {
            if (contact[k] <= 0f) continue
            val ps = o.species
            val adult = pAdult[k] * max(0f, 1f - caution * (if (s.human) fear[k] else risk[k])) * min(ps.massKg, need)
            val young = if (o.young > 0) pYoung[k] * min(0.3f * ps.massKg, need) * min(1f, 2f * o.young / (o.size + o.young)) else 0f
            // A band that spares mothers and young takes only adults, and fewer of those in the birth season.
            val spare = g.holds(Tradition.TABOO_YOUNG)
            takeYoung[k] = !spare && young > adult
            value[k] = contact[k] * if (spare) adult * (if (o.young > 0) 0.5f else 1f) else max(adult, young)
        }
        // A drive: open-country herds near a kite the band knows are funnelled in, a large share at once.
        g.places?.filter { it.kind == PlaceKind.KITE }?.let { kites ->
            if (kites.isEmpty()) return@let
            for (o in near) {
                if (!o.alive || o.species !in KITE_QUARRY || o.alert > 0) continue
                if (kites.none { dist(it.x - o.x, it.y - o.y) * cellKm < KITE_KM }) continue
                val taken = binomial(o.adults, KITE_TAKE, r) ; val takenY = binomial(o.young, KITE_TAKE, r)
                if (taken + takenY == 0) continue
                o.adults -= taken; o.young -= takenY; o.alert = ALERT_WEEKS
                o.wary += WARY_GAIN * (1f - o.wary)
                val meat = o.species.massKg * (taken + 0.3f * takenY)
                // What cannot be eaten fresh is dried for the stores; the rest goes to the scavengers.
                val fresh = min(meat, wantMeat - g.stored).coerceAtLeast(0f)
                g.stored += fresh
                val dried = min(meat - fresh, 0.5f * (meat - fresh)); g.larder += dried
                soil.carrion[c] += ((meat - fresh - dried) / grid.cellAreaM2).toFloat()
                killCount.addAndGet(taken + takenY); killsBy.merge(o.species, taken + takenY, Int::plus); killsByPredator.merge(s, taken + takenY, Int::plus)
                kiteCount.addAndGet(taken + takenY)
                peopleKills.merge(o.species, taken + takenY, Int::plus)
                break
            }
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
            g.craft?.let {
                it[prey.species.index] += PRACTICE * (1f - it[prey.species.index])
                prey.wary += WARY_GAIN * (1f - prey.wary)
            }
            if (r.nextFloat() >= bestP) { shove(prey, g); continue }
            // Brought to bay, a big animal may take one of its killers with it.
            if (bestRisk > 0f && r.nextFloat() < bestRisk) {
                g.adults--; deathCount.incrementAndGet()
                deathsBy.merge("${s.name}:fought back", 1, Int::plus)
                soil.carrion[c] += (s.massKg / grid.cellAreaM2).toFloat()
            }
            val meat = if (bestYoung) { prey.young--; 0.3f * prey.species.massKg } else { prey.adults--; prey.species.massKg }
            g.goods?.let { gd ->
                val p = prey.species.products; val f = if (bestYoung) 0.3f else 1f
                gd[Material.HIDE.ordinal] += f * p.hide; gd[Material.BONE.ordinal] += f * (p.bone + p.hornOrAntler); gd[Material.SINEW.ordinal] += f * p.sinew
            }
            // The bold come closest and fall first.
            if (s.human) prey.bold *= max(0f, 1f - BOLD_TOLL / max(1, prey.size))
            g.stored += meat - steal(g, meat, r)
            killCount.incrementAndGet()
            killsBy.merge(prey.species, 1, Int::plus)
            killsByPredator.merge(s, 1, Int::plus)
            if (s.human) peopleKills.merge(prey.species, 1, Int::plus)
            shove(prey, g)
        }
    }

    /** Chance that one chase by [g] after herd [o] (cell [at]) succeeds. */
    private fun catchChance(g: AnimalGroup, o: AnimalGroup, at: Int, young: Boolean, packKg: Float, home: Float): Float {
        val s = g.species; val ps = o.species
        val cov = cover(at)
        // Surprise: cover lets a stalker close in unseen; a watchful herd is seldom surprised.
        // Stalkers hunt by night: dark weeks around the new moon hide them, bright ones give them away.
        val dark = 1f + MOON_DARK * s.stalking * (1f - 2f * moonlight)
        // Fear of people: inborn, less the bold few, plus what this herd has learned from being hunted.
        val fear = if (s.human) min(1f, ps.fearOfPeople * (1f - o.bold) + o.wary) else 0f
        val shy = if (s.human) 1f + WARY_EFFECT * (BASE_FEAR - fear) else 1f
        val surprise = (SURPRISE_BASE + s.stalking * cov) * dark * shy * (1f - WATCH_EFFECT * o.posture)
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
        // People hunt by learned skill: a band that knows a quarry's ways gets close and kills clean.
        val craft = g.craft?.let { CRAFT_FLOOR + (1f - CRAFT_FLOOR) * it[ps.index] } ?: 1f
        // Spears are the baseline; bows add reach against fast, shy quarry, in practised hands.
        val weapons = g.tools?.let { k ->
            WEAPONLESS + (1f - WEAPONLESS) * k[Tool.SPEAR.ordinal] + BOW_GAIN * k[Tool.BOW.ordinal] * min(1f, ps.speedKmh / 70f) * (g.making?.get(Making.WOODWORK.ordinal) ?: 0.5f)
        } ?: 1f
        return (min(1f, g.adults / 2f) * eyes * size * base * chase * skill * craft * weapons * refuge * (1f + surprise) * (1f - stand.coerceIn(0f, 0.9f))).coerceIn(0f, 1f)
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
    /**
     * Chance a cornered [prey] kills one of the hunters: its weapons, and its weight against the
     * whole party bringing it down (a pride smothers an aurochs together; a pair of wolves faces it alone).
     */
    private fun fightBack(prey: Species, packKg: Float) = FIGHT_BACK * prey.defence * min(2f, prey.massKg / max(1f, packKg) * 2f)

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
        // People drive big cats off a kill only by day, in numbers, and not without risk: they seldom try.
        if (thief.species.human && g.species.dangerToHumans >= DANGEROUS_TO_PEOPLE && r.nextFloat() >= PEOPLE_TAKE_KILLS) return 0f
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
            // People's camps: a fire through the night, and a site with a cliff at the back and a view out.
            val camp = if (g.species.human) 1f - CAMPFIRE - (1f - CAMPFIRE) * terrain.defensibility[c] else 1f
            if (g.adults > 0 && r.nextFloat() < INTRAGUILD_KILL * camp * (1.5f - home) * (1f - 0.5f * g.posture)) {
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
        val full = when {
            s.human -> PEOPLE_FULL_KM2 // foragers' camps move before waste and fevers build up
            s.startKm2PerAnimal > 0f -> CROWD_PREDATOR / s.startKm2PerAnimal
            else -> Allometry.herbivoreDensityPerKm2(s.massKg) * CROWD_HERBIVORE
        }
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
            val before = g.size
            g.elders = min(g.elders, g.adults) // losses elsewhere (hunting accidents, attacks) fall on any adult
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
                    // Grandmothers feed the weaned children, so mothers can wean sooner and bear again.
                    val mothers = g.adults - g.elders
                    // Settled mothers carry infants less and wean them on gruel from the stores: births come closer.
                    val settled = if (s.human) 1f + SETTLED_FERTILITY * min(1f, g.campWeeks / 26f) * min(1f, g.larder / max(1e-3f, 30f * g.dailyNeedKg)) else 1f
                    val help = settled * if (s.human) 1f + GRANDMOTHERS * min(1f, g.elders * ELDER_MOTHERS / max(1, mothers).toFloat()) else 1f
                    binomial(mothers, 0.5f * s.youngPerFemale * help / s.littersPerYear * fit / stepsInSeason, r)
                }
                g.young += born; birthCount.addAndGet(born)
            }
            // Growing up, and for people growing old: adults become elders after some decades.
            val matured = binomial(g.young, years / s.yearsToMaturity, r)
            g.young -= matured; g.adults += matured
            if (s.human) {
                g.elders += binomial(g.adults - g.elders, years / ADULT_YEARS, r)
                // Inbreeding builds up in a band that marries within itself.
                g.inbred += (1f - g.inbred) * years / (GENERATION_YEARS * 2f * max(1, g.adults))
            }
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
            // People: grandmothers keep children fed and watched; inbred children die more often.
            val careOfElders = if (s.human) 1f - ELDER_CARE * min(1f, g.elders * ELDER_MOTHERS / max(1, g.adults - g.elders).toFloat()) else 1f
            val inbreeding = if (s.human) INBREEDING_DEATHS * g.inbred * years else 0f
            // A camp kept for months fouls: waste, worms and fevers, children first.
            val fouled = if (s.human) CAMP_FOULING * years * min(1f, max(0, g.campWeeks - 8) / 52f) * (if (g.holds(Tradition.HYGIENE)) 0.4f else 1f) else 0f
            if (g.holds(Tradition.MEDICINE)) pAdult *= MEDICINE_SAVES
            val pYoung = min(1f, pAdult + s.youngMortality * years * careOfElders + inbreeding + fouled + 0.1f * hunger + eagle + THERMAL_DEATHS * years * stress)
            // Elders die faster than adults in their prime.
            val deadE = if (s.human) binomial(g.elders, min(1f, pAdult + ELDER_DEATHS * years), r) else 0
            g.elders -= deadE; g.adults -= deadE
            val deadA = binomial(g.adults - g.elders, pAdult, r) + deadE
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
            g.adults -= deadA - deadE
            val orphaned = if (g.adults == 0) g.young - deadY else 0
            if (orphaned > 0) deathsBy.merge("${s.name}:orphaned", orphaned, Int::plus)
            g.young -= deadY + orphaned
            val dead = deadA + deadY + orphaned
            if (s.human) g.prosper += 0.05f * ((g.size - before).toFloat() / max(1, before) / years - g.prosper)
            deathCount.addAndGet(dead)
            if (dead > 0) carrionKg[gi] = s.massKg * (deadA + 0.5f * (deadY + orphaned))
            // Splitting: a group too big for its food divides.
            if (g.size > s.maxGroupSize * (if (g.holds(Tradition.FEASTS)) 1.4f else 1f)) {
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
            child.craft = g.craft?.copyOf(); child.forageCraft = g.forageCraft?.copyOf()
            child.wary = g.wary
            // The stores are shared out by heads, and the elders go with both halves.
            val share = child.size.toFloat() / (child.size + g.size).coerceAtLeast(1)
            child.larder = g.larder * share; g.larder -= child.larder
            child.elders = min(child.adults, (g.elders * share).toInt()); g.elders = min(g.adults, g.elders - child.elders)
            child.lineage = g.lineage?.copyOf(); child.inbred = g.inbred
            child.campWeeks = 0
            child.making = g.making?.copyOf()
            child.traditions = g.traditions?.copyOf(); child.prosper = g.prosper; child.grudge = g.grudge?.copyOf()
            child.words = g.words?.copyOf()
            child.places = g.places?.let { ps -> ArrayList(ps.map { Place(it.x, it.y, it.kind, it.quality, it.seen) }) }
            child.tools = g.tools?.copyOf()?.also { k -> for (tl in Tool.entries) if (tl.heavy) k[tl.ordinal] = 0f }
            child.goods = g.goods?.let { gd -> FloatArray(gd.size) { gd[it] * share }.also { for (m in gd.indices) gd[m] -= it[m] } }
            // A new group carries its parents' temper, give or take: the variation selection works on.
            child.bold = (g.bold * (0.8f + 0.4f * rnd.nextFloat())).coerceIn(0f, 1f)
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
                other.larder += g.larder; g.larder = 0f
                other.elders += g.elders; g.elders = 0
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
        const val CANDIDATES = 28
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
        /** How much of the gap to the best food a small selective feeder closes by picking its bites. */
        const val SELECTIVITY = 0.5f
        /** Snare lines a band fully equipped with nets and snares keeps across its range. */
        const val SNARE_LINES = 4f
        /** Adults needed to build a kite without calling on neighbours, and weekly chance of starting one where it fits. */
        const val KITE_HANDS = 30
        const val KITE_WEEKLY = 0.02f
        /** Open-country herds kites are built for, how many animals must pass, how near a herd must come, and the share taken in a drive. */
        val KITE_QUARRY by lazy { with(Catalogue) { listOf(GAZELLE, ONAGER, ORYX, HARTEBEEST, HORSE) } }
        const val KITE_HERDS = 200.0
        const val KITE_KM = 8f
        const val KITE_TAKE = 0.4f
        /** Ornaments a band keeps per adult before giving the rest away, kg. */
        const val ORNAMENTS_KEPT = 0.2f
        /** How much renown (0..1) counts against growth when bands decide whom to copy, and its weekly keep. */
        const val RENOWN_WEIGHT = 0.02f
        const val RENOWN_KEEP = 0.995f
        /** How far a band will go to a gathering, km, and how strongly a gathering draws it (as a season's pull). */
        const val GATHER_KM = 100f
        const val GATHER_PULL = 0.5f
        /** Places a band can know by name at once, and years it remembers one not seen. */
        const val MAX_PLACES = 40
        const val PLACE_MEMORY_YEARS = 20f
        /** Places a band's parties look over in a week. */
        const val EXPLORE_TRIES = 6
        /** How far a party goes for stone, km. */
        const val STONE_TRIP_KM = 60f
        /** Most a claim builds to (weeks of use), its weekly keep, and how unwelcome a firm claim makes strangers. */
        const val CLAIM_MAX = 104f
        const val CLAIM_KEEP = 0.995f
        const val TRESPASS = 0.5f
        /** Concepts whose words two bands try to align at a meeting. */
        const val WORDS_PER_MEETING = 3
        /** How much a band trusts another's report of a good place, against its own memory. */
        const val HEARSAY = 0.8f
        /** Weekly chance per concept that its word changes: about a fifth of a band's words in a thousand years. */
        const val WORD_DRIFT = 0.000004f
        /** Chance that a meeting of unrelated, well-fed strangers with nothing to take turns violent. */
        const val HOSTILITY = 0.02f
        /** Share of the raided band's adults killed in a raid by an equal force. */
        const val RAID_DEATHS = 0.05f
        /** Share of a grievance kept from week to week (half gone in about 13 years). */
        const val GRUDGE_KEEP = 0.999f
        /** Share of traditions a region's bands start with. */
        const val TRADITION_START = 0.5f
        /** Chance, at a meeting, of copying a differing tradition from a band doing clearly better. */
        const val IMITATE = 0.3f
        /** Chance, at a meeting, of taking up or dropping a tradition on one's own. */
        const val INNOVATE = 0.01f
        /** Adult deaths left where a band practises medicine. */
        const val MEDICINE_SAVES = 0.8f
        /** Fighting weight per kg of people with no weapons (a wolf is 1). */
        const val UNARMED_PEOPLE = 0.3f
        /** Hunting success of a band with no weapons at all, against one fully armed with spears. */
        const val WEAPONLESS = 0.3f
        /** Extra hunting success from bows against the fastest quarry, in practised hands. */
        const val BOW_GAIN = 0.4f
        /** Extra plant food brought in with sickles, baskets and grinding stones. */
        const val SICKLE_GAIN = 0.3f
        const val BASKET_GAIN = 0.3f
        const val GRINDER_GAIN = 0.4f
        /** Extra small game and fish with nets and snares. */
        const val NET_GAIN = 1f
        /** Most of a band's kit that can be made in a week. */
        const val MAKE_WEEKLY = 0.1f
        /** Raw materials a band carries, kg per adult of each kind. */
        const val GOODS_PER_PERSON = 20f
        /** Share of the difference in a material's stock that changes hands when bands meet. */
        const val TRADE_SHARE = 0.2f
        /** Days of food a band can carry when it moves camp. */
        const val CARRY_DAYS = 20f
        /** How far a band's hunting parties go, in multiples of its daily foraging range. */
        const val TASK_REACH = 2f
        /** Most that settled life with stores raises births (birth spacing about 4 years to under 3). */
        const val SETTLED_FERTILITY = 0.4f
        /** Extra yearly deaths among children in a camp kept for a year or more. */
        const val CAMP_FOULING = 0.04f
        /** Founding lineages tracked in a band's descent, and the size of the blocks they start in, km. */
        const val LINEAGES = 8
        const val LINEAGE_KM = 150f
        /** Years in a generation, for how fast inbreeding builds in a closed band. */
        const val GENERATION_YEARS = 25f
        /** Extra yearly deaths among children per unit of inbreeding (first cousins' children: F = 0.0625). */
        const val INBREEDING_DEATHS = 0.3f
        /** Kinship with a partner from another band, per unit of shared descent (about second cousins). */
        const val OUTSIDER_KIN = 0.03f
        /** Weekly chance that two bands whose ranges fully overlap meet and exchange partners. */
        const val MARRY_WEEKLY = 0.1f
        /** Years an adult spends bearing and raising children before becoming an elder. */
        const val ADULT_YEARS = 30f
        /** Extra yearly deaths among elders. */
        const val ELDER_DEATHS = 0.05f
        /** Mothers one elder can help. */
        const val ELDER_MOTHERS = 3f
        /** Most that grandmothers raise births, and cut children's deaths, when there are enough of them. */
        const val GRANDMOTHERS = 0.3f
        const val ELDER_CARE = 0.3f
        /** Days of food a band with full storing skill can keep. */
        const val LARDER_DAYS = 120f
        /** Share of stores lost each week to damp, mould and mice. */
        const val SPOIL = 0.01f
        /** Share of night attacks on a camp that its fire keeps off. */
        const val CAMPFIRE = 0.6f
        /** Predators at least this dangerous to people (lions, leopards) are avoided by bands. */
        const val DANGEROUS_TO_PEOPLE = 0.4f
        /** Share of the chances to take a big cat's kill that people actually take. */
        const val PEOPLE_TAKE_KILLS = 0.3f
        /** People per km² over a band's range at which crowding brings sickness (rich foraging country holds 0.5 and more). */
        const val PEOPLE_FULL_KM2 = 0.5f
        /** Share of a band's food that ends on the midden. */
        const val REFUSE = 0.1f
        /** How strongly a person in the range draws a camp follower, against a 20 kg prey animal (1). */
        const val CAMP_LURE = 0.5f
        /** How far one hunt by people closes a herd's gap to full shyness. */
        const val WARY_GAIN = 0.3f
        /** Share of shyness kept from week to week (half gone in about half a year). */
        const val WARY_KEEP = 0.97f
        /** How much fear of people, above or below [BASE_FEAR], changes their chance of surprising a herd. */
        const val WARY_EFFECT = 0.6f
        /** The fear of an ordinary game herd, at which people's hunting is calibrated. */
        const val BASE_FEAR = 0.7f
        /** Extra chance a bold animal is the one people kill (1: twice as likely); its share drops by this over the group size. */
        const val BOLD_TOLL = 1f
        /** Weekly shift towards boldness in camp followers that live near people (centuries, not years). */
        const val BOLD_DRIFT = 0.0002f
        /** Chance a band burns off grass in a dry-season week. */
        const val BURN_WEEKLY = 0.1f
        /** How much people let danger put them off a quarry, against 5 for other hunters. */
        const val PEOPLE_CAUTION = 15f
        /** Share of the danger of a cornered quarry that reaches hunters armed with spears and bows. */
        const val RANGED_RISK = 0.2f
        /** Share of the success an untrained band still has at a hunt or a harvest. */
        const val CRAFT_FLOOR = 0.3f
        /** How far one attempt or one week's use closes the gap to mastery. */
        const val PRACTICE = 0.02f
        /** Share of a skill that fades each week without use (about a fifth a year). */
        const val FORGET = 0.004f
        /** Share of the plant food in its range a skilled band can gather and process in a week. */
        const val GATHER_REACH = 0.05f
        /** How much darker nights add to a full stalker's surprise (and full moon takes away). */
        const val MOON_DARK = 0.3f
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
        /** Food value of browse, roots and mast, and fruit, 0..1 (grass varies, see [grassQuality]). */
        const val BROWSE_QUALITY = 0.65f
        const val ROOTS_QUALITY = 0.8f
        const val FRUIT_QUALITY = 0.95f
        /** Chance a young animal, and an adult, of a group whose ground burns dies in the fire (more for slow kinds). */
        const val FIRE_YOUNG = 0.15f
        const val FIRE_ADULT = 0.02f
        /** Fruit eaten per km² in a week that spreads a full seed rain. */
        const val SEED_RAIN_KG = 5f
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
