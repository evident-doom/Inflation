package inflation.world

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Long-term monthly climate normals. Temperature follows latitude, elevation (6.5 °C/km lapse
 * rate) and distance from the sea (continentality widens the seasonal range). Precipitation is
 * carried by westerly winds from the sea: each row is swept west to east, air loses moisture
 * as it rains, and rising terrain forces extra rain on windward slopes, leaving rain shadows
 * behind mountains. The southern part has a Mediterranean regime with dry summers.
 */
class Climate(
    val latitude: DoubleArray,
    val distanceToSeaKm: FloatArray,
    /** [month][cell], °C. */
    val monthlyTemp: Array<FloatArray>,
    /** [month][cell], mm per month. */
    val monthlyPrecip: Array<FloatArray>,
    val annualPrecip: FloatArray,
    /** [month][cell], potential evapotranspiration, mm per month (Thornthwaite). */
    val monthlyPet: Array<FloatArray>,
    /** Potential evapotranspiration, mm/yr. */
    val annualPet: FloatArray,
    /** Actual evapotranspiration, mm/yr (Fu–Budyko). */
    val annualAet: FloatArray,
    /** Water leaving each land cell as surface or ground flow, mm/yr. */
    val runoff: FloatArray,
) {
    /** Annual mean temperature per cell, °C, worked out once. */
    private val meanTemps: FloatArray by lazy { FloatArray(monthlyTemp[0].size) { i -> monthlyTemp.sumOf { it[i].toDouble() }.toFloat() / 12f } }
    fun meanTemp(i: Int): Float = meanTemps[i]
    fun warmestMonthTemp(i: Int): Float = monthlyTemp.maxOf { it[i] }
    fun coldestMonthTemp(i: Int): Float = monthlyTemp.minOf { it[i] }
    /** Aridity index, precipitation over potential evapotranspiration. */
    fun aridity(i: Int): Float = annualPrecip[i] / max(annualPet[i], 1f)

    companion object {
        const val LAPSE_RATE = 6.5e-3
        /** Day of the year (from the winter solstice) with the warmest and wettest climate: late July, early January. */
        const val WARMEST_DAY = 210.0
        const val WETTEST_DAY = 30.0

        fun latitudeOfRow(cfg: WorldConfig, grid: Grid, y: Int): Double =
            cfg.northLatitude - (cfg.northLatitude - cfg.southLatitude) * y / (grid.height - 1).coerceAtLeast(1)

        fun generate(grid: Grid, cfg: WorldConfig, elevation: FloatArray, isSea: BooleanArray, noise: Noise): Climate {
            val n = grid.size; val w = grid.width
            val lat = DoubleArray(grid.height) { latitudeOfRow(cfg, grid, it) }
            val dSea = distanceTransformKm(grid, isSea)
            val temp = Array(12) { FloatArray(n) }
            val precip = Array(12) { FloatArray(n) }

            for (i in 0 until n) {
                val l = lat[grid.y(i)]
                val seaLevelMean = 21.0 - 0.65 * (l - 31.0)
                val cont = 1 - exp(-dSea[i] / 250.0)
                val amplitude = 6.5 + 0.35 * (l - 31.0) + 7.0 * cont
                val lapse = LAPSE_RATE * max(elevation[i], 0f)
                for (m in 0 until 12) {
                    val day = m * 30 + 15.0
                    temp[m][i] = (seaLevelMean - lapse + amplitude * cos(2 * PI * (day - WARMEST_DAY) / 360)).toFloat()
                }
            }

            // Moisture sweep along each row, west to east.
            val baseFraction = 0.004 * grid.cellKm   // share of carried moisture that rains out per cell on flat land
            val orographic = 0.0004                  // extra share per metre of rise across one cell
            val smoothElevation = blur(grid, elevation, (8 / grid.cellKm).toInt().coerceAtLeast(1))
            for (m in 0 until 12) {
                val day = m * 30 + 15.0
                for (y in 0 until grid.height) {
                    val l = lat[y]
                    val south = ((cfg.northLatitude - l) / (cfg.northLatitude - cfg.southLatitude)).coerceIn(0.0, 1.0)
                    val seasonality = 0.6 + 0.35 * south
                    val wet = max(0.02, 1 + seasonality * cos(2 * PI * (day - WETTEST_DAY) / 360))
                    val coastAnnual = 950 - 550 * south          // mm/yr at a western coast
                    val background = (380 - 340 * south) / 12.0  // moisture arriving from other directions
                    val q0 = coastAnnual / 12.0 * wet / baseFraction
                    var q = q0
                    for (x in 0 until w) {
                        val i = y * w + x
                        if (isSea[i]) {
                            // Air picks up moisture over open water; a narrow inland sea adds little.
                            q += (q0 - q) * min(1.0, 0.02 * grid.cellKm)
                            precip[m][i] = (coastAnnual / 12.0 * wet * 0.8).toFloat()
                            continue
                        }
                        // Forced ascent follows the large-scale terrain, not every ridge and gully.
                        val prev = if (x > 0) smoothElevation[i - 1] else smoothElevation[i]
                        val rise = max(0f, smoothElevation[i] - max(prev, 0f)).toDouble()
                        val frac = min(0.5, baseFraction + orographic * rise)
                        val rain = q * frac
                        q -= rain * 0.8 // a fifth is recycled by evaporation from wet ground and plants
                        val texture = 1 + 0.25 * noise.fbm(x.toDouble() + m * 3.0, y.toDouble(), 120.0 / grid.cellKm, 3)
                        precip[m][i] = ((rain + background * wet.coerceAtMost(1.6) * 0.8) * texture).toFloat().coerceAtLeast(0f)
                    }
                }
            }

            // Fronts and storms are hundreds of km across; smooth out row-by-row sweep artefacts.
            for (m in 0 until 12) precip[m] = blur(grid, precip[m], (20 / grid.cellKm).toInt().coerceAtLeast(1))
            val annualP = FloatArray(n) { i -> (0 until 12).sumOf { precip[it][i].toDouble() }.toFloat() }
            val pet = FloatArray(n)
            val monthlyPet = Array(12) { FloatArray(n) }
            val aet = FloatArray(n)
            val runoff = FloatArray(n)
            for (i in 0 until n) {
                val l = lat[grid.y(i)]
                val monthly = thornthwaite(DoubleArray(12) { temp[it][i].toDouble() }, l)
                for (m in 0 until 12) monthlyPet[m][i] = monthly[m].toFloat()
                pet[i] = monthly.sum().toFloat()
                val p = annualP[i].toDouble()
                if (isSea[i] || p <= 0) continue
                val ratio = pet[i] / p
                val fu = 1 + ratio - (1 + ratio.pow(2.6)).pow(1 / 2.6)
                aet[i] = (p * fu).toFloat()
                runoff[i] = (p - aet[i]).toFloat().coerceAtLeast(0f)
            }
            return Climate(lat, dSea, temp, precip, annualP, monthlyPet, pet, aet, runoff)
        }

        /** Thornthwaite (1948) potential evapotranspiration in mm per month from monthly mean temperatures. */
        fun thornthwaite(monthlyTemp: DoubleArray, latitudeDeg: Double): DoubleArray {
            val out = DoubleArray(12)
            val heat = monthlyTemp.sumOf { t -> if (t > 0) (t / 5).pow(1.514) else 0.0 }
            if (heat <= 0) return out
            val a = 6.75e-7 * heat.pow(3) - 7.71e-5 * heat.pow(2) + 1.792e-2 * heat + 0.49239
            for (m in 0 until 12) {
                val t = monthlyTemp[m]
                if (t <= 0) continue
                val daylight = Date(m * 30L + 15).dayLengthHours(latitudeDeg)
                val base = if (t < 26.5) 16 * (10 * t / heat).pow(a) else -415.85 + 32.24 * t - 0.43 * t * t
                out[m] = base * daylight / 12
            }
            return out
        }

        /** Chamfer distance (3-4) from every cell to the nearest [target] cell, in km. */
        fun distanceTransformKm(grid: Grid, target: BooleanArray): FloatArray {
            val w = grid.width; val h = grid.height
            val big = Float.MAX_VALUE / 4
            val d = FloatArray(grid.size) { if (target[it]) 0f else big }
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                var v = d[i]
                if (x > 0) v = min(v, d[i - 1] + 3)
                if (y > 0) {
                    v = min(v, d[i - w] + 3)
                    if (x > 0) v = min(v, d[i - w - 1] + 4)
                    if (x < w - 1) v = min(v, d[i - w + 1] + 4)
                }
                d[i] = v
            }
            for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
                val i = y * w + x
                var v = d[i]
                if (x < w - 1) v = min(v, d[i + 1] + 3)
                if (y < h - 1) {
                    v = min(v, d[i + w] + 3)
                    if (x < w - 1) v = min(v, d[i + w + 1] + 4)
                    if (x > 0) v = min(v, d[i + w - 1] + 4)
                }
                d[i] = v
            }
            val scale = (grid.cellKm / 3).toFloat()
            return FloatArray(grid.size) { if (d[it] >= big) big else d[it] * scale }
        }
    }
}
