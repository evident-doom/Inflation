package inflation.world

import inflation.Books
import inflation.Par
import inflation.StockName
import inflation.Stocks
import inflation.KgM2
import inflation.Tuning

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Plant life forms tracked on each place ([HexPlants]). Food, fodder, fuel and materials are drawn from these.
 * They grow by the water they transpire; the stands and ramps here only set where a new world's plants start
 * ([HexPlants.ceiling]) and the scales of cover and canopy.
 */
enum class LifeForm(
    /** A full stand in the best conditions, kg dry matter per m² (the scale of [HexPlants.canopy] and [HexPlants.groundCover]). */
    val maxBiomass: Float,
    /** Long-term soil moisture (0..1) below which the form cannot persist, and where it thrives. */
    val moistureMin: Float,
    val moistureFull: Float,
    /** Growing-season temperature (warmest month, °C) below which it cannot persist, and where it thrives. */
    val warmthMin: Float,
    val warmthFull: Float,
    /** Air temperature that kills it outright, °C. */
    val frostKill: Float,
) {
    HERB(0.6f, 0.05f, 0.35f, 4f, 12f, -45f),
    SHRUB(3f, 0.12f, 0.45f, 6f, 14f, -30f),
    TREE(25f, 0.3f, 0.7f, 10f, 16f, -25f),
}

/**
 * The plants of the places of [HexLand], on the averages of each place's cells:
 * herbs, shrubs and trees grow by the water they transpire ([HexHydrology.transpired]) and shed leaves and twigs as they
 * go, each taking its share of the light (trees first, then shrubs, then herbs) and of the soil water its roots reach;
 * seed comes from the place itself and its neighbours (the other half of its hex, the places
 * in the hexes around); frost, floods, ash and fire set them back. Grazing, browsing, rooting, trampling and
 * seed carried by animals are set by the ecology.
 */
class HexPlants(land: HexLand) : HexLandPart(land) {
    /** Degree-days above 5 °C a place has in a normal year, and so far this year (from the winter solstice). */
    @Transient val annualGdd = FloatArray(n) { p -> (0 until 12).sumOf { m -> (max(0f, hw.placeTemp(Place(p), m) - 5f) * Date.DAYS_PER_MONTH).toDouble() }.toFloat() }
    val gdd = FloatArray(n) { p -> degreeDaysBefore(Place(p), 5f) }
    /** Degree-days above [base] °C a place has had by the day the run starts on ([inflation.Tuning.startDay]), from the winter solstice, at its usual monthly warmth. */
    private fun degreeDaysBefore(place: Place, base: Float): Float {
        val start = tuning.startDay
        var sum = 0.0
        for (m in 0 until Date.MONTHS_PER_YEAR) {
            val days = min(Date.DAYS_PER_MONTH, start - m * Date.DAYS_PER_MONTH)
            if (days <= 0) break
            sum += (max(0f, hw.placeTemp(place, m) - base) * days).toDouble()
        }
        return sum.toFloat()
    }
    val herb = FloatArray(n); val shrub = FloatArray(n); val tree = FloatArray(n)
    val moisture = FloatArray(n) { p -> (mean(p) { world.climate.aridity(it) } / 1.5f).coerceIn(0f, 1f) }
    private val warmth = FloatArray(n) { p -> mean(p) { world.climate.warmestMonthTemp(it) } }
    val fertility = land.soilFertility.copyOf()
    val organicFertility = FloatArray(n)
    val litterfall = FloatArray(n)
    /**
     * Browse within an animal's reach at each place, kg/m² of the edible part: the leaves and shoots
     * the shrubs and trees put out (their growth and the leaves and twigs they renew each year), not
     * yet eaten or shed. Shoots last about a year, as the yearly renewal they come from.
     */
    val shrubShoots = FloatArray(n); val treeShoots = FloatArray(n)
    /** Whether the shoots have been set; a world saved before they were kept gets a year's worth from its plants. */
    private var shootsSet = false
    /** Set by the ecology: browsing, grazing and rooting lately, kg/km²/day; seed carried by animals, 0..1; trampling. */
    val browsing = FloatArray(n); val grazing = FloatArray(n); val rooting = FloatArray(n); val animalSeed = FloatArray(n)
    private val trampling = FloatArray(n)
    /**
     * The share of each place people keep open of shrubs and trees ([keepOpen]: the ground cleared for their fields, set by
     * the ecology): the woody plants' ceiling there is less by it, so they grow back on it only as it is let go.
     */
    val opened = FloatArray(n)
    /**
     * Ground stepped on at each place, m² (see [tread]): the seedlings of shrubs and trees it kills. Trodden in the week's grazing
     * and counted, then cleared, in the next week's plant step (the plants grow before the animals feed).
     */
    private val trodden = FloatArray(n)
    /**
     * How green each place's forage is this week, 0..1: young growth in warm, moist weeks; dry or
     * dormant stuff in drought, heat and cold. Grazers get full nourishment from green forage and a
     * fraction from the rest (see [nourishment]).
     */
    val green = FloatArray(n)
    /**
     * The cured grass weathering where it stands: how much of the stand has fallen into the litter since
     * it last grew, kg/m² (new growth makes it good again), and how fresh the cured part still is, 0..1: 1 just cured, 0 leached to straw (see [inflation.Tuning.hayWeathering]).
     */
    val hayFallen = FloatArray(n); val hayFresh = FloatArray(n) { 1f }
    /**
     * Each place's warmest month, °C (normal); and from it how plants there are adapted: the warmth at which they start to grow
     * and over which they reach full growth, °C, and the share of the sward that is perennial.
     */
    @Transient private val warmest = FloatArray(n) { p -> (0 until 12).maxOf { hw.placeTemp(Place(p), it) } }
    @Transient private val growFrom = FloatArray(n) { p -> (warmest[p] - 20f).coerceIn(-2f, tuning.growFromC) }
    @Transient private val growSpan = FloatArray(n) { p -> (warmest[p] - 6f).coerceIn(6f, tuning.growSpanC) }
    @Transient val perennialShare = FloatArray(n) { p -> ((PERENNIAL_FULL_C + 4f - warmest[p]) / 4f).coerceIn(0f, 1f) }
    /**
     * Each place's coldest month, °C (normal): its plants stand frost down to [FROST_MARGIN] below it, as they grow by its
     * warmest ([frostKillC]).
     */
    @Transient private val coldest = FloatArray(n) { p -> (0 until 12).minOf { hw.placeTemp(Place(p), it) } }
    /**
     * The tick's mean warmth, °C, that kills [form] at place [p] outright: the form's own [LifeForm.frostKill], or colder where
     * the place's winters are, by [FROST_MARGIN] below its coldest month: larch and the steppe's perennial grasses stand winters
     * that would kill the trees and grass of a milder country.
     */
    private fun frostKillC(form: LifeForm, p: Place) = min(form.frostKill, coldest[p] - FROST_MARGIN)
    /** How far the warmth [t], °C, lets the plants of place [p] grow, 0..1, as they are adapted to it. */
    fun growthWarmth(p: Place, t: Float) = ((t - growFrom[p]) / growSpan[p]).coerceIn(0f, 1f) * if (t > 38f) 0.3f else 1f
    /**
     * How fast the grass of place [p] grows, 0..1, at warmth [season] (see [growthWarmth]) with the soil
     * [wet] (its water against what it holds), the annuals of it [cured] or not: the annuals grow with the
     * soil's water to where plants take it unstressed and not at all once cured; the perennials, by their
     * deep roots, until the soil is a quarter full; mixed as the place's sward is.
     */
    fun herbGrowth(p: Place, season: Float, wet: Float, cured: Boolean): Float {
        val annual = if (cured) 0f else season * (wet / HexHydrology.UNSTRESSED).coerceIn(0f, 1f)
        val perennial = season * (wet / PERENNIAL_UNSTRESSED).coerceIn(0f, 1f)
        return (1f - perennialShare[p]) * annual + perennialShare[p] * perennial
    }
    /** The grass has dried out and cured this season (it waits for rain to sprout again). */
    val cured = BooleanArray(n)
    /**
     * How green the open ground is, and the seeps: the hollows and stream sides ([HexHydrology.SEEP_SHARE] of each place)
     * where the ground water comes out and keeps the turf wet while it flows, through a dry summer.
     * [green] is the two together.
     */
    private val greenOpen = FloatArray(n); val greenSeep = FloatArray(n)
    /** How green the turf of the wet valley floor is (see [HexHydrology.wetFloor]). */
    val greenWet = FloatArray(n)
    /** The same for the grass of the shaded slopes: how green it is, and whether it has cured. */
    val greenShade = FloatArray(n); private val curedShade = BooleanArray(n)

