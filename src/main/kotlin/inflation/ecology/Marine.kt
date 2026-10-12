package inflation.ecology

import inflation.world.Date
import inflation.world.Simulation
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/** A sea animal as data: what it eats, how it lives, how it breeds. */
class SeaSpecies(
    val name: String,
    /** Adult body mass, kg. */
    val massKg: Float,
    /** What it eats: krill and other zooplankton, fish, or other sea animals (and fish). */
    val food: SeaFood,
    /** Food per adult per day as a share of body mass. */
    val dailyShare: Float,
    val groupSize: Int,
    val maxGroupSize: Int,
    /** Distance it covers in a day, km. */
    val dailyKm: Float,
    /** Stays within this distance of the shore, km (seals haul out, porpoises keep to the shelf). */
    val maxShoreKm: Float = Float.POSITIVE_INFINITY,
    val birthMonth: Int,
    val birthMonths: Int,
    /** Young per adult female per year in good condition. */
    val youngPerFemale: Float,
    val yearsToMaturity: Float,
    val backgroundMortality: Float,
    /** How well adults escape or fend off sharks, 0..1. */
    val defence: Float = 0f,
    /** Seasonal range memory: follows the richest feeding of each month (whales). */
    val migrates: Boolean = false,
) {
    var index = -1
        internal set
}

enum class SeaFood { PLANKTON, FISH, PREY }

object SeaCatalogue {
    /** Fin whale: a baleen whale straining krill and small fish; follows the blooms across the open sea. */
    val FIN_WHALE = SeaSpecies("fin whale", massKg = 50_000f, food = SeaFood.PLANKTON, dailyShare = 0.015f,
        groupSize = 2, maxGroupSize = 6, dailyKm = 100f, birthMonth = 9, birthMonths = 2,
        youngPerFemale = 0.4f, yearsToMaturity = 8f, backgroundMortality = 0.03f, defence = 1f, migrates = true)
    /** Common dolphin: big, fast pods hunting shoals of small fish. */
    val DOLPHIN = SeaSpecies("dolphin", massKg = 80f, food = SeaFood.FISH, dailyShare = 0.05f,
        groupSize = 30, maxGroupSize = 150, dailyKm = 60f, birthMonth = 3, birthMonths = 3,
        youngPerFemale = 0.4f, yearsToMaturity = 6f, backgroundMortality = 0.06f, defence = 0.7f)
    /** Harbour porpoise: small, shy, in twos and threes over the shelf. */
    val PORPOISE = SeaSpecies("porpoise", massKg = 55f, food = SeaFood.FISH, dailyShare = 0.08f,
        groupSize = 3, maxGroupSize = 10, dailyKm = 30f, maxShoreKm = 25f, birthMonth = 3, birthMonths = 2,
        youngPerFemale = 0.8f, yearsToMaturity = 4f, backgroundMortality = 0.12f, defence = 0.4f)
    /** Monk seal: fishes along rocky coasts and hauls out to rest and pup in sea caves and quiet beaches. */
    val SEAL = SeaSpecies("monk seal", massKg = 300f, food = SeaFood.FISH, dailyShare = 0.05f,
        groupSize = 8, maxGroupSize = 30, dailyKm = 20f, maxShoreKm = 12f, birthMonth = 6, birthMonths = 3,
        youngPerFemale = 0.5f, yearsToMaturity = 4f, backgroundMortality = 0.07f, defence = 0.3f)
    /** A large shark (white shark and kin): hunts seals, dolphins and big fish. */
    val SHARK = SeaSpecies("shark", massKg = 800f, food = SeaFood.PREY, dailyShare = 0.01f,
        groupSize = 1, maxGroupSize = 3, dailyKm = 60f, birthMonth = 4, birthMonths = 2,
        youngPerFemale = 0.6f, yearsToMaturity = 12f, backgroundMortality = 0.05f, defence = 1f)

