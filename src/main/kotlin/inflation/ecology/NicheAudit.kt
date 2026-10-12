package inflation.ecology

import inflation.world.Date
import inflation.world.Simulation
import inflation.world.World
import inflation.world.WorldConfig
import java.io.File
import kotlin.math.ln
import kotlin.math.max

/**
 * Runs the living world and reports, species by species, whether it found a niche: which died
 * out (when, where it held on longest, and what killed it), where survivors live (ground, climate,
 * neighbours), and which grow without limit (and what fails to check them). The point is not to
 * keep every species alive by tuning, but to see where the world offers each a place.
 *
 * Usage: audit [--seed N] [--size KM] [--years N] [--out FILE]
 */
fun main(args: Array<String>) {
    var cfg = WorldConfig()
    var years = 30
    var out: File? = null
    var k = 0
    while (k < args.size) {
        when (args[k]) {
            "--seed" -> cfg = cfg.copy(seed = args[++k].toLong())
            "--size" -> { val s = args[++k].toInt(); cfg = cfg.copy(widthKm = s, heightKm = s) }
            "--years" -> years = args[++k].toInt()
            "--out" -> out = File(args[++k])
            else -> error("unknown argument ${args[k]}")
        }
        k++
    }
    val world = World.generate(cfg)
    val sim = Simulation(world)
    val e = sim.ecology
    val land = Catalogue.all.map { it.name }
    val sea = SeaCatalogue.all.map { it.name }
    val names = land + sea
    val counts = names.associateWith { ArrayList<Int>() }
    val causes = names.associateWith { HashMap<String, Int>() }
    val recentCauses = names.associateWith { HashMap<String, Int>() }
    val niche = names.associateWith { Niche() }
    val lastSeen = HashMap<String, String>()
    val extinctYear = HashMap<String, Int>()
    val stepsPerYear = Date.DAYS_PER_YEAR / cfg.daysPerStep

    fun count(name: String) = Catalogue.all.find { it.name == name }?.let { e.count(it) } ?: e.marine.count(SeaCatalogue.all.first { it.name == name })

    for (year in 0 until years) {
        for (s in recentCauses.values) s.clear()
        repeat(stepsPerYear) { step ->
            sim.step()
            for ((sp, n) in e.killsBy) causes.getValue(sp.name).merge("predators", n, Int::plus).also { recentCauses.getValue(sp.name).merge("predators", n, Int::plus) }
            e.killsBy.clear()
            for ((key, n) in e.deathsBy) { val (sp, cause) = key.split(":", limit = 2); causes[sp]?.merge(cause, n, Int::plus); recentCauses[sp]?.merge(cause, n, Int::plus) }
            e.deathsBy.clear()
            for ((key, n) in e.marine.deathsBy) { val (sp, cause) = key.split(":", limit = 2); causes[sp]?.merge(cause, n, Int::plus); recentCauses[sp]?.merge(cause, n, Int::plus) }
            e.marine.deathsBy.clear()
            // Sample where each species lives at midsummer and midwinter.
            if (step == stepsPerYear / 4 || step == 3 * stepsPerYear / 4) {
                for (g in e.groups) if (g.alive) {
                    val c = world.grid.index(g.x.toInt().coerceIn(0, world.grid.width - 1), g.y.toInt().coerceIn(0, world.grid.height - 1))
                    niche.getValue(g.species.name).add(sim, c, g.size.toDouble())
                    lastSeen[g.species.name] = "${world.biome[c].name.lowercase().replace('_', ' ')} at %.0f m".format(world.elevation[c])
                }
                for (g in e.marine.groups) if (g.alive) {
                    val c = world.grid.index(g.x.toInt().coerceIn(0, world.grid.width - 1), g.y.toInt().coerceIn(0, world.grid.height - 1))
                    niche.getValue(g.species.name).addSea(e.marine, c, g.size.toDouble())
                    lastSeen[g.species.name] = "%.0f km offshore".format(e.marine.shoreKm[c])
                }
            }
        }
        for (name in names) {
            val n = count(name)
            counts.getValue(name) += n
            if (n == 0 && name !in extinctYear) extinctYear[name] = year
        }
        System.err.println("year $year: " + names.joinToString { "$it ${counts.getValue(it).last()}" })
    }

    val report = StringBuilder()
    report.appendLine("# Niche audit: seed ${cfg.seed}, ${cfg.widthKm} km, $years years\n")
    report.appendLine("| Species | Start | End | Trend, last third | Verdict | Main causes of death | Where it lives (or held on) |")
    report.appendLine("| --- | --- | --- | --- | --- | --- | --- |")
    for (name in names) {
        val c = counts.getValue(name)
        val start = c.first(); val end = c.last()
        val third = c.takeLast(max(3, years / 3))
        val trend = growthPerYear(third)
        val verdict = when {
            name in extinctYear -> "died out in year ${extinctYear.getValue(name)}"
            trend > 0.05 && end > 2 * start -> "growing without limit"
            trend < -0.05 -> "declining"
            else -> "holds"
        }
        val total = causes.getValue(name).values.sum().coerceAtLeast(1)
        val top = (if (name in extinctYear) recentCauses.getValue(name).takeIf { it.isNotEmpty() } ?: causes.getValue(name) else causes.getValue(name))
            .entries.sortedByDescending { it.value }.take(3).joinToString(", ") { "${it.key} ${100 * it.value / total}%" }
        val where = if (name in extinctYear) "last seen in ${lastSeen[name] ?: "?"}" else niche.getValue(name).describe()
        report.appendLine("| $name | $start | $end | " + "%+.0f%%/yr".format(100 * trend) + " | $verdict | $top | $where |")
    }
    report.appendLine("\nSmall game at the end: ${e.game.summary()}; sea birds %.0f.".format(e.marine.birdCount()))
    print(report)
    out?.let { it.parentFile?.mkdirs(); it.writeText(report.toString()) }
}

