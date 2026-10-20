package inflation.ecology

import inflation.ecology.HexSmallLife.DeadPool
import inflation.Books

import inflation.KgM2
import inflation.Tuning
import inflation.wrapMonth
import inflation.monthsFrom
import inflation.world.Date
import inflation.world.HexLand
import inflation.world.HexWorld
import inflation.world.LifeForm
import inflation.world.Weather
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.min
import kotlin.random.Random
import inflation.world.Biome
import java.util.concurrent.atomic.DoubleAdder
import java.util.stream.IntStream
import kotlin.math.abs

// Foraging's rule, births and deaths, splitting, and what groups remember of the seasons.

/**
 * What a band's forebears knew: for each month, the place within a few weeks' trek where its kind
 * does best then: warm enough and not too hot for its coat, growing (warmth and rain against
 * evaporation), and free of lying snow; nearer ground preferred when much the same. This is the
 * template a band starts from; it then remembers where it actually fared better.
 */
internal fun HexGroups.inheritSeasons(g: HexGroup) {
    val s = g.species
    val here = hw.placeHex[g.place]
    val (hx, hy) = hex.centreKm(here)
    val reachKm = SEASON_RANGE_KM
    val r = (reachKm / hex.hexKm).toInt() + 1
    var near = IntArray(64); var count = 0
    // (The hexes of the square about it, row by row, rather than every place on the map; in the places' order.)
    val r0 = here / hex.cols; val c0 = here % hex.cols
    val dr = (reachKm / (hex.hexKm * 0.866)).toInt() + 2; val dc = r + 1
    for (row in max(0, r0 - dr)..min(hex.rows - 1, r0 + dr)) for (col in max(0, c0 - dc)..min(hex.cols - 1, c0 + dc)) {
        val h = row * hex.cols + col
        val (cx, cy) = hex.centreKm(h)
        if (abs(cx - hx) <= reachKm && abs(cy - hy) <= reachKm && hypot(cx - hx, cy - hy) <= reachKm)
            for (p in hw.placesOf(h)) { if (count == near.size) near = near.copyOf(2 * count); near[count++] = p }
    }
    if (count == 0) return
    java.util.Arrays.sort(near, 0, count)
    // (How far each lies, the same every month.)
    val distOf = DoubleArray(count) { i -> val (cx, cy) = hex.centreKm(hw.placeHex[near[i]]); hypot(cx - hx, cy - hy) / reachKm }
    // (And its forage, ground and water, the same every month as the forebears reckon them.)
    val foodOf = FloatArray(count) { foodFor(s, near[it]) }
    val suitsOf = FloatArray(count) { fauna.suits(s, near[it]) }
    val tetherOf = FloatArray(count) { fauna.usualTether(s, near[it]) }
    var top = 0f
    for (m in 0 until 12) {
        var best = -1; var bestScore = 0f
        for (i in 0 until count) {
            val p = near[i]
            val t = hw.placeTemp(p, m)
            val h = hw.placeHex[p]
            val grow = ((t - 2f) / 13f).coerceIn(0f, 1f) * (hw.precip[m][h] / hw.pet[m][h].coerceAtLeast(1f)).coerceIn(0.1f, 1f)
            val discomfort = s.discomfortC(t, m) / 10f
            val snow = if (t < -1f && s.diet.grass > 0.3f) 0.2f else 1f
            val dist = distOf[i]
            // ... and how much forage grows there at all: a desert flush is little food; and whether it can drink there in a usual year.
            val food = foodOf[i]
            val score = (0.2f + grow) * food / (food + 0.3f) * suitsOf[i] * tetherOf[i] * snow / (1f + discomfort * discomfort) /
                (1f + SEASON_DISTANCE * dist.toFloat())
            if (score > bestScore) { bestScore = score; best = p }
        }
        g.seasonPlace[m] = best; g.seasonQ[m] = bestScore
        top = max(top, bestScore)
    }
    // What the forebears knew is trusted until the band learns better (see [remember]).
    if (top > 0f) for (m in 0 until 12) g.seasonQ[m] = 1f
}

/** Where an animal's food at a place comes from, kg per m² asked for and got: eaten from its stocks ([HexFauna.graze]), or only reckoned on ([DryPantry]). */
internal interface Pantry {
    fun grass(kg: Float): Float
    fun browse(kg: Float): Float
    fun fruit(kg: Float): Float
    fun roots(kg: Float): Float
    fun straw(kg: Float): Float
}

/**
 * A place's food as it is only reckoned on (the residents' [HexFauna.room]): what an animal would get of each, taken
 * off what is there, without eating it.
 */
internal class DryPantry : Pantry {
    var reach = 0f; var grass = 0f; var browse = 0f; var fruit = 0f; var roots = 0f; var straw = 0f
    override fun grass(kg: Float): Float { val t = min(kg, reach * grass); grass -= t; return t }
    override fun browse(kg: Float): Float { val t = min(kg, reach * browse); browse -= t; return t }
    override fun fruit(kg: Float): Float { val t = min(kg, reach * fruit); fruit -= t; return t }
    override fun roots(kg: Float): Float { val t = min(kg, reach * roots); roots -= t; return t }
    override fun straw(kg: Float): Float { val t = min(kg, straw); straw -= t; return t }
}

/**
 * What an animal of a kind eats at a place in a week, kg per m² of it, and what it [got] of nourishment (see [forage]):
 * filled in by [forage], so one can be kept and filled again (the residents graze every place, every kind, every week).
 */
internal class Bites {
    var grass = 0f; var browse = 0f; var fruit = 0f; var roots = 0f
    var grass2 = 0f; var browse2 = 0f; var fruit2 = 0f; var roots2 = 0f
    var straw = 0f; var short = 0f; var room = 0f; var q = 0f; var got = 0f
    /** All it ate, kg per m². */
    val eaten get() = grass + grass2 + browse + browse2 + fruit + fruit2 + roots + roots2 + straw
}

/**
 * The share of the grass it would eat that kind [s] gets at under [snowMm] of snow (mm water): all of it on bare ground,
 * falling evenly to none at the depth it can dig through no more ([Species.snowLimitMm]). Grazing under snow is
 * digging: the deeper the snow, the more snow it moves and the more of its feeding time goes to clearing each mouthful,
 * until at its limit digging takes all of it. (The even fall is the simplest shape between the two ends the record
 * gives, bare ground and the limit. Digging is cheap work, caribou spend 118-481 J a stroke, Fancy and White 1985, so
 * the snow is felt as time lost from feeding, not as fat.)
 */
internal fun grassUnderSnow(s: Species, snowMm: Float): Float = (1f - snowMm / s.snowLimitMm).coerceIn(0f, 1f)

