package inflation.tools

import inflation.Args
import inflation.ecology.HexSim
import inflation.world.Date
import inflation.world.WorldConfig
import inflation.world.WorldCache

/**
 * Whether drinking water binds: month by month over the last year, how much of the land the viewer
 * shows watered (the mean share within an easy walk of water), how much has pools everywhere, how much
 * counts as dry (under 5% within an easy walk) and how far that lies from water; and for each herd kind,
 * the share of its heads going without water and the share thirsty (dry for over half the days it can go).
 *
 * Usage: hex-thirst-check [--seed N] [--width KM] [--height KM] [--years N]
 */
fun main(args: Array<String>) {
    val a = Args(args, "hex-thirst-check [--seed N] [--width KM] [--height KM] [--years N]")
    var cfg = WorldConfig(seed = 1)
    cfg = cfg.copy(seed = a.long("--seed", cfg.seed), widthKm = a.int("--width", cfg.widthKm), heightKm = a.int("--height", cfg.heightKm))
    val years = a.int("--years", 2)
    a.done()
    val sim = HexSim(WorldCache.load(cfg) { })
    val land = sim.land; val herds = sim.groups
    val stepsY = Date.DAYS_PER_YEAR / cfg.daysPerStep
    val area = DoubleArray(12); val dry = DoubleArray(12); val far15 = DoubleArray(12); val far40 = DoubleArray(12); val far100 = DoubleArray(12)
    val pools = DoubleArray(12); val tint = DoubleArray(12)
    val grazers = herds.kinds.filter { !it.carnivore }
    val heads = Array(grazers.size) { DoubleArray(12) }; val drying = Array(grazers.size) { DoubleArray(12) }
    val thirsty = Array(grazers.size) { DoubleArray(12) }
    for (y in 0 until years) repeat(stepsY) {
        sim.step()
        if (y < years - 1) return@repeat
        val m = sim.weather.lastMonth
        for (p in 0 until land.n) { val a = land.areaKm2[p].toDouble(); area[m] += a; tint[m] += a * land.drink.water[p]
            if (land.drink.water[p] >= 0.999f && land.drink.waterKm[p] == 0f) pools[m] += a
            if (land.drink.water[p] < 0.05f) {
                dry[m] += a
                if (land.drink.waterKm[p] > 15f) far15[m] += a
                if (land.drink.waterKm[p] > 40f) far40[m] += a
                if (land.drink.waterKm[p] > 100f) far100[m] += a
            } }
        for (g in herds.groups) { if (!g.alive) continue; val i = grazers.indexOf(g.species); if (i < 0) continue
            heads[i][m] += g.size.toDouble()
            if (g.dry > 0f) drying[i][m] += g.size.toDouble()
            if (g.dry > 0.5f * g.species.daysWithoutWater) thirsty[i][m] += g.size.toDouble() }
    }
    fun row(label: String, f: (Int) -> Double) = println("%-36s".format(label) + (0 until 12).joinToString(" ") { "%5.2f".format(f(it)) })
    println("%-36s".format("month") + (0 until 12).joinToString(" ") { "%5d".format(it) })
    row("land shown blue (mean water share)") { tint[it] / area[it] }
    row("land with pools everywhere") { pools[it] / area[it] }
    row("land counted dry (water < 0.05)") { dry[it] / area[it] }
    row("  of it > 15 km from water") { far15[it] / area[it] }
    row("  of it > 40 km from water") { far40[it] / area[it] }
    row("  of it > 100 km from water") { far100[it] / area[it] }
    for ((i, s) in grazers.withIndex()) { if (heads[i].sum() <= 0) continue
        row("${s.name}: share going without water") { drying[i][it] / heads[i][it].coerceAtLeast(1e-9) }
        row("${s.name}: share thirsty") { thirsty[i][it] / heads[i][it].coerceAtLeast(1e-9) } }
}
