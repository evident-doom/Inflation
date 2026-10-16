package inflation.ecology

import inflation.Config

import inflation.world.HexWorld
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Each kind's sense of where better country lies, as a bearing per hex: a direction and how much
 * better it is there, 0..1. Recomputed once a month from [quality], how well a place suits the kind
 * this month (climate for its coat, its ground, its food, water).
 *
 * What an animal knows of the land around it:
 * - Its own country within [NEAR_KM]: the ground it and its kind range over.
 * - High ground in sight: peaks show above the horizon from afar, out to about 3.6 km × √(height
 *   above the watcher, m), at most [FAR_KM]; a peak stands for the massif around it, so a kind that
 *   wants the cool of the mountains in summer heads for the mountains it can see.
 * - Which way is warmer: a kind too hot for its coat this month heads for cooler country, one too
 *   cold for warmer, along the lie of the land's climate over a few hundred km (north and up in the
 *   heat, south and down in the cold). So a reindeer set down in a hot desert heads for the hills
 *   and the north until it finds country that suits it.
 *
 * The bearing points the mean way to the places that are better than the best of here, each weighed
 * by how much better and how near; its strength is how far here falls short of the best it knows of.
 */
class HexBearings(
    private val hw: HexWorld,
    private val kinds: List<Species>,
    /** How well place p suits kind s in month m, 0..1. */
    private val quality: (Species, Int, Int) -> Float,
    /** Whether any of a kind are left (the bearings of a kind gone are not worked out). */
    private val present: (Species) -> Boolean = { true },
) {
    private val hex = hw.hex
    private val size = hex.size
    /** For each kind, the bearing per hex: east and south components, km-free, 0..1 long. */
    val east = Array(kinds.size) { FloatArray(size) }
    val south = Array(kinds.size) { FloatArray(size) }
    /** Month the bearings were last worked out for. */
    var month = -1; private set

    /** Forgets the month worked out, so the next update works it out again (after a saved world is loaded). */
    fun forget() { month = -1 }

    private val cx = DoubleArray(size); private val cy = DoubleArray(size)
    /** Hexes within [NEAR_KM] of each land hex. */
    private val near: Array<IntArray>
    /** For each of those, the way to it (unit vector) times how much a place there weighs, and the weight. */
    private val nearE: Array<FloatArray>; private val nearS: Array<FloatArray>; private val nearW: Array<FloatArray>
    /** Peaks in sight of each land hex, beyond [NEAR_KM]; and the hexes of each peak's massif. */
    private val inSight: Array<IntArray>
    private val massif: Array<IntArray>
    /** Which way it gets warmer over the year, unit vector per hex. */
    private val warmerE = FloatArray(size); private val warmerS = FloatArray(size)

    init {
        for (h in 0 until size) { val (x, y) = hex.centreKm(h); cx[h] = x; cy[h] = y }
        fun within(h: Int, km: Double): IntArray {
            val r = (km / (hex.hexKm * 0.86)).toInt() + 1
            val col = h % hex.cols; val row = h / hex.cols
            val out = ArrayList<Int>()
            for (rr in max(0, row - r)..min(hex.rows - 1, row + r)) for (cc in max(0, col - r - 1)..min(hex.cols - 1, col + r + 1)) {
                val q = rr * hex.cols + cc
                if (hex.heart[q] >= 0 && len(cx[q] - cx[h], cy[q] - cy[h]) <= km) out += q
            }
            return out.toIntArray()
        }
        val land = (0 until size).filter { hex.heart[it] >= 0 }
        near = Array(size) { IntArray(0) }
        land.parallelStream().forEach { h -> near[h] = within(h, NEAR_KM).filter { it != h }.toIntArray() }
        nearE = Array(size) { FloatArray(near[it].size) }; nearS = Array(size) { FloatArray(near[it].size) }; nearW = Array(size) { FloatArray(near[it].size) }
        land.parallelStream().forEach { h ->
            for ((i, q) in near[h].withIndex()) {
                val dx = cx[q] - cx[h]; val dy = cy[q] - cy[h]; val d = len(dx, dy)
                val wt = 1.0 / (1.0 + d / FADE_KM)
                nearE[h][i] = (wt * dx / d).toFloat(); nearS[h][i] = (wt * dy / d).toFloat(); nearW[h][i] = wt.toFloat()
            }
        }
        // Peaks: the highest ground of its surroundings within [PEAK_SPACING_KM], high enough to stand out.
        val top = FloatArray(size)
        for (p in 0 until hw.placeHex.size) { val h = hw.placeHex[p]; for (c in hw.placeCells[p]) top[h] = max(top[h], hw.world.elevation[c]) }
        val peaks = land.filter { h -> top[h] >= PEAK_M && within(h, PEAK_SPACING_KM).all { top[it] <= top[h] } }
        massif = Array(size) { IntArray(0) }
        for (pk in peaks) massif[pk] = within(pk, MASSIF_KM)
        inSight = Array(size) { IntArray(0) }
        land.parallelStream().forEach { h ->
            val eye = hw.elevationM[h]
            inSight[h] = peaks.filter { pk ->
                val d = len(cx[pk] - cx[h], cy[pk] - cy[h])
                d > NEAR_KM && d <= min(FAR_KM, 3.57 * sqrt(max(0f, top[pk] - eye).toDouble()))
            }.toIntArray()
        }
        // The lie of the climate: a plane fitted to the year's mean temperature over [CLIMATE_KM] around.
        val meanT = FloatArray(size) { h -> (0 until 12).sumOf { hw.temp[it][h].toDouble() }.toFloat() / 12f }
        land.parallelStream().forEach { h ->
            var sxx = 0.0; var sxy = 0.0; var syy = 0.0; var sxt = 0.0; var syt = 0.0
            val step = 3
            val r = (CLIMATE_KM / (hex.hexKm * 0.86)).toInt()
            val col = h % hex.cols; val row = h / hex.cols
            for (rr in max(0, row - r)..min(hex.rows - 1, row + r) step step) for (cc in max(0, col - r)..min(hex.cols - 1, col + r) step step) {
                val q = rr * hex.cols + cc
                if (hex.heart[q] < 0) continue
                val dx = cx[q] - cx[h]; val dy = cy[q] - cy[h]
                if (len(dx, dy) > CLIMATE_KM) continue
                // Sea-level temperature, so that the mountains do not hide the lie of the land.
                val t = meanT[q] + 6.5e-3 * hw.elevationM[q]
                sxx += dx * dx; sxy += dx * dy; syy += dy * dy; sxt += dx * t; syt += dy * t
            }
            val det = sxx * syy - sxy * sxy
            if (det > 1e-6) {
                val ge = (syy * sxt - sxy * syt) / det; val gs = (sxx * syt - sxy * sxt) / det
                val len = len(ge, gs)
                if (len > 1e-6) { warmerE[h] = (ge / len).toFloat(); warmerS[h] = (gs / len).toFloat() }
            }
        }
    }

    /** Works out every kind's bearings for month [m]. */
    fun update(m: Int) {
        val t0 = System.nanoTime()
        updateMonth(m)
        updateNs += System.nanoTime() - t0
    }

    /** Time spent working out bearings, ns. */
    @Transient var updateNs = 0L; private set

    private fun updateMonth(m: Int) {
        month = m
        val places = hw.placeHex.size
        for ((k, s) in kinds.withIndex()) {
            if (!present(s)) { east[k].fill(0f); south[k].fill(0f); continue }
            // Best of each hex this month, over its height bands.
            val pq = FloatArray(places)
            java.util.stream.IntStream.range(0, places).parallel().forEach { p -> pq[p] = quality(s, p, m) }
            val hq = FloatArray(size)
            for (p in 0 until places) { val h = hw.placeHex[p]; if (pq[p] > hq[h]) hq[h] = pq[p] }
            val be = east[k]; val bs = south[k]
            java.util.stream.IntStream.range(0, size).parallel().forEach { h ->
                if (hex.heart[h] < 0) return@forEach
                val here = hq[h]
                var ve = 0f; var vs = 0f; var w = 0f; var best = here
                val nq = near[h]; val ne = nearE[h]; val ns = nearS[h]; val nw = nearW[h]
                for (i in nq.indices) {
                    val gain = hq[nq[i]] - here
                    if (gain > 0f) { ve += gain * ne[i]; vs += gain * ns[i]; w += nw[i]; best = max(best, hq[nq[i]]) }
                }
                for (pk in inSight[h]) {
                    var top = 0f; for (q in massif[pk]) top = max(top, hq[q])
                    val gain = top - here
                    if (gain <= 0f) continue
                    best = max(best, top)
                    val dx = cx[pk] - cx[h]; val dy = cy[pk] - cy[h]; val d = len(dx, dy)
                    val wt = 1.0 / (1.0 + d / FADE_KM)
                    ve += (gain * wt * dx / d).toFloat(); vs += (gain * wt * dy / d).toFloat(); w += wt.toFloat()
                }
                // The way: the weighed mean way to better places. How strongly: how far short of the best
                // it knows of here falls, so that a kind far out of its country feels it keenly.
                var e = 0f; var so = 0f
                val len0 = len(ve, vs)
                if (w > 0f && len0 > 0f && best > 0f) {
                    val keen = (best - here) / best
                    e = ve / len0 * keen; so = vs / len0 * keen
                }
                // Too hot for its coat this month: toward cooler country; too cold: toward warmer.
                // (A kind bound to water feels the water's warmth through the cold: see [HexFauna.feltC].)
                val air = hw.temp[m][h]
                val water = if (s.maxWaterKm.isFinite()) hexWaterNormalC[h][m] else 0f
                val cold = min(1f, s.coldC(if (water > 0f && water > air) water else air, m) / 10f)
                val hot = min(1f, max(0f, air - s.comfortC.endInclusive) / 10f)
                e += INSTINCT * (cold - hot) * warmerE[h]; so += INSTINCT * (cold - hot) * warmerS[h]
                val len = len(e, so)
                if (len > 1f) { e /= len; so /= len }
                be[h] = e; bs[h] = so
            }
        }
    }

    /** Open water's monthly normals by hex, °C (see [HexFauna.laggedNormals]). */
    private val hexWaterNormalC by lazy { Array(hw.hex.size) { h -> HexFauna.laggedNormals { m -> hw.temp[m][h] } } }

    /** How far moving from hex [from] to hex [to] goes along kind [k]'s bearing, in units of [km]. */
    fun along(k: Int, from: Int, to: Int, km: Double): Float =
        ((east[k][from] * (cx[to] - cx[from]) + south[k][from] * (cy[to] - cy[from])) / km).toFloat()

    /** The bearing's strength for kind [k] at hex [h], 0..1. */
    fun strength(k: Int, h: Int): Float = len(east[k][h], south[k][h])

    companion object {
        /** Country an animal knows around it, km; how fast the draw of a place fades with distance, km. */
        val NEAR_KM = Config.double("t.nearKm", 60.0)
        val FADE_KM = Config.double("t.fadeKm", 50.0)
        /** Peaks: lowest standing out, m; one per this spacing at most, km; the massif a peak stands for, km; furthest seen, km. */
        const val PEAK_M = 600f
        const val PEAK_SPACING_KM = 30.0
        const val MASSIF_KM = 15.0
        const val FAR_KM = 200.0
        /** Span over which the lie of the climate is read, km; the pull of a wrong climate. */
        const val CLIMATE_KM = 250.0
        const val INSTINCT = 0.5f
    }
}

/** Length of a vector: as hypot, without its care for overflow, which costs here. */
private fun len(a: Double, b: Double) = sqrt(a * a + b * b)
private fun len(a: Float, b: Float) = sqrt(a * a + b * b)
