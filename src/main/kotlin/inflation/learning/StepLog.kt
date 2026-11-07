package inflation.learning

import inflation.world.WorldConfig

/**
 * The days a training world's steps covered: the day each step began on, counted from the world's start, so that what
 * follows a choice is judged over days, not over steps, whatever length the steps were (fixed by `t.stepDays`, or drawn by
 * `ppo.stepChoices`). At one tick a step, step k began on day 6k, and everything judged over days is judged as it was
 * over weeks.
 */
class StepLog {
    /** The first step kept, and the day each step from it began on ([n] of them; the last is the step to come). */
    private var base = 0
    private var days = IntArray(256)
    private var n = 1

    /** Starts again from step [week], beginning on [day]. */
    fun reset(week: Int, day: Int) { base = week; days[0] = day; n = 1 }

    /** The step [week] has been taken, covering [length] days. */
    fun stepped(length: Int) {
        if (n == days.size) days = days.copyOf(2 * n)
        days[n] = days[n - 1] + length
        n++
    }

    /** The day step [week] began on (the step to come included). */
    fun dayOf(week: Int): Int = days[week - base]

    /**
     * The steps from step [from] on until at least [span] days have passed (at least one), or -1 while that many have not yet
     * been taken. At one tick a step, [span] / 6 rounded up.
     */
    fun reach(from: Int, span: Int): Int {
        val i0 = from - base
        if (i0 < 0) return -1
        var k = 1
        while (i0 + k < n) {
            if (days[i0 + k] - days[i0] >= span) return k
            k++
        }
        return -1
    }

    companion object {
        /** The week the horizons and boots are set in: one tick ([WorldConfig.daysPerStep]). */
        val WEEK = WorldConfig().daysPerStep
    }
}
