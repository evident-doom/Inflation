package inflation.world

/**
 * Parameters for world generation. Defaults describe a 1000 x 1000 km region spanning roughly
 * the latitudes of the Levant and Anatolia: an archipelago of islands and larger landmasses
 * with open ocean on every side.
 */
data class WorldConfig(
    val seed: Long = 1L,
    val widthKm: Int = 1000,
    val heightKm: Int = 1000,
    val cellKm: Double = 1.0,
    /** Latitude of the northern and southern edges, degrees north. */
    val northLatitude: Double = 40.0,
    val southLatitude: Double = 31.0,
    /** Number of tectonic plates seeded across the region. */
    val plates: Int = 14,
    /** Share of plates that carry continental crust; they are drawn preferentially near the centre. */
    val continentalShare: Double = 0.5,
    /** Width of the band along every edge that is forced down to ocean floor, km. */
    val oceanMarginKm: Double = 110.0,
    /** Mantle hotspots, each leaving a chain of volcanic islands and seamounts. */
    val hotspots: Int = 2,
    /** Erosion: number of implicit stream-power steps and the length of each step in years. */
    val erosionSteps: Int = 40,
    val erosionStepYears: Double = 50_000.0,
    /** Days advanced by one simulation step. 30-day months split evenly into 5 steps of 6 days. */
    val daysPerStep: Int = 6,
) {
    init {
        require(30 % daysPerStep == 0 || daysPerStep % 30 == 0) { "daysPerStep must divide a 30-day month" }
        require(northLatitude > southLatitude)
    }

    val width: Int get() = (widthKm / cellKm).toInt()
    val height: Int get() = (heightKm / cellKm).toInt()
}
