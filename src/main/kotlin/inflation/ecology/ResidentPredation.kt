package inflation.ecology

import inflation.ecology.HexFauna.Companion.FEAR_HALF
import inflation.ecology.HexFauna.Companion.RESIDENT_KILL_MOST
import inflation.ecology.HexFauna.Companion.YOUNG_WEIGHT
import inflation.world.Place
import inflation.world.get
import inflation.world.set
import inflation.ecology.HexSmallLife.DeadPool
import inflation.Kg
import inflation.KgKm2
import inflation.KgM2
import inflation.min
import kotlin.math.max
import kotlin.math.min
import java.util.concurrent.atomic.DoubleAdder

// The residents' hunters and their game: what each hunter sees and can catch, the kills they make, and the fear that keeps game away.

/** Notes [kg] of game killed at place [p], for the fear of it. */
fun HexFauna.killed(p: Place, kg: Kg) { fear[p] += kg.v / land.areaKm2[p] }

/** The odds that an animal of [kg] about place [p] is killed by the residents' hunters in a week, as they hunted there this week ([killRate]). */
fun HexFauna.residentKillOdds(kg: Float, p: Place): Float {
    val rates = killRate
    var v = 0f
    for (k in kindIds) { val r = rates[k] ?: continue; if (r[p] > 0f) v += r[p] * kinds[k].preySize(kg) }
    return v
}

/** The heads of kind [k] kept by bands camped at [p] ([keptAt]), per km² of the place: they crowd its residents of their kind ([hazards]). */
internal fun HexFauna.keptKm2(k: FaunaKind, p: Place): Float {
    val bs = keptAt?.get(p.i) ?: return 0f
    var h = 0f
    for (b in bs) h += b.herds.herd.getOrElse(k.i) { 0f } + b.herds.herdYoung.getOrElse(k.i) { 0f }
    return h / land.areaKm2[p]
}

/**
 * The odds that a resident of kind [k] at place [p] (one of its young if [young]) is killed by hunters in a week, all of
 * them: the residents' hunters as they hunted there this week ([residentKillOdds]) and the hunting parties as they did
 * in their last week ([groupKilled]).
 */
fun HexFauna.huntedOdds(k: FaunaKind, p: Place, young: Boolean): Float {
    val s = kinds[k]
    return residentKillOdds(if (young) YOUNG_WEIGHT * s.massKg else s.massKg, p) + groupKilled[k][p]
}

/**
 * Resident hunter [k] at place [p] kills game among the residents there, for up to [meat] of meat: each other kind,
 * grown and young, as it is common there, by its weight and how catchable it is ([catchable], as [game] counts it),
 * at most [RESIDENT_KILL_MOST] of that game in a week; what it cannot
 * eat of a kill is left as carrion. Returns what it ate.
 */
