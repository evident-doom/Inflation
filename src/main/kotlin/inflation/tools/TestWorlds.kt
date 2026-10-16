package inflation.tools

import inflation.Config
import inflation.ecology.HexHistory
import inflation.ecology.HexSim
import inflation.ecology.HexSnapshot
import inflation.world.WorldConfig
import java.io.File
import java.util.Properties
import kotlin.math.ln

/**
 * Settled worlds kept ready for short tests: a few seeds of the full map and of smaller ones, each run
 * for some years and saved, so a test of a change starts from a world past its first years' swings
 * (the herds' slow climb from their start numbers, the plants finding their level) and needs only the
 * years the change itself takes to show. They live in ~/.inflation/test-worlds/NAME: state.bin (the
 * saved world), about.properties (how and when it was made) and settle.csv (the history of its settling).
 *
 * A smaller world is cheaper but not the same world: the 400 x 800 km map covers 32-40°N only, without
 * the cold north and the hot south, and small game crashed there when it did not on the full map. So
 * every smaller world is to be trusted only as far as [compare] shows it tracks the full map: its
 * numbers, their trends and the effect of a change, set against the spread between the full map's seeds.
 *
 * Usage:
 *   testWorld list
 *   testWorld build NAME... [--years N]       (settles each and saves it; the config file's keys apply, as in hexFauna)
 *   testWorld compare --full A.csv,B.csv[,..] [--full-variant A2.csv,B2.csv,..] NAME=BASE.csv[,VARIANT.csv] ...
 * A test then runs as hexFauna --world NAME --years N [--replicate R] [--history FILE].
 */
object TestWorlds {
    class Entry(val name: String, val seed: Long, val widthKm: Int, val heightKm: Int, val note: String) {
        val config get() = WorldConfig(seed = seed, widthKm = widthKm, heightKm = heightKm)
        val dir get() = File(root, name)
        val state get() = File(dir, "state.bin")
        val about get() = File(dir, "about.properties")
        val settle get() = File(dir, "settle.csv")
        fun info(): Properties? = about.takeIf { it.isFile }?.let { f -> Properties().also { p -> f.reader().use { p.load(it) } } }
    }

    val root = File(System.getProperty("user.home"), ".inflation/test-worlds")

    /** Years a test world is settled for unless told otherwise. */
    const val SETTLE_YEARS = 30

    val ALL = listOf(
        Entry("full-1", 1, 1000, 2000, "the full map"),
        Entry("full-2", 2, 1000, 2000, "the full map"),
        Entry("full-3", 3, 1000, 2000, "the full map"),
        Entry("strip-1", 1, 500, 2000, "half as wide, every latitude 27-45°N"),
        Entry("strip-2", 2, 500, 2000, "half as wide, every latitude 27-45°N"),
        Entry("small-1", 1, 400, 800, "a sixth of the area, 32-40°N only"),
        Entry("small-2", 2, 400, 800, "a sixth of the area, 32-40°N only"),
    )

    fun named(name: String): Entry = ALL.firstOrNull { it.name == name } ?: error("no test world $name; they are ${ALL.joinToString(", ") { it.name }}")

    /** Says what [e] is and how it was made, and warns where today's code or config differ from when it was settled. */
    fun check(e: Entry, sim: HexSim) {
        require(e.state.isFile) { "test world ${e.name} is not built yet (testWorld build ${e.name})" }
        val a = e.info() ?: Properties()
        println("test world ${e.name}: seed ${e.seed}, ${e.widthKm} x ${e.heightKm} km (${e.note}), ${sim.land.n} places; settled ${a.getProperty("years", "?")} years at commit ${a.getProperty("commit", "?")} on ${a.getProperty("built", "?")}")
        val key = "%016x".format(HexSnapshot.WorldCache.key(e.config))
        if (a.getProperty("worldKey") != key) println("WARNING: the world-making code has changed since ${e.name} was settled: its saved state is of the old land and climate. Rebuild it (testWorld build ${e.name}) before trusting a test on it.")
        val then = a.getProperty("config", ""); val now = Config.set("")
        if (then != now) println("WARNING: the config differs from when ${e.name} was settled.\n  then: ${then.ifEmpty { "(defaults)" }}\n  now:  ${now.ifEmpty { "(defaults)" }}")
    }

