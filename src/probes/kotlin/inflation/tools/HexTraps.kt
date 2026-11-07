package inflation.tools

import inflation.Args
import inflation.Config
import inflation.Tuning
import inflation.ecology.COVER_GOING
import inflation.ecology.Catalogue
import inflation.ecology.HexSim
import inflation.ecology.HexSnapshot
import inflation.ecology.SHIFT_DAYS
import inflation.ecology.WEAK_SLOW
import inflation.ecology.stepEffort
import inflation.ecology.weakShare
import inflation.learning.LearnedModel
import inflation.run.TestWorlds
import inflation.world.Hex
import inflation.world.HexMap
import inflation.world.WorldCache
import inflation.world.WorldConfig
import inflation.world.get

/**
 * Whether people can be shut in by the going: a band moves camp only as far as its week's reach, so a border that costs
 * more than that reach is one it never crosses, and ground ringed by such borders is ground it never leaves (survival
 * thread 2026-11-07: of the bands the storms still killed, 4 could reach only their camp's hex, its cheapest border
 * costing 74-77 km against a reach of 42-46). Asked by Olli: are these single hexes, or larger areas?
 *
 * 1. The map's own going for people's way of travelling ([HexMap.crossings], no snow, no worn paths): each land hex's
 *    cheapest border, in km of effort, by share of land; and for each reach, the land hexes whose every border costs more.
 * 2. The areas closed at each reach: land hexes joined by borders that cost no more than it, by the size of the area.
 * 3. The bands, at the start and every [--every] weeks for [--weeks] weeks: with this week's going as bands weigh it
 *    (snow, worn paths: [stepEffort]) and each band's own reach fleeing (as [GroupPlanner.plan] has it, restless: the
 *    weak and the cover slowing it), how many can leave their camp's hex at all, and the size of the area they could
 *    ever reach, week by week, at that reach.
 *
 * Usage: hex-traps (--world NAME | --seed N [--width KM] [--height KM]) [--weeks N] [--every N] [--reach a,b,...]
 */
