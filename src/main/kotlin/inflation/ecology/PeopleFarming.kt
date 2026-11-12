package inflation.ecology

import inflation.Part
import inflation.world.Place
import inflation.world.Hex
import inflation.world.get
import inflation.world.set
import inflation.ecology.HexSmallLife.DeadPool
import inflation.Kg
import inflation.KgM2
import inflation.min
import inflation.monthsFrom
import inflation.world.Date
import inflation.world.LifeForm
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.min

// Bands' fields and animals: sowing and reaping, kept animals, dogs and herds.

/**
 * In autumn [g]'s band, at home or at a camp it has kept long enough to build at ([houseAfterWeeks]), sows a share of its seed
 * corn there ([Band.seedCorn]: grain of its own crops, kept back, not grain gathered from the wild stands), in the [time] days
 * to spare, if it knows how to grow grain ([Skill.CULTIVATION]). (A band that then moves on leaves the plot to whoever comes by.)
 */
@Part("people: sow grain")
internal fun HexGroups.sowGrain(g: HexGroup, b: Band, p: Place, date: Date, time: BandTime) {
    val left = time.leftFor(Activity.SOWING)
    if (canSow(g, b, p, date) && left > 0f) {
        val corn = b.seedCornIn(g)
        val per = g.storeKgPer[Store.SEED]; val canIn = sowableKg(p, left / b.lapse(Skill.CULTIVATION)) / per
        val want = minOf(sowLeft(g, b, p, per, b.sowWill), corn, canIn)
        dialUse?.amount(DialSite.SOWING, b.sowWill, want, minOf(sowLeft(g, b, p, per, 1f), corn, canIn))
        val (sow, pulse) = sowMix(b, p, corn, want, per)
        if (sow > 0.1f) {
            val domestic = b.seedDomestic
            val kg = sow * per
            g.stores[Store.SEED] -= sow; b.seedCorn = corn - sow; time.spend(Activity.SOWING, sowDays(p, kg) * b.lapse(Skill.CULTIVATION))
            b.seedPulse = if (b.seedCorn > 0f) ((corn * b.seedPulse - pulse) / b.seedCorn).coerceIn(0f, 1f) else 0f
            b.practise(Skill.CULTIVATION)
            // (Ground it sows beyond what is cleared there it clears first: [HexFood.cleared], kept by [HexFood.sow].)
            // (A field sown afresh, or sown where last year's crop is spent, is this year's crop.)
            if (b.sownPlace != p || fauna.food.field[p] <= 0f) b.sownKg = 0f
            b.sownPlace = p; b.sownKg += kg; b.seedKept = b.sownKg; b.sinceSowed = 0f
            fauna.food.sow(p, land.spread(p, Kg(kg)), domestic, pulse / sow, g.id)
            stats.meeting(Met.BANDS_SOWED)
            if (pulse > 0f) stats.meeting(Met.BANDS_SOWED_PULSES)
            note(g) { "sowed %.0f kg of grain at home".format(sow) }
        }
    }
    tallySowing(g, b, p, date)
}

/** Whether [g]'s band [b] is one the sowing census counts at [p] now: one that grows grain, at home or long at its camp, in its sowing window, where a crop can grow. */
private fun HexGroups.inSowingWindow(g: HexGroup, b: Band, p: Place, date: Date) =
    b.knowsSkill[Skill.CULTIVATION] && fauna.food.canCrop(p) && (b.atHome || b.campWeeks >= houseAfterWeeks(b)) &&
        date.month in (if (fauna.food.springSown(p)) SPRING_SOW_MONTHS else SOW_MONTHS) && sowTarget(g, b, p, 1f) > 0f

/** Where the days of [g]'s band [b] went in its sowing window ([inSowingWindow]), tenths of person-days by work ([Tally.sowingTime]). Report only. */
internal fun HexGroups.tallySowingTime(g: HexGroup, b: Band, p: Place, date: Date, time: BandTime) {
    if (!stats.census || !inSowingWindow(g, b, p, date)) return
    stats.tally(Tally.sowingTime(null), 1)
    for (a in Activity.entries) { val d = (10f * time.spentOn(a)).roundToInt(); if (d > 0) stats.tally(Tally.sowingTime(a), d) }
    stats.tally(Tally.sowingLost, (10f * time.lost).roundToInt())
}

/** In its sowing window's last month, what of its year's target [g]'s band [b] has sown at [p] and what held the rest back ([Tally.Sowing]). Report only. */
private fun HexGroups.tallySowing(g: HexGroup, b: Band, p: Place, date: Date) {
    val food = fauna.food
    if (!stats.census || !b.knowsSkill[Skill.CULTIVATION] || !food.canCrop(p) || !(b.atHome || b.campWeeks >= houseAfterWeeks(b))) return
    if (date.month != (if (food.springSown(p)) SPRING_SOW_MONTHS else SOW_MONTHS).last) return
    val per = g.storeKgPer[Store.SEED]
    val full = sowTarget(g, b, p, 1f) * per
    if (full <= 0f) return
    val sown = min(full, if (b.sownPlace == p && food.field[p] > 0f) b.sownKg else 0f)
    val ways = max(0f, full - max(sown, sowTarget(g, b, p, b.sowWill) * per))
    val unsown = max(0f, full - sown - ways)
    val seed = min(unsown, max(0f, unsown - b.seedCornIn(g) * per))
    stats.tally(Tally.sowing(Tally.Sowing.WEEKS), 1)
    stats.tally(Tally.sowing(Tally.Sowing.SOWN), sown.roundToInt())
    stats.tally(Tally.sowing(Tally.Sowing.WAYS), ways.roundToInt())
    stats.tally(Tally.sowing(Tally.Sowing.SEED), seed.roundToInt())
    stats.tally(Tally.sowing(Tally.Sowing.DAYS), (unsown - seed).roundToInt())
}

/**
 * The seed band [b] (group [g]) sows a year at [p], in its stores' units: what ripens to [FARM_NEED_SHARE] of its people's
 * year's food (as far as its ways have it, [will]: [Band.sowWill]), at what a kilo sown there bears ([HexFood.yieldPerKg] of
 * its seed's strain, in its season, [HexFood.seasonYield], on ground as worn as it has seen it) and feeds ([seedValue], cooked). Farmers sow for the year they
 * see coming, not what seed happens to be left over; nothing where it cannot farm.
 */
internal fun GroupWorld.sowTarget(g: HexGroup, b: Band, p: Place, will: Float = b.sowWill): Float {
    if (!b.knowsSkill[Skill.CULTIVATION] || !fauna.food.canCrop(p)) return 0f
    // (On ground it has cropped before it reckons with the wear it has seen there: [HexFood.fieldSoil].)
    val food = fauna.food
    val soil = if (food.fieldWorn[p] > 0f) food.fieldSoil[p] else 1f
    val perUnit = g.storeKgPer[Store.SEED] * HexFood.yieldPerKg(b.seedDomestic) * food.seasonYield(p) * soil * seedValue(b) * b.cookValue(Food.GRAIN)
    return if (perUnit > 0f) FARM_NEED_SHARE * will * g.dailyNeedKg * Date.DAYS_PER_YEAR / perUnit else 0f
}

/** Of [g]'s band [b]'s year's sowing at [p] at [will] ([sowTarget], units of [per] kg), what is still to sow: less what it has sown there this season. */
internal fun GroupWorld.sowLeft(g: HexGroup, b: Band, p: Place, per: Float, will: Float): Float {
    val sown = if (b.sownPlace == p && fauna.food.field[p] > 0f) b.sownKg / per else 0f
    return max(0f, sowTarget(g, b, p, will) - sown)
}

/**
 * Of [want] of its seed corn [corn] (store units, [per] kg each) that band [b] sows at [p], what it sows and of that its pulses
 * ([Band.seedPulse]): pulses on the place's worn ground not yet under pulses ([HexFood.fieldWorn], where they mend it), cereal on
 * the rest, and pulses beyond the worn ground only where it has no cereal left to sow. (A rule of thumb, not a choice its ways weigh.)
 */
