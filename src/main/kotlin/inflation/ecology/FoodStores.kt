package inflation.ecology

import inflation.ecology.HexSmallLife.DeadPool
import inflation.EnumFloats
import inflation.Kg
import inflation.world.get
import inflation.world.set
import kotlin.math.exp
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
    val unkept = g.larder
    for (st in Store.entries) addFood(g.stores, g.storeKgPer, st, got[st], gotKg[st])
    band.larderMoved(g, LarderMove.KEPT, g.larder - unkept)
    // (Racks in the sun dry little in wet weather; smoking over a slow fire works in any.)
    val sun = band.skill(Skill.STORE) / (1f + weather.precipitation[g.place] / DRY_RAIN_MM)
    val smoke = if (band.knowsSkill[Skill.SMOKING] && band.fire > 0f) band.skill(Skill.SMOKING) * min(1f, band.fire) else 0f
    // (A band that knows salting and has salt salts what it cures: salt cures in any weather.)
    val salt = if (band.knowsSkill[Skill.SALTING]) band.goods[Material.SALT] else 0f
    val salting = if (salt > 0f) band.skill(Skill.SALTING) else 0f
    val dryRate = max(max(sun, smoke), salting)
    // (As much of it as the band chooses to, in the days its week gives drying. Fresh food is kept at what it feeds;
    // drying takes its time by what it weighs. What it would have liked to dry is what drying asks of next week's plan.)
    val freshKgPer = g.storeKgPer[Store.FRESH].coerceAtLeast(1e-6f)
    val dryDays = time.leftFor(Activity.DRYING)
    // (All of it from the scaffold's 1 up; above 1 more of the same: dried for longer, a kilo taking that much more of its
    // days and keeping that much better ([driedKeep]).)
    val dryShare = band.dryWill.coerceIn(0f, 1f)
    val depth = max(1f, band.dryWill)
    val daysPerKg = DRY_DAYS_PER_KG * depth
    val dried = min(g.stores[Store.FRESH] * dryShare, dryDays * dryRate / daysPerKg / freshKgPer)
    dialUse?.amount(DialSite.DRYING, band.dryWill, dried, min(g.stores[Store.FRESH], dryDays * dryRate / DRY_DAYS_PER_KG / freshKgPer))
    band.dryAsk = if (dryRate > 0f) g.stores[Store.FRESH] * dryShare * freshKgPer * daysPerKg / dryRate else 0f
    if (dried > 0f) {
        val saltedNow = min(dried, salt / SALT_PER_KG); band.goods[Material.SALT] -= saltedNow * SALT_PER_KG
        val before = g.stores[Store.DRIED]; band.salted = (before * band.salted + saltedNow) / (before + dried)
        band.dryDepth = (before * band.dryDepth + dried * depth) / (before + dried)
        g.stores[Store.FRESH] -= dried; addFood(g.stores, g.storeKgPer, Store.DRIED, dried, dried * g.storeKgPer[Store.FRESH]); band.practise(if (salting >= max(sun, smoke)) Skill.SALTING else if (smoke > sun) Skill.SMOKING else Skill.STORE)
    }
    time.spendAtMost(Activity.DRYING, if (dried > 0f) dried * freshKgPer * daysPerKg / dryRate else 0f)
    time.release(Activity.DRYING)
    band.skinAsk = SKIN_DAYS * pelts
    val unskinned = skinSmallGame(band, time, pelts)
    time.release(Activity.SKINS)
    // What time is left is for making and mending (see [bandWeek]).
    band.spareDays = max(0f, time.left)
    band.week = time
    time.count(stats.hunting, Tally.TimeTotal.ALL, days * w.hands.toFloat())
    // And it spoils, each kind at its own rate, fresh meat the faster the warmer.
    // (In storage pits at home stores keep better, though mice get into the grain, nuts and roots.)
    val home = if (band.atHome) band.house else 0f
    val kept = keepWorth(g, band, home)
    band.houseFoodWeek += kept; band.houseWeek[HouseWorth.KEPT_KG] += kept
    // (Pits sealed with clay and plaster keep the mice out.)
    val unspoilt = g.larder
    var rotten = spoil(g.stores, weather.temperature[g.place], 1f - PIT_KEEP * home, driedKeep(band), g.storeKgPer)
    val sealed = if (band.knowsSkill[Skill.SEALING]) 1f - SEAL_KEEP * band.skill(Skill.SEALING) else 1f
    if (home > 0f) {
        for (st in listOf(Store.SEED, Store.FRUIT, Store.ROOTS)) {
            val x = g.stores[st] * MICE * sealed * home * days / 7f
            g.stores[st] -= x
            rotten += x * g.storeKgPer[st]
        }
        if (band.knowsSkill[Skill.SEALING] && g.larder > 0f) band.practise(Skill.SEALING, 0.2f * PRACTICE)
    }
    rotten += infest(g, band, weather.temperature[g.place], 1f - home * (1f - sealed))
    band.larderMoved(g, LarderMove.SPOILT, g.larder - unspoilt)
    small.deposit(DeadPool.CARRION, g.place, Kg(rotten + unskinned))
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
    val p = band.pests
    band.pests = min(1f, p + (PEST_GROWTH * p * (1f - p) + PEST_ARRIVE * (1f - p)) * warm * week)
    var eaten = 0f
    for (st in Store.entries) if (st.pestWeekly > 0f) {
        val keep = when (st) { Store.DRIED -> curedKeep(band); Store.SEED -> seedKeep; else -> 1f }
        val x = g.stores[st] * min(1f, st.pestWeekly * keep * band.pests * warm * week)
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
    for (st in Store.entries) if (st != Store.FRESH)
        kept += g.stores[st] * min(1f, st.lostWeekly * (if (st == Store.DRIED) driedKeep(b) else 1f) * days / 7f) * PIT_KEEP * more
    val sealed = if (b.knowsSkill[Skill.SEALING]) 1f - SEAL_KEEP * b.skill(Skill.SEALING) else 1f
    for (st in listOf(Store.SEED, Store.FRUIT, Store.ROOTS)) kept -= g.stores[st] * MICE * sealed * more * days / 7f
    return kept
}

