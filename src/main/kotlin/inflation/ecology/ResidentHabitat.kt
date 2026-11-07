package inflation.ecology

import inflation.ecology.HexFauna.Companion.ASH_DEATHS
import inflation.ecology.HexFauna.Companion.ASH_HARM_MM
import inflation.ecology.HexFauna.Companion.BREED_SHIFT_GAIN
import inflation.ecology.HexFauna.Companion.FIRE_ADULT
import inflation.ecology.HexFauna.Companion.FIRE_YOUNG
import inflation.ecology.HexFauna.Companion.FLOOD_ADULT
import inflation.ecology.HexFauna.Companion.FLOOD_DROWN_M
import inflation.ecology.HexFauna.Companion.FLOOD_YOUNG
import inflation.ecology.HexFauna.Companion.HABITAT
import inflation.ecology.HexFauna.Companion.HIBERNATE_C
import inflation.ecology.HexFauna.Companion.REACH
import inflation.ecology.HexFauna.Companion.SNOW_DEATHS
import inflation.ecology.HexFauna.Companion.WATER_LAG_DAYS
import inflation.world.Place
import inflation.world.get
import inflation.world.set
import inflation.wrapMonth
import inflation.KgM2
import inflation.min
import inflation.world.HexLand
import inflation.world.Weather
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import java.util.concurrent.ConcurrentHashMap

// The residents and their country: how the climate, the ground and the water suit each kind, its winter sleep and breeding season, and the calamities of fire, flood and snow.

/**
 * How well the climate of place [p] suits [s] over the year, 0..1: 1 where every month's normal
 * temperature is within what its coat bears, falling off with each degree beyond.
 */
fun HexFauna.climateSuits(s: Species, p: Place): Float {
    var worst = 0f
    for (m in 0 until 12) worst = max(worst, s.discomfortC(feltNormalC(s, p, m), m))
    return exp(-worst / 5f)
}

/**
 * A share [perTick] that befalls or is taken up each tick (a week's), over the step's ticks: 1 - (1 - p)^ticks; itself at
 * one tick a step.
 */
internal fun HexFauna.overTicks(perTick: Float) = if (clock.ticks == 1) perTick else 1f - (1f - perTick.coerceIn(0f, 1f)).pow(clock.ticks)

/** How well the normal temperatures of month [m] and the next at place [p] suit [s]'s coat, 0..1. */
fun HexFauna.monthFit(s: Species, p: Place, m: Int): Float {
    val m1 = wrapMonth(m + 1)
    return exp(-(s.discomfortC(feltNormalC(s, p, m), m) + s.discomfortC(feltNormalC(s, p, m1), m1)) / 10f)
}

internal fun HexFauna.stepWater(weather: Weather) {
    val m = weather.date.month
    val k = 1f - exp(-days / WATER_LAG_DAYS)
    for (p in land.places) {
        val w = waterC[p]
        waterC[p] = if (w.isNaN()) waterNormalC[m][p] else w + (max(0f, weather.temperature[p]) - w) * k
    }
}

/**
 * The temperature [s] feels at place [p] with the air at [t] °C: a kind bound to water (hippos)
 * lies in it through the cold, as far as open water is at hand; ice shelters nothing.
 */
fun HexFauna.feltC(s: Species, p: Place, t: Float): Float = if (s.maxWaterKm.isInfinite()) t else refugeC(s, t, waterC[p], tether(s, p))

/** [feltC] in month [m] of an ordinary year, from the normals. */
fun HexFauna.feltNormalC(s: Species, p: Place, m: Int): Float =
    if (s.maxWaterKm.isInfinite()) hw.placeTemp(p, m) else refugeC(s, hw.placeTemp(p, m), waterNormalC[m][p], usualTether(s, p))

private fun HexFauna.refugeC(s: Species, t: Float, water: Float, share: Float): Float =
    if (water.isNaN() || water <= 0f || water <= t) t else t + share * (water - t)

