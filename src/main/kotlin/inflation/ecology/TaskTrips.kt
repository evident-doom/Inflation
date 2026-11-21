package inflation.ecology

import inflation.Part
import inflation.world.Hex
import inflation.world.Place
import inflation.world.Weather
import inflation.world.get
import kotlin.math.min

// Task trips: a few of a band's people go out for days to fetch what its own ground does not give, and come back
// (Binford 1980, "Willow smoke and dogs' tails": collectors keep a base and send task groups out for stone, salt, game,
// as foragers move the camp to them instead). A band that holds its home hex ([staysHome]) gathers its materials only
// where it is ([gatherMaterials]: along the way, and its way is its hex), so without these a band with no flint at home
// had good stone only as a gift. They go by the ground, are paid for in the band's days, and wear the way, as every
// going is (docs/design/coherence-review.md, A).

/**
 * Band [g] ([b]), holding its home hex, sends a party of [TASK_PARTY] for each rare material ([PlaceMemory.RARE]) it lacks
 * by [TASK_LACK] or more ([lacking]) and its own place does not give: to the nearest place it remembers finding it
 * ([PlaceMemory.foundAt]) within [TASK_KM] of easy going ([reachOf]), of equals the lower hex. They walk there and back
 * ([tripAlong], wearing the way), gather there at the place's rate ([taskRate]), and carry home what they can, up to
 * [TASK_LOAD_KG] each and what the band carries ([carried]). Their days, the walk's and the work's, are the band's for its
 * materials ([Activity.MATERIALS]): a party goes only if the days left cover its walk and some work. With no [time] it
 * sends none and returns the person-days the trips would take, for the week's plan to ask ([choreAsks]).
 */
@IgnorableReturnValue
@Part("people: task trips")
internal fun HexGroups.taskTrips(g: HexGroup, b: Band, time: BandTime?, weather: Weather, month: Int): Float {
    if (!staysHome(g) || g.adults < TASK_PARTY + SMALL_BAND) return 0f
    val here = hw.placeHex[g.place]
    val mem = g.memory
    var asked = 0f
    var reach: Reach? = null
    for ((k, m) in PlaceMemory.RARE.withIndex()) {
        if (lacking(g, b, m) < TASK_LACK || taskRate(m, g.place, here, month) > 0f) continue
        val r = reach ?: searchedAtLeast(g, here, TASK_KM, weather).also { reach = it }
        var at = Hex.NONE; var km = Float.MAX_VALUE
        for (i in 0 until PlaceMemory.MEMORY_SLOTS) {
            val h = mem.hex[i]
            if (h.none || h == here || mem.foundAt(i, k) <= 0f) continue
            val d = r.km(h, TASK_KM) ?: continue
            if (d < km || (d == km && h.i < at.i)) { km = d; at = h }
        }
        if (at.none) continue
        val p = hw.placeNear(g.place, at).takeIf { !it.none } ?: continue
        val perDay = taskRate(m, p, at, month)
        if (perDay <= 0f) continue
        val load = min(TASK_PARTY * TASK_LOAD_KG, carried(b, m, g.adults.toFloat()) - b.goods[m])
        if (load <= 0f) continue
        val walk = visitDays(TASK_PARTY, km.toDouble()); val work = load / perDay
        if (time == null) { asked += walk + work; continue }
        val left = time.leftFor(Activity.MATERIALS)
        if (left < walk + TASK_MIN_WORK * work) continue
        val got = load * min(1f, (left - walk) / work)
        b.goods[m] += got
        time.spend(Activity.MATERIALS, walk + got / perDay)
        r.wayTo(at, TASK_KM)?.let { tripAlong(g, it, TASK_PARTY, WalkPaid.BY_CALLER) }
        stats.meeting(Met.TASK_TRIPS)
    }
    return asked
}

/**
 * The kg a person gathers of rare material [m] in a day at place [p] (in hex [h]) in [month], as [gatherMaterials] has it: good
 * stone where flint or obsidian crops out, salt from a pan (in a month that dries, with [inflation.Tuning.saltSeasons]),
 * ochre where it crops out, shell on the shore; 0 where there is none.
 */
internal fun GroupWorld.taskRate(m: Material, p: Place, h: Hex, month: Int): Float = when (m) {
    Material.STONE -> STONE_A_DAY * min(1f, 5f * land.stone[p])
    Material.SALT -> if (tuning.saltSeasons > 0f && !land.dryMonth(p, month)) 0f else SALT_A_DAY * min(1f, 5f * land.salt[p])
    Material.OCHRE -> OCHRE_A_DAY * min(1f, 5f * land.ochre[p])
    Material.SHELL -> if (hw.coast[h]) SHELL_A_DAY else 0f
    else -> 0f
}

/** How many of a band go on a task trip ([taskTrips]): a few, as task groups were (two to six, Binford 1980; an estimate). */
internal const val TASK_PARTY = 3f
/** How far a task trip goes, km of easy going ([taskTrips]): two days' walk out at most ([DAY_WALK_KM]). An estimate. */
internal const val TASK_KM = 2f * DAY_WALK_KM
/** What each of a task party carries home, kg ([taskTrips]): a load on the back besides food for the way. An estimate. */
internal const val TASK_LOAD_KG = 20f
/** How far a band lacks a material ([lacking]) before it sends for it ([taskTrips]): half of what it carries. An estimate. */
internal const val TASK_LACK = 0.5f
/** The least share of a trip's gathering the band's days must cover, beyond the walk, for it to go ([taskTrips]). */
internal const val TASK_MIN_WORK = 0.25f
