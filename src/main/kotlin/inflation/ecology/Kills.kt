package inflation.ecology

import inflation.Part
import inflation.Books
import inflation.ecology.HexSmallLife.DeadPool
import inflation.Kg
import inflation.KgM2
import inflation.world.get
import inflation.world.Place
import inflation.world.set
import inflation.world.Weather
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

// Kills: the odds a hunting party has of each kind on its ground, the hunt, herds raided, carcasses scavenged, and the shares of a kill.

/** How catchable prey [ps] is for hunter [s] in place [p], 0..1, this week ([HexFauna.catchable], as resident hunters reckon it too). */
internal fun GroupWorld.catchable(s: Species, ps: Species, p: Place): Float = fauna.catchable(s, ps, ps.massKg, p)

/**
 * The ground hunting party [g] searches for game this week with [km2] of search (see [searchKm2]): the places on its
 * way, each its share of the search as far as the place goes; and where a place is too small for its share (a lion's
 * week sweeps several hexes' worth), the rest spread evenly over the places it can cross into about its way, each as
 * far as it goes. (A party ranges round where it goes, not only through it; what is left over after that is the same
 * ground searched again, which finds no more game.) The places nearest in height to its own.
 */
internal fun GroupWorld.huntGround(g: HexGroup, km2: Float): HuntGround {
    val path = g.path; val area = land.areaKm2
    // (Over a step of several ticks, ground searched one week has game on it again the next: a place holds as much
    // search as its area each tick.)
    val laps = ticks
    var places = IntArray(path.size + 6); var swept = FloatArray(path.size + 6); var m = 0
    fun indexOf(p: Int): Int { for (i in 0 until m) if (places[i] == p) return i; return -1 }
    fun add(p: Int, s: Float) {
        if (m == places.size) { places = places.copyOf(2 * m); swept = swept.copyOf(2 * m) }
        places[m] = p; swept[m] = s; m++
    }
    val share = km2 / path.size.coerceAtLeast(1)
    var over = 0f
    for (h in path) {
        val p = hw.placeNear(g.place, h).takeIf { !it.none } ?: continue
        val i = indexOf(p.i)
        val had = if (i >= 0) swept[i] else 0f
        val s = min(area[p] * laps, had + share)
        over += had + share - s
        if (i >= 0) swept[i] = s else add(p.i, s)
    }
    val onWay = m
    if (over > 0f) {
        val cross = hex.crossings[Catalogue.travel(g.species).ordinal]
        for (h in path) for (d in 0 until 6) {
            val nh = hex.neighbour(h, d); if (nh.none || !cross[hex.edge(h, d)].isFinite() || nh in path) continue
            val q = hw.placeNear(g.place, nh).takeIf { !it.none } ?: continue
            if (indexOf(q.i) < 0) add(q.i, 0f)
        }
        // (Evenly over the places about, those too small for their share filled and the rest shared again among the others.)
        var open = m - onWay
        while (over > 1e-3f && open > 0) {
            val each = over / open; open = 0
            for (i in onWay until m) {
                val room = area[places[i]] * laps - swept[i]; if (room <= 0f) continue
                val s = min(room, each); swept[i] += s; over -= s
                if (s < room) open++
            }
        }
    }
    return HuntGround(places.copyOf(m), swept.copyOf(m))
}

/** The places of a hunting party's week's ground ([huntGround]), and the km² of each it searches. */
internal class HuntGround(val place: IntArray, val km2: FloatArray) { val size get() = place.size }

/**
 * The kills, of [heads] there, that [unchecked] kills over the step would come to with the game falling as it is taken:
 * each day takes its share of what is left, at most half of it a tick, so heads (1 - exp(-min(unchecked / heads,
 * ln 2 days / [tickDays]))). The same at any step length (plan section 52.B.2); it was the unchecked kills up to half of
 * what was there a step, whatever the step's length.
 */
internal fun takenOfGame(unchecked: Float, heads: Float, days: Int, tickDays: Int): Float {
    if (heads <= 0f || unchecked <= 0f) return 0f
    return heads * (1f - kotlin.math.exp(-min(unchecked / heads, 0.6931472f * days / tickDays)))
}

