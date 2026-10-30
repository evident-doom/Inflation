package inflation.ecology

import inflation.world.Hex
import inflation.world.Place
import inflation.world.get
import inflation.world.set
import inflation.Books
import inflation.Kg
import inflation.min
import inflation.world.LifeForm
import kotlin.math.max
import kotlin.math.min
import inflation.world.Weather
import kotlin.math.exp

// Bands' warmth: shelters and houses, the hearth's firewood, the night's fire and watch.

/**
 * At any camp a band first puts up brush shelters, quickly (and leaves them when it moves on: [moved], come to a new camp).
 * It does so as soon as its week is planned, in the days the plan gives them, before the foraging can eat into them
 * ([planWeek]); and again with what its chores leave ([bandWeek]). (Built only after the foraging and the other chores, its
 * few held days would be the first a week's overrun took, and bands would sleep in the open at a new camp.) It builds as far as it aims ([aim]: its
 * ways', or more where its week's survival pass would thatch them, [survivalDays]): a light hut first, then its thatching.
 */
internal fun putUpShelter(b: Band, people: Float, time: BandTime, moved: Boolean = false, aim: Float = b.shelterWill) {
    if (moved) b.shelter = 0f
    val to = min(SHELTER_MOST, aim)
    val left = time.leftFor(Activity.SHELTER)
    if (to > b.shelter && left > 0f && people > 0f) {
        val built = shelterReach(b, people, b.shelter, to, left)
        time.spend(Activity.SHELTER, shelterWork(b, people, b.shelter, built))
        b.shelter = built
        b.practise(Skill.WOODWORK, 0.2f * PRACTICE)
    }
    time.release(Activity.SHELTER)
}

/**
 * Person-days [b]'s [people] take to bring their shelters from [from] to [to]: up to 1, a light hut of poles and brush or
 * leaves ([SHELTER_DAYS] a whole one), which keeps off the wind and rain and holds the bedding; beyond, up to
 * [SHELTER_MOST], its thatching, thick enough to hold the warmth in ([THATCH_DAYS] a whole one, [BodyHeat]). The practised
 * build faster.
 */
internal fun shelterWork(b: Band, people: Float, from: Float, to: Float): Float {
    if (to <= from || people <= 0f) return 0f
    val hut = max(0f, min(1f, to) - min(1f, from)); val thatch = max(0f, max(1f, to) - max(1f, from))
    return people * (hut * SHELTER_DAYS + thatch * THATCH_DAYS) / b.skill(Skill.WOODWORK)
}

/** How far [days] person-days of [b]'s [people] bring their shelters from [from] towards [to] ([shelterWork] the other way). */
internal fun shelterReach(b: Band, people: Float, from: Float, to: Float, days: Float): Float {
    if (to <= from || people <= 0f || days <= 0f) return max(from, 0f)
    val per = people / b.skill(Skill.WOODWORK)
    var s = from; var d = days
    if (s < 1f) {
        val hut = min(1f, to) - s; val need = hut * SHELTER_DAYS * per
        if (d < need) return s + d / (SHELTER_DAYS * per)
        s += hut; d -= need
    }
    return if (to > s) s + min(to - s, d / (THATCH_DAYS * per)) else s
}

/** A fire through the night burns wood: where there is little, the night goes dark. Returns the wood burnt, kg. */
internal fun burnNightFire(b: Band, people: Float): Float {
    val wood = b.goods
    val want = NIGHT_WOOD * people * b.nightFire
    val got = min(want, wood[Material.WOOD])
    wood[Material.WOOD] -= got
    b.nightWood = if (want > 0f) got / want else 1f
    return got
}

/** Weeks [b] keeps a camp before it builds houses there: sooner the keener it is ([BandWay.HOUSES]). */
internal fun GroupWorld.houseAfterWeeks(b: Band) = HOUSE_AFTER_YEARS * stepsPerYear / b.houseWill.coerceAtLeast(0.1f)

/**
 * The hearth's firewood for the week, fetched by [hands] before they forage: dead wood from the trees and scrub
 * round the camp, quick to gather where they are thick and slow where they are few, for everyone in the band.
 * It fetches in the [given] person-days its week's plan gives it ([planWeek]), but never more than [FUEL_MOST] of
 * its days ([fuelMost]), and its fire burns all it fetches ([Band.hearthWood], [fireOf]). Returns the days each hand took.
 */
