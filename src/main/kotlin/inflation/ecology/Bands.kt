package inflation.ecology

import inflation.enumFloats
import inflation.Profile
import inflation.world.Phase
import inflation.world.Hex
import inflation.world.HexArray
import inflation.world.HexList
import inflation.world.forEachHex
import inflation.world.hexArray
import inflation.world.toHexArray
import inflation.world.Place
import inflation.world.get
import inflation.world.set

import inflation.gaussianF
import inflation.KindStock
import inflation.StockName
import inflation.ecology.HexSmallLife.DeadPool
import inflation.Books
import inflation.Stocks
import inflation.Kg
import inflation.KgM2
import inflation.Tuning
import inflation.store.MovedInto
import inflation.wrapMonth
import inflation.saturate
import inflation.monthsFrom
import inflation.HASH_A
import inflation.HASH_B
import inflation.HASH_C
import inflation.mix64Once
import inflation.unitOf
import inflation.Config

import inflation.world.Date
import inflation.world.HexLand
import inflation.world.HexMap
import inflation.world.HexWorld
import inflation.world.Weather
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.min
import kotlin.random.Random
import inflation.world.Biome
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.stream.IntStream
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What the bands share in the world beyond each band's own ([Band]): the houses and palisades that stand ([built]), the
 * bands by the hex they are in, the raids due this week, where each band's houses are and where it camps, the kept
 * animals' flows for the census, the packs that follow people's camps, and where the traded goods and salt are got. It
 * needs nothing of the groups but these; the bands' week ([HexPeople], [HexCulture], [Structures]) reads and keeps them.
 */
class Bands internal constructor(private val world: GroupWorld) {
    private val hw get() = world.hw
    private val hex get() = world.hex
    private val land get() = world.land
    private val n get() = world.n
    private fun hexKm(a: Hex, b: Hex) = world.hexKm(a, b)

    /** What bands have built that stands in the world ([Structure]): no band's own, any band's to use. */
    val built = ArrayList<Structure>()

    /** (The [built] by hex, as the list last stood: see [structuresAt].) */
    @Volatile @Transient internal var builtByHex: StructureIndex? = null

    /** (Whether a world saved before [built] was kept has had its bands' houses raised in it: see [tendStructures].) */
    @Transient internal var structuresSeen = false

    /** Bands' kept animals since the census last read them ([HerdFlow]): heads born, dead, run off and culled, and kg herded and fed. */
    @Transient val herdFlows = enumFloats<HerdFlow>()

    /** What a whole house would have done for bands' weeks since the census last read it, by the week's air ([HouseAir], [HouseWorth]). */
    @Transient val houseWorth = HouseAir.entries.map { enumFloats<HouseWorth>() }

    /** The same by kind, as [HexFauna.kinds] indexes them. */
    @delegate:Transient val kindFlows by lazy { Array(world.fauna.kinds.size) { enumFloats<HerdFlow>() } }

    /** Young caught in the hunt and kept, and herds begun from none so, by kind, since the census last read them (hunts run on many cores at once). */
    @delegate:Transient val keptYoung by lazy { java.util.concurrent.atomic.AtomicIntegerArray(world.fauna.kinds.size) }
    @delegate:Transient val herdsBegun by lazy { java.util.concurrent.atomic.AtomicIntegerArray(world.fauna.kinds.size) }

    /** Villages since the census last read them ([VillageFlow], [tallyVillages]). */
    @Transient val villageFlows = enumFloats<VillageFlow>()

    /** The weeks in a row each hex that is a village now has been one ([tallyVillages]). */
    @Transient internal val villageAge = HashMap<Hex, Int>()

    /** Bands by the hex they are in ([indexBands]): at the start of the week for [fillBandStates], after the moves for [setWays]. */
    @Transient internal val bandIndex = HashMap<Hex, ArrayList<HexGroup>>()

    /** Raiders that reached their target's home this week, with the band they came for: fought once every band has had its week. */
    @Transient internal val raidsDue = ArrayList<Pair<HexGroup, HexGroup>>()

    /** Packs of kinds that follow people, by the hex they end the week in (for taming). */
    internal var followersByHex: Map<Hex, List<HexGroup>> = emptyMap()

    /** Each living band's hex by its id, as of this week's camps (for visiting, see [cultureWeek]). */
    internal var bandHex: Map<Int, Hex> = emptyMap()

    /** Where each living band with a standing house has it, by the band's id (set each week before the bands' own weeks). */
    internal var houseOf: Map<Int, Hex> = emptyMap()

    /**
     * Where each material bands give each other ([TRADED]) is got (trade on): for each, the hexes where
     * it is richest, one in a square of [SOURCE_KM] at most, for bands' rites to take them to.
     */
    @delegate:Transient internal val materialSources: Map<Material, HexArray> by lazy {
        TRADED.associateWith { m ->
            val share = requireNotNull(m.deposit) { "no deposits known for traded $m" }(land)
            val best = HashMap<Long, Int>()
            for (p in 0 until n) {
                if (share[p] < SOURCE_MIN) continue
                val (x, y) = hex.centreKm(hw.placeHex[p]); val key = (x / SOURCE_KM).toLong() * 100_000 + (y / SOURCE_KM).toLong()
                val b = best[key]; if (b == null || share[p] > share[b]) best[key] = p
            }
            best.values.map { hw.placeHex[it] }.sorted().toHexArray()
        }
    }

    /**
     * Where people get salt to eat: the salt pans and dry lake beds richest in it ([materialSources]) and the shore, where
     * it dries out of sea spray and pools ([SEA_SALT_A_WEEK]), each one in a square of [SOURCE_KM] at most.
     */
    @delegate:Transient internal val saltSources: HexArray by lazy {
        val shore = HashMap<Long, Hex>()
        for (h in hex.all) if (hw.coast[h]) {
            val (x, y) = hex.centreKm(h); shore.putIfAbsent((x / SOURCE_KM).toLong() * 100_000 + (y / SOURCE_KM).toLong(), h)
        }
        (materialSources.getValue(Material.SALT).toList() + shore.values).distinct().sorted().toHexArray()
    }

    /** The nearest of the [saltSources] band [b] knows, from hex [here], or -1 if it knows none (kept on the band till it moves on). */
    internal fun saltKnown(b: Band, here: Hex): Hex {
        if (b.saltFrom == here) return b.saltAt
        var best = Hex.NONE; var bestKm = Double.MAX_VALUE
        for (h in saltSources) if (b.knows(h)) { val km = hexKm(here, h); if (km < bestKm) { bestKm = km; best = h } }
        b.saltFrom = here; b.saltAt = best
        return best
    }
}