/** A herd of kind [k] kept by band [g] at the [j]th place of a beast's hunting ground: the odds per head it comes on it and kills, and the stalks a kill takes ([herdOdds]). */
internal class HerdGame(val g: HexGroup, val k: Int, val j: Int, val odds: Float, val stalks: Float, val young: Boolean)

/**
 * [forage]'s odds that a beast of prey comes on and kills an animal of the herds kept at bands' camps on its hunting ground,
 * into [ForageWeek.herds]: as it would one of their kind in the wild there ([huntingOdds]), the herd come on as one band of
 * all its heads (it is kept together), and through the band's guard against beasts ([stalkGuard]: by night its fire, dogs,
 * watch and palisade, by day its dogs; its houses keep its people, not its stock).
 */
internal fun HexGroups.herdOdds(w: ForageWeek) {
    val s = w.g.species; val view = FloatArray(2)
    for (j in 0 until w.ground.size) {
        val p = Place(w.ground.place[j])
        val camps = bands.bandIndex[hw.placeHex[p]] ?: continue
        var looked = false
        for (c in camps) {
            val b = c.band ?: continue
            if (c.place != p || !c.alive) continue
            val guard = stalkGuard(b, 0f, b.fencedNow())
            val young = b.herds.youngHerd()
            for (k in b.herds.herd.indices) {
                val all = b.herds.herd[k] + young[k]; if (all < 1f) continue
                val ps = fauna.kinds[k]
                // (The grown and the young, each as catchable as their weight makes them ([HexFauna.catchable]).)
                for (y in 0..1) {
                    val n = if (y == 1) young[k] else b.herds.herd[k]; if (n <= 0f) continue
                    val success = killChance(fauna.catchable(s, ps, if (y == 1) HexFauna.YOUNG_WEIGHT * ps.massKg else ps.massKg, p), 1f) * guard
                    if (!(success > 0f)) continue
                    if (!looked) { openness(s, p, w.weather, view); looked = true }
                    val image = w.g.metLately(ps.index, w.today).let { it / (it + SEARCH_HALF) }
                    val odds = success * (0.5f + 0.5f * Niches.overlap(s, ps)) * (1f - seenFirst(view[0], view[1], null, ps)) / all * image
                    if (odds > 0f) w.herds += HerdGame(c, k, j, odds, 1f / success.coerceIn(1e-3f, 1f), y == 1)
                    if (HUNT_DIAG) { val swept = w.ground.km2[j] / land.areaKm2[p]
                        diag(s, HuntStep.HERDS_MET, swept); diag(s, HuntStep.HERD_KILLS_ON_OFFER, n * swept * odds); diag(s, HuntStep.HERD_GUARD, guard) }
                }
            }
        }
    }
    if (HUNT_DIAG && w.herds.isNotEmpty()) diag(s, HuntStep.HERD_WEEKS, 1f)
}

/**
 * [forage]'s odds that [w]'s party comes on and kills each kind present in each place of its hunting ground, per animal
 * there, into [ForageWeek.odds]; the stalks a kill takes into [ForageWeek.stalks], and the bands come on unseen per
 * animal into [ForageWeek.met]; and the game worth stalking there. Prey resting while the hunter is about lie up in
 * cover and are found less; a band's weapons, dogs and hunting skill change the odds that a stalk ends in a kill
 * ([killChance]). Residents go about in their kind's usual bands: a band is come on as one, and may see the hunters
 * first and keep out of their way ([seenFirst]); one try at it, for a band chased scatters. And game it seldom comes on
 * it does not look for (prey switching, see [SEARCH_HALF]). (What depends on the kind alone is reckoned once a kind,
 * what depends on the place alone once a place.)
 */