    fun build(e: Entry, years: Int) {
        e.dir.mkdirs()
        val building = File(e.dir, "building.bin")
        val t0 = System.nanoTime()
        faunaCheck(arrayOf("--seed", "${e.seed}", "--width", "${e.widthKm}", "--height", "${e.heightKm}", "--years", "$years", "--save", building.path, "--history", e.settle.path))
        val seconds = (System.nanoTime() - t0) / 1e9
        if (e.state.exists()) e.state.delete()
        require(building.renameTo(e.state)) { "could not move $building to ${e.state}" }
        val area = HexHistory.readCsv(e.settle).series["land/area km²"]?.let { it[it.size - 1] } ?: Float.NaN
        val p = Properties()
        p["name"] = e.name; p["seed"] = "${e.seed}"; p["widthKm"] = "${e.widthKm}"; p["heightKm"] = "${e.heightKm}"
        p["years"] = "$years"; p["commit"] = gitCommit(); p["built"] = java.time.LocalDateTime.now().withNano(0).toString()
        p["worldKey"] = "%016x".format(HexSnapshot.WorldCache.key(e.config)); p["config"] = Config.set("")
        p["landKm2"] = "%.0f".format(area); p["buildSeconds"] = "%.0f".format(seconds)
        p["secondsPerYear"] = "%.1f".format(seconds / years)
        e.about.writer().use { p.store(it, "test world ${e.name}, written by testWorld build") }
        println("test world ${e.name} settled for $years years in %.0f s (%.1f s a year, with history and saving): ${e.state} (%.0f MB)".format(seconds, seconds / years, e.state.length() / 1e6))
    }

    fun list() {
        println("%-8s %5s %11s  %-38s %s".format("name", "seed", "km", "", "settled"))
        for (e in ALL) {
            val a = e.info()
            val built = when {
                a == null || !e.state.isFile -> "not built"
                else -> {
                    val stale = a.getProperty("worldKey") != "%016x".format(HexSnapshot.WorldCache.key(e.config))
                    "%s years at %s on %s, %s s a year, %.0f MB%s".format(a.getProperty("years"), a.getProperty("commit"), a.getProperty("built"), a.getProperty("secondsPerYear"),
                        e.state.length() / 1e6, if (stale) " — WORLD CODE CHANGED SINCE: rebuild" else "")
                }
            }
            println("%-8s %5d %5d x %4d  %-38s %s".format(e.name, e.seed, e.widthKm, e.heightKm, e.note, built))
        }
    }

    // ---- how well a smaller world tracks the full map

    /** One run's history, reduced to whole years from its start (a year being 60 weeks from wherever the run began): each key number's yearly mean, or sum for flows. */
    class Run(file: File) {
        val name = file.name
        private val h = HexHistory.readCsv(file)
        private val weeksPerYear = inflation.world.Date.DAYS_PER_YEAR / WorldConfig().daysPerStep
        /** The whole years recorded, 1 for the first; a year cut short at the end is left out. */
        val years: List<Int> = (1..h.weeks / weeksPerYear).toList()
        init { require(years.isNotEmpty()) { "$file holds less than a year" } }
        private fun rows(year: Int) = (year - 1) * weeksPerYear until year * weeksPerYear
        val landKm2: Double = h.series["land/area km²"]?.let { s -> (0 until s.size).map { s[it] }.lastOrNull { !it.isNaN() }?.toDouble() } ?: Double.NaN
        val keys: List<String> get() = h.series.keys.filter { isKey(it) }

