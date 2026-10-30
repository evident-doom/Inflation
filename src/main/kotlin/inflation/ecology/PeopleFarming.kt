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
internal fun GroupWorld.cropRipe(g: HexGroup, b: Band): Float {
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
internal fun GroupWorld.canSow(g: HexGroup, b: Band, p: Place, date: Date) =
    (b.atHome || b.campWeeks >= houseAfterWeeks(b)) && date.month in SOW_MONTHS && fauna.food.cereal[p] > 0f

/**
 * Packs of kinds that follow people (wolves, jackals) and live by a band's camps grow bolder over
 * generations, the shy ones keeping away; from a bold pack the band now and then takes a pup and
 * raises it. Its dogs eat what lies about the camp and a little of the band's food, breed as they are fed, help it hunt
 * small game and guard its camp.
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
    var dogs = 0f; var need = 0f; var got = 0f
    val p = g.place
    // (A band feeds no more dogs than make a full pack for its hunters ([Band.dogShare]); the rest live on what they find.)
    val fedShare = min(1f, g.adults / 3f / max(1f, b.kept.sum()))
    for (i in b.kept.indices) {
        val k = b.kept[i]
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
        // They whelp as they are fed and die as their kind does by people's camps, and of hunger (as kept herds do).
        val dying = (DOG_DEATHS + 0.5f * (1f - fed)) * years
        val died = k * min(0.9f, dying)
        small.deposit(DeadPool.CARRION, p, Kg(died * s.massKg))
        b.kept[i] = max(0f, k + k * DOG_BIRTHS * fed * years - died)
        dogs += b.kept[i]
    }
    b.dogFed = if (need > 0f) min(1f, got / need) else 1f
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
 * Dogs. Pups raised a dog a year when fed: half are bitches, of one litter a year of about 5 (an estimate, from memory), half
 * of the pups dying as newborns (Koster 2008, Mayangna and Miskito, Nicaragua). Yearly deaths of grown dogs: 49% (Koster
 * 2008; hunting dogs die younger than guard dogs, Pacheco-Cobos and Winterhalder 2021, Belize). The share of their need a
 * well-fed band gives the dogs of a full pack of its own food: 13% of village dogs' food by mass was given them, 87% they found, mostly
 * carrion and human waste (Butler et al. 2018, rural Zimbabwe). How much a full pack adds to the hunt of the smallest game,
 * falling to nothing at [DOG_PREY_KG]: dogs met agouti and paca far more often but red brocket deer no more (Koster 2008),
 * and gains are limited to some prey (Lupo 2017); the size a guess. How much a full pack keeps off night attacks (no source).
 */
const val DOG_BIRTHS = 1.25f
const val DOG_DEATHS = 0.49f
const val DOG_FED_SHARE = 0.13f
const val DOG_HUNT = 1f
const val DOG_PREY_KG = 25f
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
    // The herders the week's days nobody asked for would make besides.
    var spare = time.unheld * herdSkill(b) / days
    // And the herders its leanest month leaves room for, once its fire, shelters and food are had ([Band.freeShare]),
    // less those its herds take now: a herd grows towards what the band could herd in its worst month, and is culled
    // above it, so that it never takes the days that keep its people alive. (Measured from the band's weeks, not from
    // the herd: a capacity measured from the herd itself ratchets down, see 4.64 in [HexSnapshot].) Null till it has
    // planned a week of every month; till then, the spare days alone.
    if (b.herdGrew.size != b.herd.size) b.herdGrew = BooleanArray(b.herd.size)
    if (b.herdPet.size != b.herd.size) b.herdPet = FloatArray(b.herd.size)
    val young = b.youngHerd()
    val herders = b.herd.indices.sumOf { k -> ((b.herd[k] + young[k]) / headsAHerder(fauna.kinds[k])).toDouble() }.toFloat()
    val capacity = b.freeShare.takeIf { f -> f.all { it >= 0f } }?.let { f -> f.min() * time.total / days * herdSkill(b) }
    var room = capacity?.let { it - herders }
    // (Above it, the band culls a month's share of the excess a week, as it eats: not all at once, to rot.)
    val over = if (capacity != null && herders > capacity) (1f - capacity / herders) * min(1f, years * Date.MONTHS_PER_YEAR) else 0f
    fun tally(k: Int, f: HerdFlow, v: Float) { bands.herdFlows[f] += v; bands.kindFlows[k][f] += v }
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
    if (b.herdTaken.size != b.herd.size) b.herdTaken = FloatArray(b.herd.size)
    for (k in b.herd.indices) {
        // (What beasts of prey took from it while they hunted this week: [raidHerd].)
        if (b.herdTaken[k] > 0f) { tally(k, HerdFlow.TAKEN, b.herdTaken[k]); bands.takenWeek[k] += b.herdTaken[k]; b.herdTaken[k] = 0f }
        var n = b.herd[k]; var y = young[k]
        if (n + y <= 0f) continue
        val s = fauna.kinds[k]
        val tame = b.herdTame[k]
        // Grazing round the camp. (The young eat about half what a grown one does, as the residents' do.)
        val f = herdForage(p, s, n + 0.5f * y)
        val got = land.plants.consume(p, LifeForm.HERB, KgM2(f.grass)) + land.plants.eatBrowse(p, KgM2(f.browse))
        val fed = if (f.need > 0f) min(1f, got.v / f.need) else 1f
        val inSeason = monthsFrom(s.birthMonth, date.month) < s.birthMonths
        val born = if (inSeason) n * herdMothers(s, tame) * s.youngPerFemale * fed /
            (s.birthMonths * Date.DAYS_PER_MONTH.toFloat() / days) else 0f
        // Its mothers give milk through the lactation after the birth season, as they are fed and as tame as they are.
        if (lactating(s, date)) {
            val milk = n * herdMothers(s, tame) * tame * fed * milkKg(s) * days / LACTATION_DAYS
            addFood(g.stores, g.storeKgPer, Store.FRESH, milk * Nourishment.MILK * g.species.needScale, milk)
            tally(k, HerdFlow.MILK_KG, milk)
        }
        val dying = (s.backgroundMortality + 0.5f * (1f - fed) + fighting(s)) * years
        val running = straying(s, tame) * b.lapse(Skill.TENDING) * years
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
        y -= up; n += up
        tally(k, HerdFlow.BORN, born); tally(k, HerdFlow.DIED, died + diedY); tally(k, HerdFlow.RAN_OFF, lost - died + lostY - diedY)
        val kg = (n + HexFauna.YOUNG_WEIGHT * y) * s.massKg
        tally(k, HerdFlow.KG, kg); tally(k, HerdFlow.KG_TENDED, kg * tended); tally(k, HerdFlow.KG_FED, kg * fed)
        // (For the census by kind: what it adds in a year, and what a herder's day of it brings against a lean month's foraging.)
        tally(k, HerdFlow.INCREASE_KG, herdIncrease(b, k, fed) * kg)
        // What its herders cannot keep it culls for meat.
        if (tended < 1f) {
            val cull = n * (1f - tended); val cullY = y * (1f - tended)
            n -= cull; y -= cullY
            slaughter(k, cull, HerdFlow.CULLED)
            slaughter(k, cullY, HerdFlow.CULLED, HexFauna.YOUNG_WEIGHT)
            stats.meeting(Met.KEPT_ANIMALS_CULLED)
        }
        // Herders live on their herds' increase: the band eats what its herd adds in a year, week by week through the year
        // (its births come in one season, its losses all year), and keeps it, its females with it, where its leanest
        // month would still herd the herd grown ([room]; till it knows its months, where this week's spare days would). A herd whose increase brings a herder less than
        // a day's foraging brings it eats down, a week's food at a time. Pets ([Band.herdPet]) it keeps whole, for their own
        // sake, and weighs as food only once they have grown.
        val pet = b.herdPet[k] > 0f
        if (pet) { tally(k, HerdFlow.KG_PET, kg); b.herdPet[k] = max(0f, b.herdPet[k] - years) }
        val worth = herdWorth(g, b, k)
        val pays = worth >= 1f
        if (pays) tally(k, HerdFlow.KG_PAYS, kg)
        tally(k, when { worth < 0.5f -> HerdFlow.KG_WORTH_HALF; worth < 1f -> HerdFlow.KG_WORTH_UNDER; worth < 2f -> HerdFlow.KG_WORTH_OVER; else -> HerdFlow.KG_WORTH_TWICE }, kg)
        if (worth.isFinite()) { tally(k, HerdFlow.KG_WORTH_KNOWN, kg); tally(k, HerdFlow.WORTH_KG, worth * kg) }
        if (over > 0f) { slaughter(k, n * over, HerdFlow.EATEN); n -= n * over }
        val increase = n * max(0f, herdIncrease(b, k, fed)) * years
        val grows = pet || pays && increase / headsAHerder(s) <= (room ?: spare)
        if (grows && !pet) { if (room != null) room -= increase / headsAHerder(s) else spare -= increase / headsAHerder(s) }
        b.herdGrew[k] = grows
        val kept = if (grows) n else if (pays) max(0f, n - increase)
            else max(0f, n - g.dailyNeedKg * days / (s.massKg * MEAT_SHARE * Nourishment.MEAT * g.species.needScale))
        if (n > kept) slaughter(k, n - kept, if (pays) HerdFlow.EATEN else HerdFlow.EATEN_UNPAID)
        n = kept
        if (n + y < 0.5f) { fauna.adults[k][p] += n / area; fauna.young[k][p] += y / area; n = 0f; y = 0f; b.herdPet[k] = 0f
            tally(k, if (pet) HerdFlow.ENDED_PET else if (pays) HerdFlow.ENDED else HerdFlow.ENDED_UNPAID, 1f) }
        b.herd[k] = n; young[k] = y
        b.herdTame[k] = tame + TAMING_RATE * b.edge(Skill.TENDING) * years * (1f - tame)
        if (b.knowsSkill[Skill.TENDING]) b.practise(Skill.TENDING, 0.5f * PRACTICE)
    }
}

