package inflation.tools

import inflation.Args
import inflation.Config
import inflation.Tuning
import inflation.ecology.Catalogue
import inflation.ecology.HexSim
import inflation.ecology.HexSnapshot
import inflation.ecology.boatWater
import inflation.learning.LearnedModel
import inflation.run.TestWorlds
import inflation.world.Hex
import inflation.world.HexMap
import inflation.world.WorldCache
import inflation.world.WorldConfig
import inflation.world.get
import java.util.PriorityQueue

/**
 * Which water parts the bands (boats thread, Olli 2026-11-21: "find which water actually separates the clusters"). The census's
 * clusters of bands join bands by straight-line distance, which no crossing can change; this probe joins them by the going.
 *
 * On a settled test world's saved state, bands are joined when one can reach the other within [--km] km of effort over the
 * map's going for people ([HexMap.crossings], no snow, no worn paths), and the clusters are counted under rules that open more
 * and more of the water, each on top of the one before:
 *  - straight line (the census's clusters, for comparison);
 *  - the going as main has it (deep rivers closed, people swim the straits as walkers do);
 *  - rivers ferried (a border closed only by its river opens at its ground's going and 2 km);
 *  - open water up to 10 km (sea and lake links at 1.5 km of effort a km);
 *  - open water up to 20 km (every link the map keeps: one or two hexes of water);
 *  - closed ground opened too (cliffs and the like, at 20 km a border), to tell water from rock.
 * For each rule: clusters, the largest's share of bands, bands alone; and how many pairs of bands within [--km] in a straight
 * line are joined. Also how many land borders each kind of water closes, and how many bands camp by boat water.
 *
 * Usage: hex-water-splits --world NAME [--km N]
 */
