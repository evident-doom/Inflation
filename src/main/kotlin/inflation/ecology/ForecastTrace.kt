package inflation.ecology

import inflation.Config
import inflation.HASH_A
import inflation.mix64
import inflation.unitOf
import inflation.world.Date
import inflation.world.Weather

/**
 * forecastTrace=true: a sample of bands (1 in 50, the first [FORECAST_TRACE_MOST]) followed for a year from the week they are
 * first seen: what their forecast foresaw month by month ([yearAhead]), and what came, the food they got as [Band.monthSupply]
 * counts it, their larder, fat and body protein burnt, their people, deaths and moves; on stderr, once the year is over or
 * the band is gone. Whether the forecast's months are what came, where [bookCalibration] shows only that its deaths are not.
 */
internal val FORECAST_TRACE = Config.flag(Config.Key.FORECAST_TRACE, false)
private val forecastTraced = java.util.concurrent.atomic.AtomicInteger()
private const val FORECAST_TRACE_MOST = 300
private const val FORECAST_TRACE_SHARE = 0.02f
private const val FORECAST_TRACE_SALT = 0x46435452L

/** Starts following band [b] (group [g]) for [ForecastTrace] where it is drawn for it, once a band. */
internal fun HexGroups.traceForecast(g: HexGroup, b: Band, weather: Weather) {
    if (b.forecastTraced) return
    b.forecastTraced = true
    if (unitOf(mix64(g.id * HASH_A + FORECAST_TRACE_SALT)) >= FORECAST_TRACE_SHARE || forecastTraced.incrementAndGet() > FORECAST_TRACE_MOST) return
    val year = yearAhead(g, b, weather)
    val need = g.dailyNeedKg.coerceAtLeast(1e-3f)
    val m0 = weather.date.month
    val head = "forecast trace: band %d, %s, %s, %d people, larder %.1f days, fat %.1f, burnt %.1f, foreseen alive %.4f\n".format(
        g.id, weather.date, b.mind, g.size, g.larder / need, g.fat, g.spent, g.foreseen) +
        " foreseen, a usual year:\n" + year.trace(g.species, g.larder / need, g.fat, g.spent)
    val supply = FloatArray(Date.MONTHS_PER_YEAR) { b.monthSupply[(m0 + 1 + it) % Date.MONTHS_PER_YEAR] }
    val spread = FloatArray(Date.MONTHS_PER_YEAR) { b.supplySpread((m0 + 1 + it) % Date.MONTHS_PER_YEAR) }
    b.forecastTrace = ForecastTrace(head, m0, supply, spread, g.size)
}

/**
 * One band's year as [traceForecast] follows it: from the month after [startMonth], each month's food got against what it
 * foresaw ([supply] and its [spread], as [Band.monthSupply] counts it), and how it fared. [head]: what it foresaw.
 */
internal class ForecastTrace(private val head: String, private val startMonth: Int, private val supply: FloatArray,
    private val spread: FloatArray, private val startPeople: Int) {
    private val n = Date.MONTHS_PER_YEAR
    private val got = FloatArray(n); private val weeks = IntArray(n); private val moves = IntArray(n)
    private val larder = FloatArray(n); private val fat = FloatArray(n); private val burnt = FloatArray(n)
    private val people = IntArray(n); private val died = IntArray(n); private val starved = IntArray(n)
    private var month = startMonth
    /** The month of the year ahead it is in (0 for the first; -1 still in the month it started). */
    private var slot = -1

    /** A week in month [m]: the food it got ([got], as [Band.addSupply] has it), its larder (days), fat, burnt, people and whether it moved. */
    fun week(m: Int, got: Float, larder: Float, fat: Float, burnt: Float, people: Int, moved: Boolean) {
        if (m != month) { month = m; slot++ }
        if (slot < 0 || slot >= n) return
        if (weeks[slot] == 0) this.people[slot] = people
        this.got[slot] += got; weeks[slot]++; if (moved) moves[slot]++
        this.larder[slot] = larder; this.fat[slot] = fat; this.burnt[slot] = burnt
    }

    /** [k] of its people died this week, [hunger] whether of hunger. */
    fun died(k: Int, hunger: Boolean) {
        if (slot < 0 || slot >= n) return
        died[slot] += k; if (hunger) starved[slot] += k
    }

    /** Whether its year is over. */
    val over get() = slot >= n

    /** What it foresaw and what came, [end] how the following ended. */
    fun report(end: String): String {
        val sb = StringBuilder(head)
        sb.append(" what came (%s; got against foreseen, of the need before keeping warm; larder, fat and burnt at the month's end):\n".format(end))
        for (k in 0 until n) {
            if (weeks[k] == 0) break
            sb.append("  +%d: got %.2f foreseen %.2f±%.2f larder %.1f fat %.1f burnt %.1f people %d died %d hunger %d moved %d of %d weeks\n".format(
                k + 1, got[k] / weeks[k], supply[k], spread[k], larder[k], fat[k], burnt[k], people[k], died[k], starved[k], moves[k], weeks[k]))
        }
        sb.append("  people at the start %d; month %d\n".format(startPeople, startMonth))
        return sb.toString()
    }
}
