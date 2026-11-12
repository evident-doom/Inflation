package inflation.ecology

import inflation.Trace
import inflation.world.Place
import inflation.world.get
import inflation.world.set

import inflation.world.Date
import inflation.Tuning
import inflation.monthsFrom
import inflation.Config
import inflation.world.Weather
import inflation.world.HexLand
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

// The numbers the packs and bands on the hexes live by.

/** Days of going a tick's (6 days') net shift amounts to, at the usual pace or a trek's; a longer step reaches further ([GroupWorld.stepShift]). */
internal const val SHIFT_DAYS = 2f
/** Snow depth that doubles the effort of going, mm. */
internal const val SNOW_EFFORT_MM = 150f
/** How much more than its needs an animal eats, at most, when food is plentiful and its reserves are short; share of that surplus laid on as reserves. */
internal const val SURPLUS_EAT = 0.3f
internal const val FAT_EFFICIENCY = 0.6f
/** A newborn's weight against its mother's, paid from her reserves. */
internal const val NEWBORN_SHARE = 0.08f
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
    val within = seasonDays(s, start, date, days)
    if (within <= 0) return 0f
    // (A step that reaches past the season's end bears all she has left; else the season's days it holds.)
    val last = monthsFrom(start, date.plusDays(days).month) >= s.birthMonths
    return if (last) left else min(left, within / (s.birthMonths * Date.DAYS_PER_MONTH.toFloat()))
}

/**
 * The days of the step of [days] from [date] that fall in [s]'s birth season beginning in month [start]: counted day by
 * day of the step, not by the month it starts in, so a step longer than a month neither skips a season nor bears through
 * months outside it.
 */
fun seasonDays(s: Species, start: Int, date: Date, days: Int): Int = seasonDays(start, s.birthMonths, date, days)

/** The days of the step of [days] from [date] in a season of [months] months beginning in month [start] ([seasonDays]). */
fun seasonDays(start: Int, months: Int, date: Date, days: Int): Int {
    var n = 0; var d = 0
    while (d < days) {
        val at = date.plusDays(d); val run = min(days - d, Date.DAYS_PER_MONTH - (at.dayOfMonth - 1))
        if (monthsFrom(start, at.month) < months) n += run
        d += run
    }
    return n
}

/** The month a mother of [s] whose season starts in month [start] starts her year again: the middle of the months outside it. */
fun offSeason(s: Species, start: Int) = (start + s.birthMonths + (12 - s.birthMonths) / 2) % 12

/** Days of reserves a mother of [s] fed [fed] over [days] gives up for [young] young: what her spare food did not build. */
fun fromReserves(s: Species, young: Float, fed: Float, days: Int) = max(0f, young * youngDays(s) - spareDays(fed, days))
/** Share of its fill above which a group lays on reserves rather than drawing on them (a week's food is never exactly what it needs). */
internal const val WELL_FED = 0.9f
/**
 * How far browse stretches to make up for grass under snow: to (1 + this) times its usual share; and how far a fed
 * hunter lays on fat from each of its foods. A shortfall is otherwise made up as far as the gut holds (grass, browse)
 * or the place has the food (fruit, roots, prey, carrion).
 */
internal const val SWITCH = 1f
/** Scent marks: share of freshness kept from week to week; below what freshness a mark no longer keeps others off. */
internal const val MARK_KEEP = 0.85f
internal const val MARK_FRESH = 0.3f
/**
 * Hunters of other kinds: strength ratio from which the stronger robs a kill (with this chance), and the
 * chance a much stronger party kills an adult of the other, or one of its young.
 */
internal const val THEFT_RATIO = 1.3f
internal const val THEFT = 0.5f
internal const val INTRAGUILD_KILL = 0.01f
internal const val INTRAGUILD_KILL_YOUNG = 0.1f
/** The share of a much stronger party's chances at young in hiding that come to nothing ([youngHidden]); an estimate, not checked. */
internal const val YOUNG_HIDDEN = 0.5f
/** How much a hunting party is put off a place held by a much stronger party of another kind. */
internal const val AVOID_STRONGER = 0.3f
/** How much of a courser's success thick cover takes away (a cheetah in a wood runs into trees). */
internal const val COURSING_COVER = 0.7f
/** How much a pack weighs its own kind of country (the log of [HexFauna.suits]) in choosing its ground. */
internal const val GROUP_HABITAT = 0.15f
/**
 * Paths: share of the way to fully worn one passage of a group of [PATH_MASS_KG] or more wears;
 * weekly share plants take back at the greenest (less on bare ground); weight of a group that wears
 * a path at the full rate, kg; how much a fully worn path eases the going on middling ground (more
 * on broken); how much likelier hunters meet game, or people, on a busy path (they wait there).
 */
