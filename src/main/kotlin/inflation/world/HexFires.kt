package inflation.world

import inflation.HASH_A
import inflation.HASH_B
import inflation.HASH_C
import inflation.mix64
import inflation.unitOf
import inflation.Config
import inflation.ecology.Place
import inflation.ecology.get
import inflation.ecology.set

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** Fires on the places of [HexLand]: a fire burns a share of a place and spreads to neighbouring places, more readily downwind. */
class HexFires(land: HexLand) : HexLandPart(land) {
    /** Share of each place burnt lately, fading over a few years; share burning this step. */
    val burnt = FloatArray(n)
    val burning = FloatArray(n)
    /** Fires this step: the place each started in and the km² it burned. */
    val fires = ArrayList<Fire>()
    /** For the yearly report ([yearReport]): fires bands set, those that took, and km² burnt by theirs and by the rest. */
    @Transient private var setYear = 0
    @Transient private var tookYear = 0
    @Transient private var peopleKm2Year = 0.0
    @Transient private var wildKm2Year = 0.0

    private fun fuel(p: Place) = (plants.herb[p] + 0.5f * plants.shrub[p]) * (1f - 0.8f * (1f - exp(-plants.grazing[p] / GRAZE_SCALE)))

    /** Whether place [p] would burn this week if lit: fuel enough, not flooded, dry soil, warm and rainless. */
    internal fun canBurn(p: Place, weather: Weather) =
        hydro.flood[p] <= 0f && fuel(p) >= 0.2f && hydro.soil[p] / hydro.soilCap[p] < 0.3f && weather.temperature[p] > 15f && weather.precipitation[p] < 5f

    /** Fires people have set, to be lit next step. */
    private val lit = java.util.concurrent.ConcurrentLinkedQueue<Place>()
    fun ignite(p: Place) { lit += p }

    /**
     * Fires: started by lightning and sparks in hot, dry, fuel-rich places (more often the bigger the
     * place), each burns a share of its place set by the fuel and spreads to neighbouring places,
     * readily downwind, barely upwind, within the step.
     */
    internal fun spread(weather: Weather, years: Float) {
        burning.fill(0f)
        fires.clear()
        val set = HashSet<Place>()
        while (true) set += lit.poll() ?: break
        setYear += set.size
        for (i in 0 until n) {
            val p = Place(i)
            if (burning[p] > 0f || !canBurn(p, weather)) continue
            val heat = ((weather.temperature[p] - 15f) / 20f).coerceIn(0f, 1f)
            val chance = tuning.ignitionsPerKm2Year * land.areaKm2[p] * years * min(1f, fuel(p) / 1.5f) * heat
            if (p !in set && hash(p, 0) >= chance) continue
            var km2 = 0f
            val queue = ArrayDeque<Place>(); queue += p
            burning[p] = burnShare(p); km2 += burning[p] * land.areaKm2[p]
            val farKm = SPREAD_KM_PER_DAY * days
            while (queue.isNotEmpty()) {
                val c = queue.removeFirst()
                val (cx, cy) = hex.centreKm(hw.placeHex[c])
                val u = weather.windEast(c.i); val v = weather.windSouth(c.i)
                val speed = kotlin.math.hypot(u, v)
                for (j in land.neighbours[c.i]) {
                    val nb = Place(j)
                    if (burning[nb] > 0f || !canBurn(nb, weather)) continue
                    if (hex.distanceKm(hw.placeHex[p], hw.placeHex[nb]) > farKm) continue
                    val (nx, ny) = hex.centreKm(hw.placeHex[nb])
                    val dx = (nx - cx).toFloat(); val dy = (ny - cy).toFloat()
                    val len = kotlin.math.hypot(dx, dy)
                    val along = if (speed > 0.1f && len > 0f) (dx * u + dy * v) / (speed * len) else 0f
                    val wind = 0.2f + 0.4f * min(1f, speed / 8f) * along
                    if (hash(nb, c.i) >= wind * burning[c] * min(1f, fuel(nb))) continue
                    burning[nb] = burnShare(nb); km2 += burning[nb] * land.areaKm2[nb]; queue += nb
                }
            }
            fires += Fire(p.i, km2)
            if (p in set) { tookYear++; peopleKm2Year += km2 } else wildKm2Year += km2
        }
    }

    /** Share of place [p] a fire burns: most of a place thick with dry grass, a little of a sparse one. */
    private fun burnShare(p: Place) = (0.1f + 0.6f * min(1f, fuel(p) / 1.5f)).coerceAtMost(0.7f)

    internal fun burn(p: Place, share: Float): Float {
        val before = plants.biomass(p)
        plants.herb[p] *= 1f - 0.9f * share; plants.shrub[p] *= 1f - 0.8f * share; plants.tree[p] *= 1f - 0.3f * share
        // (What burnt of the grass grows back as new leaf.)
        plants.grassAge[p] *= 1f - 0.9f * share
        burnt[p] = max(burnt[p], share)
        plants.fertility[p] = min(1f, plants.fertility[p] + 0.01f * share)
        return before - plants.biomass(p)
    }

    /** The year's fires so far, as a line for the report, and starts the count afresh. */
    fun yearReport(): String =
        "bands set %d (%d took), km² burnt by their fires %.0f, by others %.0f".format(setYear, tookYear, peopleKm2Year, wildKm2Year)
            .also { setYear = 0; tookYear = 0; peopleKm2Year = 0.0; wildKm2Year = 0.0 }

    private fun hash(p: Place, salt: Int): Float {
        return unitOf(mix64(land.seed * HASH_A + plants.stepCount * HASH_A + p.i * HASH_B + salt * HASH_C))
    }

    companion object {
        /** Grazing, kg/km²/day, at which most dry fuel is gone. */
        const val GRAZE_SCALE = 5f
        /** How far a fire front can run in a day, km. */
        const val SPREAD_KM_PER_DAY = 5f
    }
}

/** A fire of one step: the place or cell it started in ([start]) and the km² it burned. */
class Fire(val start: Int, val km2: Float)
