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
    val dead = Array(kinds.size) { DoubleArray(ResidentDeath.entries.size) }
    val faded = DoubleArray(kinds.size)
    bornThisWeek.fill(0.0)
    // Each kind on its own core, with its own carcasses, added up after.
    val carcasses = Array(kinds.size) { FloatArray(n) }
    val bornBook = land.books?.adder(Books.Stuff.MATTER, "residents born and grew up")
    Par.forEach("residents: births, deaths", kindIds.toList()) { k -> Profile.kind("residents: births, deaths", kinds[k].name) {
        val s = kinds[k]
        val carcassKg = carcasses[k]
        val grow = years / s.yearsToMaturity
        val thinGone = if (tuning.thinFadeDays > 0f) HexFauna.EXTINCT_FLOOR else EXTINCT
        val a = adults[k]; val y = young[k]; val f = fed[k]; val dk = dry[k]; val leavingK = leaving[k]
        val hz = enumFloats<Danger>()
        for (p in land.places) {
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
                val due = dueYoung(k, p, crowdHeads, protein[k][p], share)
                val born = 0.5f * a[p] * due * breeding(s, due, reserve[k][p], f[p], days)
                y[p] += born
                bornHere = born
                bornThisWeek[k] += (born * area).toDouble()
                bornBook?.add((born * area * YOUNG_WEIGHT * s.massKg).toDouble())
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
            // Where they are crowded, now and then a plague breaks out among them.
            if (crowd > 0f && hash(p, k) < OUTBREAK_START * crowd * crowd * years) startOutbreak(s, hw.placeHex[p])
            val fireA = hz[Danger.FIRE_A]; val floodA = hz[Danger.FLOOD_A]; val snowA = hz[Danger.SNOW_A]
            val adultSum = s.backgroundMortality * years + hunger + cold + heat + thirst + disease + fireA + floodA + snowA + hz[Danger.STRIFE]
            val adultOdds = adultSum.coerceIn(0f, Hazards.MAX_GROWN_ODDS)
            val eagle = eagleOdds(s, p); val croc = crocodileOdds(p, weather)
            val starved = starvedOdds(protein[k][p])
            val youngSum = youngSum(s, hz, eagle, croc, starved)
            val youngOdds = youngSum.coerceIn(0f, Hazards.MAX_YOUNG_ODDS)
            // (The young born this step face its deaths only from their birth on: [StepClock.newbornExposure], 1 at one tick a step.)
            val yExposed = y[p] - bornHere * (1f - clock.newbornExposure)
            val da = a[p] * adultOdds; val dy = yExposed * youngOdds
            // (The causes are booked as shares of the deaths that happen: where the odds are capped, each cause is cut alike.)
            val ca = if (adultSum > 0f) a[p] * adultOdds / adultSum * area else 0f
            val cy = if (youngSum > 0f) yExposed * youngOdds / youngSum * area else 0f
            bookDeaths(k, p, ca, cy, hz, eagle, croc, starved, dead[k])
            sickBook[3 * k.i].add(((ca + cy) * hz[Danger.OUTBREAK]).toDouble())
            sickBook[3 * k.i + 1].add(((a[p] + y[p]) * area).toDouble()); sickBook[3 * k.i + 2].add(((a[p] + y[p]) * area * crowd * crowd).toDouble())
            a[p] -= da; y[p] -= dy
            carcassKg[p] += (da + YOUNG_WEIGHT * dy) * area * s.massKg
            // Growing up.
            val up = y[p] * grow
            y[p] -= up; a[p] += up
            // (Of a kind that also roams, grown young on crowded ground are due to leave it as roamers: [leaving].)
            leavingK?.let { it[hw.placeHex[p]] += up * area * leaveShare(a[p] + y[p], usual[k]) }
            bornBook?.add((up * area * (1f - YOUNG_WEIGHT) * s.massKg).toDouble())
            // (Too thin to hold on: gone below the floor here, fading when they spill, [EXTINCT]; with t.thinFadeDays=0 gone below EXTINCT.)
            if (a[p] + y[p] < thinGone) { faded[k] += ((a[p] + y[p]) * area).toDouble(); carcassKg[p] += (a[p] + 0.3f * y[p]) * area * s.massKg; a[p] = 0f; y[p] = 0f }
        }
    } }
    for (c in carcasses) for (p in land.places) carcassKg[p] += c[p]
    land.books?.let { b -> var sum = 0.0; for (p in land.places) sum += carcassKg[p]; b.flow(Books.Stuff.MATTER, "carcasses (to the soil)", -sum) }
    record?.foldResidents()
    for ((k, s) in kinds.withKinds()) {
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
        if (faded[k] > 0.0) deaths.merge(Tally.of(s, FADED_OUT), faded[k], Double::plus)
        deaths[Tally.of(s, DeathCause.PREY_FOUGHT_BACK)] = foughtBackDead[k].let { h ->
            var sum = 0.0
            for (p in h.indices) { sum += h[p]; record?.let { it.animalKg[PlaceRecord.Cause.KILLED.ordinal][p] += h[p] * s.massKg }; h[p] = 0f }
            sum
        }
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
    dd[ResidentDeath.HUNGER] += (ca + 1.5f * cy) * hunger
    dd[ResidentDeath.COLD] += (ca + 1.5f * cy) * cold
    dd[ResidentDeath.HEAT] += (ca + 1.5f * cy) * heat
    dd[ResidentDeath.DISEASE] += (ca + cy) * disease
    dd[ResidentDeath.EAGLES] += cy * eagle
    dd[ResidentDeath.FIRE] += ca * fireA + cy * fireY
    dd[ResidentDeath.FLOOD] += ca * floodA + cy * floodY
    dd[ResidentDeath.SNOW] += ca * snowA + cy * snowY
    dd[ResidentDeath.CROCODILES] += cy * croc
    dd[ResidentDeath.THIRST] += (ca + 1.5f * cy) * thirst
    dd[ResidentDeath.SHORT_OF_PROTEIN] += cy * starved
    val strife = hz[Danger.STRIFE]
    dd[ResidentDeath.RIVALS] += ca * strife; dd[ResidentDeath.YOUNG_KILLED_BY_RIVALS] += cy * STRIFE_YOUNG * strife
    record?.let { r ->
        val ka = ca * s.massKg; val ky = cy * s.massKg
        r.resident(k.i, p, PlaceRecord.Cause.HUNGER, (ka + 1.5f * ky) * hunger)
        r.resident(k.i, p, PlaceRecord.Cause.THIRST, (ka + 1.5f * ky) * thirst)
        r.resident(k.i, p, PlaceRecord.Cause.COLD, (ka + 1.5f * ky) * cold + ka * snowA + ky * snowY)
        r.resident(k.i, p, PlaceRecord.Cause.ILLNESS, (ka + ky) * disease)
        r.resident(k.i, p, PlaceRecord.Cause.KILLED, ky * (eagle + croc) + (ka + STRIFE_YOUNG * ky) * strife)
        r.resident(k.i, p, PlaceRecord.Cause.OTHER, ka * s.backgroundMortality * years + ky * (s.youngMortality * years + starved) +
            (ka + 1.5f * ky) * heat + ka * (fireA + floodA) + ky * (fireY + floodY))
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
    val crowd = Hazards.crowding(heads, usual[k])
    out[Danger.CROWD] = crowd
    // (Over a step of several ticks the crowd thins as they sicken: [Hazards.fallingOver].)
    out[Danger.SICK] = if (clock.ticks == 1) Hazards.crowdDisease(crowd, fed, years)
        else Hazards.fallingOver(heads, clock.ticks, years / clock.ticks) { n -> Hazards.crowdDisease(Hazards.crowding(n, usual[k]), fed, 1f) }
    out[Danger.OUTBREAK] = outbreakDeaths(s, p)
    // Hunters hold ground: past the numbers their territories hold, they fight their own kind and kill its young.
    out[Danger.STRIFE] = if (s.carnivore) strife(heads, usual[k], if (s in Catalogue.roamingResidents) tuning.roamerStrifeCrowd else STRIFE_CROWD) else 0f
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

/** The young a mother of kind [k] at place [p] is due this step, its [share] of her season, among [heads] of her kind per km², fed [protein] of her need for it ([youngDue]), and as far as her sodium there allows ([saltBreeding]). */
internal fun HexFauna.dueYoung(k: FaunaKind, p: Place, heads: Float, protein: Float, share: Float) =
    youngDue(kinds[k], heads, usual[k], tuning.breedSpacing, protein * saltBreeding(kinds[k], p), share)

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

/** Eagles take the young of the smaller hoofed animals in the open: the share of [s]'s young at [p] they take in a step. */
internal fun HexFauna.eagleOdds(s: Species, p: Place) = if (s.massKg <= EAGLE_PREY_KG) small.eagleRisk(p) * years else 0f

/** Crocodiles take the young that come down to warm rivers and lakes to drink: the share at [p] they take in a step. */
internal fun HexFauna.crocodileOdds(p: Place, weather: Weather) = water.crocodileRisk(p, weather) * years

/**
 * The odds, summed before any cap, that one of [s]'s young dies in a step besides the hunters' take: their usual deaths, the [hazards] in [hz]
 * (hunger, cold and thirst harder on them), [eagle]s and [croc]odiles, and, fed [protein] of their need for it, the
 * young short of protein ([starvedOdds]), which grow slowly and die more.
 */
private fun HexFauna.youngSum(s: Species, hz: EnumFloats<Danger>, eagle: Float, croc: Float, starved: Float) =
    youngFixed(s, hz, eagle, croc, starved) + 1.5f * hz[Danger.HUNGER] + hz[Danger.SICK] + STRIFE_YOUNG * hz[Danger.STRIFE]

/**
 * Of [youngSum], the odds that do not change with the numbers at a place or how well they are fed: their usual
 * deaths, cold and thirst, a raging outbreak, fire, flood and snow, [eagle]s, [croc]odiles and [starved] of protein.
 */
internal fun HexFauna.youngFixed(s: Species, hz: EnumFloats<Danger>, eagle: Float, croc: Float, starved: Float) =
    s.youngMortality * years + 1.5f * (hz[Danger.COLD] + hz[Danger.HEAT] + hz[Danger.THIRST]) + hz[Danger.OUTBREAK] + eagle +
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
