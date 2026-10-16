package inflation.ecology

import inflation.Config

// The numbers the herds and packs on the hexes live by.

/** Days of going a week's net shift amounts to, at the usual pace or a trek's. */
const val SHIFT_DAYS = 2f
/** Snow depth that doubles the effort of going, mm. */
const val SNOW_EFFORT_MM = 150f
/** Days of reserves laid on a week by a well-fed group in late summer and autumn (0.3 the rest of the year). */
/** How much more than its needs an animal eats, at most, when food is plentiful and its reserves are short; share of that surplus laid on as reserves. */
const val FATTEN = 0.3f
const val FAT_EFFICIENCY = 0.6f
/** A newborn's weight against its mother's, paid from her reserves. */
const val NEWBORN_SHARE = 0.08f
/** Share of its fill above which a group lays on reserves rather than drawing on them (a week's food is never exactly what it needs). */
const val WELL_FED = 0.9f
/** How many times its usual most a herd may grow to on a full green flush, well fed (herds gather where the grass is new). */
const val AGGREGATION = 4f
/** How far a part of a group's diet stretches to make up for another that fell short: to (1 + this) times its usual share. */
const val SWITCH = 1f
/** Scent marks: share of freshness kept from week to week; below what freshness a mark no longer keeps others off. */
const val MARK_KEEP = 0.85f
const val MARK_FRESH = 0.3f
/** Hunters of other kinds: strength ratio from which the stronger robs a kill (with this chance), and the chance a much stronger party kills an adult of the other, or one of its young. */
const val THEFT_RATIO = 1.3f
const val THEFT = 0.5f
const val INTRAGUILD_KILL = 0.01f
const val INTRAGUILD_KILL_YOUNG = 0.1f
/** Chance a chase after fully armed prey as big as the party costs a hunter (injuries kill a few per cent of big cats a year, over some dozens of chases). */
const val FIGHT_BACK = 0.002f
/** Catchability below which hunters do not try a herd they meet. */
const val NOT_WORTH_TRYING = 0.05f
/** How much a hunting party is put off a place held by a much stronger party of another kind. */
const val AVOID_STRONGER = 0.3f
/** How many times its usual density a migratory herd bears where herds gather before crowding sickens it. */
const val MIGRANTS_GATHER = 20f
/**
 * A herd agent stands for this many of its kind's usual bands (a gazelle herd of 120 rather than 12):
 * herds that move together are one agent, which keeps their numbers, and the step's cost, down.
 */
const val HERD_UNIT = 10
/** Herds lean to sizes from this many times smaller to this many times larger than their kind's usual. */
const val SIZE_PREF_RANGE = 3f
/** How much of a courser's success thick cover takes away (a cheetah in a wood runs into trees). */
const val COURSING_COVER = 0.7f
/** How much a herd or pack weighs its own kind of country (the log of [HexFauna.suits]) in choosing its ground. */
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
/** Newcomers: a kind with fewer groups than this gets this many more once a year (see [HexGroups.newcomers]). */
/** Sea crossings: most hexes of open sea between two coasts that count as a strait; effort of each km swum against a km walked; share of a swimming herd that drowns on a strait as wide as all it can swim (young twice that). */
const val SEA_HEXES = 2
const val SWIM_EFFORT = 10f
const val DROWN = 0.05f
const val NEWCOMER_BELOW = 5
const val NEWCOMERS = 3
/** What a band takes a sign it cannot read to be: a middling value, neither good nor bad. */
const val SIGN_GUESS = 0.3f
/** How much harder thick cover makes people's going; share of what the camp's own hex gives that each hex round it gives a band's gatherers at the usual range. */
const val COVER_GOING = 0.5f
const val RING_REACH = 0.15f
/** How much the old, the sick and the lamed slow a band's going (all of it so: this share slower). */
const val WEAK_SLOW = 0.6f
/**
 * The sides of a herd's size, against its kind's usual band: how its walking slows, how much less
 * each attempt on it succeeds (many eyes, confusion), and how much less often its young are taken
 * (they keep to the middle), each as a power of the bands it is; under how many bands a herd can lie
 * hidden in cover, and how much of its being found the best cover saves a lone band.
 */
