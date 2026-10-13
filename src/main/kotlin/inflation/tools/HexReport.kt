package inflation.tools

import inflation.world.HexMap
import inflation.world.HexWorld
import inflation.world.RiverClass
import inflation.world.World
import inflation.world.WorldConfig


import java.util.PriorityQueue
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * How well a hex map keeps what matters for movement in the fine terrain: what kinds of border it
 * has (open, hard, passes, rivers, none), and for random journeys across the land, how the effort
 * by the hex borders compares with the easiest way over the fine terrain, and whether the two
 * agree on which journeys are possible at all.
 *
 * Usage: hex-report [--seed N] [--width KM] [--height KM] [--cell KM] [--hex KM] [--png FILE]
 */
fun main(args: Array<String>) {
    var cfg = WorldConfig(seed = 1)
    var hexKm = 10.0
    var png: java.io.File? = null
    var k = 0
    while (k < args.size) {
        when (args[k]) {
            "--seed" -> cfg = cfg.copy(seed = args[++k].toLong())
            "--width" -> cfg = cfg.copy(widthKm = args[++k].toInt())
            "--height" -> cfg = cfg.copy(heightKm = args[++k].toInt())
            "--cell" -> cfg = cfg.copy(cellKm = args[++k].toDouble())
            "--hex" -> hexKm = args[++k].toDouble()
            "--png" -> png = java.io.File(args[++k])
            else -> error("unknown argument ${args[k]}")
        }
        k++
    }
    var t0 = System.nanoTime()
    val world = World.generate(cfg)
    println("world %d x %d km at %.0f km cells generated in %.0f s".format(cfg.widthKm, cfg.heightKm, cfg.cellKm, (System.nanoTime() - t0) / 1e9))
    t0 = System.nanoTime()
    val hex = HexMap(world, hexKm)
    val land = (0 until hex.size).filter { hex.heart[it] >= 0 }
    println("%.0f km hexes: %d, of them %d with land; summarised in %.1f s".format(hexKm, hex.size, land.size, (System.nanoTime() - t0) / 1e9))
    // Borders between land hexes.
    var open = 0; var hard = 0; var dear = 0; var none = 0; var pass = 0; var river = 0; var bigRiver = 0
    for (a in land) for (d in 0 until 3) {
        val b = hex.neighbour(a, d)
        if (b < 0 || hex.heart[b] < 0) continue
        val e = hex.crossing[a * 6 + d]
        when {
            !e.isFinite() -> none++
            e < 1.5 * hexKm -> open++
            e < 4 * hexKm -> hard++
            else -> dear++
        }
        if (e.isFinite()) {
            val lo = maxOf(world.elevation[hex.heart[a]], world.elevation[hex.heart[b]])
            if (hex.passM[a * 6 + d] - lo > 300f) pass++
            if (hex.riverM3s[a * 6 + d] > 10f) river++
            if (hex.riverM3s[a * 6 + d] > 200f) bigRiver++
        }
    }
    val all = open + hard + dear + none
    val ringed = land.count { h -> (0 until 6).all { d -> val n = hex.neighbour(h, d); n < 0 || hex.heart[n] < 0 || !hex.crossing[h * 6 + d].isFinite() } && (0 until 6).any { d -> val n = hex.neighbour(h, d); n >= 0 && hex.heart[n] >= 0 } }
    println("land hexes with no way out on foot to any land neighbour: %d".format(ringed))
    val isRinged = { h: Int -> (0 until 6).all { d -> val n = hex.neighbour(h, d); n < 0 || hex.heart[n] < 0 || !hex.crossing[h * 6 + d].isFinite() } && (0 until 6).any { d -> val n = hex.neighbour(h, d); n >= 0 && hex.heart[n] >= 0 } }
    println("  of them mostly land: %d".format(land.count { isRinged(it) && hex.landCells[it] > hex.cells[it] / 2 }))
    for (h in land.filter { isRinged(it) && hex.landCells[it] > hex.cells[it] / 2 }.take(12)) {
        val cs = (0 until world.grid.size).filter { hex.owner[it] == h }
        val (cx, cy) = hex.centreKm(h)
        println("  ringed hex at (%.0f, %.0f) km: land %d of %d cells, lake %d, deep river cells %d, cliff cells %d, heart walk cost %.1f, biggest river %.0f m³/s".format(
            cx, cy, cs.count { !world.isSea[it] && !world.water.isLake(it) }, cs.size, cs.count { world.water.isLake(it) },
            cs.count { world.terrain.crossingDepthM(it) > 1.5f }, cs.count { world.terrain.cliff[it] }, hex.walk[hex.heart[h]], cs.maxOf { world.water.discharge[it] }))
    }
    println("borders between land hexes: %d | open %.0f%%, hard %.0f%%, very hard %.0f%%, no way %.0f%% | over a pass (300 m+ climb) %.0f%%, across a river (10+ m³/s) %.0f%%, across a big river (200+ m³/s) %.1f%%".format(
        all, 100.0 * open / all, 100.0 * hard / all, 100.0 * dear / all, 100.0 * none / all, 100.0 * pass / all, 100.0 * river / all, 100.0 * bigRiver / all))
    val lands = land.filter { hex.landCells[it] > hex.cells[it] / 2 }
    val landCells = (0 until world.grid.size).count { !world.isSea[it] }
    val rivers = (0 until world.grid.size).filter { world.riverClass(it) >= RiverClass.STREAM }
    println("rivers: %d km of streams and rivers, %d km with 50+ m³/s, %d km with 200+ m³/s; deepest crossing on them: %d km over 1 m, %d km over 1.5 m".format(
        rivers.size, rivers.count { world.water.discharge[it] > 50f }, rivers.count { world.water.discharge[it] > 200f },
        rivers.count { world.terrain.crossingDepthM(it) > 1f }, rivers.count { world.terrain.crossingDepthM(it) > 1.5f }))
    val cliffs = (0 until world.grid.size).count { world.terrain.cliff[it] }
    val lowCliffs = (0 until world.grid.size).count { world.terrain.cliff[it] && world.elevation[it] < 1500f }
    println("cliffs (escarpments, gorge walls): %.2f%% of land, %d km² of them below 1500 m; hexes with a cliff: %.0f%%".format(
        100.0 * cliffs / landCells, lowCliffs, 100.0 * (0 until world.grid.size).filter { world.terrain.cliff[it] }.map { hex.owner[it] }.toSet().count { it in lands.toSet() } / lands.size))
    println("land hexes: median relief (highest - lowest) %.0f m, 90%% %.0f m; median share of crag (> 24° over a km) %.0f%%".format(
        pct(lands.map { hex.highM[it] - hex.lowM[it] }, .5), pct(lands.map { hex.highM[it] - hex.lowM[it] }, .9), 100 * pct(lands.map { hex.crag[it] }, .5)))
    // Journeys: by the hex borders against the fine terrain.
    // Per way of travelling: how many land borders have no way across, and the median effort of the rest.
    for (tr in HexMap.Travel.entries) {
        val c = hex.crossings[tr.ordinal]
        val es = ArrayList<Float>(); var shut = 0
        for (a in land) for (d in 0 until 3) {
            val b = hex.neighbour(a, d)
            if (b < 0 || hex.heart[b] < 0) continue
            if (c[a * 6 + d].isFinite()) es += c[a * 6 + d] / hexKm.toFloat() else shut++
        }
        println("  %-6s borders with no way %.1f%%; effort to cross median %.1f× flat, 90%% %.1f×".format(tr.name.lowercase(), 100.0 * shut / (shut + es.size), pct(es, .5), pct(es, .9)))
    }
    val rnd = Random(cfg.seed)
    val g = world.grid
    val ratio = ArrayList<Float>()
    var both = 0; var onlyFine = 0; var onlyHex = 0; var neither = 0
    var tries = 0
    while (both + onlyFine + onlyHex + neither < 80 && tries < 5000) {
        tries++
        val a = lands[rnd.nextInt(lands.size)]
        val (ax, ay) = hex.centreKm(a)
        val km = 50 + rnd.nextDouble() * 100
        val ang = rnd.nextDouble() * 2 * Math.PI
        val b = hex.hexAt(ax + cos(ang) * km, ay + sin(ang) * km)
        if (b == a || hex.heart[b] < 0 || hex.landCells[b] <= hex.cells[b] / 2) continue
        val byHex = hex.efforts(a)[b]
        val byFine = fine(world, hex.walk, hex.heart[a], hex.heart[b], (km + 60).toInt())
        when {
            byHex.isFinite() && byFine.isFinite() -> { both++; ratio += byHex / byFine }
            byFine.isFinite() -> onlyFine++
            byHex.isFinite() -> onlyHex++
            else -> neither++
        }
    }
    println("journeys of 50-150 km: possible both ways %d, only over the fine terrain %d, only by hexes %d, neither %d; effort by hexes / fine: median %.2f, 10%% %.2f, 90%% %.2f".format(
        both, onlyFine, onlyHex, neither, pct(ratio, .5), pct(ratio, .1), pct(ratio, .9)))
    png?.let { draw(world, hex, it) }
    png?.let { p ->
        t0 = System.nanoTime()
        val hw = HexWorld(world, hex)
        println("hex world summarised in %.1f s".format((System.nanoTime() - t0) / 1e9))
        hw.draw(java.io.File(p.parentFile, p.nameWithoutExtension + "-hexworld.png"), 1.0)
    }
}