internal const val PATH_WEAR = 0.05f
internal const val PATH_FADE = 0.03f
internal const val PATH_MASS_KG = 3000f
/**
 * How much a week of a band living in a hex wears each of its six borders, as a share of what a through going wears
 * ([PATH_WEAR]), before its weight ([PATH_MASS_KG]): a band of 25 wears its borders toward about half worn,
 * where plants regrow at the usual pace ([PATH_FADE]), within a few months; a guess, unmeasured.
 */
internal const val CAMP_WEAR = 0.05f
internal const val PATH_EASE = 0.5f
internal const val AMBUSH = 1f
/**
 * Sea crossings: most hexes of open sea between two coasts that count as a strait; effort of each km swum
 * against a km walked; share of a swimming group that drowns on a strait as wide as all it can swim (young
 * twice that).
 */
internal const val SEA_HEXES = 2
internal const val SWIM_EFFORT = 10f
internal const val DROWN = 0.05f
/** Newcomers: a kind with fewer groups than this gets this many more once a year (see [HexGroups.newcomers]). */
internal const val NEWCOMER_BELOW = 5
internal const val NEWCOMERS = 3
/** What a band takes a sign it cannot read to be: a middling value, neither good nor bad. */
internal const val SIGN_GUESS = 0.3f
/**
 * How much harder thick cover makes people's going; share of what the camp's own hex gives that each hex
 * round it gives a band's gatherers at the usual range.
 */
internal const val COVER_GOING = 0.5f
internal const val RING_REACH = 0.15f
/** How much the old, the sick and the lamed slow a band's going (all of it so: this share slower). */
internal const val WEAK_SLOW = 0.6f
/** Hunting is worked region by region in squares this wide, km (wider than any party's week, see [hunt]). */
internal const val HUNT_BLOCK_KM = 150f
/** Parties that kept near home this week are worked in squares this wide, km (see [hunt]). */
internal const val HUNT_FINE_KM = 50f
/** How far a lone hunter goes looking for a mate in a week, km. */
internal const val MATE_SEARCH_KM = 50.0
/** How much the best shelter takes off a hunter's chance; how likely a group that senses danger first slips away. */
internal const val SHELTER_EFFECT = 0.7f
internal const val AVOID_SKILL = 0.6f
/** People: plant food counted against game by weight (it is easier to come by, but not all is worth gathering); how much a safe camp site adds. */
internal const val PLANT_FOOD_WEIGHT = 0.5f
internal const val CAMP_SAFETY = 0.5f
/** Days of food a band has stored when the world begins. */
internal const val START_LARDER_DAYS = 30f
/**
 * Founding bands' dress for their country ([HexGroups.dressForCountry]): from a half kit where the coldest month is
 * [FOUNDER_WARM_C] °C or milder to a whole one where it is [FOUNDER_COLD_C] or colder, and sewing that much nearer mastery
 * by [FOUNDER_COLD_SEWING] (estimates: people of cold countries wear whole, tailored outfits, as the Inuit's of caribou
 * hide; the bounds are a guess). No band is founded where the warmest month is under [FOUNDER_TREELINE_C], the alpine
 * meadow's bound ([inflation.world.Biomes]).
 */
internal const val FOUNDER_WARM_C = 10f
internal const val FOUNDER_COLD_C = -5f
internal const val FOUNDER_COLD_SEWING = 0.5f
internal const val FOUNDER_TREELINE_C = 10f
/**
 * The spread of people's bands' sizes when the world begins, as the sd of their log: foragers' residential camps run from about
 * half to about twice the usual size, a dozen to fifty or so about a mean of 25-30 (Kelly 1995; Marlowe 2005; both from memory,
 * to check), which a lognormal of sd 0.4 spans in nine bands of ten (an estimate fitted to that range).
 */
internal const val START_BAND_SPREAD = 0.4f
/**
 * The same for packs: wolf packs run from a pair to a dozen or more about a mean of 5-8, lion prides and hyena clans as widely
 * about theirs (Mech and Boitani 2003; Kruuk 1972; both from memory, to check): a lognormal of sd 0.5 (an estimate).
 */
