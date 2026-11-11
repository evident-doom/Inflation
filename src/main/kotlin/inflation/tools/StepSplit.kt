package inflation.tools

import inflation.Args
import inflation.ecology.Fed
import inflation.ecology.HexHistory
import inflation.ecology.HexSim
import inflation.ecology.HexSnapshot
import inflation.learning.LearnedModel
import inflation.run.TestWorlds
import inflation.tools.fauna.FaunaCheckOptions
import inflation.world.WorldCache
import java.io.File
import java.nio.file.Files
import kotlin.math.abs

/**
 * Whether a span of days comes out the same in one long step as in many short ones (the variable step's aim: the same world
 * gives the same results at any step length, design/2026-11-07-variable-step-plan.md in the project files).
 *
 * A test world is loaded and carried on at the 6-day step. At each of [offsets] days in, it is saved, and the next [days] days are
 * played from that save at each step length: the 6-day step, each of [lengths], the 6-day step under other weather drawn at the
 * split (how far the span swings anyway), and the 6-day step carried on in memory without the save (whether a save and load
 * changes anything). The weather, water and plants keep their 6-day tick at every length, so with the same weather what differs
 * between the lengths is only the slow parts. Every series of [HexHistory] is compared: events (deaths, births, kills) summed over
 * the span, states (head counts) at its end. The lines that differ from the 6-day step by more than the other weather does are the
 * processes to look at.
 *
 * Usage: step-split --world NAME [--replicate R] [--offsets 0,120,240] [--days 60] [--lengths 12,30,60] [--min 20] [--out FILE]
 */
fun main(args: Array<String>) {
    val a = Args(args, "step-split --world NAME [--replicate R] [--offsets 0,120,240] [--days 60] [--lengths 12,30,60] [--min 20] [--out FILE]")
    val entry = TestWorlds.named(requireNotNull(a.string("--world")) { "step-split needs --world NAME" })
    val replicate = a.long("--replicate", 1L)
    val offsets = (a.string("--offsets") ?: "0,120,240").split(',').map { it.trim().toInt() }.sorted()
    val days = a.int("--days", 60)
    val lengths = (a.string("--lengths") ?: "12,30,60").split(',').map { it.trim().toInt() }
    val least = a.int("--min", 20)
    val outFile = a.file("--out")
    a.done()
    val tick = entry.config.daysPerStep
    require(days % tick == 0 && lengths.all { days % it == 0 && it % tick == 0 }) { "every length must be whole ticks of $tick days and divide --days $days" }
    require(offsets.all { it % tick == 0 }) { "offsets must be whole ticks of $tick days" }
    require(offsets.zipWithNext().all { (x, y) -> y - x >= days }) { "offsets must lie at least --days $days apart" }

    val lines = ArrayList<String>()
    fun say(s: String) { println(s); lines += s }
    val tmp = Files.createTempDirectory("step-split").toFile().also { it.deleteOnExit() }

    // What a span came to: events summed over it, states at its end.
    fun play(sim: HexSim, length: Int): Map<String, Double> {
        sim.stepDays = length
        val h = HexHistory()
        val fauna = sim.fauna; val nk = fauna.kinds.size
        // (Which resident hunter killed which resident, and what the residents ate, over the span only.)
        for (a in fauna.killsByHunter) a.reset()
        for (ff in fauna.fedFrom) for (a in ff) a.reset()
        repeat(days / length) { sim.step(); h.record(sim) }
        val got = HashMap(h.keys().associateWith { k ->
            val s = h.series.getValue(k)
            if (h.isFlow(k) || k.startsWith(HUNT_DIAG_KEYS)) { var sum = 0.0; for (i in 0 until s.size) if (!s[i].isNaN()) sum += s[i]; sum }
            else { var v = Double.NaN; for (i in 0 until s.size) if (!s[i].isNaN()) v = s[i].toDouble(); v }
        })
        for (i in 0 until nk * nk) {
            val kills = fauna.killsByHunter[2 * i].sum() + fauna.killsByHunter[2 * i + 1].sum()
            if (kills > 0.0) got[KILLS_BY + fauna.kinds[i / nk].name + " > " + fauna.kinds[i % nk].name] = kills
        }
        for (k in 0 until nk) {
            val ff = fauna.fedFrom[k]; val heads = ff[Fed.HEADS.ordinal].sum()
            if (heads <= 0.0) continue
            for (f in listOf(Fed.PLANTS, Fed.MEAT, Fed.SMALL_PREY, Fed.CARRION, Fed.RESERVES))
                got[FED_FROM + fauna.kinds[k].name + "/" + f.name.lowercase()] = ff[f.ordinal].sum() / heads
        }
        return got
    }
    fun fork(snap: File, length: Int, reseed: Long = 0L): Map<String, Double> {
        val sim = HexSim(WorldCache.load(entry.config) { }, uniformStart = false)
        HexSnapshot.load(sim, snap)
        if (reseed != 0L) sim.weather.reseed(reseed)
        LearnedModel.fromConfig(sim.groups) { }
        return play(sim, length)
    }

    val main = FaunaCheckOptions(entry.config, 0, false, null, null, entry.state, replicate, entry).start()
    main.stepDays = tick
    say("step-split ${entry.name} replicate $replicate: $days days from each of ${offsets.joinToString(",")} days in, at $tick days and ${lengths.joinToString(",")}; save at ${main.weather.date}")
    val arms = listOf("$tick d") + lengths.map { "$it d" } + listOf("$tick d other weather", "$tick d in memory")
    val total = HashMap<String, HashMap<String, Double>>()
    var at = 0
    for (off in offsets) {
        while (at < off) { main.step(); at += tick }
        val snap = File(tmp, "at$off.bin").also { it.deleteOnExit() }
        HexSnapshot.save(main, snap)
        val date = main.weather.date
        val got = LinkedHashMap<String, Map<String, Double>>()
        got["$tick d"] = fork(snap, tick)
        for (l in lengths) got["$l d"] = fork(snap, l)
        got["$tick d other weather"] = fork(snap, tick, reseed = 1000L + replicate * 100 + off)
        got["$tick d in memory"] = play(main, tick); at += days
        snap.delete()
        for ((arm, m) in got) for ((k, v) in m) if (!v.isNaN()) total.getOrPut(arm) { HashMap() }.merge(k, v) { x, y -> x + y }
        report("from day $off ($date)", arms, got, least, ::say)
    }
    report("all spans together (events summed, states summed over the spans' ends)", arms, total, least, ::say)
    outFile?.let { f -> f.parentFile?.mkdirs(); f.writeText(lines.joinToString("\n", postfix = "\n")) }
}

