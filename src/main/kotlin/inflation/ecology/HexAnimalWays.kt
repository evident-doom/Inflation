package inflation.ecology

import inflation.world.Weather
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * How a herd or a pack sets its ways for the week, as bands do theirs ([BandWays]): given what it knows
 * of itself ([animalInputs], [ANIMAL_WAYS_IN] numbers), it writes [ANIMAL_WAYS_OUT] numbers to `out`,
 * each 0 for the hand-written way. One network for each role (herds, hunters); kinds differ in what
 * they are (their traits are among the inputs), not in the weights.
 */
fun interface AnimalWays { fun choose(g: HexGroup, inputs: FloatArray, out: FloatArray) }

const val ANIMAL_WAYS_IN = 18
const val ANIMAL_WAYS_OUT = 6

/**
 * What each output means, by role. Each is read through `2 / (1 + e^-x)`: 1 (the hand-written way) at
 * an output of 0, from never (0) to twice as readily (2).
 */
object AnimalWay {
    // Herds.
    /** How big it lets itself grow before it splits, and how big a herd it joins: against its kind's (and its own leaning's) size. */
    const val HERD_SIZE = 0
    /** How much it leans to grass, browse, and fruit and roots, against its kind's diet. */
    const val GRASS = 1
    const val BROWSE = 2
    const val FRUIT = 3
    /** How much more it eats to lay on reserves when there is food to spare. */
    const val FATTEN = 4
    /** Its births: under 1 a month earlier this year, over 1 a month later, when far enough from 1; near 1 they drift towards the months it fed best in, as they always did. */
    const val BIRTHS = 5
    // Hunters.
    /** How long it keeps on chasing herds it meets: until it has this many times its week's need. */
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
    /** The births lean beyond which a herd moves its births itself. */
    const val BIRTHS_LEAN = 0.5f
}

/** What a herd or pack knows of itself this week: the season, how fed and watered it is, its reserves, size and young, its fear, its ground, the warmth, and what kind of animal it is. */
internal fun HexGroups.animalInputs(g: HexGroup, weather: Weather, into: FloatArray) {
    val s = g.species
    var i = 0
    val angle = 2.0 * Math.PI * weather.date.dayOfYear / inflation.world.Date.DAYS_PER_YEAR
    into[i++] = kotlin.math.sin(angle).toFloat(); into[i++] = kotlin.math.cos(angle).toFloat()
    into[i++] = g.fed
    into[i++] = g.fat / s.maxFatDays.coerceAtLeast(1f)
    into[i++] = min(2f, g.dry / s.daysWithoutWater)
    into[i++] = ln(g.size.toFloat().coerceAtLeast(1f) / unitSize(s)) / 2f
    into[i++] = g.young.toFloat() / max(1, g.adults)
    into[i++] = g.wary
    into[i++] = if (g.home >= 0) 1f else 0f
    into[i++] = min(1f, g.lean.toFloat() / GIVE_UP_WEEKS)
    into[i++] = weather.temperature[g.place] / 30f
    // What it is: its weight, and what it eats.
    val d = s.diet
    into[i++] = ln(s.massKg.coerceAtLeast(1f)) / 5f
    into[i++] = d.grass; into[i++] = d.browse; into[i++] = d.fruit + d.roots
    into[i++] = d.meat; into[i++] = d.smallPrey; into[i++] = d.carrion
}

/** Sets a herd's or pack's ways from [ways]' outputs. */
internal fun HexGroups.setAnimalWays(g: HexGroup, weather: Weather, ways: AnimalWays, inputs: FloatArray, out: FloatArray) {
    animalInputs(g, weather, inputs)
    out.fill(0f)
    ways.choose(g, inputs, out)
    for (k in 0 until ANIMAL_WAYS_OUT) g.will[k] = 2f / (1f + kotlin.math.exp(-out[k]))
}

/** Each herd and pack sets its ways for the week, on all cores (each only from its own state). */
internal fun HexGroups.setAnimalWays(weather: Weather) {
    val herds = herdWays; val hunters = hunterWays
    if (herds == null && hunters == null) return
    val inputs = ThreadLocal.withInitial { FloatArray(ANIMAL_WAYS_IN) }; val out = ThreadLocal.withInitial { FloatArray(ANIMAL_WAYS_OUT) }
    java.util.stream.IntStream.range(0, groups.size).parallel().forEach { k ->
        val g = groups[k]
        if (!g.alive || g.species.human) return@forEach
        val ways = (if (g.species.carnivore) hunters else herds) ?: return@forEach
        setAnimalWays(g, weather, ways, inputs.get(), out.get())
    }
}
