package inflation.ecology

import inflation.ecology.HexFauna.Companion.EXTINCT
import inflation.ecology.HexFauna.Companion.OUTBREAK_START
import inflation.ecology.HexFauna.Companion.STRIFE_CROWD
import inflation.ecology.HexFauna.Companion.STRIFE_DEATHS
import inflation.ecology.HexFauna.Companion.STRIFE_YOUNG
import inflation.ecology.HexFauna.Companion.YOUNG_WEIGHT
import inflation.Par
import inflation.EnumFloats
import inflation.enumFloats
import inflation.Profile
import inflation.world.Place
import inflation.world.get
import inflation.world.set
import inflation.Books
import inflation.world.Date
import inflation.world.Weather
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

// The residents' births and deaths: the young born in season, what kills grown and young, and the books of their deaths.

/**
 * The share of a grown [k]'s body at [p] that is fat: the most it can carry ([Species.mostFatShare]) as far as its
 * reserves are full ([reserve] against [Species.maxFatDays]). Fat at the end of a lean winter, fat in a good autumn.
 */
fun HexFauna.fatShare(k: Int, p: Place): Float {
    val s = kinds[k]
    return s.mostFatShare * (reserve[k][p] / s.maxFatDays.coerceAtLeast(1e-3f)).coerceIn(0f, 1f)
}

