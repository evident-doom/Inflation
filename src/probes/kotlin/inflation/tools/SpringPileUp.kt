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
 * Where residents pile up and what drew them there, month by month: of each kind named, the places that end a week as
 * crowded as twice [HexFauna.CROWDED] times its usual numbers or more (the piles), against all the places it lives at,
 * heads-weighted. For each: how many heads it holds against the week before (the walkers that came), its km² and height
 * band, how fed those there were and how fed a band coming there was foreseen to be ([HexFauna.room]), protein, what the
 * place was worth to the movers ([HexFauna.worth], in the weeks they move), the young born to a grown one and the grown
 * dying there as the week before foresaw it ([HexFauna.growthParts]), and the week's disease (and of it the outbreaks').
 *
 * Usage: spring-pile-up (--world NAME | --seed N [--width KM] [--height KM]) [--weeks N] [--kinds a,b]
 */
fun main(args: Array<String>) {
    val a = Args(args, "spring-pile-up (--world NAME | --seed N [--width KM] [--height KM]) [--weeks N] [--kinds a,b]")
    val named = a.string("--world")?.let { TestWorlds.named(it) }
    var cfg = named?.config ?: WorldConfig(seed = 1)
    cfg = cfg.copy(seed = a.long("--seed", cfg.seed), widthKm = a.int("--width", cfg.widthKm), heightKm = a.int("--height", cfg.heightKm))
    val weeks = a.int("--weeks", 52)
    // (Names with a space are given with an underscore: wild_boar.)
    val names = (a.string("--kinds") ?: "wild_boar,roe_deer,red_deer,mouflon").split(",").map { it.trim().replace('_', ' ') }
    a.done()
    val sim = HexSim(WorldCache.load(cfg) { })
    named?.let { TestWorlds.check(it, sim); HexSnapshot.load(sim, it.state); println("carrying on ${it.state} at ${sim.weather.date}") }
    LearnedModel.fromConfig(sim.groups) { println(it) }
    val fauna = sim.fauna; val land = sim.land; val hw = sim.hw; val n = land.n
    val kinds = names.mapNotNull { name -> fauna.kinds.indexOfFirst { it.name == name }.takeIf { it >= 0 && fauna.room[it] != null } }

    val cols = listOf("heads", "came x", "km2", "band", "fed", "room", "protein", "worth", "born", "grown die", "disease", "outbreak")
    // [kind][month][0 piles / 1 all][column]: heads-weighted sums ("heads" holds the heads); [kind][month]: pile-weeks.
    val sums = Array(kinds.size) { Array(12) { Array(2) { DoubleArray(cols.size) } } }
    val piles = Array(kinds.size) { IntArray(12) }; val weeksIn = IntArray(12)
    val before = Array(kinds.size) { FloatArray(n) }
    val born = Array(kinds.size) { FloatArray(n) }; val die = Array(kinds.size) { FloatArray(n) }
    val out = FloatArray(HexFauna.HZ_COUNT); val parts = FloatArray(HexFauna.GROWTH_PARTS)

    for (w in 0 until weeks) {
        // The week before: heads at each place, and the births and deaths a newcomer would see there as it stands.
        for ((i, k) in kinds.withIndex()) {
            val ad = fauna.adults[k]; val yo = fauna.young[k]
            var grown = 0.0; var little = 0.0; var fatDays = 0.0
            for (p in 0 until n) { val ar = land.areaKm2[p]; grown += ad[p] * ar; little += yo[p] * ar; fatDays += fauna.reserve[k][p] * ad[p] * ar }
            val ypg = if (grown > 0.0) (little / grown).toFloat() else 0f; val fat = if (grown > 0.0) (fatDays / grown).toFloat() else 0f
            for (p in 0 until n) {
                before[i][p] = (ad[p] + yo[p]) * land.areaKm2[p]
                if (before[i][p] <= 0f || fauna.arriveOdds(k, p, sim.weather, out).isNaN()) { born[i][p] = 0f; die[i][p] = 0f; continue }
                fauna.growthParts(k, p, sim.weather, out, ypg, fat, parts)
                born[i][p] = parts[HexFauna.GROWTH_BORN]; die[i][p] = parts[HexFauna.GROWTH_GROWN_DIE]
            }
        }
        sim.step()
        val m = sim.weather.date.month; weeksIn[m]++
        for ((i, k) in kinds.withIndex()) {
            val s = fauna.kinds[k]; val ad = fauna.adults[k]; val yo = fauna.young[k]
            for (p in 0 until n) {
                val h = (ad[p] + yo[p]) * land.areaKm2[p].toDouble()
                if (h <= 0.0) continue
                if (fauna.stayOdds(k, p, sim.weather, out).isNaN()) continue
                val pile = out[HexFauna.HZ_CROWD] >= 2f
                if (pile) piles[i][m]++
                val v = doubleArrayOf(h, h / before[i][p].coerceAtLeast(1e-3f), land.areaKm2[p].toDouble(), hw.placeBand[p].toDouble(),
                    fauna.fed[k][p].toDouble(), fauna.room[k]!![p].toDouble(), fauna.protein[k][p].toDouble(), fauna.worth[k][p].toDouble(),
                    born[i][p].toDouble(), die[i][p].toDouble(), out[HexFauna.HZ_DISEASE].toDouble(), fauna.outbreakDeaths(s, p).toDouble())
                for (g in if (pile) intArrayOf(0, 1) else intArrayOf(1)) {
                    val row = sums[i][m][g]
                    row[0] += h
                    for (c in 1 until cols.size) row[c] += h * v[c]
                }
            }
        }
    }
    for ((i, k) in kinds.withIndex()) {
        println("${fauna.kinds[k].name}: piles (crowd 2+ of ${HexFauna.CROWDED} times usual) against all its places, heads-weighted, by month; pile place-weeks: ${piles[i].joinToString(" ")}")
        for ((c, name) in cols.withIndex()) for (g in 0..1) {
            val label = "%-9s %-4s".format(if (g == 0) name else "", if (g == 0) "pile" else "all")
            println("  $label %s".format((0 until 12).joinToString(" ") { m ->
                val row = sums[i][m][g]
                when {
                    row[0] <= 0.0 -> "        -"
                    c == 0 -> "%9.0f".format(row[0] / weeksIn[m])
                    else -> "%9.4f".format(row[c] / row[0])
                }
            }))
        }
    }
    println("(heads: mean heads a week that month; came x: heads after the week against before it.)")
}
