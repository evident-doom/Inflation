package inflation.ecology

import inflation.Books
import inflation.Stocks
import inflation.KgM2
import inflation.world.HexLand
import inflation.world.LifeForm
import inflation.world.Weather
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import inflation.world.Date
import java.util.stream.IntStream

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
 * Each stock comes off the plants that bear it as it grows, and what is not eaten falls into the litter.
 */
class HexFood(private val land: HexLand, private val small: HexSmallLife, private val days: Int) {
    val n = land.n
    /** Wild cereals do best on broken hill country: the mean over each place's ground (see [HexLand.overGround]). */
    @Transient private val cerealGround = land.overGround { (0.5f + it).coerceAtMost(1f) }
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
     * Wild cereals: large-seeded annual grasses (wild wheat and barley here), as a share of a place's
     * grass, where winters are wet, summers dry and the ground broken (the hilly flanks). Their ripe
     * grain, kg/m², is shed within weeks by ears that shatter; [domestic] is the share of the stand
     * whose ears hold their grain (0 wild), which sickle harvests and sowing raise and which drifts
     * back where the stand seeds itself. [field] is grain sown here this year, kg/m², which ripens to
     * [FIELD_YIELD] times as much.
     */
    val cereal = FloatArray(n) { p -> cerealShare(p) }
    val grain = FloatArray(n)
    val domestic = FloatArray(n) { p -> if (cereal[p] > 0f) MUTANT else 0f }
    val field = FloatArray(n)
    /** The share wild cereals would make of a place's grass in its climate, colder by [dT] and with [rainShare] of its rain. */
    private fun cerealShare(p: Int, dT: Float = 0f, rainShare: Float = 1f): Float {
        val h = land.hw.placeHex[p]
        val rain = rainShare * (0 until 12).sumOf { land.hw.precip[it][h].toDouble() }.toFloat()
        val winter = rainShare * ((0..2).sumOf { land.hw.precip[it][h].toDouble() }.toFloat() + land.hw.precip[11][h])
        val summer = rainShare * (5..7).sumOf { land.hw.precip[it][h].toDouble() }.toFloat()
        val warmth = dT + (0 until 12).map { land.hw.placeTemp(p, it) }.average().toFloat()
        // Winter rain and summer drought, 250-800 mm, mild; best on broken hill country.
        val climate = (if (rain in 250f..800f) 1f else 0f) * ((winter - summer) / rain.coerceAtLeast(1f) * 2f).coerceIn(0f, 1f) *
            (if (warmth in 10f..22f) 1f else 0f)
        return CEREAL_MAX * climate * cerealGround[p]
    }

    /** Standing straw: the land's (it burns there). */
    val straw get() = land.plants.straw

    /** Fruit-bearing plants at [p], kg/m²: shrubs and the fruiting share of trees. */
    // (Only the edible kinds bear food people eat, see [HexLand.palatable].)
    private fun bearing(p: Int) = land.palatable[p] * (0.5f * land.plants.shrub[p] + 0.05f * land.plants.tree[p])
    private fun strawCap(p: Int) = STRAW_SHARE * (land.plants.herb[p] + 0.3f * land.plants.shrub[p])
    private fun rootsCap(p: Int) = ROOTS_SHARE * land.palatable[p] * (land.plants.herb[p] + 0.05f * land.plants.tree[p]) + REED_ROOTS * land.waterlogged[p]

