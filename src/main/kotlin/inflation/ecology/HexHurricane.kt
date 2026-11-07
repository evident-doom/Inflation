package inflation.ecology

import inflation.Stream
import inflation.drawUnit
import inflation.world.Hex
import inflation.world.HexArray
import inflation.world.HexMap
import inflation.world.HexWorld
import inflation.world.Place
import inflation.world.Weather
import inflation.world.get
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A testing artifact, off by default (`t.hurricane` = how many): slow storms that roam the land with the wind, hunt no one and kill
 * every group that ends its move, or crosses, where one stands. They are there to see whether the learned movers keep out of the way
 * of something plain to see: if bands and packs learn to, movement is learning from what it is shown; if they do not, that says
 * something about the movers (or the training). Meant to be taken out again; everything of it is this file and a few hooks
 * ([HexGroups.chooseAndMove], [fillFeatures], [HexGroups.hurricaneKill]).
 *
 * Each storm keeps the hexes of its next [warn] weeks, worked out in advance and never changed once worked out, so that every
 * group can be told which hexes will be struck and when ([urgency]). A storm's path depends on the world, its seed and the
 * wind, never on the groups, so runs that differ in what the groups do see the same storms. It draws nothing from the
 * model's shared streams.
 */
class HexHurricane(
    private val hex: HexMap, private val hw: HexWorld, private val seed: Long,
    count: Int, private val speed: Float, private val radius: Int, warnWeeks: Int,
    /** Whether groups are told where the storms strike, and whether a struck group dies. */
    val told: Boolean, val kills: Boolean,
) : Peril {
    private val warn = warnWeeks.coerceIn(1, Peril.MAX_STRIKE_WEEKS - 1)
    private val width = warn + 1
    /** Storm k's hexes now (index 0) and in each of the next [warn] weeks. */
    private val queue = HexArray(count * width)
    private val previous = IntArray(count) { -1 }
    /** A storm's smoothed wind (east, south, m/s), the turn it holds from the heading the wind gives, and the part of a hex it has gone. */
    private val windEast = FloatArray(count); private val windSouth = FloatArray(count)
    private val wobble = FloatArray(count); private val carry = FloatArray(count)
    private var steps = 0L
    private var started = false
    /** For each hex, the first of the coming weeks (0 = this one) it lies in a storm's reach; [NEVER] if none does. */
    private val first = IntArray(hex.size) { NEVER }
    /** For each hex, the coming weeks it lies in a storm's reach: bit w for w weeks ahead ([Peril.strikeWeeks]). */
    private val weeks = IntArray(hex.size)

    val count get() = windEast.size

    /** Moves the storms on a week, given this week's [weather]: the first week sets them down; every later week drops the week that has passed and works out one more ahead. */
    fun advance(weather: Weather) {
        if (!started) {
            started = true
            val land = ArrayList<Hex>(); for (h in hex.all) if (hex.heart[h] >= 0) land += h
            if (land.isEmpty()) return
            for (k in 0 until count) {
                val at = land[(drawUnit(seed, Stream.HURRICANE, k.toLong(), -1L) * land.size).toInt().coerceAtMost(land.size - 1)]
                queue[k * width] = at
                for (w in 1..warn) queue[k * width + w] = next(k, queue[k * width + w - 1], weather)
            }
        } else {
            for (k in 0 until count) {
                System.arraycopy(queue.a, k * width + 1, queue.a, k * width, warn)
                queue[k * width + warn] = next(k, queue[k * width + warn - 1], weather)
            }
        }
        steps++
        mark()
    }

    /** Where storm [k] goes after [from], by the wind at [from] (as it has smoothed) and the turn it holds, over land only. */
    private fun next(k: Int, here: Hex, weather: Weather): Hex {
        val p = Place(hw.placesOf(here)[0])
        windEast[k] += SMOOTH * (weather.windEast(p.i) - windEast[k]); windSouth[k] += SMOOTH * (weather.windSouth(p.i) - windSouth[k])
        wobble[k] = WOBBLE_KEEP * wobble[k] + WOBBLE_TURN * (drawUnit(seed, Stream.HURRICANE, k.toLong(), steps + 1, (queue.a.size + here.i).toLong()) - 0.5f)
        carry[k] += speed
        var at = here
        while (carry[k] >= 1f) {
            carry[k] -= 1f
            val toward = stepFrom(k, at)
            if (toward == at) break
            previous[k] = at.i; at = toward
        }
        return at
    }

    /** The land neighbour of [at] best along storm [k]'s heading, not back where it came from unless nothing else is open. */
    private fun stepFrom(k: Int, at: Hex): Hex {
        val speedNow = hypot(windEast[k], windSouth[k])
        // (A calm leaves it drifting the way it was turned.)
        val a = if (speedNow > CALM) Math.atan2(windSouth[k].toDouble(), windEast[k].toDouble()) + wobble[k] else wobble[k].toDouble()
        val hx = cos(a); val hy = sin(a)
        var best = Hex.NONE; var bestDot = Double.NEGATIVE_INFINITY; var back = Hex.NONE
        for (d in 0 until 6) {
            val b = hex.neighbour(at, d); if (b.none || hex.heart[b] < 0) continue
            if (b.i == previous[k]) { back = b; continue }
            val dot = DIR_EAST[d] * hx + DIR_SOUTH[d] * hy
            if (dot > bestDot) { bestDot = dot; best = b }
        }
        return if (!best.none) best else if (!back.none) back else at
    }

    /** Works out [first] from the storms' queues. */
    private fun mark() {
        first.fill(NEVER); weeks.fill(0)
        for (k in 0 until count) for (w in 0..warn) {
            val h = queue[k * width + w]; if (h.none) continue
            val seen = HashSet<Hex>(); seen += h
            if (w < first[h.i]) first[h.i] = w
            weeks[h.i] = weeks[h.i] or (1 shl w)
            var ring = listOf(h)
            repeat(radius) {
                ring = hex.nextRing(ring, seen)
                for (r in ring) { if (w < first[r.i]) first[r.i] = w; weeks[r.i] = weeks[r.i] or (1 shl w) }
            }
        }
    }

    /** Whether hex [h] is struck this week. */
    fun struck(h: Hex) = first[h.i] == 0

    /** How soon hex [h] is struck, as a group would be told: 1 for this week, falling by a [width]th for each week ahead, 0 if not within the weeks worked out. */
    fun urgency(h: Hex): Float = first[h.i].let { if (it == NEVER) 0f else (width - it).toFloat() / width }

    /** A storm kills all it strikes: as deadly as it is soon, when groups are told of it and it kills ([Peril]). */
    override fun ahead(h: Hex): Float = if (told && kills) urgency(h) else 0f

    /** The coming weeks a storm strikes hex [h], when groups are told of it and it kills ([Peril.strikeWeeks]); 0 if none. */
    override fun strikeWeeks(h: Hex): Int = if (told && kills) weeks[h.i] else 0

    /** The hex where a storm (or recruiter) stands this week that is nearest [from]; [Hex.NONE] if none was set down. */
    fun nearest(from: Hex): Hex {
        var best = Hex.NONE; var bestKm = Double.POSITIVE_INFINITY
        for (k in 0 until count) { val h = queue[k * width]; if (h.none) continue; val d = hex.distanceKm(from, h); if (d < bestKm) { bestKm = d; best = h } }
        return best
    }

    /** The hexes storms stand on this week (for logs and tests). */
    fun now(): List<Hex> = (0 until count).map { queue[it * width] }

    /** One line for the run log: where each storm is. */
    fun describe(): String = (0 until count).joinToString(", ") { k -> val (x, y) = hex.centreKm(queue[k * width]); "storm %d at (%.0f, %.0f) km".format(k, x, y) }

    companion object {
        const val NEVER = Int.MAX_VALUE
        /** The weight of each week's wind in a storm's smoothed wind (about eight weeks). */
        private const val SMOOTH = 0.125f
        /** How much of its turn a storm keeps a week, and how far it may add to it (radians). */
        private const val WOBBLE_KEEP = 0.9f
        private const val WOBBLE_TURN = 0.35f
        /** The wind below which a storm drifts as it was turned, m/s. */
        private const val CALM = 0.3f
        /** The directions of [HexMap.neighbour] (0 E, 1 SE, 2 SW, 3 W, 4 NW, 5 NE) as east and south. */
        private val DIR_EAST = doubleArrayOf(1.0, 0.5, -0.5, -1.0, -0.5, 0.5)
        private val DIR_SOUTH = doubleArrayOf(0.0, 0.866, 0.866, 0.0, -0.866, -0.866)
    }
}
