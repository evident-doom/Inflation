package inflation.tools

import inflation.world.World
import inflation.world.WorldConfig


import java.util.PriorityQueue
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * How mountainous country is shaped and how much it steers travel: slopes and relief of the high
 * ground, how much of it is plateau, and for random journeys of a set length across mountains and
 * across lowland, how much longer and harder the easiest way is than the straight line.
 *
 * Usage: terrain-report [--seed N] [--width KM] [--height KM] [--km N] [--belt KM] [--ridges KM] [--ridge-share S]
 *                       [--diffusivity D] [--critical S] [--steps N] [--png FILE]
 */
fun main(args: Array<String>) {
    var cfg = WorldConfig(seed = 1)
    var km = 40
    var png: java.io.File? = null
    var k = 0
    while (k < args.size) {
        when (args[k]) {
            "--seed" -> cfg = cfg.copy(seed = args[++k].toLong())
            "--width" -> cfg = cfg.copy(widthKm = args[++k].toInt())
            "--height" -> cfg = cfg.copy(heightKm = args[++k].toInt())
            "--km" -> km = args[++k].toInt()
            "--belt" -> cfg = cfg.copy(beltKm = args[++k].toDouble())
            "--ridges" -> cfg = cfg.copy(ridgeKm = args[++k].toDouble())
            "--ridge-share" -> cfg = cfg.copy(ridgeShare = args[++k].toDouble())
            "--diffusivity" -> cfg = cfg.copy(hillDiffusivity = args[++k].toDouble())
            "--critical" -> cfg = cfg.copy(criticalSlope = args[++k].toDouble())
            "--steps" -> cfg = cfg.copy(erosionSteps = args[++k].toInt())
            "--uplift" -> cfg = cfg.copy(upliftScale = args[++k].toDouble())
            "--plateau-km" -> cfg = cfg.copy(plateauKm = args[++k].toDouble())
            "--plateau-m" -> cfg = cfg.copy(plateauM = args[++k].toDouble())
            "--png" -> png = java.io.File(args[++k])
            else -> error("unknown argument ${args[k]}")
        }
        k++
    }
    val world = World.generate(cfg)
    val g = world.grid
    val t = world.terrain
    val land = (0 until g.size).filter { !world.isSea[it] }
    png?.let { hillshade(world, it) }
    fun pct(v: List<Float>, p: Double) = v.sorted().let { if (it.isEmpty()) Float.NaN else it[((it.size - 1) * p).toInt()] }
    fun deg(s: Float) = Math.toDegrees(atan(s.toDouble())).toFloat()
    val elev = land.map { world.elevation[it] }
    println("Land elevation, m: median %.0f, 90%% %.0f, 99%% %.0f, max %.0f".format(pct(elev, .5), pct(elev, .9), pct(elev, .99), elev.max()))
    for ((name, band) in listOf("lowland < 500 m" to 0f..500f, "hills 500-1500 m" to 500f..1500f, "mountains > 1500 m" to 1500f..9000f)) {
        val cells = land.filter { world.elevation[it] in band }
        if (cells.isEmpty()) continue
        val slope = cells.map { deg(t.slope[it]) }
        val relief = cells.map { t.relief[it] }
        val cost = cells.map { t.landCost[it] }.filter { it.isFinite() }
        val flat = cells.count { t.slope[it] < 0.05f }.toFloat() / cells.size
        println("%-20s %5.1f%% of land | slope median %.1f°, 90%% %.1f°, max %.1f° | relief (2 km) median %.0f m, 90%% %.0f m | walking cost median %.1f×, 90%% %.1f× | flat (< 3°) %.0f%%".format(
            name, 100f * cells.size / land.size, pct(slope, .5), pct(slope, .9), slope.max(), pct(relief, .5), pct(relief, .9),
            pct(cost, .5), pct(cost, .9), 100 * flat))
    }
    // Journeys: the easiest way between two points [km] apart, against the straight line.
    val rnd = Random(cfg.seed)
    for ((name, test) in listOf<Pair<String, (Int) -> Boolean>>("across mountains" to { world.elevation[it] > 1500f }, "across lowland" to { world.elevation[it] < 500f })) {
        val starts = land.filter(test)
        if (starts.size < 100) continue
        val longer = ArrayList<Float>(); val harder = ArrayList<Float>()
        var tries = 0
        while (longer.size < 60 && tries < 2000) {
            tries++
            val a = starts[rnd.nextInt(starts.size)]
            val ang = rnd.nextDouble() * 2 * Math.PI
            val bx = (g.x(a) + cos(ang) * km / g.cellKm).toInt(); val by = (g.y(a) + sin(ang) * km / g.cellKm).toInt()
            if (!g.inBounds(bx, by)) continue
            val b = g.index(bx, by)
            if (world.isSea[b] || !test(b)) continue
            val straightKm = hypot((bx - g.x(a)).toDouble(), (by - g.y(a)).toDouble()).toFloat() * g.cellKm.toFloat()
            val (cost, lengthKm) = easiest(world, a, b, km) ?: continue
            longer += lengthKm / straightKm; harder += cost / straightKm
        }
        println("%-20s %d journeys of %d km: easiest way median %.2f× the straight distance (90%% %.2f×), effort median %.1f× flat ground (90%% %.1f×)".format(
            name, longer.size, km, pct(longer, .5), pct(longer, .9), pct(harder, .5), pct(harder, .9)))
    }
}

