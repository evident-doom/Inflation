package inflation.ecology
import inflation.world.HexMap
import java.util.Properties


object Catalogue {
    /**
     * Share of hoofed newborns dying of causes not modelled (stillbirth, desertion, birth defects, failing to suckle,
     * falls). Where nothing hunts them, about a fifth of temperate ungulate young die as newborns, and predators
     * cause some 70% of the losses where they hunt (Linnell et al. 1995); on Rum, free of predators, 18% of red deer
     * calves died before five months, most in their first week, chiefly deserted, stillborn or malformed (Guinness,
     * Clutton-Brock and Albon 1978). Hunger, cold, crowding sickness, eagles and hunters come on top, each modelled.
     */
    const val HOOFED_YOUNG_LOST = 0.2f

    /**
     * Where each kind settled in a long run, animals per 1000 km² of land (settled-start.properties);
     * null for a kind with no settled value.
     */
    fun settledPer1000Km2(s: Species): Float? = SETTLED[s.name]

    private val SETTLED: Map<String, Float> by lazy {
        val props = Properties()
        Catalogue::class.java.getResourceAsStream("/settled-start.properties")?.use { props.load(it) }
        props.stringPropertyNames().associateWith { props.getProperty(it).trim().toFloat() }
    }

    /** Mountain gazelle: the staple prey of Natufian hunters. Mixed grazer and browser of open land. */
    val GAZELLE = Species(
        id = 0, name = "gazelle", massKg = 20f,
        fearOfPeople = 0.8f, boldShare = 0.03f,
        diet = Diet(grass = 0.6f, browse = 0.4f),
        groupSize = 12, maxGroupSize = 40,
        dailyTravelKm = 6f, maxDailyTravelKm = 30f, senseKm = 4f,
        daysWithoutWater = 5, waterFromFood = 0.8f,
        birthMonth = 4, birthMonths = 2, youngPerFemale = 1f, yearsToMaturity = 1f,
        backgroundMortality = 0.08f, firstYearLost = HOOFED_YOUNG_LOST, snowLimitMm = 40f,
        products = Products(meat = 9f, fat = 0.5f, hide = 1.2f, bone = 2.5f, sinew = 0.2f, hornOrAntler = 0.3f),
        comfortC = 0f..36f, 
        speedKmh = 80f,
        foresight = 0.6f,
        domestication = floatArrayOf(0.6f, 0.6f, 0.2f, 0.5f, 0.05f, 0.2f), // panics and leaps fences
        habitat = Habitat(cover = -1f, wet = -0.5f),
        activity = floatArrayOf(0.25f, 0.4f, 0.25f, 0.1f),
        swimKm = 1f,
    )

    /** Roe deer: small, solitary or in families, browsing woodland edges and thickets. */
    val ROE_DEER = Species(
        id = 1, name = "roe deer", massKg = 25f,
        diet = Diet(grass = 0.2f, browse = 0.7f, fruit = 0.1f),
        groupSize = 3, maxGroupSize = 6,
        dailyTravelKm = 3f, maxDailyTravelKm = 20f, senseKm = 1f,
        daysWithoutWater = 4, waterFromFood = 0.9f,
        birthMonth = 5, birthMonths = 1, youngPerFemale = 1.6f, yearsToMaturity = 2f,
        // (Snow 50 cm deep, some 125 mm of water, bounds it in the north: Danilkin 1996, Mysterud and Østbye 2006.)
        backgroundMortality = 0.07f, firstYearLost = HOOFED_YOUNG_LOST, snowLimitMm = 125f,
        products = Products(meat = 12f, fat = 0.5f, hide = 1.5f, bone = 3f, sinew = 0.2f, hornOrAntler = 0.3f),
        comfortC = -8f..29f, winterCoat = true,
        speedKmh = 70f,
        domestication = floatArrayOf(0.4f, 0.6f, 0.1f, 0.3f, 0.1f, 0.1f),
        habitat = Habitat(cover = 0.6f),
        activity = floatArrayOf(0.35f, 0.1f, 0.35f, 0.2f),
        shelters = setOf(Niches.Shelter.THICKET),
        swimKm = 4f,
    )

    /** Persian fallow deer: herds in open woodland and parkland; common in Levantine Epipalaeolithic sites. */
    val FALLOW_DEER = Species(
        id = 2, name = "fallow deer", massKg = 65f,
        diet = Diet(grass = 0.5f, browse = 0.4f, fruit = 0.1f),
        groupSize = 6, maxGroupSize = 25,
        dailyTravelKm = 4f, maxDailyTravelKm = 25f, senseKm = 2f,
        daysWithoutWater = 3, waterFromFood = 0.6f,
        birthMonth = 5, birthMonths = 1, youngPerFemale = 0.9f, yearsToMaturity = 2f,
        backgroundMortality = 0.1f, firstYearLost = HOOFED_YOUNG_LOST, snowLimitMm = 150f,
        products = Products(meat = 32f, fat = 2f, hide = 4f, bone = 7f, sinew = 0.5f, hornOrAntler = 2f),
        comfortC = -5f..35f, winterCoat = true,
        speedKmh = 65f,
        domestication = floatArrayOf(0.5f, 0.5f, 0.4f, 0.4f, 0.3f, 0.4f), // kept in parks, never domesticated
        habitat = Habitat(cover = 0.3f),
        defence = 0.1f,
        activity = floatArrayOf(0.35f, 0.1f, 0.35f, 0.2f),
        shelters = setOf(Niches.Shelter.THICKET),
        swimKm = 6f,
    )

    /** Red deer: woodland and hills, grazing and browsing, herds split by sex outside the rut. */
    val RED_DEER = Species(
        id = 3, name = "red deer", massKg = 150f,
        diet = Diet(grass = 0.5f, browse = 0.5f),
        groupSize = 8, maxGroupSize = 30,
        dailyTravelKm = 6f, maxDailyTravelKm = 40f, senseKm = 3f,
        daysWithoutWater = 3, waterFromFood = 0.5f,
        birthMonth = 5, birthMonths = 1, youngPerFemale = 0.8f, yearsToMaturity = 3f,
        backgroundMortality = 0.08f, firstYearLost = HOOFED_YOUNG_LOST, snowLimitMm = 180f,
        products = Products(meat = 75f, fat = 6f, hide = 9f, bone = 18f, sinew = 1f, hornOrAntler = 5f),
        comfortC = -12f..29f, winterCoat = true,
        speedKmh = 60f,
        domestication = floatArrayOf(0.5f, 0.4f, 0.3f, 0.3f, 0.2f, 0.3f),
        habitat = Habitat(cover = 0.5f, rugged = 0.2f),
        defence = 0.25f,
        activity = floatArrayOf(0.35f, 0.1f, 0.35f, 0.2f),
        shelters = setOf(Niches.Shelter.THICKET, Niches.Shelter.WATER),
        swimKm = 10f,
    )

