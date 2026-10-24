package inflation.ecology

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The kinds of work that take a band's time, each by its line in the census of band time ([Tally.bandTime]); and its leisure.
 * [par]: how hard it is, as the energy it takes over the resting rate while it goes on (a physical activity ratio, FAO/WHO/UNU
 * 2004, Human energy requirements, table 5.1: collecting water or wood 4.4; planting, weeding, gathering 4.1; domestic chores
 * by hand 2.3; light leisure 1.4, sitting 1.5). Foraging and hunting are taken as gathering, building and digging as planting
 * and weeding, fetching materials as collecting wood, crafts, skins and the customs' cleaning and nursing as domestic chores;
 * moving camp as gathering, its walking costed apart ([walkCost]). These pairings are estimates.
 */
enum class Activity(val label: String, val par: Float, val where: Workplace) {
    FIREWOOD("firewood", 4.4f, Workplace.OUT), FOOD("food", 4.1f, Workplace.OUT), DRYING("drying", 2.3f, Workplace.CAMP), SKINS("skins", 2.3f, Workplace.CAMP),
    SHELTER("shelter", 4.1f, Workplace.OUT), CUSTOMS("customs", 2.3f, Workplace.CAMP), TRAINING("training", 2.3f, Workplace.CAMP), TOOLS("tools", 2.3f, Workplace.CAMP),
    HOUSES("houses", 4.1f, Workplace.OUT), ORNAMENTS("ornaments", 2.3f, Workplace.CAMP), SOWING("sowing", 4.1f, Workplace.OUT), PALISADE("palisade", 4.1f, Workplace.OUT),
    MATERIALS("materials", 4.4f, Workplace.OUT), CACHING("caching", 4.1f, Workplace.OUT), MOVING("moving", 4.1f, Workplace.OUT), LEISURE("leisure", 1.45f, Workplace.NONE);

    /**
     * The share of a grown person's daily need ([Species.dailyIntakeKg], taken as a day of foraging's) a day of this saves
     * against a day of foraging, as its day's average over the resting rate: 8 h asleep (1.0), [WORK_HOURS] at [par], the rest
     * about camp (1.45, between light leisure and sitting). A day foraging comes to 1.96 (Hadza adults spend about 1.8-2.3
     * times the resting rate a day, Pontzer et al. 2012); a day at rest to 1.30, saving 0.34; a day of crafts saves 0.23.
     * Below 0 for harder work (fetching wood or materials).
     */
    val restSaves: Float get() = 1f - dayRate(par) / dayRate(FOOD.par)

    companion object {
        /** Hours of a working day. */
        const val WORK_HOURS = 6f
        private fun dayRate(par: Float) = (8f * 1f + WORK_HOURS * par + (24f - 8f - WORK_HOURS) * 1.45f) / 24f
    }
}

/**
 * Where an [Activity] is done: out in the weather ([OUT]: foraging, fetching, building, walking), at camp by the fire or,
 * at home, in the houses ([CAMP]: crafts, drying, the customs), or it is no work ([NONE]). The cold and hunger slow work
 * at each as [Band.paceAt] has it.
 */
enum class Workplace { OUT, CAMP, NONE }

/**
 * A band's time through its week, person-days. At the start of the week each activity asks for the days it wants
 * ([plan]), and when the asks come to more than the week the days are shared out among them ([shareOut]): what is held
 * for an activity is kept from the others till it has had its turn. The chores then take from what is theirs and from
 * what nobody asked for ([leftFor]), in turn, each as far as it goes; what one did not use is free for those after it
 * ([release]). What each took is kept for the census of band time.
 */
class BandTime(left: Float, private val pace: FloatArray? = null) {
    /** Person-days in all, as it began. */
    val total = left
    /** Person-days still unspent, held or not. */
    var left = left
        private set
    private val spent = FloatArray(Activity.entries.size)
    private val held = FloatArray(Activity.entries.size)
    private val heldAtOne = FloatArray(Activity.entries.size)
    /** What each activity asked for this week, person-days (none before a [plan]). */
    val asked = FloatArray(Activity.entries.size)
    /** Whether the asks beyond what the week needs came to more than the days left for them (false before a [plan]). */
    var short = false
        private set

