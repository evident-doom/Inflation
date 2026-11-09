package inflation.ecology

import inflation.world.Hex
import inflation.world.HexArray
import inflation.world.Place
import inflation.world.get
import kotlin.math.max
import kotlin.random.Random

// How a contact between two groups whose grounds overlap is settled (docs/design/pack-performance.md, "Engagements"):
// by each side's strategy, set beforehand, for the part of its ground the meeting falls on. A group may hold its lair
// whatever comes, give way to the stronger in its core and yield its fringe; another may lie in wait, or fall on whatever
// it meets. In order:
// 1. Ground: where they meet. Of the hexes they contest, a side that would hold some of them draws the meeting to the one
//    it holds best (high ground over the other, cover, its lair); else where their grounds overlap most.
// 2. Detection: each may see the other first; one keeping out of sight is harder to see where there is cover.
// 3. Choice: each does what its stance there says, by what it knows: of an unseen other, nothing.
// 4. Outcome: one that sees the other first may withdraw or slip by; one taken unawares cannot. Fights go by strength
//    as each stands there: on held ground, in its lair, by surprise. What a fight does is the meetings' own
//    ([rivalsFight], [enemiesClash]); giving way acts on the ground ([gaveWay]).
// The animals' rule is one strategy among these ([Strategy.ANIMALS]); a contact both of whose sides keep it is settled
// as any meeting is ([rivalsMeet], [enemiesMeet]), so nothing changes where nobody has another strategy.

/** The part of a group's ground a hex is ([groundOf]): its lair, its core, its fringe, or outside its ground. */
enum class Ground { LAIR, CORE, FRINGE, OUTSIDE }

/** What a group does on coming on another, by its strategy ([Strategy]) for the ground it is on. */
enum class Stance {
    /** Comes in peace: two that both greet meet as their kinds do (two bands talk, [bandsMeet]); against one that does not, as [GIVE_WAY_TO_STRONGER]. */
    GREET,
    /** Holds the ground: never gives way, fights whoever does not, on the ground it holds best ([engage]: ground). */
    DEFEND,
    /** Gives way to one stronger by more than its will to fight bears, fights near equals: the animals' rule ([rivalsMeet]). */
    GIVE_WAY_TO_STRONGER,
    /** Gives way to whatever it sees coming. */
    YIELD,
    /** Keeps out of sight; seeing the other first unseen, falls on it if it is the stronger with surprise, else lets it by. */
    AMBUSH,
    /** Keeps out of sight and watches: seeing the other first unseen, it learns its strength ([noteSeen]) and lets it by; seen, it falls back. */
    HIDE_AND_SCOUT,
    /** Falls on whatever it sees, whatever its strength. */
    ATTACK;

    /** Whether it keeps out of sight: it is harder to see where there is cover ([sightings]). */
    val hidden get() = this == AMBUSH || this == HIDE_AND_SCOUT
}

/** A group's strategy, set beforehand: its stance on each part of its ground. */
class Strategy(val lair: Stance, val core: Stance, val fringe: Stance, val outside: Stance) {
    /** Its stance on ground [g]. */
    fun at(g: Ground) = when (g) { Ground.LAIR -> lair; Ground.CORE -> core; Ground.FRINGE -> fringe; Ground.OUTSIDE -> outside }
    /** Whether it keeps the animals' rule everywhere: a contact both of whose sides do is settled as any meeting is. */
    val plain get() = lair == Stance.GIVE_WAY_TO_STRONGER && core == lair && fringe == lair && outside == lair

    companion object {
        /** The animals': give way to the stronger, fight near equals, on any ground ([rivalsMeet], [enemiesMeet]). */
        val ANIMALS = Strategy(Stance.GIVE_WAY_TO_STRONGER, Stance.GIVE_WAY_TO_STRONGER, Stance.GIVE_WAY_TO_STRONGER, Stance.GIVE_WAY_TO_STRONGER)

        /**
         * A strategy written as `lair:DEFEND,core:GIVE_WAY_TO_STRONGER,fringe:YIELD,outside:YIELD` (any part left out keeps the
         * animals' stance there); null for none.
         */
        fun parse(text: String?): Strategy? {
            if (text.isNullOrBlank()) return null
            val by = HashMap<Ground, Stance>()
            for (part in text.split(',')) {
                val (g, st) = part.split(':').map { it.trim().uppercase() }.also { require(it.size == 2) { "a strategy's part is ground:stance, not '$part'" } }
                by[Ground.valueOf(g)] = Stance.valueOf(st)
            }
            fun of(g: Ground) = by[g] ?: Stance.GIVE_WAY_TO_STRONGER
            return Strategy(of(Ground.LAIR), of(Ground.CORE), of(Ground.FRINGE), of(Ground.OUTSIDE))
        }
    }
}

