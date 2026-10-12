package inflation.ecology

import kotlin.math.pow

/** What a species eats, as shares of its intake. Shares sum to 1. */
data class Diet(
    val grass: Float = 0f,
    val browse: Float = 0f,
    /** Dug and gleaned plant food: bulbs, roots, tubers, fallen acorns and nuts. */
    val roots: Float = 0f,
    /** Fruit, berries, seeds and nuts while they last, summer to autumn. */
    val fruit: Float = 0f,
    val meat: Float = 0f,
    val smallPrey: Float = 0f,
    val carrion: Float = 0f,
)

/**
 * Where a species likes to be, each from -1 (avoids) to 1 (seeks): [cover] (forest, thicket, broken
 * ground), [rugged] (steep, rocky terrain) and [wet] (marsh, floodplain, damp ground).
 */
data class Habitat(val cover: Float = 0f, val rugged: Float = 0f, val wet: Float = 0f)

/** Raw materials an adult yields, kg. */
data class Products(
    val meat: Float,
    val fat: Float,
    val hide: Float,
    val bone: Float,
    val sinew: Float,
    val hornOrAntler: Float = 0f,
    val fur: Boolean = false,
)

/**
 * A species as data. Most physiology follows from body mass by allometry (see [Allometry]), so
 * a record holds what cannot be derived: diet, social life, breeding, tolerances and products.
 * Numbers are mid-range values from the zoological literature, recalled rather than looked up;
 * they are to be checked against sources before being treated as calibrated.
 */
class Species(
    val name: String,
    /** Adult body mass, kg. */
    val massKg: Float,
    val diet: Diet,
    /** Group size: typical and the size at which groups split. */
    val groupSize: Int,
    val maxGroupSize: Int,
    /** Distance a group covers on a normal day and at most, km. */
    val dailyTravelKm: Float,
    val maxDailyTravelKm: Float,
    /** How far the group perceives forage, water and others, km. */
    val senseKm: Float,
    /** Days it can go without drinking. */
    val daysWithoutWater: Int,
    /**
     * Share of its water it can get from fresh food and dew, 0..1. Browsers and desert-adapted
     * antelope barely drink while plants are green; cattle and wild asses must drink every day or two.
     */
    val waterFromFood: Float,
    /** Birth season: first month (0-based) and length in months. */
    val birthMonth: Int,
    val birthMonths: Int,
    /** Litters a year in good years: small breeders fit in several. */
    val littersPerYear: Int = 1,
    /** Young per adult female per year in good condition. */
    val youngPerFemale: Float,
    val yearsToMaturity: Float,
    /** Adult mortality per year from causes not modelled (age, accident, disease); predation and hunger are modelled. */
    val backgroundMortality: Float,
    /**
     * Extra yearly mortality of the young from causes not modelled (cold, wet, disease, falls), on
     * top of the adults'. Species with big litters lose most of them: half of all piglets die in their first year.
     */
    val youngMortality: Float = backgroundMortality,
    /** Snow depth (mm water equivalent) that stops it feeding and slows it badly. */
    val snowLimitMm: Float,
    /** Distance at which it notices a predator and flees, km. */
    val flightKm: Float,
    val dangerToHumans: Float,
    val products: Products,
    val habitat: Habitat = Habitat(),
    /** How well adults fight off or outlast predators, 0 (not at all) to 1 (adults practically safe). */
    val defence: Float = 0f,
    /**
     * For predators: how much cover helps it get close, 0 (a coursing hunter like the wolf, which
     * runs prey down in the open) to 1 (a stalker like the leopard, which needs cover to strike).
     */
    val stalking: Float = 0f,
    /**
     * How well it digests coarse fibre (straw, twigs) relative to a ruminant or horse of its size:
     * pigs, with a simple stomach, get far less from it.
     */
    val fibreDigestion: Float = 1f,
    /**
     * For predators: fighting weight per kg compared with a wolf, for fights over carcasses and
     * ground: a hyena clan bites above its weight, a jackal below.
     */
    val ferocity: Float = 1f,
    /** For predators: distance within which two groups of the species meet and contest their ground, km. */
    val territoryKm: Float = 3f,
    /** For predators: land per animal at the start, km². */
    val startKm2PerAnimal: Float = 0f,
    /**
     * Weekly mean air temperatures it is comfortable in, °C. Outside them it needs more food and
     * the young and weak die; a [winterCoat] stretches the cold end as it grows in.
     */
    val comfortC: ClosedFloatingPointRange<Float> = -10f..30f,
    /** Grows a winter coat in the cold months, worth about 10 °C of cold, shed in spring. */
    val winterCoat: Boolean = false,
    /** Top running speed, km/h: in a chase, a courser must outrun its prey; a stalker needs only surprise. */
    val speedKmh: Float = 60f,
    /** Keeps within this distance of drinking water, km (hippos graze at night near their river). */
    val maxWaterKm: Float = Float.POSITIVE_INFINITY,
    /**
     * How keenly it senses what is coming, 0..1: distant rain, a pressure drop before a storm, the
     * tremors before an eruption, rising water upstream.
     */
    val foresight: Float = 0.4f,
    /** Sleeps through the cold months in a den on its fat, needing a quarter of its food. */
    val hibernates: Boolean = false,
    /** Breeds as a pack: one litter a year from the breeding pair, of this size in good condition. */
    val packLitter: Int = 0,
    /** Diamond's criteria, 0..1 each: diet breadth, growth, captive breeding, temperament, calm, hierarchy. */
    val domestication: FloatArray,
    /** People: they hunt and gather by skill learned in practice (see [AnimalGroup.craft]), cook, and remember. */
    val human: Boolean = false,
    /** Daily food, kg of prey or plant food, where the allometric rule does not fit (people, who cook). */
    val intakeKg: Float = 0f,
    /** Hangs about people's camps for scraps and keeps clear of trouble with them: wolves (future dogs), jackals. */
    val followsPeople: Boolean = false,
) {
    /** Position in [Catalogue.all], for array lookups. */
    var index = -1
        internal set
    /** Mostly eats animals. */
    val carnivore: Boolean get() = diet.meat + diet.smallPrey + diet.carrion > 0.5f
    /**
     * Days of food it can carry as fat: bigger animals store more, about 8 M^0.25 days (a gazelle
     * some 17, an aurochs 42).
     */
    val maxFatDays: Float get() = 8f * massKg.pow(0.25f) * if (hibernates) 4f else 1f
    /** Dry-matter (or meat) intake per adult per day, kg. */
    val dailyIntakeKg: Float get() = if (intakeKg > 0f) intakeKg else Allometry.dailyIntakeKg(massKg, carnivore)
}

