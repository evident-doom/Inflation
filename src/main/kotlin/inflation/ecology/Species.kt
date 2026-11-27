package inflation.ecology

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.min

/** What a species eats, as shares of its intake. Shares sum to 1. */
data class Diet(
    val grass: Float = 0f,
    val browse: Float = 0f,
    /** Dug plant food: bulbs, roots and tubers ([HexFood.diggable]). */
    val roots: Float = 0f,
    /** Fleshy fruit and berries picked off the shrubs while they last, summer to autumn ([HexFood.fruit]). */
    val fruit: Float = 0f,
    /**
     * Fallen mast, acorns and nuts picked up off the ground through autumn and winter ([HexFood.mast]): eaten raw only by
     * the kinds that bear their tannins and crack their shells (deer, pigs, bears; not the dogs nor the ostrich). People
     * gather it, shell it and leach it ([ForageOffers]).
     */
    val mast: Float = 0f,
    val meat: Float = 0f,
    val smallPrey: Float = 0f,
    val carrion: Float = 0f,
) {
    /** Share of animal food: meat, small prey and carrion. */
    val animal: Float get() = meat + smallPrey + carrion
    /** Share of plant food: grass, browse, fruit, mast and roots. */
    val plants: Float get() = grass + browse + fruit + mast + roots
    /** The food stocks of it, fruit, mast and roots: what the networks see as one ([inflation.learning.HexPpo]). */
    val stocks: Float get() = fruit + mast + roots
    /** All the shares (1 for a full diet). */
    val total: Float get() = grass + browse + fruit + mast + roots + meat + smallPrey + carrion
    /** How much of this diet [o] eats too, 0..1: the shares the two have in common (each of its whole). */
    fun overlap(o: Diet): Float {
        val a = total.coerceAtLeast(1e-6f); val b = o.total.coerceAtLeast(1e-6f)
        return min(grass / a, o.grass / b) + min(browse / a, o.browse / b) + min(roots / a, o.roots / b) + min(fruit / a, o.fruit / b) +
            min(mast / a, o.mast / b) + min(meat / a, o.meat / b) + min(smallPrey / a, o.smallPrey / b) + min(carrion / a, o.carrion / b)
    }
}

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
    /** Snow depth (mm water equivalent) it can dig through to grass and roots no more: what it gets at falls evenly to none there ([grassUnderSnow]). */
    val snowLimitMm: Float,
    /** Raw materials an adult yields (for people: hides, bone, sinew). */
    val products: Products,
    val habitat: Habitat = Habitat(),
    /** How well adults fight off or outlast predators, 0 (not at all) to 1 (adults practically safe). */
    val defence: Float = 0f,
    /**
     * Holds ground against its own kind though it eats mostly plants: past the numbers its ranges hold it fights its own
     * and kills their young, as the resident hunters do ([inflation.ecology.HexFauna.strife], [inflation.Tuning.holderStrifeCrowd]).
     * Brown bear males kill cubs not their own, and cub losses rise with the bears' numbers (Swenson et al. 1997, Nature
     * 386:450, from memory).
     */
    val holdsGround: Boolean = false,
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
     * A hindgut fermenter (horses, asses): it passes poor forage fast and eats as much of it as of good, where ruminants
     * and the rest eat less of it, as it fills the gut before it nourishes. Not more of it: Przewalski's horses ate least
     * in late winter, on their most fibrous food, some 40% less than at the autumn peak, and lost 17-22% of their
     * weight over the winter (Kuntz et al. 2006, J Exp Biol 209:4557).
     */
    val hindgut: Boolean = false,
    /**
     * The kinds it shares its worms, flukes and plagues with ([Kin]): crowding by any of them breeds its sickness, as far
     * as they feed on the same ground ([HexFauna.sharers]). The hoofed cud-chewers share the stomach worms and the
     * cattle plague, horses and asses theirs, pigs theirs.
     */
    val kin: Kin = Kin.NONE,
    /**
     * How well it climbs crags and screes, 0..1: the share of a place's crag it feeds on ([HexFood.access]), and from 0.5
     * up, a climber that crosses crags others cannot ([Catalogue.climbers]) and finds refuge on them from hunters.
     */
    val climbing: Float = 0f,
    /**
     * Whether it cools itself by sweating or panting; a kind that does not (pigs) takes on the sun's heat in the open
     * and keeps to shade and wallows in hot weather ([HexFauna.sunLoadC]).
     */
    val sweats: Boolean = true,
    /**
     * How well it bears damp ground, 0..1: the liver fluke and lungworm of wet pasture, foot rot in soft ground
     * ([HexFauna.wetSickness]). Cattle, pigs, elk and hippos live with them; sheep, goats and the kinds of the dry
     * country sicken. 1, the default, for kinds that take no worms from the pasture (the hunters).
     */
    val dampTolerance: Float = 1f,
    /**
     * For predators: fighting weight per kg compared with a wolf, for fights over carcasses and
     * ground: a hyena clan bites above its weight, a jackal below.
     */
    val ferocity: Float = 1f,
    /**
     * For hunters: the biggest game it kills, times its own weight, as the field records its kills (so for kinds
     * that hunt together, with their groups' strength in it); see [preySize].
     */
    val preyUpTo: Float = 3f,
    /**
     * For hunters: the biggest game it takes as readily as any, times its own weight; above it it takes game the
     * less readily the nearer it is to [preyUpTo] (see [preySize]).
     */
    val preyPreferredUpTo: Float = preyUpTo,
    /**
     * For hunters: the smallest game it hunts as game, times its own weight; smaller is small prey, caught otherwise
     * ([preySize]). A quarter for most; a bear, which finds the hidden fawns and calves of spring, far smaller.
     */
    val preyFrom: Float = 0.25f,
    /** For predators: land per animal at the start, km². */
    val startKm2PerAnimal: Float = 0f,
    /**
     * Heads per km² that field counts find where it lives, over the kind of country it lives in most: what a run's numbers
     * are held against ([inflation.run.Nominal.FIELD_SLACK], the probe LimitsCheck). Null where the record has none (kinds
     * gone wild long ago, or a relict here). Each with its source in the catalogue, "from memory" where recalled.
     */
    val fieldKm2: ClosedFloatingPointRange<Float>? = null,
    /**
     * A resident hunter's kg a km² for each kg a km² of the game it catches ([HexFauna.usualAt], the ground its territories
     * hold): null for its kind's rule, [HexFauna.PREY_SUPPORT] for the big hunters of game, [HexFauna.SMALL_HUNTER_SUPPORT]
     * for the rest.
     */
    val preySupport: Float? = null,
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
    /**
     * Months from a mother losing her young (dead or grown) to her next litter, out of her kind's season: she comes back into
     * heat within weeks and bears after her gestation ([HexGroup.birthMonth] moves there). 0: she waits for the next season.
     * The cats that lose most of their cubs to lions and hyenas keep up so (a cheetah has her next litter months after losing
     * one; from memory of Laurenson 1994 and Caro 1994, not checked).
     */
    val rebreedMonths: Int = 0,
    /**
     * How many of a group's grown go about together, foraging, and so meet others and stand up to them ([HexGroups.strength]);
     * 0 where all of them do. A kind that rests in pairs but forages alone has 1.
     */
    val foragingParty: Int = 0,
    /** Diamond's criteria, 0..1 each: diet breadth, growth, captive breeding, temperament, calm, hierarchy. */
    val domestication: FloatArray,
    /**
     * Its mothers can be milked once kept: the kinds whose kept descendants were milked (cattle, sheep and goats from the
     * 7th millennium BC in the Near East, Evershed et al. 2008, Nature 455; horses at Botai about 3500 BC, Outram et al.
     * 2009, Science 323; camels). Not pigs (a sow lets down her milk for seconds at a time, to a litter on many teats), deer
     * nor birds. Kept herds of other kinds give meat only ([milkKg]).
     */
    val milked: Boolean = false,
    /** People: they hunt and gather by skill learned in practice (see [AnimalGroup.craft]), cook, and remember. */
    val human: Boolean = false,
    /** Daily food, kg of prey or plant food, where the allometric rule does not fit (people, who cook). */
    val intakeKg: Float = 0f,
    /** With [intakeKg]: its daily need in [Nourishment]'s units, which [intakeKg] stands for. */
    val needUnits: Float = 0f,
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
    /** Its mothers hide their young and move them from lair to lair in their first months, as the cats do ([youngHidden]). */
    val hidesYoung: Boolean = false,
    /**
     * Its young lie out alone and still in the grass or the thicket for their first months ([LIE_OUT_MONTHS]), as gazelle,
     * deer and wild goats' do: a hunter comes on each by itself, not as its mother's herd, and they do not see it coming
     * and run, they lie close; only cover keeps them ([HexFauna.huntResidents]). (Hiders, against the followers that run
     * at their mothers' heels from birth: horses, asses, aurochs.)
     */
    val liesOut: Boolean = false,
    /**
     * How old a carcass it will still eat, in days of rotting at 15 °C (faster in the warmth, none in frost: [spoilWarmth]):
     * past that the bacteria and their poisons would sicken it, and it passes it up. The vultures and hyenas, with
     * stomachs as acid as battery acid, eat it to the bone; wolves, jackals, bears and boar a carcass some days old; the cats
     * and people (cooked) only fresh ones. ([HexSmallLife.freshShare]: the share of the carrion lying about it will eat.
     * From memory of the record, not checked.)
     */
    val carrionAgeDays: Float = 3f,
    /**
     * Days its eggs lie in the nest on the ground before they hatch, open to the nest robbers ([robsNests],
     * [HexFauna.hatching]); 0 for a kind that bears its young live. Its [youngPerFemale] are then the chicks a hen's nest
     * hatches where no one robs it.
     */
    val nestDays: Float = 0f,
    /** It finds and eats the eggs of birds nesting on the ground ([nestDays]), as the small life's foxes do: jackals, hyenas, wild boar, bears. */
    val robsNests: Boolean = false,
    /** How far its dispersers go, km: the scale of nearness their settling weighs ([disperse]); an estimate. */
    val dispersalKm: Float = 60f,
    /** The days its dispersers take to settle where there is room enough ([disperse]); an estimate. */
    val dispersalDays: Float = 90f,
    /**
     * A kind it keeps off the ground of, losing its kills and young to it: it starts away from where that kind starts
     * ([HexGroups]' start). Null: none.
     */
    val keepsOff: Species? = null,
) : Comparable<Species> {
    /** Position in [Catalogue.all] (its [id], which the catalogue checks run without gaps), for array lookups. */
    val index get() = id
    /** By [id]: so a save writes a map keyed by kind (HexGroups.policies) in the same order every run, not in identity-hash order. */
    override fun compareTo(other: Species) = id.compareTo(other.id)
    /** [shelters] as an array, for [Niches.shelter]'s loop. */
    internal val shelterArray: Array<Niches.Shelter> = shelters.toTypedArray()
    /** Whether it escapes by seeing danger early and outrunning it ([inflation.ecology.watchesInOpen]), worked out once: it is read in the hunters' innermost loops. */
    internal val runsInOpen: Boolean = !(diet.animal > 0.5f || defence >= COVER_DEFENCE || Niches.Shelter.THICKET in shelters ||
        Niches.Shelter.TREE in shelters || Niches.Shelter.CRAG in shelters)
    /** Mostly eats animals. */
    val carnivore: Boolean get() = diet.animal > 0.5f
    /** What kind of group it lives in, for the kinds that live in groups: a band of people or a pack of hunters. */
    val lifeway: Lifeway get() = if (human) Lifeway.BAND else Lifeway.PACK
    /**
     * Days of food it can carry as fat. Mammals store some 0.075 M^1.19 kg of fat and burn about 317 M^0.75 kJ a
     * day at rest, so fat lasts 9.3 M^0.44 days at rest (Lindstedt and Boyce 1985); on the move they burn some
     * [Allometry.FIELD_OVER_BASAL] times that: a gazelle 14 days, an aurochs 68. A sleeper needs a quarter.
     */
    val maxFatDays: Float get() = 9.3f / Allometry.FIELD_OVER_BASAL * massKg.pow(0.44f) * if (hibernates) 4f else 1f
    /** The most of its body that can be fat, the share [maxFatDays] fills: 0.075 M^1.19 kg over M (Lindstedt and Boyce 1985). */
    val mostFatShare: Float get() = 0.075f * massKg.pow(0.19f)
    /** Dry-matter (or meat) intake per adult per day, kg. */
    val dailyIntakeKg: Float get() = if (intakeKg > 0f) intakeKg else Allometry.dailyIntakeKg(massKg, diet)
    /**
     * What a unit of food energy ([Nourishment]) feeds it, in kg of its need ([dailyIntakeKg]): the allometric rule's
     * need over 0.08 M^0.75 units (1 for a plant-eater, 2.5 for a hunter, between for a mixed feeder), or [intakeKg]
     * over [needUnits].
     */
    val needScale: Float = if (intakeKg > 0f && needUnits > 0f) intakeKg / needUnits
        else Allometry.dailyIntakeKg(massKg, diet) / (0.08f * massKg.pow(0.75f))

    /**
     * Its gut's room against its need, against a 100 kg kind's: (M/100)^0.25, as gut capacity grows with M and need with
     * M^0.75 ([gutFill]). A gazelle 0.67, an aurochs 1.65.
     */
    val gutSize: Float get() = (massKg / 100f).pow(0.25f).coerceIn(0.6f, 2f)

    /**
     * Standing food, kg/m², at which it crops all but 1/e of its want in its feeding day ([cropRate]): [CROP_HALF] for a
     * 100 kg kind going 5 km a day, growing with the fourth root of its mass (its bite holds more, and it needs a fuller
     * sward to fill it) and falling with the square root of its day's going (it searches more ground for the scattered
     * good bites). A gazelle 0.007, an onager 0.008, an aurochs 0.016. (The exponents are a reading of the bite-size and
     * search-rate rules, not measured. At the square root of its mass the aurochs, hippo and wild horse could not live on
     * the plant model's sward once they no longer made up their grass with browse, eval 2026-11-19.)
     */
    val cropHalfKgM2: Float get() = CROP_HALF * (massKg / 100f).pow(0.25f) * (5f / dailyTravelKm.coerceAtLeast(1f)).pow(0.5f)

    /**
     * How far its coat stretches the cold end in [month] (from the winter solstice), °C: a winter coat
     * grows in through the autumn to 10 °C and is moulted in spring.
     */
    fun coatC(month: Int): Float = if (winterCoat) 10f * COAT[month] else 0f
    /**
     * How readily it takes game of [kg] ([young] if it is a young one), 0..1, resident hunters and packs alike: not at all under [preyFrom] times its
     * weight (that is small prey, hunted otherwise; a young one, under [YOUNG_PREY_FROM]) or over [preyUpTo] times it, fully up to [preyPreferredUpTo] times
     * it, and falling off evenly between. (The even fall is a simplification: the record gives the preferred and the
     * accessible range, not the shape.)
     */
    fun preySize(kg: Float, young: Boolean = false): Float {
        val r = kg / massKg
        // (The young it comes on lying hidden it takes down to [YOUNG_PREY_FROM] of its weight, however small its game:
        // newborn fawns, kids and calves are much of a wolf's early-summer food, found in the grass, not run down.)
        if (r < (if (young) min(preyFrom, YOUNG_PREY_FROM) else preyFrom) || r > preyUpTo) return 0f
        if (r <= preyPreferredUpTo) return 1f
        return (preyUpTo - r) / (preyUpTo - preyPreferredUpTo)
    }

    /** Degrees colder than its coat bears at [t] °C in [month]. */
    fun coldC(t: Float, month: Int): Float = max(0f, comfortC.start - coatC(month) - t)
    /** Degrees hotter than it bears at [t] °C. */
    fun hotC(t: Float): Float = max(0f, t - comfortC.endInclusive)
    /** Degrees outside what it bears at [t] °C in [month], either way. */
    fun discomfortC(t: Float, month: Int): Float = coldC(t, month) + hotC(t)

    companion object {
        /** Share of a winter coat grown, by month from the winter solstice. */
        private val COAT = floatArrayOf(1f, 1f, 0.9f, 0.5f, 0.2f, 0f, 0f, 0f, 0.2f, 0.5f, 0.8f, 1f)
        /**
         * [cropHalfKgM2] of a 100 kg kind going 5 km a day, kg/m². The pasture's thinness already sets how much food it
         * offers ([HexFauna.REACH], a week's share of what stands); this is only the cropping time a bite size costs on top.
         * At 0.03 the two together, with the hindgut's loss on cured grass, starved horses and onagers on the steppe that
         * fed them in nature (hunger 56-64% of their deaths); at this a horse crops some 97% of its want there, an aurochs
         * some 82%, and the small kinds keep their edge only on the thinnest pickings.
         */
        const val CROP_HALF = 0.012f
        /**
         * [preySize]: the smallest young a hunter takes as game, times its own weight, whatever its grown game: a wolf a
         * newborn fawn of a kilo or so, a lion a calf of some 7 kg. (With its grown game's quarter, the seasonal weight of
         * the young put the first months' fawns and kids out of every wolf's reach, and the small kinds boomed.)
         */
        const val YOUNG_PREY_FROM = 0.05f
        /** Months from birth a lying-out kind's young lie out alone ([liesOut]); an estimate (gazelle and roe fawns some two). */
        const val LIE_OUT_MONTHS = 2
    }
}