private fun pct(v: List<Float>, p: Double) = v.sorted().let { if (it.isEmpty()) Float.NaN else it[((it.size - 1) * p).toInt()] }

/** Least effort over the fine terrain from cell [a] to cell [b], within [km] of [a]. */
private fun fine(world: World, walk: FloatArray, a: Int, b: Int, km: Int): Float {
    val g = world.grid
    val r = (km / g.cellKm).toInt()
    val x0 = maxOf(0, g.x(a) - r); val x1 = minOf(g.width - 1, g.x(a) + r)
    val y0 = maxOf(0, g.y(a) - r); val y1 = minOf(g.height - 1, g.y(a) + r)
    val w = x1 - x0 + 1
    val dist = FloatArray(w * (y1 - y0 + 1)) { Float.POSITIVE_INFINITY }
    fun local(i: Int) = (g.y(i) - y0) * w + g.x(i) - x0
    val q = PriorityQueue<Pair<Float, Int>>(compareBy { it.first })
    dist[local(a)] = 0f; q += 0f to a
    val ck = g.cellKm.toFloat()
    while (q.isNotEmpty()) {
        val (d, i) = q.poll()
        if (i == b) return d
        if (d > dist[local(i)]) continue
        val xi = g.x(i); val yi = g.y(i)
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            val nx = xi + dx; val ny = yi + dy
            if (nx < x0 || nx > x1 || ny < y0 || ny > y1) continue
            val j = g.index(nx, ny)
            if (!walk[j].isFinite()) continue
            if (dx != 0 && dy != 0 && (!walk[g.index(xi + dx, yi)].isFinite() || !walk[g.index(xi, yi + dy)].isFinite())) continue
            val nd = d + (if (dx != 0 && dy != 0) 1.4142f else 1f) * ck * 0.5f * (walk[i] + walk[j])
            val lj = local(j)
            if (nd < dist[lj]) { dist[lj] = nd; q += nd to j }
        }
    }
    return Float.POSITIVE_INFINITY
}

