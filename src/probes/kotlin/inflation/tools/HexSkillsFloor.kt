package inflation.tools

import inflation.Args
import inflation.ecology.Catalogue
import inflation.ecology.HexGroup
import inflation.ecology.HexSim
import inflation.ecology.Skill
import inflation.run.Nominal
import inflation.tools.fauna.FaunaCheckOptions
import inflation.world.Biome
import inflation.world.Hex
import inflation.world.Place
import java.io.File

/**
 * The skills floor: how soon unprepared people die. On a settled test world, with the learned nets the config names,
 * the bands are stripped of their skills, tools, materials, stores, caches, herds, fields and memory of the land, as
 * unprepared people dropped into the wild, and followed week by week. Three arms, each from the same save, weather
 * and start, one after another (one world in memory at a time):
 *  - all: every band stripped at once (the floor the limits are for: [Nominal.floor], by the half of the year it falls in);
 *  - drop: one band in [--drop] stripped among neighbours as they are (report only: neighbours share with them);
 *  - usual: no band stripped (the control: the world itself must not be what kills).
 * Only the bands stripped (in the usual arm, the bands that would have been) are counted, with the bands that split
 * from them (a split keeps its parent's tag). Deaths by cause are the whole world's people (in the drop arm mostly the
 * unstripped bands').
 *
 * --start-week W lets the world run W weeks as it is before the stripping, so that it falls in another season (the
 * test worlds are saved at the end of winter). Weeks are the model's 6-day steps.
 *
 * Usage: hex-skills-floor --world NAME [--replicate R] [--start-week W] [--weeks N] [--drop N] [--arms all,drop,usual]
 *        [--out PREFIX]
 * With --out, PREFIX-alive.csv (arm, week, biome, alive, start) and PREFIX-deaths.csv (arm, week, cause, heads).
 */
fun main(args: Array<String>) {
    val usage = "hex-skills-floor --world NAME [--replicate R] [--start-week W] [--weeks N] [--drop N] [--arms all,drop,usual] [--out PREFIX]"
    val a = Args(args, usage)
    val world = a.string("--world") ?: error("--world NAME (a settled test world, e.g. m5-1)")
    val replicate = a.long("--replicate", 1L)
    val startWeek = a.int("--start-week", 0)
    val weeks = a.int("--weeks", 52)
    val drop = a.int("--drop", 10)
    val arms = (a.string("--arms") ?: "all,drop,usual").split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val out = a.string("--out")
    a.done()
    require(arms.all { it in ARMS }) { "--arms: some of $ARMS" }
    require(drop >= 2) { "--drop takes 2 or more" }

    val results = LinkedHashMap<String, Arm>()
    for (arm in arms) {
        val run = FaunaCheckOptions.parse(arrayOf("--world", world, "--replicate", "$replicate")).start()
        repeat(startWeek) { run.step() }
        results[arm] = follow(run, arm, drop, weeks)
        println("arm $arm done, stripping at ${results.getValue(arm).date}")
    }

    val marks = listOf(1, 2, 3, 4, 6, 8, 13, 26, 52).filter { it <= weeks }
    println("\nskills floor, world $world, replicate $replicate, start week $startWeek, $weeks weeks; share of the counted people alive")
    for ((arm, r) in results) {
        val counted = when (arm) { "all" -> "every band stripped"; "drop" -> "1 band in $drop stripped, those counted"; else -> "no band stripped (the bands the all arm strips)" }
        println("\n[$arm] $counted, from ${r.date}")
        println("%-24s %7s  ".format("country", "people") + marks.joinToString(" ") { "%5s".format("wk$it") })
        for (b in Biome.entries.indices.sortedByDescending { r.start[it] }) {
            if (r.start[b] < 1.0) continue
            println("%-24s %7.0f  %s".format(Biome.entries[b].name.lowercase().replace('_', ' '), r.start[b],
                marks.joinToString(" ") { w -> "%4.0f%%".format(100.0 * r.alive[w][b] / r.start[b]) }))
        }
        println("%-24s %7.0f  %s".format("all", r.start.sum(), marks.joinToString(" ") { w -> "%4.0f%%".format(100.0 * r.share(w)) }))
        println("%-24s %7s  %s".format("how well fed", "", marks.joinToString(" ") { w -> "%4.0f%%".format(100.0 * r.fed[w]) }))
        println("%-24s %7s  %s".format("bands left", "${r.bands0}", marks.joinToString(" ") { w -> "%5d".format(r.bands[w]) }))
        println("deaths of people by cause, whole world, weeks 1-4 | 5-13 | 14-52")
        for (c in r.causes()) {
            fun sum(range: IntRange) = range.filter { it <= weeks }.sumOf { r.deaths[it][c] ?: 0 }
            println("  %-22s %7d | %7d | %7d".format(c, sum(1..4), sum(5..13), sum(14..52)))
        }
    }
    results["all"]?.let { all ->
        val usual = results["usual"]
        println()
        println(Nominal.floor(DoubleArray(weeks + 1) { all.share(it) }, usual?.let { u -> DoubleArray(weeks + 1) { u.share(it) } },
            "skills floor, $world r$replicate start week $startWeek" + if (Nominal.coldHalf(all.month)) ", cold half" else ", warm half",
            Nominal.coldHalf(all.month)).report())
    }

    out?.let { p ->
        File("$p-alive.csv").absoluteFile.parentFile?.mkdirs()
        File("$p-alive.csv").printWriter().use { f ->
            f.println("arm,week,biome,alive,start,fed,bands")
            for ((arm, r) in results) for (w in 0..weeks) for (b in Biome.entries.indices) if (r.start[b] >= 1.0)
                f.println("$arm,$w,${Biome.entries[b].name.lowercase()},${r.alive[w][b]},${r.start[b]},${r.fed[w]},${r.bands[w]}")
        }
        File("$p-deaths.csv").printWriter().use { f ->
            f.println("arm,week,cause,heads")
            for ((arm, r) in results) for (w in 1..weeks) for ((c, n) in r.deaths[w]) f.println("$arm,$w,$c,$n")
        }
    }
}

