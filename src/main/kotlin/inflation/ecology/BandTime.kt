package inflation.ecology

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The kinds of work that take a band's time, each by its line in the census of band time ([Tally.bandTime]); and its leisure. */
enum class Activity(val label: String) {
    FIREWOOD("firewood"), FOOD("food"), DRYING("drying"), SKINS("skins"), SHELTER("shelter"), CUSTOMS("customs"),
    TRAINING("training"), TOOLS("tools"), HOUSES("houses"), ORNAMENTS("ornaments"), SOWING("sowing"), PALISADE("palisade"), MATERIALS("materials"), CACHING("caching"),
    MOVING("moving"), LEISURE("leisure"),
}

/**
 * A band's time through its week, person-days. At the start of the week each activity asks for the days it wants
 * ([plan]), and when the asks come to more than the week the days are shared out among them ([shareOut]): what is held
 * for an activity is kept from the others till it has had its turn. The chores then take from what is theirs and from
 * what nobody asked for ([leftFor]), in turn, each as far as it goes; what one did not use is free for those after it
 * ([release]). What each took is kept for the census of band time.
 */
class BandTime(left: Float) {
    /** Person-days in all, as it began. */
    val total = left
    /** Person-days still unspent, held or not. */
    var left = left
        private set
    private val spent = FloatArray(Activity.entries.size)
    private val held = FloatArray(Activity.entries.size)
    /** What each activity asked for this week, person-days (none before a [plan]). */
    val asked = FloatArray(Activity.entries.size)

    /** Person-days [a] may take now: what is held for it, and what is held for no one. */
    fun leftFor(a: Activity) = max(0f, left - held.sum() + held[a.ordinal])

    /** Takes [personDays] for [a]: from what is held for it first. */
    fun spend(a: Activity, personDays: Float) { spent[a.ordinal] += personDays; left -= personDays; held[a.ordinal] = max(0f, held[a.ordinal] - personDays) }

    /** Takes [personDays] for [a], but never more than it may take ([leftFor]). */
    fun spendAtMost(a: Activity, personDays: Float) = spend(a, min(personDays, leftFor(a)))

    /** Counts [personDays] as gone to [a] before this stretch began (the time foraging took, worked out apart). */
    fun took(a: Activity, personDays: Float) { spent[a.ordinal] += personDays }

    /** What is still held for [a] after its turn is free for the activities after it. */
    fun release(a: Activity) { held[a.ordinal] = 0f }

    /** Sets what is left to no less than none. */
    fun noneBelowZero() { left = max(0f, left) }

    /** Person-days that went to [a] so far. */
    fun spentOn(a: Activity) = spent[a.ordinal]

    /**
     * Holds for each activity its share of the time left: first what it [needs] to live through the week (survival first:
     * food for the week's want, the hearth's firewood), then of the rest its share ([shareOut]) of what else it [asks],
     * weighed by [weights] (1 each by default).
     */
    fun plan(needs: FloatArray, asks: FloatArray, weights: FloatArray? = null) {
        for (i in asked.indices) asked[i] = needs[i] + asks[i]
        val first = shareOut(needs, FloatArray(needs.size) { 1f }, max(0f, left))
        val then = shareOut(asks, weights ?: FloatArray(asks.size) { 1f }, max(0f, left - first.sum()))
        for (i in held.indices) held[i] = first[i] + then[i]
    }

    /** Adds what went to each of [activities] to [census] (tenths of a day). */
    fun count(census: MutableMap<TallyKey, Int>, vararg activities: Activity) {
        for (a in activities) census.merge(Tally.bandTime(a), (spent[a.ordinal] * 10f).roundToInt(), Int::plus)
    }

    /** Adds what went to every [Activity] to [census] (tenths of a day), so a new activity needs no list of its own here. */
    fun countEach(census: MutableMap<TallyKey, Int>) = count(census, *Activity.entries.toTypedArray())

    /** Adds [personDays] to the census under [what]. */
    fun count(census: MutableMap<TallyKey, Int>, what: Tally.TimeTotal, personDays: Float) {
        census.merge(Tally.bandTime(what), (personDays * 10f).roundToInt(), Int::plus)
    }

    companion object {
        /**
         * The days each activity gets of [total] when they [asks] for them, weighed by [weights]: all it asks while the
         * asks fit; else as if each day went where it is worth most, every activity's next day worth its weight less as
         * much again for each share of its ask it has had (days = ask x (1 - lambda / weight), the same lambda for all, as
         * many days as there are). At equal weights each gets the same share of its ask; the less weighed lose theirs first.
         */
        fun shareOut(asks: FloatArray, weights: FloatArray, total: Float): FloatArray {
            val n = asks.size
            if (asks.sum() <= total) return asks.copyOf()
            fun given(lambda: Float) = FloatArray(n) { i -> if (weights[i] > 0f) max(0f, asks[i] * (1f - lambda / weights[i])) else 0f }
            var lo = 0f; var hi = weights.max()
            repeat(40) { val mid = 0.5f * (lo + hi); if (given(mid).sum() > total) lo = mid else hi = mid }
            return given(hi)
        }
    }
}

