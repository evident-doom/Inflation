package inflation.tools

import inflation.tools.fauna.FaunaCheckOptions
import inflation.world.Phase
import inflation.world.Place

/**
 * Where the plant-eaters' food goes, by kind of country as its plants stand ([VegClass]) and quarter of the last year: each phase of the step (the plants'
 * growth, the residents' grazing, the soil and small life, the food stocks' seed and roots, the bands ...) and what it
 * added to or took from the herbs standing and the browse (the shoots within reach), g/m² over the quarter, area-weighted.
 * The phases are wrapped as they run: herbs and browse at every place before and after each. Then by kind of country the
 * plant-eaters' heads a km² and how fed, and their weight, kg/km².
 *
 * Usage: forage-budget (--world NAME | --seed N [--width KM] [--height KM]) [--years N]  (the last year is reported)
 */
fun main(args: Array<String>) {
    val years = args.indexOf("--years").takeIf { it >= 0 }?.let { args[it + 1].toInt() } ?: 1
    val rest = args.toMutableList().also { i -> val j = i.indexOf("--years"); if (j >= 0) { i.removeAt(j); i.removeAt(j) } }
    val sim = FaunaCheckOptions.parse((rest + listOf("--years", "1")).toTypedArray()).start()
    val land = sim.land; val fauna = sim.fauna; val n = land.n
    // (Places by their plants as they stand at the start of the reported year, [VegClass].)
    val names0 = VegClass.names
    val cls = IntArray(n)
    val area = DoubleArray(names0.size)
    val names = ArrayList<String>()
    // [phase][class][quarter][herb, browse]: kg added (area km² x kg/m²), summed over the quarter's steps.
    val flows = ArrayList<Array<Array<DoubleArray>>>()
    var recording = false
    var qtr = 0
    val herb0 = DoubleArray(n); val browse0 = DoubleArray(n)
    fun wrap(list: List<Phase>) {
        @Suppress("UNCHECKED_CAST") val ml = list as MutableList<Phase>
        for (i in ml.indices) {
            val ph = ml[i]
            val idx = names.indexOf(ph.name).let { if (it >= 0) it else { names += ph.name; flows += Array(names0.size) { Array(4) { DoubleArray(2) } }; names.size - 1 } }
            ml[i] = Phase(ph.name) { w ->
                if (recording) for (p in 0 until n) { herb0[p] = land.plants.herb[p].toDouble(); browse0[p] = land.plants.browse(Place(p)).toDouble() }
                ph.run(w)
                if (recording) {
                    val f = flows[idx]
                    for (p in 0 until n) {
                        val a = land.areaKm2[p].toDouble(); val v = f[cls[p]][qtr]
                        v[0] += a * (land.plants.herb[p] - herb0[p]); v[1] += a * (land.plants.browse(Place(p)) - browse0[p])
                    }
                }
            }
        }
    }
    wrap(sim.tickPhases); wrap(sim.slowPhases)
    val eaters = fauna.kinds.indices.filter { !fauna.kinds[it].carnivore }
    // [kind][class]: heads (area x density, summed over steps), heads x fed; [class]: weight kg (summed over steps), steps.
    val heads = Array(fauna.kinds.size) { DoubleArray(names0.size) }; val fed = Array(fauna.kinds.size) { DoubleArray(names0.size) }
    val stock = Array(names0.size) { Array(4) { DoubleArray(3) } }
    // Small life standing, kg/km² x area summed over steps [class][quarter]: insects, rodents, hares, hyrax, tortoises, locusts.
    val small = Array(names0.size) { Array(4) { DoubleArray(6) } }
    val stepsIn = IntArray(4); var steps = 0
    val perYear = sim.stepsPerYear
    for (s in 0 until years * perYear) {
        recording = s >= (years - 1) * perYear
        if (s == (years - 1) * perYear) for (p in 0 until n) { cls[p] = VegClass.of(land, p); area[cls[p]] += land.areaKm2[p].toDouble() }
        qtr = sim.weather.date.month / 3
        sim.step()
        if (!recording) continue
        stepsIn[qtr]++; steps++
        for (p in 0 until n) {
            val b = cls[p]; val a = land.areaKm2[p].toDouble()
            val st = stock[b][qtr]; st[0] += a * land.plants.herb[p]; st[1] += a * land.palatable[p] * land.plants.herb[p]; st[2] += a * land.plants.browse(Place(p))
            val sl = fauna.small; val sm = small[b][qtr]
            sm[0] += a * sl.insects[p]; sm[1] += a * sl.rodents[p]; sm[2] += a * sl.hares[p]; sm[3] += a * sl.hyrax[p]; sm[4] += a * sl.tortoises[p]; sm[5] += a * sl.locusts[p]
            for (k in eaters) { val h = (fauna.adults[k][p] + fauna.young[k][p]).toDouble() * a; if (h <= 0.0) continue
                heads[k][b] += h; fed[k][b] += h * fauna.fed[k][p] }
        }
    }
    val shown = names0.indices.filter { area[it] > 0.02 * area.sum() }.sortedByDescending { area[it] }
    println("forage-budget: last of $years years, by kind of country as its plants stood at the year's start (tree canopy, shrubs)")
    for (b in shown) {
        println()
        println("${names0[b]} (%.1f%% of land): by quarter (Jan-Mar, Apr-Jun, Jul-Sep, Oct-Dec)".format(100 * area[b] / area.sum()))
        println("  standing, g/m² (mean): herbs / edible herbs / browse within reach")
        for (q in 0 until 4) { val st = stock[b][q]; val d = area[b] * stepsIn[q].coerceAtLeast(1) / 1000.0
            println("    Q${q + 1}  %.1f / %.1f / %.2f".format(st[0] / d, st[1] / d, st[2] / d)) }
        println("  herbs and browse added (+) or taken (-) by each phase, g/m² over the quarter: Q1 | Q2 | Q3 | Q4")
        for ((i, nm) in names.withIndex()) {
            val f = flows[i][b]
            if ((0 until 4).all { kotlin.math.abs(f[it][0]) + kotlin.math.abs(f[it][1]) < 1e-9 * area[b] }) continue
            println("    %-34s herbs ".format(nm) + (0 until 4).joinToString(" | ") { "%+.2f".format(1000 * f[it][0] / area[b]) } +
                "   browse " + (0 until 4).joinToString(" | ") { "%+.3f".format(1000 * f[it][1] / area[b]) })
        }
        println("  small life standing, kg/km²: insects / rodents / hares / hyrax / tortoises / locusts")
        for (q in 0 until 4) { val sm = small[b][q]; val d = area[b] * stepsIn[q].coerceAtLeast(1)
            println("    Q${q + 1}  " + (0 until 6).joinToString(" / ") { "%.0f".format(sm[it] / d) }) }
        println("  plant-eaters: heads/km² (fed), year mean")
        println("    " + eaters.filter { heads[it][b] / steps / area[b] > 1e-3 }.joinToString(", ") { k ->
            "${fauna.kinds[k].name} %.2f (%.2f)".format(heads[k][b] / steps / area[b], fed[k][b] / heads[k][b]) })
        println("    weight %.0f kg/km²".format(eaters.sumOf { heads[it][b] * fauna.kinds[it].massKg } / steps / area[b]))
    }
}
