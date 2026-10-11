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
 * animals as groups that decide where to go each day (several days per world step).
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
     * How much the scent of each prey species draws each predator, by species index: its mass,
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
    var kills = 0; private set
    var births = 0; private set
    var deaths = 0; private set
    /** Kills by prey species since the counter was last cleared. */
    val killsBy = HashMap<Species, Int>()
    /** Kills by predator species, and kills taken from others by predator species, since last cleared. */
    val killsByPredator = HashMap<Species, Int>()
    val stolen = HashMap<Species, Int>()
    /** Deaths other than predation, by "species:cause", since last cleared. */
    val deathsBy = HashMap<String, Int>()

    // Coarse scent grids: each species leaves scent where it goes; it fades within days.
    private val scentKm = 4f
    private val sw = ceil(grid.width * cellKm / scentKm).toInt()
    private val sh = ceil(grid.height * cellKm / scentKm).toInt()
    private val scent = Array(Catalogue.all.size) { FloatArray(sw * sh) }

    // Spatial buckets per species, rebuilt each day, for finding nearby groups.
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
        updateWater()
        updateSight()
        populate()
    }

    fun step() {
        kills = 0; births = 0; deaths = 0
        plankton.step()
        fish.step()
        soil.step()
        small.step()
        regrow()
        stepCount++
        updateWater()
        updateSight()
        repeat(days) { day() }
        demography()
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
        for (i in 0 until n) {
            if (!v.isVegetated(i)) { graze[i] = 0f; browse[i] = 0f; continue }
            val t = sim.weather.temperature[i]
            val warm = ((t - 2f) / 10f).coerceIn(0f, 1f)
            val wet = (sim.water.soil[i] / 60f).coerceIn(0.1f, 1f)
            val g = PALATABLE_SHARE * 1e6f * v.herb[i] * warm * wet / REGROWTH_DAYS
            val b = PALATABLE_SHARE * 1e6f * woody(i) * max(0.3f, warm) * max(0.5f, wet) / REGROWTH_DAYS
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

    private fun plantDiet(s: Species) = max(1e-3f, s.diet.grass + s.diet.browse + s.diet.roots)

    /** Food for [s] at [i] in the shares of its diet, kg/km². */
    fun food(s: Species, i: Int) = (s.diet.grass * graze[i] + s.diet.browse * browse[i] + s.diet.roots * roots[i]) / plantDiet(s)

    // ---------------------------------------------------------------- sight


    /** Caches [concealKm] and [sightKm] for this step. */
    private fun updateSight() {
        val v = sim.vegetation
        java.util.stream.IntStream.range(0, n).parallel().forEach { i ->
            concealKm[i] = terrain.concealmentKm(i, v)
            sightKm[i] = terrain.sightKm(i, v)
        }
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
        for (i in 0 until n) {
            if (world.isSea[i]) { wetness[i] = 0f; waterKm[i] = big; continue }
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
        if (!v.isVegetated(i) || world.elevation[i] > 2500f || waterKm[i] > 15f) return 0f
        val food = ((s.diet.grass * v.herb[i] / 0.3f + s.diet.browse * woody(i) / 0.45f + s.diet.roots * (v.herb[i] + 0.05f * v.tree[i]) / 0.7f) / plantDiet(s)).coerceIn(0f, 1f)
        val h = s.habitat
        val ground = 0.5f + h.cover * (cover(i) - 0.5f) + h.rugged * (terrain.ruggedness[i] - 0.3f) * 1.5f + h.wet * (wetness[i] - 0.3f)
        return food * ground.coerceIn(0f, 1f)
    }

    private fun isWetland(i: Int) = world.biome[i].name.let { it == "MARSH" || it == "SWAMP_FOREST" || it == "BOG" || it == "SALT_MARSH" || it == "MANGROVE" }

    private fun populate() {
        for (s in Catalogue.herbivores) {
            // Several species share the plants, so each starts well below its density alone.
            val perCell = 0.15f * Allometry.herbivoreDensityPerKm2(s.massKg) * cellKm2 / s.groupSize
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
        groups += group
        return group
    }

    // ---------------------------------------------------------------- one day

    private fun cellOf(g: AnimalGroup) = grid.index(g.x.toInt().coerceIn(0, grid.width - 1), g.y.toInt().coerceIn(0, grid.height - 1))
    private fun scentIndex(x: Double, y: Double) =
        (y * cellKm / scentKm).toInt().coerceIn(0, sh - 1) * sw + (x * cellKm / scentKm).toInt().coerceIn(0, sw - 1)
    private fun bucketIndex(x: Double, y: Double) =
        (y * cellKm / bucketKm).toInt().coerceIn(0, bh - 1) * bw + (x * cellKm / bucketKm).toInt().coerceIn(0, bw - 1)

    private fun day() {
        dayCount++
        for (f in scent) for (k in f.indices) f[k] *= 0.5f
        for (b in buckets) for (l in b) l.clear()
        for (g in groups) if (g.alive) {
            scent[g.species.index][scentIndex(g.x, g.y)] += g.size.toFloat()
            buckets[g.species.index][bucketIndex(g.x, g.y)] += g
        }
        // Every group decides where to go from what it perceives at dawn. Deciding only reads the
        // world, so all groups decide at once, in parallel; then they move, eat and hunt one at a
        // time in a random order, so no group always goes first.
        val size = groups.size
        if (planX.size < size) { planX = DoubleArray(size * 2); planY = DoubleArray(size * 2) }
        java.util.stream.IntStream.range(0, size).parallel().forEach { k ->
            val g = groups[k]
            if (g.alive) plan(g, k, scratch.get()) else planX[k] = Double.NaN
        }
        val order = IntArray(size) { it }
        for (k in size - 1 downTo 1) { val j = rnd.nextInt(k + 1); val t = order[k]; order[k] = order[j]; order[j] = t }
        val startX = DoubleArray(size) { groups[it].x }; val startY = DoubleArray(size) { groups[it].y }
        for (k in order) {
            val g = groups[k]
            if (!g.alive) continue
            // A herd that bolted from a pack earlier today has already gone elsewhere.
            if (!planX[k].isNaN() && g.x == startX[k] && g.y == startY[k]) go(g, planX[k], planY[k])
            if (g.species.carnivore) hunt(g, contested = meetEnemy(g) || meet(g)) else graze(g)
            drink(g)
            if (g.alert > 0) g.alert--
        }
    }

    private var dayCount = 0
    private var planX = DoubleArray(0)
    private var planY = DoubleArray(0)

    /** Per-thread buffers for [plan]. */
    private class Scratch {
        val around = ArrayList<AnimalGroup>()
        val walkTo = DoubleArray(2)
        val tx = DoubleArray(9)
        val ty = DoubleArray(9)
        val options = ArrayList<Option>(9)
    }
    private val scratch = ThreadLocal.withInitial { Scratch() }

    /**
     * Deterministic noise in -0.5..0.5 for [g]'s option [k] today: a hash of the seed, the day and
     * the group, so results do not depend on which thread decided for which group.
     */
    fun noise(g: AnimalGroup, k: Int): Float {
        var h = world.config.seed.toLong() * -0x61c8864680b583ebL + dayCount * 0x9E3779B97F4A7C15uL.toLong() + g.id * 0x632BE59BD9B4E019L + k
        h = (h xor (h ushr 33)) * -0xae502812aa7333L
        h = (h xor (h ushr 33)) * -0x3b314601e57a13adL
        h = h xor (h ushr 33)
        return (h ushr 40).toFloat() / (1L shl 24).toFloat() - 0.5f
    }

    /** The nearest live group of species [s] to (x, y) within [km], and its distance. */
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

    private val around = ArrayList<AnimalGroup>()

    private fun dist(dx: Double, dy: Double) = sqrt(dx * dx + dy * dy)

    /** Collects into [out] the live groups of any of [species] within [km] of (x, y). */
    private fun within(species: List<Species>, x: Double, y: Double, km: Float, out: ArrayList<AnimalGroup>) {
        out.clear()
        for (s in species) collect(s, x, y, km, out)
    }

    private fun collect(s: Species, x: Double, y: Double, km: Float, out: ArrayList<AnimalGroup>) {
        val b = buckets[s.index]
        val bx = (x * cellKm / bucketKm).toInt(); val by = (y * cellKm / bucketKm).toInt()
        val r = ceil(km / bucketKm).toInt()
        val lim = km / cellKm
        for (yy in max(0, by - r)..min(bh - 1, by + r)) for (xx in max(0, bx - r)..min(bw - 1, bx + r))
            for (o in b[yy * bw + xx]) if (o.alive && dist(o.x - x, o.y - y) < lim) out += o
    }

    /** Scent of prey worth hunting for predator [p] at (x, y), weighted by [lure], normalised 0..1. */
    private fun preyScent(p: Species, x: Double, y: Double): Float {
        val k = scentIndex(x, y)
        val w = lure.getValue(p)
        var v = 0f
        for (s in Catalogue.herbivores) v += scent[s.index][k] * w[s.index]
        return v / (v + 30f)
    }

    /** Scent of [s]'s enemies at (x, y), normalised 0..1. */
    private fun enemyScent(s: Species, x: Double, y: Double): Float {
        var v = 0f
        for (e in enemies.getValue(s)) v = max(v, scentAt(e, x, y, 6f))
        return v
    }

    private fun scentAt(s: Species, x: Double, y: Double, norm: Float): Float {
        val v = scent[s.index][scentIndex(x, y)]
        return v / (v + norm)
    }

    /** How well adults of [prey] fend off hunters weighing [packKg] together: horns and bulk deter a few hunters more than a big pack or pride. */
    private fun effectiveDefence(prey: Species, packKg: Float) = prey.defence * sqrt(min(1f, prey.massKg / (2f * packKg)))

    private fun threatens(p: Species, s: Species) =
        if (s.carnivore) strength(p, p.groupSize) > 1.5f * strength(s, s.groupSize) else p.massKg * p.groupSize >= 0.3f * s.massKg

    /** Fighting weight of [adults] of predator species [s]. */
    private fun strength(s: Species, adults: Int) = s.massKg * adults * s.ferocity

    /** How well the ground and plants at [i] hide things, 0 (seen from 2 km or more) to 1. */
    private fun cover(i: Int) = 1f - min(1f, concealKm[i] / 2f)

    /** Whether a group at distance [km] in cell [at] can be seen from cell [from]. */
    private fun visible(from: Int, at: Int, km: Float) =
        km <= concealKm[at] && km <= sightKm[from]

    /**
     * Walks from (x, y) in direction [k] until a day's effort [budgetKm] (km of flat open ground)
     * is spent or water blocks the way. Puts the end point in [walkTo] and returns the effort used.
     */
    private fun walk(s: Species, x0: Double, y0: Double, k: Int, budgetKm: Float, walkTo: DoubleArray): Float {
        var x = x0; var y = y0; var spent = 0f
        val v = sim.vegetation
        // Deer and boar slip through thickets, ibex climb rock: their favoured ground costs them less.
        val thicketEase = max(0f, s.habitat.cover) * 0.8f
        val rockEase = max(0f, s.habitat.rugged) * 0.8f
        while (true) {
            val nx = x + COS[k]; val ny = y + SIN[k]
            if (nx < 0 || ny < 0 || !grid.inBounds(nx.toInt(), ny.toInt())) break
            val c = grid.index(nx.toInt(), ny.toInt())
            val cost = terrain.travelCost(c, v, sim.weather.snowpack[c], thicketEase, rockEase) * cellKm
            if (!cost.isFinite() || spent + cost > budgetKm) {
                // Part of a cell: go as far as the remaining effort allows.
                if (cost.isFinite() && spent < budgetKm) {
                    val f = (budgetKm - spent) / cost
                    x += COS[k] * f; y += SIN[k] * f; spent = budgetKm
                }
                break
            }
            x = nx; y = ny; spent += cost
        }
        walkTo[0] = x; walkTo[1] = y
        return spent
    }

    /** Chooses where [g] goes today and stores the target in [planX], [planY] at [slot] (NaN to stay put). */
    private fun plan(g: AnimalGroup, slot: Int, sc: Scratch) {
        val s = g.species
        val carnivore = s.carnivore
        val here = cellOf(g)
        // A herd that sees a predator close by runs; in cover it may not see it in time.
        val threatened = !carnivore && enemies.getValue(s).any { p -> nearest(p, g.x, g.y, 2f * s.flightKm).let { (w, km) -> w != null && visible(here, cellOf(w), km) } }
        val budget = if (threatened) s.maxDailyTravelKm / 3f else s.dailyTravelKm
        val policy = policies.getValue(s)
        gather(g, budget, sc)
        var choice = policy.choose(g, sc.options)
        // Grazing herds drift a few km a day. Where a day's drift finds neither enough food nor, for a
        // thirsty herd, water, they set off on a march instead: many times as far, eating little on
        // the way, day after day until they reach better ground, as herds cross a dry plain.
        g.marching = false
        if (!carnivore && !threatened && mustMarch(g, sc.options[choice])) {
            gather(g, MARCH_SHARE * s.maxDailyTravelKm, sc)
            choice = policy.choose(g, sc.options)
            g.marching = choice != 0 && sc.options[choice].cell >= 0
        }
        if (sc.options[choice].cell < 0 || choice == 0) { planX[slot] = Double.NaN; return }
        planX[slot] = sc.tx[choice]; planY[slot] = sc.ty[choice]
    }

    /** Whether the best place within an ordinary day's walk still leaves [g] hungry or thirsty. */
    private fun mustMarch(g: AnimalGroup, best: Option): Boolean {
        if (best.cell < 0) return true
        val s = g.species
        val thirsty = g.daysWithoutWater >= s.daysWithoutWater - 1
        // Food within grazing reach (3 x 3 cells) far short of a day's need, or no water within reach today.
        return best.forage * 9f * cellKm2 < MARCH_FOOD * g.dailyNeedKg || (thirsty && best.waterKm > s.dailyTravelKm)
    }

    /** Fills [sc] with the stay option and eight directions walked with effort [budget]. */
    private fun gather(g: AnimalGroup, budget: Float, sc: Scratch) {
        val s = g.species
        val carnivore = s.carnivore
        // Others the group can perceive around today's possible destinations, gathered once:
        // prey for a predator, enemies for prey.
        val others = if (carnivore) Catalogue.herbivores else enemies.getValue(s)
        val radius = if (carnivore) s.senseKm else 3f
        val around = sc.around
        within(others, g.x, g.y, budget + radius, around)
        val options = sc.options
        options.clear()
        val tx = sc.tx; val ty = sc.ty; val walkTo = sc.walkTo
        for (k in -1 until 8) {
            val km = if (k < 0) { walkTo[0] = g.x; walkTo[1] = g.y; 0f } else walk(s, g.x, g.y, k, budget, walkTo)
            val x = walkTo[0]; val y = walkTo[1]
            tx[k + 1] = x; ty[k + 1] = y
            val xi = x.toInt(); val yi = y.toInt()
            val cell = if (x >= 0 && y >= 0 && grid.inBounds(xi, yi) && sim.vegetation.isVegetated(grid.index(xi, yi))) grid.index(xi, yi) else -1
            if (cell < 0 || (k >= 0 && km == 0f)) { options += Option(-1, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f); continue }
            val forage = if (carnivore) small.rodents[cell] else food(s, cell)
            // Sight of the others from there; scent carries regardless of cover.
            var near = radius
            val sight = sightKm[cell]
            for (o in around) {
                val d = (dist(o.x - x, o.y - y) * cellKm).toFloat()
                if (d < near && d <= sight && d <= concealKm[cellOf(o)]) near = d
            }
            val seen = 1f - near / radius
            val danger = if (carnivore) enemyScent(s, x, y) else max(enemyScent(s, x, y), seen)
            val preySig = if (!carnivore) 0f else max(preyScent(s, x, y), seen)
            val kin = scentAt(s, x, y, 3f * s.groupSize) * if (k < 0) 0.5f else 1f // own scent counts less where it stands
            options += Option(cell, km, forage, danger, preySig, kin, waterKm[cell], sim.weather.snowpack[cell], cover(cell), terrain.ruggedness[cell], wetness[cell])
        }
    }

    /** Moves [g] to (x, y), trampling the cells along the way: repeated, the passage wears a trail. */
    private fun go(g: AnimalGroup, x: Double, y: Double) {
        val from = cellOf(g)
        g.x = x; g.y = y
        val to = cellOf(g)
        val steps = max(1, max(abs(grid.x(to) - grid.x(from)), abs(grid.y(to) - grid.y(from))))
        for (t in 0..steps) {
            val xi = grid.x(from) + (grid.x(to) - grid.x(from)) * t / steps
            val yi = grid.y(from) + (grid.y(to) - grid.y(from)) * t / steps
            sim.vegetation.trample(grid.index(xi, yi), g.biomassKg / TRAMPLE_KG)
        }
    }

    private fun graze(g: AnimalGroup) {
        val s = g.species
        val c = cellOf(g)
        // On the march they snatch a few mouthfuls on the way.
        val want = g.dailyNeedKg * if (g.marching) MARCH_APPETITE else 1f
        var got = 0f
        if (sim.weather.snowpack[c] <= s.snowLimitMm) {
            val total = plantDiet(s)
            // Each part of the diet from its own stock; a shortfall in one is partly made up from the other.
            val fromGrass = eat(c, graze, want * s.diet.grass / total, LifeForm.HERB)
            val fromBrowse = eat(c, browse, want * s.diet.browse / total, LifeForm.SHRUB)
            val fromRoots = eat(c, roots, want * s.diet.roots / total, null)
            got = fromGrass + fromBrowse + fromRoots
            // Grazers and browsers partly make up a shortfall from the other's food; rooting animals do not.
            if (got < want && s.diet.roots < 0.5f) {
                val short = want - got
                got += 0.5f * (if (s.diet.grass >= s.diet.browse) eat(c, browse, short, LifeForm.SHRUB) else eat(c, graze, short, LifeForm.HERB))
            }
            val fresh = got
            // The stomach holds only so much: coarse food fills it before it nourishes.
            val value = roughageValue(s)
            if (got < want) got += roughage(c, min(want - got, max(0f, GUT_FILL * want - fresh) * value), value)
            g.freshShare = if (got > 0f) fresh / got else 0f
        } else g.freshShare = 0f
        feed(g, min(1f, got / g.dailyNeedKg))
    }

    /**
     * Winter and dry-season fallback: [straw]. Poor food: it fills the stomach with [value] of the
     * nourishment, so animals live through lean months on it while losing condition, and only
     * while it lasts. Returns nourishment in kg of good food.
     */
    private fun roughage(c: Int, nourishmentKg: Float, value: Float): Float =
        value * eat(c, straw, nourishmentKg / value, LifeForm.HERB)

    /**
     * How much a species gets out of coarse forage (the Jarman-Bell principle): a big gut holds
     * food longer and digests fibre better, while a small animal's high metabolism needs rich food.
     * Aurochs and wild asses live through the dry season on straw that starves a gazelle.
     */
    private fun roughageValue(s: Species) = (ROUGHAGE_VALUE * s.fibreDigestion * (s.massKg / 100f).pow(0.25f)).coerceAtMost(0.75f)

    /** Eats up to [kg] from [stock] over the 3 x 3 cells around [c], taking it from plants of [form]; returns kg eaten. */
    private fun eat(c: Int, stock: FloatArray, kg: Float, form: LifeForm?): Float {
        if (kg <= 0f) return 0f
        val cx = grid.x(c); val cy = grid.y(c)
        var available = 0f
        for (dy in -1..1) for (dx in -1..1) if (grid.inBounds(cx + dx, cy + dy)) available += stock[grid.index(cx + dx, cy + dy)] * cellKm2
        if (available <= 0f) return 0f
        val share = min(1f, kg / available)
        var eaten = 0f
        for (dy in -1..1) for (dx in -1..1) {
            if (!grid.inBounds(cx + dx, cy + dy)) continue
            val i = grid.index(cx + dx, cy + dy)
            val e = stock[i] * share
            stock[i] -= e
            if (form != null) sim.vegetation.consume(i, form, (e * cellKm2 / grid.cellAreaM2).toFloat())
            eaten += e * cellKm2
        }
        return eaten
    }

    private fun hunt(g: AnimalGroup, contested: Boolean) {
        val s = g.species
        val c = cellOf(g)
        val need = g.dailyNeedKg
        // A hungry pack tests the herds it can reach today, up to four chases, picking the one
        // that promises most meat for the risk. Healthy adults usually outrun or fight off wolves;
        // the young and the weak are caught far more often, a big herd spots the pack sooner,
        // large and well-armed animals are rarely worth it, and ibex on cliffs cannot be followed.
        // Carrion first, up to its share of the diet: a scavenger finds most of its food this way.
        if (g.stored < need) g.stored += soil.scavenge(c, (s.diet.carrion * need / grid.cellAreaM2).toFloat()) * grid.cellAreaM2.toFloat()
        // Scavengers and small hunters chase big game only for their smaller share of meat.
        var attempts = 0
        val packKg = g.adults * s.massKg
        val wantMeat = need * min(1f, 2f * s.diet.meat)
        while (!contested && g.stored < wantMeat && g.adults > 0 && attempts++ < 4) {
            within(Catalogue.herbivores, g.x, g.y, HUNT_KM, around)
            var prey: AnimalGroup? = null; var bestValue = 0f; var bestP = 0f; var bestYoung = false; var bestRisk = 0f
            for (o in around) {
                if (!o.alive) continue
                val ps = o.species
                val at = cellOf(o)
                val eyes = 1f - 0.3f * min(1f, o.size / 40f)
                val wary = if (o.alert > 0) 0.3f else 1f
                // Cover lets a stalker get close unseen; a coursing pack gains less, as it must still run the prey down.
                val ambush = 1f + 0.8f * s.stalking * cover(at)
                val refuge = 1f - 0.8f * max(0f, ps.habitat.rugged) * terrain.ruggedness[at]
                val young = o.young > 0 && rnd.nextFloat() < 2f * o.young / (o.size + o.young)
                val kg = if (young) 0.3f * ps.massKg else ps.massKg
                // Hunters much lighter than their quarry rarely bring it down.
                val size = min(1f, packKg / (1.5f * kg)).let { it * it }
                val defence = effectiveDefence(ps, packKg)
                val base = if (young) YOUNG_CATCH * (1f - 0.5f * defence) else (0.15f + 0.5f * (1f - o.condition)) * (1f - defence)
                val p = min(1f, g.adults / 2f) * eyes * wary * ambush * refuge * size * base
                // Big, armed prey fight back: horns and tusks kill hunters. Worth it only for much meat.
                val risk = if (young) 0f else fightBack(ps, s)
                val value = p * min(kg, 4f * need) * max(0f, 1f - 5f * risk)
                if (value > bestValue) { bestValue = value; prey = o; bestP = p; bestYoung = young; bestRisk = risk }
            }
            if (prey == null) break
            // Caught or not, the herd bolts and stays watchful for days.
            prey.alert = ALERT_DAYS
            bolt(prey, g)
            if (rnd.nextFloat() >= bestP) continue
            // Brought to bay, a big animal may take one of its killers with it.
            if (bestRisk > 0f && rnd.nextFloat() < bestRisk) {
                g.adults--; deaths++
                deathsBy.merge("${s.name}:fought back", 1, Int::plus)
                soil.carrion[c] += (s.massKg / grid.cellAreaM2).toFloat()
            }
            val meat = if (bestYoung) { prey.young--; 0.3f * prey.species.massKg } else { prey.adults--; prey.species.massKg }
            g.stored += meat - steal(g, meat)
            kills++
            killsBy.merge(prey.species, 1, Int::plus)
            killsByPredator.merge(s, 1, Int::plus)
        }
        var eaten = min(g.stored, need)
        g.stored -= eaten
        // Small prey and carrion fill the gap. Catching voles one at a time yields little: at most
        // the small-prey share of the diet.
        if (eaten < need) eaten += small.catchRodents(c, min(need - eaten, s.diet.smallPrey * need) / cellKm2) * cellKm2
        if (eaten < need) eaten += soil.scavenge(c, ((need - eaten) / grid.cellAreaM2).toFloat()) * grid.cellAreaM2.toFloat()
        // A pack can gorge and cache a few days' food; the rest is left to rot and to scavengers.
        val cap = 4f * need
        if (g.stored > cap) {
            soil.carrion[c] += ((g.stored - cap) / grid.cellAreaM2).toFloat()
            g.stored = cap
        }
        g.freshShare = min(1f, eaten / need)
        feed(g, eaten / need)
        if (g.stored > 0f) g.stored *= 0.9f // spoilage and theft
    }

    /**
     * Chance per kill that an adult of [prey] kills one of the hunting [predator]s: grows with its
     * defence and with how much heavier it is than one hunter.
     */
    private fun fightBack(prey: Species, predator: Species) = FIGHT_BACK * prey.defence * min(2f, prey.massKg / predator.massKg)

    /**
     * A kill draws rivals: the strongest other predator group within [THEFT_KM], if it outweighs
     * the killers, drives them off and takes most of the meat (hyenas rob leopards, lions rob
     * everyone). Returns the kg taken from [g].
     */
    private fun steal(g: AnimalGroup, kg: Float): Float {
        var thief: AnimalGroup? = null; var best = strength(g.species, g.adults)
        for (p in Catalogue.predators) {
            if (p == g.species) continue
            val (o, _) = nearest(p, g.x, g.y, THEFT_KM)
            if (o != null && o.adults > 0 && strength(p, o.adults) * (0.7f + 0.6f * rnd.nextFloat()) > best) { thief = o; best = strength(p, o.adults) }
        }
        if (thief == null) return 0f
        val taken = 0.7f * kg
        thief.stored += taken
        stolen.merge(thief.species, 1, Int::plus)
        return taken
    }

    /**
     * A much stronger predator met close by kills now and then, and drives the weaker off for the
     * day: lions kill leopards, hyenas and wolves; all of them kill jackals. Returns whether [g] met one.
     */
    private fun meetEnemy(g: AnimalGroup): Boolean {
        for (p in enemies.getValue(g.species)) {
            val (o, _) = nearest(p, g.x, g.y, 1f)
            if (o == null || o.adults == 0) continue
            if (g.adults > 0 && rnd.nextFloat() < INTRAGUILD_KILL) {
                g.adults--; deaths++
                deathsBy.merge("${g.species.name}:killed by ${p.name}", 1, Int::plus)
                o.stored += g.species.massKg * 0.5f
            }
            flee(g, o)
            return true
        }
        return false
    }

    /** Moves [loser] a day's walk directly away from [winner], if there is land there. */
    private fun flee(loser: AnimalGroup, winner: AnimalGroup) {
        val dx = loser.x - winner.x; val dy = loser.y - winner.y
        val d = max(1e-3, dist(dx, dy))
        val run = loser.species.dailyTravelKm / cellKm
        val nx = loser.x + dx / d * run; val ny = loser.y + dy / d * run
        if (grid.inBounds(nx.toInt(), ny.toInt()) && nx >= 0 && ny >= 0 && sim.vegetation.isVegetated(grid.index(nx.toInt(), ny.toInt()))) { loser.x = nx; loser.y = ny }
    }

    /**
     * Packs defend their ground: when two meet, they spend the day in threat and chase instead of
     * hunting, the smaller pack is usually driven off, and now and then a wolf is killed. Crowded
     * packs therefore hunt less each; in studied wolf populations strife is a leading cause of death.
     * Returns whether the pack met another.
     */
    private fun meet(g: AnimalGroup): Boolean {
        val (other, _) = nearest(g.species, g.x, g.y, g.species.territoryKm, except = g)
        if (other == null) return false
        val loser = if (rnd.nextFloat() < g.adults / (g.adults + other.adults + 1e-3f)) other else g
        // Most meetings end in a chase; a few in a death.
        if (loser.adults > 0 && rnd.nextFloat() < 0.05f) {
            loser.adults--; deaths++
            soil.carrion[cellOf(loser)] += (loser.species.massKg / grid.cellAreaM2).toFloat()
        }
        flee(loser, if (loser === g) other else g)
        return true
    }

    /** Moves [prey] a few km directly away from [predator], if there is land there. */
    private fun bolt(prey: AnimalGroup, predator: AnimalGroup) {
        val dx = prey.x - predator.x; val dy = prey.y - predator.y
        val d = max(1e-3, dist(dx, dy))
        val run = prey.species.maxDailyTravelKm / 3f / cellKm
        val nx = prey.x + dx / d * run; val ny = prey.y + dy / d * run
        if (nx >= 0 && ny >= 0 && grid.inBounds(nx.toInt(), ny.toInt()) && sim.vegetation.isVegetated(grid.index(nx.toInt(), ny.toInt()))) { prey.x = nx; prey.y = ny }
    }

    private fun feed(g: AnimalGroup, ratio: Float) {
        g.lastIntakeRatio = ratio
        // Losing condition is slower than for a starving animal: on poor food they cut their metabolism.
        g.condition = if (ratio >= 0.9f) min(1f, g.condition + 0.01f) else max(0f, g.condition - 0.012f * (1f - ratio))
    }

    /**
     * Drinks if water is near; otherwise fresh food and dew cover part of the day's need (more on
     * wet ground), and the rest adds to the group's thirst. Past its limit it loses condition fast.
     */
    private fun drink(g: AnimalGroup) {
        val c = cellOf(g)
        if (waterKm[c] <= 1.5f) { g.daysWithoutWater = 0f; return }
        val fromFood = g.species.waterFromFood * g.freshShare * (0.5f + 0.5f * wetness[c])
        g.daysWithoutWater += max(0f, 1f - fromFood)
        if (g.daysWithoutWater > g.species.daysWithoutWater) g.condition = max(0f, g.condition - 0.05f)
    }

    // ---------------------------------------------------------------- births, deaths, splitting

    private fun binomial(n: Int, p: Float): Int {
        if (n <= 0 || p <= 0f) return 0
        if (p >= 1f) return n
        var k = 0
        repeat(n) { if (rnd.nextFloat() < p) k++ }
        return k
    }

    private fun demography() {
        val years = days / Date.DAYS_PER_YEAR.toFloat()
        val date = sim.weather.date
        val firstStepOfMonth = date.dayOfMonth <= days
        val newcomers = ArrayList<AnimalGroup>()
        for (g in groups) {
            if (!g.alive) continue
            val s = g.species
            val c = cellOf(g)
            val fit = ((g.condition - 0.3f) / 0.4f).coerceIn(0f, 1f)
            // Births in the season.
            val inSeason = (date.month - s.birthMonth + 12) % 12 < s.birthMonths
            if (inSeason) {
                val born = if (s.packLitter > 0) {
                    if (firstStepOfMonth && date.month == s.birthMonth && g.adults >= 2) (s.packLitter * fit + rnd.nextFloat()).toInt() else 0
                } else {
                    val stepsInSeason = s.birthMonths * Date.DAYS_PER_MONTH / days.toFloat()
                    binomial(g.adults, 0.5f * s.youngPerFemale * fit / stepsInSeason)
                }
                g.young += born; births += born
            }
            // Growing up.
            val matured = binomial(g.young, years / s.yearsToMaturity)
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
            val pYoung = min(1f, pAdult + s.youngMortality * years + 0.1f * hunger)
            val deadA = binomial(g.adults, pAdult)
            val deadY = binomial(g.young, pYoung)
            if (deadA + deadY > 0) {
                val cause = when {
                    sim.eruptions.ash[c] > 300f -> "ash"
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
            deaths += dead
            if (dead > 0) soil.carrion[c] += (s.massKg * (deadA + 0.5f * (deadY + orphaned)) / grid.cellAreaM2).toFloat()
            // Splitting: a group too big for its food divides.
            if (g.size > s.maxGroupSize) {
                val a = g.adults / 2; val y = g.young / 2
                g.adults -= a; g.young -= y
                val child = AnimalGroup(nextId++, s, g.x + rnd.nextDouble() - 0.5, g.y + rnd.nextDouble() - 0.5, a, y)
                child.condition = g.condition
                child.tag = g.tag
                newcomers += child
            }
        }
        groups += newcomers
        // Joining: small remnants merge into a nearby group of their kind.
        for (s in Catalogue.all) {
            val b = buckets[s.index]
            for (l in b) l.clear()
            for (g in groups) if (g.species == s && g.alive) b[bucketIndex(g.x, g.y)] += g
            for (g in groups) {
                // A pair-breeding predator left without a mate looks for one as well.
                val alone = g.size < s.groupSize / 3 || (s.packLitter > 0 && g.adults < 2)
                if (g.species != s || !g.alive || !alone) continue
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
        /** Days for dug-up roots and mast to be replaced. */
        const val ROOTS_DAYS = 180f
        /** Distance at which a pack can stalk and chase a herd it has found, km. */
        const val HUNT_KM = 4f
        /** Animal mass whose passage counts as trampling intensity 1 (see Vegetation.trample). */
        const val TRAMPLE_KG = 150_000f
        private val COS = DoubleArray(8) { kotlin.math.cos(it * Math.PI / 4) }
        private val SIN = DoubleArray(8) { kotlin.math.sin(it * Math.PI / 4) }
        /** Ground wetter than this has rain pools, seeps or springs to drink from (when not frozen). */
        const val POOL_WETNESS = 0.8f
        /** A march covers this share of a species' longest day's travel, day after day. */
        const val MARCH_SHARE = 0.6f
        /** Share of its daily food a marching herd eats on the way. */
        const val MARCH_APPETITE = 0.3f
        /** A herd marches when the food within reach covers less than this share of a day's need. */
        const val MARCH_FOOD = 0.3f
        /** Share of runoff that sinks in and flows on underground. */
        const val GROUND_SHARE = 0.3f
        /** Groundwater flow below a cell that can feed a spring, m³/yr (about a litre a second). */
        const val MIN_SPRING_FLOW = 3e4f
        /** Dry country per spring or water hole where groundwater is ample, km². */
        const val SPRING_KM2 = 150f
        /** Days a herd stays watchful after an attack. */
        const val ALERT_DAYS = 5
    }
}
