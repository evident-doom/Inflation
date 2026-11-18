package inflation.tools

import inflation.ecology.breeding
import inflation.tools.fauna.FaunaCheckOptions
import inflation.world.Place
import inflation.world.get

/**
 * What holds the resident hunters' births, over a year: for each kind, head-weighted over the land and the weeks, how
 * well fed they were, their fat against the most they carry, the share of mothers with a range of their own to breed in
 * (the breeding spacing against the ground the game holds, [usualAt]), and the share of a week's due young their food
 * and fat could build ([breeding]); with how crowded they stand against the ground.
 *
 * Usage: hunter-breeding (--world NAME | --seed N [--width KM] [--height KM]) [--years N]  (the last year is reported)
 */
fun main(args: Array<String>) {
    val years = args.indexOf("--years").takeIf { it >= 0 }?.let { args[it + 1].toInt() } ?: 1
    val rest = args.toMutableList().also { i -> val j = i.indexOf("--years"); if (j >= 0) { i.removeAt(j); i.removeAt(j) } }
    val sim = FaunaCheckOptions.parse((rest + listOf("--years", "1")).toTypedArray()).start()
    val land = sim.land; val fauna = sim.fauna; val n = land.n
    val hunters = fauna.kinds.indices.filter { fauna.kinds[it].carnivore }
    // [kind][measure]: heads, fed, fat share, ranges, energy, crowd against ground.
    val acc = Array(fauna.kinds.size) { DoubleArray(6) }
    val perYear = sim.stepsPerYear
    for (s in 0 until years * perYear) {
        sim.step()
        if (s < (years - 1) * perYear) continue
        val days = sim.clock.days
        for (k in hunters) {
            val sp = fauna.kinds[k]; val v = acc[k]
            val due = sp.youngPerFemale * days / (sp.birthMonths * 30f)
            for (p in 0 until n) {
                val heads = fauna.adults[k][p] + fauna.young[k][p]; if (!(heads > 0f)) continue
                val w = (heads * land.areaKm2[p]).toDouble(); val u = fauna.usualAt(k, Place(p))
                v[0] += w; v[1] += w * fauna.fed[k][p]; v[2] += w * (fauna.reserve[k][p] / sp.maxFatDays.coerceAtLeast(1e-3f))
                v[3] += w * minOf(1f, fauna.tuning.breedSpacing * u / heads)
                v[4] += w * breeding(sp, due, fauna.reserve[k][p], fauna.fed[k][p], days)
                v[5] += w * heads / u
            }
        }
    }
    println("kind: fed | fat of most | mothers with a range | due young food and fat build | heads against the ground's")
    for (k in hunters) { val v = acc[k]; val h = v[0].coerceAtLeast(1e-9)
        println("%s: %.2f | %.2f | %.2f | %.2f | %.2f".format(fauna.kinds[k].name, v[1] / h, v[2] / h, v[3] / h, v[4] / h, v[5] / h)) }
}
