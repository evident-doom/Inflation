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
        dialUse?.choice(DialSite.PALISADES, b.fortifyWill, b.sinceRaided < ticksPerYear * b.fortifyWill, b.sinceRaided < ticksPerYear)
    if (fortifies(b) && left > 0f && people > 0f) {
        val s = buildingAt(g, Built.PALISADE, h, -1, people)
        // (Practised hands build it the faster: the days go as the band's skill at fortifying.)
        val days = PALISADE_DAYS / b.skill(Skill.FORTIFYING)
        val built = minOf(1f - s.built, left / (days * people), gd[Material.WOOD] / (PALISADE_WOOD * people)).coerceAtLeast(0f)
        gd[Material.WOOD] -= built * PALISADE_WOOD * people; buildOn(s, built); time.spend(Activity.PALISADE, built * days * people)
        if (built > 0f) { stats.meeting(Met.BANDS_BUILT_PALISADES); b.practise(Skill.FORTIFYING) }
    }
}

/** Whether [b] rings its houses with a palisade: it knows how ([Skill.FORTIFYING]), at home, raided lately (by its will), with the ring not whole. */
internal fun GroupWorld.fortifies(b: Band) = b.knowsSkill[Skill.FORTIFYING] && b.atHome && b.sinceRaided < ticksPerYear * b.fortifyWill && b.palisade < 1f

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
        // (Its weeks are ticks: a step of several ticks uses up as many, so a raid gives up in the same days at any step length.)
        } else b.raid = if (raid.weeks > ticks) Raid(raid.hex, raid.on, raid.weeks - ticks) else null
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
        val ob = o.band ?: continue
        // (As the record of early foragers has it: planned raids are on bands with something fixed to take or hold, their
        // houses, fresh sowing or a palisade ([Band.livesFixed]); bands on the move are not worth it.)
        if (!ob.livesFixed()) continue
        val r = Regard(b, id, ob, map = hex)
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
        stats.meeting(Met.RAIDS_SET_OUT)
        // (A band that holds its home hex sends its fighters and stays: the raid is fought this step if the others are at the
        // home it knows, else it comes to nothing, as a band walking there finds it empty. Its fighters' walk there and back,
        // the whole day's going each way, is taken from its next step's days, [Band.raidWalkOwed].)
        if (staysHome(g)) {
            b.raidWalkOwed += raidParty(g) * (2.0 * hexKm(here, at) / (WALK_KM_PER_HOUR * Activity.WORK_HOURS)).toFloat()
            val target = bands.groupById[best]?.takeIf { it.alive && it.band != null && hw.placeHex[it.place] == at }
            if (target != null) bands.raidsDue += g to target
            note(g) { "sent its fighters to raid band #%d at hex %d".format(best, at.i) }
            return
        }
        b.raid = Raid(at, best, RAID_WEEKS)
        note(g) { "set out to raid band #%d at hex %d".format(best, at.i) }
    }
}

