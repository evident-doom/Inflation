package inflation.tools

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
 * crowding (of its usual), what a band of the kind would add to it on that place's ground; and last the higher and the lower
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
    val hz = listOf("hunger" to HexFauna.HZ_HUNGER, "cold" to HexFauna.HZ_COLD, "thirst" to HexFauna.HZ_THIRST,
        "disease" to HexFauna.HZ_DISEASE, "crowd" to HexFauna.HZ_CROWD, "snow" to HexFauna.HZ_SNOW_A, "fire" to HexFauna.HZ_FIRE_A, "hunted" to HexFauna.HZ_HUNTED)
    val cols = listOf("growth", "born", "grown die", "young die", "fed", "protein") + hz.map { it.first } + listOf("band crowd", "km2 here", "km2")
    // [kind][month][column]: heads-weighted sums of high minus low; [kind][month]: heads, heads in the higher band.
    val diff = Array(kinds.size) { Array(12) { DoubleArray(cols.size) } }
    val heads = Array(kinds.size) { DoubleArray(12) }; val high = Array(kinds.size) { DoubleArray(12) }
    val outLo = FloatArray(HexFauna.HZ_COUNT); val outHi = FloatArray(HexFauna.HZ_COUNT)
    val partsLo = FloatArray(HexFauna.GROWTH_PARTS); val partsHi = FloatArray(HexFauna.GROWTH_PARTS)

    for (w in 0 until weeks) {
        sim.step()
        val m = sim.weather.date.month
        for ((i, k) in kinds.withIndex()) {
            val s = fauna.kinds[k]; val ad = fauna.adults[k]; val yo = fauna.young[k]
            var grown = 0.0; var little = 0.0; var fatDays = 0.0
            for (p in 0 until land.n) { val area = land.areaKm2[p]; grown += ad[p] * area; little += yo[p] * area; fatDays += fauna.reserve[k][p] * ad[p] * area }
            if (grown <= 0.0) continue
            val ypg = (little / grown).toFloat(); val fat = (fatDays / grown).toFloat()
            val band = s.maxGroupSize.toFloat()
            for (h in 0 until hex.size) for (b in 0 until HexWorld.BANDS_M.size - 1) {
                val lo = hw.placeOf[b][h]; val hi = hw.placeOf[b + 1][h]
                if (lo < 0 || hi < 0) continue
                val nLo = (ad[lo] + yo[lo]) * land.areaKm2[lo].toDouble(); val nHi = (ad[hi] + yo[hi]) * land.areaKm2[hi].toDouble()
                val n = nLo + nHi; if (n <= 0.0) continue
                val oLo = fauna.arriveOdds(k, lo, sim.weather, outLo); val oHi = fauna.arriveOdds(k, hi, sim.weather, outHi)
                if (oLo.isNaN() || oHi.isNaN()) continue
                val gLo = fauna.growthParts(k, lo, sim.weather, outLo, ypg, fat, partsLo)
                val gHi = fauna.growthParts(k, hi, sim.weather, outHi, ypg, fat, partsHi)
                val d = diff[i][m]
                d[0] += n * (gHi - gLo)
                for (c in 0 until HexFauna.GROWTH_PARTS) d[1 + c] += n * (partsHi[c] - partsLo[c])
                for ((j, e) in hz.withIndex()) d[1 + HexFauna.GROWTH_PARTS + j] += n * (outHi[e.second] - outLo[e.second])
                val bandCrowd = band / (HexFauna.CROWDED * HexFauna.usualDensity(s))
                d[cols.size - 3] += n * (bandCrowd / land.areaKm2[hi] - bandCrowd / land.areaKm2[lo])
                d[cols.size - 2] += n * land.areaKm2[hi]; d[cols.size - 1] += n * land.areaKm2[lo]
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
