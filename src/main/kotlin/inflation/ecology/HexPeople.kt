package inflation.ecology

import inflation.EnumFloats
import inflation.world.get
import inflation.world.set
import inflation.ecology.HexSmallLife.DeadPool
import inflation.Books
import inflation.Kg
import inflation.KgM2
import inflation.min
import inflation.world.Date
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import inflation.world.Weather
import java.util.stream.IntStream
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

// What makes bands of people more than a pack: skills learned in practice, tools made from what the
// country gives, and camps they stay in.

/** Share of the success an untrained band still has at a hunt or a harvest. */
const val CRAFT_FLOOR = 0.3f
/**
 * Hunting, tracking and stalking are learned over years, not weeks: Kaplan et al. 2000 have Hiwi boys at 16% of the adult
 * return at 10, 50% at 20 and the peak at 30-35, Ache boys at 25% at 20 and the peak in the mid-30s. At complexity 1.5 this is
 * about 0.0015 of the gap a week: half way in about 9 years of weekly practice, 90% in about 29. A guess between the two
 * curves (adults relearning plausibly go faster than the boys of the record); Olli's call. Forgetting slows in step.
 */
const val HUNTING_PRACTICE = 0.0022f
/** Hunting success of a band without spears, against one with them; what bows add against fast quarry. */
const val WEAPONLESS = 0.3f
const val BOW_GAIN = 0.5f
/**
 * What a child (any under adulthood, infants included) adds to its band's gathering, against a grown hand: children
 * of five and up gather fruit, small tubers and finds near camp, and Hadza children get some half of their own food
 * in a good season (from memory, Blurton Jones, Hawkes and O'Connell), about a sixth of an adult's take for the
 * children as a whole.
 */
const val CHILD_GATHER = 0.15f
/**
 * The share of a band's hands that go out hunting while the rest gather, fish and snare, at the scaffold's effort on
 * big game: the men. In nearly every foraging people men do most of the hunting and women most of the gathering
 * (Marlowe 2007, Cross-Cultural Research 41: 170-195), and about half the grown are men. The hunters search on their
 * own, all the days they are out, so meeting game does not wait on the gatherers' handling of plant food.
 */
const val HUNTERS_SHARE = 0.5f
/** The share of [band]'s hands that hunt this week: [HUNTERS_SHARE] at the scaffold's effort on big game, all at twice it. */
internal fun huntersShare(band: Band) = (HUNTERS_SHARE * band.effort[Food.BIG_GAME]).coerceIn(0f, 1f)
/** Extra small game and fish with nets and snares. */
const val NET_GAIN = 1f
/** Most of each kind of plant food in a place a band can gather in a week, at full skill and without tools. */
const val GATHER_SHARE = 0.5f
/**
 * The insulation of a whole kit of furs and hides sewn by a master, clo: half of a heavy modern cold-weather ensemble's
 * about 3 clo, since hides wrapped and sewn by hand leave gaps at the neck, wrists and feet and lack layered underwear,
 * hoods, mittens and boots (a judgement, Olli 2026-10-22; no measured clo of skin clothing was found: the Inuit's tailored
 * caribou-skin outfits held skin warmth as well as modern expedition clothing, Climate Research 5: 83-90, 1995, and Hill
 * et al. 2020, Arctic 73(1), but those are the most refined skin clothing known). Bands start with half a kit, sewn by
 * novices: about 0.5 clo.
 */
const val CLOTHES_CLO = 1.5f
/** Km of a week's camp move at which the ways' input for it ([BandInputs.MOVE_KM]) is half way to 1. */
const val MOVE_KM_HALF = 20f

/**
 * The spacing of a mother's births, years, as pregnancy ([GESTATION_YEARS]), the wait to conceive again once she is fertile
 * ([CONCEIVE_YEARS]: months at a monthly chance of about a sixth, with early losses; Bongaarts and Potter 1983, from memory), and
 * the months nursing keeps her from it: [NURSING_YEARS_MOST] where a child lives on the breast till it eats what grown people
 * eat (the !Kung spaced births about four years, Howell 1979, from memory), down to [NURSING_YEARS_LEAST] where soft food
 * weans it early (traditional farmers' months without ovulation, about 12 to 18, Bongaarts and Potter 1983, from memory).
 * The base rate ([HUMAN_BIRTHS]) is the first spacing's, [SPACING_MOST].
 */
const val GESTATION_YEARS = 0.75f
const val CONCEIVE_YEARS = 0.7f
const val NURSING_YEARS_MOST = 2.55f
const val NURSING_YEARS_LEAST = 1.25f
const val SPACING_MOST = GESTATION_YEARS + CONCEIVE_YEARS + NURSING_YEARS_MOST
/**
 * Share of a band's food its weanlings ask, the seed that weans them all as gruel (an estimate: under-fours about a sixth of
 * a forager band, Howell 1979, from memory, at about a third of a grown share).
 */
const val WEANING_SHARE = 0.05f
/**
 * A mother carries a child on the band's moves till it walks them, about four years old (Lee 1979; the backload, Blurton
 * Jones 1986, both from memory): a mother of a band that moves camp as often as the !Kung, about [CARRY_MOVES] times a year
 * (Kelly 1995, from memory), spaces her births so; one that moves less, the less (in proportion: an estimate).
 */
