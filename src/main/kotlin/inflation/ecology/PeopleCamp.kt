package inflation.ecology

import inflation.world.Hex
import inflation.world.Place
import inflation.world.get
import inflation.world.set
import inflation.ecology.HexSmallLife.DeadPool
import inflation.Kg
import inflation.KgM2
import inflation.min
import inflation.world.Date
import kotlin.math.max
import kotlin.math.min
import inflation.world.Weather
import kotlin.math.sqrt

// Bands' camps: moving camp at the pace of the slowest, what they carry and cache, and the fouling of a camp kept long.

/** Extra plant food with sickles (seed), grinding stones (seed and roots) and baskets (all of it, and stores). */
const val SICKLE_GAIN = 0.3f
const val GRINDER_GAIN = 0.4f
const val BASKET_GAIN = 0.3f
/**
 * Moving camp: an unloaded adult's walking pace over rough ground, km/h (on roads people walk about 5); a walking child's
 * pace against an adult's, as the square root of its leg length (Froude scaling; legs about 0.55 an adult's at six
 * years); hours a band walks in a day of moving camp (an estimate: no source found).
 */
const val WALK_KMH = 4f
const val CHILD_PACE = 0.74f
const val MOVE_HOURS = 6f
/**
 * Weeks a band expects to keep a camp before it has kept any (foragers moved camp some 5 to 60 times a year, Kelly 1995,
 * from memory: about 4 weeks a camp near the middle), and the share of the gap each move closes in its running mean.
 */
const val STAY_WEEKS_START = 4f
const val STAY_LEARN = 0.2f
/** Share of [g]'s people who hold it back on the move: the old, the hurt and lamed, the sick. */
internal fun weakShare(g: HexGroup) = g.band?.let { b -> ((b.elders + (b.hurt + b.lame) * g.adults + b.sick.sum() * g.size) / g.size.coerceAtLeast(1)).coerceIn(0f, 1f) } ?: 0f

/**
 * Km [g]'s band covers in a day of moving camp: at the pace of its slowest, its adults under their loads ([carriedKg],
 * [loadedKmh]) or its walking children if it has any, held back by its weak ([weakShare]) and by thick cover where it camps.
 */
internal fun GroupWorld.campKmADay(g: HexGroup): Float {
    val b = g.band
    val adults = if (b != null) loadedKmh(g.species.massKg, carriedKg(g, b)) else WALK_KMH
    return MOVE_HOURS * min(adults, if (g.young > 0) CHILD_PACE * WALK_KMH else adults) * (1f - WEAK_SLOW * weakShare(g)) /
        (1f + COVER_GOING * land.plants.cover(g.place))
}

/** Days [g]'s band takes to move its camp [km], all its people walking (and its adults back for each load past the first): they are not at work. */
internal fun GroupWorld.moveDays(g: HexGroup, km: Float) = km * (g.band?.let { relayWalks(it) } ?: 1f) / campKmADay(g)

/**
 * What an adult carries in a load when the camp moves, food and goods together, kg: !Kung women carried about 15 kg
 * besides a child (Lee 1979, from memory: to check).
 */
const val CARRY_KG = 15f
/** Extra yearly deaths among children in a camp kept for a year or more. */
const val CAMP_FOULING = 0.02f
/** Whether [g]'s band [b] has come to a new camp this week (or has none yet), where no shelter of its stands. */
internal fun GroupWorld.movedCamp(g: HexGroup, b: Band) = b.campPlace.none || hw.placeHex[g.place] != hw.placeHex[b.campPlace]

