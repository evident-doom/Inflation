package inflation.tools

import inflation.ecology.Fed
import inflation.tools.fauna.FaunaCheckOptions

/**
 * What each plant-eating kind lives on, over the whole map by quarter of the last year, heads-weighted
 * ([inflation.ecology.HexFauna.fedFrom]): how fed, and the shares of its need met by grass, browse, fruit, roots and mast,
 * and animal food; and each kind's share of all the browse the residents ate in the quarter. For telling which kinds
 * eat the shoots, and what a browser short of them makes up with.
 *
 * Usage: diet-by-kind (--world NAME | --seed N [--width KM] [--height KM]) [--years N]  (the last year is reported)
 */
fun main(args: Array<String>) {
    val years = args.indexOf("--years").takeIf { it >= 0 }?.let { args[it + 1].toInt() } ?: 1
    val rest = args.toMutableList().also { i -> val j = i.indexOf("--years"); if (j >= 0) { i.removeAt(j); i.removeAt(j) } }
    val sim = FaunaCheckOptions.parse((rest + listOf("--years", "1")).toTypedArray()).start()
    val fauna = sim.fauna; val nk = fauna.kinds.size
    val eaters = fauna.kinds.indices.filter { !fauna.kinds[it].carnivore }
    val parts = listOf(Fed.HEADS, Fed.PLANTS, Fed.ANIMAL, Fed.GRASS, Fed.BROWSE, Fed.FRUIT, Fed.UNDERGROUND, Fed.BROWSE_KG)
    // [kind][quarter][part], then fed x heads and heads (area-weighted) over the steps.
    val acc = Array(nk) { Array(4) { DoubleArray(parts.size + 2) } }
    val perYear = sim.stepsPerYear
    for (s in 0 until years * perYear) {
        for (ff in fauna.fedFrom) for (a in ff) a.reset()
        val qtr = sim.weather.date.month / 3
        sim.step()
        if (s < (years - 1) * perYear) continue
        for (k in eaters) {
            val ff = fauna.fedFrom[k]; val v = acc[k][qtr]
            for ((i, f) in parts.withIndex()) v[i] += ff[f.ordinal].sum()
            var fh = 0.0; var hh = 0.0
            for (p in 0 until sim.land.n) { val h = (fauna.adults[k][p] + fauna.young[k][p]).toDouble() * sim.land.areaKm2[p]; hh += h; fh += h * fauna.fed[k][p] }
            v[parts.size] += fh; v[parts.size + 1] += hh
        }
    }
    println("diet-by-kind: last of $years years, whole map, by quarter (Jan-Mar, Apr-Jun, Jul-Sep, Oct-Dec)")
    println("  heads-weighted: fed | of need met by plants: all = grass + browse + fruit + roots/mast | animal | share of residents' browse eaten")
    val browseAll = DoubleArray(4) { q -> eaters.sumOf { acc[it][q][parts.indexOf(Fed.BROWSE_KG)] } }
    val heads = DoubleArray(nk) { k -> acc[k].sumOf { it[0] } }
    for (k in eaters.filter { heads[it] > 0.0 }.sortedByDescending { heads[it] }) {
        println(fauna.kinds[k].name + ":")
        for (q in 0 until 4) { val v = acc[k][q]; val h = v[0]; if (h <= 0.0) continue
            println("  Q${q + 1}  %.2f | %.2f = %.2f + %.2f + %.2f + %.2f | %.2f | %4.1f%%".format(
                v[8] / v[9].coerceAtLeast(1e-30), v[1] / h, v[3] / h, v[4] / h, v[5] / h, v[6] / h, v[2] / h, 100 * v[7] / browseAll[q].coerceAtLeast(1e-30))) }
    }
}