fun main(args: Array<String>) {
    val a = Args(args, "hex-traps (--world NAME | --seed N [--width KM] [--height KM]) [--weeks N] [--every N] [--reach a,b,...]")
    val named = a.string("--world")?.let { TestWorlds.named(it) }
    var cfg = named?.config ?: WorldConfig(seed = 1)
    cfg = cfg.copy(seed = a.long("--seed", cfg.seed), widthKm = a.int("--width", cfg.widthKm), heightKm = a.int("--height", cfg.heightKm))
    val weeks = a.int("--weeks", 52)
    val every = a.int("--every", 4).coerceAtLeast(1)
    val reaches = (a.string("--reach") ?: "20,30,40,45,50,60").split(",").map { it.trim().toFloat() }
    a.done()
    val sim = HexSim(WorldCache.load(cfg) { }, tuning = Tuning(read = { Config.tuning(it) }))
    named?.let { TestWorlds.check(it, sim); HexSnapshot.load(sim, it.state); println("carrying on ${it.state} at ${sim.weather.date}") }
    LearnedModel.fromConfig(sim.groups) { println(it) }
    val hex = sim.hex; val gs = sim.groups
    val human = Catalogue.HUMAN
    val travel = Catalogue.travel(human)
    println("people travel as $travel; hexes ${hex.hexKm} km across; a week's reach fleeing is up to %.0f km (%.0f km a day for %.0f days)".format(
        human.maxDailyTravelKm * SHIFT_DAYS, human.maxDailyTravelKm, SHIFT_DAYS))
    val land = hex.all.filter { hex.heart[it] >= 0 }
    val cross = hex.crossings[travel.ordinal]
    fun cheapest(h: Hex, effort: FloatArray): Float { var m = Float.POSITIVE_INFINITY
        for (k in 0 until 6) { val n = hex.neighbour(h, k); if (n.none || hex.heart[n] < 0) continue; m = minOf(m, effort[hex.edge(h, k)]) }
        return m }

    // 1. Each land hex's cheapest border.
    println("\n1. The map's going (no snow, no worn paths): each land hex's cheapest border, km of effort")
    val least = land.map { cheapest(it, cross) }.sorted()
    fun pct(p: Double) = least[((least.size - 1) * p).toInt()]
    println("  land hexes %d; cheapest border: median %.1f, 90%% %.1f, 99%% %.1f, 99.9%% %.1f, max %.1f; none at all (no way out) %d".format(
        least.size, pct(0.5), pct(0.9), pct(0.99), pct(0.999), least.last(), least.count { !it.isFinite() }))
    for (r in reaches) println("  reach %3.0f km: land hexes whose every border costs more: %d (%.2f%%)".format(r, least.count { it > r }, 100.0 * least.count { it > r } / least.size))

    // 2. The areas closed at each reach.
    fun areas(r: Float, effort: FloatArray): IntArray {
        val comp = IntArray(hex.size) { -1 }; var next = 0
        val stack = ArrayDeque<Hex>()
        for (s in land) { if (comp[s.i] >= 0) continue
            comp[s.i] = next; stack.addLast(s)
            while (stack.isNotEmpty()) { val h = stack.removeLast()
                for (k in 0 until 6) { val n = hex.neighbour(h, k); if (n.none || hex.heart[n] < 0 || comp[n.i] >= 0) continue
                    if (effort[hex.edge(h, k)] <= r) { comp[n.i] = next; stack.addLast(n) } } }
            next++ }
        return comp
    }
    val bins = intArrayOf(1, 2, 5, 20, 100, 1000, Int.MAX_VALUE)
    fun binName(i: Int) = when (i) { 0 -> "1"; else -> if (bins[i] == Int.MAX_VALUE) ">${bins[i - 1]}" else "${bins[i - 1] + 1}-${bins[i]}" }
    println("\n2. Areas closed at each reach (land hexes joined by borders costing no more), by size in hexes: areas / land hexes in them")
    for (r in reaches) {
        val comp = areas(r, cross)
        val size = IntArray(comp.max() + 1); for (h in land) size[comp[h.i]]++
        val nA = IntArray(bins.size); val nH = IntArray(bins.size)
        for (s in size) { if (s == 0) continue; val b = bins.indexOfFirst { s <= it }; nA[b]++; nH[b] += s }
        println("  reach %3.0f km: ".format(r) + bins.indices.joinToString(", ") { "${binName(it)}: ${nA[it]}/${nH[it]}" } +
            "; largest %d hexes".format(size.max()))
    }

    // 3. The bands, with the going as they weigh it, week by week.
    println("\n3. Bands with this week's going (snow, worn paths) and each band's own reach fleeing")
    println("  date | bands | reach km median (min..max) | camp hex only: bands (people) | area it could ever reach, hexes: " +
        bins.indices.joinToString(" ") { binName(it) })
    for (w in 0..weeks) {
        if (w > 0) sim.step()
        if (w % every != 0) continue
        gs.planner.stepEffort.clear()
        val bands = gs.groups.filter { it.alive && it.band != null }
        val reachOf = FloatArray(bands.size); var shut = 0; var shutPeople = 0
        val nB = IntArray(bins.size)
        // (One closed-area map per elevation band and reach, rounded to the km, shared by the bands that weigh it.)
        val areaCache = HashMap<Long, Pair<IntArray, IntArray>>()
        for ((i, g) in bands.withIndex()) {
            val reach = human.maxDailyTravelKm * SHIFT_DAYS * (1f - WEAK_SLOW * weakShare(g)) / (1f + COVER_GOING * gs.land.plants.cover(g.place))
            reachOf[i] = reach
            val band = gs.hw.placeBand[g.place]
            val effort = gs.planner.stepEffort(travel.ordinal, band, sim.weather)
            val here = gs.hw.placeHex[g.place]
            if (cheapest(here, effort) > reach) { shut++; shutPeople += g.size }
            val key = band.toLong() * 100_000 + reach.toInt()
            val (comp, size) = areaCache.getOrPut(key) { val c = areas(reach.toInt().toFloat(), effort); val s = IntArray(c.max() + 1); for (h in land) s[c[h.i]]++; c to s }
            val s = size[comp[here.i]]
            nB[bins.indexOfFirst { s <= it }]++
        }
        val sorted = reachOf.sorted()
        println("  %s | %d | %.0f (%.0f..%.0f) | %d (%d) | %s".format(sim.weather.date, bands.size,
            if (sorted.isEmpty()) 0f else sorted[sorted.size / 2], sorted.firstOrNull() ?: 0f, sorted.lastOrNull() ?: 0f, shut, shutPeople,
            nB.joinToString(" ")))
    }
}