internal fun HexFauna.breedAndDie(weather: Weather, date: Date) {
    stepDamp(); reckonShared()
    // (Each kind's places in [BREED_BLOCKS] blocks that keep a hex whole, each block on its own core with its own tallies, added
    // up after in block order: the blocks do not depend on the cores, so a run is the same on any number of them.)
    val blocks = BREED_BLOCKS; val bounds = breedBounds(blocks)
    val deadB = Array(kinds.size * blocks) { DoubleArray(ResidentDeath.entries.size) }
    val fadedB = DoubleArray(kinds.size * blocks); val bornB = DoubleArray(kinds.size * blocks); val eggsB = DoubleArray(kinds.size * blocks)
    // Each kind on its own core, with its own carcasses, added up after.
    val carcasses = Array(kinds.size) { FloatArray(n) }
    val bornBook = land.books?.adder(Books.Stuff.MATTER, "residents born and grew up")
    stepYoungShare(date, bornBook)
    val ids = kindIds.toList()
    Par.forEach("residents: births, deaths", ids.size * blocks) { job -> val k = ids[job / blocks]; val blk = job % blocks; val kb = k.i * blocks + blk
        Profile.kind("residents: births, deaths", kinds[k].name) {
        val s = kinds[k]
        val carcassKg = carcasses[k]
        val grow = years / s.yearsToMaturity
        val thinGone = if (tuning.thinFadeDays > 0f) HexFauna.EXTINCT_FLOOR else EXTINCT
        val a = adults[k]; val y = young[k]; val f = fed[k]; val dk = dry[k]; val leavingK = leaving[k]
        val hz = enumFloats<Danger>()
        for (pi in bounds[blk] until bounds[blk + 1]) { val p = Place(pi)
            if (a[p] + y[p] <= 0f) { dk[p] = 0f; continue }
            val area = land.areaKm2[p]
            // Births: half the adults are mothers; a mother bears what her food and reserves can build.
            val share = seasonShare(k, p, date)
            // (The crowding the young are born to and the step's deaths go by is the step's midway heads: the heads it began
            // with and half the young a first reckoning on those gives, then the births reckoned again on that. One reckoning
            // at any step length (plan section 52.A.1): born all on the step's first crowding, a fast breeder would find
            // ranges its own young had taken; on its last, the whole step would be charged with its end's crowd.)
            var crowdHeads = a[p] + y[p]
            var bornHere = 0f
            if (share > 0f) {
                bred[k][p] += share
                fun bornAt(heads: Float): Float { val due = dueYoung(k, p, heads, protein[k][p], share); return 0.5f * a[p] * due * breeding(s, due, reserve[k][p], f[p], days) }
                crowdHeads += 0.5f * bornAt(crowdHeads)
                val hatch = hatching(s, p)
                val laid = dueLaid(k, p, crowdHeads, protein[k][p], share); val due = laid * hatch
                val born = 0.5f * a[p] * due * breeding(s, due, reserve[k][p], f[p], days)
                y[p] += born
                bornHere = born
                // (The eggs the robbers took are left as carrion at the nest, as the hens made them.)
                // (As many of the eggs as of the hatched the hens' food and reserves build: [breeding]'s share of them.)
                if (hatch < 1f && laid > 0f) { val robbed = 0.5f * a[p] * laid * (1f - hatch) * breeding(s, due, reserve[k][p], f[p], days) * area
                    val eggKg = HexFauna.newbornShare(s, s.birthMonth) * s.massKg
                    carcassKg[p] += robbed * eggKg; bornBook?.add((robbed * eggKg).toDouble()); eggsB[kb] += robbed.toDouble() }
                bornB[kb] += (born * area).toDouble()
                bornBook?.add((born * area * youngShare[k.i] * s.massKg).toDouble())
                // The young are made of what their mothers ate: what the week's food did not build comes off the mothers' reserves (as in groups).
                if (born > 0f && a[p] > 0f) reserve[k][p] = max(0f, reserve[k][p] - fromReserves(s, born / (0.5f * a[p]), f[p], days))
            }
            // Deaths.
            // Thirst by the groups' rule: an animal drinks where water is within half its days without
            // water's going (or gets enough from green food and prey); beyond that, its days without water
            // mount week to week, and past what it can bear they kill as they do a pack.
            if (drinks(s, p)) dk[p] = 0f
            else dk[p] += Hazards.dryDays(s, days, f[p], land.plants.green[p])
            hazards(k, p, weather, date.month, f[p], dk[p], crowdHeads + keptKm2(k, p), hz)
            spent[k][p] = Hazards.spentAfter(s, spent[k][p], f[p], days)
            val hunger = hz[Danger.HUNGER]; val cold = hz[Danger.COLD]; val heat = hz[Danger.HEAT]; val thirst = hz[Danger.THIRST]; val disease = hz.disease()
            val crowd = hz[Danger.CROWD]
            // Where they are crowded far past the usual, now and then a plague breaks out among them.
            val over = crowd - 1f
            if (over > 0f && hash(p, k) < OUTBREAK_START * over * over * years) startOutbreak(s, hw.placeHex[p])
            val fireA = hz[Danger.FIRE_A]; val floodA = hz[Danger.FLOOD_A]; val snowA = hz[Danger.SNOW_A]
            val adultSum = s.backgroundMortality * years + hunger + cold + heat + thirst + disease + fireA + floodA + snowA + hz[Danger.STRIFE]
            val adultOdds = adultSum.coerceIn(0f, Hazards.MAX_GROWN_ODDS)
            val eagle = youngTakenOdds(s, p); val croc = crocodileOdds(p, weather)
            val starved = starvedOdds(protein[k][p])
            val youngSum = youngSum(s, hz, eagle, croc, starved)
            val youngOdds = youngSum.coerceIn(0f, Hazards.MAX_YOUNG_ODDS)
            // (The young born this step face its deaths only from their birth on: [StepClock.newbornExposure], 1 at one tick a step.)
            val yExposed = y[p] - bornHere * (1f - clock.newbornExposure)
            val da = a[p] * adultOdds; val dy = yExposed * youngOdds
            // (The causes are booked as shares of the deaths that happen: where the odds are capped, each cause is cut alike.)
            val ca = if (adultSum > 0f) a[p] * adultOdds / adultSum * area else 0f
            val cy = if (youngSum > 0f) yExposed * youngOdds / youngSum * area else 0f
            bookDeaths(k, p, ca, cy, hz, eagle, croc, starved, deadB[kb])
            sickBook[3 * k.i].add(((ca + cy) * hz[Danger.OUTBREAK]).toDouble())
            sickBook[3 * k.i + 1].add(((a[p] + y[p]) * area).toDouble()); sickBook[3 * k.i + 2].add(((a[p] + y[p]) * area * crowd * crowd).toDouble())
            a[p] -= da; y[p] -= dy
            carcassKg[p] += (da + youngShare[k.i] * dy) * area * s.massKg
            // Growing up.
            val up = y[p] * grow
            y[p] -= up; a[p] += up
            // (Of a kind that also roams, grown young on crowded ground are due to leave it as roamers: [leaving].)
            leavingK?.let { it[hw.placeHex[p]] += up * area * leaveShare(a[p] + y[p], usualAt(k.i, p)) }
            bornBook?.add((up * area * (1f - youngShare[k.i]) * s.massKg).toDouble())
            // (Too thin to hold on: gone below the floor here, fading when they spill, [EXTINCT]; with t.thinFadeDays=0 gone below EXTINCT.)
            if (a[p] + y[p] < thinGone) { fadedB[kb] += ((a[p] + y[p]) * area).toDouble(); carcassKg[p] += (a[p] + 0.3f * y[p]) * area * s.massKg; a[p] = 0f; y[p] = 0f }
        }
    } }
    val dead = Array(kinds.size) { k -> DoubleArray(ResidentDeath.entries.size).also { d -> for (b in 0 until blocks) { val src = deadB[k * blocks + b]; for (c in d.indices) d[c] += src[c] } } }
    val faded = DoubleArray(kinds.size) { k -> var s = 0.0; for (b in 0 until blocks) s += fadedB[k * blocks + b]; s }
    for (k in kindIds) for (b in 0 until blocks) { bornThisWeek[k.i] += bornB[k.i * blocks + b]; eggsTakenThisWeek[k.i] += eggsB[k.i * blocks + b] }
    for (c in carcasses) for (p in land.places) carcassKg[p] += c[p]
    land.books?.let { b -> var sum = 0.0; for (p in land.places) sum += carcassKg[p]; b.flow(Books.Stuff.MATTER, "carcasses (to the soil)", -sum) }
    record?.foldResidents()
    for ((k, s) in kinds.withKinds()) {
        // (Summed place by place in order, so that a run is the same on any number of cores.)
        // (Added to the step's, not set: over a step of several ticks the residents may take each tick as a week, [HexSim.step].)
        deaths.merge(Tally.of(s, DeathCause.RESIDENT_HUNTERS), huntedByResidents[k].let { h ->
            var sum = 0.0
            for (p in h.indices) {
                sum += h[p]
                record?.let { it.animalKg[PlaceRecord.Cause.KILLED.ordinal][p] += h[p] * s.massKg }
                h[p] = 0f
            }
            sum
        }, Double::plus)
        for (c in ResidentDeath.entries) deaths.merge(Tally.of(s, c.cause), dead[k][c], Double::plus)
        if (faded[k] > 0.0) deaths.merge(Tally.of(s, FADED_OUT), faded[k], Double::plus)
        deaths.merge(Tally.of(s, DeathCause.PREY_FOUGHT_BACK), foughtBackDead[k].let { h ->
            var sum = 0.0
            for (p in h.indices) { sum += h[p]; record?.let { it.animalKg[PlaceRecord.Cause.KILLED.ordinal][p] += h[p] * s.massKg }; h[p] = 0f }
            sum
        }, Double::plus)
    }
}