@Part("foraging: hunting odds")
internal fun GroupWorld.huntingOdds(w: ForageWeek) {
    val g = w.g; val s = g.species; val band = g.band; val nk = w.nk
    val adults = fauna.adults; val young = fauna.young
    val edge = FloatArray(nk); val image = FloatArray(nk); val meets = FloatArray(nk); val view = FloatArray(2)
    for (k in 0 until nk) {
        val ps = fauna.kinds[k]
        edge[k] = band?.huntEdge(ps, tuning) ?: 1f
        image[k] = g.metLately(ps.index, w.today).let { it / (it + SEARCH_HALF) }
        meets[k] = 0.5f + 0.5f * Niches.overlap(s, ps)
    }
    for (j in 0 until w.ground.size) {
        w.gameFrom[j] = w.games
        val p = w.ground.place[j]
        openness(s, Place(p), w.weather, view)
        for (k in 0 until nk) {
            if (adults[k][p] + young[k][p] + fauna.packAt(k, Place(p)) <= 0f) continue
            val ps = fauna.kinds[k]; val i = j * nk + k
            // (No hunter hunts its own kind as game: a settled lion is a rival to a lion pack, not its prey, as to a settled one.)
            if (ps === s) continue
            val success = killChance(catchable(s, ps, Place(p)), edge[k])
            w.met[i] = meets[k] * (1f - seenFirst(view[0], view[1], band, ps)) / ps.groupSize
            w.odds[i] = success * w.met[i] * image[k]
            w.stalks[i] = 1f / success.coerceIn(1e-3f, 1f)
            if (!(w.odds[i] <= 0f) && k != w.avoid) w.gameAt[w.games++] = i
        }
    }
    w.gameFrom[w.ground.size] = w.games
}

/**
 * The small game's skins are kept too, as far as [b] has time to scrape and dry them: many small pelts sewn
 * together (hare, fox, marmot) clothe as well as a few big hides. [pelts] kg were set aside from the small game
 * caught, in the [time] it has to spare; returns the kg left unskinned, for the scavengers.
 */
internal fun GroupWorld.skinSmallGame(b: Band, time: BandTime, pelts: Float): Float {
    val skinDays = min(time.leftFor(Activity.SKINS), SKIN_DAYS * pelts)
    b.goods[Material.HIDE] += skinDays / SKIN_DAYS
    time.spend(Activity.SKINS, skinDays)
    stats.tally(Tally.hide(Tally.HideSource.SMALL_PELTS), (1000f * skinDays / SKIN_DAYS).roundToInt())
    return max(0f, pelts - skinDays / SKIN_DAYS)
}

/** Tallies the hide in the [took] kg of hide, bone and sinew a band took from kills of [ps] (none if no band), by [source]; returns [took]. */
internal fun GroupWorld.tallyHide(source: Tally.HideSource, ps: Species, took: Float?): Float {
    if (took == null || took <= 0f) return 0f
    val p = ps.products
    stats.tally(Tally.hide(source), (1000f * took * p.hide / (p.hide + p.bone + p.sinew)).roundToInt())
    return took
}

/** What one of [forage]'s takes brought: kilos of food, and party-days spent on it beyond the take itself. */
internal class Haul { var kg = 0f; var busy = 0f; var mass = 0f; var fat = 0f
    /** Of a hunt of game in the wild, the hunters the kills are expected to cost ([foughtBack]). */
    var fought = 0f }

/**
 * [forage]'s raid on herd [hg] at place [p]: [expected] kills on average, each worth [kgEach] to the beast, out of the band's
 * herd (no more than half of it, as of game in the wild, [huntGame]), the band to learn of them as it keeps its herds
 * ([Herd.herdTaken]). Returns the kills; [out] gets the meat.
 */
internal fun HexGroups.raidHerd(g: HexGroup, hg: HerdGame, p: Place, expected: Float, kgEach: Float, rnd: kotlin.random.Random, out: Haul): Int {
    val b = hg.g.band ?: return 0
    val s = g.species; val ps = fauna.kinds[hg.k]
    val heads = if (hg.young) b.herds.youngHerd() else b.herds.herd
    val kills1 = min(poisson(expected, rnd), (heads[hg.k] * 0.5f).toInt())
    if (kills1 <= 0) return 0
    heads[hg.k] -= kills1.toFloat()
    if (b.herds.herdTaken.size != b.herds.herd.size) b.herds.herdTaken = FloatArray(b.herds.herd.size)
    b.herds.herdTaken[hg.k] += kills1.toFloat()
    fauna.keptTakenBy.computeIfAbsent(s) { java.util.concurrent.atomic.DoubleAdder() }.add(kills1.toDouble())
    // (Kept herds have no fat state yet: their meat is lean.)
    out.kg = kills1 * kgEach; out.fat = 0f
    val each = if (hg.young) HexFauna.YOUNG_WEIGHT * ps.massKg else ps.massKg
    small.deposit(DeadPool.CARRION, p, Kg(kills1 * max(0f, each - kgEach * meatShare(null) / creditShare(null))))
    stats.meeting(Met.KEPT_ANIMALS_TAKEN, kills1)
    stats.keep(Tally.of(s, Tally.OfKind.KILLS_OF_KEPT), kills1)
    note(g) { "took %d kept %s".format(kills1, ps.name) }
    fauna.killed(p, Kg(kills1 * each))
    return kills1
}

