package inflation.ecology

import inflation.Profile
import inflation.world.Phase
import inflation.world.Hex
import inflation.world.HexArray
import inflation.world.HexList
import inflation.world.forEachHex
import inflation.world.hexArray
import inflation.world.toHexArray
import inflation.world.Place
import inflation.world.get
import inflation.world.set

import inflation.gaussianF
import inflation.KindStock
import inflation.StockName
import inflation.ecology.HexSmallLife.DeadPool
import inflation.Books
import inflation.Stocks
import inflation.Kg
import inflation.KgM2
import inflation.Tuning
import inflation.store.MovedInto
import inflation.wrapMonth
import inflation.saturate
import inflation.monthsFrom
import inflation.HASH_A
import inflation.HASH_B
import inflation.HASH_C
import inflation.mix64Once
import inflation.unitOf
import inflation.Config

import inflation.world.Date
import inflation.world.HexLand
import inflation.world.HexMap
import inflation.world.HexWorld
import inflation.world.Weather
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.min
import kotlin.random.Random
import inflation.world.Biome
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.stream.IntStream
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What every part of the groups' week reads of the world they live in: the hexes and their places ([hw]), the land with
 * its water and plants ([land]), the residents and small life ([fauna], [small]), the run's settings ([tuning]), the week's
 * tallies ([stats]) and the step's length ([days]); the groups' shared stream of chance ([rnd]) and their own streams; and
 * the questions about a place that need nothing of the groups themselves: what it offers a kind, its air, how far
 * hexes lie apart. The groups ([HexGroups]) are such a world with the groups in it.
 */