const val CARRY_YEARS = 4f
const val CARRY_MOVES = 5f
/** People's deaths of thirst against an animal's at the same days without water. */
const val HUMAN_THIRST = 0.3f
/** How much more thirst troubles people on ground they do not know (they look for water where animals and birds lead them). */
const val STRANGER_THIRST = 2f
/**
 * In a lean week a child goes without this much less of its share of the band's shortfall than a grown one: parents feed
 * their children before themselves (a drive found in every forager society, Hawkes, O'Connell and Blurton Jones on Hadza
 * sharing, from memory: to check; the size is a guess). The food is only moved, not made: the grown go without the more.
 */
const val CHILDREN_FIRST = 0.5f
/** People's births against the catalogue's rate: long nursing spaces births three and a half to four years apart. */
const val HUMAN_BIRTHS = 0.8f

/**
 * A band's week at camp: it keeps the camp or moves on (leaving what it cannot carry), gathers
 * materials from the country, makes and mends its tools, now and then hits on a new one, and its
 * skills fade a little where unused.
 */
internal fun HexGroups.bandWeek(g: HexGroup, weather: Weather, date: Date) {
    val b = g.band ?: return
    b.weeks++
    learnCountry(g, b, weather)
    val p = g.place
    val h = hw.placeHex[p]
    val area = land.m2(p)
    // The days its week's plan gives each chore ([planWeek]) go first to caching what it leaves at a camp, then to gathering
    // what it lacks of its materials, then to making and mending; what a chore leaves is free for those after it.
    val time = b.week ?: BandTime(b.spareDays, b.paces())
    b.week = null
    campAndCache(g, b, p, h, weather, date, time)
    time.release(Activity.CACHING)
    keepAnimals(g, b)
    keepHerds(g, b, date, time)
    val people = g.adults.toFloat()
    gatherMaterials(g, b, p, h, people, area, time)
    time.release(Activity.MATERIALS)
    val living0 = b.livingGoods()
    wearAndInvent(b, years)
    // Making and mending takes the time still to spare, the tools it ranks first first (the
    // scaffold: the most worn); each as far as the time, the scarcest material and the skill allow.
    dialUse?.amount(DialSite.SHELTERS, b.shelterWill, b.shelterWill, 1f)
    if (WARM_DIAG) { b.diagShelterHeld = time.heldFor(Activity.SHELTER); b.diagShelterLeft = time.leftFor(Activity.SHELTER) }
    putUpShelter(g, b, people, time)
    keepCustoms(b, people, time)
    trainHolders(g, b, time)
    time.release(Activity.TRAINING)
    makeTools(b, people, time)
    time.release(Activity.TOOLS)
    b.renown *= 1f - RENOWN_FADE * years
    b.unlucky?.let { u -> b.unlucky = if (u.weeks > 1) Unlucky(u.hex, u.weeks - 1) else null }
    b.sinceRaided++
    plotRaid(g, b, weather)
    keepFire(g, b)
    b.fire *= b.hearthWood
    val woodBefore = b.goods[Material.WOOD]
    val nightBurnt = burnNightFire(b, people)
    dialUse?.amount(DialSite.NIGHT_FIRE_WOOD, b.nightFire, nightBurnt, min(NIGHT_WOOD * people, woodBefore))
    // With the time still to spare, a band that has kept its camp a while builds stone-footed houses
    // there, as far as it has gathered the stone and timber (and starts afresh where it settles
    // anew); houses it has left weather away.
    useStructures(g, h)
    buildHouse(g, b, p, h, people, time)
    time.release(Activity.HOUSES)
    // (Its home is where it last had houses to use; what stands there still shelters it, unless another band took it up.)
    if (housesAt(g, h) > 0.05f) b.houseHex = h
    b.house = if (b.houseHex.none) 0f else housesAt(g, b.houseHex)
    b.atHome = b.houseHex == h && b.house > 0.05f
    b.homeEarth = if (b.atHome) earthAt(g, h) else 0f
    b.homeWalls = if (b.atHome) wallsAt(g, h) else 0f
    b.homeGroundC = if (b.atHome) (0 until Date.MONTHS_PER_YEAR).sumOf { weather.usualTemperature(it, p).toDouble() }.toFloat() / Date.MONTHS_PER_YEAR else 0f
    // With time still to spare, it makes ornaments of shell and bone: they are given, worn and buried.
    makeOrnaments(b, people, time)
    time.release(Activity.ORNAMENTS)
    // In autumn a band at home, or long at its camp, sows a share of its stored grain there: a plot that ripens
    // next summer to several times as much, of the kind of ears it sowed.
    harvestCrop(g, b, time)
    sowGrain(g, b, p, date, time)
    time.release(Activity.SOWING)
    // A band at home that has been raided in the last year rings its houses with a palisade, as far
    // as its time and timber go.
    buildPalisade(g, b, h, people, time)
    b.palisade = if (b.houseHex.none) 0f else palisadeAt(g, b.houseHex)
    // Where fish crowd the river by its camp, it builds a weir across it with traps, or mends the one there, as far as its
    // time and timber go.
    buildWeir(g, b, h, time)
    // What no chore took is its leisure. (Counted once every chore has had its time.)
    time.noneBelowZero()
    b.spareDays = time.left
    countSqueeze(b, time)
    time.spend(Activity.LEISURE, time.left)
    time.countEach(stats.hunting)
    time.count(stats.hunting, Tally.TimeTotal.LEFT, time.left)
    time.count(stats.hunting, Tally.TimeTotal.LOST, time.lost)
    // (And by whether it keeps a herd, tenths of a day: where a herding band's days go.)
    val herding = bandHerding(b, g.adults)
    for (a in Activity.entries) stats.hunting.merge(Tally.bandsHerdingTime(herding, a), (10f * time.spentOn(a)).roundToInt(), Int::plus)
    stats.hunting.merge(Tally.bandsHerdingTime(herding, Tally.TimeTotal.LOST), (10f * time.lost).roundToInt(), Int::plus)
    // (The wood burnt through the night goes to the air; what went into tools, houses, palisades and ornaments
    // is not kept as matter, and they do not go back to the ground as they wear: a known leak.)
    land.books?.let { bk ->
        bk.flow(Books.Stuff.MATTER, "burnt", -nightBurnt.toDouble())
        bk.leak(Books.Stuff.MATTER, "goods made into tools, houses and ornaments, not kept", (b.livingGoods() - living0 + nightBurnt).toDouble())
    }
    // A band carries only so much: the rest is left behind (and goes back to the ground); at a camp it
    // keeps, it stacks building stone and timber beyond that.
    leaveExcess(b, p, people, area)
    // Knowledge fades fast unused, unless songs and stories keep it; hand skills last for years.
    forget(b)
    // Teaching the young keeps the teachers practised; the songs and stories are kept up where the band holds to them.
    if (g.young > 0) b.practise(Skill.SHOWING, 0.08f * PRACTICE)
    b.practise(Skill.LORE, (if (b.holds(Tradition.ORAL_TRADITION)) 0.08f else 0.03f) * PRACTICE)
}

