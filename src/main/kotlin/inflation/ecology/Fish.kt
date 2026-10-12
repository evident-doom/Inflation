package inflation.ecology

import inflation.world.Channel
import inflation.world.Date
import inflation.world.RiverClass
import inflation.world.Simulation
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Fish and shellfish, the aquatic food that coastal and river people lived on.
 *
 * - Resident fish, kg/km² of water: in the sea they live off the plankton, so shelves and river mouths
 *   carry far more than open water; in lakes the same; in rivers they live on insects and drift,
 *   so the bigger and warmer the river the more fish per km. Low water in drought kills fish in
 *   streams, and streams that dry up lose them all; they come back from downstream and from lakes.
 * - Shellfish within a few km of the shore in water under 10 m (mussels, oysters, clams, limpets), kg/km², growing on the
 *   phytoplankton: the beds behind the shell middens of coastal foragers.
 * - Spawning runs: shad-like fish that live at sea gather off river mouths in spring and swim
 *   upstream about 20 km a day to spawn, then die or drift back. A run is a seasonal glut a river
 *   people can harvest. Dams, steep cascades and dried-up reaches stop it.
 *
 * Runs follow the river network as it was formed; rerouted rivers keep their old run paths.
 */
class Fish(private val sim: Simulation, private val plankton: Plankton) {
    private val world = sim.world
    private val grid = world.grid
    private val n = grid.size
    private val drainage = world.water.drainage

    /** Resident fish, kg/km². */
    val fish = FloatArray(n)
    /** Shellfish on shallow coasts, kg/km². */
    val shellfish = FloatArray(n)
    /** Fish of the spawning run present now, kg/km². */
    val run = FloatArray(n)

    /** Fish habitat: sea, lakes, and river cells of at least stream size. */
    private val isRiver = BooleanArray(n) { world.isRiver(it) && world.water.discharge[it] >= RiverClass.STREAM.minDischarge }
    private val habitat = BooleanArray(n) { plankton.isWater[it] || isRiver[it] }
    /** River mouth each river cell drains to, or -1. */
    private val mouth = IntArray(n) { -1 }
    /** Distance upstream from the mouth along the river, km. */
    private val upKm = FloatArray(n)
    /** A barrier (cascade) between this cell and the sea that fish cannot pass. */
    private val cascade = BooleanArray(n)
    private var knownDams = -1
    private val damBlocked = BooleanArray(n)

    /** Sea within about 3 km of the shore and shallower than 10 m: rocky shores, lagoons and estuaries. */
    private val shallowCoast = BooleanArray(n).also { out ->
        val r = kotlin.math.max(1, (3 / grid.cellKm).toInt())
        for (i in 0 until n) {
            if (!world.isSea[i] || world.elevation[i] < -10f) continue
            val x0 = grid.x(i); val y0 = grid.y(i)
            search@ for (dy in -r..r) for (dx in -r..r) {
                val x = x0 + dx; val y = y0 + dy
                if (grid.inBounds(x, y) && !world.isSea[grid.index(x, y)]) { out[i] = true; break@search }
            }
        }
    }

    init {
        // Downstream-first order: each river cell inherits its receiver's mouth and distance.
        // Lakes on the way are passable: run fish cross them to the rivers that feed them.
        for (i in drainage.stack) {
            if (!isRiver[i] && !world.water.isLake(i)) continue
            val r = drainage.receiver[i]
            when {
                r == i -> {}
                world.isSea[r] -> { mouth[i] = r; upKm[i] = 0f }
                mouth[r] >= 0 -> {
                    mouth[i] = mouth[r]; upKm[i] = upKm[r] + drainage.receiverDistM[i] / 1000f
                    val gradient = (world.elevation[i] - world.elevation[r]) / drainage.receiverDistM[i]
                    cascade[i] = cascade[r] || gradient > CASCADE_GRADIENT
                }
            }
        }
        for (i in 0 until n) {
            if (!habitat[i]) continue
            fish[i] = 0.7f * capacity(i)
            if (isShallowCoast(i)) shellfish[i] = 0.7f * shellCapacity(i)
        }
    }

    private fun isShallowCoast(i: Int) = shallowCoast[i]

    /** Fish the water can carry, kg/km². */
    private fun capacity(i: Int): Float = when {
        // Fish production follows primary production, through zooplankton and small fish.
        plankton.isWater[i] -> MAX_SEA_FISH * plankton.phyto[i] / (plankton.phyto[i] + 5f)
        isRiver[i] -> {
            // Per km of river, by size, spread over the cell. Warm water is more productive.
            val q = sim.water.discharge[i]
            val widthKm = Channel.widthM(max(q, 0.01f)) / 1000f
            val warm = ((sim.weather.temperature[i] + 5f) / 25f).coerceIn(0.2f, 1.2f)
            RIVER_FISH_PER_KM2_WATER * widthKm * warm / grid.cellKm.toFloat()
        }
        else -> 0f
    }

    private fun shellCapacity(i: Int) = MAX_SHELLFISH * plankton.phyto[i] / (plankton.phyto[i] + 3f)