internal fun HexFauna.huntResidents(k: FaunaKind, p: Place, meat: KgM2, killedSoFar: FloatArray): KgM2 {
    if (meat.v <= 0f) return KgM2.ZERO
    val hunter = kinds[k]
    val area = land.areaKm2[p]
    var total = 0f
    // (Weights in kg per km2, as the numbers are kept.)
    val wA = FloatArray(kinds.size); val wY = FloatArray(kinds.size)
    // (Each kind by how catchable it is there, grown and young, as [game] counts it, and as a hunting party weighs game that
    // may kill one of it: by the days a kill takes over those and the days its danger costs ([dangerDays]).)
    // (The cap on its week's kills is of the game as catchable, whatever its danger: [game].)
    val partyHeads = (if (hunter.foragingParty > 0) minOf(hunter.foragingParty, hunter.groupSize) else hunter.groupSize).toFloat()
    var game = 0f
    for ((j, s) in kinds.withKinds()) {
        if (j == k || adults[j][p] + young[j][p] <= 0f) continue
        val ym = YOUNG_WEIGHT * s.massKg
        val a = adults[j][p] * s.massKg * catchable(hunter, s, s.massKg, p); val y = young[j][p] * ym * catchable(hunter, s, ym, p)
        game += a + y
        wA[j] = a * HANDLING_DAYS / (HANDLING_DAYS + dangerDays(hunter, s, s.massKg, partyHeads))
        wY[j] = y * HANDLING_DAYS / (HANDLING_DAYS + dangerDays(hunter, s, ym, partyHeads))
        total += wA[j] + wY[j]
    }
    // (And the herds kept at bands' camps there, as their kind in the wild, through each band's guard against beasts
    // ([stalkGuard]: by night its fire, dogs, watch and palisade, by day its dogs); kg per km² as the game.)
    val camps = keptAt?.get(p.i)
    // (The grown at [j], the young at herd.size + j.)
    val wK = if (camps != null) Array(camps.size) { c -> val b = camps[c]; val guard = stalkGuard(b, 0f, b.fencedNow()); val yh = b.herds.youngHerd()
        val nk = b.herds.herd.size
        FloatArray(2 * nk) { i -> val j = i % nk; val y = i >= nk; val heads = if (y) yh[j] else b.herds.herd[j]
            if (j == k.i || b.herds.herd[j] + yh[j] < 1f || heads <= 0f) 0f else { val s = kinds[j]; val m = if (y) YOUNG_WEIGHT * s.massKg else s.massKg
                (heads / area * m * catchable(hunter, s, m, p) * guard).also { total += it; game += it } } } } else null
    if (total <= 0f) return KgM2.ZERO
    // (Its need is reckoned in the weight of the prey it kills, the parts it leaves included ([Allometry.dailyIntakeKg]);
    // of each kill it eats [MEAT_SHARE] and leaves the rest as carrion. At most [RESIDENT_KILL_MOST] of the game in the
    // week, all its hunting that week together ([killedSoFar]: what it took before, kg per km²). The game, [total] and
    // the kills are kept per km²; the carrion per m².)
    val want = min(meat.perKm2, KgKm2(RESIDENT_KILL_MOST * (game + killedSoFar[0]) - killedSoFar[0]))
    if (want.v <= 0f) return KgM2.ZERO
    killedSoFar[0] += want.v
    killRate[k]?.let { it[p] += want.v / total }
    var ate = KgM2.ZERO
    // (The hunters its kills cost, heads per km²: [foughtBack].)
    var fought = 0f
    for ((j, s) in kinds.withKinds()) {
        if (wA[j] + wY[j] <= 0f) continue
        val kg = want.v * (wA[j] + wY[j]) / total
        val fromA = kg * wA[j] / (wA[j] + wY[j]); val fromY = kg - fromA
        fought += fromA / s.massKg * foughtBack(hunter, s, s.massKg) + fromY / (YOUNG_WEIGHT * s.massKg) * foughtBack(hunter, s, YOUNG_WEIGHT * s.massKg)
        adults[j][p] = max(0f, adults[j][p] - fromA / s.massKg)
        young[j][p] = max(0f, young[j][p] - fromY / (YOUNG_WEIGHT * s.massKg))
        huntedByResidents[j][p] += (fromA / s.massKg + fromY / (YOUNG_WEIGHT * s.massKg)) * land.areaKm2[p]
        if (HUNT_DIAG && camps != null) { campTaken[j][2].add((fromY / (YOUNG_WEIGHT * s.massKg) * area).toDouble()); campTaken[j][3].add((fromA / s.massKg * area).toDouble()) }
        small.deposit(DeadPool.CARRION, p, KgKm2(kg * (1f - MEAT_SHARE)).perM2)
        ate += KgKm2(kg).perM2
        ateBook?.add(-(kg * MEAT_SHARE * area).toDouble())
    }
    // (What it takes of the herds comes out of the bands' herds, the band to learn of it as it keeps them: [Herd.herdTaken].)
    if (camps != null && wK != null) for ((c, b) in camps.withIndex()) for (i in wK[c].indices) {
        if (wK[c][i] <= 0f) continue
        val nk = b.herds.herd.size; val j = i % nk; val s = kinds[j]
        val from = if (i >= nk) b.herds.youngHerd() else b.herds.herd; val m = if (i >= nk) YOUNG_WEIGHT * s.massKg else s.massKg
        val heads = min(from[j], want.v * wK[c][i] / total * area / m)
        val kg = heads * m / area
        from[j] -= heads
        if (HUNT_DIAG) campTaken[j][if (i >= nk) 0 else 1].add(heads.toDouble())
        if (b.herds.herdTaken.size != b.herds.herd.size) b.herds.herdTaken = FloatArray(b.herds.herd.size)
        b.herds.herdTaken[j] += heads
        keptTakenBy.computeIfAbsent(hunter) { DoubleAdder() }.add(heads.toDouble())
        small.deposit(DeadPool.CARRION, p, KgKm2(kg * (1f - MEAT_SHARE)).perM2)
        ate += KgKm2(kg).perM2
        ateBook?.add(-(kg * MEAT_SHARE * area).toDouble())
    }
    // (Those its game killed die, left where they fell; their meat is no longer to be found.)
    if (fought > 0f) {
        val dead = min(fought, adults[k][p])
        adults[k][p] -= dead
        foughtBackDead[k][p] += dead * area
        small.deposit(DeadPool.CARRION, p, KgKm2(dead * hunter.massKg).perM2)
    }
    // (Game takes fright at the kills, by their weight.)
    killed(p, Kg(want.v * area))
    return ate
}

