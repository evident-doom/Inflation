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
    /** Holds the ground: never gives way, fights whoever does not, on the ground it holds best ([engage]: ground). */
    DEFEND,
    /** Gives way to one stronger by more than its will to fight bears, fights near equals: the animals' rule ([rivalsMeet]). */
    GIVE_WAY_TO_STRONGER,
    /** Gives way to whatever it sees coming. */
    YIELD,
    /** Keeps out of sight; seeing the other first unseen, falls on it if it is the stronger with surprise, else lets it by. */
    AMBUSH,
    /** Keeps out of sight and watches: seeing the other first unseen, it learns its strength and lets it by. */
    HIDE_AND_SCOUT,
    /** Falls on whatever it sees, whatever its strength. */
    ATTACK;

    /** Whether it keeps out of sight: it is harder to see where there is cover ([detects]). */
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
    val w = g.footWeights ?: return Ground.OUTSIDE
    for (i in 0 until foot.size) if (foot[i] == h) return if (w[i] >= CORE_WEIGHT) Ground.CORE else Ground.FRINGE
    return Ground.OUTSIDE
}

/** Group [g]'s strategy: a pack holding its ground keeps the one set for test ([inflation.Tuning.packStrategy]), if any; every other the animals'. */
internal fun GroupWorld.strategyOf(g: HexGroup): Strategy =
    if (g.footprint != null && g.band == null) packStrategy(tuning.packStrategy) ?: Strategy.ANIMALS else Strategy.ANIMALS

/** The strategy set for test ([inflation.Tuning.packStrategy]), read once while the text stays the same. */
private class ReadStrategy(val text: String, val strategy: Strategy?)
@Volatile private var lastRead: ReadStrategy? = null
private fun packStrategy(text: String?): Strategy? {
    if (text == null) return null
    lastRead?.let { if (it.text == text) return it.strategy }
    return Strategy.parse(text).also { lastRead = ReadStrategy(text, it) }
}

/** Whether a meeting of [a] and [b] over [contested] goes by their strategies ([engage]): it is a contact and one of them has a strategy other than the animals'. */
internal fun GroupWorld.engages(a: HexGroup, b: HexGroup, contested: HexArray?): Boolean =
    contested != null && tuning.packsStay && !(strategyOf(a).plain && strategyOf(b).plain)

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
internal fun GroupWorld.battleground(a: HexGroup, b: HexGroup, contested: HexArray, thickest: Hex): Hex {
    fun best(g: HexGroup, o: HexGroup): Hex {
        var at = Hex.NONE; var most = 0f
        for (i in 0 until contested.size) { val h = contested[i]
            if (strategyOf(g).at(groundOf(g, h)) != Stance.DEFEND) continue
            val v = holding(g, o, h); if (v > most) { most = v; at = h } }
        return at
    }
    val ha = best(a, b); val hb = best(b, a)
    return if (!ha.none && hb.none) ha else if (ha.none && !hb.none) hb else thickest
}

/** Whether [g] sees [other] at place [p] before it is seen, by its senses against the other's, the other harder to see in cover if it keeps out of sight ([Stance.hidden]). */
internal fun GroupWorld.detects(g: HexGroup, other: HexGroup, otherStance: Stance, p: Place, r: Random): Boolean {
    val hide = if (otherStance.hidden) 1f + ENGAGE_HIDING * land.plants.cover(p) else 1f
    return r.nextFloat() < g.species.senseKm / (g.species.senseKm + other.species.senseKm * hide)
}

/** What a side does at an engagement ([engage]). */
private enum class Act { FIGHT, WITHDRAW, LIE_LOW }

/**
 * A contact between [a] and [b] (indices [ak], [bk] among the meeting's parties, at their paths' [ai], [bi]) over the
 * hexes [contested], thickest at [thickest], settled by their strategies (see the head of this file). [stop] is the
 * meetings' (where a group on its way turns back), [r] the meetings' draws.
 */
