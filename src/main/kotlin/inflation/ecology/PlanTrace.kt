package inflation.ecology

import inflation.ecology.GroupPlanner.PlaceTerms
import inflation.ecology.GroupPlanner.PlanContext
import inflation.ecology.GroupPlanner.Scratch
import inflation.world.Hex
import inflation.world.Place
import inflation.world.get
import inflation.world.set
import kotlin.math.roundToLong

// Choosing, for the reader: the watched group's places, planDiag's reasons, scores that came out not a number, moveTrace's book.

/** For the watched group ([watched]): the places it weighed ([seen], with their scores) and where it went ([best]), into [watchLog]. */
internal fun GroupPlanner.logWatched(c: PlanContext, seen: List<Pair<Int, Float>>, best: Place, byNetwork: Boolean) {
    val g = c.g
    fun where(p: Int) = "hex %d at %.0f m".format(gs.hw.placeHex[p].i, gs.hw.placeElevationM[p])
    val top = seen.sortedByDescending { it.second }.take(5)
    gs.watchLog += "weighed %d places%s; went to %s (hand-written score %.2f%s)".format(seen.size, if (c.restless) ", restless" else "", where(best.i),
        seen.firstOrNull { it.first == best.i }?.second ?: 0f, if (byNetwork) ", chosen by the network" else "")
    for ((p, score) in top) gs.watchLog += "   %s%s: %.2f".format(where(p), if (p == g.place.i) " (stay)" else "", score)
}

/**
 * planDiag=kind: one group's reasons ([c], the places it weighed in [seen]), for the place it chose ([best]), staying
 * and the best-placed step toward its season's place.
 */
internal fun GroupPlanner.printPlanDiag(c: PlanContext, seen: List<Pair<Int, Float>>, best: Place) {
    val g = c.g; val s = g.species; val kind = c.kind; val goalHex = c.goalHex; val reached = c.buf.reached
    // (The score is the hand-written one before its small noise and the group's temper, as [scorePlace] gives it.)
    fun parts(p: Place): String {
        val h = gs.hw.placeHex[p]; val hunters = huntersKm2(c, p, h)
        val dying = deathsAt(c, p, h, 0f, c.buf.dist[h])
        return "score %.2f: deaths %.4f (%.1f steps) food %.2f crowd %.2f hunters %.2f kg/km2 water %.1f km going %.0f km (%.0f km from goal)".format(
            seen.firstOrNull { it.first == p.i }?.second ?: Float.NaN, dying, gs.survivalSteps(g, dying), placeValue[kind][p],
            gs.crowdedBy(s, kinAround(g, kind, h, c.sensed)), hunters,
            if (s.human) gs.land.drink.peopleWaterKm(p) else gs.land.drink.waterKm[p], c.buf.dist[h], gs.hexKm(h, goalHex))
    }
    val towards = (0 until reached.size).map { reached[it] }.minByOrNull { gs.hexKm(it, goalHex) }?.let { h ->
        gs.hw.placeNear(g.place, h).takeIf { !it.none } }
    println("plan ${s.name} #${g.id} of ${g.size} month ${c.date.month + 1}, ${if (c.restless) "restless" else "content"}, reach %.0f km, weighed %d places: chose ".format(c.reach, seen.size) +
        parts(best) + (if (best != g.place) " | stay " + parts(g.place) else "") + (towards?.let { " | toward goal " + parts(it) } ?: ""))
}

/**
 * Prints the first few places whose hand-written score for [c]'s group came out not a number (NaN or infinite), with
 * the parts it is made of: such a place is never chosen, by the hand-written rules or a network, so nothing else shows
 * it. [alive] is the score before its deaths, [drowning] the crossing's; [t] the place's terms (null where they were not kept);
 * the rest as [PlanContext.at] holds them.
 */
