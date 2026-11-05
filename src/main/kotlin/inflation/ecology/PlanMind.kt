package inflation.ecology

import inflation.Config
import inflation.drawUnit
import inflation.Stream

/**
 * How a band chooses ([Band.mind]), slot by slot (review/2026-11-05-people-models-harmonize.md). A band's week has four
 * choices beyond the rules every band lives by alike (the deaths a place would cost and the filter that never takes a place
 * a survival step deadlier than the safest, the survival pass's essentials, the chore asks, what its customs owe, the
 * raids' gate):
 * - where it would go: the place it scores best ([HexGroups.policyFor], a learned network, else the hand-written
 *   [GroupPlanner.scorePlace]); not a field here, since which network it is (or none) is set by the config, not the band;
 * - whether it leaves where it is ([gate]);
 * - how it splits its week's days beyond the essentials ([days]);
 * - where its ways' dials come from ([dials]).
 * Each mind is one filling of the last three:
 * - [NETS], the forager (today's way): it goes where its place scores draw it by its will to explore, splits its days by
 *   its ways' weights, and takes its ways from the ways network (the scaffold's without one). With no network at all it is
 *   the hand-written behaviour, which only teaches a fresh network and stands in for a missing one.
 * - [PLANS], the planning kind (doc: Discrete planning: build spec), under development: it weighs places by the same
 *   network but moves camp only when its customs send it, staying is an emergency, or its reckoning says leaving pays
 *   ([plannedMove]); holds its days by [planDays]; keeps the scaffold's ways (all 1) beyond what it reckons itself
 *   (its loads on a move, [relayLoads], and the tools worth making, [toolRates]).
 * Bands of two minds do not merge at a meeting; at home they may become one, choosing as the larger did.
 * (The entries' names are what a save keeps: they must not change.)
 */
enum class Mind(val label: String, private val gateAsBuilt: MoveGate, private val daysAsBuilt: DaySplit, private val dialsAsBuilt: DialSource) {
    NETS("nets", MoveGate.EXPLORE_DRAW, DaySplit.WEIGHTED, DialSource.WAYS),
    PLANS("plans", MoveGate.RECKONED, DaySplit.TIERED, DialSource.SCAFFOLD);

    /**
     * Its slots as this run has them: as built, but for the planning kind's under t.plannerSlots, a diagnostic key for
     * the slot-by-slot A/B (review/2026-11-05-people-models-harmonize.md, step 7): `gate:draw|reckoned`, `days:weighted|tiered`,
     * `dials:ways|scaffold`, comma-separated, any left out as built. With `t.planners=1`, `t.plannerSlots=days:weighted,dials:ways`
     * runs foragers that move by the planner's reckoning alone. (Read once a run, from the config.)
     */
    private val slots by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val given = if (this == PLANS) Config.tuning(PLANNER_SLOTS) else null
        Slots.parse(given, Slots(gateAsBuilt, daysAsBuilt, dialsAsBuilt))
    }
    val gate: MoveGate get() = slots.gate
    val days: DaySplit get() = slots.days
    val dials: DialSource get() = slots.dials
}

/** The config key that sets the planning kind's slots for the slot-by-slot A/B ([Mind]). */
internal const val PLANNER_SLOTS = "t.plannerSlots"

/** A mind's three slots ([Mind]). */
internal data class Slots(val gate: MoveGate, val days: DaySplit, val dials: DialSource) {
    companion object {
        /** [given] (as [PLANNER_SLOTS] has it; null or blank: none) over [base]. Stops on a slot or a value it does not know. */
        fun parse(given: String?, base: Slots): Slots {
            var out = base
            for (part in given.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }) {
                val (slot, value) = part.split(":").map { it.trim() }.takeIf { it.size == 2 }
                    ?: error("$PLANNER_SLOTS: \"$part\" is not slot:value (gate:draw|reckoned, days:weighted|tiered, dials:ways|scaffold)")
                out = when (slot) {
                    "gate" -> out.copy(gate = when (value) { "draw" -> MoveGate.EXPLORE_DRAW; "reckoned" -> MoveGate.RECKONED; else -> error("$PLANNER_SLOTS: gate is draw or reckoned, not $value") })
                    "days" -> out.copy(days = when (value) { "weighted" -> DaySplit.WEIGHTED; "tiered" -> DaySplit.TIERED; else -> error("$PLANNER_SLOTS: days is weighted or tiered, not $value") })
                    "dials" -> out.copy(dials = when (value) { "ways" -> DialSource.WAYS; "scaffold" -> DialSource.SCAFFOLD; else -> error("$PLANNER_SLOTS: dials is ways or scaffold, not $value") })
                    else -> error("$PLANNER_SLOTS: no slot \"$slot\" (gate, days, dials)")
                }
            }
            return out
        }
    }
}

/** How a band decides whether to leave where it is ([Mind.gate]). */
enum class MoveGate {
    /** It goes where a draw by its will to explore over its places' scores sends it ([exploreChoice]); leaving's lives count in each place's deaths ([Band.leavingDying]). */
    EXPLORE_DRAW,
    /**
     * It stays unless owed, in an emergency, or its reckoned case beats the walk ([plannedMove]); what leaving gives up
     * (stores, houses, crop, heavy tools) is reckoned once in that case and walk, not in each place's deaths; it keeps other
     * bands' news of their camps' yields ([tellYield]) to judge a fresh camp by, and chooses its loads ([relayLoads]).
     */
    RECKONED
}

/** How a band splits its week's days after the walk and the essentials ([Mind.days]). */
enum class DaySplit {
    /** Today's chore asks shared out by its ways' weights ([BandTime.plan]). */
    WEIGHTED,
    /** In tiers, each item held as far as its reckoning says it pays ([planDays]); its tools only those it reckons worth making ([toolRates]). */
    TIERED
}

/** Where a band's ways' dials come from each week ([Mind.dials]). */
enum class DialSource {
    /** The ways network ([HexGroups.ways]), the scaffold's without one. */
    WAYS,
    /** The scaffold's (all at 1, [Band.scaffoldWays]), whatever the network. */
    SCAFFOLD
}

/**
 * Each founding band's mind: [inflation.Tuning.planners] of them, drawn by the band's id from the world's seed (its own
 * hashed draw, so the world's stream of chance is untouched), start as [Mind.PLANS]; the rest as [Mind.NETS]. A saved world
 * carried on with [inflation.Tuning.planners] above 0 draws every band's mind afresh the same way once it is loaded, so a
 * world settled with nets alone (every test world) takes it up as a fresh one does; a split's heirs then draw by their own
 * ids. (Before, a loaded world kept its minds once any band was a planning one, and a band the fresh world drew before
 * the load could carry its mind into it: one test world took up 1% where all was asked.)
 */
internal fun HexGroups.foundMinds() {
    if (tuning.planners <= 0f) return
    for (g in groups) g.band?.let { b ->
        b.mind = if (drawUnit(seedBase, Stream.PLANNERS, g.id.toLong(), 0L) < tuning.planners) Mind.PLANS else Mind.NETS
    }
}

/**
 * The learned chooser group [g] weighs its places by: for a band whose mind has its own network ([HexGroups.mindPolicies]),
 * that one; else its kind's ([HexGroups.policies]); null for none (the hand-written score).
 */
internal fun HexGroups.policyFor(g: HexGroup): HexPolicy? {
    val m = g.band?.mind
    if (m != null) mindPolicies[m]?.get(g.species)?.let { return it }
    return policies[g.species]
}
