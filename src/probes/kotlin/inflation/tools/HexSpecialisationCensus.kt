package inflation.tools

import inflation.Args
import inflation.ecology.Catalogue
import inflation.ecology.Skill
import inflation.ecology.Tool
import inflation.ecology.get
import inflation.tools.fauna.FaunaCheckOptions
import java.io.File

/**
 * Do bands specialise? A DRAFT, not yet compiled. Carries on a settled test world with the bands as they are (no
 * stripping) and, every [--every] weeks for [--weeks], records for each band: how far its place is from drinking water,
 * the fish within its reach (HexWater.fishWithin, unit as the model keeps it), its gather, small game, fish and hunt
 * skills, whether it holds nets, and how well fed it is. Prints the means by distance to water, so the question "do bands
 * on rivers, lakes and coasts fish more, and do those with fish and few big game hunt less" can be read, and writes
 * every row to PREFIX-bands.csv for the finer cuts.
 *
 * Not recorded (no per-band tally exists in main): the kg a band took of each food. That is the first thing to add if
 * the picture here is unclear; see spec-small-game-and-fishing.md.
 *
 * Usage: hex-specialisation-census --world NAME [--replicate R] [--weeks N] [--every K] [--out PREFIX]
 */
fun main(args: Array<String>) {
    val a = Args(args, "hex-specialisation-census --world NAME [--replicate R] [--weeks N] [--every K] [--out PREFIX]")
    val world = a.string("--world") ?: error("--world NAME (a settled test world, e.g. storm-1)")
    val replicate = a.long("--replicate", 1L)
    val weeks = a.int("--weeks", 52)
    val every = a.int("--every", 13)
    val out = a.string("--out")
    a.done()

    val run = FaunaCheckOptions.parse(arrayOf("--world", world, "--replicate", "$replicate")).start()
    val people = Catalogue.HUMAN
    class Row(val week: Int, val waterKm: Float, val fish: Float, val gather: Float, val small: Float, val fishSkill: Float,
        val hunt: Float, val nets: Float, val fed: Float, val size: Int)
    val rows = ArrayList<Row>()
    for (w in 1..weeks) {
        run.step()
        if (w % every != 0) continue
        for (g in run.groups.groups) if (g.alive && g.species == people) {
            val b = g.band ?: continue
            rows += Row(w, run.land.drink.waterKm[g.place.i], run.fauna.water.fishWithin(run.hw.placeHex[g.place], 1f),
                b.skills[Skill.GATHER], b.skills[Skill.SMALL_GAME], b.skills[Skill.FISH], b.skills[Skill.HUNT], b.kit[Tool.NET],
                g.fed, g.size)
        }
    }
    val classes = listOf("water within 1 km" to { r: Row -> r.waterKm < 1f }, "1-3 km" to { r: Row -> r.waterKm in 1f..3f },
        "3-10 km" to { r: Row -> r.waterKm > 3f && r.waterKm <= 10f }, "over 10 km" to { r: Row -> r.waterKm > 10f })
    println("specialisation census, world $world, replicate $replicate, every $every weeks for $weeks, bands weighted by heads")
    println("%-20s %6s %8s %8s  %7s %7s %7s %7s  %6s %6s".format("distance to water", "bands", "heads", "fish", "gather", "small", "fish", "hunt", "nets", "fed"))
    for ((name, test) in classes) {
        val rs = rows.filter(test); if (rs.isEmpty()) continue
        val heads = rs.sumOf { it.size }.toDouble()
        fun mean(f: (Row) -> Float) = rs.sumOf { f(it) * it.size.toDouble() } / heads
        println("%-20s %6d %8.0f %8.1f  %7.2f %7.2f %7.2f %7.2f  %6.2f %6.2f".format(name, rs.size, heads, mean { it.fish },
            mean { it.gather }, mean { it.small }, mean { it.fishSkill }, mean { it.hunt }, mean { it.nets }, mean { it.fed }))
    }
    // The same bands cut by the fish within reach (thirds of the bands, by heads).
    val sorted = rows.sortedBy { it.fish }
    val third = sorted.size / 3
    println("by fish within reach (thirds of band-weeks):")
    for ((name, rs) in listOf("least fish" to sorted.take(third), "middle" to sorted.drop(third).take(third), "most fish" to sorted.drop(2 * third))) {
        if (rs.isEmpty()) continue
        val heads = rs.sumOf { it.size }.toDouble()
        fun mean(f: (Row) -> Float) = rs.sumOf { f(it) * it.size.toDouble() } / heads
        println("%-20s %6d %8.0f %8.1f  %7.2f %7.2f %7.2f %7.2f  %6.2f %6.2f".format(name, rs.size, heads, mean { it.fish },
            mean { it.gather }, mean { it.small }, mean { it.fishSkill }, mean { it.hunt }, mean { it.nets }, mean { it.fed }))
    }
    out?.let { p ->
        File("$p-bands.csv").absoluteFile.parentFile?.mkdirs()
        File("$p-bands.csv").printWriter().use { f ->
            f.println("week,water_km,fish_in_reach,gather,small_game,fish,hunt,nets,fed,size")
            for (r in rows) f.println("${r.week},${r.waterKm},${r.fish},${r.gather},${r.small},${r.fishSkill},${r.hunt},${r.nets},${r.fed},${r.size}")
        }
    }
}
