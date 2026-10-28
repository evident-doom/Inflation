package inflation.ecology

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The weather people meet at a place over a week, as their body heat reckons it ([BodyHeat]): the week's mean air
 * temperature [meanC], °C; the swing between the night's cold and the day's warmth [swingC], °C (the diurnal range); the
 * wind at a person's height in the open there [windMs], m/s; the share of the week's hours clothes out in it are wet with
 * rain [wet]; and whether snow lies ([snow]).
 */
class Air(val meanC: Float, val swingC: Float, val windMs: Float, val wet: Float, val snow: Boolean) {
    /** The mean of the night's half of the day's swing (a sine: the mean of its lower half lies swing / π below the mean), °C. */
    val nightC get() = meanC - swingC / PI.toFloat()
    /** The mean of the day's half, °C. */
    val dayC get() = meanC + swingC / PI.toFloat()

    companion object {
        /**
         * The day's swing between night and day under a sky as cloudy as [opaque] (0 clear to 1 overcast), °C: on bare
         * ground 16.7 - 9.3 o - 6.0 o², over snow 5.7 - 6.7 o + 0.116 SWn, net shortwave SWn taken as 30 W/m² (low winter sun
         * on snow that sends most back; an estimate). Fitted by Betts and colleagues to hourly records on the Canadian Prairies
         * (the fits as found; the papers' titles not checked here).
         */
        fun swingC(opaque: Float, snow: Boolean) =
            if (snow) max(0f, 5.7f - 6.7f * opaque + 0.116f * SNOW_SWN) else max(0f, 16.7f - 9.3f * opaque - 6.0f * opaque * opaque)

        /** How cloudy the week's sky is from the rain and snow that fell in it ([fellMmDay], mm a day): an estimate. */
        fun opaque(fellMmDay: Float) = 1f - exp(-fellMmDay / CLOUD_MM_DAY)

        /**
         * The share of the hours clothes out in the weather are wet in a step of [days] with [rainMm] of rain: the hours it
         * rains at [RAIN_MM_H], drawn out [WET_LINGER] times as long while hides dry (both estimates).
         */
        fun wet(rainMm: Float, days: Int) = 1f - exp(-WET_LINGER * rainMm / (RAIN_MM_H * 24f * days))

        const val SNOW_SWN = 30f
        const val CLOUD_MM_DAY = 2f
        const val RAIN_MM_H = 1.5f
        const val WET_LINGER = 3f
    }
}

/**
 * What a band has against the cold, as [BodyHeat] reckons with it: the insulation of the clothes its people wear by day,
 * clo ([clothesClo]); its fire, 0..1; the brush shelters at its camp ([shelter], as [Band.shelter]: 1 a whole one for
 * everyone, more for sturdier); how far it sleeps in houses ([home], 0..1); and how much warmer than the air a house keeps
 * ([houseWarmC], °C).
 */
class Cover(val clothesClo: Float, val fire: Float, val shelter: Float, val home: Float, val houseWarmC: Float)

/**
 * A week's cold for a band, from its body heat ([BodyHeat.week]): the share more food its people eat to make the heat they
 * lose beyond what their day's work and rest make ([extra]); the share of them it kills, of exposure ([odds]); the
 * worst heat debt of a day, beyond what the body bears, Wh per m² of skin ([debt]); the share of its working hours out in
 * the weather it works, the rest spent warming up ([pace], 1 when the work keeps it warm); and the share of its hours of
 * crafts at camp or at home its hands can work ([campPace]).
 */
class Warmth(val extra: Float, val odds: Float, val debt: Float, val pace: Float = 1f, val campPace: Float = 1f)

/**
 * People's body heat in the cold: the heat balance of ISO 11079:2007 (Annex A, as the required clothing insulation IREQ is
 * worked out; code as in pythermalcomfort 4.6), through a day of three parts as the band's time reckons it ([Activity]):
 * [SLEEP_H] asleep at the resting rate, lying on the ground at night; [WORK_H] at work out in the day's weather at a
 * foraging day's rate ([Activity.FOOD]); and the rest about camp at 1.45 times resting, by the fire.
 *
 * At each the body loses heat by the dry way through its clothes and the air about it (insulation worn down by wind and
 * walking, and by wet), by sweat through the skin, by breath, and asleep through the ground it lies on. Where it loses more
 * than it makes, it first shivers to make up the difference, which costs food ([Warmth.extra]); beyond what it can keep up
 * for hours ([SUSTAINED]) it cools. Beyond the stored heat ISO 11079 sets as the limit of tolerable cooling ([QLIM]) the
 * cooling grows dangerous, and the deeper the debt the more die ([lethal]).
 *
 * The lesser means of keeping warm count where they act: a shelter breaks the wind, keeps the rain off and holds a little
 * of the camp's warmth, and its bedding of grass, reeds or boughs keeps the body off the cold ground; a fire warms those
 * about it, less through the night when it is banked; robes and hides piled on in the night add to the day's clothes; a
 * house is still air, dry, warmer by its hearth and the ground's warmth through sunken walls, with beds.
 */
