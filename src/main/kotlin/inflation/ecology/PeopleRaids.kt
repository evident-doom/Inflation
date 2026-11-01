package inflation.ecology

import inflation.Part
import inflation.Kg
import inflation.ecology.HexSmallLife.DeadPool
import inflation.world.Hex
import inflation.world.get
import inflation.world.set
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.min
import inflation.world.Weather
import kotlin.math.exp
import kotlin.math.roundToInt

// Bands' raids and their defence: palisades, plotting and fighting raids.

/** A palisade round the houses at [g]'s band [b]'s camp at hex [h], at home and raided in the last year, as far as the [time] days and its timber go. */
@Part("people: build palisade")
internal fun HexGroups.buildPalisade(g: HexGroup, b: Band, h: Hex, people: Float, time: BandTime) {
    val gd = b.goods
    val left = time.leftFor(Activity.PALISADE)
    if (b.atHome && b.palisade < 1f && left > 0f && people > 0f)
        dialUse?.choice(DialSite.PALISADES, b.fortifyWill, b.sinceRaided < stepsPerYear * b.fortifyWill, b.sinceRaided < stepsPerYear)
    if (fortifies(b) && left > 0f && people > 0f) {
        val s = buildingAt(g, Built.PALISADE, h, -1, people)
        val built = minOf(1f - s.built, left / (PALISADE_DAYS * people), gd[Material.WOOD] / (PALISADE_WOOD * people)).coerceAtLeast(0f)
        gd[Material.WOOD] -= built * PALISADE_WOOD * people; buildOn(s, built); time.spend(Activity.PALISADE, built * PALISADE_DAYS * people)
        if (built > 0f) stats.meeting(Met.BANDS_BUILT_PALISADES)
    }
}

/** Whether [b] rings its houses with a palisade: at home, raided lately (by its will), with the ring not whole. */
internal fun GroupWorld.fortifies(b: Band) = b.atHome && b.sinceRaided < stepsPerYear * b.fortifyWill && b.palisade < 1f

/**
 * A band driven by hunger or a feud, knowing where a band it is not close to has its home within
 * reach, sets out to fall on it there: settled bands can be found. Once there, if the others are at
 * home, it attacks (see [fight], fought in [fightRaids] once every band has had its week); otherwise
 * it gives up.
 */
