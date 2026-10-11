package inflation.ecology

import kotlin.math.pow

/** What a species eats, as shares of its intake. Shares sum to 1. */
data class Diet(
    val grass: Float = 0f,
    val browse: Float = 0f,
    /** Dug and gleaned plant food: bulbs, roots, tubers, fallen acorns and nuts. */
    val roots: Float = 0f,
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
    /** Top running speed, km/h: in a chase, a courser must outrun its prey; a stalker needs only surprise. */
    val speedKmh: Float = 60f,
    /** Keeps within this distance of drinking water, km (hippos graze at night near their river). */
    val maxWaterKm: Float = Float.POSITIVE_INFINITY,
    /** Breeds as a pack: one litter a year from the breeding pair, of this size in good condition. */
    val packLitter: Int = 0,
    /** Diamond's criteria, 0..1 each: diet breadth, growth, captive breeding, temperament, calm, hierarchy. */
    val domestication: FloatArray,
) {
    /** Position in [Catalogue.all], for array lookups. */
    var index = -1
        internal set
    /** Mostly eats animals. */
    val carnivore: Boolean get() = diet.meat + diet.smallPrey + diet.carrion > 0.5f
    /** Dry-matter (or meat) intake per adult per day, kg. */
    val dailyIntakeKg: Float get() = Allometry.dailyIntakeKg(massKg, carnivore)
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
        speedKmh = 80f,
        domestication = floatArrayOf(0.6f, 0.6f, 0.2f, 0.5f, 0.05f, 0.2f), // panics and leaps fences
        habitat = Habitat(cover = -1f, wet = -0.5f),
    )

    /** Roe deer: small, solitary or in families, browsing woodland edges and thickets. */
    val ROE_DEER = Species(
        name = "roe deer", massKg = 25f,
        diet = Diet(grass = 0.2f, browse = 0.8f),
        groupSize = 3, maxGroupSize = 6,
        dailyTravelKm = 3f, maxDailyTravelKm = 20f, senseKm = 1f,
        daysWithoutWater = 4, waterFromFood = 0.9f,
        birthMonth = 2, birthMonths = 1, youngPerFemale = 1.6f, yearsToMaturity = 1f,
        backgroundMortality = 0.12f, snowLimitMm = 150f, flightKm = 0.2f, dangerToHumans = 0f,
        products = Products(meat = 12f, fat = 0.5f, hide = 1.5f, bone = 3f, sinew = 0.2f, hornOrAntler = 0.3f),
        speedKmh = 70f,
        domestication = floatArrayOf(0.4f, 0.6f, 0.1f, 0.3f, 0.1f, 0.1f),
        habitat = Habitat(cover = 0.6f),
    )

    /** Persian fallow deer: herds in open woodland and parkland; common in Levantine Epipalaeolithic sites. */
    val FALLOW_DEER = Species(
        name = "fallow deer", massKg = 65f,
        diet = Diet(grass = 0.5f, browse = 0.5f),
        groupSize = 6, maxGroupSize = 25,
        dailyTravelKm = 4f, maxDailyTravelKm = 25f, senseKm = 2f,
        daysWithoutWater = 3, waterFromFood = 0.6f,
        birthMonth = 2, birthMonths = 1, youngPerFemale = 0.9f, yearsToMaturity = 1.5f,
        backgroundMortality = 0.1f, snowLimitMm = 150f, flightKm = 0.4f, dangerToHumans = 0f,
        products = Products(meat = 32f, fat = 2f, hide = 4f, bone = 7f, sinew = 0.5f, hornOrAntler = 2f),
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
        speedKmh = 55f,
        domestication = floatArrayOf(0.7f, 0.5f, 0.4f, 0.4f, 0.4f, 0.6f), // its cousin became the goat
        habitat = Habitat(cover = -0.3f, rugged = 1f),
        defence = 0.1f,
    )

    /** Wild boar: omnivore of forest, thicket and marsh; roots, acorns, bulbs; large litters. */
    val BOAR = Species(
        name = "wild boar", massKg = 80f,
        diet = Diet(grass = 0.2f, browse = 0.1f, roots = 0.7f),
        groupSize = 6, maxGroupSize = 20,
        dailyTravelKm = 5f, maxDailyTravelKm = 20f, senseKm = 1.5f,
        daysWithoutWater = 2, waterFromFood = 0.4f,
        birthMonth = 1, birthMonths = 2, youngPerFemale = 4f, yearsToMaturity = 1.5f,
        backgroundMortality = 0.2f, youngMortality = 0.6f,
        snowLimitMm = 200f, flightKm = 0.2f, dangerToHumans = 0.3f,
        products = Products(meat = 45f, fat = 8f, hide = 5f, bone = 9f, sinew = 0.5f, hornOrAntler = 0.5f),
        speedKmh = 45f,
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
        speedKmh = 50f,
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
        speedKmh = 70f,
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
        speedKmh = 30f,
        domestication = floatArrayOf(0.6f, 0.2f, 0.2f, 0.0f, 0.1f, 0.3f),
        habitat = Habitat(cover = -0.2f, rugged = -1f, wet = 1f),
        defence = 1f,
        maxWaterKm = 1.5f,
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
        speedKmh = 60f,
        domestication = floatArrayOf(0.7f, 0.5f, 0.6f, 0.4f, 0.6f, 0.9f), // became the dog
        packLitter = 4,
        stalking = 0.3f,
        startKm2PerAnimal = 400f,
    )

    /**
     * Lion: still in the Levant at the time, in prides that take the largest prey, aurochs
     * included, and drive other predators off their kills.
     */
    val LION = Species(
        name = "lion", massKg = 150f,
        diet = Diet(meat = 0.9f, carrion = 0.1f),
        groupSize = 5, maxGroupSize = 12,
        dailyTravelKm = 8f, maxDailyTravelKm = 30f, senseKm = 8f,
        daysWithoutWater = 4, waterFromFood = 0.6f,
        birthMonth = 2, birthMonths = 1, youngPerFemale = 0f, yearsToMaturity = 3f,
        backgroundMortality = 0.1f, snowLimitMm = 200f, flightKm = 0.2f, dangerToHumans = 0.9f,
        products = Products(meat = 60f, fat = 5f, hide = 8f, bone = 18f, sinew = 1f, fur = true),
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
        speedKmh = 45f,
        domestication = floatArrayOf(0.8f, 0.6f, 0.4f, 0.4f, 0.4f, 0.6f),
        habitat = Habitat(cover = 0.2f, wet = 0.2f),
        packLitter = 4,
        stalking = 0.4f,
        ferocity = 0.8f,
        territoryKm = 4f,
        startKm2PerAnimal = 200f,
    )

    val herbivores = listOf(GAZELLE, ROE_DEER, FALLOW_DEER, RED_DEER, IBEX, BOAR, AUROCHS, ONAGER, HIPPO)
    val predators = listOf(WOLF, LION, LEOPARD, HYENA, JACKAL)
    val all = herbivores + predators

    init {
        all.forEachIndexed { i, s -> s.index = i }
    }
}
