package inflation.tools

import inflation.ecology.Hazards
import inflation.ecology.drinks
import inflation.ecology.tether
import inflation.tools.fauna.FaunaCheckOptions
import inflation.world.Biome
import inflation.world.Place

/**
 * How far the plant-eaters stand from water, and what the water rules leave them, by kind of country, over the last
 * year. For asking whether water keeps the daily drinkers (aurochs, boar) off the dry steppe as it should.
 *
 * Land: by kind of country, the share of the land within 2, 5, 8, 15 and 40 km of water ([HexDrinkingWater.animalWaterKm])
 * and its mean, area-weighted, and the share where water stands ([HexDrinkingWater.animalWater] >= 0.05).
 * Kinds: the two distances each kind's rules turn on, the tether's half-way km (the place's pull is halved there,
 * [tether]) and the walk within which it never goes dry ([Hazards.fetchKm]); then by kind of country, weighted by the
 * heads standing there: heads a km², the share of the kind's heads, the mean km to water, the mean tether, the share
 * that can drink where they stand ([drinks]), the share gone dry at all (days without water > 0) and the mean fed.
 * Last, per kind over all land, the same month by month.
 *
 * Usage: water-tether (--world NAME | --seed N [--width KM] [--height KM]) [--years N]  (the last year is reported)
 */
fun main(args: Array<String>) {
    val years = args.indexOf("--years").takeIf { it >= 0 }?.let { args[it + 1].toInt() } ?: 3
    val rest = args.toMutableList().also { i -> val j = i.indexOf("--years"); if (j >= 0) { i.removeAt(j); i.removeAt(j) } }
    val sim = FaunaCheckOptions.parse((rest + listOf("--years", "1")).toTypedArray()).start()
    val land = sim.land; val fauna = sim.fauna; val hw = sim.hw; val n = land.n
    val biomes = Biome.entries
    val area = DoubleArray(biomes.size)
    for (p in 0 until n) area[hw.placeBiome[p]] += land.areaKm2[p].toDouble()
    val shown = biomes.indices.filter { area[it] > 0.005 * area.sum() }.sortedByDescending { area[it] }
    // (Plant-eaters that drink: not the kinds bound to deep water, whose tether is the water itself.)
    val eaters = fauna.kinds.indices.filter { !fauna.kinds[it].carnivore && fauna.kinds[it].maxWaterKm.isInfinite() }
    val bands = floatArrayOf(2f, 5f, 8f, 15f, 40f)
    // Land [biome]: within each band (5), km sum, standing water, area.
    val landAcc = Array(biomes.size) { DoubleArray(8) }
    // Kinds [kind][biome]: heads, heads*km, heads*tether, heads drinking, heads dry, heads*fed, area-steps.
    val kindAcc = Array(fauna.kinds.size) { Array(biomes.size) { DoubleArray(6) } }
    // Kinds by month [kind][month]: heads, heads*km, heads*tether, heads drinking, heads dry, heads*fed.
    val monthAcc = Array(fauna.kinds.size) { Array(12) { DoubleArray(6) } }
    var steps = 0
    val perYear = sim.stepsPerYear
    for (s in 0 until years * perYear) {
        sim.step()
        if (s < (years - 1) * perYear) continue
        steps++
        val mo = sim.weather.date.month
        for (p in 0 until n) {
            val pl = Place(p); val b = hw.placeBiome[p]; val a = land.areaKm2[p].toDouble()
            val km = land.drink.animalWaterKm(pl)
            val l = landAcc[b]
            for (i in bands.indices) if (km <= bands[i]) l[i] += a
            l[5] += km * a
            if (land.drink.animalWater(pl) >= 0.05f) l[6] += a
            l[7] += a
            for (k in eaters) {
                val h = (fauna.adults[k][p] + fauna.young[k][p]).toDouble()
                if (h <= 0.0) continue
                val sp = fauna.kinds[k]
                val t = fauna.tether(sp, pl).toDouble()
                val dr = if (fauna.drinks(sp, pl)) h else 0.0
                val dry = if (fauna.dry[k][p] > 0f) h else 0.0
                val fed = fauna.fed[k][p].toDouble()
                for (v in arrayOf(kindAcc[k][b], monthAcc[k][mo])) {
                    v[0] += h; v[1] += h * km; v[2] += h * t; v[3] += dr; v[4] += dry; v[5] += h * fed
                }
            }
        }
    }
    println("water-tether: last of $years years, $steps steps")
    println()
    println("LAND by kind of country (area-weighted over the year): share within 2 / 5 / 8 / 15 / 40 km of water | mean km | share with water standing")
    for (b in shown) {
        val l = landAcc[b]; val a = l[7]
        println("  %-24s %4.1f%% of land: ".format(biomes[b].label, 100 * area[b] / area.sum()) +
            (0 until 5).joinToString(" / ") { "%3.0f%%".format(100 * l[it] / a) } + " | %5.1f km | %3.0f%%".format(l[5] / a, 100 * l[6] / a))
    }
    println()
    println("KINDS: tether half km (pull halved) and never-dry walk km (Hazards.fetchKm); then by kind of country, by heads standing there:")
    println("  heads/km² | share of kind | mean km to water | mean tether | can drink here | gone dry at all | fed")
    for (k in eaters) {
        val sp = fauna.kinds[k]
        // (The tether's reach, as ResidentHabitat.tetherAt has it: the pull is halved at this distance.)
        val half = sp.dailyTravelKm * sp.daysWithoutWater / 4f * (1f + 10f * sp.waterFromFood * sp.waterFromFood)
        val all = kindAcc[k].sumOf { it[0] }
        if (all <= 0.0) { println("${sp.name}: none"); continue }
        println("${sp.name}: tether half %.1f km, never dry within %.1f km, days without water %d, water from food %.2f".format(
            half, Hazards.fetchKm(sp), sp.daysWithoutWater, sp.waterFromFood))
        for (b in shown) {
            val v = kindAcc[k][b]; val h = v[0]
            if (h / all < 0.005) continue
            println("  %-24s %8.3f | %3.0f%% | %5.1f km | %.2f | %3.0f%% | %3.0f%% | %.2f".format(biomes[b].label,
                h / steps / area[b], 100 * h / all, v[1] / h, v[2] / h, 100 * v[3] / h, 100 * v[4] / h, v[5] / h))
        }
    }
    println()
    println("KINDS by month over all land (mean km to water | mean tether | can drink here | gone dry at all | fed):")
    for (k in eaters) {
        val sp = fauna.kinds[k]
        if (monthAcc[k].sumOf { it[0] } <= 0.0) continue
        println(sp.name + ": " + (0 until 12).joinToString("  ") { m -> val v = monthAcc[k][m]; val h = v[0]
            if (h <= 0.0) "$m -" else "$m %.1f/%.2f/%.0f%%/%.0f%%/%.2f".format(v[1] / h, v[2] / h, 100 * v[3] / h, 100 * v[4] / h, v[5] / h) })
    }
}
