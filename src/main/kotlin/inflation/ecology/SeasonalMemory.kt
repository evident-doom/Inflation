package inflation.ecology

import inflation.world.Biome
import inflation.world.Date
import inflation.world.HexWorld
import java.util.stream.IntStream
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * What the plant-eating residents remember of how their kind fared at each time of year on each kind of ground, and
 * where that draws them ([HexFauna.flow]). Not a place on the map but a kind of place ([typeOf]): its country (biome)
 * and where it lies in the lie of the land about it (the low, middle or high third of the ground within a month's
 * going, each place's height taken over the ground a week's going about it covers). For each kind of ground and month, [fare] keeps how the kind grew there in a week
 * (log of the young born to a head, and of the share that lived), by the rules it is born and dies by, as those there
 * lived it ([learn]): in the terms a place is weighed in ([HexFauna.flow]). From it,
 * [route] finds, from each place, the best ground a month's going reaches by what it remembers of the month after
 * ([monthAt]), and the first step there: so it sets off for ground that will be good by the time it gets there.
 *
 * Its cost is the world's: what it remembers is last years', and where a year goes otherwise the animals walk into
 * what it brings; and they learn only from what their kind lived through, so ground it has not stood on is
 * weighed as the kind fares on average then.
 */
internal class SeasonalMemory(private val hw: HexWorld, private val kinds: List<Species>, private val days: Int) {
    private val n = hw.placeHex.size
    private val hex = hw.hex
    private val weeksPerMonth = Date.DAYS_PER_MONTH / days.toFloat()
    /** The share of what it remembers of a month kept each week of that month it learns again ([learn]). */
    private val keepAWeek = Math.pow(1.0 - 1.0 / YEARS, 1.0 / weeksPerMonth)

    /** The main country of each place. */
    private val biomeOf: IntArray by lazy {
        IntArray(n) { p -> val c = IntArray(Biome.entries.size); for (cell in hw.placeCells[p]) c[hw.world.biome[cell].ordinal]++; c.indices.maxBy { c[it] } }
    }

    private val centreX by lazy { DoubleArray(hex.size) { hex.centreKm(it).first } }
    private val centreY by lazy { DoubleArray(hex.size) { hex.centreKm(it).second } }
    /** The places of each hex. */
    private val placesOf: Array<IntArray> by lazy {
        val lists = Array(hex.size) { ArrayList<Int>(2) }; for (p in 0 until n) lists[hw.placeHex[p]] += p
        Array(hex.size) { lists[it].toIntArray() }
    }
    /** Each place's ground, in fine cells (equal areas). */
    private fun area(p: Int) = hw.placeCells[p].size.toFloat()

    /**
     * Calls [f] with each hex within [km] of hex [h], centre to centre (land or not; [h] too).
     */
    private inline fun within(h: Int, km: Double, f: (Int) -> Unit) {
        val r = (km / (hex.hexKm * 0.86)).toInt() + 1
        val col = h % hex.cols; val row = h / hex.cols
        val cx = centreX; val cy = centreY
        for (rr in max(0, row - r)..min(hex.rows - 1, row + r)) for (cc in max(0, col - r - 1)..min(hex.cols - 1, col + r + 1)) {
            val q = rr * hex.cols + cc
            val dx = cx[q] - cx[h]; val dy = cy[q] - cy[h]
            if (dx * dx + dy * dy <= km * km) f(q)
        }
    }

    /**
     * Each hex's mean of what [sum] and [weight] give by hex (the weighted sum of what is averaged, and its weight) over
     * the ground about it, out to about [km]: spread a hex a pass to the six about, as many passes as [km] has hexes (a
     * bell of about that reach, cheaper than the whole circle). NaN where there is none.
     */
    private fun smooth(sum: FloatArray, weight: FloatArray, km: Double): FloatArray {
        var s = sum.copyOf(); var w = weight.copyOf()
        var s2 = FloatArray(hex.size); var w2 = FloatArray(hex.size)
        repeat(max(1, (km / hex.hexKm).toInt())) {
            val ss = s; val ww = w; val so = s2; val wo = w2
            IntStream.range(0, hex.size).parallel().forEach { h ->
                var a = ss[h]; var b = ww[h]
                for (d in 0 until 6) { val q = hex.neighbour(h, d); if (q >= 0) { a += ss[q]; b += ww[q] } }
                so[h] = a / 7f; wo[h] = b / 7f
            }
            s2 = ss; w2 = ww; s = so; w = wo
        }
        return FloatArray(hex.size) { h -> if (placesOf[h].isNotEmpty() && w[h] > 0f) s[h] / w[h] else Float.NaN }
    }

