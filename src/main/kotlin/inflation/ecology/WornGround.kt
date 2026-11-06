package inflation.ecology

import inflation.world.Place
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt

/*
 * Worn ground (t.wornGround; review/2026-11-06-camp-depletion-design.md, Olli 2026-11-06). A place's food and wood stay as the
 * land holds them; what wears is what a band has taken near where it lives, food by food, and the next kilo costs the more for it.
 * - A light band (no houses, crop or palisade: [settledSpot]) shifts its camp about its place as it likes: what all bands took
 *   there counts against the whole place's, and the best goes first, so each next kilo is found and handled the slower
 *   ([wornPace]): the yields of a place's patches spread lognormally ([WORN_SPREAD]), the richest first and a long tail behind.
 * - A settled band takes from about its fixed spot outward: the ground near it is gone over, so its gatherers walk out the
 *   farther ([wornWalk]), and its hearth's dead wood is fetched from ever farther off ([wornWoodCover]).
 * What was taken comes back over each food's [WORN_YEARS]. Fruit and grass seed are a season's flow, not a standing stock: what
 * was taken of them is set against what the place bore over the same span ([WornGround.crop]), not against the little ripe
 * on the plants this week. Not saved (rebuilt within a year of a load).
 */

/** The kinds a band's take is kept by: fruit, roots and bulbs, mast, grass seed, small finds, and the hearth's dead wood. */
internal const val WORN_FRUIT = 0
internal const val WORN_ROOTS = 1
internal const val WORN_MAST = 2
internal const val WORN_SEED = 3
internal const val WORN_FINDS = 4
internal const val WORN_WOOD = 5
internal const val WORN_KINDS = 6

/**
 * Years in which what was taken of each kind comes back (fruit, roots, mast, seed, finds, dead wood), e-folding. Fruit, mast
 * and seed: a season, as the plants bear again (inferred). Roots 2.5: harvested tubers of a Noongar staple were still below
 * their weight before after 2 years, though the stems were back in 1 (Biol. J. Linn. Soc. doi 10.1093/biolinnean/blaa149,
 * abstract); finds a guess; dead wood 5, a guess.
 */
internal val WORN_YEARS = floatArrayOf(1f, 2.5f, 1f, 1f, 1f, 5f)

/**
 * The spread (σ of the log) of the yields of a place's patches: at 1.1 the richest tenth of the patches hold some 43% of
 * the food. An estimate: fruit is "highly unequal among trees of the same species" (Ecol. Evol. 2019, doi 10.1002/ece3.4867,
 * abstract), with no figure found.
 */
internal const val WORN_SPREAD = 1.1f

/** The share of a place's food taken up to which a light band gathers at the pace it would on fresh ground (an estimate). */
internal const val WORN_FRESH = 0.1f

/** The most of a place's food (or of the ground about a settled band) counted as taken. */
internal const val WORN_MOST = 0.95f

/** Dead wood lying where the wood cover is full ([woodCover] 1), kg/m²: 2 t/ha, a guess (no stock in t/ha was found). */
internal const val DEADWOOD_KG_M2 = 0.2f

/** The dead wood a hand carries home a trip, kg (an estimate). */
internal const val WOOD_LOAD_KG = 25f

/** What bands have taken, by place and by settled band ([WornGround.Spot]); faded each week ([fade]). */
internal class WornGround(n: Int) {
    /** Kg of each kind taken at each place by any band, coming back over [WORN_YEARS]. */
    val taken = Array(WORN_KINDS) { FloatArray(n) }
    /**
     * Kg of fruit and of grass seed each place bore, faded as [taken] is (the week's ripe fruit or seed that falls or is shed,
     * and what was taken of it): what a share taken of a flowing crop is reckoned against. Zero for the standing kinds.
     */
    val crop = Array(WORN_KINDS) { FloatArray(n) }
    /** A settled band's fixed spot: its [place] and the kg of each kind it has taken from about it. */
    class Spot(val place: Int) { val taken = FloatArray(WORN_KINDS) }
    /** The settled bands' spots, by group id (concurrent: bands forage in parallel, each its own). */
    val spots = java.util.concurrent.ConcurrentHashMap<Int, Spot>()

    /** A week of [days] days: what was taken comes back. */
    fun fade(days: Int) {
        for (k in 0 until WORN_KINDS) {
            val keep = exp(-days / (365f * WORN_YEARS[k]))
            val a = taken[k]; val c = crop[k]
            for (p in a.indices) { a[p] *= keep; c[p] *= keep }
            for (s in spots.values) s.taken[k] *= keep
        }
    }
}