internal fun GroupWorld.sowMix(b: Band, p: Place, corn: Float, want: Float, per: Float): Pair<Float, Float> {
    val food = fauna.food
    val pulses = corn * b.seedPulse; val cereal = corn - pulses
    val wornRoom = max(0f, food.fieldWorn[p] * HexFood.SEED_RATE - food.field[p] * food.fieldPulse[p]) * land.m2(p) / per
    val onWorn = minOf(want, pulses, wornRoom)
    val cerealSown = min(want - onWorn, cereal)
    val pulse = onWorn + min(want - onWorn - cerealSown, pulses - onWorn)
    return (cerealSown + pulse) to pulse
}

/**
 * The ripe grain of the crop [g]'s band [b] sowed ([Band.sownPlace]), kg, where the band is in that hex and the field has
 * ripened ([HexFood.fieldRipening]): its share of what stands there, by the seed it sowed against all sown there (all of it once the year's sowing is struck off).
 */
internal fun GroupWorld.cropRipe(g: HexGroup, b: Band): Float {
    val f = b.sownPlace
    if (f.none || b.sownKg <= 0f || hw.placeHex[f.i] != hw.placeHex[g.place.i]) return 0f
    val food = fauna.food
    if (food.fieldRipening(f)) return 0f
    val share = if (food.field[f] > 0f) min(1f, b.sownKg / (food.field[f] * land.m2(f))) else 1f
    return food.fieldGrain[f] * land.m2(f) * share
}

/**
 * [g]'s band [b] brings in its ripe crop ([cropRipe]) in the [time] days to spare, as seed corn: a crop is reaped whole
 * when it is ripe, not a week's meals at a time.
 */
@Part("people: harvest crop")
internal fun HexGroups.harvestCrop(g: HexGroup, b: Band, time: BandTime) {
    val ripe = cropRipe(g, b); val left = time.leftFor(Activity.SOWING)
    if (ripe <= 0f || left <= 0f) return
    val perKg = cropReapDays(b)
    val f = b.sownPlace; val food = fauna.food
    val kg = land.total(f, food.reapField(f, land.spread(f, Kg(min(ripe, left / perKg))))).v
    if (kg <= 0f) return
    // (It is ground as it is eaten, through the year, not as it comes in: [Band.grindOwed].)
    b.seedKept -= min(kg, b.seedKept)
    time.spend(Activity.SOWING, kg * perKg)
    val worth = kg * seedValue(b) * b.cookValue(Food.GRAIN)
    val corn = b.seedCorn
    b.seedDomestic = (b.seedDomestic * corn + b.reapedDomestic(food.fieldDomestic[f]) * worth) / (corn + worth)
    b.seedPulse = (b.seedPulse * corn + food.ripePulse[f] * worth) / (corn + worth)
    b.seedCorn = corn + worth
    addFood(g.stores, g.storeKgPer, Store.SEED, worth, kg)
    if (b.knowsSkill[Skill.CULTIVATION]) b.practise(Skill.CULTIVATION)
    stats.tally(Tally.of(g.species, Tally.OfKind.GRAIN_REAPED), worth.toInt())
    stats.tally(Tally.grain(Tally.Grain.CROP_KG), kg.roundToInt())
    stats.tally(Tally.grain(Tally.Grain.CROP_DAYS), (10f * kg * perKg).roundToInt())
    note(g) { "brought in %.0f kg of its crop".format(kg) }
}

/**
 * Person-days band [b] takes to reap, thresh and clean a kilo of its own crop ([reapDaysPerKg], less its grinding, done as it is
 * eaten: [Band.grindOwed]), as thick as it stands in
 * its plot ([HexFood.fieldStand]); before it has sown, as a field would stand ripened in full.
 */
internal fun GroupWorld.cropReapDays(b: Band) =
    reapDaysPerKg(b, tuning, if (b.sownPlace.none) HexFood.yieldPerKg(b.seedDomestic) * HexFood.SEED_RATE else fauna.food.fieldStand(b.sownPlace)) - grindDaysPerKg(b)

/**
 * Person-days to sow [kg] at [p]: hoeing and sowing the ground ([SOW_DAYS_PER_KG]), and first clearing what of it is not cleared
 * yet of its trees and scrub ([HexFood.toClear], [HexFood.clearDaysPerHa]).
 */
internal fun GroupWorld.sowDays(p: Place, kg: Float): Float {
    val food = fauna.food
    return kg * SOW_DAYS_PER_KG + food.toClear(p, land.spread(p, Kg(kg))) * land.m2(p) / 1e4f * food.clearDaysPerHa(p)
}


/** The kg that [days] person-days sow at [p] ([sowDays]): on cleared ground first, then clearing as it goes. */
internal fun GroupWorld.sowableKg(p: Place, days: Float): Float {
    if (days <= 0f) return 0f
    val food = fauna.food
    val free = food.sowableCleared(p) * land.m2(p)
    if (days <= free * SOW_DAYS_PER_KG) return days / SOW_DAYS_PER_KG
    // (Beyond it each kg sown needs 1/[HexFood.SEED_RATE] m² cleared.)
    val clearPerKg = food.clearDaysPerHa(p) / (HexFood.SEED_RATE * 1e4f)
    return free + (days - free * SOW_DAYS_PER_KG) / (SOW_DAYS_PER_KG + clearPerKg)
}

/** The seed corn [g]'s band keeps back from its meals for its next sowing ([Band.seedTarget]), as far as it holds it ([seedCornIn]). */
internal fun seedKeep(g: HexGroup): Float = g.band?.let { min(it.seedTarget, it.seedCornIn(g)) } ?: 0f

/** [Band.seedCorn] as [g]'s stores hold it: grain is eaten, spoils, is given and taken from the wild grain first. */
internal fun Band.seedCornIn(g: HexGroup) = min(seedCorn, g.stores[Store.SEED])

/**
 * Whether [g]'s band [b] sows at place [p] now: in autumn (in spring where winters are too cold, [HexFood.springSown]), at home or
 * at a camp it has kept long enough to build at, where a crop can grow ([HexFood.canCrop]); if it knows how to grow grain ([Skill.CULTIVATION]) and has seed corn of its own ([Band.seedCorn]) to sow.
 */
internal fun GroupWorld.canSow(g: HexGroup, b: Band, p: Place, date: Date) =
    b.knowsSkill[Skill.CULTIVATION] && b.seedCornIn(g) > 0f &&
        (b.atHome || b.campWeeks >= houseAfterWeeks(b)) && date.month in (if (fauna.food.springSown(p)) SPRING_SOW_MONTHS else SOW_MONTHS) &&
        fauna.food.canCrop(p)

/**
 * At a meeting, a band that grows grain ([Skill.CULTIVATION]) gives [SEED_GIFT] of its seed corn, as far as it is giving
 * ([Band.giveWill]), to a band that knows how to grow grain and has none of its own to sow: farming spreads with the seed as
 * well as the know-how. Returns 1 when it gave (it founds the other's fields), else 0.
 */
internal fun GroupWorld.giveSeed(giver: HexGroup, gb: Band, taker: HexGroup, tb: Band): Float {
    if (!gb.knowsSkill[Skill.CULTIVATION] || !tb.knowsSkill[Skill.CULTIVATION] || tb.seedCornIn(taker) > 0f) return 0f
    val gift = gb.seedCornIn(giver) * SEED_GIFT * min(1f, gb.giveWill)
    if (gift <= 0.1f) return 0f
    val kg = gift * giver.storeKgPer[Store.SEED]
    giver.stores[Store.SEED] -= gift; gb.seedCorn -= gift
    addFood(taker.stores, taker.storeKgPer, Store.SEED, gift, kg)
    tb.seedDomestic = gb.seedDomestic; tb.seedPulse = gb.seedPulse; tb.seedCorn = gift
    stats.meeting(Met.Of.GIVEN, "seed corn")
    return 1f
}

