package inflation.ecology

import inflation.ecology.HexSmallLife.DeadPool
import inflation.EnumFloats
import inflation.Kg
import inflation.Part
import inflation.world.Hex
import inflation.world.Weather
import inflation.world.get
import inflation.world.set
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.max
import kotlin.math.min

// Food kept: what spoils of a band's stores, what drying and salting keep, and the food it works to lay by.

/**
 * [forage]'s end of a band's week with its food: what is left of the week's [got] (kg by [Store]) is kept, its hands
 * dry fresh meat and fish and keep the small game's skins in the days its week gives them ([planWeek]), and the stores
 * spoil, what rots going to carrion where the band is.
 */
internal fun HexGroups.keepAndSpoil(w: ForageWeek, got: EnumFloats<Store>, gotKg: EnumFloats<Store>, pelts: Float) {
    val g = w.g; val band = checkNotNull(w.band); val weather = w.weather
    val time = checkNotNull(w.time)
    // (What is left of its kills' fat keeps with the fresh meat, as the carcass it came on: only rendered grease keeps as
    // grease. Of the grease left, the kills' fat is its share of the week's.)
    val killFatFed = w.killFat * w.fatFeeds
    if (killFatFed > 0f && got[Store.GREASE] > 0f) {
        val share = killFatFed / (killFatFed + w.greaseFed); val fed = got[Store.GREASE] * share; val kg = gotKg[Store.GREASE] * share
        got[Store.GREASE] -= fed; gotKg[Store.GREASE] -= kg; got[Store.FRESH] += fed; gotKg[Store.FRESH] += kg
    }
    // What is left is kept; spare hands dry fresh meat and fish over the fire, as fast as their skill allows.
    val unkept = g.larder; val unkeptStores = storesNow(g)
    for (st in Store.entries) addFood(g.stores, g.storeKgPer, st, got[st], gotKg[st])
    band.larderMoved(g, LarderMove.KEPT, g.larder - unkept)
    band.storeMoved(g, StoreFlow.KEPT, unkeptStores)
    // (Racks in the sun dry little in wet weather; smoking over a slow fire works in any.)
    val sun = band.skill(Skill.STORE) / (1f + weather.tickPrecipitation(g.place) / DRY_RAIN_MM)
    val smoke = if (band.knowsSkill[Skill.SMOKING] && band.fire > 0f) band.skill(Skill.SMOKING) * min(1f, band.fire) else 0f
    // (A band that knows salting and has salt salts what it cures: salt cures in any weather.)
    val salt = if (band.knowsSkill[Skill.SALTING]) band.goods[Material.SALT] else 0f
    val salting = if (salt > 0f) band.skill(Skill.SALTING) else 0f
    val dryRate = max(max(sun, smoke), salting)
    // (As much of it as the band chooses to, in the days its week gives drying. Fresh food is kept at what it feeds;
    // drying takes its time by what it weighs. What it would have liked to dry is what drying asks of next week's plan.)
    val freshKgPer = g.storeKgPer[Store.FRESH].coerceAtLeast(1e-6f)
    // (Fresh meat will not wait: drying takes days the chores after it would have had, but not those held first for warmth and food.)
    val dryDays = time.leftBeforeLater(Activity.DRYING)
    // (All of it from the scaffold's 1 up; above 1 more of the same: dried for longer, a kilo taking that much more of its
    // days and keeping that much better ([driedKeep]).)
    val dryShare = band.dryWill.coerceIn(0f, 1f)
    val depth = max(1f, band.dryWill)
    val daysPerKg = DRY_DAYS_PER_KG * depth
    val dried = min(g.stores[Store.FRESH] * dryShare, dryDays * dryRate / daysPerKg / freshKgPer)
    band.driedLast = dried
    dialUse?.amount(DialSite.DRYING, band.dryWill, dried, min(g.stores[Store.FRESH], dryDays * dryRate / DRY_DAYS_PER_KG / freshKgPer))
    band.dryAsk = if (dryRate > 0f) g.stores[Store.FRESH] * dryShare * freshKgPer * daysPerKg / dryRate else 0f
    // (Report only: what held its drying back, the way or the days ([Tally.Drying]).)
    if (g.stores[Store.FRESH] > 0f) {
        val perDay = (g.dailyNeedKg / g.warmth.coerceAtLeast(1e-3f)).coerceAtLeast(1e-3f) / g.size.coerceAtLeast(1)
        val fresh = g.stores[Store.FRESH]; val byDays = dryDays * dryRate / daysPerKg / freshKgPer
        fun t(k: Tally.Drying, v: Float) = stats.tally(Tally.drying(k), v.roundToInt())
        t(Tally.Drying.FRESH, fresh / perDay); t(Tally.Drying.BY_WAY, fresh * dryShare / perDay); t(Tally.Drying.BY_DAYS, min(fresh, byDays) / perDay)
        t(Tally.Drying.DRIED, dried / perDay); t(Tally.Drying.WAY, 10f * band.dryWill * fresh / perDay); t(Tally.Drying.RATE, 10f * dryRate * fresh / perDay)
        t(Tally.Drying.WEEKS, 1f); if (byDays < fresh * dryShare) t(Tally.Drying.DAYS_BOUND, 1f)
    }
    if (dried > 0f) {
        val undried = storesNow(g)
        val saltedNow = min(dried, salt / SALT_PER_KG); band.goods[Material.SALT] -= saltedNow * SALT_PER_KG; band.noteSalt(SaltFlow.CURED, saltedNow * SALT_PER_KG)
        val before = g.stores[Store.DRIED]; band.salted = (before * band.salted + saltedNow) / (before + dried)
        band.dryDepth = (before * band.dryDepth + dried * depth) / (before + dried)
        g.stores[Store.FRESH] -= dried; addFood(g.stores, g.storeKgPer, Store.DRIED, dried, dried * g.storeKgPer[Store.FRESH]); band.practise(if (salting >= max(sun, smoke)) Skill.SALTING else if (smoke > sun) Skill.SMOKING else Skill.STORE)
        band.storeMoved(g, StoreFlow.DRIED, undried)
    }
    time.spendBeforeLater(Activity.DRYING, if (dried > 0f) dried * freshKgPer * daysPerKg / dryRate else 0f)
    time.release(Activity.DRYING)
    band.skinAsk = SKIN_DAYS * pelts
    val unskinned = skinSmallGame(band, time, pelts)
    time.release(Activity.SKINS)
    // What time is left is for making and mending (see [bandWeek]).
    band.spareDays = max(0f, time.left)
    band.week = time
    if (stats.census) time.count(stats.hunting, Tally.TimeTotal.ALL, days * w.hands.toFloat())
    // And it spoils, each kind at its own rate, fresh meat the faster the warmer.
    // (In storage pits at home stores keep better, though mice get into the grain, nuts and roots.)
    val home = if (band.atHome) band.house else 0f
    val kept = keepWorth(g, band, home)
    band.houseFoodWeek += kept; band.houseWeek[HouseWorth.KEPT_KG] += kept
    // (Fresh meat beyond what it can eat before it rots it sets before its neighbours, by its custom of feasting: it rots, what
    // the feast leaves, once they have eaten ([feastOnKills]).)
    band.feastMeat = if (band.byCustom(band.holds(Tradition.FEASTS), band.feastWill) > 0f)
        max(0f, g.stores[Store.FRESH] - killKeeps(band, g.dailyNeedKg * days, weather.temperature[g.place]) + band.driedLast) else 0f
    g.stores[Store.FRESH] -= band.feastMeat
    // (Pits sealed with clay and plaster keep the mice out.)
    val unspoilt = g.larder; val unspoiltStores = storesNow(g)
    var rotten = spoil(g.stores, weather.temperature[g.place], 1f - PIT_KEEP * home, driedKeep(band), g.storeKgPer)
    val sealed = band.sealedLetsThrough()
    if (home > 0f) {
        for (st in listOf(Store.FRUIT, Store.ROOTS)) {
            val x = g.stores[st] * overWeeks(MICE * sealed * home, days / 7f)
            g.stores[st] -= x
            rotten += x * g.storeKgPer[st]
        }
        if (band.knowsSkill[Skill.SEALING] && g.larder > 0f) band.practise(Skill.SEALING, 0.2f * PRACTICE)
    }
    // (Mice and rats get into the grain and nuts wherever they are kept: storage pits at home keep them out the better, sealed
    // the better still, [seedRodents].)
    val gnawed = g.stores[Store.SEED] * overWeeks(SEED_RODENTS * seedRodents(home, sealed), days / 7f)
    g.stores[Store.SEED] -= gnawed; rotten += gnawed * g.storeKgPer[Store.SEED]
    rotten += infest(g, band, weather.temperature[g.place], 1f - home * (1f - sealed))
    band.larderMoved(g, LarderMove.SPOILT, g.larder - unspoilt)
    band.storeMoved(g, StoreFlow.SPOILT, unspoiltStores)
    g.stores[Store.FRESH] += band.feastMeat
    small.deposit(DeadPool.CARRION, g.place, Kg(rotten + unskinned))
}