    /**
     * For kind [s], each hex's third of the lie of the land, low (0), middle (1) or high (2): its height, as the mean of the
     * ground a week's going about it covers, among the hexes within a month's going, by theirs the same way. So a whole
     * upland counts as high and a valley through it as low, not each hex against the next.
     */
    private val reliefByKind = HashMap<Pair<Int, Int>, ByteArray>()
    private fun relief(s: Species): ByteArray {
        val key = weekKm(s).toInt() to monthKm(s).toInt()
        synchronized(reliefByKind) { reliefByKind[key] }?.let { return it }
        val sum = FloatArray(hex.size); val wt = FloatArray(hex.size)
        for (p in 0 until n) { val h = hw.placeHex[p]; sum[h] += area(p) * hw.placeElevationM[p]; wt[h] += area(p) }
        val height = smooth(sum, wt, weekKm(s).toDouble())
        val out = ByteArray(hex.size)
        IntStream.range(0, hex.size).parallel().forEach { h ->
            if (height[h].isNaN()) return@forEach
            var lower = 0; var all = 0
            within(h, monthKm(s).toDouble()) { q -> if (!height[q].isNaN()) { all++; if (height[q] < height[h]) lower++ } }
            out[h] = min(2, 3 * lower / max(1, all)).toByte()
        }
        synchronized(reliefByKind) { reliefByKind[key] = out }
        return out
    }

    /** How far kind [s] goes in a week at its usual going, km: the ground it uses about a place. */
    private fun weekKm(s: Species) = s.dailyTravelKm * days

    /** How far kind [s] goes in a month at its usual going, km. */
    private fun monthKm(s: Species) = s.dailyTravelKm * Date.DAYS_PER_MONTH

    /** The kind of ground each place is to kind [k]: its country and the third of the lie of the land about it, `biome * 3 + third`. */
    private val types = arrayOfNulls<IntArray>(kinds.size)
    fun typeOf(k: Int): IntArray = types[k] ?: synchronized(types) {
        types[k] ?: relief(kinds[k]).let { rel -> IntArray(n) { p -> biomeOf[p] * 3 + rel[hw.placeHex[p]] } }.also { types[k] = it }
    }

    /**
     * Adds a week of kind [k]'s life in [month] to what it remembers in [fare] (its kinds of ground × 12 months, none yet
     * where [seen] is 0), with the head-weeks it has learnt each from in [seen]: for each kind of ground, how the ground let
     * its [heads] there (in head counts at each place) grow in the week ([grew], log). Each is the mean of the weeks the kind
     * lived there by their heads over [YEARS] years or so, the older the less: the ways of a kind are shaped over many
     * generations, and one bad year does not change them.
     */
    fun learn(k: Int, month: Int, heads: FloatArray, grew: FloatArray, fare: FloatArray, seen: FloatArray) {
        val t = typeOf(k)
        val h = DoubleArray(TYPES); val d = DoubleArray(TYPES)
        for (p in 0 until n) { val x = heads[p]; if (!(x > 0f)) continue; h[t[p]] += x; d[t[p]] += x * grew[p] }
        for (i in 0 until TYPES) {
            if (!(h[i] > 0.0)) continue
            val j = i * 12 + month
            val old = seen[j] * keepAWeek
            fare[j] = ((fare[j] * old + d[i]) / (old + h[i])).toFloat()
            seen[j] = (old + h[i]).toFloat()
        }
    }

    /**
     * For kind [k] in [month], a month's log growth on each kind of ground by what it remembers ([fare], learnt from
     * [seen] head-weeks), against how the kind fared on all its ground then: a kind of ground it has lived on little
     * counts as the usual, and as its own as it learns it, a band's month of living ([Species.maxGroupSize]) as much as
     * the usual.
     */
    fun stayOf(k: Int, month: Int, fare: FloatArray, seen: FloatArray): FloatArray {
        val s = kinds[k]
        var known = 0.0; var heads = 0.0
        for (i in 0 until TYPES) { val j = i * 12 + month; if (seen[j] > 0f) { known += fare[j] * seen[j]; heads += seen[j] } }
        val usual = if (heads > 0.0) (known / heads).toFloat() else 0f
        val band = s.maxGroupSize * weeksPerMonth
        return FloatArray(TYPES) { i -> val j = i * 12 + month
            val f = if (seen[j] <= 0f) usual else (fare[j] * seen[j] + usual * band) / (seen[j] + band)
            weeksPerMonth * f }
    }

    /**
     * For kind [k], a month's log growth at each hex in [month] by what it remembers ([stayOf]): a month at a place is a
     * month on the ground a week's going about it covers, its kinds of ground by their area. NaN where no place is. The
     * whole of it, not only how much better than usual the ground is then: ground at its best in its season but poor all
     * the same led them to worse places than they left.
     */
    private fun hexMonth(k: Int, month: Int, fare: FloatArray, seen: FloatArray): FloatArray {
        val t = typeOf(k)
        val stay = stayOf(k, month, fare, seen)
        val sum = FloatArray(hex.size); val wt = FloatArray(hex.size)
        for (p in 0 until n) { val h = hw.placeHex[p]; sum[h] += area(p) * stay[t[p]]; wt[h] += area(p) }
        return smooth(sum, wt, weekKm(kinds[k]).toDouble())
    }