/**
 * How much more (or less) than its need a plant-eater eats of forage of [quality] (0..1, 1 fresh and green). What a
 * ruminant falls short of on poor forage is shared out by the size of its gut against its need ([Species.gutSize]): a big
 * one holds coarse food longer and gets more from it, a small one must find better food (Jarman 1974, Bell 1971, Demment
 * and Van Soest 1985: gut capacity goes with M, need with M^0.75). On forage good enough to fill any gut, size changes nothing.
 */
fun gutFill(s: Species, quality: Float): Float {
    if (s.hindgut) return 1f
    val f = min(1f, 0.5f + 0.5f * quality / 0.8f * s.fibreDigestion)
    return 1f - (1f - f) / s.gutSize
}

/**
 * The worth of the forage [s] eats where the place's grass and browse are worth [q] (0..1, of fresh growth's), as it picks
 * them over: a small kind, whose gut is small against its need ([Species.gutSize]), picks the leaves, buds, shoots and
 * flowers out of the same sward and thicket and eats better than the place's mean; a big one crops it as it stands. The
 * other half of the size trade-off ([gutFill]): the small live on thin, good food, the big on plenty of coarse (Jarman
 * 1974, Bell 1971, Hofmann 1989). Of the gap to fresh growth, [SELECTION] of the share its gut falls short of a 100 kg
 * kind's: a gazelle about a sixth, a goat a tenth, a deer none.
 */