@Part("people: plotRaid")
internal fun HexGroups.plotRaid(g: HexGroup, b: Band, weather: Weather) {
    val here = hw.placeHex[g.place]
    b.raid?.let { raid ->
        if (here == raid.hex) {
            val target = bands.groupById[raid.on]?.takeIf { it.alive && hw.placeHex[it.place] == here }
            if (target?.band != null) bands.raidsDue += g to target
            b.raid = null
        } else b.raid = if (raid.weeks > 1) Raid(raid.hex, raid.on, raid.weeks - 1) else null
        return
    }
    // (A readier band plots on less hunger: each step of readiness closes the same share of what is left of the gap to fed.)
    val hungry = g.fed < shareOfWill(b.raidWill, RAID_HUNGER)
    dialUse?.choice(DialSite.RAIDS_HUNGER, b.raidWill, hungry, g.fed < RAID_HUNGER)
    // (A band neither hungry nor holding a grudge to a feud looks at no home: nothing to plot.)
    if (!hungry && b.grudge.none { it > FEUD }) return
    val within = ArrayList<Int>()
    for ((id, h) in b.knownHomes) {
        val feud = (b.knownLineage[id] ?: -1).let { it >= 0 && b.grudge[it] > FEUD }
        if (!hungry && !feud) continue
        if (hexKm(here, h) <= RAID_RANGE_KM) within += id
    }
    if (within.isEmpty()) return
    // Whether it sets out turns on how it regards the bands it could fall on ([Regard.fightChance]): the warier of one of them,
    // the likelier; never on a band it is close to. (No regard is warier than [Regard.MOST_WARY], so a draw above that chance
    // needs no look at them.)
    val drive = RAID_PLOT * b.raidWill * days / 7f
    val u = rnd.nextFloat()
    if (dialUse == null && u >= drive * Regard.MOST_WARY) return
    val them = ArrayList<Pair<HexGroup, Regard>>()
    for (id in within) {
        val o = bands.groupById[id]?.takeIf { it.alive } ?: continue
        val r = Regard(b, id, o.band ?: continue, map = hex)
        if (r.tie <= 0f) them += o to r
    }
    if (them.isEmpty()) return
    val wariest = them.maxOf { it.second.wariness }
    if (drawn(DialSite.RAIDS_PLOT, b.raidWill, u, drive * wariest) { RAID_PLOT * days / 7f * wariest }) {
        // Above all it keeps alive: it falls on the home where it would lose fewest of its own (the defenders as it
        // knows them, at home behind their works; see [raidStrengths]); of homes about as safe (within a step of its
        // score, see [survivalSteps]), the one it is warier of, then the nearest.
        val dying = HashMap<Int, Float>(); val wary = HashMap<Int, Float>()
        for ((o, r) in them) {
            val ob = o.band ?: continue
            val (sg, so) = raidStrengths(g, o, ob, planned = true, atHome = true, weather)
            dying[o.id] = RAID_DEATHS * so / (sg + so); wary[o.id] = r.wariness
        }
        if (dying.isEmpty()) return
        val least = dying.values.min()
        val best = dying.keys.filter { survivalSteps(g, dying.getValue(it) - least) <= 1f }
            .minWith(compareByDescending<Int> { wary.getValue(it) }.thenBy { hexKm(here, b.knownHomes.getValue(it)) }.thenBy { it })
        val at = b.knownHomes.getValue(best)
        b.raid = Raid(at, best, RAID_WEEKS)
        stats.meeting(Met.RAIDS_SET_OUT)
        note(g) { "set out to raid band #%d at hex %d".format(best, at.i) }
    }
}

/** The raids set for this week ([plotRaid]), in the bands' order, each if both bands are still alive and the target still there. */
internal fun HexGroups.fightRaids(weather: Weather) {
    for ((g, target) in bands.raidsDue) {
        val b = g.band ?: continue; val tb = target.band ?: continue
        if (g.alive && target.alive && hw.placeHex[target.place] == hw.placeHex[g.place]) {
            val u = Language.understanding(b.words, tb.words)
            tallyMeeting(MetAs(Regard(b, target.id, tb, u, hex), Regard(tb, g.id, b, u, hex)), Tally.MeetingEnd.RAIDED_HOME)
            fight(g, b, target, tb, planned = true, weather)
        }
    }
    bands.raidsDue.clear()
}

/**
 * Raids: hunger under which a band will go raiding; how far, km; weekly chance a driven band sets
 * out; weeks it keeps at it. Palisades: person-days and timber (kg) for one person's share.
 * Fighting: surprise and the lie of the land.
 */
internal const val RAID_HUNGER = 0.6f

/**
 * A way's [will] as a share of a whole: [atOne] at the scaffold's 1, and each further unit of will taking the same share of
 * what is left, so more of the same buys ever less and the whole is never reached (no cap: the band finds its own balance).
 */
internal fun shareOfWill(will: Float, atOne: Float) = 1f - (1f - atOne).pow(max(0f, will))

