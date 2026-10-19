package inflation.world

import kotlin.math.max
import kotlin.math.min

/**
 * The air above the ground's own stirred layer, by month and latitude, as observed: the ERA5 climatology of 1990-2019
 * (WeatherBench 2, 1.5°), averaged over 20-50°E, at 925-300 hPa (free-air.csv). Over the subtropics it is warm and dry
 * aloft in summer, where the Hadley cell's air sinks and the summer high holds, and cooler and moister towards the
 * storm track; the sea's warmth less a fixed lapse rate misses that.
 */
object FreeAir {
    private class Row(val z: Double, val t: Double, val q: Double)

    /** [month][latitude][level, from the lowest]. */
    private val rows: Array<Array<Array<Row>>>
    private val lats: DoubleArray

    init {
        val lines = FreeAir::class.java.getResourceAsStream("/free-air.csv")!!.bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        val parsed = lines.map { l -> l.split(",").let { Triple(it[0].toInt() - 1, it[2].toDouble(), Pair(it[1].toInt(), Row(it[3].toDouble(), it[4].toDouble(), it[5].toDouble() / 1000))) } }
        lats = parsed.map { it.second }.distinct().sorted().toDoubleArray()
        rows = Array(12) { m -> Array(lats.size) { j ->
            parsed.filter { it.first == m && it.second == lats[j] }.map { it.third }.sortedByDescending { it.first }.map { it.second }.toTypedArray()
        } }
    }

    /** Temperature, °C, and specific humidity, kg/kg, of the free air [z] m up at latitude [lat] in month [m] (0-11), into [out]. */
    fun at(m: Int, lat: Double, z: Double, out: DoubleArray) {
        val x = ((lat - lats[0]) / (lats[1] - lats[0])).coerceIn(0.0, lats.size - 1.0)
        val j0 = min(x.toInt(), lats.size - 2); val f = x - j0
        var t = 0.0; var q = 0.0
        for ((j, w) in listOf(j0 to 1 - f, j0 + 1 to f)) {
            val col = rows[m][j]
            // Between levels, linear in height; below the lowest and above the highest, the nearest level's moisture and its
            // temperature carried on at the lapse rate of the nearest pair.
            val k = (0 until col.size - 1).firstOrNull { z < col[it + 1].z } ?: (col.size - 2)
            val a = col[k]; val b = col[k + 1]
            val s = (z - a.z) / (b.z - a.z)
            t += w * (a.t + (b.t - a.t) * s)
            q += w * max(0.0, a.q + (b.q - a.q) * s.coerceIn(0.0, 1.0))
        }
        out[0] = t; out[1] = q
    }
}