    /** Nubian ibex: cliffs and steep rock of the hot dry hills and gorges, where nothing can follow it. */
    val IBEX = Species(
        id = 4, name = "ibex", massKg = 45f,
        fearOfPeople = 0.75f, boldShare = 0.04f,
        diet = Diet(grass = 0.6f, browse = 0.4f),
        groupSize = 8, maxGroupSize = 25,
        dailyTravelKm = 4f, maxDailyTravelKm = 20f, senseKm = 3f,
        daysWithoutWater = 3, waterFromFood = 0.6f,
        birthMonth = 4, birthMonths = 1, youngPerFemale = 0.9f, yearsToMaturity = 2.5f,
        backgroundMortality = 0.1f, firstYearLost = HOOFED_YOUNG_LOST, snowLimitMm = 80f,
        products = Products(meat = 21f, fat = 1.5f, hide = 3f, bone = 5.5f, sinew = 0.4f, hornOrAntler = 4f),
        comfortC = 0f..38f, winterCoat = true,
        speedKmh = 55f,
        foresight = 0.5f,
        domestication = floatArrayOf(0.7f, 0.5f, 0.4f, 0.4f, 0.4f, 0.6f), // its cousin became the goat
        habitat = Habitat(cover = -0.3f, rugged = 1f),
        defence = 0.1f,
        activity = floatArrayOf(0.3f, 0.4f, 0.2f, 0.1f),
        shelters = setOf(Niches.Shelter.CRAG),
    )

    /** Wild boar: omnivore of forest, thicket and marsh; roots, acorns, bulbs; large litters. */
    val BOAR = Species(
        id = 5, name = "wild boar", massKg = 90f,
        fearOfPeople = 0.6f, boldShare = 0.1f,
        diet = Diet(grass = 0.15f, browse = 0.1f, roots = 0.45f, fruit = 0.2f, smallPrey = 0.05f, carrion = 0.05f),
        groupSize = 6, maxGroupSize = 20,
        dailyTravelKm = 5f, maxDailyTravelKm = 20f, senseKm = 1.5f,
        daysWithoutWater = 2, waterFromFood = 0.4f,
        birthMonth = 4, birthMonths = 2, youngPerFemale = 5f, yearsToMaturity = 1.5f,
        // Piglets and adults die mostly by hunting, then hard winters; few of other causes (Keuling et al. 2013,
        // Jędrzejewski et al. 1992), so the unmodelled adult rate is that of other hoofed kinds of its size.
        backgroundMortality = 0.08f, firstYearLost = HOOFED_YOUNG_LOST,
        snowLimitMm = 110f,
        products = Products(meat = 45f, fat = 8f, hide = 5f, bone = 9f, sinew = 0.5f, hornOrAntler = 0.5f),
        comfortC = -8f..34f, winterCoat = true,
        speedKmh = 45f,
        foresight = 0.5f,
        domestication = floatArrayOf(0.9f, 0.9f, 0.8f, 0.4f, 0.6f, 0.6f), // became the pig
        habitat = Habitat(cover = 0.8f, wet = 0.6f),
        defence = 0.6f,
        fibreDigestion = 0.5f,
        activity = floatArrayOf(0.2f, 0.05f, 0.25f, 0.5f),
        shelters = setOf(Niches.Shelter.THICKET, Niches.Shelter.WATER),
        swimKm = 10f,
    )

    /** Aurochs: huge wild cattle of wet lowlands, floodplains and open woodland; formidable when cornered. */
    val AUROCHS = Species(
        id = 6, name = "aurochs", massKg = 750f,
        fearOfPeople = 0.6f, boldShare = 0.05f,
        diet = Diet(grass = 0.8f, browse = 0.2f),
        groupSize = 15, maxGroupSize = 40,
        dailyTravelKm = 8f, maxDailyTravelKm = 30f, senseKm = 2f,
        daysWithoutWater = 2, waterFromFood = 0.2f,
        birthMonth = 4, birthMonths = 2, youngPerFemale = 0.7f, yearsToMaturity = 3f,
        backgroundMortality = 0.06f, firstYearLost = HOOFED_YOUNG_LOST, snowLimitMm = 200f,
        products = Products(meat = 370f, fat = 40f, hide = 40f, bone = 80f, sinew = 4f, hornOrAntler = 6f),
        comfortC = -12f..33f, winterCoat = true,
        speedKmh = 50f,
        foresight = 0.6f,
        domestication = floatArrayOf(0.8f, 0.5f, 0.7f, 0.2f, 0.4f, 0.9f),
        milked = true, // became cattle
        habitat = Habitat(cover = 0.1f, rugged = -0.5f, wet = 0.8f),
        defence = 0.9f,
        activity = floatArrayOf(0.25f, 0.35f, 0.2f, 0.2f),
        swimKm = 8f,
    )

    /** Onager (Asiatic wild ass): fast grazer of steppe and semi-desert, needs to drink often. */
    val ONAGER = Species(
        id = 7, name = "onager", massKg = 230f,
        diet = Diet(grass = 0.9f, browse = 0.1f),
        groupSize = 8, maxGroupSize = 30,
        dailyTravelKm = 15f, maxDailyTravelKm = 50f, senseKm = 4f,
        // Wild asses of the desert steppe go three or four days between drinks, and get water from green grass.
        daysWithoutWater = 4, waterFromFood = 0.4f,
        birthMonth = 5, birthMonths = 2, youngPerFemale = 0.7f, yearsToMaturity = 2f,
        backgroundMortality = 0.05f, firstYearLost = HOOFED_YOUNG_LOST, snowLimitMm = 150f,
        products = Products(meat = 110f, fat = 8f, hide = 15f, bone = 25f, sinew = 2f),
        comfortC = -8f..38f, winterCoat = true,
        speedKmh = 70f,
        foresight = 0.7f,
        domestication = floatArrayOf(0.7f, 0.4f, 0.3f, 0.1f, 0.2f, 0.4f), // too wild to tame; the donkey came from Africa
        habitat = Habitat(cover = -0.8f, rugged = -0.3f, wet = -0.1f), // shuns marsh, not the water's edge
        defence = 0.4f,
        hindgut = true,
        activity = floatArrayOf(0.25f, 0.35f, 0.2f, 0.2f),
    )