    /**
     * How far the grass of each place has grown on from leaf to stem and seed this year: degree-days above 0 °C it has
     * grown through since the winter solstice, or since it sprouted again after curing. Grasses run up to flower only
     * once a year, on lengthening days, so a sward that sprouts after midsummer stays leafy; one that has begun to run
     * up ripens on. Grazing and fire keep a sward leafy: what is eaten or burnt grows back as new leaf.
     */
    val grassAge = FloatArray(n) { p -> degreeDaysBefore(Place(p), 0f) }
    /** How ripe the grass of place [p] is, 0..1: leafy at 0, gone to stem and seed at 1 (see [grassAge]). */
    fun maturity(p: Place) = ((grassAge[p] - LEAFY_DD) / (RIPE_DD - LEAFY_DD)).coerceIn(0f, 1f)
    /** How much of the forage in place [p] is fresh, leafy growth, 0..1: the green part of it, less as it ripens. */
    fun leafy(p: Place) = green[p] * (1f - maturity(p))

    /**
     * Nourishment per kg of forage in place [p] this week, against fresh growth: about two thirds when just dry, dormant or
     * ripe, falling toward straw's as the cured grass weathers ([hayFresh]): the lean end of a dry season or a winter.
     */
    fun nourishment(p: Place): Float {
        val dry = tuning.strawValue + (tuning.dryValue - tuning.strawValue) * hayFresh[p]
        return dry + (1f - dry) * leafy(p)
    }

    /** Volcanic ash lying on each place, mm (set from the eruptions each week). */
    val ash = FloatArray(n)

    /** Share of each place worn by paths, 0..1. */
    val trails = FloatArray(n)
    private var treeSeed = FloatArray(n); private var shrubSeed = FloatArray(n)
    internal var stepCount = 0

    init {
        for (p in land.places) {
            herb[p] = ceiling(LifeForm.HERB, p) * 0.8f
            shrub[p] = ceiling(LifeForm.SHRUB, p) * 0.8f
            tree[p] = ceiling(LifeForm.TREE, p) * 0.8f
        }
        seedSources()
    }

