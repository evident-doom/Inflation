package inflation.ecology

import inflation.world.StepClock
import inflation.drawUnit
import inflation.Par
import inflation.Part
import inflation.enumTables
import inflation.Stream
import inflation.world.Place
import inflation.world.get
import inflation.world.set

import inflation.Kg
import inflation.KgKm2
import inflation.KgM2
import inflation.StockName
import inflation.min
import inflation.world.Biome
import inflation.world.HexPlants
import inflation.wrapMonth
import inflation.world.Date
import inflation.world.HexLand
import inflation.world.HexWorld
import inflation.world.LifeForm
import inflation.world.Weather
import java.util.EnumSet
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import java.util.concurrent.atomic.DoubleAdder
import inflation.Books
import inflation.Stocks
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
 * The life in the soil and the small animals of each place (a hex, or its low or high ground), as in the 1 km
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
class HexSmallLife(private val hw: HexWorld, internal val land: HexLand, @Transient private val clock: StepClock) {
    /** Days the step covers ([StepClock]). */
    internal val days get() = clock.days
    /** The step's ticks ([StepClock.ticks]). */
    internal val ticks get() = clock.ticks
    /** Days a tick ([StepClock.tickDays]): what the rates reckoned a tick are reckoned over. */
    internal val tickDays get() = clock.tickDays
    /** A share [perTick] taken or lost each tick (a week's), over the step's ticks: 1 - (1 - p)^ticks; itself at one tick a step. */
    internal fun overTicks(perTick: Float) = clock.share(perTick)
    internal val tuning get() = land.tuning
    val n = land.n
    /** Soil: litter and organic matter, kg/m²; carrion, kg/m²; soil animals, g/m². */
    val litter = FloatArray(n) { p -> 0.5f * (land.plants.herb[p] + 0.2f * land.plants.shrub[p] + 0.04f * land.plants.tree[p]) + 0.3f }
    val organicMatter = FloatArray(n) { p -> 4f * min(1f, land.plants.biomass(Place(p)) / 5f) }
    val carrion = FloatArray(n)
    /**
     * Carrion the groups found as it lay through the step, kg/m², held for them till they forage at its end ([stepSoil], in
     * closed form over the step's days): they come on the carrion that lies at any time in it, not only on what lies at the
     * step's end (Olli 2026-11-11, "carrion per tick": at 60 days leopard packs found 60% less, pass728). Empty between steps.
     */
    @Transient val groupCarrion = FloatArray(n)
    /**
     * How fast the groups that searched each place in the last step came on its carrion, a day: the share of the place each
     * searched a day, times how well it finds carrion ([CARRION_FOUND], [inflation.ecology.offerFoods]), summed. The step's
     * carrion is shared between them and the rot at these rates ([stepSoil]).
     */
    val groupCarrionRate = FloatArray(n)
    /** Of [groupCarrion] and [groupCarrionRate], as the step's groups set out to forage: each finder takes its rate's share of what was found. */
    @Transient val groupCarrionFound = FloatArray(n); @Transient val groupCarrionRateLast = FloatArray(n)
    /**
     * Carrion the residents found as it lay through the last span, kg/m², held for them till they feed next ([stepSoil]):
     * like [groupCarrion], so over a long step they come on what lies at any time in it, not only on what is left at its end.
     * What they leave of it goes back on the ground as they feed ([HexFauna.feedKinds]).
     */
    val residentCarrion = FloatArray(n)
    /** How fast the residents of each place come on its carrion, a day, as they last fed there: summed over the kinds that eat it ([stepSoil]). */
    val residentCarrionRate = FloatArray(n)
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
    val soilAnimals = FloatArray(n) { p -> SOIL_YIELD / SOIL_DEATHS * 1e6f * (land.plants.herb[p] + 0.2f * land.plants.shrub[p] + 0.04f * land.plants.tree[p]) }
    /** Small animals, kg/km². */
    val insects = FloatArray(n) { p -> 300f * min(1f, land.plants.herb[p] / 0.4f) }
    /** Plant-eating insects waiting out the cold or the drought as eggs, larvae and pupae, kg/km² (as the active insects they will become). */
    val dormantInsects = FloatArray(n)
    val biting = FloatArray(n)
    /** Biting insects waiting as eggs and larvae in the mud and water, kg/km² (as the adults they will become). */
    val dormantBiting = FloatArray(n)
    val rodents = FloatArray(n) { p -> 150f * min(1f, land.plants.herb[p] / 0.4f) }
    val herps = FloatArray(n) { p -> 20f * min(1f, land.plants.herb[p] / 0.4f) }
    val pollinators = FloatArray(n) { p -> 10f * min(1f, land.plants.herb[p] / 0.4f) }
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
    @Transient internal val eagleCrags = land.overGround { min(1f, 2f * it) }
    val eagles = eagleCrags.copyOf()
    /**
     * Rock for the small life of broken ground, the mean over each place's ground (see
     * [HexLand.overGround]): hyrax and rock doves, chukar, and the cracks and screes reptiles and
     * small mammals hide in.
     */
    @Transient private val rockKopje = land.overGround { it.pow(1.5f) }
    @Transient private val rockChukar = land.overGround { min(1f, it / 0.5f) }
    @Transient internal val rockHides = land.overGround { 1f / (1f + min(1f, it)) }
    /** Vulture colonies: nest place, the birds, and the places within reach, nearest first. */
    val vulturePlace = ArrayList<Int>()
    val vultures = ArrayList<Float>()
    internal val vultureReach = ArrayList<IntArray>()
    /** Scratch: litter the runoff washed off each place this week, kg; organic matter floods laid on each, kg/m² (see [HexHydrology.carrySilt]). */
    @Transient internal val washedKg = FloatArray(n)
    @Transient val laidKgM2 = FloatArray(n)
    /** Dung dropped this week by the animals, kg/m²: it goes to the litter. */
    val dung = FloatArray(n)
    /** Small animals that died this week in each place, kg/km²: they go to the litter. */
    internal val smallDead = FloatArray(n)
    /** Migrant birds away beyond the map, kg, per kind; and how much ground each kind finds when it comes back. */
    private val away = DoubleArray(BIRDS.size)
    internal val returning = DoubleArray(BIRDS.size)
    /** Migrants that live on insects, back on the map but not yet settled, kg per kind: they settle where the insects boom. */
    private val waiting = DoubleArray(BIRDS.size)
    /** Migrant birds leaving this week from each place, kg (summed in place order after the parallel loop, so the sum is the same on any number of cores). */
    internal val leaving = Array(BIRDS.size) { FloatArray(n) }
    private var lastMonth = -1
    internal val years get() = clock.years
    /**
     * For the books (see [Books]), this step, kg; null when they are not kept: what the small animals grew (born and
     * grown, from what they ate or from their rates), ate, and dropped as dung and frass.
     */
    @Transient private var grewBook: DoubleAdder? = null
    @Transient internal var ateBook: DoubleAdder? = null
    @Transient private var droppedBook: DoubleAdder? = null
    /** And in the soil: what rotted away to the air, what came in from the plants and the residents' carcasses, and the soil animals' leaks. */
    @Transient internal var rotBook: DoubleAdder? = null
    @Transient internal var litterfallBook: DoubleAdder? = null
    @Transient internal var carcassBook: DoubleAdder? = null
    @Transient internal var soilGrewLeak: DoubleAdder? = null
    @Transient internal var soilDiedLeak: DoubleAdder? = null
    internal fun grew(p: Place, kgKm2: Float) { grewBook?.add(kgKm2 * land.areaKm2[p].toDouble()) }
    internal fun ate(p: Place, kgKm2: Float) { ateBook?.add(-kgKm2 * land.areaKm2[p].toDouble()) }
    internal fun dropped(p: Place, kgM2: Float) { droppedBook?.add(kgM2 * land.areaM2[p].toDouble()) }
    internal fun ateM2(p: Place, kgM2: Float) { ateBook?.add(-kgM2 * land.areaM2[p].toDouble()) }
    @Transient internal var crowdLeak: DoubleAdder? = null
    /** Share of each place that is wetland, where biting insects breed. */
    @Transient val wetland = FloatArray(n) { p -> hw.placeCells[p].count { val b = hw.world.biome[it]; b in WETLANDS }.toFloat() / hw.placeCells[p].size }
    /** Marsh, bog and floodplain, where ducks and geese feed. */
    @Transient private val waterfowl = FloatArray(n) { p -> min(1f, wetland[p] + land.floodShare[p]) }
    /** The year's mean temperature and rain at each place. */
    @Transient private val meanTemp = FloatArray(n) { p -> (0 until 12).map { hw.placeTemp(Place(p), it) }.average().toFloat() }
    @Transient internal val annualRain = FloatArray(n) { p -> (0 until 12).sumOf { hw.precip[it][hw.placeHex[p]].toDouble() }.toFloat() }
    /**
     * The warmth, °C, at which the plant-eating insects of each place start to grow: about 10 °C,
     * lower where summers are cool and the insects are adapted to them.
     */
    @Transient internal val insectFrom = FloatArray(n) { p -> ((0 until 12).maxOf { hw.placeTemp(Place(p), it) } - 12f).coerceIn(INSECT_FROM_MIN_C, INSECT_FROM_C) }

