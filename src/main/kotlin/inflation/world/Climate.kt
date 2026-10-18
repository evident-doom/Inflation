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
                carry(grid, cfg, elevation, isSea, lat, temp, monthlyPet, evap, precip)
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
            for (m in 0 until 12) precip[m] = blur(grid, precip[m], ((System.getenv("PROBE_BLUR_KM") ?: "20").toDouble() / grid.cellKm).toInt())
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
        val CARRY_KM = (System.getenv("PROBE_CARRY_KM") ?: "10").toDouble()
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
         * Days the sea's surface takes to follow the air, by e (its mixed layer stores the summer's heat). With
         * 40 days the sea swings 0.8 as far as the coastal air and lags it by 5 weeks: the Levantine Sea runs
         * from about 16-17 °C in February-March to 28 °C in August against Beirut's 13-28 °C (from memory).
         */
        const val SEA_HEAT_DAYS = 40.0
        /** Air density near the sea, kg/m³. */
        const val AIR_DENSITY = 1.2
        /** Bulk transfer coefficient of heat and vapour between sea and air (about 1.2e-3; Large and Pond 1982). */
        const val SEA_TRANSFER = 1.2e-3
        /** Days a shower lives, by e: a convective cell lasts about half an hour to an hour (Byers and Braham 1949). */
        const val SHOWER_DAYS = 1.0 / 24

        /** Sea surface temperature, °C, at latitude [l] on [day]: the coastal air's, damped and delayed by [SEA_HEAT_DAYS]. */
        fun seaSurfaceTemp(l: Double, day: Double): Double {
            val wt = 2 * PI / 360 * SEA_HEAT_DAYS
            val mean = 21.0 - 0.65 * (l - 31.0)
            val amplitude = 6.5 + 0.35 * (l - 31.0)
            return mean + amplitude / hypot(1.0, wt) * cos(2 * PI * (day - WARMEST_DAY) / 360 - kotlin.math.atan(wt))
        }

        /** Specific humidity of saturated air at [t] °C at sea-level pressure, kg/kg. */
        private fun saturatedHumidity(t: Double) = 0.622 * 6.1078 * exp(17.27 * t / (t + 237.3)) / 1013.25

        /**
         * Monthly rain, mm, into [precip]. In each weather regime ([Regime], weighted by how often it blows that
         * month) the air comes in from the seas round about along the regime's wind. Over open water it holds
         * what the sea's warmth allows ([SEA_HUMIDITY] of saturation through [VAPOUR_HEIGHT_M]); over land
         * it gains what the land evaporates ([evap], mm a day) and loses what rains out. Rain needs rising
         * air: fronts in the storm track under the jet (by the regime's storminess, fading with distance from
         * the jet, over [VAPOUR_DAYS], fading over land as the lows fill, see [FRONT_LAND_DAYS]), and ground rising into the wind, where air lifted past saturation rains
         * out what it cannot hold. Highs give rain only on windward slopes. Air crossing a sea warmer than
         * itself (the sea lags the seasons, [SEA_HEAT_DAYS], so in autumn and winter) is heated from below and
         * takes up more vapour than at its own temperature; that extra water rains out in showers that drift a
         * few tens of km before they die ([SHOWER_DAYS]), so they fall on the sea and the coast it blows onto.
         * Worked on cells of [CARRY_KM] and spread back to the fine cells.
         */
        private fun carry(grid: Grid, cfg: WorldConfig, elevation: FloatArray, isSea: BooleanArray, lat: DoubleArray,
                          temp: Array<FloatArray>, monthlyPet: Array<FloatArray>, evap: Array<FloatArray>, precip: Array<FloatArray>) {
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
            // Shower water the air carries, mm, and the temperatures of the sea's surface and the air over it, °C.
            val shower = FloatArray(cw * ch)
            val seaT = FloatArray(ch); val seaAirT = FloatArray(ch)
            val gain = FloatArray(cw * ch); val wSat = FloatArray(cw * ch)
            val groundT = FloatArray(cw * ch); val petC = FloatArray(cw * ch)
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
                groundT.fill(0f); petC.fill(0f)
                for (i in 0 until grid.size) { val c = (grid.y(i) / f) * cw + grid.x(i) / f; groundT[c] += temp[m][i]; petC[c] += monthlyPet[m][i] }
                for (c in 0 until cw * ch) { groundT[c] /= cnt[c].coerceAtLeast(1); petC[c] /= cnt[c].coerceAtLeast(1) }
                for (y in 0 until ch) { seaT[y] = seaSurfaceTemp(rowLat[y], day).toFloat(); seaAirT[y] = seaLevelTemp(rowLat[y], day, 0.0).toFloat() }
                total.fill(0f)
                var weights = 0.0
                for (r in Regime.entries) {
                    val w = winds.weight(r, season)
                    weights += w
                    val frontal = r == Regime.WESTERLY || r == Regime.SOUTHERLY || r == Regime.NORTHERLY
                    val troughs = r != Regime.BLOCKING && r != Regime.SUMMER_HIGH
                    val rising = DoubleArray(ch) { y -> if (troughs) exp(-((rowLat[y] - jet) / STORM_TRACK_DEG).let { it * it }) else 0.0 }
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
                        val lifted = if (System.getenv("PROBE_NO_FROUDE") != null) elev[c].toDouble() else min(elev[c].toDouble(), kmDay[y] / 86.4 / BUOYANCY_HZ)
                        wSat[c] = saturatedColumn(seaLevelTemp(rowLat[y], day, 0.0) - LAPSE_RATE * lifted).toFloat()
                    }
                    // Downwind order: each cell after the cells upwind of it.
                    val order = (0 until cw * ch).sortedBy { c -> val y = c / cw; (c % cw) * ux[y] + y * uy[y] }
                    for (c in 0 until cw * ch) q[c] = w0[c / cw]
                    landDays.fill(0f)
                    shower.fill(0f)
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
                            var left = min(wet * keep + (1 - sea[c]) * gain[c] * days, wSat[c].toDouble())
                            if (CONV && sea[c] < 1) { val cv = convection(left, w0[y].toDouble(), groundT[c].toDouble(), elev[c].toDouble(),
                                petC[c] / 30.0, seaLevelTemp(rowLat[y], day, 0.0), rising[y])
                                left -= (1 - sea[c]) * min(left, cv * days) }
                            rain[c] = ((wet + (1 - sea[c]) * gain[c] * days - left) / days * 30).toFloat()
                            q[c] = left.toFloat()
                            // A sea warmer than the air over it gives the air more vapour than the air's own warmth would, mm a day,
                            // and that rains out in showers.
                            val extra = AIR_DENSITY * SEA_TRANSFER * kmDay[y] / 86.4 * (saturatedHumidity(seaT[y].toDouble()) - saturatedHumidity(seaAirT[y].toDouble())) * 86400
                            val sin = at(shower, bx, by, 0f) + sea[c] * max(0.0, extra) * days
                            val sKeep = sin * exp(-days / SHOWER_DAYS)
                            shower[c] = sKeep.toFloat()
                            rain[c] += ((sin - sKeep) / days * 30).toFloat()
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

        val CONV = System.getenv("CONV") != null
        val CONV_SURFACE_Q = System.getenv("CONV_SURFACE_Q") != null
        const val BOUNDARY_LAYER_M = 1500.0
        var ENTRAINMENT_PER_M = (System.getenv("CONV_EPS") ?: "0.5").toDouble() * 1e-3
        const val CLOUD_TOP_M = 5500.0
        const val RADIATIVE_COOLING_C = 1.5
        const val SINKING_DAYS = 7.0
        private const val CP = 1005.0
        private const val LV = 2.5e6
        private const val GRAVITY = 9.81
        private const val R_DRY = 287.0
        private const val PRESSURE_HEIGHT_M = 8400.0
        val sinkingM = RADIATIVE_COOLING_C / (GRAVITY / CP - LAPSE_RATE) * SINKING_DAYS
        private fun staticEnergy(t: Double, z: Double, q: Double) = CP * (t + 273.15) + GRAVITY * z + LV * q
        private fun airDensity(t: Double, z: Double) = 101325 * exp(-z / PRESSURE_HEIGHT_M) / (R_DRY * (t + 273.15))
        private fun saturatedQ(t: Double, z: Double) = saturatedVapour(t) / 1000 / airDensity(t, z)
        private fun convection(column: Double, seaColumn: Double, t: Double, z: Double, petDay: Double, seaTemp: Double, rising: Double): Double {
            if (petDay <= 0 || column <= 0) return 0.0
            val density = airDensity(t, z)
            val thin = if (CONV_SURFACE_Q) 1.0 else exp(-z / VAPOUR_HEIGHT_M)
            val q = min(min(column, seaColumn * thin) / VAPOUR_HEIGHT_M, saturatedVapour(t) / 1000) / density
            val mass = density * BOUNDARY_LAYER_M
            val heating = petDay * LV / mass
            var a = 1.0; var b = 0.0; var need = Double.NEGATIVE_INFINITY
            var level = z + BOUNDARY_LAYER_M
            val dz = 250.0
            val mix = ENTRAINMENT_PER_M * dz
            while (level < max(CLOUD_TOP_M, z + BOUNDARY_LAYER_M + 1000)) {
                level += dz
                val tf = seaTemp - LAPSE_RATE * level
                val saturated = saturatedQ(tf, level)
                val qf = rising * SEA_HUMIDITY * saturated + (1 - rising) * min(saturated, saturatedQ(tf - LAPSE_RATE * sinkingM, level + sinkingM))
                a *= 1 - mix
                b = b * (1 - mix) + mix * staticEnergy(tf, level, qf)
                need = max(need, (staticEnergy(tf, level, saturated) - b) / a)
            }
            val excess = min(staticEnergy(t, z, q) + heating - need, heating)
            return if (excess <= 0) 0.0 else mass * excess / LV
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