    fun biomass(p: Place) = herb[p] + shrub[p] + tree[p]
    fun canopy(p: Place) = min(1f, tree[p] / (0.4f * LifeForm.TREE.maxBiomass))
    /**
     * The share of the light at place [p] the trees' leaves take: a stand's leaf mass levels off once its crowns close, so
     * the light it takes grows with its mass only while it is young ([TREE_CLOSED_KG]); the wood it lays on after does not
     * add to it. ([canopy], the cover the crowns give, is the forest's structure for the animals and stays as it was.)
     */
    fun treeLight(p: Place) = 1f - exp(-tree[p] / TREE_CLOSED_KG)
    /**
     * The shares of the light at place [p] taken by the leaves of its herbs, shrubs and trees, into [out] in that order (the
     * rest falls on bare ground): the trees' crowns first ([treeLight]); under and between them the shrubs and the green herbs
     * side by side, as one layer by Beer's law on their leaf areas together ([LIGHT_K]), each by its share of that leaf area.
     * What each takes is its share of the water the place gives to the air ([HexHydrology.step]).
     */
    fun lightShares(p: Place, out: FloatArray) {
        val trees = treeLight(p)
        out[0] = herbLight(p, trees); out[1] = shrubLight(p, trees); out[2] = trees
    }
    /** Leaf area of place [p]'s shrubs, and of its herbs in leaf ([inLeaf]): these on at least [HERB_SWARD] kg/m² (see there). */
    private fun shrubLeaf(p: Place) = SHRUB_LAI_PER_KG * shrub[p]
    private fun herbLeaf(p: Place) = HERB_LAI_PER_KG * max(herb[p], HERB_SWARD) * max(green[p], inLeaf(p))
    /**
     * The share of place [p]'s sward in leaf, 0..1: all of it in growing weather, but for the annuals that have cured (their
     * perennials stay in leaf). [green] says how fast the sward grows; this, how much of it has leaves out to take the light,
     * however slowly it grows in the cool of spring.
     */
    private fun inLeaf(p: Place): Float {
        if (growthWarmth(p, warmth[p]) <= 0f) return 0f
        val shaded = land.slopeShade[p]
        return (1 - shaded) * (if (cured[p]) perennialShare[p] else 1f) + shaded * (if (curedShade[p]) perennialShare[p] else 1f)
    }
    /** The light the shrubs and the herbs take together under the trees' [trees] share. */
    private fun understory(trees: Float, leaf: Float) = (1f - trees) * (1f - exp(-LIGHT_K * leaf))
    private fun shrubLight(p: Place, trees: Float): Float {
        val s = shrubLeaf(p); val h = herbLeaf(p)
        return if (s + h > 0f) understory(trees, s + h) * s / (s + h) else 0f
    }
    private fun herbLight(p: Place, trees: Float): Float {
        val s = shrubLeaf(p); val h = herbLeaf(p)
        return if (s + h > 0f) understory(trees, s + h) * h / (s + h) else 0f
    }
    /** Cover to hide in at place [p], 0..1: scrub and forest. */
    fun cover(p: Place) = min(1f, shrub[p] / 2f + canopy(p))
    /** Cover on the ground at place [p], 0..1, that small game hides in: standing grass and herbs (full at their most) and scrub; fire and grazing take it off. */
    fun groundCover(p: Place) = min(1f, herb[p] / LifeForm.HERB.maxBiomass + shrub[p] / 2f)

    /**
     * The stand of [form] place [p]'s climate and soil would once have carried: only where a new world's plants start
     * from, and for the diagnostics; the plants grow by their water, not toward it.
     */
    fun ceiling(form: LifeForm, p: Place): Float {
        val m = ((moisture[p] - form.moistureMin) / (form.moistureFull - form.moistureMin)).coerceIn(0f, 1f)
        val t = ((warmth[p] - form.warmthMin) / (form.warmthFull - form.warmthMin)).coerceIn(0f, 1f)
        val soilF = soilOpen(p)
        val wetFactor = when (form) {
            LifeForm.HERB -> 1f
            LifeForm.SHRUB -> 1f - 0.6f * land.waterlogged[p]
            LifeForm.TREE -> 1f - 0.95f * land.waterlogged[p]
        }
        // (Shrubs and trees grow only off the ground kept open: [opened].)
        val woody = if (form == LifeForm.HERB) 1f else 1f - opened[p]
        val open = form.maxBiomass * m * t * soilF * wetFactor * woody
        // The wet valley floor grows on its own soil: moist from the water table and as fertile as its alluvium.
        val wf = hydro.wetFloor(p); if (wf <= 0f) return open
        val floor = form.maxBiomass * t * soilFloor(p)
        if (form == LifeForm.HERB) return (1f - wf) * open + wf * floor
        // Woody plants root down to the water table there (the poplar, willow and tamarisk of the gallery
        // forests along rivers in dry country), except where it stands so near the surface that the soil is
        // sodden: there they drown and the floor is wet meadow and reed.
        val rooted = max(0f, wf - hydro.saturatedFloor(p))
        return (1f - wf) * open + rooted * floor * wetFactor * woody
    }

    /**
     * People keep [share] of place [p] open of shrubs and trees ([opened]): ground newly cleared, beyond what was open, has
     * its shrubs and trees cut, and their shoots with them, as their share of the woody ground left. Returns what was cut,
     * kg/m², for the caller to leave lying (felled and burnt or left to rot where it fell, as clearing for the first
     * fields was). Less open than before cuts nothing: the woods come back by their own growth.
     */
    fun keepOpen(p: Place, share: Float): KgM2 {
        val was = opened[p]; opened[p] = share.coerceIn(0f, 1f)
        if (opened[p] <= was) return KgM2.ZERO
        val f = (opened[p] - was) / (1f - was).coerceAtLeast(1e-6f)
        shrubShoots[p] *= 1f - f; treeShoots[p] *= 1f - f
        return consume(p, LifeForm.SHRUB, KgM2(f * shrub[p]), ShrubPart.CLEARED) + consume(p, LifeForm.TREE, KgM2(f * tree[p]))
    }

    /** How much the soil of place [p]'s open ground lets plants grow, 0.3 to 1: its fertility and its organic matter's ([ceiling]). */
    private fun soilOpen(p: Place) = 0.3f + 0.7f * min(1f, fertility[p] + organicFertility[p])
    /** The same on its wet valley floor, of its alluvium ([HexLand.floorFertility]). */
    private fun soilFloor(p: Place) = 0.3f + 0.7f * min(1f, land.floorFertility[p] + organicFertility[p])
    /**
     * How much place [p]'s soil lets what is sown there grow, 0.3 to 1: its open ground's and its wet valley floor's ([soilOpen],
     * [soilFloor]) by their shares, as the plants' ceilings have them. The one soil the plants and the fields both grow on.
     */
    fun soil(p: Place): Float { val wf = hydro.wetFloor(p); return (1f - wf) * soilOpen(p) + wf * soilFloor(p) }