/**
 * At a meeting, a band that keeps herded stock ([Herd.herdBred]) gives [STOCK_GIFT] of its grown herd of a kind, as far as it
 * is giving ([Band.giveWill]), to a band that knows herding ([Skill.TENDING]) and has no herded stock of that kind: a herd
 * starts from herders' stock, not from young caught wild. Returns 1 when it gave, else 0.
 */
internal fun GroupWorld.giveStock(gb: Band, tb: Band): Float {
    if (!gb.knowsSkill[Skill.TENDING] || !tb.knowsSkill[Skill.TENDING]) return 0f
    val gh = gb.herds; val th = tb.herds
    val gBred = gh.bred(); val tBred = th.bred(); val tYoung = th.youngHerd()
    var gave = 0f
    for (k in 0 until min(gh.herd.size, th.herd.size)) {
        if ((th.herd[k] + tYoung[k]) * tBred[k] >= 1f) continue
        val heads = gh.herd[k] * gBred[k] * STOCK_GIFT * min(1f, gb.giveWill)
        if (heads < 1f) continue
        val had = th.herd[k] + tYoung[k]
        th.herdTame[k] = (th.herdTame[k] * had + gh.herdTame[k] * heads) / (had + heads)
        val ts = th.sows(); ts[k] = (ts[k] * th.herd[k] + gh.sows()[k] * heads) / (th.herd[k] + heads)
        gh.herd[k] -= heads
        th.addBred(k, heads, 1f)
        if (k < th.herdPet.size) th.herdPet[k] = 0f
        stats.meeting(Met.Of.GIVEN, "breeding stock")
        gave = 1f
    }
    return gave
}

/**
 * [g]'s band [b], knowing herding ([Skill.TENDING]) and keeping animals, builds a pen at its camp's hex [h] for those of them
 * the pens there it may use do not hold, or goes on with the one it began, in the field work's [time] days, as far as its
 * timber goes ([PEN_DAYS_PER_HEAD], [PEN_WOOD_KG_PER_HEAD]; the unpractised take longer, [Band.lapse]). A pen keeps its
 * animals from beasts of prey at night ([herdOdds]) and from straying in the dark ([keepHerds]). It stands at the hex for
 * whoever comes, and weathers away unused, as houses do.
 */
@Part("people: build pen")
internal fun HexGroups.buildPen(g: HexGroup, b: Band, h: Hex, time: BandTime) {
    if (!b.knowsSkill[Skill.TENDING]) return
    val heads = b.herds.herd.sum() + b.herds.youngHerd().sum()
    val left = time.leftFor(Activity.SOWING)
    if (heads < PEN_MIN_HEADS || left <= 0f) return
    val need = heads - penHeadsAt(g, h, begun = true)
    val s = bands.built.firstOrNull { it.kind == Built.PEN && it.hex == h && it.user == g.id && it.built < 1f }
        ?: if (need >= PEN_MIN_HEADS) Structure(Built.PEN, h, -1, g.id, need).also { it.user = g.id; bands.built += it } else return
    val days = PEN_DAYS_PER_HEAD * s.size * b.lapse(Skill.TENDING); val wood = PEN_WOOD_KG_PER_HEAD * s.size
    val gd = b.goods
    val built = minOf(1f - s.built, left / days, gd[Material.WOOD] / wood).coerceAtLeast(0f)
    if (built <= 0f) return
    gd[Material.WOOD] -= built * wood; buildOn(s, built); time.spend(Activity.SOWING, built * days)
    stats.meeting(Met.BANDS_BUILT_PENS)
}

/**
 * [g]'s band [b] spreads the dung of its animals penned in hex [h] ([penHeadsAt]) on the worn ground of the field it sowed
 * there ([Band.sownPlace]): the week's share of a year's [HexFood.MANURE_M2_PER_HEAD] for each head penned.
 */
@Part("people: manure fields")
internal fun HexGroups.manureField(g: HexGroup, b: Band, h: Hex) {
    val f = b.sownPlace
    if (f.none || hw.placeHex[f.i] != h) return
    val heads = min(b.herds.herd.sum() + b.herds.youngHerd().sum(), penHeadsAt(g, h))
    if (heads <= 0f) return
    if (fauna.food.fieldWorn[f] <= 0f) return
    fauna.food.manure(f, heads * HexFood.MANURE_M2_PER_HEAD * days / Date.DAYS_PER_YEAR / land.m2(f))
    stats.meeting(Met.BANDS_MANURED_FIELDS)
}

/**
 * Pens ([buildPen]): the fewest head a band pens; person-days and kg of brush and stakes to fence one head's room. A thorn or
 * brush corral gives a goat 1-2 m² (from memory, unsourced): a hundred goats in a ring some 45 m round, fenced at some 4-5
 * m a person-day with some 20 kg of brush a metre (estimates). The share of beasts of prey's chance at a herd a pen takes
 * away ([PEN_GUARD]: most attacks on kept stock come at night, from memory, unsourced), and of a herd's strays left
 * unwatched it keeps ([PEN_KEEPS]: the night's share). Estimates all.
 */
internal const val PEN_MIN_HEADS = 2f
internal const val PEN_DAYS_PER_HEAD = 0.1f
internal const val PEN_WOOD_KG_PER_HEAD = 9f
internal const val PEN_GUARD = 0.5f
internal const val PEN_KEEPS = 0.5f

/**
 * Shares given at a meeting: of the giver's seed corn to a band that knows farming and has none ([giveSeed]), and of its
 * grown herded stock to a band that knows herding and has none ([giveStock]). Estimates, not measured: enough to sow a
 * small plot, and a few breeding beasts from a herd of tens.
 */
internal const val SEED_GIFT = 0.1f
internal const val STOCK_GIFT = 0.1f

/**
 * Packs of kinds that follow people (wolves, jackals) and live by a band's camps grow bolder over
 * generations, the shy ones keeping away; from a bold pack the band now and then takes a pup and
 * raises it. Resident kinds that follow people live by it the same way ([followingResidents]). Its dogs eat what lies about the camp and a little of the band's food, breed as they are fed, help it hunt
 * small game and guard its camp.
 */
@Part("people: keep dogs and animals")
internal fun HexGroups.keepAnimals(g: HexGroup, b: Band) {
    val h = hw.placeHex[g.place]
    for (o in bands.followersByHex[h].orEmpty()) {
        if (!o.alive) continue
        val s = o.species
        val bold = boldness(o)
        o.bold = bold + overTicks(BOLD_DRIFT) * (1f - bold)
        val tameable = s.domestication.average().toFloat()
        if (o.young > 0 && drawn(DialSite.KEEP_TAME, b.keepWill, rnd.nextFloat(), TAME * b.keepWill * bold * tameable * years) { TAME * bold * tameable * years }) {
            o.young--
            b.dogs.kept[s.index] += 1f
            stats.meeting(Met.Of.TOOK_A_PUP, s.name)
        }
    }
    followingResidents(h, b)
    var dogs = 0f; var need = 0f; var got = 0f
    val p = g.place
    // (A band feeds no more dogs than make a full pack for its hunters ([Dogs.dogShare]); the rest live on what they find.)
    val fedShare = min(1f, g.adults / 3f / max(1f, b.dogs.kept.sum()))
    for (i in b.dogs.kept.indices) {
        val k = b.dogs.kept[i]
        if (k <= 0f) continue
        val s = Catalogue.all[i]
        // Its dogs live mostly on what lies about the camp (scraps, offal, carrion: [DeadPool.CARRION]), and the band gives
        // them a little of its own food, the less the hungrier it is ([DOG_FED_SHARE]); they need what their wild kind does.
        val want = k * s.dailyIntakeKg * days
        var ate = small.scavengeIn(p, Kg(want), land.areaKm2[p]).v
        val given = min(want - ate, DOG_FED_SHARE * want * fedShare * g.fed)
        if (given > 0f) ate += eatStores(g, given * Nourishment.MEAT * g.species.needScale)
        val fed = min(1f, ate / want)
        need += want; got += ate
        // They whelp as they are fed and die as their kind does by people's camps. A camp that does not feed them they
        // leave rather than starve by it (as fast as hunger would kill kept herds): they go off hungry as a pack of their
        // own, of their wild kind, whose ground and prey then decide whether it lasts.
        val died = k * min(0.9f, DOG_DEATHS * years)
        small.deposit(DeadPool.CARRION, p, Kg(died * s.massKg))
        val left = binomial(k.toInt(), min(0.9f, 0.5f * (1f - fed) * years), rnd)
        if (left > 0) {
            dogsGoWild(s, p, left, fatShare = 0f)?.let { bands.strays += it }
            stats.meeting(Met.Of.DOGS_LEFT, s.name, left)
        }
        b.dogs.kept[i] = max(0f, k + k * DOG_BIRTHS * fed * years - died - left)
        dogs += b.dogs.kept[i]
    }
    b.dogs.dogFed = if (need > 0f) min(1f, got / need) else 1f
    b.dogs.dogShare = min(1f, dogs / max(1f, g.adults / 3f))
}

