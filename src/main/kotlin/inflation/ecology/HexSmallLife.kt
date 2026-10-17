package inflation.ecology

import inflation.wrapMonth
import inflation.HASH_A
import inflation.HASH_B
import inflation.mix64Once
import inflation.unitOf
import inflation.world.Date
import inflation.world.HexLand
import inflation.world.HexWorld
import inflation.world.LifeForm
import inflation.world.LiveWater
import inflation.world.Weather
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import java.util.stream.IntStream
import kotlin.math.hypot

/**
 * A wild bird as a density field: when it breeds, how fast it grows and dies back, the months it is
 * here at all (migrants), and [domestication] by the same six criteria as the large animals (diet
 * breadth, growth, captive breeding, temperament, calm, hierarchy).
 */
class BirdKind(
    val name: String,
    val breedMonths: IntRange,
    /** Young raised a year per head; yearly share of the birds dying. */
    val growth: Float,
    val deaths: Float,
    /** Months present; null for residents. Outside them the birds are away. */
    val present: Set<Int>?,
    val domestication: FloatArray,
    /**
     * Share of the food that is insects (for most, what the chicks are raised on). Migrants that
     * live mostly on insects ([followsInsects]) come and go with the insect booms within their months.
     */
    val insectShare: Float = 0f,
) {
    val followsInsects get() = present != null && insectShare >= 0.3f
}

/**
 * The life in the soil and the small animals of each place (height band of a hex), as in the 1 km
 * model's soil life and small fauna:
 *
 * - Soil: dead plant matter falls as litter and rots, faster in warm, moist soil, building organic
 *   matter that enriches the soil; soil animals live on the rotting. Carcasses rot too, half gone in
 *   about ten days in summer heat and far slower in the cold, and scavengers take what they find.
 * - Plant-eating insects (grasshoppers, caterpillars, beetles): boom in warm weeks on green plants,
 *   wait out cold and hot drought as eggs.
 * - Biting insects (mosquitoes, midges): breed in standing water when it is warm.
 * - Rodents and other small mammals: breed fast on seeds and herbs in good years, crash in drought,
 *   cold and deep snow.
 * - Reptiles and amphibians: active and breeding on insects in warm weeks, slow to turn over, torpid in cold
 *   winters and hot, dry summers.
 * - Pollinators: build up on flowers in spring, fewer where grazing is hard; how well the flowers
 *   were visited sets the summer and autumn fruit crop.
 * - Small game, as in the 1 km model's small game: hares on open grass, rock hyrax in broken
 *   country, six wild birds each on its own ground (quail and greylag geese come and go with the
 *   seasons), tortoises in warm scrub, and foxes and wildcats living on the rest.
 * - Griffon vulture colonies on the most broken ground, one to a block of [VULTURE_SPACING_KM],
 *   soaring up to [VULTURE_RANGE_KM] for carcasses, nearest first.
 * - Golden eagles nesting on crags, as a share of each place's ground they hunt over (0..1): they
 *   fly over any ground, take hyrax, hares and rock birds, and the young of the smaller hoofed
 *   animals in the open; they thrive where the crags and the game are, and dwindle where food fails.
 * - Fire kills what cannot flee. Every month each kind spreads a little to the places around.
 */
class HexSmallLife(private val hw: HexWorld, private val land: HexLand, private val days: Int) {
    val n = land.n
    /** Soil: litter and organic matter, kg/m²; carrion, kg/m²; soil animals, g/m². */
    val litter = FloatArray(n) { p -> 0.5f * (land.herb[p] + 0.2f * land.shrub[p] + 0.04f * land.tree[p]) + 0.3f }
    val organicMatter = FloatArray(n) { p -> 4f * min(1f, land.biomass(p) / 5f) }
    val carrion = FloatArray(n)
    /** Where dead matter went, for the viewer (see [PlaceRecord]); set with [HexFauna.record]. */
    @Transient var record: PlaceRecord? = null
    /** Bones left when the meat is gone, kg/m²: slow to weather, food for bone-crackers. */
    val bones = FloatArray(n)
    /**
     * Soil animals (earthworms, beetles, millipedes, spiders, larvae in the litter), kg/km²: they live on
     * the rotting litter, a few in a hundred of what rots becoming their flesh, and turn over about once a
     * year, so they hold some 20 g/m² for each kg/m² rotting a year. Ground feeders reach the share of them
     * at the surface and in the litter ([SOIL_REACH]) while the soil is moist and not frozen; rooters dig
     * them out.
     */
    val soilAnimals = FloatArray(n) { p -> SOIL_YIELD / SOIL_DEATHS * 1e6f * (land.herb[p] + 0.2f * land.shrub[p] + 0.04f * land.tree[p]) }
    /** Small animals, kg/km². */
    val insects = FloatArray(n) { p -> 300f * min(1f, land.herb[p] / 0.4f) }
    /** Plant-eating insects waiting out the cold or the drought as eggs, larvae and pupae, kg/km² (as the active insects they will become). */
    val dormantInsects = FloatArray(n)
    val biting = FloatArray(n)
    /** Biting insects waiting as eggs and larvae in the mud and water, kg/km² (as the adults they will become). */
    val dormantBiting = FloatArray(n)
    val rodents = FloatArray(n) { p -> 150f * min(1f, land.herb[p] / 0.4f) }
    val herps = FloatArray(n) { p -> 20f * min(1f, land.herb[p] / 0.4f) }
    val pollinators = FloatArray(n) { p -> 10f * min(1f, land.herb[p] / 0.4f) }
    /** Pollinators waiting out the cold and the flowerless months as eggs, larvae and pupae, kg/km² (as the active ones they will become). */
    val dormantPollinators = FloatArray(n)
    /** How well this year's flowers were pollinated, 0..1: sets the fruit crop. */
    val pollination = FloatArray(n) { 0.6f }
    /** Small game, kg/km². */
    val hares = FloatArray(n)
    val hyrax = FloatArray(n)
    val tortoises = FloatArray(n)
    val foxes = FloatArray(n)
    /** Owls, kestrels, buzzards and kites hunting rodents, kg/km². */
    val owls = FloatArray(n)
    /** Desert locusts, kg/km²: swarms that rise in the desert after a wet spring, strip the green and move on. */
    val locusts = FloatArray(n)
    /** Each kind of [BIRDS], and all together, kg/km². */
    val bird = Array(BIRDS.size) { FloatArray(n) }
    val birds = FloatArray(n)
    /** Eagles hunting over each place, 0..1, and as many as the crags allow. */
    @Transient private val eagleCrags = land.overGround { min(1f, 2f * it) }
    val eagles = eagleCrags.copyOf()
    /**
     * Rock for the small life of broken ground, the mean over each place's ground (see
     * [HexLand.overGround]): hyrax and rock doves, chukar, and the cracks and screes reptiles and
     * small mammals hide in.
     */
    @Transient private val rockKopje = land.overGround { it.pow(1.5f) }
    @Transient private val rockChukar = land.overGround { min(1f, it / 0.5f) }
    @Transient private val rockHides = land.overGround { 1f / (1f + min(1f, it)) }
    /** Vulture colonies: nest place, the birds, and the places within reach, nearest first. */
    val vulturePlace = ArrayList<Int>()
    val vultures = ArrayList<Float>()
    private val vultureReach = ArrayList<IntArray>()
    /** Scratch: litter the runoff washed off each place this week, kg; organic matter floods laid on each, kg/m² (see [HexLand.carrySilt]). */
    @Transient private val washedKg = FloatArray(n)
    @Transient val laidKgM2 = FloatArray(n)
    /** Dung dropped this week by the herds and packs, kg/m²: it goes to the litter. */
    val dung = FloatArray(n)
    /** Small animals that died this week in each place, kg/km²: they go to the litter. */
    private val smallDead = FloatArray(n)
    /** Migrant birds away beyond the map, kg, per kind; and how much ground each kind finds when it comes back. */
    private val away = DoubleArray(BIRDS.size)
    private val returning = DoubleArray(BIRDS.size)
    /** Migrants that live on insects, back on the map but not yet settled, kg per kind: they settle where the insects boom. */
    private val waiting = DoubleArray(BIRDS.size)
    /** Migrant birds leaving this week from each place, kg (summed in place order after the parallel loop, so the sum is the same on any number of cores). */
    private val leaving = Array(BIRDS.size) { FloatArray(n) }
    private var lastMonth = -1
    private val years = days / Date.DAYS_PER_YEAR.toFloat()
    /** Share of each place that is wetland, where biting insects breed. */
    val wetland = FloatArray(n) { p -> hw.placeCells[p].count { val b = hw.world.biome[it]; b.name in WETLANDS }.toFloat() / hw.placeCells[p].size }
    /** Marsh, bog and floodplain, where ducks and geese feed. */
    private val waterfowl = FloatArray(n) { p -> min(1f, wetland[p] + land.floodShare[p]) }
    /** The year's mean temperature and rain at each place. */
    private val meanTemp = FloatArray(n) { p -> (0 until 12).map { hw.placeTemp(p, it) }.average().toFloat() }
    private val annualRain = FloatArray(n) { p -> (0 until 12).sumOf { hw.precip[it][hw.placeHex[p]].toDouble() }.toFloat() }
    /**
     * The warmth, °C, at which the plant-eating insects of each place start to grow: about 10 °C,
     * lower where summers are cool and the insects are adapted to them.
     */
    private val insectFrom = FloatArray(n) { p -> ((0 until 12).maxOf { hw.placeTemp(p, it) } - 12f).coerceIn(INSECT_FROM_MIN_C, INSECT_FROM_C) }