const val BIG_HERD_SLOW = 0.05f
const val BIG_HERD_EYES = 0.15f
const val BIG_HERD_SHELTERS = 0.3f
const val SMALL_HERD_BANDS = 3f
const val HIDE_IN_COVER = 0.6f
/** A content herd weighs its places one week in this many (others keep to their ground unweighed). */
const val CONTENT_WEEKS = 2
/** A content band choosing by a network weighs its places one week in this many: every week, as everything a band does is its choice (hand-written bands: every other week). */
const val BAND_CONTENT_WEEKS = 1
/** Hunting is worked region by region in squares this wide, km (wider than twice any party's week). */
const val HUNT_BLOCK_KM = 300f
/** A herd that shares its plan still weighs where to go for itself one week in this many (see [HexGroups.step]). */
const val OWN_PLAN_WEEKS = 3
/** Herds and packs search and weigh every hex within this many hexes of where they are, and beyond it one hex in each two by two (see [HexGroups.planInner]). */
const val COARSE_FROM_HEXES = 3.0
/** Parties that kept near home this week are worked in squares this wide, km (see [hunt]). */
const val HUNT_FINE_KM = 100f
/** How far a lone hunter goes looking for a mate in a week, km. */
const val MATE_SEARCH_KM = 50.0
/** How much the best shelter takes off a hunter's chance; how likely a group that senses danger first slips away. */
const val SHELTER_EFFECT = 0.7f
const val AVOID_SKILL = 0.6f
/** How much more ground that matters a hunter covers when it follows a herd's tracks than when it happens on it (lions try a hunt about every day or two). */
const val TRACKING = 8f
/** How well fed a group must be to keep to its ground without weighing where else to go (every other week). */
const val CONTENT = 0.95f
/** How much herds shun ground thick with hunters; hunters, kg per km², at which the shunning is half. */
const val SHUN_HUNTERS = 0.3f
val HUNTERS_HALF_KG = Config.float("t.huntersHalf", 1f)
/** Share of a migratory herd's weight a hunter's sense of good hunting country counts as game (herds watch and move). */
const val HERD_GAME = 0.3f
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
/** Soil water below which a herd is in a dry spell, mm; rain ahead that promises relief, mm a week. */
const val DRY_SOIL_MM = 40f
const val RAIN_AHEAD_MM = 20f
/** Prey, kg per km² weighed by catchability, at which a place is half as good as it gets for a hunter. */
const val PREY_HALF = 200f
/** Pull of the season's remembered place, and of staying where one is. */
const val SEASON_PULL = 1.0f
/** For tracing: the kind whose herds' reasons the plan prints (planDiag=kind). */
val PLAN_DIAG: String? = Config.string("planDiag")
/** How strongly a group follows its sense of better country, per reach moved along it. */
val BEARING_PULL = Config.float("t.bearingPull", 0.5f)
/** Strength of the pull to better country beyond which a group treks rather than drifts. */
const val ASTRAY = 0.5f
/** How far a herd's inherited seasonal places may lie from where it starts, km. */
const val SEASON_RANGE_KM = 200.0
/** How much farther ground counts against nearer when a herd's forebears chose its seasonal places: a place at the edge of the range is worth 1/(1 + this) of one at home. */
const val SEASON_DISTANCE = 0.25f
const val STAY = 0.05f
/** Share of a remembered season's place's worth kept each week the herd is elsewhere (about a tenth lost a year). */
const val MEMORY_FADE = 0.998f
/** How much a hungry pack is put off ground it has hunted out lately; weeks of hunger over which it widens its range to three times its usual ground. */
const val HUNGRY_LEAVE = 0.5f
const val GIVE_UP_WEEKS = 4
/** How much a fed pack's hand-written score is put off ground beyond its territory, and ground another pack of its kind has freshly marked: enough that it keeps to its own as it did when those places were not weighed at all, while a learned policy sees both and may weigh them otherwise. */
const val KEEP_TO_GROUND = 2f
const val KEEP_OFF_MARKS = 2f
/** Chance a chase at a fully catchable quarry succeeds. */
const val CATCH = 0.35f
/** Time a kill takes, days: the chase and making the kill, and eating it at this many kg a day per kg of hunter. */
const val HANDLING_DAYS = 0.3f
const val EATING_RATE = 0.15f
/** Share of the time a hunting party on the move is searching, and sensing prey on either side as far as its kind senses. */
const val SEARCHING = 0.3f

/**
 * A warm-blooded animal below its comfortable range burns more to keep warm: [COLD_COST] more food
 * for each 10 °C of cold it feels beyond what its coat (or clothes and house) keeps off, at most
 * [COLD_MAX] times its need.
 */
fun coldNeed(s: Species, felt: Float, month: Int): Float = kotlin.math.min(COLD_MAX, 1f + COLD_COST * Tuning.coldCost * s.coldC(felt, month) / 10f)
const val COLD_COST = 0.1f
const val COLD_MAX = 1.6f

/**
 * What the week's going costs [g] in food, as a share of a week's need: each km of net shift (the
 * hexes passed) costs [WALK_COST] of a day's food. (Mammals spend about 2-3 J per kg per m walking,
 * a little under a hundredth of a day's energy per km, whatever their size; the walking within its
 * usual range is part of the day's need already.)
 */
fun HexGroups.walkCost(g: HexGroup): Float = WALK_COST * kotlin.math.max(0, g.path.size - 1) * hex.hexKm.toFloat() / days *
    // (People pushing through thick woods and scrub go slower and tire more than in the open.)
    (if (g.species.human) 1f + COVER_GOING * land.cover(g.place) else 1f) +
    // (Gatherers going further than usual walk the difference out and back each day.)
    (g.band?.let { b -> if (b.gatherRange > 1f) WALK_COST * GATHER_WALK_KM * (b.gatherRange - 1f) * 0.5f else 0f } ?: 0f)