@inflation.Part("meetings: engagements")
internal fun HexGroups.engage(a: HexGroup, ak: Int, ai: Int, b: HexGroup, bk: Int, bi: Int, contested: HexArray, thickest: Hex, stop: IntArray, r: Random) {
    stats.meeting(Met.ENGAGEMENTS)
    val h = battleground(a, b, contested, thickest)
    val p = hw.placeNear(a.place, h).takeIf { !it.none } ?: a.place
    val stA = strategyOf(a).at(groundOf(a, h)); val stB = strategyOf(b).at(groundOf(b, h))
    val aSees = detects(a, b, stB, p, r); val bSees = detects(b, a, stA, p, r)
    // (Each as strong as it stands there: holding its ground, and falling on the other unseen.)
    val sa0 = strength(a); val sb0 = strength(b)
    val holdA = if (stA == Stance.DEFEND) holding(a, b, h) else 1f; val holdB = if (stB == Stance.DEFEND) holding(b, a, h) else 1f
    fun act(st: Stance, sees: Boolean, seen: Boolean, own: Float, other: Float, will: Float): Act {
        if (!sees) return Act.LIE_LOW
        return when (st) {
            Stance.DEFEND, Stance.ATTACK -> Act.FIGHT
            Stance.YIELD -> Act.WITHDRAW
            Stance.GIVE_WAY_TO_STRONGER -> if (other > FIGHT_RATIO * will * own) Act.WITHDRAW else Act.FIGHT
            Stance.AMBUSH -> if (!seen) (if (own * ENGAGE_SURPRISE >= other) Act.FIGHT else Act.LIE_LOW)
                else if (other > FIGHT_RATIO * will * own) Act.WITHDRAW else Act.FIGHT
            Stance.HIDE_AND_SCOUT -> if (!seen) Act.LIE_LOW else if (other > FIGHT_RATIO * will * own) Act.WITHDRAW else Act.FIGHT
        }
    }
    val actA = act(stA, aSees, bSees, sa0 * holdA, sb0 * holdB, a.will[AnimalWay.FIGHT])
    val actB = act(stB, bSees, aSees, sb0 * holdB, sa0 * holdA, b.will[AnimalWay.FIGHT])
    // (One that falls on the other unseen strikes with surprise; one taken unawares can neither withdraw nor slip by.)
    val surpriseA = actA == Act.FIGHT && !bSees; val surpriseB = actB == Act.FIGHT && !aSees
    if (surpriseA || surpriseB) stats.meeting(Met.ENGAGEMENTS_AMBUSHED)
    val fight = actA == Act.FIGHT || actB == Act.FIGHT
    if (!fight) {
        when {
            actA == Act.WITHDRAW || actB == Act.WITHDRAW -> {
                // (Both withdrawing: the weaker as they stand gives the ground.)
                val give = if (actA == Act.WITHDRAW && (actB != Act.WITHDRAW || sa0 * holdA <= sb0 * holdB)) a else b
                if (give === a) stop[ak] = minOf(stop[ak], max(0, ai - 1)) else stop[bk] = minOf(stop[bk], max(0, bi - 1))
                gaveWay(give, contested, contestDay); stats.meeting(Met.ENGAGEMENTS_GAVE_WAY)
            }
            (aSees && actA == Act.LIE_LOW) || (bSees && actB == Act.LIE_LOW) -> stats.meeting(Met.ENGAGEMENTS_SCOUTED)
            else -> stats.meeting(Met.ENGAGEMENTS_PASSED_UNSEEN)
        }
        return
    }
    // (One that saw the other coming and withdraws gets away from one that falls on it, but gives the ground.)
    if (actA == Act.WITHDRAW && aSees && !surpriseB) { stop[ak] = minOf(stop[ak], max(0, ai - 1)); gaveWay(a, contested, contestDay); stats.meeting(Met.ENGAGEMENTS_GAVE_WAY); return }
    if (actB == Act.WITHDRAW && bSees && !surpriseA) { stop[bk] = minOf(stop[bk], max(0, bi - 1)); gaveWay(b, contested, contestDay); stats.meeting(Met.ENGAGEMENTS_GAVE_WAY); return }
    stats.meeting(Met.ENGAGEMENTS_FOUGHT)
    val sa = sa0 * holdA * (if (surpriseA) ENGAGE_SURPRISE else 1f); val sb = sb0 * holdB * (if (surpriseB) ENGAGE_SURPRISE else 1f)
    if (a.species == b.species) rivalsFight(a, ak, ai, b, bk, bi, h, stop, sa, sb) else enemiesClash(a, ak, ai, b, bk, bi, h, stop, sa, sb)
}

/** The weight in a footprint at and above which a hex is a pack's core, below it its fringe ([groundOf]); an estimate. */
internal const val CORE_WEIGHT = 0.5f
/** How much stronger one holding high ground is, at [ENGAGE_HIGH_M] or more above where the other comes from ([holding]); an estimate. */
internal const val ENGAGE_HIGH = 0.5f
internal const val ENGAGE_HIGH_M = 200f
/** How much stronger one holding its ground is in full cover ([holding]); an estimate. */
internal const val ENGAGE_COVER = 0.3f
/** How much harder one keeping out of sight is to see in full cover ([detects]); an estimate. */
internal const val ENGAGE_HIDING = 3f
/** How much stronger one falling on the other unseen is ([engage]); an estimate. */
internal const val ENGAGE_SURPRISE = 2f