    /** Takes up to [want] of the plants of [form] at [p]; returns what was taken. */
    fun consume(p: Place, form: LifeForm, want: KgM2, shrubPart: ShrubPart = ShrubPart.TAKEN_OTHERWISE): KgM2 {
        val b = when (form) { LifeForm.HERB -> herb; LifeForm.SHRUB -> shrub; LifeForm.TREE -> tree }
        val taken = min(b[p], max(0f, want.v)); val before = b[p]; b[p] -= taken
        if (form == LifeForm.SHRUB) shrubNote(shrubPart, p, before)
        // (A bite under half a float's step off a big stand leaves it as it was: food made from nothing, for the books.)
        land.books?.leak(Books.Stuff.MATTER, Books.ROUNDING, (taken - (before - b[p]).toDouble()) * land.areaM2[p])
        return KgM2(taken)
    }

    fun trample(p: Place, intensity: Float) { trampling[p] += intensity }

    /**
     * Records [km] walked at place [p] by [heads] animals of [massKg] each: the ground their feet come down on, m². Each
     * stride sets all four feet down once, on as much ground as carries the animal's weight at a hoof's usual pressure
     * on the ground ([HOOF_KPA]); strides lengthen with size as legs do ([STRIDE_M] at 50 kg, as the cube root of mass).
     * (Grown shrubs and trees stand above the hooves and are walked round: only their seedlings are trodden, see [step].)
     */
    fun tread(p: Place, heads: Float, massKg: Float, km: Float) {
        if (heads <= 0f || km <= 0f) return
        val feetM2 = massKg * 9.81f / (HOOF_KPA * 1000f)
        val strideM = STRIDE_M * Math.cbrt(massKg / 50.0).toFloat()
        val m2 = heads * km * 1000f / strideM * feetM2
        trodden[p] += m2
        shrubBook?.let { it[ShrubPart.entries.size].add(m2.toDouble()) }
    }

    /** Browse at [p], kg/m². (Never below nothing: worlds saved before [eatBrowse] kept its floor may hold shoots a rounding below it.) */
    fun browse(p: Place) = max(0f, shrubShoots[p]) + max(0f, treeShoots[p])

    /**
     * For shrubDiag=true (a report): the shrubs' kg gained or lost by each process ([SHRUB_PARTS]) since it was last read,
     * summed over the map.
     */
    @delegate:Transient val shrubBook: Array<java.util.concurrent.atomic.DoubleAdder>? by lazy {
        if (inflation.Config.flag(inflation.Config.Key.SHRUB_DIAG, false)) Array(ShrubPart.entries.size + 1) { java.util.concurrent.atomic.DoubleAdder() } else null }
    private fun shrubNote(part: ShrubPart, p: Place, before: Float) { shrubBook?.let { it[part.ordinal].add(((shrub[p] - before) * land.areaM2[p]).toDouble()) } }

    /** Eats up to [want] of the browse at [p], shrubs and trees alike; what was eaten comes off the plants too. */
    fun eatBrowse(p: Place, want: KgM2): KgM2 {
        val all = browse(p)
        if (all <= 0f || want.v <= 0f) return KgM2.ZERO
        val x = min(want.v, all)
        val s = x * max(0f, shrubShoots[p]) / all; val t = x - s
        // (Eaten bare, the shares' rounding can take a hair more than is there: bare is nothing.)
        shrubShoots[p] = max(0f, shrubShoots[p] - s); treeShoots[p] = max(0f, treeShoots[p] - t)
        return consume(p, LifeForm.SHRUB, KgM2(s), shrubPart = ShrubPart.BROWSED) + consume(p, LifeForm.TREE, KgM2(t))
    }