internal fun GroupWorld.fetchFuel(g: HexGroup, b: Band, hands: Int, given: Float): Float {
    val p = g.place
    val tree = land.plants.tree[p]; val shrub = land.plants.shrub[p]
    val cover = woodCover(p)
    if (g.size <= 0 || hands <= 0 || cover <= 0f) { b.hearthWood = if (g.size <= 0) 1f else 0f; return 0f }
    val spent = min(given, fuelMost(g, hands))
    b.hearthWood = fireOf(g, spent)
    val got = spent * FUEL_A_DAY * cover
    val m2 = land.m2(p)
    val burnt = land.plants.consume(p, LifeForm.TREE, Kg(got * tree / (tree + shrub)).perM2(m2)).v +
        land.plants.consume(p, LifeForm.SHRUB, Kg(got * shrub / (tree + shrub)).perM2(m2)).v
    land.books?.flow(Books.Stuff.MATTER, "burnt", -burnt * m2.toDouble())
    return spent / hands
}

/** The most person-days [g]'s [hands] give the hearth's firewood in a week: [FUEL_MOST] of their days, where there is wood. */
internal fun GroupWorld.fuelMost(g: HexGroup, hands: Int) = if (g.size <= 0 || woodCover(g.place) <= 0f) 0f else FUEL_MOST * days * hands

/**
 * The fire [personDays] of firewood keep for [g]'s people this week ([Band.fire]): the wood a person a day, in whole cooking
 * fires ([HEARTH_WOOD]); a bigger fire warms them more ([BodyHeat.fireC], [Band.houseWarmC]).
 */
internal fun GroupWorld.fireOf(g: HexGroup, personDays: Float) =
    if (g.size <= 0) 0f else personDays * FUEL_A_DAY * woodCover(g.place) / (HEARTH_WOOD * days * g.size)

/** The person-days [g]'s [hands] would give the hearth's firewood for a whole cooking fire this week ([HEARTH_WOOD], [fetchFuel]). */
internal fun GroupWorld.fuelAsk(g: HexGroup, hands: Int): Float {
    val cover = woodCover(g.place)
    return if (HEARTH_WOOD * g.size <= 0f || cover <= 0f) 0f else fuelShare(cover, g.size.toFloat() / hands) * days * hands
}

/** How thickly dead wood lies about place [p], 0 (none) to 1 (trees or scrub thick enough to fetch [FUEL_A_DAY] a person-day). */
internal fun GroupWorld.woodCover(p: Place) = min(1f, (land.plants.tree[p] + land.plants.shrub[p]) / 3f)

/**
 * The share of its hands' days a band gives to the hearth's firewood at wood cover [cover] ([woodCover]), [perHand]
 * mouths to each hand that fetches it: what the hearth wants, but never more than [FUEL_MOST] ([fetchFuel], and the
 * camp's worth in [HexGroups.campFactor]).
 */
internal fun fuelShare(cover: Float, perHand: Float) = if (cover <= 0f) 0f else min(FUEL_MOST, HEARTH_WOOD * perHand / (FUEL_A_DAY * cover))

/** The share of the hearth's want those days ([fuelShare]) bring in: its fire burns only as far as that goes ([Band.hearthWood]). */
internal fun hearthShare(cover: Float, perHand: Float) =
    if (perHand <= 0f) 1f else if (cover <= 0f) 0f else min(1f, fuelShare(cover, perHand) * FUEL_A_DAY * cover / (HEARTH_WOOD * perHand))

/**
 * What keeping warm takes of a group of kind [s]'s week's need at place [p], at [t] °C (the week's own unless given): an
 * animal's by the cold it feels through its coat ([coldNeed]); band [b]'s by its people's body heat ([Band.warmth]) with a
 * fire of [fire] and at its house or not ([home]), and more again for raw meals where there is no fire ([RAW_COST]). The
 * week's own ([HexGroup.warmth]) and a place weighed ahead ([HexGroups.drain]) alike.
 */
internal fun HexGroups.warmthNeed(s: Species, b: Band?, p: Place, weather: Weather, fire: Float, home: Float, t: Float = weather.temperature[p]) =
    if (b == null) coldNeed(s, t, weather.date.month, tuning)
    else warmthNeed(b.warmth(airAt(p, weather, t), fire, home, days), fire)