    init {
        for (p in land.places) {
            if (land.plants.biomass(p) <= 0f) continue
            hares[p] = 0.5f * hareCapacity(p); hyrax[p] = 0.5f * hyraxCapacity(p)
            for (k in bird.indices) bird[k][p] = 0.5f * birdCapacity(k, p)
            birds[p] = bird.sumOf { it[p].toDouble() }.toFloat()
            tortoises[p] = 0.5f * tortoiseCapacity(p); foxes[p] = 0.3f
            dormantBiting[p] = 50f * waterfowl[p]
        }
        // Vultures: the most broken ground with plants in each block, if broken enough for cliffs.
        val hex = hw.hex
        val blocks = HashMap<Long, Int>()
        for (p in land.places) {
            if (land.plants.biomass(p) <= 0f || land.ruggedness[p] <= 0.5f) continue
            val (x, y) = hex.centreKm(hw.placeHex[p])
            val key = (x / VULTURE_SPACING_KM).toLong() * 100000 + (y / VULTURE_SPACING_KM).toLong()
            val best = blocks[key]
            if (best == null || land.ruggedness[p] > land.ruggedness[best]) blocks[key] = p.i
        }
        for (c in blocks.values.sorted()) {
            val (cx, cy) = hex.centreKm(hw.placeHex[c])
            fun dist(q: Place): Double { val (x, y) = hex.centreKm(hw.placeHex[q]); return hypot(x - cx, y - cy) }
            val near = (0 until n).filter { dist(Place(it)) <= VULTURE_RANGE_KM }.sortedBy { dist(Place(it)) }
            vulturePlace += c; vultures += 40f; vultureReach += near.toIntArray()
        }
    }