/**
 * Feasts on kills: a band holding more fresh meat than it can eat before it rots ([Band.feastMeat]) sets it before the bands
 * within a day or two's walk ([VISIT_RINGS]), the nearest first, as much as [FEAST_KG] a guest by its will to feast; the guests
 * take it home to eat first ([PERISHABLE]) and remember it, as at a feast at a meeting. What is left rots as fresh meat does
 * ([spoil]). (A big kill is shared out across camps: Hadza meat goes to every household in camp and beyond.)
 */
@Part("people: feasting on kills")
internal fun HexGroups.feastOnKills(weather: Weather) {
    val hosts = groups.filter { g -> g.alive && (g.band?.feastMeat ?: 0f) > 0f }
    if (hosts.isEmpty()) return
    val byHex = HashMap<Hex, ArrayList<HexGroup>>()
    for (g in groups) if (g.alive && g.band != null) byHex.getOrPut(hw.placeHex[g.place]) { ArrayList() } += g
    for (host in hosts) {
        val hb = host.band ?: continue
        val keen = hb.byCustom(hb.holds(Tradition.FEASTS), hb.feastWill)
        var left = min(hb.feastMeat, host.stores[Store.FRESH])
        val h0 = hw.placeHex[host.place]
        var ring = listOf(h0); val seen = hashSetOf(h0)
        for (r in 0..VISIT_RINGS) {
            for (h in ring) for (guest in byHex[h].orEmpty()) if (guest !== host && left > 0f) {
                // (From another hex the guests' [VISITORS] come and carry home what they can, [MEAT_LOAD_KG] each, and give
                // the days of the walk ([visitDays]). Olli, 13 Nov 2026: no free movement.)
                val apart = r > 0
                val meal = minOf(left, FEAST_KG * guest.size * keen, if (apart) VISITORS * MEAT_LOAD_KG else Float.POSITIVE_INFINITY)
                if (meal <= 0f) continue
                val walk = if (apart) tripKm(guest, h, h0, weather) ?: continue else 0f
                if (apart) guest.band?.let { it.raidWalkOwed += visitDays(VISITORS, walk.toDouble()) }
                host.stores[Store.FRESH] -= meal; addFood(guest.stores, guest.storeKgPer, Store.FRESH, meal, meal * host.storeKgPer[Store.FRESH])
                hb.larderMoved(host, LarderMove.FEAST, -meal); guest.band?.larderMoved(guest, LarderMove.FEAST, meal)
                left -= meal
                guest.band?.befriend(host.id, TIE_FEAST); hb.befriend(guest.id, 0.5f * TIE_FEAST)
                stats.meeting(Met.KILL_FEASTS)
            }
            if (r == VISIT_RINGS || left <= 0f) break
            ring = hex.nextRing(ring, seen)
        }
        // (What none ate rots, as it would have in its stores.)
        val rot = min(left, host.stores[Store.FRESH]) * (1f - exp(-HexSmallLife.CARRION_ROT * spoilWarmth(weather.temperature[host.place]) * days))
        host.stores[Store.FRESH] -= rot
        hb.larderMoved(host, LarderMove.SPOILT, -rot); hb.storeFlow[Store.FRESH.ordinal * StoreFlow.entries.size + StoreFlow.SPOILT.ordinal] -= rot
        small.deposit(DeadPool.CARRION, host.place, Kg(rot * host.storeKgPer[Store.FRESH]))
        hb.feastMeat = 0f
    }
}