    /** Person-days held for no activity: the week's rest, as planned. */
    val unheld get() = max(0f, left - held.sum())

    /** How much of a person-day spent at [a] is work this week, the rest lost to the cold and hunger ([Band.paceAt]; 1 when not given). */
    fun pace(a: Activity) = pace?.get(a.ordinal)?.coerceAtLeast(PACE_LEAST) ?: 1f

    /** Person-days lost to the cold and hunger in what was spent so far (see [pace]). */
    var lost = 0f
        private set

    /**
     * Person-days of work [a] may do now: what is held for it, and what is held for no one, at its [pace]. (What each
     * activity asks, plans and spends is days of work; what the week holds and has left is its people's days.)
     */
    fun leftFor(a: Activity) = max(0f, left - held.sum() + held[a.ordinal]) * pace(a)

    /** Takes [personDays] of work for [a]: from what is held for it first. */
    fun spend(a: Activity, personDays: Float) {
        val days = personDays / pace(a)
        spent[a.ordinal] += days; left -= days; lost += days - personDays; held[a.ordinal] = max(0f, held[a.ordinal] - days)
    }

    /** Takes [personDays] for [a], but never more than it may take ([leftFor]). */
    fun spendAtMost(a: Activity, personDays: Float) = spend(a, min(personDays, leftFor(a)))

    /** Counts [personDays] as gone to [a] before this stretch began (the time foraging took, worked out apart). */
    fun took(a: Activity, personDays: Float) { spent[a.ordinal] += personDays }

    /** What is still held for [a] after its turn is free for the activities after it. */
    fun release(a: Activity) { held[a.ordinal] = 0f }

    /** Sets what is left to no less than none. */
    fun noneBelowZero() { left = max(0f, left) }

    /** Person-days that went to [a] so far, the time lost to the cold and hunger in it included. */
    fun spentOn(a: Activity) = spent[a.ordinal]

    /**
     * Holds for each activity its share of the time left (survival first): first what keeps its people [warm] this week
     * (the hearth's firewood and the camp's shelters as far as they avert deaths, [survivalDays]: a cold night without them
     * kills within the week, where a hungry week is bridged by the larder and fat), then what else it [needs] (the week's
     * food), then of the rest its share ([shareOut]) of what else it [asks], weighed by [weights] (1 each by default).
     */
    fun plan(needs0: FloatArray, asks0: FloatArray, weights: FloatArray? = null, warm0: FloatArray? = null) {
        for (i in asked.indices) asked[i] = needs0[i] + asks0[i] + (warm0?.get(i) ?: 0f)
        // (Days of work asked, as people's days at each activity's pace.)
        val needs = FloatArray(needs0.size) { needs0[it] / pace(Activity.entries[it]) }
        val asks = FloatArray(asks0.size) { asks0[it] / pace(Activity.entries[it]) }
        val ones = FloatArray(needs.size) { 1f }
        val warmed = warm0?.let { w -> shareOut(FloatArray(w.size) { w[it] / pace(Activity.entries[it]) }, ones, max(0f, left)) }
        val fed = shareOut(needs, ones, max(0f, left - (warmed?.sum() ?: 0f)))
        val first = if (warmed == null) fed else FloatArray(fed.size) { fed[it] + warmed[it] }
        short = asks.sum() > max(0f, left - first.sum())
        val then = shareOut(asks, weights ?: ones, max(0f, left - first.sum()))
        if (weights != null) shareOut(asks, ones, max(0f, left - first.sum())).forEachIndexed { i, d -> heldAtOne[i] = first[i] + d }
        for (i in held.indices) { held[i] = first[i] + then[i]; if (weights == null) heldAtOne[i] = held[i] }
    }

    /** People's days the [plan] held for [a], and would have held were every weight 1 (for counting the weights' use, [DialUse]). */
    fun heldFor(a: Activity) = held[a.ordinal]
    fun heldAtOneFor(a: Activity) = heldAtOne[a.ordinal]

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
        /** The least pace reckoned with, so that work that cannot go on asks no end of days (a guard against dividing by none). */
        const val PACE_LEAST = 0.01f

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