/**
 * Stronger scavengers coming on beast [g]'s [kills] at [p] (worth [haul] to it) by scent, and taking of them
 * (review/2026-11-01-bears-at-kills-v2-design.md): takes their share off [haul] and leaves it at [p] as carrion, which they
 * eat as they eat any. Each carcass lies while the party eats it at its gut's rate ([EATING_RATE], grown and half the
 * young, as [ForageWeek.gutKg]). Resident kinds that kill as well as scavenge, awake and stronger than the party
 * ([strength]), come on it from downwind as they range for carrion: a strip [Species.senseKm] wide along the share of a
 * day's travel their diet gives carrion ([Diet.carrion]), their bands as common there as they are. A kill found is won by
 * the scavenger as often as its share of their strengths, and it eats its band's day's fill of half the kill (found
 * halfway through, on average). A carcass a kind hauls up a tree keeps, as far as there are trees. Returns the kg taken.
 * (Counted for huntSteps by kill size, small under 100 kg live and large over, a beast's kill counting its whole weight,
 * [creditShare].)
 */
internal fun HexGroups.scavengedAtKill(g: HexGroup, p: Place, kills: Int, haul: Haul, rnd: kotlin.random.Random): Float {
    if (haul.kg <= 0f || kills <= 0) return 0f
    val s = g.species
    val party = strength(g)
    val each = haul.kg / kills
    val kinds = fauna.kinds
    var rate = 0f; var wins = 0f; var takes = 0f
    for ((k, r) in kinds.withIndex()) {
        val finds = scavengerFinds(r, k, p, party); if (finds <= 0f) continue
        val win = scavengerWins(r, party)
        rate += finds; wins += finds * win; takes += finds * win * scavengerTakes(r, each)
    }
    if (rate <= 0f) { if (HUNT_DIAG) { diag(s, if (each >= 100f) HuntStep.SCENT_KILLS_LARGE else HuntStep.SCENT_KILLS_SMALL, kills.toFloat()); diag(s, HuntStep.SCENT_KG, haul.kg) }; return 0f }
    val eatsADay = EATING_RATE * s.massKg * (g.adults + 0.5f * g.young).coerceAtLeast(1f)
    val lies = min(days.toFloat(), each / eatsADay)
    val cached = if (Niches.Shelter.TREE in Niches.shelters(s)) land.plants.canopy(p) else 0f
    val found = (1f - exp(-rate * lies)) * (1f - cached)
    val won = binomial(binomial(kills, found, rnd), wins / rate, rnd)
    val kg = haul.kg
    val lost = if (won > 0 && wins > 0f) min(kg, won * takes / wins) else 0f
    if (lost > 0f) {
        haul.fat *= 1f - lost / kg; haul.kg = kg - lost
        // (What they eat of it is as much meat as the party would have held of it.)
        small.deposit(DeadPool.CARRION, p, Kg(lost * meatShare(null) / creditShare(null)))
    }
    if (!HUNT_DIAG) return lost
    // (By the scavenger's kind: its finds a day, and its share of the kills won and of the kg lost.)
    for ((k, r) in kinds.withIndex()) {
        val finds = scavengerFinds(r, k, p, party); if (finds <= 0f) continue
        val win = scavengerWins(r, party)
        diag(s, HuntStep.SCENT_FINDS_A_DAY, r, finds * kills)
        diag(s, HuntStep.SCENT_WON, r, won * finds * win / wins)
        if (takes > 0f) diag(s, HuntStep.SCENT_KG_LOST, r, lost * finds * win * scavengerTakes(r, each) / takes)
    }
    val large = each >= 100f
    diag(s, if (large) HuntStep.SCENT_KILLS_LARGE else HuntStep.SCENT_KILLS_SMALL, kills.toFloat())
    diag(s, if (large) HuntStep.SCENT_FOUND_LARGE else HuntStep.SCENT_FOUND_SMALL, found * kills)
    diag(s, HuntStep.SCENT_WON, won.toFloat())
    diag(s, HuntStep.SCENT_KG, kg); diag(s, HuntStep.SCENT_KG_LOST, lost)
    diag(s, HuntStep.SCENT_LIE_DAYS, lies * kills); diag(s, HuntStep.SCENT_FINDS_A_DAY, rate * kills)
    return lost
}