/** The country [g]'s band comes to know this week: what it walks, and what it sees round about from the high points on its way, its scouts further still. */
internal fun HexGroups.learnCountry(g: HexGroup, b: Band, weather: Weather) {
    // It comes to know the country it walks, and what it sees round about.
    // From the high points on its way it sees as far as the line of sight and the weather allow;
    // its scouts range further still.
    var lookKm = 0f
    for (h in g.path) {
        val q = hw.placeNear(g.place, h).takeIf { !it.none } ?: continue
        lookKm = max(lookKm, land.bestViewKm[q] * sight(q, weather) / (1f - SIGHT_COVER * land.plants.cover(q)).coerceAtLeast(0.1f))
    }
    val scouts = b.scoutShare * g.adults
    fun scoutRings(scouts: Float) = if (scouts >= 2f) SCOUT_RINGS else if (scouts >= 1f) SCOUT_RINGS / 2 else 0
    val rings = min(SIGHT_RINGS, max(1, (lookKm / hex.hexKm).toInt())) + scoutRings(scouts)
    dialUse?.amount(DialSite.SCOUTS_TOLD, b.will[BandWay.SCOUTS], scoutRings(scouts).toFloat(), scoutRings(SCOUT_SHARE * g.adults).toFloat())
    // (Every hex within [rings] of the way walked, found ring by ring from the whole path at once.)
    val seen = HashSet<Hex>()
    var ring: List<Hex> = g.path.filter { seen.add(it) }
    for (h in ring) b.learn(h, hex.size)
    for (r in 1..rings) {
        ring = hex.nextRing(ring, seen)
        for (q in ring) b.learn(q, hex.size)
    }
}

/**
 * How much care band [b] takes over its caches, 1 at its will's default and up to 2: a cache dug with more care takes as much
 * more time a kg, and as much less of it is dug up by beasts or found by other bands. (Read from its will now, not as it was
 * when the cache was dug; ways change slowly. The proportions are a choice: no source sets them.)
 */
internal fun cacheCare(b: Band) = max(1f, b.cacheWill)

/**
 * The kg of [m] [b]'s [people] take along first on a camp move: what mending the tools they know and take along needs
 * over the weeks they keep a camp ([Band.stayWeeks]), as each wears ([Tool.wearPerYear]); and of the materials found only
 * where they crop out (good stone, salt, ochre), what they keep of them ([KEEP_YEARS] of [YEAR_NEED]), since what is left
 * behind is not to be had again on the way.
 */
private fun GroupWorld.mendingKg(b: Band, m: Material, people: Float) =
    people * (b.stayWeeks / stepsPerYear * Tool.entries.sumOf { t -> if (b.knows[t] && !t.heavy) ((t.recipe[m] ?: 0f) * t.wearPerYear).toDouble() else 0.0 }.toFloat() +
        KEEP_YEARS * YEAR_NEED[m])

/** The kg of goods [g]'s band [b] takes along on a camp move before its food: what it has of what it takes first ([mendingKg]). */
private fun GroupWorld.mendingStockKg(g: HexGroup, b: Band) = Material.entries.sumOf { m -> min(b.goods[m], mendingKg(b, m, g.adults.toFloat())).toDouble() }.toFloat()

/** The kg of its stores [g]'s band [b] cannot carry in its loads ([carryKg], [carryLoads]) beside its mending stock: what it caches or leaves when it moves camp. */
internal fun GroupWorld.leftBehindKg(g: HexGroup, b: Band): Float {
    val total = g.larder
    val carry = max(0f, carryKg(g, b) * carryLoads(b) - mendingStockKg(g, b))
    if (total <= carry) return 0f
    val left = 1f - carry / total
    return Store.entries.sumOf { (g.stores[it] * left * g.storeKgPer[it]).toDouble() }.toFloat()
}

/** What [g]'s band can carry in one load, kg: its adults' loads, more with baskets and bags. */
private fun carryKg(g: HexGroup, b: Band) = CARRY_KG * max(1, g.adults) * (1f + BASKET_GAIN * b.has(Tool.BASKET))

/**
 * Loads of [carryKg] each of [b]'s adults takes on a camp move, its choice ([BandWay.CARRY_LOADS]): under one, a lighter
 * load and a faster walk; above one, they carry a load ahead, go back for the next and so on (relays).
 */
internal fun carryLoads(b: Band) = b.will[BandWay.CARRY_LOADS]

/** Times [b]'s adults walk the way of a camp move: once, and there and back again for each load past the first. */
internal fun relayWalks(b: Band) = 1f + 2f * max(0f, carryLoads(b) - 1f)

/** What [g]'s band [b] carries a walker on each leg of a camp move, kg: its food first, then its goods, as far as its loads take them. */
internal fun carriedKg(g: HexGroup, b: Band) = min(g.larder + b.goods.sum(), carryKg(g, b) * carryLoads(b)) / (max(1, g.adults) * max(1f, carryLoads(b)))