/**
 * The person-days [g]'s band [b] would like this week for each of its chores ([BandTime.plan]), into [asks]: each worked
 * out by the chore's own rules, as things stand when it plans (it moved camp, if it did, but has not yet foraged).
 * Drying and skinning follow the catch, so they ask what they would have liked last week.
 */
internal fun HexGroups.choreAsks(g: HexGroup, b: Band, date: Date, asks: EnumFloats<Activity>, warm: EnumFloats<Activity>? = null) {
    val p = g.place; val h = hw.placeHex[p]; val people = g.adults.toFloat()
    val moved = movedCamp(g, b)
    fun ask(a: Activity, days: Float) { asks[a] = if (days.isFinite()) max(0f, days) else 0f }
    if (moved && !b.campPlace.none) ask(Activity.CACHING, min(1f, b.cacheWill) * leftBehindKg(g, b) * cacheCare(b) / CACHE_KG_A_DAY)
    ask(Activity.MATERIALS, gatherMaterials(g, b, p, h, people, land.m2(p), null))
    // (What the shelters it aims at take beyond the days already given them first, when given: [survivalDays].)
    ask(Activity.SHELTER, shelterLack(b, moved) * shelterDays(b, people, woodCover(p)) - (warm?.get(Activity.SHELTER) ?: 0f))
    ask(Activity.CUSTOMS, cleanDays(b, people) + nurseDays(b, people))
    ask(Activity.TRAINING, Skill.entries.sumOf { s -> (learners(b, s, people) * APPRENTICE_DAYS * s.complexity).toDouble() }.toFloat())
    ask(Activity.TOOLS, makeTools(b, people, null))
    ask(Activity.ORNAMENTS, ornamentsWanted(b, people) * ORNAMENT_DAYS)
    // (A band that has just moved builds, sows and fortifies nothing where it has come to.)
    if (!moved) {
        ask(Activity.HOUSES, buildHouse(g, b, p, h, people, null))
        if (canSow(g, b, p, date)) ask(Activity.SOWING, g.stores[Store.SEED] * SOW_SHARE * b.sowWill * SOW_DAYS_PER_KG)
        if (fortifies(b)) ask(Activity.PALISADE,
            minOf(1f - palisadeAt(g, h), b.goods[Material.WOOD] / (PALISADE_WOOD * people)).coerceAtLeast(0f) * PALISADE_DAYS * people)
        ask(Activity.WEIRS, weirAsk(g, b, h))
    }
    // (Its own crop, ripe, it brings in wherever it has come from: the field work is sowing's time.)
    cropRipe(g, b).takeIf { it > 0f }?.let { asks[Activity.SOWING] += it * reapDaysPerKg(b, tuning) }
    ask(Activity.DRYING, b.dryAsk)
    ask(Activity.SKINS, b.skinAsk)
}

/**
 * What a band that has died out leaves where it was: its food and the materials that rot go back to the ground,
 * its cache with them, and its herd goes wild among the animals of its kind there.
 */