/** Spoils a week of [stores] at [t] °C, each kind at its own rate; returns kg lost (weighed at [kgPer] for each kg of worth). */
internal fun GroupWorld.spoil(stores: EnumFloats<Store>, t: Float, keep: Float = 1f, driedKeep: Float = 1f, kgPer: EnumFloats<Store>? = null): Float {
    var lost = 0f
    val warm = if (t <= 0f) 0f else min(2.8f, exp(0.069f * (t - 15f)))
    for (st in Store.entries) {
        val share = if (st == Store.FRESH) 1f - exp(-HexSmallLife.CARRION_ROT * warm * days) else min(1f,
            st.lostWeekly * keep * (if (st == Store.DRIED) driedKeep else 1f) * days / 7f)
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

/** [storeWant] were its store aim [aimDays]. */
private fun storeWant(g: HexGroup, band: Band, aimDays: Float) =
    max(0f, aimDays * (if (band.holds(Tradition.SHARING)) SHARING_STORE else 1f) * g.dailyNeedKg - g.larder) * STORE_FILL

/** Counts in [u] how band [b]'s store aim and its mending first set the food [work] it works to lay by ([storeWork]). */
internal fun countStoreWork(u: DialUse, g: HexGroup, b: Band, work: Float) {
    val worn = b.worn()
    val mendAt1 = if (b.worn(MEND_BELOW)) 0f else storeWant(g, b)
    u.amount(DialSite.STORE_WANT, b.storeShare, work, if (worn) storeWant(g, b, b.storeAimAt1) * (1f - b.mendFirst) else storeWant(g, b, b.storeAimAt1))
    if (worn) u.amount(DialSite.MENDING_FIRST, b.will[BandWay.MENDING_FIRST], work, mendAt1)
}
