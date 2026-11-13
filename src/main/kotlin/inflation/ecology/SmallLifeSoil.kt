package inflation.ecology

import inflation.drawUnit
import inflation.ecology.HexSmallLife.Companion.ANAEROBIC
import inflation.ecology.HexSmallLife.Companion.BONE_SHARE
import inflation.ecology.HexSmallLife.Companion.BOOM_HALF
import inflation.ecology.HexSmallLife.Companion.CANOPY_HERBIVORY
import inflation.ecology.HexSmallLife.Companion.CARRION_ROT
import inflation.ecology.HexSmallLife.Companion.DORMANT_DEATHS
import inflation.ecology.HexSmallLife.Companion.DUNG
import inflation.ecology.HexSmallLife.Companion.EGG_SHARE
import inflation.ecology.HexSmallLife.Companion.FRASS
import inflation.ecology.HexSmallLife.Companion.HARASS_HALF
import inflation.ecology.HexSmallLife.Companion.INSECT_GROWTH
import inflation.ecology.HexSmallLife.Companion.INSECT_INTAKE
import inflation.ecology.HexSmallLife.Companion.INSECT_SPAN_C
import inflation.ecology.HexSmallLife.Companion.LEAF_DAYS
import inflation.ecology.HexSmallLife.Companion.LOCUST_DEATHS
import inflation.ecology.HexSmallLife.Companion.LOCUST_RAIN_MM
import inflation.ecology.HexSmallLife.Companion.LOCUST_START
import inflation.ecology.HexSmallLife.Companion.LOCUST_SWARM
import inflation.ecology.HexSmallLife.Companion.SMALL_EFFICIENCY
import inflation.ecology.HexSmallLife.Companion.SOIL_DEATHS
import inflation.ecology.HexSmallLife.Companion.SOIL_REACH
import inflation.ecology.HexSmallLife.Companion.SOIL_YIELD
import inflation.ecology.HexSmallLife.Companion.WASH_PER_MM
import inflation.ecology.HexSmallLife.Companion.WIND_CALM
import inflation.Part
import inflation.Stream
import inflation.world.Place
import inflation.world.get
import inflation.world.set
import inflation.Kg
import inflation.KgKm2
import inflation.KgM2
import inflation.min
import inflation.world.HexPlants
import inflation.world.LifeForm
import inflation.world.Weather
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.hypot

// Small life in the soil and the air: the soil's decay, the insects that eat plants, bite and pollinate, and the locusts' swarms.

/**
 * Soil at place [p] this week: decomposition doubles every 10 °C up to about 30 °C, stops in dry or frozen soil.
 * Litter, dung and the small dead rot; carrion rots and the week's carcasses ([carcassKg]) are added; bones
 * weather away.
 */