/**
 * How animals of [s] eating diet [d] feed where the forage nourishes [q], asking [want] kg per m² this week, from
 * [pantry]: grass (as far as it gets at it under the snow, [underSnow], see [grassUnderSnow]) and browse as far as the gut fills at that nourishment, fruit and roots, then
 * switching between them for a shortfall, then straw; into [out]. The one rule for the residents' week's grazing
 * ([HexFauna.graze]) and for the room they reckon a place has for more of them ([HexFauna.room]).
 */
internal fun forage(s: Species, d: Diet, q: Float, want: Float, underSnow: Float, access: Float, pantry: Pantry, out: Bites): Bites {
    val total = (d.grass + d.browse + d.fruit + d.roots).coerceAtLeast(1e-6f)
    // Horses and asses make up for poor forage by eating more of it; ruminants eat less of it.
    val fill = gutFill(s, q)
    // (As the residents: what it cannot get at is a share of the place's food it cannot reach, not a share
    // of its need it goes without; where food is plenty it still eats its fill.)
    val grass = pantry.grass(underSnow * want * fill * d.grass / total)
    val browse = pantry.browse(want * fill * d.browse / total)
    val fruit = pantry.fruit(want * d.fruit / total)
    val roots = pantry.roots(want * d.roots / total)
    // Diet switching: a food that fell short is made up from the others: grass and browse as far as the
    // gut holds (a wild ass short of grass eats shrubs), fruit and roots to at most (1 + SWITCH) times their
    // usual share (an ostrich out of fruit season picks more shoots and leaves).
    var gap = want - ((grass + browse) * q + fruit + roots)
    var grass2 = 0f; var browse2 = 0f; var fruit2 = 0f; var roots2 = 0f
    // But the gut holds only so much bulk a day: on poor forage a ruminant cannot make up by eating
    // more of it (switching shifts what it eats, not how much its gut takes).
    // (Its need is reckoned in forage of middling quality, [HexFauna.GUT_QREF]: on that it fills its need exactly.)
    var room = max(0f, want * fill / HexFauna.GUT_QREF - (grass + browse))
    // (What it switches to nourishes it as well as its gut digests fibre, [Species.fibreDigestion].)
    val qf = q * s.fibreDigestion
    if (gap > 0f && d.browse > 0f) { browse2 = pantry.browse(min(gap / qf, room)); gap -= browse2 * qf; room -= browse2 }
    if (gap > 0f && d.grass > 0f && underSnow > 0f) { grass2 = pantry.grass(underSnow * min(gap / qf, room)); gap -= grass2 * qf; room -= grass2 }
    if (gap > 0f && d.fruit > 0f) { fruit2 = pantry.fruit(min(gap, SWITCH * want * d.fruit / total)); gap -= fruit2 }
    if (gap > 0f && d.roots > 0f) { roots2 = pantry.roots(min(gap, SWITCH * want * d.roots / total)); gap -= roots2 }
    // What grass and browse could not give, straw makes up as far as there is any, at what its gut gets out of it.
    val short = max(0f, gap)
    val straw = pantry.straw(min(short * HexFauna.GUT_FILL * access, max(0f, room)))
    // Grass and browse nourish fully only when green; fruit and roots keep their value.
    val got = (grass + browse + grass2 + browse2) * q + fruit + roots + fruit2 + roots2 + min(short, straw * HexFood.roughage(s))
    out.grass = grass; out.browse = browse; out.fruit = fruit; out.roots = roots
    out.grass2 = grass2; out.browse2 = browse2; out.fruit2 = fruit2; out.roots2 = roots2
    out.straw = straw; out.short = short; out.room = room; out.q = q; out.got = got
    return out
}

/** The protein in [b], kg per m², with grass, browse and straw of the protein shares [grass], [browse] and [straw]. */
internal fun protein(b: Bites, grass: Float, browse: Float, straw: Float): Float =
    (b.grass + b.grass2) * grass + (b.browse + b.browse2) * browse +
        (b.fruit + b.fruit2 + b.roots + b.roots2) * HexFauna.PROTEIN_FRUIT + b.straw * straw