private val ARMS = setOf("all", "drop", "usual")

/** One arm's people week by week: alive by kind of country (index 0 the start), how well fed, bands left, the world's deaths by cause. */
private class Arm(val date: String, val month: Int, val start: DoubleArray, weeks: Int, val bands0: Int) {
    val alive = Array(weeks + 1) { DoubleArray(start.size) }.also { start.copyInto(it[0]) }
    val fed = DoubleArray(weeks + 1)
    val bands = IntArray(weeks + 1).also { it[0] = bands0 }
    val deaths = Array(weeks + 1) { HashMap<String, Int>() }
    fun share(w: Int) = alive[w].sum() / start.sum().coerceAtLeast(1.0)
    fun causes() = deaths.flatMap { it.keys }.toSortedSet()
}

/** Strips the bands [arm] calls for in [run] (tagging the counted ones by their kind of country) and follows them [weeks] weeks. */
private fun follow(run: HexSim, arm: String, drop: Int, weeks: Int): Arm {
    val people = Catalogue.HUMAN
    val prefix = "${people.name}:"
    val biomeOf = run.hw.placeBiome
    val start = DoubleArray(Biome.entries.size)
    var seen = 0; var counted = 0
    for (g in run.groups.groups) {
        if (!g.alive || g.species != people || g.band == null) continue
        val take = arm != "drop" || seen++ % drop == 0
        if (!take) continue
        g.tag = biomeOf[g.place.i]; start[g.tag] += g.size.toDouble(); counted++
        if (arm != "usual") strip(g)
    }
    val r = Arm(run.weather.date.toString(), run.weather.date.month, start, weeks, counted)
    r.fed[0] = run.groups.groups.filter { it.alive && it.tag >= 0 && it.species == people }.let { c ->
        c.sumOf { it.fed.toDouble() * it.size } / c.sumOf { it.size }.coerceAtLeast(1) }
    for (w in 1..weeks) {
        run.step()
        var heads = 0.0; var fedSum = 0.0; var n = 0
        for (g in run.groups.groups) if (g.alive && g.species == people && g.tag >= 0) {
            r.alive[w][g.tag] += g.size.toDouble(); heads += g.size; fedSum += g.fed.toDouble() * g.size; n++
        }
        r.fed[w] = fedSum / heads.coerceAtLeast(1.0); r.bands[w] = n
        for ((k, v) in run.groups.stats.deaths) if (k.text.startsWith(prefix)) r.deaths[w][k.text.removePrefix(prefix)] = v
    }
    return r
}

/** [g] as unprepared people: no skills, tools, materials, stores, caches, herds, fields, house or memory of the land. */
private fun strip(g: HexGroup) {
    val b = g.band ?: return
    b.skills.fill(0f)
    for (s in Skill.entries) b.knowsSkill[s] = s.known
    b.plants.fill(0f); b.fire = 0f; b.shelter = 0f; b.known = LongArray(0)
    b.kit.fill(0f); b.knows.fill(false); b.goods.fill(0f); b.cache.fill(0f); b.cachePlace = Place.NONE
    b.house = 0f; b.houseHex = Hex.NONE; b.palisade = 0f; b.ornaments = 0f; b.exotic = 0f
    b.seedCorn = 0f; b.seedKept = 0f; b.sownKg = 0f; b.sownPlace = Place.NONE
    b.herds.herd.fill(0f); b.herds.herdTame.fill(0f); b.herds.herdYoung.fill(0f); b.herds.herdPet.fill(0f)
    b.knownHomes.clear(); b.homeSeen.clear(); b.homeOff.clear(); b.seasonPlace.fill(-1)
    g.stores.fill(0f)
    g.memory.hex.fill(Hex.NONE)
}