    internal fun open(p: Place) = 1f - min(1f, land.plants.canopy(p) + 0.5f * min(1f, land.plants.shrub[p] / 1.5f))
    internal fun hareCapacity(p: Place) = 40f * land.plants.herb[p] / (land.plants.herb[p] + 0.3f) * open(p)
    internal fun hyraxCapacity(p: Place) = 80f * rockKopje[p] * min(1f, land.plants.shrub[p] / 0.5f + 0.2f)
    internal fun birdCapacity(k: Int, p: Place): Float {
        val grass = land.plants.herb[p] / (land.plants.herb[p] + 0.2f)
        val shrubby = min(1f, land.plants.shrub[p])
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
    fun tortoiseCapacity(p: Place): Float {
        val warm = ((meanTemp[p] - 12f) / 8f).coerceIn(0f, 1f)
        return TORTOISE_MOST * warm * min(1f, (land.plants.shrub[p] + land.plants.herb[p]) / 0.8f)
    }

    fun step(weather: Weather, carcassKg: FloatArray) {
        val month = weather.date.month
        val books = land.books
        grewBook = books?.adder(Books.Stuff.MATTER, "small animals grew"); ateBook = books?.adder(Books.Stuff.MATTER, "eaten")
        droppedBook = books?.adder(Books.Stuff.MATTER, "dung and frass")
        rotBook = books?.adder(Books.Stuff.MATTER, "rot, to the air"); litterfallBook = books?.adder(Books.Stuff.MATTER, "litterfall (from the plants)")
        carcassBook = books?.adder(Books.Stuff.MATTER, "carcasses (from the residents)")
        soilGrewLeak = books?.leakAdder(Books.Stuff.MATTER, "soil animals grow from rot already gone to humus and air")
        soilDiedLeak = books?.leakAdder(Books.Stuff.MATTER, "soil animals die without a body")
        crowdLeak = books?.leakAdder(Books.Stuff.MATTER, "rodents crowded out without a body")
        // Migrant birds: those leaving this week go beyond the map; those coming back spread over the
        // ground that draws them, as many as survived the winter away.
        for (k in BIRDS.indices) {
            val b = BIRDS[k]
            val pr = b.present ?: continue
            returning[k] = 0.0
            if (lastMonth >= 0 && month in pr && lastMonth !in pr) {
                var cap = 0.0
                for (p in land.places) cap += (birdCapacity(k, p) * land.areaKm2[p]).toDouble()
                // Kinds that breed far away (winter visitors such as geese, passage migrants) come from a
                // flyway's whole population, held by its breeding grounds, not by the few that wintered here:
                // as many come as the ground draws. Those that breed here come back as many as lived.
                val awayMonths = generateSequence(wrapMonth(month - 1)) { wrapMonth(it - 1) }.takeWhile { it !in pr }.toSet()
                val bredAway = b.breedMonths.any { it in awayMonths }
                val back = if (bredAway) cap else away[k] * MIGRANT_SURVIVAL
                books?.flow(Books.Stuff.MATTER, "migrant birds beyond the map", back - away[k])
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
                    for (p in land.places) draw += (birdCapacity(k, p) * boom(p) * land.areaKm2[p]).toDouble()
                    if (draw > 0.0) { val settle = if (clock.ticks == 1) SETTLE else 1.0 - (1.0 - SETTLE).pow(clock.ticks)
                        returning[k] = settle * waiting[k] / draw; waiting[k] *= 1.0 - settle }
                    books?.flow(Books.Stuff.MATTER, "migrant birds beyond the map", -waiting[k] * b.deaths * years)
                    waiting[k] *= 1.0 - b.deaths * years
                }
            }
        }
        Par.forEachPlace("small life: step", n) { p ->
            val t = weather.temperature[p]
            val soilWater = land.hydro.soil[p]
            stepSoil(p, t, soilWater, carcassKg)
            val food = land.plants.herb[p] + 0.3f * land.plants.shrub[p]
            val snow = weather.snowpack[p]
            val active = stepInsects(p, t)
            stepBiting(p, active, soilWater, weather)
            stepRodents(p, t, food, snow)
            stepHerps(p, t, soilWater)
            val b = land.fire.stepBurning[p]
            burnSmallLife(p, b)
            rodentRoom[p] *= 1f - RODENTS_BURNT * b
            stepPollinators(p, month, active)
            stepGame(p, t, month, b)
        }
        // What the runoff washed off goes down the rivers; floods lay part of it on the floodplains with their silt.
        land.hydro.carrySilt(washedKg, laidKgM2)
        for (p in land.places) if (laidKgM2[p] > 0f) organicMatter[p] += laidKgM2[p]
        if (books != null) {
            var off = 0.0; for (p in land.places) off += washedKg[p] - laidKgM2[p] * land.areaM2[p].toDouble()
            books.flow(Books.Stuff.MATTER, "litter washed to the sea and closed lakes", -off)
        }
        stepLocusts(weather)
        for (k in BIRDS.indices) {
            val l = leaving[k]; var sum = 0.0; for (p in l.indices) if (l[p] != 0f) { sum += l[p]; l[p] = 0f }
            // (Insect-eaters leaving a place where the insects failed, within their months, look for another.)
            if (BIRDS[k].followsInsects && month in BIRDS[k].present!!) waiting[k] += sum else away[k] += sum
        }
        lastMonth = month
        stepVultures(weather)
        // A little every week, not all at once each month: a monthly lump would show as a pulse at the
        // start of each month where the spreaders arrived and then died back over the weeks.
        spreadToNeighbours()
    }

