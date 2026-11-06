package inflation.ecology

import inflation.Config
import inflation.ecology.GroupPlanner.PlanContext
import inflation.world.Hex
import inflation.world.Place
import inflation.world.get
import kotlin.math.min
import kotlin.math.roundToInt

// The doom trace, a [GroupWatch]: which bands run into hunger (doomList), and, run again from the same save, whether
// those bands had a better place within reach, or beyond it, week by week as they chose (doomTrace). See
// docs/tracing-groups.md for the method and scripts/doomtrace.py for reading what it prints.

/** The doom trace as a [GroupWatch]. */
internal object DoomWatch : GroupWatch {
    /** Each band's hunger deaths this year (by id), for doomList. */
    private val hungerDied = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    /** Each band's hunger deaths this week (by id), for the lean table ([lean]). */
    private val hungerWeek = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    /** Each band's fat at last week's end, for the lean table. */
    private val fatBefore = HashMap<Int, Float>()
    /** The lean table over the year: by kind of country and month (biome * 12 + month), the counts in [Lean]. */
    private val lean = HashMap<Int, IntArray>()
    /** The prey table: by kind of country and month, the residents' grown biomass (kg, summed over weeks) and of it the fat share times that. */
    private val prey = HashMap<Int, DoubleArray>()
    /** The year whose hunger deaths are being counted. */
    private var year = Long.MIN_VALUE
    override fun chose(planner: GroupPlanner, c: PlanContext, best: Place, byNet: Boolean) {
        if (c.g.id in DOOM_TRACE) planner.traceDoomed(c, best, byNet)
    }
    override fun outcome(g: HexGroup, o: Outcome) {
        if (!DOOM_LIST || g.band == null) return
        val n = o.causes.sumOf { if (it.cause == DeathCause.HUNGER) it.n else 0 }
        if (n > 0) { hungerDied.merge(g.id, n, Int::plus); hungerWeek.merge(g.id, n, Int::plus) }
    }
    override fun weekEnd(gs: HexGroups, date: inflation.world.Date) {
        if (!DOOM_LIST) return
        val groups = gs.groups
        for (g in groups) if (!g.alive && g.mergedSize < 0) listDoomedEnd(g, date, hungerDied[g.id] ?: 0)
        // (The lean table: every band this week, by the kind of country it is in and the month.)
        for (g in groups) { val b = g.band ?: continue
            val died = hungerWeek[g.id] ?: 0
            if (!g.alive && died == 0) continue
            val row = lean.getOrPut(gs.biomeOfPlace[g.place] * 12 + date.month) { IntArray(Lean.entries.size) }
            row[Lean.BAND_WEEKS.ordinal]++; row[Lean.PEOPLE_WEEKS.ordinal] += g.size
            val was = fatBefore[g.id]
            if (was != null && g.fat < was - 0.05f) row[Lean.FAT_DRAWN.ordinal]++
            if (g.fed < 0.9f) row[Lean.HUNGRY.ordinal]++
            if (b.movedCamp) row[Lean.MOVED.ordinal]++
            row[Lean.HUNGER_DEATHS.ordinal] += died
            // (What it held and aimed at, days of its people's need, tenths; its foraging's yield and spare days, hundredths.)
            val need = g.dailyNeedKg.coerceAtLeast(1e-3f)
            row[Lean.FAT_TENTHS.ordinal] += (10f * g.fat).roundToInt()
            row[Lean.LARDER_TENTHS.ordinal] += (10f * min(LEAN_LARDER_CAP, g.larder / need)).roundToInt()
            row[Lean.AIM_TENTHS.ordinal] += (10f * b.storeAim).roundToInt()
            row[Lean.DURABLE_TENTHS.ordinal] += (10f * min(LEAN_LARDER_CAP, (g.larder - perishablesHeld(g)) / need)).roundToInt()
            val y = b.recentYield(date, gs.days)
            if (!y.isNaN()) { row[Lean.YIELD_HUNDREDTHS.ordinal] += (100f * y).roundToInt(); row[Lean.YIELD_WEEKS.ordinal]++ }
            row[Lean.SPARE_HUNDREDTHS.ordinal] += (100f * b.spareMean).roundToInt()
            if (g.alive) fatBefore[g.id] = g.fat else fatBefore.remove(g.id)
        }
        hungerWeek.clear()
        // (The prey table: how fat the grown residents are, by kind of country and month, weighed by their biomass: the fat a
        // hunter's kill carries, [fatOfKill], as the country's game stands.)
        val fauna = gs.fauna
        for (p in 0 until fauna.n) { val place = Place(p)
            val row = prey.getOrPut(gs.biomeOfPlace[p] * 12 + date.month) { DoubleArray(2) }
            for (k in fauna.kinds.indices) { val kg = fauna.adults[k][p] * fauna.kinds[k].massKg
                if (!(kg > 0f)) continue
                row[0] += kg.toDouble(); row[1] += (kg * fauna.fatShare(k, place) / fauna.kinds[k].mostFatShare.coerceAtLeast(1e-6f)).toDouble() }
        }
        if (date.year != year) {
            if (year != Long.MIN_VALUE) { listDoomedYear(groups, date, hungerDied); printLean(date, lean); printPrey(date, prey); lean.clear(); prey.clear() }
            year = date.year
        }
    }
}