    /** [hexMonth] at each place: how a head of kind [k] would grow in a month there in [month], by what it remembers. */
    fun monthAt(k: Int, month: Int, fare: FloatArray, seen: FloatArray): FloatArray =
        hexMonth(k, month, fare, seen).let { here -> FloatArray(n) { p -> here[hw.placeHex[p]] } }

    /**
     * For kind [k] moving in a month, by what it remembers of how a head fares at each place in the month after ([after],
     * [monthAt]): the best of the places a month's going from each place reaches (the share living through the ways
     * counted), and the way ([ways]) of the first step towards it by the fewest steps into [step] (-1: none better), with
     * how much better it is than staying, in log, into [gain].
     */
    fun route(k: Int, after: FloatArray, ways: HexFauna.FlowWays, step: IntArray, gain: FloatArray) {
        val s = kinds[k]
        val r = routeWays.getOrPut(k) { RouteWays(ways, weekKm(s)) }
        var best = after.copyOf(); var hops = IntArray(n); var via = IntArray(n) { -1 }
        var best2 = FloatArray(n); var hops2 = IntArray(n); var via2 = IntArray(n)
        // (Each pass weighs again only the places whose own or a neighbour's best, steps or way changed in the last: the
        // others would come out as they are.)
        var dirty = ByteArray(n) { 1 }; var next = ByteArray(n)
        val passes = max(1, (monthKm(s) / hex.hexKm).toInt())
        for (pass in 0 until passes) {
            val b0 = best; val h0 = hops; val v0 = via; val b1 = best2; val h1 = hops2; val v1 = via2; val d0 = dirty; val d1 = next
            System.arraycopy(b0, 0, b1, 0, n); System.arraycopy(h0, 0, h1, 0, n); System.arraycopy(v0, 0, v1, 0, n)
            d1.fill(0)
            val moved = IntStream.range(0, n).parallel().map { p ->
                if (d0[p].toInt() == 0) return@map 0
                var b = b0[p]; var h = h0[p]; var w = v0[p]
                for (j in r.from[p] until r.from[p + 1]) {
                    val q = r.to[j]; val x = b0[q] + r.lnKeep[j]
                    // (The better, or as good by fewer steps.)
                    if (x > b || (x == b && w >= 0 && h0[q] + 1 < h)) { b = x; h = h0[q] + 1; w = r.way[j] }
                }
                b1[p] = b; h1[p] = h; v1[p] = w
                if (b != b0[p] || h != h0[p] || w != v0[p]) { d1[p] = 1; for (j in r.backFrom[p] until r.backFrom[p + 1]) d1[r.back[j]] = 1 }
                if (b != b0[p] || w != v0[p]) 1 else 0
            }.sum()
            best = b1; hops = h1; via = v1; best2 = b0; hops2 = h0; via2 = v0; dirty = d1; next = d0
            if (moved == 0) break
        }
        for (p in 0 until n) { step[p] = via[p]; gain[p] = best[p] - after[p] }
    }

    /**
     * A kind's [HexFauna.FlowWays] as its routes take them: only across borders a week's going ([reachKm]) crosses (past
     * them a way is all but closed, [HexFauna.FlowWays.km]), each with the log of the share that lives through it and its
     * index among all the ways ([way]); and the other way about, for each place the places with a way to it ([back]).
     */
    private class RouteWays(ways: HexFauna.FlowWays, reachKm: Float) {
        val from: IntArray; val to: IntArray; val lnKeep: FloatArray; val way: IntArray
        val backFrom: IntArray; val back: IntArray
        init {
            val n = ways.from.size - 1
            val kept = (0 until ways.to.size).filter { ways.km[it] <= reachKm }
            from = IntArray(n + 1); to = IntArray(kept.size); lnKeep = FloatArray(kept.size); way = IntArray(kept.size)
            var j = 0
            for (p in 0 until n) {
                from[p] = j
                for (i in ways.from[p] until ways.from[p + 1]) if (ways.km[i] <= reachKm) { to[j] = ways.to[i]; lnKeep[j] = ln(ways.keep[i]); way[j] = i; j++ }
            }
            from[n] = j
            backFrom = IntArray(n + 1)
            for (q in to) backFrom[q + 1]++
            for (q in 0 until n) backFrom[q + 1] += backFrom[q]
            back = IntArray(to.size); val fill = backFrom.copyOf()
            for (p in 0 until n) for (i in from[p] until from[p + 1]) back[fill[to[i]]++] = p
        }
    }

    /** Each kind's ways as its routes take them, worked out once. */
    private val routeWays = java.util.concurrent.ConcurrentHashMap<Int, RouteWays>()

    companion object {
        /** Kinds of ground: each country by the low, middle and high third of the lie of the land. */
        val TYPES = Biome.entries.size * 3
        /** How many years a kind's memory of its ground is a mean over, roughly: the share of it kept a year is 1 - 1/YEARS. */
        const val YEARS = 10
    }
}