    /**
     * Hippopotamus: lived in the Levant's coastal rivers into historical times. Spends the day in
     * water and grazes the banks at night; adults fear nothing, calves fall to lions and crocodiles.
     */
    val HIPPO = Species(
        id = 8, name = "hippo", massKg = 1400f,
        fearOfPeople = 0.3f, boldShare = 0.05f,
        diet = Diet(grass = 0.95f, browse = 0.05f),
        groupSize = 10, maxGroupSize = 30,
        dailyTravelKm = 4f, maxDailyTravelKm = 10f, senseKm = 1f,
        daysWithoutWater = 1, waterFromFood = 0f,
        birthMonth = 6, birthMonths = 4, youngPerFemale = 0.4f, yearsToMaturity = 8f,
        backgroundMortality = 0.04f, firstYearLost = HOOFED_YOUNG_LOST, snowLimitMm = 20f,
        products = Products(meat = 600f, fat = 80f, hide = 100f, bone = 150f, sinew = 5f, hornOrAntler = 3f),
        comfortC = 10f..36f, 
        speedKmh = 30f,
        foresight = 0.3f,
        domestication = floatArrayOf(0.6f, 0.2f, 0.2f, 0.0f, 0.1f, 0.3f),
        habitat = Habitat(cover = -0.2f, rugged = -1f, wet = 1f),
        defence = 1f,
        maxWaterKm = 1.5f,
        activity = floatArrayOf(0.1f, 0f, 0.2f, 0.7f),
        shelters = setOf(Niches.Shelter.WATER),
        swimKm = 15f,
    )

    /**
     * Syrian brown bear: an omnivore of wooded hills, living mostly on fruit, nuts, roots and green
     * plants, with insects, carrion and the odd fawn or calf it kills. Fattens in autumn and sleeps out the winter.
     * (Ungulates, killed and scavenged, a fifth of its diet: in central Sweden, without sheep, 14-30% of brown bears'
     * digestible energy, berries 44-46%, insects 14-22%, forbs and graminoids 12-18%; where free-ranging sheep were to be
     * had, in Norway, ungulates, mostly sheep, 65-87%; Dahle et al. 1998, Wildlife Biology 4: 147, from the abstract. A
     * northern record for a southern bear, which likely eats less meat: an estimate.)
     */
    val BEAR = Species(
        id = 9, name = "brown bear", massKg = 150f,
        fearOfPeople = 0.6f, boldShare = 0.05f,
        diet = Diet(grass = 0.1f, browse = 0.1f, roots = 0.2f, fruit = 0.3f, meat = 0.1f, smallPrey = 0.1f, carrion = 0.1f),
        fibreDigestion = 0.5f, // a bear digests cellulose poorly (captive grizzlies on high-fibre feed); set as the boar
        groupSize = 2, maxGroupSize = 4,
        dailyTravelKm = 8f, maxDailyTravelKm = 30f, senseKm = 4f,
        daysWithoutWater = 4, waterFromFood = 0.6f,
        birthMonth = 0, birthMonths = 1, youngPerFemale = 0.8f, yearsToMaturity = 5f,
        // (Cubs lost a year: 0.04 to 0.35, mostly to infanticide by incoming males; Swenson et al. 2001.)
        backgroundMortality = 0.05f, firstYearLost = 0.2f, snowLimitMm = 600f,
        products = Products(meat = 70f, fat = 30f, hide = 12f, bone = 15f, sinew = 1f, fur = true),
        comfortC = -15f..28f, winterCoat = true,
        speedKmh = 50f,
        foresight = 0.5f,
        domestication = floatArrayOf(0.9f, 0.2f, 0.2f, 0.1f, 0.2f, 0.1f),
        habitat = Habitat(cover = 0.5f, rugged = 0.4f),
        defence = 0.8f,
        hibernates = true,
        startKm2PerAnimal = 60f,
        activity = floatArrayOf(0.3f, 0.2f, 0.3f, 0.2f),
        shelters = setOf(Niches.Shelter.DEN, Niches.Shelter.THICKET),
        swimKm = 10f,
        // a fifth of the lion's weight: bears took 358 of 100,000 person-years, lions 57; no Natufian or Hiwi bear kills known (review/2026-10-22-predators-megafauna.md)
        takesPeople = 0.08f,
    )

    /**
     * Grey wolf of the southern, Arabian-Levantine type: smaller than northern wolves and living in
     * small packs. Eats much small prey and carrion.
     */
    val WOLF = Species(
        id = 18, name = "wolf", massKg = 22f,
        fearOfPeople = 0.5f, boldShare = 0.15f,
        diet = Diet(meat = 0.6f, smallPrey = 0.25f, carrion = 0.15f),
        groupSize = 3, maxGroupSize = 8,
        dailyTravelKm = 15f, maxDailyTravelKm = 50f, senseKm = 8f,
        daysWithoutWater = 3, waterFromFood = 0.5f,
        birthMonth = 4, birthMonths = 1, youngPerFemale = 2.5f, yearsToMaturity = 2f,
        backgroundMortality = 0.15f, youngMortality = 0.4f, snowLimitMm = 400f,
        products = Products(meat = 9f, fat = 0.4f, hide = 1.5f, bone = 2.2f, sinew = 0.2f, fur = true),
        comfortC = -20f..30f, winterCoat = true,
        speedKmh = 60f,
        foresight = 0.5f,
        domestication = floatArrayOf(0.7f, 0.5f, 0.6f, 0.4f, 0.6f, 0.9f), // became the dog
        packLitter = 4,
        stalking = 0.3f,
        // (Packs take mostly red deer hinds and calves, some 100 kg, at 25-40 kg a wolf (Nowak et al. 2005), and in
        // Yellowstone elk of five or six times their weight, bison seldom: ranges inferred, not stated by the sources.)
        preyPreferredUpTo = 3f, preyUpTo = 6f,
        startKm2PerAnimal = 50f,
        followsPeople = true,
        activity = floatArrayOf(0.3f, 0.1f, 0.3f, 0.3f),
        shelters = setOf(Niches.Shelter.DEN),
        swimKm = 4f,
        takesPeople = 0.3f,
    )