    /**
     * Takes up to the asked kg/m² of fallen mast at a place, returning what was taken: set by [HexFauna], which holds the
     * stock ([HexFood.mast]).
     */
    @Transient var takeMast: ((Place, KgM2) -> KgM2)? = null

    /** The rate in the breeding months that gives [perYear] young a head a year when they last [months] months. */
    internal fun inSeason(perYear: Float, months: Int) = perYear * 12f / months

    /**
     * Logistic growth with deaths, kg/km²: what dies (and what crowding beyond [k] takes back) goes to
     * the dead; a kind that [grazes] grows only by the plants it eats, at [SMALL_EFFICIENCY]. [counted]
     * false for an index that is not kg. Growth is at [r] a year in season, deaths at [d] a year, toward
     * [k], in place [p]. An empty place stays empty until animals spread in from around it.
     */
    internal fun logistic(p: Place, x: Float, r: Float, d: Float, k: Float, grazes: Boolean = false, counted: Boolean = true,
        grown: FloatArray? = null, died: FloatArray? = null, crowdedTo: FloatArray? = null, born: Nest? = null): Float {
        val kk = max(k, 0.01f)
        val dead = min(x, x * d * years * if (x > kk) 2f else 1f)
        var change = x * r * years * (1f - x / kk)
        if (change > 0f && grazes) {
            val taken = land.plants.consume(p, LifeForm.HERB, KgKm2(change / SMALL_EFFICIENCY).perM2)
            change = (taken * SMALL_EFFICIENCY).perKm2.v
            ateM2(p, taken.v)
        }
        if (counted && change > 0f) grew(p, change)
        val crowded = if (change < 0f) min(x - dead, -change) else 0f
        if (counted) smallDead[p] += dead + crowded
        if (grown != null) grown[p] += max(0f, change)
        if (died != null) died[p] += dead
        if (crowdedTo != null) crowdedTo[p] += crowded
        if (born != null) newborn[born][p] += max(0f, change)
        return max(0f, x - dead + max(0f, change) - crowded)
    }