    init {
        for (p in 0 until n) {
            if (land.biomass(p) <= 0f) continue
            hares[p] = 0.5f * hareCapacity(p); hyrax[p] = 0.5f * hyraxCapacity(p)
            for (k in bird.indices) bird[k][p] = 0.5f * birdCapacity(k, p)
            birds[p] = bird.sumOf { it[p].toDouble() }.toFloat()
            tortoises[p] = 0.5f * tortoiseCapacity(p); foxes[p] = 0.3f
            dormantBiting[p] = 50f * waterfowl[p]
        }
        // Vultures: the most broken ground with plants in each block, if broken enough for cliffs.
        val hex = hw.hex
        val blocks = HashMap<Long, Int>()
        for (p in 0 until n) {
            if (land.biomass(p) <= 0f || land.ruggedness[p] <= 0.5f) continue
            val (x, y) = hex.centreKm(hw.placeHex[p])
            val key = (x / VULTURE_SPACING_KM).toLong() * 100000 + (y / VULTURE_SPACING_KM).toLong()
            val best = blocks[key]
            if (best == null || land.ruggedness[p] > land.ruggedness[best]) blocks[key] = p
        }
        for (c in blocks.values.sorted()) {
            val (cx, cy) = hex.centreKm(hw.placeHex[c])
            fun dist(q: Int): Double { val (x, y) = hex.centreKm(hw.placeHex[q]); return hypot(x - cx, y - cy) }
            val near = (0 until n).filter { dist(it) <= VULTURE_RANGE_KM }.sortedBy { dist(it) }
            vulturePlace += c; vultures += 40f; vultureReach += near.toIntArray()
        }
    }

    private fun open(p: Int) = 1f - min(1f, land.canopy(p) + 0.5f * min(1f, land.shrub[p] / 1.5f))
    private fun hareCapacity(p: Int) = 40f * land.herb[p] / (land.herb[p] + 0.3f) * open(p)
    private fun hyraxCapacity(p: Int) = 80f * rockKopje[p] * min(1f, land.shrub[p] / 0.5f + 0.2f)
    private fun birdCapacity(k: Int, p: Int): Float {
        val grass = land.herb[p] / (land.herb[p] + 0.2f)
        val shrubby = min(1f, land.shrub[p])
        val dry = (1f - annualRain[p] / 500f).coerceIn(0f, 1f)
        return when (BIRDS[k]) {
            CHUKAR -> 6f * rockChukar[p] * (0.3f + 0.7f * open(p)) * (0.3f + 0.7f * shrubby) * grass
            QUAIL -> 5f * open(p) * grass
            SANDGROUSE -> 3f * open(p) * dry
            ROCK_DOVE -> 4f * rockKopje[p] * (0.3f + 0.7f * grass)
            MALLARD -> 6f * waterfowl[p] * (0.5f + 0.5f * grass)
            GREYLAG_GOOSE -> 10f * waterfowl[p] * grass
            CRANE -> 8f * waterfowl[p] * (0.5f + 0.5f * grass) + 1f * open(p) * grass
            WHITE_STORK -> 3f * waterfowl[p] + 2f * open(p) * grass
            // Rich woodland and scrub hold some 40-120 breeding pairs per 10 ha (Bialowieza, Tomialojc &
            // Wesolowski, J. Ornithol. 2004, doi:10.1007/s10336-003-0017-2); at some 20-25 g a bird, up to
            // about 40 kg/km2. Open grass holds a tenth of that (larks, pipits).
            SONGBIRDS -> 40f * (0.1f + 0.9f * (1f - open(p)))
            else -> 0f
        }
    }
    fun tortoiseCapacity(p: Int): Float {
        val warm = ((meanTemp[p] - 12f) / 8f).coerceIn(0f, 1f)
        return TORTOISE_MOST * warm * min(1f, (land.shrub[p] + land.herb[p]) / 0.8f)
    }

    fun step(weather: Weather, carcassKg: FloatArray) {
        val month = weather.date.month
        // Migrant birds: those leaving this week go beyond the map; those coming back spread over the
        // ground that draws them, as many as survived the winter away.
        for (k in BIRDS.indices) {
            val b = BIRDS[k]
            val pr = b.present ?: continue
            returning[k] = 0.0
            if (lastMonth >= 0 && month in pr && lastMonth !in pr) {
                var cap = 0.0
                for (p in 0 until n) cap += (birdCapacity(k, p) * land.areaKm2[p]).toDouble()
                // Kinds that breed far away (winter visitors such as geese, passage migrants) come from a
                // flyway's whole population, held by its breeding grounds, not by the few that wintered here:
                // as many come as the ground draws. Those that breed here come back as many as lived.
                val awayMonths = generateSequence(wrapMonth(month - 1)) { wrapMonth(it - 1) }.takeWhile { it !in pr }.toSet()
                val bredAway = b.breedMonths.any { it in awayMonths }
                val back = if (bredAway) cap else away[k] * MIGRANT_SURVIVAL
                // (Those that live on insects wait to settle where the insects boom, below.)
                if (b.followsInsects) waiting[k] += back else returning[k] = if (cap > 0.0) back / cap else 0.0
                away[k] = 0.0
            }
            if (b.followsInsects) {
                if (month !in pr) { away[k] += waiting[k]; waiting[k] = 0.0 }
                else if (waiting[k] > 0.0) {
                    // Half of those waiting settle each week, over the ground that draws them, as the
                    // insects there are booming; the rest pass on and look again.
                    var draw = 0.0
                    for (p in 0 until n) draw += (birdCapacity(k, p) * boom(p) * land.areaKm2[p]).toDouble()
                    if (draw > 0.0) { returning[k] = SETTLE * waiting[k] / draw; waiting[k] *= 1.0 - SETTLE }
                    waiting[k] *= 1.0 - b.deaths * years
                }
            }
        }
        IntStream.range(0, n).parallel().forEach { p ->
            var pt = Profile.mark()
            val t = weather.temperature[p]
            val soilWater = land.soil[p]
            stepSoil(p, t, soilWater, carcassKg)
            val food = land.herb[p] + 0.3f * land.shrub[p]
            val snow = weather.snowpack[p]
            pt = Profile.lap("small life: soil and decay", pt)
            val active = stepInsects(p, t)
            pt = Profile.lap("small life: insects", pt)
            stepBiting(p, active, soilWater, weather)
            pt = Profile.lap("small life: biting insects", pt)
            stepRodents(p, t, food, snow)
            pt = Profile.lap("small life: rodents", pt)
            stepHerps(p, t, soilWater)
            pt = Profile.lap("small life: reptiles and amphibians", pt)
            val b = land.burning[p]
            burnSmallLife(p, b)
            pt = Profile.lap("small life: fire", pt)
            stepPollinators(p, month, active)
            pt = Profile.lap("small life: pollinators", pt)
            stepGame(p, t, month, b); Profile.lap("small life: hares, hyrax, birds, tortoises, foxes, owls, eagles", pt)
        }
        // What the runoff washed off goes down the rivers; floods lay part of it on the floodplains with their silt.
        land.carrySilt(washedKg, laidKgM2)
        for (p in 0 until n) if (laidKgM2[p] > 0f) organicMatter[p] += laidKgM2[p]
        Profile.part("small life: locusts") { stepLocusts(weather) }
        for (k in BIRDS.indices) {
            val l = leaving[k]; var sum = 0.0; for (p in l.indices) if (l[p] != 0f) { sum += l[p]; l[p] = 0f }
            // (Insect-eaters leaving a place where the insects failed, within their months, look for another.)
            if (BIRDS[k].followsInsects && month in BIRDS[k].present!!) waiting[k] += sum else away[k] += sum
        }
        lastMonth = month
        Profile.part("small life: vultures") { stepVultures(weather) }
        // A little every week, not all at once each month: a monthly lump showed as a pulse at the
        // start of each month where the spreaders arrived and then died back over the weeks.
        Profile.part("small life: spreading to neighbours") {
            spread(spreaders)
            spread(eagles) { eagleCrags[it] > 0f }
        }
    }

    /**
     * Soil at place [p] this week: decomposition doubles every 10 °C up to about 30 °C, stops in dry or frozen soil.
     * Litter, dung and the small dead rot; carrion rots and the week's carcasses ([carcassKg]) are added; bones
     * weather away.
     */
    private fun stepSoil(p: Int, t: Float, soilWater: Float, carcassKg: FloatArray) {
        val wet = (soilWater / 75f).coerceIn(0f, 1f)
        val warm = if (t <= 0f) 0f else min(2.8f, exp(0.069f * (t - 15f)))
        // What fell, what the animals dropped, and the small animals that died.
        litter[p] += land.litterfall[p] + dung[p] + smallDead[p] / 1e6f
        dung[p] = 0f; smallDead[p] = 0f
        // In sodden soil the rot runs short of air and slows (see [ANAEROBIC]): marsh, flooded and waterlogged valley floors build up peat and muck.
        val airless = 1f - (1f - ANAEROBIC) * land.saturated(p)
        val decomposed = litter[p] * min(1f, 1.2f * warm * wet * airless * years)
        litter[p] -= decomposed
        val humusGone = 0.05f * airless * years * organicMatter[p]
        record?.let { it.litterGone[p] += decomposed; it.organicGone[p] += humusGone }
        organicMatter[p] += 0.3f * decomposed - humusGone
        // Water running off over the ground washes some of the litter away into the streams (see [WASH_PER_MM]).
        val washed = litter[p] * min(0.5f, WASH_PER_MM * land.runoffMm[p])
        litter[p] -= washed; washedKg[p] = washed * land.m2(p)
        land.organicFertility[p] = 0.3f * organicMatter[p] / (organicMatter[p] + 4f)
        soilAnimals[p] += SOIL_YIELD * decomposed * 1e6f - soilAnimals[p] * min(1f, SOIL_DEATHS * years)
        soilReach[p] = if (t > 0f) SOIL_REACH * wet else 0f
        // What lay from earlier weeks rots (half in three or four days at 15 °C, faster in the heat);
        // then the week's carcasses are added, fresh for the vultures and scavengers this week.
        if (carrion[p] > 0f) {
            val gone = carrion[p] * (1f - exp(-CARRION_ROT * max(0.1f, warm) * days))
            carrion[p] -= gone; litter[p] += 0.3f * gone - BONE_SHARE * gone; bones[p] += BONE_SHARE * gone
            record?.let { it.carrionGone[p] += gone }
            if (carrion[p] < 1e-9f) carrion[p] = 0f
        }
        carrion[p] += carcassKg[p] / land.m2(p)
        // Bones weather away over a couple of years into the soil.
        if (bones[p] > 0f) { val b = bones[p] * min(1f, years / 2f); bones[p] -= b; litter[p] += b; record?.let { it.bonesGone[p] += b } }
    }

