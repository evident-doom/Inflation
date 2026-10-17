package inflation.world

import inflation.Config

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * The plants of the places of [HexLand], as the fine [Vegetation] grows them on the averages of each band's cells:
 * herbs, shrubs and trees grow towards ceilings set by recent moisture, warmth and soil; trees shade the rest;
 * seed comes from the place itself and its neighbours (the bands above and below in the hex, the same heights
 * in the hexes around); frost, floods, ash and fire set them back. Grazing, browsing, rooting, trampling and
 * seed carried by animals are set by the ecology.
 */
class HexPlants(land: HexLand) : HexLandPart(land) {
    /** Degree-days above 5 °C a place has in a normal year, and so far this year (from the winter solstice). */
    val annualGdd = FloatArray(n) { p -> (0 until 12).sumOf { m -> (max(0f, hw.placeTemp(p, m) - 5f) * Date.DAYS_PER_MONTH).toDouble() }.toFloat() }
    val gdd = FloatArray(n) { p ->
        (0 until Date.START_DAY / Date.DAYS_PER_MONTH).sumOf { m -> (max(0f, hw.placeTemp(p, m) - 5f) * Date.DAYS_PER_MONTH).toDouble() }.toFloat()
    }
    val herb = FloatArray(n); val shrub = FloatArray(n); val tree = FloatArray(n)
    /** Standing dead grass and stems, kg/m² (built up in HexFood), eaten in the lean months, and fuel for fires. */
    val straw = FloatArray(n)
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
     * How green each place's forage is this week, 0..1: young growth in warm, moist weeks; dry or
     * dormant stuff in drought, heat and cold. Grazers get full nourishment from green forage and a
     * fraction from the rest (see [nourishment]).
     */
    val green = FloatArray(n)
    /**
     * The cured grass weathering where it stands: how much of the stand has fallen into the litter since
     * it last grew, kg/m² (the crowns that grew it are still there, so it grows back as fast as it would
     * have), and how fresh the cured part still is, 0..1: 1 just cured, 0 leached to straw (see [HAY_WEATHERING]).
     */
    val hayFallen = FloatArray(n); val hayFresh = FloatArray(n) { 1f }
    /**
     * Each place's warmest month, °C (normal); and from it how plants there are adapted: the warmth at which they start to grow
     * and over which they reach full growth, °C, and the share of the sward that is perennial.
     */
    private val warmest = FloatArray(n) { p -> (0 until 12).maxOf { hw.placeTemp(p, it) } }
    private val growFrom = FloatArray(n) { p -> (warmest[p] - 20f).coerceIn(-2f, GROW_FROM_C) }
    private val growSpan = FloatArray(n) { p -> (warmest[p] - 6f).coerceIn(6f, GROW_SPAN_C) }
    val perennialShare = FloatArray(n) { p -> ((PERENNIAL_FULL_C + 4f - warmest[p]) / 4f).coerceIn(0f, 1f) }
    /** How far the warmth [t], °C, lets the plants of place [p] grow, 0..1, as they are adapted to it. */
    fun growthWarmth(p: Int, t: Float) = ((t - growFrom[p]) / growSpan[p]).coerceIn(0f, 1f) * if (t > 38f) 0.3f else 1f
    /**
     * How fast the grass of place [p] grows, 0..1, at warmth [season] (see [growthWarmth]) with the soil
     * [wet] (its water against what it holds), the annuals of it [cured] or not: the annuals grow with the
     * soil's water to where plants take it unstressed and not at all once cured; the perennials, by their
     * deep roots, until the soil is a quarter full; mixed as the place's sward is.
     */
    fun herbGrowth(p: Int, season: Float, wet: Float, cured: Boolean): Float {
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

    /** Nourishment per kg of forage in place [p] this week, against fresh growth: about two thirds when dry or dormant. */
    fun nourishment(p: Int) = DRY_VALUE + (1f - DRY_VALUE) * green[p]

    /** Volcanic ash lying on each place, mm (set from the eruptions each week). */
    val ash = FloatArray(n)

    /** Share of each place worn by paths, 0..1. */
    val trails = FloatArray(n)
    private var treeSeed = FloatArray(n); private var shrubSeed = FloatArray(n)
    internal var stepCount = 0

    init {
        for (p in 0 until n) {
            herb[p] = ceiling(LifeForm.HERB, p) * 0.8f
            shrub[p] = ceiling(LifeForm.SHRUB, p) * 0.8f
            tree[p] = ceiling(LifeForm.TREE, p) * 0.8f
        }
        seedSources()
    }

    fun biomass(p: Int) = herb[p] + shrub[p] + tree[p]
    fun canopy(p: Int) = min(1f, tree[p] / (0.4f * LifeForm.TREE.maxBiomass))
    /** Cover to hide in at place [p], 0..1: scrub and forest. */
    fun cover(p: Int) = min(1f, shrub[p] / 2f + canopy(p))

    fun ceiling(form: LifeForm, p: Int): Float {
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

    fun consume(p: Int, form: LifeForm, kgPerM2: Float): Float {
        val b = when (form) { LifeForm.HERB -> herb; LifeForm.SHRUB -> shrub; LifeForm.TREE -> tree }
        val taken = min(b[p], max(0f, kgPerM2)); b[p] -= taken; return taken
    }

    fun trample(p: Int, intensity: Float) { trampling[p] += intensity }

    /** Browse at [p], kg/m². (Never below nothing: worlds saved before [eatBrowse] kept its floor may hold shoots a rounding below it.) */
    fun browse(p: Int) = max(0f, shrubShoots[p]) + max(0f, treeShoots[p])

    /** Eats up to [kgPerM2] of the browse at [p], shrubs and trees alike; what was eaten comes off the plants too. */
    fun eatBrowse(p: Int, kgPerM2: Float): Float {
        val all = browse(p)
        if (all <= 0f || kgPerM2 <= 0f) return 0f
        val x = min(kgPerM2, all)
        val s = x * max(0f, shrubShoots[p]) / all; val t = x - s
        // (Eaten bare, the shares' rounding can take a hair more than is there: bare is nothing.)
        shrubShoots[p] = max(0f, shrubShoots[p] - s); treeShoots[p] = max(0f, treeShoots[p] - t)
        return consume(p, LifeForm.SHRUB, s) + consume(p, LifeForm.TREE, t)
    }

    internal fun step(weather: Weather) {
        for (p in 0 until n) {
            if (weather.date.month == 0 && weather.date.dayOfMonth <= days) gdd[p] = 0f
            gdd[p] += max(0f, weather.temperature[p] - 5f) * days
        }
        val years = days / Date.DAYS_PER_YEAR.toFloat()
        val memory = 1 - exp(-days / 360f)
        if (stepCount++ % 5 == 0) seedSources()
        if (!shootsSet) {
            for (p in 0 until n) {
                shrubShoots[p] = land.palatable[p] * SHRUB_RENEWAL * shrub[p]
                treeShoots[p] = land.palatable[p] * TREE_IN_REACH * TREE_RENEWAL * tree[p]
            }
            shootsSet = true
        }
        val shootsKept = exp(-years)
        fire.spread(weather, years)
        java.util.stream.IntStream.range(0, n).parallel().forEach { p ->
            val before = biomass(p)
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
            // again only when rain wets the soil enough to sprout it (to where plants take water unstressed).
            // So do the annual grasses of hot, dry summers. Where summers are cool the sward is perennial: deep
            // roots keep it growing until the soil is a quarter full, it browns as the soil dries further and
            // greens again with the next rain, without curing for the year.
            if (season > 0f && water0 < 0.5f) cured[p] = true else if (water0 >= 1f) cured[p] = false
            if (season > 0f && waterShade < 0.5f) curedShade[p] = true else if (waterShade >= 1f) curedShade[p] = false
            val shaded = land.slopeShade[p]
            val sunnyGrowth = herbGrowth(p, season, wet, cured[p])
            val shadyGrowth = herbGrowth(p, season, hydro.soilShade[p] / hydro.soilCap[p], curedShade[p])
            val openGrowth = (1 - shaded) * sunnyGrowth + shaded * shadyGrowth
            // The seeps grow as long as the ground water flows, whatever the weather brings.
            val seepGrowth = season * hydro.seeping(p)
            // So does the wet valley floor, on the water table under it.
            val wetShare = hydro.wetFloor(p); val openShare = 1f - HexHydrology.SEEP_SHARE - wetShare
            val wetGrowth = season
            val grassGrowth = openShare * openGrowth + HexHydrology.SEEP_SHARE * seepGrowth + wetShare * wetGrowth
            // Greenness follows growth, with a lag: a flush takes some weeks to come and to cure.
            // After a fire the burn greens up with young growth, rich forage, as long as it is growing weather.
            greenOpen[p] += (min(1f, sunnyGrowth + FLUSH * fire.burnt[p] * season) - greenOpen[p]) * GREEN_RATE
            greenShade[p] += (min(1f, shadyGrowth + FLUSH * fire.burnt[p] * season) - greenShade[p]) * GREEN_RATE
            greenSeep[p] += (seepGrowth - greenSeep[p]) * GREEN_RATE
            greenWet[p] += (wetGrowth - greenWet[p]) * GREEN_RATE
            green[p] = openShare * ((1 - shaded) * greenOpen[p] + shaded * greenShade[p]) + HexHydrology.SEEP_SHARE * greenSeep[p] + wetShare * greenWet[p]
            val shade = canopy(p)
            val browsed = 1f - exp(-browsing[p] / BROWSE_SCALE)
            val young = 1f - min(1f, tree[p] / (0.3f * ceiling(LifeForm.TREE, p).coerceAtLeast(1e-3f)))
            val rooted = 1f - exp(-rooting[p] / ROOT_SCALE)
            val treeSeedHere = max(treeSeed[p], 0.5f * animalSeed[p]) * (1f + 1.5f * rooted)
            val shrubSeedHere = max(shrubSeed[p], animalSeed[p]) * (1f + 1.5f * rooted)
            val tree0 = tree[p]; val shrub0 = shrub[p]
            // (Woody plants on the wet valley floor draw on the water table whatever the weather.)
            val woodyWater = water0 + wetShare * (1f - water0)
            grow(LifeForm.TREE, tree, p, season * woodyWater * years * (1 - 0.8f * browsed * young), treeSeedHere * (1 - 0.9f * browsed))
            grow(LifeForm.SHRUB, shrub, p, season * woodyWater * years * (1 - 0.6f * shade) * (1 - 0.5f * browsed), shrubSeedHere * (1 - 0.8f * browsed))
            // New shoots: what the woody plants grew, and the leaves and twigs they renew, in growing weather.
            val renew = season * water0 * years
            shrubShoots[p] = shrubShoots[p] * shootsKept + land.palatable[p] * (max(0f, shrub[p] - shrub0) + SHRUB_RENEWAL * renew * shrub[p])
            treeShoots[p] = treeShoots[p] * shootsKept + land.palatable[p] * TREE_IN_REACH * (max(0f, tree[p] - tree0) + TREE_RENEWAL * renew * tree[p])
            // In the cold the deciduous leaves fall: through the winter only twigs and buds are left to browse.
            // (Where winters are mild enough to grow on, the leaves stay, as on the evergreen scrub of the south.)
            if (LEAF_FALL > 0f && season <= 0f && t < growFrom[p]) { val k = exp(-LEAF_FALL * years); shrubShoots[p] *= k; treeShoots[p] *= k }
            val lawn = 1f - exp(-grazing[p] / LAWN_SCALE)
            val herb0 = herb[p]
            grow(LifeForm.HERB, herb, p, grassGrowth * years * (1 - 0.8f * shade), 1f, 1f - 0.5f * lawn - 0.2f * rooted, hayFallen[p])
            // Cured grass weathers where it stands: the dead stems break and fall into the litter, and what
            // stands is leached of its protein, week by week, until rain sprouts it again. New growth makes
            // good what fell, and freshens the stand by its share of it.
            val grown = max(0f, herb[p] - herb0)
            hayFallen[p] = max(0f, hayFallen[p] - grown)
            if (HAY_WEATHERING > 0f) {
                val kept = exp(-HAY_WEATHERING * years)
                val fell = herb[p] * (1f - green[p]) * (1f - kept)
                herb[p] -= fell; hayFallen[p] += fell
                hayFresh[p] = if (herb[p] > 1e-6f) ((herb[p] - grown).coerceAtLeast(0f) * hayFresh[p] * kept + grown) / herb[p] else 1f
            }
            // Ash smothers the low plants and scorches leaves; as it weathers it enriches the soil.
            if (ash[p] > 1f) {
                herb[p] *= exp(-ash[p] / ASH_SMOTHER_MM * years * 52f / 10f); shrub[p] *= exp(-ash[p] / (4f * ASH_SMOTHER_MM) * years * 52f / 10f)
                fertility[p] = min(1f, fertility[p] + ASH_FERTILITY * ash[p] * years)
            }
            if (t < LifeForm.HERB.frostKill) herb[p] *= 0.3f
            if (t < LifeForm.SHRUB.frostKill) shrub[p] *= 0.3f
            if (t < LifeForm.TREE.frostKill) tree[p] *= 0.3f
            // Floods drown what grows on the floodplain share of the place.
            val f = hydro.flood[p]; val fs = land.floodShare[p]
            if (f > 1f) { herb[p] *= 1f - 0.5f * fs; shrub[p] *= 1f - 0.1f * fs }
            if (f > 3f) tree[p] *= 1f - 0.03f * fs
            fertility[p] += (land.soilFertility[p] - fertility[p]) * years / SOIL_RECOVERY_YEARS
            val cover = min(1f, herb[p] / 0.3f + shrub[p] + canopy(p))
            val bare = (1f - cover) * (1f - cover)
            if (bare > 0.01f) fertility[p] *= 1f - min(0.5f, EROSION * bare * weather.precipitation[p] / 50f * land.slope[p] / 0.2f * years)
            val burned = if (fire.burning[p] > 0f) fire.burn(p, fire.burning[p]) else 0f
            litterfall[p] = max(0f, before - biomass(p) - burned) + years * (herb[p] + SHRUB_RENEWAL * shrub[p] + TREE_RENEWAL * tree[p])
            // Trampling, spread over the place: a herd beats down a share of it.
            val tr = trampling[p] / land.areaKm2[p]
            if (tr > 0f) {
                herb[p] *= exp(-TRAMPLE_HERB * tr); shrub[p] *= exp(-TRAMPLE_SHRUB * tr)
                trails[p] = min(1f, trails[p] + TRAIL_WEAR * tr * (1f - trails[p]))
                trampling[p] = 0f
            } else if (trails[p] > 0f) trails[p] = max(0f, trails[p] - TRAIL_FADE * years)
            if (fire.burnt[p] > 0f) fire.burnt[p] = max(0f, fire.burnt[p] - 0.4f * years)
        }
    }

    /** (With [crowns], kg/m², of the stand lost lately that its roots will grow back: it grows as from the whole stand.) */
    private fun grow(form: LifeForm, b: FloatArray, p: Int, time: Float, seedRain: Float, capFactor: Float = 1f, crowns: Float = 0f) {
        val cap = ceiling(form, p) * capFactor
        val v = b[p]; val base = v + crowns
        if (v < cap) b[p] = v + form.growthPerYear * time * (base + seedRain * cap * 0.05f) * (1 - v / cap.coerceAtLeast(1e-6f))
        else b[p] = v - (v - cap) * min(1f, 2f * time + 0.002f)
        if (b[p] < 0f) b[p] = 0f
    }

    /** Seed for trees and shrubs: from the place itself, and half as much from its neighbours. */
    private fun seedSources() {
        val t = FloatArray(n) { min(1f, tree[it] / 5f) }; val s = FloatArray(n) { min(1f, shrub[it] / 1f) }
        treeSeed = FloatArray(n) { p -> max(t[p], 0.5f * (land.neighbours[p].maxOfOrNull { t[it] } ?: 0f)) }
        shrubSeed = FloatArray(n) { p -> max(s[p], 0.5f * (land.neighbours[p].maxOfOrNull { s[it] } ?: 0f)) }
    }

    companion object {
        /** Nourishment of dry or dormant forage against fresh growth (dry grass is about 45-55% digestible against 65-75% for young growth, from memory). */
        val DRY_VALUE = Config.float("t.dryValue", 0.65f)
        /** Experiment knobs: the warmth at which plants start to grow, °C, and the span over which they reach full growth, °C. */
        val GROW_FROM_C = Config.float("t.growFrom", 2f)
        val GROW_SPAN_C = Config.float("t.growSpan", 13f)
        /**
         * Perennial swards: wholly so where the warmest month is at most this, °C (annuals wholly where it is
         * 4 °C warmer); the share of what the soil holds down to which their deep roots keep them growing.
         */
        const val PERENNIAL_FULL_C = 18f
        const val PERENNIAL_UNSTRESSED = 0.25f
        /** Share of shrubs' and trees' standing mass renewed as leaves and twigs a year (as shed into the litter). */
        const val SHRUB_RENEWAL = 0.2f
        const val TREE_RENEWAL = 0.04f
        /**
         * How fast cured grass weathers where it stands, a year: the share of the standing dead that falls
         * into the litter (about 0.12-0.2 a month for grasses in the CENTURY model's fall rate, from memory),
         * and the rate at which what stands loses its protein toward straw's (crude protein of cured grass
         * falling from about 6% to 3% over a dry season of four to five months, from memory). 0: no weathering.
         */
        val HAY_WEATHERING = Config.float("t.hayWeathering", 1.8f)
        /** Share of trees' new growth within a browser's reach (saplings and low boughs). */
        const val TREE_IN_REACH = 0.03f
        /** Weekly share by which greenness follows growth. */
        const val GREEN_RATE = 0.3f
        /** How green a fresh burn turns in growing weather, per share burnt. */
        const val FLUSH = 1.5f
        /** Ash depth that smothers most of the herb layer within weeks, mm; fertility added per mm of ash a year as it weathers. */
        const val ASH_SMOTHER_MM = 50f
        const val ASH_FERTILITY = 0.002f
        /** Browsing, kg/km²/day, at which seedlings and saplings are mostly eaten (a few deer per km²). */
        val BROWSE_SCALE = Config.float("t.browseScale", 3f)
        /** Grazing, kg/km²/day, that over years turns grassland into short lawn. */
        const val LAWN_SCALE = 8f
        /** Rooting, kg/km²/day, at which most of the ground is churned. */
        const val ROOT_SCALE = 2f
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

/**
 * Share of the herbs and shrubs a place loses a step for each unit of trampling over it (see
 * [HexPlants.trample]; t.trampleHerb, t.trampleShrub): about 5% of the shrubs a year where a
 * tonne of animals a km² goes about (it was ten times that, more than shrubs regrow: the shrub layer
 * fell by half in four years and the browsers starved).
 */
val TRAMPLE_HERB = Config.float("t.trampleHerb", 0.005f)
val TRAMPLE_SHRUB = Config.float("t.trampleShrub", 0.01f)

/**
 * How fast the leaves of the shoots fall in the cold, a year: about two-thirds gone over a four-month winter, the twigs and buds
 * left (t.leafFall; 0: they last as twigs do).
 */
val LEAF_FALL = Config.float("t.leafFall", 4f)