    private var standYear = -1
    /** Grain ripened at each place so far this year, kg/m², and over all the land in the last whole year, t. */
    private val ripenedNow = FloatArray(n)
    var grainRipenedLastYear = 0.0; private set
    fun step(weather: Weather) {
        // Once a year the wild cereal stands follow the climate: they spread where it suits them and
        // die back where it no longer does, over decades (a long cold, dry spell shrinks them).
        val year = (weather.date.absoluteDay / Date.DAYS_PER_YEAR).toInt()
        if (year != standYear) {
            val first = standYear < 0; standYear = year
            if (!first) grainRipenedLastYear = (0 until n).sumOf { (ripenedNow[it] * land.areaKm2[it] * 1e6f).toDouble() } / 1000
            ripenedNow.fill(0f)
            if (!first) { val (dT, rain) = weather.shock()
                IntStream.range(0, n).parallel().forEach { p -> cereal[p] += STAND_SHIFT * (cerealShare(p, dT, rain) - cereal[p]) } }
        }
        IntStream.range(0, n).parallel().forEach { p ->
            // The season as the plants feel it: the share of a normal year's warmth they have had since
            // midwinter. Seed ripens early, fruit from midsummer to autumn, roots and bulbs fill as the
            // plants die back; a warm year brings all of it on earlier, high ground later.
            val season = land.plants.gdd[p] / land.plants.annualGdd[p].coerceAtLeast(1f)
            // Fruit ripens from midsummer to autumn, as many as the spring's flowers were pollinated
            // for and the spring was wet enough to fill; then it falls and rots.
            val ripe = if (season in FRUIT_FROM..FRUIT_TO) FRUIT_SHARE * bearing(p) * small.fruitSet(p) *
                min(1f, land.plants.moisture[p] / 0.5f) / FRUIT_DAYS else 0f
            val fallen = fruit[p] * min(1f, days / FRUIT_DAYS)
            // (It grows on the shrubs and trees that bear it, and comes off them.)
            val shrubBears = 0.5f * land.plants.shrub[p]; val treeBears = 0.05f * land.plants.tree[p]
            val fromShrub = if (shrubBears + treeBears > 0f) shrubBears / (shrubBears + treeBears) else 1f
            val newFruit = days * ripe
            val bore = if (newFruit > 0f) (land.plants.consume(p, LifeForm.SHRUB, KgM2(newFruit * fromShrub)) +
                land.plants.consume(p, LifeForm.TREE, KgM2(newFruit * (1f - fromShrub)))).v else 0f
            fruit[p] = max(0f, fruit[p] + bore - fallen)
            small.litter[p] += fallen
            // Grass seed ripens in early summer as the grass dries, and is shed over the following weeks.
            val ripeSeed = if (season in SEED_FROM..SEED_TO) SEED_SHARE * land.palatable[p] * land.plants.herb[p] / SEED_DAYS else 0f
            val shed = seeds[p] * min(1f, days / SEED_DAYS)
            seeds[p] = max(0f, seeds[p] + land.plants.consume(p, LifeForm.HERB, KgM2(days * ripeSeed)).v - shed)
            small.litter[p] += shed
            // (Roots and bulbs fill from the plants above them; old ones rot into the litter.)
            val r = rootsCap(p) * (if (season >= ROOTS_FROM) 3f else 0.5f) / ROOTS_DAYS
            val rotted = roots[p] * min(1f, days / ROOTS_DAYS)
            roots[p] = max(0f, roots[p] + land.plants.consume(p, LifeForm.HERB, KgM2(days * r)).v - rotted)
            small.litter[p] += rotted
            // Wild cereals ripen with the other grasses; ears that shatter shed their grain within weeks,
            // those that hold it keep it for months. Sown fields ripen with them.
            if (cereal[p] > 0f || field[p] > 0f) {
                // (As the season advances: a cold year that never reaches its full warmth ripens only part of its grain.)
                val advance = max(0f, weather.temperature[p] - 5f) * days / land.plants.annualGdd[p].coerceAtLeast(1f)
                val inWindow = (min(season, SEED_TO) - max(season - advance, SEED_FROM)).coerceAtLeast(0f)
                if (inWindow > 0f) {
                    val ripe = land.plants.consume(p, LifeForm.HERB,
                        KgM2((GRAIN_SHARE * cereal[p] * land.plants.herb[p] + FIELD_YIELD * field[p]) * inWindow / (SEED_TO - SEED_FROM))).v
                    grain[p] += ripe
                    ripenedNow[p] += ripe
                }
                val wild = 1f - domestic[p]
                val lost = grain[p] * (wild * min(1f, days / SEED_DAYS) + (1f - wild) * min(1f, days / GRAIN_HOLD_DAYS))
                grain[p] = max(0f, grain[p] - lost); small.litter[p] += lost
                // After the year's ripening, the sown crop is spent, and a stand that seeded itself
                // drifts back toward the wild (ears that hold their grain cannot sow themselves).
                if ((season > SEED_TO && season - advance <= SEED_TO) || (weather.date.month == 0 && weather.date.dayOfMonth <= days && field[p] > 0f)) {
                    field[p] = 0f
                    domestic[p] = MUTANT + (domestic[p] - MUTANT) * (1f - WILD_DRIFT)
                }
            }
            // Small finds in season.
            val month = weather.date.month
            val honey = if (month in 3..5) HONEY * small.pollination[p] * (land.plants.shrub[p] + 0.1f * land.plants.tree[p]) else 0f
            val truffles = if (month in 2..4) TRUFFLES * land.plants.herb[p] * (1f - land.plants.moisture[p]).coerceIn(0f, 1f) * land.plants.green[p] else 0f
            val eggs = if (month in 3..5) EGGS * small.birds[p] / 1e6f else 0f
            val snails = if (month in 0..4) SNAILS * land.plants.herb[p] * land.plants.moisture[p] else 0f
            val made = (honey + truffles + snails) * days
            val grown = land.plants.consume(p, LifeForm.HERB, KgM2(made)).v + eggs * days
            if (eggs > 0f) land.books?.leak(Books.Stuff.MATTER, "eggs, not taken from the birds", (eggs * days * land.areaM2[p]).toDouble())
            val spoilt = finds[p] * min(1f, days / FINDS_DAYS)
            small.litter[p] += spoilt
            finds[p] = max(0f, finds[p] + grown - spoilt)
            // Standing dead grass and twigs build up as a small share of the herbs and shrubs and are eaten down.
            // (The straw is the herbs and shrubs dying back where they stand; it falls into the litter as it weathers.)
            val toward = days * (strawCap(p) - straw[p]) / STRAW_DAYS
            if (toward > 0f) {
                val herbShare = land.plants.herb[p] / (land.plants.herb[p] + 0.3f * land.plants.shrub[p]).coerceAtLeast(1e-9f)
                straw[p] += (land.plants.consume(p, LifeForm.HERB, KgM2(toward * herbShare)) + land.plants.consume(p, LifeForm.SHRUB, KgM2(toward * (1f - herbShare)))).v
            } else { val fell = min(straw[p], -toward); straw[p] -= fell; small.litter[p] += fell }
        }
    }