object Allometry {
    /**
     * Food intake grows with metabolic mass, M^0.75 (Kleiber). Herbivores eat about 0.065 M^0.75
     * kg of dry plant matter a day (a 20 kg gazelle ~0.6 kg). Carnivores need about 0.2 M^0.75 kg
     * of prey body mass a day, counting the parts they leave (a 30 kg wolf ~2.6 kg, in line with
     * field kill rates of roughly 0.1 kg of prey per kg of wolf per day). Rules of thumb, from memory.
     */
    fun dailyIntakeKg(massKg: Float, carnivore: Boolean): Float =
        (if (carnivore) 0.2f else 0.065f) * massKg.pow(0.75f)

    /**
     * Damuth's rule: mammal population density falls with body mass, about 10^4.23 M^-0.75
     * individuals per km² for herbivores with M in grams (from memory; to be checked). Real
     * densities scatter an order of magnitude around it.
     */
    fun herbivoreDensityPerKm2(massKg: Float): Float = 10f.pow(4.23f) * (massKg * 1000f).pow(-0.75f)
}

object Catalogue {
    /** Mountain gazelle: the staple prey of Natufian hunters. Mixed grazer and browser of open land. */
    val GAZELLE = Species(
        name = "gazelle", massKg = 20f,
        diet = Diet(grass = 0.6f, browse = 0.4f),
        groupSize = 12, maxGroupSize = 40,
        dailyTravelKm = 6f, maxDailyTravelKm = 30f, senseKm = 4f,
        daysWithoutWater = 5, waterFromFood = 0.8f,
        birthMonth = 1, birthMonths = 2, youngPerFemale = 1f, yearsToMaturity = 1f,
        backgroundMortality = 0.08f, snowLimitMm = 40f, flightKm = 1f, dangerToHumans = 0f,
        products = Products(meat = 9f, fat = 0.5f, hide = 1.2f, bone = 2.5f, sinew = 0.2f, hornOrAntler = 0.3f),
        comfortC = 5f..36f, 
        speedKmh = 80f,
        foresight = 0.6f,
        domestication = floatArrayOf(0.6f, 0.6f, 0.2f, 0.5f, 0.05f, 0.2f), // panics and leaps fences
        habitat = Habitat(cover = -1f, wet = -0.5f),
    )

    /** Roe deer: small, solitary or in families, browsing woodland edges and thickets. */
    val ROE_DEER = Species(
        name = "roe deer", massKg = 25f,
        diet = Diet(grass = 0.2f, browse = 0.7f, fruit = 0.1f),
        groupSize = 3, maxGroupSize = 6,
        dailyTravelKm = 3f, maxDailyTravelKm = 20f, senseKm = 1f,
        daysWithoutWater = 4, waterFromFood = 0.9f,
        birthMonth = 2, birthMonths = 1, youngPerFemale = 1.6f, yearsToMaturity = 1f,
        backgroundMortality = 0.12f, snowLimitMm = 150f, flightKm = 0.2f, dangerToHumans = 0f,
        products = Products(meat = 12f, fat = 0.5f, hide = 1.5f, bone = 3f, sinew = 0.2f, hornOrAntler = 0.3f),
        comfortC = -8f..25f, winterCoat = true,
        speedKmh = 70f,
        domestication = floatArrayOf(0.4f, 0.6f, 0.1f, 0.3f, 0.1f, 0.1f),
        habitat = Habitat(cover = 0.6f),
    )

