package inflation.ecology

import inflation.world.Place
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
import kotlin.math.min

// Bands' fields and animals: sowing and reaping, kept animals, dogs and herds.

/**
 * In autumn [g]'s band, at home or at a camp it has kept long enough to build at ([houseAfterWeeks]), sows a share of its stored
 * grain there, in the [time] days to spare. (A band that then moves on leaves the plot to whoever comes by.)
 */
internal fun HexGroups.sowGrain(g: HexGroup, b: Band, p: Place, date: Date, time: BandTime) {
    val left = time.leftFor(Activity.SOWING)
    if (canSow(g, b, p, date) && left > 0f) {
        val stock = g.stores[Store.SEED]
        val sow = minOf(stock * SOW_SHARE * b.sowWill, left / SOW_DAYS_PER_KG)
        dialUse?.amount(DialSite.SOWING, b.sowWill, sow, minOf(stock * SOW_SHARE, left / SOW_DAYS_PER_KG))
        if (sow > 0.1f) {
            // (Its seed corn first, then grain gathered from the wild stands.)
            val corn = b.seedCornIn(g); val fromCorn = min(sow, corn)
            val domestic = (fromCorn * b.seedDomestic + (sow - fromCorn) * b.grainDomestic) / sow
            g.stores[Store.SEED] -= sow; b.seedCorn = corn - fromCorn; time.spend(Activity.SOWING, sow * SOW_DAYS_PER_KG)
            val kg = sow * g.storeKgPer[Store.SEED]
            // (A field sown afresh, or sown where last year's crop is spent, is this year's crop.)
            if (b.sownPlace != p || fauna.food.field[p] <= 0f) b.sownKg = 0f
            b.sownPlace = p; b.sownKg += kg
            fauna.food.sow(p, land.spread(p, Kg(kg)), domestic)
            stats.meeting(Met.BANDS_SOWED)
            note(g) { "sowed %.0f kg of grain at home".format(sow) }
        }
    }
}

/**
 * The ripe grain of the crop [g]'s band [b] sowed ([Band.sownPlace]), kg, where the band is in that hex: its share of what
 * stands there, by the seed it sowed against all sown there (all of it once the year's sowing is struck off).
 */
internal fun HexGroups.cropRipe(g: HexGroup, b: Band): Float {
    val f = b.sownPlace
    if (f.none || b.sownKg <= 0f || hw.placeHex[f.i] != hw.placeHex[g.place.i]) return 0f
    val food = fauna.food
    val share = if (food.field[f] > 0f) min(1f, b.sownKg / (food.field[f] * land.m2(f))) else 1f
    return food.fieldGrain[f] * land.m2(f) * share
}

/**
 * [g]'s band [b] brings in its ripe crop ([cropRipe]) in the [time] days to spare, as seed corn: a crop is reaped whole
 * when it is ripe, not a week's meals at a time.
 */
internal fun HexGroups.harvestCrop(g: HexGroup, b: Band, time: BandTime) {
    val ripe = cropRipe(g, b); val left = time.leftFor(Activity.SOWING)
    if (ripe <= 0f || left <= 0f) return
    val perKg = reapDaysPerKg(b, tuning)
    val f = b.sownPlace; val food = fauna.food
    val kg = land.total(f, food.take(food.fieldGrain, f, land.spread(f, Kg(min(ripe, left / perKg))))).v
    if (kg <= 0f) return
    time.spend(Activity.SOWING, kg * perKg)
    val worth = kg * seedValue(b) * b.cookValue(Food.GRAIN)
    val corn = b.seedCorn
    b.seedDomestic = (b.seedDomestic * corn + b.reapedDomestic(food.fieldDomestic[f]) * worth) / (corn + worth)
    b.seedCorn = corn + worth
    addFood(g.stores, g.storeKgPer, Store.SEED, worth, kg)
    stats.hunting.merge(Tally.of(g.species, Tally.OfKind.GRAIN_REAPED), worth.toInt(), Int::plus)
    note(g) { "brought in %.0f kg of its crop".format(kg) }
}

/** [Band.seedCorn] as [g]'s stores hold it: grain is eaten, spoils, is given and taken from the wild grain first. */
internal fun Band.seedCornIn(g: HexGroup) = min(seedCorn, g.stores[Store.SEED])

/** Whether [g]'s band [b] sows at place [p] now: in autumn, at home or at a camp it has kept long enough to build at, where grass grain grows. */
internal fun HexGroups.canSow(g: HexGroup, b: Band, p: Place, date: Date) =
    (b.atHome || b.campWeeks >= houseAfterWeeks(b)) && date.month in SOW_MONTHS && fauna.food.cereal[p] > 0f

