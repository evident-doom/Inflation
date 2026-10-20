package inflation.tools

import inflation.run.freshSim
import inflation.Args
import inflation.ecology.Catalogue
import inflation.tools.herps.ClimateClass
import inflation.world.Date
import inflation.world.WorldConfig

/** Probe: insects month by month, by climate class, over the last [years] years. */
fun main(args: Array<String>) {
    val a = Args(args, "insect-seasons [--seed N] [--width KM] [--years N] [--show N]")
    val cfg = WorldConfig(seed = 1).let { it.copy(seed = a.long("--seed", it.seed), widthKm = a.int("--width", it.widthKm)) }
    val years = a.int("--years", 3); val show = a.int("--show", 2)
    a.done()
    val t0 = System.nanoTime()
    val run = freshSim(cfg, Catalogue.learned)
    System.err.println("world built in %.0f s".format((System.nanoTime() - t0) / 1e9))
    val land = run.land; val hw = run.hw; val n = land.n; val sl = run.fauna.small
    val meanT = FloatArray(n) { p -> (0 until 12).map { hw.placeTemp(p, it) }.average().toFloat() }
    val rain = FloatArray(n) { p -> (0 until 12).sumOf { hw.precip[it][hw.placeHex[p]].toDouble() }.toFloat() }
    val tmin = FloatArray(n) { p -> (0 until 12).minOf { hw.placeTemp(p, it) } }
    val tmax = FloatArray(n) { p -> (0 until 12).maxOf { hw.placeTemp(p, it) } }
    val classes = ClimateClass.entries.map { "${it.label} (${it.bounds})" }
    fun cls(p: Int): Int = if (land.plants.biomass(inflation.ecology.Place(p)) <= 0f) -1 else ClimateClass.of(meanT[p], rain[p]).ordinal
    val c = IntArray(n) { cls(it) }
    val q = listOf("temp C", "soil mm", "herb kg/m2", "green 0..1", "empty <10 share", "insects kg/km2", "dormant kg/km2", "biting", "herps",
        "rodents", "pollinators")
    val sum = Array(classes.size) { Array(q.size) { DoubleArray(12) } }; val area = Array(classes.size) { DoubleArray(12) }
    val steps = years * Date.DAYS_PER_YEAR / cfg.daysPerStep; val perYear = Date.DAYS_PER_YEAR / cfg.daysPerStep
    val ts = System.nanoTime()
    repeat(steps) { w ->
        run.step()
        if (w < steps - show * perYear) return@repeat
        val m = run.weather.date.month
        for (p in 0 until n) { val cc = c[p]; if (cc < 0) continue; val a = land.areaKm2[p].toDouble()
            val v = floatArrayOf(run.weather.temperature[p], land.hydro.soil[p], land.plants.herb[p], land.plants.green[p], if (sl.insects[p] < 10f) 1f else 0f,
                sl.insects[p], sl.dormantInsects[p], sl.biting[p], sl.herps[p], sl.rodents[p], sl.pollinators[p])
            for (i in v.indices) sum[cc][i][m] += v[i] * a; area[cc][m] += a }
    }
    System.err.println("stepped in %.0f s".format((System.nanoTime() - ts) / 1e9))
    for ((ci, name) in classes.withIndex()) {
        if (area[ci].sum() == 0.0) continue
        val places = (0 until n).filter { c[it] == ci }
        println("== $name: ${places.size} places, mean coldest month %.1f C, warmest %.1f C, rain %.0f mm".format(places.map { tmin[it] }.average(),
            places.map { tmax[it] }.average(), places.map { rain[it] }.average()))
        println("%-16s".format("month") + (0 until 12).joinToString("") { "%8d".format(it + 1) })
        for ((i, nm) in q.withIndex()) println("%-16s".format(nm) +
            (0 until 12).joinToString("") { m -> "%8.1f".format(if (area[ci][m] > 0) sum[ci][i][m] / area[ci][m] else Double.NaN) })
    }
}