/**
 * Books kind [k]'s deaths at place [p] by cause into [dd] (heads) and the place record (kg): [ca] and [cy] the
 * grown and young heads dying per unit of odds ([breedAndDie]), [hz] the week's [hazards], and the young's
 * [eagle], [croc]odile and [starved] odds.
 */
private fun HexFauna.bookDeaths(k: FaunaKind, p: Place, ca: Float, cy: Float, hz: EnumFloats<Danger>, eagle: Float, croc: Float, starved: Float, dd: DoubleArray) {
    val s = kinds[k]
    val hunger = hz[Danger.HUNGER]; val cold = hz[Danger.COLD]; val heat = hz[Danger.HEAT]; val thirst = hz[Danger.THIRST]; val disease = hz.disease()
    val fireA = hz[Danger.FIRE_A]; val floodA = hz[Danger.FLOOD_A]; val snowA = hz[Danger.SNOW_A]
    val fireY = hz[Danger.FIRE_Y]; val floodY = hz[Danger.FLOOD_Y]; val snowY = hz[Danger.SNOW_Y]
    dd[ResidentDeath.AGE] += ca * s.backgroundMortality * years + cy * s.youngMortality * years
    dd[ResidentDeath.HUNGER] += (ca + Hazards.YOUNG_HARDER * cy) * hunger
    dd[ResidentDeath.COLD] += (ca + Hazards.YOUNG_HARDER * cy) * cold
    dd[ResidentDeath.HEAT] += (ca + Hazards.YOUNG_HARDER * cy) * heat
    dd[ResidentDeath.DISEASE] += (ca + cy) * disease
    // (Of the small hunters' share, [youngTakenOdds], the eagles' and the foxes'.)
    val byEagles = eaglesOnYoung(s, p); val byFoxes = eagle - byEagles
    dd[ResidentDeath.EAGLES] += cy * byEagles; dd[ResidentDeath.FOXES] += cy * byFoxes
    dd[ResidentDeath.FIRE] += ca * fireA + cy * fireY
    dd[ResidentDeath.FLOOD] += ca * floodA + cy * floodY
    dd[ResidentDeath.SNOW] += ca * snowA + cy * snowY
    dd[ResidentDeath.CROCODILES] += cy * croc
    dd[ResidentDeath.THIRST] += (ca + Hazards.YOUNG_HARDER * cy) * thirst
    dd[ResidentDeath.SHORT_OF_PROTEIN] += cy * starved
    val strife = hz[Danger.STRIFE]
    dd[ResidentDeath.RIVALS] += ca * strife; dd[ResidentDeath.YOUNG_KILLED_BY_RIVALS] += cy * STRIFE_YOUNG * strife
    record?.let { r ->
        val ka = ca * s.massKg; val ky = cy * s.massKg
        r.resident(k.i, p, PlaceRecord.Cause.HUNGER, (ka + Hazards.YOUNG_HARDER * ky) * hunger)
        r.resident(k.i, p, PlaceRecord.Cause.THIRST, (ka + Hazards.YOUNG_HARDER * ky) * thirst)
        r.resident(k.i, p, PlaceRecord.Cause.COLD, (ka + Hazards.YOUNG_HARDER * ky) * cold + ka * snowA + ky * snowY)
        r.resident(k.i, p, PlaceRecord.Cause.ILLNESS, (ka + ky) * disease)
        r.resident(k.i, p, PlaceRecord.Cause.KILLED, ky * (eagle + croc) + (ka + STRIFE_YOUNG * ky) * strife)
        r.resident(k.i, p, PlaceRecord.Cause.OTHER, ka * s.backgroundMortality * years + ky * (s.youngMortality * years + starved) +
            (ka + Hazards.YOUNG_HARDER * ky) * heat + ka * (fireA + floodA) + ky * (fireY + floodY))
    }
}

