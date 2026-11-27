package inflation.tools

import inflation.tools.fauna.FaunaCheckOptions

/**
 * Can a kind come back from rare? The mutual-invasion test of coexistence (Chesson 2000): with the others where they
 * stand, one kind is cut to [cut] of its heads in every place, and the run goes on. A kind that grows back from rare has
 * a niche of its own; one that stays down or dwindles is held in only by whatever kept it up before (the breeding-range
 * cap, [inflation.Tuning.breedSpacing]). Run once with `--kind none` on the same world as the control: the cut kind's
 * yearly mean against the control's, year by year, is its recovery, free of the season and of drift.
 *
 * Prints, for every year after the cut, every animal kind's mean heads over that year (`year kind heads`), so the
 * control and each cut run can be set side by side.
 *
 * Usage: rare-recovery (--world NAME | --load FILE ...) --kind NAME|none [--cut 0.05] [--years 10]
 */
fun main(args: Array<String>) {
    fun opt(name: String): String? = args.indexOf(name).takeIf { it >= 0 }?.let { args[it + 1] }
    val kindName = requireNotNull(opt("--kind")) { "--kind NAME, or none for the control" }
    val cut = opt("--cut")?.toFloat() ?: 0.05f
    val years = opt("--years")?.toInt() ?: 10
    val rest = args.toMutableList()
    for (o in listOf("--kind", "--cut", "--years")) { val j = rest.indexOf(o); if (j >= 0) { rest.removeAt(j); rest.removeAt(j) } }
    val sim = FaunaCheckOptions.parse((rest + listOf("--years", "1")).toTypedArray()).start()
    val fauna = sim.fauna; val kinds = fauna.kinds; val n = sim.land.n
    val shown = kinds.indices.filter { !kinds[it].human }
    val cutK = if (kindName == "none") -1 else kinds.indexOfFirst { it.name.equals(kindName, ignoreCase = true) }
        .also { require(it >= 0) { "no kind named $kindName: ${kinds.joinToString { k -> k.name }}" } }
    fun heads(k: Int): Double { var h = 0.0; for (p in 0 until n) h += (fauna.adults[k][p] + fauna.young[k][p]).toDouble(); return h }
    val before = if (cutK >= 0) heads(cutK) else 0.0
    if (cutK >= 0) for (p in 0 until n) { fauna.adults[cutK][p] *= cut; fauna.young[cutK][p] *= cut }
    println("rare-recovery: kind ${if (cutK >= 0) kinds[cutK].name else "none (control)"}, cut to $cut (${"%.0f".format(before)} -> ${"%.0f".format(before * cut)} heads), $years years from ${sim.weather.date}")
    val startYear = sim.weather.date.year
    val sum = DoubleArray(kinds.size); var steps = 0; var year = startYear
    while (true) {
        sim.step()
        val y = sim.weather.date.year
        if (y != year) {
            for (k in shown) println("%d %s %.1f".format(year - startYear, kinds[k].name, sum[k] / steps.coerceAtLeast(1)))
            sum.fill(0.0); steps = 0; year = y
            if (y - startYear >= years) break
        }
        for (k in shown) sum[k] += heads(k)
        steps++
    }
}
