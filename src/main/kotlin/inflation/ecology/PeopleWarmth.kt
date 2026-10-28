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
 * few held days were the first a week's overrun took: in a trace of 2026-10-28, bands whose plan held all the shelter they
 * aimed at slept in the open at a new camp, and died of exposure there by a fire.) Its poles and brush are cut about the
 * camp ([shelterDays], [SHELTER_WOOD]), where there is wood to cut.
 */
internal fun GroupWorld.putUpShelter(g: HexGroup, b: Band, people: Float, time: BandTime, moved: Boolean = false) {
    if (moved) b.shelter = 0f
    val shelterDays = shelterDays(b, people, woodCover(g.place))
    val lack = shelterLack(b, moved = false)
    val left = time.leftFor(Activity.SHELTER)
    if (lack > 0f && left > 0f && people > 0f && shelterDays.isFinite()) {
        val built = min(lack, left / shelterDays)
        b.shelter += built
        time.spend(Activity.SHELTER, built * shelterDays)
        cutWood(g.place, built * SHELTER_WOOD * people, "cut for shelters")
        b.practise(Skill.WOODWORK, 0.2f * PRACTICE)
    }
    time.release(Activity.SHELTER)
}

/**
 * Brush shelters wear while a band keeps its camp: leaves and brush dry, sag and leak, and are made good again ([putUpShelter])
 * or let go ([SHELTER_WEAR]). Once a week, as the week is planned ([planWeek]); none at a camp it has just come to ([moved]).
 */
internal fun GroupWorld.wearShelter(b: Band, moved: Boolean) {
    if (!moved) b.shelter *= max(0f, 1f - SHELTER_WEAR * days / 7f)
}

/**
 * Person-days [b]'s [people] take to put up whole shelters where the wood lies as thick as [cover] ([woodCover]): building
 * them (a debris hut takes a novice an afternoon, the practised an hour), and cutting their poles and brush ([SHELTER_WOOD])
 * as fast as wood comes in there ([FUEL_A_DAY], the firewood's rule). None can be put up where there is no wood (infinite).
 */
internal fun shelterDays(b: Band, people: Float, cover: Float) =
    if (cover <= 0f) Float.POSITIVE_INFINITY else people * (SHELTER_DAYS / b.skill(Skill.WOODWORK) + SHELTER_WOOD / (FUEL_A_DAY * cover))

/**
 * What [b] lacks of the shelter it aims at: none stands at a camp it has just come to ([moved]). (Above the default it
 * builds sturdier: thatched thicker and banked with earth, warmer as it took longer.)
 */
internal fun shelterLack(b: Band, moved: Boolean) = max(0f, b.shelterWill - if (moved) 0f else b.shelter)

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
 * A band goes without rather than give more than [FUEL_MOST] of its days to it, and fetches only the [share] of that its
 * week's plan gives it ([planWeek]). Returns the days it took.
 */
internal fun GroupWorld.fetchFuel(g: HexGroup, b: Band, hands: Int, share: Float = 1f): Float {
    val p = g.place
    val want = HEARTH_WOOD * days * g.size
    val cover = woodCover(p)
    if (want <= 0f || cover <= 0f) { b.hearthWood = if (want <= 0f) 1f else 0f; return 0f }
    val perHand = g.size.toFloat() / hands
    val spent = fuelShare(cover, perHand) * days * share
    b.hearthWood = hearthShare(cover, perHand) * share
    cutWood(p, b.hearthWood * want, "burnt")
    return spent
}

/** Takes [kg] of wood from place [p]'s trees and scrub, each as far as it grows there, booked as [flow]: the hearth's, a shelter's. */
internal fun GroupWorld.cutWood(p: Place, kg: Float, flow: String) {
    val tree = land.plants.tree[p]; val shrub = land.plants.shrub[p]
    if (kg <= 0f || tree + shrub <= 0f) return
    val m2 = land.m2(p)
    val cut = land.plants.consume(p, LifeForm.TREE, Kg(kg * tree / (tree + shrub)).perM2(m2)).v +
        land.plants.consume(p, LifeForm.SHRUB, Kg(kg * shrub / (tree + shrub)).perM2(m2)).v
    land.books?.flow(Books.Stuff.MATTER, flow, -cut * m2.toDouble())
}

