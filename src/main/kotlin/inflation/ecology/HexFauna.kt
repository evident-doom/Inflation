package inflation.ecology

import inflation.ecology.HexSmallLife.DeadPool
import inflation.Tuning
import inflation.wrapMonth
import inflation.saturate
import inflation.monthsFrom
import inflation.HASH_A
import inflation.HASH_B
import inflation.HASH_C
import inflation.mix64Once
import inflation.unitOf
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
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.concurrent.atomic.DoubleAdder
import java.util.stream.IntStream

/**
 * The resident plant-eaters on the hexes, as densities: adults and young per km² of each place
 * (a height band of a hex). Each week, in each place:
 *
 * - The herds of every kind graze and browse the place's plants, sharing them by what each wants
 *   of grass, browse and fruit; snow deeper than a kind can dig through hides the grass.
 * - Young are born in the season, and grow up over the years to maturity.
 * - Animals die of age and accident, faster when hungry, in cold beyond what their coat allows,
 *   and the young most.
 * - Within a hex they shift between its height bands towards better feeding and kinder weather,
 *   up the mountain in summer and down in winter.
 * - When hungry or crowded, some spill over into the places around, over the hex borders as their
 *   way of travelling allows (climbers over crags, swimmers across rivers), towards better feeding.
 *
 * They stay in their country: nothing here wanders far, but over the years a kind spreads into
 * empty ground that suits it, and withdraws from ground that fails it.
 */
class HexFauna(private val hw: HexWorld, private val land: HexLand, private val days: Int) {
    internal val tuning get() = land.tuning
    private val hex = hw.hex
    val n = land.n
    /**
     * Open water's temperature at each place this week, °C: it follows the air slowly (see
     * [WATER_LAG_DAYS]) and freezes at 0. NaN until the first week (and in worlds saved before it was
     * kept), when it starts from its normal for the month.
     */
    val waterC = FloatArray(n) { Float.NaN }
    /** [waterC]'s normal for each month and place, from the month's normal air temperatures. */
    @delegate:Transient private val waterNormalC by lazy {
        val byPlace = Array(n) { p -> laggedNormals { m -> hw.placeTemp(p, m) } }
        Array(12) { m -> FloatArray(n) { p -> byPlace[p][m] } }
    }

    /**
     * The resident kinds, and for each, adults and young per km² of each place: those that live only so, then the herd kinds that
     * also live spread out between their gatherings ([Catalogue.gathering]).
     */
    @Transient val kinds = (Catalogue.all.filter { Catalogue.resident(it) } + Catalogue.gathering.sortedBy { it.index }).distinct()
    /** Each kind's place in [kinds] (and in every table here kept by kind), by its [Species.index]; -1 for a kind kept in groups. */
    @Transient private val slots = IntArray(Catalogue.all.size) { -1 }.also { for ((k, s) in kinds.withIndex()) it[s.index] = k }
    /** [s]'s place in [kinds], or -1 if it is not a resident. */
    fun slot(s: Species) = slots[s.index]

    val adults = Array(kinds.size) { FloatArray(n) }
    val young = Array(kinds.size) { FloatArray(n) }
    /** The resident kinds by body mass, the biggest first: the order they get at food that is short. */
    @Transient private val byMass = kinds.indices.sortedByDescending { kinds[it].massKg }.toIntArray()
    /** Last week's share of its wants each kind found in each place, 0..1. */
    val fed = Array(kinds.size) { FloatArray(n) { 1f } }
    /**
     * How well each kind's diet in each place met its need for protein over the last weeks, 0..1 (see
     * [proteinMet]): what mothers make milk of and the young grow on.
     */
    val protein = Array(kinds.size) { FloatArray(n) { 1f } }
    /**
     * For the check: where each kind's food came from, summed over its animals since last read (fresh
     * grass, browse, fruit and roots; standing straw; small prey and carrion; its reserves), and the
     * animals counted, so that each over the last is the share of a full need met that way: indexed by
     * [Fed.PLANTS], [Fed.STRAW], [Fed.ANIMAL], [Fed.RESERVES] and [Fed.HEADS].
     */
    val fedFrom = Array(kinds.size) { Array(Fed.entries.size) { DoubleAdder() } }
    /**
     * For the check, since last read, counted on the weeks the residents move ([Tuning.residentMoveWeeks]): each kind's
     * resident heads summed over those weeks ([Stuck.HEADS]); those hungry (fed under [STUCK_FED]) with no neighbouring
     * place the spill could take them to that is better ([Stuck.STUCK]); of those, the ones where some border is closed to
     * them, by sea or ground they cannot cross ([Stuck.AT_EDGE]); and the most of a kind so held at any one move, as a
     * share ([Stuck.PEAK]). Where gradual movement piles animals up.
     */
    @Transient val stuck = Array(kinds.size) { DoubleArray(Stuck.entries.size) }
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
    @Transient private val usual = FloatArray(kinds.size) { k -> usualDensity(kinds[k]) }
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
    fun startOutbreak(s: Species, h: Int) { outbreak[s.index][h] = 1f }
    /** This week's share of [s] that an outbreak kills at place [p]. */
    fun outbreakDeaths(s: Species, p: Int) = OUTBREAK_KILL * outbreak[s.index][hw.placeHex[p]]

    private var outbreakSalt = 0L
    /** The world's own draws: outbreaks and newcomers differ from seed to seed. */
    @Transient private val seedSalt = mix64Once(land.seed)
    /** A number in 0..1 that changes from week to week, for place [p] and kind [k]. */
    private fun hash(p: Int, k: Int): Float {
        return unitOf(mix64Once((p.toLong() * HASH_A) xor (k.toLong() * HASH_B) xor (outbreakSalt * HASH_C) xor seedSalt))
    }

    /** Outbreaks pass to neighbouring hexes where the kind lives, and burn out. */
    private fun stepOutbreaks() {
        for ((si, s) in Catalogue.all.withIndex()) {
            val o = outbreak[si]
            if (o.all { it < 0.01f }) continue
            val k = slot(s)
            val old = o.copyOf()
            for (h in 0 until hex.size) {
                if (old[h] < 0.05f) continue
                for (d in 0 until 6) {
                    val q = hex.neighbour(h, d); if (q < 0) continue
                    val here = groupHeads[si][q] > 0 ||
                        (k >= 0 && (0 until HexWorld.BANDS_M.size).any { b -> hw.placeOf[b][q].let { p -> p >= 0 && adults[k][p] + young[k][p] > 0.01f } })
                    if (here) o[q] = min(1f, max(o[q], OUTBREAK_SPREAD * old[h]))
                }
            }
            for (h in 0 until hex.size) o[h] *= OUTBREAK_KEEP
        }
    }
    /** Notes [kg] of game killed at place [p], for the fear of it. */
    fun killed(p: Int, kg: Kg) { fear[p] += kg.v / land.areaKm2[p] }

    /** Protein of the grass and of the browse at place [p], against fresh growth: full when green and leafy, little when cured or ripe. */
    fun grassProtein(p: Int): Float {
        // (Cured grass is leached toward straw as it weathers, see [HexPlants.hayFresh].)
        val dry = tuning.proteinStraw + (tuning.proteinDryGrass - tuning.proteinStraw) * land.plants.hayFresh[p]
        return dry + (1f - dry) * land.plants.leafy(p)
    }
    fun browseProtein(p: Int) = PROTEIN_DRY_BROWSE + (1f - PROTEIN_DRY_BROWSE) * land.plants.green[p]
    /** How well a diet with [share] of fresh growth's protein meets a mother's and a growing young's need, 0..1. */
    fun proteinMet(share: Float) = min(1f, share / PROTEIN_NEED)

    /** For the kinds that also live as herds ([Catalogue.gathering]): the young that grew up this week, by place, per km² (see [HexGroups.scatter]). */
    @Transient internal val grownUp: Array<FloatArray?> = Array(kinds.size) { if (kinds[it] in Catalogue.gathering) FloatArray(n) else null }
    /**
     * For the plant-eaters: how well a band of the kind ([Species.maxGroupSize]) coming to each place would be fed there
     * this week, 0..1 of its need, eating by the same rule as those there ([forage]) from the food left uneaten after
     * every kind has eaten, as far as the kind can get at it (cliffs, the walk to water, biting insects); see [arriveOdds].
     */
    @Transient internal val room: Array<FloatArray?> = Array(kinds.size) { if (!kinds[it].carnivore) FloatArray(n) else null }
    /** The food left uneaten at each place after every kind has eaten this week, kg per m², as far as the eaters could reach it: grass, browse, fruit, roots and straw ([room], [roomFor]). */
    @Transient private val spareGrass = FloatArray(n)
    @Transient private val spareBrowse = FloatArray(n)
    @Transient private val spareFruit = FloatArray(n)
    @Transient private val spareRoots = FloatArray(n)
    @Transient private val spareStraw = FloatArray(n)

    /** The plants an animal of kind [k] at place [p] wants this week, kg per m² for one animal per km²: none asleep, more in the cold ([coldNeed]). */
    private fun plantWant(k: Int, p: Int, weather: Weather): Float {
        val s = kinds[k]
        return if (asleep(k, p)) 0f else s.dailyIntakeKg * coldNeed(s, feltC(s, p, weather.temperature[p]), weather.date.month, tuning) *
            days / 1e6f * plantShare(s)
    }

    /** How much of the food at place [p] kind [s] can get at: cliffs keep all but climbers off some of it, a kind that must drink often uses only the ground within reach of water, and biting insects cost it feeding time. */
    private fun reachOf(s: Species, p: Int) = HexFood.access(s, land.crag[p]) * tether(s, p) * (1f - HexSmallLife.HARASS_FEED * small.harassment(p))

    /**
     * How well [heads] of kind [s] coming to place [p] together would be fed there, 0..1 of their need: wanting [each] a
     * head ([plantWant]), getting at [reach] of the food ([reachOf]) under [snowMm] of snow, eating by the same rule as
     * those there ([forage]) from the food they left uneaten ([spareGrass] and the rest), so poor food feeds them no
     * better than it feeds those already there.
     */
    private fun fedOnSpare(s: Species, p: Int, heads: Float, each: Float, reach: Float, snowMm: Float, dry: DryPantry, b: Bites): Float {
        val want = heads / land.areaKm2[p] * each
        if (!(want > 0f)) return 1f
        dry.reach = reach; dry.grass = spareGrass[p]; dry.browse = spareBrowse[p]
        dry.fruit = spareFruit[p]; dry.roots = spareRoots[p]; dry.straw = spareStraw[p]
        return (forage(s, s.diet, land.plants.nourishment(p), want, grassUnderSnow(s, snowMm), reach, dry, b).got / want).coerceIn(0f, 1f)
    }

    /** [fedOnSpare] for [heads] of plant-eating kind [k] coming to place [p] this week; [room] is this for a band. */
    private fun roomFor(k: Int, p: Int, heads: Float, weather: Weather, dry: DryPantry, b: Bites): Float =
        fedOnSpare(kinds[k], p, heads, plantWant(k, p, weather), reachOf(kinds[k], p), weather.snowpack[p], dry, b)
    /**
     * Each kind's share of those coming to each place that would die there in a week
     * ([arriveOdds], NaN where it cannot stay), worked out once a week before the residents move ([fillArrive]): the
     * one measure the residents' drift and the groups' leaving and settling ([HexGroups.leaveAhead]) weigh places by.
     */
    @Transient internal val arrive = Array(kinds.size) { FloatArray(n) }
    /**
     * Per km², the heads a band of each kind ([Species.maxGroupSize]) coming to a place adds to those there: spread over
     * the ground it ranges in a day, a circle half its day's going across (out and back).
     */
    private fun arrivingKm2(k: Int): Float {
        val s = kinds[k]; val r = 0.5f * s.dailyTravelKm
        return s.maxGroupSize / (Math.PI.toFloat() * r * r)
    }
    /**
     * What each place is worth to each kind for moving there, worked out in the weeks the residents move ([fillArrive]):
     * for the plant-eaters, the rate a band coming there would grow at over a year by the rules they are born and die by
     * ([growth]); for the resident hunters, its [appeal]. Never below nothing nor past reckoning ([worthOrNothing]).
     */
    @Transient internal val worth = Array(kinds.size) { FloatArray(n) }
    /** Of each kind as it moves this week ([fillArrive]): its young to a grown one, and the fat days its grown carry, all over. */
    @Transient private val moverYoung = FloatArray(kinds.size)
    @Transient private val moverFat = FloatArray(kinds.size)
    /** Animals of each kind killed this week by hunters among the residents (lynx, jackals), for the deaths by cause. */
    private val huntedByResidents = Array(kinds.size) { FloatArray(n) }
    /**
     * What each resident hunter took this week at each place of the game about it ([huntResidents]): the kg it killed per kg
     * of game there, each kind's weighed by how readily it takes game of its size. An animal there (resident or of a herd)
     * is killed at that rate times the hunter's [Species.preySize] for it. Null for kinds that eat no meat.
     */
    @delegate:Transient private val killRate by lazy { Array(kinds.size) { if (kinds[it].diet.meat > 0f) FloatArray(n) else null } }

    /** The odds that an animal of [kg] about place [p] is killed by the residents' hunters in a week, as they hunted there this week ([killRate]). */
    fun residentKillOdds(kg: Float, p: Int): Float {
        val rates = killRate
        var v = 0f
        for (k in kinds.indices) { val r = rates[k] ?: continue; if (r[p] > 0f) v += r[p] * kinds[k].preySize(kg) }
        return v
    }

