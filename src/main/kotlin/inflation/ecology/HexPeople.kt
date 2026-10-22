package inflation.ecology

import inflation.ecology.HexSmallLife.DeadPool
import inflation.Books

import inflation.Kg
import inflation.KgM2
import inflation.min
import inflation.Tuning
import inflation.binomial
import inflation.world.HexLand
import kotlin.random.Random
import inflation.wrapMonth
import inflation.monthsFrom
import inflation.Config

import inflation.world.Date
import inflation.world.LifeForm
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.min
import inflation.world.Biome
import inflation.world.Weather
import java.util.stream.IntStream
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.sin

// What makes bands of people more than a pack: skills learned in practice, tools made from what the
// country gives, and camps they stay in.

/** Raw materials a band gathers and carries, kg. */
enum class Material(
    /** Where it is dug or gathered, as a share of each place (for the materials bands trade, [TRADED]). */
    val deposit: ((HexLand) -> FloatArray)? = null,
) {
    /** Good knapping stone (flint, chert, obsidian); poorer stone is found almost anywhere. */
    STONE({ it.stone }), WOOD, HIDE, BONE, SINEW,
    /** Plant fibre for cord, nets and baskets: bark, reeds, grasses. */
    FIBRE,
    /** Tree resin for glue. */
    RESIN,
    /** Sea shells (dentalium, cowrie) gathered on the shore, for beads. */
    SHELL,
    /** Salt scraped from dry lake beds and salt pans (and a little from the shore): meat and fish salted keep, and cure in any weather (see [TRADED]). */
    SALT({ it.salt }),
    /** Ochre, the red and yellow earth: hides tanned with it last (with trade on). */
    OCHRE({ it.ochre }),
}

/**
 * A kind of tool, with what one person's share takes to make ([recipe], kg per person), the skill
 * that makes it, how fast it wears out (share a year), whether it is too heavy to take along when
 * the camp moves, and whether bands know it from the start (the others are hit on now and then and
 * passed on when bands meet); the work it is used in ([uses]: it wears when that work is done), the
 * skill it serves ([serves], for how likely a band is to hit on it), and whether it is [hafted] (set
 * with resin and sinew, so wearing the slower the better the band glues).
 */
enum class Tool(val recipe: Map<Material, Float>, val skill: Skill, val wearPerYear: Float, val makeDays: Float,
    val uses: List<Skill>, val serves: Skill, val hafted: Boolean = false, val heavy: Boolean = false, val known: Boolean = false) {
    /** Stone-tipped spears: the basic hunting weapon. */
    SPEAR(mapOf(Material.STONE to 0.3f, Material.WOOD to 2f, Material.SINEW to 0.05f), Skill.KNAPPING, 1f, 1f, listOf(Skill.HUNT), Skill.HUNT, hafted = true, known = true),
    /** Bow and arrows tipped with microliths set in resin: reach and silence against fast, shy game. */
    BOW(mapOf(Material.WOOD to 1f, Material.SINEW to 0.2f, Material.STONE to 0.2f, Material.RESIN to 0.1f), Skill.WOODWORK, 1.5f, 4f, listOf(Skill.HUNT), Skill.HUNT, hafted = true),
    /** Nets and snares: hares, birds and fish in quantity. */
    NET(mapOf(Material.FIBRE to 2f), Skill.CORDAGE, 1f, 3f, listOf(Skill.SMALL_GAME, Skill.FISH), Skill.SMALL_GAME, known = true),
    /** Sickles of flint bladelets set in bone or wood: wild cereals cut before the ears shatter. */
    SICKLE(mapOf(Material.STONE to 0.2f, Material.BONE to 0.3f, Material.RESIN to 0.05f), Skill.KNAPPING, 0.7f, 1f, listOf(Skill.GATHER), Skill.GATHER, hafted = true),
    /** Mortars, pestles and querns: nuts, acorns and seeds made into meal. Too heavy to move. */
    GRINDER(mapOf(Material.STONE to 15f), Skill.PECKING, 0.05f, 3f, listOf(Skill.GATHER), Skill.GATHER, heavy = true),
    /** Baskets and bags: carrying the harvest home and keeping it. */
    BASKET(mapOf(Material.FIBRE to 3f), Skill.CORDAGE, 0.7f, 2f, listOf(Skill.GATHER, Skill.STONE_BOILING, Skill.LEACHING), Skill.GATHER,
        known = true),
    /** Sewn, fitted clothes and footwear of hide and fur: winter becomes bearable. */
    CLOTHES(mapOf(Material.HIDE to 4f, Material.SINEW to 0.3f, Material.BONE to 0.05f), Skill.HIDEWORK, 0.5f, 4f, emptyList(), Skill.STORE, known = true),
}

/** Share of the success an untrained band still has at a hunt or a harvest. */
const val CRAFT_FLOOR = 0.3f
/** How far one week's use closes the gap to mastery. */
const val PRACTICE = 0.02f
/** Raw materials a band carries, kg per adult of each kind. */
const val GOODS_PER_PERSON = 20f
/** Hunting success of a band without spears, against one with them; what bows add against fast quarry. */
const val WEAPONLESS = 0.3f
const val BOW_GAIN = 0.5f
/** Extra plant food with sickles (seed), grinding stones (seed and roots) and baskets (all of it, and stores). */
const val SICKLE_GAIN = 0.3f
const val GRINDER_GAIN = 0.4f
const val BASKET_GAIN = 0.3f
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
internal fun huntersShare(band: Band) = (HUNTERS_SHARE * band.effort[Food.BIG_GAME.ordinal]).coerceIn(0f, 1f)
/** Extra small game and fish with nets and snares. */
const val NET_GAIN = 1f
/** Most of each kind of plant food in a place a band can gather in a week, at full skill and without tools. */
const val GATHER_SHARE = 0.5f
/** Degrees of cold that full clothes take off, against the half-clothed bands start with. */
const val CLOTHES_C = 10f
/** Yearly chance a band hits on a tool it does not know, where it has use for it; chance a band that meets one that knows a tool learns it. */
const val INVENT = 0.002f
const val TEACH = 0.2f
/**
 * Moving camp: an unloaded adult's walking pace over rough ground, km/h (on roads people walk about 5); a walking child's
 * pace against an adult's, as the square root of its leg length (Froude scaling; legs about 0.55 an adult's at six
 * years); hours a band walks in a day of moving camp (an estimate: no source found).
 */
const val WALK_KMH = 4f
const val CHILD_PACE = 0.74f
const val MOVE_HOURS = 6f
/**
 * Weeks a band expects to keep a camp before it has kept any (foragers moved camp some 5 to 60 times a year, Kelly 1995,
 * from memory: about 4 weeks a camp near the middle), and the share of the gap each move closes in its running mean.
 */
const val STAY_WEEKS_START = 4f
const val STAY_LEARN = 0.2f

/** Share of [g]'s people who hold it back on the move: the old, the hurt and lamed, the sick. */
internal fun weakShare(g: HexGroup) = g.band?.let { b -> ((b.elders + (b.hurt + b.lame) * g.adults + b.sick.sum() * g.size) / g.size.coerceAtLeast(1)).coerceIn(0f, 1f) } ?: 0f

/**
 * Km [g]'s band covers in a day of moving camp: at the pace of its slowest, its adults under their loads ([carriedKg],
 * [loadedKmh]) or its walking children if it has any, held back by its weak ([weakShare]) and by thick cover where it camps.
 */
internal fun HexGroups.campKmADay(g: HexGroup): Float {
    val b = g.band
    val adults = if (b != null) loadedKmh(g.species.massKg, carriedKg(g, b)) else WALK_KMH
    return MOVE_HOURS * min(adults, if (g.young > 0) CHILD_PACE * WALK_KMH else adults) * (1f - WEAK_SLOW * weakShare(g)) /
        (1f + COVER_GOING * land.plants.cover(g.place))
}

/** Days [g]'s band takes to move its camp [km], all its people walking (and its adults back for each load past the first): they are not at work. */
internal fun HexGroups.moveDays(g: HexGroup, km: Float) = km * (g.band?.let { relayWalks(it) } ?: 1f) / campKmADay(g)

/**
 * What an adult carries in a load when the camp moves, food and goods together, kg: !Kung women carried about 15 kg
 * besides a child (Lee 1979, from memory: to check).
 */
const val CARRY_KG = 15f
/** Most that settled life with stores raises births (birth spacing about 4 years to under 3). */
const val SETTLED_FERTILITY = 0.4f
/** Extra yearly deaths among children in a camp kept for a year or more. */
const val CAMP_FOULING = 0.02f
/** People's deaths of thirst against an animal's at the same days without water. */
const val HUMAN_THIRST = 0.3f
/** How much more thirst troubles people on ground they do not know (they look for water where animals and birds lead them). */
const val STRANGER_THIRST = 2f
/** People's births against the catalogue's rate: long nursing spaces births three and a half to four years apart. */
const val HUMAN_BIRTHS = 0.8f

/** A band's appointment: the hex where it means to be in [month] of the year. */
class Appointment(val hex: Hex, val month: Int)
/** A raid a band is set on: the home [hex] of band [on] it means to fall on, given up after [weeks] more weeks. */
class Raid(val hex: Hex, val on: Int, val weeks: Int)
/** A hex a band shuns for [weeks] more weeks after a death there ([Tradition.LEAVE_DEAD]). */
class Unlucky(val hex: Hex, val weeks: Int)

/**
 * What a band is: its skills, making skills, tools (0..1 of a full kit each), the tools it knows, its goods, and how long it has kept its camp.
 * [kinds] is the number of resident kinds ([HexFauna.kinds]), for the animals it keeps.
 */
class Band(kinds: Int) {
    /** For loading a saved band (its saved arrays replace these), so that its unsaved fields start as a new band's do. */
    @Suppress("unused") private constructor() : this(0)
    /** Its know-how, 0..1 for each [Skill] (untaught 0), and which variants it knows of. */
    val skills = enumFloats<Skill> { it.start }
    val knowsSkill = enumBools<Skill> { it.known }
    /** How many of its adults are practised in each skill (-1 until first counted), and the week each was last used. */
    val holders = enumFloats<Skill> { -1f }
    val lastUsed = enumInts<Skill>()
    /** The place it remembers doing best in each month (-1 for none), and how well (0..1). */
    val seasonPlace = IntArray(12) { -1 }
    val seasonQ = FloatArray(12)
    /** Share of its people sick with each [Illness] now, and share spared for having had it. */
    val sick = enumFloats<Illness>()
    val immune = enumFloats<Illness> { it.endemic }
    /**
     * Its wounded: the share of its adults nursing a wound, broken bone or sprain (they do little,
     * and a wound may fester and kill), and the share lamed for life by one that healed badly.
     */
    var hurt = 0f
    var lame = 0f
    /** Share of the work its customs ask that it got done last week: a clean camp ([Tradition.HYGIENE]), the sick and hurt nursed ([Tradition.MEDICINE]). */
    var cleaned = 1f
    var nursed = 1f
    /** How much a band keeping its camp clean spares itself of what fouling spreads, as far as it did the work (1: not at all). */
    val hygiene get() = if (holds(Tradition.HYGIENE)) 1f - (1f - HYGIENE_GUARD) * cleaned else 1f
    /** How far its medicine reaches this week, 0..1: none without the custom, and only as far as the sick and hurt were nursed. */
    val medicine get() = if (holds(Tradition.MEDICINE)) nursed else 0f
    /** People taken by beasts of prey lately, per head (fading by a tenth a week). */
    var beastLoss = 0f
    /** What it knows of itself and of the bands about it, as of the start of the week ([bandInputs]): the people networks' view of the band. */
    @Transient var state = FloatArray(0)
    /** Its working hands, against all its adults: the hurt and the lamed do less. */
    val ableShare get() = (1f - HURT_HANDS * hurt - LAME_HANDS * lame).coerceIn(0.3f, 1f)
    /** Its weeks so far, and its adults last week (to see how many it lost). */
    var weeks = 0
    var lastAdults = 0
    val kit = enumFloats<Tool>()
    val knows = enumBools<Tool> { it.known }
    val goods = enumFloats<Material>()
    /**
     * Of each tool in its kit, the share made well (see [TRADED]): stone tools of good
     * knapping stone rather than of whatever stone is at hand, clothes of hide tanned with ochre.
     */
    val fine = enumFloats<Tool> { if (it == Tool.CLOTHES) 0f else 1f }
    /** Of its dried meat and fish, the share salted (with trade on). */
    var salted = 0f
    /** Of the protein it ate last week, the share beyond what could feed it ([Nourishment.PROTEIN_MOST]): what it knows lean food is worth. */
    var leanLost = 0f
    /** Its name among the bands for the useful gifts it has given (fading a fifth a year; with trade on). */
    var renown = 0f
    var campWeeks = 0
    /** Weeks it has lately kept a camp before moving on, a running mean ([STAY_LEARN]): over how long a move pays back its walk. */
    var stayWeeks = STAY_WEEKS_START
    var campPlace = Place.NONE
    /** Food it left in a cache at an old camp, kg, and where (-1 for none). */
    val cache = enumFloats<Store>()
    val cacheKg get() = cache.sum()
    /** The kg each kind in the cache weighs for each kg of its worth (see [addFood]). */
    val cacheKgPer = enumFloats<Store> { 1f }
    var cachePlace = Place.NONE
    /** Its choices: days of food it aims to keep in store (the scaffold's; a learned policy sets its own). */
    var storeAim = STORE_AIM_DAYS
    /**
     * Its appointment: the hex where it means to be in a month of the year, kept by a moon count
     * (with sky lore, to the month; without, a month either way), -1 for none; taken up from its
     * graves and meeting places, or from bands it meets.
     */
    var appointment: Appointment? = null
    val appointHex get() = appointment?.hex ?: Hex.NONE
    /**
     * Its houses: round, half-sunken huts of stone and brush at hex [houseHex], built 0..1 (enough
     * for all its people at 1), with storage pits; they weather away while it is elsewhere. At home
     * ([atHome]) they keep it warm, its stores keep better (though mice get into them) and it is
     * harder to raid.
     */
    var houseHex = Hex.NONE
    var house = 0f
    /** Where it knows other bands have their houses (band id to hex), and their descent (main lineage). */
    val knownHomes = HashMap<Int, Int>()
    val knownLineage = HashMap<Int, Int>()
    /** A raid it is set on, if any. */
    var raid: Raid? = null
    val raidHex get() = raid?.hex ?: Hex.NONE
    /** A palisade of posts and brush round its houses, 0..1; weeks since it was last raided. */
    var palisade = 0f
    var sinceRaided = 1000
    /**
     * Its ornaments (beads of bone and shell, carved pendants, figurines), as a count, and how many of
     * them are of sea shell (gifts travel along ties, so shell from the shore reaches far inland).
     */
    var ornaments = 0f
    var exotic = 0f
    /** Takes [n] of its ornaments, shell among them in its share; returns the shell ones taken. */
    @IgnorableReturnValue fun takeOrnaments(n: Float): Float {
        val share = if (ornaments > 0f) exotic / ornaments else 0f
        val shell = n * share
        ornaments -= n; exotic -= shell
        return shell
    }
    /**
     * Animals it keeps by its camp, by kind of the residents ([HexFauna.kinds] index): young
     * taken in the hunt and raised, and their offspring; and how tame each kind has grown under it
     * (the docile breed and stay, the wild ones run off), 0..1.
     */
    val herd = FloatArray(kinds)
    val herdTame = FloatArray(kinds)
    /** Of the grain in its stores, the share from ears that hold their grain (what it would sow). */
    var seedDomestic = 0f
    /** Its ways ([BandWay]), as [setWays] sets them each week from its ways' outputs: 1 each for the scaffold's way. Read through the names below. */
    val will = enumFloats<BandWay> { 1f }
    /** Its choices: how much of its stored grain it sows in autumn at its home (the scaffold: some, once it has houses). */
    val sowWill get() = will[BandWay.SOWING]
    /** How far a band with worn kit holds back from laying food by, to mend first (0 not at all, 1 wholly). */
    val mendFirst get() = will[BandWay.MENDING_FIRST].coerceAtMost(1f)
    /**
     * Its choices against the dangers (1 for each gives the scaffold's way):
     * - how far from camp its gatherers go, against today's reach: further brings more food and
     *   more ground to watch (beasts take more where the gatherers are spread thin);
     * - how closely it watches its children: adults kept by them cost hands, children left alone are
     *   easy pickings;
     * - how faithfully it keeps a fire through the night (wood to gather; the fire keeps beasts off);
     * - how readily it keeps away from bands with sickness among them (0: not at all), losing their
     *   company, news and partners.
     */
    val gatherRange get() = will[BandWay.GATHERING_RANGE]
    val childWatch get() = will[BandWay.WATCHING_CHILDREN]
    val nightFire get() = will[BandWay.NIGHT_FIRE]
    val shunSick get() = 0.5f * will[BandWay.SHUNNING_THE_SICK]
    /** How keen it is to go into country it does not know (1: the usual wariness of strange ground; 2: drawn to it). */
    val explore get() = will[BandWay.EXPLORING]
    /** The fire as it guards the camp at night: kept only as faithfully as the band chooses. */
    val nightGuard get() = fire * min(1f, nightFire) * nightWood
    /** Share of the night's firewood it had this week. */
    var nightWood = 1f
    /** Share of the hearth's firewood (warmth and cooking) it fetched this week: its fire burns only as far as that goes. */
    var hearthWood = 1f
    /** Its choices: how ready it is to go raiding, and to fortify (1 the scaffold's). */
    val raidWill get() = will[BandWay.RAIDS]
    val fortifyWill get() = will[BandWay.PALISADES]
    /**
     * Its choices in dealings with other bands and with its own camp (each 1 for the scaffold's way;
     * 0 never, 2 twice as readily or as much):
     * - how much it gives: food to hungry bands it meets or that come to it, ornaments to friends;
     * - how readily it takes from a cache of another band it comes upon;
     * - how much of what it cannot carry it caches on leaving camp (the rest is left to rot);
     * - how much of its fresh meat and fish it dries with spare time;
     * - how readily it keeps young animals it catches or that follow its camp;
     * - how fully it puts up brush shelters at a new camp;
     * - how keen it is to set food before other bands and to make an appointment to meet again
     *   (above 1 even a band without the custom does; below, even one with it holds back);
     * - how readily it burns off dry grass round its camp.
     */
    val giveWill get() = will[BandWay.GIVING]
    val robWill get() = will[BandWay.TAKING_FROM_CACHES]
    val cacheWill get() = will[BandWay.CACHING]
    val dryWill get() = will[BandWay.DRYING]
    val keepWill get() = will[BandWay.KEEPING_YOUNG_ANIMALS]
    val shelterWill get() = will[BandWay.SHELTERS]
    val feastWill get() = will[BandWay.FEASTING]
    val appointWill get() = will[BandWay.APPOINTMENTS]
    val burnWill get() = will[BandWay.BURNING_OFF]
    /**
     * How keenly it lays food [t] by when it works for its stores (1 the scaffold's, as it would eat it; 0 none of its store
     * work for it, 2 twice as keen): weighed against what else it could gather, what rots and how soon is for it to learn.
     */
    fun storeWill(t: Food) = will[storeWay(t)]
    /**
     * How keenly it acts on a custom: a band that holds it as its will (1 at the default, twice as much at 2, not at all at 0),
     * one that does not only as far as its will goes above the default.
     */
    fun byCustom(holds: Boolean, will: Float) = max(0f, will - (if (holds) 0f else 1f))

