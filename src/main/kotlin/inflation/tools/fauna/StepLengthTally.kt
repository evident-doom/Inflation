package inflation.tools.fauna

import inflation.ecology.Hazards
import inflation.ecology.HexGroup
import inflation.ecology.HexSim
import inflation.world.Date
import inflation.world.Hex
import inflation.world.get
import kotlin.math.max

/**
 * The step-length census, reported only: how long a step the world could take, were the step's length variable (the
 * variable-step plan, design/2026-11-07-variable-step-plan.md in the project files). It reads the groups after each step
 * and changes nothing in them.
 *
 * For each step and each group alive it notes whether the group changed hex in the step (a move between places of one hex
 * does not count), and the steps ahead to its first deadline, foreseen from where it stands:
 * - hunger: its reserves (fat and larder, days of food) at this step's drain on them, then its body's protein at that rate
 *   until the first die ([Hazards.starvedShare]); at once by its body if it already burns it. Larder lost on a move or
 *   to spoiling counts as drain, so this errs towards deadlines;
 * - thirst: dry past half the days it lasts without water, as the planner reads thirst;
 * - exposure (bands): this step's planned exposure odds ([inflation.ecology.Band.planOdds]) at or over [EXPOSURE_NEAR],
 *   or the usual cold of the next three months by the band's own body heat with what it has now.
 *
 * At the year's end, for each step of the year as the start of a step of 1, 2, 5, 10 or 15 of today's steps ([LENGTHS],
 * only windows inside the year): the share of people foreseen at the start to reach a deadline within it, the share of
 * groups that changed hex within it, and the longest length that keeps both within a controller's limits ([LIMITS]).
 */
class StepLengthTally(private val sim: HexSim) {
    companion object {
        /** Step lengths weighed, in today's steps. */
        val LENGTHS = intArrayOf(1, 2, 5, 10, 15)
        /** The controller's limits weighed: (people foreseen at a deadline within the step, groups changing hex within it), shares. */
        val LIMITS = listOf(0.01 to 0.05, 0.05 to 0.2)
        /** Planned exposure deaths a step, as a share of the band, at which exposure counts as a deadline. */
        const val EXPOSURE_NEAR = 1e-3f
        /** Months ahead whose usual cold is weighed for exposure. */
        const val MONTHS_AHEAD = 3
    }

    private enum class Cause { HUNGER, THIRST, EXPOSURE }

    /** One step's reading. */
    private class Step(val month: Int, val ids: IntArray, val sizes: IntArray, val human: BooleanArray,
        /** Steps ahead to each group's first deadline (infinite if none foreseen), and the cause. */
        val ahead: FloatArray, val cause: IntArray,
        /** Ids of groups that changed hex in this step. */
        val movedHex: HashSet<Int>)

    private val steps = ArrayList<Step>()
    private val lastHex = HashMap<Int, Hex>()
    private val lastReserve = HashMap<Int, Float>()
    private val lastSpent = HashMap<Int, Float>()
    /** Each band's months-ahead exposure, reckoned once a month (the usual cold changes by the month): the month reckoned, and the first month ahead that reaches [EXPOSURE_NEAR] (0 for none). */
    private val coldAhead = HashMap<Int, Long>()

    fun startYear() { steps.clear() }

    /** After each step. */
    fun week() {
        val groups = sim.groups; val hw = sim.hw; val weather = sim.weather
        val days = sim.cfg.daysPerStep
        val alive = groups.groups.filter { it.alive }
        val ids = IntArray(alive.size); val sizes = IntArray(alive.size); val human = BooleanArray(alive.size)
        val ahead = FloatArray(alive.size); val cause = IntArray(alive.size) { -1 }
        val moved = HashSet<Int>()
        for ((k, g) in alive.withIndex()) {
            ids[k] = g.id; sizes[k] = g.size; human[k] = g.species.human
            val hex = hw.placeHex[g.place]
            lastHex.put(g.id, hex)?.let { if (it != hex) moved += g.id }
            var t = Float.POSITIVE_INFINITY; var c = -1
            fun take(daysAhead: Float, why: Cause) { if (daysAhead < t) { t = daysAhead; c = why.ordinal } }
            hungerDays(g, days)?.let { take(it, Cause.HUNGER) }
            // (Thirsty as the planner has it, [inflation.ecology.GroupPlanner]: dry past half the days it lasts without water.)
            if (g.dry > 0.5f * g.species.daysWithoutWater) take(max(0f, g.species.daysWithoutWater - g.dry), Cause.THIRST)
            exposureDays(g, days)?.let { take(it, Cause.EXPOSURE) }
            ahead[k] = t / days; cause[k] = c
        }
        // (Groups gone since: their notes go too.)
        if (lastHex.size > 2 * alive.size + 64) {
            val now = ids.toHashSet()
            lastHex.keys.retainAll(now); lastReserve.keys.retainAll(now); lastSpent.keys.retainAll(now); coldAhead.keys.retainAll(now)
        }
        steps += Step(weather.date.month, ids, sizes, human, ahead, cause, moved)
    }