/** A way's [will] as an effect that each further unit adds less to: 1 at the scaffold's 1, about 1.37 at 2, never above 1.58. */
internal fun moreOfWill(will: Float) = (1f - exp(-max(0f, will))) / (1f - exp(-1f))
internal const val RAID_RANGE_KM = 60.0
internal const val RAID_PLOT = 0.3f
/** The share of a raid's take ([RAID_TAKE]) that a mobile band slipping away loses of its larder. An estimate; no figure found. */
internal const val SLIP_LOSS = 0.3f
/** How much of the raided's grudge and loss of ties ([GRUDGE], [TIE_RAID]) the raiders hold against the defenders when they lost people in the raid. An estimate; no figure found. */
internal const val RAIDER_RESENT = 0.3f
internal const val RAID_WEEKS = 4
internal const val PALISADE_DAYS = 6f
internal const val PALISADE_WOOD = 80f
internal const val PALISADE_DEFENCE = 1f
internal const val SURPRISE_RAID = 1f
internal const val HIGH_GROUND = 0.5f

/** [g] raids [o] (as it regards it: [r]) if it is hungry and [o] has food, or holds a feud against its people. Returns whether it did. */
internal fun HexGroups.raid(g: HexGroup, b: Band, o: HexGroup, ob: Band, r: Regard, weather: Weather): Boolean {
    // What drives a raid: an old feud, or competition (hunger with food in the other's larder, or too
    // many people on the same country); kin and those who understand each other fight less.
    val feud = r.feud
    val hunger = if (o.larder > 10f * o.dailyNeedKg) (1f - g.fed) else 0f
    // (On ground it holds as its own, a band drives off those it is not close to: trespass.)
    val trespass = if (b.claims(hw.placeHex[g.place], g.memory) && r.tie <= 0f) CLAIM_PRESSURE else 0f
    val pressure = if (feud) 1f else maxOf(hunger, min(1f, peopleCrowding(g)), trespass)
    // (Whether it comes to blows is the band's choice: its readiness to raid; and how it regards the others.)
    val chance = r.fightChance(RAID * b.raidWill * pressure)
    if (!drawn(DialSite.RAIDS_MEETING, b.raidWill, rnd.nextFloat(), chance) { chance / b.raidWill.coerceAtLeast(1e-6f) }) return false
    fight(g, b, o, ob, planned = false, weather)
    return true
}

/**
 * [g] falls on [o]. A planned raid on a band at home may take it by surprise, the less so the more
 * it sees (scouts, open ground, moonlight); the defenders gain from high, broken ground, their
 * houses and a palisade. A band with no home to hold may slip away, losing what it cannot carry; one beaten at its home
 * may be driven from it ([driveOff]).
 */
