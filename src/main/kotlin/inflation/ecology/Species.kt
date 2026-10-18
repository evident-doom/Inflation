package inflation.ecology

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.min

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
    /**
     * Fixed number of the kind, 0 until [Catalogue.all]'s size, never reused or changed: per-kind
     * tables, saves and learned networks are laid out by it. A new kind takes the next number.
     */
    val id: Int,
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
    /** Birth season: first month (0-based from the winter solstice: 0 about January) and length in months. */
    val birthMonth: Int,
    val birthMonths: Int,
    /** Young per adult female per year in good condition. */
    val youngPerFemale: Float,
    val yearsToMaturity: Float,
    /** Adult mortality per year from causes not modelled (age, accident, disease); predation and hunger are modelled. */
    val backgroundMortality: Float,
    /**
     * Share of the young lost in their first year to causes not modelled (stillbirth, desertion, birth defects,
     * falls, infanticide); negative: none beyond the adults' rate. Hunger, thirst, cold, crowding sickness, fire,
     * flood and hunters come on top, each modelled, so a total from the field is too much here.
     */
    val firstYearLost: Float = -1f,
    /**
     * Yearly mortality of the young from causes not modelled, in place of the adults' [backgroundMortality]: by
     * default [firstYearLost] in the first year and the adults' rate after it, spread over the [yearsToMaturity] they
     * count as young (they grow up at 1/[yearsToMaturity] a year), so a hippo calf, young for eight years, is not
     * lost at a newborn's rate every year.
     */
    val youngMortality: Float = if (firstYearLost < 0f) backgroundMortality
        else backgroundMortality + (firstYearLost - backgroundMortality) / max(1f, yearsToMaturity),
    /** Snow depth (mm water equivalent) that stops it feeding and slows it badly. */
    val snowLimitMm: Float,
    /** Raw materials an adult yields (for people: hides, bone, sinew). */
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
     * A hindgut fermenter (horses, asses): on poor forage it eats more and passes it fast. Ruminants
     * and the rest eat less of poor forage, as it fills the gut before it nourishes.
     */
    val hindgut: Boolean = false,
    /**
     * For predators: fighting weight per kg compared with a wolf, for fights over carcasses and
     * ground: a hyena clan bites above its weight, a jackal below.
     */
    val ferocity: Float = 1f,
    /** For a hunter kept as a resident: the biggest game it kills, times its own weight (see [HexFauna.huntResidents]). */
    val preyUpTo: Float = 3f,
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
    /**
     * Inborn fear of people, 0..1, bred in over the millennia people have hunted here: high in game
     * animals, lower in big dangerous ones and in scavengers that have long lived off people's leavings.
     */
    val fearOfPeople: Float = 0.7f,
    /**
     * Share of curious, bold animals in a typical group: the ones that come close, get killed first
     * by hunters, and, around camps, are the start of the road to taming.
     */
    val boldShare: Float = 0.03f,
    /** Share of its active hours spent at dawn, day, dusk and night; null: about at all hours alike. */
    val activity: FloatArray? = null,
    /** The shelters it uses ([Niches.shelter]). */
    val shelters: Set<Niches.Shelter> = emptySet(),
    /** How far it will swim open water, km; negative: as its way of travel gives ([Catalogue.swimKm]). */
    val swimKm: Float = -1f,
    /** Cracks bones and lives on what others leave long after the meat is gone. */
    val cracksBones: Boolean = false,
    /** How readily it takes people (leopards 1.2, lions 1, bears 0.4, wolves and hyenas 0.3; 0: never), see [STALKED]. */
    val takesPeople: Float = 0f,
) {
    /** Position in [Catalogue.all] (its [id], which the catalogue checks run without gaps), for array lookups. */
    val index get() = id
    /** Mostly eats animals. */
    val carnivore: Boolean get() = diet.meat + diet.smallPrey + diet.carrion > 0.5f
    /** What kind of group it lives in: a band of people, a pack of hunters or a herd of plant-eaters. */
    val lifeway: Lifeway get() = if (human) Lifeway.BAND else if (carnivore) Lifeway.PACK else Lifeway.HERD
    /** Hunts in packs: a meat-eater that is not people. */
    val pack: Boolean get() = carnivore && !human
    /**
     * Days of food it can carry as fat. Mammals store some 0.075 M^1.19 kg of fat and burn about 317 M^0.75 kJ a
     * day at rest, so fat lasts 9.3 M^0.44 days at rest (Lindstedt and Boyce 1985); on the move they burn some
     * [Allometry.FIELD_OVER_BASAL] times that: a gazelle 14 days, an aurochs 68. A sleeper needs a quarter.
     */
    val maxFatDays: Float get() = 9.3f / Allometry.FIELD_OVER_BASAL * massKg.pow(0.44f) * if (hibernates) 4f else 1f
    /** Dry-matter (or meat) intake per adult per day, kg. */
    val dailyIntakeKg: Float get() = if (intakeKg > 0f) intakeKg else Allometry.dailyIntakeKg(massKg, diet)

    /**
     * How far its coat stretches the cold end in [month] (from the winter solstice), °C: a winter coat
     * grows in through the autumn to 10 °C and is moulted in spring.
     */
    fun coatC(month: Int): Float = if (winterCoat) 10f * COAT[month] else 0f
    /** Degrees colder than its coat bears at [t] °C in [month]. */
    fun coldC(t: Float, month: Int): Float = max(0f, comfortC.start - coatC(month) - t)
    /** Degrees hotter than it bears at [t] °C. */
    fun hotC(t: Float): Float = max(0f, t - comfortC.endInclusive)
    /** Degrees outside what it bears at [t] °C in [month], either way. */
    fun discomfortC(t: Float, month: Int): Float = coldC(t, month) + hotC(t)

    companion object {
        /** Share of a winter coat grown, by month from the winter solstice. */
        private val COAT = floatArrayOf(1f, 1f, 0.9f, 0.5f, 0.2f, 0f, 0f, 0f, 0.2f, 0.5f, 0.8f, 1f)
    }
}