internal fun HexGroups.demography(weather: Weather, date: Date) {
    // Bands keep or move camp, gather materials and make their tools.
    followersByHex = groups.filter { it.alive && it.species.followsPeople && !it.species.human }.groupBy { hw.placeHex[it.place] }
    houseOf = groups.filter { g -> g.alive && g.band.let { it != null && !it.houseHex.none && it.house >= Thresholds.HOUSE_STANDING } }
        .associate { it.id to it.band!!.houseHex.i }
    Profile.part("people: camps, tools, culture") {
        for (g in groups) if (g.alive && g.band != null) {
            bandWeek(g, weather, date)
            cultureWeek(g)
            riteWeek(g, weather)
        }
        fightRaids(weather)
    }
    // Hunters remember where they hunted, and a pack long hungry on its ground gives it up.
    for (g in groups) {
        if (!g.alive) continue
        val h = hw.placeHex[g.place]
        if (g.recent[0] != h) { for (i in g.recent.size - 1 downTo 1) g.recent[i] = g.recent[i - 1]; g.recent[0] = h }
        g.lean = if (g.fed < Thresholds.HUNGRY) g.lean + 1 else 0
        // A pack long hungry keeps its ground but ranges wider over it (see [HexGroups.plan]).
    }
    val kinNow = HashMap<Long, Int>()
    for (g in groups) if (g.alive) kinNow.merge(g.species.index.toLong() * hex.size + hw.placeHex[g.place], g.size, Int::plus)
    // Where each kind's groups are, for outbreaks to pass between them.
    for (a in fauna.groupHeads) a.fill(0)
    for (g in groups) if (g.alive) fauna.groupHeads[g.species.index][hw.placeHex[g.place]] += g.size
    // Each group on its own core, with its own random stream; what it leaves for others (carcasses,
    // the counts, a split-off pack or band) is gathered and handed on after, in the groups' order.
    val list = groups.toList()
    val outcome = arrayOfNulls<Outcome>(list.size)
    IntStream.range(0, list.size).parallel().forEach { k ->
        val g = list[k]
        if (g.alive) outcome[k] = Profile.part("groups: life and death of each") { liveWeek(g, weather, date, kinNow, groupRandom(g.id, date.absoluteDay)) }
    }
    // Outbreaks that broke out this week rage from next week: started after every group has lived its
    // week, so no group's plague depends on which core another group's ran on.
    for (key in outbreaksStarting.sorted()) fauna.startOutbreak(Catalogue.all[(key / hex.size).toInt()], (key % hex.size).toInt())
    outbreaksStarting.clear()
    val born = ArrayList<HexGroup>()
    for ((k, o) in outcome.withIndex()) {
        val g = list[k]; val s = g.species
        if (o == null) {
            // Young whose last grown animal was killed this week (by hunters or rivals) die too.
            if (!g.alive && g.young > 0) {
                stats.deaths.merge(Tally.of(s, DeathCause.ORPHANED), g.young, Int::plus)
                small.deposit(DeadPool.CARRION, g.place, KgM2(HexFauna.YOUNG_WEIGHT * g.young * s.massKg / (land.areaKm2[g.place] * 1e6f)))
                g.young = 0
            }
            continue
        }
        if (o.carrionKg > 0f) small.deposit(DeadPool.CARRION, g.place, KgM2(o.carrionKg / (land.areaKm2[g.place] * 1e6f)))
        if (o.births > 0) stats.births.merge(s.name, o.births, Int::plus)
        note(g) { "born %d; died: %s".format(o.births, o.causes.filter { it.n > 0 }.joinToString(", ") { "${it.n} ${it.cause}" }.ifEmpty { "none" }) }
        for ((cause, k) in o.causes) if (k > 0) stats.deaths.merge(Tally.of(s, cause), k, Int::plus)
        fauna.record?.let { rec -> for ((cause, k) in o.causes) if (k > 0) rec.group(s, g.place, cause, k) }
        if (s.human) {
            val c = Biome.entries[biomeOfPlace[g.place]]
            for ((cause, k) in o.causes) if (k > 0) stats.hunting.merge(Tally.bandsDied(c, cause), k, Int::plus)
            if (o.births > 0) stats.hunting.merge(Tally.bandsIn(c, Tally.Bands.BORN), o.births, Int::plus)
            // And by how well clad the band was: what clothes are worth in lives.
            g.band?.let { b ->
                val clad = if (b.has(Tool.CLOTHES) >= 0.5f) Tally.Clad.CLAD else Tally.Clad.POORLY_CLAD
                stats.hunting.merge(Tally.bandsClad(clad, Tally.Bands.PEOPLE_WEEKS), g.size, Int::plus)
                for ((cause, k) in o.causes) if (k > 0) stats.hunting.merge(Tally.bandsCladDied(clad, cause), k, Int::plus)
            }
            // And, in the weeks it was cold through all the cover the band had, what each cover gave.
            val b = g.band
            if (b != null) {
                val t = weather.temperature[g.place]
                if (b.exposure(t) > 0f) {
                    fun add(k: Tally.Cold, v: Float) = stats.hunting.merge(Tally.bandsIn(c, k), (10f * v).roundToInt(), Int::plus)
                    add(Tally.Cold.WEEKS, 1f); add(Tally.Cold.SHORT, b.exposure(t)); add(Tally.Cold.AIR, EXPOSURE_C - t)
                    add(Tally.Cold.CLOTHES, b.clothesCover()); add(Tally.Cold.FIRE, PROTECT_FIRE * b.fire); add(Tally.Cold.SHELTER, b.roofCover(b.atHome))
                }
            }
        }
        if (o.orphans > 0) stats.deaths.merge(Tally.of(s, DeathCause.ORPHANED), o.orphans, Int::plus)
        // Children's deaths apart, for the life tables.
        if (s.human && o.youngDied > 0) stats.hunting.merge(Tally.of(s, Tally.OfKind.CHILDREN_DIED), o.youngDied, Int::plus)
        // A band that keeps the old way leaves a camp where someone died.
        if (s.human && o.deaths > 0) g.band?.let { b ->
            if (b.holds(Tradition.LEAVE_DEAD) && !b.holds(Tradition.BURIAL)) {
                b.unlucky = Unlucky(hw.hexOf(g.place), UNLUCKY_WEEKS)
                note(g) { "left the camp where its dead lie" }
            }
        }
        // A band that buries its dead buries them where it is: its ancestors' place.
        // For each taken by a beast, others got away hurt.
        g.band?.let { b ->
            val taken = o.causes.filter { it.cause.startsWith(DeathCause.TAKEN_BY) }.sumOf { it.n }
            if (taken > 0) {
                b.wounded(WOUNDED_PER_DEAD * taken, g.adults)
                b.beastLoss += taken / g.size.coerceAtLeast(1).toFloat()
            }
        }
        // Where a beast took one of them, the band remembers the place as dangerous.
        if (s.human && o.causes.any { it.cause.startsWith(DeathCause.TAKEN_BY) && it.n > 0 })
            g.memory.mark(hw.placeHex[g.place], PlaceMemory.seasonOf(date.month), stepOf(date), stepsPerYear, g.memory.danger, 1f)
        if (s.human && o.deaths > 0 && g.band?.holds(Tradition.BURIAL) == true) {
            g.memory.mark(hw.placeHex[g.place], PlaceMemory.seasonOf(date.month), stepOf(date), stepsPerYear, g.memory.dead, o.deaths.toFloat())
            land.burials[g.place] += o.deaths.toFloat()
            // Ornaments go into the grave with them: the more a band has, the richer its graves.
            g.band?.let { b -> val goods = min(b.ornaments, GRAVE_GOODS * b.ornaments * o.deaths)
                b.takeOrnaments(goods); land.graveGoods[g.place] += goods }
        }
        o.split?.let { c -> born += splitOff(g, c.adults, c.young) } // a young pack has no ground yet
        if (o.leavers > 0) {
            repeat(o.leavers) { born += splitOff(g, 1, 0) } // each goes alone, to find a mate and ground
            stats.meeting("young hunters left their pack", o.leavers)
        }
    }
    groups += born
    Profile.part("groups: merging and pairing") { mergeAndPair(date) }
}

/**
 * The group that splits off [g] with [adults] and [young] of its number: it takes its memory, condition, taste
 * and temper, a share of its larder by heads, and, for a band, a copy of what it knew and its share of its
 * tools, goods, elders, practised people and ages; and the places it remembers for each month.
 */
private fun HexGroups.splitOff(g: HexGroup, adults: Int, young: Int): HexGroup {
    val s = g.species
    val n = HexGroup(nextId++, s, g.place, adults, young)
    g.memory.copyInto(n.memory)
    n.fed = g.fed
    n.fat = g.fat
    n.tag = g.tag
    n.parent = g.id
    n.dry = g.dry
    n.herdsMet = g.herdsMet?.copyOf(); n.herdsMetOn = g.herdsMetOn; n.deathOdds = g.deathOdds
    n.birthMonth = g.birthMonth; n.bred = g.bred
    n.temper = Temperament.inherit(g.temper, n.id, seedBase)
    // A band that splits shares out its larder by heads.
    val share = (adults + young).toFloat() / (g.size + adults + young).coerceAtLeast(1)
    moveFood(g, n, g.larder * share)
    // Both halves keep what the band knew and share out its tools and goods.
    n.bold = g.bold
    g.band?.let { b -> n.band = Band(fauna.kinds.size).also { nb -> b.splitInto(nb, adults, g.adults, share, rnd) { riteInherit(it) } } }
    g.seasonPlace.copyInto(n.seasonPlace); g.seasonQ.copyInto(n.seasonQ)
    return n
}

