package inflation.ecology

import inflation.world.Hex
import inflation.world.Place
import inflation.world.get
import inflation.world.set
import inflation.world.Weather
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

// What kills a group's members in a week: the place and how they fare there, thirst, wounds, sickness, crowding, venom, and the deaths shared among the causes.

/**
 * [band]'s wounds this week: mishaps of the work and last week's hurts heal, the tended faster; some lame, some fester.
 * Returns the deaths from festering, a year's rate. Worked as rates a day in closed form over the step's days, so a month
 * neither heals every hurt at once nor festers all of them for the month.
 */
internal fun HexGroups.woundsWeek(band: Band): Float {
    val care = 0.5f * band.medicine + 0.5f * band.skill(Skill.HERBS)
    dialUse?.amount(DialSite.GATHER_MISHAPS, band.gatherRange, min(1f, band.hurt + MISHAPS * band.gatherRange * years), min(1f, band.hurt + MISHAPS * years))
    // (Rates a day, in closed form over the step's days, the same at any step length (plan section 52.A.5): hurt comes at
    // [MISHAPS] a year of ranging and goes as it heals (a week's share of it, [HEAL_UNTENDED] to [HEAL_TENDED]) or festers,
    // hurt' = in - (heal + fester) hurt; the mended and the festered are those rates over the hurt the step carried.)
    val inflow = MISHAPS * band.gatherRange * years / days
    val heal = (HEAL_UNTENDED + (HEAL_TENDED - HEAL_UNTENDED) * care) / 7f
    val fester = FESTER * (1f - 0.8f * care) / 7f
    val k = heal + fester
    val settle = inflow / k; val fade = exp(-k * days)
    val carried = settle * days + (band.hurt - settle) * (1f - fade) / k
    val festered = fester * carried
    // (The lamed leave the share only as adults die or age out, see [adultsDied] and [growUp]; no flat turnover.)
    band.lame = min(1f, band.lame + heal * carried * LAMED * (1f - 0.5f * care))
    band.hurt = (settle + (band.hurt - settle) * fade).coerceIn(0f, 1f)
    return festered
}

/** Whether a group of [s] can drink at place [p] ([Hazards.canDrink]). */
internal fun GroupWorld.canDrink(s: Species, p: Place) = Hazards.canDrink(land.drink, s, p)

/** [g] drinks where there is water; otherwise days without water mount, eased by the water in green food. Returns the deaths from thirst, a year's rate. */
internal fun GroupWorld.thirstWeek(g: HexGroup): Float {
    val before = g.dry
    g.dry = dryAfter(g, g.place, g.fed)
    return thirstOdds(g, g.place, before, g.dry)
}

/** Days [g] would have gone without water by the end of the week at place [p], fed [fed]: none where it can drink. */
internal fun GroupWorld.dryAfter(g: HexGroup, p: Place, fed: Float): Float =
    if (canDrink(g.species, p)) 0f
    // Plant-eaters get water from green food; hunters from the body water of what they eat, in any season.
    else g.dry + Hazards.dryDays(g.species, days, fed, land.plants.green[p])

/**
 * The share of [g] that dies of thirst at place [p] over the step, its days without water going from [before] to [dry]
 * ([Hazards.thirstOver]: the same at any step length).
 */
internal fun GroupWorld.thirstOdds(g: HexGroup, p: Place, before: Float, dry: Float): Float {
    val s = g.species
    // (People carry water in skins and know every spring: they suffer far less from it than animals.)
    // (Strangers to the country do not know its springs.)
    val knowsWater = g.band?.knows(hw.placeHex[p]) ?: true
    return Hazards.thirstOver(s, before, dry, days, fauna.clock.tickDays) *
        (if (s.human) HUMAN_THIRST * tuning.humanThirst * (if (knowsWater) 1f else STRANGER_THIRST) else 1f)
}

/**
 * The mothers among [g]'s adults: half of them, or in a band, the women in their bearing years: most in
 * their twenties and early thirties, few in their teens, fewer after thirty-five, none past the
 * childbearing years (the elders).
 */
internal fun mothersIn(g: HexGroup): Float {
    val band = g.band
    return if (band != null) {
        val first = max(0, g.adults - band.elders - band.ageStage[0] - band.ageStage[1])
        (FERTILE_BY_SPAN[0] * first + FERTILE_BY_SPAN[1] * band.ageStage[0] + FERTILE_BY_SPAN[2] * band.ageStage[1]) / 2f
    } else (g.adults / 2).toFloat()
}

