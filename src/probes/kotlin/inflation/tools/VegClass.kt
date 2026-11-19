package inflation.tools

import inflation.world.HexLand
import inflation.world.Place

/**
 * The kind of country a place is by its plants as they stand, for reports (not the climate's [inflation.world.Biome],
 * which says only what the climate could carry): by tree canopy, the usual tree-cover classes (grassland under a tenth,
 * wooded grassland to two fifths, open woodland to three fifths, closed forest above), the treeless split by shrub
 * cover into grassland and shrubland, and ground with next to no plants as bare.
 */
object VegClass {
    val names = listOf("bare", "grassland", "shrubland", "wooded grassland", "open woodland", "closed forest")
    fun of(land: HexLand, p: Int): Int {
        val pl = Place(p); val pls = land.plants
        val canopy = pls.canopy(pl); val shrubs = pls.shrub[p] / 2f
        return when {
            canopy >= 0.6f -> 5
            canopy >= 0.4f -> 4
            canopy >= 0.1f -> 3
            shrubs >= 0.2f -> 2
            pls.herb[p] + pls.shrub[p] < 0.01f -> 0
            else -> 1
        }
    }
}
