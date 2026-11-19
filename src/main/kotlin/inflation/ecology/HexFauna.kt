package inflation.ecology

import inflation.world.StepClock
import inflation.drawUnit
import inflation.Par
import inflation.Part
import inflation.EnumFloats
import inflation.enumFloats
import inflation.Profile
import inflation.Stream
import inflation.stream
import inflation.world.Phase
import inflation.world.Hex
import inflation.world.HexArray
import inflation.world.toHexArray
import inflation.world.Place
import inflation.world.get
import inflation.world.set

import inflation.KindStock
import inflation.StockName
import inflation.ecology.HexSmallLife.DeadPool
import inflation.Tuning
import inflation.wrapMonth
import inflation.saturate
import inflation.monthsFrom
import inflation.Books
import inflation.Stocks
import inflation.Config
import inflation.Kg
import inflation.KgKm2
import inflation.KgM2
import inflation.min

import inflation.world.Date
import inflation.world.HexLand
import inflation.world.HexMap
import inflation.world.HexWorld
import inflation.world.LifeForm
import inflation.world.Weather
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.DoubleAdder

/**
 * The resident plant-eaters on the hexes, as densities: adults and young per km² of each place
 * (a hex, or its low or high ground). Each week, in each place:
 *
 * - The animals of every kind graze and browse the place's plants, sharing them by what each wants
 *   of grass, browse and fruit; snow deeper than a kind can dig through hides the grass.
 * - Young are born in the season, and grow up over the years to maturity.
 * - Animals die of age and accident, faster when hungry, in cold beyond what their coat allows,
 *   and the young most.
 * - Within a hex they shift between its low and high ground towards better feeding and kinder weather,
 *   up the mountain in summer and down in winter.
 * - When hungry or crowded, some spill over into the places around, over the hex borders as their
 *   way of travelling allows (climbers over crags, swimmers across rivers), towards better feeding.
 *
 * They stay in their country: nothing here wanders far, but over the years a kind spreads into
 * empty ground that suits it, and withdraws from ground that fails it.
 */
class HexFauna(internal val hw: HexWorld, internal val land: HexLand, @Transient internal val clock: StepClock, @Transient internal val stats: WeekStats) {
    /** Days the step covers ([StepClock]). */
    internal val days get() = clock.days
    internal val tuning get() = land.tuning
    internal val hex = hw.hex
    val n = land.n
    /**
     * Open water's temperature at each place this week, °C: it follows the air slowly (see
     * [WATER_LAG_DAYS]) and freezes at 0. NaN until the first week (and in worlds saved before it was
     * kept), when it starts from its normal for the month.
     */
    val waterC = FloatArray(n) { Float.NaN }
    /** [waterC]'s normal for each month and place, from the month's normal air temperatures. */
    @delegate:Transient internal val waterNormalC by lazy {
        val byPlace = Array(n) { p -> laggedNormals { m -> hw.placeTemp(Place(p), m) } }
        Array(12) { m -> FloatArray(n) { p -> byPlace[p][m] } }
    }

    /**
     * The resident kinds ([Catalogue.resident]), and for each, adults and young per km² of each place: those that keep to
     * small home ranges first, then the [Catalogue.migratory] plant-eaters, then the [Catalogue.roamingResidents] (the order
     * saved worlds keep their tables in: kinds added at the end load into older saves, starting as a fresh model has them).
     */
    @Transient val kinds = Catalogue.all.filter { Catalogue.resident(it) && it !in Catalogue.migratory && it !in Catalogue.roamingResidents } +
        Catalogue.all.filter { it in Catalogue.migratory } + Catalogue.all.filter { it in Catalogue.roamingResidents }
    /**
     * How many of [kinds], the first, a band may keep as its totem ([Band.totem]): all but the [Catalogue.roamingResidents],
     * which as packs never could be one. (Whether they may once they live settled too is for when they do.)
     */
    @Transient val totemKinds = kinds.count { it !in Catalogue.roamingResidents }
    /** Each kind's place in [kinds] (and in every table here kept by kind), by its [Species.index]; -1 for a kind kept in groups. */
    @Transient internal val slots = IntArray(Catalogue.all.size) { -1 }.also { for ((k, s) in kinds.withKinds()) it[s.index] = k.i }
    /** [s]'s place in [kinds], or -1 if it is not a resident. */
    fun slot(s: Species) = FaunaKind(slots[s.index])
    internal val kindIds get() = KindRange(kinds.size)

