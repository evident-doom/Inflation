package inflation.ecology

import inflation.world.Hex
import inflation.world.Weather
import inflation.world.get
import kotlin.math.max
import kotlin.math.min

/*
 * What the goods bands give each other ([TRADED], spare tools) are worth to a band, in its people's days: what it would have
 * cost it to get them itself, the walk to the nearest source it knows and back with a load, and the work there. One measure
 * for every good, and for food through the band's own yield ([favoursOfDays]), so that a gift of salt makes favours to be
 * returned in food, stone or tools as food does ([Band.favours]). Each band reckons from where it stands: salt is cheap
 * beside a salt pan and dear 200 km from one, and that difference is what one band gains by another's gift
 * (docs/design/trade.md).
 */

/** What one person carries home from a source on a trip to fetch a good, kg: a full load of goods ([GOODS_PER_PERSON]). */
internal const val FETCH_LOAD = GOODS_PER_PERSON

/**
 * How far a band reckons the nearest source of a good when it knows of none, km, and the furthest it reckons any:
 * about the edge of the supply zone round the Anatolian obsidian sources, within which the record shows people fetching
 * it themselves (Renfrew, Dixon and Cann 1968). An estimate.
 */
internal const val UNKNOWN_SOURCE_KM = 300f

/** Person-days a kg of a good costs a band [km] from a source where a person-day gets [perDay] kg: there and back with a load, and the work. */
internal fun fetchDaysPerKg(km: Float, perDay: Float): Float =
    2f * min(km, UNKNOWN_SOURCE_KM) / DAY_WALK_KM / FETCH_LOAD + 1f / perDay

/**
 * Favours a band of [adults] grown and [size] people owes for [days] person-days of its work it was spared: the days of
 * its need those days would have brought at its yield of late ([recentYield]: days of need a day of all its hands'
 * foraging brings), a favour to a week of it ([FAVOUR_DAYS]). Without a yield of late, a person-day for a person's day of need.
 */
internal fun favoursOfDays(days: Float, recentYield: Float, adults: Int, size: Int): Float {
    val needDays = if (recentYield.isFinite() && recentYield > 0f) days * recentYield / max(1, adults) else days / max(1, size)
    return needDays / FAVOUR_DAYS
}

/** What a person-day gets of [m] at a source in hex [h], kg (sea salt is made, slower than a pan's crust is scraped); NaN for a good not got at sources. */
private fun HexGroups.rateAt(m: Material, h: Hex): Float = when (m) {
    Material.STONE, Material.GROUND_STONE, Material.OBSIDIAN -> STONE_A_DAY
    Material.OCHRE -> OCHRE_A_DAY
    Material.SALT -> if (hw.coast[h] && h !in bands.materialSources.getValue(Material.SALT)) SEA_SALT_A_DAY else SALT_A_DAY
    else -> Float.NaN
}

/**
 * Person-days a kg of [m] ([TRADED]) is worth to group [g]'s band [b], camped where it is: the cheapest of the sources of
 * it it knows ([Bands.materialSources], for salt the shore too), each by the going within [GOAL_GOING_KM] and beyond as
 * the crow flies, never nearer than that (as [goalKm]); one it knows of none reckons [UNKNOWN_SOURCE_KM] off at a pan's rate.
 */
internal fun HexGroups.worthDaysPerKg(g: HexGroup, b: Band, m: Material, weather: Weather): Float {
    val here = hw.placeHex[g.place]
    val sources = if (m == Material.SALT) bands.saltSources else bands.materialSources.getValue(m)
    var reach: Reach? = null
    var best = Float.MAX_VALUE
    for (h in sources) if (b.knows(h)) {
        val crow = hexKm(here, h).toFloat()
        // (No need to search the ground for a source that could not beat the best even as the crow flies.)
        if (fetchDaysPerKg(crow, rateAt(m, h)) >= best) continue
        val r = reach ?: searchedAtLeast(g.species, hw.placeBand[g.place], here, GOAL_GOING_KM, weather).also { reach = it }
        val km = r.km(h, GOAL_GOING_KM) ?: max(GOAL_GOING_KM, crow)
        best = min(best, fetchDaysPerKg(km, rateAt(m, h)))
    }
    return if (best < Float.MAX_VALUE) best else fetchDaysPerKg(UNKNOWN_SOURCE_KM, rateAt(m, sources.firstOrNull() ?: here))
}

/** Person-days a person's set of tool [t] is worth to group [g]'s band [b]: its making, and the good stone it takes at what that is worth to it. */
internal fun HexGroups.toolWorthDays(g: HexGroup, b: Band, t: Tool, weather: Weather): Float =
    t.makeDays + t.recipe.entries.sumOf { (m, kg) -> if (m in TRADED) (kg * worthDaysPerKg(g, b, m, weather)).toDouble() else 0.0 }.toFloat()

/**
 * [giver] gave [taker] goods worth [days] of the taker's person-days: [taker] owes it favours by them ([favoursOfDays]), and
 * help to a band that is owed favours settles them first ([helpedBy]). Returns the favours.
 */
internal fun HexGroups.gaveGoods(giver: HexGroup, taker: HexGroup, days: Float, weather: Weather): Float {
    val tb = taker.band ?: return 0f
    if (days <= 0f) return 0f
    val f = tuning.goodsFavours * favoursOfDays(days, tb.recentYield(weather.date, this.days), taker.adults, taker.size)
    helpedBy(giver, taker, f)
    return f
}

/** Km as the crow flies from hex [h] to the nearest source of [m] ([TRADED]) any band could know ([Bands.materialSources], for salt the shore too). */
internal fun HexGroups.nearestSourceKm(h: Hex, m: Material): Float {
    val sources = if (m == Material.SALT) bands.saltSources else bands.materialSources.getValue(m)
    var best = Double.MAX_VALUE
    for (s in sources) best = min(best, hexKm(h, s))
    return best.toFloat()
}

/** The bounds of the census's rings of distance from a good's nearest source ([nearestSourceKm]), km; the last ring is beyond the last bound. */
internal val SOURCE_RINGS_KM = floatArrayOf(50f, 100f, 200f, 400f)

/** A gift at a meeting: of what ([GIVEN_OF]), kg or a person's sets of tools, and its worth to the taker and the giver, person-days. */
internal class Given(val of: String, val amount: Float, val takerDays: Float, val giverDays: Float)

/** The census of goods given at meetings: kg, and what they were worth to the taker and to the giver, by the good ([bookGiven]). */
enum class GivenCount(val label: String) { KG("kg/10"), TAKER_DAYS("days to the taker/10"), GIVER_DAYS("days to the giver/10"), FAVOURS("favours/100") }

@OptIn(RawKey::class)
internal fun givenKey(of: String, c: GivenCount) = TallyKey.raw("given $of:${c.label}")

/** What the census shows given of, in order: the traded goods, then tools. */
internal val GIVEN_OF = TRADED.map { it.name.lowercase() } + "tools"

/** Books for the census [kg] of [of] given, worth [takerDays] to the taker and [giverDays] to the giver, for [favours]. */
internal fun HexGroups.bookGiven(of: String, kg: Float, takerDays: Float, giverDays: Float, favours: Float) {
    stats.tally(givenKey(of, GivenCount.KG), Math.round(10f * kg))
    stats.tally(givenKey(of, GivenCount.TAKER_DAYS), Math.round(10f * takerDays))
    stats.tally(givenKey(of, GivenCount.GIVER_DAYS), Math.round(10f * giverDays))
    stats.tally(givenKey(of, GivenCount.FAVOURS), Math.round(100f * favours))
}