    /**
     * [residentKillOdds] for an animal of kind [s] (into `out[0]`) and its young (`out[1]`) about place [p], kept for the
     * week once worked out (the kill rates stand from the residents' grazing to the next week's): herds of a kind weigh
     * the same places over and over as they choose.
     */
    fun residentKillOdds(s: Species, p: Int, out: FloatArray) {
        val cache = killOdds ?: AtomicReferenceArray<FloatArray>(Catalogue.all.size).also { killOdds = it }
        val odds = cache.get(s.index) ?: run {
            cache.compareAndSet(s.index, null, FloatArray(2 * n) { Float.NaN })
            cache.get(s.index)
        }
        val a = odds[2 * p]; val y = odds[2 * p + 1]
        if (a.isNaN() || y.isNaN()) {
            residentKillOdds(s.massKg, YOUNG_WEIGHT * s.massKg, p, out)
            odds[2 * p] = out[0]; odds[2 * p + 1] = out[1]
        } else { out[0] = a; out[1] = y }
    }
    /** The odds [residentKillOdds] gives each kind ([Species.index]) and its young about each place this week (at `2p` and `2p + 1`; NaN: not yet worked out). */
    @Transient private var killOdds: AtomicReferenceArray<FloatArray>? = null

    /** [residentKillOdds] for an animal of [kg] and one of [youngKg] at once, into [out] (0: the first, 1: the second). */
    fun residentKillOdds(kg: Float, youngKg: Float, p: Int, out: FloatArray) {
        val rates = killRate
        var v = 0f; var w = 0f
        for (k in kinds.indices) {
            val r = rates[k] ?: continue
            val rp = r[p]
            if (rp > 0f) { val ks = kinds[k]; v += rp * ks.preySize(kg); w += rp * ks.preySize(youngKg) }
        }
        out[0] = v; out[1] = w
    }

    /**
     * The share of each kind's residents at each place the hunting parties (packs and bands) killed in their last week's
     * foraging ([HexGroups.hunt]; young and grown alike, as they take them as they come on them), set as they kill.
     */
    val groupKilled = Array(kinds.size) { FloatArray(n) }

    /**
     * The odds that a resident of kind [k] at place [p] (one of its young if [young]) is killed by hunters in a week, all of
     * them: the residents' hunters as they hunted there this week ([residentKillOdds]) and the hunting parties as they did
     * in their last week ([groupKilled]).
     */
    fun huntedOdds(k: Int, p: Int, young: Boolean): Float {
        val s = kinds[k]
        return residentKillOdds(if (young) YOUNG_WEIGHT * s.massKg else s.massKg, p) + groupKilled[k][p]
    }
    /**
     * For the books (see [Books]), this step, kg; null when they are not kept: what the residents ate and dropped as
     * dung, the herds' animals their hunters killed (taken from the herds after, see [HexGroups.takeResidentsKills]),
     * the young born and grown, and the carcasses handed to the soil.
     */
    @Transient private var ateBook: DoubleAdder? = null
    @Transient private var droppedBook: DoubleAdder? = null
    @Transient private var herdKillBook: DoubleAdder? = null

    /**
     * Small animal food for resident kind [k] at place [p], up to [want]; returns what was eaten. A kind that
     * roots for its food (roots in its diet: boar, bear) finds it mostly by digging, the grubs in the
     * soil first, then what is out, and of the small vertebrates only the young in the nest; the
     * others stalk it ([HexSmallLife.catchSmall], [HexSmallLife.reach]).
     */
    private fun smallFood(k: Int, p: Int, want: KgM2, weather: Weather): KgM2 {
        if (want.v <= 0f) return KgM2.ZERO
        val roots = kinds[k].diet.roots > 0f
        val dug = if (roots) small.digGrubs(p, want.perKm2).perM2 else KgM2.ZERO
        return dug + small.catchSmall(p, (want - dug).perKm2, weather, who = k, style = if (roots) SmallHunt.ROOT else SmallHunt.STALK).perM2
    }

    /**
     * Resident hunter [k] at place [p] kills game among the residents there, and among the herds about it this week
     * ([herdsAt]), for up to [meat] of meat: prey from a quarter of its weight to [Species.preyUpTo] times it (the young
     * of bigger kinds too), each kind as it is common there, by its weight and as readily as the hunter takes game of its
     * size ([Species.preySize]), herd or resident alike, at most [RESIDENT_KILL_MOST] of that game in a week; what it cannot
     * eat of a kill is left as carrion. Returns what it ate.
     */
    /**
     * For the check: by prey kind, the weight of game the residents' hunters could take (in their size range) and took,
     * spread and in herds ([HuntSeen]), summed over every hunt since last cleared: a kill rate per kg by form.
     */
    @Transient val huntSeen = Array(kinds.size) { Array(HuntSeen.entries.size) { DoubleAdder() } }
    enum class HuntSeen { SPREAD_KG, SPREAD_TAKEN, HERDS_KG, HERDS_TAKEN }

    private fun huntResidents(k: Int, p: Int, meat: KgM2, killedSoFar: FloatArray): KgM2 {
        if (meat.v <= 0f) return KgM2.ZERO
        val hunter = kinds[k]
        val area = land.areaKm2[p]
        var total = 0f
        // (Weights in kg per km2, as the numbers are kept.)
        val wA = FloatArray(kinds.size); val wY = FloatArray(kinds.size)
        for ((j, s) in kinds.withIndex()) {
            if (j == k || s.carnivore) continue
            val ym = YOUNG_WEIGHT * s.massKg
            wA[j] = adults[j][p] * s.massKg * hunter.preySize(s.massKg)
            wY[j] = young[j][p] * ym * hunter.preySize(ym)
            total += wA[j] + wY[j]
        }
        val herds = herdsHere?.get(p)
        val hA = FloatArray(herds?.size ?: 0); val hY = FloatArray(hA.size)
        if (herds != null) for ((i, at) in herds.withIndex()) {
            val s = at.g.species
            val ym = YOUNG_WEIGHT * s.massKg
            hA[i] = at.g.adults * at.share / area * s.massKg * hunter.preySize(s.massKg)
            hY[i] = at.g.young * at.share / area * ym * hunter.preySize(ym)
            total += hA[i] + hY[i]
        }
        if (total <= 0f) return KgM2.ZERO
        // (Its need is reckoned in the weight of the prey it kills, the parts it leaves included ([Allometry.dailyIntakeKg]);
        // of each kill it eats [MEAT_SHARE] and leaves the rest as carrion. At most [RESIDENT_KILL_MOST] of the game in the
        // week, all its hunting that week together ([killedSoFar]: what it took before, kg per km²). The game, [total] and
        // the kills are kept per km²; the carrion per m².)
        val want = min(meat.perKm2, KgKm2(RESIDENT_KILL_MOST * (total + killedSoFar[0]) - killedSoFar[0]))
        if (want.v <= 0f) return KgM2.ZERO
        killedSoFar[0] += want.v
        killRate[k]?.let { it[p] += want.v / total }
        var ate = KgM2.ZERO
        for ((j, s) in kinds.withIndex()) {
            if (wA[j] + wY[j] <= 0f) continue
            val kg = want.v * (wA[j] + wY[j]) / total
            huntSeen[j][HuntSeen.SPREAD_KG.ordinal].add(((wA[j] + wY[j]) * area).toDouble()); huntSeen[j][HuntSeen.SPREAD_TAKEN.ordinal].add((kg * area).toDouble())
            val fromA = kg * wA[j] / (wA[j] + wY[j]); val fromY = kg - fromA
            adults[j][p] = max(0f, adults[j][p] - fromA / s.massKg)
            young[j][p] = max(0f, young[j][p] - fromY / (YOUNG_WEIGHT * s.massKg))
            huntedByResidents[j][p] += (fromA / s.massKg + fromY / (YOUNG_WEIGHT * s.massKg)) * land.areaKm2[p]
            small.deposit(DeadPool.CARRION, p, KgKm2(kg * (1f - MEAT_SHARE)).perM2)
            ate += KgKm2(kg).perM2
            ateBook?.add(-(kg * MEAT_SHARE * area).toDouble())
        }
        // The herds' losses are counted out to them when the week's hunting is done (see [HerdAt]).
        if (herds != null) for ((i, at) in herds.withIndex()) {
            if (hA[i] + hY[i] <= 0f) continue
            val s = at.g.species
            val kg = want.v * (hA[i] + hY[i]) / total
            val j = slot(s)
            if (j >= 0) { huntSeen[j][HuntSeen.HERDS_KG.ordinal].add(((hA[i] + hY[i]) * area).toDouble()); huntSeen[j][HuntSeen.HERDS_TAKEN.ordinal].add((kg * area).toDouble()) }
            val fromA = kg * hA[i] / (hA[i] + hY[i]); val fromY = kg - fromA
            at.adults.add((fromA / s.massKg * area).toDouble()); at.young.add((fromY / (YOUNG_WEIGHT * s.massKg) * area).toDouble())
            small.deposit(DeadPool.CARRION, p, KgKm2(kg * (1f - MEAT_SHARE)).perM2)
            ate += KgKm2(kg).perM2
            ateBook?.add(-(kg * MEAT_SHARE * area).toDouble()); herdKillBook?.add((kg * area).toDouble())
        }
        // (Game takes fright at the kills, by their weight.)
        killed(p, Kg(want.v * area))
        return ate
    }
    /** The herds about each place this week, for the residents' hunters (see [huntResidents]); set by the groups before the residents' week. */
    @Transient var herdsAt: Map<Int, List<HerdAt>> = emptyMap()
        set(v) { field = v; herdsHere = if (v.isEmpty()) null else arrayOfNulls<List<HerdAt>>(n).also { for ((p, l) in v) it[p] = l } }
    /** [herdsAt] by place, for looking up on every place (null when there are none). */
    @Transient private var herdsHere: Array<List<HerdAt>?>? = null
    /** How much fear puts game off a place, 0..1. */
    fun afraid(p: Int) = fear[p] / (fear[p] + FEAR_HALF)
    /** The soil life and small animals the herds live among. */
    val small = HexSmallLife(hw, land, days)
    /** Each kind's sense of where better country lies. */
    val bearings = HexBearings(hw, kinds, { s, p, m ->
        val food = fareFor(s, p) * min(1.5f, suits(s, p)) / 1.5f
        saturate(food, Thresholds.FOOD_HALF) * tether(s, p) * monthFit(s, p, m)
    }, { s -> val k = slot(s); (0 until n).any { adults[k][it] > 0f } }, land.tuning)
    /**
     * Each kind's month births begin in each place, which drifts year by year towards the months
     * that feed mothers and young best; how well fed it was in each month lately (place × 12 + month).
     */
    val birthStart = Array(kinds.size) { k -> ByteArray(n) { kinds[k].birthMonth.toByte() } }
    private val fedByMonth = Array(kinds.size) { FloatArray(n * 12) { 0.7f } }
    /** Days of food each kind carries as fat in each place. */
    val reserve = Array(kinds.size) { k -> FloatArray(n) { 0.5f * kinds[k].maxFatDays } }
    /** For kinds that sleep through the winter: how well fed they went to sleep, 0..1. */
    private val autumnFed = Array(kinds.size) { FloatArray(n) { 0.8f } }
    /** Days each kind has gone without water in each place, as a herd keeps them (see [HexGroup.dry]): they mount week to week until it drinks. */
    val dry = Array(kinds.size) { FloatArray(n) }
    /** Life in the sea, lakes and rivers. */
    val water = HexWater(hw, land, days, small)
    /** Fruit, roots and straw as stocks. */
    val food = HexFood(land, small, days)
    /** Carcasses left this week in each place, kg (deaths other than kills, which the hunters count). */
    val carcassKg = FloatArray(n)
    /** Deaths this week by cause, animals ([WeekStats.residentDeaths]). */
    val deaths get() = land.stats.residentDeaths
    /** Where things died and dead matter went, for the viewer; null (and not kept) unless something reads it. */
    @Transient var record: PlaceRecord? = null
        set(v) { field = v; small.record = v }

    private val years = days / Date.DAYS_PER_YEAR.toFloat()
    /** How each kind travels between places. */
    @Transient private val travel = kinds.map { Catalogue.travel(it) }
    /** Young born this week, by kind (for the history). */
    @Transient val bornThisWeek = DoubleArray(kinds.size)

    init {
        // Start below what the land can carry, so that each kind grows into its country rather than
        // crashing out of it: half its usual density where its food grows, no denser where the ground
        // suits it best, and thinner where it is far from water or the climate is wrong for it.
        for ((k, s) in kinds.withIndex()) {
            // (A kind that follows the seasons starts as its herds, see [HexGroups].)
            if (s in Catalogue.migratory) continue
            val dens = if (s.startKm2PerAnimal > 0f) 1f / s.startKm2PerAnimal else 0.5f
            for (p in 0 until n) {
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
                if (now > 0f) { val f = want / now; for (p in 0 until n) { adults[k][p] *= f; young[k][p] *= f } }
            }
        }
    }

    /**
     * How well the climate of place [p] suits [s] over the year, 0..1: 1 where every month's normal
     * temperature is within what its coat bears, falling off with each degree beyond.
     */
    fun climateSuits(s: Species, p: Int): Float {
        var worst = 0f
        for (m in 0 until 12) worst = max(worst, s.discomfortC(feltNormalC(s, p, m), m))
        return exp(-worst / 5f)
    }

    /** How well the normal temperatures of month [m] and the next at place [p] suit [s]'s coat, 0..1. */
    fun monthFit(s: Species, p: Int, m: Int): Float {
        val m1 = wrapMonth(m + 1)
        return exp(-(s.discomfortC(feltNormalC(s, p, m), m) + s.discomfortC(feltNormalC(s, p, m1), m1)) / 10f)
    }