/**
 * [keepAnimals] for the resident kinds that follow people, as for packs (Olli 2026-11-02: residents and packs alike): each
 * band of them in the camp's hex [h] (heads over [Species.groupSize], as many as the meetings' stand-ins stand in for,
 * [ResidentParties]) lives by the camp as a pack would. They grow bolder as a pack does ([HexFauna.bold]), and from each
 * band the band [b] may take a pup with a pack's odds, off the kind's young there.
 */
private fun HexGroups.followingResidents(h: Hex, b: Band) {
    for ((k, s) in fauna.kinds.withKinds()) {
        if (!s.followsPeople) continue
        val places = hw.placesOf(h); val a = fauna.adults[k]; val y = fauna.young[k]; val bold = fauna.bold[k]
        var grown = 0f; var little = 0f; var boldHeads = 0f
        for (q in places) { val area = land.areaKm2[q]; grown += a[q] * area; little += y[q] * area; boldHeads += bold[q] * a[q] * area }
        val bands = (grown + little) / s.groupSize
        if (bands < ResidentParties.MIN_BANDS) continue
        val boldNow = if (grown > 0f) boldHeads / grown else s.boldShare
        val drift = overTicks(BOLD_DRIFT)
        for (q in places) bold[q] += drift * (1f - bold[q])
        val tameable = s.domestication.average().toFloat()
        // (Each whole band a draw, and the part band one as often as its share.)
        val draws = bands.toInt() + if (rnd.nextFloat() < bands - bands.toInt()) 1 else 0
        var youngLeft = little
        repeat(draws) {
            if (youngLeft >= 1f && drawn(DialSite.KEEP_TAME, b.keepWill, rnd.nextFloat(), TAME * b.keepWill * boldNow * tameable * years) { TAME * boldNow * tameable * years }) {
                youngLeft -= 1f
                fauna.takeYoung(k, h, 1f)
                b.dogs.kept[s.index] += 1f
                stats.meeting(Met.Of.TOOK_A_PUP, s.name)
            }
        }
    }
}

/** A group's boldness towards people: its kind's share until it has lived by them. */
internal fun boldness(g: HexGroup) = if (g.bold < 0f) g.species.boldShare else g.bold

/**
 * Taming: weekly drift towards boldness of a pack that lives by people's camps (centuries, not
 * years); yearly chance a band takes a pup from such a pack, at full boldness and tameability.
 */
internal const val BOLD_DRIFT = 0.0003f
internal const val TAME = 0.5f
/**
 * Dogs. Pups raised a dog a year when fed: 0.40 bitches (39% of village dogs, Czupryna et al. 2016, Serengeti) x 1.08
 * litters a year (11.1 months between litters, Hyeroba et al. 2017, western Uganda) x 4.2 pups (4.9 Serengeti, 3.8 Uganda,
 * 4.0 Paul et al. 2016, West Bengal) x 0.19 living to 7 months, near grown (Paul et al. 2016; the missing among them
 * counted as dead, some of whom will have left); Iringa gives as many (0.40 x 0.6 litters x 5.5 pups x 0.28 living a year,
 * Gsell et al. 2012). Yearly deaths of grown dogs: 36%, from 2.76 years left to a dog that lived its first year (Gsell et
 * al. 2012, Iringa, Tanzania); hunting dogs die younger (49%, Koster 2008, Nicaragua; Pacheco-Cobos and Winterhalder 2021,
 * Belize). Well fed, the two leave the dogs about steady, where village dogs grow 6-14% a year. The share of their need a
 * well-fed band gives the dogs of a full pack of its own food: 13% of village dogs' food by mass was given them, 87% they found, mostly
 * carrion and human waste (Butler et al. 2018, rural Zimbabwe). How much a full pack adds to the hunt of the smallest game,
 * falling to nothing at [DOG_PREY_KG]: dogs met agouti and paca far more often but red brocket deer no more (Koster 2008),
 * and gains are limited to some prey (Lupo 2017); the size a guess. How much a full pack keeps off night attacks (no source).
 */
internal const val DOG_BIRTHS = 0.35f
internal const val DOG_DEATHS = 0.36f
internal const val DOG_FED_SHARE = 0.13f
internal const val DOG_HUNT = 1f
internal const val DOG_PREY_KG = 25f
internal const val DOG_GUARD = 0.5f
/**
 * Sowing: months (0 the one after midwinter) for sowing in autumn; share of its stored grain a band sows; person-days (of 6
 * hours) to hoe and sow a hectare of cleared ground, and so for each kg sown at [HexFood.SEED_RATE] (clearing comes on top:
 * [sowDays]).
 * - Hoeing: digging by hand about 15 m² an hour, some 670 hours a hectare, 110 days (a model table of Cucuteni-Tripolye
 *   farming, arXiv 1505.05121, that gives no source for it: unsourced).
 * - Sowing: 80 hours a hectare for sorghum and millet by hand, 13 days (van Heemst, "Crop calendar, workability and labour
 *   requirements", WUR, broad averages).
 * - Not counted: weeding, 145 and 120 hours for a first and second hoeing (same source), till a field going unweeded bears less.
 * It was 0.5 days a kg sown, 60 a hectare, unsourced.
 */
val SOW_MONTHS = 9..10
/** Where winters are too cold for an autumn-sown crop ([HexFood.springSown]): sown as the ground thaws, March and April. */
val SPRING_SOW_MONTHS = 2..3
/**
 * The share of its people's year's food a farming band sows for ([sowTarget]), at its ways' 1: Neolithic farmers' bones show
 * a diet mostly of plants, cereals and pulses the staple (from memory, unchecked); half, the rest from herds, hunting and gathering.
 */
internal const val FARM_NEED_SHARE = 0.5f
/**
 * How short of its need a band goes before it eats its seed corn kept back ([seedKeep]): farmers went hungry before they ate
 * next year's seed, and ate it in famine (from memory, unsourced): half.
 */
internal const val SEED_EAT_SHORT = 0.5f
internal const val TILL_DAYS_PER_HA = 110f
internal const val SOW_DAYS_PER_HA = 13f
internal const val SOW_DAYS_PER_KG = (TILL_DAYS_PER_HA + SOW_DAYS_PER_HA) / (HexFood.SEED_RATE * 1e4f)
/**
 * A band's kept animals' week: they graze round the camp ([herdForage]), breed in their season as they are fed and as tame
 * as they have grown, give milk after it ([milkKg]), die, and run off as wild as they still are; they are herded in the field
 * work's time ([herdDays], [Band.herded]), and those its herders cannot go out with graze by the camp unwatched, and the
 * wilder of them do not come back at night ([unherdedStrays]); it eats its herd's yearly increase week by week
 * ([herdIncrease]) unless its spare days would keep that too; and generation by generation they grow tamer, since the
 * docile stay and breed.
 */
