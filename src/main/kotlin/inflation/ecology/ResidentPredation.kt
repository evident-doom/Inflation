package inflation.ecology

import inflation.ecology.HexFauna.Companion.FEAR_HALF
import inflation.ecology.HexFauna.Companion.YOUNG_WEIGHT
import inflation.world.Place
import inflation.world.Weather
import inflation.world.get
import inflation.world.set
import inflation.ecology.HexSmallLife.DeadPool
import inflation.Kg
import inflation.Par
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
fun HexFauna.residentKillOdds(kg: Float, p: Place, young: Boolean = false): Float {
    val rates = killRate
    var v = 0f
    for (k in kindIds) { val r = rates[k] ?: continue; if (r[p] > 0f) v += r[p] * kinds[k].preySize(kg, young) }
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
    val kg = if (young) youngKg(k.i) else s.massKg
    val own = tuning.ownDanger
    val bySize = if (own < 1f) residentKillOdds(kg, p, young) else 0f
    val byKind = if (own > 0f) ownKillOdds(s, kg, p) else 0f
    return (1f - own) * bySize + own * byKind + groupKilled[k][p]
}

/**
 * The odds that an animal of kind [ps] weighing [kg] at place [p] is killed by the residents' hunters in a week, as they
 * hunted there this week: each hunter's take per kg of the game it could catch there ([killRate], reckoned on
 * [catchable] kg) times how catchable this one is to it there ([catchable]: its shelter, speed and watchfulness on this
 * ground). So a wild goat by its crags, a roe in its thickets and an onager on the open plain know themselves safer
 * there than where they are caught.
 */
fun HexFauna.ownKillOdds(ps: Species, kg: Float, p: Place): Float {
    val rates = killRate
    val sh = preyShelter(ps, p); val cover = land.plants.cover(p)
    var v = 0f
    for (h in kindIds) { val r = rates[h] ?: continue; if (r[p] > 0f && kinds[h] !== ps) v += r[p] * catchable(kinds[h], ps, kg, p, sh, cover) }
    return v
}

/**
 * Folds the step's [huntedOdds] of each plant-eating kind into [HexFauna.huntedLately] and [HexFauna.huntedYoungLately],
 * the old fading by [HexFauna.FEAR_KEEP] a tick (compounded over a step of several): every step, moving or not, after
 * the residents' hunters have hunted and with the parties' last week's kills ([groupKilled]).
 */
internal fun HexFauna.noteHunted() {
    val keep = clock.keep(HexFauna.FEAR_KEEP)
    for (k in kindIds) {
        if (kinds[k].carnivore) continue
        val g = huntedLately[k]; val y = huntedYoungLately[k]
        Par.forEachPlace("residents: hunted lately", n) { p ->
            g[p] = keep * g[p] + (1f - keep) * huntedOdds(k, p, false)
            y[p] = keep * y[p] + (1f - keep) * huntedOdds(k, p, true)
        }
    }
}