/**
 * [n] of [g]'s grown members died, of any cause but old age (hunger, illness, a fight, a horn, a
 * crossing): in a band the elders and each stage of the childbearing years lose their share of them
 * (rounded at random, so single deaths fall on the old as often as their share says), so the age
 * structure stays whole and births are not reckoned on the dead. Never more than there are.
 */
internal fun adultsDied(g: HexGroup, n: Int, r: Random) {
    val dead = min(n, g.adults)
    if (dead <= 0) return
    g.band?.let { b ->
        b.elders -= min(b.elders, (dead * b.elders + r.nextInt(g.adults)) / g.adults)
        for (i in b.ageStage.indices) b.ageStage[i] -= min(b.ageStage[i], (dead * b.ageStage[i] + r.nextInt(g.adults)) / g.adults)
    }
    g.adults -= dead
    g.band?.let { b ->
        // (Rounding can leave more counted old than are left alive; the rest are of the first stage.)
        b.elders = min(b.elders, g.adults)
        for (i in b.ageStage.indices) b.ageStage[i] = min(b.ageStage[i], g.adults - b.elders - b.ageStage.take(i).sum())
    }
}

/**
 * What a group's week left for others: carcasses (kg), births, deaths by cause, orphans, a split-off part (adults, young),
 * and the young grown up this week that leave a pack on their own ([leavers]).
 */
internal class Outcome(val carrionKg: Float, val births: Int, val deaths: Int, val causes: List<Died>, val orphans: Int,
    val split: Split?, val youngDied: Int = 0, val leavers: Int = 0)

/** [n] of a group died of [cause] this week. */
internal data class Died(val cause: String, val n: Int)

/** The part that splits off a group: [adults] and [young]. */
internal class Split(val adults: Int, val young: Int)

