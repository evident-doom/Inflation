package inflation.ecology

import inflation.Config
import inflation.Part
import inflation.Trace
import inflation.ecology.HexSmallLife.DeadPool
import inflation.heapify
import inflation.Kg
import inflation.KgM2
import inflation.popMin
import inflation.rankKeys
import inflation.world.get
import inflation.world.Place
import inflation.world.set
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sqrt

// The foods a foraging pass offers a party, and what it takes of them: plants reaped and gathered, shellfish, weirs, runs and mast.

/** The foods [forage] offers a party in one pass, and its pick of them. */
internal class Offers(n: Int) {
    // Each food: its [Food] (big game of [kinds] index k, or another food), place, rate a day, kg each, what each feeds
    // (kg of the party's need, see [ForageWeek.worth]), what the party weighs it at ([ForageWeek.weigh]), days each.
    val type = Array(n) { Food.BIG_GAME }; val kind = IntArray(n); val place = IntArray(n)
    val rate = FloatArray(n); val kg = FloatArray(n); val value = FloatArray(n); val weight = FloatArray(n); val time = FloatArray(n)
    // (Of each food's days, those finding it and walking to it and home: plant food's, for the census.)
    val seek = FloatArray(n); val walk = FloatArray(n)
    // (Of big game in the wild, the bands of it come on a day of searching unseen, whether or not stalked: for the hunt trace.)
    val met = FloatArray(n)
    // (Which of the band's parties takes each food: 0 the hunters, 1 the gatherers, 2 its children, who snare small game by the camp; an animal's is all 0.)
    val who = IntArray(n)
    // (For a beast, the herd in [ForageWeek.herds] each big game is of; -1 for game in the wild.)
    val herd = IntArray(n)
    var m = 0
    val order = IntArray(n); val profit = FloatArray(n); val sortKeys = LongArray(n)
    // What each party gains (as it weighs it, and what it feeds) and the time it spends on the foods it takes up, and
    // whether it wants no more; the foods taken up are the first [used] of [order].
    val gain = FloatArray(3); val fed = FloatArray(3); val spent = FloatArray(3); val full = BooleanArray(3); var used = 0

    // (Of each party, its foods not yet drawn in [takeUp].)
    private val rest = IntArray(3)
    private fun take(i: Int, w: Int) { gain[w] += rate[i] * weight[i]; fed[w] += rate[i] * value[i]; spent[w] += rate[i] * time[i]; order[used++] = i }

    fun add(t: Food, k: Int, p: Place, r: Float, e: Float, v: Float, wt: Float, h: Float, w: Int, hd: Int = -1, sk: Float = 0f, wk: Float = 0f) {
        type[m] = t; kind[m] = k; place[m] = p.i; rate[m] = r; kg[m] = e; value[m] = v; weight[m] = wt; time[m] = h; who[m] = w; herd[m] = hd
        seek[m] = sk; walk[m] = wk; met[m] = 0f; m++
    }

    /**
     * Takes up the foods best first by what the party weighs them at a day of handling while they raise each party's own
     * rate (Charnov's rule).
     * (Foods worth the same in the order they were offered in. They are drawn best first from a heap, as far as the
     * parties go: each stops at the first food not worth its while, so most are never ranked.)
     */
    fun takeUp() {
        for (i in 0 until m) profit[i] = weight[i] / time[i]
        rankKeys(profit, m, sortKeys)
        heapify(sortKeys, m)
        gain.fill(0f); fed.fill(0f); spent.fill(1f); full.fill(false); used = 0
        // (A party with no food on offer, or none left, is as good as done: so the heap is not drained for a party
        // that took up all it had, as the children with their one offer do.)
        rest.fill(0); for (i in 0 until m) rest[who[i]]++
        for (w in 0 until 3) if (rest[w] == 0) full[w] = true
        var left = m
        while (left > 0 && !(full[0] && full[1] && full[2])) {
            val i = (popMin(sortKeys, left--) and 0xffffffffL).toInt(); val w = who[i]
            if (--rest[w] == 0 && !full[w]) { if (profit[i] > gain[w] / spent[w]) take(i, w); full[w] = true; continue }
            if (full[w]) continue
            if (profit[i] <= gain[w] / spent[w]) { full[w] = true; continue }
            take(i, w)
        }
    }

    /**
     * How the gatherers' [t] (of kind [k]) paid after [takeUp]: its yield a day of handling over itself and the least the
     * gatherers took up (their rate when they stopped): above 1/2 it was taken up, below it ranked out; 0 when none was on offer.
     */
    fun pull(t: Food, k: Int = 0): Float {
        for (i in 0 until m) if (type[i] == t && kind[i] == k && who[i] == 1) return pullAt(profit[i])
        return 0f
    }

    /** How a food paying [p] a day of handling, as the gatherers weigh it, would have pulled against their least paying food taken up (see [pull]). */
    fun pullAt(p: Float): Float = if (p > 0f) p / (p + gain[1] / spent[1]) else 0f
}

/**
 * Offers [t] to [w]'s party in [o]: [r] a day of [e] kg each, taking [h] days each, each kg feeding [rich] times a kg of [t]
 * (a kill's fat, [fatOfKill]), weighed also for the food [spared] a kill takes from a rival (see [offerFoods]). A band's gatherers rank each kind of
 * food by what it weighs it at, times its effort on it: above 1 they take it up before foods that pay more, and below 1
 * leave it for ones that pay less, and either way get what it truly yields ([Offers.takeUp]). (Big game's effort is
 * the hunters' share instead, see [huntersShare].)
 */