/** The raids set for this week ([plotRaid]), in the bands' order, each if both bands are still alive and the target still there. */
@Part("people: fight raids")
internal fun HexGroups.fightRaids(weather: Weather) {
    for ((g, target) in bands.raidsDue) {
        val b = g.band ?: continue; val tb = target.band ?: continue
        // (A band that holds its home hex fights from its own: its fighters went to the target's.)
        if (g.alive && target.alive && (staysHome(g) || hw.placeHex[target.place] == hw.placeHex[g.place])) {
            val u = Language.understanding(b.words, tb.words)
            tallyMeeting(MetAs(Regard(b, target.id, tb, u, hex), Regard(tb, g.id, b, u, hex)), Tally.MeetingEnd.RAIDED_HOME)
            // (The raiders' going there and back wears the way, [wearBetween].)
            wearBetween(hw.placeHex[g.place], hw.placeHex[target.place], g)
            // (Settled as any meeting is, a raid by intent, [settle]: by the strategies where a side has one, else as a raid.)
            if (tuning.packsStay) settleAt(g, target, hw.placeHex[target.place], weather, intent = Intent.RAID)
            else fight(g, b, target, tb, planned = true, weather)
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

/**
 * How much less likely a meeting comes to a raid between two bands neither of which lives fixed ([raid]): among mobile
 * foragers most lethal violence is within the band, and killing between groups a minority (Fry & Soderberg 2013: 148
 * killings in 21 mobile forager societies, more than half by lone killers, most for personal motives); among early
 * farmers it was endemic (Fibiger et al. 2023: over a tenth of 2300 skeletons of NW Europe's first farmers with weapon
 * injuries). An estimate, set to bring the mobile bands' killing between groups under 0.5 per 1000 a year.
 */
internal const val MOBILE_RAID = 0.1f

/**
 * The raiders band [g] sends on a planned raid, with [inflation.Tuning.bandStances]: [RAID_PARTY] of its able adults (its
 * grown less the wounded and lamed, [Band.ableShare]), the rest, its children and old staying at home; all its adults
 * without the switch, as before.
 */
internal fun HexGroups.raidParty(g: HexGroup): Float =
    if (tuning.bandStances) max(1f, g.adults * (g.band?.ableShare ?: 1f) * RAID_PARTY) else foragers(g).toFloat()

/** The share of a band's able adults that go on a planned raid ([raidParty]): its men of fighting age, about half its grown; an estimate. */
internal const val RAID_PARTY = 0.5f
internal const val RAID_PLOT = 0.1f
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

/**
 * How far band [g] is under strain, 0..1, for whether crowding or trespass bring it to raid ([raid]): its want this week
 * (1 - fed) or its reserves run down (1 - fat over the most it holds), whichever is more. Among foragers, raiding and
 * feuding rise with resource stress, above all unpredictable shortfalls, not with numbers as such (Ember and Ember 1992,
 * J. Confl. Resolut. 36: 242-262, from memory: to check). (Before 12 Nov 2026 crowding drove raids of itself: at x4 raids
 * were 14% of grown deaths and at x8 20%, while food did not bind, adults746.)
 */
internal fun raidStrain(g: HexGroup): Float =
    max(1f - g.fed, 1f - g.fat / g.species.maxFatDays.coerceAtLeast(1f)).coerceIn(0f, 1f)

/** [g] raids [o] (as it regards it: [r]) if it is hungry and [o] has food, or holds a feud against its people. Returns whether it did. */
internal fun HexGroups.raid(g: HexGroup, b: Band, o: HexGroup, ob: Band, r: Regard, weather: Weather): Boolean {
    // What drives a raid: an old feud, or competition (hunger with food in the other's larder, or too
    // many people on the same country); kin and those who understand each other fight less.
    val feud = r.feud
    val hunger = if (o.larder > 10f * o.dailyNeedKg) (1f - g.fed) else 0f
    // (On ground it holds as its own, a band drives off those it is not close to: trespass.)
    val trespass = if (b.claims(hw.placeHex[g.place], g.memory) && r.tie <= 0f) CLAIM_PRESSURE else 0f
    // (Crowding and trespass drive a band to blows only as far as it is under strain, [raidStrain]: a band fed and with its
    // reserves full does not raid however many others share its country (Olli, 12 Nov 2026: raiding is a stress reaction).)
    val strain = raidStrain(g)
    val pressure = if (feud) 1f else maxOf(hunger, strain * min(1f, peopleCrowding(g)), strain * trespass)
    // (Whether it comes to blows is the band's choice: its readiness to raid; and how it regards the others. And what there
    // is to fight over: a band living fixed has its houses, fields and stores to take or hold; between bands on the move, as
    // mobile foragers, killing between groups is rare, most of their violence being within the band ([MOBILE_RAID]).)
    // (Over the meetings one meeting stands for ([meetChance]): a pair that met that many times had that many chances to fall out.)
    val once = r.fightChance(RAID * b.raidWill * pressure * (if (ob.livesFixed() || b.livesFixed()) 1f else MOBILE_RAID))
    val chance = meetChance(once)
    if (!drawn(DialSite.RAIDS_MEETING, b.raidWill, rnd.nextFloat(), chance) { meetChance(once / b.raidWill.coerceAtLeast(1e-6f)) }) return false
    fight(g, b, o, ob, planned = false, weather)
    return true
}

/**
 * [g] falls on [o]. A planned raid on a band at home may take it by surprise, the less so the more
 * it sees (scouts, open ground, moonlight); the defenders gain from high, broken ground, their
 * houses and a palisade. A band with no home to hold may slip away, losing what it cannot carry; one beaten at its home
 * may be driven from it ([driveOff]).
 */
internal fun HexGroups.fight(g: HexGroup, b: Band, o: HexGroup, ob: Band, planned: Boolean, weather: Weather, edge: Float? = null) {
    stats.meeting(if (planned) Met.RAIDS_ON_HOMES else Met.RAIDS)
    note(g) { "raided band #%d%s".format(o.id, if (planned) " at its home" else "") }; note(o) { "was raided by band #%d".format(g.id) }
    ob.sinceRaided = 0
    // (Both see what the other is, [noteSeen].)
    noteSeen(g, o, weather.date.absoluteDay); noteSeen(o, g, weather.date.absoluteDay)
    // The mobile slip away (and lose some of their stores).
    if (!ob.atHome && rnd.nextFloat() < FLEE) {
        val t = SLIP_LOSS * RAID_TAKE * o.larder; moveFood(o, g, t, LarderMove.RAIDED)
        stats.meeting(Met.RAIDED_BANDS_SLIPPED_AWAY)
        ob.grudge[b.mainLineage()] += 0.5f * GRUDGE; ob.befriend(g.id, -0.5f * TIE_RAID)
        return
    }
    val sides = raidSides(g, o, ob, planned, ob.atHome, weather, edge)
    val sg = sides.raiders; val so = sides.defenders
    raidWorth(o, ob, sg, so, sides.own)
    // Each side loses some of its people, the more so against the stronger side; neighbours who stood with the raided
    // lose theirs as the raided do, of as many as came. (Each group in the fight, its fighters, and the share of them it may lose.)
    // (Who fights, with t.bandStances: a planned raid's party, its able adults that went ([raidParty]), against the able
    // adults of the band at home; children and those who stayed behind do not fight.)
    val raiders = if (planned && tuning.bandStances) raidParty(g).roundToInt().coerceIn(1, g.adults) else g.adults
    val defenders = if (tuning.bandStances) (o.adults * (ob.ableShare)).roundToInt().coerceIn(1, o.adults) else o.adults
    val fought = arrayListOf(Triple(g, raiders, RAID_DEATHS * so / (sg + so)), Triple(o, defenders, RAID_DEATHS * sg / (sg + so)))
    for (n in sides.helpers) {
        fought += Triple(n.group, (n.share * n.group.adults).roundToInt(), RAID_DEATHS * sg / (sg + so))
        stats.meeting(Met.NEIGHBOURS_DEFENDED)
        note(n.group) { "stood with band #%d against band #%d".format(o.id, g.id) }
    }
    val lostG = binomial(raiders, fought[0].third, rnd)
    for ((k, side) in fought.withIndex()) {
        val (who, fighters, share) = side
        // (Many more are wounded than killed, whether or not any die: some fester, some are lamed for life.)
        who.band?.wounded(RAID_WOUNDED_PER_DEAD * share * fighters, who.adults)
        val lost = if (k == 0) lostG else binomial(fighters, share, rnd)
        if (lost <= 0) continue
        adultsDied(who, lost, rnd)
        who.band?.let { stats.meeting(if (it.livesFixed()) Met.RAID_KILLED_FIXED else Met.RAID_KILLED_MOBILE, lost) }
        stats.deaths.merge(Tally.of(who.species, DeathCause.RAIDS), lost, Int::plus)
        if (who.species.human) stats.adultDeaths.merge(Tally.of(who.species, DeathCause.RAIDS), lost, Int::plus)
        who.band?.let {
            it.raidDeathRate += lost.toFloat() / max(1, who.size)
            stats.tally(Tally.bandsLivingDied(bandLiving(it), DeathCause.RAIDS), lost)
            stats.tally(Tally.bandsMindDied(it.mind, DeathCause.RAIDS), lost)
        }
        // (Raiders who hold their home hex fell where they fought, at the raided band's.)
        small.deposit(DeadPool.CARRION, if (k == 0 && staysHome(g)) o.place else who.place, Kg(lost * who.species.massKg))
    }
    // The stronger side carries off food; the raided remember.
    if (sg > so) { val t = RAID_TAKE * o.larder; moveFood(o, g, t, LarderMove.RAIDED); stats.meeting(Met.LARDERS_RAIDED) }
    // A band beaten at its home may be driven from it, the likelier the more the raiders overpower it (none when they are
    // only as strong; Wrangham and Glowacki 2012: victors drove the defeated off their land in most non-state wars).
    if (ob.atHome && sg > so && rnd.nextFloat() < 1f - so / sg) driveOff(o, ob, b, hw.placeHex[g.place])
    ob.grudge[b.mainLineage()] += GRUDGE
    ob.befriend(g.id, -TIE_RAID); b.befriend(o.id, -RAIDER_RESENT * TIE_RAID)
    if (lostG > 0) b.grudge[ob.mainLineage()] += RAIDER_RESENT * GRUDGE
    // (Those who stood with them remember the raiders too, as far as they stood.)
    for (n in sides.helpers) { n.band.grudge[b.mainLineage()] += n.share * GRUDGE; n.band.befriend(g.id, -n.share * TIE_RAID) }
}

/**
 * Band [o] ([ob]) is driven from its home by raiders of band [by], whose home is hex [byHex]: it leaves its houses and palisade
 * to whoever takes them up, and while it fears the raiders' people it neither takes up houses there again nor seeks the place
 * ([Band.keptFrom]). A band that holds its home hex ([staysHome]) leaves the hex too, after the step's raids ([scatterBeaten]).
 */
private fun HexGroups.driveOff(o: HexGroup, ob: Band, by: Band, byHex: Hex) {
    val h = ob.houseHex
    leaveStructures(o, h)
    ob.drivenFrom = h; ob.drivenBy = by.mainLineage()
    ob.houseHex = Hex.NONE; ob.house = 0f; ob.atHome = false; ob.palisade = 0f
    ob.homeEarth = 0f; ob.homeWalls = 0f; ob.homeGroundC = 0f
    stats.meeting(Met.BANDS_DRIVEN_FROM_HOME)
    note(o) { "was driven from its home at hex %d".format(h.i) }
    if (staysHome(o)) beaten += o to byHex
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
internal fun HexGroups.raidSides(g: HexGroup, o: HexGroup, ob: Band, planned: Boolean, atHome: Boolean, weather: Weather, edge: Float? = null): RaidSides {
    val p = o.place
    // Surprise: a planned raid at night, in cover, on a band that keeps no lookout.
    val look = sight(p, weather) * (0.5f + 0.5f * moon)
    val seenRaw = look * (1f + 5f * ob.scoutShare)
    val seen = seenRaw.coerceIn(0f, 1f)
    if (planned) dialUse?.amount(DialSite.SCOUTS_SEEN, ob.will[BandWay.SCOUTS], seen, (look * (1f + 5f * SCOUT_SHARE)).coerceIn(0f, 1f),
        cut = seenRaw > 1f)
    // (Where an engagement settled whether the raiders came unawares ([engage]), its [edge] in place of the raid's own draw.)
    val surprise = edge ?: if (planned) 1f + SURPRISE_RAID * (1f - seen) else 1f
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
    // (With t.bandStances, each side as many as fight: a planned raid's party ([raidParty]), the able at home ([Band.ableShare]).)
    val raidersHere = if (tuning.bandStances) (if (planned) raidParty(g) / g.adults.coerceAtLeast(1) else gb?.ableShare ?: 1f) else 1f
    val defendersHere = if (tuning.bandStances) ob.ableShare else 1f
    return RaidSides(strength(g) * raidersHere * surprise, strength(o) * defendersHere * ground * works, helpers, helped)
}
