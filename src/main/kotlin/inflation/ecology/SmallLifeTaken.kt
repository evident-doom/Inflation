package inflation.ecology

import inflation.ecology.HexSmallLife.Companion.DORMANT_RAIN_MM
import inflation.ecology.HexSmallLife.Companion.FLIGHT_REACH
import inflation.ecology.HexSmallLife.Companion.INSECT_CATCH
import inflation.ecology.HexSmallLife.Companion.SMALL_FIND_HALF
import inflation.world.Place
import inflation.world.get
import inflation.world.set
import inflation.Kg
import inflation.KgKm2
import inflation.KgM2
import inflation.min
import inflation.world.Weather
import kotlin.math.max
import kotlin.math.min

// What the larger animals and the bands take of small life: small prey reached and caught, carrion and bones scavenged.

/**
 * Share of small prey [prey] (an index of [huntedAll]; birds all as one) that a hunter of [style]
 * can reach at place [p] among the grown ones: how the prey escapes against how the hunter hunts.
 * Stalking and pouncing beasts ([SmallHunt.STALK]: foxes, jackals, lynx) catch rodents and hares out
 * foraging, hyrax only away from their rocks, reptiles only out of cover ([herpExposed]), grown
 * tortoises not at all (the shell: only the young of the year, whose shells are still soft), and
 * flying birds only as far as they are caught on the ground ([FLIGHT_REACH]). Rooting beasts
 * ([SmallHunt.ROOT]: boar, bears) catch none of the grown, only what is in the nest. People ([SmallHunt.PEOPLE], and
 * the hunting packs) reach all, with snares, nets and digging sticks.
 */
fun HexSmallLife.reach(style: SmallHunt, prey: SmallPrey, p: Place): Float = when (style) {
    SmallHunt.PEOPLE -> 1f
    SmallHunt.ROOT -> when (prey) { SmallPrey.LOCUSTS -> 1f; SmallPrey.INSECTS, SmallPrey.POLLINATORS -> INSECT_CATCH; else -> 0f }
    SmallHunt.STALK -> when (prey) {
        SmallPrey.HYRAX -> rockHides[p]
        SmallPrey.HERPS -> herpExposed(p)
        SmallPrey.TORTOISES -> 0f
        SmallPrey.INSECTS, SmallPrey.POLLINATORS -> INSECT_CATCH
        SmallPrey.BIRDS -> FLIGHT_REACH
        else -> 1f
    }
}

/** What share of [stock] a hunter reaches when the grown are reached at [adults] and the [y] young all. */
internal fun HexSmallLife.reachable(stock: Float, y: Float, adults: Float) = if (stock > 0f) (adults * (stock - min(y, stock)) + min(y, stock)) / stock else 0f

/**
 * Takes up to [want] of small prey in place [p]: rodents, hares, hyrax and birds, locust
 * swarms, and when they are out, reptiles, amphibians and tortoises, each in proportion to what
 * a hunter of [style] can reach of it ([reach]; the young in the nest any can take), at most half
 * of all; and the scattered insects that are out (plant-eaters and pollinators) as far as a
 * vertebrate hunting by sight can pick them up ([INSECT_CATCH]). Returns what was caught.
 */
