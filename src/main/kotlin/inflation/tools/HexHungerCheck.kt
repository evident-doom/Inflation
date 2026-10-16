package inflation.tools

import inflation.ecology.Catalogue
import inflation.learning.HexLearning
import inflation.world.Biome
import inflation.world.Date
import inflation.world.WorldConfig

/**
 * When and where herds go hungry: for each herd kind, month by month over the run (after the first
 * year), the share of its animals in groups fed under half, the mean fed, the mean snow over them
 * and the share standing where their kind's food is under a fifth of what it wants; and the kinds
 * of country the hungry stand in. Hand-written moves (people left out with -Dhex.noPeople).
 *
 * Usage: hex-hunger-check [--seed N] [--years N] [--kinds a,b]
 */
fun main(args: Array<String>) {
    var cfg = WorldConfig(); var years = 4; var only: Set<String>? = null
    var k = 0
    while (k < args.size) {
        when (args[k]) {
            "--seed" -> cfg = cfg.copy(seed = args[++k].toLong())
            "--years" -> years = args[++k].toInt()
            "--kinds" -> only = args[++k].split(",").toSet()
        }
        k++
    }
    val run = HexLearning.Run(cfg, Catalogue.learned.filter { !it.human || System.getProperty("hex.noPeople") == null })
    val herds = Catalogue.learned.filter { it in Catalogue.migratory && (only == null || it.name in only!!) }
    val perYear = Date.DAYS_PER_YEAR / cfg.daysPerStep
    val heads = Array(herds.size) { DoubleArray(12) }; val hungry = Array(herds.size) { DoubleArray(12) }; val fed = Array(herds.size) { DoubleArray(12) }
    val snow = Array(herds.size) { DoubleArray(12) }
    val hungryIn = Array(herds.size) { HashMap<String, Double>() }
    repeat(years * perYear) { w ->
        run.step()
        if (w < perYear) return@repeat
        val m = run.weather.date.month
        for ((i, s) in herds.withIndex()) for (g in run.groups.groups) {
            if (!g.alive || g.species != s) continue
            val n = g.size.toDouble()
            heads[i][m] += n; fed[i][m] += g.fed * n; snow[i][m] += run.weather.snowpack[g.place] * n
            if (g.fed < 0.5f) {
                hungry[i][m] += n
                val h = run.hw.placeHex[g.place]; var best = 0; for (b in run.hw.biome.indices) if (run.hw.biome[b][h] > run.hw.biome[best][h]) best = b
                hungryIn[i].merge(Biome.entries[best].name.lowercase().replace('_', ' '), n, Double::plus)
            }
        }
    }
    for ((i, s) in herds.withIndex()) {
        println("%-11s %d animals at the end".format(s.name, run.groups.count(s)))
        println("   hungry (fed < 0.5): " + (0 until 12).joinToString(" ") { m -> "%4.0f%%".format(100.0 * hungry[i][m] / heads[i][m].coerceAtLeast(1.0)) })
        println("   mean fed:           " + (0 until 12).joinToString(" ") { m -> "%5.2f".format(fed[i][m] / heads[i][m].coerceAtLeast(1.0)) })
        println("   snow over them, mm: " + (0 until 12).joinToString(" ") { m -> "%5.0f".format(snow[i][m] / heads[i][m].coerceAtLeast(1.0)) })
        val all = hungryIn[i].values.sum()
        println("   the hungry stand in: " + hungryIn[i].entries.sortedByDescending { it.value }.take(6).joinToString(", ") { "%s %.0f%%".format(it.key, 100.0 * it.value / all) })
    }
}