private fun HexGroups.offer(w: ForageWeek, o: Offers, t: Food, k: Int, p: Place, r: Float, e: Float, h: Float, spared: Float = 0f, herd: Int = -1, rich: Float = 1f,
    danger: Float = 0f, seek: Float = 0f, walk: Float = 0f, child: Boolean = false) {
    val band = w.band
    val keen = if (band != null && t != Food.BIG_GAME) band.effort[t] else 1f
    if (band != null && t != Food.BIG_GAME && r > 0f && !child) dialUse?.let { u -> val f = FoodEfforts.slot(t)
        u.amount(DialSite.EFFORT_RANK, keen, keen, 1f, dial = f) }
    if (r <= 0f || e <= 0f || (band != null && t != Food.BIG_GAME && (if (child) w.childHands else w.gatherers) <= 0f)) return
    // (Biting insects slow people at whatever they do out there; fish are offered at the camp, whose place stands for the water they fish.)
    val hh = if (band != null) harassedDays(p, h) else h
    // (What it weighs a food at is over the days it takes and the days its [danger] costs: game that may kill a hunter is
    // weighed down as if it took that much longer, though it yields as it does.)
    o.add(t, k, p, r, e, e * w.feeds(t) * rich, (e * rich + spared) * w.weigh(t) * keen * (if (danger > 0f) hh / (hh + danger) else 1f), hh, if (child) 2 else if (band != null && t != Food.BIG_GAME) 1 else 0, herd,
        seek * hh / h.coerceAtLeast(1e-9f), walk * hh / h.coerceAtLeast(1e-9f))
}

/** [h] days of a band's work at place [p], as biting insects slow it there ([HexSmallLife.HARASS_TIME]). */
private fun HexGroups.harassedDays(p: Place, h: Float) = h * (1f + HexSmallLife.HARASS_TIME * small.harassment(p))

/**
 * The most foods [offerFoods] can offer [w]'s party in a pass: what its [Offers] must hold. Each place offers at most so many,
 * whether or not each is there this week: big game of each kind it may hunt on its ground ([ForageWeek.games]) and each herd
 * ([ForageWeek.herds]); along its way, [PATH_OFFERS] a hex; [PLANT_OFFERS] a place it gathers on; and for a band, [CAMP_OFFERS] at its camp.
 * (Sized from the offers themselves: a camp's own foods are not covered by its way's, which a band that stayed has little of.)
 */
internal fun mostOffers(w: ForageWeek): Int =
    w.games + w.herds.size + w.g.path.size * PATH_OFFERS + (w.gatherAt.size + w.roundCamp.size) * PLANT_OFFERS + (if (w.band != null) CAMP_OFFERS else 0)

/** Foods offered a hex along a party's way: small game, carrion, and bones (cracked, or for a band rendered; one or the other). */
private const val PATH_OFFERS = 3
/** Plant foods offered a place a party gathers on: fruit, roots, mast (a beast's roots and mast are one), finds, grass seed, wild grain and sown grain. */
private const val PLANT_OFFERS = 7
/** Foods offered at a band's camp: its children's small game, fish, shellfish, the spring run and the weir's catch. */
private const val CAMP_OFFERS = 5

