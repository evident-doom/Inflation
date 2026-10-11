package inflation.ecology

import kotlin.math.pow

/** What a species eats, as shares of its intake. Shares sum to 1. */
data class Diet(
    val grass: Float = 0f,
    val browse: Float = 0f,
    val meat: Float = 0f,
    val smallPrey: Float = 0f,
    val carrion: Float = 0f,
)

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
    /** Birth season: first month (0-based) and length in months. */
    val birthMonth: Int,
    val birthMonths: Int,
    /** Young per adult female per year in good condition. */
    val youngPerFemale: Float,
    val yearsToMaturity: Float,
    /** Adult mortality per year from causes not modelled (age, accident, disease); predation and hunger are modelled. */
    val backgroundMortality: Float,
    /** Snow depth (mm water equivalent) that stops it feeding and slows it badly. */
    val snowLimitMm: Float,
    /** Distance at which it notices a predator and flees, km. */
    val flightKm: Float,
    val dangerToHumans: Float,
    val products: Products,
    /** Diamond's criteria, 0..1 each: diet breadth, growth, captive breeding, temperament, calm, hierarchy. */
    val domestication: FloatArray,
) {
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
    /** Mountain or dorcas-type gazelle: the staple prey of Natufian hunters. Mixed grazer and browser of open land. */
    val GAZELLE = Species(
        name = "gazelle", massKg = 20f,
        diet = Diet(grass = 0.6f, browse = 0.4f),
        groupSize = 12, maxGroupSize = 40,
        dailyTravelKm = 6f, maxDailyTravelKm = 30f, senseKm = 4f,
        daysWithoutWater = 5,
        birthMonth = 1, birthMonths = 2, youngPerFemale = 1f, yearsToMaturity = 1f,
        backgroundMortality = 0.08f, snowLimitMm = 40f, flightKm = 1f, dangerToHumans = 0f,
        products = Products(meat = 9f, fat = 0.5f, hide = 1.2f, bone = 2.5f, sinew = 0.2f, hornOrAntler = 0.3f),
        domestication = floatArrayOf(0.6f, 0.6f, 0.2f, 0.5f, 0.05f, 0.2f), // panics and leaps fences
    )

    /**
     * Grey wolf of the southern, Arabian-Levantine type: smaller than northern wolves and living in
     * small packs, since prey the size of a gazelle feeds only a few. Eats much small prey and carrion.
     */
    val WOLF = Species(
        name = "wolf", massKg = 22f,
        diet = Diet(meat = 0.6f, smallPrey = 0.25f, carrion = 0.15f),
        groupSize = 3, maxGroupSize = 8,
        dailyTravelKm = 15f, maxDailyTravelKm = 50f, senseKm = 8f,
        daysWithoutWater = 3,
        birthMonth = 1, birthMonths = 1, youngPerFemale = 2.5f, yearsToMaturity = 2f,
        backgroundMortality = 0.15f, snowLimitMm = 400f, flightKm = 0.3f, dangerToHumans = 0.2f,
        products = Products(meat = 9f, fat = 0.4f, hide = 1.5f, bone = 2.2f, sinew = 0.2f, fur = true),
        domestication = floatArrayOf(0.7f, 0.5f, 0.6f, 0.4f, 0.6f, 0.9f), // became the dog
    )

    val all = listOf(GAZELLE, WOLF)
}
