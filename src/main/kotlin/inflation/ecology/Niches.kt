package inflation.ecology

import inflation.world.HexLand
import kotlin.math.min

/**
 * How animals sharing a hex keep out of each other's way: when they are about, and where they can
 * shelter. Together with how much of a place each searches in a week (see [HexGroups.encounters]),
 * these set how often two groups in the same hex actually meet.
 */
object Niches {
    /** Parts of the day, for [activity]. */
    const val DAWN = 0; const val DAY = 1; const val DUSK = 2; const val NIGHT = 3

    private val ALL_HOURS = floatArrayOf(0.25f, 0.25f, 0.25f, 0.25f)

    /** Share of its active hours kind [s] spends at dawn, day, dusk and night ([Species.activity]). */
    fun activity(s: Species): FloatArray = s.activity ?: ALL_HOURS

    /** How much two kinds are about at the same hours, 0..1 (1 for two kinds about at all hours alike). */
    fun overlap(a: Species, b: Species): Float {
        val x = activity(a); val y = activity(b)
        var s = 0f
        for (i in 0 until 4) s += min(x[i], y[i])
        return s
    }

    /** Kinds of shelter: crags and screes, caves and burrows, thickets and forest, trees to climb. */
    enum class Shelter { CRAG, DEN, THICKET, TREE, WATER }

    /** The shelters kind [s] uses. */
    fun shelters(s: Species): Set<Shelter> = s.shelters

    /**
     * How well kind [s] can shelter in place [p], 0..1: from the crags (ruggedness), the caves and
     * burrows of broken ground, the thickets and forest (cover), the trees (canopy), the reeds and water
 * it can take to, as it uses them.
     */
    fun shelter(s: Species, p: Place, land: HexLand): Float {
        var best = 0f
        for (u in s.shelterArray) best = maxOf(best, when (u) {
            Shelter.CRAG -> land.cragShelter[p]
            // Dens: burrows in broken ground, and best of all caves.
            Shelter.DEN -> maxOf(land.denShelter[p], land.caves[p])
            Shelter.THICKET -> land.plants.cover(Place(p.i))
            Shelter.TREE -> land.plants.canopy(Place(p.i))
            // Reed beds, marsh and deep water: those that swim or wade take to it.
            Shelter.WATER -> min(1f, land.floodShare[p] + 0.5f * (land.drink.nearRiver[p] + land.drink.nearLake[p]))
        })
        return best
    }

    /** Kinds that crack bones and live on what others leave long after the meat is gone. */
    fun cracksBones(s: Species) = s.cracksBones
}