    /**
     * Lion: still in the Levant at the time, in prides that take the largest prey, aurochs
     * included, and drive other predators off their kills.
     */
    val LION = Species(
        id = 19, name = "lion", massKg = 150f,
        fearOfPeople = 0.6f, boldShare = 0.05f,
        diet = Diet(meat = 0.9f, carrion = 0.1f),
        groupSize = 5, maxGroupSize = 12,
        dailyTravelKm = 12f, maxDailyTravelKm = 30f, senseKm = 8f,
        daysWithoutWater = 4, waterFromFood = 0.6f,
        birthMonth = 5, birthMonths = 1, youngPerFemale = 1.5f, yearsToMaturity = 3f,
        backgroundMortality = 0.1f, youngMortality = 0.4f, snowLimitMm = 200f,
        products = Products(meat = 60f, fat = 5f, hide = 8f, bone = 18f, sinew = 1f, fur = true),
        comfortC = 0f..38f, 
        speedKmh = 60f,
        domestication = floatArrayOf(0.1f, 0.4f, 0.2f, 0.0f, 0.3f, 0.5f),
        habitat = Habitat(cover = -0.2f, rugged = -0.3f),
        stalking = 0.7f,
        ferocity = 1.2f,
        // (Prides prefer prey of 190-550 kg, most of all some 350 (Hayward and Kerley 2005), and take up to some
        // 630 (Clements et al. 2014).)
        preyPreferredUpTo = 3.7f, preyUpTo = 4.2f,
        startKm2PerAnimal = 50f,
        activity = floatArrayOf(0.25f, 0.05f, 0.3f, 0.4f),
        takesPeople = 1f,
    )

    /** Leopard: a solitary stalker of broken and wooded ground; caches kills away from bigger rivals. */
    val LEOPARD = Species(
        id = 20, name = "leopard", massKg = 45f,
        fearOfPeople = 0.7f, boldShare = 0.03f,
        diet = Diet(meat = 0.6f, smallPrey = 0.35f, carrion = 0.05f),
        groupSize = 2, maxGroupSize = 4,
        // (Solitary: grown leopards live and hunt alone, a mother with her cubs aside; pairs only to mate. From memory.)
        foragingParty = 1,
        dailyTravelKm = 6f, maxDailyTravelKm = 20f, senseKm = 4f,
        daysWithoutWater = 6, waterFromFood = 0.8f,
        birthMonth = 6, birthMonths = 1, youngPerFemale = 1.5f, yearsToMaturity = 2.5f,
        backgroundMortality = 0.07f, youngMortality = 0.4f, snowLimitMm = 300f,
        products = Products(meat = 18f, fat = 1f, hide = 3f, bone = 5f, sinew = 0.3f, fur = true),
        comfortC = -8f..35f, winterCoat = true,
        // (Its winter fur grows to some 70 mm on the back, against 20-25 in summer: Heptner and Sludskij 1972.)
        speedKmh = 58f,
        domestication = floatArrayOf(0.1f, 0.4f, 0.1f, 0.0f, 0.2f, 0.0f),
        habitat = Habitat(cover = 0.7f, rugged = 0.6f),
        stalking = 1f,
        ferocity = 1.1f,
        // (Leopards prefer prey of 10-40 kg (Hayward et al. 2006) and avoid any over 45 (Clements et al. 2014).)
        preyPreferredUpTo = 0.9f, preyUpTo = 1f,
        startKm2PerAnimal = 50f,
        activity = floatArrayOf(0.2f, 0.05f, 0.25f, 0.5f),
        shelters = setOf(Niches.Shelter.CRAG, Niches.Shelter.DEN, Niches.Shelter.THICKET, Niches.Shelter.TREE),
        takesPeople = 1.2f,
    )

    /**
     * Eurasian lynx: a solitary stalker of forest and broken ground, the roe deer's chief hunter (a
     * roe or a chamois a week or so), with hares and birds between; a mother keeps her kits with
     * her through their first winter.
     */
    val LYNX = Species(
        id = 25, name = "lynx", massKg = 20f,
        fearOfPeople = 0.9f, boldShare = 0.01f,
        diet = Diet(meat = 0.75f, smallPrey = 0.22f, carrion = 0.03f),
        groupSize = 1, maxGroupSize = 4,
        dailyTravelKm = 8f, maxDailyTravelKm = 25f, senseKm = 3f,
        daysWithoutWater = 6, waterFromFood = 0.8f,
        // (Kept as a resident: a litter of two or three a year.)
        birthMonth = 5, birthMonths = 1, youngPerFemale = 2.5f, yearsToMaturity = 2f,
        backgroundMortality = 0.1f, youngMortality = 0.5f, snowLimitMm = 900f,
        products = Products(meat = 8f, fat = 0.3f, hide = 1.5f, bone = 2f, sinew = 0.1f, fur = true),
        comfortC = -30f..28f, winterCoat = true,
        speedKmh = 50f,
        domestication = floatArrayOf(0.1f, 0.3f, 0.1f, 0.0f, 0.2f, 0.0f),
        habitat = Habitat(cover = 0.8f, rugged = 0.4f),
        // (It lies up in thickets and among rocks and climbs out of a wolf's reach.)
        shelters = setOf(Niches.Shelter.CRAG, Niches.Shelter.THICKET, Niches.Shelter.TREE),
        stalking = 1f,
        // (A lynx holds its own against animals its size and kills foxes and the young of others; from memory, not checked.)
        ferocity = 0.9f,
        // (A lynx fights back with teeth and claws: an estimate between the onager's kicks (0.4) and the boar's tusks (0.6).)
        defence = 0.5f,
        startKm2PerAnimal = 150f,
    )

