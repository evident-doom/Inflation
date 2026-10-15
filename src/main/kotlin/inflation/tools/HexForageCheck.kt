package inflation.tools

import inflation.ecology.Catalogue
import inflation.learning.HexLearning
import inflation.world.Date
import inflation.world.WorldConfig

/**
 * Where the forage goes: year by year, the land's mean standing herbs, shrubs and trees (kg/m²), the
 * browse within reach, soil fertility, and what the animals took in the year by grazing and browsing
 * (kg/m², from the ecology's weekly rates), so a fall in forage can be put down to the eating or to
 * the plants themselves. -Dhex.noPeople leaves people out.
 *
 * Usage: hex-forage-check [--seed N] [--years N]
 */
fun main(args: Array<String>) {
    var cfg = WorldConfig(); var years = 6
    var k = 0
    while (k < args.size) { when (args[k]) { "--seed" -> cfg = cfg.copy(seed = args[++k].toLong()); "--years" -> years = args[++k].toInt() }; k++ }
    val run = HexLearning.Run(cfg, Catalogue.learned.filter { !it.human || System.getProperty("hex.noPeople") == null })
    val land = run.groups.land; val n = land.herb.size
    val area = land.areaKm2; val total = area.sum().toDouble()
    fun mean(f: (Int) -> Float) = (0 until n).sumOf { f(it).toDouble() * area[it] } / total
    val perYear = Date.DAYS_PER_YEAR / cfg.daysPerStep
    println("year   herb  shrub   tree  browse  fertility | grazed  browsed (kg/m² in the year) | burned share of land | moisture, shrub ceiling")
    println("start: moisture %.3f, shrub ceiling %.3f, shrubs %.3f".format(mean { land.moisture[it] }, mean { land.ceiling(inflation.world.LifeForm.SHRUB, it) }, mean { land.shrub[it] }))
    repeat(years) { y ->
        var grazed = 0.0; var browsed = 0.0; var burned = 0.0
        repeat(perYear) { run.step(); burned += land.fires.sumOf { it.second.toDouble() } / total; grazed += mean { land.grazing[it] } * cfg.daysPerStep / 1e6; browsed += mean { land.browsing[it] } * cfg.daysPerStep / 1e6 }
        println("%4d %6.3f %6.3f %6.2f %7.4f %9.3f | %6.4f  %6.4f | %.3f | %.3f %.3f".format(y + 1, mean { land.herb[it] }, mean { land.shrub[it] }, mean { land.tree[it] }, mean { land.browse(it) }, mean { land.fertility[it] }, grazed, browsed, burned, mean { land.moisture[it] }, mean { land.ceiling(inflation.world.LifeForm.SHRUB, it) }))
    }
}