/** [forage]'s foods on offer to [w]'s party in a pass, into [o]: along its way, round its camp and, for a band, in the water by it. */
@Part("foraging: offer foods")
internal fun HexGroups.offerFoods(w: ForageWeek, o: Offers) {
    val g = w.g; val band = w.band; val nk = w.nk; val sweep = w.sweep; val days = this.days
    val adults = fauna.adults; val young = fauna.young
    o.m = 0
    // (What a kill is worth to it: see below. Its butchers' skill grows with each kill, so once a pass.)
    val credit = creditShare(band)
    val most = if (band == null) max(w.wantAll, w.gutKg) else killKeeps(band, w.wantAll, w.weather.temperature[g.place])
    // (A beast weighs a kill also by the food the dead one would have taken from it: a week of its intake, as far as their
    // diets overlap, valued as its own. A rival of its own food is the more worth killing; a plant-eater is not one.)
    val spared = FloatArray(nk) { k -> if (band != null) 0f else { val ps = fauna.kinds[k]; w.d.overlap(ps.diet) * ps.dailyIntakeKg * days } }
    // Big game over its hunting ground. (A band's hunters fan out over less of it than the whole band would, as for a
    // smaller party: see [searchKm2] and [ForageWeek.ground].)
    for (j in 0 until w.ground.size) {
        val p = w.ground.place[j]; val huntSwept = w.ground.km2[j]
        for (c in w.gameFrom[j] until w.gameFrom[j + 1]) {
            val i = w.gameAt[c]; val k = i - j * nk
            // (The grown of its packs standing here are game as its settled grown are: [HexFauna.packGrown].)
            val grown = adults[k][p] + fauna.packAt(k, Place(p))
            val dens = grown + young[k][p]
            if (dens <= 0f) continue
            // A kill is worth what the party can eat of it, gorging included (a band also what it can
            // dry); the rest is left to others.
            val meat1 = min(fauna.kinds[k].massKg * credit, most)
            // (A band's kill of a grown one is the richer for its fat, the young lean: what it weighs is what its kills would be, as
            // many grown as there are among them.)
            val rich = if (band != null && meat1 > 0f) {
                val fat1 = fatOfKill(k, Place(p), meat1, band) * grown / dens
                1f + fat1 / meat1 * (w.fatFeeds / w.feeds(Food.BIG_GAME).coerceAtLeast(1e-6f) - 1f)
            } else 1f
            // (And the hunters it may cost, the grown and the young as many as there are: [dangerDays].)
            val ps = fauna.kinds[k]
            val danger = (grown * dangerDays(g.species, ps, ps.massKg, w.huntHeads) +
                young[k][p] * dangerDays(g.species, ps, HexFauna.YOUNG_WEIGHT * ps.massKg, w.huntHeads)) / dens
            // (Each kill also costs the stalks and chases that failed before it.)
            val before = o.m
            // (A band carries its kill home; a beast eats it where it lies: [ForageWeek.carryDaysPerKg].)
            offer(w, o, Food.BIG_GAME, k, Place(p), dens * huntSwept * w.odds[i] / days, meat1, HANDLING_DAYS + STALK_DAYS * w.stalks[i] + meat1 * w.carryDaysPerKg,
                spared[k], rich = rich, danger = danger)
            if (o.m > before) o.met[before] = dens * huntSwept * w.met[i] / days
        }
    }
    // And the herds kept at bands' camps on it, as their kind in the wild ([herdOdds]).
    for ((x, hg) in w.herds.withIndex()) {
        val b = hg.g.band ?: continue
        val n = (if (hg.young) b.herds.youngHerd() else b.herds.herd).getOrNull(hg.k) ?: continue
        if (n < 1f) continue
        val p = w.ground.place[hg.j]
        val meat1 = min((if (hg.young) HexFauna.YOUNG_WEIGHT else 1f) * fauna.kinds[hg.k].massKg * credit, most)
        offer(w, o, Food.BIG_GAME, hg.k, Place(p), n / land.areaKm2[p] * w.ground.km2[hg.j] * hg.odds / days, meat1,
            HANDLING_DAYS + STALK_DAYS * hg.stalks + meat1 * w.eats, spared[hg.k], x)
    }
    // The rest along its way.
    for (h in g.path) {
        val p = hw.placeNear(g.place, h).takeIf { !it.none } ?: continue
        val area = land.areaKm2[p]
        // (Carrion lies once a step, so the ground holds only its area of it; small game is searched again each tick of a
        // step of several, as the hunting ground is: see [huntGround].)
        val swept = min(area, sweep)
        val small = this.small
        // (Small game in cover is found the less, on burnt or grazed bare ground the more: [smallSeen].)
        offer(w, o, Food.SMALL_GAME, 0, p, small.smallPreyKg(p) * min(area * ticks, w.smallSweep * smallSeen(p)) * w.smallCatch / days, 1f, w.smallDays)
        if (w.d.carrion > 0f) {
            // (At most half of what lies on the ground searched: the rest is not found in time, or others get it;
            // and only the scavengers by trade, which watch the vultures and quarter the ground for it, find
            // that much: a wolf or a band comes on a carcass now and then, not every week.)
            val found = CARRION_FOUND * min(1f, w.d.carrion / SCAVENGER_CARRION)
            // (What lies now, as far as it finds it, and what the groups found of it as it lay through the step: [HexSmallLife.groupCarrion].)
            val held = min(small.groupCarrion[p], foundShare(w, p) * small.groupCarrionFound[p])
            offer(w, o, Food.CARRION, 0, p, (KgM2(found * small.carrion[p]).perKm2.over(swept).v + KgM2(held).perKm2.over(area).v) / days, 1f, w.carrionDays)
            // (How fast its search comes on carrion as it lies, a day, for the next step's share of it: [HexSmallLife.groupCarrionRate].)
            if (!w.carrionNoted) small.groupCarrionRate[p] += carrionRate(w, p)
            if (Niches.cracksBones(w.s)) offer(w, o, Food.BONES, 0, p, KgM2(found * small.bones[p]).perKm2.over(swept).v / days, 1f, w.eats * BONE_DAYS)
            else if (band != null && w.worth(Food.BONES) > 0f)
                offer(w, o, Food.BONES, 0, p, KgM2(found * small.bones[p]).perKm2.over(swept).v / days, 1f, w.renderDays + w.eats * GREASE_YIELD)
        }
    }
    // A band's children snare small game on the ground by the camp, a party of their own: they stay near it, and what they
    // can take there is their own pay, not weighed against the grown gatherers' (whose time is worth more), nor out of their days.
    if (band != null && w.childHands > 0f) { val p = g.place
        offer(w, o, Food.SMALL_GAME, 0, p, small.smallPreyKg(p) * min(land.areaKm2[p], w.childSmallSweep * smallSeen(p)) * w.smallCatch / days, 1f, w.childSmallDays, child = true) }
    // (What a band has taken near where it lives, t.wornGround: a light band finds each next kilo of a place the slower, a
    // settled one walks the farther from its fixed spot for it, [WornGround].)
    val wornLand = if (band != null && w.eatsPlants) worn() else null
    val spot = if (wornLand != null) settledSpot(g) else null
    // (The ground it walked, then the country round its camp, which holds none of it.)
    if (w.eatsPlants) for (qi in 0 until w.gatherAt.size + w.roundCamp.size) {
        val q = if (qi < w.gatherAt.size) w.gatherAt[qi] else w.roundCamp[qi - w.gatherAt.size]
        val part = if (qi < w.gatherAt.size) 1f else w.roundShare
        // A band can strip so much of a place in a week, as its skill allows; an animal finds what lies along its way.
        // (On strange ground people find little of it.)
        val knowHere = band?.let { plantKnow(it, hw.placeHex[q]) } ?: 1f
        // (Gatherers who go further from camp cover more of the ground.)
        val reach = band?.let { b -> dialed(DialSite.GATHER_REACH, b.gatherRange, min(0.9f, b.gatherShare() * knowHere * b.gatherRange) * part,
            cut = b.gatherShare() * knowHere * b.gatherRange > 0.9f) { min(0.9f, b.gatherShare() * knowHere) * part } }
            ?: min(1f, w.smallSweep * g.path.size / land.areaKm2[q])
        // (The unskilled, and strangers to its plants, are slow at it.)
        val slow = band?.slowness(Skill.GATHER, knowHere) ?: 1f
        val eats = w.eats
        val pickQ = (w.pickDays - eats) * slow + eats; val seedQ = (w.seedDays - eats) * slow + eats
        val fruitQ = (w.fruitDays - eats) * slow + eats
        val seedReach = band?.let { it.gatherShare() * knowHere * part } ?: reach
        // (Gatherers take the near ground first: the share they reach of a place as a disc about the camp, two thirds of
        // its radius out on average; a place round the camp lies half a hex further. The walk takes no skill.)
        val off = if (qi < w.gatherAt.size) 0f else hex.hexKm.toFloat() / 2f
        fun walkKm(share: Float) = 2f / 3f * sqrt(share * land.areaKm2[q] / PI.toFloat()) + off
        val walk = walkKm(reach)
        val seedWalk = walkKm(seedReach)
        // (Gatherers go out and back once a day whatever they bring home: the walk costs each kg its share of a full load
        // ([GATHER_LOAD_KG]) or of the day's take in the field, which is less where the work is slow. [field] is the
        // party-days a kg takes out there, finding and taking it (seed and grain: all their handling, threshing and grinding
        // with it, an over-count). Hadza women walk some 5.8 km a day, Pontzer et al. 2012.)
        val dayWalk = if (band != null) 2f / (WALK_KM_PER_HOUR * Activity.WORK_HOURS) else 0f
        // (Food too thin to find in any time, [field] infinite, is walked to as before: it is not taken up, and Inf - Inf
        // in [plant]'s worn-ground reckoning would make its days NaN.)
        fun walked(t: Food, km: Float, field: Float) = km * max(w.walkDaysPerKm / dryShare(t),
            (field - w.eats).let { f -> if (f.isFinite()) dayWalk * max(0f, f) else 0f })
        // (A band's gatherers must find fruit before they pick it, as they must roots: the thinner it grows, the longer a
        // kilo; see [PLANT_SIGHT_M2_PER_DAY].)
        val food = fauna.food
        // (A plant food on offer at [q]: [share] of the ground reached, of [density] kg a km², for [workDays] a kg.)
        fun plant(f: Food, kind: Int, share: Float, density: Float, workDays: Float, seek: Float = 0f, walked: Float = 0f, rich: Float = 1f) {
            var h = workDays; var sk = seek; var wk = walked
            val k = wornKind(f, kind)
            if (wornLand != null && k >= 0 && density > 0f) {
                val left = land.total(Place(q), KgM2(density)).v
                if (spot == null) {
                    // (A light band: the best of the place went first.)
                    val pace = wornPaceAt(wornLand.takenShare(k, Place(q), left))
                    h = wk + (h - wk) / pace; sk /= pace
                } else if (spot.place == q) {
                    // (A settled band: the ground near its spot is gone over.)
                    val farther = wornWalkAt(wornLand.spotTakenShare(spot, k, Place(q), share, left))
                    h += wk * (farther - 1f); wk *= farther
                }
            }
            // (A band strips its share of what is left each tick: over a step of several, the more of it, [overTicks].)
            val got = if (band != null) overTicks(share) else share
            offer(w, o, f, kind, Place(q), land.total(Place(q), KgM2(got * density)).v / days, 1f, h, rich = rich, seek = sk, walk = wk)
        }
        fun sought(density: Float) = if (band != null && density > 0f) 1f / (PLANT_SIGHT_M2_PER_DAY * density * w.gatherers) else 0f
        plant(Food.FRUIT, 0, reach, food.fruit[q], fruitQ + sought(food.fruit[q]) + walked(Food.FRUIT, walk, fruitQ + sought(food.fruit[q])), sought(food.fruit[q]), walked(Food.FRUIT, walk, fruitQ + sought(food.fruit[q])))
        // (Roots likewise, searched out over less ground a day, [ROOT_SEARCH_M2_PER_DAY]; none are dug in frozen ground,
        // [HexFood.diggable]. The mast lying under the trees is picked up a nut at a time, as fruit is picked ([MAST_KIND]);
        // its bitter share must be leached before it is eaten, by bands that know how and have water by them
        // ([MAST_LEACH_DAYS_PER_KG]). An animal roots for both as one.)
        if (band != null) {
            val bulbs = food.diggable(Place(q))
            val rootSearch = if (bulbs > 0f) 1f / (ROOT_SEARCH_M2_PER_DAY * bulbs * w.gatherers) else 0f
            plant(Food.ROOTS, 0, reach, bulbs, ROOT_DIGGING * pickQ + rootSearch + walked(Food.ROOTS, walk, ROOT_DIGGING * pickQ + rootSearch), rootSearch, walked(Food.ROOTS, walk, ROOT_DIGGING * pickQ + rootSearch))
            val bitter = if (band.leachesMast(land.drink.water[q] >= Thresholds.WET || land.drink.waterKm[q] < 3f)) MAST_BITTER else 0f
            val eaten = 1f - MAST_BITTER + bitter
            val leach = bitter / eaten * MAST_LEACH_DAYS_PER_KG / w.gatherers * band.slowness(Skill.LEACHING)
            plant(Food.ROOTS, MAST_KIND, reach * eaten, food.mast[q], fruitQ + leach + sought(food.mast[q]) + walked(Food.ROOTS, walk, fruitQ + sought(food.mast[q])), sought(food.mast[q]), walked(Food.ROOTS, walk, fruitQ + sought(food.mast[q])), MAST_RICH)
        } else plant(Food.ROOTS, 0, reach, food.underground(Place(q)), ROOT_DIGGING * pickQ + walked(Food.ROOTS, walk, 0f))
        plant(Food.FINDS, 0, reach, food.finds[q], ROOT_DIGGING * pickQ + walked(Food.FINDS, walk, ROOT_DIGGING * pickQ), walked = walked(Food.FINDS, walk, ROOT_DIGGING * pickQ))
        // Grass seed must be reaped, threshed and ground: worth it only with the tools and the hands.
        // (The small seeds of wild grasses are beaten out, parched and winnowed at their own rate, [smallSeedDaysPerKg]; both
        // must be found, as fruit is. A band beats them out the sooner the thicker they stand, [standHandling]: over the place,
        // as the grass grows.)
        // (Its days a kilo against wild cereal's in a stand of [REF_STAND_KG_M2], whose days [seedQ] holds.)
        val refReap = reapDaysPerKg(band, tuning)
        val seedStand = if (band != null) smallSeedDaysPerKg(band, food.seeds[q]) / refReap else 1f
        plant(Food.SEED, 0, seedReach, food.seeds[q], (seedQ - eats) * seedStand + eats + sought(food.seeds[q]) + walked(Food.SEED, seedWalk, (seedQ - eats) * seedStand + eats + sought(food.seeds[q])),
            sought(food.seeds[q]), walked(Food.SEED, seedWalk, (seedQ - eats) * seedStand + eats + sought(food.seeds[q])))
        // Wild and sown cereals: reaped like grass seed, but richer where they stand. A band's reapers cut a kilo the
        // sooner the thicker it stands ([reapDaysPerKg]): the wild stand as thick as its grain stands in it, a sown field
        // (kind [FIELD_KIND]) as thick as it stands in the plot. (An animal eats the grain where it finds it.)
        fun grainQ(stand: Float) = if (band != null) (seedQ - eats) * reapDaysPerKg(band, tuning, stand) / refReap + eats else seedQ
        val wild = food.grain[q]; val sown = food.fieldGrain[q]
        plant(Food.GRAIN, 0, seedReach, wild, grainQ(food.wildStand(Place(q))) + sought(wild) + walked(Food.GRAIN, seedWalk, grainQ(food.wildStand(Place(q))) + sought(wild)), sought(wild),
            walked(Food.GRAIN, seedWalk, grainQ(food.wildStand(Place(q))) + sought(wild)))
        if (sown > 0f && !food.fieldRipening(Place(q)) && mayReap(g, Place(q))) plant(Food.GRAIN, FIELD_KIND, seedReach, sown, grainQ(food.fieldStand(Place(q))) + sought(sown) + walked(Food.GRAIN, seedWalk, grainQ(food.fieldStand(Place(q))) + sought(sown)),
            sought(sown), walked(Food.GRAIN, seedWalk, grainQ(food.fieldStand(Place(q))) + sought(sown)))
    }
    // Bands fish the river, the lake or the sea by their camp, as their skill and nets allow ([Band.fishing]); and gather
    // shellfish off the shore, which wants neither (kind [SHELLFISH_KIND] of the fish offered).
    if (band != null) {
        val h = hw.placeHex[g.place]
        offer(w, o, Food.FISH, 0, g.place, fauna.water.fishWithin(h, band.fishing(), HexWater.Catch.FISH) / days, 1f, FISH_HANDLING_DAYS_PER_KG / w.gatherHands * band.slowness(Skill.FISH) + w.eats)
        offer(w, o, Food.FISH, SHELLFISH_KIND, g.place, fauna.water.fishWithin(h, 1f, HexWater.Catch.SHELLFISH) / days, 1f, SHELLFISH_DAYS_PER_KG / w.gatherHands + w.eats)
        // And the spring run's fish going up the river by the camp, speared or dip-netted from the bank (kind [RUN_KIND]): a
        // kilo takes the wait for fish to come within reach ([HexWater.runWithinReach]), slower for unskilled hands, and its
        // landing and gutting ([WEIR_HANDLING_DAYS_PER_KG]). The more fish go by, the less the wait: where the run crowds a
        // narrow river it pays as nothing else does, and elsewhere hardly at all.
        val runKg = fauna.water.fishWithin(h, band.fishing(), HexWater.Catch.RUN)
        val reachKg = fauna.water.runWithinReach(h)
        if (runKg > 0f && reachKg > 0f)
            offer(w, o, Food.FISH, RUN_KIND, g.place, runKg / days, 1f, (band.slowness(Skill.FISH) / reachKg + WEIR_HANDLING_DAYS_PER_KG) / w.gatherHands + w.eats)
        // And fish trapped at a weir across the river by the camp, where one stands that it may use (kind [WEIR_KIND]): the
        // tending of its traps over what they take, and the landing and gutting of each kilo ([weirDays]). A weir pays
        // only where many fish pass; it wants no fishing skill.
        val width = fauna.water.riverWidthM(h)
        val blocked = weirAt(g, h)
        if (blocked > 0f) {
            val kg = fauna.water.weirWithin(h, blocked)
            if (kg > 0f) offer(w, o, Food.FISH, WEIR_KIND, g.place, kg / days, 1f, weirDays(blocked * width, kg) / w.gatherHands + w.eats)
        }
        // (What fish from a whole weir there would pay it, as it weighs them, a day of handling: whether one would be worth
        // building, see [weirAsk].)
        val whole = weirLengthM(h)
        val wholeKg = if (whole > 0f) fauna.water.weirWouldTake(h, whole / width) else 0f
        w.weirProbe = if (wholeKg > 0f && w.gatherers > 0f)
            w.weigh(Food.FISH) * band.effort[Food.FISH] / harassedDays(g.place, weirDays(whole, wholeKg) / w.gatherHands + w.eats) else 0f
        if (wholeKg > 0f) { w.weirFood = wholeKg * w.worth(Food.FISH); w.weirTend = weirDays(whole, wholeKg) * wholeKg }
    }
}

