package inflation.ecology

import inflation.world.Date
import inflation.Tuning
import inflation.monthsFrom
import inflation.Config
import inflation.world.Weather
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

// The numbers the packs and bands on the hexes live by.

/** Days of going a week's net shift amounts to, at the usual pace or a trek's. */
const val SHIFT_DAYS = 2f
/** Snow depth that doubles the effort of going, mm. */
const val SNOW_EFFORT_MM = 150f
/** How much more than its needs an animal eats, at most, when food is plentiful and its reserves are short; share of that surplus laid on as reserves. */
const val SURPLUS_EAT = 0.3f
const val FAT_EFFICIENCY = 0.6f
/** A newborn's weight against its mother's, paid from her reserves. */
const val NEWBORN_SHARE = 0.08f
/** A newborn of [s], in days of its mother's food: what she gives of her reserves or her food to bear it. */
fun youngDays(s: Species) = NEWBORN_SHARE * s.massKg / s.dailyIntakeKg

/** What a mother of [s] fed [fed] over [days] can give her young of the week's food: what she ate above the hunger line, days of food. */
fun spareDays(fed: Float, days: Int) = max(0f, fed - HexFauna.HUNGER_FROM) * days

/**
 * The share of her young due this step a mother of [s] bears, [young] of them in full: she builds them first out of the
 * food she ate beyond the hunger line ([spareDays]), the rest out of her reserves of [fat] days, and bears only those she
 * can build.
 */
fun breeding(s: Species, young: Float, fat: Float, fed: Float, days: Int): Float {
    val cost = young * youngDays(s)
    return if (cost > 0f) min(1f, (spareDays(fed, days) + fat) / cost) else 1f
}

/**
 * The young a mother of [s] is due in a step, its [share] of her season ([seasonShare]), among [headsKm2] of her kind
 * per km² of the ground she ranges, fed [protein] of her need for it: mothers short of protein bear and suckle fewer, and
 * a mother needs a range of her own to bear and raise her young in (where they crowd beyond [spacing] times their
 * [usualKm2] numbers, only as many as there are ranges breed). How many she bears of them is [breeding]'s. The one rule
 * for residents and groups.
 */
fun youngDue(s: Species, headsKm2: Float, usualKm2: Float, spacing: Float, protein: Float, share: Float): Float {
    val ranges = min(1f, spacing * usualKm2 / headsKm2)
    return ranges * s.youngPerFemale * protein * share
}

/**
 * The share of her season a mother of [s] whose season starts in month [start] bears in the step of [days] from [date]:
 * none out of season; else a step's share of the season ([Date.DAYS_PER_MONTH] days a month, so a month of four or five
 * weekly steps counts alike), no more than the [left] of it she has not borne this year, and in the season's last step
 * all she has left. The one rule for residents and groups.
 */
fun seasonShare(s: Species, start: Int, left: Float, date: Date, days: Int): Float {
    if (monthsFrom(start, date.month) >= s.birthMonths) return 0f
    val last = monthsFrom(start, date.plusDays(days).month) >= s.birthMonths
    return if (last) left else min(left, days / (s.birthMonths * Date.DAYS_PER_MONTH.toFloat()))
}

/** The month a mother of [s] whose season starts in month [start] starts her year again: the middle of the months outside it. */
fun offSeason(s: Species, start: Int) = (start + s.birthMonths + (12 - s.birthMonths) / 2) % 12

/** Days of reserves a mother of [s] fed [fed] over [days] gives up for [young] young: what her spare food did not build. */
fun fromReserves(s: Species, young: Float, fed: Float, days: Int) = max(0f, young * youngDays(s) - spareDays(fed, days))
/** Share of its fill above which a group lays on reserves rather than drawing on them (a week's food is never exactly what it needs). */
const val WELL_FED = 0.9f
/**
 * How far fruit, roots or prey stretch to make up for a food that fell short: to (1 + this) times their usual
 * share; and browse for grass under snow (grass and browse otherwise go as far as the gut holds).
 */
