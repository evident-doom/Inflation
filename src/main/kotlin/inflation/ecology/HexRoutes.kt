package inflation.ecology

import inflation.world.Hex
import inflation.world.HexArray
import inflation.world.toHexArray
import inflation.world.get
import inflation.world.set
import inflation.world.Channel
import inflation.world.HexMap
import inflation.world.HexWorld
import inflation.world.RiverClass
import java.util.Arrays
import kotlin.math.max
import kotlin.math.min

/**
 * Routes and landmarks: the worn paths read as a graph. Where the ways meet, cross a river, go over
 * broken country, come to water or end, there is a landmark (a junction, ford, pass, water or end);
 * between two landmarks runs a leg, the chain of hexes the path follows. A band that knows two
 * landmarks and the leg between can be told the way, and can follow it through country it never
 * walked; a route is remembered by its landmarks long after the country round about is forgotten.
 * Made afresh every [ROUTE_WEEKS] weeks from the paths as they are worn and grow over.
 *
 * The country has landmarks of its own too, on a route or not, that anyone can point to and name: a
 * hilltop higher than all round it, a lake, a spring, where two rivers meet, where a river comes to the
 * sea. These are found once, from the land ([natural]), and are told and remembered like the others.
 */
class HexRoutes(private val g: HexGroups) {
    enum class Kind(val label: String) {
        NONE("none"), JUNCTION("junction"), FORD("ford"), PASS("pass"), WATER("water"), END("end"),
        PEAK("hilltop"), LAKE("lake"), SPRING("spring"), CONFLUENCE("rivers meet"), MOUTH("river mouth"),
    }

    private val hex = g.hex
    /** Per hex: the kind of landmark there ([Kind.ordinal]; 0 for none). */
    val landmark = ByteArray(hex.size)
    /** Per hex: whether a worn path runs through it. */
    val onRoute = BooleanArray(hex.size)
    /** The legs: the hexes of each, from one landmark to the next, ends included. */
    val legs = ArrayList<HexArray>()
    /** Per landmark hex: the legs that start or end there. */
    val legsAt = HashMap<Hex, IntArray>()
    /** The landmarks, in hex order. */
    var landmarks = HexArray.EMPTY; private set
    /**
     * Places: landmarks told of as one (the ford by the junction, the hill above the lake), each the most
     * telling landmark and the others within a ring of it. [places] are those landmarks' hexes; [placeOf]
     * gives each landmark hex its place (-1 off any). Legs run between places, not within one.
     */
    var places = HexArray.EMPTY; private set
    val placeOf = IntArray(hex.size) { -1 }
    /** Per leg: how worn its least worn border is (its use, as the weakest stretch shows it). */
    var legWear = FloatArray(0); private set
    private var weeks = 0

    fun kind(h: Hex) = Kind.entries[landmark[h].toInt()]
    fun isLandmark(h: Hex) = landmark[h] != 0.toByte()

    /** Called once a week, after the paths are worn: every [ROUTE_WEEKS] weeks the graph is read afresh. */
    fun week() { if (weeks++ % ROUTE_WEEKS == 0) build() }

    private fun wornRaw(h: Hex, d: Int) = g.wear.paths[hex.edge(h, d)] >= ROUTE_WORN
    /** Per border (hex × 6 + side): whether it is a way of the routes, as last read ([build]). */
    private val way = BooleanArray(hex.size * 6)
    private fun worn(h: Hex, d: Int) = way[hex.edge(h, d)]
    /** Whether the border is a river that cannot just be stepped over: deeper than the knee at its easiest crossing. */
    private fun river(i: Int) = hex.riverDepthM[i] >= HexMap.KNEE