/** The part of group [g]'s ground hex [h] is: its home its lair; of its footprint, the hexes weighted [CORE_WEIGHT] or more its core and the rest its fringe; else outside. */
internal fun groundOf(g: HexGroup, h: Hex): Ground {
    if (!g.home.none && h == g.home) return Ground.LAIR
    val foot = g.footprint ?: return Ground.OUTSIDE
    // (A band's lair is its camp's hex, the first of its ground, [bandGround].)
    if (g.band != null && foot.size > 0 && foot[0] == h) return Ground.LAIR
    val w = g.footWeights ?: return Ground.OUTSIDE
    for (i in 0 until foot.size) if (foot[i] == h) return if (w[i] >= CORE_WEIGHT) Ground.CORE else Ground.FRINGE
    return Ground.OUTSIDE
}

/** Group [g]'s strategy: a pack holding its ground keeps the one set for test ([inflation.Tuning.packStrategy]), if any; every other the animals'. */
internal fun GroupWorld.strategyOf(g: HexGroup): Strategy = when {
    g.footprint == null -> Strategy.ANIMALS
    g.band == null -> packStrategies.of(tuning.packStrategy) ?: Strategy.ANIMALS
    else -> bandStrategies.of(tuning.bandStrategy) ?: Strategy.ANIMALS
}

/** A strategy set for test ([inflation.Tuning.packStrategy], [inflation.Tuning.bandStrategy]), read once while its text stays the same. */
private class ReadStrategies {
    private class Read(val text: String, val strategy: Strategy?)
    @Volatile private var last: Read? = null
    fun of(text: String?): Strategy? {
        if (text == null) return null
        last?.let { if (it.text == text) return it.strategy }
        return Strategy.parse(text).also { last = Read(text, it) }
    }
}
private val packStrategies = ReadStrategies()
private val bandStrategies = ReadStrategies()

/**
 * The hexes a meeting of [a] and [b] at [h] is over, for the engagement: a contact's ([contested]); else, where one side
 * holds its ground there (a pack about its footprint, a resident stand-in in its hex), the meeting's hex; null for two
 * groups on their way, which meet by their timetables alone.
 */
internal fun engagedOver(a: HexGroup, b: HexGroup, contested: HexArray?, h: Hex): HexArray? =
    contested ?: if (holdsHere(a) || holdsHere(b)) inflation.world.hexArray(1) { h } else null

/** Whether [g] holds its ground where it is met: a pack about its footprint, or a resident stand-in (its id below 0) in its hex. */
internal fun holdsHere(g: HexGroup) = (g.footprint != null && g.path.size == 1) || g.id < 0

/**
 * Whether a meeting of [a] and [b] over [contested] goes by their stances ([engage]): one of them takes a stance there
 * other than its kind's own way of meeting ([plainStance]: the animals' rule, or two bands greeting), judged where the
 * contact is thickest (the first of [contested] when nothing else says).
 */
internal fun HexGroups.engages(a: HexGroup, b: HexGroup, contested: HexArray?, at: Hex = contested?.let { if (it.size > 0) it[0] else Hex.NONE } ?: Hex.NONE): Boolean {
    if (contested == null || !tuning.packsStay) return false
    if (strategyOf(a).plain && strategyOf(b).plain && !bandStancesFor(a, b)) return false
    return stanceOf(a, b, at) != plainStance(a, b) || stanceOf(b, a, at) != plainStance(b, a)
}

/** [g]'s stance toward [other] when it meets it by its kind's own rules: two bands greet, every other the animals' rule. */
internal fun plainStance(g: HexGroup, other: HexGroup) = if (g.band != null && other.band != null) Stance.GREET else Stance.GIVE_WAY_TO_STRONGER