const val SWITCH = 1f
/** Scent marks: share of freshness kept from week to week; below what freshness a mark no longer keeps others off. */
const val MARK_KEEP = 0.85f
const val MARK_FRESH = 0.3f
/**
 * Hunters of other kinds: strength ratio from which the stronger robs a kill (with this chance), and the
 * chance a much stronger party kills an adult of the other, or one of its young.
 */
const val THEFT_RATIO = 1.3f
const val THEFT = 0.5f
const val INTRAGUILD_KILL = 0.01f
const val INTRAGUILD_KILL_YOUNG = 0.1f
/** How much a hunting party is put off a place held by a much stronger party of another kind. */
const val AVOID_STRONGER = 0.3f
/** How much of a courser's success thick cover takes away (a cheetah in a wood runs into trees). */
const val COURSING_COVER = 0.7f
/** How much a pack weighs its own kind of country (the log of [HexFauna.suits]) in choosing its ground. */
const val GROUP_HABITAT = 0.15f
/**
 * Paths: share of the way to fully worn one passage of a group of [PATH_MASS_KG] or more wears;
 * weekly share plants take back at the greenest (less on bare ground); weight of a group that wears
 * a path at the full rate, kg; how much a fully worn path eases the going on middling ground (more
 * on broken); how much likelier hunters meet game, or people, on a busy path (they wait there).
 */
const val PATH_WEAR = 0.05f
const val PATH_FADE = 0.03f
const val PATH_MASS_KG = 3000f
const val PATH_EASE = 0.5f
const val AMBUSH = 1f
/**
 * Sea crossings: most hexes of open sea between two coasts that count as a strait; effort of each km swum
 * against a km walked; share of a swimming group that drowns on a strait as wide as all it can swim (young
 * twice that).
 */
const val SEA_HEXES = 2
const val SWIM_EFFORT = 10f
const val DROWN = 0.05f
/** Newcomers: a kind with fewer groups than this gets this many more once a year (see [HexGroups.newcomers]). */
const val NEWCOMER_BELOW = 5
const val NEWCOMERS = 3
/** What a band takes a sign it cannot read to be: a middling value, neither good nor bad. */
const val SIGN_GUESS = 0.3f
/**
 * How much harder thick cover makes people's going; share of what the camp's own hex gives that each hex
 * round it gives a band's gatherers at the usual range.
 */
const val COVER_GOING = 0.5f
const val RING_REACH = 0.15f
/** How much the old, the sick and the lamed slow a band's going (all of it so: this share slower). */
const val WEAK_SLOW = 0.6f
/** Hunting is worked region by region in squares this wide, km (wider than twice any party's week). */
const val HUNT_BLOCK_KM = 300f
/** Parties that kept near home this week are worked in squares this wide, km (see [hunt]). */
const val HUNT_FINE_KM = 100f
/** How far a lone hunter goes looking for a mate in a week, km. */
const val MATE_SEARCH_KM = 50.0
/** How much the best shelter takes off a hunter's chance; how likely a group that senses danger first slips away. */
const val SHELTER_EFFECT = 0.7f
const val AVOID_SKILL = 0.6f
/** People: plant food counted against game by weight (it is easier to come by, but not all is worth gathering); how much a safe camp site adds. */
const val PLANT_FOOD_WEIGHT = 0.5f
const val CAMP_SAFETY = 0.5f
/** Days of food a band has stored when the world begins. */
const val START_LARDER_DAYS = 30f
/** Share of its larder a band gives one that is going hungry, when they meet. */
const val BAND_GIFT = 0.3f
/** Chance a band burns off the dry grass around its camp in a dry-season week, to bring up green grass that draws game. */
const val BURN_OFF = 0.1f
/** How much a camp fire through the night keeps lions and hyenas from taking people. */
const val CAMPFIRE = 0.7f
/** Soil water below which a place is in a dry spell, mm; rain ahead that promises relief, mm a week. */
const val DRY_SOIL_MM = 40f
const val RAIN_AHEAD_MM = 20f
/** Prey, kg per km² weighed by catchability, at which a place is half as good as it gets for a hunter. */
const val PREY_HALF = 200f
/** Pull of the season's remembered place, and of staying where one is. */
const val SEASON_PULL = 1.0f
/** For tracing: the kind whose groups' reasons the plan prints (planDiag=kind). */
val PLAN_DIAG: String? = Config.string(Config.Key.PLAN_DIAG)
/** Strength of the pull to better country beyond which a group treks rather than drifts. */
const val ASTRAY = 0.5f
/** How far a band's inherited seasonal places may lie from where it starts, km. */
const val SEASON_RANGE_KM = 200.0
/**
 * How much farther ground counts against nearer when a band's forebears chose its seasonal places: a place at
 * the edge of the range is worth 1/(1 + this) of one at home.
 */
