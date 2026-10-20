package inflation.ecology

import inflation.world.HexDrinkingWater
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The odds of death that residents ([HexFauna]) and groups ([HexGroups]) share, a year's rate each: what
 * hunger, the cold and thirst do to an animal is the same rule whichever way its kind is kept. What each
 * feeds in (how fed, the warmth it feels, its days without water) is its own.
 */
object Hazards {
    /**
     * Hunger: the share of animals of [s] that die in a step of [days], fed a share [fed] of their wants with their fat
     * drawn on ([onFat]), having burnt [spent] days of their body's protein before it. Below [WELL_FED] they would have drawn
     * on their fat for the rest, so it is gone: what they still lack they burn of their body's protein ([spentAfter]), and
     * an animal dies when it has burnt what carries it ([starveDays]), some sooner and some later ([starvedShare]).
     */
    fun hunger(s: Species, spent: Float, fed: Float, days: Int): Float {
        val before = starvedShare(s, spent); val after = starvedShare(s, spentAfter(s, spent, fed, days))
        return if (after > before) min(1f, (after - before) / max(1e-6f, 1f - before)) else 0f
    }

    /**
     * The days of its body's protein an animal of [s] has burnt after a step of [days] fed [fed] past its fat, having burnt
     * [spent] before: what it lacks beyond what it saves by spending less ([economy]).
     */
    fun spentAfter(s: Species, spent: Float, fed: Float, days: Int) =
        if (fed < WELL_FED) spent + max(0f, 1f - economy(s) - fed) * days else max(0f, spent - REBUILD * (fed - WELL_FED) / (1f - WELL_FED) * days)

    /**
     * The share of its need an animal of [s] short of food saves by spending less: plant-eaters lower their body heat and
     * their metabolism in the lean season, wild northern ruminants in winter to somewhat more than half the summer's (heart
     * rate; deeper when food is short: Arnold, "Review: Seasonal differences in the physiology of wild northern ruminants",
     * Animal 2020), Arabian oryx in the dry summer to half the spring's (field metabolic rate by doubly labelled water,
     * Williams, Ostrowski, Bedin and Ismail, J Exp Biol 2001). Hunters and people: not in the record here, none.
     */
    fun economy(s: Species) = if (s.carnivore || s.human) 0f else LEAN_ECONOMY
    const val LEAN_ECONOMY = 0.4f
    /**
     * Days of burnt body a fully fed animal wins back a day: what it eats beyond its need when it can ([SURPLUS_EAT]),
     * laid on as fat is ([FAT_EFFICIENCY]); less as it is fed less, none at [WELL_FED].
     */
    val REBUILD = SURPLUS_EAT * FAT_EFFICIENCY

    /** How well fed an animal is over a step of [days] fed [fed], with [fat] days of fat to draw on below [WELL_FED] (as the week's feeding draws on it). */
    fun onFat(fed: Float, fat: Float, days: Int) = if (fed < WELL_FED) min(1f, fed + min(fat, (1f - fed) * days) / days) else fed

    /**
     * The share of animals of [s] dead of hunger once they have burnt [spent] days of their body's protein: none until
     * the first reach the end of theirs, all by [STARVE_SPREAD] past [starveDays], evenly between. (How evenly the end comes
     * among animals in a herd is not in the record: the spread is a stand-in.)
     */
    fun starvedShare(s: Species, spent: Float) = ((spent / starveDays(s) - 1f + STARVE_SPREAD) / (2f * STARVE_SPREAD)).coerceIn(0f, 1f)
    const val STARVE_SPREAD = 0.5f

    /**
     * How long an animal of [s] lives on its body's protein once its fat is spent, days of going without food: the last
     * phase of a fast, a few days in rats (0.3 kg) and two to three weeks in penguins, which are made for fasting (Cherel,
     * Robin and Le Maho, review "Relationships between nutrient storage and nutrient utilisation in long-term fasting
     * birds and mammals", Acta XX Congressus Internationalis Ornithologici, Durban). Scaled with body mass as the protein
     * carried (as M) over its burning (as M^0.75): a roe deer 12 days, a red deer 18, a horse 22, an aurochs 28.
     */
    fun starveDays(s: Species) = STARVE_DAYS * (s.massKg / STARVE_KG).pow(0.25f)
    /** A rat's last phase of a fast, days, and its weight, kg: "a few days" (see [starveDays]). */
    const val STARVE_DAYS = 4f
    const val STARVE_KG = 0.3f

    /** The cold [s] feels at [feltC] in [month], beyond what its coat (or clothes) allows. */
    fun cold(s: Species, feltC: Float, month: Int) = s.coldC(feltC, month) / 10f * HexFauna.COLD_DEATHS
    /** The heat [s] feels at [feltC], beyond what it bears: by the same rule as the cold ([cold]), booked on its own. */
    fun heat(s: Species, feltC: Float) = s.hotC(feltC) / 10f * HexFauna.COLD_DEATHS

    /** How far an animal of [s] goes to water and back as often as it must: a kind that can go days without drinking can live as many half-days' going from it. */
    fun fetchKm(s: Species) = 0.5f * s.dailyTravelKm * max(1, s.daysWithoutWater)

    /**
     * Whether [s] can drink at place [p] of [drink], the one rule for residents and groups: where there is water (for
     * animals, soft snow too), or where water is within its going ([fetchKm]); a kind that keeps to the water's edge
     * ([Species.maxWaterKm], kept there by its range) always can. People carry water home in skins, gourds and ostrich
     * eggshells: anywhere within the day's round will do; and they dig for it in a dry bed where the water lies near
     * ([HexDrinkingWater.dug]).
     */
    fun canDrink(drink: HexDrinkingWater, s: Species, p: Place): Boolean {
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