    /** Whether it holds hex [h] as its own: its houses, its dead, its appointment place. */
    fun claims(h: Hex, memory: PlaceMemory) = h == houseHex || h == appointHex || memory.deadAt(h) > 0f
    /** A hex it shuns after a death there, if any ([Tradition.LEAVE_DEAD]). */
    var unlucky: Unlucky? = null
    val unluckyHex get() = unlucky?.hex ?: Hex.NONE
    /** Brush shelters at its present camp, 0..1 (left behind when it moves on). */
    var shelter = 0f
    /** Its choices: how ready it is to build (1 the scaffold's: after [HOUSE_AFTER_YEARS] of a year at a camp that feeds it well). */
    val houseWill get() = will[BandWay.HOUSES]
    var atHome = false
    /** Its choices: the share of its adults out scouting rather than foraging. */
    val scoutShare get() = SCOUT_SHARE * will[BandWay.SCOUTS]
    /** Its choices: how it ranks making each tool ([Tool]) with its spare time (the scaffold: the most worn first). */
    val makeFirst = enumFloats<Tool> { 1f }
    /**
     * How it stands with each band it has met (by the band's id), -1..1: raised by meeting in peace,
     * gifts, feasts and marriages, lowered by raids; fading unless renewed.
     */
    val ties = HashMap<Int, Float>()
    fun tie(other: Int) = ties[other] ?: 0f
    fun befriend(other: Int, by: Float) { ties[other] = (tie(other) + by * (if (by > 0f) 1f - tie(other) else 1f + tie(other))).coerceIn(-1f, 1f) }
    /**
     * Favours: what each band it has dealt with owes it (above 0) or it is owed by it (below 0), in
     * favours, an abstract unit of help: food that met a week of the helped band's needs is one favour
     * to it. Help given raises the balance, help received lowers it; it is settled only by help in
     * return, and lapses only when the other band is gone (see [helped]).
     */
    val favours = HashMap<Int, Float>()
    /** Favours it owes band [other] (0 if none). */
    fun owesTo(other: Int) = max(0f, -(favours[other] ?: 0f))
    /** Its choices: how fully it returns the favours it owes when a band it owes is hungry (1 the scaffold's: as far as the other's need goes; 0 not at all). */
    val repayWill get() = will[BandWay.REPAYING]
    /** Person-days its people had to spare this week, after getting and keeping food. */
    var spareDays = 0f
    /** Its week's time, as planned when it foraged ([BandTime]; null till then, and once the week's chores are done). */
    @Transient var week: BandTime? = null
    /** Person-days drying and skinning would have liked last week: what they ask of this week's plan (they follow the catch). */
    var dryAsk = 0f
    var skinAsk = 0f
    /**
     * Its choices: how much of its effort goes to each kind of food ([FOOD_TYPES]: big game, small
     * game, carrion, bones, fruit, roots, grass seed, small finds, fish), 1 the scaffold's. Big game's
     * sets how many of its hands go hunting ([HUNTERS_SHARE] of them at 1, see [huntersShare]).
     */
    val effort = FloatArray(FOOD_TYPES) { 1f }
    /**
     * Hides, bone and sinew from [n] of the game it killed, what each kind yields (see [Species.products]), out of the
     * [left] kg of the carcasses not eaten; returns the kg taken.
     */
    fun skin(s: Species, n: Int, left: Float): Float {
        val p = s.products; val each = p.hide + p.bone + p.sinew
        if (n <= 0 || each <= 0f || left <= 0f) return 0f
        val share = min(1f, left / (n * each))
        goods[Material.HIDE] += n * p.hide * share
        goods[Material.BONE] += n * p.bone * share
        goods[Material.SINEW] += n * p.sinew * share
        return n * each * share
    }
    /**
     * Its customs ([Tradition]), its words for things ([Language]), its descent (a share for each
     * founding lineage), how inbred its children are, its grudges against each lineage's people.
     */
    val traditions = enumBools<Tradition>()
    val words = IntArray(Language.CONCEPTS)
    val lineage = FloatArray(LINEAGES)
    var inbred = 0.02f
    val grudge = FloatArray(LINEAGES)
    /** Of its adults, the elders: past bearing children, helping their daughters raise theirs. */
    var elders = 0
    /**
     * Of its adults not yet elders, how many are in the second and third of the three equal spans of
     * adult life (the rest are in the first): adults age through them in turn, so becoming an elder
     * comes at a fairly steady age, not at random.
     */
    val ageStage = IntArray(2)
    /** Animals it keeps, by kind ([Species.index]): wolf pups taken from bold packs become dogs. */
    val kept = FloatArray(Catalogue.all.size)
    /** The country it knows, a bit for each hex: walked, seen from where it walked, or told of by bands it understands. */
    var known = LongArray(0)

    fun knows(h: Int) = h / 64 < known.size && (known[h / 64] ushr (h % 64)) and 1L == 1L
    fun learn(h: Int, size: Int) { if (known.size * 64 < size) known = known.copyOf((size + 63) / 64); known[h / 64] = known[h / 64] or (1L shl (h % 64)) }

    /**
     * The rite its culture holds it to in [month], if any: the place it must go to then and how strongly
     * (see [inflation.ecology.roundOf]): the site of its rite that month ([rite]) at the strength its
     * people hold to their rites ([riteStrength]); none in a month without a site, or with no hold.
     */
    fun riteFor(month: Int): RoundPull? {
        if (riteStrength <= 0f) return null
        // (A rite's month is spent at its site: the band is drawn there from the month before, and kept
        // there until the month after, unless that has its own.)
        val site = if (rite[month] >= 0) rite[month] else rite[wrapMonth(month - 1)]
        return if (site >= 0) RoundPull(site, riteStrength) else null
    }
    /**
     * Its rites: for each month, the place its round of observance takes it to then (-1: none) — the
     * graves of its dead, where bands gather, far landmarks it walked to or was told of (see [riteWeek]).
     * Handed down whole to bands that split off, taken up from bands met, now and then let go; never
     * chosen for what they bring, so a rite that costs lives lasts as long as the line that keeps it.
     */
    val rite = IntArray(12) { -1 }
    /** How strongly its people hold to their rites (0: not at all; see [inflation.Tuning.riteFounderMax]). */
    var riteStrength = 0f

    fun holds(t: Tradition) = traditions[t]
    fun mainLineage(): Int { var best = 0; for (i in lineage.indices) if (lineage[i] > lineage[best]) best = i; return best }

    /**
     * A band splitting off it, [o] (made fresh), with [adults] of its adults; [stay] adults remain with it,
     * and the two share out by heads at [share] (the new band's): the new band takes along everything
     * it knew and believed, its descent and its grudges, a share of its goods, ornaments, kept animals,
     * elders and ages, and its practised people go one way or the other (drawn from [r]); [copied] has the
     * new band before anything is shared out (its customs drift as they are handed down). Each field's
     * rule is in [CARRY].
     */
    /** Word between two bands of where each did better this month: the poorer memory takes the better one's place. */
    fun shareSeasons(o: Band) {
        for (m in 0 until 12) {
            if (o.seasonQ[m] > seasonQ[m] + 0.1f && o.seasonPlace[m] >= 0) {
                seasonPlace[m] = o.seasonPlace[m]
                seasonQ[m] = 0.5f * (seasonQ[m] + o.seasonQ[m])
            }
            else if (seasonQ[m] > o.seasonQ[m] + 0.1f && seasonPlace[m] >= 0) {
                o.seasonPlace[m] = seasonPlace[m]
                o.seasonQ[m] = 0.5f * (seasonQ[m] + o.seasonQ[m])
            }
        }
    }

    fun splitInto(o: Band, adults: Int, stay: Int, share: Float, r: Random, copied: (Band) -> Unit) {
        o.ties.clear(); o.ties.putAll(ties); o.appointment = appointment
        o.houseHex = houseHex; o.house = house; o.palisade = palisade; herdTame.copyInto(o.herdTame); herd.copyInto(o.herd)
        o.seedDomestic = seedDomestic; o.sinceRaided = sinceRaided
        o.knownHomes.putAll(knownHomes); o.knownLineage.putAll(knownLineage)
        skills.copyInto(o.skills)
        knowsSkill.copyInto(o.knowsSkill)
        sick.copyInto(o.sick)
        immune.copyInto(o.immune)
        o.hurt = hurt
        o.lame = lame
        holders.copyInto(o.holders)
        lastUsed.copyInto(o.lastUsed)
        seasonPlace.copyInto(o.seasonPlace); seasonQ.copyInto(o.seasonQ)
        o.weeks = weeks
        plants.copyInto(o.plants)
        o.fire = fire
        kit.copyInto(o.kit)
        knows.copyInto(o.knows)
        fine.copyInto(o.fine)
        o.salted = salted
        o.renown = renown
        rite.copyInto(o.rite); o.riteStrength = riteStrength
        traditions.copyInto(o.traditions); words.copyInto(o.words); lineage.copyInto(o.lineage); grudge.copyInto(o.grudge)
        o.inbred = inbred; o.campWeeks = campWeeks; o.campPlace = campPlace; o.known = known.copyOf(); kept.copyInto(o.kept)
        copied(o)
        for (i in o.kept.indices) { o.kept[i] *= share; kept[i] -= o.kept[i] }
        // (A band from a save made when there were fewer resident kinds keeps fewer: see [HexFauna.kinds].)
        for (i in 0 until min(herd.size, o.herd.size)) { o.herd[i] *= share; herd[i] -= o.herd[i] }
        for (m in Material.entries) { o.goods[m] = goods[m] * share; goods[m] -= o.goods[m] }
        o.ornaments = ornaments * share; ornaments -= o.ornaments; o.exotic = exotic * share; exotic -= o.exotic
        o.elders = elders * adults / (stay + adults).coerceAtLeast(1); elders -= o.elders
        // Its practised people go one way or the other: a craft held by one or two may go wholly with either half.
        val go = adults.toFloat() / (stay + adults).coerceAtLeast(1)
        for (i in Skill.entries) if (holders[i] > 0f) {
            val held = holders[i].toInt() + (if (r.nextFloat() < holders[i] - holders[i].toInt()) 1 else 0)
            val took = binomial(held, go, r).toFloat(); o.holders[i] = took; holders[i] = max(0f, holders[i] - took)
        }
        o.lastAdults = adults; lastAdults = stay
        for (i in ageStage.indices) { o.ageStage[i] = ageStage[i] * adults / (stay + adults).coerceAtLeast(1); ageStage[i] -= o.ageStage[i] }
    }