fun HexSmallLife.catchSmall(p: Place, want: KgKm2, weather: Weather, most: Float = 0.5f, who: Taker = Taker.GROUPS, style: SmallHunt = SmallHunt.PEOPLE): KgKm2 {
    val t = weather.temperature[p]
    val herpsOut = herpsActive(t, land.hydro.soil[p])
    val tortOut = t in 14f..33f
    // Desert rodents sleep through the summer heat in their burrows.
    val rodentsOut = !(t > 30f && land.hydro.soil[p] < 10f && annualRain[p] < DORMANT_RAIN_MM)
    // (Prey that is not out is not met: in the same order as [huntedAll].)
    val yr = young[Nest.RODENTS][p]
    val yha = young[Nest.HARES][p]
    val yhy = young[Nest.HYRAX][p]
    val yb = young[Nest.BIRDS][p]
    val yh = young[Nest.HERPS][p]
    val yt = young[Nest.TORTOISES][p]
    val eR = if (rodentsOut) reachable(rodents[p], yr, reach(style, SmallPrey.RODENTS, p)) else 0f
    val eHa = reachable(hares[p], yha, reach(style, SmallPrey.HARES, p)); val eHy = reachable(hyrax[p], yhy, reach(style, SmallPrey.HYRAX, p))
    val eH = if (herpsOut) reachable(herps[p], yh, reach(style, SmallPrey.HERPS, p)) else 0f
    val eB = reachable(birds[p], yb, reach(style, SmallPrey.BIRDS, p))
    val eT = if (tortOut) reachable(tortoises[p], yt, reach(style, SmallPrey.TORTOISES, p)) else 0f
    val eI = reach(style, SmallPrey.INSECTS, p)
    val ease = floatArrayOf(eR, eHa, eHy, 1f, eH, eT, eI, eI) + FloatArray(bird.size) { eB }
    val total = eR * rodents[p] + eHa * hares[p] + eHy * hyrax[p] + eB * birds[p] + eH * herps[p] + eT * tortoises[p] + locusts[p] +
        eI * (insects[p] + pollinators[p])
    if (total <= 0f) return KgKm2.ZERO
    val h0 = herps[p]; val t0 = tortoises[p]
    val r0 = rodents[p]; val ha0 = hares[p]; val hy0 = hyrax[p]; val b0 = birdSum(p)
    val took = takeSwitching(p, min(want.v, most * total), most, huntedAll, ease)
    // (Of what was taken of each kind, the young's share came out of the nest.)
    fun tookYoung(i: Nest, before: Float, now: Float, e: Float) {
        if (before > 0f && e > 0f) young[i][p] = max(0f, young[i][p] - (before - now) * min(1f, young[i][p] / (e * before)))
    }
    tookYoung(Nest.RODENTS, r0, rodents[p], eR); tookYoung(Nest.HARES, ha0, hares[p], eHa); tookYoung(Nest.HYRAX, hy0, hyrax[p], eHy)
    tookYoung(Nest.BIRDS, b0, birdSum(p), eB); tookYoung(Nest.HERPS, h0, herps[p], eH); tookYoung(Nest.TORTOISES, t0, tortoises[p], eT)
    herpLedger[HerpLedger.HUNTED][p] += h0 - herps[p]
    herpTakenBy[who.atMostGroups][p] += h0 - herps[p]
    tortLedger[TortLedger.HUNTED][p] += t0 - tortoises[p]; tortTakenBy[who.atMostGroups][p] += t0 - tortoises[p]
    noteTaken(p, who.atMostGroups, r0, ha0, hy0, b0)
    return KgKm2(took)
}

internal fun HexSmallLife.noteTaken(p: Place, who: Taker, r0: Float, ha0: Float, hy0: Float, b0: Float) {
    val t = preyTakenBy
    t[Prey.RODENTS][who][p] += r0 - rodents[p]
    t[Prey.HARES][who][p] += ha0 - hares[p]
    t[Prey.HYRAX][who][p] += hy0 - hyrax[p]
    t[Prey.BIRDS][who][p] += b0 - birdSum(p)
}

/**
 * Takes up to [want] kg/km² at place [p] from [stocks], at most [most] of each. Hunters switch
 * to what is common (as with large game): a kind thin on the ground is seldom met and passed over
 * for the common ones, each weighted by how easily it is found. [ease] (one per stock, 1 when
 * null) is the share of a kind the hunter can take at all: it counts and takes only that share.
 * Returns kg/km² taken.
 */
internal fun HexSmallLife.takeSwitching(p: Place, want: Float, most: Float, stocks: Array<FloatArray>, ease: FloatArray? = null): Float {
    if (want <= 0f) return 0f
    var weight = 0f
    for ((i, s) in stocks.withIndex()) { val x = s[p] * (ease?.get(i) ?: 1f); weight += x * x / (x + SMALL_FIND_HALF) }
    if (weight <= 0f) return 0f
    var took = 0f
    for ((i, s) in stocks.withIndex()) {
        val x = s[p] * (ease?.get(i) ?: 1f)
        val g = min(most * x, want * x * x / (x + SMALL_FIND_HALF) / weight)
        s[p] -= g; took += g
    }
    var all = 0f; for (b in bird) all += b[p]; birds[p] = all
    return took
}

/**
 * A hunter searching [sweptKm2] of place [p] takes up to [want] of small prey: at most half of what
 * lives on the ground it searched. What it takes comes off the place's stocks; returns what was taken.
 */
