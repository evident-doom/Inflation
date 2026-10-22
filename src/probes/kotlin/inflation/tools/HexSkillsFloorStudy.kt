package inflation.tools

import inflation.Args
import inflation.ecology.Catalogue
import inflation.ecology.Hex
import inflation.ecology.Met
import inflation.ecology.Skill
import inflation.ecology.Tool
import inflation.ecology.contains
import inflation.ecology.get
import inflation.ecology.set
import inflation.tools.fauna.FaunaCheckOptions
import inflation.world.Biome
import java.io.File

/**
 * The skills floor on a settled full-size test world: how long people last who start with nothing, by the kind of
 * country and the time of year they start in, and what kills them. A DRAFT, not yet compiled: it extends
 * HexSkillsFloor (same stripping, taken from it verbatim) in four ways:
 *  - starts from a settled test world (--world storm-1) with the weather drawn afresh (--replicate R), not a fresh
 *    600 km world;
 *  - --start-week W lets the bands live as they are for W weeks first, so the stripping falls in another season
 *    (the settled state is saved at the end of winter, Date.START_DAY);
 *  - --adults N trims every band to at most N adults (young in proportion), for the group-size question;
 *  - counts the people's deaths by cause each week (groups.stats.deaths) and every person alive, tagged or not
 *    (a band that splits sends its new half out untagged, which the old probe counted as dead).
 * One arm per run (--arm naked | usual), so a run holds one world in memory; the usual arm is the same world, season
 * and weather with the bands as they are.
 *
 * Output: a table to stdout, and with --out PREFIX the files PREFIX-weekly.csv (week, biome, alive, fed), PREFIX-deaths.csv (week, cause,
 * heads, whole population) and PREFIX-bands.csv (each band's start: biome, size, and the week it was gone, -1 = still there).
 *
 * Usage: hex-skills-floor-study --world NAME [--replicate R] [--arm naked|usual] [--start-week W] [--weeks N]
 *        [--adults N] [--drop N] [--keep LIST] [--out PREFIX]
 *
 * v2 (2026-10-22): --drop N strips only every Nth band (the rest of the world carries on as it is, so a naked band
 * meets normal neighbours instead of 1,200 naked ones); only the stripped bands are counted, and the by-cause table
 * is then the whole world's, mostly the intact bands', so read survival and how-well-fed only. --keep LIST (comma list of
 * clothes, fire, shelter, spears, skills, plants, stores, goods) leaves those as they were when stripping the rest.
 */