/** Whether [a] and [b] are two bands that take their stances by regard ([inflation.Tuning.bandStances]). */
internal fun GroupWorld.bandStancesFor(a: HexGroup, b: HexGroup) = tuning.bandStances && a.band != null && b.band != null

/**
 * [g]'s stance toward [other] at hex [h]: a strategy set for test ([strategyOf]), as it says of that part of its ground;
 * else, a band toward a band with [inflation.Tuning.bandStances], by how it regards it and what it knows ([bandStance]);
 * else its kind's own way ([plainStance]).
 */
internal fun HexGroups.stanceOf(g: HexGroup, other: HexGroup, h: Hex): Stance {
    val set = strategyOf(g)
    if (!set.plain) return set.at(groundOf(g, h))
    if (bandStancesFor(g, other)) return bandStance(g, other, groundOf(g, h))
    return plainStance(g, other)
}

/**
 * Band [g]'s stance toward band [other] on ground [ground] of its own, by how it regards it ([Regard]) and what it knows of
 * its strength ([knownStrength]), as [regardStance] has it. The inputs are laid out as a learned chooser would read them
 * ([StanceInputs]), so that a network can take the place of the rule.
 */
internal fun HexGroups.bandStance(g: HexGroup, other: HexGroup, ground: Ground): Stance {
    val b = g.band ?: return Stance.GIVE_WAY_TO_STRONGER; val ob = other.band ?: return Stance.GIVE_WAY_TO_STRONGER
    val r = Regard(b, other.id, ob, map = hex)
    val own = strength(g).coerceAtLeast(1e-3f)
    val known = knownStrength(g, other)
    val x = FloatArray(StanceInputs.size)
    x[StanceInputs.TIE] = r.tie; x[StanceInputs.FRIEND] = if (r.friend) 1f else 0f; x[StanceInputs.FEUD] = if (r.feud) 1f else 0f
    x[StanceInputs.WARINESS] = r.wariness / Regard.MOST_WARY; x[StanceInputs.STRANGER] = if (r.stranger) 1f else 0f
    x[StanceInputs.KNOWN] = if (known != null) 1f else 0f; x[StanceInputs.THEIRS] = (known ?: own) / own
    x[StanceInputs.GROUND + ground.ordinal] = 1f
    return regardStance(x)
}

/**
 * The hand-written stance of a band toward another ([bandStance]), from inputs laid out as [StanceInputs]:
 * - friends greet;
 * - a feud: it holds its camp; about it, if it is the stronger by [FIGHT_RATIO], it stands its ground (gives way only to
 *   the stronger), else it falls back watching, as it does beyond. Whether to fall on them is not its stance's to say but
 *   its raids' ([raid], [plotRaid]: its readiness to raid, its hunger, how it regards them): a stance that attacked a
 *   weaker foe at every meeting, visits and all, set feuds burning (800x1200 km: raids on homes 35 to 120 a year);
 * - a stranger it has never met and is wary of (past [WARY_STANCE] of the wariest): it holds its camp, gives way to the
 *   stronger in its core, and keeps its distance beyond;
 * - else it greets: neighbouring bands are mostly kin and friends, and violence among early foragers was mostly within
 *   the band, not between bands (Olli, 9 Nov 2026: stay true to the record of foragers and hamlets).
 */
internal fun regardStance(x: FloatArray): Stance {
    val ground = Ground.entries.first { x[StanceInputs.GROUND + it.ordinal] > 0.5f }
    val theirs = x[StanceInputs.THEIRS]
    return when {
        x[StanceInputs.FRIEND] > 0.5f -> Stance.GREET
        x[StanceInputs.FEUD] > 0.5f -> when {
            ground == Ground.LAIR -> Stance.DEFEND
            ground == Ground.CORE && theirs * FIGHT_RATIO < 1f -> Stance.GIVE_WAY_TO_STRONGER
            else -> Stance.HIDE_AND_SCOUT
        }
        x[StanceInputs.STRANGER] > 0.5f && x[StanceInputs.WARINESS] > WARY_STANCE -> when (ground) {
            Ground.LAIR -> Stance.DEFEND
            Ground.CORE -> Stance.GIVE_WAY_TO_STRONGER
            else -> Stance.YIELD
        }
        else -> Stance.GREET
    }
}