/** One group's week of feeding on its reserves, births, deaths and growing up; with its own random stream [r]. */
internal fun HexGroups.liveWeek(g: HexGroup, weather: Weather, date: Date, kinNow: HashMap<Long, Int>, r: kotlin.random.Random): Outcome {
    val s = g.species
    feedOnReserves(g)
    if (g.birthMonth < 0) g.birthMonth = s.birthMonth
    if (date.month == offSeason(s, g.birthMonth) && date.dayOfMonth <= days) g.bred = 0f
    val inSeason = monthsFrom(g.birthMonth, date.month) < s.birthMonths
    var bornNow = 0
    var bornExpected = 0f
    // In a band, the elders are past bearing children; they help their daughters raise theirs.
    val band = g.band
    val mothers = mothersIn(g)
    val help = if (band != null) min(1f, band.elders * ELDER_MOTHERS / max(1, mothers)) else 0f
    // (Births follow one rule for every kind, groups and residents alike: each mother bears her
    // litter as her food and reserves can build it (people as they are fed), through the birth season; only where just the leading pair of a pack
    // breeds ([Species.packLitter]: wolves) is there one litter a pack. A pack bears only on ground of its own.)
    if (inSeason && g.adults >= 2 && !(s.pack && g.home.none)) {
        val births = if (s.packLitter > 0) {
            if (date.dayOfMonth <= days && date.month == g.birthMonth && !g.home.none) (s.packLitter * breeding(s, s.packLitter.toFloat(), g.fat, g.fed, days) + r.nextFloat()).toInt() else 0
        } else {
            val rate = if (s.human) {
                val stepsInSeason = s.birthMonths * Date.DAYS_PER_MONTH / days.toFloat()
                s.youngPerFemale * g.fed * settledFertility(g) * HUMAN_BIRTHS * tuning.humanBirths * (1f + GRANDMOTHERS * help) / stepsInSeason
            } else {
                // (By the residents' rule: a week's share of the season, no more than the mother has left of it, spaced by how crowded her kind is.)
                val share = seasonShare(s, g.birthMonth, max(0f, 1f - g.bred), date, days)
                g.bred += share
                // (Meat is rich in protein: their young are short of none.)
                val due = youngDue(s, kinKm2(g, kinNow), HexFauna.usualDensity(s), tuning.breedSpacing, 1f, share)
                due * breeding(s, due, g.fat, g.fed, days) * settledFertility(g) * (1f + GRANDMOTHERS * help)
            }
            bornExpected = mothers * rate
            // (A big litter over a short season is more than one a mother a week: the whole ones, and the rest by chance.)
            val whole = rate.toInt()
            mothers * whole + binomial(mothers, rate - whole, r)
        }
        g.young += births
        bornNow = births
        // The young are made of what their mothers ate: their weight comes off the mothers' reserves.
        if (births > 0) g.fat = max(0f, g.fat - fromReserves(s, births.toFloat() / max(1, g.adults / 2), g.fed, days))
    }
    val fouled = campFouling(g) * years
    // Wounds: mishaps of the work (the further the gatherers range, the more), and those hurt in
    // fights, hunts and by beasts last week; tended wounds heal faster, fester less and lame fewer.
    val festered = if (band != null) woundsWeek(band) else 0f
    // Beasts of prey about: now and then one takes a gatherer, a child or an old one alone.
    val (stalked, stalker) = if (band != null) stalkedBy(g, band, kinNow) else Stalking.NONE
    val venom = venomOdds(s, g.place.i, weather)
    // Children of a closed band are inbred; medicine saves some of the sick and wounded.
    val inbred = if (band != null) INBREEDING_DEATHS * band.inbred * years else 0f
    // (Healers save some of the sick and wounded, the more the more they know.)
    val healed = healedShare(band)
    // (And practised midwives and carers lose fewer infants.)
    val cared = band?.lapse(Skill.MIDWIFERY)?.let { 1f + HEALING_REACH * (it - 1f) } ?: 1f
    // Sicknesses of their own: the flux, marsh fever, the cough (see [Illness]); healers save some.
    val ill = if (band != null) illnessWeek(g, band, weather, r).also { for (i in Illness.entries) it[i] *= healed } else null
    val thirst = thirstWeek(g)
    // Where its kind is crowded, an outbreak may break out.
    val sick = sicknessWeek(g, weather, kinNow, r)
    val disease = fauna.outbreakDeaths(s, g.place.i) + sick
    // Packs without ground of their own die more: fights, injuries and hunger on others' ground.
    val homeless = if (s.pack && g.home.none) DISPERSAL_DEATHS * years else 0f
    // Every hazard of the week, once: its cause and the odds it adds for a grown one and for a young one.
    val hazards = arrayListOf(
        Hazard(DeathCause.OTHER.label, healed * s.backgroundMortality * (if (s.human) tuning.humanOther else 1f) * years,
            healed * cared * s.youngMortality * (if (s.human) tuning.humanYoung else 1f) * years))
    placeHazards(g, g.place.i, weather, g.fed, thirst, sick, band?.fire ?: 0f, band?.atHome ?: false) { c, a, y -> hazards += Hazard(c, a, y) }
    hazards.addAll(listOf(
        Hazard(DeathCause.HOMELESS.label, homeless, homeless),
        Hazard(DeathCause.CAMP_FOULING.label, 0f, fouled), Hazard(DeathCause.SNAKES.label, venom, venom), Hazard(DeathCause.INBREEDING.label, 0f, inbred),
        Hazard(DeathCause.takenBy(stalker), stalked, YOUNG_STALKED / (band?.childWatch ?: 1f).coerceAtLeast(0.25f) * stalked),
        Hazard(DeathCause.WOUNDS.label, festered, 0f)))
    ill?.let { a -> for (it in Illness.entries) hazards += Hazard(it.label, a[it], a[it] * it.young) }
    // The elders' care spares some of the young.
    val spared = 1f - ELDER_CARE * help
    var adultSum = 0f; var youngSum = 0f
    for (h in hazards) { adultSum += h.adult; youngSum += h.young }
    val adultOdds = adultSum.coerceIn(0f, 0.9f)
    g.deathOdds = adultOdds
    val youngOdds = (youngSum * spared).coerceIn(0f, 0.95f)
    if (s.human && g.young > 0) for (h in hazards) if (h.young > 0f)
        stats.childDeaths.computeIfAbsent(h.cause) { DoubleAdder() }.add((g.young * spared * h.young).toDouble())
    val da = binomial(g.adults, adultOdds, r); val dy = binomial(g.young, youngOdds, r)
    // (For training: what the week's odds made likely, before the elders' deaths are drawn.)
    val diedExpected = g.adults * adultOdds + g.young * youngOdds +
        (band?.let { b -> (b.elders - (da * b.elders.toFloat() / g.adults.coerceAtLeast(1))).coerceAtLeast(0f) * min(1f, ELDER_DEATHS * years) } ?: 0f)
    // Elders die as the others do (their share of the adults who died), and of old age besides.
    adultsDied(g, da, r)
    var oldAge = 0
    if (band != null) {
        oldAge = binomial(band.elders, ELDER_DEATHS * years, r)
        band.elders -= oldAge; g.adults -= oldAge
    }
    if (s.human) g.luck += (bornNow - bornExpected) - (da + dy + oldAge - diedExpected)
    // The sick and the newborn teach their carers.
    band?.let { b -> if (disease > 0f || da > 0) b.practise(Skill.HERBS, 0.2f * PRACTICE); if (bornNow > 0) b.practise(Skill.MIDWIFERY) }
    g.young -= dy
    // The dead lie where they fell, for the scavengers and the soil.
    var carrion = (da + oldAge + 0.3f * dy) * s.massKg
    // The week's deaths are shared among the causes by how much each weighed, the grown and the young apart ("other": age, accidents, ordinary illness).
    val causes = shareDeaths(hazards, da, dy, r)
    if (oldAge > 0) causes += Died(DeathCause.OLD_AGE.label, oldAge)
    val grown = growUp(g, r)
    land.books?.adder(Books.Stuff.MATTER, "groups born and grew up")?.add(((HexFauna.YOUNG_WEIGHT * bornNow + (1f - HexFauna.YOUNG_WEIGHT) * grown) * s.massKg).toDouble())
    if (g.adults == 0) {
        // Young left without a grown animal of their kind die.
        val orphans = g.young
        carrion += 0.3f * orphans * s.massKg
        g.young = 0
        return Outcome(carrion, bornNow, da + oldAge + dy, causes, orphans, null, dy + orphans)
    }
    // Young hunters grown up leave a pack already as big as its kind's usual one, each alone, to find a mate
    // and ground of their own: how packs are founded (a pack that only grows until it splits seldom gets there).
    val leavers = if (s.pack && band == null) min(grown, max(0, g.adults - s.groupSize)) else 0
    g.adults -= leavers
    var split: Split? = null
    // Bands that feast together hold together longer.
    if (g.size > maxSize(g)) {
        split = Split(g.adults / 2, g.young / 2)
        g.adults -= split.adults; g.young -= split.young
    }
    return Outcome(carrion, bornNow, da + oldAge + dy, causes, 0, split, dy, leavers)
}

/** Reserves carry [g] through lean weeks; a group that ate (nearly) its fill lays them on, most in late summer and autumn. */
private fun HexGroups.feedOnReserves(g: HexGroup) {
    val s = g.species
    if (g.fed < WELL_FED) { val draw = min(g.fat, (1f - g.fed) * days); g.fat -= draw; g.fed = min(1f, g.fed + draw / days) }
    else g.fat = min(s.maxFatDays, g.fat + FAT_EFFICIENCY * g.surplus)
    g.surplus = 0f
}

/**
 * [band]'s wounds this week: mishaps of the work and last week's hurts heal, the tended faster; some lame, some fester.
 * Returns the deaths from festering, a year's rate.
 */
private fun HexGroups.woundsWeek(band: Band): Float {
    val care = 0.5f * band.medicine + 0.5f * band.skill(Skill.HERBS)
    band.hurt = min(1f, band.hurt + MISHAPS * band.gatherRange * years)
    val heal = min(1f, (HEAL_UNTENDED + (HEAL_TENDED - HEAL_UNTENDED) * care) * days / 7f)
    val fester = FESTER * (1f - 0.8f * care) * band.hurt * days / 7f
    val mended = band.hurt * heal
    band.lame = min(1f, band.lame * (1f - LAME_TURNOVER * years) + mended * LAMED * (1f - 0.5f * care))
    band.hurt = max(0f, band.hurt - mended - fester)
    return fester
}

/** Whether a group of [s] can drink at place [p] ([Hazards.canDrink]). */
internal fun HexGroups.canDrink(s: Species, p: Int) = Hazards.canDrink(land.drink, s, p)

/** [g] drinks where there is water; otherwise days without water mount, eased by the water in green food. Returns the deaths from thirst, a year's rate. */
private fun HexGroups.thirstWeek(g: HexGroup): Float {
    g.dry = dryAfter(g, g.place.i, g.fed)
    return thirstOdds(g, g.place.i, g.dry)
}