/** A band's need for its week's [w] (its body heat) with a fire of [fire]: see [warmthNeed]. */
internal fun GroupWorld.warmthNeed(w: Warmth, fire: Float) = (1f + tuning.coldCost * w.extra) * (1f + RAW_COST * (1f - min(1f, fire)))

/**
 * Houses: [g]'s band, having kept its camp a while, builds stone-footed houses there with the [time] days to spare, as
 * far as its stone and timber go: houses for those of its [people] the houses there it may use do not shelter. (What it
 * builds stands in the world, anyone's to use once it leaves: see [Structure].) With no [time] it builds nothing, and
 * returns the person-days it would have liked (else 0).
 */
@IgnorableReturnValue
internal fun HexGroups.buildHouse(g: HexGroup, b: Band, p: Place, h: Hex, people: Float, time: BandTime?): Float {
    val gd = b.goods
    val left = time?.leftFor(Activity.HOUSES) ?: Float.MAX_VALUE
    // (Only where the camp keeps it well fed and its stores filled: building is worth it where staying pays.)
    if (time != null) dialUse?.let { u -> if (left > 0f && people > 0f && g.fed >= 0.9f) {
        val longEnough = b.campWeeks >= houseAfterWeeks(b)
        val stocked = g.larder >= 0.5f * b.storeAim * g.dailyNeedKg
        u.choice(DialSite.HOUSES, b.houseWill, longEnough && stocked, b.campWeeks >= HOUSE_AFTER_YEARS * stepsPerYear && stocked, cut = b.houseWill < 0.1f)
        u.choice(DialSite.STORE_HOUSES, b.storeShare, longEnough && stocked, longEnough && g.larder >= 0.5f * b.storeAimAt1 * g.dailyNeedKg)
    } }
    if (b.campWeeks < houseAfterWeeks(b) || left <= 0f || people <= 0f || g.fed < 0.9f || g.larder < 0.5f * b.storeAim * g.dailyNeedKg) return 0f
    val sheltered = housesAt(g, h)
    if (sheltered >= 1f) return 0f
    // It builds the way it knows that its stone, timber and clay go furthest with, as keen as it is on each (or goes on with
    // what it began).
    val clay = land.drink.water[p] >= Thresholds.WET || land.drink.waterKm[p] < 3f
    val begun = bands.built.firstOrNull { it.kind == Built.HOUSES && it.hex == h && it.user == g.id && it.built < 1f }
    val size = begun?.size ?: (people * (1f - sheltered))
    // (Each way weighed by how far this week's stone, timber and days take it, and by the band's keenness on it: [buildWay].)
    val known = Skill.BUILDING_WAYS.filter { b.knowsSkill[it] && (it != Skill.MUDBRICK || clay) }
    fun reach(w: Skill): Float {
        val (st, wd, dd) = houseNeeds(w)
        return minOf(left / (dd * size), gd[Material.STONE] / max(1e-3f, st * size), gd[Material.WOOD] / (wd * size))
    }
    val way = begun?.let { Skill.entries[it.design] } ?: known.maxByOrNull { w -> reach(w) * b.will[buildWay(w)] } ?: Skill.STONE_FOOTING
    if (time != null && begun == null) dialUse?.let { u ->
        val at1 = known.maxByOrNull { w -> reach(w) } ?: Skill.STONE_FOOTING
        for (w in known) u.choice(DialSite.BUILD_WAY, b.will[buildWay(w)], way == w, at1 == w, dial = buildWay(w).slot)
    }
    val (stoneEach, woodEach, daysEach) = houseNeeds(way)
    val daysNow = daysEach * size / b.skill(way) * b.skill(Skill.STONE_FOOTING)
    var built = min(1f - (begun?.built ?: 0f), left / daysNow)
    built = minOf(built, if (stoneEach > 0f) gd[Material.STONE] / (stoneEach * size) else 1f, gd[Material.WOOD] / (woodEach * size))
    built = max(0f, built)
    if (time == null) return built * daysNow
    val s = begun ?: buildingAt(g, Built.HOUSES, h, way.ordinal, size)
    gd[Material.STONE] -= built * stoneEach * size; gd[Material.WOOD] -= built * woodEach * size
    buildOn(s, built); time.spend(Activity.HOUSES, built * daysNow)
    if (built > 0f) b.practise(way)
    if (built > 0f) stats.meeting(Met.BANDS_BUILT_HOUSES)
    return 0f
}