    /** Persian fallow deer: herds in open woodland and parkland; common in Levantine Epipalaeolithic sites. */
    val FALLOW_DEER = Species(
        name = "fallow deer", massKg = 65f,
        diet = Diet(grass = 0.5f, browse = 0.4f, fruit = 0.1f),
        groupSize = 6, maxGroupSize = 25,
        dailyTravelKm = 4f, maxDailyTravelKm = 25f, senseKm = 2f,
        daysWithoutWater = 3, waterFromFood = 0.6f,
        birthMonth = 2, birthMonths = 1, youngPerFemale = 0.9f, yearsToMaturity = 1.5f,
        backgroundMortality = 0.1f, snowLimitMm = 150f, flightKm = 0.4f, dangerToHumans = 0f,
        products = Products(meat = 32f, fat = 2f, hide = 4f, bone = 7f, sinew = 0.5f, hornOrAntler = 2f),
        comfortC = -5f..28f, winterCoat = true,
        speedKmh = 65f,
        domestication = floatArrayOf(0.5f, 0.5f, 0.4f, 0.4f, 0.3f, 0.4f), // kept in parks, never domesticated
        habitat = Habitat(cover = 0.3f),
        defence = 0.1f,
    )

    /** Red deer: woodland and hills, grazing and browsing, herds split by sex outside the rut. */
    val RED_DEER = Species(
        name = "red deer", massKg = 150f,
        diet = Diet(grass = 0.5f, browse = 0.5f),
        groupSize = 8, maxGroupSize = 30,
        dailyTravelKm = 6f, maxDailyTravelKm = 40f, senseKm = 3f,
        daysWithoutWater = 3, waterFromFood = 0.5f,
        birthMonth = 2, birthMonths = 1, youngPerFemale = 0.8f, yearsToMaturity = 2.5f,
        backgroundMortality = 0.08f, snowLimitMm = 300f, flightKm = 0.5f, dangerToHumans = 0.05f,
        products = Products(meat = 75f, fat = 6f, hide = 9f, bone = 18f, sinew = 1f, hornOrAntler = 5f),
        comfortC = -12f..25f, winterCoat = true,
        speedKmh = 70f,
        domestication = floatArrayOf(0.5f, 0.4f, 0.3f, 0.3f, 0.2f, 0.3f),
        habitat = Habitat(cover = 0.5f, rugged = 0.2f),
        defence = 0.25f,
    )

    /** Nubian or Caucasian-type ibex: cliffs and steep rock, where nothing can follow it. */
    val IBEX = Species(
        name = "ibex", massKg = 60f,
        diet = Diet(grass = 0.6f, browse = 0.4f),
        groupSize = 8, maxGroupSize = 25,
        dailyTravelKm = 4f, maxDailyTravelKm = 20f, senseKm = 3f,
        daysWithoutWater = 3, waterFromFood = 0.6f,
        birthMonth = 1, birthMonths = 1, youngPerFemale = 1.1f, yearsToMaturity = 2.5f,
        backgroundMortality = 0.1f, snowLimitMm = 300f, flightKm = 0.5f, dangerToHumans = 0f,
        products = Products(meat = 28f, fat = 1.5f, hide = 4f, bone = 7f, sinew = 0.5f, hornOrAntler = 4f),
        comfortC = -12f..30f, winterCoat = true,
        speedKmh = 55f,
        foresight = 0.5f,
        domestication = floatArrayOf(0.7f, 0.5f, 0.4f, 0.4f, 0.4f, 0.6f), // its cousin became the goat
        habitat = Habitat(cover = -0.3f, rugged = 1f),
        defence = 0.1f,
    )

    /** Wild boar: omnivore of forest, thicket and marsh; roots, acorns, bulbs; large litters. */
    val BOAR = Species(
        name = "wild boar", massKg = 80f,
        diet = Diet(grass = 0.2f, browse = 0.1f, roots = 0.5f, fruit = 0.2f),
        groupSize = 6, maxGroupSize = 20,
        dailyTravelKm = 5f, maxDailyTravelKm = 20f, senseKm = 1.5f,
        daysWithoutWater = 2, waterFromFood = 0.4f,
        birthMonth = 1, birthMonths = 2, littersPerYear = 2, youngPerFemale = 4f, yearsToMaturity = 1.5f,
        backgroundMortality = 0.2f, youngMortality = 0.6f,
        snowLimitMm = 200f, flightKm = 0.2f, dangerToHumans = 0.3f,
        products = Products(meat = 45f, fat = 8f, hide = 5f, bone = 9f, sinew = 0.5f, hornOrAntler = 0.5f),
        comfortC = -8f..30f, winterCoat = true,
        speedKmh = 45f,
        foresight = 0.5f,
        domestication = floatArrayOf(0.9f, 0.9f, 0.8f, 0.4f, 0.6f, 0.6f), // became the pig
        habitat = Habitat(cover = 0.8f, wet = 0.6f),
        defence = 0.6f,
        fibreDigestion = 0.5f,
    )