/** The inputs of a band's stance toward another ([bandStance], [regardStance]), laid out as a learned chooser would read them. */
object StanceInputs : NamedSlots() {
    val TIE = one("tie"); val FRIEND = one("friend"); val FEUD = one("feud"); val WARINESS = one("wariness"); val STRANGER = one("stranger")
    val KNOWN = one("strength known"); val THEIRS = one("their strength against ours")
    val GROUND = block(Ground.entries.map { "ground:${it.name.lowercase()}" })
}

/**
 * What band [g] knows of [other]'s strength: as it last saw it ([Band.seenStrength]) within [SEEN_DAYS], else null (it
 * goes by its own, as if they were even).
 */
internal fun GroupWorld.knownStrength(g: HexGroup, other: HexGroup): Float? {
    val b = g.band ?: return null
    val day = b.seenDay[other.id] ?: return null
    if (contestDay - day > SEEN_DAYS) return null
    return b.seenStrength[other.id]
}

/** Band [g] saw [other] on [day]: it remembers its strength ([Band.seenStrength]); nothing for a group that is not a band, or of one. */
internal fun HexGroups.noteSeen(g: HexGroup, other: HexGroup, day: Long) {
    val b = g.band ?: return
    if (other.band == null || !tuning.bandStances) return
    b.seenStrength[other.id] = strength(other); b.seenDay[other.id] = day
}

/**
 * The people's network's view of what band [g] ([b]) has seen of the bands about lately ([BandInputs.SEEN]): the strongest
 * it saw within [SEEN_DAYS] against its own strength, the strongest of those it feuds with or is wary of, and how many it
 * saw (a few is many); those it saw longer ago forgotten.
 */
internal fun HexGroups.seenInputs(g: HexGroup, b: Band, into: FloatArray, now: Long) {
    val it = b.seenDay.entries.iterator()
    while (it.hasNext()) { val e = it.next(); if (now - e.value > SEEN_DAYS) { it.remove(); b.seenStrength.remove(e.key) } }
    val own = strength(g).coerceAtLeast(1e-3f)
    var most = 0f; var hostile = 0f; var n = 0
    for ((id, s) in b.seenStrength) {
        n++; most = maxOf(most, s / own)
        val o = bands.groupById[id]?.takeIf { it.alive }?.band ?: continue
        val r = Regard(b, id, o, map = hex)
        if (r.feud || r.wariness / Regard.MOST_WARY > WARY_STANCE) hostile = maxOf(hostile, s / own)
    }
    into[BandInputs.SEEN] = minOf(3f, most) / 3f; into[BandInputs.SEEN_HOSTILE] = minOf(3f, hostile) / 3f
    into[BandInputs.SEEN_COUNT] = minOf(1f, n / 5f)
}

/** Days a band remembers what it saw of another ([Band.seenStrength]): a season; an estimate. */
internal const val SEEN_DAYS = 90L
/** The share of the wariest regard past which a band is wary of another and keeps its distance ([regardStance]); an estimate. */
internal const val WARY_STANCE = 0.5f

/**
 * How well [g] holds hex [h] against [other] coming on it, if it stands to hold it ([Stance.DEFEND]): as it stands on high
 * ground over where the other comes from ([ENGAGE_HIGH], up to [ENGAGE_HIGH_M] above), in cover ([ENGAGE_COVER]), and in
 * its lair ([HOME_ADVANTAGE]). 1 for no advantage.
 */
internal fun GroupWorld.holding(g: HexGroup, other: HexGroup, h: Hex): Float {
    val p = hw.placeNear(g.place, h).takeIf { !it.none } ?: g.place
    val above = (hw.placeElevationM[p.i] - hw.placeElevationM[other.place.i]) / ENGAGE_HIGH_M
    return (1f + ENGAGE_HIGH * above.coerceIn(0f, 1f)) * (1f + ENGAGE_COVER * land.plants.cover(p)) * (if (groundOf(g, h) == Ground.LAIR) HOME_ADVANTAGE else 1f)
}

/**
 * Where a contact over [contested] is fought: of those hexes, the one a side that would hold any draws it to, the one it
 * holds best ([holding]; of equals, the first); [thickest] where neither or both would hold some.
 */
