package inflation.tools

import inflation.ecology.Hazards
import inflation.ecology.tether
import inflation.tools.fauna.FaunaCheckOptions
import inflation.world.Biome
import inflation.world.Place

/**
 * Whether drinking water holds the resident plant-eaters where they stand, by kind of country as it is now
 * ([inflation.world.HexCountry.kind]) over the last year, heads-weighted: the share of head-weeks at places where the kind
 * can drink ([Hazards.canDrink]: water at the place, or within its fetch, [Hazards.fetchKm]); the mean km to water there
 * ([inflation.world.HexDrinkingWater.waterKm]) against its fetch; and its days gone dry ([inflation.ecology.HexFauna.dry]).
 * Countries holding under 2% of the kind's heads are left out. For telling whether a kind of wet country lives in the
 * steppe because the water there lets it. Also the share of the place's food the kind can use for the walk to water
 * ([inflation.ecology.tether]) and its share of wants found by eating ([inflation.ecology.HexFauna.fedByFood]), heads-weighted.
 * Also, land-weighted by month, the km to water and the share of ground within an
 * easy walk of it ([inflation.world.HexDrinkingWater.water]) in the dry kinds of country.
 *
 * Usage: water-by-kind (--world NAME | --load FILE | --seed N ...) [--years N]  (the last year is reported)
 */
fun main(args: Array<String>) {
    val years = args.indexOf("--years").takeIf { it >= 0 }?.let { args[it + 1].toInt() } ?: 1
    val rest = args.toMutableList().also { i -> val j = i.indexOf("--years"); if (j >= 0) { i.removeAt(j); i.removeAt(j) } }
    val sim = FaunaCheckOptions.parse((rest + listOf("--years", "1")).toTypedArray()).start()
    val land = sim.land; val fauna = sim.fauna; val n = land.n
    val nk = fauna.kinds.size; val nb = Biome.entries.size
    // [kind][biome][0 heads, 1 heads that can drink, 2 heads x km to water, 3 heads x dry days, 4 heads x tether, 5 heads x fed by food]
    val acc = Array(nk) { Array(nb) { DoubleArray(6) } }
    val dryKinds = listOf(Biome.STEPPE, Biome.SHRUBLAND, Biome.SEMI_DESERT, Biome.MEDITERRANEAN_WOODLAND)
    // [kind of country][month][0 area, 1 area x km, 2 area x water share]
    val byMonth = Array(dryKinds.size) { Array(12) { DoubleArray(3) } }
    val perYear = sim.stepsPerYear; val total = years * perYear
    for (step in 0 until total) {
        sim.step()
        if (step < total - perYear) continue
        val mo = sim.weather.lastMonth
        for (p in 0 until n) { val b = dryKinds.indexOfFirst { it.ordinal == land.country.kind[p] }; if (b < 0) continue
            val a = land.areaKm2[p].toDouble(); val v = byMonth[b][mo]; v[0] += a; v[1] += a * land.drink.waterKm[p]; v[2] += a * land.drink.water[p] }
        for ((k, s) in fauna.kinds.withIndex()) {
            if (s.carnivore) continue
            for (p in 0 until n) {
                val h = ((fauna.adults[k][p] + fauna.young[k][p]) * land.areaKm2[p]).toDouble(); if (h <= 0.0) continue
                val v = acc[k][land.country.kind[p]]
                v[0] += h
                if (Hazards.canDrink(land.drink, s, Place(p))) v[1] += h
                v[2] += h * land.drink.waterKm[p]; v[3] += h * fauna.dry[k][p]
                v[4] += h * fauna.tether(s, Place(p)); v[5] += h * fauna.fedByFood[k][p]
            }
        }
    }
    println("water-by-kind: last of $years years; per kind, countries with 2% or more of its heads: share of its heads | can drink | km to water (its fetch) | days dry | food usable for water | fed by food")
    for ((k, s) in fauna.kinds.withIndex()) {
        if (s.carnivore) continue
        val all = acc[k].sumOf { it[0] }; if (all <= 0.0) continue
        println("%s (fetch %.0f km, %d days without water, %.1f water from food):".format(s.name, Hazards.fetchKm(s), s.daysWithoutWater, s.waterFromFood))
        for (b in Biome.entries.sortedByDescending { acc[k][it.ordinal][0] }) { val v = acc[k][b.ordinal]; if (v[0] < 0.02 * all) continue
            println("  %-22s %4.0f%% | %4.0f%% | %6.1f km | %5.2f | %4.2f | %4.2f".format(b.label, 100 * v[0] / all, 100 * v[1] / v[0], v[2] / v[0], v[3] / v[0], v[4] / v[0], v[5] / v[0])) }
    }
    println("dry country by month (Jan-Dec): km to water | share of ground within an easy walk of water")
    for ((b, kind) in dryKinds.withIndex()) {
        println("  %-22s km    ".format(kind.label) + (0 until 12).joinToString(" ") { val v = byMonth[b][it]; if (v[0] > 0) "%5.1f".format(v[1] / v[0]) else "    -" })
        println("  %-22s water ".format("") + (0 until 12).joinToString(" ") { val v = byMonth[b][it]; if (v[0] > 0) "%5.2f".format(v[2] / v[0]) else "    -" })
    }
}
