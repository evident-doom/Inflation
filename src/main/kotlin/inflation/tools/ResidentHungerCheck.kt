package inflation.tools

import inflation.Args
import inflation.ecology.HexFauna
import inflation.ecology.HexSim
import inflation.world.Date
import inflation.world.WorldConfig
import inflation.ecology.HexSnapshot
import inflation.world.WorldCache

/**
 * When and where resident kinds go hungry: for each kind named, month by month, its heads, how well
 * fed, the share of heads and of hunger deaths in places with little water within reach (tether) or
 * little green, and how many heads stand in places their kind barely lives in (the edge it spills
 * into); then the hunger deaths by how much water is within reach, how green the place is, and how
 * many of the kind live there. Hunger deaths are worked out place by place by the residents' rule
 * (see [HexFauna.HUNGER_FROM]).
 *
 * Usage: resident-hunger-check (--world NAME | --seed N [--width KM] [--height KM]) [--years N] [--kinds a,b]
 */
fun main(args: Array<String>) {
    val a = Args(args, "resident-hunger-check (--world NAME | --seed N [--width KM] [--height KM]) [--years N] [--kinds a,b]")
    val named = a.string("--world")?.let { TestWorlds.named(it) }
    var cfg = named?.config ?: WorldConfig(seed = 1)
    val loadFile: java.io.File? = named?.state
    cfg = cfg.copy(seed = a.long("--seed", cfg.seed), widthKm = a.int("--width", cfg.widthKm), heightKm = a.int("--height", cfg.heightKm))
    val years = a.int("--years", 1); val kinds = a.string("--kinds")?.split(",") ?: listOf("hippo")
    a.done()
    val sim = HexSim(WorldCache.load(cfg) { })
    loadFile?.let { HexSnapshot.load(sim, it); println("carrying on $it at ${sim.weather.date}") }
    val fauna = sim.fauna; val land = sim.land; val n = land.n
    val stepsY = Date.DAYS_PER_YEAR / cfg.daysPerStep
    for (name in kinds) {
        val ki = fauna.kinds.indexOfFirst { it.name == name }
        if (ki < 0) { println("$name is not a resident kind"); continue }
    }
    val ks = kinds.mapNotNull { name -> fauna.kinds.indexOfFirst { it.name == name }.takeIf { it >= 0 } }
    // By month: heads, fed (heads-weighted), heads and hunger deaths with tether < 0.2, green < 0.2, and at the edge (under 1% of the kind's usual density).
    class Acc { val heads = DoubleArray(12); val fed = DoubleArray(12); val tether = DoubleArray(12); val dry = DoubleArray(12); val brown = DoubleArray(12); val edge = DoubleArray(12)
        val wetFloor = DoubleArray(12); val flood = DoubleArray(12); val table = DoubleArray(12); val green = DoubleArray(12); val hunger = DoubleArray(12); val hungerDry = DoubleArray(12); val hungerBrown = DoubleArray(12); val hungerEdge = DoubleArray(12); val weeks = IntArray(12)
        val byTether = DoubleArray(5); val byGreen = DoubleArray(5); val byDensity = DoubleArray(5); val headsByTether = DoubleArray(5); val headsByGreen = DoubleArray(5); val headsByDensity = DoubleArray(5)
        var hungerReported = 0.0 }
    val acc = ks.associateWith { Acc() }
    fun bucket(x: Float) = (x * 5f).toInt().coerceIn(0, 4)
    repeat(years * stepsY) {
        sim.step()
        val m = sim.weather.lastMonth
        for (k2 in ks) {
            val s = fauna.kinds[k2]; val a = acc[k2]!!
            val usual = HexFauna.usualDensity(s)
            a.weeks[m]++
            a.hungerReported += fauna.deaths["${s.name}:hunger"] ?: 0.0
            for (p in 0 until n) {
                val ad = fauna.adults[k2][p]; val yd = fauna.young[k2][p]
                if (ad + yd <= 0f) continue
                val area = land.areaKm2[p]
                val heads = ((ad + yd) * area).toDouble()
                val f = fauna.fed[k2][p]
                val hunger = if (f < HexFauna.HUNGER_FROM) (HexFauna.HUNGER_FROM - f) * HexFauna.HUNGER_RATE else 0f
                val died = ((ad + 1.5f * yd) * hunger * area).toDouble()
                val t = fauna.tether(s, p); val g = land.green[p]
                val edge = (ad + yd) < 0.01f * usual
                a.heads[m] += heads; a.fed[m] += heads * f; a.wetFloor[m] += heads * land.wetFloor(p); a.flood[m] += heads * land.floodShare[p]; a.table[m] += heads * land.waterTableM(p); a.green[m] += heads * land.green[p]; a.tether[m] += heads * t; a.hunger[m] += died
                if (t < 0.2f) { a.dry[m] += heads; a.hungerDry[m] += died }
                if (g < 0.2f) { a.brown[m] += heads; a.hungerBrown[m] += died }
                if (edge) { a.edge[m] += heads; a.hungerEdge[m] += died }
                a.byTether[bucket(t)] += died; a.headsByTether[bucket(t)] += heads
                a.byGreen[bucket(g)] += died; a.headsByGreen[bucket(g)] += heads
                val db = when { ad + yd < 0.01f * usual -> 0; ad + yd < 0.1f * usual -> 1; ad + yd < usual -> 2; ad + yd < 4f * usual -> 3; else -> 4 }
                a.byDensity[db] += died; a.headsByDensity[db] += heads
            }
        }
    }
    for (k2 in ks) {
        val s = fauna.kinds[k2]; val a = acc[k2]!!
        println("\n${s.name}: hunger deaths reported ${"%.1f".format(a.hungerReported)}, worked out here ${"%.1f".format(a.hunger.sum())} (over $years years; month 0 starts at the winter solstice)")
        fun row(label: String, f: (Int) -> Double) = println("%-34s".format(label) + (0 until 12).joinToString(" ") { "%7.2f".format(f(it)) })
        println("%-34s".format("month") + (0 until 12).joinToString(" ") { "%7d".format(it) })
        row("heads (weekly mean)") { a.heads[it] / a.weeks[it].coerceAtLeast(1) }
        row("fed") { a.fed[it] / a.heads[it].coerceAtLeast(1e-9) }
        row("green") { a.green[it] / a.heads[it].coerceAtLeast(1e-9) }
        row("water within reach (tether)") { a.tether[it] / a.heads[it].coerceAtLeast(1e-9) }
        row("floodplain share") { a.flood[it] / a.heads[it].coerceAtLeast(1e-9) }
        row("water table depth, m") { a.table[it] / a.heads[it].coerceAtLeast(1e-9) }
        row("wet valley floor share") { a.wetFloor[it] / a.heads[it].coerceAtLeast(1e-9) }
        row("share of heads: tether < 0.2") { a.dry[it] / a.heads[it].coerceAtLeast(1e-9) }
        row("share of heads: green < 0.2") { a.brown[it] / a.heads[it].coerceAtLeast(1e-9) }
        row("share of heads: at the edge") { a.edge[it] / a.heads[it].coerceAtLeast(1e-9) }
        row("hunger deaths") { a.hunger[it] }
        row("  of them tether < 0.2") { a.hungerDry[it] / a.hunger[it].coerceAtLeast(1e-9) }
        row("  of them green < 0.2") { a.hungerBrown[it] / a.hunger[it].coerceAtLeast(1e-9) }
        row("  of them at the edge") { a.hungerEdge[it] / a.hunger[it].coerceAtLeast(1e-9) }
        val tot = a.hunger.sum().coerceAtLeast(1e-9); val heads = a.heads.sum().coerceAtLeast(1e-9)
        fun split(label: String, names: List<String>, d: DoubleArray, h: DoubleArray) =
            println("$label: " + names.indices.joinToString(", ") { names[it] + " %.0f%% of deaths / %.0f%% of head-weeks".format(100 * d[it] / tot, 100 * h[it] / heads) })
        split("by water within reach", listOf("0-0.2", "0.2-0.4", "0.4-0.6", "0.6-0.8", "0.8-1"), a.byTether, a.headsByTether)
        split("by green", listOf("0-0.2", "0.2-0.4", "0.4-0.6", "0.6-0.8", "0.8-1"), a.byGreen, a.headsByGreen)
        split("by density against usual", listOf("<1%", "1-10%", "10-100%", "1-4x", ">4x"), a.byDensity, a.headsByDensity)
    }
}