    /** Days ahead to [g]'s first hunger deaths at this step's drain, or null if none foreseen. */
    private fun hungerDays(g: HexGroup, days: Int): Float? {
        val s = g.species
        val reserve = g.fat + g.larder / g.dailyNeedKg.coerceAtLeast(1e-3f)
        val before = lastReserve.put(g.id, reserve)
        val spentBefore = lastSpent.put(g.id, g.spent)
        val left = max(0f, (1f - Hazards.STARVE_SPREAD) * Hazards.starveDays(s) - g.spent)
        if (spentBefore != null && g.spent > spentBefore) return left / ((g.spent - spentBefore) / days)
        if (before == null || reserve >= before) return null
        val drain = (before - reserve) / days
        return reserve / drain + left / drain
    }

    /** Days ahead to band [g]'s exposure deadline, or null if none foreseen in [MONTHS_AHEAD] months. */
    private fun exposureDays(g: HexGroup, days: Int): Float? {
        val b = g.band ?: return null
        if (b.planOdds >= EXPOSURE_NEAR) return 0f
        val weather = sim.weather; val date = weather.date
        val month = date.year * Date.MONTHS_PER_YEAR + date.month
        val known = coldAhead[g.id]
        val first = if (known != null && known shr 8 == month) (known and 0xff).toInt() else {
            var f = 0
            for (k in 1..MONTHS_AHEAD) {
                val m = (date.month + k) % Date.MONTHS_PER_YEAR
                val air = sim.groups.airAt(g.place, weather, weather.usualTemperature(m, g.place))
                if (b.warmth(air, b.fire, b.homeShelter(), days).odds >= EXPOSURE_NEAR) { f = k; break }
            }
            coldAhead[g.id] = (month shl 8) or f.toLong()
            f
        }
        return if (first == 0) null else (first * Date.DAYS_PER_MONTH - (date.dayOfMonth - 1)).toFloat()
    }

    /** People at [w] foreseen to reach a deadline within [len] steps, and of them each cause's, as shares of the people. */
    private fun near(w: Int, len: Int): DoubleArray {
        val st = steps[w]; val out = DoubleArray(1 + Cause.entries.size)
        var all = 0.0
        for (k in st.ids.indices) {
            all += st.sizes[k]
            if (st.ahead[k] < len) { out[0] += st.sizes[k].toDouble(); out[1 + st.cause[k]] += st.sizes[k].toDouble() }
        }
        if (all > 0) for (i in out.indices) out[i] /= all
        return out
    }

    /** Groups alive at [w] (only bands if [bands]) that changed hex in the [len] steps from [w], as a share of them. */
    private fun movedWithin(w: Int, len: Int, bands: Boolean? = null): Double {
        val st = steps[w]; var n = 0; var m = 0
        for (k in st.ids.indices) {
            if (bands != null && st.human[k] != bands) continue
            n++
            val id = st.ids[k]
            for (j in w until w + len) if (id in steps[j].movedHex) { m++; break }
        }
        return if (n == 0) 0.0 else m.toDouble() / n
    }

    /** The year's report. */
    fun printYear() {
        val n = steps.size
        if (n == 0) return
        fun starts(len: Int) = 0..(n - len)
        fun mean(len: Int, f: (Int) -> Double) = starts(len).let { r -> if (r.isEmpty()) 0.0 else r.sumOf(f) / (r.last - r.first + 1) }
        println("     step length, people foreseen at a deadline within 1/2/5/10/15 steps (any; hunger / thirst / exposure): " +
            LENGTHS.joinToString(", ") { len ->
                val sums = DoubleArray(1 + Cause.entries.size)
                for (w in starts(len)) near(w, len).forEachIndexed { i, v -> sums[i] += v }
                val c = (n - len + 1).coerceAtLeast(1)
                "%d: %.2f%% (%.2f / %.2f / %.2f)".format(len, 100 * sums[0] / c, 100 * sums[1] / c, 100 * sums[2] / c, 100 * sums[3] / c)
            })
        println("     step length, groups that changed hex within 1/2/5/10/15 steps (bands / packs): " +
            LENGTHS.joinToString(", ") { len -> "%d: %.1f%% / %.1f%%".format(len, 100 * mean(len) { movedWithin(it, len, true) }, 100 * mean(len) { movedWithin(it, len, false) }) })
        for ((nearMost, movedMost) in LIMITS) {
            // (The longest length from each start whose window fits in the year and keeps within both limits; 1 if none does.)
            val picked = IntArray(n) { w ->
                LENGTHS.lastOrNull { len -> w + len <= n && near(w, len)[0] <= nearMost && movedWithin(w, len) <= movedMost } ?: 1
            }
            val byMonth = DoubleArray(Date.MONTHS_PER_YEAR); val inMonth = IntArray(Date.MONTHS_PER_YEAR)
            for (w in 0 until n) { byMonth[steps[w].month] += picked[w].toDouble(); inMonth[steps[w].month]++ }
            val mean = picked.average()
            println(("     step length a controller would pick (people at a deadline at most %.0f%%, groups changing hex at most %.0f%%; " +
                "windows inside the year): mean %.1f steps (%.0f days), steps a year at that %.0f; share of starts at 1/2/5/10/15: %s; mean by month: %s").format(
                100 * nearMost, 100 * movedMost, mean, mean * sim.cfg.daysPerStep, n / mean,
                LENGTHS.joinToString(" / ") { len -> "%.0f%%".format(100.0 * picked.count { it == len } / n) },
                (0 until Date.MONTHS_PER_YEAR).joinToString(" ") { m -> if (inMonth[m] == 0) "-" else "%.1f".format(byMonth[m] / inMonth[m]) }))
        }
    }
}