    /**
     * Plant-eating insects (see [insectSeason]): on the young leaves of herbs and shrubs, as green as the place is.
     * Returns how far insects are out (0..1) at the warmth [t].
     */
    private fun stepInsects(p: Int, t: Float): Float {
        val active = ((t - insectFrom[p]) / INSECT_SPAN_C).coerceIn(0f, 1f)
        val greenFood = (land.herb[p] + 0.3f * land.shrub[p]) * land.green[p]
        val low = 2000f * greenFood / (greenFood + 0.15f)
        // In the canopy, caterpillars and their kin take a steady share of the leaves the trees put out
        // ([CANOPY_HERBIVORY] of them); as many live there as eat that much while the leaves are out.
        val canopy = land.green[p] * CANOPY_HERBIVORY * HexLand.TREE_RENEWAL * land.tree[p] * 1e6f / (INSECT_INTAKE * LEAF_DAYS)
        smallDead[p] += insectSeason(p, insects, dormantInsects, active, low + canopy)
        if (active > 0f) {
            // They eat about a quarter of their weight a day, and half of it comes back as frass.
            val up = if (canopy > 0f) canopy / (low + canopy) else 0f
            val eaten = land.consume(p, LifeForm.HERB, (1f - up) * insects[p] * 0.2f * days / 1e6f) +
                land.consume(p, LifeForm.SHRUB, (1f - up) * insects[p] * 0.05f * days / 1e6f) +
                land.consume(p, LifeForm.TREE, up * insects[p] * INSECT_INTAKE * days / 1e6f)
            litter[p] += FRASS * eaten
        }
        insects[p] = capped(p, insects[p], 3000f)
        return active
    }

    /**
     * Biting insects (see [insectSeason]): their larvae live in standing water; when it dries or the cold comes, the
     * eggs wait in the mud.
     */
    private fun stepBiting(p: Int, active: Float, soilWater: Float, weather: Weather) {
        val standing = land.flood[p] > 0f || wetland[p] > 0.1f || soilWater / LiveWater.SOIL_CAPACITY > 0.9f
        smallDead[p] += insectSeason(p, biting, dormantBiting, active, if (standing) 500f else 0f, least = 0f)
        harass[p] = harassmentNow(p, weather)
    }

    /**
     * Rodents (a female raises some twenty young a year): they eat ~10% of their mass a day, and a mouse or vole
     * lasts only a few days without food, so the share that found none in the week dies. They breed on fresh growth
     * (young shoots and seeds carry the protein a litter needs), logistic on food; losses to cold and deep snow.
     */
    private fun stepRodents(p: Int, t: Float, food: Float, snow: Float) {
        var r = rodents[p]
        val need = r * 0.1f * days / 1e6f
        val eaten = land.consume(p, LifeForm.HERB, need)
        litter[p] += FRASS * eaten
        val fed = if (need > 0f) eaten / need else 1f
        val fresh = food * land.green[p]
        val birth = if (t > 5f) 10f * years * fed * fresh / (fresh + 0.05f) else 0f // (half the most at 0.05 kg/m² of green forage)
        val death = RODENT_DEATH * years + (if (snow > 30f || t < -5f) 3f * years else 0f)
        val starved = r * (1f - fed)
        val ceil = 600f * food / (food + 0.3f)
        val bornR = r * birth * (1 - r / ceil.coerceAtLeast(1f))
        r += bornR - r * death - starved
        smallDead[p] += rodents[p] * death + starved
        rodents[p] = capped(p, r, 3000f)
        nest(p, NEST_RODENTS, rodents[p], max(0f, bornR))
    }

    /**
     * Reptiles and amphibians: cold-blooded, they need little food and turn over slowly. Out, they live on the
     * insects that are out (plant-eaters, pollinators, locusts, mosquitoes and midges), from each kind as it is met,
     * and breed as far as they are fed: what they find per head, not the plants, holds their numbers. In the cold
     * and in hot drought they lie up, losing few. (Toads and lizards take the dry country where frogs and newts
     * cannot.)
     */
    private fun stepHerps(p: Int, t: Float, soilWater: Float) {
        var h = herps[p]; var herpBorn = 0f
        if (herpsActive(t, soilWater)) {
            val need = h * HERP_INTAKE * days
            val ate = if (need > 0f) takeSwitching(p, need, 0.5f, insectFood, insectEaseAt(p)) else 0f
            val fed = if (need > 0f) ate / need else 1f
            val old = h * HERP_DEATHS * years; val hungry = h * HERP_HUNGER * (1f - fed) * years
            val died = old + hungry
            smallDead[p] += died
            // (Young crowd the place's breeding sites: tadpoles the pools, eggs the warm sheltered ground.)
            val born = h * HERP_YOUNG * fed * max(0f, 1f - h / herpSites(p).coerceAtLeast(1e-3f)) * years
            h += born - died; herpBorn = born
            herpLedger[HerpLedger.BORN][p] += born; herpLedger[HerpLedger.DIED_OUT][p] += old; herpLedger[HerpLedger.HUNGER][p] += hungry
            herpLedger[HerpLedger.ATE][p] += ate; herpLedger[HerpLedger.NEEDED][p] += need
        } else { val died = h * HERP_DORMANT_DEATHS * years; smallDead[p] += died; h -= died; herpLedger[HerpLedger.LYING_UP][p] += died }
        herps[p] = capped(p, h, HERP_MOST)
        nest(p, NEST_HERPS, herps[p], herpBorn)
    }

    /**
     * Fire at place [p] kills what cannot flee, on the share [b] that burned.
     */
    private fun burnSmallLife(p: Int, b: Float) {
        if (b > 0f) {
            smallDead[p] += insects[p] * 0.8f * b + herps[p] * 0.7f * b + rodents[p] * 0.5f * b + pollinators[p] * 0.8f * b
            insects[p] *= 1f - 0.8f * b; herps[p] *= 1f - 0.7f * b; rodents[p] *= 1f - 0.5f * b; pollinators[p] *= 1f - 0.8f * b
        }
    }

    /**
     * Pollinators (see [insectSeason]): on the flowers of spring and early summer; grazing leaves fewer flowers, and
     * as the flowers fade they lay their eggs and wait for the next spring.
     */
    private fun stepPollinators(p: Int, month: Int, active: Float) {
        val flowering = month in 3..7
        val flowers = (land.herb[p] + 0.5f * land.shrub[p]) * (if (flowering) 1f else 0.2f) * (1f - 0.5f * (1f - exp(-land.grazing[p] / 5f)))
        smallDead[p] += insectSeason(p, pollinators, dormantPollinators, active, 50f * flowers / (flowers + 0.3f), least = 0f)
        pollinators[p] = capped(p, pollinators[p], 100f)
        if (flowering) pollination[p] += 0.2f * (min(1f, pollinators[p] / 20f) - pollination[p])
    }

    /**
     * Desert locusts: in a spring after good rain, where the desert edge has greened, a swarm may rise;
     * it strips the green plants where it is, moves on (mostly with the wind) and dies out by late
     * summer. Birds, foxes, jackals and people eat them (see [catchSmall]).
     */
    private fun stepLocusts(weather: Weather) {
        val month = weather.date.month
        val season = month in 2..7
        val old = locusts.copyOf()
        for (p in 0 until n) {
            var l = old[p]
            // (A swarm is the hoppers that grew up on the green plants there: its weight comes off them.)
            if (season && l <= 0f && annualRain[p] < LOCUST_RAIN_MM && land.green[p] > 0.6f &&
                locustHash(p) < LOCUST_START * years) l = land.consume(p, LifeForm.HERB, LOCUST_SWARM / SMALL_EFFICIENCY / 1e6f) * SMALL_EFFICIENCY * 1e6f
            if (l <= 0f) continue
            // A swarm eats about its weight in green plants a day.
            val m2 = 1e6f
            val ate = land.consume(p, LifeForm.HERB, min(0.5f * land.herb[p], 0.8f * l * days / m2)) +
                land.consume(p, LifeForm.SHRUB, min(0.3f * land.shrub[p], 0.2f * l * days / m2))
            dung[p] += DUNG * ate
            val dying = l * (if (season) LOCUST_DEATHS else 0.6f)
            smallDead[p] += dying * 1f
            l -= dying
            // Most of a swarm moves on to the neighbours each week.
            val nb = land.neighbours[p]
            if (nb.isNotEmpty() && l > 1f) { val go = 0.6f * l; for (q in nb) locusts[q] += go / nb.size * land.areaKm2[p] / land.areaKm2[q]; l -= go }
            locusts[p] += l - old[p]
        }
        for (p in 0 until n) if (locusts[p] < 0.1f) { smallDead[p] += locusts[p]; locusts[p] = 0f }
    }

    private var locustSalt = 0L
    private fun locustHash(p: Int): Float {
        return unitOf(mix64Once((p.toLong() * HASH_A) xor (++locustSalt * HASH_B)))
    }

