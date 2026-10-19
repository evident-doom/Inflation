package inflation.world

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The week's prevailing weather pattern over the region. Each brings its own wind, and so its
 * own weather: what the air carries depends on where it comes from.
 */
enum class Regime(
    /** Rain against an average week: fronts bring much, high pressure little. */
    val storminess: Float,
) {
    /** The storm track overhead: fronts from the sea, mild in winter, cool and showery in summer. */
    WESTERLY(1.5f),
    /** Cold, dry air pouring south behind a low: frost, clear skies, snow on north-facing slopes. */
    NORTHERLY(0.7f),
    /** Warm air drawn up from the south ahead of a front: hot, dusty, then rain. */
    SOUTHERLY(1.3f),
    /** A blocking high: easterly drift, dry, still; hard frost in winter, heat in summer. */
    BLOCKING(0.35f),
    /** The summer high: steady north winds over the south (like the Etesians), clear and dry. */
    SUMMER_HIGH(0.4f),
}

/**
 * A simple model of the region's winds. The jet stream, and the storm track under it, lies over
 * the north of the map in summer and moves south in winter, meandering week to week. Each week
 * has a [Regime], which tends to persist for a week or two and is drawn by season otherwise; the
 * summer high holds the south and reaches north only so far ([highReach]), and beyond it the week's
 * other regime blows. Its wind is the regime's flow, stronger near the jet for the westerlies, plus
 * slowly changing eddies.
 *
 * The wind decides where rain falls: air forced up a slope facing it rains out, and the lee of a
 * ridge or a lone volcano lies in its shadow. The pattern for each of eight wind directions is
 * worked out once from the terrain ([rainFactor]). Averaged over the regimes of the season and latitude, rain still
 * comes out at the climate normals: the factors are divided by their seasonal mean ([normalFactor]).
 */