    val all = listOf(FIN_WHALE, DOLPHIN, PORPOISE, SEAL, SHARK)
    /** What sharks hunt. */
    val sharkPrey = listOf(DOLPHIN, PORPOISE, SEAL)

    init { all.forEachIndexed { i, s -> s.index = i } }
}

/** A pod, school or colony at sea: a centre and the range it roams in a week, like a herd on land. */
class SeaGroup(val id: Int, val species: SeaSpecies, var x: Double, var y: Double, var adults: Int, var young: Int) {
    var condition = 0.8f
    var rangeKm = 10f
    /** Remembered best place per month, fine cells; NaN where unknown. */
    val seasonX = FloatArray(12) { Float.NaN }
    val seasonY = FloatArray(12) { Float.NaN }
    val seasonQ = FloatArray(12)
    var lastIntake = 1f
    val size get() = adults + young
    val alive get() = size > 0
    val massKg get() = species.massKg * (adults + 0.5f * young)
}

/**
 * Life of the open sea and the coasts: where the plankton blooms, and the animals that follow it.
 *
 * - **Upwelling.** Wind drags the surface water, and the earth's turning bends that drift to the
 *   right of the wind (Ekman transport). Where it carries water away from a coast, cold water rich
 *   in nutrients wells up from below, and the plankton blooms. Westerlies blow most of the year
 *   (drift to the south); in summer steady north winds take over (drift to the west), as the
 *   Etesians do in the eastern Mediterranean. Shelf edges, where the sea floor drops away, stir up
 *   nutrients as well. The plankton's nutrient supply is raised there each month.
 * - **Sea animals** as roaming groups, weekly like those on land: fin whales follow the krill and
 *   remember where it was rich each month; dolphin pods and porpoises hunt fish; monk seals fish
 *   along rocky coasts and haul out on shore; sharks hunt seals, dolphins and porpoises where their
 *   ranges overlap.
 * - **Sea birds** in colonies on cliffs and islets, foraging over the sea within a day's flight.
 */
class Marine(private val sim: Simulation, private val plankton: Plankton, private val fish: Fish) {
    private val world = sim.world
    private val grid = world.grid
    private val n = grid.size
    private val cellKm = grid.cellKm.toFloat()
    private val days = world.config.daysPerStep
    private val rnd = Random(world.config.seed * 977 + 13)

    /** Distance from each sea cell to the nearest land, km (large in the open sea). */
    val shoreKm = FloatArray(n)
    /** Upwelling strength under the westerlies and under summer north winds, 0..1. */
    private val upWest = FloatArray(n)
    private val upNorth = FloatArray(n)
    /** Shelf edges, where the sea floor drops steeply, 0..1. */
    private val shelfEdge = FloatArray(n)
    /** Nutrient supply before upwelling. */
    private val baseSupply = plankton.supply.copyOf()
    /** Upwelling feeding the plankton this month, 0..1. */
    val upwelling = FloatArray(n)

    val groups = ArrayList<SeaGroup>()
    private var nextId = 0
    private var stepCount = 0

    /** Sea-bird colonies: cell and number of breeding birds. */
    val colonyCell = ArrayList<Int>()
    val colonyBirds = ArrayList<Float>()

    /** Deaths by "species:cause" and shark kills since last cleared, for reports. */
    val deathsBy = HashMap<String, Int>()
    var sharkKills = 0

    // Coarse grid for the sea animals' ranges.
    private val maps = CoarseMaps(grid.width, grid.height, cellKm, COARSE_KM)
    private val mSea = maps.layer()
    private val mZoo = maps.layer()
    private val mFish = maps.layer()
    private val mShore = maps.layer()
    private val mOcc = Array(SeaCatalogue.all.size) { maps.layer() }

    init {
        shoreAndUpwelling()
        upwell()
        populate()
        findColonies()
    }