    /** [x] held to [most], what is beyond it dying. */
    internal fun capped(p: Place, x: Float, most: Float): Float {
        if (x > most) { smallDead[p] += x - most; return most }
        return max(0f, x)
    }

    /**
     * The young of the small vertebrates still in the nest or hiding where they were born, kg/km², of
     * rodents, hares, hyrax, birds, and reptiles and amphibians ([Nest]): what this week's
     * births add, held [Nest.days] days, never more than the kind's stock. They cannot run, fly or
     * hide, so any hunter that finds them can take them; see [reach].
     */
    val young = Array(Nest.entries.size) { FloatArray(n) }
    /** Births of the kinds bred by [logistic] this week, for [nest]. */
    internal val newborn = Array(Nest.entries.size) { FloatArray(n) }

    /**
     * Each kind of [BIRDS] since cleared, kg/km² (for checks): 0 grown, 1 died (more when short of
     * insects), 2 died of crowding, 3 left (migrants, and insect-eaters leaving a failed place), 4 came
     * back, 5 burnt; 6 days fed (weighted by how well), 7 days out eating insects.
     */
    val birdLedger = Array(BIRDS.size) { enumTables<BirdLedger>(n) }
    fun birdCapacityAt(k: Int, p: Place) = birdCapacity(k, p)
    /** Grubs dug since cleared, kg/km² (for checks). */
    val grubsDug = FloatArray(n)