/** Extra series beside [HexHistory]'s: resident hunter > resident kills over the span (heads), and each resident kind's food
 * by source as a share of its need, heads-weighted over the span's steps ([inflation.ecology.HexFauna.fedFrom]). */
private const val KILLS_BY = "kills by/"
private const val FED_FROM = "fed from/"
/** The hunt's steps counted by huntSteps=true, summed over the span (each step's counts are that step's). */
private const val HUNT_DIAG_KEYS = "hunt diag/"

/**
 * The series of [got] whose 6-day value is at least [least] (or any head count), as the 6-day value and every other arm's
 * difference from it in %; largest difference among the step lengths first.
 */
private fun report(title: String, arms: List<String>, got: Map<String, Map<String, Double>>, least: Int, say: (String) -> Unit) {
    val base = got.getValue(arms[0])
    val keys = base.keys.filter { k ->
        val v = base[k] ?: Double.NaN
        !v.isNaN() && (HexHistory.Key.animalKind(k) != null && v > 0.0 || (HexHistory.Key.isFlow(k) || k.startsWith(KILLS_BY) || k.startsWith(HUNT_DIAG_KEYS)) && v >= least ||
            k.startsWith(FED_FROM) && v >= 0.01)
    }
    fun pct(arm: String, k: String): Double { val b = base.getValue(k); val v = got[arm]?.get(k) ?: return Double.NaN; return if (b == 0.0) Double.NaN else 100.0 * (v - b) / b }
    val lengthArms = arms.drop(1).filter { it.matches(Regex("\\d+ d")) }
    val sorted = keys.sortedByDescending { k -> lengthArms.maxOfOrNull { abs(pct(it, k)).takeIf { p -> !p.isNaN() } ?: 0.0 } ?: 0.0 }
    say("")
    say("== $title")
    say("%-48s %12s".format("series", arms[0]) + arms.drop(1).joinToString("") { "%14s".format(it.replace(" other weather", " wthr").replace(" in memory", " mem")) })
    for (k in sorted) say("%-48s %12.1f".format(k.take(48), base.getValue(k)) + arms.drop(1).joinToString("") { "%13.1f%%".format(pct(it, k)) })
}