/**
 * What became of bands' kept animals, for the census ([HexGroups.herdFlows]): heads born, dead, run off or strayed, taken by beasts of prey ([raidHerd]), culled
 * for want of herders, eaten of the herd's increase, eaten down for not paying ([herdPays]), and gone wild with a band that
 * died out ([leaveDead]); milk, kg; and kg on the hoof weighed by the share herded, by the share fed, in herds that pay their
 * herders, and in all (week by week), and by what a herder's day brings against a lean month's foraging day ([herdWorth]:
 * under half, under 1, under 2, more); and herds that came to an end, as they paid, did not, or went wild with their band. By
 * kind only ([Bands.kindFlows]): the yearly increase, and what a herder's day brings, each weighed by kg on the hoof (where
 * the band has foraged a month to weigh it against); kg kept as pets ([Band.herdPet]), and pets' herds that came to an end.
 */
enum class HerdFlow { BORN, DIED, RAN_OFF, TAKEN, CULLED, EATEN, EATEN_UNPAID, FREED, MILK_KG, KG_TENDED, KG_FED, KG_PAYS, KG,
    KG_WORTH_HALF, KG_WORTH_UNDER, KG_WORTH_OVER, KG_WORTH_TWICE, ENDED, ENDED_UNPAID, ENDED_FREED,
    INCREASE_KG, KG_WORTH_KNOWN, WORTH_KG, KG_PET, ENDED_PET }

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
 * Person-days [b]'s herders take a week to keep its animals: herded on foot, a herder keeps [headsAHerder] of each kind,
 * and the unpractised take up to twice as long.
 */
