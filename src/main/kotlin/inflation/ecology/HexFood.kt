package inflation.ecology

import inflation.world.StepClock
import inflation.Par
import inflation.world.Place
import inflation.world.perPlace
import inflation.world.get
import inflation.world.set

import inflation.StockName
import inflation.ecology.HexSmallLife.DeadPool
import inflation.Books
import inflation.Stocks
import inflation.KgM2
import inflation.EnumFloats
import inflation.enumTables
import inflation.world.HexDrinkingWater
import inflation.world.Biome
import inflation.world.HexLand
import inflation.world.LifeForm
import inflation.world.MastTrees
import inflation.world.Weather
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import inflation.world.Date

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
class HexFood(private val land: HexLand, private val small: HexSmallLife, @Transient private val clock: StepClock) {
    /** Days the step covers ([StepClock]). */
    private val days get() = clock.days

    /**
     * What a stock of [stock] loses over the step, losing [perTick] of itself a tick ([lossShare] of a tick's days) while
     * [inflow] comes in evenly over the step: as the ticks one after another would have it. At one tick a step, [perTick] of it.
     */
    private fun lostOver(stock: Float, inflow: Float, perTick: Float): Float {
        if (clock.ticks == 1) return stock * perTick
        val n = clock.ticks; val a = perTick.coerceIn(0f, 1f); val keep = (1f - a).pow(n)
        val end = keep * stock + (if (a > 0f) inflow / n * (1f - keep) / a else inflow)
        return max(0f, stock + inflow - end)
    }
    /** The share of its stock a store that loses [days]/[lifeDays] of itself loses in a tick, at most all of it. */
    private fun lossShare(lifeDays: Float) = min(1f, clock.tickDays / lifeDays)
    /** The share lost over the step of a stock losing [perTick] a tick with nothing coming in: [perTick] at one tick a step. */
    private fun overTicks(perTick: Float) = clock.share(perTick)
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
    /** Fallen mast, kg/m²: acorns and nuts on the ground, the trees' crop as it drops ([fruit] is the shrubs' alone); rots or sprouts as old roots rot. */
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
        return KgM2(take(roots, p, KgM2(fromRoots)).v + takeMast(MastFate.ANIMALS, p, KgM2(w - fromRoots)).v)
    }
    /** What becomes of the mast, for the census: kg over all the land since the census last read it ([mastLedger]). */
    enum class MastFate { FELL, PEOPLE, ANIMALS, SMALL_LIFE, ROTTED }
    /** (Adders: the places' weekly step and the eaters book into it from parallel loops.) */
    @Transient val mastLedger = Array(MastFate.entries.size) { java.util.concurrent.atomic.DoubleAdder() }
    /** Takes mast at [p] as [take] does, booked to [by] in [mastLedger]. */
    fun takeMast(by: MastFate, p: Place, want: KgM2): KgM2 =
        take(mast, p, want).also { mastLedger[by.ordinal].add(it.v.toDouble() * land.areaM2[p.i]) }
    /**
     * Wild cereals: large-seeded annual grasses (wild wheat and barley here), as a share of a place's
     * grass, where winters are wet, summers dry and the ground broken (the hilly flanks). Their ripe
     * grain, kg/m², is shed within weeks by ears that shatter; [domestic] is the share of the stand
     * whose ears hold their grain (0 wild: rare mutants, [MUTANT]), which drifts back there where the
     * stand seeds itself. [field] is grain sown here this year, kg/m², which ripens to
     * [yieldPerKg] times as much into [fieldGrain], a crop of its own: the share of its ears that hold their grain is
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
     * Of the seed sown at each place this year ([field]), the share that is pulses (lentil, pea, bitter vetch: one kind here), sown on
     * the worn ground first ([PULSE_YIELD], [wearGround]); and of the ripe crop standing ([fieldGrain]), the share that is pulses.
     */
    val fieldPulse = FloatArray(n)
    val ripePulse = FloatArray(n)
    /** The group that last sowed each place's field (-1 none): it and the bands housed in the hex hold the crop ([mayReap]). */
    val fieldHolder = IntArray(n) { -1 }
    /**
     * How short of water a sown field has been since midwinter: the days it has stood ([fieldDays]) and the sum over
     * them of its water stress ([waterStress]), so that a dry season ripens less of it.
     */
    val fieldDays = FloatArray(n)
    val fieldStress = FloatArray(n)
    /**
     * The ground worn by cropping at each place: [fieldWorn] the share of the place's land worn, [fieldSoil] how well that ground
     * bears against rested ground, 0..1. Ground cropped again wears further ([CROP_WEAR], toward [CROPPED_FLOOR]); worn ground
     * left to rest mends ([REST_MEND]) and, mended, is rested ground again. A field is sown on cleared ground first, the mended
     * ground of it before the worn, so a band that clears no more ground crops worn ground again ([cerealOnWorn], [cropSoil]).
     */
    val fieldWorn = FloatArray(n)
    val fieldSoil = FloatArray(n) { 1f }
    /**
     * The share of each place's land under its sown field, as last sown ([sow]): kept after the year's sowing is struck off,
     * so that the ripe crop still standing is reaped as a plot of its own, as thick as it stands there ([fieldStand]).
     */
    val fieldCover = FloatArray(n)
    /**
     * The share of each place's land cleared of its trees and scrub for fields ([sow] keeps it at least what is sown), and of it
     * the share cropped this year ([croppedNow]): cleared ground not cropped in a year grows back by [REGROW_YEARS] (and must
     * be cleared again, [clearDaysPerHa]).
     */
    val cleared = FloatArray(n)
    private val croppedNow = FloatArray(n)
    /**
     * The share of each place's land under this year's wild cereal patches: wild cereals grow as dense stands, not spread
     * thin through the grass, so the grain that ripens at a place stands on [WILD_STAND_GRAIN] kg/m² of patch (up to the
     * place's whole [cereal] share). Grown as the grain ripens, struck off at the year's turn. Shedding and reaping thin the
     * patch's grain, not its ground, as a reaped field's ([fieldCover]) is.
     */
    val wildCover = FloatArray(n)
    /** The share of [p]'s land still to clear to sow [kg] more there, kg/m² of the place: beyond what is cleared already. */
    fun toClear(p: Place, kg: KgM2): Float = max(0f, min(1f, (field[p] + max(0f, kg.v)) / SEED_RATE) - cleared[p])
    /** The kg/m² of the place that can be sown at [p] on ground cleared already. */
    fun sowableCleared(p: Place): Float = max(0f, cleared[p] * SEED_RATE - field[p])
    /**
     * Person-days to clear a hectare at [p] of what stands there: felling and burning off its trees and scrub, [FELL_DAYS_PER_HA]
     * for each kg/m² of them (the place's own stand: the land a field is cleared on is the place's land, not its mean).
     */
    fun clearDaysPerHa(p: Place): Float = FELL_DAYS_PER_HA * (land.plants.tree[p] + land.plants.shrub[p])
    /**
     * The ripe grain as it stands in the wild cereal at [p], kg/m² of the stand: the place's grain over the ground of its
     * patches this year ([wildCover]); before any ripens this year, over the share of its grass that is wild cereal ([cereal]),
     * as thin as last year's leavings stand. A reaper cuts the stand, not the place ([reapDaysPerKg]).
     */
    fun wildStand(p: Place): Float = if (wildCover[p] > 0f) grain[p] / wildCover[p] else if (cereal[p] > 0f) grain[p] / cereal[p] else grain[p]
    /**
     * The ripe grain as it stands in the sown field at [p], kg/m² of the field: the place's field grain over the share of its
     * land sown ([fieldCover]); where that is not known (a save from before it was kept), a field as it would ripen in full.
     */
    fun fieldStand(p: Place): Float = if (fieldCover[p] > 0f) fieldGrain[p] / fieldCover[p] else yieldPerKg(0f) * SEED_RATE
    /**
     * Whether the sown field at [p] is still ripening: sown, and its season ([SEED_FROM] to [SEED_TO] of the year's warmth) not
     * yet over. A sown field is one sowing on one ground and ripens as one stand, not patch by patch as the wild cereals of a
     * place do: its grain fills over the season, sheds none before it is ripe, and is reaped once it is ([HexFood] week,
     * [inflation.ecology.cropRipe]). (Before, its grain was reaped and shed week by week as it filled, and a field reaped as
     * it filled was cut whole for each week's sixth of its grain: some six times the cutting a kilo, sow719 2026-11-12.)
     */
    fun fieldRipening(p: Place): Boolean {
        if (field[p] <= 0f) return false
        val season = land.plants.gdd[p] / land.plants.annualGdd[p].coerceAtLeast(1f)
        return season >= SEED_FROM && season < SEED_TO
    }
    /**
     * Reaps [want] of the sown grain at [p]: the reapers cut the ground they take it from, so the field's sown ground
     * ([fieldCover]) goes with its grain and what stands on the rest stands as thick as before. Shedding thins the stand, not
     * the ground. (Before, reaping thinned the stand as shedding does, so each kilo reaped made the next dearer to cut.)
     */
    fun reapField(p: Place, want: KgM2): KgM2 {
        val before = fieldGrain[p]
        val t = take(fieldGrain, p, want)
        if (before > 0f) fieldCover[p] *= fieldGrain[p] / before
        return t
    }
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
    /**
     * Where a crop can be sown at all, wild cereals or not ([canCrop]): [CROP_RAIN_MM] of rain a year and [CROP_GDD] degree-days
     * above 5 °C; and where winters are too cold for an autumn-sown crop to live through ([SPRING_BELOW_C]), so it is sown in
     * spring ([springSown]: [SPRING_YIELD]). (Fixed by the world's climate, so not taken from a saved run.)
     */
    @Transient private var cropClimate: BooleanArray? = null
    @Transient private var springCold: BooleanArray? = null
    private fun cropClimate(): BooleanArray = cropClimate ?: BooleanArray(n) { p ->
        val h = land.hw.placeHex[p]
        (0 until 12).sumOf { land.hw.precip[it][h].toDouble() } >= CROP_RAIN_MM && land.plants.annualGdd[p] >= CROP_GDD
    }.also { cropClimate = it }
    /** Whether [p]'s winters are too cold for an autumn-sown crop, so it is sown in spring ([SPRING_BELOW_C]). */
    fun springSown(p: Place): Boolean = (springCold ?: BooleanArray(n) { q -> (0 until 12).minOf { land.hw.placeTemp(Place(q), it) } < SPRING_BELOW_C }
        .also { springCold = it })[p.i]
    /** Whether a crop can be sown at [p]: where wild cereals grow, or where the climate lets a crop ripen ([cropClimate]). */
    fun canCrop(p: Place) = cereal[p] > 0f || cropClimate()[p.i]
    /** What [p]'s crop bears for its season: [SPRING_YIELD] where it is sown in spring ([springSown]), else all. */
    fun seasonYield(p: Place) = if (springSown(p)) SPRING_YIELD else 1f

    @Transient private val foodTrees = enumTables<MastTrees>(n).also { by ->
        for (p in 0 until n) {
            val h = land.hw.placeHex[p]
            var w = 0f
            for (b in Biome.entries) { val share = land.hw.biome[b.ordinal][h]; if (share <= 0f) continue; w += share
                val (t, bears) = FOOD_TREES[b] ?: continue; by[t][p] += share * bears }
            if (w > 0f) for (t in by.a) t[p] /= w
        }
    }
    /** The fruiting share of [p]'s trees this year, kg/m²: the food-bearing trees ([foodTrees]) of each group in its year's mast crop ([Weather.mastCrop]). */
    private fun treeBearing(p: Place, crop: EnumFloats<MastTrees>): Float {
        var s = 0f
        for (t in crop.a.indices) s += foodTrees.a[t][p] * crop.a[t]
        return TREE_BEARING * s * land.plants.tree[p]
    }
    /**
     * Fruit-bearing plants at [p] in a year of mast crop [crop], kg/m²: the edible share of the shrubs ([HexLand.palatable])
     * and the fruiting share of trees ([treeBearing]).
     */
    private fun bearing(p: Place, crop: EnumFloats<MastTrees>) = land.palatable[p] * 0.5f * land.plants.shrub[p] + treeBearing(p, crop)
    private fun rootsCap(p: Place) = ROOTS_SHARE * land.palatable[p] * land.plants.herb[p] + REED_ROOTS * land.waterlogged[p]

    private var standYear = -1
    /** Grain ripened at each place so far this year, kg/m², and over all the land in the last whole year, t. */
    private val ripenedNow = FloatArray(n)
    var grainRipenedLastYear = 0.0; private set
    /**
     * Where the sown fields' grain went, kg/m² so far this year at each place ([FieldFate]), and over all the land in the last
     * whole year, t: for the census only.
     */
    /**
     * For the census: fruit borne at each place over the year so far, kg/m², off the shrubs and off the trees (the trees'
     * falls as mast), and over the last whole year. Not saved: a loaded world counts from its first new year.
     */
    @Transient private val shrubFruitNow = FloatArray(n)
    @Transient private val treeFruitNow = FloatArray(n)
    @Transient val shrubFruitLastYear = FloatArray(n)
    @Transient val treeFruitLastYear = FloatArray(n)
    @Transient var fruitYears = 0; private set
    private val fieldFateNow = Array(FieldFate.entries.size) { FloatArray(n) }
    val fieldFateLastYear = DoubleArray(FieldFate.entries.size)
    /** What becomes of a sown field's grain in a year: the seed whose crop is spent, what it would ripen to in full, what its water and ground let it ripen to, what ripened, what was shed. */
    enum class FieldFate { SEED, FULL, EXPECTED, RIPENED, SHED }
    fun step(weather: Weather) {
        // Once a year the wild cereal stands follow the climate: they spread where it suits them and
        // die back where it no longer does, over decades (a long cold, dry spell shrinks them).
        val year = (weather.date.absoluteDay / Date.DAYS_PER_YEAR).toInt()
        if (year != standYear) {
            val first = standYear < 0; standYear = year
            if (!first) grainRipenedLastYear = (0 until n).sumOf { (ripenedNow[it] * land.areaKm2[it] * 1e6f).toDouble() } / 1000
            ripenedNow.fill(0f)
            if (!first) { shrubFruitNow.copyInto(shrubFruitLastYear); treeFruitNow.copyInto(treeFruitLastYear); fruitYears++ }
            shrubFruitNow.fill(0f); treeFruitNow.fill(0f)
            wildCover.fill(0f)
            // (Cleared ground not cropped in the year grows back toward the place's woods and scrub.)
            if (!first) for (p in 0 until n) cleared[p] = croppedNow[p] + max(0f, cleared[p] - croppedNow[p]) * (1f - 1f / REGROW_YEARS)
            croppedNow.fill(0f)
            for (k in FieldFate.entries.indices) {
                if (!first) fieldFateLastYear[k] = (0 until n).sumOf { (fieldFateNow[k][it] * land.areaKm2[it] * 1e6f).toDouble() } / 1000
                fieldFateNow[k].fill(0f)
            }
            if (!first) { val (dT, rain) = weather.shock()
                Par.forEachPlace("food: cereal stands", n) { p -> cereal[p] += STAND_SHIFT * (cerealShare(p, dT, rain) - cereal[p]) } }
        }
        Par.forEachPlace("food: week", n) { p ->
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
            // (It grows on the shrubs and trees that bear it, and comes off them.)
            val shrubBears = land.palatable[p] * 0.5f * land.plants.shrub[p]; val treeBears = treeBearing(p, weather.mastCrop)
            val fromShrub = if (shrubBears + treeBears > 0f) shrubBears / (shrubBears + treeBears) else 1f
            val newFruit = days * ripe
            val bore = if (newFruit > 0f) (land.plants.consume(p, LifeForm.SHRUB, KgM2(newFruit * fromShrub)) +
                land.plants.consume(p, LifeForm.TREE, KgM2(newFruit * (1f - fromShrub)))).v else 0f
            shrubFruitNow[p] += bore * fromShrub; treeFruitNow[p] += bore * (1f - fromShrub)
            // (The trees' crop, acorns and nuts, is mast from the start: gathered as it drops and shelled, its bitter share
            // leached, never picked off the bough as berries are. It was counted as fruit while it hung and picked at the
            // berry rate with no leaching: some 95% of the "fruit" at the places bands live, bears 2026-11-10.)
            val shrubIn = bore * fromShrub; val treeIn = bore - shrubIn
            val fallen = lostOver(fruit[p], shrubIn, lossShare(FRUIT_DAYS))
            fruit[p] = max(0f, fruit[p] + shrubIn - fallen)
            small.deposit(DeadPool.LITTER, p, KgM2(fallen))
            // Grass seed ripens in early summer as the grass dries, and is shed over the following weeks.
            val ripeSeed = if (season in SEED_FROM..SEED_TO) SEED_SHARE * land.palatable[p] * land.plants.herb[p] / SEED_DAYS else 0f
            val seedIn = land.plants.consume(p, LifeForm.HERB, KgM2(days * ripeSeed)).v
            val shed = lostOver(seeds[p], seedIn, lossShare(SEED_DAYS))
            seeds[p] = max(0f, seeds[p] + seedIn - shed)
            small.deposit(DeadPool.LITTER, p, KgM2(shed))
            // (Roots and bulbs fill from the plants above them; old ones rot into the litter, as the mast does.)
            val r = rootsCap(p) * (if (season >= ROOTS_FROM) 3f else 0.5f) / ROOTS_DAYS
            val rootsIn = land.plants.consume(p, LifeForm.HERB, KgM2(days * r)).v
            val rotted = lostOver(roots[p], rootsIn, lossShare(ROOTS_DAYS))
            roots[p] = max(0f, roots[p] + rootsIn - rotted)
            val mastRotted = lostOver(mast[p], treeIn, lossShare(ROOTS_DAYS))
            mastLedger[MastFate.FELL.ordinal].add(treeIn.toDouble() * land.areaM2[p]); mastLedger[MastFate.ROTTED.ordinal].add(mastRotted.toDouble() * land.areaM2[p])
            mast[p] = max(0f, mast[p] + treeIn - mastRotted)
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
                    val expected = fieldExpect(p) * field[p] * share
                    val sownRipe = land.plants.consume(p, LifeForm.HERB, KgM2(expected)).v
                    if (sownRipe > 0f) ripePulse[p] = (ripePulse[p] * fieldGrain[p] + pulseOfRipe(p) * sownRipe) / (fieldGrain[p] + sownRipe)
                    grain[p] += ripe; fieldGrain[p] += sownRipe
                    wildCover[p] = min(cereal[p], wildCover[p] + ripe / WILD_STAND_GRAIN)
                    ripenedNow[p] += ripe + sownRipe
                    fieldFateNow[FieldFate.FULL.ordinal][p] += yieldPerKg(fieldDomestic[p]) * seasonYield(p) * (1f - fieldPulse[p] * (1f - PULSE_YIELD)) * field[p] * share
                    fieldFateNow[FieldFate.EXPECTED.ordinal][p] += expected
                    fieldFateNow[FieldFate.RIPENED.ordinal][p] += sownRipe
                }
                val lost = grain[p] * shedShare(domestic[p])
                grain[p] = max(0f, grain[p] - lost); small.deposit(DeadPool.LITTER, p, KgM2(lost))
                val fieldLost = if (fieldRipening(p)) 0f else fieldGrain[p] * shedShare(fieldDomestic[p])
                fieldGrain[p] = max(0f, fieldGrain[p] - fieldLost); small.deposit(DeadPool.LITTER, p, KgM2(fieldLost))
                fieldFateNow[FieldFate.SHED.ordinal][p] += fieldLost
                // After the year's ripening, the sown crop is spent, and a stand that seeded itself
                // drifts back toward the wild (ears that hold their grain cannot sow themselves). A crop that has not
                // ripened by the end of summer has failed. (Not at midwinter: what was sown in autumn stands through the
                // winter and ripens the next summer.)
                if ((season > SEED_TO && season - advance <= SEED_TO) || (weather.date.holdsMonthStart(days, FIELD_FAILS_MONTH) && field[p] > 0f)) {
                    wearGround(p)
                    fieldFateNow[FieldFate.SEED.ordinal][p] += field[p]
                    field[p] = 0f; fieldDays[p] = 0f; fieldStress[p] = 0f; fieldPulse[p] = 0f
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
            val spoilt = lostOver(finds[p], grown, lossShare(FINDS_DAYS))
            small.deposit(DeadPool.LITTER, p, KgM2(spoilt))
            finds[p] = max(0f, finds[p] + grown - spoilt)
        }
    }

    /**
     * Sows [kg] of seed at [p], of which [domestic] are of ears that hold their grain and [pulse] are pulses: the field's crop takes
     * after what was sown in it.
     */
    fun sow(p: Place, kg: KgM2, domestic: Float, pulse: Float = 0f, by: Int = -1) {
        val add = max(0f, kg.v)
        if (add <= 0f) return
        fieldHolder[p] = by
        fieldDomestic[p] = (fieldDomestic[p] * field[p] + domestic * add) / (field[p] + add)
        fieldPulse[p] = (fieldPulse[p] * field[p] + pulse * add) / (field[p] + add)
        field[p] += add
        fieldCover[p] = sownShare(p)
        cleared[p] = max(cleared[p], fieldCover[p]); croppedNow[p] = max(croppedNow[p], fieldCover[p])
    }

    /**
     * What a kilo sown at [p] ripens to as its field stands now: [yieldPerKg] of the strain sown, less as the field has gone short of water since
     * midwinter ([waterStress]); its cereal by the ground it is sown on ([cropSoil]), its pulses [PULSE_YIELD] of that on any ground
     * (they feed themselves nitrogen).
     */
    fun fieldExpect(p: Place): Float = yieldPerKg(fieldDomestic[p]) * seasonYield(p) * (if (fieldDays[p] > 0f) fieldStress[p] / fieldDays[p] else 1f) * sownMix(p)

    /** What a kilo of [p]'s sown seed bears against cereal on rested ground: its cereal by [cropSoil], its pulses [PULSE_YIELD]. */
    private fun sownMix(p: Place) = (1f - fieldPulse[p]) * cropSoil(p) + fieldPulse[p] * PULSE_YIELD

    /** Of what [p]'s field ripens now, the share that is pulses ([sownMix]). */
    private fun pulseOfRipe(p: Place): Float { val m = sownMix(p); return if (m > 0f) fieldPulse[p] * PULSE_YIELD / m else 0f }

    /** The share of [p]'s land under this year's field: grain sown at [SEED_RATE] (thicker sowing covers no more than the place). */
    private fun sownShare(p: Place) = min(1f, field[p] / SEED_RATE)

    /**
     * Dung spread on [p]'s worn ground ([fieldWorn]) enough to mend [share] of the place's land: that much of it is rested ground
     * again, still cleared ([MANURE_M2_PER_HEAD]).
     */
    fun manure(p: Place, share: Float) { fieldWorn[p] = max(0f, fieldWorn[p] - max(0f, share)) }

    /**
     * How well this year's cereal at [p] bears against rested ground: the worn ground of it ([cerealOnWorn]) as worn ground
     * bears ([fieldSoil]), the rest as rested ground.
     */
    fun cropSoil(p: Place): Float {
        val s = sownShare(p) * (1f - fieldPulse[p])
        if (s <= 0f) return 1f
        val again = cerealOnWorn(p)
        return (s - again + again * fieldSoil[p]) / s
    }

    /**
     * The share of [p]'s land under this year's cereal that is worn ground cropped again. A field is sown on cleared ground first
     * ([cleared]: what costs no clearing, [sowableCleared]), and cleared ground is the worn ground ([fieldWorn]) and the ground
     * mended from it, still clear; the pulses take the worn ground first ([fieldPulse]), the cereal the mended ground, then what
     * worn ground is left, then ground cleared afresh.
     */
    private fun cerealOnWorn(p: Place): Float {
        val sown = sownShare(p); val worn = fieldWorn[p]
        val s = sown * (1f - fieldPulse[p]); val pulses = min(sown * fieldPulse[p], worn)
        val mended = max(cleared[p], worn) - worn
        return min(max(0f, s - mended), worn - pulses)
    }

    /**
     * After the year's crop at [p]: rested ground its cereal was sown on ([cerealOnWorn]) is worn now, worn ground cropped again with cereal wears
     * further, worn ground sown with pulses is rested ground again ([PULSE_MEND]), and worn ground left to rest mends, the mended
     * part rested ground again (its loss shrinks as [REST_MEND] says either way).
     */
    private fun wearGround(p: Place) {
        val sown = sownShare(p); val worn = fieldWorn[p]
        val s = sown * (1f - fieldPulse[p]); val onWorn = min(sown * fieldPulse[p], worn) * PULSE_MEND
        val again = cerealOnWorn(p); val fresh = s - again; val rested = max(0f, worn - onWorn - again) * (1f - REST_MEND)
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
    private fun shedShare(domestic: Float) = overTicks((1f - domestic) * lossShare(SEED_DAYS) + domestic * lossShare(GRAIN_HOLD_DAYS))

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
        /** Wild cereals: most of a place's grass they make up; days ears that hold their grain keep it;
         * yearly drift back to wild where a stand seeds itself; a sown field's yield per kg sown. */
        const val CEREAL_MAX = 0.2f
        /** Yearly share of the way wild cereal stands move toward what the climate suits. */
        const val STAND_SHIFT = 0.1f
        /**
         * Ripe grain per kg of the wild-cereal grass standing at ripening: 0.25, a harvest index (grain over grain and straw)
         * of 0.2. Wild einkorn, emmer and barley grown as dense stands had harvest indices of 0.17-0.22 (Preece et al. 2018,
         * Journal of Ecology 106(3), progenitor means from raw data and by species), against 0.33 as lone plants; other wild
         * grasses 0.11-0.12. Those were fertilised pot stands, so wild field stands may sit lower. It was 0.1 (index 0.09).
         */
        const val GRAIN_SHARE = 0.25f
        /**
         * Grain a dense wild cereal patch ripens, kg/m² of the patch ([wildCover]): 0.1, 1 t/ha.
         * - Harlan reaped wild einkorn at Karacadağ at some 2 kg an hour stripping by hand and some 2.5 with a flint sickle
         *   (Harlan 1967, Archaeology 20:197, as told in JSTOR Daily, "Einkorn, the new it wheat"); at the sickle's some 25 m²
         *   an hour ([CUT_M2_PER_HOUR] with [SICKLE_GAIN]) that is about 0.1 kg/m² standing.
         * - Grain yields of wild emmer and wild barley of 1-1.5 t/ha "were feasible" 10,000 years ago (Araus et al. 2007, as
         *   cited by Jaradat 2011, AJCS 5(9):1072): an estimate, not a measured stand.
         * - Natural stands of 500-800 kg/ha (Zohary 1969, from memory, unchecked).
         * Harlan's rate is the grain standing when he reaped, after some shedding, so the ripened patch may be thicker than this.
         * The place's grain is as before ([GRAIN_SHARE] of its cereal grass); only the ground it stands on is less. It stood spread
         * through the place's whole [cereal] share before, some 20-30 kg/ha where bands reaped ([REF_STAND_KG_M2]).
         */
        const val WILD_STAND_GRAIN = 0.1f
        const val GRAIN_HOLD_DAYS = 120f
        const val WILD_DRIFT = 0.3f
        /**
         * What a kilo sown ripens to on rested ground: [FIELD_YIELD] for a domestic strain (ears that hold their grain), wild
         * grain sown [WILD_SOWN_SHARE] of that, the strains between by their share ([yieldPerKg]).
         * - Domestic: Broadbalk's unmanured wheat after a two-year break gave about 2.6 t/ha (the record behind
         *   [CROPPED_FLOOR]), about 22 times [SEED_RATE]'s 120 kg/ha. Hulled wheats sown at 120 kg/ha gave 1.8-5 t/ha on burned
         *   plots at Forchtenberg, winter emmer about 80% and einkorn 60% of an old bread wheat (Rösch et al. 2017, Land 6(1):11);
         *   autumn-sown emmer at Butser gave 1:52 on its seed unmanured (Reynolds, "Mediaeval cereal yields: an empirical
         *   challenge"). 20, a hulled wheat a little under Broadbalk. It was 6 (unsourced), under every plot above.
         * - Wild grain sown: spring-sown wild emmer at Forchtenberg gave about 30% of the old bread wheat, the domestic hulled
         *   wheats sown in spring 30-40% (same source): about 0.75-1 of a domestic strain sown alike. 0.8. What wild grain
         *   loses beyond that is its shedding ([SEED_DAYS] against [GRAIN_HOLD_DAYS]).
         */
        const val FIELD_YIELD = 20f
        const val WILD_SOWN_SHARE = 0.8f
        /** What a kilo sown ripens to on rested ground, of a strain whose ears hold their grain as [domestic] of them (0 wild). */
        fun yieldPerKg(domestic: Float) = FIELD_YIELD * (WILD_SOWN_SHARE + (1f - WILD_SOWN_SHARE) * domestic)
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
         * What a kilo of pulses sown bears against a kilo of cereal on rested ground ([yieldPerKg]), by weight (a kilo of dry pulse
         * feeds about as a kilo of grain: lentils some 350 kcal/100 g, wheat 340, USDA SR from memory). Lentils yield some 1.1 t/ha
         * on the world's mean (FAO 2010) and 1.1-2.9 t/ha in trials (Najah's review of lentil cultivation), against the
         * cereal's 2.4 t/ha here ([FIELD_YIELD] at [SEED_RATE]); sown about as thick as cereal (from memory, unchecked): 0.45.
         * Pulses fix their own nitrogen, so they bear as well on worn ground as on rested.
         */
        const val PULSE_YIELD = 0.45f
        /**
         * The share of the worn ground under pulses that is rested ground again after their year. At Butser, spring emmer after
         * legumes gave 3.13 t/ha against 2.57 after fallow and 1.77 unmanured (Reynolds, "Mediaeval cereal yields: an empirical
         * challenge"): a pulse year mends more than a year's rest ([REST_MEND]); lentils fix some 46-192 kg N/ha (Najah), a 5 t/ha
         * wheat crop takes about 90 (Rösch et al. 2017). So all of it.
         */
        const val PULSE_MEND = 1f
        /**
         * The worn field a penned head's dung mends in a year, m² ([manure]). A sheep or goat drops some 0.3-0.5 t of dung a year
         * (from memory, unchecked), half of it in the pen at night; manuring at some 10-30 t/ha (from memory; Bogaard's
         * intensive manuring of the first farmers' plots) spreads that on some 100-250 m². At Butser, emmer and barley gave
         * 32% more manured than unmanured (Reynolds), about what mends worn ground back to rested ([CROPPED_FLOOR]). 150.
         */
        const val MANURE_M2_PER_HEAD = 150f
        /**
         * Spring sowing ([springSown]): where the coldest month is below [SPRING_BELOW_C] mean, an autumn-sown wheat or barley does
         * not live through the winter (winter cereals stand some -10 to -20 °C at the crown, under snow more; from memory,
         * unchecked), so the crop is sown in spring, barley-like, at [SPRING_YIELD] of an autumn crop: at Butser spring-sown
         * emmer and barley gave 0.55-0.6 of autumn-sown (Reynolds), at Forchtenberg spring barley near winter levels and spring
         * wheats 0.3-0.4 of winter bread wheat (Rösch et al. 2017).
         */
        const val SPRING_BELOW_C = -5f
        const val SPRING_YIELD = 0.6f
        /**
         * The least rain a year, mm, and warmth, degree-days above 5 °C, a crop is sown in beyond the wild cereals' ground ([canCrop]):
         * dry farming of barley goes down to some 250-300 mm (from memory), and barley ripens in some 1,000-1,300 degree-days
         * above 0 °C (from memory), some 800 above 5. Both unchecked; the field's water ([fieldStress]) and the season's warmth
         * ([fieldExpect], the ripening window) do the rest.
         */
        const val CROP_RAIN_MM = 280f
        const val CROP_GDD = 800f
        /**
         * Grain sown per m² of field: about 120 kg/ha, after Columella's 4-5 modii of wheat to the iugerum (about 110-135 kg/ha)
         * and medieval English seed rates of about 2 bushels an acre (both from memory, unchecked).
         */
        const val SEED_RATE = 0.012f
        /**
         * Clearing: person-days (of 6 hours) to fell and burn off a hectare, for each kg/m² of trees and scrub standing on it.
         * Felling a 15-year stand at Forchtenberg took 4.9 eight-hour days per 0.1 ha and a 50-year stand 14.6 (Rösch et al.
         * 2017, Land 6(1):11: time went with the trunks' cross-section, little with the axe or the worker); such stands hold
         * some 4-6 and 15-20 kg/m² (from memory, not checked: that paper gives no biomass), about 8-10 eight-hour days, or
         * 11-13 of six hours, a hectare per kg/m² either way. 12.
         */
        const val FELL_DAYS_PER_HA = 12f
        /**
         * Years cleared ground left uncropped takes to grow back (the share left cleared falls by 1/[REGROW_YEARS] a year):
         * at Forchtenberg the fallow had grown wood enough to burn again after about 12 years (Rösch et al. 2017).
         */
        const val REGROW_YEARS = 12f
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
         * trees go with one group whose crops boom and bust together ([MastTrees]). Broadleaved and open woods at 0.3: in
         * Białowieża's strict reserve, the nearest to a natural lowland forest left in Central Europe, oak is 2-5% of the stems
         * among hornbeam, lime and spruce (Ann. For. Sci. 2018, doi 10.1007/s13595-018-0767-x, table 1); oak's larger
         * trees and the mid-Holocene's oak, hazel and later beech put the bearing share higher, a guess.
         */
        val FOOD_TREES: Map<Biome, Pair<MastTrees, Float>> = mapOf(
            Biome.TEMPERATE_FOREST to (MastTrees.TEMPERATE to 0.3f), Biome.OPEN_WOODLAND to (MastTrees.TEMPERATE to 0.3f),
            Biome.FLOODPLAIN_FOREST to (MastTrees.TEMPERATE to 0.3f),
            Biome.MEDITERRANEAN_WOODLAND to (MastTrees.DRY to 1f), Biome.STEPPE to (MastTrees.DRY to 0.5f),
            Biome.SHRUBLAND to (MastTrees.DRY to 0.5f), Biome.SEMI_DESERT to (MastTrees.DRY to 0.3f), Biome.DESERT to (MastTrees.DRY to 0.3f),
            Biome.CONIFER_FOREST to (MastTrees.CONIFER to 0.1f), Biome.ALPINE_MEADOW to (MastTrees.CONIFER to 0.1f),
        )
        /**
         * Bulbs, corms, tubers and rhizomes to be dug, per kg/m² of herbs; days to replace them, and for fallen mast to
         * rot or sprout. Edible underground storage organs in the southern Cape's shrublands, sampled at random, came to
         * 52-160 kg/ha fresh by kind of country (Singels 2013, thesis, Stellenbosch University), at 41-228 kcal/100 g fresh,
         * most of them corms at 170-230 (Singels et al. 2015, PeerJ PrePrints 3:e1573), some 1.9 kcal/g: 10-30 kcal/m². Counted
         * by their energy in kg of roots ([Nourishment.ROOTS], 2.5 kcal/g), 4-12 g/m² under 0.15-0.3 kg/m² of herbs, as
         * [ROOT_SEARCH_M2_PER_DAY] was fitted to the same ground. The Cape is among the
         * richest in bulbs there is, so this is a high mean; the plots are patchy (most under 200 kg/ha, hotspots over 3,000).
         */
        const val ROOTS_SHARE = 0.03f
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

