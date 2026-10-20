package inflation.tools

import inflation.run.TestWorlds
import inflation.Args
import inflation.ecology.HexSim
import inflation.world.Date
import inflation.world.LifeForm
import inflation.world.WorldConfig

/**
 * Whether river plains have the richer soil and plants they should: soil organic matter, litter, plants,
 * the silt floods lay and how much of the ground is sodden, for places by their floodplain share, in dry and
 * in wet country, against places with no floodplain. Means are by area.
 *
 * Usage: runTool -Pmain=inflation.tools.FloodplainSoilKt -PtoolArgs="(--world NAME | --seed N [--width KM] [--height KM]) [--years N]"
 * With --world the settled state is loaded; --years steps on (default 1) to measure the silt laid and the sodden share over those years.
 */
fun main(args: Array<String>) {
    val a = Args(args, "floodplain-soil (--world NAME | --seed N [--width KM] [--height KM]) [--years N]")
    val named = a.string("--world")?.let { TestWorlds.named(it) }
    var cfg = named?.config ?: WorldConfig(seed = 1)
    cfg = cfg.copy(seed = a.long("--seed", cfg.seed), widthKm = a.int("--width", cfg.widthKm), heightKm = a.int("--height", cfg.heightKm))
    val years = a.int("--years", 1)
    a.done()
    val sim = HexSim(inflation.world.WorldCache.load(cfg) { })
    named?.let { TestWorlds.check(it, sim); inflation.ecology.HexSnapshot.load(sim, it.state); println("loaded ${it.state} at ${sim.weather.date}") }
    val land = sim.land; val small = sim.fauna.small; val n = land.n
    val laid = DoubleArray(n); val sodden = DoubleArray(n)
    val steps = years * Date.DAYS_PER_YEAR / cfg.daysPerStep
    val t0 = System.nanoTime()
    repeat(steps) {
        sim.step()
        for (p in 0 until n) { laid[p] += small.laidKgM2[p]; sodden[p] += land.hydro.saturated(inflation.ecology.Place(p)) }
    }
    println("stepped %d years at %.0f ms a step; state at %s".format(years, (System.nanoTime() - t0) / 1e6 / steps.coerceAtLeast(1), sim.weather.date))
    // Dry country: where the soil of the open ground is too dry for more than a thin scatter of trees.
    val dry = BooleanArray(n) { p -> land.plants.moisture[p] < LifeForm.TREE.moistureMin + 0.1f }
    val bins = listOf("no floodplain" to 0f..0f, "floodplain <10%" to 1e-6f..0.1f, "floodplain 10-30%" to 0.1f..0.3f, "floodplain >=30%" to 0.3f..1f)
    println("%-8s %-18s %6s %8s | %6s %6s %6s | %6s %6s %6s %6s | %8s %6s %6s".format("country", "places", "n", "km²",
        "herb", "shrub", "tree", "litter", "OM", "OM p10", "OM p90", "silt/yr", "sodden", "wetFl"))
    for ((country, isDry) in listOf("dry" to true, "wet" to false)) for ((name, r) in bins) {
        val ps = (0 until n).filter { land.plants.biomass(it) > 0f && dry[it] == isDry && land.floodShare[it] in r &&
            (r.endInclusive > 0f || land.floodShare[it] == 0f) }
        if (ps.isEmpty()) continue
        val area = ps.sumOf { land.areaKm2[it].toDouble() }
        fun mean(f: (Int) -> Double) = ps.sumOf { f(it) * land.areaKm2[it] } / area
        val om = ps.map { small.organicMatter[it] }.sorted()
        println("%-8s %-18s %6d %8.0f | %6.3f %6.2f %6.2f | %6.2f %6.2f %6.2f %6.2f | %8.4f %6.3f %6.3f".format(country, name, ps.size, area,
            mean { land.plants.herb[it].toDouble() }, mean { land.plants.shrub[it].toDouble() }, mean { land.plants.tree[it].toDouble() },
            mean { small.litter[it].toDouble() }, mean { small.organicMatter[it].toDouble() }, om[om.size / 10], om[om.size * 9 / 10],
            mean { laid[it] / years }, mean { sodden[it] / steps.coerceAtLeast(1) }, mean { land.hydro.wetFloor(inflation.ecology.Place(it)).toDouble() }))
    }
}