    /**
     * What happened to the reptiles and amphibians of each place since the ledger was last cleared,
     * kg/km² (for checks): 0 born, 1 died out, 2 died of hunger, 3 died lying up, 4 taken by hunters,
     * 5 insects they ate, 6 insects they needed, 7 insects the birds ate. (Hunters at the same place
     * in parallel may undercount 4 a little.)
     */
    val herpLedger = enumTables<HerpLedger>(n)
    /**
     * Reptiles and amphibians taken by hunters since the ledger was cleared, kg/km², by who took
     * them: the resident kind of that index, or (at [Taker.GROUPS]) the hunting packs and
     * people.
     */
    val herpTakenBy = Array(Taker.KINDS_AND_GROUPS) { FloatArray(n) }
    /**
     * The same for tortoises (for checks), kg/km² unless said: 0 grown, 1 died, 2 died of crowding, 3 taken by
     * hunters, 4 burnt; 5 days out in the breeding months, 6 days out; and taken by who, as [herpTakenBy].
     */
    val tortLedger = enumTables<TortLedger>(n)
    val tortTakenBy = Array(Taker.KINDS_AND_GROUPS) { FloatArray(n) }
    /**
     * Rodents, hares, hyrax and birds (0..3) taken since cleared, kg/km², by who took them: as [herpTakenBy],
     * and foxes and wildcats ([Taker.FOXES]), eagles ([Taker.EAGLES]), owls ([Taker.OWLS]). For checks.
     */
    val preyTakenBy = Array(Prey.entries.size) { Array(Taker.ALL) { FloatArray(n) } }
    @delegate:Transient internal val huntedAll by lazy { (listOf(rodents, hares, hyrax, locusts, herps, tortoises, insects, pollinators) + bird).toTypedArray() }
    @delegate:Transient internal val foxPrey by lazy { (listOf(rodents, hares, locusts, insects, pollinators) + bird).toTypedArray() }
    /** What reptiles, amphibians and insect-eating birds eat: the insects that are out. */
    @delegate:Transient internal val insectFood by lazy { arrayOf(insects, pollinators, locusts, biting, soilAnimals) }
    /** How much of the soil animals ground feeders reach this week, by place (see [soilAnimals]). */
    internal val soilReach = FloatArray(n)
    internal val insectEase = ThreadLocal.withInitial { floatArrayOf(1f, 1f, 1f, 1f, 0f) }
    /** [harassment] this week, worked out once a step for all who ask. */
    internal val harass = FloatArray(n)
    @delegate:Transient internal val eaglePrey by lazy { arrayOf(hyrax, hares, bird[CHUKAR_AT], bird[ROCK_DOVE_AT]) }

    /** The pools of dead matter on the ground: what dies, drops or falls anywhere in the model comes to them through [deposit]. */
    enum class DeadPool { CARRION, LITTER, DUNG }
    /** Lays [kgM2] of dead matter into [pool] at place [p]. */
    fun deposit(pool: DeadPool, p: Place, kgM2: KgM2) {
        when (pool) { DeadPool.CARRION -> carrion[p] += kgM2.v; DeadPool.LITTER -> litter[p] += kgM2.v; DeadPool.DUNG -> dung[p] += kgM2.v }
    }
    /** Lays [kg] of the buried dead into the ground at place [p]: they rot into the soil's organic matter, out of the scavengers' reach. */
    fun bury(p: Place, kg: Kg) { organicMatter[p] += land.spread(p, kg).v }
    /** Lays [kg] of dead matter into [pool] at place [p], spread over the place. */
    fun deposit(pool: DeadPool, p: Place, kg: Kg) = deposit(pool, p, land.spread(p, kg))