/** Days [g] would have gone without water by the end of the week at place [p], fed [fed]: none where it can drink. */
internal fun HexGroups.dryAfter(g: HexGroup, p: Int, fed: Float): Float =
    if (canDrink(g.species, p)) 0f
    // Plant-eaters get water from green food; hunters from the body water of what they eat, in any season.
    else g.dry + Hazards.dryDays(g.species, days, fed, land.plants.green[p])

/** The share of [g] that dies of thirst at place [p], [dry] days without water. */
internal fun HexGroups.thirstOdds(g: HexGroup, p: Int, dry: Float): Float {
    val s = g.species
    // (People carry water in skins and know every spring: they suffer far less from it than animals.)
    // (Strangers to the country do not know its springs.)
    val knowsWater = g.band?.knows(hw.placeHex[p]) ?: true
    return Hazards.thirst(s, dry) *
        (if (s.human) HUMAN_THIRST * tuning.humanThirst * (if (knowsWater) 1f else STRANGER_THIRST) else 1f)
}

/**
 * The mothers among [g]'s adults: half of them, or in a band, the women in their bearing years: most in
 * their twenties and early thirties, few in their teens, fewer after thirty-five, none past the
 * childbearing years (the elders).
 */
private fun mothersIn(g: HexGroup): Int {
    val band = g.band
    return if (band != null) {
        val first = max(0, g.adults - band.elders - band.ageStage[0] - band.ageStage[1])
        ((FERTILE_BY_SPAN[0] * first + FERTILE_BY_SPAN[1] * band.ageStage[0] + FERTILE_BY_SPAN[2] * band.ageStage[1]) / 2f).toInt()
    } else g.adults / 2
}

/**
 * [g]'s deaths of sickness this week besides a raging outbreak's, a year's rate: people's crowd
 * sicknesses; hunters' mange, distemper and rabies from packs of their kind about. Where its kind is
 * crowded, an outbreak may break out (drawn from [r]).
 */
private fun HexGroups.sicknessWeek(g: HexGroup, weather: Weather, kinNow: HashMap<Long, Int>, r: Random): Float {
    val s = g.species
    val usual = HexFauna.usualDensity(s)
    // (Hunters by their kind's numbers over the ground they range over, as residents by theirs in their place.)
    val packs = if (s.human) 0f else Hazards.crowding(kinKm2(g, kinNow), usual)
    val crowdNow = if (s.human) peopleCrowding(g) else packs
    if (r.nextFloat() < HexFauna.OUTBREAK_START * (if (s.human) PEOPLE_OUTBREAK * tuning.peopleOutbreak else 1f) * crowdNow * crowdNow * years)
        outbreaksStarting += s.index.toLong() * hex.size + hw.placeHex[g.place]
    return if (s.human) peopleSickness(g) * years * (1f + HexFauna.MALNOURISHED * (1f - g.fed))
        // Hunters catch mange, distemper and rabies where their kind crowds the ground.
        else Hazards.crowdDisease(packs, g.fed, years)
}

/**
 * [g]'s kind's heads per km² about it: its numbers, its own included, over the ground it ranges over (its hex, and the
 * six round it where its kind's home range reaches beyond one hex, [HexGroups.rangesWide]).
 */
private fun HexGroups.kinKm2(g: HexGroup, kinNow: HashMap<Long, Int>): Float {
    val s = g.species
    val h0 = hw.placeHex[g.place]
    var heads = 0; var hexes = 0
    for (k in -1 until (if (rangesWide(s)) 6 else 0)) {
        val q = if (k < 0) h0 else hex.neighbour(h0, k)
        if (q >= 0) {
            heads += kinNow[s.index.toLong() * hex.size + q] ?: 0
            hexes++
        }
    }
    return heads / (hexes * hex.areaKm2.toFloat())
}

/** One of the week's hazards: its [cause] and the odds it adds that a grown one ([adult]) or a young one ([young]) dies. */
private class Hazard(val cause: String, val adult: Float, val young: Float)

/** Where the week's hazards go as they are reckoned: each cause, and the odds it adds for a grown one and for a young one. */
internal fun interface HazardSink { fun add(cause: String, adult: Float, young: Float) }

/**
 * The week's hazards to [g] that come of the place it is in ([p]) and how it fares there, into [into]: hunger at [fed],
 * the cold past its coat (or clothes, a fire of [fire] and its house if [home]), exposure, thirst at [thirst] and its
 * kind's everyday sickness at [sick], an outbreak raging there, fire, flood and deep snow, crocodiles at the water
 * and eagles. The one rule for the week's deaths ([liveWeek]) and for those a group foresees
 * at a place it weighs ([HexGroups.deathsAt]).
 */
internal fun HexGroups.placeHazards(g: HexGroup, p: Int, weather: Weather, fed: Float, thirst: Float, sick: Float,
    fire: Float, home: Boolean, into: HazardSink) {
    val s = g.species; val b = g.band; val t = weather.temperature[p]
    val hunger = Hazards.hunger(fed)
    // People feel the cold as their clothes allow.
    // Exposure: the unprotected die of the cold within days.
    val exposure = b?.let { min(0.9f, EXPOSURE * it.exposure(t, fire, home) * days / 7f) } ?: 0f
    val cold = Hazards.cold(s, b?.feels(t, fire, home) ?: t, weather.date.month)
    // An outbreak raging where it is kills its share.
    val plague = fauna.outbreakDeaths(s, p)
    // Eagles take the young of the smaller hoofed animals in the open.
    val eagle = if (s.human) 0f else fauna.eagleOdds(s, p)
    val (fireA, floodA, snowA) = fauna.calamities(s, p, weather, false)
    val (fireY, floodY, snowY) = fauna.calamities(s, p, weather, true)
    val croc = if (s.human) 0f else fauna.crocodileOdds(p, weather)
    into.add(DeathCause.HUNGER.label, hunger, 1.5f * hunger); into.add(DeathCause.COLD.label, cold, 1.5f * cold)
    into.add(DeathCause.EXPOSURE.label, exposure, 1.5f * exposure); into.add(DeathCause.THIRST.label, thirst, 1.5f * thirst)
    into.add(DeathCause.DISEASE.label, sick, sick); into.add(DeathCause.OUTBREAK.label, plague, plague)
    into.add(DeathCause.FIRE.label, fireA, fireY); into.add(DeathCause.FLOOD.label, floodA, floodY); into.add(DeathCause.SNOW.label, snowA, snowY)
    into.add(DeathCause.CROCODILES.label, 0f, croc); into.add(DeathCause.EAGLES.label, 0f, eagle)
}