object BodyHeat {
    /**
     * A week of [days] at [air] with [cover], for people who work on through the cold as far as their perseverance [sisu]
     * (0..1, see [day]): what keeping warm takes, how many it kills and how much of their working time they work.
     */
    fun week(air: Air, cover: Cover, days: Int, sisu: Float = 1f): Warmth {
        val open = day(air, cover, house = false, sisu)
        if (cover.home <= 0f) return week(open, days)
        val house = day(air, cover, house = true, sisu)
        val h = min(1f, cover.home)
        return week(DayHeat(h * house.extra + (1f - h) * open.extra, h * house.share + (1f - h) * open.share,
            h * house.debt + (1f - h) * open.debt, h * house.pace + (1f - h) * open.pace, h * house.campPace + (1f - h) * open.campPace), days)
    }

    private fun week(d: DayHeat, days: Int) = Warmth(d.extra, 1f - (1f - d.share).pow(days), d.debt, d.pace, d.campPace)

    /** One day's heat: the extra food a share of the day's need, the share that die of it, the worst debt, and the share of the working hours worked out and at camp. */
    private class DayHeat(val extra: Float, val share: Float, val debt: Float, val pace: Float, val campPace: Float)

    /**
     * One day's heat ([BodyHeat]). Where the work out in the weather cools the body faster than shivering makes up, people
     * break off to warm up by the fire at camp, for as long as it takes to win back what the work loses (at the camp's rate,
     * shivering as hard as they can keep up): those breaks are working time lost. People who persevere ([sisu]) break off
     * less, and carry the cooling as heat debt instead, at the risk of exposure: at 1 they work through, at 0 they warm up
     * whenever they must. Where even the camp does not warm them, every hour out at work deepens the debt faster than one by
     * the fire: those who do not persevere stay in.
     *
     * Crafts at camp (or, at home, in the houses: [Workplace.CAMP]) need hands that can work: where even the camp, or the
     * house, cools people sitting at a craft faster than shivering makes up, their hands go numb and the crafts stop,
     * however they persevere; a house's still, warm air keeps them going where an open camp would not. (The hands'
     * cold is not in it: dexterity falls once the fingers cool below about 20 °C, steeply below 15 °C, Wiggen et al. 2011,
     * Industrial Health 49: 443, after Heus et al. 1995; the fingers' temperature is not reckoned here.)
     */
    private fun day(air: Air, c: Cover, house: Boolean, sisu: Float): DayHeat {
        val rest = REST_WM2
        val camp = CAMP_PAR * rest
        val work = Activity.FOOD.par * rest
        // At camp and asleep: in a house, still dry air warmed by its hearth and the ground, with beds; at a camp, the wind as
        // the shelters break it, the rain as their roofs keep it off, a little warmth held under them and the fire's.
        // (A light hut keeps off the wind and rain and holds the bedding; only its thatching holds any warmth in: [shelterWork].)
        val hut = min(1f, c.shelter); val thatch = max(0f, c.shelter - 1f)
        val inWind = if (house) HOUSE_AIR_MS else air.windMs * (1f - WINDBREAK).pow(hut)
        val inWet = if (house) 0f else air.wet * (1f - ROOF).pow(hut)
        val warmer = if (house) c.houseWarmC else SHELTER_C * thatch
        val bed = if (house) BED_R else BED_R * hut
        val nightClo = c.clothesClo * (1f + NIGHT_COVER)
        val ground = if (air.snow) min(0f, air.meanC) else air.meanC
        // (A house's hearth warms its air, [Cover.houseWarmC]; those beside it, at a camp or in a house, it warms besides.)
        val campC = air.meanC + warmer + FIRE_CAMP_C * c.fire
        val sleepC = air.nightC + warmer + FIRE_SLEEP_C * c.fire
        val lCamp = loss(camp, campC, inWind, 0f, c.clothesClo, inWet)
        val lSleep = loss(rest, sleepC, inWind, 0f, nightClo, inWet) * (1f - LYING_CONTACT) + breath(rest, sleepC) * LYING_CONTACT +
            LYING_CONTACT * (skinC(rest) - ground) / (CONTACT_R + bed + if (air.snow) SNOW_R else SOIL_R)
        val lWork = loss(work, air.dayC, air.windMs, WALK_MS, c.clothesClo, air.wet)
        // (Breaks to warm up: the share of the working hours spent by the fire so that the hours together lose no more than
        // shivering makes up, less as people persevere.)
        val shiverMost = (SUSTAINED - 1f) * rest
        val chill = max(0f, lWork - work - shiverMost)
        val warming = camp + shiverMost - lCamp
        val breaks = if (chill > 0f) (1f - sisu.coerceIn(0f, 1f)) * (if (warming > 0f) chill / (chill + warming) else 1f) else 0f
        val atWork = work * (1f - breaks) + camp * breaks
        // (Crafts: sitting at the camp's, or the house's, air and wind.)
        val craft = Activity.TOOLS.par * rest
        val craftChill = max(0f, loss(craft, campC, inWind, 0f, c.clothesClo, inWet) - craft - shiverMost)
        val campPace = if (craftChill > 0f) 0f else 1f
        // The day in order: the evening about camp, the night, the morning, the work; heat lost beyond what it makes and can
        // shiver up is stored as debt, made good again while it makes more than it loses.
        var stored = 0f; var worst = 0f; var shivered = 0f
        fun part(m: Float, lost: Float, hours: Float) {
            val shiver = min(max(0f, lost - m), (SUSTAINED - 1f) * rest)
            shivered += shiver * hours
            stored = min(0f, stored + (m + shiver - lost) * hours)
            worst = min(worst, stored)
        }
        val campH = 24f - SLEEP_H - WORK_H
        part(camp, lCamp, campH / 2f); part(rest, lSleep, SLEEP_H); part(camp, lCamp, campH / 2f)
        part(atWork, lWork * (1f - breaks) + lCamp * breaks, WORK_H)
        val made = rest * SLEEP_H + camp * campH + atWork * WORK_H
        val debt = max(0f, -worst - QLIM)
        return DayHeat(shivered / made, lethal(debt), debt, 1f - breaks, campPace)
    }