    /**
     * Band [b] (of [bAdults] adults, [w] of the adults of the two) joins this one: the best of what either
     * knew, its goods and kept animals added, kit, descent and inbreeding blended by adults, its dried food's
     * salting by the dried food each held ([dried] and [bDried], kg), its grain's by the seed each held ([seed],
     * [bSeed]); its herd, ornaments and exotic goods added, the herd's tameness blended by the animals, its
     * sick, immune, hurt and lame blended by adults. [id] and [bId] are the two bands' ids.
     * Each field's rule is in [CARRY].
     */
    fun absorb(b: Band, bAdults: Int, w: Float, dried: Float, bDried: Float, seed: Float, bSeed: Float, id: Int, bId: Int) {
        val a = this
        for (i in Skill.entries) a.skills[i] = max(a.skills[i], b.skills[i])
        for (i in Skill.entries) a.holders[i] = max(0f, a.holders[i]) + max(0f, b.holders[i])
        a.lastAdults += bAdults
        for (i in Skill.entries) a.knowsSkill[i] = a.knowsSkill[i] || b.knowsSkill[i]
        for (i in Biome.entries) a.plants[i] = max(a.plants[i], b.plants[i])
        for (i in Tool.entries) {
            val ka = a.kit[i] * (1f - w)
            val kb = b.kit[i] * w
            if (ka + kb > 0f) a.fine[i] = (ka * a.fine[i] + kb * b.fine[i]) / (ka + kb)
            a.kit[i] += w * (b.kit[i] - a.kit[i])
        }
        if (dried + bDried > 0f) a.salted = (dried * a.salted + bDried * b.salted) / (dried + bDried)
        if (seed + bSeed > 0f) a.seedDomestic = (seed * a.seedDomestic + bSeed * b.seedDomestic) / (seed + bSeed)
        for (i in Illness.entries) { a.sick[i] += w * (b.sick[i] - a.sick[i]); a.immune[i] += w * (b.immune[i] - a.immune[i]) }
        a.hurt += w * (b.hurt - a.hurt); a.lame += w * (b.lame - a.lame)
        a.ornaments += b.ornaments; a.exotic += b.exotic
        for (i in 0 until min(a.herd.size, b.herd.size)) {
            val n = a.herd[i] + b.herd[i]
            if (n > 0f) a.herdTame[i] = (a.herd[i] * a.herdTame[i] + b.herd[i] * b.herdTame[i]) / n
            a.herd[i] = n
        }
        a.renown = max(a.renown, b.renown)
        for (i in Tool.entries) a.knows[i] = a.knows[i] || b.knows[i]
        for (i in a.lineage.indices) a.lineage[i] += w * (b.lineage[i] - a.lineage[i])
        for (i in a.grudge.indices) a.grudge[i] = max(a.grudge[i], b.grudge[i])
        for ((k, v) in b.ties) if (k != id) a.ties[k] = max(a.tie(k), v)
        a.ties.remove(bId)
        for ((k, v) in b.favours) if (k != id) a.favours.merge(k, v, Float::plus)
        a.favours.remove(bId)
        for (i in Material.entries) a.goods[i] += b.goods[i]
        if (a.known.size < b.known.size) a.known = a.known.copyOf(b.known.size)
        for (i in b.known.indices) a.known[i] = a.known[i] or b.known[i]
        for (i in b.kept.indices) a.kept[i] += b.kept[i]
        a.inbred += w * (b.inbred - a.inbred); a.elders += b.elders
        for (i in a.ageStage.indices) a.ageStage[i] += b.ageStage[i]
    }

    /** How well the band does at [s], against a master: from the skill's floor untrained to 1 (see [Skill]). */
    fun skill(s: Skill) = s.floor + (1f - s.floor) * skills[s]
    /** How well it does at a kind of work, by the best of the variants it knows. */
    fun skill(d: Domain) = Skill.of(d).maxOf { if (knowsSkill[it]) skill(it) * it.gain else 0f }
    /**
     * What it knows of the plants of each kind of country ([Biome]), 0..1: which are
     * food, where and when they are found, and how to make them safe. Learned by gathering there and
     * from partners; on strange ground people find little and risk the wrong plants.
     */
    val plants = enumFloats<Biome>()
    /** Share of the week it has had a fire going: warmth, cooked food. */
    var fire = 0f
    /**
     * How much longer than an ordinary practised forager (bands start at [START_CRAFT]) it takes over
     * each kilo of [f]'s food: finding, catching, digging and making it ready. [know] is how well it
     * knows the plants there, against a band in its own country.
     */
    fun slowness(f: Skill, know: Float = KNOW_AT_HOME) = (f.floor + (1f - f.floor) * START_CRAFT) / skill(f) * (KNOW_AT_HOME / know)
    /**
     * How much more of a kilo of plant food of food type [t] its cooking makes good (1 roasting): earth
     * ovens for roots, gruel boiled with hot stones for seed and grain. Nothing cooks without fire.
     */
    fun cookValue(t: Food): Float {
        if (fire <= 0f) return 1f
        val v = when (t) {
            Food.ROOTS -> if (knowsSkill[Skill.EARTH_OVEN]) EARTH_OVEN_GAIN * skill(Skill.EARTH_OVEN) else 0f
            Food.SEED, Food.GRAIN -> if (knowsSkill[Skill.STONE_BOILING]) GRUEL_GAIN * skill(Skill.STONE_BOILING) * max(has(Tool.BASKET), 0.5f) else 0f
            else -> 0f
        }
        return 1f + fire * v
    }
    /**
     * Food a band makes of the bitter and poisonous plants beside each kilo of plants it finds palatable
     * where the edible share of the plants is [palatable], kg: none unless it knows how to leach them and
     * has baskets and water.
     */
    fun detoxExtra(palatable: Float, water: Boolean): Float =
        if (!knowsSkill[Skill.LEACHING] || !water) 0f
        else min(1f, LEACH_SHARE * skill(Skill.LEACHING) * has(Tool.BASKET) * (1f - palatable) / palatable.coerceAtLeast(0.1f))
    fun practise(s: Skill, rate: Float = s.practice) {
        skills[s] += rate * learning / s.complexity * toolsFor(s) * (1f - skills[s])
        lastUsed[s] = weeks
    }
    /**
     * How far a band can practise [s] with the tools it has: a hunter without spear or bow, a fisher
     * without lines and nets, a cook without tight baskets learns little of the craft (a little, at
     * best, by watching and making do).
     */
    fun toolsFor(s: Skill) = when (s) {
        Skill.HUNT, Skill.STALK -> max(TOOLLESS, max(has(Tool.SPEAR), has(Tool.BOW)))
        Skill.SMALL_GAME, Skill.FISH -> max(TOOLLESS, has(Tool.NET))
        Skill.STONE_BOILING, Skill.LEACHING -> max(TOOLLESS, has(Tool.BASKET))
        else -> 1f
    }
    /** How fast its practice teaches it, against a start band's: good teachers bring the young on faster. */
    val learning get() = edge(Skill.SHOWING)
    /** How well it makes [t]: by the best way of its craft it knows (blades and microliths beat plain flakes). */
    fun makeSkill(t: Tool) = skill(t.skill.domain)
    /** The way of its craft it uses for [t]. */
    fun makeWay(t: Tool) = Skill.of(t.skill.domain).filter { knowsSkill[it] }.maxByOrNull { skill(it) * it.gain } ?: t.skill
    fun has(t: Tool) = kit[t]
    /** Whether any tool it knows is worn below [MEND_BELOW] of a full kit. */
    fun worn() = Tool.entries.any { knows[it] && kit[it] < MEND_BELOW }

    /** How its weapons, hunting skill and dogs do against [prey], against an ordinary armed, practised band without dogs (1). */
    fun huntEdge(prey: Species, tuning: Tuning) =
        skill(Skill.HUNT) * edge(Skill.STALK) * (WEAPONLESS + (1f - WEAPONLESS) * has(Tool.SPEAR) * serveOfMake(Tool.SPEAR, fine[Tool.SPEAR], tuning) +
            BOW_GAIN * has(Tool.BOW) * serveOfMake(Tool.BOW, fine[Tool.BOW], tuning) * min(1f, prey.speedKmh / 70f)) * (1f + DOG_HUNT * dogShare)

    /** Its dogs against its hunters (one to three a full pack): they track, bay and hold game, and bark at night. */
    var dogShare = 0f

    /** Its people's fighting weight per kg: with spears and bows lions and hyenas give way; without, they are no match. */
    fun ferocity(s: Species) = s.ferocity * (WEAPONLESS + (1f - WEAPONLESS) * has(Tool.SPEAR) + 0.5f * has(Tool.BOW))

    /** How much more seed its sickles reap, as well as their stone cuts. */
    fun sickleGain(tuning: Tuning) = SICKLE_GAIN * has(Tool.SICKLE) * serveOfMake(Tool.SICKLE, fine[Tool.SICKLE], tuning)

    /** Gathering: the most of a plant food's stock in a place it can take in a week; [seed] for grass seed. */
    fun gatherShare(seed: Boolean, tuning: Tuning): Float {
        val kit = 1f + BASKET_GAIN * has(Tool.BASKET) +
            if (seed) sickleGain(tuning) +
                GRINDER_GAIN * has(Tool.GRINDER) else GRINDER_GAIN * 0.5f * has(Tool.GRINDER)
        return min(0.9f, GATHER_SHARE * skill(Skill.GATHER) * kit)
    }


    /** Small game and fish: snare lines and nets, in practised hands. */
    fun snares() = (1f + NET_GAIN * has(Tool.NET)) * skill(Skill.SMALL_GAME)
    fun fishing() = (1f + NET_GAIN * has(Tool.NET)) / (1f + NET_GAIN) * skill(Skill.FISH)

    /** How much more (above 1) or less a skill's work leaves undone than a start band's: the sick die, infants are lost. */
    fun lapse(s: Skill) = (s.floor + (1f - s.floor) * s.ref) / skill(s)
    /** How much better (above 1) or worse it does at [s] than a start band. */
    fun edge(s: Skill) = 1f / lapse(s)
    /** How well its clothes keep out the cold against a start band's (sewn to fit by skilled hands, better). */
    fun clothesFit() = skill(Skill.SEWING) / (Skill.SEWING.floor + (1f - Skill.SEWING.floor) * Skill.SEWING.start)
    /** Temperature as the band feels it at [t] °C: clothes take the edge off the cold. */
    fun feels(t: Float) = feels(t, fire, homeShelter())
    /** How far houses shelter it where it is: its home's, at home. */
    fun homeShelter() = if (atHome) house else 0f
    /** [feels] with a fire of [fire] and houses sheltering it as far as [home]: as it would camped elsewhere. */
    fun feels(t: Float, fire: Float, home: Float) =
        if (t < 15f) min(15f, t + CLOTHES_C * (has(Tool.CLOTHES) * clothesFit() - 0.5f) + FIRE_C * fire +
            max(SHELTER_C * shelter, HOUSE_C * home)) else t

    /**
     * How far below what people can bear the cold at [t] °C (the week's mean) takes the band, °C: full
     * fur clothes, a fire and a shelter or house keep people alive in deep cold; the naked die of
     * exposure in a night of a few degrees above freezing, wet and windy.
     */
    fun exposure(t: Float) = exposure(t, fire, homeShelter())
    /** [exposure] with a fire of [fire] and houses sheltering it as far as [home]. */
    fun exposure(t: Float, fire: Float, home: Float) =
        (EXPOSURE_C - t - clothesCover() - PROTECT_FIRE * fire - roofCover(home)).coerceAtLeast(0f)
    /** The cold its clothes keep off ([exposure]), °C. */
    fun clothesCover() = PROTECT_CLOTHES * has(Tool.CLOTHES) * clothesFit()
    /** The cold its shelter, or houses sheltering it as far as [home], keep off ([exposure]), °C. */
    fun roofCover(home: Float) = max(PROTECT_SHELTER * shelter, PROTECT_HOUSE * home)

    companion object {
        /**
         * How each of a band's fields goes when it splits ([splitInto]) and when another band joins it
         * ([absorb]), as the code does it; BandCarryTest fails when a field is missing here, so that every
         * new field's rule is chosen, not fallen into.
         */
        val CARRY: Map<String, Pair<OnSplit, OnJoin>> by lazy { carryRules() }

        /**
         * A band starting out in country it knows, founded at ([x], [y]) km: practised at what the
         * country offers, with a basic kit, and the descent, words and customs of its country.
         */
        fun start(kinds: Int, nearWater: Boolean, x: Double, y: Double, tuning: Tuning) = Band(kinds).apply {
            val l = lineageAt(x, y)
            lineage[l] = 1f
            wordsAt(x, y).copyInto(words)
            traditionsAt(l).copyInto(traditions)
            riteStrength = riteStrengthAt(l, tuning)
            skills[Skill.GATHER] = START_CRAFT; skills[Skill.SMALL_GAME] = START_CRAFT; skills[Skill.HUNT] = START_CRAFT
            skills[Skill.FISH] = if (nearWater) 0.5f else 0.1f; skills[Skill.STORE] = 0.4f
            kit[Tool.SPEAR] = 1f; kit[Tool.NET] = 0.5f; kit[Tool.BASKET] = 0.5f; kit[Tool.CLOTHES] = 0.5f
            // Everyone keeps fire; they know the plants of their own country well, of others' less.
            skills[Skill.FIRE] = 0.95f; fire = 1f
            plants.fill(0.5f)
        }
    }

}

/** At any camp a band first puts up brush shelters, quickly (and leaves them when it moves on). */
private fun putUpShelter(b: Band, people: Float, time: BandTime) {
    if (b.campWeeks == 0) b.shelter = 0f
    val shelterDays = shelterDays(b, people)
    val lack = shelterLack(b, moved = false)
    val left = time.leftFor(Activity.SHELTER)
    if (lack > 0f && left > 0f && people > 0f) {
        val built = min(lack, left / shelterDays)
        b.shelter += built
        time.spend(Activity.SHELTER, built * shelterDays)
        b.practise(Skill.WOODWORK, 0.2f * PRACTICE)
    }
    time.release(Activity.SHELTER)
}

/** Person-days [b]'s [people] take to put up whole shelters. (A debris hut takes a novice an afternoon, the practised an hour.) */
private fun shelterDays(b: Band, people: Float) = SHELTER_DAYS * people / b.skill(Skill.WOODWORK)

/**
 * What [b] lacks of the shelter it aims at: none stands at a camp it has just come to ([moved]). (Above the default it
 * builds sturdier: thatched thicker and banked with earth, warmer as it took longer.)
 */
private fun shelterLack(b: Band, moved: Boolean) = max(0f, b.shelterWill - if (moved) 0f else b.shelter)

