package inflation.ecology

// The numbers the herds and packs on the hexes live by.

/** Days of going a week's net shift amounts to, at the usual pace or a trek's. */
const val SHIFT_DAYS = 2f
/** Snow depth that doubles the effort of going, mm. */
const val SNOW_EFFORT_MM = 150f
/** Days of reserves laid on a week by a well-fed group in late summer and autumn (0.3 the rest of the year). */
const val AUTUMN_FAT = 0.8f
/** Scent marks: share of freshness kept from week to week; below what freshness a mark no longer keeps others off. */
const val MARK_KEEP = 0.85f
const val MARK_FRESH = 0.3f
/** Hunters of other kinds: strength ratio from which the stronger robs a kill (with this chance), and the chance a much stronger party kills one. */
const val THEFT_RATIO = 1.3f
const val THEFT = 0.5f
const val INTRAGUILD_KILL = 0.1f
/** Chance a chase after fully armed prey as big as the party costs a hunter (injuries kill a few per cent of big cats a year, over some dozens of chases). */
const val FIGHT_BACK = 0.002f
/** Catchability below which hunters do not try a herd they meet. */
const val NOT_WORTH_TRYING = 0.05f
/** How much a hunting party is put off a place held by a much stronger party of another kind. */
const val AVOID_STRONGER = 0.3f
/** Soil water below which a herd is in a dry spell, mm; rain ahead that promises relief, mm a week. */
const val DRY_SOIL_MM = 40f
const val RAIN_AHEAD_MM = 20f
/** Prey, kg per km² weighed by catchability, at which a place is half as good as it gets for a hunter. */
const val PREY_HALF = 200f
/** Pull of the season's remembered place, and of staying where one is. */
const val SEASON_PULL = 1.0f
/** How strongly a group follows its sense of better country, per reach moved along it. */
const val BEARING_PULL = 0.5f
/** Strength of the pull to better country beyond which a group treks rather than drifts. */
const val ASTRAY = 0.5f
/** How far a herd's inherited seasonal places may lie from where it starts, km. */
const val SEASON_RANGE_KM = 120.0
const val STAY = 0.15f
/** How much a hungry pack is put off ground it has hunted out lately; weeks of hunger after which it gives up its ground. */
const val HUNGRY_LEAVE = 0.5f
const val GIVE_UP_WEEKS = 4
/** Most chases a hunter makes in a week; chance a chase at a fully catchable quarry succeeds. */
const val MAX_CHASES = 12
const val CATCH = 0.35f
/** Ground a hunting party sweeps in a week, km² (shared over the hexes it passes). */
const val SWEEP_KM2 = 40f
/** Share of a kill's weight that is eaten. */
const val MEAT_SHARE = 0.6f
const val HOME_ADVANTAGE = 1.5f
const val RIVAL_KILL = 0.05f
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
