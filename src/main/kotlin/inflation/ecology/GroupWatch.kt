package inflation.ecology

import inflation.ecology.GroupPlanner.PlanContext
import inflation.world.Place

// Following groups through their choices, kept apart from the model: the model calls [groupWatch] at a few points (a
// group's choice, its week's births and deaths, the week's end), and does nothing else for it; with no watch on, nothing.
// A new trace is a new watch: its own file, its own config keys, and a case in [groupWatch]; the model is not touched.
// The one so far is the doom trace ([DoomWatch]). See docs/tracing-groups.md.

/**
 * What a watch sees of the groups: each group's choice as it makes it ([PlanContext], the place chosen, whether a network
 * chose), each group's week of births and deaths, and the end of each week. A watch only reads: it draws nothing and
 * changes nothing, so a run with it is the run without it.
 */
internal interface GroupWatch {
    fun chose(planner: GroupPlanner, c: PlanContext, best: Place, byNet: Boolean) {}
    fun outcome(g: HexGroup, o: Outcome) {}
    fun weekEnd(groups: List<HexGroup>, date: inflation.world.Date) {}
}

/** The watch the run keeps, from its config: null (the model calls nothing) unless a trace is asked for. */
internal val groupWatch: GroupWatch? = if (DOOM_LIST || DOOM_TRACE.isNotEmpty()) DoomWatch else null
