package inflation.tools

import inflation.learning.RunScore
import inflation.run.freshSim
import inflation.Args
import inflation.ecology.Catalogue
import inflation.ecology.ROUNDS
import inflation.ecology.TRADED
import inflation.ecology.Tool
import inflation.ecology.YEAR_NEED
import inflation.ecology.KEEP_YEARS
import inflation.world.Date
import inflation.world.WorldConfig
import inflation.ecology.HexGroup
import inflation.ecology.Store
import java.util.TreeMap

/**
 * What trade does (with rounds on for the rites at the sources): where good stone, salt
 * and ochre are got (sources), and each year the share of bands holding a year's worth of each, how
 * much of their spears and clothes is made well, how much of their dried food is salted, the useful
 * gifts given, how many rites stand at a source, the share of band-weeks spent in country of another
 * kind than the band's own at the year's start, and people's score (as hex-rite-check reckons it).
 *
 * Usage: hex-trade-check [--seeds A,B] [--size KM] [--years N]
 */
fun main(args: Array<String>) {
    val a = Args(args, "hex-trade-check [--seeds A,B] [--size KM] [--years N]")
    val seeds = a.string("--seeds")?.split(",")?.map { it.toLong() } ?: listOf(91L); val size = a.int("--size", 600); val years = a.int("--years", 4)
    a.done()
    println("rounds ${if (ROUNDS) "on" else "off"}, $size km, $years years")
    val lines = seeds.parallelStream().map { seed ->
        val run = freshSim(WorldConfig(seed = seed, widthKm = size, heightKm = size), Catalogue.learned)
        val hw = run.hw; val land = run.groups.land
        fun chief(h: Int): Int { var best = 0; for (b in hw.biome.indices) if (hw.biome[b][h] > hw.biome[best][h]) best = b; return best }
        val stepsPerYear = Date.DAYS_PER_YEAR / WorldConfig().daysPerStep
        val score = RunScore(run, listOf(Catalogue.HUMAN))
        val sb = StringBuilder("seed $seed: places with good stone %d, salt %d, ochre %d of %d".format(
            land.stone.count { it > 0.1f }, land.salt.count { it > 0.1f }, land.ochre.count { it > 0.1f }, land.stone.size))
        sb.append("; sources (one in 50 km at most): " + TRADED.joinToString(", ") { "${it.name.lowercase()} ${run.groups.materialSources.getValue(it).size}" })
        repeat(years) { y ->
            val home = HashMap<Int, Int>(); var bandWeeks = 0; var away = 0
            val given = TreeMap<String, Int>()
            for (g in run.groups.groups) if (g.alive && g.band != null) home[g.id] = chief(hw.placeHex[g.place])
            repeat(stepsPerYear) {
                run.step(); score.afterStep(run)
                for ((key, n) in run.groups.stats.meetings) if (key.endsWith(" given") || key.contains("rite")) given.merge(key, n, Int::plus)
                for (g in run.groups.groups) if (g.species.human) {
                    if (!g.alive) continue
                    val hm = home[g.id] ?: continue
                    bandWeeks++; if (chief(hw.placeHex[g.place]) != hm) away++
                }
            }
            val bands = run.groups.groups.filter { it.alive && it.band != null }
            fun share(f: (HexGroup) -> Boolean) = 100.0 * bands.count(f) / bands.size.coerceAtLeast(1)
            sb.append("\n  year %d: %d bands, %d people; a year's worth held: %s; spears %.0f%% and clothes %.0f%% made well, dried food %.0f%% salted; %.0f%% of band-weeks in other country"
                .format(y + 1, bands.size, bands.sumOf { it.size },
                    TRADED.joinToString(", ") { m -> "%s %.0f%%".format(m.name.lowercase(),
                        share { g -> g.band!!.goods[m.ordinal] >= KEEP_YEARS * YEAR_NEED[m.ordinal] * g.adults }) },
                    100.0 * bands.map { it.band!!.fine[Tool.SPEAR.ordinal].toDouble() }.average(),
                    100.0 * bands.map { it.band!!.fine[Tool.CLOTHES.ordinal].toDouble() }.average(),
                    100.0 * bands.sumOf { it.band!!.salted.toDouble() * it.stores[Store.DRIED.ordinal] } /
                        bands.sumOf { it.stores[Store.DRIED.ordinal].toDouble() }.coerceAtLeast(1e-9),
                    100.0 * away / bandWeeks.coerceAtLeast(1)))
            val sources = TRADED.flatMap { run.groups.materialSources.getValue(it).toList() }.toHashSet()
            val atSources = bands.sumOf { g -> g.band!!.rite.count { it >= 0 && hw.placeHex[it] in sources } }
            sb.append("; rites at a source %d of %d".format(atSources, bands.sumOf { g -> g.band!!.rite.count { it >= 0 } }))
            if (given.isNotEmpty()) sb.append("; " + given.entries.joinToString(", ") { "${it.key} ${it.value}" })
        }
        sb.append("\n  people's score %.4f".format(score.value().getValue(Catalogue.HUMAN)))
        sb.toString()
    }.toList()
    lines.forEach(::println)
}
