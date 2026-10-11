package inflation.ecology

import inflation.world.Simulation
import inflation.world.World
import inflation.world.WorldConfig

/**
 * Runs the living world for some years and prints the animal populations each season.
 *
 * Usage: fauna [--seed N] [--size KM] [--years N]
 */
fun main(args: Array<String>) {
    var cfg = WorldConfig()
    var years = 10
    var k = 0
    while (k < args.size) {
        when (args[k]) {
            "--seed" -> cfg = cfg.copy(seed = args[++k].toLong())
            "--size" -> { val s = args[++k].toInt(); cfg = cfg.copy(widthKm = s, heightKm = s) }
            "--years" -> years = args[++k].toInt()
            else -> error("unknown argument ${args[k]}")
        }
        k++
    }
    val world = World.generate(cfg)
    val sim = Simulation(world)
    val e = sim.ecology
    val land = (0 until world.grid.size).count { sim.vegetation.isVegetated(it) } * world.grid.cellKm * world.grid.cellKm
    println("land %.0f km²".format(land))
    println("date (cond = mean gazelle condition, fed = mean share of food need met)")
    println("date                          gazelle  herds   wolf packs  kills/season  rodents kg/km²  insects kg/km²  plankton g/m²  litter kg/m²  carrion t  cond  fed  wolfcond herb")
    val stepsPerSeason = 90 / cfg.daysPerStep
    var kills = 0
    val t0 = System.nanoTime()
    repeat(years * 4) {
        repeat(stepsPerSeason) { sim.step(); kills += e.kills }
        val veg = (0 until world.grid.size).filter { sim.vegetation.isVegetated(it) }
        val water = (0 until world.grid.size).filter { e.plankton.isWater[it] }
        println("%-28s %8d %6d %6d %5d %13d %15.0f %15.0f %14.2f %13.2f %10.1f %5.2f %4.2f %5.2f %5.3f".format(
            sim.date.toString(), e.count(Catalogue.GAZELLE), e.groupCount(Catalogue.GAZELLE),
            e.count(Catalogue.WOLF), e.groupCount(Catalogue.WOLF), kills,
            veg.sumOf { e.small.rodents[it].toDouble() } / veg.size, veg.sumOf { e.small.insects[it].toDouble() } / veg.size,
            water.sumOf { e.plankton.phyto[it].toDouble() } / water.size, veg.sumOf { e.soil.litter[it].toDouble() } / veg.size,
            veg.sumOf { e.soil.carrion[it].toDouble() } * world.grid.cellAreaM2 / 1000,
            mean(e, Catalogue.GAZELLE) { it.condition }, mean(e, Catalogue.GAZELLE) { it.lastIntakeRatio }, mean(e, Catalogue.WOLF) { it.condition }, veg.sumOf { sim.vegetation.herb[it].toDouble() } / veg.size))
        kills = 0
    }
    println("%.0f ms per step".format((System.nanoTime() - t0) / 1e6 / (years * 4 * stepsPerSeason)))
    sim.log.filter { "gazelles" !in it }.takeLast(10).forEach(::println)
}

private fun mean(e: Ecology, s: Species, f: (AnimalGroup) -> Float): Float {
    val gs = e.groups.filter { it.species == s }
    return if (gs.isEmpty()) 0f else gs.sumOf { f(it).toDouble() * it.size }.toFloat() / gs.sumOf { it.size }
}