/** [scavengedAtKill]'s bands of kind [r] (index [k]) coming on a kill at [p] a day, if awake and stronger than the [party]; else 0. */
private fun HexGroups.scavengerFinds(r: Species, k: Int, p: Place, party: Float): Float {
    if (r.diet.carrion <= 0f || r.diet.meat <= 0f || r.human || fauna.asleep(FaunaKind(k), p)) return 0f
    if (r.groupSize * r.massKg * r.ferocity <= party) return 0f
    // (It ranges for carcasses only the share of its days its diet gives carrion.)
    return (fauna.adults[k][p] + fauna.young[k][p]) / r.groupSize * r.senseKm * r.dailyTravelKm * r.diet.carrion
}

/** [scavengedAtKill]'s odds a band of kind [r] wins a kill from the [party]: its share of their strengths. */
private fun scavengerWins(r: Species, party: Float): Float { val band = r.groupSize * r.massKg * r.ferocity; return band / (band + party) }

/** What a band of kind [r] eats of a kill worth [each] that it wins: its day's fill, of half the kill at most. */
private fun scavengerTakes(r: Species, each: Float) = min(0.5f * each, EATING_RATE * r.massKg * r.groupSize)

/**
 * [forage]'s hunt of kind [k] at place [p]: [expected] kills on average, each worth [kgEach] to the party.
 * Returns the kills (0: none, and nothing done); [out] gets the meat. A band at home may keep young it catches.
 */
