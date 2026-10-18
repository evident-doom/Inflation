package inflation.ecology

import kotlin.math.max
import kotlin.math.min

/**
 * The odds of death that residents ([HexFauna]) and groups ([HexGroups]) share, a year's rate each: what
 * hunger, the cold and thirst do to an animal is the same rule whichever way its kind is kept. What each
 * feeds in (how fed, the warmth it feels, its days without water) is its own.
 */
object Hazards {
    /** Hunger, for a share [fed] of its wants found last week. */
    fun hunger(fed: Float) = if (fed < HexFauna.HUNGER_FROM) (HexFauna.HUNGER_FROM - fed) * HexFauna.HUNGER_RATE else 0f

    /** The cold or heat [s] feels at [feltC] in [month], beyond what its coat (or clothes) allows. */
    fun cold(s: Species, feltC: Float, month: Int) = s.discomfortC(feltC, month) / 10f * HexFauna.COLD_DEATHS

    /** How far an animal of [s] goes to water and back as often as it must: a kind that can go days without drinking can live as many half-days' going from it. */
    fun fetchKm(s: Species) = 0.5f * s.dailyTravelKm * max(1, s.daysWithoutWater)

    /** The days of [days] an animal of [s] goes dry away from water, eased by the water in its food: plant-eaters' from [green] food, hunters' from the body water of what they ate ([fed]). */
    fun dryDays(s: Species, days: Int, fed: Float, green: Float) = days * max(0f, 1f - s.waterFromFood * (if (s.carnivore) fed else 0.5f + 0.5f * green))

    /**
     * The share of its full litter a mother bears for her body [condition] (her feeding over the last weeks, see
     * [CONDITION_MEMORY]): what she eats beyond keeping herself alive goes to her young, so a mother fed no better than
     * the hunger line ([HexFauna.HUNGER_FROM]) bears none, and one fed in full her whole litter.
     */
    fun breeding(condition: Float) = ((condition - HexFauna.HUNGER_FROM) / (1f - HexFauna.HUNGER_FROM)).coerceIn(0f, 1f)

    /**
     * How fast a body's condition follows its feeding, per week: about the last ten weeks count, a pregnancy's span
     * for the middle-sized hoofed animals (some 16 weeks for a sow, from memory).
     */
    const val CONDITION_MEMORY = 0.1f

    /** Thirst, after [dry] days without water. */
    fun thirst(s: Species, dry: Float) = if (dry > s.daysWithoutWater) min(0.5f, 0.05f * (dry - s.daysWithoutWater)) else 0f
}
