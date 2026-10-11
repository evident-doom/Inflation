package inflation.world

import java.io.File
import kotlin.system.measureTimeMillis

/**
 * Generates a world and writes maps and a summary.
 *
 * Usage: generate [--seed N] [--size KM] [--out DIR]
 */
fun main(args: Array<String>) {
    var cfg = WorldConfig()
    var out = File("output/world")
    var k = 0
    while (k < args.size) {
        when (args[k]) {
            "--seed" -> cfg = cfg.copy(seed = args[++k].toLong())
            "--size" -> { val s = args[++k].toInt(); cfg = cfg.copy(widthKm = s, heightKm = s) }
            "--out" -> out = File(args[++k])
            else -> error("unknown argument ${args[k]}")
        }
        k++
    }
    lateinit var world: World
    val ms = measureTimeMillis { world = World.generate(cfg) { println("  $it") } }
    println("Generated ${cfg.widthKm} x ${cfg.heightKm} km world (seed ${cfg.seed}) in ${ms / 1000.0} s")
    Render.all(world, out)
    val summary = Summary.of(world)
    File(out, "summary.txt").writeText(summary)
    print(summary)
    println("Maps written to ${out.absolutePath}")
}

object Summary {
    fun of(world: World): String = buildString {
        val g = world.grid
        val land = (0 until g.size).filter { !world.isSea[it] }
        val elev = land.map { world.elevation[it] }
        appendLine("Land: ${"%.1f".format(100.0 * land.size / g.size)}% of ${g.size} cells")
        appendLine("Elevation: highest ${elev.max().toInt()} m, lowest land ${elev.min().toInt()} m, median ${elev.sorted()[elev.size / 2].toInt()} m")
        val lakes = land.count { world.water.isLake(it) }
        val salt = land.count { world.water.isLake(it) && world.water.lakeClosed[it] }
        appendLine("Lakes: $lakes km² (of which closed/saline $salt km²)")
        appendLine("River cells (≥ ${SurfaceWater.RIVER_THRESHOLD} m³/s): ${land.count { world.isRiver(it) }}; largest discharge ${"%.0f".format(world.water.discharge.max())} m³/s")
        val p = land.map { world.climate.annualPrecip[it] }.sorted()
        appendLine("Annual precipitation on land: 10th pct ${p[p.size / 10].toInt()} mm, median ${p[p.size / 2].toInt()} mm, 90th pct ${p[p.size * 9 / 10].toInt()} mm")
        for (row in listOf(0, g.height / 2, g.height - 1)) {
            val cells = (0 until g.width).map { g.index(it, row) }.filter { !world.isSea[it] && world.elevation[it] < 300 }
            if (cells.isEmpty()) continue
            val jan = cells.map { world.climate.coldestMonthTemp(it) }.average()
            val jul = cells.map { world.climate.warmestMonthTemp(it) }.average()
            appendLine("Lowland at %.1f°N: coldest month %.1f °C, warmest month %.1f °C".format(world.climate.latitude[row], jan, jul))
        }
        appendLine("Biomes:")
        val counts = land.groupingBy { world.biome[it] }.eachCount().toList().sortedByDescending { it.second }
        for ((b, c) in counts) appendLine("  %-24s %5.1f%%".format(b.name.lowercase(), 100.0 * c / land.size))
        appendLine("Minerals (cells):")
        for ((bit, name) in Mineral.names.withIndex()) appendLine("  %-10s %d".format(name, land.count { world.geology.minerals[it] and (1 shl bit) != 0 }))
    }
}