    /** Small game at place [p] this week: [t] its temperature, [burnt] the share that burned. */
    private fun stepGame(p: Int, t: Float, month: Int, burnt: Float) {
        if (land.biomass(p) <= 0f) { hares[p] = 0f; hyrax[p] = 0f; birds[p] = 0f; for (b in bird) b[p] = 0f; tortoises[p] = 0f; foxes[p] = 0f; return }
        val mild = t > 5f
        // Young raised a year per head, all born in the breeding months (so the rate in them is higher).
        hares[p] = logistic(p, hares[p], if (month in 3..9 && mild) inSeason(4f, 7) else 0f, 0.9f, hareCapacity(p), grazes = true, born = NEST_HARES)
        hyrax[p] = logistic(p, hyrax[p], if (month in 4..6) inSeason(1.2f, 3) else 0f, 0.4f, hyraxCapacity(p), grazes = true, born = NEST_HYRAX)
        var all = 0f
        for (k in bird.indices) { stepBird(k, p, month, mild); all += bird[k][p] }
        birds[p] = all
        nest(p, NEST_HARES, hares[p], 0f); nest(p, NEST_HYRAX, hyrax[p], 0f); nest(p, NEST_BIRDS, all, 0f)
        val active = stepTortoises(p, t, month)
        smallGrazing(p, all, active)
        stepFoxesAndOwls(p, month)
        if (eagleCrags[p] > 0f) stepEagles(p, month)
        // Fire: tortoises die where it burns; the rest bolt and many young die.
        if (burnt > 0f) {
            smallDead[p] += tortoises[p] * 0.6f * burnt + hares[p] * 0.3f * burnt + birds[p] * 0.3f * burnt
            tortLedger[TortLedger.BURNT][p] += tortoises[p] * 0.6f * burnt
            tortoises[p] *= 1f - 0.6f * burnt; hares[p] *= 1f - 0.3f * burnt; takeBirds(p, 0.3f * burnt)
        }
    }

    /**
     * Bird kind [k] at place [p] this week: migrants leave and come back, the rest feed on insects
     * as their kind does, breed in their months as fed ([mild] weather), and die more when short.
     */
    private fun stepBird(k: Int, p: Int, month: Int, mild: Boolean) {
        val b = BIRDS[k]; val f = bird[k]
        val here = b.present?.contains(month) ?: true
        // The insects they eat (most of what the chicks are raised on): short of them, fewer young
        // are raised and more birds die.
        var fed = 1f
        if (here && b.insectShare > 0f && f[p] > 0f) {
            val need = f[p] * BIRD_INTAKE * days * b.insectShare
            // (A few birds' need can round to nothing.)
            if (need > 0f) {
                val ate = takeSwitching(p, need, 0.5f, insectFood, insectEaseAt(p))
                herpLedger[HerpLedger.EATEN_BY_BIRDS][p] += ate
                fed = ate / need
            }
        }
        f[p] = when {
            // Migrants leave for beyond the map at the end of their months here, and come back.
            !here -> { if (f[p] > 0f) { leaving[k][p] = f[p] * land.areaKm2[p]; birdLedger[k][BirdLedger.LEFT][p] += f[p] }; 0f }
            returning[k] > 0.0 && !b.followsInsects -> {
                val back = (returning[k] * birdCapacity(k, p)).toFloat()
                birdLedger[k][BirdLedger.BACK][p] += back
                f[p] + back
            }
            else -> {
                val hunger = b.insectShare * (1f - fed)
                // (Insect-eating migrants settling this week arrive where the insects boom, and live on as the rest.)
                val arrived = if (b.followsInsects && returning[k] > 0.0) (returning[k] * birdCapacity(k, p) * boom(p)).toFloat() else 0f
                birdLedger[k][BirdLedger.BACK][p] += arrived
                if (here && b.insectShare > 0f) {
                    birdLedger[k][BirdLedger.FED_DAYS][p] += fed * days
                    birdLedger[k][BirdLedger.INSECT_DAYS][p] += days
                }
                var x = logistic(p, f[p] + arrived, if (month in b.breedMonths && mild) inSeason(b.growth, b.breedMonths.count()) * (1f - hunger) else 0f,
                    b.deaths * (1f + hunger), birdCapacity(k, p), grazes = true, ledger = birdLedger[k], born = NEST_BIRDS)
                // Insect-eating migrants leave a place where the insects have failed, for one where they boom.
                if (b.followsInsects && fed < 0.5f && x > 0f) {
                    val go = x * (1f - 2f * fed)
                    leaving[k][p] = go * land.areaKm2[p]
                    birdLedger[k][BirdLedger.LEFT][p] += go
                    x -= go
                }
                x
            }
        }
    }

    /** Tortoises at place [p]: out in the warm (when the soil is not dry), breeding in summer. Returns whether they are out. */
    private fun stepTortoises(p: Int, t: Float, month: Int): Boolean {
        val active = t in 14f..33f && land.soil[p] > 8f
        tortoises[p] = logistic(p, tortoises[p], if (active && month in 4..8) inSeason(0.3f, 5) else 0f, if (active) 0.08f else 0.03f,
            tortoiseCapacity(p), grazes = true, ledger = tortLedger, born = NEST_TORTOISES)
        nest(p, NEST_TORTOISES, tortoises[p], 0f)
        if (active) tortLedger[TortLedger.DAYS_OUT][p] += days; if (active && month in 4..8) tortLedger[TortLedger.BREEDING_DAYS][p] += days
        return active
    }

    /** The small plant-eaters at place [p] ([birdsHere] the birds, tortoises when [tortoisesOut]) eat what they grow on, and drop half of it again. */
    private fun smallGrazing(p: Int, birdsHere: Float, tortoisesOut: Boolean) {
        // (A small share of their weight a day; tortoises only when out.)
        val eaters = hares[p] + hyrax[p] + birdsHere + (if (tortoisesOut) tortoises[p] else 0f) // kg/km²
        if (eaters > 0f) {
            val eaten = land.consume(p, LifeForm.HERB, 0.7f * SMALL_INTAKE * eaters * days / 1e6f) +
                land.consume(p, LifeForm.SHRUB, 0.3f * SMALL_INTAKE * eaters * days / 1e6f)
            dung[p] += DUNG * eaten
        }
    }

    /** Foxes and wildcats at place [p], and the owls and small hawks that hunt the same rodents. */
    private fun stepFoxesAndOwls(p: Int, month: Int) {
        // Foxes and wildcats eat about a fourteenth of their weight a day: carrion where they find it, then
        // rodents, hares and birds; they breed in spring as the food allows, and die back when it fails.
        // (They stalk: what they reach of each, as [reach]; the young in the nest they take as found.)
        val eB = reachable(birds[p], young[NEST_BIRDS][p], FLIGHT_REACH)
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
        if (b0 > 0f && eB > 0f) young[NEST_BIRDS][p] = max(0f, young[NEST_BIRDS][p] - (b0 - birdSum(p)) * min(1f, young[NEST_BIRDS][p] / (eB * b0)))
        if (r0 > 0f) young[NEST_RODENTS][p] *= rodents[p] / r0; if (ha0 > 0f) young[NEST_HARES][p] *= hares[p] / ha0
        noteTaken(p, TAKER_FOXES, r0, ha0, hy0, b0)
        val fed = if (need > 0f) got / need else 1f
        // A litter of four to six a year, born in spring: over the three months, six times the stock a year.
        // Owls and small hawks eat about a fifth of their weight a day, mostly rodents.
        run {
            val r = rodents[p]
            if (owls[p] <= 0f && r > 0f) owls[p] = OWL_SEED * r
            val needO = owls[p] * OWL_INTAKE * days
            val gotO = min(0.5f * r, needO * r / (r + OWL_HALF))
            if (gotO > 0f) { rodents[p] -= gotO; preyTakenBy[Prey.RODENTS][TAKER_OWLS][p] += gotO }
            val fedO = if (needO > 0f) gotO / needO else 1f
            owls[p] = logistic(p, owls[p], if (month in 3..5) 3f * fedO else 0f, 0.3f + 0.8f * (1f - fedO), 0.02f * r)
        }
        foxes[p] = logistic(p, foxes[p], if (month in 4..6) 6f * fed else 0f, 0.4f + 0.8f * (1f - fed), 0.02f * (prey + dead))
    }

