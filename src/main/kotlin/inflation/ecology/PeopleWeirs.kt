package inflation.ecology

import inflation.world.Hex
import kotlin.math.max
import kotlin.math.min

// Bands' weirs: fences of stakes and wattle across a river, with traps where the fish are led in ([Built.WEIR]). A weir costs
// days and timber to build and its traps a watch to tend; what it brings goes with how many fish pass, so it pays only where
// and while fish crowd the river (a spawning run), and nowhere else.

/**
 * The days a kilo of fish from a weir [lengthM] long takes [g]'s band when its traps take [kgWeek] a week ([HexWater.weirWithin]):
 * the watch on its traps, by its length, over what they take ([WEIR_TEND_DAYS_PER_M]), and the landing and gutting of each
 * kilo ([WEIR_HANDLING_DAYS_PER_KG]). Person-days; the drying is its own chore.
 */
internal fun GroupWorld.weirDays(lengthM: Float, kgWeek: Float) =
    if (kgWeek > 0f) WEIR_TEND_DAYS_PER_M * lengthM * days / kgWeek + WEIR_HANDLING_DAYS_PER_KG else Float.MAX_VALUE

/** The length of a weir across the river at hex [h] that [g]'s band would still build or mend there, m (see [weirLengthM]). */
private fun HexGroups.weirLack(g: HexGroup, h: Hex): Float =
    max(0f, weirLengthM(h) - weirAt(g, h, begun = true) * fauna.water.riverWidthM(h))

/**
 * The person-days [g]'s band [b] asks this week for building or mending a weir across the river at its camp, hex [h]: where
 * fish from a whole weir there would have been worth taking up last week ([Band.weirPull] above 1/2: they would have ranked
 * in among its gatherers' foods), what it lacks of one, as far as its timber goes.
 */
internal fun HexGroups.weirAsk(g: HexGroup, b: Band, h: Hex): Float {
    if (b.weirPull <= 0.5f) return 0f
    return min(weirLack(g, h), b.goods[Material.WOOD] / WEIR_WOOD_KG_PER_M) * WEIR_DAYS_PER_M
}

/** A weir across the river at [g]'s band [b]'s camp at hex [h], where one would pay ([weirAsk]), built or mended as far as the [time] days and its timber go. */
internal fun HexGroups.buildWeir(g: HexGroup, b: Band, h: Hex, time: BandTime) {
    val left = time.leftFor(Activity.WEIRS)
    val gd = b.goods
    if (left <= 0f || b.weirPull <= 0.5f || gd[Material.WOOD] <= 0f) return
    val m = minOf(weirLack(g, h), left / WEIR_DAYS_PER_M, gd[Material.WOOD] / WEIR_WOOD_KG_PER_M)
    if (m <= 0f) return
    // (On the weir it is building or mending there, or a new one.)
    val s = buildingAt(g, Built.WEIR, h, -1, weirLengthM(h))
    val add = min(1f - s.built, m / s.size)
    if (add <= 0f) return
    gd[Material.WOOD] -= add * s.size * WEIR_WOOD_KG_PER_M; buildOn(s, add); time.spend(Activity.WEIRS, add * s.size * WEIR_DAYS_PER_M)
    stats.meeting(Met.BANDS_BUILT_WEIRS)
}

/**
 * The longest weir a band builds, m: it fences the whole river up to this width, and a wider one only in part. The Kepel fish
 * dam spanned the Klamath (Waterman & Kroeber 1938); Mesolithic wattle weirs had leaders of 12-63 m (Syltholm and Femern Bælt,
 * Denmark), the Neolithic one at Nekselø ran 200 m. An estimate.
 */
const val WEIR_MAX_M = 100f

/**
 * Person-days to build a metre of weir. The Kepel dam took 50-70 men ten days, besides those cutting stakes, to build across
 * the Klamath (Waterman & Kroeber 1938): some 600-700 person-days over a river there taken as about 100 m wide (the width is an
 * estimate). A shallow stream's weir is lighter work: this overstates it there.
 */
const val WEIR_DAYS_PER_M = 7f

/** Timber in a metre of weir, kg: posts and stakes some 30 cm apart and the wattle or lattice between them (an estimate). */
const val WEIR_WOOD_KG_PER_M = 40f

/**
 * Person-days a day that a metre of weir takes to tend: one at each trap, the traps about 10 m apart (the Kepel dam had ten
 * gates and pens across the river, each worked by its own party: Waterman & Kroeber 1938; the spacing is an estimate).
 */
const val WEIR_TEND_DAYS_PER_M = 0.1f

/** Person-days to land a kilo of fish from a weir's traps and gut it: some minutes a fish of a few kilos (an estimate). */
const val WEIR_HANDLING_DAYS_PER_KG = 0.01f

/**
 * Yearly share of a weir that the current and the floods take: wattle panels and basket traps lasted several years when lifted
 * out for the winter, and the Syltholm weir stood at least 43 years through four rebuildings (Syltholm, southern Lolland, Late
 * Mesolithic; the rate is an estimate).
 */
const val WEIR_WEAR = 0.3f
