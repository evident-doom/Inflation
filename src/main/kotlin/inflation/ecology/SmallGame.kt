package inflation.ecology

import inflation.world.Date
import inflation.world.Simulation
import inflation.world.blur
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The middle of the food web, as density fields in kg/km²: the small game foragers lived on day to
 * day, the small carnivores that live on it, and the vultures that clear the carcasses.
 *
 * - Hares on open grassland and steppe, breeding through spring and summer.
 * - Rock hyrax in cliffs and boulder fields, browsing the scrub nearby: the leopard's staple.
 * - Game birds (partridge, quail, sandgrouse) of open scrub and fields.
 * - Tortoises in warm scrub: slow to breed, torpid in the cold and in hot drought.
 * - Foxes and wildcats, living on rodents, hares, birds, insects and fruit.
 * - Griffon vultures nesting in inland cliffs, soaring up to [VULTURE_RANGE_KM] for carcasses.
 *
 * Large predators, eagles and foxes take the small game; fire kills tortoises and scatters the rest.
 */
class SmallGame(private val sim: Simulation, private val small: SmallFauna, private val soil: SoilLife) {
    private val world = sim.world
    private val grid = world.grid
    private val n = grid.size
    private val cellKm2 = (grid.cellKm * grid.cellKm).toFloat()

    val hares = FloatArray(n)
    val hyrax = FloatArray(n)
    val birds = FloatArray(n)
    val tortoises = FloatArray(n)
    val foxes = FloatArray(n)
    /** Vulture colonies: nest cell and birds. */
    val vultureCell = ArrayList<Int>()
    val vultures = ArrayList<Float>()
    private var steps = 0

    init {
        val v = sim.vegetation
        val t = world.terrain
        for (i in 0 until n) {
            if (!v.isVegetated(i)) continue
            hares[i] = 0.5f * hareCapacity(i)
            hyrax[i] = 0.5f * hyraxCapacity(i)
            birds[i] = 0.5f * birdCapacity(i)
            tortoises[i] = 0.5f * tortoiseCapacity(i)
            foxes[i] = 0.3f
        }
        // Vultures: the steepest inland cliffs, one colony per block of [VULTURE_SPACING_KM].
        val spacing = max(1, (VULTURE_SPACING_KM / grid.cellKm).toInt())
        for (by in 0 until grid.height step spacing) for (bx in 0 until grid.width step spacing) {
            var best = -1; var bestRug = 0.5f
            for (y in by until min(grid.height, by + spacing)) for (x in bx until min(grid.width, bx + spacing)) {
                val c = grid.index(x, y)
                if (!v.isVegetated(c)) continue
                if (t.ruggedness[c] > bestRug) { bestRug = t.ruggedness[c]; best = c }
            }
            if (best >= 0) { vultureCell += best; vultures += 40f }
        }
    }

    private fun open(i: Int) = 1f - min(1f, sim.vegetation.canopy(i) + 0.5f * min(1f, sim.vegetation.shrub[i] / 1.5f))
    private fun hareCapacity(i: Int) = 40f * sim.vegetation.herb[i] / (sim.vegetation.herb[i] + 0.3f) * open(i)
    private fun hyraxCapacity(i: Int) = 80f * world.terrain.ruggedness[i].pow(1.5f) * min(1f, sim.vegetation.shrub[i] / 0.5f + 0.2f)
    private fun birdCapacity(i: Int): Float {
        val shrubby = min(1f, sim.vegetation.shrub[i] / 1f)
        return 15f * (0.3f + 0.7f * open(i)) * (0.3f + 0.7f * shrubby) * sim.vegetation.herb[i] / (sim.vegetation.herb[i] + 0.2f)
    }
    private fun tortoiseCapacity(i: Int): Float {
        val warm = ((world.climate.meanTemp(i) - 12f) / 8f).coerceIn(0f, 1f)
        return 30f * warm * min(1f, (sim.vegetation.shrub[i] + sim.vegetation.herb[i]) / 0.8f)
    }