    val adults = Array(kinds.size) { FloatArray(n) }
    val young = Array(kinds.size) { FloatArray(n) }
    /** The resident kinds by body mass, the biggest first: the order they get at food that is short. */
    @Transient internal val byMass = kinds.indices.sortedByDescending { kinds[it].massKg }.toIntArray()
    /** Last week's share of its wants each kind found in each place, 0..1. */
    val fed = Array(kinds.size) { FloatArray(n) { 1f } }
    /**
     * How well each kind's diet in each place met its need for protein over the last weeks, 0..1 (see
     * [proteinMet]): what mothers make milk of and the young grow on.
     */
    val protein = Array(kinds.size) { FloatArray(n) { 1f } }
    /**
     * For the check: where each kind's food came from, summed over its animals since last read (fresh
     * grass, browse, fruit and roots; small prey and carrion; its reserves), and the
     * animals counted, so that each over the last is the share of a full need met that way: indexed by
     * [Fed.PLANTS], [Fed.ANIMAL], [Fed.RESERVES] and [Fed.HEADS]; and of the plants, the fruit ([Fed.FRUIT]) and the roots
     * and fallen mast dug or picked up ([Fed.UNDERGROUND]).
     */
    @Transient val fedFrom = Array(kinds.size) { Array(Fed.entries.size) { DoubleAdder() } }
    /**
     * For the check, since last read, counted on the weeks the residents move ([Tuning.residentMoveWeeks]): each kind's
     * resident heads summed over those weeks ([Stuck.HEADS]); those hungry (fed under [STUCK_FED]) with no neighbouring
     * place the spill could take them to that is better ([Stuck.STUCK]); of those, the ones where some border is closed to
     * them, by sea or ground they cannot cross ([Stuck.AT_EDGE]); and the most of a kind so held at any one move, as a
     * share ([Stuck.PEAK]). Where gradual movement piles animals up.
     */
    @Transient val stuck = Array(kinds.size) { DoubleArray(Stuck.entries.size) }
    /**
     * For the check, since last read: each resident kind's heads that moved by the flow ([flow]) times the height they
     * climbed (m, down negative), by month. Where the moving itself takes a kind up or down, apart from births and deaths by height.
     */
    @Transient val climbed = Array(kinds.size) { DoubleArray(12) }
    /**
     * Fear in each place: kg of game killed there lately per km², fading over a few weeks. Game keeps
     * away from ground where its kind has been killed (the landscape of fear).
     */
    val fear = FloatArray(n)
    /**
     * Each kind's usual density, per km²: as the catalogue gives it, else by Damuth's rule from its
     * body mass (8/km² for roe deer, 0.4 for hippos), so that crowding is reckoned against what is
     * crowded for its size, not one density for every kind.
     */
    // (Of a kind that also roams, the territories its packs hold: one of its usual size a hex, as their marks keep them
    // ([HexGroups.mark]), so its settled are held where its packs were.)
    @Transient internal val usual = FloatArray(kinds.size) { k -> val s = kinds[k]
        if (s in Catalogue.roamingResidents) tuning.roamerUsual * s.groupSize / hex.areaKm2.toFloat() else usualDensity(s) }
    /**
     * How far each kind's sickness of crowding is bred by each other kind's numbers ([sharersKm2]), by kind index: the
     * kinds that share its parasites ([Species.kin]) as far as their diets overlap (the share of each's food that is of
     * the same sorts, grass, browse, fruit and roots: they graze the same ground and pick up each other's worms), at
     * [SHARED_SICK]; its own kind fully. Crowding is of all who feed and sicken alike, not of one kind as if alone.
     */
    @Transient internal val sharers: Array<FloatArray> = Array(kinds.size) { k -> FloatArray(kinds.size) { j ->
        val a = kinds[k]; val b = kinds[j]
        if (j == k) 1f else if (a.kin == Kin.NONE || a.kin != b.kin) 0f else SHARED_SICK * dietOverlap(a.diet, b.diet) } }
    /**
     * How damp each place's ground has been over the last months, 0..1 ([wetSickness]): the share of it sodden
     * ([stepDamp]), a mean of some half a year. NaN until the first step reckons it.
     */
    val damp = FloatArray(n) { Float.NaN }
    /**
     * Each kind's crowding by the others that share its parasites at each place, heads per km² as so many of its own
     * ([sharersKm2]): reckoned once a step before births and deaths ([reckonShared]), from the numbers as they were then,
     * so that the kinds, each on its own core, read the same crowd whatever order they run in.
     */
    @Transient internal val shared = Array(kinds.size) { FloatArray(n) }
    /**
     * For each resident hunter that lives mainly on game it kills ([holdsByPrey]), the prey of its size at each place, kg
     * per km² ([preyBiomass]), a mean of some half a year ([GAME_WEEK]); empty for every other kind. NaN until the first step.
     */
    val gameMean: Array<FloatArray> = Array(kinds.size) { k -> if (holdsByPrey(kinds[k])) FloatArray(n) { Float.NaN } else FloatArray(0) }
    /**
     * For each plant-eater (each kind not holding ground by its prey, [gameMean], nor a hunter), its food at each place,
     * kg/m² as [foodFor] has it, a mean of about a year ([USUAL_FOOD_WEEK]); empty for every other kind. NaN until the
     * first step. What its usual numbers at a place go by ([usualAt]).
     */
    val foodMean: Array<FloatArray> = Array(kinds.size) { k -> if (!kinds[k].carnivore && !holdsByPrey(kinds[k])) FloatArray(n) { Float.NaN } else FloatArray(0) }
    /**
     * Each plant-eater's [foodMean] over the country it can live in, by its area, climate and water ([foodRefWeights]): where it has its [usual] numbers.
     * 0 until reckoned, and for every other kind. (Saved: reckoned at a step's start from the means then, so a run carried
     * on from a save goes on as it would have.)
     */
    val foodRef = FloatArray(kinds.size)
    /**
     * [foodRef]'s weights by kind and place: [startWeight] without the cover it likes ([suits]), which changes as the plants
     * grow; of the fixed facts of the place alone (area, climate, water), so the same whenever reckoned, and after a reload.
     */
    @Transient private var foodRefWeights: Array<FloatArray>? = null
    /**
     * The density at place [p] at which kind [k] holds all the ground there is, heads per km²: for a hunter that lives
     * mainly on game it kills, as many as the prey of its size there lately holds at [PREY_SUPPORT] of its weight in
     * hunters (territories shrink where game is rich and spread where it is thin: wolves' numbers follow their prey's
     * biomass, Fuller 1989, lions' theirs, Hayward et al. 2007); for every other kind its [usual] density, the same everywhere.
     */
    internal fun usualAt(k: Int, p: Place): Float {
        val g = gameMean[k]
        if (g.isEmpty()) return usual[k] * foodScale(k, p) * hidingShare(k, p)
        val m = g[p.i]
        return if (m.isNaN()) usual[k] else max(TERRITORY_LEAST, PREY_SUPPORT * m / kinds[k].massKg)
    }
    /**
     * How many times its [usual] numbers place [p] holds of plant-eater [k]: its food there ([foodMean]) against its
     * food over the country it lives in ([foodRef]), to the power [Tuning.usualByFood], within [USUAL_FOOD_LEAST] and
     * [USUAL_FOOD_MOST]. Damuth's densities are means over the country a kind lives in, rich and poor ground together;
     * here rich ground holds more and poor less, as many over all. 1 for every other kind, and until the means are reckoned.
     */
    internal fun foodScale(k: Int, p: Place): Float {
        val power = tuning.usualByFood; val m = foodMean[k]
        if (power <= 0f || m.isEmpty()) return 1f
        val f = m[p.i]; val r = foodRef[k]
        if (f.isNaN() || !(r > 0f)) return 1f
        return (f / r).pow(power).coerceIn(USUAL_FOOD_LEAST, USUAL_FOOD_MOST)
    }
    /**
     * The share of place [p] that plant-eater [k] can live on, if it is one that hides in thickets ([Niches.Shelter.THICKET]):
     * the ground its shelter covers there ([preyShelter]: the scrub and woods, and the reeds and water for those that take
     * to them), as far as [Tuning.coverReach] times it, at least [HIDING_LEAST]. A place's cover is a mean over its ground:
     * at a third, a boar, a deer or an elk lies up in the third that is thicket and feeds out from it, and the open rest
     * is not its country; counted as all of it, the boar held the steppe as thick as the oak woods (cover 0.56 where they
     * stood on the steppe, shrubs 1 kg/m²). 1 for every other kind.
     */
    internal fun hidingShare(k: Int, p: Place): Float {
        val reach = tuning.coverReach; val s = kinds[k]
        if (reach <= 0f || s.carnivore || Niches.Shelter.THICKET !in s.shelters) return 1f
        return (reach * preyShelter(s, p)).coerceIn(HIDING_LEAST, 1f)
    }
    /** Reckons [foodRef] from [foodMean] ([foodScale]). */
    internal fun reckonFoodRef() {
        val w = foodRefWeights ?: Array(kinds.size) { k -> if (foodMean[k].isEmpty()) FloatArray(0) else FloatArray(n) { p -> val pl = Place(p); land.areaKm2[p] * climateSuits(kinds[k], pl) * usualTether(kinds[k], pl) } }
            .also { foodRefWeights = it }
        for (k in kinds.indices) {
            val m = foodMean[k]; val wk = w[k]
            if (m.isEmpty()) continue
            var sw = 0.0; var swf = 0.0
            for (p in 0 until n) { val f = m[p]; if (f.isNaN()) continue; sw += wk[p]; swf += wk[p] * f }
            foodRef[k] = if (sw > 0.0) (swf / sw).toFloat() else 0f
        }
    }
    /** The sodium in each place's forage, a share of its dry matter, or [LICK] by a salt lick ([forageSodium], [saltBreeding]). */
    @Transient internal val forageSodiumAt = forageSodium(hw, land)
    /**
     * Outbreaks (rinderpest-like plagues among grazers, distemper and rabies among hunters, fevers
     * among people), by kind ([Species.index]) and hex: how strongly one is raging, 0..1. One starts
     * where a kind is crowded, passes to neighbouring hexes where the kind lives, and burns out over
     * some weeks; while it rages it kills.
     */
    val outbreak = Array(Catalogue.all.size) { FloatArray(hex.size) }
    /** Heads of each kind living in groups, by hex, this week (set by the groups) for outbreaks to pass on. */
    val groupHeads = Array(Catalogue.all.size) { IntArray(hex.size) }
    /** Starts an outbreak of [s] in hex [h] (a shared array: concurrent starts all set the same value). */
    fun startOutbreak(s: Species, h: Hex) { outbreak[s.index][h] = 1f }
    /** The share of [s] that an outbreak kills at place [p] over the step: [OUTBREAK_KILL] a tick of the outbreak there, over the step's outbreak ([outbreakSum]). */
    fun outbreakDeaths(s: Species, p: Place): Float {
        val h = hw.placeHex[p]
        val sum = outbreakSum ?: return OUTBREAK_KILL * outbreak[s.index][h]
        return 1f - exp(-OUTBREAK_KILL * sum[s.index][h])
    }
    /** Each kind's outbreak at each hex summed over the step's days, in ticks of it ([stepOutbreaks]); null before the first step. */
    @Transient private var outbreakSum: Array<FloatArray>? = null

    private var outbreakSalt = 0L
    /** A number in 0..1 that changes from week to week, for place [p] and kind [k]. */
    internal fun hash(p: Place, k: FaunaKind): Float = drawUnit(land.seed, Stream.OUTBREAKS, p.i.toLong(), outbreakSalt, k.i.toLong())