/** What the lean table counts: band-weeks, people-weeks; band-weeks drawing on fat, fed under 0.9, moving camp; hunger deaths. */
private enum class Lean(val label: String) {
    BAND_WEEKS("band-weeks"), PEOPLE_WEEKS("people-weeks"), FAT_DRAWN("fat drawn"), HUNGRY("fed<0.9"), MOVED("moved"),
    HUNGER_DEATHS("hunger deaths"),
    /** Fat, larder (at most [LEAN_LARDER_CAP]) and store aim ([Band.storeAim]), days of need, tenths summed over band-weeks. */
    FAT_TENTHS("fat d/10"), LARDER_TENTHS("larder d/10"), AIM_TENTHS("store aim d/10"),
    /** Its foraging's yield over the last 4 weeks ([Band.recentYield]), hundredths summed, and the band-weeks it was seen. */
    YIELD_HUNDREDTHS("yield/100"), YIELD_WEEKS("yield weeks"),
    /** Its share of hands' days spare over about a season ([Band.spareMean]), hundredths summed. */
    SPARE_HUNDREDTHS("spare/100"),
    /** Of the larder, what keeps beyond days ([PERISHABLE]: fresh meat and fruit left out), at most [LEAN_LARDER_CAP], days of need, tenths. */
    DURABLE_TENTHS("durable d/10")
}

/** The larder the lean table counts at most, days: a seed store of years would swamp a kind of country's mean. */
private const val LEAN_LARDER_CAP = 120f

/** doomList: at a year's end, the lean table, a line for each kind of country and month with bands in it. */
private fun printLean(date: inflation.world.Date, lean: Map<Int, IntArray>) {
    for (k in lean.keys.sorted()) { val row = lean.getValue(k)
        System.err.println("doomLean year to %s: %s, month %d: %s".format(date, inflation.world.Biome.entries[k / 12].label, k % 12 + 1,
            Lean.entries.joinToString(", ") { "${it.label} ${row[it.ordinal]}" }))
    }
}

/** doomList: at a year's end, the prey table, a line for each kind of country: its grown residents' fat reserves month by month, biomass-weighted, % of full. */
private fun printPrey(date: inflation.world.Date, prey: Map<Int, DoubleArray>) {
    for (b in prey.keys.map { it / 12 }.distinct().sorted()) {
        System.err.println("doomPrey year to %s: %s: fat reserves %% of full by month: %s".format(date, inflation.world.Biome.entries[b].label,
            (0 until 12).joinToString(" ") { m -> val r = prey[b * 12 + m]; if (r == null || r[0] <= 0.0) "-" else "%.0f".format(100.0 * r[1] / r[0]) }))
    }
}

/** doomList=true: prints the bands that lost people to hunger, each year and as they die out ([listDoomed]). */
internal val DOOM_LIST = Config.flag(Config.Key.DOOM_LIST, false)
/** doomTrace=id,id,...: the bands followed week by week ([traceDoomed]). */
internal val DOOM_TRACE: Set<Int> = Config.string(Config.Key.DOOM_TRACE)?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet() ?: emptySet()
/** Hunger deaths over a year at which a band is listed. */
internal const val DOOM_LEAST = 2
/** doomList: of the bands with no hunger deaths in a year, those whose id this divides are listed as a control. */
internal const val DOOM_CONTROL_EVERY = 40
/** How far the trace looks for better country: this many times the band's reach this week. */
internal const val DOOM_WIDE = 4f
/** A place this many times as rich as the band's own, as its mover sees food ([knownGame]), counts as better. */
internal const val DOOM_BETTER = 1.25f

/**
 * doomTrace: for followed band [g] as it chose [best] this week ([c]): where it is and how it fares; its own place's food,
 * as its mover sees it ([knownGame]); the place it chose; the best place within its reach this week; and, by the least
 * effort out to [DOOM_WIDE] times its reach, the best place there and the nearest one [DOOM_BETTER] times as rich as its own.
 * Reads only: draws nothing, changes nothing, so the run is the run without it.
 */