internal const val START_PACK_SPREAD = 0.5f
/** Share of its larder a band gives one that is going hungry, when they meet. */
internal const val BAND_GIFT = 0.3f
/** Chance a band burns off the dry grass around its camp in a dry-season week, to bring up green grass that draws game. */
internal const val BURN_OFF = 0.1f
/** How much a camp fire through the night keeps lions and hyenas from taking people. */
internal const val CAMPFIRE = 0.7f
/** Soil water below which a place is in a dry spell, mm; rain ahead that promises relief, mm a week. */
internal const val DRY_SOIL_MM = 40f
internal const val RAIN_AHEAD_MM = 20f
/** Prey, kg per km² weighed by catchability, at which a place is half as good as it gets for a hunter. */
internal const val PREY_HALF = 200f
/** planDiag=kind: once a month, the reasons of one in 97 of that kind's groups for the place it chose, on stderr. */
internal val PLAN_REASONS = Trace(Config.Key.PLAN_DIAG, "plan reasons", share = 1f / 97, byKind = true)
/** Strength of the pull to better country beyond which a group treks rather than drifts. */
internal const val ASTRAY = 0.5f
/** How far a band's inherited seasonal places may lie from where it starts, km. */
internal const val SEASON_RANGE_KM = 200.0
/**
 * How much farther ground counts against nearer when a band's forebears chose its seasonal places: a place at
 * the edge of the range is worth 1/(1 + this) of one at home.
 */
internal const val SEASON_DISTANCE = 0.25f
internal const val STAY = 0.05f
/** Share of a remembered season's place's worth kept each week the band is elsewhere (about a tenth lost a year). */
internal const val MEMORY_FADE = 0.998f
/** How much a hungry pack is put off ground it has hunted out lately; weeks of hunger over which it widens its range to three times its usual ground. */
internal const val HUNGRY_LEAVE = 0.5f
internal const val GIVE_UP_WEEKS = 4
/**
 * How much a fed pack's hand-written score is put off ground beyond its territory, and ground another pack of
 * its kind has freshly marked: enough that it keeps to its own about as if those places were not weighed
 * at all, while a learned policy sees both and may weigh them otherwise.
 */
internal const val KEEP_TO_GROUND = 2f
internal const val KEEP_OFF_MARKS = 2f
/** Chance a chase at a fully catchable quarry succeeds. */
internal const val CATCH = 0.35f
/**
 * Time making a kill takes, days (the last rush, pulling it down, dragging it off); time each stalk or chase takes, whether
 * or not it ends in a kill (about an hour of a day: stalks of tens of minutes, chases of a minute or two, from memory);
 * and eating at this many kg a day per kg of hunter.
 */
internal const val HANDLING_DAYS = 0.3f
internal const val STALK_DAYS = 0.04f
internal const val EATING_RATE = 0.15f
/** Share of the time a hunting party on the move is searching, and sensing prey on either side as far as its kind senses. */
internal const val SEARCHING = 0.3f

/**
 * A warm-blooded animal below its comfortable range burns more to keep warm: [COLD_COST] more food
 * for each 10 °C of cold it feels beyond what its coat keeps off, at most [COLD_MAX] times its need.
 * (People's, by their body heat: [BodyHeat].)
 */
fun coldNeed(s: Species, felt: Float, month: Int, tuning: Tuning): Float = min(COLD_MAX, 1f + COLD_COST * tuning.coldCost * s.coldC(felt, month) / 10f)
internal const val COLD_COST = 0.1f
internal const val COLD_MAX = 1.6f

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
    (g.band?.let { b -> dialed(DialSite.GATHER_WALK, b.gatherRange, WALK_COST * GATHER_WALK_KM * (b.gatherRange - 1f) * 0.5f) { 0f } } ?: 0f) +
    // (And the height it gained on the way costs as the level walk it equals, see [climbKmPerM].)
    WALK_COST * climbKmPerM(g.species.massKg) * g.climbedM / days
internal const val WALK_COST = 0.011f