fun main(args: Array<String>) {
    val a = Args(args, "hex-skills-floor-study --world NAME [--replicate R] [--arm naked|usual] [--start-week W] [--weeks N] [--adults N] [--drop N] [--bare-neighbours] [--keep LIST] [--out PREFIX]")
    val world = a.string("--world") ?: error("--world NAME (a settled test world, e.g. storm-1)")
    val replicate = a.long("--replicate", 1L)
    val naked = (a.string("--arm") ?: "naked") == "naked"
    val startWeek = a.int("--start-week", 0)
    val weeks = a.int("--weeks", 52)
    val adults = a.int("--adults", 0)
    val out = a.string("--out")
    val drop = a.int("--drop", 0)
    val bare = a.flag("--bare-neighbours")
    val keeps = listOf("clothes", "fire", "shelter", "spears", "skills", "plants", "stores", "goods")
    val keep = (a.string("--keep") ?: "").split(',').filter { it.isNotBlank() }.toSet()
    require(keep.all { it in KEEPS }) { "--keep: some of $keeps" }
    a.done()

    val run = FaunaCheckOptions.parse(arrayOf("--world", world, "--replicate", "$replicate")).start()
    val people = Catalogue.HUMAN
    val prefix = "${people.name}:"
    repeat(startWeek) { run.step() }

    val biomeOf = run.hw.placeBiome
    val nBiome = Biome.entries.size
    val start = DoubleArray(nBiome)
    class Band0(val id: Int, val biome: Int, val size: Int, var gone: Int = -1)
    val bands = ArrayList<Band0>()
    var seen = 0
    for (g in run.groups.groups) if (g.alive && g.species == people) {
        if (drop > 0 && seen++ % drop != 0) {
            // (--bare-neighbours: the bands left alone have no larder or cache, so nothing to share, give or visit for.)
            if (bare) { g.stores.fill(0f); g.band?.cache?.fill(0f) }
            continue
        }
        if (adults > 0 && g.adults > adults) { g.young = (g.young.toDouble() * adults / g.adults).toInt(); g.adults = adults }
        g.tag = biomeOf[g.place]; start[g.tag] += g.size.toDouble()
        bands += Band0(g.id, g.tag, g.size)
        if (naked) g.band?.let { b ->
            val kept = keptOf(keep, g, b)
            b.skills.fill(0f)
            for (s in Skill.entries) b.knowsSkill[s] = s.known
            b.plants.fill(0f); b.fire = 0f; b.shelter = 0f; b.known = LongArray(0); b.kit.fill(0f); b.knows.fill(false); b.goods.fill(0f); b.cache.fill(0f)
            b.house = 0f; b.houseHex = Hex.NONE; b.palisade = 0f; b.ornaments = 0f; b.exotic = 0f
            g.stores.fill(0f)
            g.memory.hex.fill(-1); g.band?.seasonPlace?.fill(-1)
            kept()
        }
    }
    val causes = sortedSetOf<String>()
    val weekly = ArrayList<Triple<Int, DoubleArray, Pair<Double, Map<String, Int>>>>()   // week, tagged alive by biome, (all alive, causes)
    val fedByWeek = DoubleArray(weeks + 1)
    val tallied = listOf(Met.HUNGRY_BANDS_FED_BY_KIN, Met.FOOD_GIVEN, Met.LARDERS_RAIDED, Met.CACHES_ROBBED, Met.BANDS_MET)
    val meetings = Array(weeks + 1) { IntArray(tallied.size) }
    for (w in 1..weeks) {
        run.step()
        for ((i, m) in tallied.withIndex()) meetings[w][i] = run.groups.stats.meetings[m.key] ?: 0
        val now = DoubleArray(nBiome); var all = 0.0; var fedSum = 0.0
        for (g in run.groups.groups) if (g.alive && g.species == people && (drop == 0 || g.tag >= 0)) {
            all += g.size; fedSum += g.fed.toDouble() * g.size
            if (g.tag >= 0) now[g.tag] += g.size.toDouble()
        }
        for (b in bands) if (b.gone < 0 && run.groups.groups.none { it.id == b.id && it.alive }) b.gone = w
        val dead = HashMap<String, Int>()
        for ((k, v) in run.groups.stats.deaths) if (k.text.startsWith(prefix)) { dead[k.text.removePrefix(prefix)] = v; causes += k.text.removePrefix(prefix) }
        fedByWeek[w] = fedSum / all.coerceAtLeast(1.0)
        weekly += Triple(w, now, all to dead)
    }

    val marks = listOf(1, 2, 4, 8, 13, 26, 52).filter { it <= weeks }
    println("skills floor study, world $world, replicate $replicate, arm ${if (naked) "naked" else "usual"}, drop ${if (drop > 0) "1 in $drop" else "all"}, keep ${keep.sorted().ifEmpty { listOf("nothing") }}, start week $startWeek (${run.weather.date}), adults cap ${if (adults > 0) adults else "none"}")
    println("%-24s %7s  ".format("country", "people") + marks.joinToString("  ") { "%5s".format("wk $it") })
    for (b in Biome.entries.indices.sortedByDescending { start[it] }) {
        if (start[b] < 1.0) continue
        println("%-24s %7.0f  %s".format(Biome.entries[b].name.lowercase().replace('_', ' '), start[b],
            marks.joinToString("  ") { m -> "%4.0f%%".format(100.0 * (weekly[m - 1].second[b]) / start[b]) }))
    }
    println("%-24s %7.0f  %s".format("all (tagged)", start.sum(), marks.joinToString("  ") { m -> "%4.0f%%".format(100.0 * weekly[m - 1].second.sum() / start.sum()) }))
    println("%-24s %7s  %s".format("all alive, tagged or not", "", marks.joinToString("  ") { m -> "%4.0f%%".format(100.0 * weekly[m - 1].third.first / start.sum()) }))
    println("%-24s %7s  %s".format("how well fed", "", marks.joinToString("  ") { m -> "%4.0f%%".format(100.0 * fedByWeek[m]) }))
    println("whole world's meetings, weeks 1-4 | 5-13 | 14-52 (all bands, not only the stripped)")
    for ((i, m) in tallied.withIndex()) println("%-24s %6d | %6d | %6d".format(m.label, (1..4).sumOf { meetings[it][i] }, (5..13).filter { it <= weeks }.sumOf { meetings[it][i] }, (14..52).filter { it <= weeks }.sumOf { meetings[it][i] }))
    println("deaths of people by cause, weeks 1-4 | 5-13 | 14-52 (heads)")
    for (c in causes) {
        fun sum(range: IntRange) = range.filter { it <= weeks }.sumOf { weekly[it - 1].third.second[c] ?: 0 }
        println("%-24s %6d | %6d | %6d".format(c, sum(1..4), sum(5..13), sum(14..52)))
    }

    out?.let { p ->
        File("$p-weekly.csv").absoluteFile.parentFile?.mkdirs()
        File("$p-weekly.csv").printWriter().use { f ->
            f.println("week,biome,alive_tagged,alive_all,fed")
            for ((w, now, rest) in weekly) for (b in 0 until nBiome) if (start[b] >= 1.0)
                f.println("$w,${Biome.entries[b].name.lowercase()},${now[b]},${rest.first},${fedByWeek[w]}")
        }
        File("$p-deaths.csv").printWriter().use { f ->
            f.println("week,cause,heads")
            for ((w, _, rest) in weekly) for ((c, n) in rest.second) f.println("$w,$c,$n")
        }
        File("$p-meetings.csv").printWriter().use { f ->
            f.println("week," + tallied.joinToString(",") { it.label.replace(' ', '_') })
            for (w in 1..weeks) f.println("$w," + meetings[w].joinToString(","))
        }
        File("$p-bands.csv").printWriter().use { f ->
            f.println("band,biome,size,gone_week")
            for (b in bands) f.println("${b.id},${Biome.entries[b.biome].name.lowercase()},${b.size},${b.gone}")
        }
    }
}

