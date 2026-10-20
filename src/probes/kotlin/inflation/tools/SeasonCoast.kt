package inflation.tools

import inflation.ecology.Hex
import inflation.ecology.FaunaKind
import inflation.ecology.Place
import inflation.Args
import inflation.ecology.HexSim
import inflation.ecology.SeasonalMemory
import inflation.world.Biome
import inflation.world.WorldCache
import inflation.world.WorldConfig

/**
 * Why the seasonal memory pushes away from coasts and lakes: for the given kinds, the mean draw value and the share of
 * each kind of ground on hexes next to water (a neighbour without land places, or a lake biome) against the rest, and
 * each kind of ground's month of log survival as remembered.
 *
 * Also, as the flow sees it: between neighbouring hexes where the kind is, the gap in what the memory draws by (log) and
 * in the whole worth it flows by (relative), median and 90th percentile.
 *
 * Usage: season-coast [--seed N] [--width KM] [--height KM] [--weeks N] [--kinds a,b] [--load FILE]
 */
fun main(args: Array<String>) {
    val a = Args(args, "season-coast [--seed N] [--width KM] [--height KM] [--weeks N] [--kinds a,b] [--load FILE]")
    var cfg = WorldConfig(seed = 1)
    cfg = cfg.copy(seed = a.long("--seed", cfg.seed), widthKm = a.int("--width", cfg.widthKm), heightKm = a.int("--height", cfg.heightKm))
    val weeks = a.int("--weeks", 104)
    val names = a.string("--kinds")?.split(",")?.map { it.trim().replace('_', ' ') } ?: listOf("wild goat", "wild horse")
    val load = a.string("--load")
    a.done()
    val sim = HexSim(WorldCache.load(cfg) { })
    load?.let { inflation.ecology.HexSnapshot.load(sim, java.io.File(it)); println("carrying on $it at ${sim.weather.date}") }
    repeat(weeks) { sim.step() }
    val f = sim.fauna; val hw = sim.hw; val hex = sim.hex; val n = f.n
    val next = (sim.weather.date.month + 1) % 12
    val land = BooleanArray(hex.size); for (p in 0 until n) land[hw.placeHex[p]] = true
    val byWater = BooleanArray(n) { p -> val h = hw.placeHex[p]; (0 until 6).any { d -> val q = hex.neighbour(h, d); q >= 0 && !land[q] } }
    println("at ${sim.weather.date}; values for month ${next + 1}; places next to water ${byWater.count { it }} of $n")
    for (k in f.kinds.indices) {
        if (f.kinds[k].name !in names) continue
        val t = f.seasons.typeOf(k); val stay = f.seasons.stayOf(k, next, f.seasonFare[k], f.seasonSeen[k])
        var vw = 0.0; var cw = 0; var vi = 0.0; var ci = 0
        val tw = DoubleArray(SeasonalMemory.TYPES); val ti = DoubleArray(SeasonalMemory.TYPES)
        for (p in 0 until n) { val v = f.routeGainAt(FaunaKind(k), Place(p))
            if (byWater[p]) { vw += v; cw++; tw[t[p]]++ } else { vi += v; ci++; ti[t[p]]++ } }
        val dg = ArrayList<Float>(); val wg = ArrayList<Float>()
        for (p in 0 until n) {
            if (f.adults[k][p] + f.young[k][p] <= 0f) continue
            val h = hw.placeHex[p]
            for (d in 0 until 6) { val nh = hex.neighbour(h, d); if (nh < 0) continue; val q = hw.placeNear(Place(p), Hex(nh)); if (q.none) continue
                dg += kotlin.math.abs(f.routeGainAt(FaunaKind(k), Place(p)) - f.routeGainAt(FaunaKind(k), Place(q.i)))
                val w0 = f.worth[k][p]; val w1 = f.worth[k][q.i]; if (w0 > 0f && w1 > 0f) wg += kotlin.math.abs(w1 - w0) / kotlin.math.max(w0, w1) }
        }
        dg.sort(); wg.sort()
        fun pc(l: List<Float>, x: Double) = if (l.isEmpty()) Float.NaN else l[(l.size * x).toInt().coerceAtMost(l.size - 1)]
        println(f.kinds[k].name + ": neighbour gap, memory draw median %.5f p90 %.5f; whole worth median %.4f p90 %.4f".format(pc(dg, 0.5), pc(dg, 0.9), pc(wg, 0.5), pc(wg, 0.9)))
        println(f.kinds[k].name + ": mean value next to water %.5f, inland %.5f".format(vw / cw, vi / ci))
        val top = (0 until SeasonalMemory.TYPES).sortedByDescending { tw[it] / cw + ti[it] / ci }.take(10)
        for (i in top) println("  %-24s %-4s next to water %5.1f%%  inland %5.1f%%  month's log survival %.5f  fare %s seen %.0f".format(
            Biome.entries[i / 3].label, listOf("low", "mid", "high")[i % 3], 100 * tw[i] / cw, 100 * ti[i] / ci, stay[i],
            f.seasonFare[k][i * 12 + next].let { if (f.seasonSeen[k][i * 12 + next] <= 0f) "-" else "%.4f%%".format(100 * it) }, f.seasonSeen[k][i * 12 + next]))
    }
}