/**
 * The km of level walking that a metre climbed costs as much as, for an animal of [massKg]: lifting its body takes
 * about [CLIMB_J_PER_KG_M] per kg per m gained whatever its size (g over the muscles' ~30% efficiency; Taylor, Caldwell
 * & Rowntree 1972), walking on the level 10.7 M^-0.316 J per kg per m (Taylor, Heglund & Maloiy 1982). So a metre up is
 * about 13 m of level going for a 100 kg animal, 12 m for a person (Naismith's rule has about 8), 6 m for a 10 kg one.
 * Going down is taken as free: on the gentle slopes of a week's going it costs no more than the level.
 */
fun climbKmPerM(massKg: Float): Float = CLIMB_J_PER_KG_M / (10.7f * massKg.pow(-0.316f)) / 1000f
internal const val CLIMB_J_PER_KG_M = 33f

/**
 * How far a group sees this week against a clear day in the open, 0..1: rain, snow and fog cut the
 * view (half in [SIGHT_RAIN_MM] of rain or snow a week), so does the haze over hot ground, and thick
 * cover hides game from all but those close by.
 */
fun GroupWorld.sight(p: Place, weather: Weather): Float = sightAt(land, p, weather)

/** [GroupWorld.sight] at place [p] of [land], for any hunter there (a pack's party or a resident's). */
fun sightAt(land: HexLand, p: Place, weather: Weather): Float {
    val wet = 1f / (1f + weather.tickPrecipitation(p) / SIGHT_RAIN_MM)
    val haze = if (weather.temperature[p] > SIGHT_HAZE_C) 0.8f else 1f
    return wet * haze * (1f - SIGHT_COVER * land.plants.cover(p))
}
internal const val SIGHT_RAIN_MM = 60f
internal const val SIGHT_HAZE_C = 32f
internal const val SIGHT_COVER = 0.5f
/**
 * Country: what a band sees of the land from the high points on its way (the line of sight from
 * them, as far as the weather lets it see), at most [SIGHT_RINGS] rings of hexes; and how many more
 * rings scouts range when it sends a share of its people ahead of the camp ([SCOUT_RINGS] when two
 * or more go).
 */
internal const val SIGHT_RINGS = 4
internal const val SCOUT_RINGS = 3
/** The scaffold's share of a band's adults out scouting rather than foraging. */
internal const val SCOUT_SHARE = 0.05f
/**
 * Optimal foraging: how far to each side of its way a hunter finds small game, km; the share of
 * small game met that a hunter of [SMALL_HUNTER_KG] or less catches (bigger ones are clumsier with
 * it); days to catch a kilo of it (many tries for each vole or hare); days to pick a kilo of fruit
 * or dig one of roots; how much longer bones take to crack and eat than meat.
 */
internal const val SMALL_SENSE_KM = 0.02f
internal const val SMALL_CATCH = 0.3f
internal const val SMALL_HUNTER_KG = 15f
internal const val SMALL_HANDLING_DAYS_PER_KG = 0.3f
/**
 * Share of the small game a hunter would find on bare ground that full ground cover hides from it ([HexPlants.groundCover]):
 * burnt ground shows its burrows and tracks (Bird, Bird & Parker 2005, Hum. Ecol. 33:443: Martu burning raises the
 * returns on burrowed small prey; the size here is an estimate).
 */
internal const val SMALL_HIDE = 0.5f
internal const val PLANT_HANDLING_DAYS_PER_KG = 0.15f
internal const val BONE_DAYS = 3f
/**
 * Digging roots and searching out small finds take this many times as long a kilo as picking fruit; days of
 * all hands to reap, thresh and grind a kilo of grass seed by hand; to catch and clean a kilo of fish.
 * (Grass seed: 0.5 days of 6 working hours is 0.33 kg an hour bare-handed, some 0.6 with sickle and grinder; gathering
 * fallen wild cereal grain in northern Israel gave 0.18-0.58 kg of dehusked grain an hour, 0.34 on average: Kislev,
 * Weiss & Hartman 2004, PNAS 101:2692. Reaping a dense stand with a sickle is faster, unmeasured here.)
 */
internal const val ROOT_DIGGING = 2f
/**
 * Ground a band's gatherer searches for roots and bulbs in a day, m². Roots must be found before they are dug, so
 * thin ground takes longer a kilo: Holling's disc equation, days a kilo = digging + 1 / (search x density). Fitted to one
 * pair of figures. Naive foragers in the southern Cape took about two hours to 2,000 kcal: half of 26 events got there
 * within two hours (Singels et al. 2015, PeerJ PrePrints 3:e1573). The ground held 52-160 kg/ha of edible underground
 * organs, fresh (Singels 2013), at about 1.9 kcal/g. The digging time here alone gives some 1,400 kcal/h, so the search
 * takes the rest: about 190 m² searched an hour, or 1,100 m² over a 6-hour day. This is an approximation. Skilled foragers
 * search faster than naive ones, and the fit rests on the digging time being right. Fallen mast, counted in the same stock,
 * is easier to find than bulbs.
 */