/** Whether worn kind [k] is a season's flow (fruit, grass seed), reckoned against the crop borne ([WornGround.crop]). */
internal fun wornFlows(k: Int) = k == WORN_FRUIT || k == WORN_SEED

/**
 * A week of [days] days on the worn ground: what was taken comes back, and each place's crop of fruit and seed grows by what
 * fell or was shed of it this week ([HexFood.FRUIT_DAYS], [HexFood.SEED_DAYS]); what was taken of it is added as it is taken.
 */
internal fun HexGroups.wornWeek(days: Int) {
    val w = wornGround ?: return
    w.fade(days)
    val food = fauna.food
    val fruit = w.crop[WORN_FRUIT]; val seed = w.crop[WORN_SEED]
    for (p in 0 until n) {
        fruit[p] += land.total(Place(p), inflation.KgM2(food.fruit[p] * min(1f, days / HexFood.FRUIT_DAYS))).v
        seed[p] += land.total(Place(p), inflation.KgM2(food.seeds[p] * min(1f, days / HexFood.SEED_DAYS))).v
    }
    if (w.spots.isNotEmpty()) { val alive = groups.filter { it.alive }.mapTo(HashSet()) { it.id }; w.spots.keys.retainAll(alive) }
}

/**
 * The share of worn kind [k] taken at place [p] by all bands, with [leftKg] lying there now: of a flowing crop, against what
 * the place bore ([wornFlows]); of a standing one, against what was taken and what is left.
 */
internal fun WornGround.takenShare(k: Int, p: Place, leftKg: Float): Float {
    val t = taken[k][p.i]
    if (t <= 0f) return 0f
    return min(WORN_MOST, if (wornFlows(k)) t / crop[k][p.i].coerceAtLeast(t) else t / (t + leftKg).coerceAtLeast(1e-9f))
}

/**
 * The share of worn kind [k] that settled band's spot [s] has taken of what lies within its [reach] (share of place [p]'s
 * ground), with [leftKg] lying on the whole place now: of a flowing crop, against the reached share of what it bore.
 */
internal fun WornGround.spotTakenShare(s: WornGround.Spot, k: Int, p: Place, reach: Float, leftKg: Float): Float {
    val t = s.taken[k]
    if (t <= 0f) return 0f
    return min(WORN_MOST, if (wornFlows(k)) t / (reach * crop[k][p.i]).coerceAtLeast(t) else t / (t + reach * leftKg).coerceAtLeast(1e-9f))
}

/** This run's worn ground, made the first time it is asked for; null unless t.wornGround. */
internal fun GroupWorld.worn(): WornGround? {
    if (!tuning.wornGround) return null
    wornGround?.let { return it }
    // (Made once, though bands forage in parallel.)
    return synchronized(this) { wornGround ?: WornGround(n).also { wornGround = it } }
}

/**
 * Whether band [b] lives fixed to its camp: in its houses, with a crop sown in the year, or behind a palisade. Such a band
 * cannot shift its camp about its place; the ground about it wears.
 */
internal fun Band.livesFixed() = atHome || (sownKg > 0f && sinceSowed < 1f) || palisade > 0f

/** [g]'s fixed spot if it lives fixed ([livesFixed]): kept while it stays at its camp's place, begun afresh where it settles anew; null for a light band. */
internal fun GroupWorld.settledSpot(g: HexGroup): WornGround.Spot? {
    val w = worn() ?: return null
    val b = g.band ?: return null
    if (!b.livesFixed()) { w.spots.remove(g.id); return null }
    val s = w.spots[g.id]
    return if (s != null && s.place == g.place.i) s else WornGround.Spot(g.place.i).also { w.spots[g.id] = it }
}

/** The worn kind of plant food [t] of [kind] ([MAST_KIND] or not), or -1 for those not kept (grain, sown or wild). */
internal fun wornKind(t: Food, kind: Int) = when (t) {
    Food.FRUIT -> WORN_FRUIT
    Food.ROOTS -> if (kind == MAST_KIND) WORN_MAST else WORN_ROOTS
    Food.SEED -> WORN_SEED
    Food.FINDS -> WORN_FINDS
    else -> -1
}

/**
 * The pace a light band finds and handles the next kilo of a food at, against fresh ground, when [taken] kg of it have
 * been taken there and [left] kg lie there still: the best patches went first, so the best left is the poorer by the
 * lognormal spread of patch yields ([WORN_SPREAD]) from the share taken past [WORN_FRESH]. 1 on fresh ground, falling
 * toward a long tail.
 */