    /**
     * Outbreaks pass to neighbouring hexes where the kind lives, and burn out, over the step's days (plan section 52.A.2).
     * An outbreak fades to [OUTBREAK_KEEP] of itself a tick, continuously; its front passes one hex a tick into hexes where
     * the kind is, at [OUTBREAK_SPREAD] of the strength it set out with, while what it passes from is still at
     * [OUTBREAK_PASSES] or more. So each hex's outbreak is its strongest arrival, fading from the tick it came: worked out
     * front by front (it dies out within a few hexes whatever the step's length), not tick by tick, and summed over the
     * step for the deaths ([outbreakDeaths]).
     */
    @Part("outbreaks")
    private fun stepOutbreaks() {
        val span = days.toFloat() / clock.tickDays
        val sums = outbreakSum ?: Array(Catalogue.all.size) { FloatArray(hex.size) }.also { outbreakSum = it }
        val lnKeep = ln(OUTBREAK_KEEP)
        // (The integral of KEEP^t from t0 to t1, in ticks.)
        fun faded(t0: Float, t1: Float) = (OUTBREAK_KEEP.pow(t1) - OUTBREAK_KEEP.pow(t0)) / lnKeep
        for ((si, s) in Catalogue.all.withIndex()) {
            val o = outbreak[si]; val sum = sums[si]
            if (o.all { it < 0.01f }) { for (h in hex.all) sum[h] = o[h] * span; continue }
            val k = slot(s)
            // (Each hex's strength as of the step's start, faded from there: KEEP^t of it at t ticks in; and the tick it came.)
            val base = o.copyOf(); val came = IntArray(hex.size)
            var front: List<Hex> = hex.all.filter { o[it] >= OUTBREAK_PASSES }
            var tick = 0
            while (front.isNotEmpty() && tick + 1 <= span) {
                val next = ArrayList<Hex>()
                for (h in front) {
                    if (base[h] * OUTBREAK_KEEP.pow(tick) < OUTBREAK_PASSES) continue
                    for (d in 0 until 6) {
                        val q = hex.neighbour(h, d); if (q.none) continue
                        val here = groupHeads[si][q] > 0 ||
                            (!k.none && hw.placesOf(q).any { p -> adults[k][p] + young[k][p] > 0.01f })
                        // (Set out at SPREAD of what it passes from then, it fades with the rest from that tick: as a strength at the step's start, SPREAD of its source's.)
                        val arrives = min(OUTBREAK_KEEP.pow(-tick.toFloat()), OUTBREAK_SPREAD * base[h])
                        if (here && arrives > base[q]) { base[q] = arrives; came[q.i] = tick + 1; next += q }
                    }
                }
                front = next; tick++
            }
            // (The step's fading worked out once, not a hex at a time: prof761, a serial loop of pows.)
            val whole = faded(0f, span); val keepSpan = OUTBREAK_KEEP.pow(span)
            for (h in hex.all) {
                val c = came[h.i].toFloat()
                sum[h] = if (c <= 0f) base[h] * whole else o[h] * faded(0f, c) + base[h] * faded(c, span)
                o[h] = min(1f, base[h] * keepSpan)
            }
        }
    }
    /** The food left uneaten at each place after every kind has eaten this week, kg per m², as far as the eaters could reach it: grass, browse, fruit and roots ([roomFor]). */
    @Transient internal val spareGrass = FloatArray(n)
    @Transient internal val spareBrowse = FloatArray(n)
    @Transient internal val spareFruit = FloatArray(n)
    @Transient internal val spareRoots = FloatArray(n)

    /**
     * Each resident hunter kind's share of those coming to each place of a split hex that would die there in a week
     * ([arriveOdds], NaN where it cannot stay), worked out before the residents move ([fillArrive]): what they shift
     * between low and high ground by ([shiftBands]).
     */
    @Transient internal val arrive = Array(kinds.size) { FloatArray(n) }
    /** Whether each place is one of the two of a split hex ([arrive]). */
    @delegate:Transient internal val splitPlace by lazy { BooleanArray(n) { p -> val h = hw.placeHex[p]; hw.placeIn(HexWorld.LOW, h) != hw.placeIn(HexWorld.HIGH, h) } }
    /**
     * What each place is worth to each kind for moving there, worked out in the weeks the residents move: for the
     * plant-eaters, the rate those there would grow at over a year by the rules they are born and die by, as their [flow]
     * left them ([placeWorth]); for the resident hunters, its [appeal] ([fillArrive]). Never below nothing nor past
     * reckoning ([worthOrNothing]). Saved: the dispersers' drift reads it in the weeks between ([drift]).
     */
    internal val worth = Array(kinds.size) { FloatArray(n) }
    /** Of each plant-eating kind as it moves this week ([fillArrive]): its young to a grown one, and the fat days its grown carry, all over. */
    @Transient internal val moverYoung = FloatArray(kinds.size)
    @Transient internal val moverFat = FloatArray(kinds.size)
    @Transient internal val moverSpent = FloatArray(kinds.size)
    /** Animals of each kind killed this week by hunters among the residents (lynx, jackals), for the deaths by cause. */
    internal val huntedByResidents = Array(kinds.size) { FloatArray(n) }
    /** Resident hunters of each kind killed this week by the game they hunted ([huntResidents], [foughtBack]), heads; not saved. */
    @delegate:Transient internal val foughtBackDead by lazy { Array(kinds.size) { FloatArray(n) } }
    /**
     * What each resident hunter took this week at each place of the game about it ([huntResidents]): the kg it killed per kg
     * of game there, each kind's weighed by how readily it takes game of its size. An animal there
     * is killed at that rate times the hunter's [Species.preySize] for it. Null for kinds that eat no meat.
     */
    @delegate:Transient internal val killRate by lazy { Array(kinds.size) { if (kinds[it].diet.meat > 0f) FloatArray(n) else null } }

    /** Heads of each kind's residents each resident hunter kind killed since last read, hunter by prey, grown (even) and young (odd) ([huntResidents]): for the fauna check's report. */
    @delegate:Transient internal val killsByHunter by lazy { Array(2 * kinds.size * kinds.size) { java.util.concurrent.atomic.DoubleAdder() } }
    /**
     * For the fauna check, each kind's since last read: heads that died of a raging outbreak (of those booked as disease)
     * (0); heads alive at the step's deaths (1); and those heads times their crowding squared (2), as [breedAndDie] went by it.
     */
    @delegate:Transient internal val sickBook by lazy { Array(3 * kinds.size) { java.util.concurrent.atomic.DoubleAdder() } }

    /**
     * The share of each kind's residents at each place the hunting parties (packs and bands) killed in their last week's
     * foraging ([HexGroups.hunt]; young and grown alike, as they take them as they come on them), set as they kill.
     */
    val groupKilled = Array(kinds.size) { FloatArray(n) }

    /**
     * Each plant-eating kind's odds of being hunted in a week at each place lately, its grown and its young ([huntedOdds]),
     * fading by [FEAR_KEEP] a week ([noteHunted]): what it weighs places by as it flows ([fix]). Weighing them by one
     * week's kills compounded over a year, a place where a pride took 5% in a week looked like 7% surviving the year, and
     * the kind half left it in one move, and the hexes about it in rings (Olli 2026-11-11: out of proportion).
     */
    val huntedLately = Array(kinds.size) { FloatArray(n) }
    val huntedYoungLately = Array(kinds.size) { FloatArray(n) }

    /**
     * The strongest hunting party (pack or band) standing in each place at the start of the groups' last week
     * ([GroupPlanner.takeStock] fills it; saved, so that a loaded world goes on as it would have). The
     * [Catalogue.residentRivals] keep out of its way, as packs keep out of a much stronger party's.
     */
    val strongest = FloatArray(n)

    /**
     * The bands camped in each place with kept herds, by place, as the week starts ([HexGroups.herdsForBeasts] fills it before
     * the residents' week): the resident hunters take from their herds as from game ([huntResidents]). Not saved: made afresh
     * each week from the bands.
     */
    @Transient internal var keptAt: Array<out List<Band>?>? = null