internal fun HexGroups.fight(g: HexGroup, b: Band, o: HexGroup, ob: Band, planned: Boolean, weather: Weather) {
    stats.meeting(if (planned) Met.RAIDS_ON_HOMES else Met.RAIDS)
    note(g) { "raided band #%d%s".format(o.id, if (planned) " at its home" else "") }; note(o) { "was raided by band #%d".format(g.id) }
    ob.sinceRaided = 0
    // The mobile slip away (and lose some of their stores).
    if (!ob.atHome && rnd.nextFloat() < FLEE) {
        val t = SLIP_LOSS * RAID_TAKE * o.larder; moveFood(o, g, t, LarderMove.RAIDED)
        stats.meeting(Met.RAIDED_BANDS_SLIPPED_AWAY)
        ob.grudge[b.mainLineage()] += 0.5f * GRUDGE; ob.befriend(g.id, -0.5f * TIE_RAID)
        return
    }
    val sides = raidSides(g, o, ob, planned, ob.atHome, weather)
    val sg = sides.raiders; val so = sides.defenders
    raidWorth(o, ob, sg, so, sides.own)
    // Each side loses some of its people, the more so against the stronger side; neighbours who stood with the raided
    // lose theirs as the raided do, of as many as came. (Each group in the fight, its fighters, and the share of them it may lose.)
    val fought = arrayListOf(Triple(g, g.adults, RAID_DEATHS * so / (sg + so)), Triple(o, o.adults, RAID_DEATHS * sg / (sg + so)))
    for (n in sides.helpers) {
        fought += Triple(n.group, (n.share * n.group.adults).roundToInt(), RAID_DEATHS * sg / (sg + so))
        stats.meeting(Met.NEIGHBOURS_DEFENDED)
        note(n.group) { "stood with band #%d against band #%d".format(o.id, g.id) }
    }
    val lostG = binomial(g.adults, fought[0].third, rnd)
    for ((k, side) in fought.withIndex()) {
        val (who, fighters, share) = side
        // (Many more are wounded than killed, whether or not any die: some fester, some are lamed for life.)
        who.band?.wounded(RAID_WOUNDED_PER_DEAD * share * fighters, who.adults)
        val lost = if (k == 0) lostG else binomial(fighters, share, rnd)
        if (lost <= 0) continue
        adultsDied(who, lost, rnd)
        stats.deaths.merge(Tally.of(who.species, DeathCause.RAIDS), lost, Int::plus)
        if (who.species.human) stats.adultDeaths.merge(Tally.of(who.species, DeathCause.RAIDS), lost, Int::plus)
        who.band?.let {
            stats.tally(Tally.bandsLivingDied(bandLiving(it), DeathCause.RAIDS), lost)
            stats.tally(Tally.bandsMindDied(it.mind, DeathCause.RAIDS), lost)
        }
        small.deposit(DeadPool.CARRION, who.place, Kg(lost * who.species.massKg))
    }
    // The stronger side carries off food; the raided remember.
    if (sg > so) { val t = RAID_TAKE * o.larder; moveFood(o, g, t, LarderMove.RAIDED); stats.meeting(Met.LARDERS_RAIDED) }
    // A band beaten at its home may be driven from it, the likelier the more the raiders overpower it (none when they are
    // only as strong; Wrangham and Glowacki 2012: victors drove the defeated off their land in most non-state wars).
    if (ob.atHome && sg > so && rnd.nextFloat() < 1f - so / sg) driveOff(o, ob, b)
    ob.grudge[b.mainLineage()] += GRUDGE
    ob.befriend(g.id, -TIE_RAID); b.befriend(o.id, -RAIDER_RESENT * TIE_RAID)
    if (lostG > 0) b.grudge[ob.mainLineage()] += RAIDER_RESENT * GRUDGE
    // (Those who stood with them remember the raiders too, as far as they stood.)
    for (n in sides.helpers) { n.band.grudge[b.mainLineage()] += n.share * GRUDGE; n.band.befriend(g.id, -n.share * TIE_RAID) }
}

/**
 * Band [o] ([ob]) is driven from its home by raiders of band [by]: it leaves its houses and palisade to whoever takes them up,
 * and while it fears the raiders' people it neither takes up houses there again nor seeks the place ([Band.keptFrom]).
 */
private fun HexGroups.driveOff(o: HexGroup, ob: Band, by: Band) {
    val h = ob.houseHex
    leaveStructures(o, h)
    ob.drivenFrom = h; ob.drivenBy = by.mainLineage()
    ob.houseHex = Hex.NONE; ob.house = 0f; ob.atHome = false; ob.palisade = 0f
    ob.homeEarth = 0f; ob.homeWalls = 0f; ob.homeGroundC = 0f
    stats.meeting(Met.BANDS_DRIVEN_FROM_HOME)
    note(o) { "was driven from its home at hex %d".format(h.i) }
}

/**
 * What a whole house would have done for [o] (band [ob]) in a raid of strength [sg] on its [so] (its [own] and its neighbours'), against the houses it has:
 * the lives it would have saved and the food it would have kept from the raiders, by [fight]'s rules; for [Band.houseLives]
 * and [Band.houseFood].
 */