    /** Striped hyena: mostly a scavenger, cracking the bones others leave; also takes small prey and young. */
    val HYENA = Species(
        id = 21, name = "striped hyena", massKg = 35f,
        fearOfPeople = 0.5f, boldShare = 0.1f,
        diet = Diet(meat = 0.1f, smallPrey = 0.25f, carrion = 0.55f, fruit = 0.1f),
        groupSize = 2, maxGroupSize = 5,
        // (They rest in pairs, now and then up to four, but forage alone; several may come to a carcass, not together:
        // hyaenidae.org, striped hyaena social behaviour. In the Negev some go about with wolf packs: Dinets and Eligulashvili 2016.)
        foragingParty = 1,
        dailyTravelKm = 12f, maxDailyTravelKm = 40f, senseKm = 10f,
        daysWithoutWater = 4, waterFromFood = 0.6f,
        birthMonth = 5, birthMonths = 2, youngPerFemale = 2f, yearsToMaturity = 2f,
        backgroundMortality = 0.07f, youngMortality = 0.3f, snowLimitMm = 200f,
        products = Products(meat = 14f, fat = 1f, hide = 2.5f, bone = 4f, sinew = 0.2f, fur = true),
        comfortC = 0f..38f, winterCoat = true,
        // (A dense, soft winter coat with underfur, shed for a coarse summer one: Heptner and Sludskii 1992.)
        speedKmh = 50f,
        domestication = floatArrayOf(0.8f, 0.3f, 0.2f, 0.3f, 0.3f, 0.2f),
        habitat = Habitat(rugged = 0.3f),
        stalking = 0.2f,
        // (The striped hyena is shy and gives way to wolves, leopards and dogs, unlike the spotted hyena; from memory, not checked.)
        ferocity = 0.8f,
        startKm2PerAnimal = 50f,
        activity = floatArrayOf(0.1f, 0f, 0.2f, 0.7f),
        shelters = setOf(Niches.Shelter.DEN),
        cracksBones = true,
        takesPeople = 0.3f,
    )

    /** Golden jackal: pairs living on rodents, carrion and the young of gazelle and deer. */
    val JACKAL = Species(
        id = 22, name = "jackal", massKg = 10f,
        fearOfPeople = 0.4f, boldShare = 0.15f,
        diet = Diet(meat = 0.1f, smallPrey = 0.5f, carrion = 0.25f, fruit = 0.15f),
        groupSize = 2, maxGroupSize = 6,
        dailyTravelKm = 8f, maxDailyTravelKm = 25f, senseKm = 4f,
        daysWithoutWater = 4, waterFromFood = 0.6f,
        // (Kept as a resident: a litter of four to six a year.)
        birthMonth = 5, birthMonths = 1, youngPerFemale = 5f, yearsToMaturity = 1f,
        backgroundMortality = 0.25f, youngMortality = 0.4f, snowLimitMm = 150f,
        products = Products(meat = 4f, fat = 0.2f, hide = 0.6f, bone = 1f, sinew = 0.1f, fur = true),
        comfortC = -5f..38f, winterCoat = true,
        speedKmh = 45f,
        domestication = floatArrayOf(0.8f, 0.6f, 0.4f, 0.4f, 0.4f, 0.6f),
        habitat = Habitat(cover = 0.2f, wet = 0.2f),
        stalking = 0.4f,
        ferocity = 0.8f,
        // (Jackals bite back and stand by each other: an estimate, as the onager's (0.4).)
        defence = 0.4f,
        // (Golden jackals take prey up to about 15 kg but mostly up to 4: hares, rodents, fawns, not grown gazelle;
        // Hayward et al. 2017, prey preferences of jackals.)
        preyUpTo = 1.5f, preyPreferredUpTo = 0.4f,
        startKm2PerAnimal = 10f,
        followsPeople = true,
        activity = floatArrayOf(0.25f, 0.1f, 0.25f, 0.4f),
        shelters = setOf(Niches.Shelter.DEN, Niches.Shelter.THICKET),
    )

    // ---------------------------------------------------------------- the cold north

    /**
     * Elk (moose): a browser of wet northern forest, willow carr and lake shores, wading for water
     * plants in summer. Long legs carry it through deep snow; it suffers in summer heat above about
     * 15–20 °C. Adults kick hard enough to keep wolves at bay.
     */
    val ELK = Species(
        id = 10, name = "elk", massKg = 400f,
        // (Its summer water plants are browse here: it does not dig bulbs or pick up acorns, and the model's roots are those.)
        diet = Diet(grass = 0.1f, browse = 0.9f),
        groupSize = 2, maxGroupSize = 5,
        dailyTravelKm = 4f, maxDailyTravelKm = 20f, senseKm = 1.5f,
        daysWithoutWater = 3, waterFromFood = 0.6f,
        birthMonth = 7, birthMonths = 1, youngPerFemale = 1.2f, yearsToMaturity = 2f,
        // (It all but stops moving past 90-100 cm of snow, some 250 mm of water: Kelsall and Prescott 1967, Nasimovich 1955.)
        backgroundMortality = 0.07f, firstYearLost = HOOFED_YOUNG_LOST, snowLimitMm = 250f,
        products = Products(meat = 200f, fat = 15f, hide = 25f, bone = 45f, sinew = 3f, hornOrAntler = 15f),
        comfortC = -30f..17f, winterCoat = true,
        speedKmh = 55f,
        domestication = floatArrayOf(0.5f, 0.4f, 0.3f, 0.4f, 0.3f, 0.1f),
        habitat = Habitat(cover = 0.6f, rugged = -0.2f, wet = 0.8f),
        defence = 0.6f,
        activity = floatArrayOf(0.3f, 0.2f, 0.3f, 0.2f),
        shelters = setOf(Niches.Shelter.THICKET, Niches.Shelter.WATER),
        swimKm = 15f,
    )

    /**
     * Wild goat (bezoar): herds of the broken hill country, crags and open oak woods of the Taurus
     * and Zagros; they climb to the high pastures in summer and come down to the lower slopes and
     * valleys in winter, ahead of the deep snow. The ancestor of the goat (figures from memory).
     */
    val WILD_GOAT = Species(
        id = 11, name = "wild goat", massKg = 40f,
        fearOfPeople = 0.75f, boldShare = 0.05f,
        diet = Diet(grass = 0.5f, browse = 0.5f),
        groupSize = 10, maxGroupSize = 50,
        dailyTravelKm = 5f, maxDailyTravelKm = 25f, senseKm = 3f,
        daysWithoutWater = 3, waterFromFood = 0.6f,
        birthMonth = 4, birthMonths = 1, youngPerFemale = 1.3f, yearsToMaturity = 2f,
        backgroundMortality = 0.1f, firstYearLost = HOOFED_YOUNG_LOST, snowLimitMm = 100f,
        products = Products(meat = 18f, fat = 1.5f, hide = 3f, bone = 5f, sinew = 0.4f, hornOrAntler = 3f),
        comfortC = -15f..35f, winterCoat = true,
        speedKmh = 50f,
        foresight = 0.6f,
        domestication = floatArrayOf(0.8f, 0.6f, 0.7f, 0.6f, 0.6f, 0.8f),
        milked = true, // became the goat
        habitat = Habitat(cover = -0.2f, rugged = 0.8f),
        defence = 0.1f,
        activity = floatArrayOf(0.3f, 0.4f, 0.2f, 0.1f),
        shelters = setOf(Niches.Shelter.CRAG),
    )