    /** Aurochs: huge wild cattle of wet lowlands, floodplains and open woodland; formidable when cornered. */
    val AUROCHS = Species(
        name = "aurochs", massKg = 750f,
        diet = Diet(grass = 0.8f, browse = 0.2f),
        groupSize = 15, maxGroupSize = 40,
        dailyTravelKm = 8f, maxDailyTravelKm = 30f, senseKm = 2f,
        daysWithoutWater = 2, waterFromFood = 0.2f,
        birthMonth = 1, birthMonths = 2, youngPerFemale = 0.7f, yearsToMaturity = 3f,
        backgroundMortality = 0.06f, snowLimitMm = 300f, flightKm = 0.3f, dangerToHumans = 0.5f,
        products = Products(meat = 370f, fat = 40f, hide = 40f, bone = 80f, sinew = 4f, hornOrAntler = 6f),
        comfortC = -12f..28f, winterCoat = true,
        speedKmh = 50f,
        foresight = 0.6f,
        domestication = floatArrayOf(0.8f, 0.5f, 0.7f, 0.2f, 0.4f, 0.9f), // became cattle
        habitat = Habitat(cover = 0.1f, rugged = -0.5f, wet = 0.8f),
        defence = 0.9f,
    )

    /** Onager (Asiatic wild ass): fast grazer of steppe and semi-desert, needs to drink often. */
    val ONAGER = Species(
        name = "onager", massKg = 230f,
        diet = Diet(grass = 0.9f, browse = 0.1f),
        groupSize = 8, maxGroupSize = 30,
        dailyTravelKm = 15f, maxDailyTravelKm = 50f, senseKm = 4f,
        daysWithoutWater = 3, waterFromFood = 0.3f,
        birthMonth = 2, birthMonths = 2, youngPerFemale = 0.7f, yearsToMaturity = 2f,
        backgroundMortality = 0.05f, snowLimitMm = 150f, flightKm = 1f, dangerToHumans = 0.1f,
        products = Products(meat = 110f, fat = 8f, hide = 15f, bone = 25f, sinew = 2f),
        comfortC = -8f..38f, winterCoat = true,
        speedKmh = 70f,
        foresight = 0.7f,
        domestication = floatArrayOf(0.7f, 0.4f, 0.3f, 0.1f, 0.2f, 0.4f), // too wild to tame; the donkey came from Africa
        habitat = Habitat(cover = -0.8f, rugged = -0.3f, wet = -0.1f), // shuns marsh, not the water's edge
        defence = 0.4f,
    )

    /**
     * Hippopotamus: lived in the Levant's coastal rivers into historical times. Spends the day in
     * water and grazes the banks at night; adults fear nothing, calves fall to lions and crocodiles.
     */
    val HIPPO = Species(
        name = "hippo", massKg = 1400f,
        diet = Diet(grass = 0.95f, browse = 0.05f),
        groupSize = 10, maxGroupSize = 30,
        dailyTravelKm = 4f, maxDailyTravelKm = 10f, senseKm = 1f,
        daysWithoutWater = 1, waterFromFood = 0f,
        birthMonth = 3, birthMonths = 4, youngPerFemale = 0.4f, yearsToMaturity = 5f,
        backgroundMortality = 0.04f, snowLimitMm = 20f, flightKm = 0.1f, dangerToHumans = 0.9f,
        products = Products(meat = 600f, fat = 80f, hide = 100f, bone = 150f, sinew = 5f, hornOrAntler = 3f),
        comfortC = 14f..36f, 
        speedKmh = 30f,
        foresight = 0.3f,
        domestication = floatArrayOf(0.6f, 0.2f, 0.2f, 0.0f, 0.1f, 0.3f),
        habitat = Habitat(cover = -0.2f, rugged = -1f, wet = 1f),
        defence = 1f,
        maxWaterKm = 1.5f,
    )

    /**
     * Syrian brown bear: an omnivore of wooded hills, living mostly on fruit, nuts, roots and green
     * plants, with insects, carrion and the odd fawn. Fattens in autumn and sleeps out the winter.
     */
    val BEAR = Species(
        name = "brown bear", massKg = 150f,
        diet = Diet(grass = 0.15f, browse = 0.1f, roots = 0.25f, fruit = 0.3f, smallPrey = 0.1f, carrion = 0.1f),
        groupSize = 2, maxGroupSize = 4,
        dailyTravelKm = 8f, maxDailyTravelKm = 30f, senseKm = 4f,
        daysWithoutWater = 4, waterFromFood = 0.6f,
        birthMonth = 11, birthMonths = 1, youngPerFemale = 1f, yearsToMaturity = 4f,
        backgroundMortality = 0.05f, snowLimitMm = 600f, flightKm = 0.3f, dangerToHumans = 0.7f,
        products = Products(meat = 70f, fat = 30f, hide = 12f, bone = 15f, sinew = 1f, fur = true),
        comfortC = -15f..28f, winterCoat = true,
        speedKmh = 50f,
        foresight = 0.5f,
        domestication = floatArrayOf(0.9f, 0.2f, 0.2f, 0.1f, 0.2f, 0.1f),
        habitat = Habitat(cover = 0.5f, rugged = 0.4f),
        defence = 0.8f,
        hibernates = true,
        startKm2PerAnimal = 60f,
    )