/**
 * What kills kind [k]'s animals at place [p] this week besides old age, as shares of them ([Danger.HUNGER] and the rest
 * into [out]): hunger fed [fed], cold and heat, thirst [dry] days without water, disease among [heads] of its kind per
 * km² ([Danger.SICK]; and a raging outbreak, [Danger.OUTBREAK]: together [HZ_DISEASE]), fire, flood and deep snow, for the grown and the young, and the hunters
 * ([huntedOdds]). The one rule residents die by ([breedAndDie], which takes the hunters' kills as they make
 * them) and weigh a place to stay or leave by ([stayOdds], [arriveOdds]).
 */
internal fun HexFauna.hazards(k: FaunaKind, p: Place, weather: Weather, month: Int, fed: Float, dry: Float, heads: Float, out: EnumFloats<Danger>, spentBefore: Float = spent[k][p]) {
    val s = kinds[k]
    // (Hunger, cold and heat, and thirst kill residents by the same rules as packs and bands.)
    val sleeping = asleep(k, p)
    out[Danger.HUNGER] = Hazards.hunger(s, spentBefore, fed, days)
    // (Each tick's cold or heat over the step kills as it would have ([Weather.overTicks]: the weather keeps its ticks), and thirst as the dry days mounted through the step.)
    out[Danger.COLD] = if (sleeping) 0f else weather.overTicks(p) { Hazards.cold(s, feltC(s, p, it), month) }
    out[Danger.HEAT] = if (sleeping) 0f else weather.overTicks(p) { Hazards.heat(s, feltC(s, p, it)) }
    out[Danger.THIRST] = if (dry <= 0f) 0f
        else Hazards.thirstOver(s, dry - min(dry, Hazards.dryDays(s, days, fed, land.plants.green[p])), dry, days, clock.tickDays)
    // Crowding breeds disease and parasites, far more among the underfed: numbers grow until the
    // lean season binds, and then hunger and sickness together hold them.
    // (Crowded by its own kind and by those that share its parasites, counted as so many of its own: [sharersKm2].)
    val others = sharersKm2(k, p); val u = usualAt(k.i, p)
    val crowd = Hazards.crowding(heads + others, u)
    out[Danger.CROWD] = crowd
    // (Over a step of several ticks the crowd thins as they sicken: [Hazards.fallingOver].) And damp ground breeds flukes
    // and foot rot in the kinds that do not bear it ([wetSickness]).
    // (Only past the worst crowding: [Hazards.crowdBackstop].)
    out[Danger.SICK] = (if (clock.ticks == 1) Hazards.crowdBackstop(crowd, fed, years)
        else Hazards.fallingOver(heads, clock.ticks, years / clock.ticks) { n -> Hazards.crowdBackstop(Hazards.crowding(n + others, u), fed, 1f) }) +
        wetSickness(s, p, fed) * years
    out[Danger.OUTBREAK] = outbreakDeaths(s, p)
    // Hunters hold ground: past the numbers their territories hold, they fight their own kind and kill its young.
    out[Danger.STRIFE] = if (s.carnivore) strife(heads, u, if (s in Catalogue.roamingResidents) tuning.roamerStrifeCrowd else STRIFE_CROWD) else 0f
    val (fireA, floodA, snowA) = calamities(s, p, weather, false)
    val (fireY, floodY, snowY) = calamities(s, p, weather, true)
    out[Danger.FIRE_A] = fireA; out[Danger.FLOOD_A] = floodA; out[Danger.SNOW_A] = if (sleeping) 0f else snowA
    out[Danger.FIRE_Y] = fireY; out[Danger.FLOOD_Y] = floodY; out[Danger.SNOW_Y] = if (sleeping) 0f else snowY
    // (And the hunters, resident and roaming, as they hunted there lately; [breedAndDie] takes their kills as they make them.)
    out[Danger.HUNTED] = huntedOdds(k, p, false)
}

