package inflation.ecology

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
const val MIGRANTS_GATHER = 5f
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
const val HUNTERS_HALF_KG = 1f
/** Share of a migratory herd's weight a hunter's sense of good hunting country counts as game (herds watch and move). */
const val HERD_GAME = 0.3f
/** People: plant food counted against game by weight (it is easier to come by, but not all is worth gathering); how much a safe camp site adds. */
const val PLANT_FOOD_WEIGHT = 0.5f
const val CAMP_SAFETY = 0.5f
/** A band's larder: most it keeps, days of food; share that spoils or is lost a week. */
const val LARDER_DAYS = 120f
const val LARDER_SPOIL = 0.01f
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
/** For tracing: the kind whose herds' reasons the plan prints (-Dhex.planDiag=kind). */
val PLAN_DIAG: String? = System.getProperty("hex.planDiag")
/** How strongly a group follows its sense of better country, per reach moved along it. */
const val BEARING_PULL = 0.5f
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
/** Chance a chase at a fully catchable quarry succeeds. */
const val CATCH = 0.35f
/** Time a kill takes, days: the chase and making the kill, and eating it at this many kg a day per kg of hunter. */
const val HANDLING_DAYS = 0.3f
const val EATING_RATE = 0.15f
/** Share of the time a hunting party on the move is searching, and sensing prey on either side as far as its kind senses. */
const val SEARCHING = 0.3f

/** Ground a hunting party of kind [s] sweeps in a week, km² (shared over the hexes it passes): its going, by the width it senses. */
fun HexGroups.sweptKm2(s: Species) = s.dailyTravelKm * days * 2f * s.senseKm * SEARCHING
/** Share of a kill's weight that is eaten. */
const val MEAT_SHARE = 0.6f
const val HOME_ADVANTAGE = 1.5f
const val RIVAL_KILL = 0.05f
/** Share of a beaten pack's young the winners kill when they take its ground. */
const val INFANTICIDE = 0.5f
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
