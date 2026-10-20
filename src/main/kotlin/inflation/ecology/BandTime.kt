package inflation.ecology

import kotlin.math.max
import kotlin.math.roundToInt

/** The kinds of work that take a band's time, each by its line in the census of band time ([Tally.bandTime]). */
enum class Activity(val label: String) {
    FIREWOOD("firewood"), FOOD("food"), DRYING("drying"), SKINS("skins"), SHELTER("shelter"), CUSTOMS("customs"),
    TRAINING("training"), TOOLS("tools"), HOUSES("houses"), ORNAMENTS("ornaments"), SOWING("sowing"), PALISADE("palisade"), MATERIALS("materials"), CACHING("caching"),
}

/**
 * A band's spare time through a stretch of its week, person-days: the chores take from [left] in turn, each as far as
 * it goes, and what each took is kept for the census of band time.
 */
class BandTime(left: Float) {
    /** Person-days still to spare. */
    var left = left
        private set
    private val spent = FloatArray(Activity.entries.size)

    /** Takes [personDays] for [a]. */
    fun spend(a: Activity, personDays: Float) { spent[a.ordinal] += personDays; left -= personDays }

    /** Takes [personDays] for [a], but never below none left. */
    fun spendAtMost(a: Activity, personDays: Float) { val after = max(0f, left - personDays); spent[a.ordinal] += left - after; left = after }

    /** Counts [personDays] as gone to [a] before this stretch began (the time foraging took, worked out apart). */
    fun took(a: Activity, personDays: Float) { spent[a.ordinal] += personDays }

    /** Sets what is left to no less than none. */
    fun noneBelowZero() { left = max(0f, left) }

    /** Adds what went to each of [activities] to [census] (tenths of a day). */
    fun count(census: MutableMap<String, Int>, vararg activities: Activity) {
        for (a in activities) census.merge(Tally.bandTime(a), (spent[a.ordinal] * 10f).roundToInt(), Int::plus)
    }

    /** Adds what went to every [Activity] to [census] (tenths of a day), so a new activity needs no list of its own here. */
    fun countEach(census: MutableMap<String, Int>) = count(census, *Activity.entries.toTypedArray())

    /** Adds [personDays] to the census under [what]. */
    fun count(census: MutableMap<String, Int>, what: Tally.TimeTotal, personDays: Float) {
        census.merge(Tally.bandTime(what), (personDays * 10f).roundToInt(), Int::plus)
    }
}