/** How much more (or less) than its need a plant-eater eats of forage of [quality] (0..1, 1 fresh and green). */
fun gutFill(s: Species, quality: Float): Float =
    if (s.hindgut) min(HexFauna.GUT_FILL, 1f / quality.coerceAtLeast(1e-3f)) else min(1f, 0.5f + 0.5f * quality / 0.8f * s.fibreDigestion)

object Allometry {
    /**
     * A free-living mammal's daily energy use against its resting need: about 2-3 times (the herbivores' intake below
     * is "some 2-3 times their basal needs"); the middle of that.
     */
    const val FIELD_OVER_BASAL = 2.5f

    /**
     * Food intake grows with metabolic mass, M^0.75 (Kleiber). Free-living herbivores eat about
     * 0.08 M^0.75 kg of dry plant matter a day (a 20 kg gazelle ~0.75 kg), some 2-3 times their
     * basal needs. Carnivores need about 0.2 M^0.75 kg
     * of prey body mass a day, counting the parts they leave (a 30 kg wolf ~2.6 kg, in line with
     * field kill rates of roughly 0.1 kg of prey per kg of wolf per day). Rules of thumb, from memory. A
     * mixed feeder needs each share of its diet by its own rule (a bear eating a fifth animals, some 0.104 M^0.75),
     * so the need does not jump as the animal share passes a half.
     */
    fun dailyIntakeKg(massKg: Float, diet: Diet): Float {
        val animal = diet.meat + diet.smallPrey + diet.carrion
        val plants = diet.grass + diet.browse + diet.roots + diet.fruit
        val share = if (animal + plants > 0f) animal / (animal + plants) else 0f
        return (0.2f * share + 0.08f * (1f - share)) * massKg.pow(0.75f)
    }

    /**
     * Damuth's rule: mammal population density falls with body mass, about 10^4.23 M^-0.75
     * individuals per km² for herbivores with M in grams (from memory; to be checked). Real
     * densities scatter an order of magnitude around it.
     */
    fun herbivoreDensityPerKm2(massKg: Float): Float = 10f.pow(4.23f) * (massKg * 1000f).pow(-0.75f)
}

/** The kinds of group a species lives in (see [Species.lifeway]); not the learning [inflation.learning.Role]. */
enum class Lifeway { HERD, PACK, BAND }
