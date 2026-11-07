package inflation.world

/**
 * The length of the step the slow parts of the model take (the residents, small life, water life and the groups), as
 * against the tick the weather, the water and the plants keep ([tickDays], [WorldConfig.daysPerStep]). Each slow part
 * reads [days] as the step stands, so the step's length can change from one step to the next: every calculation that
 * takes a step's [days] then covers that step whole (the variable-step plan, design/2026-11-07-variable-step-plan.md
 * in the project files). Set by [inflation.ecology.HexSim] before the slow parts run; at one tick a step the model is
 * the weekly model it was.
 */
class StepClock(val tickDays: Int) {
    /** Days the step the slow parts are taking covers: a whole number of ticks. */
    var days: Int = tickDays
        set(v) { require(v >= tickDays && v % tickDays == 0) { "a step of $v days is not a whole number of $tickDays-day ticks" }; field = v }
    /** The share of a year the step is. */
    val years: Float get() = Date.years(days)
    /** Steps of this length in a year. */
    val stepsPerYear: Int get() = Date.stepsPerYear(days)
    /** Ticks the step covers. */
    val ticks: Int get() = days / tickDays
    /**
     * The share of the step's deaths the young born in it face: they are born through the step, and those born in a tick
     * face that tick whole, as at one tick a step (1 there); over a longer step, half the step and a half tick, not all of
     * it ((days + tick) / 2 days: 0.55 at 60 days). Born at its start and charged the whole step, the young of a 60-day
     * step died of 30 days more than the weekly model's.
     */
    val newbornExposure: Float get() = (days + tickDays) / (2f * days)
}
