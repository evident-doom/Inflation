package inflation.ecology

import inflation.enumFloats
import inflation.enumBools
import inflation.enumInts
import inflation.weightedMean
import inflation.world.Hex
import inflation.world.HexArray
import inflation.world.toHexArray
import inflation.world.Place
import inflation.world.get
import inflation.world.set

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
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.min
import inflation.world.Biome
import inflation.world.Weather
import java.util.stream.IntStream
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.sin

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
    /** The food it got in each month over the last years, as a share of its need before keeping warm ([foresee]). */
    val monthSupply = FloatArray(12) { 1f }
    /**
     * The share of its hands' days left in each month once its fire, shelters and food are held ([BandTime.vital]), a mean
     * over the years (below 0 till it has planned a week then): what its leanest month could give its herders ([keepHerds]).
     */
    val freeShare = FloatArray(12) { -1f }
    /** Share of its people sick with each [Illness] now, and share spared for having had it. */
    val sick = enumFloats<Illness>()
    val immune = enumFloats<Illness> { it.endemic }
    /**
     * Its wounded: the share of its adults nursing a wound, broken bone or sprain (they do little,
     * and a wound may fester and kill), and the share lamed for life by one that healed badly.
     */
    var hurt = 0f
    var lame = 0f
    /** For the training of its ways: what bound in the season its recorded draw covers (see [Binding]); null where none is recorded. */
    @Transient var binding: FloatArray? = null
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
    /** Of its dried meat and fish, how long on the whole it was dried for, against drying at the scaffold's 1 (the longer, the better it keeps: [driedKeep]). */
    var dryDepth = 1f
    /** Of the protein it ate last week, the share beyond what could feed it ([Nourishment.PROTEIN_MOST]): what it knows lean food is worth. */
    var leanLost = 0f
    /** Of its people's sodium need last week, the share that neither meat, salted meat nor its salt met ([saltFeeds]): what it knows it lacks. */
    var sodiumShort = 0f
    /** The nearest salt it knows of ([HexGroups.saltKnown]), or [Hex.NONE], as reckoned from hex [saltFrom] ([Hex.NONE]: not yet). */
    @Transient var saltAt = Hex.NONE
    @Transient var saltFrom = Hex.NONE
    /**
     * Its perseverance, 0..1 ("sisu"): how far its people keep working when cold and hunger are against them ([Sisu]),
     * at the price of the heat debt and the food it costs. Its lineage's, a little changed at each split.
     */
    var sisu = 0.5f
    /**
     * The share of its working time its people work this week for the cold, out in the weather ([Warmth.pace]) and at their
     * crafts at camp ([Warmth.campPace]), and for hunger ([Sisu.hungerPace]); set as the week starts.
     */
    @Transient var coldPace = 1f
    @Transient var campPace = 1f
    @Transient var hungerPace = 1f
    /** The share of its working time its people work for the sleep its night watch takes ([watchPace]); set as the week starts. */
    @Transient var sleepPace = 1f
    /** The share of its people's time at work [where] that is work this week ([Sisu], [watchPace]). */
    fun paceAt(where: Workplace) = when (where) {
        Workplace.OUT -> coldPace * hungerPace * sleepPace
        Workplace.CAMP -> campPace * hungerPace * sleepPace
        Workplace.NONE -> 1f
    }
    /** [paceAt] each [Activity]'s workplace, by its ordinal (for [BandTime]). */
    fun paces() = enumFloats<Activity> { paceAt(it.where) }
    /** Its name among the bands for the useful gifts it has given (fading a fifth a year; with trade on). */
    var renown = 0f
    var campWeeks = 0
    /** Weeks it has lately kept a camp before moving on, a running mean ([STAY_LEARN]): over how long a move pays back its walk. */
    var stayWeeks = STAY_WEEKS_START
    /**
     * What a whole house would have been worth to it where it was, a person a year, as it lived its weeks (a running mean over
     * about a year, [houseWorthWeek]): the lives it would have saved (cold, exposure, raids) and the food, kg of need (keeping
     * warm, stores kept from spoiling, less to raiders, less to mice taken off). (Nothing weighs a house by it yet: the census reports it.)
     */
    var houseLives = 0f
    var houseFood = 0f
    /** This week's share of them so far, not yet a person's: lives and kg ([houseWorthWeek]). */
    var houseLivesWeek = 0f
    var houseFoodWeek = 0f
    /** Its food over about a year by where it came from ([FoodFrom]), kg a person a day (a running mean, [HexGroups.recordFood]). */
    val foodFrom = enumFloats<FoodFrom>()
    /** Over about a year, the share of its weanlings it could wean on gruel ([gruel], [WEANING_SHARE]; a running mean, at its meals). */
    var weaningFood = 0f
    /**
     * Over about a year, how its fishing paid against the least paying food its gatherers took up, as fish's yield a day of
     * handling over itself and that least yield: above 1/2 fish was worth taking up, 0 no fish in reach ([Offers.pull]).
     */
    var fishPull = 0f
    /** This week, how fish from a whole weir across the river by its camp would have pulled against that least yield ([Offers.pullAt]): above 1/2 one would pay; 0 no river or no fish passing. */
    var weirPull = 0f
    var campPlace = Place.NONE
    /** Food it left in a cache at an old camp, kg, and where (-1 for none). */
    val cache = enumFloats<Store>()
    val cacheKg get() = cache.sum()
    /** The kg each kind in the cache weighs for each kg of its worth (see [addFood]). */
    val cacheKgPer = enumFloats<Store> { 1f }
    var cachePlace = Place.NONE
    /**
     * Its choices: how much of its foreseen want it aims to keep in store (the scaffold's 1; a learned policy sets its own).
     * Days of food it aims to keep: that share of [STORE_AIM_DAYS] against bad weeks and of the days its food fell short over
     * the last years' months ([leanDays]), to carry it through the lean months.
     */
    var storeShare = 1f
    /**
     * Days of its food it went short of over a year, month by month as it got food in each month over the last years
     * ([monthSupply]) against what keeping warm takes then ([warmthNeed]); [foreseenSurvival] reckons it.
     */
    var leanDays = 0f
    val storeAim get() = storeShare * storeAimAt1
    /** [storeAim] at the scaffold's [storeShare]. */
    val storeAimAt1 get() = STORE_AIM_DAYS + leanDays
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
    /**
     * Where it was last driven from its houses by raiders, and their people (main lineage): while it holds its grudge
     * against them a feud's worth ([keptFrom]), it does not take up houses there again and keeps off the place.
     */
    var drivenFrom = Hex.NONE
    var drivenBy = -1
    /** Whether the raiders who drove it from [h] keep it from taking up houses there: as long as its grudge against them is a feud. */
    fun keptFrom(h: Hex) = h == drivenFrom && drivenBy >= 0 && grudge[drivenBy] > FEUD
    /**
     * At home: how far its houses' sunken walls bring the ground's warmth in ([earthGain], over the houses that shelter it), and
     * the ground's warmth there, the year's mean air temperature, °C. They keep it warmer in the cold the deeper they are sunk.
     */
    var homeEarth = 0f
    var homeGroundC = 0f
    /** At home: how well its houses' walls keep beasts of prey out at night ([wallGuard], over the houses that shelter it), 0..1. */
    var homeWalls = 0f
    /** Where it knows other bands have their houses (band id to hex), and their descent (main lineage). */
    val knownHomes = HashMap<Int, Hex>()
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
    /**
     * Of each kind, whether its herd kept its increase to grow at its last week's keeping ([keepHerds]), and so fed the band
     * only its milk. (Empty in a band loaded from a save made before it; [keepHerds] sizes it to [herd].)
     */
    var herdGrew = BooleanArray(kinds)
    /**
     * Of each kind, the years left of the time its herd, begun from young caught in the hunt, is kept as pets: small animals
     * kept for their own sake, as companions, and not yet weighed as food ([keepHerds]) till the young it was begun with have
     * grown, or sooner for a kind of bad temper ([petYears]). 0 for a herd kept for food. (Empty in a band loaded from a save made before it;
     * [keepHerds] sizes it to [herd].)
     */
    var herdPet = FloatArray(kinds)
    /**
     * Of each kind, its kept young not yet grown, born in its herd or caught in the hunt: each weighs [HexFauna.YOUNG_WEIGHT]
     * of a grown one, eats about half as much, and grows up over its kind's years to maturity, as the residents' young do
     * ([keepHerds]). [herd] holds the grown. (Empty in a band loaded from a save made before it; [youngHerd] sizes it.)
     */
    var herdYoung = FloatArray(kinds)
    /** [herdYoung], sized to [herd]. */
    fun youngHerd(): FloatArray { if (herdYoung.size != herd.size) herdYoung = FloatArray(herd.size); return herdYoung }
    /** Whether it keeps any animals, grown or young. */
    fun keepsAnimals() = herd.any { it > 0f } || herdYoung.any { it > 0f }
    /** Of each kind, the heads beasts of prey took from its herd since it last kept it ([raidHerd], [keepHerds]). */
    var herdTaken = FloatArray(kinds)
    /**
     * Of the grain in its stores, what it reaped from sown fields and keeps to sow first, its seed corn (as its stores count
     * it; eaten last, so no more than its grain: [seedCornIn]), and the share of that from ears that hold their grain;
     * and of the rest, gathered from wild stands, the share from ears that hold their grain.
     */
    var seedCorn = 0f
    /** Where it sowed this year's crop ([Place.NONE] for nowhere) and the kg it sowed there: what it would lose by leaving (HexGroups.cropPull). */
    var sownPlace = Place.NONE
    var sownKg = 0f
    var seedDomestic = 0f
    var grainDomestic = 0f
    /** Its ways ([BandWay]), as [setWays] sets them each week from its ways' outputs: 1 each for the scaffold's way. Read through the names below. */
    val will = enumFloats<BandWay> { 1f }
    /** Its choices: how much of its stored grain it sows in autumn at its home (the scaffold: some, once it has houses). */
    val sowWill get() = will[BandWay.SOWING]
    /** How far a band with worn kit holds back from laying food by, to mend first (0 not at all, all of it from the scaffold's 1 up). */
    val mendFirst get() = will[BandWay.MENDING_FIRST].coerceIn(0f, 1f)
    /**
     * Share of a full kit below which it counts a tool worn ([worn]): [MEND_BELOW] up to the scaffold's 1, and above it more of the
     * same, mending first ever sooner (each unit of will above 1 the same share of the rest of the way to a full kit: [shareOfWill]).
     */
    val mendBelow get() = shareOfWill(max(1f, will[BandWay.MENDING_FIRST]), MEND_BELOW)
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
    /** The fire as it guards the camp at night: kept only as faithfully as the band chooses (a bigger fire above 1, guarding less for each log more). */
    val nightGuard get() = min(1f, fire) * moreOfWill(nightFire) * nightWood
    /** Share of the night's firewood it had this week. */
    var nightWood = 1f
    /** The hearth's firewood it fetched this week, a person a day in whole cooking fires ([fireOf]): its fire burns only as far as that goes. */
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
    /** Hours a night its people keep watch by turns: [WATCH_HOURS] at the scaffold's 1 (one awake at a time through the dark), as many more as its way asks. */
    val watchHours get() = WATCH_HOURS * max(0f, will[BandWay.NIGHT_WATCH])
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
    /** The share of its hands' days left spare, as a running mean over about a season ([SPARE_WEEKS]), once [spareSeen]: for the census only. */
    @Transient var spareMean = 0f
    @Transient var spareSeen = false
    /** How making clothes went this week ([makeTools]; null where it wanted none): for the census only. */
    @Transient var clothesMade: Tally.ClothesMade? = null
    /**
     * What its week's survival pass reckoned with ([survivalDays]), for the census only: the fire, the week's exposure odds,
     * the share of its hands' days left after the walk, and the share of the shelter it asked that it held first (below 0
     * where it asked none).
     */
    @Transient var planFire = 0f
    @Transient var planOdds = 0f
    @Transient var planLeft = 0f
    @Transient var planShelterFirst = -1f
    /** The shelters its week's survival pass would put up ([survivalDays]): what it builds to first ([putUpShelter]). Set as the week is planned. */
    @Transient var shelterAim = 0f
    /** The air and cover its survival pass reckoned the week's exposure with (for warmDiag only: [bookSqueeze]). */
    @Transient var planAir: Air? = null
    @Transient var planCover: Cover? = null
    @Transient var planSisu = 0f
    /** People-days its plan held for food and what foraging spent; held for shelter and what was left it when it built (warmDiag only). */
    @Transient var diagFoodHeld = 0f
    @Transient var diagFoodSpent = 0f
    @Transient var diagShelterHeld = 0f
    @Transient var diagShelterLeft = 0f
    /** What a whole house would have done for its week, part by part ([HouseWorth]), for the census only ([bookSqueeze]). */
    @Transient val houseWeek = enumFloats<HouseWorth>()
    /** The share of its herd its herders kept this week, as its week's plan gave them days ([herdDays]; 1 till it plans). */
    @Transient var herded = 1f
    /** What a day's foraging brings it, kg of its need, month by month: a mean over the years (below 0 till it has foraged then; [herdPays]). */
    @Transient val forageReturn = FloatArray(Date.MONTHS_PER_YEAR) { -1f }
    /** Person-days drying and skinning would have liked last week: what they ask of this week's plan (they follow the catch). */
    var dryAsk = 0f
    var skinAsk = 0f
    /**
     * Its choices: how much of its effort goes to each kind of food ([FOOD_TYPES]: big game, small
     * game, carrion, bones, fruit, roots, grass seed, small finds, fish), 1 the scaffold's, with no most. Big game's
     * sets how many of its hands go hunting ([HUNTERS_SHARE] of them at 1, see [huntersShare]).
     */
    val effort = FoodEfforts()
    /** Sets its ways to the scaffold's: every effort, its store aim, its tools' order and every [BandWay] at 1 (what a ways network's 0 gives). */
    fun scaffoldWays() { effort.fill(1f); storeShare = 1f; makeFirst.fill(1f); will.fill(1f) }
    /**
     * Hides, bone and sinew from [n] of the game it killed, what each kind yields (see [Species.products]), out of the
     * [left] kg of the carcasses not eaten; returns the kg taken.
     */
    fun skin(s: Species, n: Float, left: Float): Float {
        val p = s.products; val each = p.hide + p.bone + p.sinew
        if (n <= 0f || each <= 0f || left <= 0f) return 0f
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
    /**
     * What it has seen held about it, for its marks ([Tradition.mark]): of the bands it met lately, each as far as it understood
     * them, how many held each tradition ([heard]) of how many in all ([heardOf]); fading over [HEARD_YEARS] ([HexGroups.cultureWeek]).
     */
    val heard = enumFloats<Tradition>()
    var heardOf = 0f
    /** Counts band [o], understood as far as [u], among those it has met lately ([heard], [heardTotem]). */
    fun hear(o: Band, u: Float) {
        for (t in Tradition.entries) if (o.holds(t)) heard[t] += u
        heardOf += u
        val i = totemSlot(o.keptTotem)
        if (i < heardTotem.size) heardTotem[i] += u
        if (o.holds(Tradition.BURIAL)) heardBurial[o.burialWay] += u
    }
    /**
     * The share of the bands it met lately, itself counted as one, that hold [t] as [held] (for its marks: [conformist]).
     */
    fun heardShare(t: Tradition, held: Boolean): Float {
        val n = if (held) heard[t] else heardOf - heard[t]
        return (n + (if (holds(t) == held) 1f else 0f)) / (heardOf + 1f)
    }
    /**
     * The resident kind its people do not hunt or eat (its totem; [FaunaKind.NONE] for none), and whether it has one yet
     * (a band from a save made before totems takes its lineage's at its first week: see [ensureMarks]). Of the bands it met
     * lately, how many kept each kind as theirs, the last for none ([heardTotem], fading as [heard]). Years left that the bands
     * of its totem who meet it know it ate of it ([totemBroken]; see [keptTotem]).
     */
    var totem = FaunaKind.NONE
    /** Whether it has its totem and burial way yet ([ensureMarks]). */
    var marksChosen = false
    var heardTotem = FloatArray(kinds + 1)
    var totemBroken = 0f
    /** Its totem as the bands it meets see it: none while it is known to have eaten of it. */
    val keptTotem get() = if (totemBroken > 0f) FaunaKind.NONE else totem
    /** [k]'s place in [heardTotem]: none the last. */
    fun totemSlot(k: FaunaKind) = if (k.none) heardTotem.size - 1 else k.i
    /** The share of the bands it met lately, itself counted as one, that keep [k] as their totem (for [conformist]). */
    fun heardTotemShare(k: FaunaKind): Float {
        val i = totemSlot(k)
        return ((if (i < heardTotem.size) heardTotem[i] else 0f) + (if (totem == k) 1f else 0f)) / (heardOf + 1f)
    }
    /**
     * Where it lays its dead, if it buries them ([Tradition.BURIAL]); of the burying bands it met lately, how many laid them
     * each way ([heardBurial], fading as [heard]); and the person-days its burials still ask of it ([burialOwed]: digging, and
     * carrying the dead to its ancestors, done as its customs' time allows: [GroupWorld.keepCustoms]).
     */
    var burialWay = BurialWay.AT_CAMP
    val heardBurial = enumFloats<BurialWay>()
    var burialOwed = 0f
    /** The share of the burying bands it met lately, itself counted as one, that lay their dead as [w] (for [conformist]). */
    fun heardBurialShare(w: BurialWay) = (heardBurial[w] + (if (burialWay == w) 1f else 0f)) / (heardBurial.sum() + 1f)
    /**
     * Gives a band without its marks yet ([marksChosen]) its lineage's totem (among [kinds] resident kinds) and burial way,
     * and room to hear of every kind.
     */
    fun ensureMarks(kinds: Int) {
        if (heardTotem.size != kinds + 1) heardTotem = FloatArray(kinds + 1)
        if (marksChosen) return
        totem = totemAt(mainLineage(), kinds); burialWay = burialWayAt(mainLineage()); marksChosen = true
    }
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

    fun knows(h: Hex) = h.i / 64 < known.size && (known[h.i / 64] ushr (h.i % 64)) and 1L == 1L
    fun learn(h: Hex, size: Int) { if (known.size * 64 < size) known = known.copyOf((size + 63) / 64); known[h.i / 64] = known[h.i / 64] or (1L shl (h.i % 64)) }

    /**
     * Its rites: for each month, the place it holds sacred then (-1: none) — the graves of its dead, where
     * bands gather, far landmarks it walked to or was told of (see [riteWeek]). Handed down whole to bands
     * that split off, taken up from bands met, now and then let go: customs that mark a band's line. They
     * do not draw its moves: a trip to a rite's site will be an action the band chooses.
     */
    val rite = IntArray(12) { -1 }
    /** How strongly its people hold to their rites (0: not at all; see [inflation.Tuning.riteFounderMax]). */
    var riteStrength = 0f
    /**
     * The person-days its rites still ask of it ([riteOwed]: [RITE_DAYS] an adult for each rite held, done as its customs'
     * time allows after its burials, [GroupWorld.keepCustoms], in the rite's month only), and the month, counted from the world's start, it last held
     * one ([riteHeld]), so that a rite is owed once in its month.
     */
    var riteOwed = 0f
    var riteHeld = -1L
    /** The site of the rite its days are owed to ([riteOwed]), held once they are given; -1: none under way. */
    var riteDue = -1
    /** The month, counted from the world's start, it last held a rite to the end (-1: never). */
    var riteKept = -1L
    /**
     * What the rite under way asked of it at its month's start ([riteAsked], person-days), and the days of that month it
     * gave to neither its needs nor its customs ([riteSpare]): what it could have given the rite and gave to other work.
     */
    var riteAsked = 0f
    var riteSpare = 0f
    /**
     * Its belief is its rites as a whole: the years it has kept them ([beliefYears], its weight: the older, the more strain
     * it bears), and the strain of rites it held back from ([strain]: up to one a rite cut short, as far as the days it
     * did not give went to other work than its needs; easing in weeks it eats its fill): one of the quarrels that split a
     * band, the more the nearer it is to what the belief bears ([strainBorne]; see [HexGroups.maxSize]).
     */
    var beliefYears = 0f
    var strain = 0f
    /**
     * The omen it remembers ([GroupWorld.omen]): the place, month and year of a salient event and how many times what it
     * is used to it was ([omenWeight], fading); -1: none. And its usual deaths a week a head ([deathsUsual], over about a year).
     */
    var omenPlace = -1
    var omenMonth = 0
    var omenYear = 0L
    var omenWeight = 0f
    var deathsUsual = 0f
    /** How it chooses ([Mind]): today's way, or the planning kind's. Handed down whole at a split; a band never joins one of the other mind. */
    var mind = Mind.NETS
    /** The strain its belief bears before part of the band breaks from it. */
    fun strainBorne() = STRAIN_SPLIT * (1f + beliefYears / BELIEF_AGE_YEARS)
    /** Gives up its belief: its rites, its hold on them and what they asked of it (the part of a band that broke away). */
    fun dropBelief() {
        rite.fill(-1); riteStrength = 0f; beliefYears = 0f; strain = 0f; riteOwed = 0f; riteAsked = 0f; riteSpare = 0f; riteDue = -1; omenPlace = -1; omenWeight = 0f
    }

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
        o.houseHex = houseHex; o.house = house; o.drivenFrom = drivenFrom; o.drivenBy = drivenBy; o.homeEarth = homeEarth; o.homeWalls = homeWalls; o.homeGroundC = homeGroundC; o.palisade = palisade; herdTame.copyInto(o.herdTame); herd.copyInto(o.herd); herdGrew.copyInto(o.herdGrew); herdPet.copyInto(o.herdPet)
        o.houseLives = houseLives; o.houseFood = houseFood; foodFrom.copyInto(o.foodFrom); o.fishPull = fishPull; monthSupply.copyInto(o.monthSupply); freeShare.copyInto(o.freeShare); o.leanDays = leanDays
        o.seedDomestic = seedDomestic; o.grainDomestic = grainDomestic; o.seedCorn = seedCorn * share; seedCorn -= o.seedCorn; o.sinceRaided = sinceRaided; o.sisu = sisu
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
        o.salted = salted; o.dryDepth = dryDepth
        o.renown = renown
        rite.copyInto(o.rite); o.riteStrength = riteStrength; o.riteHeld = riteHeld; o.riteKept = riteKept; o.beliefYears = beliefYears; o.strain = strain
        o.omenPlace = omenPlace; o.omenMonth = omenMonth; o.omenYear = omenYear; o.omenWeight = omenWeight; o.deathsUsual = deathsUsual; o.mind = mind
        traditions.copyInto(o.traditions); heard.copyInto(o.heard); o.heardOf = heardOf
        o.totem = totem; o.marksChosen = marksChosen; o.heardTotem = heardTotem.copyOf(); o.totemBroken = totemBroken
        o.burialWay = burialWay; heardBurial.copyInto(o.heardBurial); words.copyInto(o.words); lineage.copyInto(o.lineage); grudge.copyInto(o.grudge)
        o.inbred = inbred; o.campWeeks = campWeeks; o.campPlace = campPlace; o.known = known.copyOf(); kept.copyInto(o.kept)
        copied(o)
        for (i in o.kept.indices) { o.kept[i] *= share; kept[i] -= o.kept[i] }
        // (A band from a save made when there were fewer resident kinds keeps fewer: see [HexFauna.kinds].)
        for (i in 0 until min(herd.size, o.herd.size)) { o.herd[i] *= share; herd[i] -= o.herd[i] }
        run { val y = youngHerd(); val oy = o.youngHerd(); for (i in 0 until min(y.size, oy.size)) { oy[i] = y[i] * share; y[i] -= oy[i] } }
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
            if (ka + kb > 0f) a.fine[i] = weightedMean(a.fine[i], ka, b.fine[i], kb)
            a.kit[i] += w * (b.kit[i] - a.kit[i])
        }
        if (dried + bDried > 0f) a.salted = weightedMean(a.salted, dried, b.salted, bDried)
        if (dried + bDried > 0f) a.dryDepth = weightedMean(a.dryDepth, dried, b.dryDepth, bDried)
        // (Seed corn blended by the seed corn each held, the rest of the grain by the rest.)
        val corn = min(a.seedCorn, seed); val bCorn = min(b.seedCorn, bSeed)
        if (corn + bCorn > 0f) a.seedDomestic = weightedMean(a.seedDomestic, corn, b.seedDomestic, bCorn)
        val wild = seed - corn; val bWild = bSeed - bCorn
        if (wild + bWild > 0f) a.grainDomestic = weightedMean(a.grainDomestic, wild, b.grainDomestic, bWild)
        a.seedCorn = corn + bCorn
        for (i in Illness.entries) { a.sick[i] += w * (b.sick[i] - a.sick[i]); a.immune[i] += w * (b.immune[i] - a.immune[i]) }
        a.hurt += w * (b.hurt - a.hurt); a.lame += w * (b.lame - a.lame)
        a.ornaments += b.ornaments; a.exotic += b.exotic
        val ay = a.youngHerd(); val by = b.youngHerd()
        for (i in 0 until min(a.herd.size, b.herd.size)) {
            val n = a.herd[i] + b.herd[i]
            val heads = n + ay[i] + by[i]
            if (heads > 0f) a.herdTame[i] = ((a.herd[i] + ay[i]) * a.herdTame[i] + (b.herd[i] + by[i]) * b.herdTame[i]) / heads
            a.herd[i] = n; ay[i] += by[i]
            // (Pets while either band's were: the joined herd is weighed as food once the younger has grown.)
            if (i < a.herdPet.size && i < b.herdPet.size) a.herdPet[i] = max(a.herdPet[i], b.herdPet[i])
        }
        a.renown = max(a.renown, b.renown)
        for (i in Tool.entries) a.knows[i] = a.knows[i] || b.knows[i]
        for (i in a.lineage.indices) a.lineage[i] += w * (b.lineage[i] - a.lineage[i])
        for (i in a.grudge.indices) a.grudge[i] = max(a.grudge[i], b.grudge[i])
        for (t in Tradition.entries) a.heard[t] += b.heard[t]
        a.heardOf += b.heardOf
        for (i in 0 until min(a.heardTotem.size, b.heardTotem.size)) a.heardTotem[i] += b.heardTotem[i]
        for (way in BurialWay.entries) a.heardBurial[way] += b.heardBurial[way]
        a.burialOwed += b.burialOwed
        a.riteOwed += b.riteOwed; a.riteAsked += b.riteAsked; a.riteSpare += b.riteSpare
        for ((k, v) in b.ties) if (k != id) a.ties[k] = max(a.tie(k), v)
        a.ties.remove(bId)
        for ((k, v) in b.favours) if (k != id) a.favours.merge(k, v, Float::plus)
        a.favours.remove(bId)
        for (i in Material.entries) a.goods[i] += b.goods[i]
        if (a.known.size < b.known.size) a.known = a.known.copyOf(b.known.size)
        for (i in b.known.indices) a.known[i] = a.known[i] or b.known[i]
        for (i in b.kept.indices) a.kept[i] += b.kept[i]
        a.inbred += w * (b.inbred - a.inbred); a.elders += b.elders
        a.sisu += w * (b.sisu - a.sisu)
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
    /** Its fire this week: the share of the week it was lit, times the wood it burnt a person a day in whole cooking fires ([HEARTH_WOOD]). Cooks at 1; a bigger fire only warms more. */
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
    /** Whether it can make food of bitter mast, acorns leached in running water or a sand basin: if it knows how and has water by it. */
    fun leachesMast(water: Boolean) = knowsSkill[Skill.LEACHING] && water
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
     * without a fish spear, traps or nets, a snarer without cords or a bow for birds, a cook without
     * tight baskets learns little of the craft (a little, at best, by watching and making do). (Fish are
     * speared and trapped in baskets and weirs as well as netted; shellfish want no tools at all.)
     */
    fun toolsFor(s: Skill) = when (s) {
        Skill.HUNT, Skill.STALK -> max(TOOLLESS, max(has(Tool.SPEAR), has(Tool.BOW)))
        Skill.FISH -> max(TOOLLESS, max(has(Tool.NET), max(has(Tool.SPEAR), has(Tool.BASKET))))
        Skill.SMALL_GAME -> max(TOOLLESS, max(has(Tool.NET), has(Tool.BOW)))
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
    /** Whether any tool it knows is worn below [below] of a full kit (as far as its way of mending first goes: [mendBelow]). */
    fun worn(below: Float = mendBelow) = Tool.entries.any { knows[it] && kit[it] < below }

    /** How its weapons, hunting skill and dogs do against [prey], against an ordinary armed, practised band without dogs (1). */
    fun huntEdge(prey: Species, tuning: Tuning) =
        skill(Skill.HUNT) * edge(Skill.STALK) * (WEAPONLESS + (1f - WEAPONLESS) * has(Tool.SPEAR) * serveOfMake(Tool.SPEAR, fine[Tool.SPEAR], tuning) +
            BOW_GAIN * has(Tool.BOW) * serveOfMake(Tool.BOW, fine[Tool.BOW], tuning) * min(1f, prey.speedKmh / 70f)) *
            (1f + DOG_HUNT * dogShare * max(0f, 1f - prey.massKg / DOG_PREY_KG))

    /** Its dogs against its hunters (one to three a full pack): they track, bay and hold small game, and bark at night. */
    var dogShare = 0f
    /** How far its dogs were fed this week, 0..1 (1 with none; for the census). */
    @Transient var dogFed = 1f

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
    /** The insulation of the clothes its people wear by day, clo: a whole kit at [CLOTHES_CLO], as well sewn as its hands sew. */
    fun clothesClo(clothes: Float = has(Tool.CLOTHES)) = CLOTHES_CLO * clothes * skill(Skill.SEWING)
    /** How far houses shelter it where it is: its home's, at home. */
    fun homeShelter() = if (atHome) house else 0f
    /** How much warmer than [t] °C outside its houses keep: by the wood their hearths burn ([fire], [FIRE_HOUSE_KG_C]), and the ground's warmth through sunken walls. */
    fun houseWarmC(t: Float, fire: Float = 1f) = fire * HEARTH_WOOD / FIRE_HOUSE_KG_C + homeEarth * max(0f, homeGroundC - t)
    /** What it has against the cold at [t] °C with a fire of [fire] and houses sheltering it as far as [home] ([BodyHeat]), in a kit of [clothes]. */
    fun cover(t: Float, fire: Float, home: Float, shelter: Float = this.shelter, clothes: Float = has(Tool.CLOTHES)) =
        Cover(clothesClo(clothes), fire, shelter, home, houseWarmC(t, fire))
    /**
     * Its week of [days] in [air] with a fire of [fire] and houses sheltering it as far as [home], by its people's body heat
     * ([BodyHeat]): the food keeping warm takes, and the deaths of exposure. Fur clothes, robes at night, a fire, a shelter
     * with its bedding or a house keep people alive in deep cold; the naked die of exposure in a night a few degrees above
     * freezing, wet and windy.
     */
    fun warmth(air: Air, fire: Float, home: Float, days: Int, shelter: Float = this.shelter, clothes: Float = has(Tool.CLOTHES)) =
        BodyHeat.week(air, cover(air.meanC, fire, home, shelter, clothes), days, sisu)

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
            totem = totemAt(l, kinds); burialWay = burialWayAt(l); marksChosen = true
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