    /**
     * Grey wolf of the southern, Arabian-Levantine type: smaller than northern wolves and living in
     * small packs. Eats much small prey and carrion.
     */
    val WOLF = Species(
        name = "wolf", massKg = 22f,
        diet = Diet(meat = 0.6f, smallPrey = 0.25f, carrion = 0.15f),
        groupSize = 3, maxGroupSize = 8,
        dailyTravelKm = 15f, maxDailyTravelKm = 50f, senseKm = 8f,
        daysWithoutWater = 3, waterFromFood = 0.5f,
        birthMonth = 1, birthMonths = 1, youngPerFemale = 2.5f, yearsToMaturity = 2f,
        backgroundMortality = 0.15f, snowLimitMm = 400f, flightKm = 0.3f, dangerToHumans = 0.2f,
        products = Products(meat = 9f, fat = 0.4f, hide = 1.5f, bone = 2.2f, sinew = 0.2f, fur = true),
        comfortC = -20f..30f, winterCoat = true,
        speedKmh = 60f,
        foresight = 0.5f,
        domestication = floatArrayOf(0.7f, 0.5f, 0.6f, 0.4f, 0.6f, 0.9f), // became the dog
        packLitter = 4,
        stalking = 0.3f,
        startKm2PerAnimal = 400f,
        followsPeople = true,
    )

    /**
     * Lion: still in the Levant at the time, in prides that take the largest prey, aurochs
     * included, and drive other predators off their kills.
     */
    val LION = Species(
        name = "lion", massKg = 150f,
        diet = Diet(meat = 0.9f, carrion = 0.1f),
        groupSize = 5, maxGroupSize = 12,
        dailyTravelKm = 12f, maxDailyTravelKm = 30f, senseKm = 8f,
        daysWithoutWater = 4, waterFromFood = 0.6f,
        birthMonth = 2, birthMonths = 1, youngPerFemale = 0f, yearsToMaturity = 3f,
        backgroundMortality = 0.1f, snowLimitMm = 200f, flightKm = 0.2f, dangerToHumans = 0.9f,
        products = Products(meat = 60f, fat = 5f, hide = 8f, bone = 18f, sinew = 1f, fur = true),
        comfortC = 0f..38f, 
        speedKmh = 60f,
        domestication = floatArrayOf(0.1f, 0.4f, 0.2f, 0.0f, 0.3f, 0.5f),
        habitat = Habitat(cover = -0.2f, rugged = -0.3f),
        packLitter = 3,
        stalking = 0.7f,
        ferocity = 1.2f,
        territoryKm = 6f,
        startKm2PerAnimal = 400f,
    )

    /** Leopard: a solitary stalker of broken and wooded ground; caches kills away from bigger rivals. */
    val LEOPARD = Species(
        name = "leopard", massKg = 45f,
        diet = Diet(meat = 0.6f, smallPrey = 0.35f, carrion = 0.05f),
        groupSize = 2, maxGroupSize = 4,
        dailyTravelKm = 6f, maxDailyTravelKm = 20f, senseKm = 4f,
        daysWithoutWater = 6, waterFromFood = 0.8f,
        birthMonth = 3, birthMonths = 1, youngPerFemale = 0f, yearsToMaturity = 2.5f,
        backgroundMortality = 0.07f, snowLimitMm = 300f, flightKm = 0.3f, dangerToHumans = 0.4f,
        products = Products(meat = 18f, fat = 1f, hide = 3f, bone = 5f, sinew = 0.3f, fur = true),
        comfortC = -8f..35f, 
        speedKmh = 58f,
        domestication = floatArrayOf(0.1f, 0.4f, 0.1f, 0.0f, 0.2f, 0.0f),
        habitat = Habitat(cover = 0.7f, rugged = 0.6f),
        packLitter = 3,
        stalking = 1f,
        ferocity = 1.1f,
        territoryKm = 5f,
        startKm2PerAnimal = 400f,
    )

    /** Striped hyena: mostly a scavenger, cracking the bones others leave; also takes small prey and young. */
    val HYENA = Species(
        name = "striped hyena", massKg = 35f,
        diet = Diet(meat = 0.1f, smallPrey = 0.25f, carrion = 0.65f),
        groupSize = 2, maxGroupSize = 5,
        dailyTravelKm = 12f, maxDailyTravelKm = 40f, senseKm = 10f,
        daysWithoutWater = 4, waterFromFood = 0.6f,
        birthMonth = 2, birthMonths = 2, youngPerFemale = 0f, yearsToMaturity = 2f,
        backgroundMortality = 0.07f, snowLimitMm = 200f, flightKm = 0.5f, dangerToHumans = 0.1f,
        products = Products(meat = 14f, fat = 1f, hide = 2.5f, bone = 4f, sinew = 0.2f, fur = true),
        comfortC = 0f..38f, 
        speedKmh = 50f,
        domestication = floatArrayOf(0.8f, 0.3f, 0.2f, 0.3f, 0.3f, 0.2f),
        habitat = Habitat(rugged = 0.3f),
        packLitter = 3,
        stalking = 0.2f,
        ferocity = 1.4f,
        territoryKm = 5f,
        startKm2PerAnimal = 500f,
    )