abstract class GroupWorld(
    internal val hw: HexWorld, internal val land: HexLand, internal val fauna: HexFauna, internal val days: Int, seed: Long,
) {
    internal val hex = hw.hex
    internal val n = land.n
    internal val tuning get() = land.tuning
    internal val rnd = Random(seed * 41 + 3)
    internal val seedBase = seed
    /** This week's tallies ([HexSim.stats]). */
    val stats get() = fauna.stats
    internal val years = Date.years(days)
    internal val small get() = fauna.small

    internal fun foodFor(s: Species, p: Place) = fauna.foodFor(s, p)

    /**
     * What place [p] offers a band for a camp, as game-weight per km²: the game it can hunt there, and
     * the fruit, nuts and roots it can gather (counted at their weight against meat), the more so where
     * the camp can stand on high, broken or water-girt ground safe from lions and raiders.
     */
    internal fun campValue(s: Species, p: Place): Float = preyHere(s, p) * campFactor(p) + campPlants(p)

    /**
     * What a camp's game is worth to a band, times: the more where the camp is safe, and the less the more of its days its
     * water ([waterShare]) and the hearth's firewood ([fuelShare]) take there, for a band whose every mouth fetches (the
     * place's worth to bands at large; its water at a temperate day's need, [WATER_MILD_C]). Where there is too little wood,
     * the cold and the raw meals it costs are in [drain].
     */
    internal fun campFactor(p: Place): Float {
        val water = waterShare(p, Catalogue.HUMAN.dailyTravelKm, waterLitres(WATER_MILD_C), 1f)
        val fuel = fuelShare(woodCover(p), 1f)
        return (1f + CAMP_SAFETY * land.bestDefence[p] + CAVE_CAMP * land.caves[p]) * (1f - fuel - water).coerceAtLeast(0f)
    }

    internal fun campPlants(p: Place) = KgM2(fauna.food.fruit[p] + rootsFound(p) + fauna.food.seeds[p] + fauna.food.grain[p] + fauna.food.fieldGrain[p]).perKm2.v *
        PLANT_FOOD_WEIGHT * campFactor(p)

    /**
     * The roots at [p] as a band's gathering would find them this week, kg/m²: the thinner they grow, the more of a
     * gatherer's day goes to finding them ([ROOT_SEARCH_M2_PER_DAY]), so they count as the share of the day that is digging
     * (none while the ground is frozen, [HexFood.diggable]); and the mast lying under the trees, as the share of its days
     * that is gathering it, the rest going to leaching its bitter share ([MAST_LEACH_DAYS_PER_KG]). (A hand's work at the
     * start rate, without skill or baskets: an approximation of [forage]'s own rate.)
     */
    internal fun rootsFound(p: Place): Float {
        val kg = fauna.food.diggable(p)
        val dug = ROOT_DIGGING * PLANT_HANDLING_DAYS_PER_KG * ROOT_SEARCH_M2_PER_DAY * kg
        return kg * dug / (1f + dug) + fauna.food.mast[p] * MAST_GATHERED
    }

    /**
     * The model's clock: the step [date] falls in (what the groups' "weeks" count: a step of [days] days), and how
     * many steps make a year, for counts of weeks compared against spans of years.
     */
    internal fun stepOf(date: Date) = (date.absoluteDay / days).toInt()

    internal val stepsPerYear get() = Date.stepsPerYear(days).toFloat()

    internal fun hexKm(a: Hex, b: Hex): Double = hex.distanceKm(a, b)

    internal fun territoryKm(s: Species) = sqrt(max(1f, s.startKm2PerAnimal) * s.groupSize / Math.PI.toFloat()) + 5f

    /** Whether kind [s]'s home range reaches beyond its hex, so that it counts its kind over the six round it too. */
    internal fun rangesWide(s: Species) = territoryKm(s) > 0.6f * hex.hexKm

    internal fun discomfort(s: Species, p: Place, weather: Weather): Float {
        return s.discomfortC(weather.temperature[p], weather.date.month) / 10f
    }

    /**
     * The week's weather at place [p] as people's body heat meets it ([BodyHeat]), at [t] °C: the swing between night and day
     * under a sky as cloudy as the week's rain and snow make it, the wind at a person's height (less in woods and thickets), the
     * rain that wets clothes, and whether snow lies.
     */
    internal fun airAt(p: Place, weather: Weather, t: Float = weather.temperature[p]): Air {
        val snow = weather.snowpack[p] > BodyHeat.SNOW_LIES_MM
        val wind = hypot(weather.windEast(p.i), weather.windSouth(p.i)) * BodyHeat.BODY_WIND * (1f - BodyHeat.FOREST_CALM * land.plants.cover(p))
        return Air(t, Air.swingC(Air.opaque(weather.precipitation[p] / days), snow), wind, Air.wet(weather.rain(p.i), days), snow)
    }

    /**
     * What the biting insects at [p] cost a group of kind [s] this week, as a share of the working time
     * people lose to them. Hunters lose nothing to them here, so they do not mind them.
     */
    internal fun bitten(s: Species, p: Place): Float {
        val h = small.harassment(p)
        return when {
            h <= 0f -> 0f
            s.human -> HexSmallLife.HARASS_TIME * h / (1f + HexSmallLife.HARASS_TIME * h)
            else -> 0f
        }
    }

    /** How crowded kind [s] is among [kinKm2] of its own kind per km², 0..1: the one measure ([Hazards.crowding]) as x / (1 + x). */
    internal fun crowdedBy(s: Species, kinKm2: Float): Float {
        val x = Hazards.crowding(kinKm2, HexFauna.usualDensity(s))
        return x / (1f + x)
    }

    /**
     * What place [p] offers a hunter of kind [s] by its whole diet, kg/km²: the big game it can catch
     * ([game]), the small prey on the ground and the carrion lying there, each by its share of the
     * diet. A jackal looks for rodents and carrion, a lion for big game.
     */
    internal fun dietValue(s: Species, p: Place, game: Float): Float {
        // Small game is searched for over a narrower strip than big game, and carrion only by those that eat it.
        val smallWorth = smallCatch(s) * SMALL_SENSE_KM / (s.senseKm * SEARCHING * CATCH)
        return game + smallWorth * small.smallPreyKg(p) + (if (s.diet.carrion > 0f) KgM2(small.carrion[p]).perKm2.v else 0f)
    }

    /** The resident game hunter [s] sees at place [p], kg per km² ([HexFauna.game], as resident hunters count it). */
    internal fun preyHere(s: Species, p: Place): Float = fauna.game(s, p)

    /** Mean moonlight over this week's nights, 0 (new moon) to 1 (full). */
    internal val moon get() = fauna.moon

    internal fun poisson(mean: Float, r: Random): Int = inflation.poisson(mean, r)

    internal fun binomial(n: Int, p: Float, r: Random): Int = inflation.binomial(n, p, r)

    /** A random stream of group [id]'s own for the week starting on [day]: the same whatever the cores do. */
    internal fun groupRandom(id: Int, day: Long) = Random(seedBase * 1_000_003L + id * 7_919L + day)

    /** A group's own stream for its choice of ground this week (apart from [groupRandom]'s, which its week's life draws on). */
    internal fun policyRandom(id: Int, day: Long) = Random((seedBase * 1_000_003L + id * 7_919L + day) * 0x9E3779B97F4A7C15uL.toLong() + 0x2545F4914F6CDD1DL)

    internal fun hashNoise(id: Int, p: Place): Float {
        return unitOf(mix64Once(id * HASH_A + p.i * HASH_B + seedBase * HASH_C))
    }
}