/** [forage]'s taking of the foods [w]'s party took up in [o], at those rates for the [f] share of its working days. */
@Part("foraging: take foods")
internal fun HexGroups.takeFoods(w: ForageWeek, o: Offers, f: Float) {
    val g = w.g; val band = w.band; val haul = w.haul
    for (r in 0 until o.used) {
        val i = o.order[r]
        val expected = w.workDays * o.rate[i] / o.spent[o.who[i]] * f
        val p = o.place[i]
        // (Days of work counted for the whole band: each party's days in its share of the hands.)
        if (o.type[i] != Food.BIG_GAME) { val d = expected * w.party[o.who[i]]; w.busy += d * o.time[i]
            if (band != null) { w.gatherHandle += d * o.time[i]; w.plantSeek += d * o.seek[i]; w.plantWalk += d * o.walk[i]
                if (o.type[i] == Food.GRAIN) { if (o.kind[i] == FIELD_KIND) w.fieldDays += d * o.time[i] else w.wildDays += d * o.time[i] } } }
        when (o.type[i]) {
            Food.FRUIT, Food.ROOTS, Food.FINDS -> w.plantWorkDays += expected * o.time[i] * w.party[o.who[i]]
            Food.SEED, Food.GRAIN -> { val d = expected * o.time[i] * w.party[o.who[i]]; w.plantWorkDays += d; w.seedWorkDays += d }
            else -> {}
        }
        val fedBefore = w.fed
        when (o.type[i]) {
            Food.BIG_GAME -> {
                if (HUNT_DIAG && band == null) w.killsExpected += expected
                val kills1 = if (o.herd[i] >= 0) raidHerd(g, w.herds[o.herd[i]], Place(p), expected, o.kg[i], w.rnd, haul)
                    .also { k -> diag(g.species, HuntStep.HERD_KILLS_SOUGHT, expected); diag(g.species, HuntStep.HERD_KILLS, k.toFloat()) }
                    else huntGame(g, o.kind[i], Place(p), expected, o.kg[i], w.spareYoung, w.rnd, haul).also { if (it > 0) w.foughtBack += haul.fought }
                val luck = (kills1 - expected) * o.time[i] * w.party[0]
                if (luck > 0f) w.killOver += luck else w.killUnder -= luck
                // (A beast's luck at it: the kills beyond or short of those expected, each at what a kill of it was offered at; see
                // [ForageWeek.killLuck]. What the kills then lose, to stronger scavengers and the like, is no luck: the next pass
                // hunts for it.)
                if (band == null) w.killLuck += (kills1 - expected) * o.value[i]
                if (kills1 <= 0) continue
                if (band != null && o.kind[i] == band.totem.i) {
                    band.totemBroken = TOTEM_SHAME_YEARS
                    stats.meeting(Met.BANDS_ATE_THEIR_TOTEM)
                }
                w.busy += kills1 * o.time[i] * w.party[0]
                if (band != null) w.huntHandle += kills1 * o.time[i] * w.party[0]
                if (HUNT_DIAG && band == null) {
                    diag(g.species, HuntStep.PACK_KILLS, fauna.kinds[o.kind[i]], kills1.toFloat())
                    diag(g.species, HuntStep.PACK_KILL_KG, fauna.kinds[o.kind[i]], haul.kg) }
                // (Stronger scavengers may come on a beast's kills and take of them; the days it would have spent eating
                // what they took are its own again, to hunt in.)
                if (band == null) w.busy -= scavengedAtKill(g, Place(p), kills1, haul, w.rnd) * w.eats * w.party[0]
                // (Its fat feeds as grease, the rest as lean meat; for the census, the kills by month and by prey kind, lean and fat by weight.)
                w.meat += haul.kg - haul.fat; w.killFat += haul.fat
                w.fed += (haul.kg - haul.fat) * w.feeds(Food.BIG_GAME) + haul.fat * w.fatFeeds
                if (band != null) { val m = w.weather.date.month
                    stats.tally(Tally.bandKills(m, Tally.KillPart.KILLS), kills1)
                    stats.tally(Tally.bandKills(m, Tally.KillPart.LEAN_KG), (haul.kg - haul.fat).roundToInt())
                    stats.tally(Tally.bandKills(m, Tally.KillPart.FAT_KG), haul.fat.roundToInt())
                    val prey = fauna.kinds[o.kind[i]].name
                    stats.tally(Tally.bandKillsOf(prey, Tally.KillPart.KILLS), kills1)
                    stats.tally(Tally.bandKillsOf(prey, Tally.KillPart.LEAN_KG), (haul.kg - haul.fat).roundToInt())
                    stats.tally(Tally.bandKillsOf(prey, Tally.KillPart.FAT_KG), haul.fat.roundToInt()) }
            }
            Food.SMALL_GAME -> small.catchSmallIn(Place(p), Kg(expected), min(land.areaKm2[p] * ticks, (if (o.who[i] == 2) w.childSmallSweep else w.smallSweep) * smallSeen(Place(p))), w.weather).v.let { w.smallGot += it; if (o.who[i] == 2) w.kidsSmall += it; w.fed += it * w.feeds(Food.SMALL_GAME)
                if (band == null) diag(g.species, HuntStep.PACK_SMALL_GAME_KG, it) }
            Food.CARRION -> scavenge(g, Place(p), expected, w.sweep, w.rnd, foundShare(w, Place(p))).let { w.scraps += it; w.fed += it * w.feeds(Food.CARRION)
                if (band == null) diag(g.species, HuntStep.PACK_CARRION_KG, it) }
            Food.BONES -> small.crackBonesIn(Place(p), Kg(expected), min(land.areaKm2[p], w.sweep)).v.let { kg ->
                if (band == null) { w.scraps += kg; w.fed += kg * w.worth(Food.BONES) }
                else if (kg > 0f) {
                    // (The boiled bone, its grease taken, is thrown out at the camp.)
                    val grease = kg * greaseOf(band); val fed = kg * w.worth(Food.BONES)
                    w.grease += grease; w.greaseFed += fed; w.fed += fed
                    small.deposit(DeadPool.LITTER, Place(p), Kg(kg - grease)); band.practise(Skill.STONE_BOILING)
                }
            }
            Food.FRUIT, Food.ROOTS, Food.SEED, Food.FINDS -> {
                val seedStand = if (o.type[i] == Food.SEED) fauna.food.seeds[p] else 0f
                gather(g, o.type[i], o.kind[i], Place(p), expected, w.hands, haul)
                if (o.type[i] == Food.SEED) traceStand(w, "grass seed", seedStand, seedStand, haul.mass)
                if (haul.busy > 0f) { w.busy += haul.busy; w.haulOver += haul.busy }
                val rich = if (o.type[i] == Food.ROOTS && o.kind[i] == MAST_KIND) MAST_RICH else 1f
                val fed = haul.kg * w.worth(o.type[i]) * rich
                w.plants += haul.kg; w.fed += haul.kg * w.feeds(o.type[i]) * rich
                // (Mast, dried, keeps as seed does, for a year or more; bulbs and roots as roots.)
                val st = when (o.type[i]) { Food.ROOTS -> if (o.kind[i] == MAST_KIND) Store.SEED else Store.ROOTS; Food.SEED -> Store.SEED; else -> Store.FRUIT }
                w.plantKind[st] += fed; w.plantKg[st] += haul.mass
                val from = when (o.type[i]) { Food.FRUIT -> PlantFrom.FRUIT; Food.SEED -> PlantFrom.SEED; Food.FINDS -> PlantFrom.FINDS
                    else -> if (o.kind[i] == MAST_KIND) PlantFrom.MAST else PlantFrom.BULBS }
                w.plantFrom[from] += fed
                if (from == PlantFrom.MAST && haul.kg > 0f && band != null && band.knowsSkill[Skill.LEACHING]) band.practise(Skill.LEACHING)
            }
            Food.FISH -> if (o.kind[i] == SHELLFISH_KIND) fauna.water.fishFor(hw.placeHex[p], expected, 1f, HexWater.Catch.SHELLFISH).let { w.fish += it; w.fed += it * w.feeds(Food.FISH) }
                else if (o.kind[i] == RUN_KIND) fauna.water.fishFor(hw.placeHex[p], expected, band?.fishing() ?: 1f, HexWater.Catch.RUN).let { w.fish += it; w.fished += it; w.fed += it * w.feeds(Food.FISH) }
                else if (o.kind[i] == WEIR_KIND) fauna.water.weirFor(hw.placeHex[p], expected, weirAt(g, hw.placeHex[p])).let { w.fish += it; w.fished += it; w.fed += it * w.feeds(Food.FISH) }
                else fauna.water.fishFor(hw.placeHex[p], expected, band?.fishing() ?: 1f, HexWater.Catch.FISH).let { w.fish += it; w.fished += it; w.fed += it * w.feeds(Food.FISH) }
            Food.GRAIN -> {
                val sown = o.kind[i] == FIELD_KIND
                val stand = if (sown) fauna.food.fieldStand(Place(p)) else fauna.food.wildStand(Place(p))
                val mean = if (sown) fauna.food.fieldGrain[p] else fauna.food.grain[p]
                reap(g, Place(p), sown, expected, haul)
                traceStand(w, if (sown) "sown field" else "wild cereal", stand, mean, haul.mass)
                if (band != null) { if (sown) w.fieldKg += haul.mass else w.wildKg += haul.mass
                    w.grindDays += haul.mass * grindDaysPerKg(band) }
                val fed = haul.kg * w.worth(Food.GRAIN)
                w.plants += haul.kg; w.fed += haul.kg * w.feeds(Food.GRAIN); w.plantKind[Store.SEED] += fed; w.plantKg[Store.SEED] += haul.mass
                w.plantFrom[PlantFrom.GRAIN] += fed
            }
        }
        w.fedBy[o.who[i]] += w.fed - fedBefore
    }
}