    /**
     * The grown of each kind that also roams ([Catalogue.roamingResidents]) in packs standing at each place, per km²: game
     * as their settled kind is, to every hunter ([HexGroups.fillPackGame] fills it before the residents' week and again as
     * the groups hunt); null for the other kinds. Not saved.
     */
    @Transient internal val packGrown: Array<FloatArray?> = Array(kinds.size) { k -> if (kinds[k] in Catalogue.roamingResidents) FloatArray(n) else null }
    /** [packGrown] of kind [k] at place [p], per km². */
    internal fun packAt(k: Int, p: Place): Float = packGrown[k]?.get(p.i) ?: 0f
    /** Pack members killed as game, heads, to be taken off the packs that stood there ([HexGroups.takePackKills]). Not saved. */
    @delegate:Transient internal val packKills by lazy { java.util.concurrent.ConcurrentLinkedQueue<PackKill>() }
    /**
     * Diagnostic (huntSteps): heads the resident hunters took this week at places in [keptAt], by prey kind: the kept young,
     * the kept grown, the wild young and the wild grown there ([HexGroups.herdsForBeasts] hands them to the hunt steps).
     */
    @delegate:Transient internal val campTaken by lazy { Array(kinds.size) { Array(4) { DoubleAdder() } } }
    /**
     * Heads of bands' kept herds taken since the last census, by the beast that took them: resident hunters ([huntResidents])
     * and packs ([HexGroups.raidHerd]) alike. For the census only; it changes nothing in the world.
     */
    @delegate:Transient internal val keptTakenBy by lazy { ConcurrentHashMap<Species, DoubleAdder>() }

    /**
     * For the books (see [Books]), this step, kg; null when they are not kept: what the residents ate and dropped as
     * dung, the young born and grown, and the carcasses handed to the soil.
     */
    @Transient internal var ateBook: DoubleAdder? = null
    @Transient internal var droppedBook: DoubleAdder? = null

    /** The soil life and small animals the residents live among. */
    val small = HexSmallLife(hw, land, clock)
    /** Each kind's sense of where better country lies. */
    val bearings = HexBearings(hw, kinds, { s, p, m ->
        val food = fareFor(s, Place(p))
        saturate(food, Thresholds.FOOD_HALF) * tether(s, Place(p)) * unbitten(Place(p)) * monthFit(s, Place(p), m)
    }, { s -> val k = slot(s); (0 until n).any { adults[k][it] > 0f } }, land.tuning)
    /**
     * Each kind's month births begin in each place, which drifts year by year towards the months
     * that feed mothers and young best; how well fed it was in each month lately (place × 12 + month).
     */
    val birthStart = Array(kinds.size) { k -> ByteArray(n) { kinds[k].birthMonth.toByte() } }
    /**
     * Each kind's young's mean weight now, as a share of a grown one's ([youngShareIn]): light after the births, heavier as
     * they grow; brought on each step and booked as growth as it changes ([stepYoungShare]), so the young's weight in the
     * stocks always matches its flows. By the kind's own birth month, the same everywhere, so moving changes no weight.
     */
    val youngShare = FloatArray(kinds.size) { YOUNG_WEIGHT }
    /** A young of kind [k]'s weight now, kg ([youngShare]). */
    internal fun youngKg(k: Int) = youngShare[k] * kinds[k].massKg
    /** A young of [s]'s weight now, kg, for a kind kept as residents; the groups' fixed [YOUNG_WEIGHT] for any other. */
    internal fun youngKgOf(s: Species): Float { val k = slots[s.index]; return if (k >= 0) youngKg(k) else YOUNG_WEIGHT * s.massKg }
    internal val fedByMonth = Array(kinds.size) { FloatArray(n * 12) { 0.7f } }
    /**
     * The share of the year's season of births the mothers of each kind in each place have had (0..1): it goes with them
     * as they move, so a mother bears a year's young once, wherever she walks and whatever each place's calendar; it
     * starts again in the kind's [offSeason].
     */
    val bred = Array(kinds.size) { FloatArray(n) }
    /** Days of food each kind carries as fat in each place. */
    val reserve = Array(kinds.size) { k -> FloatArray(n) { 0.5f * kinds[k].maxFatDays } }
    /** For kinds that sleep through the winter: how well fed they went to sleep, 0..1. */
    internal val autumnFed = Array(kinds.size) { FloatArray(n) { 0.8f } }
    /** Days each kind has gone without water in each place, as a group keeps them (see [HexGroup.dry]): they mount week to week until it drinks. */
    val dry = Array(kinds.size) { FloatArray(n) }
    /** Days of their body's protein each kind's grown at each place have burnt, their fat spent ([Hazards.hunger]); won back before fat. */
    val spent = Array(kinds.size) { FloatArray(n) }
    /**
     * How bold of people each kind's grown at each place are, as a pack's [HexGroup.bold]: the kind's [Species.boldShare]
     * until they live by people's camps, where they grow bolder ([HexGroups.keepAnimals]); it goes with them as they move.
     */
    val bold = Array(kinds.size) { k -> FloatArray(n) { kinds[k].boldShare } }
    /**
     * Of each kind that also roams ([Catalogue.roamingResidents]), the grown young due to leave each hex as roamers, heads (null
     * for the others): grown up this week on crowded ground ([leaveShare]), still counted among the hex's grown until they go
     * ([HexGroups.roamersLeave] takes them off, whole ones, the fraction left for next week).
     */
    val leaving = Array(kinds.size) { k -> if (kinds[k] in Catalogue.roamingResidents) FloatArray(hex.size) else null }
    /** Of each kind that also roams, the grown young dispersing from each hex they left, heads ([disperse]); null till the first leave (and in saves made before it). */
    internal var dispersing: Array<FloatArray?>? = null
    /**
     * Of each resident hunter kind, its heads killed by people lately per km² by place,
     * fading at a rate a day ([persecutedBy], [pressure]); null till people first kill a hunter (and in saves made before it).
     */
    internal var persecuted: Array<FloatArray?>? = null
    /** People within a day's walk of each hex, per km², as the step began ([peopleAbout]). Not saved. */
    @Transient internal val peopleNear = FloatArray(hex.size)
    /** The ground about each hex its dispersers look over, by kind and hex ([dispersalAbout]): fixed by the map, worked out once and kept. Not saved. */
    @Transient internal val dispersalGround = java.util.concurrent.ConcurrentHashMap<Long, DispersalGround>()

    /**
     * The share of a kind's young growing up at a place where its kind stands at [heads] per km² that leave it as roamers:
     * [Tuning.roamerLeaveBase] of them wherever they are born (most young wolves leave the pack they were born in, and young male lions
     * their pride), and more as the ground is crowded beyond its usual numbers ([usual], the territories there are), up to
     * that and [LEAVE_CROWD] more at twice them, as young grown up leave a pack already as big as its kind's usual one ([liveWeek]).
     */
    internal fun leaveShare(heads: Float, usual: Float) = tuning.roamerLeaveBase + LEAVE_CROWD * (heads / usual - 1f).coerceIn(0f, 1f)
    /** What a grown animal carries with it as it moves ([carried]). */
    internal enum class Carry { BRED, FAT, SPENT, BOLD }
    /** Life in the sea, lakes and rivers. */
    val water = HexWater(hw, land, clock, small)
    /** Fruit and roots as stocks. */
    val food = HexFood(land, small, clock).also { f -> small.takeMast = { p, want -> f.takeMast(HexFood.MastFate.SMALL_LIFE, p, want) } }
    /** Carcasses left this week in each place, kg (deaths other than kills, which the hunters count). */
    val carcassKg = FloatArray(n)
    /** Deaths this week by cause, animals ([WeekStats.residentDeaths]). */
    val deaths get() = stats.residentDeaths
    /** Where things died and dead matter went, for the viewer; null (and not kept) unless something reads it. */
    @Transient var record: PlaceRecord? = null
        set(v) { field = v; small.record = v }

    internal val years get() = clock.years
    /** How each kind travels between places. */
    @Transient internal val travel = kinds.map { Catalogue.travel(it) }
    /** Young born to the residents this step (all its ticks: [HexSim.step] clears it), by kind. */
    @Transient val bornThisWeek = DoubleArray(kinds.size)

    init {
        // Start below what the land can carry, so that each kind grows into its country rather than
        // crashing out of it: half its usual density where its food grows, no denser where the ground
        // suits it best, and thinner where it is far from water or the climate is wrong for it.
        for ((k, s) in kinds.withKinds()) {
            val dens = if (s.startKm2PerAnimal > 0f) 1f / s.startKm2PerAnimal else 0.5f
            for (p in land.places) {
                // (Hunters among the residents live on prey found wherever there is life: food is no bar to them here.)
                val food = if (s.carnivore) FOOD_FULL else foodFor(s, p)
                adults[k][p] = START_SHARE * dens * min(1f, food / FOOD_FULL) * min(1f, suits(s, p)) * climateSuits(s, p) * usualTether(s, p)
                young[k][p] = 0.2f * adults[k][p]
            }
            // Where a long run showed where the kind settles, start at that many, spread the same way.
            val settled = Catalogue.settledPer1000Km2(s)
            if (settled != null) {
                val want = settled * land.areaKm2.sum() / 1000f
                val now = count(k).toFloat()
                if (now > 0f) { val f = want / now; for (p in land.places) { adults[k][p] *= f; young[k][p] *= f } }
            }
        }
    }

