package inflation.ecology

import inflation.world.Hex
import inflation.world.Place
import inflation.world.get
import inflation.world.set
import inflation.world.Weather
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

// What kills a group's members in a week: the place and how they fare there, thirst, wounds, sickness, crowding, venom, and the deaths shared among the causes.

/**
 * [band]'s wounds this week: mishaps of the work and last week's hurts heal, the tended faster; some lame, some fester.
 * Returns the deaths from festering, a year's rate.
 */
internal fun HexGroups.woundsWeek(band: Band): Float {
    val care = 0.5f * band.medicine + 0.5f * band.skill(Skill.HERBS)
    dialUse?.amount(DialSite.GATHER_MISHAPS, band.gatherRange, min(1f, band.hurt + MISHAPS * band.gatherRange * years), min(1f, band.hurt + MISHAPS * years))
    band.hurt = min(1f, band.hurt + MISHAPS * band.gatherRange * years)
    val heal = min(1f, (HEAL_UNTENDED + (HEAL_TENDED - HEAL_UNTENDED) * care) * days / 7f)
    val fester = FESTER * (1f - 0.8f * care) * band.hurt * days / 7f
    val mended = band.hurt * heal
    // (The lamed leave the share only as adults die or age out, see [adultsDied] and [growUp]; no flat turnover.)
    band.lame = min(1f, band.lame + mended * LAMED * (1f - 0.5f * care))
    band.hurt = max(0f, band.hurt - mended - fester)
    return fester
}

/** Whether a group of [s] can drink at place [p] ([Hazards.canDrink]). */
internal fun GroupWorld.canDrink(s: Species, p: Place) = Hazards.canDrink(land.drink, s, p)

/** [g] drinks where there is water; otherwise days without water mount, eased by the water in green food. Returns the deaths from thirst, a year's rate. */
internal fun GroupWorld.thirstWeek(g: HexGroup): Float {
    g.dry = dryAfter(g, g.place, g.fed)
    return thirstOdds(g, g.place, g.dry)
}

/** Days [g] would have gone without water by the end of the week at place [p], fed [fed]: none where it can drink. */
internal fun GroupWorld.dryAfter(g: HexGroup, p: Place, fed: Float): Float =
    if (canDrink(g.species, p)) 0f
    // Plant-eaters get water from green food; hunters from the body water of what they eat, in any season.
    else g.dry + Hazards.dryDays(g.species, days, fed, land.plants.green[p])

/** The share of [g] that dies of thirst at place [p], [dry] days without water. */
internal fun GroupWorld.thirstOdds(g: HexGroup, p: Place, dry: Float): Float {
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
    return if (s.human) peopleSickness(g) * years * (1f + HexFauna.MALNOURISHED * (1f - g.fed))
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
    val s = g.species; val b = g.band; val t = weather.temperature[p]
    // (Fed as it would be with its fat drawn on, as its week's feeding draws on it: a group foresees its fat too.)
    // (In a band the shortfall is not shared evenly: parents feed their children first, see [CHILDREN_FIRST].)
    // (And the elders yield theirs to the rest, see [ELDERS_YIELD]: the grown's odds are the mean of the elders' and the others'.)
    val short = shortfallOfAdults(g, fed)
    val hunger = grownHunger(g, short)
    val hungerYoung = youngHunger(g, short, hunger)
    // People keep warm as their body heat allows, through their clothes, fire, shelter and houses: past what they can
    // shiver up, they die of exposure. Animals feel the cold through their coats.
    val exposure = b?.warmth(airAt(p, weather), fire, home, days, shelter)?.odds ?: 0f
    val cold = if (b != null) 0f else Hazards.cold(s, t, weather.date.month); val heat = Hazards.heat(s, t)
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
 * How much of its need a grown member of [g] in the childbearing years goes without when the group as a whole is fed [fed]:
 * in a band, the shortfall spares a child [CHILDREN_FIRST] of its share and falls [ELDERS_YIELD] times as hard on an elder,
 * so the band's food is shared out whole (each one's shortfall times their number, summed, is the band's), as far as no one
 * goes without more than all of it.
 */
internal fun shortfallOfAdults(g: HexGroup, fed: Float): Float {
    val shortfall = 1f - fed
    val b = g.band
    if (b == null || shortfall <= 0f || (g.young == 0 && b.elders == 0)) return shortfall
    val elders = b.elders.coerceAtMost(g.adults)
    return min(1f, shortfall * g.size / (g.adults - elders + ELDERS_YIELD * elders + g.young * (1f - CHILDREN_FIRST)))
}

/**
 * The week's hunger odds of [g]'s grown when one in the childbearing years goes without [short] of its need
 * ([shortfallOfAdults]): the mean of the elders' ([eldersHunger]) and the others'. [placeHazards]'s hunger.
 */
internal fun GroupWorld.grownHunger(g: HexGroup, short: Float): Float {
    val s = g.species
    val hungerPrime = Hazards.hunger(s, g.spent, Hazards.onFatOf(s, 1f - short, g.fat, days), days)
    val elders = g.band?.elders?.coerceAtMost(g.adults) ?: 0
    return if (elders == 0) hungerPrime else ((g.adults - elders) * hungerPrime + elders * eldersHunger(g, short)) / g.adults
}

/** The same for [g]'s young, fed first in a band ([CHILDREN_FIRST]); [grown]'s odds ([grownHunger]) where it is no band. */
internal fun GroupWorld.youngHunger(g: HexGroup, short: Float, grown: Float) =
    if (g.band == null) grown else Hazards.hunger(g.species, g.spent, Hazards.onFatOf(g.species, 1f - short * (1f - CHILDREN_FIRST), g.fat, days), days)

/** The week's hunger odds of an elder of [g]'s band when a grown one in the childbearing years goes without [short] of its need ([shortfallOfAdults]). */
private fun GroupWorld.eldersHunger(g: HexGroup, short: Float, spent: Float = g.spent) =
    Hazards.hunger(g.species, spent, Hazards.onFatOf(g.species, 1f - min(1f, ELDERS_YIELD * short), g.fat, days), days)

/**
 * How many times as likely an elder of [g]'s band is to die this week as another grown one, of the grown's odds [adultOdds]
 * (all causes, summed): only hunger tells them apart ([ELDERS_YIELD]), at [fed] with [spent] days of body protein spent; 1 for
 * animals, or with no elders.
 */
internal fun GroupWorld.elderRisk(g: HexGroup, fed: Float, spent: Float, adultSum: Float): Float {
    val b = g.band ?: return 1f
    val elders = b.elders.coerceAtMost(g.adults); if (elders == 0 || elders == g.adults) return 1f
    val short = shortfallOfAdults(g, fed); if (short <= 0f) return 1f
    val prime = Hazards.hunger(g.species, spent, Hazards.onFatOf(g.species, 1f - short, g.fat, days), days)
    val old = eldersHunger(g, short, spent)
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
        fun starve(fed: Float, young: Boolean): Float {
            val short = shortfallOfAdults(g, fed) * (if (young) 1f - CHILDREN_FIRST else 1f)
            return Hazards.hunger(s, g.spent, Hazards.onFatOf(s, 1f - short, g.fat, days), days)
        }
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
