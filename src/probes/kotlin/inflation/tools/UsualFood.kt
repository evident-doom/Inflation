package inflation.tools

import inflation.tools.fauna.FaunaCheckOptions
import inflation.world.Biome
import inflation.world.Place

/**
 * What sets each resident plant-eater's usual numbers ([inflation.ecology.HexFauna.usualAt]) where it stands: its
 * Damuth density ([inflation.ecology.HexFauna.usual]), the mean food over its own country that scaling is against
 * ([inflation.ecology.HexFauna.foodRef]), and, heads-weighted over the last year by kind of country as it is now, the
 * food it has there ([inflation.ecology.HexFauna.foodMean], kg/m² as standing plants), its usual numbers there and the
 * heads it stands at (a km²). Countries under 3% of a kind's heads are left out. For choosing one absolute food
 * yardstick in place of each kind's own country's mean.
 *
 * Usage: usual-food (--world NAME | --load FILE | --seed N ...) [--years N]  (the last year is reported)
 */
fun main(args: Array<String>) {
    val years = args.indexOf("--years").takeIf { it >= 0 }?.let { args[it + 1].toInt() } ?: 1
    val rest = args.toMutableList().also { i -> val j = i.indexOf("--years"); if (j >= 0) { i.removeAt(j); i.removeAt(j) } }
    val sim = FaunaCheckOptions.parse((rest + listOf("--years", "1")).toTypedArray()).start()
    val land = sim.land; val fauna = sim.fauna; val n = land.n
    val nk = fauna.kinds.size; val nb = Biome.entries.size
    // [kind][biome][0 heads, 1 heads x food, 2 heads x usualAt, 3 km² x weeks]; [kind][biome] km² of places holding it
    val acc = Array(nk) { Array(nb) { DoubleArray(4) } }
    // Food on offer to each kind over all land by country, area-weighted: [kind][biome][0 km², 1 km² x food].
    val all = Array(nk) { Array(nb) { DoubleArray(2) } }
    val perYear = sim.stepsPerYear; val total = years * perYear
    for (step in 0 until total) {
        sim.step()
        if (step < total - perYear) continue
        for ((k, s) in fauna.kinds.withIndex()) {
            val fm = fauna.foodMean[k]; if (fm.isEmpty()) continue
            for (p in 0 until n) {
                val f = fm[p]; if (f.isNaN()) continue
                val b = land.country.kind[p]; val a = land.areaKm2[p].toDouble()
                all[k][b][0] += a; all[k][b][1] += a * f
                val h = ((fauna.adults[k][p] + fauna.young[k][p]) * land.areaKm2[p]).toDouble(); if (h <= 0.0) continue
                val v = acc[k][b]
                v[0] += h; v[1] += h * f; v[2] += h * fauna.usualAt(k, Place(p)); v[3] += a
            }
        }
    }
    println("usual-food: last of $years years. Per plant-eater: Damuth usual /km², foodRef (mean food over its own country, kg/m²), and heads-weighted food, usualAt and density where it stands")
    println("then per country with 3% or more of its heads: share of heads | food kg/m² | usualAt /km² | heads /km² of the places holding it")
    for ((k, s) in fauna.kinds.withIndex()) {
        if (fauna.foodMean[k].isEmpty()) continue
        val hs = acc[k].sumOf { it[0] }
        val food = acc[k].sumOf { it[1] } / hs.coerceAtLeast(1e-9); val u = acc[k].sumOf { it[2] } / hs.coerceAtLeast(1e-9)
        println("%s (%.0f kg): usual %.3g, foodRef %.4f, food where it stands %.4f, usualAt %.3g, heads %.0f".format(s.name, s.massKg, fauna.usual[k], fauna.foodRef[k], food, u, hs / perYear))
        if (hs <= 0.0) continue
        for (b in Biome.entries.sortedByDescending { acc[k][it.ordinal][0] }) { val v = acc[k][b.ordinal]; if (v[0] < 0.03 * hs) continue
            println("  %-22s %4.0f%% | %.4f | %8.3g | %8.3g".format(b.label, 100 * v[0] / hs, v[1] / v[0], v[2] / v[0], v[0] / v[3])) }
    }
    println("food on offer by country, area-weighted over all its places (kg/m²), per plant-eater:")
    val countries = Biome.entries.filter { b -> all.any { it[b.ordinal][0] > 0.0 } }
    println("%-16s".format("kind") + countries.joinToString("") { "%14.14s".format(it.label) })
    for ((k, s) in fauna.kinds.withIndex()) { if (fauna.foodMean[k].isEmpty()) continue
        println("%-16.16s".format(s.name) + countries.joinToString("") { b -> val v = all[k][b.ordinal]; if (v[0] > 0.0) "%14.4f".format(v[1] / v[0]) else "%14s".format("-") }) }
}