/**
 * Resident hunter [k] at place [p] hunts for up to [meat] of meat, as a pack of its kind would on the same ground (one
 * model for both): its grown go about in parties of its kind's usual size, each searching the place as a pack's party
 * searches its ground ([sweptKm2]'s going and senses, as far as the weather lets it see); each kind of game there, grown
 * and young, and the grown of packs of a kind that also roams ([packGrown]) and the herds kept at bands' camps, is come
 * on and killed at a pack's odds ([killChance], [seenFirst]), takes a pack's days to stalk, kill and eat, and is weighed
 * also by the days its danger costs ([dangerDays]); the party takes the best first while they raise its rate (Charnov's
 * rule, as [Offers.takeUp]) for as many of the week's days as it needs ([spent]: the party-days its hunting took this
 * week so far). Its search image of each kind is a pack's at as many meetings as it has here ([SEARCH_HALF]).
 * Of each kill it eats what a party can (a beast's whole prey, gorging included: [Nourishment.PREY]), the best of it first
 * ([bestFirst]), after the scavengers about have had their share ([scavengedShare]); what it cannot,
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
    // (What the scavengers about take of each kill before the hunter has eaten it: [scavengedShare]. It goes on hunting
    // the sooner, and what they took lies as carrion for them.)
    val lost = scavengedShare(k, p)
    val most = max(partyHeads * hunter.dailyIntakeKg * days, partyKg * EATING_RATE * days)
    // The game on offer: each kind's grown (part 0), young (1) and packs' grown (2), and each camp's kept herds, grown
    // (3) and young (4); heads per km², each one's weight, its kills a party-day of searching, and the days each takes.
    val camps = keptAt?.get(p.i)
    val cap = 3 * kinds.size + (camps?.sumOf { 2 * it.herds.herd.size } ?: 0)
    val kindOf = IntArray(cap); val part = IntArray(cap); val campOf = IntArray(cap)
    val dens = FloatArray(cap); val mass = FloatArray(cap); val rate = FloatArray(cap); val time = FloatArray(cap); val eat = FloatArray(cap)
    // (What the eaten share of each kill is worth, in kg of its meat as the whole carcass is: [bestFirst].)
    val worth = FloatArray(cap)
    var m = 0
    var game = 0f
    // (The young of a kind that lie out ([Species.liesOut]) in their first months: come on one by one, and not watching.)
    val month = weather.date.month
    fun offer(j: Int, pt: Int, c: Int, d: Float, kg: Float, ps: Species, guard: Float, groupHeads: Float, shelter: Float, alone: Boolean = false) {
        if (d <= 0f) return
        val catchKg = catchable(hunter, ps, kg, p, shelter, cover)
        game += d * kg * catchKg
        val success = killChance(catchKg, 1f) * guard
        if (!(success > 0f)) return
        val met = (0.5f + 0.5f * Niches.overlap(hunter, ps)) * (if (alone) 1f else (1f - seenFirst(open, clear, null, ps)) / groupHeads.coerceAtLeast(1f))
        // (Its search image of the kind, by the packs' rule, m / (m + SEARCH_HALF): m the meetings with it over SEARCH_DAYS, as
        // often as it comes on them here. A resident lives its life on this ground and knows its game: it keeps to the kinds it
        // meets often and passes up what has grown rare there, the switching that lets scarce game recover.)
        val meets = d * swept * met / days
        val known = meets * SEARCH_DAYS.toFloat()
        val r = meets * success * known / (known + SEARCH_HALF)
        if (!(r > 0f)) return
        val meat1 = min(kg * (1f - lost), most)
        kindOf[m] = j; part[m] = pt; campOf[m] = c; dens[m] = d; mass[m] = kg; rate[m] = r; eat[m] = meat1; worth[m] = meat1 * bestFirst(meat1 / kg)
        time[m] = handlingDays(hunter, kg) + STALK_DAYS / success.coerceIn(1e-3f, 1f) + meat1 * eats + dangerDays(hunter, ps, kg, partyHeads)
        m++
    }
    for ((j, s) in kinds.withKinds()) {
        // (Not other beasts of prey: hunters kill their rivals and their young, [Danger.STRIFE] and the lions' killing, but
        // do not live on them as game. As game, wolves and hyenas ate each other: a quarter of each's deaths.)
        if (j == k || s === hunter || Catalogue.beastOfPrey(s)) continue
        val ym = youngKg(j.i)
        // (Its shelter there once for its grown, young and packs: the ground does not change while one hunts.)
        val sh = preyShelter(s, p)
        offer(j.i, 0, -1, adults[j][p], s.massKg, s, 1f, s.groupSize.toFloat(), sh)
        offer(j.i, 1, -1, young[j][p], ym, s, 1f, s.groupSize.toFloat(), sh, alone = s.liesOut && inflation.monthsFrom(s.birthMonth, month) < Species.LIE_OUT_MONTHS)
        offer(j.i, 2, -1, packAt(j.i, p), s.massKg, s, 1f, s.groupSize.toFloat(), sh)
    }
    // (The herds kept at bands' camps there, as their kind in the wild, come on as one band of all their heads, through
    // each band's guard against beasts: [stalkGuard], by night its fire, dogs, watch and palisade, by day its dogs.)
    if (camps != null) for ((c, b) in camps.withIndex()) {
        val guard = stalkGuard(b, 0f, b.fencedNow()); val yh = b.herds.youngHerd()
        for (j in b.herds.herd.indices) {
            val all = b.herds.herd[j] + yh[j]
            if (j == k.i || all < 1f) continue
            val s = kinds[j]; val sh = preyShelter(s, p)
            offer(j, 3, c, b.herds.herd[j] / area, s.massKg, s, guard, all, sh)
            offer(j, 4, c, yh[j] / area, YOUNG_WEIGHT * s.massKg, s, guard, all, sh)
        }
    }
    if (m == 0) return KgM2.ZERO
    // (Best first by kg a day of handling; taken while each raises the party's rate.)
    val order = (0 until m).sortedByDescending { worth[it] / time[it] }
    var gain = 0f; var spentRate = 1f; var used = 0
    for (i in order) {
        if (worth[i] / time[i] <= gain / spentRate) break
        gain += rate[i] * worth[i]; spentRate += rate[i] * time[i]; used++
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
        // (It eats the best of each kill first, so what it ate is worth more a kg the less of the kill it had: [bestFirst].)
        ate += kills * worth[i]
        // (What it could not eat of each kill, and the parts it leaves of what it did, lie as carrion.)
        small.deposit(DeadPool.CARRION, p, KgKm2((kills * (kg - eat[i]) + eaten * (1f - MEAT_SHARE)) / area).perM2)
        ateBook?.add(-(eaten * MEAT_SHARE).toDouble())
    }
    // (The kg it killed of the game as catchable, for the odds of being hunted: [residentKillOdds].)
    if (game > 0f) killRate[k]?.let { it[p] += killedKg / area / game }
    if (killedKg > 0f) killedKgByCountry[k.i * inflation.world.Biome.entries.size + land.country.kind[p]].add(killedKg.toDouble())
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
 * The resident game hunter [s] sees at place [p], kg per km²: every other kind there but the beasts of prey, grown and young (at
 * [YOUNG_WEIGHT]), by its weight and how catchable it is ([catchable]). Resident hunters and the groups count it alike.
 */
