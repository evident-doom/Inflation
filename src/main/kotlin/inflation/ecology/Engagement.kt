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
    val ground = g.footprint ?: return Ground.OUTSIDE
    val foot = ground.hexes
    // (A band's lair is its camp's hex, the first of its ground, [bandGround].)
    if (g.band != null && foot.size > 0 && foot[0] == h) return Ground.LAIR
    val w = ground.weights
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
    contested ?: if (holdsGround(a) || holdsGround(b)) inflation.world.hexArray(1) { h } else null

/**
 * Whether a meeting of [a] and [b] over [contested] goes by their stances ([engage]): one of them takes a stance there
 * other than its kind's own way of meeting ([plainStance]: the animals' rule, or two bands greeting), judged where the
 * contact is thickest (the first of [contested] when nothing else says).
 */
internal fun HexGroups.engages(a: HexGroup, b: HexGroup, contested: HexArray?, at: Hex, day: Long): Boolean {
    if (contested == null) return false
    return stanceOf(a, b, at, day) != plainStance(a, b) || stanceOf(b, a, at, day) != plainStance(b, a)
}

/** [g]'s stance toward [other] when it meets it by its kind's own rules: two bands greet, every other the animals' rule. */
internal fun plainStance(g: HexGroup, other: HexGroup) = if (g.band != null && other.band != null) Stance.GREET else Stance.GIVE_WAY_TO_STRONGER

/** Whether [a] and [b] are two bands, which take their stances by regard ([bandStance]). */
internal fun bandStancesFor(a: HexGroup, b: HexGroup) = a.band != null && b.band != null

/**
 * [g]'s stance toward [other] at hex [h]: a strategy set for test ([strategyOf]), as it says of that part of its ground;
 * else, a band toward a band, by how it regards it and what it knows ([bandStance]);
 * else its kind's own way ([plainStance]).
 */
internal fun HexGroups.stanceOf(g: HexGroup, other: HexGroup, h: Hex, day: Long): Stance {
    val set = strategyOf(g)
    if (!set.plain) return set.at(groundOf(g, h))
    if (bandStancesFor(g, other)) return bandStance(g, other, h, day)
    if (avoids(g, other, h)) return Stance.HIDE_AND_SCOUT
    return plainStance(g, other)
}

/**
 * Whether hunter [g] keeps out of [other]'s way where they meet at hex [h]: it is no band, and [other] is much the stronger
 * there ([muchStronger]: of another kind, over [AVOID_RATIO] times as strong by those present). It then keeps out of sight and falls back if seen
 * ([Stance.HIDE_AND_SCOUT]): harder to find in cover ([sightings]), and one that sees the other coming gets away. Cheetahs
 * keep out of lions' areas and hours and move their cubs, leopards keep to cover and trees: the lesser hunters live
 * among the greater by not being found (Durant 1998, J. Anim. Ecol.; Vanak et al. 2013, Ecology). The same line as the
 * getting away's ([escapes]): no kind is named.
 */
internal fun HexGroups.avoids(g: HexGroup, other: HexGroup, h: Hex): Boolean = g.band == null && muchStronger(other, g, h)

/**
 * Band [g]'s stance toward band [other] at hex [h], on that part of its own ground ([groundOf]), by how it regards it
 * ([Regard]) and what it knows of its strength ([knownStrength]), as [regardStance] has it. Both as they would stand
 * there, as the meeting is then fought ([strengthAt], [knownAt]): its own as many of it as are there, the other's as many
 * of the band it knows as would be. The inputs are laid out as a learned chooser would read them ([StanceInputs]), so
 * that a network can take the place of the rule.
 */
