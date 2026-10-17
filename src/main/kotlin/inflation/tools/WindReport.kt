package inflation.tools

import inflation.ui.Render
import inflation.Args
import inflation.world.Date
import inflation.world.Regime
import inflation.world.Weather
import inflation.world.World
import inflation.world.WorldConfig


import java.awt.BasicStroke
import java.awt.Color
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Runs the weather alone and reports how the winds behave: how often each regime comes in each
 * season, whether rain still averages to the climate normals, how much a cell's rain varies from
 * week to week, and maps of rain in a westerly and a northerly week with the wind drawn on.
 *
 * Usage: wind-report [--seed N] [--size KM] [--height KM] [--years N] [--out DIR]
 */
fun main(args: Array<String>) {
    val a = Args(args, "wind-report [--seed N] [--size KM] [--height KM] [--years N] [--out DIR]")
    var cfg = WorldConfig()
    cfg = cfg.copy(seed = a.long("--seed", cfg.seed))
    a.string("--size")?.let { val s = a.int("--size", 0); cfg = cfg.copy(widthKm = s, heightKm = s) }
    cfg = cfg.copy(heightKm = a.int("--height", cfg.heightKm))
    val years = a.int("--years", 10)
    val out = a.file("--out") ?: File("output/wind")
    a.done()
    out.mkdirs()
    val world = World.generate(cfg)
    val g = world.grid
    val weather = Weather(g, world.climate, cfg, world.elevation)
    val steps = Date.DAYS_PER_YEAR / cfg.daysPerStep * years
    val rain = DoubleArray(g.size); val normal = DoubleArray(g.size)
    val logSum = DoubleArray(g.size); val logSq = DoubleArray(g.size)
    val counts = Array(4) { IntArray(Regime.entries.size) }
    var saved = mutableSetOf<Regime>()
    repeat(steps) {
        val month = weather.date.month
        val share = cfg.daysPerStep.toDouble() / Date.DAYS_PER_MONTH
        weather.step()
        counts[month / 3][weather.regime.ordinal]++
        for (i in 0 until g.size) {
            if (world.isSea[i]) continue
            val p = weather.precipitation[i].toDouble()
            rain[i] += p; normal[i] += world.climate.monthlyPrecip[month][i] * share
            val r = ln(p / (world.climate.monthlyPrecip[month][i] * share).coerceAtLeast(0.01) + 0.05)
            logSum[i] += r; logSq[i] += r * r
        }
        val r = weather.regime
        if (r !in saved && (month >= 9 || month <= 1) && (r == Regime.WESTERLY || r == Regime.NORTHERLY || r == Regime.SOUTHERLY)) {
            saved += r
            val img = Render.image(world) { i ->
                if (world.isSea[i]) 0x1f3f6f
                else Render.shade(world, i, Render.ramp(
                    (weather.precipitation[i] / (world.climate.monthlyPrecip[month][i] * share).coerceAtLeast(0.01)).toFloat(),
                    0f, 3f, intArrayOf(0xc8a050, 0xf0e8c0, 0x80c080, 0x2060c0)))
            }
            val gr = img.createGraphics()
            gr.color = Color.BLACK; gr.stroke = BasicStroke(2f)
            for (y in 25 until g.height step 50) for (x in 25 until g.width step 50) {
                val c = g.index(x, y)
                val u = weather.windEast(c); val v = weather.windSouth(c)
                gr.drawLine(x, y, (x + u * 3).toInt(), (y + v * 3).toInt())
                gr.fillOval((x + u * 3).toInt() - 3, (y + v * 3).toInt() - 3, 6, 6)
            }
            ImageIO.write(img, "png", File(out, "rain-${r.name.lowercase()}-week.png"))
        }
    }
    val land = (0 until g.size).filter { !world.isSea[it] && normal[it] > 0 }
    val ratio = land.map { rain[it] / normal[it] }.sorted()
    val n = steps.toDouble()
    val spread = land.map { sqrt((logSq[it] / n - (logSum[it] / n).let { m -> m * m }).coerceAtLeast(0.0)) }.sorted()
    println("Regime frequency by season (weeks): " + Regime.entries.joinToString(" ") { it.name.lowercase() })
    val names = listOf("spring", "summer", "autumn", "winter")
    for (s in 0 until 4) println("  %-7s".format(names[s]) + counts[s].joinToString(" ") { "%5d".format(it) })
    println("Rain over $years years against the normals, land cells: median %.2f, 5%% %.2f, 95%% %.2f".format(ratio[ratio.size / 2], ratio[ratio.size / 20],
        ratio[ratio.size * 19 / 20]))
    println("Week-to-week spread of log rain per cell: median %.2f, 5%% %.2f, 95%% %.2f".format(spread[spread.size / 2], spread[spread.size / 20],
        spread[spread.size * 19 / 20]))
    println("Maps written to ${out.absolutePath}")
}