internal fun HexGroups.leaveDead(g: HexGroup) {
    val b = g.band ?: return
    val p = g.place
    val rots = ROTS.sumOf { b.goods[it].toDouble() }.toFloat()
    small.deposit(DeadPool.LITTER, p, Kg(massOf(g.stores, g.storeKgPer) + rots))
    for (i in Store.entries) g.stores[i] = 0f
    for (m in ROTS) b.goods[m] = 0f
    dropCache(b)
    val area = land.areaKm2[p]
    for (k in b.herd.indices) if (b.herd[k] > 0f) {
        fauna.adults[k][p] += b.herd[k] / area; bands.herdFlows[HerdFlow.FREED] += b.herd[k]; bands.herdFlows[HerdFlow.ENDED_FREED] += 1f
        b.herd[k] = 0f; b.herdTame[k] = 0f
    }
}

/** How much a band is put off country it does not know (beside the pull of better country it senses); hexes round its first camp it knows from the start. */
const val UNKNOWN_COUNTRY = 0.2f
const val KNOWN_AT_START = 3

/** How strongly people's camps draw bold animals of kinds that follow people (refuse, scraps, warmth). */
const val CAMP_LURE = 0.3f

/** People per km² around a camp (its hex and the six round it) at which crowding brings sickness; yearly deaths from it then. */
const val PEOPLE_FULL_KM2 = 0.5f
const val PEOPLE_SICK = 0.012f
/** Outbreaks start among people at this share of the rate for animals of the same crowding (small bands keep few diseases going). */
const val PEOPLE_OUTBREAK = 0.3f
/** How much a cave or rock shelter adds to a camp's worth (dry, warm, easy to defend). */
const val CAVE_CAMP = 0.5f
/** Yearly deaths from vipers and scorpions among people, in the warm months. */
const val VENOM = 0.002f
/** Yearly deaths from marsh fever among people camped by a marsh in the warm months. */
const val MARSH_FEVER = 0.05f

/** How [b] lives, for the reports: settled at home in its houses or at a camp kept half a year or more, else mobile. */
/** Whether [b], [hands] strong, keeps a herd and how much of its days the herd asks, for the census ([Tally.Herding]). */
internal fun GroupWorld.bandHerding(b: Band, hands: Int): Tally.Herding {
    if (b.herd.none { it > 0f }) return Tally.Herding.NOT_HERDING
    val load = herdDays(b) / (days * hands.coerceAtLeast(1)).toFloat()
    return Tally.Herding.entries.first { it != Tally.Herding.NOT_HERDING && load < it.below }
}
internal fun GroupWorld.bandLiving(b: Band) =
    if (b.atHome || b.campWeeks >= stepsPerYear / 2f) Tally.Living.SETTLED else Tally.Living.MOBILE

/**
 * [b]'s births against those of a band without weaning food ([SPACING_MOST] over its spacing): gruel weans children early
 * ([Band.weaningFood]), but a mother carries each child on the band's moves till it walks them ([CARRY_YEARS]).
 */
internal fun GroupWorld.birthSpacing(b: Band): Float {
    val nursing = NURSING_YEARS_MOST - (NURSING_YEARS_MOST - NURSING_YEARS_LEAST) * b.weaningFood
    // (A band at home or long at one camp moves the less: the weeks it has kept this camp count as well as its usual stay.)
    val moves = stepsPerYear / max(b.stayWeeks, b.campWeeks + 1f)
    val carried = CARRY_YEARS * min(1f, moves / CARRY_MOVES)
    return SPACING_MOST / max(GESTATION_YEARS + CONCEIVE_YEARS + nursing, carried)
}

/**
 * How far [b] can make its seed into gruel, soft food to wean a child on: ground to meal ([Tool.GRINDER], too heavy to take
 * along, so a band that moves camp leaves its querns behind) and boiled with hot stones, in baskets. (Whole seed boiled is
 * no weanling's food, as raw seed is poor food for anyone: [seedValue].)
 */
internal fun Band.gruel(): Float =
    if (knowsSkill[Skill.STONE_BOILING]) fire * skill(Skill.STONE_BOILING) * max(has(Tool.BASKET), 0.5f) * has(Tool.GRINDER) else 0f

/**
 * Adds [value] (its worth as food) of kind [st], weighing [kg], to [stores], whose kg for each unit of worth are [kgPer].
 * (Stores keep the kg their food weighs beside its worth as food: grinding, cooking and leaching change its worth, not
 * its weight, so what spoils, is cached, given or eaten moves its true weight, each kind keeping its own worth.)
 */
internal fun addFood(stores: EnumFloats<Store>, kgPer: EnumFloats<Store>, st: Store, value: Float, kg: Float) {
    if (value > 0f) kgPer[st] = (stores[st] * kgPer[st] + kg) / (stores[st] + value)
    stores[st] += value
}

/** The kg [stores] weigh, at [kgPer] for each unit of worth. */
internal fun massOf(stores: EnumFloats<Store>, kgPer: EnumFloats<Store>): Float {
    var sum = 0f; for (st in Store.entries) sum += stores[st] * kgPer[st]; return sum
}

