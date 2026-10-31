package inflation.ecology

import inflation.world.Hex
import inflation.world.HexDrinkingWater
import inflation.world.Place
import kotlin.math.min

// Bands' wells: shafts sunk to the water table ([Built.WELL]) where a band at home has had to dig for its water. A well costs
// days by its depth, the more without skill and picks; water is drawn from it in a fifth of a soak's time, by anyone at its
// hex (no band holds it), as long as the water table stands above what of it has not silted up. Wells silt up and their
// sides fall in, used or not, and the band that wants one mends it; one nobody mends fills in and is gone.

/** How deep a well at [s] reaches as it stands, m: silt and fallen sides fill it from the bottom. */
internal fun wellReachM(s: Structure) = s.built * s.size

/** The depth a band at place [p] sinks a well to, m: [WELL_MARGIN_M] below the water table there this week. */
private fun GroupWorld.wellDepthM(p: Place) = land.hydro.waterTableM(p) + WELL_MARGIN_M

/** Person-days a metre of well takes [b]: [WELL_DAYS_PER_M] for a master, the more the less it is practised, the less with picks. */
private fun wellDaysPerM(b: Band) = WELL_DAYS_PER_M / (b.skill(Skill.WELLS) * (1f + PICK_GAIN * b.has(Tool.PICK)))

/**
 * The person-days [g]'s band [b] asks this week for sinking, deepening or mending a well at its home, hex [h]: where it has had
 * to dig for its water there ([Band.waterShort]), the water table lies within [WELL_M] and the days a well would save it
 * over soaks in [WELL_PAYBACK_YEARS] come to the days the whole well takes; what it lacks of one, at its skill and picks.
 */
internal fun HexGroups.wellAsk(g: HexGroup, b: Band, h: Hex): Float {
    if (!b.atHome || !b.knowsSkill[Skill.WELLS] || b.waterShort <= 0f) return 0f
    val depth = wellDepthM(g.place)
    if (depth > WELL_M) return 0f
    val perM = wellDaysPerM(b)
    val savesYear = b.waterShort * (HexDrinkingWater.DIG_SHARE - WELL_DRAW_SHARE) * g.adults * days / years
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