    fun step() {
        val days = world.config.daysPerStep
        val years = days / Date.DAYS_PER_YEAR.toFloat()
        updateDams()
        // Cells are independent of each other, so they are stepped in parallel.
        java.util.stream.IntStream.range(0, n).parallel().forEach { i ->
            if (!habitat[i]) return@forEach
            val k = capacity(i)
            var f = fish[i]
            f += FISH_GROWTH * years * f * (1 - f / max(k, 1f))
            if (isRiver[i]) {
                // Low water shrinks the river and what it can carry (see capacity); a nearly dry
                // bed leaves only what survives in pools.
                val ratio = sim.water.discharge[i] / max(world.water.discharge[i], 1e-3f)
                if (ratio < 0.05f) f *= 0.3f
            }
            fish[i] = max(f, 0f)
            if (plankton.isWater[i] && plankton.zoo[i] > 0f) {
                // They eat about three times their own mass a year, taken from the zooplankton (g/m² = t/km²).
                plankton.zoo[i] = max(0.005f, plankton.zoo[i] - 3f * years * fish[i] / 1000f)
            }
            if (isShallowCoast(i)) {
                val s = shellfish[i]
                shellfish[i] = max(0f, s + 0.8f * years * s * (1 - s / max(shellCapacity(i), 1f)))
            }
        }
        recolonise()
        spawningRun()
    }

    /** Fish spread up and down rivers from neighbouring water every step. */
    private fun recolonise() {
        for (i in drainage.stack) {
            if (!isRiver[i]) continue
            val r = drainage.receiver[i]
            if (r == i || !habitat[r]) continue
            // A tenth of the density difference moves along each reach; cascades stop fish going up.
            val diff = fish[r] - fish[i]
            if (diff > 0f && !(cascade[i] && !cascade[r])) fish[i] += 0.1f * diff
        }
    }

    private fun updateDams() {
        val dams = sim.water.dams
        if (dams.size == knownDams) return
        knownDams = dams.size
        damBlocked.fill(false)
        val damAt = BooleanArray(n)
        for (d in dams) damAt[d.cell] = true
        for (i in drainage.stack) {
            if (!isRiver[i] && !world.water.isLake(i)) continue
            val r = drainage.receiver[i]
            damBlocked[i] = damAt[i] || (r != i && damBlocked[r])
        }
    }

    /**
     * Spring spawning run. Run fish gather off each river mouth in proportion to the coastal stock
     * and the river's size, then move up about [RUN_KM_PER_DAY] km a day while the season lasts.
     */
    private fun spawningRun() {
        val date = sim.weather.date
        val dayInSeason = (date.dayOfYear - RUN_START_DAY)
        if (dayInSeason !in 0 until RUN_DAYS) {
            for (i in 0 until n) run[i] *= 0.3f
            return
        }
        val front = dayInSeason * RUN_KM_PER_DAY
        for (i in 0 until n) {
            run[i] = 0f
            val m = mouth[i]
            if (m < 0 || cascade[i] || damBlocked[i]) continue
            val dist = upKm[i]
            if (dist > front) continue
            // The run thins as fish turn off into side streams and spawn on the way.
            val atMouth = RUN_PER_COASTAL_FISH * fish[m] * min(1f, world.water.discharge[i] / 30f)
            val ratio = sim.water.discharge[i] / max(world.water.discharge[i], 1e-3f)
            if (ratio < 0.2f) continue // too little water to swim up
            run[i] = atMouth * exp(-dist / 150f)
        }
    }

    /** Takes up to [kg] of fish (resident, then run) from cell [i]; returns what was caught. */
    fun catch(i: Int, kg: Float): Float {
        val area = grid.cellKm.toFloat() * grid.cellKm.toFloat()
        var left = kg
        val fromRun = min(run[i] * area * 0.5f, left); run[i] -= fromRun / area; left -= fromRun
        val fromFish = min(fish[i] * area * 0.3f, left); fish[i] -= fromFish / area; left -= fromFish
        return kg - left
    }

    /** Gathers up to [kg] of shellfish from cell [i]; returns what was gathered. */
    fun gatherShellfish(i: Int, kg: Float): Float {
        val area = grid.cellKm.toFloat() * grid.cellKm.toFloat()
        val taken = min(shellfish[i] * area * 0.3f, kg)
        shellfish[i] -= taken / area
        return taken
    }

    companion object {
        /** Fish on a rich shelf, kg/km² (a few tonnes per km² in the oligotrophic Mediterranean). */
        const val MAX_SEA_FISH = 6000f
        /** Fish per km² of river water surface at summer warmth: small rivers are rich per area. */
        const val RIVER_FISH_PER_KM2_WATER = 15_000f
        const val MAX_SHELLFISH = 20_000f
        const val FISH_GROWTH = 0.6f
        /**
         * Mean gradient over a kilometre reach that run fish cannot climb: a cascade or waterfall.
         * Rivers on a 1 km grid average over pools and riffles, so ordinary steep reaches pass.
         */
        const val CASCADE_GRADIENT = 0.12f
        const val RUN_START_DAY = 30
        const val RUN_DAYS = 60
        const val RUN_KM_PER_DAY = 20f
        /** Run fish entering a river per kg/km² of fish off its mouth, scaled by river size. */
        const val RUN_PER_COASTAL_FISH = 2f
    }
}
