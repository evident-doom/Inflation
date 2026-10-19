package inflation.tools

import inflation.Args
import inflation.ecology.HexFauna
import inflation.ecology.HexSim
import inflation.ecology.HexSnapshot
import inflation.learning.LearnedModel
import inflation.run.TestWorlds
import inflation.world.WorldCache
import inflation.world.WorldConfig

/**
 * How crowded the residents of each kind live, month by month: the share of their heads at places as crowded as
 * [HexFauna.CROWDED] times their usual numbers and beyond (crowd 1 is that, 2 twice it), and the weekly share of them
 * disease takes where they are ([HexFauna.stayOdds]), heads-weighted. Read the kinds' crowding against the deaths it
 * makes: a kind piling up at a few places shows as heads at crowd 1 and more.
 *
 * Usage: resident-crowding (--world NAME | --seed N [--width KM] [--height KM]) [--weeks N] [--kinds a,b]
 */
fun main(args: Array<String>) {
    val a = Args(args, "resident-crowding (--world NAME | --seed N [--width KM] [--height KM]) [--weeks N] [--kinds a,b]")
    val named = a.string("--world")?.let { TestWorlds.named(it) }
    var cfg = named?.config ?: WorldConfig(seed = 1)
    cfg = cfg.copy(seed = a.long("--seed", cfg.seed), widthKm = a.int("--width", cfg.widthKm), heightKm = a.int("--height", cfg.heightKm))
    val weeks = a.int("--weeks", 52)
    val names = a.string("--kinds")?.split(",")?.map { it.trim() }
    a.done()
    val sim = HexSim(WorldCache.load(cfg) { })
    named?.let { TestWorlds.check(it, sim); HexSnapshot.load(sim, it.state); println("carrying on ${it.state} at ${sim.weather.date}") }
    LearnedModel.fromConfig(sim.groups) { println(it) }
    val fauna = sim.fauna; val land = sim.land
    val kinds = fauna.kinds.indices.filter { k -> !fauna.kinds[k].carnivore && (names == null || fauna.kinds[k].name in names) }

    // Crowd bins (of CROWDED times usual): under a quarter, a quarter to a half, a half to 1, 1 to 2, 2 and more.
    val edges = floatArrayOf(0.25f, 0.5f, 1f, 2f)
    val cols = listOf("<0.25", "0.25-0.5", "0.5-1", "1-2", ">=2", "disease")
    // [kind][month][column]: heads in each bin, and heads times the week's disease share; [kind][month]: heads.
    val sums = Array(kinds.size) { Array(12) { DoubleArray(cols.size) } }
    val heads = Array(kinds.size) { DoubleArray(12) }
    val out = FloatArray(HexFauna.HZ_COUNT)

    for (w in 0 until weeks) {
        sim.step()
        val m = sim.weather.date.month
        for ((i, k) in kinds.withIndex()) {
            val ad = fauna.adults[k]; val yo = fauna.young[k]
            for (p in 0 until land.n) {
                val h = (ad[p] + yo[p]) * land.areaKm2[p].toDouble()
                if (h <= 0.0) continue
                if (fauna.stayOdds(k, p, sim.weather, out).isNaN()) continue
                val crowd = out[HexFauna.HZ_CROWD]
                var bin = 0; while (bin < edges.size && crowd >= edges[bin]) bin++
                sums[i][m][bin] += h; sums[i][m][cols.size - 1] += h * out[HexFauna.HZ_DISEASE]
                heads[i][m] += h
            }
        }
    }
    for ((i, k) in kinds.withIndex()) {
        println("${fauna.kinds[k].name}: share of heads by crowding (of ${HexFauna.CROWDED} times usual), and disease a week, by month")
        for ((c, name) in cols.withIndex()) println("  %-9s %s".format(name,
            (0 until 12).joinToString(" ") { m -> if (heads[i][m] > 0) (if (c == cols.size - 1) "%8.5f" else "%8.3f").format(sums[i][m][c] / heads[i][m]) else "       -" }))
    }
}