fun HexFauna.game(s: Species, p: Place): Float {
    var v = 0f
    val cover = land.plants.cover(p)
    for ((j, ps) in kinds.withKinds()) {
        if (ps === s || Catalogue.beastOfPrey(ps)) continue
        val a = adults[j][p]; val y = young[j][p]
        if (a + y <= 0f) continue
        val ym = youngKg(j.i); val sh = preyShelter(ps, p)
        v += a * ps.massKg * catchable(s, ps, ps.massKg, p, sh, cover) + y * ym * catchable(s, ps, ym, p, sh, cover)
    }
    return v
}

/**
 * The food hunter [s] lives on at place [p], kg per km², what its numbers follow ([HexFauna.usualAt]): the game of its size
 * ([preyBiomass]) for a hunter that lives mainly on game; for one that lives on small prey and carrion, each of its animal
 * foods as far as it lives on it: the game of its size, the small prey it reaches ([HexSmallLife.smallPreyReached]) and the
 * carrion lying there, weighed by their shares of its diet. (Counted whole, the game held a striped hyena, a tenth of whose
 * food it is, as if it lived on nothing else: settled 30 years, hyenas stood 10-14 times as thick as before and took more
 * boar, gazelle and roe than any other hunter, w1122 2026-11-23.)
 */
fun HexFauna.huntersFood(s: Species, p: Place): Float {
    if (HexFauna.holdsByPrey(s)) return preyBiomass(s, p)
    val d = s.diet
    var food = 0f; var shares = 0f
    if (d.meat > 0f) { food += d.meat * preyBiomass(s, p); shares += d.meat }
    if (d.smallPrey > 0f) { food += d.smallPrey * small.smallPreyReached(p, if (d.roots > 0f) SmallHunt.ROOT else SmallHunt.STALK); shares += d.smallPrey }
    if (d.carrion > 0f) { food += d.carrion * small.carrion[p.i] * 1e6f; shares += d.carrion }
    return if (shares > 0f) food / shares else 0f
}

/**
 * The prey of a size hunter [s] takes standing at place [p], kg per km²: every other kind there but the beasts of prey,
 * grown and young (at [YOUNG_WEIGHT]), by its weight as far as it is of a size [s] takes ([Species.preySize]), however
 * well it defends or hides itself. What hunters' numbers follow ([HexFauna.usualAt]), as the record measures it.
 */
