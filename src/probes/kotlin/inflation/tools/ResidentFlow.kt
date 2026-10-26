package inflation.tools

import inflation.world.set
import inflation.world.get
import inflation.Args
import inflation.ecology.HexSim
import inflation.ecology.HexSnapshot
import inflation.learning.LearnedModel
import inflation.run.TestWorlds
import inflation.world.WorldCache
import inflation.world.WorldConfig
import kotlin.math.abs

/**
 * How much the plant-eating residents flow over the land, month by month: for each kind, the heads-weighted mean height
 * of its heads (m), the share of its heads above 1000 m, and the share of its heads that changed hex in a week (half the
 * summed change of heads in each hex against the week before, over all heads: births and deaths count too, so read it
 * against a week without moving).
 *
 * Usage: resident-flow (--world NAME | --seed N [--width KM] [--height KM]) [--weeks N] [--kinds a,b]
 */
fun main(args: Array<String>) {
    val a = Args(args, "resident-flow (--world NAME | --seed N [--width KM] [--height KM]) [--weeks N] [--kinds a,b]")
    val named = a.string("--world")?.let { TestWorlds.named(it) }
    var cfg = named?.config ?: WorldConfig(seed = 1)
    cfg = cfg.copy(seed = a.long("--seed", cfg.seed), widthKm = a.int("--width", cfg.widthKm), heightKm = a.int("--height", cfg.heightKm))
    val weeks = a.int("--weeks", 52)
    // (Names with a space are given with an underscore: wild_boar.)
    val names = a.string("--kinds")?.split(",")?.map { it.trim().replace('_', ' ') }
    a.done()
    val sim = HexSim(WorldCache.load(cfg) { })
    named?.let { TestWorlds.check(it, sim); HexSnapshot.load(sim, it.state); println("carrying on ${it.state} at ${sim.weather.date}") }
    LearnedModel.fromConfig(sim.groups) { println(it) }
    val fauna = sim.fauna; val land = sim.land; val hw = sim.hw; val n = land.n; val hexes = sim.hex.size
    val kinds = fauna.kinds.indices.filter { k -> fauna.room[k] != null && (names == null || fauna.kinds[k].name in names) }

    // [kind][month]: heads, heads times height, heads above 1000 m, heads changing hex; weeks in each month.
    val heads = Array(kinds.size) { DoubleArray(12) }; val height = Array(kinds.size) { DoubleArray(12) }
    val high = Array(kinds.size) { DoubleArray(12) }; val moved = Array(kinds.size) { DoubleArray(12) }
    val before = Array(kinds.size) { DoubleArray(hexes) }
    fun byHex(k: Int, into: DoubleArray) { into.fill(0.0); for (p in 0 until n) into[hw.placeHex[p]] += ((fauna.adults[k][p] + fauna.young[k][p]) * land.areaKm2[p]).toDouble() }
    for ((i, k) in kinds.withIndex()) byHex(k, before[i])
    val now = DoubleArray(hexes)
    for (w in 0 until weeks) {
        sim.step()
        val m = sim.weather.date.month
        for ((i, k) in kinds.withIndex()) {
            var total = 0.0
            for (p in 0 until n) {
                val h = ((fauna.adults[k][p] + fauna.young[k][p]) * land.areaKm2[p]).toDouble()
                if (h <= 0.0) continue
                total += h; height[i][m] += h * hw.placeElevationM[p]
                if (hw.placeElevationM[p] >= 1000f) high[i][m] += h
            }
            heads[i][m] += total
            byHex(k, now)
            var change = 0.0; for (h in 0 until hexes) change += abs(now[h] - before[i][h])
            moved[i][m] += 0.5 * change
            System.arraycopy(now, 0, before[i], 0, hexes)
        }
    }
    println("month:                          " + (0 until 12).joinToString(" ") { "%7d".format(it + 1) })
    for ((i, k) in kinds.withIndex()) {
        fun row(label: String, f: (Int) -> String) = println("  %-12s %-17s %s".format(fauna.kinds[k].name, label, (0 until 12).joinToString(" ") { m -> if (heads[i][m] > 0) f(m) else "      -" }))
        row("mean height m") { m -> "%7.0f".format(height[i][m] / heads[i][m]) }
        row("above 1000 m") { m -> "%7.3f".format(high[i][m] / heads[i][m]) }
        row("changed hex/wk") { m -> "%7.4f".format(moved[i][m] / heads[i][m]) }
    }
}
