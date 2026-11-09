package inflation.ecology

import inflation.ecology.HexSmallLife.Companion.CHUKAR_AT
import inflation.ecology.HexSmallLife.Companion.EAGLE_HALF
import inflation.ecology.HexSmallLife.Companion.EAGLE_NEED
import inflation.ecology.HexSmallLife.Companion.EAGLE_RATE
import inflation.ecology.HexSmallLife.Companion.FLIGHT_REACH
import inflation.ecology.HexSmallLife.Companion.FOX_HALF
import inflation.ecology.HexSmallLife.Companion.FOX_INTAKE
import inflation.ecology.HexSmallLife.Companion.INSECT_CATCH
import inflation.ecology.HexSmallLife.Companion.OWL_HALF
import inflation.ecology.HexSmallLife.Companion.OWL_INTAKE
import inflation.ecology.HexSmallLife.Companion.OWL_SEED
import inflation.ecology.HexSmallLife.Companion.ROCK_DOVE_AT
import inflation.Part
import inflation.world.Place
import inflation.world.get
import inflation.world.set
import inflation.Kg
import inflation.KgKm2
import inflation.KgM2
import inflation.min
import inflation.world.Weather
import kotlin.math.max
import kotlin.math.min

// The small hunters and scavengers: foxes, wildcats and owls, eagles on their crags, vultures soaring from their colonies.

/** Foxes and wildcats at place [p], and the owls and small hawks that hunt the same rodents. */
internal fun HexSmallLife.stepFoxesAndOwls(p: Place, month: Int) {
    // Foxes and wildcats eat about a fourteenth of their weight a day: carrion where they find it, then
    // rodents, hares and birds; they breed in spring as the food allows, and die back when it fails.
    // (They stalk: what they reach of each, as [reach]; the young in the nest they take as found.)
    val eB = reachable(birds[p], young[Nest.BIRDS][p], FLIGHT_REACH)
    val prey = rodents[p] + hares[p] + eB * birds[p] + locusts[p] + INSECT_CATCH * (insects[p] + pollinators[p])
    val need = foxes[p] * FOX_INTAKE * days
    // Carrion first: a carcass they smell out costs no chase, and in winter, when the rodents keep
    // under the snow and much lies dead, it is much of what they live on. At most half of what lies
    // there in a week, found as readily as prey.
    val dead = carrion[p] * 1e6f
    val scavenged = scavengeFirst(p, dead, need, FOX_HALF)
    val left = need - scavenged
    val r0 = rodents[p]; val ha0 = hares[p]; val hy0 = hyrax[p]; val b0 = birdSum(p)
    val got = scavenged + takeSwitching(p, min(0.5f * prey, left * prey / (prey + FOX_HALF)), 0.5f, foxPrey,
        floatArrayOf(1f, 1f, 1f, INSECT_CATCH, INSECT_CATCH) + FloatArray(bird.size) { eB })
    ate(p, got)
    if (b0 > 0f && eB > 0f) young[Nest.BIRDS][p] = max(0f, young[Nest.BIRDS][p] - (b0 - birdSum(p)) * min(1f, young[Nest.BIRDS][p] / (eB * b0)))
    if (r0 > 0f) young[Nest.RODENTS][p] *= rodents[p] / r0; if (ha0 > 0f) young[Nest.HARES][p] *= hares[p] / ha0
    noteTaken(p, Taker.FOXES, r0, ha0, hy0, b0)
    val fed = if (need > 0f) got / need else 1f
    // A litter of four to six a year, born in spring: over the three months, six times the stock a year.
    // Owls and small hawks eat about a fifth of their weight a day, mostly rodents.
    run {
        val r = rodents[p]
        if (owls[p] <= 0f && r > 0f) owls[p] = OWL_SEED * r
        val needO = owls[p] * OWL_INTAKE * days
        val gotO = min(0.5f * r, needO * r / (r + OWL_HALF))
        if (gotO > 0f) { rodents[p] -= gotO; preyTakenBy[Prey.RODENTS][Taker.OWLS][p] += gotO; ate(p, gotO) }
        val fedO = if (needO > 0f) gotO / needO else 1f
        owls[p] = logistic(p, owls[p], if (month in 3..5) 3f * fedO else 0f, 0.3f + 0.8f * (1f - fedO), 0.02f * r)
    }
    foxes[p] = logistic(p, foxes[p], if (month in 4..6) 6f * fed else 0f, 0.4f + 0.8f * (1f - fed), 0.02f * (prey + dead))
}