/**
 * Walking pace of an adult carrying [loadKg], km/h: as fast as costs the same effort as walking unloaded ([WALK_KMH]),
 * by Pandolf et al. (1977) on level ground, for a body of [massKg] (W): 1.5 W + 2 (W + L) (L / W)^2 + 1.5 (W + L) v^2.
 */
internal fun loadedKmh(massKg: Float, loadKg: Float): Float {
    val v0 = WALK_KMH / 3.6f
    val wl = massKg + loadKg
    return 3.6f * sqrt(max(0f, (1.5f * massKg * v0 * v0 - 2f * wl * (loadKg / massKg) * (loadKg / massKg)) / (1.5f * wl)))
}

/**
 * [g]'s band at its camp in place [p] (hex [h]): moving camp caches what it cannot carry and leaves
 * the grinding stones; a cache keeps, but not perfectly, and back at it the band takes up what it
 * has room for.
 */
internal fun HexGroups.campAndCache(g: HexGroup, b: Band, p: Place, h: Hex, weather: Weather, date: Date, time: BandTime) {
    if (b.campPlace.none || h != hw.placeHex[b.campPlace]) {
        // Moving camp: food that cannot be carried is cached at the old camp (an older cache is given
        // up to the mice), and the grinding stones are left behind.
        if (!b.campPlace.none) {
            // (Some goods are taken first: see [mendingKg].)
            val carry = max(0f, carryKg(g, b) * carryLoads(b) - mendingStockKg(g, b))
            dialUse?.amount(DialSite.CARRY_LOADS, carryLoads(b), carryKg(g, b) * carryLoads(b), carryKg(g, b))
            val total = g.larder
            if (total > carry) {
                // (It chooses how much of what it cannot carry to cache, as far as its time to dig and cover the pits goes;
                // the rest is left where it lies. Above the default it caches all of it with more care, see [cacheCare]:
                // the pits deeper and better hidden, at as much more time for each kg.)
                val left = 1f - carry / total
                val leftKg = leftBehindKg(g, b)
                val care = cacheCare(b)
                fun cachedAt(will: Float, care: Float) =
                    min(min(1f, will), if (leftKg > 0f) time.leftFor(Activity.CACHING) * CACHE_KG_A_DAY / care / leftKg else 0f)
                val cached = cachedAt(b.cacheWill, care)
                dialUse?.amount(DialSite.CACHING, b.cacheWill, cached * care, cachedAt(1f, 1f))
                time.spend(Activity.CACHING, cached * leftKg * care / CACHE_KG_A_DAY)
                if (cached > 0f) dropCache(b)
                var abandoned = 0f
                for (i in Store.entries) {
                    val x = g.stores[i] * left
                    g.stores[i] -= x
                    if (cached > 0f) { b.cache[i] = x * cached; b.cacheKgPer[i] = g.storeKgPer[i] }
                    abandoned += x * (1f - cached) * g.storeKgPer[i]
                }
                if (cached > 0f) b.cachePlace = b.campPlace
                if (abandoned > 0f) small.deposit(DeadPool.LITTER, b.campPlace, Kg(abandoned))
            }
            for (t in Tool.entries) if (t.heavy) b.kit[t] = 0f
            b.stayWeeks += STAY_LEARN * (b.campWeeks + 1 - b.stayWeeks)
            // Its goods: what it takes first ([mendingKg]), then the rest in what room its loads have left after the food, each
            // kind alike; what does not fit is left at the old camp (and goes back to the ground).
            val people = g.adults.toFloat()
            val spare = Material.entries.sumOf { m -> max(0f, b.goods[m] - mendingKg(b, m, people)).toDouble() }.toFloat()
            val room = max(0f, carry - g.larder)
            if (spare > room) {
                val left = 1f - room / spare
                var rots = 0f
                for (m in Material.entries) { val x = max(0f, b.goods[m] - mendingKg(b, m, people)) * left; b.goods[m] -= x; if (m in ROTS) rots += x }
                if (rots > 0f) small.deposit(DeadPool.LITTER, b.campPlace, Kg(rots))
            }
        }
        b.campWeeks = 0
    } else b.campWeeks++
    b.campPlace = p
    // A cache keeps, but not perfectly; back at it, the band takes up what it has room for.
    if (!b.cachePlace.none) {
        // (A cache spoils as stores do, by the week's weather where it lies and as well as its meat was smoked
        // and salted, and mice and badgers get into it too; boar, bears and hyenas dig
        // it up where they are about.)
        run {
            val cp = b.cachePlace
            val diggers = small.rodents[cp] / CACHE_RODENTS_HALF + small.foxes[cp] / CACHE_FOXES_HALF +
                cacheDiggers.sumOf { (fauna.adults[it][cp] / 0.5f).toDouble() }.toFloat()
            val dug = min(0.5f, CACHE_DUG * min(1f, diggers) * years) / cacheCare(b)
            if (dug > 0f) {
                var lost = 0f
                for (i in Store.entries) {
                    val x = b.cache[i] * dug
                    b.cache[i] -= x
                    lost += x * b.cacheKgPer[i]
                }
                // (What they dig up and scatter is food for them and the scavengers after them.)
                small.deposit(DeadPool.CARRION, cp, Kg(lost))
            }
        }
        val spoilt = spoil(b.cache, weather.temperature[b.cachePlace], driedKeep = driedKeep(b), kgPer = b.cacheKgPer) + CACHE_SPOIL * b.cacheMass()
        for (i in Store.entries) b.cache[i] *= 1f - CACHE_SPOIL
        small.deposit(DeadPool.LITTER, b.cachePlace, Kg(spoilt))
        if (hw.placeHex[b.cachePlace] == h) {
            val room = max(0f, carryKg(g, b) - g.larder)
            val take = min(b.cacheKg, room)
            b.takeFromCache(g, take)
            if (take > 0f) stats.meeting(Met.BANDS_BACK_AT_A_CACHE)
        }
        if (b.cacheKg < 1f) dropCache(b)
    }
}