    internal fun step(weather: Weather) {
        for (p in land.places) {
            if (weather.date.month == 0 && weather.date.dayOfMonth <= days) { gdd[p] = 0f; grassAge[p] = 0f }
            gdd[p] += max(0f, weather.temperature[p] - 5f) * days
        }
        val years = Date.years(days)
        val memory = 1 - exp(-days / 360f)
        // (Seed sources afresh every [SEED_SOURCE_DAYS], at any tick length.)
        if (stepCount++ % max(1, (SEED_SOURCE_DAYS / days).toInt()) == 0) seedSources()
        if (!shootsSet) {
            for (p in land.places) {
                shrubShoots[p] = land.palatable[p] * SHRUB_IN_REACH * SHRUB_BROWSE * SHRUB_RENEWAL * shrub[p]
                treeShoots[p] = land.palatable[p] * TREE_IN_REACH * TREE_RENEWAL * tree[p]
            }
            shootsSet = true
        }
        val shootsKept = exp(-years)
        // (The rules set as a share a tick at [SET_AT_DAYS] a tick, as a rate a day: the same at that tick, and as much in
        // a month at any other. The plants' warmth rises halfway to a warmer tick's in one; frost and floods take their share.)
        val perSet = days / SET_AT_DAYS
        val warmUp = 1f - 0.5f.pow(perSet)
        val frostKept = 0.3f.pow(perSet)
        val lengthening = weather.date.dayOfYear < Date.DAYS_PER_YEAR / 2
        fire.spread(weather, years)
        // (For the books: what grew, what burnt and what fell into the litter, kg.)
        val books = land.books
        val grewBook = books?.adder(Books.Stuff.MATTER, "plants grew"); val burntBook = books?.adder(Books.Stuff.MATTER, "burnt")
        val fellBook = books?.adder(Books.Stuff.MATTER, "litterfall (to the litter)")
        Par.forEach("plants: step", n) { i ->
            val p = Place(i)
            val before = biomass(p)
            var grew = 0f
            val t = weather.temperature[p]
            val wet = hydro.soil[p] / hydro.soilCap[p]
            moisture[p] += (((1 - land.slopeShade[p]) * hydro.soil[p] + land.slopeShade[p] * hydro.soilShade[p]) / hydro.soilCap[p] - moisture[p]) * memory
            warmth[p] = if (t > warmth[p]) warmth[p] + (t - warmth[p]) * warmUp else warmth[p] + (t - warmth[p]) * memory * 0.1f
            // Plants are adapted to their place: where summers are cool they grow fully at less warmth (alpine
            // grasses at 5-10 °C); where summers are hot they need the usual warmth.
            val season = growthWarmth(p, t)
            val water0 = (wet / HexHydrology.UNSTRESSED).coerceIn(0f, 1f)
            val waterShade = (hydro.soilShade[p] / hydro.soilCap[p] / HexHydrology.UNSTRESSED).coerceIn(0f, 1f)
            // Grass that dries out in growing weather sets seed and cures to straw where it stands; it greens
            // again when rain wets the soil enough to sprout it: a soaking rain ([GERMINATION_MM] in the week), or any
            // rain on soil moist enough to keep the seedlings (as moist as the grass cured at). So do the annual grasses
            // of hot, dry summers. Where summers are cool the sward is perennial: deep roots keep it growing until the
            // soil is a quarter full, it browns as the soil dries further and greens again with the next rain, without
            // curing for the year.
            val rained = weather.rain(p.i) > 0f
            val soaked = weather.rain(p.i) >= GERMINATION_MM
            val wasCured = cured[p]
            if (season > 0f && water0 < 0.5f) cured[p] = true else if ((rained && water0 >= 0.5f) || soaked) cured[p] = false
            if (wasCured && !cured[p]) grassAge[p] = 0f
            else if (!cured[p] && (lengthening || grassAge[p] >= LEAFY_DD)) grassAge[p] += max(0f, t) * days
            // (Grazing, kg/km² a day, against the grass standing, kg/m².)
            if (herb[p] > 0f) grassAge[p] *= 1f - (grazing[p] * days * 1e-6f / herb[p]).coerceIn(0f, 1f)
            if (season > 0f && waterShade < 0.5f) curedShade[p] = true else if ((rained && waterShade >= 0.5f) || soaked) curedShade[p] = false
            val shaded = land.slopeShade[p]
            val sunnyGrowth = herbGrowth(p, season, wet, cured[p])
            val shadyGrowth = herbGrowth(p, season, hydro.soilShade[p] / hydro.soilCap[p], curedShade[p])
            // The seeps grow as long as the ground water flows, whatever the weather brings.
            val seepGrowth = season * hydro.seeping(p)
            // So does the turf of the wet valley floor, as far as the water table under it lies within its roots' reach:
            // when the river falls in a drought, the table under the floodplain falls with it and the meadows brown.
            val wetShare = hydro.wetFloor(p); val openShare = 1f - HexHydrology.SEEP_SHARE - wetShare
            val wetGrowth = season * (1f - hydro.floorTableM(p) / HexHydrology.TURF_REACH_M).coerceIn(0f, 1f)
            // Greenness follows growth, with a lag: a flush takes some weeks to come and to cure.
            // After a fire the burn greens up with young growth, rich forage, when the soil is moist enough to sprout
            // it (the burnt straw no longer holds it back): a burn in the dry season stays black until the rain.
            val sprout = herbGrowth(p, season, wet, false)
            val sproutShade = herbGrowth(p, season, hydro.soilShade[p] / hydro.soilCap[p], false)
            greenOpen[p] += (min(1f, sunnyGrowth + FLUSH * fire.burnt[p] * sprout) - greenOpen[p]) * GREEN_RATE
            greenShade[p] += (min(1f, shadyGrowth + FLUSH * fire.burnt[p] * sproutShade) - greenShade[p]) * GREEN_RATE
            greenSeep[p] += (seepGrowth - greenSeep[p]) * GREEN_RATE
            greenWet[p] += (wetGrowth - greenWet[p]) * GREEN_RATE
            green[p] = openShare * ((1 - shaded) * greenOpen[p] + shaded * greenShade[p]) + HexHydrology.SEEP_SHARE * greenSeep[p] + wetShare * greenWet[p]
            val browsed = 1f - exp(-browsing[p] / tuning.browseScale)
            val young = 1f - min(1f, tree[p] / YOUNG_STAND_KG)
            val rooted = 1f - exp(-rooting[p] / ROOT_SCALE)
            val treeSeedHere = max(treeSeed[p], 0.5f * animalSeed[p]) * (1f + 1.5f * rooted)
            val shrubSeedHere = max(shrubSeed[p], animalSeed[p]) * (1f + 1.5f * rooted)
            // Hooves kill the seedlings they come down on: a seedling lives if no hoof falls on its spot in the years it
            // takes to grow up out of their way, the share of the ground stepped on in a year being last week's at its rate.
            val trod = exp(-trodden[p] / land.areaM2[p] / years * SEEDLING_YEARS).let { if (it.isNaN()) 1f else it }
            // (The residents' treading is a tick's walking, set once a step: it falls again each tick of the step, [HexLand.lastTick].)
            if (land.lastTick) trodden[p] = 0f
            // The plants grow by the water they transpired ([HexHydrology.transpired]), at [WUE_KG_PER_MM], in growing weather
            // and as the soil feeds them: no ceiling but the light their leaves take and the water. (The warmth is in the
            // water already: the air takes more of it the warmer it is. Below the plants' growing warmth they grow nothing.)
            val trees = treeLight(p); val shrubs = shrubLight(p, trees); val herbs = herbLight(p, trees)
            val growing = if (season > 0f) 1f else 0f
            val made = WUE_KG_PER_MM * growing * soil(p)
            // Woody roots reach the water table under the wet valley floor where it is not sodden: there the shrubs and
            // trees transpire as much as the air takes, whatever the weather (the gallery forests of dry country).
            val table = hydro.demandMm[p] * max(0f, wetShare - hydro.saturatedFloor(p))
            val woody = 1f - opened[p]
            // Seedlings come up from the seed rain where the soil is moist in growing weather, and live as far as hooves and
            // browsers spare them: the stand that grows from nothing (to be replaced by wet-year recruitment, phase 3).
            val sprouting = season * water0 * years * woody * trod
            val gTree = made * (hydro.transpired[2][p] + table * trees) * (1f - 0.95f * land.waterlogged[p]) * (1 - 0.8f * browsed * young) +
                ESTABLISH_TREE * sprouting * treeSeedHere * (1 - 0.9f * browsed)
            grew += growBy(tree, p, gTree, TREE_RENEWAL * years)
            val sGrow = shrub[p]
            val gShrub = made * (hydro.transpired[1][p] + table * shrubs) * (1f - 0.6f * land.waterlogged[p]) * (1 - 0.5f * browsed) +
                ESTABLISH_SHRUB * sprouting * shrubSeedHere * (1 - 0.8f * browsed)
            grew += growBy(shrub, p, gShrub, SHRUB_RENEWAL * years)
            shrubNote(if (shrub[p] >= sGrow) ShrubPart.GREW else ShrubPart.DIED_BACK, p, sGrow)
            // New shoots: what the woody plants grew (their new leaves and twigs, which the old ones are shed against).
            shrubShoots[p] = shrubShoots[p] * shootsKept + land.palatable[p] * SHRUB_IN_REACH * SHRUB_BROWSE * gShrub
            treeShoots[p] = treeShoots[p] * shootsKept + land.palatable[p] * TREE_IN_REACH * gTree
            // In the cold the deciduous leaves fall: through the winter only twigs and buds are left to browse.
            // (Where winters are mild enough to grow on, the leaves stay, as on the evergreen scrub of the south.)
            if (tuning.leafFall > 0f && season <= 0f && t < growFrom[p]) { val k = exp(-tuning.leafFall * years); shrubShoots[p] *= k; treeShoots[p] *= k }
            val lawn = 1f - exp(-grazing[p] / LAWN_SCALE)
            val herb0 = herb[p]
            // The herbs of the open ground transpire from its soil while they grow (the annuals not once cured); those of the
            // seeps and the wet valley floor as far as their water flows ([seepGrowth], [wetGrowth], warmth in them).
            val active = (1 - shaded) * (if (cured[p]) perennialShare[p] else 1f) + shaded * (if (curedShade[p]) perennialShare[p] else 1f)
            val herbWater = openShare * growing * active * hydro.transpired[0][p] +
                hydro.demandMm[p] * herbs * (HexHydrology.SEEP_SHARE * seepGrowth + wetShare * wetGrowth)
            val gHerb = WUE_KG_PER_MM * soil(p) * herbWater * (1f - 0.5f * lawn - 0.2f * rooted)
            // Green leaves are renewed as they grow, the old shed to the litter.
            grew += growBy(herb, p, gHerb, HERB_RENEWAL * years * green[p])
            // Cured grass weathers where it stands: the dead stems break and fall into the litter, and what
            // stands is leached of its protein, week by week, until rain sprouts it again. New growth makes
            // good what fell, and freshens the stand by its share of it.
            val grown = max(0f, herb[p] - herb0)
            hayFallen[p] = max(0f, hayFallen[p] - grown)
            if (tuning.hayWeathering > 0f) {
                val kept = exp(-tuning.hayWeathering * years)
                val fell = herb[p] * (1f - green[p]) * (1f - kept)
                herb[p] -= fell; hayFallen[p] += fell
                hayFresh[p] = if (herb[p] > 1e-6f) ((herb[p] - grown).coerceAtLeast(0f) * hayFresh[p] * kept + grown) / herb[p] else 1f
            }
            // Ash smothers the low plants and scorches leaves; as it weathers it enriches the soil.
            if (ash[p] > 1f) {
                val sAsh = shrub[p]
                herb[p] *= exp(-ash[p] / ASH_SMOTHER_MM * years * 52f / 10f); shrub[p] *= exp(-ash[p] / (4f * ASH_SMOTHER_MM) * years * 52f / 10f)
                shrubNote(ShrubPart.ASH, p, sAsh)
                fertility[p] = min(1f, fertility[p] + ASH_FERTILITY * ash[p] * years)
            }
            frostAndFlood(p, t, frostKept, perSet)
            fertility[p] += (land.soilFertility[p] - fertility[p]) * years / SOIL_RECOVERY_YEARS
            val cover = min(1f, herb[p] / 0.3f + shrub[p] + canopy(p))
            val bare = (1f - cover) * (1f - cover)
            // (On the rain a week, whatever the tick: [EROSION] is set against 50 mm a week.)
            if (bare > 0.01f) fertility[p] *= 1f - min(0.5f, EROSION * bare * weather.precipitation[p] * 7f / days / 50f * land.slope[p] / 0.2f * years)
            val sFire = shrub[p]
            val burned = if (fire.burning[p] > 0f) fire.burn(p, fire.burning[p]) else 0f
            shrubNote(ShrubPart.FIRE, p, sFire)
            // What the plants lost (died back, weathered, frosted, drowned, smothered: all but the fire's) falls into the
            // litter, and so do the leaves, stems and roots they renew: production beyond the stand's net growth, shed as it is made.
            val fell = max(0f, before - biomass(p) - burned + grew)
            // (Over a step of several ticks it adds up for the soil, which takes it in once a step: [HexLand.firstTick].)
            litterfall[p] = if (land.firstTick) fell else litterfall[p] + fell
            if (books != null) {
                val m2 = land.areaM2[p].toDouble()
                grewBook!!.add(grew * m2); burntBook!!.add(-burned * m2); fellBook!!.add(-fell * m2)
            }
            trample(p, years, perSet, fellBook)
            // The young growth matures as it grows, over [FLUSH_WEEKS] of full growth; the black scar itself fades over years.
            if (fire.burnt[p] > 0f) fire.burnt[p] = max(0f, fire.burnt[p] * exp(-sprout * days / (7f * FLUSH_WEEKS)) - 0.4f * years)
        }
    }

