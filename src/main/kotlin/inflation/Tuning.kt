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
    /**
     * Time to pick or dig a kilo of plant food (not grain or seed: [inflation.ecology.reapDaysPerKg]), against [inflation.ecology.PLANT_HANDLING_DAYS_PER_KG] and its kin. 1.6:
     * at 1, bands' gatherers got some 2,070 kcal an hour, the in-patch rate, against 460-910 for !Kung mongongo with travel and
     * processing (Blurton Jones, Hawkes & Draper 1994) and 729 across foragers (Kraft et al. 2021); 1.6 brings them to some
     * 1,430 at a cost of 5-6% of people, and 2.5 (some 1,010) cost 26-38% (gather screen, 2026-11-04). An approximation
     * until it is known where the food beyond the field rates goes.
     */
    val plantHandling = f("plantHandling", 1.6f)
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
    /**
     * The number of cheetahs a world started afresh begins with, against the usual (the density the long runs settled at,
     * which was far below the field's: a start of few is limited by chance alone). A start-time setting only.
     */
    val cheetahStart = f("cheetahStart", 1f).also { require(it > 0f) { "t.cheetahStart is $it: a positive multiple" } }
    /** The size of the bands people start in, against the usual, the same people in fewer, larger bands (2: half as many bands, twice the size). */
    val peopleBand = f("peopleBand", 1f).also { require(it > 0f) { "t.peopleBand is $it: a band's size is a positive multiple" } }
    /**
     * The day of the year a world started afresh begins on, against the spring equinox ([START_DAY], 90, when snow still
     * lies in the mountains); 0 is the winter solstice, 30 days to a month. A save carries its own date.
     */
    val startDay = i("startDay", START_DAY).also { require(it in 0 until DAYS_PER_YEAR) { "t.startDay is $it: a day of the year, 0-359" } }
    /**
     * Days a step of the slow parts covers (the residents, small life, water life and the groups), while the weather, the
     * water and the plants keep their tick ([inflation.world.WorldConfig.daysPerStep], 6) through it: a whole number of
     * ticks that divide the 360-day year (a step may cross a month's end: month-keyed parts take the month it starts in);
     * 0 for one tick. The variable-step plan, design/2026-11-07-variable-step-plan.md in the project files.
     */
    val stepDays = i("stepDays", 0).also { require(it == 0 || (it > 0 && DAYS_PER_YEAR % it == 0)) { "t.stepDays is $it: 0, or days that divide a 360-day year" } }
    /**
     * How far a group's net shift over a step of several ticks reaches, as the ticks raised to this power: the reach of one
     * tick's shift ([inflation.ecology.SHIFT_DAYS]) times ticks^stepReach. 1, as far as that many ticks' shifts end to end (a
     * march held to one bearing); 0.5, as far as that many shifts on bearings that do not hold (a wander); 0, one tick's shift
     * for the whole step. No effect at one tick a step.
     */
    /**
     * How far a beast's party hunts past its want to lay fat on, as its gut and fat room allow (1), or not at all (0: the fat
     * it laid on came only of its last kill's overshoot, which a longer step all but loses). [inflation.ecology.ForageWeek].
     */
    /** How many times a group's party may look again at what is left in a step, still hungry ([inflation.ecology.FORAGE_PASSES], 3). */
    val foragePasses = i("foragePasses", 3).also { require(it >= 1) { "t.foragePasses is $it: 1 or more" } }
    /**
     * Days over which residents too thin to hold on at a place (under [inflation.ecology.HexFauna.EXTINCT] a km²) fade there, to
     * 1/e; 0: set to none once a step, as before (which takes more the shorter the step).
     */
    val thinFadeDays = f("thinFadeDays", 30f).also { require(it >= 0f) { "t.thinFadeDays is $it: 0 or more" } }
    val packFatAim = f("packFatAim", 0f).also { require(it in 0f..1f) { "t.packFatAim is $it: 0-1" } }
    val stepReach = f("stepReach", 0.5f).also { require(it in 0f..1f) { "t.stepReach is $it: 0-1" } }
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
    /** Diagnostic: the planning kind's slots over its own, for the slot-by-slot A/B (as text; [inflation.ecology.Mind] reads and checks it). */
    val plannerSlots: String? = read("t.plannerSlots")
    /** Diagnostic: the nets' bands' slots over their own, as [plannerSlots] (as text; [inflation.ecology.Mind] reads and checks it). */
    val foragerSlots: String? = read("t.foragerSlots")
    /** Scouting parties ([inflation.ecology.tendParties]): bands with food to spare send some of their people out to scout and come back; off unless set (under test). */
    val parties = read("t.parties")?.toBooleanStrict() ?: false
    /** Worn ground ([inflation.ecology.WornGround]): what bands take near where they live makes the next kilo and load of wood dearer; off unless set (under test). */
    val wornGround = read("t.wornGround")?.toBooleanStrict() ?: false
    /**
     * Bands hold their home hex ([inflation.ecology.staysHome]): a band chooses no place, it camps on its hex's low ground; a
     * band tied to no spot shifts its camp within the hex when the ground would foul; it meets its neighbours, and its
     * dealings with them wear the ways; a raid is fought at the target's home without the band walking there; a band that
     * splits off takes a free hex beside its parent's; a world starts with one band to a hex, the best country first
     * (docs/design/band-movement-removal.md). Off unless set (under test).
     */
    val bandsStay = read("t.bandsStay")?.toBooleanStrict() ?: false
    /**
     * With [bandsStay]: how many times a year two bands in hexes side by side meet ([inflation.ecology.neighboursMeet]: friends
     * and kin, news of the country, gifts, words, customs), half as often for each hex further apart, out to five. Weekly
     * between neighbours, a week of the model's calendar (6 days: 60 a year; Olli, 8 Nov 2026).
     */
    val neighbourMeetings = f("neighbourMeetings", 60f).also { require(it >= 0f) { "t.neighbourMeetings is $it: 0 or more" } }
    /**
     * Wolves' and lions' dispersal pool weighs every hex it leaves from at once against the room about as the step found
     * it, each hex then taking as many as it has room for, and those still dispersing drift a tick at a time toward better
     * hunting, so the nomads follow the game ([inflation.ecology.disperse]; docs/design/pack-performance.md, step 2). Off
     * unless set (under test).
     */
    val poolDrifts = read("t.poolDrifts")?.toBooleanStrict() ?: false
    /**
     * The beasts of prey keep out of people's way: a pressure per hunting kind by place, of the people within a day's
     * walk and of its kind killed by people lately, fading over months. Resident hunters weigh it in a place's appeal and
     * leave pressed ground as from want, dispersers find less room there, and packs pressed past what they bear grow
     * restless ([inflation.ecology.pressure]; docs/design/pack-performance.md, step 3). Off unless set (under test).
     */
    val huntersPressed = read("t.huntersPressed")?.toBooleanStrict() ?: false
    /**
     * Packs hold their ground ([inflation.ecology.holdsGround]): a pack on its home hex that is not restless weighs no place,
     * it stays, and hunts and marks its territory's footprint, the hexes within its range of home weighted by nearness, at
     * any step length ([inflation.ecology.footprint]; docs/design/pack-performance.md, step 4). Off unless set (under test).
     */
    val packsStay = read("t.packsStay")?.toBooleanStrict() ?: false
    /**
     * With [packsStay], for test: the strategy every pack holding its ground settles its contacts by ([inflation.ecology.engage]),
     * its stance on each part of its ground, as `lair:DEFEND,core:GIVE_WAY_TO_STRONGER,fringe:YIELD,outside:YIELD` (stances
     * DEFEND, GIVE_WAY_TO_STRONGER, YIELD, AMBUSH, HIDE_AND_SCOUT, ATTACK; a part left out keeps GIVE_WAY_TO_STRONGER). Unset:
     * the animals' rule everywhere, as at any meeting.
     */
    val packStrategy: String? = read("t.packStrategy")
    /**
     * With [packsStay], for test: the strategy every band that holds its home hex settles its meetings by, as [packStrategy]
     * (its lair its camp's hex, its core the hexes about). Two bands that are friends both ways greet whatever their
     * strategies, but at a raid. Unset: the people's own rules, as at any meeting.
     */
    val bandStrategy: String? = read("t.bandStrategy")
    /**
     * With [packsStay]: a band takes its stance toward each band it comes on afresh, by how it regards it and what it
     * knows of its strength ([inflation.ecology.bandStance]): friends and those it has no quarrel with greet; the wary hold
     * their camp, give way to the stronger about it and keep their distance beyond; those it feuds with it falls on if it
     * is the stronger, else holds its camp and falls back watching. What it saw of the others it remembers
     * ([inflation.ecology.Band.seenStrength]). A [bandStrategy] set for test overrides it. Off unless set (under test).
     */
    val bandStances = read("t.bandStances")?.toBooleanStrict() ?: false
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
     * Residents: weeks between their moves (up and down the slopes, and spilling to the hexes about): the plant-eaters
     * flow a step each move, the hunters shift and spill what that many weeks' would ([inflation.ecology.HexFauna.spill]).
     * Three: moving every week (in up to nine passes) cost a quarter of the week's time. Against that, moving every three
     * weeks in one step costs a seventh as much; the residents follow the seasons up and down the slopes about half as far
     * (red deer 32 m against 58 m), and after three years elk, red and roe deer are 5-15% more and wild boar 2-5% fewer.
     */
    val residentMoveWeeks = i("residentMoveWeeks", 3).also { require(it >= 1) { "t.residentMoveWeeks is $it: residents move at most every week" } }
    /**
     * Residents: the share of its usual numbers below which a kind's animals at a place are too thin to keep apart; they
     * gather into the neighbouring place where most of their thinly spread kind are ([inflation.ecology.HexFauna.gatherThin]).
     * 0: none. For the step's cost: fewer places hold each kind.
     */
    val residentFloor = f("residentFloor", 0.01f)
    /**
     * Wolves and lions settled with roaming packs (docs/design/roaming-packs.md, [inflation.ecology.Catalogue.roamingResidents]):
     * their settled's usual numbers, in packs of their usual size a hex; the times those numbers past which they fight their own
     * kind ([inflation.ecology.HexFauna.STRIFE_CROWD] for other resident hunters); and the share of their young growing up that
     * leave wherever they are born ([inflation.ecology.HexFauna.leaveShare]). For calibrating them against packs.
     */
    val roamerUsual = f("roamerUsual", 1f)
    val roamerStrifeCrowd = f("roamerStrifeCrowd", 2f)
    val roamerLeaveBase = f("roamerLeaveBase", 0.5f)
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

    // The hurricane test (see [inflation.ecology.HexHurricane]); off by default.
    /** How many storms roam the land (0 = none). */
    val hurricanes = i("hurricane", 0)
    /** How far a storm goes a week, hexes (a part of a hex is carried over). */
    val hurricaneSpeed = f("hurricaneSpeed", 1f)
    /** How many hexes around a storm's own it strikes. */
    val hurricaneRadius = i("hurricaneRadius", 0)
    /** 1: a group struck dies; 0: the storm kills no one (to see where groups stand without them dying). */
    val hurricaneKills = i("hurricaneKills", 1)
    /** 1: groups are told which hexes the storms will strike; 0: they are not (the storms kill as before). */
    val hurricaneSeen = i("hurricaneSeen", 1)
    /** How many weeks ahead the storms' paths are worked out and told. */
    val hurricaneWarn = i("hurricaneWarn", 6)

    // The recruiter test (see [inflation.ecology.HexGroups.recruit]); off by default; meant to be used with the hurricanes.
    /** How many recruiters roam the land (0 = none). */
    val recruiters = i("recruiters", 0)
    /** How far a recruiter goes a week, hexes (0: it stays where it was set down). */
    val recruiterSpeed = f("recruiterSpeed", 0f)
    /** The share of its grown people a band gains at a recruiter, at least two, once a year. */
    val recruiterShare = f("recruiterShare", 0.15f)
    /** Days of its need the band's food stores are filled up to at a recruiter (0: not filled). */
    val recruiterFood = f("recruiterFood", 0f)
    /** How strongly a recruiter draws a band that may take recruits, in the appointment's pull (1 = as full as an appointment's). */
    val recruiterPull = f("recruiterPull", 2f)
    /** 1: bands feel the draw of the recruiters; 0: they do not (the recruiters give as before). */
    val recruiterSeen = i("recruiterSeen", 1)

    // Bands' rites (see [rounds]).
    /** A band takes up no rite at a site within this of where it is, km (half a range across): a rite is somewhere it goes. */
    val roundArrivedKm = d("roundArrived", 50.0)
    /**
     * How strongly the place a band remembers doing best at this time of year draws it: per unit of
     * progress toward it (1 by default).
     */
    val seasonPull = f("seasonPull", 1f)
    /**
     * The widest hold on its rites a founding band can have, by lineage, as its traditions are: a custom it keeps
     * and passes on (it does not draw the band's moves). Not measured: wide on purpose.
     */
    val riteFounderMax = f("riteMax", 3f)

    // Bands' goods.
    /** A tool of poor stone: how much worse it serves (its edge against one of good stone), and how much faster it wears. Not measured. */
    val poorStoneEdge = f("poorStoneEdge", 0.75f)
    val poorStoneWear = f("poorStoneWear", 1f)
    /**
     * How much slower people work wholly without sodium ([inflation.ecology.SODIUM_A_PERSON_DAY], [inflation.ecology.saltPace]).
     * Not measured: salt hunger among farmers and plant gatherers is well known, its toll here a guess.
     */
    val saltLack = f("saltLack", 0.15f)
    /**
     * Sodium in a litre of sweat, kg ([inflation.ecology.BodyHeat.sweatLitres]): 0.46 g, 20 mmol/L. Outdoor workers used to
     * the heat on a Western diet sweat 45 mmol/L, 64 before the summer (Bates and Miller 2008, J Occup Med Toxicol 3:4);
     * acclimatisation and a low-salt diet lower it further, how far for people who never had salt is not measured: 20 is a
     * guess between. Peoples of hot countries ate some 2-6 g of salt a day (Ladell 1957; Orr and Gilks 1931, as cited in
     * Institute of Medicine 1993, Nutritional Needs in Hot Environments, ch. 17), 0.8-2.4 g of sodium; at 20 mmol/L a day's
     * work and rest at a mean of 28 °C sheds about 0.9 g. 0 is the old rule (the bare 23 mg a day).
     */
    val sweatSodium = f("sweatSodium", 0.46e-3f)
    /**
     * How many fewer young plant-eaters, wild and kept, bear wholly without sodium ([inflation.ecology.saltBreeding]). Not
     * measured: sodium-short livestock grow and breed worse and chew bones and soil; its toll here a guess. 0 is the old rule.
     */
    val grazerSaltLack = f("grazerSaltLack", 0.3f)
    /**
     * How far inland the sea's spray keeps plant forage in sodium, km: it falls off as e^(-km / this)
     * ([inflation.ecology.forageSodium]). A guess fitted to ungulates' salt licks lying mostly beyond 500 km of the coast
     * (Maro and Dudley 2022). No place on a 1000 x 2000 km test world is then short; about 9% on an 8000 x 2000 one.
     */
    val marineKm = f("marineKm", 300f)
    /**
     * Rain a year beyond which it leaches sodium from soil and forage, mm: at twice it half is left
     * ([inflation.ecology.leached]). A guess: dry and Mediterranean country keeps its salts, wet uplands and rainforest lose
     * them. 0 leaches none (the old rule).
     */
    val leachMm = f("leachMm", 1000f)
    /**
     * How far bands foresee the work they will lose for the salt they will lack, once the salt they hold runs out
     * ([inflation.ecology.foreseenSaltPace]): in their year ahead, and so in what training judges them by. 1 in full, 0 not
     * at all (the old rule).
     */
    val saltForeseen = f("saltForeseen", 1f)
    /**
     * Salt by its season and its making (1; 0 the old rule): a pan's crust is scraped only in a month that dries more than it
     * rains, and sea salt is made in such a month at [inflation.ecology.SEA_SALT_A_DAY], not picked up all year at the pan's rate.
     */
    val saltSeasons = f("saltSeasons", 1f)
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
