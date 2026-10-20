package inflation.ecology

import inflation.world.Weather
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import inflation.world.Date
import java.util.stream.IntStream
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * How a pack sets its ways for the week, as bands do theirs ([BandWays]): given what it knows of itself
 * ([animalInputs], [ANIMAL_WAYS_IN] numbers), it writes [ANIMAL_WAYS_OUT] numbers to `out`, each 0 for
 * the hand-written way. One network for all hunters; kinds differ in what they are (their traits are
 * among the inputs), not in the weights.
 */
fun interface AnimalWays { fun choose(g: HexGroup, inputs: FloatArray, out: FloatArray) }

val ANIMAL_WAYS_IN = AnimalInputs.size
const val ANIMAL_WAYS_OUT = 6

/**
 * What each output means. Each is read through `2 / (1 + e^-x)`: 1 (the hand-written way) at an output of 0, from
 * never (0) to twice as readily (2).
 */
object AnimalWay {
    /** How long it kept on chasing herds it met (kept for the layout until retraining: there are no herds to chase). */
    const val CHASE = 0
    /** How readily it robs a weaker hunter of another kind of its kill. */
    const val STEAL = 1
    /** How readily it kills one of a much weaker hunter of another kind (its young most often). */
    const val KILL = 2
    /** How much stronger a rival pack has to be before it gives way rather than fights. */
    const val FIGHT = 3
    /** How far a lone hunter looks for a mate. */
    const val MATE = 4
    /** How readily a pack without ground takes the unmarked ground it is on. */
    const val SETTLE = 5

    /** Each way's name, by its slot, as a learned network's `.names` file records it (the strings must not change). */
    val HUNTER_NAMES: List<String> = arrayOfNulls<String>(ANIMAL_WAYS_OUT).also {
        it[CHASE] = "chasing"; it[STEAL] = "stealing kills"; it[KILL] = "killing other hunters"; it[FIGHT] = "fighting rivals"; it[MATE] = "seeking a mate"
        it[SETTLE] = "settling"
    }.mapIndexed { k, n -> requireNotNull(n) { "hunter way $k has no name" } }
}

/** What a pack knows of itself this week: the season, how fed and watered it is, its reserves, size and young, its fear, its
 * ground, the warmth, and what kind of animal it is. */
internal fun HexGroups.animalInputs(g: HexGroup, weather: Weather, into: FloatArray) {
    val s = g.species
    val x = AnimalInputs
    val angle = 2.0 * Math.PI * weather.date.dayOfYear / Date.DAYS_PER_YEAR
    into[x.YEAR_SIN] = sin(angle).toFloat(); into[x.YEAR_COS] = cos(angle).toFloat()
    into[x.FED] = g.fed
    into[x.FAT] = g.fat / s.maxFatDays.coerceAtLeast(1f)
    into[x.DAYS_DRY] = min(2f, g.dry / s.daysWithoutWater)
    into[x.OWN_SIZE] = ln(g.size.toFloat().coerceAtLeast(1f) / unitSize(s)) / 2f
    into[x.YOUNG] = g.young.toFloat() / max(1, g.adults)
    // (Kept for the layout until retraining: no pack is chased, so it is never wary.)
    into[x.WARY] = 0f
    into[x.HOLDS_GROUND] = if (!g.home.none) 1f else 0f
    into[x.WEEKS_HUNGRY] = min(1f, g.lean.toFloat() / GIVE_UP_WEEKS)
    into[x.TEMPERATURE] = weather.temperature[g.place] / 30f
    // What it is: its weight, and what it eats.
    val d = s.diet
    into[x.MASS] = ln(s.massKg.coerceAtLeast(1f)) / 5f
    into[x.GRASS] = d.grass; into[x.BROWSE] = d.browse; into[x.FRUIT_AND_ROOTS] = d.fruit + d.roots
    into[x.MEAT] = d.meat; into[x.SMALL_PREY] = d.smallPrey; into[x.CARRION] = d.carrion
    // Its temperament.
    for (k in 0 until Temperament.AXES) into[x.TEMPER + k] = g.temper.getOrElse(k) { 0f }
}

/** The name of each of [animalInputs]' numbers, in order, as a learned network's `.names` file records them (the strings must not change). */
val ANIMAL_INPUT_NAMES: List<String> get() = AnimalInputs.names

/** Sets a pack's ways from [ways]' outputs. */
internal fun HexGroups.setAnimalWays(g: HexGroup, weather: Weather, ways: AnimalWays, inputs: FloatArray, out: FloatArray) {
    animalInputs(g, weather, inputs)
    out.fill(0f)
    ways.choose(g, inputs, out)
    if (g.id == watched) control?.ways(g, out)
    for (k in 0 until ANIMAL_WAYS_OUT) g.will[k] = 2f / (1f + exp(-out[k]))
}

/** Each pack sets its ways for the week, on all cores (each only from its own state). */
internal fun HexGroups.setAnimalWays(weather: Weather) {
    val hunters = hunterWays ?: return
    val inputs = ThreadLocal.withInitial { FloatArray(ANIMAL_WAYS_IN) }; val out = ThreadLocal.withInitial { FloatArray(ANIMAL_WAYS_OUT) }
    IntStream.range(0, groups.size).parallel().forEach { k ->
        val g = groups[k]
        if (!g.alive || g.species.human) return@forEach
        setAnimalWays(g, weather, hunters, inputs.get(), out.get())
    }
}
