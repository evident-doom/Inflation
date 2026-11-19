package inflation.tools

import inflation.tools.fauna.FaunaCheckOptions

/**
 * Where the plant-eaters stand by how broken the ground is: places by their share of crag ground ([inflation.world.HexLand.cragShelter]:
 * none, under a quarter, a quarter to a half, more), and in each the land's share and every plant-eating kind's heads
 * per km² and how fed, over the last year.
 *
 * Usage: kinds-by-ruggedness (--world NAME | --seed N [--width KM] [--height KM]) [--years N]  (the last year is reported)
 */
fun main(args: Array<String>) {
    val years = args.indexOf("--years").takeIf { it >= 0 }?.let { args[it + 1].toInt() } ?: 1
    val rest = args.toMutableList().also { i -> val j = i.indexOf("--years"); if (j >= 0) { i.removeAt(j); i.removeAt(j) } }
    val sim = FaunaCheckOptions.parse((rest + listOf("--years", "1")).toTypedArray()).start()
    val land = sim.land; val fauna = sim.fauna; val n = land.n
    val names = listOf("no crags", "crags < 0.25", "crags 0.25-0.5", "crags > 0.5")
    fun cls(p: Int): Int { val c = land.cragShelter[p]; return if (c <= 0f) 0 else if (c < 0.25f) 1 else if (c < 0.5f) 2 else 3 }
    val area = DoubleArray(4); for (p in 0 until n) area[cls(p)] += land.areaKm2[p].toDouble()
    val eaters = fauna.kinds.indices.filter { !fauna.kinds[it].carnivore }
    val heads = Array(fauna.kinds.size) { DoubleArray(4) }; val fed = Array(fauna.kinds.size) { DoubleArray(4) }
    var steps = 0
    val perYear = sim.stepsPerYear
    for (s in 0 until years * perYear) {
        sim.step()
        if (s < (years - 1) * perYear) continue
        steps++
        for (p in 0 until n) { val c = cls(p); val a = land.areaKm2[p].toDouble()
            for (k in eaters) { val h = (fauna.adults[k][p] + fauna.young[k][p]).toDouble() * a; if (!(h > 0.0)) continue
                heads[k][c] += h; fed[k][c] += h * fauna.fed[k][p] } }
    }
    println("kinds-by-ruggedness: last of $years years; heads/km² (fed) and share of the kind's heads, by share of crag ground")
    println("  land share: " + (0 until 4).joinToString(" | ") { "%s %.1f%%".format(names[it], 100 * area[it] / area.sum()) })
    for (k in eaters) {
        val all = heads[k].sum(); if (all <= 0.0) continue
        println("  %-12s ".format(fauna.kinds[k].name) + (0 until 4).joinToString(" | ") { c ->
            if (heads[k][c] <= 0.0) "-" else "%.2f (%.2f) %2.0f%%".format(heads[k][c] / steps / area[c], fed[k][c] / heads[k][c], 100 * heads[k][c] / all) })
    }
}
