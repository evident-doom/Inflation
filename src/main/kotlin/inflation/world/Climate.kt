package inflation.world

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Long-term monthly climate normals. Temperature follows latitude, elevation (6.5 °C/km lapse
 * rate) and distance from the sea (continentality widens the seasonal range). Precipitation is
 * carried from every sea by the winds of each weather regime ([Regime]): the air holds what the
 * sea's warmth allows, gains what the land evaporates, and rains in fronts under the jet and where
 * it is lifted over high ground, leaving rain shadows behind mountains (see [carry]). Summers are
 * dry where the jet and its storms have moved north, as in the Mediterranean.
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
        /** Day of the year (from the winter solstice) with the warmest climate: late July. */
        const val WARMEST_DAY = 210.0
        
        fun latitudeOfRow(cfg: WorldConfig, grid: Grid, y: Int): Double =
            cfg.northLatitude - (cfg.northLatitude - cfg.southLatitude) * y / (grid.height - 1).coerceAtLeast(1)

        fun generate(grid: Grid, cfg: WorldConfig, elevation: FloatArray, isSea: BooleanArray, noise: Noise): Climate {
            val n = grid.size
            val lat = DoubleArray(grid.height) { latitudeOfRow(cfg, grid, it) }
            val dSea = distanceTransformKm(grid, isSea)
            val temp = Array(12) { FloatArray(n) }
            val precip = Array(12) { FloatArray(n) }

            for (i in 0 until n) {
                val l = lat[grid.y(i)]
                val cont = 1 - exp(-dSea[i] / 250.0)
                val lapse = LAPSE_RATE * max(elevation[i], 0f)
                for (m in 0 until 12) temp[m][i] = (seaLevelTemp(l, m * 30 + 15.0, cont) - lapse).toFloat()
            }
            val pet = FloatArray(n)
            val monthlyPet = Array(12) { FloatArray(n) }
            for (i in 0 until n) {
                val monthly = thornthwaite(DoubleArray(12) { temp[it][i].toDouble() }, lat[grid.y(i)])
                for (m in 0 until 12) monthlyPet[m][i] = monthly[m].toFloat()
                pet[i] = monthly.sum().toFloat()
            }

            // Moisture carried by the winds of each weather regime from every sea (see [carry]). What the land
            // evaporates goes back into the air, and how much it evaporates depends on the rain, so the two
            // are worked out together a few times over.
            val evap = Array(12) { FloatArray(n) }
            repeat(RECYCLING_PASSES) { pass ->
                carry(grid, cfg, elevation, isSea, lat, temp, evap, precip)
                if (pass == RECYCLING_PASSES - 1) return@repeat
                java.util.stream.IntStream.range(0, n).parallel().forEach { i ->
                    val p = (0 until 12).sumOf { precip[it][i].toDouble() }
                    val aet = if (isSea[i] || p <= 0 || pet[i] <= 0) 0.0 else p * fu(pet[i] / p)
                    // Evaporation follows the month's demand: water stored from the wet season goes in the warm one.
                    for (m in 0 until 12) evap[m][i] = if (pet[i] > 0) (aet * monthlyPet[m][i] / pet[i] / 30).toFloat() else 0f
                }
            }
            for (m in 0 until 12) for (i in 0 until n) {
                val x = grid.x(i); val y = grid.y(i)
                val texture = 1 + 0.25 * noise.fbm(x.toDouble() + m * 3.0, y.toDouble(), 120.0 / grid.cellKm, 3)
                precip[m][i] = (precip[m][i] * texture).toFloat().coerceAtLeast(0f)
            }

            // Fronts and storms are hundreds of km across.
            for (m in 0 until 12) precip[m] = blur(grid, precip[m], (20 / grid.cellKm).toInt().coerceAtLeast(1))
            val annualP = FloatArray(n) { i -> (0 until 12).sumOf { precip[it][i].toDouble() }.toFloat() }
            val aet = FloatArray(n)
            val runoff = FloatArray(n)
            for (i in 0 until n) {
                val p = annualP[i].toDouble()
                if (isSea[i] || p <= 0) continue
                aet[i] = (p * fu(pet[i] / p)).toFloat()
                runoff[i] = (p - aet[i]).toFloat().coerceAtLeast(0f)
            }
            return Climate(lat, dSea, temp, precip, annualP, monthlyPet, pet, aet, runoff)
        }

        /** Mean air temperature at sea level, °C, at latitude [l] on [day], [cont] 0 on the coast to 1 far inland. */
        fun seaLevelTemp(l: Double, day: Double, cont: Double): Double {
            val amplitude = 6.5 + 0.35 * (l - 31.0) + 7.0 * cont
            return 21.0 - 0.65 * (l - 31.0) + amplitude * cos(2 * PI * (day - WARMEST_DAY) / 360)
        }

        /** Fu's (1981) form of the Budyko curve: evaporation as a share of rain, from the dryness ([pet] / rain). */
        private fun fu(dryness: Double) = 1 + dryness - (1 + dryness.pow(2.6)).pow(1 / 2.6)

        /** Water vapour that saturates air at [t] °C, g/m³ (Tetens' formula for the vapour pressure). */
        fun saturatedVapour(t: Double): Double = 6.1078 * exp(17.27 * t / (t + 237.3)) * 100 / (461.5 * (t + 273.15)) * 1000

        /** Water a column of saturated air holds over ground at [t] °C, mm, cooling upwards at [LAPSE_RATE]. */
        fun saturatedColumn(t: Double): Double {
            var sum = 0.0
            var z = 0.0
            while (z < 10000) { sum += saturatedVapour(t - LAPSE_RATE * (z + 125)) * 250; z += 250 }
            return sum / 1000
        }

        /** Size of the cells the moisture is carried over, km. */
        const val CARRY_KM = 10.0
        /** Relative humidity of air over the sea (it is near 80% over the open oceans). */
        const val SEA_HUMIDITY = 0.8
        /** Height over which water vapour thins by e, m (about 2 km in the real atmosphere). */
        const val VAPOUR_HEIGHT_M = 2200.0
        /** Days a unit of water vapour stays in the air under average weather before it rains out (about 8–9 days, worldwide). */
        const val VAPOUR_DAYS = 8.0
        /** Latitude band of the storm track either side of the jet, degrees: the same width the westerlies have in [Winds.baseWind]. */
        const val STORM_TRACK_DEG = 8.0
        /** Buoyancy frequency of the lower air, per second (about 0.01 in a standard atmosphere). */
        const val BUOYANCY_HZ = 0.01
        /** Share per km of the gap to the sea's moisture that air takes up over open water. */
        const val PICKUP_PER_KM = 0.02
        /**
         * Days over which a front's lift fades once its air has left the sea, by e. The lows that bring the
         * fronts form and feed over warm water and fill over land: Cyprus lows last about 1-4 days (Hochman et
         * al. 2019, Clim. Dyn., citing Alpert and Ziv 1989), most of it at sea, and a landfalling storm cut
         * off from the sea's warmth and moisture fills with a half-life of hours (about 15 h by e for tropical
         * cyclones in idealised runs). A day, from these; to be checked against an extratropical source.
         */
        const val FRONT_LAND_DAYS = 1.0
        /** Times the rain and the land's evaporation are worked out against each other. */
        const val RECYCLING_PASSES = 3

        /**
         * Monthly rain, mm, into [precip]. In each weather regime ([Regime], weighted by how often it blows that
         * month) the air comes in from the seas round about along the regime's wind. Over open water it holds
         * what the sea's warmth allows ([SEA_HUMIDITY] of saturation through [VAPOUR_HEIGHT_M]); over land
         * it gains what the land evaporates ([evap], mm a day) and loses what rains out. Rain needs rising
         * air: fronts in the storm track under the jet (by the regime's storminess, fading with distance from
         * the jet, over [VAPOUR_DAYS], fading over land as the lows fill, see [FRONT_LAND_DAYS]), and ground rising into the wind, where air lifted past saturation rains
         * out what it cannot hold. Highs give rain only on windward slopes.
         * Worked on cells of [CARRY_KM] and spread back to the fine cells.
         */
        private fun carry(grid: Grid, cfg: WorldConfig, elevation: FloatArray, isSea: BooleanArray, lat: DoubleArray,
                          temp: Array<FloatArray>, evap: Array<FloatArray>, precip: Array<FloatArray>) {
            val f = max(1, Math.round(CARRY_KM / grid.cellKm).toInt())
            val cw = (grid.width + f - 1) / f; val ch = (grid.height + f - 1) / f
            val ckm = f * grid.cellKm
            val elev = FloatArray(cw * ch); val sea = FloatArray(cw * ch); val cnt = IntArray(cw * ch)
            for (i in 0 until grid.size) { val c = (grid.y(i) / f) * cw + grid.x(i) / f; elev[c] += max(0f, elevation[i]); if (isSea[i]) sea[c] += 1f; cnt[c]++ }
            for (c in 0 until cw * ch) { elev[c] /= cnt[c].coerceAtLeast(1); sea[c] /= cnt[c].coerceAtLeast(1) }
            val rowLat = DoubleArray(ch) { lat[min(grid.height - 1, it * f + f / 2)] }
            val winds = Winds(null)
            val tmp = FloatArray(2)
            val q = FloatArray(cw * ch); val rain = FloatArray(cw * ch); val total = FloatArray(cw * ch)
            // Days the air has been over land since it left the sea.
            val landDays = FloatArray(cw * ch)
            val gain = FloatArray(cw * ch); val wSat = FloatArray(cw * ch)
            fun at(a: FloatArray, x: Double, y: Double, outside: Float): Float {
                if (x < 0 || y < 0 || x > cw - 1 || y > ch - 1) return outside
                val x0 = x.toInt().coerceAtMost(cw - 2).coerceAtLeast(0); val y0 = y.toInt().coerceAtMost(ch - 2).coerceAtLeast(0)
                val fx = (x - x0).toFloat().coerceIn(0f, 1f); val fy = (y - y0).toFloat().coerceIn(0f, 1f)
                val x1 = min(cw - 1, x0 + 1); val y1 = min(ch - 1, y0 + 1)
                return (a[y0 * cw + x0] * (1 - fx) + a[y0 * cw + x1] * fx) * (1 - fy) + (a[y1 * cw + x0] * (1 - fx) + a[y1 * cw + x1] * fx) * fy
            }
            for (m in 0 until 12) {
                val day = m * 30 + 15.0
                val season = cos(2 * PI * (day - WARMEST_DAY) / 360)
                val jet = winds.jetLatitude(season)
                // The moisture of air that has been over the sea a long time, mm of water in the column.
                val w0 = FloatArray(ch) { y -> (SEA_HUMIDITY * saturatedVapour(seaLevelTemp(rowLat[y], day, 0.0)) * VAPOUR_HEIGHT_M / 1000).toFloat() }
                // Each cell's evaporation, mm a day over the cell.
                gain.fill(0f)
                for (i in 0 until grid.size) { val c = (grid.y(i) / f) * cw + grid.x(i) / f; gain[c] += evap[m][i] }
                for (c in 0 until cw * ch) gain[c] /= cnt[c].coerceAtLeast(1)
                total.fill(0f)
                var weights = 0.0
                for (r in Regime.entries) {
                    val w = winds.weight(r, season)
                    weights += w
                    val frontal = r == Regime.WESTERLY || r == Regime.SOUTHERLY || r == Regime.NORTHERLY
                    // Unit wind of each row (towards the east and the south), its speed in km a day, and how fast fronts rain the air out, a day.
                    val ux = DoubleArray(ch); val uy = DoubleArray(ch); val kmDay = DoubleArray(ch); val lift = DoubleArray(ch)
                    for (y in 0 until ch) {
                        winds.baseWind(r, rowLat[y], jet, tmp)
                        val sp = hypot(tmp[0].toDouble(), tmp[1].toDouble()).coerceAtLeast(1.0)
                        ux[y] = tmp[0] / sp; uy[y] = tmp[1] / sp; kmDay[y] = sp * 86.4
                        lift[y] = if (frontal) r.storminess * exp(-((rowLat[y] - jet) / STORM_TRACK_DEG).let { it * it }) / VAPOUR_DAYS else 0.0
                    }
                    // Air from the sea keeps the sea's warmth aloft and cools only as it is lifted over high ground,
                    // and a wind of speed U lifts it at most U/N over a ridge; below that it flows round (the Froude limit).
                    for (c in 0 until cw * ch) {
                        val y = c / cw
                        val lifted = min(elev[c].toDouble(), kmDay[y] / 86.4 / BUOYANCY_HZ)
                        wSat[c] = saturatedColumn(seaLevelTemp(rowLat[y], day, 0.0) - LAPSE_RATE * lifted).toFloat()
                    }
                    // Downwind order: each cell after the cells upwind of it.
                    val order = (0 until cw * ch).sortedBy { c -> val y = c / cw; (c % cw) * ux[y] + y * uy[y] }
                    for (c in 0 until cw * ch) q[c] = w0[c / cw]
                    landDays.fill(0f)
                    repeat(2) {
                        for (c in order) {
                            val x = c % cw; val y = c / cw
                            val bx = x - ux[y]; val by = y - uy[y]
                            val qin = at(q, bx, by, w0[y])
                            val days = ckm / kmDay[y]
                            // Over the sea the air takes up moisture towards the sea's level.
                            val wet = qin + sea[c] * (w0[y] - qin) * (1 - exp(-PICKUP_PER_KM * ckm)).toFloat()
                            // (Over land the front's low fills: its lift fades with the days since the air left the sea.)
                            val overLand = (1 - sea[c]) * (at(landDays, bx, by, 0f) + days)
                            landDays[c] = overLand.toFloat()
                            val keep = exp(-lift[y] * exp(-overLand / FRONT_LAND_DAYS) * days)
                            // Air lifted over high ground holds less; what it cannot hold rains out.
                            val left = min(wet * keep + (1 - sea[c]) * gain[c] * days, wSat[c].toDouble())
                            rain[c] = ((wet + (1 - sea[c]) * gain[c] * days - left) / days * 30).toFloat()
                            q[c] = left.toFloat()
                        }
                    }
                    for (c in 0 until cw * ch) total[c] += (w * rain[c]).toFloat()
                }
                for (c in 0 until cw * ch) total[c] = (total[c] / weights).toFloat()
                // Back to the fine cells.
                java.util.stream.IntStream.range(0, grid.size).parallel().forEach { i ->
                    precip[m][i] = at(total, (grid.x(i) + 0.5) / f - 0.5, (grid.y(i) + 0.5) / f - 0.5, 0f)
                }
            }
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
