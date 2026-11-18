package inflation.tools

import inflation.ecology.Hazards
import inflation.ecology.sharersKm2
import inflation.ecology.wetSickness
import inflation.ecology.FaunaKind
import inflation.tools.fauna.FaunaCheckOptions
import inflation.world.Place
import inflation.world.get

/**
 * What the residents' sickness is made of, over the last year: for each plant-eating kind, head-weighted over the land
 * and the weeks, its own heads and those that share its parasites against the crowd the backstop starts at, the yearly
 * deaths the crowding backstop and the damp ground would take there, how damp the ground is, and how well fed; and the
 * share of its heads standing past the backstop's crowd.
 *
 * Usage: sickness-split (--world NAME | --seed N [--width KM] [--height KM]) [--years N]  (the last year is reported)
 */
fun main(args: Array<String>) {
    val years = args.indexOf("--years").takeIf { it >= 0 }?.let { args[it + 1].toInt() } ?: 1
    val rest = args.toMutableList().also { i -> val j = i.indexOf("--years"); if (j >= 0) { i.removeAt(j); i.removeAt(j) } }
    val sim = FaunaCheckOptions.parse((rest + listOf("--years", "1")).toTypedArray()).start()
    val land = sim.land; val fauna = sim.fauna; val n = land.n
    val eaters = fauna.kinds.indices.filter { !fauna.kinds[it].carnivore }
    // [kind][measure]: heads, own crowd, shared crowd, backstop a year, damp a year, damp, fed, heads past the crowd.
    val acc = Array(fauna.kinds.size) { DoubleArray(8) }
    val perYear = sim.stepsPerYear
    for (s in 0 until years * perYear) {
        sim.step()
        if (s < (years - 1) * perYear) continue
        for (k in eaters) {
            val sp = fauna.kinds[k]; val v = acc[k]; val u = fauna.usual[k]
            for (p in 0 until n) {
                val heads = fauna.adults[k][p] + fauna.young[k][p]; if (!(heads > 0f)) continue
                val pl = Place(p); val w = (heads * land.areaKm2[p]).toDouble()
                val others = fauna.sharersKm2(FaunaKind(k), pl)
                val own = Hazards.crowding(heads, u); val crowd = Hazards.crowding(heads + others, u)
                val fed = fauna.fed[k][p]
                v[0] += w; v[1] += w * own; v[2] += w * crowd
                v[3] += w * Hazards.crowdBackstop(crowd, fed, 1f); v[4] += w * fauna.wetSickness(sp, pl, fed)
                v[5] += w * fauna.damp[p]; v[6] += w * fed; if (crowd > 1f) v[7] += w
            }
        }
    }
    println("kind: own crowd | with sharers | backstop deaths a year | damp deaths a year | damp | fed | heads past the crowd")
    for (k in eaters) { val v = acc[k]; val h = v[0].coerceAtLeast(1e-9)
        println("%s: %.2f | %.2f | %.3f | %.3f | %.2f | %.2f | %.0f%%".format(fauna.kinds[k].name, v[1] / h, v[2] / h, v[3] / h, v[4] / h, v[5] / h, v[6] / h, 100 * v[7] / h)) }
}