    /**
     * Frost kills back what its form cannot stand at [t] °C there ([frostKillC]) ([frostKept] of it left, a tick's share of [SET_AT_DAYS]' 0.3), and
     * floods drown what grows on the floodplain share of place [p] (as much a day, [perSet] the tick's days against [SET_AT_DAYS]).
     */
    private fun frostAndFlood(p: Place, t: Float, frostKept: Float, perSet: Float) {
        if (t < frostKillC(LifeForm.HERB, p)) herb[p] *= frostKept
        if (t < frostKillC(LifeForm.SHRUB, p)) { val s0 = shrub[p]; shrub[p] *= frostKept; shrubNote(ShrubPart.FROST, p, s0) }
        if (t < frostKillC(LifeForm.TREE, p)) tree[p] *= frostKept
        // Floods drown what grows on the floodplain share of the place.
        val f = hydro.flood[p]; val fs = land.floodShare[p]
        if (f > 1f) { val s0 = shrub[p]; herb[p] *= (1f - 0.5f * fs).pow(perSet); shrub[p] *= (1f - 0.1f * fs).pow(perSet); shrubNote(ShrubPart.FLOOD, p, s0) }
        if (f > 3f) tree[p] *= (1f - 0.03f * fs).pow(perSet)
    }

    /** Trampling, spread over place [p]: a herd beats down a share of it, wearing trails that fade over [years] where none walk. */
    private fun trample(p: Place, years: Float, perSet: Float, fellBook: java.util.concurrent.atomic.DoubleAdder?) {
        val tr = trampling[p] / land.areaKm2[p]
        if (tr > 0f) {
            val h0 = herb[p]
            // (As much beaten down a day at any tick: [inflation.Tuning.trampleHerb] is a [SET_AT_DAYS] tick's.)
            herb[p] *= exp(-tuning.trampleHerb * tr * perSet)
            val beaten = h0 - herb[p]
            // (What is beaten down falls into the litter.)
            litterfall[p] += beaten; fellBook?.add(-beaten * land.areaM2[p].toDouble())
            trails[p] = min(1f, trails[p] + TRAIL_WEAR * tr * (1f - trails[p]))
            // (The residents' week of trampling, set once a step, beats down each tick of it: [HexLand.lastTick].)
            if (land.lastTick) trampling[p] = 0f
        } else if (trails[p] > 0f) trails[p] = max(0f, trails[p] - TRAIL_FADE * years)
    }