    /** Names the food stocks: fruit, grass seed, small finds, roots, grain and sown seed. */
    internal fun listStocks(stocks: Stocks) {
        for ((name, a) in listOf("fruit" to fruit, "grass seed" to seeds, "small finds" to finds, "roots" to roots, "grain" to grain, "sown seed" to field))
            stocks.perM2(name, a, land.areaM2)
    }

    /**
     * Takes up to [want] from [stock] (kg/m² by place) at [p]; returns what was taken.
     */
    fun take(stock: FloatArray, p: Int, want: KgM2): KgM2 {
        val t = min(stock[p], max(0f, want.v))
        val before = stock[p]
        stock[p] -= t
        land.books?.leak(Books.Stuff.MATTER, Books.ROUNDING, (t - (before - stock[p]).toDouble()) * land.areaM2[p])
        // (Each stock already came off the plants as it grew.)
        return KgM2(t)
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
        /** Wild cereals: most of a place's grass they make up; grain per kg of their stand; days ears that hold their grain keep it;
         * yearly drift back to wild where a stand seeds itself; a sown field's yield per kg sown. */
        const val CEREAL_MAX = 0.2f
        /** Yearly share of the way wild cereal stands move toward what the climate suits. */
        const val STAND_SHIFT = 0.1f
        const val GRAIN_SHARE = 0.1f
        const val GRAIN_HOLD_DAYS = 120f
        const val WILD_DRIFT = 0.3f
        const val FIELD_YIELD = 6f
        /** Share of a wild stand whose ears hold their grain (rare mutants, kept down by their failing to sow themselves). */
        const val MUTANT = 0.003f
        const val SEED_DAYS = 40f
        /** Roots and mast to be dug, per kg/m² of herbs (and trees × 0.05); days to replace them. */
        const val ROOTS_SHARE = 0.0003f
        const val ROOTS_DAYS = 180f
        /** Small finds a day in season: honey per kg/m² of shrubs (and trees) at full pollination, truffles and snails per kg/m² of herbs,
         * eggs per kg of birds; days they keep. */
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
         * Share of a place's food [s] can get at, given the share of it that is crag (slopes over 45%):
         * the crags keep all but the climbers off, which is what leaves the ibex its cliffs; the
         * meadows, plateaus and gentler slopes between them are open to any grazer. (It was reckoned
         * from a place's ruggedness, which in the mountains is high even where the pasture is good,
         * and kept grazers off two thirds of the uplands.)
         */
        fun access(s: Species, crag: Float): Float {
            val climber = max(0f, s.habitat.rugged)
            return 1f - (1f - min(1f, climber)) * crag.coerceIn(0f, 1f)
        }
    }
}