const val SEASON_DISTANCE = 0.25f
const val STAY = 0.05f
/** Share of a remembered season's place's worth kept each week the band is elsewhere (about a tenth lost a year). */
const val MEMORY_FADE = 0.998f
/** How much a hungry pack is put off ground it has hunted out lately; weeks of hunger over which it widens its range to three times its usual ground. */
const val HUNGRY_LEAVE = 0.5f
const val GIVE_UP_WEEKS = 4
/**
 * How much a fed pack's hand-written score is put off ground beyond its territory, and ground another pack of
 * its kind has freshly marked: enough that it keeps to its own about as if those places were not weighed
 * at all, while a learned policy sees both and may weigh them otherwise.
 */
const val KEEP_TO_GROUND = 2f
const val KEEP_OFF_MARKS = 2f
/** Chance a chase at a fully catchable quarry succeeds. */
const val CATCH = 0.35f
/**
 * Time making a kill takes, days (the last rush, pulling it down, dragging it off); time each stalk or chase takes, whether
 * or not it ends in a kill (about an hour of a day: stalks of tens of minutes, chases of a minute or two, from memory);
 * and eating at this many kg a day per kg of hunter.
 */
const val HANDLING_DAYS = 0.3f
const val STALK_DAYS = 0.04f
const val EATING_RATE = 0.15f
/** Share of the time a hunting party on the move is searching, and sensing prey on either side as far as its kind senses. */
const val SEARCHING = 0.3f

/**
 * A warm-blooded animal below its comfortable range burns more to keep warm: [COLD_COST] more food
 * for each 10 °C of cold it feels beyond what its coat keeps off, at most [COLD_MAX] times its need.
 * (People's, by their body heat: [BodyHeat].)
 */
fun coldNeed(s: Species, felt: Float, month: Int, tuning: Tuning): Float = min(COLD_MAX, 1f + COLD_COST * tuning.coldCost * s.coldC(felt, month) / 10f)
const val COLD_COST = 0.1f
const val COLD_MAX = 1.6f

/**
 * What the week's going costs [g] in food, as a share of a week's need: each km of net shift (the
 * hexes passed) costs [WALK_COST] of a day's food. (Mammals spend about 2-3 J per kg per m walking,
 * a little under a hundredth of a day's energy per km, whatever their size; the walking within its
 * usual range is part of the day's need already.)
 */
fun HexGroups.walkCost(g: HexGroup): Float = WALK_COST * max(0, g.path.size - 1) * hex.hexKm.toFloat() / days *
    // (People pushing through thick woods and scrub go slower and tire more than in the open.)
    (if (g.species.human) 1f + COVER_GOING * land.plants.cover(g.place) else 1f) +
    // (Gatherers going further than usual walk the difference out and back each day; keeping nearer, they walk less.)
    (g.band?.let { b -> (WALK_COST * GATHER_WALK_KM * (b.gatherRange - 1f) * 0.5f)
        .also { v -> dialUse?.amount(DialSite.GATHER_WALK, b.gatherRange, v, 0f) } } ?: 0f) +
    // (And the height it gained on the way costs as the level walk it equals, see [climbKmPerM].)
    WALK_COST * climbKmPerM(g.species.massKg) * g.climbedM / days
const val WALK_COST = 0.011f

/**
 * The km of level walking that a metre climbed costs as much as, for an animal of [massKg]: lifting its body takes
 * about [CLIMB_J_PER_KG_M] per kg per m gained whatever its size (g over the muscles' ~30% efficiency; Taylor, Caldwell
 * & Rowntree 1972), walking on the level 10.7 M^-0.316 J per kg per m (Taylor, Heglund & Maloiy 1982). So a metre up is
 * about 13 m of level going for a 100 kg animal, 12 m for a person (Naismith's rule has about 8), 6 m for a 10 kg one.
 * Going down is taken as free: on the gentle slopes of a week's going it costs no more than the level.
 */