    /**
     * The share of people a heat debt of [debt] Wh/m² beyond [QLIM] kills: half at [LETHAL_WH_M2], fewer the shallower, as
     * people differ in size, fat and age ([LETHAL_SPREAD]).
     */
    fun lethal(debt: Float) = if (debt <= 0f) 0f else 1f / (1f + (LETHAL_WH_M2 / debt).pow(LETHAL_SPREAD))

    /** The skin's temperature as the body lets it cool to hold its heat at [m] W/m² (ISO 11079's lowest bearable), °C. */
    private fun skinC(m: Float) = 33.34f - 0.0354f * m

    /**
     * Heat lost a m² of skin at [m] W/m² in air at [airC] blowing [windMs] at a person walking [walkMs], in clothes of [clo]
     * wet for a share [wet] of the time: the dry way, through the clothes' resultant insulation (ISO 11079 A: worn down by
     * wind and walking at the clothes' air permeability [PERMEABILITY]), sweat at the least skin wetness, and breath.
     */
    private fun loss(m: Float, airC: Float, windMs: Float, walkMs: Float, clo: Float, wet: Float): Float {
        val skin = skinC(m)
        val i = clo * CLO * (1f - WET_INSULATION * wet)
        val fcl = 1f + 1.197f * i
        val corr = 0.54f * exp(-0.15f * windMs - 0.22f * walkMs) * PERMEABILITY.pow(0.075f) - 0.06f * ln(PERMEABILITY) + 0.5f
        val r = (i + 0.085f / fcl) * corr
        val dry = (skin - airC) / r
        val sweat = 0.06f * (vapour(skin) - HUMIDITY * vapour(airC)) / (0.06f / 0.38f * r)
        return dry + sweat + breath(m, airC)
    }

    /** Heat lost a m² of skin in breath at [m] W/m² in air at [airC] (ISO 11079 A). */
    private fun breath(m: Float, airC: Float): Float {
        val out = 29f + 0.2f * airC
        return 1.73e-2f * m * (vapour(out) - HUMIDITY * vapour(airC)) + 1.4e-3f * m * (out - airC)
    }

    /** Saturated water vapour pressure at [c] °C, kPa (ISO 11079 A). */
    private fun vapour(c: Float) = 0.1333f * exp(18.6686f - 4030.183f / (c + 235f))

    /** Thermal resistance of a half-space of ground warmed from above for half a night: sqrt(π κ t) / k, m²K/W. */
    private fun groundR(k: Float, kappa: Float) = sqrt(PI.toFloat() * kappa * SLEEP_H * 3600f / 2f) / k

