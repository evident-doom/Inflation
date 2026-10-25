package inflation.world

import inflation.Books
import inflation.StockName
import inflation.Stocks
import inflation.KgM2
import inflation.Tuning
import inflation.ecology.Place
import inflation.ecology.get
import inflation.ecology.set

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * The plants of the places of [HexLand], on the averages of each place's cells (begun from the fine [Vegetation]'s rules, since gone further):
 * herbs, shrubs and trees grow towards ceilings set by recent moisture, warmth and soil; trees shade the rest;
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
     * it last grew, kg/m² (the crowns that grew it are still there, so it grows back as fast as it would
     * have), and how fresh the cured part still is, 0..1: 1 just cured, 0 leached to straw (see [inflation.Tuning.hayWeathering]).
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

    /** Nourishment per kg of forage in place [p] this week, against fresh growth: about two thirds when dry, dormant or ripe. */
    fun nourishment(p: Place) = tuning.dryValue + (1f - tuning.dryValue) * leafy(p)

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
    /** Cover to hide in at place [p], 0..1: scrub and forest. */
    fun cover(p: Place) = min(1f, shrub[p] / 2f + canopy(p))
    /** Cover on the ground at place [p], 0..1, that small game hides in: standing grass and herbs (full at their most) and scrub; fire and grazing take it off. */
    fun groundCover(p: Place) = min(1f, herb[p] / LifeForm.HERB.maxBiomass + shrub[p] / 2f)

    fun ceiling(form: LifeForm, p: Place): Float {
        val m = ((moisture[p] - form.moistureMin) / (form.moistureFull - form.moistureMin)).coerceIn(0f, 1f)
        val t = ((warmth[p] - form.warmthMin) / (form.warmthFull - form.warmthMin)).coerceIn(0f, 1f)
        val soilF = 0.3f + 0.7f * min(1f, fertility[p] + organicFertility[p])
        val wetFactor = when (form) {
            LifeForm.HERB -> 1f
            LifeForm.SHRUB -> 1f - 0.6f * land.waterlogged[p]
            LifeForm.TREE -> 1f - 0.95f * land.waterlogged[p]
        }
        val open = form.maxBiomass * m * t * soilF * wetFactor
        // The wet valley floor grows on its own soil: moist from the water table and as fertile as its alluvium.
        val wf = hydro.wetFloor(p); if (wf <= 0f) return open
        val floor = form.maxBiomass * t * (0.3f + 0.7f * min(1f, land.floorFertility[p] + organicFertility[p]))
        if (form == LifeForm.HERB) return (1f - wf) * open + wf * floor
        // Woody plants root down to the water table there (the poplar, willow and tamarisk of the gallery
        // forests along rivers in dry country), except where it stands so near the surface that the soil is
        // sodden: there they drown and the floor is wet meadow and reed.
        val rooted = max(0f, wf - hydro.saturatedFloor(p))
        return (1f - wf) * open + rooted * floor * wetFactor
    }

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
        val years = days / Date.DAYS_PER_YEAR.toFloat()
        val memory = 1 - exp(-days / 360f)
        if (stepCount++ % 5 == 0) seedSources()
        if (!shootsSet) {
            for (p in land.places) {
                shrubShoots[p] = land.palatable[p] * SHRUB_RENEWAL * shrub[p]
                treeShoots[p] = land.palatable[p] * TREE_IN_REACH * TREE_RENEWAL * tree[p]
            }
            shootsSet = true
        }
        val shootsKept = exp(-years)
        val lengthening = weather.date.dayOfYear < Date.DAYS_PER_YEAR / 2
        fire.spread(weather, years)
        // (For the books: what grew, what burnt and what fell into the litter, kg.)
        val books = land.books
        val grewBook = books?.adder(Books.Stuff.MATTER, "plants grew"); val burntBook = books?.adder(Books.Stuff.MATTER, "burnt")
        val fellBook = books?.adder(Books.Stuff.MATTER, "litterfall (to the litter)")
        java.util.stream.IntStream.range(0, n).parallel().forEach { i ->
            val p = Place(i)
            val before = biomass(p)
            var grew = 0f
            val t = weather.temperature[p]
            val wet = hydro.soil[p] / hydro.soilCap[p]
            moisture[p] += (((1 - land.slopeShade[p]) * hydro.soil[p] + land.slopeShade[p] * hydro.soilShade[p]) / hydro.soilCap[p] - moisture[p]) * memory
            warmth[p] = if (t > warmth[p]) warmth[p] + (t - warmth[p]) * 0.5f else warmth[p] + (t - warmth[p]) * memory * 0.1f
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
            val openGrowth = (1 - shaded) * sunnyGrowth + shaded * shadyGrowth
            // The seeps grow as long as the ground water flows, whatever the weather brings.
            val seepGrowth = season * hydro.seeping(p)
            // So does the turf of the wet valley floor, as far as the water table under it lies within its roots' reach:
            // when the river falls in a drought, the table under the floodplain falls with it and the meadows brown.
            val wetShare = hydro.wetFloor(p); val openShare = 1f - HexHydrology.SEEP_SHARE - wetShare
            val wetGrowth = season * (1f - hydro.floorTableM(p) / HexHydrology.TURF_REACH_M).coerceIn(0f, 1f)
            val grassGrowth = openShare * openGrowth + HexHydrology.SEEP_SHARE * seepGrowth + wetShare * wetGrowth
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
            val shade = canopy(p)
            val browsed = 1f - exp(-browsing[p] / tuning.browseScale)
            val young = 1f - min(1f, tree[p] / (0.3f * ceiling(LifeForm.TREE, p).coerceAtLeast(1e-3f)))
            val rooted = 1f - exp(-rooting[p] / ROOT_SCALE)
            val treeSeedHere = max(treeSeed[p], 0.5f * animalSeed[p]) * (1f + 1.5f * rooted)
            val shrubSeedHere = max(shrubSeed[p], animalSeed[p]) * (1f + 1.5f * rooted)
            val tree0 = tree[p]; val shrub0 = shrub[p]
            // (Woody plants on the wet valley floor draw on the water table whatever the weather.)
            val woodyWater = water0 + wetShare * (1f - water0)
            // Hooves kill the seedlings they come down on: a seedling lives if no hoof falls on its spot in the years it
            // takes to grow up out of their way, the share of the ground stepped on in a year being last week's at its rate.
            val trod = exp(-trodden[p] / land.areaM2[p] / years * SEEDLING_YEARS).let { if (it.isNaN()) 1f else it }
            trodden[p] = 0f
            grew += grow(LifeForm.TREE, tree, p, season * woodyWater * years * (1 - 0.8f * browsed * young), treeSeedHere * (1 - 0.9f * browsed) * trod)
            val sGrow = shrub[p]
            grew += grow(LifeForm.SHRUB, shrub, p, season * woodyWater * years * (1 - 0.6f * shade) * (1 - 0.5f * browsed), shrubSeedHere * (1 - 0.8f * browsed) * trod)
            shrubNote(if (shrub[p] >= sGrow) ShrubPart.GREW else ShrubPart.DIED_BACK, p, sGrow)
            // New shoots: what the woody plants grew, and the leaves and twigs they renew, in growing weather.
            val renew = season * water0 * years
            shrubShoots[p] = shrubShoots[p] * shootsKept + land.palatable[p] * (max(0f, shrub[p] - shrub0) + SHRUB_RENEWAL * renew * shrub[p])
            treeShoots[p] = treeShoots[p] * shootsKept + land.palatable[p] * TREE_IN_REACH * (max(0f, tree[p] - tree0) + TREE_RENEWAL * renew * tree[p])
            // In the cold the deciduous leaves fall: through the winter only twigs and buds are left to browse.
            // (Where winters are mild enough to grow on, the leaves stay, as on the evergreen scrub of the south.)
            if (tuning.leafFall > 0f && season <= 0f && t < growFrom[p]) { val k = exp(-tuning.leafFall * years); shrubShoots[p] *= k; treeShoots[p] *= k }
            val lawn = 1f - exp(-grazing[p] / LAWN_SCALE)
            val herb0 = herb[p]
            grew += grow(LifeForm.HERB, herb, p, grassGrowth * years * (1 - 0.8f * shade), 1f, 1f - 0.5f * lawn - 0.2f * rooted, hayFallen[p])
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
            frostAndFlood(p, t)
            fertility[p] += (land.soilFertility[p] - fertility[p]) * years / SOIL_RECOVERY_YEARS
            val cover = min(1f, herb[p] / 0.3f + shrub[p] + canopy(p))
            val bare = (1f - cover) * (1f - cover)
            if (bare > 0.01f) fertility[p] *= 1f - min(0.5f, EROSION * bare * weather.precipitation[p] / 50f * land.slope[p] / 0.2f * years)
            val sFire = shrub[p]
            val burned = if (fire.burning[p] > 0f) fire.burn(p, fire.burning[p]) else 0f
            shrubNote(ShrubPart.FIRE, p, sFire)
            // What the plants lost (died back, weathered, frosted, drowned, smothered: all but the fire's) falls into the
            // litter, and so do the leaves, stems and roots they renew: production beyond the stand's net growth, shed as it is made.
            val lost = max(0f, before - biomass(p) - burned + grew)
            val turnover = years * (herb[p] + SHRUB_RENEWAL * shrub[p] + TREE_RENEWAL * tree[p])
            litterfall[p] = lost + turnover
            if (books != null) {
                val m2 = land.areaM2[p].toDouble()
                grewBook!!.add((grew + turnover) * m2); burntBook!!.add(-burned * m2); fellBook!!.add(-litterfall[p] * m2)
            }
            trample(p, years, fellBook)
            // The young growth matures as it grows, over [FLUSH_WEEKS] of full growth; the black scar itself fades over years.
            if (fire.burnt[p] > 0f) fire.burnt[p] = max(0f, fire.burnt[p] * exp(-sprout * days / (7f * FLUSH_WEEKS)) - 0.4f * years)
        }
    }

    /** Frost kills back what its form cannot stand at [t] °C, and floods drown what grows on the floodplain share of place [p]. */
    private fun frostAndFlood(p: Place, t: Float) {
        if (t < LifeForm.HERB.frostKill) herb[p] *= 0.3f
        if (t < LifeForm.SHRUB.frostKill) { val s0 = shrub[p]; shrub[p] *= 0.3f; shrubNote(ShrubPart.FROST, p, s0) }
        if (t < LifeForm.TREE.frostKill) tree[p] *= 0.3f
        // Floods drown what grows on the floodplain share of the place.
        val f = hydro.flood[p]; val fs = land.floodShare[p]
        if (f > 1f) { val s0 = shrub[p]; herb[p] *= 1f - 0.5f * fs; shrub[p] *= 1f - 0.1f * fs; shrubNote(ShrubPart.FLOOD, p, s0) }
        if (f > 3f) tree[p] *= 1f - 0.03f * fs
    }

    /** Trampling, spread over place [p]: a herd beats down a share of it, wearing trails that fade over [years] where none walk. */
    private fun trample(p: Place, years: Float, fellBook: java.util.concurrent.atomic.DoubleAdder?) {
        val tr = trampling[p] / land.areaKm2[p]
        if (tr > 0f) {
            val h0 = herb[p]; val s0 = shrub[p]
            herb[p] *= exp(-tuning.trampleHerb * tr)
            val beaten = h0 - herb[p] + s0 - shrub[p]
            // (What is beaten down falls into the litter.)
            litterfall[p] += beaten; fellBook?.add(-beaten * land.areaM2[p].toDouble())
            trails[p] = min(1f, trails[p] + TRAIL_WEAR * tr * (1f - trails[p]))
            trampling[p] = 0f
        } else if (trails[p] > 0f) trails[p] = max(0f, trails[p] - TRAIL_FADE * years)
    }

    /**
     * (With [crowns], kg/m², of the stand lost lately that its roots will grow back: it grows as from the whole stand.)
     * Returns what grew, kg/m² (what dies back above the ceiling is not counted against it: it falls into the litter).
     */
    private fun grow(form: LifeForm, b: FloatArray, p: Place, time: Float, seedRain: Float, capFactor: Float = 1f, crowns: Float = 0f): Float {
        val cap = ceiling(form, p) * capFactor
        val v = b[p]; val base = v + crowns
        if (v < cap) b[p] = v + form.growthPerYear * time * (base + seedRain * cap * 0.05f) * (1 - v / cap.coerceAtLeast(1e-6f))
        else b[p] = v - (v - cap) * min(1f, 2f * time + 0.002f)
        if (b[p] < 0f) b[p] = 0f
        return max(0f, b[p] - v)
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
        const val SHRUB_RENEWAL = 0.2f
        const val TREE_RENEWAL = 0.04f
        /** Share of trees' new growth within a browser's reach (saplings and low boughs). */
        const val TREE_IN_REACH = 0.03f
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
    TAKEN_OTHERWISE("taken otherwise (small life, fruit, curing, people)")
}
