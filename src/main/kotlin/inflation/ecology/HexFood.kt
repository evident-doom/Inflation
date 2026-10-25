package inflation.ecology

import inflation.StockName
import inflation.ecology.HexSmallLife.DeadPool
import inflation.Books
import inflation.Stocks
import inflation.KgM2
import inflation.world.HexDrinkingWater
import inflation.world.Biome
import inflation.world.HexLand
import inflation.world.LifeForm
import inflation.world.MastTrees
import inflation.world.Weather
import kotlin.math.max
import kotlin.math.min
import inflation.world.Date
import java.util.stream.IntStream

/**
 * The plant food that is not simply the standing green: stocks that build up and are eaten down, in
 * kg/m² of each place, as in the 1 km model.
 *
 * - Fruit, berries, seeds and nuts on shrubs and trees: ripen from midsummer to autumn, as many as
 *   the spring's flowers were pollinated for; what is not eaten falls and rots into the litter.
 * - Bulbs and roots that rooting animals and people dig up, from the herbs, slow to replace: none to be had while the
 *   ground is frozen.
 * - Mast: the trees' fruit (acorns, nuts) fallen in autumn, lying on the ground through the winter, picked up, not dug.
 *
 * (The standing dead grass is not one of them: it is the cured grass, grazed as the grass is, see [HexPlants.nourishment].)
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
    val roots = perPlace(n) { p -> rootsCap(p) * 0.7f }
    /** Fallen mast, kg/m²: acorns and nuts on the ground, the trees' share of the fallen [fruit]; rots or sprouts as old roots rot. */
    val mast = FloatArray(n)
    /**
     * This week, whether the ground at each place is frozen too hard to dig: a week's mean air below [HexDrinkingWater.THAW_C],
     * the rule a soak in a dry bed goes by. Bulbs and roots cannot be had then; the mast lying on it can.
     */
    @Transient private val frozen = BooleanArray(n)
    /** The bulbs and roots at [p] that can be dug this week, kg/m²: none while the ground is frozen. */
    fun diggable(p: Place) = if (frozen[p]) 0f else roots[p]
    /** What rooting animals find under the trees and in the ground at [p] this week, kg/m²: the roots they can dig and the mast. */
    fun underground(p: Place) = diggable(p) + mast[p]
    /** Takes up to [want] of what is [underground] at [p], from the roots and the mast as each makes of it; returns what was taken. */
    fun takeUnderground(p: Place, want: KgM2): KgM2 {
        val all = underground(p); if (all <= 0f || want.v <= 0f) return KgM2(0f)
        val w = min(want.v, all); val fromRoots = w * diggable(p) / all
        return KgM2(take(roots, p, KgM2(fromRoots)).v + take(mast, p, KgM2(w - fromRoots)).v)
    }
    /**
     * Wild cereals: large-seeded annual grasses (wild wheat and barley here), as a share of a place's
     * grass, where winters are wet, summers dry and the ground broken (the hilly flanks). Their ripe
     * grain, kg/m², is shed within weeks by ears that shatter; [domestic] is the share of the stand
     * whose ears hold their grain (0 wild: rare mutants, [MUTANT]), which drifts back there where the
     * stand seeds itself. [field] is grain sown here this year, kg/m², which ripens to
     * [FIELD_YIELD] times as much into [fieldGrain], a crop of its own: the share of its ears that hold their grain is
     * [fieldDomestic], what was sown, not the wild stand's about it (a sown plot is reaped as a plot, not as a share of the
     * hillside's stand).
     */
    val cereal = perPlace(n) { p -> cerealShare(p) }
    val grain = FloatArray(n)
    val domestic = FloatArray(n) { p -> if (cereal[p] > 0f) MUTANT else 0f }
    val field = FloatArray(n)
    val fieldGrain = FloatArray(n)
    val fieldDomestic = FloatArray(n)
    /**
     * How short of water a sown field has been since midwinter: the days it has stood ([fieldDays]) and the sum over
     * them of its water stress ([waterStress]), so that a dry season ripens less of it.
     */
    val fieldDays = FloatArray(n)
    val fieldStress = FloatArray(n)
    /**
     * The ground worn by cropping at each place: [fieldWorn] the share of the place's land worn, [fieldSoil] how well that ground
     * bears against rested ground, 0..1. Ground cropped again wears further ([CROP_WEAR], toward [CROPPED_FLOOR]); worn ground
     * left to rest mends ([REST_MEND]) and, mended, is rested ground again. A field is sown on rested ground first, so only a
     * place whose fields cover most of it has to crop worn ground ([cropSoil]).
     */
    val fieldWorn = FloatArray(n)
    val fieldSoil = FloatArray(n) { 1f }
    /** The share wild cereals would make of a place's grass in its climate, colder by [dT] and with [rainShare] of its rain. */
    private fun cerealShare(p: Place, dT: Float = 0f, rainShare: Float = 1f): Float {
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

    /**
     * The share of [p]'s trees that bear food (oaks, beech, hazel, wild fruit trees), by its kinds of country ([FOOD_TREES]),
     * for each group of trees whose crops go together ([MastTrees]). (Fixed by the world, so not taken from a saved run.)
     */
    @Transient private val foodTrees = Array(MastTrees.entries.size) { FloatArray(n) }.also { by ->
        for (p in 0 until n) {
            val h = land.hw.placeHex[p]
            var w = 0f
            for (b in Biome.entries) { val share = land.hw.biome[b.ordinal][h]; if (share <= 0f) continue; w += share
                val (t, bears) = FOOD_TREES[b] ?: continue; by[t.ordinal][p] += share * bears }
            if (w > 0f) for (t in by) t[p] /= w
        }
    }
    /** The fruiting share of [p]'s trees this year, kg/m²: the food-bearing trees ([foodTrees]) of each group in its year's mast crop ([Weather.mastCrop]). */
    private fun treeBearing(p: Place, crop: FloatArray): Float {
        var s = 0f
        for (t in crop.indices) s += foodTrees[t][p] * crop[t]
        return TREE_BEARING * s * land.plants.tree[p]
    }
    /**
     * Fruit-bearing plants at [p] in a year of mast crop [crop], kg/m²: the edible share of the shrubs ([HexLand.palatable])
     * and the fruiting share of trees ([treeBearing]).
     */
    private fun bearing(p: Place, crop: FloatArray) = land.palatable[p] * 0.5f * land.plants.shrub[p] + treeBearing(p, crop)
    private fun rootsCap(p: Place) = ROOTS_SHARE * land.palatable[p] * land.plants.herb[p] + REED_ROOTS * land.waterlogged[p]

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
                IntStream.range(0, n).parallel().forEachPlace { p -> cereal[p] += STAND_SHIFT * (cerealShare(p, dT, rain) - cereal[p]) } }
        }
        IntStream.range(0, n).parallel().forEachPlace { p ->
            // The season as the plants feel it: the share of a normal year's warmth they have had since
            // midwinter. Seed ripens early, fruit from midsummer to autumn, roots and bulbs fill as the
            // plants die back; a warm year brings all of it on earlier, high ground later.
            val season = land.plants.gdd[p] / land.plants.annualGdd[p].coerceAtLeast(1f)
            frozen[p] = weather.temperature[p] <= HexDrinkingWater.THAW_C
            // Fruit ripens from midsummer to autumn, as many as the spring's flowers were pollinated
            // for and the spring was wet enough to fill; then it falls: the shrubs' berries rot, the trees'
            // acorns and nuts lie on the ground as mast for rooting animals through the winter.
            val ripe = if (season in FRUIT_FROM..FRUIT_TO) FRUIT_SHARE * bearing(p, weather.mastCrop) * small.fruitSet(p) *
                min(1f, land.plants.moisture[p] / 0.5f) / FRUIT_DAYS else 0f
            val fallen = fruit[p] * min(1f, days / FRUIT_DAYS)
            // (It grows on the shrubs and trees that bear it, and comes off them.)
            val shrubBears = land.palatable[p] * 0.5f * land.plants.shrub[p]; val treeBears = treeBearing(p, weather.mastCrop)
            val fromShrub = if (shrubBears + treeBears > 0f) shrubBears / (shrubBears + treeBears) else 1f
            val newFruit = days * ripe
            val bore = if (newFruit > 0f) (land.plants.consume(p, LifeForm.SHRUB, KgM2(newFruit * fromShrub)) +
                land.plants.consume(p, LifeForm.TREE, KgM2(newFruit * (1f - fromShrub)))).v else 0f
            fruit[p] = max(0f, fruit[p] + bore - fallen)
            small.deposit(DeadPool.LITTER, p, KgM2(fallen * fromShrub))
            // Grass seed ripens in early summer as the grass dries, and is shed over the following weeks.
            val ripeSeed = if (season in SEED_FROM..SEED_TO) SEED_SHARE * land.palatable[p] * land.plants.herb[p] / SEED_DAYS else 0f
            val shed = seeds[p] * min(1f, days / SEED_DAYS)
            seeds[p] = max(0f, seeds[p] + land.plants.consume(p, LifeForm.HERB, KgM2(days * ripeSeed)).v - shed)
            small.deposit(DeadPool.LITTER, p, KgM2(shed))
            // (Roots and bulbs fill from the plants above them; old ones rot into the litter, as the mast does.)
            val r = rootsCap(p) * (if (season >= ROOTS_FROM) 3f else 0.5f) / ROOTS_DAYS
            val rotted = roots[p] * min(1f, days / ROOTS_DAYS)
            roots[p] = max(0f, roots[p] + land.plants.consume(p, LifeForm.HERB, KgM2(days * r)).v - rotted)
            val mastRotted = mast[p] * min(1f, days / ROOTS_DAYS)
            mast[p] = max(0f, mast[p] + fallen * (1f - fromShrub) - mastRotted)
            small.deposit(DeadPool.LITTER, p, KgM2(rotted + mastRotted))
            // Wild cereals ripen with the other grasses; ears that shatter shed their grain within weeks,
            // those that hold it keep it for months. Sown fields ripen with them.
            // (A sown field counts the water it is short of, week by week, till it ripens.)
            if (field[p] > 0f && season < SEED_TO) { fieldDays[p] += days; fieldStress[p] += waterStress(p) * days }
            if (cereal[p] > 0f || field[p] > 0f || fieldGrain[p] > 0f) {
                // (As the season advances: a cold year that never reaches its full warmth ripens only part of its grain.)
                val advance = max(0f, weather.temperature[p] - 5f) * days / land.plants.annualGdd[p].coerceAtLeast(1f)
                val inWindow = (min(season, SEED_TO) - max(season - advance, SEED_FROM)).coerceAtLeast(0f)
                if (inWindow > 0f) {
                    val share = inWindow / (SEED_TO - SEED_FROM)
                    val ripe = land.plants.consume(p, LifeForm.HERB, KgM2(GRAIN_SHARE * cereal[p] * land.plants.herb[p] * share)).v
                    val sownRipe = land.plants.consume(p, LifeForm.HERB, KgM2(fieldExpect(p) * field[p] * share)).v
                    grain[p] += ripe; fieldGrain[p] += sownRipe
                    ripenedNow[p] += ripe + sownRipe
                }
                val lost = grain[p] * shedShare(domestic[p])
                grain[p] = max(0f, grain[p] - lost); small.deposit(DeadPool.LITTER, p, KgM2(lost))
                val fieldLost = fieldGrain[p] * shedShare(fieldDomestic[p])
                fieldGrain[p] = max(0f, fieldGrain[p] - fieldLost); small.deposit(DeadPool.LITTER, p, KgM2(fieldLost))
                // After the year's ripening, the sown crop is spent, and a stand that seeded itself
                // drifts back toward the wild (ears that hold their grain cannot sow themselves). A crop that has not
                // ripened by the end of summer has failed. (Not at midwinter: what was sown in autumn stands through the
                // winter and ripens the next summer.)
                if ((season > SEED_TO && season - advance <= SEED_TO) || (weather.date.month == FIELD_FAILS_MONTH && weather.date.dayOfMonth <= days && field[p] > 0f)) {
                    wearGround(p)
                    field[p] = 0f; fieldDays[p] = 0f; fieldStress[p] = 0f
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
            small.deposit(DeadPool.LITTER, p, KgM2(spoilt))
            finds[p] = max(0f, finds[p] + grown - spoilt)
        }
    }

    /** Sows [kg] of grain at [p], of which [domestic] are of ears that hold their grain: the field's crop takes after what was sown in it. */
    fun sow(p: Place, kg: KgM2, domestic: Float) {
        val add = max(0f, kg.v)
        if (add <= 0f) return
        fieldDomestic[p] = (fieldDomestic[p] * field[p] + domestic * add) / (field[p] + add)
        field[p] += add
    }

    /**
     * What a kilo sown at [p] ripens to as its field stands now: [FIELD_YIELD], less as the field has gone short of water since
     * midwinter ([waterStress]) and on the ground it is sown on ([cropSoil]).
     */
    fun fieldExpect(p: Place): Float = FIELD_YIELD * (if (fieldDays[p] > 0f) fieldStress[p] / fieldDays[p] else 1f) * cropSoil(p)

    /** The share of [p]'s land under this year's field: grain sown at [SEED_RATE] (thicker sowing covers no more than the place). */
    private fun sownShare(p: Place) = min(1f, field[p] / SEED_RATE)

    /**
     * How well this year's field at [p] bears against rested ground: sown on rested ground as far as there is any, the rest on
     * the worn ground ([fieldSoil]).
     */
    fun cropSoil(p: Place): Float {
        val s = sownShare(p)
        if (s <= 0f) return 1f
        val fresh = min(s, 1f - fieldWorn[p])
        return (fresh + (s - fresh) * fieldSoil[p]) / s
    }

    /**
     * After the year's crop at [p]: rested ground it was sown on is worn now, worn ground cropped again wears further, and worn
     * ground left to rest mends, the mended part rested ground again (its loss shrinks as [REST_MEND] says either way).
     */
    private fun wearGround(p: Place) {
        val s = sownShare(p); val worn = fieldWorn[p]
        val fresh = min(s, 1f - worn); val again = min(s - fresh, worn); val rested = (worn - again) * (1f - REST_MEND)
        val freshSoil = CROPPED_FLOOR + (1f - CROPPED_FLOOR) * (1f - CROP_WEAR)
        val againSoil = CROPPED_FLOOR + (fieldSoil[p] - CROPPED_FLOOR) * (1f - CROP_WEAR)
        val w = fresh + again + rested
        fieldSoil[p] = if (w > 0f) (fresh * freshSoil + again * againSoil + rested * fieldSoil[p]) / w else 1f
        fieldWorn[p] = w
    }

    /**
     * How fully a crop at [p] can draw water this week, 0..1: all it needs while the soil holds more than [CROP_DEPLETION]
     * of the water it can give plants up, then less in proportion to what is left (FAO-56 water-stress coefficient Ks, with
     * the depletion fraction of wheat; a grain crop's yield falls about as its water use does, Ky about 1).
     */
    private fun waterStress(p: Place): Float {
        val cap = land.hydro.soilCap[p]
        return if (cap <= 0f) 0f else (land.hydro.soil[p] / ((1f - CROP_DEPLETION) * cap)).coerceIn(0f, 1f)
    }

    /** The share of ripe grain shed this week by a stand whose ears hold their grain as [domestic] (0 wild) of them. */
    private fun shedShare(domestic: Float) = (1f - domestic) * min(1f, days / SEED_DAYS) + domestic * min(1f, days / GRAIN_HOLD_DAYS)

    /** Names the food stocks: fruit, grass seed, small finds, roots, mast, grain, sown seed and the grain of sown fields. */
    internal fun listStocks(stocks: Stocks) {
        for ((name, a) in listOf(StockName.FRUIT to fruit, StockName.GRASS_SEED to seeds, StockName.SMALL_FINDS to finds, StockName.ROOTS to roots, StockName.MAST to mast, StockName.GRAIN to grain, StockName.SOWN_SEED to field,
            StockName.FIELD_GRAIN to fieldGrain))
            stocks.perM2(name, a, land.areaM2)
    }

    /**
     * Takes up to [want] from [stock] (kg/m² by place) at [p]; returns what was taken.
     */
    fun take(stock: FloatArray, p: Place, want: KgM2): KgM2 {
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
        /**
         * Share of the water a soil can give plants that a grain crop draws down before it goes short: 0.55 for wheat (Allen,
         * Pereira, Raes & Smith 1998, FAO Irrigation and Drainage Paper 56, table 22); yield falls about one for one with the
         * water use lost (Ky 1.0 for wheat: Doorenbos & Kassam 1979, FAO Paper 33). Both figures from memory, unchecked.
         */
        const val CROP_DEPLETION = 0.55f
        /**
         * How a plot cropped year after year bears against rested ground, and how fast it wears and mends. Broadbalk's unmanured
         * plot gives about 1.2 t/ha of grain in continuous wheat and 2.6 t/ha after a two-year break (Rothamsted guide 1991,
         * e-RA guide1991-10-16), so cropping wears a plot to about 0.45 of rested ground and two years' rest mend most of it (0.7
         * a year leaves a tenth of the loss after two). How fast it wears is not in that record: half the way a year is a guess.
         */
        const val CROPPED_FLOOR = 0.45f
        const val CROP_WEAR = 0.5f
        const val REST_MEND = 0.7f
        /**
         * Grain sown per m² of field: about 120 kg/ha, after Columella's 4-5 modii of wheat to the iugerum (about 110-135 kg/ha)
         * and medieval English seed rates of about 2 bushels an acre (both from memory, unchecked).
         */
        const val SEED_RATE = 0.012f
        /** The month (0 from midwinter) by whose start a sown crop that has not ripened has failed: the last before the autumn sowing. */
        const val FIELD_FAILS_MONTH = 8
        /** Share of a wild stand whose ears hold their grain (rare mutants, kept down by their failing to sow themselves). */
        const val MUTANT = 0.003f
        const val SEED_DAYS = 40f
        /**
         * Fruit-bearing share of the trees: mast (acorns, nuts) a year of about 20 kg/ha per kg/m² of trees, some
         * 200 kg/ha in a woodland of 10 kg/m² (oak stands in Moravia bore 182-667 kg/ha of acorns, dry, in 2013; wild
         * boar ate over 90% of it: Zeman et al. 2016, Acta Univ. Agric. Silvic. Mendel. Brun. 64:579).
         */
        const val TREE_BEARING = 1.25f
        /**
         * The share of the trees that bear food people and beasts eat, by kind of country (an estimate, by the trees that
         * make them up; no measured shares were found): oak, beech, hazel and wild fruit in broadleaved and Mediterranean
         * woods and in open oak woodland; oak, elm and wild fruit among the alder, willow, ash and poplar of floodplain forest;
         * pistachio, wild almond and oak scattered on steppe and in scrub; little in conifer forest (a few stone pines and
         * hazel), none in alder swamp, mangrove or bog. Spruce, fir and larch seed feeds no one here. Each kind of country's
         * trees go with one group whose crops boom and bust together ([MastTrees]).
         */
        val FOOD_TREES: Map<Biome, Pair<MastTrees, Float>> = mapOf(
            Biome.TEMPERATE_FOREST to (MastTrees.TEMPERATE to 1f), Biome.OPEN_WOODLAND to (MastTrees.TEMPERATE to 1f),
            Biome.FLOODPLAIN_FOREST to (MastTrees.TEMPERATE to 0.3f),
            Biome.MEDITERRANEAN_WOODLAND to (MastTrees.DRY to 1f), Biome.STEPPE to (MastTrees.DRY to 0.5f),
            Biome.SHRUBLAND to (MastTrees.DRY to 0.5f), Biome.SEMI_DESERT to (MastTrees.DRY to 0.3f), Biome.DESERT to (MastTrees.DRY to 0.3f),
            Biome.CONIFER_FOREST to (MastTrees.CONIFER to 0.1f), Biome.ALPINE_MEADOW to (MastTrees.CONIFER to 0.1f),
        )
        /**
         * Bulbs, corms, tubers and rhizomes to be dug, per kg/m² of herbs; days to replace them, and for fallen mast to
         * rot or sprout. About 1-3 g/m² dry under 0.15-0.3 kg/m² of herbs: edible underground storage organs in the southern
         * Cape's shrublands, sampled at random, came to 52-160 kg/ha fresh by kind of country (Singels 2013, thesis,
         * Stellenbosch University; taken here as about 30% dry, from memory), and the Cape is the richest in bulbs there is.
         */
        const val ROOTS_SHARE = 0.01f
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