internal const val ROOT_SEARCH_M2_PER_DAY = 1100f
/**
 * Ground a band's gatherer scans for fruit on the bush, mast under the trees and ripe seed heads in a day, m²: as for roots
 * ([ROOT_SEARCH_M2_PER_DAY]), the thinner the food the longer a kilo, but fruit and nuts are seen, not dug for. An estimate,
 * no measured figure found: some 2 km/h while searching (half the walking pace, [WALK_KM_PER_HOUR]) over a 6-hour day
 * and a swath of some 5 m in which a bush's fruit or a scatter of acorns is seen, 60,000 m². Field returns with travel
 * and processing run several times below those in the patch (mongongo 1,600-5,200 kcal/h on site, 460-910 with travel
 * and processing: Blurton Jones, Hawkes & Draper 1994), but most of that gap is the walk to the groves ([GATHER_LOAD_KG])
 * and the cracking; the search itself costs little where fruit or mast is thick.
 */
internal const val PLANT_SIGHT_M2_PER_DAY = 60_000f
/**
 * The share of the mast that is bitter and must be leached before it is eaten: the acorns of most oaks, and wild bitter
 * almonds, against beech nuts, hazel nuts, pine nuts and sweet acorns. An estimate, no measured share found.
 */
internal const val MAST_BITTER = 0.7f
/**
 * Days of a band's hands to pound and leach a dry kg of bitter mast, at a start band's skill, on top of gathering and
 * shelling it ([BAND_FRUIT_DAYS_PER_KG]). From two small trials, weak sources: the whole process came to some 364 kcal/h
 * (Garza, a hobby trial of 100 acorns), or about 11 h a kg of kernel at 4.0 kcal/g (Kievman et al. 2025); gathering and
 * shelling took some 4 h a kg of kernel (Low Tech Institute 2017), leaving about 7 h, 1.2 days of 6 hours. Acorn processing
 * takes much more labour for its food than most plant foods (Basgall 1987, as cited by Glassow; no figure).
 */
internal const val MAST_LEACH_DAYS_PER_KG = 1.2f
/**
 * Reaping grain ([reapDaysPerKg]):
 * - [CUT_M2_PER_HOUR]: ground a reaper covers an hour by hand, uprooting or snapping off the ears: uprooting einkorn 10 m² an
 *   hour and bread wheat 5, a deer's jaw 9 (Häg 2024, "An experimental investigation of alternative Neolithic harvesting
 *   tools", EXARC 2024/4). 8. With a sickle ([SICKLE_GAIN]) some 25.
 * - [THRESH_DAYS_PER_KG]: threshing, winnowing and cleaning a kilo. A flail threshes some 8 bushels of wheat a day, 20-27 kg an
 *   hour (McConnell 1911 and Atack & Passell 1994, as cited in Wikipedia's "Threshing"), 0.007 days of 6 hours a kilo; hand
 *   threshing of sorghum and millet some 75 hours a hectare (van Heemst, WUR). Winnowing and cleaning, and parching and
 *   pounding the hulled wheats out of their husks, were not found: 0.05, some seven times the flail's rate, an allowance for
 *   them (unsourced).
 * - [REF_STAND_KG_M2]: the stand at which bands reaped wild cereal in a traced year (stand trace, main 93d43621, m5-1 and
 *   m5-3: kg-weighted medians 0.0019 and 0.0028 kg/m², 2026-11-04). At it cutting takes some 3 days a kilo even with a
 *   sickle: the stands of the record were hundreds of kg/ha (Harlan took some 2 kg an hour in dense wild einkorn, from
 *   memory), the model's were some 20-30: wild cereals now stand as dense patches ([HexFood.WILD_STAND_GRAIN]), and this is
 *   only the stand against which a band's seed days are priced.
 * - Grass seed ([smallSeedDaysPerKg]): beaten into baskets, parched and winnowed, small seeds return some 300-1,000 kcal/h,
 *   about 600 after they are found (Gremillion 2004, and Simms 1987 as consistent with it, both as cited in a review of the
 *   Eastern Agricultural Complex; secondary): some 0.17 kg an hour, about 1 day a kilo, of it grinding [GRIND_DAYS_PER_KG],
 *   so [SMALL_SEED_DAYS_PER_KG] 0.75, where it stands at its traced median over the place, [REF_SEED_STAND_KG_M2]
 *   (0.00012-0.00020 kg/m²); the beating share of it [REAP_CUT_SHARE] goes by the ground covered.
 * (Before, a kilo of grain took 0.5 days × t.plantHandling, a sixth of it cutting priced against [REF_STAND_KG_M2]: threshing
 * some 15 times the rate above and cutting a thick field some 25 times too fast.)
 */
