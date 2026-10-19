package inflation

/**
 * The settings a balancing run can change without rebuilding (t.NAME=value in the config file, see [Config]): one
 * instance for a run, built from the config at the edge ([config]) and handed down through the world's parts
 * (`HexLand.tuning`), so that a test can build another. The first group are multipliers on an existing parameter
 * (1 leaves it as it is); the rest are the parameters themselves.
 */
class Tuning(private val read: (String) -> String? = Config::string) {
    private fun f(name: String, default: Float) = read("t.$name")?.toFloat() ?: default
    private fun d(name: String, default: Double) = read("t.$name")?.toDouble() ?: default
    private fun i(name: String, default: Int) = read("t.$name")?.toInt() ?: default

    /** Outbreaks starting among people, against [inflation.ecology.HexFauna.OUTBREAK_START]. */
    val peopleOutbreak = f("peopleOutbreak", 1f)
    /** People's sickness from crowding, against [inflation.ecology.PEOPLE_SICK]. */
    val peopleSick = f("peopleSick", 1f)
    /** Time to pick, dig or reap a kilo of plant food, against [inflation.ecology.PLANT_HANDLING_DAYS_PER_KG] and its kin. */
    val plantHandling = f("plantHandling", 1f)
    /** Chance a much stronger hunter kills another kind's young, against [inflation.ecology.INTRAGUILD_KILL_YOUNG]. */
    val intraguildYoung = f("intraguildYoung", 1f)
    /** People's deaths of age and ordinary illness, of children, of thirst, and from camp fouling, against their present rates. */
    val humanOther = f("humanOther", 1f)
    val humanYoung = f("humanYoung", 1f)
    val humanThirst = f("humanThirst", 1f)
    val campFouling = f("campFouling", 1f)
    /** Marsh fever among people camped by marshes, against [inflation.ecology.MARSH_FEVER]. */
    val marshFever = f("marshFever", 1f)
    /** People's births, and the extra births of settled, well-stocked bands ([inflation.ecology.SETTLED_FERTILITY]), against their present rates. */
    val humanBirths = f("humanBirths", 1f)
    val settledFertility = f("settledFertility", 1f)
    /** The food cost of keeping warm, against [inflation.ecology.COLD_COST]. */
    val coldCost = f("coldCost", 1f)

    // Plants.
    /** Nourishment of dry or dormant forage against fresh growth (dry grass is about 45-55% digestible against 65-75% for young growth, from memory). */
    val dryValue = f("dryValue", 0.65f)
    /** Experiment knobs: the warmth at which plants start to grow, °C, and the span over which they reach full growth, °C. */
    val growFromC = f("growFrom", 2f)
    val growSpanC = f("growSpan", 13f)
    /**
     * How fast cured grass weathers where it stands, a year: the share of the standing dead that falls
     * into the litter (about 0.12-0.2 a month for grasses in the CENTURY model's fall rate, from memory),
     * and the rate at which what stands loses its protein toward straw's (crude protein of cured grass
     * falling from about 6% to 3% over a dry season of four to five months, from memory). 0: no weathering.
     */
    val hayWeathering = f("hayWeathering", 1.8f)
    /** Browsing, kg/km²/day, at which seedlings and saplings are mostly eaten (a few deer per km²). */
    val browseScale = f("browseScale", 3f)
    /**
     * Share of the herbs a place loses a step for each unit of trampling over it (see
     * [inflation.world.HexPlants.trample]). (Shrubs and trees lose only their seedlings, see [inflation.world.HexPlants.tread].)
     */
    val trampleHerb = f("trampleHerb", 0.005f)
    /** How fast the leaves of the shoots fall in the cold, a year: about two-thirds gone over a four-month winter, the twigs and buds left (0: they last as twigs do). */
    val leafFall = f("leafFall", 4f)
    /** The floor of the share of each place's plants something can eat ([inflation.world.HexLand.palatable]). */
    val palatableFloor = f("palatableFloor", 0.5f)
    /** Fires started per km² per year in hot, dry, fuel-rich ground. */
    val ignitionsPerKm2Year = f("ignitions", 0.01f)