    /**
     * The resting rate, W per m² of skin: between sleeping's 0.7 met and quiet sitting's 1.0 (1 met = 58.2 W/m²; ASHRAE
     * Handbook, Fundamentals, ch. 9); the day's hours asleep and at work, as [Activity] counts them; about camp 1.45 times
     * resting (FAO/WHO/UNU 2004, light leisure and sitting).
     */
    const val REST_WM2 = 46.5f
    const val SLEEP_H = 8f
    const val WORK_H = 6f
    const val CAMP_PAR = 1.45f
    /** One clo, m²K/W. */
    const val CLO = 0.155f
    /**
     * The most heat the body keeps up for hours by shivering, against the resting rate: peak shivering reaches about 4.9
     * times resting (Eyolfson et al. 2001, J Appl Physiol 90: 1253); held for hours, about half of that (an estimate from
     * Haman et al. 2002 and 2004, J Appl Physiol, whose figures were not checked here).
     */
    const val SUSTAINED = 2.5f
    /** The body heat that may be lost before the cooling is no longer tolerable, Wh/m² (ISO 11079 DLE: Qlim). */
    const val QLIM = 40f
    /**
     * The heat debt beyond [QLIM] that kills half, Wh/m²: the body cooled by about 8 °C on the mean (deep hypothermia, the
     * core near 28 °C) at a body's 3.47 kJ/kg K over 70 kg and 1.8 m² of skin; how sharply deaths rise about it, as people
     * differ (both estimates).
     */
    const val LETHAL_WH_M2 = 300f
    const val LETHAL_SPREAD = 6f
    /**
     * The wind at a person's height against the weather's at 10 m: ln(1.5 / z0) / ln(10 / z0) for open grassland's
     * roughness z0 of 0.03 m (the log wind profile).
     */
    const val BODY_WIND = 0.67f
    /** How much of the open's wind woods and thickets at full cover keep off (an estimate). */
    const val FOREST_CALM = 0.8f
    /**
     * How much of the wind a whole brush shelter keeps off those behind and in it: windbreaks cut the wind by up to 70-80%
     * close in their lee (Brandle, Hodges and Zhou 2004, "Windbreaks in North American agricultural systems", Agroforestry
     * Systems 61: 65-78, as recalled, not checked here); the air in a house, m/s (still air).
     */
    const val WINDBREAK = 0.7f
    const val HOUSE_AIR_MS = 0.1f
    /** How much of the rain a whole brush shelter's roof keeps off (an estimate). */
    const val ROOF = 0.9f
    /**
     * How much of the clothes' insulation they lose wet through: about 30% (Zhao et al. 2025, Building and Environment 267:
     * 112299, fully wetted ensembles on a sweating manikin; a wet mid layer alone 9-16%, Bröde et al. 2008, Eur J Appl
     * Physiol).
     */
    const val WET_INSULATION = 0.3f
    /** The clothes' air permeability, l/m²s: hides and leather let little wind through (an estimate). */
    const val PERMEABILITY = 10f
    /** The air's relative humidity (no record of it in the weather: an estimate). */
    const val HUMIDITY = 0.7f
    /** Walking pace at work, m/s (gathering and searching, slow: an estimate). */
    const val WALK_MS = 0.5f
    /** How much the hides and robes piled on in the night add to the day's clothes, as a share of them (an estimate). */
    const val NIGHT_COVER = 1f
    /**
     * How much warmer a fire keeps those about it, awake by it and asleep beside it banked, °C at a whole fire (estimates);
     * a hut thatched whole, by the warmth of the bodies in it (as [SHELTER_C]).
     */
    const val FIRE_CAMP_C = 10f
    const val FIRE_SLEEP_C = 5f
    /** The share of the body's skin on the ground lying asleep (an estimate); the resistance of the clothes pressed under it, m²K/W (an estimate). */
    const val LYING_CONTACT = 0.2f
    const val CONTACT_R = 0.03f
    /**
     * A whole shelter's bedding, m²K/W: 3 cm of grass, reeds or straw pressed under the body (an estimate) at straw's 0.052-0.065
     * W/m K (Buildings 7(1): 11, 2017).
     */
    const val BED_R = 0.5f
    /**
     * The ground's resistance under a sleeper ([groundR]): moist soil at 1 W/m K and 5e-7 m²/s (Hillel 1998, Environmental
     * Soil Physics; estimates for a moist loam); snow at 0.2 W/m K and 3e-7 m²/s (a settled snow: estimates).
     */
    val SOIL_R = groundR(1f, 5e-7f)
    val SNOW_R = groundR(0.2f, 3e-7f)
    /** Snow lying deeper than this is snow underfoot, mm of water. */
    const val SNOW_LIES_MM = 10f
}
