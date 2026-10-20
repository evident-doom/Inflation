package inflation.ecology

import inflation.world.HexDrinkingWater
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

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

    /**
     * Whether [s] can drink at place [p] of [drink], the one rule for residents and groups: where there is water (for
     * animals, soft snow too), or where water is within its going ([fetchKm]); a kind that keeps to the water's edge
     * ([Species.maxWaterKm], kept there by its range) always can. People carry water home in skins, gourds and ostrich
     * eggshells: anywhere within the day's round will do; and they dig for it in a dry bed where the water lies near
     * ([HexDrinkingWater.dug]).
     */
    fun canDrink(drink: HexDrinkingWater, s: Species, p: Int): Boolean {
        if (s.human) { val fetch = s.dailyTravelKm; return drink.water[p] >= Thresholds.WET || drink.waterKm[p] <= fetch || drink.mustDig(p, fetch) }
        return s.maxWaterKm.isFinite() || drink.animalWater(p) >= Thresholds.WET || drink.waterKm[p] <= fetchKm(s)
    }

    /** The days of [days] an animal of [s] goes dry away from water, eased by the water in its food: plant-eaters' from [green] food, hunters' from the body water of what they ate ([fed]). */
    fun dryDays(s: Species, days: Int, fed: Float, green: Float) = days * max(0f, 1f - s.waterFromFood * (if (s.carnivore) fed else 0.5f + 0.5f * green))

    /**
     * How crowded a kind is at [headsKm2] of its animals per km² (counted over the ground it ranges: a resident's place, a
     * pack's hex and those round it it ranges into) against its [usualKm2] density ([HexFauna.usualDensity]): 1 at
     * [HexFauna.CROWDED] times usual, where disease starts to bite ([crowdDisease]). The one measure of crowding for
     * residents and groups; a place's pull falls as 1 / (1 + this), and a group foresees its share of it as this / (1 + this).
     */
    fun crowding(headsKm2: Float, usualKm2: Float) = headsKm2 / (HexFauna.CROWDED * usualKm2)

    /**
     * Deaths [dying] (a share of [heads] animals in a week) in steps of the chance in a week's deaths: the spread of the
     * share that dies of so many at the odds they live under ([oddsNow], at least the kind's [background], both a week's
     * share). A difference within a step is no more than chance. The one way residents and groups weigh a place's deaths.
     */
    fun survivalSteps(dying: Float, oddsNow: Float, background: Float, heads: Float): Float {
        val q = max(oddsNow, background).coerceIn(1e-6f, 0.5f)
        return dying / sqrt(q * (1f - q) / max(1f, heads))
    }

    /** Sickness from crowding, over [years] (a week's share of a year), at [crowd] times its kind's usual density, fed [fed]: worse when underfed. */
    fun crowdDisease(crowd: Float, fed: Float, years: Float) = HexFauna.DISEASE * crowd * crowd * years * (1f + HexFauna.MALNOURISHED * (1f - fed))

    /** The share of animals of [s] that drown swimming [gapKm] of open water, before any cap. */
    fun drowning(s: Species, gapKm: Float) = DROWN * gapKm / Catalogue.swimKm(s).coerceAtLeast(1f)

    /** Thirst, after [dry] days without water. */
    fun thirst(s: Species, dry: Float) = if (dry > s.daysWithoutWater) min(0.5f, 0.05f * (dry - s.daysWithoutWater)) else 0f
}