    /** Of the kinds that can hibernate, those asleep in their winter dens, by place (null for the kinds that cannot; see [sleepOrWake]). */
    val sleeping = Array(kinds.size) { k -> if (kinds[k].hibernates) BooleanArray(n) else null }

    /** For a kind's liking for rough ground [rugged] (see [Habitat]): how well each place's ground suits it on that alone, the mean over its ground. */
    @Transient internal var ruggedFits: ConcurrentHashMap<Float, FloatArray>? = ConcurrentHashMap()
    /** [ruggedFit] by kind ([Species.index]), as first asked for. */
    @Transient internal var ruggedFitOf: Array<FloatArray?>? = null
    /** Mean moonlight over this week's nights, 0 (new moon) to 1 (full): stalkers hunt by the dark. */
    var moon = 0.5f

    /**
     * Each kind's [Niches.groundShelter] at each place: fixed for the run, so worked out once. (A plain field, not `by lazy`:
     * every [catchable] reads it, and the lazy's read alone was 4% of a step's samples.)
     */
    @Transient internal val groundShelter = Array(kinds.size) { k -> FloatArray(n) { p -> Niches.groundShelter(kinds[k], Place(p), land) } }

    /** The game each resident hunter takes at each place ([game]) while the residents move; null otherwise. */
    @Transient internal var preyNow: Array<FloatArray?>? = null

    fun count(k: FaunaKind): Double = (0 until n).sumOf { ((adults[k][it] + young[k][it]) * land.areaKm2[it]).toDouble() }

    /**
     * Newcomers: once a year a resident kind that has dwindled below [NEWCOMER_ANIMALS] (or died out)
     * gets a few animals at places that suit it, as if wandering in from beyond the map; whether they
     * last is up to their niche. Only in training's play worlds ([newcomersOn]).
     */
    var newcomersOn = false
    private fun newcomers(date: Date) {
        if (!newcomersOn) return
        val r = stream(land.seed, Stream.NEWCOMERS, 0L, date.absoluteDay)
        for (k in kindIds) {
            if (count(k) >= NEWCOMER_ANIMALS) continue
            val s = kinds[k]
            val w = FloatArray(n) { p -> startWeight(s, Place(p)) }
            val total = w.sum(); if (total <= 0f) continue
            repeat(NEWCOMER_PLACES) {
                val p = inflation.drawWeighted(w, total, r)
                adults[k][p] += NEWCOMER_HEADS / land.areaKm2[p]
                land.books?.flow(Books.Stuff.MATTER, "newcomers from beyond the map", (NEWCOMER_HEADS * s.massKg).toDouble())
            }
        }
    }

    /** The residents' part of the week, in order, with the weather on the same places. */
    @Transient val phases = listOf(
        Phase("residents: new month") { newWeek(it, it.date) },
        Phase("food stocks") { food.step(it) },
        Phase("water life") { water.step(it) },
        Phase("residents: grazing") { graze(it) },
        Phase("residents: births, deaths") { breedAndDie(it, it.date) },
        Phase("residents: moving") {
            noteHunted()
            // (The places are weighed once, before the bands shift: the shift and the spill both go by them.)
            // They move every [Tuning.residentMoveWeeks] weeks, a step each move: from week to week they shift by a few percent.
            // (Over steps of several ticks they move every step, the step's ticks' worth at once: [moveWeeks].)
            // (A span of fewer ticks than that, as the residents' 12 days in a long step ([inflation.Tuning.residentDays]), moves if
            // a move falls in it, that move's weeks' worth: prof761, moving each span had them move half again as often.)
            val t0 = it.date.absoluteDay / clock.tickDays; val every = tuning.residentMoveWeeks
            val moving = clock.ticks >= every || (0 until clock.ticks).any { j -> (t0 + j) % every == 0L }
            if (moving) {
                fillArrive(it)
                // (The game the hunters weigh, taken once before any kind moves: the kinds move on all cores at once.)
                preyNow = Array(kinds.size) { k -> if (kinds[k].carnivore) FloatArray(n).also { v -> Par.forEachPlace("residents: game the hunters weigh", n) { p -> v[p] = game(kinds[k], p) } } else null }
                shiftBands(it)
                spill(it)
                preyNow = null
            }
        },
        Phase("soil and small life") { small.step(it, carcassKg) },
    )

    /** The week's start: the books' running sums, fear fading, outbreaks, a new month's newcomers, mothers' new year ([bred]) and sense of direction, and the water. */
    private fun newWeek(weather: Weather, date: Date) {
        val books = land.books
        ateBook = books?.adder(Books.Stuff.MATTER, "eaten"); droppedBook = books?.adder(Books.Stuff.MATTER, "dung and frass")
        // (A tick's fading a tick: over a step of several, compounded, as the step's kills add theirs.)
        val fearKeep = clock.keep(FEAR_KEEP)
        for (p in land.places) fear[p] *= fearKeep
        persecutionFades()
        stepOutbreaks(); outbreakSalt++
        carcassKg.fill(0f)
        // (Once a year at a month's start, in the step that holds it, whatever the step's length: [Date.holdsMonthStart].)
        if (date.holdsMonthStart(days, 0)) newcomers(date)
        for ((k, s) in kinds.withKinds()) if (date.holdsMonthStart(days, offSeason(s))) bred[k].fill(0f)
        if (bearings.month != date.month) Profile.time("sense of direction") { bearings.update(date.month) }
        if (date.holdsMonthStart(days, 3)) shiftBreeding()
        moon = date.meanMoonlight(days).toFloat()
        stepWater(weather)
    }

    /** Names the residents' stocks (the young at their weight now, [youngShare]), with the food stocks and the small life. */
    internal fun listStocks(stocks: Stocks) {
        stocks.within(StockName.RESIDENT_ANIMALS) { for ((k, s) in kinds.withKinds())
            stocks.ofKind(KindStock.RESIDENT, s.name, adults[k], young[k]) { var sum = 0.0; for (p in land.places) sum += (adults[k][p] + youngShare[k.i] * young[k][p]) * land.areaKm2[p] * s.massKg.toDouble(); sum } }
        food.listStocks(stocks)
        small.listStocks(stocks)
    }

    /**
     * The grass and browse, kg/m², that can still be eaten at each place this week without eating it
     * bare, once the residents have had theirs.
     */
    val grazeLeft = FloatArray(n)
    val browseLeft = FloatArray(n)