    // Plant-eaters and the hunters that live as residents.
    /**
     * Residents: how many times their usual numbers can each hold a breeding range. At 1.5 roe deer level off at
     * about 20-25 a km² of their forest, the high end of what is seen.
     */
    val breedSpacing = f("breedSpacing", 1.5f)
    /**
     * Residents: weeks between their moves (up and down the slopes, and spilling to the hexes about), each move taking what
     * that many weeks' would ([inflation.ecology.HexFauna.spill]). Every week: residents follow the seasons themselves,
     * and a fortnight's wait is too coarse for that.
     */
    val residentMoveWeeks = i("residentMoveWeeks", 1).also { require(it >= 1) { "t.residentMoveWeeks is $it: residents move at most every week" } }
    /**
     * Residents: the share of a kind's animals at a place that take a step each week along the route their
     * seasonal memory leads to better ground ([inflation.ecology.SeasonalMemory.route]), as far as the crowding ahead
     * lets them. 1: all of them, too much; 0.3 looked right in the viewer but crowded them into disease over 10 years; 0.1 with half the gain trusted at the arrival ([residentRouteTrust]) for now.
     */
    val residentRouteShare = f("residentRouteShare", 0.1f).also { require(it in 0f..1f) { "t.residentRouteShare is $it: a share, 0..1" } }
    /**
     * Residents: how much of what the memory's route promises those on it count when they weigh where they come against
     * where they left ([inflation.ecology.HexFauna.flow]): 1, all of it, so they crowd in until the place is as much
     * worse than where they left as the route gains; 0, none, so they come only while it is no worse than where they left.
     */
    val residentRouteTrust = f("residentRouteTrust", 0.5f).also { require(it in 0f..1f) { "t.residentRouteTrust is $it: a share, 0..1" } }
    /**
     * Residents: the share of its usual numbers below which a kind's animals at a place are too thin to keep apart; they
     * gather into the neighbouring place where most of their thinly spread kind are ([inflation.ecology.HexFauna.gatherThin]).
     * 0: none. For the step's cost: fewer places hold each kind.
     */
    val residentFloor = f("residentFloor", 0.01f)
    /**
     * Protein in cured grass and in straw, against fresh growth (see [inflation.ecology.HexFauna.PROTEIN_NEED]): cured grass
     * and straw keep some of their energy but lose most of their protein (about 4% crude protein against 16% green, though grazers
     * pick the best of it and get about 6%; 3% of straw); and the young's deaths a year from want of protein, at none.
     */
    val proteinDryGrass = f("proteinDryGrass", 0.375f)
    val proteinStraw = f("proteinStraw", 0.2f)
    val youngProteinDeaths = f("youngProteinDeaths", 0.25f)
    /** Rodents' deaths a year from all causes but predators, cold, snow and hunger. */
    val rodentDeath = f("rodentDeath", 1.2f)

    // Groups' choices of where to go.
    /** How strongly a group follows its sense of better country, per reach moved along it. */
    val bearingPull = f("bearingPull", 0.5f)
    /** Country an animal knows around it, km; how fast the draw of a place fades with distance, km (see [inflation.ecology.HexBearings]). */
    val nearKm = d("nearKm", 60.0)
    val fadeKm = d("fadeKm", 50.0)
    /**
     * Packs tied to their dens: months from the births a pack with young hunts near its den; how near, km. Not
     * measured: wolves and hyenas keep to dens for two to three months, lions to their cubs' lairs about as long.
     */
    val denMonths = i("denMonths", 3)
    val denKm = f("denKm", 15f)

    // Bands' yearly rounds, their rites (see [inflation.ecology.ROUNDS]).
    /** How strongly a round draws a learned mover's choice, outside its network (per unit of the "on round" feature). */
    val roundNetPull = f("roundNetPull", 1.5f)
    /** How strongly a round draws the hand-written choice: per unit of progress toward its place this month (a full week's going toward it). */
    val roundPull = f("roundPull", 1.5f)
    /** Within this of its round's place this month, km, a band has arrived and goes about it as it likes (half a range across). */
    val roundArrivedKm = d("roundArrived", 50.0)
    /**
     * The widest hold on its rites a founding band can have: each band holds to the rites by which it keeps its round,
     * by lineage, as its traditions are. A well-fed band feels a hold under 1 hardly at all (the round's pull grows with
     * how much better the place promises than it fares now), so the range reaches well above it. Not measured otherwise:
     * wide on purpose, so that some lines are bound by their rites and others hardly, and the world sorts them.
     */
    val riteFounderMax = f("riteMax", 3f)

    // Bands' goods.
    /** A tool of poor stone: how much worse it serves (its edge against one of good stone), and how much faster it wears. Not measured. */
    val poorStoneEdge = f("poorStoneEdge", 0.75f)
    val poorStoneWear = f("poorStoneWear", 1f)
    /**
     * How much less good a person's food does them wholly without salt ([inflation.ecology.SALT_A_PERSON_WEEK]). Not measured:
     * salt hunger among farmers and plant gatherers is well known, its toll here a guess.
     */
    val saltLack = f("saltLack", 0.15f)
    /** How much less salted meat and fish spoil. */
    val saltKeep = f("saltKeep", 0.7f)

    companion object {
        /** The run's settings, as the config file gives them. */
        val config by lazy { Tuning() }

        /** The settings that differ from the defaults, for the check's header. */
        fun changed(): String = Config.set("t.")
    }
}