/** What [b] cannot carry is left behind at place [p] (and goes back to the ground); at a camp it keeps, it stacks building stone and timber beyond that. */
internal fun GroupWorld.leaveExcess(b: Band, p: Place, people: Float, area: Float) {
    val gd = b.goods
    for (m in Material.entries) {
        val over = gd[m] - carried(b, m, people)
        if (over > 0f) {
            gd[m] -= over
            if (m in ROTS) small.deposit(DeadPool.LITTER, p, KgM2(over / area))
        }
    }
}

/** Gives up a band's cache: what is in it goes back to the ground. */
internal fun GroupWorld.dropCache(b: Band) {
    if (!b.cachePlace.none) small.deposit(DeadPool.LITTER, b.cachePlace, Kg(b.cacheMass()))
    b.cache.fill(0f); b.cachePlace = Place.NONE
}

/** The materials that rot back into the ground when left (stone, salt, ochre and shell stay as they are). */
internal val ROTS = setOf(Material.WOOD, Material.HIDE, Material.BONE, Material.SINEW, Material.FIBRE, Material.RESIN)
/** The kg of [b]'s goods that came from living things (those that rot), for the books. */
internal fun Band.livingGoods(): Float { var sum = 0f; for (m in ROTS) sum += goods[m]; return sum }
/** Moves [kg] of the cache (in the same mix of stores) into [to]'s stores. */
internal fun Band.takeFromCache(to: HexGroup, kg: Float) {
    val share = if (cacheKg > 0f) kg / cacheKg else 0f
    for (i in Store.entries) { val x = cache[i] * share; cache[i] -= x; addFood(to.stores, to.storeKgPer, i, x, x * cacheKgPer[i]) }
}

/** The kg [Band.cache] weighs (see [addFood]). */
internal fun Band.cacheMass() = massOf(cache, cacheKgPer)

/** Share of a cache lost each week to damp, mice and thieves. */
const val CACHE_SPOIL = 0.02f
/** Extra yearly deaths among children of a camp kept long: refuse, flies and fouled water. */
internal fun HexGroups.campFouling(g: HexGroup, campWeeks: Int = g.band?.campWeeks ?: 0): Float {
    val b = g.band ?: return 0f
    return CAMP_FOULING * tuning.campFouling * fouled(campWeeks) * b.hygiene * (1f - 0.3f * b.medicine)
}