    /**
     * One place's week of [graze]: what each kind wants of its plants and can get at, and what it got, the food it left
     * ([Pantry]: taken off what is there, each kind as far as it can reach), and the totals eaten.
     */
    internal class Grazing(nk: Int) : Pantry {
        // How much each kind eats of poor forage depends on its gut (see [gutFill]).
        val need = FloatArray(nk); val each = FloatArray(nk)
        // How much of the place's food each kind can get at: cliffs keep all but climbers off some of
        // it, and far from water a kind that must drink often uses only the ground within reach of it.
        val avail = FloatArray(nk)
        // (Of each kind's need: what it got of plants; what it ate beyond, laid on.)
        val got = FloatArray(nk); val surplus = FloatArray(nk)
        // (For the check: of what it got of plants, the shares of its need from fruit and from roots and mast.)
        val gotFruit = FloatArray(nk); val gotUnder = FloatArray(nk)
        // (For the check: the shares of its need from grass and from browse, and the browse it ate, kg/m².)
        val gotGrass = FloatArray(nk); val gotBrowse = FloatArray(nk); val browseKg = FloatArray(nk)
        // (For the check: of its need, the shares from game it killed, small prey, and carrion and bones.)
        val gotMeat = FloatArray(nk); val gotSmall = FloatArray(nk); val gotCarrion = FloatArray(nk)
        /** Trace: kg/m² of carrion (not bones) each kind ate, and its whole need then, kg/m² of prey ([Fed.CARRION_KG]). */
        val carrionKg = FloatArray(nk); val needKg = FloatArray(nk)
        // (What each kind ate this week, kg per m2, and the protein in it: fresh growth full, cured grass
        // little; see [proteinMet]. What it ate to fatten is left out.)
        val ateKg = FloatArray(nk); val proteinKg = FloatArray(nk)
        var spareG = 0f; var spareB = 0f; var spareF = 0f; var spareR = 0f
        // (The share of the food the kind eating now can get at.)
        var access = 0f
        var eatG = 0f; var eatB = 0f; var eatF = 0f; var eatR = 0f
        val b = Bites()
        /** Zeroes what a place's week fills in, for the next place (the spare food, [access] and the eaten totals are set before they are read, [b] by each [forage]). */
        fun clear() {
            need.fill(0f); each.fill(0f); avail.fill(0f); got.fill(0f); surplus.fill(0f); gotFruit.fill(0f); gotUnder.fill(0f); gotGrass.fill(0f); gotBrowse.fill(0f); browseKg.fill(0f); gotMeat.fill(0f); gotSmall.fill(0f); gotCarrion.fill(0f); carrionKg.fill(0f); needKg.fill(0f); ateKg.fill(0f); proteinKg.fill(0f)
            eatG = 0f; eatB = 0f; eatF = 0f; eatR = 0f
            askG.fill(0f); askB.fill(0f); askF.fill(0f); askR.fill(0f); mode = FREE
        }
        // (The feeding is shared out in turns, [eatPlants]: first each kind asks what it would eat of the food there
        // ([ASKING]: noted in [askG] and the rest, nothing eaten), then each eats its share of what was asked of each food
        // ([SHARES]: at most [capG] and the rest), then the fed lay on fat from what is left ([FREE]).)
        var mode = FREE; var kind = 0
        val askG = FloatArray(nk); val askB = FloatArray(nk); val askF = FloatArray(nk); val askR = FloatArray(nk)
        val capG = FloatArray(nk); val capB = FloatArray(nk); val capF = FloatArray(nk); val capR = FloatArray(nk)
        private inline fun take(kg: Float, spare: Float, ask: FloatArray, cap: FloatArray, eat: (Float) -> Unit): Float {
            var t = min(kg, access * spare)
            when (mode) {
                ASKING -> { ask[kind] += t; return t }
                SHARES -> { t = min(t, cap[kind]); cap[kind] -= t }
            }
            eat(t); return t
        }
        override fun grass(kg: Float): Float = take(kg, spareG, askG, capG) { spareG -= it }
        override fun browse(kg: Float): Float = take(kg, spareB, askB, capB) { spareB -= it }
        override fun fruit(kg: Float): Float = take(kg, spareF, askF, capF) { spareF -= it }
        override fun roots(kg: Float): Float = take(kg, spareR, askR, capR) { spareR -= it }
        companion object { const val ASKING = 0; const val SHARES = 1; const val FREE = 2 }
    }

    /** One thread's [Grazing], cleared for each place it grazes ([graze]): made once, not afresh for every place every week. */
    @Transient internal val grazings = ThreadLocal.withInitial { Grazing(kinds.size) }

    /** The places a step leads to from each place, for [neighbourPlaces]: those of place `p` are `to[from[p] until from[p.i + 1]]`. */
    internal class StepPlaces(val from: IntArray, val to: IntArray)
    /** [StepPlaces] for each way of travel, worked out once (borders and places do not change). */
    @delegate:Transient internal val stepPlaces: Array<StepPlaces> by lazy {
        Array(HexMap.Travel.entries.size) { t ->
            val cross = hex.crossings[t]
            val from = IntArray(n + 1); var to = IntArray(12 * n); var m = 0
            for (p in land.places) {
                from[p] = m
                val h = hw.placeHex[p]
                for (d in 0 until 6) {
                    val nh = hex.neighbour(h, d)
                    if (nh.none || !cross[hex.edge(h, d)].isFinite()) continue
                    val q = hw.placeNear(p, nh); if (q.none) continue
                    if (m == to.size) to = to.copyOf(2 * m); to[m++] = q.i
                }
            }
            from[n] = m
            StepPlaces(from, to.copyOf(m))
        }
    }

    @delegate:Transient internal val climbPerM: FloatArray by lazy { FloatArray(kinds.size) { climbKmPerM(kinds[it].massKg) } }

    /**
     * For the viewer: the kind (slot) whose spill to record, -1 for none; and the last move's ([Tuning.residentMoveWeeks]),
     * by hex, the heads that spilled from it to each neighbour summed as a way east and south (km times heads), and the
     * heads that left it (the thin gathered in are not counted).
     */
    @Volatile @Transient var flowOf = -1
    @Transient val flowEast = FloatArray(hex.size)
    @Transient val flowSouth = FloatArray(hex.size)
    @Transient val flowOut = FloatArray(hex.size)

    /** The straits from each hex: the hexes across ([seaLinks]) and the km of open water to each ([seaGaps]); set by the groups (see [HexGroups.seaLinks]). */
    @Transient var seaLinks: Lazy<Array<HexArray>>? = null
    @Transient var seaGaps: Lazy<Array<FloatArray>>? = null

    /** The ways from each place for [flowPlaces]: those of place `p` are at `from[p] until from[p.i + 1]` of the rest. */
    internal class FlowWays(val from: IntArray, val to: IntArray, val ease: FloatArray, val km: FloatArray, val keep: FloatArray) {
        /** The place each way leaves from. */
        val src = IntArray(to.size).also { for (p in 0 until from.size - 1) for (i in from[p] until from[p + 1]) it[i] = p }
        /** The ways into each place: those into place `q` are `inWay[inFrom[q] until inFrom[q + 1]]`, by the place they leave from. */
        val inFrom = IntArray(from.size).also { f -> for (q in to) f[q + 1]++; for (q in 1 until f.size) f[q] += f[q - 1] }
        val inWay = IntArray(to.size).also { w -> val at = inFrom.copyOf(); for (i in to.indices) w[at[to[i]]++] = i }
    }

    /**
     * [FlowWays] for each kind, worked out once (borders, straits and places do not change): the other half of its hex;
     * across each border it can cross, the neighbouring hex's place nearest in height to its own (see [neighbourPlaces]);
     * and the shore across a strait it can swim, at the height nearest its own (a trickle, [SEA_SPILL], the nearer all it can swim the fewer).
     */
    @delegate:Transient internal val flowWays: Array<FlowWays> by lazy {
        val links = seaLinks?.value; val gaps = seaGaps?.value
        Array(kinds.size) { k ->
            val s = kinds[k]; val cross = hex.crossings[travel[k].ordinal]; val swim = Catalogue.swimKm(s)
            val from = IntArray(n + 1); val to = ArrayList<Int>(); val ease = ArrayList<Float>(); val km = ArrayList<Float>(); val keep = ArrayList<Float>()
            fun way(q: Int, e: Float, d: Float, l: Float) { to += q; ease += e; km += d; keep += l }
            for (p in land.places) {
                from[p] = to.size
                val h = hw.placeHex[p]
                for (q in hw.placesOf(h)) if (q != p.i) way(q, 1f, 0f, 1f)
                for (d in 0 until 6) {
                    val nh = hex.neighbour(h, d); if (nh.none) continue
                    val e = cross[hex.edge(h, d)]; if (!e.isFinite()) continue
                    val q = hw.placeNear(p, nh); if (!q.none) way(q.i, min(1f, hex.hexKm.toFloat() / e), e, 1f)
                }
                if (swim > 0f && links != null && gaps != null) {
                    val across = links[h]; val gapKm = gaps[h]
                    for (i in across.indices) {
                        if (gapKm[i] > swim) continue
                        val q = hw.placeNear(p, across[i]); if (q.none) continue
                        way(q.i, SEA_SPILL * (1f - gapKm[i] / swim), gapKm[i], 1f - drownRisk(s, gapKm[i]))
                    }
                }
            }
            from[n] = to.size
            FlowWays(from, to.toIntArray(), ease.toFloatArray(), km.toFloatArray(), keep.toFloatArray())
        }
    }