/** Draws the shaded fine terrain with the hex borders: thin grey open, orange hard, red very hard, black no way, blue across a big river. */
private fun draw(world: World, hex: HexMap, file: java.io.File) {
    val g = world.grid
    val scale = 2
    val img = java.awt.image.BufferedImage(g.width * scale, g.height * scale, java.awt.image.BufferedImage.TYPE_INT_RGB)
    for (y in 0 until g.height) for (x in 0 until g.width) {
        val i = g.index(x, y)
        val e = world.elevation[i]
        val c = if (world.isSea[i]) 0x30506e else {
            val h = (e / 4000f).coerceIn(0f, 1f)
            val ex = world.elevation[g.index(minOf(x + 1, g.width - 1), y)] - world.elevation[g.index(maxOf(x - 1, 0), y)]
            val ey = world.elevation[g.index(x, minOf(y + 1, g.height - 1))] - world.elevation[g.index(x, maxOf(y - 1, 0))]
            val shade = (0.75f - (ex + ey) / (4f * g.cellM.toFloat())).coerceIn(0.3f, 1f)
            val r = ((90 + 140 * h) * shade).toInt(); val gg = ((130 + 90 * h) * shade).toInt(); val b = ((80 + 150 * h) * shade).toInt()
            (r.coerceIn(0, 255) shl 16) or (gg.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
        }
        for (sy in 0 until scale) for (sx in 0 until scale) img.setRGB(x * scale + sx, y * scale + sy, c)
    }
    val gr = img.createGraphics()
    gr.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
    val px = scale / world.grid.cellKm
    for (a in 0 until hex.size) {
        if (hex.heart[a] < 0) continue
        val (ax, ay) = hex.centreKm(a)
        for (d in 0 until 3) {
            val b = hex.neighbour(a, d)
            if (b < 0 || hex.heart[b] < 0) continue
            val (bx, by) = hex.centreKm(b)
            val e = hex.crossing[a * 6 + d]
            gr.color = when {
                !e.isFinite() -> java.awt.Color.BLACK
                hex.riverM3s[a * 6 + d] > 200f -> java.awt.Color(0x2060ff)
                e >= 4 * hex.hexKm -> java.awt.Color(0xd02020)
                e >= 1.5 * hex.hexKm -> java.awt.Color(0xff9020)
                else -> java.awt.Color(255, 255, 255, 90)
            }
            // Draw the border itself: the segment between the two hexes' shared corners.
            val mx = (ax + bx) / 2; val my = (ay + by) / 2
            val nx = -(by - ay); val ny = bx - ax
            val len = hypot(nx, ny); val half = hex.hexKm / kotlin.math.sqrt(3.0) / 2
            gr.stroke = java.awt.BasicStroke(if (!e.isFinite() || e >= 4 * hex.hexKm) 2.5f else 1.2f)
            gr.drawLine(((mx + nx / len * half) * px).toInt(), ((my + ny / len * half) * px).toInt(), ((mx - nx / len * half) * px).toInt(), ((my - ny / len * half) * px).toInt())
        }
    }
    javax.imageio.ImageIO.write(img, "png", file)
}