internal fun HexGroups.bandStance(g: HexGroup, other: HexGroup, h: Hex, day: Long): Stance {
    val b = g.band ?: return Stance.GIVE_WAY_TO_STRONGER; val ob = other.band ?: return Stance.GIVE_WAY_TO_STRONGER
    val r = Regard(b, other.id, ob, map = hex)
    val ground = groundOf(g, h)
    val own = strengthAt(g, h).coerceAtLeast(1e-3f)
    val known = knownStrength(g, other, day)?.let { knownAt(other, it, h) }
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
internal fun GroupWorld.knownStrength(g: HexGroup, other: HexGroup, today: Long): Float? {
    val b = g.band ?: return null
    return b.seen(other.id, today)
}

/**
 * What a band that knows [other]'s whole strength as [known] ([knownStrength]) expects of it at hex [h]: as many of it as
 * would be there ([presentAt]: all its able at its camp, a party out), the stronger in its lair ([lairAdvantage]).
 */
internal fun HexGroups.knownAt(other: HexGroup, known: Float, h: Hex): Float =
    known * presentAt(other, h) / other.adults.coerceAtLeast(1) * lairAdvantage(other, h)

/**
 * Band [g] saw [other] on [day]: it remembers its strength ([Band.seenStrength]), the whole band's (how many they are, as
 * neighbours know of each other; [knownAt] takes it to where they meet); nothing for a group that is not a band, or of one.
 */
internal fun HexGroups.noteSeen(g: HexGroup, other: HexGroup, day: Long) {
    val b = g.band ?: return
    if (other.band == null) return
    b.saw(other.id, strength(other), day)
}

/**
 * The people's network's view of what band [g] ([b]) has seen of the bands about lately ([BandInputs.SEEN]): the strongest
 * it saw within [SEEN_DAYS] against its own strength, the strongest of those it feuds with or is wary of, and how many it
 * saw (a few is many); those it saw longer ago forgotten.
 */
internal fun HexGroups.seenInputs(g: HexGroup, b: Band, into: FloatArray, now: Long) {
    b.forgetSeen(now)
    val own = strength(g).coerceAtLeast(1e-3f)
    var most = 0f; var hostile = 0f; var n = 0
    for ((id, s) in b.seenStrength) {
        n++; most = maxOf(most, s / own)
        StaleCheck.read("groupById", bands.whereStamp)
        val o = bands.groupById[id]?.takeIf { it.alive }?.band ?: continue
        val r = Regard(b, id, o, map = hex)
        if (r.feud || r.wariness / Regard.MOST_WARY > WARY_STANCE) hostile = maxOf(hostile, s / own)
    }
    into[BandInputs.SEEN] = minOf(3f, most) / 3f; into[BandInputs.SEEN_HOSTILE] = minOf(3f, hostile) / 3f
    into[BandInputs.SEEN_COUNT] = minOf(1f, n / 5f)
}

/**
 * Whether [g], falling back from [other] that comes on at place [p], outruns it: one fleeing a hunter of another kind more
 * than [AVOID_RATIO] times as strong gets away as it slips away from one ([slipChance]: by its senses and the shelter about,
 * at most nine in ten), drawn by [r] only then; from any other it gets away. So the lesser hunters' safety is mostly in
 * not being found: cheetahs outrun lions, but their cubs do not (Laurenson 1994: lions the chief killers of cheetah cubs).
 */
internal fun HexGroups.escapes(g: HexGroup, other: HexGroup, p: Place, r: Random): Boolean =
    if (!muchStronger(other, g, hw.placeHex[p])) true else r.nextFloat() < slipChance(g.species, other.species, p)

/** How many times stronger another kind must be before a hunter keeps out of its way ([avoids]) and must outrun it to get away ([escapes]). */
internal const val AVOID_RATIO = 2f

/** Days a band remembers what it saw of another ([Band.seenStrength]): a season; an estimate. */
internal const val SEEN_DAYS = 90L
/** The share of the wariest regard past which a band is wary of another and keeps its distance ([regardStance]); an estimate. */
internal const val WARY_STANCE = 0.5f

/**
 * How well [g] holds hex [h] against [other] coming on it, if it stands to hold it ([Stance.DEFEND]): by the lie of it, as
 * it stands on high ground over where the other comes from ([ENGAGE_HIGH], up to [ENGAGE_HIGH_M] above) and in cover
 * ([ENGAGE_COVER]). 1 for no advantage. Its lair's advantage is not here: a group has it in its lair whatever its stance
 * ([lairAdvantage], [strengthAt]).
 */
internal fun GroupWorld.holding(g: HexGroup, other: HexGroup, h: Hex): Float {
    val p = hw.placeNear(g.place, h).takeIf { !it.none } ?: g.place
    val above = (hw.placeElevationM[p.i] - hw.placeElevationM[other.place.i]) / ENGAGE_HIGH_M
    return (1f + ENGAGE_HIGH * above.coerceIn(0f, 1f)) * (1f + ENGAGE_COVER * land.plants.cover(p))
}

/**
 * Where a contact over [contested] is fought: of those hexes, the one a side that would hold any draws it to, the one it
 * stands best on (by the lie of it, [holding], and its lair, [lairAdvantage]; of equals, the first); [thickest] where
 * neither or both would hold some.
 */
internal fun HexGroups.battleground(a: HexGroup, b: HexGroup, contested: HexArray, thickest: Hex, day: Long): Hex {
    fun best(g: HexGroup, o: HexGroup): Hex {
        var at = Hex.NONE; var most = 0f
        for (i in 0 until contested.size) { val h = contested[i]
            if (stanceOf(g, o, h, day) != Stance.DEFEND) continue
            val v = holding(g, o, h) * lairAdvantage(g, h); if (v > most) { most = v; at = h } }
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
 * ground, if it outruns the other ([aEscapes], [bEscapes]: drawn before, [escapes]); two that both withdraw, the weaker
 * gives it. Pure: the draws are made before ([sightingsOf]).
 */
internal fun settleEngagement(stA: Stance, stB: Stance, aSees: Boolean, bSees: Boolean, sa0: Float, sb0: Float, willA: Float, willB: Float, raid: Boolean,
                              aEscapes: Boolean = true, bEscapes: Boolean = true): Engaged {
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
    if (actA == Act.WITHDRAW && aSees && !surpriseB && (actB != Act.FIGHT || aEscapes)) return ended(Ending.A_GAVE_WAY)
    if (actB == Act.WITHDRAW && bSees && !surpriseA && (actA != Act.FIGHT || bEscapes)) return ended(Ending.B_GAVE_WAY)
    val sa = sa0 * (if (surpriseA) ENGAGE_SURPRISE else 1f); val sb = sb0 * (if (surpriseB) ENGAGE_SURPRISE else 1f)
    val aStrikes = actA == Act.FIGHT && (actB != Act.FIGHT || surpriseA || (!surpriseB && (raid || (aSees && !bSees) || (aSees == bSees && sa >= sb))))
    return Engaged(Ending.FOUGHT, surpriseA, surpriseB, aStrikes, sa, sb)
}

/**
 * The encounter [met] over the hexes [contested] (thickest at its hex), settled by the two sides' strategies (see the head of
 * this file), on the ground the one that would hold some draws it to ([battleground]); [r] the meetings' draws. Returns
 * whether it came to anything: one side gave way or they fought (not scouted or passed unseen).
 */
@inflation.Part("meetings: engagements")
internal fun HexGroups.engage(met: Encounter, contested: HexArray, r: Random, weather: inflation.world.Weather? = null): Boolean {
    stats.meeting(Met.ENGAGEMENTS)
    val a = met.a; val b = met.b; val intent = met.intent; val day = met.day
    val h = battleground(a, b, contested, met.hex, day)
    val p = hw.placeNear(a.place, h).takeIf { !it.none } ?: a.place
    // (Met on the ground the engagement chose: what follows is settled there.)
    val enc = met.at(h, p)
    // (A side that came to attack, a raid set out ([Intent.RAID]), attacks whatever its strategy says of that ground.)
    val raid = intent == Intent.RAID
    val stA = if (raid) Stance.ATTACK else stanceOf(a, b, h, day); val stB = stanceOf(b, a, h, day)
    // (Who sees whom first: drawn where they come on each other by chance; at a visit or a gathering each knows the other is
    // there; raiders know where they are going, the raided may not see them coming.)
    val aware = intent == Intent.VISIT || intent == Intent.GATHERING
    // (Raiders come on the raided as stealthily as they can: as one keeping out of sight.)
    val (aSaw, bSaw) = if (aware) true to true else sightings(a, if (raid) Stance.AMBUSH else stA, b, stB, p, r)
    val aSees = aware || raid || aSaw; val bSees = aware || bSaw
    // (What each saw of the other it remembers: a scout's watching is for this.)
    if (aSees) noteSeen(a, b, day); if (bSees) noteSeen(b, a, day)
    // (Each as strong as it stands there: those present, by their weight and weapons, in its lair the stronger ([strengthAt],
    // as every meeting weighs them; a raid's own party for the raiders, [raidParty]); holding its ground; falling on the other unseen.)
    val gathered = intent == Intent.GATHERING
    val sa0 = if (raid) raidParty(a) * a.species.massKg * ferocity(a) else strengthAt(a, h, gathered); val sb0 = strengthAt(b, h, gathered)
    val holdA = if (stA == Stance.DEFEND) holding(a, b, h) else 1f; val holdB = if (stB == Stance.DEFEND) holding(b, a, h) else 1f
    // (What each does and how it ends, by the numbers alone, [settleEngagement].)
    val e = settleEngagement(stA, stB, aSees, bSees, sa0 * holdA, sb0 * holdB, a.will[AnimalWay.FIGHT], b.will[AnimalWay.FIGHT], raid,
        escapes(a, b, p, r), escapes(b, a, p, r))
    val surpriseA = e.surpriseA; val surpriseB = e.surpriseB
    if (surpriseA || surpriseB) stats.meeting(Met.ENGAGEMENTS_AMBUSHED)
    when (e.ending) {
        // (One giving way to another kind leaves its kill to it, as at any meeting, [leavesKill].)
        Ending.A_GAVE_WAY -> { leavesKill(b, a, e.sb / max(1f, e.sa)); enc.turnBack(a); gaveWay(a, enc.contested, day); stats.meeting(Met.ENGAGEMENTS_GAVE_WAY); return true }
        Ending.B_GAVE_WAY -> { leavesKill(a, b, e.sa / max(1f, e.sb)); enc.turnBack(b); gaveWay(b, enc.contested, day); stats.meeting(Met.ENGAGEMENTS_GAVE_WAY); return true }
        Ending.SCOUTED -> { stats.meeting(Met.ENGAGEMENTS_SCOUTED); return false }
        Ending.PASSED_UNSEEN -> { stats.meeting(Met.ENGAGEMENTS_PASSED_UNSEEN); return false }
        Ending.FOUGHT -> {}
    }
    stats.meeting(Met.ENGAGEMENTS_FOUGHT)
    val sa = e.sa; val sb = e.sb
    // (Two bands fight by the people's own rules ([fight]), where they met and with those there: at the defenders' camp
    // behind their works and with the bands about that stand with them, elsewhere as parties on open ground. The engagement
    // has settled who falls on whom, whether unawares, and that nobody got away.)
    val ba = a.band; val bb = b.band
    if (ba != null && bb != null && weather != null) {
        if (e.aStrikes) fight(a, ba, b, bb, planned = raid, weather, h, gathered, edge = if (surpriseA) ENGAGE_SURPRISE else 1f)
        else fight(b, bb, a, ba, planned = false, weather, h, gathered, edge = if (surpriseB) ENGAGE_SURPRISE else 1f)
        return true
    }
    if (a.species == b.species) rivalsFight(enc, sa, sb) else enemiesClash(enc, sa, sb)
    return true
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
