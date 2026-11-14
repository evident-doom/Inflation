package inflation.ecology

import inflation.world.Hex
import inflation.world.Place
import inflation.world.get
import inflation.world.set
import kotlin.math.exp

// People's pressure on the beasts of prey (docs/design/pack-performance.md, "Displaced by people"): the hunting kinds
// keep out of people's way where people are many or have killed
// their kind lately, a landscape of fear for the hunters as the kills make one for the game ([HexFauna.fear]).

/**
 * The people within a day's walk of each hex, per km², as the step begins ([HexFauna.peopleNear]): each band's heads
 * spread over its hex and the six about it (a forager's day out and back), by their land. Once a step, before the
 * foraging and the residents' moves read it.
 */
internal fun HexGroups.peopleAbout() {
    val near = fauna.peopleNear
    near.fill(0f)
    for (g in groups) {
        if (!g.alive || g.band == null) continue
        val h = hw.placeHex[g.place]
        var area = landKm2(h)
        for (d in 0 until 6) { val q = hex.neighbour(h, d); if (!q.none) area += landKm2(q) }
        if (!(area > 0f)) continue
        val each = g.size / area
        near[h] += each
        for (d in 0 until 6) { val q = hex.neighbour(h, d); if (!q.none) near[q] += each }
    }
}

/** The land of hex [h], km². */
internal fun GroupWorld.landKm2(h: Hex): Float { var a = 0f; for (q in hw.placesOf(h)) a += land.areaKm2[q]; return a }

/**
 * [heads] of resident hunter kind [k] killed by people at place [p] ([huntGame]), or the heads people would kill of it
 * hitting back for stock it took ([retaliate]): the kind's persecution there ([HexFauna.persecuted]). Made at the step's
 * start ([persecutionFades]): the foraging and the hunters' hunting add to it on all cores, a place to a job.
 */
internal fun HexFauna.persecutedBy(k: Int, p: Place, heads: Float) {
    if (!(heads > 0f) || !kinds[k].carnivore) return
    persecuted?.get(k)?.let { it[p] += heads / land.areaKm2[p] }
}

/** Kind [k]'s hunters took [stock] heads of a band's kept animals at place [p]: the band hunts them about ([RETALIATION] of theirs for each). */
internal fun HexFauna.retaliate(k: Int, p: Place, stock: Float) = persecutedBy(k, p, RETALIATION * stock)

/** The step's start: the persecution table made, and its fading at a rate a day ([PERSECUTION_DAYS]), the same at any step length. */
internal fun HexFauna.persecutionFades() {
    val all = persecuted ?: Array(kinds.size) { j -> if (kinds[j].carnivore) FloatArray(n) else null }.also { persecuted = it }
    val keep = exp(-days / PERSECUTION_DAYS)
    for (a in all) if (a != null) for (i in a.indices) a[i] *= keep
}

/**
 * How much people press resident hunter kind [k] at place [p], 0..1: the people about ([HexFauna.peopleNear], against
 * [PEOPLE_HALF]) and its kind killed there lately ([HexFauna.persecuted], as a share of its usual numbers against
 * [KILLED_HALF]), each saturating, times how much the kind fears people ([Species.fearOfPeople]) less the boldness of
 * those there ([HexFauna.bold]): the bold, camp followers, are not put off.
 */
internal fun HexFauna.pressure(k: FaunaKind, p: Place): Float {
    val s = kinds[k]
    if (!s.carnivore) return 0f
    val killed = persecuted?.get(k.i)?.let { it[p] / (usual[k] * KILLED_HALF) } ?: 0f
    val x = peopleNear[hw.placeHex[p]] / PEOPLE_HALF + killed
    return s.fearOfPeople * (1f - bold[k][p].coerceIn(0f, 1f)) * x / (1f + x)
}

/** How much people press a pack of [s] that is [boldness] bold at place [p], 0..1, by the people about only ([pressure]): packs' kinds keep no persecution. */
internal fun HexFauna.packPressure(s: Species, boldness: Float, p: Place): Float {
    val x = peopleNear[hw.placeHex[p]] / PEOPLE_HALF
    return s.fearOfPeople * (1f - boldness.coerceIn(0f, 1f)) * x / (1f + x)
}

/** Whether pack [g] is pressed off its ground by people ([packPressure] past [PACK_BEARS]): it grows restless. */
internal fun GroupWorld.pressedOff(g: HexGroup) = g.band == null && g.species.carnivore && fauna.packPressure(g.species, boldness(g), g.place) > PACK_BEARS

/** People per km² within a day's walk at which they put a hunter off half as much as at the most (a village's country; foragers' about a tenth of it); an estimate. */
private const val PEOPLE_HALF = 0.5f
/** The share of its usual numbers killed lately by people at which a hunter kind is put off half as much as at the most; an estimate. */
private const val KILLED_HALF = 0.2f
/** Days over which people's killing of a hunter kind fades by e (months: it remembers a season); an estimate. */
private const val PERSECUTION_DAYS = 120f
/** The hunters' heads people count against a kind for each head of stock it takes from them ([retaliate]): a stock-killer is hunted down; an estimate. */
private const val RETALIATION = 0.5f
/** The pressure past which a pack leaves its ground ([pressedOff]); an estimate. */
private const val PACK_BEARS = 0.25f