/** Least-effort path from [a] to [b] on land within a box around them: (effort in flat-km, length km), or null. */
private fun easiest(world: World, a: Int, b: Int, km: Int): Pair<Float, Float>? {
    val g = world.grid
    val t = world.terrain
    val pad = (km / g.cellKm).toInt()
    val x0 = maxOf(0, minOf(g.x(a), g.x(b)) - pad); val x1 = minOf(g.width - 1, maxOf(g.x(a), g.x(b)) + pad)
    val y0 = maxOf(0, minOf(g.y(a), g.y(b)) - pad); val y1 = minOf(g.height - 1, maxOf(g.y(a), g.y(b)) + pad)
    val w = x1 - x0 + 1; val h = y1 - y0 + 1
    val dist = FloatArray(w * h) { Float.POSITIVE_INFINITY }
    val len = FloatArray(w * h)
    fun local(i: Int) = (g.y(i) - y0) * w + (g.x(i) - x0)
    val q = PriorityQueue<Pair<Float, Int>>(compareBy { it.first })
    dist[local(a)] = 0f; q += 0f to a
    val ck = g.cellKm.toFloat()
    while (q.isNotEmpty()) {
        val (d, i) = q.poll()
        if (i == b) return d to len[local(b)]
        if (d > dist[local(i)]) continue
        val xi = g.x(i); val yi = g.y(i)
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            val nx = xi + dx; val ny = yi + dy
            if (nx < x0 || nx > x1 || ny < y0 || ny > y1) continue
            val j = g.index(nx, ny)
            val cj = t.landCost[j]
            if (!cj.isFinite()) continue
            val step = if (dx != 0 && dy != 0) 1.4142f * ck else ck
            val nd = d + step * 0.5f * (t.landCost[i] + cj)
            val lj = local(j)
            if (nd < dist[lj]) { dist[lj] = nd; len[lj] = len[local(i)] + step; q += nd to j }
        }
    }
    return null
}

/** Writes a shaded relief map of [world]: light from the north-west, tinted by height. */
private fun hillshade(world: World, file: java.io.File) {
    val g = world.grid
    val img = java.awt.image.BufferedImage(g.width, g.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
    val cm = g.cellM.toFloat()
    for (y in 0 until g.height) for (x in 0 until g.width) {
        val i = g.index(x, y)
        if (world.isSea[i]) { img.setRGB(x, y, 0x30506e); continue }
        val e = world.elevation[i]
        val ex = world.elevation[g.index((x + 1).coerceAtMost(g.width - 1), y)] - world.elevation[g.index((x - 1).coerceAtLeast(0), y)]
        val ey = world.elevation[g.index(x, (y + 1).coerceAtMost(g.height - 1))] - world.elevation[g.index(x, (y - 1).coerceAtLeast(0))]
        // Light from the north-west, 45 degrees up.
        val nx = -ex / (2 * cm); val ny = -ey / (2 * cm)
        val shade = ((nx * -0.5f + ny * -0.5f + 0.7071f) / kotlin.math.sqrt(nx * nx + ny * ny + 1f)).coerceIn(0f, 1f)
        val h = (e / 4000f).coerceIn(0f, 1f)
        val r = (90 + 140 * h) * (0.35f + 0.65f * shade); val gg = (130 + 90 * h) * (0.35f + 0.65f * shade); val b = (80 + 150 * h) * (0.35f + 0.65f * shade)
        img.setRGB(x, y, (r.toInt().coerceIn(0, 255) shl 16) or (gg.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255))
    }
    javax.imageio.ImageIO.write(img, "png", file)
}