/**
 * How much of the usual loss band [band]'s dried meat and fish suffer: smoked keeps longer than sun-dried, salted longer still,
 * and dried for longer ([Band.dryDepth]) longer in proportion (drier meat keeps longer; the proportion is an estimate).
 */
internal fun GroupWorld.driedKeep(band: Band) = curedKeep(band) / max(1f, band.dryDepth)

/**
 * How much of what spoils band [band]'s dried meat and fish, and what insects eat of it, its curing lets through: smoke
 * and salt keep both off. (Drying longer keeps it from going off ([driedKeep]) but not from the beetles, which eat it dry.)
 */
internal fun GroupWorld.curedKeep(band: Band): Float {
    val smoked = if (band.knowsSkill[Skill.SMOKING]) 1f - SMOKE_KEEP * band.skill(Skill.SMOKING) else 1f
    return smoked * (1f - tuning.saltKeep * band.salted)
}

/**
 * A week of insects in band [band]'s stores at [t] °C ([Band.pests]: how far they are infested, 0 to 1); returns the kg they
 * ate. They come in from around ([PEST_ARRIVE]) and breed in what they find ([PEST_GROWTH]), both the faster the warmer, and
 * not at all at [PEST_MIN_T] or below; frost kills them off ([PEST_FROST]); a store gone empty starves them out. The longer
 * a store is kept through warm weather, the worse it is infested. Of each kind they eat [Store.pestWeekly] as far as the store
 * is infested, the warmer the more: of dried meat and fish what its curing lets through ([curedKeep]), of seed what its pits
 * let through ([seedKeep], sealed pits at home keeping them out).
 */