/**
 * Prints a sample of bands' reaping (with the plan trace on): the grain or seed as thick as it stood where they took it, kg/m²
 * of the stand ([HexFood.wildStand], [HexFood.fieldStand]; grass seed over the place) and of the place, and the kg taken.
 * Report only: for setting [REF_STAND_KG_M2] where the record's reaping rates were measured.
 */
private fun traceStand(w: ForageWeek, kind: String, stand: Float, placeMean: Float, kg: Float) {
    val g = w.g
    if (g.band == null || kg <= 0f || !STAND_TRACE.wants(g.id.toLong(), w.weather.date.absoluteDay, part = kind.hashCode().toLong())) return
    System.err.println("stand trace: band %d, %s, %s, stand %.5f kg/m2, place %.6f kg/m2, took %.1f kg".format(g.id, w.weather.date, kind, stand, placeMean, kg))
}
private val STAND_TRACE = Trace(Config.Key.PLAN_TRACE, "stand", share = 0.02f, most = 3000)

/** [forage]'s reaping of the wild or ([sown]) the sown grain at place [p], [expected] kg a party could take: [out] gets the kilos it is worth, and weighs. */
private fun GroupWorld.reap(g: HexGroup, p: Place, sown: Boolean, expected: Float, out: Haul) {
    val band = g.band
    val food = fauna.food
    val want = land.spread(p, Kg(expected)).v
    val fromField = if (sown) food.reapField(p, KgM2(want)).v else 0f
    val fromWild = if (sown) 0f else food.take(food.grain, p, KgM2(want)).v
    val taken = land.total(p, KgM2(fromField + fromWild)).v
    val took = taken * seedValue(band) * (band?.cookValue(Food.GRAIN) ?: 1f)
    out.kg = took; out.mass = taken
    // A sickle takes the ears that hold their grain more than those that shatter at a touch. What is reaped from a sown
    // field is kept as seed corn; what is reaped from the wild stand goes with the rest of the grain.
    if (band != null && took > 0f) {
        fun harvested(d: Float) = band.reapedDomestic(d)
        val tookField = took * fromField / (fromField + fromWild).coerceAtLeast(1e-12f); val tookWild = took - tookField
        // (The week's reaping is kept with the stores at the week's end, where the seed corn is held to the grain kept.)
        val corn = band.seedCorn; val wild = max(0f, g.stores[Store.SEED] - corn)
        if (tookField > 0f) band.seedDomestic = (band.seedDomestic * corn + harvested(food.fieldDomestic[p]) * tookField) / (corn + tookField)
        if (tookField > 0f) band.seedPulse = (band.seedPulse * corn + food.ripePulse[p] * tookField) / (corn + tookField)
        if (tookWild > 0f) band.grainDomestic = (band.grainDomestic * wild + harvested(food.domestic[p]) * tookWild) / (wild + tookWild)
        band.seedCorn = corn + tookField
        stats.tally(Tally.of(g.species, Tally.OfKind.GRAIN_REAPED), took.toInt())
    }
}