/** A fire through the night burns wood: where there is little, the night goes dark. Returns the wood burnt, kg. */
private fun burnNightFire(b: Band, people: Float): Float {
    val wood = b.goods
    val want = NIGHT_WOOD * people * min(1f, b.nightFire)
    val got = min(want, wood[Material.WOOD])
    wood[Material.WOOD] -= got
    b.nightWood = if (want > 0f) got / want else 1f
    return got
}

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
    val h = hw.hexOf(p)
    val area = land.m2(p)
    // The days its week's plan gives each chore ([planWeek]) go first to caching what it leaves at a camp, then to gathering
    // what it lacks of its materials, then to making and mending; what a chore leaves is free for those after it.
    val time = b.week ?: BandTime(b.spareDays)
    b.week = null
    campAndCache(g, b, p, h, weather, date, time)
    time.release(Activity.CACHING)
    keepAnimals(g, b)
    keepHerds(g, b, date)
    val people = g.adults.toFloat()
    gatherMaterials(g, b, p, h, people, area, time)
    time.release(Activity.MATERIALS)
    val living0 = b.livingGoods()
    wearAndInvent(b, years)
    // Making and mending takes the time still to spare, the tools it ranks first first (the
    // scaffold: the most worn); each as far as the time, the scarcest material and the skill allow.
    dialUse?.amount(DialSite.SHELTERS, b.shelterWill, b.shelterWill, 1f)
    putUpShelter(b, people, time)
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
    dialUse?.amount(DialSite.NIGHT_FIRE_WOOD, b.nightFire, nightBurnt, min(NIGHT_WOOD * people, woodBefore), cut = b.nightFire > 1f)
    // With the time still to spare, a band that has kept its camp a while builds stone-footed houses
    // there, as far as it has gathered the stone and timber (and starts afresh where it settles
    // anew); houses it has left weather away.
    useStructures(g, h.i)
    buildHouse(g, b, p, h, people, time)
    time.release(Activity.HOUSES)
    // (Its home is where it last had houses to use; what stands there still shelters it, unless another band took it up.)
    if (housesAt(g, h.i) > 0.05f) b.houseHex = h
    b.house = if (b.houseHex.none) 0f else housesAt(g, b.houseHex.i)
    b.atHome = b.houseHex == h && b.house > 0.05f
    // With time still to spare, it makes ornaments of shell and bone: they are given, worn and buried.
    makeOrnaments(b, people, time)
    time.release(Activity.ORNAMENTS)
    // In autumn a band at home, or long at its camp, sows a share of its stored grain there: a plot that ripens
    // next summer to several times as much, of the kind of ears it sowed.
    sowGrain(g, b, p, date, time)
    time.release(Activity.SOWING)
    // A band at home that has been raided in the last year rings its houses with a palisade, as far
    // as its time and timber go.
    buildPalisade(g, b, h, people, time)
    b.palisade = if (b.houseHex.none) 0f else palisadeAt(g, b.houseHex.i)
    // What no chore took is its leisure. (Counted once every chore has had its time.)
    time.noneBelowZero()
    b.spareDays = time.left
    time.spend(Activity.LEISURE, time.left)
    time.countEach(stats.hunting)
    time.count(stats.hunting, Tally.TimeTotal.LEFT, time.left)
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
internal fun HexGroups.choreAsks(g: HexGroup, b: Band, date: Date, asks: FloatArray) {
    val p = g.place; val h = hw.hexOf(p); val people = g.adults.toFloat()
    val moved = b.campPlace.none || h != hw.hexOf(b.campPlace)
    fun ask(a: Activity, days: Float) { asks[a.ordinal] = if (days.isFinite()) max(0f, days) else 0f }
    if (moved && !b.campPlace.none) ask(Activity.CACHING, min(1f, b.cacheWill) * leftBehindKg(g, b) * cacheCare(b) / CACHE_KG_A_DAY)
    ask(Activity.MATERIALS, gatherMaterials(g, b, p, h, people, land.m2(p), null))
    ask(Activity.SHELTER, shelterLack(b, moved) * shelterDays(b, people))
    ask(Activity.CUSTOMS, cleanDays(b, people) + nurseDays(b, people))
    ask(Activity.TRAINING, Skill.entries.sumOf { s -> (learners(b, s, people) * APPRENTICE_DAYS * s.complexity).toDouble() }.toFloat())
    ask(Activity.TOOLS, makeTools(b, people, null))
    ask(Activity.ORNAMENTS, ornamentsWanted(b, people) * ORNAMENT_DAYS)
    // (A band that has just moved builds, sows and fortifies nothing where it has come to.)
    if (!moved) {
        ask(Activity.HOUSES, buildHouse(g, b, p, h, people, null))
        if (canSow(g, b, p, date)) ask(Activity.SOWING, g.stores[Store.SEED] * SOW_SHARE * b.sowWill * SOW_DAYS_PER_KG)
        if (fortifies(b)) ask(Activity.PALISADE,
            minOf(1f - palisadeAt(g, h.i), b.goods[Material.WOOD] / (PALISADE_WOOD * people)).coerceAtLeast(0f) * PALISADE_DAYS * people)
    }
    ask(Activity.DRYING, b.dryAsk)
    ask(Activity.SKINS, b.skinAsk)
}

/** The country [g]'s band comes to know this week: what it walks, and what it sees round about from the high points on its way, its scouts further still. */
private fun HexGroups.learnCountry(g: HexGroup, b: Band, weather: Weather) {
    // It comes to know the country it walks, and what it sees round about.
    // From the high points on its way it sees as far as the line of sight and the weather allow;
    // its scouts range further still.
    var lookKm = 0f
    for (h in g.path) {
        val q = hw.placeNear(g.place, Hex(h)).takeIf { !it.none } ?: continue
        lookKm = max(lookKm, land.bestViewKm[q] * sight(Place(q.i), weather) / (1f - SIGHT_COVER * land.plants.cover(Place(q.i))).coerceAtLeast(0.1f))
    }
    val scouts = b.scoutShare * g.adults
    fun scoutRings(scouts: Float) = if (scouts >= 2f) SCOUT_RINGS else if (scouts >= 1f) SCOUT_RINGS / 2 else 0
    val rings = min(SIGHT_RINGS, max(1, (lookKm / hex.hexKm).toInt())) + scoutRings(scouts)
    dialUse?.amount(DialSite.SCOUTS_TOLD, b.will[BandWay.SCOUTS], scoutRings(scouts).toFloat(), scoutRings(SCOUT_SHARE * g.adults).toFloat())
    // (Every hex within [rings] of the way walked, found ring by ring from the whole path at once.)
    val seen = HashSet<Int>()
    var ring: List<Int> = g.path.filter { seen.add(it) }
    for (h in ring) b.learn(h, hex.size)
    for (r in 1..rings) {
        ring = hex.nextRing(ring, seen)
        for (q in ring) b.learn(q, hex.size)
    }
}

/**
 * How much care band [b] takes over its caches, 1 at its will's default and up to 2: a cache dug with more care takes as much
 * more time a kg, and as much less of it is dug up by beasts or found by other bands. (Read from its will now, not as it was
 * when the cache was dug; ways change slowly. The proportions are a choice: no source sets them.)
 */
internal fun cacheCare(b: Band) = max(1f, b.cacheWill)

/**
 * The kg of [m] [b]'s [people] take along first on a camp move: what mending the tools they know and take along needs
 * over the weeks they keep a camp ([Band.stayWeeks]), as each wears ([Tool.wearPerYear]); and of the materials found only
 * where they crop out (good stone, salt, ochre), what they keep of them ([KEEP_YEARS] of [YEAR_NEED]), since what is left
 * behind is not to be had again on the way.
 */
private fun HexGroups.mendingKg(b: Band, m: Material, people: Float) =
    people * (b.stayWeeks / stepsPerYear * Tool.entries.sumOf { t -> if (b.knows[t] && !t.heavy) ((t.recipe[m] ?: 0f) * t.wearPerYear).toDouble() else 0.0 }.toFloat() +
        KEEP_YEARS * YEAR_NEED[m])

/** The kg of goods [g]'s band [b] takes along on a camp move before its food: what it has of what it takes first ([mendingKg]). */
private fun HexGroups.mendingStockKg(g: HexGroup, b: Band) = Material.entries.sumOf { m -> min(b.goods[m], mendingKg(b, m, g.adults.toFloat())).toDouble() }.toFloat()

/** The kg of its stores [g]'s band [b] cannot carry in its loads ([carryKg], [carryLoads]) beside its mending stock: what it caches or leaves when it moves camp. */
private fun HexGroups.leftBehindKg(g: HexGroup, b: Band): Float {
    val total = g.larder
    val carry = max(0f, carryKg(g, b) * carryLoads(b) - mendingStockKg(g, b))
    if (total <= carry) return 0f
    val left = 1f - carry / total
    return Store.entries.sumOf { (g.stores[it] * left * g.storeKgPer[it]).toDouble() }.toFloat()
}

/** What [g]'s band can carry in one load, kg: its adults' loads, more with baskets and bags. */
private fun carryKg(g: HexGroup, b: Band) = CARRY_KG * max(1, g.adults) * (1f + BASKET_GAIN * b.has(Tool.BASKET))

/**
 * Loads of [carryKg] each of [b]'s adults takes on a camp move, its choice ([BandWay.CARRY_LOADS]): under one, a lighter
 * load and a faster walk; above one, they carry a load ahead, go back for the next and so on (relays).
 */
internal fun carryLoads(b: Band) = b.will[BandWay.CARRY_LOADS]

/** Times [b]'s adults walk the way of a camp move: once, and there and back again for each load past the first. */
internal fun relayWalks(b: Band) = 1f + 2f * max(0f, carryLoads(b) - 1f)

/** What [g]'s band [b] carries a walker on each leg of a camp move, kg: its food first, then its goods, as far as its loads take them. */
private fun carriedKg(g: HexGroup, b: Band) = min(g.larder + b.goods.sum(), carryKg(g, b) * carryLoads(b)) / (max(1, g.adults) * max(1f, carryLoads(b)))

/**
 * Walking pace of an adult carrying [loadKg], km/h: as fast as costs the same effort as walking unloaded ([WALK_KMH]),
 * by Pandolf et al. (1977) on level ground, for a body of [massKg] (W): 1.5 W + 2 (W + L) (L / W)^2 + 1.5 (W + L) v^2.
 */
internal fun loadedKmh(massKg: Float, loadKg: Float): Float {
    val v0 = WALK_KMH / 3.6f
    val wl = massKg + loadKg
    return 3.6f * sqrt(max(0f, (1.5f * massKg * v0 * v0 - 2f * wl * (loadKg / massKg) * (loadKg / massKg)) / (1.5f * wl)))
}

/**
 * [g]'s band at its camp in place [p] (hex [h]): moving camp caches what it cannot carry and leaves
 * the grinding stones; a cache keeps, but not perfectly, and back at it the band takes up what it
 * has room for.
 */
private fun HexGroups.campAndCache(g: HexGroup, b: Band, p: Place, h: Hex, weather: Weather, date: Date, time: BandTime) {
    if (b.campPlace.none || h != hw.hexOf(b.campPlace)) {
        // Moving camp: food that cannot be carried is cached at the old camp (an older cache is given
        // up to the mice), and the grinding stones are left behind.
        if (!b.campPlace.none) {
            // (Some goods are taken first: see [mendingKg].)
            val carry = max(0f, carryKg(g, b) * carryLoads(b) - mendingStockKg(g, b))
            dialUse?.amount(DialSite.CARRY_LOADS, carryLoads(b), carryKg(g, b) * carryLoads(b), carryKg(g, b))
            val total = g.larder
            if (total > carry) {
                // (It chooses how much of what it cannot carry to cache, as far as its time to dig and cover the pits goes;
                // the rest is left where it lies. Above the default it caches all of it with more care, see [cacheCare]:
                // the pits deeper and better hidden, at as much more time for each kg.)
                val left = 1f - carry / total
                val leftKg = leftBehindKg(g, b)
                val care = cacheCare(b)
                fun cachedAt(will: Float, care: Float) =
                    min(min(1f, will), if (leftKg > 0f) time.leftFor(Activity.CACHING) * CACHE_KG_A_DAY / care / leftKg else 0f)
                val cached = cachedAt(b.cacheWill, care)
                dialUse?.amount(DialSite.CACHING, b.cacheWill, cached * care, cachedAt(1f, 1f))
                time.spend(Activity.CACHING, cached * leftKg * care / CACHE_KG_A_DAY)
                if (cached > 0f) dropCache(b)
                var abandoned = 0f
                for (i in Store.entries) {
                    val x = g.stores[i] * left
                    g.stores[i] -= x
                    if (cached > 0f) { b.cache[i] = x * cached; b.cacheKgPer[i] = g.storeKgPer[i] }
                    abandoned += x * (1f - cached) * g.storeKgPer[i]
                }
                if (cached > 0f) b.cachePlace = b.campPlace
                if (abandoned > 0f) small.deposit(DeadPool.LITTER, b.campPlace, Kg(abandoned))
            }
            for (t in Tool.entries) if (t.heavy) b.kit[t] = 0f
            b.stayWeeks += STAY_LEARN * (b.campWeeks + 1 - b.stayWeeks)
            // Its goods: what it takes first ([mendingKg]), then the rest in what room its loads have left after the food, each
            // kind alike; what does not fit is left at the old camp (and goes back to the ground).
            val people = g.adults.toFloat()
            val spare = Material.entries.sumOf { m -> max(0f, b.goods[m] - mendingKg(b, m, people)).toDouble() }.toFloat()
            val room = max(0f, carry - g.larder)
            if (spare > room) {
                val left = 1f - room / spare
                var rots = 0f
                for (m in Material.entries) { val x = max(0f, b.goods[m] - mendingKg(b, m, people)) * left; b.goods[m] -= x; if (m in ROTS) rots += x }
                if (rots > 0f) small.deposit(DeadPool.LITTER, b.campPlace, Kg(rots))
            }
        }
        b.campWeeks = 0
    } else b.campWeeks++
    b.campPlace = p
    // A cache keeps, but not perfectly; back at it, the band takes up what it has room for.
    if (!b.cachePlace.none) {
        // (A cache spoils as stores do, by the week's weather where it lies and as well as its meat was smoked
        // and salted, and mice and badgers get into it too; boar, bears and hyenas dig
        // it up where they are about.)
        run {
            val cp = b.cachePlace
            val diggers = small.rodents[cp] / CACHE_RODENTS_HALF + small.foxes[cp] / CACHE_FOXES_HALF +
                cacheDiggers.sumOf { (fauna.adults[it][cp] / 0.5f).toDouble() }.toFloat()
            val dug = min(0.5f, CACHE_DUG * min(1f, diggers) * years) / cacheCare(b)
            if (dug > 0f) {
                var lost = 0f
                for (i in Store.entries) {
                    val x = b.cache[i] * dug
                    b.cache[i] -= x
                    lost += x * b.cacheKgPer[i]
                }
                // (What they dig up and scatter is food for them and the scavengers after them.)
                small.deposit(DeadPool.CARRION, cp, Kg(lost))
            }
        }
        val spoilt = spoil(b.cache, weather.temperature[b.cachePlace], driedKeep = driedKeep(b), kgPer = b.cacheKgPer) + CACHE_SPOIL * b.cacheMass()
        for (i in Store.entries) b.cache[i] *= 1f - CACHE_SPOIL
        small.deposit(DeadPool.LITTER, b.cachePlace, Kg(spoilt))
        if (hw.hexOf(b.cachePlace) == h) {
            val room = max(0f, carryKg(g, b) - g.larder)
            val take = min(b.cacheKg, room)
            b.takeFromCache(g, take)
            if (take > 0f) stats.meeting(Met.BANDS_BACK_AT_A_CACHE)
        }
        if (b.cacheKg < 1f) dropCache(b)
    }
}

/**
 * What [g]'s band gathers of its materials in a week, into its goods: what the country gives along the way, as far as the
 * band lacks it (up to what it carries, [carried]) and as far as the [time] it has to spare goes, each material at its
 * own rate of gathering. With no [time] it gathers nothing, and returns the person-days it would have liked (else 0).
 */