internal fun GroupWorld.herdDays(b: Band): Float {
    var herders = 0f
    for (k in b.herd.indices) if (b.herd[k] > 0f) herders += b.herd[k] / headsAHerder(fauna.kinds[k])
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
    val s = fauna.kinds[k]; val tame = b.herdTame[k]
    val mothers = herdMothers(s, tame)
    val increase = herdIncrease(b, k)
    val heads = headsAHerder(s) * herdSkill(b)
    val day = heads * (increase * s.massKg * MEAT_SHARE * Nourishment.MEAT + mothers * tame * milkKg(s) * Nourishment.MILK) *
        g.species.needScale / Date.DAYS_PER_YEAR
    return day / lean
}

/**
 * What [g]'s band [b] can count on its herds to feed it in the week of [date], in kg of its need as [forage] counts food:
 * the week's share of each herd's yearly increase, which [keepHerds] eats unless the herd kept it to grow last week
 * ([Band.herdGrew]), and the milk of its mothers in milk. The band forages only for the rest of its week's food.
 */
internal fun GroupWorld.herdFoodWeek(g: HexGroup, b: Band, date: Date): Float {
    var food = 0f
    for (k in b.herd.indices) {
        val n = b.herd[k]
        if (n <= 0f) continue
        val s = fauna.kinds[k]; val tame = b.herdTame[k]
        if (!b.herdGrew.getOrElse(k) { false }) food += n * max(0f, herdIncrease(b, k)) * years * s.massKg * MEAT_SHARE * Nourishment.MEAT
        if (lactating(s, date)) food += n * herdMothers(s, tame) * tame * milkKg(s) * days / LACTATION_DAYS * Nourishment.MILK
    }
    return food * g.species.needScale
}