    /** What does not change at each place within a week for one kind, as [flow] weighs places ([placeWorth]), worked out as first needed ([fix]). */
    internal class WeekFixed(n: Int) {
        val done = BooleanArray(n); val drinks = BooleanArray(n)
        val grown = FloatArray(n); val young = FloatArray(n); val huntedYoung = FloatArray(n); val protein = FloatArray(n)
    }

    /** One thread's working arrays for weighing places ([fillArrive], [flow]): every use fills what it reads first. */
    internal class Scratch {
        val outs = enumFloats<Danger>(); val parts = enumFloats<Growth>(); val pantry = DryPantry(); val bites = Bites()
    }

    companion object {
        /** Each thread's [Scratch], made once for the run rather than afresh for every kind every week. */
        internal val scratch = ThreadLocal.withInitial { Scratch() }

        /** Share of the standing plants a week's grazing can take without eating the place bare. */
        const val REACH = 0.01f
        /** How strongly a kind is drawn to the ground it prefers. */
        const val HABITAT = 3f
        /** Density, as a multiple of a kind's usual, at which disease kills a [DISEASE] share a year; and at which crowding halves a place's appeal ([appeal]). */
        const val CROWDED = 4f
        /** [strife]: the times a resident hunter kind's usual numbers its territories hold; its deaths a year at twice that; its young's odds against the grown's. */
        const val STRIFE_CROWD = 2f
        const val STRIFE_DEATHS = 2f
        const val STRIFE_YOUNG = 2f
        /** [leaveShare]: of a kind that also roams, the more of its young growing up that leave on ground crowded to twice its usual numbers. */
        const val LEAVE_CROWD = 0.5f
        /** For the check only ([stuck]): fed below this counts as hungry. */
        const val STUCK_FED = 0.8f
        /**
         * Protein in forage, against fresh growth: cured grass keeps some of its energy but loses most
         * of its protein (about 4% crude protein against 16% green, though grazers pick the best of it
         * and get about 6%; 3% once leached to straw); browse keeps more
         * (shoots and leaves of shrubs, about 10%); fruit, nuts and roots are middling. A diet at
         * [PROTEIN_NEED] of fresh growth's protein meets a mother's and a growing young's need (7 to 8%
         * crude protein); below it births fall and the young die more, by [inflation.Tuning.youngProteinDeaths] a year
         * at none. Worked out as a mean over the last weeks ([PROTEIN_MEMORY] a week).
         */
        const val PROTEIN_DRY_BROWSE = 0.6f
        const val PROTEIN_FRUIT = 0.4f
        const val PROTEIN_NEED = 0.5f
        const val PROTEIN_MEMORY = 0.3f
        /** Share a week of residents that leave for the best country next door when it is as good again as here (none when it is no better). */
        const val SPILL_BETTER = 0.1f
        /**
         * The share of the way to the level a pass of the residents' [flow] goes: half. Moving all the way at once, those
         * from all about a place would each come as if alone, and together overshoot it.
         */
        const val FLOW_STEP = 0.5f
        /** Heads per km² past what any place's food could feed: what [spareHeads] weighs the food left uneaten by. */
        const val SPARE_CROWD = 1e6f
        /**
         * Resident hunters: heaviest prey against their own weight; share of a prey kind's numbers in a place they can
         * kill in a week. (They eat [MEAT_SHARE] of a kill, as packs do.)
         */
        const val RESIDENT_KILL_MOST = 0.02f
        /** The weight of a young animal against a grown one: in a hunter's kill, and in the carcasses the dead leave. */
        const val YOUNG_WEIGHT = 0.3f
        /**
         * The mean weight of [s]'s young [month]s into the year (0-based) as a share of a grown one's: each year's young born
         * at its birth weight ([NEWBORN_SHARE] of its mother's over her litter, a piglet some 1.4 kg, a deer calf some 8)
         * and growing evenly to grown at [Species.yearsToMaturity], those of the years before mixed in as many as are still
         * growing. Piglets weigh 1-2 kg in their first weeks, when foxes, jackals and eagles take them, and some 35-45 kg at
         * a year (from memory); counted at a fixed three tenths of a sow all year, no small hunter could take one.
         */
        fun youngShareIn(s: Species, month: Int): Float {
            val born = (NEWBORN_SHARE / max(1f, s.youngPerFemale)).coerceAtMost(1f)
            val grow = 12f * s.yearsToMaturity.coerceAtLeast(0.1f)
            val since = ((month - s.birthMonth) % 12 + 12) % 12
            var sum = 0f; var cohorts = 0; var age = since.toFloat()
            while (age < grow) { sum += born + (1f - born) * (age / grow); cohorts++; age += 12f }
            return if (cohorts > 0) sum / cohorts else 1f
        }
        /** How strongly a strait draws spilling residents against a neighbouring hex on land (a trickle). */
        const val SEA_SPILL = 0.02f
        /** Newcomers among the residents: a kind under this many animals gets this many at each of this many places, once a year. */
        const val NEWCOMER_ANIMALS = 300.0
        const val NEWCOMER_HEADS = 20f
        const val NEWCOMER_PLACES = 5
        /** How many times more disease strikes those not fed at all than the well-fed. */
        const val MALNOURISHED = 4f
        const val DISEASE = 0.3f
        /** Food, kg/m², at which a place carries a kind's usual density. */
        const val FOOD_FULL = 0.3f
        /** Fed above this share of its need, an animal or band has days to spare ([spareDays]). */
        const val HUNGER_FROM = 0.5f
        /**
         * A kind's usual density, per km²: as the catalogue gives it, else by Damuth's rule from its body
         * mass; what crowding (and the sickness it breeds) is reckoned against, for residents and groups alike.
         */
        fun usualDensity(s: Species) = if (s.startKm2PerAnimal > 0f) 1f / s.startKm2PerAnimal else Allometry.herbivoreDensityPerKm2(s.massKg)
        const val COLD_DEATHS = 0.02f
        /**
         * How slowly open water follows the air, days (an exponential lag): lakes and big lowland rivers
         * lag the air by weeks, small streams by days (from memory of stream- and lake-temperature
         * studies, not checked). It smooths a cold snap for kinds that lie in water.
         */
        const val WATER_LAG_DAYS = 30f

        /** The monthly normals of water under air with normals [t] (by month), °C, lagged by [WATER_LAG_DAYS] and kept from freezing below 0. */
        fun laggedNormals(t: (Int) -> Float): FloatArray {
            val k = 1f - exp(-Date.DAYS_PER_YEAR / 12f / WATER_LAG_DAYS)
            val out = FloatArray(12)
            var w = (0 until 12).map { max(0f, t(it)) }.average().toFloat()
            repeat(3) { for (m in 0 until 12) { w += (max(0f, t(m)) - w) * k; out[m] = w } }
            return out
        }
        /**
         * Below this density, per km², a kind is too thin at a place to hold on: it fades there at a rate a day of one in
         * [Tuning.thinFadeDays] ([spill]), and is gone below [EXTINCT_FLOOR]. (It was set to none once a step, which took ten
         * times as many at the weekly step as at 60 days: weekly wolves lost about 50 per 1000 a year so, 60-day ones 1-4;
         * fade692.) With t.thinFadeDays=0, set to none once a step as before.
         */
        const val EXTINCT = 1e-4f
        /** Below this density, per km², a kind is gone from a place at once (a hundredth of [EXTINCT]). */
        const val EXTINCT_FLOOR = 1e-6f
        /**
         * Outbreaks: yearly chance one starts in a place at its kind's crowded density; share of the kind it kills a
         * week at full strength; share of its strength it passes to a neighbouring hex; share kept a week.
         */
        const val OUTBREAK_START = 0.02f
        const val OUTBREAK_KILL = 0.04f
        const val OUTBREAK_SPREAD = 0.6f
        const val OUTBREAK_KEEP = 0.85f
        /** The least strength an outbreak passes on from. */
        const val OUTBREAK_PASSES = 0.05f
        /** Fear: kg of game killed lately per km² at which a place is half as frightening as it gets; how much it puts game off at most; share kept a week. */
        const val FEAR_HALF = 5f
        const val FEAR_WEIGHT = 0.5f
        const val FEAR_KEEP = 0.7f
        /** Weekly share moving between bands at full advantage. */
        const val BAND_SHIFT = 0.15f
        /** Weekly share spilling to neighbouring hexes anyway, and more when hungry. */
        const val SPILL_BASE = 0.005f
        const val SPILL_HUNGER = 0.1f
        /** Share of its usual density a kind starts at. */
        const val START_SHARE = 0.5f
        /** Forage quality (against green, 1) at which a herbivore's gut just holds its need: middling forage. */
        const val GUT_QREF = 0.8f
        /** [sharers]: how far another kind that shares its parasites crowds it, at a diet the same as its own. (At 0.3 the cud-chewers
         * crowded each other past the backstop at half their own numbers: a modest share, as their worms are mostly their own.) */
        const val SHARED_SICK = 0.1f
        /**
         * [usualAt]: kg of a hunter its territories hold for each kg of prey of its size there ([preyBiomass]). Wolves some
         * 3.3 to the 1000 km² for each deer-equivalent a km² (Fuller 1989), about 2 kg for 1000 of prey at a 35 kg wolf;
         * lions in the same range (Hayward et al. 2007); both from memory. Reckoned once on the game it could catch (as
         * defence, cover and shelter leave it), only a few hundred kg a km², the ground held a wolf a third of the record's.
         */
        const val PREY_SUPPORT = 0.002f
        /** [usualAt]'s least, heads per km², where there is next to no game: a territory of some 10,000 km². */
        const val TERRITORY_LEAST = 1e-4f
        /** [gameMean]: the share of the way to the week's game it goes a week, about half a year's memory. */
        const val GAME_WEEK = 1f / 26f
        /** [foodMean]: the share of the way to the week's food it goes a week, about a year's memory. */
        const val USUAL_FOOD_WEEK = 1f / 52f
        /** [foodScale]'s least and most: poor ground holds a tenth of a kind's usual numbers at least, rich four times at most (an estimate). */
        const val USUAL_FOOD_LEAST = 0.1f
        /** [hidingShare]'s least: a thicket kind holds a twentieth of its usual numbers on the openest ground (an estimate, so its crowding stays finite). */
        const val HIDING_LEAST = 0.05f
        const val USUAL_FOOD_MOST = 4f
        /** Whether resident kind [s] holds ground by the game it kills ([usualAt]): a hunter with game most of its diet. */
        fun holdsByPrey(s: Species) = s.carnivore && s.diet.meat > 0.5f * (s.diet.meat + s.diet.smallPrey + s.diet.carrion + s.diet.plants)
        /**
         * [wetSickness]: deaths a year of a kind that bears no damp at all on ground wet the year round, and well fed. Liver
         * fluke kills a fifth to a half of sheep in a bad fluke year on wet pasture (from memory of the veterinary record).
         */
        const val WET_SICK = 0.3f
        /** [damp]: the share of the way to the week's dampness it goes a week, about half a year's memory. */
        const val DAMP_WEEK = 1f / 26f
        /** The share of two diets that is of the same sorts of food, 0..1 (Schoener's overlap). */
        fun dietOverlap(a: Diet, b: Diet): Float {
            val ta = (a.grass + a.browse + a.fruit + a.roots).coerceAtLeast(1e-6f); val tb = (b.grass + b.browse + b.fruit + b.roots).coerceAtLeast(1e-6f)
            return min(a.grass / ta, b.grass / tb) + min(a.browse / ta, b.browse / tb) + min(a.fruit / ta, b.fruit / tb) + min(a.roots / ta, b.roots / tb)
        }
        /** Seed carried by fruit-eaters: kg of fruit eaten per km² that seeds a place fully; share kept from week to week. */
        const val SEED_KG = 200f
        const val SEED_KEEP = 0.9f
        /** Share of the residents' weight that tramples their place in a week. */
        const val RESIDENT_TRAMPLE = 0.1f
        /** Hibernators sleep below this temperature, °C. */
        const val HIBERNATE_C = 0f
        /** Share of its waking food a sleeping animal burns from its fat. */
        const val SLEEP_NEED = 0.25f
        /** How fast the memory of each month's feeding follows the year (per week in that month). */
        const val FED_MEMORY = 0.1f
        /** How much better fed (summed over three months) a month earlier or later must be to move the births. */
        const val BREED_SHIFT_GAIN = 0.1
        /** Fire: share of adults and of young caught on ground that burns entirely. */
        const val FIRE_ADULT = 0.05f
        const val FIRE_YOUNG = 0.3f
        /** Floods: depth over the floodplain that drowns animals, m; share of adults and of young drowned on a fully flooded place. */
        const val FLOOD_DROWN_M = 2f
        const val FLOOD_ADULT = 0.05f
        const val FLOOD_YOUNG = 0.2f
        /** Deep snow: share a week dying per snow depth (in multiples of what the kind can dig through) beyond two. */
        const val SNOW_DEATHS = 0.02f
        /** Ash: depth from which grazing in it harms, mm; share a week dying per 50 mm. */
        const val ASH_HARM_MM = 5f
        const val ASH_DEATHS = 0.02f
        /** Share a week that leaves country that does not suit it, at full strength of the bearing; how strongly the bearing steers them. */
        const val SPILL_MISFIT = 0.05f
        const val BEARING_SPILL = 3f
    }
}