@Part("people: keep herds")
internal fun HexGroups.keepHerds(g: HexGroup, b: Band, date: Date, time: BandTime) {
    val p = g.place
    // (Herding is field work: its days came out of sowing's time when the band planned its week ([Band.herded]), as the
    // harvest's do; on the days the band walks to a new camp, the herd walks with it.)
    val tended = b.herded
    b.herded = 1f
    // The herders the week's days nobody asked for would make besides.
    var spare = time.unheld * herdSkill(b) / days
    // And the herders its leanest month leaves room for, once its fire, shelters and food are had ([Band.freeShare]),
    // less those its herds take now: a herd grows towards what the band could herd in its worst month, and is culled
    // above it, so that it never takes the days that keep its people alive. (Measured from the band's weeks, not from
    // the herd: a capacity measured from the herd itself ratchets down, see 4.64 in [HexSnapshot].) Null till it has
    // planned a week of every month; till then, the spare days alone.
    if (b.herds.herdGrew.size != b.herds.herd.size) b.herds.herdGrew = BooleanArray(b.herds.herd.size)
    if (b.herds.herdPet.size != b.herds.herd.size) b.herds.herdPet = FloatArray(b.herds.herd.size)
    val young = b.herds.youngHerd(); val sows = b.herds.sows(); val bred = b.herds.bred()
    val pen = penned(g, b, hw.placeHex[p])
    val herders = b.herds.herd.indices.sumOf { k -> ((b.herds.herd[k] + young[k]) / headsAHerder(fauna.kinds[k])).toDouble() }.toFloat()
    val capacity = b.freeShare.takeIf { f -> f.all { it >= 0f } }?.let { f -> f.min() * time.total / days * herdSkill(b) }
    var room = capacity?.let { it - herders }
    // (Above it, the band culls a month's share of the excess a week, as it eats: not all at once, to rot.)
    val over = if (capacity != null && herders > capacity) (1f - capacity / herders) * min(1f, years * Date.MONTHS_PER_YEAR) else 0f
    fun tally(k: Int, f: HerdFlow, v: Float) { bands.herdFlows[f] += v; bands.kindFlows[k][f] += v }
    // Hunger before the herd: a band that went short this week, with too little in its larder to make the rest up, kills of
    // its grown beasts what its shortfall was, for next week's meals (kg of its need). Its herd's increase to come is the
    // cost. (Pets it keeps.)
    var hungry = if (g.fed < WELL_FED) max(0f, (1f - g.fed) * g.dailyNeedKg * days - g.larder) else 0f
    fun slaughter(k: Int, heads: Float, flow: HerdFlow, weight: Float = 1f) {
        val s = fauna.kinds[k]
        tally(k, flow, heads)
        // (Kept at what it feeds, as the week's meat: [Nourishment.MEAT]; its hides, bone and sinew as a kill's ([Band.skin]);
        // the rest of the carcass for the scavengers. Young weigh [weight] of a grown one.)
        val meat = heads * weight * s.massKg * MEAT_SHARE
        addFood(g.stores, g.storeKgPer, Store.FRESH, meat * Nourishment.MEAT * g.species.needScale, meat)
        val left = heads * weight * s.massKg * (1f - MEAT_SHARE)
        small.deposit(DeadPool.CARRION, p, Kg(left - tallyHide(Tally.HideSource.HERDS, s, b.skin(s, heads, left))))
    }
    if (b.herds.herdTaken.size != b.herds.herd.size) b.herds.herdTaken = FloatArray(b.herds.herd.size)
    for (k in b.herds.herd.indices) {
        // (What beasts of prey took from it while they hunted this week: [raidHerd].)
        if (b.herds.herdTaken[k] > 0f) { tally(k, HerdFlow.TAKEN, b.herds.herdTaken[k]); bands.takenWeek[k] += b.herds.herdTaken[k]; b.herds.takenSince += b.herds.herdTaken[k]; b.herds.herdTaken[k] = 0f }
        var n = b.herds.herd[k]; var y = young[k]
        b.herds.heldSince += (n + y) * years
        if (n + y <= 0f) continue
        val n0 = n
        val s = fauna.kinds[k]
        val tame = b.herds.herdTame[k]
        // Grazing round the camp. (The young eat about half what a grown one does, as the residents' do.)
        val f = herdForage(p, s, n + 0.5f * y)
        val got = land.plants.consume(p, LifeForm.HERB, KgM2(f.grass)) + land.plants.eatBrowse(p, KgM2(f.browse))
        val fed = if (f.need > 0f) min(1f, got.v / f.need) else 1f
        // (The days of its birth season the step holds, at any step length: [seasonDays].)
        val seasonPart = seasonDays(s, s.birthMonth, date, days) / (s.birthMonths * Date.DAYS_PER_MONTH.toFloat())
        // (Short of sodium where their forage is, its beasts bear fewer young, as far as its salt does not make it up: [feedHerdSalt].)
        val salted = if (tuning.grazerSaltLack > 0f) saltBreeding(feedHerdSalt(b, s, n + 0.5f * y, fauna.forageSodiumAt[p], days), tuning.grazerSaltLack) else 1f
        // (Only its mothers of herded stock bear young, [Herd.herdBred]: young caught wild are pets. Those born are herded stock.)
        val stock = bred[k]
        val born = if (seasonPart > 0f) n * stock * herdMothers(s, tame, sows[k]) * s.youngPerFemale * fed * salted * seasonPart else 0f
        if (born > 0f) bred[k] = (stock * (n + y) + born) / (n + y + born)
        // Its mothers give milk through the lactation after the birth season, as they are fed and as tame as they are.
        if (lactating(s, date)) {
            val milk = n * stock * herdMothers(s, tame, sows[k]) * tame * fed * milkKg(s) * days / LACTATION_DAYS
            addFood(g.stores, g.storeKgPer, Store.FRESH, milk * Nourishment.MILK * g.species.needScale, milk)
            tally(k, HerdFlow.MILK_KG, milk)
        }
        // (Crowded with their kind on the ground they graze, wild ones there and all, they sicken as the residents do, and
        // an outbreak among their kind there takes them too: [HexFauna.hazards].)
        val dying = (s.backgroundMortality + 0.5f * (1f - fed) + fighting(s) + herdSickness(p, k, n + y, fed)) * years +
            fauna.outbreakDeaths(s, p)
        // (The herded stray as wild as they are ([straying]); those nobody went out with this week graze by the camp, and the
        // wilder of them do not come home ([unherdedStrays]).)
        // (Penned at night ([Built.PEN]), they cannot wander off in the dark: [PEN_KEEPS] of the strays are kept.)
        val unwatched = (1f - tended) * (1f - PEN_KEEPS * pen) * min(1f, unherdedStrays(s, tame) * b.lapse(Skill.TENDING) * days / 7f)
        val running = straying(s, tame) * b.lapse(Skill.TENDING) * years + unwatched
        val lost = n * min(0.9f, dying + running); val lostY = y * min(0.9f, dying + running)
        // (The dead lie by the camp; those that run off or stray go wild among their kind.)
        val diedShare = if (dying + running > 0f) dying / (dying + running) else 0f
        val died = lost * diedShare; val diedY = lostY * diedShare
        val area = land.areaKm2[p]
        small.deposit(DeadPool.CARRION, p, Kg((died + HexFauna.YOUNG_WEIGHT * diedY) * s.massKg))
        fauna.adults[k][p] += (lost - died) / area; fauna.young[k][p] += (lostY - diedY) / area
        n = max(0f, n - lost); y = max(0f, y + born - lostY)
        // The young grow up as the residents' young do, over the years to maturity.
        val up = y * min(1f, years / s.yearsToMaturity)
        y -= up; if (n + up > 0f) sows[k] = (n * sows[k] + 0.5f * up) / (n + up); n += up
        // (What the grown herd gained this week: its young grown up, less the grown ones lost. That much the band can eat
        // without eating into its mothers, for only the grown ones bear young.)
        val nUp = n; val gained = max(0f, n - n0)
        tally(k, HerdFlow.BORN, born); tally(k, HerdFlow.DIED, died + diedY); val ran = lost - died + lostY - diedY; val strayed = if (running > 0f) ran * unwatched / running else 0f
        tally(k, HerdFlow.RAN_OFF, ran - strayed); tally(k, HerdFlow.STRAYED, strayed)
        val kg = (n + HexFauna.YOUNG_WEIGHT * y) * s.massKg
        tally(k, HerdFlow.KG, kg); tally(k, HerdFlow.KG_TENDED, kg * tended); tally(k, HerdFlow.KG_FED, kg * fed)
        // (For the census by kind: what it adds in a year, and what a herder's day of it brings against a lean month's foraging.)
        tally(k, HerdFlow.INCREASE_KG, herdIncrease(b, k, fed, p) * kg)
        // Herders live on their herds' increase: the band eats what its herd adds in a year, week by week through the year
        // (its births come in one season, its losses all year), and keeps it, its females with it, where its leanest
        // month would still herd the herd grown ([room]; till it knows its months, where this week's spare days would). A herd whose increase brings a herder less than
        // a day's foraging brings it eats down, a week's food at a time. Pets ([Herd.herdPet]) it keeps whole, for their own
        // sake, and weighs as food only once they have grown.
        val pet = b.herds.herdPet[k] > 0f
        if (pet) { tally(k, HerdFlow.KG_PET, kg); b.herds.herdPet[k] = max(0f, b.herds.herdPet[k] - years) }
        val worth = herdWorth(g, b, k)
        val pays = worth >= 1f
        if (pays) tally(k, HerdFlow.KG_PAYS, kg)
        tally(k, when { worth < 0.5f -> HerdFlow.KG_WORTH_HALF; worth < 1f -> HerdFlow.KG_WORTH_UNDER; worth < 2f -> HerdFlow.KG_WORTH_OVER; else -> HerdFlow.KG_WORTH_TWICE }, kg)
        if (worth.isFinite()) { tally(k, HerdFlow.KG_WORTH_KNOWN, kg); tally(k, HerdFlow.WORTH_KG, worth * kg) }
        // (The keepers kill the males first, keeping a boar to [SOWS_A_MALE] sows, and the sows only once those are gone.)
        fun eatGrown(heads: Float, flow: HerdFlow) {
            if (heads <= 0f) return
            val males = max(0f, n * (1f - sows[k]) - n * sows[k] / SOWS_A_MALE)
            val fromSows = max(0f, heads - males)
            slaughter(k, heads, flow)
            sows[k] = if (n - heads > 0f) min(1f, max(0f, (n * sows[k] - fromSows) / (n - heads))) else 0.5f
            n -= heads
        }
        if (over > 0f) { tally(k, HerdFlow.KG_OVER, kg); eatGrown(n * over, HerdFlow.EATEN_OVER) }
        val increase = if (nUp > 0f) gained * n / nUp else 0f
        // (The heads a year a herd of [m] grown, its young in the same share, adds at this week's feeding: its yearly
        // increase ([herdIncrease]) at its crowding. A keeper grows a herd only while a bigger one adds more, and eats one
        // bigger than the herd that adds most down towards it, a month's share of the excess a week, as above.)
        val youngShare = if (n > 0f) y / n else 0f
        fun adds(m: Float) = m * max(0f, herdIncrease(b, k, fed, p, m * (1f + youngShare)))
        val best = if (pet || n <= 0f || adds(n) <= 0f) n else n * (1..BEST_HERD_STEPS).maxBy { adds(n * it / BEST_HERD_STEPS) } / BEST_HERD_STEPS
        val grows = pet || pays && increase / headsAHerder(s) <= (room ?: spare) && adds(n + increase) > adds(n)
        if (grows && !pet) { if (room != null) room -= increase / headsAHerder(s) else spare -= increase / headsAHerder(s) }
        b.herds.herdGrew[k] = grows
        // (For the census: what held a paying herd back from growing, the leanest month's herders or a bigger herd adding no more.)
        if (pays && !grows && !pet) tally(k, if (increase / headsAHerder(s) > (room ?: spare)) HerdFlow.KG_HELD_ROOM else HerdFlow.KG_HELD_BEST, kg)
        val kept = if (grows) n else if (pays) max(0f, min(n - increase, n - (n - best) * min(1f, years * Date.MONTHS_PER_YEAR)))
            else max(0f, n - g.dailyNeedKg * days / (s.massKg * MEAT_SHARE * Nourishment.MEAT * g.species.needScale))
        if (n > kept) {
            // (Of a paying herd, its increase is eaten as such, and what is eaten beyond it towards the herd that adds most is
            // counted apart: [HerdFlow.EATEN_BEST].)
            if (pays) { val ofIncrease = min(n - kept, increase); eatGrown(ofIncrease, HerdFlow.EATEN); eatGrown(n - kept - ofIncrease, HerdFlow.EATEN_BEST) }
            else eatGrown(n - kept, HerdFlow.EATEN_UNPAID)
        }
        n = kept
        if (hungry > 0f && !pet && n > 0f) {
            val each = s.massKg * MEAT_SHARE * Nourishment.MEAT * g.species.needScale
            val heads = min(n, hungry / each)
            eatGrown(heads, HerdFlow.EATEN_HUNGRY); hungry -= heads * each
        }
        if (n + y < 0.5f) { fauna.adults[k][p] += n / area; fauna.young[k][p] += y / area; n = 0f; y = 0f; b.herds.herdPet[k] = 0f
            sows[k] = 0.5f; tally(k, if (pet) HerdFlow.ENDED_PET else if (pays) HerdFlow.ENDED else HerdFlow.ENDED_UNPAID, 1f) }
        b.herds.herd[k] = n; young[k] = y
        b.herds.herdTame[k] = tame + TAMING_RATE * b.edge(Skill.TENDING) * years * (1f - tame)
        if (b.knowsSkill[Skill.TENDING]) b.practise(Skill.TENDING, 0.5f * PRACTICE)
    }
}