/**
 * [placeHazards] as [g] foresees them at place [p], weighing it: no everyday sickness, and its band's fire and house as they
 * would be there.
 */
internal fun HexGroups.foreseenHazards(g: HexGroup, p: Int, weather: Weather, fed: Float, thirst: Float, into: HazardSink) {
    val b = g.band
    placeHazards(g, p, weather, fed, thirst, 0f, b?.let { fireAt(g, it, p) } ?: 0f, b?.let { atHouse(it, p) } ?: false, into)
}

/** Vipers and scorpions, out in the warm months: the odds for people at place [p] this week. */
internal fun HexGroups.venomOdds(s: Species, p: Int, weather: Weather) = if (s.human && weather.temperature[p] > 18f) VENOM * years else 0f

/** The week's deaths, [adults] and [young], shared among the [hazards] by how much each weighed for them (drawn from [r]); what is left over is "other". */
private fun HexGroups.shareDeaths(hazards: List<Hazard>, adults: Int, young: Int, r: Random): ArrayList<Died> {
    val died = LinkedHashMap<String, Int>()
    for ((n, ofYoung) in listOf(adults to false, young to true)) {
        var left = n; var weight = 0f
        for (h in hazards) weight += if (ofYoung) h.young else h.adult
        for (h in hazards) {
            if (left <= 0 || weight <= 0f) break
            val w = if (ofYoung) h.young else h.adult
            val k = if (w >= weight) left else binomial(left, w / weight, r)
            if (k > 0) died.merge(h.cause, k, Int::plus)
            left -= k; weight -= w
        }
        if (left > 0) died.merge(DeathCause.OTHER.label, left, Int::plus)
    }
    return died.entries.mapTo(ArrayList()) { Died(it.key, it.value) }
}

/** [g]'s young grow up, and in a band the adults age through three spans of adult life, then become elders (drawn from [r]); returns how many young grew up. */
private fun HexGroups.growUp(g: HexGroup, r: Random): Int {
    val s = g.species
    val band = g.band
    val up = binomial(g.young, years / s.yearsToMaturity, r)
    g.young -= up; g.adults += up
    // Adults age through three spans of adult life, then become elders.
    band?.let { b ->
        val rate = 3f * years / ADULT_YEARS
        val toElder = binomial(b.ageStage[1], rate, r); val toThird = binomial(b.ageStage[0], rate, r)
        val first = max(0, g.adults - b.elders - b.ageStage[0] - b.ageStage[1])
        val toSecond = binomial(first, rate, r)
        b.elders += toElder; b.ageStage[1] += toThird - toElder; b.ageStage[0] += toSecond - toThird
    }
    return up
}

/**
 * Yearly deaths from crowding among people: fevers and worms where many camp near each other, by the
 * people in a band's hex and the six round it.
 */
private fun HexGroups.peopleSickness(g: HexGroup): Float {
    val dens = peopleCrowding(g)
    // (Marsh fever is its own sickness, not counted here: see [Illness].)
    return PEOPLE_SICK * tuning.peopleSick * dens * dens
}

/** People round a band's camp (its hex and the six round it), per km², against [PEOPLE_FULL_KM2]. */
internal fun HexGroups.peopleCrowding(g: HexGroup, h: Int = hw.placeHex[g.place]): Float {
    var heads = 0; var near = false
    for (k in -1 until 6) {
        val q = if (k < 0) h else hex.neighbour(h, k)
        if (q < 0) continue
        if (q == hw.placeHex[g.place]) near = true
        for (p in hw.placesOf(q)) heads += headsHere[kindSlot[g.species.index]][p]
    }
    // (About a hex it would move to, its own heads are not counted there yet.)
    if (!near) heads += g.size
    return heads / (7f * hex.areaKm2.toFloat()) / PEOPLE_FULL_KM2
}

/** The share of the sick and wounded of [band] its healers do not save (1 for a group with no band). */
internal fun healedShare(band: Band?) = (1f - 0.15f * (band?.medicine ?: 0f)) * (band?.lapse(Skill.HERBS)?.let { 1f + HEALING_REACH * (it - 1f) } ?: 1f)

/** Small bands join kin, and lone hunters find mates (see [demography]). */
internal fun HexGroups.mergeAndPair(date: Date) {
    // A band grown too small to hunt, gather and defend itself joins the nearest kin band that has room.
    val bandsByHex = HashMap<Int, ArrayList<HexGroup>>()
    for (g in groups) if (g.alive && g.band != null) bandsByHex.getOrPut(hw.placeHex[g.place]) { ArrayList() } += g
    for (g in groups) {
        val b = g.band ?: continue
        if (!g.alive || g.adults >= SMALL_BAND) continue
        var best: HexGroup? = null; var bestKin = -1f
        val h0 = hw.placeHex[g.place]
        var ring = listOf(h0); val seen = hashSetOf(h0)
        for (r in 0..VISIT_RINGS) {
            for (h in ring) for (o in bandsByHex[h].orEmpty()) {
                if (o === g || !o.alive || o.size + g.size > maxSize(o)) continue
                val k = kinship(b.lineage, o.band!!.lineage) / (1f + r)
                if (k > bestKin) { bestKin = k; best = o }
            }
            if (best != null || r == VISIT_RINGS) break
            ring = hex.nextRing(ring, seen)
        }
        val o = best ?: continue
        joinBand(o, g, stepOf(date))
        stats.meeting("small bands joined kin")
    }
    // A hunter left on its own looks for a mate: lone adults of a kind within a few hexes pair up, and
    // the pair holds the ground of the one that had any.
    val lone = HashMap<Int, ArrayList<HexGroup>>()
    for (g in groups) if (g.alive && g.species.pack && g.adults == 1) lone.getOrPut(g.species.index) { ArrayList() } += g
    for ((_, list) in lone) {
        val used = HashSet<Int>()
        for (a in list) {
            if (a.id in used) continue
            // (As far as it chooses to look; and only among its own lineage while variants are weighed against each other.)
            val b = list.firstOrNull { it.id != a.id && it.id !in used && it.tag == a.tag &&
                hex.distanceKm(hw.placeHex[it.place], hw.placeHex[a.place]) <= MATE_SEARCH_KM * a.will[AnimalWay.MATE] } ?: continue
            used += a.id; used += b.id
            a.adults += b.adults; a.young += b.young
            a.fed = (a.fed + b.fed) / 2f; a.fat = (a.fat + b.fat) / 2f; a.bred = (a.bred + b.bred) / 2f
            if (a.home.none) claimGround(a, b.home)
            b.mergedSize = b.size; b.mergedInto = a.id
            b.adults = 0; b.young = 0
        }
    }
}