    /** Totals over the land, tonnes, and vultures in all colonies. */
    fun summary(): String {
        fun t(a: FloatArray) = a.indices.sumOf { (a[it] * land.areaKm2[it]).toDouble() } / 1000
        return "rodents %.0f t, hares %.0f t, hyrax %.0f t, birds %.0f t (%s), tortoises %.0f t, foxes %.0f t, owls %.0f t, eagles %.0f pairs, %d vulture colonies %.0f birds".format(
            t(rodents), t(hares), t(hyrax), t(birds), bird.indices.joinToString(", ") { "%s %.0f".format(BIRDS[it].name, t(bird[it])) },
            t(tortoises), t(foxes), t(owls), eagles.indices.sumOf { (eagles[it] * land.areaKm2[it]).toDouble() } / EAGLE_KM2, vultures.size, vultures.sum())
    }

    /** Names the soil's and the small animals' stocks. */
    internal fun listStocks(stocks: Stocks) {
        fun perM2(name: StockName, a: FloatArray) = stocks.perM2(name, a, land.areaM2)
        fun perKm2(name: StockName, a: FloatArray) = stocks.perKm2(name, a, land.areaKm2)
        perM2(StockName.LITTER, litter); perM2(StockName.SOIL_ORGANIC_MATTER, organicMatter); perM2(StockName.CARRION, carrion); perM2(StockName.CARRION_FOUND_BY_GROUPS, groupCarrion); perM2(StockName.CARRION_FOUND_BY_RESIDENTS, residentCarrion); perM2(StockName.BONES, bones); perM2(StockName.DUNG, dung)
        perKm2(StockName.SMALL_DEAD, smallDead); perKm2(StockName.SOIL_ANIMALS, soilAnimals)
        // (The small animals on the land, as against those in the soil and the migrants away.)
        stocks.within(StockName.SMALL_ANIMALS) {
            perKm2(StockName.INSECTS, insects); perKm2(StockName.INSECTS_WAITING, dormantInsects)
            perKm2(StockName.BITING_INSECTS, biting); perKm2(StockName.BITING_INSECTS_WAITING, dormantBiting); perKm2(StockName.RODENTS, rodents); perKm2(StockName.REPTILES_AND_AMPHIBIANS, herps)
            perKm2(StockName.POLLINATORS, pollinators); perKm2(StockName.POLLINATORS_WAITING, dormantPollinators); perKm2(StockName.HARES, hares); perKm2(StockName.HYRAX, hyrax)
            perKm2(StockName.TORTOISES, tortoises); perKm2(StockName.FOXES, foxes); perKm2(StockName.OWLS, owls); perKm2(StockName.LOCUSTS, locusts)
            for (k in BIRDS.indices) stocks.perKm2(BIRDS[k].name, bird[k], land.areaKm2)
        }
        stocks.add(Books.Stuff.MATTER, StockName.MIGRANT_BIRDS_AWAY, away, waiting) { away.sum() + waiting.sum() }
    }

    @delegate:Transient internal val spreaders by lazy {
        (listOf(insects, herps, pollinators, biting, hyrax, tortoises, foxes) + bird).toTypedArray()
    }

    /**
     * What each place holds for rodents and for hares wandering in, heads per km²: the room its food gives them, less the
     * share the week's cold, deep snow and fire kill there ([spreadToward]).
     */
    @delegate:Transient internal val rodentRoom by lazy { FloatArray(n) }
    @delegate:Transient internal val hareRoom by lazy { FloatArray(n) }