/**
 * Packs of kinds that follow people (wolves, jackals) and live by a band's camps grow bolder over
 * generations, the shy ones keeping away; from a bold pack the band now and then takes a pup and
 * raises it. Its dogs breed when the band eats well, help it hunt and guard its camp.
 */
internal fun HexGroups.keepAnimals(g: HexGroup, b: Band) {
    val h = hw.placeHex[g.place]
    for (o in bands.followersByHex[h].orEmpty()) {
        if (!o.alive) continue
        val s = o.species
        val bold = boldness(o)
        o.bold = bold + BOLD_DRIFT * (1f - bold)
        val tameable = s.domestication.average().toFloat()
        if (o.young > 0 && drawn(DialSite.KEEP_TAME, b.keepWill, rnd.nextFloat(), TAME * b.keepWill * bold * tameable * years) { TAME * bold * tameable * years }) {
            o.young--
            b.kept[s.index] += 1f
            stats.meeting(Met.Of.TOOK_A_PUP, s.name)
        }
    }
    var dogs = 0f
    for (i in b.kept.indices) {
        val k = b.kept[i]
        if (k <= 0f) continue
        val cap = DOGS_PER_ADULT * g.adults
        b.kept[i] = max(0f, k + k * (DOG_GROWTH * g.fed * (1f - k / max(1f, cap)) - DOG_DEATHS) * years)
        dogs += b.kept[i]
    }
    b.dogShare = min(1f, dogs / max(1f, g.adults / 3f))
}

/** A group's boldness towards people: its kind's share until it has lived by them. */
internal fun boldness(g: HexGroup) = if (g.bold < 0f) g.species.boldShare else g.bold

/**
 * Taming: weekly drift towards boldness of a pack that lives by people's camps (centuries, not
 * years); yearly chance a band takes a pup from such a pack, at full boldness and tameability.
 */
const val BOLD_DRIFT = 0.0003f
const val TAME = 0.5f
/**
 * Dogs: yearly growth of a band's dogs when fed, their yearly deaths, most a band keeps per adult;
 * how much a full pack of dogs adds to the hunt and keeps off night attacks.
 */
const val DOG_GROWTH = 0.4f
const val DOG_DEATHS = 0.1f
const val DOGS_PER_ADULT = 0.3f
const val DOG_HUNT = 0.2f
const val DOG_GUARD = 0.5f
/**
 * Sowing: months (0 the one after midwinter) for sowing in autumn; share of its stored grain a band
 * sows; person-days to clear, sow and weed for each kg sown.
 */
val SOW_MONTHS = 9..10
const val SOW_SHARE = 0.3f
const val SOW_DAYS_PER_KG = 0.5f
/**
 * A band's kept animals' week: they graze round the camp ([herdForage]), breed in their season as they are fed and as tame
 * as they have grown, give milk after it ([milkKg]), die, and run off as wild as they still are; they are herded in the field
 * work's time ([herdDays], [Band.herded]), and what its herders cannot keep the band culls (meat in its stores), as it eats
 * its herd's yearly increase week by week ([herdIncrease]) unless its spare days would keep that too; and generation by
 * generation they grow tamer, since the docile stay and breed.
 */
