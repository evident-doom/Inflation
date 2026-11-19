package inflation.tools

import inflation.ecology.breeding
import inflation.ecology.saltBreeding
import inflation.ecology.seasonShare
import inflation.tools.fauna.FaunaCheckOptions
import inflation.world.Place
import kotlin.math.max
import kotlin.math.min

/**
 * What holds down the births of each plant-eating kind, by kind of country as its plants stand ([VegClass]), over the
 * last year's birth seasons, weighted by mothers: the share of mothers with a range to breed in (heads against
 * breedSpacing times the usual numbers there, [inflation.ecology.HexFauna.usualAt]), and of those the usual numbers' food
 * and hiding parts; the protein and sodium shares; what her food and fat let her build of what she is due
 * ([inflation.ecology.breeding]); and all of it together against her kind's most.
 *
 * Usage: birth-limits (--world NAME | --seed N [--width KM] [--height KM]) [--years N]  (the last year is reported)
 */
fun main(args: Array<String>) {
    val years = args.indexOf("--years").takeIf { it >= 0 }?.let { args[it + 1].toInt() } ?: 1
    val rest = args.toMutableList().also { i -> val j = i.indexOf("--years"); if (j >= 0) { i.removeAt(j); i.removeAt(j) } }
    val sim = FaunaCheckOptions.parse((rest + listOf("--years", "1")).toTypedArray()).start()
    val land = sim.land; val fauna = sim.fauna; val n = land.n
    val names = listOf("deer", "roe deer", "elk", "wild boar", "aurochs", "gazelle", "wild goat", "mouflon", "wild horse")
    val ks = names.mapNotNull { nm -> fauna.kinds.indexOfFirst { it.name == nm }.takeIf { it >= 0 } }
    val nc = VegClass.names.size
    // [kind][class]: mothers x share, ranges, food scale, hiding, protein, salt, breeding, all; heads (for the share of the kind).
    val acc = Array(fauna.kinds.size) { Array(nc) { DoubleArray(9) } }
    val cls = IntArray(n)
    val perYear = sim.stepsPerYear
    for (s in 0 until years * perYear) {
        if (s == (years - 1) * perYear) for (p in 0 until n) cls[p] = VegClass.of(land, p)
        if (s >= (years - 1) * perYear) {
            val date = sim.weather.date; val days = sim.clock.days
            for (p in 0 until n) { val pl = Place(p); val a = land.areaKm2[p].toDouble()
                for (k in ks) {
                    val sp = fauna.kinds[k]; val v = acc[k][cls[p]]
                    val heads = fauna.adults[k][p] + fauna.young[k][p]; if (!(heads > 0f)) continue
                    v[8] += heads * a
                    val share = seasonShare(sp, fauna.birthStart[k][p].toInt(), max(0f, 1f - fauna.bred[k][p]), date, days)
                    if (share <= 0f) continue
                    val m = 0.5 * fauna.adults[k][p] * a * share
                    val usual = fauna.usualAt(k, pl)
                    val ranges = min(1f, land.tuning.breedSpacing * usual / heads)
                    val prot = fauna.protein[k][p]; val salt = fauna.saltBreeding(sp, pl)
                    val due = ranges * sp.youngPerFemale * prot * salt * share
                    val b = breeding(sp, due, fauna.reserve[k][p], fauna.fed[k][p], days)
                    v[0] += m; v[1] += m * ranges; v[2] += m * fauna.foodScale(k, pl); v[3] += m * fauna.hidingShare(k, pl)
                    v[4] += m * prot; v[5] += m * salt; v[6] += m * b; v[7] += m * ranges * prot * salt * b
                }
            }
        }
        sim.step()
    }
    println("birth-limits: last of $years years, by kind of country as its plants stood at the year's start, weighted by mothers over the birth season")
    println("  share of the kind's heads | ranges (breed spacing x usual / heads) [food scale, hiding] | protein | sodium | food and fat | all")
    for (k in ks) {
        val all = acc[k].sumOf { it[8] }; if (all <= 0.0) continue
        println(fauna.kinds[k].name + " (most young a mother a year %.2f):".format(fauna.kinds[k].youngPerFemale))
        for (c in 0 until nc) { val v = acc[k][c]; if (v[8] / all < 0.01 || v[0] <= 0.0) continue
            println("  %-17s %3.0f%% | %.2f [%.2f, %.2f] | %.2f | %.2f | %.2f | %.2f".format(VegClass.names[c], 100 * v[8] / all,
                v[1] / v[0], v[2] / v[0], v[3] / v[0], v[4] / v[0], v[5] / v[0], v[6] / v[0], v[7] / v[0])) }
    }
}