@Part("foraging: hunt a game")
internal fun HexGroups.huntGame(g: HexGroup, k: Int, p: Place, expected: Float, kgEach: Float, spareYoung: Boolean, rnd: kotlin.random.Random, out: Haul): Int {
    val s = g.species; val band = g.band
    val ps = fauna.kinds[k]
    val area = land.areaKm2[p]
    // (Of a kind that also roams, the grown of its packs standing here are come on as its settled are: [HexFauna.packGrown].)
    val pack = fauna.packAt(k, p)
    val settled = fauna.adults[k][p] + fauna.young[k][p]
    val dens = settled + pack
    out.fought = 0f
    // (Of what is there as it falls, at most half of it a tick: [takenOfGame].)
    val taken = takenOfGame(expected, dens * area, days, fauna.clock.tickDays)
    if (HUNT_DIAG && band == null) { diag(s, HuntStep.HUNT_CALLS, 1f); diag(s, HuntStep.HUNT_UNCHECKED, expected); diag(s, HuntStep.HUNT_TAKEN, taken)
        if (taken >= 0.1f * dens * area) diag(s, HuntStep.HUNT_DEPLETING, 1f) }
    var kills1 = poisson(taken, rnd)
    kills1 = min(kills1, (dens * area).toInt())
    if (kills1 <= 0) return kills1
    // (No more of any are killed than are there: the packs' as many as they are of the game here, taken off them once the
    // week's hunting is done, [takePackKills].)
    val np = if (pack > 0f) min(binomial(kills1, pack / dens, rnd), (pack * area).toInt()) else 0
    val nr = kills1 - np
    var ny = if (settled > 0f) min(if (spareYoung) 0 else (nr * fauna.young[k][p] / settled).toInt(), (fauna.young[k][p] * area).toInt()) else 0
    val na = min(nr - ny, (fauna.adults[k][p] * area).toInt())
    kills1 = na + ny + np
    if (np > 0) { fauna.packKills += PackKill(p.i, k, np.toFloat(), g.id.toLong()); fauna.packGrown[k]?.let { it[p.i] = max(0f, it[p.i] - np / area) } }
    if (kills1 <= 0) return kills1
    // A band may keep young it catches, to raise by its camp and drive along when it moves.
    if (band != null && ny > 0 && k < band.herds.herd.size) {
        // (A kind is kept by the worst of Diamond's criteria, since it fails as stock on any one, as [herdKind] chooses.)
        val keepable = ps.domestication.min()
        val kept = binomial(ny, min(1f, CAPTURE * band.keepWill * keepable), rnd)
        dialUse?.amount(DialSite.KEEP_CAPTURE, band.keepWill, min(1f, CAPTURE * band.keepWill * keepable),
            min(1f, CAPTURE * keepable), cut = CAPTURE * band.keepWill * keepable > 1f)
        if (kept > 0) {
            // (Raised by hand from young, they come to the camp as tame as a herd's, [RAISED_TAME], whatever their kind: how
            // well a kind keeps on foot shows in how many stray, [straying].)
            val young = band.herds.youngHerd(); val had = band.herds.herd[k] + young[k]
            band.herds.herdTame[k] = (band.herds.herdTame[k] * had + RAISED_TAME * kept) / (had + kept)
            // (A herd begun so is kept as pets till they have grown, or sooner as their temper shows: [Herd.herdPet], [petYears].)
            if (had < 0.5f) {
                bands.herdsBegun.incrementAndGet(k)
                if (band.herds.herdPet.size != band.herds.herd.size) band.herds.herdPet = FloatArray(band.herds.herd.size)
                band.herds.herdPet[k] = petYears(ps)
            }
            bands.keptYoung.addAndGet(k, kept)
            young[k] += kept; ny -= kept
            fauna.young[k][p] = max(0f, fauna.young[k][p] - kept / area)
            stats.meeting(Met.YOUNG_ANIMALS_KEPT, kept)
        }
    }
    fauna.young[k][p] = max(0f, fauna.young[k][p] - ny / area); fauna.adults[k][p] = max(0f, fauna.adults[k][p] - na / area)
    // (The share of the settled it killed, kept for the residents' odds of being hunted, see [HexFauna.huntedOdds].)
    if (settled > 0f) fauna.groupKilled[k][p] += (na + ny) / (settled * area)
    // (A band that kills a beast of prey presses its kind off the ground about: [persecutedBy].)
    if (band != null) fauna.persecutedBy(k, p, (na + ny + np).toFloat())
    val young = min(HexFauna.YOUNG_WEIGHT * ps.massKg, kgEach)
    // (The grown killed, settled and of packs alike.)
    val grown = na + np
    // (Each kill may cost a hunter, grown prey the more: those kept alive were taken too.)
    out.fought = grown * foughtBack(s, ps, ps.massKg) + (kills1 - grown) * foughtBack(s, ps, HexFauna.YOUNG_WEIGHT * ps.massKg)
    out.kg = grown * kgEach + ny * young
    out.fat = grown * fatOfKill(k, p, kgEach, band)
    // (Hides, bone and sinew come out of what the hunters leave of the carcasses.)
    val leftA = grown * leftOver(ps, kgEach, band)
    small.deposit(DeadPool.CARRION, p, KgM2((leftA + ny * (HexFauna.YOUNG_WEIGHT * ps.massKg - young) - tallyHide(Tally.HideSource.RESIDENTS, ps, band?.skin(ps, grown.toFloat(), leftA))) / (area * 1e6f)))
    band?.let { it.practise(Skill.HUNT); it.practise(Skill.TRACK); it.practise(Skill.STALK); it.practise(Skill.BUTCHER) }
    stats.killed(ps, kills1)
    note(g) { "killed %d %s".format(kills1, ps.name) }
    fauna.killed(p, Kg(kills1 * ps.massKg))
    stats.keep(Tally.of(s, Tally.OfKind.KILLS_OF_RESIDENTS), kills1)
    diag(ps, HuntStep.RESIDENT_KILLS, kills1.toFloat())
    return kills1
}