internal fun HexGroups.battleground(a: HexGroup, b: HexGroup, contested: HexArray, thickest: Hex): Hex {
    fun best(g: HexGroup, o: HexGroup): Hex {
        var at = Hex.NONE; var most = 0f
        for (i in 0 until contested.size) { val h = contested[i]
            if (stanceOf(g, o, h) != Stance.DEFEND) continue
            val v = holding(g, o, h); if (v > most) { most = v; at = h } }
        return at
    }
    val ha = best(a, b); val hb = best(b, a)
    return if (!ha.none && hb.none) ha else if (ha.none && !hb.none) hb else thickest
}

/**
 * Who sees whom when [a] (stance [stA]) and [b] (stance [stB]) come on each other by chance at place [p]: whether [a] sees
 * [b], and [b] sees [a]. One that keeps out of sight ([Stance.hidden]) is [ENGAGE_HIDING] times harder to see in full cover
 * (its hiding, 1 in the open or when it does not hide). Two that both hide may pass unseen, each missing the other as its
 * hiding has it; else one notices the other first, by its senses against the other's hiding; and the other notices it in
 * turn unless the one that noticed first keeps out of sight, as its hiding has it. So only keeping out of sight gives
 * surprise: two that do not hide see each other.
 */
internal fun GroupWorld.sightings(a: HexGroup, stA: Stance, b: HexGroup, stB: Stance, p: Place, r: Random): Pair<Boolean, Boolean> {
    val cover = land.plants.cover(p)
    return sightingsOf(a.species.senseKm, if (stA.hidden) 1f + ENGAGE_HIDING * cover else 1f, b.species.senseKm, if (stB.hidden) 1f + ENGAGE_HIDING * cover else 1f, r)
}

/**
 * [sightings] by the numbers: two sides of senses [senseA] and [senseB] and hiding [hideA] and [hideB] (1 for one that
 * does not hide); whether a sees b, and b sees a, drawn by [r].
 */
internal fun sightingsOf(senseA: Float, hideA: Float, senseB: Float, hideB: Float, r: Random): Pair<Boolean, Boolean> {
    if (r.nextFloat() < (1f - 1f / hideA) * (1f - 1f / hideB)) return false to false
    val wa = senseA / hideB; val wb = senseB / hideA
    return if (r.nextFloat() < wa / (wa + wb)) true to (r.nextFloat() < 1f / hideA) else (r.nextFloat() < 1f / hideB) to true
}

/** What a side does at an engagement ([engage]). */
internal enum class Act { FIGHT, WITHDRAW, LIE_LOW }

/**
 * What a side of stance [st] does at an engagement, by what it knows: whether it [sees] the other and is [seen] by it,
 * its strength [own] and the other's [other] as they stand there, and its will to fight [will] (how much stronger the
 * other must be before it gives way, against [FIGHT_RATIO]). One that does not see the other does nothing; else:
 * - DEFEND and ATTACK fight;
 * - YIELD withdraws;
 * - GIVE_WAY_TO_STRONGER and GREET withdraw from one stronger by more than their will bears, else fight;
 * - AMBUSH unseen falls on the other if it is the stronger with surprise ([ENGAGE_SURPRISE]), else lies low; seen, it
 *   gives way to the stronger;
 * - HIDE_AND_SCOUT unseen lies low and watches; seen, it falls back.
 */
internal fun actOf(st: Stance, sees: Boolean, seen: Boolean, own: Float, other: Float, will: Float): Act {
    if (!sees) return Act.LIE_LOW
    return when (st) {
        Stance.DEFEND, Stance.ATTACK -> Act.FIGHT
        Stance.GREET -> if (other > FIGHT_RATIO * will * own) Act.WITHDRAW else Act.FIGHT
        Stance.YIELD -> Act.WITHDRAW
        Stance.GIVE_WAY_TO_STRONGER -> if (other > FIGHT_RATIO * will * own) Act.WITHDRAW else Act.FIGHT
        Stance.AMBUSH -> if (!seen) (if (own * ENGAGE_SURPRISE >= other) Act.FIGHT else Act.LIE_LOW)
            else if (other > FIGHT_RATIO * will * own) Act.WITHDRAW else Act.FIGHT
        // (Watching is falling back with eyes open: unseen it lies low and watches, seen it withdraws.)
        Stance.HIDE_AND_SCOUT -> if (!seen) Act.LIE_LOW else Act.WITHDRAW
    }
}