fun climbKmPerM(massKg: Float): Float = CLIMB_J_PER_KG_M / (10.7f * massKg.pow(-0.316f)) / 1000f
const val CLIMB_J_PER_KG_M = 33f

/**
 * How far a group sees this week against a clear day in the open, 0..1: rain, snow and fog cut the
 * view (half in [SIGHT_RAIN_MM] of rain or snow a week), so does the haze over hot ground, and thick
 * cover hides game from all but those close by.
 */
fun HexGroups.sight(p: Place, weather: Weather): Float {
    val wet = 1f / (1f + weather.precipitation[p] / SIGHT_RAIN_MM)
    val haze = if (weather.temperature[p] > SIGHT_HAZE_C) 0.8f else 1f
    return wet * haze * (1f - SIGHT_COVER * land.plants.cover(Place(p.i)))
}
const val SIGHT_RAIN_MM = 60f
const val SIGHT_HAZE_C = 32f
const val SIGHT_COVER = 0.5f
/**
 * Country: what a band sees of the land from the high points on its way (the line of sight from
 * them, as far as the weather lets it see), at most [SIGHT_RINGS] rings of hexes; and how many more
 * rings scouts range when it sends a share of its people ahead of the camp ([SCOUT_RINGS] when two
 * or more go).
 */
const val SIGHT_RINGS = 4
const val SCOUT_RINGS = 3
/** The scaffold's share of a band's adults out scouting rather than foraging. */
const val SCOUT_SHARE = 0.05f
/**
 * Optimal foraging: how far to each side of its way a hunter finds small game, km; the share of
 * small game met that a hunter of [SMALL_HUNTER_KG] or less catches (bigger ones are clumsier with
 * it); days to catch a kilo of it (many tries for each vole or hare); days to pick a kilo of fruit
 * or dig one of roots; how much longer bones take to crack and eat than meat.
 */
const val SMALL_SENSE_KM = 0.02f
const val SMALL_CATCH = 0.3f
const val SMALL_HUNTER_KG = 15f
const val SMALL_HANDLING_DAYS_PER_KG = 0.3f
const val PLANT_HANDLING_DAYS_PER_KG = 0.15f
const val BONE_DAYS = 3f
/**
 * Digging roots and searching out small finds take this many times as long a kilo as picking fruit; days of
 * all hands to reap, thresh and grind a kilo of grass seed by hand; to catch and clean a kilo of fish.
 * (Grass seed: 0.5 days of 6 working hours is 0.33 kg an hour bare-handed, some 0.6 with sickle and grinder; gathering
 * fallen wild cereal grain in northern Israel gave 0.18-0.58 kg of dehusked grain an hour, 0.34 on average: Kislev,
 * Weiss & Hartman 2004, PNAS 101:2692. Reaping a dense stand with a sickle is faster, unmeasured here.)
 */
const val ROOT_DIGGING = 2f
const val SEED_HANDLING_DAYS_PER_KG = 0.5f
const val FISH_HANDLING_DAYS_PER_KG = 0.3f
/**
 * Days of a band's hands to pick a kilo of wild fruit (dry matter), at a start band's skill: berries one by one off the
 * bush. Hadza berry patches give 1,045 kcal/h (Salvadora persica) to 2,089 kcal/h (Cordia gharaf), !Kung Grewia 434 g of
 * fresh berries an hour, some 500-740 kcal/h (Blurton Jones, Hawkes & Draper 1994, J Anthropol Res 50: 217-248, citing
 * Hawkes, O'Connell & Blurton Jones). At [Nourishment.FRUIT] and a 6-hour day this gives a start band with full baskets
 * about 1,600 kcal/h and a master about 2,300: the top of the Hadza range, an approximation. (The [PLANT_HANDLING_DAYS_PER_KG]
 * that fruit took before, 0.15, was halved from 0.3 to fit the plant share of food when every food was weighed by the kg,
 * and was not looked at again when foods came to be weighed by their energy: some 6,900 kcal/h, 3-14 times the record.)
 */