/** The residents' deaths in a week, by [cause] as the deaths ledger names it. */
/**
 * Residents of a place left too thin to count ([HexFauna.EXTINCT] a km²), set to none after births and deaths and after
 * moving: counted as deaths of [DeathCause.OTHER] (residents have no other "other"), a report of what was a silent loss.
 */
internal val FADED_OUT = DeathCause.OTHER

enum class ResidentDeath(val cause: DeathCause) {
    AGE(DeathCause.AGE), HUNGER(DeathCause.HUNGER), COLD(DeathCause.COLD), HEAT(DeathCause.HEAT), DISEASE(DeathCause.DISEASE), EAGLES(DeathCause.EAGLES),
    FIRE(DeathCause.FIRE), FLOOD(DeathCause.FLOOD), SNOW(DeathCause.SNOW), CROCODILES(DeathCause.CROCODILES), THIRST(DeathCause.THIRST),
    SHORT_OF_PROTEIN(DeathCause.SHORT_OF_PROTEIN), RIVALS(DeathCause.RIVALS), YOUNG_KILLED_BY_RIVALS(DeathCause.YOUNG_KILLED_BY_RIVALS)
}
@Suppress("NOTHING_TO_INLINE") internal inline operator fun DoubleArray.get(d: ResidentDeath) = this[d.ordinal]
@Suppress("NOTHING_TO_INLINE") internal inline operator fun DoubleArray.set(d: ResidentDeath, v: Double) { this[d.ordinal] = v }

/** Residents held hungry where they are ([HexFauna.stuck]). */
enum class Stuck { HEADS, STUCK, AT_EDGE, PEAK }

/** Where residents' food came from ([HexFauna.fedFrom]): fresh plants, prey and carrion, reserves; and the animals counted. */
enum class Fed { PLANTS, ANIMAL, RESERVES, HEADS, FRUIT, UNDERGROUND, MEAT, SMALL_PREY, CARRION, CARRION_KG, NEED_KG, GRASS, BROWSE, BROWSE_KG }
internal val FED = Fed.entries.size
@Suppress("NOTHING_TO_INLINE") inline operator fun Array<DoubleAdder>.get(f: Fed) = this[f.ordinal]

/** Shares of a kind's animals that die this week of fire, flood, and deep snow or ash (see [HexFauna.calamities]). */
data class Calamities(val fire: Float, val flood: Float, val snow: Float)

/** The animal foods a resident hunter lives on, in the order its shortfall is made up when their shares tie. */
internal enum class AnimalFood { MEAT, SMALL_PREY, CARRION }

/**
 * Whether a band of [band] animals dying at a share [here] in a week is clearly worse off than at a share [there]: by
 * more than a step of a week's chance spread of its deaths ([Hazards.survivalSteps], at [here] or its kind's
 * [background] share), as groups weigh a place's deaths.
 */
internal fun clearlyWorse(here: Float, there: Float, band: Float, background: Float): Boolean {
    if (!(there < here)) return false
    return Hazards.survivalSteps(here - there, here, background, band) > 1f
}

/** [heads] grown of kind [k] killed as game at place [at] (its index) out of the packs standing there, by hunter [by] (a group's id, or -1 - a resident kind). */
internal class PackKill(val at: Int, val k: Int, val heads: Float, val by: Long)
