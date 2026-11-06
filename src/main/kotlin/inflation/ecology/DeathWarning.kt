package inflation.ecology

import inflation.world.Place
import kotlin.math.max
import kotlin.math.roundToInt

// For the census: whether bands saw their hunger and thirst deaths coming. Report-only: the model never reads any of it.

/**
 * What a band foresaw of the week's deaths when it chose its place, and what it had to live on, in each of the last
 * [WARN_WEEKS] weeks ([Band.warn]): set as it chooses ([recordWarning], [noteReserves]), read when its people die
 * ([bookWarning]). The shares are of its people, a week's deaths, by the same reckoning its choice weighs ([deathsAt]).
 */
enum class Warn {
    /** The week's hunger and thirst at the place it chose ([foreseenHungerThirst]). */
    CHOSEN,
    /** All it foresaw at the place it chose ([deathsAt], with the crossing and the storms): what the networks read. */
    SEEN,
    /** The least hunger and thirst at any place it weighed: whether there was somewhere to go. */
    LEAST,
    /** Its fat, days. */
    FAT,
    /** Its body's protein burnt, days. */
    SPENT,
    /** Its days without water. */
    DRY,
    /** Its larder, days of its need. */
    LARDER,
    /** Of it, dried meat and fish; seed and nuts; roots; and the rest (fresh meat and fish, fruit, grease), days of its need. */
    LARDER_DRIED, LARDER_SEED, LARDER_ROOTS, LARDER_OTHER,
    /** Its larder as the week ended ([weekWarning]), or for the week its people die, as they die ([bookWarning]), days of its need. */
    LARDER_END,
    /** Whether it moved camp this week (1) or stayed (0). */
    MOVED,
    /** How fed it was last week ([HexGroup.fed]), its people's sodium short last week ([Band.sodiumShort]), and the share of the protein it ate that went to waste ([Band.leanLost]). */
    FED_LAST, SODIUM_SHORT, LEAN_LOST,
    /** How fed it foresaw it would be at the place it chose, and at the best fed of all it weighed ([GroupPlanner.deathsAt]'s fed). */
    FED_CHOSEN, FED_BEST,
}

/** The [Warn]s the census averages over the deaths (the rest it bins, [WarnBin]). */
val WARN_STATE = Warn.entries.filter { it.ordinal >= Warn.FAT.ordinal }

/** Weeks of what bands foresaw kept for the census ([Band.warn]): the week they die and the four before. */
const val WARN_WEEKS = 5

/** The weeks back the census looks at: the week of the deaths (its choice at the week's start), and 1, 2 and 4 weeks before. */
val WARN_LAGS = intArrayOf(0, 1, 2, 4)

/** A share of a band foreseen to die in the week, binned for the census. */
enum class WarnBin(val label: String, val below: Float) {
    NOT_WEIGHED("not weighed", Float.NaN), NONE("none", 1e-4f), UNDER_1("under 1%", 0.01f), UNDER_5("1-5%", 0.05f),
    UNDER_20("5-20%", 0.2f), MOST("20% and over", Float.POSITIVE_INFINITY);
    companion object {
        fun of(share: Float) = if (share.isNaN()) NOT_WEIGHED else entries.first { it != NOT_WEIGHED && share < it.below }
    }
}

/** Which deaths the census sets against what was foreseen. */
enum class WarnDeaths(val label: String) { HUNGER("hunger"), THIRST("thirst"), OTHER("other causes");
    companion object { fun of(c: DeathReason) = when (c) { DeathCause.HUNGER -> HUNGER; DeathCause.THIRST -> THIRST; else -> OTHER } }
}

/**
 * The share of [g]'s people the week's hunger and thirst would kill at place [p], fed [fed] there: those parts of [deathsAt]
 * ([placeHazards]'s hunger and thirst, the young's at half as much again), by the same rules.
 */
internal fun GroupWorld.foreseenHungerThirst(g: HexGroup, p: Place, fed: Float): Float {
    val short = shortfallOfAdults(g, fed)
    val grown = grownHunger(g, short); val young = youngHunger(g, short, grown)
    val thirst = thirstOdds(g, p, dryAfter(g, p, fed))
    return (g.adults * (grown + thirst).coerceIn(0f, Hazards.MAX_GROWN_ODDS) +
        g.young * (1.5f * young + 1.5f * thirst).coerceIn(0f, Hazards.MAX_YOUNG_ODDS)) / max(1, g.size)
}

/** The slot of band [b]'s [Band.warn] for [lag] weeks back, [what]. */
private fun warnSlot(b: Band, lag: Int, what: Warn) = Math.floorMod(b.warnAt - lag, WARN_WEEKS) * Warn.entries.size + what.ordinal

/**
 * What band [g] foresaw this week of the places it weighed ([Scratch.explore]) and went to, [chosen], into [Band.warn]: the
 * week's hunger and thirst there and the least anywhere, and all it foresaw there. Report-only, for the census.
 */