    fun step() {
        val days = world.config.daysPerStep
        val years = days / Date.DAYS_PER_YEAR.toFloat()
        val month = sim.weather.date.month
        val v = sim.vegetation
        java.util.stream.IntStream.range(0, n).parallel().forEach { i ->
            if (!v.isVegetated(i)) { hares[i] = 0f; hyrax[i] = 0f; birds[i] = 0f; tortoises[i] = 0f; foxes[i] = 0f; return@forEach }
            val t = sim.weather.temperature[i]
            val mild = t > 5f
            hares[i] = logistic(hares[i], if (month in 0..6 && mild) 4f else 0f, 0.9f, hareCapacity(i), years)
            hyrax[i] = logistic(hyrax[i], if (month in 1..3) 2.5f else 0f, 0.4f, hyraxCapacity(i), years)
            birds[i] = logistic(birds[i], if (month in 1..4 && mild) 4f else 0f, 0.9f, birdCapacity(i), years)
            val active = t in 14f..33f && sim.water.soil[i] > 8f
            tortoises[i] = logistic(tortoises[i], if (active && month in 1..5) 0.8f else 0f, if (active) 0.15f else 0.05f, tortoiseCapacity(i), years)
            // Foxes and wildcats eat about a seventh of their weight a day, mostly rodents, then
            // hares and birds; they breed in spring as the food allows, and die back when it fails.
            val prey = small.rodents[i] + hares[i] + birds[i] + 0.3f * small.insects[i] / 10f
            val need = foxes[i] * 0.15f * days
            val got = min(need, 0.1f * prey)
            if (got > 0f && prey > 0f) {
                val f = got / prey
                small.rodents[i] -= small.rodents[i] * f; hares[i] -= hares[i] * f; birds[i] -= birds[i] * f
            }
            val fed = if (need > 0f) got / need else 1f
            val foxK = 0.02f * prey
            foxes[i] = logistic(foxes[i], if (month in 1..3) 1.5f * fed else 0f, 0.4f + 0.8f * (1f - fed), foxK, years).coerceAtLeast(0.01f)
            // Fire: tortoises die where it burns; the rest bolt and many young die.
            if (v.burning[i]) { tortoises[i] *= 0.4f; hares[i] *= 0.7f; birds[i] *= 0.7f }
        }
        vultureStep(days, years)
        if (++steps % 5 == 0) for (f in listOf(hares, birds, foxes)) spread(f)
    }

    /** Logistic growth at [r] a year in season, deaths at [d] a year, toward [k]. */
    private fun logistic(x: Float, r: Float, d: Float, k: Float, years: Float): Float {
        val kk = max(k, 0.01f)
        return max(0.001f, x + x * r * years * (1f - x / kk) - x * d * years * if (x > kk) 2f else 1f)
    }

    /**
     * Vultures soar out from their colony each day and find the carcasses within reach before most
     * other scavengers; a colony grows when they eat well and shrinks when they do not.
     */
    private fun vultureStep(days: Int, years: Float) {
        val r = (VULTURE_RANGE_KM / grid.cellKm).toInt()
        val step = max(1, r / 20) // sample the range coarsely
        for (k in vultureCell.indices) {
            val c = vultureCell[k]
            val cx = grid.x(c); val cy = grid.y(c)
            val need = vultures[k] * 0.5f * days
            var got = 0f
            var y = max(0, cy - r)
            while (y <= min(grid.height - 1, cy + r) && got < need) {
                var x = max(0, cx - r)
                while (x <= min(grid.width - 1, cx + r) && got < need) {
                    val i = grid.index(x, y)
                    if (soil.carrion[i] > 0f) got += soil.scavenge(i, ((need - got) / grid.cellAreaM2).toFloat()) * grid.cellAreaM2.toFloat()
                    x += step
                }
                y += step
            }
            val fed = if (need > 0f) min(1f, got / need) else 1f
            val month = sim.weather.date.month
            var b = vultures[k]
            if (month in 1..2) b += b * 0.5f * fed * years * 6f // one chick a pair, in late winter
            b -= b * (0.08f * years + 0.4f * years * (1f - fed))
            vultures[k] = b.coerceIn(2f, 2000f)
        }
    }

    /** Takes up to [kgPerKm2] of small game at [i] (for hunters); returns what was caught. */
    fun catch(i: Int, kgPerKm2: Float): Float {
        val active = sim.weather.temperature[i] in 14f..33f
        val tort = if (active) tortoises[i] else 0f
        val total = hares[i] + hyrax[i] + birds[i] + tort
        if (total <= 0f) return 0f
        val take = min(kgPerKm2, 0.3f * total)
        val f = take / total
        hares[i] -= hares[i] * f; hyrax[i] -= hyrax[i] * f; birds[i] -= birds[i] * f
        if (active) tortoises[i] -= tortoises[i] * f
        return take
    }

    private fun spread(f: FloatArray) {
        val mixed = blur(grid, f, 2)
        val v = sim.vegetation
        for (i in 0 until n) if (v.isVegetated(i)) f[i] = 0.8f * f[i] + 0.2f * mixed[i]
    }

    /** Totals over the land, tonnes, and vultures in all colonies. */
    fun summary(): String {
        fun t(a: FloatArray) = a.sumOf { it.toDouble() } * cellKm2 / 1000
        return "hares %.0f t, hyrax %.0f t, game birds %.0f t, tortoises %.0f t, foxes %.0f t, vultures %.0f".format(
            t(hares), t(hyrax), t(birds), t(tortoises), t(foxes), vultures.sum())
    }

    companion object {
        /** How far vultures soar from the colony for carcasses, km. */
        const val VULTURE_RANGE_KM = 80f
        /** Land per vulture colony at most, km. */
        const val VULTURE_SPACING_KM = 80f
    }
}