/**
 * Moves [value] of [from]'s food to [to] (a gift, a raid, a split): each kind by its share, with its weight. */
internal fun moveFood(from: HexGroup, to: HexGroup, value: Float) {
    val total = from.larder
    if (total <= 0f || value <= 0f) return
    val share = min(1f, value / total)
    for (st in Store.entries) {
        val x = from.stores[st] * share
        from.stores[st] -= x; addFood(to.stores, to.storeKgPer, st, x, x * from.storeKgPer[st])
    }
}

/** Days of its own need [larder][HexGroup.larder] holds. */
internal fun HexGroup.larderDays() = larder / max(1e-3f, dailyNeedKg)
/** A larder of this many days counts as much as a week fully fed, in how well off a band is ([wellOff]). */
const val WELL_STOCKED_DAYS = 30f
/** How well off a group is: how fed this week, and how many months of need it has on hand. */
internal fun HexGroup.wellOff() = fed + larderDays() / WELL_STOCKED_DAYS

/** [g] eats [value] of its stores, what spoils first first; returns the kg it ate. */
internal fun eatStores(g: HexGroup, value: Float): Float {
    var need = value; var kg = 0f
    for (st in EAT_ORDER) { val e = min(need, g.stores[st]); g.stores[st] -= e; need -= e; kg += e * g.storeKgPer[st] }
    return kg
}

/** The scaffold's store: days of food a band aims to keep against bad weeks, besides its lean months ([Band.leanDays]); share of the gap it works to close a week. */
const val STORE_AIM_DAYS = 30f
const val STORE_FILL = 0.25f
/** Person-days to dry or smoke a kilo of meat or fish (cutting it thin, tending the racks and fire), at full skill. */
const val DRY_DAYS_PER_KG = 0.1f

/**
 * What a band knows of itself this week, for its ways: the season, how fed it is and what it has on
 * hand, its people, skills and tools, the warmth, its camp and its spare time.
 */
internal fun HexGroups.bandInputs(g: HexGroup, weather: Weather, into: FloatArray) {
    val b = g.band ?: return
    val x = BandInputs
    val angle = 2.0 * Math.PI * weather.date.dayOfYear / Date.DAYS_PER_YEAR
    into[x.YEAR_SIN] = sin(angle).toFloat(); into[x.YEAR_COS] = cos(angle).toFloat()
    into[x.FED] = g.fed
    val total = g.larder
    into[x.LARDER] = g.larderDays() / WELL_STOCKED_DAYS
    for (st in Store.entries) into[x.STORES + st.ordinal] = if (total > 0f) g.stores[st] / total else 0f
    into[x.ADULTS] = g.adults / 30f; into[x.YOUNG] = g.young.toFloat() / max(1, g.adults)
    for ((k, f) in Skill.FORAGING.withIndex()) into[x.SKILLS + k] = b.skill(f)
    for (t in Tool.entries) into[x.TOOLS + t.ordinal] = b.has(t)
    into[x.TEMPERATURE] = weather.temperature[g.place] / 30f
    into[x.CAMP_WEEKS] = min(1f, b.campWeeks / (stepsPerYear / 2f))
    into[x.SPARE_DAYS] = b.spareDays / max(1f, g.adults * days.toFloat())
    // Its camp's ground: how long it has gone short of water, the wood for its fire, the cover about it, the snow.
    into[x.DAYS_DRY] = min(1f, g.dry / g.species.daysWithoutWater)
    into[x.FIREWOOD] = woodCover(g.place)
    into[x.COVER] = land.plants.cover(g.place)
    into[x.SNOW] = min(1f, weather.snowpack[g.place] / g.species.snowLimitMm)
    // Its health: the sick with each illness, the wounded and lamed, its old; and those beasts took lately.
    for (k in Illness.entries) into[x.SICK + k.ordinal] = b.sick[k]
    into[x.WOUNDED] = b.hurt; into[x.LAMED] = b.lame
    into[x.ELDERS] = b.elders / g.size.coerceAtLeast(1).toFloat()
    into[x.TAKEN_BY_BEASTS] = min(1f, b.beastLoss * 10f)
    // The beasts of prey about its camp, as its tracking reads their sign.
    val beasts = monthBeastKg[g.place] / land.areaKm2[g.place]
    into[x.BEASTS] = SIGN_GUESS + b.skill(Skill.TRACK) * (beasts / (beasts + 3f) - SIGN_GUESS)
    // The bands about it (in its hex and the next): friends, strangers and those it holds a grudge
    // against, and how sick they are; how lately it was raided, and whether it is set on a raid.
    var friends = 0; var strangers = 0; var foes = 0; var theirSick = 0f; var near = 0
    val here = hw.placeHex[g.place]
    for (d in -1 until 6) {
        val h = if (d < 0) here else hex.neighbour(here, d)
        if (h.none) continue
        for (o in bands.bandIndex[h] ?: continue) {
            if (o === g) continue
            val ob = o.band ?: continue
            val t = b.tie(o.id)
            if (t > 0.3f) friends++ else if (t < 0f || b.grudge[ob.mainLineage()] > 0.5f) foes++ else strangers++
            theirSick += ob.sick.sum(); near++
        }
    }
    into[x.FRIENDS] = min(1f, friends / 3f); into[x.STRANGERS] = min(1f, strangers / 3f); into[x.FOES] = min(1f, foes / 3f)
    into[x.THEIR_SICKNESS] = if (near > 0) min(1f, theirSick / near) else 0f
    into[x.RAIDED] = 1f - min(1f, b.sinceRaided / stepsPerYear)
    into[x.ON_A_RAID] = if (!b.raidHex.none) 1f else 0f
    into[x.INBRED] = min(1f, b.inbred * 4f)
    // The favours owed to it and that it owes, all told (in weeks of food: a few weeks' worth is a lot).
    var owed = 0f; var owes = 0f
    for (v in b.favours.values) if (v > 0f) owed += v else owes -= v
    into[x.OWED] = owed / (1f + owed); into[x.OWES] = owes / (1f + owes)
    // Its customs, and its temperament.
    for (t in Tradition.entries) into[x.CUSTOMS + t.ordinal] = if (b.holds(t)) 1f else 0f
    for (k in 0 until Temperament.AXES) into[x.TEMPER + k] = g.temper.getOrElse(k) { 0f }
    ecosystemInputs(g, b, into)
    // Its people's cold where it stands: what the week's cold would cost in food and lives, and what keeps it off.
    val air = airAt(g.place, weather); val cold = b.warmth(air, b.fire, b.homeShelter(), days)
    into[x.COLD_FOOD] = min(1f, cold.extra); into[x.COLD_ODDS] = min(1f, 10f * cold.odds)
    into[x.WIND] = min(1f, air.windMs / 10f); into[x.WET] = air.wet
    into[x.FIRE_SHARE] = b.fire; into[x.HOME_SHELTER] = b.homeShelter()
    // Its salt: the share of its people's sodium it lacked last week, and the salt to be had where it stands (a pan, the shore).
    into[x.SODIUM_SHORT] = b.sodiumShort
    into[x.SALT_HERE] = if (hw.coast[hw.placeHex[g.place]]) 1f else min(1f, 5f * land.salt[g.place])
}