/** The person-days [g]'s [hands] would give the hearth's firewood this week ([fetchFuel] with all it asks). */
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
internal fun GroupWorld.warmthNeed(w: Warmth, fire: Float) = (1f + tuning.coldCost * w.extra) * (1f + RAW_COST * (1f - fire))

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
const val HOUSE_DAYS = 20f
/**
 * Stone and timber for one person's share of a stone-footed house, kg; person-days for one person's share of a brush
 * shelter (a debris hut for about five takes a novice an afternoon, from memory); how much warmer a whole brush shelter keeps
 * those in it, by their own warmth, °C (an estimate; what else it does against the cold: [BodyHeat]); how much warmer a house
 * keeps its air than the air outside with its hearth fire kept, °C: 10 °C on the mean over two winters in two reconstructed
 * Viking Age houses of wattle and daub with a central hearth, the side facing the fire warmer still (Christensen 2013,
 * "Living Conditions and Indoor Air Quality in a Reconstructed Viking House", EXARC Journal 2013/3). Those beside the
 * hearth are warmed by it besides, as at a camp ([BodyHeat.FIRE_CAMP_C]).
 */
const val HOUSE_STONE = 150f
const val HOUSE_WOOD = 60f
const val SHELTER_DAYS = 0.1f
const val SHELTER_C = 3f
/**
 * Poles and brush for one person's share of a whole brush shelter, kg (an estimate: a light dome hut for four or five, a
 * frame of a dozen or so poles thatched with brush and leaves, at some 50 kg); the share of a shelter that wears away in a
 * week while the camp is kept (an estimate: leaf and brush thatch dries, sags and leaks within a few months).
 */
const val SHELTER_WOOD = 10f
const val SHELTER_WEAR = 0.05f
const val HOUSE_AFTER_YEARS = 0.5f
const val HOUSE_DECAY = 0.3f
const val HOUSE_C = 10f
const val PIT_KEEP = 0.5f
const val MICE = 0.01f
const val HOUSE_DEFENCE = 0.5f
const val HOME_PULL = 0.15f

/**
 * A band's fire: practised bands carry embers when they move and light it again at once; the
 * unpractised may fail at the hand drill for days, and learn as they go.
 */
internal fun GroupWorld.keepFire(g: HexGroup, b: Band) {
    val skill = b.skills[Skill.FIRE]
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
const val FIRE_FLOOR = 0.1f
const val FIRE_PRACTICE = 0.1f
const val RAW_COST = 0.3f
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
const val PIT_WALLS = 0.5f

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
const val WATCH_HOURS = 10f
const val SLEEP_HOURS = 8f
const val SLEEP_PACE = 0.5f
const val WATCH_GUARD = 0.4f

/**
 * The depth over which the soil's yearly swing of warmth falls to 1/e, m: sqrt(2 κ / ω) for a moist soil's thermal diffusivity
 * κ of about 5e-7 m²/s and the year's ω (heat conduction in soil, e.g. Hillel 1998, Environmental Soil Physics; κ an estimate
 * for a moist loam).
 */
const val SOIL_DAMPING_M = 2.2f

/** Firewood a fire through the night burns in a week, kg a person. */
const val NIGHT_WOOD = 3f
/**
 * Firewood the hearth burns for warmth and cooking, kg a person a day (about 1-2 in household fuel surveys of
 * people cooking on open fires, from memory); dead wood one person gathers in a day where trees or scrub are
 * thick, kg (head-loads of 20-30 kg, from memory).
 */
const val HEARTH_WOOD = 1f
const val FUEL_A_DAY = 25f
/** Most of its days a band gives to firewood before it goes without (an estimate). */
const val FUEL_MOST = 0.5f
