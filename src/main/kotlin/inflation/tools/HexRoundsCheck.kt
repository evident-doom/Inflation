package inflation.tools

import inflation.monthsFrom
import inflation.Args
import inflation.Config

import inflation.ecology.Catalogue
import inflation.ecology.roundOf
import inflation.ecology.roundsWhere
import inflation.learning.HexLearning
import inflation.world.Date
import inflation.world.WorldConfig
import java.util.TreeMap

/**
 * Whether herds keep to their yearly rounds: for each herd kind, month by
 * month over the last year of a run, the share of its herds on a round, how far they are from the
 * round's place that month (median km), the share within 50 km of it, and how far apart a round's
 * places lie on average from January to July; with the kind's numbers. And, over the whole run, herds
 * on a round against herds keeping to their own country: share of the kind, how well fed, protein,
 * and births and deaths by cause per 100 animals a year (which way pays).
 *
 * Usage: hex-rounds-check [--seed N] [--width KM] [--height KM] [--years N]
 */
fun main(args: Array<String>) {
    val a = Args(args, "hex-rounds-check [--seed N] [--width KM] [--height KM] [--years N]")
    var cfg = WorldConfig()
    cfg = cfg.copy(seed = a.long("--seed", cfg.seed), widthKm = a.int("--width", cfg.widthKm), heightKm = a.int("--height", cfg.heightKm))
    val years = a.int("--years", 4)
    a.done()
    val run = HexLearning.Run(cfg, Catalogue.learned.filter { !it.human || Config.flag("people", true) })
    val herds = Catalogue.learned.filter { it in Catalogue.migratory }
    val steps = years * Date.DAYS_PER_YEAR / cfg.daysPerStep; val perYear = Date.DAYS_PER_YEAR / cfg.daysPerStep
    // [kind][month]: distances of herds on a round to its place, and how many herds there were.
    val dists = Array(herds.size) { Array(12) { ArrayList<Double>() } }
    val total = Array(herds.size) { IntArray(12) }
    val start = herds.map { s -> run.groups.count(s) }
    // [kind][way]: animal-weeks, fed and protein summed over them; births and deaths by cause.
    val heads = Array(herds.size) { DoubleArray(2) }; val fedSum = Array(herds.size) { DoubleArray(2) }; val protSum = Array(herds.size) { DoubleArray(2) }
    // (And in the birth season only: mothers, fed and protein then.)
    val sHeads = Array(herds.size) { DoubleArray(2) }
    val sFed = Array(herds.size) { DoubleArray(2) }
    val sProt = Array(herds.size) { DoubleArray(2) }
    val sMothers = Array(herds.size) { DoubleArray(2) }
    // (And where they are: hunters about, kg/km², and animals of the kind in the same hex, by head.)
    val huntSum = Array(herds.size) { DoubleArray(2) }; val kinSum = Array(herds.size) { DoubleArray(2) }
    val events = Array(herds.size) { Array(2) { TreeMap<String, Int>() } }
    val ways = listOf("on round", "own country")
    val shareByYear = Array(herds.size) { ArrayList<Int>() }
    // Where meetings and kills happen: "WAY|GOING|WHERE|WHO|met or killed" -> count, and animal-weeks by
    // "WAY|GOING|WHERE" (see [inflation.ecology.roundsWhere]).
    val where = Array(herds.size) { TreeMap<String, Int>() }
    val whereHeads = Array(herds.size) { TreeMap<String, Double>() }
    val whereHerdWeeks = Array(herds.size) { TreeMap<String, Int>() }
    repeat(steps) { w ->
        run.step()
        for ((i, s) in herds.withIndex()) {
            val inHex = HashMap<Int, Int>()
            for (g in run.groups.groups) if (g.alive && g.species == s) inHex.merge(run.hw.placeHex[g.place], g.size, Int::plus)
            for (g in run.groups.groups) if (g.alive && g.species == s && g.route[0] != -1) {
                val k = if (g.route[0] >= 0) 0 else 1
                huntSum[i][k] += g.size * run.groups.monthHunterKg[g.place].toDouble() / run.groups.land.areaKm2[g.place]
                kinSum[i][k] += g.size * inHex.getValue(run.hw.placeHex[g.place]).toDouble()
                heads[i][k] += g.size.toDouble(); fedSum[i][k] += g.fed * g.size.toDouble(); protSum[i][k] += g.protein * g.size.toDouble()
                if (monthsFrom(g.birthMonth, run.weather.date.month) < s.birthMonths) {
                    sHeads[i][k] += g.size.toDouble()
                    sFed[i][k] += g.fed * g.size.toDouble()
                    sProt[i][k] += g.protein * g.size.toDouble()
                    sMothers[i][k] += g.adults / 2.0
                }
            }
            for ((key, n) in run.groups.stats.hunting.subMap("rounds|${s.name}|", "rounds|${s.name}}")) {
                val c = key.substringAfter("rounds|${s.name}|")
                if (c.endsWith("|heads")) whereHeads[i].merge(c.removeSuffix("|heads"), n.toDouble(), Double::plus)
                else if (c.endsWith("|herds")) whereHerdWeeks[i].merge(c.removeSuffix("|herds"), n, Int::plus)
                else where[i].merge(c, n, Int::plus)
            }
            for ((k, way) in ways.withIndex()) for ((key, n) in run.groups.stats.deaths.subMap("${s.name} $way:",
                "${s.name} $way;")) events[i][k].merge(key.substringAfter(':'), n, Int::plus)
            if ((w + 1) % perYear == 0) {
                val on = run.groups.groups.filter { it.alive && it.species == s }
                shareByYear[i] += (100.0 * on.filter { it.route[0] >= 0 }.sumOf { it.size } / on.sumOf { it.size }.coerceAtLeast(1)).toInt()
            }
        }
        if (w < steps - perYear) return@repeat
        val m = run.weather.date.month
        for ((i, s) in herds.withIndex()) for (g in run.groups.groups) {
            if (!g.alive || g.species != s) continue
            total[i][m]++
            val r = run.groups.roundOf(g, m)?.first ?: continue
            dists[i][m] += run.hex.distanceKm(run.hw.placeHex[g.place], run.hw.placeHex[r])
        }
    }
    for ((i, s) in herds.withIndex()) {
        val span = run.groups.groups.filter { it.alive && it.species == s && it.route[0] >= 0 }
            .map { run.hex.distanceKm(run.hw.placeHex[it.route[0]], run.hw.placeHex[it.route[6]]) }.average()
        println("%-11s animals %d -> %d; rounds' January-July span %.0f km".format(s.name, start[i], run.groups.count(s), span))
        println("   on a round:      " + (0 until 12).joinToString(" ") { m ->
            "%4.0f%%".format(100.0 * dists[i][m].size / total[i][m].coerceAtLeast(1))
        })
        println("   median km off:   " + (0 until 12).joinToString(" ") { m ->
            dists[i][m].sorted().let { if (it.isEmpty()) "    -" else "%5.0f".format(it[it.size / 2]) }
        })
        println("   within 50 km:    " + (0 until 12).joinToString(" ") { m ->
            "%4.0f%%".format(100.0 * dists[i][m].count { it <= 50.0 } / dists[i][m].size.coerceAtLeast(1))
        })
        println("   share on a round by year: " + shareByYear[i].joinToString(" ") { "$it%" })
        for ((k, way) in ways.withIndex()) {
            val years = heads[i][k] / perYear; if (years <= 0.0) continue
            println("   %-12s fed %.2f protein %.2f; in the birth season %.0f%% of animal-weeks, fed %.2f protein %.2f, mothers %.2f a head; hunters about %.2f kg/km², kin in the hex %.0f; per 100 a year: %s"
                .format(way, fedSum[i][k] / heads[i][k], protSum[i][k] / heads[i][k],
                100.0 * sHeads[i][k] / heads[i][k], sFed[i][k] / sHeads[i][k].coerceAtLeast(1.0), sProt[i][k] / sHeads[i][k].coerceAtLeast(1.0),
                sMothers[i][k] / sHeads[i][k].coerceAtLeast(1.0), huntSum[i][k] / heads[i][k], kinSum[i][k] / heads[i][k],
                events[i][k].entries.sortedByDescending { it.value }.take(8).joinToString(", ") { "%s %.1f".format(it.key, 100.0 * it.value / years) }))
        }
        // Where: for each way of living, still or moving this week, and for herds on a round near their round's place or not:
        // its share of the way's animal-weeks, meetings with hunters per herd-week, and kills per 100 animals a year, by people and by animals.
        println("   where (share of animal-weeks; meetings a herd-week, people/animals; kills per 100 a year, people/animals):")
        for ((c, h) in whereHeads[i]) {
            val way = c.substringBefore('|'); val wayHeads = whereHeads[i].filterKeys { it.startsWith("$way|") }.values.sum()
            val yrs = h / perYear; val hw = whereHerdWeeks[i].getValue(c).toDouble()
            fun n(who: String, what: String) = (where[i]["$c|$who|$what"] ?: 0).toDouble()
            println("     %-28s %4.0f%%  met %.3f / %.3f  killed %5.1f / %5.1f".format(c.replace('|', ' '), 100 * h / wayHeads,
                n("people", "met") / hw, n("animals", "met") / hw, 100 * n("people", "killed") / yrs, 100 * n("animals", "killed") / yrs))
            // And why: the hexes its way crossed, the hunters about where it set out, and where on its week it met them.
            fun x(what: String) = (where[i]["$c|$what"] ?: 0).toDouble()
            val met = (x("met on the way") + x("met where it grazes") + x("met round it")).coerceAtLeast(1.0)
            println("     %-28s        crossed %.2f hexes; hunters at start %.2f kg/km²; met on the way %3.0f%%, where it grazes %3.0f%%, round it %3.0f%%; on worn ways %3.0f%%".format("",
                x("crossed") / hw, x("hunters at start") / 100 / hw, 100 * x("met on the way") / met, 100 * x("met where it grazes") / met, 100 * x("met round it") / met, 100 * x("met on worn ways") / met))
            // And how meetings went: seen first, let pass by fed hunters, chased; tries and wariness a chase; kills per 1000 animals met.
            fun both(what: String) = n("people", what) + n("animals", what)
            val m = both("met").coerceAtLeast(1.0); val ch = both("chased").coerceAtLeast(1.0)
            println("     %-28s        %.0f animals a meeting; saw them first %3.0f%%, hunters fed %3.0f%%, chased %3.0f%%; %.2f tries and wary %.2f a chase; killed %.1f per 1000 met".format("",
                both("heads met") / m, 100 * both("saw them first") / m, 100 * both("hunters fed") / m, 100 * both("chased") / m,
                both("tries") / ch, both("wary") / 100 / ch, 1000 * both("killed") / both("heads met").coerceAtLeast(1.0)))
        }
    }
}