internal const val CUT_M2_PER_HOUR = 8f
internal const val THRESH_DAYS_PER_KG = 0.05f
internal const val REF_STAND_KG_M2 = 0.0023f
internal const val REF_SEED_STAND_KG_M2 = 0.00016f
internal const val REAP_CUT_SHARE = 0.17f
internal const val SMALL_SEED_DAYS_PER_KG = 0.75f
internal const val FISH_HANDLING_DAYS_PER_KG = 0.3f
/**
 * Days of a band's hands to pick a kilo of wild fruit (dry matter), at a start band's skill: berries one by one off the
 * bush. Hadza berry patches give 1,045 kcal/h (Salvadora persica) to 2,089 kcal/h (Cordia gharaf), !Kung Grewia 434 g of
 * fresh berries an hour, some 500-740 kcal/h (Blurton Jones, Hawkes & Draper 1994, J Anthropol Res 50: 217-248, citing
 * Hawkes, O'Connell & Blurton Jones). At [Nourishment.FRUIT] and a 6-hour day this gives a start band with full baskets
 * about 1,600 kcal/h and a master about 2,300: the top of the Hadza range, an approximation. (The [PLANT_HANDLING_DAYS_PER_KG]
 * that fruit took before, 0.15, was halved from 0.3 to fit the plant share of food when every food was weighed by the kg,
 * and was not looked at again when foods came to be weighed by their energy: some 6,900 kcal/h, 3-14 times the record.)
 */
internal const val BAND_FRUIT_DAYS_PER_KG = 0.45f
/** The share of a kg of mast's days that is gathering it, the rest leaching its bitter share: how a band choosing its camp counts it ([HexGroups.rootsFound]). */
internal const val MAST_GATHERED = BAND_FRUIT_DAYS_PER_KG / (BAND_FRUIT_DAYS_PER_KG + MAST_BITTER * MAST_LEACH_DAYS_PER_KG)
/**
 * Days of a band's hands to gather a kilo of shellfish meat off the shore at low tide: Meriam reef-flat collecting gives
 * 1,492 ± 173 kcal/h (Bliege Bird, Smith & Bird 2001, Behav Ecol Sociobiol 50: 9-19), at [Nourishment.FISH] and a 6-hour
 * day about 0.12 days a kilo. Collecting needs no tool, and its returns go by the collector's size, not by experience
 * (Bliege Bird & Bird 2002, Human Nature 13: 239-267), so no skill slows it.
 */
internal const val SHELLFISH_DAYS_PER_KG = 0.12f
/**
 * Gatherers walk out to the food and carry it home a load at a time, so food further off takes longer a kilo: the walk
 * there and back over the load (the marginal value theorem at its simplest). Walking speed, km/h, and a gatherer's load,
 * fresh kg: 4 km/h and a !Kung mother's 12 kg of mongongo nuts, Lee's usual loads being 10-15 kg (Blurton Jones, Hawkes &
 * Draper 1994, J Anthropol Res 50: 217-248, pp. 228-232).
 */
internal const val WALK_KM_PER_HOUR = 4f
/**
 * A hunter's load of a kill carried to camp, kg as carried: Hadza carcasses of zebra, wildebeest and hartebeest size
 * (some 115-340 kg alive) take 7-10 carriers, smaller ones one to three (Bunn, in Breathing Life into Fossils, ch. 15):
 * some 20-35 kg each. An approximation.
 */