/**
 * [g]'s deaths of sickness this week besides a raging outbreak's, a year's rate: people's crowd
 * sicknesses; hunters' mange, distemper and rabies from packs of their kind about. Where its kind is
 * crowded, an outbreak may break out (drawn from [r]).
 */
internal fun HexGroups.sicknessWeek(g: HexGroup, weather: Weather, kinNow: HashMap<Long, Int>, r: Random): Float {
    val s = g.species
    val usual = HexFauna.usualDensity(s)
    // (Hunters by their kind's numbers over the ground they range over, as residents by theirs in their place.)
    val packs = if (s.human) 0f else Hazards.crowding(kinKm2(g, kinNow), usual)
    val crowdNow = if (s.human) peopleCrowding(g) else packs
    if (r.nextFloat() < HexFauna.OUTBREAK_START * (if (s.human) PEOPLE_OUTBREAK * tuning.peopleOutbreak else 1f) * crowdNow * crowdNow * years)
        outbreaksStarting += s.index.toLong() * hex.size + hw.placeHex[g.place].i
    return if (s.human) peopleSickness(g) * years * famineIllness(g, g.fed).first
        // Hunters catch mange, distemper and rabies where their kind crowds the ground.
        else Hazards.crowdDisease(packs, g.fed, years)
}

/**
 * [g]'s kind's heads per km² about it: its numbers, its own included, over the ground it ranges over (its hex, and the
 * six round it where its kind's home range reaches beyond one hex, [HexGroups.rangesWide]).
 */
internal fun GroupWorld.kinKm2(g: HexGroup, kinNow: HashMap<Long, Int>): Float {
    val s = g.species
    val h0 = hw.placeHex[g.place]
    var heads = 0; var hexes = 0
    for (k in -1 until (if (rangesWide(s)) 6 else 0)) {
        val q = if (k < 0) h0 else hex.neighbour(h0, k)
        if (!q.none) {
            heads += kinNow[s.index.toLong() * hex.size + q.i] ?: 0
            hexes++
        }
    }
    return heads / (hexes * hex.areaKm2.toFloat())
}

/** One of the week's hazards: its [cause] and the odds it adds that a grown one ([adult]) or a young one ([young]) dies. */
internal class Hazard(val cause: DeathReason, val adult: Float, val young: Float)

/**
 * The week's hazards to [g] that come of the place it is in ([p]) and how it fares there, into [into]: hunger at [fed],
 * the cold past its coat (people's, exposure past what their body heat bears: [Band.warmth], with a fire of [fire] and houses
 * sheltering them as far as [home]), the heat, thirst at [thirst] and its
 * kind's everyday sickness at [sick], an outbreak raging there, fire, flood and deep snow, crocodiles at the water
 * and eagles. The one rule for the week's deaths ([liveWeek]) and for those a group foresees
 * at a place it weighs ([HexGroups.deathsAt]).
 */
internal fun GroupWorld.placeHazards(g: HexGroup, p: Place, weather: Weather, fed: Float, thirst: Float, sick: Float,
    fire: Float, home: Float, shelter: Float = g.band?.shelter ?: 0f, into: HazardSink) {
    val s = g.species; val b = g.band
    // (Fed as it would be with its fat drawn on, as its week's feeding draws on it: a group foresees its fat too.)
    // (Each age bears its share of the band's burden by how food is shared and how long its body holds out: [ageHunger].)
    val (hunger, hungerYoung) = ageHunger(g, fed)
    // People keep warm as their body heat allows, through their clothes, fire, shelter and houses: past what they can
    // shiver up, they die of exposure. Animals feel the cold through their coats.
    val exposure = b?.warmth(airAt(p, weather), fire, home, days, shelter)?.odds ?: 0f
    // (Each tick's cold or heat over a step of several, as it would have killed: [Weather.overTicks].)
    val cold = if (b != null) 0f else weather.overTicks(p) { Hazards.cold(s, it, weather.date.month) }; val heat = weather.overTicks(p) { Hazards.heat(s, it) }
    // An outbreak raging where it is kills its share.
    val plague = fauna.outbreakDeaths(s, p)
    // Eagles take the young of the smaller hoofed animals in the open.
    val eagle = if (s.human) 0f else fauna.eagleOdds(s, p)
    val (fireA, floodA, snowA) = fauna.calamities(s, p, weather, false)
    val (fireY, floodY, snowY) = fauna.calamities(s, p, weather, true)
    val croc = if (s.human) 0f else fauna.crocodileOdds(p, weather)
    into.add(DeathCause.HUNGER, hunger, 1.5f * hungerYoung); into.add(DeathCause.COLD, cold, 1.5f * cold)
    into.add(DeathCause.HEAT, heat, 1.5f * heat)
    into.add(DeathCause.EXPOSURE, exposure, 1.5f * exposure); into.add(DeathCause.THIRST, thirst, 1.5f * thirst)
    into.add(DeathCause.DISEASE, sick, sick); into.add(DeathCause.OUTBREAK, plague, plague)
    into.add(DeathCause.FIRE, fireA, fireY); into.add(DeathCause.FLOOD, floodA, floodY); into.add(DeathCause.SNOW, snowA, snowY)
    into.add(DeathCause.CROCODILES, 0f, croc); into.add(DeathCause.EAGLES, 0f, eagle)
}