internal fun HexGroups.recordWarning(g: HexGroup, b: Band, buf: GroupPlanner.Scratch, chosen: Place) {
    var least = Float.NaN; var there = Float.NaN; var seen = Float.NaN; var fedThere = Float.NaN; var fedBest = Float.NaN
    for (k in 0 until buf.explPlaces.size) {
        val p = Place(buf.explPlaces[k])
        val ht = foreseenHungerThirst(g, p, buf.explFed[k])
        if (least.isNaN() || ht < least) least = ht
        if (fedBest.isNaN() || buf.explFed[k] > fedBest) fedBest = buf.explFed[k]
        if (p == chosen) { there = ht; seen = buf.explDying[k]; fedThere = buf.explFed[k] }
    }
    b.warn[warnSlot(b, 0, Warn.CHOSEN)] = there; b.warn[warnSlot(b, 0, Warn.SEEN)] = seen; b.warn[warnSlot(b, 0, Warn.LEAST)] = least
    b.warn[warnSlot(b, 0, Warn.FED_CHOSEN)] = fedThere; b.warn[warnSlot(b, 0, Warn.FED_BEST)] = fedBest
}

/** Band [g]'s fat, burnt protein, days dry, larder by kind, last week's feeding and whether it moves camp, as the week starts, into [Band.warn]. Report-only, for the census. */
internal fun noteReserves(g: HexGroup, b: Band) {
    fun set(what: Warn, x: Float) { b.warn[warnSlot(b, 0, what)] = x }
    set(Warn.FAT, g.fat); set(Warn.SPENT, g.spent); set(Warn.DRY, g.dry); set(Warn.LARDER, g.larderDays())
    val need = max(1e-3f, g.dailyNeedKg)
    set(Warn.LARDER_DRIED, g.stores[Store.DRIED] / need); set(Warn.LARDER_SEED, g.stores[Store.SEED] / need)
    set(Warn.LARDER_ROOTS, g.stores[Store.ROOTS] / need)
    set(Warn.LARDER_OTHER, (g.stores[Store.FRESH] + g.stores[Store.FRUIT] + g.stores[Store.GREASE]) / need)
    set(Warn.MOVED, if (g.path.size > 1) 1f else 0f)
    set(Warn.FED_LAST, g.fed); set(Warn.SODIUM_SHORT, b.sodiumShort); set(Warn.LEAN_LOST, b.leanLost)
}

/**
 * Books band [b]'s (group [g]'s) deaths this week, [causes], against what it foresaw [WARN_LAGS] weeks before ([Band.warn]),
 * with its larder as they die ([Warn.LARDER_END]). For the census.
 */
internal fun HexGroups.bookWarning(g: HexGroup, b: Band, causes: List<Died>) {
    b.warn[warnSlot(b, 0, Warn.LARDER_END)] = g.larderDays()
    for (d in causes) {
        if (d.n <= 0) continue
        val of = WarnDeaths.of(d.cause)
        for (lag in WARN_LAGS) {
            for (what in arrayOf(Warn.CHOSEN, Warn.SEEN, Warn.LEAST)) stats.tally(Tally.warnDied(of, lag, what, WarnBin.of(b.warn[warnSlot(b, lag, what)])), d.n)
            for (what in WARN_STATE) {
                val x = b.warn[warnSlot(b, lag, what)]; if (x.isNaN()) continue
                stats.tally(Tally.warnReserves(of, lag, what, true), d.n)
                stats.tally(Tally.warnReserves(of, lag, what, false), (100f * d.n * x).roundToInt())
            }
        }
    }
}

/** At the week's end: band [g]'s people-weeks by what it foresaw for the week; then on to the next week's slot. For the census. */
internal fun HexGroups.weekWarning(g: HexGroup, b: Band) {
    for (what in arrayOf(Warn.CHOSEN, Warn.SEEN)) stats.tally(Tally.warnPeopleWeeks(what, WarnBin.of(b.warn[warnSlot(b, 0, what)])), g.size)
    b.warn[warnSlot(b, 0, Warn.LARDER_END)] = g.larderDays()
    b.warnAt = (b.warnAt + 1) % WARN_WEEKS
    for (what in Warn.entries) b.warn[warnSlot(b, 0, what)] = Float.NaN
}

/** Band [b] joining [a] ([Band.absorb]), at [w] of their adults: what each foresaw, week by week back, blended by adults where both saw it. */
internal fun blendWarning(a: Band, b: Band, w: Float) {
    for (lag in 0 until WARN_WEEKS) for (what in Warn.entries) {
        val ia = warnSlot(a, lag, what); val x = a.warn[ia]; val y = b.warn[warnSlot(b, lag, what)]
        a.warn[ia] = if (x.isNaN()) y else if (y.isNaN()) x else x + w * (y - x)
    }
}

/** The share of [n] in [of], or "-". */
internal fun warnShare(n: Int, of: Int) = if (of <= 0) "-" else "%.0f%%".format(100.0 * n / of)