private fun raidWorth(o: HexGroup, ob: Band, sg: Float, so: Float, own: Float) {
    val works = if (ob.atHome) 1f + HOUSE_DEFENCE * ob.house + PALISADE_DEFENCE * ob.palisade else 1f
    val whole = 1f + HOUSE_DEFENCE + (if (ob.atHome) PALISADE_DEFENCE * ob.palisade else 0f)
    // (Its own part of the defence, [own], behind a whole house; the neighbours who stood with it as they were.)
    val so1 = so + own * (whole / works - 1f)
    val lives = max(0f, o.adults * RAID_DEATHS * (sg / (sg + so) - sg / (sg + so1)))
    ob.houseLivesWeek += lives; ob.houseWeek[HouseWorth.RAIDS] += lives
    if (sg > so && sg <= so1) { ob.houseFoodWeek += RAID_TAKE * o.larder; ob.houseWeek[HouseWorth.RAID_KG] += RAID_TAKE * o.larder }
}

/** A band [group] (band [band]) at home where a neighbour is raided, standing with it with [share] of its people ([raidSides]). */
internal class Helper(val group: HexGroup, val band: Band, val share: Float)

/** The sides of a raid: the [raiders]' strength, the raided band's [own] and its [helpers], with theirs. */
internal class RaidSides(val raiders: Float, val own: Float, val helpers: List<Helper>, private val helped: Float) {
    /** All who hold off the raiders. */
    val defenders get() = own + helped
}

/** The raiders' [g] and the defenders' [o] (band [ob]) strengths when [g] falls on [o] where it is ([raidSides]). */
internal fun HexGroups.raidStrengths(g: HexGroup, o: HexGroup, ob: Band, planned: Boolean, atHome: Boolean, weather: Weather): Pair<Float, Float> =
    raidSides(g, o, ob, planned, atHome, weather).let { it.raiders to it.defenders }

/**
 * The sides when [g] falls on [o] (band [ob]) where it is: a planned raid may take it by surprise, the less so the more it
 * sees (scouts, open ground, moonlight); defenders [atHome] gain from high, broken ground, their houses and a palisade, and
 * the other bands at home in the same hex stand with them, each with as many of its people as it is closer to the raided
 * than to the raiders (by its [Regard] of each: a band that holds neither close, or the raiders as close, stays out), behind
 * its own houses and palisade. The one rule for a raid ([fight]) and for a raiding band weighing its target ([plotRaid]).
 */
internal fun HexGroups.raidSides(g: HexGroup, o: HexGroup, ob: Band, planned: Boolean, atHome: Boolean, weather: Weather): RaidSides {
    val p = o.place
    // Surprise: a planned raid at night, in cover, on a band that keeps no lookout.
    val look = sight(p, weather) * (0.5f + 0.5f * moon)
    val seenRaw = look * (1f + 5f * ob.scoutShare)
    val seen = seenRaw.coerceIn(0f, 1f)
    if (planned) dialUse?.amount(DialSite.SCOUTS_SEEN, ob.will[BandWay.SCOUTS], seen, (look * (1f + 5f * SCOUT_SHARE)).coerceIn(0f, 1f),
        cut = seenRaw > 1f)
    val surprise = if (planned) 1f + SURPRISE_RAID * (1f - seen) else 1f
    // The defenders' ground and works.
    val ground = 1f + HIGH_GROUND * land.bestDefence[p]
    val works = if (atHome) 1f + HOUSE_DEFENCE * ob.house + PALISADE_DEFENCE * ob.palisade else 1f
    val helpers = ArrayList<Helper>(); var helped = 0f
    val gb = g.band
    if (atHome && gb != null) {
        val h = hw.placeHex[p]
        for (n in groups) {
            val nb = n.band ?: continue
            if (n === o || n === g || !n.alive || !nb.atHome || hw.placeHex[n.place] != h) continue
            val share = Regard(nb, o.id, ob, map = hex).close - Regard(nb, g.id, gb, map = hex).close
            if (share <= 0f) continue
            helpers += Helper(n, nb, share)
            helped += share * strength(n) * ground * (1f + HOUSE_DEFENCE * nb.house + PALISADE_DEFENCE * nb.palisade)
        }
    }
    return RaidSides(strength(g) * surprise, strength(o) * ground * works, helpers, helped)
}