/** How much fear puts game off a place, 0..1. */
fun HexFauna.afraid(p: Place) = fear[p] / (fear[p] + FEAR_HALF)

/**
 * The resident game hunter [s] sees at place [p], kg per km²: every other kind there, grown and young (at
 * [YOUNG_WEIGHT]), by its weight and how catchable it is ([catchable]). Resident hunters and the groups count it alike.
 */
fun HexFauna.game(s: Species, p: Place): Float {
    var v = 0f
    for ((j, ps) in kinds.withKinds()) {
        if (ps === s) continue
        val a = adults[j][p]; val y = young[j][p]
        if (a + y <= 0f) continue
        val ym = YOUNG_WEIGHT * ps.massKg
        v += a * ps.massKg * catchable(s, ps, ps.massKg, p) + y * ym * catchable(s, ps, ym, p)
    }
    return v
}

/**
 * How catchable an animal of kind [ps] weighing [massKg] (grown, or young) is for hunter [s] at place [p] this week,
 * 0..1: its size against what the hunter takes ([Species.preySize]), defence, refuge in broken ground, small prey
 * hiding in thick cover, and for stalkers, dark nights.
 */
fun HexFauna.catchable(s: Species, ps: Species, massKg: Float, p: Place): Float =
    (s.preySize(massKg) * (1f - ps.defence) * closingIn(s, ps, massKg, p)).coerceIn(0f, 1f)

private fun HexFauna.closingIn(s: Species, ps: Species, massKg: Float, p: Place): Float {
    // Shelter: crags, dens, thickets and trees, as the prey uses them; small prey hide in any cover.
    // (Hunters that follow the scent find much of what hides; shelter counts for half against them.)
    // (But crags are a wild goat's whole defence: no hunter follows it up a cliff.)
    val k = slots[ps.index]
    val shelter = if (k >= 0) Niches.shelter(ps, p, land, groundShelter[k][p]) else Niches.shelter(ps, p, land)
    val refuge = 1f - (if (ps.habitat.rugged >= 0.5f) 1f else 0.5f) * SHELTER_EFFECT * shelter
    // Small prey hide in thick cover from hunters that run them down; a stalker uses the same cover to get close.
    val hide = 1f - HIDING * land.plants.cover(p) * (1f - s.stalking) * max(0f, 1f - massKg / HIDING_KG)
    // Stalkers hunt by night: dark weeks around the new moon hide them, bright ones give them away.
    val dark = 1f + MOON_DARK * s.stalking * (1f - 2f * moon)
    // A courser must run its quarry down; a stalker gets close unseen and needs no speed.
    val speed = s.stalking + (1f - s.stalking) * min(1f, s.speedKmh / ps.speedKmh)
    // And it needs room to run: in thick woods and scrub a chase ends at the first tangle.
    val room = 1f - COURSING_COVER * (1f - s.stalking) * land.plants.cover(p)
    return refuge * hide * dark * speed * room
}

/** How much fear of hunters puts kind [s] off place [p], 0..1: none for the hunters themselves. */
internal fun HexFauna.shy(s: Species, p: Place) = if (s.carnivore) 0f else afraid(p)

/**
 * How much a rival [s] ([Catalogue.residentRivals]) is put off place [p] by a much stronger hunting party standing there
 * ([strongest]): by the rule packs keep ([AVOID_STRONGER] where it is more than twice its band's [residentStrength]).
 */
internal fun HexFauna.outranked(s: Species, p: Place): Float {
    if (s !in Catalogue.residentRivals) return 0f
    return if (strongest[p] > 2f * residentStrength(s)) AVOID_STRONGER else 0f
}