internal fun GroupWorld.infest(g: HexGroup, band: Band, t: Float, seedKeep: Float): Float {
    if (g.stores[Store.DRIED] + g.stores[Store.SEED] <= 0f) { band.pests = 0f; return 0f }
    val week = days / 7f
    if (t <= 0f) { band.pests *= exp(-PEST_FROST * week); return 0f }
    if (t <= PEST_MIN_T) return 0f
    val warm = min(2.8f, exp(0.069f * (t - 15f)))
    // (Over the step in closed form: dp/dt = (1 - p)(ARRIVE + GROWTH p) a week at this warmth, [pestsOver]; they eat as the
    // mean of p over the step, each store compounding.)
    val (pEnd, pMean) = pestsOver(band.pests, warm * week)
    band.pests = pEnd
    var eaten = 0f
    for (st in Store.entries) if (st.pestWeekly > 0f) {
        val keep = when (st) { Store.DRIED -> curedKeep(band); Store.SEED -> seedKeep; else -> 1f }
        val x = g.stores[st] * (1f - exp(-st.pestWeekly * keep * pMean * warm * week))
        g.stores[st] -= x; eaten += x * g.storeKgPer[st]
    }
    return eaten
}

/**
 * Insects in stores ([infest]): the share of a clean store a week's warmth at 15 °C brings in from around; how fast they
 * breed there, a week at 15 °C (doubling in about a month at 25 °C); below this °C they neither breed nor eat; the share a
 * week of frost kills. (Rates from memory: most stored-food beetles and moths develop only above 10-15 °C, and freezing
 * kills them.)
 */
internal const val PEST_ARRIVE = 0.005f

/**
 * Pests [p0] after [weeks] weeks at 15 °C (warmth already in it), by dp/dt = (1 - p)(A + G p) with A = [PEST_ARRIVE], G =
 * [PEST_GROWTH]: Q = (A + G p) / (1 - p) grows as exp((A + G) t), p = (Q - A) / (Q + G). Returns the end and the mean over
 * the weeks.
 */
internal fun pestsOver(p0: Float, weeks: Float): Pair<Float, Float> {
    if (weeks <= 0f) return p0 to p0
    if (p0 >= 1f) return 1f to 1f
    val a = PEST_ARRIVE.toDouble(); val gr = PEST_GROWTH.toDouble(); val k = a + gr; val w = weeks.toDouble()
    val q0 = (a + gr * p0) / (1.0 - p0)
    val q1 = q0 * exp(k * w)
    val end = ((q1 - a) / (q1 + gr)).coerceIn(0.0, 1.0)
    // (Mean of 1 - (A + G) / (Q + G) over the weeks: 1 - (k / G) (1 - ln((q1 + G) / (q0 + G)) / (k w)).)
    val mean = (1.0 - (k / gr) * (1.0 - ln((q1 + gr) / (q0 + gr)) / (k * w))).coerceIn(0.0, 1.0)
    return end.toFloat() to mean.toFloat()
}

