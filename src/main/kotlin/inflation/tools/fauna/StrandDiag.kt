package inflation.tools.fauna

import inflation.ecology.HexFauna
import inflation.ecology.HexSim
import java.io.File
import kotlin.math.max

/**
 * strandDiag=FILE: the residents caught where the season turns against them, written to FILE as CSV every quarter: for
 * each kind, month and height class, the head-weeks there, those in snow deeper than the kind digs through (and twice
 * that), colder or hotter than its coat bears, past its days without water; how well fed; and the deaths the week's
 * hazards ([HexFauna.hazards]) give, by cause, before the cap on a week's deaths.
 */
class StrandDiag(private val sim: HexSim, private val file: File) {
    private val fauna = sim.fauna
    private val hw = sim.hw
    private val kinds = fauna.kinds
    private val classes = floatArrayOf(500f, 1000f, 1500f, 2500f)
    private val names = listOf("<500", "500-1000", "1000-1500", "1500-2500", ">2500")
    private val cols = listOf("heads", "fedHeads", "snowed", "deepSnow", "cold", "hot", "dry", "fedSnowed",
        "dHunger", "dCold", "dHeat", "dSnow", "dThirst", "dDisease", "dHunted", "dSnowedAll", "dColdAll", "dDryAll")
    private val acc = Array(kinds.size) { Array(12) { Array(names.size) { DoubleArray(cols.size) } } }
    private var weeks = 0

    fun week() {
        val w = sim.weather
        val month = w.date.month
        val hz = FloatArray(HexFauna.HZ_COUNT)
        for ((k, s) in kinds.withIndex()) {
            val a = fauna.adults[k]; val y = fauna.young[k]
            for (p in 0 until fauna.n) {
                val heads = a[p] + y[p]
                if (heads <= 0f) continue
                val area = sim.land.areaKm2[p]
                fauna.hazards(k, p, w, month, fauna.fed[k][p], fauna.dry[k][p], heads, hz)
                val c = classes.count { hw.placeElevationM[p] >= it }
                val r = acc[k][month][c]
                val n = heads * area
                val felt = fauna.feltC(s, p, w.temperature[p])
                val coldC = s.coldC(felt, month); val hotC = s.hotC(felt)
                val coldShare = if (coldC + hotC > 0f) coldC / (coldC + hotC) else 0f
                val snowed = w.snowpack[p] > s.snowLimitMm
                val dryOut = fauna.dry[k][p] > s.daysWithoutWater
                // (As [HexFauna.breedAndDie] books them: the young die 1.5 times as readily of hunger, cold and thirst.)
                val ya = a[p] * area; val yy = y[p] * area
                fun d(x: Float) = ya * x + yy * 1.5f * x
                val dHunger = d(hz[HexFauna.HZ_HUNGER]); val dCold = d(hz[HexFauna.HZ_COLD] * coldShare)
                val dHeat = d(hz[HexFauna.HZ_COLD] * (1f - coldShare)); val dThirst = d(hz[HexFauna.HZ_THIRST])
                val dSnow = ya * hz[HexFauna.HZ_SNOW_A] + yy * hz[HexFauna.HZ_SNOW_Y]
                val dDisease = (ya + yy) * hz[HexFauna.HZ_DISEASE]; val dHunted = ya * hz[HexFauna.HZ_HUNTED]
                val all = dHunger + dCold + dHeat + dThirst + dSnow + dDisease + dHunted
                r[0] += n; r[1] += n * fauna.fed[k][p]
                if (snowed) { r[2] += n; r[7] += n * fauna.fed[k][p]; r[15] += all }
                if (w.snowpack[p] > 2f * s.snowLimitMm) r[3] += n
                if (coldC > 0f) { r[4] += n; r[16] += all }
                if (hotC > 0f) r[5] += n
                if (dryOut) { r[6] += n; r[17] += all }
                r[8] += dHunger; r[9] += dCold; r[10] += dHeat; r[11] += dSnow; r[12] += dThirst; r[13] += dDisease; r[14] += dHunted
            }
        }
        if (++weeks % 13 == 0) write()
    }

    private fun write() {
        file.printWriter().use { out ->
            out.println("kind,month,height," + cols.joinToString(","))
            for ((k, s) in kinds.withIndex()) for (m in 0 until 12) for (c in names.indices) {
                val r = acc[k][m][c]
                if (r[0] <= 0.0) continue
                out.println("${s.name},$m,${names[c]}," + r.joinToString(",") { "%.6g".format(max(0.0, it)) })
            }
        }
    }
}
