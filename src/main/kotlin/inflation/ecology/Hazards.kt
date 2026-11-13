package inflation.ecology

import inflation.world.Place
import inflation.world.get
import inflation.world.set

import inflation.world.HexDrinkingWater
import kotlin.math.exp
import kotlin.math.ln
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
     * The most a week's hazards add up to for a grown one and for a young one: of whichever the odds of the week are held
     * under, in the groups' and residents' deaths and in the foresight that reckons them, so that these stay one rule.
     */
    const val MAX_GROWN_ODDS = 0.9f
    const val MAX_YOUNG_ODDS = 0.95f

    /**
     * Hunger: the share of animals of [s] that die in a step of [days], fed a share [fed] of their wants with their fat
     * drawn on ([onFat]), having burnt [spent] days of their body's protein before it. Below [WELL_FED] they would have drawn
     * on their fat for the rest, so it is gone: what they still lack they burn of their body's protein ([spentAfter]), and
     * an animal dies when it has burnt what carries it ([starveDays]), some sooner and some later ([starvedShare]).
     */
    fun hunger(s: Species, spent: Float, fed: Float, days: Int) = starvedRise(s, spent, spentAfter(s, spent, fed, days))

    /**
     * The share of animals of [s] alive having burnt [spent] days of their body's protein that die of it by [after] burnt
     * ([starvedShare]). Read off the body state at the step's two ends, so steps compound: ten steps of six days give the
     * odds of one of sixty at the same food.
     */
    fun starvedRise(s: Species, spent: Float, after: Float): Float {
        val was = starvedShare(s, spent); val now = starvedShare(s, after)
        return if (now > was) min(1f, (now - was) / max(1e-6f, 1f - was)) else 0f
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
     * Williams, Ostrowski, Bedin and Ismail, J Exp Biol 2001). People too ([HUMAN_ECONOMY]). Beasts of prey: not in the
     * record here, none.
     */
    fun economy(s: Species) = if (s.human) HUMAN_ECONOMY else if (s.carnivore) 0f else LEAN_ECONOMY
    const val LEAN_ECONOMY = 0.4f
    /**
     * The share of their need half-starved people save: the 32 men of the Minnesota experiment (Keys et al., The Biology of
     * Human Starvation, 1950) lived 24 weeks on about 1,570 kcal a day against 3,200 before, lost about a quarter of their
     * weight, their body heat, breathing and pulse fell, and none died. Their weight lost (about 17 kg, as fat and lean
     * tissue, roughly 100,000 kcal) paid only about 600 kcal a day of the 1,630 lacking: they spent about a third less.
     * (Reckoned here from those figures, not a figure of the study's own.)
     */
    const val HUMAN_ECONOMY = 0.35f
    /**
     * Days of burnt body a fully fed animal wins back a day: what it eats beyond its need when it can ([SURPLUS_EAT]),
     * laid on as fat is ([FAT_EFFICIENCY]); less as it is fed less, none at [WELL_FED].
     */
    val REBUILD = SURPLUS_EAT * FAT_EFFICIENCY

    /**
     * How well fed an animal is over a step of [days] fed [fed], with [fat] days of fat to draw on below [from] (as the week's
     * feeding draws on it): [WELL_FED] for packs and people, 1 for the residents, who draw on it for any shortfall. What it
     * saves by spending less ([economy], people's when passed) it need not draw: fed in full once the fat pays the rest.
     */
    fun onFat(fed: Float, fat: Float, days: Int, from: Float = WELL_FED, economy: Float = 0f): Float {
        if (fed >= from) return fed
        val short = max(0f, 1f - economy - fed) * days
        return if (fat >= short) 1f else fed + fat / days
    }

    /** [onFat] for a group of kind [s] (people's [economy] with it): the one way groups' hunger reads their fat. */
    fun onFatOf(s: Species, fed: Float, fat: Float, days: Int) = onFat(fed, fat, days, economy = economy(s))

    /** [fatDrawn] for a group of kind [s], as [onFatOf] draws it. */
    fun fatDrawnOf(s: Species, fed: Float, fat: Float, days: Int) = fatDrawn(fed, fat, days, economy = economy(s))

    /** The days of fat drawn over a step of [days] fed [fed], with [fat] to draw on, as [onFat] draws it: what it lacks beyond what it saves ([economy]). */
    fun fatDrawn(fed: Float, fat: Float, days: Int, from: Float = WELL_FED, economy: Float = 0f) =
        if (fed < from) min(fat, max(0f, 1f - economy - fed) * days) else 0f

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
    fun starveDays(s: Species) = if (s.human) HUMAN_STARVE_DAYS else STARVE_DAYS * (s.massKg / STARVE_KG).pow(0.25f)
    /**
     * People's last phase of a fast, days ([starveDays]): the scaling above gives 15, so that with their fat (about 22 days)
     * the first would die of a total fast at about 4 weeks. Fasting people die at 46 to 73 days (the ten hunger strikers of
     * 1981; others lived through 28 to 40, Peel, BMJ 1997, as Scientific American reports them). With [HUMAN_ECONOMY] on
     * the fat as on the body, 22 puts the first deaths of a total fast from full fat at about 7 weeks and half dead at about
     * 10, and kills none on half rations in 24 weeks (the Minnesota men).
     */
    const val HUMAN_STARVE_DAYS = 22f
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
     * ([HexDrinkingWater.dug]), or draw it from a well ([HexDrinkingWater.well]).
     */
    fun canDrink(drink: HexDrinkingWater, s: Species, p: Place): Boolean {
        if (s.human) { val fetch = s.dailyTravelKm; return drink.water[p] >= Thresholds.WET || drink.waterKm[p] <= fetch || drink.mustDig(p, fetch) || drink.drawsFromWell(p, fetch) }
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

    /**
     * The share of [heads] that deaths growing with their numbers take over a step of [ticks] ticks of [tickYears] each,
     * a tick taking [perYear] of its heads (at so many) a year: the heads fall over the step as they die, as they did from
     * week to week, so a step of many ticks does not charge the crowding it began with to the whole step (at 60 days a
     * crowded place lost to strife and crowd sickness what the weekly model's falling numbers soon stopped). At one tick
     * the callers keep their own expression, the weekly model unchanged to the bit.
     */
    inline fun fallingOver(heads: Float, ticks: Int, tickYears: Float, perYear: (Float) -> Float): Float {
        // (In closed form, not tick by tick: the hazard read at the heads halfway through the step, as they fall at the
        // hazard they began with, and taken over the whole step, 1 - exp(-h t). Weekly-rules plan, F1 at midpoint heads.)
        if (!(heads > 0f)) return 0f
        val years = ticks * tickYears
        val mid = heads * exp(-perYear(heads) * years / 2f)
        return 1f - exp(-perYear(mid) * years)
    }

    /** Sickness from crowding, over [years] (a week's share of a year), at [crowd] times its kind's usual density, fed [fed]: worse when underfed. */
    fun crowdDisease(crowd: Float, fed: Float, years: Float) = HexFauna.DISEASE * crowd * crowd * years * (1f + HexFauna.MALNOURISHED * (1f - fed))

    /** The share of animals of [s] that drown swimming [gapKm] of open water, before any cap. */
    fun drowning(s: Species, gapKm: Float) = DROWN * gapKm / Catalogue.swimKm(s).coerceAtLeast(1f)

    /**
     * Thirst over a step of [days] days in which the days without water went evenly from [before] to [after]: [thirst]
     * is a tick's odds at so many dry days, so -ln(1 - [thirst]) / [tickDays] is the hazard a day, and the step's odds are
     * 1 - exp of that hazard summed over the step's days, in closed form ([thirstHazard]). The same at any step length
     * (plan section 52.A.6): a step whose dry days stand still gives a tick's odds compounded over its days. None once it
     * drinks ([after] 0).
     */
    fun thirstOver(s: Species, before: Float, after: Float, days: Int, tickDays: Int): Float {
        if (after <= s.daysWithoutWater) return 0f
        val w = s.daysWithoutWater
        val start = min(before, after); val a = max(0f, start - w); val b = after - w
        // (The mean hazard over the whole ramp: the days of it still within what it bears count, at none.)
        val meanHazard = if (after - start < 1e-3f) -ln(1f - min(0.5f, THIRST_RISE * b)) else (thirstHazard(b) - thirstHazard(a)) / (after - start)
        return 1f - exp(-meanHazard * days / tickDays)
    }

    /**
     * The integral of -ln(1 - [thirst]) over dry days from [daysWithoutWater][Species.daysWithoutWater] to [u] days past
     * it: (z ln z - z + 1) / [THIRST_RISE] with z = 1 - [THIRST_RISE] u, up to where [thirst] reaches its cap of 0.5, and
     * ln 2 a day beyond.
     */
    private fun thirstHazard(u: Float): Float {
        val top = 0.5f / THIRST_RISE
        val v = min(u, top); val z = 1f - THIRST_RISE * v
        val under = (z * ln(z) - z + 1f) / THIRST_RISE
        return under + if (u > top) ln(2f) * (u - top) else 0f
    }
    private const val THIRST_RISE = 0.05f

    /** Thirst, after [dry] days without water. */
    fun thirst(s: Species, dry: Float) = if (dry > s.daysWithoutWater) min(0.5f, THIRST_RISE * (dry - s.daysWithoutWater)) else 0f
}