/** The share lost over [weeks] weeks at [weekly] a week, compounding: 1 - (1 - weekly)^weeks. */
internal fun overWeeks(weekly: Float, weeks: Float): Float = 1f - (1f - weekly.coerceIn(0f, 1f)).pow(weeks)
internal const val PEST_GROWTH = 0.1f
internal const val PEST_MIN_T = 12f
internal const val PEST_FROST = 0.1f

/**
 * The food a whole house would have kept from spoiling in [g]'s stores this week against the houses it has ([home]), less what
 * mice would have taken from its pits: kg of need, by [spoil]'s rates and the mice's. For [Band.houseFood].
 */
internal fun GroupWorld.keepWorth(g: HexGroup, b: Band, home: Float): Float {
    val more = 1f - home
    if (more <= 0f) return 0f
    var kept = 0f
    val weeks = days / 7f
    for (st in Store.entries) if (st != Store.FRESH) {
        val rate = st.lostWeekly * (if (st == Store.DRIED) driedKeep(b) else 1f)
        kept += g.stores[st] * (overWeeks(rate * (1f - PIT_KEEP * home), weeks) - overWeeks(rate * (1f - PIT_KEEP), weeks))
    }
    val sealed = b.sealedLetsThrough()
    for (st in listOf(Store.FRUIT, Store.ROOTS)) kept -= g.stores[st] * (overWeeks(MICE * sealed, weeks) - overWeeks(MICE * sealed * home, weeks))
    // (And the grain and nuts the mice would not get at in its pits: [seedRodents] at a whole house against the houses it has.)
    kept += g.stores[Store.SEED] * (overWeeks(SEED_RODENTS * seedRodents(home, sealed), weeks) - overWeeks(SEED_RODENTS * seedRodents(1f, sealed), weeks))
    return kept
}

/** How much faster than at 15 °C stores spoil at [t] °C: not at all at or below freezing ([spoil]). */
internal fun spoilWarmth(t: Float) = if (t <= 0f) 0f else min(2.8f, exp(0.069f * (t - 15f)))

/**
 * What of a kill band [band]'s hunters reckon it can use, kg of its need, against [wantAll], its week's want, at [t] °C: what
 * it eats before the fresh meat rots, its want as far as the meat keeps through the week (it keeps on average 1 / its rate a day
 * of rotting, [HexSmallLife.CARRION_ROT], against the week's [days]), gorging included ([SURPLUS_EAT]), and what it dries, as
 * much as it dried last week ([Band.driedLast]). The rest would rot: a band does not count on it.
 */
internal fun GroupWorld.killKeeps(band: Band, wantAll: Float, t: Float): Float {
    val rot = HexSmallLife.CARRION_ROT * spoilWarmth(t)
    val keeps = if (rot <= 0f) 1f else min(1f, 1f / (rot * days))
    return (1f + SURPLUS_EAT) * wantAll * keeps + band.driedLast
}

/** The share of the mice its pits let through that [this] band's sealing of them with clay and plaster still lets in ([SEAL_KEEP]). */
internal fun Band.sealedLetsThrough() = if (knowsSkill[Skill.SEALING]) 1f - SEAL_KEEP * skill(Skill.SEALING) else 1f

/**
 * The share of [SEED_RODENTS] that gets into a band's grain and nuts kept in [home] of its houses' storage pits (the rest
 * kept in bags and baskets about the camp), the pits [sealed] as its sealing lets through: pits keep out [PIT_KEEP] of them.
 */
internal fun seedRodents(home: Float, sealed: Float) = (1f - home) + home * (1f - PIT_KEEP) * sealed

/**
 * Share of a band's grain and nuts that mice and rats take a week where nothing keeps them out ([seedRodents]): some 10% a
 * year. An estimate, from memory: losses in traditional grain stores run some 5-15% a year all told, rodents and insects
 * together ([Store.pestWeekly], [infest]), and sealed pits and bins keep most of both out.
 */