/**
 * The fat of a kill of a grown [k] at [p], kg, worth [kgEach] to [band]: the animal's fat ([HexFauna.fatShare]), as much of it
 * as its butchers take (all of it at [MEAT_MOST]) and as the party takes of the carcass. None for a beast: to it a kill is
 * whole prey, fat and all ([Nourishment.PREY]).
 */
internal fun HexGroups.fatOfKill(k: Int, p: Place, kgEach: Float, band: Band?): Float {
    if (band == null || kgEach <= 0f) return 0f
    val ps = fauna.kinds[k]
    val body = fauna.fatShare(k, p) * ps.massKg * min(1f, meatShare(band) / MEAT_MOST)
    return min(kgEach, body * min(1f, kgEach / (ps.massKg * creditShare(band)).coerceAtLeast(1e-6f)))
}

/** [forage]'s scavenging of [expected] kg at place [p] by [g], over [sweep] km²: what it found, kg. */
internal fun HexGroups.scavenge(g: HexGroup, p: Place, expected: Float, sweep: Float, rnd: kotlin.random.Random): Float {
    val band = g.band
    val got = small.scavengeIn(p, Kg(expected), min(land.areaKm2[p], sweep)).v
    // A band at a carcass may meet its owner: lions or hyenas back at their kill. Driving
    // them off is done with a crowd and a din, but now and then someone is mauled.
    if (band != null && got > 1f) {
        val beasts = monthBeastKg[p] / land.areaKm2[p]
        if (rnd.nextFloat() < SCAVENGE_RISK * beasts / (beasts + 3f)) {
            band.wounded(1f, g.adults)
            stats.meeting(Met.BANDS_MAULED_AT_A_CARCASS)
        }
    }
    return got
}

/**
 * The odds that a kill of [ps] weighing [massKg] costs hunter [s] one of its own: cornered or wounded, the prey turns on
 * the hunters closing in (a boar's tusks, an aurochs' horns, an elk's hooves, a bear's or a lion's claws), the more the
 * better it defends itself ([Species.defence]) and the heavier it is against the hunter. The same for every hunter, beast
 * or band, settled or roaming. ([FOUGHT_BACK] is an estimate: no field figure per kill was found. The Hiwi lose about 3 a
 * thousand a year to all accidents together, hunting among them (Hill et al. 2007).)
 */
internal fun foughtBack(s: Species, ps: Species, massKg: Float): Float {
    val r = massKg / s.massKg
    return FOUGHT_BACK * ps.defence * r / (1f + r)
}

/**
 * The hunters a kill of [ps] can cost, most per kill of a prey of full [Species.defence] far heavier than the hunter (an
 * estimate). At 0.005 bands lost 4 a thousand a year to their game (danger589), more than the Hiwi lose to all accidents
 * together; a quarter of it, to about 1 a thousand.
 */
internal const val FOUGHT_BACK = 0.00125f

/**
 * What the risk of [foughtBack] costs a party of [heads] of [s] a kill, in days of the party's work: the life lost, as the
 * days the dead had left to work (a generation at most, [GENERATION_YEARS]; a beast, as long as its kind usually lives on),
 * shared over the party. Hunters weigh their game by it, as by the days a kill takes.
 */
internal fun dangerDays(s: Species, ps: Species, massKg: Float, heads: Float): Float =
    foughtBack(s, ps, massKg) * 365f * min(GENERATION_YEARS, 1f / s.backgroundMortality.coerceAtLeast(1e-3f)) / heads.coerceAtLeast(1f)

/**
 * [g]'s hunters that its game killed this week: [expected] on average, and some more wounded ([WOUNDED_PER_DEAD]); the
 * dead are left where they fell.
 */
internal fun HexGroups.preyFoughtBack(g: HexGroup, expected: Float, rnd: Random) {
    if (!(expected > 0f)) return
    g.band?.wounded(WOUNDED_PER_DEAD * expected, g.adults)
    val lost = min(poisson(expected, rnd), g.adults)
    if (lost <= 0) return
    adultsDied(g, lost, rnd)
    stats.deaths.merge(Tally.of(g.species, DeathCause.PREY_FOUGHT_BACK), lost, Int::plus)
    if (g.species.human) stats.adultDeaths.merge(Tally.of(g.species, DeathCause.PREY_FOUGHT_BACK), lost, Int::plus)
    g.band?.let {
        stats.tally(Tally.bandsLivingDied(bandLiving(it), DeathCause.PREY_FOUGHT_BACK), lost)
        stats.tally(Tally.bandsMindDied(it.mind, DeathCause.PREY_FOUGHT_BACK), lost)
    }
    small.deposit(DeadPool.CARRION, g.place, Kg(lost * g.species.massKg))
    note(g) { "lost %d to game that fought back".format(lost) }
}

