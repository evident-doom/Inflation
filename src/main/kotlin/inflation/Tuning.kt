package inflation

/**
 * The settings a balancing run can change without rebuilding (t.NAME=value in the config file, see [Config]): one
 * instance for a run, built from the config at the edge ([config]) and handed down through the world's parts
 * (`HexLand.tuning`), so that a test can build another. The first group are multipliers on an existing parameter
 * (1 leaves it as it is); the rest are the parameters themselves.
 */
class Tuning(
    private val read: (String) -> String? = Config::tuning,
    /** The run's plain settings (not t.NAME) that change what the model does, as [Config] reads them. */
    private val setting: (Config.Key) -> String? = Config::string,
) {
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
    /** People's births against their present rate ([inflation.ecology.HUMAN_BIRTHS]). */
    val humanBirths = f("humanBirths", 1f)
    /** The number of bands people start with, against the usual (a world started afresh; 4, 16, 64: how much more the land could carry than its people start at). */
    val peopleStart = f("peopleStart", 1f)
    /** The size of the bands people start in, against the usual, the same people in fewer, larger bands (2: half as many bands, twice the size). */
    val peopleBand = f("peopleBand", 1f).also { require(it > 0f) { "t.peopleBand is $it: a band's size is a positive multiple" } }
    /**
     * The day of the year a world started afresh begins on, against the spring equinox ([inflation.world.Date.START_DAY], 90, when snow still
     * lies in the mountains); 0 is the winter solstice, 30 days to a month. A save carries its own date.
     */
    val startDay = i("startDay", inflation.world.Date.START_DAY).also { require(it in 0 until inflation.world.Date.DAYS_PER_YEAR) { "t.startDay is $it: a day of the year, 0-359" } }
    /**
     * A world begun once farming is known: the share of people's bands that start as villages on the best farm ground
     * (houses standing, grain stored and sown, the ways of living in houses known: [inflation.ecology.startVillages]); 0, none.
     */
    val startSettled = f("startSettled", 0f).also { require(it in 0f..1f) { "t.startSettled is $it: a share of the bands, 0-1" } }
    /**
     * A world begun once herding is known: the share of people's mobile bands that start with a tame herd, those whose camps best
     * feed one ([inflation.ecology.startHerders]); 0, none. (Villages started by [startSettled] start with a herd as well.)
     */
    val startHerders = f("startHerders", 0f).also { require(it in 0f..1f) { "t.startHerders is $it: a share of the bands, 0-1" } }
    /**
     * The share of people's founding bands that start with the planning kind's mind ([inflation.ecology.Mind.PLANS]): they
     * choose from the week's menu of activities, and their camp and days follow from what they hold; 0, none.
     */
    val planners = f("planners", 0f).also { require(it in 0f..1f) { "t.planners is $it: a share of the bands, 0-1" } }
    /** The food cost of keeping warm, against [inflation.ecology.COLD_COST]. */
    val coldCost = f("coldCost", 1f)

    // Plants.
    /** Nourishment of dry or dormant forage against fresh growth (dry grass is about 45-55% digestible against 65-75% for young growth, from memory). */
    val dryValue = f("dryValue", 0.65f)
    /**
     * Nourishment of cured grass leached to straw where it stands ([inflation.world.HexPlants.hayFresh] 0), against fresh
     * growth: weathering takes its energy as well as its protein. Zebu cattle on Sahelian pasture ate a diet of about 7.8-8.6 MJ
     * ME/kg OM early in the dry season and under 7.1 at its end, eating a fifth less of it (Schlecht, Sangaré & Becker 1999,
     * J Agric Sci 133:69): the cured forage worth some 0.83-0.91 of itself after a dry season, by when [hayWeathering] has
     * left about half of it fresh; so straw at about 0.44-0.54 (cattle pick the best of it, so the stand is worse than
     * what they ate).
     */
    val strawValue = f("strawValue", 0.5f).also { require(it <= dryValue) { "t.strawValue is $it: no more than t.dryValue ($dryValue)" } }
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

    // Bands' rites (see [rounds]).
    /** A band takes up no rite at a site within this of where it is, km (half a range across): a rite is somewhere it goes. */
    val roundArrivedKm = d("roundArrived", 50.0)
    /**
     * How strongly the place a band remembers doing best at this time of year draws it: per unit of
     * progress toward it ([inflation.ecology.SEASON_PULL] by default).
     */
    val seasonPull = f("seasonPull", inflation.ecology.SEASON_PULL)
    /**
     * The widest hold on its rites a founding band can have, by lineage, as its traditions are: a custom it keeps
     * and passes on (it no longer draws the band's moves: see model 4.89). Not measured: wide on purpose.
     */
    val riteFounderMax = f("riteMax", 3f)

    // Bands' goods.
    /** A tool of poor stone: how much worse it serves (its edge against one of good stone), and how much faster it wears. Not measured. */
    val poorStoneEdge = f("poorStoneEdge", 0.75f)
    val poorStoneWear = f("poorStoneWear", 1f)
    /**
     * How much less good a person's food does them wholly without sodium ([inflation.ecology.SODIUM_A_PERSON_DAY]). Not measured:
     * salt hunger among farmers and plant gatherers is well known, its toll here a guess.
     */
    val saltLack = f("saltLack", 0.15f)
    /** How much less salted meat and fish spoil. */
    val saltKeep = f("saltKeep", 0.7f)

    // The run's switches: plain keys, not tuning (t.NAME), that change what the model does.
    /** Bands keep rites, sites through the year, as customs ([inflation.ecology.riteWeek]); rounds=false leaves them out. */
    val rounds = setting(Config.Key.ROUNDS)?.toBooleanStrict() ?: true
    /** People live in the world; people=false leaves them out, for comparison runs. */
    val people = setting(Config.Key.PEOPLE)?.toBooleanStrict() ?: true
    /**
     * A long climate shock ([inflation.world.Weather.shock]): climateShock=startYear,years,degrees,rainShare (for example
     * 20,1200,-4,0.7 for something like the Younger Dryas); null when unset.
     */
    val climateShock: List<Double>? = setting(Config.Key.CLIMATE_SHOCK)?.split(",")?.map { it.trim().toDouble() }
    /** Each year's chance of an extra severe winter or drought year (weather.stressYears), so training worlds meet hard years often; 0 when unset. */
    val weatherStressYears = (setting(Config.Key.WEATHER_STRESS_YEARS)?.toFloat() ?: 0f).toDouble()

    companion object {
        /** The run's settings, as the config file gives them. */
        val config by lazy { Tuning() }

        /** The settings that differ from the defaults, for the check's header. */
        fun changed(): String = Config.set("t.")
    }
}
