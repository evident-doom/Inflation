package inflation.ecology

import inflation.Config
import inflation.world.Weather
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Weeks the census's running mean of a band's spare days reaches back, about a season ([Band.spareMean]). The report's, not the model's. */
internal const val SPARE_WEEKS = 13f
/** A chore that got less than this share of its ask in a week that ran out of days was squeezed ([countSqueeze]). The report's line. */
internal const val SQUEEZED = 0.9f
/** A week whose spare days came to less than this share of its hands' days ran out of days ([countSqueeze]). The report's line. */
internal const val WEEK_FULL = 0.05f

/** warmDiag=true: each week a band loses people to exposure, what its survival pass reckoned with and what came, on stderr (the first [WARM_DIAG_LINES]). */
internal val WARM_DIAG = Config.flag(Config.Key.WARM_DIAG, false)
private const val WARM_DIAG_LINES = 400
private var warmDiagLines = 0

private fun describe(air: Air?, c: Cover?, sisu: Float, odds: Float) = if (air == null || c == null) "none" else
    "air %.1f °C swing %.1f wind %.1f wet %.2f snow %s; clo %.2f fire %.2f shelter %.2f home %.2f house %.1f °C; sisu %.2f; odds %.2g".format(
        air.meanC, air.swingC, air.windMs, air.wet, air.snow, c.clothesClo, c.fire, c.shelter, c.home, c.houseWarmC, sisu, odds)

/**
 * Counts for the census how [b]'s chores fared this week against what each asked of its plan ([time], all its chores done,
 * its leisure not yet taken): the days each asked and did not get, and whether it got less than [SQUEEZED] of its ask in a
 * week that ran out of days, so that the work before it took its days. Also keeps the running mean of its spare days the
 * census groups bands by ([bookSqueeze]), and how its clothes making went ([makeTools]). Counts only: the week is as it was.
 * (Food's ask includes the stores it aims at; drying's and skins' are last week's; herding's is sowing's. An ask is counted
 * up to the week's days.)
 */
internal fun HexGroups.countSqueeze(b: Band, time: BandTime) {
    val total = time.total
    val spare = if (total > 0f) max(0f, time.left) / total else 0f
    b.spareMean = if (b.spareSeen) b.spareMean + (spare - b.spareMean) / SPARE_WEEKS else spare
    b.spareSeen = true
    val full = spare < WEEK_FULL
    for (a in Activity.entries) {
        if (a == Activity.LEISURE || a == Activity.MOVING) continue
        // (An ask beyond the whole week, as materials' year of need can be, is counted as the week: no week could give more,
        // and the tally, tenths of a day in an Int, would overflow.)
        val ask = min(time.asked[a], total)
        if (ask <= 0f) continue
        val missing = max(0f, ask - time.workedOn(a))
        fun add(k: Tally.Squeeze, v: Int) { stats.hunting.merge(Tally.squeeze(a, k), v, Int::plus) }
        add(Tally.Squeeze.ASKED_WEEKS, 1)
        add(Tally.Squeeze.ASKED_DAYS, (10f * ask).roundToInt())
        add(Tally.Squeeze.MISSING_DAYS, (10f * missing).roundToInt())
        add(Tally.Squeeze.FIRST_DAYS, (10f * min(time.askedFirst[a], ask)).roundToInt())
        if (full && missing > (1f - SQUEEZED) * ask) add(Tally.Squeeze.SHORT_WEEKS, 1)
    }
    b.clothesMade?.let { stats.hunting.merge(Tally.clothesMade(it), 1, Int::plus) }
}

/**
 * Books [g]'s band [b]'s week, and its deaths ([causes]), by its spare days over the last season ([Band.spareMean]) and by
 * whether it keeps a herd, with its clothes: whether bands whose weeks are full die of what spare days would have kept off.
 * And by its spare days with how it was kept warm at the week's end ([Tally.Warmed]), the week's air and whether it came to
 * a new camp: whether the full weeks' exposure deaths come with a fire or shelters cut short, or with the cold of where they are.
 */
