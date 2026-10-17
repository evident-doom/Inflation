package inflation.ecology
import inflation.world.HexMap
import java.util.Arrays

/**
 * Routes and landmarks: the worn paths read as a graph. Where the ways meet, cross a river, go over
 * broken country, come to water or end, there is a landmark (a junction, ford, pass, water or end);
 * between two landmarks runs a leg, the chain of hexes the path follows. A band that knows two
 * landmarks and the leg between can be told the way, and can follow it through country it never
 * walked; a route is remembered by its landmarks long after the country round about is forgotten.
 * Made afresh every [ROUTE_WEEKS] weeks from the paths as they are worn and grow over.
 */
class HexRoutes(private val g: HexGroups) {
    enum class Kind(val label: String) { NONE("none"), JUNCTION("junction"), FORD("ford"), PASS("pass"), WATER("water"), END("end") }

    private val hex = g.hex
    /** Per hex: the kind of landmark there ([Kind.ordinal]; 0 for none). */
    val landmark = ByteArray(hex.size)
    /** Per hex: whether a worn path runs through it. */
    val onRoute = BooleanArray(hex.size)
    /** The legs: the hexes of each, from one landmark to the next, ends included. */
    val legs = ArrayList<IntArray>()
    /** Per landmark hex: the legs that start or end there. */
    val legsAt = HashMap<Int, IntArray>()
    /** The landmarks, in hex order. */
    var landmarks = IntArray(0); private set
    private var weeks = 0

    fun kind(h: Int) = Kind.entries[landmark[h].toInt()]
    fun isLandmark(h: Int) = landmark[h] != 0.toByte()

    /** Called once a week, after the paths are worn: every [ROUTE_WEEKS] weeks the graph is read afresh. */
    fun week() { if (weeks++ % ROUTE_WEEKS == 0) build() }

    private fun wornRaw(h: Int, d: Int) = g.wear.paths[h * 6 + d] >= ROUTE_WORN
    /** Per border (hex × 6 + side): whether it is a way of the routes, as last read ([build]). */
    private val way = BooleanArray(hex.size * 6)
    private fun worn(h: Int, d: Int) = way[h * 6 + d]
    /** Whether the border is a river that cannot just be stepped over: deeper than the knee at its easiest crossing. */
    private fun river(i: Int) = hex.riverDepthM[i] >= HexMap.KNEE

    fun build() {
        Arrays.fill(landmark, 0); Arrays.fill(onRoute, false); legs.clear(); legsAt.clear()
        val hw = g.hw; val land = g.land
        fun rug(h: Int) = hw.placeOf[0][h].takeIf { it >= 0 }?.let { land.ruggedness[it] } ?: 0f
        fun water(h: Int) = hw.placeOf[0][h].let { it >= 0 && land.drink.waterKmMean[it] <= WATER_LANDMARK_KM }
        // A pass: the way through broken country, rougher all round than where the path goes.
        fun pass(h: Int): Boolean {
            var round = 0f; var k = 0
            for (d in 0 until 6) { val q = hex.neighbour(h, d); if (q >= 0 && hex.heart[q] >= 0) { round += rug(q); k++ } }
            return rug(h) >= PASS_RUGGED && k > 0 && round / k >= rug(h)
        }
        for (h in 0 until hex.size) for (d in 0 until 6) way[h * 6 + d] = hex.heart[h] >= 0 && wornRaw(h, d)
        // A worn way that goes off a route into the next hex and stops there, with nothing there to go for
        // (water, a pass, a river crossed), is a group leaving the route to range, not a way of its own: a
        // way passes through at least one hex, as only going through country wears one (see [PathWear]).
        val cut = ArrayList<Int>()
        for (h in 0 until hex.size) {
            if (hex.heart[h] < 0) continue
            var n = 0; var only = -1
            for (d in 0 until 6) if (way[h * 6 + d]) { n++; only = d }
            if (n != 1 || water(h) || pass(h) || river(h * 6 + only)) continue
            val q = hex.neighbour(h, only)
            if (q >= 0 && (0 until 6).count { way[q * 6 + it] } >= 3) cut += h * 6 + only
        }
        for (i in cut) { val h = i / 6; val d = i % 6; way[i] = false; hex.neighbour(h, d).takeIf { it >= 0 }?.let { q -> way[q * 6 + (d + 3) % 6] = false } }
        for (h in 0 until hex.size) {
            if (hex.heart[h] < 0) continue
            var ways = 0; var ford = false
            for (d in 0 until 6) if (worn(h, d)) { ways++; if (river(h * 6 + d)) ford = true }
            if (ways == 0) continue
            onRoute[h] = true
            val pass = pass(h)
            val water = water(h)
            landmark[h] = (when {
                ways >= 3 -> Kind.JUNCTION; ford -> Kind.FORD; pass -> Kind.PASS; ways == 1 -> Kind.END; water -> Kind.WATER; else -> Kind.NONE
            }).ordinal.toByte()
        }
        landmarks = (0 until hex.size).filter { isLandmark(it) }.toIntArray()
        // The legs: from each landmark along each worn way, through hexes with two ways, to the next landmark.
        val at = HashMap<Int, ArrayList<Int>>()
        val done = HashSet<Long>()
        for (a in landmarks) for (d in 0 until 6) {
            if (!worn(a, d)) continue
            val first = hex.neighbour(a, d); if (first < 0 || !onRoute[first]) continue
            val way = arrayListOf(a, first); var prev = a; var cur = first
            while (!isLandmark(cur) && way.size < MAX_LEG_HEXES) {
                val next = (0 until 6).map { hex.neighbour(cur, it) to it }.firstOrNull { (q, e) -> q >= 0 && q != prev && worn(cur, e) && onRoute[q] }?.first ?: break
                prev = cur; cur = next; way += cur
            }
            if (!isLandmark(cur)) continue
            val b = cur
            if (a == b) continue
            // Each leg once: by its start and first step, either way round.
            if (!done.add(a.toLong() * hex.size + first)) continue
            done.add(b.toLong() * hex.size + way[way.size - 2])
            val i = legs.size; legs += way.toIntArray()
            at.getOrPut(a) { ArrayList() } += i; at.getOrPut(b) { ArrayList() } += i
        }
        for ((h, l) in at) legsAt[h] = l.toIntArray()
    }