/**
 * The share of a resident hunter kind's grown animals among [heads] a km² killed in a week by their own kind fighting
 * over ground ([Danger.STRIFE]; the young [STRIFE_YOUNG] times as many): none up to [crowd] times its usual ([STRIFE_CROWD],
 * for wolves and lions settled with roaming packs [Tuning.roamerStrifeCrowd])
 * numbers ([usualKm2]), the territories there are, and growing with the square of the excess beyond, [STRIFE_DEATHS] a
 * year at twice the territories' numbers. As packs, rivals, the homeless and lions killing their young hold them;
 * as residents nothing else would but the breeding spacing (leopards rose fivefold in ten years: ab-residents, 2026-10-31).
 */
internal fun HexFauna.strife(heads: Float, usualKm2: Float, crowd: Float): Float {
    if (clock.ticks == 1) {
        val over = heads / (crowd * usualKm2) - 1f
        return if (over > 0f) STRIFE_DEATHS * over * over * years else 0f
    }
    // (Over a step of several ticks they fight down to the territories there are, the heads falling tick by tick: [Hazards.fallingOver].)
    return Hazards.fallingOver(heads, clock.ticks, years / clock.ticks) { n -> val over = n / (crowd * usualKm2) - 1f; if (over > 0f) STRIFE_DEATHS * over * over else 0f }
}

/**
 * The heads per km² of the other kinds at place [p] that crowd kind [k] by sharing its parasites ([HexFauna.sharers]),
 * counted as so many of [k]'s own: each other kind's numbers against its usual density, times [k]'s usual. So two kinds
 * of one sort living together each sicken at half the numbers either would alone.
 */
internal fun HexFauna.sharersKm2(k: FaunaKind, p: Place): Float = shared[k.i][p.i]