    /**
     * The share of the difference with each neighbour that crosses in a step: [SPREAD] a month, over
     * the larger place's count of neighbours and scaled to the smaller place's area. (The same flow
     * both ways between two places, so what one gains the other loses.)
     */
    @Transient internal val spreadWeight = Array(n) { p ->
        val nb = land.neighbours[p]
        val rate = SPREAD * days / Date.DAYS_PER_MONTH
        FloatArray(nb.size) { i -> val q = nb[i]; rate / max(nb.size, land.neighbours[q].size) * min(land.areaKm2[p], land.areaKm2[q]) / land.areaKm2[p] }
    }

    companion object {
        val WETLANDS = EnumSet.of(Biome.MARSH, Biome.SWAMP_FOREST, Biome.BOG, Biome.SALT_MARSH, Biome.MANGROVE)
        /** Share of rodents and of hares a fire kills where it burns all of a place (the rest bolt). Not measured. */
        const val RODENTS_BURNT = 0.5f
        const val HARES_BURNT = 0.3f
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
        /** Share of what animals eat that comes back as dung. */
        const val DUNG = 0.5f
        internal val CHUKAR_AT = BIRDS.indexOf(CHUKAR)
        internal val ROCK_DOVE_AT = BIRDS.indexOf(ROCK_DOVE)
    }
}

/** Slots of [HexSmallLife.herpLedger]. */
enum class HerpLedger { BORN, DIED_OUT, HUNGER, LYING_UP, HUNTED, ATE, NEEDED, EATEN_BY_BIRDS }

/** Slots of [HexSmallLife.tortLedger]. */
enum class TortLedger { GROWN, DIED, CROWDED, HUNTED, BURNT, BREEDING_DAYS, DAYS_OUT }

/** Slots of each kind's [HexSmallLife.birdLedger]. */
enum class BirdLedger { GROWN, DIED, CROWDED, LEFT, BACK, BURNT, FED_DAYS, INSECT_DAYS }

/** The small prey of [HexSmallLife.preyTakenBy]. */
enum class Prey { RODENTS, HARES, HYRAX, BIRDS }

/** Prey [p]'s entry of a table kept per [Prey]. */
@Suppress("NOTHING_TO_INLINE") inline operator fun <T> Array<T>.get(p: Prey) = this[p.ordinal]

/** Ways of hunting small prey, for [HexSmallLife.reach]: stalking and pouncing beasts, rooting beasts, people. */
enum class SmallHunt { STALK, ROOT, PEOPLE }

/** The small prey a hunter meets, as [HexSmallLife.reach] and [HexSmallLife.catchSmall] take them (in the order of their huntedAll; birds all as one). */
enum class SmallPrey { RODENTS, HARES, HYRAX, LOCUSTS, HERPS, TORTOISES, INSECTS, POLLINATORS, BIRDS }

/**
 * The kinds whose [HexSmallLife.young] are kept: rodents, hares, hyrax, birds, reptiles and amphibians, tortoises;
 * each with the [days] its young stay where they were born, unable to flee: mouse and vole pups some three weeks in
 * the nest, leverets four lying up in their forms, hyrax young a month by the rocks, chicks a month from laying to
 * fledging, the eggs and hatchlings of reptiles and amphibians some two months, and young tortoises their first
 * year, while the shell is soft (grown ones beasts rarely kill: Sci. Rep. 2022, doi:10.1038/s41598-022-22288-9).
 */
enum class Nest(val days: Float) { RODENTS(21f), HARES(28f), HYRAX(30f), BIRDS(30f), HERPS(60f), TORTOISES(360f) }

/** Nest kind [n]'s row of a table kept per [Nest]. */
@Suppress("NOTHING_TO_INLINE") inline operator fun Array<FloatArray>.get(n: Nest) = this[n.ordinal]
