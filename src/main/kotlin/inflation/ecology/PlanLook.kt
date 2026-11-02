package inflation.ecology

import inflation.world.Date

// For the viewer: what the followed planning band reckoned this week, kept as the reckoning went, for the Plan tab of the
// group controls (inflation.ui.GroupControlWindow). Filled only for the followed group ([HexGroups.watched]) while the
// controls are open ([HexGroups.control]): a run without the viewer fills none, and filling one changes nothing it reckons.

/** Whether [g] is the followed group with the controls open: the only group whose reckoning is kept ([PlanLook]). */
internal fun HexGroups.looked(g: HexGroup) = control != null && g.id == watched

/**
 * Planning band [group]'s choice whether to stay or move camp in [date]'s week ([GroupPlanner.planned], [plannedMove]): each
 * place it weighed and why it went or stayed.
 */
class MoveLook(
    val group: Int,
    val date: Date,
    /** Its customs sent it (an appointment due, a raid set, a camp left after a death). */
    val owed: Boolean,
    /** The places weighed ([Place] indices), the hand-written scores, the survival steps each would cost ([survivalSteps]). */
    val places: IntArray,
    val handScores: FloatArray,
    val deaths: FloatArray,
    /** The scores it went by once it might leave (the network's, else the hand-written); null if it never asked (it stayed). */
    val scores: FloatArray?,
    /** What the walk to each would cost, shares of a week ([reckonedWalk]); NaN where it was not reckoned. */
    val walks: FloatArray,
    /** Km to each, by the cheapest way. */
    val km: FloatArray,
    /** The place it stands on (an index into [places], -1 if not among them). */
    val stays: Int,
    /** The parts of its case for leaving ([ReckonTerm], shares of a week; NaN where not judged). */
    val terms: FloatArray,
    /** The case less what leaving gives up ([reckonedGain]); NaN when it had none to judge. */
    val gain: Float,
    /** The weeks it expects to keep a camp ([Band.stayWeeks]), over which leaving's losses are paid. */
    val stayWeeks: Float,
    /** Why it went or stayed, and where it went (an index into [places], -1 none). */
    val why: Reckoned?,
    val chosen: Int,
)

/**
 * One of a planning band's scored items ([planDays]: the stores and the grinding stones for gruel in lives; houses, weirs and
 * tools in days saved): what it asked, what a day of it was reckoned to save, the bar it had to clear, the days held.
 */
class ItemLook(
    val name: String,
    /** "lives" (lives a day of work saves) or "days" (days saved a day of work). */
    val unit: String,
    val askDays: Float,
    val rate: Float,
    val bar: Float,
    val heldDays: Float,
    /** Its place in the order it was weighed (0 first). */
    val rank: Int,
    /** The reckoning behind [rate], as label and value. */
    val inputs: List<Pair<String, String>>,
)

/** One tool weighed by its days ([toolRates]): a year's days saved by a whole kit, how long it lasts, its making, and whether it pays. */
class ToolLook(val tool: Tool, val kit: Float, val savedAYear: Float, val lastsYears: Float, val makeDays: Float, val ratio: Float, val pays: Boolean)

/**
 * Planning band [group]'s days in [date]'s week ([planDays]): the days its hands had, what the essentials held first, and then
 * tier by tier what each item asked and got. Days are people-days; asks and holds in days at each activity's pace.
 */
class DaysLook(val group: Int, val date: Date, val people: Int, val total: Float) {
    /** The days the essentials held first, by activity (the week's food, fire, shelter and clothes, herders, the walk). */
    val essentials = FloatArray(Activity.entries.size)
    /** The days the walk to a new camp and the water took before anything else. */
    var walkAndWater = 0f
    /** What was left for the tiers once the essentials were held. */
    var afterEssentials = 0f
    /** Owed by custom (rites and burials, ornaments): asked and paid. */
    var customsAsked = 0f; var customsPaid = 0f; var ornamentsAsked = 0f; var ornamentsPaid = 0f
    /** The scored items, in [planDays]' table order. */
    val items = ArrayList<ItemLook>()
    val tools = ArrayList<ToolLook>()
    /** The chores with no reckoning yet: asked and shared, days, by activity; the clothes beyond the survival pass last. */
    val unreckoned = ArrayList<Triple<String, Float, Float>>()
    var leisure = 0f
    /** Inputs gathered while the rates were reckoned, by item (in [planDays]' table order), for [items]. */
    internal val pending = Array(SCORED_ITEMS_SHOWN) { ArrayList<Pair<String, String>>() }
    internal fun note(item: Int, label: String, value: String) { pending[item] += label to value }
}

/** The scored items [planDays] reckons ([DaysLook.pending]). */
internal const val SCORED_ITEMS_SHOWN = 5