/** The beasts of prey a band tells apart: those that take people ([Species.takesPeople], from the catalogue) and those that do not. */
enum class BeastClass(val label: String) {
    OTHERS("that leave people"), TAKERS("that take people");
    companion object { fun of(s: Species) = if (s.takesPeople > 0f) TAKERS else OTHERS }
}

/**
 * What the ground about a band's camp holds, for its ways: the water and the fish in it, the plants and the food they bear,
 * the game, small game and carrion, how the game is changing, and the beasts of prey by whether they take people. Food is read as weeks of the
 * band's need that a week's search or gathering would bring in, x / (1 + x). All from the places' own arrays.
 */
private fun HexGroups.ecosystemInputs(g: HexGroup, b: Band, into: FloatArray) {
    val x = BandInputs
    val p = g.place; val s = g.species
    val h = hw.placeHex[p]
    val drink = land.drink
    into[x.NEAR_RIVER] = drink.nearRiver[p]; into[x.NEAR_LAKE] = drink.nearLake[p]; into[x.NEAR_SPRING] = drink.nearSpring[p]
    into[x.ON_COAST] = if (hw.coast[h]) 1f else 0f
    into[x.WATERLOGGED] = min(1f, land.waterlogged[p])
    into[x.TREES] = min(1f, land.plants.tree[p] / 10f); into[x.SHRUBS] = min(1f, land.plants.shrub[p] / 2f); into[x.GRASS] = min(1f, land.plants.herb[p] / 0.5f)
    val need = (g.dailyNeedKg * days).coerceAtLeast(1f)
    fun weeks(kg: Float) = (kg / need).let { it / (1f + it) }
    into[x.FISH] = weeks(fauna.water.bandCatchWithin(h, b.fishing()) + fauna.water.weirWithin(h, weirAt(g, h)))
    // Plants: what its gatherers would strip from the camp's own ground in a week (as in [forage]).
    val food = fauna.food
    val pick = min(0.9f, b.gatherShare(false, tuning) * b.gatherRange)
    val reap = min(0.9f, b.gatherShare(true, tuning) * b.gatherRange)
    into[x.FRUIT] = weeks(land.total(p, KgM2(pick * food.fruit[p])).v)
    into[x.ROOTS] = weeks(land.total(p, KgM2(pick * food.underground(p))).v)
    into[x.SEED] = weeks(land.total(p, KgM2(reap * food.seeds[p])).v)
    // Game: what it could catch in a week of searching its swept ground, as its move scores it.
    val swept = sweptKm2(s) * CATCH
    into[x.GAME] = weeks(preyHere(s, p) * swept)
    into[x.SMALL_GAME] = weeks(small.smallPreyKg(p) * swept)
    into[x.CARRION] = weeks(KgM2(small.carrion[p]).perKm2.v * swept)
    into[x.GAME_TREND] = gameTrend(p)
    val sign = b.skill(Skill.TRACK)
    for (c in BeastClass.entries) {
        val beasts = monthBeastClassKg[c.ordinal][p] / land.areaKm2[p]
        into[x.PREDATORS + c.ordinal] = SIGN_GUESS + sign * (beasts / (beasts + 3f) - SIGN_GUESS)
    }
    into[x.ELEVATION] = (hw.placeElevationM[p] / 3000f).coerceIn(0f, 1f)
    into[x.LEAN_LOST] = b.leanLost
    val adults = max(1, g.adults) * GOODS_PER_PERSON
    for (m in Material.entries) into[x.GOODS + m.ordinal] = b.goods[m] / adults
    val km = max(0, g.path.size - 1) * hex.hexKm.toFloat()
    val walk = moveDays(g, km)
    into[x.MOVE_KM] = km / (km + MOVE_KM_HALF); into[x.MOVE_DAYS] = walk / (walk + days); into[x.LOAD] = carriedKg(g, b) / (CARRY_KG * (1f + BASKET_GAIN))
    for ((k, w) in Skill.BUILDING_WAYS.withIndex()) into[x.BUILDING + k] = if (b.knowsSkill[w]) b.skill(w) else 0f
    val here = h == b.houseHex
    into[x.HOUSE_HERE] = if (here) b.house else 0f; into[x.PALISADE_HERE] = if (here) b.palisade else 0f
    into[x.HOUSES_BEGUN] = housesBegunAt(g, h); into[x.PALISADE_BEGUN] = palisadeBegunAt(g, h)
}

