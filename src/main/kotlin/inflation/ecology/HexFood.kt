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
    /**
     * Small finds people gather in season, kg/m²: honey where bees work the flowers (late spring), desert
     * truffles after winter rain (spring), birds' eggs (spring), snails on damp ground (winter and spring).
     * They come off the plants (and the birds' brood) they grow from, and spoil if not taken.
     */
    val finds = FloatArray(n)
    val roots = FloatArray(n) { p -> rootsCap(p) * 0.7f }
    /**
     * Lichen on cold, open ground, kg/m²: it grows a few hundredths of its stand a year, so grazed-out
     * heath takes decades to come back; reindeer dig it out from under the snow in winter.
     */
    val lichen = FloatArray(n) { p -> 0.8f * lichenCap(p) }
    private fun lichenCap(p: Int) = LICHEN_MAX * (1f - land.annualGdd[p] / LICHEN_GDD).coerceIn(0f, 1f) * (1f - land.canopy(p))
    /** Standing straw: the land's (it burns there). */
    val straw get() = land.straw

    /** Fruit-bearing plants at [p], kg/m²: shrubs and the fruiting share of trees. */
    // (Only the edible kinds bear food people eat, see [HexLand.palatable].)
    private fun bearing(p: Int) = land.palatable[p] * (0.5f * land.shrub[p] + 0.05f * land.tree[p])
    private fun strawCap(p: Int) = STRAW_SHARE * (land.herb[p] + 0.3f * land.shrub[p])
    private fun rootsCap(p: Int) = ROOTS_SHARE * land.palatable[p] * (land.herb[p] + 0.05f * land.tree[p]) + REED_ROOTS * land.logged[p]

    fun step(weather: Weather) {
        java.util.stream.IntStream.range(0, n).parallel().forEach { p ->
            // The season as the plants feel it: the share of a normal year's warmth they have had since
            // midwinter. Seed ripens early, fruit from midsummer to autumn, roots and bulbs fill as the
            // plants die back; a warm year brings all of it on earlier, high ground later.
            val season = land.gdd[p] / land.annualGdd[p].coerceAtLeast(1f)
            // Fruit ripens from midsummer to autumn, as many as the spring's flowers were pollinated
            // for and the spring was wet enough to fill; then it falls and rots.
            val ripe = if (season in FRUIT_FROM..FRUIT_TO) FRUIT_SHARE * bearing(p) * small.fruitSet(p) * min(1f, land.moisture[p] / 0.5f) / FRUIT_DAYS else 0f
            val fallen = fruit[p] * min(1f, days / FRUIT_DAYS)
            fruit[p] = max(0f, fruit[p] + days * ripe - fallen)
            small.litter[p] += fallen
            // Grass seed ripens in early summer as the grass dries, and is shed over the following weeks.
            val ripeSeed = if (season in SEED_FROM..SEED_TO) SEED_SHARE * land.palatable[p] * land.herb[p] / SEED_DAYS else 0f
            val shed = seeds[p] * min(1f, days / SEED_DAYS)
            seeds[p] = max(0f, seeds[p] + days * ripeSeed - shed)
            small.litter[p] += shed
            val r = rootsCap(p) * (if (season >= ROOTS_FROM) 3f else 0.5f) / ROOTS_DAYS
            roots[p] = max(0f, roots[p] + days * (r - roots[p] / ROOTS_DAYS))
            // Lichen creeps back.
            val lc = lichenCap(p)
            if (lc > 0f) lichen[p] = max(0f, lichen[p] + LICHEN_GROWTH * days / 365f * max(lichen[p], 0.01f * lc) * (1f - lichen[p] / lc))
            // Small finds in season.
            val month = weather.date.month
            val honey = if (month in 3..5) HONEY * small.pollination[p] * (land.shrub[p] + 0.1f * land.tree[p]) else 0f
            val truffles = if (month in 2..4) TRUFFLES * land.herb[p] * (1f - land.moisture[p]).coerceIn(0f, 1f) * land.green[p] else 0f
            val eggs = if (month in 3..5) EGGS * small.birds[p] / 1e6f else 0f
            val snails = if (month in 0..4) SNAILS * land.herb[p] * land.moisture[p] else 0f
            val made = (honey + truffles + snails) * days
            val grown = land.consume(p, LifeForm.HERB, made) + eggs * days
            finds[p] = max(0f, finds[p] + grown - finds[p] * min(1f, days / FINDS_DAYS))
            // Standing dead grass and twigs build up as a small share of the herbs and shrubs and are eaten down.
            straw[p] = max(0f, straw[p] + days * (strawCap(p) - straw[p]) / STRAW_DAYS)
        }
    }

    /**
     * Takes up to [kgPerM2] from [stock] at [p], off the plants it comes from (fruit and mast from
     * Takes up to [kgPerM2] from [stock] at [p] (digging roots kills the herbs they come from); returns what was taken.
     */
    fun take(stock: FloatArray, p: Int, kgPerM2: Float): Float {
        val t = min(stock[p], max(0f, kgPerM2))
        stock[p] -= t
        // Fruit, seed and straw are already off the live plants; digging roots and bulbs kills herbs.
        if (stock === roots) land.consume(p, LifeForm.HERB, t)
        return t
    }

    companion object {
        /** Ripe fruit at the season's peak, per kg/m² of fruit-bearing plants; days it lasts before it falls. */
        const val FRUIT_SHARE = 0.0008f
        const val FRUIT_DAYS = 30f
        /** When each ripens, as the share of a normal year's warmth (degree-days above 5 °C since midwinter) had so far. */
        const val SEED_FROM = 0.25f; const val SEED_TO = 0.45f
        const val FRUIT_FROM = 0.5f; const val FRUIT_TO = 0.9f
        const val ROOTS_FROM = 0.8f
        /** Ripe grass seed at its peak, per kg/m² of herbs (a rich wild-cereal stand gives far more); days it stays on the stalk. */
        const val SEED_SHARE = 0.01f
        const val SEED_DAYS = 40f
        /** Roots and mast to be dug, per kg/m² of herbs (and trees × 0.05); days to replace them. */
        const val ROOTS_SHARE = 0.0003f
        const val ROOTS_DAYS = 180f
        /** Lichen: a full heath, kg/m²; warmth over a year (degree-days above 5 °C) beyond which none grows; share of the stand it adds a year; what a kilo is worth to a reindeer against green forage. */
        const val LICHEN_MAX = 0.5f
        const val LICHEN_GDD = 1200f
        const val LICHEN_GROWTH = 0.05f
        const val LICHEN_VALUE = 0.8f
        /** Small finds a day in season: honey per kg/m² of shrubs (and trees) at full pollination, truffles and snails per kg/m² of herbs, eggs per kg of birds; days they keep. */
        const val HONEY = 7e-8f
        const val TRUFFLES = 6e-5f
        const val SNAILS = 1.2e-6f
        const val EGGS = 0.01f
        const val FINDS_DAYS = 30f
        /** Starchy rhizomes of reed and reedmace in a marsh, kg/m² at their peak. */
        const val REED_ROOTS = 0.01f
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