/** Whether mothers of kind [s] are in milk at [date]: through the lactation after the birth season. */
private fun lactating(s: Species, date: Date) =
    monthsFrom(s.birthMonth, date.month) * Date.DAYS_PER_MONTH < s.birthMonths * Date.DAYS_PER_MONTH + LACTATION_DAYS

/**
 * The share [b]'s herd of kind [k] adds in a year as [keepHerds] has it, fed as far as [fed]: births by its tameness, less
 * its deaths and those that run off.
 */
internal fun GroupWorld.herdIncrease(b: Band, k: Int, fed: Float = 1f): Float {
    val s = fauna.kinds[k]; val tame = b.herdTame[k]
    return herdMothers(s, tame) * s.youngPerFemale * fed - s.backgroundMortality - 0.5f * (1f - fed) - fighting(s) -
        straying(s, tame) * b.lapse(Skill.TENDING)
}

/**
 * The share of a kept herd of kind [s] that bear young in a year, [tame] as it is: its females, as tame as they are, and as
 * far as their kind mates and rears young in a camp without pens or incubators (Diamond's captive-breeding criterion)
 * against [HERD_BREEDS], a goat's. No more than their kind's wild rate: a herd does not out-breed its kind.
 */
internal fun herdMothers(s: Species, tame: Float) = 0.5f * (0.3f + 0.7f * tame) * min(1f, s.domestication[2] / HERD_BREEDS)

/**
 * The years young of kind [s] caught in the hunt are kept as pets ([Band.herdPet]): till they have grown
 * ([Species.yearsToMaturity]), or as much sooner as their kind's temper (Diamond's disposition criterion) falls short of a
 * goat's, [HERD_TEMPER]: nobody keeps a pet that bites or wrecks the camp, and the temper shows as the young grow.
 */
internal fun petYears(s: Species) = s.yearsToMaturity * min(1f, s.domestication[3] / HERD_TEMPER)

/**
 * The yearly share of a kept herd of kind [s] that kill or maim each other or are put down as dangerous, however tame:
 * [HERD_FIGHTING] times the square of how far its kind's temper (Diamond's disposition criterion) falls short of a goat's,
 * [HERD_TEMPER]; without pens the keepers cannot part the fighters.
 */
