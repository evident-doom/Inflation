package inflation.tools

import inflation.ecology.Catalogue
import inflation.learning.HexLearning
import inflation.world.Date
import inflation.world.WorldConfig

/**
 * Whether herds keep to their yearly rounds (run with -Dhex.rounds): for each herd kind, month by
 * month over the last year of a run, the share of its herds on a round, how far they are from the
 * round's place that month (median km), the share within 50 km of it, and how far apart a round's
 * places lie on average from January to July; with the kind's numbers.
 *
 * Usage: hex-rounds-check [--seed N] [--width KM] [--height KM] [--years N]
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
    // [kind][month]: distances of herds on a round to its place, and how many herds there were.
    val dists = Array(herds.size) { Array(12) { ArrayList<Double>() } }
    val total = Array(herds.size) { IntArray(12) }
    val start = herds.map { s -> run.groups.count(s) }
    repeat(steps) { w ->
        run.step()
        if (w < steps - perYear) return@repeat
        val m = run.weather.date.month
        for ((i, s) in herds.withIndex()) for (g in run.groups.groups) {
            if (!g.alive || g.species != s) continue
            total[i][m]++
            val r = g.route[m]; if (r < 0) continue
            dists[i][m] += run.hex.distanceKm(run.hw.placeHex[g.place], run.hw.placeHex[r])
        }
    }
    for ((i, s) in herds.withIndex()) {
        val span = run.groups.groups.filter { it.alive && it.species == s && it.route[0] >= 0 }.map { run.hex.distanceKm(run.hw.placeHex[it.route[0]], run.hw.placeHex[it.route[6]]) }.average()
        println("%-11s animals %d -> %d; rounds' January-July span %.0f km".format(s.name, start[i], run.groups.count(s), span))
        println("   on a round:      " + (0 until 12).joinToString(" ") { m -> "%4.0f%%".format(100.0 * dists[i][m].size / total[i][m].coerceAtLeast(1)) })
        println("   median km off:   " + (0 until 12).joinToString(" ") { m -> dists[i][m].sorted().let { if (it.isEmpty()) "    -" else "%5.0f".format(it[it.size / 2]) } })
        println("   within 50 km:    " + (0 until 12).joinToString(" ") { m -> "%4.0f%%".format(100.0 * dists[i][m].count { it <= 50.0 } / dists[i][m].size.coerceAtLeast(1)) })
    }
}