    /**
     * Wild horse: herds of cold steppe and open grassland, pawing through snow to winter grass; the
     * future mount. A family band of a stallion and his mares, roaming far between grazing and water.
     */
    val HORSE = Species(
        id = 12, name = "wild horse", massKg = 300f,
        fearOfPeople = 0.7f, boldShare = 0.05f,
        diet = Diet(grass = 0.9f, browse = 0.1f),
        groupSize = 10, maxGroupSize = 25,
        dailyTravelKm = 12f, maxDailyTravelKm = 50f, senseKm = 3f,
        // Steppe horses drink once a day or two but go three days between waterholes in a pinch.
        daysWithoutWater = 3, waterFromFood = 0.3f,
        birthMonth = 6, birthMonths = 2, youngPerFemale = 0.7f, yearsToMaturity = 2.5f,
        // (Steppe horses paw down to grass under some 50 cm of snow, some 125 mm of water: the Yakut horse's, Khazanov 1995.)
        backgroundMortality = 0.06f, firstYearLost = HOOFED_YOUNG_LOST, snowLimitMm = 125f,
        products = Products(meat = 140f, fat = 10f, hide = 18f, bone = 30f, sinew = 2f),
        comfortC = -25f..30f, winterCoat = true,
        speedKmh = 70f,
        foresight = 0.6f,
        domestication = floatArrayOf(0.8f, 0.5f, 0.6f, 0.5f, 0.6f, 0.8f),
        milked = true, // became the horse
        habitat = Habitat(cover = -0.8f, rugged = -0.2f),
        defence = 0.4f,
        hindgut = true,
        activity = floatArrayOf(0.25f, 0.35f, 0.2f, 0.2f),
    )

    /**
     * Mouflon: wild sheep of rolling hills and open slopes, less of a cliff-dweller than the ibex;
     * the ancestor of the sheep.
     */
    val MOUFLON = Species(
        id = 13, name = "mouflon", massKg = 40f,
        fearOfPeople = 0.7f, boldShare = 0.06f,
        diet = Diet(grass = 0.8f, browse = 0.2f),
        groupSize = 10, maxGroupSize = 40,
        dailyTravelKm = 5f, maxDailyTravelKm = 25f, senseKm = 3f,
        daysWithoutWater = 3, waterFromFood = 0.6f,
        birthMonth = 5, birthMonths = 1, youngPerFemale = 1.2f, yearsToMaturity = 2f,
        backgroundMortality = 0.12f, firstYearLost = HOOFED_YOUNG_LOST, snowLimitMm = 80f,
        products = Products(meat = 18f, fat = 2f, hide = 3f, bone = 5f, sinew = 0.3f, hornOrAntler = 2f),
        comfortC = -15f..32f, winterCoat = true,
        speedKmh = 55f,
        foresight = 0.5f,
        domestication = floatArrayOf(0.8f, 0.6f, 0.7f, 0.6f, 0.6f, 0.8f),
        milked = true, // became the sheep
        habitat = Habitat(cover = -0.6f, rugged = 0.6f),
        defence = 0.1f,
        activity = floatArrayOf(0.3f, 0.4f, 0.2f, 0.1f),
        shelters = setOf(Niches.Shelter.CRAG),
    )

    /**
     * Argali: the great wild sheep of open high country, rolling uplands and plateaus above the
     * trees; herds go up to the summer pastures as the snow leaves them and come down before it
     * lies deep, to the windswept slopes and valleys (figures from memory).
     */
    val ARGALI = Species(
        id = 26, name = "argali", massKg = 100f,
        fearOfPeople = 0.8f, boldShare = 0.04f,
        diet = Diet(grass = 0.85f, browse = 0.15f),
        groupSize = 15, maxGroupSize = 80,
        dailyTravelKm = 8f, maxDailyTravelKm = 40f, senseKm = 4f,
        daysWithoutWater = 3, waterFromFood = 0.5f,
        birthMonth = 5, birthMonths = 1, youngPerFemale = 1.1f, yearsToMaturity = 2f,
        backgroundMortality = 0.1f, firstYearLost = HOOFED_YOUNG_LOST, snowLimitMm = 100f,
        products = Products(meat = 45f, fat = 5f, hide = 6f, bone = 12f, sinew = 0.8f, hornOrAntler = 8f),
        comfortC = -30f..25f, winterCoat = true,
        speedKmh = 60f,
        foresight = 0.6f,
        domestication = floatArrayOf(0.5f, 0.4f, 0.5f, 0.4f, 0.4f, 0.5f),
        habitat = Habitat(cover = -0.8f, rugged = 0.4f, wet = -0.3f),
        defence = 0.2f,
        activity = floatArrayOf(0.3f, 0.4f, 0.2f, 0.1f),
        shelters = setOf(Niches.Shelter.CRAG),
    )

    // ---------------------------------------------------------------- the hot, dry south

    /**
     * Arabian oryx: open gravel plains and dunes; barely drinks, living on dew and the water in
     * plants, and tracks distant rain. Its long horns make adults dangerous to tackle.
     */
    val ORYX = Species(
        id = 14, name = "oryx", massKg = 70f,
        diet = Diet(grass = 0.7f, browse = 0.3f),
        groupSize = 10, maxGroupSize = 30,
        dailyTravelKm = 10f, maxDailyTravelKm = 40f, senseKm = 4f,
        daysWithoutWater = 30, waterFromFood = 0.95f,
        birthMonth = 3, birthMonths = 6, youngPerFemale = 0.8f, yearsToMaturity = 2.5f,
        backgroundMortality = 0.08f, firstYearLost = HOOFED_YOUNG_LOST, snowLimitMm = 20f,
        products = Products(meat = 30f, fat = 2f, hide = 5f, bone = 8f, sinew = 0.6f, hornOrAntler = 2f),
        comfortC = 8f..45f,
        speedKmh = 60f,
        foresight = 0.8f,
        domestication = floatArrayOf(0.6f, 0.5f, 0.4f, 0.3f, 0.4f, 0.5f), // kept in Egyptian herds, never bred on
        habitat = Habitat(cover = -1f, rugged = -0.2f, wet = -0.8f),
        defence = 0.4f,
        activity = floatArrayOf(0.3f, 0.3f, 0.3f, 0.1f),
    )