@Part("small life: soil and decay")
internal fun HexSmallLife.stepSoil(p: Place, t: Float, soilWater: Float, carcassKg: FloatArray) {
    // (Full rate once the soil is half full of what it holds there.)
    val wet = (soilWater / (0.5f * land.hydro.soilCap[p])).coerceIn(0f, 1f)
    val warm = if (t <= 0f) 0f else min(2.8f, exp(0.069f * (t - 15f)))
    // What fell, what the animals dropped, and the small animals that died.
    litter[p] += land.plants.litterfall[p] + dung[p] + smallDead[p] / 1e6f
    val m2 = land.areaM2[p].toDouble()
    litterfallBook?.add(land.plants.litterfall[p] * m2)
    dung[p] = 0f; smallDead[p] = 0f
    // In sodden soil the rot runs short of air and slows (see [ANAEROBIC]): marsh, flooded and waterlogged valley floors build up peat and muck.
    val airless = 1f - (1f - ANAEROBIC) * land.hydro.saturated(p)
    val decomposed = litter[p] * min(1f, 1.2f * warm * wet * airless * years)
    litter[p] -= decomposed
    val humusGone = 0.05f * airless * years * organicMatter[p]
    record?.let { it.litterGone[p] += decomposed; it.organicGone[p] += humusGone }
    organicMatter[p] += 0.3f * decomposed - humusGone
    rotBook?.add(-(0.7f * decomposed + humusGone) * m2)
    // Water running off over the ground washes some of the litter away into the streams (see [WASH_PER_MM]).
    val washed = litter[p] * min(0.5f, WASH_PER_MM * land.hydro.runoffMm[p])
    litter[p] -= washed; washedKg[p] = washed * land.m2(p)
    land.plants.organicFertility[p] = 0.3f * organicMatter[p] / (organicMatter[p] + 4f)
    val soilDied = soilAnimals[p] * min(1f, SOIL_DEATHS * years)
    soilGrewLeak?.add(SOIL_YIELD * decomposed * m2); soilDiedLeak?.add(-soilDied / 1e6 * m2)
    soilAnimals[p] += SOIL_YIELD * decomposed * 1e6f - soilDied
    soilReach[p] = if (t > 0f) SOIL_REACH * wet else 0f
    // What lies rots (half in three or four days at 15 °C, faster in the heat), and the step's carcasses come in evenly over
    // its days and rot as they lie; the groups find their share of it as it lies, before it rots ([groupCarrion], at the
    // rate their search came on it in the last step, [groupCarrionRate]). In closed form over the step's
    // days (weekly-rules plan, F4): with inflow R a day and losses k = rot + the groups' finding, what lay over the step
    // sums to C0 (1 - e^-kt)/k + R (t - (1 - e^-kt)/k)/k, of which rot takes its rate's part and the groups theirs.
    val inflow = land.spread(p, Kg(carcassKg[p])).v
    carcassBook?.add(carcassKg[p].toDouble())
    if (carrion[p] > 0f || inflow > 0f) {
        val rot = CARRION_ROT * max(0.1f, warm)
        val find = groupCarrionRate[p] + residentCarrionRate[p] + vultureCarrionRate[p]
        val k = rot + find; val d = days.toFloat(); val r = inflow / d
        val lay = (1f - exp(-k * d)) / k
        val lain = carrion[p] * lay + r * (d - lay) / k
        val gone = min(rot * lain, carrion[p] + inflow); val found = min(find * lain, carrion[p] + inflow - gone)
        // (The groups' and the residents' finds by their rates.)
        val byResidents = if (find > 0f) found * residentCarrionRate[p] / find else 0f
        val byVultures = if (find > 0f) found * vultureCarrionRate[p] / find else 0f
        val byGroups = found - byResidents - byVultures
        carrion[p] += inflow - gone - found; groupCarrion[p] += byGroups; residentCarrion[p] += byResidents; vultureCarrion[p] += byVultures
        flow(CarrionFlow.SOIL_IN, p, inflow); flow(CarrionFlow.ROT, p, gone); flow(CarrionFlow.GROUPS_FOUND, p, byGroups); flow(CarrionFlow.RESIDENTS_FOUND, p, byResidents)
        litter[p] += 0.3f * gone - BONE_SHARE * gone; bones[p] += BONE_SHARE * gone
        rotBook?.add(-0.7f * gone * m2)
        record?.let { it.carrionGone[p] += gone }
        if (carrion[p] < 1e-9f) carrion[p] = 0f
    }
    // Bones weather away over a couple of years into the soil.
    if (bones[p] > 0f) { val b = bones[p] * min(1f, years / 2f); bones[p] -= b; litter[p] += b; record?.let { it.bonesGone[p] += b } }
}

/**
 * Plant-eating insects (see [insectSeason]): on the young leaves of herbs and shrubs, as green as the place is.
 * Returns how far insects are out (0..1) at the warmth [t].
 */
@Part("small life: insects")
internal fun HexSmallLife.stepInsects(p: Place, t: Float): Float {
    val active = ((t - insectFrom[p]) / INSECT_SPAN_C).coerceIn(0f, 1f)
    val greenFood = (land.plants.herb[p] + 0.3f * land.plants.shrub[p]) * land.plants.green[p]
    val low = 2000f * greenFood / (greenFood + 0.15f)
    // In the canopy, caterpillars and their kin take a steady share of the leaves the trees put out
    // ([CANOPY_HERBIVORY] of them); as many live there as eat that much while the leaves are out.
    val canopy = land.plants.green[p] * CANOPY_HERBIVORY * HexPlants.TREE_RENEWAL * land.plants.tree[p] * 1e6f / (INSECT_INTAKE * LEAF_DAYS)
    smallDead[p] += insectSeason(p, insects, dormantInsects, active, low + canopy)
    if (active > 0f) {
        // They eat about a quarter of their weight a day, and half of it comes back as frass.
        val up = if (canopy > 0f) canopy / (low + canopy) else 0f
        val eaten = land.plants.consume(p, LifeForm.HERB, KgKm2((1f - up) * insects[p] * 0.2f * days).perM2) +
            land.plants.consume(p, LifeForm.SHRUB, KgKm2((1f - up) * insects[p] * 0.05f * days).perM2) +
            land.plants.consume(p, LifeForm.TREE, KgKm2(up * insects[p] * INSECT_INTAKE * days).perM2)
        litter[p] += FRASS * eaten.v
        ateM2(p, eaten.v); dropped(p, FRASS * eaten.v)
    }
    insects[p] = capped(p, insects[p], 3000f)
    return active
}