/** Reckons [HexFauna.shared] from the numbers now ([sharersKm2]). */
internal fun HexFauna.reckonShared() {
    val rate = overTicks(HexFauna.GAME_WEEK); val foodRate = overTicks(HexFauna.USUAL_FOOD_WEEK)
    // (A save from before every hunter held ground by its food has none kept for the small hunters: begin it now.)
    for (k in kinds.indices) if (kinds[k].carnivore && gameMean[k].isEmpty()) gameMean[k] = FloatArray(n) { Float.NaN }
    val usuals = ThreadLocal.withInitial { FloatArray(kinds.size) }
    Par.forEachPlace("residents: shared crowding", n) { p ->
        // (Each against its usual numbers at the place, [usualAt]: on the ground's food for the plant-eaters, and the
        // thicket kinds on the ground they hide in. Reckoned once a kind, as the step began.)
        val u = usuals.get(); for (k in kinds.indices) u[k] = usualAt(k, p)
        // (And the nest robbers here: [HexFauna.hatching].)
        var rob = small.foxes[p.i]; for (k in nestRobberKinds) rob += (adults[k][p] + young[k][p]) * kinds[k].massKg * kinds[k].diet.smallPrey; nestRobbers[p.i] = rob
        for (k in kinds.indices) {
            val row = sharers[k]; var sum = 0f
            for (j in row.indices) { val w = row[j]; if (j == k || w <= 0f) continue
                val h = adults[j][p] + young[j][p]; if (h > 0f) sum += w * h / u[j] }
            shared[k][p.i] = sum * u[k]
            // (And the prey of its size a hunter that holds ground by it has here, lately: [HexFauna.usualAt].)
            val g = gameMean[k]
            if (g.isNotEmpty()) { val now = huntersFood(kinds[k], p); val m = g[p.i]; g[p.i] = if (m.isNaN()) now else m + (now - m) * rate }
            // (And a plant-eater's food here, about a year's mean: [HexFauna.foodScale].)
            val fm = foodMean[k]
            if (fm.isNotEmpty()) { val now = foodFor(kinds[k], p); val m = fm[p.i]; fm[p.i] = if (m.isNaN()) now else m + (now - m) * foodRate }
        }
    }
    if (tuning.usualByFood > 0f) reckonFoodRef()
}

/**
 * Deaths a year of [s] at place [p], fed [fed], of the sickness of damp ground: flukes, lungworm and foot rot, as damp as
 * the ground has been ([HexFauna.damp]), for a kind as far as it does not bear it ([Species.dampTolerance]); far more
 * among the underfed, as crowding's.
 */
internal fun HexFauna.wetSickness(s: Species, p: Place, fed: Float): Float {
    val d = damp[p.i]; if (!(d > 0f)) return 0f
    return HexFauna.WET_SICK * d * (1f - s.dampTolerance).coerceIn(0f, 1f) * (1f + HexFauna.MALNOURISHED * (1f - fed).coerceIn(0f, 1f))
}

/**
 * Brings each place's [HexFauna.damp] a step on: the share of it whose soil is sodden this week ([HexHydrology.saturated]:
 * marsh and bog, a flooded floodplain, a valley floor over a high water table), where the fluke's snails live and hooves
 * soften, followed at [HexFauna.DAMP_WEEK] a week. (Reckoned from the soil's water against what it holds, the winter
 * soil of the hills at field capacity counted as wet ground, and goats, sheep and gazelle sickened on their own hills.)
 */
internal fun HexFauna.stepDamp() {
    val h = land.hydro; val rate = overTicks(HexFauna.DAMP_WEEK)
    for (p in land.places) {
        val now = h.saturated(p)
        val d = damp[p.i]
        damp[p.i] = if (d.isNaN()) now else d + (now - d) * rate
    }
}

/** The young a mother of kind [k] at place [p] is due this step, its [share] of her season, among [heads] of her kind per km², fed [protein] of her need for it ([youngDue]), and as far as her sodium there allows ([saltBreeding]). */
internal fun HexFauna.dueYoung(k: FaunaKind, p: Place, heads: Float, protein: Float, share: Float) =
    dueLaid(k, p, heads, protein, share) * hatching(kinds[k], p)

/** [dueYoung] before the nest robbers' take: the eggs a hen of a kind that lays is due, the young of one that bears them live. */
internal fun HexFauna.dueLaid(k: FaunaKind, p: Place, heads: Float, protein: Float, share: Float) =
    youngDue(kinds[k], heads, usualAt(k.i, p), tuning.breedSpacing, protein * saltBreeding(kinds[k], p), share)