/**
 * What became of bands' kept animals, for the census ([HexGroups.herdFlows]): heads born, dead, run off while herded, taken by beasts of prey ([raidHerd]), strayed
 * from the camp with nobody out with them ([unherdedStrays]), eaten of the herd's increase, eaten down for not paying ([herdPays]), eaten for the band's hunger, and gone wild with a band that
 * died out ([leaveDead]); milk, kg; and kg on the hoof weighed by the share herded, by the share fed, in herds that pay their
 * herders, and in all (week by week), and by what a herder's day brings against a lean month's foraging day ([herdWorth]:
 * under half, under 1, under 2, more); and herds that came to an end, as they paid, did not, or went wild with their band. By
 * kind only ([Bands.kindFlows]): the yearly increase, and what a herder's day brings, each weighed by kg on the hoof (where
 * the band has foraged a month to weigh it against); kg kept as pets ([Herd.herdPet]), and pets' herds that came to an end.
 */
enum class HerdFlow { BORN, DIED, RAN_OFF, TAKEN, STRAYED, EATEN, EATEN_UNPAID, EATEN_HUNGRY, FREED, MILK_KG, KG_TENDED, KG_FED, KG_PAYS, KG,
    KG_WORTH_HALF, KG_WORTH_UNDER, KG_WORTH_OVER, KG_WORTH_TWICE, ENDED, ENDED_UNPAID, ENDED_FREED,
    INCREASE_KG, KG_WORTH_KNOWN, WORTH_KG, KG_PET, ENDED_PET,
    /** Culled for being more than the band's leanest month could herd; and kg on the hoof in herds that were. */
    EATEN_OVER, KG_OVER,
    /** Eaten beyond a paying herd's increase, down towards the herd that adds most. */
    EATEN_BEST,
    /** Kg on the hoof in paying herds kept from growing: by the leanest month's herders ([Band.freeShare]), or by a bigger herd adding no more. */
    KG_HELD_ROOM, KG_HELD_BEST }

