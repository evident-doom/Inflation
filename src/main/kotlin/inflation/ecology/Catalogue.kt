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
        // (Field counts: mountain gazelle 10-31 in one small Israeli park (Ramat Hanadiv surveys), goitered gazelle 0.5-5 on the steppe, from memory.)
        fieldKm2 = 0.3f..15f,
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
        kin = Kin.RUMINANTS, dampTolerance = 0.2f,
    )

    /**
     * Deer: the Persian fallow deer of the Levant's open woods and parkland, the commonest deer in Epipalaeolithic sites,
     * and the red deer of the northern woods and hills, kept as one kind: they share one slot, mixing grass and browse
     * in woodland and its edges, and nothing in the model would part them. Herds split by sex outside the rut.
     * Figures between the fallow and the red deer's. (The roe, the small selective browser, is a kind of its own:
     * [ROE_DEER].)
     */
    val DEER = Species(
        id = 1, name = "deer", massKg = 90f,
        // (Field counts: red deer in Białowieża 0-5.4 (Jędrzejewska et al. 1997), 2-3.5 (Kamler et al. 2007, Wildl. Biol. 13:48).)
        fieldKm2 = 0.3f..6f,
        // (Acorns and beechnuts where the oaks and beeches bear, berries less: red deer fatten on fallen mast in autumn;
        // from memory of the record, not checked.)
        diet = Diet(grass = 0.45f, browse = 0.45f, fruit = 0.03f, mast = 0.07f),
        groupSize = 6, maxGroupSize = 25,
        dailyTravelKm = 5f, maxDailyTravelKm = 30f, senseKm = 2.5f,
        daysWithoutWater = 3, waterFromFood = 0.55f,
        // (A hind or doe bears her first at two: the fallow deer at about two, the red deer at two or three; from memory.)
        birthMonth = 5, birthMonths = 1, youngPerFemale = 0.85f, yearsToMaturity = 2f,
        backgroundMortality = 0.09f, firstYearLost = HOOFED_YOUNG_LOST, snowLimitMm = 160f,
        products = Products(meat = 45f, fat = 3.5f, hide = 6f, bone = 11f, sinew = 0.7f, hornOrAntler = 3.5f),
        comfortC = -10f..33f, winterCoat = true,
        speedKmh = 62f,
        domestication = floatArrayOf(0.5f, 0.5f, 0.4f, 0.4f, 0.3f, 0.4f), // kept in parks, never domesticated
        habitat = Habitat(cover = 0.4f, rugged = 0.1f),
        defence = 0.15f,
        activity = floatArrayOf(0.35f, 0.1f, 0.35f, 0.2f),
        shelters = setOf(Niches.Shelter.THICKET, Niches.Shelter.WATER),
        swimKm = 8f,
        kin = Kin.RUMINANTS, dampTolerance = 0.6f, climbing = 0.1f,
    )

    /**
     * Roe deer: small, solitary or in families, picking the best shoots, buds, leaves and herbs of woodland edges and
     * thickets; the small selective browser beside the bigger deer that mix grass and browse, living off thinner and
     * better food than they can (its small gut and wide-ranging bites: [gutFill], [cropRate]), and the staple prey of
     * the middle-sized hunters, leopard and lynx. Left out with the fallow and red deer in model 6, the leopards lost
     * their prey and starved (2026-11-16); back as the next kind.
     */
    val ROE_DEER = Species(
        id = 22, name = "roe deer", massKg = 25f,
        // (Field counts: Białowieża 0.6-4.8 (Jędrzejewska et al. 1997), spring 1.2-3.2 (Theuerkauf et al. 2008); up to 20-30 on farmland without their hunters, from memory.)
        fieldKm2 = 0.5f..10f,
        // (Berries and fallen acorns alike in autumn; from memory of the record, not checked.)
        diet = Diet(grass = 0.2f, browse = 0.7f, fruit = 0.05f, mast = 0.05f),
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
        kin = Kin.RUMINANTS, dampTolerance = 0.6f,
    )




    /** Wild boar: omnivore of forest, thicket and marsh; roots, acorns, bulbs; large litters. */
    val BOAR = Species(
        id = 2, name = "wild boar", massKg = 90f,
        robsNests = true,
        // (Field counts: Białowieża 0.2-3.8 over a century (Jędrzejewska et al. 1997, Acta Theriol. 42:399), spring 2.2-4.3 (Theuerkauf et al. 2008, Ann. Zool. Fenn. 45); more after a mast.)
        fieldKm2 = 0.2f..5f,
        fearOfPeople = 0.6f, boldShare = 0.1f,
        // (Mast, acorns and beechnuts, the most of a boar's food through autumn and winter where it falls; roots, bulbs and
        // tubers dug the year round; fleshy fruit a little: as the reviews of the boar's diet have it, from memory, not
        // checked. The three together as the roots and fruit were before mast was a food of its own.)
        diet = Diet(grass = 0.15f, browse = 0.1f, roots = 0.25f, mast = 0.3f, fruit = 0.1f, smallPrey = 0.05f, carrion = 0.05f),
        groupSize = 6, maxGroupSize = 20,
        dailyTravelKm = 5f, maxDailyTravelKm = 20f, senseKm = 1.5f,
        daysWithoutWater = 2, waterFromFood = 0.4f,
        birthMonth = 4, birthMonths = 2, youngPerFemale = 5f, yearsToMaturity = 1.5f,
        // Piglets and adults die mostly by hunting, then hard winters; few of other causes (Keuling et al. 2013,
        // Jędrzejewski et al. 1992), so the unmodelled adult rate is that of other hoofed kinds of its size.
        // (Half its litter is lost young of chill, hunger and being lain on, before any hunter: 56% of radio-tagged
        // piglets dead by six weeks, most within ten days, Chinn et al. 2021 Sci Rep 11:11033; a third in the first three
        // months in Kampinos, and 80-90% in hard years, Jezierski 1977 Acta Theriol 22:337 citing Lebedev 1956. The
        // price of its big litters: at the calf's 0.2 a sow raised four of her five, and the boar stood 3-4 times as
        // thick as in Białowieża: dens1121, piglets-ab.)
        backgroundMortality = 0.08f, firstYearLost = 0.5f,
        snowLimitMm = 110f,
        products = Products(meat = 45f, fat = 8f, hide = 5f, bone = 9f, sinew = 0.5f, hornOrAntler = 0.5f),
        comfortC = -8f..34f, winterCoat = true,
        speedKmh = 45f,
        foresight = 0.5f,
        domestication = floatArrayOf(0.9f, 0.9f, 0.8f, 0.4f, 0.6f, 0.6f), // became the pig
        habitat = Habitat(cover = 0.8f, wet = 0.6f),
        // (At 0.6 the residents' hunters took 49 a thousand of them a year and lived on the other game; at 0.4 they take 58,
        // the boar no fewer (what the hunters take, hunger and sickness would), roe 1.28x, deer 1.11x, the cats' hunger
        // halved: boar-defence-ab, 2026-11-19. An estimate: wolves live much on boar in the oak woods, from memory.)
        defence = 0.4f,
        fibreDigestion = 0.5f,
        activity = floatArrayOf(0.2f, 0.05f, 0.25f, 0.5f),
        shelters = setOf(Niches.Shelter.THICKET, Niches.Shelter.WATER),
        swimKm = 10f,
        kin = Kin.PIGS, dampTolerance = 0.9f,
        // (No sweat glands to speak of: it cools in mud and shade.)
        sweats = false,
    )

    /** Aurochs: huge wild cattle of wet lowlands, floodplains and open woodland; formidable when cornered. */
    val AUROCHS = Species(
        id = 3, name = "aurochs", massKg = 750f,
        // (Field counts: none for the aurochs; European bison in Białowieża some 0.5-1, from memory.)
        fieldKm2 = 0.1f..2f,
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
        kin = Kin.RUMINANTS, dampTolerance = 0.8f,
    )

    /** Onager (Asiatic wild ass): fast grazer of steppe and semi-desert, needs to drink often. */
    val ONAGER = Species(
        id = 4, name = "onager", massKg = 230f,
        // (Field counts: khulan and onager some 0.05-0.6, from memory.)
        fieldKm2 = 0.03f..1f,
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
        kin = Kin.EQUIDS, dampTolerance = 0.2f,
    )

    /**
     * Hippopotamus: lived in the Levant's coastal rivers into historical times. Spends the day in
     * water and grazes the banks at night; adults fear nothing, calves fall to lions and crocodiles.
     */
    val HIPPO = Species(
        id = 5, name = "hippo", massKg = 1400f,
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
        dampTolerance = 1f,
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
        id = 6, name = "brown bear", massKg = 150f,
        robsNests = true,
        // (Field counts: European brown bears some 0.01-0.1, the Carpathians to 0.2, from memory.)
        fieldKm2 = 0.01f..0.2f,
        fearOfPeople = 0.6f, boldShare = 0.05f,
        // (Berries in summer, hard mast, beechnuts and acorns, in autumn to fatten for the winter sleep; from memory, not checked.)
        diet = Diet(grass = 0.1f, browse = 0.1f, roots = 0.2f, fruit = 0.15f, mast = 0.15f, meat = 0.1f, smallPrey = 0.1f, carrion = 0.1f),
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
        dampTolerance = 0.7f, climbing = 0.4f,
        // (Its kills are mostly the young of spring, found hidden in their first weeks: moose and elk calves, fawns, piglets;
        // grown deer and elk now and then, cattle seldom. Swenson et al. 2007 and the Scandinavian bear studies, from memory.)
        preyFrom = 0.05f, preyPreferredUpTo = 0.5f, preyUpTo = 3f,
    )

    /**
     * Grey wolf of the southern, Arabian-Levantine type: smaller than northern wolves and living in
     * small packs. Eats much small prey and carrion.
     */
    val WOLF = Species(
        id = 15, name = "wolf", massKg = 22f,
        // (Field counts: Białowieża some 0.02-0.03, from memory.)
        fieldKm2 = 0.005f..0.06f,
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
        defence = 0.5f, // (a wolf at bay bites; an estimate)
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
        id = 16, name = "lion", massKg = 150f,
        // (Field counts: from memory: 0.01-0.03 on poor ground, 0.1 on the richest.)
        fieldKm2 = 0.003f..0.1f,
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
        defence = 0.8f, // (a lion turns on its hunters, as a bear does: Maasai lion hunts wound men often (from memory); an estimate)
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
        hidesYoung = true,
        // (A pride's young males roam some 30 km before settling, and take a year or so to win a pride: an estimate.)
        dispersalKm = 30f, dispersalDays = 300f,
    )

    /** Leopard: a solitary stalker of broken and wooded ground; caches kills away from bigger rivals. */
    val LEOPARD = Species(
        id = 17, name = "leopard", massKg = 45f,
        // (Field counts: Persian and Arabian leopards some 0.01-0.03, from memory.)
        fieldKm2 = 0.003f..0.05f,
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
        defence = 0.7f, // (a cornered leopard is among the most dangerous of beasts to hunters; an estimate)
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
        hidesYoung = true,
        climbing = 0.6f,
    )


    /** Striped hyena: mostly a scavenger, cracking the bones others leave; also takes small prey and young. */
    val HYENA = Species(
        id = 18, name = "striped hyena", massKg = 35f,
        robsNests = true,
        // (Field counts: from memory: some 0.01-0.1.)
        fieldKm2 = 0.005f..0.1f,
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
        defence = 0.6f, // (a hyena bites back hard; an estimate)
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
        climbing = 0.3f,
    )

    /** Golden jackal: pairs living on rodents, carrion and the young of gazelle and deer. */
    val JACKAL = Species(
        id = 19, name = "jackal", massKg = 10f,
        robsNests = true,
        // (Field counts: golden jackals about one family group a km² in Europe, 0.1-5 in the record (Arnold et al. 2012, Mammal Rev.; from the review as found), 0.06-0.11 groups on average over four Balkan countries; a group two to six.)
        fieldKm2 = 0.2f..5f,
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

    /**
     * Lynx, and with it the caracal: the middle-sized cats, solitary stalkers of forest, scrub and broken ground, the roe
     * deer's chief hunter in the woods and the gazelle's and the hare's on the dry hills (a roe or a young gazelle a week or
     * so, with hares, birds and rodents between); a mother keeps her kits with her through their first winter. One label
     * for the slot: the Eurasian lynx in the woods and the mountains, the caracal in the scrub and the steppe.
     */
    val LYNX = Species(
        id = 23, name = "lynx", massKg = 18f,
        // (Field counts: Eurasian lynx some 1-3 to the 100 km² (Białowieża about 3, Jędrzejewski et al.), caracal about the
        // same where it is not hunted down; from memory, not checked.)
        fieldKm2 = 0.005f..0.05f,
        // (Its ground held by its game as Carbone and Gittleman's rule has it, some 90 kg of a carnivore kind for 10,000 kg of
        // its prey ([HexFauna.SMALL_HUNTER_SUPPORT]), not the wolves' fit ([HexFauna.PREY_SUPPORT], 4-5 times less): Białowieża's
        // lynx, some 3 to the 100 km² on roe at 1-3 a km² and the hares and small game beside, come out near it. On the wolves'
        // figure lynx sat at 0.016 a km² fed and fought over their ground while roe stood at 7 a km² in the woods (roe1123).)
        preySupport = HexFauna.SMALL_HUNTER_SUPPORT,
        fearOfPeople = 0.9f, boldShare = 0.01f,
        diet = Diet(meat = 0.7f, smallPrey = 0.27f, carrion = 0.03f),
        groupSize = 1, maxGroupSize = 4,
        dailyTravelKm = 8f, maxDailyTravelKm = 25f, senseKm = 3f,
        // (The caracal lives in dry country, on what its prey holds of water.)
        daysWithoutWater = 6, waterFromFood = 0.8f,
        // (Kept as a resident: a litter of two or three a year.)
        birthMonth = 5, birthMonths = 1, youngPerFemale = 2.5f, yearsToMaturity = 2f,
        backgroundMortality = 0.1f, youngMortality = 0.5f, snowLimitMm = 900f,
        products = Products(meat = 8f, fat = 0.3f, hide = 1.5f, bone = 2f, sinew = 0.1f, fur = true),
        // (The lynx's winters to the caracal's summers.)
        comfortC = -30f..38f, winterCoat = true,
        speedKmh = 50f,
        domestication = floatArrayOf(0.1f, 0.3f, 0.1f, 0.0f, 0.2f, 0.0f),
        habitat = Habitat(cover = 0.3f, rugged = 0.5f),
        // (It lies up in thickets and among rocks and climbs out of a wolf's reach.)
        shelters = setOf(Niches.Shelter.CRAG, Niches.Shelter.THICKET, Niches.Shelter.TREE),
        stalking = 1f,
        // (A lynx holds its own against animals its size and kills foxes and the young of others; from memory, not checked.)
        ferocity = 0.9f,
        // (A lynx fights back with teeth and claws: an estimate between the onager's kicks (0.4) and the boar's tusks (0.6).)
        defence = 0.5f,
        // (Roe deer of 20-30 kg most of all, chamois and young deer, a caracal gazelle of its own weight; from memory.)
        preyUpTo = 2f, preyPreferredUpTo = 1.4f,
        startKm2PerAnimal = 60f,
        activity = floatArrayOf(0.3f, 0.05f, 0.3f, 0.35f),
        hidesYoung = true,
    )

    // ---------------------------------------------------------------- the cold north

    /**
     * Elk (moose): a browser of wet northern forest, willow carr and lake shores, wading for water
     * plants in summer. Long legs carry it through deep snow; it suffers in summer heat above about
     * 15–20 °C. Adults kick hard enough to keep wolves at bay.
     */
    val ELK = Species(
        id = 7, name = "elk", massKg = 400f,
        // (Field counts: moose with wolves some 0.1-1, up to 2 managed, from memory.)
        fieldKm2 = 0.1f..2f,
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
        kin = Kin.RUMINANTS, dampTolerance = 0.9f,
    )

    /**
     * Wild goat: herds of the crags and broken hill country, the bezoar of the Taurus and Zagros and, kept as the same
     * kind, the Nubian ibex of the hot dry hills and gorges of the south: they share the one slot, cliffs and steep rock
     * where nothing can follow them. They climb to the high pastures in summer and come down to the lower slopes and
     * valleys in winter, ahead of the deep snow. The ancestor of the goat (figures from memory).
     */
    val WILD_GOAT = Species(
        id = 8, name = "wild goat", massKg = 40f,
        // (Field counts: bezoar goats some 1-5 on their crags, from memory.)
        fieldKm2 = 0.3f..5f,
        fearOfPeople = 0.75f, boldShare = 0.05f,
        diet = Diet(grass = 0.5f, browse = 0.5f),
        groupSize = 10, maxGroupSize = 50,
        dailyTravelKm = 5f, maxDailyTravelKm = 25f, senseKm = 3f,
        daysWithoutWater = 3, waterFromFood = 0.6f,
        birthMonth = 4, birthMonths = 1, youngPerFemale = 1.3f, yearsToMaturity = 2f,
        backgroundMortality = 0.1f, firstYearLost = HOOFED_YOUNG_LOST, snowLimitMm = 100f,
        products = Products(meat = 18f, fat = 1.5f, hide = 3f, bone = 5f, sinew = 0.4f, hornOrAntler = 3f),
        comfortC = -15f..38f, winterCoat = true,
        speedKmh = 50f,
        foresight = 0.6f,
        domestication = floatArrayOf(0.8f, 0.6f, 0.7f, 0.6f, 0.6f, 0.8f),
        milked = true, // became the goat
        habitat = Habitat(cover = -0.2f, rugged = 0.8f),
        defence = 0.1f,
        activity = floatArrayOf(0.3f, 0.4f, 0.2f, 0.1f),
        shelters = setOf(Niches.Shelter.CRAG),
        kin = Kin.RUMINANTS, dampTolerance = 0.2f, climbing = 0.9f,
    )

    /**
     * Wild horse: herds of cold steppe and open grassland, pawing through snow to winter grass; the
     * future mount. A family band of a stallion and his mares, roaming far between grazing and water.
     */
    val HORSE = Species(
        id = 9, name = "wild horse", massKg = 300f,
        // (Field counts: Przewalski's horses some 0.1-0.5 where set back, from memory.)
        fieldKm2 = 0.05f..1f,
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
        kin = Kin.EQUIDS, dampTolerance = 0.4f,
    )

    /**
     * Mouflon: wild sheep of rolling hills, open slopes and uplands, less of a cliff-dweller than the wild goat; the
     * ancestor of the sheep. (The argali, the great wild sheep of the Central Asian plateaus, is the same slot farther
     * east, and left out.)
     */
    val MOUFLON = Species(
        id = 10, name = "mouflon", massKg = 40f,
        // (Field counts: from memory: a few to ten a km² on good ground.)
        fieldKm2 = 0.3f..10f,
        fearOfPeople = 0.7f, boldShare = 0.06f,
        diet = Diet(grass = 0.8f, browse = 0.2f),
        groupSize = 10, maxGroupSize = 40,
        dailyTravelKm = 5f, maxDailyTravelKm = 25f, senseKm = 3f,
        daysWithoutWater = 3, waterFromFood = 0.6f,
        birthMonth = 5, birthMonths = 1, youngPerFemale = 1.2f, yearsToMaturity = 2f,
        backgroundMortality = 0.12f, firstYearLost = HOOFED_YOUNG_LOST, snowLimitMm = 80f,
        products = Products(meat = 18f, fat = 2f, hide = 3f, bone = 5f, sinew = 0.3f, hornOrAntler = 2f),
        comfortC = -20f..32f, winterCoat = true,
        speedKmh = 55f,
        foresight = 0.5f,
        domestication = floatArrayOf(0.8f, 0.6f, 0.7f, 0.6f, 0.6f, 0.8f),
        milked = true, // became the sheep
        habitat = Habitat(cover = -0.6f, rugged = 0.6f),
        defence = 0.1f,
        activity = floatArrayOf(0.3f, 0.4f, 0.2f, 0.1f),
        shelters = setOf(Niches.Shelter.CRAG),
        kin = Kin.RUMINANTS, dampTolerance = 0.2f, climbing = 0.6f,
    )


    // ---------------------------------------------------------------- the hot, dry south

    /**
     * Arabian oryx: open gravel plains and dunes; barely drinks, living on dew and the water in
     * plants, and tracks distant rain. Its long horns make adults dangerous to tackle.
     */
    val ORYX = Species(
        id = 11, name = "oryx", massKg = 70f,
        // (Field counts: Arabian oryx some 0.01-0.1, more on better ground, from memory.)
        fieldKm2 = 0.01f..0.5f,
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
        kin = Kin.RUMINANTS, dampTolerance = 0f,
    )

    /**
     * Arabian ostrich: a big flightless bird of the desert steppe, picking green shoots, flowers,
     * seeds and fruit; outruns almost everything and kicks hard. Large clutches, but a pair rears about one chick a year.
     */
    val OSTRICH = Species(
        id = 12, name = "ostrich", massKg = 110f,
        // (Field counts: Maasai ostrich 0.03-0.08 around Amboseli and Natron (African Wildlife Foundation surveys, 2016).)
        fieldKm2 = 0.02f..0.5f,
        // A picky feeder on green shoots, flowers, seeds and fruit, not a bulk grazer; it does not dig (the seeds and flowers
        // are counted with the fruit).
        diet = Diet(grass = 0.2f, browse = 0.4f, fruit = 0.4f),
        groupSize = 5, maxGroupSize = 30,
        dailyTravelKm = 15f, maxDailyTravelKm = 50f, senseKm = 5f,
        daysWithoutWater = 7, waterFromFood = 0.8f,
        // A hen lays 10-20 eggs, some of them pushed out of the nest the major hen sits; a nest unrobbed hatches some six
        // chicks a hen. The nest lies 42 days on open ground, where most are robbed ([nestDays], [HexFauna.hatching]).
        birthMonth = 5, birthMonths = 3, youngPerFemale = 6f, yearsToMaturity = 3f, nestDays = 42f,
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
        dampTolerance = 0.3f,
    )


    /**
     * Wild dromedary: a browser of desert and thorn scrub that goes two weeks without drinking and
     * then drinks a hundred litres; the future beast of burden of the desert routes.
     */
    val DROMEDARY = Species(
        id = 13, name = "dromedary", massKg = 450f,
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
        // (Camels carry the stomach worms of the cud-chewers.)
        kin = Kin.RUMINANTS, dampTolerance = 0f,
    )

    /**
     * Elephant: the Asian elephant of the Syrian and Mesopotamian rivers, hunted out by the Iron Age; the one beast
     * bigger than the hippo, mixing grass and browse and breaking down the shrubs and young trees it browses. It drinks
     * every day or two and keeps near water, bears little cold, and fears nothing grown; its calves fall to lions.
     * A calf every four or five years, grown at a dozen. (Figures from memory of the Asian elephant.)
     */
    val ELEPHANT = Species(
        id = 14, name = "elephant", massKg = 3000f,
        fearOfPeople = 0.4f, boldShare = 0.05f,
        diet = Diet(grass = 0.5f, browse = 0.4f, fruit = 0.1f),
        groupSize = 8, maxGroupSize = 20,
        dailyTravelKm = 8f, maxDailyTravelKm = 30f, senseKm = 5f,
        daysWithoutWater = 2, waterFromFood = 0.3f,
        birthMonth = 0, birthMonths = 12, youngPerFemale = 0.22f, yearsToMaturity = 12f,
        backgroundMortality = 0.03f, firstYearLost = 0.15f, snowLimitMm = 60f,
        products = Products(meat = 1500f, fat = 150f, hide = 300f, bone = 500f, sinew = 10f, hornOrAntler = 15f),
        comfortC = 5f..40f,
        speedKmh = 25f,
        foresight = 0.8f,
        domestication = floatArrayOf(0.9f, 0.05f, 0.1f, 0.4f, 0.5f, 0.7f), // tamed for work, never bred on
        habitat = Habitat(cover = 0.3f, rugged = -0.5f, wet = 0.5f),
        defence = 1f,
        // (Elephants ferment in the hindgut and pass much of their food half digested: they eat their fill of poor forage.)
        hindgut = true,
        activity = floatArrayOf(0.3f, 0.2f, 0.25f, 0.25f),
        shelters = setOf(Niches.Shelter.WATER),
        swimKm = 20f,
        kin = Kin.NONE, dampTolerance = 0.8f,
    )

    /**
     * Asiatic cheetah: a sprinter of open plains that runs down gazelle and young antelope in the
     * open; it loses its kills to anything bigger and cannot hunt in cover or broken ground.
     */
    val CHEETAH = Species(
        id = 20, name = "cheetah", massKg = 45f,
        // (Field counts: from memory: some 0.005-0.02.)
        fieldKm2 = 0.001f..0.02f,
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
        defence = 0.3f, // (a cheetah is built to run, not to fight; it gives up its kill to any rival; an estimate)
        domestication = floatArrayOf(0.2f, 0.4f, 0.0f, 0.5f, 0.3f, 0.2f), // tamed for the hunt, never bred
        habitat = Habitat(cover = -0.8f, rugged = -0.5f),
        stalking = 0.3f,
        ferocity = 0.6f,
        // (It lies up in thickets and among rocks; its cubs are hidden in tall grass and cover and moved every few days.)
        shelters = setOf(Niches.Shelter.THICKET, Niches.Shelter.CRAG), hidesYoung = true,
        // (Cheetahs prefer prey of 23-56 kg (Hayward et al. 2006) and take up to some 135 (Clements et al. 2014).)
        preyPreferredUpTo = 1.25f, preyUpTo = 3f,
        startKm2PerAnimal = 100f,
        activity = floatArrayOf(0.25f, 0.55f, 0.15f, 0.05f),
        swimKm = 1f,
        // (Lions take their kills and kill their cubs; Durant 1998 has cheetahs keeping off lion ground.)
        keepsOff = LION,
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
        id = 21, name = "people", massKg = 55f,
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

    private val herbivores = listOf(
        GAZELLE, DEER, BOAR, AUROCHS, ONAGER, HIPPO, BEAR, ELK, WILD_GOAT, HORSE, MOUFLON, ORYX, OSTRICH, DROMEDARY, ELEPHANT,
        ROE_DEER,
    )
    // (A new kind takes the next free [Species.id], so every kind keeps its index, and saved networks their identities.)
    val predators = listOf(WOLF, LION, LEOPARD, HYENA, JACKAL, CHEETAH, HUMAN, LYNX)

    /**
     * Plant-eaters that follow the seasons over long distances; the others live out their lives in small home ranges.
     * They live as residents ([resident]) as the others do; the set keeps their place at the end of [HexFauna.kinds] and
     * the learned movers' "migratory" input.
     */
    val migratory = setOf(GAZELLE, WILD_GOAT, HORSE, ONAGER, ORYX, OSTRICH, DROMEDARY)

    /**
     * Hunters that live everywhere they can on small home ranges, kept as
     * numbers per place like the plant-eaters rather than as packs that choose where to go
     * (cheaper, and the same rules of eating, breeding and dying): golden jackals and striped hyenas, lone
     * foragers that did nothing as packs that residents do not (Olli 2026-10-31, on packs' cost against what they add,
     * review/2026-10-31-mobile-predators-worth.md). Leopards stay packs: as residents nothing held them but the
     * breeding spacing, and they rose some fivefold in ten years (review/2026-10-31-ab-residents.md). (They stay in
     * [predators], so every kind keeps its index.)
     */
    val residentHunters by lazy { setOf(JACKAL, HYENA, LYNX) }

    /**
     * Hunters that live both ways (docs/design/roaming-packs.md, Olli 2026-11-02): most settled on their ground as residents,
     * kept as numbers per place, and the rest roaming as packs without ground, which the learned mover moves. Settled ones'
     * grown young leave as roamers and roamers settle where there is room, so the share roaming comes out of the two flows.
     * (Until they can leave and settle, their residents start at none and stay so: [HexFauna] seeds none and brings none in.)
     */
    val roamingResidents by lazy { setOf(WOLF, LION) }

    /** Kinds kept as numbers per place, the residents: every plant-eater, the [residentHunters] and the [roamingResidents]. */
    fun resident(s: Species) = !s.carnivore || s in residentHunters || s in roamingResidents

    /**
     * Residents that are rivals of the other beasts of prey as well as their game: the [residentHunters] and the brown bear.
     * The resident hunters keep out of the way of a much stronger party ([HexFauna.strongest]). (Bears and hyenas rob a
     * pack's kills they come on by scent: [scavengedAtKill].)
     */
    val residentRivals by lazy { setOf(JACKAL, HYENA, LYNX, BEAR) }

    /**
     * Beasts of prey, the hunters and the bear: other hunters kill them as rivals and kill their young ([Danger.STRIFE],
     * the lions' killing), but do not live on them as game.
     */
    fun beastOfPrey(s: Species) = s.carnivore || s in residentRivals

    /** Kinds that climb crags and screes others cannot cross. */
    val climbers by lazy { all.filter { it.climbing >= 0.5f }.toSet() }

    /**
     * How far a kind will swim open water, km: elk, hippos, red deer, boar and bears cross straits of
     * ten or more; most others a few; people as other walkers do (Olli 2026-11-21: the animals' rules for crossing water
     * are people's too; boats go further, [boatEdges]). From memory of the record, mild.
     */
    fun swimKm(s: Species): Float = swimByIndex[s.index]
    private val swimByIndex by lazy { FloatArray(all.size) { i -> val s = all[i]; s.swimKm.takeIf { it >= 0f } ?: when (travel(s)) {
        HexMap.Travel.SWIM -> 8f; HexMap.Travel.CLIMB -> 2f; else -> 3f } } }

    /** How a kind crosses hex borders: climbers over crags, big plant-eaters swim rivers, the rest walk. */
    fun travel(s: Species) = if (s in climbers) HexMap.Travel.CLIMB
        else if (!s.carnivore && s.massKg > 60f) HexMap.Travel.SWIM else HexMap.Travel.FOOT
    /** Every kind, in [Species.id] order (so `all[s.index] === s`). */
    val all = (herbivores + predators).sortedBy { it.id }
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
