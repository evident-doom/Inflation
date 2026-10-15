package inflation.tools

import inflation.world.Date
import inflation.world.HexLand
import inflation.world.HexMap
import inflation.world.HexWorld
import inflation.world.Weather
import inflation.world.World
import inflation.world.WorldConfig

/** Temporary probe: monthly rain, temperature, soil wetness, growth factors and greenness by region, in the last year. */
fun main(args: Array<String>) {
    var cfg = WorldConfig(seed = 1); var years = 2
    var k = 0
    while (k < args.size) { when (args[k]) { "--width" -> cfg = cfg.copy(widthKm = args[++k].toInt()); "--height" -> cfg = cfg.copy(heightKm = args[++k].toInt()); "--years" -> years = args[++k].toInt() }; k++ }
    val world = World.generate(cfg)
    val hw = HexWorld(world, HexMap(world))
    val n = hw.placeCells.size
    val land = HexLand(hw, cfg.seed)
    val weather = Weather(hw.weatherPlaces(cfg), cfg)
    val g = world.grid; val c = world.climate
    fun mean(p: Int, f: (Int) -> Float): Float { val cs = hw.placeCells[p]; var s = 0.0; for (i in cs) s += f(i); return (s / cs.size).toFloat() }
    val lat = FloatArray(n) { p -> mean(p) { c.latitude[g.y(it)].toFloat() } }
    val dsea = FloatArray(n) { p -> mean(p) { c.distanceToSeaKm[it] } }
    val elev = FloatArray(n) { p -> mean(p) { world.elevation[it] } }
    val lo = lat.min(); val hi = lat.max()
    val names = mutableListOf<String>(); val reg = IntArray(n) { -1 }
    val kinds = listOf("coast", "inland", "upland", "alpine")
    for (b in 0 until 3) for (kk in kinds) names += "%s %s".format(listOf("north", "middle", "south")[b], kk)
    for (p in 0 until n) {
        val b = (((hi - lat[p]) / (hi - lo + 1e-3f)) * 3).toInt().coerceIn(0, 2)
        val kk = when { elev[p] >= 2400 -> 3; elev[p] >= 1000 -> 2; elev[p] < 300 && dsea[p] < 50 -> 0; elev[p] < 300 && dsea[p] >= 100 -> 1; else -> -1 }
        if (kk >= 0) reg[p] = b * 4 + kk
    }
    val stepsY = Date.DAYS_PER_YEAR / cfg.daysPerStep
    val R = names.size
    val acc = Array(15) { Array(R) { DoubleArray(12) } }; val cnt = Array(R) { IntArray(12) }
    val all = DoubleArray(12); val allN = IntArray(12)
    for (y in 0 until years) repeat(stepsY) {
        weather.step(); land.step(weather)
        if (y < years - 1) return@repeat
        val m = weather.lastMonth
        for (p in 0 until n) {
            if (hw.land[hw.placeHex[p]] > 0f) { all[m] += land.green[p]; allN[m]++ }
            val r = reg[p]; if (r < 0) continue
            val t = weather.temperature[p]; val wet = land.soil[p] / land.soilCap[p]
            acc[0][r][m] += weather.precipitation[p]; acc[1][r][m] += t; acc[2][r][m] += wet
            acc[3][r][m] += ((t - HexLand.GROW_FROM_C) / HexLand.GROW_SPAN_C).coerceIn(0f, 1f); acc[4][r][m] += if (land.cured[p]) 1f else 0f; acc[5][r][m] += land.green[p]; acc[6][r][m] += weather.snowpack[p]; acc[7][r][m] += weather.meltwater[p]; acc[8][r][m] += mean(p) { c.monthlyPet[m][it] } / (30.0 / cfg.daysPerStep); acc[9][r][m] += land.soilCap[p]; acc[10][r][m] += land.groundwater[p] / land.aquiferCap[p].coerceAtLeast(1f); acc[11][r][m] += land.springFlow(p); acc[12][r][m] += hw.sunlight.shade[p]; acc[13][r][m] += land.greenShade[p]; acc[14][r][m] += weather.snowShade[p]
            cnt[r][m]++ }
    }
    println("all places  green " + (0 until 12).joinToString(" ") { "%5.2f".format(all[it] / allN[it].coerceAtLeast(1)) })
    val lbl = listOf("rain mm/mo", "temp C", "soil wet", "season", "cured", "green", "snow mm", "melt mm/mo", "pet mm/mo", "soil cap", "ground fill", "vs usual", "shade share", "green shade", "snow shade")
    for (r in 0 until R) {
        if (cnt[r].sum() == 0) continue
        println("== ${names[r]} (places ${(0 until n).count { reg[it] == r }}, mean height ${"%.0f".format((0 until n).filter { reg[it] == r }.map { elev[it] }.average())} m)")
        for (q in 0 until 15) println("  %-10s".format(lbl[q]) + (0 until 12).joinToString(" ") { m ->
            val v = acc[q][r][m] / cnt[r][m].coerceAtLeast(1) * (if (q == 0 || q == 7 || q == 8) 30.0 / cfg.daysPerStep else 1.0)
            if (q <= 1 || q in 6..9 || q == 14) "%5.0f".format(v) else "%5.2f".format(v) })
    }
}