internal fun HexGroups.keepHerds(g: HexGroup, b: Band, date: Date, time: BandTime) {
    val p = g.place
    // (Herding is field work: its days came out of sowing's time when the band planned its week ([Band.herded]), as the
    // harvest's do; on the days the band walks to a new camp, the herd walks with it.)
    val tended = b.herded
    b.herded = 1f
    // The stock the week's days nobody asked for would keep besides, kg on the hoof.
    var spareKg = time.unheld * HERD_KG_A_HERDER * herdSkill(b) / days
    fun slaughter(k: Int, heads: Float, flow: HerdFlow) {
        val s = fauna.kinds[k]
        bands.herdFlows[flow] += heads
        // (Kept at what it feeds, as the week's meat: [Nourishment.MEAT]; the rest of the carcass for the scavengers.)
        val meat = heads * s.massKg * MEAT_SHARE
        addFood(g.stores, g.storeKgPer, Store.FRESH, meat * Nourishment.MEAT * g.species.needScale, meat)
        small.deposit(DeadPool.CARRION, p, Kg(heads * s.massKg * (1f - MEAT_SHARE)))
    }
    for (k in b.herd.indices) {
        var n = b.herd[k]
        if (n <= 0f) continue
        val s = fauna.kinds[k]
        val tame = b.herdTame[k]
        // Grazing round the camp.
        val f = herdForage(p, s, n)
        val got = land.plants.consume(p, LifeForm.HERB, KgM2(f.grass)) + land.plants.eatBrowse(p, KgM2(f.browse))
        val fed = if (f.need > 0f) min(1f, got.v / f.need) else 1f
        val inSeason = monthsFrom(s.birthMonth, date.month) < s.birthMonths
        val born = if (inSeason) n * 0.5f * s.youngPerFemale * fed * (0.3f + 0.7f * tame) /
            (s.birthMonths * Date.DAYS_PER_MONTH.toFloat() / days) else 0f
        // Its mothers give milk through the lactation after the birth season, as they are fed and as tame as they are.
        val lactating = monthsFrom(s.birthMonth, date.month) * Date.DAYS_PER_MONTH < s.birthMonths * Date.DAYS_PER_MONTH + LACTATION_DAYS
        if (lactating) {
            val milk = n * 0.5f * (0.3f + 0.7f * tame) * tame * fed * milkKg(s) * days / LACTATION_DAYS
            addFood(g.stores, g.storeKgPer, Store.FRESH, milk * Nourishment.MILK * g.species.needScale, milk)
            bands.herdFlows[HerdFlow.MILK_KG] += milk
        }
        val dying = (s.backgroundMortality + 0.5f * (1f - fed)) * years
        val running = HERD_RUN_OFF * (1f - tame) * b.lapse(Skill.TENDING) * years
        val lost = n * min(0.9f, dying + running)
        // (The dead lie by the camp; those that run off or stray go wild among their kind.)
        val died = if (dying + running > 0f) lost * dying / (dying + running) else 0f
        val area = land.areaKm2[p]
        small.deposit(DeadPool.CARRION, p, Kg(died * s.massKg))
        fauna.adults[k][p] += (lost - died) / area
        n = max(0f, n + born - lost)
        bands.herdFlows[HerdFlow.BORN] += born; bands.herdFlows[HerdFlow.DIED] += died; bands.herdFlows[HerdFlow.RAN_OFF] += lost - died
        val kg = n * s.massKg
        bands.herdFlows[HerdFlow.KG] += kg; bands.herdFlows[HerdFlow.KG_TENDED] += kg * tended; bands.herdFlows[HerdFlow.KG_FED] += kg * fed
        // What its herders cannot keep it culls for meat.
        if (tended < 1f) {
            val cull = n * (1f - tended)
            n -= cull
            slaughter(k, cull, HerdFlow.CULLED)
            stats.meeting(Met.KEPT_ANIMALS_CULLED)
        }
        // Herders live on their herds' increase: the band eats what its herd adds in a year, week by week through the year
        // (its births come in one season, its losses all year), and keeps it only where the days nobody asked for would
        // keep it too, since a herd let grow takes its days from the food. A herd whose increase brings a herder less than
        // a day's foraging brings it eats down, a week's food at a time.
        val worth = herdWorth(g, b, k)
        val pays = worth >= 1f
        if (pays) bands.herdFlows[HerdFlow.KG_PAYS] += n * s.massKg
        bands.herdFlows[when { worth < 0.5f -> HerdFlow.KG_WORTH_HALF; worth < 1f -> HerdFlow.KG_WORTH_UNDER; worth < 2f -> HerdFlow.KG_WORTH_OVER; else -> HerdFlow.KG_WORTH_TWICE }] += n * s.massKg
        val increase = n * max(0f, herdIncrease(b, k, fed)) * years
        val grows = pays && increase * s.massKg <= spareKg
        if (grows) spareKg -= increase * s.massKg
        val kept = if (grows) n else if (pays) max(0f, n - increase)
            else max(0f, n - g.dailyNeedKg * days / (s.massKg * MEAT_SHARE * Nourishment.MEAT * g.species.needScale))
        if (n > kept) slaughter(k, n - kept, if (pays) HerdFlow.EATEN else HerdFlow.EATEN_UNPAID)
        n = kept
        if (n < 0.5f) { fauna.adults[k][p] += n / area; n = 0f; bands.herdFlows[if (pays) HerdFlow.ENDED else HerdFlow.ENDED_UNPAID] += 1f }
        b.herd[k] = n
        b.herdTame[k] = tame + TAMING_RATE * b.edge(Skill.TENDING) * years * (1f - tame)
        if (b.knowsSkill[Skill.TENDING]) b.practise(Skill.TENDING, 0.5f * PRACTICE)
    }
}

/**
 * What became of bands' kept animals, for the census ([HexGroups.herdFlows]): heads born, dead, run off or strayed, culled
 * for want of herders, eaten of the herd's increase, eaten down for not paying ([herdPays]), and gone wild with a band that
 * died out ([leaveDead]); milk, kg; and kg on the hoof weighed by the share herded, by the share fed, in herds that pay their
 * herders, and in all (week by week), and by what a herder's day brings against a lean month's foraging day ([herdWorth]:
 * under half, under 1, under 2, more); and herds that came to an end, as they paid, did not, or went wild with their band.
 */