internal const val MEAT_LOAD_KG = 25f
internal const val GATHER_LOAD_KG = 12f
/**
 * Dry matter's share of a kg of each plant food as carried, by which [GATHER_LOAD_KG] holds less of the plant stocks'
 * dry kilos. Fruit: !Kung Grewia's 434 g fresh an hour at 500-740 kcal/h (Blurton Jones, Hawkes & Draper 1994) is some
 * 1.2-1.7 kcal/g fresh, against Hadza berries' 3.2 dry ([Nourishment.FRUIT]): about 0.45. Roots and seed are guesses
 * (tubers mostly water; grain about a tenth): unsourced. Small finds are reckoned as found.
 */
internal const val FRUIT_DRY = 0.45f
internal const val ROOTS_DRY = 0.3f
internal const val SEED_DRY = 0.9f
fun dryShare(t: Food) = when (t) {
    Food.FRUIT -> FRUIT_DRY; Food.ROOTS -> ROOTS_DRY; Food.SEED, Food.GRAIN -> SEED_DRY; else -> 1f
}
/** Times a party plans its week's foraging over: if others took what it planned on, it looks again at what is left. */
internal const val FORAGE_PASSES = 3
/** [FORAGE_PASSES], or as t.foragePasses sets it (a diagnostic of long steps, review in the variable-step plan). */
internal val GroupWorld.foragePasses get() = tuning.foragePasses

/** Ground a hunting party of kind [s] sweeps in a week, km² (shared over the hexes it passes): its going, by the width it senses. */
fun GroupWorld.sweptKm2(s: Species) = s.dailyTravelKm * days * 2f * s.senseKm * SEARCHING
/**
 * Ground hunting party [g] searches in a week about place [p], km² (shared over the hexes it passes): [sweptKm2], a
 * bigger party fanning out over more of it (as the square root of its numbers against its kind's usual party), a band's
 * trackers reading sign further off, and less of it seen when rain, haze or cover hide game ([sight]).
 */
fun GroupWorld.searchKm2(g: HexGroup, p: Place, weather: Weather) = sweptKm2(g.species) * (g.band?.edge(Skill.TRACK) ?: 1f) *
    sqrt(max(1, g.adults).toFloat() / g.species.groupSize) * sight(p, weather)
/** Share of a kill's weight that is eaten. */
internal const val MEAT_SHARE = 0.6f
internal const val HOME_ADVANTAGE = 1.5f
internal const val RIVAL_KILL = 0.02f
/** Share of a beaten pack's young the winners kill when they take its ground. */
internal const val INFANTICIDE = 0.25f
/**
 * How watchful game is for coursing hunters in fully open country (times the other odds, at most 0.95 in all), and how a
 * chase goes when they come on it unseen, against [CATCH].
 */
internal const val VIGILANCE = 0.86f
internal const val SURPRISE = 1.03f
/**
 * Prey switching (a search image): a hungry hunter chases game of a kind with a chance that grows with how often
 * it has come on that kind lately, m / (m + [SEARCH_HALF]), m counting meetings that fade over [SEARCH_DAYS] (to 1/e).
 * Hunters keep to the game they meet often and pass up what has grown rare, as predators with other prey do in the
 * field (the sigmoid, type III, response that lets scarce prey recover); a new hunter starts at [SEARCH_HALF] of every kind.
 */
internal const val SEARCH_HALF = 3f
internal const val SEARCH_DAYS = 180.0
/** Body mass below which an animal can hide in thick cover, kg, and how much of a hunter's chance cover takes away. */
internal const val HIDING_KG = 70f
internal const val HIDING = 0.8f
/** How much dark or moonlit nights change a stalker's chances. */
internal const val MOON_DARK = 0.3f
/** Extra yearly deaths among packs without ground of their own. */
internal const val DISPERSAL_DEATHS = 0.4f
/** Hoofed animals up to this mass, kg, lose young to eagles. */
internal const val EAGLE_PREY_KG = 80f
/** Strength ratio beyond which the weaker pack gives way without a fight. */
internal const val FIGHT_RATIO = 1.5f

/**
 * Carrion: the most of what lies on the ground searched that a scavenger by trade finds (the rest is
 * not found in time, or others get it); and the share of carrion in a kind's diet that makes it one
 * (striped hyenas): kinds that eat less of it find less, as far as their share falls short (a wolf,
 * at 0.15, finds under a third as much).
 */
internal const val CARRION_FOUND = 0.5f
internal const val SCAVENGER_CARRION = 0.5f


/** Months over which a group's sense of how thick each kind usually is follows the change (about a year). */
internal const val USUAL_MONTHS = 12f