internal fun wornPace(taken: Float, left: Float): Float =
    if (taken <= 0f) 1f else wornPaceAt(min(WORN_MOST, taken / (taken + left).coerceAtLeast(1e-9f)))

/** [wornPace] at a share [f] of the place's food taken. */
internal fun wornPaceAt(f: Float): Float {
    if (f <= WORN_FRESH) return 1f
    return exp(WORN_SPREAD * (normalQuantile(1f - f) - normalQuantile(1f - WORN_FRESH)))
}

/**
 * How much farther a settled band's gatherers walk, against fresh ground, when [taken] kg of a food have been taken from
 * about its spot and [reachKg] lie within its usual reach: the near ground is gone over, so they reach out over as much
 * more ground as holds what was taken, and the walk goes by the square root of the ground.
 */
internal fun wornWalk(taken: Float, reachKg: Float): Float =
    if (taken <= 0f) 1f else wornWalkAt(min(WORN_MOST, taken / (taken + reachKg).coerceAtLeast(1e-9f)))

/** [wornWalk] at a share [f] of the food within reach taken. */
internal fun wornWalkAt(f: Float) = sqrt(1f / (1f - min(WORN_MOST, f)))

/**
 * The wood cover a settled band at its spot [s] fetches its hearth's wood at ([woodCover] [cover] about it): the dead wood
 * near it is gone, cleared over the ground its [WORN_WOOD] take held at [DEADWOOD_KG_M2], so each load is carried home from
 * the cleared ground's edge, two-thirds of its radius out on average, there and back.
 */
internal fun GroupWorld.wornWoodCover(s: WornGround.Spot?, cover: Float): Float {
    if (s == null || cover <= 0f || s.taken[WORN_WOOD] <= 0f) return cover
    val clearedKm2 = s.taken[WORN_WOOD] / (DEADWOOD_KG_M2 * cover * 1e6f)
    val km = 2f * 2f / 3f * sqrt(clearedKm2 / PI.toFloat())
    val walkDaysPerKg = km / (WALK_KM_PER_HOUR * Activity.WORK_HOURS) / WOOD_LOAD_KG
    return 1f / (FUEL_A_DAY * (1f / (FUEL_A_DAY * cover) + walkDaysPerKg))
}

/** The wood cover band [g] fetches its hearth's wood at, about its camp: [woodCover], less where it has worn the dead wood near a fixed spot. */
internal fun GroupWorld.bandWoodCover(g: HexGroup): Float {
    val cover = woodCover(g.place)
    return if (!tuning.wornGround || g.band == null) cover else wornWoodCover(settledSpot(g), cover)
}

/** [g] took [kg] of worn kind [k] at place [p]: the place keeps it, and a settled band's spot if it is there. */
internal fun GroupWorld.wornTook(g: HexGroup, k: Int, p: Place, kg: Float) {
    if (k < 0 || kg <= 0f) return
    val w = worn() ?: return
    w.taken[k][p.i] += kg
    if (wornFlows(k)) w.crop[k][p.i] += kg
    settledSpot(g)?.let { s -> if (s.place == p.i) s.taken[k] += kg }
}

/** The standard normal's quantile at [q] (0..1), by Acklam's rational approximation (relative error under 1.2e-9). */
internal fun normalQuantile(q: Float): Float {
    val p = q.toDouble().coerceIn(1e-9, 1 - 1e-9)
    val a = doubleArrayOf(-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02, 1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00)
    val b = doubleArrayOf(-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02, 6.680131188771972e+01, -1.328068155288572e+01)
    val c = doubleArrayOf(-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00, -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00)
    val d = doubleArrayOf(7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00)
    val low = 0.02425
    val x = when {
        p < low -> { val r = sqrt(-2 * ln(p)); (((((c[0] * r + c[1]) * r + c[2]) * r + c[3]) * r + c[4]) * r + c[5]) / ((((d[0] * r + d[1]) * r + d[2]) * r + d[3]) * r + 1) }
        p > 1 - low -> { val r = sqrt(-2 * ln(1 - p)); -(((((c[0] * r + c[1]) * r + c[2]) * r + c[3]) * r + c[4]) * r + c[5]) / ((((d[0] * r + d[1]) * r + d[2]) * r + d[3]) * r + 1) }
        else -> { val r = p - 0.5; val s = r * r; (((((a[0] * s + a[1]) * s + a[2]) * s + a[3]) * s + a[4]) * s + a[5]) * r / (((((b[0] * s + b[1]) * s + b[2]) * s + b[3]) * s + b[4]) * s + 1) }
    }
    return x.toFloat()
}