    /**
     * Adds [g] kg/m² of new growth to stand [b] at place [p] and sheds [shed] of it (a share) to the litter. Returns the growth,
     * kg/m² (what is shed is counted with the plants' other losses).
     */
    private fun growBy(b: FloatArray, p: Place, g: Float, shed: Float): Float {
        val add = max(0f, g)
        b[p] = max(0f, (b[p] + add) * (1f - min(1f, shed)))
        return add
    }

    /** Names the plants' stocks: herbs, shrubs and trees. */
    internal fun listStocks(stocks: Stocks) {
        stocks.within(StockName.PLANTS) { for ((name, a) in listOf(StockName.HERBS to herb, StockName.SHRUBS to shrub, StockName.TREES to tree)) stocks.perM2(name, a, land.areaM2) }
    }

    /** Seed for trees and shrubs: from the place itself, and half as much from its neighbours. */
    private fun seedSources() {
        val t = FloatArray(n) { min(1f, tree[it] / 5f) }; val s = FloatArray(n) { min(1f, shrub[it] / 1f) }
        treeSeed = FloatArray(n) { p -> max(t[p], 0.5f * (land.neighbours[p].maxOfOrNull { t[it] } ?: 0f)) }
        shrubSeed = FloatArray(n) { p -> max(s[p], 0.5f * (land.neighbours[p].maxOfOrNull { s[it] } ?: 0f)) }
    }

