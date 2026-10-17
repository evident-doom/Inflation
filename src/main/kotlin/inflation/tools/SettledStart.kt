package inflation.tools

import inflation.Args
import inflation.ecology.HexHistory
import inflation.ecology.HexSim
import inflation.world.Biome
import inflation.world.World
import inflation.world.WorldConfig
import java.io.File

/**
 * Turns a long run's history into the starting state of new worlds: for each kind, where it settled,
 * as animals per 1000 km² of each kind of country, averaged over the last years of the run. Writes
 * settled-start.properties (keys "kind" for the whole land and "kind@biome" for each kind of country).
 *
 * Usage: settled-start HISTORY.csv OUT.properties [--seed N] [--from-year Y]
 */
fun main(args: Array<String>) {
    val a = Args(args, "settled-start HISTORY.csv OUT.properties [--seed N] [--from-year Y]")
    val cfg = WorldConfig(seed = a.long("--seed", 1))
    val fromYear = a.float("--from-year", -1f)
    val files = a.rest()
    a.done()
    val history = HexHistory.readCsv(File(files[0]))
    val out = File(files[1])
    // The area of each kind of country on the map the history was run on.
    val sim = HexSim(World.generate(cfg))
    val land = sim.land
    val area = DoubleArray(Biome.entries.size)
    for (p in 0 until land.n) {
        val counts = IntArray(Biome.entries.size)
        for (c in sim.hw.placeCells[p]) counts[sim.world.biome[c].ordinal]++
        area[counts.indices.maxBy { counts[it] }] += land.areaKm2[p].toDouble()
    }
    val total = area.sum()
    val last = history.year[history.weeks - 1]
    val first = if (fromYear >= 0f) history.year[0] + fromYear else last - 40f
    fun mean(key: String): Double {
        val s = history.series[key] ?: return 0.0
        var sum = 0.0; var c = 0
        for (i in 0 until history.weeks) if (history.year[i] >= first && !s[i].isNaN()) { sum += s[i]; c++ }
        return if (c == 0) 0.0 else sum / c
    }
    val lines = ArrayList<String>()
    lines += "# Where each kind settled: animals per 1000 km² of land, and of each kind of country (kind@country),"
    lines += "# the mean of the years from ${first.toInt()} of a run on seed ${cfg.seed}. Written by SettledStart."
    val kinds = history.series.keys.filter { it.startsWith("animals/") }.map { it.removePrefix("animals/") }
    for (kind in kinds) {
        val whole = mean("animals/$kind")
        if (whole <= 0.5) continue // died out: no line, so it starts from its default seeding
        lines += "${kind.replace(" ", "\\ ")}=%.4f".format(whole / total * 1000)
        for (b in Biome.entries) {
            if (area[b.ordinal] <= 0.0) continue
            val name = b.name.lowercase().replace('_', ' ')
            val heads = mean("biome/$name/$kind")
            lines += "${kind.replace(" ", "\\ ")}@${b.name}=%.4f".format(heads / area[b.ordinal] * 1000)
        }
    }
    out.writeText(lines.joinToString("\n", postfix = "\n"))
    println("wrote ${out.path}: ${kinds.size} kinds")
}