const val WALK_COST = 0.011f

/**
 * How far a group sees this week against a clear day in the open, 0..1: rain, snow and fog cut the
 * view (half in [SIGHT_RAIN_MM] of rain or snow a week), so does the haze over hot ground, and thick
 * cover hides game from all but those close by.
 */
fun HexGroups.sight(p: Int): Float {
    val wet = 1f / (1f + weatherNow.precipitation[p] / SIGHT_RAIN_MM)
    val haze = if (weatherNow.temperature[p] > SIGHT_HAZE_C) 0.8f else 1f
    return wet * haze * (1f - SIGHT_COVER * land.cover(p))
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
/** Digging roots and searching out small finds take this many times as long a kilo as picking fruit; days of all hands to reap, thresh and grind a kilo of grass seed by hand; to catch and clean a kilo of fish. */
const val ROOT_DIGGING = 2f
const val SEED_HANDLING_DAYS_PER_KG = 0.5f
const val FISH_HANDLING_DAYS_PER_KG = 0.3f
/** Times a party plans its week's foraging over: if others took what it planned on, it looks again at what is left. */
const val FORAGE_PASSES = 3

/** Ground a hunting party of kind [s] sweeps in a week, km² (shared over the hexes it passes): its going, by the width it senses. */
fun HexGroups.sweptKm2(s: Species) = s.dailyTravelKm * days * 2f * s.senseKm * SEARCHING
/** Share of a kill's weight that is eaten. */
const val MEAT_SHARE = 0.6f
const val HOME_ADVANTAGE = 1.5f
const val RIVAL_KILL = 0.02f
/** Share of a beaten pack's young the winners kill when they take its ground. */
const val INFANTICIDE = 0.25f
/** Most meetings settled one by one for a group in a week. */
const val MAX_MEETINGS = 4
/** Chance a herd in fully open country sees coursing hunters first; how much likelier a kill when hunters come unseen. */
const val VIGILANCE = 0.6f
/** Wariness gained in a chase, and kept from week to week. */
const val WARY_GAIN = 0.3f
const val WARY_KEEP = 0.97f
/** Body mass below which an animal can hide in thick cover, kg, and how much of a hunter's chance cover takes away. */
const val HIDING_KG = 70f
const val HIDING = 0.8f
/** How much dark or moonlit nights change a stalker's chances. */
const val MOON_DARK = 0.3f
/** Extra yearly deaths among packs without ground of their own. */
const val DISPERSAL_DEATHS = 0.4f
/** Hoofed animals up to this mass, kg, lose young to eagles. */
const val EAGLE_PREY_KG = 80f
const val SURPRISE = 1.5f
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



/**
 * Herds' heading (see [HexGroup.headE]): share of last week's kept; weight of the way it actually went
 * against its sense of better country in bending it; and how strongly it draws the next week's choice
 * (as the bearing's [BEARING_PULL]): small persistent nudges that add up to a migration.
 */
val HEADING_KEEP = Config.float("t.headingKeep", 0.95f)
val HEADING_MOVED = Config.float("t.headingMoved", 0.5f)
val HEADING_PULL = Config.float("t.headingPull", 2f)

/** Yearly rounds, herds' and bands' rites (see [growRounds], [roundOf]): on only with rounds=true (being tried locally); how strongly a herd's round draws, as a season's place of that quality would. */
val ROUNDS = Config.flag("rounds", false)
val ROUND_STRENGTH = Config.float("t.roundStrength", 1f)
/** How strongly a herd's round draws a learned mover's choice, outside its network (per unit of the "on round" feature). */
val ROUND_NET_PULL = Config.float("t.roundNetPull", 1.5f)
/** How strongly a round draws the hand-written choice: per unit of progress toward its place this month (a full week's going toward it). */
val ROUND_PULL = Config.float("t.roundPull", 1.5f)
/** Within this of its round's place this month, km, a herd has arrived and grazes as it likes (half a range across). */
val ROUND_ARRIVED_KM = Config.double("t.roundArrived", 50.0)
/** Packs tied to their dens: months from the births a pack with young hunts near its den; how near, km. Not measured: wolves and hyenas keep to dens for two to three months, lions to their cubs' lairs about as long. */
val DEN_MONTHS = Config.int("t.denMonths", 3)
val DEN_KM = Config.float("t.denKm", 15f)
/** Days of a week a herd far behind its round spends trekking on (the rest grazing). */
val ROUND_TREK_DAYS = Config.float("t.roundTrekDays", 4f)

/**
 * Herds graze only the ground within reach of water, as far as their kind must drink (see [HexFauna.tether]),
 * as the residents do, and value places by what they can graze so; false: they graze a whole place wherever its water lies.
 */
val HERD_WATER_TIE = Config.flag("t.herdWaterTie", true)