    /**
     * What [teller] tells [listener] of the routes it knows within [ROUTE_TOLD_KM] of [here]: each
     * landmark it knows, and the hexes of each leg between two landmarks it knows (it can describe the
     * way from one to the next). Returns how many hexes the listener newly learnt.
     */
    fun tell(teller: Band, listener: Band, here: Int): Int {
        var learnt = 0
        for (a in landmarks) {
            if (!teller.knows(a) || hex.distanceKm(a, here) > ROUTE_TOLD_KM) continue
            if (!listener.knows(a)) { listener.learn(a, hex.size); learnt++ }
            for (i in legsAt[a] ?: continue) {
                val leg = legs[i]
                if (!teller.knows(leg[0]) || !teller.knows(leg[leg.size - 1])) continue
                for (h in leg) if (!listener.knows(h)) { listener.learn(h, hex.size); learnt++ }
            }
        }
        return learnt
    }

    /** How long a band keeps [h] in mind once far from it, against other country: landmarks and ways are remembered. */
    fun forgetShare(h: Int) = if (isLandmark(h)) LANDMARK_FORGET else if (onRoute[h]) ROUTE_FORGET else 1f

    /**
     * For the check: landmarks by kind; legs, their mean and longest tenth's length, km; hexes on a route
     * and the share of them that are landmarks; how many separate networks the ways make, and the
     * hexes and span (widest distance across, km) of the largest.
     */
    fun summary(): String {
        val byKind = Kind.entries.drop(1).joinToString(", ") { k -> "${k.label} ${landmarks.count { kind(it) == k }}" }
        val legKm = legs.map { l -> (0 until l.size - 1).sumOf { hex.distanceKm(l[it], l[it + 1]) } }.sorted()
        val km = if (legKm.isEmpty()) 0.0 else legKm.average()
        val km90 = if (legKm.isEmpty()) 0.0 else legKm[(legKm.size * 0.9).toInt().coerceAtMost(legKm.size - 1)]
        val onIt = (0 until hex.size).count { onRoute[it] }
        // The networks: route hexes joined by worn ways.
        val seen = BooleanArray(hex.size); var nets = 0; var bigHexes = 0; var bigSpan = 0.0
        for (h0 in 0 until hex.size) {
            if (!onRoute[h0] || seen[h0]) continue
            nets++
            val stack = arrayListOf(h0); seen[h0] = true; var count = 0
            var x0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
            while (stack.isNotEmpty()) {
                val h = stack.removeAt(stack.size - 1); count++
                val (x, y) = hex.centreKm(h); x0 = minOf(x0, x); x1 = maxOf(x1, x); y0 = minOf(y0, y); y1 = maxOf(y1, y)
                for (d in 0 until 6) { val q = hex.neighbour(h, d); if (q >= 0 && !seen[q] && onRoute[q] && worn(h, d)) { seen[q] = true; stack += q } }
            }
            if (count > bigHexes) { bigHexes = count; bigSpan = Math.hypot(x1 - x0, y1 - y0) }
        }
        return "%d landmarks (%s); %d legs, mean %.0f km, longest tenth from %.0f km; %d hexes on routes, %.0f%% of them landmarks; %d networks, largest %d hexes across %.0f km".format(
            landmarks.size, byKind, legs.size, km, km90, onIt, if (onIt == 0) 0.0 else 100.0 * landmarks.size / onIt, nets, bigHexes, bigSpan)
    }

    /** For the check: the share of worn borders that cross a river, against the share of all land borders that do (funnelling at fords). */
    fun fordShare(): Pair<Double, Double> {
        var w = 0; var wr = 0; var all = 0; var allR = 0
        for (h in 0 until hex.size) {
            if (hex.heart[h] < 0) continue
            for (d in 0 until 6) {
                val q = hex.neighbour(h, d); if (q < 0 || hex.heart[q] < 0) continue
                val r = river(h * 6 + d)
                all++; if (r) allR++
                if (wornRaw(h, d)) { w++; if (r) wr++ }
            }
        }
        return (if (w == 0) 0.0 else wr.toDouble() / w) to (if (all == 0) 0.0 else allR.toDouble() / all)
    }
}

/**
 * Routes: how worn a border must be to count as a way; weeks between readings of the graph; ruggedness of a pass; how near water a hex on a path must be to be a
 * water landmark, km; longest leg followed, hexes; how far from where bands meet they tell of
 * routes, km; share of the usual forgetting a band keeps of landmarks and of the ways between.
 */
const val ROUTE_WORN = 0.15f
const val ROUTE_WEEKS = 4
const val PASS_RUGGED = 0.3f
const val WATER_LANDMARK_KM = 1f
const val MAX_LEG_HEXES = 60
const val ROUTE_TOLD_KM = 150.0
const val LANDMARK_FORGET = 0.1f
const val ROUTE_FORGET = 0.3f