/**
 * The share of [s]'s eggs at [p] that hatch rather than are robbed: 1 for a kind that bears its young live; else its
 * eggs lie [Species.nestDays] in the nest, taken at [NEST_ROB_RATE] a year for each kg/km² of the nest robbers there
 * ([HexFauna.nestRobbers]: each robbing kind by the share of its food it hunts as small prey, the ground's small finds a
 * nest is found among; the small life's foxes all of theirs). (What the hens spent on the robbed eggs is not charged them.)
 */
internal fun HexFauna.hatching(s: Species, p: Place): Float =
    if (s.nestDays <= 0f) 1f else exp(-NEST_ROB_RATE * nestRobbers[p.i] * s.nestDays / Date.DAYS_PER_YEAR)

/**
 * The month kind [s]'s mothers start their year again ([bred]): the middle of the months outside its season as it
 * began ([Species.birthMonth]). (A place whose season has drifted over it lets its mothers bear part of a season again.)
 */
internal fun HexFauna.offSeason(s: Species) = offSeason(s, s.birthMonth)

/**
 * The share of her season a mother of kind [k] at place [p] bears this week of [date] ([dueYoung]): none out of the
 * place's season; else a week's share of the season ([Date.DAYS_PER_MONTH] days a month, so a month of four or five
 * weekly steps counts alike), no more than she has left ([bred]), and in the season's last week all she has left.
 */
private fun HexFauna.seasonShare(k: FaunaKind, p: Place, date: Date) = seasonShare(kinds[k], birthStart[k][p.i].toInt(), max(0f, 1f - bred[k][p]), date, days)

/** The share of [s]'s young at [p] the small hunters take in a step: eagles ([eaglesOnYoung]) and foxes ([foxesOnYoung]). */
internal fun HexFauna.youngTakenOdds(s: Species, p: Place) = eaglesOnYoung(s, p) + foxesOnYoung(s, p)

/** Eagles take the young of the smaller hoofed animals in the open: the share of [s]'s young at [p] they take in a step. */
internal fun HexFauna.eaglesOnYoung(s: Species, p: Place) = if (youngKgOf(s) <= EAGLE_PREY_KG) small.eagleRisk(p) * years else 0f

/**
 * Foxes and the other small carnivores (wildcats, martens, mongooses) find the young of the hoofed animals lying hidden in
 * their first weeks, as light as [FOX_PREY_KG]: the share of [s]'s young at [p] they take in a step, as many foxes as
 * there are ([HexSmallLife.foxRisk]), as far as the mother does not drive them off ([Species.defence]). What they kill
 * lies as carrion, which they and the jackals find first.
 */
internal fun HexFauna.foxesOnYoung(s: Species, p: Place): Float {
    // (By the newborn's own weight, not the mean of the young of all the years they take to grow: a roe's fawn weighs a kilo
    // or two, the yearlings beside it a dozen. Of the young, only the newborn's share is at risk.)
    if (s.massKg * HexFauna.newbornShare(s, youngMonth) > FOX_PREY_KG) return 0f
    return small.foxRisk(p) * (1f - s.defence).coerceAtLeast(0f) * HexFauna.newbornOfYoung(s, youngMonth) * years
}

/**
 * Brings each kind's young's weight ([HexFauna.youngShare]) to [date]'s, booking what they put on (or, as the year's
 * young join the last year's, the mean's fall) into [book] as growth: the young's weight in the stocks matches its flows.
 */
internal fun HexFauna.stepYoungShare(date: Date, book: java.util.concurrent.atomic.DoubleAdder?) {
    youngMonth = date.month
    for ((k, s) in kinds.withKinds()) {
        val now = HexFauna.youngShareIn(s, date.month); val d = now - youngShare[k.i]
        if (d == 0f) continue
        if (book != null) { var heads = 0.0; for (p in land.places) heads += young[k][p] * land.areaKm2[p]; book.add(heads * d * s.massKg) }
        youngShare[k.i] = now
    }
}

/** Crocodiles take the young that come down to warm rivers and lakes to drink: the share at [p] they take in a step. */
internal fun HexFauna.crocodileOdds(p: Place, weather: Weather) = water.crocodileRisk(p, weather) * years

