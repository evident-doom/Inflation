package inflation.tools

import inflation.ecology.HexSim
import inflation.world.Date
import inflation.world.WorldConfig

/**
 * Temporary probe: where the migratory herds are by height through the year, how well fed they are
 * there, and the forage the low and high country offers (herb kg/m², its nourishment), in the last year.
 */
fun main(args: Array<String>) {
    var cfg = WorldConfig(seed = 1); var years = 3
    var k = 0
    while (k < args.size) { when (args[k]) { "--seed" -> cfg = cfg.copy(seed = args[++k].toLong()); "--width" -> cfg = cfg.copy(widthKm = args[++k].toInt()); "--height" -> cfg = cfg.copy(heightKm = args[++k].toInt()); "--years" -> years = args[++k].toInt() }; k++ }
    val sim = HexSim(inflation.ecology.HexSnapshot.WorldCache.load(cfg) { })
    val hw = sim.hw; val land = sim.land; val herds = sim.groups
    val elev = hw.placeElevationM
    fun band(p: Int) = when { elev[p] < 500 -> 0; elev[p] < 1000 -> 1; else -> 2 }
    val bands = listOf("low <500", "mid", "high >1000")
    val stepsY = Date.DAYS_PER_YEAR / cfg.daysPerStep
    val grazers = herds.kinds.filter { !it.carnivore }
    // [kind][band][month]: heads, heads×fed.
    val heads = Array(grazers.size) { Array(3) { DoubleArray(12) } }; val fed = Array(grazers.size) { Array(3) { DoubleArray(12) } }
    val herb = Array(3) { DoubleArray(12) }; val nour = Array(3) { DoubleArray(12) }; val cnt = Array(3) { IntArray(12) }
    for (y in 0 until years) repeat(stepsY) {
        sim.step()
        if (y < years - 1) return@repeat
        val m = sim.weather.lastMonth
        for (p in 0 until land.n) { if (land.herb[p] + land.shrub[p] <= 0f) continue; val b = band(p); herb[b][m] += land.herb[p]; nour[b][m] += land.nourishment(p); cnt[b][m]++ }
        for (g in herds.groups) { if (!g.alive) continue; val i = grazers.indexOf(g.species); if (i < 0) continue; val b = band(g.place); heads[i][b][m] += g.size.toDouble(); fed[i][b][m] += g.size * g.fed.toDouble() }
    }
    fun row(label: String, f: (Int) -> String) = println("  %-22s".format(label) + (0 until 12).joinToString(" ") { f(it) })
    println("month (0 = mid Dec-mid Jan)  " + (0 until 12).joinToString(" ") { "%5d".format(it) })
    for (b in 0 until 3) {
        row("${bands[b]} herb kg/m2") { m -> "%5.3f".format(herb[b][m] / cnt[b][m].coerceAtLeast(1)) }
        row("${bands[b]} nourishment") { m -> "%5.2f".format(nour[b][m] / cnt[b][m].coerceAtLeast(1)) }
    }
    for ((i, s) in grazers.withIndex()) {
        val tot = DoubleArray(12) { m -> (0 until 3).sumOf { heads[i][it][m] } }
        if (tot.sum() <= 0) continue
        println("== ${s.name} (heads mid-year ${"%.0f".format(tot[6] / (stepsY / 12.0))})")
        for (b in 0 until 3) {
            row("${bands[b]} share") { m -> "%5.2f".format(heads[i][b][m] / tot[m].coerceAtLeast(1e-9)) }
            row("${bands[b]} fed") { m -> if (heads[i][b][m] > 0) "%5.2f".format(fed[i][b][m] / heads[i][b][m]) else "    -" }
        }
    }
}