@IgnorableReturnValue
private fun HexGroups.gatherMaterials(g: HexGroup, b: Band, p: Place, h: Hex, people: Float, m2: Float, time: BandTime?): Float {
    val gd = b.goods
    var asked = 0f
    /** Takes up to [give] kg of [m], as far as the band lacks it and has the time at [perDay] kg a person-day; returns what it took. */
    @IgnorableReturnValue fun gather(m: Material, give: Float, perDay: Float): Float {
        val want = min(give, carried(b, m, people) - gd[m])
        if (want <= 0f || perDay <= 0f) return 0f
        if (time == null) { asked += want / perDay; return 0f }
        val left = time.leftFor(Activity.MATERIALS)
        if (left <= 0f) return 0f
        val took = min(want, left * perDay)
        gd[m] += took; time.spend(Activity.MATERIALS, took / perDay)
        return took
    }
    // What the country gives, as far as the band's time goes: good stone where flint or obsidian crops out
    // along the way (poorer stone anywhere), wood and resin where there are trees, fibre from grass,
    // scrub and reeds; hides, bone and sinew from the big game it killed.
    fun alongTheWay(a: FloatArray) = max(a[p], g.path.maxOfOrNull { q -> hw.placeNear(p, Hex(q)).takeIf { !it.none }?.let { a[it] } ?: 0f } ?: 0f)
    val stone = alongTheWay(land.stone)
    // (Only good stone is kept: whatever stone is at hand makes poorer tools, see [TRADED]. It takes as much as it wants
    // and has the time for, slower where little crops out.)
    if (stone > 0f) gather(Material.STONE, Float.MAX_VALUE, STONE_A_DAY * min(1f, 5f * stone))
    // Salt scraped from a dry lake bed or pan along the way, and a little from the shore; ochre dug where it crops out.
    gather(Material.SALT, people * (SALT_A_WEEK * min(1f, 5f * alongTheWay(land.salt)) + if (hw.coast[h]) SEA_SALT_A_WEEK else 0f), SALT_A_DAY)
    gather(Material.OCHRE, people * OCHRE_A_WEEK * min(1f, 5f * alongTheWay(land.ochre)), OCHRE_A_DAY)
    // (Wood and fibre are what the plants give up: from the trees and the scrub, and the grass and scrub, each by its share.
    // Wood comes as slowly as firewood does, by how thick the trees and scrub stand, as much as it wants and has the time for.)
    val tree = land.plants.tree[p]; val shrub = land.plants.shrub[p]; val herb = land.plants.herb[p]
    val woody = tree + shrub
    if (woody > 0f) {
        val cover = min(1f, woody / 3f)
        val wood = gd[Material.WOOD]
        val took = gather(Material.WOOD, Float.MAX_VALUE, FUEL_A_DAY * cover)
        if (took > 0f) gd[Material.WOOD] = wood + land.plants.consume(p, LifeForm.TREE, Kg(took * tree / woody).perM2(m2)).over(m2).v +
            land.plants.consume(p, LifeForm.SHRUB, Kg(took * shrub / woody).perM2(m2)).over(m2).v
    }
    val resin = gather(Material.RESIN, 0.05f * people * min(1f, tree / 3f), RESIN_A_DAY)
    if (resin > 0f) land.books?.leak(Books.Stuff.MATTER, "resin, not taken from the trees", resin.toDouble())
    // (Reeds and rushes in a marsh give the best fibre of all.)
    val green = herb + shrub
    if (green > 0f) {
        val fibre = gd[Material.FIBRE]
        val took = gather(Material.FIBRE, people * min(1f, (herb + shrub) / 0.5f + 2f * land.waterlogged[p]), FIBRE_A_DAY)
        if (took > 0f) gd[Material.FIBRE] = fibre + land.plants.consume(p, LifeForm.HERB, Kg(took * herb / green).perM2(m2)).over(m2).v +
            land.plants.consume(p, LifeForm.SHRUB, Kg(took * shrub / green).perM2(m2)).over(m2).v
    }
    // Shells on the shore, for beads.
    if (hw.coast[h]) gather(Material.SHELL, SHELL_A_WEEK * people, SHELL_A_DAY)
    return asked
}

/**
 * What [b] carries of [m] at most, kg: its share of the goods a person carries, and at a camp it has kept long enough to build
 * at ([houseAfterWeeks]), building stone and timber stacked beyond that.
 */
private fun HexGroups.carried(b: Band, m: Material, people: Float): Float {
    val stack = if (m == Material.STONE || m == Material.WOOD) (if (b.campWeeks >= houseAfterWeeks(b)) HOUSE_STONE + HOUSE_WOOD else 0f) else 0f
    return (GOODS_PER_PERSON + stack) * people
}

/** How far [g]'s band [b] lacks [m]: 1 with none of it, 0 with all it carries ([carried]). */
internal fun HexGroups.lacking(g: HexGroup, b: Band, m: Material) = max(0f, 1f - b.goods[m] / carried(b, m, g.adults.toFloat()).coerceAtLeast(1e-3f))

/** Weeks [b] keeps a camp before it builds houses there: sooner the keener it is ([BandWay.HOUSES]). */
internal fun HexGroups.houseAfterWeeks(b: Band) = HOUSE_AFTER_YEARS * stepsPerYear / b.houseWill.coerceAtLeast(0.1f)

/**
 * The hearth's firewood for the week, fetched by [hands] before they forage: dead wood from the trees and scrub
 * round the camp, quick to gather where they are thick and slow where they are few, for everyone in the band.
 * A band goes without rather than give more than [FUEL_MOST] of its days to it, and fetches only the [share] of that its
 * week's plan gives it ([planWeek]). Returns the days it took.
 */
internal fun HexGroups.fetchFuel(g: HexGroup, b: Band, hands: Int, share: Float = 1f): Float {
    val p = g.place
    val want = HEARTH_WOOD * days * g.size
    val tree = land.plants.tree[p]; val shrub = land.plants.shrub[p]
    val cover = woodCover(p)
    if (want <= 0f || cover <= 0f) { b.hearthWood = if (want <= 0f) 1f else 0f; return 0f }
    val perHand = g.size.toFloat() / hands
    val spent = fuelShare(cover, perHand) * days * share
    b.hearthWood = hearthShare(cover, perHand) * share
    val got = b.hearthWood * want
    val m2 = land.m2(p)
    val burnt = land.plants.consume(p, LifeForm.TREE, Kg(got * tree / (tree + shrub)).perM2(m2)).v +
        land.plants.consume(p, LifeForm.SHRUB, Kg(got * shrub / (tree + shrub)).perM2(m2)).v
    land.books?.flow(Books.Stuff.MATTER, "burnt", -burnt * m2.toDouble())
    return spent
}

/** The person-days [g]'s [hands] would give the hearth's firewood this week ([fetchFuel] with all it asks). */
internal fun HexGroups.fuelAsk(g: HexGroup, hands: Int): Float {
    val cover = woodCover(g.place)
    return if (HEARTH_WOOD * g.size <= 0f || cover <= 0f) 0f else fuelShare(cover, g.size.toFloat() / hands) * days * hands
}

/** How thickly dead wood lies about place [p], 0 (none) to 1 (trees or scrub thick enough to fetch [FUEL_A_DAY] a person-day). */
internal fun HexGroups.woodCover(p: Place) = min(1f, (land.plants.tree[p] + land.plants.shrub[p]) / 3f)

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
 * What keeping warm takes of group [g]'s week's need at [t] °C in [month], with band [b]'s fire [fire] and at its house
 * or not ([home]): more in the cold it feels through its clothes, fire and shelter, and more again for raw meals where
 * there is no fire ([RAW_COST]). The week's own ([HexGroup.warmth]) and a place weighed ahead ([HexGroups.drain]) alike.
 */
internal fun warmthNeed(s: Species, b: Band?, t: Float, fire: Float, home: Float, month: Int, tuning: Tuning) =
    if (b == null) coldNeed(s, t, month, tuning) else coldNeed(s, b.feels(t, fire, home), month, tuning) * (1f + RAW_COST * (1f - fire))

/**
 * The work [b]'s customs ask of it this week, from [time] (person-days) after the firewood: keeping the camp clean
 * (middens carried off, water fetched apart from them) and nursing its sick and hurt. Each is done as far as the time
 * goes, and helps only as far as it was done.
 */
private fun HexGroups.keepCustoms(b: Band, people: Float, time: BandTime) {
    val clean = cleanDays(b, people)
    b.cleaned = if (clean > 0f) min(1f, time.leftFor(Activity.CUSTOMS) / clean) else 1f
    time.spend(Activity.CUSTOMS, b.cleaned * clean)
    val nurse = nurseDays(b, people)
    b.nursed = if (nurse > 0f) min(1f, time.leftFor(Activity.CUSTOMS) / nurse) else 1f
    time.spend(Activity.CUSTOMS, b.nursed * nurse)
    time.noneBelowZero()
    time.release(Activity.CUSTOMS)
}

/** Person-days [b]'s [people] give a week to keeping clean, if it holds to it. */
private fun HexGroups.cleanDays(b: Band, people: Float) = if (b.holds(Tradition.HYGIENE)) HYGIENE_DAYS * people * days else 0f

/** Person-days [b] gives a week to nursing its sick and hurt among its [people], if it holds to it. */
private fun HexGroups.nurseDays(b: Band, people: Float) =
    if (b.holds(Tradition.MEDICINE)) NURSE_DAYS * people * (b.hurt + b.sick.sum()).coerceAtMost(1f) * days else 0f

/** [b]'s tools wear out in use (slowly lying idle); a band with a use for a tool or practised at a kind of work now and then hits on a new one. */
private fun HexGroups.wearAndInvent(b: Band, years: Float) {
    for (t in Tool.entries) {
        // Tools wear out in use; lying idle, slowly (cords rot, hafts dry and crack).
        val inUse = t == Tool.CLOTHES || t.uses.any { b.weeks - b.lastUsed[it] <= 1 }
        b.kit[t] = max(0f, b.kit[t] - t.wearPerYear * years * (if (inUse) 1f else IDLE_WEAR) *
            (if (t.hafted) b.lapse(Skill.GLUING) else 1f) * wearOfMake(t, b.fine[t], tuning))
        // A band that has a use for a tool now and then hits on it: bows where game is shy and
        // fast, sickles and grinding stones where it reaps seed, and so on.
        if (!b.knows[t] && rnd.nextFloat() < INVENT * b.edge(Skill.TINKER) * years * (0.5f + b.skills[t.serves])) {
            b.knows[t] = true
            stats.meeting(Met.Of.HIT_ON, t.name.lowercase())
        }
    }
    // So with ways of working: a band practised at a kind of work now and then hits on a new way of doing it.
    // (Salting is hit on only with salt to hand.)
    for (s in Skill.entries) if (!b.knowsSkill[s] && (!s.settled || b.atHome) && (s != Skill.SALTING || b.goods[Material.SALT] > 0f) &&
        s.needs.let { it == null || (b.knowsSkill[it] && b.skills[it] >= NEEDS_LEVEL) } &&
        rnd.nextFloat() < s.invent * INVENT * b.edge(Skill.TINKER) * years * (0.5f + b.skill(s.domain))) {
        b.knowsSkill[s] = true
        b.lastUsed[s] = b.weeks
        stats.meeting(Met.Of.HIT_ON, s.name.lowercase().replace('_', ' '))
    }
}

/**
 * Making and mending in the [time] days [b] has to spare: the tools it ranks first first (the
 * scaffold: the most worn), each as far as the time, the scarcest material and the skill allow. With no [time] it makes
 * nothing, and returns the person-days it would have liked (else 0).
 */
@IgnorableReturnValue
private fun HexGroups.makeTools(b: Band, people: Float, time: BandTime?): Float {
    val gd = if (time == null) b.goods.copyOf() else b.goods
    var asked = 0f
    val order = Tool.entries.filter { b.knows[it] && b.kit[it] < 1f }.sortedByDescending { b.makeFirst[it] * (1f - b.kit[it]) }
    if (time != null) dialUse?.let { u -> if (order.size > 1) {
        val order1 = Tool.entries.filter { b.knows[it] && b.kit[it] < 1f }.sortedByDescending { 1f - b.kit[it] }
        // (Counted under the tool it put first: whether the order differs from the scaffold's, most worn first.)
        u.choice(DialSite.MAKE_FIRST, b.makeFirst[order[0]], order != order1, false, dial = MAKE_FIRST_SLOT + order[0].ordinal)
    } }
    for (t in order) {
        val left = time?.leftFor(Activity.TOOLS) ?: Float.MAX_VALUE
        if (left <= 0f || people <= 0f) break
        val way = b.makeWay(t)
        val daysEach = t.makeDays * people / b.makeSkill(t) // for a whole band's kit
        // (Blades get several times the edge out of a kilo of flint.)
        val stoneUse = if (way == Skill.BLADES || way == Skill.MICROLITHS) BLADE_STONE else 1f
        var can = min(1f - b.kit[t], left / daysEach)
        fun use(m: Material) = if (m == Material.STONE) stoneUse else 1f
        // (Stone lacking is made up from whatever stone is at hand, and the tool is the poorer
        // for it; clothes are tanned with ochre as far as it goes.)
        val made = fineMaterial(t)
        for ((m, perPerson) in t.recipe) if (m != made) can = min(can, gd[m] / max(1e-3f, perPerson * use(m) * people))
        if (can <= 0f) continue
        for ((m, perPerson) in t.recipe) if (m != made) gd[m] = max(0f, gd[m] - can * perPerson * use(m) * people)
        if (time == null) { asked += can * daysEach; continue }
        if (made != null) {
            val want = can * (t.recipe[made] ?: TAN_OCHRE) * use(made) * people
            val good = if (want > 0f) min(1f, gd[made] / want) else 1f
            gd[made] = max(0f, gd[made] - good * want)
            b.fine[t] = (b.kit[t] * b.fine[t] + can * good) / (b.kit[t] + can)
        }
        b.kit[t] += can; time.spend(Activity.TOOLS, can * daysEach)
        b.practise(way); b.practise(Skill.TINKER, 0.02f * PRACTICE)
        // (And tries the other ways of the craft it knows now and then, so a new way can come to beat the old.)
        for (o in Skill.of(way.domain)) if (o != way && b.knowsSkill[o]) b.practise(o, 0.2f * PRACTICE * b.edge(Skill.TINKER))
        if (t == Tool.CLOTHES) { b.practise(Skill.SEWING); b.practise(Skill.BONE_TOOLS) }
        if (t.hafted) b.practise(Skill.GLUING)
    }
    return asked
}

/**
 * Houses: [g]'s band, having kept its camp a while, builds stone-footed houses there with the [time] days to spare, as
 * far as its stone and timber go: houses for those of its [people] the houses there it may use do not shelter. (What it
 * builds stands in the world, anyone's to use once it leaves: see [Structure].) With no [time] it builds nothing, and
 * returns the person-days it would have liked (else 0).
 */
@IgnorableReturnValue
private fun HexGroups.buildHouse(g: HexGroup, b: Band, p: Place, h: Hex, people: Float, time: BandTime?): Float {
    val gd = b.goods
    val left = time?.leftFor(Activity.HOUSES) ?: Float.MAX_VALUE
    // (Only where the camp keeps it well fed and its stores filled: building is worth it where staying pays.)
    if (time != null) dialUse?.let { u -> if (left > 0f && people > 0f && g.fed >= 0.9f) {
        val longEnough = b.campWeeks >= houseAfterWeeks(b)
        val stocked = g.larder >= 0.5f * b.storeAim * g.dailyNeedKg
        u.choice(DialSite.HOUSES, b.houseWill, longEnough && stocked, b.campWeeks >= HOUSE_AFTER_YEARS * stepsPerYear && stocked, cut = b.houseWill < 0.1f)
        u.choice(DialSite.STORE_HOUSES, b.storeAim / STORE_AIM_DAYS, longEnough && stocked, longEnough && g.larder >= 0.5f * STORE_AIM_DAYS * g.dailyNeedKg)
    } }
    if (b.campWeeks < houseAfterWeeks(b) || left <= 0f || people <= 0f || g.fed < 0.9f || g.larder < 0.5f * b.storeAim * g.dailyNeedKg) return 0f
    val sheltered = housesAt(g, h.i)
    if (sheltered >= 1f) return 0f
    // It builds the way it knows that its stone, timber and clay go furthest with (or goes on with what it began).
    val clay = land.drink.water[p] >= Thresholds.WET || land.drink.waterKm[p] < 3f
    val begun = built.firstOrNull { it.kind == Built.HOUSES && it.hex == h.i && it.user == g.id && it.built < 1f }
    val size = begun?.size ?: (people * (1f - sheltered))
    val way = begun?.let { Skill.entries[it.design] } ?: Skill.BUILDING_WAYS.filter { b.knowsSkill[it] && (it != Skill.MUDBRICK || clay) }
        .maxByOrNull { w ->
            val (st, wd, dd) = houseNeeds(w)
            minOf(left / (dd * size), gd[Material.STONE] / max(1e-3f, st * size), gd[Material.WOOD] / (wd * size))
        }
        ?: Skill.STONE_FOOTING
    val (stoneEach, woodEach, daysEach) = houseNeeds(way)
    val daysNow = daysEach * size / b.skill(way) * b.skill(Skill.STONE_FOOTING)
    var built = min(1f - (begun?.built ?: 0f), left / daysNow)
    built = minOf(built, if (stoneEach > 0f) gd[Material.STONE] / (stoneEach * size) else 1f, gd[Material.WOOD] / (woodEach * size))
    built = max(0f, built)
    if (time == null) return built * daysNow
    val s = begun ?: buildingAt(g, Built.HOUSES, h.i, way.ordinal, size)
    gd[Material.STONE] -= built * stoneEach * size; gd[Material.WOOD] -= built * woodEach * size
    s.built += built; time.spend(Activity.HOUSES, built * daysNow)
    if (built > 0f) b.practise(way)
    if (built > 0f) stats.meeting(Met.BANDS_BUILT_HOUSES)
    return 0f
}

