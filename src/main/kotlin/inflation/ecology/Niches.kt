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

    /**
     * Share of its active hours a kind spends in each part of the day: dawn, day, dusk, night. A kind
     * not listed is about at all hours alike.
     */
    private val ACTIVITY by lazy { mapOf(
        Catalogue.LION to floatArrayOf(0.25f, 0.05f, 0.3f, 0.4f),
        Catalogue.LEOPARD to floatArrayOf(0.2f, 0.05f, 0.25f, 0.5f),
        Catalogue.CHEETAH to floatArrayOf(0.25f, 0.55f, 0.15f, 0.05f),
        Catalogue.WOLF to floatArrayOf(0.3f, 0.1f, 0.3f, 0.3f),
        Catalogue.HYENA to floatArrayOf(0.1f, 0f, 0.2f, 0.7f),
        Catalogue.JACKAL to floatArrayOf(0.25f, 0.1f, 0.25f, 0.4f),
        Catalogue.GAZELLE to floatArrayOf(0.25f, 0.4f, 0.25f, 0.1f),
        Catalogue.ROE_DEER to floatArrayOf(0.35f, 0.1f, 0.35f, 0.2f),
        Catalogue.FALLOW_DEER to floatArrayOf(0.35f, 0.1f, 0.35f, 0.2f),
        Catalogue.RED_DEER to floatArrayOf(0.35f, 0.1f, 0.35f, 0.2f),
        Catalogue.ELK to floatArrayOf(0.3f, 0.2f, 0.3f, 0.2f),
        Catalogue.IBEX to floatArrayOf(0.3f, 0.4f, 0.2f, 0.1f),
        Catalogue.MOUFLON to floatArrayOf(0.3f, 0.4f, 0.2f, 0.1f),
        Catalogue.BOAR to floatArrayOf(0.2f, 0.05f, 0.25f, 0.5f),
        Catalogue.AUROCHS to floatArrayOf(0.25f, 0.35f, 0.2f, 0.2f),
        Catalogue.HIPPO to floatArrayOf(0.1f, 0f, 0.2f, 0.7f),
        Catalogue.BEAR to floatArrayOf(0.3f, 0.2f, 0.3f, 0.2f),
        Catalogue.ONAGER to floatArrayOf(0.25f, 0.35f, 0.2f, 0.2f),
        Catalogue.HORSE to floatArrayOf(0.25f, 0.35f, 0.2f, 0.2f),
        Catalogue.WILD_GOAT to floatArrayOf(0.3f, 0.4f, 0.2f, 0.1f),
        Catalogue.ORYX to floatArrayOf(0.3f, 0.3f, 0.3f, 0.1f),
        Catalogue.OSTRICH to floatArrayOf(0.2f, 0.6f, 0.2f, 0f),
        Catalogue.HARTEBEEST to floatArrayOf(0.25f, 0.45f, 0.25f, 0.05f),
        Catalogue.DROMEDARY to floatArrayOf(0.2f, 0.5f, 0.2f, 0.1f),
        Catalogue.ARGALI to floatArrayOf(0.3f, 0.4f, 0.2f, 0.1f),
    ) }
    private val ALL_HOURS = floatArrayOf(0.25f, 0.25f, 0.25f, 0.25f)

    fun activity(s: Species): FloatArray = ACTIVITY[s] ?: ALL_HOURS

    /** How much two kinds are about at the same hours, 0..1 (1 for two kinds about at all hours alike). */
    fun overlap(a: Species, b: Species): Float {
        val x = activity(a); val y = activity(b)
        var s = 0f
        for (i in 0 until 4) s += min(x[i], y[i])
        return s
    }

    /** Kinds of shelter: crags and screes, caves and burrows, thickets and forest, trees to climb. */
    enum class Shelter { CRAG, DEN, THICKET, TREE, WATER }

    private val SHELTERS by lazy { mapOf(
        Catalogue.IBEX to setOf(Shelter.CRAG), Catalogue.MOUFLON to setOf(Shelter.CRAG), Catalogue.ARGALI to setOf(Shelter.CRAG),
        Catalogue.WILD_GOAT to setOf(Shelter.CRAG),
        Catalogue.LEOPARD to setOf(Shelter.CRAG, Shelter.DEN, Shelter.THICKET, Shelter.TREE),
        Catalogue.HYENA to setOf(Shelter.DEN), Catalogue.JACKAL to setOf(Shelter.DEN, Shelter.THICKET),
        Catalogue.WOLF to setOf(Shelter.DEN), Catalogue.BEAR to setOf(Shelter.DEN, Shelter.THICKET),
        Catalogue.ROE_DEER to setOf(Shelter.THICKET), Catalogue.FALLOW_DEER to setOf(Shelter.THICKET),
        Catalogue.BOAR to setOf(Shelter.THICKET, Shelter.WATER), Catalogue.ELK to setOf(Shelter.THICKET, Shelter.WATER),
        Catalogue.HIPPO to setOf(Shelter.WATER), Catalogue.RED_DEER to setOf(Shelter.THICKET, Shelter.WATER),
    ) }

    /** The shelters kind [s] uses. */
    fun shelters(s: Species): Set<Shelter> = sheltersByIndex[s.index]
    private val sheltersByIndex by lazy { Array(Catalogue.all.size) { SHELTERS[Catalogue.all[it]] ?: emptySet() } }

    /**
     * How well kind [s] can shelter in place [p], 0..1: from the crags (ruggedness), the caves and
     * burrows of broken ground, the thickets and forest (cover), the trees (canopy), the reeds and water
 * it can take to, as it uses them.
     */
    fun shelter(s: Species, p: Int, land: HexLand): Float {
        var best = 0f
        for (u in shelters(s)) best = maxOf(best, when (u) {
            Shelter.CRAG -> land.cragShelter[p]
            // Dens: burrows in broken ground, and best of all caves.
            Shelter.DEN -> maxOf(land.denShelter[p], land.caves[p])
            Shelter.THICKET -> land.plants.cover(p)
            Shelter.TREE -> land.plants.canopy(p)
            // Reed beds, marsh and deep water: those that swim or wade take to it.
            Shelter.WATER -> min(1f, land.floodShare[p] + 0.5f * (land.drink.nearRiver[p] + land.drink.nearLake[p]))
        })
        return best
    }

    /** Kinds that crack bones and live on what others leave long after the meat is gone. */
    fun cracksBones(s: Species) = s === Catalogue.HYENA
}
