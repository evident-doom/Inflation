package inflation.tools

import inflation.ecology.madeFor
import inflation.tools.fauna.FaunaCheckOptions
import inflation.world.Biome
import inflation.world.Place

/**
 * What sets each resident plant-eater's usual numbers ([inflation.ecology.HexFauna.usualAt]) where it stands: its
 * Damuth density ([inflation.ecology.HexFauna.usual]), the mean food over its own country that scaling is against
 * ([inflation.ecology.HexFauna.foodRef]), and, heads-weighted over the last year by kind of country as it is now, the
 * food it has there ([inflation.ecology.HexFauna.foodMean], kg/m² as standing plants), its usual numbers there and the
 * heads it stands at (a km²). Countries under 3% of a kind's heads are left out. For choosing one absolute food
 * yardstick in place of each kind's own country's mean; and what each place grows of its food a year
 * ([inflation.ecology.madeFor]), gathered from the start of the run (run 2 years or more to have a year of it).
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
    val acc = Array(nk) { Array(nb) { DoubleArray(5) } }
    // Food on offer to each kind over all land by country, area-weighted: [kind][biome][0 km², 1 km² x food].
    val all = Array(nk) { Array(nb) { DoubleArray(3) } }
    val perYear = sim.stepsPerYear; val total = years * perYear
    for (step in 0 until total) {
        sim.step()
        if (step < total - perYear) continue
        for (k in fauna.kinds.indices) {
            val fm = fauna.foodMean[k]; if (fm.isEmpty()) continue
            for (p in 0 until n) {
                val f = fm[p]; if (f.isNaN()) continue
                val b = land.country.kind[p]; val a = land.areaKm2[p].toDouble()
                val made = fauna.madeFor(fauna.kinds[k], Place(p))
                all[k][b][0] += a; all[k][b][1] += a * f; all[k][b][2] += a * made
                val h = ((fauna.adults[k][p] + fauna.young[k][p]) * land.areaKm2[p]).toDouble(); if (h <= 0.0) continue
                val v = acc[k][b]
                v[0] += h; v[1] += h * f; v[2] += h * fauna.usualAt(k, Place(p)); v[3] += a; v[4] += h * made
            }
        }
    }
    println("usual-food: last of $years years. Per plant-eater: Damuth usual /km², foodRef (mean food over its own country, kg/m²), and heads-weighted food, usualAt and density where it stands")
    println("then per country with 3% or more of its heads: share of heads | food kg/m² | usualAt /km² | heads /km² of the places holding it | grows kg/m² a year (madeFor)")
    for ((k, s) in fauna.kinds.withIndex()) {
        if (fauna.foodMean[k].isEmpty()) continue
        val hs = acc[k].sumOf { it[0] }
        val food = acc[k].sumOf { it[1] } / hs.coerceAtLeast(1e-9); val u = acc[k].sumOf { it[2] } / hs.coerceAtLeast(1e-9)
        val made = acc[k].sumOf { it[4] } / hs.coerceAtLeast(1e-9)
        println("%s (%.0f kg): usual %.3g, foodRef %.4f, food where it stands %.4f, grows %.4f kg/m² a year, usualAt %.3g, heads %.0f".format(s.name, s.massKg, fauna.usual[k], fauna.foodRef[k], food, made, u, hs / perYear))
        if (hs <= 0.0) continue
        for (b in Biome.entries.sortedByDescending { acc[k][it.ordinal][0] }) { val v = acc[k][b.ordinal]; if (v[0] < 0.03 * hs) continue
            println("  %-22s %4.0f%% | %.4f | %8.3g | %8.3g | %.4f".format(b.label, 100 * v[0] / hs, v[1] / v[0], v[2] / v[0], v[0] / v[3], v[4] / v[0])) }
    }
    println("food on offer by country, area-weighted over all its places (kg/m²), per plant-eater:")
    val countries = Biome.entries.filter { b -> all.any { it[b.ordinal][0] > 0.0 } }
    println("%-16s".format("kind") + countries.joinToString("") { "%14.14s".format(it.label) })
    for ((k, s) in fauna.kinds.withIndex()) { if (fauna.foodMean[k].isEmpty()) continue
        println("%-16.16s".format(s.name) + countries.joinToString("") { b -> val v = all[k][b.ordinal]; if (v[0] > 0.0) "%14.4f".format(v[1] / v[0]) else "%14s".format("-") }) }
    println("what grows of each plant-eater's food by country, area-weighted (kg/m² a year, madeFor; gathered ${"%.2f".format(land.plants.madeYears)} years):")
    println("%-16s".format("kind") + countries.joinToString("") { "%14.14s".format(it.label) })
    for ((k, s) in fauna.kinds.withIndex()) { if (fauna.foodMean[k].isEmpty()) continue
        println("%-16.16s".format(s.name) + countries.joinToString("") { b -> val v = all[k][b.ordinal]; if (v[0] > 0.0) "%14.4f".format(v[2] / v[0]) else "%14s".format("-") }) }
    println("plant-eaters' heads and live weight by country, a km² of the country (all its places):")
    for (b in countries) { var heads = 0.0; var kg = 0.0; var km2 = 0.0
        for (p in 0 until n) if (land.country.kind[p] == b.ordinal) km2 += land.areaKm2[p]
        for ((k, s) in fauna.kinds.withIndex()) { if (fauna.foodMean[k].isEmpty()) continue
            for (p in 0 until n) if (land.country.kind[p] == b.ordinal) { val h = ((fauna.adults[k][p] + fauna.young[k][p]) * land.areaKm2[p]).toDouble(); heads += h; kg += h * s.massKg } }
        if (km2 > 0.0) println("  %-22s %8.0f km² | %7.2f heads | %7.0f kg a km²".format(b.label, km2, heads / km2, kg / km2)) }
}