    /**
     * Arabian ostrich: a big flightless bird of the desert steppe, picking green shoots, flowers,
     * seeds and fruit; outruns almost everything and kicks hard. Large clutches, but a pair rears about one chick a year.
     */
    val OSTRICH = Species(
        id = 15, name = "ostrich", massKg = 110f,
        // A picky feeder on green shoots, flowers, seeds and fruit, not a bulk grazer; it does not dig (the seeds and flowers
        // are counted with the fruit).
        diet = Diet(grass = 0.2f, browse = 0.4f, fruit = 0.4f),
        groupSize = 5, maxGroupSize = 30,
        dailyTravelKm = 15f, maxDailyTravelKm = 50f, senseKm = 5f,
        daysWithoutWater = 7, waterFromFood = 0.8f,
        // A hen lays 10-20 eggs; about three chicks a hen live through their first weeks.
        birthMonth = 5, birthMonths = 3, youngPerFemale = 3f, yearsToMaturity = 3f,
        backgroundMortality = 0.07f, youngMortality = 0.7f,
        snowLimitMm = 30f,
        products = Products(meat = 50f, fat = 3f, hide = 10f, bone = 10f, sinew = 1f),
        comfortC = 5f..45f,
        speedKmh = 70f,
        foresight = 0.6f,
        domestication = floatArrayOf(0.6f, 0.6f, 0.5f, 0.2f, 0.3f, 0.3f),
        habitat = Habitat(cover = -1f, wet = -0.5f),
        defence = 0.4f,
        // Ostriches ferment fibre in a long hindgut: volatile fatty acids from it may give up to 76% of a growing
        // chick's metabolisable energy (Swart, Mackie and Hayes 1993). Like horses, they eat as much of poor forage as of good.
        hindgut = true,
        activity = floatArrayOf(0.2f, 0.6f, 0.2f, 0f),
    )

    /** Bubal hartebeest: a grazer of dry grassland and semi-desert edges, in herds; kept by the Egyptians. */
    val HARTEBEEST = Species(
        id = 16, name = "hartebeest", massKg = 150f,
        diet = Diet(grass = 0.95f, browse = 0.05f),
        groupSize = 12, maxGroupSize = 40,
        dailyTravelKm = 8f, maxDailyTravelKm = 40f, senseKm = 3f,
        daysWithoutWater = 5, waterFromFood = 0.5f, // goes days without drinking where the grass is green
        birthMonth = 4, birthMonths = 2, youngPerFemale = 0.8f, yearsToMaturity = 2f,
        backgroundMortality = 0.08f, firstYearLost = HOOFED_YOUNG_LOST, snowLimitMm = 30f,
        products = Products(meat = 70f, fat = 4f, hide = 9f, bone = 18f, sinew = 1f, hornOrAntler = 2f),
        comfortC = 5f..40f,
        speedKmh = 70f,
        domestication = floatArrayOf(0.6f, 0.5f, 0.4f, 0.3f, 0.4f, 0.5f),
        habitat = Habitat(cover = -0.8f, rugged = -0.3f),
        defence = 0.2f,
        activity = floatArrayOf(0.25f, 0.45f, 0.25f, 0.05f),
    )

    /**
     * Wild dromedary: a browser of desert and thorn scrub that goes two weeks without drinking and
     * then drinks a hundred litres; the future beast of burden of the desert routes.
     */
    val DROMEDARY = Species(
        id = 17, name = "dromedary", massKg = 450f,
        diet = Diet(grass = 0.3f, browse = 0.7f),
        groupSize = 8, maxGroupSize = 20,
        dailyTravelKm = 15f, maxDailyTravelKm = 60f, senseKm = 4f,
        daysWithoutWater = 14, waterFromFood = 0.5f,
        birthMonth = 2, birthMonths = 3, youngPerFemale = 0.4f, yearsToMaturity = 4f,
        backgroundMortality = 0.05f, firstYearLost = HOOFED_YOUNG_LOST, snowLimitMm = 40f,
        products = Products(meat = 200f, fat = 40f, hide = 30f, bone = 50f, sinew = 3f),
        comfortC = 5f..48f,
        speedKmh = 60f,
        foresight = 0.7f,
        domestication = floatArrayOf(0.7f, 0.3f, 0.5f, 0.6f, 0.6f, 0.7f),
        milked = true, // became the camel
        habitat = Habitat(cover = -0.8f, rugged = -0.3f, wet = -0.5f),
        defence = 0.6f,
        fibreDigestion = 1.5f, // a camel keeps coarse food long in its foregut and gets more from it than cattle do,
        activity = floatArrayOf(0.2f, 0.5f, 0.2f, 0.1f),
    )

    /**
     * Asiatic cheetah: a sprinter of open plains that runs down gazelle and young antelope in the
     * open; it loses its kills to anything bigger and cannot hunt in cover or broken ground.
     */
    val CHEETAH = Species(
        id = 23, name = "cheetah", massKg = 45f,
        fearOfPeople = 0.7f, boldShare = 0.08f,
        diet = Diet(meat = 0.8f, smallPrey = 0.2f),
        groupSize = 2, maxGroupSize = 4,
        dailyTravelKm = 8f, maxDailyTravelKm = 30f, senseKm = 6f,
        daysWithoutWater = 10, waterFromFood = 0.8f,
        birthMonth = 5, birthMonths = 2, youngPerFemale = 2f, yearsToMaturity = 2f,
        backgroundMortality = 0.1f, youngMortality = 0.4f, snowLimitMm = 50f,
        products = Products(meat = 20f, fat = 1f, hide = 3f, bone = 5f, sinew = 0.3f, fur = true),
        comfortC = 3f..42f,
        speedKmh = 105f,
        domestication = floatArrayOf(0.2f, 0.4f, 0.0f, 0.5f, 0.3f, 0.2f), // tamed for the hunt, never bred
        habitat = Habitat(cover = -0.8f, rugged = -0.5f),
        stalking = 0.3f,
        ferocity = 0.6f,
        // (Cheetahs prefer prey of 23-56 kg (Hayward et al. 2006) and take up to some 135 (Clements et al. 2014).)
        preyPreferredUpTo = 1.25f, preyUpTo = 3f,
        startKm2PerAnimal = 100f,
        activity = floatArrayOf(0.25f, 0.55f, 0.15f, 0.05f),
        swimKm = 1f,
    )

    // ---------------------------------------------------------------- people