/**
 * Person-days to reap, thresh and clean a kilo of grain standing [stand] kg/m² where it grows: cutting it, by the ground the
 * reapers cover ([CUT_M2_PER_HOUR], more with a sickle), so a kilo from a stand half as thick takes twice as long to cut; and
 * threshing, winnowing and cleaning it ([THRESH_DAYS_PER_KG]), as long a kilo however it stood. And, with grinding stones, the
 * days to grind it to meal ([GRIND_DAYS_PER_KG]), which make more of it ([seedValue]) but take longer, not less.
 */
internal fun reapDaysPerKg(band: Band?, tuning: inflation.Tuning, stand: Float = REF_STAND_KG_M2) =
    1f / (CUT_M2_PER_HOUR * (1f + (band?.sickleGain(tuning) ?: 0f)) * Activity.WORK_HOURS * stand.coerceAtLeast(1e-6f)) +
        THRESH_DAYS_PER_KG + grindDaysPerKg(band)

/** Person-days band [band] takes to grind a kilo of seed or grain to meal: [GRIND_DAYS_PER_KG] with grinding stones, none without. */
internal fun grindDaysPerKg(band: Band?) = GRIND_DAYS_PER_KG * (band?.has(Tool.GRINDER) ?: 0f)

/**
 * Person-days band [band] takes to gather a kilo of wild grass seed standing [stand] kg/m² over the place: beaten into baskets,
 * parched and winnowed ([SMALL_SEED_DAYS_PER_KG] where it stands as thick as [REF_SEED_STAND_KG_M2], the longer the thinner it
 * stands: [standHandling]), and ground where it has grinding stones.
 */
