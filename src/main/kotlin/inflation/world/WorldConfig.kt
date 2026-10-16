package inflation.world

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Parameters for world generation. Defaults describe a region 1000 km wide and 2000 km from north
 * to south, from about 27°N to 45°N: from the latitude of northern Arabia and Sinai, through the
 * Levant and Anatolia, to the Black Sea and the Balkans: one continent-like mass through every
 * latitude band, with offshore islands and open ocean on every side.
 */
data class WorldConfig(
    val seed: Long = 1L,
    val widthKm: Int = 1000,
    val heightKm: Int = 2000,
    val cellKm: Double = 1.0,
    /** Latitude of the middle of the map, degrees north; the map spans 1° per 111 km either side. */
    val centreLatitude: Double = 36.0,
    /** Number of tectonic plates seeded across the region; 0 for about 14 per million km². */
    val plates: Int = 0,
    /** Share of plates that carry continental crust; they are drawn preferentially near the centre. */
    val continentalShare: Double = 0.6,
    /** Width of the band along every edge that is forced down to ocean floor, km. */
    val oceanMarginKm: Double = 110.0,
    /** Mantle hotspots, each leaving a chain of volcanic islands and seamounts; -1 for about 2 per million km². */
    val hotspots: Int = -1,
    /** Erosion: number of implicit stream-power steps and the length of each step in years. */
    val erosionSteps: Int = 80,
    val erosionStepYears: Double = 50_000.0,
    /** Width of the uplifted belt where continents collide, e-folding distance from the boundary, km. */
    val beltKm: Double = 40.0,
    /**
     * Mountain belts are broken into ridges and valleys on this scale, km (0 for smooth belts): crests
     * of folds and thrust sheets, which erosion then deepens into a web of valleys and passes.
     */
    val ridgeKm: Double = 12.0,
    /** Share of a belt's height and uplift that follows the ridges, 0..1. */
    val ridgeShare: Double = 0.9,
    /**
     * High plateaus behind continental collisions: how far the thickened crust stands high from the
     * colliding edge, km (0 for none), and how high it stands per m/yr of closing speed, m. With the
     * closing speeds here the flat plateau tops stand mostly 500-1200 m, as on the Anatolian and
     * Iranian plateaus.
     */
    val plateauKm: Double = inflation.Config.double("t.plateauKm", 180.0),
    val plateauM: Double = inflation.Config.double("t.plateauM", 50_000.0),
    /** Scale on rock uplift rates in mountain belts (and on the belts' starting height). */
    val upliftScale: Double = 0.7,
    /** Hillslope diffusivity, m²/yr. */
    val hillDiffusivity: Double = 0.05,
    /** Steepest slope hillsides hold over the length of a cell before they slide, rise over run (0 for no limit). */
    val criticalSlope: Double = 0.7,
    /** Days advanced by one simulation step. 30-day months split evenly into 5 steps of 6 days. */
    val daysPerStep: Int = 6,
) {
    init {
        require(30 % daysPerStep == 0 || daysPerStep % 30 == 0) { "daysPerStep must divide a 30-day month" }
    }

    val width: Int get() = (widthKm / cellKm).toInt()
    val height: Int get() = (heightKm / cellKm).toInt()

    /** Latitudes of the northern and southern edges, degrees north. */
    val northLatitude: Double get() = centreLatitude + heightKm / KM_PER_DEGREE / 2
    val southLatitude: Double get() = centreLatitude - heightKm / KM_PER_DEGREE / 2

    private val millionKm2: Double get() = widthKm.toDouble() * heightKm / 1e6
    val plateCount: Int get() = if (plates > 0) plates else max(4, (14 * millionKm2).roundToInt())
    val hotspotCount: Int get() = if (hotspots >= 0) hotspots else (2 * millionKm2).roundToInt()

    companion object {
        const val KM_PER_DEGREE = 111.0
    }
}
