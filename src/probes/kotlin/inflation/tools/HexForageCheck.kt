package inflation.tools

import inflation.Args
import inflation.Config

import inflation.ecology.Catalogue
import inflation.learning.HexLearning
import inflation.world.Date
import inflation.world.WorldConfig
import inflation.world.LifeForm

/**
 * Where the forage goes: year by year, the land's mean standing herbs, shrubs and trees (kg/m²), the
 * browse within reach, soil fertility, and what the animals took in the year by grazing and browsing
 * (kg/m², from the ecology's weekly rates), so a fall in forage can be put down to the eating or to
 * the plants themselves. people=false leaves people out.
 *
 * Usage: hex-forage-check [--seed N] [--years N]
 */
fun main(args: Array<String>) {
    val a = Args(args, "hex-forage-check [--seed N] [--years N]")
    val cfg = WorldConfig().let { it.copy(seed = a.long("--seed", it.seed)) }; val years = a.int("--years", 6)
    a.done()
    val run = HexLearning.Run(cfg, Catalogue.learned.filter { !it.human || Config.flag("people", true) })
    val land = run.groups.land; val n = land.plants.herb.size
    val area = land.areaKm2; val total = area.sum().toDouble()
    fun mean(f: (Int) -> Float) = (0 until n).sumOf { f(it).toDouble() * area[it] } / total
    val perYear = Date.DAYS_PER_YEAR / cfg.daysPerStep
    println("year   herb  shrub   tree  browse  fertility | grazed  browsed (kg/m² in the year) | burned share of land | moisture, shrub ceiling")
    println("start: moisture %.3f, shrub ceiling %.3f, shrubs %.3f".format(mean { land.plants.moisture[it] }, mean { land.plants.ceiling(LifeForm.SHRUB, it) },
        mean { land.plants.shrub[it] }))
    repeat(years) { y ->
        var grazed = 0.0; var browsed = 0.0; var burned = 0.0
        repeat(perYear) {
            run.step()
            burned += land.fire.fires.sumOf { it.second.toDouble() } / total
            grazed += mean { land.plants.grazing[it] } * cfg.daysPerStep / 1e6
            browsed += mean { land.plants.browsing[it] } * cfg.daysPerStep / 1e6
        }
        println("%4d %6.3f %6.3f %6.2f %7.4f %9.3f | %6.4f  %6.4f | %.3f | %.3f %.3f".format(y + 1, mean { land.plants.herb[it] },
            mean { land.plants.shrub[it] },
            mean { land.plants.tree[it] }, mean { land.plants.browse(it) }, mean { land.plants.fertility[it] }, grazed, browsed, burned,
            mean { land.plants.moisture[it] },
            mean { land.plants.ceiling(LifeForm.SHRUB, it) }))
    }
}