fun HexSmallLife.catchSmallIn(p: Place, want: Kg, sweptKm2: Float, weather: Weather): Kg {
    val area = land.areaKm2[p]
    // (Over a step of several ticks the ground is searched again each tick, as much as its area each, and half of the
    // small game on what was searched is had each tick: compounded over the ticks, as the game is not much replaced within a step.)
    val searched = if (ticks == 1) min(sweptKm2, area) else min(sweptKm2 / ticks, area)
    if (want.v <= 0f || searched <= 0f) return Kg.ZERO
    return catchSmall(p, want.perKm2(area), weather, overTicks(0.5f * searched / area)).over(area)
}

/** A scavenger searching [sweptKm2] of place [p] takes up to [want] of carrion, at most half of what lies on the ground it searched; returns what was taken. */
fun HexSmallLife.scavengeIn(p: Place, want: Kg, sweptKm2: Float, foundShare: Float = 0f): Kg {
    val area = land.areaKm2[p]; val m2 = area * 1e6f
    val searched = min(sweptKm2, area)
    if (want.v <= 0f || searched <= 0f) return Kg.ZERO
    // (What the groups found as it lay through the step first ([HexSmallLife.groupCarrion]: found already), then at most half of
    // what lies now on the ground searched.)
    val wantM2 = want.v / m2
    // (Of that, the share its own search found: [HexSmallLife.groupCarrionFound].)
    val held = minOf(groupCarrion[p], foundShare * groupCarrionFound[p], wantM2); groupCarrion[p] -= held
    val now = if (wantM2 > held) scavenge(p, KgM2(min(wantM2 - held, 0.5f * carrion[p] * searched / area))).v else 0f
    flow(CarrionFlow.GROUPS_ATE, p, held + now)
    return KgM2(held + now).over(m2)
}

/**
 * A resident takes up to [want] (kg/m²) of carrion at place [p]: what the residents found as it lay ([HexSmallLife.residentCarrion])
 * first, up to [most] of it (its share), then at most half of what lies now. Returns what was taken, and of it what was held.
 */
fun HexSmallLife.scavengeHeld(p: Place, want: Float, most: Float): Pair<Float, Float> {
    if (want <= 0f) return 0f to 0f
    val held = minOf(residentCarrion[p], want, max(0f, most)); residentCarrion[p] -= held
    val lying = if (want > held) scavenge(p, KgM2(min(want - held, 0.5f * carrion[p]))).v else 0f
    flow(CarrionFlow.RESIDENTS_HELD, p, held); flow(CarrionFlow.RESIDENTS_LYING, p, lying)
    return (held + lying) to held
}

/** As the residents of place [p] have fed: what they found and left goes back on the ground. */
fun HexSmallLife.settleResidentCarrion(p: Place) { flow(CarrionFlow.HELD_BACK, p, residentCarrion[p]); carrion[p] += residentCarrion[p]; residentCarrion[p] = 0f }

/** At a step's end: what was held for the groups and left goes back on the ground. */
fun HexSmallLife.settleGroupCarrion() {
    for (p in 0 until n) { carrion[p] += groupCarrion[p]; groupCarrion[p] = 0f }
}

/** The same for bones, for bone-crackers. */
fun HexSmallLife.crackBonesIn(p: Place, want: Kg, sweptKm2: Float): Kg {
    val area = land.areaKm2[p]; val m2 = area * 1e6f
    val searched = min(sweptKm2, area)
    if (want.v <= 0f || searched <= 0f) return Kg.ZERO
    return crackBones(p, min(want, Kg(0.5f * bones[p] * searched * 1e6f)).perM2(m2)).over(m2)
}

/**
 * Small prey on the ground at [p], kg/km²: rodents, hares, hyrax, game birds, reptiles and
 * tortoises, locust swarms, and the insects a hunter can pick up (as [catchSmall]).
 */
fun HexSmallLife.smallPreyKg(p: Place) = rodents[p] + hares[p] + hyrax[p] + birds[p] + herps[p] + tortoises[p] + locusts[p] + INSECT_CATCH * (insects[p] + pollinators[p])

/** Takes up to [want] of carrion in place [p]; returns what was taken. */
fun HexSmallLife.scavenge(p: Place, want: KgM2): KgM2 { val t = min(carrion[p], want.v); carrion[p] -= t; flow(CarrionFlow.TAKEN, p, t); record?.let { it.carrionGone[p] += t }; return KgM2(t) }

/** Takes up to [want] of bones in place [p] (for bone-crackers); returns what was taken. */
fun HexSmallLife.crackBones(p: Place, want: KgM2): KgM2 { val t = min(bones[p], want.v); bones[p] -= t; record?.let { it.bonesGone[p] += t }; return KgM2(t) }