/** How an engagement ends ([settleEngagement]). */
internal enum class Ending { PASSED_UNSEEN, SCOUTED, A_GAVE_WAY, B_GAVE_WAY, FOUGHT }

/**
 * How an engagement ends, and how it is fought ([engage]): [ending]; whether each side fell on the other unseen
 * ([surpriseA], [surpriseB]); for a fight, whether [a] is the one that strikes ([aStrikes]), for the people's fight
 * ([fight]); and the strengths the fight goes by ([sa], [sb]: as they stood, with surprise).
 */
internal class Engaged(val ending: Ending, val surpriseA: Boolean, val surpriseB: Boolean, val aStrikes: Boolean, val sa: Float, val sb: Float)

/**
 * The outcome of an engagement by the numbers: the two sides' stances [stA] and [stB], who sees whom ([aSees], [bSees]),
 * their strengths as they stand ([sa0], [sb0]: holding their ground included) and wills to fight ([willA], [willB]), and
 * whether [a] came to raid ([raid]). Each acts ([actOf]); one that falls on the other unseen strikes with surprise; one
 * taken unawares can neither withdraw nor slip by; one that saw the other coming and withdraws gets away, giving the
 * ground; two that both withdraw, the weaker gives it. Pure: the draws are made before ([sightingsOf]).
 */
internal fun settleEngagement(stA: Stance, stB: Stance, aSees: Boolean, bSees: Boolean, sa0: Float, sb0: Float, willA: Float, willB: Float, raid: Boolean): Engaged {
    val actA = actOf(stA, aSees, bSees, sa0, sb0, willA)
    val actB = actOf(stB, bSees, aSees, sb0, sa0, willB)
    val surpriseA = actA == Act.FIGHT && !bSees; val surpriseB = actB == Act.FIGHT && !aSees
    fun ended(e: Ending) = Engaged(e, surpriseA, surpriseB, false, sa0, sb0)
    if (actA != Act.FIGHT && actB != Act.FIGHT) return when {
        actA == Act.WITHDRAW || actB == Act.WITHDRAW ->
            ended(if (actA == Act.WITHDRAW && (actB != Act.WITHDRAW || sa0 <= sb0)) Ending.A_GAVE_WAY else Ending.B_GAVE_WAY)
        (aSees && actA == Act.LIE_LOW) || (bSees && actB == Act.LIE_LOW) -> ended(Ending.SCOUTED)
        else -> ended(Ending.PASSED_UNSEEN)
    }
    if (actA == Act.WITHDRAW && aSees && !surpriseB) return ended(Ending.A_GAVE_WAY)
    if (actB == Act.WITHDRAW && bSees && !surpriseA) return ended(Ending.B_GAVE_WAY)
    val sa = sa0 * (if (surpriseA) ENGAGE_SURPRISE else 1f); val sb = sb0 * (if (surpriseB) ENGAGE_SURPRISE else 1f)
    val aStrikes = actA == Act.FIGHT && (actB != Act.FIGHT || surpriseA || (!surpriseB && (raid || (aSees && !bSees) || (aSees == bSees && sa >= sb))))
    return Engaged(Ending.FOUGHT, surpriseA, surpriseB, aStrikes, sa, sb)
}

/**
 * A contact between [a] and [b] (indices [ak], [bk] among the meeting's parties, at their paths' [ai], [bi]) over the
 * hexes [contested], thickest at [thickest], settled by their strategies (see the head of this file). [stop] is the
 * meetings' (where a group on its way turns back), [r] the meetings' draws.
 */