fun HexFauna.preyBiomass(s: Species, p: Place): Float {
    var v = 0f
    for ((j, ps) in kinds.withKinds()) {
        if (ps === s || Catalogue.beastOfPrey(ps)) continue
        val a = adults[j][p]; val y = young[j][p]
        if (a + y <= 0f) continue
        val ym = youngKg(j.i)
        v += a * ps.massKg * s.preySize(ps.massKg) + y * ym * s.preySize(ym, young = true)
    }
    return v
}

/**
 * How catchable an animal of kind [ps] weighing [massKg] (grown, or young) is for hunter [s] at place [p] this week,
 * 0..1: its size against what the hunter takes ([Species.preySize]), defence, refuge in broken ground, small prey
 * hiding in thick cover, and for stalkers, dark nights.
 */
fun HexFauna.catchable(s: Species, ps: Species, massKg: Float, p: Place): Float = catchable(s, ps, massKg, p, preyShelter(ps, p))

/** [catchable], given [shelter], [ps]'s [preyShelter] at [p]: for a caller that weighs its grown and young, or many hunters, alike. */
fun HexFauna.catchable(s: Species, ps: Species, massKg: Float, p: Place, shelter: Float): Float = catchable(s, ps, massKg, p, shelter, land.plants.cover(p))

/** [catchable], given [shelter] and the [cover] at [p] ([inflation.world.HexPlants.cover]): for a caller that weighs many kinds at one place. */
fun HexFauna.catchable(s: Species, ps: Species, massKg: Float, p: Place, shelter: Float, cover: Float): Float =
    (s.preySize(massKg, young = massKg < ps.massKg) * (1f - youngDefence(ps, massKg) * condition(ps, p)) * closingIn(s, ps, massKg, shelter, cover)).coerceIn(0f, 1f)

/**
 * How much of its kind's defence game of kind [ps] at place [p] still has, 0..1, by how it has fed there ([HexFauna.fed]):
 * a hunter tests the herd and takes the weak, and the share of them grows as the food fails. All of it fed in full; at
 * fed f, 1 - [inflation.Tuning.weakCatch] x (1 - f). Wolves' kills run to the old, the young and those in poor marrow
 * (from memory: the Isle Royale moose, the Yellowstone elk), so the hunters take most where hunger would have taken
 * them, and least where the game is well fed. 1 for a kind not living here as residents.
 */
internal fun HexFauna.condition(ps: Species, p: Place): Float {
    val w = tuning.weakCatch
    if (w <= 0f) return 1f
    val k = slots[ps.index]
    if (k < 0) return 1f
    return (1f - w * (1f - fed[k][p].coerceIn(0f, 1f))).coerceIn(0f, 1f)
}

/**
 * How well an animal of kind [ps] weighing [massKg] fends off a hunter, 0..1: its kind's [Species.defence] for a grown one,
 * less for the young by the square root of their weight against the grown's. A calf is guarded by its mother and the herd
 * but is no bull: lions take buffalo calves far more readily than grown buffalo, wolves moose calves than cows (from
 * memory). At [YOUNG_WEIGHT] of the grown's weight, about half the grown's defence.
 */
fun youngDefence(ps: Species, massKg: Float): Float = ps.defence * kotlin.math.sqrt((massKg / ps.massKg).coerceIn(0f, 1f))

/** How well game of kind [ps] can shelter at place [p] now ([Niches.shelter]), its ground's part as worked out for the run. */
fun HexFauna.preyShelter(ps: Species, p: Place): Float {
    val k = slots[ps.index]
    return if (k >= 0) Niches.shelter(ps, p, land, groundShelter[k][p]) else Niches.shelter(ps, p, land)
}

