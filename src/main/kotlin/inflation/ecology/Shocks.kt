package inflation.ecology

import inflation.world.ClimateEvent
import inflation.world.Date
import inflation.world.Simulation
import inflation.world.World
import inflation.world.WorldConfig
import java.io.File

/**
 * Knocks the living world off balance and records how it recovers, as people will once they
 * arrive: they clear forest for fields, wear out the soil, hunt game out, kill off predators, and
 * set or suffer great fires. Runs [years] years, applies the shock at [at], and writes a table of
 * yearly totals (animals per species, plant biomass, area burned) to compare with a run without one.
 *
 * Shocks: none, clear (forest cleared on the best lowland), deplete (that land's soil worn out),
 * overhunt (nine in ten large herbivores killed), cull (nineteen in twenty predators killed),
 * drought (three dry, hot years over the middle of the map), people (all of these but the drought).
 *
 * Usage: shocks --shock NAME [--seed N] [--size KM] [--height KM] [--years N] [--at YEAR] --out FILE
 */
fun main(args: Array<String>) {
    var cfg = WorldConfig()
    var years = 40
    var at = 10
    var shock = "none"
    var out = File("output/shock.tsv")
    var k = 0
    while (k < args.size) {
        when (args[k]) {
            "--seed" -> cfg = cfg.copy(seed = args[++k].toLong())
            "--size" -> { val s = args[++k].toInt(); cfg = cfg.copy(widthKm = s, heightKm = s) }
            "--width" -> cfg = cfg.copy(widthKm = args[++k].toInt())
            "--height" -> cfg = cfg.copy(heightKm = args[++k].toInt())
            "--years" -> years = args[++k].toInt()
            "--at" -> at = args[++k].toInt()
            "--shock" -> shock = args[++k]
            "--out" -> out = File(args[++k])
            else -> error("unknown argument ${args[k]}")
        }
        k++
    }
    val world = World.generate(cfg)
    val sim = Simulation(world)
    val e = sim.ecology
    val v = sim.vegetation
    val grid = world.grid
    val stepsPerYear = Date.DAYS_PER_YEAR / cfg.daysPerStep
    val land = (0 until grid.size).filter { v.isVegetated(it) }

    // Farmland: the most fertile lowland woodland, a fifth of all wooded land.
    val wooded = land.filter { v.tree[it] > 2f && world.elevation[it] < 800f }
    val farmland = wooded.sortedByDescending { world.soilFertility[it] }.take(wooded.size / 5)

    // Each animal is killed by chance, so small groups are not wiped out whole by rounding.
    val rnd = kotlin.random.Random(cfg.seed * 7 + 11)
    fun kill(species: List<Species>, share: Float) {
        for (g in e.groups) if (g.species in species && g.alive) {
            g.adults -= (0 until g.adults).count { rnd.nextFloat() < share }
            g.young -= (0 until g.young).count { rnd.nextFloat() < share }
        }
    }

    fun apply(name: String) {
        when (name) {
            "none" -> {}
            "clear" -> for (i in farmland) { v.tree[i] = 0f; v.shrub[i] *= 0.1f }
            "deplete" -> for (i in farmland) { v.fertility[i] *= 0.5f; v.organicFertility[i] = 0f }
            "overhunt" -> kill(Catalogue.herbivores.filter { it.massKg >= 100f }, 0.9f)
            "cull" -> kill(Catalogue.predators, 0.95f)
            "drought" -> {
                val day = sim.date.absoluteDay
                val x = grid.width / 2.0; val y = grid.height / 2.0
                sim.weather.events += ClimateEvent(ClimateEvent.Kind.DROUGHT, day, day + 3L * Date.DAYS_PER_YEAR, x, y, 500.0, 0.4)
                sim.weather.events += ClimateEvent(ClimateEvent.Kind.HEAT_WAVE, day + 90, day + 3L * Date.DAYS_PER_YEAR, x, y, 500.0, 2.0)
            }
            "people" -> for (s in listOf("clear", "deplete", "overhunt", "cull")) apply(s)
            else -> error("unknown shock $name")
        }
    }

    out.parentFile?.mkdirs()
    out.printWriter().use { w ->
        w.println((listOf("year") + Catalogue.all.map { it.name } + SeaCatalogue.all.map { it.name } +
            listOf("trees_Mt", "shrubs_Mt", "herbs_Mt", "farmland_trees_Mt", "burned_km2", "fertility")).joinToString("\t"))
        for (year in 0 until years) {
            if (year == at) apply(shock)
            var burned = 0.0
            repeat(stepsPerYear) {
                sim.step()
                burned += v.fires.sumOf { it.second.toDouble() }
                e.killsBy.clear(); e.killsByPeople.clear(); e.deathsBy.clear(); e.marine.deathsBy.clear()
            }
            val km2 = grid.cellAreaM2 / 1e9 // kg/m² over a cell -> Mt
            val row = listOf(year.toString()) + Catalogue.all.map { e.count(it).toString() } + SeaCatalogue.all.map { e.marine.count(it).toString() } +
                listOf(
                    "%.1f".format(land.sumOf { v.tree[it].toDouble() } * km2),
                    "%.1f".format(land.sumOf { v.shrub[it].toDouble() } * km2),
                    "%.1f".format(land.sumOf { v.herb[it].toDouble() } * km2),
                    "%.2f".format(farmland.sumOf { v.tree[it].toDouble() } * km2),
                    "%.0f".format(burned),
                    "%.3f".format(farmland.sumOf { v.fertility[it].toDouble() } / farmland.size.coerceAtLeast(1)),
                )
            w.println(row.joinToString("\t"))
            w.flush()
            println("year $year done")
        }
    }
}