/**
 * How hard the band's protein burnt bears on each age of [g] (design "Famine deaths by age", 2026-11-13): a prime grown
 * one's, an elder's and a young one's share of the burden ([inflation.Tuning.elderShare], [inflation.Tuning.youngShare];
 * the prime bear the rest, so heads times shares sum to the band's: the food is only moved) over their reserve against a
 * grown one's ([inflation.Tuning.elderReserve], [inflation.Tuning.youngReserve]). 1 for all where it is no band.
 */
internal class AgeTilts(val prime: Float, val elder: Float, val young: Float)

internal fun GroupWorld.ageTilts(g: HexGroup): AgeTilts {
    val b = g.band ?: return AgeTilts(1f, 1f, 1f)
    val t = tuning
    val elders = b.elders.coerceAtMost(g.adults); val prime = g.adults - elders
    val primeShare = if (prime > 0) max(0f, (g.size - t.youngShare * g.young - t.elderShare * elders) / prime) else 1f
    // (Starvation itself comes the later the more of the starving die of illness first: [inflation.Tuning.starveLater].)
    val later = 1f / t.starveLater.coerceAtLeast(0.1f)
    return AgeTilts(primeShare * later, t.elderShare / t.elderReserve * later, t.youngShare / t.youngReserve * later)
}

/** [g]'s protein burnt at the end of a step fed [fed] (its fat drawn on), from [spent] at its start. */
private fun GroupWorld.spentAtEnd(g: HexGroup, fed: Float, spent: Float) =
    Hazards.spentAfter(g.species, spent, Hazards.onFatOf(g.species, fed, g.fat, days), days)

/**
 * The step's hunger odds of [g]'s grown and young fed [fed] (its fat drawn on), having burnt [spent] days of protein before
 * it: each age's share of the band's burnt protein ([ageTilts]), at the step's start and end, read off the starve ramp
 * ([Hazards.starvedRise]); the grown's the mean of the elders' and the prime's. Only the band's state is carried, so the
 * same at any step length. Where fortune lies unevenly ([Band.unevenness]) the poorer half bears that share more of the
 * burden and the richer half less, so the same mean food costs more lives the more uneven it is. [placeHazards]'s hunger.
 */
internal fun GroupWorld.ageHunger(g: HexGroup, fed: Float, spent: Float = g.spent): Pair<Float, Float> {
    val s = g.species
    val end = spentAtEnd(g, fed, spent)
    val a = ageTilts(g)
    val elders = g.band?.elders?.coerceAtMost(g.adults) ?: 0
    fun at(u: Float): Pair<Float, Float> {
        fun odds(k: Float) = Hazards.starvedRise(s, k * u * spent, k * u * end)
        val prime = odds(a.prime)
        val grown = if (elders == 0 || g.adults == 0) prime else ((g.adults - elders) * prime + elders * odds(a.elder)) / g.adults
        return grown to (if (g.band == null) grown else odds(a.young))
    }
    val uneven = g.band?.unevenness ?: 0f
    if (uneven <= 0f) return at(1f)
    val (pa, py) = at(1f + uneven); val (ra, ry) = at(1f - uneven)
    return 0.5f * (pa + ra) to 0.5f * (py + ry)
}

/**
 * The share of their starve days [g]'s people have burnt over a step fed [fed], its mean: the band's protein burnt runs
 * straight from the step's start to its end at the step's food, so the mean of the two is exact at any step length. 0 for
 * animals.
 */
internal fun GroupWorld.burntShare(g: HexGroup, fed: Float): Float =
    if (!g.species.human) 0f else 0.5f * (g.spent + spentAtEnd(g, fed, g.spent)) / Hazards.starveDays(g.species)

/**
 * How much deadlier illness is to [g]'s grown and young over the step, starving ([inflation.Tuning.famineIllness]): 1 +
 * that times their share of the starve days burnt ([ageTilts]), its mean over the step. Fed [fed] the band's burnt
 * protein runs straight from the step's start to its end, so the mean of the two is exact at any step length. 1 for
 * animals.
 */
