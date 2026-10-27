package inflation.tools

import inflation.world.get
import inflation.world.set

import inflation.world.Hex
import inflation.ecology.FaunaKind
import inflation.ecology.disease
import inflation.EnumFloats
import inflation.ecology.Danger
import inflation.ecology.Growth
import inflation.enumFloats
import inflation.world.Place
import inflation.Args
import inflation.ecology.HexFauna
import inflation.ecology.HexSim
import inflation.ecology.HexSnapshot
import inflation.learning.LearnedModel
import inflation.run.TestWorlds
import inflation.world.HexWorld
import inflation.world.WorldCache
import inflation.world.WorldConfig

/**
 * Why upland plant-eaters weigh the higher ground as they do, month by month: for each kind named, over each pair of
 * neighbouring height bands in a hex where the kind lives, the share of its heads in the higher band, and the higher
 * band's weekly growth for a band coming there ([HexFauna.growthParts]) against the lower's, split into what it is made
 * of: the young born to a grown one, the grown and young dying, feeding and protein, each hazard of the grown and the
 * crowding (of its usual); and last the higher and the lower
 * place's km². Each pair weighs by the
 * heads in it. Read with the weekly deaths: a hazard 0.001 higher a week is about 5% a year.
 *
 * Usage: upland-growth (--world NAME | --seed N [--width KM] [--height KM]) [--weeks N] [--kinds a,b]
 */
fun main(args: Array<String>) {
    val a = Args(args, "upland-growth (--world NAME | --seed N [--width KM] [--height KM]) [--weeks N] [--kinds a,b]")
    val named = a.string("--world")?.let { TestWorlds.named(it) }
    var cfg = named?.config ?: WorldConfig(seed = 1)
    cfg = cfg.copy(seed = a.long("--seed", cfg.seed), widthKm = a.int("--width", cfg.widthKm), heightKm = a.int("--height", cfg.heightKm))
    val weeks = a.int("--weeks", 52)
    val names = (a.string("--kinds") ?: "mouflon,ibex,argali,wild goat,red deer,wild horse").split(",").map { it.trim() }
    a.done()
    val sim = HexSim(WorldCache.load(cfg) { })
    named?.let { TestWorlds.check(it, sim); HexSnapshot.load(sim, it.state); println("carrying on ${it.state} at ${sim.weather.date}") }
    LearnedModel.fromConfig(sim.groups) { println(it) }
    val fauna = sim.fauna; val land = sim.land; val hw = sim.hw; val hex = sim.hex
    val kinds = names.mapNotNull { name -> fauna.kinds.indexOfFirst { it.name == name }.takeIf { it >= 0 && fauna.room[it] != null } }

    // What is weighed, higher band against lower: growth, its parts, then the grown's hazards.
    val hz = listOf<Pair<String, (EnumFloats<Danger>) -> Float>>("hunger" to { it[Danger.HUNGER] }, "cold" to { it[Danger.COLD] }, "thirst" to { it[Danger.THIRST] },
        "disease" to { it.disease() }, "crowd" to { it[Danger.CROWD] }, "snow" to { it[Danger.SNOW_A] }, "fire" to { it[Danger.FIRE_A] }, "hunted" to { it[Danger.HUNTED] })
    val cols = listOf("growth", "born", "grown die", "young die", "fed", "protein") + hz.map { it.first } + listOf("km2 here", "km2")
    // [kind][month][column]: heads-weighted sums of high minus low; [kind][month]: heads, heads in the higher band.
    val diff = Array(kinds.size) { Array(12) { DoubleArray(cols.size) } }
    val heads = Array(kinds.size) { DoubleArray(12) }; val high = Array(kinds.size) { DoubleArray(12) }
    val outLo = enumFloats<Danger>(); val outHi = enumFloats<Danger>()
    val partsLo = enumFloats<Growth>(); val partsHi = enumFloats<Growth>()

    for (w in 0 until weeks) {
        sim.step()
        val m = sim.weather.date.month
        for ((i, k) in kinds.withIndex()) {
            val ad = fauna.adults[k]; val yo = fauna.young[k]
            var grown = 0.0; var little = 0.0; var fatDays = 0.0
            for (p in 0 until land.n) { val area = land.areaKm2[p]; grown += ad[p] * area; little += yo[p] * area; fatDays += fauna.reserve[k][p] * ad[p] * area }
            if (grown <= 0.0) continue
            val ypg = (little / grown).toFloat(); val fat = (fatDays / grown).toFloat()
            for (h in hex.all) {
                val lo = hw.placeIn(HexWorld.LOW, h); val hi = hw.placeIn(HexWorld.HIGH, h)
                if (lo.none || hi == lo) continue
                val nLo = (ad[lo.i] + yo[lo.i]) * land.areaKm2[lo.i].toDouble(); val nHi = (ad[hi.i] + yo[hi.i]) * land.areaKm2[hi.i].toDouble()
                val n = nLo + nHi; if (n <= 0.0) continue
                val oLo = fauna.arriveOdds(FaunaKind(k), Place(lo.i), sim.weather, outLo); val oHi = fauna.arriveOdds(FaunaKind(k), Place(hi.i), sim.weather, outHi)
                if (oLo.isNaN() || oHi.isNaN()) continue
                val gLo = fauna.growthParts(FaunaKind(k), Place(lo.i), sim.weather, outLo, ypg, fat, partsLo)
                val gHi = fauna.growthParts(FaunaKind(k), Place(hi.i), sim.weather, outHi, ypg, fat, partsHi)
                val d = diff[i][m]
                d[0] += n * (gHi - gLo)
                for (c in Growth.entries) d[1 + c.ordinal] += n * (partsHi[c] - partsLo[c])
                for ((j, e) in hz.withIndex()) d[1 + Growth.entries.size + j] += n * (e.second(outHi) - e.second(outLo))
                d[cols.size - 2] += n * land.areaKm2[hi.i]; d[cols.size - 1] += n * land.areaKm2[lo.i]
                heads[i][m] += n; high[i][m] += nHi
            }
        }
    }
    for ((i, k) in kinds.withIndex()) {
        println("${fauna.kinds[k].name}: higher band against the lower, a week, by month (heads-weighted over band pairs)")
        println("  %-10s %s".format("in higher", (0 until 12).joinToString(" ") { m -> if (heads[i][m] > 0) "%8.2f".format(high[i][m] / heads[i][m]) else "       -" }))
        for ((c, name) in cols.withIndex()) println("  %-10s %s".format(name,
            (0 until 12).joinToString(" ") { m -> if (heads[i][m] > 0) "%+8.4f".format(diff[i][m][c] / heads[i][m]) else "       -" }))
    }
}