    /** Golden jackal: pairs living on rodents, carrion and the young of gazelle and deer. */
    val JACKAL = Species(
        name = "jackal", massKg = 10f,
        diet = Diet(meat = 0.1f, smallPrey = 0.6f, carrion = 0.3f),
        groupSize = 2, maxGroupSize = 6,
        dailyTravelKm = 8f, maxDailyTravelKm = 25f, senseKm = 4f,
        daysWithoutWater = 4, waterFromFood = 0.6f,
        birthMonth = 2, birthMonths = 1, youngPerFemale = 0f, yearsToMaturity = 1f,
        backgroundMortality = 0.25f, snowLimitMm = 150f, flightKm = 0.5f, dangerToHumans = 0f,
        products = Products(meat = 4f, fat = 0.2f, hide = 0.6f, bone = 1f, sinew = 0.1f, fur = true),
        comfortC = -5f..38f, winterCoat = true,
        speedKmh = 45f,
        domestication = floatArrayOf(0.8f, 0.6f, 0.4f, 0.4f, 0.4f, 0.6f),
        habitat = Habitat(cover = 0.2f, wet = 0.2f),
        packLitter = 4,
        stalking = 0.4f,
        ferocity = 0.8f,
        territoryKm = 4f,
        startKm2PerAnimal = 200f,
        followsPeople = true,
    )

    // ---------------------------------------------------------------- the cold north

    /**
     * Elk (moose): a browser of wet northern forest, willow carr and lake shores, wading for water
     * plants in summer. Long legs carry it through deep snow; it suffers in summer heat above about
     * 15–20 °C. Adults kick hard enough to keep wolves at bay.
     */
    val ELK = Species(
        name = "elk", massKg = 400f,
        diet = Diet(grass = 0.1f, browse = 0.75f, roots = 0.15f),
        groupSize = 2, maxGroupSize = 5,
        dailyTravelKm = 4f, maxDailyTravelKm = 20f, senseKm = 1.5f,
        daysWithoutWater = 3, waterFromFood = 0.6f,
        birthMonth = 4, birthMonths = 1, youngPerFemale = 1.2f, yearsToMaturity = 2f,
        backgroundMortality = 0.07f, snowLimitMm = 900f, flightKm = 0.2f, dangerToHumans = 0.4f,
        products = Products(meat = 200f, fat = 15f, hide = 25f, bone = 45f, sinew = 3f, hornOrAntler = 15f),
        comfortC = -30f..17f, winterCoat = true,
        speedKmh = 55f,
        domestication = floatArrayOf(0.5f, 0.4f, 0.3f, 0.4f, 0.3f, 0.1f),
        habitat = Habitat(cover = 0.6f, rugged = -0.2f, wet = 0.8f),
        defence = 0.6f,
    )

    /**
     * Reindeer: herds of tundra and the alpine zone above the trees, digging through snow for
     * lichen and grazing the summer flush; the one deer people herd. Heat is its limit.
     */
    val REINDEER = Species(
        name = "reindeer", massKg = 100f,
        diet = Diet(grass = 0.6f, browse = 0.4f),
        groupSize = 20, maxGroupSize = 200,
        dailyTravelKm = 10f, maxDailyTravelKm = 50f, senseKm = 3f,
        daysWithoutWater = 4, waterFromFood = 0.7f,
        birthMonth = 4, birthMonths = 1, youngPerFemale = 0.8f, yearsToMaturity = 2f,
        backgroundMortality = 0.1f, snowLimitMm = 800f, flightKm = 0.4f, dangerToHumans = 0.05f,
        products = Products(meat = 45f, fat = 6f, hide = 6f, bone = 10f, sinew = 1f, hornOrAntler = 4f, fur = true),
        comfortC = -40f..13f, winterCoat = true,
        speedKmh = 75f,
        foresight = 0.6f,
        domestication = floatArrayOf(0.6f, 0.5f, 0.6f, 0.6f, 0.6f, 0.7f), // herded, barely changed
        habitat = Habitat(cover = -0.4f, rugged = 0.2f),
        defence = 0.2f,
    )

    /**
     * Wild horse: herds of cold steppe and open grassland, pawing through snow to winter grass; the
     * future mount. A family band of a stallion and his mares, roaming far between grazing and water.
     */
    val HORSE = Species(
        name = "wild horse", massKg = 300f,
        diet = Diet(grass = 0.9f, browse = 0.1f),
        groupSize = 10, maxGroupSize = 25,
        dailyTravelKm = 12f, maxDailyTravelKm = 50f, senseKm = 3f,
        daysWithoutWater = 2, waterFromFood = 0.3f,
        birthMonth = 3, birthMonths = 2, youngPerFemale = 0.7f, yearsToMaturity = 2.5f,
        backgroundMortality = 0.06f, snowLimitMm = 250f, flightKm = 0.8f, dangerToHumans = 0.1f,
        products = Products(meat = 140f, fat = 10f, hide = 18f, bone = 30f, sinew = 2f),
        comfortC = -25f..30f, winterCoat = true,
        speedKmh = 70f,
        foresight = 0.6f,
        domestication = floatArrayOf(0.8f, 0.5f, 0.6f, 0.5f, 0.6f, 0.8f), // became the horse
        habitat = Habitat(cover = -0.8f, rugged = -0.2f),
        defence = 0.4f,
    )