internal fun fighting(s: Species): Float {
    val wild = max(0f, 1f - s.domestication[3] / HERD_TEMPER)
    return HERD_FIGHTING * wild * wild
}

/**
 * The yearly share of a kept herd of kind [s] that strays, [tame] as it is: [HERD_RUN_OFF] times the square of how far the
 * herd falls short of being kept on foot, its tameness times the kind's ease on foot (the worse of Diamond's panic and
 * social-structure criteria) against [HERD_EASE], a goat's.
 */
internal fun straying(s: Species, tame: Float): Float {
    val ease = min(s.domestication[4], s.domestication[5])
    val wild = max(0f, 1f - tame * ease / HERD_EASE)
    return HERD_RUN_OFF * wild * wild
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
 * kind's tameability); yearly share that run off, [HERD_RUN_OFF] times the square of their wildness ([straying]; the square an
 * estimate, so that the wilder stray far more), their wildness reckoned from their tameness times their kind's ease on foot, the
 * worse of Diamond's panic and social-structure criteria, against a goat's ([HERD_EASE] 0.6, an estimate: a kind that panics or
 * keeps no herd, as an ostrich or a deer, is not kept on foot however tame), so that a goat herd as tame as one at world
 * start (0.8) loses 2% a year to straying (an estimate: no record gives half-tame herds' straying; the wild-type stock of the first
 * herds, as at Ganj Dareh some 10,000 years ago, still bred and paid its herders, Daly et al. 2021, from the press release).
 * Tame herded flocks stray not at all, as [straying] has it: flock records of herded sheep and goats count no strays and
 * 0-1% of their outflow to predators, Assen and Aklilu 2012, mixed farms in Tigray, not nomads; so a move costs a herd only
 * its walk. Beasts of prey take their share in the hunt ([raidHerd]), not here: the goats of central Mali, 1978-83, whose
 * adults died 12.7% a year, slightly more than 17% of the deaths "lost" or taken by predators, mainly dogs (FAO x5531e,
 * ch. 13), are a check on the two together. [HERD_FIGHTING], the same scale for deaths from fighting ([fighting]);
 * yearly growth of tameness. And the head a herder keeps on foot ([headsAHerder]): 200 goats of [GOAT_KG]; Iranian nomads' flocks of
 * 200-600 head go with one to three shepherds (Encyclopaedia Iranica, "Cupan"). Bigger beasts fewer, as their weight to the
 * power [HERD_MASS_EXP], an estimate between the goats and camel herds of 50-60 head kept by young men (ODI Pastoral
 * Development Network paper 5337; the herders to a herd not given). Young caught and raised by hand come as tame as a
 * herd started at world start ([RAISED_TAME], an estimate), whatever their kind: young of wild hoofed animals reared by people take them as
 * their own and stay by them (imprinting; Price 1984, "Behavioral aspects of animal domestication", from memory), long
 * before their kind changes in body. Kinds also differ in how they breed and how they get on kept without pens or incubators,
 * by the other two of Diamond's criteria (Guns, Germs, and Steel, ch. 9, from memory): their young as their captive breeding
 * against a goat's ([HERD_BREEDS] 0.7, [herdMothers]), their deaths from fighting by their disposition against a goat's
 * ([HERD_TEMPER] 0.6, [fighting]); the scales and the square estimates, the criteria the catalogue's.
 */
const val CAPTURE = 0.5f
const val RAISED_TAME = 0.8f
const val HERD_RUN_OFF = 0.5f
const val HERD_FIGHTING = 0.5f
const val HERD_EASE = 0.6f
const val HERD_BREEDS = 0.7f
const val HERD_TEMPER = 0.6f
const val TAMING_RATE = 0.01f
const val HERD_GOATS_A_HERDER = 200f
const val GOAT_KG = 40f
const val HERD_MASS_EXP = 0.5f