private fun HexFauna.closingIn(s: Species, ps: Species, massKg: Float, shelter: Float, cover: Float): Float {
    // Shelter: crags, dens, thickets and trees, as the prey uses them ([preyShelter]); small prey hide in any cover.
    // (Hunters that follow the scent find much of what hides; shelter counts for half against them.)
    // (But crags are a wild goat's whole defence: no hunter follows it up a cliff.)
    val refuge = 1f - (if (ps.climbing >= 0.5f) 1f else 0.5f) * SHELTER_EFFECT * shelter
    // Small prey hide in thick cover from hunters that run them down; a stalker uses the same cover to get close.
    val hide = 1f - HIDING * cover * (1f - s.stalking) * max(0f, 1f - massKg / HIDING_KG)
    // Stalkers hunt by night: dark weeks around the new moon hide them, bright ones give them away.
    val dark = 1f + MOON_DARK * s.stalking * (1f - 2f * moon)
    // A courser must run its quarry down; a stalker gets close unseen and needs no speed. (Against quarry faster than it,
    // a courser's chance falls with the square of their speeds: a chase is won or lost on the gap, and a wolf rarely runs
    // down a grown ostrich or onager in the open. Linear, a hunter a sixth slower than its quarry still closed in nine
    // times in ten.)
    // (A runner of the open plains ([watchesInOpen]) outruns only what it sees coming from afar: in thick cover a hunter is on
    // it at a bound, and its speed counts for the less, the thicker the cover. Without it gazelle, onagers, horses and
    // ostriches stood thick in the woods: dens1121.)
    val ratio = min(1f, s.speedKmh / ps.speedKmh).let { if (watchesInOpen(ps)) 1f - (1f - it) * (1f - cover) else it }
    val speed = s.stalking + (1f - s.stalking) * ratio * ratio
    // And it needs room to run: in thick woods and scrub a chase ends at the first tangle.
    val room = 1f - COURSING_COVER * (1f - s.stalking) * cover
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


/**
 * The share of each kill resident hunter [k] makes at place [p] that scavengers take before it has eaten it: the small ones
 * everywhere ([SMALL_SCAVENGED]: ravens, crows, vultures, eagles, foxes), and the big resident ones about ([BIG_SCAVENGED_MOST]
 * of it as their kg a km² come to [inflation.Tuning.bigScavengedHalf]): each kind that eats carrion, by its share of carrion in its diet and by
 * its weight against the hunter's (a bear drives a lynx off its kill, a jackal waits its turn). Real hunters kill two to three
 * times what they eat: Yellowstone's wolves killed 6-17 kg a wolf a day in winter of which some 3.3 was theirs once the
 * scavengers had theirs (NPS; Smith et al.), lynx a roe every 5-6 days (Okarma et al. 1997). The shares are estimates.
 */
internal fun HexFauna.scavengedShare(k: FaunaKind, p: Place): Float {
    val hunter = kinds[k]
    var kg = 0f
    for ((j, s) in kinds.withKinds()) {
        if (j == k || s.diet.carrion <= 0f) continue
        val heads = adults[j][p] + young[j][p]
        if (heads <= 0f) continue
        kg += heads * s.massKg * s.diet.carrion / s.diet.total.coerceAtLeast(1e-6f) * (s.massKg / (s.massKg + hunter.massKg))
    }
    val big = BIG_SCAVENGED_MOST * kg / (kg + tuning.bigScavengedHalf)
    return 1f - (1f - SMALL_SCAVENGED) * (1f - big)
}

/** [scavengedShare]: what the small scavengers take of a kill wherever it lies, and the most the big ones take (at [inflation.Tuning.bigScavengedHalf] of their kg a km², weighed, half that). Estimates. */
internal const val SMALL_SCAVENGED = 0.15f
internal const val BIG_SCAVENGED_MOST = 0.5f


/**
 * What a kg a hunter eats of a kill is worth against a kg of the whole carcass, when it eats [share] of it (0..1) and the
 * best first: the organs, the fat about the gut and the marrow ([BEST_SHARE] of the carcass), [BEST_WORTH] times a kg of
 * the muscle, then the muscle. 1 when it eats the whole kill; more the less of it it had, as scavengers take the poorer meat.
 * (A carnivore opens the belly and takes the liver, heart and fat first: they hold most of the fat, vitamin A and B12 and
 * iron it needs; lean muscle alone cannot feed it. The shares are estimates, from memory of the record.)
 */
internal fun bestFirst(share: Float): Float {
    val x = share.coerceIn(1e-3f, 1f)
    val whole = BEST_WORTH * BEST_SHARE + (1f - BEST_SHARE)
    val got = if (x <= BEST_SHARE) BEST_WORTH * x else BEST_WORTH * BEST_SHARE + (x - BEST_SHARE)
    return got / x / whole
}

/** [bestFirst]: the share of a carcass that is organs, gut fat and marrow, and what a kg of it is worth against a kg of muscle. Estimates. */
internal const val BEST_SHARE = 0.15f
internal const val BEST_WORTH = 2.5f