    /**
     * Mouflon: wild sheep of rolling hills and open slopes, less of a cliff-dweller than the ibex;
     * the ancestor of the sheep.
     */
    val MOUFLON = Species(
        name = "mouflon", massKg = 40f,
        diet = Diet(grass = 0.8f, browse = 0.2f),
        groupSize = 10, maxGroupSize = 40,
        dailyTravelKm = 5f, maxDailyTravelKm = 25f, senseKm = 3f,
        daysWithoutWater = 3, waterFromFood = 0.6f,
        birthMonth = 2, birthMonths = 1, youngPerFemale = 1.2f, yearsToMaturity = 2f,
        backgroundMortality = 0.12f, snowLimitMm = 200f, flightKm = 0.6f, dangerToHumans = 0f,
        products = Products(meat = 18f, fat = 2f, hide = 3f, bone = 5f, sinew = 0.3f, hornOrAntler = 2f),
        comfortC = -15f..32f, winterCoat = true,
        speedKmh = 55f,
        foresight = 0.5f,
        domestication = floatArrayOf(0.8f, 0.6f, 0.7f, 0.6f, 0.6f, 0.8f), // became the sheep
        habitat = Habitat(cover = -0.6f, rugged = 0.6f),
        defence = 0.1f,
    )

    // ---------------------------------------------------------------- the hot, dry south

    /**
     * Arabian oryx: open gravel plains and dunes; barely drinks, living on dew and the water in
     * plants, and tracks distant rain. Its long horns make adults dangerous to tackle.
     */
    val ORYX = Species(
        name = "oryx", massKg = 70f,
        diet = Diet(grass = 0.7f, browse = 0.3f),
        groupSize = 10, maxGroupSize = 30,
        dailyTravelKm = 10f, maxDailyTravelKm = 40f, senseKm = 4f,
        daysWithoutWater = 30, waterFromFood = 0.95f,
        birthMonth = 0, birthMonths = 6, youngPerFemale = 0.8f, yearsToMaturity = 2.5f,
        backgroundMortality = 0.08f, snowLimitMm = 20f, flightKm = 0.8f, dangerToHumans = 0.1f,
        products = Products(meat = 30f, fat = 2f, hide = 5f, bone = 8f, sinew = 0.6f, hornOrAntler = 2f),
        comfortC = 8f..45f,
        speedKmh = 60f,
        foresight = 0.8f,
        domestication = floatArrayOf(0.6f, 0.5f, 0.4f, 0.3f, 0.4f, 0.5f), // kept in Egyptian herds, never bred on
        habitat = Habitat(cover = -1f, rugged = -0.2f, wet = -0.8f),
        defence = 0.4f,
    )

    /**
     * Arabian ostrich: a big flightless bird of the desert steppe, picking green shoots, flowers,
     * seeds and fruit; outruns almost everything and kicks hard. Large clutches, but a pair rears about one chick a year.
     */
    val OSTRICH = Species(
        name = "ostrich", massKg = 110f,
        // A picky feeder on green shoots, flowers, seeds and fruit, not a bulk grazer.
        diet = Diet(grass = 0.2f, browse = 0.4f, roots = 0.1f, fruit = 0.3f),
        groupSize = 5, maxGroupSize = 30,
        dailyTravelKm = 15f, maxDailyTravelKm = 50f, senseKm = 5f,
        daysWithoutWater = 7, waterFromFood = 0.8f,
        birthMonth = 2, birthMonths = 3, youngPerFemale = 1.2f, yearsToMaturity = 3f,
        backgroundMortality = 0.07f, youngMortality = 0.7f,
        snowLimitMm = 30f, flightKm = 1f, dangerToHumans = 0.2f,
        products = Products(meat = 50f, fat = 3f, hide = 10f, bone = 10f, sinew = 1f),
        comfortC = 5f..45f,
        speedKmh = 70f,
        foresight = 0.6f,
        domestication = floatArrayOf(0.6f, 0.6f, 0.5f, 0.2f, 0.3f, 0.3f),
        habitat = Habitat(cover = -1f, wet = -0.5f),
        defence = 0.4f,
        fibreDigestion = 0.5f, // a bird's short gut gets little from straw
    )

    /** Bubal hartebeest: a grazer of dry grassland and semi-desert edges, in herds; kept by the Egyptians. */
    val HARTEBEEST = Species(
        name = "hartebeest", massKg = 150f,
        diet = Diet(grass = 0.95f, browse = 0.05f),
        groupSize = 12, maxGroupSize = 40,
        dailyTravelKm = 8f, maxDailyTravelKm = 40f, senseKm = 3f,
        daysWithoutWater = 3, waterFromFood = 0.4f,
        birthMonth = 1, birthMonths = 2, youngPerFemale = 0.8f, yearsToMaturity = 2f,
        backgroundMortality = 0.08f, snowLimitMm = 30f, flightKm = 0.6f, dangerToHumans = 0.05f,
        products = Products(meat = 70f, fat = 4f, hide = 9f, bone = 18f, sinew = 1f, hornOrAntler = 2f),
        comfortC = 5f..40f,
        speedKmh = 70f,
        domestication = floatArrayOf(0.6f, 0.5f, 0.4f, 0.3f, 0.4f, 0.5f),
        habitat = Habitat(cover = -0.8f, rugged = -0.3f),
        defence = 0.2f,
    )

