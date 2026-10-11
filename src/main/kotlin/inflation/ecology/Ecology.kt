package inflation.ecology

import inflation.world.Date
import inflation.world.LifeForm
import inflation.world.Simulation
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Animal life on top of the vegetation: plankton, soil life and small fauna as fields, and large
 * animals as groups that decide where to go each day (several days per world step).
 *
 * Herbivores eat from [palatable]: the young, nutritious part of the plants that they select,
 * which regrows over about a month. Standing biomass is far larger than what grazers can use;
 * this stock is what limits them. Its size per unit of plant biomass ([PALATABLE_SHARE]) is a
 * calibration constant, chosen so that grazer densities settle near Damuth's rule.
 */
class Ecology(private val sim: Simulation) {
    private val world = sim.world
    private val grid = world.grid
    private val n = grid.size
    private val days = world.config.daysPerStep
    private val rnd = Random(world.config.seed * 31 + 7)
    private val cellKm = grid.cellKm.toFloat()
    private val cellKm2 = cellKm * cellKm

    val plankton = Plankton(sim)
    val soil = SoilLife(sim)
    val small = SmallFauna(sim)

    /** Edible young plant growth, kg per km². */
    val palatable = FloatArray(n)
    /** Distance to drinkable fresh water, km. */
    val waterKm = FloatArray(n)

    val groups = ArrayList<AnimalGroup>()
    val policies = HashMap<Species, Policy>()
    private var nextId = 0
    private var stepCount = 0

    /** Running totals for the last step, for logs and tests. */
    var kills = 0; private set
    var births = 0; private set
    var deaths = 0; private set

    // Coarse scent grids: each species leaves scent where it goes; it fades within days.
    private val scentKm = 4f
    private val sw = ceil(grid.width * cellKm / scentKm).toInt()
    private val sh = ceil(grid.height * cellKm / scentKm).toInt()
    private val scent = HashMap<Species, FloatArray>()

    // Spatial buckets per species, rebuilt each day, for finding nearby groups.
    private val bucketKm = 8f
    private val bw = ceil(grid.width * cellKm / bucketKm).toInt()
    private val bh = ceil(grid.height * cellKm / bucketKm).toInt()
    private val buckets = HashMap<Species, Array<ArrayList<AnimalGroup>>>()

    init {
        for (s in Catalogue.all) {
            scent[s] = FloatArray(sw * sh)
            buckets[s] = Array(bw * bh) { ArrayList() }
            policies[s] = BaselinePolicy { rnd.nextFloat() - 0.5f }
        }
        for (i in 0 until n) if (sim.vegetation.isVegetated(i)) palatable[i] = palatableCap(i) * 0.7f
        updateWater()
        populate()
    }

    fun step() {
        kills = 0; births = 0; deaths = 0
        plankton.step()
        soil.step()
        small.step()
        regrow()
        if (stepCount++ % 5 == 0) updateWater()
        repeat(days) { day() }
        demography()
        groups.removeAll { !it.alive }
    }

    fun count(s: Species) = groups.filter { it.species == s }.sumOf { it.size }
    fun groupCount(s: Species) = groups.count { it.species == s }

    // ---------------------------------------------------------------- plants as food

    /** Palatable growth produced per day at [i], kg/km²: green, growing herbs and shrub shoots. */
    private fun palatableProduction(i: Int): Float {
        val v = sim.vegetation
        val t = sim.weather.temperature[i]
        val green = ((t - 2f) / 10f).coerceIn(0f, 1f) * (sim.water.soil[i] / 60f).coerceIn(0.1f, 1f)
        return PALATABLE_SHARE * 1e6f * (v.herb[i] + 0.3f * v.shrub[i]) * green / REGROWTH_DAYS
    }

    private fun palatableCap(i: Int) = PALATABLE_SHARE * 1e6f * (sim.vegetation.herb[i] + 0.3f * sim.vegetation.shrub[i])

    private fun regrow() {
        val v = sim.vegetation
        for (i in 0 until n) {
            if (!v.isVegetated(i)) { palatable[i] = 0f; continue }
            palatable[i] = max(0f, palatable[i] + days * (palatableProduction(i) - palatable[i] / REGROWTH_DAYS))
        }
    }

    // ---------------------------------------------------------------- water