/** Share of [s]'s diet that is plants. */
fun HexFauna.plantShare(s: Species): Float {
    val d = s.diet
    val all = d.total
    return if (all <= 0f) 1f else d.plants / all
}

/** Whether kind [k] is asleep in its winter den in place [p] this week (see [sleepOrWake]). */
fun HexFauna.asleep(k: FaunaKind, p: Place) = sleeping[k]?.get(p) ?: false

/**
 * Sleeping is a way through a lean winter, not a rule of the calendar: kinds that can hibernate lie up in their dens
 * where it is cold and they went hungry last week, with fat to sleep on, and sleep on while the cold lasts and their
 * fat holds out. Well fed, they stay awake.
 */
internal fun HexFauna.sleepOrWake(weather: Weather) {
    for ((k, z) in sleeping.withIndex()) {
        if (z == null) continue
        val f = fed[k]; val r = reserve[k]
        for (p in land.places) z[p] = weather.temperature[p] < HIBERNATE_C && r[p] > 0f && (z[p] || f[p] < Thresholds.HUNGRY)
    }
}

/**
 * Once a year: in each place, a kind's births move a month earlier or later where that puts them
 * in better-fed months for the mothers and the young (the birth month and the two after it).
 */
internal fun HexFauna.shiftBreeding() {
    for (k in kindIds) {
        val fm = fedByMonth[k]; val start = birthStart[k]
        for (p in land.places) {
            if (adults[k][p] <= 0f) continue
            fun window(m0: Int) = (0 until 3).sumOf { fm[p.i * 12 + wrapMonth(m0 + it)].toDouble() }
            val m = start[p.i].toInt()
            val now = window(m); val earlier = window(m - 1); val later = window(m + 1)
            if (earlier > now + BREED_SHIFT_GAIN && earlier >= later) start[p.i] = wrapMonth(m - 1).toByte()
            else if (later > now + BREED_SHIFT_GAIN) start[p.i] = wrapMonth(m + 1).toByte()
        }
    }
}

/**
 * Shares of [s]'s adults and young ([young]) that die this week in place [p] of fire, flood and
 * deep snow: fire catches the slow and the young on the share of the place that burns; a high
 * flood drowns some on the floodplain (not the kinds that live in the water); snow far deeper
 * than a kind can dig through starves and exhausts it.
 */
fun HexFauna.calamities(s: Species, p: Place, weather: Weather, young: Boolean): Calamities {
    val fire = land.fire.stepBurning[p] * (if (young) FIRE_YOUNG else FIRE_ADULT)
    val flood = if (s.maxWaterKm.isFinite() || land.hydro.flood[p] <= FLOOD_DROWN_M) 0f
        else land.floodShare[p] * (if (young) FLOOD_YOUNG else FLOOD_ADULT)
    val over = weather.snowpack[p] / s.snowLimitMm
    val snow = if (over <= 2f) 0f else min(0.1f, SNOW_DEATHS * (over - 2f)) * (if (young) 3f else 1f)
    // Grazing in fresh ash wears teeth and poisons with fluorine: counted with the snow, as a smothered winter.
    val ash = if (land.plants.ash[p] < ASH_HARM_MM) 0f else min(0.1f, ASH_DEATHS * land.plants.ash[p] / 50f) * (if (young) 2f else 1f)
    // (Snow and ash smother week by week: over a step of several ticks their shares compound; fire is the step's burning already.)
    return Calamities(fire, flood, overTicks(snow + ash))
}

/**
 * How well place [p] suits [s] by the ground it prefers, about 0.2..2.5: ibex the crags, roe deer
 * cover, boar wet thickets, aurochs open ground; 1 for a kind with no preference.
 */