internal const val SEED_RODENTS = 0.002f

/** Spoils a week of [stores] at [t] °C, each kind at its own rate; returns kg lost (weighed at [kgPer] for each kg of worth). */
internal fun GroupWorld.spoil(stores: EnumFloats<Store>, t: Float, keep: Float = 1f, driedKeep: Float = 1f, kgPer: EnumFloats<Store>? = null): Float {
    var lost = 0f
    val warm = spoilWarmth(t)
    for (st in Store.entries) {
        val share = if (st == Store.FRESH) 1f - exp(-HexSmallLife.CARRION_ROT * warm * days) else
            overWeeks(st.lostWeekly * keep * (if (st == Store.DRIED) driedKeep else 1f), days / 7f)
        val x = stores[st] * share
        stores[st] -= x; lost += x * (kgPer?.get(st) ?: 1f)
    }
    return lost
}

/**
 * The food [band] works to lay by this week: while its kit is worn it holds back from storing as far as its way of
 * mending first goes ([Band.mendFirst]: the scaffold's 1 stores a tenth of it, mending in the time the rest would take).
 */
internal fun GroupWorld.storeWork(g: HexGroup, band: Band) = if (band.worn()) storeWant(g, band) * (1f - band.mendFirst) else storeWant(g, band)

/**
 * What band [g] works this week to lay by towards the stores it aims to keep (its choice), kg: a share
 * of what it lacks. (A band holding to demand sharing stores less: what it lays by is asked of it.)
 */
internal fun storeWant(g: HexGroup, band: Band) = storeWant(g, band, band.storeAim)

/**
 * [storeWant] were its store aim [aimDays]. Only what keeps counts towards the aim: the fresh meat and fruit it holds
 * ([perishablesHeld]) are next week's food, foraged for that much less ([GroupFood]), and would spoil before a lean month.
 * (Counted as stores as well, they both cut its foraging and stood in for its stores. Larders stood at about 40% of their
 * aim, lean663, 2026-11-06; how much of that this was is unmeasured.)
 */
private fun storeWant(g: HexGroup, band: Band, aimDays: Float) = max(0f, storeAimKg(g, band, aimDays) - (g.larder - perishablesHeld(g))) * STORE_FILL

/** The stores band [g] aims to keep, kg of its need: its store aim's days ([Band.storeAim]; less, holding to demand sharing). */
private fun storeAimKg(g: HexGroup, band: Band, aimDays: Float = band.storeAim) =
    aimDays * (if (band.holds(Tradition.SHARING)) SHARING_STORE else 1f) * g.dailyNeedKg

/**
 * What band [g] eats this week of the stores it holds beyond its aim, kg of its need, rather than forage for it: the same
 * share of the excess a week as it lays by of a shortfall ([STORE_FILL]). Food kept is food to eat: a band with more than it
 * means to keep lives on it and spends the days on other work. ([held]: what of its larder counts, all of it unless said.)
 */
internal fun storeSpare(g: HexGroup, band: Band, held: Float = g.larder) = max(0f, held - storeAimKg(g, band)) * STORE_FILL

/** What group [g] holds of the stores that spoil within days ([PERISHABLE]), kg of its need: eaten first, foraged for that much less. */
internal fun perishablesHeld(g: HexGroup) = PERISHABLE.sumOf { g.stores[it].toDouble() }.toFloat()

/** Counts in [u] how band [b]'s store aim and its mending first set the food [work] it works to lay by ([storeWork]). */
internal fun countStoreWork(u: DialUse, g: HexGroup, b: Band, work: Float) {
    val worn = b.worn()
    val mendAt1 = if (b.worn(MEND_BELOW)) 0f else storeWant(g, b)
    u.amount(DialSite.STORE_WANT, b.storeShare, work, if (worn) storeWant(g, b, b.storeAimAt1) * (1f - b.mendFirst) else storeWant(g, b, b.storeAimAt1))
    if (worn) u.amount(DialSite.MENDING_FIRST, b.will[BandWay.MENDING_FIRST], work, mendAt1)
}