/** Chance a band scavenging a carcass where hunters are thick has one of its people mauled by them. */
internal const val SCAVENGE_RISK = 0.01f

/** Share of a carcass a hunter takes as food: a band as its butchers' skill allows (all the fat and marrow, at best). */
internal fun meatShare(band: Band?) = if (band == null) MEAT_SHARE else min(MEAT_MOST, MEAT_SHARE * band.edge(Skill.BUTCHER))

/**
 * Share of a kill's weight that counts towards a hunter's need: all of it for a beast, whose need is reckoned in the weight
 * of the prey it kills, the parts it leaves included ([Allometry.dailyIntakeKg]); for a band, what its butchers make of it.
 */
internal fun creditShare(band: Band?) = if (band == null) 1f else meatShare(band)

/**
 * For the books: [credit] kg added to [hunter]'s meat in hand (taken off when below zero) from a kill of [kg] that left
 * [carrion] kg; a beast holds only [meatShare] of what it is credited, so the rest of a kill credited whole goes nowhere.
 */
internal fun GroupWorld.bookKill(hunter: HexGroup, credit: Float, kg: Float, carrion: Float) {
    land.books?.leak(Books.Stuff.MATTER, "hunters' kills credited at more or less than they weigh (kills taken from another kind)",
        (credit * meatShare(hunter.band) / creditShare(hunter.band) + carrion - kg).toDouble())
}

/** What is left of a kill of [ps] as carrion when the hunter took [kgEach] of it towards its need. */
internal fun leftOver(ps: Species, kgEach: Float, band: Band?) = max(0f, ps.massKg - kgEach * meatShare(band) / creditShare(band))

internal const val MEAT_MOST = 0.7f

/** Share of the ground a party searches for small game at place [p] over which it finds it: less in standing cover ([SMALL_HIDE]). */
internal fun GroupWorld.smallSeen(p: Place) = 1f - SMALL_HIDE * land.plants.groundCover(p)

/**
 * How readily [s] catches small game it comes on, 0..1: the smaller and nimbler the hunter, the better; cover hides the prey from all but stalkers.
 * (People do not run it down: they snare, net, trap and dig it out, which their skill and kit count in ([Band.snares]), so their weight does not
 * count against them.)
 */
internal fun smallCatch(s: Species) = SMALL_CATCH * (if (s.lifeway == Lifeway.BAND) 1f else min(1f, SMALL_HUNTER_KG / s.massKg))

/**
 * The odds that game of kind [ps] sees the hunters first and keeps out of their way: likelier in the [open] (the less
 * cover, and against a courser than a stalker) and in [clear] weather ([openness]), and against [band]s for game bred
 * to fear people over the millennia.
 */
internal fun seenFirst(open: Float, clear: Float, band: Band?, ps: Species): Float {
    val fear = if (band != null) 0.5f + ps.fearOfPeople else 1f
    return min(0.95f, open * fear * clear)
}

/** [seenFirst]'s parts at place [p] for hunter [s]: how open the ground is to a hunter of its way, and how clear the weather and cover leave the view. */
private fun GroupWorld.openness(s: Species, p: Place, weather: Weather, out: FloatArray) {
    val cover = land.plants.cover(p)
    out[0] = VIGILANCE * (1f - cover) * (1f - s.stalking)
    out[1] = 0.5f + 0.5f * sight(p, weather) / (1f - SIGHT_COVER * cover).coerceAtLeast(0.1f)
}

/**
 * The odds that one try at a band of game ends in a kill, once the hunter has come on it unseen: as [catchable] as it
 * is there, and by a band's [edge] over it ([Band.huntEdge]; 1 for a beast of prey).
 */
internal fun killChance(catchable: Float, edge: Float): Float = CATCH * SURPRISE * catchable * edge