    /**
     * Per hex: its natural landmark ([Kind.ordinal]; 0 for none), the strongest if several: a river's mouth,
     * then where two rivers meet, a lake, a spring, a hilltop; of each kind the greatest within two rings.
     * A mouth or meeting is of a river (not a stream, [RiverClass.RIVER]) with the streams that cannot be
     * stepped over (deeper than the knee) that join it; a spring counts where it is the water of the
     * country, with no river or lake within an easy walk.
     */
    val natural: ByteArray by lazy {
        val world = g.hw.world; val grid = world.grid; val water = world.water; val recv = water.drainage.receiver
        // Candidates by kind: hex to how great the feature is there (height, cells of spring or lake, river flow).
        val found = Kind.entries.associateWith { HashMap<Hex, Float>() }
        fun land(h: Hex) = if (!h.none && hex.heart[h] >= 0) h else (0 until 6).map { if (h.none) Hex.NONE else hex.neighbour(h, it) }.firstOrNull { !it.none && hex.heart[it] >= 0 } ?: Hex.NONE
        fun note(h: Hex, k: Kind, v: Float) { if (!h.none) found.getValue(k).merge(h, v, ::maxOf) }
        fun river(i: Int) = world.riverClass(i) >= RiverClass.STREAM && Channel.depthM(water.discharge[i]) > HexMap.KNEE
        // A hilltop: the highest land of the hex.
        for (h in hex.all) if (hex.heart[h] >= 0) note(h, Kind.PEAK, hex.highM[h])
        // Springs and oases, from the fine cells.
        val drink = g.land.drink
        fun dry(h: Hex) = g.hw.placesOf(h).all { p -> drink.nearRiver[p] <= 0f && drink.nearLake[p] <= 0f }
        for (i in 0 until grid.size) if (!world.isSea[i] && (drink.spring[i] || drink.oasis[i]) && dry(hex.owner[i])) found.getValue(Kind.SPRING).merge(hex.owner[i], 1f, Float::plus)
        // Where rivers meet, and come to the sea: from the fine cells' flow.
        val inflows = ByteArray(grid.size)
        for (i in 0 until grid.size) {
            val r = recv[i]
            if (r == i || !river(i)) continue
            if (world.isSea[r]) { if (world.riverClass(i) >= RiverClass.RIVER) note(land(hex.owner[i]), Kind.MOUTH, water.discharge[i]) }
            else if (inflows[r] < 2) inflows[r]++
        }
        for (i in 0 until grid.size) if (inflows[i] >= 2 && world.riverClass(i) >= RiverClass.RIVER) note(land(hex.owner[i]), Kind.CONFLUENCE, water.discharge[i])
        // A lake: each one, by the land hex that holds most of it (or most of its shore).
        val lakeOf = IntArray(grid.size) { -1 }; var lakes = 0
        val share = HashMap<Long, Int>(); val size = ArrayList<Int>()
        for (i0 in 0 until grid.size) {
            if (!water.isLake(i0) || lakeOf[i0] >= 0) continue
            val id = lakes++; val stack = arrayListOf(i0); lakeOf[i0] = id; var n = 0
            while (stack.isNotEmpty()) {
                val i = stack.removeAt(stack.size - 1); n++
                val h = hex.owner[i]
                val holders = if (hex.heart[h] >= 0) listOf(h) else (0 until 6).map { hex.neighbour(h, it) }.filter { !it.none && hex.heart[it] >= 0 }
                for (q in holders) share.merge(id.toLong() * hex.size + q.i, 1, Int::plus)
                val x = grid.x(i); val y = grid.y(i)
                for ((dx, dy) in arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    val nx = x + dx; val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= grid.width || ny >= grid.height) continue
                    val j = grid.index(nx, ny)
                    if (lakeOf[j] < 0 && water.isLake(j)) { lakeOf[j] = id; stack += j }
                }
            }
            size += n
        }
        val holder = HashMap<Int, Pair<Hex, Int>>()
        for ((key, n) in share) { val id = (key / hex.size).toInt(); val h = Hex((key % hex.size).toInt()); if (n > (holder[id]?.second ?: 0)) holder[id] = h to n }
        for ((id, hn) in holder) note(hn.first, Kind.LAKE, size[id].toFloat())
        // Of features of a kind within two rings of each other (about 20 km, as far as one is seen or told
        // apart), the greatest is the landmark: the hill above the rest, the lake and not the pond beside it.
        val out = ByteArray(hex.size)
        for ((k, at) in found) for ((h, v) in at) {
            val seen = hashSetOf(h); var ring = listOf(h); var greatest = true
            for (r in 0 until 2) { ring = hex.nextRing(ring, seen); if (ring.any { q -> at[q]?.let { it > v || (it == v && q < h) } == true }) { greatest = false; break } }
            if (greatest && out[h] < k.ordinal) out[h] = k.ordinal.toByte()
        }
        out
    }

    fun build() {
        Arrays.fill(landmark, 0); Arrays.fill(onRoute, false); Arrays.fill(placeOf, -1); legs.clear(); legsAt.clear()
        val hw = g.hw; val land = g.land
        fun rug(h: Hex) = hw.firstPlaceIn(h).takeIf { !it.none }?.let { land.ruggedness[it] } ?: 0f
        fun water(h: Hex) = hw.firstPlaceIn(h).let { !it.none && land.drink.waterKmMean[it] <= WATER_LANDMARK_KM }
        // A pass: the way through broken country, rougher all round than where the path goes.
        fun pass(h: Hex): Boolean {
            var round = 0f; var k = 0
            for (d in 0 until 6) { val q = hex.neighbour(h, d); if (!q.none && hex.heart[q] >= 0) { round += rug(q); k++ } }
            return rug(h) >= PASS_RUGGED && k > 0 && round / k >= rug(h)
        }
        // A way is a line of wear, not a worn patch: a border counts where it is worn, and at least as worn as
        // the borders beside it running the same way (from the two hexes flanking it), so of side-by-side
        // strands across country walked all over, the most trodden is the way. (Wear either way across.)
        fun wear(h: Hex, d: Int): Float {
            val q = hex.neighbour(h, d); if (q.none || hex.heart[h] < 0 || hex.heart[q] < 0) return 0f
            return max(g.wear.paths[hex.edge(h, d)], g.wear.paths[hex.edge(q, (d + 3) % 6)])
        }
        for (h in hex.all) for (d in 0 until 6) {
            val w = wear(h, d)
            way[hex.edge(h, d)] = w >= ROUTE_WORN && hex.neighbour(h, (d + 1) % 6).let { a -> a.none || wear(a, d) <= w } &&
                hex.neighbour(h, (d + 5) % 6).let { b -> b.none || wear(b, d) <= w }
        }
        // (Both ways across a border agree: a border is a way if it is one from either side.)
        for (h in hex.all) for (d in 0 until 6) { val q = hex.neighbour(h, d); if (!q.none && way[hex.edge(h, d)]) way[hex.edge(q, (d + 3) % 6)] = true }
        // A worn way that goes off a route into the next hex and stops there, with nothing there to go for
        // (water, a pass, a river crossed), is a group leaving the route to range, not a way of its own: a
        // way passes through at least one hex, as only going through country wears one (see [PathWear]).
        val cut = ArrayList<Pair<Hex, Int>>()
        for (h in hex.all) {
            if (hex.heart[h] < 0) continue
            var n = 0; var only = -1
            for (d in 0 until 6) if (way[hex.edge(h, d)]) { n++; only = d }
            if (n != 1 || water(h) || pass(h) || river(hex.edge(h, only))) continue
            val q = hex.neighbour(h, only)
            if (!q.none && (0 until 6).count { way[hex.edge(q, it)] } >= 3) cut += h to only
        }
        for ((h, d) in cut) { way[hex.edge(h, d)] = false; hex.neighbour(h, d).takeIf { !it.none }?.let { q -> way[hex.edge(q, (d + 3) % 6)] = false } }
        // Of like features in a row along a way, the best is the one told of (ties: the lower hex): of the
        // crossings of a river a way walked along it makes as it winds between the hexes, the shallowest is
        // the ford; of passes one after another up broken country, the easiest is the pass. [cost]: lower is
        // better, infinite where there is none.
        fun best(h: Hex, cost: FloatArray): Boolean {
            if (cost[h] == Float.POSITIVE_INFINITY) return false
            for (d in 0 until 6) {
                if (!worn(h, d)) continue
                val q = hex.neighbour(h, d)
                if (!q.none && (cost[q] < cost[h] || cost[q] == cost[h] && q < h)) return false
            }
            return true
        }
        // A ford: where a way crosses a river too deep to step over, by the shallowest crossing of the hex.
        val fordM = FloatArray(hex.size) { Float.POSITIVE_INFINITY }
        for (h in hex.all) if (hex.heart[h] >= 0) for (d in 0 until 6) if (worn(h, d) && river(hex.edge(h, d))) fordM[h] = min(fordM[h], hex.riverDepthM[hex.edge(h, d)])
        // A pass, by how rugged it is.
        val passRug = FloatArray(hex.size) { Float.POSITIVE_INFINITY }
        for (h in hex.all) if (hex.heart[h] >= 0 && (0 until 6).any { worn(h, it) } && pass(h)) passRug[h] = rug(h)
        for (h in hex.all) {
            if (hex.heart[h] < 0) continue
            var ways = 0
            for (d in 0 until 6) if (worn(h, d)) ways++
            if (ways == 0) { landmark[h] = natural[h]; continue }
            onRoute[h] = true
            val ford = best(h, fordM)
            val pass = best(h, passRug)
            val water = water(h)
            landmark[h] = (when {
                ways >= 3 -> Kind.JUNCTION; ford -> Kind.FORD; natural[h] != 0.toByte() -> Kind.entries[natural[h].toInt()]
                pass -> Kind.PASS; ways == 1 -> Kind.END; water -> Kind.WATER; else -> Kind.NONE
            }).ordinal.toByte()
        }
        landmarks = hex.all.filter { isLandmark(it) }.toHexArray()
        // Places: the most telling landmark not yet in a place takes in those within a ring of it. Natural
        // ones tell most (anyone can see them), then fords and passes, then junctions; of landmarks alike,
        // the best: the shallowest ford, the easiest pass, the junction of the most travelled ways (natural
        // ones are already the greatest of their kind around).
        fun telling(h: Hex): Int = when (kind(h)) {
            Kind.MOUTH, Kind.CONFLUENCE, Kind.LAKE, Kind.SPRING, Kind.PEAK -> 100 + landmark[h]
            Kind.FORD, Kind.PASS -> 50 + landmark[h]
            Kind.JUNCTION -> 10
            else -> 0
        }
        fun better(h: Hex): Float = when (kind(h)) {
            Kind.FORD -> -fordM[h]
            Kind.PASS -> -passRug[h]
            Kind.JUNCTION -> (0 until 6).sumOf { d -> if (worn(h, d)) hex.neighbour(h, d).let { q -> wearOf(h, q).toDouble() } else 0.0 }.toFloat()
            else -> 0f
        }
        val seeds = ArrayList<Hex>()
        for (h in landmarks.toList().sortedWith(compareByDescending<Hex> { telling(it) }.thenByDescending { better(it) }.thenBy { it })) {
            if (placeOf[h] >= 0) continue
            val id = seeds.size; seeds += h; placeOf[h] = id
            for (d in 0 until 6) { val q = hex.neighbour(h, d); if (!q.none && isLandmark(q) && placeOf[q] < 0) placeOf[q] = id }
        }
        places = seeds.toHexArray()
        // The legs: from each landmark along each worn way, through hexes with two ways, to the next landmark.
        val at = HashMap<Hex, ArrayList<Int>>()
        val done = HashSet<Pair<Hex, Hex>>()
        for (a in landmarks) for (d in 0 until 6) {
            if (!worn(a, d)) continue
            val first = hex.neighbour(a, d); if (first.none || !onRoute[first]) continue
            val way = arrayListOf(a, first); var prev = a; var cur = first
            while (!isLandmark(cur) && way.size < MAX_LEG_HEXES) {
                val next = (0 until 6).map { hex.neighbour(cur, it) to it }.firstOrNull { (q, e) -> !q.none && q != prev && worn(cur, e) && onRoute[q] }?.first ?: break
                prev = cur; cur = next; way += cur
            }
            if (!isLandmark(cur)) continue
            val b = cur
            if (a == b || placeOf[a] == placeOf[b]) continue
            // Each leg once: by its start and first step, either way round.
            if (!done.add(a to first)) continue
            done.add(b to way[way.size - 2])
            val i = legs.size; legs += way.toHexArray()
            at.getOrPut(a) { ArrayList() } += i; at.getOrPut(b) { ArrayList() } += i
        }
        for ((h, l) in at) legsAt[h] = l.toIntArray()
        legWear = FloatArray(legs.size) { i -> val l = legs[i]; (0 until l.size - 1).minOf { k -> wearOf(l[k], l[k + 1]) } }
    }

    /** How worn the border between neighbours [a] and [b] is, the more worn way across. */
    private fun wearOf(a: Hex, b: Hex): Float {
        val d = (0 until 6).first { hex.neighbour(a, it) == b }
        return max(g.wear.paths[hex.edge(a, d)], g.wear.paths[hex.edge(b, (d + 3) % 6)])
    }

    /**
     * What [teller] tells [listener] of the routes it knows within [ROUTE_TOLD_KM] of [here]: each
     * landmark it knows, and the hexes of each leg between two landmarks it knows (it can describe the
     * way from one to the next). Returns how many hexes the listener newly learnt.
     */
    fun tell(teller: Band, listener: Band, here: Hex): Int {
        var learnt = 0
        // (Only the rows of hexes within reach: the landmarks are in hex order, row by row.)
        val rows = (ROUTE_TOLD_KM / hex.rowKm).toInt() + 1
        val row = hex.row(here)
        val from = landmarks.binarySearch(hex.at(0, max(0, row - rows))).let { if (it < 0) -it - 1 else it }
        val until = Hex((row + rows + 1) * hex.cols)
        for (i in from until landmarks.size) {
            val a = landmarks[i]
            if (a >= until) break
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
    fun forgetShare(h: Hex) = if (isLandmark(h)) LANDMARK_FORGET else if (onRoute[h]) ROUTE_FORGET else 1f

    /**
     * For the check: landmarks by kind, and the places they make; legs, their mean and longest tenth's length, km; hexes on a route
     * and the share of them that are landmarks; how many separate networks the ways make, and the
     * hexes and span (widest distance across, km) of the largest.
     */
    fun summary(): String {
        val byKind = Kind.entries.drop(1).joinToString(", ") { k -> "${k.label} ${landmarks.count { kind(it) == k }}" }
        val legKm = legs.map { l -> (0 until l.size - 1).sumOf { hex.distanceKm(l[it], l[it + 1]) } }.sorted()
        val km = if (legKm.isEmpty()) 0.0 else legKm.average()
        val km90 = if (legKm.isEmpty()) 0.0 else legKm[(legKm.size * 0.9).toInt().coerceAtMost(legKm.size - 1)]
        val onIt = onRoute.count { it }
        val marked = hex.all.count { onRoute[it] && isLandmark(it) }
        // The networks: route hexes joined by worn ways.
        val seen = BooleanArray(hex.size); var nets = 0; var bigHexes = 0; var bigSpan = 0.0
        for (h0 in hex.all) {
            if (!onRoute[h0] || seen[h0]) continue
            nets++
            val stack = arrayListOf(h0); seen[h0] = true; var count = 0
            var x0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
            while (stack.isNotEmpty()) {
                val h = stack.removeAt(stack.size - 1); count++
                val (x, y) = hex.centreKm(h); x0 = minOf(x0, x); x1 = maxOf(x1, x); y0 = minOf(y0, y); y1 = maxOf(y1, y)
                for (d in 0 until 6) { val q = hex.neighbour(h, d); if (!q.none && !seen[q] && onRoute[q] && worn(h, d)) { seen[q] = true; stack += q } }
            }
            if (count > bigHexes) { bigHexes = count; bigSpan = Math.hypot(x1 - x0, y1 - y0) }
        }
        val placesOnRoute = places.count { onRoute[it] }
        return ("%d landmarks (%s) in %d places, %d of them on routes (%.0f%% of route hexes); %d legs between places, mean %.0f km, longest tenth from %.0f km; " +
            "%d hexes on routes, %.0f%% of them landmarks; %d networks, largest %d hexes across %.0f km").format(
            landmarks.size, byKind, places.size, placesOnRoute, if (onIt == 0) 0.0 else 100.0 * placesOnRoute / onIt, legs.size, km, km90,
            onIt, if (onIt == 0) 0.0 else 100.0 * marked / onIt, nets, bigHexes, bigSpan)
    }

    /** For the check: the share of worn borders that cross a river, against the share of all land borders that do (funnelling at fords). */
    fun fordShare(): Pair<Double, Double> {
        var w = 0; var wr = 0; var all = 0; var allR = 0
        for (h in hex.all) {
            if (hex.heart[h] < 0) continue
            for (d in 0 until 6) {
                val q = hex.neighbour(h, d); if (q.none || hex.heart[q] < 0) continue
                val r = river(hex.edge(h, d))
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
internal const val ROUTE_WORN = 0.15f
internal const val ROUTE_WEEKS = 4
internal const val PASS_RUGGED = 0.3f
internal const val WATER_LANDMARK_KM = 1f
internal const val MAX_LEG_HEXES = 60
internal const val ROUTE_TOLD_KM = 150.0
internal const val LANDMARK_FORGET = 0.1f
internal const val ROUTE_FORGET = 0.3f