/**
 * Houses: person-days of work for one person's share of a hamlet (digging, walls, roof); weeks at a
 * camp before a band starts to build; share a year a left house weathers away; how much warmer a
 * full house keeps its people, °C; how much less its pits let stores spoil; share of the stored
 * grain, nuts and roots mice take a week from full pits; how much harder a band at home is to raid;
 * how strongly its houses draw a band back.
 */
internal const val HOUSE_DAYS = 20f
/**
 * Stone and timber for one person's share of a stone-footed house, kg; person-days for one person's share of a brush
 * shelter (a debris hut for about five takes a novice an afternoon, from memory); how much warmer a hut thatched whole keeps
 * those in it, by their own warmth, °C (an estimate; what else it does against the cold: [BodyHeat]).
 */
internal const val HOUSE_STONE = 150f
internal const val HOUSE_WOOD = 60f
internal const val SHELTER_DAYS = 0.1f
internal const val SHELTER_C = 3f
/**
 * Person-days for one person's share of thatching a hut whole, thick enough to hold the warmth in: a 2.5 m dome of cane
 * ribs thatched with grass took one man seven days, five of them gathering the grass (Primitive Technology, "Grass Hut",
 * 2016, one build): some 2 for each of the three or four it sleeps. (A light hut: a Ju/'hoansi shade hut went up in about
 * half an hour for four people, bushguide101.com; a Baka leaf hut in about an hour, a group's work, as recalled on a garden
 * exhibit's page: a tenth of a day each or less, [SHELTER_DAYS].) The most a band's shelters reach: a light hut, thatched.
 */
internal const val THATCH_DAYS = 2f
internal const val SHELTER_MOST = 2f
internal const val HOUSE_AFTER_YEARS = 0.5f
internal const val HOUSE_DECAY = 0.3f
/**
 * Firewood a person a day that keeps a house's air 1 °C warmer than outside, kg: its walls and roof hold the hearth's
 * warmth. Houses with hearths or stoves in the Himalaya burn 1.4 kg a person a day in summer and 4.3 in winter at
 * Lahaul, 3200 m (Rawat, Vishvakarma and Todaria 2009, Biomass and Bioenergy 33: 1547), and about 2 in the heating
 * season in the Western Pamirs (Mislimshoeva et al. 2014, Mountain Research and Development 34(2)): 0.1-0.2 kg a degree
 * over what cooking takes, for a winter some 15-25 °C below the warmth kept (a reckoning here; the sources give no
 * temperatures). Two reconstructed Viking Age houses with a central hearth kept 10 °C over the outside air on the mean
 * through two winters (Christensen 2013, EXARC Journal 2013/3): at this, 1.5 kg a person a day.
 */
internal const val FIRE_HOUSE_KG_C = 0.15f
internal const val PIT_KEEP = 0.5f
internal const val MICE = 0.01f
internal const val HOUSE_DEFENCE = 0.5f
internal const val HOME_PULL = 0.15f

/**
 * A band's fire: practised bands carry embers when they move and light it again at once; the
 * unpractised may fail at the hand drill for days, and learn as they go.
 */
internal fun GroupWorld.keepFire(g: HexGroup, b: Band) {
    val skill = b.skills[Skill.FIRE]
    // (Last week's fire, as far as it was lit: a bigger one is still one fire.)
    b.fire = min(1f, b.fire)
    if (g.path.size > 1 && rnd.nextFloat() >= skill) b.fire = 0f
    if (b.fire >= 1f) return
    // Each day it tries; the first day it succeeds it has fire for the rest of the week.
    val pDay = FIRE_FLOOR + (1f - FIRE_FLOOR) * skill * skill
    var day = 0
    while (day < days && rnd.nextFloat() >= pDay) day++
    b.fire = 1f - day.toFloat() / days
    b.practise(Skill.FIRE, FIRE_PRACTICE * (if (day < days) 1f else 0.3f))
}

/**
 * Survival floors: each foraging skill's success untrained against a master's (gathering the obvious
 * fruit and shellfish is easy; a novice's snares and lines catch little; big game falls to the
 * practised); a novice's daily chance to get a fire going, and how fast trying teaches it (what a fire does
 * against the cold: [BodyHeat]); how much more food raw meals take for the same energy.
 */