/** Band [from] joins band [into]: its people, food, goods and knowledge come with it. */
private fun HexGroups.joinBand(into: HexGroup, from: HexGroup, week: Int) {
    val w = from.adults.toFloat() / (into.adults + from.adults).coerceAtLeast(1)
    into.band!!.absorb(from.band!!, from.adults, w, into.stores[Store.DRIED], from.stores[Store.DRIED],
        into.stores[Store.SEED], from.stores[Store.SEED], into.id, from.id)
    into.fed += w * (from.fed - into.fed); into.fat += w * (from.fat - into.fat); into.bred += w * (from.bred - into.bred)
    for (i in Store.entries) addFood(into.stores, into.storeKgPer, i, from.stores[i], from.stores[i] * from.storeKgPer[i])
    into.memory.hear(from.memory, week, stepsPerYear, 1f)
    // (Its cache is left: no one goes back for it.)
    dropCache(from.band!!)
    from.mergedSize = from.size; from.mergedInto = into.id
    into.adults += from.adults; into.young += from.young
    from.adults = 0; from.young = 0; from.larder = 0f
}

/**
 * Every group remembers where it was and how it fared; bands also remember the place where they did best in each month:
 * how well they fed there, by the share of them that would live through a month of the week's odds (a place they fed
 * well at but went dry, froze or sickened at is no good place).
 */
internal fun HexGroups.remember(date: Date) {
    val m = date.month
    val weeksAMonth = stepsPerYear / 12f
    // Every group remembers where it was this week and how it fared there.
    val week = stepOf(date)
    for (g in groups) if (g.alive) g.memory.record(hw.placeHex[g.place.i], PlaceMemory.seasonOf(m), g.fed, land.drink.water[g.place.i], fauna.afraid(g.place.i), week, stepsPerYear)
    for (g in groups) {
        if (g.species.pack) continue
        // At the remembered place it learns how good it really is; elsewhere the memory fades slowly,
        // and a clearly better place takes its place.
        val fared = g.fed * (1f - g.deathOdds).pow(weeksAMonth)
        if (g.seasonPlace[m] < 0) { g.seasonPlace[m] = g.place.i; g.seasonQ[m] = fared }
        else if (g.seasonPlace[m] == g.place.i) g.seasonQ[m] += 0.3f * (fared - g.seasonQ[m])
        else {
            g.seasonQ[m] *= MEMORY_FADE
            if (fared > g.seasonQ[m] + 0.1f) { g.seasonPlace[m] = g.place.i; g.seasonQ[m] = fared }
        }
    }
}

/**
 * The yearly-scaled share of a band taken by beasts of prey this week, and the beast most to blame:
 * lions, leopards, bears, wolves and hyenas that range its hex now and then take one who is alone
 * at the gathering, a child, an old one. The thicker the cover the likelier; a fire at night, dogs,
 * lookouts, company and an eye for sign (tracking) keep them off. No set battle: the beasts take
 * the unwary, not armed men.
 */
internal fun HexGroups.stalkedBy(g: HexGroup, b: Band, kinNow: HashMap<Long, Int>): Stalking {
    val h = hw.placeHex[g.place]; val p = g.place
    val hexKm2 = hex.areaKm2.toFloat()
    var pressure = 0f; var worst = ""; var worstP = 0f
    for (k in kinds) {
        val w = k.takesPeople; if (w <= 0f) continue
        val heads = kinNow[k.index.toLong() * hex.size + h] ?: continue
        val v = w * heads * k.massKg / hexKm2
        pressure += v; if (v > worstP) { worstP = v; worst = k.name }
    }
    for ((ki, k) in fauna.kinds.withIndex()) {
        val w = k.takesPeople; if (w <= 0f) continue
        val v = w * (fauna.adults[ki][p] + fauna.young[ki][p]) * k.massKg
        pressure += v; if (v > worstP) { worstP = v; worst = k.name }
    }
    if (pressure <= 0f) return Stalking.NONE
    // (Where one was taken the band remembers the danger.)
    return Stalking(stalkRate(g, b, p.i, pressure), worst)
}

/** The man-eaters' flesh in resident animals at place [p], kg/km², weighted by how readily each takes people ([Species.takesPeople]). */
internal fun HexGroups.residentManEaters(p: Int): Float {
    var v = 0f
    for ((ki, k) in fauna.kinds.withIndex()) { val w = k.takesPeople; if (w > 0f) v += w * (fauna.adults[ki][p] + fauna.young[ki][p]) * k.massKg }
    return v
}

/** The yearly-scaled share of band [b] (group [g]) beasts of prey would take a week camped at place [p], where their flesh is [pressure] kg/km² (see [stalkedBy]). */
internal fun HexGroups.stalkRate(g: HexGroup, b: Band, p: Int, pressure: Float): Float {
    if (pressure <= 0f) return 0f
    val h = hw.placeHex[p]
    val cover = 0.5f + land.plants.cover(p)
    val guard = (1f - FIRE_GUARD * b.nightGuard) * (1f - DOGS_GUARD * min(1f, b.dogShare)) * (1f - LOOKOUTS_GUARD * min(1f, 10f * b.scoutShare))
    // (Gatherers spread further from camp leave more ground to watch: about as its square.)
    val spread = b.gatherRange * b.gatherRange
    val company = (STALKED_BAND / max(1, g.size).toFloat()).coerceIn(0.5f, 2f).pow(0.3f)
    val sign = b.lapse(Skill.TRACK).pow(0.5f)
    // (Beasts lie up by the paths people and game use: a camp on a busy way is the more dangerous.)
    return STALKED * pressure / (pressure + STALKED_HALF) * cover * guard * company * sign * spread * (1f + 0.5f * AMBUSH * wear.at(h)) * years
}

/** A band's [rate] of loss to beasts of prey (yearly-scaled share) and the beast most to blame, [by] ("" when none). */
data class Stalking(val rate: Float, val by: String) {
    companion object { val NONE = Stalking(0f, "") }
}

/**
 * Of the beasts that take people ([Species.takesPeople]): the yearly share of people they take at most
 * where they are thick, from the unwary in thick cover with no fire, dogs or lookouts; density of beast flesh, kg/km² weighted, at which they take
 * half that; a band size the company factor is set for; how much a fire at night, dogs and lookouts
 * each keep off; how much more often children are taken.
 */
const val STALKED = 0.02f
const val STALKED_HALF = 3f
const val STALKED_BAND = 25f
const val FIRE_GUARD = 0.5f
const val DOGS_GUARD = 0.5f
const val LOOKOUTS_GUARD = 0.3f
const val YOUNG_STALKED = 2f