private val KEEPS = setOf("clothes", "fire", "shelter", "spears", "skills", "plants", "stores", "goods")

/** What the stripping of [b] (of group [g]) leaves of the things in [keep]: read before the wipe, the returned function puts them back. */
private fun keptOf(keep: Set<String>, g: inflation.ecology.HexGroup, b: inflation.ecology.Band): () -> Unit {
    val kit = b.kit.a.copyOf(); val knows = b.knows.a.copyOf(); val skills = b.skills.a.copyOf(); val knowsSkill = b.knowsSkill.a.copyOf()
    val goods = b.goods.a.copyOf(); val cache = b.cache.a.copyOf(); val plants = b.plants.a.copyOf(); val stores = g.stores.a.copyOf()
    val fire = b.fire; val shelter = b.shelter
    return {
        if ("clothes" in keep) { b.kit[Tool.CLOTHES] = kit[Tool.CLOTHES.ordinal]; b.knows[Tool.CLOTHES] = knows[Tool.CLOTHES.ordinal] }
        if ("spears" in keep) {
            b.kit[Tool.SPEAR] = kit[Tool.SPEAR.ordinal]; b.knows[Tool.SPEAR] = knows[Tool.SPEAR.ordinal]
            b.skills[Skill.HUNT] = skills[Skill.HUNT.ordinal]; b.knowsSkill[Skill.HUNT] = knowsSkill[Skill.HUNT.ordinal]
        }
        if ("fire" in keep) { b.fire = fire; b.skills[Skill.FIRE] = skills[Skill.FIRE.ordinal]; b.knowsSkill[Skill.FIRE] = knowsSkill[Skill.FIRE.ordinal] }
        if ("shelter" in keep) b.shelter = shelter
        if ("skills" in keep) { skills.copyInto(b.skills.a); knowsSkill.copyInto(b.knowsSkill.a) }
        if ("plants" in keep) plants.copyInto(b.plants.a)
        if ("stores" in keep) stores.copyInto(g.stores.a)
        if ("goods" in keep) { kit.copyInto(b.kit.a); knows.copyInto(b.knows.a); goods.copyInto(b.goods.a); cache.copyInto(b.cache.a) }
    }
}
