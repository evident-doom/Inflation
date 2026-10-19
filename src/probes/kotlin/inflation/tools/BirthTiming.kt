package inflation.tools

import inflation.Args
import inflation.ecology.HexFauna
import inflation.ecology.HexSim
import inflation.ecology.HexSnapshot
import inflation.ecology.swapForms
import inflation.learning.LearnedModel
import inflation.run.TestWorlds
import inflation.world.Date
import inflation.world.HexWorld
import inflation.world.WorldCache
import inflation.world.WorldConfig
import kotlin.math.exp
import kotlin.math.ln1p

/**
 * How much of what draws residents to the next place is the birth calendar: month by month, of each kind named, its
 * heads (weighted) by how much better the best place next door is than theirs ((best - here) / best, the pull [HexFauna]'s
 * spill goes by), with the place's worth as the movers reckon it (births in the birth season, over its weeks) and with
 * the births left out; and the heads whose best place next door is in its birth season while theirs is not, or the
 * other way, and the pull on them.
 *
 * Usage: birth-timing (--world NAME | --seed N [--width KM] [--height KM]) [--weeks N] [--kinds a,b]
 */
fun main(args: Array<String>) {
    val a = Args(args, "birth-timing (--world NAME | --seed N [--width KM] [--height KM]) [--weeks N] [--kinds a,b]")
    val named = a.string("--world")?.let { TestWorlds.named(it) }
    var cfg = named?.config ?: WorldConfig(seed = 1)
    cfg = cfg.copy(seed = a.long("--seed", cfg.seed), widthKm = a.int("--width", cfg.widthKm), heightKm = a.int("--height", cfg.heightKm))
    val weeks = a.int("--weeks", 52)
    // (Names with a space are given with an underscore: wild_boar.)
    val names = (a.string("--kinds") ?: "wild_boar,roe_deer,red_deer,mouflon,aurochs").split(",").map { it.trim().replace('_', ' ') }
    a.done()
    val sim = HexSim(WorldCache.load(cfg) { })
    named?.let { TestWorlds.check(it, sim); HexSnapshot.load(sim, it.state); println("carrying on ${it.state} at ${sim.weather.date}") }
    sim.swapForms { println(it) }
    LearnedModel.fromConfig(sim.groups) { println(it) }
    val fauna = sim.fauna; val land = sim.land; val hw = sim.hw; val hex = sim.hex; val n = land.n
    val kinds = names.mapNotNull { name -> fauna.kinds.indexOfFirst { it.name == name }.takeIf { it >= 0 && fauna.room[it] != null } }
    val days = sim.cfg.daysPerStep
    val stepsPerYear = Date.DAYS_PER_YEAR / days.toFloat()

    val cols = listOf("pull", "pull, no births", "heads out->in", "pull out->in", "heads in->out", "pull in->out", "best moved", "height above hex")
    val sums = Array(kinds.size) { Array(12) { DoubleArray(cols.size + 1) } }
    // Where each kind's births start now, by month 1-12, heads-weighted: how far apart the places' calendars have drifted.
    for (k in kinds) {
        val by = DoubleArray(12); var all = 0.0
        for (p in 0 until n) { val h = (fauna.adults[k][p] + fauna.young[k][p]) * land.areaKm2[p].toDouble(); by[fauna.birthStart[k][p].toInt()] += h; all += h }
        println("${fauna.kinds[k].name} births start (month 1-12, share of heads): " + by.joinToString(" ") { "%.3f".format(it / all) })
    }
    val out = FloatArray(HexFauna.HZ_COUNT); val parts = FloatArray(HexFauna.GROWTH_PARTS)
    val full = FloatArray(n); val bare = FloatArray(n); val season = BooleanArray(n)
    val bornByYear = Array(kinds.size) { DoubleArray(10) }; val headsByYear = Array(kinds.size) { DoubleArray(10) }
    val lowest = FloatArray(hex.size) { Float.MAX_VALUE }
    for (p in 0 until n) lowest[hw.placeHex[p]] = minOf(lowest[hw.placeHex[p]], hw.placeElevationM[p])

    for (w in 0 until weeks) {
        val m = sim.weather.date.month
        for ((i, k) in kinds.withIndex()) {
            val s = fauna.kinds[k]; val ad = fauna.adults[k]; val yo = fauna.young[k]
            var grown = 0.0; var little = 0.0; var fatDays = 0.0
            for (p in 0 until n) { val ar = land.areaKm2[p]; grown += ad[p] * ar; little += yo[p] * ar; fatDays += fauna.reserve[k][p] * ad[p] * ar }
            val ypg = if (grown > 0.0) (little / grown).toFloat() else 0f; val fat = if (grown > 0.0) (fatDays / grown).toFloat() else 0f
            val head = 1f + ypg
            val stepsInSeason = s.birthMonths * Date.DAYS_PER_MONTH / days.toFloat()
            // Each place's worth to the movers, with the births and without (as [HexFauna]'s growth reckons it).
            for (p in 0 until n) {
                season[p] = Math.floorMod(m - fauna.birthStart[k][p].toInt(), 12) < s.birthMonths
                if (fauna.arriveOdds(k, p, sim.weather, out).isNaN()) { full[p] = 0f; bare[p] = 0f; continue }
                fauna.growthParts(k, p, sim.weather, out, ypg, fat, parts)
                val dying = ((parts[HexFauna.GROWTH_GROWN_DIE] + ypg * parts[HexFauna.GROWTH_YOUNG_DIE]) / head).coerceIn(0f, 1f)
                val live = exp(stepsPerYear * ln1p(-dying))
                bare[p] = live.takeIf { it.isFinite() && it > 0f } ?: 0f
                full[p] = ((1f + stepsInSeason * parts[HexFauna.GROWTH_BORN] / head) * live).takeIf { it.isFinite() && it > 0f } ?: 0f
            }
            val row = sums[i][m]
            for (p in 0 until n) {
                val heads = (ad[p] + yo[p]) * land.areaKm2[p].toDouble()
                if (heads <= 0.0) continue
                val h = hw.placeHex[p]; val b = hw.placeBand[p]
                var bestF = -1; var bestB = -1
                for (d in 0 until 6) {
                    val nh = hex.neighbour(h, d); if (nh < 0) continue
                    for (db in -1..1) {
                        val bb = b + db; if (bb !in 0 until HexWorld.BANDS_M.size) continue
                        val q = hw.placeOf[bb][nh]; if (q < 0) continue
                        if (bestF < 0 || full[q] > full[bestF]) bestF = q
                        if (bestB < 0 || bare[q] > bare[bestB]) bestB = q
                    }
                }
                if (bestF < 0) continue
                val pullF = if (full[bestF] > full[p]) (full[bestF] - full[p]) / full[bestF] else 0f
                val pullB = if (bare[bestB] > bare[p]) (bare[bestB] - bare[p]) / bare[bestB] else 0f
                row[0] += heads * pullF; row[1] += heads * pullB
                if (season[bestF] && !season[p]) { row[2] += heads; row[3] += heads * pullF }
                if (!season[bestF] && season[p]) { row[4] += heads; row[5] += heads * pullF }
                if (bestF != bestB) row[6] += heads
                // (How high it stands above its hex's lowest band, m: the seasonal up and down within the hexes.)
                row[7] += heads * (hw.placeElevationM[p] - lowest[h])
                row[cols.size] += heads
            }
        }
        sim.step()
        for ((i, k) in kinds.withIndex()) { val y = (w / 52).coerceAtMost(9); bornByYear[i][y] += fauna.bornThisWeek[k]; headsByYear[i][y] += fauna.count(k) / 52 }
    }
    for ((i, k) in kinds.withIndex()) {
        println("${fauna.kinds[k].name} (births from month ${fauna.kinds[k].birthMonth + 1}, ${fauna.kinds[k].birthMonths} months), by month 1-12, heads-weighted:")
        for ((c, name) in cols.withIndex()) println("  %-16s %s".format(name, (0 until 12).joinToString(" ") { m ->
            val row = sums[i][m]; val all = row[cols.size]
            when {
                all <= 0.0 -> "     -"
                c == 3 -> if (row[2] > 0.0) "%6.3f".format(row[3] / row[2]) else "     -"
                c == 5 -> if (row[4] > 0.0) "%6.3f".format(row[5] / row[4]) else "     -"
                c == 7 -> "%6.0f".format(row[c] / all)
                else -> "%6.3f".format(row[c] / all)
            }
        }))
    }
    for ((i, k) in kinds.withIndex()) println("${fauna.kinds[k].name} by year: mean heads " + (0 until (weeks + 51) / 52).joinToString(" ") { "%.0f".format(headsByYear[i][it]) } +
        ", born a head " + (0 until (weeks + 51) / 52).joinToString(" ") { "%.3f".format(bornByYear[i][it] / headsByYear[i][it].coerceAtLeast(1.0)) })
    println("(pull: (best next door - here) / best; heads out->in: share of heads out of their birth season whose best place next door is in" +
        " its; best moved: share of heads whose best place next door is another one without the births.)")
}