/** What [n] kept animals of kind [s] ask of place [p]'s ground this week, and the grass and browse they can get there, kg/m². */
internal class HerdForage(val need: Float, val grass: Float, val browse: Float) {
    val fed get() = if (need > 0f) min(1f, (grass + browse) / need) else 1f
}

/**
 * The forage [n] kept animals of kind [s] find round a camp at [p] in a week: their week's intake ([Species.dailyIntakeKg]),
 * grass and browse in their diet's shares, as far as the ground they graze round the camp ([grazedKm2]) has them: grass
 * as far as the residents reach it in a week ([HexFauna.REACH]), and the browse shoots. (Their fruit and roots are not
 * counted: a herd's forage does not bind by a long way, see the HerdLimits probe.) In kg/m² of the place. The same reckoning feeds them ([keepHerds]) and weighs a camp for them ([HexGroups.herdLoss]).
 */
internal fun GroupWorld.herdForage(p: Place, s: Species, n: Float): HerdForage {
    val need = land.spread(p, Kg(n * s.dailyIntakeKg * days)).v
    val d = s.diet; val total = (d.grass + d.browse).coerceAtLeast(1e-6f)
    val grazed = grazedKm2(p, s) / land.areaKm2[p]
    return HerdForage(need, min(need * d.grass / total, grazed * overTicks(HexFauna.REACH) * land.palatable[p] * land.plants.herb[p]),
        min(need * d.browse / total, grazed * land.plants.browse(p)))
}

/**
 * The ground, km², a herd of kind [s] kept by a camp at [p] grazes: the circle half its day's going across (out and back,
 * as [HexFauna] spreads a band of its residents), no more than the place. Its forage ([herdForage]) and its crowding
 * ([keepHerds]) are reckoned on it.
 */
internal fun GroupWorld.grazedKm2(p: Place, s: Species): Float {
    val r = 0.5f * s.dailyTravelKm
    return min(land.areaKm2[p], Math.PI.toFloat() * r * r)
}

/** The share of the week [time] its band spent walking to a new camp. */
internal fun walkedShare(time: BandTime) = if (time.total > 0f) min(1f, time.spentOn(Activity.MOVING) / time.total) else 0f

/**
 * Person-days [b]'s herders take a week to keep its animals: herded on foot, a herder keeps [headsAHerder] of each kind,
 * and the unpractised take up to twice as long.
 */
internal fun GroupWorld.herdDays(b: Band): Float {
    var herders = 0f
    for (k in b.herds.herd.indices) if (b.herds.herd[k] > 0f) herders += b.herds.herd[k] / headsAHerder(fauna.kinds[k])
    return herders * days / herdSkill(b)
}

/**
 * The head of kind [s] a practised herder keeps on foot: [HERD_GOATS_A_HERDER] goats, fewer of bigger beasts, as their
 * weight to the power [HERD_MASS_EXP] (more weight on the hoof a herder in big beasts than in small).
 */
internal fun headsAHerder(s: Species) = HERD_GOATS_A_HERDER * (s.massKg / GOAT_KG).pow(-HERD_MASS_EXP)

/** Whether a herder's day with [g]'s band [b]'s herd of kind [k] brings it at least what a day's foraging does in its leanest month ([herdWorth]). */
internal fun GroupWorld.herdPays(g: HexGroup, b: Band, k: Int) = herdWorth(g, b, k) >= 1f

/**
 * What a herder's day with [g]'s band [b]'s herd of kind [k] brings it against what a day's foraging does in its leanest
 * month ([Band.forageReturn]; without a month foraged yet, without bound): a herd is food kept alive till the lean months, when a day's foraging brings least. The
 * herd's day is its yearly increase as [keepHerds] has it, well fed (births by its tameness, less its deaths and those
 * that run off), at its meat, and its mothers' milk ([milkKg], as tame as they are), on the stock a herder keeps. (Its
 * mean day's foraging stands for its last one; the milking is taken to be in the herders' days.)
 */
internal fun GroupWorld.herdWorth(g: HexGroup, b: Band, k: Int): Float {
    val lean = b.forageReturn.filter { it >= 0f }.minOrNull()?.takeIf { it > 0f } ?: return Float.POSITIVE_INFINITY
    val s = fauna.kinds[k]; val tame = b.herds.herdTame[k]
    val mothers = b.herds.bred()[k] * herdMothers(s, tame, b.herds.sows()[k])
    val increase = herdIncrease(b, k, at = g.place)
    val heads = headsAHerder(s) * herdSkill(b)
    val day = heads * (increase * s.massKg * MEAT_SHARE * Nourishment.MEAT + mothers * tame * milkKg(s) * Nourishment.MILK) *
        g.species.needScale / Date.DAYS_PER_YEAR
    return day / lean
}

/**
 * What [g]'s band [b] can count on its herds to feed it in the week of [date], in kg of its need as [forage] counts food:
 * the week's share of each herd's yearly increase, which [keepHerds] eats unless the herd kept it to grow last week
 * ([Herd.herdGrew]), and the milk of its mothers in milk. The band forages only for the rest of its week's food.
 */
internal fun GroupWorld.herdFoodWeek(g: HexGroup, b: Band, date: Date): Float {
    var food = 0f
    for (k in b.herds.herd.indices) {
        val n = b.herds.herd[k]
        if (n <= 0f) continue
        val s = fauna.kinds[k]; val tame = b.herds.herdTame[k]
        if (!b.herds.herdGrew.getOrElse(k) { false }) food += n * max(0f, herdIncrease(b, k, at = g.place)) * years * s.massKg * MEAT_SHARE * Nourishment.MEAT
        if (lactating(s, date)) food += n * b.herds.bred()[k] * herdMothers(s, tame, b.herds.sows()[k]) * tame * milkKg(s) * days / LACTATION_DAYS * Nourishment.MILK
    }
    return food * g.species.needScale
}

/** Whether mothers of kind [s] are in milk at [date]: through the lactation after the birth season. */
private fun lactating(s: Species, date: Date) =
    monthsFrom(s.birthMonth, date.month) * Date.DAYS_PER_MONTH < s.birthMonths * Date.DAYS_PER_MONTH + LACTATION_DAYS

/**
 * The share [b]'s grown herd of kind [k] adds in a year as [keepHerds] has it, fed as far as [fed], once its young are of
 * every age: births by its tameness, as many as live to grow up, less the grown ones' deaths and those that run off, and, kept by a camp
 * at [at], those the sickness of its crowding takes at its heads now ([herdSickness]). The
 * young die and run off at the grown ones' yearly rate and grow up at one over [Species.yearsToMaturity] a year, so
 * 1 / (1 + rate * years to maturity) of them grow up.
 */
internal fun GroupWorld.herdIncrease(b: Band, k: Int, fed: Float = 1f, at: Place? = null,
    heads: Float = b.herds.herd[k] + b.herds.youngHerd().getOrElse(k) { 0f }): Float {
    val s = fauna.kinds[k]; val tame = b.herds.herdTame[k]
    val lost = s.backgroundMortality + 0.5f * (1f - fed) + fighting(s) + straying(s, tame) * b.lapse(Skill.TENDING) +
        (at?.let { herdSickness(it, k, heads, fed) } ?: 0f)
    // (Where its forage is short of sodium, as far as its keepers' salt would not make it up: [feedHerdSalt].)
    val salted = if (at == null || b.goods[Material.SALT] > 0f) 1f else fauna.saltBreeding(s, at)
    return b.herds.bred()[k] * herdMothers(s, tame, b.herds.sows()[k]) * s.youngPerFemale * fed * salted / (1f + lost * s.yearsToMaturity) - lost
}