    fun step() {
        stepCount++
        upwell()
        summarise()
        val r = rnd
        // Each group chooses from the same summary and writes only its own place: in parallel.
        groups.parallelStream().forEach { g -> if (g.alive) move(g) }
        summarise()
        keepZoo.fill(1f); keepFish.fill(1f)
        for (g in groups) if (g.alive && g.species.food != SeaFood.PREY) feed(g)
        for (g in groups.filter { it.alive && it.species == SeaCatalogue.SHARK }.shuffled(r)) hunt(g)
        eatFromCells()
        for (g in groups) if (g.alive) learn(g)
        birds()
        demography()
        groups.removeAll { !it.alive }
    }

    fun count(s: SeaSpecies) = groups.filter { it.species == s }.sumOf { it.size }
    fun birdCount() = colonyBirds.sum()

    // ---------------------------------------------------------------- the sea itself

    private fun shoreAndUpwelling() {
        val big = 1e6f
        for (i in 0 until n) shoreKm[i] = if (world.isSea[i]) big else 0f
        val d1 = cellKm; val d2 = cellKm * 1.4142f
        val w = grid.width; val h = grid.height
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x; var d = shoreKm[i]
            if (x > 0) d = min(d, shoreKm[i - 1] + d1)
            if (y > 0) { d = min(d, shoreKm[i - w] + d1); if (x > 0) d = min(d, shoreKm[i - w - 1] + d2); if (x < w - 1) d = min(d, shoreKm[i - w + 1] + d2) }
            shoreKm[i] = d
        }
        for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
            val i = y * w + x; var d = shoreKm[i]
            if (x < w - 1) d = min(d, shoreKm[i + 1] + d1)
            if (y < h - 1) { d = min(d, shoreKm[i + w] + d1); if (x < w - 1) d = min(d, shoreKm[i + w + 1] + d2); if (x > 0) d = min(d, shoreKm[i + w - 1] + d2) }
            shoreKm[i] = d
        }
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            if (!world.isSea[i]) continue
            // Offshore direction: up the slope of distance from shore (y grows southward).
            val gx = shoreKm[i + 1] - shoreKm[i - 1]; val gy = shoreKm[i + w] - shoreKm[i - w]
            val len = hypot(gx, gy)
            if (len > 1e-3f && shoreKm[i] < UPWELL_KM * 3) {
                val ox = gx / len; val oy = gy / len
                val near = exp(-shoreKm[i] / UPWELL_KM)
                // Northern hemisphere: the drift runs 90° right of the wind. Westerlies (blowing
                // east) drift water south (+y); north winds (blowing south) drift it west (-x).
                upWest[i] = max(0f, oy) * near
                upNorth[i] = max(0f, -ox) * near
            }
            // Shelf edge: the sea floor falling away steeply between about 100 and 600 m.
            val depth = -world.elevation[i]
            if (depth in 100f..600f) {
                val slope = hypot(world.elevation[i + 1] - world.elevation[i - 1], world.elevation[i + w] - world.elevation[i - w]) / (2 * cellKm * 1000f)
                shelfEdge[i] = min(1f, slope / 0.03f)
            }
        }
    }

    /** Raises the plankton's nutrient supply where the season's wind and the shelf edges bring deep water up. */
    private fun upwell() {
        val m = sim.weather.date.month
        // Summer (the 4th to 6th months from the spring equinox): steady north winds; otherwise westerlies.
        val summer = m in 3..5
        java.util.stream.IntStream.range(0, n).parallel().forEach { i ->
            if (!world.isSea[i]) return@forEach
            val u = min(1f, (if (summer) upNorth[i] + 0.3f * upWest[i] else upWest[i]) + 0.5f * shelfEdge[i])
            upwelling[i] = u
            plankton.supply[i] = min(1f, baseSupply[i] + UPWELL_SUPPLY * u)
        }
    }

    // ---------------------------------------------------------------- populations

    /** Where [s] can live: sea, deep enough, near enough the shore for coastal kinds. */
    private fun habitable(s: SeaSpecies, i: Int) =
        world.isSea[i] && shoreKm[i] <= s.maxShoreKm && (s.maxShoreKm.isFinite() || shoreKm[i] > 5f)

    private fun populate() {
        val sea = (0 until n).filter { world.isSea[it] }
        if (sea.isEmpty()) return
        for (s in SeaCatalogue.all) {
            val area = sea.count { habitable(s, it) } * cellKm * cellKm
            val animals = area / START_KM2.getValue(s.name)
            val groupsWanted = (animals / s.groupSize).toInt()
            val cand = sea.filter { habitable(s, it) }
            if (cand.isEmpty()) continue
            repeat(groupsWanted) {
                // Start where the water is richest, more or less.
                var best = cand[rnd.nextInt(cand.size)]
                repeat(3) { val c = cand[rnd.nextInt(cand.size)]; if (plankton.supply[c] > plankton.supply[best]) best = c }
                val young = (s.groupSize * 0.25f).toInt()
                groups += SeaGroup(nextId++, s, grid.x(best) + rnd.nextDouble(), grid.y(best) + rnd.nextDouble(), max(1, s.groupSize - young), young)
            }
        }
    }

    private fun findColonies() {
        // Cliffs and rocky islets facing the sea: land cells next to the sea, steep, about one colony per stretch of coast.
        val spacing = max(1, (COLONY_SPACING_KM / cellKm).toInt())
        for (i in 0 until n) {
            if (world.isSea[i] || world.water.isLake(i)) continue
            val x = grid.x(i); val y = grid.y(i)
            if (x % spacing != 0 || y % spacing != 0) continue
            // Look for a steep coastal cell in this block.
            var best = -1; var bestRug = 0.25f
            for (dy in 0 until spacing) for (dx in 0 until spacing) {
                val xx = x + dx; val yy = y + dy
                if (!grid.inBounds(xx, yy)) continue
                val c = grid.index(xx, yy)
                if (world.isSea[c] || !nextToSea(c)) continue
                val rug = world.terrain.ruggedness[c]
                if (rug > bestRug) { bestRug = rug; best = c }
            }
            if (best >= 0) { colonyCell += best; colonyBirds += 2000f * bestRug }
        }
    }

    private fun nextToSea(c: Int): Boolean {
        val x = grid.x(c); val y = grid.y(c)
        for (dy in -1..1) for (dx in -1..1) if (grid.inBounds(x + dx, y + dy) && world.isSea[grid.index(x + dx, y + dy)]) return true
        return false
    }

    // ---------------------------------------------------------------- summaries

    private fun summarise() {
        for (l in listOf(mSea, mZoo, mFish, mShore)) l.clear()
        // Each band of coarse rows covers its own fine rows, so bands are summed in parallel.
        java.util.stream.IntStream.range(0, maps.h).parallel().forEach { band ->
            for (fy in band * maps.ratio until min(grid.height, (band + 1) * maps.ratio)) for (fx in 0 until grid.width) {
                val i = grid.index(fx, fy)
                if (!world.isSea[i]) continue
                val m = maps.of(fx, fy)
                mSea.cell[m] += 1f
                mZoo.cell[m] += plankton.zoo[i] * cellKm * cellKm * 1000f // g/m² = t/km² -> kg
                mFish.cell[m] += fish.fish[i] * cellKm * cellKm
                mShore.cell[m] += min(shoreKm[i], 200f)
            }
        }
        listOf(mSea, mZoo, mFish, mShore).parallelStream().forEach { it.build() }
        // Each species on its own layer, groups added in list order: species in parallel.
        java.util.stream.IntStream.range(0, SeaCatalogue.all.size).parallel().forEach { si ->
            val layer = mOcc[si]
            layer.clear()
            val box = CoarseMaps.Box()
            for (g in groups) {
                if (!g.alive || g.species.index != si) continue
                box.set(maps, g.x, g.y, g.rangeKm)
                val per = g.massKg / box.cells
                for (y in box.y0 until box.y1) for (x in box.x0 until box.x1) layer.cell[y * maps.w + x] += per
            }
            layer.build()
        }
    }

    // ---------------------------------------------------------------- movement

    private fun cellOf(g: SeaGroup) = grid.index(g.x.toInt().coerceIn(0, grid.width - 1), g.y.toInt().coerceIn(0, grid.height - 1))

    /** Moves [g]'s range to the best of its candidates: food, safety from sharks, room from its own kind, the season's place. */
    private fun move(g: SeaGroup) {
        val s = g.species
        g.rangeKm = RANGE_FACTOR * s.dailyKm
        var bestX = g.x; var bestY = g.y; var best = Float.NEGATIVE_INFINITY
        val box = CoarseMaps.Box()
        val m = sim.weather.date.month
        for (ring in 0..2) {
            val d = when (ring) { 0 -> 0f; 1 -> g.rangeKm; else -> 3f * g.rangeKm }
            for (dir in 0 until if (ring == 0) 1 else 8) {
                val a = dir * Math.PI / 4
                val x = g.x + kotlin.math.cos(a) * d / cellKm; val y = g.y + kotlin.math.sin(a) * d / cellKm
                val v = score(g, x, y, d, box)
                if (v > best) { best = v; bestX = x; bestY = y }
            }
        }
        // Whales head for the place they remember feeding best this month.
        if (s.migrates && !g.seasonX[m].isNaN()) {
            val dx = g.seasonX[m] - g.x; val dy = g.seasonY[m] - g.y
            val dist = hypot(dx, dy) * cellKm
            if (dist > g.rangeKm) {
                val step = min(1.0, (s.dailyKm * days * 0.7 / dist))
                val x = g.x + dx * step; val y = g.y + dy * step
                val v = score(g, x, y, (dist * step).toFloat(), box) + 0.5f * (g.seasonQ[m] - g.lastIntake).coerceIn(0f, 1f)
                if (v > best) { best = v; bestX = x; bestY = y }
            }
        }
        g.x = bestX; g.y = bestY
    }

    private fun score(g: SeaGroup, x: Double, y: Double, d: Float, box: CoarseMaps.Box): Float {
        val s = g.species
        if (x < 0 || y < 0 || !grid.inBounds(x.toInt(), y.toInt())) return Float.NEGATIVE_INFINITY
        val c = grid.index(x.toInt(), y.toInt())
        if (!habitable(s, c)) return Float.NEGATIVE_INFINITY
        box.set(maps, x, y, g.rangeKm)
        val sea = mSea.box(box.x0, box.y0, box.x1, box.y1).toFloat()
        if (sea < 1f) return Float.NEGATIVE_INFINITY
        val km2 = sea * cellKm * cellKm
        val need = s.dailyShare * g.massKg * days
        val food = when (s.food) {
            SeaFood.PLANKTON -> mZoo.box(box.x0, box.y0, box.x1, box.y1).toFloat()
            SeaFood.FISH -> mFish.box(box.x0, box.y0, box.x1, box.y1).toFloat()
            SeaFood.PREY -> SeaCatalogue.sharkPrey.sumOf { mOcc[it.index].box(box.x0, box.y0, box.x1, box.y1) }.toFloat() +
                0.3f * mFish.box(box.x0, box.y0, box.x1, box.y1).toFloat()
        } * FOOD_REACHABLE
        val plenty = food / (food + 20f * need)
        val shark = if (s == SeaCatalogue.SHARK) 0f else (mOcc[SeaCatalogue.SHARK.index].box(box.x0, box.y0, box.x1, box.y1).toFloat() / km2)
        val danger = shark / (shark + SHARK_KG_KM2) * (1f - s.defence)
        val own = if (d == 0f) g.massKg else 0f
        val kin = max(0f, mOcc[s.index].box(box.x0, box.y0, box.x1, box.y1).toFloat() - own) / km2
        val crowd = kin / (kin + g.massKg / (3.14f * g.rangeKm * g.rangeKm))
        return plenty - 2f * danger - 0.3f * crowd - 0.05f * d / (s.dailyKm * days) + 0.02f * noise(g.id, x, y)
    }

    private fun noise(id: Int, x: Double, y: Double): Float {
        var h = id * 0x9E3779B1L + stepCount * 0x85EBCA6BL + (x * 13).toLong() * 31 + (y * 7).toLong()
        h = h xor (h ushr 17); h *= 0xED5AD4BBL; h = h xor (h ushr 11)
        return ((h and 0xFFFF).toFloat() / 0xFFFF) - 0.5f
    }

    // ---------------------------------------------------------------- feeding

    /** Eats a week's food from the water of its range, as far as it holds out. */
    private fun feed(g: SeaGroup) {
        val s = g.species
        val need = s.dailyShare * g.massKg * days
        val box = CoarseMaps.Box().also { it.set(maps, g.x, g.y, g.rangeKm) }
        val layer = if (s.food == SeaFood.PLANKTON) mZoo else mFish
        val have = layer.box(box.x0, box.y0, box.x1, box.y1).toFloat() * FOOD_REACHABLE
        val got = min(need, have)
        val f = if (have > 0f) got / (have / FOOD_REACHABLE) else 0f
        if (f > 0f) take(box, s.food, f)
        nourish(g, if (need > 0f) got / need else 1f)
    }

    /** Share of each coarse cell's plankton and fish left after this week's feeding, applied by [eatFromCells]. */
    private val keepZoo = FloatArray(maps.size)
    private val keepFish = FloatArray(maps.size)

    /** Takes share [f] of the food of [kind] from every sea cell under [box]. */
    private fun take(box: CoarseMaps.Box, kind: SeaFood, f: Float) {
        val keep = if (kind == SeaFood.PLANKTON) keepZoo else keepFish
        for (cy in box.y0 until box.y1) for (cx in box.x0 until box.x1) keep[cy * maps.w + cx] *= (1f - f)
    }

    /** Takes what the week's feeding ate from the sea cells, all at once and in parallel. */
    private fun eatFromCells() {
        java.util.stream.IntStream.range(0, n).parallel().forEach { i ->
            if (!world.isSea[i]) return@forEach
            val m = maps.of(grid.x(i), grid.y(i))
            if (keepZoo[m] < 1f) plankton.zoo[i] = max(0.005f, plankton.zoo[i] * keepZoo[m])
            if (keepFish[m] < 1f) fish.fish[i] *= keepFish[m]
        }
    }

    private fun nourish(g: SeaGroup, ratio: Float) {
        g.lastIntake = ratio
        g.condition = if (ratio >= 0.9f) min(1f, g.condition + 0.06f) else max(0f, g.condition - 0.07f * (1f - ratio))
    }

    /** Sharks take seals, porpoises and dolphins whose ranges overlap theirs, then fish. */
    private fun hunt(g: SeaGroup) {
        val s = g.species
        var need = s.dailyShare * g.massKg * days
        var got = 0f
        val r = Random(world.config.seed * 31 + stepCount * 7919L + g.id * 104729L)
        val near = groups.filter { it.alive && it.species in SeaCatalogue.sharkPrey && abs(it.x - g.x) * cellKm < g.rangeKm + it.rangeKm && abs(it.y - g.y) * cellKm < g.rangeKm + it.rangeKm }
        for (o in near.shuffled(r)) {
            if (got >= need) break
            val d = (hypot(o.x - g.x, o.y - g.y) * cellKm).toFloat()
            val ov = overlap(d, g.rangeKm, o.rangeKm)
            if (ov <= 0f || r.nextFloat() >= SHARK_ATTACK * ov) continue
            // Young and weak are easier; a pod defends itself.
            val young = o.young > 0 && r.nextFloat() < 0.5f
            val p = (if (young) 0.5f else 0.25f * (1.5f - o.condition)) * (1f - o.species.defence * if (young) 0.5f else 1f)
            if (r.nextFloat() >= p) continue
            if (young) o.young-- else o.adults--
            got += if (young) 0.3f * o.species.massKg else o.species.massKg
            sharkKills++
            deathsBy.merge("${o.species.name}:shark", 1, Int::plus)
        }
        if (got < need) {
            // Big fish make up the rest.
            val box = CoarseMaps.Box().also { it.set(maps, g.x, g.y, g.rangeKm) }
            val have = mFish.box(box.x0, box.y0, box.x1, box.y1).toFloat() * FOOD_REACHABLE
            val more = min(need - got, have * 0.3f)
            if (have > 0f) take(box, SeaFood.FISH, more / (have / FOOD_REACHABLE))
            got += more
        }
        nourish(g, if (need > 0f) min(1f, got / need) else 1f)
    }

    /** Whales remember where they fed best each month. */
    private fun learn(g: SeaGroup) {
        if (!g.species.migrates) return
        val m = sim.weather.date.month
        val q = g.lastIntake
        if (g.seasonX[m].isNaN() || q > g.seasonQ[m] + 0.05f) { g.seasonX[m] = g.x.toFloat(); g.seasonY[m] = g.y.toFloat(); g.seasonQ[m] = q }
        else if (hypot(g.x - g.seasonX[m], g.y - g.seasonY[m]) * cellKm < g.rangeKm) g.seasonQ[m] += 0.3f * (q - g.seasonQ[m])
    }

    // ---------------------------------------------------------------- birds

    /**
     * Each colony's birds fish the sea within a day's flight of the nest. They breed in spring as
     * the fish allow, and starve back in lean years.
     */
    private fun birds() {
        val years = days / Date.DAYS_PER_YEAR.toFloat()
        val spring = sim.weather.date.month in 0..2
        val box = CoarseMaps.Box()
        for (k in colonyCell.indices) {
            val c = colonyCell[k]
            box.set(maps, grid.x(c).toDouble(), grid.y(c).toDouble(), BIRD_FORAGE_KM)
            val have = mFish.box(box.x0, box.y0, box.x1, box.y1).toFloat() * FOOD_REACHABLE
            val birds = colonyBirds[k]
            val need = birds * BIRD_KG_DAY * days
            val got = min(need, have * 0.5f)
            if (got > 0f && have > 0f) take(box, SeaFood.FISH, got / (have / FOOD_REACHABLE))
            val ratio = if (need > 0f) got / need else 1f
            var b = birds
            // Chicks fledged over the spring, fewer as the colony nears what its waters can feed.
            val cap = have / FOOD_REACHABLE * BIRD_SHARE_OF_FISH / (BIRD_KG_DAY * Date.DAYS_PER_YEAR)
            if (spring) b += birds * 0.5f * ratio * years * 12f / 3f * max(0f, 1f - birds / max(1f, cap))
            b -= birds * (0.15f * years + 0.5f * years * (1f - ratio))
            colonyBirds[k] = b.coerceIn(10f, 200_000f)
        }
    }

    // ---------------------------------------------------------------- births and deaths

    private fun demography() {
        val years = days / Date.DAYS_PER_YEAR.toFloat()
        val date = sim.weather.date
        val newcomers = ArrayList<SeaGroup>()
        for (g in groups) {
            if (!g.alive) continue
            val s = g.species
            val r = Random(world.config.seed * 17 + stepCount * 7919L + g.id * 104729L)
            val fit = ((g.condition - 0.3f) / 0.4f).coerceIn(0f, 1f)
            if ((date.month - s.birthMonth + 12) % 12 < s.birthMonths) {
                val steps = s.birthMonths * Date.DAYS_PER_MONTH / days.toFloat()
                val born = binomial(g.adults, 0.5f * s.youngPerFemale * fit / steps, r)
                g.young += born
            }
            val grown = binomial(g.young, years / s.yearsToMaturity, r)
            g.young -= grown; g.adults += grown
            // Crowded waters breed parasites and disease, as on land.
            val crowd = crowding(g)
            val pAdult = s.backgroundMortality * years + (if (g.condition < 0.2f) 0.1f else 0f) + SEA_DISEASE * years * max(0f, crowd - 1f)
            val pYoung = min(1f, 2f * pAdult + if (g.condition < 0.4f) 0.05f else 0f)
            val deadA = binomial(g.adults, pAdult, r); val deadY = binomial(g.young, pYoung, r)
            if (deadA + deadY > 0) deathsBy.merge("${s.name}:${if (g.condition < 0.2f) "hunger" else if (crowd > 1.2f) "disease" else "other"}", deadA + deadY, Int::plus)
            g.adults -= deadA; g.young -= deadY
            if (g.adults == 0) g.young = 0
            if (g.size > s.maxGroupSize) {
                val a = g.adults / 2; val y = g.young / 2
                g.adults -= a; g.young -= y
                val child = SeaGroup(nextId++, s, g.x + r.nextDouble() - 0.5, g.y + r.nextDouble() - 0.5, a, y)
                child.condition = g.condition
                g.seasonX.copyInto(child.seasonX); g.seasonY.copyInto(child.seasonY); g.seasonQ.copyInto(child.seasonQ)
                newcomers += child
            }
        }
        groups += newcomers
    }

    /** How crowded [g]'s range is with others of its kind, against a full sea ([SEA_FULL] times the starting density): pods gather where the fish are. */
    private fun crowding(g: SeaGroup): Float {
        val box = CoarseMaps.Box().also { it.set(maps, g.x, g.y, g.rangeKm) }
        val sea = max(1f, mSea.box(box.x0, box.y0, box.x1, box.y1).toFloat()) * cellKm * cellKm
        val others = max(0f, mOcc[g.species.index].box(box.x0, box.y0, box.x1, box.y1).toFloat() - g.massKg) / g.species.massKg
        return others / sea / (SEA_FULL / START_KM2.getValue(g.species.name))
    }

    private fun binomial(n: Int, p: Float, r: Random): Int {
        if (n <= 0 || p <= 0f) return 0
        if (p >= 1f) return n
        if (n > 30) {
            val mean = n * p; val sd = sqrt(n * p * (1f - p))
            val u = max(r.nextDouble(), 1e-12); val z = sqrt(-2 * kotlin.math.ln(u)) * kotlin.math.cos(2 * Math.PI * r.nextDouble())
            return (mean + sd * z + 0.5).toInt().coerceIn(0, n)
        }
        var k = 0
        repeat(n) { if (r.nextFloat() < p) k++ }
        return k
    }

    companion object {
        /** Extra deaths per year per unit of crowding beyond full. */
        const val SEA_DISEASE = 0.5f
        /** A full sea, as a multiple of the starting density. */
        const val SEA_FULL = 8f
        /** Coarse grid for sea ranges, km. */
        const val COARSE_KM = 8f
        /** A sea group's weekly range radius, in days' travel. */
        const val RANGE_FACTOR = 0.5f
        /** How far from the shore coastal upwelling reaches, km (e-folding). */
        const val UPWELL_KM = 20f
        /** Nutrient supply added by full upwelling. */
        const val UPWELL_SUPPLY = 0.6f
        /** Share of the food in a range a group can find and catch in a week. */
        const val FOOD_REACHABLE = 0.2f
        /** Shark mass per km² at which danger reads one half. */
        const val SHARK_KG_KM2 = 0.5f
        /** Chance a shark meets and tries a prey group whose range fully overlaps its own, per week: most of its food is fish. */
        const val SHARK_ATTACK = 0.03f
        /** Sea-bird foraging radius from the colony, km, and food per bird per day, kg. */
        const val BIRD_FORAGE_KM = 40f
        const val BIRD_KG_DAY = 0.25f
        /** Share of the fish within reach a colony can take in a year without emptying its waters. */
        const val BIRD_SHARE_OF_FISH = 0.05f
        /** Coastline per colony at most, km. */
        const val COLONY_SPACING_KM = 25f
        /** Sea per animal at the start, km², by species. */
        val START_KM2 = mapOf("fin whale" to 800f, "dolphin" to 15f, "porpoise" to 20f, "monk seal" to 30f, "shark" to 2500f)
    }
}