fun HexFauna.suits(s: Species, p: Place): Float {
    val h = s.habitat
    val cover = land.plants.cover(p)
    val wet = land.floodShare[p]
    if (h.rugged == 0f) return exp(HABITAT * (h.cover * (cover - 0.5f) + h.wet * (wet - 0.2f)))
    // (The ground it likes is a share of the place: the valley floors of a rough hex for a plains kind,
    // its crags for a wild goat; it suits as the mean over its ground, see [HexLand.ruggedSpread].)
    val fits = ruggedFitOf ?: arrayOfNulls<FloatArray>(Catalogue.all.size).also { ruggedFitOf = it }
    val fit = fits[s.index] ?: ruggedFit(h.rugged).also { fits[s.index] = it }
    return exp(HABITAT * (h.cover * (cover - 0.5f) + h.wet * (wet - 0.2f))) * fit[p]
}

internal fun HexFauna.ruggedFit(rugged: Float): FloatArray = (ruggedFits ?: ConcurrentHashMap<Float, FloatArray>().also { ruggedFits = it }).getOrPut(rugged) {
    val q = land.ruggedSpread
    FloatArray(n) { p -> var v = 0f; for (k in q.indices) v += exp(HABITAT * rugged * (q[k][p] - 0.3f)); v / q.size }
}

/**
 * Share of place [p]'s food that [s] can use for its water: grazers that must drink every day or
 * two graze out from water and back, and leave the far country to those that live on the water
 * in their food (gazelle, oryx, camels). Kinds bound to water (hippos) use only what lies by it.
 */
fun HexFauna.tether(s: Species, p: Place): Float = tetherAt(s, p, land.drink.animalWater(p), land.drink.animalWaterKm(p))

/** [tether] in an ordinary year: from the lasting water around, not this week's. */
fun HexFauna.usualTether(s: Species, p: Place): Float = tetherAt(s, p, land.drink.nearRiver[p] + land.drink.nearLake[p] + land.drink.nearSpring[p],
    land.drink.waterKmMean[p])

private fun HexFauna.tetherAt(s: Species, p: Place, water: Float, waterKm: Float): Float {
    if (s.maxWaterKm.isFinite()) return min(1f, water)
    val reachKm = s.dailyTravelKm * s.daysWithoutWater / 4f * (1f + 10f * s.waterFromFood * s.waterFromFood)
    val r = waterKm / reachKm
    return 1f / (1f + r * r)
}

/**
 * What place [p] offers kind [s] to live on, on [foodFor]'s scale: its plant food, and for the residents' hunters
 * the game, small prey and carrion there too, each by its share of the diet. (Game counts as plant food would
 * where it is [PREY_HALF] kg per km² against [Thresholds.FOOD_HALF], so that both weigh alike in the kind's bearings.)
 */
fun HexFauna.fareFor(s: Species, p: Place): Float {
    if (!s.carnivore) return foodFor(s, p)
    val d = s.diet
    val plants = d.plants; val animal = d.animal
    if (plants + animal <= 0f) return 0f
    val kg = (d.meat * (preyNow?.get(slot(s))?.get(p) ?: game(s, p)) + d.smallPrey * small.smallPreyKg(p) + d.carrion * KgM2(small.carrion[p]).perKm2.v) / animal.coerceAtLeast(1e-6f)
    return (animal * kg * Thresholds.FOOD_HALF / PREY_HALF + plants * foodFor(s, p)) / (plants + animal)
}

/**
 * Food for [s] in place [p] from its diet, as standing plants, kg/m²: grass from herbs, and the
 * browse (shoots), fruit and roots stocks (counted as the standing plants that would give as much
 * in a week); less on cliffs it cannot climb.
 */
fun HexFauna.foodFor(s: Species, p: Place): Float {
    val d = s.diet
    val total = d.grass + d.browse + d.fruit + d.roots
    if (total <= 0f) return 0f
    val plants = land.palatable[p] * d.grass * land.plants.herb[p] +
        (d.browse * land.plants.browse(p) + d.fruit * food.fruit[p] + d.roots * food.underground(p)) / REACH
    return plants / total * HexFood.access(s, land.crag[p])
}

/** How much a newcomer of [s], resident or group, is drawn to place [p]: its ground, the climate and water in an ordinary year, by its area. */
fun HexFauna.startWeight(s: Species, p: Place) = land.areaKm2[p] * suits(s, p) * climateSuits(s, p) * usualTether(s, p)
