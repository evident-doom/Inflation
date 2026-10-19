package inflation.tools

import inflation.Args
import inflation.Config
import inflation.Tuning
import inflation.ecology.HexSim
import inflation.ecology.HexSnapshot
import inflation.learning.LearnedModel
import inflation.monthsFrom
import inflation.run.TestWorlds
import inflation.world.WorldCache
import inflation.world.WorldConfig

/**
 * Whether the residents' births follow the mothers or the places: each place keeps its own birth season
 * ([inflation.ecology.HexFauna.birthStart]), and the mothers at a place in its season bear there, wherever they came
 * from. For each plant-eating kind, over the weeks run: the share of its mothers in a season (heads-weighted, per week)
 * against what one season a year gives ([Species.birthMonths] of 12) — above 1, mothers spend more weeks in season than
 * one season, so bear more than once a year's young; the heads-weighted mean birth month of the places by height band;
 * the young born a year to a mother (the week's births over the mothers there, summed) against [Species.youngPerFemale];
 * and the share of the week's arrivals (heads up at a place from the week before, among the places where heads rose)
 * that came to places in season, against the share of all heads at places in season. With --still the residents never
 * move (the control: what births and deaths alone make of the ratio).
 *
 * Usage: birth-timing (--world NAME | --seed N [--width KM] [--height KM]) [--weeks N] [--kinds a,b] [--still]
 */
fun main(args: Array<String>) {
    val a = Args(args, "birth-timing (--world NAME | --seed N [--width KM] [--height KM]) [--weeks N] [--kinds a,b] [--still]")
    val named = a.string("--world")?.let { TestWorlds.named(it) }
    var cfg = named?.config ?: WorldConfig(seed = 1)
    cfg = cfg.copy(seed = a.long("--seed", cfg.seed), widthKm = a.int("--width", cfg.widthKm), heightKm = a.int("--height", cfg.heightKm))
    val weeks = a.int("--weeks", 52)
    // (Names with a space are given with an underscore: wild_boar.)
    val names = a.string("--kinds")?.split(",")?.map { it.trim().replace('_', ' ') }
    val still = a.flag("--still")
    a.done()
    val tuning = Tuning { if (still && it == "t.residentMoveWeeks") "1000000" else Config.string(it) }
    val sim = HexSim(WorldCache.load(cfg) { }, tuning = tuning)
    named?.let { TestWorlds.check(it, sim); HexSnapshot.load(sim, it.state); println("carrying on ${it.state} at ${sim.weather.date}") }
    LearnedModel.fromConfig(sim.groups) { println(it) }
    val fauna = sim.fauna; val land = sim.land; val hw = sim.hw; val n = land.n
    val kinds = fauna.kinds.indices.filter { k -> fauna.room[k] != null && (names == null || fauna.kinds[k].name in names) }
    val bands = hw.placeBand.max() + 1

    val inSeason = DoubleArray(kinds.size); val weeksRun = IntArray(kinds.size); val bornPerMother = DoubleArray(kinds.size)
    val cameIn = DoubleArray(kinds.size); val came = DoubleArray(kinds.size); val atIn = DoubleArray(kinds.size)
    // [kind][band]: heads, heads times the months from January of the birth start (as an angle's sine and cosine, to wrap the year).
    val bandHeads = Array(kinds.size) { DoubleArray(bands) }
    val bandSin = Array(kinds.size) { DoubleArray(bands) }; val bandCos = Array(kinds.size) { DoubleArray(bands) }
    val before = Array(kinds.size) { k -> DoubleArray(n) { p -> heads(fauna, land, kinds[k], p) } }
    for (w in 0 until weeks) {
        sim.step()
        val m = sim.weather.date.month
        for ((i, k) in kinds.withIndex()) {
            val s = fauna.kinds[k]
            var mothers = 0.0; var mothersIn = 0.0
            for (p in 0 until n) {
                val h = heads(fauna, land, k, p)
                val season = monthsFrom(fauna.birthStart[k][p].toInt(), m) < s.birthMonths
                val mo = 0.5 * fauna.adults[k][p] * land.areaKm2[p]
                mothers += mo; if (season) mothersIn += mo
                val rise = h - before[i][p]
                if (rise > 0.0) { came[i] += rise; if (season) cameIn[i] += rise }
                if (season) atIn[i] += h
                if (h > 0.0) {
                    val b = hw.placeBand[p]; val ang = 2 * Math.PI * fauna.birthStart[k][p] / 12.0
                    bandHeads[i][b] += h; bandSin[i][b] += h * Math.sin(ang); bandCos[i][b] += h * Math.cos(ang)
                }
                before[i][p] = h
            }
            if (mothers > 0.0) { inSeason[i] += mothersIn / mothers; weeksRun[i]++; bornPerMother[i] += fauna.bornThisWeek[k] / mothers }
        }
    }
    println(if (still) "(residents kept still)" else "(residents moving)")
    for ((i, k) in kinds.withIndex()) {
        val s = fauna.kinds[k]
        val oneSeason = s.birthMonths / 12.0
        val ratio = if (weeksRun[i] > 0) inSeason[i] / weeksRun[i] / oneSeason else Double.NaN
        val total = bandHeads[i].sum()
        val months = (0 until bands).joinToString(" ") { b ->
            if (bandHeads[i][b] <= 0.0) "    -" else {
                val ang = Math.atan2(bandSin[i][b], bandCos[i][b]); val mo = ((ang / (2 * Math.PI) * 12.0) + 12.0) % 12.0
                "%5.1f".format(mo + 1)
            }
        }
        println("%-12s season %d mo from %2d: born a year to a mother %.2f of %.2f; mothers in season %.2f of one season; arrivals to places in season %.3f against heads there %.3f; birth month by band %s (heads %s)".format(
            s.name, s.birthMonths, s.birthMonth + 1, bornPerMother[i] * (360.0 / 7) / weeksRun[i].coerceAtLeast(1), s.youngPerFemale, ratio,
            if (came[i] > 0) cameIn[i] / came[i] else Double.NaN, if (total > 0) atIn[i] / (bandHeads[i].sum()) else Double.NaN, months,
            (0 until bands).joinToString("/") { b -> "%.0f%%".format(100 * bandHeads[i][b] / total) }))
    }
}

private fun heads(fauna: inflation.ecology.HexFauna, land: inflation.world.HexLand, k: Int, p: Int) =
    ((fauna.adults[k][p] + fauna.young[k][p]) * land.areaKm2[p]).toDouble()