    companion object {
        /**
         * Pressure an ungulate's hooves put on the ground standing, kPa: about 50-80 for deer and sheep, 150-250 for
         * cattle (from memory of soil-compaction studies; not checked against a source).
         */
        const val HOOF_KPA = 100f
        /** A 50 kg ungulate's walking stride, m (from memory: deer and sheep walk at about a metre a stride). */
        const val STRIDE_M = 1f
        /**
         * Years a shrub or tree seedling takes to grow up out of the way of hooves (from memory: a few years for
         * shrubs on good ground; not checked against a source).
         */
        const val SEEDLING_YEARS = 3f
        /**
         * The tick length, days, at which the rules once taken as a share a tick were set (frost, floods, trampling, the plants'
         * warmth rising): they go as a rate a day, the same as before at this tick.
         */
        const val SET_AT_DAYS = 6f
        /** How often the seed sources of shrubs and trees are worked out afresh, days (every 5 ticks of 6 days, before). */
        const val SEED_SOURCE_DAYS = 30f
        /**
         * How far below its coldest month's mean a place's plants stand a tick's mean warmth before frost kills them, °C
         * ([frostKillC]): a winter's cold spells run some 10-20 °C below its mean month (an estimate, from memory).
         */
        const val FROST_MARGIN = 15f
        /** What changes the shrubs, for shrubDiag (see [shrubBook]). */
        val SHRUB_PARTS: List<String> get() = ShrubPart.entries.map { it.label }
        /**
         * Perennial swards: wholly so where the warmest month is at most this, °C (annuals wholly where it is
         * 4 °C warmer); the share of what the soil holds down to which their deep roots keep them growing.
         */
        const val PERENNIAL_FULL_C = 18f
        const val PERENNIAL_UNSTRESSED = 0.25f
        /**
         * Rain in a week that sprouts cured annual grass again, mm: the seed of Mediterranean and steppe annuals
         * germinates after the first soaking autumn rain of about 20 mm (a "germination rain").
         */
        const val GERMINATION_MM = 20f
        /** Share of shrubs' and trees' standing mass renewed as leaves and twigs a year (as shed into the litter). */
        /** Light extinction of a stand's leaves (Beer's law): about 0.5 for most canopies (Monsi & Saeki 1953, from memory). */
        const val LIGHT_K = 0.5f
        /**
         * Leaf area per kg/m² of green herbs (their specific leaf area, about 20 m² a kg) and per kg/m² of a shrub stand (about
         * a sixth of it leaf, at about 8 m² a kg). Guesses to source.
         */
        const val HERB_LAI_PER_KG = 20f
        const val SHRUB_LAI_PER_KG = 1.3f
        const val SHRUB_RENEWAL = 0.2f
        const val TREE_RENEWAL = 0.04f
        /** Share of the green herbs renewed as leaves a year (shed to the litter as new leaves grow); a guess to source. */
        const val HERB_RENEWAL = 1f
        /**
         * Aboveground dry matter grown per mm transpired, kg/m², on the best soil ([soil] scales it down to 0.3 of it): crops
         * reach some 5 g/m² a mm (wheat's 55 kg/ha per mm of water used beyond soil evaporation, Sadras & Angus 2006, from
         * memory); dryland range grows some 0.3-0.6 g/m² per mm of rain (Le Houérou, from memory) with a third to a half of it
         * transpired, 1-2 g. At 1.2 g the woods could not hold their stands against their shedding (water-eval-ab, 2026-11-19).
         */
        const val WUE_KG_PER_MM = 0.002f
        /** The tree stand, kg/m², whose crowns take 1-1/e of the light ([treeLight]): young stands close their canopy at a few kg. A guess to source. */
        const val TREE_CLOSED_KG = 3f
        /**
         * The sward's leaf, kg/m², that greens wherever the ground does whatever was grazed or weathered off it: the perennials'
         * tillers sprout from their crowns and roots, the annuals from the seed in the soil, so a sward's leaf area in a green
         * spell is not set by the dead stand left standing. Its mass grows from the water as all growth does ([herbLeaf]). A
         * guess: a steppe sward's green leaf area in spring of about 1 (to source).
         */
        const val HERB_SWARD = 0.06f
        /**
         * Seedlings of shrubs and trees established a year, kg/m², under a full seed rain in moist growing weather all year: the
         * start of a stand on open ground. Guesses, to be replaced by wet-year recruitment.
         */
        const val ESTABLISH_SHRUB = 0.03f
        const val ESTABLISH_TREE = 0.05f
        /** A stand of trees, kg/m², grown out of the reach of browsers (below it, browsing holds the young trees back). */
        const val YOUNG_STAND_KG = 5f
        /** Share of trees' new growth within a browser's reach (saplings and low boughs). */
        const val TREE_IN_REACH = 0.03f
        /**
         * Share of shrubs' new growth within a browser's reach: the maquis, the thickets of the woodland edge and the scrub
         * stand some 1-4 m, and a deer reaches some 1.5-2 m, a goat standing on its hind legs a little more (from memory);
         * about half. With all of it counted, the woodlands grew some 60-75 g/m² of browse a year, two to five times the
         * current year's twigs and leaves in reach that browsed woods are measured to grow, and the browsers lacked for none.
         */
        const val SHRUB_IN_REACH = 0.5f
        /**
         * Share of shrubs' new growth that is browse at all: the current year's shoots and soft leaves of the kinds browsers
         * eat, not the tough, resinous evergreen leaves of the maquis, the thorny and the bitter (an estimate; the place's
         * palatable share is the herbs'). With all of it counted, woods grew some 100 g/m² of browse in reach a year
         * against some 10-50 that browsed woods are measured to (from memory), and the small browsers stood at twice their
         * record's densities. At 0.4 the deer, elk and elephants, which live mostly on browse, crashed; at 0.65 the small
         * kinds came down by a third and the elk held.
         */
        const val SHRUB_BROWSE = 0.65f
        /**
         * Degree-days above 0 °C of growth over which a grass sward stays leafy, and by which it has gone to stem and seed and
         * is worth no more than cured grass ([grassAge]). Leafy until the stems start to run up: barley and wheat, both grasses
         * of the steppe, reach stem elongation at 490-660 °C·d (Montana State University Extension, "Using growing degree
         * days to predict plant stages", from Saskatchewan crop trials). Then digestibility falls by a third to a half of a
         * percentage point a day from over 80% to below 50% (Ball et al. 2001, "Understanding forage quality", American
         * Farm Bureau): some 75 days, at the 15 °C or so of the late spring and early summer it falls in, about 1100 °C·d.
         */
        const val LEAFY_DD = 550f
        const val RIPE_DD = 1650f
        /** Weekly share by which greenness follows growth. */
        const val GREEN_RATE = 0.3f
        /** How green a fresh burn turns in growing weather, per share burnt. */
        const val FLUSH = 1.5f
        /**
         * Weeks of full growth over which a burn's young regrowth matures and loses its edge as forage (to 1/e): grazers
         * favour fresh burns for one to a few months (from memory: tallgrass prairie and African savanna studies).
         */
        const val FLUSH_WEEKS = 8f
        /** Ash depth that smothers most of the herb layer within weeks, mm; fertility added per mm of ash a year as it weathers. */
        const val ASH_SMOTHER_MM = 50f
        const val ASH_FERTILITY = 0.002f
        /** Grazing, kg/km²/day, that over years turns grassland into short lawn. */
        const val LAWN_SCALE = 8f
        /**
         * Rooting, kg/km²/day, at which most of the ground is churned: wild boar at a few a km², digging most of a kilo a
         * day each, turn over about a tenth of the ground in a year (8-11% of a Pannonian oak forest's floor a year:
         * Drimaj et al. 2025, Eur. J. Wildl. Res. 71, 10.1007/s10344-025-01994-5; the boar's density there not given).
         */
        const val ROOT_SCALE = 30f
        /** How fast trampling wears a path (per unit of trampling, slowing as the path deepens), and how fast an unused one fades, per year. */
        const val TRAIL_WEAR = 0.3f
        const val TRAIL_FADE = 1f
        /** Years over which soil fertility returns most of the way to its natural level. */
        const val SOIL_RECOVERY_YEARS = 50f
        /**
         * Share of the topsoil's fertility lost in a year from bare ground on a 1-in-5 slope under
         * 50 mm of rain a week (from memory; bare hillsides lose cm of soil a decade).
         */
        const val EROSION = 0.05f
    }
}

/** What changes the shrubs, for shrubDiag ([HexPlants.shrubBook]); [label] is its line in the report. */
enum class ShrubPart(val label: String) {
    GREW("grew"), DIED_BACK("died back over its ceiling"), BROWSED("browsed"), FROST("frost"), FLOOD("flood"), ASH("ash"), FIRE("fire"),
    TAKEN_OTHERWISE("taken otherwise (small life, fruit, curing, people)"), CLEARED("cleared for fields")
}

