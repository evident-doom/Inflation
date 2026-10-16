package inflation.tools

/**
 * A probe for the pollinators: runs the hex world for some years and prints, at each month, the
 * fruit set ([inflation.ecology.HexSmallLife.fruitSet]) weighted by the fruit the plants could bear,
 * in the north, middle and south thirds, the share of that bearing with pollination under 0.9, and
 * pollinator density. Usage: pollination-probe [--seed N] [--width KM] [--height KM] [--years N]
 */

import inflation.ecology.HexSim
import inflation.world.WorldConfig

fun main(args: Array<String>) {
    var cfg = WorldConfig(seed = 1); var years = 2
    var k = 0
    while (k < args.size) { when (args[k]) { "--seed" -> cfg = cfg.copy(seed = args[++k].toLong()); "--width" -> cfg = cfg.copy(widthKm = args[++k].toInt()); "--height" -> cfg = cfg.copy(heightKm = args[++k].toInt()); "--years" -> years = args[++k].toInt() }; k++ }
    val t0 = System.nanoTime()
    val sim = HexSim(inflation.ecology.HexSnapshot.WorldCache.load(cfg) { println(it) })
    println("world in %.0f s".format((System.nanoTime() - t0) / 1e9))
    val land = sim.land; val sl = sim.fauna.small; val hw = sim.hw; val cols = sim.hex.cols; val rows = sim.hex.rows
    fun third(p: Int) = (3 * (hw.placeHex[p] / cols) / rows).coerceIn(0, 2)
    var lastMonth = -1
    val steps = years * 360 / cfg.daysPerStep
    for (s in 0 until steps) {
        sim.step()
        val m = sim.weather.date.month
        if (m != lastMonth) {
            lastMonth = m
            val w = DoubleArray(3); val ws = DoubleArray(3); val low = DoubleArray(3); val po = DoubleArray(3); val area = DoubleArray(3)
            for (p in 0 until land.n) {
                val t = third(p); val a = land.areaKm2[p].toDouble()
                val bear = land.palatable[p] * (0.5f * land.shrub[p] + 0.05f * land.tree[p]) * kotlin.math.min(1f, land.moisture[p] / 0.5f) * a
                w[t] += bear; ws[t] += bear * sl.fruitSet(p); if (sl.pollination[p] < 0.9f) low[t] += bear
                po[t] += sl.pollinators[p] * a; area[t] += a
            }
            fun tonnes(f: FloatArray) = (0 until land.n).sumOf { (f[it] * land.areaKm2[it]).toDouble() } / 1000
            val kinds = inflation.ecology.HexSmallLife.BIRDS.indices.joinToString(" ") { "%s %.0f".format(inflation.ecology.HexSmallLife.BIRDS[it].name.replace(' ', '_'), tonnes(sl.bird[it])) }
            val hz = DoubleArray(3); val hzA = DoubleArray(3)
            for (p in 0 until land.n) { val t = third(p); val a = land.areaKm2[p].toDouble(); hz[t] += sl.harassment(p) * a; hzA[t] += a }
            println("      harassment by biting insects N/M/S %.3f %.3f %.3f".format(hz[0] / hzA[0], hz[1] / hzA[1], hz[2] / hzA[2]))
            println("      t: insects %.0f, pollinators %.0f, biting %.0f, herps %.0f, foxes %.0f | birds %s".format(tonnes(sl.insects), tonnes(sl.pollinators), tonnes(sl.biting), tonnes(sl.herps), tonnes(sl.foxes), kinds))
            println("y%d m%2d  fruitSet N/M/S %.3f %.3f %.3f | bearing with pollination<0.9 %.0f%% %.0f%% %.0f%% | pollinators kg/km2 %.1f %.1f %.1f  (%.0f s)".format(
                s * cfg.daysPerStep / 360, m, ws[0] / w[0], ws[1] / w[1], ws[2] / w[2], 100 * low[0] / w[0], 100 * low[1] / w[1], 100 * low[2] / w[2], po[0] / area[0], po[1] / area[1], po[2] / area[2], (System.nanoTime() - t0) / 1e9))
        }
    }
}
