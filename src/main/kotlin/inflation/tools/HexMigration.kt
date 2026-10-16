package inflation.tools

import inflation.Args
import inflation.ecology.Catalogue
import inflation.learning.HexLearning
import inflation.world.Date
import inflation.world.WorldConfig
import kotlin.math.sqrt

/**
 * Whether the land gives the herds a reason to migrate, and whether they take it. For each herd
 * kind over the last year of a run of the hand-written behaviour, month by month: how good each
 * place is for it (as its sense of better country reckons it: food, its ground, water, the month's
 * climate), and from that how far its best country moves over the year (the centre of its best
 * tenth of places, January against July), how far a herd could do better than where it stands by
 * going elsewhere within 50, 150 and 300 km at the season's turn, and where its herds actually were.
 *
 * Usage: hex-migration [--seed N] [--width KM] [--height KM] [--years N]
 */
fun main(args: Array<String>) {
    val a = Args(args, "hex-migration [--seed N] [--width KM] [--height KM] [--years N]")
    val base = WorldConfig()
    val cfg = base.copy(seed = a.long("--seed", base.seed), widthKm = a.int("--width", base.widthKm), heightKm = a.int("--height", base.heightKm))
    val years = a.int("--years", 4)
    a.done()
    val run = HexLearning.Run(cfg, Catalogue.learned)
    val herds = Catalogue.learned.filter { it in Catalogue.migratory }
    val steps = years * Date.DAYS_PER_YEAR / cfg.daysPerStep; val perYear = Date.DAYS_PER_YEAR / cfg.daysPerStep
    val n = run.land.n
    // Month by month over the last year: each kind's quality per place, and where its herds stood.
    val q = Array(herds.size) { Array(12) { FloatArray(n) } }
    val seen = Array(herds.size) { BooleanArray(12) }
    val at = Array(herds.size) { Array(12) { HashMap<Int, IntArray>() } }
    val green = Array(12) { FloatArray(n) }; val greenSeen = BooleanArray(12)
    repeat(steps) { w ->
        run.step()
        if (w < steps - perYear) return@repeat
        val m = run.weather.date.month
        for ((i, s) in herds.withIndex()) {
            for (g in run.groups.groups) if (g.alive && g.species == s) at[i][m].getOrPut(g.id) { IntArray(1) }[0] = run.hw.placeHex[g.place]
            if (!greenSeen[m]) { greenSeen[m] = true; for (p in 0 until n) green[m][p] = run.land.green[p] }
            if (seen[i][m]) continue
            seen[i][m] = true
            // (As the herds themselves reckon it: see [inflation.ecology.HexGroups.herdQuality].)
            for (p in 0 until n) q[i][m][p] = run.groups.herdQuality(s, p, m)
        }
    }
    val hex = run.hex; val hw = run.hw
    val placeHex = IntArray(n) { hw.placeHex[it] }
    fun centre(qs: FloatArray): Pair<Double, Double> {
        val cut = qs.sortedDescending().let { it[(it.size / 10).coerceAtMost(it.size - 1)] }
        var x = 0.0; var y = 0.0; var wsum = 0.0
        for (p in 0 until n) if (qs[p] >= cut && qs[p] > 0f) { val (cx, cy) = hex.centreKm(placeHex[p]); x += cx * qs[p]; y += cy * qs[p]; wsum += qs[p] }
        return if (wsum > 0) (x / wsum) to (y / wsum) else 0.0 to 0.0
    }
    // The land by height: how much of it, how rough, how much a non-climbing grazer can get at, its grass, and how good it is for gazelle in month 5.
    run {
        val gaz = herds.indexOfFirst { it.name == "gazelle" }
        val bands = listOf(0.0 to 500.0, 500.0 to 1000.0, 1000.0 to 2000.0, 2000.0 to 9000.0)
        for ((lo, hi) in bands) {
            val ps = (0 until n).filter { run.hw.placeElevationM[it] in lo..hi }
            if (ps.isEmpty()) continue
            val area = ps.sumOf { run.land.areaKm2[it].toDouble() }
            fun mean(f: (Int) -> Double) = ps.sumOf { f(it) * run.land.areaKm2[it] } / area
            val g = herds[gaz]
            println("height %4.0f-%4.0f m: %.0f km², ruggedness %.2f, a grazer gets at %.2f of it, grass %.3f kg/m², green month 5 %.2f, month 8 %.2f, gazelle quality month 5 %.2f".format(lo, hi, area,
                mean { run.land.ruggedness[it].toDouble() }, mean { inflation.ecology.HexFood.access(g, run.land.crag[it]).toDouble() },
                mean { run.land.herb[it].toDouble() }, mean { green[5][it].toDouble() }, mean { green[8][it].toDouble() }, mean { q[gaz][5][it].toDouble() }))
        }
    }
    println("all land, green by month: " + (0 until 12).joinToString(" ") { m -> "%.2f".format((0 until n).sumOf { green[m][it].toDouble() } / n) })
    println("kind | best tenth's centre, January to July km | herds: gain by moving within 50 / 150 / 300 km at the turn of the season (quality, July at the July best within reach against July where they stood in January) | herds' January to July km (mean)")
    for ((i, s) in herds.withIndex()) {
        val (jx, jy) = centre(q[i][0]); val (ux, uy) = centre(q[i][6])
        val shift = sqrt((jx - ux) * (jx - ux) + (jy - uy) * (jy - uy))
        // For each herd: where it stood in January; the best July quality within each radius, against July quality there.
        val gains = DoubleArray(3); var count = 0; var walked = 0.0
        val jul = q[i][6]
        // Best July quality per hex (over its places).
        val bestHex = HashMap<Int, Float>()
        for (p in 0 until n) bestHex.merge(placeHex[p], jul[p]) { a, b -> maxOf(a, b) }
        val hexes = bestHex.keys.toIntArray()
        for ((id, hJ) in at[i][0].entries.sortedBy { it.key }.take(300).associate { it.key to it.value }) {
            val h0 = hJ[0]; val here = bestHex[h0] ?: continue
            val radii = doubleArrayOf(50.0, 150.0, 300.0)
            for ((r, km) in radii.withIndex()) {
                var best = here
                for (h in hexes) if (hex.distanceKm(h0, h) <= km) best = maxOf(best, bestHex[h]!!)
                gains[r] += (best - here).toDouble()
            }
            at[i][6][id]?.let { walked += hex.distanceKm(h0, it[0]) }
            count++
        }
        if (count == 0) { println("%-11s | %4.0f | no herds".format(s.name, shift)); continue }
        // How much the places its herds stood on in January swing over the year: worst month against best.
        var swing = 0.0; var low = 0.0; var c2 = 0
        for ((_, hJ) in at[i][0]) { val p = hw.placeOf[0][hJ[0]]; if (p < 0) continue
            var mx = 0f; var mn = Float.MAX_VALUE; for (m in 0 until 12) { mx = maxOf(mx, q[i][m][p]); mn = minOf(mn, q[i][m][p]) }
            if (mx > 0f) { swing += (1 - mn / mx).toDouble(); low += mn.toDouble(); c2++ } }
        val gm = (0 until 12).map { m -> at[i][0].values.mapNotNull { hw.placeOf[0][it[0]].takeIf { p -> p >= 0 } }.map { green[m][it].toDouble() }.average() }
        println("%-11s green at its January ground by month: %s".format(s.name, gm.joinToString(" ") { "%.2f".format(it) }))
        val elev = (0 until 12).map { m -> at[i][m].values.map { hex.midM[it[0]].toDouble() }.average() }
        println("%-11s mean height of its herds by month, m: %s".format(s.name, elev.joinToString(" ") { "%.0f".format(it) }))
        println("%-11s | %4.0f | %.3f / %.3f / %.3f | %.0f (%d herds) | swing over the year at their January ground %.0f%%, worst month quality %.2f".format(s.name, shift, gains[0] / count, gains[1] / count, gains[2] / count, walked / count, count, 100 * swing / c2.coerceAtLeast(1), low / c2.coerceAtLeast(1)))
    }
}