/**
 * Biting insects (see [insectSeason]): their larvae live in standing water; when it dries or the cold comes, the
 * eggs wait in the mud.
 */
@Part("small life: biting insects")
internal fun HexSmallLife.stepBiting(p: Place, active: Float, soilWater: Float, weather: Weather) {
    val standing = land.hydro.flood[p] > 0f || wetland[p] > 0.1f || soilWater / land.hydro.soilCap[p] > 0.9f
    smallDead[p] += insectSeason(p, biting, dormantBiting, active, if (standing) 500f else 0f, least = 0f)
    harass[p] = harassmentNow(p, weather)
}

/**
 * Pollinators (see [insectSeason]): on the flowers of spring and early summer; grazing leaves fewer flowers, and
 * as the flowers fade they lay their eggs and wait for the next spring.
 */
@Part("small life: pollinators")
internal fun HexSmallLife.stepPollinators(p: Place, month: Int, active: Float) {
    val flowering = month in 3..7
    val flowers = (land.plants.herb[p] + 0.5f * land.plants.shrub[p]) * (if (flowering) 1f else 0.2f) *
        (1f - 0.5f * (1f - exp(-land.plants.grazing[p] / 5f)))
    smallDead[p] += insectSeason(p, pollinators, dormantPollinators, active, 50f * flowers / (flowers + 0.3f), least = 0f)
    pollinators[p] = capped(p, pollinators[p], 100f)
    if (flowering) pollination[p] += 0.2f * (min(1f, pollinators[p] / 20f) - pollination[p])
}

/**
 * Desert locusts: in a spring after good rain, where the desert edge has greened, a swarm may rise;
 * it strips the green plants where it is, moves on to the greenest country about and dies out by late
 * summer. Birds, foxes, jackals and people eat them (see [catchSmall]).
 */
@Part("small life: locusts")
internal fun HexSmallLife.stepLocusts(weather: Weather) {
    val month = weather.date.month
    val season = month in 2..7
    val old = locusts.copyOf()
    for (p in land.places) {
        var l = old[p]
        // (A swarm is the hoppers that grew up on the green plants there: its weight comes off them.)
        if (season && l <= 0f && annualRain[p] < LOCUST_RAIN_MM && land.plants.green[p] > 0.6f &&
            locustHash(p, weather.date.absoluteDay) < LOCUST_START * years) {
            val taken = land.plants.consume(p, LifeForm.HERB, KgKm2(LOCUST_SWARM / SMALL_EFFICIENCY).perM2)
            l = (taken * SMALL_EFFICIENCY).perKm2.v
            ateM2(p, taken.v); grew(p, l)
        }
        if (l <= 0f) continue
        // A swarm eats about its weight in green plants a day.
        val m2 = 1e6f
        val ate = land.plants.consume(p, LifeForm.HERB, KgM2(min(0.5f * land.plants.herb[p], 0.8f * l * days / m2))) +
            land.plants.consume(p, LifeForm.SHRUB, KgM2(min(0.3f * land.plants.shrub[p], 0.2f * l * days / m2)))
        dung[p] += DUNG * ate.v
        ateM2(p, ate.v); dropped(p, DUNG * ate.v)
        val dying = l * overTicks(if (season) LOCUST_DEATHS else 0.6f)
        smallDead[p] += dying * 1f
        l -= dying
        // Most of a swarm moves on to the neighbours each week, settling the more where the green plants stand thicker.
        val nb = land.neighbours[p.i]
        if (nb.isNotEmpty() && l > 1f) {
            val go = 0.6f * l
            var total = 0f
            for (q in nb) total += greenHerbKm2(Place(q))
            for (q in nb) {
                val share = if (total > 0f) greenHerbKm2(Place(q)) / total else 1f / nb.size
                locusts[q] += go * share * land.areaKm2[p] / land.areaKm2[q]
            }
            l -= go
        }
        locusts[p] += l - old[p]
    }
    for (p in land.places) if (locusts[p] < 0.1f) { smallDead[p] += locusts[p]; locusts[p] = 0f }
}