/** The name of each of [bandInputs]' numbers, in order, as a learned network's `.names` file records them (the strings must not change). */
val BAND_INPUT_NAMES: List<String> get() = BandInputs.names

/**
 * Every band's view of itself and of the bands about it, as the week starts ([Band.state]), for the
 * people networks to choose by: worked out once, before any band moves.
 */
internal fun HexGroups.fillBandStates(weather: Weather) {
    indexBands()
    val list = groups.filter { it.alive && it.band != null }
    IntStream.range(0, list.size).parallel().forEach { k ->
        val g = list[k]; val b = g.band!!
        if (b.state.size != WAYS_IN) b.state = FloatArray(WAYS_IN)
        bandInputs(g, weather, b.state)
    }
    for (g in list) g.band!!.beastLoss *= 0.9f
}

/** Sets a band's ways from [ways]' outputs: 0 is the scaffold (effort 1, stores [Band.storeAimAt1], every tool alike). */
/** Files every living band under the hex it is in now ([HexGroups.bandIndex]), for [bandInputs]' bands about. */
internal fun HexGroups.indexBands() {
    bands.bandIndex.clear()
    for (g in groups) if (g.alive && g.band != null) bands.bandIndex.getOrPut(hw.placeHex[g.place]) { ArrayList() } += g
}

internal fun HexGroups.setWays(g: HexGroup, weather: Weather, ways: BandWays, inputs: FloatArray, out: FloatArray) {
    val b = g.band ?: return
    bandInputs(g, weather, inputs)
    out.fill(0f)
    ways.choose(g, inputs, out)
    if (g.id == watched) control?.ways(g, out)
    fun squash(x: Float) = 2f / (1f + exp(-x))
    // (Efforts have no ceiling: ranking a food above what it pays costs the food it would have paid, see [offer].)
    for (t in 0 until FOOD_TYPES) b.effort.a[t] = exp(out[WaysOut.EFFORTS + t])
    b.storeShare = squash(out[WaysOut.STORE_AIM])
    for (t in Tool.entries) b.makeFirst[t] = squash(out[WaysOut.MAKE_FIRST + t.ordinal])
    for (w in BandWay.entries) b.will[w] = squash(out[w.slot])
}

/** Chance a band with no home to hold slips away from a raid, losing only what it cannot carry. */
const val FLEE = 0.5f

/**
 * How much likelier a sickle harvest takes an ear that holds its grain than one that shatters at a
 * touch. Set so that ears that hold their grain take one to two thousand years to come to dominate
 * a cultivated stand, as the archaeobotany of Near Eastern wheat and barley shows (Fuller and
 * colleagues: early harvests were often green, by beating or uprooting, and sown seed was mixed with
 * wild-gathered grain), not the decades a pure sickle-and-resow model would give.
 */
const val SICKLE_SELECT = 0.06f

/**
 * Sodium a person needs a day, kg: the 23 mg a day the Yanomami pass, the least of any people measured (INTERSALT, BMJ
 * 1988; Mancilha-Carvalho et al., J Hum Hypertens 1989: 0.9 mmol a day, healthy and with low blood pressure, on a
 * mostly plant diet and no salt). How much less good their food does them wholly without it is [inflation.Tuning.saltLack].
 */
const val SODIUM_A_PERSON_DAY = 23e-6f
/** Sodium in a kg of meat or fish, kg: raw deer 51 mg/100 g (USDA SR "game meat, deer, raw"), freshwater fish about 50. */
const val SODIUM_PER_KG_MEAT = 0.51e-3f
/**
 * The share of the food eaten that does a band's people good, [short] of the sodium they [need] (kg each, or both as shares):
 * wholly without it, [lack] less ([inflation.Tuning.saltLack]). The one rule for the week's meal and for what a band foresees
 * of a place ([HexGroups.saltFeedsAt]).
 */