/** Ornaments of shell and bone, made with the [time] days [b] has still to spare. */
private fun HexGroups.makeOrnaments(b: Band, people: Float, time: BandTime) {
    val gd = b.goods
    val left = time.leftFor(Activity.ORNAMENTS)
    if (left > 0f && people > 0f) {
        val made = min(left / ORNAMENT_DAYS, ornamentsWanted(b, people))
        if (made > 0f) {
            val fromShell = min(made, gd[Material.SHELL] / SHELL_EACH)
            gd[Material.SHELL] = max(0f, gd[Material.SHELL] - fromShell * SHELL_EACH)
            gd[Material.BONE] = max(0f, gd[Material.BONE] - (made - fromShell) * BONE_EACH)
            b.ornaments += made; b.exotic += fromShell; time.spend(Activity.ORNAMENTS, made * ORNAMENT_DAYS)
        }
    }
}

/** The ornaments [b] would make for its [people], as far as it lacks them and has shell and bone. */
private fun ornamentsWanted(b: Band, people: Float) =
    min(b.goods[Material.SHELL] / SHELL_EACH + b.goods[Material.BONE] / BONE_EACH, ORNAMENTS_PER_PERSON * people - b.ornaments).coerceAtLeast(0f)

/**
 * In autumn [g]'s band, at home or at a camp it has kept long enough to build at ([houseAfterWeeks]), sows a share of its stored
 * grain there, in the [time] days to spare. (A band that then moves on leaves the plot to whoever comes by.)
 */
private fun HexGroups.sowGrain(g: HexGroup, b: Band, p: Place, date: Date, time: BandTime) {
    val left = time.leftFor(Activity.SOWING)
    if (canSow(g, b, p, date) && left > 0f) {
        val stock = g.stores[Store.SEED]
        val sow = minOf(stock * SOW_SHARE * b.sowWill, left / SOW_DAYS_PER_KG)
        dialUse?.amount(DialSite.SOWING, b.sowWill, sow, minOf(stock * SOW_SHARE, left / SOW_DAYS_PER_KG))
        if (sow > 0.1f) {
            g.stores[Store.SEED] -= sow; time.spend(Activity.SOWING, sow * SOW_DAYS_PER_KG)
            val food = fauna.food
            val standing = food.cereal[p] * land.plants.herb[p] * HexFood.GRAIN_SHARE + food.field[p]
            val add = land.spread(p, Kg(sow * g.storeKgPer[Store.SEED])).v
            food.domestic[p] = (food.domestic[p] * standing + b.seedDomestic * add) / (standing + add).coerceAtLeast(1e-9f)
            food.field[p] += add
            stats.meeting(Met.BANDS_SOWED)
            note(g) { "sowed %.0f kg of grain at home".format(sow) }
        }
    }
}

/** Whether [g]'s band [b] sows at place [p] now: in autumn, at home or at a camp it has kept long enough to build at, where grass grain grows. */
private fun HexGroups.canSow(g: HexGroup, b: Band, p: Place, date: Date) =
    (b.atHome || b.campWeeks >= houseAfterWeeks(b)) && date.month in SOW_MONTHS && fauna.food.cereal[p] > 0f

/** A palisade round the houses at [g]'s band [b]'s camp at hex [h], at home and raided in the last year, as far as the [time] days and its timber go. */
private fun HexGroups.buildPalisade(g: HexGroup, b: Band, h: Hex, people: Float, time: BandTime) {
    val gd = b.goods
    val left = time.leftFor(Activity.PALISADE)
    if (b.atHome && b.palisade < 1f && left > 0f && people > 0f)
        dialUse?.choice(DialSite.PALISADES, b.fortifyWill, b.sinceRaided < stepsPerYear * b.fortifyWill, b.sinceRaided < stepsPerYear)
    if (fortifies(b) && left > 0f && people > 0f) {
        val s = buildingAt(g, Built.PALISADE, h.i, -1, people)
        val built = minOf(1f - s.built, left / (PALISADE_DAYS * people), gd[Material.WOOD] / (PALISADE_WOOD * people)).coerceAtLeast(0f)
        gd[Material.WOOD] -= built * PALISADE_WOOD * people; s.built += built; time.spend(Activity.PALISADE, built * PALISADE_DAYS * people)
        if (built > 0f) stats.meeting(Met.BANDS_BUILT_PALISADES)
    }
}

/** Whether [b] rings its houses with a palisade: at home, raided lately (by its will), with the ring not whole. */
private fun HexGroups.fortifies(b: Band) = b.atHome && b.sinceRaided < stepsPerYear * b.fortifyWill && b.palisade < 1f

/** What [b] cannot carry is left behind at place [p] (and goes back to the ground); at a camp it keeps, it stacks building stone and timber beyond that. */
private fun HexGroups.leaveExcess(b: Band, p: Place, people: Float, area: Float) {
    val gd = b.goods
    for (m in Material.entries) {
        val over = gd[m] - carried(b, m, people)
        if (over > 0f) {
            gd[m] -= over
            if (m in ROTS) small.deposit(DeadPool.LITTER, p, KgM2(over / area))
        }
    }
}

/** Knowledge fades fast unused, unless songs and stories keep [b]'s; hand skills last for years; a way no one holds is lost. */
private fun HexGroups.forget(b: Band) {
    val forgetKnowledge = (if (b.holds(Tradition.ORAL_TRADITION)) FORGET_KNOWLEDGE * 0.4f else FORGET_KNOWLEDGE) * b.lapse(Skill.LORE)
    for (s in Skill.entries) {
        val unused = b.weeks - b.lastUsed[s] > 4
        b.skills[s] *= 1f - (if (!unused) FORGET_HANDS else if (s.knowledge) forgetKnowledge else FORGET_HANDS * 2f)
        // A way no one holds and no one has used for years is lost altogether; so is one whose groundwork is lost.
        if (b.knowsSkill[s] && s.losable && b.holders[s] < 0.5f && b.weeks - b.lastUsed[s] > (if (s.knowledge) LOST_KNOWLEDGE_YEARS else LOST_HANDS_YEARS) * stepsPerYear) {
            b.knowsSkill[s] = false; b.skills[s] = 0f; b.holders[s] = 0f
            stats.meeting(Met.Of.LOST, s.name.lowercase().replace('_', ' '))
        }
        s.needs?.let { n -> if (b.knowsSkill[s] && !b.knowsSkill[n]) { b.knowsSkill[s] = false; b.skills[s] = 0f; b.holders[s] = 0f } }
    }
}

/**
 * Packs of kinds that follow people (wolves, jackals) and live by a band's camps grow bolder over
 * generations, the shy ones keeping away; from a bold pack the band now and then takes a pup and
 * raises it. Its dogs breed when the band eats well, help it hunt and guard its camp.
 */