        fun yearly(key: String, year: Int): Double {
            val s = h.series[key] ?: return Double.NaN
            val v = rows(year).map { s[it].toDouble() }.filter { !it.isNaN() }
            if (v.isEmpty()) return Double.NaN
            return if (h.isFlow(key)) v.sum() else v.average()
        }
        /** The last whole year's value, per 1000 km² of land for heads, per km² for biomass (tonnes). */
        fun density(key: String): Double = scale(key) * yearly(key, years.last())
        /** The yearly change from the first whole year to the last, %. */
        fun trend(key: String): Double {
            val a = yearly(key, years.first()); val b = yearly(key, years.last())
            return if (a > 0 && b > 0 && years.size > 1) 100 * ln(b / a) / (years.last() - years.first()) else Double.NaN
        }
        private fun scale(key: String) = when {
            key.startsWith("animals/") -> 1000 / landKm2
            key.startsWith("biomass/") -> 1000 / landKm2
            else -> 1.0
        }
    }

    private fun isKey(k: String) = k.startsWith("animals/") || k in setOf("biomass/herbs", "biomass/shrubs", "biomass/trees", "biomass/small animals", "weather/temperature °C", "weather/rain mm")

    /** The change a variant made against its base in the last whole year, %. */
    private fun effect(base: Run, variant: Run, key: String): Double {
        val a = base.yearly(key, base.years.last()); val b = variant.yearly(key, variant.years.last())
        return if (a > 0 && b > 0) 100 * ln(b / a) else Double.NaN
    }

    /**
     * Sets each test world's runs beside the full map's: for every key number, the full map's seeds'
     * range and the test world's value, marked in or out of that range; for trends and effects also
     * whether it goes the same way, where all the full map's seeds agree on the way.
     */
    fun compare(full: List<Run>, fullVariant: List<Run>, tests: List<Triple<String, Run, Run?>>) {
        require(full.isNotEmpty()) { "compare needs at least one full-map run (--full)" }
        require(fullVariant.isEmpty() || fullVariant.size == full.size) { "--full-variant needs one run for each --full run, in the same order" }
        if (full.size < 2) println("(only one full-map run: its 'range' is a single value, so nearly everything reads as out of it; give two or more seeds)")
        val keys = full.first().keys.filter { k -> full.all { it.density(k) > 0 || k.startsWith("weather/") } }
        println("full map: ${full.joinToString(", ") { "${it.name} (${it.years.size} years, land %.0f km²)".format(it.landKm2) }}")
        for ((name, base, variant) in tests) {
            println()
            println("== $name: ${base.name}${variant?.let { " against ${it.name}" } ?: ""} (${base.years.size} years, land %.0f km²)".format(base.landKm2))
            println("%-24s | %-28s | %-28s | %s".format("", "level (per 1000 km² of land)", "trend (% a year)", if (variant != null && fullVariant.isNotEmpty()) "effect of the variant (%)" else ""))
            println("%-24s | %-20s %7s | %-20s %7s | %s".format("", "full: mean [range]", "here", "full: mean [range]", "here", if (variant != null && fullVariant.isNotEmpty()) "%-20s %7s".format("full: mean [range]", "here") else ""))
            var nLevel = 0; var inLevel = 0; var nTrend = 0; var inTrend = 0; var nWay = 0; var sameWay = 0; var nEff = 0; var inEff = 0; var nEffWay = 0; var sameEffWay = 0
            val missing = ArrayList<String>()
            for (k in keys) {
                val label = k.substringAfter('/').take(24)
                val d = base.density(k)
                if (k.startsWith("animals/") && !(d > 0)) { missing += label; continue }
                fun cell(ref: List<Double>, v: Double, counts: (Boolean) -> Unit, way: ((Boolean) -> Unit)?): String {
                    val r = ref.filter { !it.isNaN() }
                    if (r.isEmpty() || v.isNaN()) return "%-28s".format("-")
                    val lo = r.min(); val hi = r.max()
                    val inside = v in lo..hi
                    counts(inside)
                    var mark = if (inside) " " else if (v < lo) "↓" else "↑"
                    if (way != null && (lo > 0 || hi < 0)) { val same = (v > 0) == (lo > 0); way(same); if (!same) mark = "✗" }
                    return "%-20s %6s%s".format("%s [%s, %s]".format(fmt(r.average()), fmt(lo), fmt(hi)), fmt(v), mark)
                }
                val level = cell(full.map { it.density(k) }, d, { nLevel++; if (it) inLevel++ }, null)
                val trend = cell(full.map { it.trend(k) }, base.trend(k), { nTrend++; if (it) inTrend++ }, { nWay++; if (it) sameWay++ })
                val eff = if (variant != null && fullVariant.isNotEmpty())
                    cell(full.indices.map { effect(full[it], fullVariant[it], k) }, effect(base, variant, k), { nEff++; if (it) inEff++ }, { nEffWay++; if (it) sameEffWay++ }) else ""
                println("%-24s | %s | %s | %s".format(label, level, trend, eff))
            }
            if (missing.isNotEmpty()) println("missing here (present on the full map): ${missing.joinToString(", ")}")
            println("-> $name: level in the full map's range for $inLevel of $nLevel, trend in range for $inTrend of $nTrend and the same way for $sameWay of $nWay where the full map's seeds agree" +
                (if (nEff > 0) "; the variant's effect in range for $inEff of $nEff and the same way for $sameEffWay of $nEffWay" else "") + "; ${missing.size} kinds missing")
        }
        println("\nmarks: ↑ ↓ above or below the full map's range; ✗ the other way from all the full map's seeds")
    }

