package inflation.ecology

import inflation.world.Hex
import inflation.world.HexDrinkingWater
import inflation.world.Place
import inflation.world.get
import kotlin.math.min

// Bands' water: carried home from the river, lake or spring, dug for in a soak, or drawn from a well.
// Bands' wells: shafts sunk to the water table ([Built.WELL]) where a band at home spends days on its water. A well costs
// days by its depth, the more without skill and picks; water is drawn from it in a fifth of a soak's time, by anyone at its
// hex (no band holds it), as long as the water table stands above what of it has not silted up. Wells silt up and their
// sides fall in, used or not, and the band that wants one mends it; one nobody mends fills in and is gone.

/**
 * Litres of water a person needs a day at [tempC] °C: drinking, 3 L in temperate weather and 5 L in hot (Gleick 1996, "Basic
 * water requirements for human activities", Water International 21: 83-92; the break points [WATER_MILD_C] and [WATER_HOT_C]
 * are estimates), and [WATER_COOK_L] for cooking.
 */
internal fun waterLitres(tempC: Float) =
    WATER_COOK_L + WATER_DRINK_L + (WATER_DRINK_HOT_L - WATER_DRINK_L) * ((tempC - WATER_MILD_C) / (WATER_HOT_C - WATER_MILD_C)).coerceIn(0f, 1f)

/**
 * Share of a band's hands' days that carrying its water home from [km] away takes, [litres] a person a day, [peoplePerHand]
 * mouths for each pair of hands: the trips of [CARRY_L] each, there and back at [CARRY_KMH], over the working day.
 */
internal fun fetchShare(km: Float, litres: Float, peoplePerHand: Float) =
    litres * peoplePerHand / CARRY_L * 2f * km / CARRY_KMH / Activity.WORK_HOURS

/**
 * Share of its hands' days a band at place [p] gives its water this week, fetching it as far as [fetchKm], [litres] a person a
 * day, [peoplePerHand] mouths a pair of hands: a soak's ([HexDrinkingWater.DIG_SHARE]) where it must dig, a well's
 * ([WELL_DRAW_SHARE]) where it draws from one, else the carrying ([fetchShare]). The bands' weeks and the planner's camps alike.
 */
internal fun GroupWorld.waterShare(p: Place, fetchKm: Float, litres: Float, peoplePerHand: Float): Float {
    val d = land.drink
    return when {
        d.mustDig(p, fetchKm) -> HexDrinkingWater.DIG_SHARE
        d.drawsFromWell(p, fetchKm) -> WELL_DRAW_SHARE
        else -> fetchShare(min(d.campKm[p], fetchKm), litres, peoplePerHand)
    }
}

/**
 * Share of its hands' days a well at place [p] would spare a band there that fetches water as far as [fetchKm], at [tempC] °C,
 * with [peoplePerHand] mouths a pair of hands: what digging a soak ([HexDrinkingWater.DIG_SHARE]) or carrying its water home
 * ([fetchShare]) takes, over drawing it from a well ([WELL_DRAW_SHARE]). Where there is water neither near nor in a soak, a
 * soak's share stands for it (a well would let it stay at all).
 */
internal fun GroupWorld.wellSpares(p: Place, fetchKm: Float, tempC: Float, peoplePerHand: Float): Float {
    val d = land.drink
    val without = if (d.underground(p, fetchKm)) HexDrinkingWater.DIG_SHARE
        else fetchShare(min(d.campKm[p], fetchKm), waterLitres(tempC), peoplePerHand)
    return (without - WELL_DRAW_SHARE).coerceAtLeast(0f)
}

/** How deep a well at [s] reaches as it stands, m: silt and fallen sides fill it from the bottom. */
internal fun wellReachM(s: Structure) = s.built * s.size

/** The depth a band at place [p] sinks a well to, m: [WELL_MARGIN_M] below the water table there this week. */
private fun GroupWorld.wellDepthM(p: Place) = land.hydro.waterTableM(p) + WELL_MARGIN_M

/** Person-days a metre of well takes [b]: [WELL_DAYS_PER_M] for a master, the more the less it is practised, the less with picks. */
private fun wellDaysPerM(b: Band) = WELL_DAYS_PER_M / (b.skill(Skill.WELLS) * (1f + PICK_GAIN * b.has(Tool.PICK)))

/**
 * The person-days [g]'s band [b] asks this week for sinking, deepening or mending a well at its home, hex [h]: where the water
 * table lies within [WELL_M] and the days a well would have spared it there over the last year ([Band.waterShort]), over
 * [WELL_PAYBACK_YEARS], come to the days the whole well takes; what it lacks of one, at its skill and picks.
 */