    /** Fresh water: flowing rivers and creeks, open (not salt) lakes, floodwater. Distance by chamfer transform. */
    private fun updateWater() {
        val big = 1e6f
        for (i in 0 until n) {
            val lake = world.water.isLake(i)
            val fresh = !world.isSea[i] && ((lake && !world.water.lakeClosed[i]) || (!lake && sim.water.discharge[i] >= 0.1f) || sim.water.flood[i] > 0f)
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

    private fun suitable(s: Species, i: Int): Boolean {
        val v = sim.vegetation
        if (!v.isVegetated(i) || world.elevation[i] > 2000f) return false
        return when (s) {
            Catalogue.GAZELLE -> v.canopy(i) < 0.35f && v.herb[i] > 0.1f && !isWetland(i) && waterKm[i] < 15f
            else -> true
        }
    }

    private fun isWetland(i: Int) = world.biome[i].name.let { it == "MARSH" || it == "SWAMP_FOREST" || it == "BOG" || it == "SALT_MARSH" || it == "MANGROVE" }

    private fun populate() {
        val g = Catalogue.GAZELLE
        val density = 0.5f * Allometry.herbivoreDensityPerKm2(g.massKg)
        val perCell = density * cellKm2 / g.groupSize
        var preyKg = 0f
        for (i in 0 until n) {
            if (!suitable(g, i) || rnd.nextFloat() >= perCell) continue
            val group = spawn(g, i, g.groupSize)
            preyKg += group.biomassKg
        }
        val w = Catalogue.WOLF
        val packs = (PREDATOR_PER_PREY_KG * preyKg / w.massKg / w.groupSize).toInt()
        val herds = groups.filter { it.species == g }
        repeat(min(packs, herds.size)) {
            val h = herds[rnd.nextInt(herds.size)]
            spawn(w, grid.index(h.x.toInt(), h.y.toInt()), w.groupSize)
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
        for ((_, f) in scent) for (k in f.indices) f[k] *= 0.5f
        for ((_, b) in buckets) for (l in b) l.clear()
        for (g in groups) if (g.alive) {
            scent.getValue(g.species)[scentIndex(g.x, g.y)] += g.size.toFloat()
            buckets.getValue(g.species)[bucketIndex(g.x, g.y)] += g
        }
        // Random order each day so no group always moves first.
        groups.shuffle(rnd)
        for (g in groups) {
            if (!g.alive) continue
            move(g)
            if (g.species.carnivore) hunt(g, contested = meet(g)) else graze(g)
            drink(g)
            if (g.alert > 0) g.alert--
        }
    }

    /** The nearest live group of species [s] to (x, y) within [km], and its distance. */
    private fun nearest(s: Species, x: Double, y: Double, km: Float, except: AnimalGroup? = null): Pair<AnimalGroup?, Float> {
        val b = buckets.getValue(s)
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

    /** Collects into [out] the live groups of species [s] within [km] of (x, y). */
    private fun within(s: Species, x: Double, y: Double, km: Float, out: ArrayList<AnimalGroup>) {
        out.clear()
        val b = buckets.getValue(s)
        val bx = (x * cellKm / bucketKm).toInt(); val by = (y * cellKm / bucketKm).toInt()
        val r = ceil(km / bucketKm).toInt()
        val lim = km / cellKm
        for (yy in max(0, by - r)..min(bh - 1, by + r)) for (xx in max(0, bx - r)..min(bw - 1, bx + r))
            for (o in b[yy * bw + xx]) if (o.alive && dist(o.x - x, o.y - y) < lim) out += o
    }

    private fun scentAt(s: Species, x: Double, y: Double, norm: Float): Float {
        val v = scent.getValue(s)[scentIndex(x, y)]
        return v / (v + norm)
    }

    private fun move(g: AnimalGroup) {
        val s = g.species
        val carnivore = s.carnivore
        val predator = Catalogue.WOLF
        val prey = Catalogue.GAZELLE
        // Others the group can perceive around today's possible destinations, gathered once.
        val other = if (carnivore) prey else predator
        val radius = if (carnivore) s.senseKm else 3f
        val threatened = !carnivore && nearest(predator, g.x, g.y, 2f * s.flightKm).first != null
        val reach = if (threatened) s.maxDailyTravelKm / 3f else s.dailyTravelKm
        within(other, g.x, g.y, reach + radius, around)
        val options = ArrayList<Option>(9)
        val tx = DoubleArray(9); val ty = DoubleArray(9)
        for (k in -1 until 8) {
            val x = if (k < 0) g.x else g.x + COS[k] * reach / cellKm
            val y = if (k < 0) g.y else g.y + SIN[k] * reach / cellKm
            tx[k + 1] = x; ty[k + 1] = y
            val xi = x.toInt(); val yi = y.toInt()
            val cell = if (x >= 0 && y >= 0 && grid.inBounds(xi, yi) && sim.vegetation.isVegetated(grid.index(xi, yi))) grid.index(xi, yi) else -1
            if (cell < 0) { options += Option(-1, 0f, 0f, 0f, 0f, 0f, 0f, 0f); continue }
            val km = if (k < 0) 0f else reach
            val forage = if (carnivore) small.rodents[cell] else palatable[cell]
            var near = radius
            for (o in around) near = min(near, (dist(o.x - x, o.y - y) * cellKm).toFloat())
            val seen = 1f - near / radius
            val danger = if (carnivore) 0f else max(scentAt(predator, x, y, 6f), seen)
            val preySig = if (!carnivore) 0f else max(scentAt(prey, x, y, 30f), seen)
            val kin = scentAt(s, x, y, 3f * s.groupSize) * if (k < 0) 0.5f else 1f // own scent counts less where it stands
            options += Option(cell, km, forage, danger, preySig, kin, waterKm[cell], sim.weather.snowpack[cell])
        }
        val choice = policies.getValue(s).choose(g, options)
        if (options[choice].cell < 0) return
        val from = cellOf(g)
        g.x = tx[choice]; g.y = ty[choice]
        // Trampling along the way: a herd crossing a cell beats it down and, repeated, wears a trail.
        val to = cellOf(g)
        val steps = max(1, (options[choice].km / cellKm).toInt())
        for (t in 0..steps) {
            val xi = grid.x(from) + (grid.x(to) - grid.x(from)) * t / steps
            val yi = grid.y(from) + (grid.y(to) - grid.y(from)) * t / steps
            sim.vegetation.trample(grid.index(xi, yi), g.biomassKg / TRAMPLE_KG)
        }
    }

    private fun graze(g: AnimalGroup) {
        val s = g.species
        val c = cellOf(g)
        var need = g.dailyNeedKg
        val want = need
        val snowBlocked = sim.weather.snowpack[c] > s.snowLimitMm
        if (!snowBlocked) {
            // Feed over the 3 x 3 cells around: palatable kg/km² times cell area.
            val cx = grid.x(c); val cy = grid.y(c)
            var available = 0f
            for (dy in -1..1) for (dx in -1..1) if (grid.inBounds(cx + dx, cy + dy)) available += palatable[grid.index(cx + dx, cy + dy)] * cellKm2
            if (available > 0f) {
                val share = min(1f, need / available)
                for (dy in -1..1) for (dx in -1..1) {
                    if (!grid.inBounds(cx + dx, cy + dy)) continue
                    val i = grid.index(cx + dx, cy + dy)
                    val eat = palatable[i] * share
                    palatable[i] -= eat
                    val kgPerM2 = (eat * cellKm2 / grid.cellAreaM2).toFloat()
                    sim.vegetation.consume(i, LifeForm.HERB, kgPerM2 * s.diet.grass / (s.diet.grass + s.diet.browse))
                    sim.vegetation.consume(i, LifeForm.SHRUB, kgPerM2 * s.diet.browse / (s.diet.grass + s.diet.browse))
                    need -= eat * cellKm2
                }
            }
        }
        feed(g, 1f - max(0f, need) / want)
    }

    private fun hunt(g: AnimalGroup, contested: Boolean) {
        val s = g.species
        val c = cellOf(g)
        val need = g.dailyNeedKg
        // A hungry pack tests the herds it can reach today, up to four chases. Healthy adult
        // gazelles usually outrun wolves; the young and the weak are caught far more often, and a
        // big herd spots the pack sooner.
        var attempts = 0
        while (!contested && g.stored < need && g.adults > 0 && attempts++ < 4) {
            val (prey, _) = nearest(Catalogue.GAZELLE, g.x, g.y, HUNT_KM)
            if (prey == null) break
            val pack = min(1f, g.adults / 2f)
            val eyes = 1f - 0.3f * min(1f, prey.size / 40f)
            val wary = if (prey.alert > 0) 0.3f else 1f
            val young = prey.young > 0 && rnd.nextFloat() < 2f * prey.young / (prey.size + prey.young)
            val p = pack * eyes * wary * if (young) 0.4f else 0.15f + 0.5f * (1f - prey.condition)
            // Caught or not, the herd bolts and stays watchful for days.
            prey.alert = ALERT_DAYS
            bolt(prey, g)
            if (rnd.nextFloat() >= p) continue
            if (young) { prey.young--; g.stored += 0.5f * prey.species.massKg } else { prey.adults--; g.stored += prey.species.massKg }
            kills++
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
        feed(g, eaten / need)
        if (g.stored > 0f) g.stored *= 0.9f // spoilage and theft
    }

    /**
     * Packs defend their ground: when two meet, they spend the day in threat and chase instead of
     * hunting, the smaller pack is usually driven off, and now and then a wolf is killed. Crowded
     * packs therefore hunt less each; in studied wolf populations strife is a leading cause of death.
     * Returns whether the pack met another.
     */
    private fun meet(g: AnimalGroup): Boolean {
        val (other, _) = nearest(g.species, g.x, g.y, 3f, except = g)
        if (other == null) return false
        val loser = if (rnd.nextFloat() < g.adults / (g.adults + other.adults + 1e-3f)) other else g
        // Most meetings end in a chase; a few in a death.
        if (loser.adults > 0 && rnd.nextFloat() < 0.05f) {
            loser.adults--; deaths++
            soil.carrion[cellOf(loser)] += (loser.species.massKg / grid.cellAreaM2).toFloat()
        }
        val winner = if (loser === g) other else g
        val dx = loser.x - winner.x; val dy = loser.y - winner.y
        val d = max(1e-3, dist(dx, dy))
        val flee = loser.species.dailyTravelKm / cellKm
        val nx = loser.x + dx / d * flee; val ny = loser.y + dy / d * flee
        if (grid.inBounds(nx.toInt(), ny.toInt()) && nx >= 0 && ny >= 0 && sim.vegetation.isVegetated(grid.index(nx.toInt(), ny.toInt()))) { loser.x = nx; loser.y = ny }
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
        g.condition = if (ratio >= 0.9f) min(1f, g.condition + 0.01f) else max(0f, g.condition - 0.02f * (1f - ratio))
    }

    private fun drink(g: AnimalGroup) {
        if (waterKm[cellOf(g)] <= 1.5f) g.daysWithoutWater = 0
        else if (++g.daysWithoutWater > g.species.daysWithoutWater) g.condition = max(0f, g.condition - 0.05f)
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
                val born = if (s == Catalogue.WOLF) {
                    if (firstStepOfMonth && date.month == s.birthMonth && g.adults >= 2) (4f * fit + rnd.nextFloat()).toInt() else 0
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
            val pYoung = min(1f, pAdult + s.backgroundMortality * years + 0.1f * hunger)
            val deadA = binomial(g.adults, pAdult)
            val deadY = binomial(g.young, pYoung)
            // A pack or herd without adults does not survive.
            g.adults -= deadA
            val orphaned = if (g.adults == 0) g.young - deadY else 0
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
                newcomers += child
            }
        }
        groups += newcomers
        // Joining: small remnants merge into a nearby group of their kind.
        for (s in Catalogue.all) {
            val b = buckets.getValue(s)
            for (l in b) l.clear()
            for (g in groups) if (g.species == s && g.alive) b[bucketIndex(g.x, g.y)] += g
            for (g in groups) {
                if (g.species != s || !g.alive || g.size >= s.groupSize / 3) continue
                val (other, _) = nearest(s, g.x, g.y, 5f, except = g)
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
        /** Predator biomass per kg of prey biomass at the start (about 1:100). */
        const val PREDATOR_PER_PREY_KG = 0.004f
        /** Distance at which a pack can stalk and chase a herd it has found, km. */
        const val HUNT_KM = 4f
        /** Animal mass whose passage counts as trampling intensity 1 (see Vegetation.trample). */
        const val TRAMPLE_KG = 150_000f
        private val COS = DoubleArray(8) { kotlin.math.cos(it * Math.PI / 4) }
        private val SIN = DoubleArray(8) { kotlin.math.sin(it * Math.PI / 4) }
        /** Days a herd stays watchful after an attack. */
        const val ALERT_DAYS = 5
    }
}