    /** Eagles at place [p] (where there are crags): a pair needs about a kilo a day; they take what is out in the open among the rocks. */
    private fun stepEagles(p: Int, month: Int) {
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
        noteTaken(p, TAKER_EAGLES, r0, ha0, hy0, b0)
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
    private fun scavengeFirst(p: Int, dead: Float, need: Float, half: Float) =
        if (dead > 0f && need > 0f) scavenge(p, min(0.5f * dead, need * dead / (dead + half)) / 1e6f) * 1e6f else 0f

    /** Yearly share of the young of small hoofed animals eagles take in place [p]. */
    fun eagleRisk(p: Int) = EAGLE_RATE * eagles[p] * open(p)

    /** Takes share [f] of every kind of bird at [p]. */
    private fun takeBirds(p: Int, f: Float) {
        for ((k, b) in bird.withIndex()) { birdLedger[k][BirdLedger.BURNT][p] += b[p] * f; b[p] -= b[p] * f }
        birds[p] -= birds[p] * f
    }

    /** The rate in the breeding months that gives [perYear] young a head a year when they last [months] months. */
    private fun inSeason(perYear: Float, months: Int) = perYear * 12f / months

    /**
     * Logistic growth with deaths, kg/km²: what dies (and what crowding beyond [k] takes back) goes to
     * the dead; a kind that [grazes] grows only by the plants it eats, at [SMALL_EFFICIENCY]. [counted]
     * false for an index that is not kg. Growth is at [r] a year in season, deaths at [d] a year, toward
     * [k], in place [p]. An empty place stays empty until animals spread in from around it.
     */
    private fun logistic(p: Int, x: Float, r: Float, d: Float, k: Float, grazes: Boolean = false, counted: Boolean = true,
        ledger: Array<FloatArray>? = null, born: Int = -1): Float {
        val kk = max(k, 0.01f)
        val dead = min(x, x * d * years * if (x > kk) 2f else 1f)
        var change = x * r * years * (1f - x / kk)
        if (change > 0f && grazes) change = land.consume(p, LifeForm.HERB, change / SMALL_EFFICIENCY / 1e6f) * SMALL_EFFICIENCY * 1e6f
        val crowded = if (change < 0f) min(x - dead, -change) else 0f
        if (counted) smallDead[p] += dead + crowded
        if (ledger != null) { ledger[Ledger.GROWN][p] += max(0f, change); ledger[Ledger.DIED][p] += dead; ledger[Ledger.CROWDED][p] += crowded }
        if (born >= 0) newborn[born][p] += max(0f, change)
        return max(0f, x - dead + max(0f, change) - crowded)
    }

    /** [x] held to [most], what is beyond it dying. */
    private fun capped(p: Int, x: Float, most: Float): Float {
        if (x > most) { smallDead[p] += x - most; return most }
        return max(0f, x)
    }

    /**
     * Vultures soar out from their colony each day and find the carcasses within reach, nearest
     * first, before most other scavengers; a colony grows when they eat well and shrinks when not.
     */
    private fun stepVultures(weather: Weather) {
        val month = weather.date.month
        for (k in vulturePlace.indices) {
            val need = vultures[k] * 0.5f * days
            var got = 0f
            for (q in vultureReach[k]) {
                if (got >= need) break
                if (carrion[q] > 0f) { val m2 = land.m2(q); got += scavenge(q, (need - got) / m2) * m2 }
            }
            val fed = if (need > 0f) min(1f, got / need) else 1f
            var b = vultures[k]
            if (month in 1..2) b += b * 0.5f * fed * years * 6f // one chick a pair, hatched in late winter
            b -= b * (0.08f * years + 0.4f * years * (1f - fed))
            vultures[k] = if (b < 2f) 0f else min(b, 2000f) // a colony below a pair is gone
        }
    }

    /**
     * A week of an insect's year at place [p], for any kind of insect kept as an [active] field and a
     * [dormant] one (kg/km², the dormant counted as the active insects they will become): they are out
     * while it is warm enough for them ([warm], 0..1, how far toward their best warmth) and grow fast
     * (larvae and nymphs gain some 10-20% of their weight a day) up to what their food carries
     * ([ceiling], kg/km²); when the food fades below that, or it is too cold, the adults lay their eggs
     * and die, and the next generation waits as eggs, larvae and pupae, with modest losses, and comes
     * out again over a few weeks as it warms. Returns what died, kg/km².
     */
    private fun insectSeason(p: Int, active: FloatArray, dormant: FloatArray, warm: Float, ceiling: Float, least: Float = 10f): Float {
        var ins = active[p]; var dor = dormant[p]
        var dead = dor * min(1f, DORMANT_DEATHS * years)
        dor -= dead
        if (warm > 0f) {
            val out = dor * 0.5f * warm; dor -= out; ins += out
            val k = max(least, ceiling)
            if (ins > 0f && ins < k) ins = k / (1f + (k / ins - 1f) * exp(-INSECT_GROWTH * warm * days))
            val died = ins * 2f * years; dead += died; ins -= died
            if (ins > k) { val spent = 0.5f * (ins - k); ins -= spent; dor += EGG_SHARE * spent; dead += (1f - EGG_SHARE) * spent }
        } else {
            val spent = 0.5f * ins; ins -= spent; dor += EGG_SHARE * spent; dead += (1f - EGG_SHARE) * spent
        }
        active[p] = ins; dormant[p] = dor
        return dead
    }

    /**
     * The share of the reptiles and amphibians of place [p] a hunter can find: the rest are in cover,
     * in rock cracks and screes, under shrubs and in the leaf litter, and come out only to bask and
     * hunt close by it. Each kind of cover hides more as there is more of it: [HERP_COVER_KG] of shrub
     * or of litter to the m² hides half of them, and so does the most broken ground.
     */
    fun herpExposed(p: Int) = rockHides[p] * HERP_COVER_KG / (HERP_COVER_KG + land.shrub[p]) * HERP_COVER_KG / (HERP_COVER_KG + litter[p])

    /**
     * How many reptiles and amphibians place [p] has breeding sites for, kg/km²: frogs, toads and newts
     * spawn in standing water (marsh, bog, floodplain), lizards, snakes and tortoises lay their eggs in
     * sheltered ground (cover, as [herpExposed]); taken as half of each. As they near it, the young
     * crowd the sites and fewer come through.
     */
    fun herpSites(p: Int) = HERP_MOST * (0.5f * min(1f, wetland[p] + land.floodShare[p]) + 0.5f * (1f - herpExposed(p)))

    /** Whether reptiles and amphibians are out: warm, and not baking in a drought. */
    fun herpsActive(t: Float, soilWater: Float) = t in 12f..34f && !(t > 28f && soilWater < 10f)

    /**
     * The young of the small vertebrates still in the nest or hiding where they were born, kg/km², of
     * rodents, hares, hyrax, birds, and reptiles and amphibians ([NEST_RODENTS]..): what this week's
     * births add, held [NEST_DAYS] days, never more than the kind's stock. They cannot run, fly or
     * hide, so any hunter that finds them can take them; see [reach].
     */
    val young = Array(NEST_KINDS) { FloatArray(n) }
    /** Births of the kinds bred by [logistic] this week, for [nest]. */
    private val newborn = Array(NEST_KINDS) { FloatArray(n) }

    /** Ages the young of nest kind [i] at [p] a week, adds [born] (and what [logistic] recorded), and holds them to the [stock]. */
    private fun nest(p: Int, i: Int, stock: Float, born: Float) {
        val y = young[i]
        y[p] = min(stock, y[p] * max(0f, 1f - days / NEST_DAYS[i]) + born + newborn[i][p])
        newborn[i][p] = 0f
    }

    /**
     * Share of small prey [prey] (an index of [huntedAll]; birds all as one) that a hunter of [style]
     * can reach at place [p] among the grown ones: how the prey escapes against how the hunter hunts.
     * Stalking and pouncing beasts ([STALK]: foxes, jackals, lynx) catch rodents and hares out
     * foraging, hyrax only away from their rocks, reptiles only out of cover ([herpExposed]), grown
     * tortoises not at all (the shell: only the young of the year, whose shells are still soft), and
     * flying birds only as far as they are caught on the ground ([FLIGHT_REACH]). Rooting beasts
     * ([ROOT]: boar, bears) catch none of the grown, only what is in the nest. People ([PEOPLE], and
     * the hunting groups of herds and packs) reach all, with snares, nets and digging sticks.
     */
    fun reach(style: Int, prey: Int, p: Int): Float = when (style) {
        PEOPLE -> 1f
        ROOT -> when (prey) { PREY_LOCUSTS -> 1f; PREY_INSECTS, PREY_POLLINATORS -> INSECT_CATCH; else -> 0f }
        else -> when (prey) {
            PREY_HYRAX -> rockHides[p]
            PREY_HERPS -> herpExposed(p)
            PREY_TORTOISES -> 0f
            PREY_INSECTS, PREY_POLLINATORS -> INSECT_CATCH
            PREY_BIRDS -> FLIGHT_REACH
            else -> 1f
        }
    }

    /** What share of [stock] a hunter reaches when the grown are reached at [adults] and the [y] young all. */
    private fun reachable(stock: Float, y: Float, adults: Float) = if (stock > 0f) (adults * (stock - min(y, stock)) + min(y, stock)) / stock else 0f

    /**
     * Takes up to [kgPerKm2] of small prey in place [p]: rodents, hares, hyrax and birds, locust
     * swarms, and when they are out, reptiles, amphibians and tortoises, each in proportion to what
     * a hunter of [style] can reach of it ([reach]; the young in the nest any can take), at most half
     * of all; and the scattered insects that are out (plant-eaters and pollinators) as far as a
     * vertebrate hunting by sight can pick them up ([INSECT_CATCH]). Returns kg per km² caught.
     */
    fun catchSmall(p: Int, kgPerKm2: Float, weather: Weather, most: Float = 0.5f, who: Int = TAKER_GROUPS, style: Int = PEOPLE): Float {
        val t = weather.temperature[p]
        val herpsOut = herpsActive(t, land.soil[p])
        val tortOut = t in 14f..33f
        // Desert rodents sleep through the summer heat in their burrows.
        val rodentsOut = !(t > 30f && land.soil[p] < 10f && annualRain[p] < DORMANT_RAIN_MM)
        // (Prey that is not out is not met: in the same order as [huntedAll].)
        val yr = young[NEST_RODENTS][p]
        val yha = young[NEST_HARES][p]
        val yhy = young[NEST_HYRAX][p]
        val yb = young[NEST_BIRDS][p]
        val yh = young[NEST_HERPS][p]
        val yt = young[NEST_TORTOISES][p]
        val eR = if (rodentsOut) reachable(rodents[p], yr, reach(style, PREY_RODENTS, p)) else 0f
        val eHa = reachable(hares[p], yha, reach(style, PREY_HARES, p)); val eHy = reachable(hyrax[p], yhy, reach(style, PREY_HYRAX, p))
        val eH = if (herpsOut) reachable(herps[p], yh, reach(style, PREY_HERPS, p)) else 0f
        val eB = reachable(birds[p], yb, reach(style, PREY_BIRDS, p))
        val eT = if (tortOut) reachable(tortoises[p], yt, reach(style, PREY_TORTOISES, p)) else 0f
        val eI = reach(style, PREY_INSECTS, p)
        val ease = floatArrayOf(eR, eHa, eHy, 1f, eH, eT, eI, eI) + FloatArray(bird.size) { eB }
        val total = eR * rodents[p] + eHa * hares[p] + eHy * hyrax[p] + eB * birds[p] + eH * herps[p] + eT * tortoises[p] + locusts[p] +
            eI * (insects[p] + pollinators[p])
        if (total <= 0f) return 0f
        val h0 = herps[p]; val t0 = tortoises[p]
        val r0 = rodents[p]; val ha0 = hares[p]; val hy0 = hyrax[p]; val b0 = birdSum(p)
        val took = takeSwitching(p, min(kgPerKm2, most * total), most, huntedAll, ease)
        // (Of what was taken of each kind, the young's share came out of the nest.)
        fun tookYoung(i: Int, before: Float, now: Float, e: Float) {
            if (before > 0f && e > 0f) young[i][p] = max(0f, young[i][p] - (before - now) * min(1f, young[i][p] / (e * before)))
        }
        tookYoung(NEST_RODENTS, r0, rodents[p], eR); tookYoung(NEST_HARES, ha0, hares[p], eHa); tookYoung(NEST_HYRAX, hy0, hyrax[p], eHy)
        tookYoung(NEST_BIRDS, b0, birdSum(p), eB); tookYoung(NEST_HERPS, h0, herps[p], eH); tookYoung(NEST_TORTOISES, t0, tortoises[p], eT)
        herpLedger[HerpLedger.HUNTED][p] += h0 - herps[p]
        herpTakenBy[min(who, TAKER_GROUPS)][p] += h0 - herps[p]
        tortLedger[TortLedger.HUNTED][p] += t0 - tortoises[p]; tortTakenBy[min(who, TAKER_GROUPS)][p] += t0 - tortoises[p]
        noteTaken(p, min(who, TAKER_GROUPS), r0, ha0, hy0, b0)
        return took
    }

    /**
     * A beast rooting in the soil of place [p] takes up to [kgPerKm2] of the grubs lying there (the larvae
     * and pupae of insects waiting out the cold or the drought), at most half of them; returns kg/km² taken.
     */
    fun digGrubs(p: Int, kgPerKm2: Float): Float {
        val there = dormantInsects[p] + dormantPollinators[p] + soilAnimals[p]
        if (kgPerKm2 <= 0f || there <= 0f) return 0f
        val took = min(kgPerKm2, 0.5f * there); val f = 1f - took / there
        dormantInsects[p] *= f; dormantPollinators[p] *= f; soilAnimals[p] *= f
        grubsDug[p] += took
        return took
    }
    /**
     * Each kind of [BIRDS] since cleared, kg/km² (for checks): 0 grown, 1 died (more when short of
     * insects), 2 died of crowding, 3 left (migrants, and insect-eaters leaving a failed place), 4 came
     * back, 5 burnt; 6 days fed (weighted by how well), 7 days out eating insects.
     */
    val birdLedger = Array(BIRDS.size) { Array(BirdLedger.SIZE) { FloatArray(n) } }
    fun birdCapacityAt(k: Int, p: Int) = birdCapacity(k, p)
    /** Grubs dug since cleared, kg/km² (for checks). */
    val grubsDug = FloatArray(n)

    /**
     * What happened to the reptiles and amphibians of each place since the ledger was last cleared,
     * kg/km² (for checks): 0 born, 1 died out, 2 died of hunger, 3 died lying up, 4 taken by hunters,
     * 5 insects they ate, 6 insects they needed, 7 insects the birds ate. (Hunters at the same place
     * in parallel may undercount 4 a little.)
     */
    val herpLedger = Array(HerpLedger.SIZE) { FloatArray(n) }
    /**
     * Reptiles and amphibians taken by hunters since the ledger was cleared, kg/km², by who took
     * them: the resident kind of that index, or (at [TAKER_GROUPS]) herds' and packs' hunters and
     * people.
     */
    val herpTakenBy = Array(TAKER_GROUPS + 1) { FloatArray(n) }
    /**
     * The same for tortoises (for checks), kg/km² unless said: 0 grown, 1 died, 2 died of crowding, 3 taken by
     * hunters, 4 burnt; 5 days out in the breeding months, 6 days out; and taken by who, as [herpTakenBy].
     */
    val tortLedger = Array(TortLedger.SIZE) { FloatArray(n) }
    val tortTakenBy = Array(TAKER_GROUPS + 1) { FloatArray(n) }
    /**
     * Rodents, hares, hyrax and birds (0..3) taken since cleared, kg/km², by who took them: as [herpTakenBy],
     * and foxes and wildcats ([TAKER_FOXES]), eagles ([TAKER_EAGLES]), owls ([TAKER_OWLS]). For checks.
     */
    val preyTakenBy = Array(Prey.SIZE) { Array(TAKER_OWLS + 1) { FloatArray(n) } }
    private fun birdSum(p: Int): Float { var all = 0f; for (b in bird) all += b[p]; return all }
    private fun noteTaken(p: Int, who: Int, r0: Float, ha0: Float, hy0: Float, b0: Float) {
        val t = preyTakenBy
        t[Prey.RODENTS][who][p] += r0 - rodents[p]
        t[Prey.HARES][who][p] += ha0 - hares[p]
        t[Prey.HYRAX][who][p] += hy0 - hyrax[p]
        t[Prey.BIRDS][who][p] += b0 - birdSum(p)
    }

    private val huntedAll by lazy { (listOf(rodents, hares, hyrax, locusts, herps, tortoises, insects, pollinators) + bird).toTypedArray() }
    private val foxPrey by lazy { (listOf(rodents, hares, locusts, insects, pollinators) + bird).toTypedArray() }
    /** What reptiles, amphibians and insect-eating birds eat: the insects that are out. */
    private val insectFood by lazy { arrayOf(insects, pollinators, locusts, biting, soilAnimals) }
    /** How much of the soil animals ground feeders reach this week, by place (see [soilAnimals]). */
    private val soilReach = FloatArray(n)
    private val insectEase = ThreadLocal.withInitial { floatArrayOf(1f, 1f, 1f, 1f, 0f) }
    /** The insects and soil animals at [p], as ground feeders reach them: what [takeSwitching] needs as ease. */
    private fun insectEaseAt(p: Int): FloatArray = insectEase.get().also { it[4] = soilReach[p] }

    /**
     * How hard the biting insects at [p] harass the animals and people there this week, 0..1: by how
     * many are out, and less in a wind (mosquitoes and midges barely fly in a breeze of a few m/s; a
     * herd on a windy ridge is left in peace).
     */
    fun harassment(p: Int): Float = harass[p]
    /** [harassment] this week, worked out once a step for all who ask. */
    private val harass = FloatArray(n)
    private fun harassmentNow(p: Int, weather: Weather): Float {
        val b = biting[p]
        if (b <= 0f) return 0f
        val wind = hypot(weather.windEast(p), weather.windSouth(p))
        return b / (b + HARASS_HALF) * exp(-wind / WIND_CALM)
    }

    /** How far the insects that are out at [p] are booming, 0..1: what draws the insect-eating migrants. */
    private fun boom(p: Int): Float { val x = insects[p] + pollinators[p] + locusts[p] + biting[p]; return x / (x + BOOM_HALF) }
    private val eaglePrey by lazy { arrayOf(hyrax, hares, bird[CHUKAR_AT], bird[ROCK_DOVE_AT]) }

    /**
     * Takes up to [want] kg/km² at place [p] from [stocks], at most [most] of each. Hunters switch
     * to what is common (as with large game): a kind thin on the ground is seldom met and passed over
     * for the common ones, each weighted by how easily it is found. [ease] (one per stock, 1 when
     * null) is the share of a kind the hunter can take at all: it counts and takes only that share.
     * Returns kg/km² taken.
     */
    private fun takeSwitching(p: Int, want: Float, most: Float, stocks: Array<FloatArray>, ease: FloatArray? = null): Float {
        if (want <= 0f) return 0f
        var weight = 0f
        for ((i, s) in stocks.withIndex()) { val x = s[p] * (ease?.get(i) ?: 1f); weight += x * x / (x + SMALL_FIND_HALF) }
        if (weight <= 0f) return 0f
        var took = 0f
        for ((i, s) in stocks.withIndex()) {
            val x = s[p] * (ease?.get(i) ?: 1f)
            val g = min(most * x, want * x * x / (x + SMALL_FIND_HALF) / weight)
            s[p] -= g; took += g
        }
        var all = 0f; for (b in bird) all += b[p]; birds[p] = all
        return took
    }

    /**
     * A hunter searching [sweptKm2] of place [p] takes up to [kg] of small prey: at most half of what
     * lives on the ground it searched. What it takes comes off the place's stocks; returns kg taken.
     */
    fun catchSmallIn(p: Int, kg: Float, sweptKm2: Float, weather: Weather): Float {
        val area = land.areaKm2[p]
        val searched = min(sweptKm2, area)
        if (kg <= 0f || searched <= 0f) return 0f
        return catchSmall(p, kg / area, weather, 0.5f * searched / area) * area
    }

    /** A scavenger searching [sweptKm2] of place [p] takes up to [kg] of carrion, at most half of what lies on the ground it searched; returns kg taken. */
    fun scavengeIn(p: Int, kg: Float, sweptKm2: Float): Float {
        val area = land.areaKm2[p]; val m2 = area * 1e6f
        val searched = min(sweptKm2, area)
        if (kg <= 0f || searched <= 0f) return 0f
        return scavenge(p, min(kg, 0.5f * carrion[p] * searched * 1e6f) / m2) * m2
    }

    /** The same for bones, for bone-crackers. */
    fun crackBonesIn(p: Int, kg: Float, sweptKm2: Float): Float {
        val area = land.areaKm2[p]; val m2 = area * 1e6f
        val searched = min(sweptKm2, area)
        if (kg <= 0f || searched <= 0f) return 0f
        return crackBones(p, min(kg, 0.5f * bones[p] * searched * 1e6f) / m2) * m2
    }

    /**
     * Small prey on the ground at [p], kg/km²: rodents, hares, hyrax, game birds, reptiles and
     * tortoises, locust swarms, and the insects a hunter can pick up (as [catchSmall]).
     */
    fun smallPreyKg(p: Int) = rodents[p] + hares[p] + hyrax[p] + birds[p] + herps[p] + tortoises[p] + locusts[p] + INSECT_CATCH * (insects[p] + pollinators[p])

    /** Totals over the land, tonnes, and vultures in all colonies. */
    fun summary(): String {
        fun t(a: FloatArray) = a.indices.sumOf { (a[it] * land.areaKm2[it]).toDouble() } / 1000
        return "rodents %.0f t, hares %.0f t, hyrax %.0f t, birds %.0f t (%s), tortoises %.0f t, foxes %.0f t, owls %.0f t, eagles %.0f pairs, %d vulture colonies %.0f birds".format(
            t(rodents), t(hares), t(hyrax), t(birds), bird.indices.joinToString(", ") { "%s %.0f".format(BIRDS[it].name, t(bird[it])) },
            t(tortoises), t(foxes), t(owls), eagles.indices.sumOf { (eagles[it] * land.areaKm2[it]).toDouble() } / EAGLE_KM2, vultures.size, vultures.sum())
    }

    /** Takes up to [kgPerM2] of carrion in place [p]; returns what was taken. */
    fun scavenge(p: Int, kgPerM2: Float): Float { val t = min(carrion[p], kgPerM2); carrion[p] -= t; record?.let { it.carrionGone[p] += t }; return t }


    /** Takes up to [kgPerM2] of bones in place [p] (for bone-crackers); returns what was taken. */
    fun crackBones(p: Int, kgPerM2: Float): Float { val t = min(bones[p], kgPerM2); bones[p] -= t; record?.let { it.bonesGone[p] += t }; return t }

    /** The fruit crop's share of what the trees could bear, from this year's pollination. */
    fun fruitSet(p: Int) = 0.3f + 0.7f * pollination[p]

    /**
     * Small animals wander between neighbouring places: as many leave one as arrive in the other, the
     * density difference over the smaller place; [where] keeps a kind to the places it can live in.
     */
    private fun spread(f: FloatArray, where: (Int) -> Boolean) {
        val old = f.copyOf()
        for (p in 0 until n) {
            if (!where(p)) continue
            val nb = land.neighbours[p]
            val w = spreadWeight[p]
            var delta = 0f
            for (i in nb.indices) { val q = nb[i]; if (where(q)) delta += w[i] * (old[q] - old[p]) }
            f[p] = max(0f, old[p] + delta)
        }
    }

    /** [spread] for kinds that can wander anywhere, all in one pass over the places. */
    private fun spread(fs: Array<FloatArray>) {
        val olds = Array(fs.size) { fs[it].copyOf() }
        IntStream.range(0, n).parallel().forEach { p ->
            val nb = land.neighbours[p]
            val w = spreadWeight[p]
            for (k in fs.indices) {
                val old = olds[k]
                var delta = 0f
                for (i in nb.indices) delta += w[i] * (old[nb[i]] - old[p])
                fs[k][p] = max(0f, old[p] + delta)
            }
        }
    }

    private val spreaders by lazy { (listOf(insects, rodents, herps, pollinators, biting, hares, hyrax, tortoises, foxes) + bird).toTypedArray() }
    /**
     * The share of the difference with each neighbour that crosses in a step: [SPREAD] a month, over
     * the larger place's count of neighbours and scaled to the smaller place's area. (The same flow
     * both ways between two places, so what one gains the other loses.)
     */
    private val spreadWeight = Array(n) { p ->
        val nb = land.neighbours[p]
        val rate = SPREAD * days / Date.DAYS_PER_MONTH
        FloatArray(nb.size) { i -> val q = nb[i]; rate / max(nb.size, land.neighbours[q].size) * min(land.areaKm2[p], land.areaKm2[q]) / land.areaKm2[p] }
    }

    companion object {
        val WETLANDS = setOf("MARSH", "SWAMP_FOREST", "BOG", "SALT_MARSH", "MANGROVE")
        /** Share of what small plant-eaters eat that becomes their own growth (about a tenth for warm-blooded animals, Lindeman's rule). */
        const val SMALL_EFFICIENCY = 0.1f
        val CHUKAR = BirdKind("chukar", 4..6, 4f, 0.9f, null,
            floatArrayOf(0.7f, 0.7f, 0.5f, 0.5f, 0.6f, 0.6f), insectShare = 0.3f)
        val QUAIL = BirdKind("quail", 4..7, 6f, 1.2f, (4..9).toSet(),
            floatArrayOf(0.7f, 0.9f, 0.7f, 0.5f, 0.5f, 0.4f), insectShare = 0.4f)
        val SANDGROUSE = BirdKind("sandgrouse", 4..7, 1.5f, 0.7f, null,
            floatArrayOf(0.5f, 0.6f, 0.2f, 0.3f, 0.3f, 0.4f), insectShare = 0.05f)
        val ROCK_DOVE = BirdKind("rock dove", 3..10, 5f, 0.8f, null,
            floatArrayOf(0.9f, 0.9f, 0.9f, 0.8f, 0.7f, 0.6f)) // became the pigeon
        val MALLARD = BirdKind("mallard", 4..6, 4f, 0.8f, null,
            floatArrayOf(0.9f, 0.8f, 0.8f, 0.7f, 0.6f, 0.6f), insectShare = 0.3f) // became the duck
        val GREYLAG_GOOSE = BirdKind("greylag goose", 5..6, 1.5f, 0.5f, (setOf(10, 11, 0, 1, 2, 3)),
            floatArrayOf(0.8f, 0.8f, 0.8f, 0.6f, 0.6f, 0.8f)) // became the goose
        // Common cranes winter by the thousand in the rift's marshes and fields; white storks pass
        // through on the way to and from Africa in spring and late summer. Both breed far away.
        val CRANE = BirdKind("crane", 5..6, 0.5f, 0.3f, setOf(10, 11, 0, 1, 2),
            floatArrayOf(0.5f, 0.3f, 0.3f, 0.4f, 0.3f, 0.5f), insectShare = 0.1f)
        val WHITE_STORK = BirdKind("white stork", 5..6, 1.2f, 0.3f, setOf(2, 3, 7, 8),
            floatArrayOf(0.5f, 0.3f, 0.3f, 0.5f, 0.4f, 0.4f), insectShare = 0.6f)
        // Songbirds (larks, warblers, tits, finches, thrushes) as one guild: small, many, short-lived,
        // raising their broods on insects. About half the adults die each year; the hunters here take
        // some 10-15% of them, so the rest (cold, hunger, sickness, old age) is about 0.45.
        val SONGBIRDS = BirdKind("songbirds", 4..7, 3f, 0.45f, null,
            floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f), insectShare = 0.7f)
        /**
         * The wild birds. Months count from the winter solstice (0 about January): quail are here
         * from spring to early autumn, greylag geese from autumn to early spring.
         */
        val BIRDS = listOf(CHUKAR, QUAIL, SANDGROUSE, ROCK_DOVE, MALLARD, GREYLAG_GOOSE, CRANE, WHITE_STORK, SONGBIRDS)
        /** How far vultures soar from the colony for carcasses, km; land per colony at most, km. */
        const val VULTURE_RANGE_KM = 80.0
        const val VULTURE_SPACING_KM = 80.0
        /**
         * Eagles: what the eagles over a place eat at full strength (a pair to [EAGLE_KM2]), kg/km²
         * a day; yearly share of young taken in open crag country.
         */
        const val EAGLE_NEED = 0.01f
        /** Ground a pair hunts over where the eagles are at full strength, km². */
        const val EAGLE_KM2 = 100.0
        const val EAGLE_RATE = 0.15f
        /** Share of what small plant-eaters eat that comes back as frass and droppings. */
        const val FRASS = 0.5f
        /**
         * Reptiles and amphibians: what they eat a day out, as a share of their weight (cold-blooded,
         * some 1-3%); young raised a year per head while out and fed (a clutch or spawn a year, few of
         * which live); yearly share dying while out, more when hungry, and while lying up (most adults
         * live some years, and most come through a winter).
         */
        const val HERP_INTAKE = 0.02f
        const val HERP_YOUNG = 2f
        const val HERP_DEATHS = 0.4f
        const val HERP_HUNGER = 1f
        const val HERP_DORMANT_DEATHS = 0.15f
        const val HERP_COVER_KG = 1f
        /**
         * Reptiles and amphibians where every bit of the ground is breeding site, kg/km²: some 10 kg a hectare,
         * as woodland salamanders alone reach in moist eastern North American forest (median some 10,000 a
         * hectare at about a gram each, range 2,000-34,000; Biol. Lett. 2024, doi:10.1098/rsbl.2024.0033).
         */
        const val HERP_MOST = 1000f
        /**
         * Tortoises in warm scrub at its best, kg/km²: some 11-13 Hermann's tortoises a hectare in good
         * Spanish sites (Mascort 1997; Garraf 2010) at about a kilo each.
         */
        const val TORTOISE_MOST = 1000f
        /**
         * Share of grown birds a stalking beast can catch: those it surprises on the ground, at roost or
         * on the nest; the rest fly. An estimate.
         */
        const val FLIGHT_REACH = 0.2f
        /** Ways of hunting small prey, for [reach]: stalking and pouncing beasts, rooting beasts, people. */
        const val STALK = 0
        const val ROOT = 1
        const val PEOPLE = 2
        /** The small prey a hunter meets, as [reach] and [catchSmall] number them (the order of [huntedAll]; birds all as one). */
        const val PREY_RODENTS = 0
        const val PREY_HARES = 1
        const val PREY_HYRAX = 2
        const val PREY_LOCUSTS = 3
        const val PREY_HERPS = 4
        const val PREY_TORTOISES = 5
        const val PREY_INSECTS = 6
        const val PREY_POLLINATORS = 7
        const val PREY_BIRDS = 8
        /** The kinds whose [young] are kept: rodents, hares, hyrax, birds, reptiles and amphibians, tortoises. */
        const val NEST_RODENTS = 0
        const val NEST_HARES = 1
        const val NEST_HYRAX = 2
        const val NEST_BIRDS = 3
        const val NEST_HERPS = 4
        const val NEST_TORTOISES = 5
        const val NEST_KINDS = 6
        /**
         * Days the young stay where they were born, unable to flee: mouse and vole pups some three weeks in
         * the nest, leverets four lying up in their forms, hyrax young a month by the rocks, chicks a month
         * from laying to fledging, the eggs and hatchlings of reptiles and amphibians some two months, and
         * young tortoises their first year, while the shell is soft (grown ones beasts rarely kill: Sci.
         * Rep. 2022, doi:10.1038/s41598-022-22288-9).
         */
        val NEST_DAYS = floatArrayOf(21f, 28f, 30f, 30f, 60f, 360f)
        /** The [herpTakenBy] slot of hunting groups and people (resident kinds take the slots below it). */
        const val TAKER_GROUPS = 40
        const val TAKER_FOXES = 41
        const val TAKER_EAGLES = 42
        const val TAKER_OWLS = 43
        /**
         * Plant-eating insects: the warmth at which they start to grow (the usual lower threshold for
         * development, about 10 °C, down to about 5 °C in cool-summer insects), and the warmth above it
         * at which they grow fastest, °C; their fastest growth, a day (larvae and nymphs gain some 10-20%
         * of their weight a day); the yearly share of the dormant ones dying (some half to two thirds
         * live through a winter); and the share of the spent adults' weight left as the next generation.
         */
        const val INSECT_FROM_C = 10f
        const val INSECT_FROM_MIN_C = 5f
        const val INSECT_SPAN_C = 15f
        const val INSECT_GROWTH = 0.15f
        /**
         * Canopy insects: the share of the leaves woody plants put out each year that insects eat (about 7%
         * worldwide, less toward the poles: Kozlov et al. 2015, "Global patterns in background losses of woody plant foliage to insects");
         * what they eat a day, as a share of their weight; and how long the leaves are out, days (about half
         * a year where they fall in winter).
         */
        const val CANOPY_HERBIVORY = 0.07f
        const val INSECT_INTAKE = 0.25f
        const val LEAF_DAYS = 180f
        const val DORMANT_DEATHS = 1f
        const val EGG_SHARE = 0.5f
        /** Share of a rotting carcass left as bones. */
        const val BONE_SHARE = 0.2f
        const val MIGRANT_SURVIVAL = 0.7f
        /**
         * Birds: what they eat a day, as a share of their weight (small birds a tenth to a quarter);
         * the share of the insect-eating migrants waiting on the map that settle in a week; and the
         * insects out, kg/km², at which a place draws them half as strongly as a booming one.
         */
        const val BIRD_INTAKE = 0.1f
        const val SETTLE = 0.5
        const val BOOM_HALF = 200f
        /**
         * Harassment by biting insects: the biting insects out, kg/km², at which it is half its worst;
         * the wind, m/s, that cuts it to about a third; the share of their feeding time harassed
         * animals lose at its worst (reindeer under mosquitoes and warble flies stand, run and bunch
         * instead of grazing for much of the day); and how much longer people take at what they do
         * outdoors at its worst.
         */
        const val HARASS_HALF = 100f
        const val WIND_CALM = 3f
        const val HARASS_FEED = 0.4f
        const val HARASS_TIME = 0.5f
        /** Plant food small plant-eaters (hares, hyrax, game birds, tortoises) eat, kg a day per kg of them. */
        const val SMALL_INTAKE = 0.08f
        /** Foxes and wildcats eat about a fourteenth of their weight a day; prey on the ground (kg/km²) at which they get half of it. */
        const val FOX_INTAKE = 0.07f
        const val FOX_HALF = 3f
        /**
         * Locusts: where they swarm (rain under this, mm a year); yearly chance a greened place
         * raises a swarm; a swarm, kg/km²; share dying a week in season.
         */
        const val LOCUST_RAIN_MM = 250f
        const val LOCUST_START = 0.0005f
        const val LOCUST_SWARM = 5000f
        const val LOCUST_DEATHS = 0.15f
        /** Desert rodents (where rain is under this, mm a year) sleep through hot, dry weeks. */
        const val DORMANT_RAIN_MM = 300f
        /**
         * Owls and small hawks: food a day per kg of them; rodents (kg/km²) at which they get half
         * of it; their share of the rodents where they first appear.
         */
        const val OWL_INTAKE = 0.2f
        const val OWL_HALF = 3f
        /**
         * Share of a carcass gone a day at 15 °C (maggots, beetles, bacteria, and the crows and
         * ravens not counted here; foxes, wildcats and eagles are counted, below): half in three or
         * four days.
         */
        const val CARRION_ROT = 0.2f
        /** Small prey of one kind, kg/km², at which hunters find it half as readily as a common kind. */
        const val SMALL_FIND_HALF = 2f
        /**
         * Soil animals: the share of the litter rotting that becomes their flesh, kg fresh per kg; their
         * yearly deaths (most live about a year); and the share of them at the surface and in the litter
         * where birds, toads, lizards and shrews find them. Soil fauna eat 15-100% of the litter that
         * falls (Hedenec et al., Sci. Rep. 2022, doi:10.1038/s41598-022-21563-z). Most earthworms keep to
         * the top 5 cm, and on moist nights only some 0.04-4 a m² come up where birds can see them (Onrust
         * et al., J. Appl. Ecol. 2019, doi:10.1111/1365-2664.13356): a few in a hundred of them at most.
         */
        const val SOIL_YIELD = 0.02f
        /**
         * Soil life in sodden soil, without air: rot runs at this share of its rate in drained soil (anaerobic
         * decomposition is some 2-3 times slower, from memory; the CENTURY model's drainage factor is of that order).
         */
        const val ANAEROBIC = 0.4f
        /**
         * Share of the litter lying on the ground that each mm of water running off over it washes into the
         * streams. Rivers carry off some 1-5 g of organic carbon a m² of their catchment a year (from memory),
         * about 1% of what falls as litter, with a few hundred mm of runoff: about 5e-5 a mm.
         */
        const val WASH_PER_MM = 5e-5f
        const val SOIL_DEATHS = 1f
        const val SOIL_REACH = 0.02f
        /**
         * Share of the scattered insects that are out (plant-eaters and pollinators) a vertebrate
         * hunting small prey (foxes, jackals, badgers, bears, people) can pick up and count on: beetles,
         * grasshoppers and bees are many but each is a few hundred milligrams, found one at a time.
         * Locust swarms and the reptiles' own hunting are not limited by it.
         */
        const val INSECT_CATCH = 0.03f
        const val OWL_SEED = 0.002f
        /** Small prey out in the open (kg/km²) at which eagles get half of what they need. */
        const val EAGLE_HALF = 1f
        /** Share of the difference with its neighbours a small animal population evens out in a month. */
        const val SPREAD = 0.2f
        /** Rodents' deaths a year from all causes but predators, cold, snow and hunger. */
        val RODENT_DEATH = inflation.Config.float("t.rodentDeath", 1.2f)
        /** Share of what herds and packs eat that comes back as dung. */
        const val DUNG = 0.5f
        private val CHUKAR_AT = BIRDS.indexOf(CHUKAR)
        private val ROCK_DOVE_AT = BIRDS.indexOf(ROCK_DOVE)
    }
}

