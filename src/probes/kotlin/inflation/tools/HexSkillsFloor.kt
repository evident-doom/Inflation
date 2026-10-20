package inflation.tools

import inflation.run.freshSim
import inflation.Args
import inflation.ecology.Hex
import inflation.ecology.Catalogue
import inflation.world.Biome
import inflation.world.Date
import inflation.world.WorldConfig
import inflation.ecology.Skill
import inflation.ecology.get
import inflation.ecology.set
import inflation.ecology.contains
import inflation.ecology.Place
import inflation.ecology.hexOf
import inflation.ecology.m2

/**
 * The skills floor: how long people last who start with nothing. Every band is stripped of its
 * skills, tools, materials, stores, caches and memory of the land, as unprepared people dropped into
 * the wild; a twin world keeps its bands as they are. People are followed by the kind of country
 * they started in, week by week, against the record (cold and wet kills the unclothed in hours to a
 * night, thirst in about three days, hunger in three to eight weeks; in the warm tropics people
 * scrape by for weeks, losing weight).
 *
 * Weeks are the model's 6-day steps; the run starts at the end of winter.
 *
 * Usage: hex-skills-floor [--seed N] [--size KM] [--weeks N]
 */
fun main(args: Array<String>) {
    val a = Args(args, "hex-skills-floor [--seed N] [--size KM] [--weeks N]")
    var cfg = WorldConfig(seed = 1)
    cfg = cfg.copy(seed = a.long("--seed", cfg.seed))
    val size = a.int("--size", 600)
    val weeks = a.int("--weeks", 52)
    a.done()
    cfg = cfg.copy(widthKm = size, heightKm = size)
    val kinds = Catalogue.all
    val people = Catalogue.HUMAN
    val marks = listOf(1, 2, 4, 8, 13, 26, 52).filter { it <= weeks }
    val results = listOf(true, false).parallelStream().map { naked ->
        val run = freshSim(cfg, kinds)
        val biomeOf = run.hw.placeBiome
        val start = DoubleArray(Biome.entries.size)
        for (g in run.groups.groups) if (g.alive && g.species == people) {
            g.tag = biomeOf[g.place]; start[g.tag] += g.size.toDouble()
            if (naked) g.band?.let { b ->
                b.skills.fill(0f)
                for (s in Skill.entries) b.knowsSkill[s] = s.known
                b.plants.fill(0f); b.fire = 0f; b.shelter = 0f; b.known = LongArray(0); b.kit.fill(0f); b.knows.fill(false); b.goods.fill(0f); b.cache.fill(0f)
                b.house = 0f; b.houseHex = Hex.NONE; b.palisade = 0f; b.ornaments = 0f; b.exotic = 0f
                g.stores.fill(0f)
                g.memory.hex.fill(-1); g.seasonPlace.fill(-1)
            }
        }
        val alive = HashMap<Int, DoubleArray>()
        val fed = HashMap<Int, Double>()

        for (w in 1..weeks) {
            run.step()
            if (w in marks) {
                val now = DoubleArray(Biome.entries.size)
                for (g in run.groups.groups) if (g.alive && g.species == people && g.tag >= 0) now[g.tag] += g.size.toDouble()
                alive[w] = now
                val ps = run.groups.groups.filter { it.alive && it.species == people && it.tag >= 0 }
                fed[w] = ps.sumOf { it.fed.toDouble() * it.size } / ps.sumOf { it.size }.coerceAtLeast(1)
            }
        }
        Triple(naked, start, alive) to fed
    }.toList()
    println("skills floor, seed %d, %d km, starting %s: people left (share of those who started there), naked | as they are".format(
        cfg.seed, size, Date(Date.START_DAY.toLong())))
    val nakedFed = results.first { it.first.first }.second; val usualFed = results.first { !it.first.first }.second
    val naked = results.first { it.first.first }.first; val usual = results.first { !it.first.first }.first
    println("%-24s %7s  ".format("country", "people") + marks.joinToString("  ") { "%9s".format("wk $it") })
    for (b in Biome.entries.indices.sortedByDescending { naked.second[it] }) {
        if (naked.second[b] < 1.0) continue
        val cells = marks.joinToString("  ") { w -> "%3.0f%%|%3.0f%%".format(100.0 * (naked.third[w]?.get(b) ?: 0.0) / naked.second[b],
            100.0 * (usual.third[w]?.get(b) ?: 0.0) / usual.second[b].coerceAtLeast(1.0)) }
        println("%-24s %7.0f  %s".format(Biome.entries[b].name.lowercase().replace('_', ' '), naked.second[b], cells))
    }
    val all = marks.joinToString("  ") { w -> "%3.0f%%|%3.0f%%".format(100.0 * (naked.third[w]?.sum() ?: 0.0) / naked.second.sum(),
        100.0 * (usual.third[w]?.sum() ?: 0.0) / usual.second.sum().coerceAtLeast(1.0)) }
    println("%-24s %7.0f  %s".format("all", naked.second.sum(), all))
    println("%-24s %7s  %s".format("how well fed", "",
        marks.joinToString("  ") { w -> "%3.0f%%|%3.0f%%".format(100.0 * (nakedFed[w] ?: 0.0), 100.0 * (usualFed[w] ?: 0.0)) }))
}