internal const val FIRE_FLOOR = 0.1f
internal const val FIRE_PRACTICE = 0.1f
internal const val RAW_COST = 0.3f
/** Stone and timber (kg a person) and person-days a person's share of a house takes, built [way]. */
fun houseNeeds(way: Skill) = when (way) {
    Skill.PIT_HOUSE -> Triple(HOUSE_STONE / 3f, HOUSE_WOOD, HOUSE_DAYS)
    Skill.MUDBRICK -> Triple(0f, HOUSE_WOOD / 2f, HOUSE_DAYS * 1.5f)
    else -> Triple(HOUSE_STONE, HOUSE_WOOD, HOUSE_DAYS)
}

/**
 * How deep a house built [way] is sunk into the ground, m: a pit house about a metre, a stone-footed hut half sunk (as the
 * Natufian houses at Mallaha), a mudbrick house stands on the ground. (Estimates.)
 */
fun sunkenM(way: Skill) = when (way) { Skill.PIT_HOUSE -> 1f; Skill.MUDBRICK -> 0f; else -> 0.5f }

/**
 * The share of the gap between the ground's year-round warmth and the air's that a house built [way] brings inside through its
 * sunken walls: the year's swing of the soil's warmth falls off with depth as exp(-depth / [SOIL_DAMPING_M]).
 */
fun earthGain(way: Skill) = 1f - exp(-sunkenM(way) / SOIL_DAMPING_M)

/**
 * How well the walls of a house built [way] keep beasts of prey out at night, 0..1: stone-footed and mudbrick walls whole; a
 * pit house, whose roof of brush and earth comes down to the ground, [PIT_WALLS] of that. (Lions break into thatched houses
 * and makeshift huts to take those asleep inside, Packer et al. 2005, Nature 436: 927; the shares are estimates.)
 */
fun wallGuard(way: Skill) = if (way == Skill.PIT_HOUSE) PIT_WALLS else 1f
internal const val PIT_WALLS = 0.5f

/**
 * The share of their working time [adults] keep at work when they keep [watchHours] of watch a night between them: each hour
 * awake comes out of someone's sleep, and a short night costs the next day's work about [SLEEP_PACE] of the hours lost against
 * a night's [SLEEP_HOURS] (an estimate). No cap: a band that watches all night all of it works ever less.
 */
fun watchPace(watchHours: Float, adults: Int) = max(0f, 1f - SLEEP_PACE * watchHours / (max(1, adults) * SLEEP_HOURS))
/**
 * Night watch: hours awake a night at the scaffold's 1 (one awake at a time through about ten hours of dark); a night's sleep,
 * h; the share of the hours lost the next day's work loses (estimates); the share of the loss to beasts at night a watch of
 * [WATCH_HOURS] keeps off, each more of it the same share of what is left ([shareOfWill]) (an estimate: in Kenya bomas lost
 * fewer livestock at night the more people were in them, while herders by day made no difference, Woodroffe et al. 2007, via
 * Conservation Evidence).
 */
internal const val WATCH_HOURS = 10f
internal const val SLEEP_HOURS = 8f
internal const val SLEEP_PACE = 0.5f
internal const val WATCH_GUARD = 0.4f

/**
 * The depth over which the soil's yearly swing of warmth falls to 1/e, m: sqrt(2 κ / ω) for a moist soil's thermal diffusivity
 * κ of about 5e-7 m²/s and the year's ω (heat conduction in soil, e.g. Hillel 1998, Environmental Soil Physics; κ an estimate
 * for a moist loam).
 */
internal const val SOIL_DAMPING_M = 2.2f

/** Firewood a fire through the night burns in a week, kg a person. */
internal const val NIGHT_WOOD = 3f
/**
 * Firewood a whole cooking fire burns, kg a person a day (1.4 in summer in houses at Lahaul, 3200 m, where little goes to
 * warmth, Rawat et al. 2009, as [FIRE_HOUSE_KG_C]; more warms them more, [BodyHeat.fireC]); dead wood one person gathers in
 * a day where trees or scrub are thick, kg (head-loads of 20-30 kg, from memory).
 */
internal const val HEARTH_WOOD = 1f
internal const val FUEL_A_DAY = 25f
/** Most of its days a band gives to firewood before it goes without (an estimate). */
internal const val FUEL_MOST = 0.5f