/** Eagles at place [p] (where there are crags): a pair needs about a kilo a day; they take what is out in the open among the rocks. */
internal fun HexSmallLife.stepEagles(p: Place, month: Int) {
    val open = open(p)
    val prey = (hyrax[p] + hares[p] + bird[CHUKAR_AT][p] + bird[ROCK_DOVE_AT][p]) * (0.3f + 0.7f * open)
    val need = eagles[p] * EAGLE_NEED * days
    // Carrion first, as the foxes: a carcass seen from the air costs no chase, and in winter it is
    // much of what they live on. They see it as they see prey, best in the open.
    val dead = carrion[p] * 1e6f * (0.3f + 0.7f * open)
    val scavenged = scavengeFirst(p, dead, need, EAGLE_HALF)
    val left = need - scavenged
    val r0 = rodents[p]; val ha0 = hares[p]; val hy0 = hyrax[p]; val b0 = birdSum(p)
    val got = scavenged + takeSwitching(p, min(0.5f * prey, left * prey / (prey + EAGLE_HALF)), 0.5f, eaglePrey)
    ate(p, got)
    noteTaken(p, Taker.EAGLES, r0, ha0, hy0, b0)
    // Fawns and kids make up much of what the small game does not.
    val fed = if (need > 0f) min(1f, got / need + 0.3f) else 1f
    eagles[p] = logistic(p, eagles[p], if (month in 4..6) 0.6f * fed else 0f, 0.1f + 0.6f * (1f - fed), eagleCrags[p],
        counted = false) // (an index, not kg: their dead are not counted)
}

/**
 * Carrion a hunter of small game at place [p] eats before it hunts, kg/km²: of [dead] kg/km²
 * lying there, as readily as prey ([half]: where it finds half its [need]), at most half in a
 * week.
 */
private fun HexSmallLife.scavengeFirst(p: Place, dead: Float, need: Float, half: Float) =
    if (dead > 0f && need > 0f) scavenge(p, KgKm2(min(0.5f * dead, need * dead / (dead + half))).perM2).perKm2.v else 0f

/** Yearly share of the young of small hoofed animals eagles take in place [p]. */
fun HexSmallLife.eagleRisk(p: Place) = EAGLE_RATE * eagles[p] * open(p)

/**
 * Vultures soar out from their colony each day and find the carcasses within reach, before most
 * other scavengers; a colony grows when they eat well and shrinks when not. They quarter the
 * whole range, so they take the same share of what lies in every place in reach, at most half
 * (as the other scavengers): taking the nearest places bare first left fixed holes in the carrion.
 */
@Part("small life: vultures")
internal fun HexSmallLife.stepVultures(weather: Weather) {
    val month = weather.date.month
    for (k in vulturePlace.indices) {
        val need = vultures[k] * 0.5f * days
        var lying = 0.0
        for (q in vultureReach[k]) lying += carrion[q].toDouble() * land.m2(Place(q))
        val share = if (lying > 0.0) min(0.5, need / lying).toFloat() else 0f
        var got = 0f
        if (share > 0f) for (q in vultureReach[k]) {
            if (carrion[q] > 0f) { val m2 = land.m2(Place(q)); got += scavenge(Place(q), KgM2(share * carrion[q])).over(m2).v }
        }
        ateBook?.add(-got.toDouble())
        val fed = if (need > 0f) min(1f, got / need) else 1f
        var b = vultures[k]
        if (month in 1..2) b += b * 0.5f * fed * years * 6f // one chick a pair, hatched in late winter
        b -= b * (0.08f * years + 0.4f * years * (1f - fed))
        vultures[k] = if (b < 2f) 0f else min(b, 2000f) // a colony below a pair is gone
    }
}