internal fun GroupWorld.famineIllness(g: HexGroup, fed: Float): Pair<Float, Float> {
    val f = tuning.famineIllness; val s = g.species
    if (f <= 0f || !s.human) return 1f to 1f
    // (Illness reads the body's state as it is, not moved later as starvation itself is: [inflation.Tuning.starveLater] undone.)
    val burnt = burntShare(g, fed) * (if (g.band != null) tuning.starveLater.coerceAtLeast(0.1f) else 1f)
    if (burnt <= 0f) return 1f to 1f
    val a = ageTilts(g)
    val elders = g.band?.elders?.coerceAtMost(g.adults) ?: 0
    val grown = if (g.adults > 0) ((g.adults - elders) * a.prime + elders * a.elder) / g.adults else a.prime
    return (1f + f * grown * burnt) to (1f + f * a.young * burnt)
}

/**
 * How many times as likely an elder of [g]'s band is to die this week as another grown one, of the grown's odds [adultSum]
 * (all causes, summed): only hunger tells them apart ([ageTilts]), at [fed] with [spent] days of body protein spent before
 * the step; 1 for animals, or with no elders.
 */
internal fun GroupWorld.elderRisk(g: HexGroup, fed: Float, spent: Float, adultSum: Float): Float {
    val b = g.band ?: return 1f
    val elders = b.elders.coerceAtMost(g.adults); if (elders == 0 || elders == g.adults) return 1f
    val s = g.species; val end = spentAtEnd(g, fed, spent); val a = ageTilts(g)
    val prime = Hazards.starvedRise(s, a.prime * spent, a.prime * end)
    val old = Hazards.starvedRise(s, a.elder * spent, a.elder * end)
    if (old <= 0f && prime <= 0f) return 1f
    val mean = ((g.adults - elders) * prime + elders * old) / g.adults
    val others = adultSum - mean
    val primeOdds = others + prime
    return if (primeOdds <= 0f) 1f else (others + old) / primeOdds
}

/**
 * [placeHazards] as [g] foresees them at place [p], [km] off, weighing it: no everyday sickness, and its band's fire, house
 * and shelters as they would be there.
 */
internal fun HexGroups.foreseenHazards(g: HexGroup, p: Place, weather: Weather, fed: Float, thirst: Float, km: Float, into: HazardSink) {
    val b = g.band
    placeHazards(g, p, weather, fed, thirst, 0f, b?.let { fireAt(g, it, p) } ?: 0f, if (b != null) houseAt(g, p) else 0f,
        b?.let { shelterAt(g, it, p, km) } ?: 0f, into)
}

/** Vipers and scorpions, out in the warm months: the odds for people at place [p] this week. */
internal fun GroupWorld.venomOdds(s: Species, p: Place, weather: Weather) = if (s.human && weather.temperature[p] > 18f) VENOM * years else 0f

/** The week's deaths, [adults] and [young], shared among the [hazards] by how much each weighed for them (drawn from [r]); what is left over is "other". */
internal fun GroupWorld.shareDeaths(hazards: List<Hazard>, adults: Int, young: Int, r: Random): ArrayList<Died> {
    val died = ArrayList<Died>()
    for ((n, ofYoung) in listOf(adults to false, young to true)) {
        val of = LinkedHashMap<DeathReason, Int>()
        var left = n; var weight = 0f
        for (h in hazards) weight += if (ofYoung) h.young else h.adult
        for (h in hazards) {
            if (left <= 0 || weight <= 0f) break
            val w = if (ofYoung) h.young else h.adult
            val k = if (w >= weight) left else binomial(left, w / weight, r)
            if (k > 0) of.merge(h.cause, k, Int::plus)
            left -= k; weight -= w
        }
        if (left > 0) of.merge(DeathCause.OTHER, left, Int::plus)
        of.entries.mapTo(died) { Died(it.key, it.value, ofYoung) }
    }
    return died
}

/**
 * What a whole house where [g]'s band is would have done for it this week, against the houses it has, by the week's own rules
 * ([placeHazards], [warmthNeed], [stalkRate]): the cold and exposure deaths it would have averted, and those to beasts of prey
 * ([stalked], this week's) behind its walls (its own houses', or solid ones), and the food keeping warm takes that it
 * would have saved; with what its stores and raids added this week ([keepWorth], [raidWorth]), folded into the band's running
 * worth of a house ([Band.houseLives], [Band.houseFood]) a person a year, over about a year. Changes nothing else.
 */