internal fun smallSeedDaysPerKg(band: Band?, stand: Float) = SMALL_SEED_DAYS_PER_KG * standHandling(stand, REF_SEED_STAND_KG_M2) + grindDaysPerKg(band)

/**
 * How long a kilo of grass seed standing [stand] kg/m² takes to beat out against one in a stand of [ref]: the beating share of
 * its days ([REAP_CUT_SHARE]) goes by the ground covered.
 */
internal fun standHandling(stand: Float, ref: Float = REF_SEED_STAND_KG_M2) = 1f - REAP_CUT_SHARE + REAP_CUT_SHARE * ref / stand.coerceAtLeast(1e-6f)

/** Of grain whose ears hold their grain at share [d], the share in what band [b] reaps: a sickle takes them more than those that shatter at a touch. */
internal fun Band.reapedDomestic(d: Float): Float { val sel = SICKLE_SELECT * has(Tool.SICKLE); return d * (1f + sel) / (1f + sel * d).coerceAtLeast(1e-6f) }

/**
 * [forage]'s gathering of plant food [t] (fruit, roots of [kind]: a band's bulbs and roots, or its [MAST_KIND], an animal's
 * both, grass seed or small finds) at place [p], [expected] kg a party could take: [out] gets the kilos it is worth once seed
 * is ground and food cooked or leached, what they weigh, and the party-days the leaching took ([hands] at it).
 */