/** How fouled a band's camp is, 0..1: not at all for its first two months, fully after a year more. */
internal fun HexGroups.fouled(b: Band) = fouled(b.campWeeks)
/** How fouled a camp kept [campWeeks] weeks is (see [fouled]). */
internal fun GroupWorld.fouled(campWeeks: Int) = min(1f, max(0f, campWeeks - stepsPerYear / 6f) / stepsPerYear)

/** How much a band would rather leave its camp than stay: the camp fouled (less where it keeps it clean), against the graves of its dead. */
internal fun HexGroups.stayCost(b: Band): Float {
    var c = FOUL_AVOID * fouled(b) * b.hygiene
    if (b.holds(Tradition.BURIAL) && b.campWeeks > 4) c -= BURIAL_STAY
    return c
}

/**
 * Caches: yearly share dug up where diggers are thick; rodents and foxes (kg/km²) at which they
 * count fully; chance a band passing a hex finds another's cache there; share it takes.
 */
const val CACHE_DUG = 2f
const val CACHE_RODENTS_HALF = 200f
const val CACHE_FOXES_HALF = 20f
const val CACHE_FIND = 0.15f
const val CACHE_TAKE = 0.6f
/**
 * Bands passing through another band's cache may find it and carry off what they can, unless they
 * are close to its owners: the more it lies in the open and the more they see, the likelier. The
 * owners hold it against the takers when they learn of it.
 */
internal fun HexGroups.robCaches(weather: Weather) {
    val caches = HashMap<Hex, ArrayList<HexGroup>>()
    for (g in groups) {
        val b = g.band ?: continue
        if (g.alive && !b.cachePlace.none && b.cacheKg > 1f) caches.getOrPut(hw.placeHex[b.cachePlace]) { ArrayList() } += g
    }
    if (caches.isEmpty()) return
    for (g in groups) {
        val b = g.band ?: continue
        if (!g.alive) continue
        for (h in g.path) {
            val owners = caches[h] ?: continue
            for (o in owners) {
                val ob = o.band ?: continue
                if (o === g || b.tie(o.id) > 0.3f || ob.cacheKg <= 1f) continue
                if (!drawn(DialSite.SCOUTS_FIND_CACHE, b.will[BandWay.SCOUTS], rnd.nextFloat(), CACHE_FIND * sight(ob.cachePlace, weather) * (1f + b.scoutShare * 5f) / cacheCare(ob))
                    { CACHE_FIND * sight(ob.cachePlace, weather) * (1f + SCOUT_SHARE * 5f) / cacheCare(ob) }) continue
                // (Found, it is the band's choice whether to take from it.)
                if (!drawn(DialSite.ROB_TAKE, b.robWill, rnd.nextFloat(), min(1f, b.robWill), cut = b.robWill > 1f) { 1f }) continue
                val takeShare = CACHE_TAKE * max(1f, b.robWill)
                dialUse?.amount(DialSite.ROB_SHARE, b.robWill, takeShare, CACHE_TAKE, cut = b.robWill < 1f)
                val room = max(0f, carryKg(g, b) - g.larder)
                val take = min(ob.cacheKg * min(1f, takeShare), room)
                if (take <= 0f) continue
                ob.takeFromCache(g, take)
                ob.befriend(g.id, -TIE_RAID * 0.5f); ob.grudge[b.mainLineage()] += 0.5f * GRUDGE
                stats.meeting(Met.CACHES_ROBBED)
                note(g) { "found and took %.0f kg from band #%d's cache".format(take, o.id) }; note(o) { "its cache was robbed by band #%d".format(g.id) }
            }
        }
    }
}

/**
 * The materials bands give each other for their use (trade on); what a person uses of each in a
 * year, kg (not measured: a full kit's wear, a year's curing, a year's tanning); how many years'
 * worth a band keeps before it gives of it.
 */
val TRADED = listOf(Material.STONE, Material.SALT, Material.OCHRE)
val YEAR_NEED = enumFloats<Material> { when (it) { Material.STONE -> 1f; Material.SALT -> 4f; Material.OCHRE -> 0.2f; else -> 0f } }
const val KEEP_YEARS = 1f