internal fun saltFeeds(short: Float, need: Float, lack: Float) = if (need > 0f) 1f - lack * (short / need).coerceIn(0f, 1f) else 1f
/** Sodium's share of salt by weight (Na in NaCl). */
const val SALT_SODIUM = 0.393f
/** Salt that holds a person's year of sodium ([SODIUM_A_PERSON_DAY]), kg: some 21 g. */
const val SALT_BODY_YEAR = SODIUM_A_PERSON_DAY * Date.DAYS_PER_YEAR / SALT_SODIUM
/** Salt to cure a kilo of meat or fish, kg (how much less salted meat and fish spoil: [inflation.Tuning.saltKeep]). */
const val SALT_PER_KG = 0.1f

/**
 * How much a gift that meets a band's whole want binds it to the giver (the giver half as much);
 * ties below which no gift is given. Not measured: as a feast.
 */
const val TIE_USEFUL = 0.3f
const val FEUD_GIFT = -0.2f
/** How a band's name for its gifts counts against food in hand when other bands weigh whose ways to take up; share of it that fades a year. */
const val RENOWN = 0.2f
const val RENOWN_FADE = 0.2f

/** Share of the way to knowing a kind of country's plants a week's gathering there goes; the least a stranger finds of what a local would. */
const val PLANT_LEARN = 0.05f
const val PLANT_FLOOR = 0.1f
/** The foraging skill bands start with, and how well a band knows the plants in its own country (handling times are set for these). */
const val START_CRAFT = 0.6f
/**
 * Cooking and detoxifying: how much more of roots earth ovens make good, and of seed gruel; how much
 * of the bitter plants leaching makes food (against the palatable), and person-days to leach a kilo.
 */
const val EARTH_OVEN_GAIN = 0.3f
/**
 * Keeping: rain in a week that halves what racks in the sun dry, mm; how much less smoked meat spoils
 * at full skill than sun-dried; how much of the mice's take sealed pits and bins keep out at full skill.
 */
const val DRY_RAIN_MM = 30f
const val SMOKE_KEEP = 0.5f
const val SEAL_KEEP = 0.7f
/** How far a healer's or midwife's skill reaches into the deaths it can prevent (share of the gap to a start band's). */
const val HEALING_REACH = 0.5f

/**
 * Mudbrick walls wear in the rain: mm of wall lost a mm of rain, 28.3 mm over 3 years under 2330 mm of rain on an unprotected
 * field wall (Reddy and Jagadish, as reported in Heathcote 2002, An investigation into the erodibility of earth wall units,
 * UTS thesis); and the wall lost at which a house is gone, half a 300 mm wall (an estimate).
 */
const val MUD_EROSION = 0.012f
const val MUD_WALL_MM = 150f
const val GRUEL_GAIN = 0.2f
const val LEACH_SHARE = 0.5f
const val LEACH_DAYS_PER_KG = 0.3f
val KNOW_AT_HOME = PLANT_FLOOR + (1f - PLANT_FLOOR) * 0.9f

/** How far below its teacher a newcomer's skill falls, at best. */
const val TRANSMIT_LOSS = 0.1f

/**
 * Choices against danger: share of the hands lost (or gained) for each step of watching the
 * children beyond (or short of) the usual; how far beyond the usual round a gatherer walks at twice
 * the usual range, km a day; share of a band sick before others can see it.
 */
const val CHILD_WATCH_HANDS = 0.15f
const val GATHER_WALK_KM = 6f
const val SICK_SHOWS = 0.05f
/** Share of a full kit below which a band counts its kit worn, and mends before it lays food by as far as its way goes ([Band.mendFirst]; Olli's choice). */
const val MEND_BELOW = 0.5f
/**
 * Skin a kilo of small game yields, kg (a hare's or rabbit's pelt is about a tenth of its weight, from memory),
 * and the person-days scraping, stretching and drying a kilo of small pelts takes (an hour or two a pelt of
 * a few hundred grams, from memory): more work for the hide than a big beast's, and more seams to sew.
 */
const val SMALL_SKIN = 0.1f
const val SKIN_DAYS = 0.5f
/**
 * Wounds: how much less the hurt and the lamed do; yearly share of adults hurt by mishaps (falls,
 * cuts, sprains, snakebite gone bad) at the usual gathering range; wounded who live for each one
 * killed in a fight, by prey or by a beast; weekly share of the wounded who heal, untended and with
 * the best care; weekly share whose wound festers and kills, untended (care saves most); share of
 * the healed lamed for life, untended (care halves it); yearly share of the lamed lost as the band's
 * people turn over.
 */
const val HURT_HANDS = 0.8f
const val LAME_HANDS = 0.4f
const val MISHAPS = 0.08f
const val WOUNDED_PER_DEAD = 2f
const val HEAL_UNTENDED = 0.15f
const val HEAL_TENDED = 0.35f
const val FESTER = 0.02f
const val LAMED = 0.2f
const val LAME_TURNOVER = 0.04f