    /**
     * Wild dromedary: a browser of desert and thorn scrub that goes two weeks without drinking and
     * then drinks a hundred litres; the future beast of burden of the desert routes.
     */
    val DROMEDARY = Species(
        name = "dromedary", massKg = 450f,
        diet = Diet(grass = 0.3f, browse = 0.7f),
        groupSize = 8, maxGroupSize = 20,
        dailyTravelKm = 15f, maxDailyTravelKm = 60f, senseKm = 4f,
        daysWithoutWater = 14, waterFromFood = 0.5f,
        birthMonth = 11, birthMonths = 3, youngPerFemale = 0.4f, yearsToMaturity = 4f,
        backgroundMortality = 0.05f, snowLimitMm = 40f, flightKm = 0.5f, dangerToHumans = 0.2f,
        products = Products(meat = 200f, fat = 40f, hide = 30f, bone = 50f, sinew = 3f),
        comfortC = 5f..48f,
        speedKmh = 60f,
        foresight = 0.7f,
        domestication = floatArrayOf(0.7f, 0.3f, 0.5f, 0.6f, 0.6f, 0.7f), // became the camel
        habitat = Habitat(cover = -0.8f, rugged = -0.3f, wet = -0.5f),
        defence = 0.6f,
    )

    /**
     * Asiatic cheetah: a sprinter of open plains that runs down gazelle and young antelope in the
     * open; it loses its kills to anything bigger and cannot hunt in cover or broken ground.
     */
    val CHEETAH = Species(
        name = "cheetah", massKg = 45f,
        diet = Diet(meat = 0.8f, smallPrey = 0.2f),
        groupSize = 2, maxGroupSize = 4,
        dailyTravelKm = 8f, maxDailyTravelKm = 30f, senseKm = 6f,
        daysWithoutWater = 10, waterFromFood = 0.8f,
        birthMonth = 2, birthMonths = 2, youngPerFemale = 0f, yearsToMaturity = 2f,
        backgroundMortality = 0.1f, snowLimitMm = 50f, flightKm = 0.3f, dangerToHumans = 0.05f,
        products = Products(meat = 20f, fat = 1f, hide = 3f, bone = 5f, sinew = 0.3f, fur = true),
        comfortC = 3f..42f,
        speedKmh = 105f,
        domestication = floatArrayOf(0.2f, 0.4f, 0.0f, 0.5f, 0.3f, 0.2f), // tamed for the hunt, never bred
        habitat = Habitat(cover = -0.8f, rugged = -0.5f),
        packLitter = 3,
        stalking = 0.3f,
        ferocity = 0.6f,
        territoryKm = 8f,
        startKm2PerAnimal = 600f,
    )

    // ---------------------------------------------------------------- people

    /**
     * People: hunter-gatherer bands of the Natufian age, some 25 strong, hunting with spears, bows
     * and fire and gathering fruit, nuts, seeds and roots, in about equal parts by weight. How well
     * a band hunts each kind of game and gathers each kind of food is a skill it builds up by
     * practice and loses without it. They cook (so they need less food than a meat-eater of their
     * size), wear clothes and keep fires (so cold troubles them less), and sense no weather ahead:
     * what they know of next week they must learn.
     */
    val HUMAN = Species(
        name = "people", massKg = 55f,
        diet = Diet(meat = 0.45f, smallPrey = 0.1f, carrion = 0.05f, roots = 0.15f, fruit = 0.25f),
        groupSize = 25, maxGroupSize = 50,
        dailyTravelKm = 10f, maxDailyTravelKm = 40f, senseKm = 10f,
        daysWithoutWater = 3, waterFromFood = 0.2f,
        birthMonth = 0, birthMonths = 12, youngPerFemale = 0.3f, yearsToMaturity = 15f,
        backgroundMortality = 0.02f, youngMortality = 0.03f,
        snowLimitMm = 600f, flightKm = 0.2f, dangerToHumans = 0f,
        products = Products(meat = 25f, fat = 4f, hide = 0f, bone = 7f, sinew = 0f),
        comfortC = -5f..35f, winterCoat = true, // clothes and fire
        speedKmh = 45f, // with thrown spears and arrows a chase is short
        foresight = 0f,
        domestication = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f),
        habitat = Habitat(cover = 0f, rugged = 0f, wet = 0.2f),
        defence = 0.7f,
        stalking = 0.6f,
        ferocity = 1.5f, // spears, numbers and fire: lions and hyenas give way
        territoryKm = 10f,
        startKm2PerAnimal = 40f,
        human = true,
        intakeKg = 1.6f,
    )

    val herbivores = listOf(
        GAZELLE, ROE_DEER, FALLOW_DEER, RED_DEER, IBEX, BOAR, AUROCHS, ONAGER, HIPPO, BEAR,
        ELK, REINDEER, HORSE, MOUFLON, ORYX, OSTRICH, HARTEBEEST, DROMEDARY,
    )
    val predators = listOf(WOLF, LION, LEOPARD, HYENA, JACKAL, CHEETAH, HUMAN)
    val all = herbivores + predators

    init {
        all.forEachIndexed { i, s -> s.index = i }
    }
}