enum class HerdFlow { BORN, DIED, RAN_OFF, CULLED, EATEN, EATEN_UNPAID, FREED, MILK_KG, KG_TENDED, KG_FED, KG_PAYS, KG,
    KG_WORTH_HALF, KG_WORTH_UNDER, KG_WORTH_OVER, KG_WORTH_TWICE, ENDED, ENDED_UNPAID, ENDED_FREED }

/** What [n] kept animals of kind [s] ask of place [p]'s ground this week, and the grass and browse they can get there, kg/m². */
internal class HerdForage(val need: Float, val grass: Float, val browse: Float) {
    val fed get() = if (need > 0f) min(1f, (grass + browse) / need) else 1f
}

/**
 * The forage [n] kept animals of kind [s] find round a camp at [p] in a week: their week's intake ([Species.dailyIntakeKg]),
 * grass and browse in their diet's shares, as far as the place has them (grass to a tenth of the palatable herbage a week).
 * The same reckoning feeds them ([keepHerds]) and weighs a camp for them ([HexGroups.herdLoss]).
 */
internal fun GroupWorld.herdForage(p: Place, s: Species, n: Float): HerdForage {
    val need = land.spread(p, Kg(n * s.dailyIntakeKg * days)).v
    val d = s.diet; val total = (d.grass + d.browse).coerceAtLeast(1e-6f)
    return HerdForage(need, min(need * d.grass / total, land.palatable[p] * land.plants.herb[p] * 0.1f),
        min(need * d.browse / total, land.plants.browse(p)))
}

/** The share of the week [time] its band spent walking to a new camp. */
internal fun walkedShare(time: BandTime) = if (time.total > 0f) min(1f, time.spentOn(Activity.MOVING) / time.total) else 0f

/**
 * Person-days [b]'s herders take a week to keep its animals: herded on foot, a herder keeps about [HERD_KG_A_HERDER] kg of
 * stock on the hoof, and the unpractised take up to twice as long.
 */
internal fun GroupWorld.herdDays(b: Band): Float {
    var kg = 0f
    for (k in b.herd.indices) if (b.herd[k] > 0f) kg += b.herd[k] * fauna.kinds[k].massKg
    return kg / HERD_KG_A_HERDER * days / herdSkill(b)
}

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
    val s = fauna.kinds[k]; val tame = b.herdTame[k]
    val mothers = 0.5f * (0.3f + 0.7f * tame)
    val increase = herdIncrease(b, k)
    val heads = HERD_KG_A_HERDER * herdSkill(b) / s.massKg
    val day = heads * (increase * s.massKg * MEAT_SHARE * Nourishment.MEAT + mothers * tame * milkKg(s) * Nourishment.MILK) *
        g.species.needScale / Date.DAYS_PER_YEAR
    return day / lean
}

/**
 * The share [b]'s herd of kind [k] adds in a year as [keepHerds] has it, fed as far as [fed]: births by its tameness, less
 * its deaths and those that run off.
 */
internal fun GroupWorld.herdIncrease(b: Band, k: Int, fed: Float = 1f): Float {
    val s = fauna.kinds[k]; val tame = b.herdTame[k]
    return 0.5f * (0.3f + 0.7f * tame) * s.youngPerFemale * fed - s.backgroundMortality - 0.5f * (1f - fed) -
        HERD_RUN_OFF * (1f - tame) * b.lapse(Skill.TENDING)
}

/**
 * The milk a mother of kind [s] gives its keepers in a lactation, kg: an indigenous goat milked once a day in the open,
 * 56 kg in 101 days (Hagos, Gizaw and Urge 2017, Begait goats, western Tigray; the kids suckle besides), taken by the
 * mother's weight against its 40 kg (as M^0.75; the scaling an estimate). Early herds' mothers likely gave less.
 */
internal fun milkKg(s: Species) = MILK_KG_GOAT * (s.massKg / 40f).pow(0.75f)
const val MILK_KG_GOAT = 56f
const val LACTATION_DAYS = 101f

/** How much of a practised herder's stock [b]'s herders keep each: half, unpractised. */
private fun herdSkill(b: Band) = 0.5f + 0.5f * min(1f, b.skill(Skill.TENDING))

/**
 * Herding: chance a young animal caught in the hunt is kept and raised instead of eaten (times its
 * kind's tameability); yearly share of the still-wild that run off (an estimate, no source); yearly growth of
 * tameness. (Herded flocks lose next to nothing on the move: flock records of herded sheep and goats count no strays and
 * 0-1% of their outflow to predators, Assen and Aklilu 2012, mixed farms in Tigray, not nomads; so a move costs a herd only
 * its walk.) And the stock a herder keeps on foot, kg on the hoof: about a hundred sheep and goats
 * (Dahl and Hjort 1976, from memory, to check), at 40 kg a head.
 */
const val CAPTURE = 0.5f
const val HERD_RUN_OFF = 0.5f
const val TAMING_RATE = 0.01f
const val HERD_KG_A_HERDER = 4000f