@inflation.Part("meetings: engagements")
internal fun HexGroups.engage(a: HexGroup, ak: Int, ai: Int, b: HexGroup, bk: Int, bi: Int, contested: HexArray, thickest: Hex, stop: IntArray, r: Random,
                              intent: Intent = Intent.CHANCE, weather: inflation.world.Weather? = null) {
    stats.meeting(Met.ENGAGEMENTS)
    val h = battleground(a, b, contested, thickest)
    val p = hw.placeNear(a.place, h).takeIf { !it.none } ?: a.place
    // (A side that came to attack, a raid set out ([Intent.RAID]), attacks whatever its strategy says of that ground.)
    val raid = intent == Intent.RAID
    val stA = if (raid) Stance.ATTACK else stanceOf(a, b, h); val stB = stanceOf(b, a, h)
    // (Who sees whom first: drawn where they come on each other by chance; at a visit or a gathering each knows the other is
    // there; raiders know where they are going, the raided may not see them coming.)
    val aware = intent == Intent.VISIT || intent == Intent.GATHERING
    // (Raiders come on the raided as stealthily as they can: as one keeping out of sight.)
    val (aSaw, bSaw) = if (aware) true to true else sightings(a, if (raid) Stance.AMBUSH else stA, b, stB, p, r)
    val aSees = aware || raid || aSaw; val bSees = aware || bSaw
    // (What each saw of the other it remembers: a scout's watching is for this.)
    if (aSees) noteSeen(a, b, contestDay); if (bSees) noteSeen(b, a, contestDay)
    // (Each as strong as it stands there: holding its ground, and falling on the other unseen.)
    val sa0 = strength(a); val sb0 = strength(b)
    val holdA = if (stA == Stance.DEFEND) holding(a, b, h) else 1f; val holdB = if (stB == Stance.DEFEND) holding(b, a, h) else 1f
    // (What each does and how it ends, by the numbers alone, [settleEngagement].)
    val e = settleEngagement(stA, stB, aSees, bSees, sa0 * holdA, sb0 * holdB, a.will[AnimalWay.FIGHT], b.will[AnimalWay.FIGHT], raid)
    val surpriseA = e.surpriseA; val surpriseB = e.surpriseB
    if (surpriseA || surpriseB) stats.meeting(Met.ENGAGEMENTS_AMBUSHED)
    when (e.ending) {
        Ending.A_GAVE_WAY -> { stop[ak] = minOf(stop[ak], max(0, ai - 1)); gaveWay(a, contested, contestDay); stats.meeting(Met.ENGAGEMENTS_GAVE_WAY); return }
        Ending.B_GAVE_WAY -> { stop[bk] = minOf(stop[bk], max(0, bi - 1)); gaveWay(b, contested, contestDay); stats.meeting(Met.ENGAGEMENTS_GAVE_WAY); return }
        Ending.SCOUTED -> { stats.meeting(Met.ENGAGEMENTS_SCOUTED); return }
        Ending.PASSED_UNSEEN -> { stats.meeting(Met.ENGAGEMENTS_PASSED_UNSEEN); return }
        Ending.FOUGHT -> {}
    }
    stats.meeting(Met.ENGAGEMENTS_FOUGHT)
    val sa = e.sa; val sb = e.sb
    // (Two bands fight by the people's own rules ([fight]: their works, the lie of the land about the camp, the bands about
    // that stand with the defenders); the engagement has settled who falls on whom, and whether unawares.)
    val ba = a.band; val bb = b.band
    if (ba != null && bb != null && weather != null) {
        if (e.aStrikes) fight(a, ba, b, bb, planned = raid, weather, edge = if (surpriseA) ENGAGE_SURPRISE else 1f)
        else fight(b, bb, a, ba, planned = false, weather, edge = if (surpriseB) ENGAGE_SURPRISE else 1f)
        return
    }
    if (a.species == b.species) rivalsFight(a, ak, ai, b, bk, bi, h, stop, sa, sb) else enemiesClash(a, ak, ai, b, bk, bi, h, stop, sa, sb)
}

/** The weight in a footprint at and above which a hex is a pack's core, below it its fringe ([groundOf]); an estimate. */
internal const val CORE_WEIGHT = 0.5f
/** How much stronger one holding high ground is, at [ENGAGE_HIGH_M] or more above where the other comes from ([holding]); an estimate. */
internal const val ENGAGE_HIGH = 0.5f
internal const val ENGAGE_HIGH_M = 200f
/** How much stronger one holding its ground is in full cover ([holding]); an estimate. */
internal const val ENGAGE_COVER = 0.3f
/** How much harder one keeping out of sight is to see in full cover ([sightings]); an estimate. */
internal const val ENGAGE_HIDING = 3f
/** How much stronger one falling on the other unseen is ([engage]); an estimate. */
internal const val ENGAGE_SURPRISE = 2f