/** Green herb in place [p], over all its area: what draws a swarm in. */
private fun HexSmallLife.greenHerbKm2(q: Place) = land.plants.green[q] * land.plants.herb[q] * land.areaKm2[q]

private fun HexSmallLife.locustHash(p: Place, day: Long): Float = drawUnit(land.seed, Stream.LOCUSTS, p.i.toLong(), day)

/**
 * A week of an insect's year at place [p], for any kind of insect kept as an [active] field and a
 * [dormant] one (kg/km², the dormant counted as the active insects they will become): they are out
 * while it is warm enough for them ([warm], 0..1, how far toward their best warmth) and grow fast
 * (larvae and nymphs gain some 10-20% of their weight a day) up to what their food carries
 * ([ceiling], kg/km²); when the food fades below that, or it is too cold, the adults lay their eggs
 * and die, and the next generation waits as eggs, larvae and pupae, with modest losses, and comes
 * out again over a few weeks as it warms. Returns what died, kg/km².
 */
private fun HexSmallLife.insectSeason(p: Place, active: FloatArray, dormant: FloatArray, warm: Float, ceiling: Float, least: Float = 10f): Float {
    var ins = active[p]; var dor = dormant[p]
    var dead = dor * min(1f, DORMANT_DEATHS * years)
    dor -= dead
    if (warm > 0f) {
        val out = dor * 0.5f * warm; dor -= out; ins += out
        val k = max(least, ceiling)
        if (ins > 0f && ins < k) { val was = ins; ins = k / (1f + (k / ins - 1f) * exp(-INSECT_GROWTH * warm * days)); grew(p, ins - was) }
        val died = ins * 2f * years; dead += died; ins -= died
        if (ins > k) { val spent = 0.5f * (ins - k); ins -= spent; dor += EGG_SHARE * spent; dead += (1f - EGG_SHARE) * spent }
    } else {
        val spent = 0.5f * ins; ins -= spent; dor += EGG_SHARE * spent; dead += (1f - EGG_SHARE) * spent
    }
    active[p] = ins; dormant[p] = dor
    return dead
}

/**
 * A beast rooting in the soil of place [p] takes up to [want] of the grubs lying there (the larvae
 * and pupae of insects waiting out the cold or the drought), at most half of them; returns what was taken.
 */
fun HexSmallLife.digGrubs(p: Place, want: KgKm2): KgKm2 {
    val there = dormantInsects[p] + dormantPollinators[p] + soilAnimals[p]
    if (want.v <= 0f || there <= 0f) return KgKm2.ZERO
    // (At most half of them a tick: over a step of several ticks, compounded.)
    val took = min(want.v, overTicks(0.5f) * there); val f = 1f - took / there
    dormantInsects[p] *= f; dormantPollinators[p] *= f; soilAnimals[p] *= f
    grubsDug[p] += took
    return KgKm2(took)
}

/** The insects and soil animals at [p], as ground feeders reach them: what [takeSwitching] needs as ease. */
internal fun HexSmallLife.insectEaseAt(p: Place): FloatArray = insectEase.get().also { it[4] = soilReach[p] }

/**
 * How hard the biting insects at [p] harass the animals and people there this week, 0..1: by how
 * many are out, and less in a wind (mosquitoes and midges barely fly in a breeze of a few m/s; a
 * herd on a windy ridge is left in peace).
 */
fun HexSmallLife.harassment(p: Place): Float = harass[p]

private fun HexSmallLife.harassmentNow(p: Place, weather: Weather): Float {
    val b = biting[p]
    if (b <= 0f) return 0f
    val wind = hypot(weather.windEast(p.i), weather.windSouth(p.i))
    return b / (b + HARASS_HALF) * exp(-wind / WIND_CALM)
}

/** How far the insects that are out at [p] are booming, 0..1: what draws the insect-eating migrants. */
internal fun HexSmallLife.boom(p: Place): Float { val x = insects[p] + pollinators[p] + locusts[p] + biting[p]; return x / (x + BOOM_HALF) }

/** The fruit crop's share of what the trees could bear, from this year's pollination. */
fun HexSmallLife.fruitSet(p: Place) = 0.3f + 0.7f * pollination[p]
