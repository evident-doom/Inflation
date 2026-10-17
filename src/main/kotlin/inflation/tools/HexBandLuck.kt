package inflation.tools

import inflation.ecology.Catalogue
import inflation.ecology.DeathCause
import inflation.ecology.VISIT_FED
import inflation.tools.fauna.FaunaCheckOptions
import java.util.TreeMap
import kotlin.math.sqrt

/**
 * Whether bands' bad weeks come at the same time as their neighbours': the question any sharing
 * between bands turns on (help is worth asking for only from a band that isn't short itself). Carries
 * on a world as hex-fauna-check does and, week by week, notes each band's shortfall (1 - fed) and
 * where it is. At the end it prints:
 * - how often bands are short, and hungry as kin visits count it (fed under [VISIT_FED] with under a
 *   week's food laid by), after this week's sharing;
 * - of those hungry band-weeks, the share with a band able to help (not hungry, with a week of its own
 *   food and the hungry band's gap to spare) within 20, 50, 100 and 200 km;
 * - the correlation of two bands' weekly shortfalls, by the distance between them (pairs that lived
 *   side by side for at least half a year);
 * - people's deaths by cause, so hunger can be weighed against the rest.
 *
 * Usage: hex-band-luck --world NAME [--years N] [--replicate R] (any hex-fauna-check option)
 */
fun main(args: Array<String>) {
    val options = FaunaCheckOptions.parse(args)
    val sim = options.start()
    val gs = sim.groups; val hex = sim.hex
    val weeks = options.years * 365 / sim.cfg.daysPerStep
    // Each band's shortfall by week (NaN while it is not about), and the sum of its positions.
    val short = HashMap<Int, FloatArray>(); val sx = HashMap<Int, Double>(); val sy = HashMap<Int, Double>(); val seen = HashMap<Int, Int>()
    val reach = doubleArrayOf(20.0, 50.0, 100.0, 200.0)
    var bandWeeks = 0L; var shortWeeks = 0L; var hungryWeeks = 0L
    val helpWithin = LongArray(reach.size)
    var fedByKin = 0L
    val deaths = TreeMap<String, Long>()
    val people = Catalogue.HUMAN.name
    for (w in 0 until weeks) {
        sim.step()
        fedByKin += gs.stats.meetings["hungry bands fed by kin"] ?: 0
        for ((k, n) in gs.stats.deaths) if (k.startsWith("$people:")) deaths.merge(k.removePrefix("$people:"), n.toLong(), Long::plus)
        val bands = gs.groups.filter { it.alive && it.band != null }
        val hexOf = IntArray(bands.size) { sim.hw.placeHex[bands[it].place.i] }
        val week = bands.map { it.dailyNeedKg * sim.cfg.daysPerStep }
        val hungry = BooleanArray(bands.size) { bands[it].fed < VISIT_FED && bands[it].larder < week[it] }
        for ((i, g) in bands.withIndex()) {
            bandWeeks++
            val s = (1f - g.fed).coerceIn(0f, 1f)
            if (s > 0f) shortWeeks++
            short.getOrPut(g.id) { FloatArray(weeks) { Float.NaN } }[w] = s
            val (x, y) = hex.centreKm(hexOf[i])
            sx.merge(g.id, x, Double::plus); sy.merge(g.id, y, Double::plus); seen.merge(g.id, 1, Int::plus)
            if (!hungry[i]) continue
            hungryWeeks++
            val gap = (1f - g.fed) * week[i]
            var nearest = Double.MAX_VALUE
            for ((j, o) in bands.withIndex()) if (j != i && !hungry[j] && o.larder >= week[j] + gap) {
                val km = hex.distanceKm(hexOf[i], hexOf[j]); if (km < nearest) nearest = km
            }
            for (r in reach.indices) if (nearest <= reach[r]) helpWithin[r]++
        }
    }
    println("band-weeks %d; short (fed < 1) %.1f%%; hungry after sharing %.1f%%; hungry bands fed by kin %d".format(
        bandWeeks, 100.0 * shortWeeks / bandWeeks, 100.0 * hungryWeeks / bandWeeks, fedByKin))
    for (r in reach.indices) println("hungry band-weeks with a band able to help within %.0f km: %.1f%%".format(reach[r], 100.0 * helpWithin[r] / hungryWeeks.coerceAtLeast(1)))
    // Correlation of shortfalls by distance between the bands' mean positions.
    val ids = short.keys.filter { seen.getValue(it) >= 26 }
    val edges = doubleArrayOf(20.0, 50.0, 100.0, 200.0, 400.0, Double.MAX_VALUE)
    val sum = DoubleArray(edges.size); val n = IntArray(edges.size)
    for (a in ids.indices) for (b in a + 1 until ids.size) {
        val ia = ids[a]; val ib = ids[b]
        val km = sqrt(Math.pow(sx.getValue(ia) / seen.getValue(ia) - sx.getValue(ib) / seen.getValue(ib), 2.0) +
            Math.pow(sy.getValue(ia) / seen.getValue(ia) - sy.getValue(ib) / seen.getValue(ib), 2.0))
        val r = correlation(short.getValue(ia), short.getValue(ib)) ?: continue
        val bin = edges.indexOfFirst { km < it }
        sum[bin] += r; n[bin]++
    }
    var lo = 0.0
    for (k in edges.indices) {
        val hi = if (edges[k] == Double.MAX_VALUE) "" else "%.0f".format(edges[k])
        println("shortfall correlation %s-%s km: %s (%d pairs)".format("%.0f".format(lo), hi, if (n[k] > 0) "%.2f".format(sum[k] / n[k]) else "-", n[k]))
        lo = edges[k]
    }
    val total = deaths.values.sum().coerceAtLeast(1)
    println("people's deaths: %d; ".format(total) + deaths.entries.sortedByDescending { it.value }.take(10).joinToString(", ") { "%s %.1f%%".format(it.key, 100.0 * it.value / total) })
    println("hunger's share of people's deaths: %.1f%%".format(100.0 * ((deaths[DeathCause.HUNGER.label] ?: 0L) + (deaths[DeathCause.ORPHANED.label] ?: 0L)) / total))
}

/** Pearson correlation over the weeks both series have (at least 26, with some spread in each), or null. */
private fun correlation(a: FloatArray, b: FloatArray): Double? {
    var n = 0; var ma = 0.0; var mb = 0.0
    for (i in a.indices) if (!a[i].isNaN() && !b[i].isNaN()) { n++; ma += a[i]; mb += b[i] }
    if (n < 26) return null
    ma /= n; mb /= n
    var cab = 0.0; var caa = 0.0; var cbb = 0.0
    for (i in a.indices) if (!a[i].isNaN() && !b[i].isNaN()) { val da = a[i] - ma; val db = b[i] - mb; cab += da * db; caa += da * da; cbb += db * db }
    if (caa < 1e-9 || cbb < 1e-9) return null
    return cab / sqrt(caa * cbb)
}