    private fun fmt(v: Double): String = when {
        v.isNaN() -> "-"
        kotlin.math.abs(v) >= 100 -> "%.0f".format(v)
        kotlin.math.abs(v) >= 10 -> "%.1f".format(v)
        else -> "%.2f".format(v)
    }
}

fun main(args: Array<String>) {
    when (args.getOrNull(0)) {
        "list" -> TestWorlds.list()
        "build" -> {
            var years = TestWorlds.SETTLE_YEARS
            val names = ArrayList<String>()
            var k = 1
            while (k < args.size) { when (args[k]) { "--years" -> years = args[++k].toInt(); else -> names += args[k] }; k++ }
            require(names.isNotEmpty()) { "build which? ${TestWorlds.ALL.joinToString(", ") { it.name }}" }
            for (n in names) TestWorlds.build(TestWorlds.named(n), years)
        }
        "compare" -> {
            var full = emptyList<TestWorlds.Run>(); var fullVariant = emptyList<TestWorlds.Run>()
            val tests = ArrayList<Triple<String, TestWorlds.Run, TestWorlds.Run?>>()
            fun runs(s: String) = s.split(',').filter { it.isNotBlank() }.map { TestWorlds.Run(File(it)) }
            var k = 1
            while (k < args.size) {
                when {
                    args[k] == "--full" -> full = runs(args[++k])
                    args[k] == "--full-variant" -> fullVariant = runs(args[++k])
                    '=' in args[k] -> { val (n, files) = args[k].split('=', limit = 2); val r = runs(files); tests += Triple(n, r[0], r.getOrNull(1)) }
                    else -> error("unknown argument ${args[k]}")
                }
                k++
            }
            TestWorlds.compare(full, fullVariant, tests)
        }
        else -> println("usage: testWorld list | testWorld build NAME... [--years N] | testWorld compare --full A.csv,B.csv [--full-variant A2.csv,B2.csv] NAME=BASE.csv[,VARIANT.csv] ...\n" +
            "a test on a settled world: hexFauna --world NAME --years N [--replicate R] [--history FILE]")
    }
}