const val BAND_FRUIT_DAYS_PER_KG = 0.45f
/**
 * Days of a band's hands to gather a kilo of shellfish meat off the shore at low tide: Meriam reef-flat collecting gives
 * 1,492 ± 173 kcal/h (Bliege Bird, Smith & Bird 2001, Behav Ecol Sociobiol 50: 9-19), at [Nourishment.FISH] and a 6-hour
 * day about 0.12 days a kilo. Collecting needs no tool, and its returns go by the collector's size, not by experience
 * (Bliege Bird & Bird 2002, Human Nature 13: 239-267), so no skill slows it.
 */
const val SHELLFISH_DAYS_PER_KG = 0.12f
/** Times a party plans its week's foraging over: if others took what it planned on, it looks again at what is left. */
const val FORAGE_PASSES = 3

/** Ground a hunting party of kind [s] sweeps in a week, km² (shared over the hexes it passes): its going, by the width it senses. */
fun HexGroups.sweptKm2(s: Species) = s.dailyTravelKm * days * 2f * s.senseKm * SEARCHING
/**
 * Ground hunting party [g] searches in a week about place [p], km² (shared over the hexes it passes): [sweptKm2], a
 * bigger party fanning out over more of it (as the square root of its numbers against its kind's usual party), a band's
 * trackers reading sign further off, and less of it seen when rain, haze or cover hide game ([sight]).
 */
fun HexGroups.searchKm2(g: HexGroup, p: Place, weather: Weather) = sweptKm2(g.species) * (g.band?.edge(Skill.TRACK) ?: 1f) *
    sqrt(max(1, g.adults).toFloat() / g.species.groupSize) * sight(p, weather)
/** Share of a kill's weight that is eaten. */
const val MEAT_SHARE = 0.6f
const val HOME_ADVANTAGE = 1.5f
const val RIVAL_KILL = 0.02f
/** Share of a beaten pack's young the winners kill when they take its ground. */
const val INFANTICIDE = 0.25f
/**
 * How watchful game is for coursing hunters in fully open country (times the other odds, at most 0.95 in all), and how a
 * chase goes when they come on it unseen, against [CATCH].
 */
const val VIGILANCE = 0.86f
const val SURPRISE = 1.03f
/**
 * Prey switching (a search image): a hungry hunter chases game of a kind with a chance that grows with how often
 * it has come on that kind lately, m / (m + [SEARCH_HALF]), m counting meetings that fade over [SEARCH_DAYS] (to 1/e).
 * Hunters keep to the game they meet often and pass up what has grown rare, as predators with other prey do in the
 * field (the sigmoid, type III, response that lets scarce prey recover); a new hunter starts at [SEARCH_HALF] of every kind.
 */
const val SEARCH_HALF = 3f
const val SEARCH_DAYS = 180.0
/** Body mass below which an animal can hide in thick cover, kg, and how much of a hunter's chance cover takes away. */
const val HIDING_KG = 70f
const val HIDING = 0.8f
/** How much dark or moonlit nights change a stalker's chances. */
const val MOON_DARK = 0.3f
/** Extra yearly deaths among packs without ground of their own. */
const val DISPERSAL_DEATHS = 0.4f
/** Hoofed animals up to this mass, kg, lose young to eagles. */
const val EAGLE_PREY_KG = 80f
/** Strength ratio beyond which the weaker pack gives way without a fight. */
const val FIGHT_RATIO = 1.5f

/**
 * Carrion: the most of what lies on the ground searched that a scavenger by trade finds (the rest is
 * not found in time, or others get it); and the share of carrion in a kind's diet that makes it one
 * (striped hyenas): kinds that eat less of it find less, as far as their share falls short (a wolf,
 * at 0.15, finds under a third as much).
 */
const val CARRION_FOUND = 0.5f
const val SCAVENGER_CARRION = 0.5f




/** Bands' yearly rounds and rites (see [roundOf]): on unless rounds=false (their strengths in [inflation.Tuning]). */
val ROUNDS = Config.flag(Config.Key.ROUNDS, true)

/** Months over which a group's sense of how thick each kind usually is follows the change (about a year). */
const val USUAL_MONTHS = 12f
