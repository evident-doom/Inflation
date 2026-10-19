package inflation.tools

import inflation.Args
import inflation.ecology.Catalogue
import inflation.ecology.HexFauna
import inflation.ecology.HexSim
import inflation.ecology.clearlyWorse
import inflation.ecology.HexSnapshot
import inflation.ecology.gatherKm
import inflation.learning.LearnedModel
import inflation.run.TestWorlds
import inflation.world.Date
import inflation.world.WorldCache
import inflation.world.WorldConfig
import inflation.wrapMonth

/**
 * Why herds of the kinds that live both ways stand still where they formed: week by week, for each such kind, its
 * herds, how many stood still (same hex as last week), and of those how many have never gone beyond their gathering
 * ground ([gatherKm] of where they formed, so cannot settle), how many stand within two hexes of their season's place
 * (so are not restless for it), their feeding, the heads they took in and gave back; how many herds share one hex; and
 * at the end, for herds that formed during the run, how long they lived, how far they got, and how they ended.
 *
 * Usage: herd-standstill (--world NAME | --seed N [--width KM] [--height KM]) [--weeks N] [--kinds a,b]
 */
fun main(args: Array<String>) {
    val a = Args(args, "herd-standstill (--world NAME | --seed N [--width KM] [--height KM]) [--weeks N] [--kinds a,b]")
    val named = a.string("--world")?.let { TestWorlds.named(it) }
    var cfg = named?.config ?: WorldConfig(seed = 1)
    cfg = cfg.copy(seed = a.long("--seed", cfg.seed), widthKm = a.int("--width", cfg.widthKm), heightKm = a.int("--height", cfg.heightKm))
    val weeks = a.int("--weeks", 26)
    val only = a.string("--kinds")?.split(",")?.map { it.trim() }?.toSet()
    a.done()
    val sim = HexSim(WorldCache.load(cfg) { })
    named?.let { TestWorlds.check(it, sim); HexSnapshot.load(sim, it.state); println("carrying on ${it.state} at ${sim.weather.date}") }
    LearnedModel.fromConfig(sim.groups) { println(it) }
    val hex = sim.hex; val hw = sim.hw; val groups = sim.groups
    val kinds = Catalogue.gathering.filter { it in groups.kinds && (only == null || it.name in only) }

    class Track(val kind: String, val firstHex: Int, val firstWeek: Int) {
        var lastHex = firstHex; var weeks = 0; var maxKm = 0.0; var everFree = false; var joined = 0L; var settled = 0L; var end = "alive"
    }
    val tracks = HashMap<Int, Track>()
    val preexisting = groups.groups.filter { it.alive }.map { it.id }.toHashSet()
    val lastHex = HashMap<Int, Int>()
    for (g in groups.groups) if (g.alive) lastHex[g.id] = hw.placeHex[g.place.i]

    println("week kind: herds (new) heads | still: share, of them never left gathering ground, within 2 hexes of season's place, fed, joined/settled heads a herd, residents' arrive odds there | hexes with 5+ herds (coast share, river share), most herds in a hex")
    val fauna = sim.fauna; val land = sim.land
    val causes = listOf("hunger" to HexFauna.HZ_HUNGER, "cold" to HexFauna.HZ_COLD, "thirst" to HexFauna.HZ_THIRST, "disease" to HexFauna.HZ_DISEASE,
        "fire" to HexFauna.HZ_FIRE_A, "flood" to HexFauna.HZ_FLOOD_A, "snow" to HexFauna.HZ_SNOW_A, "hunted" to HexFauna.HZ_HUNTED)
    val out = FloatArray(HexFauna.HZ_COUNT)
    for (w in 0 until weeks) {
        // Before the week: the residents whose place is clearly deadly to stay at (as leaveAhead weighs it, by last week's
        // weather), their heads, their crowding against their kind's usual, and what would kill them.
        for (s in kinds) {
            val k = fauna.slot(s); val ad = fauna.adults[k]; val yo = fauna.young[k]
            var heads = 0.0; var all = 0.0; var coast = 0.0; var crowd = 0.0; val by = DoubleArray(causes.size)
            for (p in 0 until land.n) {
                val h = (ad[p] + yo[p]) * land.areaKm2[p].toDouble(); if (h <= 0.0) continue
                all += h
                val stay = fauna.stayOdds(k, p, sim.weather, out)
                if (!clearlyWorse(stay, 0f, s.maxGroupSize.toFloat())) continue
                heads += h; if (hw.coast[hw.placeHex[p]] || hw.riverM3s[hw.placeHex[p]] > 1f) coast += h; crowd += h * out[HexFauna.HZ_CROWD]
                for ((i, c) in causes.withIndex()) by[i] += h * out[c.second]
            }
            if (heads > 0.0) println("%d %s deadly to stay: %.0f of %.0f heads (%.3f), on coast or river %.2f, crowd %.2f of crowded | weekly deaths: %s".format(w, s.name, heads, all,
                heads / all, coast / heads, crowd / heads, causes.indices.joinToString(" ") { "%s %.4f".format(causes[it].first, by[it] / heads) }))
        }
        sim.step()
        val month = wrapMonth(sim.weather.date.month + if (sim.weather.date.dayOfMonth > Date.DAYS_PER_MONTH / 2) 1 else 0)
        val alive = groups.groups.filter { it.alive && it.species in kinds }
        val ids = alive.map { it.id }.toHashSet()
        for ((id, t) in tracks) if (t.end == "alive" && id !in ids) t.end = groups.merged.firstOrNull { it.id == id }?.let { if (it.mergedInto == inflation.ecology.SCATTERED) "scattered" else "merged" } ?: "gone"
        for (s in kinds) {
            val gs = alive.filter { it.species == s }
            if (gs.isEmpty()) { println("$w ${s.name}: none"); continue }
            val arrive = groups.arriveBy[s]
            var fresh = 0; var still = 0; var stuck = 0; var atGoal = 0; var fed = 0.0; var joined = 0.0; var settled = 0.0; var odds = 0.0; var oddsN = 0
            for (g in gs) {
                val h = hw.placeHex[g.place.i]
                val t = tracks[g.id] ?: if (g.id !in preexisting) Track(s.name, lastHex[g.id] ?: h, w).also { tracks[g.id] = it; fresh++ } else null
                t?.let { it.weeks++; it.maxKm = maxOf(it.maxKm, hex.distanceKm(it.firstHex, h)); if (g.formedAt < 0 || hex.distanceKm(h, hw.placeHex[g.formedAt]) > gatherKm(s)) it.everFree = true; it.joined += g.joined; it.settled += g.settled; it.lastHex = h }
                if (lastHex[g.id] == h) {
                    still++
                    if (g.formedAt >= 0 && hex.distanceKm(h, hw.placeHex[g.formedAt]) <= gatherKm(s)) stuck++
                    val goal = g.seasonPlace[month]
                    if (goal < 0 || hex.distanceKm(h, hw.placeHex[goal]) <= 2 * hex.hexKm) atGoal++
                    fed += g.fed; joined += g.joined; settled += g.settled
                    arrive?.get(g.place.i)?.takeIf { it.isFinite() }?.let { odds += it; oddsN++ }
                }
                lastHex[g.id] = h
            }
            val byHex = gs.groupingBy { hw.placeHex[it.place.i] }.eachCount()
            val crowded = byHex.filter { it.value >= 5 }.keys
            println("%d %s: %d (%d) %d | still %.2f, never left %.2f, near goal %.2f, fed %.2f, joined %.0f settled %.0f, arrive odds %.4f | %d hexes (coast %.2f, river %.2f), most %d".format(
                w, s.name, gs.size, fresh, gs.sumOf { it.size },
                still.toDouble() / gs.size, stuck.toDouble() / still.coerceAtLeast(1), atGoal.toDouble() / still.coerceAtLeast(1),
                fed / still.coerceAtLeast(1), joined / still.coerceAtLeast(1), settled / still.coerceAtLeast(1), odds / oddsN.coerceAtLeast(1),
                crowded.size, crowded.count { hw.coast[it] }.toDouble() / crowded.size.coerceAtLeast(1),
                crowded.count { hw.riverM3s[it] > 1f }.toDouble() / crowded.size.coerceAtLeast(1), byHex.values.maxOrNull() ?: 0))
        }
    }
    println("== herds formed during the run, by kind: count | median weeks lived, median furthest km from where formed | share never beyond gathering ground | ended alive/scattered/merged/gone | heads taken in, given back")
    for (s in kinds) {
        val ts = tracks.values.filter { it.kind == s.name }
        if (ts.isEmpty()) continue
        fun med(xs: List<Double>) = xs.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
        val ends = ts.groupingBy { it.end }.eachCount()
        println("%s: %d | %.0f weeks, %.0f km | never left %.2f | %d/%d/%d/%d | in %d, out %d".format(s.name, ts.size, med(ts.map { it.weeks.toDouble() }),
            med(ts.map { it.maxKm }), ts.count { !it.everFree }.toDouble() / ts.size,
            ends["alive"] ?: 0, ends["scattered"] ?: 0, ends["merged"] ?: 0, ends["gone"] ?: 0, ts.sumOf { it.joined }, ts.sumOf { it.settled }))
    }
}
