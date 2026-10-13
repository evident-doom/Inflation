package inflation.ecology

import inflation.world.HexLand
import inflation.world.LifeForm
import inflation.world.Weather
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The plant food that is not simply the standing green: stocks that build up and are eaten down, in
 * kg/m² of each place, as in the 1 km model.
 *
 * - Fruit, berries, seeds and nuts on shrubs and trees: ripen from midsummer to autumn, as many as
 *   the spring's flowers were pollinated for; what is not eaten falls and rots into the litter.
 * - Bulbs, roots and fallen mast that rooting animals dig up: slow to replace, most mast in autumn.
 * - Standing dead grass, stems and twigs: plentiful but poor, the fallback of the lean months; it
 *   builds up over months and is eaten down where herds crowd, so the dry season and the winter set
 *   how many grazers the land carries. Big animals get more from it (Jarman-Bell, see [roughage]).
 *
 * What is eaten comes off the plants that bear it ([take]).
 */
class HexFood(private val land: HexLand, private val small: HexSmallLife, private val days: Int) {
    val n = land.n
    val fruit = FloatArray(n)
    /** Ripe grass seed (wild cereals and other grasses), kg/m²: early summer, shed and gone by autumn. People gather it. */
    val seeds = FloatArray(n)
    val roots = FloatArray(n) { p -> rootsCap(p) * 0.7f }
    val straw = FloatArray(n) { p -> strawCap(p) * 0.7f }

    /** Fruit-bearing plants at [p], kg/m²: shrubs and the fruiting share of trees. */
    private fun bearing(p: Int) = 0.5f * land.shrub[p] + 0.05f * land.tree[p]
    private fun rootsCap(p: Int) = ROOTS_SHARE * (land.herb[p] + 0.05f * land.tree[p])
    private fun strawCap(p: Int) = STRAW_SHARE * (land.herb[p] + 0.3f * land.shrub[p])

    fun step(weather: Weather) {
        val month = weather.date.month
        java.util.stream.IntStream.range(0, n).parallel().forEach { p ->
            // Fruit ripens from midsummer to autumn, then falls and rots.
            val ripe = if (month in 4..7) FRUIT_SHARE * bearing(p) * small.fruitSet(p) / FRUIT_DAYS else 0f
            val fallen = fruit[p] * min(1f, days / FRUIT_DAYS)
            fruit[p] = max(0f, fruit[p] + days * ripe - fallen)
            small.litter[p] += fallen
            // Grass seed ripens in early summer as the grass dries, and is shed over the following weeks.
            val ripeSeed = if (month in 2..4) SEED_SHARE * land.herb[p] / SEED_DAYS else 0f
            val shed = seeds[p] * min(1f, days / SEED_DAYS)
            seeds[p] = max(0f, seeds[p] + days * ripeSeed - shed)
            small.litter[p] += shed
            val r = rootsCap(p) * (if (month in 6..8) 3f else 0.5f) / ROOTS_DAYS
            roots[p] = max(0f, roots[p] + days * (r - roots[p] / ROOTS_DAYS))
            straw[p] = max(0f, straw[p] + days * (strawCap(p) - straw[p]) / STRAW_DAYS)
        }
    }

    /**
     * Takes up to [kgPerM2] from [stock] at [p], off the plants it comes from (fruit and mast from
     * shrubs and trees, roots and straw from the herb layer); returns what was taken.
     */
    fun take(stock: FloatArray, p: Int, kgPerM2: Float): Float {
        val t = min(stock[p], max(0f, kgPerM2))
        stock[p] -= t
        when (stock) {
            fruit -> { val sh = 0.5f * land.shrub[p]; val tr = 0.05f * land.tree[p]; val all = sh + tr
                if (all > 0f) { land.consume(p, LifeForm.SHRUB, t * sh / all); land.consume(p, LifeForm.TREE, t * tr / all) } }
            roots, seeds -> land.consume(p, LifeForm.HERB, t)
            straw -> land.consume(p, LifeForm.HERB, 0.7f * t).also { land.consume(p, LifeForm.SHRUB, 0.3f * t) }
        }
        return t
    }

    companion object {
        /** Ripe fruit at the season's peak, per kg/m² of fruit-bearing plants; days it lasts before it falls. */
        const val FRUIT_SHARE = 0.0008f
        const val FRUIT_DAYS = 30f
        /** Ripe grass seed at its peak, per kg/m² of herbs (a rich wild-cereal stand gives far more); days it stays on the stalk. */
        const val SEED_SHARE = 0.01f
        const val SEED_DAYS = 40f
        /** Roots and mast to be dug, per kg/m² of herbs (and trees × 0.05); days to replace them. */
        const val ROOTS_SHARE = 0.0003f
        const val ROOTS_DAYS = 180f
        /** Standing dead grass and twigs, per kg/m² of herbs (and shrubs × 0.3); days to build up again. */
        const val STRAW_SHARE = 0.0025f
        const val STRAW_DAYS = 180f
        /** Nourishment of straw against fresh growth, for a 100 kg animal. */
        const val ROUGHAGE_VALUE = 0.4f

        /**
         * How much [s] gets out of straw (the Jarman-Bell principle): a big gut holds food longer and
         * digests fibre better; aurochs and wild asses live through the lean months on straw that
         * starves a gazelle.
         */
        fun roughage(s: Species) = (ROUGHAGE_VALUE * s.fibreDigestion * (s.massKg / 100f).pow(0.25f)).coerceAtMost(0.75f)

        /**
         * Share of a place's food [s] can get at: very broken ground (cliffs, crags) keeps all but the
         * climbers off much of it, which is what leaves the ibex its cliffs.
         */
        fun access(s: Species, rugged: Float): Float {
            val climber = max(0f, s.habitat.rugged)
            return 1f - 0.8f * (1f - min(1f, climber)) * ((rugged - 0.4f) / 0.4f).coerceIn(0f, 1f)
        }
    }
}