fun Species.selected(q: Float, picks: Float = 1f): Float {
    val picked = q + (1f - q) * SELECTION * selectivity * picks
    // (A hindgut fermenter passes its food fast and gets less of the fibre out of it than a cud-chewer: as much of fresh
    // growth, some 70% of straw ([HINDGUT_LOSS]). It makes up by eating its fill of poor forage, [gutFill]; with as much
    // from each kilo as well, horses and onagers had the best of both in every lean season and crowded the cud-chewers out.)
    return if (hindgut) picked * (1f - HINDGUT_LOSS * (1f - picked)) else picked
}

/**
 * [selected]: how much less than a cud-chewer a hindgut fermenter gets of forage worth nothing at all, falling to none at
 * fresh growth. Horses digest hay to some 70-80% of what cattle do (from memory of the feeding record); at 0.5 (half of
 * straw) onagers and horses fell to a quarter beside the small cud-chewers in 25 years, hungry, and ostriches to a third:
 * half that, as they also lose out on gut and crop.
 */
const val HINDGUT_LOSS = 0.25f

/**
 * How much a kind picks over its forage rather than cropping it as it stands, 0..1: the share its gut falls short of a
 * 100 kg kind's ([Species.gutSize]); none for 100 kg and over. See [selected].
 */
val Species.selectivity: Float get() = (1f - gutSize).coerceIn(0f, 1f)

/** [selected]: how far a small kind's picking closes the gap to fresh growth, at a gut none at all against a 100 kg kind's; an estimate. */
const val SELECTION = 0.5f

/**
 * The share of its want of a food it can crop in its feeding day where [kgM2] of it stands: all where the food is thick,
 * less where it is thin, as each bite holds less and more time goes to walking between them ([Species.cropHalfKgM2];
 * Spalinger and Hobbs 1992, Illius and Gordon 1987). Small and wide-ranging kinds make a living off thin pickings that
 * starve a big one; a big one does well only where its food stands thick.
 */
fun cropRate(s: Species, kgM2: Float): Float = if (kgM2 <= 0f) 0f else 1f - kotlin.math.exp(-kgM2 / s.cropHalfKgM2)

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
        val animal = diet.animal
        val plants = diet.plants
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

/** Kinds that share their parasites and plagues ([Species.kin]); [NONE] shares them with no other kind. */
enum class Kin { NONE, RUMINANTS, EQUIDS, PIGS }

/** The kinds of group a species lives in (see [Species.lifeway]); not the learning [inflation.learning.Role]. */
enum class Lifeway { PACK, BAND }