/** Mean yearly growth rate of a series of counts (log-linear fit). */
private fun growthPerYear(c: List<Int>): Double {
    if (c.size < 2 || c.any { it <= 0 }) return if (c.lastOrNull() == 0) -1.0 else 0.0
    val xs = c.indices.map { it.toDouble() }; val ys = c.map { ln(it.toDouble()) }
    val mx = xs.average(); val my = ys.average()
    val num = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) }
    val den = xs.sumOf { (it - mx) * (it - mx) }
    return if (den == 0.0) 0.0 else kotlin.math.exp(num / den) - 1
}

/** Animal-weighted means of the places a species was seen. */
private class Niche {
    var w = 0.0; var elev = 0.0; var temp = 0.0; var cover = 0.0; var rugged = 0.0; var wet = 0.0; var shore = 0.0
    val biomes = HashMap<String, Double>()
    var sea = false

    fun add(sim: Simulation, c: Int, weight: Double) {
        val world = sim.world
        w += weight
        elev += weight * world.elevation[c]
        temp += weight * world.climate.meanTemp(c)
        cover += weight * sim.vegetation.canopy(c)
        rugged += weight * world.terrain.ruggedness[c]
        wet += weight * sim.ecology.wetness[c]
        biomes.merge(world.biome[c].name.lowercase().replace('_', ' '), weight, Double::plus)
    }

    fun addSea(m: Marine, c: Int, weight: Double) {
        sea = true
        w += weight
        shore += weight * m.shoreKm[c].coerceAtMost(500f)
        temp += weight * m.upwelling[c]
    }

    fun describe(): String {
        if (w <= 0.0) return "?"
        if (sea) return "%.0f km offshore on average, upwelling %.2f".format(shore / w, temp / w)
        val top = biomes.entries.sortedByDescending { it.value }.take(2).joinToString(" and ") { it.key }
        return "%s; %.0f m, mean %.0f °C, canopy %.2f, rugged %.2f, wet %.2f".format(top, elev / w, temp / w, cover / w, rugged / w, wet / w)
    }
}