/**
 * The yearly share of [heads] kept animals of kind [k], fed [fed], kept by a camp at [p], that the sickness of crowding
 * takes: crowded with the residents of their kind on the ground they graze ([grazedKm2]), by the residents' rule
 * ([Hazards.crowdDisease], [HexFauna.hazards]).
 */
internal fun GroupWorld.herdSickness(p: Place, k: Int, heads: Float, fed: Float): Float {
    val s = fauna.kinds[k]
    val crowd = Hazards.crowding(heads / grazedKm2(p, s) + fauna.adults[k][p] + fauna.young[k][p], HexFauna.usualDensity(s))
    return Hazards.crowdDisease(crowd, fed, 1f)
}

/** Diamond's six criteria for a kind that can be kept, in the order of [Species.domestication]. */
internal enum class Diamond { DIET, GROWTH, BREEDING, TEMPERAMENT, CALM, HIERARCHY }

/** How well kind [this] meets Diamond's criterion [d], 0..1. */
internal fun Species.diamond(d: Diamond) = domestication[d.ordinal]

/**
 * The share of a kept herd of kind [s] that bear young in a year, [tame] as it is: its females ([sows] of the grown), as tame as they are, and as
 * far as their kind mates and rears young in a camp without pens or incubators (Diamond's captive-breeding criterion)
 * against [HERD_BREEDS], a goat's. No more than their kind's wild rate: a herd does not out-breed its kind.
 */
internal fun herdMothers(s: Species, tame: Float, sows: Float = 0.5f) = sows * (0.3f + 0.7f * tame) * min(1f, s.diamond(Diamond.BREEDING) / HERD_BREEDS)

/**
 * The years young of kind [s] caught in the hunt are kept as pets ([Herd.herdPet]): till they have grown
 * ([Species.yearsToMaturity]), or as much sooner as their kind's temper (Diamond's disposition criterion) falls short of a
 * goat's, [HERD_TEMPER]: nobody keeps a pet that bites or wrecks the camp, and the temper shows as the young grow.
 */
internal fun petYears(s: Species) = s.yearsToMaturity * min(1f, s.diamond(Diamond.TEMPERAMENT) / HERD_TEMPER)

/**
 * The yearly share of a kept herd of kind [s] that kill or maim each other or are put down as dangerous, however tame:
 * [HERD_FIGHTING] times the square of how far its kind's temper (Diamond's disposition criterion) falls short of a goat's,
 * [HERD_TEMPER]; without pens the keepers cannot part the fighters.
 */
internal fun fighting(s: Species): Float {
    val wild = max(0f, 1f - s.diamond(Diamond.TEMPERAMENT) / HERD_TEMPER)
    return HERD_FIGHTING * wild * wild
}

/**
 * The yearly share of a kept herd of kind [s] that strays, [tame] as it is: [HERD_RUN_OFF] times the square of how far the
 * herd falls short of being kept on foot, its tameness times the kind's ease on foot (the worse of Diamond's panic and
 * social-structure criteria) against [HERD_EASE], a goat's.
 */
internal fun straying(s: Species, tame: Float): Float = HERD_RUN_OFF * wildness(s, tame).let { it * it }

/**
 * The share of a kept herd of kind [s] grazing by the camp with nobody out with it that does not come home in a week, [tame]
 * as it is: the square of its wildness, as [straying]'s. A herd as tame as a goat's at its tamest stays by the camp unwatched
 * (village goats in south-west Nigeria roam free in herds of 7-9, and strays are not among their main exits: ILRI, abstract
 * only); a wild one does not come back. An estimate: no record gives how half-tame herds come home.
 */
internal fun unherdedStrays(s: Species, tame: Float): Float = wildness(s, tame).let { it * it }

/**
 * How far a kept herd of kind [s] falls short of being kept on foot, 0..1, [tame] as it is: its tameness times the kind's
 * ease on foot (the worse of Diamond's panic and social-structure criteria) against [HERD_EASE], a goat's.
 */
private fun wildness(s: Species, tame: Float): Float {
    val ease = min(s.diamond(Diamond.CALM), s.diamond(Diamond.HIERARCHY))
    return max(0f, 1f - tame * ease / HERD_EASE)
}

/**
 * The milk a mother of kind [s] gives its keepers in a lactation, kg, where its kind is milked ([Species.milked]; none else): an indigenous goat milked once a day in the open,
 * 56 kg in 101 days (Hagos, Gizaw and Urge 2017, Begait goats, western Tigray; the kids suckle besides), taken by the
 * mother's weight against its 40 kg (as M^0.75; the scaling an estimate). Early herds' mothers likely gave less.
 */
internal fun milkKg(s: Species) = if (s.milked) MILK_KG_GOAT * (s.massKg / 40f).pow(0.75f) else 0f
internal const val MILK_KG_GOAT = 56f
internal const val LACTATION_DAYS = 101f

/** How much of a practised herder's stock [b]'s herders keep each: half, unpractised. */
private fun herdSkill(b: Band) = 0.5f + 0.5f * min(1f, b.skill(Skill.TENDING))

/** Chance a young animal caught in the hunt is kept and raised instead of eaten, times its kind's tameability. An estimate. See docs/herding-constants.md for these constants. */
internal const val CAPTURE = 0.5f
/** How tame young raised by hand come, whatever their kind (imprinting; Price 1984). An estimate. */
internal const val RAISED_TAME = 0.8f
/** Yearly share of a kept herd that strays: this times the square of its wildness ([straying]). An estimate. */
internal const val HERD_RUN_OFF = 0.5f
/** Yearly share of a kept herd lost to fighting: this times the square of the kind's lack of temper ([fighting]). An estimate. */
internal const val HERD_FIGHTING = 0.5f
/** A goat's ease on foot (the worse of Diamond's panic and social-structure criteria), the scale for [straying]. An estimate. */
internal const val HERD_EASE = 0.6f
/**
 * Sows (or ewes, does, cows) a kept male serves: the keepers kill the other males first ([keepHerds]). Sardinian pig herds
 * of 20-40 keep one to three boars (Albarella, Manconi and Trentacoste 2011, "A week on the plateau", in Ethnozooarchaeology,
 * Oxbow, p. 150): about one to fifteen. The same for every kind, an estimate beyond pigs.
 */
internal const val SOWS_A_MALE = 15f
/** The herd sizes, as shares of the herd now in equal steps, a keeper weighs for the herd that adds most a year ([keepHerds]). */
internal const val BEST_HERD_STEPS = 10
/** A goat's captive breeding (Diamond's criterion), the scale for [herdMothers]. */
internal const val HERD_BREEDS = 0.7f
/** A goat's temperament (Diamond's disposition criterion), the scale for [fighting] and [petYears]. */
internal const val HERD_TEMPER = 0.6f
/** Yearly growth of a herd's tameness. */
internal const val TAMING_RATE = 0.01f
/** Goats a herder keeps on foot (Iranian nomads: 200-600 head to one to three shepherds, Encyclopaedia Iranica, "Cupan"). */
internal const val HERD_GOATS_A_HERDER = 200f
/** A goat's weight, the base for [HERD_MASS_EXP]. */
internal const val GOAT_KG = 40f
/** Bigger beasts fewer to a herder, as their weight to this power. An estimate between goats and camel herds of 50-60 head (ODI Pastoral Development Network paper 5337). */
internal const val HERD_MASS_EXP = 0.5f