    /**
     * People: hunter-gatherer bands of the Natufian age, some 25 strong, hunting with spears, bows
     * and fire and gathering fruit, nuts, seeds and roots, about half of each. How well
     * a band hunts each kind of game and gathers each kind of food is a skill it builds up by
     * practice and loses without it. They cook (so they need less food than a meat-eater of their
     * size), wear clothes and keep fires (so cold troubles them less), and sense no weather ahead:
     * what they know of next week they must learn.
     */
    val HUMAN = Species(
        id = 24, name = "people", massKg = 55f,
        fearOfPeople = 0.0f, boldShare = 0.0f,
        // Natufian middens and plant remains suggest plant food (nuts, acorns, seeds, pulses, roots)
        // made up as much of the diet as game; hunting is a little over a third.
        diet = Diet(meat = 0.35f, smallPrey = 0.12f, carrion = 0.04f, roots = 0.19f, fruit = 0.30f),
        groupSize = 25, maxGroupSize = 50,
        dailyTravelKm = 10f, maxDailyTravelKm = 40f, senseKm = 10f,
        daysWithoutWater = 3, waterFromFood = 0.2f,
        birthMonth = 3, birthMonths = 12, youngPerFemale = 0.22f, yearsToMaturity = 15f, // a birth every 4–5 years
        // Children's ordinary deaths (fevers, gut infections, accidents): about 60% would reach fifteen
        // on these alone. Hunger, thirst, cold, crowding sickness, fouled camps, inbreeding, raids and
        // predators come on top, each modelled, for the 55-65% foragers see in all (Hill and Hurtado,
        // Gurven and Kaplan, from memory).
        backgroundMortality = 0.0035f, youngMortality = 0.0175f,
        snowLimitMm = 600f,
        products = Products(meat = 25f, fat = 4f, hide = 0f, bone = 7f, sinew = 0f),
        comfortC = -5f..35f, winterCoat = true, // clothes and fire
        speedKmh = 45f, // with thrown spears and arrows a chase is short
        foresight = 0f,
        domestication = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f),
        habitat = Habitat(cover = 0f, rugged = 0f, wet = 0.2f),
        defence = 0.7f,
        stalking = 0.6f,
        ferocity = 1.5f, // spears, numbers and fire: lions and hyenas give way
        // (With spears, bows and numbers people take game of any size, aurochs too; the danger is in [defence].)
        preyPreferredUpTo = Float.POSITIVE_INFINITY, preyUpTo = Float.POSITIVE_INFINITY,
        startKm2PerAnimal = 40f,
        human = true,
        intakeKg = 1.6f, needUnits = Nourishment.PEOPLE_NEED,
    )

    private val firstHerbivores = listOf(
        GAZELLE, ROE_DEER, FALLOW_DEER, RED_DEER, IBEX, BOAR, AUROCHS, ONAGER, HIPPO, BEAR,
        ELK, WILD_GOAT, HORSE, MOUFLON, ORYX, OSTRICH, HARTEBEEST, DROMEDARY,
    )
    // (A new kind takes the next free [Species.id], so every kind keeps its index, and saved networks their identities.)
    val predators = listOf(WOLF, LION, LEOPARD, HYENA, JACKAL, CHEETAH, HUMAN, LYNX)
    /** Kinds added after the predators (with later ids, so the others keep their indices). */
    private val laterHerbivores = listOf(ARGALI)

    /**
     * Plant-eaters that follow the seasons over long distances; the others live out their lives in small home ranges.
     * They live as residents ([resident]) as the others do; the set keeps their place at the end of [HexFauna.kinds] and
     * the learned movers' "migratory" input.
     */
    val migratory = setOf(GAZELLE, WILD_GOAT, HORSE, ONAGER, ORYX, OSTRICH, HARTEBEEST, DROMEDARY, ARGALI)

    /**
     * Hunters that live everywhere they can on small home ranges, kept as
     * numbers per place like the plant-eaters rather than as packs that choose where to go
     * (cheaper, and the same rules of eating, breeding and dying): golden jackals, lynx, and striped hyenas, lone
     * foragers that did nothing as packs that residents do not (Olli 2026-10-31, on packs' cost against what they add,
     * review/2026-10-31-mobile-predators-worth.md). Leopards stay packs: as residents nothing held them but the
     * breeding spacing, and they rose some fivefold in ten years (review/2026-10-31-ab-residents.md). (They stay in
     * [predators], so every kind keeps its index.)
     */
    val residentHunters by lazy { setOf(JACKAL, LYNX, HYENA) }

    /** Kinds kept as numbers per place, the residents: every plant-eater, and the [residentHunters]. */
    fun resident(s: Species) = !s.carnivore || s in residentHunters

    /**
     * Residents that are rivals of the other beasts of prey as well as their game: the [residentHunters] and the brown bear.
     * The resident hunters keep out of the way of a much stronger party ([HexFauna.strongest]). (A bear robbing a pack's
     * kill waits for a rule of bears finding kills by scent.)
     */
    val residentRivals by lazy { setOf(JACKAL, LYNX, HYENA, BEAR) }

    /** Kinds that climb crags and screes others cannot cross. */
    val climbers = setOf(IBEX, MOUFLON, WILD_GOAT, LEOPARD)

    /**
     * How far a kind will swim open water, km: elk, hippos, red deer, boar and bears cross straits of
     * ten or more; most others a few; people none (until boats). From memory of the record, mild.
     */
    fun swimKm(s: Species): Float = swimByIndex[s.index]
    private val swimByIndex by lazy { FloatArray(all.size) { i -> val s = all[i]; s.swimKm.takeIf { it >= 0f } ?: if (s.human) 0f else when (travel(s)) {
        HexMap.Travel.SWIM -> 8f; HexMap.Travel.CLIMB -> 2f; else -> 3f } } }

    /** How a kind crosses hex borders: climbers over crags, big plant-eaters swim rivers, the rest walk. */
    fun travel(s: Species) = if (s in climbers) HexMap.Travel.CLIMB
        else if (!s.carnivore && s.massKg > 60f) HexMap.Travel.SWIM else HexMap.Travel.FOOT
    /** Every kind, in [Species.id] order (so `all[s.index] === s`). */
    val all = (firstHerbivores + predators + laterHerbivores).sortedBy { it.id }
    /**
     * The kinds that move as groups with a learned mover: the beasts of prey and people. Named, not read
     * from the diet: people eat about half meat, and a small retune of their diet would otherwise drop
     * them from training and from the check without a word.
     */
    val learned = all.filter { it in predators && it !in residentHunters }

    init {
        require(all.withIndex().all { (i, s) -> s.id == i }) { "species ids must run 0 until ${all.size} without gaps or repeats" }
    }
}
