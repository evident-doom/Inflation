package inflation.ecology

import inflation.ecology.HexFauna.Companion.FEAR_HALF
import inflation.ecology.HexFauna.Companion.YOUNG_WEIGHT
import inflation.world.Place
import inflation.world.Weather
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
 * Resident hunter [k] at place [p] hunts for up to [meat] of meat, as a pack of its kind would on the same ground (one
 * model for both): its grown go about in parties of its kind's usual size, each searching the place as a pack's party
 * searches its ground ([sweptKm2]'s going and senses, as far as the weather lets it see); each kind of game there, grown
 * and young, and the grown of packs of a kind that also roams ([packGrown]) and the herds kept at bands' camps, is come
 * on and killed at a pack's odds ([killChance], [seenFirst]), takes a pack's days to stalk, kill and eat, and is weighed
 * also by the days its danger costs ([dangerDays]); the party takes the best first while they raise its rate (Charnov's
 * rule, as [Offers.takeUp]) for as many of the week's days as it needs ([spent]: the party-days its hunting took this
 * week so far). A party new to every kind of game, as a resident hunter keeps no memory of what it met ([SEARCH_HALF]).
 * Of each kill it eats what a party can (a beast's whole prey, gorging included: [Nourishment.PREY]); what it cannot,
 * and the parts it leaves ([MEAT_SHARE]), are left as carrion. Returns what it ate.
 */
internal fun HexFauna.huntResidents(k: FaunaKind, p: Place, meat: KgM2, weather: Weather, spent: FloatArray): KgM2 {
    if (meat.v <= 0f) return KgM2.ZERO
    val hunter = kinds[k]
    val area = land.areaKm2[p]
    val partyHeads = (if (hunter.foragingParty > 0) minOf(hunter.foragingParty, hunter.groupSize) else hunter.groupSize).toFloat()
    val parties = adults[k][p] * area / partyHeads
    val daysLeft = days - spent[0]
    if (parties <= 0f || daysLeft <= 0f) return KgM2.ZERO
    // (What a party searches of the place in the week, and how open the ground and clear the view: as for a pack, [openness].)
    val seen = sightAt(land, p, weather)
    // (Over a step of several ticks the place is searched afresh each tick: as much as its area each.)
    val swept = min(area * clock.ticks, hunter.dailyTravelKm * days * 2f * hunter.senseKm * SEARCHING * seen)
    val cover = land.plants.cover(p)
    val open = VIGILANCE * (1f - cover) * (1f - hunter.stalking)
    val clear = 0.5f + 0.5f * seen / (1f - SIGHT_COVER * cover).coerceAtLeast(0.1f)
    val partyKg = partyHeads * hunter.massKg
    val eats = 1f / (partyKg * EATING_RATE)
    val most = max(partyHeads * hunter.dailyIntakeKg * days, partyKg * EATING_RATE * days)
    val image = 1f / 2f // (m / (m + SEARCH_HALF) at m = SEARCH_HALF: a party new to every kind)
    // The game on offer: each kind's grown (part 0), young (1) and packs' grown (2), and each camp's kept herds, grown
    // (3) and young (4); heads per km², each one's weight, its kills a party-day of searching, and the days each takes.
    val camps = keptAt?.get(p.i)
    val cap = 3 * kinds.size + (camps?.sumOf { 2 * it.herds.herd.size } ?: 0)
    val kindOf = IntArray(cap); val part = IntArray(cap); val campOf = IntArray(cap)
    val dens = FloatArray(cap); val mass = FloatArray(cap); val rate = FloatArray(cap); val time = FloatArray(cap); val eat = FloatArray(cap)
    var m = 0
    var game = 0f
    fun offer(j: Int, pt: Int, c: Int, d: Float, kg: Float, ps: Species, guard: Float, groupHeads: Float) {
        if (d <= 0f) return
        val catchKg = catchable(hunter, ps, kg, p)
        game += d * kg * catchKg
        val success = killChance(catchKg, 1f) * guard
        if (!(success > 0f)) return
        val met = (0.5f + 0.5f * Niches.overlap(hunter, ps)) * (1f - seenFirst(open, clear, null, ps)) / groupHeads.coerceAtLeast(1f)
        val r = d * swept * success * met * image / days
        if (!(r > 0f)) return
        val meat1 = min(kg, most)
        kindOf[m] = j; part[m] = pt; campOf[m] = c; dens[m] = d; mass[m] = kg; rate[m] = r; eat[m] = meat1
        time[m] = HANDLING_DAYS + STALK_DAYS / success.coerceIn(1e-3f, 1f) + meat1 * eats + dangerDays(hunter, ps, kg, partyHeads)
        m++
    }
    for ((j, s) in kinds.withKinds()) {
        if (j == k || s === hunter) continue
        val ym = YOUNG_WEIGHT * s.massKg
        offer(j.i, 0, -1, adults[j][p], s.massKg, s, 1f, s.groupSize.toFloat())
        offer(j.i, 1, -1, young[j][p], ym, s, 1f, s.groupSize.toFloat())
        offer(j.i, 2, -1, packAt(j.i, p), s.massKg, s, 1f, s.groupSize.toFloat())
    }
    // (The herds kept at bands' camps there, as their kind in the wild, come on as one band of all their heads, through
    // each band's guard against beasts: [stalkGuard], by night its fire, dogs, watch and palisade, by day its dogs.)
    if (camps != null) for ((c, b) in camps.withIndex()) {
        val guard = stalkGuard(b, 0f, b.fencedNow()); val yh = b.herds.youngHerd()
        for (j in b.herds.herd.indices) {
            val all = b.herds.herd[j] + yh[j]
            if (j == k.i || all < 1f) continue
            val s = kinds[j]
            offer(j, 3, c, b.herds.herd[j] / area, s.massKg, s, guard, all)
            offer(j, 4, c, yh[j] / area, YOUNG_WEIGHT * s.massKg, s, guard, all)
        }
    }
    if (m == 0) return KgM2.ZERO
    // (Best first by kg a day of handling; taken while each raises the party's rate.)
    val order = (0 until m).sortedByDescending { eat[it] / time[it] }
    var gain = 0f; var spentRate = 1f; var used = 0
    for (i in order) {
        if (eat[i] / time[i] <= gain / spentRate) break
        gain += rate[i] * eat[i]; spentRate += rate[i] * time[i]; used++
    }
    // (The days it hunts: as many as the meat asked for takes at that rate, as far as the week goes.)
    val intake = gain / spentRate
    if (!(intake > 0f)) return KgM2.ZERO
    val d = min(daysLeft, meat.perKm2.v * area / parties / intake)
    spent[0] += d
    var ate = 0f; var killedKg = 0f
    // (The hunters its kills cost, heads per km²: [foughtBack].)
    var fought = 0f
    for (r in 0 until used) {
        val i = order[r]
        // (Taken from what is there as it falls, at most half of it a tick, as for a pack: [takenOfGame].)
        val kills = takenOfGame(parties * d * rate[i] / spentRate, dens[i] * area, days, clock.tickDays)
        if (!(kills > 0f)) continue
        val j = kindOf[i]; val s = kinds[j]; val kg = mass[i]
        val perKm2 = kills / area
        when (part[i]) {
            0 -> { adults[j][p] = max(0f, adults[j][p] - perKm2); huntedByResidents[j][p] += kills; killsByHunter[2 * (k.i * kinds.size + j)].add(kills.toDouble()) }
            1 -> { young[j][p] = max(0f, young[j][p] - perKm2); huntedByResidents[j][p] += kills; killsByHunter[2 * (k.i * kinds.size + j) + 1].add(kills.toDouble()) }
            2 -> { packKills += PackKill(p.i, j, kills, -1L - k.i); packGrown[j]?.let { it[p.i] = max(0f, it[p.i] - perKm2) } }
            else -> { val b = camps?.get(campOf[i]) ?: continue
                // (What it takes of the herds comes out of the bands' herds, the band to learn of it as it keeps them: [Herd.herdTaken].)
                val from = if (part[i] == 4) b.herds.youngHerd() else b.herds.herd
                val heads = min(from[j], kills)
                from[j] -= heads
                if (HUNT_DIAG) campTaken[j][if (part[i] == 4) 0 else 1].add(heads.toDouble())
                if (b.herds.herdTaken.size != b.herds.herd.size) b.herds.herdTaken = FloatArray(b.herds.herd.size)
                b.herds.herdTaken[j] += heads
                retaliate(k.i, p, heads)
                keptTakenBy.computeIfAbsent(hunter) { DoubleAdder() }.add(heads.toDouble())
            }
        }
        if (HUNT_DIAG && camps != null && part[i] <= 1) campTaken[j][if (part[i] == 1) 2 else 3].add(kills.toDouble())
        fought += perKm2 * foughtBack(hunter, s, kg)
        killedKg += kills * kg
        val eaten = kills * eat[i]
        ate += eaten
        // (What it could not eat of each kill, and the parts it leaves of what it did, lie as carrion.)
        small.deposit(DeadPool.CARRION, p, KgKm2((kills * (kg - eat[i]) + eaten * (1f - MEAT_SHARE)) / area).perM2)
        ateBook?.add(-(eaten * MEAT_SHARE).toDouble())
    }
    // (The kg it killed of the game as catchable, for the odds of being hunted: [residentKillOdds].)
    if (game > 0f) killRate[k]?.let { it[p] += killedKg / area / game }
    // (Those its game killed die, left where they fell; their meat is no longer to be found.)
    if (fought > 0f) {
        val dead = min(fought, adults[k][p])
        adults[k][p] -= dead
        foughtBackDead[k][p] += dead * area
        small.deposit(DeadPool.CARRION, p, KgKm2(dead * hunter.massKg).perM2)
    }
    // (Game takes fright at the kills, by their weight.)
    killed(p, Kg(killedKg))
    return KgKm2(ate / area).perM2
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