private fun HexGroups.keepAnimals(g: HexGroup, b: Band) {
    val h = hw.placeHex[g.place]
    for (o in followersByHex[h].orEmpty()) {
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

/** Gives up a band's cache: what is in it goes back to the ground. */
internal fun HexGroups.dropCache(b: Band) {
    if (!b.cachePlace.none) small.deposit(DeadPool.LITTER, b.cachePlace, Kg(b.cacheMass()))
    b.cache.fill(0f); b.cachePlace = Place.NONE
}

/** The materials that rot back into the ground when left (stone, salt, ochre and shell stay as they are). */
private val ROTS = setOf(Material.WOOD, Material.HIDE, Material.BONE, Material.SINEW, Material.FIBRE, Material.RESIN)
/** The kg of [b]'s goods that came from living things (those that rot), for the books. */
internal fun Band.livingGoods(): Float { var sum = 0f; for (m in ROTS) sum += goods[m]; return sum }
/** Moves [kg] of the cache (in the same mix of stores) into [to]'s stores. */
internal fun Band.takeFromCache(to: HexGroup, kg: Float) {
    val share = if (cacheKg > 0f) kg / cacheKg else 0f
    for (i in Store.entries) { val x = cache[i] * share; cache[i] -= x; addFood(to.stores, to.storeKgPer, i, x, x * cacheKgPer[i]) }
}

/** The kg [Band.cache] weighs (see [addFood]). */
internal fun Band.cacheMass() = massOf(cache, cacheKgPer)

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
    for (k in b.herd.indices) if (b.herd[k] > 0f) { fauna.adults[k][p] += b.herd[k] / area; b.herd[k] = 0f; b.herdTame[k] = 0f }
}

/** How much more slowly a tool wears lying idle than in use; how much of a craft a band learns making do without its tools. */
const val IDLE_WEAR = 0.3f
const val TOOLLESS = 0.2f


/** Two bands that meet show each other tools one of them does not know, the more readily the better they understand each other ([understood]). */
internal fun HexGroups.teach(a: Band, b: Band, understood: Float, aDoesBetter: Float = 0f) {
    for (k in Tool.entries) if (a.knows[k] != b.knows[k] && rnd.nextFloat() < TEACH * (0.5f + 0.5f * understood)) {
        a.knows[k] = true; b.knows[k] = true
        stats.meeting(Met.Of.LEARNED, k.name.lowercase())
    }
    // And ways of working: the one that knows a way shows it; the other starts on it as a beginner.
    // (Bands take up a stranger's way the less readily the more their customs differ, and the more
    // readily from a band that does better than they do.)
    val alike = 1f - CONFORMITY * Tradition.entries.count { a.holds(it) != b.holds(it) }.toFloat() / Tradition.entries.size
    for (k in Skill.entries) if (a.knowsSkill[k] != b.knowsSkill[k]) {
        val (from, to) = if (a.knowsSkill[k]) a to b else b to a
        val need = k.needs
        if (need != null && (!to.knowsSkill[need] || to.skills[need] < NEEDS_LEVEL)) continue
        val prestige = (1f + PRESTIGE * (if (from === a) aDoesBetter else -aDoesBetter)).coerceIn(0.2f, 2f)
        if (rnd.nextFloat() >= k.teach * TEACH * (0.5f + 0.5f * understood) * alike * prestige) continue
        // (A meeting shows that a way exists; the skill itself comes only with people who stay to teach it.)
        // (Newly learned, it counts as fresh: it is lost only if it then goes unused for years.)
        to.knowsSkill[k] = true; to.lastUsed[k] = to.weeks
        stats.meeting(Met.Of.LEARNED, k.name.lowercase().replace('_', ' '))
    }
}

/** How much a band is put off country it does not know (beside the pull of better country it senses); hexes round its first camp it knows from the start. */
const val UNKNOWN_COUNTRY = 0.2f
const val KNOWN_AT_START = 3

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
/** How strongly people's camps draw bold animals of kinds that follow people (refuse, scraps, warmth). */
const val CAMP_LURE = 0.3f

/** Share of a cache lost each week to damp, mice and thieves. */
const val CACHE_SPOIL = 0.02f
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

/** Settled life with stores shortens the spacing of births. */
internal fun HexGroups.settledFertility(g: HexGroup): Float {
    val b = g.band ?: return 1f
    return 1f + SETTLED_FERTILITY * tuning.settledFertility * min(1f, b.campWeeks / (stepsPerYear / 2f)) * min(1f, g.larderDays() / WELL_STOCKED_DAYS)
}

/** Extra yearly deaths among children of a camp kept long: refuse, flies and fouled water. */
internal fun HexGroups.campFouling(g: HexGroup, campWeeks: Int = g.band?.campWeeks ?: 0): Float {
    val b = g.band ?: return 0f
    return CAMP_FOULING * tuning.campFouling * fouled(campWeeks) * b.hygiene * (1f - 0.3f * b.medicine)
}

/** How fouled a band's camp is, 0..1: not at all for its first two months, fully after a year more. */
internal fun HexGroups.fouled(b: Band) = fouled(b.campWeeks)
/** How fouled a camp kept [campWeeks] weeks is (see [fouled]). */
internal fun HexGroups.fouled(campWeeks: Int) = min(1f, max(0f, campWeeks - stepsPerYear / 6f) / stepsPerYear)

/** How much a band would rather leave its camp than stay: the camp fouled (less where it keeps it clean), against the graves of its dead. */
internal fun HexGroups.stayCost(b: Band): Float {
    var c = FOUL_AVOID * fouled(b) * b.hygiene
    if (b.holds(Tradition.BURIAL) && b.campWeeks > 4) c -= BURIAL_STAY
    return c
}

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

/**
 * Food on hand, by how it keeps: fresh meat and fish rot in days (faster in the heat), dried meat
 * and fish keep for months, fruit for a week or two, roots for some weeks, seed and nuts for a year, rendered grease
 * sealed in guts or bladders for months (its rate a guess).
 * [lostWeekly] is the share lost a week (fresh meat: see [spoil]); [meat] whether it counts as meat (for its salt);
 * [protein] the share of its energy that is protein (fresh and dried meat and fish at meat's; small finds kept with fruit).
 */
enum class Store(val lostWeekly: Float, val meat: Boolean, val protein: Float) {
    FRESH(0f, true, Nourishment.PROTEIN_MEAT), DRIED(0.01f, true, Nourishment.PROTEIN_MEAT), FRUIT(0.1f, false, Nourishment.PROTEIN_FRUIT),
    ROOTS(0.03f, false, Nourishment.PROTEIN_ROOTS), SEED(0.005f, false, Nourishment.PROTEIN_GRAIN), GREASE(0.01f, false, 0f)
}
/** The order a band eats its food in: what spoils first, first. */
val EAT_ORDER = listOf(Store.FRESH, Store.FRUIT, Store.ROOTS, Store.DRIED, Store.SEED, Store.GREASE)
/** The scaffold's store: days of food a band aims to keep; share of the gap it works to close a week. */
const val STORE_AIM_DAYS = 30f
const val STORE_FILL = 0.25f
/** Person-days to dry or smoke a kilo of meat or fish (cutting it thin, tending the racks and fire), at full skill. */
const val DRY_DAYS_PER_KG = 0.1f

/** Kinds of food a band sets its effort on (see [forage]): the [Food] kinds before grain (the last), which goes by grass seed's. */
const val FOOD_TYPES = 9
/** [setWays]' outputs after the efforts: the store aim, then one for each [Tool] (which to make first), then the [BandWay]s. */
const val STORE_AIM_SLOT = FOOD_TYPES
const val MAKE_FIRST_SLOT = STORE_AIM_SLOT + 1

/**
 * How a band sets its ways for the week: given what it knows of itself ([bandInputs], [WAYS_IN]
 * numbers), it writes [WAYS_OUT] numbers to `out`, each 0 for the scaffold's choice: its effort on
 * each kind of food, the days of food it aims to keep, how it ranks making each tool, and its [BandWay]s.
 */
fun interface BandWays { fun choose(g: HexGroup, inputs: FloatArray, out: FloatArray) }
val WAYS_IN = BandInputs.size
/**
 * A band's ways after its effort on each food, its store aim and its ranking of the tools, in the order of
 * [setWays]' outputs; [label] is the output's name in saved networks ([inflation.learning.Schema.WAYS_OUT]).
 */
enum class BandWay(val label: String) {
    SCOUTS("scouts"),
    HOUSES("houses"),
    RAIDS("raids"),
    PALISADES("palisades"),
    SOWING("sowing"),
    GATHERING_RANGE("gathering range"),
    WATCHING_CHILDREN("watching children"),
    NIGHT_FIRE("night fire"),
    SHUNNING_THE_SICK("shunning the sick"),
    EXPLORING("exploring"),
    GIVING("giving"),
    TAKING_FROM_CACHES("taking from caches"),
    CACHING("caching"),
    DRYING("drying"),
    KEEPING_YOUNG_ANIMALS("keeping young animals"),
    SHELTERS("shelters"),
    FEASTING("feasting"),
    APPOINTMENTS("appointments"),
    BURNING_OFF("burning off"),
    REPAYING("repaying"),
    MENDING_FIRST("mending first"),
    STORING_MEAT("storing: meat and fish"),
    STORING_FRUIT("storing: fruit and finds"),
    STORING_ROOTS("storing: roots"),
    STORING_SEED("storing: seed and grain"),
    /** Loads each adult carries on a camp move ([carryLoads]): under one, lighter and faster; over one, relays. */
    CARRY_LOADS("carry loads"),
    /**
     * How a band weighs each activity's ask for days against the others' when they come to more than its week
     * ([BandTime.plan]): the less weighed lose their days first.
     */
    TIME_STORES("time: laying food by"), TIME_DRYING("time: drying"), TIME_SKINS("time: skins"), TIME_SHELTER("time: shelter"),
    TIME_CUSTOMS("time: customs"), TIME_TRAINING("time: training"), TIME_TOOLS("time: tools"), TIME_HOUSES("time: houses"),
    TIME_ORNAMENTS("time: ornaments"), TIME_SOWING("time: sowing"), TIME_PALISADE("time: palisade"), TIME_MATERIALS("time: materials"),
    TIME_CACHING("time: caching");
    /** Its place among the ways' outputs. */
    val slot = MAKE_FIRST_SLOT + Tool.entries.size + ordinal
}

/** The way that weighs [a]'s ask for days ([BandWay.TIME_STORES] and after); none for what comes first (the walk, the week's food and firewood) or is what is left (leisure). */
fun timeWay(a: Activity): BandWay? = when (a) {
    Activity.FOOD -> BandWay.TIME_STORES
    Activity.DRYING -> BandWay.TIME_DRYING
    Activity.SKINS -> BandWay.TIME_SKINS
    Activity.SHELTER -> BandWay.TIME_SHELTER
    Activity.CUSTOMS -> BandWay.TIME_CUSTOMS
    Activity.TRAINING -> BandWay.TIME_TRAINING
    Activity.TOOLS -> BandWay.TIME_TOOLS
    Activity.HOUSES -> BandWay.TIME_HOUSES
    Activity.ORNAMENTS -> BandWay.TIME_ORNAMENTS
    Activity.SOWING -> BandWay.TIME_SOWING
    Activity.PALISADE -> BandWay.TIME_PALISADE
    Activity.MATERIALS -> BandWay.TIME_MATERIALS
    Activity.CACHING -> BandWay.TIME_CACHING
    Activity.FIREWOOD, Activity.MOVING, Activity.LEISURE -> null
}
/** The way that sets how keenly a band lays food [t] by ([Band.storeWill]). */
fun storeWay(t: Food) = when (t) {
    Food.BIG_GAME, Food.SMALL_GAME, Food.CARRION, Food.BONES, Food.FISH -> BandWay.STORING_MEAT
    Food.FRUIT, Food.FINDS -> BandWay.STORING_FRUIT
    Food.ROOTS -> BandWay.STORING_ROOTS
    Food.SEED, Food.GRAIN -> BandWay.STORING_SEED
}
val WAYS_OUT = MAKE_FIRST_SLOT + Tool.entries.size + BandWay.entries.size
/** The name of each of a band's ways, in order ([setWays]), as a learned network's `.names` file records them (the strings must not change). */
val WAYS_OUT_NAMES: List<String> get() = (0 until FOOD_TYPES).map { "effort:$it" } + listOf("store aim") +
    Tool.entries.map { "make first:${it.name.lowercase()}" } + BandWay.entries.map { it.label }

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
        if (h < 0) continue
        for (o in bandIndex[h] ?: continue) {
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
    into[x.FISH] = weeks(fauna.water.fishWithin(h, b.fishing()))
    // Plants: what its gatherers would strip from the camp's own ground in a week (as in [forage]).
    val food = fauna.food
    val pick = min(0.9f, b.gatherShare(false, tuning) * b.gatherRange)
    val reap = min(0.9f, b.gatherShare(true, tuning) * b.gatherRange)
    into[x.FRUIT] = weeks(land.total(p, KgM2(pick * food.fruit[p])).v)
    into[x.ROOTS] = weeks(land.total(p, KgM2(pick * food.roots[p])).v)
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
    for ((k, w) in Skill.BUILDING_WAYS.withIndex()) into[x.BUILDING + k] = if (b.knowsSkill[w]) b.skill(w) else 0f
    val here = hw.placeHex[g.place] == b.houseHex.i
    into[x.HOUSE_HERE] = if (here) b.house else 0f; into[x.PALISADE_HERE] = if (here) b.palisade else 0f
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

/** Sets a band's ways from [ways]' outputs: 0 is the scaffold (effort 1, stores [STORE_AIM_DAYS], every tool alike). */
/** Files every living band under the hex it is in now ([HexGroups.bandIndex]), for [bandInputs]' bands about. */
internal fun HexGroups.indexBands() {
    bandIndex.clear()
    for (g in groups) if (g.alive && g.band != null) bandIndex.getOrPut(hw.placeHex[g.place]) { ArrayList() } += g
}

internal fun HexGroups.setWays(g: HexGroup, weather: Weather, ways: BandWays, inputs: FloatArray, out: FloatArray) {
    val b = g.band ?: return
    bandInputs(g, weather, inputs)
    out.fill(0f)
    ways.choose(g, inputs, out)
    if (g.id == watched) control?.ways(g, out)
    fun squash(x: Float) = 2f / (1f + exp(-x))
    for (t in 0 until FOOD_TYPES) b.effort[t] = squash(out[t])
    b.storeAim = STORE_AIM_DAYS * squash(out[STORE_AIM_SLOT])
    for (t in Tool.entries) b.makeFirst[t] = squash(out[MAKE_FIRST_SLOT + t.ordinal])
    for (w in BandWay.entries) b.will[w] = squash(out[w.slot])
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
 * Caches: yearly share dug up where diggers are thick; rodents and foxes (kg/km²) at which they
 * count fully; chance a band passing a hex finds another's cache there; share it takes.
 */
const val CACHE_DUG = 2f
const val CACHE_RODENTS_HALF = 200f
const val CACHE_FOXES_HALF = 20f
const val CACHE_FIND = 0.15f
const val CACHE_TAKE = 0.6f
/**
 * Stone and timber for one person's share of a stone-footed house, kg; person-days for one person's share of a brush
 * shelter (a debris hut for about five takes a novice an afternoon, from memory); how much warmer a brush shelter keeps, °C.
 */
const val HOUSE_STONE = 150f
const val HOUSE_WOOD = 60f
const val SHELTER_DAYS = 0.1f
const val SHELTER_C = 3f
const val HOUSE_AFTER_YEARS = 0.5f
const val HOUSE_DECAY = 0.3f
const val HOUSE_C = 8f
const val PIT_KEEP = 0.5f
const val MICE = 0.01f
const val HOUSE_DEFENCE = 0.5f
const val HOME_PULL = 0.15f

/**
 * Bands passing through another band's cache may find it and carry off what they can, unless they
 * are close to its owners: the more it lies in the open and the more they see, the likelier. The
 * owners hold it against the takers when they learn of it.
 */
internal fun HexGroups.robCaches(weather: Weather) {
    val caches = HashMap<Int, ArrayList<HexGroup>>()
    for (g in groups) {
        val b = g.band ?: continue
        if (g.alive && !b.cachePlace.none && b.cacheKg > 1f) caches.getOrPut(hw.placeHex[b.cachePlace]) { ArrayList() } += g
    }
    if (caches.isEmpty()) return
    for (g in groups) {
        val b = g.band ?: continue
        if (!g.alive) continue
        for (h in g.path) {
            val owners = caches[h] ?: continue
            for (o in owners) {
                val ob = o.band ?: continue
                if (o === g || b.tie(o.id) > 0.3f || ob.cacheKg <= 1f) continue
                if (!drawn(DialSite.SCOUTS_FIND_CACHE, b.will[BandWay.SCOUTS], rnd.nextFloat(), CACHE_FIND * sight(ob.cachePlace, weather) * (1f + b.scoutShare * 5f) / cacheCare(ob))
                    { CACHE_FIND * sight(ob.cachePlace, weather) * (1f + SCOUT_SHARE * 5f) / cacheCare(ob) }) continue
                // (Found, it is the band's choice whether to take from it.)
                if (!drawn(DialSite.ROB_TAKE, b.robWill, rnd.nextFloat(), min(1f, b.robWill), cut = b.robWill > 1f) { 1f }) continue
                val takeShare = CACHE_TAKE * max(1f, b.robWill)
                dialUse?.amount(DialSite.ROB_SHARE, b.robWill, takeShare, CACHE_TAKE, cut = b.robWill < 1f)
                val room = max(0f, carryKg(g, b) - g.larder)
                val take = min(ob.cacheKg * min(1f, takeShare), room)
                if (take <= 0f) continue
                ob.takeFromCache(g, take)
                ob.befriend(g.id, -TIE_RAID * 0.5f); ob.grudge[b.mainLineage()] += 0.5f * GRUDGE
                stats.meeting(Met.CACHES_ROBBED)
                note(g) { "found and took %.0f kg from band #%d's cache".format(take, o.id) }; note(o) { "its cache was robbed by band #%d".format(g.id) }
            }
        }
    }
}

/**
 * A band driven by hunger or a feud, knowing where a band it is not close to has its home within
 * reach, sets out to fall on it there: settled bands can be found. Once there, if the others are at
 * home, it attacks (see [fight], fought in [fightRaids] once every band has had its week); otherwise
 * it gives up.
 */
internal fun HexGroups.plotRaid(g: HexGroup, b: Band, weather: Weather) {
    val here = hw.hexOf(g.place)
    b.raid?.let { raid ->
        if (here == raid.hex) {
            val target = groups.firstOrNull { it.id == raid.on && it.alive && hw.hexOf(it.place) == here }
            if (target?.band != null) raidsDue += g to target
            b.raid = null
        } else b.raid = if (raid.weeks > 1) Raid(raid.hex, raid.on, raid.weeks - 1) else null
        return
    }
    val hungry = g.fed < RAID_HUNGER * b.raidWill.coerceAtMost(1.6f)
    dialUse?.choice(DialSite.RAIDS_HUNGER, b.raidWill, hungry, g.fed < RAID_HUNGER, cut = b.raidWill > 1.6f)
    val within = ArrayList<Int>()
    for ((id, h) in b.knownHomes) {
        if (b.tie(id) > 0f) continue
        val feud = (b.knownLineage[id] ?: -1).let { it >= 0 && b.grudge[it] > FEUD }
        if (!hungry && !feud) continue
        if (hexKm(here, Hex(h)) <= RAID_RANGE_KM) within += id
    }
    if (within.isNotEmpty() && drawn(DialSite.RAIDS_PLOT, b.raidWill, rnd.nextFloat(), RAID_PLOT * b.raidWill * days / 7f) { RAID_PLOT * days / 7f }) {
        // Above all it keeps alive: it falls on the home where it would lose fewest of its own (the defenders as it
        // knows them, at home behind their works; see [raidStrengths]); of homes about as safe (within a step of its
        // score, see [survivalSteps]), the nearest.
        val dying = HashMap<Int, Float>()
        for (id in within) {
            val o = groups.firstOrNull { it.id == id && it.alive } ?: continue
            val ob = o.band ?: continue
            val (sg, so) = raidStrengths(g, o, ob, planned = true, atHome = true, weather)
            dying[id] = RAID_DEATHS * so / (sg + so)
        }
        if (dying.isEmpty()) return
        val least = dying.values.min()
        val best = dying.keys.filter { survivalSteps(g, dying.getValue(it) - least) <= 1f }
            .minWith(compareBy<Int> { hexKm(here, Hex(b.knownHomes.getValue(it))) }.thenBy { it })
        val at = b.knownHomes.getValue(best)
        b.raid = Raid(Hex(at), best, RAID_WEEKS)
        stats.meeting(Met.RAIDS_SET_OUT)
        note(g) { "set out to raid band #%d at hex %d".format(best, at) }
    }
}

/** The raids set for this week ([plotRaid]), in the bands' order, each if both bands are still alive and the target still there. */
internal fun HexGroups.fightRaids(weather: Weather) {
    for ((g, target) in raidsDue) {
        val b = g.band ?: continue; val tb = target.band ?: continue
        if (g.alive && target.alive && hw.placeHex[target.place] == hw.placeHex[g.place]) fight(g, b, target, tb, planned = true, weather)
    }
    raidsDue.clear()
}

/**
 * Raids: hunger under which a band will go raiding; how far, km; weekly chance a driven band sets
 * out; weeks it keeps at it. Palisades: person-days and timber (kg) for one person's share.
 * Fighting: surprise and the lie of the land.
 */
const val RAID_HUNGER = 0.6f
const val RAID_RANGE_KM = 60.0
const val RAID_PLOT = 0.3f
const val RAID_WEEKS = 4
const val PALISADE_DAYS = 6f
const val PALISADE_WOOD = 80f
const val PALISADE_DEFENCE = 1f
const val SURPRISE_RAID = 1f
const val HIGH_GROUND = 0.5f
/** Chance a band with no home to hold slips away from a raid, losing only what it cannot carry. */
const val FLEE = 0.5f

/**
 * Sowing: months (0 the one after midwinter) for sowing in autumn; share of its stored grain a band
 * sows; person-days to clear, sow and weed for each kg sown.
 */
val SOW_MONTHS = 9..10
const val SOW_SHARE = 0.3f
const val SOW_DAYS_PER_KG = 0.5f
/**
 * How much likelier a sickle harvest takes an ear that holds its grain than one that shatters at a
 * touch. Set so that ears that hold their grain take one to two thousand years to come to dominate
 * a cultivated stand, as the archaeobotany of Near Eastern wheat and barley shows (Fuller and
 * colleagues: early harvests were often green, by beating or uprooting, and sown seed was mixed with
 * wild-gathered grain), not the decades a pure sickle-and-resow model would give.
 */
const val SICKLE_SELECT = 0.06f

/**
 * Ornaments: shell gathered on a shore a week per person, kg; shell and bone for one ornament, kg;
 * person-days to make one; most a band keeps per person; share of its ornaments a band gives a band
 * it is close to when they meet; share of a band's ornaments buried with each of its dead (grave goods).
 */
const val SHELL_A_WEEK = 0.05f
const val SHELL_EACH = 0.02f
const val BONE_EACH = 0.05f
const val ORNAMENT_DAYS = 2f
const val ORNAMENTS_PER_PERSON = 3f
const val ORNAMENT_GIFT = 0.03f
const val GRAVE_GOODS = 0.05f


/*
 * Trade: good knapping stone, salt and ochre are found only in some
 * country, and each is worth having. Stone tools of good stone (flint, chert, obsidian) cut and wear
 * better than those of whatever stone is at hand; meat and fish salted keep far longer and cure in
 * any weather; hides tanned with ochre last. Bands give what they have to spare of them to bands that
 * lack them, which binds both, and a band's rites may take it to where they are got.
 */
/**
 * What a person gets in a week at a source, kg: salt where a salt pan or dry lake bed lies along the way,
 * from the shore; ochre where it crops out. Not measured: a day's scraping or digging gives far more,
 * these are a week's worth carried off by a band passing by.
 */
const val SALT_A_WEEK = 1f
const val SEA_SALT_A_WEEK = 0.05f
const val OCHRE_A_WEEK = 0.3f
/** Ochre to tan a person's share of clothes, kg; how much slower tanned clothes wear. */
const val TAN_OCHRE = 0.2f
const val TANNED_WEAR = 0.5f
/**
 * Sodium a person needs a day, kg: the 23 mg a day the Yanomami pass, the least of any people measured (INTERSALT, BMJ
 * 1988; Mancilha-Carvalho et al., J Hum Hypertens 1989: 0.9 mmol a day, healthy and with low blood pressure, on a
 * mostly plant diet and no salt). How much less good their food does them wholly without it is [inflation.Tuning.saltLack].
 */
const val SODIUM_A_PERSON_DAY = 23e-6f
/** Sodium in a kg of meat or fish, kg: raw deer 51 mg/100 g (USDA SR "game meat, deer, raw"), freshwater fish about 50. */
const val SODIUM_PER_KG_MEAT = 0.51e-3f
/** Sodium's share of salt by weight (Na in NaCl). */
const val SALT_SODIUM = 0.393f
/** Salt to cure a kilo of meat or fish, kg (how much less salted meat and fish spoil: [inflation.Tuning.saltKeep]). */
const val SALT_PER_KG = 0.1f

/**
 * The materials bands give each other for their use (trade on); what a person uses of each in a
 * year, kg (not measured: a full kit's wear, a year's curing, a year's tanning); how many years'
 * worth a band keeps before it gives of it.
 */
val TRADED = listOf(Material.STONE, Material.SALT, Material.OCHRE)
val YEAR_NEED = enumFloats<Material> { when (it) { Material.STONE -> 1f; Material.SALT -> 4f; Material.OCHRE -> 0.2f; else -> 0f } }
const val KEEP_YEARS = 1f
/**
 * How much a gift that meets a band's whole want binds it to the giver (the giver half as much);
 * ties below which no gift is given. Not measured: as a feast.
 */
const val TIE_USEFUL = 0.3f
const val FEUD_GIFT = -0.2f
/** How a band's name for its gifts counts against food in hand when other bands weigh whose ways to take up; share of it that fades a year. */
const val RENOWN = 0.2f
const val RENOWN_FADE = 0.2f

/** The material that makes tool [t] well (with trade on): good stone for stone tools, ochre for tanned clothes; null for the others. */
fun fineMaterial(t: Tool): Material? = when { Material.STONE in t.recipe -> Material.STONE; t == Tool.CLOTHES -> Material.OCHRE; else -> null }
/** How much faster tool [t] wears for being made well only in share [fine] */
fun wearOfMake(t: Tool, fine: Float, tuning: Tuning): Float = when (fineMaterial(t)) {
    Material.STONE -> 1f + tuning.poorStoneWear * (1f - fine)
    Material.OCHRE -> 1f - TANNED_WEAR * fine
    else -> 1f
}
/** How well tool [t] serves for being made well only in share [fine] stone tools of poor stone, less. */
fun serveOfMake(t: Tool, fine: Float, tuning: Tuning): Float = if (fineMaterial(t) != Material.STONE) 1f else tuning.poorStoneEdge + (1f - tuning.poorStoneEdge) * fine

/**
 * A band's kept animals' week: they graze round the camp, breed in their season as they are fed
 * and as tame as they have grown, die, run off as wild as they still are, and are lost on long
 * moves; the band culls what it cannot keep (meat in its stores); and generation by generation they
 * grow tamer, since the docile stay and breed.
 */
private fun HexGroups.keepHerds(g: HexGroup, b: Band, date: Date) {
    val p = g.place
    val moved = max(0, g.path.size - 1)
    for (k in b.herd.indices) {
        var n = b.herd[k]
        if (n <= 0f) continue
        val s = fauna.kinds[k]
        val tame = b.herdTame[k]
        // Grazing round the camp.
        val need = land.spread(p, Kg(n * s.dailyIntakeKg * days))
        val d = s.diet; val total = (d.grass + d.browse).coerceAtLeast(1e-6f)
        val got = land.plants.consume(p, LifeForm.HERB, min(need * d.grass / total, KgM2(land.palatable[p] * land.plants.herb[p] * 0.1f))) +
            land.plants.eatBrowse(p, need * d.browse / total)
        val fed = if (need.v > 0f) min(1f, got / need) else 1f
        val inSeason = monthsFrom(s.birthMonth, date.month) < s.birthMonths
        val born = if (inSeason) n * 0.5f * s.youngPerFemale * fed * (0.3f + 0.7f * tame) /
            (s.birthMonths * Date.DAYS_PER_MONTH.toFloat() / days) else 0f
        val dying = (s.backgroundMortality + 0.5f * (1f - fed)) * years
        val running = HERD_RUN_OFF * (1f - tame) * b.lapse(Skill.TENDING) * years + HERD_MOVE_LOSS * (1f - tame) * moved * b.lapse(Skill.TENDING)
        val lost = n * min(0.9f, dying + running)
        // (The dead lie by the camp; those that run off or stray go wild among their kind.)
        val died = if (dying + running > 0f) lost * dying / (dying + running) else 0f
        val area = land.areaKm2[p]
        small.deposit(DeadPool.CARRION, p, Kg(died * s.massKg))
        fauna.adults[k][p] += (lost - died) / area
        n = max(0f, n + born - lost)
        // What it cannot keep it culls for meat (the rest of the carcass for the scavengers).
        val most = HERD_PER_PERSON * g.size * b.edge(Skill.TENDING)
        if (n > most) {
            val cull = n - most
            n = most
            // (Kept at what it feeds, as the week's meat: [Nourishment.MEAT].)
            val culled = cull * s.massKg * MEAT_SHARE
            addFood(g.stores, g.storeKgPer, Store.FRESH, culled * Nourishment.MEAT * g.species.needScale, culled)
            small.deposit(DeadPool.CARRION, p, Kg(cull * s.massKg * (1f - MEAT_SHARE)))
            stats.meeting(Met.KEPT_ANIMALS_CULLED)
        }
        if (n < 0.5f) { fauna.adults[k][p] += n / area; n = 0f }
        b.herd[k] = n
        b.herdTame[k] = tame + TAMING_RATE * b.edge(Skill.TENDING) * years * (1f - tame)
        if (b.knowsSkill[Skill.TENDING]) b.practise(Skill.TENDING, 0.5f * PRACTICE)
    }
}

/**
 * Herding: chance a young animal caught in the hunt is kept and raised instead of eaten (times its
 * kind's tameability), by a band at home; yearly share of the still-wild that run off, and share lost
 * for each hex a move takes them; most kept animals a person tends; yearly growth of tameness.
 */
const val CAPTURE = 0.5f
const val HERD_RUN_OFF = 0.5f
const val HERD_MOVE_LOSS = 0.05f
const val HERD_PER_PERSON = 2f
const val TAMING_RATE = 0.01f

/**
 * A band's fire: practised bands carry embers when they move and light it again at once; the
 * unpractised may fail at the hand drill for days, and learn as they go.
 */
private fun HexGroups.keepFire(g: HexGroup, b: Band) {
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
 * practised); a novice's daily chance to get a fire going, and how fast trying teaches it; what a fire
 * adds to the felt warmth, °C; how much more food raw meals take for the same energy.
 */
const val FIRE_FLOOR = 0.1f
const val FIRE_PRACTICE = 0.1f
const val FIRE_C = 3f
const val RAW_COST = 0.3f
/**
 * Exposure: the week's mean temperature below which people unprotected start to die (nights near
 * freezing); the degrees full clothes, a fire, a brush shelter and a house each keep off; the share of
 * people dying each week for each degree below.
 */
const val EXPOSURE_C = 10f
const val PROTECT_CLOTHES = 30f
const val PROTECT_FIRE = 12f
const val PROTECT_SHELTER = 5f
const val PROTECT_HOUSE = 10f
const val EXPOSURE = 0.05f
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
/** Flint a tool's stone takes when knapped as blades or microliths, against plain flakes. */
const val BLADE_STONE = 0.4f
/** How far a healer's or midwife's skill reaches into the deaths it can prevent (share of the gap to a start band's). */
const val HEALING_REACH = 0.5f

/** Stone and timber (kg a person) and person-days a person's share of a house takes, built [way]. */
fun houseNeeds(way: Skill) = when (way) {
    Skill.PIT_HOUSE -> Triple(HOUSE_STONE / 3f, HOUSE_WOOD, HOUSE_DAYS)
    Skill.MUDBRICK -> Triple(0f, HOUSE_WOOD / 2f, HOUSE_DAYS * 1.5f)
    else -> Triple(HOUSE_STONE, HOUSE_WOOD, HOUSE_DAYS)
}
const val GRUEL_GAIN = 0.2f
const val LEACH_SHARE = 0.5f
const val LEACH_DAYS_PER_KG = 0.3f
val KNOW_AT_HOME = PLANT_FLOOR + (1f - PLANT_FLOOR) * 0.9f

/**
 * Skills live in people. Each week the band's practised holders of each skill follow the work: the
 * young and the newcomers are taught the ways in use (taking time from the band's spare days, the
 * more the more intricate the way, the faster the better its teachers), people drop ways unused for
 * half a year, the dead take theirs with them, and a person can keep up only so many. A way held by
 * too few falls back toward what they can pass on (see [Skill.cap]).
 */
internal fun HexGroups.trainHolders(g: HexGroup, b: Band, time: BandTime) {
    val adults = g.adults.toFloat()
    if (b.holders.a[0] < 0f) for (s in Skill.entries) {
        b.holders[s] = if (b.knowsSkill[s]) adults * s.share else 0f
        b.lastUsed[s] = b.weeks
    }
    // The dead take their skills with them.
    if (b.lastAdults > 0 && g.adults < b.lastAdults) { val k = adults / b.lastAdults; for (i in Skill.entries) b.holders[i] *= k }
    b.lastAdults = g.adults
    for (s in Skill.entries) {
        if (!b.knowsSkill[s]) { b.holders[s] = 0f; continue }
        val used = b.weeks - b.lastUsed[s] <= DISUSE_YEARS * stepsPerYear
        val learners = learners(b, s, adults)
        val left = time.leftFor(Activity.TRAINING)
        if (learners > 0f && left > 0f) {
            val add = min(learners, left / (APPRENTICE_DAYS * s.complexity))
            b.holders[s] += add; time.spend(Activity.TRAINING, add * APPRENTICE_DAYS * s.complexity)
        } else if (!used) b.holders[s] = max(0f, b.holders[s] - SKILL_DROP * b.holders[s])
        b.holders[s] = min(b.holders[s], adults)
        // A way held by too few falls back toward what they can pass on.
        val cap = s.cap(b.holders[s])
        if (b.skills[s] > cap) b.skills[s] -= SHORTFALL * (b.skills[s] - cap)
    }
    // A person keeps up only so many ways: beyond that, the least used give way.
    val most = adults * SLOTS
    if (b.holders.sum() > most) for (s in Skill.entries.sortedBy { b.lastUsed[it] }) {
        val over = b.holders.sum() - most
        if (over <= 0f) break
        if (s.share >= 1f) continue
        b.holders[s] = max(0f, b.holders[s] - over)
    }
}

/**
 * The learners of way [s] [b] would bring on this week among its [adults], time allowing: as far as it lacks holders of a
 * way in use, as fast as they learn. (Learning from a holder is quick; working it out alone is slow.)
 */
private fun HexGroups.learners(b: Band, s: Skill, adults: Float): Float {
    if (!b.knowsSkill[s] || b.weeks - b.lastUsed[s] > DISUSE_YEARS * stepsPerYear) return 0f
    val pace = (if (b.holders[s] >= 1f) 1f else SELF_TAUGHT) * b.learning
    return minOf(adults * s.share - b.holders[s], adults * TRAINED_A_WEEK * pace).coerceAtLeast(0f)
}

/**
 * Holders: the share of a year unused before people drop a way; weekly share of its holders that drop it then;
 * pace of working a way out with no one to learn from; most of the adults taught a way in a week;
 * person-days to bring one learner on (times the way's intricacy); how many ways a person keeps
 * up; weekly share of the gap a way held by too few loses.
 */
const val DISUSE_YEARS = 0.5f
const val SKILL_DROP = 0.02f
const val SELF_TAUGHT = 0.1f
const val TRAINED_A_WEEK = 0.05f
const val APPRENTICE_DAYS = 10f
const val SLOTS = 14f
/**
 * Forgetting (Arthur and colleagues 1998): weekly share a hand skill loses in use and unused, and
 * knowledge unused (about a third a year); years unheld and unused before a way is lost altogether;
 * the level a way's groundwork must reach before it can be taken up; how much a difference in
 * customs puts bands off each other's ways (all of them differing: this share less), and how much
 * a band doing better is copied more readily.
 */
const val FORGET_HANDS = 0.001f
const val FORGET_KNOWLEDGE = 0.008f
const val LOST_KNOWLEDGE_YEARS = 5f
const val LOST_HANDS_YEARS = 25f
const val NEEDS_LEVEL = 0.3f
const val CONFORMITY = 0.8f
const val PRESTIGE = 0.5f
const val SHORTFALL = 0.02f
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
/** Firewood a fire through the night burns in a week, kg a person. */
const val NIGHT_WOOD = 3f
/**
 * Firewood the hearth burns for warmth and cooking, kg a person a day (about 1-2 in household fuel surveys of
 * people cooking on open fires, from memory); dead wood one person gathers in a day where trees or scrub are
 * thick, kg (head-loads of 20-30 kg, from memory).
 */
const val HEARTH_WOOD = 1f
const val FUEL_A_DAY = 25f
/**
 * Materials gathered, kg a person-day where the country has them (wood comes at [FUEL_A_DAY]). Estimates, not field
 * figures: good stone picked up where it crops out, salt scraped from a pan, ochre dug, reeds and grass cut for fibre,
 * resin scraped from trees and shells picked over on the shore for beads.
 */
const val STONE_A_DAY = 50f
/** Food cached, kg a person-day: pits dug, lined and covered (an estimate, not a field figure). */
const val CACHE_KG_A_DAY = 100f
const val SALT_A_DAY = 20f
const val OCHRE_A_DAY = 5f
const val FIBRE_A_DAY = 10f
const val RESIN_A_DAY = 0.5f
const val SHELL_A_DAY = 0.5f
/** Share of a full kit below which a band counts its kit worn, and mends before it lays food by as far as its way goes ([Band.mendFirst]; Olli's choice). */
const val MEND_BELOW = 0.5f
/**
 * Skin a kilo of small game yields, kg (a hare's or rabbit's pelt is about a tenth of its weight, from memory),
 * and the person-days scraping, stretching and drying a kilo of small pelts takes (an hour or two a pelt of
 * a few hundred grams, from memory): more work for the hide than a big beast's, and more seams to sew.
 */
const val SMALL_SKIN = 0.1f
const val SKIN_DAYS = 0.5f
/** Most of its days a band gives to firewood before it goes without (an estimate). */
const val FUEL_MOST = 0.5f
/**
 * Days of work a clean camp takes a person each day (about a quarter of an hour of a twelve-hour day), and days a
 * carer gives each sick or hurt person each day (half a day). Estimates: no time-use figures found.
 */
const val HYGIENE_DAYS = 0.02f
const val NURSE_DAYS = 0.5f

/** A band takes [n] wounded among its [adults]: from a fight, a hunt gone wrong, a beast, a fall. */
internal fun Band.wounded(n: Float, adults: Int) { if (adults > 0) hurt = min(1f, hurt + n / adults) }

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
