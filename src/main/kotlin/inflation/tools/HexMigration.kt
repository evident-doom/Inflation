package inflation.tools

import inflation.ecology.Catalogue
import inflation.learning.HexLearning
import inflation.world.Date
import inflation.world.WorldConfig
import kotlin.math.min
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
    var cfg = WorldConfig(); var years = 4
    var k = 0
    while (k < args.size) {
        when (args[k]) {
            "--seed" -> cfg = cfg.copy(seed = args[++k].toLong())
            "--width" -> cfg = cfg.copy(widthKm = args[++k].toInt())
            "--height" -> cfg = cfg.copy(heightKm = args[++k].toInt())
            "--years" -> years = args[++k].toInt()
        }
        k++
    }
    val run = HexLearning.Run(cfg, Catalogue.learned)
    val herds = Catalogue.learned.filter { it in Catalogue.migratory }
    val steps = years * Date.DAYS_PER_YEAR / cfg.daysPerStep; val perYear = Date.DAYS_PER_YEAR / cfg.daysPerStep
    val n = run.land.n
    // Month by month over the last year: each kind's quality per place, and where its herds stood.
    val q = Array(herds.size) { Array(12) { FloatArray(n) } }
    val seen = Array(herds.size) { BooleanArray(12) }
    val at = Array(herds.size) { Array(12) { HashMap<Int, IntArray>() } }
    repeat(steps) { w ->
        run.step()
        if (w < steps - perYear) return@repeat
        val m = run.weather.date.month
        for ((i, s) in herds.withIndex()) {
            for (g in run.groups.groups) if (g.alive && g.species == s) at[i][m].getOrPut(g.id) { IntArray(1) }[0] = run.hw.placeHex[g.place]
            if (seen[i][m]) continue
            seen[i][m] = true
            val f = run.fauna
            for (p in 0 until n) {
                val food = f.foodFor(s, p) * min(1.5f, f.suits(s, p)) / 1.5f
                q[i][m][p] = food / (food + 0.3f) * f.tether(s, p) * f.monthFit(s, p, m)
            }
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
        println("%-11s | %4.0f | %.3f / %.3f / %.3f | %.0f (%d herds)".format(s.name, shift, gains[0] / count, gains[1] / count, gains[2] / count, walked / count, count))
    }
}