internal fun GroupPlanner.tellNotANumber(c: PlanContext, t: PlaceTerms?, p: Place, h: Hex, drowning: Float, alive: Float) {
    if (notANumber.incrementAndGet() > 20) return
    val g = c.g; val s = g.species
    val d = c.at.d; val prey = c.at.prey; val crowded = c.at.crowded; val fed = c.at.fed
    val deaths = deathsAt(c, p, h, fed, d)
    val thirst = gs.thirstOdds(g, p, gs.dryAfter(g, p, fed))
    val causes = StringBuilder()
    gs.foreseenHazards(g, p, c.weather, fed, thirst, d) { cause, a, y ->
        if (a != 0f || y != 0f) causes.append(" ").append(cause).append(" ").append(a).append("/").append(y) }
    val terms = t?.let { "food value %s discomfort %s drain %s suits %s water %s km".format(placeValue[c.kind][p], it.discomfort, it.drain, it.suitsLn, it.waterKm) } ?: "not kept"
    println("not a number: ${s.name} #${g.id} of ${g.size} (${g.adults} grown, ${g.young} young, odds of dying ${g.deathOdds}, fed ${g.fed}) at place ${p.i}, " +
        "$d km of going: score before deaths $alive, deaths $deaths, drowning $drowning, steps ${gs.survivalSteps(g, deaths + drowning)}; " +
        "foreseen fed $fed, thirst $thirst, crowded $crowded, prey $prey; " +
        "hazards:$causes; terms: $terms")
}

/** moveTrace: books a band's week ([moveCounts]): whether the hand-written score moved it, and if so, by what parts. */
internal fun GroupPlanner.bookMove(moved: Boolean, buf: Scratch) {
    moveCounts.incrementAndGet(MOVE_TRACE_WEEKS)
    if (!moved) return
    moveCounts.incrementAndGet(MOVE_TRACE_MOVES)
    var lead = 0; var leadGain = Float.NEGATIVE_INFINITY
    for (k in MoveTerm.entries.indices) {
        val gain = buf.bestParts[k] - buf.stayParts[k]
        moveCounts.addAndGet(MOVE_TRACE_SUMS + k, (1e4f * gain).roundToLong())
        if (gain > leadGain) { leadGain = gain; lead = k }
    }
    moveCounts.incrementAndGet(MOVE_TRACE_LEADS + lead)
}

/**
 * For the watched group ([HexGroups.watched]): the deaths it foresees at place [p] as [deathsAt] reckons them, cause by
 * cause, each in survival steps as it adds to the whole ([survivalSteps] is linear in the deaths; the caps on the odds,
 * [Hazards.MAX_GROWN_ODDS], are left out), into [watchLog]. Report only: [deathsAt] calls [sink] as it adds each part.
 */
internal fun GroupPlanner.deathsWhy(c: PlanContext, p: Place) = DeathsWhy(this, c, p)

internal class DeathsWhy(private val planner: GroupPlanner, private val c: PlanContext, private val p: Place) {
    private val parts = ArrayList<Triple<String, Float, Float>>()
    val sink: (String, Float, Float) -> Unit = { cause, a, y -> parts += Triple(cause, a, y) }

    fun log(fed: Float, drowning: Float, leaving: Float, steps: Float) {
        val gs = planner.gs; val g = c.g
        fun stepsOf(share: Float) = gs.survivalSteps(g, share)
        fun share(a: Float, y: Float) = (g.adults * a + g.young * y) / maxOf(1, g.size)
        val sb = StringBuilder("deaths at hex %d at %.0f m%s: %.2f survival steps, foreseen fed %.2f".format(gs.hw.placeHex[p].i,
            gs.hw.placeElevationM[p], if (p == g.place) " (stay)" else "", steps, fed))
        for ((cause, a, y) in parts.sortedByDescending { share(it.second, it.third) }) if (share(a, y) > 0f)
            sb.append("; %s %.3g steps (grown %.2e, young %.2e a week)".format(cause, stepsOf(share(a, y)), a, y))
        if (drowning > 0f) sb.append("; crossing %.3g steps".format(stepsOf(drowning)))
        if (leaving > 0f) sb.append("; leaving what it cannot carry (year ahead) %.3g steps".format(stepsOf(leaving)))
        gs.watchLog += sb.toString()
    }
}