class Winds(
    /** Orographic rain factor per wind direction (sector k: towards angle k·45°, east = 0, south = 90°) and place; null for none. */
    private val sector: Array<FloatArray>?,
) {

    /** Seasonal weight of [r] at [season] (-1 midwinter .. 1 midsummer). */
    fun weight(r: Regime, season: Double): Double {
        val s = (1 + season) / 2 // 0 winter .. 1 summer
        return when (r) {
            Regime.WESTERLY -> blend(0.40, 0.10, s)
            Regime.NORTHERLY -> blend(0.20, 0.10, s)
            Regime.SOUTHERLY -> blend(0.20, 0.05, s)
            Regime.BLOCKING -> blend(0.15, 0.15, s)
            Regime.SUMMER_HIGH -> blend(0.05, 0.60, s)
        }
    }

    /** [winter]'s value through to [summer]'s at [s] (0 winter .. 1 summer). */
    private fun blend(winter: Double, summer: Double, s: Double) = winter * (1 - s) + summer * s

    /**
     * Share of the weeks [r] holds at latitude [lat] at [season]. The summer high is the sinking side of the Hadley cell,
     * and it reaches only so far north ([highReach]); the weeks it leaves there go to the other regimes in their usual
     * proportions, so fronts come more often in the north's summer.
     */
    fun share(r: Regime, season: Double, lat: Double): Double {
        val high = highShare(season)
        val here = high * highReach(lat)
        return if (r == Regime.SUMMER_HIGH) here else weight(r, season) / totalWeight(season) * (1 - here) / (1 - high)
    }

    /** Share of the weeks the summer high holds where it reaches fully, at [season]. */
    fun highShare(season: Double) = weight(Regime.SUMMER_HIGH, season) / totalWeight(season)

    private fun totalWeight(season: Double) = Regime.entries.sumOf { weight(it, season) }

    /**
     * How much of its share the summer high holds at latitude [lat]: all of it south of [HIGH_FULL_LAT], none north of
     * [HIGH_EDGE_LAT]. In ERA5 (1990-2019, 20-50 °E) the June-August air at 500 hPa sinks by 0.04-0.05 Pa/s over 30-36 °N,
     * 0.02 at 37-39 °N, 0.01 at 40-44 °N and not at all at 46 °N, and 1-4% of days are wet at 33-36 °N against 39-51% at
     * 42-45 °N (review 2026-10-19-rain-regimes).
     */
    fun highReach(lat: Double) = ((HIGH_EDGE_LAT - lat) / (HIGH_EDGE_LAT - HIGH_FULL_LAT)).coerceIn(0.0, 1.0)

    /** Mean latitude of the jet stream, °N: about 48 in summer, 36 in winter. */
    fun jetLatitude(season: Double) = 42.0 + 6.0 * season

    /**
     * The regime's wind at latitude [lat] with the jet at [jet]: m/s towards the east ([0]) and
     * towards the south ([1]).
     */
    fun baseWind(r: Regime, lat: Double, jet: Double, out: FloatArray) {
        val nearJet = max(0.3, exp(-((lat - jet) / 8.0).let { it * it }))
        val southOfJet = ((jet - lat) / 6.0).coerceIn(0.0, 1.0)
        val (u, v) = when (r) {
            Regime.WESTERLY -> 9.0 * nearJet to 0.0
            Regime.NORTHERLY -> 1.5 to 7.0
            Regime.SOUTHERLY -> 2.0 to -6.0
            Regime.BLOCKING -> -4.0 to 1.0
            Regime.SUMMER_HIGH -> -1.5 * southOfJet to 2.0 + 4.0 * southOfJet
        }
        out[0] = u.toFloat(); out[1] = v.toFloat()
    }

    /**
     * Temperature the regime brings against the season's normal, °C, before the seasonal mean of
     * all regimes is taken off (so that the normals still hold on average).
     */
    fun warmth(r: Regime, season: Double): Double {
        val winter = max(0.0, -season); val summer = max(0.0, season)
        return when (r) {
            Regime.WESTERLY -> 1.5 * winter - 1.0 * summer
            Regime.NORTHERLY -> -3.0 - 3.0 * winter
            Regime.SOUTHERLY -> 2.5 + 1.0 * summer
            Regime.BLOCKING -> -3.0 * winter + 3.0 * summer
            Regime.SUMMER_HIGH -> 1.0
        }
    }

    /** Seasonal mean of [warmth] over the regimes at latitude [lat]. */
    fun meanWarmth(season: Double, lat: Double): Double = at(season).meanWarmth(lat)

    /** The regimes at [season], worked out once for every latitude ([Season]). */
    fun at(season: Double) = Season(season)

    /** [share] and [meanWarmth] at one season: what depends on the season alone worked out once. */
    inner class Season(season: Double) {
        private val high = highShare(season)
        private val total = totalWeight(season)
        private val weights = DoubleArray(Regime.entries.size) { weight(Regime.entries[it], season) }
        private val warmths = DoubleArray(Regime.entries.size) { warmth(Regime.entries[it], season) }

        /** [Winds.share] at latitude [lat]. */
        fun share(r: Regime, lat: Double): Double {
            val here = high * highReach(lat)
            return if (r == Regime.SUMMER_HIGH) here else weights[r.ordinal] / total * (1 - here) / (1 - high)
        }

        /** [Winds.meanWarmth] at latitude [lat]. */
        fun meanWarmth(lat: Double): Double {
            var sum = 0.0
            for (r in Regime.entries) sum += share(r, lat) * warmths[r.ordinal]
            return sum
        }
    }

    /** Orographic rain factor at [i] for wind (u east, v south) m/s: 1 in calm air or with no terrain. */
    fun rainFactor(i: Int, u: Float, v: Float): Float {
        val s = sector ?: return 1f
        val speed = hypot(u, v)
        if (speed < 0.5f) return 1f
        val a = (atan2(v, u) / (PI / 4)).let { if (it < 0) it + 8 else it }
        val k0 = floor(a).toInt() % 8; val k1 = (k0 + 1) % 8
        val f = (a - floor(a)).toFloat()
        val raw = s[k0][i] * (1 - f) + s[k1][i] * f
        // Light winds barely force air up the slopes.
        return 1f + (raw - 1f) * min(1f, speed / 5f)
    }

    /** Seasonal mean of storminess × [rainFactor] at [i] over the regimes' base winds, for normalising. */
    fun normalFactor(i: Int, lat: Double, season: Double, tmp: FloatArray): Float {
        val jet = jetLatitude(season)
        var w = 0.0; var sum = 0.0
        for (r in Regime.entries) {
            val p = share(r, season, lat)
            baseWind(r, lat, jet, tmp)
            w += p; sum += p * r.storminess * rainFactor(i, tmp[0], tmp[1])
        }
        return (sum / w).toFloat()
    }

    /**
     * For each of eight wind directions: rain is raised where the ground rises into the wind
     * (forced ascent) and lowered in the lee of higher ground upwind (the rain shadow), from the
     * terrain smoothed over a few km, as clouds respond to hills, not to every gully.
     */
    companion object {
        /** Rain multiplies by e for every 100 m per km of slope facing the wind. */
        const val OROGRAPHIC = 0.01f
        /** Rain falls by e for every 1250 m of higher ground upwind. */
        const val SHADOW = 0.0008f
        /** Latitude south of which the summer high holds its full share, °N, and north of which it holds none ([highReach]). */
        const val HIGH_FULL_LAT = 36.0
        const val HIGH_EDGE_LAT = 46.0

        /** The orographic rain factors of every cell of [grid] for the eight wind directions. */
        fun sectors(grid: Grid, elevation: FloatArray): Array<FloatArray> {
            val h = blur(grid, FloatArray(grid.size) { max(0f, elevation[it]) }, (3 / grid.cellKm).toInt().coerceAtLeast(1))
            val step = 4.0 / grid.cellKm
            val shadowKm = doubleArrayOf(6.0, 12.0, 25.0, 50.0)
            return Array(8) { k ->
                val dx = cos(k * PI / 4); val dy = sin(k * PI / 4)
                val out = FloatArray(grid.size)
                java.util.stream.IntStream.range(0, grid.size).parallel().forEach { i ->
                    val x = grid.x(i); val y = grid.y(i)
                    fun at(back: Double): Float {
                        val xx = (x - dx * back).toInt().coerceIn(0, grid.width - 1)
                        val yy = (y - dy * back).toInt().coerceIn(0, grid.height - 1)
                        return h[grid.index(xx, yy)]
                    }
                    val here = h[i]
                    // Rise into the wind over the last 4 km, m per km.
                    val rise = max(0f, here - at(step)) / (step * grid.cellKm).toFloat()
                    // The highest barrier upwind, above this cell, weighed less with distance.
                    var barrier = 0f
                    for (km in shadowKm) barrier = max(barrier, (at(km / grid.cellKm) - here) * (1f - (km / 100.0).toFloat()))
                    out[i] = (exp(OROGRAPHIC * min(rise, 150f) - SHADOW * barrier)).coerceIn(0.2f, 4f)
                }
                out
            }
        }
    }
}
