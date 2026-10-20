package inflation.tools

import inflation.world.*
import inflation.Args
import java.io.File
import kotlin.time.measureTimedValue

/**
 * Generates a world and writes maps and a summary.
 *
 * Usage: generate [--seed N] [--size KM] [--height KM] [--out DIR] [--weather-years N]
 */
fun main(args: Array<String>) {
    val a = Args(args, "generate [--seed N] [--size KM] [--height KM] [--out DIR] [--weather-years N]")
    var cfg = WorldConfig()
    cfg = cfg.copy(seed = a.long("--seed", cfg.seed))
    a.string("--size")?.let { val s = a.int("--size", 0); cfg = cfg.copy(widthKm = s, heightKm = s) }
    cfg = cfg.copy(heightKm = a.int("--height", cfg.heightKm))
    val out = a.file("--out") ?: File("output/world")
    val weatherYears = a.int("--weather-years", 3)
    a.done()
    val (world, took) = measureTimedValue { World.generate(cfg) { println("  $it") } }
    val ms = took.inWholeMilliseconds
    println("Generated ${cfg.widthKm} x ${cfg.heightKm} km world (seed ${cfg.seed}) in ${ms / 1000.0} s")
    Render.all(world, out)
    val summary = Summary.of(world) + if (weatherYears > 0) Summary.snowCover(world, weatherYears) else ""
    File(out, "summary.txt").writeText(summary)
    print(summary)
    println("Maps written to ${out.absolutePath}")
}

object Summary {
    /**
     * Runs the weather for [years] years and reports, by latitude band and elevation, the average
     * number of days per year with snow lying (at least 5 mm water equivalent, a few cm of snow).
     */
    fun snowCover(world: World, years: Int): String = buildString {
        val g = world.grid
        val weather = Weather(g, world.climate, world.config, world.elevation)
        val days = IntArray(g.size)
        repeat(world.config.stepsPerYear * years) {
            weather.step()
            for (i in 0 until g.size) if (weather.snowpack[i] >= 5f) days[i] += world.config.daysPerStep
        }
        val bands = listOf(0f to 300f, 300f to 1000f, 1000f to 2000f, 2000f to 9000f)
        appendLine("Snow cover, days per year with snow lying (mean over $years years; '-' = no land):")
        appendLine("  latitude   " +
            bands.joinToString("") { (lo, hi) -> "%14s".format(if (hi > 8000f) "> ${lo.toInt()} m" else "${lo.toInt()}–${hi.toInt()} m") })
        val rows = 5
        for (b in 0 until rows) {
            val y0 = g.height * b / rows; val y1 = g.height * (b + 1) / rows
            val lat0 = world.climate.latitude[y0]; val lat1 = world.climate.latitude[y1 - 1]
            append("  %4.1f–%4.1f°N".format(lat1, lat0))
            for ((lo, hi) in bands) {
                var sum = 0L; var count = 0
                for (y in y0 until y1) for (x in 0 until g.width) {
                    val i = g.index(x, y)
                    if (world.isSea[i] || world.elevation[i] < lo || world.elevation[i] >= hi) continue
                    sum += days[i]; count++
                }
                append(if (count < 50) "%14s".format("-") else "%14.0f".format(sum.toDouble() / count / years))
            }
            appendLine()
        }
    }

    fun of(world: World): String = buildString {
        val g = world.grid
        val land = (0 until g.size).filter { !world.isSea[it] }
        val elev = land.map { world.elevation[it] }
        appendLine("Land: ${"%.1f".format(100.0 * land.size / g.size)}% of ${g.size} cells")
        appendLine("Elevation: highest ${elev.max().toInt()} m, lowest land ${elev.min().toInt()} m, median ${elev.sorted()[elev.size / 2].toInt()} m")
        val lakes = land.count { world.water.isLake(it) }
        val salt = land.count { world.water.isLake(it) && world.water.lakeClosed[it] }
        appendLine("Lakes: $lakes km² (of which closed/saline $salt km²)")
        appendLine("Largest river discharge ${"%.0f".format(world.water.discharge.max())} m³/s; river length by class (km of channel cells):")
        val classes = land.groupingBy { world.riverClass(it) }.eachCount()
        for (c in RiverClass.entries.drop(1)) appendLine("  %-12s ≥ %5.1f m³/s  %6d".format(c.name.lowercase(), c.minDischarge, classes[c] ?: 0))
        appendLine("Navigable by canoe or raft at mean flow: ${land.count { world.isNavigable(it) }} km")
        appendLine("Floodplain: ${land.count { world.floodplain.isFloodplain[it] }} km²")
        val active = world.volcanoes.count { it.active }
        val emergent = world.volcanoes.count { world.elevation[world.grid.index(it.x, it.y)] > 0f }
        appendLine("Volcanoes: ${world.volcanoes.size} (${active} active, ${emergent} above sea level): " +
            VolcanoKind.entries.joinToString { k -> "${world.volcanoes.count { it.kind == k }} ${k.name.lowercase().replace('_', ' ')}" })
        val sea = (0 until g.size).filter { world.isSea[it] }
        val shelf = sea.count { world.elevation[it] > -200f }
        appendLine("Sea floor: ${"%.0f".format(100.0 * shelf / sea.size)}% shelf (< 200 m deep), deepest ${(-world.elevation.min()).toInt()} m")
        val sed = land.map { world.geology.sediment[it] }
        appendLine("Sediment on land: ${"%.0f".format(100.0 * sed.count { it > Erosion.COVER } / land.size)}% covered more than ${Erosion.COVER.toInt()} m deep, thickest ${sed.max().toInt()} m")
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
