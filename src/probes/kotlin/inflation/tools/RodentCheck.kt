package inflation.tools

import inflation.Args
import inflation.ecology.FaunaKind
import inflation.ecology.HexFood
import inflation.ecology.HexSim
import inflation.ecology.HexSnapshot
import inflation.ecology.Prey
import inflation.ecology.RodentLedger
import inflation.ecology.Taker
import inflation.ecology.get
import inflation.learning.LearnedModel
import inflation.run.TestWorlds
import inflation.world.Date
import inflation.world.Place
import inflation.world.WorldCache
import inflation.world.WorldConfig
import inflation.world.get

/**
 * The rodents and the wild boar's food: month by month, rodents kg/km² under woods (trees over [WOODS] kg/m²) and in
 * the open, the mast, grass seed and roots standing in the woods (g/m²), and the boar's heads and how well fed; then over
 * the run, what became of the rodents (born, died, cold, starved, taken by each hunter), what they ate, and the mast's fate.
 *
 * Usage: rodent-check --world NAME [--weeks N]
 */
fun main(args: Array<String>) {
    val a = Args(args, "rodent-check --world NAME [--weeks N]")
    val named = TestWorlds.named(a.string("--world") ?: error("--world NAME"))
    val cfg: WorldConfig = named.config
    val weeks = a.int("--weeks", Date.DAYS_PER_YEAR / cfg.daysPerStep)
    a.done()
    val sim = HexSim(WorldCache.load(cfg) { })
    TestWorlds.check(named, sim); HexSnapshot.load(sim, named.state); println("carrying on ${named.state} at ${sim.weather.date}")
    LearnedModel.fromConfig(sim.groups) { println(it) }
    val land = sim.land; val fauna = sim.fauna; val sl = fauna.small; val food = fauna.food; val n = land.n
    val woods = BooleanArray(n) { land.plants.tree[Place(it)] > WOODS }
    val km2 = DoubleArray(2); for (p in 0 until n) km2[if (woods[p]) 0 else 1] += land.areaKm2[p].toDouble()
    println("woods (trees over $WOODS kg/m²) %.0f km², the rest %.0f km²".format(km2[0], km2[1]))
    val boar = fauna.kinds.indexOfFirst { it.name == "wild boar" }
    sl.rodentLedger.clear(); for (t in sl.preyTakenBy[Prey.RODENTS.ordinal]) t.fill(0f)
    for (l in food.mastLedger) l.sumThenReset()

    val wk = IntArray(12); val rod = Array(2) { DoubleArray(12) }; val mast = DoubleArray(12); val seed = DoubleArray(12); val roots = DoubleArray(12)
    val bHeads = DoubleArray(12); val bFed = DoubleArray(12)
    val hare = Array(2) { DoubleArray(12) }; val hareK = Array(2) { DoubleArray(12) }; val herb = Array(2) { DoubleArray(12) }; val hareFull = DoubleArray(12)
    println("boar at the start %.0f, rodents %.0f t".format(fauna.count(FaunaKind(boar)), (0 until n).sumOf { (sl.rodents[it] * land.areaKm2[it]).toDouble() } / 1000))
    repeat(weeks) {
        sim.step()
        val m = sim.weather.date.month; wk[m]++
        for (p in 0 until n) {
            val a2 = land.areaKm2[p].toDouble(); val c = if (woods[p]) 0 else 1
            rod[c][m] += sl.rodents[p] * a2
            val hk = sl.hareCapacity(Place(p)); hare[c][m] += sl.hares[p] * a2; hareK[c][m] += hk * a2; herb[c][m] += land.plants.herb[p] * a2
            if (sl.hares[p] > 0.9f * hk) hareFull[m] += sl.hares[p] * a2
            if (c == 0) { mast[m] += food.mast[p] * a2; seed[m] += food.seeds[p] * a2; roots[m] += food.roots[p] * a2 }
            val d = fauna.adults[boar][p] + fauna.young[boar][p]
            if (d > 0f) { bHeads[m] += d * a2; bFed[m] += d * a2 * fauna.fed[boar][p] }
        }
    }
    println("\nmonth  rodents kg/km² woods / open | woods g/m²: mast  seed  roots | boar heads  fed")
    for (m in 0 until 12) if (wk[m] > 0) { val k = wk[m].toDouble()
        println("%5d  %8.1f %8.1f | %10.2f %5.2f %6.2f | %10.0f %4.2f".format(m + 1, rod[0][m] / k / km2[0], rod[1][m] / k / km2[1],
            1000 * mast[m] / k / km2[0], 1000 * seed[m] / k / km2[0], 1000 * roots[m] / k / km2[0], bHeads[m] / k, bFed[m] / bHeads[m].coerceAtLeast(1e-9))) }
    println("\nmonth  hares kg/km² woods / open | their room woods / open | herbs g/m² woods / open | hares within 10% of their room")
    for (m in 0 until 12) if (wk[m] > 0) { val k = wk[m].toDouble()
        println("%5d  %6.2f %6.2f | %6.2f %6.2f | %6.1f %6.1f | %3.0f%%".format(m + 1, hare[0][m] / k / km2[0], hare[1][m] / k / km2[1],
            hareK[0][m] / k / km2[0], hareK[1][m] / k / km2[1], 1000 * herb[0][m] / k / km2[0], 1000 * herb[1][m] / k / km2[1],
            100 * hareFull[m] / (hare[0][m] + hare[1][m]).coerceAtLeast(1e-9))) }

    fun sum(t: FloatArray, c: Int) = (0 until n).filter { (if (woods[it]) 0 else 1) == c }.sumOf { (t[it] * land.areaKm2[it]).toDouble() } / 1000
    println("\nrodents over the run, t: woods / the rest")
    for (e in RodentLedger.entries) println("  %-8s %12.0f %12.0f".format(e.name.lowercase(), sum(sl.rodentLedger[e], 0), sum(sl.rodentLedger[e], 1)))
    val taken = sl.preyTakenBy[Prey.RODENTS.ordinal]
    val names = (0 until Taker.ALL).map { i -> when (i) { Taker.GROUPS.i -> "packs and bands"; Taker.FOXES.i -> "foxes"; Taker.EAGLES.i -> "eagles"; Taker.OWLS.i -> "owls"
        else -> fauna.kinds.getOrNull(i)?.name ?: "" } }
    println("rodents taken, t (woods / the rest): " + (0 until Taker.ALL).map { Triple(names[it], sum(taken[it], 0), sum(taken[it], 1)) }
        .filter { it.second + it.third > 0.5 }.sortedByDescending { it.second + it.third }.joinToString(", ") { "%s %.0f / %.0f".format(it.first, it.second, it.third) })
    val hTaken = sl.preyTakenBy[Prey.HARES.ordinal]
    println("hares taken, t (woods / the rest): " + (0 until Taker.ALL).map { Triple(names[it], sum(hTaken[it], 0), sum(hTaken[it], 1)) }
        .filter { it.second + it.third > 0.05 }.sortedByDescending { it.second + it.third }.joinToString(", ") { "%s %.1f / %.1f".format(it.first, it.second, it.third) })
    val l = food.mastLedger.map { it.sumThenReset() }; val fell = l[HexFood.MastFate.FELL.ordinal].coerceAtLeast(1e-9)
    println("mast: fell %.0f t; animals %.1f%%, small life %.1f%%, people %.1f%%, rotted %.1f%%".format(fell / 1000,
        100 * l[HexFood.MastFate.ANIMALS.ordinal] / fell, 100 * l[HexFood.MastFate.SMALL_LIFE.ordinal] / fell, 100 * l[HexFood.MastFate.PEOPLE.ordinal] / fell, 100 * l[HexFood.MastFate.ROTTED.ordinal] / fell))
    println("boar at the end %.0f; rodents %.0f t".format(fauna.count(FaunaKind(boar)), (0 until n).sumOf { (sl.rodents[it] * land.areaKm2[it]).toDouble() } / 1000))
}

/** Trees, kg/m², over which a place counts as woods. */
private const val WOODS = 5f