/**
 * The odds, summed before any cap, that one of [s]'s young dies in a step besides the hunters' take: their usual deaths, the [hazards] in [hz]
 * (hunger, cold and thirst harder on them), [eagle]s and [croc]odiles, and, fed [protein] of their need for it, the
 * young short of protein ([starvedOdds]), which grow slowly and die more.
 */
private fun HexFauna.youngSum(s: Species, hz: EnumFloats<Danger>, eagle: Float, croc: Float, starved: Float) =
    youngFixed(s, hz, eagle, croc, starved) + Hazards.YOUNG_HARDER * hz[Danger.HUNGER] + hz[Danger.SICK] + STRIFE_YOUNG * hz[Danger.STRIFE]

/**
 * Of [youngSum], the odds that do not change with the numbers at a place or how well they are fed: their usual
 * deaths, cold and thirst, a raging outbreak, fire, flood and snow, [eagle]s, [croc]odiles and [starved] of protein.
 */
internal fun HexFauna.youngFixed(s: Species, hz: EnumFloats<Danger>, eagle: Float, croc: Float, starved: Float) =
    s.youngMortality * years + Hazards.YOUNG_HARDER * (hz[Danger.COLD] + hz[Danger.HEAT] + hz[Danger.THIRST]) + hz[Danger.OUTBREAK] + eagle +
        hz[Danger.FIRE_Y] + hz[Danger.FLOOD_Y] + hz[Danger.SNOW_Y] + croc + starved

/**
 * Of a grown one's odds of dying in a step ([adultShare] and old age), those that do not change with the numbers at a
 * place or how well they are fed: old age, cold, thirst, a raging outbreak, fire, flood, snow and the hunters.
 */
internal fun HexFauna.grownFixed(s: Species, hz: EnumFloats<Danger>) =
    s.backgroundMortality * years + hz[Danger.COLD] + hz[Danger.HEAT] + hz[Danger.THIRST] + hz[Danger.OUTBREAK] + hz[Danger.FIRE_A] + hz[Danger.FLOOD_A] + hz[Danger.SNOW_A] + hz[Danger.HUNTED]

/** The share of the young that die short of protein in a step, fed [protein] of their need for it. */
internal fun HexFauna.starvedOdds(protein: Float) = tuning.youngProteinDeaths * (1f - protein) * years

/** The share of the grown among [out] ([hazards]) that die this week, besides old age. */
internal fun HexFauna.adultShare(out: EnumFloats<Danger>) =
    out[Danger.HUNGER] + out[Danger.COLD] + out[Danger.HEAT] + out[Danger.THIRST] + out.disease() + out[Danger.FIRE_A] + out[Danger.FLOOD_A] + out[Danger.SNOW_A] + out[Danger.HUNTED] +
        out[Danger.STRIFE]

/**
 * The share of kind [k]'s grown residents at place [p] that would die next week if they stayed ([hazards]): fed as
 * they were this week, their days without water going on where they cannot drink.
 */
internal fun HexFauna.stayOdds(k: FaunaKind, p: Place, weather: Weather, out: EnumFloats<Danger>): Float {
    val s = kinds[k]
    val f = fed[k][p]
    val dryNext = if (drinks(s, p)) 0f else dry[k][p] + Hazards.dryDays(s, days, f, land.plants.green[p])
    hazards(k, p, weather, weather.date.month, f, dryNext, adults[k][p] + young[k][p] + keptKm2(k, p), out)
    return adultShare(out)
}

/** How many blocks of places [breedAndDie] splits each kind's into, for the cores: a fixed number, so a run is the same on any number of cores. */
private const val BREED_BLOCKS = 32

/** The first place of each of [blocks] blocks of about as many places, and [n] after the last; no hex's places are parted. */
private fun HexFauna.breedBounds(blocks: Int): IntArray {
    val b = IntArray(blocks + 1)
    for (i in 1 until blocks) {
        var q = max(b[i - 1], (n.toLong() * i / blocks).toInt())
        while (q in 1 until n && hw.placeHex[q] == hw.placeHex[q - 1]) q++
        b[i] = q
    }
    b[blocks] = n
    return b
}
