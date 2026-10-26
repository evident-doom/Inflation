package inflation.ecology

import inflation.world.Hex
import inflation.world.HexArray
import inflation.world.toHexArray
import inflation.world.Place
import inflation.world.get
import inflation.world.set

import inflation.ecology.HexSmallLife.DeadPool
import inflation.Books

import inflation.Kg
import inflation.KgM2
import inflation.min
import inflation.Tuning
import inflation.binomial
import inflation.world.HexLand
import kotlin.random.Random
import inflation.wrapMonth
import inflation.monthsFrom
import inflation.Config

import inflation.world.Date
import inflation.world.LifeForm
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.min
import inflation.world.Biome
import inflation.world.Weather
import java.util.stream.IntStream
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.sin

/*
 * Food, as the model tells it apart: [Food] is what a party forages and a band sets its efforts by; [Store] is the form a
 * band keeps food in; [FoodFrom] and [PlantFrom] group what it ate, for the census.
 */

/**
 * The kinds of food [forage] offers a party; all but [GRAIN] are also a band's effort slots ([FOOD_TYPES]: grain goes by grass seed's).
 * [energy]: what a kg of it as a band takes it (plant food dry, before grinding or cooking) nourishes, in [Nourishment]'s
 * units. A pack's big game is whole prey ([Nourishment.PREY]); a band's is what its butchers make of it. Bones are reckoned
 * as prey for the beasts that crack them; a band renders their grease ([greaseOf]). [protein]: the share of that energy
 * that is protein, which feeds people only so far ([Nourishment.PROTEIN_MOST]).
 */
enum class Food(val label: String, val energy: Float, val protein: Float) {
    BIG_GAME("big game", Nourishment.MEAT, Nourishment.PROTEIN_MEAT), SMALL_GAME("small game", Nourishment.PREY, Nourishment.PROTEIN_MEAT),
    CARRION("carrion", Nourishment.PREY, Nourishment.PROTEIN_MEAT), BONES("bones", Nourishment.PREY, 0f),
    FRUIT("fruit", Nourishment.FRUIT, Nourishment.PROTEIN_FRUIT), ROOTS("roots", Nourishment.ROOTS, Nourishment.PROTEIN_ROOTS),
    SEED("grass seed", Nourishment.GRAIN, Nourishment.PROTEIN_GRAIN), FINDS("small finds", Nourishment.FINDS, Nourishment.PROTEIN_FINDS),
    FISH("fish", Nourishment.FISH, Nourishment.PROTEIN_FISH), GRAIN("grain", Nourishment.GRAIN, Nourishment.PROTEIN_GRAIN)
}

/** The plant food a band's gathering came from, as the census counts it ([Tally.plantsFrom]). */
enum class PlantFrom(val label: String) { FRUIT("fruit"), MAST("mast"), BULBS("bulbs and roots"), SEED("grass seed"), GRAIN("cereal grain"), FINDS("small finds") }

/**
 * Food on hand, by how it keeps: fresh meat and fish rot in days (faster in the heat), dried meat
 * and fish keep for months, fruit for a week or two, roots for some weeks, seed and nuts for a year, rendered grease
 * sealed in guts or bladders for months (its rate a guess).
 * [lostWeekly] is the share lost a week (fresh meat: see [spoil]); [meat] whether it counts as meat (for its salt);
 * [protein] the share of its energy that is protein (fresh and dried meat and fish at meat's; small finds kept with fruit).
 */
enum class Store(val lostWeekly: Float, val meat: Boolean, val protein: Float) {
    FRESH(0f, true, Nourishment.PROTEIN_MEAT), DRIED(0.01f, true, Nourishment.PROTEIN_MEAT), FRUIT(0.1f, false, Nourishment.PROTEIN_FRUIT),
    ROOTS(0.03f, false, Nourishment.PROTEIN_ROOTS), SEED(0.005f, false, Nourishment.PROTEIN_GRAIN), GREASE(0.01f, false, 0f)
}
/** The order a band eats its food in: what spoils first, first. */
val EAT_ORDER = listOf(Store.FRESH, Store.FRUIT, Store.ROOTS, Store.DRIED, Store.SEED, Store.GREASE)

/** Kinds of food a band sets its effort on (see [forage]): the [Food] kinds before grain (the last), which goes by grass seed's. */
const val FOOD_TYPES = 9

/**
 * A band's effort on each kind of [Food] ([Band.effort]): one slot for each kind but grain, which goes by grass seed's
 * ([FOOD_TYPES] slots, laid out as the ways network's efforts, [WaysOut.EFFORTS]). As a field it compiles to the array
 * itself, so a save keeps its layout.
 */
@JvmInline value class FoodEfforts(val a: FloatArray) {
    constructor() : this(FloatArray(FOOD_TYPES) { 1f })
    @Suppress("NOTHING_TO_INLINE") inline operator fun get(t: Food) = a[slot(t)]
    fun fill(v: Float) = a.fill(v)

    companion object {
        /** The effort slot of [t]: its own, grain's being grass seed's. */
        @Suppress("NOTHING_TO_INLINE") inline fun slot(t: Food) = (if (t == Food.GRAIN) Food.SEED else t).ordinal
    }
}

/** Where a band's food came from, as [Band.foodFrom] counts it. */
enum class FoodFrom(val label: String) { PLANTS("plants"), SMALL_GAME("small game"), FISH("fish"), BIG_GAME("big game"), CARRION("carrion") }
