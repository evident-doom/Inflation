package inflation.tools

import inflation.ecology.FaunaKind
import inflation.ecology.Place
import inflation.Args
import inflation.ecology.HexSim
import inflation.ecology.SeasonalMemory
import inflation.world.Biome
import inflation.world.WorldCache
import inflation.world.WorldConfig
import kotlin.math.abs
import kotlin.math.max

/**
 * What the plant-eaters' seasonal memory holds after [--weeks] and how strongly it draws ([inflation.ecology.SeasonalMemory]):
 * for each kind, the remembered weekly death share on its three most-lived kinds of ground by month, and the largest and
 * median difference in the draw between neighbouring places (as a share of a place's worth).
 *
 * Usage: season-draw [--seed N] [--width KM] [--height KM] [--weeks N]
 */
fun main(args: Array<String>) {
    val a = Args(args, "season-draw [--seed N] [--width KM] [--height KM] [--weeks N]")
    var cfg = WorldConfig(seed = 1)
    cfg = cfg.copy(seed = a.long("--seed", cfg.seed), widthKm = a.int("--width", cfg.widthKm), heightKm = a.int("--height", cfg.heightKm))
    val weeks = a.int("--weeks", 156)
    a.done()
    val sim = HexSim(WorldCache.load(cfg) { })
    repeat(weeks) { sim.step() }
    val f = sim.fauna; val n = f.n
    val m = sim.weather.date.month; val next = (m + 1) % 12
    println("at ${sim.weather.date}; draw for month ${next + 1}")
    for (k in f.kinds.indices) {
        if (f.room[k] == null) continue
        val t = f.seasons.typeOf(k); val fare = f.seasonFare[k]
        val heads = DoubleArray(SeasonalMemory.TYPES)
        for (p in 0 until n) heads[t[p]] += ((f.adults[k][p] + f.young[k][p]) * sim.land.areaKm2[p]).toDouble()
        val top = heads.indices.sortedByDescending { heads[it] }.take(3)
        println(f.kinds[k].name)
        for (i in top) println("  %-24s %-6s %s".format(Biome.entries[i / 3].label, listOf("low", "mid", "high")[i % 3],
            (0 until 12).joinToString(" ") { mo -> fare[i * 12 + mo].let { if (f.seasonSeen[k][i * 12 + mo] <= 0f) "   -  " else "%6.3f".format(100 * it) } }))
        val diffs = ArrayList<Float>()
        for (p in 0 until n) { if (f.adults[k][p] + f.young[k][p] <= 0f) continue
            val v = f.routeGainAt(FaunaKind(k), Place(p)); val h = sim.hw.placeHex[p]
            for (d in 0 until 6) { val nh = sim.hex.neighbour(h, d); if (nh < 0) continue; val q = sim.hw.placeNear(p, nh); if (q >= 0) diffs += abs(v - f.routeGainAt(FaunaKind(k), Place(q))) } }
        diffs.sort()
        if (diffs.isNotEmpty()) println("  draw gap to neighbours (log): median %.5f, 90%% %.5f, max %.5f".format(diffs[diffs.size / 2], diffs[(diffs.size * 9) / 10], diffs.last()))
    }
}