internal fun GroupPlanner.traceDoomed(c: PlanContext, best: Place, byNet: Boolean) {
    val g = c.g; val b = g.band ?: return; val kind = c.kind; val s = g.species
    fun value(h: Hex): Pair<Float, Place> {
        var v = Float.NEGATIVE_INFINITY; var at = Place.NONE
        for (pi in gs.hw.placesOf(h)) { val p = Place(pi); val x = knownGame(g, kind, p, h); if (x > v) { v = x; at = p } }
        return v to at
    }
    val here = value(c.here).first.coerceAtLeast(0f)
    val reached = c.buf.reached; val dist = c.buf.dist
    var inBest = Float.NEGATIVE_INFINITY; var inBestKm = 0f; var inBetter = 0
    for (i in 0 until reached.size) { val h = reached[i]; val v = value(h).first
        if (v > inBest) { inBest = v; inBestKm = dist[h] }
        if (v >= DOOM_BETTER * here && v > 0f) inBetter++ }
    // (The wider search: its own least-effort walk, with this week's going, apart from the shared one.)
    val effort = stepEffort(Catalogue.travel(s).ordinal, gs.hw.placeBand[g.place], c.weather)
    val wide = DOOM_WIDE * c.reach
    val d = HashMap<Int, Float>(); val q = java.util.PriorityQueue<Pair<Float, Hex>>(compareBy { it.first })
    d[c.here.i] = 0f; q += 0f to c.here
    var wBest = Float.NEGATIVE_INFINITY; var wBestKm = 0f; var wBestHex = Hex.NONE; var firstBetterKm = Float.NaN
    while (q.isNotEmpty()) {
        val (dd, h) = q.poll()
        if (dd > (d[h.i] ?: Float.MAX_VALUE)) continue
        val v = value(h).first
        if (v > wBest) { wBest = v; wBestKm = dd; wBestHex = h }
        if (firstBetterKm.isNaN() && v >= DOOM_BETTER * here && v > 0f) firstBetterKm = dd
        for (k in 0 until 6) {
            val nh = gs.hex.neighbour(h, k); if (nh.none) continue
            val nd = dd + effort[gs.hex.edge(h, k)]
            if (!nd.isFinite() || nd > wide) continue
            if (nd < (d[nh.i] ?: Float.MAX_VALUE)) { d[nh.i] = nd; q += nd to nh }
        }
    }
    val bestHex = gs.hw.placeHex[best]
    val chose = value(bestHex).first
    val crowdBest = if (!wBestHex.none) gs.crowdedBy(s, kinAround(g, kind, wBestHex, c.sensed)) else 0f
    val crowdChose = gs.crowdedBy(s, kinAround(g, kind, bestHex, c.sensed))
    val larder = g.larder / g.dailyNeedKg.coerceAtLeast(1e-3f)
    System.err.println(("doom #%d %s: %d people, fed %.2f, fat %.1f d, larder %.1f d, yield %.2f%s; reach %.0f (%d hexes): " +
        "here %.0f, chose %.0f%s (crowd %.2f, by %s, mind %s); best in reach %.0f at %.0f (%d places %.2fx); within %.0f: best %.0f at %.0f (crowd %.2f), first %.2fx at %s").format(
        g.id, c.date, g.size, g.fed, g.fat, larder, b.yieldNow, if (c.restless) ", restless" else "", c.reach, reached.size,
        here, chose, if (best == g.place) " (stays)" else " at %.0f".format(dist[bestHex]), crowdChose, if (byNet) "net" else "hand", b.mind.label, inBest, inBestKm, inBetter, DOOM_BETTER,
        wide, wBest, wBestKm, crowdBest, DOOM_BETTER, if (firstBetterKm.isNaN()) "none" else "%.0f".format(firstBetterKm)) +
        // (Where it is, what its camp has yielded lately, and what it knows of the months ahead: its own record of the food
        // it got in each month over the years ([Band.monthSupply], share of its need), the lean days it foresees, its
        // foreseen survival, and what it remembers of how fed it was here in this season ([PlaceMemory]).)
        run {
            val m = c.date.month
            val mi = g.memory.find(c.here, PlaceMemory.seasonOf(m))
            "; country %s, hex %d; yield 4 wk %.2f; record now %.2f, ahead %s; lean days %.0f; foreseen %.3f; remembered fed here %.2f".format(
                inflation.world.Biome.entries[gs.biomeOfPlace[g.place]].label, c.here.i, b.recentYield(c.date, gs.days), b.monthSupply[m],
                (1..3).joinToString("/") { "%.2f".format(b.monthSupply[(m + it) % 12]) }, b.leanDays, g.foreseen,
                if (mi >= 0) g.memory.fed[mi] else Float.NaN)
        })
}

/** doomList: band [g], dying out, printed if it lost [died] to hunger this year. */
private fun listDoomedEnd(g: HexGroup, date: inflation.world.Date, died: Int) {
    if (g.band == null) return
    if (died > 0) System.err.println("doomList #%d died out %s: %d hunger deaths this year, last %d people".format(g.id, date, died, g.size))
}

/** doomList: at a year's end, the living bands with [DOOM_LEAST] or more hunger deaths in it; and the count starts again. */
private fun listDoomedYear(groups: List<HexGroup>, date: inflation.world.Date, hungerDied: MutableMap<Int, Int>) {
    for (g in groups) { if (g.band == null || !g.alive) continue
        val died = hungerDied[g.id] ?: 0
        if (died >= DOOM_LEAST) System.err.println("doomList #%d year to %s: %d hunger deaths, now %d people".format(g.id, date, died, g.size))
        // (And a control: one band in [DOOM_CONTROL_EVERY] by its id, of those that lost no one to hunger, to trace against the doomed.)
        else if (died == 0 && g.id % DOOM_CONTROL_EVERY == 0) System.err.println("doomList control #%d year to %s: no hunger deaths, now %d people".format(g.id, date, g.size))
    }
    hungerDied.clear()
}