    private fun stepWater(weather: Weather) {
        val m = weather.date.month
        val k = 1f - exp(-days / WATER_LAG_DAYS)
        for (p in 0 until n) {
            val w = waterC[p]
            waterC[p] = if (w.isNaN()) waterNormalC[m][p] else w + (max(0f, weather.temperature[p]) - w) * k
        }
    }

    /**
     * The temperature [s] feels at place [p] with the air at [t] °C: a kind bound to water (hippos)
     * lies in it through the cold, as far as open water is at hand; ice shelters nothing.
     */
    fun feltC(s: Species, p: Int, t: Float): Float = if (s.maxWaterKm.isInfinite()) t else refugeC(s, t, waterC[p], tether(s, p))

    /** [feltC] in month [m] of an ordinary year, from the normals. */
    fun feltNormalC(s: Species, p: Int, m: Int): Float =
        if (s.maxWaterKm.isInfinite()) hw.placeTemp(p, m) else refugeC(s, hw.placeTemp(p, m), waterNormalC[m][p], usualTether(s, p))

    private fun refugeC(s: Species, t: Float, water: Float, share: Float): Float =
        if (water.isNaN() || water <= 0f || water <= t) t else t + share * (water - t)

    /** Share of [s]'s diet that is plants. */
    fun plantShare(s: Species): Float {
        val d = s.diet
        val all = d.grass + d.browse + d.fruit + d.roots + d.meat + d.smallPrey + d.carrion
        return if (all <= 0f) 1f else (d.grass + d.browse + d.fruit + d.roots) / all
    }

    /** Of the kinds that can hibernate, those asleep in their winter dens, by place (null for the kinds that cannot; see [sleepOrWake]). */
    val sleeping = Array(kinds.size) { k -> if (kinds[k].hibernates) BooleanArray(n) else null }

    /** Whether kind [k] is asleep in its winter den in place [p] this week (see [sleepOrWake]). */
    fun asleep(k: Int, p: Int) = sleeping[k]?.get(p) ?: false

    /**
     * Sleeping is a way through a lean winter, not a rule of the calendar: kinds that can hibernate lie up in their dens
     * where it is cold and they went hungry last week, with fat to sleep on, and sleep on while the cold lasts and their
     * fat holds out. Well fed, they stay awake.
     */
    private fun sleepOrWake(weather: Weather) {
        for ((k, z) in sleeping.withIndex()) {
            if (z == null) continue
            val f = fed[k]; val r = reserve[k]
            for (p in 0 until n) z[p] = weather.temperature[p] < HIBERNATE_C && r[p] > 0f && (z[p] || f[p] < Thresholds.HUNGRY)
        }
    }

    /**
     * Once a year: in each place, a kind's births move a month earlier or later where that puts them
     * in better-fed months for the mothers and the young (the birth month and the two after it).
     */
    private fun shiftBreeding() {
        for ((k, s) in kinds.withIndex()) {
            val fm = fedByMonth[k]; val start = birthStart[k]
            for (p in 0 until n) {
                if (adults[k][p] <= 0f) continue
                fun window(m0: Int) = (0 until 3).sumOf { fm[p * 12 + wrapMonth(m0 + it)].toDouble() }
                val m = start[p].toInt()
                val now = window(m); val earlier = window(m - 1); val later = window(m + 1)
                if (earlier > now + BREED_SHIFT_GAIN && earlier >= later) start[p] = wrapMonth(m - 1).toByte()
                else if (later > now + BREED_SHIFT_GAIN) start[p] = wrapMonth(m + 1).toByte()
            }
        }
    }

    /**
     * Shares of [s]'s adults and young ([young]) that die this week in place [p] of fire, flood and
     * deep snow: fire catches the slow and the young on the share of the place that burns; a high
     * flood drowns some on the floodplain (not the kinds that live in the water); snow far deeper
     * than a kind can dig through starves and exhausts it.
     */
    fun calamities(s: Species, p: Int, weather: Weather, young: Boolean): Calamities {
        val fire = land.fire.burning[p] * (if (young) FIRE_YOUNG else FIRE_ADULT)
        val flood = if (s.maxWaterKm.isFinite() || land.hydro.flood[p] <= FLOOD_DROWN_M) 0f
            else land.floodShare[p] * (if (young) FLOOD_YOUNG else FLOOD_ADULT)
        val over = weather.snowpack[p] / s.snowLimitMm
        val snow = if (over <= 2f) 0f else min(0.1f, SNOW_DEATHS * (over - 2f)) * (if (young) 3f else 1f)
        // Grazing in fresh ash wears teeth and poisons with fluorine: counted with the snow, as a smothered winter.
        val ash = if (land.plants.ash[p] < ASH_HARM_MM) 0f else min(0.1f, ASH_DEATHS * land.plants.ash[p] / 50f) * (if (young) 2f else 1f)
        return Calamities(fire, flood, snow + ash)
    }