/** The slots [HexSmallLife.logistic] fills in a ledger: what grew, what died, what died of crowding. */
object Ledger {
    const val GROWN = 0
    const val DIED = 1
    const val CROWDED = 2
}

/** Slots of [HexSmallLife.herpLedger]. */
object HerpLedger {
    const val BORN = 0
    const val DIED_OUT = 1
    const val HUNGER = 2
    const val LYING_UP = 3
    const val HUNTED = 4
    const val ATE = 5
    const val NEEDED = 6
    const val EATEN_BY_BIRDS = 7
    const val SIZE = 8
}

/** Slots of [HexSmallLife.tortLedger] (the first three as [Ledger]). */
object TortLedger {
    const val GROWN = 0
    const val DIED = 1
    const val CROWDED = 2
    const val HUNTED = 3
    const val BURNT = 4
    const val BREEDING_DAYS = 5
    const val DAYS_OUT = 6
    const val SIZE = 7
}

/** Slots of each kind's [HexSmallLife.birdLedger] (the first three as [Ledger]). */
object BirdLedger {
    const val GROWN = 0
    const val DIED = 1
    const val CROWDED = 2
    const val LEFT = 3
    const val BACK = 4
    const val BURNT = 5
    const val FED_DAYS = 6
    const val INSECT_DAYS = 7
    const val SIZE = 8
}

/** The small prey of [HexSmallLife.preyTakenBy]. */
object Prey {
    const val RODENTS = 0
    const val HARES = 1
    const val HYRAX = 2
    const val BIRDS = 3
    const val SIZE = 4
}