fun main(args: Array<String>) {
    val a = Args(args, "hex-water-splits --world NAME [--km N]")
    val named = TestWorlds.named(a.string("--world") ?: error("--world NAME"))
    val km = a.float("--km", 60f)
    a.done()
    val cfg: WorldConfig = named.config
    val sim = HexSim(WorldCache.load(cfg) { }, tuning = Tuning(read = { Config.tuning(it) }))
    TestWorlds.check(named, sim); HexSnapshot.load(sim, named.state); println("carrying on ${named.state} at ${sim.weather.date}")
    LearnedModel.fromConfig(sim.groups) { println(it) }
    val hex = sim.hex; val gs = sim.groups
    val travel = Catalogue.travel(Catalogue.HUMAN).ordinal
    val cross = hex.crossings[travel]; val ground = hex.ground[travel]
    val swim = Catalogue.swimKm(Catalogue.HUMAN)
    val bands = gs.groups.filter { it.alive && it.band != null }
    val at = bands.map { gs.hw.placeHex[it.place] }
    println("people travel as ${HexMap.Travel.entries[travel]}, swim %.0f km; hexes %.0f km; %d bands; joined within %.0f km".format(swim, hex.hexKm, bands.size, km))

    // The land borders closed for people, by why.
    run {
        var all = 0; var river = 0; var rock = 0
        for (h in hex.all) { if (hex.heart[h] < 0) continue
            for (d in 0 until 3) { val n = hex.neighbour(h, d); if (n.none || hex.heart[n] < 0) continue
                all++; val e = hex.edge(h, d)
                if (cross[e].isFinite()) continue
                if (ground[e].isFinite()) river++ else rock++ } }
        val links = hex.all.sumOf { gs.seaLinks[it].size }
        val within10 = hex.all.sumOf { h -> gs.seaGaps[h].count { it <= 10f } }
        println("land borders: %d; closed by a river only %d (%.1f%%), by the ground %d (%.1f%%); straits (both ways) %d, of them up to 10 km %d, up to %.0f km (swum) %d".format(
            all, river, 100.0 * river / all, rock, 100.0 * rock / all, links, within10, swim, hex.all.sumOf { h -> gs.seaGaps[h].count { it <= swim } }))
        println("bands camped by boat water (a lake or the sea beside, or a river of 5 m3/s and more): %d of %d".format(at.count { gs.boatWater(it) }, bands.size))
    }

    class Rule(val name: String, val rivers: Boolean, val gapKm: Float, val rock: Boolean)
    val rules = listOf(
        Rule("going as main", false, swim, false),
        Rule("+ rivers ferried", true, swim, false),
        Rule("+ open water to 10 km", true, 10f, false),
        Rule("+ open water to 20 km", true, 20f, false),
        Rule("+ closed ground opened", true, 20f, true))
    val byHex = HashMap<Int, MutableList<Int>>()
    for ((i, h) in at.withIndex()) byHex.getOrPut(h.i) { ArrayList() } += i
    val n = bands.size
    val near = ArrayList<Pair<Int, Int>>()
    for (i in 0 until n) for (j in i + 1 until n) if (hex.distanceKm(at[i], at[j]) <= km) near += i to j

    fun report(name: String, joined: (Int, Int) -> Boolean, links: List<Pair<Int, Int>>) {
        val parent = IntArray(n) { it }
        fun root(i: Int): Int { var r = i; while (parent[r] != r) r = parent[r]; var j = i; while (parent[j] != r) { val x = parent[j]; parent[j] = r; j = x }; return r }
        for ((i, j) in links) { val ri = root(i); val rj = root(j); if (ri != rj) parent[ri] = rj }
        val count = IntArray(n); for (i in 0 until n) count[root(i)]++
        val sizes = count.filter { it > 0 }.sortedDescending()
        val pairs = near.count { (i, j) -> joined(i, j) }
        println("  %-24s %4d clusters, largest %3.0f%% of bands, next %s, %d alone; pairs within %.0f km straight joined: %d of %d (%.0f%%)".format(name,
            sizes.size, 100.0 * sizes[0] / n, sizes.drop(1).take(4).joinToString("/"), sizes.count { it == 1 }, km, pairs, near.size, 100.0 * pairs / near.size.coerceAtLeast(1)))
    }

    println("\nclusters of bands joined within %.0f km:".format(km))
    report("straight line", { _, _ -> true }, near)
    for (r in rules) {
        // Each band's reach under the rule: the bands it reaches within km.
        val reached = Array(n) { HashSet<Int>() }
        val dist = HashMap<Int, Float>()
        for (i in 0 until n) {
            dist.clear()
            val q = PriorityQueue<Pair<Float, Int>>(compareBy({ it.first }, { it.second }))
            dist[at[i].i] = 0f; q += 0f to at[i].i
            fun offer(to: Int, d: Float) { if (d <= km && d < (dist[to] ?: Float.MAX_VALUE)) { dist[to] = d; q += d to to } }
            while (q.isNotEmpty()) {
                val (d, hi) = q.poll(); if (d > (dist[hi] ?: Float.MAX_VALUE)) continue
                val h = Hex(hi)
                byHex[hi]?.let { reached[i].addAll(it) }
                for (k in 0 until 6) {
                    val nb = hex.neighbour(h, k); if (nb.none || hex.heart[nb] < 0) continue
                    val e = hex.edge(h, k)
                    var c = cross[e]
                    if (!c.isFinite() && r.rivers && ground[e].isFinite() && hex.riverDepthM[e] > 0f) c = ground[e] + 2f
                    if (!c.isFinite() && r.rock) c = 20f
                    if (c.isFinite()) offer(nb.i, d + c)
                }
                val links = gs.seaLinks[h]; val gaps = gs.seaGaps[h]
                for (x in 0 until links.size) {
                    val g = gaps[x]
                    if (g <= swim) offer(links[x].i, d + g * 10f)
                    else if (g <= r.gapKm) offer(links[x].i, d + g * 1.5f)
                }
            }
        }
        val links = ArrayList<Pair<Int, Int>>()
        for (i in 0 until n) for (j in reached[i]) if (j != i) links += i to j
        report(r.name, { i, j -> j in reached[i] || i in reached[j] }, links)
    }
}
