package inflation.ecology

import inflation.world.HexLand
import kotlin.math.min

/**
 * How animals sharing a hex keep out of each other's way: when they are about, and where they can
 * shelter. Together with how much of a place each searches in a week (see [HexGroups.meetChance]),
 * these set how often two groups in the same hex actually meet.
 */
object Niches {
    /** Parts of the day, for [activity]. */
    const val DAWN = 0; const val DAY = 1; const val DUSK = 2; const val NIGHT = 3

    /**
     * Share of its active hours a kind spends in each part of the day: dawn, day, dusk, night. A kind
     * not listed is about at all hours alike.
     */
    private val ACTIVITY = mapOf(
        "lion" to floatArrayOf(0.25f, 0.05f, 0.3f, 0.4f),
        "leopard" to floatArrayOf(0.2f, 0.05f, 0.25f, 0.5f),
        "cheetah" to floatArrayOf(0.25f, 0.55f, 0.15f, 0.05f),
        "wolf" to floatArrayOf(0.3f, 0.1f, 0.3f, 0.3f),
        "striped hyena" to floatArrayOf(0.1f, 0f, 0.2f, 0.7f),
        "jackal" to floatArrayOf(0.25f, 0.1f, 0.25f, 0.4f),
        "gazelle" to floatArrayOf(0.25f, 0.4f, 0.25f, 0.1f),
        "roe deer" to floatArrayOf(0.35f, 0.1f, 0.35f, 0.2f),
        "fallow deer" to floatArrayOf(0.35f, 0.1f, 0.35f, 0.2f),
        "red deer" to floatArrayOf(0.35f, 0.1f, 0.35f, 0.2f),
        "elk" to floatArrayOf(0.3f, 0.2f, 0.3f, 0.2f),
        "ibex" to floatArrayOf(0.3f, 0.4f, 0.2f, 0.1f),
        "mouflon" to floatArrayOf(0.3f, 0.4f, 0.2f, 0.1f),
        "wild boar" to floatArrayOf(0.2f, 0.05f, 0.25f, 0.5f),
        "aurochs" to floatArrayOf(0.25f, 0.35f, 0.2f, 0.2f),
        "hippo" to floatArrayOf(0.1f, 0f, 0.2f, 0.7f),
        "brown bear" to floatArrayOf(0.3f, 0.2f, 0.3f, 0.2f),
        "onager" to floatArrayOf(0.25f, 0.35f, 0.2f, 0.2f),
        "wild horse" to floatArrayOf(0.25f, 0.35f, 0.2f, 0.2f),
        "wild goat" to floatArrayOf(0.3f, 0.4f, 0.2f, 0.1f),
        "oryx" to floatArrayOf(0.3f, 0.3f, 0.3f, 0.1f),
        "ostrich" to floatArrayOf(0.2f, 0.6f, 0.2f, 0f),
        "hartebeest" to floatArrayOf(0.25f, 0.45f, 0.25f, 0.05f),
        "dromedary" to floatArrayOf(0.2f, 0.5f, 0.2f, 0.1f),
        "argali" to floatArrayOf(0.3f, 0.4f, 0.2f, 0.1f),
    )
    private val ALL_HOURS = floatArrayOf(0.25f, 0.25f, 0.25f, 0.25f)

    fun activity(s: Species): FloatArray = ACTIVITY[s.name] ?: ALL_HOURS

    /** How much two kinds are about at the same hours, 0..1 (1 for two kinds about at all hours alike). */
    fun overlap(a: Species, b: Species): Float {
        val x = activity(a); val y = activity(b)
        var s = 0f
        for (i in 0 until 4) s += min(x[i], y[i])
        return s
    }

    /** Kinds of shelter: crags and screes, caves and burrows, thickets and forest, trees to climb. */
    enum class Shelter { CRAG, DEN, THICKET, TREE, WATER }

    private val SHELTERS = mapOf(
        "ibex" to setOf(Shelter.CRAG), "mouflon" to setOf(Shelter.CRAG), "argali" to setOf(Shelter.CRAG), "wild goat" to setOf(Shelter.CRAG),
        "leopard" to setOf(Shelter.CRAG, Shelter.DEN, Shelter.THICKET, Shelter.TREE),
        "striped hyena" to setOf(Shelter.DEN), "jackal" to setOf(Shelter.DEN, Shelter.THICKET),
        "wolf" to setOf(Shelter.DEN), "brown bear" to setOf(Shelter.DEN, Shelter.THICKET),
        "roe deer" to setOf(Shelter.THICKET), "fallow deer" to setOf(Shelter.THICKET),
        "wild boar" to setOf(Shelter.THICKET, Shelter.WATER), "elk" to setOf(Shelter.THICKET, Shelter.WATER),
        "hippo" to setOf(Shelter.WATER), "red deer" to setOf(Shelter.THICKET, Shelter.WATER),
    )

    /** The shelters kind [s] uses. */
    fun shelters(s: Species): Set<Shelter> = sheltersByIndex[s.index]
    private val sheltersByIndex by lazy { Array(Catalogue.all.size) { SHELTERS[Catalogue.all[it].name] ?: emptySet() } }

    /**
     * How well kind [s] can shelter in place [p], 0..1: from the crags (ruggedness), the caves and
     * burrows of broken ground, the thickets and forest (cover), the trees (canopy), the reeds and water
 * it can take to, as it uses them.
     */
    fun shelter(s: Species, p: Int, land: HexLand): Float {
        var best = 0f
        for (u in shelters(s)) best = maxOf(best, when (u) {
            Shelter.CRAG -> ((land.ruggedness[p] - 0.3f) / 0.4f).coerceIn(0f, 1f)
            // Dens: burrows in broken ground, and best of all caves.
            Shelter.DEN -> maxOf(0.6f * (land.ruggedness[p] / 0.5f).coerceIn(0f, 1f), land.caves[p])
            Shelter.THICKET -> land.cover(p)
            Shelter.TREE -> land.canopy(p)
            // Reed beds, marsh and deep water: those that swim or wade take to it.
            Shelter.WATER -> min(1f, land.floodShare[p] + 0.5f * (land.nearRiver[p] + land.nearLake[p]))
        })
        return best
    }

    /** Kinds that crack bones and live on what others leave long after the meat is gone. */
    fun cracksBones(s: Species) = s.name == "striped hyena"
}