internal fun GroupWorld.gather(g: HexGroup, t: Food, kind: Int, p: Place, expected: Float, hands: Int, out: Haul) {
    val s = g.species; val band = g.band
    out.busy = 0f
    val food = fauna.food
    val stock = when (t) { Food.FRUIT -> food.fruit; Food.ROOTS -> if (kind == MAST_KIND) food.mast else food.roots; Food.SEED -> food.seeds; else -> food.finds }
    fun take(kg: KgM2) = if (t == Food.ROOTS && band == null) food.takeUnderground(p, kg)
        else if (stock === food.mast) food.takeMast(HexFood.MastFate.PEOPLE, p, kg) else food.take(stock, p, kg)
    val taken = land.total(p, take(land.spread(p, Kg(expected)))).v
    var took = taken; var mass = taken
    // Raw seed is poorly digested; ground and baked into bread it feeds fully.
    if (t == Food.SEED) took *= seedValue(band)
    if (band != null && took > 0f) {
        // Earth ovens and gruel make more of it; bands that know how leach bitter plants too.
        val cook = band.cookValue(t)
        if (cook > 1f) { took *= cook; band.practise(if (t == Food.ROOTS) Skill.EARTH_OVEN else Skill.STONE_BOILING) }
        if (t != Food.FINDS) {
            var extra = took * band.detoxExtra(land.palatable[p], land.drink.water[p] >= Thresholds.WET || land.drink.waterKm[p] < 3f)
            // (What is leached is more of the plants, bitter before: taken from them, as far as there is any.)
            if (extra > 0f) {
                val more = land.total(p, take(land.spread(p, Kg(extra * taken / took)))).v
                extra = more * took / taken; mass += more
            }
            if (extra > 0f) {
                took += extra
                out.busy = extra * LEACH_DAYS_PER_KG / hands
                band.practise(Skill.LEACHING)
                stats.tally(Tally.of(s, Tally.OfKind.PLANTS_LEACHED), extra.toInt())
            }
        }
    }
    if (band != null) wornTook(g, wornKind(t, kind), p, mass)
    out.kg = took; out.mass = mass
}

/** The kind of [Food.FISH] offer that is shellfish gathered off the shore (0 is fish caught: see [offerFoods]). */
internal const val SHELLFISH_KIND = 1

/** The kind of [Food.FISH] offer that is fish taken from the traps of a weir ([Built.WEIR]). */
internal const val WEIR_KIND = 2

/** The kind of [Food.FISH] offer that is the spring run's fish taken from the bank ([HexWater.runWithinReach]). */
internal const val RUN_KIND = 3

/**
 * The kind of a band's [Food.ROOTS] offer that is the fallen mast (0 is bulbs and roots dug: see [offerFoods]), picked up as
 * fruit is picked ([BAND_FRUIT_DAYS_PER_KG]): pine nuts in the Great Basin gave some 840-1,400 kcal/h (Simms 1987, from memory).
 */
internal const val MAST_KIND = 1
/** What a kg of mast feeds against a kg of roots, whose [Food] it is offered as ([Nourishment.MAST]). */
internal const val MAST_RICH = Nourishment.MAST / Nourishment.ROOTS
/**
 * Whether [g] may reap the sown field at [p]: a field is held by the band that last sowed it ([HexFood.fieldHolder]) and the
 * bands housed in its hex, its village; others leave it standing (taking it is a raid's). Animals eat it where they find it.
 * (Possession, not ownership: the hold goes with the next sowing, and a field unsown has no crop to hold.)
 */
internal fun GroupWorld.mayReap(g: HexGroup, p: Place): Boolean {
    val band = g.band ?: return true
    val holder = fauna.food.fieldHolder[p]
    return holder < 0 || holder == g.id || band.houseHex == hw.placeHex[p]
}

/** The kind of [Food.GRAIN] offered for a sown field's grain (0 the wild stand's). */
internal const val FIELD_KIND = 1

/** What a kilo of reaped seed or grain feeds against a kilo of other food: raw, little; ground and baked, fully. */
internal fun seedValue(band: Band?) = RAW_SEED + (1f - RAW_SEED) * (band?.has(Tool.GRINDER) ?: 0f)

internal const val RAW_SEED = 0.6f

/**
 * How fast a party's search comes on carrion as it lies at place [p], a day: [CARRION_FIND_RATE] for a scavenger by trade that
 * searches all of the place in a tick, less as it finds less ([CARRION_FOUND] of its kind) or searches less of it.
 */
private fun HexGroups.carrionRate(w: ForageWeek, p: Place): Float {
    val area = land.areaKm2[p]
    return CARRION_FIND_RATE * min(1f, w.d.carrion / SCAVENGER_CARRION) * min(1f, w.sweep / area * small.tickDays / days)
}

/** The share of the carrion the groups found as it lay at [p] in this step that the party's own search found: its rate of all of theirs. */
private fun HexGroups.foundShare(w: ForageWeek, p: Place): Float {
    val all = small.groupCarrionRateLast[p]
    return if (all > 0f) min(1f, carrionRate(w, p) / all) else 0f
}
