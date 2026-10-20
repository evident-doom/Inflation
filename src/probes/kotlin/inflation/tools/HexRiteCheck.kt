package inflation.tools

import inflation.learning.RunScore
import inflation.run.freshSim
import inflation.Args
import inflation.ecology.Catalogue
import inflation.ecology.ROUNDS
import inflation.world.Date
import inflation.world.WorldConfig
import inflation.ecology.get
import inflation.ecology.set
import inflation.ecology.contains
import inflation.ecology.Place
import inflation.ecology.Hex
import inflation.ecology.hexOf

/**
 * What bands' rites do (run once as is, once with rounds=false, same seeds): each year, how far a
 * band gets from where it began the year (km, its furthest), the share of bands that set foot in
 * country of another kind than their own (by each hex's chief kind of country), how many bands hold
 * rites and how strongly, and people's score (people-weeks per person at the start, less their luck in
 * births and deaths, as the training checks it). Hand-written moves and the scaffold's ways.
 *
 * Usage: hex-rite-check [--seeds A,B] [--size KM] [--years N]
 */
fun main(args: Array<String>) {
    val a = Args(args, "hex-rite-check [--seeds A,B] [--size KM] [--years N]")
    val seeds = a.string("--seeds")?.split(",")?.map { it.toLong() } ?: listOf(91L); val size = a.int("--size", 600); val years = a.int("--years", 4)
    a.done()
    val kinds = Catalogue.learned
    println("rounds ${if (ROUNDS) "on" else "off"}, $size km, $years years")
    val lines = seeds.parallelStream().map { seed ->
        val run = freshSim(WorldConfig(seed = seed, widthKm = size, heightKm = size), kinds)
        val hw = run.hw; val hex = run.hex
        fun chief(h: Int): Int { var best = 0; for (b in hw.biome.indices) if (hw.biome[b][h] > hw.biome[best][h]) best = b; return best }
        val stepsPerYear = Date.DAYS_PER_YEAR / WorldConfig().daysPerStep
        val score = RunScore(run, listOf(Catalogue.HUMAN))
        val sb = StringBuilder("seed $seed:")
        repeat(years) { y ->
            val from = HashMap<Int, Int>(); val far = HashMap<Int, Double>(); val home = HashMap<Int, Int>(); val strayed = HashSet<Int>()
            var riteKm = 0.0; var riteWeeks = 0; var riteAt = 0
            for (g in run.groups.groups) if (g.alive && g.species.human) { val h = hw.placeHex[g.place]; from[g.id] = h; home[g.id] = chief(h) }
            repeat(stepsPerYear) {
                run.step(); score.afterStep(run)
                for (g in run.groups.groups) if (g.species.human) {
                    if (!g.alive) continue
                    val h = hw.placeHex[g.place]; val f = from[g.id] ?: continue
                    far.merge(g.id, hex.distanceKm(f, h), ::maxOf)
                    if (chief(h) != home[g.id]) strayed += g.id
                    g.band?.let { b -> val m = run.weather.date.month; val site = b.rite[m]
                        if (site >= 0 && b.riteStrength > 0f) {
                            val km = hex.distanceKm(h, hw.placeHex[site])
                            riteKm += km
                            riteWeeks++
                            if (km <= 1.01 * hex.hexKm) riteAt++
                        } }
                }
            }
            val bands = run.groups.groups.filter { it.alive && it.band != null }
            val withRites = bands.filter { b -> b.band!!.rite.any { it >= 0 } }
            sb.append("\n  year %d: %d bands, furthest from the year's start %.0f km (median %.0f, over 50 km %.0f%%), %.0f%% set foot in other country; rites held by %.0f%% (%.1f sites, hold %.2f)"
                .format(y + 1, bands.size,
                far.values.average(), far.values.sorted().let { it[it.size / 2] }, 100.0 * far.values.count { it > 50.0 } / far.size.coerceAtLeast(1),
                100.0 * strayed.size / from.size.coerceAtLeast(1),
                100.0 * withRites.size / bands.size.coerceAtLeast(1),
                withRites.map { b -> b.band!!.rite.count { it >= 0 }.toDouble() }.average().takeIf { !it.isNaN() } ?: 0.0,
                bands.map { it.band!!.riteStrength.toDouble() }.average()))
            if (riteWeeks > 0) sb.append("; in a rite's month: %.0f km from its site on average, within a hex of it %.0f%% of the weeks"
                .format(riteKm / riteWeeks, 100.0 * riteAt / riteWeeks))
            val sites = withRites.flatMap { gg -> gg.band!!.rite.filter { it >= 0 }.map { hex.distanceKm(hw.placeHex[gg.place], hw.placeHex[it]) } }
            if (sites.isNotEmpty()) sb.append("; sites lie %.0f km from their bands (median %.0f, furthest %.0f)".format(sites.average(),
                sites.sorted()[sites.size / 2], sites.max()))
        }
        sb.append("\n  people's score %.4f".format(score.value().getValue(Catalogue.HUMAN)))
        sb.toString()
    }.toList()
    lines.forEach(::println)
}