    /**
     * How well place [p] suits [s] by the ground it prefers, about 0.2..2.5: ibex the crags, roe deer
     * cover, boar wet thickets, aurochs open ground; 1 for a kind with no preference.
     */
    fun suits(s: Species, p: Int): Float {
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

    /** For a kind's liking for rough ground [rugged] (see [Habitat]): how well each place's ground suits it on that alone, the mean over its ground. */
    @Transient private var ruggedFits: ConcurrentHashMap<Float, FloatArray>? = ConcurrentHashMap()
    /** [ruggedFit] by kind ([Species.index]), as first asked for. */
    @Transient private var ruggedFitOf: Array<FloatArray?>? = null
    private fun ruggedFit(rugged: Float): FloatArray = (ruggedFits ?: ConcurrentHashMap<Float, FloatArray>().also { ruggedFits = it }).getOrPut(rugged) {
        val q = land.ruggedSpread
        FloatArray(n) { p -> var v = 0f; for (k in q.indices) v += exp(HABITAT * rugged * (q[k][p] - 0.3f)); v / q.size }
    }

    /**
     * Share of place [p]'s food that [s] can use for its water: grazers that must drink every day or
     * two graze out from water and back, and leave the far country to those that live on the water
     * in their food (gazelle, oryx, camels). Kinds bound to water (hippos) use only what lies by it.
     */
    fun tether(s: Species, p: Int): Float = tetherAt(s, p, land.drink.animalWater(p), land.drink.animalWaterKm(p))

    /** [tether] in an ordinary year: from the lasting water around, not this week's. */
    fun usualTether(s: Species, p: Int): Float = tetherAt(s, p, land.drink.nearRiver[p] + land.drink.nearLake[p] + land.drink.nearSpring[p],
        land.drink.waterKmMean[p])

    private fun tetherAt(s: Species, p: Int, water: Float, waterKm: Float): Float {
        if (s.maxWaterKm.isFinite()) return min(1f, water)
        val reachKm = s.dailyTravelKm * s.daysWithoutWater / 4f * (1f + 10f * s.waterFromFood * s.waterFromFood)
        val r = waterKm / reachKm
        return 1f / (1f + r * r)
    }

    /**
     * Food for [s] in place [p] from its diet, as standing plants, kg/m²: grass from herbs, and the
     * browse (shoots), fruit and roots stocks (counted as the standing plants that would give as much
     * in a week); less on cliffs it cannot climb.
     */
    /**
     * What place [p] offers kind [s] to live on, on [foodFor]'s scale: its plant food, and for the residents' hunters
     * the game, small prey and carrion there too, each by its share of the diet. (Game counts as plant food would
     * where it is [PREY_HALF] kg per km² against [Thresholds.FOOD_HALF], so that both weigh alike in the kind's bearings.)
     */
    fun fareFor(s: Species, p: Int): Float {
        if (!s.carnivore) return foodFor(s, p)
        val d = s.diet
        val plants = d.grass + d.browse + d.fruit + d.roots; val animal = d.meat + d.smallPrey + d.carrion
        if (plants + animal <= 0f) return 0f
        val kg = (d.meat * (preyNow?.get(slot(s))?.get(p) ?: preyFor(s, p)) + d.smallPrey * small.smallPreyKg(p) + d.carrion * KgM2(small.carrion[p]).perKm2.v) / animal.coerceAtLeast(1e-6f)
        return (animal * kg * Thresholds.FOOD_HALF / PREY_HALF + plants * foodFor(s, p)) / (plants + animal)
    }

    /** Game a resident hunter [s] takes at place [p], kg per km²: residents and herds of its prey's size ([huntResidents]). */
    private fun preyFor(s: Species, p: Int): Float {
        var v = 0f
        for ((j, ps) in kinds.withIndex()) {
            if (ps.carnivore) continue
            v += adults[j][p] * ps.massKg * s.preySize(ps.massKg)
            v += young[j][p] * YOUNG_WEIGHT * ps.massKg * s.preySize(YOUNG_WEIGHT * ps.massKg)
        }
        herdsHere?.get(p)?.let { for (at in it) { val ps = at.g.species
            v += at.g.adults * at.share / land.areaKm2[p] * ps.massKg * s.preySize(ps.massKg)
            v += at.g.young * at.share / land.areaKm2[p] * YOUNG_WEIGHT * ps.massKg * s.preySize(YOUNG_WEIGHT * ps.massKg) } }
        return v
    }


    /** The game each resident hunter takes at each place ([preyFor]) while the residents move; null otherwise. */
    @Transient private var preyNow: Array<FloatArray?>? = null

    /** How much fear of hunters puts kind [s] off place [p], 0..1: none for the hunters themselves. */
    private fun shy(s: Species, p: Int) = if (s.carnivore) 0f else afraid(p)

    fun foodFor(s: Species, p: Int): Float {
        val d = s.diet
        val total = d.grass + d.browse + d.fruit + d.roots
        if (total <= 0f) return 0f
        val plants = land.palatable[p] * d.grass * land.plants.herb[p] +
            (d.browse * land.plants.browse(p) + d.fruit * food.fruit[p] + d.roots * food.roots[p]) / REACH
        return plants / total * HexFood.access(s, land.crag[p])
    }

    fun count(k: Int): Double = (0 until n).sumOf { ((adults[k][it] + young[k][it]) * land.areaKm2[it]).toDouble() }

    /**
     * Newcomers: once a year a resident kind that has dwindled below [NEWCOMER_ANIMALS] (or died out)
     * gets a few animals at places that suit it, as if wandering in from beyond the map; whether they
     * last is up to their niche. Only in training's play worlds ([newcomersOn]).
     */
    var newcomersOn = false
    private fun newcomers(date: Date) {
        if (!newcomersOn) return
        val r = kotlin.random.Random((date.absoluteDay * 7919L + 13) xor seedSalt)
        for (k in kinds.indices) {
            // (A kind that follows the seasons gets its newcomers as herds.)
            if (count(k) >= NEWCOMER_ANIMALS || kinds[k] in Catalogue.migratory) continue
            val s = kinds[k]
            val w = FloatArray(n) { p -> land.areaKm2[p] * suits(s, p) * climateSuits(s, p) }
            val total = w.sum(); if (total <= 0f) continue
            repeat(NEWCOMER_PLACES) {
                var u = r.nextFloat() * total; var p = 0
                while (p < n - 1 && u > w[p]) { u -= w[p]; p++ }
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
            // (The odds are weighed once, before the bands shift: the shift and the spill both go by them, and the groups every week.)
            // They move every [Tuning.residentMoveWeeks] weeks, each move taking what those weeks' would: from week to week
            // they shift by a few percent.
            val moving = (it.date.absoluteDay / days) % tuning.residentMoveWeeks == 0L
            Profile.part("residents: weighing places") { fillArrive(it, moving) }
            if (moving) {
                // (The game the hunters weigh, taken once before any kind moves: the kinds move on all cores at once.)
                preyNow = Array(kinds.size) { k -> if (kinds[k].carnivore) FloatArray(n).also { v -> IntStream.range(0, n).parallel().forEach { p -> v[p] = preyFor(kinds[k], p) } } else null }
                Profile.part("residents: moving up and down the slopes") { shiftBands(it) }
                Profile.part("residents: spreading to neighbouring hexes") { spill(it) }
                preyNow = null
            }
        },
        Phase("soil and small life") { small.step(it, carcassKg) },
    )

    /** The week's start: the books' running sums, fear fading, outbreaks, a new month's newcomers and sense of direction, and the water. */
    private fun newWeek(weather: Weather, date: Date) {
        val books = land.books
        ateBook = books?.adder(Books.Stuff.MATTER, "eaten"); droppedBook = books?.adder(Books.Stuff.MATTER, "dung and frass")
        herdKillBook = books?.adder(Books.Stuff.MATTER, "herds killed by residents (from the groups)")
        for (p in 0 until n) fear[p] *= FEAR_KEEP
        Profile.part("outbreaks") { stepOutbreaks() }; outbreakSalt++
        carcassKg.fill(0f)
        if (bearings.month != date.month) {
            if (date.month == 0) newcomers(date)
            Profile.time("sense of direction") { bearings.update(date.month) }
            if (date.month == 3) shiftBreeding()
        }
        stepWater(weather)
    }

    /** Names the residents' stocks (the young at [YOUNG_WEIGHT] of a grown animal), with the food stocks and the small life. */
    internal fun listStocks(stocks: Stocks) {
        stocks.within("resident animals") { for ((k, s) in kinds.withIndex())
            stocks.add(Books.Stuff.MATTER, "resident ${s.name}", adults[k], young[k]) { var sum = 0.0; for (p in 0 until n) sum += (adults[k][p] + YOUNG_WEIGHT * young[k][p]) * land.areaKm2[p] * s.massKg.toDouble(); sum } }
        food.listStocks(stocks)
        small.listStocks(stocks)
    }

    /**
     * The grass and browse, kg/m², that can still be eaten at each place this week without eating it
     * bare, once the residents have had theirs: the herds passing through share it (see [HexGroups.graze]).
     */
    val grazeLeft = FloatArray(n)
    val browseLeft = FloatArray(n)

    /**
     * Every kind takes its share of the place's grass, browse, fruit and roots, in proportion to
     * what each wants and can get at (cliffs keep all but climbers off part of it); a shortfall of
     * grass and browse is made up from the standing straw, at what each kind's gut gets out of it.
     */
    private fun graze(weather: Weather) {
        sleepOrWake(weather)
        // (The week's kill rates are made afresh here: the odds worked out from last week's are forgotten.)
        killOdds = AtomicReferenceArray(Catalogue.all.size)
        // (Where the food came from, tallied on each core apart and added into [fedFrom] after.)
        val tallies = java.util.concurrent.ConcurrentLinkedQueue<DoubleArray>()
        val tally = ThreadLocal.withInitial { DoubleArray(kinds.size * FED).also { tallies.add(it) } }
        IntStream.range(0, n).parallel().forEach { p ->
            val ff = tally.get()
            // How much each kind eats of poor forage depends on its gut (see [gutFill]).
            val quality = land.plants.nourishment(p)
            val need = FloatArray(kinds.size); val each = FloatArray(kinds.size)
            // How much of the place's food each kind can get at: cliffs keep all but climbers off some of
            // it, and far from water a kind that must drink often uses only the ground within reach of it.
            val avail = FloatArray(kinds.size)
            for ((k, s) in kinds.withIndex()) {
                killRate[k]?.set(p, 0f)
                val heads = adults[k][p] + 0.5f * young[k][p]
                // Bears asleep through the cold eat nothing.
                // (Only the plant part of the diet here; omnivores' small prey and carrion below.)
                // (More in the cold, see [coldNeed].)
                each[k] = plantWant(k, p, weather)
                need[k] = heads * each[k]
                // (Biting insects harassing them cost them feeding time.)
                avail[k] = reachOf(s, p)
            }
            // What can be eaten this week without eating the place bare, kg per m2: green or cured, standing grass is
            // grazed alike (the steppe's herds live through the dry season on its standing hay). What makes the dry
            // season lean is the hay itself: poor in nourishment and protein, weathering away and not growing back
            // until the rains. Supply against demand:
            val reach = REACH
            // (Only the edible share of the plants, see [HexLand.palatable].)
            val grass = reach * land.palatable[p] * land.plants.herb[p]; val browse = land.plants.browse(p)
            // (Browse is the shoots the shrubs and trees put out within reach, see [HexPlants.shrubShoots].)
            // Each kind in turn by body mass eats by the herds' rule ([forage]), as far as it can get at what the bigger ones
            // left: where food is short the bigger kinds drive the smaller off it (a hippo or an aurochs gives way to nothing
            // smaller). What fell short it makes up from the other foods (diet switching), then with the standing straw; with
            // food to spare and reserves short, it eats more grass and browse and lays the surplus on, as a herd does.
            var spareG = grass; var spareB = browse; var spareF = food.fruit[p]; var spareR = food.roots[p]; var spareS = food.straw[p]
            var access = 0f
            val pantry = object : Pantry {
                override fun grass(kg: Float): Float { val t = min(kg, access * spareG); spareG -= t; return t }
                override fun browse(kg: Float): Float { val t = min(kg, access * spareB); spareB -= t; return t }
                override fun fruit(kg: Float): Float { val t = min(kg, access * spareF); spareF -= t; return t }
                override fun roots(kg: Float): Float { val t = min(kg, access * spareR); spareR -= t; return t }
                override fun straw(kg: Float): Float { val t = min(kg, spareS); spareS -= t; return t }
            }
            val b = Bites(); val dry = DryPantry()
            // (Of each kind's need: what it got of plants, and of straw; what it ate beyond, laid on.)
            val got = FloatArray(kinds.size); val strawShare = FloatArray(kinds.size); val surplus = FloatArray(kinds.size)
            var eatG = 0f; var eatB = 0f; var eatF = 0f; var eatR = 0f; var eatS = 0f
            // (What each kind ate this week, kg per m2, and the protein in it: fresh growth full, cured grass and straw
            // little, as for the herds; see [proteinMet]. What it ate to fatten is left out, as for the herds.)
            val ateKg = FloatArray(kinds.size); val proteinKg = FloatArray(kinds.size)
            val gp = grassProtein(p); val bp = browseProtein(p)
            for (k in byMass) {
                val s = kinds[k]
                val want = need[k]
                if (want <= 0f) continue
                val d = s.diet
                val underSnow = grassUnderSnow(s, weather.snowpack[p])
                access = avail[k]
                forage(s, d, quality, want, underSnow, avail[k], pantry, b)
                val strawGot = min(b.short, b.straw * HexFood.roughage(s))
                got[k] = (b.got - strawGot) / want; strawShare[k] = strawGot / want
                var grass3 = 0f; var browse3 = 0f
                if (b.got >= want && reserve[k][p] < s.maxFatDays) {
                    val extra = SURPLUS_EAT * want
                    if (d.grass > 0f) grass3 = pantry.grass(underSnow * extra / quality * d.grass / (d.grass + d.browse).coerceAtLeast(1e-6f))
                    if (d.browse > 0f) browse3 = pantry.browse(extra / quality * d.browse / (d.grass + d.browse).coerceAtLeast(1e-6f))
                    surplus[k] = (grass3 + browse3) * quality / want
                }
                eatG += b.grass + b.grass2 + grass3; eatB += b.browse + b.browse2 + browse3
                eatF += b.fruit + b.fruit2; eatR += b.roots + b.roots2; eatS += b.straw
                ateKg[k] = b.eaten; proteinKg[k] = protein(b, gp, bp, tuning.proteinStraw)
            }
            // How well a band of each plant-eater coming here would be fed on what is left, eating by the same rule ([forage]):
            // its diet, switching and the straw as far as its gut holds them, so poor food feeds it no better than it feeds
            // those already here.
            spareGrass[p] = max(0f, spareG); spareBrowse[p] = max(0f, spareB); spareFruit[p] = max(0f, spareF)
            spareRoots[p] = max(0f, spareR); spareStraw[p] = max(0f, spareS)
            for ((k, s) in kinds.withIndex()) {
                val r = room[k] ?: continue
                r[p] = fedOnSpare(s, p, s.maxGroupSize.toFloat(), each[k], avail[k], weather.snowpack[p], dry, b)
            }
            for ((k, s) in kinds.withIndex()) {
                val strawPart = strawShare[k]
                // (And how rich in protein all it ate was, straw included; animal food is rich in it.)
                val eaten = ateKg[k]
                if (eaten > 0f) {
                    val ps = plantShare(s)
                    protein[k][p] += (proteinMet(ps * proteinKg[k] / eaten + (1f - ps)) - protein[k][p]) * PROTEIN_MEMORY
                }
                val sleeping = asleep(k, p)
                var f = if (sleeping) {
                    // Asleep, it lives on its fat at a quarter of its waking need; fed as far as the fat lasts.
                    val want = SLEEP_NEED * days
                    val draw = min(reserve[k][p], want)
                    reserve[k][p] -= draw
                    draw / want
                } else {
                    // Omnivores and hunters: the rest of the diet is game they kill, small animals they catch
                    // and carrion they find. Each food is sought for its share first; what falls short, of
                    // these or of the plants, is made up from whichever of them is to be had (as a pack does).
                    val ps = plantShare(s)
                    var animal = 0f
                    val needAll = (adults[k][p] + 0.5f * young[k][p]) * s.dailyIntakeKg * coldNeed(s, feltC(s, p, weather.temperature[p]), weather.date.month, tuning) *
                        days / 1e6f // all of the diet, kg per m2
                    if (ps < 1f && needAll > 0f) {
                        val d = s.diet; val all = d.grass + d.browse + d.fruit + d.roots + d.meat + d.smallPrey + d.carrion
                        val animalNeed = needAll * (1f - ps)
                        val plantGap = if (ps > 0f) needAll * ps * max(0f, 1f - got[k] - strawPart) else 0f
                        // Carrion first, where it eats any: a carcass lying there costs no chase and no digging, so
                        // it eats what it finds (at most half of what lies there) before it hunts or makes up plants.
                        var gotCarrion = if (d.carrion > 0f) small.scavenge(p, KgM2(min(animalNeed + plantGap, 0.5f * small.carrion[p]))).v else 0f
                        var left = animalNeed + plantGap - gotCarrion
                        val killedSoFar = FloatArray(1)
                        var gotMeat = if (left > 0f) huntResidents(k, p, KgM2(min(left, needAll * d.meat / all) * avail[k]), killedSoFar).v else 0f
                        left -= gotMeat
                        var gotSmall = if (left > 0f) smallFood(k, p, KgM2(min(left, needAll * d.smallPrey / all) * avail[k]), weather).v else 0f
                        var gap = left - gotSmall
                        // (The shortfall is made up first from the food it mostly lives on, then from the others,
                        // each to at most (1 + SWITCH) times its usual share, as with the plants.)
                        val most = (1f + SWITCH) * needAll / all
                        fun share(food: AnimalFood) = when (food) { AnimalFood.MEAT -> d.meat; AnimalFood.SMALL_PREY -> d.smallPrey; AnimalFood.CARRION -> d.carrion }
                        val order = AnimalFood.entries.filter { share(it) > 0f }.sortedByDescending { share(it) }
                        for (food in order) {
                            if (gap <= 0f) break
                            val x = when (food) {
                                AnimalFood.MEAT -> min(gap, most * d.meat - gotMeat).let { if (it > 0f) huntResidents(k, p, KgM2(it * avail[k]), killedSoFar).v else 0f }
                                AnimalFood.SMALL_PREY -> min(gap, most * d.smallPrey - gotSmall).let { if (it > 0f) smallFood(k, p, KgM2(it * avail[k]), weather).v else 0f }
                                AnimalFood.CARRION -> min(gap, most * d.carrion - gotCarrion).let { if (it > 0f) small.scavenge(p, KgM2(min(it, 0.5f * small.carrion[p]))).v else 0f }
                            }
                            when (food) { AnimalFood.MEAT -> gotMeat += x; AnimalFood.SMALL_PREY -> gotSmall += x; AnimalFood.CARRION -> gotCarrion += x }
                            gap -= x
                        }
                        // With its need met and reserves short, it eats more and lays the surplus on (as from plants).
                        if (gap <= 0f && reserve[k][p] < s.maxFatDays) {
                            var more = SURPLUS_EAT * animalNeed
                            val moreMeat = min(more, most * d.meat - gotMeat)
                            if (moreMeat > 0f) { val x = huntResidents(k, p, KgM2(moreMeat * avail[k]), killedSoFar).v; surplus[k] += x / needAll; more -= x }
                            val moreSmall = min(more, most * d.smallPrey - gotSmall)
                            if (moreSmall > 0f) { val x = smallFood(k, p, KgM2(moreSmall * avail[k]), weather).v; surplus[k] += x / needAll; more -= x
                                ateBook?.add(-x * land.areaM2[p].toDouble()) }
                        }
                        animal = (gotMeat + gotSmall + gotCarrion) / animalNeed
                        small.deposit(DeadPool.DUNG, p, KgM2(HexSmallLife.DUNG * (gotMeat + gotSmall + gotCarrion)))
                        if (ateBook != null) { val m2 = land.areaM2[p].toDouble()
                            ateBook!!.add(-(gotSmall + gotCarrion) * m2); droppedBook!!.add(HexSmallLife.DUNG * (gotMeat + gotSmall + gotCarrion) * m2) }
                    }
                    val heads = adults[k][p] + 0.5f * young[k][p]
                    if (heads > 0f) {
                        val i = k * FED
                        ff[i + Fed.PLANTS.ordinal] += (min(1f, got[k]) * ps * heads).toDouble(); ff[i + Fed.STRAW.ordinal] += (strawPart * ps * heads).toDouble()
                        ff[i + Fed.ANIMAL.ordinal] += (min(1f, animal) * (1f - ps) * heads).toDouble(); ff[i + Fed.HEADS.ordinal] += heads.toDouble()
                    }
                    // (Animal food beyond its share makes up for plants that fell short.)
                    ((got[k] + strawPart) * ps + animal * (1f - ps)).coerceIn(0f, 1f)
                }
                // Reserves carry them through lean weeks; they are laid on from what was eaten beyond need.
                val r = reserve[k]
                if (!sleeping) {
                    if (f < WELL_FED) { val draw = min(r[p], (1f - f) * days); r[p] -= draw; f = min(1f, f + draw / days)
                        val heads = adults[k][p] + 0.5f * young[k][p]; if (heads > 0f) ff[k * FED + Fed.RESERVES.ordinal] += (draw / days * heads).toDouble() }
                    else r[p] = min(s.maxFatDays, r[p] + FAT_EFFICIENCY * surplus[k] * days)
                }
                fed[k][p] = f
                val fm = fedByMonth[k]; val i = p * 12 + weather.date.month
                // (What the young would be born to: food, and the protein their mothers' milk and their growth need.)
                fm[i] += (fed[k][p] * protein[k][p] - fm[i]) * FED_MEMORY
                // Laying on fat for the winter: how well fed through late summer and autumn.
                if (s.hibernates && weather.date.month in 7..11 && !asleep(k, p)) autumnFed[k][p] += (fed[k][p] - autumnFed[k][p]) * 0.2f
            }
            // What is left of the week's grazing and browsing for the herds that come through after.
            grazeLeft[p] = max(0f, grass - eatG); browseLeft[p] = max(0f, browse - eatB)
            val dug = food.take(food.roots, p, KgM2(eatR)).v
            val ate = food.take(food.fruit, p, KgM2(eatF)).v
            val eaten = land.plants.consume(p, LifeForm.HERB, KgM2(eatG)).v +
                land.plants.eatBrowse(p, KgM2(eatB)).v +
                ate + dug + food.take(food.straw, p, KgM2(eatS)).v
            // Fruit-eaters drop the seed where they go: shrubs and trees come up away from any parent.
            land.plants.animalSeed[p] = min(1f, land.plants.animalSeed[p] * SEED_KEEP + ate * 1e6f / SEED_KG)
            // Their feet beat down the plants and wear paths: tonnes of animal going about the place.
            var tonnes = 0f
            for ((k, s) in kinds.withIndex()) tonnes += (adults[k][p] + young[k][p]) * s.massKg / 1000f
            land.plants.trample(p, RESIDENT_TRAMPLE * tonnes * land.areaKm2[p])
            // (And their going about it each day steps on the ground: see [HexPlants.tread]. Heads are per km².)
            for ((k, s) in kinds.withIndex()) land.plants.tread(p, (adults[k][p] + young[k][p]) * land.areaKm2[p], s.massKg, s.dailyTravelKm * days)
            small.deposit(DeadPool.DUNG, p, KgM2(HexSmallLife.DUNG * eaten))
            if (ateBook != null) { val m2 = land.areaM2[p].toDouble(); ateBook!!.add(-eaten * m2); droppedBook!!.add(HexSmallLife.DUNG * eaten * m2) }
            land.plants.grazing[p] = eatG * 1e6f / days; land.plants.browsing[p] = eatB * 1e6f / days
            // Rooting churns the ground: kg of roots dug per km2 a day.
            land.plants.rooting[p] = dug * 1e6f / days
        }
        for (t in tallies) for (k in kinds.indices) for (f in 0 until FED) fedFrom[k][f].add(t[k * FED + f])
    }

    private fun breedAndDie(weather: Weather, date: Date) {
        val stepsPerYear = Date.DAYS_PER_YEAR / days.toFloat()
        val dead = Array(kinds.size) { DoubleArray(ResidentDeath.entries.size) }
        bornThisWeek.fill(0.0)
        // Each kind on its own core, with its own carcasses, added up after.
        val carcasses = Array(kinds.size) { FloatArray(n) }
        val bornBook = land.books?.adder(Books.Stuff.MATTER, "residents born and grew up")
        // A kind that lives both ways is crowded by all of its animals about a place, its herds' as well as its own:
        // each herd's heads per km2 over the ground it grazes ([herdsAt]).
        val herdKm2 = arrayOfNulls<FloatArray>(kinds.size)
        for ((p, list) in herdsAt) for (at in list) {
            val k = slot(at.g.species); if (k < 0) continue
            (herdKm2[k] ?: FloatArray(n).also { herdKm2[k] = it })[p] += at.g.size * at.share / land.areaKm2[p]
        }
        kinds.indices.toList().parallelStream().forEach { k ->
            val s = kinds[k]
            val carcassKg = carcasses[k]
            val herds = herdKm2[k]
            val start = birthStart[k]
            val stepsInSeason = s.birthMonths * Date.DAYS_PER_MONTH / days.toFloat()
            val grow = years / s.yearsToMaturity
            val a = adults[k]; val y = young[k]; val f = fed[k]; val dk = dry[k]
            val grown = grownUp[k]; grown?.fill(0f)
            val hz = FloatArray(HZ_COUNT)
            for (p in 0 until n) {
                if (a[p] + y[p] <= 0f) { dk[p] = 0f; continue }
                val area = land.areaKm2[p]
                // Births: half the adults are mothers; a mother bears what her food and reserves can build.
                val inSeason = monthsFrom(start[p].toInt(), date.month) < s.birthMonths
                if (inSeason) {
                    val due = dueYoung(k, a[p] + y[p] + (herds?.get(p) ?: 0f), protein[k][p], stepsInSeason)
                    val born = 0.5f * a[p] * due * breeding(s, due, reserve[k][p], f[p], days)
                    y[p] += born
                    bornThisWeek[k] += (born * area).toDouble()
                    bornBook?.add((born * area * YOUNG_WEIGHT * s.massKg).toDouble())
                    // The young are made of what their mothers ate: what the week's food did not build comes off the mothers' reserves (as in groups).
                    if (born > 0f && a[p] > 0f) reserve[k][p] = max(0f, reserve[k][p] - fromReserves(s, born / (0.5f * a[p]), f[p], days))
                }
                // Deaths.
                // Thirst by the herds' rule: an animal drinks where water is within half its days without
                // water's going (or gets enough from green food and prey); beyond that, its days without water
                // mount week to week, and past what it can bear they kill as they do a herd.
                if (drinks(s, p)) dk[p] = 0f
                else dk[p] += Hazards.dryDays(s, days, f[p], land.plants.green[p])
                hazards(k, p, weather, date.month, f[p], dk[p], a[p] + y[p] + (herds?.get(p) ?: 0f), hz)
                val hunger = hz[HZ_HUNGER]; val cold = hz[HZ_COLD]; val thirst = hz[HZ_THIRST]; val disease = hz[HZ_DISEASE]
                val crowd = hz[HZ_CROWD]
                // Where the herds are crowded, now and then a plague breaks out among them.
                if (crowd > 0f && hash(p, k) < OUTBREAK_START * crowd * crowd * years) startOutbreak(s, hw.placeHex[p])
                val fireA = hz[HZ_FIRE_A]; val floodA = hz[HZ_FLOOD_A]; val snowA = hz[HZ_SNOW_A]
                val fireY = hz[HZ_FIRE_Y]; val floodY = hz[HZ_FLOOD_Y]; val snowY = hz[HZ_SNOW_Y]
                val adultOdds = (s.backgroundMortality * years + hunger + cold + thirst + disease + fireA + floodA + snowA).coerceIn(0f, 0.9f)
                val eagle = eagleOdds(s, p); val croc = crocodileOdds(p, weather)
                val youngOdds = youngOdds(s, hz, eagle, croc, protein[k][p])
                val da = a[p] * adultOdds; val dy = y[p] * youngOdds
                dead[k][ResidentDeath.AGE] += (a[p] * s.backgroundMortality * years + y[p] * s.youngMortality * years) * area
                dead[k][ResidentDeath.HUNGER] += ((a[p] + 1.5f * y[p]) * hunger) * area
                dead[k][ResidentDeath.COLD] += ((a[p] + 1.5f * y[p]) * cold) * area
                dead[k][ResidentDeath.DISEASE] += ((a[p] + y[p]) * disease) * area
                dead[k][ResidentDeath.EAGLES] += y[p] * eagle * area
                dead[k][ResidentDeath.FIRE] += (a[p] * fireA + y[p] * fireY) * area
                dead[k][ResidentDeath.FLOOD] += (a[p] * floodA + y[p] * floodY) * area
                dead[k][ResidentDeath.SNOW] += (a[p] * snowA + y[p] * snowY) * area
                dead[k][ResidentDeath.CROCODILES] += y[p] * croc * area
                dead[k][ResidentDeath.THIRST] += ((a[p] + 1.5f * y[p]) * thirst) * area
                record?.let { r ->
                    val kg = s.massKg * area
                    r.resident(k, p, PlaceRecord.Cause.HUNGER, (a[p] + 1.5f * y[p]) * hunger * kg)
                    r.resident(k, p, PlaceRecord.Cause.THIRST, (a[p] + 1.5f * y[p]) * thirst * kg)
                    r.resident(k, p, PlaceRecord.Cause.COLD, ((a[p] + 1.5f * y[p]) * cold + a[p] * snowA + y[p] * snowY) * kg)
                    r.resident(k, p, PlaceRecord.Cause.ILLNESS, (a[p] + y[p]) * disease * kg)
                    r.resident(k, p, PlaceRecord.Cause.KILLED, y[p] * (eagle + croc) * kg)
                    r.resident(k, p, PlaceRecord.Cause.OTHER, (a[p] * s.backgroundMortality * years + y[p] * s.youngMortality * years +
                        a[p] * (fireA + floodA) + y[p] * (fireY + floodY)) * kg)
                }
                a[p] -= da; y[p] -= dy
                carcassKg[p] += (da + 0.3f * dy) * area * s.massKg
                // Growing up.
                val up = y[p] * grow
                y[p] -= up; a[p] += up
                if (grown != null) grown[p] = up
                bornBook?.add((up * area * (1f - YOUNG_WEIGHT) * s.massKg).toDouble())
                if (a[p] + y[p] < EXTINCT) { carcassKg[p] += (a[p] + 0.3f * y[p]) * area * s.massKg; a[p] = 0f; y[p] = 0f }
            }
        }
        for (c in carcasses) for (p in 0 until n) carcassKg[p] += c[p]
        land.books?.let { b -> var sum = 0.0; for (p in 0 until n) sum += carcassKg[p]; b.flow(Books.Stuff.MATTER, "carcasses (to the soil)", -sum) }
        record?.foldResidents()
        for ((k, s) in kinds.withIndex()) {
            // (Summed place by place in order, so that a run is the same on any number of cores.)
            deaths[Tally.of(s, DeathCause.RESIDENT_HUNTERS)] = huntedByResidents[k].let { h ->
                var sum = 0.0
                for (p in h.indices) {
                    sum += h[p]
                    record?.let { it.animalKg[PlaceRecord.Cause.KILLED.ordinal][p] += h[p] * s.massKg }
                    h[p] = 0f
                }
                sum
            }
            for (c in ResidentDeath.entries) deaths[Tally.of(s, c.cause)] = dead[k][c]
        }
    }

    /**
     * What kills kind [k]'s animals at place [p] this week besides old age, as shares of them ([HZ_HUNGER] and the rest
     * into [out]): hunger fed [fed], cold and heat, thirst [dry] days without water, disease among [heads] of its kind per
     * km² (and a raging outbreak), fire, flood and deep snow, for the grown and the young, and the hunters
     * ([huntedOdds]). The one rule residents die by ([breedAndDie], which takes the hunters' kills as they make
     * them) and weigh a place to stay or leave by ([stayOdds], [arriveOdds]).
     */
    internal fun hazards(k: Int, p: Int, weather: Weather, month: Int, fed: Float, dry: Float, heads: Float, out: FloatArray) {
        val s = kinds[k]
        // (Hunger, cold and heat, and thirst kill residents by the same rules as herds and packs.)
        val sleeping = asleep(k, p)
        out[HZ_HUNGER] = Hazards.hunger(fed)
        out[HZ_COLD] = if (sleeping) 0f else Hazards.cold(s, feltC(s, p, weather.temperature[p]), month)
        out[HZ_THIRST] = Hazards.thirst(s, dry)
        // Crowding breeds disease and parasites, far more among the underfed: herds grow until the
        // lean season binds, and then hunger and sickness together hold them.
        val crowd = heads / (CROWDED * usual[k])
        out[HZ_CROWD] = crowd
        out[HZ_DISEASE] = Hazards.crowdDisease(crowd, fed, years) + outbreakDeaths(s, p)
        val (fireA, floodA, snowA) = calamities(s, p, weather, false)
        val (fireY, floodY, snowY) = calamities(s, p, weather, true)
        out[HZ_FIRE_A] = fireA; out[HZ_FLOOD_A] = floodA; out[HZ_SNOW_A] = if (sleeping) 0f else snowA
        out[HZ_FIRE_Y] = fireY; out[HZ_FLOOD_Y] = floodY; out[HZ_SNOW_Y] = if (sleeping) 0f else snowY
        // (And the hunters, resident and roaming, as they hunted there lately; [breedAndDie] takes their kills as they make them.)
        out[HZ_HUNTED] = huntedOdds(k, p, false)
    }

    /**
     * The young a mother of kind [k] is due this step of the [stepsInSeason] steps of her season, among [heads] of her kind
     * per km², fed [protein] of her need for it: mothers short of protein bear and suckle fewer, and a mother needs a range
     * of her own to bear and raise her young in (where they crowd beyond [inflation.Tuning.breedSpacing] times their usual
     * numbers, only as many as there are ranges breed). How many she bears of them is [breeding]'s.
     */
    private fun dueYoung(k: Int, heads: Float, protein: Float, stepsInSeason: Float): Float {
        val ranges = min(1f, tuning.breedSpacing * usual[k] / heads)
        return ranges * kinds[k].youngPerFemale * protein / stepsInSeason
    }

    /** Eagles take the young of the smaller hoofed animals in the open: the share of [s]'s young at [p] they take in a step. */
    private fun eagleOdds(s: Species, p: Int) = if (s.massKg <= EAGLE_PREY_KG) small.eagleRisk(p) * years else 0f

    /** Crocodiles take the young that come down to warm rivers and lakes to drink: the share at [p] they take in a step. */
    private fun crocodileOdds(p: Int, weather: Weather) = water.crocodileRisk(p, weather) * years

    /**
     * The share of [s]'s young that die in a step besides the hunters' take: their usual deaths, the [hazards] in [hz]
     * (hunger, cold and thirst harder on them), [eagle]s and [croc]odiles, and, fed [protein] of their need for it, the
     * young short of protein, which grow slowly and die more.
     */
    private fun youngOdds(s: Species, hz: FloatArray, eagle: Float, croc: Float, protein: Float): Float {
        val starved = tuning.youngProteinDeaths * (1f - protein) * years
        return (s.youngMortality * years + 1.5f * (hz[HZ_HUNGER] + hz[HZ_COLD] + hz[HZ_THIRST]) + hz[HZ_DISEASE] + eagle +
            hz[HZ_FIRE_Y] + hz[HZ_FLOOD_Y] + hz[HZ_SNOW_Y] + croc + starved).coerceIn(0f, 0.95f)
    }

    /** The share of the grown among [out] ([hazards]) that die this week, besides old age. */
    private fun adultShare(out: FloatArray) =
        out[HZ_HUNGER] + out[HZ_COLD] + out[HZ_THIRST] + out[HZ_DISEASE] + out[HZ_FIRE_A] + out[HZ_FLOOD_A] + out[HZ_SNOW_A] + out[HZ_HUNTED]

    /**
     * The share of kind [k]'s grown residents at place [p] that would die next week if they stayed ([hazards]): fed as
     * they were this week, their days without water going on where they cannot drink.
     */
    internal fun stayOdds(k: Int, p: Int, weather: Weather, out: FloatArray): Float {
        val s = kinds[k]
        val f = fed[k][p]
        val dryNext = if (drinks(s, p)) 0f else dry[k][p] + Hazards.dryDays(s, days, f, land.plants.green[p])
        hazards(k, p, weather, weather.date.month, f, dryNext, adults[k][p] + young[k][p], out)
        return adultShare(out)
    }

    /**
     * Fills [arrive] for every kind, from this week's [room] and the residents where they now are; and in the weeks they
     * move ([moving]), what each place is worth to them ([worth]).
     */
    private fun fillArrive(weather: Weather, moving: Boolean) {
        val outs = ThreadLocal.withInitial { FloatArray(HZ_COUNT) }
        for (k in kinds.indices) {
            val s = kinds[k]
            val odds = arrive[k]; val w = worth[k]
            if (!moving) { IntStream.range(0, n).parallel().forEach { p -> odds[p] = arriveOdds(k, p, weather, outs.get()) }; continue }
            if (room[k] == null) {
                IntStream.range(0, n).parallel().forEach { p ->
                    odds[p] = arriveOdds(k, p, weather, outs.get()); w[p] = worthOrNothing(appeal(k, p, weather)) }
                continue
            }
            // (Those that move are the kind as it is: its young to its grown, and the fat its grown carry, all over.)
            val a = adults[k]; val y = young[k]; val fat = reserve[k]
            var grown = 0.0; var little = 0.0; var fatDays = 0.0
            for (p in 0 until n) { val area = land.areaKm2[p]; grown += a[p] * area; little += y[p] * area; fatDays += fat[p] * a[p] * area }
            val youngPerGrown = if (grown > 0.0) (little / grown).toFloat() else 0f
            val carried = if (grown > 0.0) (fatDays / grown).toFloat() else 0.5f * s.maxFatDays
            moverYoung[k] = youngPerGrown; moverFat[k] = carried
            val stepsPerYear = Date.DAYS_PER_YEAR / days.toFloat()
            IntStream.range(0, n).parallel().forEach { p ->
                val out = outs.get()
                val o = arriveOdds(k, p, weather, out)
                odds[p] = o
                w[p] = if (o.isNaN()) 0f else worthOrNothing(growth(k, p, weather, out, youngPerGrown, carried, stepsPerYear))
            }
        }
    }

    /**
     * What place [p] is worth to plant-eating kind [k] for moving there: the rate those coming there would grow at over a
     * year, as it would fare there this week, by the rules residents are born and die by ([breedAndDie]):
     * the young born there to its mothers (fed as far as the food left uneaten feeds them, [room], with the protein the
     * forage there holds, [forageProtein], and the fat they [carried]), less the grown and the young ([youngPerGrown] to a
     * grown one) that die there of the [hazards] in [out] (as [arriveOdds] filled it) and to the hunters. The week's
     * deaths are taken over the [stepsPerYear]; its births over the weeks of the birth season only, since they are born
     * in that season and not all year (else a week of it would count as a year of births and outweigh any death).
     */
    private fun growth(k: Int, p: Int, weather: Weather, out: FloatArray, youngPerGrown: Float, carried: Float, stepsPerYear: Float,
                       f: Float = room[k]?.get(p) ?: fed[k][p]): Float {
        val parts = FloatArray(GROWTH_PARTS)
        growthParts(k, p, weather, out, youngPerGrown, carried, parts, f)
        val head = 1f + youngPerGrown
        val stepsInSeason = kinds[k].birthMonths * Date.DAYS_PER_MONTH / days.toFloat()
        val dying = ((parts[GROWTH_GROWN_DIE] + youngPerGrown * parts[GROWTH_YOUNG_DIE]) / head).coerceIn(0f, 1f)
        return (1f + stepsInSeason * parts[GROWTH_BORN] / head) * exp(stepsPerYear * ln1p(-dying))
    }

    /**
     * [growth]'s week: the change in heads a head of kind [k] coming to place [p] would see there in a week. Where [parts]
     * is given, it takes the young born to a grown one ([GROWTH_BORN]), the shares of the grown and the young that die
     * ([GROWTH_GROWN_DIE], [GROWTH_YOUNG_DIE]), and how well fed and how well met their need for protein would be
     * ([GROWTH_FED], [GROWTH_PROTEIN]).
     */
    internal fun growthParts(k: Int, p: Int, weather: Weather, out: FloatArray, youngPerGrown: Float, carried: Float, parts: FloatArray?,
                             f: Float = room[k]?.get(p) ?: fed[k][p]): Float {
        val s = kinds[k]
        val protein = forageProtein(s, p)
        val inSeason = monthsFrom(birthStart[k][p].toInt(), weather.date.month) < s.birthMonths
        val born = if (!inSeason) 0f else {
            val due = dueYoung(k, adults[k][p] + young[k][p] + arrivingKm2(k), protein, s.birthMonths * Date.DAYS_PER_MONTH / days.toFloat())
            0.5f * due * breeding(s, due, carried, f, days)
        }
        val grownDie = (s.backgroundMortality * years + adultShare(out)).coerceIn(0f, 0.9f)
        val youngDie = min(0.95f, youngOdds(s, out, eagleOdds(s, p), crocodileOdds(p, weather), protein) + huntedOdds(k, p, true))
        if (parts != null) { parts[GROWTH_BORN] = born; parts[GROWTH_GROWN_DIE] = grownDie; parts[GROWTH_YOUNG_DIE] = youngDie; parts[GROWTH_FED] = f; parts[GROWTH_PROTEIN] = protein }
        return (born - grownDie - youngPerGrown * youngDie) / (1f + youngPerGrown)
    }

    /**
     * The share of grown animals of kind [k] coming to place [p] that would die there in a week ([hazards]): fed as far
     * as the food left uneaten there ([room]) feeds them (a hunter, as well as the residents there were fed), with water
     * to drink; NaN where there is none, a place no one can stay at. Crowded by those there and a band of the kind come
     * to them ([arrivingKm2]): over the ground a band ranges in a day, the same wherever it goes. (Counted on the place's
     * own ground, a band crowded the smaller places, the high ground's above all, past any that live there; not counted
     * at all, all that move see a place as it is and crowd into the same ones together.)
     */
    internal fun arriveOdds(k: Int, p: Int, weather: Weather, out: FloatArray, f: Float = room[k]?.get(p) ?: fed[k][p]): Float {
        val s = kinds[k]
        if (!drinks(s, p)) return Float.NaN
        hazards(k, p, weather, weather.date.month, f, 0f, adults[k][p] + young[k][p] + arrivingKm2(k), out)
        return adultShare(out)
    }

    /**
     * The neighbouring place kind [k]'s residents at place [p] would walk to down the slope of [odds] (the share that
     * would die at each place in a week, NaN where they cannot stay): of the places across each border it can cross into
     * the six hexes about, in its band or the one above or below (the ground [spill] weighs), the one with the lowest
     * odds below [than]; -1 where none is lower. The one rule for which way the land slopes for a kind.
     */
    internal fun safestNeighbour(k: Int, p: Int, odds: FloatArray, than: Float): Int {
        var best = -1; var bestOdds = than
        neighbourPlaces(k, p) { q -> if (odds[q] < bestOdds) { bestOdds = odds[q]; best = q } }
        return best
    }

    /** Each place kind [k]'s residents at place [p] can walk to in a step: across each border it can cross into the six hexes about, in its band or the one above or below. */
    private inline fun neighbourPlaces(k: Int, p: Int, f: (Int) -> Unit) {
        val t = stepPlaces[travel[k].ordinal]
        val from = t.from; val to = t.to
        for (i in from[p] until from[p + 1]) f(to[i])
    }

    /** The places a step leads to from each place, for [neighbourPlaces]: those of place `p` are `to[from[p] until from[p + 1]]`. */
    private class StepPlaces(val from: IntArray, val to: IntArray)
    /** [StepPlaces] for each way of travel, worked out once (borders and places do not change). */
    @delegate:Transient private val stepPlaces: Array<StepPlaces> by lazy {
        Array(HexMap.Travel.entries.size) { t ->
            val cross = hex.crossings[t]
            val from = IntArray(n + 1); var to = IntArray(12 * n); var m = 0
            for (p in 0 until n) {
                from[p] = m
                val h = hw.placeHex[p]; val b = hw.placeBand[p]
                for (d in 0 until 6) {
                    val nh = hex.neighbour(h, d)
                    if (nh < 0 || !cross[h * 6 + d].isFinite()) continue
                    for (db in -1..1) {
                        val nb = b + db; if (nb !in HexWorld.BANDS_M.indices) continue
                        val q = hw.placeOf[nb][nh]
                        if (q >= 0) { if (m == to.size) to = to.copyOf(2 * m); to[m++] = q }
                    }
                }
            }
            from[n] = m
            StepPlaces(from, to.copyOf(m))
        }
    }

    /**
     * Where kind [k]'s walkers at place [p] step next: death first, by [odds] (the share that would die at each place on
     * coming there, NaN where they cannot stay); where staying is clearly the deadlier ([clearlyWorse] for a band of the
     * kind), to the safest neighbour; else to the neighbour of the best forage ([worth], as [appeal] weighs a place) among
     * those no deadlier than here, where it is better than here; -1 where they stay.
     */
    internal fun walkStep(k: Int, p: Int, odds: FloatArray, worth: FloatArray): Int {
        val here = odds[p]
        if (here.isNaN()) return -1
        // (The safest neighbour, as [safestNeighbour] finds it, and the best fed of those no deadlier, in one pass.)
        var safest = -1; var safestOdds = here
        var best = -1; var bestWorth = worth[p]
        neighbourPlaces(k, p) { q ->
            val o = odds[q]
            if (o < safestOdds) { safestOdds = o; safest = q }
            if (o <= here && worth[q] > bestWorth) { bestWorth = worth[q]; best = q }
        }
        if (safest >= 0 && clearlyWorse(here, odds[safest], kinds[k].maxGroupSize.toFloat())) return safest
        return best
    }

    /**
     * Where the slope of [odds] leads kind [k] from each place, stepping a neighbour a week to the safest
     * ([safestNeighbour]) while it is safer: the place it ends at ([Slope.end]), the weeks it takes ([Slope.steps]), and
     * the shares that would die on the way, a week at each place before the end, summed ([Slope.cost]). Worked out only
     * for the places asked about, and those on their way, each once.
     */
    internal fun slope(k: Int, odds: FloatArray) = Slope(k, odds)

    /** Where the slope leads from each place ([slope]). */
    internal inner class Slope(private val k: Int, private val odds: FloatArray) {
        private val endOf = IntArray(n) { -1 }; private val stepsOf = IntArray(n); private val costOf = FloatArray(n)
        private val path = IntArray(n)

        fun end(p: Int): Int { resolve(p); return endOf[p] }
        fun steps(p: Int): Int { resolve(p); return stepsOf[p] }
        fun cost(p: Int): Float { resolve(p); return costOf[p] }

        private fun resolve(p0: Int) {
            if (endOf[p0] >= 0) return
            // (Down the slope until a place already worked out or the bottom; then back up, adding each place's week.)
            var len = 0; var p = p0
            while (endOf[p] < 0) {
                val next = if (odds[p].isNaN()) -1 else safestNeighbour(k, p, odds, odds[p])
                if (next < 0) { endOf[p] = p; stepsOf[p] = 0; costOf[p] = 0f; break }
                path[len++] = p; p = next
            }
            while (len > 0) { val q = path[--len]; endOf[q] = endOf[p]; stepsOf[q] = stepsOf[p] + 1; costOf[q] = costOf[p] + odds[q]; p = q }
        }
    }

    /**
     * How good a place is for resident hunter [k] this week (the plant-eaters weigh places by [growth]): food per head,
     * its ground, water within reach, the weather against its comfort, deep snow, fear, and the share that would live
     * through the week's fire and plague there.
     */
    internal fun appeal(k: Int, p: Int, weather: Weather): Float {
        val s = kinds[k]
        val heads = adults[k][p] + young[k][p]
        // Weather now, and as far as the kind senses it, what is coming.
        val m = weather.date.month
        val discomfort = ((1f - s.foresight) * s.discomfortC(feltC(s, p, weather.temperature[p]), m) +
            s.foresight * s.discomfortC(feltC(s, p, weather.forecastTemp(p)), m)) / 10f
        // (A plant-eater weighs the snow as it feeds under it: its grass by the share it gets at, see [grassUnderSnow].)
        val snowed = if (s.carnivore) { if (weather.snowpack[p] > s.snowLimitMm) 0.3f else 1f } else s.diet.let { d ->
            val plants = d.grass + d.browse + d.fruit + d.roots
            if (plants <= 0f) 1f else 1f - d.grass * (1f - grassUnderSnow(s, weather.snowpack[p])) / plants }
        // (Plant-eaters weigh their forage by how fresh it is and how well it would meet their need for
        // protein, as herds do: so they go up to the green high pasture in summer and down for the winter.)
        val fresh = if (s.carnivore) 1f else land.plants.nourishment(p) * forageProtein(s, p)
        return fareFor(s, p) * fresh * suits(s, p) * tether(s, p) * snowed * (1f - FEAR_WEIGHT * shy(s, p)) *
            (1f - land.fire.burning[p] * FIRE_ADULT) * (1f - outbreakDeaths(s, p)) /
            (1f + heads / (CROWDED * usual[k])) / (1f + discomfort)
    }

    /** How well kind [s]'s diet at place [p] would meet its need for protein, 0..1 (see [proteinMet]). */
    fun forageProtein(s: Species, p: Int): Float {
        val d = s.diet; val plants = d.grass + d.browse + d.fruit + d.roots
        if (plants <= 0f) return 1f
        val share = (d.grass * grassProtein(p) + d.browse * browseProtein(p) + (d.fruit + d.roots) * PROTEIN_FRUIT) / plants
        val ps = plantShare(s)
        return proteinMet(ps * share + (1f - ps))
    }

    /** Whether [s] can drink at place [p]: water there, or within half its days without water's going (see [Hazards.fetchKm]). */
    private fun drinks(s: Species, p: Int) =
        s.maxWaterKm.isFinite() || land.drink.animalWater(p) >= Thresholds.WET || land.drink.waterKm[p] <= Hazards.fetchKm(s)

    /** What a place is worth to a kind for moving there: nothing when below nothing or not a number (see [shiftBands], [spill]). */
    private fun worthOrNothing(w: Float) = if (w > 0f && w.isFinite()) w else 0f

    /**
     * Within a hex, a share of each kind moves between neighbouring height bands: death first, as in [walkStep]. Where
     * staying in one band is clearly deadlier than coming to the other ([arrive], [clearlyWorse]), a share moves to the
     * safer one, by how much safer it is; else towards the band of the better forage and ground ([appeal]), if it is no
     * deadlier.
     */
    private fun shiftBands(weather: Weather) {
        val bands = HexWorld.BANDS_M.size
        // (Moving every few weeks, a move takes the share those weeks' would have.)
        val weeks = tuning.residentMoveWeeks
        // Each kind on its own core: the kinds' numbers are kept apart.
        kinds.indices.toList().parallelStream().forEach { k ->
            val a = adults[k]; val y = young[k]
            for (h in 0 until hex.size) for (b in 0 until bands - 1) {
                val lo = hw.placeOf[b][h]; val hi = hw.placeOf[b + 1][h]
                if (lo < 0 || hi < 0) continue
                if (a[lo] + y[lo] <= 0f && a[hi] + y[hi] <= 0f) continue
                // (A place worth less than nothing, or past reckoning, counts as nothing: else the gain
                // passes 1, the share can take more than the place holds and leave it below nothing,
                // and spill's floor at nothing then makes animals out of nothing.)
                // (Where the kind cannot drink, it cannot stay: deadlier than anywhere it can.)
                val ol = arrive[k][lo].let { if (it.isNaN()) Float.POSITIVE_INFINITY else it }
                val oh = arrive[k][hi].let { if (it.isNaN()) Float.POSITIVE_INFINITY else it }
                if (ol.isInfinite() && oh.isInfinite()) continue
                val band = kinds[k].maxGroupSize.toFloat()
                val from: Int; val to: Int; val gain: Float
                if (a[lo] + y[lo] > 0f && (oh.isFinite() && (ol.isInfinite() || clearlyWorse(ol, oh, band)))) {
                    from = lo; to = hi; gain = if (ol.isInfinite()) 1f else (ol - oh) / ol
                } else if (a[hi] + y[hi] > 0f && (ol.isFinite() && (oh.isInfinite() || clearlyWorse(oh, ol, band)))) {
                    from = hi; to = lo; gain = if (oh.isInfinite()) 1f else (oh - ol) / oh
                } else {
                    val al = worth[k][lo]; val ah = worth[k][hi]
                    if (al <= 0f && ah <= 0f) continue
                    if (ah > al) { from = lo; to = hi } else { from = hi; to = lo }
                    if (!(arrive[k][to] <= (if (to == hi) ol else oh))) continue
                    gain = (max(al, ah) - min(al, ah)) / max(al, ah)
                }
                val share = (BAND_SHIFT * gain).let { if (weeks == 1) it else 1f - (1f - it).pow(weeks) }
                if (!(share > 0f)) continue
                val ma = a[from] * share * land.areaKm2[from]; val my = y[from] * share * land.areaKm2[from]
                a[from] -= ma / land.areaKm2[from]; y[from] -= my / land.areaKm2[from]
                a[to] += ma / land.areaKm2[to]; y[to] += my / land.areaKm2[to]
            }
        }
    }

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
    @Transient var seaLinks: Lazy<Array<IntArray>>? = null
    @Transient var seaGaps: Lazy<Array<FloatArray>>? = null

    /**
     * Hungry or crowded animals spill into neighbouring hexes, at about the same height, towards
     * better feeding; the harder the border is for their way of travelling, the fewer cross it.
     * Those that leave walk on, hex by hex, to safer places, or to better feeding no deadlier than where they
     * stand, as far as their week's going allows ([walkOn], [hopsFor]): so a kind follows the seasons' change of
     * country by walking, not only by spreading a hex a week.
     */
    private fun spill(weather: Weather) {
        // (The swimmers drowned this week, kind by kind: their weight as grown animals by place, and their heads.)
        val drowned = Array(kinds.size) { FloatArray(n) }; val drownedHeads = DoubleArray(kinds.size)
        kinds.indices.toList().parallelStream().forEach { k ->
            val s = kinds[k]
            val a = adults[k]; val y = young[k]; val f = fed[k]
            val cross = hex.crossings[travel[k].ordinal]
            val moveA = FloatArray(n); val moveY = FloatArray(n)
            // (The heads that came, and the km they walked to come: what the walk costs their fat.)
            val arrived = FloatArray(n); val walkedKm = FloatArray(n)
            // Those still walking after the first hex, by where they stand: grown, young, and km walked (summed over heads).
            // (Moving every few weeks, a move takes the share those weeks' would have, and the walkers walk as far.)
            val weeks = tuning.residentMoveWeeks
            val hops = if (room[k] != null) hopsFor(s, weeks) else 1
            val walkA = FloatArray(n); val walkY = FloatArray(n); val walkKm = FloatArray(n)
            // What each place is worth to the kind this week, as it is worth to those moving up and down the slopes.
            val worthOf = worth[k]
            val dk = dry[k]
            var weekHeads = 0.0; var weekStuck = 0.0; var weekEdge = 0.0
            val flow = flowOf == k
            val linksTo = seaLinks?.value; val gapsTo = seaGaps?.value
            if (flow) { flowEast.fill(0f); flowSouth.fill(0f); flowOut.fill(0f) }
            for (p in 0 until n) {
                if (a[p] + y[p] <= 0f) continue
                // (Thirst drives them as hunger does: by how far their days without water have gone towards what they can bear.)
                val thirst = (dk[p] / s.daysWithoutWater).coerceIn(0f, 1f)
                // (And the cold or heat, and snow deeper than they can dig through, as they drive herds: see [restless].)
                val uneasy = (s.discomfortC(feltC(s, p, weather.temperature[p]), weather.date.month) / 10f).coerceIn(0f, 1f)
                val snowedIn = (weather.snowpack[p] / s.snowLimitMm - 1f).coerceIn(0f, 1f)
                val need = maxOf((1f - f[p]).coerceIn(0f, 1f), thirst, max(uneasy, snowedIn))
                val h = hw.placeHex[p]; val b = hw.placeBand[p]
                // Hungry, thirsty, cold or snowed-in ones leave, and so do those whose country does not suit them, towards better.
                val leave = SPILL_BASE + SPILL_HUNGER * need + SPILL_MISFIT * bearings.strength(k, h)
                // Where they could go: each neighbouring hex at about the same height (its band, or the one above
                // or below: so they work up a valley to the high pasture and down again), weighed by food, how
                // fresh and rich in protein it is, and how easy the border is.
                var total = 0f; var best = 0f; var closed = false
                val ws = FloatArray(18); val wq = IntArray(18) { -1 }
                for (d in 0 until 6) {
                    val nh = hex.neighbour(h, d)
                    if (nh < 0) { closed = true; continue }
                    val e = cross[h * 6 + d]
                    if (!e.isFinite()) { closed = true; continue }
                    val ease = hex.hexKm.toFloat() / e
                    val toward = max(0.05f, 1f + BEARING_SPILL * bearings.along(k, h, nh, hex.hexKm))
                    for (db in -1..1) {
                        val nextBand = b + db; if (nextBand !in 0 until HexWorld.BANDS_M.size) continue
                        val q = hw.placeOf[nextBand][nh]
                        if (q < 0) continue
                        val i = d * 3 + db + 1
                        // (The thirsty shun country where they could not drink, the more the thirstier: as herds head for water.)
                        val worth = if (thirst > 0f && !drinks(s, q)) worthOf[q] * (1f - thirst) else worthOf[q]
                        if (worth > best) best = worth
                        ws[i] = ease * (worth + 1e-4f) * toward
                        wq[i] = q
                        total += ws[i]
                    }
                }
                // A few swim the strait to the coast across, as far as they can swim it.
                val swim = Catalogue.swimKm(s)
                var seaW = 0f; var seaTo = -1; var seaGapKm = 0f
                if (swim > 0f && linksTo != null && gapsTo != null) {
                    val to = linksTo[h]; val gaps = gapsTo[h]
                    for (i in to.indices) { val nh = to[i]; val gapKm = gaps[i]
                        if (gapKm > swim) continue; val q = hw.placeOf[b][nh].takeIf { it >= 0 } ?: continue
                        // (Weighed by those that would reach the far shore: the strait drowns some, as it does groups.)
                        val wq = SEA_SPILL * (1f - gapKm / swim) * (1f - drownRisk(s, gapKm)) * land.areaKm2[q] * (worthOf[q] + 1e-4f)
                        if (wq > seaW) { seaW = wq; seaTo = q; seaGapKm = gapKm } }
                }
                val here = if (thirst > 0f && !drinks(s, p)) worthOf[p] * (1f - thirst) else worthOf[p]
                val heads = ((a[p] + y[p]) * land.areaKm2[p]).toDouble()
                weekHeads += heads
                if (f[p] < STUCK_FED && best <= here) { weekStuck += heads; if (closed) weekEdge += heads }
                if (total <= 0f) continue
                // (And the more so the better the country next door is than here: as the seasons turn, they follow the fresh pasture.)
                val better = if (best > 0f) ((best - here) / best).coerceIn(0f, 1f) else 0f
                val go = min(0.5f, leave + SPILL_BETTER * better).let { if (weeks == 1) it else 1f - (1f - it).pow(weeks) }
                val goA = a[p] * go * land.areaKm2[p]; val goY = y[p] * go * land.areaKm2[p]
                if (seaTo >= 0) {
                    val share = seaW / (total + seaW)
                    moveA[p] -= goA * share
                    moveY[p] -= goY * share
                    // (The drowned wash up on the far shore.)
                    val risk = drownRisk(s, seaGapKm)
                    val lostA = goA * share * risk; val lostY = goY * share * min(1f, 2f * risk)
                    moveA[seaTo] += goA * share - lostA
                    moveY[seaTo] += goY * share - lostY
                    drowned[k][seaTo] += lostA + lostY * YOUNG_WEIGHT; drownedHeads[k] += (lostA + lostY).toDouble()
                }
                moveA[p] -= goA; moveY[p] -= goY
                for (i in 0 until 18) {
                    if (ws[i] <= 0f) continue
                    val q = wq[i]
                    val ga = goA * ws[i] / total; val gy = goY * ws[i] / total
                    if (hops > 1) {
                        walkA[q] += ga; walkY[q] += gy; walkKm[q] += (ga + gy) * hex.hexKm.toFloat()
                    } else {
                        moveA[q] += ga; moveY[q] += gy
                        arrived[q] += ga + gy; walkedKm[q] += (ga + gy) * hex.hexKm.toFloat()
                    }
                    if (flow) {
                        // (The first hex of their way: where those walking on end up is not traced back to here.)
                        val (x0, y0) = hex.centreKm(h); val (x1, y1) = hex.centreKm(hw.placeHex[q])
                        flowEast[h] += (ga + gy) * (x1 - x0).toFloat(); flowSouth[h] += (ga + gy) * (y1 - y0).toFloat(); flowOut[h] += ga + gy
                    }
                }
            }
            stuck[k].let { it[Stuck.HEADS.ordinal] += weekHeads; it[Stuck.STUCK.ordinal] += weekStuck; it[Stuck.AT_EDGE.ordinal] += weekEdge
                if (weekHeads > 0.0) it[Stuck.PEAK.ordinal] = max(it[Stuck.PEAK.ordinal], weekStuck / weekHeads) }
            for (p in 0 until n) { a[p] = max(0f, a[p] + moveA[p] / land.areaKm2[p]); y[p] = max(0f, y[p] + moveY[p] / land.areaKm2[p]) }
            if (hops > 1) walkOn(k, hops, weather, walkA, walkY, walkKm, arrived, walkedKm)
            gatherThin(k, arrived, walkedKm)
            // (A place left with too few to count, by those leaving or the few that came, has none: as after births and deaths,
            // they go to the soil with the drowned. Else they would feed and die there as fractions too small to reckon with.)
            for (p in 0 until n) if (a[p] + y[p] > 0f && a[p] + y[p] < EXTINCT) { drowned[k][p] += a[p] + y[p] * YOUNG_WEIGHT; a[p] = 0f; y[p] = 0f }
            // (Those that came walked here, at a herd's cost a km out of their fat, see [walkCost].)
            for (p in 0 until n) if (arrived[p] > 0f) {
                val heads = (a[p] + y[p]) * land.areaKm2[p]
                if (heads > 0f) reserve[k][p] = max(0f, reserve[k][p] - WALK_COST * walkedKm[p] / heads)
            }
        }
        // (The drowned, counted kind by kind, go to the soil with the week's other carcasses.)
        var drownedKg = 0.0
        for ((k, s) in kinds.withIndex()) {
            for (p in 0 until n) { val d = drowned[k][p]; if (d <= 0f) continue; carcassKg[p] += d * s.massKg; drownedKg += d * s.massKg }
            if (drownedHeads[k] > 0.0) deaths.merge(Tally.of(s, DeathCause.DROWNED_CROSSING), drownedHeads[k], Double::plus)
        }
        land.books?.flow(Books.Stuff.MATTER, "carcasses (to the soil)", -drownedKg)
    }

    /**
     * How many hexes kind [s] can walk in a step, the first included: its usual day's going over the step's days.
     * (Its usual going is how far it ranges in a day; a resident that sets off for better country can make at least
     * that much headway, so this is the lower bound of what it could cover.)
     */
    internal fun hopsFor(s: Species, weeks: Int = 1) = max(1, (s.dailyTravelKm * days * weeks / hex.hexKm).toInt())

    /**
     * Kind [k]'s animals too thinly spread to keep apart (under [inflation.Tuning.residentFloor] of its usual numbers at a
     * place) gather a step to the neighbouring place that holds the most of its thinly spread animals, where they can
     * stay: so the thin edge of a kind keeps together in fewer places, spreading still as the most of it does. Adds those
     * that came, and the km they walked, to [arrived] and [walkedKm].
     */
    private fun gatherThin(k: Int, arrived: FloatArray, walkedKm: FloatArray) {
        val floor = tuning.residentFloor * usual[k]
        if (!(floor > 0f)) return
        val s = kinds[k]; val a = adults[k]; val y = young[k]; val area = land.areaKm2
        for (p in 0 until n) {
            val d = a[p] + y[p]
            if (!(d > 0f) || d >= floor) continue
            var to = -1; var most = d * area[p]
            neighbourPlaces(k, p) { q -> val dq = a[q] + y[q]; if (dq < floor && dq * area[q] > most && drinks(s, q)) { most = dq * area[q]; to = q } }
            if (to < 0) continue
            val ga = a[p] * area[p]; val gy = y[p] * area[p]
            a[to] += ga / area[to]; y[to] += gy / area[to]; a[p] = 0f; y[p] = 0f
            arrived[to] += ga + gy; walkedKm[to] += (ga + gy) * hex.hexKm.toFloat()
        }
    }

    /**
     * The walkers of kind [k] ([spill]'s, who left their place and have crossed into the next hex) walk on, a step at a
     * time as [walkStep] leads (death first by [arrive], then the forage [worth] among places no deadlier), for up to
     * [hops] hexes in all; where it leads nowhere, or their week's going is spent, they stay. Where they stay, those
     * already come count: as many stay as find it, with them there, still no worse than walking on ([settle]), and the
     * rest walk on. Only for the kinds whose food left uneaten is reckoned ([room]). Adds the heads that came to each
     * place to [arrived] and the km they walked to [walkedKm].
     */
    private fun walkOn(k: Int, hops: Int, weather: Weather, walkA: FloatArray, walkY: FloatArray, walkKm: FloatArray,
                       arrived: FloatArray, walkedKm: FloatArray) {
        val a = adults[k]; val y = young[k]
        val area = land.areaKm2
        // (The places as this move's walkers see them, changing as they come: the week's own are left as they were.)
        val odds = arrive[k].copyOf(); val seen = worth[k].copyOf()
        val weigh = Weigh(k, weather, odds, seen, arrived)
        var wA = walkA; var wY = walkY; var wK = walkKm
        var nA = FloatArray(n); var nY = FloatArray(n); var nK = FloatArray(n)
        for (hop in 2..hops) {
            var moving = false
            for (q in 0 until n) {
                val heads = wA[q] + wY[q]
                if (!(heads > 0f)) continue
                var r = walkStep(k, q, odds, seen)
                var go = 1f
                if (r < 0) {
                    val stay = weigh.settle(q, wA[q], wY[q])
                    a[q] += stay * wA[q] / area[q]; y[q] += stay * wY[q] / area[q]; arrived[q] += stay * heads; walkedKm[q] += stay * wK[q]
                    go = 1f - stay; r = weigh.onward
                    if (!(go > 0f) || r < 0) continue
                }
                moving = true
                nA[r] += go * wA[q]; nY[r] += go * wY[q]; nK[r] += go * (wK[q] + heads * hex.hexKm.toFloat())
            }
            wA = nA; wY = nY; wK = nK
            nA = FloatArray(n); nY = FloatArray(n); nK = FloatArray(n)
            if (!moving) break
        }
        // (Where their going ran out, they stay.)
        for (q in 0 until n) { val heads = wA[q] + wY[q]; if (heads > 0f) { a[q] += wA[q] / area[q]; y[q] += wY[q] / area[q]; arrived[q] += heads; walkedKm[q] += wK[q] } }
    }

    /**
     * How kind [k]'s walkers weigh a place this move ([walkOn]), with those that came there this move ([arrived]) counted:
     * the share that would die there ([arriveOdds]) and what it is worth ([growth]), fed as far as the food left uneaten
     * feeds a band of them and all that came before it together ([roomFor]); kept in [odds] and [worth] as they change.
     */
    private inner class Weigh(private val k: Int, private val weather: Weather, private val odds: FloatArray,
                              private val worth: FloatArray, private val arrived: FloatArray) {
        private val out = FloatArray(HZ_COUNT); private val dry = DryPantry(); private val b = Bites()
        private val stepsPerYear = Date.DAYS_PER_YEAR / days.toFloat()
        /** Where those that do not stay ([settle]) walk on to; -1 where all stay. */
        var onward = -1; private set

        /** [odds] and [worth] at place [p] with [came] heads come there this move besides those counted in its numbers. */
        private fun reweigh(p: Int, came: Float) {
            val f = roomFor(k, p, kinds[k].maxGroupSize + arrived[p] + came, weather, dry, b)
            val o = arriveOdds(k, p, weather, out, f)
            odds[p] = o
            worth[p] = if (o.isNaN()) 0f else worthOrNothing(growth(k, p, weather, out, moverYoung[k], moverFat[k], stepsPerYear, f))
        }

        /**
         * Of [wa] grown and [wy] young walkers at place [p], where [walkStep] would have them stay, the share that stays:
         * all, where with all of them there it still would; else as many as find it, with them there, still no worse
         * than walking on, found by halving ([SETTLE_HALVINGS] times), the rest walking on to [onward]. Leaves [odds] and
         * [worth] at [p] as they are with those that stay.
         */
        fun settle(p: Int, wa: Float, wy: Float): Float {
            val area = land.areaKm2[p]; val a = adults[k]; val y = young[k]
            val a0 = a[p]; val y0 = y[p]; val heads = wa + wy
            // (Weighed with a share of them there: counted in its numbers, as they will be, then taken out again.)
            fun stepWith(share: Float): Int {
                a[p] = a0 + share * wa / area; y[p] = y0 + share * wy / area
                reweigh(p, share * heads)
                val r = walkStep(k, p, odds, worth)
                a[p] = a0; y[p] = y0
                return r
            }
            onward = stepWith(1f)
            if (onward < 0) return 1f
            var lo = 0f; var hi = 1f
            repeat(SETTLE_HALVINGS) {
                val mid = 0.5f * (lo + hi)
                val r = stepWith(mid)
                if (r < 0) lo = mid else { hi = mid; onward = r }
            }
            stepWith(lo)
            return lo
        }
    }

    /** Share of [s] that drown swimming [gapKm] of open water: by the groups' rule, the nearer the strait to all it can swim, the more. */
    private fun drownRisk(s: Species, gapKm: Float) = min(1f, Hazards.drowning(s, gapKm))

    companion object {
        /** Where [hazards] puts each share: hunger, cold, thirst, disease, the grown's fire, flood and snow, the young's; the crowding; and the hunters' take of the grown. */
        /** Where [growthParts] puts each part. */
        const val GROWTH_BORN = 0; const val GROWTH_GROWN_DIE = 1; const val GROWTH_YOUNG_DIE = 2; const val GROWTH_FED = 3; const val GROWTH_PROTEIN = 4; const val GROWTH_PARTS = 5
        const val HZ_HUNGER = 0; const val HZ_COLD = 1; const val HZ_THIRST = 2; const val HZ_DISEASE = 3
        const val HZ_FIRE_A = 4; const val HZ_FLOOD_A = 5; const val HZ_SNOW_A = 6
        const val HZ_FIRE_Y = 7; const val HZ_FLOOD_Y = 8; const val HZ_SNOW_Y = 9; const val HZ_CROWD = 10
        const val HZ_HUNTED = 11
        const val HZ_COUNT = 12
        /** Share of the standing plants a week's grazing can take without eating the place bare. */
        const val REACH = 0.01f
        /** How strongly a kind is drawn to the ground it prefers. */
        const val HABITAT = 3f
        /** Density, as a multiple of a kind's usual, at which disease kills a [DISEASE] share a year; and at which crowding halves a place's appeal ([appeal]). */
        const val CROWDED = 4f
        /** For the check only ([stuck]): fed below this counts as hungry. */
        const val STUCK_FED = 0.8f
        /**
         * Protein in forage, against fresh growth: cured grass and straw keep some of their energy but
         * lose most of their protein (about 4% crude protein against 16% green, though grazers pick the
         * best of it and get about 6%; 3% of straw); browse keeps more
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
        /** How many times [HexFauna.Weigh.settle] halves the share of walkers that stay, to find it: to within 1/64 of them. */
        const val SETTLE_HALVINGS = 6
        /**
         * Resident hunters: heaviest prey against their own weight; share of a prey kind's numbers in a place they can
         * kill in a week; weight of the young against grown. (They eat [MEAT_SHARE] of a kill, as packs do.)
         */
        const val RESIDENT_KILL_MOST = 0.02f
        const val YOUNG_WEIGHT = 0.3f
        /** How strongly a strait draws spilling residents against a neighbouring hex on land (a trickle). */
        const val SEA_SPILL = 0.02f
        /** Newcomers among the residents: a kind under this many animals gets this many at each of this many places, once a year. */
        const val NEWCOMER_ANIMALS = 300.0
        const val NEWCOMER_HEADS = 20f
        const val NEWCOMER_PLACES = 5
        /** How many times more disease strikes a herd that is not fed at all than a well-fed one. */
        const val MALNOURISHED = 4f
        const val DISEASE = 0.3f
        /** Food, kg/m², at which a place carries a kind's usual density. */
        const val FOOD_FULL = 0.3f
        /** Hunger, for every animal and band alike: below this share of its need fed, a week kills this share of the shortfall. */
        const val HUNGER_FROM = 0.5f
        const val HUNGER_RATE = 0.3f
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
        /** Below this density, per km², a kind is gone from a place. */
        const val EXTINCT = 1e-4f
        /**
         * Outbreaks: yearly chance one starts in a place at its kind's crowded density; share of the kind it kills a
         * week at full strength; share of its strength it passes to a neighbouring hex; share kept a week.
         */
        const val OUTBREAK_START = 0.02f
        const val OUTBREAK_KILL = 0.04f
        const val OUTBREAK_SPREAD = 0.6f
        const val OUTBREAK_KEEP = 0.85f
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
        /**
         * Most a plant-eater can eat in a week as a multiple of its need: coarse food fills the gut before it nourishes,
         * so poor dry forage cannot be fully made up for (a quarter more at most).
         */
        const val GUT_FILL = 1.25f
        /** Forage quality (against green, 1) at which a herbivore's gut just holds its need: middling forage. */
        const val GUT_QREF = 0.8f
        /** Seed carried by fruit-eaters: kg of fruit eaten per km² that seeds a place fully; share kept from week to week. */
        const val SEED_KG = 200f
        const val SEED_KEEP = 0.9f
        /** Share of a resident herd's weight that tramples its place in a week, against a herd passing through. */
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
enum class ResidentDeath(val cause: DeathCause) {
    AGE(DeathCause.AGE), HUNGER(DeathCause.HUNGER), COLD(DeathCause.COLD), DISEASE(DeathCause.DISEASE), EAGLES(DeathCause.EAGLES),
    FIRE(DeathCause.FIRE), FLOOD(DeathCause.FLOOD), SNOW(DeathCause.SNOW), CROCODILES(DeathCause.CROCODILES), THIRST(DeathCause.THIRST)
}
@Suppress("NOTHING_TO_INLINE") private inline operator fun DoubleArray.get(d: ResidentDeath) = this[d.ordinal]
@Suppress("NOTHING_TO_INLINE") private inline operator fun DoubleArray.set(d: ResidentDeath, v: Double) { this[d.ordinal] = v }

/** Residents held hungry where they are ([HexFauna.stuck]). */
enum class Stuck { HEADS, STUCK, AT_EDGE, PEAK }

/** Where residents' food came from ([HexFauna.fedFrom]): fresh plants, straw, prey and carrion, reserves; and the animals counted. */
enum class Fed { PLANTS, STRAW, ANIMAL, RESERVES, HEADS }
private val FED = Fed.entries.size
@Suppress("NOTHING_TO_INLINE") inline operator fun Array<DoubleAdder>.get(f: Fed) = this[f.ordinal]

/** Shares of a kind's animals that die this week of fire, flood, and deep snow or ash (see [HexFauna.calamities]). */
data class Calamities(val fire: Float, val flood: Float, val snow: Float)

/** The animal foods a resident hunter lives on, in the order its shortfall is made up when their shares tie. */
private enum class AnimalFood { MEAT, SMALL_PREY, CARRION }