internal fun HexGroups.bookSqueeze(g: HexGroup, b: Band, causes: List<Died>, weather: Weather) {
    val clo = (10f * b.clothesClo()).roundToInt()
    val spare = Tally.Spare.of(b.spareMean)
    val spareSplit = BandSplit.spare(spare)
    stats.bookWeek(spareSplit, g.size)
    stats.book(spareSplit, Tally.Bands.CLOTHES, clo)
    stats.bookDeaths(spareSplit, causes)
    val warmed = Tally.Warmed.of(b.fire, if (b.shelterWill > 0f) b.shelter / b.shelterWill else 1f)
    val warmedSplit = BandSplit.warmed(spare, warmed)
    stats.bookWeek(warmedSplit, g.size)
    stats.book(warmedSplit, Tally.Bands.CAMP_AIR, (10f * airAt(g.place, weather).meanC).roundToInt())
    if (b.campWeeks == 0) stats.book(warmedSplit, Tally.Bands.MOVED, 1)
    // (What its survival pass reckoned with against what came: the fire, its lighting and the week's exposure odds.)
    fun put(what: Tally.Bands, x: Float) = stats.book(warmedSplit, what, x.roundToInt())
    put(Tally.Bands.PLAN_FIRE, 100f * b.planFire); put(Tally.Bands.FIRE_GOT, 100f * b.fire)
    put(Tally.Bands.LIT, 100f * if (b.hearthWood > 0f) min(1f, b.fire / b.hearthWood) else 1f)
    put(Tally.Bands.PLAN_ODDS, 1e4f * b.planOdds)
    put(Tally.Bands.ODDS, 1e4f * b.warmth(airAt(g.place, weather), b.fire, b.homeShelter(), days).odds)
    put(Tally.Bands.LEFT, 100f * b.planLeft)
    if (b.planShelterFirst >= 0f) { put(Tally.Bands.SHELTER_ASKED, 1f); put(Tally.Bands.SHELTER_FIRST, 100f * b.planShelterFirst) }
    if (WARM_DIAG && causes.any { it.cause == DeathCause.EXPOSURE && it.n > 0 } && warmDiagLines < WARM_DIAG_LINES) {
        warmDiagLines++
        val air = airAt(g.place, weather)
        System.err.println("warmDiag ${weather.date} band ${g.id} spare ${spare.label} ${warmed.label} new ${b.campWeeks == 0} died " +
            causes.filter { it.cause == DeathCause.EXPOSURE }.sumOf { it.n } + " of ${g.size}; " +
            "planned ${describe(b.planAir, b.planCover, b.planSisu, b.planOdds)}; got " +
            describe(air, b.cover(air.meanC, b.fire, b.homeShelter()), b.sisu, b.warmth(air, b.fire, b.homeShelter(), days).odds) +
            "; will %.2f, lit %.2f, hearth wood %.2f".format(b.shelterWill, if (b.hearthWood > 0f) b.fire / b.hearthWood else 1f, b.hearthWood) +
            "; shelter asked first %.2f of its ask; food held %.1f spent %.1f person-days; shelter held %.2f left it %.2f".format(
                b.planShelterFirst, b.diagFoodHeld, b.diagFoodSpent, b.diagShelterHeld, b.diagShelterLeft))
    }
    stats.bookDeaths(warmedSplit, causes)
    // (And by how far its shelters stood: whether bands thatch, and what the cold costs them in food and lives.)
    val hut = Tally.Hut.of(b.shelter)
    val hutSplit = BandSplit.hut(hut)
    stats.bookWeek(hutSplit, g.size)
    stats.book(hutSplit, Tally.Bands.CAMP_AIR, (10f * airAt(g.place, weather).meanC).roundToInt())
    stats.book(hutSplit, Tally.Bands.FED, (1000f * min(1f, g.fed) * g.size).roundToInt())
    stats.book(hutSplit, Tally.Bands.WARM_EXTRA, (1000f * b.warmth(airAt(g.place, weather), b.fire, b.homeShelter(), days).extra).roundToInt())
    stats.book(hutSplit, Tally.Bands.AIM, (100f * b.shelterAim).roundToInt())
    if (b.campWeeks == 0) stats.book(hutSplit, Tally.Bands.MOVED, 1)
    stats.bookDeaths(hutSplit, causes)
    val h = if (b.keepsAnimals()) Tally.HerdKept.HERDING else Tally.HerdKept.NONE
    val keptSplit = BandSplit.herdKept(h)
    stats.bookWeek(keptSplit, g.size)
    stats.book(keptSplit, Tally.Bands.CLOTHES, clo)
    stats.book(keptSplit, Tally.Bands.SPARE, (1000f * b.spareMean).roundToInt())
    stats.bookDeaths(keptSplit, causes)
}

/** Books what a whole house would have done for [g]'s band [b] this week, by the week's air ([HouseWorth]), and clears the band's week of it. */
internal fun HexGroups.bookHouseWorth(g: HexGroup, b: Band, weather: Weather) {
    val worth = bands.houseWorth[HouseAir.of(airAt(g.place, weather).meanC).ordinal]
    for (x in HouseWorth.entries) worth[x] += b.houseWeek[x]
    b.houseWeek.fill(0f)
}