internal fun HexGroups.houseWorthWeek(g: HexGroup, b: Band, weather: Weather, stalked: Float) {
    val s = g.species; val fire = b.fire; val now = b.homeShelter(); val air = airAt(g.place, weather)
    val had = b.warmth(air, fire, now, days); val whole = b.warmth(air, fire, 1f, days)
    fun lives(d: Float) = g.adults * d + g.young * min(1f, 1.5f * d)
    val exposure = lives(max(0f, had.odds - whole.odds))
    b.houseLivesWeek += exposure
    val walls = if (b.homeWalls > 0f) b.homeWalls else 1f
    val taken = stalked * (1f - stalkGuard(b, walls, b.fencedNow()) / stalkGuard(b, b.walledNow(), b.fencedNow()))
    val beasts = (g.adults + g.young * YOUNG_STALKED / b.childWatch.coerceAtLeast(0.25f)) * max(0f, taken)
    b.houseLivesWeek += beasts
    val warmKg = max(0f, warmthNeed(s, b, g.place, weather, fire, now) - warmthNeed(s, b, g.place, weather, fire, 1f)) * g.dailyNeedKg / g.warmth.coerceAtLeast(1e-3f) * days
    b.houseFoodWeek += warmKg
    // (For the census: the parts, and against what a brush shelter does and no cover at all.)
    val bare = b.warmth(air, fire, 0f, days, 0f)
    val w = b.houseWeek
    w[HouseWorth.PEOPLE_WEEKS] += g.size.toFloat(); w[HouseWorth.ROOFED_WEEKS] += g.size * now
    w[HouseWorth.EXPOSURE] += exposure; w[HouseWorth.STALKED] += beasts; w[HouseWorth.WARM_KG] += warmKg
    w[HouseWorth.SHELTER_EXPOSURE] += lives(max(0f, bare.odds - b.warmth(air, fire, 0f, days, 1f).odds))
    w[HouseWorth.BARE_EXPOSURE] += lives(max(0f, bare.odds - whole.odds))
    w[HouseWorth.WORK_DAYS] += g.adults * days * max(0f, whole.pace - had.pace)
    w[HouseWorth.CRAFT_DAYS] += g.adults * days * max(0f, whole.campPace - had.campPace)
    // (And its food in lives: the hunger deaths it would have averted this week by the week's own rule ([placeHazards]),
    // and the days of body protein it would have spared against hunger later ([Hazards.spentAfter]).)
    val more = b.houseFoodWeek / max(1e-3f, g.dailyNeedKg * days)
    if (more > 0f) {
        fun starve(fed: Float, young: Boolean) = ageHunger(g, fed).let { if (young) it.second else it.first }
        w[HouseWorth.HUNGER] += g.adults * max(0f, starve(g.fed, false) - starve(g.fed + more, false)) +
            g.young * max(0f, min(1f, 1.5f * starve(g.fed, true)) - min(1f, 1.5f * starve(g.fed + more, true)))
        w[HouseWorth.PROTEIN_DAYS] += g.adults * max(0f, Hazards.spentAfter(s, g.spent, g.fed, days) - Hazards.spentAfter(s, g.spent, g.fed + more, days))
    }
    val people = max(1, g.size).toFloat(); val k = 1f / stepsPerYear
    b.houseLives += k * (b.houseLivesWeek / people * stepsPerYear - b.houseLives)
    b.houseFood += k * (b.houseFoodWeek / people * stepsPerYear - b.houseFood)
    b.houseLivesWeek = 0f; b.houseFoodWeek = 0f
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
internal fun HexGroups.peopleCrowding(g: HexGroup, h: Hex = hw.placeHex[g.place]): Float {
    var heads = 0; var near = false
    for (k in -1 until 6) {
        val q = if (k < 0) h else hex.neighbour(h, k)
        if (q.none) continue
        if (q == hw.placeHex[g.place]) near = true
        for (p in hw.placesOf(q)) heads += headsHere[kindSlot[g.species.index]][p]
    }
    // (About a hex it would move to, its own heads are not counted there yet.)
    if (!near) heads += g.size
    return heads / (7f * hex.areaKm2.toFloat()) / PEOPLE_FULL_KM2
}

/** The share of the sick and wounded of [band] its healers do not save (1 for a group with no band). */
internal fun healedShare(band: Band?) = (1f - 0.15f * (band?.medicine ?: 0f)) * (band?.lapse(Skill.HERBS)?.let { 1f + HEALING_REACH * (it - 1f) } ?: 1f)