internal fun HexGroups.wellAsk(g: HexGroup, b: Band, h: Hex): Float {
    if (!b.atHome || !b.knowsSkill[Skill.WELLS] || b.waterShort <= 0f) return 0f
    val depth = wellDepthM(g.place)
    if (depth > WELL_M) return 0f
    val perM = wellDaysPerM(b)
    val savesYear = b.waterShort * g.adults * days / years
    if (savesYear * WELL_PAYBACK_YEARS < depth * perM) return 0f
    val s = wellAt(h) ?: return depth * perM
    return (maxOf(depth, s.size) - wellReachM(s)).coerceAtLeast(0f) * perM
}

/** A well at [g]'s band [b]'s home, hex [h], where one would pay ([wellAsk]): sunk, deepened or mended as far as the [time] days go. */
internal fun HexGroups.buildWell(g: HexGroup, b: Band, h: Hex, time: BandTime) {
    val left = time.leftFor(Activity.WELLS)
    if (left <= 0f || wellAsk(g, b, h) <= 0f) return
    val depth = wellDepthM(g.place); val perM = wellDaysPerM(b)
    val s = wellAt(h) ?: Structure(Built.WELL, h, -1, g.id, depth).also { bands.built += it }
    // (Deepened where the water has sunk below it: what stands of it is the shallower share of the deeper shaft.)
    if (s.size < depth) { s.built = s.built * s.size / depth; s.size = depth }
    val m = min((1f - s.built) * s.size, left / perM)
    if (m <= 0f) return
    buildOn(s, m / s.size); time.spend(Activity.WELLS, m * perM)
    b.practise(Skill.WELLS)
    stats.meeting(Met.BANDS_DUG_WELLS)
}

/**
 * Once a week, after the structures are tended ([tendStructures]): the places where a whole well at their hex reaches below
 * the water table this week ([HexDrinkingWater.well]).
 */
internal fun HexGroups.wellWater() {
    val well = land.drink.well
    well.fill(0f)
    for (s in bands.built) if (s.kind == Built.WELL && s.done) {
        val reach = wellReachM(s)
        for (p in hw.placesOf(s.hex)) if (land.hydro.waterTableM(Place(p)) < reach) well[p] = 1f
    }
}

/**
 * Deepest well a band sinks, m: the deepest of the early wells at Kissonerga-Mylouthkia, Cyprus, late Pre-Pottery Neolithic B,
 * reached 13 m; the earliest there, about 8400 BC, 8 m through limestone (Peltenburg 2012, "East Mediterranean water wells of
 * the 9th-7th millennium BC"). Atlit-Yam's, about 6900-6300 BC, was 5.5 m (Galili and Nir 1993).
 */
const val WELL_M = 13f

/** How far below the water table a well is sunk, m, so that it keeps water as the table falls a little (an estimate). */
const val WELL_MARGIN_M = 1f

/**
 * Person-days a metre of well takes a practised band without picks: a shaft some 1.5 m across (Atlit-Yam's: Galili and Nir
 * 1993) moves about 1.8 m³ a metre, at perhaps a third of a cubic metre a person-day with digging sticks, stone and baskets,
 * and its sides lined with stone (an estimate; through rock far slower).
 */
const val WELL_DAYS_PER_M = 6f

/** How much faster picks ([Tool.PICK]) sink a well: 1 halves the days (an estimate). */
const val PICK_GAIN = 1f

/**
 * Share of a band's days that drawing water from a well takes: a fifth of a soak's ([HexDrinkingWater.DIG_SHARE]), as the
 * water stands ready and is only lifted (an estimate).
 */
const val WELL_DRAW_SHARE = 0.02f

/** Years of soak-digging days saved a band asks to cover the days of a whole well before it sinks one (an estimate). */
const val WELL_PAYBACK_YEARS = 1f

/** Yearly share of a well that silt and fallen sides take, used or not (an estimate; Atlit-Yam's well was used through four levels: Galili and Nir 1993). */
const val WELL_WEAR = 0.1f

/** Litres a person drinks a day in temperate weather and in hot (Gleick 1996), and the air, °C, at which each holds (estimates). */
const val WATER_DRINK_L = 3f
const val WATER_DRINK_HOT_L = 5f
const val WATER_MILD_C = 20f
const val WATER_HOT_C = 30f

/** Litres a person's cooking takes a day: boiling with hot stones, leaching, washing food (an estimate; Gleick's 10 L assumes a kitchen). */
const val WATER_COOK_L = 2f

/** Litres carried home a trip, in skins, gourds and eggshell flasks: half a modern 20 L can (an estimate). */
const val CARRY_L = 10f

/** Walking pace carrying water, km/h, against some 5 unladen (an estimate). */
const val CARRY_KMH = 4f
